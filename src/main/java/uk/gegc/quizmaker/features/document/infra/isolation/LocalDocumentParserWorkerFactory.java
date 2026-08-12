package uk.gegc.quizmaker.features.document.infra.isolation;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.features.document.application.DocumentParseRequest;
import uk.gegc.quizmaker.features.document.application.DocumentParserWorker;
import uk.gegc.quizmaker.features.document.application.DocumentParserWorkerFactory;
import uk.gegc.quizmaker.features.document.application.DocumentProcessingLimits;
import uk.gegc.quizmaker.shared.exception.DocumentProcessingException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/** Local child-JVM adapter for the document parser worker port. */
@Component
public class LocalDocumentParserWorkerFactory implements DocumentParserWorkerFactory {

    static final String WORKER_DIRECTORY = ".parse-workers";
    private static final String OPERATION_PREFIX = "parse-";
    private static final Set<PosixFilePermission> OWNER_DIRECTORY_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE
    );
    private static final Set<PosixFilePermission> OWNER_FILE_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE
    );
    private static final Set<PosixFilePermission> OWNER_INPUT_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ
    );

    private final DocumentProcessingLimits limits;
    private final DocumentParserProtocolCodec codec;
    private final DocumentParserWorkerCommandFactory commandFactory;
    private final DocumentParserProcessStarter processStarter;
    private final Clock clock;
    private final Set<Path> activeOperations = ConcurrentHashMap.newKeySet();

    @Autowired
    public LocalDocumentParserWorkerFactory(
            DocumentProcessingLimits limits,
            @Qualifier("utcClock") Clock clock
    ) {
        this(
                limits,
                new DocumentParserProtocolCodec(),
                DocumentParserWorkerCommandFactory.currentApplication(),
                ProcessBuilder::start,
                clock
        );
    }

    LocalDocumentParserWorkerFactory(
            DocumentProcessingLimits limits,
            DocumentParserProtocolCodec codec,
            DocumentParserWorkerCommandFactory commandFactory,
            DocumentParserProcessStarter processStarter,
            Clock clock
    ) {
        this.limits = limits;
        this.codec = codec;
        this.commandFactory = commandFactory;
        this.processStarter = processStarter;
        this.clock = clock;
    }

    @PostConstruct
    void initialize() {
        prepareRootAndCleanExpired();
    }

    @Override
    public DocumentParserWorker start(DocumentParseRequest request) {
        Path validatedSource = validateSource(request);
        Path operationDirectory = null;
        Process process = null;
        try {
            operationDirectory = Files.createTempDirectory(
                    prepareRootAndCleanExpired(), OPERATION_PREFIX);
            activeOperations.add(operationDirectory);
            restrictPermissions(operationDirectory, OWNER_DIRECTORY_PERMISSIONS);

            Path isolatedSource = operationDirectory.resolve(DocumentParserProtocolCodec.INPUT_FILE);
            stageWorkerInput(validatedSource, isolatedSource, request.sizeBytes());
            restrictPermissions(isolatedSource, OWNER_INPUT_PERMISSIONS);
            DocumentParseRequest isolatedRequest = new DocumentParseRequest(
                    isolatedSource,
                    request.originalFilename(),
                    request.contentType(),
                    request.sizeBytes()
            );

            Path requestPath = operationDirectory.resolve(DocumentParserProtocolCodec.REQUEST_FILE);
            codec.writeRequest(
                    requestPath,
                    DocumentParserWorkerRequest.create(isolatedRequest, limits, operationDirectory)
            );
            restrictPermissions(requestPath, OWNER_FILE_PERMISSIONS);

            List<String> command = commandFactory.create(operationDirectory, limits);
            ProcessBuilder processBuilder = new ProcessBuilder(command)
                    .directory(operationDirectory.toFile())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD);
            processBuilder.environment().clear();
            process = processStarter.start(processBuilder);
            try {
                process.getOutputStream().close();
            } catch (IOException ignored) {
                // The worker does not consume standard input.
            }
            Path ownedOperation = operationDirectory;
            return new LocalDocumentParserWorker(
                    process,
                    ownedOperation,
                    request,
                    limits,
                    codec,
                    () -> cleanCompletedOperation(ownedOperation)
            );
        } catch (Exception spawnFailure) {
            if (process != null) {
                process.destroyForcibly();
            }
            if (operationDirectory != null) {
                activeOperations.remove(operationDirectory);
            }
            deleteRecursively(operationDirectory);
            throw new DocumentProcessingException("Document parser process could not be started");
        }
    }

    private Path validateSource(DocumentParseRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Document parse request is required");
        }
        Path storageRoot = storageRoot();
        Path source = request.sourcePath().toAbsolutePath().normalize();
        try {
            Path realStorageRoot = storageRoot.toRealPath();
            Path realSource = source.toRealPath();
            if (!realSource.startsWith(realStorageRoot)
                    || Files.isSymbolicLink(source)
                    || !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(source) != request.sizeBytes()) {
                throw new DocumentProcessingException("Document parser source is unavailable");
            }
            return realSource;
        } catch (IOException sourceFailure) {
            throw new DocumentProcessingException("Document parser source is unavailable");
        }
    }

    private void stageWorkerInput(Path source, Path destination, long expectedSize) throws IOException {
        Files.copy(source, destination);
        if (!Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS)
                || Files.size(destination) != expectedSize) {
            throw new IOException("Parser worker input staging failed");
        }
    }

    private Path prepareRootAndCleanExpired() {
        Path root = workerRoot();
        try {
            if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Configured parser worker root is not a directory");
            }
            Files.createDirectories(root);
            restrictPermissions(root, OWNER_DIRECTORY_PERMISSIONS);
            Instant cutoff = clock.instant().minus(limits.getStagingRetention());
            try (Stream<Path> entries = Files.list(root)) {
                entries.filter(this::isManagedOperationDirectory)
                        .filter(path -> !activeOperations.contains(path))
                        .filter(path -> isOlderThan(path, cutoff))
                        .forEach(LocalDocumentParserWorkerFactory::deleteRecursively);
            }
            return root;
        } catch (IOException storageFailure) {
            throw new DocumentProcessingException("Document parser workspace is unavailable");
        }
    }

    private boolean isManagedOperationDirectory(Path path) {
        return path.getFileName().toString().startsWith(OPERATION_PREFIX)
                && Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS);
    }

    private boolean isOlderThan(Path path, Instant cutoff) {
        try {
            return Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toInstant().isBefore(cutoff);
        } catch (IOException ignored) {
            return false;
        }
    }

    private void cleanCompletedOperation(Path operationDirectory) {
        try {
            deleteRecursively(operationDirectory);
        } finally {
            activeOperations.remove(operationDirectory);
        }
    }

    private Path workerRoot() {
        return storageRoot().resolve(WORKER_DIRECTORY);
    }

    private Path storageRoot() {
        return Paths.get(limits.getStorageRoot()).toAbsolutePath().normalize();
    }

    private void restrictPermissions(Path path, Set<PosixFilePermission> permissions) throws IOException {
        PosixFileAttributeView attributes = Files.getFileAttributeView(
                path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (attributes != null) {
            attributes.setPermissions(permissions);
        }
    }

    static void deleteRecursively(Path directory) {
        if (directory == null || !Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(LocalDocumentParserWorkerFactory::deletePath);
        } catch (IOException ignored) {
            // Crash leftovers are retried after the configured staging retention.
        }
    }

    private static void deletePath(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Crash leftovers are retried after the configured staging retention.
        }
    }
}
