package uk.gegc.quizmaker.features.document.application.impl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gegc.quizmaker.features.document.application.ConvertedDocument;
import uk.gegc.quizmaker.features.document.application.DocumentParseRequest;
import uk.gegc.quizmaker.features.document.application.DocumentParserWorker;
import uk.gegc.quizmaker.features.document.application.DocumentParserWorkerFactory;
import uk.gegc.quizmaker.features.document.application.DocumentParserWorkerMetrics;
import uk.gegc.quizmaker.features.document.application.DocumentProcessingLimits;
import uk.gegc.quizmaker.shared.exception.DocumentProcessingCapacityExceededException;
import uk.gegc.quizmaker.shared.exception.DocumentProcessingException;
import uk.gegc.quizmaker.shared.exception.DocumentProcessingTimeoutException;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@DisplayName("Bounded isolated document parser executor")
class BoundedDocumentParseExecutorTest {

    private BoundedDocumentParseExecutor executor;
    private DocumentParserWorkerMetrics metrics;

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.shutdown();
        }
    }

    @Test
    @DisplayName("Rejects a second parse while one live worker owns global capacity")
    void rejectsWhenGlobalCapacityIsOccupied() throws Exception {
        ControllableWorker firstWorker = ControllableWorker.blocking(successfulDocument());
        QueueWorkerFactory factory = new QueueWorkerFactory(firstWorker);
        executor = executor(1, 1, Duration.ofSeconds(5), factory);
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<ConvertedDocument> first = caller.submit(() -> executor.execute("user-a", request()));
            assertThat(factory.started.await(1, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> executor.execute("user-b", request()))
                    .isInstanceOf(DocumentProcessingCapacityExceededException.class);

            firstWorker.exit();
            assertThat(first.get(1, TimeUnit.SECONDS)).isSameAs(firstWorker.result);
        } finally {
            caller.shutdownNow();
        }
    }

    @Test
    @DisplayName("Enforces per-user capacity while another owner can use free global capacity")
    void enforcesPerUserCapacity() throws Exception {
        ControllableWorker firstWorker = ControllableWorker.blocking(successfulDocument());
        ControllableWorker otherOwnerWorker = ControllableWorker.completed(successfulDocument());
        QueueWorkerFactory factory = new QueueWorkerFactory(firstWorker, otherOwnerWorker);
        executor = executor(2, 1, Duration.ofSeconds(5), factory);
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<ConvertedDocument> first = caller.submit(() -> executor.execute("user-a", request()));
            assertThat(factory.started.await(1, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> executor.execute("user-a", request()))
                    .isInstanceOf(DocumentProcessingCapacityExceededException.class);
            assertThat(executor.execute("user-b", request())).isSameAs(otherOwnerWorker.result);

            firstWorker.exit();
            assertThat(first.get(1, TimeUnit.SECONDS)).isSameAs(firstWorker.result);
        } finally {
            caller.shutdownNow();
        }
    }

    @Test
    @DisplayName("Force-terminates an interruption-ignoring worker before reusing its capacity")
    void forceTerminatesTimedOutWorkerBeforeReusingCapacity() {
        ControllableWorker timedOut = ControllableWorker.blocking(successfulDocument());
        timedOut.terminateOnForce = true;
        ControllableWorker replacement = ControllableWorker.completed(successfulDocument());
        executor = executor(
                1, 1, Duration.ofMillis(20), new QueueWorkerFactory(timedOut, replacement));

        assertThatThrownBy(() -> executor.execute("user-a", request()))
                .isInstanceOf(DocumentProcessingTimeoutException.class)
                .hasMessage("Document processing exceeded the configured time limit");

        assertThat(timedOut.terminationRequested).isTrue();
        assertThat(timedOut.forceTerminationRequested).isTrue();
        assertThat(timedOut.isAlive()).isFalse();
        verify(metrics).record(DocumentParserWorkerMetrics.Outcome.TIMED_OUT);
        verify(metrics).record(DocumentParserWorkerMetrics.Outcome.FORCED_KILL);
        verify(metrics).workerStarted();
        verify(metrics).workerStopped();
        assertThat(executor.execute("user-a", request())).isSameAs(replacement.result);
    }

    @Test
    @DisplayName("Keeps capacity unavailable until the reaper confirms a late worker exit")
    void retainsCapacityUntilReaperConfirmsLateWorkerExit() throws InterruptedException {
        ControllableWorker unkillable = ControllableWorker.blocking(successfulDocument());
        ControllableWorker replacement = ControllableWorker.completed(successfulDocument());
        executor = executor(1, 1, Duration.ofMillis(10), new QueueWorkerFactory(unkillable, replacement));

        assertThatThrownBy(() -> executor.execute("user-a", request()))
                .isInstanceOf(DocumentProcessingException.class)
                .hasMessage("Document parser could not be terminated safely");

        assertThat(unkillable.isAlive()).isTrue();
        verify(metrics).record(DocumentParserWorkerMetrics.Outcome.KILL_FAILED);
        assertThatThrownBy(() -> executor.execute("user-b", request()))
                .isInstanceOf(DocumentProcessingCapacityExceededException.class);

        unkillable.exit();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            try {
                assertThat(executor.execute("user-b", request())).isSameAs(replacement.result);
                return;
            } catch (DocumentProcessingCapacityExceededException notReapedYet) {
                Thread.sleep(25);
            }
        }
        fail("Parser capacity was not reclaimed after the worker exit was confirmed");
    }

    @Test
    @DisplayName("Releases capacity after a crashed worker returns a typed safe failure")
    void releasesCapacityAfterWorkerCrash() {
        ControllableWorker crashed = ControllableWorker.completed(null);
        crashed.readFailure = new DocumentProcessingException("Document parser process failed");
        ControllableWorker replacement = ControllableWorker.completed(successfulDocument());
        executor = executor(1, 1, Duration.ofSeconds(1), new QueueWorkerFactory(crashed, replacement));

        assertThatThrownBy(() -> executor.execute("user-a", request()))
                .isInstanceOf(DocumentProcessingException.class)
                .hasMessage("Document parser process failed");

        assertThat(executor.execute("user-a", request())).isSameAs(replacement.result);
        assertThat(crashed.closed).isTrue();
    }

    @Test
    @DisplayName("Terminates active workers and rejects new work during service shutdown")
    void shutdownTerminatesWorkersAndStopsAdmission() throws Exception {
        ControllableWorker active = ControllableWorker.blocking(successfulDocument());
        active.terminateOnForce = true;
        QueueWorkerFactory factory = new QueueWorkerFactory(active);
        executor = executor(1, 1, Duration.ofSeconds(5), factory);
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<?> inFlight = caller.submit(() -> executor.execute("user-a", request()));
            assertThat(factory.started.await(1, TimeUnit.SECONDS)).isTrue();

            executor.shutdown();

            assertThat(active.forceTerminationRequested).isTrue();
            assertThat(active.isAlive()).isFalse();
            assertThatThrownBy(() -> executor.execute("user-b", request()))
                    .isInstanceOf(DocumentProcessingCapacityExceededException.class);
            assertThatThrownBy(() -> inFlight.get(1, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(DocumentProcessingException.class);
        } finally {
            caller.shutdownNow();
            executor = null;
        }
    }

    @Test
    @DisplayName("Rejects a missing owner before starting a worker or acquiring shared capacity")
    void rejectsMissingOwnerWithoutConsumingCapacity() {
        ControllableWorker available = ControllableWorker.completed(successfulDocument());
        QueueWorkerFactory factory = new QueueWorkerFactory(available);
        executor = executor(1, 1, Duration.ofSeconds(1), factory);

        assertThatThrownBy(() -> executor.execute(" ", request()))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(executor.execute("user-a", request())).isSameAs(available.result);
    }

    @Test
    @DisplayName("Returns capacity after worker startup fails before a process is registered")
    void releasesCapacityAfterWorkerStartupFailure() {
        ControllableWorker replacement = ControllableWorker.completed(successfulDocument());
        AtomicBoolean firstInvocation = new AtomicBoolean(true);
        DocumentParserWorkerFactory factory = request -> {
            if (firstInvocation.getAndSet(false)) {
                throw new DocumentProcessingException("Document parser process could not be started");
            }
            return replacement;
        };
        executor = executor(1, 1, Duration.ofSeconds(1), factory);

        assertThatThrownBy(() -> executor.execute("user-a", request()))
                .isInstanceOf(DocumentProcessingException.class)
                .hasMessage("Document parser process could not be started");

        verify(metrics).record(DocumentParserWorkerMetrics.Outcome.SPAWN_FAILED);
        assertThat(executor.execute("user-a", request())).isSameAs(replacement.result);
    }

    @Test
    @DisplayName("Keeps successful parsing independent from meter registry failures")
    void ignoresTelemetryFailures() {
        ControllableWorker completed = ControllableWorker.completed(successfulDocument());
        executor = executor(1, 1, Duration.ofSeconds(1), new QueueWorkerFactory(completed));
        doThrow(new IllegalStateException("simulated meter failure")).when(metrics).workerStarted();
        doThrow(new IllegalStateException("simulated meter failure"))
                .when(metrics).record(DocumentParserWorkerMetrics.Outcome.SUCCEEDED);
        doThrow(new IllegalStateException("simulated meter failure")).when(metrics).workerStopped();

        assertThat(executor.execute("user-a", request())).isSameAs(completed.result);
        assertThat(completed.closed).isTrue();
    }

    private BoundedDocumentParseExecutor executor(
            int globalCapacity,
            int perUserCapacity,
            Duration timeout,
            DocumentParserWorkerFactory factory
    ) {
        DocumentProcessingLimits limits = DocumentProcessingLimits.defaults();
        limits.setMaxConcurrentParses(globalCapacity);
        limits.setMaxConcurrentParsesPerUser(perUserCapacity);
        limits.setParseTimeout(timeout);
        limits.setParserTerminationGrace(Duration.ofMillis(10));
        limits.setParserForceKillTimeout(Duration.ofMillis(20));
        limits.setParserShutdownTimeout(Duration.ofMillis(50));
        metrics = mock(DocumentParserWorkerMetrics.class);
        BoundedDocumentParseExecutor boundedExecutor = new BoundedDocumentParseExecutor(
                limits, factory, metrics);
        boundedExecutor.initialize();
        return boundedExecutor;
    }

    private DocumentParseRequest request() {
        return new DocumentParseRequest(Path.of("/storage/document.upload"), "document.txt", "text/plain", 12L);
    }

    private static ConvertedDocument successfulDocument() {
        ConvertedDocument document = new ConvertedDocument();
        document.setFullContent("Study notes\n");
        return document;
    }

    private static final class QueueWorkerFactory implements DocumentParserWorkerFactory {

        private final Queue<DocumentParserWorker> workers = new ArrayDeque<>();
        private final CountDownLatch started = new CountDownLatch(1);

        private QueueWorkerFactory(DocumentParserWorker... workers) {
            this.workers.addAll(java.util.List.of(workers));
        }

        @Override
        public DocumentParserWorker start(DocumentParseRequest request) {
            DocumentParserWorker worker = workers.poll();
            if (worker == null) {
                throw new AssertionError("No parser worker was configured for this invocation");
            }
            started.countDown();
            return worker;
        }
    }

    private static final class ControllableWorker implements DocumentParserWorker {

        private final CountDownLatch exited = new CountDownLatch(1);
        private final ConvertedDocument result;
        private volatile boolean alive;
        private volatile boolean terminateOnRequest;
        private volatile boolean terminateOnForce;
        private volatile boolean terminationRequested;
        private volatile boolean forceTerminationRequested;
        private volatile boolean closed;
        private volatile RuntimeException readFailure;

        private ControllableWorker(ConvertedDocument result, boolean alive) {
            this.result = result;
            this.alive = alive;
            if (!alive) {
                exited.countDown();
            }
        }

        static ControllableWorker blocking(ConvertedDocument result) {
            return new ControllableWorker(result, true);
        }

        static ControllableWorker completed(ConvertedDocument result) {
            return new ControllableWorker(result, false);
        }

        @Override
        public boolean await(Duration timeout) throws InterruptedException {
            exited.await(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS);
            return !alive;
        }

        @Override
        public void requestTermination() {
            terminationRequested = true;
            if (terminateOnRequest) {
                exit();
            }
        }

        @Override
        public void forceTermination() {
            forceTerminationRequested = true;
            if (terminateOnForce) {
                readFailure = new DocumentProcessingException("Document parser process failed");
                exit();
            }
        }

        @Override
        public boolean isAlive() {
            return alive;
        }

        @Override
        public ConvertedDocument readResult() {
            if (readFailure != null) {
                throw readFailure;
            }
            return result;
        }

        @Override
        public void close() {
            closed = true;
        }

        void exit() {
            alive = false;
            exited.countDown();
        }
    }
}
