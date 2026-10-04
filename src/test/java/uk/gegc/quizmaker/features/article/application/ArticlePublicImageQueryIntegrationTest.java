package uk.gegc.quizmaker.features.article.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityManager;
import org.hibernate.FlushMode;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import uk.gegc.quizmaker.features.article.api.dto.*;
import uk.gegc.quizmaker.features.article.application.impl.ArticleServiceImpl;
import uk.gegc.quizmaker.features.article.domain.model.Article;
import uk.gegc.quizmaker.features.article.domain.model.ArticleBlockType;
import uk.gegc.quizmaker.features.article.domain.model.ArticleContentType;
import uk.gegc.quizmaker.features.article.domain.model.ArticleStatus;
import uk.gegc.quizmaker.features.article.domain.repository.ArticleRepository;
import uk.gegc.quizmaker.features.article.infra.mapping.ArticleMapper;
import uk.gegc.quizmaker.features.media.application.impl.MediaAssetServiceImpl;
import uk.gegc.quizmaker.features.media.config.MediaStorageProperties;
import uk.gegc.quizmaker.features.media.domain.model.MediaAsset;
import uk.gegc.quizmaker.features.media.domain.model.MediaAssetStatus;
import uk.gegc.quizmaker.features.media.domain.model.MediaAssetType;
import uk.gegc.quizmaker.features.media.infra.mapping.MediaAssetMapper;
import uk.gegc.quizmaker.shared.security.AppPermissionEvaluator;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

@Tag("db-serial")
@DataJpaTest(showSql = false)
@ActiveProfiles("test-mysql")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=update",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "logging.level.org.hibernate.engine.internal.StatisticalLoggingSessionEventListener=OFF",
        "app.media.cdn-base-url=https://cdn.example.test"
})
@Import({ArticleServiceImpl.class, ArticleMapper.class, MediaAssetServiceImpl.class,
        MediaAssetMapper.class, MediaStorageProperties.class, ArticlePublicImageQueryIntegrationTest.QueryConfiguration.class})
class ArticlePublicImageQueryIntegrationTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class QueryConfiguration {
        @Bean
        CapturingInspector capturingInspector() {
            return new CapturingInspector();
        }

