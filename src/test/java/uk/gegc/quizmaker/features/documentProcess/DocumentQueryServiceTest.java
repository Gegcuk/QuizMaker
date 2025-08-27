package uk.gegc.quizmaker.features.documentProcess;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gegc.quizmaker.features.documentProcess.application.DocumentQueryService;
import uk.gegc.quizmaker.features.documentProcess.domain.ValidationErrorException;
import uk.gegc.quizmaker.features.documentProcess.domain.model.NormalizedDocument;
import uk.gegc.quizmaker.features.documentProcess.infra.repository.NormalizedDocumentRepository;
import uk.gegc.quizmaker.shared.exception.ResourceNotFoundException;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentQueryServiceTest {

    @Mock
    private NormalizedDocumentRepository documentRepository;

    private DocumentQueryService service;

    @BeforeEach
    void setUp() {
        service = new DocumentQueryService(documentRepository);
    }

    @Test
    void getTextSlice_negativeStart_throwsValidationErrorException() {
        // Given
        UUID documentId = UUID.randomUUID();
        NormalizedDocument document = createTestDocument("Test content", 12);
        
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        
        // When & Then
        assertThatThrownBy(() -> service.getTextSlice(documentId, -1, 10))
                .isInstanceOf(ValidationErrorException.class)
                .hasMessageContaining("Start offset cannot be negative");
    }

    @Test
    void getTextSlice_endBeforeStart_throwsValidationErrorException() {
        // Given
        UUID documentId = UUID.randomUUID();
        NormalizedDocument document = createTestDocument("Test content", 12);
        
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        
        // When & Then
        assertThatThrownBy(() -> service.getTextSlice(documentId, 10, 5))
                .isInstanceOf(ValidationErrorException.class)
                .hasMessageContaining("End offset must be greater than or equal to start");
    }

    @Test
    void getTextSlice_startBeyondLength_throwsValidationErrorException() {
        // Given
        UUID documentId = UUID.randomUUID();
        NormalizedDocument document = createTestDocument("Test content", 12);
        
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        
        // When & Then
        assertThatThrownBy(() -> service.getTextSlice(documentId, 20, 25))
                .isInstanceOf(ValidationErrorException.class)
                .hasMessageContaining("Start offset exceeds text length");
    }

    @Test
    void getTextSlice_endBeyondLength_clampsToTextLength() {
        // Given
        UUID documentId = UUID.randomUUID();
        NormalizedDocument document = createTestDocument("Test content", 12);
        
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        
        // When
        String result = service.getTextSlice(documentId, 5, 20);
        
        // Then - Should clamp to text length
        assertThat(result).isEqualTo("content");
    }

    @Test
    void getTextSlice_noNormalizedText_throwsIllegalStateException() {
        // Given
        UUID documentId = UUID.randomUUID();
        NormalizedDocument document = createTestDocument(null, 0);
        
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        
        // When & Then
        assertThatThrownBy(() -> service.getTextSlice(documentId, 0, 10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Document has no normalized text");
    }

    @Test
    void getTextSlice_validRange_returnsCorrectSlice() {
        // Given
        UUID documentId = UUID.randomUUID();
        NormalizedDocument document = createTestDocument("Hello world content", 19);
        
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        
        // When
        String result = service.getTextSlice(documentId, 6, 11);
        
        // Then
        assertThat(result).isEqualTo("world");
    }

    @Test
    void getTextSlice_documentNotFound_throwsResourceNotFoundException() {
        // Given
        UUID documentId = UUID.randomUUID();
        
        when(documentRepository.findById(documentId)).thenReturn(Optional.empty());
        
        // When & Then
        assertThatThrownBy(() -> service.getTextSlice(documentId, 0, 10))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Document not found");
    }

    @Test
    void getTextLength_found_returnsCharCount() {
        // Given
        UUID documentId = UUID.randomUUID();
        Integer charCount = 25;
        
        when(documentRepository.findCharCountById(documentId)).thenReturn(charCount);
        
        // When
        int result = service.getTextLength(documentId);
        
        // Then
        assertThat(result).isEqualTo(25);
    }

    @Test
    void getTextLength_notFound_throwsResourceNotFoundException() {
        // Given
        UUID documentId = UUID.randomUUID();
        
        when(documentRepository.findCharCountById(documentId)).thenReturn(null);
        
        // When & Then
        assertThatThrownBy(() -> service.getTextLength(documentId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Document not found");
    }

    @Test
    void getDocument_found_returnsDocument() {
        // Given
        UUID documentId = UUID.randomUUID();
        NormalizedDocument document = createTestDocument("Test content", 12);
        
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        
        // When
        NormalizedDocument result = service.getDocument(documentId);
        
        // Then
        assertThat(result).isEqualTo(document);
    }

    @Test
    void getDocument_notFound_throwsResourceNotFoundException() {
        // Given
        UUID documentId = UUID.randomUUID();
        
        when(documentRepository.findById(documentId)).thenReturn(Optional.empty());
        
        // When & Then
        assertThatThrownBy(() -> service.getDocument(documentId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Document not found");
    }

    @Test
    void getFullText_found_returnsNormalizedText() {
        // Given
        UUID documentId = UUID.randomUUID();
        NormalizedDocument document = createTestDocument("Full text content", 17);
        
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        
        // When
        String result = service.getFullText(documentId);
        
        // Then
        assertThat(result).isEqualTo("Full text content");
    }

    @Test
    void getFullText_noNormalizedText_throwsIllegalStateException() {
        // Given
        UUID documentId = UUID.randomUUID();
        NormalizedDocument document = createTestDocument(null, 0);
        
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        
        // When & Then
        assertThatThrownBy(() -> service.getFullText(documentId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Document has no normalized text");
    }

    private NormalizedDocument createTestDocument(String normalizedText, int charCount) {
        NormalizedDocument document = new NormalizedDocument();
        document.setId(UUID.randomUUID());
        document.setOriginalName("test.txt");
        document.setMime("text/plain");
        document.setSource(NormalizedDocument.DocumentSource.TEXT);
        document.setLanguage("en");
        document.setNormalizedText(normalizedText);
        document.setCharCount(charCount);
        document.setStatus(NormalizedDocument.DocumentStatus.NORMALIZED);
        document.setCreatedAt(Instant.now());
        document.setUpdatedAt(Instant.now());
        return document;
    }
}
