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
        Instant createdAt, List<Item> items, List<RevisionSection> sections
) {
    /** Existing callers create the original flat format only. Structured content must be explicit. */
    public QuizRevision(UUID id, UUID quizId, UUID ownerId, int schemaVersion, String title, String description,
                        Instant createdAt, List<Item> items) {
        this(id, quizId, ownerId, flatVersion(schemaVersion), title, description, createdAt, items, List.of());
    }

    public QuizRevision {
        RevisionValues.require(id != null && quizId != null && ownerId != null);
        if (schemaVersion != 1 && schemaVersion != 2)
            throw new RevisionIntegrityException(RevisionIntegrityException.Reason.UNSUPPORTED_VERSION);
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
        RevisionValues.require(sections != null && sections.stream().allMatch(section -> section != null)
                && (schemaVersion == 1 ? sections.isEmpty() : !sections.isEmpty()));
        var sectionIds = new HashSet<UUID>();
        var sectionPositions = new HashSet<Integer>();
        sections.forEach(section -> RevisionValues.require(sectionIds.add(section.id())
                && sectionPositions.add(section.authoredPosition())));
        sections = sections.stream().sorted(Comparator.comparingInt(RevisionSection::authoredPosition)).toList();
        // Exactly one binding per item, in the same total order. No hidden reordering or partial partition.
        if (schemaVersion == 2) RevisionValues.require(sections.stream().flatMap(section -> section.itemIds().stream())
                .toList().equals(items.stream().map(Item::id).toList()));
    }

    public boolean structured() { return schemaVersion == 2; }

    private static int flatVersion(int version) { RevisionValues.version(version); return version; }

    public record Item(UUID id, int authoredPosition, QuestionRevision question) {
        public Item {
            RevisionValues.require(id != null && authoredPosition >= 0 && question != null);
        }
    }
}
