package uk.gegc.quizmaker.features.article.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.TestPropertySource;
import uk.gegc.quizmaker.BaseIntegrationTest;
import uk.gegc.quizmaker.features.article.api.dto.*;
import uk.gegc.quizmaker.features.article.application.impl.ArticleServiceImpl;
import uk.gegc.quizmaker.features.article.domain.model.Article;
import uk.gegc.quizmaker.features.article.domain.model.ArticleBlockType;
import uk.gegc.quizmaker.features.article.domain.model.ArticleContentType;
import uk.gegc.quizmaker.features.article.domain.model.ArticleStatus;
import uk.gegc.quizmaker.features.article.domain.repository.ArticleRepository;
import uk.gegc.quizmaker.features.tag.domain.repository.TagRepository;
import uk.gegc.quizmaker.shared.exception.ResourceNotFoundException;
import uk.gegc.quizmaker.shared.exception.ValidationException;
import uk.gegc.quizmaker.shared.security.AppPermissionEvaluator;
import static org.mockito.ArgumentMatchers.any;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=update",
        "spring.jpa.hibernate.hbm2ddl.auto=update"
})
class ArticleE2ETest extends BaseIntegrationTest {

    @Autowired
    private ArticleServiceImpl articleService;

    @Autowired
    private ArticleRepository articleRepository;

    @Autowired
    private TagRepository tagRepository;

    @MockitoBean
    private AppPermissionEvaluator permissionEvaluator;

