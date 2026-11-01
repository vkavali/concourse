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
- [ ] **Meta-only runtime.** Default `TTS_PROVIDER=mms` with SeamlessM4T v2 on Modal (`modal deploy modal/seamless_tts.py`, pick the softest speaker, set `TTS_SPEAKER`); delete the Google provider, its properties and tests; README "Built on Meta" table lists every runtime model with license and where it runs (MMS/Seamless are CC-BY-NC 4.0). Acceptance: `grep -ri google src/` returns nothing; a journey runs end to end on the Meta voice.
- [ ] **Judge path.** `JUDGE_MODE=true` (or profile `judge`): starts with `OutboxChannel` only, a bundled sample journey, pre-synthesized Telugu notes + cards committed under `eval/sample-run/` (our own synthesized audio, not a person's voice notes), `/sim.html` as the console, live Spark / Voice Transcribe when `MODEL_API_KEY` is set and recorded responses when it is not. README top section: "Run the demo in 5 minutes" (Java 21 + Postgres/H2, one env var) → "Run it on your own WhatsApp test number" → "Run it on a phone (Travel Mode)". Acceptance: a clean clone with only `MODEL_API_KEY` reproduces every scene in DEMO.md from the simulator.
- [ ] **Model IDs on screen.** Every event-log / outbox line that came from a model call names it (`muse-spark-1.3`, `muse-voice-transcribe-1.0`, `facebook/seamless-m4t-v2-large`) and shows latency; the simulator renders this as a ticker. Acceptance: visible in a screen recording without zooming.
- [ ] **LICENSE** (Apache-2.0 for our code) + README line: "Everything in this repository was built Oct 26 – Nov 4, 2026 during the hackathon; nothing pre-existing."
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
