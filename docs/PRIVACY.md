# Concourse — Privacy Policy

_Last updated: October 27, 2026. Concourse is a prototype built for the Meta Global AI Developer Hackathon by Venkatesh Kavali._

## What Concourse is

Concourse is a WhatsApp-based travel assistant. A family member ("the child") forwards a traveler's flight ticket; Concourse then sends the traveler ("the parent") voice notes and short cards about each step of the airport journey, and answers the traveler's voice notes and sign photos from verified itinerary facts only.

## What we collect

- **Itinerary facts** extracted from the ticket: flight numbers, airports, scheduled times, terminal and gate.
- **Journey state**: which step of the journey was sent and acknowledged, and when.
- **Messages you send to Concourse on WhatsApp**: text, voice notes, photos. Voice notes are transcribed and photos are analysed to produce a reply; the audio and image files are **not stored** by Concourse after the reply is produced. (A developer running a *recording* session — `TAPE_MODE=record`, never the default — keeps the inputs of that session on their own machine for the demo replay; such recordings are published only with the explicit consent of the person who sent them, and the published demo contains the builder's own voice and rendered sign images, never a traveler's.)
- **Phone numbers** of the traveler and the family member, stored in hashed form, plus the number itself in a separate table used only to send messages.
- **Consent**: the traveler's own "start my trip" voice note, recorded as a timestamp.

We do **not** store passport numbers, dates of birth, payment details, or the ticket file itself; ticket files are deleted immediately after the itinerary is extracted.

## How we use it

Only to run the journey assistance described above. We do not sell data, show ads, or use your content to train AI models. Processing uses Meta's Model API in standard mode (Meta states content in standard mode is not used to train Meta models), Meta's WhatsApp Business Platform, and Meta's open-weight speech models (SeamlessM4T v2 or MMS-TTS) hosted by us on Modal.

## Retention

Journey data is deleted 7 days after the last flight's arrival. Message media is not retained. Event logs contain no names, numbers, or transcripts.

## Your rights

Send "delete my data" to the Concourse WhatsApp number, or email the address below, and we delete the journey and contact records immediately.

## Contact

Venkatesh Kavali — vkavali10@gmail.com — https://github.com/vkavali/concourse
