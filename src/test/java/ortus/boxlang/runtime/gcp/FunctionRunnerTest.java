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
		FunctionRunner	runner		= new FunctionRunner( TEST_LAMBDA, true );

		// Launch 5 threads simultaneously
		Thread[]		threads		= new Thread[ 5 ];
		int[]			statusCodes	= new int[ 5 ];

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

	// =========================================================================
	// Manifest / handlers routing
	// =========================================================================

	@Test
	@DisplayName( "manifest.json is authoritative: routes what it lists, ignores what it doesn't" )
	public void testManifestRoutingIsAuthoritative() throws Exception {
		Path			testPath	= Path.of( "src", "test", "resources", "manifestRouting" );
		FunctionRunner	runner		= new FunctionRunner( Path.of( testPath.toString(), "Lambda.bx" ), true );

		assertThat( runner.getHandlerRoutes() ).containsKey( "products" );

		MockHttpRequest		req	= new MockHttpRequest( "GET", "/products" );
		MockHttpResponse	res	= new MockHttpResponse();
		runner.service( req, res );

		assertThat( res.getStatusCode() ).isEqualTo( 200 );
		assertThat( res.getBody() ).contains( "Manifest-routed: Products handler" );

		// Decoy.bx exists on disk (in handlers/) but is NOT listed in manifest.json:
		// it must never be reachable, proving the manifest is the allowlist, not
		// merely a hint that the handlers/ directory happens to exist.
		assertThat( runner.getHandlerRoutes() ).doesNotContainKey( "decoy" );
		assertThat( runner.resolveRoute( "/decoy" ) ).isNull();
	}

	@Test
	@DisplayName( "handlers/ directory boot-scan supports nested, case-insensitive routes" )
	public void testHandlersDirectoryNestedRouting() throws Exception {
		Path			testPath	= Path.of( "src", "test", "resources", "handlersRouting" );
		FunctionRunner	runner		= new FunctionRunner( Path.of( testPath.toString(), "Lambda.bx" ), true );

		// handlers/Api/Test.bx (mixed-case directory) registers as "api/test"
		assertThat( runner.getHandlerRoutes() ).containsKey( "api/test" );

		MockHttpRequest		req	= new MockHttpRequest( "GET", "/api/test" );
		MockHttpResponse	res	= new MockHttpResponse();
		runner.service( req, res );

		assertThat( res.getStatusCode() ).isEqualTo( 200 );
		assertThat( res.getBody() ).contains( "Nested handler: api/test" );
	}

	@Test
	@DisplayName( "Application.bx and the default handler class are never routable targets" )
	public void testReservedFilesNeverRouted() {
		Path			testPath	= Path.of( "src", "test", "resources", "reservedRouting" );
		FunctionRunner	runner		= new FunctionRunner( Path.of( testPath.toString(), "Lambda.bx" ), true );

		// Neither Application.bx nor Lambda.bx should ever appear in the routing table,
		// even under the legacy root-scan fallback (no handlers/ or manifest.json here) -
		// this is the exact scenario the reported vulnerability exploited:
		// GET /application + x-bx-function: onApplicationStart
		assertThat( runner.getHandlerRoutes() ).doesNotContainKey( "application" );
		assertThat( runner.getHandlerRoutes() ).doesNotContainKey( "lambda" );
		assertThat( runner.resolveRoute( "/application" ) ).isNull();
	}

	@Test
	@DisplayName( "A corrupt manifest.json falls back to the handlers/ directory scan instead of failing startup" )
	public void testCorruptManifestFallsBackToDirectoryScan() {
		Path			testPath	= Path.of( "src", "test", "resources", "corruptManifest" );
		FunctionRunner	runner		= new FunctionRunner( Path.of( testPath.toString(), "Lambda.bx" ), true );

		// manifest.json is invalid JSON; the handlers/Foo.bx directory scan should still
		// have registered "foo" as a fallback, rather than the constructor throwing
		assertThat( runner.getHandlerRoutes() ).containsKey( "foo" );
	}

	// =========================================================================
	// Application.bx lifecycle
	// =========================================================================

	@Test
	@DisplayName( "Application.bx onRequestStart fires for the default Lambda.bx handler" )
	public void testApplicationLifecycleFiresForDefaultHandler() throws Exception {
		Path			testPath	= Path.of( "src", "test", "resources", "applicationLifecycle" );
		FunctionRunner	runner		= new FunctionRunner( Path.of( testPath.toString(), "Lambda.bx" ), true );

		MockHttpRequest		req	= new MockHttpRequest( "GET", "/" );
		MockHttpResponse	res	= new MockHttpResponse();
		runner.service( req, res );

		assertThat( res.getStatusCode() ).isEqualTo( 200 );
		assertThat( res.getBody() ).contains( "\"applicationBxFired\" : true" );
	}

	@Test
	@DisplayName( "Application.bx onRequestStart also fires when URI routing dispatches to a handlers/ class" )
	public void testApplicationLifecycleFiresForRoutedHandler() throws Exception {
		Path			testPath	= Path.of( "src", "test", "resources", "applicationLifecycle" );
		FunctionRunner	runner		= new FunctionRunner( Path.of( testPath.toString(), "Lambda.bx" ), true );

		// Sanity check: the request really is being routed to handlers/Products.bx, not
		// silently falling back to the default Lambda.bx
		assertThat( runner.getHandlerRoutes() ).containsKey( "products" );

		MockHttpRequest		req	= new MockHttpRequest( "GET", "/products" );
		MockHttpResponse	res	= new MockHttpResponse();
		runner.service( req, res );

		assertThat( res.getStatusCode() ).isEqualTo( 200 );
		// Before the fix, Application.bx was looked up relative to handlers/, where it
		// doesn't exist, so onRequestStart never fired and this would be false.
		assertThat( res.getBody() ).contains( "\"applicationBxFired\" : true" );
	}

	// =========================================================================
	// Opt-in legacy root scan
	// =========================================================================

	@Test
	@DisplayName( "The legacy root-directory scan is on by default, matching prior releases" )
	public void testRootScanEnabledByDefault() throws Exception {
		Path			testPath	= Path.of( "src", "test", "resources", "rootScanDisabled" );
		// null = defer to BOXLANG_ENABLE_ROOT_SCAN, which defaults to true when unset
		FunctionRunner	runner		= new FunctionRunner( Path.of( testPath.toString(), "Lambda.bx" ), true, null );

		assertThat( runner.getHandlerRoutes() ).containsKey( "transport" );
	}

	@Test
	@DisplayName( "BOXLANG_ENABLE_ROOT_SCAN=false restricts the no-manifest/no-handlers fallback to the default handler only" )
	public void testRootScanCanBeDisabled() throws Exception {
		Path			testPath	= Path.of( "src", "test", "resources", "rootScanDisabled" );
		FunctionRunner	runner		= new FunctionRunner( Path.of( testPath.toString(), "Lambda.bx" ), true, false );

		// Transport.bx exists on disk at the root, but with root scanning disabled it must
		// never become a routable target.
		assertThat( runner.getHandlerRoutes() ).isEmpty();
		assertThat( runner.resolveRoute( "/transport" ) ).isNull();
		assertThat( runner.resolveRoute( "/TRANSPORT" ) ).isNull();

		// With no route registered for /transport, this must fall back to the default
		// handler (Lambda.bx), not reach Transport.bx.
		MockHttpRequest		req	= new MockHttpRequest( "GET", "/transport" );
		MockHttpResponse	res	= new MockHttpResponse();
		runner.service( req, res );

		assertThat( res.getStatusCode() ).isEqualTo( 200 );
		assertThat( res.getBody() ).contains( "default lambda" );
	}

	// =========================================================================
	// Manifest enforcement: reserved + defaultHandler
	// =========================================================================

	@Test
	@DisplayName( "manifest.json cannot route to Application.bx, Lambda.bx, or its own declared reserved files" )
	public void testManifestReservedListIsEnforced() throws Exception {
		Path			testPath	= Path.of( "src", "test", "resources", "manifestReservedEnforced" );
		FunctionRunner	runner		= new FunctionRunner( Path.of( testPath.toString(), "Lambda.bx" ), true );

		assertThat( runner.getHandlerRoutes() ).doesNotContainKey( "application" );
		assertThat( runner.getHandlerRoutes() ).doesNotContainKey( "lambda" );
		// The manifest's own "reserved" array names Secret.bx, even though it's neither
		// Application.bx nor the default handler
		assertThat( runner.getHandlerRoutes() ).doesNotContainKey( "secret" );
		assertThat( runner.resolveRoute( "/application" ) ).isNull();
		assertThat( runner.resolveRoute( "/secret" ) ).isNull();

		// A legitimate, non-reserved route from the same manifest still works
		assertThat( runner.getHandlerRoutes() ).containsKey( "products" );

		MockHttpRequest		req	= new MockHttpRequest( "GET", "/products" );
		MockHttpResponse	res	= new MockHttpResponse();
		runner.service( req, res );

		assertThat( res.getStatusCode() ).isEqualTo( 200 );
	}

	@Test
	@DisplayName( "manifest.json defaultHandler.file/method is respected instead of the Lambda.bx/run() convention" )
	public void testManifestDefaultHandlerIsRespected() throws Exception {
		Path			testPath	= Path.of( "src", "test", "resources", "manifestDefaultHandler" );
		FunctionRunner	runner		= new FunctionRunner( Path.of( testPath.toString(), "Lambda.bx" ), true );

		// No routes are declared, so every request falls through to the default handler -
		// which the manifest overrides to handlers/Special.bx#handle(), not Lambda.bx#run()
		MockHttpRequest		req	= new MockHttpRequest( "GET", "/anything" );
		MockHttpResponse	res	= new MockHttpResponse();
		runner.service( req, res );

		assertThat( res.getStatusCode() ).isEqualTo( 200 );
		assertThat( res.getBody() ).contains( "manifest-declared default handler" );
	}
}
