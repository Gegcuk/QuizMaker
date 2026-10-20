package uk.gegc.quizmaker.features.quiz.application.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.features.quiz.application.generation.GenerationWorkDispatcher;
import uk.gegc.quizmaker.features.quiz.application.generation.GenerationWorkService;

@Component
@RequiredArgsConstructor
@Slf4j
public class GenerationWorkRecoveryScheduler {
    private final GenerationWorkService workService;
    private final GenerationWorkDispatcher dispatcher;

    @Scheduled(fixedDelayString = "${quiz.jobs.execution.scan-seconds:10}000",
            scheduler = "generationLeaseScheduler")
    public void recover() {
        try {
            if (!workService.findDue().isEmpty()) dispatcher.dispatch();
        } catch (RuntimeException ignored) {
            log.warn("Durable generation recovery scan could not complete");
        }
    }
}
