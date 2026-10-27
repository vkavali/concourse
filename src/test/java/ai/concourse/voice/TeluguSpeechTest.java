package ai.concourse.voice;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TeluguSpeechTest {
    @Test
    void codesAreSpelledOutInTelugu() {
        assertEquals("క్యూ ఆర్ ఏడు రెండు తొమ్మిది", TeluguSpeech.spokenForm("QR729"));
        assertEquals("బి ఒకటి రెండు", TeluguSpeech.spokenForm("B12"));
        assertEquals("ఆరు ఈ రెండు సున్నా మూడు", TeluguSpeech.spokenForm("6E203"));
        assertEquals("గేట్ ఏడు", TeluguSpeech.spokenForm("గేట్ 7"));
    }

    @Test
    void timesBecomeWords() {
        assertEquals("మూడు గంటల నలభై ఐదు నిమిషాలకు", TeluguSpeech.spokenForm("03:45"));
        assertEquals("ఎనిమిది గంటలకు", TeluguSpeech.spokenForm("08:00"));
    }

    @Test
    void teluguTextIsUntouched() {
        assertEquals(TeluguPhrases.CLARIFY, TeluguSpeech.spokenForm(TeluguPhrases.CLARIFY));
        assertEquals("మీ విమానం క్యూ ఆర్ ఏడు రెండు తొమ్మిది గేట్ బి ఒకటి రెండు నుండి బయలుదేరుతుంది.",
                TeluguSpeech.spokenForm("మీ విమానం QR729 గేట్ B12 నుండి బయలుదేరుతుంది."));
    }
}
