package uk.gegc.quizmaker.features.article.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import uk.gegc.quizmaker.features.article.domain.model.ArticleStatus;

import java.time.Instant;
import java.util.List;

@Schema(description = "Payload for creating or updating an article")
public record ArticleUpsertRequest(
        @Schema(description = "URL-safe slug", example = "retrieval-practice-template")
        String slug,

        @Schema(description = "Title of the article", example = "Retrieval Practice Article Template that Drives Quiz Starts")
        String title,

        @Schema(description = "Meta/OG description", example = "Use this research-backed outline...")
        String description,

        @Schema(description = "Short summary for cards", example = "Why retrieval practice beats re-reading and how to ship a post fast.")
        String excerpt,

        @Schema(description = "Optional hero kicker", example = "Evidence-backed")
        String heroKicker,

        @Schema(description = "Tags associated with the article", example = "[\"Retrieval practice\", \"SEO template\"]")
        List<String> tags,

        @Schema(description = "Author info")
        ArticleAuthorDto author,

        @Schema(description = "Estimated reading time label", example = "8 minute read")
        String readingTime,

        @Schema(description = "Publish timestamp", example = "2024-12-10T00:00:00Z")
        Instant publishedAt,

        @Schema(description = "Publication status", example = "PUBLISHED")
        ArticleStatus status,

        @Schema(description = "Canonical URL (derived from slug if missing)", example = "https://www.quizzence.com/blog/retrieval-practice-template")
        String canonicalUrl,

        @Schema(description = "Open Graph image URL", example = "https://www.quizzence.com/images/retrieval-practice.png")
        String ogImage,

        @Schema(description = "Whether the page should be excluded from indexing", example = "false")
        Boolean noindex,

        @Schema(description = "Content grouping for analytics", defaultValue = "blog", example = "blog")
        String contentGroup,

        @Schema(description = "Primary CTA block")
        ArticleCallToActionDto primaryCta,

        @Schema(description = "Secondary CTA block")
        ArticleCallToActionDto secondaryCta,

        @Schema(description = "Stats to render in highlights")
        List<ArticleStatDto> stats,

        @Schema(description = "Key points to emphasize in the article body")
        List<String> keyPoints,

        @Schema(description = "Optional checklist items for readers")
        List<String> checklist,

        @Schema(description = "Structured sections with summaries and content")
        List<ArticleSectionDto> sections,

        @Schema(description = "Frequently asked questions")
        List<ArticleFaqDto> faqs,

        @Schema(description = "References or sources")
        List<ArticleReferenceDto> references
) {
}
