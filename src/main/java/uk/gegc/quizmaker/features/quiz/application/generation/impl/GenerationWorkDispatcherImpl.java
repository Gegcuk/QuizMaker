package uk.gegc.quizmaker.features.quiz.application.generation.impl;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import io.micrometer.core.instrument.MeterRegistry;
import uk.gegc.quizmaker.features.quiz.application.generation.GenerationWorkDispatcher;
import uk.gegc.quizmaker.features.quiz.application.generation.GenerationWorkExecutor;
import uk.gegc.quizmaker.features.quiz.config.QuizJobProperties;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

@Service
public class GenerationWorkDispatcherImpl implements GenerationWorkDispatcher {
    private final GenerationWorkExecutor worker;
    private final Executor executor;
    private final int parallelism;
    private final MeterRegistry metrics;
    private volatile boolean stopping;

    public GenerationWorkDispatcherImpl(GenerationWorkExecutor worker,
            @Qualifier("generationJobExecutor") Executor executor, QuizJobProperties properties, MeterRegistry metrics) {
        this.metrics = metrics;
        this.worker = worker;
        this.executor = executor;
        this.parallelism = properties.getExecution().getMaxActiveJobs();
    }

    @jakarta.annotation.PreDestroy
    public void stop() { stopping = true; }

    @Override
    public Admission dispatch() {
        if (stopping) return Admission.DEFERRED;
        int submitted = 0;
        for (int i = 0; i < parallelism; i++) {
            try {
                executor.execute(() -> {
                    while (!stopping && !Thread.currentThread().isInterrupted() && worker.executeNext()) {
                        // Claim the next durable FIFO entry only when this worker is free.
                    }
                });
                submitted++;
            } catch (RejectedExecutionException full) {
                // No in-memory backlog and no CallerRuns: the periodic scan retries durable work.
                try { metrics.counter("quiz.generation.admission.deferred").increment(); }
                catch (RuntimeException ignored) { /* Telemetry cannot reject accepted work. */ }
                return submitted == 0 ? Admission.DEFERRED : Admission.DISPATCHED;
            }
        }
        return Admission.DISPATCHED;
    }
}
