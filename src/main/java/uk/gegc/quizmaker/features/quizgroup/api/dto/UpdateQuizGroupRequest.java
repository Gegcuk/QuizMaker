package uk.gegc.quizmaker.features.quizgroup.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

@Schema(name = "UpdateQuizGroupRequest", description = "Payload for updating a quiz group")
public record UpdateQuizGroupRequest(
        @Schema(description = "Name of the quiz group", example = "My Updated Study Group")
        @Size(min = 1, max = 100, message = "Name length must be between 1 and 100 characters")
        String name,

        @Schema(description = "Description of the quiz group", example = "Updated description")
        @Size(max = 500, message = "Description must be at most 500 characters long")
        String description,

        @Schema(description = "Color for the group (hex or name)", example = "#33FF57")
        @Size(max = 20, message = "Color must be at most 20 characters long")
        String color,

        @Schema(description = "Icon identifier for the group", example = "folder")
        @Size(max = 50, message = "Icon must be at most 50 characters long")
        String icon
) {
}

