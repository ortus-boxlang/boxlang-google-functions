/**
 * [BoxLang]
 *
 * Copyright [2023] [Ortus Solutions, Corp]
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ortus.boxlang.runtime.gcp;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import com.google.cloud.functions.HttpFunction;
import com.google.cloud.functions.HttpRequest;
import com.google.cloud.functions.HttpResponse;

import ortus.boxlang.runtime.application.BaseApplicationListener;
import ortus.boxlang.runtime.BoxRuntime;
import ortus.boxlang.runtime.context.IBoxContext;
import ortus.boxlang.runtime.context.RequestBoxContext;
import ortus.boxlang.runtime.context.ScriptingRequestBoxContext;
import ortus.boxlang.runtime.dynamic.casters.StringCaster;
import ortus.boxlang.runtime.dynamic.casters.StructCaster;
import ortus.boxlang.runtime.gcp.util.KeyDictionary;
import ortus.boxlang.runtime.interop.DynamicObject;
import ortus.boxlang.runtime.runnables.IClassRunnable;
import ortus.boxlang.runtime.runnables.RunnableLoader;
import ortus.boxlang.runtime.scopes.Key;
import ortus.boxlang.runtime.types.Array;
import ortus.boxlang.runtime.types.exceptions.AbortException;
import ortus.boxlang.runtime.types.exceptions.ExceptionUtil;
import ortus.boxlang.runtime.types.IStruct;
import ortus.boxlang.runtime.types.Struct;
import ortus.boxlang.runtime.types.util.JSONUtil;
import ortus.boxlang.runtime.util.FileSystemUtil;
import ortus.boxlang.runtime.util.ResolvedFilePath;

/**
 * The BoxLang Google Cloud Functions Runner.
 * <p>
 * This class is the entry point for the GCF Java HTTP runtime. It implements
 * Google's {@link HttpFunction} interface and handles the full lifecycle of a
 * BoxLang request:
 * <ol>
 * <li><strong>Cold start</strong> – The static initializer starts the BoxLang
 * runtime exactly once per container instance via the static initializer.</li>
 * <li><strong>Request mapping</strong> – {@link RequestMapper} converts the
 * incoming {@link HttpRequest} into a BoxLang event struct whose shape mirrors
 * the AWS API Gateway v2.0 event for cross-platform compatibility.</li>
 * <li><strong>Route resolution</strong> – {@link #resolveRoute} looks up the URI
 * path against a routing table built once at cold start, from manifest.json, the
 * handlers/ directory, or (for backward compatibility) the function root. Falls
 * back to the default handler when no route matches.</li>
 * <li><strong>Compilation &amp; caching</strong> – {@link #loadHandler} compiles
 * the resolved {@code .bx} file and caches it for warm invocations.</li>
 * <li><strong>Execution</strong> – The compiled class is invoked with the
 * convention {@code run(event, context, response)}. An optional
 * {@code x-bx-function} request header can route to an alternative method.</li>
 * <li><strong>Response mapping</strong> – {@link ResponseMapper} serializes the
 * BoxLang response struct to the GCF {@link HttpResponse}.</li>
 * </ol>
 *
 * <h3>Handler Convention</h3>
 * <p>
 * Each {@code .bx} file must expose a {@code run} method (or the method named
 * by the {@code x-bx-function} header) with the following signature:
 *
 * <pre>
 * class {
 *     function run( event, context, response ) {
 *         response.body       = "Hello from BoxLang!"
 *         response.statusCode = 200
 *     }
 * }
 * </pre>
 *
 * <h3>Environment Variables</h3>
 * <ul>
 * <li>{@code BOXLANG_GCP_ROOT} – root directory for {@code .bx} files
 * (default: {@code /workspace})</li>
 * <li>{@code BOXLANG_GCP_CLASS} – override the default {@code Lambda.bx} path</li>
 * <li>{@code BOXLANG_GCP_DEBUGMODE} – enable verbose logging</li>
 * <li>{@code BOXLANG_GCP_CONFIG} – path to a custom {@code boxlang.json}</li>
 * <li>{@code K_SERVICE} – function name (set automatically by GCF)</li>
 * <li>{@code K_REVISION} – function revision (set automatically by GCF)</li>
 * <li>{@code GOOGLE_CLOUD_PROJECT} – GCP project ID</li>
 * </ul>
 */
public class FunctionRunner implements HttpFunction {

	// =========================================================================
	// Constants
	// =========================================================================

