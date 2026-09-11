#!/usr/bin/env bash
# ===========================================================================
# The correlation hierarchy:
#
#   sessionId                 the conversation - many messages
#     └── msg_id / trace      ONE user message - minted once at entry, carried
#           ├── Agent 1 ──> execution_id       per-agent, differs each time
#           ├── Agent 2 ──> execution_id
#           ├── Agent 3 ──> execution_id
#           └── Final response
#
# What this asserts, and what used to be false: every agent, sub-agent, tool
# and node that handles one user message lands in ONE Langfuse trace. Each n8n
# AI node opens its own LangSmith trace, so a message through three agents used
# to arrive as three unrelated traces; the proxy now keys the trace on
# (sessionId, msg_id) and keeps execution_id purely at observation level.
#
#   LANGFUSE_PUBLIC_KEY=... LANGFUSE_SECRET_KEY=... ./scripts/correlation-test.sh
# ===========================================================================
set -uo pipefail
cd "$(dirname "$0")/.."

N8N_URL="${N8N_URL:-http://localhost:5678}"
LANGFUSE_URL="${LANGFUSE_URL:-http://localhost:3100}"
N8N_CONTAINER="${N8N_CONTAINER:-n8n-langfuse-test}"
: "${LANGFUSE_PUBLIC_KEY:?set LANGFUSE_PUBLIC_KEY}"
: "${LANGFUSE_SECRET_KEY:?set LANGFUSE_SECRET_KEY}"
AUTH=$(printf '%s:%s' "$LANGFUSE_PUBLIC_KEY" "$LANGFUSE_SECRET_KEY" | base64 -w0)
SID="${SESSION_ID:-6d_corr_$(date +%s)}"

pass=0; fail=0
check()  { if [[ "$2" == *"$3"* ]]; then printf '  \033[32mPASS\033[0m %s\n' "$1"; pass=$((pass+1));
           else printf '  \033[31mFAIL\033[0m %s\n        expected: %s\n        in: %s\n' "$1" "$3" "${2:0:300}"; fail=$((fail+1)); fi }
equals() { if [[ "$2" == "$3" ]]; then printf '  \033[32mPASS\033[0m %s (%s)\n' "$1" "$2"; pass=$((pass+1));
           else printf '  \033[31mFAIL\033[0m %s: got %s, want %s\n' "$1" "$2" "$3"; fail=$((fail+1)); fi }

echo "=== 1. Deploy ==="
docker exec "$N8N_CONTAINER" mkdir -p /home/node/wf
for f in credentials.json multi-agent-support.json; do
  docker cp "n8n-workflows/$f" "$N8N_CONTAINER":/home/node/wf/ >/dev/null
done
docker exec "$N8N_CONTAINER" n8n import:credentials --input=/home/node/wf/credentials.json 2>&1 | tail -1
docker exec "$N8N_CONTAINER" n8n import:workflow --input=/home/node/wf/multi-agent-support.json 2>&1 | tail -1
docker exec "$N8N_CONTAINER" n8n publish:workflow --id=multiAgentSupport >/dev/null 2>&1
docker restart "$N8N_CONTAINER" >/dev/null
until curl -sf -m 5 "$N8N_URL/healthz" >/dev/null 2>&1; do sleep 3; done
sleep 6; echo "  ready"; echo

echo "=== 2. Three messages on session $SID ==="
i=0
while IFS='|' read -r mid q; do
  i=$((i+1))
  curl -s -m 90 -X POST "$N8N_URL/webhook/support" -H 'Content-Type: application/json' -d @- >/dev/null <<JSON
{"action":"sendMessage","sessionId":"$SID","route":"support","chatInput":"$q",
 "message":{"payload":"$q"},"msg_id":"$mid","user_id":"subin"}
JSON
  printf '  %-8s %s\n' "$mid" "$q"
done <<'MSGS'
MSG-001|What is the weather in Kochi?
MSG-002|And tomorrow?
MSG-003|I have a billing question
MSGS
echo

echo "=== 2b. A message with NEITHER id — both must be minted ==="
bare=$(curl -s -m 90 -X POST "$N8N_URL/webhook/support" -H 'Content-Type: application/json' \
  -d '{"action":"sendMessage","chatInput":"No ids at all on this one"}')
bare_sid=$(python3 -c 'import json,sys;print((json.loads(sys.argv[1]) or {}).get("sessionId"))' "$bare" 2>/dev/null)
bare_msg=$(python3 -c 'import json,sys;print((json.loads(sys.argv[1]) or {}).get("msg_id"))' "$bare" 2>/dev/null)
echo "  minted sessionId: $bare_sid"
echo "  minted msg_id   : $bare_msg"
case "$bare_sid" in SESSION-*) printf '  \033[32mPASS\033[0m sessionId minted when absent\n'; pass=$((pass+1));;
  *) printf '  \033[31mFAIL\033[0m sessionId not minted: %s\n' "$bare_sid"; fail=$((fail+1));; esac
case "$bare_msg" in MSG-*) printf '  \033[32mPASS\033[0m msg_id minted when absent\n'; pass=$((pass+1));;
  *) printf '  \033[31mFAIL\033[0m msg_id not minted: %s\n' "$bare_msg"; fail=$((fail+1));; esac
echo

