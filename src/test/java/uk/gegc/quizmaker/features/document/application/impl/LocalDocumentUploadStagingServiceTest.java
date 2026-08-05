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

    private DocumentProcessingLimits limits(long maxUploadBytes) {
        DocumentProcessingLimits limits = DocumentProcessingLimits.defaults();
        limits.setStorageRoot(storageRoot.toString());
        limits.setMaxUploadBytes(maxUploadBytes);
        return limits;
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
