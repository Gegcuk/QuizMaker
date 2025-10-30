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
    private final LinkFetchService linkFetchService;

    /**
     * Ingests text directly without file conversion.
     * 
     * @param originalName the name to associate with this document
     * @param language the document language (optional)
     * @param text the raw text content
     * @return the persisted Document entity
     */
    @Transactional
    public NormalizedDocument ingestFromText(String originalName, String language, String text) {
        log.info("Ingesting text document: {}", originalName);
        
        try {
            // Normalize the text
            NormalizationResult normalizationResult = normalizationService.normalize(text);
            
            // Create and persist document entity
            NormalizedDocument document = new NormalizedDocument();
            document.setOriginalName(originalName);
            document.setMime("text/plain");
            document.setSource(NormalizedDocument.DocumentSource.TEXT);
            document.setLanguage(language);
            document.setNormalizedText(normalizationResult.text());
            document.setCharCount(normalizationResult.charCount());
            document.setStatus(NormalizedDocument.DocumentStatus.NORMALIZED);

            NormalizedDocument saved = documentRepository.save(document);
            log.info("Successfully ingested text document: {} (id={})", originalName, saved.getId());
            
            return saved;
        } catch (Exception e) {
            log.error("Failed to ingest text document: {}", originalName, e);
            
            // Create failed document record
            NormalizedDocument failedDocument = new NormalizedDocument();
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
    public NormalizedDocument ingestFromFile(String originalName, byte[] bytes) {
        log.info("Ingesting file document: {} ({} bytes)", originalName, bytes.length);

        NormalizedDocument document = new NormalizedDocument();
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
            log.info("Successfully ingested file document: {} (id={})", originalName, saved.getId());
            
            return saved;
        } catch (UnsupportedFormatException e) {
            log.error("Unsupported format for file document: {}", originalName, e);
            document.setMime(mimeTypeDetector.detectMimeType(originalName));
            document.setStatus(NormalizedDocument.DocumentStatus.FAILED);
            documentRepository.save(document);
            // Re-throw to be handled by GlobalExceptionHandler with appropriate HTTP status
            throw e;
        } catch (ConversionException e) {
            log.error("Failed to convert file document: {}", originalName, e);
            document.setMime(mimeTypeDetector.detectMimeType(originalName));
            document.setStatus(NormalizedDocument.DocumentStatus.FAILED);
            documentRepository.save(document);
            // Convert to ConversionFailedException for proper error handling
            throw new ConversionFailedException("Document conversion failed: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("Failed to ingest file document: {}", originalName, e);
            document.setMime(mimeTypeDetector.detectMimeType(originalName));
            document.setStatus(NormalizedDocument.DocumentStatus.FAILED);
            documentRepository.save(document);
            throw new ConversionFailedException("Document ingestion failed: " + e.getMessage(), e);
        }
    }

    /**
     * Ingests a document from a URL by fetching, extracting text, normalizing, and persisting.
     * 
     * @param url the URL to fetch
     * @param language optional language code
     * @return the persisted Document entity
     */
    @Transactional
    public NormalizedDocument ingestFromLink(String url, String language) {
        log.info("Ingesting link document: {}", url);

        NormalizedDocument document = new NormalizedDocument();
        document.setOriginalName(url);
        document.setSource(NormalizedDocument.DocumentSource.LINK);
        document.setLanguage(language);
        document.setStatus(NormalizedDocument.DocumentStatus.PENDING);
        
        try {
            // Fetch and extract text from URL
            ConversionResult conversionResult = linkFetchService.fetchAndExtractText(url);
            
            // Normalize the extracted text
            NormalizationResult normalizationResult = normalizationService.normalize(conversionResult.text());
            
            // Update document with results
            document.setMime("text/html");
            document.setNormalizedText(normalizationResult.text());
            document.setCharCount(normalizationResult.charCount());
            document.setStatus(NormalizedDocument.DocumentStatus.NORMALIZED);

            NormalizedDocument saved = documentRepository.save(document);
            log.info("Successfully ingested link document: {} (id={})", url, saved.getId());
            
            return saved;
        } catch (Exception e) {
            log.error("Failed to ingest link document: {}", url, e);
            document.setMime("text/html");
            document.setStatus(NormalizedDocument.DocumentStatus.FAILED);
            documentRepository.save(document);
            // Re-throw to be handled by GlobalExceptionHandler
            throw e;
        }
    }
}
