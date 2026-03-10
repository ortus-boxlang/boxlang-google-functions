#!/usr/bin/env bash
# =============================================================================
# simple-test.sh — Local smoke test for boxlang-google-functions
#
# Start the function server first (in a separate terminal):
#
#   ./gradlew runFunction                    # port 8080 (default)
#   ./gradlew runFunction -Pport=9090        # custom port
#   ./gradlew runFunction -Pdebug=true       # enable BoxLang debug logging
#
# Then, once the server is up, run this script:
#
#   ./workbench/simple-test.sh              # tests against port 8080
#   ./workbench/simple-test.sh 9090         # custom port
#
# The script will wait up to 60 seconds for the server to become ready
# before sending any requests.
# =============================================================================

set -euo pipefail

PORT="${1:-8080}"
BASE="http://localhost:${PORT}"

echo "========================================="
echo " BoxLang Google Cloud Functions — Smoke Tests"
echo " Target: ${BASE}"
echo "========================================="

# ---------------------------------------------------------------------------
# wait_for_server — poll until the server responds or the timeout expires
# ---------------------------------------------------------------------------
wait_for_server() {
  local max_wait=60
  local interval=2
  local elapsed=0

  echo ""
  echo "Waiting for server at ${BASE} ..."
  while ! curl -s --max-time 2 "${BASE}/" > /dev/null 2>&1; do
    if [[ ${elapsed} -ge ${max_wait} ]]; then
      echo ""
      echo "ERROR: Server did not start within ${max_wait} seconds."
      echo "Start it first with:  ./gradlew runFunction"
      exit 1
    fi
    printf "."
    sleep ${interval}
    elapsed=$(( elapsed + interval ))
  done
  echo ""
  echo "Server is ready."
}

# ---------------------------------------------------------------------------
# run_test — send one HTTP request and pretty-print the JSON response
# ---------------------------------------------------------------------------
run_test() {
  local label="$1"
  local path="$2"
  local method="${3:-GET}"
  local body="${4:-}"
  local extra_headers="${5:-}"

  echo ""
  echo "--- ${label} ---"
  if [[ -n "$body" ]]; then
    curl -s -X "${method}" \
      -H "Content-Type: application/json" \
      ${extra_headers} \
      -d "${body}" \
      "${BASE}${path}" | python3 -m json.tool 2>/dev/null || true
  else
    curl -s -X "${method}" \
      -H "Accept: application/json" \
      ${extra_headers} \
      "${BASE}${path}" | python3 -m json.tool 2>/dev/null || true
  fi
}

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------
wait_for_server

# Health / default Lambda.bx
run_test "Health check (Lambda.bx)" "/"

# URI routing
run_test "List products (Products.bx)"        "/products"
run_test "Get product by ID (Products.bx)"   "/products/42"
run_test "List customers (Customers.bx)"      "/customers"

# x-bx-function header routing
run_test "x-bx-function: hello (Lambda.bx)" "/" "GET" "" '-H "x-bx-function: hello"'

echo ""
echo "========================================="
echo " All smoke tests complete."
echo "========================================="
