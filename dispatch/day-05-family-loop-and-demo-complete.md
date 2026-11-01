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

## Re-plan — Sun Nov 1, after reading the hackathon Discord (see docs/HACKATHON_INTEL.md)

Three days left. The rubric is Impact 30 / Meta tooling 25 / Depth 20 / Demo 25; the final round is judges watching the
3-minute video live. Everything below is ordered by what loses the most points if missing.

### Must (Nov 1–2)
- [x] **Meta-only runtime** (code, `0a22b8b`): Google provider deleted; the only voice is the Modal-served Meta model (`TTS_BASE_URL`, `TTS_SPEAKER`); README table lists every runtime model with license and where it runs. **Open (vv):** `modal deploy modal/seamless_tts.py`, audition speakers, set `TTS_SPEAKER`, run a journey on it.
- [x] **Judge path** (code, `0a22b8b`): `TAPE_MODE=record|replay` — every Spark/Voice Transcribe answer recorded by input hash, her inputs saved as samples, synthesized notes copied; without WhatsApp credentials the outbox is the only phone; `/sim.html` plays the notes and cards, sends her samples, shows the status banner. README: run in 5 minutes → own WhatsApp number → on a phone. **Open (vv):** record the tape — `TAPE_MODE=record` + keys + Seamless, run the sample journey through every stage, send 3 voice notes (start my trip / where is my gate / I'm lost) and the 4 sample sign photos, rename the voice inputs, commit `eval/sample-run/`. Then verify from a clean clone with `TAPE_MODE=replay` and no keys.
- [x] **Model IDs on screen** (`0a22b8b`): `ModelCallLog` + `/ops/models` + the simulator's "Meta model calls" ticker (model id · operation · ms · replayed/live).
- [x] **LICENSE** (Apache-2.0) + NOTICE + README provenance line.
- [ ] **Real-phone run** (deferred from Oct 30): PUBLIC_BASE_URL = ngrok HTTPS, two phones, full journey in time-travel mode, Travel Mode camera on a printed "GATES B1–B20 ←" sign, hold-to-talk. Fix what breaks. This is the raw footage source.

### Should (Nov 2)
- [ ] Family walkthrough with a Telugu speaker 60–75 who did not build it (the Nov 1 gate, now Nov 2): film it (with consent, face optional) — the Impact proof.
- [ ] Latency table in `eval/RESULTS.md`: voice note in → voice note out (p50/p95 over 10 turns), photo → verdict, camera frame → verdict. Teams in this hackathon publish numbers; ours must exist.
- [ ] Decide Railway vs local+ngrok for the demo after one test call from Railway (several teams report `404 model_not_found` from datacenter IPs).

### Could (Nov 2 evening, only if everything above is green)
- [ ] Muse Spark Open (Baseten, `meta-models/muse-spark-open-1026`) as the intent classifier behind a flag → eligible for "Best Build on Meta Open Weight Models" ($10k). Fall back to Spark 1.3 on any JSON failure. Skip if Baseten truncation (`finish_reason=length`) shows up in the first hour.

### Nov 3 — freeze + video
- Script (≤3:00): 0:00 one line "built entirely during the hackathon" + the judged sentence · 0:10 son forwards ticket → journey built · 0:30 parent gets Telugu voice note + card (real phone, English subtitles) · 0:55 she replies by voice; "where is my gate?" → templated answer + gate card · 1:20 she photographs a sign → MATCH/MISMATCH verdict · 1:45 Travel Mode camera reads a board live on the phone · 2:10 gate change hits both phones · 2:30 "I'm here" → son's phone · 2:40 simulator ticker with model IDs + safety suite green · 2:55 end card. Human presenter (vv) on voice-over.
- Post the video on X, link it in the submission (Most Viral / Community Vote).

### Nov 4 — submit by 6:00 PM CT
- Form: team name, public repo, video link, X post, description (future plans go here, not in the video), every teammate.
