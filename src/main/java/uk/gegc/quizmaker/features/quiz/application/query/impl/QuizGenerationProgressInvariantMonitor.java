package uk.gegc.quizmaker.features.quiz.application.query.impl;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizGenerationJob;

import java.util.EnumMap;
import java.util.Map;

/**
 * Records low-cardinality evidence when legacy or malformed persisted progress
 * needs normalization before it is returned to a client.
 */
@Component
public final class QuizGenerationProgressInvariantMonitor {

    static final String METRIC_NAME = "quiz.generation.progress.invariant.violations";

    private final Map<QuizGenerationJob.ProgressInvariantViolation, Counter> counters;

    public QuizGenerationProgressInvariantMonitor(MeterRegistry meterRegistry) {
        counters = new EnumMap<>(QuizGenerationJob.ProgressInvariantViolation.class);
        for (QuizGenerationJob.ProgressInvariantViolation violation
                : QuizGenerationJob.ProgressInvariantViolation.values()) {
            counters.put(violation, Counter.builder(METRIC_NAME)
                    .description("Persisted quiz generation progress values normalized at the API boundary")
                    .tag("reason", reason(violation))
                    .register(meterRegistry));
        }
    }

    public void observe(QuizGenerationJob job) {
        QuizGenerationJob.ProgressInvariantViolation violation = job.detectProgressInvariantViolation();
        if (violation != null) {
            counters.get(violation).increment();
        }
    }

    private String reason(QuizGenerationJob.ProgressInvariantViolation violation) {
        return switch (violation) {
            case INVALID_PERCENTAGE -> "invalid_percentage";
            case COMPLETED_BELOW_100 -> "completed_below_100";
            case NON_COMPLETED_AT_100 -> "non_completed_at_100";
        };
    }
}
