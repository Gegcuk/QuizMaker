package uk.gegc.quizmaker.features.document.infra.isolation;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import uk.gegc.quizmaker.features.document.application.ConvertedDocument;
import uk.gegc.quizmaker.features.document.application.DocumentParseRequest;
import uk.gegc.quizmaker.features.document.application.DocumentProcessingLimits;
import uk.gegc.quizmaker.features.document.application.impl.BoundedDocumentParseExecutor;
import uk.gegc.quizmaker.features.document.application.impl.MicrometerDocumentParserWorkerMetrics;
import uk.gegc.quizmaker.shared.exception.DocumentProcessingException;
import uk.gegc.quizmaker.shared.exception.DocumentResourceLimitException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Document parser operating-system process boundary")
class DocumentParserProcessBoundaryTest {

    @TempDir
    Path temporaryDirectory;

    private BoundedDocumentParseExecutor executor;
    private final AtomicReference<Process> lastProcess = new AtomicReference<>();

    @AfterEach
    void stopProcesses() {
        if (executor != null) {
            ReflectionTestUtils.invokeMethod(executor, "shutdown");
        }
        Process process = lastProcess.get();
        if (process != null && process.isAlive()) {
            process.destroyForcibly();
        }
    }

    @Test
    @DisplayName("Force-kills a SIGTERM-ignoring process before starting the replacement parse")
    void forceKillsTermIgnoringProcessBeforeCapacityReuse() throws IOException {
        DocumentProcessingLimits limits = limits();
        limits.setParseTimeout(Duration.ofMillis(100));
        limits.setParserTerminationGrace(Duration.ofMillis(100));
        AtomicInteger invocation = new AtomicInteger();
        DocumentParserWorkerCommandFactory realWorker = DocumentParserWorkerCommandFactory.currentApplication();
        LocalDocumentParserWorkerFactory factory = factory(limits, (operation, configuredLimits) ->
                invocation.getAndIncrement() == 0
                        ? List.of("/bin/sh", "-c", "trap '' TERM; : > worker.ready; while :; do :; done")
                        : realWorker.create(operation, configuredLimits));
        Path source = Files.writeString(storageRoot(limits).resolve("notes.upload"), "Short notes\n");
        executor = executor(limits, factory);

        assertThatThrownBy(() -> executor.execute("owner", request(source)))
                .isInstanceOf(DocumentResourceLimitException.class)
                .hasMessage("Document processing exceeded the configured time limit");

        Process timedOutProcess = lastProcess.get();
        assertThat(timedOutProcess.isAlive()).isFalse();
        assertThat(ProcessHandle.of(timedOutProcess.pid()).map(ProcessHandle::isAlive).orElse(false)).isFalse();
        limits.setParseTimeout(Duration.ofSeconds(10));
        ConvertedDocument replacement = executor.execute("owner", request(source));
        assertThat(replacement.getFullContent()).isEqualTo("Short notes\n");
    }

    @Test
    @DisplayName("Rejects malformed worker output and removes its private operation directory")
    void rejectsMalformedWorkerOutput() throws IOException {
        DocumentProcessingLimits limits = limits();
        LocalDocumentParserWorkerFactory factory = factory(
                limits,
                (operation, configuredLimits) -> List.of(
                        "/bin/sh", "-c", "printf '%s' '{not-json' > response.json")
        );
        Path source = Files.writeString(storageRoot(limits).resolve("notes.upload"), "Study notes\n");
        executor = executor(limits, factory);

        assertThatThrownBy(() -> executor.execute("owner", request(source)))
                .isInstanceOf(DocumentProcessingException.class)
                .hasMessage("Document parser returned an invalid response");

        assertThat(source).isRegularFile();
        assertWorkerDirectoryEmpty(limits);
    }

    @Test
    @DisplayName("Rejects an oversized worker response before parsing its JSON")
    void rejectsOversizedWorkerOutput() throws IOException {
        DocumentProcessingLimits limits = limits();
        limits.setMaxExtractedCharacters(1_024);
        limits.setParserWorkerMaxOutputBytes(1_024);
        LocalDocumentParserWorkerFactory factory = factory(
                limits,
                (operation, configuredLimits) -> List.of(
                        "/bin/sh", "-c", "dd if=/dev/zero of=response.json bs=2048 count=1 2>/dev/null")
        );
        Path source = Files.writeString(storageRoot(limits).resolve("notes.upload"), "Study notes\n");
        executor = executor(limits, factory);

        assertThatThrownBy(() -> executor.execute("owner", request(source)))
                .isInstanceOf(DocumentProcessingException.class)
                .hasMessage("Document parser returned an invalid response");

        assertWorkerDirectoryEmpty(limits);
    }

    @Test
    @DisplayName("Maps a crashed child to the existing safe failure and reclaims files and permits")
    void mapsWorkerCrashAndReclaimsResources() throws IOException {
        DocumentProcessingLimits limits = limits();
        AtomicInteger invocation = new AtomicInteger();
        DocumentParserWorkerCommandFactory realWorker = DocumentParserWorkerCommandFactory.currentApplication();
        LocalDocumentParserWorkerFactory factory = factory(limits, (operation, configuredLimits) ->
                invocation.getAndIncrement() == 0
                        ? List.of("/bin/sh", "-c", "exit 17")
                        : realWorker.create(operation, configuredLimits));
        Path source = Files.writeString(storageRoot(limits).resolve("notes.upload"), "Study notes\n");
        executor = executor(limits, factory);

        assertThatThrownBy(() -> executor.execute("owner", request(source)))
                .isInstanceOf(DocumentProcessingException.class)
                .hasMessage("Document parser process failed");

        assertWorkerDirectoryEmpty(limits);
        assertThat(executor.execute("owner", request(source)).getFullContent()).isEqualTo("Study notes\n");
    }

