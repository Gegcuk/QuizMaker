package uk.gegc.quizmaker.features.article.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Reference or source cited in the article")
public record ArticleReferenceDto(
        @Schema(description = "Display title", example = "McDermott et al., 2014")
        String title,

        @Schema(description = "Source URL", example = "https://pdf.retrievalpractice.org/guide/McDermott_etal_2014_JEPA.pdf")
        String url,

        @Schema(description = "Optional source type", example = "journal")
        String sourceType
) {
}
