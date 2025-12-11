package uk.gegc.quizmaker.features.article.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ArticleAuthor {

    @Column(name = "author_name", nullable = false, length = 255)
    private String name;

    @Column(name = "author_title", length = 255)
    private String title;
}
