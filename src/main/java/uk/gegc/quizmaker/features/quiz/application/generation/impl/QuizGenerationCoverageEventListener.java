package uk.gegc.quizmaker.features.quiz.application.generation.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.features.quiz.application.generation.QuizGenerationCoverageService;
import uk.gegc.quizmaker.features.quiz.domain.events.QuizGenerationCoverageReconciledEvent;

@Component
@RequiredArgsConstructor
public class QuizGenerationCoverageEventListener {

    private final QuizGenerationCoverageService coverageService;

    @EventListener
    public void persistCoverage(QuizGenerationCoverageReconciledEvent event) {
        coverageService.saveDecision(event.getJobId(), event.getCoverage());
    }
}
