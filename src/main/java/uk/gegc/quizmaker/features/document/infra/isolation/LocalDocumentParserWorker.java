package uk.gegc.quizmaker.features.document.infra.isolation;

import uk.gegc.quizmaker.features.document.application.ConvertedDocument;
import uk.gegc.quizmaker.features.document.application.DocumentParseRequest;
import uk.gegc.quizmaker.features.document.application.DocumentParserWorker;
import uk.gegc.quizmaker.features.document.application.DocumentParserWorkerException;
import uk.gegc.quizmaker.features.document.application.DocumentProcessingLimits;
import uk.gegc.quizmaker.shared.exception.DocumentProcessingException;
import uk.gegc.quizmaker.shared.exception.DocumentResourceLimitException;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

final class LocalDocumentParserWorker implements DocumentParserWorker {

    private static final int JVM_OUT_OF_MEMORY_EXIT = 3;

    private final Process process;
    private final Path operationDirectory;
    private final DocumentParseRequest request;
    private final DocumentProcessingLimits limits;
    private final DocumentParserProtocolCodec codec;
    private final Runnable cleanup;
    private final AtomicBoolean cleaned = new AtomicBoolean();

    LocalDocumentParserWorker(
            Process process,
            Path operationDirectory,
            DocumentParseRequest request,
            DocumentProcessingLimits limits,
            DocumentParserProtocolCodec codec
    ) {
        this(
                process,
                operationDirectory,
                request,
                limits,
                codec,
                () -> LocalDocumentParserWorkerFactory.deleteRecursively(operationDirectory)
        );
    }

    LocalDocumentParserWorker(
            Process process,
            Path operationDirectory,
            DocumentParseRequest request,
            DocumentProcessingLimits limits,
            DocumentParserProtocolCodec codec,
            Runnable cleanup
    ) {
        this.process = Objects.requireNonNull(process);
        this.operationDirectory = operationDirectory;
        this.request = request;
        this.limits = limits;
        this.codec = codec;
        this.cleanup = cleanup;
    }

    @Override
    public boolean await(Duration timeout) throws InterruptedException {
        long waitMillis = Math.max(1, timeout.toMillis());
        return process.waitFor(waitMillis, TimeUnit.MILLISECONDS);
    }

    @Override
    public void requestTermination() {
        process.destroy();
    }

    @Override
    public void forceTermination() {
        process.destroyForcibly();
    }

    @Override
    public boolean isAlive() {
        return process.isAlive();
    }

    @Override
    public ConvertedDocument readResult() {
        if (process.isAlive()) {
            throw new DocumentProcessingException("Document parser result is not ready");
        }
        int exitCode = process.exitValue();
        if (exitCode == JVM_OUT_OF_MEMORY_EXIT) {
            throw new DocumentResourceLimitException("Document parser exceeded the configured heap limit");
        }
        if (exitCode != 0) {
            throw new DocumentParserWorkerException(
                    DocumentParserWorkerException.FailureReason.PROCESS_CRASH,
                    "Document parser process failed",
                    exitCode);
        }

        DocumentParserWorkerResponse response;
        try {
            response = codec.readResponse(
                    operationDirectory.resolve(DocumentParserProtocolCodec.RESPONSE_FILE),
                    limits.getParserWorkerMaxOutputBytes()
            );
        } catch (IOException invalidResponse) {
            throw new DocumentParserWorkerException(
                    DocumentParserWorkerException.FailureReason.INVALID_OUTPUT,
                    "Document parser returned an invalid response");
        }
        if (response == null
                || response.protocolVersion() != DocumentParserProtocolCodec.PROTOCOL_VERSION
                || (response.document() == null) == (response.error() == null)) {
            throw incompatibleResponse();
        }
        if (response.error() != null) {
            throw response.error().toException();
        }
        validateDocument(response.document());
        return response.document();
    }

    @Override
    public void close() {
        if (!process.isAlive() && cleaned.compareAndSet(false, true)) {
            cleanup.run();
        }
    }

    private void validateDocument(ConvertedDocument document) {
        DocumentParserFormat expectedFormat = DocumentParserFormat.resolve(
                request.contentType(), request.originalFilename());
        if (expectedFormat == null
                || document.getFullContent() == null
                || document.getFullContent().length() > limits.getMaxExtractedCharacters()
                || !request.originalFilename().equals(document.getOriginalFilename())
                || !Objects.equals(expectedFormat.convertedContentType(), document.getContentType())
                || !Objects.equals(expectedFormat.converterType(), document.getConverterType())
                || !Objects.equals(request.sizeBytes(), document.getFileSize())
                || document.getChapters() == null
                || (document.getTotalPages() != null
                && (document.getTotalPages() < 0
                || document.getTotalPages() > limits.getMaxPdfPages()))) {
            throw incompatibleResponse();
        }
    }

    private DocumentParserWorkerException incompatibleResponse() {
        return new DocumentParserWorkerException(
                DocumentParserWorkerException.FailureReason.INCOMPATIBLE_PROTOCOL,
                "Document parser returned an incompatible response");
    }
}
