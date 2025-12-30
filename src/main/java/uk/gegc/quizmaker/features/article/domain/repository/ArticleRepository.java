package uk.gegc.quizmaker.features.article.domain.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import uk.gegc.quizmaker.features.article.domain.model.Article;
import uk.gegc.quizmaker.features.article.domain.model.ArticleStatus;
import uk.gegc.quizmaker.features.article.domain.repository.projection.ArticleSitemapProjection;
import uk.gegc.quizmaker.features.article.domain.repository.projection.ArticleTagCountProjection;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ArticleRepository extends JpaRepository<Article, UUID>, JpaSpecificationExecutor<Article> {

    Optional<Article> findBySlug(String slug);

    Optional<Article> findBySlugAndStatus(String slug, ArticleStatus status);

    boolean existsBySlug(String slug);

    @EntityGraph(attributePaths = "tags")
    Page<Article> findAllByStatus(ArticleStatus status, Pageable pageable);

    /**
     * Override Specification-based findAll to eagerly fetch tags for list views.
     */
    @Override
    @EntityGraph(attributePaths = "tags")
    Page<Article> findAll(Specification<Article> spec, Pageable pageable);

    @Query("""
            SELECT a.slug AS slug,
                   a.canonicalUrl AS canonicalUrl,
                   a.publishedAt AS publishedAt,
                   a.updatedAt AS updatedAt
            FROM Article a
            WHERE a.status = :status
              AND a.noindex = false
            """)
    List<ArticleSitemapProjection> findSitemapEntries(@Param("status") ArticleStatus status);

    @Query("""
            SELECT t.name AS tagName,
                   COUNT(DISTINCT a.id) AS usageCount
            FROM Article a
            JOIN a.tags t
            WHERE (:status IS NULL OR a.status = :status)
            GROUP BY t.name
            """)
    List<ArticleTagCountProjection> countTags(@Param("status") ArticleStatus status);
}
