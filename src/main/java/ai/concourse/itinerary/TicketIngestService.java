package ai.concourse.itinerary;

import ai.concourse.common.PhoneHasher;
import ai.concourse.journey.EventLog;
import ai.concourse.journey.JourneyEntity;
import ai.concourse.journey.JourneyService;
import ai.concourse.whatsapp.MessagingChannel;
import ai.concourse.whatsapp.WhatsAppClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Child forwards a ticket on WhatsApp → download → Spark extraction → validation → journey rows → summary with
 * CONFIRM / FIX buttons. The ticket bytes live only in memory for the duration of this call.
 */
@Service
public class TicketIngestService {
    private static final Logger log = LoggerFactory.getLogger(TicketIngestService.class);
    public static final String BUTTON_CONFIRM = "confirm:";
    public static final String BUTTON_FIX = "fix:";

    private final MessagingChannel whatsApp;
    private final TicketExtractor extractor;
    private final ItineraryValidator validator;
    private final JourneyService journeys;
    private final PhoneHasher hasher;
    private final EventLog eventLog;

    public TicketIngestService(MessagingChannel whatsApp, TicketExtractor extractor, ItineraryValidator validator,
                               JourneyService journeys, PhoneHasher hasher, EventLog eventLog) {
        this.whatsApp = whatsApp;
        this.extractor = extractor;
        this.validator = validator;
        this.journeys = journeys;
        this.hasher = hasher;
        this.eventLog = eventLog;
    }

    public void ingest(String fromWaId, String mediaId, String mimeType, String filename) {
        if (!extractor.supports(mimeType)) {
            whatsApp.sendText(fromWaId, "Please send the ticket as a PDF or a photo (JPG/PNG).");
            return;
        }
        whatsApp.sendText(fromWaId, "Reading the ticket…");
        byte[] bytes = whatsApp.downloadMedia(mediaId);
        try {
            Itinerary raw = extractor.extract(bytes, mimeType, filename);
            ValidatedItinerary v = validator.validate(raw);
            if (v.hasErrors()) {
                eventLog.record(null, EventLog.ACTOR_CHILD, "ticket_rejected",
                        Map.of("errors", v.errors().stream().map(ValidatedItinerary.Issue::field).toList()));
                whatsApp.sendText(fromWaId, ItinerarySummary.errorsForChild(v));
                return;
            }
            JourneyEntity j = journeys.createFromItinerary(hasher.hash(fromWaId), v);
            whatsApp.sendButtons(fromWaId, ItinerarySummary.forChild(v), List.of(
                    new WhatsAppClient.Button(BUTTON_CONFIRM + j.getId(), "Confirm"),
                    new WhatsAppClient.Button(BUTTON_FIX + j.getId(), "Fix")));
        } finally {
            Arrays.fill(bytes, (byte) 0); // the ticket is never persisted; this just shortens its life in memory
        }
    }

    public void confirm(String fromWaId, String journeyId) {
        Optional<JourneyEntity> j = journeys.confirmItinerary(UUID.fromString(journeyId), hasher.hash(fromWaId));
        if (j.isEmpty()) {
            whatsApp.sendText(fromWaId, "I could not find that itinerary. Please send the ticket again.");
            return;
        }
        whatsApp.sendText(fromWaId, "Confirmed. Next: tell me the traveler's WhatsApp number so I can send her the first voice note.");
        log.info("journey {} confirmed", journeyId);
    }

    public void fix(String fromWaId, String journeyId) {
        whatsApp.sendText(fromWaId, "No problem. Send the e-ticket PDF again, or a clearer photo of the itinerary, and I will re-read it.");
        eventLog.record(UUID.fromString(journeyId), EventLog.ACTOR_CHILD, "itinerary_fix_requested", Map.of());
    }
}
