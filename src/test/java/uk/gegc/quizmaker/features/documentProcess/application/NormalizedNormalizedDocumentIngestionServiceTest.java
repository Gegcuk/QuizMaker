package uk.gegc.quizmaker.features.documentProcess.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gegc.quizmaker.features.conversion.application.DocumentConversionService;
import uk.gegc.quizmaker.features.conversion.application.MimeTypeDetector;
import uk.gegc.quizmaker.features.conversion.domain.ConversionException;
import uk.gegc.quizmaker.features.conversion.domain.ConversionFailedException;
import uk.gegc.quizmaker.features.conversion.domain.ConversionResult;
import uk.gegc.quizmaker.features.documentProcess.application.DocumentIngestionService;
import uk.gegc.quizmaker.features.documentProcess.application.NormalizationResult;
import uk.gegc.quizmaker.features.documentProcess.application.NormalizationService;
import uk.gegc.quizmaker.features.documentProcess.domain.model.NormalizedDocument;
import uk.gegc.quizmaker.features.documentProcess.infra.repository.NormalizedDocumentRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NormalizedNormalizedDocumentIngestionServiceTest {

    @Mock
    private DocumentConversionService conversionService;

    @Mock
    private NormalizationService normalizationService;

    @Mock
    private NormalizedDocumentRepository normalizedDocumentRepository;
    
    @Mock
    private MimeTypeDetector mimeTypeDetector;

    @Mock
    private LinkFetchService linkFetchService;

    private DocumentIngestionService ingestionService;

    @BeforeEach
    void setUp() {
        ingestionService = new DocumentIngestionService(conversionService, normalizationService, normalizedDocumentRepository, mimeTypeDetector, linkFetchService);
    }

    @Test
    void ingestFromText_success_setsAllFields() {
        // Given
        String originalName = "test.txt";
        String language = "en";
        String rawText = "Hello, World!";
        String normalizedText = "Hello, World!";
        int charCount = 13;
        
        when(normalizationService.normalize(rawText))
                .thenReturn(new NormalizationResult(normalizedText, charCount));
        when(normalizedDocumentRepository.save(any(NormalizedDocument.class)))
                .thenAnswer(invocation -> {
                    NormalizedDocument doc = invocation.getArgument(0);
                    doc.setId(java.util.UUID.randomUUID());
                    return doc;
                });

        // When
        NormalizedDocument result = ingestionService.ingestFromText(originalName, language, rawText);

        // Then
        assertThat(result.getOriginalName()).isEqualTo(originalName);
        assertThat(result.getMime()).isEqualTo("text/plain");
        assertThat(result.getSource()).isEqualTo(NormalizedDocument.DocumentSource.TEXT);
        assertThat(result.getLanguage()).isEqualTo(language);
        assertThat(result.getNormalizedText()).isEqualTo(normalizedText);
        assertThat(result.getCharCount()).isEqualTo(charCount);
        assertThat(result.getStatus()).isEqualTo(NormalizedDocument.DocumentStatus.NORMALIZED);
        assertThat(result.getId()).isNotNull();
    }

    @Test
    void ingestFromText_normalizationThrows_createsFailedRecord() {
        // Given
        String originalName = "test.txt";
        String language = "en";
        String rawText = "Hello, World!";
        
        when(normalizationService.normalize(rawText))
                .thenThrow(new RuntimeException("Normalization failed"));
        when(normalizedDocumentRepository.save(any(NormalizedDocument.class)))
                .thenAnswer(invocation -> {
                    NormalizedDocument doc = invocation.getArgument(0);
                    doc.setId(java.util.UUID.randomUUID());
                    return doc;
                });

        // When
        NormalizedDocument result = ingestionService.ingestFromText(originalName, language, rawText);

        // Then
        assertThat(result.getOriginalName()).isEqualTo(originalName);
        assertThat(result.getMime()).isEqualTo("text/plain");
        assertThat(result.getSource()).isEqualTo(NormalizedDocument.DocumentSource.TEXT);
        assertThat(result.getLanguage()).isEqualTo(language);
        assertThat(result.getNormalizedText()).isNull();
        assertThat(result.getCharCount()).isNull();
        assertThat(result.getStatus()).isEqualTo(NormalizedDocument.DocumentStatus.FAILED);
        assertThat(result.getId()).isNotNull();
    }

    @Test
    void ingestFromFile_success_throughConverter() throws Exception {
        // Given
        String originalName = "document.txt";
        byte[] bytes = "Hello, World!".getBytes();
        String convertedText = "Hello, World!";
        String normalizedText = "Hello, World!";
        int charCount = 13;
        
        when(mimeTypeDetector.detectMimeType(originalName)).thenReturn("text/plain");
        when(conversionService.convert(originalName, bytes))
                .thenReturn(new ConversionResult(convertedText));
        when(normalizationService.normalize(convertedText))
                .thenReturn(new NormalizationResult(normalizedText, charCount));
        when(normalizedDocumentRepository.save(any(NormalizedDocument.class)))
                .thenAnswer(invocation -> {
                    NormalizedDocument doc = invocation.getArgument(0);
                    doc.setId(java.util.UUID.randomUUID());
                    return doc;
                });

        // When
        NormalizedDocument result = ingestionService.ingestFromFile(originalName, bytes);

        // Then
        assertThat(result.getOriginalName()).isEqualTo(originalName);
        assertThat(result.getMime()).isEqualTo("text/plain");
        assertThat(result.getSource()).isEqualTo(NormalizedDocument.DocumentSource.UPLOAD);
        assertThat(result.getLanguage()).isNull();
        assertThat(result.getNormalizedText()).isEqualTo(normalizedText);
        assertThat(result.getCharCount()).isEqualTo(charCount);
        assertThat(result.getStatus()).isEqualTo(NormalizedDocument.DocumentStatus.NORMALIZED);
        assertThat(result.getId()).isNotNull();
    }

    @Test
    void ingestFromFile_conversionFails_throwsConversionFailedException() throws Exception {
        // Given
        String originalName = "document.pdf";
        byte[] bytes = "invalid content".getBytes();
        
        when(mimeTypeDetector.detectMimeType(originalName)).thenReturn("application/pdf");
        when(conversionService.convert(originalName, bytes))
                .thenThrow(new ConversionException("Conversion failed"));
        when(normalizedDocumentRepository.save(any(NormalizedDocument.class)))
                .thenAnswer(invocation -> {
                    NormalizedDocument doc = invocation.getArgument(0);
                    doc.setId(java.util.UUID.randomUUID());
                    return doc;
                });

        // When & Then
        assertThatThrownBy(() -> ingestionService.ingestFromFile(originalName, bytes))
                .isInstanceOf(ConversionFailedException.class)
                .hasMessageContaining("Document conversion failed: Conversion failed");
    }

    @Test
    void ingestFromFile_genericException_throwsConversionFailedException() throws Exception {
        // Given
        String originalName = "document.txt";
        byte[] bytes = "test".getBytes();
        
        when(mimeTypeDetector.detectMimeType(originalName)).thenReturn("text/plain");
        when(conversionService.convert(originalName, bytes))
                .thenThrow(new RuntimeException("Unexpected error"));
        when(normalizedDocumentRepository.save(any(NormalizedDocument.class)))
                .thenAnswer(invocation -> {
                    NormalizedDocument doc = invocation.getArgument(0);
                    doc.setId(java.util.UUID.randomUUID());
                    return doc;
                });

        // When & Then
        assertThatThrownBy(() -> ingestionService.ingestFromFile(originalName, bytes))
                .isInstanceOf(ConversionFailedException.class)
                .hasMessageContaining("Document ingestion failed: Unexpected error");
    }

    @Test
    void ingestFromFile_unknownExtension_throwsConversionFailedException() throws Exception {
        // Given
        String originalName = "document.unknown";
        byte[] bytes = "test".getBytes();
        
        when(mimeTypeDetector.detectMimeType(originalName)).thenReturn("application/octet-stream");
        when(conversionService.convert(originalName, bytes))
                .thenThrow(new ConversionException("Conversion failed"));
        when(normalizedDocumentRepository.save(any(NormalizedDocument.class)))
                .thenAnswer(invocation -> {
                    NormalizedDocument doc = invocation.getArgument(0);
                    doc.setId(java.util.UUID.randomUUID());
                    return doc;
                });

        // When & Then
        assertThatThrownBy(() -> ingestionService.ingestFromFile(originalName, bytes))
                .isInstanceOf(ConversionFailedException.class)
                .hasMessageContaining("Document conversion failed: Conversion failed");
    }

    @Test
    void ingestFromFile_nullFilename_throwsConversionFailedException() throws Exception {
        // Given
        String originalName = null;
        byte[] bytes = "test".getBytes();
        
        when(mimeTypeDetector.detectMimeType(originalName)).thenReturn("application/octet-stream");
        when(conversionService.convert(originalName, bytes))
                .thenThrow(new ConversionException("Conversion failed"));
        when(normalizedDocumentRepository.save(any(NormalizedDocument.class)))
                .thenAnswer(invocation -> {
                    NormalizedDocument doc = invocation.getArgument(0);
                    doc.setId(java.util.UUID.randomUUID());
                    return doc;
                });

        // When & Then
        assertThatThrownBy(() -> ingestionService.ingestFromFile(originalName, bytes))
                .isInstanceOf(ConversionFailedException.class)
                .hasMessageContaining("Document conversion failed: Conversion failed");
    }
}
