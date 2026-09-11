#!/usr/bin/env bash
# ===========================================================================
# Runs a real n8n workflow and asserts what reached Langfuse.
#
# The workflow is a Basic LLM Chain driven by the mock OpenAI server, so it
# produces the full four-level LangChain trace an AI node really emits —
# chain, prompt, chat model, output parser — with token usage and a
# finish_reason. Anything the proxy drops shows up as a missing assertion.
#
#   LANGFUSE_PUBLIC_KEY=... LANGFUSE_SECRET_KEY=... ./scripts/n8n-workflow-test.sh
# ===========================================================================
set -uo pipefail
cd "$(dirname "$0")/.."

LANGFUSE_URL="${LANGFUSE_URL:-http://localhost:3100}"
N8N_CONTAINER="${N8N_CONTAINER:-n8n-langfuse-test}"
: "${LANGFUSE_PUBLIC_KEY:?set LANGFUSE_PUBLIC_KEY}"
: "${LANGFUSE_SECRET_KEY:?set LANGFUSE_SECRET_KEY}"
AUTH=$(printf '%s:%s' "$LANGFUSE_PUBLIC_KEY" "$LANGFUSE_SECRET_KEY" | base64 -w0)

pass=0; fail=0
check() {
  if [[ "$2" == *"$3"* ]]; then printf '  \033[32mPASS\033[0m %s\n' "$1"; pass=$((pass+1));
  else printf '  \033[31mFAIL\033[0m %s\n        expected: %s\n        in: %s\n' "$1" "$3" "${2:0:400}"; fail=$((fail+1)); fi
}

echo "=== 1. Import workflow + credential into n8n ==="
docker exec "$N8N_CONTAINER" mkdir -p /home/node/wf
for f in credentials.json weather-agent.json weather-routed.json crm-session-routing.json; do
  docker cp "n8n-workflows/$f" "$N8N_CONTAINER":/home/node/wf/"$f" >/dev/null
done
docker exec "$N8N_CONTAINER" n8n import:credentials --input=/home/node/wf/credentials.json 2>&1 | tail -1
docker exec "$N8N_CONTAINER" n8n import:workflow    --input=/home/node/wf/weather-agent.json  2>&1 | tail -1
docker exec "$N8N_CONTAINER" n8n import:workflow    --input=/home/node/wf/weather-routed.json 2>&1 | tail -1
docker exec "$N8N_CONTAINER" n8n import:workflow    --input=/home/node/wf/crm-session-routing.json 2>&1 | tail -1
echo

# The CLI spins up its own task-runner broker, which collides with the port the
# running n8n server already holds.
exec_wf() {
  docker exec -e N8N_RUNNERS_ENABLED=false -e N8N_RUNNERS_BROKER_PORT=5680 \
    "$N8N_CONTAINER" n8n execute --id="$1" 2>&1 | grep -E '"status"|"error"' | head -2
}

# Only look at traces produced by this run: the workflow name is reused across
# runs, so without a lower bound the first (stale) trace would be asserted.
START_TS=$(date -u -d '-10 seconds' +%Y-%m-%dT%H:%M:%SZ)

echo "=== 2. Execute 'Weather Agent (Langfuse test)' ==="
exec_wf weatherAgentTest
echo

echo "=== 3. Execute 'weather'-named node (per-project routing) ==="
exec_wf weatherRoutedTest
echo

echo "=== 4. Execute 'crm' chain that injects session + user + routing metadata ==="
exec_wf crmRoutedTest
echo

echo "=== 5. Proxy routing decision ==="
routing=$(docker logs --tail 600 langsmith-langfuse-proxy 2>&1 | grep "ROUTING: MATCHED" | tail -6)
echo "${routing:-  (no MATCHED line found)}"
check "routed to the 'weather' project by n8n node name" "$routing" "MATCHED project 'weather'"
check "routed to the 'crm' project by injected metadata"  "$routing" "MATCHED project 'crm'"
echo

