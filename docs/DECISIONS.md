# Decisions log (one line each; newest last)

- Oct 26: Build Concourse (WhatsApp journey escort for a parent who can't use apps). Scope = 9 features in CLAUDE.md; Hindi, immigration rehearsal, boarding-pass check, Muse connector, Muse Image, AR cut.
- Oct 26: Telugu only until the voice gate passes. Parent input confidence-gated; never type.
- Oct 26: Spark interprets, code controls the journey; safety contract enforced in code and tested.
- Oct 26: Gate-change for the demo is a labeled simulated operations event; no multi-airline live data.
- Oct 26: Android Travel Mode is optional (Nov 2 go/no-go); demo phone sideloaded.
- Oct 26: Standard (non-contributor) Spark tier for real tickets; no passport numbers stored; tickets deleted after extraction.
- Oct 26: TTS = Meta's open-source MMS-TTS Telugu on Modal (keeps the voice loop on Meta models; uses Modal credits); Google Chirp only if MMS fails the Day-1 intelligibility test.
- Oct 26: Built with Claude Code (allowed: rules permit third-party technical assistance; product must use Meta models, and does). Use Muse Code for the eval runner so both can be cited.
- Oct 26: Day-0 scaffold written in a sandbox without Maven Central access — first `gradle test` runs on vv's machine; run `gradle wrapper --gradle-version 8.14.3` and commit the wrapper before the Dockerfile build.
- Oct 27: Voice Transcribe's file endpoint returns no confidence scores → confidence for the parent loop comes from Spark's intent classifier (`confidence` field in its JSON) plus rules (empty/short transcript, intent OTHER) — the <0.7 gate in CLAUDE.md applies to that number.
- Oct 27: Spark reads only printed local times (`YYYY-MM-DDTHH:MM`); code resolves them to instants with the airport's IANA zone (OpenFlights table, ODbL). The model never computes time zones.
- Oct 27: Hibernate `ddl-auto: none` — Flyway owns the schema; entities set their own defaults in `@PrePersist`.
- Oct 27: Graph API v25.0 (console default). New Facebook account's test WhatsApp Business Account was auto-locked (error 131031) 10 h after creation → appeal filed; messaging layer is behind `MessagingChannel` so a Twilio WhatsApp Sandbox adapter can stand in if Meta does not unlock in time.
- Oct 27: MMS-TTS on Modal is CPU-only (VITS synthesises a sentence in ~1 s); speaking rate 0.9 for elderly listeners; OGG/Opus 24 kbps mono so a 60 s note stays well under WhatsApp's 512 KB inline-play limit.
