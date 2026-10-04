package uk.gegc.quizmaker.features.article.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import uk.gegc.quizmaker.features.article.api.dto.*;
import uk.gegc.quizmaker.features.article.application.ArticleService;
import uk.gegc.quizmaker.features.article.domain.model.ArticleContentType;
import uk.gegc.quizmaker.features.article.domain.model.ArticleStatus;
import uk.gegc.quizmaker.features.user.domain.model.PermissionName;
import uk.gegc.quizmaker.shared.exception.ValidationException;
import uk.gegc.quizmaker.shared.rate_limit.RateLimitService;
import uk.gegc.quizmaker.shared.security.annotation.RequirePermission;
import uk.gegc.quizmaker.shared.util.TrustedProxyUtil;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/articles")
@Validated
@SecurityRequirement(name = "Bearer Authentication")
@Tag(name = "Articles", description = "Manage articles, tags, and sitemap entries")
public class ArticleController {

    private final ArticleService articleService;
    private final RateLimitService rateLimitService;
    private final TrustedProxyUtil trustedProxyUtil;

    public ArticleController(ArticleService articleService, RateLimitService rateLimitService, TrustedProxyUtil trustedProxyUtil) {
        this.articleService = articleService;
        this.rateLimitService = rateLimitService;
        this.trustedProxyUtil = trustedProxyUtil;
    }

