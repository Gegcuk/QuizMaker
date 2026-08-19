package uk.gegc.quizmaker.features.document.application.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import uk.gegc.quizmaker.features.document.application.DocumentIngestionMetrics;
import uk.gegc.quizmaker.features.document.application.DocumentProcessingLimits;
import uk.gegc.quizmaker.features.document.application.StagedDocumentUpload;
import uk.gegc.quizmaker.shared.exception.DocumentResourceLimitException;
import uk.gegc.quizmaker.shared.exception.DocumentStorageException;
import uk.gegc.quizmaker.shared.exception.DocumentTypeMismatchException;
import uk.gegc.quizmaker.shared.exception.DocumentUploadLimitExceededException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Local document upload staging")
class LocalDocumentUploadStagingServiceTest {

    @TempDir
    Path storageRoot;

    private final DocumentIngestionMetrics metrics = mock(DocumentIngestionMetrics.class);

    @Test
    @DisplayName("Rejects an oversized multipart upload before opening its stream")
    void rejectsOversizedMultipartBeforeReadingStream() throws IOException {
        DocumentProcessingLimits limits = limits(3);
        LocalDocumentUploadStagingService service = new LocalDocumentUploadStagingService(limits, metrics);
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getSize()).thenReturn(4L);

        assertThatThrownBy(() -> service.stage(file))
                .isInstanceOf(DocumentUploadLimitExceededException.class);

