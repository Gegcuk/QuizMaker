package uk.gegc.quizmaker.features.article.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(description = "Sitemap entry for blog content")
public record SitemapEntryDto(
        @Schema(description = "Absolute URL of the sitemap entry", example = "https://www.quizzence.com/blog/retrieval-practice-template")
        String url,

        @Schema(description = "Last updated timestamp", example = "2025-02-21T00:00:00Z")
        Instant updatedAt,

        @Schema(description = "Sitemap change frequency hint", example = "weekly")
        String changefreq,

        @Schema(description = "Sitemap priority between 0.0 and 1.0", example = "0.8")
        Double priority
) {
}
