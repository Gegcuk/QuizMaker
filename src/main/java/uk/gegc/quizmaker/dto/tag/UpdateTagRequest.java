package uk.gegc.quizmaker.dto.tag;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

@Schema(description = "Request payload to update an existing tag")
public record UpdateTagRequest(
        @Schema(description = "New name for the tag", example = "history")
        @Size(min = 3, max = 50, message = "Tag length must be between 3 and 50 characters")
        String name,

        @Schema(description = "New description for the tag", example = "Questions about historical events")
        @Size(max = 1000, message = "Tag description must be less than 1000 characters")
        String description
) {}