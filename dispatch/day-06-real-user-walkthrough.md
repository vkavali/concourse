# Day 6 — Sat Nov 1 — A real older Telugu speaker, no coaching

Read `CLAUDE.md` and `docs/DEMO.md` (evidence hierarchy). The point of today is to find out what confuses a real user and fix it. This is the day that decides Impact and Demo.

## Human tasks
- [ ] A Telugu-speaking 60–75-year-old who did not build the product (parent on a real flight if one exists; otherwise a relative/friend at DFW Terminal D or any large terminal; worst case, a walkthrough of a mall/transit hub with printed gate signs).
- [ ] vv plays the child from another location. Wife/friend films (with consent) the parent's phone and face.
- [ ] No coaching: the only instruction is "send a voice note saying start my trip."

## Observe and log (in `eval/walkthrough/RESULTS.md`)
- For each stage: did she understand the voice note? Did she know what to do? Did she find the button? Time to act.
- Every voice note she sent: transcript, confidence, intent correctness.
- Every photo she sent: observation output, reply, was it helpful.
- Every moment she looked for a human instead.

## Claude Code tasks (after the walkthrough)
- [ ] Fix every confusion found: wording, speed, button labels, card size, repetition ("say it again" button).
- [ ] Add "మళ్ళీ చెప్పు / Say again" button to every stage message.
- [ ] Re-run safety suite and the time-travel journey.

## Acceptance
- Walkthrough completed; results logged; at least N/N instructions understood where N ≥ 6 stages.
- Clips saved for the video (consented).
