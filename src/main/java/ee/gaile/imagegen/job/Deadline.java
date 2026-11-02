package ee.gaile.imagegen.job;

import java.time.Duration;
import java.time.Instant;

/**
 * When a run of ComfyUI is given up: at a fixed time, or as soon as it has reported nothing for a while. A slow run
 * that keeps reporting its steps goes on until the fixed time (an LTX video that spilled out of the VRAM took over
 * 15 min, step by step), a hung one is caught early. The time the GPU was held for the speech of the chat does not
 * count: the prompt waits then and reports nothing.
 *
 * @param at    the run fails with "timeout" after it, moved by the holds since {@code since}
 * @param stall the run fails when no event of it arrived for this long, the holds not counted
 * @param since start of the run
 * @param holds the holds of the GPU
 */
public record Deadline(Instant at, Duration stall, Instant since, GpuHolds holds) {

    /** Without holds */
    public Deadline(Instant at, Duration stall) {
        this(at, stall, Instant.MIN, GpuHolds.NONE);
    }

    public boolean passed(Instant now) {
        return !now.isBefore(at.plus(holds.within(since, now)));
    }

    /** The silence since the last event, the holds not counted */
    public Duration silence(Instant lastEvent, Instant now) {
        return Duration.between(lastEvent, now).minus(holds.within(lastEvent, now));
    }
}
