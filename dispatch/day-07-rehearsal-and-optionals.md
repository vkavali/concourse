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

## Re-plan — Sun Nov 2 (actual). One goal: the tape exists and a real elder has used it.

Code is ahead of the evidence. Everything below that says **vv** needs the laptop, the keys and the phones; everything
that says **CC** happens in the repo as soon as the matching **vv** step lands. Order matters — each step feeds the next.

### vv — in this order (≈ 4 h wall clock)
1. **Voice (30 min).** `modal deploy modal/seamless_tts.py` → `TTS_BASE_URL=<printed URL>` (+ `TTS_TOKEN` if you set
   one). Audition without the app: `for n in 0 5 12 20 33 47 61 88 104 150; do modal run modal/seamless_tts.py
   --speaker $n --out s$n.ogg; done` → play them, pick the softest, set `TTS_SPEAKER=N`. If Modal fights you for more
   than 30 min: `modal deploy modal/mms_tts.py` instead (one voice, no speaker choice) and move on.
2. **Record the tape (45 min).** `.env`: `TAPE_MODE=record`, `MODEL_API_KEY`, `TTS_BASE_URL`, `TTS_SPEAKER`, WhatsApp
   off is fine (the outbox is the phone). `./gradlew bootRun`, open `/sim.html`, then: **1b** (Spark reads the ticket) →
   **2** → **3** through every stage (ack each gate card) → gate change at TRANSFER_FIND_GATE → the four 📷 samples →
   record three voice notes on your phone, drop the `.ogg`/`.m4a` into `eval/sample-run/inputs/` as
   `voice-start-my-trip.wav`, `voice-where-is-my-gate.wav`, `voice-i-am-lost.wav` (ffmpeg -i in.ogg -ac 1 -ar 16000
   out.wav) and press their 🎤 buttons → **I'm here**. Check `ls eval/sample-run/{spark,stt,sam,tts}` all non-empty.
   Commit: `git add eval/sample-run && git commit -m "eval: the tape — recorded run for the judge path" && git push`.
   **CC then:** flips CI to `STRICT=1`, verifies replay from a clean clone with no keys, fills the latency table.
3. **Two phones over ngrok (45 min).** `TAPE_MODE=off`, `WHATSAPP_*` set, `PUBLIC_BASE_URL=https://<ngrok>`. Press **1**
   with both numbers. Run the journey once end to end on the phones; Travel Mode camera on a printed sign; hold-to-talk.
   Screen-record both phones — this is the video's raw footage. If the WABA is locked again: the simulator in "Hide
   controls (H)" mode is the phone in the video; say so in one on-screen line ("simulator — same code, no WhatsApp").
4. **The elder (60 min, filmed, consented).** `docs/WALKTHROUGH.md`, `TAPE_MODE=record` so her session is also samples.
   Log into `eval/walkthrough/RESULTS.md` as you go: stage · understood? · acted without help? · seconds. **CC then:**
   fills the numbers in `docs/SUBMISSION.md` and `cards.html`.
5. **Only if 1–4 are done:** Baseten key → `SPARK_OPEN_BASE_URL`, `SPARK_OPEN_API_KEY`; one journey; keep it if the
   ticker shows it answering (Best Build on Meta Open Weight Models). One Railway test call of Spark; if 404
   `model_not_found`, the demo stays on ngrok and the README already says so.

### CC — today, without the laptop
- [x] `/ops/demo-ticket` (**1b**) so the story opens with Spark reading the ticket; mp3 fallback for Safari; tests.
- [x] `eval/judge-path/smoke.sh` + CI job: the README path runs on every commit with no keys; the outbox and model
  log are attached to the run, so what the judge sees is reviewed on every push.
- [x] Reviewed the actual outbox from the CI job log: child's column was empty (one placeholder number) → two numbers; the
  travel link went out twice → once; "Hide controls (H)" layout was broken → fixed; TTS replays named the wrong model → sidecars.
- [x] The tape recorded from CI (`tape` workflow): 22 voice notes by MMS-TTS Telugu; README screenshot from the replay.
- [x] `MODEL_API_KEY` secret added (vv, 12:25 CT) → `tape` re-run: Spark + SAM answers for the 4 signs and the ticket, Voice
  Transcribe + intent for 3 voice notes (MMS-TTS stand-ins until a person records them) — **103/103 model calls replay with
  no key.** Found and fixed on the way: "where is my gate" at Doha answered about the first leg; the demo clock broke 1b.
- [x] CI judge path is `STRICT=1`: every sample and every stage must answer from the tape, every commit.
- [ ] `eval/latency/RESULTS.md` from vv's live run (`eval/latency/summarize.py`); note: Spark intent calls from the CI runner took 13–16 s.
- [ ] Nov 3: video script timed against the footage; freeze.

### Decision rules (so nothing waits on a chat)
- Modal > 30 min of trouble → MMS-TTS. No voice by 6 PM CT → the video uses cached notes from the simulator; never a
  non-Meta voice.
- WABA locked → simulator is the phone; the README's "own WhatsApp number" section stays (judges may have one).
- Elder unavailable today → tomorrow morning, before the video; the video can be cut around that scene last.
