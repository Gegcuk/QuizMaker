package uk.gegc.quizmaker.features.learning.domain.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;
import uk.gegc.quizmaker.features.learning.domain.model.LearningSchedule.Rating;

/** Learning-only progression. Session versions start at one and advance on acceptance or supersession. */
public final class LearningReview {
    private LearningReview() { }

    public enum Mode { DUE, EARLY, PRACTICE }
    public enum Status { ACTIVE, COMPLETED, SUPERSEDED }

    public record Command(UUID sessionId, UUID commandId, long expectedVersion, UUID sourceItemId,
                          Rating rating, JsonNode response) {
        @Override public String toString() { return "LearningResponse[redacted]"; }
    }

    public record Progress(int reviewedItems, UUID nextItemId, Status status) {
        public Progress(int reviewedItems, UUID nextItemId) {
            this(reviewedItems, nextItemId, nextItemId == null ? Status.COMPLETED : Status.ACTIVE);
        }

        public long version() { return (long) reviewedItems + (status == Status.SUPERSEDED ? 2 : 1); }
        public boolean completed() { return status == Status.COMPLETED; }
    }

    public record Acknowledgement(UUID sessionId, UUID commandId, UUID sourceItemId, Progress progress,
                                  Instant nextDueAt) { }
}
