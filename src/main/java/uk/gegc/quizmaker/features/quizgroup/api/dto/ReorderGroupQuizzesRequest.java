package uk.gegc.quizmaker.features.quizgroup.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.UUID;

@Schema(name = "ReorderGroupQuizzesRequest", description = "Payload for reordering quizzes in a group")
public record ReorderGroupQuizzesRequest(
        @Schema(description = "Ordered list of quiz UUIDs (new order)", example = "[\"quiz-id-1\", \"quiz-id-2\", \"quiz-id-3\"]")
        @NotEmpty(message = "Ordered quiz IDs list must not be empty")
        List<UUID> orderedQuizIds
) {
}

