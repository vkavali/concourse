package ai.concourse.safety;

import ai.concourse.audio.AudioTranscoder;
import ai.concourse.card.CardRenderer;
import ai.concourse.card.SignCardRenderer;
import ai.concourse.common.PhoneHasher;
import ai.concourse.config.ConcourseProperties;
import ai.concourse.evidence.EvidenceReconciler;
import ai.concourse.evidence.SignObservation;
import ai.concourse.itinerary.Airports;
import ai.concourse.journey.ContactEntity;
import ai.concourse.journey.ContactService;
import ai.concourse.journey.EventLog;
import ai.concourse.journey.FlightSegmentEntity;
import ai.concourse.journey.JourneyController;
import ai.concourse.journey.JourneyEntity;
import ai.concourse.journey.JourneyFacts;
import ai.concourse.journey.JourneyService;
import ai.concourse.journey.StageType;
import ai.concourse.spark.PromptLoader;
import ai.concourse.spark.SparkClient;
import ai.concourse.spark.SparkRequest;
import ai.concourse.stt.SttClient;
import ai.concourse.travel.TravelLinks;
import ai.concourse.tts.TtsClient;
import ai.concourse.voice.ParentMessenger;
import ai.concourse.voice.ParentTurn;
import ai.concourse.voice.ParentTurnService;
import ai.concourse.voice.TeluguPhrases;
import ai.concourse.whatsapp.MessagingChannel;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.mockito.ArgumentCaptor;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Runs {@code eval/safety/cases.yaml}: every adversarial case from docs/SAFETY.md, one dynamic test each, against the
 * real {@link SafetyPolicy}, {@link EvidenceReconciler} and {@link ParentTurnService} rules. The launch criterion is
 * that none of them lets an unsupported location or routing assertion through.
 */
class SafetySuiteTest {
    private static final JourneyFacts.Flight F1 = new JourneyFacts.Flight(1, "Qatar Airways", "QR4793", "HYD", "DOH", "2026-11-28 03:45", "2026-11-28 06:05", null, "B12", true);
    private static final JourneyFacts.Flight F2 = new JourneyFacts.Flight(2, "Qatar Airways", "QR729", "DOH", "DFW", "2026-11-28 08:40", "2026-11-28 15:50", "D", "C7", true);
    private static final JourneyFacts FACTS = new JourneyFacts("WAITING_AT_ORIGIN", "Lakshmi", List.of(F1, F2), "x", null, null);
    private final SafetyPolicy safety = new SafetyPolicy();

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> cases() throws Exception {
        Path file = Path.of("eval/safety/cases.yaml");
        try (InputStream in = Files.newInputStream(file)) {
            return (List<Map<String, Object>>) new Yaml().load(in);
        }
    }

    @TestFactory
    List<DynamicTest> adversarialCases() throws Exception {
        List<Map<String, Object>> cases = cases();
        assertTrue(cases.size() >= 30, "the suite needs at least 30 cases, has " + cases.size());
        List<DynamicTest> tests = new ArrayList<>();
        for (Map<String, Object> c : cases) {
            String id = String.valueOf(c.get("id"));
            String kind = String.valueOf(c.get("kind"));
            tests.add(DynamicTest.dynamicTest(kind + ": " + id, () -> {
                switch (kind) {
                    case "outbound" -> outbound(c);
                    case "sign" -> sign(c);
                    case "turn" -> turn(c);
                    default -> fail("unknown case kind " + kind);
                }
            }));
        }
        return tests;
    }

    // ---- outbound text → SafetyPolicy -----------------------------------------------------------------------

    private void outbound(Map<String, Object> c) {
        String text = String.valueOf(c.get("text"));
        String arrow = c.get("arrow") == null ? null : String.valueOf(c.get("arrow"));
        JourneyFacts facts = new JourneyFacts(FACTS.status(), FACTS.travelerFirstName(), FACTS.flights(), FACTS.nextVerifiedStep(), null, arrow);
        SafetyPolicy.Result r = safety.check(text, facts);
        if ("pass".equals(c.get("expect"))) {
            assertTrue(r.ok(), "expected to pass but got " + r.violations());
            assertEquals(text, r.text());
        } else {
            assertFalse(r.ok(), "expected the safe fallback for: " + text);
            assertEquals(SafetyPolicy.FALLBACK_TE, r.text());
        }
    }

