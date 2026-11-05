package uk.gegc.quizmaker.features.quiz.domain.model.revision;

import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import java.time.Instant;
import java.util.*;
import static uk.gegc.quizmaker.features.quiz.domain.model.revision.PublicationException.Reason.*;
import static uk.gegc.quizmaker.features.quiz.domain.model.revision.PublicationException.require;

public final class QuizPublication {
    private QuizPublication() { }
    /** Explicit author order: the legacy draft's Set is never an ordering authority. Version zero means unpublished. */
    public record Command(int version, UUID quizId, UUID commandId, long expectedVersion, List<UUID> questionOrder) {
        public Command {
            require(version == 1, UNSUPPORTED_VERSION);
            require(quizId != null && commandId != null && expectedVersion >= 0 && questionOrder != null
                    && !questionOrder.isEmpty() && questionOrder.stream().allMatch(Objects::nonNull), INVALID_INPUT);
            require(new HashSet<>(questionOrder).size() == questionOrder.size(), INVALID_INPUT);
            questionOrder = List.copyOf(questionOrder);
        }
    }
    public record Revision(UUID id, UUID quizId, UUID publishedBy, int canonicalVersion,
                           String contentHash, Instant publishedAt) {
        public Revision {
            require(canonicalVersion == 1, UNSUPPORTED_VERSION);
            require(id != null && quizId != null && publishedBy != null && publishedAt != null
                    && contentHash != null && contentHash.matches("[0-9a-f]{64}"), INVALID_CONTENT);
        }
    }
    public record Current(long version, Revision revision) {
        public Current { require(version > 0 && revision != null, INVALID_CONTENT); }
    }
    public record Result(Current publication, boolean replayed) { }
    public record Receipt(String requestHash, Current publication) { }
    public record Image(UUID questionRevisionId, UUID assetId, UUID bindingId, UUID versionId,
                        String objectKey, String mimeType, long sizeBytes, int width, int height) {
        public Image {
            require(questionRevisionId != null && assetId != null && bindingId != null && versionId != null
                    && objectKey != null && !objectKey.isBlank() && mimeType != null
                    && sizeBytes > 0 && width > 0 && height > 0, IMAGE_UNAVAILABLE);
        }
    }
    public record PublishedContent(Revision revision, QuizRevision content, List<Image> images) {
        public PublishedContent { images = List.copyOf(images); }
    }
    public record Actor(UUID id, Set<String> permissions) {
        public Actor { permissions = Set.copyOf(permissions); }
        public boolean elevated() { return permissions.contains("QUIZ_MODERATE") || permissions.contains("QUIZ_ADMIN"); }
    }
    public record Draft(UUID id, UUID ownerId, String title, String description, String visibility, int estimatedMinutes) { }
    public record Question(UUID id, QuestionType type, Difficulty difficulty, String text, String content,
                           String hint, String explanation, String attachmentUrl, UUID attachmentAssetId) { }
    public record Materialized(String hash, QuizRevision revision, List<Image> images) { }
}
