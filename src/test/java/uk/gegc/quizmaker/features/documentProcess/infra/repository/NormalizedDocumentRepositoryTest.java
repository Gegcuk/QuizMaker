package uk.gegc.quizmaker.features.documentProcess.infra.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import uk.gegc.quizmaker.features.documentProcess.domain.model.NormalizedDocument;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test-mysql")
class NormalizedDocumentRepositoryTest {

    @Autowired
    private NormalizedDocumentRepository normalizedDocumentRepository;

    @Test
    void save_andLoad_roundTrip() {
        // Given
        NormalizedDocument normalizedDocument = new NormalizedDocument();
        normalizedDocument.setOriginalName("test.txt");
        normalizedDocument.setMime("text/plain");
        normalizedDocument.setSource(NormalizedDocument.DocumentSource.TEXT);
        normalizedDocument.setLanguage("en");
        normalizedDocument.setNormalizedText("This is a test normalizedDocument with some content.");
        normalizedDocument.setCharCount(42);
        normalizedDocument.setStatus(NormalizedDocument.DocumentStatus.NORMALIZED);

        // When
        NormalizedDocument saved = normalizedDocumentRepository.save(normalizedDocument);
        NormalizedDocument loaded = normalizedDocumentRepository.findById(saved.getId()).orElse(null);

        // Then
        assertThat(loaded).isNotNull();
        assertThat(loaded.getId()).isEqualTo(saved.getId());
        assertThat(loaded.getOriginalName()).isEqualTo("test.txt");
        assertThat(loaded.getMime()).isEqualTo("text/plain");
        assertThat(loaded.getSource()).isEqualTo(NormalizedDocument.DocumentSource.TEXT);
        assertThat(loaded.getLanguage()).isEqualTo("en");
        assertThat(loaded.getNormalizedText()).isEqualTo("This is a test normalizedDocument with some content.");
        assertThat(loaded.getCharCount()).isEqualTo(42);
        assertThat(loaded.getStatus()).isEqualTo(NormalizedDocument.DocumentStatus.NORMALIZED);
        assertThat(loaded.getCreatedAt()).isNotNull();
        assertThat(loaded.getUpdatedAt()).isNotNull();
    }

    @Test
    void save_largeText_handled() {
        // Given
        StringBuilder largeText = new StringBuilder();
        for (int i = 0; i < 10000; i++) {
            largeText.append("Line ").append(i).append(": This is a test line with some content.\n");
        }
        String normalizedText = largeText.toString();

        NormalizedDocument normalizedDocument = new NormalizedDocument();
        normalizedDocument.setOriginalName("large.txt");
        normalizedDocument.setMime("text/plain");
        normalizedDocument.setSource(NormalizedDocument.DocumentSource.UPLOAD);
        normalizedDocument.setNormalizedText(normalizedText);
        normalizedDocument.setCharCount(normalizedText.length());
        normalizedDocument.setStatus(NormalizedDocument.DocumentStatus.NORMALIZED);

        // When
        NormalizedDocument saved = normalizedDocumentRepository.save(normalizedDocument);
        NormalizedDocument loaded = normalizedDocumentRepository.findById(saved.getId()).orElse(null);

        // Then
        assertThat(loaded).isNotNull();
        assertThat(loaded.getNormalizedText()).isEqualTo(normalizedText);
        assertThat(loaded.getCharCount()).isEqualTo(normalizedText.length());
    }

