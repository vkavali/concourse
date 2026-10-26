# Concourse — CLAUDE.md (project brain)

Read this first in every session. Then read the dispatch file for today in `dispatch/`. Do not re-plan; execute the dispatch, report against its acceptance criteria.

## What we are building (one paragraph)

A WhatsApp agent that escorts a parent who cannot use apps or read English through an international flight journey. The adult child forwards the ticket; Concourse builds the journey; at each stage it sends the parent a Telugu voice note and a simple card; the parent replies by voice note or by photographing a sign; Concourse answers only from verified journey facts and what it can actually see; gate changes reach both phones; one tap shows a card to airport staff; one tap tells the child "I'm here." Entry for the Meta Global AI Developer Hackathon, indie track, Build for Your World. Submission due **Nov 4, 2026, 8:00 PM CT** (9:00 PM ET).

## The sentence we are judged by

"Her son configures the software because she can't. Spark interprets the human; code controls the journey; Concourse never invents a direction."

## Scope (final until Nov 2 — do not add)

BUILD: ticket→itinerary · deterministic journey state machine with timed pushes · Telugu voice in/out · sign photo → grounded reply · simulated gate-change to both phones · staff card · "Call my son" / "I'm here" · parent consent via "start my trip" voice note · safety contract in code + adversarial tests.
OPTIONAL (Nov 1+, only if everything above is green): Android Travel Mode (heads-up notification → full-screen stage card), demo simulator page.
CUT: Hindi, immigration rehearsal, boarding-pass verification, Muse connector, Muse Image pictograms, AR/indoor navigation, live multi-airline gate data, wheelchair booking, phone calls.

## Stack

- Backend: Java 21, Spring Boot 3, PostgreSQL, Flyway, Spring Scheduler (DB-backed jobs table). Single deployable. Hosted on a public HTTPS URL (Fly.io or Railway) — WhatsApp webhooks require it.
- Clients: WhatsApp Business Cloud API; Meta Model API (Muse Spark 1.3, Muse Voice Transcribe); text-to-speech = Meta's open-source **MMS-TTS Telugu** (`facebook/mms-tts-tel`) served on Modal (Meta has no TTS API); Google Chirp 3 HD `te-IN` only as fallback if the MMS voice fails the Day-1 intelligibility test with a real parent; ffmpeg for audio transcoding; FlightAware AeroAPI optional (demo uses a labeled simulated event).
- Optional: Next.js simulator page (two-phone view of the event log, labeled "simulator"); Kotlin Android Travel Mode.
- Repo is PUBLIC. No secrets, no tickets, no voice notes, no phone numbers in git. `.env.example` only.

## Meta Model API (verify exact values against dev.meta.ai docs on Day 0; these are from research notes)

- Base URL `https://api.meta.ai/v1`; OpenAI-compatible Chat Completions and Responses; auth header `Authorization: Bearer $MODEL_API_KEY`.
- Spark: model `muse-spark-1.3` (use the standard tier, NOT `-contributor`: real tickets must not be used for training). Image/PDF input; strict JSON structured outputs; tool calling with `tool_choice: auto`. Stream responses (non-streaming calls can time out). ≤50 images per request.
- Voice Transcribe: model `muse-voice-transcribe-1.0`. File endpoint takes WAV (≤10 min, ≤32 MB) → transcode WhatsApp OGG/Opus to 16 kHz mono WAV with ffmpeg. Realtime websocket `wss://api.meta.ai/v1/asr/realtime` exists but is not needed for voice notes. Use keyword biasing for airline names, gate codes, airport codes if the API exposes it.
- Pricing (for sanity): Spark $1.25/M in, $4.25/M out; Voice Transcribe $3 per 1,000 min. A whole journey costs under $1.

## WhatsApp Cloud API rules that shape the code

- Inbound: webhook POST with message objects; media arrives as a media id → GET media URL → download with the bearer token.
- Outbound voice note: upload OGG/Opus mono via `/{PHONE_NUMBER_ID}/media`, then send `audio` with `voice: true`. ≤16 MB; ≤512 KB shows a play icon inline.
- Interactive buttons: max 3 quick-reply buttons per message. Use them for confirmations (never ask the parent to type).
- 24-hour window: any parent message opens 24 h of free-form messaging. Outside the window only an approved template may be sent. Our template (submit Day 0): `journey_update` — "Your journey has an update. Reply to hear it in Telugu." Templates are the fallback, not the main path: the parent's "start my trip" voice note opens the window at the right moment.
- Test number reaches 5 verified recipients. Register a real number on Day 1 so judges can message it.
- Policy: Concourse is a task-bound travel-assistance bot with human handoff (the child). Never add open-ended chat.

## Architecture (see docs/ARCHITECTURE.md)

WhatsApp webhook → JourneyController (identity, consent, state machine, scheduler, safety policy) → tools → {Voice Transcribe, Spark, TTS, Journey DB} → WhatsApp send. Spark interprets inputs and proposes tool calls; the controller validates preconditions and owns state. The LLM never owns temporal truth, never changes a gate (only `apply_verified_flight_update` does), never asserts location.

## Safety contract (enforced in code, tested in `eval/safety/`)

MUST NOT: claim the traveler's location without location evidence · invent a walking route · invent a gate/terminal · overwrite itinerary facts from a sign photo · answer immigration questions on her behalf · tell her to ignore staff.
MAY: repeat verified itinerary facts · report text/arrows visibly in a photo · compare observed signage to the next verified step · ask for another photo · tell her to ask staff · contact the child.
Every outbound message passes `SafetyPolicy.check()`; a violation is replaced by the safe fallback ("Please show the card to a staff member; I have told your son.") and logged.

## Language

Telugu only for the parent (English for the child) until the Telugu flow passes its tests. Confidence-gated: if transcription confidence < threshold, reply with a voice question plus 2–3 tap buttons; never guess.

## Data and privacy

Store: itinerary facts, stage states, sanitized evidence summaries, event log. Do NOT store passport numbers. Delete ticket images from our storage after extraction; expire journeys 7 days after arrival. Parent consent = her own voice note ("start my trip"); record consent_at. Use the standard (non-contributor) Spark tier.

## Conventions

- Branch `main` only; small commits; message prefix by area: `wa:`, `journey:`, `spark:`, `stt:`, `tts:`, `safety:`, `eval:`, `docs:`.
- Every feature ships with a test or an eval entry. `./gradlew test` must pass before commit.
- `docs/DECISIONS.md` gets one line per decision ("Oct 28: cut Hindi — Telugu first").
- Daily report at the end of each dispatch: what passed, what failed, what's blocked, tomorrow's first task.

## Gates (hard)

- Oct 27: real elderly Telugu voice notes transcribed with usable intent extraction (≥8/10 intents correct) → GO on voice; else switch primary input to tap-button cards with voice output only.
- Oct 28: Telugu voice in → Telugu voice out on real WhatsApp in < 10 s.
- Oct 31: demo-complete; backup video recorded.
- Nov 1: a Telugu-speaking 60–75-year-old who did not build it completes the DFW walkthrough with no coaching.
- Nov 3: feature freeze; video final.
- Nov 4, 6:00 PM CT: submitted (2-hour buffer).
