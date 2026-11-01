# Hackathon intel — what the organizers actually said (read Nov 1, 2026)

Source: the hackathon Discord (#rules, #faqs, #announcements, #office-hours, #questions, #meta). Quotes are from
organizers (Kai | CV, Ivan, Adam Blumenfeld) or the Cerebral Valley rules bot, which answers from the Official Rules.
Official Rules: https://cerebralvalley.ai/e/meta-ai-developer-hackathon/details

## Rubric (official weights)

Each judged project is scored 0–10 on four criteria: **Impact 30 % · Use of Meta AI Tooling 25 % · Technical Depth &
Execution 20 % · Demo 25 %.** Stage One is pass/fail (theme fit, meaningful use of Meta Model API and/or Meta
open-weight models, rules compliance). Top 5 per track go to a final round judged **live at the closing ceremony,
Mon Nov 9 12:00 PM ET, with the same rubric — the judges watch the 3-minute videos; no live Q&A has been announced.**
Indie pool: $150k / $100k / $75k / $50k / $25k. Bonuses stack: Most Viral $20k (likes + reposts + comments on the one X
post linked in the submission, counted through Nov 8 EOD), Best Build on Meta Open Weight Models $10k (needs Muse
Glimmer or Muse Spark Open — MMS/Seamless do not qualify), Community Vote $10k, Best Agentic Use-Case $5k, Best Creative
Build $5k.

Demo criterion, in the organizers' words: "whether it's a working, impressive demo, whether the product holds up live,
and whether the video is genuinely cool to watch."

## Rules that bind this repo

1. **"Every model your project calls at runtime must be a Meta model."** Third-party AI voices (ElevenLabs, Wit.ai,
   Kokoro, Google) are not allowed even as a side component and even if disclosed. Allowed for speech output: Meta's
   open-source TTS (MMS-TTS, Seamless) run anywhere, or a built-in OS/browser voice that is not an AI model call.
   README must say which open Meta models are used, where they run, and their licenses. Third-party *libraries,
   APIs and frameworks* (WhatsApp Cloud API, ffmpeg, Spring, Modal) are fine within their licenses.
2. New work only: repo created on/after Oct 26 12:00 PM ET, incremental commits preferred, README labels anything
   pre-existing, video opens with one line saying the same. (This repo's first commit: Oct 26, 6:39 PM CT.)
3. Public repo. Add a LICENSE. Keep every key out of the repo; `.env.example` explains how a judge supplies a Meta
   Model API key.
4. Submission by **Wed Nov 4, 9:00 PM ET** at https://cerebralvalley.ai/e/meta-ai-developer-hackathon/hackathon/submit:
   public GitHub repo + 3-minute demo video + every teammate added + X post link (viral) + description. Editable until
   the deadline, not after. Future plans go in the written description, never in the video.
5. Demo video: 3:00 (they stop watching at 3:00), shows the working product not slides, no code walkthrough needed,
   human presenter preferred (AI voice-over and sped-up runs are allowed if labelled), understandable in English
   (Telugu audio needs English subtitles), and **"the live Meta model calls visible"** — "a working feature on screen
   with a log line naming the Meta model IDs is good provenance."
6. Hosting is **not required**. "Judges need a project that runs from the public repo's setup instructions, plus the
   demo video." "Judges mainly work from your 3-minute demo video and public GitHub repo, and they may do some testing
   with their own keys." "Don't count on judges testing on a phone" — show phone flows in the video, explain desktop
   fallbacks in the README.
7. WhatsApp projects, organizer ruling (Oct 28): "Judges don't need a hosted version or a shared test number. Your
   project needs to be runnable from the repo with setup instructions, plus the 3-minute demo video, so show the
   WhatsApp channel working in the video. Document in your README how someone can run it with their own WhatsApp Cloud
   API test number, and add as much reviewable material as you can."
8. "A judge path that runs on recorded sample runs is accepted as long as you include as much demo provenance as
   possible so it can be evaluated offline" — but "a replay alone is not enough; the full demo in the video must still
   run from the repo's setup instructions."
9. Anti-projects: AI medical/health advice, basic RAG, simple image analyzers, generic education chatbots, job
   screeners, nutrition coaches, personality analyzers, sports coaches, political/war. Elder-assistance was explicitly
   ruled fine as long as it gives no medical, medication or mental-health advice and says so.

## What this means for Concourse

- **Google Chirp is a disqualifier at runtime.** Ship on SeamlessM4T v2 / MMS-TTS (Meta open weights on Modal) and
  remove the Google provider before freeze so no non-Meta model can be called.
- Build a **judge path**: `JUDGE_MODE` that runs the full demo from the simulator with the outbox (no WhatsApp account
  needed), with recorded sample-run audio and cards committed under `eval/sample-run/` as provenance, and live Spark /
  Voice Transcribe when the judge supplies a key. README: "Run it in 5 minutes" → "Run it on your own WhatsApp test
  number" → "Run it on a phone".
- Put **model IDs on screen**: the simulator event log prints `muse-spark-1.3`, `muse-voice-transcribe-1.0`,
  `facebook/seamless-m4t-v2-large` next to every call so the video satisfies "live Meta model calls visible".
- The video is the whole final round. Real phones, real Telugu voice notes, English subtitles, one line up front:
  "Everything in this repo was built Oct 26 – Nov 4."
- Hosted Meta API calls from Railway/Render have failed for several teams (`404 model_not_found` from datacenter IPs).
  Test before relying on Railway; the demo can run locally behind ngrok, and judges run locally anyway.
- Spark latency spikes (up to a minute) and `finish_reason=length` truncation are being reported; keep TTS prewarming,
  keep `max_tokens` generous for JSON turns, and record the demo when the API is healthy.
