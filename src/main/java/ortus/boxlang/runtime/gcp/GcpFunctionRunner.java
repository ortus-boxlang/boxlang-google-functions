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

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import com.google.cloud.functions.HttpFunction;
import com.google.cloud.functions.HttpRequest;
import com.google.cloud.functions.HttpResponse;

import ortus.boxlang.runtime.BoxRuntime;
import ortus.boxlang.runtime.application.BaseApplicationListener;
import ortus.boxlang.runtime.context.IBoxContext;
import ortus.boxlang.runtime.context.RequestBoxContext;
import ortus.boxlang.runtime.context.ScriptingRequestBoxContext;
import ortus.boxlang.runtime.dynamic.casters.StringCaster;
import ortus.boxlang.runtime.dynamic.casters.StructCaster;
import ortus.boxlang.runtime.runnables.IClassRunnable;
import ortus.boxlang.runtime.scopes.Key;
import ortus.boxlang.runtime.types.Array;
import ortus.boxlang.runtime.types.IStruct;
import ortus.boxlang.runtime.types.Struct;
import ortus.boxlang.runtime.types.exceptions.AbortException;
import ortus.boxlang.runtime.types.exceptions.ExceptionUtil;
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
 * runtime exactly once per container instance via {@link RuntimeBootstrap}.</li>
 * <li><strong>Request mapping</strong> – {@link RequestMapper} converts the
 * incoming {@link HttpRequest} into a BoxLang event struct whose shape mirrors
 * the AWS API Gateway v2.0 event for cross-platform compatibility.</li>
 * <li><strong>Route resolution</strong> – {@link RouteResolver} extracts the
 * first URI path segment, converts it to PascalCase, and looks for a matching
 * {@code .bx} file. Falls back to {@code Lambda.bx} when no match is found.</li>
 * <li><strong>Compilation &amp; caching</strong> – {@link ClassCompiler} compiles
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
public class GCPFunctionRunner implements HttpFunction {

	// =========================================================================
	// Constants
	// =========================================================================

	/**
	 * The header clients may send to route the request to a specific method
	 * within the resolved {@code .bx} class.
	 */
	protected static final Key			BOXLANG_FUNCTION_HEADER	= Key.of( "x-bx-function" );

	/**
	 * The default method executed when no {@code x-bx-function} header is present.
	 */
	protected static final Key			DEFAULT_FUNCTION_METHOD	= Key.run;

	// =========================================================================
	// Static state (shared across warm invocations)
	// =========================================================================

	/**
	 * The BoxLang runtime singleton, initialized once at cold-start.
	 */
	protected static final BoxRuntime	runtime;

