package ai.concourse.ops;

import ai.concourse.itinerary.Itinerary;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The sample ticket is re-dated for the demo: every time of day and every leg's spacing stays as printed. */
class OpsRedateTest {
    private static Itinerary june() {
        return new Itinerary(true, "sample", "Lakshmi", "DEMO42", List.of(
                new Itinerary.Segment("Qatar Airways", "QR4793", "HYD", "DOH", "2026-06-06T03:45", "2026-06-06T06:05", null, null, 1.0, ""),
                new Itinerary.Segment("Qatar Airways", "QR729", "DOH", "DFW", "2026-06-06T08:40", "2026-06-06T15:50", "D", null, 1.0, "")));
    }

    @Test
    void shiftsEveryLegByTheSameWholeDays() {
        Itinerary raw = june();
        long days = OpsController.redateDays(raw, 2);
        LocalDate target = LocalDate.now(ZoneId.of("Asia/Kolkata")).plusDays(2);
        assertEquals(target, LocalDate.parse("2026-06-06").plusDays(days));
        Itinerary moved = OpsController.redate(raw, days);
        assertEquals(target + "T03:45", moved.segments().get(0).departureLocal());
        assertEquals(target + "T08:40", moved.segments().get(1).departureLocal());
        assertEquals(target + "T15:50", moved.segments().get(1).arrivalLocal());
        assertEquals("D", moved.segments().get(1).terminal());
        assertEquals("Lakshmi", moved.passengerFirstName());
        assertSame(raw, OpsController.redate(raw, 0));
    }

    @Test
    void twoPlaceholderPhonesWithoutWhatsAppOnePhoneForBothWithIt() {
        OpsController.DemoRequest blank = new OpsController.DemoRequest("", "", 2);
        assertArrayEquals(new String[]{"10000000000", "10000000001"}, OpsController.phones(blank, true), "simulator: both columns fill");
        assertNull(OpsController.phones(blank, false), "real WhatsApp needs a real child number");
        OpsController.DemoRequest one = new OpsController.DemoRequest("+1 716 555 0123", null, null);
        assertArrayEquals(new String[]{"17165550123", "17165550123"}, OpsController.phones(one, false), "one real phone plays both");
        assertArrayEquals(new String[]{"17165550123", "10000000001"}, OpsController.phones(one, true));
        OpsController.DemoRequest two = new OpsController.DemoRequest("17165550123", "91 98765 43210", null);
        assertArrayEquals(new String[]{"17165550123", "919876543210"}, OpsController.phones(two, false));
    }

    @Test
    void samplesAreVoiceNotesInAnyFormatAndPhotosWithTheWavFormPreferred() {
        assertEquals(List.of("a.wav", "b.ogg", "c.M4A", "sign.jpg", "sign2.png"),
                OpsController.pickSamples(List.of("sign2.png", "a.m4a", "a.wav", "b.ogg", "c.M4A", "sign.jpg", "notes.txt", "2026-11-02-voice.wav.tmp")));
        assertTrue(OpsController.isAudio("note.opus"));
        assertFalse(OpsController.isAudio("sign.jpg"));
    }

    @Test
    void unparseableDatesAreLeftAlone() {
        Itinerary odd = new Itinerary(true, "sample", "L", "X", List.of(
                new Itinerary.Segment("A", "A1", "HYD", "DOH", "soon", null, null, null, 0.5, "")));
        assertEquals(0, OpsController.redateDays(odd, 2));
        assertEquals("soon", OpsController.redate(odd, 3).segments().get(0).departureLocal());
    }
}
