package uk.gegc.quizmaker.features.documentProcess;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.features.documentProcess.application.DocumentIngestionService;
import uk.gegc.quizmaker.features.documentProcess.domain.model.NormalizedDocument;
import uk.gegc.quizmaker.features.documentProcess.infra.repository.NormalizedDocumentRepository;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test-mysql")
@Transactional
class IngestionFlowIT {

    @Autowired
    private DocumentIngestionService ingestionService;

    @Autowired
    private NormalizedDocumentRepository normalizedDocumentRepository;

    @Test
    void ingestFromText_completeFlow() {
        // Given
        String originalName = "test-document.txt";
        String language = "en";
        String rawText = "This is a test document with some content.\n" +
                        "It has multiple lines and some formatting.";

        // When
        NormalizedDocument result = ingestionService.ingestFromText(originalName, language, rawText);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getId()).isNotNull();
        assertThat(result.getOriginalName()).isEqualTo(originalName);
        assertThat(result.getMime()).isEqualTo("text/plain");
        assertThat(result.getSource()).isEqualTo(NormalizedDocument.DocumentSource.TEXT);
        assertThat(result.getLanguage()).isEqualTo(language);
        assertThat(result.getStatus()).isEqualTo(NormalizedDocument.DocumentStatus.NORMALIZED);
        assertThat(result.getNormalizedText()).isNotNull();
        assertThat(result.getCharCount()).isPositive();
        assertThat(result.getCreatedAt()).isNotNull();
        assertThat(result.getUpdatedAt()).isNotNull();

        // Verify persistence
        NormalizedDocument persisted = normalizedDocumentRepository.findById(result.getId()).orElse(null);
        assertThat(persisted).isNotNull();
        assertThat(persisted.getNormalizedText()).isEqualTo(result.getNormalizedText());
        assertThat(persisted.getCharCount()).isEqualTo(result.getCharCount());
    }

    @Test
    void ingestFromFile_completeFlow() throws Exception {
        // Given
        String originalName = "test-file.txt";
        String content = "This is a test file content.\n" +
                        "It should be converted and normalized.";
        byte[] bytes = content.getBytes();

        // When
        NormalizedDocument result = ingestionService.ingestFromFile(originalName, bytes);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getId()).isNotNull();
        assertThat(result.getOriginalName()).isEqualTo(originalName);
        assertThat(result.getMime()).isEqualTo("text/plain");
        assertThat(result.getSource()).isEqualTo(NormalizedDocument.DocumentSource.UPLOAD);
        assertThat(result.getLanguage()).isNull();
        assertThat(result.getStatus()).isEqualTo(NormalizedDocument.DocumentStatus.NORMALIZED);
        assertThat(result.getNormalizedText()).isNotNull();
        assertThat(result.getCharCount()).isPositive();
        assertThat(result.getCreatedAt()).isNotNull();
        assertThat(result.getUpdatedAt()).isNotNull();

        // Verify the text was properly converted and normalized
        assertThat(result.getNormalizedText()).contains("This is a test file content");
        assertThat(result.getNormalizedText()).contains("It should be converted and normalized");

        // Verify persistence
        NormalizedDocument persisted = normalizedDocumentRepository.findById(result.getId()).orElse(null);
        assertThat(persisted).isNotNull();
        assertThat(persisted.getNormalizedText()).isEqualTo(result.getNormalizedText());
    }

    @Test
    void ingestFromFile_unsupportedFormat_createsFailedRecord() throws Exception {
        // Given
        String originalName = "unsupported.pdf";
        byte[] bytes = "This is not a real PDF".getBytes();

        // When
        NormalizedDocument result = ingestionService.ingestFromFile(originalName, bytes);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getId()).isNotNull();
        assertThat(result.getOriginalName()).isEqualTo(originalName);
        assertThat(result.getMime()).isEqualTo("application/pdf");
        assertThat(result.getSource()).isEqualTo(NormalizedDocument.DocumentSource.UPLOAD);
        assertThat(result.getStatus()).isEqualTo(NormalizedDocument.DocumentStatus.FAILED);
        assertThat(result.getNormalizedText()).isNull();
        assertThat(result.getCharCount()).isNull();
        assertThat(result.getCreatedAt()).isNotNull();
        assertThat(result.getUpdatedAt()).isNotNull();

        // Verify persistence
        NormalizedDocument persisted = normalizedDocumentRepository.findById(result.getId()).orElse(null);
        assertThat(persisted).isNotNull();
        assertThat(persisted.getStatus()).isEqualTo(NormalizedDocument.DocumentStatus.FAILED);
    }

    @Test
    void multipleIngestions_isolated() throws InterruptedException {
        // Given
        String text1 = "First document content";
        String text2 = "Second document content";

        // When
        NormalizedDocument doc1 = ingestionService.ingestFromText("doc1.txt", "en", text1);
        // Add a small delay to ensure different timestamps
        Thread.sleep(10);
        NormalizedDocument doc2 = ingestionService.ingestFromText("doc2.txt", "en", text2);

        // Then
        assertThat(doc1.getId()).isNotEqualTo(doc2.getId());
        assertThat(doc1.getNormalizedText()).isNotEqualTo(doc2.getNormalizedText());
        assertThat(doc1.getCreatedAt()).isNotEqualTo(doc2.getCreatedAt());

        // Verify both are persisted
        NormalizedDocument persisted1 = normalizedDocumentRepository.findById(doc1.getId()).orElse(null);
        NormalizedDocument persisted2 = normalizedDocumentRepository.findById(doc2.getId()).orElse(null);
        assertThat(persisted1).isNotNull();
        assertThat(persisted2).isNotNull();
        assertThat(persisted1.getOriginalName()).isEqualTo("doc1.txt");
        assertThat(persisted2.getOriginalName()).isEqualTo("doc2.txt");
    }
}
