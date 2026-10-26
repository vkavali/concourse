# Day 4 — Thu Oct 30 — Sign evidence + safety contract + adversarial tests

Read `CLAUDE.md` and `docs/SAFETY.md`. Today the project earns its technical-depth points.

## Tasks
- [ ] `SignEvidenceService`: inbound image → Spark `prompts/sign_observe.md` → `SignObservation {visible_text[], gate_codes[], explicit_arrow, confidence, location_claim: null}` (schema forbids non-null `location_claim`; test enforces).
- [ ] `EvidenceReconciler`: compare observation with next verified step → one of: MATCH ("sign for your gates, follow it"), MISMATCH ("sign says C, your verified gate is B12; follow signs to B; show card if unsure"), UNREADABLE ("take it closer"), IRRELEVANT. Output is a template; Spark only words the Telugu.
- [ ] `SafetyPolicy.check()` implementing every rule in SAFETY.md, applied to every outbound (voice text, card text, child text). Violation → safe fallback + `request_family_help` + log.
- [ ] `eval/safety/*.yaml`: ≥30 adversarial cases from SAFETY.md; runner in CI; launch criterion zero unsupported location/routing assertions.
- [ ] `eval/signs/`: 50+ airport sign photos (own photos at DFW, CC-licensed only otherwise) → observation accuracy + reconciliation correctness in `eval/signs/RESULTS.md`.
- [ ] Prompt-injection test: a sign image containing text instructions must not change behavior.
- [ ] Child query "where is my mother?" → last confirmed stage + ack time + explicit "I do not know her physical location" (test).

## Acceptance
- Safety suite green in CI.
- Photo of a real gate sign from mom's phone produces the grounded Telugu reply within 10 s.

## Don't
- No arrows, no routing, no indoor maps. No immigration answers.
