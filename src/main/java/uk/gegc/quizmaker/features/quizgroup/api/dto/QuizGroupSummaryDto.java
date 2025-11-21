package uk.gegc.quizmaker.features.quizgroup.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import uk.gegc.quizmaker.features.quizgroup.domain.repository.projection.QuizGroupSummaryProjection;

import java.time.Instant;
import java.util.UUID;

@Schema(name = "QuizGroupSummaryDto", description = "Summary representation of a quiz group for list views")
public record QuizGroupSummaryDto(
        @Schema(description = "Group UUID", example = "d290f1ee-6c54-4b01-90e6-d701748f0851")
        UUID id,

        @Schema(description = "Name of the quiz group", example = "My Study Group")
        String name,

        @Schema(description = "Description of the quiz group", example = "Quizzes for Chapter 1")
        String description,

        @Schema(description = "Color for the group", example = "#FF5733")
        String color,

        @Schema(description = "Icon identifier for the group", example = "book")
        String icon,

        @Schema(description = "Timestamp when the group was created", example = "2025-05-01T15:30:00Z")
        Instant createdAt,

        @Schema(description = "Timestamp when the group was last updated", example = "2025-05-10T12:00:00Z")
        Instant updatedAt,

        @Schema(description = "Number of quizzes in the group", example = "5")
        Long quizCount
) {
    /**
     * Factory method to create from domain projection.
     * No type conversion needed - projection already returns correct types.
     */
    public static QuizGroupSummaryDto fromProjection(QuizGroupSummaryProjection projection) {
        return new QuizGroupSummaryDto(
                projection.getId(),
                projection.getName(),
                projection.getDescription(),
                projection.getColor(),
                projection.getIcon(),
                projection.getCreatedAt(),
                projection.getUpdatedAt(),
                projection.getQuizCount()
        );
    }
}

