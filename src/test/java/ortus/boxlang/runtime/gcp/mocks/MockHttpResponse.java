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
package ortus.boxlang.runtime.gcp.mocks;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.google.cloud.functions.HttpResponse;

/**
 * Test implementation of the GCF {@link HttpResponse} interface.
 * <p>
 * Captures status code, headers, and the written body so that tests can assert
 * on the full response without a live GCF environment:
 *
 * <pre>
 * MockHttpResponse res = new MockHttpResponse();
 * runner.service( request, res );
 *
 * assertThat( res.getStatusCode() ).isEqualTo( 200 );
 * assertThat( res.getBody() ).contains( "products" );
 * </pre>
 */
public class MockHttpResponse implements HttpResponse {

	private int								statusCode		= 200;
	private final Map<String, List<String>>	headers			= new HashMap<>();
	private final StringWriter				bodyWriter		= new StringWriter();
	private final ByteArrayOutputStream		outputStream	= new ByteArrayOutputStream();

	// =========================================================================
	// HttpResponse implementation
	// =========================================================================

	@Override
	public void setStatusCode( int code ) {
		this.statusCode = code;
	}

	@Override
	public void setStatusCode( int code, String message ) {
		this.statusCode = code;
	}

	@Override
	public void setContentType( String contentType ) {
		appendHeader( "Content-Type", contentType );
	}

	@Override
	public Optional<String> getContentType() {
		List<String> values = headers.get( "Content-Type" );
		return ( values != null && !values.isEmpty() )
		    ? Optional.of( values.get( 0 ) )
		    : Optional.empty();
	}

	@Override
	public void appendHeader( String header, String value ) {
		headers.computeIfAbsent( header, k -> new ArrayList<>() ).add( value );
	}

	@Override
	public Map<String, List<String>> getHeaders() {
		return Collections.unmodifiableMap( headers );
	}

	@Override
	public OutputStream getOutputStream() throws IOException {
		return outputStream;
	}

	@Override
	public BufferedWriter getWriter() throws IOException {
		return new BufferedWriter( bodyWriter ) {

			@Override
			public void close() throws IOException {
				// Flush to the backing StringWriter but do NOT close it,
				// so callers can read the accumulated body after service() returns.
				flush();
			}
		};
	}

	// =========================================================================
	// Test-only accessors
	// =========================================================================

	/**
	 * Return the HTTP status code that was set on this response.
	 *
	 * @return The integer status code (default 200)
	 */
	public int getStatusCode() {
		return statusCode;
	}

	/**
	 * Return the body string accumulated through {@link #getWriter()}.
	 *
	 * @return The response body as a plain string
	 */
	public String getBody() {
		return bodyWriter.toString();
	}

	/**
	 * Return the first value of the named response header, or {@code null} if
	 * the header was not set.
	 *
	 * @param name The header name (case-sensitive as stored by
	 *             {@link #appendHeader})
	 *
	 * @return The first header value, or {@code null}
	 */
	public String getHeader( String name ) {
		List<String> values = headers.get( name );
		return ( values != null && !values.isEmpty() ) ? values.get( 0 ) : null;
	}
}
