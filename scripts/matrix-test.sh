#!/usr/bin/env bash
# ===========================================================================
# The correlation model, case by case.
#
# Each case states what must be true and why it could plausibly be false, so a
# failure points at a behaviour rather than just a number.
#
#   LANGFUSE_PUBLIC_KEY=... LANGFUSE_SECRET_KEY=... ./scripts/matrix-test.sh
# ===========================================================================
set -uo pipefail
cd "$(dirname "$0")/.."

N8N_URL="${N8N_URL:-http://localhost:5678}"
LANGFUSE_URL="${LANGFUSE_URL:-http://localhost:3100}"
: "${LANGFUSE_PUBLIC_KEY:?set LANGFUSE_PUBLIC_KEY}"
: "${LANGFUSE_SECRET_KEY:?set LANGFUSE_SECRET_KEY}"
AUTH=$(printf '%s:%s' "$LANGFUSE_PUBLIC_KEY" "$LANGFUSE_SECRET_KEY" | base64 -w0)
RUN=$(date +%s)

pass=0; fail=0
ok()   { printf '  \033[32mPASS\033[0m %s\n' "$1"; pass=$((pass+1)); }
bad()  { printf '  \033[31mFAIL\033[0m %s — %s\n' "$1" "$2"; fail=$((fail+1)); }
want() { [[ "$2" == "$3" ]] && ok "$1 ($2)" || bad "$1" "got '$2', want '$3'"; }
has()  { [[ "$2" == *"$3"* ]] && ok "$1" || bad "$1" "'$3' not in ${2:0:160}"; }

send() { # send <json>
  curl -s -m 120 -X POST "$N8N_URL/webhook/support" -H 'Content-Type: application/json' -d "$1"
}
sess() { curl -s -m 15 -H "Authorization: Basic $AUTH" "$LANGFUSE_URL/api/public/sessions/$1"; }
trace(){ curl -s -m 15 -H "Authorization: Basic $AUTH" "$LANGFUSE_URL/api/public/traces/$1"; }
ntraces(){ python3 -c 'import json,sys;print(len(json.load(sys.stdin).get("traces") or []))' 2>/dev/null || echo 0; }
waitfor(){ # waitfor <session> <count>
  for _ in $(seq 1 40); do
    [[ "$(sess "$1" | ntraces)" -ge "$2" ]] && return 0
    sleep 4
  done
  return 1
}

echo "############ 1. Both ids supplied ############"
S="mx1-$RUN"
send "{\"sessionId\":\"$S\",\"msg_id\":\"M1\",\"chatInput\":\"What is the weather in Kochi?\",\"user_id\":\"subin\"}" >/dev/null
waitfor "$S" 1
j=$(sess "$S")
want "one trace for one message" "$(ntraces <<<"$j")" "1"
tid=$(python3 -c 'import json,sys;print(json.load(sys.stdin)["traces"][0]["id"])' <<<"$j")
t=$(trace "$tid")
has "msg_id preserved"   "$t" '"msg_id":"M1"'
has "session preserved"  "$t" "$S"
has "user preserved"     "$t" '"userId":"subin"'
has "input is the question" "$t" "weather in Kochi"
echo

echo "############ 2. Only sessionId — msg_id minted ############"
S="mx2-$RUN"
r=$(send "{\"sessionId\":\"$S\",\"chatInput\":\"hello there\"}")
m=$(python3 -c 'import json,sys;print((json.loads(sys.argv[1]) or {}).get("msg_id"))' "$r" 2>/dev/null)
case "$m" in MSG-*) ok "msg_id minted ($m)";; *) bad "msg_id minted" "got '$m'";; esac
has "caller's session kept" "$r" "$S"
echo

echo "############ 3. Only msg_id — sessionId minted ############"
r=$(send "{\"msg_id\":\"M3-$RUN\",\"chatInput\":\"hello again\"}")
s3=$(python3 -c 'import json,sys;print((json.loads(sys.argv[1]) or {}).get("sessionId"))' "$r" 2>/dev/null)
case "$s3" in SESSION-*) ok "sessionId minted ($s3)";; *) bad "sessionId minted" "got '$s3'";; esac
has "caller's msg_id kept" "$r" "M3-$RUN"
echo

echo "############ 4. Neither id — both minted, no user ############"
r=$(send '{"chatInput":"no ids and no user"}')
python3 -c '
import json,sys
d=json.loads(sys.argv[1])
print(d.get("sessionId"),d.get("msg_id"),d.get("user_id"))' "$r" 2>/dev/null | read -r s4 m4 u4 || true
s4=$(python3 -c 'import json,sys;print((json.loads(sys.argv[1]) or {}).get("sessionId"))' "$r")
m4=$(python3 -c 'import json,sys;print((json.loads(sys.argv[1]) or {}).get("user_id"))' "$r")
case "$s4" in SESSION-*) ok "both minted ($s4)";; *) bad "both minted" "got '$s4'";; esac
want "missing user defaults" "$m4" "sixdee"
echo

echo "############ 5. Multi-turn: one session, three messages ############"
S="mx5-$RUN"
for n in 1 2 3; do
  send "{\"sessionId\":\"$S\",\"msg_id\":\"T$n\",\"chatInput\":\"turn $n please\",\"user_id\":\"subin\"}" >/dev/null
