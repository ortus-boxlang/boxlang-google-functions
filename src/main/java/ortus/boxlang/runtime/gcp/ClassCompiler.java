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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import ortus.boxlang.runtime.context.IBoxContext;
import ortus.boxlang.runtime.interop.DynamicObject;
import ortus.boxlang.runtime.runnables.IClassRunnable;
import ortus.boxlang.runtime.runnables.RunnableLoader;
import ortus.boxlang.runtime.util.ResolvedFilePath;

/**
 * Compiles BoxLang {@code .bx} class files and caches the resulting
 * {@link IClassRunnable} instances for reuse across warm invocations.
 * <p>
 * The cache is a static {@link ConcurrentHashMap} keyed by the absolute file
 * path string, ensuring thread-safe reads and writes without explicit locking.
 * On the first invocation for a given path the class is compiled; all
 * subsequent invocations return the cached instance immediately.
 * <p>
 * <strong>Memory:</strong> compiled classes remain in memory for the lifetime
 * of the container instance, which is the desired behavior for a cold-start
 * optimization strategy. GCF terminates idle containers automatically so there
 * is no unbounded growth concern in practice.
 */
public final class ClassCompiler {

	/**
	 * Thread-safe cache of compiled BoxLang class instances, keyed by absolute
	 * file path string.
	 */
	private static final Map<String, IClassRunnable> cache = new ConcurrentHashMap<>();

	/**
	 * Private constructor — this is a stateless utility class with a shared
	 * static cache.
	 */
	private ClassCompiler() {
	}

	/**
	 * Return a compiled {@link IClassRunnable} for the given file path, compiling
	 * and caching it on first access.
	 *
	 * @param resolvedPath The resolved file path of the {@code .bx} class
	 * @param context      The BoxLang execution context used for compilation
	 * @param debugMode    When {@code true}, prints cache hit/miss information
	 *
	 * @return A ready-to-invoke {@link IClassRunnable} instance
	 */
	public static IClassRunnable getOrCompile( ResolvedFilePath resolvedPath, IBoxContext context, boolean debugMode ) {
		String			cacheKey	= resolvedPath.absolutePath().toString();
		IClassRunnable	cached		= cache.get( cacheKey );

		if ( cached != null ) {
			if ( debugMode ) {
				System.out.println( "[BoxLang GCP] Cache hit: " + cacheKey );
			}
			return cached;
		}

		if ( debugMode ) {
			System.out.println( "[BoxLang GCP] Compiling: " + cacheKey );
		}

		IClassRunnable compiled = ( IClassRunnable ) DynamicObject.of(
		    RunnableLoader.getInstance().loadClass( resolvedPath, context )
		).invokeConstructor( context ).getTargetInstance();

		cache.put( cacheKey, compiled );
		return compiled;
	}

	/**
	 * Convenience overload with {@code debugMode = false}.
	 *
	 * @param resolvedPath The resolved file path of the {@code .bx} class
	 * @param context      The BoxLang execution context used for compilation
	 *
	 * @return A ready-to-invoke {@link IClassRunnable} instance
	 */
	public static IClassRunnable getOrCompile( ResolvedFilePath resolvedPath, IBoxContext context ) {
		return getOrCompile( resolvedPath, context, false );
	}

	/**
	 * Check whether a compiled class for the given path is already cached.
	 * Primarily useful for testing cold vs. warm invocation behaviour.
	 *
	 * @param absolutePath The absolute file path string to check
	 *
	 * @return {@code true} if the class is cached
	 */
	public static boolean isCached( String absolutePath ) {
		return cache.containsKey( absolutePath );
	}

	/**
	 * Remove all entries from the compiled class cache.
	 * Intended for use in tests that need a clean slate between runs.
	 */
	public static void clearCache() {
		cache.clear();
	}
}
