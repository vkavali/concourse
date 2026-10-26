# Concourse — 10-day plan (Oct 26 → Nov 4, 2026, all times CT)

| Day | Date | Non-negotiable outcome | Dispatch |
|---|---|---|---|
| 0 | Sun Oct 26 (tonight) | Accounts, keys, repo, template submitted, employment clause checked, 10 real Telugu voice notes collected | `dispatch/day-00-setup.md` |
| 1 | Mon Oct 27 | Ticket PDF/photo → validated itinerary JSON; **Telugu voice GO/NO-GO** on real elderly audio | `dispatch/day-01-ticket-and-voice-gate.md` |
| 2 | Tue Oct 28 | End-to-end: WhatsApp voice note → STT → Spark tool → Telugu TTS → WhatsApp voice note, < 10 s | `dispatch/day-02-voice-loop.md` |
| 3 | Wed Oct 29 | Deterministic journey state machine + scheduler firing timed stages; consent flow | `dispatch/day-03-journey-engine.md` |
| 4 | Thu Oct 30 | Sign-photo evidence pipeline; safety contract in code; adversarial test suite passing | `dispatch/day-04-evidence-and-safety.md` |
| 5 | Fri Oct 31 | Child/parent dual state; simulated gate-change event; staff card; "Call my son"; "I'm here"; **backup video recorded** | `dispatch/day-05-family-loop-and-demo-complete.md` |
| 6 | Sat Nov 1 | Real older Telugu speaker walks DFW Terminal D with no coaching; fix every confusing interaction | `dispatch/day-06-real-user-walkthrough.md` |
| 7 | Sun Nov 2 | Full rehearsal on the real route; eval sets run; optional Travel Mode / simulator page only if green | `dispatch/day-07-rehearsal-and-optionals.md` |
| 8 | Mon Nov 3 | Feature freeze; final video; README; evaluation sheet; gallery page; X post drafted | `dispatch/day-08-freeze-and-video.md` |
| 9 | Tue Nov 4 | Submission by 6:00 PM CT; live-demo rehearsal; Discord office hours for last issues | `dispatch/day-09-submit.md` |

## MVP feature list (9) — see CLAUDE.md scope

1. Child forwards ticket → itinerary (Spark, strict schema, deterministic validation)
2. Journey state machine with timed stage pushes (voice note + card)
3. Parent Telugu voice note → Telugu voice note reply
4. Sign photo → grounded reply (reads sign, compares to next verified step, never claims location)
5. Gate change / delay push to both phones (clearly labeled simulated operations event for the demo)
6. Staff card ("SHOW STAFF")
7. "Call my son" and "I'm here" buttons
8. Consent + session start via parent's "start my trip" voice note
9. Safety contract in code + adversarial tests + evaluation sheet

## Stage timeline (relative to scheduled times; adjust per route)

- T−180 min (departure): CHECK_IN — "Show passport and ticket at the [airline] counter."
- T−120: SECURITY — "Go to security. Put bag and phone in the tray."
- T−60: WAITING_AT_ORIGIN — "Walk to gate [G]. Follow signs with letter [X]." (card with gate)
- T−40: BOARDING_ORIGIN — "Boarding soon. Keep passport and boarding pass in hand."
- Arrival+10 (transfer): TRANSFER_FIND_GATE — "Follow 'Transfer' signs. Your next gate is [G]."
- Next departure T−60 / T−40: TRANSFER_WAITING / TRANSFER_BOARDING
- Arrival+10 (US): US_ARRIVAL — "Follow 'Arrivals / Immigration' signs. Answer the officer yourself; keep your phone away unless told."
- Then BAGGAGE → EXIT → FAMILY_HANDOFF ("I'm here" button) → COMPLETE

## Evaluation sets (built as we go; results go in `eval/RESULTS.md`)

- Tickets: 20 varied booking confirmations/boarding passes → critical fields (flight no., airports, dates, times, terminal/gate if present) must be 100% on supported formats.
- Telugu voice: 30 utterances, mostly speakers 60+, scored on journey intent + critical entity (gate, flight, "where", "help", "son").
- Sign photos: 50–100 airport-sign photos (readable, blurry, cropped, irrelevant, contradictory) → extraction + grounded response correctness.
- Safety: 30 adversarial prompts → zero unsupported location/routing assertions.
- Comprehension: 3 older users receive "next step" voice + card with no explanation → did they know what to do?

## Demo video (3:00) — see docs/DEMO.md

## Submission checklist — see dispatch/day-09-submit.md
