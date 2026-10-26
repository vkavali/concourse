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
