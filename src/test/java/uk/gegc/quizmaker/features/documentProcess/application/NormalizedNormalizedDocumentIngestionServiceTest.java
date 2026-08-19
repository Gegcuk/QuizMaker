package uk.gegc.quizmaker.features.documentProcess.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gegc.quizmaker.features.documentProcess.domain.model.NormalizedDocument;
import uk.gegc.quizmaker.features.documentProcess.infra.repository.NormalizedDocumentRepository;
import uk.gegc.quizmaker.features.user.domain.model.User;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Normalized document ingestion compatibility")
class NormalizedNormalizedDocumentIngestionServiceTest {

    @Mock
    private NormalizationService normalizationService;

    @Mock
    private NormalizedDocumentRepository normalizedDocumentRepository;
    
    private DocumentIngestionService ingestionService;
    private User owner;

    @BeforeEach
    void setUp() {
        ingestionService = new DocumentIngestionService(normalizationService, normalizedDocumentRepository);
        owner = new User();
        owner.setId(UUID.randomUUID());
        owner.setUsername("owner");
        owner.setActive(true);
    }

    @Test
    @DisplayName("Keeps the legacy normalized text result shape")
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
        NormalizedDocument result = ingestionService.ingestFromText(owner, originalName, language, rawText);

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
    @DisplayName("Keeps the legacy failed text record behavior")
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
        NormalizedDocument result = ingestionService.ingestFromText(owner, originalName, language, rawText);

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

}
