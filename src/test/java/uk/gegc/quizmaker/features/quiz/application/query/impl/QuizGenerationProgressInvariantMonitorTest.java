package uk.gegc.quizmaker.features.quiz.application.query.impl;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import uk.gegc.quizmaker.features.quiz.domain.model.GenerationStatus;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizGenerationJob;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Quiz generation progress invariant monitor tests")
class QuizGenerationProgressInvariantMonitorTest {

    private SimpleMeterRegistry meterRegistry;
    private QuizGenerationProgressInvariantMonitor monitor;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        monitor = new QuizGenerationProgressInvariantMonitor(meterRegistry);
    }

    @Test
    @DisplayName("Legacy non-completed 100 percent values increment one bounded counter")
    void observe_recordsNonCompletedAtOneHundred() {
        QuizGenerationJob job = jobWithRawProgress(GenerationStatus.PROCESSING, 100.0);

        monitor.observe(job);

        assertThat(count("non_completed_at_100")).isEqualTo(1.0);
        assertThat(count("completed_below_100")).isZero();
        assertThat(count("invalid_percentage")).isZero();
    }

    @Test
    @DisplayName("Legacy completed values below 100 increment the completed mismatch counter")
    void observe_recordsCompletedBelowOneHundred() {
        QuizGenerationJob job = jobWithRawProgress(GenerationStatus.COMPLETED, 72.5);

        monitor.observe(job);

        assertThat(count("completed_below_100")).isEqualTo(1.0);
        assertThat(count("non_completed_at_100")).isZero();
    }

    @Test
    @DisplayName("Malformed non-finite progress increments only the invalid counter")
    void observe_recordsInvalidPercentage() {
        QuizGenerationJob job = jobWithRawProgress(GenerationStatus.PROCESSING, Double.NaN);

        monitor.observe(job);

        assertThat(count("invalid_percentage")).isEqualTo(1.0);
        assertThat(count("non_completed_at_100")).isZero();
    }

    @Test
    @DisplayName("Valid active and completed values do not increment invariant counters")
    void observe_ignoresValidProgress() {
        monitor.observe(jobWithRawProgress(GenerationStatus.PROCESSING, 99.0));
        monitor.observe(jobWithRawProgress(GenerationStatus.COMPLETED, 100.0));

        assertThat(count("invalid_percentage")).isZero();
        assertThat(count("non_completed_at_100")).isZero();
        assertThat(count("completed_below_100")).isZero();
    }

    private QuizGenerationJob jobWithRawProgress(GenerationStatus status, double progressPercentage) {
        QuizGenerationJob job = new QuizGenerationJob();
        job.setStatus(status);
        ReflectionTestUtils.setField(job, "progressPercentage", progressPercentage);
        return job;
    }

    private double count(String reason) {
        return meterRegistry.get(QuizGenerationProgressInvariantMonitor.METRIC_NAME)
                .tag("reason", reason)
                .counter()
                .count();
    }
}
