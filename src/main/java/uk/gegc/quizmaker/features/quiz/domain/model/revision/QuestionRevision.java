package uk.gegc.quizmaker.features.quiz.domain.model.revision;

import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/** Immutable content, independent of the mutable authoring Question entity and grading registry. */
public record QuestionRevision(
        UUID id, UUID questionId, int schemaVersion, QuestionType type, Difficulty difficulty,
        String questionText, String content, String hint, String explanation,
        String attachmentUrl, UUID attachmentAssetId, Instant createdAt, List<Option> options
) {
    public QuestionRevision {
        RevisionValues.require(id != null && questionId != null && type != null && difficulty != null);
        RevisionValues.version(schemaVersion);
        questionText = RevisionValues.text(questionText, 1000, true);
        content = RevisionValues.json(content);
        hint = RevisionValues.text(hint, 500, false);
        explanation = RevisionValues.text(explanation, 2000, false);
        attachmentUrl = RevisionValues.text(attachmentUrl, 2048, false);
        createdAt = RevisionValues.timestamp(createdAt);
        RevisionValues.require(options != null && options.stream().allMatch(option -> option != null));
        var ids = new HashSet<UUID>();
        var keys = new HashSet<String>();
        var positions = new HashSet<Integer>();
        options.forEach(option -> RevisionValues.require(ids.add(option.id())
                && keys.add(option.sourceKey()) && positions.add(option.authoredPosition())));
        options = options.stream().sorted(Comparator.comparingInt(Option::authoredPosition)).toList();
    }

    /** The family adapter owns source-key interpretation; this layer never derives IDs from positions. */
    public record Option(UUID id, String sourceKey, int authoredPosition) {
        public Option {
            RevisionValues.require(id != null && authoredPosition >= 0);
            sourceKey = RevisionValues.text(sourceKey, 255, true);
        }
    }
}
