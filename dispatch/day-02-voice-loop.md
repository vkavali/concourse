# Day 2 — Tue Oct 28 — The voice loop, end to end, < 10 s

Read `CLAUDE.md`. Gate today: parent sends a Telugu voice note on real WhatsApp → gets a Telugu voice note back, in under 10 seconds, answering from journey facts.

## Tasks
- [x] `TtsClient` (providers: `mms` = Meta MMS-TTS on Modal, `google` = Chirp 3 HD te-IN): Google Chirp 3 HD `te-IN` → OGG_OPUS mono; cache by text hash; keep files < 512 KB (short sentences; split long ones).
- [x] `ParentTurnService` (tools proposed by Spark, executed by code): inbound audio → `SttClient` → `{transcript, confidence}` → `prompts/parent_turn.md` via Spark with tools (`get_journey_state`, `get_next_stage`, `acknowledge_stage`, `request_family_help`, `create_staff_card`) → tool proposal → `JourneyController.execute(proposal)` → reply text (Telugu) → `SafetyPolicy.check()` → TTS → WhatsApp audio `voice:true`.
- [x] Confidence gate: `< 0.7` → voice question + up to 3 tap buttons ("Gate", "Counter", "Help") instead of acting.
- [x] `TeluguPhrases` + `SafetyPolicy`: gate answers are a fixed template from verified facts; other replies are Spark-worded and safety-checked. Spark words the reply from `{facts, observation}` only; unit tests assert no gate codes outside the itinerary appear.
- [x] Latency instrumentation per hop (download, ffmpeg, STT, Spark, TTS, upload, send); log a one-line timing summary per turn.
- [x] Minimal journey fixture (a real boarding pass ingested on the phone; `gate B12` simulates the operations event) (the real route) so replies have facts before the state machine exists.
- [x] Child side: plain-English text replies for anything the child sends; child can ask "what is her last confirmed stage?" → stage + last ack time + "I do not know her physical location."

## Acceptance
- 10 consecutive real voice-note turns from vv's phone (and at least 3 from mom's phone) each answered by a Telugu voice note in < 10 s; p95 logged.
- Confidence gate demonstrably fires on a mumbled note.
- `./gradlew test` includes TeluguPhraser guardrail tests.

## Don't
- No scheduled pushes yet. No sign photos yet.

## Day 2 report (written Oct 28, evening)
- Passed: ticket → itinerary → Confirm; `mode parent` / `mode child` on one phone; Telugu voice note → Voice Transcribe → Spark intent → safety check → Telugu voice note + text + gate card; clarification buttons on low confidence; `gate B12` → card + voice to the parent, English text to the child; staff card; "tell my son". 85 tests green on CI (`82397ff`).
- Changed today: TTS provider = Google Chirp 3 HD te-IN (MMS voice judged too harsh/fast for an elderly listener; MMS stays as the `mms` provider). Voice says the airline name instead of spelling the flight code; gate codes get their own beat and are repeated.
- Failed / unmeasured: the < 10 s acceptance (10 real turns, p95) has not been measured — no `parent turn N ms` line reported yet. The Oct 27 voice gate with mom's notes (≥ 8/10 intents) has not been run.
- Blocked on: a phone test of `82397ff` (voice note after `mode parent`), the timing line from the console, and ~10 voice notes from mom/dad.
- Tomorrow's first task: Day 3 journey engine (state machine, stage planner, scheduler, consent) — started Oct 28 evening without waiting for the phone test.
