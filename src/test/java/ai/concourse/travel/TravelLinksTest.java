package ai.concourse.travel;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TravelLinksTest {
    @Test
    void tokensAreBoundToTheJourneyAndTheSalt() {
        TravelLinks a = new TravelLinks("salt-1", "https://concourse.example/");
        UUID j = UUID.randomUUID();
        String t = a.token(j);
        assertEquals(24, t.length());
        assertTrue(a.valid(j, t));
        assertFalse(a.valid(UUID.randomUUID(), t), "another journey");
        assertFalse(a.valid(j, t.substring(1) + "0"), "edited token");
        assertFalse(new TravelLinks("salt-2", "x").valid(j, t), "another deployment");
        assertFalse(a.valid(j, null));
        assertEquals("https://concourse.example/travel.html#" + j + "." + t, a.url(j));
    }
}
