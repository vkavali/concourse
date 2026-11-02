# Concourse

[![CI](https://github.com/vkavali/concourse/actions/workflows/ci.yml/badge.svg)](https://github.com/vkavali/concourse/actions/workflows/ci.yml)

A WhatsApp agent that escorts a parent who cannot use apps or read English through an international flight — by voice, in her language, with her family in the loop.

Entry for the Meta Global AI Developer Hackathon (indie track, Build for Your World). **Everything in this repository was built between Oct 26 and Nov 4, 2026, during the hackathon; nothing pre-existed.** Code: Apache-2.0 (`LICENSE`).

See `CLAUDE.md` (project brain), `docs/ARCHITECTURE.md`, `docs/SAFETY.md`, `docs/DEMO.md`, `docs/HACKATHON_INTEL.md`, and the daily `dispatch/` files.

## Run the demo in five minutes (no accounts needed)

Needs Java 21 and `ffmpeg` on PATH. Nothing else: the database is a local H2 file, WhatsApp is off, and the model calls
replay from `eval/sample-run/` — a run recorded with real keys, kept on a "tape" (`TAPE_MODE=replay`).

```bash
cp .env.example .env
# in .env: ADMIN_TOKEN=judge   TAPE_MODE=replay
./gradlew bootRun
```

Open <http://localhost:8080/sim.html>, paste `judge` as the admin token, and press the buttons in order: **1** builds the
sample HYD → DOH → DFW journey and sends her the intro (**1b** has Spark read the sample ticket PDF in `eval/tickets/`
first — the itinerary extraction, re-dated to two days out; on the tape it replays); **2** is her "start my trip"; **3** fires the next stage (the
journey clock jumps to it) — the Telugu voice note, the card and the buttons appear on *Traveler's phone*, the English
update on *Child's phone*; the 🎤 / 📷 buttons send her recorded voice notes and sign photos through the real path
(transcription → intent → templated answer; photo → sign reader → verdict); the gate-change button is the simulated
operations event, and *Meta model calls* on the right lists every model call as it happens — **replayed from tape**
when no key is set, live otherwise. "Her travel screen" opens the same page she gets on WhatsApp.

With `MODEL_API_KEY` set (dev.meta.ai) the Spark and Voice Transcribe calls go live and the tape fills the gaps; with
`TTS_BASE_URL` set (below) the voice is synthesized live. `TAPE_MODE=off` is the normal mode.

## Run it on your own WhatsApp test number

1. developers.facebook.com → your app → WhatsApp → API setup: copy the temporary token and the phone number id into
   `.env` (`WHATSAPP_TOKEN`, `WHATSAPP_PHONE_NUMBER_ID`), add the two phones as test recipients.
2. Expose the app over HTTPS (`ngrok http 8080`; or Railway, below) and set `PUBLIC_BASE_URL` to that host. Register
   `https://<host>/webhook/whatsapp` as the webhook with `WHATSAPP_VERIFY_TOKEN`; subscribe to `messages`.
3. Deploy the voice: `modal deploy modal/seamless_tts.py` (SeamlessM4T v2, 200 speakers — `TTS_SPEAKER` picks one) or
   `modal deploy modal/mms_tts.py` (MMS-TTS Telugu); put the printed URL in `TTS_BASE_URL`.
4. `./gradlew bootRun`, then on the child's phone send the boarding pass (or press **1** in the simulator with both numbers
   filled in). The child's phone commands: `next`, `gate C7`, `plan`, `status`, `clock reset`, `mode parent` / `mode child`.

To record your own tape: `TAPE_MODE=record`, run the journey, send voice notes and photos; everything lands under
`eval/sample-run/` (model outputs by input hash, the inputs themselves, the synthesized notes). Rename the inputs you
want as samples (`eval/sample-run/inputs/*.wav|jpg`) and commit — only your own voice and photos, never a parent's.

## Run it on a phone (Travel Mode)

After the intro and again after consent she gets a WhatsApp button **తెరవండి** that opens `travel.html#<journey>.<token>`
in her phone browser: the current card, its voice, a live camera that reads the boards she points at (the same reader and
safety check as a WhatsApp photo; SAM 3.1 outlines the board on screen), hold-to-talk, and the four buttons. The camera and microphone need HTTPS
(`PUBLIC_BASE_URL`); on a laptop the page falls back to a file picker for the camera.

## Built on Meta

Every model Concourse calls at runtime is a Meta model; the channel is WhatsApp. The simulator's ticker and the logs name
the model id next to every call.

| Need | Meta model / platform | How | Runs |
|---|---|---|---|
| Reach a parent who only uses WhatsApp | **WhatsApp Business Cloud API** | webhooks in, voice notes (`voice: true`), images, 3-button replies, link buttons (`cta_url`), templates outside the 24-h window | Meta Graph API v25.0 |
| Read the ticket (PDF or a crumpled boarding-pass photo) | **Muse Spark 1.3** (`muse-spark-1.3`, standard tier) | `/v1/chat/completions`, inline PDF/image, constrained JSON (`response_format: json_schema`) | Meta Model API |
| Understand her Telugu voice note | **Muse Voice Transcribe 1.0** (`muse-voice-transcribe-1.0`) | `/v1/asr/transcribe`, WAV 16 kHz mono, `languageBias: ["Telugu","English"]`, airport `keywords` | Meta Model API |
| Classify what she means (gate? help? son? start my trip?) | **Muse Spark Open** (`meta-models/muse-spark-open-1026`, Meta open weights) first when `SPARK_OPEN_BASE_URL` is set, **Muse Spark 1.3** otherwise and whenever the open model fails | intent + confidence + tool proposal; code decides and answers from verified facts | Baseten partner inference / Meta Model API |
| Read the board she photographs, or points the camera at | **Muse Spark 1.3** (vision) | `visible_text`, `gate_codes`, one `explicit_arrow`; `location_claim` is typed null | Meta Model API |
| Outline the board in her camera | **SAM 3.1** (`sam-3.1`) | asked for "sign" on each frame, in parallel with Spark; the travel screen draws the box with the matched word — a highlight, never a direction | Meta Model API |
| Speak Telugu | **SeamlessM4T v2** (`facebook/seamless-m4t-v2-large`, CC-BY-NC 4.0) or **MMS-TTS Telugu** (`facebook/mms-tts-tel`, CC-BY-NC 4.0) — Meta open weights | text → speech phrase by phrase, pauses engineered in code, Opus voice notes | our Modal deployment (`modal/`) |

What is deliberately **not** a model: the journey state machine, the stage timing, the safety check on every outbound
message, and the cards (drawn in code from verified facts). The sentence we are judged by: *Spark interprets the human;
code controls the journey; Concourse never invents a direction.*

## How it works

```
child's phone ──ticket (PDF/photo)──▶ Spark (schema) ──▶ itinerary ──▶ Confirm ──▶ journey planned: 15–17 timed stages
                                                                                      │  T−180 check-in · T−120 security · T−60 gate · T−40 boarding
parent's phone ◀── Telugu voice note + card + 3 buttons ◀── StageScheduler (30 s) ◀──┘  transfer +5/+15/−60/−40 · arrival +10/+20/+45/+65/+75
      │
      ├─ voice note ──▶ Voice Transcribe ──▶ Spark Open / Spark 1.3 (intent, schema) ──▶ code answers from verified facts
      ├─ photo / camera frame ──▶ Spark (what is printed) + SAM 3.1 (where the board is) ──▶ EvidenceReconciler ──▶ MATCH / MISMATCH / IRRELEVANT / UNREADABLE
      └─ buttons: done ✓ · where is my gate · I need help (staff card) · tell my son · I'm here
every outbound ──▶ SafetyPolicy.check() ──▶ voice note (Seamless/MMS, phrase by phrase) + text ──▶ WhatsApp / travel screen / simulator outbox
gate change: only a verified operations event (`POST /ops/events`, labelled "simulated" in the demo) ──▶ both phones, journey re-planned
```

- **State machine** (`journey/`): `PRE_TRIP → CHECK_IN → SECURITY → WAITING_AT_ORIGIN → BOARDING → IN_FLIGHT → TRANSFER_* → US_ARRIVAL → IMMIGRATION → BAGGAGE → EXIT → FAMILY_HANDOFF → COMPLETE`. Time advances one stage; her "done ✓" acknowledges; only "I'm here" completes. Nothing is pushed before her own "start my trip". Outside WhatsApp's 24-hour window a stage is HELD behind the `journey_update` template.
- **Safety contract** (`safety/SafetyPolicy`, `docs/SAFETY.md`): no location claims without evidence, no invented routes, gates or terminals, a photo never overwrites the itinerary, printed text is never obeyed, no immigration answers on her behalf, never "ignore staff". A violating reply is replaced by "show the card to a staff member; I have told your son" and logged. 36 adversarial cases run in CI (`eval/safety/`).
- **Evidence, not inference** (`evidence/`): Spark reports `visible_text`, `gate_codes`, one `explicit_arrow`; `location_claim` is typed null and dropped if ever filled. A direction is spoken only when an arrow was photographed, and only as "follow the arrow". SAM's box is a highlight on the screen, nothing she hears depends on it.
- **Voice** (`tts/`, `voice/`): spoken Telugu written as spoken (reviewed in `docs/TELUGU_SCRIPT.md`), gate codes read letter by letter with their own beat, synthesized phrase by phrase with pauses engineered in code, cached per speaker.
- **The tape** (`ops/ModelTape`): every model call recorded by input hash, replayed for judges without keys; every call on the simulator's ticker with its model id and latency.

## Evals

- `eval/safety/cases.yaml` — 36 adversarial cases run in CI (`SafetySuiteTest`): every string she can hear passes the
  safety policy or is the fallback. Results: `eval/safety/RESULTS.md`.
- `eval/voice/transcribe_eval.py` — the Oct 27 voice gate (real elderly Telugu voice notes → transcript + intent).
- `eval/tickets/` — ticket → itinerary accuracy. `eval/sample-run/` — the recorded run the judge path replays.

Airport time zones come from the OpenFlights airports database (ODbL) — `src/main/resources/data/airports.csv`.

## Hosting (Railway)

Optional — the demo and the judge path run on a laptop. Note: several teams have reported the Meta Model API answering
`404 model_not_found` to hosted servers (Railway, Render); test one call from the host before relying on it.

1. railway.com → New Project → Deploy from GitHub repo → `vkavali/concourse`. The Dockerfile builds the jar with ffmpeg.
2. `+ New` → Database → PostgreSQL.
3. Open the concourse service → Variables → Raw Editor → paste your `.env` (drop the `DATABASE_URL` line), then
   add a variable reference `DATABASE_URL` = `${{Postgres.DATABASE_URL}}`. The app converts the `postgresql://` URL to JDBC.
4. Settings → Networking → Generate Domain → `https://<name>.up.railway.app`.
5. Meta app → WhatsApp → Configuration → Webhook: callback `https://<name>.up.railway.app/webhook/whatsapp`,
   verify token = `WHATSAPP_VERIFY_TOKEN`. Verify and save. Stop ngrok.
6. The hosted database starts empty: send the ticket again, Confirm, register the traveler.
