package uk.gegc.quizmaker.features.learning.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Read-only question/cycle provenance. Neither a fixed form nor permission to answer an item.
 * One item represents one quiz-local question revision, including repeated source occurrences. */
public record LearningDueSelection(int version, LearningSession.Source source, Instant selectedAt,
                                   int requestedSize, List<Item> items, Outcome outcome, Cursor next) {
    public enum Outcome { READY, PARTIAL, EMPTY }
    public record Item(UUID questionRevisionId, LearningSchedule.Expected expected, Instant dueAt) {
        @Override public String toString() { return "DueLearningItem[redacted]"; }
    }
    /** Keyset position, not a snapshot, access grant, command identity or commit high-water mark. */
    public record Cursor(int version, LearningSession.Source source, Instant dueAt, UUID questionRevisionId) {
        @Override public String toString() { return "LearningDueCursor[version=" + version + "]"; }
    }
    public LearningDueSelection { items = List.copyOf(items); }
    @Override public String toString() { return "LearningDueSelection[version=" + version + ",outcome=" + outcome + "]"; }
}
