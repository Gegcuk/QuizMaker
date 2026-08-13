package uk.gegc.quizmaker.features.quiz.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;

import java.util.Objects;

@Entity
@Table(name = "quiz_generation_type_coverage")
@Getter
@NoArgsConstructor
public class QuizGenerationTypeCoverage {

    @EmbeddedId
    private QuizGenerationTypeCoverageId id;

    @MapsId("jobId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_id", nullable = false, updatable = false)
    private QuizGenerationCoverage coverage;

    @Column(name = "requested_count", nullable = false, updatable = false)
    private int requestedCount;

    @Column(name = "accepted_count", nullable = false, updatable = false)
    private int acceptedCount;

    @Column(name = "missing_count", nullable = false, updatable = false)
    private int missingCount;

    QuizGenerationTypeCoverage(
            QuizGenerationCoverage coverage,
            QuestionType questionType,
            int requestedCount,
            int acceptedCount,
            int missingCount
    ) {
        this.coverage = Objects.requireNonNull(coverage, "coverage must not be null");
        this.id = new QuizGenerationTypeCoverageId(
                coverage.getJobId(),
                Objects.requireNonNull(questionType, "questionType must not be null")
        );
        this.requestedCount = requestedCount;
        this.acceptedCount = acceptedCount;
        this.missingCount = missingCount;
    }

    public QuestionType getQuestionType() {
        return id.getQuestionType();
    }
}
