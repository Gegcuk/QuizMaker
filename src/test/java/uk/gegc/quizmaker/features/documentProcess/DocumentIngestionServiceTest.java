package uk.gegc.quizmaker.features.documentProcess;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gegc.quizmaker.features.documentProcess.application.DocumentIngestionService;
import uk.gegc.quizmaker.features.documentProcess.application.NormalizationResult;
import uk.gegc.quizmaker.features.documentProcess.application.NormalizationService;
import uk.gegc.quizmaker.features.documentProcess.domain.model.NormalizedDocument;
import uk.gegc.quizmaker.features.documentProcess.infra.repository.NormalizedDocumentRepository;
import uk.gegc.quizmaker.features.user.domain.model.User;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Normalized text ingestion")
class DocumentIngestionServiceTest {

    @Mock
    private NormalizationService normalizationService;
    
    @Mock
    private NormalizedDocumentRepository documentRepository;
    
    private DocumentIngestionService service;
    private User owner;

    @BeforeEach
    void setUp() {
        service = new DocumentIngestionService(normalizationService, documentRepository);
        owner = new User();
        owner.setId(UUID.randomUUID());
        owner.setUsername("owner");
        owner.setActive(true);
    }

    @Test
    @DisplayName("Persists normalized text for the authenticated owner")
    void ingestFromText_success_setsNormalizedAndStatusNormalized() {
        // Given
        String text = "Test document content";
        String language = "en";
        String originalName = "test.txt";
        
        NormalizationResult normalizationResult = new NormalizationResult("Normalized text", 18);
        NormalizedDocument savedDocument = createTestDocument();
        
        when(normalizationService.normalize(text)).thenReturn(normalizationResult);
        when(documentRepository.save(any(NormalizedDocument.class))).thenReturn(savedDocument);
        
        // When
        NormalizedDocument result = service.ingestFromText(owner, originalName, language, text);
        
        // Then
        assertThat(result).isEqualTo(savedDocument);
        
        ArgumentCaptor<NormalizedDocument> documentCaptor = ArgumentCaptor.forClass(NormalizedDocument.class);
        verify(documentRepository).save(documentCaptor.capture());
        
        NormalizedDocument captured = documentCaptor.getValue();
        assertThat(captured.getOriginalName()).isEqualTo(originalName);
        assertThat(captured.getLanguage()).isEqualTo(language);
        assertThat(captured.getNormalizedText()).isEqualTo("Normalized text");
        assertThat(captured.getCharCount()).isEqualTo(18);
        assertThat(captured.getStatus()).isEqualTo(NormalizedDocument.DocumentStatus.NORMALIZED);
        assertThat(captured.getSource()).isEqualTo(NormalizedDocument.DocumentSource.TEXT);
        assertThat(captured.getOwner()).isSameAs(owner);
    }

    @Test
    @DisplayName("Persists a failed text record when normalization fails")
    void ingestFromText_normalizationThrows_persistsFailedAndReturnsFailed() {
        // Given
        String text = "Test document content";
        String language = "en";
        String originalName = "test.txt";
        
        when(normalizationService.normalize(text))
                .thenThrow(new RuntimeException("Normalization failed"));
        
        NormalizedDocument savedDocument = createTestDocument();
        savedDocument.setStatus(NormalizedDocument.DocumentStatus.FAILED);
        when(documentRepository.save(any(NormalizedDocument.class))).thenReturn(savedDocument);
        
        // When
        NormalizedDocument result = service.ingestFromText(owner, originalName, language, text);
        
        // Then
        assertThat(result.getStatus()).isEqualTo(NormalizedDocument.DocumentStatus.FAILED);
        
        ArgumentCaptor<NormalizedDocument> documentCaptor = ArgumentCaptor.forClass(NormalizedDocument.class);
        verify(documentRepository).save(documentCaptor.capture());
        
        NormalizedDocument captured = documentCaptor.getValue();
        assertThat(captured.getStatus()).isEqualTo(NormalizedDocument.DocumentStatus.FAILED);
        assertThat(captured.getOwner()).isSameAs(owner);
    }

    private NormalizedDocument createTestDocument() {
        NormalizedDocument document = new NormalizedDocument();
        document.setId(UUID.randomUUID());
        document.setOriginalName("test.txt");
        document.setMime("text/plain");
        document.setSource(NormalizedDocument.DocumentSource.TEXT);
        document.setLanguage("en");
        document.setNormalizedText("Test content");
        document.setCharCount(12);
        document.setStatus(NormalizedDocument.DocumentStatus.NORMALIZED);
        return document;
    }
}
