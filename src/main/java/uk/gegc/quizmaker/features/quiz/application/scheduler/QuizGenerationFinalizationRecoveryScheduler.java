package uk.gegc.quizmaker.features.quiz.application.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.features.quiz.application.generation.QuizGenerationFacade;

/** Releases reservations left behind by an interrupted generation finalization. */
@Component
@RequiredArgsConstructor
@Slf4j
public class QuizGenerationFinalizationRecoveryScheduler {

    private final QuizGenerationFacade quizGenerationFacade;

    @Scheduled(fixedDelayString = "${quiz.jobs.finalization.recovery-fixed-delay-seconds:60}000")
    public void recoverStalledFinalizations() {
        try {
            int recovered = quizGenerationFacade.recoverStalledQuizGenerationFinalizations();
            if (recovered > 0) {
                log.warn("Recovered {} stalled quiz-generation finalization(s)", recovered);
            }
        } catch (Exception exception) {
            log.error("Unable to recover stalled quiz-generation finalizations", exception);
        }
    }
}
