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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import ortus.boxlang.runtime.BoxRuntime;
import ortus.boxlang.runtime.context.IBoxContext;
import ortus.boxlang.runtime.context.ScriptingRequestBoxContext;
import ortus.boxlang.runtime.runnables.IClassRunnable;
import ortus.boxlang.runtime.util.FileSystemUtil;
import ortus.boxlang.runtime.util.ResolvedFilePath;

/**
 * Unit tests for {@link ClassCompiler} — focuses on compilation correctness and
 * cache behaviour (cold vs. warm invocations).
 */
public class ClassCompilerTest {

	/** Shared runner whose static block starts the BoxLang runtime once */
	private static final GCPFunctionRunner runner = new GCPFunctionRunner(
	    Path.of( "src", "test", "resources", "Lambda.bx" ), true
	);

	@BeforeEach
	public void clearCache() {
		ClassCompiler.clearCache();
	}

	private IBoxContext buildContext( Path classPath ) {
		return new ScriptingRequestBoxContext(
		    runner.getRuntime().getRuntimeContext(),
		    FileSystemUtil.createFileUri( classPath.toAbsolutePath().toString() )
		);
	}

	@Test
	@DisplayName( "Compiles Lambda.bx on first access (cold invocation)" )
	public void testColdCompilation() {
		Path				path		= Path.of( "src", "test", "resources", "Lambda.bx" ).toAbsolutePath();
		ResolvedFilePath	resolved	= ResolvedFilePath.of( path );
		IBoxContext			context		= buildContext( path );

		assertThat( ClassCompiler.isCached( path.toString() ) ).isFalse();

		IClassRunnable compiled = ClassCompiler.getOrCompile( resolved, context, true );

		assertThat( compiled ).isNotNull();
		assertThat( ClassCompiler.isCached( path.toString() ) ).isTrue();
	}

	@Test
	@DisplayName( "Returns the same instance on subsequent warm invocations" )
	public void testWarmInvocationReturnsCachedInstance() {
		Path				path		= Path.of( "src", "test", "resources", "Lambda.bx" ).toAbsolutePath();
		ResolvedFilePath	resolved	= ResolvedFilePath.of( path );
		IBoxContext			context		= buildContext( path );

		IClassRunnable		first		= ClassCompiler.getOrCompile( resolved, context, true );
		IClassRunnable		second		= ClassCompiler.getOrCompile( resolved, context, true );

		// Same reference — no recompilation on warm call
		assertThat( first ).isSameInstanceAs( second );
	}

	@Test
	@DisplayName( "Compiles Products.bx independently from Lambda.bx" )
	public void testMultipleClassesInCache() {
		Path			lambdaPath		= Path.of( "src", "test", "resources", "Lambda.bx" ).toAbsolutePath();
		Path			productsPath	= Path.of( "src", "test", "resources", "Products.bx" ).toAbsolutePath();
		IBoxContext		lambdaCtx		= buildContext( lambdaPath );
		IBoxContext		productsCtx		= buildContext( productsPath );

		IClassRunnable	lambda			= ClassCompiler.getOrCompile( ResolvedFilePath.of( lambdaPath ), lambdaCtx );
		IClassRunnable	products		= ClassCompiler.getOrCompile( ResolvedFilePath.of( productsPath ), productsCtx );

		assertThat( lambda ).isNotNull();
		assertThat( products ).isNotNull();
		assertThat( lambda ).isNotSameInstanceAs( products );
		assertThat( ClassCompiler.isCached( lambdaPath.toString() ) ).isTrue();
		assertThat( ClassCompiler.isCached( productsPath.toString() ) ).isTrue();
	}

	@Test
	@DisplayName( "clearCache removes all entries" )
	public void testClearCache() {
		Path				path		= Path.of( "src", "test", "resources", "Lambda.bx" ).toAbsolutePath();
		ResolvedFilePath	resolved	= ResolvedFilePath.of( path );
		IBoxContext			context		= buildContext( path );

		ClassCompiler.getOrCompile( resolved, context );
		assertThat( ClassCompiler.isCached( path.toString() ) ).isTrue();

		ClassCompiler.clearCache();

		assertThat( ClassCompiler.isCached( path.toString() ) ).isFalse();
	}

	@Test
	@DisplayName( "Throws when .bx file path does not exist" )
	public void testNonExistentFileThrows() {
		Path				badPath		= Path.of( "src", "test", "resources", "DoesNotExist.bx" ).toAbsolutePath();
		ResolvedFilePath	resolved	= ResolvedFilePath.of( badPath );
		IBoxContext			context		= buildContext( badPath );

		try {
			ClassCompiler.getOrCompile( resolved, context );
			throw new AssertionError( "Expected an exception for a non-existent .bx file" );
		} catch ( Exception e ) {
			// Expected — BoxLang cannot load a file that doesn't exist
			assertThat( e ).isNotNull();
		}
	}
}
