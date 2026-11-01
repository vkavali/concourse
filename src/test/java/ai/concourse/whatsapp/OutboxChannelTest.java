package ai.concourse.whatsapp;

import ai.concourse.journey.ContactEntity;
import ai.concourse.journey.ContactService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OutboxChannelTest {
    private final WhatsAppClient delegate = mock(WhatsAppClient.class);
    private final ContactService contacts = mock(ContactService.class);
    private final OutboxChannel channel = new OutboxChannel(delegate, contacts);

    @Test
    void recordsByRoleAndDelegates() {
        when(delegate.isConfigured()).thenReturn(true);
        when(contacts.roleOf("919876543210")).thenReturn(ContactEntity.Role.PARENT);
        when(contacts.roleOf("17165550100")).thenReturn(ContactEntity.Role.CHILD);
        channel.sendVoiceNote("919876543210", new byte[2048]);
        channel.sendButtons("919876543210", "గేట్ B12", List.of(new WhatsAppClient.Button("p:done", "అయ్యింది ✓")));
        channel.sendText("17165550100", "Sent stage 2 CHECK_IN");
        verify(delegate).sendVoiceNote("919876543210", new byte[2048]);
        verify(delegate).sendText("17165550100", "Sent stage 2 CHECK_IN");

        List<OutboxChannel.Entry> all = channel.since(0);
        assertEquals(3, all.size());
        assertEquals("parent", all.get(0).role()); assertEquals("voice", all.get(0).kind()); assertEquals(2048, all.get(0).bytes());
        assertEquals(List.of("అయ్యింది ✓"), all.get(1).buttons());
        assertEquals("child", all.get(2).role());
        assertEquals(1, channel.since(2).size(), "since is exclusive");
        assertTrue(all.stream().noneMatch(e -> e.text().contains("9198")), "numbers never enter the outbox");
    }

    @Test
    void withoutWhatsAppNothingLeavesButEverythingIsRecordedWithItsMedia() {
        when(delegate.isConfigured()).thenReturn(false);
        when(contacts.roleOf("919876543210")).thenReturn(ContactEntity.Role.PARENT);
        byte[] ogg = {'O', 'g', 'g', 'S', 1, 2, 3};
        byte[] png = {(byte) 0x89, 'P', 'N', 'G'};
        String id = channel.sendVoiceNote("919876543210", ogg);
        channel.sendImage("919876543210", png, "gate card");
        channel.sendLinkButton("919876543210", "open", "తెరవండి", "https://example.test/travel.html#x");

        assertTrue(id.startsWith("outbox-"));
        verify(delegate, never()).sendVoiceNote(any(), any(byte[].class));
        verify(delegate, never()).sendImage(any(), any(byte[].class), any());
        verify(delegate, never()).sendLinkButton(any(), any(), any(), any());
        assertFalse(channel.delivering());

        List<OutboxChannel.Entry> all = channel.since(0);
        assertEquals("audio/ogg", all.get(0).mediaType());
        assertArrayEquals(ogg, channel.media(all.get(0).seq()).orElseThrow().bytes());
        assertEquals("image/png", all.get(1).mediaType());
        assertTrue(all.get(2).text().contains("https://example.test/travel.html#x"), "the travel link is readable in the simulator");
        assertTrue(channel.media(999).isEmpty());
    }
}
