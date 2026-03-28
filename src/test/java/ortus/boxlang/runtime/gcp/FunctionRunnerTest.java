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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import ortus.boxlang.runtime.gcp.mocks.MockHttpRequest;
import ortus.boxlang.runtime.gcp.mocks.MockHttpResponse;

/**
 * Integration tests for {@link FunctionRunner}.
 * <p>
 * Each test exercises the full execution path: HTTP request → BoxLang execution
 * → HTTP response. The BoxLang runtime is started once by the static initializer
 * in {@link FunctionRunner} and reused across all tests.
 */
public class FunctionRunnerTest {

	/** Shared test resource path */
	private static final Path TEST_LAMBDA = Path.of( "src", "test", "resources", "Lambda.bx" );

	// =========================================================================
	// Lifecycle & basic correctness
	// =========================================================================

	@Test
	@DisplayName( "Throws RuntimeException when Lambda.bx does not exist" )
	public void testLambdaNotFound() {
		FunctionRunner		runner	= new FunctionRunner( Path.of( "invalid", "Lambda.bx" ), true );
		MockHttpRequest		req		= new MockHttpRequest( "GET", "/" );
		MockHttpResponse	res		= new MockHttpResponse();

		assertThrows( RuntimeException.class, () -> runner.service( req, res ) );
	}

	@Test
	@DisplayName( "Executes Lambda.bx and returns 200 status" )
	public void testValidLambdaReturns200() throws Exception {
		FunctionRunner		runner	= new FunctionRunner( TEST_LAMBDA, true );
		MockHttpRequest		req		= new MockHttpRequest( "GET", "/" );
		MockHttpResponse	res		= new MockHttpResponse();

		runner.service( req, res );

		assertThat( res.getStatusCode() ).isEqualTo( 200 );
	}

	@Test
	@DisplayName( "x-bx-function header routes to alternative method in Lambda.bx" )
	public void testXBxFunctionHeaderRouting() throws Exception {
		FunctionRunner		runner	= new FunctionRunner( TEST_LAMBDA, true );
		MockHttpRequest		req		= new MockHttpRequest( "GET", "/" )
		    .withHeader( "x-bx-function", "hello" );
		MockHttpResponse	res		= new MockHttpResponse();

		runner.service( req, res );

		assertThat( res.getStatusCode() ).isEqualTo( 200 );
		assertThat( res.getBody() ).isEqualTo( "Hello Baby" );
	}

	// =========================================================================
	// URI routing
	// =========================================================================

	@Test
	@DisplayName( "Routes /products to Products.bx" )
	public void testUriRoutingToProducts() throws Exception {
		FunctionRunner		runner	= new FunctionRunner( TEST_LAMBDA, true );
		MockHttpRequest		req		= new MockHttpRequest( "GET", "/products" );
		MockHttpResponse	res		= new MockHttpResponse();

		runner.service( req, res );

		assertThat( res.getStatusCode() ).isEqualTo( 200 );
		assertThat( res.getBody() ).contains( "Test: Fetching all products" );
	}

	@Test
	@DisplayName( "Routes /products/123 → Products.bx (first segment only)" )
	public void testUriRoutingToProductsWithId() throws Exception {
		FunctionRunner		runner	= new FunctionRunner( TEST_LAMBDA, true );
		MockHttpRequest		req		= new MockHttpRequest( "GET", "/products/123" );
		MockHttpResponse	res		= new MockHttpResponse();

		runner.service( req, res );

		assertThat( res.getStatusCode() ).isEqualTo( 200 );
		assertThat( res.getBody() ).contains( "Test: Fetching product #123" );
	}

	@Test
	@DisplayName( "Routes /customers to Customers.bx" )
	public void testUriRoutingToCustomers() throws Exception {
		FunctionRunner		runner	= new FunctionRunner( TEST_LAMBDA, true );
		MockHttpRequest		req		= new MockHttpRequest( "GET", "/customers" );
		MockHttpResponse	res		= new MockHttpResponse();

		runner.service( req, res );

		assertThat( res.getStatusCode() ).isEqualTo( 200 );
		assertThat( res.getBody() ).contains( "Test: Fetching all customers" );
	}

	@Test
	@DisplayName( "Routes /user-profiles (hyphenated) to UserProfiles.bx" )
	public void testUriRoutingWithHyphenatedPath() throws Exception {
		FunctionRunner		runner	= new FunctionRunner( TEST_LAMBDA, true );
		MockHttpRequest		req		= new MockHttpRequest( "GET", "/user-profiles" );
		MockHttpResponse	res		= new MockHttpResponse();

		runner.service( req, res );

		assertThat( res.getStatusCode() ).isEqualTo( 200 );
		assertThat( res.getBody() ).contains( "UserProfiles handling hyphenated URI" );
	}

	@Test
	@DisplayName( "Falls back to Lambda.bx when no matching class found for URI" )
	public void testUriFallbackToLambda() throws Exception {
		FunctionRunner		runner	= new FunctionRunner( TEST_LAMBDA, true );
		MockHttpRequest		req		= new MockHttpRequest( "GET", "/nonexistent-route" );
		MockHttpResponse	res		= new MockHttpResponse();

		runner.service( req, res );

		// Lambda.bx always returns 200; body content varies
		assertThat( res.getStatusCode() ).isEqualTo( 200 );
	}

