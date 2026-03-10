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
 */
package ortus.boxlang.runtime.gcp;

import static com.google.common.truth.Truth.assertThat;

import java.io.IOException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import ortus.boxlang.runtime.gcp.mocks.MockHttpRequest;
import ortus.boxlang.runtime.scopes.Key;
import ortus.boxlang.runtime.types.IStruct;

/**
 * Unit tests for {@link RequestMapper}.
 */
public class RequestMapperTest {

	@Test
	@DisplayName( "Maps HTTP method and path into event struct" )
	public void testBasicFieldMapping() throws IOException {
		MockHttpRequest	request	= new MockHttpRequest( "GET", "/products" );
		IStruct			event	= RequestMapper.toEventStruct( request );

		assertThat( event.getAsString( Key.of( "method" ) ) ).isEqualTo( "GET" );
		assertThat( event.getAsString( Key.of( "path" ) ) ).isEqualTo( "/products" );
		assertThat( event.getAsString( Key.of( "rawPath" ) ) ).isEqualTo( "/products" );
	}

	@Test
	@DisplayName( "Maps headers into event struct with lowercase keys" )
	public void testHeaderMapping() throws IOException {
		MockHttpRequest	request	= new MockHttpRequest( "GET", "/" )
		    .withHeader( "Content-Type", "application/json" )
		    .withHeader( "X-Custom-Header", "my-value" );
		IStruct			event	= RequestMapper.toEventStruct( request );

		IStruct			headers	= ( IStruct ) event.get( Key.of( "headers" ) );
		assertThat( headers ).isNotNull();
		assertThat( headers.getAsString( Key.of( "content-type" ) ) ).isEqualTo( "application/json" );
		assertThat( headers.getAsString( Key.of( "x-custom-header" ) ) ).isEqualTo( "my-value" );
	}

	@Test
	@DisplayName( "Maps query parameters into event struct" )
	public void testQueryParameterMapping() throws IOException {
		MockHttpRequest	request		= new MockHttpRequest( "GET", "/products" )
		    .withQueryParam( "page", "2" )
		    .withQueryParam( "limit", "10" );
		IStruct			event		= RequestMapper.toEventStruct( request );

		IStruct			queryParams	= ( IStruct ) event.get( Key.of( "queryStringParameters" ) );
		assertThat( queryParams ).isNotNull();
		assertThat( queryParams.getAsString( Key.of( "page" ) ) ).isEqualTo( "2" );
		assertThat( queryParams.getAsString( Key.of( "limit" ) ) ).isEqualTo( "10" );
	}

	@Test
	@DisplayName( "Maps request body into event struct" )
	public void testBodyMapping() throws IOException {
		String			body	= "{\"name\":\"Test Product\"}";
		MockHttpRequest	request	= new MockHttpRequest( "POST", "/products" )
		    .withBody( body );
		IStruct			event	= RequestMapper.toEventStruct( request );

		assertThat( event.getAsString( Key.of( "body" ) ) ).isEqualTo( body );
	}

	@Test
	@DisplayName( "Maps empty body to empty string" )
	public void testEmptyBody() throws IOException {
		MockHttpRequest	request	= new MockHttpRequest( "GET", "/" );
		IStruct			event	= RequestMapper.toEventStruct( request );

		assertThat( event.getAsString( Key.of( "body" ) ) ).isEmpty();
	}

	@Test
	@DisplayName( "Builds requestContext with http.method and http.path mirroring AWS API Gateway v2.0 shape" )
	public void testRequestContextShape() throws IOException {
		MockHttpRequest	request			= new MockHttpRequest( "POST", "/customers" );
		IStruct			event			= RequestMapper.toEventStruct( request );

		IStruct			requestContext	= ( IStruct ) event.get( Key.of( "requestContext" ) );
		assertThat( requestContext ).isNotNull();

		IStruct http = ( IStruct ) requestContext.get( Key.of( "http" ) );
		assertThat( http ).isNotNull();
		assertThat( http.getAsString( Key.of( "method" ) ) ).isEqualTo( "POST" );
		assertThat( http.getAsString( Key.of( "path" ) ) ).isEqualTo( "/customers" );
	}

	@Test
	@DisplayName( "Handles requests with no headers or query params gracefully" )
	public void testMinimalRequest() throws IOException {
		MockHttpRequest	request	= new MockHttpRequest( "DELETE", "/items/42" );
		IStruct			event	= RequestMapper.toEventStruct( request );

		assertThat( event.getAsString( Key.of( "method" ) ) ).isEqualTo( "DELETE" );
		assertThat( ( IStruct ) event.get( Key.of( "headers" ) ) ).isNotNull();
		assertThat( ( IStruct ) event.get( Key.of( "queryStringParameters" ) ) ).isNotNull();
	}
}
