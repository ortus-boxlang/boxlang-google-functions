# BoxLang Google Cloud Functions Runtime - AI Development Guide

## Project Overview

This is a **BoxLang Google Cloud Functions (GCF) Runtime** that enables running BoxLang code in Google Cloud Functions. The runtime acts as a bridge between the GCF Java 21 runtime and BoxLang's dynamic language features.

### Core Architecture

- **Entry Point**: `ortus.boxlang.runtime.gcp.FunctionRunner` - implements GCF `HttpFunction`
- **Route Resolution**: First URI path segment is converted to PascalCase and matched to a `.bx` file (e.g., `/customers` → `Customers.bx`). Falls back to `Lambda.bx` when no match is found.
- **Response Pattern**: Functions can return data directly OR populate a `response` struct with `statusCode`, `headers`, `body`, and `cookies`
- **BoxLang Integration**: Uses BoxLang runtime for dynamic compilation and execution of `.bx` files

## Essential Development Patterns

### Cloud Function Structure

BoxLang GCF functions follow this pattern in any `.bx` file:
```boxlang
class {
    function run( event, context, response ) {
        // Option 1: Use response struct
        response.statusCode              = 200;
        response.headers["Content-Type"] = "application/json";
        response.body = serializeJSON( { "message": "Hello World" } );

        // Option 2: Just return data (auto-populated in response.body)
        return "Hello World";
    }
}
```

### Route Resolution

The runtime resolves incoming requests to `.bx` handler files:
- `/customers` or `/customers/123` → `Customers.bx`
- `/products` → `Products.bx`
- `/` (root) or no match → `Lambda.bx`

All `.bx` handler files live in the GCF root directory (`BOXLANG_GCP_ROOT`, default `/workspace`).

### Environment Variables

- `BOXLANG_GCP_ROOT`: Root directory for `.bx` files (default: `/workspace`)
- `BOXLANG_GCP_CLASS`: Override default `Lambda.bx` file path
- `BOXLANG_GCP_DEBUGMODE`: Enable verbose debug logging
- `BOXLANG_GCP_CONFIG`: Custom BoxLang config path (defaults to `boxlang.json` in root)
- `K_SERVICE`: Function name (set automatically by GCF)
- `K_REVISION`: Function revision (set automatically by GCF)
- `GOOGLE_CLOUD_PROJECT`: GCP project ID

### Build System (Gradle)

**Key Commands:**
- `./gradlew shadowJar` - Build fat JAR with all dependencies
- `./gradlew buildMainZip` - Create deployable GCF zip with `.bx` files + runtime JAR
- `./gradlew buildTestZip` - Create test package
- `./gradlew runFunction` - Start local GCF dev server on port 8080

**runFunction Options:**
```bash
./gradlew runFunction                    # port 8080 (default)
./gradlew runFunction -Pport=9090        # custom port
./gradlew runFunction -Pdebug=true       # enable BoxLang debug logging
./gradlew runFunction -PfunctionRoot=... # custom .bx root directory
```

**Important Build Facts:**
- Uses shadow plugin for fat JARs; `functions-framework-api` is `compileOnly` (excluded from fat JAR)
- Automatically handles BoxLang dependencies (local or downloaded)
- Branch-aware versioning: `development` branch appends `-snapshot`
- Generates checksums (SHA-256, MD5) for all artifacts

### Local Development & Testing

**GCF Function Invoker (primary workflow):**
```bash
# Start the local function server
./gradlew runFunction

# In another terminal, send sample requests
./workbench/simple-test.sh              # tests against port 8080
./workbench/simple-test.sh 9090         # custom port

# Or send requests manually with curl
curl http://localhost:8080/
curl http://localhost:8080/customers
curl http://localhost:8080/products
```

**File Structure for Testing:**
- `workbench/sampleRequests/` - Sample HTTP request payloads (customers.json, products.json, health.json)
- `workbench/simple-test.sh` - Smoke test script
- `src/test/resources/Lambda.bx` - Default test handler
- `src/test/resources/Customers.bx`, `Products.bx`, `UserProfiles.bx` - Route-specific test handlers

