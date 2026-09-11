#!/usr/bin/env bash
# ===========================================================================
# Drives the chat webhook the way a chat UI does and checks the two
# identifiers end up in the right places in Langfuse.
#
#   sessionId -> the conversation. All calls of a chat carry the same one, and
#                every trace of that chat must land in ONE Langfuse Session,
#                with the chat history growing across turns.
#   msg_id    -> one webhook call. Every message carries a new one, and each
#                must produce its OWN trace, findable by that id.
#
# Two sessions are driven concurrently in spirit (interleaved ids) so the test
# would fail if the proxy or the memory leaked context between them.
#
#   LANGFUSE_PUBLIC_KEY=... LANGFUSE_SECRET_KEY=... ./scripts/chat-webhook-test.sh
# ===========================================================================
set -uo pipefail
cd "$(dirname "$0")/.."

N8N_URL="${N8N_URL:-http://localhost:5678}"
LANGFUSE_URL="${LANGFUSE_URL:-http://localhost:3100}"
: "${LANGFUSE_PUBLIC_KEY:?set LANGFUSE_PUBLIC_KEY}"
: "${LANGFUSE_SECRET_KEY:?set LANGFUSE_SECRET_KEY}"
AUTH=$(printf '%s:%s' "$LANGFUSE_PUBLIC_KEY" "$LANGFUSE_SECRET_KEY" | base64 -w0)

STAMP=$(date +%s)
SID_A="SESSION-1001-$STAMP"
SID_B="SESSION-2002-$STAMP"

pass=0; fail=0
check() {
  if [[ "$2" == *"$3"* ]]; then printf '  \033[32mPASS\033[0m %s\n' "$1"; pass=$((pass+1));
  else printf '  \033[31mFAIL\033[0m %s\n        expected: %s\n        in: %s\n' "$1" "$3" "${2:0:300}"; fail=$((fail+1)); fi
}
equals() {
  if [[ "$2" == "$3" ]]; then printf '  \033[32mPASS\033[0m %s (%s)\n' "$1" "$2"; pass=$((pass+1));
  else printf '  \033[31mFAIL\033[0m %s: got %s, want %s\n' "$1" "$2" "$3"; fail=$((fail+1)); fi
}

send() { # send <sessionId> <msgId> <text>
  curl -s -m 30 -X POST "$N8N_URL/webhook/chat" -H 'Content-Type: application/json' -d @- <<JSON
{"action":"sendMessage","sessionId":"$1","route":"support","chatInput":"$3",
 "message":{"payload":"$3"},"msg_id":"$2","user_id":"subin"}
JSON
}

echo "=== 1. Two interleaved chat sessions ==="
echo "  A: $SID_A     B: $SID_B"
send "$SID_A" "MSG-001" "What is the weather in Kochi today?" >/dev/null
send "$SID_B" "MSG-101" "Who won the last cricket world cup?"  >/dev/null
send "$SID_A" "MSG-002" "And what about tomorrow?"             >/dev/null
send "$SID_A" "MSG-003" "Should I carry an umbrella?"          >/dev/null
send "$SID_B" "MSG-102" "And the one before that?"             >/dev/null
echo "  sent 3 messages on A, 2 on B"
echo

echo "=== 2. Waiting for Langfuse ==="
for i in $(seq 1 40); do
  a=$(curl -s -m 10 -H "Authorization: Basic $AUTH" "$LANGFUSE_URL/api/public/sessions/$SID_A")
  b=$(curl -s -m 10 -H "Authorization: Basic $AUTH" "$LANGFUSE_URL/api/public/sessions/$SID_B")
  na=$(python3 -c 'import json,sys;print(len((json.load(sys.stdin).get("traces") or [])))' <<<"$a" 2>/dev/null || echo 0)
  nb=$(python3 -c 'import json,sys;print(len((json.load(sys.stdin).get("traces") or [])))' <<<"$b" 2>/dev/null || echo 0)
  [[ "$na" == "3" && "$nb" == "2" ]] && break
  sleep 3
done
echo "  session A traces=$na, session B traces=$nb"
echo

echo "=== 3. Sessions segregate by sessionId ==="
equals "session A holds one trace per message" "$na" "3"
equals "session B holds one trace per message" "$nb" "2"

SUMMARY_PY='
import json,sys
s=json.load(sys.stdin)
tr=sorted(s.get("traces",[]),key=lambda t:t["timestamp"])
print(json.dumps({
 "session": s.get("id"),
 "msg_ids": [ (t.get("metadata") or {}).get("msg_id") for t in tr ],
 "tags":    sorted({x for t in tr for x in (t.get("tags") or []) if x.startswith("msg:")}),
 "users":   sorted({t.get("userId") for t in tr}),
 "trace_ids_unique": len({t["id"] for t in tr})==len(tr),
 "history_lengths": [len(t.get("input") or []) for t in tr],
}))'
sa=$(python3 -c "$SUMMARY_PY" <<<"$a")
sb=$(python3 -c "$SUMMARY_PY" <<<"$b")
echo "  A: $sa"
echo "  B: $sb"
echo

echo "=== 4. msg_id identifies each individual call ==="
check "A carries its own msg_ids"        "$sa" '"msg_ids": ["MSG-001", "MSG-002", "MSG-003"]'
check "B carries its own msg_ids"        "$sb" '"msg_ids": ["MSG-101", "MSG-102"]'
check "A msg_ids are filterable tags"    "$sa" '"msg:MSG-001", "msg:MSG-002", "msg:MSG-003"'
check "each message is a distinct trace" "$sa" '"trace_ids_unique": true'
check "no msg_id from B leaked into A"   "$sa" '"msg_ids": ["MSG-001", "MSG-002", "MSG-003"]'
if [[ "$sa" == *"MSG-10"* ]]; then
  printf '  \033[31mFAIL\033[0m session A must not contain session B messages\n'; fail=$((fail+1))
else
  printf '  \033[32mPASS\033[0m session A contains no session B messages\n'; pass=$((pass+1))
fi
echo

echo "=== 5. sessionId carries the conversation context ==="
# Each turn adds one user and one assistant message, so the prompt grows 2,4,6.
check "A history grows across turns" "$sa" '"history_lengths": [2, 4, 6]'
check "B history grows across turns" "$sb" '"history_lengths": [2, 4]'
check "user attributed"              "$sa" '"users": ["subin"]'
echo

echo "=== 6. Conversation as Langfuse sees it (session A) ==="
python3 -c '
import json,sys
s=json.load(sys.stdin)
for t in sorted(s["traces"],key=lambda x:x["timestamp"]):
    m=t.get("metadata") or {}
    print("  %-8s trace=%s" % (m.get("msg_id"), t["id"]))
    for msg in (t.get("input") or [])[-1:]:
        print("      user  > %s" % msg.get("content","")[:70])
    o=t.get("output") or {}
    print("      model < %s" % str(o.get("content",""))[:70])
' <<<"$a"
echo

echo "==========================================="
printf 'passed %d, failed %d\n' "$pass" "$fail"
echo "Session A in Langfuse: $LANGFUSE_URL  →  Sessions  →  $SID_A"
echo "==========================================="
[[ $fail -eq 0 ]]
