package uk.gegc.quizmaker.features.quiz.domain.model;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "quiz_generation_coverage")
@Getter
@NoArgsConstructor
public class QuizGenerationCoverage {

    @Id
    @Column(name = "job_id", nullable = false, updatable = false)
    private UUID jobId;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, updatable = false, length = 24)
    private GenerationCoverageOutcome outcome;

    @Column(name = "threshold_percent", nullable = false, updatable = false)
    private int thresholdPercent;

    @Column(name = "requested_count", nullable = false, updatable = false)
    private int requestedCount;

    @Column(name = "accepted_count", nullable = false, updatable = false)
    private int acceptedCount;

    @Column(name = "missing_count", nullable = false, updatable = false)
    private int missingCount;

    @Column(name = "discarded_count", nullable = false, updatable = false)
    private int discardedCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @OneToMany(mappedBy = "coverage", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private List<QuizGenerationTypeCoverage> types = new ArrayList<>();

    public QuizGenerationCoverage(
            UUID jobId,
            GenerationCoverageOutcome outcome,
            int thresholdPercent,
            int requestedCount,
            int acceptedCount,
            int missingCount,
            int discardedCount,
            LocalDateTime createdAt
    ) {
        this.jobId = Objects.requireNonNull(jobId, "jobId must not be null");
        this.outcome = Objects.requireNonNull(outcome, "outcome must not be null");
        this.thresholdPercent = thresholdPercent;
        this.requestedCount = requestedCount;
        this.acceptedCount = acceptedCount;
        this.missingCount = missingCount;
        this.discardedCount = discardedCount;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    public void addType(
            QuestionType questionType,
            int requested,
            int accepted,
            int missing
    ) {
        types.add(new QuizGenerationTypeCoverage(this, questionType, requested, accepted, missing));
    }
}
