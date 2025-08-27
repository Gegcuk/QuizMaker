package uk.gegc.quizmaker.features.documentProcess.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gegc.quizmaker.features.documentProcess.domain.model.NormalizedDocument;
import uk.gegc.quizmaker.features.documentProcess.infra.repository.NormalizedDocumentRepository;
import uk.gegc.quizmaker.shared.exception.ResourceNotFoundException;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentQueryServiceTest {

    @Mock
    private NormalizedDocumentRepository documentRepository;

    @InjectMocks
    private DocumentQueryService queryService;

    private NormalizedDocument document;
    private UUID documentId;

    @BeforeEach
    void setUp() {
        documentId = UUID.randomUUID();
        
        document = new NormalizedDocument();
        document.setId(documentId);
        document.setOriginalName("test-document.txt");
        document.setMime("text/plain");
        document.setSource(NormalizedDocument.DocumentSource.TEXT);
        document.setLanguage("en");
        document.setNormalizedText("This is the normalized text content for testing");
        document.setCharCount(47);
        document.setStatus(NormalizedDocument.DocumentStatus.NORMALIZED);
        document.setCreatedAt(Instant.now().minusSeconds(3600));
        document.setUpdatedAt(Instant.now());
    }

    @Test
    void getDocument_found_returnsEntity() {
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        
        NormalizedDocument result = queryService.getDocument(documentId);
        
        assertEquals(document, result);
    }

    @Test
    void getDocument_notFound_throwsResourceNotFound() {
        when(documentRepository.findById(documentId)).thenReturn(Optional.empty());
        
        ResourceNotFoundException exception = assertThrows(
            ResourceNotFoundException.class,
            () -> queryService.getDocument(documentId)
        );
        
        assertTrue(exception.getMessage().contains(documentId.toString()));
    }

    @Test
    void getTextSlice_happyPath_returnsSubstring() {
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        
        String result = queryService.getTextSlice(documentId, 12, 27);
        
        assertEquals("normalized text", result);
    }

    @Test
    void getTextSlice_endBeyondLength_isClamped() {
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        
        String result = queryService.getTextSlice(documentId, 40, 100);
        
        assertEquals("testing", result); // Should clamp to actual text length
    }

    @Test
    void getTextSlice_startNegative_throwsIllegalArgument() {
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> queryService.getTextSlice(documentId, -1, 10)
        );
        
        assertTrue(exception.getMessage().contains("negative"));
    }

    @Test
    void getTextSlice_endBeforeStart_throwsIllegalArgument() {
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> queryService.getTextSlice(documentId, 20, 10)
        );
        
        assertTrue(exception.getMessage().contains("greater than or equal to start"));
    }

    @Test
    void getTextSlice_startBeyondLength_throwsIllegalArgument() {
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> queryService.getTextSlice(documentId, 100, 110)
        );
        
        assertTrue(exception.getMessage().contains("exceeds text length"));
    }

    @Test
    void getTextSlice_noNormalizedText_throwsIllegalState() {
        document.setNormalizedText(null);
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        
        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> queryService.getTextSlice(documentId, 0, 10)
        );
        
        assertTrue(exception.getMessage().contains("no normalized text"));
    }

    @Test
    void getTextLength_found_returnsCharCount() {
        when(documentRepository.findCharCountById(documentId)).thenReturn(47);
        
        int result = queryService.getTextLength(documentId);
        
        assertEquals(47, result);
    }

    @Test
    void getTextLength_notFound_throwsResourceNotFound() {
        when(documentRepository.findCharCountById(documentId)).thenReturn(null);
        
        ResourceNotFoundException exception = assertThrows(
            ResourceNotFoundException.class,
            () -> queryService.getTextLength(documentId)
        );
        
        assertTrue(exception.getMessage().contains(documentId.toString()));
    }

    @Test
    void getFullText_returnsText() {
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        
        String result = queryService.getFullText(documentId);
        
        assertEquals("This is the normalized text content for testing", result);
    }

    @Test
    void getFullText_noNormalizedText_throwsIllegalState() {
        document.setNormalizedText(null);
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        
        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> queryService.getFullText(documentId)
        );
        
        assertTrue(exception.getMessage().contains("no normalized text"));
    }

    @Test
    void getTextSlice_exactBounds_returnsExactSlice() {
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        
        String result = queryService.getTextSlice(documentId, 0, 47);
        
        assertEquals("This is the normalized text content for testing", result);
    }

    @Test
    void getTextSlice_emptySlice_returnsEmptyString() {
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        
        String result = queryService.getTextSlice(documentId, 10, 10);
        
        assertEquals("", result);
    }
}
