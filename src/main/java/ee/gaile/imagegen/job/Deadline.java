package ee.gaile.imagegen.job;

import java.time.Duration;
import java.time.Instant;

/**
 * When a run of ComfyUI is given up: at a fixed time, or as soon as it has reported nothing for a while. A slow run
 * that keeps reporting its steps goes on until the fixed time (an LTX video that spilled out of the VRAM took over
 * 15 min, step by step), a hung one is caught early.
 *
 * @param at    the run fails with "timeout" after it
 * @param stall the run fails when no event of it arrived for this long
 */
public record Deadline(Instant at, Duration stall) {
}
