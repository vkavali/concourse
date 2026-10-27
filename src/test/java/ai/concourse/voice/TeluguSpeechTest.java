package ai.concourse.voice;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TeluguSpeechTest {
    @Test
    void gateCodesReadLikeStaffSayThem() {
        assertEquals("బి పన్నెండు", TeluguSpeech.spokenForm("B12"));
        assertEquals("సి ఏడు", TeluguSpeech.spokenForm("C7"));
        assertEquals("డి నలభై ఏ", TeluguSpeech.spokenForm("D40A"));
    }

    @Test
    void flightNumbersAreSpelledDigitByDigit() {
        assertEquals("క్యూ ఆర్ ఏడు రెండు తొమ్మిది", TeluguSpeech.spokenForm("QR729"));
        assertEquals("ఆరు ఈ రెండు సున్నా మూడు", TeluguSpeech.spokenForm("6E203"));
        assertEquals("ఏ ఐ ఒకటి ఏడు మూడు", TeluguSpeech.spokenForm("AI173"));
    }

    @Test
    void codesGetTheirOwnBeatInsideASentence() {
        assertEquals("మీ విమానం, క్యూ ఆర్ ఏడు రెండు తొమ్మిది, గేట్, బి పన్నెండు, నుండి బయలుదేరుతుంది.",
                TeluguSpeech.spokenForm("మీ విమానం QR729 గేట్ B12 నుండి బయలుదేరుతుంది."));
        assertEquals("మళ్ళీ చెబుతున్నాను: గేట్, బి పన్నెండు.", TeluguSpeech.spokenForm("మళ్ళీ చెబుతున్నాను: గేట్ B12."));
    }

    @Test
    void numbersAndTimes() {
        assertEquals("గేట్ ఏడు", TeluguSpeech.spokenForm("గేట్ 7"));
        assertEquals("రెండు సున్నా రెండు ఆరు", TeluguSpeech.spokenForm("2026"));
        assertEquals("మూడు గంటల నలభై ఐదు నిమిషాలకు", TeluguSpeech.spokenForm("03:45"));
        assertEquals("ఎనిమిది గంటలకు", TeluguSpeech.spokenForm("08:00"));
    }

    @Test
    void teluguTextIsUntouched() {
        assertEquals(TeluguPhrases.CLARIFY, TeluguSpeech.spokenForm(TeluguPhrases.CLARIFY));
    }
}