    // ---- photo observation → EvidenceReconciler ----------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private void sign(Map<String, Object> c) {
        StageType stage = StageType.valueOf(String.valueOf(c.get("stage")));
        Map<String, Object> o = (Map<String, Object>) c.get("observation");
        Map<String, Object> expect = (Map<String, Object>) c.get("expect");
        JourneyFacts.Flight flight = stage.name().startsWith("TRANSFER") || stage == StageType.US_ARRIVAL ? F2 : F1;
        if (Boolean.TRUE.equals(c.get("noGate"))) {
            flight = new JourneyFacts.Flight(flight.seq(), flight.airline(), flight.flight(), flight.from(), flight.to(), flight.departureLocal(), flight.arrivalLocal(), flight.terminal(), null, false);
        }
        SignObservation seen = new SignObservation(Boolean.TRUE.equals(o.get("sign")), (List<String>) o.get("text"), (List<String>) o.get("gates"),
                o.get("arrow") == null ? null : String.valueOf(o.get("arrow")), ((Number) o.get("confidence")).doubleValue(), null);
        EvidenceReconciler.Verdict v = EvidenceReconciler.reconcile(seen, EvidenceReconciler.targetFor(stage, flight));
        assertEquals(EvidenceReconciler.Kind.valueOf(String.valueOf(expect.get("verdict"))), v.kind(), "verdict for " + v.telugu());
        if (expect.containsKey("arrow")) {
            Object want = expect.get("arrow");
            assertEquals(want == null ? null : String.valueOf(want), v.kind() == EvidenceReconciler.Kind.MATCH ? v.arrow() : null, "arrow");
        }
        for (String s : (List<String>) expect.getOrDefault("contains", List.of())) assertTrue(v.telugu().contains(s), "should contain '" + s + "': " + v.telugu());
        for (String s : (List<String>) expect.getOrDefault("notContains", List.of())) assertFalse(v.telugu().contains(s), "must not contain '" + s + "': " + v.telugu());
        List<JourneyFacts.Flight> flights = flight == F1 ? List.of(flight, F2) : List.of(F1, flight);
        JourneyFacts facts = new JourneyFacts(stage.name(), "Lakshmi", flights, "x", null, v.kind() == EvidenceReconciler.Kind.MATCH ? v.arrow() : null);
        SafetyPolicy.Result r = safety.check(v.telugu(), facts);
        if ("pass".equals(expect.get("safety"))) assertTrue(r.ok(), "the templated reply must pass the policy: " + r.violations() + " / " + v.telugu());
    }

    // ---- model interpretation → ParentTurnService rules --------------------------------------------------------

    @SuppressWarnings("unchecked")
    private void turn(Map<String, Object> c) {
        Map<String, Object> i = (Map<String, Object>) c.get("interpretation");
        Map<String, Object> expect = (Map<String, Object>) c.get("expect");
        String reply = String.valueOf(i.get("reply"));
        ParentTurn turn = new ParentTurn(String.valueOf(i.get("intent")), i.get("entity") == null ? null : String.valueOf(i.get("entity")),
                ((Number) i.get("confidence")).doubleValue(), "none", reply, "gloss");

        // the real service with everything around it mocked
        String parent = "919876543210", child = "17165550100";
        MessagingChannel channel = mock(MessagingChannel.class);
        SttClient stt = mock(SttClient.class);
        SparkClient spark = mock(SparkClient.class);
        TtsClient tts = mock(TtsClient.class);
        JourneyService journeys = mock(JourneyService.class);
        JourneyController controller = mock(JourneyController.class);
        ContactService contacts = mock(ContactService.class);
        CardRenderer cards = mock(CardRenderer.class);
        ObjectMapper mapper = new ObjectMapper();
        PhoneHasher hasher = new PhoneHasher(new ConcourseProperties("http://x", "a", "salt", null, null, null, null, null));
        ParentMessenger messenger = new ParentMessenger(channel, tts, safety, cards, new SignCardRenderer(), contacts, journeys, new Airports(), new TravelLinks("salt", "http://x"));
        ParentTurnService service = new ParentTurnService(channel, mock(AudioTranscoder.class), stt, spark, new PromptLoader(mapper), journeys, controller,
                messenger, hasher, mock(EventLog.class), mapper);
        JourneyEntity journey = new JourneyEntity();
        journey.setId(UUID.randomUUID());
        journey.setStatus("WAITING_AT_ORIGIN");
        journey.setChildPhoneHash(hasher.hash(child));
        journey.setParentPhoneHash(hasher.hash(parent));
        journey.setConsentAt(OffsetDateTime.now());
        List<FlightSegmentEntity> segs = new ArrayList<>();
        for (JourneyFacts.Flight f : List.of(F1, F2)) {
            FlightSegmentEntity s = new FlightSegmentEntity();
            s.setSeq(f.seq()); s.setAirline(f.airline()); s.setFlightNumber(f.flight()); s.setOrigin(f.from()); s.setDestination(f.to());
            s.setScheduledDeparture(OffsetDateTime.of(2026, 11, 28, 3, 45, 0, 0, ZoneOffset.ofHoursMinutes(5, 30)));
            s.setTerminal(f.terminal()); s.setGate(f.gate()); s.setLastVerifiedAt(OffsetDateTime.now());
            segs.add(s);
        }
        when(journeys.segments(journey.getId())).thenReturn(segs);
        ContactEntity p = new ContactEntity(); p.setE164(parent);
        ContactEntity ch = new ContactEntity(); ch.setE164(child);
        when(contacts.findByHash(hasher.hash(parent))).thenReturn(Optional.of(p));
        when(contacts.findByHash(hasher.hash(child))).thenReturn(Optional.of(ch));
        when(cards.gateCard(any())).thenReturn(new byte[]{1});
        when(controller.current(any())).thenReturn(Optional.empty());
        when(tts.isConfigured()).thenReturn(false);
        when(stt.transcribeWav(any(), any())).thenReturn(new SttClient.Transcript("s", "…", 1000));
        when(spark.completeJson(any(SparkRequest.class), eq(ParentTurn.class), any(ObjectMapper.class))).thenReturn(turn);

        ParentTurnService.Reply r = service.handleAudio(journey, new byte[100], "suite");
        JourneyFacts facts = messenger.facts(journey);
        String hears = String.valueOf(expect.get("hears"));
        switch (hears) {
            case "template" -> {
                assertEquals(TeluguPhrases.gateAnswer(facts), r.telugu());
                verify(channel).sendText(parent, TeluguPhrases.gateAnswer(facts));
                verify(channel, never()).sendText(parent, reply);
            }
            case "fallback" -> {
                assertEquals(SafetyPolicy.FALLBACK_TE, r.telugu());
                verify(channel).sendText(parent, SafetyPolicy.FALLBACK_TE);
                verify(channel).sendText(eq(child), org.mockito.ArgumentMatchers.contains("safety fallback"));
            }
            case "clarify" -> {
                assertEquals("clarify", r.outcome());
                verify(channel).sendButtons(eq(parent), eq(TeluguPhrases.CLARIFY), any());
                verify(channel, never()).sendText(eq(parent), any());
                verify(controller, never()).recordConsent(any(), any());
                verify(controller, never()).acknowledge(any(), any());
                verify(controller, never()).markHere(any(), any());
            }
            case "model" -> {
                assertEquals(reply, r.telugu());
                verify(channel).sendText(parent, reply);
            }
            default -> fail("unknown expectation " + hears);
        }
        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(channel, atLeast(0)).sendText(eq(parent), sent.capture());
        for (String s : sent.getAllValues()) {
            for (String banned : (List<String>) expect.getOrDefault("notContains", List.of())) assertFalse(s.contains(banned), "must not say '" + banned + "': " + s);
            assertTrue(safety.check(s, facts).ok() || s.equals(SafetyPolicy.FALLBACK_TE), "everything she hears passes the policy: " + s);
        }
        for (String s : (List<String>) expect.getOrDefault("contains", List.of())) assertTrue(r.telugu().contains(s), "should contain '" + s + "': " + r.telugu());
    }
}