	/**
	 * The default root directory for {@code .bx} files on Google Cloud Functions
	 * (Gen2). Used when {@code BOXLANG_GCP_ROOT} is not set.
	 */
	public static final String							DEFAULT_FUNCTION_ROOT	= "/workspace";

	/**
	 * The header clients may send to route the request to a specific method
	 * within the resolved {@code .bx} class.
	 */
	protected static final Key							BOXLANG_FUNCTION_HEADER	= Key.of( "x-bx-function" );

	/**
	 * The default method executed when no {@code x-bx-function} header is present.
	 */
	protected static final Key							DEFAULT_FUNCTION_METHOD	= Key.run;

	/**
	 * The subdirectory, relative to the function root, where registered handler
	 * classes live. Only files under this directory (or listed in manifest.json)
	 * are ever eligible URI-routing targets.
	 */
	protected static final String						HANDLERS_DIR			= "handlers";

	/**
	 * The build-time-generated routing manifest, relative to the function root.
	 * When present and valid, this is the sole source of truth for URI routing:
	 * no filesystem scanning happens at request time or startup.
	 */
	protected static final String						MANIFEST_FILE			= "manifest.json";

	// =========================================================================
	// Static state (shared across warm invocations)
	// =========================================================================

	/**
	 * Logger used for lifecycle messages that should not appear unconditionally
	 * on stdout in production (e.g. shutdown hook). Uses JUL FINE level so output
	 * is suppressed unless the JUL handler is configured to show FINE or lower.
	 */
	private static final Logger							logger					= Logger.getLogger( FunctionRunner.class.getName() );

	/**
	 * The BoxLang runtime singleton, initialized once at cold-start.
	 */
	protected static final BoxRuntime					runtime;

	/**
	 * Thread-safe cache of compiled BoxLang class instances, keyed by absolute
	 * file path string. Populated atomically on first access per path.
	 */
	private static final Map<String, IClassRunnable>	classCache				= new ConcurrentHashMap<>();

	static {
		Map<String, String>	env				= System.getenv();
		boolean				debugMode		= Boolean.parseBoolean( env.getOrDefault( "BOXLANG_GCP_DEBUGMODE", "false" ) );
		String				functionRoot	= env.getOrDefault( "BOXLANG_GCP_ROOT", DEFAULT_FUNCTION_ROOT );
		String				configPath		= null;

		// Explicit config path wins; otherwise auto-detect boxlang.json in the function root.
		if ( env.get( "BOXLANG_GCP_CONFIG" ) != null ) {
			configPath = env.get( "BOXLANG_GCP_CONFIG" );
		} else if ( Path.of( functionRoot, "boxlang.json" ).toFile().exists() ) {
			configPath = Path.of( functionRoot, "boxlang.json" ).toString();
		}

		// Log the resolved configuration on cold start when debug mode is enabled.
		if ( debugMode ) {
			System.out.println( "[BoxLang GCP] Initializing runtime (function root: " + functionRoot + ")" );
			if ( configPath != null ) {
				System.out.println( "[BoxLang GCP] Using config: " + configPath );
			}
		}

		// Use the system temp directory as BoxLang's working directory since
		// the /workspace filesystem on GCF is read-only during execution.
		runtime = BoxRuntime.getInstance( debugMode, configPath, System.getProperty( "java.io.tmpdir" ) ).waitForStart();

		// Gracefully shut down the runtime when GCF terminates the container.
		Runtime.getRuntime().addShutdownHook( new Thread( () -> {
			System.out.println( "[BoxLang GCP] ShutdownHook triggered — shutting down runtime..." );
			runtime.shutdown();
			System.out.println( "[BoxLang GCP] Runtime shut down cleanly." );
		} ) );
	}

	// =========================================================================
	// Instance state (one instance per container, reused across warm calls)
	// =========================================================================

	/**
	 * The absolute path to the default BoxLang handler file ({@code Lambda.bx}).
	 */
	protected Path						defaultFunctionPath;

	/**
	 * The root directory that contains the deployed {@code .bx} class files.
	 */
	protected String					functionRoot;

	/**
	 * Whether to emit verbose diagnostic output.
	 */
	protected boolean					debugMode;

