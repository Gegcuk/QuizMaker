package uk.gegc.quizmaker.features.document.infra.isolation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import uk.gegc.quizmaker.features.document.application.DocumentParseRequest;
import uk.gegc.quizmaker.features.document.application.DocumentParserWorker;
import uk.gegc.quizmaker.features.document.application.DocumentProcessingLimits;
import uk.gegc.quizmaker.shared.exception.DocumentProcessingException;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("Local document parser worker factory")
class LocalDocumentParserWorkerFactoryTest {

    @TempDir
    Path storageRoot;

    @Test
    @DisplayName("Starts workers without inherited secrets or source metadata in the command")
    void startsWorkerWithPrivateSecretFreeProcessBoundary() throws IOException {
        DocumentProcessingLimits limits = limits();
        Path source = Files.writeString(storageRoot.resolve("source.upload"), "Study notes");
        DocumentParseRequest request = new DocumentParseRequest(
                source, "private-notes.txt", "text/plain", Files.size(source));
        AtomicReference<ProcessBuilder> capturedBuilder = new AtomicReference<>();
        Process process = completedProcess();
        LocalDocumentParserWorkerFactory factory = new LocalDocumentParserWorkerFactory(
                limits,
                new DocumentParserProtocolCodec(),
                (operation, configuredLimits) -> List.of("/usr/bin/true", operation.toString()),
                builder -> {
                    capturedBuilder.set(builder);
                    return process;
                },
                Clock.systemUTC()
        );
        factory.initialize();

        DocumentParserWorker worker = factory.start(request);

        ProcessBuilder builder = capturedBuilder.get();
        assertThat(builder.environment()).isEmpty();
        assertThat(builder.command())
                .noneMatch(argument -> argument.contains("private-notes") || argument.contains("source.upload"));
        assertThat(builder.directory().toPath()).isDirectory();
        assertThat(builder.directory().toPath().startsWith(
                storageRoot.resolve(LocalDocumentParserWorkerFactory.WORKER_DIRECTORY))).isTrue();
        assertThat(builder.directory().toPath().resolve(DocumentParserProtocolCodec.REQUEST_FILE))
                .isRegularFile();
        DocumentParserWorkerRequest workerRequest = new DocumentParserProtocolCodec().readRequest(
                builder.directory().toPath().resolve(DocumentParserProtocolCodec.REQUEST_FILE));
        assertThat(Path.of(workerRequest.sourcePath()).getParent()).isEqualTo(builder.directory().toPath());
        assertThat(Path.of(workerRequest.sourcePath()).getFileName().toString())
                .isEqualTo(DocumentParserProtocolCodec.INPUT_FILE);
        assertThat(workerRequest.sourceStorageRoot()).isEqualTo(builder.directory().toPath().toString());
        assertOwnerOnlyPermissions(builder.directory().toPath(), java.util.Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE));
        assertOwnerOnlyPermissions(Path.of(workerRequest.sourcePath()), java.util.Set.of(
                PosixFilePermission.OWNER_READ));
        assertOwnerOnlyPermissions(
                builder.directory().toPath().resolve(DocumentParserProtocolCodec.REQUEST_FILE),
                java.util.Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));

        worker.close();
        assertThat(builder.directory().toPath()).doesNotExist();
        assertThat(source).isRegularFile();
    }

    @Test
    @DisplayName("Deletes a private operation directory when process spawn fails")
    void cleansOperationDirectoryAfterSpawnFailure() throws IOException {
        DocumentProcessingLimits limits = limits();
        Path source = Files.writeString(storageRoot.resolve("source.upload"), "Study notes");
        LocalDocumentParserWorkerFactory factory = new LocalDocumentParserWorkerFactory(
                limits,
                new DocumentParserProtocolCodec(),
                (operation, configuredLimits) -> List.of("missing-worker"),
                builder -> {
                    throw new IOException("simulated spawn denial");
                },
                Clock.systemUTC()
        );
        factory.initialize();

        assertThatThrownBy(() -> factory.start(new DocumentParseRequest(
                source, "notes.txt", "text/plain", Files.size(source))))
                .isInstanceOf(DocumentProcessingException.class)
                .hasMessage("Document parser process could not be started")
                .hasMessageNotContaining(source.toString());

        try (var operations = Files.list(storageRoot.resolve(LocalDocumentParserWorkerFactory.WORKER_DIRECTORY))) {
            assertThat(operations).isEmpty();
        }
    }

    @Test
    @DisplayName("Removes only expired managed crash leftovers during initialization")
    void cleansExpiredManagedWorkerDirectories() throws IOException {
        DocumentProcessingLimits limits = limits();
        limits.setStagingRetention(Duration.ofHours(1));
        Path workerRoot = Files.createDirectories(
                storageRoot.resolve(LocalDocumentParserWorkerFactory.WORKER_DIRECTORY));
        Path expired = Files.createDirectory(workerRoot.resolve("parse-expired"));
        Path recent = Files.createDirectory(workerRoot.resolve("parse-recent"));
        Path unrelated = Files.createDirectory(workerRoot.resolve("operator-notes"));
        Instant now = Instant.parse("2026-08-12T10:00:00Z");
        Files.setLastModifiedTime(expired, FileTime.from(now.minus(Duration.ofHours(2))));
        Files.setLastModifiedTime(recent, FileTime.from(now.minus(Duration.ofMinutes(5))));

        LocalDocumentParserWorkerFactory factory = new LocalDocumentParserWorkerFactory(
                limits,
                new DocumentParserProtocolCodec(),
                DocumentParserWorkerCommandFactory.currentApplication(),
                ProcessBuilder::start,
                Clock.fixed(now, ZoneOffset.UTC)
        );
        factory.initialize();

        assertThat(expired).doesNotExist();
        assertThat(recent).isDirectory();
        assertThat(unrelated).isDirectory();
    }

    @Test
    @DisplayName("Rejects a source outside the configured document storage root before spawning")
    void rejectsSourceOutsideStorageRoot() throws IOException {
        DocumentProcessingLimits limits = limits();
        Path outside = Files.createTempFile(storageRoot.getParent(), "outside-", ".upload");
        Files.writeString(outside, "Study notes");
        LocalDocumentParserWorkerFactory factory = new LocalDocumentParserWorkerFactory(limits, Clock.systemUTC());
        factory.initialize();

        assertThatThrownBy(() -> factory.start(new DocumentParseRequest(
                outside, "notes.txt", "text/plain", Files.size(outside))))
                .isInstanceOf(DocumentProcessingException.class)
                .hasMessage("Document parser source is unavailable")
                .hasMessageNotContaining(outside.toString());
        Files.deleteIfExists(outside);
    }

    @Test
    @DisplayName("Rejects a symbolic-link source before a parser process can start")
    void rejectsSymbolicLinkSource() throws IOException {
        DocumentProcessingLimits limits = limits();
        Path target = Files.writeString(storageRoot.resolve("target.upload"), "Study notes");
        Path symbolicLink = storageRoot.resolve("source.upload");
        Files.createSymbolicLink(symbolicLink, target.getFileName());
        LocalDocumentParserWorkerFactory factory = new LocalDocumentParserWorkerFactory(limits, Clock.systemUTC());
        factory.initialize();

        assertThatThrownBy(() -> factory.start(new DocumentParseRequest(
                symbolicLink, "notes.txt", "text/plain", Files.size(symbolicLink))))
                .isInstanceOf(DocumentProcessingException.class)
                .hasMessage("Document parser source is unavailable");
    }

    @Test
    @DisplayName("Builds a bounded child JVM command without source filenames")
    void commandUsesConfiguredHeapAndVersionedWorkerEntryPoint() {
        DocumentProcessingLimits limits = limits();
        Path operation = storageRoot.resolve("parse-operation").toAbsolutePath().normalize();

        List<String> command = DocumentParserWorkerCommandFactory.currentApplication()
                .create(operation, limits);

        assertThat(command).contains(
                "-Xmx" + limits.getParserWorkerMaxHeapBytes(),
                "-XX:+ExitOnOutOfMemoryError",
                DocumentParserWorkerMain.WORKER_ARGUMENT + operation
        );
        assertThat(command).noneMatch(argument -> argument.contains("notes.txt"));
    }

    @Test
    @DisplayName("Never removes an active operation even when its retention timestamp is old")
    void preservesActiveOperationDuringConcurrentCleanup() throws IOException {
        DocumentProcessingLimits limits = limits();
        limits.setStagingRetention(Duration.ofSeconds(1));
        Instant now = Instant.parse("2026-08-12T10:00:00Z");
        Path source = Files.writeString(storageRoot.resolve("source.upload"), "Study notes");
        List<ProcessBuilder> builders = new ArrayList<>();
        LocalDocumentParserWorkerFactory factory = new LocalDocumentParserWorkerFactory(
                limits,
                new DocumentParserProtocolCodec(),
                (operation, configuredLimits) -> List.of("/usr/bin/true"),
                builder -> {
                    builders.add(builder);
                    return completedProcess();
                },
                Clock.fixed(now, ZoneOffset.UTC)
        );
        factory.initialize();

        DocumentParserWorker first = factory.start(new DocumentParseRequest(
                source, "notes.txt", "text/plain", Files.size(source)));
        Path firstOperation = builders.get(0).directory().toPath();
        Files.setLastModifiedTime(firstOperation, FileTime.from(now.minus(Duration.ofHours(1))));
        DocumentParserWorker second = factory.start(new DocumentParseRequest(
                source, "notes.txt", "text/plain", Files.size(source)));

        assertThat(firstOperation).isDirectory();
        first.close();
        second.close();
        assertThat(firstOperation).doesNotExist();
    }

    private DocumentProcessingLimits limits() {
        DocumentProcessingLimits limits = DocumentProcessingLimits.defaults();
        limits.setStorageRoot(storageRoot.toString());
        limits.setParserWorkerMaxHeapBytes(128L * 1024 * 1024);
        limits.setParserWorkerMaxOutputBytes(4L * 1024 * 1024);
        return limits;
    }

    private Process completedProcess() throws IOException {
        Process process = mock(Process.class);
        when(process.getOutputStream()).thenReturn(OutputStream.nullOutputStream());
        when(process.isAlive()).thenReturn(false);
        when(process.exitValue()).thenReturn(0);
        return process;
    }

    private void assertOwnerOnlyPermissions(
            Path path,
            java.util.Set<PosixFilePermission> expected
    ) throws IOException {
        if (!Files.getFileStore(path).supportsFileAttributeView("posix")) {
            return;
        }
        assertThat(Files.getPosixFilePermissions(path)).isEqualTo(expected);
    }
}
