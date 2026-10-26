# Day 5 — Fri Oct 31 — Family loop, staff card, I'm here; DEMO-COMPLETE + backup video

Read `CLAUDE.md` and `docs/DEMO.md`. By tonight every scene in the demo script works and a backup video exists.

## Tasks
- [ ] Child notifications: stage sent/acked, gate change, help requested, "I'm here" — concise English texts on the child's thread.
- [ ] `SHOW STAFF` button (interactive) → full-screen card image (English + big text): I NEED HELP · flight → destination · GATE · I SPEAK TELUGU · PLEASE CALL MY SON +1… ; generated per journey, deterministic.
- [ ] `CALL MY SON` button → sends the child "Your mother asked you to call her now" + her number; parent gets a voice note "Your son has been told."
- [ ] `I'M HERE` button at FAMILY_HANDOFF → child notified; journey COMPLETE; optional WhatsApp location share instructions.
- [ ] Template path verified: with the window closed, `journey_update` template sends; parent reply triggers the pending voice note.
- [ ] Register a real phone number (prepaid SIM) on the WhatsApp Business account so judges can message Concourse; confirm unverified-tier limits; keep the test number for family.
- [ ] README: what it is, how to try it (number + sample ticket), what is simulated, what Meta models do, safety contract, evaluation results so far, what was built during the hackathon (everything).
- [ ] Run the whole demo script on two phones in time-travel mode; record the backup video (raw, unedited).

## Acceptance
- Every scene in DEMO.md executes on real WhatsApp.
- Backup video exists outside the repo.

## Don't
- No new features after today unless Day 6 reveals a confusion that blocks the user.
