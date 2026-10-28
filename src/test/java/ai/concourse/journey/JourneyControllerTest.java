package ai.concourse.journey;

import ai.concourse.itinerary.Itinerary;
import ai.concourse.itinerary.ItineraryValidator;
import ai.concourse.itinerary.ValidatedItinerary;
import ai.concourse.tts.TtsClient;
import ai.concourse.voice.StageText;
import ai.concourse.whatsapp.InboundMessageHandler;
import ai.concourse.whatsapp.WhatsAppClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The whole HYD→DOH→DFW journey in time-travel mode: every stage fires once, in order, with voice + card; a gate
 * change mid-journey rebuilds what is still planned and reaches both phones; "I'm here" completes it.
 */
@SpringBootTest
@ActiveProfiles("test")
class JourneyControllerTest {
    // Unique numbers per test method: the in-memory DB is shared by every Spring test in the JVM, and a leftover
    // journey for the same number would fire its own stages into this test's verifications.
    private String PARENT;
    private String CHILD;

    @Autowired JourneyController controller;
    @Autowired JourneyService journeys;
    @Autowired ItineraryValidator validator;
    @Autowired ContactService contacts;
    @Autowired StageRepository stageRepo;
    @Autowired OutboundJobRepository jobRepo;
    @Autowired EventRepository events;
    @Autowired ConcourseClock clock;
    @Autowired ai.concourse.common.PhoneHasher hasher;
    @Autowired JourneyRepository journeyRepo;
    @MockBean WhatsAppClient channel;   // the one MessagingChannel in the context
    @MockBean TtsClient tts;
    @MockBean InboundMessageHandler handler;

    private JourneyEntity journey;

    @BeforeEach
    void setUp() {
        clock.reset();
        journeyRepo.deleteAll(); // stages, jobs, segments and events cascade
        String suffix = String.valueOf(System.nanoTime() % 100_000_000L);
        PARENT = "9198" + ("00000000" + suffix).substring(suffix.length());
        CHILD = "1716" + ("00000000" + suffix).substring(suffix.length());
        when(tts.isConfigured()).thenReturn(false);
        contacts.register(CHILD, ContactEntity.Role.CHILD, "en");
        contacts.register(PARENT, ContactEntity.Role.PARENT, "te");
        Itinerary raw = new Itinerary(true, "e-ticket", "Lakshmi", null, List.of(
                new Itinerary.Segment("Qatar Airways", "QR4793", "HYD", "DOH", "2026-11-28T03:45", "2026-11-28T06:05", null, "B12", 1.0, ""),
                new Itinerary.Segment("Qatar Airways", "QR729", "DOH", "DFW", "2026-11-28T08:40", "2026-11-28T15:50", "D", null, 1.0, "")));
        ValidatedItinerary v = validator.validate(raw);
        assertFalse(v.hasErrors(), v.issues().toString());
        journey = journeys.createFromItinerary(hasher.hash(CHILD), v);
    }

    @AfterEach
    void tearDown() {
        clock.reset();
    }

    private JourneyEntity fresh() {
        return journeys.find(journey.getId()).orElseThrow();
    }

    private StageEntity stage(StageType type, int nth) {
        return controller.stages(journey.getId()).stream().filter(s -> s.stageType() == type).skip(nth - 1).findFirst().orElseThrow();
    }

    private StageEntity stage(StageType type) {
        return stage(type, 1);
    }

    private void travelToAndTick(StageEntity s) {
        clock.travelTo(s.getScheduledAt().toInstant());
        controller.tick();
    }

