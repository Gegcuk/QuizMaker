package uk.gegc.quizmaker.dto.category;

import jakarta.validation.constraints.Size;

public record CreateCategoryRequest(
        @Size(min = 3, max = 100, message = "Category name length must be between 3 and 100 characters")
        String name,

        @Size(max = 1000, message = "Category description must be less than 1000 characters")
        String description
) {
}
