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
public class ArticleCallToAction {

    @Column(name = "label", nullable = false, length = 255)
    private String label;

    @Column(name = "href", nullable = false, length = 2048)
    private String href;

    @Column(name = "event_name", length = 255)
    private String eventName;
}
