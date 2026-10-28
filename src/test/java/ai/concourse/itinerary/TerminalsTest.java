package ai.concourse.itinerary;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TerminalsTest {
    @Test
    void labelsMatchTheSigns() {
        assertEquals("3", Terminals.normalize("T3"));
        assertEquals("3", Terminals.normalize("Terminal 3"));
        assertEquals("3", Terminals.normalize(" t-3 "));
        assertEquals("D", Terminals.normalize("Terminal D"));
        assertEquals("D", Terminals.normalize("d"));
        assertEquals("1C", Terminals.normalize("1c"));
        assertEquals("International", Terminals.normalize("International"));
        assertNull(Terminals.normalize(" "));
        assertNull(Terminals.normalize(null));
    }
}