	static {
		runtime = RuntimeBootstrap.initialize();

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
	protected Path		defaultFunctionPath;

	/**
	 * The root directory that contains the deployed {@code .bx} class files.
	 */
	protected String	functionRoot;

	/**
	 * Whether to emit verbose diagnostic output.
	 */
	protected boolean	debugMode;

	// =========================================================================
	// Constructors
	// =========================================================================

	/**
	 * No-arg constructor required by the GCF functions-framework.
	 * Reads configuration from environment variables.
	 */
	public GCPFunctionRunner() {
		Map<String, String> env = System.getenv();
		this.functionRoot	= env.getOrDefault( "BOXLANG_GCP_ROOT", RuntimeBootstrap.DEFAULT_FUNCTION_ROOT );
		this.debugMode		= Boolean.parseBoolean( env.getOrDefault( "BOXLANG_GCP_DEBUGMODE", "false" ) );

		// Allow an env-var override for the default handler class
		String classOverride = env.get( "BOXLANG_GCP_CLASS" );
		this.defaultFunctionPath = classOverride != null
		    ? Path.of( classOverride ).toAbsolutePath()
		    : Path.of( functionRoot, "Lambda.bx" );
	}

	/**
	 * Constructor for tests and local tooling — accepts explicit path and debug flag.
	 *
	 * @param functionPath The absolute path to the default {@code .bx} handler
	 * @param debugMode    {@code true} to enable verbose logging
	 */
	public GCPFunctionRunner( Path functionPath, boolean debugMode ) {
		Map<String, String> env = System.getenv();
		this.defaultFunctionPath	= functionPath;
		this.debugMode				= debugMode;

		// When running under tests the functionRoot is derived from the test resource
		// directory, otherwise it comes from the environment (or the default).
		if ( functionPath.toString().contains( "test/resources" ) ) {
			this.functionRoot = functionPath.getParent().toString();
		} else {
			this.functionRoot = env.getOrDefault( "BOXLANG_GCP_ROOT", RuntimeBootstrap.DEFAULT_FUNCTION_ROOT );
		}

		// Honor the env-var override even in test constructor
		if ( env.get( "BOXLANG_GCP_DEBUGMODE" ) != null ) {
			this.debugMode = Boolean.parseBoolean( env.get( "BOXLANG_GCP_DEBUGMODE" ) );
		}

		if ( this.debugMode ) {
			System.out.println( "[BoxLang GCP] Configured with path: " + this.defaultFunctionPath );
			System.out.println( "[BoxLang GCP] Function root: " + this.functionRoot );
		}
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
		IStruct					responseStruct		= Struct.of(
		    "statusCode", 200,
		    "headers", Struct.of(
		        "Content-Type", "application/json",
		        "Access-Control-Allow-Origin", "*" ),
		    "body", "",
		    "cookies", new Array()
		);

		// --- Convert HTTP request to BoxLang event struct ---
		IStruct					eventStruct			= RequestMapper.toEventStruct( request );

		// --- Resolve the .bx class from the URI (falls back to Lambda.bx) ---
		Path					resolvedClassPath	= RouteResolver.resolve( request.getPath(), this.functionRoot, this.debugMode );
		Path					finalFunctionPath	= resolvedClassPath != null ? resolvedClassPath : defaultFunctionPath;

		ResolvedFilePath		resolvedFilePath	= ResolvedFilePath.of( finalFunctionPath );
		String					resolvedPathStr		= resolvedFilePath.absolutePath().toString();

		// --- Build BoxLang execution context ---
		IBoxContext				boxContext			= new ScriptingRequestBoxContext(
		    runtime.getRuntimeContext(),
		    FileSystemUtil.createFileUri( resolvedPathStr )
		);

		// Set up request threading context and application lifecycle
		BaseApplicationListener	listener			= boxContext.getParentOfType( RequestBoxContext.class ).getApplicationListener();
		RequestBoxContext		requestContext		= boxContext.getParentOfType( RequestBoxContext.class );
		RequestBoxContext.setCurrent( requestContext );

		// GCP context struct (stands in for AWS Context object)
		IStruct		GCP_Context		= buildGcpContext( request );

		Throwable	errorToHandle	= null;
		Object		functionResult	= null;

		try {
			// Compile (or retrieve cached) .bx class
			IClassRunnable function = ClassCompiler.getOrCompile( resolvedFilePath, boxContext, debugMode );

			// Determine which method to invoke
			Key functionMethod = getFunctionMethod( eventStruct );

			// Application lifecycle: onRequestStart
			listener.onRequestStart( boxContext, new Object[] { resolvedPathStr, eventStruct, GCP_Context } );

			// Invoke the BoxLang handler method
			functionResult = function.dereferenceAndInvoke(
			    boxContext,
			    functionMethod,
			    new Object[] { eventStruct, GCP_Context, responseStruct },
			    false
			);

		} catch ( AbortException e ) {

			if ( debugMode ) {
				System.out.println( "[BoxLang GCP] AbortException caught" );
			}

			try {
				listener.onAbort( boxContext, new Object[] { resolvedPathStr, eventStruct, GCP_Context } );
			} catch ( Throwable ae ) {
				errorToHandle = ae;
			}

			boxContext.flushBuffer( true );

			// Re-throw custom abort causes as runtime exceptions
			if ( e.getCause() != null ) {
				throw ( RuntimeException ) e.getCause();
			}

		} catch ( Exception e ) {
			errorToHandle = e;
		} finally {

			// Application lifecycle: onRequestEnd
			try {
				listener.onRequestEnd( boxContext, new Object[] { resolvedPathStr, eventStruct, GCP_Context } );
			} catch ( Throwable e ) {
				errorToHandle = e;
			}

			boxContext.flushBuffer( false );

			// Error handling
			if ( errorToHandle != null ) {
				System.err.println( "[BoxLang GCP] Error: " + errorToHandle.getMessage() );

				try {
					if ( !listener.onError( boxContext, new Object[] { errorToHandle, "", eventStruct, GCP_Context } ) ) {
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
		IStruct headers = StructCaster.cast( event.getOrDefault( "headers", new Struct() ) );

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
		    "functionName", env.getOrDefault( "K_SERVICE", "unknown" ),
		    "functionVersion", env.getOrDefault( "K_REVISION", "latest" ),
		    "projectId", env.getOrDefault( "GOOGLE_CLOUD_PROJECT", env.getOrDefault( "GCLOUD_PROJECT", "unknown" ) ),
		    "requestId", UUID.randomUUID().toString()
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
		return defaultFunctionPath;
	}

	/**
	 * Return whether this runner is operating in debug mode.
	 *
	 * @return {@code true} if verbose diagnostic logging is enabled
	 */
	public boolean inDebugMode() {
		return debugMode;
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
