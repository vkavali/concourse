package ai.concourse.voice;

import ai.concourse.audio.AudioTranscoder;
import ai.concourse.card.CardRenderer;
import ai.concourse.card.SignCardRenderer;
import ai.concourse.common.PhoneHasher;
import ai.concourse.config.ConcourseProperties;
import ai.concourse.itinerary.Airports;
import ai.concourse.journey.ContactEntity;
import ai.concourse.journey.ContactService;
import ai.concourse.journey.EventLog;
import ai.concourse.journey.FlightSegmentEntity;
import ai.concourse.journey.JourneyController;
import ai.concourse.journey.JourneyEntity;
import ai.concourse.journey.JourneyFacts;
import ai.concourse.journey.JourneyService;
import ai.concourse.journey.StageEntity;
import ai.concourse.journey.StageType;
import ai.concourse.safety.SafetyPolicy;
import ai.concourse.spark.PromptLoader;
import ai.concourse.spark.SparkClient;
import ai.concourse.spark.SparkRequest;
import ai.concourse.stt.SttClient;
import ai.concourse.travel.TravelLinks;
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
    private final JourneyController controller = mock(JourneyController.class);
    private final ContactService contacts = mock(ContactService.class);
    private final EventLog eventLog = mock(EventLog.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final CardRenderer cards = mock(CardRenderer.class);
    private final PhoneHasher hasher = new PhoneHasher(new ConcourseProperties("http://x", "a", "salt", null, null, null, null, null));
    private ParentMessenger messenger;
    private ParentTurnService service;
    private JourneyEntity journey;

    @BeforeEach
    void setUp() {
        messenger = new ParentMessenger(channel, tts, new SafetyPolicy(), cards, new SignCardRenderer(), contacts, journeys, new Airports(), new TravelLinks("salt", "http://x"));
        service = new ParentTurnService(channel, transcoder, stt, spark, new PromptLoader(mapper), journeys, controller,
                messenger, hasher, eventLog, mapper);
        when(cards.gateCard(any())).thenReturn(new byte[]{0x50, 0x4E, 0x47});
        journey = new JourneyEntity();
        journey.setId(UUID.randomUUID());
        journey.setStatus("PRE_TRIP");
        journey.setPassengerFirstName("Lakshmi");
        journey.setChildPhoneHash(hasher.hash(CHILD));
        journey.setParentPhoneHash(hasher.hash(PARENT));
        journey.setConsentAt(OffsetDateTime.now());
        FlightSegmentEntity seg = new FlightSegmentEntity();
        seg.setSeq(1);
        seg.setAirline("Qatar Airways");
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
        when(controller.current(any())).thenReturn(Optional.empty());
    }

    private void sparkReturns(ParentTurn turn) {
        when(spark.completeJson(any(SparkRequest.class), eq(ParentTurn.class), any(ObjectMapper.class))).thenReturn(turn);
    }

    private JourneyFacts facts() { return messenger.facts(journey); }

    @Test
    void gateQuestionIsAnsweredFromVerifiedFactsNotTheModel() {
        when(stt.transcribeWav(any(), any())).thenReturn(new SttClient.Transcript("s", "నా గేట్ ఎక్కడ", 2000));
        sparkReturns(new ParentTurn("WHERE_IS_GATE", null, 0.95, "get_next_stage",
                "మీ విమానం QR729 గేట్ B12 నుండి బయలుదేరుతుంది.", "where is my gate"));

        service.handleVoiceNote(PARENT, "m1");

        String expected = TeluguPhrases.gateAnswer(facts());
        assertTrue(expected.startsWith("మీ ఖతార్ ఎయిర్‌వేస్ విమానం గేట్ B12 నుంచి"), expected);
        verify(channel).sendText(PARENT, expected);
        verify(channel, never()).sendText(PARENT, "మీ విమానం QR729 గేట్ B12 నుండి బయలుదేరుతుంది.");
        verify(channel).sendImage(eq(PARENT), any(), contains("B12"));
        verify(channel, never()).sendButtons(any(), any(), any());
        verify(controller).onParentMessage(journey);
        ArgumentCaptor<SparkRequest> req = ArgumentCaptor.forClass(SparkRequest.class);
        verify(spark).completeJson(req.capture(), eq(ParentTurn.class), any(ObjectMapper.class));
        assertTrue(req.getValue().user().contains("transcript: నా గేట్ ఎక్కడ"));
        assertTrue(req.getValue().user().contains("\"gate\":\"B12\""));
        assertTrue(req.getValue().user().contains("\"airline\":\"Qatar Airways\""));
        assertTrue(req.getValue().system().contains("Never say where she is"));
        verify(eventLog).record(eq(journey.getId()), eq(EventLog.ACTOR_PARENT), eq("voice_turn"), any());
    }

    @Test
    void otherIntentsAreAnsweredFromTheModelReply() {
        when(stt.transcribeWav(any(), any())).thenReturn(new SttClient.Transcript("s", "నా బ్యాగ్ రాలేదు", 2000));
        sparkReturns(new ParentTurn("BAGGAGE_PROBLEM", null, 0.9, "none",
                "బ్యాగేజ్ కౌంటర్ దగ్గర ఈ కార్డును సిబ్బందికి చూపించండి.", "my bag did not come"));

        service.handleVoiceNote(PARENT, "m1");

        verify(channel).sendText(PARENT, "బ్యాగేజ్ కౌంటర్ దగ్గర ఈ కార్డును సిబ్బందికి చూపించండి.");
        verify(channel, never()).sendImage(any(), any(), any());
    }

    @Test
    void journeyIntentsGoToTheController() {
        when(stt.transcribeWav(any(), any())).thenReturn(new SttClient.Transcript("s", "నా ప్రయాణం మొదలు", 2000));
        sparkReturns(new ParentTurn("START_TRIP", null, 0.93, "none", "సరే", "start my trip"));
        service.handleVoiceNote(PARENT, "m1");
        verify(controller).recordConsent(journey, "voice");

        sparkReturns(new ParentTurn("I_AM_HERE", null, 0.93, "none", "సరే", "I am here"));
        service.handleVoiceNote(PARENT, "m1");
        verify(controller).markHere(journey, "voice");

        sparkReturns(new ParentTurn("AT_COUNTER", null, 0.93, "acknowledge_stage", "సరే", "I am at the counter"));
        service.handleVoiceNote(PARENT, "m1");
        verify(controller).acknowledge(journey, "voice: I am at the counter");

        when(controller.whatNext(journey)).thenReturn(StageText.ACK_THANKS);
        sparkReturns(new ParentTurn("WHAT_NEXT", null, 0.93, "get_next_stage", "model text", "what next"));
        service.handleVoiceNote(PARENT, "m1");
        verify(channel).sendText(PARENT, StageText.ACK_THANKS);
        verify(channel, never()).sendText(PARENT, "model text");
    }

    @Test
    void theVoiceSaysTheAirlineNameInsteadOfSpellingTheFlightCode() {
        when(tts.isConfigured()).thenReturn(true);
        when(tts.synthesizeOggOpus(any())).thenReturn(new byte[]{9});
        service.handleButton(PARENT, ParentTurnService.BTN_GATE);
        ArgumentCaptor<String> spoken = ArgumentCaptor.forClass(String.class);
        verify(tts).synthesizeOggOpus(spoken.capture());
        assertTrue(spoken.getValue().startsWith("మీ ఖతార్ ఎయిర్‌వేస్ విమానం గేట్, బీ పన్నెండు, నుంచి"), spoken.getValue());
        assertFalse(spoken.getValue().contains("క్యూ ఆర్"), "flight code is not spelled when the airline is known");
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
        sparkReturns(new ParentTurn("OTHER", "C7", 0.9, "none", "అవును, గేట్ C7 కి వెళ్ళండి.", "should I go to C7"));

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
        verify(channel).sendText(PARENT, TeluguPhrases.gateAnswer(facts()));
        verifyNoInteractions(spark);
    }

    @Test
    void stageButtonsGoToTheController() {
        service.handleButton(PARENT, StageText.BTN_START);
        verify(controller).recordConsent(journey, "button");
        service.handleButton(PARENT, StageText.BTN_DONE);
        verify(controller).acknowledge(journey, "button");
        service.handleButton(PARENT, StageText.BTN_HERE);
        verify(controller).markHere(journey, "button");
        verifyNoInteractions(spark);
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
    void whatNextDuringAGateStageBringsTheCard() {
        StageEntity current = new StageEntity();
        current.setType(StageType.WAITING_AT_ORIGIN.name());
        current.setSegmentSeq(1);
        when(controller.current(journey)).thenReturn(Optional.of(current));
        when(controller.whatNext(journey)).thenReturn("గేట్ B12 కి వెళ్ళండి.");
        when(stt.transcribeWav(any(), any())).thenReturn(new SttClient.Transcript("s", "ఇప్పుడు ఏం చేయాలి", 2000));
        sparkReturns(new ParentTurn("WHAT_NEXT", null, 0.9, "get_next_stage", "x", "what now"));

        service.handleVoiceNote(PARENT, "m1");

        verify(channel).sendImage(eq(PARENT), any(), contains("B12"));
        verify(channel).sendButtons(eq(PARENT), eq("గేట్ B12 కి వెళ్ళండి."), any());
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
