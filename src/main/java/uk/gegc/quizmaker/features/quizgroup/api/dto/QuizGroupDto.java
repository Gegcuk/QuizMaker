package uk.gegc.quizmaker.features.quizgroup.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(name = "QuizGroupDto", description = "Representation of a quiz group")
public record QuizGroupDto(
        @Schema(description = "Group UUID", example = "d290f1ee-6c54-4b01-90e6-d701748f0851")
        UUID id,

        @Schema(description = "Owner user UUID", example = "123e4567-e89b-12d3-a456-426614174000")
        UUID ownerId,

        @Schema(description = "Name of the quiz group", example = "My Study Group")
        String name,

        @Schema(description = "Description of the quiz group", example = "Quizzes for Chapter 1")
        String description,

        @Schema(description = "Color for the group", example = "#FF5733")
        String color,

        @Schema(description = "Icon identifier for the group", example = "book")
        String icon,

        @Schema(description = "Optional document UUID this group is linked to", example = "d290f1ee-6c54-4b01-90e6-d701748f0851")
        UUID documentId,

        @Schema(description = "Number of quizzes in the group", example = "5")
        Long quizCount,

        @Schema(description = "Timestamp when the group was created", example = "2025-05-01T15:30:00Z")
        Instant createdAt,

        @Schema(description = "Timestamp when the group was last updated", example = "2025-05-10T12:00:00Z")
        Instant updatedAt
) {
}

