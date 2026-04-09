#!/usr/bin/env bash
# =============================================================================
# simple-test.sh — Local smoke test for boxlang-google-functions
#
# Start the function server first (in a separate terminal):
#
#   ./gradlew runFunction                    # port 9099 (default)
#   ./gradlew runFunction -Pport=9090        # custom port
#   ./gradlew runFunction -Pdebug=true       # enable BoxLang debug logging
#
# Then, once the server is up, run this script:
#
#   ./workbench/simple-test.sh              # tests against port 9099
#   ./workbench/simple-test.sh 9090         # custom port
#
# The script will wait up to 60 seconds for the server to become ready
# before sending any requests.
# =============================================================================

set -euo pipefail

PORT="${1:-9099}"
BASE="http://localhost:${PORT}"
FAILURES=0

echo "========================================="
echo " BoxLang Google Cloud Functions — Smoke Tests"
echo " Target: ${BASE}"
echo "========================================="

# ---------------------------------------------------------------------------
# wait_for_server — poll until the server returns HTTP 2xx or the timeout expires
# ---------------------------------------------------------------------------
wait_for_server() {
  local max_wait=60
  local interval=2
  local elapsed=0

  echo ""
  echo "Waiting for server at ${BASE} ..."
  while true; do
    local status
    status=$(curl -s -o /dev/null -w "%{http_code}" --max-time 2 "${BASE}/" 2>/dev/null || echo "000")
    if [[ "${status}" =~ ^2 ]]; then
      break
    fi
    if [[ ${elapsed} -ge ${max_wait} ]]; then
      echo ""
      echo "ERROR: Server did not return a 2xx response within ${max_wait} seconds (last status: ${status})."
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
# run_test label path [method [body [expected [extra_curl_args...]]]]
#
# Sends one HTTP request and checks for a 2xx status.
# expected (position 5): "json" (default) validates the body as JSON;
#                        any other string checks that the body equals that value.
# Any arguments from position 6 onward are passed directly to curl, so extra
# headers can be supplied as separate tokens without quoting problems:
#
#   run_test "label" "/path" "GET" "" "json" -H "My-Header: value"
#   run_test "label" "/path" "GET" "" "expected text" -H "My-Header: value"
# ---------------------------------------------------------------------------
run_test() {
  local label="$1"
  local path="$2"
  local method="${3:-GET}"
  local body="${4:-}"
  local expected="${5:-json}"
  # Positions 6+ are passed through to curl verbatim as "${@:6}"

  echo ""
  echo "--- ${label} ---"

  local http_status
  if [[ -n "$body" ]]; then
    http_status=$(curl -s -o /tmp/bx_response.txt -w "%{http_code}" \
      -X "${method}" \
      -H "Content-Type: application/json" \
      "${@:6}" \
      -d "${body}" \
      "${BASE}${path}")
  else
    http_status=$(curl -s -o /tmp/bx_response.txt -w "%{http_code}" \
      -X "${method}" \
      -H "Accept: application/json" \
      "${@:6}" \
      "${BASE}${path}")
  fi

  if [[ ! "${http_status}" =~ ^2 ]]; then
    echo "FAIL: ${label} — HTTP ${http_status}"
    cat /tmp/bx_response.txt
    echo ""
    FAILURES=$(( FAILURES + 1 ))
    return
  fi

  if [[ "${expected}" == "json" ]]; then
    if ! python3 -m json.tool /tmp/bx_response.txt 2>/dev/null; then
      echo "FAIL: ${label} — HTTP ${http_status} but response is not valid JSON:"
      cat /tmp/bx_response.txt
      echo ""
      FAILURES=$(( FAILURES + 1 ))
      return
    fi
  else
    local actual
    actual=$(cat /tmp/bx_response.txt)
    if [[ "${actual}" != "${expected}" ]]; then
      echo "FAIL: ${label} — HTTP ${http_status} but expected '${expected}', got '${actual}'"
      echo ""
      FAILURES=$(( FAILURES + 1 ))
      return
    fi
  fi

  echo "PASS: HTTP ${http_status}"
}

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------
wait_for_server

# Health / default Lambda.bx
run_test "Health check (Lambda.bx)" "/"

# URI routing
run_test "List products (Products.bx)"       "/products"
run_test "Get product by ID (Products.bx)"   "/products/42"
run_test "List customers (Customers.bx)"     "/customers"

# x-bx-function header routing — expected plain-text body from hello();
# -H and the header value are separate args at position 6+ so no word-splitting.
run_test "x-bx-function: hello (Lambda.bx)" "/" "GET" "" "Hello Baby" -H "x-bx-function: hello"

echo ""
echo "========================================="
if [[ ${FAILURES} -gt 0 ]]; then
  echo " FAILED: ${FAILURES} test(s) did not pass."
  echo "========================================="
  exit 1
else
  echo " All smoke tests passed."
  echo "========================================="
fi