	/**
	 * URI-routing table: route key (lowercase, "/"-joined path segments, e.g. "products"
	 * or "api/test") to the absolute Path of the handler class. Built once, at
	 * construction, from manifest.json when present and valid; otherwise from a
	 * one-time boot scan of the handlers/ directory, or the legacy function root as a
	 * last resort for backward compatibility. Never consulted or rebuilt from the
	 * filesystem on a per-request basis.
	 */
	protected final Map<String, Path>	handlerRoutes;

	// =========================================================================
	// Constructors
	// =========================================================================

	/**
	 * No-arg constructor required by the GCF functions-framework.
	 * Reads configuration from environment variables and delegates to the
	 * explicit constructor.
	 */
	public FunctionRunner() {
		this( resolveDefaultFunctionPath(), resolveDebugMode() );
	}

	/**
	 * Constructor for tests and local tooling — accepts explicit path and debug flag.
	 * Also serves as the delegation target for the no-arg constructor.
	 *
	 * @param functionPath The absolute path to the default {@code .bx} handler
	 * @param debugMode    {@code true} to enable verbose logging
	 */
	public FunctionRunner( Path functionPath, boolean debugMode ) {
		this.defaultFunctionPath	= functionPath;
		this.debugMode				= debugMode;

		// Derive the function root from the path when running under tests;
		// otherwise read it from the environment.
		if ( functionPath.toString().contains( "test/resources" ) ) {
			this.functionRoot = functionPath.getParent().toString();
		} else {
			this.functionRoot = System.getenv().getOrDefault( "BOXLANG_GCP_ROOT", DEFAULT_FUNCTION_ROOT );
		}

		if ( this.debugMode ) {
			System.out.println( "[BoxLang GCP] Configured with path: " + this.defaultFunctionPath );
			System.out.println( "[BoxLang GCP] Function root: " + this.functionRoot );
		}

		// Build the URI-routing table once, at construction (cold start)
		this.handlerRoutes = loadHandlerRoutes();
		if ( this.debugMode ) {
			System.out.println( "[BoxLang GCP] URI routing table (" + this.handlerRoutes.size() + " handler(s)): " + this.handlerRoutes.keySet() );
		}
	}

	// =========================================================================
	// Constructor Helpers
	// =========================================================================

	/**
	 * Resolve the default {@code .bx} handler path from environment variables.
	 * Honors {@code BOXLANG_GCP_CLASS} as an override; otherwise builds
	 * {@code Lambda.bx} under the configured function root.
	 */
	private static Path resolveDefaultFunctionPath() {
		Map<String, String>	env				= System.getenv();
		String				classOverride	= env.get( "BOXLANG_GCP_CLASS" );
		if ( classOverride != null ) {
			return Path.of( classOverride ).toAbsolutePath();
		}
		String functionRoot = env.getOrDefault( "BOXLANG_GCP_ROOT", DEFAULT_FUNCTION_ROOT );
		return Path.of( functionRoot, "Lambda.bx" );
	}

	/**
	 * Resolve the debug mode flag from environment variables.
	 */
	private static boolean resolveDebugMode() {
		return Boolean.parseBoolean( System.getenv().getOrDefault( "BOXLANG_GCP_DEBUGMODE", "false" ) );
	}

	// =========================================================================
	// HttpFunction implementation
	// =========================================================================

