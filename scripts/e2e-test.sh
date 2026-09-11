#!/usr/bin/env bash
# ===========================================================================
# End-to-end check: replay LangSmith traffic at the proxy the way n8n emits
# it — a POST when the run starts, a separate PATCH when it ends — then read
# the trace back out of Langfuse and assert the details actually landed.
#
# The split is the whole point of the test. The PATCH carries the outputs,
# token usage and end time but almost no identifying fields, so it is the
# request where detail goes missing if the proxy cannot tie it back to the
# trace the POST created.
#
#   ./scripts/e2e-test.sh
#
# Env overrides:
#   PROXY_URL      default http://localhost:3001
#   LANGFUSE_URL   default http://localhost:3100
#   LF_PUBLIC_KEY / LF_SECRET_KEY   keys of the project the proxy writes to.
#                  Required — they are read back through the Langfuse API, so
#                  they must belong to the same project the proxy posts to.
# ===========================================================================
set -uo pipefail

PROXY_URL="${PROXY_URL:-http://localhost:3001}"
LANGFUSE_URL="${LANGFUSE_URL:-http://localhost:3100}"
LF_PUBLIC_KEY="${LF_PUBLIC_KEY:-${LANGFUSE_PUBLIC_KEY:-}}"
LF_SECRET_KEY="${LF_SECRET_KEY:-${LANGFUSE_SECRET_KEY:-}}"

if [[ -z "$LF_PUBLIC_KEY" || -z "$LF_SECRET_KEY" ]]; then
  echo "Set LF_PUBLIC_KEY and LF_SECRET_KEY (or LANGFUSE_PUBLIC_KEY/LANGFUSE_SECRET_KEY)" >&2
  echo "to the API keys of the Langfuse project the proxy writes to." >&2
  exit 2
fi

AUTH=$(printf '%s:%s' "$LF_PUBLIC_KEY" "$LF_SECRET_KEY" | base64 -w0)

# Run timestamps must sit near "now". Langfuse resolves a trace's observations
# with a time window around the trace timestamp, so hardcoded absolute times
# pass in the morning and silently return zero observations later in the day.
# The 0.1s -> 2.6s offsets keep the 2.5s latency the test asserts.
# One epoch, two renderings, so the pair cannot straddle a second boundary.
BASE_EPOCH=$(date -u -d '-60 seconds' +%s)
BASE=$(date -u -d "@$BASE_EPOCH" +%Y-%m-%dT%H:%M:%S)
BASE_PLUS2=$(date -u -d "@$((BASE_EPOCH + 2))" +%Y-%m-%dT%H:%M:%S)
CHAIN_START="${BASE}.000000Z"
LLM_START="${BASE}.100000Z"
LLM_END="${BASE_PLUS2}.600000Z"
CHAIN_END="${BASE_PLUS2}.700000Z"
RUN_TAG="e2e-$(date +%s)"
TRACE_ID="trace-$RUN_TAG"
CHAIN_ID="chain-$RUN_TAG"
LLM_ID="llm-$RUN_TAG"
SESSION_ID="session-$RUN_TAG"

pass=0; fail=0
check() { # check <description> <actual> <expected-substring>
  if [[ "$2" == *"$3"* ]]; then printf '  \033[32mPASS\033[0m %s\n' "$1"; pass=$((pass+1));
  else printf '  \033[31mFAIL\033[0m %s\n        expected to contain: %s\n        got: %s\n' "$1" "$3" "$2"; fail=$((fail+1)); fi
}
absent() { # absent <description> <actual> <forbidden-substring>
  if [[ "$2" != *"$3"* ]]; then printf '  \033[32mPASS\033[0m %s\n' "$1"; pass=$((pass+1));
  else printf '  \033[31mFAIL\033[0m %s\n        should not contain: %s\n' "$1" "$3"; fail=$((fail+1)); fi
}

echo "=== 1. POST — runs start (LangSmith sends the descriptive half) ==="
curl -sS -m 30 -X POST "$PROXY_URL/runs/batch" -H 'Content-Type: application/json' -d @- <<JSON
{"post":[
 {"id":"$CHAIN_ID","name":"AgentExecutor","run_type":"chain","trace_id":"$TRACE_ID",
  "start_time":"$CHAIN_START",
  "inputs":{"input":"what is the weather in Kochi"},
  "extra":{"metadata":{"session_id":"$SESSION_ID","user_id":"subin","workflow_name":"Weather Agent","execution_id":"4242"}},
  "tags":["$RUN_TAG"]},
 {"id":"$LLM_ID","name":"ChatGroq","run_type":"llm","trace_id":"$TRACE_ID","parent_run_id":"$CHAIN_ID",
  "start_time":"$LLM_START",
  "extra":{"metadata":{"ls_provider":"groq","ls_model_name":"llama-3.3-70b-versatile","ls_temperature":0.7,"session_id":"$SESSION_ID"},
           "invocation_params":{"model":"llama-3.3-70b-versatile","temperature":0.7,"max_tokens":1024,"top_p":0.95}},
  "inputs":{"messages":[[
     {"lc":1,"type":"constructor","id":["langchain_core","messages","SystemMessage"],"kwargs":{"content":"You are a weather bot."}},
     {"lc":1,"type":"constructor","id":["langchain_core","messages","HumanMessage"],"kwargs":{"content":"what is the weather in Kochi"}}]]}}
]}
JSON
echo; echo

