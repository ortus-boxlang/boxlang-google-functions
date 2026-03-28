# BoxLang Google Cloud Functions Runtime (GCF)

## Overview

This repository contains an initial implementation of a **BoxLang runtime adapter for Google Cloud Functions (Gen2)**.

The goal of this project is to replicate the core runtime behavior of the existing `boxlang-aws-lambda` runtime but adapted for the **Google Cloud Functions HTTP runtime using Java 21**.

The runtime allows `.bx` BoxLang handler files to be executed as serverless endpoints inside Google Cloud Functions.

This implementation focuses on:

- Translating GCF HTTP requests into a BoxLang-compatible event structure
- Executing `.bx` handler classes dynamically
- Routing requests based on URI paths
- Mapping BoxLang responses back into HTTP responses

The repository is currently intended for **engineering review and iteration** and may evolve based on feedback.

---

## Current Implementation Scope

The runtime currently supports:

- Execution of BoxLang `.bx` handlers inside Google Cloud Functions
- URI-based routing to handler classes
- Dynamic compilation and caching of `.bx` files
- Mapping of HTTP requests into BoxLang event structs
- Serialization of BoxLang response structs back to HTTP responses
- Local development using the GCF Java Function Invoker
- Deployment to Google Cloud Functions Gen2

This implementation mirrors the behavior of the AWS runtime where possible so that handler code remains portable.

---

## Architecture Summary

The runtime is composed of several focused components:

| Component | Responsibility |
|---|---|
| `FunctionRunner` | Main GCF entrypoint (`HttpFunction`); orchestrates all layers including URI routing and handler compilation/caching |
| `RequestMapper` | Converts `HttpRequest` → BoxLang event struct |
| `ResponseMapper` | Converts BoxLang response structs → HTTP response |

---

## Request / Response Flow

```
HTTP Request
│
▼
Google Cloud Functions (Gen2)
│
▼
FunctionRunner
│
▼
RequestMapper
│
▼
BoxLang Runtime
│
▼
Lambda.bx / Products.bx / Customers.bx
│
▼
ResponseMapper
│
▼
HTTP JSON Response
```

The runtime compiles `.bx` files dynamically and caches the compiled classes for reuse across warm invocations.

---

## URI Routing Behavior

Requests are routed automatically based on the first path segment.

| Request Path | Handler |
|---|---|
| `/` | `Lambda.bx` |
| `/products` | `Products.bx` |
| `/customers` | `Customers.bx` |
| `/products/42` | `Products.bx` |

If no matching handler exists, the runtime falls back to `Lambda.bx`.

---

## Handler Convention

Handlers are implemented as BoxLang classes exposing a `run()` method.

Example handler:

```boxlang
class {

    function run( event, context, response ) {

        return {
            "message": "Hello from BoxLang"
        };

    }

}
```

### Parameters

| Parameter | Description |
|---|---|
| `event` | Request data struct |
| `context` | Runtime metadata |
| `response` | Mutable response struct |

---

## Project Structure

```
boxlang-google-functions
│
├── src/main/java/ortus/boxlang/runtime/gcp
│   ├── FunctionRunner.java
│   ├── RequestMapper.java
│   └── ResponseMapper.java
│
├── src/test/resources
│   ├── Lambda.bx
│   ├── Products.bx
│   └── Customers.bx
│
├── workbench
│   └── simple-test.sh
│
├── build.gradle
└── README.md
```

```
Note: During local development, sample `.bx` handlers are loaded from `src/test/resources`.  
In production deployments, handlers should be placed in `src/main/resources` or another directory specified via `BOXLANG_GCP_ROOT`.
```

---

## Build Instructions

Run tests:

```bash
./gradlew test
```

Full build:

```bash
./gradlew clean build
```

Format code:

```bash
./gradlew spotlessApply
```

---

## Local Development

The runtime can be executed locally using the Google Cloud Function Invoker.

Start the local server:

```bash
./gradlew runFunction
```

Expected output:

```
BoxLang GCF Function Invoker
Listening on http://localhost:8080
```

---

## Local Testing

Run the included smoke test script:

```bash
./workbench/simple-test.sh
```

This script sends requests to:

- `/`
- `/products`
- `/customers`

and prints the JSON responses.

---

## Deploying to Google Cloud Functions

Authenticate with Google Cloud:

```bash
gcloud auth login
```

Set your project:

```bash
gcloud config set project <YOUR_PROJECT_ID>
```

Deploy the function:

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

After deployment completes, Google Cloud will return the function URL.

---

## Environment Variables

| Variable | Description |
|---|---|
| `BOXLANG_GCP_ROOT` | Directory where `.bx` handler files are located |
| `BOXLANG_GCP_DEBUGMODE` | Enables debug logging |

For deployments using the repository layout:

```
BOXLANG_GCP_ROOT=/workspace/src/main/resources
```

---

## Example Endpoints

After deployment the following endpoints were validated:

- `GET /`
- `GET /products`
- `GET /customers`
- `GET /products/42`

Example request:

```bash
curl https://<FUNCTION_URL>/products
```

Example response:

```json
{
  "message": "Fetching all products",
  "data": [
    {
      "id": 1,
      "name": "Widget A",
      "price": 19.99
    }
  ]
}
```

---

## Current Validation Status

The runtime has been validated with:

- Local execution using the GCF Java Function Invoker (`./gradlew runFunction`)
- Local smoke tests (`./workbench/simple-test.sh`)
- Deployment to Google Cloud Functions Gen2

Verified live endpoints:

- `/`
- `/products`
- `/customers`
- `/products/42`

All endpoints returned successful HTTP responses and expected JSON payloads.

---

## Notes / Follow-Up Areas

This repository represents an initial runtime adapter implementation and may evolve after engineering review.

Potential follow-up areas include:

- Additional routing edge cases
- Improved event compatibility
- Performance tuning
- Additional automated tests
- Parity improvements with the AWS Lambda runtime

---

## License

Apache License 2.0
