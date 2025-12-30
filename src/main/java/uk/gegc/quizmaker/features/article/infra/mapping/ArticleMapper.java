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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@Component
public class ArticleMapper {

    public Article toEntity(ArticleUpsertRequest request, Set<Tag> tags) {
        if (request == null) {
            throw new ValidationException("Request body is required");
        }
        Article article = new Article();
        applyUpsert(article, request, tags);
        return article;
    }

    public void applyUpsert(Article target, ArticleUpsertRequest request, Set<Tag> tags) {
        if (target == null) {
            throw new ValidationException("Target article is required");
        }
        if (request == null) {
            throw new ValidationException("Request body is required");
        }
        String normalizedSlug = request.slug() != null ? request.slug().trim() : null;
        target.setSlug(normalizedSlug);
        target.setTitle(request.title());
        target.setDescription(request.description());
        target.setExcerpt(request.excerpt());
        target.setHeroKicker(request.heroKicker());
        applyHeroImage(target, request.heroImage());
        Set<Tag> targetTags = target.getTags() != null ? target.getTags() : new HashSet<>();
        targetTags.clear();
        if (tags != null) {
            targetTags.addAll(tags);
        }
        target.setTags(targetTags);
        target.setReadingTime(request.readingTime());
        target.setPublishedAt(request.publishedAt());
        if (request.status() != null) {
            target.setStatus(request.status());
        } else if (target.getStatus() == null) {
            target.setStatus(ArticleStatus.DRAFT);
        }
        target.setCanonicalUrl(request.canonicalUrl());
        target.setOgImage(request.ogImage());
        target.setNoindex(request.noindex() != null ? request.noindex() : Boolean.FALSE);
        target.setContentGroup(request.contentGroup() != null ? request.contentGroup() : ArticleContentType.BLOG);
        target.setAuthor(toAuthorEntity(request.author()));
        target.setPrimaryCta(toCtaEntity(request.primaryCta(), "Learn more", "/"));
        target.setSecondaryCta(toCtaEntity(request.secondaryCta(), "Explore", "/"));

        rebuildStats(target, request.stats());
        rebuildKeyPoints(target, request.keyPoints());
        rebuildChecklist(target, request.checklist());
        rebuildBlocks(target, request.blocks());
        rebuildSections(target, request.sections());
        rebuildFaqs(target, request.faqs());
        rebuildReferences(target, request.references());
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
                mapHeroImage(article),
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
                toCtaDto(article.getPrimaryCta(), "Learn more", "/"),
                toCtaDto(article.getSecondaryCta(), "Explore", "/"),
                mapStats(article.getStats()),
                mapKeyPoints(article.getKeyPoints()),
                mapChecklist(article.getChecklistItems()),
                mapBlocks(article.getContentBlocks()),
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
                mapHeroImage(article),
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
                toCtaDto(article.getPrimaryCta(), "Learn more", "/"),
                toCtaDto(article.getSecondaryCta(), "Explore", "/"),
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

        String rawSlug = projection.getSlug();
        String slugValue = rawSlug != null && !rawSlug.isBlank() ? rawSlug : null;

        String url = projection.getCanonicalUrl();
        if (url == null && slugValue != null) {
            url = "/blog/" + slugValue;
        }
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

    private ArticleCallToActionDto toCtaDto(ArticleCallToAction cta, String defaultLabel, String defaultHref) {
        ArticleCallToAction safeCta = cta != null ? cta : new ArticleCallToAction(defaultLabel, defaultHref, null);
        String label = StringUtils.hasText(safeCta.getLabel()) ? safeCta.getLabel() : defaultLabel;
        String href = StringUtils.hasText(safeCta.getHref()) ? safeCta.getHref() : defaultHref;
        return new ArticleCallToActionDto(label, href, safeCta.getEventName());
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

    private void applyHeroImage(Article target, ArticleImageDto heroImage) {
        if (heroImage == null || heroImage.assetId() == null) {
            target.setHeroImageAssetId(null);
            target.setHeroImageAlt(null);
            target.setHeroImageCaption(null);
            return;
        }
        if (!StringUtils.hasText(heroImage.alt())) {
            throw new ValidationException("Hero image alt text is required");
        }
        target.setHeroImageAssetId(heroImage.assetId());
        target.setHeroImageAlt(heroImage.alt().trim());
        target.setHeroImageCaption(heroImage.caption());
    }

    private List<String> mapTags(Set<Tag> tags) {
        return tags == null
                ? List.of()
                : tags.stream()
                .filter(Objects::nonNull)
                .map(Tag::getName)
                .toList();
    }

    private ArticleImageDto mapHeroImage(Article article) {
        if (article == null || article.getHeroImageAssetId() == null) {
            return null;
        }
        return new ArticleImageDto(
                article.getHeroImageAssetId(),
                article.getHeroImageAlt(),
                article.getHeroImageCaption()
        );
    }

    private List<ArticleBlockDto> mapBlocks(List<ArticleContentBlock> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return List.of();
        }
        return blocks.stream()
                .filter(Objects::nonNull)
                .map(block -> new ArticleBlockDto(
                        block.getType(),
                        block.getText(),
                        block.getAssetId(),
                        block.getAlt(),
                        block.getCaption(),
                        block.getAlign()
                ))
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
            if (kp == null || kp.isBlank()) {
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
            if (item == null || item.isBlank()) {
                continue;
            }
            ArticleChecklistItem checklistItem = new ArticleChecklistItem();
            checklistItem.setArticle(article);
            checklistItem.setContent(item);
            checklistItem.setPosition(index++);
            article.getChecklistItems().add(checklistItem);
        }
    }

    private void rebuildBlocks(Article article, List<ArticleBlockDto> blocks) {
        List<ArticleContentBlock> targetBlocks = new ArrayList<>();
        if (blocks != null) {
            for (ArticleBlockDto dto : blocks) {
                if (dto == null) {
                    continue;
                }
                if (dto.type() == null) {
                    throw new ValidationException("Block type is required");
                }
                ArticleContentBlock block = new ArticleContentBlock();
                block.setType(dto.type());
                block.setText(StringUtils.hasText(dto.text()) ? dto.text() : null);
                block.setAssetId(dto.assetId());
                block.setAlt(dto.alt());
                block.setCaption(dto.caption());
                block.setAlign(dto.align());
                if (dto.type() == ArticleBlockType.IMAGE) {
                    if (dto.assetId() == null) {
                        throw new ValidationException("Image blocks require assetId");
                    }
                    if (!StringUtils.hasText(dto.alt())) {
                        throw new ValidationException("Alt text is required for image blocks");
                    }
                }
                targetBlocks.add(block);
            }
        }
        article.setContentBlocks(targetBlocks);
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
