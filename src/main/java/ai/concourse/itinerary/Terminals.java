package ai.concourse.itinerary;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Terminal labels as the signs show them: "T3", "Terminal 3", "T-3" → "3"; "Terminal D" → "D". */
public final class Terminals {
    private Terminals() {}

    private static final Pattern TERMINAL = Pattern.compile("^(?:terminal|term\\.?|t)\\s*-?\\s*(\\d{1,2}|[A-Z])$", Pattern.CASE_INSENSITIVE);

    public static String normalize(String terminal) {
        if (terminal == null || terminal.isBlank()) return null;
        String t = terminal.strip();
        Matcher m = TERMINAL.matcher(t);
        if (m.matches()) return m.group(1).toUpperCase(Locale.ROOT);
        return t.length() <= 3 ? t.toUpperCase(Locale.ROOT) : t;
    }
}
