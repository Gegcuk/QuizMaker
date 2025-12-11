package uk.gegc.quizmaker.features.article.domain.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.TestPropertySource;
import uk.gegc.quizmaker.BaseIntegrationTest;
import uk.gegc.quizmaker.features.article.api.dto.ArticleSearchCriteria;
import uk.gegc.quizmaker.features.article.domain.model.Article;
import uk.gegc.quizmaker.features.article.domain.model.ArticleAuthor;
import uk.gegc.quizmaker.features.article.domain.model.ArticleCallToAction;
import uk.gegc.quizmaker.features.article.domain.model.ArticleStatus;
import uk.gegc.quizmaker.features.article.domain.repository.projection.ArticleSitemapProjection;
import uk.gegc.quizmaker.features.article.domain.repository.projection.ArticleTagCountProjection;
import uk.gegc.quizmaker.features.tag.domain.model.Tag;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=update",
        "spring.jpa.hibernate.hbm2ddl.auto=update"
})
class ArticleRepositoryTest extends BaseIntegrationTest {

    @Autowired
    private ArticleRepository articleRepository;

    @Autowired
    private jakarta.persistence.EntityManager entityManager;

    @Test
    @DisplayName("ArticleSpecifications ignores empty tag predicate")
    void specificationsDoNotApplyEmptyTagPredicate() {
        Article article = createArticleWithTag("spec-article", "learning");
        articleRepository.save(article);

        ArticleSearchCriteria criteria = new ArticleSearchCriteria(ArticleStatus.PUBLISHED, java.util.Arrays.asList((String) null), "blog");

        var page = articleRepository.findAll(ArticleSpecifications.build(criteria), PageRequest.of(0, 10));

        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent().get(0).getId()).isEqualTo(article.getId());
    }

    @Test
    @DisplayName("Specifications filter by status and contentGroup case-insensitively")
    void specificationsFilterStatusAndContentGroup() {
        Article published = createArticleWithTag("spec-status", "focus");
        published.setContentGroup("blog");
        published.setStatus(ArticleStatus.PUBLISHED);
        Article draft = createArticleWithTag("spec-draft", "focus");
        draft.setContentGroup("BLOG");
        draft.setStatus(ArticleStatus.DRAFT);
        Tag focusTag = tag("focus");
        published.setTags(Set.of(focusTag));
        draft.setTags(Set.of(focusTag));
        articleRepository.saveAll(List.of(published, draft));

        ArticleSearchCriteria criteria = new ArticleSearchCriteria(ArticleStatus.PUBLISHED, List.of(), "BLOG");
        Page<Article> page = articleRepository.findAll(ArticleSpecifications.build(criteria), PageRequest.of(0, 10));

        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent().get(0).getSlug()).isEqualTo("spec-status");
    }

    @Test
    @DisplayName("Specifications with tag filter return distinct articles")
    void specificationsWithTagAreDistinct() {
        Tag shared = new Tag();
        shared.setName("shared");

        Article first = createArticleWithTag("spec-tag-1", "shared");
        Article second = createArticleWithTag("spec-tag-2", "shared");
        first.setTags(Set.of(shared));
        second.setTags(Set.of(shared));
        articleRepository.saveAll(List.of(first, second));

        ArticleSearchCriteria criteria = new ArticleSearchCriteria(ArticleStatus.PUBLISHED, List.of("shared"), "blog");
        Page<Article> page = articleRepository.findAll(ArticleSpecifications.build(criteria), PageRequest.of(0, 10));

        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent()).extracting(Article::getSlug)
                .containsExactlyInAnyOrder("spec-tag-1", "spec-tag-2");
    }

    @Test
    @DisplayName("findBySlug and findBySlugAndStatus return only matching status")
    void findBySlugAndStatusRespectsStatus() {
        Article published = createArticleWithTag("slug-match", "tag");
        published.setStatus(ArticleStatus.PUBLISHED);
        articleRepository.save(published);

        assertThat(articleRepository.findBySlug("slug-match")).isPresent();
        assertThat(articleRepository.findBySlugAndStatus("slug-match", ArticleStatus.PUBLISHED)).isPresent();
        assertThat(articleRepository.findBySlugAndStatus("slug-match", ArticleStatus.DRAFT)).isEmpty();
    }

    @Test
    @DisplayName("existsBySlug returns true when slug present")
    void existsBySlugWorks() {
        Article article = createArticleWithTag("exists-slug", "tag");
        articleRepository.save(article);

        assertThat(articleRepository.existsBySlug("exists-slug")).isTrue();
        assertThat(articleRepository.existsBySlug("missing-slug")).isFalse();
    }

    @Test
    @DisplayName("countTags aggregates per status")
    void countTagsAggregatesByStatus() {
        Article published = createArticleWithTag("count-1", "A");
        Tag tagA = published.getTags().iterator().next();
        Tag tagB = tag("B");
        published.setTags(Set.of(tagA, tagB));
        published.setStatus(ArticleStatus.PUBLISHED);

        Article draft = createArticleWithTag("count-2", "A");
        draft.setTags(Set.of(tagA));
        draft.setStatus(ArticleStatus.DRAFT);

        articleRepository.saveAll(List.of(published, draft));

        List<ArticleTagCountProjection> counts = articleRepository.countTags(ArticleStatus.PUBLISHED);

        assertThat(counts).extracting(ArticleTagCountProjection::getTagName)
                .containsExactlyInAnyOrder("A", "B");
        assertThat(counts).filteredOn(c -> c.getTagName().equals("A"))
                .first()
                .extracting(ArticleTagCountProjection::getUsageCount)
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("findSitemapEntries returns projections for status")
    void findSitemapEntriesReturnsProjections() {
        Article article = createArticleWithTag("sitemap-slug", "tag");
        article.setCanonicalUrl("https://example.com/sitemap-slug");
        article.setStatus(ArticleStatus.PUBLISHED);
        articleRepository.save(article);

        List<ArticleSitemapProjection> entries = articleRepository.findSitemapEntries(ArticleStatus.PUBLISHED);

        assertThat(entries).hasSize(1);
        ArticleSitemapProjection entry = entries.get(0);
        assertThat(entry.getSlug()).isEqualTo("sitemap-slug");
        assertThat(entry.getCanonicalUrl()).isEqualTo("https://example.com/sitemap-slug");
    }

    @Test
    @DisplayName("Entity graph on findAll prevents N+1 tag loading")
    void tagJoinDoesNotTriggerNPlusOne() {
        articleRepository.saveAll(List.of(
                createArticleWithTag("perf-1", "t1"),
                createArticleWithTag("perf-2", "t2"),
                createArticleWithTag("perf-3", "t3")
        ));

        SessionFactory sessionFactory = entityManager.getEntityManagerFactory().unwrap(SessionFactory.class);
        Statistics stats = sessionFactory.getStatistics();
        stats.setStatisticsEnabled(true);
        stats.clear();

        Page<Article> page = articleRepository.findAll(
                ArticleSpecifications.build(new ArticleSearchCriteria(ArticleStatus.PUBLISHED, List.of(), "blog")),
                PageRequest.of(0, 10)
        );
        long afterQuery = stats.getPrepareStatementCount();
        // Access tags to verify they were already fetched
        page.getContent().forEach(a -> a.getTags().forEach(Tag::getName));

        long afterAccess = stats.getPrepareStatementCount();
        // No additional selects should be triggered when accessing tags
        assertThat(afterAccess - afterQuery).isZero();
    }

    private Article createArticleWithTag(String slug, String tagName) {
        Tag tag = new Tag();
        tag.setName(tagName);

        Article article = new Article();
        article.setSlug(slug);
        article.setTitle("Title " + slug);
        article.setDescription("Description");
        article.setExcerpt("Excerpt");
        article.setHeroKicker("Hero");
        article.setTags(Set.of(tag));
        article.setReadingTime("5 minute read");
        article.setPublishedAt(Instant.parse("2024-01-01T00:00:00Z"));
        article.setStatus(ArticleStatus.PUBLISHED);
        article.setCanonicalUrl("https://example.com/" + slug);
        article.setOgImage("https://example.com/og.png");
        article.setNoindex(false);
        article.setContentGroup("blog");
        article.setAuthor(new ArticleAuthor("Author", "Author"));
        article.setPrimaryCta(new ArticleCallToAction("Learn", "/", null));
        article.setSecondaryCta(new ArticleCallToAction("Explore", "/explore", null));
        return article;
    }

    private Tag tag(String name) {
        Tag tag = new Tag();
        tag.setName(name);
        return tag;
    }
}
