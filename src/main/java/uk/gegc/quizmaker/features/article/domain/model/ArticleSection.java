package uk.gegc.quizmaker.features.article.domain.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "article_sections")
@Getter
@Setter
@NoArgsConstructor
public class ArticleSection {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "section_row_id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "article_id", nullable = false)
    private Article article;

    @Column(name = "section_id", nullable = false, length = 255)
    private String sectionId;

    @Column(name = "title", nullable = false, length = 512)
    private String title;

    @Column(name = "summary", columnDefinition = "TEXT")
    private String summary;

    @Column(name = "content", columnDefinition = "TEXT")
    private String content;

    @Column(name = "position", nullable = false)
    private Integer position;
}
