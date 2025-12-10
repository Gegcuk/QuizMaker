package uk.gegc.quizmaker.features.article.domain.repository.projection;

import java.time.Instant;

public interface ArticleSitemapProjection {
    String getSlug();

    String getCanonicalUrl();

    Instant getPublishedAt();

    Instant getUpdatedAt();
}
