package uk.gegc.quizmaker.features.learning.domain.repository;

import java.util.Optional;
import java.util.UUID;
import java.time.Instant;
import uk.gegc.quizmaker.features.learning.domain.model.LearningSchedule.Snapshot;
import uk.gegc.quizmaker.features.learning.domain.model.LearningSession.Source;
import uk.gegc.quizmaker.features.learning.domain.model.LearningSchedule.Transition;

/** Requires an authorized RC transaction. The strategy-neutral slot is the one common active-cycle
 * authority, including future custom writers. Lock it before cycle/state/capture changes. */
public interface LearningScheduleRepository {
    record Key(UUID learnerId, Source source, UUID questionRevisionId) {
        @Override public String toString() { return "LearningScheduleKey[redacted]"; }
    }
    record Receipt(UUID reservationId, String input, Snapshot result) {
        @Override public String toString() { return "LearningScheduleReceipt[redacted]"; }
    }
    record ActiveCycle(UUID id, long version, String strategyId) {
        @Override public String toString() { return "ActiveLearningCycle[version=" + version + "]"; }
    }
    boolean sourceExists(Key key);
    Receipt reserve(Key key, UUID commandId, UUID reservationId, String input);
    void complete(Key key, UUID commandId, UUID reservationId, Snapshot result);
    Optional<Snapshot> find(Key key);
    Optional<Snapshot> lock(Key key);
    Optional<ActiveCycle> lockCycle(Key key);
    /** Strategy-neutral CAS under the slot lock; future custom writers persist their own state
     * in this same transaction. This does not select/implement a strategy or finish a session. */
    void replaceCycle(Key key, ActiveCycle next, long expectedVersion, UUID commandId, Instant createdAt);
    void createCycle(Key key, Snapshot next, long expectedVersion, UUID commandId);
    void advance(Key key, Snapshot previous, Transition transition);
    boolean hasCaptures(Key key);
    boolean sessionContains(Key key, UUID sessionId);
    void capture(Key key, Snapshot current, UUID sessionId);
    void release(Key key, Snapshot current, UUID sessionId);
}
