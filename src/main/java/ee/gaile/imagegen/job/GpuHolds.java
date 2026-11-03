package ee.gaile.imagegen.job;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * The times the GPU was held for the speech services of the chat (POST /api/gpu/hold): the prompt of ComfyUI waits
 * meanwhile, so the deadlines of a job move by them ({@link Deadline}). A hold that is extended before its end stays
 * one hold; the holds older than {@link #KEEP} are forgotten (longer than any deadline).
 */
@Component
public class GpuHolds {

    static final Duration KEEP = Duration.ofHours(3);

    private final Clock clock;
    private final Deque<Held> past = new ArrayDeque<>();
    /** Start of the hold going on, null while not held */
    private Instant start;
    private Instant until;

    @Autowired
    public GpuHolds(Clock clock) {
        this.clock = clock;
    }

    /**
     * Holds the GPU from now for the duration, or longer if a hold going on ends later.
     *
     * @return true when a new hold starts, false when one was going on
     */
    public synchronized boolean extend(Duration duration) {
        Instant now = clock.instant();
        close(now);
        Instant end = now.plus(duration);
        if (start == null) {
            start = now;
            until = end;
            return true;
        }
        if (end.isAfter(until)) {
            until = end;
        }
        return false;
    }

    public synchronized boolean held() {
        close(clock.instant());
        return start != null;
    }

    /** The held time between the two instants; a hold going on counts up to now */
    public synchronized Duration within(Instant from, Instant to) {
        Instant now = clock.instant();
        close(now);
        Duration total = Duration.ZERO;
        for (Held held : past) {
            total = total.plus(overlap(held.start(), held.end(), from, to));
        }
        if (start != null) {
            total = total.plus(overlap(start, now.isBefore(until) ? now : until, from, to));
        }
        return total;
    }

    private void close(Instant now) {
        if (start != null && !until.isAfter(now)) {
            past.addLast(new Held(start, until));
            start = null;
            until = null;
        }
        Instant forgotten = now.minus(KEEP);
        while (!past.isEmpty() && past.peekFirst().end().isBefore(forgotten)) {
            past.removeFirst();
        }
    }

    private static Duration overlap(Instant start, Instant end, Instant from, Instant to) {
        Instant later = start.isAfter(from) ? start : from;
        Instant earlier = end.isBefore(to) ? end : to;
        return earlier.isAfter(later) ? Duration.between(later, earlier) : Duration.ZERO;
    }

    private record Held(Instant start, Instant end) {
    }
}
