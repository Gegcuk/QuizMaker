package uk.gegc.quizmaker.features.quiz.domain.events;

import org.springframework.context.ApplicationEvent;
import uk.gegc.quizmaker.features.quiz.application.generation.GenerationCoverageSnapshot;

import java.util.Objects;
import java.util.UUID;

public class QuizGenerationCoverageReconciledEvent extends ApplicationEvent {

    private final UUID jobId;
    private final GenerationCoverageSnapshot coverage;

    public QuizGenerationCoverageReconciledEvent(
            Object source,
            UUID jobId,
            GenerationCoverageSnapshot coverage
    ) {
        super(source);
        this.jobId = Objects.requireNonNull(jobId, "jobId must not be null");
        this.coverage = Objects.requireNonNull(coverage, "coverage must not be null");
    }

    public UUID getJobId() {
        return jobId;
    }

    public GenerationCoverageSnapshot getCoverage() {
        return coverage;
    }
}
