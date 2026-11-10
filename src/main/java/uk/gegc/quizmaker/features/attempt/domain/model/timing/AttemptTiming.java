package uk.gegc.quizmaker.features.attempt.domain.model.timing;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy.TimingMode;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy.TimingPolicy;
import static uk.gegc.quizmaker.features.attempt.domain.model.timing.AttemptTimingException.*;
import static uk.gegc.quizmaker.features.attempt.domain.model.timing.AttemptTimingException.Reason.*;

/** Immutable overall timing checkpoint. Scoped events and adjustments have separate owners. */
public record AttemptTiming(int version, UUID attemptId, UUID policySnapshotId, TimingPolicy policy,
                            Instant startedAt, Instant durationDeadline, Instant effectiveDeadline, Duration grace) {
    public static final int VERSION = 1;
    private static final Instant MIN = Instant.parse("1000-01-01T00:00:00Z");
    private static final Instant MAX = Instant.parse("9999-12-31T23:59:59.999999Z");
    public enum Decision { BEFORE_START, OPEN, EXPIRED }

    public AttemptTiming {
        require(version == VERSION, UNSUPPORTED);
        require(attemptId != null && policySnapshotId != null && policy != null
                && policy.mode() == TimingMode.TOTAL_ATTEMPT && Duration.ZERO.equals(grace), CORRUPT);
        require(time(startedAt) && time(effectiveDeadline), CORRUPT);
        Instant expectedDuration = durationEnd(startedAt, policy.duration());
        require(Objects.equals(durationDeadline, expectedDuration)
                && effectiveDeadline.equals(earlier(expectedDuration, policy.overallDeadline()))
                && effectiveDeadline.isAfter(startedAt), CORRUPT);
    }

    public static AttemptTiming capture(UUID attempt, UUID snapshot, TimingPolicy policy, Instant startedAt) {
        require(policy != null && policy.mode() == TimingMode.TOTAL_ATTEMPT && time(startedAt), INVALID);
        Instant durationEnd = durationEnd(startedAt, policy.duration());
        Instant end = earlier(durationEnd, policy.overallDeadline());
        require(end != null && end.isAfter(startedAt), CLOSED);
        return new AttemptTiming(VERSION, attempt, snapshot, policy, startedAt, durationEnd, end, Duration.ZERO);
    }

    /** Server receipt time, sampled before waiting for command locks. At the deadline is expired. */
    public Decision decide(Instant serverReceivedAt) {
        require(serverReceivedAt != null, INVALID);
        return serverReceivedAt.isBefore(startedAt) ? Decision.BEFORE_START
                : serverReceivedAt.isBefore(effectiveDeadline) ? Decision.OPEN : Decision.EXPIRED;
    }

    private static Instant durationEnd(Instant start, Duration duration) {
        if (duration == null) return null;
        try {
            Instant result = start.plus(duration);
            require(time(result), INVALID); return result;
        } catch (DateTimeException | ArithmeticException failure) { throw new AttemptTimingException(INVALID); }
    }
    private static Instant earlier(Instant duration, Instant overall) {
        return duration == null ? overall : overall == null || duration.isBefore(overall) ? duration : overall;
    }
    private static boolean time(Instant value) {
        return value != null && !value.isBefore(MIN) && !value.isAfter(MAX) && value.getNano() % 1_000 == 0;
    }
    @Override public String toString() { return "AttemptTiming[version=" + version + "]"; }
}
