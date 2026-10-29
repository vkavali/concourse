package ai.concourse.safety;

import ai.concourse.journey.JourneyFacts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The executable safety contract (docs/SAFETY.md). Every outbound parent message passes {@link #check}; a violation
 * is replaced by {@link #FALLBACK_TE} and the child is notified. Rules are deliberately conservative: a false alarm
 * costs one "ask staff" reply, a miss could send her the wrong way.
 */
@Component
public class SafetyPolicy {
    private static final Logger log = LoggerFactory.getLogger(SafetyPolicy.class);

    /** "Please show this card to a staff member. I have told your son." */
    public static final String FALLBACK_TE = "ఈ కార్డు అక్కడి సిబ్బందికి చూపించండి. మీ అబ్బాయికి నేను చెప్పాను.";

    /** Gate/terminal-looking tokens: B12, C-7, T2, D 40. Airline+number flight codes: QR729, AI 173, 6E203. */
    private static final Pattern CODE = Pattern.compile("\\b([A-Z]{1,2})\\s?-?(\\d{1,4})([A-Z]?)\\b");
    private static final Pattern FLIGHT = Pattern.compile("\\b([A-Z][A-Z0-9]|[0-9][A-Z])\\s?(\\d{2,4})\\b");

    private static final List<Pattern> LOCATION_CLAIMS = List.of(
            Pattern.compile("\\byou are (at|near|in|inside|next to|beside)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\byou're (at|near|in|inside|next to)\\b", Pattern.CASE_INSENSITIVE),
            // Telugu: "మీరు ... (దగ్గర|వద్ద|లో|లోపల|పక్కన) ఉన్నారు" — "you are at/near/in ..."
            Pattern.compile("మీరు[^.።!?]{0,60}(దగ్గర|వద్ద|లో|లోపల|పక్కన)\\s*(ఉన్నారు|ఉన్నారా)"),
            Pattern.compile("మీరు\\s+(ఇప్పుడు\\s+)?(గేట్|టెర్మినల్|కౌంటర్)[^.።!?]{0,40}(దగ్గర|వద్ద)\\s*ఉన్నారు"));

    private static final List<Pattern> DIRECTIONS = List.of(
            Pattern.compile("\\b(turn|go|walk|head)\\s+(left|right|straight)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(upstairs|downstairs|take the (escalator|elevator|lift|stairs))\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b\\d+\\s?(meters|metres|feet|steps)\\b", Pattern.CASE_INSENSITIVE),
            // Telugu: left, right, straight, upstairs/downstairs, turn, "N meters"
            Pattern.compile("(ఎడమ|కుడి)\\s*(వైపు|కి|పక్కకు|వైపుకు)"),
            Pattern.compile("(నేరుగా|తిన్నగా)\\s*(వెళ్ళండి|వెళ్లండి|నడవండి)"),
            Pattern.compile("(పైకి|కిందకి|కిందికి)\\s*(వెళ్ళండి|వెళ్లండి|ఎక్కండి|దిగండి)"),
            Pattern.compile("(మలుపు|తిరగండి|తిప్పండి)"),
            Pattern.compile("\\d+\\s*(మీటర్ల|మీటర్లు|అడుగుల|అడుగులు)"));

    private static final List<Pattern> IGNORE_STAFF = List.of(
            Pattern.compile("\\b(ignore|disregard|don't listen to)\\s+(the\\s+)?(staff|officer|security|airline|crew)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(సిబ్బంది|అధికారి|సెక్యూరిటీ)[^.።!?]{0,30}(పట్టించుకోకండి|వినకండి|లెక్క చేయకండి)"));

    public record Result(boolean ok, List<String> violations, String text) {
        public static Result pass(String text) { return new Result(true, List.of(), text); }
    }

    /** Checks an outbound parent reply against the verified facts. Returns the text to send (original or fallback). */
    public Result check(String text, JourneyFacts facts) {
        if (text == null || text.isBlank()) return new Result(false, List.of("empty"), FALLBACK_TE);
        List<String> violations = new ArrayList<>();

        // Rule 1: every gate/terminal/flight code must be a verified fact.
        Set<String> allowed = facts == null ? Set.of() : facts.allowedCodes();
        Matcher m = CODE.matcher(text);
        while (m.find()) {
            String code = JourneyFacts.norm(m.group());
            if (!allowed.contains(code)) violations.add("unverified code " + code);
        }
        Matcher f = FLIGHT.matcher(text);
        while (f.find()) {
            String code = JourneyFacts.norm(f.group());
            if (!allowed.contains(code) && !looksLikeTime(f.group())) violations.add("unverified flight " + code);
        }

        // Rule 3: no location claims.
        for (Pattern p : LOCATION_CLAIMS) if (p.matcher(text).find()) violations.add("location claim: " + p.pattern());

        // Rule 2: no directions unless an explicit sign arrow was observed.
        boolean arrow = facts != null && facts.observedSignArrow() != null;
        if (!arrow) for (Pattern p : DIRECTIONS) if (p.matcher(text).find()) violations.add("direction: " + p.pattern());

        // Rule 5: never tell her to ignore staff.
        for (Pattern p : IGNORE_STAFF) if (p.matcher(text).find()) violations.add("ignore staff: " + p.pattern());

        if (violations.isEmpty()) return Result.pass(text);
        log.warn("safety fallback: {}", violations);
        return new Result(false, List.copyOf(violations), FALLBACK_TE);
    }

    /** "10 45" or "08 40" are times, not flight numbers. */
    private static boolean looksLikeTime(String token) {
        return token.matches("\\d{2}\\s?\\d{2}") && Integer.parseInt(token.substring(0, 2)) < 24;
    }
}
