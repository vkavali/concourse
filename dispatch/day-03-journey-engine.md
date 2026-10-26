# Day 3 — Wed Oct 29 — Deterministic journey engine + timed stages + consent

Read `CLAUDE.md` and `docs/ARCHITECTURE.md`. The software owns time and state; the model never does.

## Tasks
- [ ] `JourneyStateMachine`: states and transitions exactly as in ARCHITECTURE.md; each state has instruction template (Telugu text), card template, required evidence, allowed tools. Pure, unit-tested (table-driven tests for every transition).
- [ ] `StagePlanner`: from `flight_segment` times → `stage` rows with `scheduled_at` per the timeline in `docs/PLAN.md`; rebuild on verified flight update.
- [ ] `StageScheduler`: polls `outbound_job` every 30 s; sends stage voice note + card; inside the 24-h window free-form; outside → `journey_update` template, and the voice note is sent when the parent replies. Idempotent; retries with backoff; never double-sends.
- [ ] Consent/session start: child confirms itinerary → Concourse sends the parent an intro + "Please send a voice note saying 'start my trip' / నా ప్రయాణం మొదలు" → parent's first voice note sets `consent_at`, opens the window, moves PRE_TRIP → CHECK_IN at the right time.
- [ ] Parent acks: tap button "Done ✓" or voice ("I am at the counter") → `acknowledge_stage` → next stage armed.
- [ ] Cards: deterministic PNG/SVG generator (big gate code, big icon, 3 words of Telugu) — no generative images.
- [ ] Admin endpoint `POST /ops/events` (ADMIN_TOKEN) → `apply_verified_flight_update` (gate/time) → stages rebuilt → both phones notified with source label. This is the demo's "gate change."
- [ ] Time-travel mode for testing: `X-Concourse-Clock` header / env to simulate "now" so the full journey can be run in 10 minutes.

## Acceptance
- Full simulated journey HYD→DOH→DFW runs in time-travel mode: every stage fires once, in order, with voice + card; a gate change mid-journey rebuilds remaining stages and notifies both phones.
- State machine tests cover 100% of transitions; invalid transitions rejected.

## Don't
- No LLM calls in transition logic. No sign photos yet.
