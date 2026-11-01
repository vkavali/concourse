package ai.concourse.ops;

import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Every model call the app makes, as it happens: which Meta model, for what, how long it took, whether it worked.
 * The simulator shows this as a ticker next to the two phones, so a viewer (or a judge) can see the live Meta model
 * calls behind each message. Entries never carry prompts, transcripts or audio — only ids, counts and milliseconds.
 */
@Component
public class ModelCallLog {
    public record Entry(long seq, OffsetDateTime at, String model, String op, long ms, boolean ok, String note) {}

    private static final int KEEP = 200;
    private final Deque<Entry> ring = new ArrayDeque<>();
    private long seq;

    public synchronized void record(String model, String op, long ms, boolean ok, String note) {
        ring.addLast(new Entry(++seq, OffsetDateTime.now(), model, op, ms, ok, note == null ? "" : note));
        while (ring.size() > KEEP) ring.pollFirst();
    }

    /** Entries after {@code afterSeq}, oldest first. */
    public synchronized List<Entry> since(long afterSeq) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : ring) if (e.seq() > afterSeq) out.add(e);
        return out;
    }
}
