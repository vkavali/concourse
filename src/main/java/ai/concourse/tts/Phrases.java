package ai.concourse.tts;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a message into the phrases that are synthesised one by one, with the silence that follows each. The pauses
 * are inserted by us, not by the voice: neural voices run "చూడండి, లేదా" together and skip the beat around a gate
 * code, whatever the punctuation says. Sentence ends get a long beat, commas a short one — so "గేట్, బీ పన్నెండు,"
 * is heard as three things. Each phrase is cached on its own, so "మళ్ళీ చెబుతున్నాను" or "బీ పన్నెండు" is synthesised once.
 */
public final class Phrases {
    private Phrases() {}

    public static final int COMMA_MS = 350;
    public static final int SENTENCE_MS = 650;

    public record Phrase(String text, int pauseAfterMs) {}

    public static List<Phrase> split(String text) {
        List<Phrase> out = new ArrayList<>();
        if (text == null) return out;
        StringBuilder cur = new StringBuilder();
        for (char c : text.toCharArray()) {
            switch (c) {
                case ',', ';', '،' -> flush(out, cur, COMMA_MS);
                case '.', '!', '?', '।', '॥' -> { cur.append(c); flush(out, cur, SENTENCE_MS); }
                case ':', '\n' -> { if (c == ':') cur.append('.'); flush(out, cur, SENTENCE_MS); }
                default -> cur.append(c);
            }
        }
        flush(out, cur, 0);
        if (!out.isEmpty()) {
            Phrase last = out.get(out.size() - 1);
            out.set(out.size() - 1, new Phrase(last.text(), 0)); // nothing follows the last phrase
        }
        return out;
    }

    private static void flush(List<Phrase> out, StringBuilder cur, int pause) {
        String t = cur.toString().strip();
        cur.setLength(0);
        if (t.isEmpty() || t.matches("[.!?।॥\\s]+")) {
            // stray punctuation: lengthen the previous pause instead of creating an empty phrase
            if (!out.isEmpty()) {
                Phrase last = out.get(out.size() - 1);
                if (pause > last.pauseAfterMs()) out.set(out.size() - 1, new Phrase(last.text(), pause));
            }
            return;
        }
        out.add(new Phrase(t, pause));
    }
}
