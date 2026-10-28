package uk.gegc.quizmaker.features.attempt.domain.repository;

import uk.gegc.quizmaker.features.attempt.domain.model.trace.ExecutionTrace.*;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Internal transactional storage only. No participant/operator access or unbounded reads. */
public interface ExecutionTraceRepository {
    void ensureStream(Scope scope);
    Optional<Stream> lockStream(UUID attemptId);
    Identity reserve(UUID attemptId, UUID eventId);
    boolean assign(UUID eventId, long sequence);
    Optional<Stored> findEvent(UUID eventId);
    Optional<Stored> last(UUID attemptId);
    void insert(Stored event);
    boolean advance(UUID attemptId, long expectedSequence);
    List<Stored> page(UUID attemptId, long after, int limit);

    record Identity(UUID attemptId, long sequence) {
        @Override public String toString() { return "TraceIdentity[sequence=" + sequence + "]"; }
    }

    record Stream(int version, Scope scope, long lastSequence) {
        @Override public String toString() { return "TraceStream[version=" + version + ",sequence=" + lastSequence + "]"; }
    }
}