        verify(file, never()).getInputStream();
        verify(metrics).recordEvent(
                DocumentIngestionMetrics.Stage.STAGING,
                DocumentIngestionMetrics.Outcome.REJECTED,
                DocumentIngestionMetrics.Reason.UPLOAD_SIZE);
    }

    @Test
    @DisplayName("Stops streaming when actual content exceeds the server upload limit")
    void rejectsActualStreamThatExceedsLimit() {
        LocalDocumentUploadStagingService service = new LocalDocumentUploadStagingService(limits(3), metrics);

        assertThatThrownBy(() -> service.stage(
                new ByteArrayInputStream("four".getBytes(StandardCharsets.UTF_8)),
                "notes.txt",
                "text/plain",
                0
        )).isInstanceOf(DocumentUploadLimitExceededException.class);

        assertThat(storageRoot.resolve(".staging")).isEmptyDirectory();
    }

    @Test
    @DisplayName("Stages supported text using detected content rather than the generic declared MIME type")
    void stagesSupportedTextUsingDetectedContentType() {
        LocalDocumentUploadStagingService service = new LocalDocumentUploadStagingService(limits(1024), metrics);

        StagedDocumentUpload staged = service.stage(new MockMultipartFile(
                "file",
                "notes.txt",
                "application/octet-stream",
                "A short study note.".getBytes(StandardCharsets.UTF_8)
        ));

        assertThat(staged.detectedContentType()).isEqualTo("text/plain");
        assertThat(staged.sizeBytes()).isEqualTo("A short study note.".getBytes(StandardCharsets.UTF_8).length);
        assertThat(staged.stagingPath()).exists();
        assertThat(staged.stagingPath().getFileName().toString()).doesNotContain("notes.txt");
        verify(metrics).recordEvent(
                DocumentIngestionMetrics.Stage.STAGING,
                DocumentIngestionMetrics.Outcome.SUCCEEDED,
                DocumentIngestionMetrics.Reason.NONE);

        service.discard(staged.stagingPath());
    }

    @Test
    @DisplayName("Uses the API filename override and accepts a declared MIME charset")
    void stagesMultipartWithFilenameOverrideAndMimeCharset() {
        LocalDocumentUploadStagingService service =
                new LocalDocumentUploadStagingService(limits(1024), metrics);
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "ignored.bin",
                "text/plain; charset=UTF-8",
                "Selected study text".getBytes(StandardCharsets.UTF_8)
        );

        StagedDocumentUpload staged = service.stage(file, "selected.txt");

        assertThat(staged.originalFilename()).isEqualTo("selected.txt");
        assertThat(staged.detectedContentType()).isEqualTo("text/plain");
        service.discard(staged.stagingPath());
    }

    @Test
    @DisplayName("Stages selected UTF-8 text when detection ends within a multibyte character")
    void stagesSelectedUtf8TextWithIncompleteProbeCharacter() {
        LocalDocumentUploadStagingService service = new LocalDocumentUploadStagingService(limits(17 * 1024), metrics);
        byte[] extractedText = utf8TextWithMultibyteCharacterAcrossDetectionBoundary();

        StagedDocumentUpload staged = service.stage(
                new ByteArrayInputStream(extractedText),
                "selected-functional-programming.pdf.txt",
                "text/plain",
                extractedText.length
        );

        assertThat(staged.detectedContentType()).isEqualTo("text/plain");

        service.discard(staged.stagingPath());
    }

    @Test
    @DisplayName("Rejects malformed UTF-8 text extracted from a source document")
    void rejectsMalformedUtf8ExtractedText() {
        LocalDocumentUploadStagingService service = new LocalDocumentUploadStagingService(limits(1024), metrics);
        byte[] malformedText = {(byte) 0xC3, (byte) 0x28};

        assertThatThrownBy(() -> service.stage(
                new ByteArrayInputStream(malformedText),
                "selected-functional-programming.epub.txt",
                "text/plain",
                malformedText.length
        )).isInstanceOf(DocumentTypeMismatchException.class);

        assertThat(storageRoot.resolve(".staging")).isEmptyDirectory();
    }

    @Test
    @DisplayName("Rejects a truncated UTF-8 character when the probe contains the whole upload")
    void rejectsTruncatedUtf8WhenProbeContainsWholeUpload() {
        LocalDocumentUploadStagingService service = new LocalDocumentUploadStagingService(limits(1024), metrics);
        byte[] truncatedText = {(byte) 0xD0};

        assertThatThrownBy(() -> service.stage(
                new ByteArrayInputStream(truncatedText),
                "selected-functional-programming.pdf.txt",
                "text/plain",
                truncatedText.length
        )).isInstanceOf(DocumentTypeMismatchException.class);

        assertThat(storageRoot.resolve(".staging")).isEmptyDirectory();
    }

    @Test
    @DisplayName("Rejects content that conflicts with the filename or declared content type")
    void rejectsTypeMismatches() {
        LocalDocumentUploadStagingService service = new LocalDocumentUploadStagingService(limits(1024), metrics);

        assertThatThrownBy(() -> service.stage(
                new ByteArrayInputStream("plain text".getBytes(StandardCharsets.UTF_8)),
                "chapter.pdf",
                "application/pdf",
                10
        )).isInstanceOf(DocumentTypeMismatchException.class);

        assertThatThrownBy(() -> service.stage(
                new ByteArrayInputStream("plain text".getBytes(StandardCharsets.UTF_8)),
                "chapter.txt",
                "application/pdf",
                10
        )).isInstanceOf(DocumentTypeMismatchException.class);

        verify(metrics, times(2)).recordEvent(
                DocumentIngestionMetrics.Stage.STAGING,
                DocumentIngestionMetrics.Outcome.REJECTED,
                DocumentIngestionMetrics.Reason.TYPE_MISMATCH);
    }

    @Test
    @DisplayName("Closes the source and records type rejection when the filename is missing")
    void rejectsMissingFilenameInsideOwnedStagingBoundary() {
        LocalDocumentUploadStagingService service =
                new LocalDocumentUploadStagingService(limits(1024), metrics);
        TrackingInputStream source = new TrackingInputStream("plain text".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> service.stage(source, null, "text/plain", 10))
                .isInstanceOf(DocumentTypeMismatchException.class);

        assertThat(source.closed).isTrue();
        verify(metrics).recordEvent(
                DocumentIngestionMetrics.Stage.STAGING,
                DocumentIngestionMetrics.Outcome.REJECTED,
                DocumentIngestionMetrics.Reason.TYPE_MISMATCH);
    }

    @Test
    @DisplayName("Rejects EPUB archives that exceed server-owned archive limits before parsing")
    void rejectsEpubArchiveAboveEntryLimit() throws IOException {
        DocumentProcessingLimits limits = limits(1024 * 1024);
        limits.setMaxEpubEntries(1);
        LocalDocumentUploadStagingService service = new LocalDocumentUploadStagingService(limits, metrics);

        byte[] epub = epubWithTwoEntries();

        assertThatThrownBy(() -> service.stage(
                new ByteArrayInputStream(epub),
                "book.epub",
                "application/epub+zip",
                epub.length
        )).isInstanceOf(DocumentResourceLimitException.class);
    }

    @Test
    @DisplayName("Streams only expired published files and leaves deletion to the caller")
    void visitsOnlyExpiredPublishedFiles() throws IOException {
        DocumentProcessingLimits limits = limits(1024);
        LocalDocumentUploadStagingService service = new LocalDocumentUploadStagingService(limits, metrics);
        Path publishedDirectory = Files.createDirectories(storageRoot.resolve("published"));
        Path stagingDirectory = Files.createDirectories(storageRoot.resolve(".staging"));
        Path expiredPublished = Files.writeString(publishedDirectory.resolve("expired.pdf"), "old");
        Path freshPublished = Files.writeString(publishedDirectory.resolve("fresh.pdf"), "new");
        Path expiredStaging = Files.writeString(stagingDirectory.resolve("expired.upload"), "staged");
        FileTime expiredAt = FileTime.from(Instant.now().minus(limits.getStagingRetention()).minusSeconds(1));
        Files.setLastModifiedTime(expiredPublished, expiredAt);
        Files.setLastModifiedTime(expiredStaging, expiredAt);
        List<Path> visited = new ArrayList<>();

        service.visitExpiredPublishedFiles(visited::add);

        assertThat(visited).containsExactly(expiredPublished.toAbsolutePath().normalize());
        assertThat(expiredPublished).exists();
        assertThat(freshPublished).exists();
        assertThat(expiredStaging).doesNotExist();
    }

    @Test
    @DisplayName("Removes an existing published file idempotently across duplicate cleanup attempts")
    void discardsPublishedFileIdempotently() throws IOException {
        LocalDocumentUploadStagingService service = new LocalDocumentUploadStagingService(limits(1024), metrics);
        Path publishedFile = Files.createDirectories(storageRoot.resolve("published"))
                .resolve("document.pdf");
        Files.writeString(publishedFile, "fixture");

        assertThatCode(() -> {
            service.discard(publishedFile);
            service.discard(publishedFile);
        }).doesNotThrowAnyException();

        assertThat(publishedFile).doesNotExist();
    }

    @Test
    @DisplayName("Records successful promotion without exposing the original filename")
    void recordsSuccessfulPromotion() {
        LocalDocumentUploadStagingService service =
                new LocalDocumentUploadStagingService(limits(1024), metrics);
        StagedDocumentUpload staged = service.stage(new MockMultipartFile(
                "file",
                "private-study-notes.txt",
                "text/plain",
                "Study notes".getBytes(StandardCharsets.UTF_8)
        ));

        Path published = service.promote(staged);

        assertThat(published).exists();
        assertThat(staged.stagingPath()).doesNotExist();
        verify(metrics).recordEvent(
                DocumentIngestionMetrics.Stage.PROMOTION,
                DocumentIngestionMetrics.Outcome.SUCCEEDED,
                DocumentIngestionMetrics.Reason.NONE);
        service.discard(published);
    }

    @Test
    @DisplayName("Records a bounded storage failure when promotion cannot create its destination")
    void recordsPromotionFailure() throws IOException {
        LocalDocumentUploadStagingService service =
                new LocalDocumentUploadStagingService(limits(1024), metrics);
        Path stagedPath = Files.writeString(storageRoot.resolve("staged.upload"), "fixture");
        Files.writeString(storageRoot.resolve("published"), "destination-blocker");
        StagedDocumentUpload staged = new StagedDocumentUpload(
                stagedPath, "private-name.txt", "text/plain", Files.size(stagedPath));

        assertThatThrownBy(() -> service.promote(staged))
                .isInstanceOf(DocumentStorageException.class);

        verify(metrics).recordEvent(
                DocumentIngestionMetrics.Stage.PROMOTION,
                DocumentIngestionMetrics.Outcome.FAILED,
                DocumentIngestionMetrics.Reason.STORAGE);
    }

    @Test
    @DisplayName("Returns deferred cleanup and records a bounded failure when deletion cannot complete")
    void recordsDeferredCleanupWithoutThrowing() throws IOException {
        LocalDocumentUploadStagingService service =
                new LocalDocumentUploadStagingService(limits(1024), metrics);
        Path nonEmptyDirectory = Files.createDirectories(storageRoot.resolve("published/not-a-file"));
        Files.writeString(nonEmptyDirectory.resolve("child"), "fixture");

        assertThat(service.discard(nonEmptyDirectory)).isFalse();

        verify(metrics).recordEvent(
                DocumentIngestionMetrics.Stage.CLEANUP,
                DocumentIngestionMetrics.Outcome.FAILED,
                DocumentIngestionMetrics.Reason.CLEANUP);
    }

    private DocumentProcessingLimits limits(long maxUploadBytes) {
        DocumentProcessingLimits limits = DocumentProcessingLimits.defaults();
        limits.setStorageRoot(storageRoot.toString());
        limits.setMaxUploadBytes(maxUploadBytes);
        return limits;
    }

    private byte[] utf8TextWithMultibyteCharacterAcrossDetectionBoundary() {
        byte[] content = new byte[(16 * 1024) + 1];
        Arrays.fill(content, (byte) ' ');
        content[(16 * 1024) - 1] = (byte) 0xD0;
        content[16 * 1024] = (byte) 0x90;
        return content;
    }

    private byte[] epubWithTwoEntries() throws IOException {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("mimetype"));
            zip.write("application/epub+zip".getBytes(StandardCharsets.US_ASCII));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("OEBPS/chapter.xhtml"));
            zip.write("<p>Study text</p>".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.finish();
            return bytes.toByteArray();
        }
    }

    private static final class TrackingInputStream extends InputStream {

        private final ByteArrayInputStream delegate;
        private boolean closed;

        private TrackingInputStream(byte[] content) {
            delegate = new ByteArrayInputStream(content);
        }

        @Override
        public int read() {
            return delegate.read();
        }

        @Override
        public void close() throws IOException {
            closed = true;
            delegate.close();
        }
    }
}