echo "=== 3. Waiting for Langfuse ==="
sess=""
for n in $(seq 1 40); do
  sess=$(curl -s -m 10 -H "Authorization: Basic $AUTH" "$LANGFUSE_URL/api/public/sessions/$SID")
  c=$(python3 -c 'import json,sys;print(len(json.load(sys.stdin).get("traces") or []))' <<<"$sess" 2>/dev/null || echo 0)
  [[ "$c" -ge 3 ]] && break
  sleep 4
done
echo "  session holds $c trace(s)"; echo

# Fetch each trace in full - the session listing carries no observations.
details="["
first=1
for tid in $(python3 -c 'import json,sys;[print(t["id"]) for t in (json.load(sys.stdin).get("traces") or [])]' <<<"$sess"); do
  d=$(curl -s -m 15 -H "Authorization: Basic $AUTH" "$LANGFUSE_URL/api/public/traces/$tid")
  [[ $first -eq 0 ]] && details+=","
  details+="$d"; first=0
done
details+="]"

REPORT='
import json,sys
s={"traces": json.load(sys.stdin)}
tr=sorted(s.get("traces",[]),key=lambda t:t["timestamp"])
out={"traces":len(tr),"per_msg":{},"rows":[]}
for t in tr:
    m=t.get("metadata") or {}
    obs=t.get("observations") or []
    out["per_msg"][m.get("msg_id")]={"trace":t["id"],"observations":len(obs),
        "executions":sorted({str((o.get("metadata") or {}).get("execution_id")) for o in obs if (o.get("metadata") or {}).get("execution_id")}),
        "node_exec":sorted({(o.get("metadata") or {}).get("node_execution_id") for o in obs if (o.get("metadata") or {}).get("node_execution_id")})}
    out["rows"].append({"msg":m.get("msg_id"),"trace":t["id"],"obs":len(obs),"name":t.get("name"),
                        "input":json.dumps(t.get("input"))[:60],"output":json.dumps(t.get("output"))[:60]})
out["msg_ids"]=sorted(k for k in out["per_msg"] if k)
out["distinct_traces"]=len({v["trace"] for v in out["per_msg"].values()})
print(json.dumps(out))'
rep=$(python3 -c "$REPORT" <<<"$details")

echo "=== 4. One trace per user message ==="
python3 -c '
import json,sys
d=json.load(sys.stdin)
print("  %-9s %-38s %-5s %s" % ("MSG_ID","TRACE","OBS","OUTPUT"))
print("  "+"-"*88)
for r in d["rows"]: print("  %-9s %-38s %-5s %s" % (r["msg"],r["trace"],r["obs"],r["output"]))
print()
for msg,v in sorted(d["per_msg"].items()):
    print("  %-9s executions=%s" % (msg, v["executions"]))
    print("  %-9s agents    =%s" % ("", v["node_exec"]))
' <<<"$rep"
echo

equals "one trace per message, three messages" "$(python3 -c 'import json,sys;print(json.load(sys.stdin)["traces"])' <<<"$rep")" "3"
equals "no message split across traces"        "$(python3 -c 'import json,sys;print(json.load(sys.stdin)["distinct_traces"])' <<<"$rep")" "3"
check  "all three msg_ids present"             "$rep" '"msg_ids": ["MSG-001", "MSG-002", "MSG-003"]'

echo
echo "=== 5. Every agent of a message is inside its one trace ==="
verdict=$(python3 -c '
import json,sys
d=json.load(sys.stdin)
bad=[m for m,v in d["per_msg"].items() if v["observations"] < 3]
print("OK" if not bad else "TOO-FEW-OBSERVATIONS "+str(bad))' <<<"$rep")
check "each message carries all its agent observations" "$verdict" "OK"

agents=$(python3 -c '
import json,sys
d=json.load(sys.stdin)
# Every agent of a message must appear under that message'"'"'s single trace.
want={"classifier","Specialist Agent","Summariser Agent"}
bad=[m for m,v in d["per_msg"].items()
     if not want <= {x.split("#")[0] for x in v["node_exec"]}]
print("OK" if not bad else "MISSING-AGENTS "+str(bad))' <<<"$rep")
check "classifier, specialist and summariser all under one trace" "$agents" "OK"

oneexec=$(python3 -c '
import json,sys
d=json.load(sys.stdin)
# execution_id is per agent; here all three agents share one n8n execution, so
# the point is simply that it is recorded, not that it is unique.
bad=[m for m,v in d["per_msg"].items() if not v["executions"]]
print("OK" if not bad else "NO-EXECUTION-ID "+str(bad))' <<<"$rep")
check "execution_id recorded on the observations" "$oneexec" "OK"

check "trace input is the user question, not an agent prompt" "$rep" "weather in Kochi"
echo
echo "=== 6. The minted ids reached Langfuse as one grouped trace ==="
bsess=""
for n in $(seq 1 30); do
  bsess=$(curl -s -m 10 -H "Authorization: Basic $AUTH" "$LANGFUSE_URL/api/public/sessions/$bare_sid")
  bc=$(python3 -c 'import json,sys;print(len(json.load(sys.stdin).get("traces") or []))' <<<"$bsess" 2>/dev/null || echo 0)
  [[ "$bc" -ge 1 ]] && break
  sleep 4
done
echo "  session $bare_sid holds $bc trace(s)"
equals "minted session groups the minted message" "$bc" "1"
check  "minted msg_id is on the trace"            "$bsess" "$bare_msg"
echo

echo "==========================================="
printf 'passed %d, failed %d\n' "$pass" "$fail"
echo "Langfuse → Sessions → $SID"
echo "==========================================="
[[ $fail -eq 0 ]]
