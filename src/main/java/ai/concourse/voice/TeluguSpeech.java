package ai.concourse.voice;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Prepares text for MMS-TTS Telugu, whose vocabulary is Telugu script only: flight numbers, gate codes and bare numbers
 * are spelled out letter by letter and digit by digit in Telugu ("QR729" → "క్యూ ఆర్ ఏడు రెండు తొమ్మిది", "B12" → "బి ఒకటి రెండు").
 * Message text keeps the Latin codes (the safety check and the card need them); only the audio gets this form.
 */
public final class TeluguSpeech {
    private TeluguSpeech() {}

    private static final Map<Character, String> LETTERS = Map.ofEntries(
            Map.entry('A', "ఏ"), Map.entry('B', "బి"), Map.entry('C', "సి"), Map.entry('D', "డి"), Map.entry('E', "ఈ"),
            Map.entry('F', "ఎఫ్"), Map.entry('G', "జి"), Map.entry('H', "హెచ్"), Map.entry('I', "ఐ"), Map.entry('J', "జే"),
            Map.entry('K', "కే"), Map.entry('L', "ఎల్"), Map.entry('M', "ఎమ్"), Map.entry('N', "ఎన్"), Map.entry('O', "ఓ"),
            Map.entry('P', "పి"), Map.entry('Q', "క్యూ"), Map.entry('R', "ఆర్"), Map.entry('S', "ఎస్"), Map.entry('T', "టి"),
            Map.entry('U', "యు"), Map.entry('V', "వి"), Map.entry('W', "డబ్ల్యూ"), Map.entry('X', "ఎక్స్"), Map.entry('Y', "వై"),
            Map.entry('Z', "జెడ్"));

    private static final String[] DIGITS = {"సున్నా", "ఒకటి", "రెండు", "మూడు", "నాలుగు", "ఐదు", "ఆరు", "ఏడు", "ఎనిమిది", "తొమ్మిది"};

    /** Codes (letters+digits mixes) and standalone digit runs. Clock times like 03:45 are spoken as "మూడు నలభై ఐదు" by {@link #time}. */
    private static final Pattern CODE_OR_NUMBER = Pattern.compile("\\b(?:[A-Z0-9]*[A-Z][A-Z0-9]*\\d[A-Z0-9]*|\\d[A-Z0-9]*[A-Z][A-Z0-9]*|\\d+)\\b");
    private static final Pattern TIME = Pattern.compile("\\b([01]?\\d|2[0-3]):([0-5]\\d)\\b");

    public static String spokenForm(String text) {
        if (text == null) return "";
        String t = TIME.matcher(text).replaceAll(m -> time(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))));
        Matcher m = CODE_OR_NUMBER.matcher(t);
        StringBuilder out = new StringBuilder();
        while (m.find()) m.appendReplacement(out, Matcher.quoteReplacement(spell(m.group())));
        m.appendTail(out);
        return out.toString().replaceAll("\\s{2,}", " ").strip();
    }

    /** Every character spoken separately — that is how gate and flight codes are read out at airports. */
    public static String spell(String token) {
        StringBuilder sb = new StringBuilder();
        for (char c : token.toCharArray()) {
            String w = Character.isDigit(c) ? DIGITS[c - '0'] : LETTERS.get(Character.toUpperCase(c));
            if (w == null) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(w);
        }
        return sb.toString();
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
