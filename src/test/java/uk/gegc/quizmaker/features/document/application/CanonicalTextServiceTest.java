package uk.gegc.quizmaker.features.document.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gegc.quizmaker.features.document.domain.model.Document;
import uk.gegc.quizmaker.features.document.domain.repository.DocumentRepository;
import uk.gegc.quizmaker.shared.exception.DocumentNotFoundException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CanonicalTextServiceTest {

    @TempDir
    Path tempDir;
    @Mock
    private DocumentRepository documentRepository;
    @Mock
    private DocumentConversionService documentConversionService;
    private CanonicalTextService canonicalTextService;
    private Document testDocument;
    private UUID documentId;

    @BeforeEach
    void setUp() throws IOException {
        // Create service with temp directory as base
        canonicalTextService = new CanonicalTextService(
                documentRepository,
                documentConversionService,
                tempDir.resolve("canonical").toString()
        );

        // Create test document
        documentId = UUID.randomUUID();
        testDocument = new Document();
        testDocument.setId(documentId);
        testDocument.setOriginalFilename("test-document.pdf");
        testDocument.setContentType("application/pdf");
        testDocument.setFileSize(1024L);
        testDocument.setFilePath(tempDir.resolve("test-document.pdf").toString());
        testDocument.setStatus(Document.DocumentStatus.PROCESSED);
        testDocument.setUploadedAt(LocalDateTime.now());
        testDocument.setProcessedAt(LocalDateTime.now());
        testDocument.setTitle("Test Document");
        testDocument.setAuthor("Test Author");
        testDocument.setTotalPages(3);

        // Create test file
        String testContent = "Test Document\n\nChapter 1\n\nThis is chapter 1 content.\n\nSection 1.1\n\nThis is section 1.1 content.";
        Files.write(tempDir.resolve("test-document.pdf"), testContent.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("loadOrBuild: should build canonical text when no existing files")
    void loadOrBuild_shouldBuildCanonicalText_whenNoExistingFiles() {
        // Given
        when(documentRepository.findById(documentId))
                .thenReturn(java.util.Optional.of(testDocument));

        ConvertedDocument convertedDocument = new ConvertedDocument();
        convertedDocument.setFullContent("Test Document\n\nChapter 1\n\nThis is chapter 1 content.\n\nSection 1.1\n\nThis is section 1.1 content.");
        convertedDocument.setTotalPages(3);
        when(documentConversionService.convertDocument(any(), anyString(), anyString()))
                .thenReturn(convertedDocument);

        // When
        CanonicalTextService.CanonicalizedText result = canonicalTextService.loadOrBuild(documentId);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getText()).isEqualTo("Test Document\n\nChapter 1\n\nThis is chapter 1 content.\n\nSection 1.1\n\nThis is section 1.1 content.");
        assertThat(result.getSourceVersionHash()).isNotNull();
        assertThat(result.getSourceVersionHash()).hasSize(64); // SHA-256 hash is 64 characters
        assertThat(result.getPageOffsets()).hasSize(3);
        assertThat(result.getParagraphOffsets()).hasSize(5); // title, chapter, content, section, section content
    }

    @Test
    @DisplayName("loadOrBuild: should load existing canonical text when file exists")
    void loadOrBuild_shouldLoadExistingText_whenFileExists() throws IOException {
        // Given
        when(documentRepository.findById(documentId))
                .thenReturn(java.util.Optional.of(testDocument));

        // Create existing canonical text files
        Path canonicalDir = tempDir.resolve("canonical");
        Files.createDirectories(canonicalDir);

        String existingText = "Existing canonical text content.";
        Files.write(canonicalDir.resolve(documentId + ".txt"),
                existingText.getBytes(StandardCharsets.UTF_8));

        // Create proper JSON metadata with 64-char hex hash
        String metadata = """
                {"sourceVersionHash":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef","pageOffsets":[{"start":0,"end":30,"title":"Page 1"}],"paragraphOffsets":[{"start":0,"end":30,"title":"Existing canonical text content."}]}
                """;
        Files.write(canonicalDir.resolve(documentId + ".meta"),
                metadata.getBytes(StandardCharsets.UTF_8));

        // When
        CanonicalTextService.CanonicalizedText result = canonicalTextService.loadOrBuild(documentId);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getText()).isEqualTo(existingText);
        assertThat(result.getSourceVersionHash()).isEqualTo("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
    }

    @Test
    @DisplayName("loadOrBuild: should rebuild when metadata is corrupted")
    void loadOrBuild_shouldRebuild_whenMetadataCorrupted() throws IOException {
        // Given
        when(documentRepository.findById(documentId))
                .thenReturn(java.util.Optional.of(testDocument));

        // Create corrupted canonical text file (invalid JSON metadata)
        Path canonicalDir = tempDir.resolve("canonical");
        Files.createDirectories(canonicalDir);
        Files.write(canonicalDir.resolve(documentId + ".txt"),
                "test content".getBytes(StandardCharsets.UTF_8));
        Files.write(canonicalDir.resolve(documentId + ".meta"),
                "invalid json".getBytes(StandardCharsets.UTF_8));

        ConvertedDocument convertedDocument = new ConvertedDocument();
        convertedDocument.setFullContent("Test Document\n\nChapter 1\n\nThis is chapter 1 content.\n\nSection 1.1\n\nThis is section 1.1 content.");
        convertedDocument.setTotalPages(3);
        when(documentConversionService.convertDocument(any(), anyString(), anyString()))
                .thenReturn(convertedDocument);

        // When
        CanonicalTextService.CanonicalizedText result = canonicalTextService.loadOrBuild(documentId);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getText()).isEqualTo("Test Document\n\nChapter 1\n\nThis is chapter 1 content.\n\nSection 1.1\n\nThis is section 1.1 content.");
        assertThat(result.getSourceVersionHash()).isNotNull();
        assertThat(result.getSourceVersionHash()).hasSize(64);
    }

    @Test
    @DisplayName("loadOrBuild: should throw exception when document not found")
    void loadOrBuild_shouldThrowException_whenDocumentNotFound() {
        // Given
        when(documentRepository.findById(documentId))
                .thenReturn(java.util.Optional.empty());

        // When & Then
        assertThatThrownBy(() -> canonicalTextService.loadOrBuild(documentId))
                .isInstanceOf(DocumentNotFoundException.class)
                .hasMessageContaining("Document not found");
    }

    @Test
    @DisplayName("buildCanonicalText: should handle empty content")
    void buildCanonicalText_shouldHandleEmptyContent() {
        // Given
        when(documentRepository.findById(documentId))
                .thenReturn(java.util.Optional.of(testDocument));

        ConvertedDocument convertedDocument = new ConvertedDocument();
        convertedDocument.setFullContent("");
        convertedDocument.setTotalPages(1);
        when(documentConversionService.convertDocument(any(), anyString(), anyString()))
                .thenReturn(convertedDocument);

        // When
        CanonicalTextService.CanonicalizedText result = canonicalTextService.loadOrBuild(documentId);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getText()).isEmpty();
        assertThat(result.getSourceVersionHash()).isNotNull();
        assertThat(result.getSourceVersionHash()).hasSize(64);
        assertThat(result.getPageOffsets()).hasSize(1);
        assertThat(result.getParagraphOffsets()).isEmpty();
    }

    @Test
    @DisplayName("buildCanonicalText: should handle document with no page information")
    void buildCanonicalText_shouldHandleDocumentWithNoPageInformation() {
        // Given
        when(documentRepository.findById(documentId))
                .thenReturn(java.util.Optional.of(testDocument));

        ConvertedDocument convertedDocument = new ConvertedDocument();
        convertedDocument.setFullContent("Test content");
        convertedDocument.setTotalPages(null);
        when(documentConversionService.convertDocument(any(), anyString(), anyString()))
                .thenReturn(convertedDocument);

        // When
        CanonicalTextService.CanonicalizedText result = canonicalTextService.loadOrBuild(documentId);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getPageOffsets()).hasSize(1);
        assertThat(result.getPageOffsets().get(0).getTitle()).isEqualTo("Page 1");
        assertThat(result.getPageOffsets().get(0).getStartOffset()).isEqualTo(0);
        assertThat(result.getPageOffsets().get(0).getEndOffset()).isEqualTo(result.getText().length());
    }

    @Test
    @DisplayName("buildCanonicalText: should generate consistent hash for same content")
    void buildCanonicalText_shouldGenerateConsistentHash() {
        // Given
        when(documentRepository.findById(documentId))
                .thenReturn(java.util.Optional.of(testDocument));

        ConvertedDocument convertedDocument = new ConvertedDocument();
        convertedDocument.setFullContent("Test Document\n\nChapter 1\n\nThis is chapter 1 content.\n\nSection 1.1\n\nThis is section 1.1 content.");
        convertedDocument.setTotalPages(3);
        when(documentConversionService.convertDocument(any(), anyString(), anyString()))
                .thenReturn(convertedDocument);

        // When - build canonical text twice
        CanonicalTextService.CanonicalizedText result1 = canonicalTextService.loadOrBuild(documentId);
        CanonicalTextService.CanonicalizedText result2 = canonicalTextService.loadOrBuild(documentId);

        // Then - should be identical
        assertThat(result1.getSourceVersionHash()).isEqualTo(result2.getSourceVersionHash());
        assertThat(result1.getText()).isEqualTo(result2.getText());
        assertThat(result1.getSourceVersionHash()).isNotNull();
        assertThat(result1.getSourceVersionHash()).isNotEmpty();
        assertThat(result1.getSourceVersionHash()).hasSize(64); // SHA-256 hash is 64 characters
        // Don't test for specific hash value as it depends on the exact content
    }

    @Test
    @DisplayName("buildCanonicalText: should handle fallback to structured content")
    void buildCanonicalText_shouldHandleFallbackToStructuredContent() {
        // Given
        when(documentRepository.findById(documentId))
                .thenReturn(java.util.Optional.of(testDocument));

        ConvertedDocument convertedDocument = new ConvertedDocument();
        convertedDocument.setFullContent(null); // Force fallback to structured content
        convertedDocument.setTitle("Test Document");

        List<ConvertedDocument.Chapter> chapters = new ArrayList<>();
        ConvertedDocument.Chapter chapter = new ConvertedDocument.Chapter();
        chapter.setTitle("Chapter 1");
        chapter.setContent("This is chapter 1 content.");

        List<ConvertedDocument.Section> sections = new ArrayList<>();
        ConvertedDocument.Section section = new ConvertedDocument.Section();
        section.setTitle("Section 1.1");
        section.setContent("This is section 1.1 content.");
        sections.add(section);
        chapter.setSections(sections);
        chapters.add(chapter);

        convertedDocument.setChapters(chapters);
        convertedDocument.setTotalPages(1);

        when(documentConversionService.convertDocument(any(), anyString(), anyString()))
                .thenReturn(convertedDocument);

        // When
        CanonicalTextService.CanonicalizedText result = canonicalTextService.loadOrBuild(documentId);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getText()).contains("Test Document");
        assertThat(result.getText()).contains("Chapter 1");
        assertThat(result.getText()).contains("Section 1.1");
        assertThat(result.getSourceVersionHash()).isNotNull();
        assertThat(result.getSourceVersionHash()).hasSize(64);
    }

    @Test
    @DisplayName("buildCanonicalText: should handle text normalization")
    void buildCanonicalText_shouldHandleTextNormalization() {
        // Given
        when(documentRepository.findById(documentId))
                .thenReturn(java.util.Optional.of(testDocument));

        ConvertedDocument convertedDocument = new ConvertedDocument();
        // Text with mixed line endings and excessive whitespace
        convertedDocument.setFullContent("Test Document\r\n\r\nChapter 1\r\n\r\n\r\nThis is chapter 1 content.\r\n\r\n\r\n\r\nSection 1.1\r\n\r\nThis is section 1.1 content.");
        convertedDocument.setTotalPages(1);
        when(documentConversionService.convertDocument(any(), anyString(), anyString()))
                .thenReturn(convertedDocument);

        // When
        CanonicalTextService.CanonicalizedText result = canonicalTextService.loadOrBuild(documentId);

        // Then
        assertThat(result).isNotNull();
        // Should normalize line endings and excessive whitespace
        assertThat(result.getText()).isEqualTo("Test Document\n\nChapter 1\n\nThis is chapter 1 content.\n\nSection 1.1\n\nThis is section 1.1 content.");
        assertThat(result.getSourceVersionHash()).isNotNull();
        assertThat(result.getSourceVersionHash()).hasSize(64);
    }

    @Test
    @DisplayName("buildCanonicalText: should handle round-trip offsets correctly")
    void buildCanonicalText_shouldHandleRoundTripOffsets() {
        // Given
        when(documentRepository.findById(documentId))
                .thenReturn(java.util.Optional.of(testDocument));

        ConvertedDocument convertedDocument = new ConvertedDocument();
        convertedDocument.setFullContent("Test Document\n\nChapter 1\n\nThis is chapter 1 content.\n\nSection 1.1\n\nThis is section 1.1 content.");
        convertedDocument.setTotalPages(3);
        when(documentConversionService.convertDocument(any(), anyString(), anyString()))
                .thenReturn(convertedDocument);

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
    @DisplayName("buildCanonicalText: should handle non-overlapping offsets")
    void buildCanonicalText_shouldHandleNonOverlappingOffsets() {
        // Given
        when(documentRepository.findById(documentId))
                .thenReturn(java.util.Optional.of(testDocument));

        ConvertedDocument convertedDocument = new ConvertedDocument();
        convertedDocument.setFullContent("Test Document\n\nChapter 1\n\nThis is chapter 1 content.\n\nSection 1.1\n\nThis is section 1.1 content.");
        convertedDocument.setTotalPages(3);
        when(documentConversionService.convertDocument(any(), anyString(), anyString()))
                .thenReturn(convertedDocument);

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
}
