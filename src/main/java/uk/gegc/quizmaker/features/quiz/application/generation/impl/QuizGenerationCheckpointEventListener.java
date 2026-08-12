package uk.gegc.quizmaker.features.quiz.application.generation.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.features.quiz.application.generation.QuizGenerationCheckpointService;
import uk.gegc.quizmaker.features.quiz.domain.events.QuizGenerationCheckpointedEvent;
import uk.gegc.quizmaker.features.quiz.domain.events.QuizGenerationCompletedEvent;

@Component
@RequiredArgsConstructor
@Slf4j
public class QuizGenerationCheckpointEventListener {

    private final QuizGenerationCheckpointService checkpointService;
    private final ApplicationEventPublisher eventPublisher;

    @EventListener
    public void checkpointGeneratedOutput(QuizGenerationCompletedEvent event) {
        checkpointService.save(event.getJobId(), event.getChunkQuestions());
        try {
            eventPublisher.publishEvent(new QuizGenerationCheckpointedEvent(this, event.getJobId()));
        } catch (RuntimeException dispatchFailure) {
            // The checkpoint is authoritative. Recovery will redispatch it after the grace period.
            log.warn("Generated output for job {} was checkpointed but immediate finalization dispatch failed",
                    event.getJobId(), dispatchFailure);
        }
    }
}
