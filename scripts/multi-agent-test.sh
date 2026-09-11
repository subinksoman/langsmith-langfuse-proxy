#!/usr/bin/env bash
# ===========================================================================
# Drives the multi-agent support flow and checks that every agent call of a
# message lands in the right Langfuse Session.
#
# The flow mixes the two kinds of AI node on purpose:
#   Intent Classifier  - a LangChain Code node, the only kind that can set run
#                        metadata. It seeds session_id, msg_id and the project.
#   Specialist         - native n8n AI Agent node, with a calculator tool and
#                        the session memory.
#   Summariser         - native n8n AI Agent node, rewriting the specialist's
#                        answer. Each waits for the previous one; nothing runs
#                        in parallel and nothing is branched away.
#
# n8n gives the native agents no way to
#                        carry metadata and wraps their model in a proxy that
# carry metadata and wraps their model in a proxy that discards any set on it,
# so their traces arrive with an execution id and nothing else. The proxy
# correlates them onto the seeded session by that execution id.
#
# Plus non-agent nodes: Webhook and three plain Code nodes. There is no
# normalisation step - the webhook body is read as it arrives, and the only
# fallback is a missing user, which becomes "sixdee".
#
#   LANGFUSE_PUBLIC_KEY=... LANGFUSE_SECRET_KEY=... ./scripts/multi-agent-test.sh
# ===========================================================================
set -uo pipefail
cd "$(dirname "$0")/.."

N8N_URL="${N8N_URL:-http://localhost:5678}"
LANGFUSE_URL="${LANGFUSE_URL:-http://localhost:3100}"
N8N_CONTAINER="${N8N_CONTAINER:-n8n-langfuse-test}"
: "${LANGFUSE_PUBLIC_KEY:?set LANGFUSE_PUBLIC_KEY}"
: "${LANGFUSE_SECRET_KEY:?set LANGFUSE_SECRET_KEY}"
AUTH=$(printf '%s:%s' "$LANGFUSE_PUBLIC_KEY" "$LANGFUSE_SECRET_KEY" | base64 -w0)

SID="${SESSION_ID:-6d_$(date +%s)}"

pass=0; fail=0
check() {
  if [[ "$2" == *"$3"* ]]; then printf '  \033[32mPASS\033[0m %s\n' "$1"; pass=$((pass+1));
  else printf '  \033[31mFAIL\033[0m %s\n        expected: %s\n        in: %s\n' "$1" "$3" "${2:0:300}"; fail=$((fail+1)); fi
}
equals() {
  if [[ "$2" == "$3" ]]; then printf '  \033[32mPASS\033[0m %s (%s)\n' "$1" "$2"; pass=$((pass+1));
  else printf '  \033[31mFAIL\033[0m %s: got %s, want %s\n' "$1" "$2" "$3"; fail=$((fail+1)); fi
}

echo "=== 1. Deploy the flow ==="
# import resets the published state, so publish and restart every time.
docker exec "$N8N_CONTAINER" mkdir -p /home/node/wf
docker cp n8n-workflows/credentials.json          "$N8N_CONTAINER":/home/node/wf/ >/dev/null
docker cp n8n-workflows/multi-agent-support.json  "$N8N_CONTAINER":/home/node/wf/ >/dev/null
docker exec "$N8N_CONTAINER" n8n import:credentials --input=/home/node/wf/credentials.json 2>&1 | tail -1
docker exec "$N8N_CONTAINER" n8n import:workflow    --input=/home/node/wf/multi-agent-support.json 2>&1 | tail -1
docker exec "$N8N_CONTAINER" n8n publish:workflow   --id=multiAgentSupport >/dev/null 2>&1
docker restart "$N8N_CONTAINER" >/dev/null
until curl -sf -m 5 "$N8N_URL/healthz" >/dev/null 2>&1; do sleep 3; done
sleep 6
echo "  webhook ready"
echo

echo "=== 2. Five messages on session $SID ==="
declare -a INTENTS
i=0
while IFS='|' read -r mid q; do
  i=$((i+1))
  resp=$(curl -s -m 60 -X POST "$N8N_URL/webhook/support" -H 'Content-Type: application/json' -d @- <<JSON
{"action":"sendMessage","sessionId":"$SID","route":"support","chatInput":"$q",
 "message":{"payload":"$q"},"msg_id":"$mid","user_id":"subin"}
JSON
)
  intent=$(python3 -c 'import json,sys;print((json.loads(sys.argv[1]) or {}).get("intent"))' "$resp" 2>/dev/null)
  INTENTS+=("$intent")
  printf '  %-8s intent=%-8s %s\n' "$mid" "$intent" "$q"
done <<'MSGS'
MSG-001|What is the weather in Kochi?
MSG-002|And what about tomorrow?
MSG-003|I have a billing question about my invoice
MSG-004|Convert 31 celsius to fahrenheit
MSG-005|Who are you?
MSGS

# Sent without user_id at all, to check the "sixdee" fallback.
resp=$(curl -s -m 60 -X POST "$N8N_URL/webhook/support" -H 'Content-Type: application/json' -d @- <<JSON
{"action":"sendMessage","sessionId":"$SID","route":"support","chatInput":"No user id on this one",
 "message":{"payload":"No user id on this one"},"msg_id":"MSG-006"}
JSON
)
printf '  %-8s intent=%-8s %s\n' "MSG-006" \
  "$(python3 -c 'import json,sys;print((json.loads(sys.argv[1]) or {}).get("intent"))' "$resp" 2>/dev/null)" \
  "(no user_id sent)"
