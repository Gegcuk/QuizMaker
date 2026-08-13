package uk.gegc.quizmaker.features.ai.infra.execution;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import uk.gegc.quizmaker.features.ai.application.AiProviderCapacityException;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Bounded AI provider task scheduler")
class ExecutorAiProviderTaskSchedulerTest {

    private ThreadPoolTaskExecutor executor;

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.shutdown();
        }
    }

    @Test
    @DisplayName("Runs only on bounded provider threads and rejects beyond worker plus queue capacity")
    void executesWithinConfiguredCapacityAndRejectsOverflow() throws Exception {
        executor = executor(1, 1);
        ExecutorAiProviderTaskScheduler scheduler = new ExecutorAiProviderTaskScheduler(executor);
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicReference<String> executionThread = new AtomicReference<>();
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximumActive = new AtomicInteger();
        AtomicBoolean rejectedTaskRan = new AtomicBoolean();

        CompletableFuture<String> first = scheduler.submit(() -> {
            executionThread.set(Thread.currentThread().getName());
            int current = active.incrementAndGet();
            maximumActive.accumulateAndGet(current, Math::max);
            firstStarted.countDown();
            await(releaseFirst);
            active.decrementAndGet();
            return "first";
        });

        assertThat(firstStarted.await(1, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<String> queued = scheduler.submit(() -> {
            int current = active.incrementAndGet();
            maximumActive.accumulateAndGet(current, Math::max);
            active.decrementAndGet();
            return "queued";
        });
        CompletableFuture<String> rejected = scheduler.submit(() -> {
            rejectedTaskRan.set(true);
            return "rejected";
        });

        assertThatThrownBy(rejected::join)
                .hasCauseInstanceOf(AiProviderCapacityException.class)
                .hasRootCauseInstanceOf(java.util.concurrent.RejectedExecutionException.class);
        assertThat(rejectedTaskRan).isFalse();

        releaseFirst.countDown();
        assertThat(first.get(1, TimeUnit.SECONDS)).isEqualTo("first");
        assertThat(queued.get(1, TimeUnit.SECONDS)).isEqualTo("queued");
        assertThat(executionThread.get()).startsWith("ai-provider-test-");
        assertThat(executionThread.get()).doesNotContain("ForkJoinPool");
        assertThat(executionThread.get()).isNotEqualTo(Thread.currentThread().getName());
        assertThat(maximumActive).hasValue(1);
    }

    @Test
    @DisplayName("Propagates task failure and reuses worker capacity")
    void propagatesFailureAndReusesCapacity() {
        executor = executor(1, 1);
        ExecutorAiProviderTaskScheduler scheduler = new ExecutorAiProviderTaskScheduler(executor);
        IllegalStateException providerFailure = new IllegalStateException("provider failed");

        CompletableFuture<String> failed = scheduler.submit(() -> {
            throw providerFailure;
        });

        assertThatThrownBy(failed::join).hasCause(providerFailure);
        assertThat(scheduler.submit(() -> "recovered").join()).isEqualTo("recovered");
    }

    @Test
    @DisplayName("Does not execute a queued task cancelled before it starts")
    void skipsCancelledQueuedTask() throws Exception {
        executor = executor(1, 1);
        ExecutorAiProviderTaskScheduler scheduler = new ExecutorAiProviderTaskScheduler(executor);
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicBoolean cancelledTaskRan = new AtomicBoolean();

        CompletableFuture<Void> first = scheduler.submit(() -> {
            firstStarted.countDown();
            await(releaseFirst);
            return null;
        });
        assertThat(firstStarted.await(1, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<Void> cancelled = scheduler.submit(() -> {
            cancelledTaskRan.set(true);
            return null;
        });
        assertThat(cancelled.cancel(false)).isTrue();

        releaseFirst.countDown();
        first.get(1, TimeUnit.SECONDS);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (executor.getActiveCount() > 0 && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(cancelledTaskRan).isFalse();
    }

    @Test
    @DisplayName("Rejects a null provider task before executor submission")
    void rejectsNullTask() {
        executor = executor(1, 1);
        ExecutorAiProviderTaskScheduler scheduler = new ExecutorAiProviderTaskScheduler(executor);

        assertThatThrownBy(() -> scheduler.submit(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("AI provider task is required");
    }

    @Test
    @DisplayName("Drains accepted work during shutdown and rejects new submissions")
    void drainsAcceptedWorkAndRejectsNewSubmissionsDuringShutdown() throws Exception {
        executor = executor(1, 1);
        ExecutorAiProviderTaskScheduler scheduler = new ExecutorAiProviderTaskScheduler(executor);
        CountDownLatch taskStarted = new CountDownLatch(1);
        CountDownLatch releaseTask = new CountDownLatch(1);
        AtomicBoolean rejectedTaskRan = new AtomicBoolean();
        CompletableFuture<String> accepted = scheduler.submit(() -> {
            taskStarted.countDown();
            await(releaseTask);
            return "completed";
        });
        assertThat(taskStarted.await(1, TimeUnit.SECONDS)).isTrue();

        Thread shutdown = new Thread(executor::shutdown, "provider-executor-shutdown-test");
        shutdown.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (!executor.getThreadPoolExecutor().isShutdown() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }

        CompletableFuture<String> rejected = scheduler.submit(() -> {
            rejectedTaskRan.set(true);
            return "rejected";
        });
        assertThatThrownBy(rejected::join).hasCauseInstanceOf(AiProviderCapacityException.class);
        assertThat(rejectedTaskRan).isFalse();

        releaseTask.countDown();
        assertThat(accepted.get(1, TimeUnit.SECONDS)).isEqualTo("completed");
        shutdown.join(TimeUnit.SECONDS.toMillis(1));
        assertThat(shutdown.isAlive()).isFalse();
    }

    private ThreadPoolTaskExecutor executor(int maxPoolSize, int queueCapacity) {
        ThreadPoolTaskExecutor taskExecutor = new ThreadPoolTaskExecutor();
        taskExecutor.setCorePoolSize(maxPoolSize);
        taskExecutor.setMaxPoolSize(maxPoolSize);
        taskExecutor.setQueueCapacity(queueCapacity);
        taskExecutor.setThreadNamePrefix("ai-provider-test-");
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
