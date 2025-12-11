package uk.gegc.quizmaker.features.article.infra.mapping;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gegc.quizmaker.features.article.api.dto.*;
import uk.gegc.quizmaker.features.article.domain.model.Article;
import uk.gegc.quizmaker.features.article.domain.model.ArticleStatus;
import uk.gegc.quizmaker.features.article.domain.repository.projection.ArticleSitemapProjection;
import uk.gegc.quizmaker.shared.exception.ValidationException;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArticleMapperTest {

    private final ArticleMapper mapper = new ArticleMapper();

    @Test
    @DisplayName("applyUpsert throws when request is null")
    void applyUpsertThrowsWhenRequestNull() {
        Article target = new Article();
        assertThatThrownBy(() -> mapper.applyUpsert(target, null, Set.of()))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("applyUpsert throws when target is null")
    void applyUpsertThrowsWhenTargetNull() {
        ArticleUpsertRequest request = new ArticleUpsertRequest(
                "slug",
                "title",
                "description",
                "excerpt",
                "hero",
                List.of("tag"),
                new ArticleAuthorDto("Author", "Role"),
                "5 min",
                Instant.parse("2024-01-01T00:00:00Z"),
                ArticleStatus.PUBLISHED,
                "https://example.com",
                "https://example.com/og",
                false,
                "blog",
                new ArticleCallToActionDto("Label", "/href", null),
                new ArticleCallToActionDto("Label2", "/href2", null),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );

        assertThatThrownBy(() -> mapper.applyUpsert(null, request, Set.of()))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("applyUpsert assigns sequential positions while skipping nulls/blanks")
    void applyUpsertPopulatesSequentialPositionsSkippingNulls() {
        Article target = new Article();
        ArticleUpsertRequest request = new ArticleUpsertRequest(
                "slug",
                "title",
                "description",
                "excerpt",
                "hero",
                List.of("tag"),
                new ArticleAuthorDto("Author", "Role"),
                "5 min",
                Instant.parse("2024-01-01T00:00:00Z"),
                ArticleStatus.PUBLISHED,
                "https://example.com",
                "https://example.com/og",
                false,
                "blog",
                new ArticleCallToActionDto("Label", "/href", null),
                new ArticleCallToActionDto("Label2", "/href2", null),
                Arrays.asList(
                        new ArticleStatDto("S1", "V1", null, null),
                        null,
                        new ArticleStatDto("S2", "V2", null, null)
                ),
                Arrays.asList("KP1", null, "KP2"),
                Arrays.asList("CL1", "", "CL2"),
                Arrays.asList(new ArticleSectionDto("sec1", "t1", null, null), null, new ArticleSectionDto("sec2", "t2", null, null)),
                Arrays.asList(new ArticleFaqDto("Q1", "A1"), null, new ArticleFaqDto("Q2", "A2")),
                Arrays.asList(new ArticleReferenceDto("R1", "https://r1", null), null, new ArticleReferenceDto("R2", "https://r2", null))
        );

        mapper.applyUpsert(target, request, Set.of());

        assertThat(target.getStats()).hasSize(2);
        assertThat(target.getStats().get(0).getPosition()).isEqualTo(0);
        assertThat(target.getStats().get(1).getPosition()).isEqualTo(1);

        assertThat(target.getKeyPoints()).hasSize(2);
        assertThat(target.getKeyPoints().get(0).getPosition()).isEqualTo(0);
        assertThat(target.getKeyPoints().get(1).getPosition()).isEqualTo(1);

        assertThat(target.getChecklistItems()).hasSize(2);
        assertThat(target.getChecklistItems().get(0).getPosition()).isEqualTo(0);
        assertThat(target.getChecklistItems().get(1).getPosition()).isEqualTo(1);

        assertThat(target.getSections()).hasSize(2);
        assertThat(target.getSections().get(0).getPosition()).isEqualTo(0);
        assertThat(target.getSections().get(1).getPosition()).isEqualTo(1);

        assertThat(target.getFaqs()).hasSize(2);
        assertThat(target.getFaqs().get(0).getPosition()).isEqualTo(0);
        assertThat(target.getFaqs().get(1).getPosition()).isEqualTo(1);

        assertThat(target.getReferences()).hasSize(2);
        assertThat(target.getReferences().get(0).getPosition()).isEqualTo(0);
        assertThat(target.getReferences().get(1).getPosition()).isEqualTo(1);
    }

    @Test
    @DisplayName("toCtaEntity falls back to defaults for blank values")
    void toCtaEntityFallsBackForBlankValues() {
        Article target = new Article();
        ArticleUpsertRequest request = new ArticleUpsertRequest(
                "slug",
                "title",
                "description",
                "excerpt",
                "hero",
                List.of("tag"),
                new ArticleAuthorDto("Author", "Role"),
                "5 min",
                Instant.parse("2024-01-01T00:00:00Z"),
                ArticleStatus.PUBLISHED,
                "https://example.com",
                "https://example.com/og",
                false,
                "blog",
                new ArticleCallToActionDto("", "", null),
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );

        mapper.applyUpsert(target, request, Set.of());

        assertThat(target.getPrimaryCta().getLabel()).isEqualTo("Learn more");
        assertThat(target.getPrimaryCta().getHref()).isEqualTo("/");
        assertThat(target.getSecondaryCta().getLabel()).isEqualTo("Explore");
        assertThat(target.getSecondaryCta().getHref()).isEqualTo("/");
    }

    @Test
    @DisplayName("applyUpsert defaults status/noindex/contentGroup when missing")
    void applyUpsertDefaultsFields() {
        Article target = new Article();
        target.setStatus(null);
        target.setNoindex(true);
        target.setContentGroup("other");

        ArticleUpsertRequest request = new ArticleUpsertRequest(
                "slug",
                "title",
                "description",
                "excerpt",
                null, // hero
                List.of("tag"),
                null, // author
                "5 min",
                Instant.parse("2024-01-01T00:00:00Z"),
                null, // status
                null, // canonical
                null, // ogImage
                null, // noindex
                null, // contentGroup
                null, // primary CTA
                null, // secondary CTA
                List.<ArticleStatDto>of(),
                List.<String>of(),
                List.<String>of(),
                List.<ArticleSectionDto>of(),
                List.<ArticleFaqDto>of(),
                List.<ArticleReferenceDto>of()
        );

        mapper.applyUpsert(target, request, Set.of());

        assertThat(target.getStatus()).isEqualTo(ArticleStatus.DRAFT);
        assertThat(target.getNoindex()).isFalse();
        assertThat(target.getContentGroup()).isEqualTo("blog");
    }

    @Test
    @DisplayName("applyUpsert enforces required nested fields")
    void applyUpsertRequiresNestedFields() {
        Article target = new Article();
        ArticleUpsertRequest request = new ArticleUpsertRequest(
                "slug",
                "title",
                "description",
                "excerpt",
                "hero",
                List.of("tag"),
                new ArticleAuthorDto("Author", "Role"),
                "5 min",
                Instant.parse("2024-01-01T00:00:00Z"),
                ArticleStatus.PUBLISHED,
                "https://example.com",
                "https://example.com/og",
                false,
                "blog",
                new ArticleCallToActionDto("Label", "/href", null),
                new ArticleCallToActionDto("Label2", "/href2", null),
                List.of(new ArticleStatDto(null, "val", null, null)),
                List.<String>of(),
                List.<String>of(),
                List.of(new ArticleSectionDto("sec1", null, null, null)),
                List.<ArticleFaqDto>of(),
                List.<ArticleReferenceDto>of()
        );

        assertThatThrownBy(() -> mapper.applyUpsert(target, request, Set.of()))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("toAuthorEntity defaults missing values to Unknown/Author")
    void toAuthorEntityDefaults() {
        Article target = new Article();
        ArticleUpsertRequest request = new ArticleUpsertRequest(
                "slug",
                "title",
                "description",
                "excerpt",
                "hero",
                List.of("tag"),
                new ArticleAuthorDto(" ", null),
                "5 min",
                Instant.parse("2024-01-01T00:00:00Z"),
                ArticleStatus.PUBLISHED,
                "https://example.com",
                "https://example.com/og",
                false,
                "blog",
                null,
                null,
                List.<ArticleStatDto>of(),
                List.<String>of(),
                List.<String>of(),
                List.<ArticleSectionDto>of(),
                List.<ArticleFaqDto>of(),
                List.<ArticleReferenceDto>of()
        );

        mapper.applyUpsert(target, request, Set.of());

        assertThat(target.getAuthor().getName()).isEqualTo("Unknown");
        assertThat(target.getAuthor().getTitle()).isEqualTo("Author");
    }

    @Test
    @DisplayName("toDto provides non-null author and CTAs even when entity fields are null")
    void toDtoProvidesDefaultsForNullEmbeddables() {
        Article article = new Article();
        article.setSlug("slug");
        article.setTitle("title");
        article.setDescription("desc");
        article.setExcerpt("ex");
        article.setStatus(ArticleStatus.PUBLISHED);
        article.setContentGroup("blog");

        ArticleDto dto = mapper.toDto(article);

        assertThat(dto.author()).isNotNull();
        assertThat(dto.primaryCta()).isNotNull();
        assertThat(dto.secondaryCta()).isNotNull();
        assertThat(dto.author().name()).isEqualTo("Unknown");
        assertThat(dto.author().title()).isEqualTo("Author");
        assertThat(dto.primaryCta().label()).isEqualTo("Learn more");
        assertThat(dto.secondaryCta().label()).isEqualTo("Learn more");
    }

    @Test
    @DisplayName("toSitemapEntry falls back to slug when canonical is missing")
    void toSitemapEntryUsesSlugFallback() {
        ArticleSitemapProjection projection = new ArticleSitemapProjection() {
            @Override public String getSlug() { return "sluggy"; }
            @Override public String getCanonicalUrl() { return null; }
            @Override public Instant getPublishedAt() { return Instant.parse("2024-01-01T00:00:00Z"); }
            @Override public Instant getUpdatedAt() { return null; }
        };

        SitemapEntryDto dto = mapper.toSitemapEntry(projection);

        assertThat(dto.url()).isEqualTo("/blog/sluggy");
        assertThat(dto.updatedAt()).isEqualTo(Instant.parse("2024-01-01T00:00:00Z"));
    }

    @Test
    @DisplayName("toSitemapEntry throws when both canonical and slug are missing")
    void toSitemapEntryRequiresUrlSource() {
        ArticleSitemapProjection projection = new ArticleSitemapProjection() {
            @Override public String getSlug() { return null; }
            @Override public String getCanonicalUrl() { return null; }
            @Override public Instant getPublishedAt() { return Instant.parse("2024-01-01T00:00:00Z"); }
            @Override public Instant getUpdatedAt() { return null; }
        };

        assertThatThrownBy(() -> mapper.toSitemapEntry(projection))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("toSitemapEntry throws when slug is blank and canonical missing")
    void toSitemapEntryRejectsBlankSlugWhenCanonicalMissing() {
        ArticleSitemapProjection projection = new ArticleSitemapProjection() {
            @Override public String getSlug() { return "  "; }
            @Override public String getCanonicalUrl() { return null; }
            @Override public Instant getPublishedAt() { return Instant.parse("2024-01-01T00:00:00Z"); }
            @Override public Instant getUpdatedAt() { return null; }
        };

        assertThatThrownBy(() -> mapper.toSitemapEntry(projection))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
