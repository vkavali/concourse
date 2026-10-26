# Day 2 — Tue Oct 28 — The voice loop, end to end, < 10 s

Read `CLAUDE.md`. Gate today: parent sends a Telugu voice note on real WhatsApp → gets a Telugu voice note back, in under 10 seconds, answering from journey facts.

## Tasks
- [ ] `TtsService`: Google Chirp 3 HD `te-IN` → OGG_OPUS mono; cache by text hash; keep files < 512 KB (short sentences; split long ones).
- [ ] `VoiceNoteService`: inbound audio → `SttClient` → `{transcript, confidence}` → `prompts/parent_turn.md` via Spark with tools (`get_journey_state`, `get_next_stage`, `acknowledge_stage`, `request_family_help`, `create_staff_card`) → tool proposal → `JourneyController.execute(proposal)` → reply text (Telugu) → `SafetyPolicy.check()` → TTS → WhatsApp audio `voice:true`.
- [ ] Confidence gate: `< 0.7` → voice question + up to 3 tap buttons ("Gate", "Counter", "Help") instead of acting.
- [ ] `TeluguPhraser`: Spark words the reply from `{facts, observation}` only; unit tests assert no gate codes outside the itinerary appear.
- [ ] Latency instrumentation per hop (download, ffmpeg, STT, Spark, TTS, upload, send); log a one-line timing summary per turn.
- [ ] Minimal journey fixture (the real route) so replies have facts before the state machine exists.
- [ ] Child side: plain-English text replies for anything the child sends; child can ask "what is her last confirmed stage?" → stage + last ack time + "I do not know her physical location."

## Acceptance
- 10 consecutive real voice-note turns from vv's phone (and at least 3 from mom's phone) each answered by a Telugu voice note in < 10 s; p95 logged.
- Confidence gate demonstrably fires on a mumbled note.
- `./gradlew test` includes TeluguPhraser guardrail tests.

## Don't
- No scheduled pushes yet. No sign photos yet.
