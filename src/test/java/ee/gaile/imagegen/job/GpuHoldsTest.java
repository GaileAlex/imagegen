package ee.gaile.imagegen.job;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class GpuHoldsTest {

    private final MutableClock clock = new MutableClock();
    private final GpuHolds holds = new GpuHolds(clock);

    @Test
    void aHoldStartsOnceAndAnExtensionKeepsIt() {
        assertThat(holds.extend(Duration.ofSeconds(30))).isTrue();
        clock.advance(Duration.ofSeconds(10));
        assertThat(holds.extend(Duration.ofSeconds(30))).isFalse();
        clock.advance(Duration.ofSeconds(25));
        assertThat(holds.held()).isTrue();
        clock.advance(Duration.ofSeconds(5));

        assertThat(holds.held()).isFalse();
        assertThat(holds.extend(Duration.ofSeconds(5))).isTrue();
    }

    @Test
    void aShorterAskDoesNotCutTheHold() {
        Instant start = clock.instant();
        holds.extend(Duration.ofSeconds(60));
        holds.extend(Duration.ofSeconds(5));
        clock.advance(Duration.ofSeconds(90));

        assertThat(holds.within(start, clock.instant())).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void withinCountsOnlyTheOverlap() {
        Instant start = clock.instant();
        holds.extend(Duration.ofSeconds(20));
        clock.advance(Duration.ofSeconds(100));
        holds.extend(Duration.ofSeconds(10));
        clock.advance(Duration.ofSeconds(100));

        assertThat(holds.within(start, clock.instant())).isEqualTo(Duration.ofSeconds(30));
        assertThat(holds.within(start.plusSeconds(15), start.plusSeconds(105))).isEqualTo(Duration.ofSeconds(10));
        assertThat(holds.within(start.plusSeconds(30), start.plusSeconds(90))).isZero();
    }

    @Test
    void aHoldGoingOnCountsUpToNow() {
        Instant start = clock.instant();
        holds.extend(Duration.ofSeconds(60));
        clock.advance(Duration.ofSeconds(25));

        assertThat(holds.within(start, clock.instant().plusSeconds(100))).isEqualTo(Duration.ofSeconds(25));
    }

    @Test
    void oldHoldsAreForgotten() {
        Instant start = clock.instant();
        holds.extend(Duration.ofSeconds(10));
        clock.advance(GpuHolds.KEEP.plusMinutes(1));

        assertThat(holds.within(start, clock.instant())).isZero();
    }

    @Test
    void theDeadlineMovesByTheHolds() {
        Instant start = clock.instant();
        Deadline deadline = new Deadline(start.plusSeconds(60), Duration.ofSeconds(20), start, holds);
        clock.advance(Duration.ofSeconds(30));
        holds.extend(Duration.ofSeconds(40));
        clock.advance(Duration.ofSeconds(40));

        // 70 s after the start, 40 of them held
        assertThat(deadline.passed(clock.instant())).isFalse();
        assertThat(deadline.silence(start.plusSeconds(25), clock.instant())).isEqualTo(Duration.ofSeconds(5));
        clock.advance(Duration.ofSeconds(30));
        assertThat(deadline.passed(clock.instant())).isTrue();
    }

    @Test
    void aDeadlineWithoutHoldsIsTheFixedTime() {
        Instant at = clock.instant();
        Deadline deadline = Deadlines.withoutHolds(at, Duration.ofSeconds(5));

        assertThat(deadline.passed(at.minusMillis(1))).isFalse();
        assertThat(deadline.passed(at)).isTrue();
        assertThat(deadline.silence(at.minusSeconds(7), at)).isEqualTo(Duration.ofSeconds(7));
    }
}