echo "=== 2. PATCH — runs finish (separate request, no trace_id, no names) ==="
curl -sS -m 30 -X POST "$PROXY_URL/runs/batch" -H 'Content-Type: application/json' -d @- <<JSON
{"patch":[
 {"id":"$LLM_ID","end_time":"$LLM_END",
  "outputs":{"generations":[[{"text":"Kochi is warm and humid, around 31C.","generationInfo":{"finish_reason":"stop"}}]],
             "llmOutput":{"tokenUsage":{"promptTokens":43,"completionTokens":11,"totalTokens":54}}}},
 {"id":"$CHAIN_ID","end_time":"$CHAIN_END",
  "outputs":{"output":"Kochi is warm and humid, around 31C."}}
]}
JSON
echo; echo

echo "=== 3. Waiting for Langfuse to process the batch ==="
trace=""
for i in $(seq 1 40); do
  trace=$(curl -s -m 10 -H "Authorization: Basic $AUTH" "$LANGFUSE_URL/api/public/traces/$TRACE_ID" 2>/dev/null)
  [[ "$trace" == *'"observations"'* ]] && break
  sleep 3
done
echo "  fetched after $((i*3))s"
echo

echo "=== 4. Trace-level details ==="
check "trace exists"                  "$trace" "$TRACE_ID"
check "name kept from the POST"       "$trace" "Weather Agent"
check "session id preserved"          "$trace" "$SESSION_ID"
check "user id preserved"             "$trace" "\"userId\":\"subin\""
check "input present"                 "$trace" "what is the weather in Kochi"
check "output set from the PATCH"     "$trace" "Kochi is warm and humid"
check "n8n execution id in metadata"  "$trace" "4242"
check "tags forwarded"                "$trace" "$RUN_TAG"
absent "not renamed by the PATCH"     "$trace" "AgentRun"
echo

echo "=== 5. Generation-level details ==="
OBS_BY_ID='
import json,sys
trace=json.load(sys.stdin)
for o in trace.get("observations",[]):
    if o.get("id")==sys.argv[1]: print(json.dumps(o)); break
else: print("{}")
'
gen=$(python3 -c "$OBS_BY_ID" "$LLM_ID" <<<"$trace")
check "generation present"            "$gen" "$LLM_ID"
check "typed as GENERATION"           "$gen" "GENERATION"
check "model recorded"                "$gen" "llama-3.3-70b-versatile"
absent "model not 'unknown'"          "$gen" "\"model\": \"unknown\""
check "nested under the chain span"   "$gen" "\"parentObservationId\": \"$CHAIN_ID\""
check "input kept through the PATCH"  "$gen" "You are a weather bot."
check "output from the PATCH"         "$gen" "Kochi is warm and humid"
check "prompt tokens"                 "$gen" "43"
check "completion tokens"             "$gen" "11"
check "modelParameters not wiped"     "$gen" "1024"
check "finish_reason captured"        "$gen" "finish_reason"
check "name kept"                     "$gen" "ChatGroq"
echo

echo "=== 6. Latency (start from the POST, end from the PATCH) ==="
lat=$(python3 -c '
import json,sys
from datetime import datetime
o=json.load(sys.stdin)
s,e=o.get("startTime"),o.get("endTime")
if not s or not e: print("MISSING"); raise SystemExit
p=lambda t: datetime.fromisoformat(t.replace("Z","+00:00"))
print(f"{(p(e)-p(s)).total_seconds():.3f}")
' <<<"$gen")
echo "  generation latency: ${lat}s (expected 2.500)"
check "latency computed from real timestamps" "$lat" "2.5"
echo

echo "=== 7. Chain span ==="
span=$(python3 -c "$OBS_BY_ID" "$CHAIN_ID" <<<"$trace")
check "span present"        "$span" "$CHAIN_ID"
check "span name kept"      "$span" "AgentExecutor"
check "span input"          "$span" "what is the weather in Kochi"
check "span output"         "$span" "Kochi is warm and humid"
absent "span not renamed"   "$span" "\"name\": \"Span\""
echo

echo "==========================================="
printf 'passed %d, failed %d\n' "$pass" "$fail"
echo "Trace: $LANGFUSE_URL/project/.../traces/$TRACE_ID"
echo "==========================================="
[[ $fail -eq 0 ]]