	/**
	 * Handle an incoming HTTP request from the GCF functions-framework.
	 * <p>
	 * This method is the single entry point for every invocation. It orchestrates
	 * all layers: request mapping, route resolution, class compilation/caching,
	 * BoxLang execution, and response writing.
	 *
	 * @param request  The GCF HTTP request
	 * @param response The GCF HTTP response
	 *
	 * @throws Exception If a fatal error occurs that the BoxLang error handler
	 *                   cannot recover from
	 */
	@Override
	public void service( HttpRequest request, HttpResponse response ) throws Exception {
		long startTime = System.currentTimeMillis();

		if ( debugMode ) {
			System.out.println( "[BoxLang GCP] Incoming " + request.getMethod() + " " + request.getPath() );
		}

		// --- Default response struct (mirrors AWS Lambda shape) ---
		IStruct						responseStruct		= Struct.of(
		    Key.statusCode, 200,
		    Key.headers, Struct.of(
		        KeyDictionary.contentType, "application/json",
		        KeyDictionary.accessControlAllowOrigin, "*" ),
		    Key.body, "",
		    Key.cookies, new Array()
		);

		// --- Convert HTTP request to BoxLang event struct ---
		IStruct						eventStruct			= RequestMapper.toEventStruct( request );

		// --- Resolve the .bx class from the URI (falls back to Lambda.bx) ---
		Path						resolvedClassPath	= resolveRoute( request.getPath() );
		final Path					finalFunctionPath	= resolvedClassPath != null ? resolvedClassPath : this.defaultFunctionPath;
		final ResolvedFilePath		resolvedFilePath	= ResolvedFilePath.of( finalFunctionPath );
		final String				resolvedPathStr		= resolvedFilePath.absolutePath().toString();

		// --- Build BoxLang execution context ---
		ScriptingRequestBoxContext	boxContext			= new ScriptingRequestBoxContext(
		    runtime.getRuntimeContext(),
		    false
		);
		RequestBoxContext.setCurrent( boxContext );
		// Set up request threading context and application lifecycle
		boxContext.loadApplicationDescriptor( FileSystemUtil.createFileUri( resolvedPathStr ) );
		RequestBoxContext		requestContext	= boxContext.getParentOfType( RequestBoxContext.class );
		BaseApplicationListener	listener		= requestContext.getApplicationListener();

		// GCP context struct (stands in for AWS Context object)
		IStruct					gcpContext		= buildGcpContext( request );
		Throwable				errorToHandle	= null;
		Object					functionResult	= null;

		try {
			// Compile (or retrieve cached) .bx class
			IClassRunnable function = getOrCompileHandler( resolvedFilePath, boxContext, debugMode );

			// Determine which method to invoke
			Key functionMethod = getFunctionMethod( eventStruct );

			// Application lifecycle: onRequestStart
			listener.onRequestStart( boxContext, new Object[] { resolvedPathStr, eventStruct, gcpContext } );

			// Invoke the BoxLang handler method
			functionResult = function.dereferenceAndInvoke(
			    boxContext,
			    functionMethod,
			    new Object[] { eventStruct, gcpContext, responseStruct },
			    false
			);

		} catch ( AbortException e ) {

			if ( debugMode ) {
				System.out.println( "[BoxLang GCP] AbortException caught" );
			}

			try {
				listener.onAbort( boxContext, new Object[] { resolvedPathStr, eventStruct, gcpContext } );
			} catch ( Throwable ae ) {
				errorToHandle = ae;
			}

			boxContext.flushBuffer( true );

			// Re-throw custom abort causes as runtime exceptions
			if ( e.getCause() != null ) {
				Throwable cause = e.getCause();
				throw cause instanceof RuntimeException ? ( RuntimeException ) cause : new RuntimeException( cause );
			}

		} catch ( Exception e ) {
			errorToHandle = e;
		} finally {

			// Application lifecycle: onRequestEnd
			try {
				listener.onRequestEnd( boxContext, new Object[] { resolvedPathStr, eventStruct, gcpContext } );
			} catch ( Throwable e ) {
				errorToHandle = e;
			}

			boxContext.flushBuffer( false );

			// Error handling
			if ( errorToHandle != null ) {
				System.err.println( "[BoxLang GCP] Error: " + errorToHandle.getMessage() );

				try {
					if ( !listener.onError( boxContext, new Object[] { errorToHandle, "", eventStruct, gcpContext } ) ) {
						throw errorToHandle;
					}
				} catch ( Throwable t ) {
					errorToHandle.printStackTrace();
					ExceptionUtil.throwException( t );
				}
			}

			boxContext.flushBuffer( false );
		}

		// If the handler returned a value directly, place it in the body
		if ( functionResult != null ) {
			responseStruct.put( "body", functionResult );
		}

		if ( debugMode ) {
			System.out.println( "[BoxLang GCP] Execution time: " + ( System.currentTimeMillis() - startTime ) + "ms" );
		}

		// Write the BoxLang response struct to the GCF HTTP response
		ResponseMapper.write( responseStruct, response );
	}

	// =========================================================================
	// Helpers
	// =========================================================================

