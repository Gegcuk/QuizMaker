package uk.gegc.quizmaker.features.quiz.application.scheduler;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.features.quiz.application.generation.QuizGenerationFacade;

/** Reconciles durable output and reservations left by interrupted generation finalization. */
@Component
@Slf4j
public class QuizGenerationFinalizationRecoveryScheduler {

    private final QuizGenerationFacade quizGenerationFacade;
    private final Counter attemptedCounter;
    private final Counter succeededCounter;
    private final Counter failedCounter;

    public QuizGenerationFinalizationRecoveryScheduler(
            QuizGenerationFacade quizGenerationFacade,
            MeterRegistry meterRegistry
    ) {
        this.quizGenerationFacade = quizGenerationFacade;
        this.attemptedCounter = counter(meterRegistry, "attempted");
        this.succeededCounter = counter(meterRegistry, "succeeded");
        this.failedCounter = counter(meterRegistry, "failed");
    }

    @Scheduled(fixedDelayString = "${quiz.jobs.finalization.recovery-fixed-delay-seconds:60}000")
    public void recoverStalledFinalizations() {
        attemptedCounter.increment();
        try {
            int recovered = quizGenerationFacade.recoverStalledQuizGenerationFinalizations();
            succeededCounter.increment();
            if (recovered > 0) {
                log.warn("Reconciled {} stalled quiz-generation finalization candidate(s)", recovered);
            }
        } catch (Exception exception) {
            failedCounter.increment();
            log.error("Unable to recover stalled quiz-generation finalizations", exception);
        }
    }

    private Counter counter(MeterRegistry meterRegistry, String outcome) {
        return Counter.builder("quiz.generation.finalization.recovery.runs")
                .description("Quiz-generation finalization recovery scheduler outcomes")
                .tag("outcome", outcome)
                .register(meterRegistry);
    }
}
