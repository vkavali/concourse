# Latency — measured from a GitHub Actions runner (Nov 2, 2026), not from a phone

The only live run before submission was the `tape` workflow: Concourse on an `ubuntu-latest` runner calling the Meta
Model API, during the judge-path recording. Numbers are the model-call latencies the app logged on its ticker (and
kept in the tape sidecars); they exclude WhatsApp delivery, which was not exercised.

| Call | Model | Samples | Latency |
|---|---|---:|---|
| Transcribe a voice note (1.7–3.8 s of audio) | muse-voice-transcribe-1.0 | 3 | 1.0 · 1.2 · 1.4 s |
| Classify her intent (`parent_turn`, schema-constrained) | muse-spark-1.3 | 8 | 7.5 – 16.2 s, median ≈ 11 s |
| Read a sign photo (`sign_observation`) | muse-spark-1.3 | 4 | 3.3 – 6.1 s |
| Find the sign in the photo (`segment`) | sam-3.1 | 4 | 0.7 – 1.5 s |
| Read the ticket PDF (`itinerary`) | muse-spark-1.3 | 1 | 23.1 s |
| Synthesize a voice note (MMS-TTS, CPU) | facebook/mms-tts-tel | 22 | ≈ 2–5 s per note (CI run log) |

So a voice round trip from a runner was roughly **STT 1.2 s + intent ≈ 11 s + TTS ≈ 3 s ≈ 15 s**, which misses the
under-10-seconds target set on Oct 28. The intent prompt carries the journey context and asks for a schema; shortening it
and warming the connection are the obvious next steps. Photo → verdict ran Spark and SAM in parallel: ≈ 3–6 s.

On the judge path (`TAPE_MODE=replay`) every call is served from the tape in ~0 ms; the ticker says so.