	/**
	 * Return a compiled {@link IClassRunnable} for the given file path, compiling
	 * and caching it on first access. Cache population is atomic — concurrent
	 * calls for the same path compile exactly once.
	 *
	 * @param resolvedPath The resolved file path of the {@code .bx} class
	 * @param context      The BoxLang execution context used for compilation
	 * @param debugMode    When {@code true}, prints a compile message on cache miss
	 *
	 * @return A ready-to-invoke {@link IClassRunnable} instance
	 */
	static IClassRunnable getOrCompileHandler( ResolvedFilePath resolvedPath, IBoxContext context, boolean debugMode ) {
		String cacheKey = resolvedPath.absolutePath().toString();

		// In debug mode, skip the cache entirely to ensure changes to .bx files are picked up immediately.
		if ( debugMode ) {
			System.out.println( "[BoxLang GCP] Compiling (no cache in debug mode): " + cacheKey );
			return ( IClassRunnable ) DynamicObject.of(
			    RunnableLoader.getInstance().loadClass( resolvedPath, context )
			).invokeConstructor( context ).getTargetInstance();
		}

		return classCache.computeIfAbsent( cacheKey, k -> ( IClassRunnable ) DynamicObject.of(
		    RunnableLoader.getInstance().loadClass( resolvedPath, context )
		).invokeConstructor( context ).getTargetInstance() );
	}

	/**
	 * Check whether a compiled class for the given absolute path is already
	 * in the cache. Primarily useful for testing cold vs. warm invocation behaviour.
	 *
	 * @param absolutePath The absolute file path string to check
	 *
	 * @return {@code true} if the class is cached
	 */
	static boolean isHandlerCached( String absolutePath ) {
		return classCache.containsKey( absolutePath );
	}

	/**
	 * Remove all entries from the compiled class cache.
	 * Intended for use in tests that need a clean slate between runs.
	 */
	static void clearHandlerCache() {
		classCache.clear();
	}

	/**
	 * Resolve a {@code .bx} class file from the given URI path, against the routing
	 * table built once at construction time. Never touches the filesystem: an
	 * unmapped path simply isn't in the map, so the caller falls back to the default
	 * handler, same as if URI routing had found nothing.
	 * <p>
	 * Nested routes are supported: "/api/test" matches a route registered as
	 * "api/test". The longest matching prefix wins, so a request to
	 * "/products/categories/electronics" still matches a flat "products" route when
	 * no more specific route is registered - preserving the original single-segment
	 * behavior for flat handlers.
	 *
	 * @param uriPath The URI path from the HTTP request (e.g. {@code /products/123})
	 *
	 * @return The absolute {@link Path} to the resolved class file, or {@code null} if no route matches
	 */
	Path resolveRoute( String uriPath ) {
		if ( uriPath == null || uriPath.isEmpty() || uriPath.equals( "/" ) ) {
			return null;
		}

		String cleanPath = uriPath.startsWith( "/" ) ? uriPath.substring( 1 ) : uriPath;
		if ( cleanPath.endsWith( "/" ) ) {
			cleanPath = cleanPath.substring( 0, cleanPath.length() - 1 );
		}

		String[] segments = cleanPath.split( "/" );
		if ( segments.length == 0 || segments[ 0 ].isEmpty() ) {
			return null;
		}

		for ( int segmentCount = segments.length; segmentCount >= 1; segmentCount-- ) {
			StringBuilder routeKey = new StringBuilder();
			for ( int i = 0; i < segmentCount; i++ ) {
				if ( i > 0 ) {
					routeKey.append( '/' );
				}
				// Strip hyphens: hyphenated URI segments map to PascalCase filenames, e.g. "user-profiles" -> "UserProfiles.bx"
				routeKey.append( segments[ i ].replace( "-", "" ).toLowerCase() );
			}
			Path match = this.handlerRoutes.get( routeKey.toString() );
			if ( match != null ) {
				if ( this.debugMode ) {
					System.out.println( "[BoxLang GCP] URI routing: " + uriPath + " → " + match );
				}
				return match;
			}
		}

		if ( this.debugMode ) {
			System.out.println( "[BoxLang GCP] URI routing: no registered handler for " + uriPath );
		}
		return null;
	}

	/**
	 * Get the URI-routing table built at construction time.
	 *
	 * @return An immutable-view map of route key to the handler's absolute Path
	 */
	public Map<String, Path> getHandlerRoutes() {
		return this.handlerRoutes;
	}

