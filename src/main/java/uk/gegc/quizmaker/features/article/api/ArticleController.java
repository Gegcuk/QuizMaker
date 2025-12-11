package uk.gegc.quizmaker.features.article.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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
import uk.gegc.quizmaker.features.article.domain.model.ArticleStatus;
import uk.gegc.quizmaker.features.user.domain.model.PermissionName;
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

    @Operation(summary = "Search articles")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Articles found",
                    content = @Content(schema = @Schema(implementation = Page.class))),
            @ApiResponse(responseCode = "403", description = "Missing ARTICLE_READ permission",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping
    @RequirePermission(PermissionName.ARTICLE_READ)
    public Page<ArticleListItemDto> searchArticles(
            @Parameter(description = "Filter by status") @RequestParam(required = false) ArticleStatus status,
            @Parameter(description = "Filter by tags") @RequestParam(required = false) List<String> tags,
            @Parameter(description = "Filter by content group") @RequestParam(required = false, defaultValue = "blog") String contentGroup,
            @PageableDefault(size = 20) Pageable pageable) {
        ArticleSearchCriteria criteria = new ArticleSearchCriteria(status, tags, contentGroup);
        return articleService.searchArticles(criteria, pageable);
    }

    @Operation(summary = "Public search articles", description = "Public endpoint returning published articles")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Articles found",
                    content = @Content(schema = @Schema(implementation = Page.class)))
    })
    @GetMapping("/public")
    public Page<ArticleListItemDto> searchPublicArticles(
            @Parameter(description = "Filter by tags") @RequestParam(required = false) List<String> tags,
            @Parameter(description = "Filter by content group") @RequestParam(required = false, defaultValue = "blog") String contentGroup,
            @PageableDefault(size = 20) Pageable pageable,
            HttpServletRequest request) {
        rateLimitService.checkRateLimit("articles-public-search", trustedProxyUtil.getClientIp(request), 120);
        ArticleSearchCriteria criteria = new ArticleSearchCriteria(ArticleStatus.PUBLISHED, tags, contentGroup);
        return articleService.searchArticles(criteria, pageable);
    }

    @Operation(summary = "Get article by ID")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Article found",
                    content = @Content(schema = @Schema(implementation = ArticleDto.class))),
            @ApiResponse(responseCode = "403", description = "Missing ARTICLE_READ permission",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "Article not found",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
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
                    content = @Content(schema = @Schema(implementation = ArticleDto.class))),
            @ApiResponse(responseCode = "403", description = "Missing ARTICLE_READ permission",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "Article not found",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping("/slug/{slug}")
    @RequirePermission(PermissionName.ARTICLE_READ)
    public ArticleDto getArticleBySlug(
            @Parameter(description = "Article slug") @PathVariable String slug,
            @RequestParam(defaultValue = "false") boolean includeDrafts) {
        return articleService.getArticleBySlug(slug, includeDrafts);
    }

    @Operation(summary = "Get article by slug (public)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Article found",
                    content = @Content(schema = @Schema(implementation = ArticleDto.class))),
            @ApiResponse(responseCode = "404", description = "Article not found",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
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
                    content = @Content(schema = @Schema(implementation = ArticleDto.class))),
            @ApiResponse(responseCode = "403", description = "Missing ARTICLE_READ permission",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PostMapping("/batch")
    @RequirePermission(PermissionName.ARTICLE_READ)
    public List<ArticleDto> getArticlesByIds(@RequestBody List<UUID> ids) {
        return articleService.getArticlesByIds(ids);
    }

    @Operation(summary = "Create article")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Article created",
                content = @Content(schema = @Schema(implementation = ArticleDto.class))),
        @ApiResponse(responseCode = "400", description = "Validation failed",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "403", description = "Missing ARTICLE_CREATE permission",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
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
                    content = @Content(schema = @Schema(implementation = ArticleDto.class))),
            @ApiResponse(responseCode = "400", description = "Validation failed",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = "Missing ARTICLE_CREATE permission",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
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
                    content = @Content(schema = @Schema(implementation = ArticleDto.class))),
            @ApiResponse(responseCode = "400", description = "Validation failed",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = "Missing ARTICLE_UPDATE permission",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "Article not found",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
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
                    content = @Content(schema = @Schema(implementation = ArticleDto.class))),
            @ApiResponse(responseCode = "400", description = "Validation failed",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = "Missing ARTICLE_UPDATE permission",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
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

    @Operation(summary = "Get sitemap entries")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Sitemap entries retrieved",
                    content = @Content(schema = @Schema(implementation = SitemapEntryDto.class)))
    })
    @GetMapping("/sitemap")
    public List<SitemapEntryDto> getSitemapEntries(
            @RequestParam(required = false) ArticleStatus status) {
        return articleService.getSitemapEntries(status);
    }
}
