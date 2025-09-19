package uk.gegc.quizmaker.features.quiz.domain.events;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import uk.gegc.quizmaker.features.ai.application.AiQuizGenerationService;

/**
 * Listens for {@link QuizGenerationRequestedEvent} instances and starts the asynchronous
 * generation workflow only after the surrounding transaction has been committed.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class QuizGenerationRequestedEventListener {

    private final AiQuizGenerationService aiQuizGenerationService;

    @Async("aiTaskExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleQuizGenerationRequest(QuizGenerationRequestedEvent event) {
        log.debug("Received QuizGenerationRequestedEvent for job {}", event.getJobId());
        aiQuizGenerationService.generateQuizFromDocumentAsync(event.getJobId(), event.getRequest());
    }
}