### Dependency Management

**Local Development Setup:**
- If `../boxlang/build/libs/boxlang-{version}.jar` exists, uses local BoxLang build
- Otherwise downloads dependencies to `src/test/resources/libs/`
- Web support included via `boxlang-web-support` dependency

**Key Dependencies:**
- `com.google.cloud.functions:functions-framework-api:1.1.0` - GCF HTTP interface (`compileOnly`)
- `com.google.cloud.functions.invoker:java-function-invoker:1.3.1` - Local dev server only (`invoker` config)
- `org.slf4j:slf4j-nop` - Logging (no-op)
- BoxLang runtime and web support JARs

### Testing Patterns

**Unit Tests Location:** `src/test/java/ortus/boxlang/runtime/gcp/`
- `FunctionRunnerTest` - Main integration tests
- `HandlerCacheTest` - Compiled class cache tests
- `RequestMapperTest` / `ResponseMapperTest` - Mapper unit tests
- `RouteResolutionTest` - URI-to-handler routing tests
- Mock `.bx` handlers live in `src/test/resources/`
- Use Google Truth assertions: `assertThat(...)`

### GCF Deployment Structure

**Required ZIP Structure:**
```
Lambda.bx                              # Default fallback handler
Customers.bx                           # Route-specific handlers
Products.bx
lib/
  boxlang-google-functions-{version}.jar  # Runtime fat JAR
```

**GCF Deployment:**
- Entry point: `ortus.boxlang.runtime.gcp.FunctionRunner`
- Runtime: `java21`
- Upload the zip from `build/distributions/`

### BoxLang-Specific Considerations

**Runtime Initialization:**
- Static initialization of BoxLang runtime for performance (once per container instance)
- Stateless execution model (uses system temp directory)
- Dynamic class loading and compilation of `.bx` files
- Compiled class caching via `ConcurrentHashMap` to avoid recompilation on warm invocations

**Method Resolution:**
- Default method: `run`
- Custom method via `x-bx-function` request header
- `event`, `context`, and `response` parameters are automatically injected

**Context Struct Fields (GCF-specific):**
- `context.functionName` - from `K_SERVICE`
- `context.functionVersion` - from `K_REVISION`
- `context.projectId` - from `GOOGLE_CLOUD_PROJECT`
- `context.requestId` - generated UUID per request

## Common Workflows

1. **New Route Handler**: Create a new `.bx` file in `src/main/resources/` (e.g., `Orders.bx` → accessible via `/orders`)
2. **Local Testing**: Run `./gradlew runFunction` then `./workbench/simple-test.sh`
3. **Building**: Always run `shadowJar` before `buildMainZip` for deployment
4. **Debugging**: Set `BOXLANG_GCP_DEBUGMODE=true` or use `-Pdebug=true` with `runFunction`
5. **Example Reference**: See `examples/HelloWorld.bx` for a minimal working function

## Performance Best Practices

- **Class Caching**: Handler classes are automatically cached between invocations
- **Static Initialization**: Use `static {}` blocks for expensive one-time setup
- **Connection Reuse**: Store DB connections and HTTP clients as class variables
- **Early Returns**: Validate input early and return immediately on errors
- **Cold Start Optimization**: Keep initialization code minimal and cache aggressively

## File Locations to Remember

- Main runtime: `src/main/java/ortus/boxlang/runtime/gcp/FunctionRunner.java`
- Request/response mappers: `src/main/java/ortus/boxlang/runtime/gcp/RequestMapper.java`, `ResponseMapper.java`
- Default handler: `src/main/resources/Lambda.bx`
- Build config: `build.gradle`
- Version info: `gradle.properties`
- Sample requests: `workbench/sampleRequests/`
- Build config: `build.gradle` (shadow plugin configuration)
- Version info: `gradle.properties`
- Sample events: `workbench/sampleEvents/`
