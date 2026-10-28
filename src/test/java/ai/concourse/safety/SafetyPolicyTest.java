package ai.concourse.safety;

import ai.concourse.journey.JourneyFacts;
import ai.concourse.voice.TeluguPhrases;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SafetyPolicyTest {
    private final SafetyPolicy policy = new SafetyPolicy();
    private final JourneyFacts facts = new JourneyFacts("PRE_TRIP", "Lakshmi", List.of(
            new JourneyFacts.Flight(1, "Qatar Airways", "QR729", "DOH", "DFW", "2026-11-28 08:40", "2026-11-28 15:50", "D", "B12", true)),
            "Flight QR729 …", null, null);
    private final JourneyFacts noGate = new JourneyFacts("PRE_TRIP", "Lakshmi", List.of(
            new JourneyFacts.Flight(1, "Qatar Airways", "QR729", "DOH", "DFW", "2026-11-28 08:40", null, null, null, false)),
            "Flight QR729 …", null, null);

    @Test
    void verifiedFactsPass() {
        assertTrue(policy.check("మీ విమానం QR729 గేట్ B12 నుండి బయలుదేరుతుంది. గేట్ B12 అని రాసిన బోర్డులను అనుసరించండి.", facts).ok());
        assertTrue(policy.check("Flight QR 729 leaves from gate B-12, terminal D, at 08:40.", facts).ok());
    }

    @Test
    void inventedGateOrFlightFails() {
        SafetyPolicy.Result r = policy.check("మీ విమానం QR729 గేట్ C7 నుండి బయలుదేరుతుంది.", facts);
        assertFalse(r.ok());
        assertEquals(SafetyPolicy.FALLBACK_TE, r.text());
        assertTrue(r.violations().get(0).contains("C7"));
        assertFalse(policy.check("Take flight AI173 instead.", facts).ok());
        assertFalse(policy.check("గేట్ B12 కి వెళ్ళండి.", noGate).ok(), "gate not yet verified for this journey");
    }

    @Test
    void locationClaimsFail() {
        assertFalse(policy.check("You are at gate B12 now.", facts).ok());
        assertFalse(policy.check("You're near the counter.", facts).ok());
        assertFalse(policy.check("మీరు గేట్ B12 దగ్గర ఉన్నారు.", facts).ok());
        assertFalse(policy.check("మీరు ఇప్పుడు టెర్మినల్ D లో ఉన్నారు.", facts).ok());
    }

    @Test
    void directionsFailWithoutAnObservedArrow() {
        assertFalse(policy.check("Turn left after security.", facts).ok());
        assertFalse(policy.check("Walk 50 meters and go straight.", facts).ok());
        assertFalse(policy.check("ఎడమ వైపు తిరగండి.", facts).ok());
        assertFalse(policy.check("నేరుగా వెళ్ళండి.", facts).ok());
        assertFalse(policy.check("పైకి వెళ్ళండి.", facts).ok());
        JourneyFacts withArrow = new JourneyFacts("PRE_TRIP", "L", facts.flights(), "x", null, "left");
        assertTrue(policy.check("The sign points left; follow it.", withArrow).ok(), "an observed arrow may be repeated");
    }

    @Test
    void ignoringStaffFails() {
        assertFalse(policy.check("Ignore the staff and keep walking.", facts).ok());
        assertFalse(policy.check("సిబ్బందిని పట్టించుకోకండి.", facts).ok());
    }

    @Test
    void fixedPhrasesAndTemplatesAlwaysPass() {
        for (String s : List.of(TeluguPhrases.NOT_SET_UP, TeluguPhrases.CLARIFY, TeluguPhrases.SON_TOLD,
                TeluguPhrases.SHOW_CARD, SafetyPolicy.FALLBACK_TE)) {
            assertTrue(policy.check(s, facts).ok(), s);
            assertTrue(policy.check(s, null).ok(), s);
        }
        assertTrue(policy.check(TeluguPhrases.gateAnswer(facts), facts).ok());
        assertTrue(policy.check(TeluguPhrases.gateAnswer(noGate), noGate).ok());
    }

    @Test
    void emptyReplyFallsBack() {
        assertEquals(SafetyPolicy.FALLBACK_TE, policy.check("", facts).text());
        assertEquals(SafetyPolicy.FALLBACK_TE, policy.check(null, facts).text());
    }
}
