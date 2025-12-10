package uk.gegc.quizmaker.features.article.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Section of an article with optional summary and body content")
public record ArticleSectionDto(
        @Schema(description = "Stable section identifier", example = "research")
        String sectionId,

        @Schema(description = "Section title", example = "Evidence in plain English")
        String title,

        @Schema(description = "Optional section summary", example = "What the testing effect shows")
        String summary,

        @Schema(description = "Section content in HTML or Markdown", example = "<p>Retrieval beats rereading...</p>")
        String content
) {
}
