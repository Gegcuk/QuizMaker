package uk.gegc.quizmaker.features.learning;

import java.time.Instant;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import uk.gegc.quizmaker.features.learning.application.LearningSchedulingStrategy;
import uk.gegc.quizmaker.features.learning.application.impl.FsrsSchedulingStrategyImpl;
import uk.gegc.quizmaker.features.learning.domain.model.FsrsManifest;
import uk.gegc.quizmaker.features.learning.domain.model.LearningSchedule.*;
import uk.gegc.quizmaker.features.learning.domain.model.LearningScheduleException;
import uk.gegc.quizmaker.features.learning.domain.model.LearningScheduleException.Reason;

import static org.assertj.core.api.Assertions.*;

final class FsrsFixtures {
    static final Instant TIME = Instant.parse("2026-10-24T09:00:00.123456789Z");
    static final Identity IDENTITY = new Identity(id(1), id(2), id(3), id(4), id(5));
    static final LearningSchedulingStrategy STRATEGY = new FsrsSchedulingStrategyImpl();

    static UUID id(long value) { return new UUID(0, value); }
    static State fresh(Instant time) { return STRATEGY.initial(IDENTITY, FsrsManifest.approved(), time); }
    static Review command(State state, Rating rating, Instant time) {
        return new Review(id(100 + state.sequence()), state.identity(), state.sequence(), rating, time);
    }
    static State apply(State state, Rating rating, Instant time) {
        return STRATEGY.review(state, command(state, rating, time)).state();
    }
    /** Existing #577 external-oracle tolerance: Math pow/exp may vary across JVM platforms.
     * Never use this for state serialization, persistence, command receipts or exact replay. */
    static void assertGoldenNumber(Double actual, String expected) {
        if (expected.equals("-")) assertThat(actual).isNull();
        else assertThat(actual).isCloseTo(Double.parseDouble(expected), within(1e-12));
    }
    static void rejected(ThrowingCallable work, Reason reason) {
        assertThatThrownBy(work).isInstanceOfSatisfying(LearningScheduleException.class, failure -> {
            assertThat(failure.reason()).isEqualTo(reason);
            assertThat(failure.getMessage()).isEqualTo(reason.name());
            assertThat(failure.getCause()).isNull();
            assertThat(failure.getSuppressed()).isEmpty();
        });
    }
}
