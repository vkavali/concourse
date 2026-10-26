# Concourse — Demo video script (3:00) and live-demo notes

## Rules
- Open with a real person, not a slide. No market numbers until the last 5 seconds, if at all.
- Every simulated element is labeled on screen ("demo operations event").
- Show the model's structured output in a small developer pane at least twice — judges must see Meta's models doing real work.
- Never show a dashboard. Two phones, side by side.

## Script

**0:00–0:10 — One sentence over her face**
"My mother can fly 9,000 miles alone. The part she cannot do is read the airport after she lands."

**0:10–0:30 — The child configures**
Child's phone: forwards the real HYD→DOH→DFW ticket PDF to the Concourse number. Developer pane shows Spark's extracted itinerary JSON (flight numbers, times, terminals). Voice-over: "Her son configures the software because she can't."

**0:30–0:55 — The parent consents and hears the first step**
Parent's phone: she sends a voice note "start my trip." Concourse replies with a Telugu voice note — play 8 seconds unsubtitled, then subtitle: "Show your passport and ticket at the Qatar Airways counter." Card appears with the counter and a big icon.

**0:55–1:25 — The hard part (Doha transfer)**
Timed stage fires: "Gate B12." She records a natural Telugu voice note. Developer pane: Voice Transcribe transcript + confidence → Spark tool call `get_next_stage`. Telugu voice reply.

**1:25–1:55 — The killer interaction**
She photographs a real gate sign. Developer pane:
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

## Live product for judges
- A real WhatsApp number anyone can message; a sample ticket PDF linked from the README; English fallback for judges who don't speak Telugu (the parent language is per-journey).
- Simulator page (optional) labeled SIMULATOR for judges who won't enroll a phone.

## Recording
- Screen-record both phones (Android screen record; iPhone if the child uses one). Film the parent's face with consent. Capture the developer pane from the backend log viewer.
- Keep raw clips; cut to 3:00; captions for every Telugu line; no music over voice notes.
