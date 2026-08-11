package uk.gegc.quizmaker.features.document.infra.converter;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
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
import java.util.Set;
import java.util.stream.Stream;

@Component
@Slf4j
public class PdfScratchSpace {

    private static final String SCRATCH_DIRECTORY = ".pdf-scratch";
    private static final String OPERATION_PREFIX = "pdf-parse-";
    private static final Set<PosixFilePermission> OWNER_ONLY = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE
    );

    private final DocumentProcessingLimits limits;
    private final Clock clock;

    @Autowired
    public PdfScratchSpace(DocumentProcessingLimits limits) {
        this(limits, Clock.systemUTC());
    }

    PdfScratchSpace(DocumentProcessingLimits limits, Clock clock) {
        this.limits = limits;
        this.clock = clock;
    }

    @PostConstruct
    void initialize() {
        prepareRootAndCleanExpired();
    }

    Session open() {
        Path root = prepareRootAndCleanExpired();
        Path operationDirectory = null;
        try {
            operationDirectory = Files.createTempDirectory(root, OPERATION_PREFIX);
            restrictToOwner(operationDirectory);
            MemoryUsageSetting memoryUsage = MemoryUsageSetting.setupMixed(
                            limits.getMaxPdfMainMemoryBytes(),
                            limits.getMaxPdfStorageBytes())
                    .setTempDir(operationDirectory.toFile());
            return new Session(operationDirectory, memoryUsage);
        } catch (IOException exception) {
            if (operationDirectory != null) {
                deleteRecursively(operationDirectory);
            }
            throw new DocumentProcessingException("PDF scratch storage is unavailable", exception);
        }
    }

    Path root() {
        return Paths.get(limits.getStorageRoot())
                .toAbsolutePath()
                .normalize()
                .resolve(SCRATCH_DIRECTORY);
    }

    private Path prepareRootAndCleanExpired() {
        Path root = root();
        try {
            if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Configured PDF scratch root is not a directory");
            }
            Files.createDirectories(root);
            restrictToOwner(root);
            cleanExpired(root);
            return root;
        } catch (IOException exception) {
            throw new DocumentProcessingException("PDF scratch storage is unavailable", exception);
        }
    }

    private void cleanExpired(Path root) throws IOException {
        Instant cutoff = clock.instant().minus(limits.getPdfScratchRetention());
        try (Stream<Path> entries = Files.list(root)) {
            entries.filter(this::isManagedOperationDirectory)
                    .filter(path -> lastModifiedBefore(path, cutoff))
                    .forEach(this::deleteRecursively);
        }
    }

    private boolean isManagedOperationDirectory(Path path) {
        return path.getFileName().toString().startsWith(OPERATION_PREFIX)
                && Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS);
    }

    private boolean lastModifiedBefore(Path path, Instant cutoff) {
        try {
            return Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toInstant().isBefore(cutoff);
        } catch (IOException exception) {
            log.warn("Could not inspect a PDF scratch artifact; preserving it");
            return false;
        }
    }

    private void restrictToOwner(Path path) throws IOException {
        PosixFileAttributeView attributes = Files.getFileAttributeView(
                path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (attributes != null) {
            attributes.setPermissions(OWNER_ONLY);
        }
    }

    private void deleteRecursively(Path directory) {
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(this::deletePath);
        } catch (IOException exception) {
            log.warn("Could not clean a PDF scratch operation directory");
        }
    }

    private void deletePath(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            log.warn("Could not remove a PDF scratch artifact");
        }
    }

    final class Session implements AutoCloseable {

        private final Path operationDirectory;
        private final MemoryUsageSetting memoryUsage;

        private Session(Path operationDirectory, MemoryUsageSetting memoryUsage) {
            this.operationDirectory = operationDirectory;
            this.memoryUsage = memoryUsage;
        }

        MemoryUsageSetting memoryUsage() {
            return memoryUsage;
        }

        Path operationDirectory() {
            return operationDirectory;
        }

        @Override
        public void close() {
            deleteRecursively(operationDirectory);
        }
    }
}
