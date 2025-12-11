package uk.gegc.quizmaker.features.article.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import uk.gegc.quizmaker.BaseUnitTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import uk.gegc.quizmaker.features.article.api.dto.*;
import uk.gegc.quizmaker.features.article.application.impl.ArticleServiceImpl;
import uk.gegc.quizmaker.features.article.domain.model.Article;
import uk.gegc.quizmaker.features.article.domain.model.ArticleStatus;
import uk.gegc.quizmaker.features.article.domain.repository.ArticleRepository;
import uk.gegc.quizmaker.features.article.domain.repository.projection.ArticleTagCountProjection;
import uk.gegc.quizmaker.features.article.domain.repository.ArticleSpecifications;
import uk.gegc.quizmaker.features.article.infra.mapping.ArticleMapper;
import uk.gegc.quizmaker.features.tag.domain.model.Tag;
import uk.gegc.quizmaker.features.tag.domain.repository.TagRepository;
import uk.gegc.quizmaker.features.user.domain.model.PermissionName;
import uk.gegc.quizmaker.shared.exception.ForbiddenException;
import uk.gegc.quizmaker.shared.exception.ResourceNotFoundException;
import uk.gegc.quizmaker.shared.exception.ValidationException;
import uk.gegc.quizmaker.shared.security.AppPermissionEvaluator;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ArticleServiceImplTest extends BaseUnitTest {

    @Mock
    ArticleRepository articleRepository;
    @Mock
    TagRepository tagRepository;
    @Mock
    ArticleMapper articleMapper;
    @Mock
    AppPermissionEvaluator permissionEvaluator;

    @InjectMocks
    ArticleServiceImpl service;

    private ArticleUpsertRequest request;
    private Article entity;
    private ArticleDto dto;

    @BeforeEach
    void setUp() {
        request = sampleRequest("slug-one", List.of("Tag1")).build();
        entity = new Article();
        entity.setId(UUID.randomUUID());
        entity.setSlug("slug-one");
        entity.setStatus(ArticleStatus.PUBLISHED);
        dto = new ArticleDto(entity.getId(), "slug-one", "Title", "Desc", "Ex", null,
                List.of("Tag1"), new ArticleAuthorDto("A", "B"), "5", Instant.now(), Instant.now(),
                ArticleStatus.PUBLISHED, null, null, false, "blog",
                new ArticleCallToActionDto("p", "/", null), new ArticleCallToActionDto("s", "/s", null),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), 1);
    }

    @Test
    @DisplayName("createArticle validates required fields and slug uniqueness")
    void createArticle_validatesAndSaves() {
        when(articleRepository.findBySlug("slug-one")).thenReturn(Optional.empty());
        when(articleMapper.toEntity(eq(request), anySet())).thenReturn(entity);
        when(articleRepository.save(entity)).thenReturn(entity);
        when(articleMapper.toDto(entity)).thenReturn(dto);

        ArticleDto result = service.createArticle("user", request);

        assertThat(result).isEqualTo(dto);
        verify(articleRepository).findBySlug("slug-one");
        verify(articleRepository).save(entity);
    }

    @Test
    @DisplayName("createArticle rejects missing status")
    void createArticle_missingStatus() {
        ArticleUpsertRequest bad = sampleRequest("slug-two", List.of("tag")).withStatus(null).build();
        assertThatThrownBy(() -> service.createArticle("user", bad))
                .isInstanceOf(ValidationException.class);
        verify(articleRepository, never()).save(any());
    }

    @Test
    @DisplayName("createArticle rejects duplicate slug")
    void createArticle_duplicateSlug() {
        when(articleRepository.findBySlug("slug-one")).thenReturn(Optional.of(entity));
        assertThatThrownBy(() -> service.createArticle("user", request))
                .isInstanceOf(ValidationException.class);
        verify(articleRepository, never()).save(any());
    }

    @Test
    @DisplayName("getArticle with includeDrafts enforces elevated permissions")
    void getArticle_includeDrafts_requiresPermission() {
        when(permissionEvaluator.hasAnyPermission(eq(PermissionName.ARTICLE_UPDATE), eq(PermissionName.ARTICLE_ADMIN))).thenReturn(false);

        assertThatThrownBy(() -> service.getArticle(UUID.randomUUID(), true))
                .isInstanceOf(ForbiddenException.class);

        verify(articleRepository, never()).findById(any());
    }

    @Test
    @DisplayName("createArticles reuses tags from shared pool")
    void createArticles_reusesTags() {
        ArticleUpsertRequest r1 = sampleRequest("s1", List.of("TagShared")).build();
        ArticleUpsertRequest r2 = sampleRequest("s2", List.of("tagshared")).build();
        when(articleRepository.findBySlug(anyString())).thenReturn(Optional.empty());

        Tag shared = new Tag();
        shared.setName("TagShared");
        when(tagRepository.findByNameInIgnoreCase(anyList())).thenReturn(List.of());

        Article article1 = new Article();
        Article article2 = new Article();
        when(articleMapper.toEntity(eq(r1), anySet())).thenReturn(article1);
        when(articleMapper.toEntity(eq(r2), anySet())).thenReturn(article2);
        when(articleRepository.saveAll(anyList())).thenReturn(List.of(article1, article2));
        when(articleMapper.toDto(any())).thenReturn(dto);

        ArgumentCaptor<Set<Tag>> tagsCaptor = ArgumentCaptor.forClass(Set.class);
        service.createArticles("user", List.of(r1, r2));

        verify(articleMapper, times(2)).toEntity(any(), tagsCaptor.capture());
        List<Set<Tag>> captured = tagsCaptor.getAllValues();
        assertThat(captured).hasSize(2);
        assertThat(captured.get(0).iterator().next().getName()).isEqualTo("TagShared");
        assertThat(captured.get(0).iterator().next()).isSameAs(captured.get(1).iterator().next());
    }

    @Test
    @DisplayName("createArticles reuses existing DB tags when present in pool")
    void createArticles_reusesExistingFromDb() {
        ArticleUpsertRequest r1 = sampleRequest("s1", List.of("Existing")).build();
        ArticleUpsertRequest r2 = sampleRequest("s2", List.of("existing", "NewOne")).build();
        Tag existing = new Tag();
        existing.setName("Existing");
        when(articleRepository.findBySlug(anyString())).thenReturn(Optional.empty());
        when(tagRepository.findByNameInIgnoreCase(anyList())).thenReturn(List.of(existing));

        Article article1 = new Article();
        Article article2 = new Article();
        when(articleMapper.toEntity(eq(r1), anySet())).thenReturn(article1);
        when(articleMapper.toEntity(eq(r2), anySet())).thenReturn(article2);
        when(articleRepository.saveAll(anyList())).thenReturn(List.of(article1, article2));
        when(articleMapper.toDto(any())).thenReturn(dto);

        ArgumentCaptor<Set<Tag>> tagsCaptor = ArgumentCaptor.forClass(Set.class);
        service.createArticles("user", List.of(r1, r2));

        verify(articleMapper, times(2)).toEntity(any(), tagsCaptor.capture());
        List<Set<Tag>> captured = tagsCaptor.getAllValues();
        assertThat(captured.get(0)).containsExactly(existing);
        assertThat(captured.get(1)).anyMatch(t -> t == existing);
        assertThat(captured.get(1)).extracting(Tag::getName).contains("NewOne");
    }

    @Test
    @DisplayName("createArticle trims blank tags and reuses existing case-insensitive tags")
    void createArticle_trimsTagsAndReusesExisting() {
        ArticleUpsertRequest req = sampleRequest("slug-one",
                Arrays.asList("  Existing  ", "", null, "existing", "New ", "new"))
                .build();
        Tag existing = new Tag();
        existing.setName("Existing");

        when(articleRepository.findBySlug("slug-one")).thenReturn(Optional.empty());
        when(tagRepository.findByNameInIgnoreCase(anyList())).thenReturn(List.of(existing));
        when(articleMapper.toEntity(eq(req), anySet())).thenReturn(entity);
        when(articleRepository.save(entity)).thenReturn(entity);
        when(articleMapper.toDto(entity)).thenReturn(dto);

        ArgumentCaptor<Set<Tag>> tagsCaptor = ArgumentCaptor.forClass(Set.class);

        service.createArticle("user", req);

        verify(articleMapper).toEntity(eq(req), tagsCaptor.capture());
        Set<Tag> captured = tagsCaptor.getValue();
        assertThat(captured).hasSize(2);
        assertThat(captured).contains(existing);
        assertThat(captured.stream().map(Tag::getName)).containsExactlyInAnyOrder("Existing", "New");
    }

    @Test
    @DisplayName("createArticles rejects duplicate slugs within request list")
    void createArticles_duplicateSlugsInRequestList() {
        ArticleUpsertRequest r1 = sampleRequest("dup", List.of("t")).build();
        ArticleUpsertRequest r2 = sampleRequest("dup", List.of("t2")).build();

        assertThatThrownBy(() -> service.createArticles("user", List.of(r1, r2)))
                .isInstanceOf(ValidationException.class);
        verify(articleRepository, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("createArticles rejects duplicate slug against DB")
    void createArticles_duplicateSlugDb() {
        ArticleUpsertRequest r1 = sampleRequest("s1", List.of("t")).build();
        ArticleUpsertRequest r2 = sampleRequest("s2", List.of("t")).build();
        when(articleRepository.findBySlug("s1")).thenReturn(Optional.of(new Article()));

        assertThatThrownBy(() -> service.createArticles("user", List.of(r1, r2)))
                .isInstanceOf(ValidationException.class);
        verify(articleRepository, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("updateArticle throws when article missing")
    void updateArticle_notFound() {
        when(articleRepository.findById(any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.updateArticle("user", UUID.randomUUID(), request))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("updateArticle validates required fields")
    void updateArticle_missingRequiredFields() {
        ArticleUpsertRequest bad = new ArticleUpsertRequest(
                " ",
                null,
                " ",
                "",
                null,
                List.of("tag"),
                new ArticleAuthorDto("a", "b"),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );

        assertThatThrownBy(() -> service.updateArticle("user", UUID.randomUUID(), bad))
                .isInstanceOf(ValidationException.class);
        verify(articleRepository, never()).save(any());
    }

    @Test
    @DisplayName("updateArticles builds shared tag pool and slug check skips null items")
    void updateArticles_sharedTagPool() {
        ArticleBulkUpdateItem item1 = new ArticleBulkUpdateItem(UUID.randomUUID(), request);
        ArticleBulkUpdateItem item2 = null;
        Article article = new Article();

        when(articleRepository.findBySlug(anyString())).thenReturn(Optional.empty());
        when(articleRepository.findById(item1.articleId())).thenReturn(Optional.of(article));
        when(tagRepository.findByNameInIgnoreCase(anyList())).thenReturn(List.of());
        when(articleMapper.toDto(article)).thenReturn(dto);
        when(articleRepository.save(any())).thenReturn(article);

        Tag shared = new Tag();
        shared.setName("Tag1");

        service.updateArticles("user", Arrays.asList(item1, item2));

        verify(articleRepository).findById(item1.articleId());
        verify(articleMapper).applyUpsert(eq(article), eq(request), anySet());
        verify(articleRepository).save(article);
    }

    @Test
    @DisplayName("updateArticles rejects duplicate slug across payloads")
    void updateArticles_duplicateSlugAcrossPayloads() {
        ArticleBulkUpdateItem item1 = new ArticleBulkUpdateItem(UUID.randomUUID(), sampleRequest("same-slug", List.of("t")).build());
        ArticleBulkUpdateItem item2 = new ArticleBulkUpdateItem(UUID.randomUUID(), sampleRequest("same-slug", List.of("t")).build());

        assertThatThrownBy(() -> service.updateArticles("user", List.of(item1, item2)))
                .isInstanceOf(ValidationException.class);
        verify(articleRepository, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("searchArticles delegates to repository with specifications")
    void searchArticles_delegates() {
        Article article = new Article();
        Page<Article> page = new PageImpl<>(List.of(article), PageRequest.of(0, 10), 1);
        when(articleRepository.findAll(any(org.springframework.data.jpa.domain.Specification.class), any(PageRequest.class)))
                .thenReturn(page);
        when(articleMapper.toListItem(article)).thenReturn(new ArticleListItemDto(
                UUID.randomUUID(), "slug", "title", "desc", "ex", null, List.of(),
                new ArticleAuthorDto("a", "b"), "5", Instant.now(), Instant.now(), ArticleStatus.PUBLISHED,
                "blog", null, null, false, null, null, 1));

        Page<ArticleListItemDto> result = service.searchArticles(new ArticleSearchCriteria(ArticleStatus.PUBLISHED, List.of(), "blog"), PageRequest.of(0, 10));
        assertThat(result.getTotalElements()).isEqualTo(1);
    }

    @Test
    @DisplayName("updateArticle rejects duplicate slug")
    void updateArticle_duplicateSlug() {
        UUID id = UUID.randomUUID();
        Article existing = new Article();
        existing.setId(id);
        when(articleRepository.findById(id)).thenReturn(Optional.of(existing));
        Article other = new Article();
        other.setId(UUID.randomUUID());
        when(articleRepository.findBySlug("slug-one")).thenReturn(Optional.of(other));

        assertThatThrownBy(() -> service.updateArticle("user", id, request))
                .isInstanceOf(ValidationException.class);
        verify(articleRepository, never()).save(any());
    }

    @Test
    @DisplayName("getArticle rejects draft when includeDrafts is false")
    void getArticle_draftRejected() {
        UUID id = UUID.randomUUID();
        Article draft = new Article();
        draft.setId(id);
        draft.setStatus(ArticleStatus.DRAFT);
        when(articleRepository.findById(id)).thenReturn(Optional.of(draft));

        assertThatThrownBy(() -> service.getArticle(id, false))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("getArticle returns draft when includeDrafts is true")
    void getArticle_draftAllowedWhenFlagTrue() {
        UUID id = UUID.randomUUID();
        Article draft = new Article();
        draft.setId(id);
        draft.setStatus(ArticleStatus.DRAFT);
        when(permissionEvaluator.hasAnyPermission(PermissionName.ARTICLE_UPDATE, PermissionName.ARTICLE_ADMIN)).thenReturn(true);
        when(articleRepository.findById(id)).thenReturn(Optional.of(draft));
        when(articleMapper.toDto(draft)).thenReturn(dto);

        ArticleDto result = service.getArticle(id, true);
        assertThat(result).isEqualTo(dto);
    }

    @Test
    @DisplayName("getArticleBySlug validates slug")
    void getArticleBySlug_requiresSlug() {
        assertThatThrownBy(() -> service.getArticleBySlug("  ", false))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("getArticlesByIds preserves order and skips missing")
    void getArticlesByIds_preservesOrder() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        Article a1 = new Article();
        a1.setId(id1);
        when(articleRepository.findAllById(anyCollection())).thenReturn(List.of(a1));
        when(articleMapper.toDto(a1)).thenReturn(dto);

        List<ArticleDto> result = service.getArticlesByIds(List.of(id1, id2));

        assertThat(result).hasSize(1);
        assertThat(result.get(0)).isEqualTo(dto);
    }

    @Test
    @DisplayName("deleteArticle throws when not found")
    void deleteArticle_notFound() {
        UUID id = UUID.randomUUID();
        when(articleRepository.existsById(id)).thenReturn(false);
        assertThatThrownBy(() -> service.deleteArticle("user", id))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("deleteArticles throws when any id missing")
    void deleteArticles_missingId() {
        UUID id = UUID.randomUUID();
        when(articleRepository.existsById(id)).thenReturn(false);
        assertThatThrownBy(() -> service.deleteArticles("user", List.of(id)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("searchArticles uses default criteria when null")
    void searchArticles_defaultsWhenNullCriteria() {
        Page<Article> page = new PageImpl<>(List.of(entity), PageRequest.of(0, 5), 1);
        when(articleRepository.findAll(any(org.springframework.data.jpa.domain.Specification.class), any(Pageable.class)))
                .thenReturn(page);
        when(articleMapper.toListItem(any())).thenReturn(new ArticleListItemDto(
                entity.getId(), "slug", "t", "d", "e", null, List.of(), new ArticleAuthorDto("a", "b"),
                "5", Instant.now(), Instant.now(), ArticleStatus.PUBLISHED, "blog", null, null, false, null, null, 1));

        Page<ArticleListItemDto> result = service.searchArticles(null, PageRequest.of(0, 5));
        assertThat(result.getTotalElements()).isEqualTo(1);
    }

    @Test
    @DisplayName("getSitemapEntries defaults status when null")
    void getSitemapEntries_defaultsStatus() {
        when(articleRepository.findSitemapEntries(ArticleStatus.PUBLISHED)).thenReturn(List.of());
        List<SitemapEntryDto> result = service.getSitemapEntries(null);
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("getTagsWithCounts maps projections")
    void getTagsWithCounts_mapsProjections() {
        ArticleTagCountProjection proj = new ArticleTagCountProjection() {
            @Override
            public String getTagName() { return "foo"; }
            @Override
            public Long getUsageCount() { return 2L; }
        };
        when(articleRepository.countTags(ArticleStatus.PUBLISHED)).thenReturn(List.of(proj));
        ArticleTagWithCountDto dto = new ArticleTagWithCountDto("foo", 2L);
        when(articleMapper.toTagWithCount(proj)).thenReturn(dto);

        List<ArticleTagWithCountDto> result = service.getTagsWithCounts(ArticleStatus.PUBLISHED);
        assertThat(result).containsExactly(dto);
    }

    private ArticleUpsertRequestBuilder sampleRequest(String slug, List<String> tags) {
        return new ArticleUpsertRequestBuilder(slug, tags);
    }

    private static class ArticleUpsertRequestBuilder {
        private final String slug;
        private final List<String> tags;
        private ArticleStatus status = ArticleStatus.PUBLISHED;

        ArticleUpsertRequestBuilder(String slug, List<String> tags) {
            this.slug = slug;
            this.tags = tags;
        }

        ArticleUpsertRequestBuilder withStatus(ArticleStatus status) {
            this.status = status;
            return this;
        }

        ArticleUpsertRequest build() {
            return new ArticleUpsertRequest(
                    slug,
                    "Title",
                    "Desc",
                    "Excerpt",
                    "Hero",
                    tags,
                    new ArticleAuthorDto("Author", "Role"),
                    "5 min",
                    Instant.parse("2024-01-01T00:00:00Z"),
                    status,
                    "https://example.com",
                    "https://example.com/og.png",
                    false,
                    "blog",
                    new ArticleCallToActionDto("p", "/", null),
                    new ArticleCallToActionDto("s", "/s", null),
                    List.of(new ArticleStatDto("S", "V", null, null)),
                    List.of("K1"),
                    List.of("C1"),
                    List.of(new ArticleSectionDto("sec", "title", null, null)),
                    List.of(new ArticleFaqDto("Q", "A")),
                    List.of(new ArticleReferenceDto("R", "https://r", null))
            );
        }
    }
}
