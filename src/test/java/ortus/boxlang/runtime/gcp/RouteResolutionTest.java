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

import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for URI route resolution in {@link FunctionRunner#resolveRoute}.
 */
public class RouteResolutionTest {

	/** Default handler file for the legacy flat-root fixture (src/test/resources) */
	private static final Path TEST_LAMBDA = Path.of( "src", "test", "resources", "Lambda.bx" );

	@Test
	@DisplayName( "Returns null for root path '/'" )
	public void testRootPathReturnsNull() {
		FunctionRunner runner = new FunctionRunner( TEST_LAMBDA, false );
		assertThat( runner.resolveRoute( "/" ) ).isNull();
	}

	@Test
	@DisplayName( "Returns null for null URI" )
	public void testNullUriReturnsNull() {
		FunctionRunner runner = new FunctionRunner( TEST_LAMBDA, false );
		assertThat( runner.resolveRoute( null ) ).isNull();
	}

	@Test
	@DisplayName( "Returns null for empty URI" )
	public void testEmptyUriReturnsNull() {
		FunctionRunner runner = new FunctionRunner( TEST_LAMBDA, false );
		assertThat( runner.resolveRoute( "" ) ).isNull();
	}

	@Test
	@DisplayName( "Resolves /products to Products.bx" )
	public void testResolvesProductsPath() {
		FunctionRunner	runner		= new FunctionRunner( TEST_LAMBDA, false );
		Path			resolved	= runner.resolveRoute( "/products" );

		assertThat( resolved ).isNotNull();
		assertThat( resolved.getFileName().toString() ).isEqualTo( "Products.bx" );
	}

	@Test
	@DisplayName( "Resolves /customers to Customers.bx" )
	public void testResolvesCustomersPath() {
		FunctionRunner	runner		= new FunctionRunner( TEST_LAMBDA, false );
		Path			resolved	= runner.resolveRoute( "/customers" );

		assertThat( resolved ).isNotNull();
		assertThat( resolved.getFileName().toString() ).isEqualTo( "Customers.bx" );
	}

	@Test
	@DisplayName( "Resolves only the first segment — /products/123 → Products.bx" )
	public void testNestedPathUsesFirstSegment() {
		FunctionRunner	runner		= new FunctionRunner( TEST_LAMBDA, false );
		Path			resolved	= runner.resolveRoute( "/products/123" );

		assertThat( resolved ).isNotNull();
		assertThat( resolved.getFileName().toString() ).isEqualTo( "Products.bx" );
	}

	@Test
	@DisplayName( "Resolves /products/categories/electronics → Products.bx" )
	public void testDeeplyNestedPathUsesFirstSegment() {
		FunctionRunner	runner		= new FunctionRunner( TEST_LAMBDA, false );
		Path			resolved	= runner.resolveRoute( "/products/categories/electronics" );

		assertThat( resolved ).isNotNull();
		assertThat( resolved.getFileName().toString() ).isEqualTo( "Products.bx" );
	}

	@Test
	@DisplayName( "Returns null for a URI whose class does not exist on disk" )
	public void testNonExistentClassReturnsNull() {
		FunctionRunner	runner		= new FunctionRunner( TEST_LAMBDA, false );
		Path			resolved	= runner.resolveRoute( "/nonexistent-resource" );

		assertThat( resolved ).isNull();
	}

	@Test
	@DisplayName( "Converts hyphenated segment to PascalCase — /user-profiles → UserProfiles.bx" )
	public void testHyphenatedPathConvertsToPascalCase() {
		FunctionRunner	runner		= new FunctionRunner( TEST_LAMBDA, false );
		Path			resolved	= runner.resolveRoute( "/user-profiles" );

		assertThat( resolved ).isNotNull();
		assertThat( resolved.getFileName().toString() ).isEqualTo( "UserProfiles.bx" );
	}

	@Test
	@DisplayName( "Resolved path is absolute" )
	public void testResolvedPathIsAbsolute() {
		FunctionRunner	runner		= new FunctionRunner( TEST_LAMBDA, false );
		Path			resolved	= runner.resolveRoute( "/products" );

		assertThat( resolved ).isNotNull();
		assertThat( resolved.isAbsolute() ).isTrue();
	}
}
