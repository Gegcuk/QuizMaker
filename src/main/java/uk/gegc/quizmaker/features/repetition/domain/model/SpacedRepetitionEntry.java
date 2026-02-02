package uk.gegc.quizmaker.features.repetition.domain.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.user.domain.model.User;

import java.time.Instant;
import java.util.UUID;

@Entity
@Getter
@Setter
@Table(
        name = "spaced_repetition_entry",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_spaced_repetition_user_question",
                        columnNames = {"user_id", "question_id"}
                )
        }
)
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

    @Column(name = "last_reviewed_at")
    private Instant lastReviewedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "last_grade", length = 10)
    private RepetitionEntryGrade lastGrade;

    @Column(name = "reminder_enabled", nullable = false)
    private Boolean reminderEnabled = Boolean.TRUE;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
