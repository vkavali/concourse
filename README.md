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

## Meta models used

| Need | Model | How |
|---|---|---|
| Read tickets, classify intents, read signs | Muse Spark 1.3 (standard tier) | `POST /v1/chat/completions`, constrained JSON (`response_format: json_schema`), inline PDF/image |
| Understand Telugu voice notes | Muse Voice Transcribe 1.0 | `POST /v1/asr/transcribe` (WAV 16 kHz mono), `languageBias: ["Telugu","English"]`, airport `keywords` |
| Speak Telugu | MMS-TTS Telugu (`facebook/mms-tts-tel`, Meta open weights) | served on Modal — `modal/mms_tts.py` |

## Evals

- `eval/voice/transcribe_eval.py` — the Oct 27 voice gate (real elderly Telugu voice notes → transcript + intent).
- `eval/tickets/` — ticket → itinerary accuracy. `eval/safety/` — adversarial suite (Day 4).

Airport time zones come from the OpenFlights airports database (ODbL) — `src/main/resources/data/airports.csv`.

## Hosting (Railway)

1. railway.com → New Project → Deploy from GitHub repo → `vkavali/concourse`. The Dockerfile builds the jar with ffmpeg.
2. `+ New` → Database → PostgreSQL.
3. Open the concourse service → Variables → Raw Editor → paste your `.env` (drop the `DATABASE_URL` line), then
   add a variable reference `DATABASE_URL` = `${{Postgres.DATABASE_URL}}`. The app converts the `postgresql://` URL to JDBC.
4. Settings → Networking → Generate Domain → `https://<name>.up.railway.app`.
5. Meta app → WhatsApp → Configuration → Webhook: callback `https://<name>.up.railway.app/webhook/whatsapp`,
   verify token = `WHATSAPP_VERIFY_TOKEN`. Verify and save. Stop ngrok.
6. The hosted database starts empty: send the ticket again, Confirm, register the traveler.
