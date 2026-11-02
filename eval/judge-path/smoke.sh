#!/usr/bin/env bash
# The README's five-minute path, run the way a judge runs it: a clean checkout, no API keys, no WhatsApp,
# TAPE_MODE=replay. Boots the jar, presses the simulator's buttons over HTTP in the README's order, and fails on
# anything that is not a plain JSON answer. Counts what the judge would see (stages fired, voice notes, cards,
# model calls replayed) and writes it to build/judge-path/ — and to the GitHub job summary when there is one.
#
#   eval/judge-path/smoke.sh                        # lenient: a missing recording is reported, not fatal
#   STRICT=1 eval/judge-path/smoke.sh               # the tape must answer every sample and every stage must have a voice note
#   JUDGE_TAPE_MODE=record eval/judge-path/smoke.sh # the same run with TAPE_MODE=record: keys stay set, answers are
#                                                   # written to the tape, notes with no voice server leave tts/<key>.txt
#                                                   # for eval/judge-path/prerender_voice.py (see .github/workflows/tape.yml)
#
# Needs: Java 21, curl, jq. ffmpeg only for ?format=mp3.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

PORT="${PORT:-18080}"
OUT="${OUT:-build/judge-path}"
STRICT="${STRICT:-0}"
BASE="http://localhost:$PORT"
mkdir -p "$OUT"

export ADMIN_TOKEN="${ADMIN_TOKEN:-judge}"
export TAPE_MODE="${JUDGE_TAPE_MODE:-replay}"
export TAPE_DIR="${TAPE_DIR:-eval/sample-run}"
export SERVER_PORT="$PORT"
export DATABASE_URL="${DATABASE_URL:-jdbc:h2:file:$ROOT/$OUT/db;MODE=PostgreSQL}"
export TTS_CACHE_DIR="$ROOT/$OUT/tts-cache"
unset WHATSAPP_TOKEN WHATSAPP_PHONE_NUMBER_ID 2>/dev/null || true
if [ "$TAPE_MODE" = replay ]; then unset MODEL_API_KEY TTS_BASE_URL SPARK_OPEN_BASE_URL SPARK_OPEN_API_KEY 2>/dev/null || true; fi

fail() { echo "FAIL: $*" >&2; echo "--- last 60 lines of the app log ---" >&2; tail -60 "$OUT/app.log" >&2 || true; exit 1; }

# ---- boot ----------------------------------------------------------------------------------------------------
if [ -z "${JAR:-}" ]; then
  ./gradlew bootJar --no-daemon -q --console=plain
  JAR="$(ls build/libs/*.jar | grep -v -- '-plain.jar' | head -1)"
fi
[ -f "$JAR" ] || fail "no jar at $JAR"
echo "booting $JAR on :$PORT (TAPE_MODE=$TAPE_MODE$([ "$TAPE_MODE" = replay ] && echo ', no keys' || echo ", MODEL_API_KEY $([ -n "${MODEL_API_KEY:-}" ] && echo set || echo unset)"))"
java -jar "$JAR" > "$OUT/app.log" 2>&1 &
APP=$!
trap 'kill $APP 2>/dev/null || true; wait $APP 2>/dev/null || true; rm -f "$TMP"' EXIT
for _ in $(seq 1 120); do
  curl -fs "$BASE/actuator/health" 2>/dev/null | grep -q '"UP"' && break
  kill -0 $APP 2>/dev/null || fail "the app exited during boot"
  sleep 1
done
curl -fs "$BASE/actuator/health" | grep -q '"UP"' || fail "the app did not come up within two minutes"
echo "up"

# ---- helpers -------------------------------------------------------------------------------------------------
STATUS=0; BODY=""
TMP=$(mktemp)
req() { # req METHOD PATH [JSON-BODY]  → sets STATUS and BODY (no subshell, so both survive)
  local m=$1 p=$2 b=${3:-}
  if [ -n "$b" ]; then
    STATUS=$(curl -s -o "$TMP" -w '%{http_code}' -X "$m" -H "X-Admin-Token: $ADMIN_TOKEN" -H "Content-Type: application/json" --data "$b" "$BASE$p")
  else
    STATUS=$(curl -s -o "$TMP" -w '%{http_code}' -X "$m" -H "X-Admin-Token: $ADMIN_TOKEN" "$BASE$p")
  fi
  BODY=$(cat "$TMP")
}
must_json() { jq -e . >/dev/null 2>&1 <<<"$1" || fail "$2: not JSON (HTTP $STATUS): ${1:0:300}"; }
expect() { [ "$STATUS" = "$1" ] || fail "$2: HTTP $STATUS, expected $1: ${3:0:300}"; }

