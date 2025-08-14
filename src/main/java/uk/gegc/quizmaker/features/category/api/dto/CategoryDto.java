package uk.gegc.quizmaker.dto.category;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

@Schema(description = "Category DTO")
public record CategoryDto(
        @Schema(description = "Category ID", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
        UUID id,

        @Schema(description = "Category name", example = "General")
        String name,

        @Schema(description = "Category description", example = "General knowledge topics")
        String description
) {
}