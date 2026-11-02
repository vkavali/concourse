# Concourse — Architecture

## Principle

**Spark interprets the human. Code controls the journey.** The model reads tickets, hears voice notes, reads signs, and proposes tool calls. The JourneyController validates every proposal against the state machine and the safety contract, and is the only writer of journey state.

## Components (single Spring Boot service)

```
WhatsApp Cloud API ──webhook──► WebhookController
                                   │
                                   ▼
                            MessageRouter (child vs parent, message type)
                                   │
          ┌────────────────────────┼─────────────────────────┐
          ▼                        ▼                         ▼
   TicketIngestService      VoiceNoteService           SignEvidenceService
   (PDF/image → Spark →     (OGG→WAV ffmpeg →         (image → Spark →
    Itinerary schema →       Voice Transcribe →         SignObservation →
    validation)              intent/entity →            reconcile vs next
                             tool proposal)             verified step)
          │                        │                         │
          └────────────────────────┼─────────────────────────┘
                                   ▼
                           JourneyController
                 (identity · consent · state machine · scheduler ·
                  SafetyPolicy.check() on every outbound)
                                   │
                 ┌─────────────────┼──────────────────┐
                 ▼                 ▼                  ▼
            JourneyRepo        TtsService         WhatsAppClient
            (Postgres)        (Seamless / MMS-TTS
                               on Modal, Meta weights)(text / audio voice:true /
                               → OGG/Opus)         image / buttons / template)
                                   ▲
                          StageScheduler (DB-backed jobs, every 30 s)
                          OpsEventService (simulated gate change; AeroAPI optional)
```

## Data model (Flyway V1)

- `journey(id, parent_phone_hash, child_phone_hash, parent_language, consent_at, status, expires_at, created_at)`
- `flight_segment(id, journey_id, airline, flight_number, origin, destination, scheduled_departure, scheduled_arrival, terminal, gate, source, last_verified_at)`
- `stage(id, journey_id, type, airport, scheduled_at, status, instruction_version, sent_at, acknowledged_at)`
- `evidence(id, journey_id, stage_id, type[ticket|sign|flight_status|parent_ack], extracted_facts jsonb, confidence, received_at, expires_at)`
- `event(id, journey_id, actor[parent|child|system|ops], type, sanitized_payload jsonb, ts)`
- `outbound_job(id, journey_id, run_at, kind, payload jsonb, status, attempts)`

Phone numbers are stored hashed with a per-deployment salt; a separate `contact` table maps hash → E.164 for sending, encrypted at rest. No passport numbers anywhere. Ticket files are deleted after extraction.

## State machine

```
PRE_TRIP → CHECK_IN → SECURITY → WAITING_AT_ORIGIN → BOARDING_ORIGIN → IN_FLIGHT
→ TRANSFER_ARRIVAL → TRANSFER_FIND_GATE → TRANSFER_WAITING → TRANSFER_BOARDING → IN_FLIGHT
→ US_ARRIVAL → IMMIGRATION → BAGGAGE → EXIT → FAMILY_HANDOFF → COMPLETE
```
Each state: known facts, required evidence to advance (parent ack, time, or ops event), allowed tools, instruction template (Telugu text → TTS), card template.
Transitions happen only in `JourneyController.advance()`; triggers are the scheduler (time), parent acks (button/voice), or verified ops events.

## Tool surface exposed to Spark (strict JSON schemas)

- `get_journey_state()` — current stage, next verified step, last ack
- `get_next_stage()` — instruction for the next step
- `acknowledge_stage(stage_id)`
- `interpret_sign_evidence(observation)` — returns a grounded reply built from observation + verified next step
- `request_family_help(reason)` — messages the child
- `create_staff_card()` — returns the card payload
Ops only (never callable from parent input): `apply_verified_flight_update(segment_id, gate|time, source)`.

## Spark prompts (keep in `prompts/` as versioned files)

- `ticket_extract.md` — output Itinerary schema; refuse if not a ticket; never guess times.
- `parent_turn.md` — input: transcript + confidence + journey state; output: `{intent, entities, tool_proposal, reply_te}`; rules: no location claims, no routes, confirm when confidence < 0.7.
- `sign_observe.md` — output `{visible_text[], gate_codes[], explicit_arrow: left|right|up|down|null, confidence, location_claim: null}`; the field `location_claim` must always be null (test enforces it).

## Reply composition

Replies are composed from templates + verified facts, with Spark only wording the Telugu sentence inside guardrails: `TeluguPhraser` takes `{facts, observation}` and returns a sentence that `SafetyPolicy` re-checks (no gate numbers that aren't in the verified itinerary, no direction verbs unless an explicit arrow was observed).

## Audio pipeline

Inbound: media id → download → ffmpeg `-ar 16000 -ac 1` WAV → Voice Transcribe file endpoint (or realtime ws) → transcript + confidence.
Outbound: Telugu text → SeamlessM4T v2 or MMS-TTS (Meta open weights on Modal; no non-Meta voice is ever called) → ffmpeg → OGG_OPUS (mono, ~24 kbps, keep < 512 KB) → WhatsApp media upload → send `audio` with `voice: true`.

## Scheduling

`StageScheduler` polls `outbound_job` every 30 s; jobs are created when a journey is built (relative to scheduled flight times) and rebuilt on verified flight updates. Inside the 24-h window the job sends free-form; outside it sends the `journey_update` template and the voice note is sent on the parent's reply.

## Simulated operations events (demo)

`POST /ops/events` (auth: admin token) with `{journey_id, type: GATE_CHANGE, segment, new_gate, source: "demo operations event"}` → `apply_verified_flight_update` → both phones notified. The video shows the source label on screen.

## The tape and the judge path (`ai.concourse.ops`)

Every model client (`SparkClient`, `SttClient`, `SamClient`, `TtsClient`) goes through `ModelTape`: with `TAPE_MODE=record`
each live answer is saved under a content key (Spark: sha256 of the operation + the input's hash; STT: the WAV bytes; SAM:
concept + image; TTS: spoken text + rate) in `eval/sample-run/`; with `TAPE_MODE=replay` the same key is looked up first
and the answer is served with its original model id, logged as "replayed from tape". Nothing else changes: the same
controller, state machine, safety policy and outbox run either way, so a judge with no keys and no WhatsApp runs the
real code against recorded model answers. `ModelCallLog` keeps the last 200 model calls (model · operation · ms · note)
for `/ops/models` and the simulator's ticker. `OutboxChannel` is the `MessagingChannel` when WhatsApp is not configured:
every outbound message (text, buttons, voice note, card, link) is kept in a ring the simulator renders as two phones.
`eval/judge-path/smoke.sh` presses the simulator's buttons over HTTP in the README's order; CI runs it on every push
with no keys, and the `tape` workflow runs it in record mode and commits the result (Spark/SAM with a key secret; the
voice notes synthesized with MMS-TTS Telugu when no voice server recorded them).

## Travel Mode (web, served by the same service)

`travel.html#<journey>.<token>`: the current card, the voice note, hold-to-talk, and a camera view that sends a frame
every few seconds to the same sign reader (Spark) with SAM 3.1 outlining the board — a highlight of what the camera sees,
never a direction. Opened from a WhatsApp link button; works on any phone browser.

## Simulator (`/sim.html`)

Two-phone view over `/ops/outbox` plus the model-call ticker over `/ops/models`, sample 🎤 / 📷 buttons from
`eval/sample-run/inputs/`, the simulated operations event, and the journey clock. Labeled "SIMULATOR" in the header;
"replayed from tape" on every recorded answer. Never the product — the console that shows it.
