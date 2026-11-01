# Walkthrough — a Telugu speaker aged 60–75 who did not build this (Nov 2)

This is the Impact evidence: one real person, no coaching, filmed with consent. Thirty minutes. Two phones: hers (any
Android/iPhone with WhatsApp) and the child's (vv). The app runs on vv's laptop behind ngrok with `TAPE_MODE=record`
so every voice note, photo and model answer of the session is kept as a sample for the judge path.

## Before she arrives (15 min)
1. `.env`: `TAPE_MODE=record`, `PUBLIC_BASE_URL=https://<ngrok>`, keys set, `TTS_BASE_URL` on Seamless. Restart.
2. Add her number as a WhatsApp test recipient; register both numbers (`mode child` on vv's phone, or press **1** in the
   simulator with both numbers filled in). Her phone must have received the intro before she sits down.
3. Print the four sign images from `eval/sample-run/inputs/` on A4, large. Tape them on different walls of the room.
4. Simulator open on the laptop, "bigger type" on, screen recording on. Phone screen recording on her phone if possible.
5. Consent, in Telugu, in your words, recorded on camera: this is a hackathon demo; her voice and photos stay on this laptop
   and the public repo will hold only the ones she agrees to; she can stop at any time.

## The session — no coaching
Say only this (in Telugu): "Amma, this is a travel helper on WhatsApp. It will tell you what to do. Do what it says.
When you want to ask something, press the mic and talk, like a voice note to me."

Then press **3** in the simulator for each stage and **watch**. Do not explain the buttons. Do not translate. If she
asks you a question, say "ask the phone".

Stages to run (the whole trip takes ~20 min with the clock jumping):
CHECK_IN → SECURITY → WAITING_AT_ORIGIN (gate card) → BOARDING → IN_FLIGHT → TRANSFER_ARRIVAL → TRANSFER_FIND_GATE
→ TRANSFER_WAITING → BOARDING → IN_FLIGHT → US_ARRIVAL → IMMIGRATION → BAGGAGE → EXIT → FAMILY_HANDOFF.

Prompts you may give, once each, between stages (these are the scenes the video needs):
- "Ask it where your gate is." (voice note)
- "Photograph that sign and send it." (point at the GATES B board)
- "Now that one." (the GATES C board — the mismatch)
- "Press the button that opens the camera and look at the board." (Travel Mode)
- "Tell it you are lost."
- At the end: "Press 'I'm here'."
During TRANSFER_WAITING, apply the gate change from the simulator and say nothing.

## Observe — fill `eval/walkthrough/RESULTS.md` live (one row per stage)
| Stage | Understood the voice note? (asked to replay / nodded / acted) | Knew what to do? | Found the button? | Seconds to act | What she said or asked | What she looked at instead |

And for every input she sent: transcript (from the ticker/log), intent, correct? reply helpful? — and every moment she
looked for a human.

## After (10 min)
- Rename the inputs she agreed to share: `eval/sample-run/inputs/<ts>-voice.wav` → `voice-where-is-my-gate.wav` etc.
  Delete the rest. Commit `eval/sample-run/` (model outputs, her consented samples, the synthesized notes).
- Write the numbers: stages understood N/16, inputs understood N/M, times. These go in the video's last frame and the
  written submission.
- Fix what confused her tonight; the Telugu wording changes go through `docs/TELUGU_SCRIPT.md`.

## If no elder is available on Nov 2
Run it with any Telugu-speaking adult who has never seen the product, and say so in the submission. A real person
who did not build it is still evidence; a developer pretending is not (DEMO.md, evidence hierarchy).