# ---- the pages a judge opens ---------------------------------------------------------------------------------
for page in sim.html travel.html cards.html; do
  code=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/$page")
  [ "$code" = 200 ] || fail "/$page is HTTP $code"
done
code=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/ops/status")
[ "$code" = 403 ] || fail "/ops/status without the token is HTTP $code, expected 403"

# ---- status: replaying, keyless ------------------------------------------------------------------------------
req GET /ops/status; body=$BODY; expect 200 "status" "$body"; must_json "$body" "status"
echo "$body" > "$OUT/status.json"
grep -q "\"tape\" *: *\"$TAPE_MODE" <<<"$body" || fail "status does not say the tape is in $TAPE_MODE mode: $body"
if [ "$TAPE_MODE" = replay ]; then
  grep -q 'no MODEL_API_KEY' <<<"$body" || fail "status says a key is set — the judge path must run without one: $body"
fi
SAMPLES=$(jq -r '.samples[]' <<<"$body")
echo "status: $(jq -c 'del(.samples)' <<<"$body")"
echo "samples: $(tr '\n' ' ' <<<"$SAMPLES")"

# ---- 1 · the demo journey -------------------------------------------------------------------------------------
req POST /ops/demo '{"child":"10000000000","days_ahead":2}'; body=$BODY; expect 200 "demo" "$body"; must_json "$body" "demo"
JOURNEY=$(jq -r .journey_id <<<"$body"); [ "${#JOURNEY}" -ge 8 ] || fail "demo returned no journey_id: $body"
echo "1 · journey $JOURNEY"

# ---- 2 · her consent -------------------------------------------------------------------------------------------
req POST /ops/journeys/latest/consent; body=$BODY; expect 200 "consent" "$body"; must_json "$body" "consent"
echo "2 · consent"

