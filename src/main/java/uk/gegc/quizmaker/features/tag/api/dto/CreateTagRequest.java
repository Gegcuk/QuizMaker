package uk.gegc.quizmaker.features.tag.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

@Schema(description = "Request payload to create a new tag")
public record CreateTagRequest(
        @Schema(description = "Name of the tag", example = "science")
        @Size(min = 3, max = 50, message = "Tag length must be between 3 and 50 characters")
        String name,

        @Schema(description = "Optional description", example = "Questions about scientific topics")
        @Size(max = 1000, message = "Category description must be less than 1000 characters")
        String description
) {
}