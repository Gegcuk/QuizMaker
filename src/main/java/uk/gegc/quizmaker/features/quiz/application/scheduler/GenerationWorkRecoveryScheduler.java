package uk.gegc.quizmaker.features.quiz.application.scheduler;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.features.quiz.application.generation.GenerationWorkExecutor;
import uk.gegc.quizmaker.features.quiz.application.generation.GenerationWorkService;

import java.util.UUID;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

@Component
@Slf4j
public class GenerationWorkRecoveryScheduler {
    private final GenerationWorkService workService;
    private final GenerationWorkExecutor worker;
    private final Executor executor;
    private final Set<UUID> submitted = ConcurrentHashMap.newKeySet();

    public GenerationWorkRecoveryScheduler(GenerationWorkService workService, GenerationWorkExecutor worker,
                                         @Qualifier("aiTaskExecutor") Executor executor) {
        this.workService = workService;
        this.worker = worker;
        this.executor = executor;
    }

    @Scheduled(fixedDelayString = "${quiz.jobs.execution.scan-seconds:10}000",
            scheduler = "generationLeaseScheduler")
    public void recover() {
        try {
            for (UUID jobId : workService.findDue()) {
                if (!submitted.add(jobId)) continue;
                try {
                    executor.execute(() -> {
                        try {
                            worker.execute(jobId);
                        } finally {
                            submitted.remove(jobId);
                        }
                    });
                } catch (RuntimeException failure) {
                    submitted.remove(jobId);
                    log.warn("Durable generation dispatch will be retried for job {}", jobId);
                }
            }
        } catch (RuntimeException ignored) {
            log.warn("Durable generation recovery scan could not complete");
        }
    }
}
