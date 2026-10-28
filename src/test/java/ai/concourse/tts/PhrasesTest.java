package ai.concourse.tts;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PhrasesTest {
    @Test
    void sentencesGetLongBeatsCommasShortOnesAndTheLastGetsNone() {
        List<Phrases.Phrase> p = Phrases.split("మీ ఎయిర్ ఇండియా విమానం గేట్, బీ పన్నెండు, నుండి బయలుదేరుతుంది. మళ్ళీ చెబుతున్నాను: గేట్, బీ పన్నెండు.");
        assertEquals(List.of(
                new Phrases.Phrase("మీ ఎయిర్ ఇండియా విమానం గేట్", Phrases.COMMA_MS),
                new Phrases.Phrase("బీ పన్నెండు", Phrases.COMMA_MS),
                new Phrases.Phrase("నుండి బయలుదేరుతుంది.", Phrases.SENTENCE_MS),
                new Phrases.Phrase("మళ్ళీ చెబుతున్నాను.", Phrases.SENTENCE_MS),
                new Phrases.Phrase("గేట్", Phrases.COMMA_MS),
                new Phrases.Phrase("బీ పన్నెండు.", 0)), p);
    }

    @Test
    void strayPunctuationNeverMakesAnEmptyPhrase() {
        assertEquals(List.of(new Phrases.Phrase("దయచేసి పెద్ద స్క్రీన్ చూడండి.", Phrases.SENTENCE_MS),
                        new Phrases.Phrase("లేదా ఈ కార్డును సిబ్బందికి చూపించండి.", 0)),
                Phrases.split("దయచేసి పెద్ద స్క్రీన్ చూడండి., లేదా ఈ కార్డును సిబ్బందికి చూపించండి. . "));
        assertTrue(Phrases.split("  ...  ").isEmpty());
        assertTrue(Phrases.split(null).isEmpty());
        assertEquals(List.of(new Phrases.Phrase("నమస్కారం", 0)), Phrases.split("నమస్కారం"));
    }
}
