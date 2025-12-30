package uk.gegc.quizmaker.features.article.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import uk.gegc.quizmaker.features.article.domain.model.ArticleContentType;
import uk.gegc.quizmaker.features.article.domain.model.ArticleStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(description = "Lightweight article payload for list and sitemap endpoints")
public record ArticleListItemDto(
        @Schema(description = "Article identifier", example = "a7f9f0d2-7c4c-4a5e-9e9b-2f5c7b1d2a4c")
        UUID id,

        @Schema(description = "URL-safe slug", example = "retrieval-practice-template")
        String slug,

        @Schema(description = "Article title", example = "Retrieval Practice Article Template that Drives Quiz Starts")
        String title,

        @Schema(description = "Meta/OG description", example = "Use this research-backed outline...")
        String description,

        @Schema(description = "Short summary for cards", example = "Why retrieval practice beats re-reading and how to ship a post fast.")
        String excerpt,

        @Schema(description = "Optional hero kicker", example = "Evidence-backed")
        String heroKicker,

        @Schema(description = "Hero image reference with alt/caption")
        ArticleImageDto heroImage,

        @Schema(description = "Tag names", example = "[\"Retrieval practice\", \"Teaching tips\"]")
        List<String> tags,

        @Schema(description = "Author details", requiredMode = Schema.RequiredMode.REQUIRED)
        ArticleAuthorDto author,

        @Schema(description = "Estimated reading time label", example = "8 minute read")
        String readingTime,

        @Schema(description = "Published timestamp", example = "2024-12-10T00:00:00Z")
        Instant publishedAt,

        @Schema(description = "Last updated timestamp", example = "2025-02-21T00:00:00Z")
        Instant updatedAt,

        @Schema(description = "Publication status", example = "PUBLISHED")
        ArticleStatus status,

        @Schema(description = "Content grouping for analytics", example = "blog")
        ArticleContentType contentGroup,

        @Schema(description = "Canonical URL if provided", example = "https://www.quizzence.com/blog/retrieval-practice-template")
        String canonicalUrl,

        @Schema(description = "Open Graph image URL", example = "https://www.quizzence.com/images/retrieval-practice.png")
        String ogImage,

        @Schema(description = "Whether the page should be excluded from indexing", example = "false")
        Boolean noindex,

        @Schema(description = "Primary CTA configuration", requiredMode = Schema.RequiredMode.REQUIRED)
        ArticleCallToActionDto primaryCta,

        @Schema(description = "Secondary CTA configuration", requiredMode = Schema.RequiredMode.REQUIRED)
        ArticleCallToActionDto secondaryCta,

        @Schema(description = "Revision for cache revalidation", example = "3")
        Integer revision
) {
}
