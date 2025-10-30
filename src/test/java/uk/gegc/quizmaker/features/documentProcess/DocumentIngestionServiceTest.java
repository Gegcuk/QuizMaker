package uk.gegc.quizmaker.features.documentProcess;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gegc.quizmaker.features.conversion.application.DocumentConversionService;
import uk.gegc.quizmaker.features.conversion.application.MimeTypeDetector;
import uk.gegc.quizmaker.features.conversion.domain.ConversionException;
import uk.gegc.quizmaker.features.conversion.domain.ConversionFailedException;
import uk.gegc.quizmaker.features.conversion.domain.ConversionResult;
import uk.gegc.quizmaker.features.conversion.domain.UnsupportedFormatException;
import uk.gegc.quizmaker.features.documentProcess.application.DocumentIngestionService;
import uk.gegc.quizmaker.features.documentProcess.application.LinkFetchService;
import uk.gegc.quizmaker.features.documentProcess.domain.model.NormalizedDocument;
import uk.gegc.quizmaker.features.documentProcess.infra.repository.NormalizedDocumentRepository;
import uk.gegc.quizmaker.features.documentProcess.application.NormalizationResult;
import uk.gegc.quizmaker.features.documentProcess.application.NormalizationService;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DocumentIngestionServiceTest {

    @Mock
    private DocumentConversionService conversionService;
    
    @Mock
    private NormalizationService normalizationService;
    
    @Mock
    private NormalizedDocumentRepository documentRepository;
    
    @Mock
    private MimeTypeDetector mimeTypeDetector;

    @Mock
    private LinkFetchService linkFetchService;

    private DocumentIngestionService service;

    @BeforeEach
    void setUp() {
        service = new DocumentIngestionService(conversionService, normalizationService, documentRepository, mimeTypeDetector, linkFetchService);
    }

    @Test
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
        NormalizedDocument result = service.ingestFromText(originalName, language, text);
        
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
    }

    @Test
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
        NormalizedDocument result = service.ingestFromText(originalName, language, text);
        
        // Then
        assertThat(result.getStatus()).isEqualTo(NormalizedDocument.DocumentStatus.FAILED);
        
        ArgumentCaptor<NormalizedDocument> documentCaptor = ArgumentCaptor.forClass(NormalizedDocument.class);
        verify(documentRepository).save(documentCaptor.capture());
        
        NormalizedDocument captured = documentCaptor.getValue();
        assertThat(captured.getStatus()).isEqualTo(NormalizedDocument.DocumentStatus.FAILED);
    }

    @Test
    void ingestFromFile_success_setsMimeAndStatusNormalized() throws ConversionException {
        // Given
        String originalName = "test.pdf";
        byte[] fileBytes = "PDF content".getBytes();
        
        ConversionResult conversionResult = new ConversionResult("Converted text");
        NormalizationResult normalizationResult = new NormalizationResult("Normalized text", 15);
        NormalizedDocument savedDocument = createTestDocument();
        
        when(mimeTypeDetector.detectMimeType(originalName)).thenReturn("application/pdf");
        when(conversionService.convert(originalName, fileBytes)).thenReturn(conversionResult);
        when(normalizationService.normalize("Converted text")).thenReturn(normalizationResult);
        when(documentRepository.save(any(NormalizedDocument.class))).thenReturn(savedDocument);
        
        // When
        NormalizedDocument result = service.ingestFromFile(originalName, fileBytes);
        
        // Then
        assertThat(result).isEqualTo(savedDocument);
        
        ArgumentCaptor<NormalizedDocument> documentCaptor = ArgumentCaptor.forClass(NormalizedDocument.class);
        verify(documentRepository).save(documentCaptor.capture());
        
        NormalizedDocument captured = documentCaptor.getValue();
        assertThat(captured.getOriginalName()).isEqualTo(originalName);
        assertThat(captured.getMime()).isEqualTo("application/pdf");
        assertThat(captured.getNormalizedText()).isEqualTo("Normalized text");
        assertThat(captured.getCharCount()).isEqualTo(15);
        assertThat(captured.getStatus()).isEqualTo(NormalizedDocument.DocumentStatus.NORMALIZED);
        assertThat(captured.getSource()).isEqualTo(NormalizedDocument.DocumentSource.UPLOAD);
    }

    @Test
    void ingestFromFile_unsupportedFormat_persistsFailed_thenRethrowsUnsupportedFormat() throws ConversionException {
        // Given
        String originalName = "test.xyz";
        byte[] fileBytes = "content".getBytes();
        
        when(mimeTypeDetector.detectMimeType(originalName)).thenReturn("application/octet-stream");
        when(conversionService.convert(originalName, fileBytes))
                .thenThrow(new UnsupportedFormatException("Unsupported format"));
        
        NormalizedDocument savedDocument = createTestDocument();
        savedDocument.setStatus(NormalizedDocument.DocumentStatus.FAILED);
        when(documentRepository.save(any(NormalizedDocument.class))).thenReturn(savedDocument);
        
        // When & Then
        assertThatThrownBy(() -> service.ingestFromFile(originalName, fileBytes))
                .isInstanceOf(UnsupportedFormatException.class)
                .hasMessageContaining("Unsupported format");
        
        // Verify document was saved with FAILED status before exception was thrown
        ArgumentCaptor<NormalizedDocument> documentCaptor = ArgumentCaptor.forClass(NormalizedDocument.class);
        verify(documentRepository).save(documentCaptor.capture());
        
        NormalizedDocument captured = documentCaptor.getValue();
        assertThat(captured.getStatus()).isEqualTo(NormalizedDocument.DocumentStatus.FAILED);
        assertThat(captured.getMime()).isEqualTo("application/octet-stream");
    }

    @Test
    void ingestFromFile_conversionFails_persistsFailed_thenThrowsConversionFailedException() throws ConversionException {
        // Given
        String originalName = "test.pdf";
        byte[] fileBytes = "PDF content".getBytes();
        
        when(mimeTypeDetector.detectMimeType(originalName)).thenReturn("application/pdf");
        when(conversionService.convert(originalName, fileBytes))
                .thenThrow(new ConversionException("Conversion failed"));
        
        NormalizedDocument savedDocument = createTestDocument();
        savedDocument.setStatus(NormalizedDocument.DocumentStatus.FAILED);
        when(documentRepository.save(any(NormalizedDocument.class))).thenReturn(savedDocument);
        
        // When & Then
        assertThatThrownBy(() -> service.ingestFromFile(originalName, fileBytes))
                .isInstanceOf(ConversionFailedException.class)
                .hasMessageContaining("Document conversion failed");
        
        // Verify document was saved with FAILED status
        ArgumentCaptor<NormalizedDocument> documentCaptor = ArgumentCaptor.forClass(NormalizedDocument.class);
        verify(documentRepository).save(documentCaptor.capture());
        
        NormalizedDocument captured = documentCaptor.getValue();
        assertThat(captured.getStatus()).isEqualTo(NormalizedDocument.DocumentStatus.FAILED);
    }

    @Test
    void ingestFromFile_genericException_persistsFailed_thenThrowsConversionFailedException() throws ConversionException {
        // Given
        String originalName = "test.pdf";
        byte[] fileBytes = "PDF content".getBytes();
        
        when(mimeTypeDetector.detectMimeType(originalName)).thenReturn("application/pdf");
        when(conversionService.convert(originalName, fileBytes))
                .thenThrow(new RuntimeException("Unexpected error"));
        
        NormalizedDocument savedDocument = createTestDocument();
        savedDocument.setStatus(NormalizedDocument.DocumentStatus.FAILED);
        when(documentRepository.save(any(NormalizedDocument.class))).thenReturn(savedDocument);
        
        // When & Then
        assertThatThrownBy(() -> service.ingestFromFile(originalName, fileBytes))
                .isInstanceOf(ConversionFailedException.class)
                .hasMessageContaining("Document ingestion failed");
        
        // Verify document was saved with FAILED status
        ArgumentCaptor<NormalizedDocument> documentCaptor = ArgumentCaptor.forClass(NormalizedDocument.class);
        verify(documentRepository).save(documentCaptor.capture());
        
        NormalizedDocument captured = documentCaptor.getValue();
        assertThat(captured.getStatus()).isEqualTo(NormalizedDocument.DocumentStatus.FAILED);
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
