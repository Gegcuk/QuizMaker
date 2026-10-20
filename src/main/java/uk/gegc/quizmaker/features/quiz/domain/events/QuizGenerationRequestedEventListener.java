package uk.gegc.quizmaker.features.quiz.domain.events;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import uk.gegc.quizmaker.features.ai.application.AiQuizGenerationService;
import uk.gegc.quizmaker.features.quiz.application.generation.GenerationWorkService;
import uk.gegc.quizmaker.features.quiz.application.generation.GenerationWorkExecutor;

/**
 * Listens for {@link QuizGenerationRequestedEvent} instances and starts the asynchronous
 * generation workflow only after the surrounding transaction has been committed.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class QuizGenerationRequestedEventListener {

    private final AiQuizGenerationService aiQuizGenerationService;
    private final GenerationWorkService workService;
    private final GenerationWorkExecutor worker;

    @Async("aiTaskExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleQuizGenerationRequest(QuizGenerationRequestedEvent event) {
        log.debug("Received QuizGenerationRequestedEvent for job {}", event.getJobId());
        if (workService.isManaged(event.getJobId())) {
            worker.execute(event.getJobId());
        } else {
            aiQuizGenerationService.generateQuizFromDocumentAsync(event.getJobId(), event.getRequest());
        }
    }
}
