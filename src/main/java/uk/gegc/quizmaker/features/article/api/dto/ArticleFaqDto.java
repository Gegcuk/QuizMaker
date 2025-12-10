package uk.gegc.quizmaker.features.article.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "FAQ entry associated with an article")
public record ArticleFaqDto(
        @Schema(description = "Question text", example = "How many questions?")
        String question,

        @Schema(description = "Answer text", example = "2-3 pre, 4-6 post; keep it low-stakes.")
        String answer
) {
}
