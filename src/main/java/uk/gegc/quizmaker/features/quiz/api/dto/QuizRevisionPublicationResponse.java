package uk.gegc.quizmaker.features.quiz.api.dto;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.QuizPublication;

import java.util.List;
import java.util.UUID;

@Schema(name = "QuizRevisionPublicationResponse",
        requiredProperties = {"quizId", "quizRevisionId", "revisionSchemaVersion", "publicationVersion", "questionRevisionIds"})
public record QuizRevisionPublicationResponse(
        UUID quizId,
        UUID quizRevisionId,
        @Schema(description = "Format version of the immutable quiz revision.", minimum = "1")
        int revisionSchemaVersion,
        @Schema(description = "Publication counter acknowledged by this command.", minimum = "1")
        long publicationVersion,
        @ArraySchema(minItems = 1, arraySchema = @Schema(description = "Immutable question revision UUIDs in the saved order."),
                schema = @Schema(type = "string", format = "uuid"))
        List<UUID> questionRevisionIds) {

    public static QuizRevisionPublicationResponse from(QuizPublication.Result result) {
        var publication = result.publication();
        var revision = publication.revision();
        var items = result.revisionItems();
        return new QuizRevisionPublicationResponse(revision.quizId(), revision.id(), items.schemaVersion(),
                publication.version(), items.questionRevisionIds());
    }
}