    @Test
    @DisplayName("Service shutdown force-kills a real SIGTERM-ignoring parser within its budget")
    void shutdownForceKillsTermIgnoringProcess() throws Exception {
        DocumentProcessingLimits limits = limits();
        limits.setParserTerminationGrace(Duration.ofMillis(100));
        LocalDocumentParserWorkerFactory factory = factory(
                limits,
                (operation, configuredLimits) -> List.of(
                        "/bin/sh", "-c", "trap '' TERM; : > worker.ready; while :; do :; done")
        );
        Path source = Files.writeString(storageRoot(limits).resolve("notes.upload"), "Study notes\n");
        executor = executor(limits, factory);
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<ConvertedDocument> inFlight = caller.submit(() -> executor.execute("owner", request(source)));
            Process process = awaitStartedProcess();

            long startedAt = System.nanoTime();
            ReflectionTestUtils.invokeMethod(executor, "shutdown");
            Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

            assertThat(elapsed).isLessThan(limits.getParserShutdownTimeout().plusMillis(500));
            assertThat(process.isAlive()).isFalse();
            assertThat(ProcessHandle.of(process.pid()).map(ProcessHandle::isAlive).orElse(false)).isFalse();
            assertThatThrownBy(() -> inFlight.get(1, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(DocumentProcessingException.class);
            assertWorkerDirectoryEmpty(limits);
        } finally {
            caller.shutdownNow();
            executor = null;
        }
    }

    private LocalDocumentParserWorkerFactory factory(
            DocumentProcessingLimits limits,
            DocumentParserWorkerCommandFactory commandFactory
    ) {
        return new LocalDocumentParserWorkerFactory(
                limits,
                new DocumentParserProtocolCodec(),
                commandFactory,
                builder -> {
                    Process process = builder.start();
                    lastProcess.set(process);
                    if (builder.command().stream().anyMatch(argument -> argument.contains("worker.ready"))) {
                        awaitReadyMarker(builder.directory().toPath(), process);
                    }
                    return process;
                },
                Clock.systemUTC()
        );
    }

    private void awaitReadyMarker(Path operationDirectory, Process process) throws IOException {
        Path marker = operationDirectory.resolve("worker.ready");
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline && process.isAlive()) {
            if (Files.isRegularFile(marker)) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for the parser test process", interrupted);
            }
        }
        throw new IOException("Parser test process did not install its termination handler");
    }

    private Process awaitStartedProcess() throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            Process process = lastProcess.get();
            if (process != null && Files.isRegularFile(findReadyMarker())) {
                return process;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Parser test process did not start");
    }

    private Path findReadyMarker() {
        Path workerRoot = storageRootForCurrentTest().resolve(LocalDocumentParserWorkerFactory.WORKER_DIRECTORY);
        try (var operations = Files.list(workerRoot)) {
            return operations
                    .map(operation -> operation.resolve("worker.ready"))
                    .filter(Files::isRegularFile)
                    .findFirst()
                    .orElse(workerRoot.resolve("missing.ready"));
        } catch (IOException ignored) {
            return workerRoot.resolve("missing.ready");
        }
    }

    private Path storageRootForCurrentTest() {
        return temporaryDirectory.resolve("documents");
    }

    private BoundedDocumentParseExecutor executor(
            DocumentProcessingLimits limits,
            LocalDocumentParserWorkerFactory factory
    ) {
        BoundedDocumentParseExecutor result = new BoundedDocumentParseExecutor(
                limits,
                factory,
                new MicrometerDocumentParserWorkerMetrics(new SimpleMeterRegistry())
        );
        ReflectionTestUtils.invokeMethod(result, "initialize");
        return result;
    }

    private DocumentProcessingLimits limits() throws IOException {
        DocumentProcessingLimits limits = DocumentProcessingLimits.defaults();
        limits.setStorageRoot(Files.createDirectories(temporaryDirectory.resolve("documents")).toString());
        limits.setMaxExtractedCharacters(100_000);
        limits.setParserWorkerMaxHeapBytes(256L * 1024 * 1024);
        limits.setParserWorkerMaxOutputBytes(4L * 1024 * 1024);
        limits.setParseTimeout(Duration.ofSeconds(10));
        limits.setParserTerminationGrace(Duration.ofMillis(100));
        limits.setParserForceKillTimeout(Duration.ofSeconds(2));
        limits.setParserShutdownTimeout(Duration.ofSeconds(3));
        return limits;
    }

    private Path storageRoot(DocumentProcessingLimits limits) {
        return Path.of(limits.getStorageRoot());
    }

    private DocumentParseRequest request(Path source) throws IOException {
        return new DocumentParseRequest(source, "notes.txt", "text/plain", Files.size(source));
    }

    private void assertWorkerDirectoryEmpty(DocumentProcessingLimits limits) throws IOException {
        Path workerRoot = storageRoot(limits).resolve(LocalDocumentParserWorkerFactory.WORKER_DIRECTORY);
        try (var entries = Files.list(workerRoot)) {
            assertThat(entries).isEmpty();
        }
    }
}
