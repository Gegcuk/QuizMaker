package uk.gegc.quizmaker.features.category.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

@Schema(description = "Request payload for creating a new category")
public record CreateCategoryRequest(
        @Schema(
                description = "Name of the category",
                example = "Science",
                minLength = 3,
                maxLength = 100
        )
        @Size(min = 3, max = 100, message = "Category name length must be between 3 and 100 characters")
        String name,

        @Schema(
                description = "Description of the category",
                example = "All science-related quizzes",
                maxLength = 1000
        )
        @Size(max = 1000, message = "Category description must be less than 1000 characters")
        String description
) {
}