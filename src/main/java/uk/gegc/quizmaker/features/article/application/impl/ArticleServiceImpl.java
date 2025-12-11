package uk.gegc.quizmaker.features.article.application.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import uk.gegc.quizmaker.features.article.api.dto.*;
import uk.gegc.quizmaker.features.article.application.ArticleService;
import uk.gegc.quizmaker.features.article.domain.model.Article;
import uk.gegc.quizmaker.features.article.domain.model.ArticleStatus;
import uk.gegc.quizmaker.features.article.domain.repository.ArticleRepository;
import uk.gegc.quizmaker.features.article.domain.repository.ArticleSpecifications;
import uk.gegc.quizmaker.features.article.domain.repository.projection.ArticleSitemapProjection;
import uk.gegc.quizmaker.features.article.domain.repository.projection.ArticleTagCountProjection;
import uk.gegc.quizmaker.features.article.infra.mapping.ArticleMapper;
import uk.gegc.quizmaker.features.tag.domain.model.Tag;
import uk.gegc.quizmaker.features.tag.domain.repository.TagRepository;
import uk.gegc.quizmaker.shared.exception.ResourceNotFoundException;
import uk.gegc.quizmaker.shared.exception.ValidationException;

import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional
@RequiredArgsConstructor
public class ArticleServiceImpl implements ArticleService {

    private final ArticleRepository articleRepository;
    private final TagRepository tagRepository;
    private final ArticleMapper articleMapper;

    @Override
    @Transactional(readOnly = true)
    public Page<ArticleListItemDto> searchArticles(ArticleSearchCriteria criteria, Pageable pageable) {
        ArticleSearchCriteria effectiveCriteria = criteria != null ? criteria : new ArticleSearchCriteria(ArticleStatus.PUBLISHED, List.of(), "blog");
        return articleRepository.findAll(ArticleSpecifications.build(effectiveCriteria), pageable)
                .map(articleMapper::toListItem);
    }

    @Override
    @Transactional(readOnly = true)
    public ArticleDto getArticle(UUID articleId, boolean includeDrafts) {
        Article article = articleRepository.findById(articleId)
                .orElseThrow(() -> new ResourceNotFoundException("Article " + articleId + " not found"));
        if (!includeDrafts && article.getStatus() != ArticleStatus.PUBLISHED) {
            throw new ResourceNotFoundException("Article " + articleId + " not found");
        }
        return articleMapper.toDto(article);
    }

    @Override
    @Transactional(readOnly = true)
    public ArticleDto getArticleBySlug(String slug, boolean includeDrafts) {
        if (!StringUtils.hasText(slug)) {
            throw new ValidationException("Slug is required");
        }
        Optional<Article> articleOpt = includeDrafts
                ? articleRepository.findBySlug(slug)
                : articleRepository.findBySlugAndStatus(slug, ArticleStatus.PUBLISHED);
        Article article = articleOpt.orElseThrow(() -> new ResourceNotFoundException("Article with slug " + slug + " not found"));
        return articleMapper.toDto(article);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ArticleDto> getArticlesByIds(List<UUID> articleIds) {
        if (articleIds == null || articleIds.isEmpty()) {
            return List.of();
        }
        List<Article> articles = articleRepository.findAllById(articleIds);
        Map<UUID, Article> byId = articles.stream()
                .collect(Collectors.toMap(Article::getId, a -> a));
        return articleIds.stream()
                .map(byId::get)
                .filter(Objects::nonNull)
                .map(articleMapper::toDto)
                .toList();
    }

    @Override
    public ArticleDto createArticle(String username, ArticleUpsertRequest request) {
        validateUpsert(request);
        assertSlugAvailable(request.slug(), null);
        Set<Tag> tags = resolveTags(request.tags());
        Article article = articleMapper.toEntity(request, tags);
        Article saved = articleRepository.save(article);
        return articleMapper.toDto(saved);
    }

    @Override
    public List<ArticleDto> createArticles(String username, List<ArticleUpsertRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            return List.of();
        }
        checkDuplicateSlugs(requests);
        List<Article> articles = new ArrayList<>();
        for (ArticleUpsertRequest request : requests) {
            validateUpsert(request);
            assertSlugAvailable(request.slug(), null);
            Article article = articleMapper.toEntity(request, resolveTags(request.tags()));
            articles.add(article);
        }
        List<Article> saved = articleRepository.saveAll(articles);
        return saved.stream().map(articleMapper::toDto).toList();
    }

    @Override
    public ArticleDto updateArticle(String username, UUID articleId, ArticleUpsertRequest request) {
        validateUpsert(request);
        Article article = articleRepository.findById(articleId)
                .orElseThrow(() -> new ResourceNotFoundException("Article " + articleId + " not found"));
        if (StringUtils.hasText(request.slug())) {
            assertSlugAvailable(request.slug(), articleId);
        }
        articleMapper.applyUpsert(article, request, resolveTags(request.tags()));
        Article saved = articleRepository.save(article);
        return articleMapper.toDto(saved);
    }

