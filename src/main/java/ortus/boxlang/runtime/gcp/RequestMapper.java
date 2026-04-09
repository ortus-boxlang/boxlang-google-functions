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

import java.io.BufferedReader;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import com.google.cloud.functions.HttpRequest;

import ortus.boxlang.runtime.gcp.util.KeyDictionary;
import ortus.boxlang.runtime.scopes.Key;
import ortus.boxlang.runtime.types.IStruct;
import ortus.boxlang.runtime.types.Struct;

/**
 * Converts an incoming Google Cloud Functions {@link HttpRequest} into a
 * BoxLang-compatible event {@link IStruct}.
 * <p>
 * The produced struct intentionally mirrors the AWS API Gateway v2.0 (HTTP API)
 * event shape so that {@code .bx} handler files can run unmodified on both
 * the AWS and GCP runtimes:
 *
 * <pre>
 * {
 *   method:               "GET",
 *   path:                 "/products",
 *   rawPath:              "/products",
 *   queryStringParameters: { page: "1" },
 *   headers:              { "content-type": "application/json" },
 *   body:                 "",
 *   requestContext: {
 *     http: {
 *       method: "GET",
 *       path:   "/products"
 *     }
 *   }
 * }
 * </pre>
 * <p>
 * Multi-value headers and query parameters are collapsed to their first value.
 * Handlers receive only the collapsed single-value struct; the raw multi-value
 * data is not exposed through the event.
 */
public final class RequestMapper {

	/**
	 * Private constructor — this is a stateless utility class.
	 */
	private RequestMapper() {
	}

	/**
	 * Convert a GCF {@link HttpRequest} into a BoxLang event struct.
	 *
	 * @param request The incoming HTTP request from the GCF functions-framework
	 *
	 * @return A BoxLang {@link IStruct} containing all relevant request fields
	 */
	public static IStruct toEventStruct( HttpRequest request ) {
		// --- Headers (lowercase keys, first-value wins for multi-value) ---
		IStruct headersStruct = new Struct();
		for ( Map.Entry<String, List<String>> entry : request.getHeaders().entrySet() ) {
			List<String> values = entry.getValue();
			headersStruct.put( entry.getKey().toLowerCase( Locale.ROOT ), values.isEmpty() ? "" : values.get( 0 ) );
		}

		// --- Query parameters (first-value wins) ---
		IStruct queryParams = new Struct();
		for ( Map.Entry<String, List<String>> entry : request.getQueryParameters().entrySet() ) {
			List<String> values = entry.getValue();
			queryParams.put( entry.getKey(), values.isEmpty() ? "" : values.get( 0 ) );
		}

		// --- Body ---
		String	body		= readBody( request );

		// --- requestContext mirrors AWS API Gateway v2.0 HTTP context ---
		IStruct	httpContext	= Struct.of(
		    Key.method, request.getMethod(),
		    Key.path, request.getPath()
		);

		return Struct.of(
		    Key.method, request.getMethod(),
		    Key.path, request.getPath(),
		    KeyDictionary.rawPath, request.getPath(),
		    KeyDictionary.queryStringParameters, queryParams,
		    Key.headers, headersStruct,
		    Key.body, body,
		    KeyDictionary.requestContext, Struct.of( Key.HTTP, httpContext )
		);
	}

	/**
	 * Read the request body as a UTF-8 string.
	 * Returns an empty string when the body is absent or unreadable.
	 *
	 * @param request The HTTP request
	 *
	 * @return The body as a plain string
	 */
	private static String readBody( HttpRequest request ) {
		try ( BufferedReader reader = request.getReader() ) {
			if ( reader == null ) {
				return "";
			}
			return reader.lines().collect( Collectors.joining( "\n" ) );
		} catch ( IOException e ) {
			// An absent or unreadable body is treated as empty
			return "";
		}
	}
}
