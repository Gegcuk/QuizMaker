package uk.gegc.quizmaker.features.document.application.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import uk.gegc.quizmaker.features.document.application.DocumentProcessingLimits;
import uk.gegc.quizmaker.features.document.application.StagedDocumentUpload;
import uk.gegc.quizmaker.shared.exception.DocumentResourceLimitException;
import uk.gegc.quizmaker.shared.exception.DocumentTypeMismatchException;
import uk.gegc.quizmaker.shared.exception.DocumentUploadLimitExceededException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Local document upload staging")
class LocalDocumentUploadStagingServiceTest {

    @TempDir
    Path storageRoot;

    @Test
    @DisplayName("Rejects an oversized multipart upload before opening its stream")
    void rejectsOversizedMultipartBeforeReadingStream() throws IOException {
        DocumentProcessingLimits limits = limits(3);
        LocalDocumentUploadStagingService service = new LocalDocumentUploadStagingService(limits);
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getSize()).thenReturn(4L);

        assertThatThrownBy(() -> service.stage(file))
                .isInstanceOf(DocumentUploadLimitExceededException.class);

        verify(file, never()).getInputStream();
    }

    @Test
    @DisplayName("Stops streaming when actual content exceeds the server upload limit")
    void rejectsActualStreamThatExceedsLimit() {
        LocalDocumentUploadStagingService service = new LocalDocumentUploadStagingService(limits(3));

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
        LocalDocumentUploadStagingService service = new LocalDocumentUploadStagingService(limits(1024));

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

        service.discard(staged.stagingPath());
    }

    @Test
    @DisplayName("Stages selected UTF-8 text when detection ends within a multibyte character")
    void stagesSelectedUtf8TextWithIncompleteProbeCharacter() {
        LocalDocumentUploadStagingService service = new LocalDocumentUploadStagingService(limits(17 * 1024));
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
        LocalDocumentUploadStagingService service = new LocalDocumentUploadStagingService(limits(1024));
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
        LocalDocumentUploadStagingService service = new LocalDocumentUploadStagingService(limits(1024));
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
        LocalDocumentUploadStagingService service = new LocalDocumentUploadStagingService(limits(1024));

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
    }

    @Test
    @DisplayName("Rejects EPUB archives that exceed server-owned archive limits before parsing")
    void rejectsEpubArchiveAboveEntryLimit() throws IOException {
        DocumentProcessingLimits limits = limits(1024 * 1024);
        limits.setMaxEpubEntries(1);
        LocalDocumentUploadStagingService service = new LocalDocumentUploadStagingService(limits);

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
        LocalDocumentUploadStagingService service = new LocalDocumentUploadStagingService(limits);
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
}
