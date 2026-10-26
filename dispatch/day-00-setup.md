# Day 0 — Sun Oct 26 — Setup (no product code yet)

Read `CLAUDE.md` first. Goal: every account, key, and asset in place so Day 1 starts with code.

## Human tasks (vv, tonight)
- [ ] Claim Meta Model API credits (Discord #resources form) → add card on dev.meta.ai → set spend limit $150. Apply for Modal ($100) and Baseten ($100) credits.
- [ ] Meta developer app with WhatsApp product: get test number + temporary token; add vv, wife, mom, dad, one friend as test recipients (5 max). Note `PHONE_NUMBER_ID`, `WABA_ID`.
- [ ] Submit Utility template `journey_update`: "Your journey has an update. Reply to hear it in Telugu." (category Utility, language te/en). Approval can take hours–days.
- [ ] Modal account + credits form (done); Day 1 deploys `facebook/mms-tts-tel` as a Modal endpoint. Google Cloud TTS only as fallback — set up only if the MMS voice fails with mom.
- [ ] FlightAware AeroAPI personal key (optional; demo uses a simulated event).
- [ ] Create PUBLIC GitHub repo `concourse`; push this skeleton (CLAUDE.md, docs/, dispatch/, .env.example). No secrets.
- [ ] Read employment agreement invention-assignment clause; decide; record in `docs/DECISIONS.md`.
- [ ] Collect 10 real Telugu voice notes from mom/dad (ask them to say things like "where is my gate", "I am at the counter", "I need help", "my bag did not come", "call my son") → store OUTSIDE the repo in `~/concourse-data/voice/`.
- [ ] Get one real ticket PDF from a past parent trip → `~/concourse-data/tickets/`.
- [ ] Ask the NRI circle: anyone's parent flying India→US before Nov 3? Any Telugu-speaking 60–75-year-old in DFW willing to do a walkthrough on Nov 1?
- [ ] Host: create a Fly.io or Railway app with a public HTTPS URL; Postgres attached.

## Coding-agent tasks (Muse Code preferred where it works; Claude Code otherwise)
- [x] Scaffold Spring Boot 3 / Java 21 project (done in sandbox). On your machine: `gradle wrapper --gradle-version 8.14.3 && ./gradlew test` — fix any compile nit, commit the wrapper.
- [ ] `.env.example` with: `MODEL_API_KEY`, `WHATSAPP_TOKEN`, `WHATSAPP_PHONE_NUMBER_ID`, `WHATSAPP_VERIFY_TOKEN`, `WHATSAPP_APP_SECRET`, `GOOGLE_APPLICATION_CREDENTIALS`, `AEROAPI_KEY`, `DATABASE_URL`, `PUBLIC_BASE_URL`, `ADMIN_TOKEN`, `PHONE_HASH_SALT`.
- [ ] `WebhookController`: GET verify handshake; POST receives messages, validates `X-Hub-Signature-256`, logs sanitized payload, returns 200 fast (async processing).
- [ ] `WhatsAppClient`: send text; send interactive buttons (≤3); upload media; send audio with `voice:true`; send template. Integration test against the test number: one text message to vv's phone.
- [ ] Flyway V1 with the schema in `docs/ARCHITECTURE.md`.
- [ ] `docs/DECISIONS.md` created with today's decisions.

## Acceptance
- Webhook verified by Meta; a text sent from vv's phone appears in logs; a text sent from the service arrives on vv's phone.
- Repo public, CI runs tests, no secrets committed (add a pre-commit secret scan).

## Don't
- Don't start the state machine or Spark integration today.
- Don't put any voice note, ticket, or phone number in the repo.
