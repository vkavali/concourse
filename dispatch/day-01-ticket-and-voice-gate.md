# Day 1 — Mon Oct 27 — Ticket → itinerary; Telugu voice GO/NO-GO

Read `CLAUDE.md`. Two outcomes today, in this order. The voice gate decides the product's primary input; do it first if the voice notes are already collected.

## A. Telugu voice gate (morning)
- [ ] `SttClient` for Muse Voice Transcribe: file endpoint (verify exact path/params on dev.meta.ai docs); ffmpeg transcode OGG/Opus → 16 kHz mono WAV; return `{transcript, language, confidence}`; keyword biasing with airport/airline vocabulary if supported.
- [ ] Run the 10 real voice notes (from `~/concourse-data/voice/`, never committed). Write `eval/voice/RESULTS.md`: transcript, human judgment of intent (gate / counter / help / baggage / son / other) and critical entity, pass/fail.
- [ ] GO if ≥ 8/10 intents correct. NO-GO → decision in `docs/DECISIONS.md`: primary input becomes tap-button cards + voice output; voice input stays as best-effort. (Do not spend the day tuning prompts to rescue a NO-GO.)

## A2. MMS-TTS on Modal (afternoon)
- [ ] Modal function serving `facebook/mms-tts-tel` (transformers VitsModel) → WAV; ffmpeg → OGG/Opus mono. Endpoint `POST /tts {text} → audio`. Cache by text hash.
- [ ] Intelligibility test: 5 stage sentences in Telugu, MMS vs Chirp, played to mom/dad → `eval/voice/TTS_RESULTS.md`. Pick one; record in DECISIONS.md.

## B. Ticket → itinerary
- [ ] `SparkClient` (OpenAI-compatible, streaming, structured outputs; standard tier model id `muse-spark-1.3`; verify base URL and image/PDF input format in docs). Retry/timeout policy.
- [ ] `prompts/ticket_extract.md` → `Itinerary` schema: passenger name (first name only stored), segments[{airline, flight_number, origin IATA, destination IATA, scheduled_departure (local, ISO with offset), scheduled_arrival, terminal?, gate?}], booking reference? (store hashed). Refuse non-tickets.
- [ ] `ItineraryValidator`: IATA codes exist; departure < arrival; connection ≥ 30 min; dates within 60 days; else mark fields `unverified` and ask the child to confirm with buttons.
- [ ] `TicketIngestService`: child sends PDF/image on WhatsApp → download → Spark → validate → persist `journey` + `flight_segment` (status PRE_TRIP) → reply to child with the parsed itinerary and a CONFIRM / FIX button.
- [ ] Eval: 20 varied tickets/boarding passes (collect from family/friends, redacted) → `eval/tickets/RESULTS.md`; critical fields 100% on supported formats or the format is listed as unsupported.

## Acceptance
- Voice gate decision recorded. 
- Forwarding the real parent ticket PDF on WhatsApp returns a correct itinerary summary to the child within 30 s.

## Don't
- No TTS, no state machine today. No Hindi.
