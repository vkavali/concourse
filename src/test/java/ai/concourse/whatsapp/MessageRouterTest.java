package ai.concourse.whatsapp;

import ai.concourse.common.PhoneHasher;
import ai.concourse.config.ConcourseProperties;
import ai.concourse.itinerary.TicketIngestService;
import ai.concourse.journey.ContactEntity;
import ai.concourse.journey.ContactService;
import ai.concourse.journey.JourneyService;
import ai.concourse.stt.SttClient;
import ai.concourse.voice.VoiceNoteService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MessageRouterTest {
    private final WhatsAppClient whatsApp = mock(WhatsAppClient.class);
    private final ContactService contacts = mock(ContactService.class);
    private final TicketIngestService tickets = mock(TicketIngestService.class);
    private final VoiceNoteService voiceNotes = mock(VoiceNoteService.class);
    private final JourneyService journeys = mock(JourneyService.class);
    private final PhoneHasher hasher = new PhoneHasher(new ConcourseProperties("http://x", "a", "salt", null, null, null, null, null));
    private MessageRouter router;

    private static InboundMessage msg(InboundMessage.Type type, String text, String mediaId, String mime, String buttonId) {
        return new InboundMessage("wamid.1", "17165550100", type, text, mediaId, mime, buttonId, 1700000000L);
    }

    @BeforeEach
    void setUp() {
        router = new MessageRouter(whatsApp, contacts, tickets, voiceNotes, journeys, hasher);
        when(contacts.roleOf(any())).thenReturn(ContactEntity.Role.CHILD);
    }

    @Test
    void childDocumentGoesToTicketIngest() {
        router.handle(msg(InboundMessage.Type.DOCUMENT, "ticket.pdf", "m1", "application/pdf", null));
        verify(tickets).ingest("17165550100", "m1", "application/pdf", "ticket.pdf");
    }

    @Test
    void parentImageIsNotATicket() {
        when(contacts.roleOf("17165550100")).thenReturn(ContactEntity.Role.PARENT);
        router.handle(msg(InboundMessage.Type.IMAGE, null, "m2", "image/jpeg", null));
        verify(tickets, never()).ingest(any(), any(), any(), any());
        verify(whatsApp).sendText(eq("17165550100"), contains("Photo received"));
    }

    @Test
    void voiceNoteIsTranscribedAndEchoed() {
        when(voiceNotes.transcribe("m3")).thenReturn(new SttClient.Transcript("s", "నా గేట్ ఎక్కడ", 2000));
        router.handle(msg(InboundMessage.Type.AUDIO, null, "m3", "audio/ogg; codecs=opus", null));
        verify(whatsApp).sendText("17165550100", "🎙 నా గేట్ ఎక్కడ");
    }

    @Test
    void confirmAndFixButtonsRoute() {
        router.handle(msg(InboundMessage.Type.BUTTON_REPLY, "Confirm", null, null, "confirm:abc"));
        verify(tickets).confirm("17165550100", "abc");
        router.handle(msg(InboundMessage.Type.BUTTON_REPLY, "Fix", null, null, "fix:abc"));
        verify(tickets).fix("17165550100", "abc");
    }

    @Test
    void statusWithoutJourneyExplains() {
        when(journeys.latestForChild(any())).thenReturn(Optional.empty());
        router.handle(msg(InboundMessage.Type.TEXT, "Status", null, null, null));
        verify(whatsApp).sendText(eq("17165550100"), contains("No journey yet"));
    }

    @Test
    void failuresAreReportedNotSwallowed() {
        when(voiceNotes.transcribe("m4")).thenThrow(new RuntimeException("stt down"));
        router.handle(msg(InboundMessage.Type.AUDIO, null, "m4", "audio/ogg", null));
        verify(whatsApp).sendText(eq("17165550100"), contains("Something went wrong"));
    }
}
