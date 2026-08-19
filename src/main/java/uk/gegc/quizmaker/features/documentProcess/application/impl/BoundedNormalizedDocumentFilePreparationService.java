package uk.gegc.quizmaker.features.documentProcess.application.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import uk.gegc.quizmaker.features.document.application.ConvertedDocument;
import uk.gegc.quizmaker.features.document.application.DocumentIngestionMetrics;
import uk.gegc.quizmaker.features.document.application.DocumentParseExecutor;
import uk.gegc.quizmaker.features.document.application.DocumentParseRequest;
import uk.gegc.quizmaker.features.document.application.DocumentProcessingLimits;
import uk.gegc.quizmaker.features.document.application.DocumentUploadStagingService;
import uk.gegc.quizmaker.features.document.application.StagedDocumentUpload;
import uk.gegc.quizmaker.features.documentProcess.application.NormalizationResult;
import uk.gegc.quizmaker.features.documentProcess.application.NormalizationService;
import uk.gegc.quizmaker.features.documentProcess.application.NormalizedDocumentFilePreparationService;
import uk.gegc.quizmaker.features.documentProcess.domain.NormalizationFailedException;
import uk.gegc.quizmaker.features.documentProcess.domain.model.NormalizedDocument;
import uk.gegc.quizmaker.shared.exception.DocumentResourceLimitException;

import java.time.Duration;

@Service
@RequiredArgsConstructor
@Slf4j
public class BoundedNormalizedDocumentFilePreparationService implements NormalizedDocumentFilePreparationService {

    private final DocumentUploadStagingService stagingService;
    private final DocumentParseExecutor parseExecutor;
    private final NormalizationService normalizationService;
    private final DocumentProcessingLimits limits;
    private final DocumentIngestionMetrics metrics;

    @Override
    @Transactional(propagation = Propagation.NEVER)
    public NormalizedDocument prepare(String admissionOwnerKey, String originalName, MultipartFile file) {
        StagedDocumentUpload upload = stagingService.stage(file, originalName);
        long startedAt = System.nanoTime();
        try {
            ConvertedDocument converted = parseExecutor.execute(
                    admissionOwnerKey,
                    DocumentParseRequest.from(upload));
            String extractedText = converted == null ? null : converted.getFullContent();
            requireBoundedText(extractedText, "Document conversion produced no extractable text");
            NormalizationResult normalized = normalize(extractedText);
            NormalizedDocument document = new NormalizedDocument();
            document.setOriginalName(upload.originalFilename());
            document.setMime(upload.detectedContentType());
            document.setSource(NormalizedDocument.DocumentSource.UPLOAD);
            document.setNormalizedText(normalized.text());
            document.setCharCount(normalized.charCount());
            document.setStatus(NormalizedDocument.DocumentStatus.NORMALIZED);
            metrics.recordExtracted(DocumentIngestionMetrics.Format.fromContentType(upload.detectedContentType()),
                    extractedText.length(), converted.getTotalPages());
            recordConversion(DocumentIngestionMetrics.Outcome.SUCCEEDED, DocumentIngestionMetrics.Reason.NONE, startedAt);
            return document;
        } catch (RuntimeException failure) {
            DocumentIngestionMetrics.Reason reason = DocumentIngestionMetrics.Reason.from(failure);
            recordConversion(reason.isRejectedRequest()
                    ? DocumentIngestionMetrics.Outcome.REJECTED
                    : DocumentIngestionMetrics.Outcome.FAILED, reason, startedAt);
            throw failure;
        } finally {
            try {
                stagingService.discard(upload.stagingPath());
            } catch (RuntimeException cleanupFailure) {
                log.warn("Normalized-document staging cleanup could not be completed");
            }
        }
    }

    private NormalizationResult normalize(String extractedText) {
        try {
            NormalizationResult normalized = normalizationService.normalize(extractedText);
            if (normalized == null || normalized.text() == null || normalized.text().isBlank()
                    || normalized.charCount() != normalized.text().length()) {
                throw new NormalizationFailedException("Document normalization failed");
            }
            requireBoundedText(normalized.text(), "Normalized document text exceeds the configured limit");
            return normalized;
        } catch (DocumentResourceLimitException | NormalizationFailedException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new NormalizationFailedException("Document normalization failed", failure);
        }
    }

    private void requireBoundedText(String text, String message) {
        if (text == null || text.isBlank()) {
            throw new NormalizationFailedException("Document normalization failed");
        }
        if (text.length() > limits.getMaxExtractedCharacters()) {
            throw new DocumentResourceLimitException(message);
        }
    }

    private void recordConversion(DocumentIngestionMetrics.Outcome outcome,
                                  DocumentIngestionMetrics.Reason reason, long startedAt) {
        metrics.recordEvent(DocumentIngestionMetrics.Stage.CONVERSION, outcome, reason);
        metrics.recordDuration(DocumentIngestionMetrics.Stage.CONVERSION, outcome,
                Duration.ofNanos(Math.max(0, System.nanoTime() - startedAt)));
    }
}
