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

	/** Root directory containing the test .bx class files */
	private static final String TEST_ROOT = Path.of( "src", "test", "resources" ).toAbsolutePath().toString();

	@Test
	@DisplayName( "Returns null for root path '/'" )
	public void testRootPathReturnsNull() {
		assertThat( FunctionRunner.resolveRoute( "/", TEST_ROOT, false ) ).isNull();
	}

	@Test
	@DisplayName( "Returns null for null URI" )
	public void testNullUriReturnsNull() {
		assertThat( FunctionRunner.resolveRoute( null, TEST_ROOT, false ) ).isNull();
	}

	@Test
	@DisplayName( "Returns null for empty URI" )
	public void testEmptyUriReturnsNull() {
		assertThat( FunctionRunner.resolveRoute( "", TEST_ROOT, false ) ).isNull();
	}

	@Test
	@DisplayName( "Resolves /products to Products.bx" )
	public void testResolvesProductsPath() {
		Path resolved = FunctionRunner.resolveRoute( "/products", TEST_ROOT, false );

		assertThat( resolved ).isNotNull();
		assertThat( resolved.getFileName().toString() ).isEqualTo( "Products.bx" );
	}

	@Test
	@DisplayName( "Resolves /customers to Customers.bx" )
	public void testResolvesCustomersPath() {
		Path resolved = FunctionRunner.resolveRoute( "/customers", TEST_ROOT, false );

		assertThat( resolved ).isNotNull();
		assertThat( resolved.getFileName().toString() ).isEqualTo( "Customers.bx" );
	}

	@Test
	@DisplayName( "Resolves only the first segment — /products/123 → Products.bx" )
	public void testNestedPathUsesFirstSegment() {
		Path resolved = FunctionRunner.resolveRoute( "/products/123", TEST_ROOT, false );

		assertThat( resolved ).isNotNull();
		assertThat( resolved.getFileName().toString() ).isEqualTo( "Products.bx" );
	}

	@Test
	@DisplayName( "Resolves /products/categories/electronics → Products.bx" )
	public void testDeeplyNestedPathUsesFirstSegment() {
		Path resolved = FunctionRunner.resolveRoute( "/products/categories/electronics", TEST_ROOT, false );

		assertThat( resolved ).isNotNull();
		assertThat( resolved.getFileName().toString() ).isEqualTo( "Products.bx" );
	}

	@Test
	@DisplayName( "Returns null for a URI whose class does not exist on disk" )
	public void testNonExistentClassReturnsNull() {
		Path resolved = FunctionRunner.resolveRoute( "/nonexistent-resource", TEST_ROOT, false );

		assertThat( resolved ).isNull();
	}

	@Test
	@DisplayName( "Converts hyphenated segment to PascalCase — /user-profiles → UserProfiles.bx" )
	public void testHyphenatedPathConvertsToPascalCase() {
		Path resolved = FunctionRunner.resolveRoute( "/user-profiles", TEST_ROOT, false );

		assertThat( resolved ).isNotNull();
		assertThat( resolved.getFileName().toString() ).isEqualTo( "UserProfiles.bx" );
	}

	@Test
	@DisplayName( "Resolved path is absolute" )
	public void testResolvedPathIsAbsolute() {
		Path resolved = FunctionRunner.resolveRoute( "/products", TEST_ROOT, false );

		assertThat( resolved ).isNotNull();
		assertThat( resolved.isAbsolute() ).isTrue();
	}
}
