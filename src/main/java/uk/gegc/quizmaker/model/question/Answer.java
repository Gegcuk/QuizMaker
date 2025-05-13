package uk.gegc.quizmaker.model.question;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import uk.gegc.quizmaker.model.attempt.Attempt;

import java.time.Instant;
import java.util.UUID;

@Entity
@Getter
@Setter
@Table(name = "answers")
public class Answer {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "attempt_id", nullable = false)
    private Attempt attempt;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "question_id", nullable = false)
    private Question question;

    @Column(name = "response", columnDefinition = "json", nullable = false)
    private String response;

    @Column(name = "is_correct")
    private Boolean isCorrect;

    @Column(name = "score")
    private Double score;

    @Column(name = "answered_at", nullable = false)
    private Instant answeredAt;


}
