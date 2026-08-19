package uk.gegc.quizmaker.features.documentProcess.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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

    @Qualifier("documentProcessNormalizationService")
    private final NormalizationService normalizationService;
    private final NormalizedDocumentRepository documentRepository;

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

}
