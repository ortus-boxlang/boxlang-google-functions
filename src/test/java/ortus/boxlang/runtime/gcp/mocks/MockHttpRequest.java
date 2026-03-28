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

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.google.cloud.functions.HttpRequest;

/**
 * Test implementation of the GCF {@link HttpRequest} interface.
 * <p>
 * Provides a fluent builder API for constructing mock requests in unit and
 * integration tests without requiring a live GCF environment:
 *
 * <pre>
 * 
 * MockHttpRequest req = new MockHttpRequest( "GET", "/products" )
 *     .withHeader( "Accept", "application/json" )
 *     .withQueryParam( "page", "1" )
 *     .withBody( "{\"id\":1}" );
 * </pre>
 */
public class MockHttpRequest implements HttpRequest {

	private final String					method;
	private final String					path;
	private final String					uri;
	private final Map<String, List<String>>	headers		= new HashMap<>();
	private final Map<String, List<String>>	queryParams	= new HashMap<>();
	private String							body		= "";
	private Optional<String>				contentType	= Optional.empty();

	/**
	 * Construct a minimal mock request with the given HTTP method and path.
	 *
	 * @param method The HTTP method (GET, POST, PUT, DELETE, …)
	 * @param path   The URI path (e.g. {@code /products/123})
	 */
	public MockHttpRequest( String method, String path ) {
		this.method	= method;
		this.path	= path;
		this.uri	= path;
	}

	// =========================================================================
	// Builder methods
	// =========================================================================

	/**
	 * Add a request header.
	 *
	 * @param name  Header name
	 * @param value Header value
	 *
	 * @return {@code this} for chaining
	 */
	public MockHttpRequest withHeader( String name, String value ) {
		headers.computeIfAbsent( name, k -> new ArrayList<>() ).add( value );
		return this;
	}

	/**
	 * Add a query parameter.
	 *
	 * @param name  Parameter name
	 * @param value Parameter value
	 *
	 * @return {@code this} for chaining
	 */
	public MockHttpRequest withQueryParam( String name, String value ) {
		queryParams.computeIfAbsent( name, k -> new ArrayList<>() ).add( value );
		return this;
	}

	/**
	 * Set the request body string.
	 *
	 * @param body The raw body content
	 *
	 * @return {@code this} for chaining
	 */
	public MockHttpRequest withBody( String body ) {
		this.body = body != null ? body : "";
		return this;
	}

	/**
	 * Set the Content-Type for this request.
	 *
	 * @param contentType The content type string (e.g. {@code application/json})
	 *
	 * @return {@code this} for chaining
	 */
	public MockHttpRequest withContentType( String contentType ) {
		this.contentType = Optional.ofNullable( contentType );
		if ( contentType != null ) {
			headers.put( "Content-Type", List.of( contentType ) );
		}
		return this;
	}

	// =========================================================================
	// HttpRequest implementation
	// =========================================================================

	@Override
	public String getMethod() {
		return method;
	}

	@Override
	public String getUri() {
		return uri;
	}

	@Override
	public String getPath() {
		return path;
	}

	@Override
	public Optional<String> getQuery() {
		return Optional.empty();
	}

	@Override
	public Map<String, List<String>> getQueryParameters() {
		return Collections.unmodifiableMap( queryParams );
	}

	@Override
	public Map<String, List<String>> getHeaders() {
		return Collections.unmodifiableMap( headers );
	}

	@Override
	public Optional<String> getContentType() {
		return contentType;
	}

	@Override
	public long getContentLength() {
		return body.getBytes( StandardCharsets.UTF_8 ).length;
	}

	@Override
	public Optional<String> getCharacterEncoding() {
		return Optional.of( "UTF-8" );
	}

	@Override
	public InputStream getInputStream() throws IOException {
		return new ByteArrayInputStream( body.getBytes( StandardCharsets.UTF_8 ) );
	}

	@Override
	public BufferedReader getReader() throws IOException {
		return new BufferedReader( new StringReader( body ) );
	}

	@Override
	public Map<String, HttpRequest.HttpPart> getParts() {
		return Collections.emptyMap();
	}
}