# ---- her samples: voice notes and sign photos through the real path (pressed while she looks for her gate) -----
SAMPLE_OK=0; SAMPLE_FAIL=0; SAMPLE_NOTES=""
run_samples() {
  local s body
  for s in $SAMPLES; do
    req POST "/ops/journeys/latest/sample/$s"; body=$BODY; must_json "$body" "sample $s"
    if [ "$STATUS" = 200 ]; then
      SAMPLE_OK=$((SAMPLE_OK + 1))
      SAMPLE_NOTES="$SAMPLE_NOTES
| \`$s\` | ok | $(jq -r '[.verdict // .intent // .outcome // "", .english // .telugu // ""] | join(" · ")' <<<"$body" | cut -c1-120) |"
      echo "   🎤📷 $s → $(jq -c 'del(.sample)' <<<"$body" | cut -c1-160)"
    else
      [ "$STATUS" = 502 ] || [ "$STATUS" = 503 ] || fail "sample $s: HTTP $STATUS: $body"
      jq -e '.error' >/dev/null <<<"$body" || fail "sample $s failed without a plain error: $body"
      SAMPLE_FAIL=$((SAMPLE_FAIL + 1))
      SAMPLE_NOTES="$SAMPLE_NOTES
| \`$s\` | **not on tape** | $(jq -r .error <<<"$body" | cut -c1-120) |"
      echo "   🎤📷 $s → not on tape ($(jq -r .error <<<"$body" | cut -c1-120))"
    fi
  done
}

# ---- 3 · every stage, the gate change and her samples where they belong -----------------------------------------
FIRED=0; GATE_CHANGED=0
for _ in $(seq 1 25); do
  req POST /ops/journeys/latest/next; body=$BODY; expect 200 "next" "$body"; must_json "$body" "next"
  stage=$(jq -r '.fired // empty' <<<"$body")
  [ "$stage" = "nothing planned" ] && break
  [ -n "$stage" ] || fail "next returned no stage: $body"
  FIRED=$((FIRED + 1))
  echo "3 · fired $stage"
  if [ "$stage" = "TRANSFER_FIND_GATE" ] && [ "$GATE_CHANGED" = 0 ]; then
    req POST /ops/events '{"type":"GATE_CHANGE","segment":2,"new_gate":"D14","source":"demo operations event"}'; body=$BODY
    expect 200 "gate change" "$body"; must_json "$body" "gate change"; GATE_CHANGED=1
    echo "   ⚡ gate change → D14 (simulated operations event, both phones)"
    run_samples
    req POST /ops/journeys/latest/ack; body=$BODY; expect 200 "ack" "$body"
  fi
done
[ "$FIRED" -ge 10 ] || fail "only $FIRED stages fired; the journey has 15"
[ "$GATE_CHANGED" = 1 ] || fail "TRANSFER_FIND_GATE never fired, so the gate change and the samples were never sent"
req POST /ops/journeys/latest/here; body=$BODY; expect 200 "here" "$body"
echo "   · $FIRED stages fired, 'I'm here' sent"
if [ "$STRICT" = 1 ] && [ "$SAMPLE_FAIL" -gt 0 ]; then fail "$SAMPLE_FAIL sample(s) not on the tape"; fi

# ---- 1b · Spark reads the sample ticket (on tape or plainly not) ----------------------------------------------
req POST /ops/demo-ticket '{"child":"10000000000","days_ahead":2}'; body=$BODY; must_json "$body" "demo-ticket"
if [ "$STATUS" = 200 ]; then TICKET="ok — $(jq -r '"\(.traveler), \(.segments) flights, re-dated +\(.redated_days) days"' <<<"$body")"
else [ "$STATUS" = 502 ] || fail "demo-ticket: HTTP $STATUS: $body"; TICKET="not on tape — $(jq -r .error <<<"$body" | cut -c1-120)"
  [ "$STRICT" = 1 ] && fail "the ticket extraction is not on the tape"; fi
echo "1b · ticket: $TICKET"

# ---- what both phones received -------------------------------------------------------------------------------
req GET '/ops/outbox?after=0'; body=$BODY; expect 200 "outbox" "$body"; must_json "$body" "outbox"
echo "$body" > "$OUT/outbox.json"
PARENT=$(jq '[.[] | select(.role=="parent")] | length' <<<"$body")
CHILD=$(jq '[.[] | select(.role=="child")] | length' <<<"$body")
VOICE=$(jq '[.[] | select(.kind=="voice")] | length' <<<"$body")
CARDS=$(jq '[.[] | select(.kind=="image")] | length' <<<"$body")
BUTTONS=$(jq '[.[] | select(.kind=="buttons")] | length' <<<"$body")
UNKNOWN=$(jq '[.[] | select(.role=="unknown")] | length' <<<"$body")
[ "$PARENT" -ge 10 ] || fail "only $PARENT messages reached her phone"
[ "$CHILD" -ge 3 ] || fail "only $CHILD messages reached the child's phone"
[ "$CARDS" -ge 5 ] || fail "only $CARDS cards were sent"
[ "$UNKNOWN" = 0 ] || fail "$UNKNOWN messages went to an unknown role"
jq -r '.[] | .text' <<<"$body" | grep -qiE 'exception|stacktrace|NullPointer' && fail "an outbound message contains an exception text"
jq -r '.[] | .text' <<<"$body" | grep -q '10000000000' && fail "an outbound message contains the phone number"
if [ "$TAPE_MODE" = replay ] && { ls "$TAPE_DIR"/tts/*.ogg >/dev/null 2>&1 || [ "$STRICT" = 1 ]; }; then
  [ "$VOICE" -ge 10 ] || fail "the tape has voice notes but only $VOICE were sent"
fi
shopt -s nullglob; wanted_files=("$TAPE_DIR"/tts/*.txt); shopt -u nullglob; WANTED=${#wanted_files[@]}
[ "$WANTED" = 0 ] || echo "tts: $WANTED note(s) left as tts/<key>.txt for prerender_voice.py"
echo "outbox: $PARENT to her, $CHILD to the child · $VOICE voice notes · $CARDS cards · $BUTTONS button prompts"

# ---- every model call, labelled -------------------------------------------------------------------------------
req GET '/ops/models?after=0'; body=$BODY; expect 200 "models" "$body"; must_json "$body" "models"
echo "$body" > "$OUT/models.json"
REPLAYED=$(jq '[.[] | select(.note | test("replayed from tape"))] | length' <<<"$body")
CALLS=$(jq 'length' <<<"$body")
echo "model calls: $CALLS logged, $REPLAYED replayed from tape"

# ---- a voice note is playable (when there is one) -----------------------------------------------------------
if [ "$VOICE" -gt 0 ]; then
  seq_=$(jq -r '[.[] | select(.kind=="voice")][0].seq' <<<"$(cat "$OUT/outbox.json")")
  curl -fs -H "X-Admin-Token: $ADMIN_TOKEN" -o "$OUT/first-voice.ogg" "$BASE/ops/outbox/$seq_/media" || fail "voice note $seq_ is not downloadable"
  head -c 4 "$OUT/first-voice.ogg" | grep -q OggS || fail "voice note $seq_ is not an Ogg file"
  if command -v ffmpeg >/dev/null; then
    code=$(curl -s -H "X-Admin-Token: $ADMIN_TOKEN" -o "$OUT/first-voice.mp3" -w '%{http_code}' "$BASE/ops/outbox/$seq_/media?format=mp3")
    [ "$code" = 200 ] || fail "format=mp3 is HTTP $code"
  fi
fi

# ---- app log hygiene ----------------------------------------------------------------------------------------
if grep -qE 'Exception|ERROR' "$OUT/app.log"; then
  echo "note: the app log has errors/exceptions (expected without a tape: missing recordings are logged as failed model calls)"
  grep -E 'ERROR|Exception' "$OUT/app.log" | grep -v 'no MODEL_API_KEY\|not on tape\|no voice\|SparkException\|TtsException' | head -20 || true
fi

# ---- a picture of it, while the app is still up (SCREENSHOT=<png>; needs playwright + chromium) ---------------
if [ -n "${SCREENSHOT:-}" ]; then
  mkdir -p "$(dirname "$SCREENSHOT")"
  python3 eval/judge-path/screenshot.py "$BASE" "$ADMIN_TOKEN" "$SCREENSHOT" || echo "screenshot failed (pip install playwright && python3 -m playwright install --with-deps chromium)"
fi

# ---- summary -------------------------------------------------------------------------------------------------
SUMMARY="### Judge path (clean checkout, \`TAPE_MODE=$TAPE_MODE\`$([ "$TAPE_MODE" = replay ] && echo ', no keys'))

| | |
|---|---|
| stages fired | $FIRED of 15, gate change sent at TRANSFER_FIND_GATE |
| to her phone / to the child | $PARENT / $CHILD messages |
| voice notes · cards · button prompts | $VOICE · $CARDS · $BUTTONS |
| samples answered from the tape | $SAMPLE_OK of $((SAMPLE_OK + SAMPLE_FAIL)) |
| sample ticket read (1b) | $TICKET |
| model calls logged / replayed | $CALLS / $REPLAYED |
| mode | TAPE_MODE=$TAPE_MODE · $([ "$STRICT" = 1 ] && echo strict || echo lenient)$([ "$WANTED" = 0 ] || echo " · $WANTED notes awaiting synthesis") |

| sample | | |
|---|---|---|$SAMPLE_NOTES

<details><summary>What reached her phone (first 40 lines)</summary>

\`\`\`
$(jq -r '.[] | select(.role=="parent") | "\(.seq) \(.kind) \(.text | split("\n")[0] | .[0:110])"' "$OUT/outbox.json" | head -40)
\`\`\`
</details>"
echo "$SUMMARY" > "$OUT/summary.md"
echo
echo "---- outbox (seq role kind bytes | text | buttons) ----"
jq -r '.[] | "\(.seq) \(.role) \(.kind) \(.bytes) | \(.text | gsub("\n"; " ⏎ ") | .[0:200]) | \(.buttons | join(" / "))"' "$OUT/outbox.json"
echo "---- model calls (model op ms ok note) ----"
jq -r '.[] | "\(.model) \(.op) \(.ms) \(.ok) \(.note)"' "$OUT/models.json" | head -60
if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then echo "$SUMMARY" >> "$GITHUB_STEP_SUMMARY"; fi
echo
echo "judge path OK — $FIRED stages, $VOICE voice notes, $SAMPLE_OK/$((SAMPLE_OK + SAMPLE_FAIL)) samples from tape, ticket: $TICKET"
