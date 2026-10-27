# WhatsApp message templates (submit in WhatsApp Manager → Message templates; category Utility)

Templates are the fallback for messages outside the 24-hour window. The main path stays free-form: the parent's own
"start my trip" voice note opens the window, and every stage push inside the window is a normal voice note + card.

## journey_update — Utility — languages: te, en

Body (te): మీ ప్రయాణంలో కొత్త సమాచారం ఉంది. తెలుగులో వినడానికి ఈ సందేశానికి జవాబు ఇవ్వండి.
Body (en): Your journey has an update. Reply to hear it in Telugu.

Sent by `StageScheduler` when a stage is due and the window is closed; the voice note goes out on her reply.

## journey_today — Utility — languages: te, en — one body parameter {{1}} = flight number

Body (te): మీ విమానం {{1}} ఈ రోజు ఉంది. ప్రయాణ సహాయం మొదలుపెట్టడానికి జవాబు ఇవ్వండి.
Body (en): Your flight {{1}} is today. Reply to start your journey guide.

Sent at T−5 h if the parent has not opened the window herself (re-opens it before CHECK_IN at T−3 h).

## Sample values for review

journey_today {{1}} = QR4793

Notes: no media headers (audio cannot be a template header); no marketing language; Telugu text is plain spoken style.
