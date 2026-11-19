package uk.gegc.quizmaker.features.learning;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import uk.gegc.quizmaker.features.learning.domain.model.FsrsManifest;
import uk.gegc.quizmaker.features.learning.domain.model.LearningSession;
import uk.gegc.quizmaker.features.learning.domain.model.LearningSessionException;

import static org.assertj.core.api.Assertions.*;
import static uk.gegc.quizmaker.features.learning.domain.model.LearningSessionException.Reason.*;

class LearningSessionTest {
    private final UUID id = UUID.randomUUID(), learner = UUID.randomUUID(), command = UUID.randomUUID();
    private final LearningSession.Source source = new LearningSession.Source(1, UUID.randomUUID(), UUID.randomUUID(), 1);
    private final Instant time = Instant.parse("2026-11-19T09:00:00.123456Z");

    @Test void capturedSessionIsSeparateFromScheduleAndContainsOnlyBoundedMetadata() {
        var session = session(1, LearningSession.Status.CREATED, 1, time, time);
        assertThat(session.source()).isEqualTo(source);
        assertThat(session.configuration()).isEqualTo(FsrsManifest.approved());
        assertThat(session.learnerId()).isEqualTo(learner);
        assertThat(session.createCommandId()).isEqualTo(command);
        assertThat(session.toString()).doesNotContain(id.toString(), learner.toString(), command.toString());
        assertThat(source.toString()).doesNotContain(source.quizId().toString(), source.quizRevisionId().toString());
        assertThat(new LearningSessionException(CONFLICT)).hasMessage("CONFLICT").hasNoCause();
    }

    @ParameterizedTest @ValueSource(ints = {-1, 0, 2, 2147483647})
    void unknownSessionAndSourceFormatsHaveNoCurrentDefault(int version) {
        rejected(() -> session(version, LearningSession.Status.CREATED, 1, time, time), UNSUPPORTED_VERSION);
        rejected(() -> new LearningSession.Source(version, source.quizId(), source.quizRevisionId(), 1), UNSUPPORTED_VERSION);
    }

    @ParameterizedTest @ValueSource(ints = {-1, 0, 4, 2147483647})
    void unsupportedRevisionSchemaIsNotReinterpreted(int version) {
        rejected(() -> new LearningSession.Source(1, source.quizId(), source.quizRevisionId(), version), UNSUPPORTED_VERSION);
    }

    @Test void knownRevisionFormatsAndExactMicrosecondsAreRetained() {
        for (int version = 1; version <= 3; version++) {
            assertThat(new LearningSession.Source(1, source.quizId(), source.quizRevisionId(), version).revisionSchemaVersion())
                    .isEqualTo(version);
        }
        assertThat(session(1, LearningSession.Status.CREATED, 1, time, time).createdAt()).isEqualTo(time);
    }

    @Test void malformedIdentityLifecycleAndTimeFailClosed() {
        rejected(() -> new LearningSession.Source(1, null, source.quizRevisionId(), 1), INVALID_INPUT);
        rejected(() -> new LearningSession.Source(1, source.quizId(), null, 1), INVALID_INPUT);
        rejected(() -> session(1, null, 1, time, time), CORRUPT);
        for (long version : new long[]{-1, 0, 2, Long.MAX_VALUE})
            rejected(() -> session(1, LearningSession.Status.CREATED, version, time, time), CORRUPT);
        rejected(() -> session(1, LearningSession.Status.CREATED, 1, null, time), CORRUPT);
        rejected(() -> session(1, LearningSession.Status.CREATED, 1, time, null), CORRUPT);
        rejected(() -> session(1, LearningSession.Status.CREATED, 1, time, time.plusSeconds(1)), CORRUPT);
        var nanos = time.plusNanos(1);
        rejected(() -> session(1, LearningSession.Status.CREATED, 1, nanos, nanos), CORRUPT);
        for (var invalid : new Instant[]{Instant.MIN, Instant.MAX})
            rejected(() -> session(1, LearningSession.Status.CREATED, 1, invalid, invalid), CORRUPT);
        rejected(() -> new LearningSession(null, 1, learner, command, source, FsrsManifest.approved(),
                LearningSession.Status.CREATED, 1, time, time), CORRUPT);
        rejected(() -> new LearningSession(id, 1, null, command, source, FsrsManifest.approved(),
                LearningSession.Status.CREATED, 1, time, time), CORRUPT);
        rejected(() -> new LearningSession(id, 1, learner, null, source, FsrsManifest.approved(),
                LearningSession.Status.CREATED, 1, time, time), CORRUPT);
        rejected(() -> new LearningSession(id, 1, learner, command, null, FsrsManifest.approved(),
                LearningSession.Status.CREATED, 1, time, time), CORRUPT);
        rejected(() -> new LearningSession(id, 1, learner, command, source, null,
                LearningSession.Status.CREATED, 1, time, time), CORRUPT);
    }

    private LearningSession session(int format, LearningSession.Status status, long version, Instant created, Instant updated) {
        return new LearningSession(id, format, learner, command, source, FsrsManifest.approved(), status, version, created, updated);
    }

    static void rejected(Runnable work, LearningSessionException.Reason reason) {
        assertThatThrownBy(work::run).isInstanceOfSatisfying(LearningSessionException.class,
                failure -> { assertThat(failure.reason()).isEqualTo(reason); assertThat(failure.getCause()).isNull(); });
    }
}
