package uk.gegc.quizmaker.features.quizgroup.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

@Schema(name = "CreateQuizGroupRequest", description = "Payload for creating a quiz group")
public record CreateQuizGroupRequest(
        @Schema(description = "Name of the quiz group", example = "My Study Group")
        @NotBlank(message = "Name must not be blank")
        @Size(min = 1, max = 100, message = "Name length must be between 1 and 100 characters")
        String name,

        @Schema(description = "Description of the quiz group", example = "Quizzes for Chapter 1")
        @Size(max = 500, message = "Description must be at most 500 characters long")
        String description,

        @Schema(description = "Color for the group (hex or name)", example = "#FF5733")
        @Size(max = 20, message = "Color must be at most 20 characters long")
        String color,

        @Schema(description = "Icon identifier for the group", example = "book")
        @Size(max = 50, message = "Icon must be at most 50 characters long")
        String icon,

        @Schema(description = "Optional document UUID this group is linked to", example = "d290f1ee-6c54-4b01-90e6-d701748f0851")
        UUID documentId
) {
    public CreateQuizGroupRequest {
        description = (description == null ? null : description.trim().isEmpty() ? null : description);
        color = (color == null ? null : color.trim().isEmpty() ? null : color);
        icon = (icon == null ? null : icon.trim().isEmpty() ? null : icon);
    }
}

