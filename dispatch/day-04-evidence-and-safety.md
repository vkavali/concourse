# Day 4 — Thu Oct 30 — Sign evidence + safety contract + adversarial tests

Read `CLAUDE.md` and `docs/SAFETY.md`. Today the project earns its technical-depth points.

## Tasks
- [x] `SignEvidenceService`: inbound image → Spark `prompts/sign_observe.md` → `SignObservation {visible_text[], gate_codes[], explicit_arrow, confidence, location_claim: null}` (schema forbids non-null `location_claim`; test enforces).
- [x] `EvidenceReconciler`: compare observation with next verified step → one of: MATCH ("sign for your gates, follow it"), MISMATCH ("sign says C, your verified gate is B12; follow signs to B; show card if unsure"), UNREADABLE ("take it closer"), IRRELEVANT. Output is a template; Spark only words the Telugu.
- [x] `SafetyPolicy.check()` implementing every rule in SAFETY.md, applied to every outbound (voice text, card text, child text). Violation → safe fallback + `request_family_help` + log.
- [ ] `eval/safety/*.yaml`: ≥30 adversarial cases from SAFETY.md; runner in CI; launch criterion zero unsupported location/routing assertions.
- [ ] `eval/signs/`: 50+ airport sign photos (own photos at DFW, CC-licensed only otherwise) → observation accuracy + reconciliation correctness in `eval/signs/RESULTS.md`.
- [x] Prompt-injection test: a sign image containing text instructions must not change behavior.
- [x] Child query "where is my mother?" → last confirmed stage + ack time + explicit "I do not know her physical location" (test).

## Acceptance
- Safety suite green in CI.
- Photo of a real gate sign from mom's phone produces the grounded Telugu reply within 10 s.

## Don't
- No arrows, no routing, no indoor maps. No immigration answers.

## Day 4 report (written Oct 28, night — two days early)
- Passed: sign cards for every non-gate stage; photo → observation → reconciler → templated Telugu (MATCH/MISMATCH/IRRELEVANT/UNREADABLE), arrows only when observed, injection text ignored, location claims dropped; Travel Mode page with live camera over the same path; 139 tests green on CI (`3b3fa90`).
- Not done: `eval/safety/*.yaml` adversarial suite as a separate runner (the cases are covered by unit tests in SafetyPolicyTest, EvidenceReconcilerTest, SignEvidenceServiceTest — the YAML suite + CI runner is Day 5 morning); `eval/signs/` real photo set (needs photos from a real airport or CC-licensed ones).
- Open: native review of docs/TELUGU_SCRIPT.md; real-phone run of Travel Mode (needs PUBLIC_BASE_URL = the HTTPS host).
