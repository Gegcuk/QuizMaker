package uk.gegc.quizmaker.features.quizgroup.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.UUID;

@Schema(name = "AddQuizzesToGroupRequest", description = "Payload for adding quizzes to a group")
public record AddQuizzesToGroupRequest(
        @Schema(description = "List of quiz UUIDs to add to the group", example = "[\"d290f1ee-6c54-4b01-90e6-d701748f0851\"]")
        @NotEmpty(message = "Quiz IDs list must not be empty")
        List<UUID> quizIds,

        @Schema(description = "Optional position to insert at (defaults to end)", example = "0")
        Integer position
) {
}

