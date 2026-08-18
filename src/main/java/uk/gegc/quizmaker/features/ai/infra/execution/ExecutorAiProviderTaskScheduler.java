package uk.gegc.quizmaker.features.ai.infra.execution;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.features.ai.application.AiProviderCapacityException;
import uk.gegc.quizmaker.features.ai.application.AiProviderTaskScheduler;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.Supplier;

@Component
@Slf4j
public class ExecutorAiProviderTaskScheduler implements AiProviderTaskScheduler {

    private final Executor providerTaskExecutor;

    public ExecutorAiProviderTaskScheduler(
            @Qualifier("aiProviderTaskExecutor") Executor providerTaskExecutor) {
        this.providerTaskExecutor = providerTaskExecutor;
    }

    @Override
    public <T> CompletableFuture<T> submit(Supplier<T> task) {
        Objects.requireNonNull(task, "AI provider task is required");
        CompletableFuture<T> result = new CompletableFuture<>();
        Runnable scheduledTask = () -> execute(task, result);
        result.whenComplete((ignored, failure) -> {
            if (result.isCancelled()) {
                removeIfQueued(scheduledTask);
            }
        });
        try {
            providerTaskExecutor.execute(scheduledTask);
        } catch (RejectedExecutionException rejected) {
            log.warn("AI provider task rejected: bounded executor capacity exhausted");
            result.completeExceptionally(new AiProviderCapacityException(rejected));
        }
        return result;
    }

    private <T> void execute(Supplier<T> task, CompletableFuture<T> result) {
        if (result.isCancelled()) {
            return;
        }
        try {
            result.complete(task.get());
        } catch (Throwable failure) {
            result.completeExceptionally(failure);
        }
    }

    private void removeIfQueued(Runnable scheduledTask) {
        try {
            if (providerTaskExecutor instanceof ThreadPoolTaskExecutor taskExecutor) {
                taskExecutor.getThreadPoolExecutor().remove(scheduledTask);
            } else if (providerTaskExecutor instanceof ThreadPoolExecutor threadPoolExecutor) {
                threadPoolExecutor.remove(scheduledTask);
            }
        } catch (RuntimeException removalFailure) {
            log.debug("Could not remove cancelled AI provider task from executor queue", removalFailure);
        }
    }
}
