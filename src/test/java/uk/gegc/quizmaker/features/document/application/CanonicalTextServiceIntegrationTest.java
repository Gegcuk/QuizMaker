package uk.gegc.quizmaker.features.document.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.features.document.domain.model.Document;
import uk.gegc.quizmaker.features.document.domain.repository.DocumentRepository;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test-mysql")
@Transactional
class CanonicalTextServiceIntegrationTest {

    @Autowired
    private CanonicalTextService canonicalTextService;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private UserRepository userRepository;

    @TempDir
    static Path tempBase; // must be static for DynamicPropertySource

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("quizmaker.canonical.base-dir", () -> tempBase.resolve("qm-canon").toString());
    }

    @TempDir
    Path tempDir;

    private Document testDocument;
    private User testUser;
    private UUID documentId;

    @BeforeEach
    void setUp() throws IOException {
        // Create test user
        testUser = new User();
        testUser.setUsername("testuser");
        testUser.setEmail("test@example.com");
        testUser.setHashedPassword("password");
        testUser.setActive(true);
        testUser = userRepository.save(testUser);

        // Create test document
        testDocument = new Document();
        testDocument.setOriginalFilename("integration-test-document.txt");
        testDocument.setContentType("text/plain");
        testDocument.setFileSize(2048L);
        testDocument.setFilePath(tempDir.resolve("integration-test-document.txt").toString());
        testDocument.setStatus(Document.DocumentStatus.PROCESSED);
        testDocument.setUploadedAt(LocalDateTime.now());
        testDocument.setProcessedAt(LocalDateTime.now());
        testDocument.setUploadedBy(testUser);
        testDocument.setTitle("Integration Test Document");
        testDocument.setAuthor("Test Author");
        testDocument.setTotalPages(3);
        testDocument = documentRepository.save(testDocument);
        documentId = testDocument.getId();

        // Create test file with known content
        String testContent = """
                Chapter 1: Introduction
                
                This is the first chapter of our test document. It contains an introduction to the topic.
                
                Chapter 2: Main Content
                
                This chapter contains the main content of the document. It has multiple paragraphs with different topics.
                
                First paragraph discusses the primary concepts.
                
                Second paragraph provides additional details and examples.
                
                Chapter 3: Conclusion
                
                This is the final chapter that summarizes the key points.
                """;
        Files.writeString(tempDir.resolve("integration-test-document.txt"), testContent, StandardCharsets.UTF_8);

    }

    @Test
    @DisplayName("Integration: should build and load canonical text with proper offsets")
    void integration_shouldBuildAndLoadCanonicalText() {
        // When
        CanonicalTextService.CanonicalizedText result = canonicalTextService.loadOrBuild(documentId);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getText()).isNotEmpty();
        assertThat(result.getSourceVersionHash()).isNotNull();
        assertThat(result.getPageOffsets()).isNotEmpty();
        assertThat(result.getParagraphOffsets()).isNotEmpty();

        // Verify text contains expected content
        assertThat(result.getText()).contains("Chapter 1: Introduction");
        assertThat(result.getText()).contains("Chapter 2: Main Content");
        assertThat(result.getText()).contains("Chapter 3: Conclusion");

        // Verify page offsets are valid
        for (CanonicalTextService.OffsetRange pageOffset : result.getPageOffsets()) {
            assertThat(pageOffset.getStartOffset()).isGreaterThanOrEqualTo(0);
            assertThat(pageOffset.getEndOffset()).isLessThanOrEqualTo(result.getText().length());
            assertThat(pageOffset.getEndOffset()).isGreaterThan(pageOffset.getStartOffset());
            assertThat(pageOffset.getTitle()).isNotEmpty();
        }

        // Verify paragraph offsets are valid
        for (CanonicalTextService.OffsetRange paragraphOffset : result.getParagraphOffsets()) {
            assertThat(paragraphOffset.getStartOffset()).isGreaterThanOrEqualTo(0);
            assertThat(paragraphOffset.getEndOffset()).isLessThanOrEqualTo(result.getText().length());
            assertThat(paragraphOffset.getEndOffset()).isGreaterThan(paragraphOffset.getStartOffset());
            assertThat(paragraphOffset.getTitle()).isNotEmpty();
        }
    }

    @Test
    @DisplayName("Integration: should generate consistent hash for same content")
    void integration_shouldGenerateConsistentHash() {
        // When - build canonical text twice
        CanonicalTextService.CanonicalizedText result1 = canonicalTextService.loadOrBuild(documentId);
        CanonicalTextService.CanonicalizedText result2 = canonicalTextService.loadOrBuild(documentId);

        // Then - should be identical
        assertThat(result1.getSourceVersionHash()).isEqualTo(result2.getSourceVersionHash());
        assertThat(result1.getText()).isEqualTo(result2.getText());
        assertThat(result1.getPageOffsets()).hasSameSizeAs(result2.getPageOffsets());
        assertThat(result1.getParagraphOffsets()).hasSameSizeAs(result2.getParagraphOffsets());
    }

    @Test
    @DisplayName("Integration: should handle round-trip offsets correctly")
    void integration_shouldHandleRoundTripOffsets() {
        // When
        CanonicalTextService.CanonicalizedText result = canonicalTextService.loadOrBuild(documentId);

        // Then - verify that text slices match their offsets
        for (CanonicalTextService.OffsetRange pageOffset : result.getPageOffsets()) {
            String pageText = result.getText().substring(pageOffset.getStartOffset(), pageOffset.getEndOffset());
            assertThat(pageText).isNotEmpty();
            assertThat(pageText.length()).isEqualTo(pageOffset.getEndOffset() - pageOffset.getStartOffset());
        }

        for (CanonicalTextService.OffsetRange paragraphOffset : result.getParagraphOffsets()) {
            String paragraphText = result.getText().substring(paragraphOffset.getStartOffset(), paragraphOffset.getEndOffset());
            assertThat(paragraphText).isNotEmpty();
            assertThat(paragraphText.length()).isEqualTo(paragraphOffset.getEndOffset() - paragraphOffset.getStartOffset());
        }
    }

    @Test
    @DisplayName("Integration: should handle non-overlapping offsets")
    void integration_shouldHandleNonOverlappingOffsets() {
        // When
        CanonicalTextService.CanonicalizedText result = canonicalTextService.loadOrBuild(documentId);

        // Then - verify page offsets don't overlap
        for (int i = 0; i < result.getPageOffsets().size() - 1; i++) {
            CanonicalTextService.OffsetRange current = result.getPageOffsets().get(i);
            CanonicalTextService.OffsetRange next = result.getPageOffsets().get(i + 1);
            assertThat(current.getEndOffset()).isLessThanOrEqualTo(next.getStartOffset());
        }

        // Verify paragraph offsets don't overlap
        for (int i = 0; i < result.getParagraphOffsets().size() - 1; i++) {
            CanonicalTextService.OffsetRange current = result.getParagraphOffsets().get(i);
            CanonicalTextService.OffsetRange next = result.getParagraphOffsets().get(i + 1);
            assertThat(current.getEndOffset()).isLessThanOrEqualTo(next.getStartOffset());
        }
    }

    @Test
    @DisplayName("Integration: should handle filesystem persistence correctly")
    void integration_shouldHandleFilesystemPersistence() {
        // When - build canonical text
        CanonicalTextService.CanonicalizedText result1 = canonicalTextService.loadOrBuild(documentId);

        // Then - verify files were created
        Path canonicalTextPath = tempBase.resolve("qm-canon").resolve(documentId + ".txt");
        Path metadataPath      = tempBase.resolve("qm-canon").resolve(documentId + ".meta");

        assertThat(Files.exists(canonicalTextPath)).isTrue();
        assertThat(Files.exists(metadataPath)).isTrue();

        // Verify content matches
        try {
            String savedText = Files.readString(canonicalTextPath, StandardCharsets.UTF_8);
            assertThat(savedText).isEqualTo(result1.getText());
        } catch (IOException e) {
            throw new RuntimeException("Failed to read saved canonical text", e);
        }
    }

    @Test
    @DisplayName("Integration: should handle multiple documents independently")
    void integration_shouldHandleMultipleDocumentsIndependently() throws IOException {
        // Create second document
        Document testDocument2 = new Document();
        testDocument2.setOriginalFilename("integration-test-document-2.txt");
        testDocument2.setContentType("text/plain");
        testDocument2.setFileSize(1024L);
        testDocument2.setFilePath(tempDir.resolve("integration-test-document-2.txt").toString());
        testDocument2.setStatus(Document.DocumentStatus.PROCESSED);
        testDocument2.setUploadedAt(LocalDateTime.now());
        testDocument2.setProcessedAt(LocalDateTime.now());
        testDocument2.setUploadedBy(testUser);
        testDocument2.setTitle("Second Test Document");
        testDocument2.setAuthor("Test Author 2");
        testDocument2.setTotalPages(1);
        testDocument2 = documentRepository.save(testDocument2);
        UUID documentId2 = testDocument2.getId();

        // Create different test content
        Files.writeString(tempDir.resolve("integration-test-document-2.txt"),
                "This is a completely different document with unique content.",
                StandardCharsets.UTF_8);

        // When
        CanonicalTextService.CanonicalizedText result1 = canonicalTextService.loadOrBuild(documentId);
        CanonicalTextService.CanonicalizedText result2 = canonicalTextService.loadOrBuild(documentId2);

        // Then
        assertThat(result1.getText()).isNotEqualTo(result2.getText());
        assertThat(result1.getSourceVersionHash()).isNotEqualTo(result2.getSourceVersionHash());
        assertThat(result1.getText()).contains("Chapter 1: Introduction");
        assertThat(result2.getText()).contains("completely different document");
    }

    @Test
    @DisplayName("Integration: should handle document with no page information")
    void integration_shouldHandleDocumentWithNoPageInformation() throws IOException {
        // Update document to have no page information
        testDocument.setTotalPages(null);
        testDocument = documentRepository.save(testDocument);

        // When
        CanonicalTextService.CanonicalizedText result = canonicalTextService.loadOrBuild(documentId);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getPageOffsets()).hasSize(1); // Should default to single page
        assertThat(result.getPageOffsets().get(0).getTitle()).isEqualTo("Page 1");
        assertThat(result.getPageOffsets().get(0).getStartOffset()).isEqualTo(0);
        assertThat(result.getPageOffsets().get(0).getEndOffset()).isEqualTo(result.getText().length());
    }

    @Test
    @DisplayName("Integration: should handle document with empty content")
    void integration_shouldHandleDocumentWithEmptyContent() throws IOException {
        Files.writeString(tempDir.resolve("integration-test-document.txt"), "", StandardCharsets.UTF_8);

        CanonicalTextService.CanonicalizedText result = canonicalTextService.loadOrBuild(documentId);

        assertThat(result).isNotNull();
        assertThat(result.getText()).isEmpty();
        assertThat(result.getSourceVersionHash()).isNotNull();
        assertThat(result.getPageOffsets()).hasSize(1);
        assertThat(result.getParagraphOffsets()).isEmpty();
    }
}
