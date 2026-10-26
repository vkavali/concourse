package ai.concourse.whatsapp;

/** Day 0: log only. Day 1+: MessageRouter routes to TicketIngestService / VoiceNoteService / SignEvidenceService. */
public interface InboundMessageHandler {
    void handle(InboundMessage message);
}