    @Operation(summary = "Search articles", description = "Requires ARTICLE_READ. Defaults to published articles; "
            + "draft searches additionally require ARTICLE_UPDATE or ARTICLE_ADMIN. "
            + "Uses the existing Spring Data page envelope: page is zero-based, size defaults to 20, "
            + "and sort accepts property,direction (for example publishedAt,desc). "
            + "heroImage.rendition is derived, nullable public image metadata; unavailable media does not hide the article.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Articles found",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ArticlePageResponse.class))),
            @ApiResponse(responseCode = "403", description = "Missing ARTICLE_READ permission",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping
    @RequirePermission(PermissionName.ARTICLE_READ)
    public Page<ArticleListItemDto> searchArticles(
            @Parameter(description = "Filter by status") @RequestParam(required = false) ArticleStatus status,
            @Parameter(description = "Filter by tags") @RequestParam(required = false) List<String> tags,
            @Parameter(description = "Filter by content group") @RequestParam(required = false) String contentGroup,
            @PageableDefault(size = 20) Pageable pageable) {
        ArticleSearchCriteria criteria = new ArticleSearchCriteria(status, tags, resolveContentType(contentGroup));
        return articleService.searchArticles(criteria, pageable);
    }

    @Operation(
            summary = "Public search articles",
            description = "Anonymous endpoint returning only published articles. Optional tags and contentGroup filters; "
                    + "page is zero-based, size defaults to 20, sort accepts property,direction (for example publishedAt,desc). "
                    + "heroImage.rendition contains a public image URL, pixel dimensions and MIME type when available, "
                    + "otherwise null. Explicit ogImage values remain unchanged; clients may use the hero rendition as fallback. "
                    + "Unresolvable images do not fail the article response.",
            security = {}
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Articles found",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ArticlePageResponse.class))),
            @ApiResponse(responseCode = "400", description = "Invalid filter or pagination parameter",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "429", description = "Public article request limit exceeded; Retry-After gives seconds to wait",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping("/public")
    public Page<ArticleListItemDto> searchPublicArticles(
            @Parameter(description = "Filter by tags") @RequestParam(required = false) List<String> tags,
            @Parameter(description = "Filter by content group") @RequestParam(required = false) String contentGroup,
            @PageableDefault(size = 20) Pageable pageable,
            HttpServletRequest request) {
        rateLimitService.checkRateLimit("articles-public-search", trustedProxyUtil.getClientIp(request), 120);
        ArticleSearchCriteria criteria = new ArticleSearchCriteria(ArticleStatus.PUBLISHED, tags, resolveContentType(contentGroup));
        return articleService.searchArticles(criteria, pageable);
    }

    @Operation(summary = "Get article by ID")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Article found",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ArticleDto.class))),
            @ApiResponse(responseCode = "403", description = "Missing ARTICLE_READ permission",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "Article not found",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping("/{articleId}")
    @RequirePermission(PermissionName.ARTICLE_READ)
    public ArticleDto getArticle(
            @Parameter(description = "Article ID") @PathVariable UUID articleId,
            @RequestParam(defaultValue = "false") boolean includeDrafts) {
        return articleService.getArticle(articleId, includeDrafts);
    }

    @Operation(summary = "Get article by slug")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Article found",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ArticleDto.class))),
            @ApiResponse(responseCode = "403", description = "Missing ARTICLE_READ permission",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "Article not found",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping("/slug/{slug}")
    @RequirePermission(PermissionName.ARTICLE_READ)
    public ArticleDto getArticleBySlug(
            @Parameter(description = "Article slug") @PathVariable String slug,
            @RequestParam(defaultValue = "false") boolean includeDrafts) {
        return articleService.getArticleBySlug(slug, includeDrafts);
    }

    @Operation(
            summary = "Get article by slug (public)",
            description = "Anonymous read of a published article; drafts and missing articles return 404. "
                    + "heroImage.rendition is nullable, derived metadata for a ready public image. "
                    + "A missing, deleted, not-ready or non-image asset leaves rendition null and the article readable. "
                    + "An explicit ogImage is preserved; clients may fall back to heroImage.rendition.url. "
                    + "URLs follow the media storage lifecycle; previously cached copies may outlive deletion.",
            security = {}
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Article found",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ArticleDto.class))),
            @ApiResponse(responseCode = "404", description = "Article not found",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "429", description = "Public article request limit exceeded; Retry-After gives seconds to wait",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping("/public/slug/{slug}")
    public ArticleDto getArticleBySlugPublic(
            @Parameter(description = "Article slug") @PathVariable String slug,
            HttpServletRequest request) {
        rateLimitService.checkRateLimit("articles-public-get", trustedProxyUtil.getClientIp(request), 240);
        return articleService.getArticleBySlug(slug, false);
    }

    @Operation(summary = "Get articles by IDs")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Articles found",
                    content = @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = ArticleDto.class)))),
            @ApiResponse(responseCode = "403", description = "Missing ARTICLE_READ permission",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PostMapping("/batch")
    @RequirePermission(PermissionName.ARTICLE_READ)
    public List<ArticleDto> getArticlesByIds(@RequestBody List<UUID> ids) {
        return articleService.getArticlesByIds(ids);
    }

    @Operation(summary = "Create article")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Article created",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ArticleDto.class))),
        @ApiResponse(responseCode = "400", description = "Validation failed",
                content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "403", description = "Missing ARTICLE_CREATE permission",
                content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PostMapping
    @RequirePermission(PermissionName.ARTICLE_CREATE)
    public ResponseEntity<ArticleDto> createArticle(
            Authentication authentication,
            @Valid @RequestBody ArticleUpsertRequest request) {
        String username = authentication != null ? authentication.getName() : "system";
        ArticleDto dto = articleService.createArticle(username, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(dto);
    }

    @Operation(summary = "Create multiple articles")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Articles created",
                    content = @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = ArticleDto.class)))),
            @ApiResponse(responseCode = "400", description = "Validation failed",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = "Missing ARTICLE_CREATE permission",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PostMapping("/bulk")
    @RequirePermission(PermissionName.ARTICLE_CREATE)
    public ResponseEntity<List<ArticleDto>> createArticles(
            Authentication authentication,
            @Valid @RequestBody List<@Valid ArticleUpsertRequest> requests) {
        String username = authentication != null ? authentication.getName() : "system";
        List<ArticleDto> created = articleService.createArticles(username, requests);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @Operation(summary = "Update article")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Article updated",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ArticleDto.class))),
            @ApiResponse(responseCode = "400", description = "Validation failed",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = "Missing ARTICLE_UPDATE permission",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "Article not found",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PutMapping("/{articleId}")
    @RequirePermission(PermissionName.ARTICLE_UPDATE)
    public ArticleDto updateArticle(
            Authentication authentication,
            @Parameter(description = "Article ID") @PathVariable UUID articleId,
            @Valid @RequestBody ArticleUpsertRequest request) {
        String username = authentication != null ? authentication.getName() : "system";
        return articleService.updateArticle(username, articleId, request);
    }

    @Operation(summary = "Update multiple articles")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Articles updated",
                    content = @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = ArticleDto.class)))),
            @ApiResponse(responseCode = "400", description = "Validation failed",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = "Missing ARTICLE_UPDATE permission",
                    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PutMapping("/bulk")
    @RequirePermission(PermissionName.ARTICLE_UPDATE)
    public List<ArticleDto> updateArticles(
            Authentication authentication,
            @Valid @RequestBody List<@Valid ArticleBulkUpdateItem> updates) {
        String username = authentication != null ? authentication.getName() : "system";
        return articleService.updateArticles(username, updates);
    }

    @Operation(summary = "Delete article")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Article deleted"),
            @ApiResponse(responseCode = "403", description = "Missing ARTICLE_DELETE permission",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "Article not found",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @DeleteMapping("/{articleId}")
    @RequirePermission(PermissionName.ARTICLE_DELETE)
    public ResponseEntity<Void> deleteArticle(
            Authentication authentication,
            @Parameter(description = "Article ID") @PathVariable UUID articleId) {
        String username = authentication != null ? authentication.getName() : "system";
        articleService.deleteArticle(username, articleId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Delete multiple articles")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Articles deleted"),
            @ApiResponse(responseCode = "403", description = "Missing ARTICLE_DELETE permission",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @DeleteMapping
    @RequirePermission(PermissionName.ARTICLE_DELETE)
    public ResponseEntity<Void> deleteArticles(
            Authentication authentication,
            @RequestBody List<UUID> articleIds) {
        String username = authentication != null ? authentication.getName() : "system";
        articleService.deleteArticles(username, articleIds);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Get article tags with counts")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Tags retrieved",
                    content = @Content(schema = @Schema(implementation = ArticleTagWithCountDto.class))),
            @ApiResponse(responseCode = "403", description = "Missing ARTICLE_READ permission",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping("/tags")
    @RequirePermission(PermissionName.ARTICLE_READ)
    public List<ArticleTagWithCountDto> getTagsWithCounts(
            @RequestParam(required = false) ArticleStatus status) {
        return articleService.getTagsWithCounts(status);
    }

    @Operation(
            summary = "Get sitemap entries",
            security = {}
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Sitemap entries retrieved",
                    content = @Content(schema = @Schema(implementation = SitemapEntryDto.class)))
    })
    @GetMapping("/sitemap")
    public List<SitemapEntryDto> getSitemapEntries(
            @RequestParam(required = false) ArticleStatus status) {
        return articleService.getSitemapEntries(status);
    }

    private ArticleContentType resolveContentType(String rawContentType) {
        if (rawContentType == null || rawContentType.isBlank()) {
            return null; // Return null to indicate no filtering by content group
        }
        try {
            ArticleContentType resolved = ArticleContentType.fromValue(rawContentType);
            return resolved;
        } catch (IllegalArgumentException ex) {
            throw new ValidationException(ex.getMessage());
        }
    }
}
