# Day 3 — Wed Oct 29 — Deterministic journey engine + timed stages + consent

Read `CLAUDE.md` and `docs/ARCHITECTURE.md`. The software owns time and state; the model never does.

## Tasks
- [x] `JourneyStateMachine`: states and transitions exactly as in ARCHITECTURE.md; each state has instruction template (Telugu text), card template, required evidence, allowed tools. Pure, unit-tested (table-driven tests for every transition).
- [x] `StagePlanner`: from `flight_segment` times → `stage` rows with `scheduled_at` per the timeline in `docs/PLAN.md`; rebuild on verified flight update.
- [x] `StageScheduler`: polls `outbound_job` every 30 s; sends stage voice note + card; inside the 24-h window free-form; outside → `journey_update` template, and the voice note is sent when the parent replies. Idempotent; retries with backoff; never double-sends.
- [x] Consent/session start: child confirms itinerary → Concourse sends the parent an intro + "Please send a voice note saying 'start my trip' / నా ప్రయాణం మొదలు" → parent's first voice note sets `consent_at`, opens the window, moves PRE_TRIP → CHECK_IN at the right time.
- [x] Parent acks: tap button "Done ✓" or voice ("I am at the counter") → `acknowledge_stage` → next stage armed.
- [x] Cards (gate card PNG; English-first, Telugu footer): deterministic PNG/SVG generator (big gate code, big icon, 3 words of Telugu) — no generative images.
- [x] Admin endpoint `POST /ops/events` (ADMIN_TOKEN) → `apply_verified_flight_update` (gate/time) → stages rebuilt → both phones notified with source label. This is the demo's "gate change."
- [x] Time-travel mode (`ConcourseClock`: `next` command, `POST /ops/clock`, `CONCOURSE_CLOCK` env): for testing: `X-Concourse-Clock` header / env to simulate "now" so the full journey can be run in 10 minutes.

## Acceptance
- Full simulated journey HYD→DOH→DFW runs in time-travel mode: every stage fires once, in order, with voice + card; a gate change mid-journey rebuilds remaining stages and notifies both phones.
- State machine tests cover 100% of transitions; invalid transitions rejected.

## Don't
- No LLM calls in transition logic. No sign photos yet.

## Day 3 report (written Oct 28, late evening — a day early)
- Passed: `JourneyControllerTest.fullSimulatedJourney` runs HYD→DOH→DFW in time-travel mode: intro + consent request, nothing pushed before her "start my trip", CHECK_IN on consent, SECURITY on time, WAITING skipped when late and BOARDING sent with the gate card, gate change for flight 2 rebuilds the remaining plan and reaches both phones, TRANSFER_FIND_GATE speaks the new gate, FAMILY_HANDOFF → "I'm here" → COMPLETE; every stage sent once, in order; no job left behind. State-machine table test covers every (from, to, trigger) triple. 118 tests green on CI (`170a626`).
- Also: HELD-outside-window path tested (template, then voice on her reply); "I'm here" before EXIT is only an ack.
- Not done: real phone run of the stage flow (needs `git pull` + the 7-step sequence below); `journey_update` template still unsubmitted in WhatsApp Manager, so the outside-window path falls back to telling the child.
- Tomorrow's first task: run the demo loop on the phone, then Day 4 (sign photo → grounded reply).

## Demo loop on one phone (after `git pull`, restart)
1. Boarding pass photo → **Confirm**.
2. `mode parent` → the intro arrives with a button **నా ప్రయాణం మొదలు** → tap it (consent).
3. `mode child` → `next` → CHECK_IN voice + card/buttons arrive. Tap **అయ్యింది ✓** any time.
4. `next` → SECURITY … `next` → gate stage with the card. `gate C7` (or `gate 2 C7` for the second flight) → card + voice + your English line.
5. Keep `next` until FAMILY_HANDOFF → tap **నేను వచ్చాను** → "Journey complete" on your side.
`plan` shows the timeline and the simulated clock; `clock reset` returns to real time.
