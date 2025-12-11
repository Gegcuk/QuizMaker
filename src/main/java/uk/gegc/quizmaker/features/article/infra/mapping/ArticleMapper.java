package uk.gegc.quizmaker.features.article.infra.mapping;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import uk.gegc.quizmaker.features.article.api.dto.*;
import uk.gegc.quizmaker.features.article.domain.model.*;
import uk.gegc.quizmaker.features.article.domain.repository.projection.ArticleSitemapProjection;
import uk.gegc.quizmaker.features.article.domain.repository.projection.ArticleTagCountProjection;
import uk.gegc.quizmaker.features.tag.domain.model.Tag;
import uk.gegc.quizmaker.shared.exception.ValidationException;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@Component
public class ArticleMapper {

    public Article toEntity(ArticleUpsertRequest request, Set<Tag> tags) {
        Article article = new Article();
        applyUpsert(article, request, tags);
        return article;
    }

    public void applyUpsert(Article target, ArticleUpsertRequest request, Set<Tag> tags) {
        if (target == null || request == null) {
            return;
        }
        target.setSlug(request.slug());
        target.setTitle(request.title());
        target.setDescription(request.description());
        target.setExcerpt(request.excerpt());
        target.setHeroKicker(request.heroKicker());
        target.setTags(tags != null ? tags : Set.of());
        target.setReadingTime(request.readingTime());
        target.setPublishedAt(request.publishedAt());
        target.setUpdatedAt(request.updatedAt());
        if (request.status() != null) {
            target.setStatus(request.status());
        } else if (target.getStatus() == null) {
            target.setStatus(ArticleStatus.DRAFT);
        }
        target.setCanonicalUrl(request.canonicalUrl());
        target.setOgImage(request.ogImage());
        target.setNoindex(request.noindex() != null ? request.noindex() : Boolean.FALSE);
        target.setContentGroup(request.contentGroup() != null ? request.contentGroup() : "blog");
        target.setAuthor(toAuthorEntity(request.author()));
        target.setPrimaryCta(toCtaEntity(request.primaryCta(), "Learn more", "/"));
        target.setSecondaryCta(toCtaEntity(request.secondaryCta(), "Explore", "/"));

        if (request.stats() != null) {
            rebuildStats(target, request.stats());
        }
        if (request.keyPoints() != null) {
            rebuildKeyPoints(target, request.keyPoints());
        }
        if (request.checklist() != null) {
            rebuildChecklist(target, request.checklist());
        }
        if (request.sections() != null) {
            rebuildSections(target, request.sections());
        }
        if (request.faqs() != null) {
            rebuildFaqs(target, request.faqs());
        }
        if (request.references() != null) {
            rebuildReferences(target, request.references());
        }
    }

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

    private ArticleAuthor toAuthorEntity(ArticleAuthorDto dto) {
        if (dto == null) {
            return new ArticleAuthor("Unknown", "Author");
        }
        String name = requireOrDefault(dto.name(), "Unknown", "Author name");
        String title = requireOrDefault(dto.title(), "Author", "Author title");
        return new ArticleAuthor(name, title);
    }

    private ArticleCallToAction toCtaEntity(ArticleCallToActionDto dto, String defaultLabel, String defaultHref) {
        if (dto == null) {
            return new ArticleCallToAction(defaultLabel, defaultHref, null);
        }
        String label = StringUtils.hasText(dto.label()) ? dto.label() : defaultLabel;
        String href = StringUtils.hasText(dto.href()) ? dto.href() : defaultHref;
        return new ArticleCallToAction(label, href, dto.eventName());
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

    private void rebuildStats(Article article, List<ArticleStatDto> stats) {
        if (stats == null || stats.isEmpty()) {
            article.getStats().clear();
            return;
        }
        article.getStats().clear();
        int index = 0;
        for (ArticleStatDto dto : stats) {
            if (dto == null) {
                continue;
            }
            ArticleStat stat = new ArticleStat();
            stat.setArticle(article);
            stat.setLabel(require(dto.label(), "Stat label"));
            stat.setValue(require(dto.value(), "Stat value"));
            stat.setDetail(dto.detail());
            stat.setLink(dto.link());
            stat.setPosition(index++);
            article.getStats().add(stat);
        }
    }

    private void rebuildKeyPoints(Article article, List<String> keyPoints) {
        if (keyPoints == null || keyPoints.isEmpty()) {
            article.getKeyPoints().clear();
            return;
        }
        article.getKeyPoints().clear();
        int index = 0;
        for (String kp : keyPoints) {
            if (kp == null) {
                continue;
            }
            ArticleKeyPoint keyPoint = new ArticleKeyPoint();
            keyPoint.setArticle(article);
            keyPoint.setContent(kp);
            keyPoint.setPosition(index++);
            article.getKeyPoints().add(keyPoint);
        }
    }

    private void rebuildChecklist(Article article, List<String> checklist) {
        if (checklist == null || checklist.isEmpty()) {
            article.getChecklistItems().clear();
            return;
        }
        article.getChecklistItems().clear();
        int index = 0;
        for (String item : checklist) {
            if (item == null) {
                continue;
            }
            ArticleChecklistItem checklistItem = new ArticleChecklistItem();
            checklistItem.setArticle(article);
            checklistItem.setContent(item);
            checklistItem.setPosition(index++);
            article.getChecklistItems().add(checklistItem);
        }
    }

    private void rebuildSections(Article article, List<ArticleSectionDto> sections) {
        if (sections == null || sections.isEmpty()) {
            article.getSections().clear();
            return;
        }
        article.getSections().clear();
        int index = 0;
        for (ArticleSectionDto dto : sections) {
            if (dto == null) {
                continue;
            }
            ArticleSection section = new ArticleSection();
            section.setArticle(article);
            section.setSectionId(require(dto.sectionId(), "Section sectionId"));
            section.setTitle(require(dto.title(), "Section title"));
            section.setSummary(dto.summary());
            section.setContent(dto.content());
            section.setPosition(index++);
            article.getSections().add(section);
        }
    }

    private void rebuildFaqs(Article article, List<ArticleFaqDto> faqs) {
        if (faqs == null || faqs.isEmpty()) {
            article.getFaqs().clear();
            return;
        }
        article.getFaqs().clear();
        int index = 0;
        for (ArticleFaqDto dto : faqs) {
            if (dto == null) {
                continue;
            }
            ArticleFaq faq = new ArticleFaq();
            faq.setArticle(article);
            faq.setQuestion(require(dto.question(), "FAQ question"));
            faq.setAnswer(dto.answer());
            faq.setPosition(index++);
            article.getFaqs().add(faq);
        }
    }

    private void rebuildReferences(Article article, List<ArticleReferenceDto> references) {
        if (references == null || references.isEmpty()) {
            article.getReferences().clear();
            return;
        }
        article.getReferences().clear();
        int index = 0;
        for (ArticleReferenceDto dto : references) {
            if (dto == null) {
                continue;
            }
            ArticleReference reference = new ArticleReference();
            reference.setArticle(article);
            reference.setTitle(require(dto.title(), "Reference title"));
            reference.setUrl(require(dto.url(), "Reference url"));
            reference.setSourceType(dto.sourceType());
            reference.setPosition(index++);
            article.getReferences().add(reference);
        }
    }

    private String require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ValidationException(field + " is required");
        }
        return value;
    }

    private String requireOrDefault(String value, String defaultValue, String field) {
        if (value == null || value.isBlank()) {
            if (defaultValue != null) {
                return defaultValue;
            }
            throw new ValidationException(field + " is required");
        }
        return value;
    }
}
