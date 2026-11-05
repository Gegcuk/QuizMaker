package uk.gegc.quizmaker.features.question.domain.model;

import jakarta.persistence.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.util.UUID;

/** The composite database FK pins the question's recorded owner; this row is never reassigned. */
@Entity
@Table(name = "question_source_authorities")
public class QuestionSourceAuthority {
    @Id
    @Column(name = "question_id", nullable = false, updatable = false)
    private UUID questionId;

    @MapsId
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "question_id", nullable = false, updatable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Question question;

    @Column(name = "owner_id", nullable = false, updatable = false)
    private UUID ownerId;

    protected QuestionSourceAuthority() { }

    QuestionSourceAuthority(Question question, UUID ownerId) {
        this.question = question;
        this.ownerId = ownerId;
    }
}
