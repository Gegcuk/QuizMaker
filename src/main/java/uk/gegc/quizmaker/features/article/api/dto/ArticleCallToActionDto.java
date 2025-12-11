package uk.gegc.quizmaker.features.article.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Call-to-action button or link for the article")
public record ArticleCallToActionDto(
        @Schema(description = "CTA label", example = "Start a sample quiz")
        String label,

        @Schema(description = "CTA target URL or path", example = "/register?intent=sample-quiz")
        String href,

        @Schema(description = "Optional analytics event name", example = "cta_try_sample_quiz")
        String eventName
) {
}
