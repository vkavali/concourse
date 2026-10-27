package ai.concourse.itinerary;

import ai.concourse.common.PhoneHasher;
import ai.concourse.config.ConcourseProperties;
import ai.concourse.journey.EventLog;
import ai.concourse.journey.JourneyEntity;
import ai.concourse.journey.JourneyService;
import ai.concourse.whatsapp.WhatsAppClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TicketIngestServiceTest {
    private final WhatsAppClient whatsApp = mock(WhatsAppClient.class);
    private final TicketExtractor extractor = mock(TicketExtractor.class);
    private final JourneyService journeys = mock(JourneyService.class);
    private final EventLog eventLog = mock(EventLog.class);
    private final PhoneHasher hasher = new PhoneHasher(new ConcourseProperties("http://x", "a", "salt", null, null, null, null, null));
    private TicketIngestService service;

    @BeforeEach
    void setUp() {
        ItineraryValidator validator = new ItineraryValidator(new Airports(), false, 60,
                Clock.fixed(Instant.parse("2026-11-01T00:00:00Z"), ZoneOffset.UTC));
        service = new TicketIngestService(whatsApp, extractor, validator, journeys, hasher, eventLog);
        when(extractor.supports("application/pdf")).thenReturn(true);
        when(extractor.supports("image/jpeg")).thenReturn(true);
        when(whatsApp.downloadMedia("m1")).thenReturn(new byte[]{1, 2, 3});
    }

    @Test
    void validTicketCreatesJourneyAndAsksForConfirmation() {
        Itinerary raw = new Itinerary(true, "e-ticket", "Lakshmi", null, List.of(
                new Itinerary.Segment("Qatar Airways", "QR4793", "HYD", "DOH", "2026-11-28T03:45", "2026-11-28T06:05", null, null, 1.0, "")));
        when(extractor.extract(any(), eq("application/pdf"), eq("ticket.pdf"))).thenReturn(raw);
        JourneyEntity j = new JourneyEntity();
        j.setId(UUID.randomUUID());
        when(journeys.createFromItinerary(eq(hasher.hash("17165550100")), any())).thenReturn(j);

        service.ingest("17165550100", "m1", "application/pdf", "ticket.pdf");

        verify(whatsApp).sendText("17165550100", "Reading the ticket…");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<WhatsAppClient.Button>> buttons = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(whatsApp).sendButtons(eq("17165550100"), body.capture(), buttons.capture());
        assertTrue(body.getValue().contains("QR4793  HYD → DOH"));
        assertEquals("confirm:" + j.getId(), buttons.getValue().get(0).id());
        assertEquals("fix:" + j.getId(), buttons.getValue().get(1).id());
        assertTrue(buttons.getValue().stream().allMatch(b -> b.title().length() <= 20));
    }

    @Test
    void rejectedDocumentExplainsWhy() {
        when(extractor.extract(any(), eq("image/jpeg"), any())).thenReturn(new Itinerary(false, "a hotel booking", null, null, List.of()));
        service.ingest("17165550100", "m1", "image/jpeg", null);
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(whatsApp, times(2)).sendText(eq("17165550100"), text.capture());
        assertTrue(text.getAllValues().get(1).contains("hotel booking"));
        verify(journeys, never()).createFromItinerary(any(), any());
        verify(whatsApp, never()).sendButtons(any(), any(), any());
    }

    @Test
    void unsupportedMediaIsRefusedWithoutDownloading() {
        service.ingest("17165550100", "m9", "audio/ogg", null);
        verify(whatsApp).sendText(eq("17165550100"), contains("PDF or a photo"));
        verify(whatsApp, never()).downloadMedia(any());
    }

    @Test
    void confirmAndFixRouteToJourneyService() {
        UUID id = UUID.randomUUID();
        when(journeys.confirmItinerary(id, hasher.hash("17165550100"))).thenReturn(Optional.of(new JourneyEntity()));
        service.confirm("17165550100", id.toString());
        verify(whatsApp).sendText(eq("17165550100"), startsWith("Confirmed."));

        when(journeys.confirmItinerary(any(), any())).thenReturn(Optional.empty());
        service.confirm("17165550100", UUID.randomUUID().toString());
        verify(whatsApp).sendText(eq("17165550100"), contains("could not find"));

        service.fix("17165550100", id.toString());
        verify(eventLog).record(eq(id), eq(EventLog.ACTOR_CHILD), eq("itinerary_fix_requested"), any());
    }
}
