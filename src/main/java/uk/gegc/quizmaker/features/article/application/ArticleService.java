package uk.gegc.quizmaker.features.article.application;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import uk.gegc.quizmaker.features.article.api.dto.*;
import uk.gegc.quizmaker.features.article.domain.model.ArticleStatus;

import java.util.List;
import java.util.UUID;

public interface ArticleService {

    Page<ArticleListItemDto> searchArticles(ArticleSearchCriteria criteria, Pageable pageable);

    ArticleDto getArticle(UUID articleId, boolean includeDrafts);

    ArticleDto getArticleBySlug(String slug, boolean includeDrafts);

    List<ArticleDto> getArticlesByIds(List<UUID> articleIds);

    ArticleDto createArticle(String username, ArticleUpsertRequest request);

    List<ArticleDto> createArticles(String username, List<ArticleUpsertRequest> requests);

    ArticleDto updateArticle(String username, UUID articleId, ArticleUpsertRequest request);

    List<ArticleDto> updateArticles(String username, List<ArticleBulkUpdateItem> updates);

    void deleteArticle(String username, UUID articleId);

    void deleteArticles(String username, List<UUID> articleIds);

    List<ArticleTagWithCountDto> getTagsWithCounts(ArticleStatus status);

    List<SitemapEntryDto> getSitemapEntries(ArticleStatus status);
}
