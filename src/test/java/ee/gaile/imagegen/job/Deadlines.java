package ee.gaile.imagegen.job;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/** Deadlines for the tests that do not care about the holds of the GPU */
public final class Deadlines {

    private Deadlines() {
    }

    public static Deadline withoutHolds(Instant at, Duration stall) {
        return new Deadline(at, stall, Instant.MIN, new GpuHolds(Clock.systemUTC()));
    }
}
