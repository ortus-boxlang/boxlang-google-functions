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

import ortus.boxlang.runtime.types.util.StringUtil;

/**
 * Resolves a URI path to a BoxLang {@code .bx} class file on disk.
 * <p>
 * The resolution algorithm:
 * <ol>
 * <li>Extract the first segment of the URI path (e.g. {@code products} from
 * {@code /products/123/details}).</li>
 * <li>Convert that segment to PascalCase (e.g. {@code user-profiles} →
 * {@code UserProfiles}).</li>
 * <li>Append {@code .bx} and look for the file under {@code functionRoot}.</li>
 * <li>Return the absolute {@link Path} if found, otherwise {@code null} so the
 * caller can fall back to {@code Lambda.bx}.</li>
 * </ol>
 * <p>
 * Examples:
 * 
 * <pre>
 *   /products           → {root}/Products.bx
 *   /products/123       → {root}/Products.bx   (only first segment matters)
 *   /user-profiles      → {root}/UserProfiles.bx
 *   /nonexistent        → null (file not found → caller uses Lambda.bx)
 *   /                   → null (root path → Lambda.bx)
 * </pre>
 */
public final class RouteResolver {

	/**
	 * Private constructor — this is a stateless utility class.
	 */
	private RouteResolver() {
	}

	/**
	 * Attempt to resolve a {@code .bx} class file from the given URI path.
	 *
	 * @param uriPath      The URI path from the HTTP request (e.g. {@code /products/123})
	 * @param functionRoot The root directory where BoxLang {@code .bx} files live
	 * @param debugMode    When {@code true}, prints resolution steps to stdout
	 *
	 * @return The absolute {@link Path} to the resolved class file, or {@code null}
	 *         when the URI is root-level, empty, or no matching file exists
	 */
	public static Path resolve( String uriPath, String functionRoot, boolean debugMode ) {
		if ( uriPath == null || uriPath.isEmpty() || uriPath.equals( "/" ) ) {
			return null;
		}

		// Strip leading slash and split on "/"
		String		cleanPath		= uriPath.startsWith( "/" ) ? uriPath.substring( 1 ) : uriPath;
		String[]	pathSegments	= cleanPath.split( "/" );

		if ( pathSegments.length == 0 || pathSegments[ 0 ].isEmpty() ) {
			return null;
		}

		// First segment → PascalCase class name
		String	className	= StringUtil.pascalCase( pathSegments[ 0 ] ) + ".bx";
		Path	classPath	= Path.of( functionRoot, className );

		if ( classPath.toFile().exists() ) {
			if ( debugMode ) {
				System.out.println( "[BoxLang GCP] URI routing: " + uriPath + " → " + classPath );
			}
			return classPath.toAbsolutePath();
		}

		if ( debugMode ) {
			System.out.println( "[BoxLang GCP] URI routing: class not found for " + uriPath + " (looked for " + classPath + ")" );
		}

		return null;
	}

	/**
	 * Convenience overload with {@code debugMode = false}.
	 *
	 * @param uriPath      The URI path from the HTTP request
	 * @param functionRoot The root directory where BoxLang {@code .bx} files live
	 *
	 * @return The absolute {@link Path} to the resolved class file, or {@code null}
	 */
	public static Path resolve( String uriPath, String functionRoot ) {
		return resolve( uriPath, functionRoot, false );
	}
}
