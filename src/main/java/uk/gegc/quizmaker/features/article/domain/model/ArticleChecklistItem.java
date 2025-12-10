package uk.gegc.quizmaker.features.article.domain.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "article_checklist_items")
@Getter
@Setter
@NoArgsConstructor
public class ArticleChecklistItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "checklist_item_id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "article_id", nullable = false)
    private Article article;

    @Column(name = "content", nullable = false, length = 1024)
    private String content;

    @Column(name = "position", nullable = false)
    private Integer position;
}
