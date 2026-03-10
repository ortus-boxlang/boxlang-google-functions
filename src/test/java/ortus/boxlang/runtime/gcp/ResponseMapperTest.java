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

import ortus.boxlang.runtime.gcp.mocks.MockHttpResponse;
import ortus.boxlang.runtime.types.Array;
import ortus.boxlang.runtime.types.IStruct;
import ortus.boxlang.runtime.types.Struct;

/**
 * Unit tests for {@link ResponseMapper}.
 */
public class ResponseMapperTest {

	@Test
	@DisplayName( "Writes status code to response" )
	public void testStatusCodeWritten() throws IOException {
		IStruct				responseStruct	= Struct.of( "statusCode", 404, "headers", new Struct(), "body", "", "cookies", new Array() );
		MockHttpResponse	res				= new MockHttpResponse();

		ResponseMapper.write( responseStruct, res );

		assertThat( res.getStatusCode() ).isEqualTo( 404 );
	}

	@Test
	@DisplayName( "Defaults to status 200 when statusCode is absent" )
	public void testDefaultStatusCode() throws IOException {
		IStruct				responseStruct	= new Struct();
		MockHttpResponse	res				= new MockHttpResponse();

		ResponseMapper.write( responseStruct, res );

		assertThat( res.getStatusCode() ).isEqualTo( 200 );
	}

	@Test
	@DisplayName( "Writes string body verbatim" )
	public void testStringBodyWritten() throws IOException {
		IStruct				responseStruct	= Struct.of(
		    "statusCode", 200,
		    "headers", new Struct(),
		    "body", "Hello from BoxLang!",
		    "cookies", new Array()
		);
		MockHttpResponse	res				= new MockHttpResponse();

		ResponseMapper.write( responseStruct, res );

		assertThat( res.getBody() ).isEqualTo( "Hello from BoxLang!" );
	}

	@Test
	@DisplayName( "Writes headers to response" )
	public void testHeadersWritten() throws IOException {
		IStruct				headers			= Struct.of(
		    "Content-Type", "application/json",
		    "X-Custom", "my-value"
		);
		IStruct				responseStruct	= Struct.of( "statusCode", 200, "headers", headers, "body", "", "cookies", new Array() );
		MockHttpResponse	res				= new MockHttpResponse();

		ResponseMapper.write( responseStruct, res );

		assertThat( res.getHeader( "Content-Type" ) ).isEqualTo( "application/json" );
		assertThat( res.getHeader( "X-Custom" ) ).isEqualTo( "my-value" );
	}

	@Test
	@DisplayName( "Serializes struct body to JSON" )
	public void testStructBodySerializedToJson() throws IOException {
		IStruct				bodyStruct		= Struct.of( "message", "hello", "count", 42 );
		IStruct				responseStruct	= Struct.of(
		    "statusCode", 200,
		    "headers", new Struct(),
		    "body", bodyStruct,
		    "cookies", new Array()
		);
		MockHttpResponse	res				= new MockHttpResponse();

		ResponseMapper.write( responseStruct, res );

		String body = res.getBody();
		assertThat( body ).isNotEmpty();
		// Jackson serializes IStruct (which is a Map<Key, Object>) — the body
		// should contain the field values even if key names are wrapped.
		assertThat( body ).contains( "hello" );
		assertThat( body ).contains( "42" );
	}

	@Test
	@DisplayName( "Writes empty body when body is null" )
	public void testNullBodyProducesEmptyOutput() throws IOException {
		IStruct				responseStruct	= Struct.of( "statusCode", 200, "headers", new Struct(), "cookies", new Array() );
		MockHttpResponse	res				= new MockHttpResponse();

		ResponseMapper.write( responseStruct, res );

		assertThat( res.getBody() ).isEmpty();
	}
}
