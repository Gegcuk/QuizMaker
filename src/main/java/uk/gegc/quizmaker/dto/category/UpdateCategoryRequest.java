package uk.gegc.quizmaker.dto.category;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

@Schema(description = "Request payload for updating an existing category")
public record UpdateCategoryRequest(
        @Schema(
                description = "Updated name of the category",
                example = "History",
                minLength = 3,
                maxLength = 100
        )
        @Size(min = 3, max = 100, message = "Category name length must be between 3 and 100 characters")
        String name,

        @Schema(
                description = "Updated description of the category",
                example = "Historical events and figures",
                maxLength = 1000
        )
        @Size(max = 1000, message = "Category description must be less than 1000 characters")
        String description
) {
}