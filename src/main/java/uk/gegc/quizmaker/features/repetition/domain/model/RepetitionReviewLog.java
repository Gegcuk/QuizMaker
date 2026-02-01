package uk.gegc.quizmaker.features.repetition.domain.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import uk.gegc.quizmaker.features.user.domain.model.User;

import java.time.Instant;
import java.util.UUID;

@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "repetition_review_log",
    uniqueConstraints = @UniqueConstraint(
            name = "uk_repetition_review_source",
            columnNames = {"source_type", "source_id"}
    )
)
public class RepetitionReviewLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "repetition_review_id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", referencedColumnName = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "entry_id", referencedColumnName = "id", nullable = false)
    private SpacedRepetitionEntry entry;

    @Enumerated(EnumType.STRING)
    @Column(name="content_type", nullable = false)
    private RepetitionContentType contentType;

    @Column(name = "content_id", nullable = false)
    private UUID contentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "grade", nullable = false, length = 10)
    private RepetitionEntryGrade grade;

    @Column(name = "reviewed_at", nullable = false, updatable = false)
    private Instant reviewedAt;

    @Column(name = "interval_days", nullable = false)
    private Integer intervalDays;

    @Column(name = "ease_factor", nullable = false)
    private Double easeFactor;

    @Column(name = "repetition_count", nullable = false)
    private Integer repetitionCount;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 30)
    private RepetitionReviewSourceType sourceType;

    @Column(name = "source_id")
    private UUID sourceId;

    @Column(name = "attempt_id")
    private UUID attemptId;

    @PrePersist
    private void prePersist() {
        if (reviewedAt == null) {
            reviewedAt = Instant.now();
        }
    }
}
