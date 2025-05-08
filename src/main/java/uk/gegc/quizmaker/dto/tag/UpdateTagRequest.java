package uk.gegc.quizmaker.dto.tag;

import jakarta.validation.constraints.Size;

public record UpdateTagRequest(
        @Size(min = 3, max = 50, message = "Tag length must be between 3 and 50 characters")
        String name,

        @Size(max = 1000, message = "Tag description must be less than 1000 characters")
        String description
) {
}
