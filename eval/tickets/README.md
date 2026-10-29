# Ticket → itinerary evals

Collect 20 varied tickets / boarding passes / itinerary emails (redacted: no passport numbers, no payment data) in
`~/concourse-data/tickets/` (outside the repo). Run each through `TicketExtractor` + `ItineraryValidator` and record in
`RESULTS.md`: format, segments found, critical fields (flight number, origin, destination, departure, arrival) correct y/n,
warnings raised. Critical fields must be 100% on supported formats; anything else gets listed as unsupported.

Runner: `./gradlew bootRun --args='--spring.profiles.active=eval-tickets'` (Day 2) — until then use the WhatsApp path.

`sample-itinerary-HYD-DOH-DFW.pdf` is a synthetic, future-dated two-leg itinerary (fictional passenger) for the demo and for
rehearsing the journey engine; the stage plan needs future flight times, and real tickets never go in the repo.
