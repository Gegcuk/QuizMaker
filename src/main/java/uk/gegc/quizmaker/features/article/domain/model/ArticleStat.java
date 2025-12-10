package uk.gegc.quizmaker.features.article.domain.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "article_stats")
@Getter
@Setter
@NoArgsConstructor
public class ArticleStat {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "stat_id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "article_id", nullable = false)
    private Article article;

    @Column(name = "label", nullable = false, length = 255)
    private String label;

    @Column(name = "value", nullable = false, length = 255)
    private String value;

    @Column(name = "detail", columnDefinition = "TEXT")
    private String detail;

    @Column(name = "link", length = 2048)
    private String link;

    @Column(name = "position", nullable = false)
    private Integer position;
}
