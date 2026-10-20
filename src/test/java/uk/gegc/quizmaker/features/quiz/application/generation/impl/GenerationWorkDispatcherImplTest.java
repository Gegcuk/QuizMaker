package uk.gegc.quizmaker.features.quiz.application.generation.impl;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import uk.gegc.quizmaker.features.quiz.application.generation.GenerationWorkExecutor;
import uk.gegc.quizmaker.features.quiz.config.QuizJobProperties;
import uk.gegc.quizmaker.shared.config.AsyncConfig;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class GenerationWorkDispatcherImplTest {
    @ParameterizedTest
    @ValueSource(ints = {1, 5, 25})
    void drainsSyntheticJobsWithoutExceedingFourWorkersOrRunningOnTheCaller(int jobs) throws Exception {
        var properties = new QuizJobProperties();
        var executor = (ThreadPoolTaskExecutor) new AsyncConfig().generationJobExecutor(properties);
        var worker = mock(GenerationWorkExecutor.class);
        var next = new AtomicInteger();
        var running = new AtomicInteger();
        var peak = new AtomicInteger();
        var completed = new CountDownLatch(jobs);
        var occupied = new CountDownLatch(Math.min(4, jobs));
        var release = new CountDownLatch(1);
        var errors = new ConcurrentLinkedQueue<Throwable>();
        Thread caller = Thread.currentThread();
        when(worker.executeNext()).thenAnswer(invocation -> {
            if (next.getAndIncrement() >= jobs) return false;
            try {
                assertThat(Thread.currentThread()).isNotSameAs(caller);
                peak.accumulateAndGet(running.incrementAndGet(), Math::max);
                occupied.countDown();
                assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            } catch (Throwable failure) {
                errors.add(failure);
            } finally {
                running.decrementAndGet();
                completed.countDown();
            }
            return true;
        });
        var dispatcher = new GenerationWorkDispatcherImpl(worker, executor, properties, new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        try {
            dispatcher.dispatch();
            assertThat(occupied.await(5, TimeUnit.SECONDS)).isTrue();
            var admission = dispatcher.dispatch(); // Saturation never runs work inline.
            if (jobs >= 4) assertThat(admission).isEqualTo(
                    uk.gegc.quizmaker.features.quiz.application.generation.GenerationWorkDispatcher.Admission.DEFERRED);
            assertThat(executor.getThreadPoolExecutor().getQueue()).isEmpty();
            release.countDown();
            assertThat(completed.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(peak).hasValue(Math.min(jobs, 4));
            assertThat(errors).isEmpty();
        } finally {
            release.countDown();
            executor.shutdown();
        }
    }
}
