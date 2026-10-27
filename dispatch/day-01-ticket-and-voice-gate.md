# Day 1 — Mon Oct 27 — Ticket → itinerary; Telugu voice GO/NO-GO

Read `CLAUDE.md`. Two outcomes today, in this order. The voice gate decides the product's primary input; do it first if the voice notes are already collected.

## A. Telugu voice gate (morning)
- [x] `SttClient` for Muse Voice Transcribe: `POST /v1/asr/transcribe` multipart (`request` JSON + `audio` WAV); `AudioTranscoder` does OGG/Opus → 16 kHz mono WAV with ffmpeg; returns `{sessionId, transcript, audioDurationMs}` (the API has no confidence field); `languageBias: [Telugu, English]` + airport/airline `keywords`.
- [ ] Run the 10 real voice notes: `python3 eval/voice/transcribe_eval.py --dir ~/concourse-data/voice` (never committed) → `eval/voice/RESULTS.md` with transcript + Spark intent; a human marks each intent correct/wrong.
- [ ] GO if ≥ 8/10 intents correct. NO-GO → decision in `docs/DECISIONS.md`: primary input becomes tap-button cards + voice output; voice input stays as best-effort. (Do not spend the day tuning prompts to rescue a NO-GO.)

## A2. MMS-TTS on Modal (afternoon)
- [x] `modal/mms_tts.py`: `facebook/mms-tts-tel` (VitsModel, CPU) → WAV → ffmpeg OGG/Opus; `POST /tts {text, format, rate}`; `GET /health`. Java `TtsClient` caches by text hash. Deploy: `modal secret create concourse-tts TTS_TOKEN=...` then `modal deploy modal/mms_tts.py`.
- [ ] Intelligibility test: 5 stage sentences in Telugu, MMS vs Chirp, played to mom/dad → `eval/voice/TTS_RESULTS.md`. Pick one; record in DECISIONS.md.

## B. Ticket → itinerary
- [x] `SparkClient`: Chat Completions, SSE streaming with blocking fallback, `response_format: json_schema`, inline image (`image_url` data URL) and PDF (`file.file_data`), one retry on 429/5xx, 120 s timeout. Verified against dev.meta.ai docs.
- [x] `src/main/resources/prompts/ticket_extract.md` + `.schema.json` → `Itinerary`: passenger name (first name only stored), segments[{airline, flight_number, origin IATA, destination IATA, scheduled_departure (local, ISO with offset), scheduled_arrival, terminal?, gate?}], booking reference? (store hashed). Refuse non-tickets.
- [x] `ItineraryValidator` (+ `Airports` IATA→zone table, 5,867 airports): IATA codes exist; departure < arrival; connection ≥ 30 min; dates within 60 days; else mark fields `unverified` and ask the child to confirm with buttons.
- [x] `TicketIngestService` + `MessageRouter` (replaces the echo handler): child sends PDF/image on WhatsApp → download → Spark → validate → persist `journey` + `flight_segment` (status PRE_TRIP) → reply to child with the parsed itinerary and a CONFIRM / FIX button.
- [ ] Eval: 20 varied tickets/boarding passes (collect from family/friends, redacted) → `eval/tickets/RESULTS.md`; critical fields 100% on supported formats or the format is listed as unsupported.

## Acceptance
- Voice gate decision recorded. 
- Forwarding the real parent ticket PDF on WhatsApp returns a correct itinerary summary to the child within 30 s.

## Don't
- No TTS, no state machine today. No Hindi.

## Day 1 report (written 12:30 CT, updated at end of day)
- Built, not yet compiled on a real JDK+Maven (sandbox has no Maven Central): STT client, Spark client, audio transcoder, ticket → itinerary pipeline (extractor, validator, airports, summary), journey/contact/event persistence (V2 migration), message router, TTS client, Modal MMS-TTS function, voice-gate eval script, 8 test classes, CI workflow (tests + gitleaks), Gradle wrapper. Validator/airport/transcoder logic smoke-tested with plain javac + ffmpeg.
- Blocked: WhatsApp test account locked (131031) → appeal filed; Facebook route being retried by vv. Voice gate waits on mom's voice notes + MODEL_API_KEY run on vv's machine.
- Tomorrow's first task: `./gradlew test` green on vv's machine; run the voice gate; deploy Modal TTS and play 5 Telugu sentences to mom.
