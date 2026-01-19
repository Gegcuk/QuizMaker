package uk.gegc.quizmaker.features.quiz.api.dto.imports;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

@Schema(name = "ImportErrorDto", description = "Error details for a single imported item")
public record ImportErrorDto(
    int index,
    UUID itemId,
    String field,
    String message,
    String code
) {
}
