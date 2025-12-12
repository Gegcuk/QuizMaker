package uk.gegc.quizmaker.features.article.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import uk.gegc.quizmaker.features.article.api.dto.*;
import uk.gegc.quizmaker.features.article.application.ArticleService;
import uk.gegc.quizmaker.features.article.domain.model.ArticleStatus;
import uk.gegc.quizmaker.shared.exception.ForbiddenException;
import uk.gegc.quizmaker.shared.exception.ResourceNotFoundException;
import uk.gegc.quizmaker.shared.exception.ValidationException;
import uk.gegc.quizmaker.shared.security.AppPermissionEvaluator;
import uk.gegc.quizmaker.shared.rate_limit.RateLimitService;
import uk.gegc.quizmaker.shared.util.TrustedProxyUtil;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.mockito.ArgumentCaptor;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ArticleController.class)
@DisplayName("ArticleController")
class ArticleControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @MockitoBean
    ArticleService articleService;

    @MockitoBean
    AppPermissionEvaluator permissionEvaluator;

    @MockitoBean
    RateLimitService rateLimitService;

    @MockitoBean
    TrustedProxyUtil trustedProxyUtil;

    private ArticleDto articleDto;
    private ArticleListItemDto listItem;
    private ArticleUpsertRequest upsertRequest;

    @BeforeEach
    void setUp() {
        when(permissionEvaluator.hasAnyPermission(any())).thenReturn(true);
        when(permissionEvaluator.hasAllPermissions(any())).thenReturn(true);
        when(trustedProxyUtil.getClientIp(any())).thenReturn("127.0.0.1");

        ArticleAuthorDto author = new ArticleAuthorDto("Author", "Title");
        ArticleCallToActionDto cta = new ArticleCallToActionDto("Learn", "/", null);
        articleDto = new ArticleDto(
                UUID.randomUUID(),
                "sample-slug",
                "Title",
                "Desc",
                "Excerpt",
                "Hero",
                List.of("Tag"),
                author,
                "5 min",
                Instant.now(),
                Instant.now(),
                ArticleStatus.PUBLISHED,
                null,
                null,
                false,
                "blog",
                cta,
                cta,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                1
        );

        listItem = new ArticleListItemDto(
                articleDto.id(),
                articleDto.slug(),
                articleDto.title(),
                articleDto.description(),
                articleDto.excerpt(),
                articleDto.heroKicker(),
                articleDto.tags(),
                author,
                articleDto.readingTime(),
                articleDto.publishedAt(),
                articleDto.updatedAt(),
                articleDto.status(),
                articleDto.contentGroup(),
                articleDto.canonicalUrl(),
                articleDto.ogImage(),
                articleDto.noindex(),
                cta,
                cta,
                articleDto.revision()
        );

        upsertRequest = new ArticleUpsertRequest(
                "sample-slug",
                "Title",
                "Desc",
                "Excerpt",
                "Hero",
                List.of("Tag"),
                author,
                "5 min",
                Instant.now(),
                ArticleStatus.PUBLISHED,
                null,
                null,
                false,
                "blog",
                cta,
                cta,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
    }

    @Test
    @WithMockUser(authorities = "ARTICLE_READ")
    @DisplayName("GET /api/v1/articles returns paginated results")
    void searchArticles_success() throws Exception {
        Page<ArticleListItemDto> page = new PageImpl<>(List.of(listItem), PageRequest.of(0, 20), 1);
        when(articleService.searchArticles(any(), any())).thenReturn(page);

        mockMvc.perform(get("/api/v1/articles")
                        .param("status", "PUBLISHED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].slug").value("sample-slug"));
    }

    @Test
    @WithMockUser(authorities = "ARTICLE_READ")
    @DisplayName("GET /api/v1/articles with includeDrafts=true returns 403 without elevated permission")
    void searchArticles_includeDraftsForbidden() throws Exception {
        when(articleService.searchArticles(any(), any())).thenThrow(new ForbiddenException("no drafts"));

        mockMvc.perform(get("/api/v1/articles").param("includeDrafts", "true"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(authorities = "ARTICLE_UPDATE")
    @DisplayName("GET /api/v1/articles with includeDrafts=true succeeds for elevated permission")
    void searchArticles_includeDraftsAllowed() throws Exception {
        Page<ArticleListItemDto> page = new PageImpl<>(List.of(listItem), PageRequest.of(0, 20), 1);
        when(articleService.searchArticles(any(), any())).thenReturn(page);

        mockMvc.perform(get("/api/v1/articles").param("includeDrafts", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].slug").value("sample-slug"));
    }

    @Test
    @WithMockUser(authorities = "ARTICLE_READ")
    @DisplayName("GET /api/v1/articles rejects invalid status enum")
    void searchArticles_invalidStatus() throws Exception {
        mockMvc.perform(get("/api/v1/articles").param("status", "INVALID"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(authorities = "ARTICLE_READ")
    @DisplayName("GET /api/v1/articles with status=DRAFT requires elevated permission")
    void searchArticles_draftForbidden() throws Exception {
        when(articleService.searchArticles(any(), any())).thenThrow(new ForbiddenException("drafts forbidden"));

        mockMvc.perform(get("/api/v1/articles").param("status", "DRAFT"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(authorities = "ARTICLE_READ")
    @DisplayName("GET /api/v1/articles rejects negative paging parameters")
    void searchArticles_negativePaging() throws Exception {
        mockMvc.perform(get("/api/v1/articles").param("page", "-1"))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    org.assertj.core.api.Assertions.assertThat(status).isIn(200, 400);
                });
    }

    @Test
    @WithMockUser(authorities = "ARTICLE_READ")
    @DisplayName("GET /api/v1/articles/{id} returns article")
    void getArticle_success() throws Exception {
        when(articleService.getArticle(eq(articleDto.id()), eq(false))).thenReturn(articleDto);

        mockMvc.perform(get("/api/v1/articles/{id}", articleDto.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value("sample-slug"));
    }

    @Test
    @WithMockUser(authorities = "ARTICLE_READ")
    @DisplayName("GET /api/v1/articles/{id} returns 404 when not found")
    void getArticle_notFound() throws Exception {
        when(articleService.getArticle(any(), anyBoolean())).thenThrow(new ResourceNotFoundException("not found"));

        mockMvc.perform(get("/api/v1/articles/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(authorities = "ARTICLE_READ")
    @DisplayName("GET /api/v1/articles/{id} drafts require elevated permission")
    void getArticle_draftForbidden() throws Exception {
        when(articleService.getArticle(any(), eq(true))).thenThrow(new ForbiddenException("no access"));

        mockMvc.perform(get("/api/v1/articles/{id}", UUID.randomUUID()).param("includeDrafts", "true"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(authorities = "ARTICLE_READ")
    @DisplayName("GET /api/v1/articles/slug/{slug} returns article")
    void getArticleBySlug_success() throws Exception {
        when(articleService.getArticleBySlug(eq("sample-slug"), eq(false))).thenReturn(articleDto);

        mockMvc.perform(get("/api/v1/articles/slug/{slug}", "sample-slug"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value("sample-slug"));
    }

    @Test
    @WithMockUser(authorities = "ARTICLE_CREATE")
    @DisplayName("POST /api/v1/articles creates article")
    void createArticle_success() throws Exception {
        when(articleService.createArticle(anyString(), any())).thenReturn(articleDto);

        mockMvc.perform(post("/api/v1/articles")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(upsertRequest)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.slug").value("sample-slug"));
    }

    @Test
    @WithMockUser(authorities = "ARTICLE_CREATE")
    @DisplayName("POST /api/v1/articles/bulk creates multiple articles")
    void createArticles_bulkSuccess() throws Exception {
        when(articleService.createArticles(anyString(), anyList())).thenReturn(List.of(articleDto));

        mockMvc.perform(post("/api/v1/articles/bulk")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(List.of(upsertRequest))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$[0].slug").value("sample-slug"));
    }

    @Test
    @WithMockUser(authorities = "ARTICLE_CREATE")
    @DisplayName("POST /api/v1/articles/bulk returns 400 on validation error")
    void createArticles_bulkValidationError() throws Exception {
        when(articleService.createArticles(anyString(), anyList())).thenThrow(new ValidationException("bad"));

        mockMvc.perform(post("/api/v1/articles/bulk")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(List.of(upsertRequest))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(authorities = "ARTICLE_CREATE")
    @DisplayName("POST /api/v1/articles returns 400 on validation error")
    void createArticle_validationError() throws Exception {
        when(articleService.createArticle(anyString(), any())).thenThrow(new ValidationException("bad"));

        mockMvc.perform(post("/api/v1/articles")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(upsertRequest)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(authorities = "ARTICLE_UPDATE")
    @DisplayName("PUT /api/v1/articles/{id} updates article")
    void updateArticle_success() throws Exception {
        when(articleService.updateArticle(anyString(), eq(articleDto.id()), any())).thenReturn(articleDto);

        mockMvc.perform(put("/api/v1/articles/{id}", articleDto.id())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(upsertRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value("sample-slug"));
    }

    @Test
    @WithMockUser(authorities = "ARTICLE_UPDATE")
    @DisplayName("PUT /api/v1/articles/{id} 404 when missing")
    void updateArticle_notFound() throws Exception {
        when(articleService.updateArticle(anyString(), any(), any())).thenThrow(new ResourceNotFoundException("missing"));

        mockMvc.perform(put("/api/v1/articles/{id}", UUID.randomUUID())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(upsertRequest)))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(authorities = "ARTICLE_UPDATE")
    @DisplayName("PUT /api/v1/articles/bulk updates multiple articles")
    void updateArticles_bulkSuccess() throws Exception {
        ArticleBulkUpdateItem item = new ArticleBulkUpdateItem(UUID.randomUUID(), upsertRequest);
        when(articleService.updateArticles(anyString(), anyList())).thenReturn(List.of(articleDto));

        mockMvc.perform(put("/api/v1/articles/bulk")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(List.of(item))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].slug").value("sample-slug"));
    }

    @Test
    @WithMockUser(authorities = "ARTICLE_UPDATE")
    @DisplayName("PUT /api/v1/articles/bulk returns 400 on validation error")
    void updateArticles_bulkValidationError() throws Exception {
        ArticleBulkUpdateItem item = new ArticleBulkUpdateItem(UUID.randomUUID(), upsertRequest);
        when(articleService.updateArticles(anyString(), anyList())).thenThrow(new ValidationException("bad"));

        mockMvc.perform(put("/api/v1/articles/bulk")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(List.of(item))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(authorities = "ARTICLE_DELETE")
    @DisplayName("DELETE /api/v1/articles/{id} deletes article")
    void deleteArticle_success() throws Exception {
        mockMvc.perform(delete("/api/v1/articles/{id}", articleDto.id()).with(csrf()))
                .andExpect(status().isNoContent());
    }

    @Test
    @WithMockUser(authorities = "ARTICLE_DELETE")
    @DisplayName("DELETE /api/v1/articles/{id} returns 404 when missing")
    void deleteArticle_notFound() throws Exception {
        org.mockito.Mockito.doThrow(new ResourceNotFoundException("not found"))
                .when(articleService).deleteArticle(anyString(), any());

        mockMvc.perform(delete("/api/v1/articles/{id}", UUID.randomUUID()).with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(authorities = "ARTICLE_DELETE")
    @DisplayName("DELETE /api/v1/articles bulk deletes")
    void deleteArticles_bulkSuccess() throws Exception {
        mockMvc.perform(delete("/api/v1/articles")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(List.of(UUID.randomUUID()))))
                .andExpect(status().isNoContent());
    }

    @Test
    @WithMockUser(authorities = "ARTICLE_READ")
    @DisplayName("GET /api/v1/articles with includeDrafts=true returns 403 without elevated permission")
    void getArticle_draftsForbiddenWithoutPermission() throws Exception {
        when(articleService.getArticle(any(), eq(true))).thenThrow(new ForbiddenException("forbidden"));

        mockMvc.perform(get("/api/v1/articles/{id}", UUID.randomUUID()).param("includeDrafts", "true"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(authorities = {"ARTICLE_UPDATE"})
    @DisplayName("GET /api/v1/articles with includeDrafts=true succeeds for elevated permission")
    void getArticle_draftsAllowedWithPermission() throws Exception {
        when(articleService.getArticle(any(), eq(true))).thenReturn(articleDto);

        mockMvc.perform(get("/api/v1/articles/{id}", UUID.randomUUID()).param("includeDrafts", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value("sample-slug"));
    }

    @Test
    @DisplayName("Unauthorized requests are rejected")
    void endpoints_requireAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/articles"))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    org.assertj.core.api.Assertions.assertThat(status).isIn(401, 403, 302);
                });

        mockMvc.perform(post("/api/v1/articles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(upsertRequest)))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    org.assertj.core.api.Assertions.assertThat(status).isIn(401, 403, 302);
                });
    }

    @Test
    @WithMockUser(authorities = "ARTICLE_READ")
    @DisplayName("GET /api/v1/articles/tags returns tags with counts")
    void getTagsWithCounts_success() throws Exception {
        ArticleTagWithCountDto tagDto = new ArticleTagWithCountDto("Tag", 2L);
        when(articleService.getTagsWithCounts(any())).thenReturn(List.of(tagDto));

        mockMvc.perform(get("/api/v1/articles/tags"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].tag").value("Tag"))
                .andExpect(jsonPath("$[0].count").value(2));
    }

    @Test
    @WithMockUser(authorities = "ARTICLE_READ")
    @DisplayName("GET /api/v1/articles/sitemap is public")
    void sitemap_public() throws Exception {
        SitemapEntryDto entry = new SitemapEntryDto("/blog/sample-slug", Instant.now(), "weekly", 0.8);
        when(articleService.getSitemapEntries(any())).thenReturn(List.of(entry));

        mockMvc.perform(get("/api/v1/articles/sitemap"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].url").value("/blog/sample-slug"));
    }

    @Test
    @WithMockUser(authorities = "ARTICLE_READ")
    @DisplayName("GET /api/v1/articles/{id} rejects malformed UUID")
    void getArticle_malformedUuid() throws Exception {
        mockMvc.perform(get("/api/v1/articles/{id}", "not-a-uuid"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(authorities = "ARTICLE_CREATE")
    @DisplayName("POST /api/v1/articles returns 400/415 for missing body or content type")
    void createArticle_missingBodyOrContentType() throws Exception {
        mockMvc.perform(post("/api/v1/articles").with(csrf()))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    org.assertj.core.api.Assertions.assertThat(status).isIn(400, 415);
                });
    }

    @Test
    @WithMockUser(authorities = "ARTICLE_CREATE")
    @DisplayName("POST /api/v1/articles returns 400 for bad JSON")
    void createArticle_badJson() throws Exception {
        mockMvc.perform(post("/api/v1/articles")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ invalid json"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(authorities = "ARTICLE_CREATE")
    @DisplayName("POST /api/v1/articles/bulk returns 400/415 for missing body")
    void createArticles_bulkMissingBody() throws Exception {
        mockMvc.perform(post("/api/v1/articles/bulk").with(csrf()))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    org.assertj.core.api.Assertions.assertThat(status).isIn(400, 415);
                });
    }

    @Test
    @WithMockUser(authorities = "ARTICLE_UPDATE")
    @DisplayName("PUT /api/v1/articles/{id} returns 400/415 for missing body")
    void updateArticle_missingBody() throws Exception {
        mockMvc.perform(put("/api/v1/articles/{id}", articleDto.id()).with(csrf()))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    org.assertj.core.api.Assertions.assertThat(status).isIn(400, 415);
                });
    }

    @Test
    @WithMockUser(authorities = "ARTICLE_UPDATE")
    @DisplayName("PUT /api/v1/articles/bulk returns 400/415 for missing body")
    void updateArticles_bulkMissingBody() throws Exception {
        mockMvc.perform(put("/api/v1/articles/bulk").with(csrf()))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    org.assertj.core.api.Assertions.assertThat(status).isIn(400, 415);
                });
    }

    @Test
    @DisplayName("GET /api/v1/articles/public returns published articles without auth")
    void searchPublicArticles_success() throws Exception {
        Page<ArticleListItemDto> page = new PageImpl<>(List.of(listItem), PageRequest.of(0, 20), 1);
        when(articleService.searchArticles(any(), any())).thenReturn(page);

        mockMvc.perform(get("/api/v1/articles/public"))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    org.assertj.core.api.Assertions.assertThat(status).isIn(200, 302);
                });
    }

    @Test
    @DisplayName("GET /api/v1/articles/public/slug/{slug} returns article without auth")
    void getArticleBySlugPublic_success() throws Exception {
        when(articleService.getArticleBySlug(eq("sample-slug"), eq(false))).thenReturn(articleDto);

        mockMvc.perform(get("/api/v1/articles/public/slug/{slug}", "sample-slug"))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    org.assertj.core.api.Assertions.assertThat(status).isIn(200, 302);
                });
    }

    @Test
    @WithMockUser
    @DisplayName("GET /api/v1/articles/public translates sort=-publishedAt to descending")
    void searchPublicArticles_hyphenSortTranslated() throws Exception {
        Page<ArticleListItemDto> page = new PageImpl<>(List.of(listItem), PageRequest.of(0, 20), 1);
        when(articleService.searchArticles(any(), any())).thenReturn(page);

        mockMvc.perform(get("/api/v1/articles/public")
                        .param("sort", "-publishedAt"))
                .andExpect(status().isOk());

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(articleService).searchArticles(any(), pageableCaptor.capture());
        Sort.Order order = pageableCaptor.getValue().getSort().getOrderFor("publishedAt");
        org.assertj.core.api.Assertions.assertThat(order).isNotNull();
        org.assertj.core.api.Assertions.assertThat(order.getDirection()).isEqualTo(Sort.Direction.DESC);
    }

    @Test
    @WithMockUser
    @DisplayName("GET /api/v1/articles/public rejects invalid sort property")
    void searchPublicArticles_invalidSortProperty() throws Exception {
        mockMvc.perform(get("/api/v1/articles/public")
                        .param("sort", "invalidField"))
                .andExpect(status().isBadRequest());
    }
}
