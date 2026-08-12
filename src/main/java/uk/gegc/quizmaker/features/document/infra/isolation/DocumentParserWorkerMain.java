package uk.gegc.quizmaker.features.document.infra.isolation;

import org.apache.pdfbox.pdmodel.font.FontMappers;
import uk.gegc.quizmaker.features.document.application.ConvertedDocument;
import uk.gegc.quizmaker.features.document.application.DocumentConverter;
import uk.gegc.quizmaker.features.document.application.DocumentConverterFactory;
import uk.gegc.quizmaker.features.document.application.DocumentProcessingLimits;
import uk.gegc.quizmaker.features.document.infra.converter.EpubDocumentConverter;
import uk.gegc.quizmaker.features.document.infra.converter.PdfDocumentConverter;
import uk.gegc.quizmaker.features.document.infra.converter.TextDocumentConverter;
import uk.gegc.quizmaker.shared.exception.DocumentResourceLimitException;
import uk.gegc.quizmaker.shared.exception.DocumentTypeMismatchException;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;

/** Minimal non-Spring entry point for one isolated document conversion. */
public final class DocumentParserWorkerMain {

    public static final String WORKER_ARGUMENT = "--document-parser-worker=";
    private static final int INVALID_PROTOCOL_EXIT = 64;
    private static final int PARENT_GONE_EXIT = 70;

    private DocumentParserWorkerMain() {
    }