    @Test
    void fullSimulatedJourney() {
        // --- onboarding: confirm + register → plan + intro (nothing is pushed until she consents)
        journeys.confirmItinerary(journey.getId(), hasher.hash(CHILD));
        controller.onJourneyReady(journey.getId());
        verify(channel, never()).sendButtons(any(), any(), any());
        journeys.attachParent(hasher.hash(CHILD), hasher.hash(PARENT));
        controller.onJourneyReady(journey.getId());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<WhatsAppClient.Button>> buttons = ArgumentCaptor.forClass(List.class);
        verify(channel).sendButtons(eq(PARENT), contains("నా ప్రయాణం మొదలు"), buttons.capture());
        assertEquals(StageText.BTN_START, buttons.getValue().get(0).id());
        verify(channel).sendText(eq(CHILD), contains("Intro sent"));
        List<StageEntity> plan = controller.stages(journey.getId());
        assertEquals(17, plan.size());
        assertEquals(JourneyStateMachine.plan(2), plan.stream().map(StageEntity::stageType).toList());
        assertEquals(StageEntity.SENT, stage(StageType.PRE_TRIP).getStatus());
        assertEquals(15, jobRepo.findByJourneyIdAndStatus(journey.getId(), OutboundJobEntity.PENDING).size(), "one job per timed stage");
        controller.onJourneyReady(journey.getId());
        verify(channel, times(1)).sendButtons(eq(PARENT), contains("నా ప్రయాణం మొదలు"), any()); // idempotent

        // --- real time, nothing due
        controller.tick();
        verify(channel, never()).sendButtons(eq(PARENT), contains("కౌంటర్"), any());

        // --- CHECK_IN is due but she has not consented: held, child told once
        travelToAndTick(stage(StageType.CHECK_IN));
        controller.tick();
        verify(channel, never()).sendButtons(eq(PARENT), contains("కౌంటర్"), any());
        verify(channel, times(1)).sendText(eq(CHILD), contains("has not said 'start my trip'"));
        assertEquals("PRE_TRIP", fresh().getStatus());

        // --- consent: thanks + the due stage goes out immediately
        controller.recordConsent(fresh(), "button");
        verify(channel).sendText(PARENT, StageText.CONSENT_THANKS);
        verify(channel).sendButtons(eq(PARENT), contains("కౌంటర్"), buttons.capture());
        assertEquals(StageText.BTN_DONE, buttons.getValue().get(0).id());
        assertEquals("CHECK_IN", fresh().getStatus());
        assertEquals(StageEntity.SENT, stage(StageType.CHECK_IN).getStatus());
        verify(channel).sendText(eq(CHILD), contains("Sent stage 2 CHECK_IN"));

        // --- her "done": evidence, not a jump
        controller.acknowledge(fresh(), "button");
        assertEquals(StageEntity.ACKED, stage(StageType.CHECK_IN).getStatus());
        assertEquals("CHECK_IN", fresh().getStatus());
        verify(channel).sendText(PARENT, StageText.ACK_THANKS);

        // --- next stage on time
        travelToAndTick(stage(StageType.SECURITY));
        assertEquals("SECURITY", fresh().getStatus());
        verify(channel).sendButtons(eq(PARENT), contains("సెక్యూరిటీ"), any());

        // --- late by one stage: WAITING is skipped, BOARDING goes out with the gate card
        travelToAndTick(stage(StageType.BOARDING_ORIGIN));
        assertEquals(StageEntity.SKIPPED, stage(StageType.WAITING_AT_ORIGIN).getStatus());
        assertEquals("BOARDING_ORIGIN", fresh().getStatus());
        verify(channel).sendImage(eq(PARENT), any(byte[].class), contains("B12"));
        verify(channel).sendButtons(eq(PARENT), contains("గేట్ B12 దగ్గర బోర్డింగ్"), any());

        // --- verified gate change for the second flight: plan rebuilt, both phones told
        assertTrue(controller.applyGateChange(fresh(), 2, "C7", "demo operations event"));
        assertEquals("C7", journeys.segments(journey.getId()).get(1).getGate());
        verify(channel).sendImage(eq(PARENT), any(byte[].class), contains("C7"));
        verify(channel).sendText(eq(PARENT), contains("కొత్త గేట్ C7"));
        verify(channel).sendText(eq(CHILD), contains("gate C7"));
        List<StageEntity> rebuilt = controller.stages(journey.getId());
        assertEquals(17, rebuilt.size(), "same plan shape after a gate change");
        assertEquals(StageEntity.SKIPPED, stage(StageType.WAITING_AT_ORIGIN).getStatus(), "sent/acked/skipped rows survive a rebuild");
        assertEquals(StageEntity.PLANNED, stage(StageType.TRANSFER_FIND_GATE).getStatus());

        // --- transfer: the new gate is what she hears
        travelToAndTick(stage(StageType.TRANSFER_FIND_GATE));
        assertEquals("TRANSFER_FIND_GATE", fresh().getStatus());
        assertEquals(StageEntity.SKIPPED, stage(StageType.IN_FLIGHT, 1).getStatus());
        assertEquals(StageEntity.SKIPPED, stage(StageType.TRANSFER_ARRIVAL).getStatus());
        verify(channel).sendButtons(eq(PARENT), contains("గేట్ C7. మళ్ళీ చెబుతున్నాను: గేట్ C7."), any());

        // --- landing side, then her "I'm here"
        travelToAndTick(stage(StageType.FAMILY_HANDOFF));
        assertEquals("FAMILY_HANDOFF", fresh().getStatus());
        verify(channel).sendButtons(eq(PARENT), contains("నేను వచ్చాను"), buttons.capture());
        assertEquals(StageText.BTN_HERE, buttons.getValue().get(0).id());
        assertTrue(controller.markHere(fresh(), "button"));
        assertEquals("COMPLETE", fresh().getStatus());
        assertEquals(StageEntity.ACKED, stage(StageType.COMPLETE).getStatus());
        verify(channel).sendText(eq(CHILD), contains("Journey complete"));
        verify(channel).sendText(eq(PARENT), contains("మీ ప్రయాణం పూర్తయింది"));
        assertTrue(journeys.latestForParent(hasher.hash(PARENT)).isEmpty(), "a complete journey no longer answers the parent");

        // --- every stage was sent at most once, in plan order
        List<EventEntity> log = events.findByJourneyIdOrderByTs(journey.getId());
        List<String> sentStages = log.stream().filter(e -> e.getType().equals("stage_sent"))
                .map(e -> e.getSanitizedPayload().replaceAll(".*\"stage\":\"([A-Z_]+)\".*", "$1")).toList();
        assertEquals(List.of("CHECK_IN", "SECURITY", "BOARDING_ORIGIN", "TRANSFER_FIND_GATE", "FAMILY_HANDOFF"), sentStages);
        Map<String, Long> perStage = sentStages.stream().collect(Collectors.groupingBy(x -> x, Collectors.counting()));
        assertTrue(perStage.values().stream().allMatch(n -> n == 1), perStage.toString());
        assertTrue(jobRepo.findByJourneyIdAndStatus(journey.getId(), OutboundJobEntity.PENDING).isEmpty(), "no job left behind");
        verify(channel, never()).sendTemplate(eq(PARENT), any(), any(), any());
    }

