package uk.gegc.quizmaker.dto.tag;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

@Schema(description = "Data Transfer Object for Tag")
public record TagDto(
        @Schema(description = "Unique identifier of the tag", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
        UUID id,

        @Schema(description = "Name of the tag", example = "math")
        String name,

        @Schema(description = "Optional description of the tag", example = "Questions related to mathematics")
        String description
) {}