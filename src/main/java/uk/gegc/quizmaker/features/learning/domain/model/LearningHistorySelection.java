package uk.gegc.quizmaker.features.learning.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static uk.gegc.quizmaker.features.learning.domain.model.LearningSessionException.Reason.*;
import static uk.gegc.quizmaker.features.learning.domain.model.LearningSessionException.require;

/** Read-only selection evidence, never a fixed form, schedule or permission to answer. */
public record LearningHistorySelection(int version, LearningSession.Source source, Mode mode, Instant selectedAt,
        int requestedSize, Availability availability, Outcome outcome, List<Item> items, Cursor next) {
    public enum Mode { INCORRECT_ONLY, UNSEEN_ONLY }
    public enum Availability { COMPLETE, LEGACY_UNKNOWN, SOURCE_UNAVAILABLE, UNSUPPORTED }
    public enum Outcome { READY, PARTIAL, EMPTY }

    /** Latest accepted answer in a completed assessment; null for an evidenced absence of answers. */
    public record Evidence(UUID attemptId, UUID resultId, UUID responseId, Instant acceptedAt) {
        public Evidence {
            require(attemptId != null && resultId != null && responseId != null && acceptedAt != null, CORRUPT);
        }
        @Override public String toString() { return "LearningHistoryEvidence[redacted]"; }
    }
    public record Item(UUID sourceItemId, UUID questionRevisionId, int authoredPosition, Evidence evidence) {
        public Item {
            require(sourceItemId != null && questionRevisionId != null && authoredPosition >= 0, CORRUPT);
        }
        @Override public String toString() { return "LearningHistoryItem[redacted]"; }
    }
    /** A position in current history, not a snapshot, access grant or persisted selection. */
    public record Cursor(int version, LearningSession.Source source, Mode mode, int authoredPosition) {
        @Override public String toString() { return "LearningHistoryCursor[redacted]"; }
    }
    public record Batch(Availability availability, List<Item> items) {
        public Batch {
            require(availability != null && items != null, CORRUPT);
            items = List.copyOf(items);
            require(availability == Availability.COMPLETE || items.isEmpty(), CORRUPT);
        }
        @Override public String toString() { return "LearningHistoryBatch[availability=" + availability + "]"; }
    }
    public LearningHistorySelection { items = List.copyOf(items); }
    @Override public String toString() {
        return "LearningHistorySelection[version=" + version + ",mode=" + mode + ",availability=" + availability + ",outcome=" + outcome + "]";
    }
}
