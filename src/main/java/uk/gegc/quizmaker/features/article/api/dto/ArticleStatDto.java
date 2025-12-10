package uk.gegc.quizmaker.features.article.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Stat line displayed alongside an article")
public record ArticleStatDto(
        @Schema(description = "Label for the stat", example = "Retention lift")
        String label,

        @Schema(description = "Headline value", example = "+10-14 pp")
        String value,

        @Schema(description = "Detail or methodology", example = "Short-answer exam 81% vs 68% (delta +13 pp).")
        String detail,

        @Schema(description = "Optional source link", example = "https://pdf.retrievalpractice.org/guide/McDermott_etal_2014_JEPA.pdf")
        String link
) {
}
