package uk.gegc.quizmaker.features.documentProcess.infra.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import uk.gegc.quizmaker.features.documentProcess.domain.model.NormalizedDocument;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test-mysql")
class NormalizedDocumentRepositoryTest {

    @Autowired
    private NormalizedDocumentRepository repository;

    @Test
    void findCharCountById_found_returnsCharCount() {
        // Given
        NormalizedDocument document = new NormalizedDocument();
        document.setOriginalName("test.txt");
        document.setMime("text/plain");
        document.setSource(NormalizedDocument.DocumentSource.TEXT);
        document.setLanguage("en");
        document.setNormalizedText("Test content");
        document.setCharCount(12);
        document.setStatus(NormalizedDocument.DocumentStatus.NORMALIZED);
        document.setCreatedAt(Instant.now());
        document.setUpdatedAt(Instant.now());
        
        NormalizedDocument saved = repository.save(document);
        
        // When
        Integer result = repository.findCharCountById(saved.getId());
        
        // Then
        assertNotNull(result);
        assertEquals(12, result);
    }

    @Test
    void findCharCountById_notFound_returnsNull() {
        // Given
        UUID nonExistentId = UUID.randomUUID();
        
        // When
        Integer result = repository.findCharCountById(nonExistentId);
        
        // Then
        assertNull(result);
    }

    @Test
    void findCharCountById_withNullCharCount_returnsNull() {
        // Given
        NormalizedDocument document = new NormalizedDocument();
        document.setOriginalName("test.txt");
        document.setMime("text/plain");
        document.setSource(NormalizedDocument.DocumentSource.TEXT);
        document.setLanguage("en");
        document.setNormalizedText("Test content");
        document.setCharCount(null); // Explicitly null
        document.setStatus(NormalizedDocument.DocumentStatus.NORMALIZED);
        document.setCreatedAt(Instant.now());
        document.setUpdatedAt(Instant.now());
        
        NormalizedDocument saved = repository.save(document);
        
        // When
        Integer result = repository.findCharCountById(saved.getId());
        
        // Then
        assertNull(result);
    }

    @Test
    void findCharCountById_largeCharCount_returnsCorrectValue() {
        // Given
        NormalizedDocument document = new NormalizedDocument();
        document.setOriginalName("large.txt");
        document.setMime("text/plain");
        document.setSource(NormalizedDocument.DocumentSource.TEXT);
        document.setLanguage("en");
        document.setNormalizedText("A".repeat(10000)); // Large text
        document.setCharCount(10000);
        document.setStatus(NormalizedDocument.DocumentStatus.NORMALIZED);
        document.setCreatedAt(Instant.now());
        document.setUpdatedAt(Instant.now());
        
        NormalizedDocument saved = repository.save(document);
        
        // When
        Integer result = repository.findCharCountById(saved.getId());
        
        // Then
        assertNotNull(result);
        assertEquals(10000, result);
    }
}
