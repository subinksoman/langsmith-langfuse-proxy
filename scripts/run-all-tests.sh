#!/usr/bin/env bash
# Runs both halves of the integration check:
#   1. e2e-test.sh     — hand-built POST/PATCH fixtures, asserted field by field
#   2. n8n-sdk-test.js — n8n's own bundled LangChain + LangSmith SDK, which
#                        exercises the multipart endpoint the fixtures do not
set -uo pipefail
cd "$(dirname "$0")/.."

: "${LANGFUSE_PUBLIC_KEY:?set LANGFUSE_PUBLIC_KEY to the target project's key}"
: "${LANGFUSE_SECRET_KEY:?set LANGFUSE_SECRET_KEY to the target project's key}"
N8N_CONTAINER="${N8N_CONTAINER:-n8n-langfuse-test}"

echo "################ 1/2  fixture replay ################"
./scripts/e2e-test.sh
fixture_rc=$?

echo
echo "################ 2/2  real LangChain SDK ############"
# The script has to live inside n8n's module tree for require() to resolve
# @langchain/core and langsmith from the bundled install.
docker cp scripts/n8n-sdk-test.js "$N8N_CONTAINER":/usr/local/lib/node_modules/n8n/n8n-sdk-test.js >/dev/null
docker exec -w /usr/local/lib/node_modules/n8n "$N8N_CONTAINER" node ./n8n-sdk-test.js
sdk_rc=$?

echo
echo "fixture replay: $([[ $fixture_rc -eq 0 ]] && echo PASS || echo FAIL)"
echo "SDK round-trip: $([[ $sdk_rc -eq 0 ]] && echo PASS || echo FAIL)"
[[ $fixture_rc -eq 0 && $sdk_rc -eq 0 ]]
