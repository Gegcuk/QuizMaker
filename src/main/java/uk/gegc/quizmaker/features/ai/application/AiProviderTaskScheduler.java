package uk.gegc.quizmaker.features.ai.application;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Application boundary for provider-bound AI work.
 */
public interface AiProviderTaskScheduler {

    <T> CompletableFuture<T> submit(Supplier<T> task);
}