    @Test
    void timestamps_prePersist_and_preUpdate() throws InterruptedException {
        // Given
        NormalizedDocument normalizedDocument = new NormalizedDocument();
        normalizedDocument.setOriginalName("test.txt");
        normalizedDocument.setMime("text/plain");
        normalizedDocument.setSource(NormalizedDocument.DocumentSource.TEXT);
        normalizedDocument.setStatus(NormalizedDocument.DocumentStatus.NORMALIZED);

        // When - First save
        NormalizedDocument saved = normalizedDocumentRepository.save(normalizedDocument);
        Instant createdAt = saved.getCreatedAt();
        Instant updatedAt = saved.getUpdatedAt();

        // Then - Check initial timestamps
        assertThat(createdAt).isNotNull();
        assertThat(updatedAt).isNotNull();
        assertThat(createdAt).isEqualTo(updatedAt);

        // When - Update the normalizedDocument
        Thread.sleep(100); // Ensure different timestamp
        saved.setStatus(NormalizedDocument.DocumentStatus.FAILED);
        NormalizedDocument updated = normalizedDocumentRepository.save(saved);
        Instant newUpdatedAt = updated.getUpdatedAt();

        // Then - Check updated timestamps
        assertThat(updated.getCreatedAt()).isEqualTo(createdAt);
        assertThat(newUpdatedAt).isAfterOrEqualTo(updatedAt);
        assertThat(updated.getCreatedAt()).isBeforeOrEqualTo(newUpdatedAt);
    }

    @Test
    void save_failedDocument_persistsCorrectly() {
        // Given
        NormalizedDocument normalizedDocument = new NormalizedDocument();
        normalizedDocument.setOriginalName("failed.pdf");
        normalizedDocument.setMime("application/pdf");
        normalizedDocument.setSource(NormalizedDocument.DocumentSource.UPLOAD);
        normalizedDocument.setStatus(NormalizedDocument.DocumentStatus.FAILED);
        // Note: normalizedText and charCount are null for failed documents

        // When
        NormalizedDocument saved = normalizedDocumentRepository.save(normalizedDocument);
        NormalizedDocument loaded = normalizedDocumentRepository.findById(saved.getId()).orElse(null);

        // Then
        assertThat(loaded).isNotNull();
        assertThat(loaded.getOriginalName()).isEqualTo("failed.pdf");
        assertThat(loaded.getMime()).isEqualTo("application/pdf");
        assertThat(loaded.getSource()).isEqualTo(NormalizedDocument.DocumentSource.UPLOAD);
        assertThat(loaded.getStatus()).isEqualTo(NormalizedDocument.DocumentStatus.FAILED);
        assertThat(loaded.getNormalizedText()).isNull();
        assertThat(loaded.getCharCount()).isNull();
        assertThat(loaded.getLanguage()).isNull();
    }

    @Test
    void save_multipleDocuments_isolated() throws InterruptedException {
        // Given
        NormalizedDocument doc1 = new NormalizedDocument();
        doc1.setOriginalName("doc1.txt");
        doc1.setMime("text/plain");
        doc1.setSource(NormalizedDocument.DocumentSource.TEXT);
        doc1.setStatus(NormalizedDocument.DocumentStatus.NORMALIZED);

        NormalizedDocument doc2 = new NormalizedDocument();
        doc2.setOriginalName("doc2.txt");
        doc2.setMime("text/plain");
        doc2.setSource(NormalizedDocument.DocumentSource.TEXT);
        doc2.setStatus(NormalizedDocument.DocumentStatus.NORMALIZED);

        // When
        NormalizedDocument saved1 = normalizedDocumentRepository.save(doc1);
        Thread.sleep(100); // Ensure different timestamp
        NormalizedDocument saved2 = normalizedDocumentRepository.save(doc2);

        // Then
        assertThat(saved1.getId()).isNotEqualTo(saved2.getId());
        assertThat(saved1.getCreatedAt()).isNotEqualTo(saved2.getCreatedAt());
        
        NormalizedDocument loaded1 = normalizedDocumentRepository.findById(saved1.getId()).orElse(null);
        NormalizedDocument loaded2 = normalizedDocumentRepository.findById(saved2.getId()).orElse(null);
        
        assertThat(loaded1).isNotNull();
        assertThat(loaded2).isNotNull();
        assertThat(loaded1.getOriginalName()).isEqualTo("doc1.txt");
        assertThat(loaded2.getOriginalName()).isEqualTo("doc2.txt");
    }
}
