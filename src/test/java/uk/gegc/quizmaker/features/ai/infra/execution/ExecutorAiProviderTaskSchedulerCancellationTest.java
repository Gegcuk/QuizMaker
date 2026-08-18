package uk.gegc.quizmaker.features.ai.infra.execution;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import uk.gegc.quizmaker.features.ai.application.AiProviderCapacityException;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("AI provider queue cancellation")
class ExecutorAiProviderTaskSchedulerCancellationTest {

    private ThreadPoolTaskExecutor executor;

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.shutdown();
        }
    }

    @Test
    @DisplayName("Cancelling queued work removes it immediately and releases capacity")
    void cancellingQueuedTaskRemovesItAndReleasesCapacity() throws Exception {
        executor = executor(1, 1);
        ExecutorAiProviderTaskScheduler scheduler = new ExecutorAiProviderTaskScheduler(executor);
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicBoolean cancelledSupplierRan = new AtomicBoolean();

        CompletableFuture<String> first = scheduler.submit(() -> {
            firstStarted.countDown();
            await(releaseFirst);
            return "first";
        });
        assertThat(firstStarted.await(1, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<String> cancelled = scheduler.submit(() -> {
            cancelledSupplierRan.set(true);
            return "cancelled";
        });
        assertThat(executor.getThreadPoolExecutor().getQueue()).hasSize(1);

        assertThat(cancelled.cancel(false)).isTrue();

        assertThat(executor.getThreadPoolExecutor().getQueue()).isEmpty();
        CompletableFuture<String> replacement = scheduler.submit(() -> "replacement");
        assertThat(executor.getThreadPoolExecutor().getQueue()).hasSize(1);

        releaseFirst.countDown();
        assertThat(first.get(1, TimeUnit.SECONDS)).isEqualTo("first");
        assertThat(replacement.get(1, TimeUnit.SECONDS)).isEqualTo("replacement");
        assertThat(cancelledSupplierRan).isFalse();
    }

    @Test
    @DisplayName("Cancellation remains safe for executors without physical queue removal")
    void genericExecutorSkipsCancelledTaskWhenWrapperEventuallyRuns() {
        Queue<Runnable> queuedCommands = new ArrayDeque<>();
        Executor genericExecutor = queuedCommands::add;
        ExecutorAiProviderTaskScheduler scheduler = new ExecutorAiProviderTaskScheduler(genericExecutor);
        AtomicBoolean supplierRan = new AtomicBoolean();

        CompletableFuture<String> cancelled = scheduler.submit(() -> {
            supplierRan.set(true);
            return "unexpected";
        });

        assertThat(cancelled.cancel(false)).isTrue();
        assertThat(queuedCommands).hasSize(1);
        queuedCommands.remove().run();

        assertThat(cancelled).isCancelled();
        assertThat(supplierRan).isFalse();
    }

    @Test
    @DisplayName("Cancellation after dequeue wins before the atomic running claim")
    void cancellationAfterDequeuePreventsSupplierStart() throws Exception {
        CountDownLatch wrapperDequeued = new CountDownLatch(1);
        CountDownLatch allowWrapperRun = new CountDownLatch(1);
        CountDownLatch wrapperFinished = new CountDownLatch(1);
        AtomicBoolean supplierRan = new AtomicBoolean();
        Executor pausedAfterDequeue = command -> {
            Thread worker = new Thread(() -> {
                wrapperDequeued.countDown();
                await(allowWrapperRun);
                try {
                    command.run();
                } finally {
                    wrapperFinished.countDown();
                }
            }, "ai-provider-dequeue-race-test");
            worker.start();
        };
        ExecutorAiProviderTaskScheduler scheduler = new ExecutorAiProviderTaskScheduler(pausedAfterDequeue);

        CompletableFuture<String> cancelled = scheduler.submit(() -> {
            supplierRan.set(true);
            return "unexpected";
        });
        assertThat(wrapperDequeued.await(1, TimeUnit.SECONDS)).isTrue();

        assertThat(cancelled.cancel(false)).isTrue();
        allowWrapperRun.countDown();

        assertThat(wrapperFinished.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(cancelled).isCancelled();
        assertThat(supplierRan).isFalse();
    }

    @Test
    @DisplayName("Cancelling a running future does not interrupt its provider thread")
    void cancellingRunningTaskDoesNotInterruptSupplier() throws Exception {
        executor = executor(1, 1);
        ExecutorAiProviderTaskScheduler scheduler = new ExecutorAiProviderTaskScheduler(executor);
        CountDownLatch taskStarted = new CountDownLatch(1);
        CountDownLatch releaseTask = new CountDownLatch(1);
        CountDownLatch taskFinished = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();

        CompletableFuture<String> running = scheduler.submit(() -> {
            taskStarted.countDown();
            try {
                await(releaseTask);
                interrupted.set(Thread.currentThread().isInterrupted());
                return "finished";
            } finally {
                taskFinished.countDown();
            }
        });
        assertThat(taskStarted.await(1, TimeUnit.SECONDS)).isTrue();

        assertThat(running.cancel(false)).isTrue();
        releaseTask.countDown();

        assertThat(taskFinished.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(running).isCancelled();
        assertThat(interrupted).isFalse();
    }

    @Test
    @DisplayName("Executor rejection keeps the typed provider capacity failure")
    void rejectionRemainsTypedCapacityFailure() {
        Executor rejectingExecutor = command -> {
            throw new RejectedExecutionException("queue full");
        };
        ExecutorAiProviderTaskScheduler scheduler = new ExecutorAiProviderTaskScheduler(rejectingExecutor);

        CompletableFuture<String> rejected = scheduler.submit(() -> "never");

        assertThatThrownBy(rejected::join)
                .hasCauseInstanceOf(AiProviderCapacityException.class)
                .hasRootCauseInstanceOf(RejectedExecutionException.class);
    }

    private ThreadPoolTaskExecutor executor(int maxPoolSize, int queueCapacity) {
        ThreadPoolTaskExecutor taskExecutor = new ThreadPoolTaskExecutor();
        taskExecutor.setCorePoolSize(maxPoolSize);
        taskExecutor.setMaxPoolSize(maxPoolSize);
        taskExecutor.setQueueCapacity(queueCapacity);
        taskExecutor.setThreadNamePrefix("ai-provider-cancel-test-");
        taskExecutor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
        taskExecutor.setWaitForTasksToCompleteOnShutdown(true);
        taskExecutor.setAwaitTerminationSeconds(2);
        taskExecutor.initialize();
        return taskExecutor;
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for test release");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Test task interrupted", interrupted);
        }
    }
}