	@Test
	@DisplayName( "Routes /products/categories/deep-nest → Products.bx (first segment only)" )
	public void testUriRoutingDeeplyNestedPath() throws Exception {
		FunctionRunner		runner	= new FunctionRunner( TEST_LAMBDA, true );
		MockHttpRequest		req		= new MockHttpRequest( "GET", "/products/categories/electronics" );
		MockHttpResponse	res		= new MockHttpResponse();

		runner.service( req, res );

		assertThat( res.getStatusCode() ).isEqualTo( 200 );
		assertThat( res.getBody() ).contains( "Test: Fetching all products" );
	}

	// =========================================================================
	// Warm invocation (cache)
	// =========================================================================

	@Test
	@DisplayName( "Warm invocations return consistent results (cache correctness)" )
	public void testWarmInvocationConsistency() throws Exception {
		FunctionRunner runner = new FunctionRunner( TEST_LAMBDA, true );

		for ( int i = 0; i < 3; i++ ) {
			MockHttpRequest		req	= new MockHttpRequest( "GET", "/products" );
			MockHttpResponse	res	= new MockHttpResponse();
			runner.service( req, res );
			assertThat( res.getStatusCode() ).isEqualTo( 200 );
			assertThat( res.getBody() ).contains( "Test: Fetching all products" );
		}
	}

	// =========================================================================
	// Response headers
	// =========================================================================

	@Test
	@DisplayName( "Response includes Content-Type header from response struct" )
	public void testDefaultContentTypeHeader() throws Exception {
		FunctionRunner		runner	= new FunctionRunner( TEST_LAMBDA, true );
		MockHttpRequest		req		= new MockHttpRequest( "GET", "/" );
		MockHttpResponse	res		= new MockHttpResponse();

		runner.service( req, res );

		assertThat( res.getHeader( "Content-Type" ) ).isEqualTo( "application/json" );
	}

	// =========================================================================
	// Edge cases
	// =========================================================================

	@Test
	@DisplayName( "Handles large request body without error" )
	public void testLargeRequestBody() throws Exception {
		FunctionRunner		runner		= new FunctionRunner( TEST_LAMBDA, true );
		String				largeBody	= "x".repeat( 100_000 );
		MockHttpRequest		req			= new MockHttpRequest( "POST", "/" ).withBody( largeBody );
		MockHttpResponse	res			= new MockHttpResponse();

		runner.service( req, res );

		assertThat( res.getStatusCode() ).isEqualTo( 200 );
	}

	@Test
	@DisplayName( "Handles request with no headers gracefully" )
	public void testEmptyHeaders() throws Exception {
		FunctionRunner		runner	= new FunctionRunner( TEST_LAMBDA, true );
		MockHttpRequest		req		= new MockHttpRequest( "GET", "/" );
		MockHttpResponse	res		= new MockHttpResponse();

		runner.service( req, res );

		assertThat( res.getStatusCode() ).isEqualTo( 200 );
	}

	@Test
	@DisplayName( "Handles concurrent invocations without data corruption" )
	public void testConcurrentInvocations() throws Exception {
		FunctionRunner		runner		= new FunctionRunner( TEST_LAMBDA, true );

		// Launch 5 threads simultaneously
		Thread[]			threads		= new Thread[ 5 ];
		int[]				statusCodes	= new int[ 5 ];

		for ( int i = 0; i < threads.length; i++ ) {
			final int idx = i;
			threads[ i ] = new Thread( () -> {
				try {
					MockHttpRequest		req	= new MockHttpRequest( "GET", "/products" );
					MockHttpResponse	res	= new MockHttpResponse();
					runner.service( req, res );
					statusCodes[ idx ] = res.getStatusCode();
				} catch ( Exception e ) {
					statusCodes[ idx ] = 500;
				}
			} );
		}

		for ( Thread t : threads ) {
			t.start();
		}
		for ( Thread t : threads ) {
			t.join( 10_000 );
		}

		for ( int code : statusCodes ) {
			assertThat( code ).isEqualTo( 200 );
		}
	}

	// =========================================================================
	// Accessor tests
	// =========================================================================

	@Test
	@DisplayName( "getDefaultFunctionPath returns the configured path" )
	public void testGetDefaultFunctionPath() {
		FunctionRunner runner = new FunctionRunner( TEST_LAMBDA, false );

		assertThat( runner.getDefaultFunctionPath().toString() ).contains( "Lambda.bx" );
	}

	@Test
	@DisplayName( "inDebugMode returns the configured value" )
	public void testInDebugMode() {
		assertThat( new FunctionRunner( TEST_LAMBDA, true ).inDebugMode() ).isTrue();
		assertThat( new FunctionRunner( TEST_LAMBDA, false ).inDebugMode() ).isFalse();
	}

	@Test
	@DisplayName( "getRuntime returns a non-null BoxRuntime" )
	public void testGetRuntime() {
		FunctionRunner runner = new FunctionRunner( TEST_LAMBDA, false );

		assertThat( runner.getRuntime() ).isNotNull();
	}
}
