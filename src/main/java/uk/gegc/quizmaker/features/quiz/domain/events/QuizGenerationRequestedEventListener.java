package uk.gegc.quizmaker.features.quiz.domain.events;

import org.springframework.beans.factory.annotation.Qualifier;
import java.util.concurrent.Executor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import uk.gegc.quizmaker.features.ai.application.AiQuizGenerationService;
import uk.gegc.quizmaker.features.quiz.application.generation.GenerationWorkService;
import uk.gegc.quizmaker.features.quiz.application.generation.GenerationWorkDispatcher;

/**
 * Listens for {@link QuizGenerationRequestedEvent} instances and starts the asynchronous
 * generation workflow only after the surrounding transaction has been committed.
 */
@Component
@Slf4j
public class QuizGenerationRequestedEventListener {

    private final AiQuizGenerationService aiQuizGenerationService;
    private final GenerationWorkService workService;
    private final GenerationWorkDispatcher dispatcher;
    private final Executor legacyExecutor;

    public QuizGenerationRequestedEventListener(AiQuizGenerationService aiQuizGenerationService,
            GenerationWorkService workService, GenerationWorkDispatcher dispatcher,
            @Qualifier("generationJobExecutor") Executor legacyExecutor) {
        this.aiQuizGenerationService = aiQuizGenerationService;
        this.workService = workService;
        this.dispatcher = dispatcher;
        this.legacyExecutor = legacyExecutor;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleQuizGenerationRequest(QuizGenerationRequestedEvent event) {
        log.debug("Received QuizGenerationRequestedEvent for job {}", event.getJobId());
        if (workService.isManaged(event.getJobId())) {
            dispatcher.dispatch();
        } else {
            try {
                legacyExecutor.execute(() -> aiQuizGenerationService.generateQuizFromDocumentAsync(event.getJobId(), event.getRequest()));
            } catch (java.util.concurrent.RejectedExecutionException full) {
                // No durable record exists for this compatibility path; legacy stale-job cleanup applies.
                log.warn("Legacy generation dispatch rejected for job {}", event.getJobId());
            }
        }
    }
}