        @Bean
        HibernatePropertiesCustomizer queryCapture(CapturingInspector inspector) {
            return properties -> properties.put("hibernate.session_factory.statement_inspector", inspector);
        }
    }

    static class CapturingInspector implements StatementInspector {
        private final ThreadLocal<List<String>> statements = ThreadLocal.withInitial(ArrayList::new);

        @Override
        public String inspect(String sql) {
            statements.get().add(sql);
            return sql;
        }
    }

    @Autowired private ArticleService articleService;
    @Autowired private ArticleRepository articleRepository;
    @Autowired private ArticleMapper articleMapper;
    @Autowired private EntityManager entityManager;
    @Autowired private CapturingInspector inspector;
    @MockitoBean private AppPermissionEvaluator permissionEvaluator;
    @MockitoBean private S3Client s3Client;
    @MockitoBean private S3Presigner presigner;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final List<Article> articles = new ArrayList<>();
    private Statistics statistics;
    private String fixtureTag;

    @BeforeEach
    void persistMultiParentGraph() {
        fixtureTag = "image-query-" + UUID.randomUUID().toString().substring(0, 8);
        uk.gegc.quizmaker.features.tag.domain.model.Tag commonTag = tag(fixtureTag);
        for (int index = 0; index < 9; index++) {
            UUID heroId = persistMedia(MediaAssetType.IMAGE, MediaAssetStatus.READY);
            UUID inlineId = persistMedia(MediaAssetType.IMAGE, MediaAssetStatus.READY);
            Article article = articleMapper.toEntity(request(index, heroId, inlineId),
                    Set.of(commonTag, tag(fixtureTag + "-" + index)));
            articleRepository.save(article);
            articles.add(article);
        }
        entityManager.flush();
        entityManager.clear();
        statistics = entityManager.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
    }

    @Test
    void listImagesAndTagsUseBoundedQueriesAcrossPageSizes() throws Exception {
        Measurement small = measure(() -> articleService.searchArticles(criteria(), page(2)));
        Measurement large = measure(() -> articleService.searchArticles(criteria(), page(8)));

        assertThat(small.json().path("content")).hasSize(2);
        assertThat(large.json().path("content")).hasSize(8);
        assertThat(large.json().path("totalElements").asInt()).isEqualTo(9);
        large.json().path("content").forEach(this::assertPublicHero);
        large.json().path("content").forEach(article -> assertThat(article.path("ogImage").isNull()).isTrue());
        // Two full pages require the same article/count/media work, not one media query per row.
        assertThat(large.statements()).as("Executed SQL: %s", large.sql())
                .isEqualTo(small.statements()).isLessThanOrEqualTo(3L);
        verifyNoInteractions(s3Client, presigner);
    }

    @Test
    void bulkImagesAndAllChildCollectionsUseBoundedQueries() throws Exception {
        Measurement small = measure(() -> articleService.getArticlesByIds(ids(2)));
        Measurement large = measure(() -> articleService.getArticlesByIds(ids(8)));

        assertThat(small.json()).hasSize(2);
        assertThat(large.json()).hasSize(8);
        large.json().forEach(this::assertFullGraph);
        // Six independent child collections are batch-loaded once each; tags and media
        // must likewise stay bounded as the number of parent articles increases.
        assertThat(large.statements()).isEqualTo(small.statements()).isLessThanOrEqualTo(9L);
        verifyNoInteractions(s3Client, presigner);
    }

    @Test
    void idAndSlugDetailsSerializeResolvedImagesWithoutLazyDatabaseAccess() throws Exception {
        Article article = articles.get(0);
        entityManager.find(Article.class, article.getId()).setOgImage("https://example.test/social-cover.webp");
        entityManager.flush();
        Measurement byId = measure(() -> articleService.getArticle(article.getId(), false));
        Measurement bySlug = measure(() -> articleService.getArticleBySlug(article.getSlug(), false));

        assertFullGraph(byId.json());
        assertFullGraph(bySlug.json());
        assertThat(bySlug.json().path("tags")).containsExactlyInAnyOrderElementsOf(byId.json().path("tags"));
        ObjectNode byIdWithoutTags = ((ObjectNode) byId.json()).deepCopy();
        ObjectNode bySlugWithoutTags = ((ObjectNode) bySlug.json()).deepCopy();
        byIdWithoutTags.remove("tags");
        bySlugWithoutTags.remove("tags");
        assertThat(bySlugWithoutTags).isEqualTo(byIdWithoutTags);
        assertThat(byId.json().path("ogImage").asText()).isEqualTo("https://example.test/social-cover.webp");
        assertThat(byId.statements()).isBetween(1L, 9L);
        assertThat(bySlug.statements()).isBetween(1L, 9L);
        verifyNoInteractions(s3Client, presigner);
    }

    @Test
    void unavailableHeroAssetsDoNotHideArticlesOrExposePublicRenditions() throws Exception {
        List<UUID> unavailable = List.of(
                persistMedia(MediaAssetType.IMAGE, MediaAssetStatus.DELETED),
                persistMedia(MediaAssetType.IMAGE, MediaAssetStatus.UPLOADING),
                persistMedia(MediaAssetType.DOCUMENT, MediaAssetStatus.READY));
        for (int index = 0; index < unavailable.size(); index++) {
            Article article = entityManager.find(Article.class, articles.get(index).getId());
            article.setHeroImageAssetId(unavailable.get(index));
        }
        entityManager.flush();

        Measurement result = measure(() -> articleService.getArticlesByIds(ids(3)));

        assertThat(result.json()).hasSize(3);
        for (JsonNode article : result.json()) {
            assertThat(article.path("heroImage").path("rendition").isNull()).isTrue();
            assertThat(article.path("ogImage").isNull()).isTrue();
            assertThat(article.path("blocks")).hasSize(3);
        }
        verifyNoInteractions(s3Client, presigner);
    }

    private Measurement measure(Supplier<?> read) throws Exception {
        // Each measurement starts cold. An earlier read must not warm the next read's graph.
        entityManager.clear();
        entityManager.getEntityManagerFactory().getCache().evictAll();
        statistics.clear();
        inspector.statements.get().clear();
        // A DataJpaTest write transaction encloses the fixture and the service call.
        // Mirror the Hibernate settings of the service's real read-only transaction;
        // otherwise mutable JSON snapshots can flush incidental fixture updates.
        Session session = entityManager.unwrap(Session.class);
        boolean previousReadOnly = session.isDefaultReadOnly();
        FlushMode previousFlushMode = session.getHibernateFlushMode();
        session.setDefaultReadOnly(true);
        session.setHibernateFlushMode(FlushMode.MANUAL);
        try {
            Object response = read.get();
            long mappedStatements = statistics.getPrepareStatementCount();
            entityManager.clear();
            JsonNode json = objectMapper.readTree(objectMapper.writeValueAsBytes(response));
            assertThat(statistics.getPrepareStatementCount())
                    .as("DTO serialization after detachment must not run database queries")
                    .isEqualTo(mappedStatements);
            List<String> sql = List.copyOf(inspector.statements.get());
            assertThat(sql).as("Read-only article mapping must only issue SELECTs: %s", sql)
                    .allMatch(statement -> statement.stripLeading().toLowerCase(Locale.ROOT).startsWith("select"));
            return new Measurement(json, mappedStatements, sql);
        } finally {
            session.setDefaultReadOnly(previousReadOnly);
            session.setHibernateFlushMode(previousFlushMode);
        }
    }

    private void assertPublicHero(JsonNode article) {
        assertThat(article.path("tags")).hasSize(2);
        assertThat(article.path("heroImage").path("alt").asText()).isEqualTo("Hero alt");
        assertRendition(article.path("heroImage").path("rendition"));
    }

    private void assertFullGraph(JsonNode article) {
        assertPublicHero(article);
        for (String collection : List.of("stats", "keyPoints", "checklist", "sections", "faqs", "references")) {
            assertThat(article.path(collection)).as(collection).hasSize(2);
        }
        assertThat(article.path("blocks")).hasSize(3);
    }

    private void assertRendition(JsonNode rendition) {
        assertThat(rendition.path("url").asText()).startsWith("https://cdn.example.test/library/");
        assertThat(rendition.path("width").asInt()).isEqualTo(1280);
        assertThat(rendition.path("height").asInt()).isEqualTo(720);
        assertThat(rendition.path("mimeType").asText()).isEqualTo("image/webp");
    }

    private UUID persistMedia(MediaAssetType type, MediaAssetStatus status) {
        MediaAsset media = new MediaAsset();
        media.setId(UUID.randomUUID());
        media.setType(type);
        media.setStatus(status);
        media.setKey("library/" + media.getId() + ".webp");
        media.setMimeType(type == MediaAssetType.IMAGE ? "image/webp" : "application/pdf");
        media.setWidth(1280);
        media.setHeight(720);
        entityManager.persist(media);
        return media.getId();
    }

    private ArticleUpsertRequest request(int index, UUID heroId, UUID inlineId) {
        return new ArticleUpsertRequest(
                fixtureTag + "-" + index, "Article " + index, "Description", "Excerpt", "Hero",
                new ArticleImageDto(heroId, "Hero alt", "Hero caption"), List.of(),
                new ArticleAuthorDto("Author", "Teacher"), "5 minute read",
                Instant.parse("2026-01-01T00:00:00Z"), ArticleStatus.PUBLISHED,
                null, null, false, ArticleContentType.BLOG,
                new ArticleCallToActionDto("Learn", "/", null),
                new ArticleCallToActionDto("Explore", "/explore", null),
                List.of(new ArticleStatDto("First", "10", "Detail", null),
                        new ArticleStatDto("Second", "20", "Detail", null)),
                List.of("First key point", "Second key point"), List.of("First check", "Second check"),
                List.of(new ArticleBlockDto(ArticleBlockType.PARAGRAPH, "Paragraph", null, null, null, null),
                        new ArticleBlockDto(ArticleBlockType.IMAGE, null, inlineId, "Inline alt", "Caption", "center"),
                        new ArticleBlockDto(ArticleBlockType.IMAGE, null, heroId, "Repeated hero", null, "center")),
                List.of(new ArticleSectionDto("first", "First section", "Summary", "Content"),
                        new ArticleSectionDto("second", "Second section", "Summary", "Content")),
                List.of(new ArticleFaqDto("First question?", "Answer"), new ArticleFaqDto("Second question?", "Answer")),
                List.of(new ArticleReferenceDto("First source", "https://example.test/first", "journal"),
                        new ArticleReferenceDto("Second source", "https://example.test/second", "journal")));
    }

    private uk.gegc.quizmaker.features.tag.domain.model.Tag tag(String name) {
        var tag = new uk.gegc.quizmaker.features.tag.domain.model.Tag();
        tag.setName(name);
        return tag;
    }

    private List<UUID> ids(int size) {
        return articles.subList(0, size).stream().map(Article::getId).toList();
    }

    private ArticleSearchCriteria criteria() {
        return new ArticleSearchCriteria(ArticleStatus.PUBLISHED, List.of(fixtureTag), ArticleContentType.BLOG);
    }

    private PageRequest page(int size) {
        return PageRequest.of(0, size, Sort.by("slug"));
    }

    private record Measurement(JsonNode json, long statements, List<String> sql) {}
}
