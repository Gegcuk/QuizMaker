package uk.gegc.quizmaker.features.article.domain.repository.projection;

public interface ArticleTagCountProjection {
    String getTagName();

    Long getUsageCount();
}
