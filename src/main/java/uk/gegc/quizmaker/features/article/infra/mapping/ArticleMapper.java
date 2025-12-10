package uk.gegc.quizmaker.features.article.infra.mapping;

import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.features.article.api.dto.*;
import uk.gegc.quizmaker.features.article.domain.model.*;
import uk.gegc.quizmaker.features.article.domain.repository.projection.ArticleSitemapProjection;
import uk.gegc.quizmaker.features.article.domain.repository.projection.ArticleTagCountProjection;
import uk.gegc.quizmaker.features.tag.domain.model.Tag;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@Component
public class ArticleMapper {

    public ArticleDto toDto(Article article) {
        if (article == null) {
            return null;
        }

        return new ArticleDto(
                article.getId(),
                article.getSlug(),
                article.getTitle(),
                article.getDescription(),
                article.getExcerpt(),
                article.getHeroKicker(),
                mapTags(article.getTags()),
                toAuthorDto(article.getAuthor()),
                article.getReadingTime(),
                article.getPublishedAt(),
                article.getUpdatedAt(),
                article.getStatus(),
                article.getCanonicalUrl(),
                article.getOgImage(),
                article.getNoindex(),
                article.getContentGroup(),
                toCtaDto(article.getPrimaryCta()),
                toCtaDto(article.getSecondaryCta()),
                mapStats(article.getStats()),
                mapKeyPoints(article.getKeyPoints()),
                mapChecklist(article.getChecklistItems()),
                mapSections(article.getSections()),
                mapFaqs(article.getFaqs()),
                mapReferences(article.getReferences()),
                article.getRevision()
        );
    }

    public ArticleListItemDto toListItem(Article article) {
        if (article == null) {
            return null;
        }

        return new ArticleListItemDto(
                article.getId(),
                article.getSlug(),
                article.getTitle(),
                article.getDescription(),
                article.getExcerpt(),
                article.getHeroKicker(),
                mapTags(article.getTags()),
                toAuthorDto(article.getAuthor()),
                article.getReadingTime(),
                article.getPublishedAt(),
                article.getUpdatedAt(),
                article.getStatus(),
                article.getContentGroup(),
                article.getCanonicalUrl(),
                article.getOgImage(),
                article.getNoindex(),
                toCtaDto(article.getPrimaryCta()),
                toCtaDto(article.getSecondaryCta()),
                article.getRevision()
        );
    }

    public ArticleTagWithCountDto toTagWithCount(ArticleTagCountProjection projection) {
        if (projection == null) {
            return null;
        }
        return new ArticleTagWithCountDto(projection.getTagName(), projection.getUsageCount());
    }

    public SitemapEntryDto toSitemapEntry(ArticleSitemapProjection projection) {
        if (projection == null) {
            return null;
        }

        String url = Optional.ofNullable(projection.getCanonicalUrl())
                .orElseGet(() -> projection.getSlug() == null ? null : "/blog/" + projection.getSlug());
        if (url == null) {
            throw new IllegalArgumentException("Sitemap projection must contain either canonicalUrl or slug");
        }

        return new SitemapEntryDto(
                url,
                projection.getUpdatedAt() != null ? projection.getUpdatedAt() : projection.getPublishedAt(),
                "weekly",
                0.8
        );
    }

    private ArticleAuthorDto toAuthorDto(ArticleAuthor author) {
        ArticleAuthor safeAuthor = author != null ? author : new ArticleAuthor("Unknown", "Author");
        String title = safeAuthor.getTitle() != null ? safeAuthor.getTitle() : "Author";
        return new ArticleAuthorDto(safeAuthor.getName(), title);
    }

    private ArticleCallToActionDto toCtaDto(ArticleCallToAction cta) {
        ArticleCallToAction safeCta = cta != null ? cta : new ArticleCallToAction("Learn more", "/", null);
        return new ArticleCallToActionDto(safeCta.getLabel(), safeCta.getHref(), safeCta.getEventName());
    }

    private List<String> mapTags(Set<Tag> tags) {
        return tags == null
                ? List.of()
                : tags.stream()
                .filter(Objects::nonNull)
                .map(Tag::getName)
                .toList();
    }

    private List<ArticleStatDto> mapStats(List<ArticleStat> stats) {
        return stats == null
                ? List.of()
                : stats.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(this::safePosition))
                .map(stat -> new ArticleStatDto(
                        stat.getLabel(),
                        stat.getValue(),
                        stat.getDetail(),
                        stat.getLink()
                ))
                .toList();
    }

    private List<String> mapKeyPoints(List<ArticleKeyPoint> keyPoints) {
        return keyPoints == null
                ? List.of()
                : keyPoints.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(this::safePosition))
                .map(ArticleKeyPoint::getContent)
                .toList();
    }

    private List<String> mapChecklist(List<ArticleChecklistItem> checklistItems) {
        return checklistItems == null
                ? List.of()
                : checklistItems.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(this::safePosition))
                .map(ArticleChecklistItem::getContent)
                .toList();
    }

    private List<ArticleSectionDto> mapSections(List<ArticleSection> sections) {
        return sections == null
                ? List.of()
                : sections.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(this::safePosition))
                .map(section -> new ArticleSectionDto(
                        section.getSectionId(),
                        section.getTitle(),
                        section.getSummary(),
                        section.getContent()
                ))
                .toList();
    }

    private List<ArticleFaqDto> mapFaqs(List<ArticleFaq> faqs) {
        return faqs == null
                ? List.of()
                : faqs.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(this::safePosition))
                .map(faq -> new ArticleFaqDto(faq.getQuestion(), faq.getAnswer()))
                .toList();
    }

    private List<ArticleReferenceDto> mapReferences(List<ArticleReference> references) {
        return references == null
                ? List.of()
                : references.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(this::safePosition))
                .map(ref -> new ArticleReferenceDto(
                        ref.getTitle(),
                        ref.getUrl(),
                        ref.getSourceType()
                ))
                .toList();
    }

    private int safePosition(Object withPosition) {
        Integer position = null;
        if (withPosition instanceof ArticleStat stat) {
            position = stat.getPosition();
        } else if (withPosition instanceof ArticleKeyPoint keyPoint) {
            position = keyPoint.getPosition();
        } else if (withPosition instanceof ArticleChecklistItem checklistItem) {
            position = checklistItem.getPosition();
        } else if (withPosition instanceof ArticleSection section) {
            position = section.getPosition();
        } else if (withPosition instanceof ArticleFaq faq) {
            position = faq.getPosition();
        } else if (withPosition instanceof ArticleReference reference) {
            position = reference.getPosition();
        }
        return position == null ? Integer.MAX_VALUE : position;
    }
}
