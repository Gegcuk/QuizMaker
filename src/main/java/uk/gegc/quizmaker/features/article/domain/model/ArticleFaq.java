package uk.gegc.quizmaker.features.article.domain.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "article_faqs")
@Getter
@Setter
@NoArgsConstructor
public class ArticleFaq {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "faq_id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "article_id", nullable = false)
    private Article article;

    @Column(name = "question", nullable = false, length = 1024)
    private String question;

    @Column(name = "answer", columnDefinition = "TEXT")
    private String answer;

    @Column(name = "position", nullable = false)
    private Integer position;
}
