#!/usr/bin/env bash
# ===========================================================================
# Sub-workflows: does an agent inside one still land in the caller's session?
#
# n8n runs a sub-workflow under its OWN execution id, so the proxy's
# execution correlation — which is scoped to a single execution by design —
# does not reach across the boundary. A sub-workflow built only from native AI
# Agent nodes therefore traces with no session at all.
#
# The fix is one LangChain Code node per execution. It is the only kind of AI
# node that can declare run metadata, it is an agent call in its own right
# (no extra LLM round trip just to seed), and the native agents beside it
# inherit from it through the sub-execution's own id.
#
#   LANGFUSE_PUBLIC_KEY=... LANGFUSE_SECRET_KEY=... ./scripts/subflow-test.sh
# ===========================================================================
set -uo pipefail
cd "$(dirname "$0")/.."

N8N_URL="${N8N_URL:-http://localhost:5678}"
LANGFUSE_URL="${LANGFUSE_URL:-http://localhost:3100}"
N8N_CONTAINER="${N8N_CONTAINER:-n8n-langfuse-test}"
: "${LANGFUSE_PUBLIC_KEY:?set LANGFUSE_PUBLIC_KEY}"
: "${LANGFUSE_SECRET_KEY:?set LANGFUSE_SECRET_KEY}"
AUTH=$(printf '%s:%s' "$LANGFUSE_PUBLIC_KEY" "$LANGFUSE_SECRET_KEY" | base64 -w0)

SID="${SESSION_ID:-6d_sub_$(date +%s)}"

pass=0; fail=0
check() {
  if [[ "$2" == *"$3"* ]]; then printf '  \033[32mPASS\033[0m %s\n' "$1"; pass=$((pass+1));
  else printf '  \033[31mFAIL\033[0m %s\n        expected: %s\n        in: %s\n' "$1" "$3" "${2:0:300}"; fail=$((fail+1)); fi
}
equals() {
  if [[ "$2" == "$3" ]]; then printf '  \033[32mPASS\033[0m %s (%s)\n' "$1" "$2"; pass=$((pass+1));
  else printf '  \033[31mFAIL\033[0m %s: got %s, want %s\n' "$1" "$2" "$3"; fail=$((fail+1)); fi
}

echo "=== 1. Deploy parent + sub-workflow ==="
docker exec "$N8N_CONTAINER" mkdir -p /home/node/wf
for f in credentials.json support-subagent.json subflow-parent.json; do
  docker cp "n8n-workflows/$f" "$N8N_CONTAINER":/home/node/wf/ >/dev/null
done
docker exec "$N8N_CONTAINER" n8n import:credentials --input=/home/node/wf/credentials.json 2>&1 | tail -1
docker exec "$N8N_CONTAINER" n8n import:workflow --input=/home/node/wf/support-subagent.json 2>&1 | tail -1
docker exec "$N8N_CONTAINER" n8n import:workflow --input=/home/node/wf/subflow-parent.json   2>&1 | tail -1
docker exec "$N8N_CONTAINER" n8n publish:workflow --id=subflowParent   >/dev/null 2>&1
docker exec "$N8N_CONTAINER" n8n publish:workflow --id=supportSubagent >/dev/null 2>&1
docker restart "$N8N_CONTAINER" >/dev/null
until curl -sf -m 5 "$N8N_URL/healthz" >/dev/null 2>&1; do sleep 3; done
sleep 6
echo "  webhook ready"
echo

echo "=== 2. Three messages on session $SID ==="
while IFS='|' read -r mid q; do
  curl -s -m 60 -X POST "$N8N_URL/webhook/subflow" -H 'Content-Type: application/json' -d @- >/dev/null <<JSON
{"action":"sendMessage","sessionId":"$SID","route":"support","chatInput":"$q",
 "message":{"payload":"$q"},"msg_id":"$mid","user_id":"subin"}
JSON
  printf '  %-8s %s\n' "$mid" "$q"
done <<'MSGS'
MSG-001|What is the weather in Kochi?
MSG-002|And tomorrow?
MSG-003|Thanks
MSGS
echo

echo "=== 3. Waiting for Langfuse ==="
sess=""
for i in $(seq 1 40); do
  sess=$(curl -s -m 10 -H "Authorization: Basic $AUTH" "$LANGFUSE_URL/api/public/sessions/$SID")
  n=$(python3 -c 'import json,sys;print(len(json.load(sys.stdin).get("traces") or []))' <<<"$sess" 2>/dev/null || echo 0)
  [[ "$n" -ge 9 ]] && break
  sleep 4
done
echo "  session holds $n traces"
echo

echo "=== 4. Parent and sub-workflow traces, per message ==="
summary=$(python3 -c '
import json,sys
s=json.load(sys.stdin)
tr=sorted(s.get("traces",[]),key=lambda t:t["timestamp"])
rows=[{"exec":str((t.get("metadata") or {}).get("execution_id")),
       "msg":(t.get("metadata") or {}).get("msg_id"),
       "agent":(t.get("metadata") or {}).get("agent") or "native-agent",
       "parent":str((t.get("metadata") or {}).get("parent_execution_id")),
       "wf":(t.get("metadata") or {}).get("workflow_name") or t.get("name"),
       "user":t.get("userId")} for t in tr]
by_msg={}
for r in rows: by_msg.setdefault(r["msg"],[]).append(r)
print(json.dumps({
 "traces": len(rows),
 "executions": len({r["exec"] for r in rows}),
 "msg_ids": sorted({r["msg"] for r in rows if r["msg"]}),
 "msg_id_missing": sum(1 for r in rows if not r["msg"]),
 "agents": sorted({r["agent"] for r in rows}),
 "users": sorted({r["user"] for r in rows}),
 "per_message_traces": sorted({len(v) for v in by_msg.values()}),
 "subflow_linked_to_parent": all(
     any(r["agent"]=="subflow" and r["parent"] not in (None,"None") for r in v) for v in by_msg.values()),
 "rows": rows,
}))' <<<"$sess")

python3 -c '
import json,sys
d=json.load(sys.stdin)
print("  %-5s %-8s %-13s %-22s %s" % ("EXEC","MSG_ID","AGENT","WORKFLOW","PARENT_EXEC"))
print("  "+"-"*70)
for r in d["rows"]:
    print("  %-5s %-8s %-13s %-22s %s" % (r["exec"],r["msg"],r["agent"],(r["wf"] or "")[:22],r["parent"]))
' <<<"$summary"
echo

equals "two executions per message (parent + sub)" \
       "$(python3 -c 'import json,sys;print(json.load(sys.stdin)["executions"])' <<<"$summary")" "6"
check  "all three msg_ids present"       "$summary" '"msg_ids": ["MSG-001", "MSG-002", "MSG-003"]'
check  "no trace left without a msg_id"  "$summary" '"msg_id_missing": 0'
check  "parent, sub and native agents"   "$summary" '"agents": ["classifier", "native-agent", "subflow"]'
check  "three traces per message"        "$summary" '"per_message_traces": [3]'
check  "sub-execution records its caller" "$summary" '"subflow_linked_to_parent": true'
check  "user attributed throughout"      "$summary" '"users": ["subin"]'
echo

echo "==========================================="
printf 'passed %d, failed %d\n' "$pass" "$fail"
echo "Langfuse → Sessions → $SID"
echo "==========================================="
[[ $fail -eq 0 ]]
