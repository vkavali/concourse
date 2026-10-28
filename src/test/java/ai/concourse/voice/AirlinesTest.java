package ai.concourse.voice;

import ai.concourse.journey.JourneyFacts;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AirlinesTest {
    @Test
    void matchesByFlightPrefixFirst() {
        assertEquals("ఎయిర్ ఇండియా", Airlines.teluguName(null, "AI173").orElseThrow());
        assertEquals("ఇండిగో", Airlines.teluguName("Something else", "6E 203").orElseThrow());
        assertEquals("ఖతార్ ఎయిర్‌వేస్", Airlines.teluguName("Qatar Airways", "qr729").orElseThrow());
    }

    @Test
    void fallsBackToTheAirlineName() {
        assertEquals("ఎయిర్ ఇండియా ఎక్స్‌ప్రెస్", Airlines.teluguName("Air India Express Ltd", "ZZ12").orElseThrow());
        assertEquals("ఎమిరేట్స్", Airlines.teluguName("EMIRATES", null).orElseThrow());
        assertTrue(Airlines.teluguName("Unknown Air", "ZZ12").isEmpty());
        assertTrue(Airlines.teluguName(null, null).isEmpty());
    }

    @Test
    void spokenNamesCoverEveryKnownFlight() {
        Map<String, String> names = Airlines.spokenNames(List.of(
                new JourneyFacts.Flight(1, "Qatar Airways", "QR4793", "HYD", "DOH", "2026-11-28 03:45", null, null, null, false),
                new JourneyFacts.Flight(2, null, "ZZ12", "DOH", "DFW", "2026-11-28 08:40", null, "D", "B12", true)));
        assertEquals(Map.of("QR4793", "ఖతార్ ఎయిర్‌వేస్"), names);
    }
}
