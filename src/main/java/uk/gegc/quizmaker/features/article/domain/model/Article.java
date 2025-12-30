package uk.gegc.quizmaker.features.article.domain.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import jakarta.validation.constraints.NotNull;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import uk.gegc.quizmaker.features.tag.domain.model.Tag;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "articles")
@Getter
@Setter
@NoArgsConstructor
public class Article {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "article_id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "slug", nullable = false, unique = true, length = 255)
    private String slug;

    @Column(name = "title", nullable = false, length = 512)
    private String title;

    @Column(name = "description", nullable = false, length = 2048)
    private String description;

    @Column(name = "excerpt", nullable = false, length = 1024)
    private String excerpt;

    @Column(name = "hero_kicker", length = 255)
    private String heroKicker;

    @Column(name = "hero_image_asset_id")
    private UUID heroImageAssetId;

    @Column(name = "hero_image_alt", length = 512)
    private String heroImageAlt;

    @Column(name = "hero_image_caption", length = 1024)
    private String heroImageCaption;

    @ManyToMany(fetch = FetchType.LAZY, cascade = {CascadeType.PERSIST, CascadeType.MERGE})
    @JoinTable(
            name = "article_tags",
            joinColumns = @JoinColumn(name = "article_id", nullable = false),
            inverseJoinColumns = @JoinColumn(name = "tag_id", nullable = false)
    )
    private Set<Tag> tags = new HashSet<>();

    @Column(name = "reading_time", nullable = false, length = 100)
    private String readingTime;

    @Column(name = "published_at", nullable = false)
    private Instant publishedAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ArticleStatus status;

    @Column(name = "canonical_url", length = 2048)
    private String canonicalUrl;

    @Column(name = "og_image", length = 2048)
    private String ogImage;

    @Column(name = "noindex", nullable = false)
    private Boolean noindex;

    @Convert(converter = ArticleContentTypeConverter.class)
    @Column(name = "content_group", nullable = false, length = 100)
    private ArticleContentType contentGroup;

    @Embedded
    @NotNull
    private ArticleAuthor author;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "label", column = @Column(name = "primary_cta_label", length = 255, nullable = false)),
            @AttributeOverride(name = "href", column = @Column(name = "primary_cta_href", length = 2048, nullable = false)),
            @AttributeOverride(name = "eventName", column = @Column(name = "primary_cta_event_name", length = 255))
    })
    @NotNull
    private ArticleCallToAction primaryCta;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "label", column = @Column(name = "secondary_cta_label", length = 255, nullable = false)),
            @AttributeOverride(name = "href", column = @Column(name = "secondary_cta_href", length = 2048, nullable = false)),
            @AttributeOverride(name = "eventName", column = @Column(name = "secondary_cta_event_name", length = 255))
    })
    @NotNull
    private ArticleCallToAction secondaryCta;

    @OneToMany(mappedBy = "article", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position ASC")
    @BatchSize(size = 50)
    private List<ArticleStat> stats = new ArrayList<>();

    @OneToMany(mappedBy = "article", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position ASC")
    @BatchSize(size = 50)
    private List<ArticleKeyPoint> keyPoints = new ArrayList<>();

    @OneToMany(mappedBy = "article", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position ASC")
    @BatchSize(size = 50)
    private List<ArticleChecklistItem> checklistItems = new ArrayList<>();

    @OneToMany(mappedBy = "article", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position ASC")
    @BatchSize(size = 50)
    private List<ArticleSection> sections = new ArrayList<>();

    @Convert(converter = ArticleContentBlocksConverter.class)
    @Column(name = "content_blocks", columnDefinition = "JSON")
    private List<ArticleContentBlock> contentBlocks = new ArrayList<>();

    @OneToMany(mappedBy = "article", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position ASC")
    @BatchSize(size = 50)
    private List<ArticleFaq> faqs = new ArrayList<>();

    @OneToMany(mappedBy = "article", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position ASC")
    @BatchSize(size = 50)
    private List<ArticleReference> references = new ArrayList<>();

    @Version
    @Column(name = "revision")
    private Integer revision;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private Instant createdAt;

    @PrePersist
    public void prePersist() {
        if (status == null) {
            status = ArticleStatus.DRAFT;
        }
        if (contentGroup == null) {
            contentGroup = ArticleContentType.BLOG;
        }
        if (noindex == null) {
            noindex = false;
        }
        if (author == null) {
            author = new ArticleAuthor("Unknown", "Author");
        }
        if (primaryCta == null) {
            primaryCta = new ArticleCallToAction("Learn more", "/", null);
        }
        if (secondaryCta == null) {
            secondaryCta = new ArticleCallToAction("Explore", "/", null);
        }
        if (contentBlocks == null) {
            contentBlocks = new ArrayList<>();
        }
    }
}
