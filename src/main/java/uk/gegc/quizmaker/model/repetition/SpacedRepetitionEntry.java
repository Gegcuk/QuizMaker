package uk.gegc.quizmaker.model.repetition;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import uk.gegc.quizmaker.model.question.Question;
import uk.gegc.quizmaker.model.user.User;

import java.time.Instant;
import java.util.UUID;

@Entity
@Getter
@Setter
@Table(name = "spaced_repetition_entry")
public class SpacedRepetitionEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "question_id", nullable = false)
    private Question question;

    @Column(name = "next_review_at", nullable = false)
    private Instant nextReviewAt;

    @Column(name = "interval_days", nullable = false)
    private Integer intervalDays;

    @Column(name = "repetition_count", nullable = false)
    private Integer repetitionCount;

    @Column(name = "ease_factor", nullable = false)
    private Double easeFactor;

}
