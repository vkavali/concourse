# Day 7 — Sun Nov 2 — Full rehearsal; eval sets; optionals only if green

Read `CLAUDE.md`. Go/no-go on optionals happens at 12:00 CT: only if Days 2–6 acceptance criteria are all green.

## Tasks (core)
- [ ] Full rehearsal of the demo on the real route with the real parent/friend phones; time every hop; fix latency outliers (pre-generate stage TTS; warm clients).
- [ ] Run all eval sets; write `eval/RESULTS.md` with the numbers that go in the video's last frame: tickets N/N, voice intents N/N, signs N/N, safety 0 violations, walkthrough N/N understood.
- [ ] Load test: 20 concurrent journeys in time-travel mode; no double-sends; scheduler stable.
- [ ] Privacy pass: ticket files deleted after extraction; passport numbers never stored (grep tests); journey expiry job; consent recorded.

## Optionals (12:00 CT go/no-go; pick at most one)
- [ ] Android Travel Mode (Kotlin): FCM → heads-up notification → full-screen stage card with autoplay audio → SHOW STAFF / CALL MY SON; sideload on the demo phone; preload next 3 stages. One day max.
- [ ] Simulator page (Next.js): two-phone view over SSE, labeled SIMULATOR; for judges who won't enroll a phone.

## Acceptance
- Rehearsal video raw footage captured; eval numbers final.
- Optionals either green or deleted from the submission narrative (no half-features).

## Don't
- Don't start Hindi, immigration, boarding-pass, connector, or AR. Ever, this week.
