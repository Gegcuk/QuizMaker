package uk.gegc.quizmaker.features.attempt.domain.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import uk.gegc.quizmaker.features.attempt.domain.model.trace.ExecutionTrace.Scope;
import uk.gegc.quizmaker.features.attempt.domain.model.trace.TraceQuery.Entry;

/** Scalar read-only storage; only the protected application service may expose its projections. */
public interface TraceQueryRepository {
    Optional<Head> head(UUID attemptId);
    List<Entry> page(UUID attemptId, long after, long through, int limit);

    /** Root and physical tail are observed in one SQL snapshot, without writer locks. */
    record Head(int version, Scope scope, long sequence, long tail) {
        @Override public String toString() { return "TraceQueryHead[redacted]"; }
    }
}
