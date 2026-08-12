package uk.gegc.quizmaker.features.document.infra.isolation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import uk.gegc.quizmaker.features.document.application.ConvertedDocument;
import uk.gegc.quizmaker.features.document.application.DocumentParseRequest;
import uk.gegc.quizmaker.features.document.application.DocumentParserWorkerException;
import uk.gegc.quizmaker.features.document.application.DocumentProcessingLimits;
import uk.gegc.quizmaker.shared.exception.DocumentResourceLimitException;
import uk.gegc.quizmaker.shared.exception.DocumentTypeMismatchException;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("Local document parser worker response validation")
class LocalDocumentParserWorkerTest {

    @TempDir
    Path operationDirectory;

    @Test
    @DisplayName("Maps the bounded JVM heap exit to the existing resource-limit failure")
    void mapsJvmHeapExitToResourceLimit() throws Exception {
        LocalDocumentParserWorker worker = worker(completedProcess(3), request(), limits());

        assertThatThrownBy(worker::readResult)
                .isInstanceOf(DocumentResourceLimitException.class)
                .hasMessage("Document parser exceeded the configured heap limit");
    }

    @Test
    @DisplayName("Rejects successful protocol output whose metadata does not match the request")
    void rejectsPoisonedSuccessfulResponse() throws Exception {
        DocumentProcessingLimits limits = limits();
        DocumentParseRequest request = request();
        ConvertedDocument document = validDocument(request);
        document.setOriginalFilename("different.txt");
        new DocumentParserProtocolCodec().writeResponse(
                operationDirectory.resolve(DocumentParserProtocolCodec.RESPONSE_FILE),
                DocumentParserWorkerResponse.success(document),
                limits.getParserWorkerMaxOutputBytes());
        LocalDocumentParserWorker worker = worker(completedProcess(0), request, limits);

        assertThatThrownBy(worker::readResult)
                .isInstanceOfSatisfying(DocumentParserWorkerException.class, failure ->
                        assertThat(failure.getReason()).isEqualTo(
                                DocumentParserWorkerException.FailureReason.INCOMPATIBLE_PROTOCOL))
                .hasMessage("Document parser returned an incompatible response");
    }

    @Test
    @DisplayName("Preserves the existing typed document mismatch failure from a valid worker response")
    void mapsTypedWorkerFailure() throws Exception {
        DocumentProcessingLimits limits = limits();
        new DocumentParserProtocolCodec().writeResponse(
                operationDirectory.resolve(DocumentParserProtocolCodec.RESPONSE_FILE),
                DocumentParserWorkerResponse.failure(DocumentParserWorkerError.TYPE_MISMATCH),
                limits.getParserWorkerMaxOutputBytes());
        LocalDocumentParserWorker worker = worker(completedProcess(0), request(), limits);

        assertThatThrownBy(worker::readResult)
                .isInstanceOf(DocumentTypeMismatchException.class)
                .hasMessage("Document content does not match a supported document type");
    }

    private LocalDocumentParserWorker worker(
            Process process,
            DocumentParseRequest request,
            DocumentProcessingLimits limits
    ) {
        return new LocalDocumentParserWorker(
                process, operationDirectory, request, limits, new DocumentParserProtocolCodec());
    }

    private Process completedProcess(int exitCode) {
        Process process = mock(Process.class);
        when(process.isAlive()).thenReturn(false);
        when(process.exitValue()).thenReturn(exitCode);
        return process;
    }

    private DocumentParseRequest request() throws Exception {
        Path source = Files.writeString(operationDirectory.resolve("source.upload"), "Study notes\n");
        return new DocumentParseRequest(source, "notes.txt", "text/plain", Files.size(source));
    }

    private DocumentProcessingLimits limits() {
        DocumentProcessingLimits limits = DocumentProcessingLimits.defaults();
        limits.setStorageRoot(operationDirectory.toString());
        limits.setMaxExtractedCharacters(10_000);
        limits.setParserWorkerMaxOutputBytes(1_000_000);
        return limits;
    }

    private ConvertedDocument validDocument(DocumentParseRequest request) {
        ConvertedDocument document = new ConvertedDocument();
        document.setFullContent("Study notes\n");
        document.setOriginalFilename("notes.txt");
        document.setContentType("text/plain");
        document.setFileSize(request.sizeBytes());
        document.setConverterType("TEXT_DOCUMENT_CONVERTER");
        return document;
    }
}
