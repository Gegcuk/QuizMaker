package uk.gegc.quizmaker.features.quiz.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Schema(name = "BulkQuizUpdateOperationResultDto", description = "Outcome of a bulk operation")
public record BulkQuizUpdateOperationResultDto(
        @Schema(
                description = "Quiz UUIDs successfully updated. This can be empty when every requested ID fails.",
                example = "[\"d290f1ee-6c54-4b01-90e6-d701748f0851\"]"
        )
        List<UUID> successfulIds,

        @Schema(
                description = "Object keyed by the requested quiz UUID. Each string value is the reason that particular quiz was not updated. This can be empty when every requested ID succeeds.",
                additionalPropertiesSchema = String.class,
                example = "{\"f3e2d1c0-b9a8-4765-8432-10fedcba9876\":\"Quiz not found\"}"
        )
        Map<UUID, String> failures
) {
}
