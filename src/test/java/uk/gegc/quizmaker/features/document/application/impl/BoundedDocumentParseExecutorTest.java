package uk.gegc.quizmaker.features.document.application.impl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gegc.quizmaker.features.document.application.DocumentProcessingLimits;
import uk.gegc.quizmaker.shared.exception.DocumentProcessingCapacityExceededException;
import uk.gegc.quizmaker.shared.exception.DocumentResourceLimitException;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Bounded document parser executor")
class BoundedDocumentParseExecutorTest {

    private BoundedDocumentParseExecutor executor;

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.shutdown();
        }
    }

    @Test
    @DisplayName("Rejects a second parse when global parser capacity is occupied")
    void rejectsWhenGlobalCapacityIsOccupied() throws Exception {
        executor = executor(1, 1, Duration.ofSeconds(5));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<String> first = caller.submit(() -> executor.execute("user-a", () -> {
                started.countDown();
                release.await();
                return "first";
            }));
            assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> executor.execute("user-b", () -> "second"))
                    .isInstanceOf(DocumentProcessingCapacityExceededException.class);

            release.countDown();
            assertThat(first.get(1, TimeUnit.SECONDS)).isEqualTo("first");
        } finally {
            caller.shutdownNow();
        }
    }

    @Test
    @DisplayName("Enforces a per-user parser limit while allowing another user within global capacity")
    void enforcesPerUserCapacity() throws Exception {
        executor = executor(2, 1, Duration.ofSeconds(5));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<String> first = caller.submit(() -> executor.execute("user-a", () -> {
                started.countDown();
                release.await();
                return "first";
            }));
            assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> executor.execute("user-a", () -> "second"))
                    .isInstanceOf(DocumentProcessingCapacityExceededException.class);
            assertThat(executor.execute("user-b", () -> "other-user")).isEqualTo("other-user");

            release.countDown();
            assertThat(first.get(1, TimeUnit.SECONDS)).isEqualTo("first");
        } finally {
            caller.shutdownNow();
        }
    }

    @Test
    @DisplayName("Times out a parser and releases its capacity after interruption")
    void timesOutAndReleasesCapacity() throws Exception {
        executor = executor(1, 1, Duration.ofMillis(50));
        CountDownLatch interrupted = new CountDownLatch(1);

        assertThatThrownBy(() -> executor.execute("user-a", () -> {
            try {
                new CountDownLatch(1).await();
                return "never";
            } catch (InterruptedException exception) {
                interrupted.countDown();
                throw exception;
            }
        })).isInstanceOf(DocumentResourceLimitException.class);

        assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(executeAfterCapacityIsReleased("user-a", Duration.ofSeconds(1)))
                .isEqualTo("available-again");
    }

    @Test
    @DisplayName("Rejects a missing owner before acquiring shared parser capacity")
    void rejectsMissingOwnerWithoutConsumingCapacity() {
        executor = executor(1, 1, Duration.ofSeconds(5));

        assertThatThrownBy(() -> executor.execute(" ", () -> "never"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(executor.execute("user-a", () -> "available")).isEqualTo("available");
    }

    private BoundedDocumentParseExecutor executor(int globalCapacity, int perUserCapacity, Duration timeout) {
        DocumentProcessingLimits limits = DocumentProcessingLimits.defaults();
        limits.setMaxConcurrentParses(globalCapacity);
        limits.setMaxConcurrentParsesPerUser(perUserCapacity);
        limits.setParseTimeout(timeout);
        BoundedDocumentParseExecutor boundedExecutor = new BoundedDocumentParseExecutor(limits);
        boundedExecutor.initialize();
        return boundedExecutor;
    }

    private String executeAfterCapacityIsReleased(String ownerKey, Duration waitTimeout) throws InterruptedException {
        long deadline = System.nanoTime() + waitTimeout.toNanos();
        DocumentProcessingCapacityExceededException lastFailure = null;

        do {
            try {
                return executor.execute(ownerKey, () -> "available-again");
            } catch (DocumentProcessingCapacityExceededException exception) {
                lastFailure = exception;
                TimeUnit.MILLISECONDS.sleep(10);
            }
        } while (System.nanoTime() < deadline);

        throw new AssertionError("Parser capacity was not released after the timed-out operation exited", lastFailure);
    }
}
