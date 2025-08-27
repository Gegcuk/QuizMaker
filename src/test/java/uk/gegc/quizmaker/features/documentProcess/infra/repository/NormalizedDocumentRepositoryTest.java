package uk.gegc.quizmaker.features.documentProcess.infra.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import uk.gegc.quizmaker.features.documentProcess.domain.model.NormalizedDocument;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test-mysql")
@DisplayName("NormalizedDocumentRepository Tests")
class NormalizedDocumentRepositoryTest {

    @Autowired
    private NormalizedDocumentRepository repository;

    private NormalizedDocument testDocument;

    @BeforeEach
    void setUp() {
        testDocument = new NormalizedDocument();
        testDocument.setOriginalName("test-document.txt");
        testDocument.setMime("text/plain");
        testDocument.setSource(NormalizedDocument.DocumentSource.TEXT);
        testDocument.setLanguage("en");
        testDocument.setNormalizedText("This is a test document with moderate content. " +
                "It contains multiple sentences and should be sufficient to test the LONGTEXT field. " +
                "The content includes various characters and formatting to ensure proper persistence. " +
                "We want to make sure that the database can handle documents with substantial text content " +
                "without any issues related to field size or encoding.");
        testDocument.setCharCount(testDocument.getNormalizedText().length());
        testDocument.setStatus(NormalizedDocument.DocumentStatus.NORMALIZED);
    }

    @Test
    @DisplayName("saveAndFind_roundTrip_allCoreFieldsPersist - with a moderate LONGTEXT")
    void saveAndFind_roundTrip_allCoreFieldsPersist() {
        // Given
        String originalText = testDocument.getNormalizedText();
        int originalCharCount = testDocument.getCharCount();

        // When
        NormalizedDocument saved = repository.save(testDocument);
        UUID documentId = saved.getId();
        
        Optional<NormalizedDocument> found = repository.findById(documentId);

        // Then
        assertThat(found).isPresent();
        NormalizedDocument retrieved = found.get();
        
        // Verify all core fields persist correctly
        assertThat(retrieved.getId()).isEqualTo(documentId);
        assertThat(retrieved.getOriginalName()).isEqualTo("test-document.txt");
        assertThat(retrieved.getMime()).isEqualTo("text/plain");
        assertThat(retrieved.getSource()).isEqualTo(NormalizedDocument.DocumentSource.TEXT);
        assertThat(retrieved.getLanguage()).isEqualTo("en");
        assertThat(retrieved.getNormalizedText()).isEqualTo(originalText);
        assertThat(retrieved.getCharCount()).isEqualTo(originalCharCount);
        assertThat(retrieved.getStatus()).isEqualTo(NormalizedDocument.DocumentStatus.NORMALIZED);
        assertThat(retrieved.getCreatedAt()).isNotNull();
        assertThat(retrieved.getUpdatedAt()).isNotNull();
        
        // Verify the text content is exactly preserved (LONGTEXT field)
        assertThat(retrieved.getNormalizedText().length()).isEqualTo(originalText.length());
        assertThat(retrieved.getNormalizedText()).isEqualTo(originalText);
    }

    @Test
    @DisplayName("findCharCountById_returnsCharCount - Projection query returns correct char count")
    void findCharCountById_returnsCharCount() {
        // Given
        NormalizedDocument saved = repository.save(testDocument);
        UUID documentId = saved.getId();

        // When
        Integer charCount = repository.findCharCountById(documentId);

        // Then
        assertThat(charCount).isNotNull();
        assertThat(charCount).isEqualTo(testDocument.getCharCount());
        assertThat(charCount).isEqualTo(testDocument.getNormalizedText().length());
    }

    @Test
    @DisplayName("findCharCountById_unknown_returnsNull - Unknown document ID returns null")
    void findCharCountById_unknown_returnsNull() {
        // Given
        UUID unknownId = UUID.randomUUID();

        // When
        Integer charCount = repository.findCharCountById(unknownId);

        // Then
        assertThat(charCount).isNull();
    }

    @Test
    @DisplayName("saveAndFind_documentWithNullFields_handlesCorrectly")
    void saveAndFind_documentWithNullFields_handlesCorrectly() {
        // Given
        NormalizedDocument documentWithNulls = new NormalizedDocument();
        documentWithNulls.setOriginalName(null);
        documentWithNulls.setMime(null);
        documentWithNulls.setSource(NormalizedDocument.DocumentSource.TEXT);
        documentWithNulls.setLanguage(null);
        documentWithNulls.setNormalizedText("Simple text");
        documentWithNulls.setCharCount(11);
        documentWithNulls.setStatus(NormalizedDocument.DocumentStatus.NORMALIZED);

        // When
        NormalizedDocument saved = repository.save(documentWithNulls);
        Optional<NormalizedDocument> found = repository.findById(saved.getId());

        // Then
        assertThat(found).isPresent();
        NormalizedDocument retrieved = found.get();
        assertThat(retrieved.getOriginalName()).isNull();
        assertThat(retrieved.getMime()).isNull();
        assertThat(retrieved.getLanguage()).isNull();
        assertThat(retrieved.getNormalizedText()).isEqualTo("Simple text");
        assertThat(retrieved.getCharCount()).isEqualTo(11);
    }

    @Test
    @DisplayName("saveAndFind_documentWithEmptyText_handlesCorrectly")
    void saveAndFind_documentWithEmptyText_handlesCorrectly() {
        // Given
        NormalizedDocument emptyTextDocument = new NormalizedDocument();
        emptyTextDocument.setOriginalName("empty.txt");
        emptyTextDocument.setMime("text/plain");
        emptyTextDocument.setSource(NormalizedDocument.DocumentSource.TEXT);
        emptyTextDocument.setLanguage("en");
        emptyTextDocument.setNormalizedText("");
        emptyTextDocument.setCharCount(0);
        emptyTextDocument.setStatus(NormalizedDocument.DocumentStatus.NORMALIZED);

        // When
        NormalizedDocument saved = repository.save(emptyTextDocument);
        Optional<NormalizedDocument> found = repository.findById(saved.getId());

        // Then
        assertThat(found).isPresent();
        NormalizedDocument retrieved = found.get();
        assertThat(retrieved.getNormalizedText()).isEqualTo("");
        assertThat(retrieved.getCharCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("saveAndFind_documentWithSpecialCharacters_handlesCorrectly")
    void saveAndFind_documentWithSpecialCharacters_handlesCorrectly() {
        // Given
        String specialText = "Special chars: éñüß€£¥©®™\n\r\t\u0000";
        NormalizedDocument specialCharDocument = new NormalizedDocument();
        specialCharDocument.setOriginalName("special.txt");
        specialCharDocument.setMime("text/plain");
        specialCharDocument.setSource(NormalizedDocument.DocumentSource.TEXT);
        specialCharDocument.setLanguage("en");
        specialCharDocument.setNormalizedText(specialText);
        specialCharDocument.setCharCount(specialText.length());
        specialCharDocument.setStatus(NormalizedDocument.DocumentStatus.NORMALIZED);

        // When
        NormalizedDocument saved = repository.save(specialCharDocument);
        Optional<NormalizedDocument> found = repository.findById(saved.getId());

        // Then
        assertThat(found).isPresent();
        NormalizedDocument retrieved = found.get();
        assertThat(retrieved.getNormalizedText()).isEqualTo(specialText);
        assertThat(retrieved.getCharCount()).isEqualTo(specialText.length());
    }
}
