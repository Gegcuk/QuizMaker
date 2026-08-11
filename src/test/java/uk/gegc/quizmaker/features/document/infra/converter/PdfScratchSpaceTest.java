package uk.gegc.quizmaker.features.document.infra.converter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import uk.gegc.quizmaker.features.document.application.DocumentProcessingLimits;
import uk.gegc.quizmaker.shared.exception.DocumentProcessingException;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("PDF scratch space")
class PdfScratchSpaceTest {

    private static final Instant NOW = Instant.parse("2026-08-11T12:00:00Z");

    @TempDir
    Path temporaryDirectory;

    @Test
    @DisplayName("Creates a bounded owner-private operation directory and removes it on close")
    void openCreatesBoundedPrivateSessionAndCloseRemovesIt() throws Exception {
        DocumentProcessingLimits limits = limits();
        PdfScratchSpace scratchSpace = scratchSpace(limits);

        Path operationDirectory;
        try (PdfScratchSpace.Session session = scratchSpace.open()) {
            operationDirectory = session.operationDirectory();
            assertThat(operationDirectory).isDirectory();
            assertThat(operationDirectory.getParent()).isEqualTo(scratchSpace.root());
            assertThat(session.memoryUsage().useMainMemory()).isTrue();
            assertThat(session.memoryUsage().useTempFile()).isTrue();
            assertThat(session.memoryUsage().getMaxMainMemoryBytes())
                    .isEqualTo(limits.getMaxPdfMainMemoryBytes());
            assertThat(session.memoryUsage().getMaxStorageBytes())
                    .isEqualTo(limits.getMaxPdfStorageBytes());
            assertThat(session.memoryUsage().getTempDir().toPath()).isEqualTo(operationDirectory);
            assertOwnerOnlyWhenSupported(operationDirectory);
            Files.writeString(operationDirectory.resolve("PDFBox-test.tmp"), "scratch");
        }

        assertThat(operationDirectory).doesNotExist();
    }

    @Test
    @DisplayName("Removes expired managed sessions while preserving fresh and unrelated directories")
    void initializeRemovesOnlyExpiredManagedSessions() throws Exception {
        DocumentProcessingLimits limits = limits();
        PdfScratchSpace scratchSpace = scratchSpace(limits);
        Path root = scratchSpace.root();
        Files.createDirectories(root);
        Path expired = Files.createDirectory(root.resolve("pdf-parse-expired"));
        Path fresh = Files.createDirectory(root.resolve("pdf-parse-fresh"));
        Path unrelated = Files.createDirectory(root.resolve("operator-owned"));
        Files.setLastModifiedTime(expired, FileTime.from(NOW.minus(Duration.ofHours(2))));
        Files.setLastModifiedTime(fresh, FileTime.from(NOW.minus(Duration.ofMinutes(5))));
        Files.setLastModifiedTime(unrelated, FileTime.from(NOW.minus(Duration.ofDays(1))));

        scratchSpace.initialize();

        assertThat(expired).doesNotExist();
        assertThat(fresh).isDirectory();
        assertThat(unrelated).isDirectory();
    }

    @Test
    @DisplayName("Fails closed when the configured scratch root cannot be created")
    void initializeFailsWhenScratchRootIsUnavailable() throws Exception {
        DocumentProcessingLimits limits = limits();
        Path storageRootFile = temporaryDirectory.resolve("not-a-directory");
        Files.writeString(storageRootFile, "occupied");
        limits.setStorageRoot(storageRootFile.toString());
        PdfScratchSpace scratchSpace = scratchSpace(limits);

        assertThatThrownBy(scratchSpace::initialize)
                .isInstanceOf(DocumentProcessingException.class)
                .hasMessage("PDF scratch storage is unavailable");
    }

    private DocumentProcessingLimits limits() {
        DocumentProcessingLimits limits = DocumentProcessingLimits.defaults();
        limits.setStorageRoot(temporaryDirectory.resolve("documents").toString());
        limits.setMaxPdfMainMemoryBytes(8_192);
        limits.setMaxPdfStorageBytes(16_384);
        limits.setPdfScratchRetention(Duration.ofHours(1));
        return limits;
    }

    private PdfScratchSpace scratchSpace(DocumentProcessingLimits limits) {
        return new PdfScratchSpace(limits, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void assertOwnerOnlyWhenSupported(Path path) throws Exception {
        PosixFileAttributeView attributes = Files.getFileAttributeView(
                path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (attributes != null) {
            assertThat(attributes.readAttributes().permissions()).isEqualTo(Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE
            ));
        }
    }
}