	/**
	 * Build the URI-routing table, once, at construction (cold start). Tries, in order:
	 * <ol>
	 * <li>manifest.json at the function root - the build-time-generated source of
	 * truth. No filesystem scanning happens when this is present and valid.</li>
	 * <li>A one-time scan of the handlers/ directory, if manifest.json is missing or
	 * invalid but the directory exists. Supports nested routes.</li>
	 * <li>A one-time scan of the function root itself, for backward compatibility with
	 * deployments that predate the handlers/ convention. Application.bx and the
	 * configured default handler class are always excluded as routing targets.</li>
	 * </ol>
	 * Tiers 2 and 3 log a warning listing every handler they registered, since silently
	 * discovering routable classes from the filesystem is exactly the behavior this
	 * routing table replaces - the log is there so a missing/corrupt manifest.json is
	 * never a silent surprise.
	 *
	 * @return The route key to Path map
	 */
	private Map<String, Path> loadHandlerRoutes() {
		Path manifestPath = Path.of( this.functionRoot, MANIFEST_FILE );
		if ( manifestPath.toFile().isFile() ) {
			try {
				Map<String, Path> fromManifest = parseManifest( manifestPath );
				if ( fromManifest != null ) {
					return fromManifest;
				}
			} catch ( Exception e ) {
				System.err.println(
				    "[BoxLang GCP] WARNING: " + MANIFEST_FILE + " found at " + manifestPath
				        + " but could not be parsed (" + e.getMessage() + "); falling back to a directory scan"
				);
			}
		}

		Path				handlersDir	= Path.of( this.functionRoot, HANDLERS_DIR );
		Map<String, Path>	discovered	= handlersDir.toFile().isDirectory()
		    ? scanHandlersDirectory( handlersDir, "" )
		    : scanLegacyRoot();

		System.out.println(
		    "[BoxLang GCP] WARNING: no valid " + MANIFEST_FILE + " found; scanned and registered "
		        + discovered.size() + " handler(s) at startup for auditing purposes: " + discovered.keySet()
		);
		return discovered;
	}

	/**
	 * Parse manifest.json into a route key to Path map. Only the "handlers" object is
	 * consulted; every other field is documentation for humans, not a security decision.
	 *
	 * @param manifestPath The absolute path to manifest.json
	 *
	 * @return The route key to Path map, or null if the manifest has no "handlers" object
	 *
	 * @throws IOException              If the manifest file can't be read
	 * @throws IllegalArgumentException If the manifest isn't a valid JSON object
	 */
	private Map<String, Path> parseManifest( Path manifestPath ) throws IOException {
		String	content	= new String( Files.readAllBytes( manifestPath ), StandardCharsets.UTF_8 );
		Object	parsed	= JSONUtil.fromJSON( content, true );
		if ( ! ( parsed instanceof IStruct manifest ) ) {
			throw new IllegalArgumentException( MANIFEST_FILE + " root is not a JSON object" );
		}

		Object handlersObj = manifest.get( Key.of( "handlers" ) );
		if ( ! ( handlersObj instanceof IStruct handlersStruct ) ) {
			throw new IllegalArgumentException( MANIFEST_FILE + " is missing a valid 'handlers' object" );
		}

		Map<String, Path> routes = new LinkedHashMap<>();
		for ( Key routeKey : handlersStruct.keySet() ) {
			Object entry = handlersStruct.get( routeKey );
			if ( entry instanceof IStruct entryStruct && entryStruct.get( Key.of( "file" ) ) != null ) {
				String relativeFile = entryStruct.get( Key.of( "file" ) ).toString();
				routes.put( routeKey.getName().toLowerCase(), Path.of( this.functionRoot, relativeFile ).toAbsolutePath() );
			}
		}
		return routes;
	}

	/**
	 * Recursively scan the handlers/ directory, building route keys from each file's
	 * relative path. Directory segments are taken as-is, lowercased for matching;
	 * only the leaf .bx filename is expected to be PascalCase by convention.
	 * "handlers/Api/Test.bx" and "handlers/api/Test.bx" both register as "api/test".
	 *
	 * @param dir    The directory to scan
	 * @param prefix The route-key prefix accumulated so far (empty at the top level)
	 *
	 * @return The route key to Path map for this subtree
	 */
	private Map<String, Path> scanHandlersDirectory( Path dir, String prefix ) {
		Map<String, Path>	routes	= new LinkedHashMap<>();
		File[]				entries	= dir.toFile().listFiles();
		if ( entries == null ) {
			return routes;
		}

		for ( File entry : entries ) {
			if ( entry.isDirectory() ) {
				String subPrefix = prefix.isEmpty() ? entry.getName().toLowerCase() : prefix + "/" + entry.getName().toLowerCase();
				routes.putAll( scanHandlersDirectory( entry.toPath(), subPrefix ) );
			} else if ( entry.getName().toLowerCase().endsWith( ".bx" ) ) {
				String	fileKey		= entry.getName().substring( 0, entry.getName().length() - 3 ).toLowerCase();
				String	routeKey	= prefix.isEmpty() ? fileKey : prefix + "/" + fileKey;
				routes.put( routeKey, entry.toPath().toAbsolutePath() );
			}
		}
		return routes;
	}

