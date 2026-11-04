# Submission text (draft — fill the bracketed numbers after the Nov 2 run)

Form: https://cerebralvalley.ai/e/meta-ai-developer-hackathon/hackathon/submit — due Wed Nov 4, 9:00 PM ET (our cutoff 6:00 PM CT).
Fields seen so far: team name (mandatory), track, repo link, demo video link, X post link (Most Viral), description.

**Team name:** Concourse  
**Track:** Indie Developer · theme: Build for Your World  
**Repo:** https://github.com/vkavali/concourse  
**Video:** [YouTube link — made by the `video` workflow; see the Releases page]  
**X post:** [link]

## Description

**Concourse escorts a parent who cannot use apps or read English through an international flight — on WhatsApp, by
voice, in Telugu, with her family in the loop.**

My mother can fly 9,000 miles alone. The part she cannot do is read the airport after she lands. Every stage of a
HYD → DOH → DFW trip — check-in, security, the gate, boarding, the Doha transfer, immigration, baggage, the exit — arrives
on her phone at the right time as a Telugu voice note and a card she can match against the boards (the board word in
English capitals, huge, plus the gate code). She answers the only way she knows: a WhatsApp voice note, or a photo of a
sign. Concourse answers only from the verified itinerary and what is actually printed in her photo. A gate change
reaches both phones. One tap shows a card to airport staff; one tap tells her son "I'm here."

**Built on Meta — every model that runs is a Meta model:**
- **Muse Spark 1.3** (Meta Model API, standard tier) reads the ticket (PDF or a crumpled boarding-pass photo) into a
  schema-constrained itinerary, classifies what she means from her transcript (intent · confidence · tool proposal),
  and reads the signs she photographs or points her camera at (`visible_text`, `gate_codes`, one `explicit_arrow`;
  `location_claim` is typed null — the model cannot say where she is).
- **SAM 3.1** finds the sign in every photo and camera frame (concept "sign"), in parallel with Spark; the travel screen
  outlines it with the matched word — a highlight of what the camera can see, never a route.
- **Muse Voice Transcribe 1.0** turns her Telugu voice notes into text with a Telugu/English language bias and airport
  vocabulary.
- **MMS-TTS Telugu** (Meta open weights) is her voice — every note on the tape was synthesized with it; SeamlessM4T v2
  is supported behind a flag. **Muse Spark Open** (open weights) can classify her intent first when configured; the
  recorded run used Spark 1.3.
- **WhatsApp Business Cloud API** is the channel: voice notes, cards, three-button replies, a link button that opens
  her travel screen (card, voice, live camera reading of the boards, hold-to-talk).

**What is deliberately not a model:** the journey state machine and its timing, the gate (only a verified operations
event changes it), the safety check on every outbound message, and the cards. *Spark interprets the human; code controls
the journey; Concourse never invents a direction.* The safety contract (`docs/SAFETY.md`) is enforced in code and tested
by 36 adversarial cases in CI: no location claims, no invented routes or gates, a sign photo can never overwrite the
itinerary, printed text is never obeyed, immigration questions are never answered for her, she is never told to ignore
staff. A violation is replaced by "show the card to a staff member; I have told your son."

**Evidence:** 36/36 adversarial safety cases green in CI (`eval/safety/RESULTS.md`); the full demo path — 15 stages,
the gate change, 4 sign photos, 3 voice notes, the ticket — run end to end against live Meta model calls on Nov 2 and
replayed by CI on every commit since: 103/103 model calls, 7/7 of her inputs answered from verified facts, 0 invented
directions (`eval/judge-path/`). Latency measured only from a GitHub runner (`eval/latency/RESULTS.md`).

**What was not verified before the deadline, said plainly:** the journey on real WhatsApp phones (the client is
implemented and tested against the API contract; the test business account was locked on Oct 27 and the end-to-end
phone run was not completed), and a walkthrough with a real elderly user. The video is the real app replaying its
recorded model calls, with her synthesized Telugu voice — nothing in it is staged outside the code.

**Run it:** the README's "five minutes" path replays a recorded run (every model answer keyed by its input, her sample
voice notes and sign photos, the synthesized notes) on a laptop with no keys; with a Meta Model API key the calls go
live; with a WhatsApp test number it runs on real phones. The simulator shows both phones and a ticker naming every Meta
model call as it happens. CI runs that exact keyless path on every commit (`eval/judge-path/smoke.sh`) and the tape
itself is recorded by a workflow, so what a judge sees is what CI saw.

**Agentic, with the keys taken away:** Spark proposes — an intent, a tool call (`get_next_stage`, `acknowledge_stage`,
`request_family_help`, `create_staff_card`), a sign observation — and the controller validates every proposal against the journey's state before
anything happens; the model never holds the clock, the gate, or her location. The handoffs are to people: airport staff
(the card) and her son (the thread), never to another model.

**Everything in the repository was built between Oct 26 and Nov 4, 2026; nothing pre-existed.** Apache-2.0.

**Next (not in the video):** live gate feeds (FlightAware) behind the same verified-update gate; Hindi and Tamil
voices; the travel screen as an installable home-screen page; a second family member on the thread; airport-staff
handoff cards in the local language of each airport.

## X post (Most Viral)

> My mother can fly 9,000 miles alone. The part she can't do is read the airport.
> Concourse escorts her through the trip on WhatsApp — Telugu voice notes, a card she matches to the boards, a camera
> that reads signs, her son in the loop. Every model is Meta's; the code never invents a direction.
> Built in 10 days for the #MetaHackathon. Video ↓ [link] · Code: github.com/vkavali/concourse

## Nov 4 checklist
- [ ] Repo public, `main` green, no secrets (`gitleaks` job), LICENSE present, README runs from a clean clone.
- [ ] Video ≤ 3:00, English subtitles on every Telugu line, opens with the "built this week" line, model ticker visible, no "next" segment.
- [ ] `eval/sample-run/` committed and replay verified from a clean clone with no keys.
- [ ] Numbers filled in above; description pasted; X post linked; every teammate (vv only) added.
- [ ] Submitted by 6:00 PM CT; confirmation screenshot saved.