echo "=== 6. Read the trace back from Langfuse ==="
trace=""
for i in $(seq 1 40); do
  id=$(curl -s -m 10 -H "Authorization: Basic $AUTH" \
        --get --data-urlencode "name=Weather Agent (Langfuse test)" \
              --data-urlencode "fromTimestamp=$START_TS" --data-urlencode "limit=10" \
        "$LANGFUSE_URL/api/public/traces" \
      | python3 -c "
import json,sys
d=json.load(sys.stdin).get('data') or []
print(max(d, key=lambda t: t['timestamp'])['id'] if d else '')" 2>/dev/null)
  if [[ -n "$id" ]]; then
    trace=$(curl -s -m 10 -H "Authorization: Basic $AUTH" "$LANGFUSE_URL/api/public/traces/$id")
    [[ "$trace" == *'"observations"'* ]] && break
  fi
  sleep 3
done
if [[ -z "$trace" ]]; then echo "  no trace found in Langfuse"; exit 1; fi
echo "  trace id: $id"
echo

echo "=== 7. Trace ==="
check "named after the n8n workflow" "$trace" "Weather Agent (Langfuse test)"
check "input is the rendered prompt" "$trace" "You are a concise weather assistant"
check "output is the model answer"   "$trace" "Kochi is warm and humid today"
check "n8n execution id in metadata" "$trace" '"execution_id"'
check "n8n workflow id in metadata"  "$trace" "weatherAgentTest"
check "n8n node name in metadata"    "$trace" '"node":"Basic LLM Chain"'
echo

echo "=== 8. Observation tree ==="
python3 -c '
import json,sys
t=json.load(sys.stdin)
obs=sorted(t.get("observations",[]),key=lambda o:o.get("startTime") or "")
ids=set(o["id"] for o in obs)
for o in obs:
    p=o.get("parentObservationId")
    indent="    " if (p in ids and p!=o["id"]) else "  "
    line="[%-10s] %-46s lat=%s tok=%s" % (o["type"], o["name"][:44], o.get("latency"), o.get("totalTokens"))
    print(indent+line)
' <<<"$trace"

summary=$(python3 -c '
import json,sys
t=json.load(sys.stdin)
g=[o for o in t.get("observations",[]) if o["type"]=="GENERATION"]
print(json.dumps({
  "count": len(t.get("observations",[])),
  "types": sorted({o["type"] for o in t.get("observations",[])}),
  "nested": sum(1 for o in t.get("observations",[]) if o.get("parentObservationId")),
  "gen": g[0] if g else {},
}))
' <<<"$trace")

check "four observations captured"      "$summary" '"count": 4'
check "both SPAN and GENERATION types"  "$summary" '"GENERATION"'
check "three nested under the chain"    "$summary" '"nested": 3'
check "model recorded"                  "$summary" "gpt-4o-mini"
check "prompt tokens counted"           "$summary" '"promptTokens": 24'
check "completion tokens counted"       "$summary" '"completionTokens": 23'
check "temperature in modelParameters"  "$summary" '"temperature": 0.7'
check "max_tokens in modelParameters"   "$summary" '"max_tokens": 256'
check "finish_reason captured"          "$summary" '"finish_reason": "stop"'
check "cost calculated"                 "$summary" '"calculatedTotalCost"'
echo

echo "=== 9. Session id is not the LangSmith project name ==="
sid=$(python3 -c 'import json,sys;print(json.load(sys.stdin).get("sessionId"))' <<<"$trace")
echo "  sessionId = $sid  (LANGCHAIN_PROJECT is \"n8n\"; a real session must come from workflow metadata)"
if [[ "$sid" == "n8n" ]]; then
  printf '  \033[31mFAIL\033[0m sessionId must not be the LangSmith project name\n'; fail=$((fail+1))
else
  printf '  \033[32mPASS\033[0m sessionId is not the LangSmith project name\n'; pass=$((pass+1))
fi
echo

echo "=== 10. Session and user from the crm workflow's injected metadata ==="
crm=""
for i in $(seq 1 30); do
  cid=$(curl -s -m 10 -H "Authorization: Basic $AUTH" \
        --get --data-urlencode "sessionId=chat-session-kochi-42" --data-urlencode "limit=10" \
        "$LANGFUSE_URL/api/public/traces" \
      | python3 -c "
import json,sys
d=json.load(sys.stdin).get('data') or []
print(max(d, key=lambda t: t['timestamp'])['id'] if d else '')" 2>/dev/null)
  if [[ -n "$cid" ]]; then crm=$(curl -s -m 10 -H "Authorization: Basic $AUTH" "$LANGFUSE_URL/api/public/traces/$cid"); break; fi
  sleep 3
done
check "trace grouped into the injected session" "$crm" '"sessionId":"chat-session-kochi-42"'
check "user attributed from metadata"           "$crm" '"userId":"subin"'
check "named from injected workflow_name"       "$crm" "CRM Weather Agent"
check "node:crm tag forwarded"                  "$crm" "node:crm"
echo

echo "==========================================="
printf 'passed %d, failed %d\n' "$pass" "$fail"
echo "==========================================="
[[ $fail -eq 0 ]]