    @Override
    public List<ArticleDto> updateArticles(String username, List<ArticleBulkUpdateItem> updates) {
        if (updates == null || updates.isEmpty()) {
            return List.of();
        }
        checkDuplicateSlugs(updates.stream().map(ArticleBulkUpdateItem::payload).toList());
        List<ArticleDto> results = new ArrayList<>();
        for (ArticleBulkUpdateItem update : updates) {
            if (update == null) {
                continue;
            }
            ArticleUpsertRequest request = update.payload();
            UUID id = update.articleId();
            validateUpsert(request);
            Article article = articleRepository.findById(id)
                    .orElseThrow(() -> new ResourceNotFoundException("Article " + id + " not found"));
            if (StringUtils.hasText(request.slug())) {
                assertSlugAvailable(request.slug(), id);
            }
            articleMapper.applyUpsert(article, request, resolveTags(request.tags()));
            results.add(articleMapper.toDto(articleRepository.save(article)));
        }
        return results;
    }

    @Override
    public void deleteArticle(String username, UUID articleId) {
        if (!articleRepository.existsById(articleId)) {
            throw new ResourceNotFoundException("Article " + articleId + " not found");
        }
        articleRepository.deleteById(articleId);
    }

    @Override
    public void deleteArticles(String username, List<UUID> articleIds) {
        if (articleIds == null || articleIds.isEmpty()) {
            return;
        }
        for (UUID id : articleIds) {
            if (!articleRepository.existsById(id)) {
                throw new ResourceNotFoundException("Article " + id + " not found");
            }
        }
        articleRepository.deleteAllById(articleIds);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ArticleTagWithCountDto> getTagsWithCounts(ArticleStatus status) {
        List<ArticleTagCountProjection> projections = articleRepository.countTags(status);
        return projections.stream()
                .map(articleMapper::toTagWithCount)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<SitemapEntryDto> getSitemapEntries(ArticleStatus status) {
        List<ArticleSitemapProjection> projections = articleRepository.findSitemapEntries(status != null ? status : ArticleStatus.PUBLISHED);
        return projections.stream()
                .map(articleMapper::toSitemapEntry)
                .toList();
    }

    private void validateUpsert(ArticleUpsertRequest request) {
        if (request == null) {
            throw new ValidationException("Request body is required");
        }
        List<String> errors = new ArrayList<>();
        if (!StringUtils.hasText(request.slug())) {
            errors.add("Slug is required");
        }
        if (!StringUtils.hasText(request.title())) {
            errors.add("Title is required");
        }
        if (!StringUtils.hasText(request.description())) {
            errors.add("Description is required");
        }
        if (!StringUtils.hasText(request.excerpt())) {
            errors.add("Excerpt is required");
        }
        if (!StringUtils.hasText(request.readingTime())) {
            errors.add("Reading time is required");
        }
        if (request.publishedAt() == null) {
            errors.add("PublishedAt is required");
        }
        if (request.status() == null) {
            errors.add("Status is required");
        }
        if (!errors.isEmpty()) {
            throw new ValidationException(String.join("; ", errors));
        }
    }

    private void assertSlugAvailable(String slug, UUID currentId) {
        if (!StringUtils.hasText(slug)) {
            throw new ValidationException("Slug is required");
        }
        Optional<Article> existing = articleRepository.findBySlug(slug);
        if (existing.isPresent() && (currentId == null || !existing.get().getId().equals(currentId))) {
            throw new ValidationException("Slug already in use: " + slug);
        }
    }

    private Set<Tag> resolveTags(List<String> tagNames) {
        if (tagNames == null || tagNames.isEmpty()) {
            return Set.of();
        }
        List<String> normalized = tagNames.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(StringUtils::hasText)
                .toList();
        if (normalized.isEmpty()) {
            return Set.of();
        }

        List<String> lowerNames = normalized.stream()
                .map(name -> name.toLowerCase(Locale.ROOT))
                .toList();

        List<Tag> existing = tagRepository.findByNameInIgnoreCase(lowerNames);
        Map<String, Tag> existingByLower = existing.stream()
                .collect(Collectors.toMap(tag -> tag.getName().toLowerCase(Locale.ROOT), tag -> tag, (a, b) -> a));

        Set<Tag> result = new HashSet<>(existing);
        for (String name : normalized) {
            String lower = name.toLowerCase(Locale.ROOT);
            if (!existingByLower.containsKey(lower)) {
                Tag tag = new Tag();
                tag.setName(name);
                result.add(tag);
            }
        }
        return result;
    }

    private void checkDuplicateSlugs(List<ArticleUpsertRequest> requests) {
        if (requests == null) {
            return;
        }
        Set<String> seen = new HashSet<>();
        for (ArticleUpsertRequest request : requests) {
            if (request == null || !StringUtils.hasText(request.slug())) {
                continue;
            }
            String normalized = request.slug().trim();
            if (!seen.add(normalized)) {
                throw new ValidationException("Duplicate slug in bulk request: " + normalized);
            }
        }
    }
}
