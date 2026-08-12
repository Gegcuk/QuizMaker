package uk.gegc.quizmaker.features.document.infra.isolation;

import uk.gegc.quizmaker.features.document.application.DocumentParseRequest;
import uk.gegc.quizmaker.features.document.application.DocumentProcessingLimits;

import java.nio.file.Path;
import java.time.Duration;

record DocumentParserWorkerRequest(
        int protocolVersion,
        long parentProcessId,
        String sourcePath,
        String sourceStorageRoot,
        String originalFilename,
        String contentType,
        long sizeBytes,
        ParserLimits parserLimits
) {

    static DocumentParserWorkerRequest create(
            DocumentParseRequest request,
            DocumentProcessingLimits limits,
            Path operationDirectory
    ) {
        return new DocumentParserWorkerRequest(
                DocumentParserProtocolCodec.PROTOCOL_VERSION,
                ProcessHandle.current().pid(),
                request.sourcePath().toString(),
                operationDirectory.toAbsolutePath().normalize().toString(),
                request.originalFilename(),
                request.contentType(),
                request.sizeBytes(),
                ParserLimits.from(limits, operationDirectory)
        );
    }

    record ParserLimits(
            int maxExtractedCharacters,
            int maxPdfPages,
            long maxPdfMainMemoryBytes,
            long maxPdfStorageBytes,
            long pdfScratchRetentionMillis,
            int maxEpubEntries,
            long maxEpubUncompressedBytes,
            int maxEpubCompressionRatio,
            long maxWorkerOutputBytes,
            String workerStorageRoot
    ) {

        static ParserLimits from(DocumentProcessingLimits limits, Path operationDirectory) {
            return new ParserLimits(
                    limits.getMaxExtractedCharacters(),
                    limits.getMaxPdfPages(),
                    limits.getMaxPdfMainMemoryBytes(),
                    limits.getMaxPdfStorageBytes(),
                    limits.getPdfScratchRetention().toMillis(),
                    limits.getMaxEpubEntries(),
                    limits.getMaxEpubUncompressedBytes(),
                    limits.getMaxEpubCompressionRatio(),
                    limits.getParserWorkerMaxOutputBytes(),
                    operationDirectory.toAbsolutePath().normalize().toString()
            );
        }

        DocumentProcessingLimits toProcessingLimits() {
            DocumentProcessingLimits limits = DocumentProcessingLimits.defaults();
            limits.setMaxExtractedCharacters(maxExtractedCharacters);
            limits.setMaxPdfPages(maxPdfPages);
            limits.setMaxPdfMainMemoryBytes(maxPdfMainMemoryBytes);
            limits.setMaxPdfStorageBytes(maxPdfStorageBytes);
            limits.setPdfScratchRetention(Duration.ofMillis(pdfScratchRetentionMillis));
            limits.setMaxEpubEntries(maxEpubEntries);
            limits.setMaxEpubUncompressedBytes(maxEpubUncompressedBytes);
            limits.setMaxEpubCompressionRatio(maxEpubCompressionRatio);
            limits.setStorageRoot(workerStorageRoot);
            return limits;
        }
    }
}
