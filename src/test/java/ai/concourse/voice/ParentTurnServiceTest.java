package ai.concourse.voice;

import ai.concourse.audio.AudioTranscoder;
import ai.concourse.card.CardRenderer;
import ai.concourse.common.PhoneHasher;
import ai.concourse.config.ConcourseProperties;
import ai.concourse.itinerary.Airports;
import ai.concourse.journey.ContactEntity;
import ai.concourse.journey.ContactService;
import ai.concourse.journey.EventLog;
import ai.concourse.journey.FlightSegmentEntity;
import ai.concourse.journey.JourneyEntity;
import ai.concourse.journey.JourneyService;
import ai.concourse.safety.SafetyPolicy;
import ai.concourse.spark.PromptLoader;
import ai.concourse.spark.SparkClient;
import ai.concourse.spark.SparkRequest;
import ai.concourse.stt.SttClient;
import ai.concourse.tts.TtsClient;
import ai.concourse.whatsapp.MessagingChannel;
import ai.concourse.whatsapp.WhatsAppClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ParentTurnServiceTest {
    private static final String PARENT = "919876543210";
    private static final String CHILD = "17165550100";

    private final MessagingChannel channel = mock(MessagingChannel.class);
    private final AudioTranscoder transcoder = mock(AudioTranscoder.class);
    private final SttClient stt = mock(SttClient.class);
    private final SparkClient spark = mock(SparkClient.class);
    private final TtsClient tts = mock(TtsClient.class);
    private final JourneyService journeys = mock(JourneyService.class);
    private final ContactService contacts = mock(ContactService.class);
    private final EventLog eventLog = mock(EventLog.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final CardRenderer cards = mock(CardRenderer.class);
    private final PhoneHasher hasher = new PhoneHasher(new ConcourseProperties("http://x", "a", "salt", null, null, null, null, null));
    private ParentTurnService service;
    private JourneyEntity journey;

    @BeforeEach
    void setUp() {
        service = new ParentTurnService(channel, transcoder, stt, spark, new PromptLoader(mapper), tts, new SafetyPolicy(),
                journeys, contacts, new Airports(), hasher, eventLog, mapper, cards);
        when(cards.gateCard(any())).thenReturn(new byte[]{0x50, 0x4E, 0x47});
        journey = new JourneyEntity();
        journey.setId(UUID.randomUUID());
        journey.setStatus("PRE_TRIP");
        journey.setPassengerFirstName("Lakshmi");
        journey.setChildPhoneHash(hasher.hash(CHILD));
        journey.setParentPhoneHash(hasher.hash(PARENT));
        FlightSegmentEntity seg = new FlightSegmentEntity();
        seg.setSeq(1);
        seg.setFlightNumber("QR729");
        seg.setOrigin("DOH");
        seg.setDestination("DFW");
        seg.setScheduledDeparture(OffsetDateTime.of(2026, 11, 28, 8, 40, 0, 0, ZoneOffset.ofHours(3)));
        seg.setGate("B12");
        seg.setLastVerifiedAt(OffsetDateTime.now());
        when(journeys.latestForParent(hasher.hash(PARENT))).thenReturn(Optional.of(journey));
        when(journeys.segments(journey.getId())).thenReturn(List.of(seg));
        ContactEntity child = new ContactEntity();
        child.setE164(CHILD);
        when(contacts.findByHash(hasher.hash(CHILD))).thenReturn(Optional.of(child));
        ContactEntity parent = new ContactEntity();
        parent.setE164(PARENT);
        when(contacts.findByHash(hasher.hash(PARENT))).thenReturn(Optional.of(parent));
        when(channel.downloadMedia("m1")).thenReturn(new byte[]{1});
        when(transcoder.toWav16kMono(any())).thenReturn(new byte[100]);
        when(tts.isConfigured()).thenReturn(false);
    }

    private void sparkReturns(ParentTurn turn) {
        when(spark.completeJson(any(SparkRequest.class), eq(ParentTurn.class), any(ObjectMapper.class))).thenReturn(turn);
    }

    @Test
    void confidentTurnIsAnsweredFromTheModelReply() {
        when(stt.transcribeWav(any(), any())).thenReturn(new SttClient.Transcript("s", "నా గేట్ ఎక్కడ", 2000));
        sparkReturns(new ParentTurn("WHERE_IS_GATE", null, 0.95, "get_next_stage",
                "మీ విమానం QR729 గేట్ B12 నుండి బయలుదేరుతుంది.", "where is my gate"));

        service.handleVoiceNote(PARENT, "m1");

        verify(channel).sendText(PARENT, "మీ విమానం QR729 గేట్ B12 నుండి బయలుదేరుతుంది.");
        verify(channel).sendImage(eq(PARENT), any(), contains("B12"));
        verify(channel, never()).sendButtons(any(), any(), any());
        ArgumentCaptor<SparkRequest> req = ArgumentCaptor.forClass(SparkRequest.class);
        verify(spark).completeJson(req.capture(), eq(ParentTurn.class), any(ObjectMapper.class));
        assertTrue(req.getValue().user().contains("transcript: నా గేట్ ఎక్కడ"));
        assertTrue(req.getValue().user().contains("\"gate\":\"B12\""));
        assertTrue(req.getValue().system().contains("Never say where she is"));
        verify(eventLog).record(eq(journey.getId()), eq(EventLog.ACTOR_PARENT), eq("voice_turn"), any());
    }

    @Test
    void lowConfidenceAsksAgainWithButtons() {
        when(stt.transcribeWav(any(), any())).thenReturn(new SttClient.Transcript("s", "ఏదో", 1000));
        sparkReturns(new ParentTurn("UNCLEAR", null, 0.3, "none", "మళ్ళీ చెప్పండి", "unclear"));

        service.handleVoiceNote(PARENT, "m1");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<WhatsAppClient.Button>> buttons = ArgumentCaptor.forClass(List.class);
        verify(channel).sendButtons(eq(PARENT), eq(TeluguPhrases.CLARIFY), buttons.capture());
        assertEquals(3, buttons.getValue().size());
        assertEquals("p:gate", buttons.getValue().get(0).id());
    }

    @Test
    void emptyTranscriptAsksAgainWithoutCallingSpark() {
        when(stt.transcribeWav(any(), any())).thenReturn(new SttClient.Transcript("s", "", 500));
        service.handleVoiceNote(PARENT, "m1");
        verify(spark, never()).completeJson(any(SparkRequest.class), eq(ParentTurn.class), any(ObjectMapper.class));
        verify(channel).sendButtons(eq(PARENT), eq(TeluguPhrases.CLARIFY), any());
    }

    @Test
    void unverifiedGateInModelReplyBecomesSafeFallbackAndChildIsTold() {
        when(stt.transcribeWav(any(), any())).thenReturn(new SttClient.Transcript("s", "గేట్ C7 కి వెళ్ళాలా", 2000));
        sparkReturns(new ParentTurn("WHERE_IS_GATE", "C7", 0.9, "none", "అవును, గేట్ C7 కి వెళ్ళండి.", "should I go to C7"));

        service.handleVoiceNote(PARENT, "m1");

        verify(channel).sendText(PARENT, SafetyPolicy.FALLBACK_TE);
        verify(channel).sendText(eq(CHILD), contains("safety fallback"));
        verify(channel).sendText(eq(CHILD), contains("I do not know her physical location"));
    }

    @Test
    void familyHelpToolNotifiesTheChild() {
        when(stt.transcribeWav(any(), any())).thenReturn(new SttClient.Transcript("s", "నా కొడుకుకు చెప్పు", 2000));
        sparkReturns(new ParentTurn("CALL_SON", null, 0.92, "request_family_help", TeluguPhrases.SON_TOLD, "call my son"));

        service.handleVoiceNote(PARENT, "m1");

        verify(channel).sendText(eq(CHILD), contains("asked for help"));
        verify(channel).sendText(eq(CHILD), contains("Call her: +" + PARENT));
        verify(channel).sendText(PARENT, TeluguPhrases.SON_TOLD);
    }

    @Test
    void gateButtonSendsTheCardAndAnswersFromFactsOnly() {
        service.handleButton(PARENT, ParentTurnService.BTN_GATE);
        verify(channel).sendImage(eq(PARENT), eq(new byte[]{0x50, 0x4E, 0x47}), contains("గేట్ B12"));
        verify(channel).sendText(PARENT, TeluguPhrases.gateAnswer(service_facts()));
        verifyNoInteractions(spark);
    }

    private ai.concourse.journey.JourneyFacts service_facts() {
        return ai.concourse.journey.JourneyFacts.of(journey, journeys.segments(journey.getId()), new Airports());
    }

    @Test
    void helpButtonSendsStaffCardAndTellsChild() {
        service.handleButton(PARENT, ParentTurnService.BTN_HELP);
        verify(channel).sendText(eq(PARENT), contains("PLEASE HELP — I speak Telugu"));
        verify(channel).sendText(eq(PARENT), contains("Flight QR729 to DFW"));
        verify(channel).sendText(eq(PARENT), contains("Gate B12"));
        verify(channel).sendText(eq(CHILD), contains("pressed HELP"));
    }

    @Test
    void unknownParentGetsNotSetUp() {
        when(journeys.latestForParent(any())).thenReturn(Optional.empty());
        service.handleVoiceNote("910000000000", "m1");
        verify(channel).sendText("910000000000", TeluguPhrases.NOT_SET_UP);
        verifyNoInteractions(stt);
    }

    @Test
    void voiceNoteGoesOutWhenTtsIsConfigured() {
        when(tts.isConfigured()).thenReturn(true);
        when(tts.synthesizeOggOpus(any())).thenReturn(new byte[]{9, 9});
        service.handleButton(PARENT, ParentTurnService.BTN_SON);
        verify(tts).synthesizeOggOpus(TeluguSpeech.spokenForm(TeluguPhrases.SON_TOLD));
        verify(channel).sendVoiceNote(PARENT, new byte[]{9, 9});
        verify(channel).sendText(PARENT, TeluguPhrases.SON_TOLD);
    }
}