    public static void main(String[] args) {
        int exitCode = run(args);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    public static boolean isRequested(String[] args) {
        if (args == null) {
            return false;
        }
        for (String argument : args) {
            if (argument != null && argument.startsWith(WORKER_ARGUMENT)) {
                return true;
            }
        }
        return false;
    }

    public static int run(String[] args) {
        Path operationDirectory;
        try {
            operationDirectory = operationDirectory(args);
        } catch (RuntimeException invalidArgument) {
            return INVALID_PROTOCOL_EXIT;
        }

        DocumentParserProtocolCodec codec = new DocumentParserProtocolCodec();
        DocumentParserWorkerRequest request;
        try {
            request = codec.readRequest(operationDirectory.resolve(DocumentParserProtocolCodec.REQUEST_FILE));
            validateRequest(request, operationDirectory);
        } catch (Exception invalidRequest) {
            return INVALID_PROTOCOL_EXIT;
        }

        ParentProcessMonitor.start(
                request.parentProcessId(),
                () -> Runtime.getRuntime().halt(PARENT_GONE_EXIT)
        );

        DocumentParserWorkerResponse response = convert(request);
        Path responsePath = operationDirectory.resolve(DocumentParserProtocolCodec.RESPONSE_FILE);
        try {
            codec.writeResponse(responsePath, response, request.parserLimits().maxWorkerOutputBytes());
        } catch (DocumentParserProtocolCodec.OutputLimitExceededException outputLimit) {
            try {
                codec.writeResponse(
                        responsePath,
                        DocumentParserWorkerResponse.failure(DocumentParserWorkerError.OUTPUT_LIMIT),
                        request.parserLimits().maxWorkerOutputBytes()
                );
            } catch (Exception responseFailure) {
                return INVALID_PROTOCOL_EXIT;
            }
        } catch (Exception responseFailure) {
            return INVALID_PROTOCOL_EXIT;
        }
        return 0;
    }

    private static DocumentParserWorkerResponse convert(DocumentParserWorkerRequest request) {
        try {
            DocumentProcessingLimits limits = request.parserLimits().toProcessingLimits();
            DocumentParserFormat format = DocumentParserFormat.resolve(
                    request.contentType(), request.originalFilename());
            if (format == DocumentParserFormat.PDF) {
                try (BundledPdfFontMapper fontMapper = new BundledPdfFontMapper()) {
                    FontMappers.set(fontMapper);
                    return DocumentParserWorkerResponse.success(convertDocument(request, limits));
                }
            }
            return DocumentParserWorkerResponse.success(convertDocument(request, limits));
        } catch (DocumentResourceLimitException resourceLimit) {
            return DocumentParserWorkerResponse.failure(
                    DocumentParserWorkerError.fromResourceLimit(resourceLimit.getMessage()));
        } catch (DocumentTypeMismatchException typeMismatch) {
            return DocumentParserWorkerResponse.failure(DocumentParserWorkerError.TYPE_MISMATCH);
        } catch (Exception conversionFailure) {
            return DocumentParserWorkerResponse.failure(DocumentParserWorkerError.PROCESSING_FAILED);
        }
    }

    private static ConvertedDocument convertDocument(
            DocumentParserWorkerRequest request,
            DocumentProcessingLimits limits
    ) throws Exception {
        List<DocumentConverter> converters = List.of(
                new PdfDocumentConverter(limits),
                new EpubDocumentConverter(limits),
                new TextDocumentConverter(limits)
        );
        DocumentConverter converter = new DocumentConverterFactory(converters)
                .findConverter(request.contentType(), request.originalFilename());
        try (InputStream input = Files.newInputStream(Path.of(request.sourcePath()))) {
            return converter.convert(input, request.originalFilename(), request.sizeBytes());
        }
    }

    private static Path operationDirectory(String[] args) {
        if (args == null) {
            throw new IllegalArgumentException("Parser worker operation is required");
        }
        Path operationDirectory = null;
        for (String argument : args) {
            if (argument != null && argument.startsWith(WORKER_ARGUMENT)) {
                if (operationDirectory != null) {
                    throw new IllegalArgumentException("Only one parser worker operation is allowed");
                }
                operationDirectory = Path.of(argument.substring(WORKER_ARGUMENT.length()))
                        .toAbsolutePath()
                        .normalize();
            }
        }
        if (operationDirectory == null
                || !Files.isDirectory(operationDirectory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Parser worker operation is invalid");
        }
        return operationDirectory;
    }

    private static void validateRequest(
            DocumentParserWorkerRequest request,
            Path operationDirectory
    ) throws Exception {
        if (request == null
                || request.protocolVersion() != DocumentParserProtocolCodec.PROTOCOL_VERSION
                || request.parentProcessId() <= 0
                || request.parserLimits() == null) {
            throw new IllegalArgumentException("Parser worker protocol is incompatible");
        }
        if (request.originalFilename() == null
                || request.originalFilename().isBlank()
                || request.originalFilename().length() > 1_024
                || containsControlCharacter(request.originalFilename())
                || request.contentType() == null
                || request.contentType().isBlank()
                || request.contentType().length() > 255
                || containsControlCharacter(request.contentType())
                || DocumentParserFormat.resolve(request.contentType(), request.originalFilename()) == null
                || request.sizeBytes() <= 0) {
            throw new IllegalArgumentException("Parser worker input metadata is invalid");
        }

        Path source = Path.of(request.sourcePath()).toAbsolutePath().normalize();
        Path sourceRoot = Path.of(request.sourceStorageRoot()).toAbsolutePath().normalize();
        Path expectedSource = operationDirectory.resolve(DocumentParserProtocolCodec.INPUT_FILE);
        if (!sourceRoot.equals(operationDirectory) || !source.equals(expectedSource)) {
            throw new IllegalArgumentException("Parser worker source is invalid");
        }
        Path realSourceRoot = sourceRoot.toRealPath();
        Path realSource = source.toRealPath();
        if (!realSource.startsWith(realSourceRoot)
                || Files.isSymbolicLink(source)
                || !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)
                || Files.size(source) != request.sizeBytes()) {
            throw new IllegalArgumentException("Parser worker source is invalid");
        }

        DocumentParserWorkerRequest.ParserLimits limits = request.parserLimits();
        Path workerRoot = Path.of(limits.workerStorageRoot()).toAbsolutePath().normalize();
        if (!workerRoot.equals(operationDirectory)
                || limits.maxExtractedCharacters() <= 0
                || limits.maxPdfPages() <= 0
                || limits.maxPdfMainMemoryBytes() < 4_096
                || limits.maxPdfStorageBytes() < limits.maxPdfMainMemoryBytes()
                || limits.pdfScratchRetentionMillis() <= 0
                || limits.maxEpubEntries() <= 0
                || limits.maxEpubUncompressedBytes() <= 0
                || limits.maxEpubCompressionRatio() <= 0
                || limits.maxWorkerOutputBytes() < limits.maxExtractedCharacters()) {
            throw new IllegalArgumentException("Parser worker limits are invalid");
        }
    }

    private static boolean containsControlCharacter(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                return true;
            }
        }
        return false;
    }
}
