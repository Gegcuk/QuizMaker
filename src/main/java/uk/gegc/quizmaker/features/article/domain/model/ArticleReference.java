package uk.gegc.quizmaker.features.article.domain.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "article_references")
@Getter
@Setter
@NoArgsConstructor
public class ArticleReference {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "reference_id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "article_id", nullable = false)
    private Article article;

    @Column(name = "title", nullable = false, length = 512)
    private String title;

    @Column(name = "url", nullable = false, length = 2048)
    private String url;

    @Column(name = "source_type", length = 255)
    private String sourceType;

    @Column(name = "position", nullable = false)
    private Integer position;
}
