# ⚡︎ BoxLang Google Cloud Functions Runtime

```
|:------------------------------------------------------:|
| ⚡︎ B o x L a n g ⚡︎
| Dynamic : Modular : Productive
|:------------------------------------------------------:|
```

<blockquote>
	Copyright Since 2023 by Ortus Solutions, Corp
	<br>
	<a href="https://www.boxlang.io">www.boxlang.io</a> |
	<a href="https://www.ortussolutions.com">www.ortussolutions.com</a>
</blockquote>

<p>&nbsp;</p>

## 🚀 Welcome to the BoxLang Google Cloud Functions Runtime

This repository contains the **core Google Cloud Functions Runtime** for the BoxLang language. This runtime acts as a bridge between GCF's Java 21 runtime and BoxLang's dynamic language features, enabling BoxLang code execution in serverless environments on Google Cloud.

**✨ Key Features:**

- **🎯 Automatic URI Routing** - Route requests to specific BoxLang classes based on URI paths
- **⚡ Performance Optimized** - Class compilation caching for warm invocations
- **☁️ GCF Native** - Built on the official GCF Functions Framework for Java
- **🧪 Developer Friendly** - Live reloading in debug mode, local dev server, and smoke tests

> 💡 **For creating GCF projects**: Use our [BoxLang GCF Template](https://github.com/ortus-boxlang/boxlang-starter-google-functions) to quickly bootstrap new serverless applications.

## 🏗️ Architecture Overview

The runtime consists of:

- **`ortus.boxlang.runtime.gcp.FunctionRunner`** - Main GCF `HttpFunction` entry point
- **`RequestMapper`** - Converts `HttpRequest` → BoxLang event struct
- **`ResponseMapper`** - Serializes BoxLang response struct → GCF `HttpResponse`
- **Dynamic Class Compilation** - Compiles `.bx` files on-demand with intelligent caching
- **Convention-based Routing** - Routes requests to `Lambda.bx` or a URI-matched `.bx` file

### Runtime Flow

1. **Static Initialization** - BoxLang runtime loads once per GCF container instance
2. **URI-based Class Resolution** - Automatically routes requests to specific BoxLang classes based on URI path
3. **Class Compilation** - `.bx` files are compiled and cached for warm invocations (skipped in debug mode)
4. **Method Resolution** - Discovers target method via convention or `x-bx-function` header
5. **Application Lifecycle** - Full `Application.bx` lifecycle with `onRequestStart`/`onRequestEnd`
6. **Response Marshalling** - Converts BoxLang response struct to GCF HTTP response

## 🎯 URI-Based Routing

The runtime supports **automatic class routing** based on incoming URI paths, making it easy to build multi-resource APIs without any configuration.

### How It Works

When a request comes in, the runtime:

1. **Extracts the first URI path segment** from the request path
2. **Converts it to PascalCase** using BoxLang's built-in `StringUtil`
3. **Looks for a matching `.bx` file** in the configured function root (`BOXLANG_GCP_ROOT`)
4. **Falls back to `Lambda.bx`** if no specific class is found

### URI to Class Mapping Examples

| Incoming URI | BoxLang Class | Description |
|---|---|---|
| `/products` | `Products.bx` | Product management endpoints |
| `/customers` | `Customers.bx` | Customer management endpoints |
| `/user-profiles` | `UserProfiles.bx` | Handles hyphenated URIs |
| `/api_endpoints` | `ApiEndpoints.bx` | Handles underscored URIs |
| `/orders/123` | `Orders.bx` | Routes based on first segment only |
| `/` | `Lambda.bx` | Root requests use default handler |
| `/unknown/path` | `Lambda.bx` | Falls back to default when class not found |

### Creating Route Classes

Simply create a `.bx` file with the PascalCase name of your resource:

```java
// Products.bx - Handles all /products/* requests
class {
    function run( event, context, response ) {
        switch( event.method ?: "GET" ) {
            case "GET":
                return getAllProducts()
                break
            case "POST":
                return createProduct( event.body )
                break
            default:
                response.statusCode = 405
                return { "error": "Method not allowed" }
        }
    }

    private function getAllProducts() {
        return {
            "message": "Fetching all products",
            "data": [
                { "id": 1, "name": "Product 1", "price": 29.99 }
            ]
        }
    }
}
```

### Multi-Method Support

You can use the `x-bx-function` header to call a specific method within a route class:

```bash
# Call the default 'run' method
curl -X GET /products

# Call a custom method
curl -X GET /products -H "x-bx-function: getActiveProducts"
```

### Benefits

- **🚀 Zero Configuration** - Just create `.bx` files, no routing setup required
- **📁 Clean Organization** - Separate classes for different resources/domains
- **🔄 Backward Compatible** - Existing `Lambda.bx` files continue to work
- **⚡ Performance Optimized** - Classes are compiled once and cached on warm invocations
- **🧪 Easy Testing** - Test individual resource classes in isolation

## 🛠️ Development Setup

### Prerequisites

- **Java 21+** - Required for BoxLang runtime
- **Google Cloud CLI** - For deployment and project management
- **Docker** *(optional)* - For containerized local testing

### Local Development

```bash
# Clone the runtime repository
git clone https://github.com/ortus-boxlang/boxlang-google-functions.git
cd boxlang-google-functions

# Download BoxLang dependencies
./gradlew downloadBoxLang

# Build the runtime
./gradlew build shadowJar

# Create deployment packages
./gradlew buildMainZip buildTestZip

# Run tests
./gradlew test
```

### Running Locally

Start the local GCF Function Invoker server (defaults to port 9099):

```bash
./gradlew runFunction                    # port 9099 (default)
./gradlew runFunction -Pport=9090        # custom port
./gradlew runFunction -Pdebug=true       # enable BoxLang verbose logging
./gradlew runFunction -PfunctionRoot=... # custom .bx root directory
```

Then run the smoke tests in another terminal:

```bash
./workbench/simple-test.sh              # tests against port 9099
./workbench/simple-test.sh 9090         # custom port
```

Or test manually with curl:

```bash
curl http://localhost:9099/
curl http://localhost:9099/customers
curl http://localhost:9099/products
```

## 🧩 Core Components

### FunctionRunner.java

The main entry point implementing GCF's `HttpFunction`:

- **Static Initialization** - BoxLang runtime loads once per container instance
- **Class Caching** - Compiled BoxLang classes cached via `ConcurrentHashMap` (disabled in debug mode for live reloading)
- **URI Routing** - Resolves incoming paths to `.bx` handler files automatically
- **Application Lifecycle** - Integrates with BoxLang's `Application.bx` lifecycle

### Environment Variables

Runtime behavior is controlled via environment variables:

| Variable | Description | Default |
|---|---|---|
| `BOXLANG_GCP_ROOT` | Root directory for `.bx` handler files | `/workspace` |
| `BOXLANG_GCP_CLASS` | Override the default `Lambda.bx` path | *(unset)* |
| `BOXLANG_GCP_DEBUGMODE` | Enable verbose logging and disable class caching | `false` |
| `BOXLANG_GCP_CONFIG` | Path to a custom `boxlang.json` config | `boxlang.json` in root |
| `K_SERVICE` | Function name (set automatically by GCF) | *(GCF-managed)* |
| `K_REVISION` | Function revision (set automatically by GCF) | *(GCF-managed)* |
| `GOOGLE_CLOUD_PROJECT` | GCP project ID | *(GCF-managed)* |

### Build System (Gradle)

Key build tasks:

- `build` - Builds, tests, and packages
- `shadowJar` - Creates fat JAR with all dependencies
- `buildMainZip` - Packages deployable GCF runtime + all `.bx` route classes
- `buildTestZip` - Creates test package for validation
- `runFunction` - Starts local GCF dev server
- `downloadBoxLang` - Downloads BoxLang JARs for local development
- `spotlessApply` - Code formatting and linting

**Note:** The build system automatically includes all `.bx` files from `src/main/resources/` in the deployment package to support URI routing.

## 🔬 Testing Infrastructure

### Sample Requests

The `workbench/sampleRequests/` directory contains test payloads for manual testing:

- `customers.json` - Customer resource test
- `products.json` - Product resource test
- `health.json` - Health check test

### Unit Tests

Tests are located in `src/test/java/ortus/boxlang/runtime/gcp/`:

- **`FunctionRunnerTest`** - Core runtime functionality and integration tests
- **`HandlerCacheTest`** - Class caching and warm invocation validation
- **`RequestMapperTest`** - HTTP request → event struct mapping
- **`ResponseMapperTest`** - Response struct → HTTP response serialization
- **`RouteResolutionTest`** - URI-to-handler routing logic

### Smoke Testing

```bash
# Start the dev server
./gradlew runFunction

# In another terminal, run the smoke test suite
./workbench/simple-test.sh
```

## ⚡ Performance Optimizations

### Class Compilation Caching

The runtime caches compiled handler classes between invocations to minimize cold start overhead:

```java
private static final ConcurrentHashMap<String, IClassRunnable> classCache = new ConcurrentHashMap<>();
```

In **debug mode** (`BOXLANG_GCP_DEBUGMODE=true`), caching is intentionally bypassed so that changes to `.bx` files are picked up immediately without restarting the server.

### Best Practices for Contributors

- **Minimize Cold Start Impact** - Keep static initialization lightweight
- **Cache Aggressively** - Store expensive computations in static variables
- **Early Validation** - Fail fast on invalid inputs to reduce execution time
- **Connection Reuse** - Store DB connections and HTTP clients as class variables

## 🏗️ Build & Deployment

### Creating Runtime Distributions

```bash
# Build all artifacts
./gradlew clean build shadowJar buildMainZip buildTestZip

# Artifacts created in build/distributions/:
# - boxlang-google-functions-{version}.jar  (fat JAR)
# - boxlang-google-functions-{version}.zip  (deployable package)
# - boxlang-google-functions-test-{version}.zip (test package)
```

### Deploying to Google Cloud Functions

```bash
# Authenticate
gcloud auth login
gcloud config set project <YOUR_PROJECT_ID>

# Deploy
gcloud functions deploy boxlang-gcf \
  --gen2 \
  --runtime=java21 \
  --region=us-central1 \
  --entry-point=ortus.boxlang.runtime.gcp.FunctionRunner \
  --trigger-http \
  --allow-unauthenticated \
  --source=build/distributions/boxlang-google-functions-{version}.zip
```

## 🧑‍💻 Usage

To use it, create a Google Cloud Function using the `java21` runtime. Set the entry point to `ortus.boxlang.runtime.gcp.FunctionRunner`. By convention the runtime will execute a `Lambda.bx` file located at the root of the deployed package (`/workspace/Lambda.bx`) via the `run()` method:

```boxlang
// Lambda.bx
class {

    function run( event, context, response ) {
        // Your code here
    }

}
```

- The `event` parameter is the HTTP request data mapped to a BoxLang `Struct` (method, path, headers, body, queryString, etc.).
- The `context` parameter is GCF runtime metadata: `functionName`, `functionVersion`, `projectId`, `requestId`.
- The `response` parameter is a mutable `Struct` you populate to control the HTTP response.

### Response Struct

The `response` struct supports the following keys:

| Key | Description |
|---|---|
| `statusCode` | HTTP status code (default: `200`) |
| `headers` | A `Struct` of response headers |
| `body` | Response body — any type; serialized to JSON automatically |
| `cookies` | An `Array` of cookie strings |

If you return a value directly from `run()`, it is placed in `response.body` automatically.

### Example Function

```java
// Lambda.bx
class {

    function run( event, context, response ) {
        response.statusCode              = 200;
        response.headers["Content-Type"] = "application/json";
        response.body = serializeJSON( {
            "message"      : "Hello from BoxLang on Google Cloud!",
            "method"       : event.method,
            "path"         : event.path,
            "functionName" : context.functionName,
            "requestId"    : context.requestId
        } );
    }

}
```

Or using the shorthand return form:

```java
// Lambda.bx
class {

    function run( event, context, response ) {
        return { "message": "Hello from BoxLang!" };
    }

}
```

### Custom Handler Class

If you don't want to use the `Lambda.bx` convention, set the `BOXLANG_GCP_CLASS` environment variable to the full path of your BoxLang class file.

### Debug Mode

Set `BOXLANG_GCP_DEBUGMODE=true` to enable verbose logging and disable class caching (so `.bx` file changes are reflected immediately without a restart).

## 📚 Additional Resources

### BoxLang Documentation

- **Main Documentation** - [boxlang.ortusbooks.com](https://boxlang.ortusbooks.com)
- **Google Cloud Functions Guide** - [BoxLang GCF Documentation](https://boxlang.ortusbooks.com/getting-started/running-boxlang/google-cloud-functions)
- **IDE Tooling** - [Development Tools](https://boxlang.ortusbooks.com/getting-started/ide-tooling)

### Related Projects

- **BoxLang Core** - [boxlang](https://github.com/ortus-boxlang/boxlang)
- **Web Support** - [boxlang-web-support](https://github.com/ortus-boxlang/boxlang-web-support)
- **AWS Lambda Runtime** - [boxlang-aws-lambda](https://github.com/ortus-boxlang/boxlang-aws-lambda)

## License

Apache License, Version 2.0.

## Open-Source & Professional Support

This project is a professional open source project and is available as FREE and open source to use.  Ortus Solutions, Corp provides commercial support, training and commercial subscriptions which include the following:

- Professional Support and Priority Queuing
- Remote Assistance and Troubleshooting
- New Feature Requests and Custom Development
- Custom SLAs
- Application Modernization and Migration Services
- Performance Audits
- Enterprise Modules and Integrations
- Much More

https://www.boxlang.io/plans

<p>&nbsp;</p>

<blockquote>
"We ❤️ Open Source and BoxLang" - Luis Majano
</blockquote>

## ⭐ Star Us

Please star us if this runtime helps you build amazing serverless applications with BoxLang!

[![GitHub Stars](https://img.shields.io/github/stars/ortus-boxlang/boxlang-google-functions?style=social)](

## Ortus Sponsors

BoxLang is a professional open-source project and it is completely funded by the [community](https://patreon.com/ortussolutions) and [Ortus Solutions, Corp](https://www.ortussolutions.com). Ortus Patreons get many benefits like a cfcasts account, a FORGEBOX Pro account and so much more. If you are interested in becoming a sponsor, please visit our patronage page: [https://patreon.com/ortussolutions](https://patreon.com/ortussolutions)

### THE DAILY BREAD

> "I am the way, and the truth, and the life; no one comes to the Father, but by me (JESUS)" Jn 14:1-12
