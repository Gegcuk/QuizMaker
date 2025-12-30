package uk.gegc.quizmaker.features.article.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.TestPropertySource;
import uk.gegc.quizmaker.BaseIntegrationTest;
import uk.gegc.quizmaker.features.article.api.dto.*;
import uk.gegc.quizmaker.features.article.application.impl.ArticleServiceImpl;
import uk.gegc.quizmaker.features.article.domain.model.Article;
import uk.gegc.quizmaker.features.article.domain.model.ArticleBlockType;
import uk.gegc.quizmaker.features.article.domain.model.ArticleContentType;
import uk.gegc.quizmaker.features.article.domain.model.ArticleStatus;
import uk.gegc.quizmaker.features.article.domain.repository.ArticleRepository;
import uk.gegc.quizmaker.features.tag.domain.model.Tag;
import uk.gegc.quizmaker.features.tag.domain.repository.TagRepository;

import java.time.Instant;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=update",
        "spring.jpa.hibernate.hbm2ddl.auto=update"
})
class ArticleServiceImplIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private ArticleServiceImpl articleService;

    @Autowired
    private ArticleRepository articleRepository;

    @Autowired
    private TagRepository tagRepository;

    @Test
    @DisplayName("createArticle persists full graph")
    void createArticle_persistsFullyPopulatedArticle() {
        ArticleUpsertRequest request = baseRequest("retrieval-practice")
                .withTags(List.of("Learning", "Practice"))
                .build();

        ArticleDto dto = articleService.createArticle("user", request);

        Article saved = articleRepository.findBySlug(dto.slug()).orElseThrow();
        assertThat(saved.getTitle()).isEqualTo(request.title());
        assertThat(saved.getStats()).hasSize(1);
        assertThat(saved.getKeyPoints()).hasSize(2);
        assertThat(saved.getChecklistItems()).hasSize(1);
        assertThat(saved.getSections()).hasSize(1);
        assertThat(saved.getFaqs()).hasSize(1);
        assertThat(saved.getReferences()).hasSize(1);
        assertThat(saved.getTags()).extracting(Tag::getName)
                .containsExactlyInAnyOrder("Learning", "Practice");
    }

    @Test
    @DisplayName("createArticle trims blanks and reuses existing tags")
    void createArticle_trimsBlankTagsAndReusesExistingCaseInsensitive() {
        Tag existing = new Tag();
        existing.setName("Focus");
        tagRepository.save(existing);

        ArticleUpsertRequest request = baseRequest("tag-normalization")
                .withTags(Arrays.asList("  Focus ", "NewTag", "newtag", "", null))
                .build();

        ArticleDto dto = articleService.createArticle("user", request);

        Article saved = articleRepository.findById(dto.id()).orElseThrow();
        assertThat(saved.getTags()).extracting(Tag::getName)
                .containsExactlyInAnyOrder("Focus", "NewTag");
        List<Tag> tags = tagRepository.findAll();
        assertThat(tags).filteredOn(t -> t.getName().equals("Focus")).hasSize(1);
        assertThat(tags).filteredOn(t -> t.getName().equals("NewTag")).hasSize(1);
    }

    @Test
    @DisplayName("createArticle applies default author and CTAs when blank")
    void createArticle_appliesDefaultAuthorAndCtasWhenBlank() {
        ArticleUpsertRequest request = baseRequest("cta-defaults")
                .withAuthor(new ArticleAuthorDto(" ", null))
                .withPrimaryCta(new ArticleCallToActionDto("", "", null))
                .withSecondaryCta(null)
                .build();

        ArticleDto dto = articleService.createArticle("user", request);

        Article saved = articleRepository.findById(dto.id()).orElseThrow();
        assertThat(saved.getAuthor().getName()).isEqualTo("Unknown");
        assertThat(saved.getAuthor().getTitle()).isEqualTo("Author");
        assertThat(saved.getPrimaryCta().getLabel()).isEqualTo("Learn more");
        assertThat(saved.getPrimaryCta().getHref()).isEqualTo("/");
        assertThat(saved.getSecondaryCta().getLabel()).isEqualTo("Explore");
        assertThat(saved.getSecondaryCta().getHref()).isEqualTo("/");
    }

    @Test
    @DisplayName("createArticles reuses tags across requests")
    void createArticles_reusesTagsAcrossRequests() {
        ArticleUpsertRequest first = baseRequest("post-1")
                .withTags(List.of("Learning"))
                .build();
        ArticleUpsertRequest second = baseRequest("post-2")
                .withTags(List.of("learning", "Extra"))
                .build();

        List<ArticleDto> dtos = articleService.createArticles("user", List.of(first, second));

        assertThat(dtos).hasSize(2);
        List<Tag> tags = tagRepository.findAll();
        assertThat(tags).filteredOn(t -> t.getName().equalsIgnoreCase("Learning")).hasSize(1);
        assertThat(tags).filteredOn(t -> t.getName().equalsIgnoreCase("Extra")).hasSize(1);
    }

    @Test
    @DisplayName("updateArticle clears collections when empty lists provided")
    void updateArticle_clearsCollectionsWhenEmptyListsProvided() {
        ArticleDto created = articleService.createArticle("user", baseRequest("clear-me").build());

        ArticleUpsertRequest update = baseRequest("clear-me")
                .withStats(List.of())
                .withKeyPoints(List.of())
                .withChecklist(List.of())
                .withSections(List.of())
                .withFaqs(List.of())
                .withReferences(List.of())
                .build();

        articleService.updateArticle("user", created.id(), update);
        Article reloaded = articleRepository.findById(created.id()).orElseThrow();

        assertThat(reloaded.getStats()).isEmpty();
        assertThat(reloaded.getKeyPoints()).isEmpty();
        assertThat(reloaded.getChecklistItems()).isEmpty();
        assertThat(reloaded.getSections()).isEmpty();
        assertThat(reloaded.getFaqs()).isEmpty();
        assertThat(reloaded.getReferences()).isEmpty();
    }

    @Test
    @DisplayName("updateArticle clears collections when null provided")
    void updateArticle_clearsCollectionsWhenNullProvided() {
        ArticleDto created = articleService.createArticle("user", baseRequest("clear-null").build());

        ArticleUpsertRequest update = baseRequest("clear-null")
                .withStats(null)
                .withKeyPoints(null)
                .withChecklist(null)
                .withSections(null)
                .withFaqs(null)
                .withReferences(null)
                .build();

        articleService.updateArticle("user", created.id(), update);
        Article reloaded = articleRepository.findById(created.id()).orElseThrow();

        assertThat(reloaded.getStats()).isEmpty();
        assertThat(reloaded.getKeyPoints()).isEmpty();
        assertThat(reloaded.getChecklistItems()).isEmpty();
        assertThat(reloaded.getSections()).isEmpty();
        assertThat(reloaded.getFaqs()).isEmpty();
        assertThat(reloaded.getReferences()).isEmpty();
    }

    @Test
    @DisplayName("updateArticles uses shared tag pool in bulk update")
    void updateArticles_usesSharedTagPoolInBulkUpdate() {
        ArticleDto first = articleService.createArticle("user", baseRequest("bulk-1").build());
        ArticleDto second = articleService.createArticle("user", baseRequest("bulk-2").build());

        Tag existingShared = new Tag();
        existingShared.setName("SharedTag");
        tagRepository.save(existingShared);

        int initialTagCount = tagRepository.findAll().size();

        ArticleBulkUpdateItem updateOne = new ArticleBulkUpdateItem(first.id(),
                baseRequest("bulk-1").withTags(List.of("SharedTag")).build());
        ArticleBulkUpdateItem updateTwo = new ArticleBulkUpdateItem(second.id(),
                baseRequest("bulk-2").withTags(List.of("sharedtag")).build());

        articleService.updateArticles("user", List.of(updateOne, updateTwo));

        List<Tag> tags = tagRepository.findAll();
        long sharedCount = tags.stream()
                .filter(t -> t.getName().equalsIgnoreCase("SharedTag"))
                .count();
        assertThat(sharedCount).isEqualTo(1);
        assertThat(tags.size()).isEqualTo(initialTagCount);
    }

    @Test
    @DisplayName("updateArticles rolls back when any article missing")
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void updateArticles_missingArticleRollsBackAll() {
        ArticleDto created = articleService.createArticle("user", baseRequest("rollback-one").build());

        ArticleBulkUpdateItem valid = new ArticleBulkUpdateItem(created.id(),
                baseRequest("rollback-one").withTitle("Updated Title").build());
        ArticleBulkUpdateItem missing = new ArticleBulkUpdateItem(UUID.randomUUID(),
                baseRequest("rollback-missing").build());

        try {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> articleService.updateArticles("user", List.of(valid, missing)))
                    .isInstanceOf(uk.gegc.quizmaker.shared.exception.ResourceNotFoundException.class);

            Article reloaded = articleRepository.findById(created.id()).orElseThrow();
            assertThat(reloaded.getTitle()).isEqualTo("Title");
        } finally {
            articleRepository.deleteAll();
            tagRepository.deleteAll();
        }
    }

    @Test
    @DisplayName("Clearing collections removes orphans from child tables")
    void clearingCollectionsRemovesOrphans() {
        long statsBefore = jdbcTemplate.queryForObject("select count(*) from article_stats", Long.class);
        long keyPointsBefore = jdbcTemplate.queryForObject("select count(*) from article_key_points", Long.class);
        long checklistBefore = jdbcTemplate.queryForObject("select count(*) from article_checklist_items", Long.class);
        long sectionsBefore = jdbcTemplate.queryForObject("select count(*) from article_sections", Long.class);
        long faqsBefore = jdbcTemplate.queryForObject("select count(*) from article_faqs", Long.class);
        long refsBefore = jdbcTemplate.queryForObject("select count(*) from article_references", Long.class);

        ArticleDto dto = articleService.createArticle("user", baseRequest("orphans").build());

        ArticleUpsertRequest update = baseRequest("orphans")
                .withStats(List.of())
                .withKeyPoints(List.of())
                .withChecklist(List.of())
                .withSections(List.of())
                .withFaqs(List.of())
                .withReferences(List.of())
                .build();

        articleService.updateArticle("user", dto.id(), update);
        entityManager.flush();

        long stats = jdbcTemplate.queryForObject("select count(*) from article_stats", Long.class);
        long keyPoints = jdbcTemplate.queryForObject("select count(*) from article_key_points", Long.class);
        long checklist = jdbcTemplate.queryForObject("select count(*) from article_checklist_items", Long.class);
        long sections = jdbcTemplate.queryForObject("select count(*) from article_sections", Long.class);
        long faqs = jdbcTemplate.queryForObject("select count(*) from article_faqs", Long.class);
        long refs = jdbcTemplate.queryForObject("select count(*) from article_references", Long.class);

        assertThat(stats).isEqualTo(statsBefore);
        assertThat(keyPoints).isEqualTo(keyPointsBefore);
        assertThat(checklist).isEqualTo(checklistBefore);
        assertThat(sections).isEqualTo(sectionsBefore);
        assertThat(faqs).isEqualTo(faqsBefore);
        assertThat(refs).isEqualTo(refsBefore);
    }

    @Test
    @DisplayName("getSitemapEntries falls back to slug when canonical is missing")
    void getSitemapEntries_fallbackToSlug() {
        String slug = "sitemap-fallback";
        articleService.createArticle("user", baseRequest(slug).withCanonical(null).build());

        List<SitemapEntryDto> entries = articleService.getSitemapEntries(null);

        assertThat(entries).extracting(SitemapEntryDto::url).contains("/blog/" + slug);
    }

    @Test
    @DisplayName("createArticle rejects fields exceeding column lengths")
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void createArticle_rejectsTooLongFields() {
        String longSlug = "s".repeat(260);
        ArticleUpsertRequest request = baseRequest(longSlug)
                .withTitle("t".repeat(600))
                .build();

        try {
            assertThatThrownBy(() -> articleService.createArticle("user", request))
                    .isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            articleRepository.deleteAll();
            tagRepository.deleteAll();
        }
    }

    @Test
    @DisplayName("Concurrent createArticle with duplicate slug only persists one record")
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void concurrentCreateDuplicateSlugOnlyPersistsOne() throws Exception {
        ArticleUpsertRequest request = baseRequest("concurrent-slug").build();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<ArticleDto> successes = Collections.synchronizedList(new ArrayList<>());
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

        try {
            Runnable task = () -> {
                ready.countDown();
                try {
                    start.await();
                    successes.add(articleService.createArticle("user", request));
                } catch (Throwable t) {
                    errors.add(t);
                }
            };

            executor.submit(task);
            executor.submit(task);
            ready.await(2, TimeUnit.SECONDS);
            start.countDown();
            executor.shutdown();
            executor.awaitTermination(5, TimeUnit.SECONDS);

            assertThat(articleRepository.findAll()).hasSize(1);
            assertThat(successes).hasSize(1);
            assertThat(errors).isNotEmpty();
            assertThat(errors.get(0)).isInstanceOfAny(DataIntegrityViolationException.class, uk.gegc.quizmaker.shared.exception.ValidationException.class);
        } finally {
            executor.shutdownNow();
            articleRepository.deleteAll();
            tagRepository.deleteAll();
        }
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

        ArticleRequestBuilder withAuthor(ArticleAuthorDto author) {
            this.author = author;
            return this;
        }

        ArticleRequestBuilder withCanonical(String canonicalUrl) {
            this.canonicalUrl = canonicalUrl;
            return this;
        }

        ArticleRequestBuilder withPrimaryCta(ArticleCallToActionDto primaryCta) {
            this.primaryCta = primaryCta;
            return this;
        }

        ArticleRequestBuilder withSecondaryCta(ArticleCallToActionDto secondaryCta) {
            this.secondaryCta = secondaryCta;
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