	/**
	 * Scan the function root itself for routable classes - the legacy, pre-handlers/
	 * convention, kept only for backward compatibility with deployments that haven't
	 * adopted the handlers/ directory yet. Flat only (no nesting), and Application.bx
	 * plus the configured default handler class are always excluded: neither is ever a
	 * legitimate URI-routing target, regardless of what's on disk.
	 *
	 * @return The route key to Path map
	 */
	private Map<String, Path> scanLegacyRoot() {
		Map<String, Path>	routes	= new LinkedHashMap<>();
		File[]				entries	= Path.of( this.functionRoot ).toFile().listFiles();
		if ( entries == null ) {
			return routes;
		}

		Set<String> reserved = reservedFileNames();
		for ( File entry : entries ) {
			String lowerName = entry.getName().toLowerCase();
			if ( entry.isFile() && lowerName.endsWith( ".bx" ) && !reserved.contains( lowerName ) ) {
				routes.put( lowerName.substring( 0, lowerName.length() - 3 ), entry.toPath().toAbsolutePath() );
			}
		}
		return routes;
	}

	/**
	 * Filenames that must never be treated as URI-routing targets, regardless of the
	 * routing tier in use: the application descriptor and whichever file this instance
	 * resolves as its default handler class (honoring BOXLANG_GCP_CLASS overrides).
	 *
	 * @return The set of lowercase reserved filenames
	 */
	private Set<String> reservedFileNames() {
		return Set.of(
		    "application.bx",
		    this.defaultFunctionPath.getFileName().toString().toLowerCase()
		);
	}

	/**
	 * Determine which BoxLang method to invoke on the resolved class.
	 * <p>
	 * Checks the {@code x-bx-function} request header (forwarded via the event
	 * struct's {@code headers} key). Falls back to {@code run}.
	 *
	 * @param event The BoxLang event struct
	 *
	 * @return The {@link Key} of the method to invoke
	 */
	protected Key getFunctionMethod( IStruct event ) {
		IStruct headers = event.getAsStruct( Key.headers );

		if ( headers.containsKey( BOXLANG_FUNCTION_HEADER ) ) {
			String headerValue = StringCaster.cast( headers.get( BOXLANG_FUNCTION_HEADER ) );
			if ( headerValue != null && !headerValue.isEmpty() ) {
				return Key.of( headerValue );
			}
		}

		return DEFAULT_FUNCTION_METHOD;
	}

	/**
	 * Build a GCP context struct that provides runtime metadata to {@code .bx}
	 * handlers — analogous to the AWS {@code Context} object.
	 *
	 * @param request The GCF HTTP request (reserved for future use, e.g. trace IDs)
	 *
	 * @return A {@link IStruct} containing GCF context metadata
	 */
	protected IStruct buildGcpContext( HttpRequest request ) {
		Map<String, String> env = System.getenv();
		return Struct.of(
		    KeyDictionary.functionName, env.getOrDefault( "K_SERVICE", "unknown" ),
		    KeyDictionary.functionVersion, env.getOrDefault( "K_REVISION", "latest" ),
		    KeyDictionary.projectId, env.getOrDefault( "GOOGLE_CLOUD_PROJECT", env.getOrDefault( "GCLOUD_PROJECT", "unknown" ) ),
		    KeyDictionary.requestId, UUID.randomUUID().toString()
		);
	}

	// =========================================================================
	// Accessors (primarily for testing)
	// =========================================================================

	/**
	 * Return the default function path this runner will use when URI routing
	 * does not match any class file.
	 *
	 * @return The absolute path to {@code Lambda.bx} (or the configured override)
	 */
	public Path getDefaultFunctionPath() {
		return this.defaultFunctionPath;
	}

	/**
	 * Return whether this runner is operating in debug mode.
	 *
	 * @return {@code true} if verbose diagnostic logging is enabled
	 */
	public boolean inDebugMode() {
		return this.debugMode;
	}

	/**
	 * Return the BoxLang runtime used by this runner.
	 *
	 * @return The {@link BoxRuntime} singleton
	 */
	public BoxRuntime getRuntime() {
		return runtime;
	}
}