    @Test
    @DisplayName("E2E: full lifecycle create -> fetch -> update -> fetch draft -> delete")
    void lifecycle_endToEnd() {
        ArticleUpsertRequest create = baseRequest("e2e-slug").build();
        ArticleDto created = articleService.createArticle("user", create);

        ArticleDto fetched = articleService.getArticleBySlug("e2e-slug", false);
        assertThat(fetched.slug()).isEqualTo("e2e-slug");

        ArticleUpsertRequest update = baseRequest("e2e-slug")
                .withTitle("Updated Title")
                .withTags(List.of("UpdatedTag"))
                .withStatus(ArticleStatus.DRAFT)
                .build();
        articleService.updateArticle("user", created.id(), update);

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        "admin",
                        "pwd",
                        List.of(new SimpleGrantedAuthority("ARTICLE_UPDATE"))
                )
        );
        org.mockito.Mockito.when(permissionEvaluator.hasAnyPermission(any(), any())).thenReturn(true);
        try {
            ArticleDto draft = articleService.getArticle(created.id(), true);
            assertThat(draft.status()).isEqualTo(ArticleStatus.DRAFT);
        } finally {
            SecurityContextHolder.clearContext();
        }

        articleService.deleteArticle("user", created.id());
        assertThatThrownBy(() -> articleService.getArticle(created.id(), false))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("E2E: padded slug duplicates rejected before insert")
    void paddedSlug_duplicateRejected() {
        ArticleUpsertRequest first = baseRequest(" padded ").build();
        articleService.createArticle("user", first);

        ArticleUpsertRequest second = baseRequest("padded").build();
        assertThatThrownBy(() -> articleService.createArticle("user", second))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("E2E: cascade/orphan removal clears child tables")
    void cascadeOrphanRemoval() {
        ArticleDto dto = articleService.createArticle("user", baseRequest("cascade-test").build());
        ArticleUpsertRequest clear = baseRequest("cascade-test")
                .withStats(List.of())
                .withKeyPoints(List.of())
                .withChecklist(List.of())
                .withSections(List.of())
                .withFaqs(List.of())
                .withReferences(List.of())
                .build();

        articleService.updateArticle("user", dto.id(), clear);
        Article reloaded = articleRepository.findById(dto.id()).orElseThrow();

        assertThat(reloaded.getStats()).isEmpty();
        assertThat(reloaded.getKeyPoints()).isEmpty();
        assertThat(reloaded.getChecklistItems()).isEmpty();
        assertThat(reloaded.getSections()).isEmpty();
        assertThat(reloaded.getFaqs()).isEmpty();
        assertThat(reloaded.getReferences()).isEmpty();
    }

    @Test
    @DisplayName("E2E: shared tag names across bulk create do not create duplicates")
    void bulkCreate_sharedTagsSingleInsert() {
        ArticleUpsertRequest one = baseRequest("tag-e2e-1").withTags(List.of("SharedTag")).build();
        ArticleUpsertRequest two = baseRequest("tag-e2e-2").withTags(List.of("sharedtag")).build();

        articleService.createArticles("user", List.of(one, two));

        long sharedCount = tagRepository.findAll().stream()
                .filter(t -> t.getName().equalsIgnoreCase("SharedTag"))
                .count();
        assertThat(sharedCount).isEqualTo(1);
    }

    @Test
    @DisplayName("E2E: bulk create rolls back when payload contains duplicates")
    void bulkCreate_rollbackOnValidation() {
        ArticleUpsertRequest one = baseRequest("bulk-dup").build();
        ArticleUpsertRequest two = baseRequest("bulk-dup").build();

        assertThatThrownBy(() -> articleService.createArticles("user", List.of(one, two)))
                .isInstanceOf(ValidationException.class);

        assertThat(articleRepository.count()).isZero();
    }

    private ArticleRequestBuilder baseRequest(String slug) {
        return new ArticleRequestBuilder(slug);
    }

    private static class ArticleRequestBuilder {
        private final String slug;
        private String title = "Title";
        private String description = "Description";
        private String excerpt = "Excerpt";
        private String hero = "Hero";
        private ArticleImageDto heroImage = new ArticleImageDto(UUID.randomUUID(), "Alt", "Caption");
        private List<String> tags = List.of("DefaultTag");
        private ArticleAuthorDto author = new ArticleAuthorDto("Author", "Author");
        private String readingTime = "5 minute read";
        private Instant publishedAt = Instant.parse("2024-01-01T00:00:00Z");
        private ArticleStatus status = ArticleStatus.PUBLISHED;
        private String canonicalUrl = "https://example.com/" + UUID.randomUUID();
        private String ogImage = "https://example.com/og.png";
        private Boolean noindex = false;
        private ArticleContentType contentGroup = ArticleContentType.BLOG;
        private ArticleCallToActionDto primaryCta = new ArticleCallToActionDto("Learn", "/", null);
        private ArticleCallToActionDto secondaryCta = new ArticleCallToActionDto("Explore", "/explore", null);
        private List<ArticleStatDto> stats = List.of(new ArticleStatDto("Stat", "Val", "Detail", null));
        private List<String> keyPoints = List.of("KP1", "KP2");
        private List<String> checklist = List.of("CL1");
        private List<ArticleBlockDto> blocks = List.of(new ArticleBlockDto(ArticleBlockType.PARAGRAPH, "Body", null, null, null, null));
        private List<ArticleSectionDto> sections = List.of(new ArticleSectionDto("sec", "Section", "Summary", "Content"));
        private List<ArticleFaqDto> faqs = List.of(new ArticleFaqDto("Q", "A"));
        private List<ArticleReferenceDto> references = List.of(new ArticleReferenceDto("Ref", "https://ref", "journal"));

        ArticleRequestBuilder(String slug) {
            this.slug = slug;
        }

        ArticleRequestBuilder withTags(List<String> tags) {
            this.tags = tags;
            return this;
        }

        ArticleRequestBuilder withStatus(ArticleStatus status) {
            this.status = status;
            return this;
        }

        ArticleRequestBuilder withTitle(String title) {
            this.title = title;
            return this;
        }

        ArticleRequestBuilder withStats(List<ArticleStatDto> stats) {
            this.stats = stats;
            return this;
        }

        ArticleRequestBuilder withKeyPoints(List<String> keyPoints) {
            this.keyPoints = keyPoints;
            return this;
        }

        ArticleRequestBuilder withChecklist(List<String> checklist) {
            this.checklist = checklist;
            return this;
        }

        ArticleRequestBuilder withBlocks(List<ArticleBlockDto> blocks) {
            this.blocks = blocks;
            return this;
        }

        ArticleRequestBuilder withSections(List<ArticleSectionDto> sections) {
            this.sections = sections;
            return this;
        }

        ArticleRequestBuilder withFaqs(List<ArticleFaqDto> faqs) {
            this.faqs = faqs;
            return this;
        }

        ArticleRequestBuilder withReferences(List<ArticleReferenceDto> references) {
            this.references = references;
            return this;
        }

        ArticleUpsertRequest build() {
            return new ArticleUpsertRequest(
                    slug,
                    title,
                    description,
                    excerpt,
                    hero,
                    heroImage,
                    tags,
                    author,
                    readingTime,
                    publishedAt,
                    status,
                    canonicalUrl,
                    ogImage,
                    noindex,
                    contentGroup,
                    primaryCta,
                    secondaryCta,
                    stats,
                    keyPoints,
                    checklist,
                    blocks,
                    sections,
                    faqs,
                    references
            );
        }
    }
}