nouser=$(python3 -c 'import json,sys;print((json.loads(sys.argv[1]) or {}).get("user_id"))' "$resp" 2>/dev/null)
echo

echo "=== 3. Agent 1's verdict steered agent 2 ==="
all_intents="${INTENTS[*]}"
echo "  intents: $all_intents"
check "weather question classified as weather" "$all_intents" "weather"
check "billing question classified as billing" "$all_intents" "billing"
check "open question classified as general"    "$all_intents" "general"
echo

echo "=== 4. Waiting for Langfuse ==="
sess=""
for i in $(seq 1 40); do
  sess=$(curl -s -m 10 -H "Authorization: Basic $AUTH" "$LANGFUSE_URL/api/public/sessions/$SID")
  n=$(python3 -c 'import json,sys;print(len(json.load(sys.stdin).get("traces") or []))' <<<"$sess" 2>/dev/null || echo 0)
  [[ "$n" -ge 18 ]] && break
  sleep 4
done
echo "  session $SID holds $n traces"
echo

echo "=== 5. Every agent call of every message is in the session ==="
summary=$(python3 -c '
import json,sys
s=json.load(sys.stdin)
tr=sorted(s.get("traces",[]),key=lambda t:t["timestamp"])
rows=[]
for t in tr:
    m=t.get("metadata") or {}
    rows.append({"exec":str(m.get("execution_id")),"msg":m.get("msg_id"),
                 "agent":m.get("agent") or "native-agent","node":m.get("node"),
                 "name":t.get("name"),"user":t.get("userId")})
print(json.dumps({
 "traces": len(tr),
 "executions": len({r["exec"] for r in rows}),
 "msg_ids": sorted({r["msg"] for r in rows if r["msg"]}),
 "msg_id_missing": sum(1 for r in rows if not r["msg"]),
 "classifier_calls": sum(1 for r in rows if r["agent"]=="classifier"),
 "native_agent_calls": sum(1 for r in rows if r["agent"]=="native-agent"),
 "users": sorted({r["user"] for r in rows}),
 "rows": rows,
}))' <<<"$sess")

python3 -c '
import json,sys
d=json.load(sys.stdin)
print("  %-5s %-9s %-14s %-34s %s" % ("EXEC","MSG_ID","AGENT","TRACE NAME","NODE"))
print("  "+"-"*86)
for r in d["rows"]:
    print("  %-5s %-9s %-14s %-34s %s" % (r["exec"],r["msg"],r["agent"],(r["name"] or "")[:34],r["node"]))
' <<<"$summary"
echo

equals "one execution per message"          "$(python3 -c 'import json,sys;print(json.load(sys.stdin)["executions"])' <<<"$summary")" "6"
equals "agent 1 ran once per message"      "$(python3 -c 'import json,sys;print(json.load(sys.stdin)["classifier_calls"])' <<<"$summary")" "6"
# At least two: the specialist and the summariser. A turn where the specialist
# calls a tool adds one more, because n8n emits each LLM call of an agent loop
# as its own root run and therefore its own trace.
native=$(python3 -c 'import json,sys;print(json.load(sys.stdin)["native_agent_calls"])' <<<"$summary")
if [[ "$native" -ge 12 ]]; then printf '  \033[32mPASS\033[0m agents 2 and 3 traced for every message (%s)\n' "$native"; pass=$((pass+1));
else printf '  \033[31mFAIL\033[0m agents 2 and 3 traced: got %s, want >= 12\n' "$native"; fail=$((fail+1)); fi
check  "all six msg_ids present"           "$summary" '"msg_ids": ["MSG-001", "MSG-002", "MSG-003", "MSG-004", "MSG-005", "MSG-006"]'
check  "no trace left without a msg_id"    "$summary" '"msg_id_missing": 0'
check  "users are the sender or the default" "$summary" '"users": ["sixdee", "subin"]'
equals "missing user_id defaults to sixdee" "${nouser:-}" "sixdee"
echo

echo "=== 5b. The three agents ran one after another ==="
order=$(python3 -c '
import json,sys
d=json.load(sys.stdin)
# Within one execution, traces are listed in timestamp order; agent 1 must be first.
by={}
for r in d["rows"]: by.setdefault(r["exec"],[]).append(r["agent"])
bad=[e for e,seq in by.items() if seq[0]!="classifier" or len(seq)<3]
print("OK" if not bad else "OUT-OF-ORDER "+str(bad))' <<<"$summary")
check "classifier first, three agent traces per execution" "$order" "OK"
echo

echo "=== 6. Routing: OpenRouter traces went to the crm project ==="
routing=$(docker logs --tail 800 langsmith-langfuse-proxy 2>&1 | grep "ROUTING: MATCHED project 'crm'" | tail -1)
check "routed to crm" "${routing:-none}" "MATCHED project 'crm'"
echo

echo "==========================================="
printf 'passed %d, failed %d\n' "$pass" "$fail"
echo "Langfuse → Sessions → $SID"
echo "==========================================="
[[ $fail -eq 0 ]]