    @Test
    void outsideTheWindowTheStageIsHeldUntilSheReplies() {
        journeys.confirmItinerary(journey.getId(), hasher.hash(CHILD));
        journeys.attachParent(hasher.hash(CHILD), hasher.hash(PARENT));
        controller.onJourneyReady(journey.getId());
        controller.recordConsent(fresh(), "voice");
        JourneyEntity j = fresh();
        j.setLastParentMessageAt(OffsetDateTime.now().minusDays(2));
        journeys.save(j);

        travelToAndTick(stage(StageType.CHECK_IN));

        verify(channel).sendTemplate(PARENT, JourneyController.TEMPLATE_NAME, "te", List.of());
        verify(channel, never()).sendButtons(eq(PARENT), contains("కౌంటర్"), any());
        assertEquals(JourneyController.HELD, stage(StageType.CHECK_IN).getStatus());
        verify(channel).sendText(eq(CHILD), contains("window is closed"));

        controller.onParentMessage(fresh()); // she replied with anything
        assertEquals(StageEntity.SENT, stage(StageType.CHECK_IN).getStatus());
        verify(channel).sendButtons(eq(PARENT), contains("కౌంటర్"), any());
        verify(channel, times(1)).sendTemplate(any(), any(), any(), any());
    }

    @Test
    void iAmHereBeforeTheExitIsOnlyAnAck() {
        journeys.confirmItinerary(journey.getId(), hasher.hash(CHILD));
        journeys.attachParent(hasher.hash(CHILD), hasher.hash(PARENT));
        controller.onJourneyReady(journey.getId());
        controller.recordConsent(fresh(), "voice");
        travelToAndTick(stage(StageType.SECURITY));
        assertFalse(controller.markHere(fresh(), "voice"));
        assertEquals("SECURITY", fresh().getStatus());
        verify(channel).sendText(eq(CHILD), contains("Not treating the journey as complete"));
    }
}
