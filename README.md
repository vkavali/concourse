# Concourse

A WhatsApp agent that escorts a parent who cannot use apps or read English through an international flight — by voice, in her language, with her family in the loop.

Entry for the Meta Global AI Developer Hackathon (indie track, Build for Your World). All code written Oct 26 – Nov 4, 2026.

See `CLAUDE.md` (project brain), `docs/PLAN.md`, `docs/ARCHITECTURE.md`, `docs/SAFETY.md`, `docs/DEMO.md`, and the daily `dispatch/` files.

## Run

```bash
cp .env.example .env            # fill in keys; never commit .env
./gradlew test                  # needs Java 21 + ffmpeg on PATH
set -a && source .env && set +a
./gradlew bootRun               # H2 in-memory DB unless DATABASE_URL is set
```

Expose `https://<host>/webhook/whatsapp` (Fly.io/Railway/ngrok) and register it in the Meta app's WhatsApp → Configuration
with `WHATSAPP_VERIFY_TOKEN`; subscribe to `messages`.

## Built on Meta

Every model decision in the journey is a Meta model; the channel is WhatsApp.

| Need | Meta tool | How |
|---|---|---|
| Reach a parent who only uses WhatsApp | **WhatsApp Business Cloud API** | webhooks in, voice notes (`voice: true`), images, 3-button replies, link buttons (`cta_url`), templates outside the 24-h window |
| Read the ticket (PDF or a crumpled boarding-pass photo) | **Muse Spark 1.3** (Meta Model API, standard tier) | `/v1/chat/completions`, inline PDF/image, constrained JSON (`response_format: json_schema`) |
| Understand her Telugu voice note | **Muse Voice Transcribe 1.0** | `/v1/asr/transcribe`, WAV 16 kHz mono, `languageBias: ["Telugu","English"]`, airport `keywords` |
| Classify what she means (gate? help? son? start my trip?) | **Muse Spark 1.3** | intent + confidence + tool proposal; code decides and answers from verified facts |
| Read the board she photographs, or points the camera at | **Muse Spark 1.3** (vision) | `visible_text`, `gate_codes`, one `explicit_arrow`; `location_claim` is typed null |
| Speak Telugu | **MMS-TTS** (`facebook/mms-tts-tel`, Meta open weights) and **SeamlessM4T v2** (`facebook/seamless-m4t-v2-large`, Meta open weights) | served from `modal/` — Meta has no TTS API, so its open models are the voice; Google Chirp 3 HD is wired only as a fallback switch (`TTS_PROVIDER=google`) if a real parent cannot follow the open voice |

What is deliberately **not** a model: the journey state machine, the stage timing, the safety check on every outbound message, and the cards (drawn in code from verified facts). The sentence we are judged by: *Spark interprets the human; code controls the journey; Concourse never invents a direction.*

## Evals

- `eval/voice/transcribe_eval.py` — the Oct 27 voice gate (real elderly Telugu voice notes → transcript + intent).
- `eval/tickets/` — ticket → itinerary accuracy. `eval/safety/` — adversarial suite (Day 4).

Airport time zones come from the OpenFlights airports database (ODbL) — `src/main/resources/data/airports.csv`.

## Travel Mode (her screen)

After the intro and again after consent she gets a WhatsApp button **తెరవండి** that opens `travel.html#<journey>.<token>` in her phone browser: the current card, its voice, a live camera that reads the boards she points at (same reader and safety check as a WhatsApp photo), and the four buttons. Needs `PUBLIC_BASE_URL` set to the public HTTPS host (ngrok or Railway); the camera only works over HTTPS. The simulator shows the same link.

## Demo in ten minutes (one phone)

1. Send the boarding pass → **Confirm**.
2. `mode parent` → tap **నా ప్రయాణం మొదలు** on the intro (her consent; nothing is pushed before it).
3. `mode child` → `next` fires the next planned stage now (the journey clock jumps to its time): CHECK_IN, SECURITY, gate stages with the card, transfer, arrival, FAMILY_HANDOFF.
4. `gate C7` / `gate 2 C7` = a labelled simulated operations event: the only way a gate changes; both phones are told.
5. Tap **నేను వచ్చాను** ("I'm here") on the last stage → journey complete.

`plan` lists the timeline, `status` the itinerary, `clock reset` returns to real time. Ops: `POST /ops/events` and `POST /ops/clock` with header `X-Admin-Token` (set `ADMIN_TOKEN`).

## Hosting (Railway)

1. railway.com → New Project → Deploy from GitHub repo → `vkavali/concourse`. The Dockerfile builds the jar with ffmpeg.
2. `+ New` → Database → PostgreSQL.
3. Open the concourse service → Variables → Raw Editor → paste your `.env` (drop the `DATABASE_URL` line), then
   add a variable reference `DATABASE_URL` = `${{Postgres.DATABASE_URL}}`. The app converts the `postgresql://` URL to JDBC.
4. Settings → Networking → Generate Domain → `https://<name>.up.railway.app`.
5. Meta app → WhatsApp → Configuration → Webhook: callback `https://<name>.up.railway.app/webhook/whatsapp`,
   verify token = `WHATSAPP_VERIFY_TOKEN`. Verify and save. Stop ngrok.
6. The hosted database starts empty: send the ticket again, Confirm, register the traveler.
