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

import java.io.BufferedWriter;
import java.io.IOException;
import java.util.Map;

import com.google.cloud.functions.HttpResponse;

import ortus.boxlang.runtime.dynamic.casters.StringCaster;
import ortus.boxlang.runtime.types.util.JSONUtil;
import ortus.boxlang.runtime.scopes.Key;
import ortus.boxlang.runtime.types.IStruct;
import ortus.boxlang.runtime.types.Struct;

/**
 * Writes a BoxLang response {@link IStruct} to a Google Cloud Functions
 * {@link HttpResponse}.
 * <p>
 * The response struct contract is:
 * <ul>
 * <li>{@code statusCode} – integer HTTP status code (default 200)</li>
 * <li>{@code headers} – a nested struct of response headers</li>
 * <li>{@code body} – the response body; may be a {@link String}, a BoxLang
 * struct/array (auto-serialized to JSON), or empty</li>
 * <li>{@code cookies} – array of cookie strings (currently written as
 * {@code Set-Cookie} headers)</li>
 * </ul>
 * <p>
 * If a {@code .bx} handler returns a plain value (e.g. a struct literal) rather
 * than explicitly setting {@code response.body}, the caller places that value in
 * {@code response.body} before invoking this mapper. Struct and array values are
 * JSON-serialized automatically; plain strings are written as-is.
 */
public final class ResponseMapper {

	/**
	 * Private constructor — this is a stateless utility class.
	 */
	private ResponseMapper() {
	}

	/**
	 * Write the BoxLang response struct to the GCF {@link HttpResponse}.
	 *
	 * @param responseStruct The BoxLang response struct produced by the handler
	 * @param response       The GCF {@link HttpResponse} to populate
	 *
	 * @throws IOException If writing the response body fails
	 */
	public static void write( IStruct responseStruct, HttpResponse response ) throws IOException {
		// --- Status code ---
		int		statusCode	= 200;
		Object	statusObj	= responseStruct.get( Key.statusCode );

		// Accept numeric status codes or strings that can be parsed as integers; default to 200 for anything else (null, non-numeric string, etc.)
		if ( statusObj instanceof Number castedStatus ) {
			statusCode = castedStatus.intValue();
		} else if ( statusObj != null ) {
			try {
				statusCode = Integer.parseInt( statusObj.toString() );
			} catch ( NumberFormatException ignored ) {
				// keep 200
			}
		}
		response.setStatusCode( statusCode );

		// --- Headers ---
		Object headersObj = responseStruct.getOrDefault( Key.headers, new Struct() );
		if ( headersObj instanceof IStruct castedHeaders ) {
			for ( Map.Entry<Key, Object> entry : castedHeaders.entrySet() ) {
				String value = StringCaster.cast( entry.getValue() );
				response.appendHeader( entry.getKey().getName(), value );
			}
		}

		// --- Cookies → Set-Cookie headers ---
		Object cookiesObj = responseStruct.getOrDefault( Key.cookies, null );
		if ( cookiesObj instanceof Iterable castedCookies ) {
			for ( Object cookie : castedCookies ) {
				response.appendHeader( "Set-Cookie", StringCaster.cast( cookie ) );
			}
		}

		// --- Body ---
		writeBody( responseStruct.get( Key.body ), response );
	}

	/**
	 * Serialize and write the body value to the response writer.
	 * <ul>
	 * <li>Null / empty string → nothing written</li>
	 * <li>Plain {@link String} → written verbatim</li>
	 * <li>Any other type (struct, array, number …) → JSON-serialized via Jackson</li>
	 * </ul>
	 *
	 * @param body     The body value from the response struct
	 * @param response The GCF {@link HttpResponse}
	 *
	 * @throws IOException If opening the writer fails
	 */
	private static void writeBody( Object body, HttpResponse response ) throws IOException {
		// No body or empty body → write nothing
		if ( body == null ) {
			return;
		}

		String bodyOutput;
		if ( body instanceof String castedBody ) {
			bodyOutput = castedBody;
		} else {
			// Struct, Array, or any other BoxLang type → serialize to JSON
			try {
				bodyOutput = JSONUtil.getJSONBuilder().asString( body );
			} catch ( Exception e ) {
				// Last-resort fallback
				bodyOutput = body.toString();
			}
		}

		// Don't write anything if the body is empty after serialization (e.g. empty struct/array)
		if ( bodyOutput.isEmpty() ) {
			return;
		}

		// Write the body string to the response writer
		try ( BufferedWriter writer = response.getWriter() ) {
			writer.write( bodyOutput );
		}
	}
}
