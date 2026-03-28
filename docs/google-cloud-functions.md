# BoxLang Runtime for Google Cloud Functions

> **Serverless BoxLang on Google Cloud — write `.bx` handlers and deploy to GCF Gen2.**

---

## What is Google Cloud Functions?

Google Cloud Functions is a serverless execution environment that allows you to run code in response to HTTP requests without managing infrastructure. Functions scale automatically, execute in response to HTTP requests or cloud events, and you pay only for the time your code is running ([https://cloud.google.com/functions](https://cloud.google.com/functions)).

The BoxLang GCF runtime provides a pre-built Java entry point that bridges the Google Cloud Functions HTTP interface with the BoxLang language runtime. You write plain `.bx` handler files; the runtime handles the rest — request mapping, routing, compilation, caching, and response serialization.

---

## 📋 Table of Contents

- [What is Google Cloud Functions?](#what-is-google-cloud-functions)
- [BoxLang GCF Handler](#boxlang-gcf-handler)
- [Requirements](#requirements)
- [Project Setup](#project-setup)
- [Running Locally](#running-locally)
- [Writing Handlers](#writing-handlers)
  - [Handler Arguments](#handler-arguments)
  - [Event Struct](#event-struct)
  - [Context Struct](#context-struct)
  - [Response Struct](#response-struct)
- [Convention-Based URI Routing](#convention-based-uri-routing)
- [x-bx-function Header Routing](#x-bx-function-header-routing)
- [Environment Variables](#environment-variables)
- [Testing Locally](#testing-locally)
- [Deployment to Google Cloud Functions](#deployment-to-google-cloud-functions)
- [Example Requests](#example-requests)
- [How It Works](#how-it-works)
- [Notes](#notes)

---

## BoxLang GCF Runtime

The runtime entry point is `ortus.boxlang.runtime.gcp.FunctionRunner`, which implements the Google Cloud Functions `HttpFunction` interface. It acts as a front controller for every incoming HTTP request and provides:

- Automatic translation of HTTP requests into a BoxLang-compatible event struct
- Convention-based routing to `.bx` handler files based on the URI path
- Compilation and in-memory caching of `.bx` classes across warm invocations
- Automatic JSON serialization of BoxLang structs and arrays in the response body
- Application lifecycle events via `Application.bx`
- Custom method dispatch via the `x-bx-function` request header

The runtime is designed so that handler code written for this runtime is portable to the BoxLang AWS Lambda runtime with minimal changes — the event struct shape mirrors the AWS API Gateway v2.0 HTTP event format.

---

## Requirements

| Requirement | Version |
|---|---|
| Java | 21 |
| BoxLang | 1.x |
| Google Cloud SDK (`gcloud`) | Latest |
| Gradle | Included via wrapper |

---

## Project Setup

Clone the repository and download the BoxLang JARs needed for local development:

```bash
git clone https://github.com/ortus-boxlang/boxlang-google-functions.git
cd boxlang-google-functions
./gradlew downloadBoxLang
```

Build the deployable fat JAR:

```bash
./gradlew shadowJar
```

---

## Running Locally

The project includes a Gradle task that starts a local HTTP server using the official [GCF Java Function Invoker](https://github.com/GoogleCloudPlatform/functions-framework-java), replicating the production GCF execution model on your machine.

```bash
./gradlew runFunction
```

Optional flags:

```bash
./gradlew runFunction -Pport=9090          # custom port (default: 8080)
./gradlew runFunction -Pdebug=true         # enable BoxLang verbose logging
./gradlew runFunction -PfunctionRoot=/path # custom .bx handler directory
```

Expected output:

```
================================================================
 BoxLang GCF Function Invoker
 Listening on  : http://localhost:8080
 Function root : .../src/test/resources
 Debug mode    : false
 Press Ctrl+C to stop.
================================================================
```

The function root defaults to `src/test/resources` during local development so you can edit `.bx` files and restart without rebuilding the JAR.

---

## Writing Handlers

Handlers are BoxLang classes (`.bx` files) that expose a `run()` method. The runtime locates the handler file on disk, compiles it once, and caches the compiled class for all subsequent warm invocations.

```boxlang
class {

    function run( event, context, response ) {

        return {
            "message" : "Hello from BoxLang on GCF!",
            "path"    : event.path
        };

    }

}
```

Returning a struct or array from `run()` is the simplest way to produce a JSON response body. Alternatively, you can write directly to `response.body`.

### Handler Arguments

| Argument | Type | Description |
|---|---|---|
| `event` | `Struct` | All incoming request data — method, path, headers, query parameters, body |
| `context` | `Struct` | GCF runtime metadata — function name, revision, project ID, request ID |
| `response` | `Struct` | Mutable response struct — set `statusCode`, `headers`, `body`, or `cookies` |

### Event Struct

The event struct mirrors the AWS API Gateway v2.0 HTTP event shape so that handler code can run on both platforms without modification:

```boxlang
{
    method                : "GET",
    path                  : "/products",
    rawPath               : "/products",
    queryStringParameters : { page: "1" },
    headers               : { "content-type": "application/json" },
    body                  : "",
    requestContext        : {
        http : {
            method : "GET",
            path   : "/products"
        }
    }
}
```

> Multi-value headers and query parameters are collapsed to their first value. The event struct contains only the collapsed single-value representation.

### Context Struct

The context struct provides metadata about the running function instance. It is populated from standard GCF environment variables:

| Key | Source | Description |
|---|---|---|
| `functionName` | `K_SERVICE` | The deployed Cloud Function name |
| `functionVersion` | `K_REVISION` | The current function revision |
| `projectId` | `GOOGLE_CLOUD_PROJECT` | The GCP project ID |
| `requestId` | Generated | A unique UUID for this invocation |

### Response Struct

The default response struct provided to your handler is:

```boxlang
{
    statusCode : 200,
    headers    : { "Content-Type": "application/json", "Access-Control-Allow-Origin": "*" },
    body       : "",
    cookies    : []
}
```

You can mutate any field directly, or simply `return` a value from your handler method and the runtime will place it in `body` automatically. Structs and arrays are JSON-serialized; plain strings are written as-is.

---

## Convention-Based URI Routing

The runtime automatically routes incoming requests to different `.bx` handler files based on the first segment of the URI path. This lets you build multi-handler functions without any explicit routing configuration.

### Routing Algorithm

1. Extract the first path segment (e.g. `products` from `/products/42`)
2. Convert it to PascalCase (e.g. `products` → `Products`)
3. Append `.bx` and look for the file under `BOXLANG_GCP_ROOT`
4. If found, execute that handler; otherwise fall back to `Lambda.bx`

### URI to Handler Mapping

| Request Path | Handler File |
|---|---|
| `/` | `Lambda.bx` (default) |
| `/products` | `Products.bx` |
| `/products/42` | `Products.bx` (first segment only) |
| `/customers` | `Customers.bx` |
| `/user-profiles` | `UserProfiles.bx` (hyphens → PascalCase) |
| `/unknown-route` | `Lambda.bx` (fallback, file not found) |

### Multi-Handler Structure

```
src/main/resources/
├── Lambda.bx        ← default / fallback
├── Products.bx      ← handles /products/**
└── Customers.bx     ← handles /customers/**
```

Each file is a standard BoxLang class exposing a `run()` method:

```boxlang
// Products.bx
class {

    function run( event, context, response ) {
        return {
            "message" : "Fetching all products",
            "data"    : []
        };
    }

}
```

---

## x-bx-function Header Routing

You can route a request to any method in the resolved `.bx` class by including the `x-bx-function` request header. This allows a single handler file to expose multiple named operations without relying on separate URI paths.

```bash
curl -H "x-bx-function: findById" https://<FUNCTION_URL>/products
```

This will call `findById( event, context, response )` in `Products.bx` instead of the default `run()` method.

---

## Environment Variables

| Variable | Description |
|---|---|
| `BOXLANG_GCP_ROOT` | Directory where `.bx` handler files are located. Defaults to `/workspace` on GCF Gen2. |
| `BOXLANG_GCP_CLASS` | Absolute path override for the default handler. Bypasses `Lambda.bx` convention. |
| `BOXLANG_GCP_DEBUGMODE` | Set to `true` to enable verbose diagnostic logging. |
| `BOXLANG_GCP_CONFIG` | Absolute path to a custom `boxlang.json` runtime configuration file. |
| `K_SERVICE` | Function name — set automatically by GCF Gen2. |
| `K_REVISION` | Function revision — set automatically by GCF Gen2. |
| `GOOGLE_CLOUD_PROJECT` | GCP project ID — set automatically by GCF Gen2. |

---

## Testing Locally

### Unit and Integration Tests

Run the full test suite with Gradle:

```bash
./gradlew test
```

Tests are located in `src/test/java/ortus/boxlang/runtime/gcp/` and cover:

| Test Class | Coverage |
|---|---|
| `FunctionRunnerTest` | Full HTTP request lifecycle, URI routing, header routing, error handling |
| `HandlerCacheTest` | Cold and warm compilation, cache correctness, multi-class caching |
| `RouteResolutionTest` | URI-to-class resolution, PascalCase conversion, fallback behavior |
| `RequestMapperTest` | HTTP request → event struct mapping |
| `ResponseMapperTest` | BoxLang response struct → HTTP response serialization |

### Smoke Tests

Once the local server is running, execute the included smoke test script:

```bash
./workbench/simple-test.sh
```

The script:
- Waits up to 60 seconds for the server to return a `2xx` response before sending tests
- Checks HTTP status codes — non-`2xx` responses are reported as failures
- Validates JSON responses using `python3 -m json.tool`
- Validates plain-text responses against expected values
- Exits with a non-zero status code if any test fails

Sample output:

```
=========================================
 BoxLang Google Cloud Functions — Smoke Tests
 Target: http://localhost:8080
=========================================

Server is ready.

--- Health check (Lambda.bx) ---
{"status": "ok", "message": "Hello from BoxLang"}
PASS: HTTP 200

--- List products (Products.bx) ---
{"message": "Fetching all products", ...}
PASS: HTTP 200

--- All smoke tests passed.
=========================================
```

---

## Deployment to Google Cloud Functions

### Authenticate with Google Cloud

```bash
gcloud auth login
gcloud config set project <YOUR_PROJECT_ID>
```

### Build the deployment JAR

```bash
./gradlew shadowJar
```

### Deploy

```bash
gcloud functions deploy boxlang-gcf \
  --gen2 \
  --runtime=java21 \
  --region=us-central1 \
  --source=. \
  --entry-point=ortus.boxlang.runtime.gcp.FunctionRunner \
  --trigger-http \
  --allow-unauthenticated \
  --set-env-vars=BOXLANG_GCP_ROOT=/workspace/src/main/resources
```

After deployment, Google Cloud returns the function URL. Use the `BOXLANG_GCP_ROOT` environment variable to point the runtime at the directory containing your `.bx` handler files on the deployed filesystem.

---

## Example Requests

Default handler (`Lambda.bx`):

```bash
curl https://<FUNCTION_URL>/
```

Route to `Products.bx`:

```bash
curl https://<FUNCTION_URL>/products
```

Route to `Products.bx` with a path parameter (first segment determines the class):

```bash
curl https://<FUNCTION_URL>/products/42
```

Invoke a specific method within `Lambda.bx` using the `x-bx-function` header:

```bash
curl -H "x-bx-function: hello" https://<FUNCTION_URL>/
```

POST with a JSON body:

```bash
curl -X POST \
  -H "Content-Type: application/json" \
  -d '{"name": "Widget A"}' \
  https://<FUNCTION_URL>/products
```

---

## How It Works

```
HTTP Request
│
▼
Google Cloud Functions Gen2
│
▼
FunctionRunner  ← implements HttpFunction
│
├─ RequestMapper      converts HttpRequest → BoxLang event struct
│
├─ resolveRoute()     extracts first URI segment → PascalCase → .bx file lookup
│
├─ loadHandler()      compiles .bx class via RunnableLoader, caches in ConcurrentHashMap
│
├─ getFunctionMethod() reads x-bx-function header → method key (default: run)
│
├─ handler.run(event, context, response)  ← your BoxLang code runs here
│
└─ ResponseMapper     writes response struct → GCF HttpResponse (JSON-serializes structs)
```

**Cold start:** The BoxLang runtime is initialized once in a static block when the container first starts. Compiled `.bx` classes are cached in-memory and reused across all warm invocations without recompilation.

**Routing:** The first URI path segment is converted to PascalCase and matched against `.bx` files in `BOXLANG_GCP_ROOT`. If no match is found, `Lambda.bx` is used as the fallback. The routing logic lives entirely inside `FunctionRunner` — no external routing configuration is required.

**Response serialization:** If your handler returns a `Struct` or `Array`, it is automatically JSON-serialized via Jackson. Plain strings are written verbatim. Setting `response.body` directly bypasses the return-value convention.

---

## Notes

- **Handler portability:** The event struct shape intentionally mirrors the AWS API Gateway v2.0 format. Handler files that run on the BoxLang AWS Lambda runtime can be deployed to GCF with no modification in most cases.
- **Sample handlers:** The `src/test/resources/` directory contains working sample handlers (`Lambda.bx`, `Products.bx`, `Customers.bx`) used for local development and testing.
- **Production handlers:** For production deployments, place your `.bx` handler files in `src/main/resources/` or any directory reachable at runtime, and configure `BOXLANG_GCP_ROOT` accordingly.
- **BoxLang modules:** You can use any BoxLang module with this runtime by including it in the deployed filesystem and configuring the BoxLang runtime to load it via a `boxlang.json` configuration file pointed to by `BOXLANG_GCP_CONFIG`.
- **GCF filesystem:** The `/workspace` directory on GCF Gen2 is read-only during execution. The runtime uses the JVM temp directory (`java.io.tmpdir`) as BoxLang's working directory.
