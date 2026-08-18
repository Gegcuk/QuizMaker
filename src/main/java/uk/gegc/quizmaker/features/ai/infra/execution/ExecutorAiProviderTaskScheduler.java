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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
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
        ProviderTask<T> scheduledTask = new ProviderTask<>(task, this::removeIfQueued);
        try {
            providerTaskExecutor.execute(scheduledTask);
        } catch (RejectedExecutionException rejected) {
            log.warn("AI provider task rejected: bounded executor capacity exhausted");
            scheduledTask.reject(new AiProviderCapacityException(rejected));
        }
        return scheduledTask;
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

    private static final class ProviderTask<T> extends CompletableFuture<T> implements Runnable {

        private final Supplier<T> supplier;
        private final Consumer<Runnable> queuedTaskRemoval;
        private final AtomicReference<State> state = new AtomicReference<>(State.QUEUED);

        private ProviderTask(Supplier<T> supplier, Consumer<Runnable> queuedTaskRemoval) {
            this.supplier = supplier;
            this.queuedTaskRemoval = queuedTaskRemoval;
        }

        @Override
        public void run() {
            // This claim is the only queued-to-running boundary. If cancellation
            // claimed QUEUED first, provider invocation can never begin.
            if (!state.compareAndSet(State.QUEUED, State.RUNNING)) {
                return;
            }

            try {
                super.complete(supplier.get());
            } catch (Throwable failure) {
                super.completeExceptionally(failure);
            } finally {
                state.compareAndSet(State.RUNNING, State.FINISHED);
            }
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            while (true) {
                State current = state.get();
                if (current == State.QUEUED) {
                    if (!state.compareAndSet(State.QUEUED, State.CANCELLED)) {
                        continue;
                    }
                    queuedTaskRemoval.accept(this);
                    return super.cancel(false);
                }
                if (current == State.RUNNING) {
                    // Preserve the existing cooperative contract: callers stop
                    // waiting, but an in-flight provider request is not interrupted.
                    return super.cancel(false);
                }
                return isCancelled();
            }
        }

        private void reject(AiProviderCapacityException failure) {
            if (state.compareAndSet(State.QUEUED, State.FINISHED)) {
                super.completeExceptionally(failure);
            }
        }

        private enum State {
            QUEUED,
            RUNNING,
            CANCELLED,
            FINISHED
        }
    }
}
