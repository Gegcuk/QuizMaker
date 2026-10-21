package uk.gegc.quizmaker.features.quiz.domain.model.revision;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/** Stored delivery content. Publication, current pointers and attempt binding are separate use cases. */
public record QuizRevision(
        UUID id, UUID quizId, UUID ownerId, int schemaVersion, String title, String description,
        Instant createdAt, List<Item> items
) {
    public QuizRevision {
        RevisionValues.require(id != null && quizId != null && ownerId != null);
        RevisionValues.version(schemaVersion);
        title = RevisionValues.text(title, 100, true);
        description = RevisionValues.text(description, 1000, false);
        createdAt = RevisionValues.timestamp(createdAt);
        RevisionValues.require(items != null && items.stream().allMatch(item -> item != null));
        var ids = new HashSet<UUID>();
        var positions = new HashSet<Integer>();
        var questions = new HashMap<UUID, QuestionRevision>();
        items.forEach(item -> {
            RevisionValues.require(ids.add(item.id()) && positions.add(item.authoredPosition()));
            QuestionRevision previous = questions.putIfAbsent(item.question().id(), item.question());
            RevisionValues.require(previous == null || previous.equals(item.question()));
        });
        items = items.stream().sorted(Comparator.comparingInt(Item::authoredPosition)).toList();
    }

    public record Item(UUID id, int authoredPosition, QuestionRevision question) {
        public Item {
            RevisionValues.require(id != null && authoredPosition >= 0 && question != null);
        }
    }
}
