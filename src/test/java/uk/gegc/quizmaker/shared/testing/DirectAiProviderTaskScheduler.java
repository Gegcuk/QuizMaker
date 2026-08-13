package uk.gegc.quizmaker.shared.testing;

import uk.gegc.quizmaker.features.ai.application.AiProviderTaskScheduler;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Deterministic scheduler for tests that are not exercising executor behavior.
 */
public enum DirectAiProviderTaskScheduler implements AiProviderTaskScheduler {
    INSTANCE;

    @Override
    public <T> CompletableFuture<T> submit(Supplier<T> task) {
        try {
            return CompletableFuture.completedFuture(task.get());
        } catch (Throwable failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }
}
