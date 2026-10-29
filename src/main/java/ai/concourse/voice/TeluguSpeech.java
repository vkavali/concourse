package ai.concourse.voice;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Prepares text for MMS-TTS Telugu, whose vocabulary is Telugu script only: flight numbers, gate codes and bare numbers
 * are spelled out letter by letter and digit by digit in Telugu ("QR729" → "క్యూ ఆర్ ఏడు రెండు తొమ్మిది", "B12" → "బీ పన్నెండు").
 * Message text keeps the Latin codes (the safety check and the card need them); only the audio gets this form.
 */
public final class TeluguSpeech {
    private TeluguSpeech() {}

    /** English letter names as Telugu writes them — long vowels (బీ, టీ), so "B" is "bee", not a clipped "bi". */
    private static final Map<Character, String> LETTERS = Map.ofEntries(
            Map.entry('A', "ఏ"), Map.entry('B', "బీ"), Map.entry('C', "సీ"), Map.entry('D', "డీ"), Map.entry('E', "ఈ"),
            Map.entry('F', "ఎఫ్"), Map.entry('G', "జీ"), Map.entry('H', "హెచ్"), Map.entry('I', "ఐ"), Map.entry('J', "జే"),
            Map.entry('K', "కే"), Map.entry('L', "ఎల్"), Map.entry('M', "ఎమ్"), Map.entry('N', "ఎన్"), Map.entry('O', "ఓ"),
            Map.entry('P', "పీ"), Map.entry('Q', "క్యూ"), Map.entry('R', "ఆర్"), Map.entry('S', "ఎస్"), Map.entry('T', "టీ"),
            Map.entry('U', "యూ"), Map.entry('V', "వీ"), Map.entry('W', "డబ్ల్యూ"), Map.entry('X', "ఎక్స్"), Map.entry('Y', "వై"),
            Map.entry('Z', "జెడ్"));

    private static final String[] DIGITS = {"సున్నా", "ఒకటి", "రెండు", "మూడు", "నాలుగు", "ఐదు", "ఆరు", "ఏడు", "ఎనిమిది", "తొమ్మిది"};

    /** Codes (letter+digit mixes) and standalone digit runs. Clock times like 03:45 are spoken by {@link #time}. */
    private static final Pattern CODE_OR_NUMBER = Pattern.compile("\\b(?:[A-Z0-9]*[A-Z][A-Z0-9]*\\d[A-Z0-9]*|\\d[A-Z0-9]*[A-Z][A-Z0-9]*|\\d+)\\b");
    private static final Pattern TIME = Pattern.compile("\\b([01]?\\d|2[0-3]):([0-5]\\d)\\b");
    /** A lone capital letter between non-Latin text ("B గేట్లు") is a gate letter: say its name. */
    private static final Pattern LONE_LETTER = Pattern.compile("(?<![A-Za-z0-9])([A-Z])(?![A-Za-z0-9])");
    private static final Pattern GATE_CODE = Pattern.compile("^([A-Z]{1,2})(\\d{1,2})([A-Z]?)$");

    /**
     * Text → what the TTS should say. Codes are wrapped in commas so the voice pauses around them
     * ("గేట్ B12 నుండి" → "గేట్, బి పన్నెండు, నుండి"): the letter and the number get their own beat.
     */
    public static String spokenForm(String text) {
        return spokenForm(text, Map.of());
    }

    /**
     * Same, with known codes replaced by names before anything is spelled: {@code {"AI173": "ఎయిర్ ఇండియా"}} turns
     * "మీ విమానం AI173" into "మీ విమానం ఎయిర్ ఇండియా" — a parent knows her airline, not her flight code.
     */
    public static String spokenForm(String text, Map<String, String> names) {
        if (text == null) return "";
        String named = text;
        for (Map.Entry<String, String> e : names.entrySet()) {
            named = Pattern.compile("(?i)(?<![A-Za-z0-9])" + Pattern.quote(e.getKey()) + "(?![A-Za-z0-9])").matcher(named)
                    .replaceAll(Matcher.quoteReplacement(e.getValue()));
        }
        String t = TIME.matcher(named).replaceAll(m -> time(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))));
        t = LONE_LETTER.matcher(t).replaceAll(m -> Matcher.quoteReplacement(LETTERS.getOrDefault(m.group(1).charAt(0), m.group(1))));
        Matcher m = CODE_OR_NUMBER.matcher(t);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String token = m.group();
            String spoken = token.chars().allMatch(Character::isDigit) ? number(token) : ", " + spell(token) + ",";
            m.appendReplacement(out, Matcher.quoteReplacement(spoken));
        }
        m.appendTail(out);
        return tidy(out.toString());
    }

    /** Gate codes read like airport staff say them ("B twelve"); longer codes and flight numbers digit by digit. */
    public static String spell(String token) {
        String up = token.toUpperCase();
        Matcher g = GATE_CODE.matcher(up);
        if (g.matches()) {
            String letters = letters(g.group(1));
            String num = number(g.group(2));
            String suffix = g.group(3).isEmpty() ? "" : " " + letters(g.group(3));
            return (letters + " " + num + suffix).strip();
        }
        StringBuilder sb = new StringBuilder();
        for (char c : up.toCharArray()) {
            String w = Character.isDigit(c) ? DIGITS[c - '0'] : LETTERS.get(c);
            if (w == null) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(w);
        }
        return sb.toString();
    }

    private static String letters(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            String w = LETTERS.get(c);
            if (w == null) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(w);
        }
        return sb.toString();
    }

    /** Whole numbers: up to 99 as words, longer digit by digit (years, flight numbers). */
    public static String number(String digits) {
        if (digits.length() <= 2) return number(Integer.parseInt(digits));
        StringBuilder sb = new StringBuilder();
        for (char c : digits.toCharArray()) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(DIGITS[c - '0']);
        }
        return sb.toString();
    }

    private static String tidy(String s) {
        return s.replaceAll("\\s+,", ",")             // "విమానం ," → "విమానం,"
                .replaceAll("\\s*,\\s*,", ",")      // ", ," → ","
                .replaceAll(",\\s*([.!?])", "$1")   // ", ." → "."
                .replaceAll("([.!?:])\\s*,\\s*", "$1 ")  // ". , బీ" → ". బీ": a code opening a sentence needs no comma before it
                .replaceAll("^\\s*,\\s*", "")       // leading comma
                .replaceAll("\\s*,\\s*$", "")       // trailing comma
                .replaceAll("\\s{2,}", " ")
                .strip();
    }

    /** 03:45 → "మూడు గంటల నలభై ఐదు నిమిషాలకు" (at three forty-five). */
    public static String time(int h, int min) {
        String hours = number(h) + " గంటల";
        if (min == 0) return hours + "కు";
        return hours + " " + number(min) + " నిమిషాలకు";
    }

    /** 0–59 in Telugu words. */
    public static String number(int n) {
        String[] tens = {"", "పది", "ఇరవై", "ముప్పై", "నలభై", "యాభై"};
        String[] teens = {"పది", "పదకొండు", "పన్నెండు", "పదమూడు", "పద్నాలుగు", "పదిహేను", "పదహారు", "పదిహేడు", "పద్దెనిమిది", "పంతొమ్మిది"};
        if (n < 10) return DIGITS[n];
        if (n < 20) return teens[n - 10];
        if (n < 60) return n % 10 == 0 ? tens[n / 10] : tens[n / 10] + " " + DIGITS[n % 10];
        return spell(Integer.toString(n));
    }
}
