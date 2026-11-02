# The tape — a recorded run for the judge path

`TAPE_MODE=replay` serves this folder so the whole demo runs on a laptop with no API keys, no TTS server and no
WhatsApp account; `TAPE_MODE=record` writes it during a real run (see README → "Run it on your own WhatsApp test number").

| Folder | What | Provenance |
|---|---|---|
| `spark/<key>.json` | Muse Spark 1.3 outputs: intent classifications, sign observations, the ticket extraction — keyed by a hash of the input (transcript, photo bytes, ticket bytes) with model id, token counts and milliseconds | recorded from live Meta Model API calls |
| `sam/<key>.json` | SAM 3.1 boxes for the sign in each photo and camera frame (special-token text as returned), keyed by concept + photo hash | recorded from live calls |
| `stt/<key>.json` | Muse Voice Transcribe 1.0 transcripts, keyed by the audio bytes' hash | recorded from live calls |
| `tts/<key>.ogg` | the Telugu voice notes Concourse synthesized (SeamlessM4T v2 / MMS-TTS on Modal), keyed by text | our own synthesized audio |
| `inputs/*.wav` | her sample voice notes, as sent through the simulator's 🎤 buttons | recorded by the builder's own voice, with consent — never a parent's note |
| `inputs/sign-*.jpg` | sample airport signs for the 📷 buttons | **rendered, not photographed**: `GATES B1 – B20 ←`, `GATES C1 – C30 →`, `BAGGAGE CLAIM →`, `TRANSFER FLIGHTS ←` |

Nothing here contains a phone number, a name beyond the sample itinerary's "Lakshmi", a ticket, or a transcript of a
real parent. The model-call ticker in the simulator labels every replayed answer "replayed from tape".
