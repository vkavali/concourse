package ai.concourse.voice;

import ai.concourse.journey.JourneyFacts;
import ai.concourse.journey.StageType;
import ai.concourse.safety.SafetyPolicy;
import ai.concourse.whatsapp.WhatsAppClient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StageTextTest {
    private static final JourneyFacts FACTS = new JourneyFacts("PRE_TRIP", "Lakshmi", List.of(
            new JourneyFacts.Flight(1, "Qatar Airways", "QR4793", "HYD", "DOH", "2026-11-28 03:45", "2026-11-28 06:05", null, "B12", true),
            new JourneyFacts.Flight(2, "Qatar Airways", "QR729", "DOH", "DFW", "2026-11-28 08:40", "2026-11-28 15:50", "D", "C7", true)),
            "x", null, null);
    private static final JourneyFacts NO_GATES = new JourneyFacts("PRE_TRIP", null, List.of(
            new JourneyFacts.Flight(1, "Air India", "AI173", "DEL", "SFO", "2026-06-06 00:05", null, "3", null, false)), "x", null, null);

    @Test
    void everyStageInstructionPassesTheSafetyPolicy() {
        SafetyPolicy safety = new SafetyPolicy();
        for (StageType t : StageType.values()) {
            for (int seg = 1; seg <= 2; seg++) {
                String text = StageText.instruction(t, FACTS, seg, seg == 1 ? "HYD" : "DOH");
                SafetyPolicy.Result r = safety.check(text, FACTS);
                assertTrue(r.ok(), t + " seg " + seg + ": " + r.violations() + "\n" + text);
                assertFalse(text.contains("మీరు ") && text.contains("ఉన్నారు"), t + " must not claim where she is");
            }
            SafetyPolicy.Result r = safety.check(StageText.instruction(t, NO_GATES, 1, "DEL"), NO_GATES);
            assertTrue(r.ok(), t + " (no gate): " + r.violations());
            assertTrue(StageText.buttons(t).size() <= 3, t + " offers at most three buttons");
            assertFalse(StageText.english(t, FACTS, 1).isBlank());
        }
    }

    @Test
    void gateStagesSayTheVerifiedGateTwiceAndNameTheAirline() {
        String waiting = StageText.instruction(StageType.WAITING_AT_ORIGIN, FACTS, 1, "HYD");
        assertTrue(waiting.startsWith("మీ ఖతార్ ఎయిర్‌వేస్ విమానం గేట్ B12."), waiting);
        assertTrue(waiting.contains("మళ్ళీ చెబుతున్నాను: గేట్ B12."));
        String transfer = StageText.instruction(StageType.TRANSFER_FIND_GATE, FACTS, 2, "DOH");
        assertTrue(transfer.contains("గేట్ C7"), transfer);
        assertFalse(transfer.contains("B12"), "the transfer stage speaks of the second flight only");
        String unknown = StageText.instruction(StageType.WAITING_AT_ORIGIN, NO_GATES, 1, "DEL");
        assertTrue(unknown.contains("గేట్ ఇంకా చెప్పలేదు"), unknown);
        assertTrue(StageText.instruction(StageType.IN_FLIGHT, FACTS, 1, "HYD").contains("దోహాలో దిగాక"));
    }

    @Test
    void introAsksForConsentAndButtonsMatchTheStage() {
        String intro = StageText.intro(FACTS);
        assertTrue(intro.contains("Lakshmi గారు"));
        assertTrue(intro.contains("నా ప్రయాణం మొదలు"));
        List<WhatsAppClient.Button> pre = StageText.buttons(StageType.PRE_TRIP);
        assertEquals(StageText.BTN_START, pre.get(0).id());
        assertEquals(StageText.BTN_DONE, StageText.buttons(StageType.CHECK_IN).get(0).id());
        assertEquals(StageText.BTN_HERE, StageText.buttons(StageType.FAMILY_HANDOFF).get(0).id());
        assertTrue(StageText.buttons(StageType.IN_FLIGHT).isEmpty());
        assertTrue(StageText.DONE_TITLE.length() <= 20 && StageText.HERE_TITLE.length() <= 20 && StageText.START_TITLE.length() <= 20, "WhatsApp button titles are 20 chars max");
    }
}
