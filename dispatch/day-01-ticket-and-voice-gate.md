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

## Day 1 report (Oct 27, 15:50 CT)
**Passed**
- Full WhatsApp loop on a real phone: text → reply; boarding-pass photo (crumpled Air India AI173 DEL→SFO) → itinerary with the right two warnings (arrival not printed, date in the past) → Confirm button → confirmed; Telugu voice note → `🎙 నా gate ఎక్కడ ఉంది?` (code-switched, correct).
- CI green (48 tests, ffmpeg transcoder included). MMS-TTS Telugu deployed on Modal (`/health` ok, `uroman: false`); `say <telugu>` dev command returns a voice note.
- Meta Model API facts verified in code: `/v1/asr/transcribe` multipart must be hand-built (Spring's MultipartBodyBuilder → "Malformed multipart body"); chat completions with inline image + json_schema works on a phone photo.

**Failed / lessons**
- First Facebook account's test WABA locked (131031) within 10 h; second new account worked. Console tokens live 1 h → permanent System User token. Unpublished apps receive no webhooks → published with privacy policy URL. WABA must be subscribed to the app (`POST /{waba}/subscribed_apps`).

**Blocked / open**
- Voice gate (10 real elderly notes) and TTS intelligibility with mom: tonight, via WhatsApp forwards.
- Hosting: still ngrok on vv's laptop; Fly.io deploy is Day 2's first infra task so the number works while the laptop is closed.

**Tomorrow first**: parent registration (child sends the traveler's number) → parent voice note → Spark intent → Telugu voice reply < 10 s.
