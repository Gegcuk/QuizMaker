package uk.gegc.quizmaker.features.result.application;

import java.time.Instant;
import java.util.UUID;
import uk.gegc.quizmaker.features.result.domain.model.ResultRelease;
import uk.gegc.quizmaker.features.result.domain.model.ResultReleaseException.Reason;
import uk.gegc.quizmaker.features.result.domain.model.release.ScheduledRelease;

/** Trusted internal boundary, disabled by absence of an automatic registrar/HTTP/scheduler.
 * The start/assignment consumer must supply its captured configuration, never an authoring head.
 * Every operation owns independent RC; caller rollback does not cancel committed worker work.
 * #668 owns actual formal-start composition, #707 scoped operator recovery/resumption. */
public interface ScheduledResultReleaseService {
    ScheduledRelease register(UUID submissionId, UUID expectedResultId, UUID capturedConfigurationId);
    Result execute(UUID submissionId);
    Batch runBatch(int limit, Cursor after);
    enum Outcome { RELEASED, REPLAYED, NOT_DUE, DEFERRED, REVIEW }
    record Result(Outcome outcome, ResultRelease release, Reason reason) { }
    record Cursor(Instant nextAt, UUID submissionId) {
        @Override public String toString() { return "ScheduledReleaseCursor"; }
    }
    record Batch(int examined, int released, int deferred, int review, Cursor next) { }
}
