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

import ortus.boxlang.runtime.BoxRuntime;

/**
 * Handles one-time static initialization of the BoxLang runtime for
 * Google Cloud Functions cold-start optimization.
 * <p>
 * Called from {@link GCPFunctionRunner}'s static initializer so the runtime
 * is ready before the first request arrives. Subsequent warm invocations
 * reuse the already-started runtime without paying the startup cost again.
 * <p>
 * Configuration is driven by environment variables:
 * <ul>
 * <li>{@code BOXLANG_GCP_DEBUGMODE} – enable verbose BoxLang debug logging</li>
 * <li>{@code BOXLANG_GCP_CONFIG} – absolute path to a custom {@code boxlang.json}</li>
 * <li>{@code BOXLANG_GCP_ROOT} – root directory where {@code .bx} files are deployed
 * (defaults to {@code /workspace})</li>
 * </ul>
 */
public final class RuntimeBootstrap {

	/**
	 * The default root directory used by Google Cloud Functions (gen2) when no
	 * override is provided.
	 */
	public static final String DEFAULT_FUNCTION_ROOT = "/workspace";

	/**
	 * Private constructor — this is a pure utility class.
	 */
	private RuntimeBootstrap() {
	}

	/**
	 * Initialize the BoxLang runtime.
	 * <p>
	 * Reads environment variables, locates an optional {@code boxlang.json}
	 * configuration file, and calls {@link BoxRuntime#getInstance} which returns
	 * (or creates) the singleton runtime. {@code waitForStart()} blocks until the
	 * runtime is fully ready.
	 *
	 * @return The initialized {@link BoxRuntime} singleton
	 */
	public static BoxRuntime initialize() {
		Map<String, String>	env				= System.getenv();
		boolean				debugMode		= Boolean.parseBoolean( env.getOrDefault( "BOXLANG_GCP_DEBUGMODE", "false" ) );
		String				configPath		= null;
		String				functionRoot	= env.getOrDefault( "BOXLANG_GCP_ROOT", DEFAULT_FUNCTION_ROOT );

		// Explicit config path wins
		if ( env.get( "BOXLANG_GCP_CONFIG" ) != null ) {
			configPath = env.get( "BOXLANG_GCP_CONFIG" );
		}
		// Auto-detect boxlang.json in the function root
		else if ( Path.of( functionRoot, "boxlang.json" ).toFile().exists() ) {
			configPath = Path.of( functionRoot, "boxlang.json" ).toString();
		}

		if ( debugMode ) {
			System.out.println( "[BoxLang GCP] Initializing runtime (function root: " + functionRoot + ")" );
			if ( configPath != null ) {
				System.out.println( "[BoxLang GCP] Using config: " + configPath );
			}
		}

		// Use the system temp directory as BoxLang's working directory since
		// the /workspace filesystem on GCF is read-only during execution.
		return BoxRuntime.getInstance( debugMode, configPath, System.getProperty( "java.io.tmpdir" ) ).waitForStart();
	}
}