done
waitfor "$S" 3
j=$(sess "$S")
want "three messages, three traces" "$(ntraces <<<"$j")" "3"
ids=$(python3 -c '
import json,sys
d=json.load(sys.stdin)["traces"]
print(",".join(sorted((t.get("metadata") or {}).get("msg_id") or "?" for t in d)))' <<<"$j")
want "each turn its own msg_id" "$ids" "T1,T2,T3"
echo

echo "############ 6. Same msg_id twice — a retry must not split ############"
S="mx6-$RUN"
send "{\"sessionId\":\"$S\",\"msg_id\":\"R1\",\"chatInput\":\"first attempt\",\"user_id\":\"subin\"}" >/dev/null
waitfor "$S" 1
send "{\"sessionId\":\"$S\",\"msg_id\":\"R1\",\"chatInput\":\"retry of the same message\",\"user_id\":\"subin\"}" >/dev/null
sleep 12
want "retry lands on the same trace" "$(sess "$S" | ntraces)" "1"
echo

echo "############ 7. Two sessions interleaved — no leakage ############"
A="mx7a-$RUN"; B="mx7b-$RUN"
send "{\"sessionId\":\"$A\",\"msg_id\":\"A1\",\"chatInput\":\"session A first\",\"user_id\":\"subin\"}" >/dev/null
send "{\"sessionId\":\"$B\",\"msg_id\":\"B1\",\"chatInput\":\"session B first\",\"user_id\":\"subin\"}" >/dev/null
send "{\"sessionId\":\"$A\",\"msg_id\":\"A2\",\"chatInput\":\"session A second\",\"user_id\":\"subin\"}" >/dev/null
waitfor "$A" 2; waitfor "$B" 1
ja=$(sess "$A"); jb=$(sess "$B")
want "session A has its two"   "$(ntraces <<<"$ja")" "2"
want "session B has its one"   "$(ntraces <<<"$jb")" "1"
[[ "$ja" != *'"B1"'* ]] && ok "no B message leaked into A" || bad "no B message leaked into A" "found B1"
[[ "$jb" != *'"A1"'* ]] && ok "no A message leaked into B" || bad "no A message leaked into B" "found A1"
echo

echo "############ 8. Sub-workflow — parent and child in ONE trace ############"
S="mx8-$RUN"
curl -s -m 120 -X POST "$N8N_URL/webhook/subflow" -H 'Content-Type: application/json' \
  -d "{\"sessionId\":\"$S\",\"msg_id\":\"SUB1\",\"chatInput\":\"weather please\",\"user_id\":\"subin\"}" >/dev/null
waitfor "$S" 1
j=$(sess "$S")
want "one trace despite two executions" "$(ntraces <<<"$j")" "1"
tid=$(python3 -c 'import json,sys;print(json.load(sys.stdin)["traces"][0]["id"])' <<<"$j")
ex=$(trace "$tid" | python3 -c '
import json,sys
t=json.load(sys.stdin)
print(len({str((o.get("metadata") or {}).get("execution_id")) for o in (t.get("observations") or []) if (o.get("metadata") or {}).get("execution_id")}))')
want "both n8n executions inside it" "$ex" "2"
echo

echo "############ 9. Tool call, sequential — must attach ############"
S="mx9-$RUN"
send "{\"sessionId\":\"$S\",\"msg_id\":\"TOOL1\",\"chatInput\":\"Convert 31 celsius to fahrenheit\",\"user_id\":\"subin\"}" >/dev/null
waitfor "$S" 1
tid=$(sess "$S" | python3 -c 'import json,sys;print(json.load(sys.stdin)["traces"][0]["id"])')
tools=$(trace "$tid" | python3 -c '
import json,sys
t=json.load(sys.stdin)
print(",".join(o["name"] for o in (t.get("observations") or []) if (o.get("metadata") or {}).get("run_type")=="tool") or "none")')
want "tool inside the message trace" "$tools" "Calculator"
echo

echo "############ 10. Tool calls, concurrent — abstain, never misattribute ############"
C1="mx10a-$RUN"; C2="mx10b-$RUN"
for s in "$C1" "$C2"; do
  ( send "{\"sessionId\":\"$s\",\"msg_id\":\"CT\",\"chatInput\":\"Convert 31 celsius to fahrenheit\",\"user_id\":\"subin\"}" >/dev/null ) &
done
wait
waitfor "$C1" 1; waitfor "$C2" 1
mis=0
for s in "$C1" "$C2"; do
  tid=$(sess "$s" | python3 -c 'import json,sys;print(json.load(sys.stdin)["traces"][0]["id"])')
  n=$(trace "$tid" | python3 -c '
import json,sys
t=json.load(sys.stdin)
print(sum(1 for o in (t.get("observations") or []) if (o.get("metadata") or {}).get("run_type")=="tool"))')
  [[ "$n" -gt 1 ]] && mis=1
done
want "no message absorbed both tool runs" "$mis" "0"
echo

echo "==========================================="
printf 'passed %d, failed %d\n' "$pass" "$fail"
echo "==========================================="
[[ $fail -eq 0 ]]
