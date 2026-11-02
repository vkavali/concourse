# Concourse — Demo video script (3:00) and live-demo notes

## Rules (updated Nov 1 from the organizers' answers — docs/HACKATHON_INTEL.md)
- First line, spoken, over the first shot: "Everything you see was built between Oct 26 and Nov 4." Then a real person, not a slide.
- 3:00 hard stop (judges stop the video there). A human presenter is preferred; English subtitles under every Telugu line.
- The live Meta model calls must be **visible**: the simulator's "Meta model calls" ticker (model id · operation · ms) sits on screen whenever a model is working. That is the provenance judges asked for.
- Every simulated element is labeled on screen ("demo operations event").
- Never show a dashboard as the product. Two phones, side by side; the simulator only as the console/ticker.
- No "coming next" segment — future plans go in the written submission.

## Script

**0:00–0:10 — One sentence over her face**
"My mother can fly 9,000 miles alone. The part she cannot do is read the airport after she lands."

**0:10–0:30 — The child configures**
Child's phone: forwards the HYD→DOH→DFW ticket PDF to the Concourse number (in the simulator: button **1b**). The ticker
shows the first model call — `muse-spark-1.3 · itinerary · 4.2 s` — and the child's phone gets the itinerary Spark read
(flight numbers, times, terminal). Voice-over: "Her son configures the software because she can't."

**0:30–0:55 — The parent consents and hears the first step**
Parent's phone: she sends a voice note "start my trip." Concourse replies with a Telugu voice note — play 8 seconds unsubtitled, then subtitle: "Show your passport and ticket at the Qatar Airways counter." Card appears with the counter and a big icon.

**0:55–1:25 — The hard part (Doha transfer)**
Timed stage fires: "Gate B12." She records a natural Telugu voice note. Developer pane: Voice Transcribe transcript + confidence → Spark tool call `get_next_stage`. Telugu voice reply.

**1:25–1:55 — The killer interaction**
She photographs a real gate sign; then she opens the camera from the WhatsApp button and points it at the board — the
screen outlines the board (SAM 3.1), shows the matched word and the arrow the board itself has, and speaks the verdict.
Developer pane (the simulator ticker: `muse-spark-1.3 · sign_observation · 2.1 s`, `sam-3.1 · segment sign · 1.4 s`):
```
{ "visible_text": ["Gates B1-B18"], "explicit_arrow": "right", "location_claim": null }
```
Telugu reply, subtitled: "The sign in your photo points right for the B gates. Your confirmed gate is B12. Follow that sign. I don't know your exact location. Send another photo when you reach the B gates."

**1:55–2:20 — Something changes**
On screen: `QR729 · B12 → C7 · source: demo operations event`. Both phones update: parent gets voice; child gets "Gate changed. Your mother has been notified."

**2:20–2:40 — When AI is not enough**
One tap: SHOW STAFF. Full-screen card: I NEED HELP · QR729 → DALLAS · GATE C7 · I SPEAK TELUGU · PLEASE CALL MY SON +1 …

**2:40–2:55 — The handoff**
Arrival. She taps I'M HERE. Child's phone shows her confirmation. Voice-over: "Not tracked. She told her family she arrived."

**2:55–3:00 — Evidence, not TAM**
```
1 real parent · 1 real itinerary · N Telugu voice notes
N/N instructions understood · 0 invented directions
```
"Concourse. Let the person who knows the trip help the person taking it — from anywhere."

## Evidence hierarchy (use the highest you reach)
1. Real elderly Telugu-speaking parent + real flight
2. Real elderly Telugu-speaking parent + DFW Terminal D walkthrough, no coaching
3. Real parent + route replay with real airport signs
4. Developer pretending — do not ship this

## Judges (what they actually do)
- They run the repo from the README and watch the video; hosting and phones are optional. The judge path (`TAPE_MODE=replay`,
  README "Run the demo in five minutes") must reproduce every scene above from the simulator, and `eval/sample-run/README.md`
  says what is recorded and what is rendered.
- The written submission carries what the video cannot: the safety contract, the eval numbers (`eval/safety/RESULTS.md`,
  `eval/latency/RESULTS.md`), and what comes next.

## Title and end cards
`http://localhost:8080/cards.html` — five full-screen cards (← → to switch, F for full screen): the "built this week"
opener, the one-line problem, the rule, the Meta models, and the evidence numbers (fill the placeholders from
`eval/walkthrough/RESULTS.md` and `eval/latency/RESULTS.md`). Screen-record them for the cuts.

## Subtitles
Every Telugu line she hears is in `docs/TELUGU_SCRIPT.md` with its English meaning — paste from there; the stage texts
are deterministic for the sample journey, so the lines in the video are exactly those.

## If the phones fail (WABA locked, no network)
The simulator **is** the phones: press H for the recording layout (traveler's phone · child's phone · model calls), "Bigger
type" on, and film the screen; one on-screen line says "simulator — same code, no WhatsApp". Every scene above exists
there: 1b (ticket) → 2 (consent) → 3 (stages) → ⚡ gate change → 🎤 / 📷 samples → I'm here.

## Recording
- Screen-record both phones (Android screen record; iPhone if the child uses one). Film the parent's face with consent. Capture the developer pane from the backend log viewer.
- Keep raw clips; cut to 3:00; captions for every Telugu line; no music over voice notes.
