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
import java.util.Arrays;
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
    @DisplayName("Stages frontend-extracted text that retains the selected PDF filename")
    void stagesFrontendExtractedTextWithPdfFilename() {
        LocalDocumentUploadStagingService service = new LocalDocumentUploadStagingService(limits(1024));
        byte[] extractedText = "Extracted PDF text for quiz generation.".getBytes(StandardCharsets.UTF_8);

        StagedDocumentUpload staged = service.stage(
                new ByteArrayInputStream(extractedText),
                "functional-programming.pdf",
                "text/plain",
                extractedText.length
        );

        assertThat(staged.detectedContentType()).isEqualTo("text/plain");

        service.discard(staged.stagingPath());
    }

    @Test
    @DisplayName("Rejects frontend-extracted text when a non-PDF source filename is retained")
    void rejectsFrontendExtractedTextWithNonPdfFilename() {
        LocalDocumentUploadStagingService service = new LocalDocumentUploadStagingService(limits(1024));
        byte[] extractedText = "Extracted PDF text for quiz generation.".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> service.stage(
                new ByteArrayInputStream(extractedText),
                "book.epub",
                "text/plain",
                extractedText.length
        )).isInstanceOf(DocumentTypeMismatchException.class);

        assertThat(storageRoot.resolve(".staging")).isEmptyDirectory();
    }

    @Test
    @DisplayName("Rejects PDF-named plain text without an explicit text declaration")
    void rejectsPdfNamedPlainTextWithGenericContentType() {
        LocalDocumentUploadStagingService service = new LocalDocumentUploadStagingService(limits(1024));
        byte[] extractedText = "Extracted PDF text for quiz generation.".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> service.stage(
                new ByteArrayInputStream(extractedText),
                "functional-programming.pdf",
                "application/octet-stream",
                extractedText.length
        )).isInstanceOf(DocumentTypeMismatchException.class);

        assertThat(storageRoot.resolve(".staging")).isEmptyDirectory();
    }

    @Test
    @DisplayName("Recognizes a PDF header after a standards-permitted leading preamble")
    void recognizesPdfHeaderWithinFirstKilobyte() {
        LocalDocumentUploadStagingService service = new LocalDocumentUploadStagingService(limits(2048));
        byte[] pdf = pdfWithHeaderAtOffset(3);

        StagedDocumentUpload staged = service.stage(
                new ByteArrayInputStream(pdf),
                "book.pdf",
                "application/pdf",
                pdf.length
        );

        assertThat(staged.detectedContentType()).isEqualTo("application/pdf");

        service.discard(staged.stagingPath());
    }

    @Test
    @DisplayName("Rejects a PDF header that begins after the first kilobyte")
    void rejectsPdfHeaderAfterFirstKilobyte() {
        LocalDocumentUploadStagingService service = new LocalDocumentUploadStagingService(limits(2048));
        byte[] pdf = pdfWithHeaderAtOffset(1024);

        assertThatThrownBy(() -> service.stage(
                new ByteArrayInputStream(pdf),
                "book.pdf",
                "application/pdf",
                pdf.length
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

    private DocumentProcessingLimits limits(long maxUploadBytes) {
        DocumentProcessingLimits limits = DocumentProcessingLimits.defaults();
        limits.setStorageRoot(storageRoot.toString());
        limits.setMaxUploadBytes(maxUploadBytes);
        return limits;
    }

    private byte[] pdfWithHeaderAtOffset(int offset) {
        byte[] content = new byte[offset + 5];
        Arrays.fill(content, (byte) ' ');
        System.arraycopy("%PDF-".getBytes(StandardCharsets.US_ASCII), 0, content, offset, 5);
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
