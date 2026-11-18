package uk.gegc.quizmaker.features.result.domain.repository;

import java.time.Instant;
import java.util.*;
import uk.gegc.quizmaker.features.result.application.ScheduledResultReleaseService.Cursor;
import uk.gegc.quizmaker.features.result.domain.model.disclosure.ResultDisclosure.Binding;
import uk.gegc.quizmaker.features.result.domain.model.release.ScheduledRelease;

/** Caller-owned RC transactions. Source locking precedes work locking everywhere. */
public interface ScheduledReleaseRepository {
    Source lockSource(UUID submissionId);
    Optional<ScheduledRelease> find(UUID submissionId, boolean lock);
    void insert(ScheduledRelease work);
    void update(ScheduledRelease previous, ScheduledRelease next);
    List<Cursor> due(Instant now, int limit, Cursor after);
    Backlog backlog(Instant now);
    record Source(UUID submissionId, UUID resultId, Binding binding, UUID quizId, UUID ownerId,
                  UUID subjectId, Instant createdAt) {
        @Override public String toString() { return "ScheduledReleaseSource"; }
    }
    record Backlog(long count, Instant oldest) { }
}
