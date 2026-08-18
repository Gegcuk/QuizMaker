package uk.gegc.quizmaker.features.documentProcess.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.features.conversion.application.DocumentConversionService;
import uk.gegc.quizmaker.features.conversion.application.MimeTypeDetector;
import uk.gegc.quizmaker.features.conversion.domain.ConversionException;
import uk.gegc.quizmaker.features.conversion.domain.ConversionFailedException;
import uk.gegc.quizmaker.features.conversion.domain.ConversionResult;
import uk.gegc.quizmaker.features.conversion.domain.UnsupportedFormatException;
import uk.gegc.quizmaker.features.documentProcess.domain.model.NormalizedDocument;
import uk.gegc.quizmaker.features.documentProcess.infra.repository.NormalizedDocumentRepository;
import uk.gegc.quizmaker.features.user.domain.model.User;

/**
 * Service for ingesting documents - orchestrates conversion, normalization, and persistence.
 * This is the main entry point for document processing.
 */
@Service("documentProcessIngestionService")
@RequiredArgsConstructor
@Slf4j
public class DocumentIngestionService {

    private final DocumentConversionService conversionService;
    @Qualifier("documentProcessNormalizationService")
    private final NormalizationService normalizationService;
    private final NormalizedDocumentRepository documentRepository;
    private final MimeTypeDetector mimeTypeDetector;

    /**
     * Ingests text directly without file conversion.
     * 
     * @param originalName the name to associate with this document
     * @param language the document language (optional)
     * @param text the raw text content
     * @return the persisted Document entity
     */
    @Transactional
    public NormalizedDocument ingestFromText(User owner, String originalName, String language, String text) {
        log.info("Ingesting normalized text document");
        
        try {
            // Normalize the text
            NormalizationResult normalizationResult = normalizationService.normalize(text);
            
            // Create and persist document entity
            NormalizedDocument document = new NormalizedDocument();
            document.setOwner(owner);
            document.setOriginalName(originalName);
            document.setMime("text/plain");
            document.setSource(NormalizedDocument.DocumentSource.TEXT);
            document.setLanguage(language);
            document.setNormalizedText(normalizationResult.text());
            document.setCharCount(normalizationResult.charCount());
            document.setStatus(NormalizedDocument.DocumentStatus.NORMALIZED);

            NormalizedDocument saved = documentRepository.save(document);
            log.info("Successfully ingested normalized text document");
            
            return saved;
        } catch (Exception e) {
            log.error("Failed to ingest normalized text document");
            
            // Create failed document record
            NormalizedDocument failedDocument = new NormalizedDocument();
            failedDocument.setOwner(owner);
            failedDocument.setOriginalName(originalName);
            failedDocument.setMime("text/plain");
            failedDocument.setSource(NormalizedDocument.DocumentSource.TEXT);
            failedDocument.setLanguage(language);
            failedDocument.setStatus(NormalizedDocument.DocumentStatus.FAILED);
            
            return documentRepository.save(failedDocument);
        }
    }

    /**
     * Ingests a file by first converting it to text, then normalizing and persisting.
     * 
     * @param originalName the original filename
     * @param bytes the file bytes
     * @return the persisted Document entity
     */
    @Transactional
    public NormalizedDocument ingestFromFile(User owner, String originalName, byte[] bytes) {
        log.info("Ingesting normalized file document ({} bytes)", bytes.length);

        NormalizedDocument document = new NormalizedDocument();
        document.setOwner(owner);
        document.setOriginalName(originalName);
        document.setSource(NormalizedDocument.DocumentSource.UPLOAD);
        document.setLanguage(null); // Will be detected later or remain null
        document.setStatus(NormalizedDocument.DocumentStatus.PENDING);
        
        try {
            // Convert file to text
            ConversionResult conversionResult = conversionService.convert(originalName, bytes);
            
            // Normalize the extracted text
            NormalizationResult normalizationResult = normalizationService.normalize(conversionResult.text());
            
            // Update document with results
            document.setMime(mimeTypeDetector.detectMimeType(originalName));
            document.setNormalizedText(normalizationResult.text());
            document.setCharCount(normalizationResult.charCount());
            document.setStatus(NormalizedDocument.DocumentStatus.NORMALIZED);

            NormalizedDocument saved = documentRepository.save(document);
            log.info("Successfully ingested normalized file document");
            
            return saved;
        } catch (UnsupportedFormatException e) {
            log.error("Unsupported normalized document format");
            document.setMime(mimeTypeDetector.detectMimeType(originalName));
            document.setStatus(NormalizedDocument.DocumentStatus.FAILED);
            documentRepository.save(document);
            // Re-throw to be handled by GlobalExceptionHandler with appropriate HTTP status
            throw new UnsupportedFormatException("Unsupported document format", e);
        } catch (ConversionException e) {
            log.error("Failed to convert normalized document");
            document.setMime(mimeTypeDetector.detectMimeType(originalName));
            document.setStatus(NormalizedDocument.DocumentStatus.FAILED);
            documentRepository.save(document);
            // Convert to ConversionFailedException for proper error handling
            throw new ConversionFailedException("Document conversion failed", e);
        } catch (Exception e) {
            log.error("Failed to ingest normalized file document");
            document.setMime(mimeTypeDetector.detectMimeType(originalName));
            document.setStatus(NormalizedDocument.DocumentStatus.FAILED);
            documentRepository.save(document);
            throw new ConversionFailedException("Document ingestion failed", e);
        }
    }
}
