package uk.gegc.quizmaker.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gegc.quizmaker.features.document.infra.util.ChunkTitleGenerator;

import static org.junit.jupiter.api.Assertions.*;

@Execution(ExecutionMode.CONCURRENT)
@ExtendWith(MockitoExtension.class)
class ChunkTitleGeneratorTest {

    @InjectMocks
    private ChunkTitleGenerator titleGenerator;

    @Test
    void generateChunkTitle_WithSingleChunk_ReturnsOriginalTitle() {
        // Arrange
        String originalTitle = "Introduction";
        int chunkIndex = 0;
        int totalChunks = 1;
        boolean isMultipleChunks = false;

        // Act
        String result = titleGenerator.generateChunkTitle(originalTitle, chunkIndex, totalChunks, isMultipleChunks);

        // Assert
        assertEquals("Introduction", result);
    }

    @Test
    void generateChunkTitle_WithMultipleChunks_AddsPartNumber() {
        // Arrange
        String originalTitle = "Introduction";
        int chunkIndex = 0;
        int totalChunks = 3;
        boolean isMultipleChunks = true;

        // Act
        String result = titleGenerator.generateChunkTitle(originalTitle, chunkIndex, totalChunks, isMultipleChunks);

        // Assert
        assertEquals("Introduction (Part 1)", result);
    }

    @Test
    void generateChunkTitle_WithSecondChunk_AddsCorrectPartNumber() {
        // Arrange
        String originalTitle = "Introduction";
        int chunkIndex = 1;
        int totalChunks = 3;
        boolean isMultipleChunks = true;

        // Act
        String result = titleGenerator.generateChunkTitle(originalTitle, chunkIndex, totalChunks, isMultipleChunks);

        // Assert
        assertEquals("Introduction (Part 2)", result);
    }

    @Test
    void generateChunkTitle_WithNullTitle_ReturnsDefaultTitle() {
        // Arrange
        String originalTitle = null;
        int chunkIndex = 0;
        int totalChunks = 1;
        boolean isMultipleChunks = false;

        // Act
        String result = titleGenerator.generateChunkTitle(originalTitle, chunkIndex, totalChunks, isMultipleChunks);

        // Assert
        assertEquals("Document", result);
    }

    @Test
    void generateChunkTitle_WithEmptyTitle_ReturnsDefaultTitle() {
        // Arrange
        String originalTitle = "";
        int chunkIndex = 0;
        int totalChunks = 1;
        boolean isMultipleChunks = false;

        // Act
        String result = titleGenerator.generateChunkTitle(originalTitle, chunkIndex, totalChunks, isMultipleChunks);

        // Assert
        assertEquals("Document", result);
    }

    @Test
    void generateChunkTitle_WithExistingPartNumber_RemovesAndReplaces() {
        // Arrange
        String originalTitle = "Introduction (Part 1)";
        int chunkIndex = 1;
        int totalChunks = 3;
        boolean isMultipleChunks = true;

        // Act
        String result = titleGenerator.generateChunkTitle(originalTitle, chunkIndex, totalChunks, isMultipleChunks);

        // Assert
        assertEquals("Introduction (Part 2)", result);
    }

    @Test
    void generateChapterChunkTitle_WithValidData_ReturnsCorrectTitle() {
        // Arrange
        String chapterTitle = "Chapter 1: Introduction";
        Integer chapterNumber = 1;
        int chunkIndex = 0;
        int totalChunks = 2;

        // Act
        String result = titleGenerator.generateChapterChunkTitle(chapterTitle, chapterNumber, chunkIndex, totalChunks);

        // Assert
        assertEquals("Chapter 1: Introduction (Part 1)", result);
    }

    @Test
    void generateChapterChunkTitle_WithNullChapterTitle_UsesChapterNumber() {
        // Arrange
        String chapterTitle = null;
        Integer chapterNumber = 2;
        int chunkIndex = 0;
        int totalChunks = 1;

        // Act
        String result = titleGenerator.generateChapterChunkTitle(chapterTitle, chapterNumber, chunkIndex, totalChunks);

        // Assert
        assertEquals("Chapter 2", result);
    }

    @Test
    void generateSectionChunkTitle_WithValidData_ReturnsCorrectTitle() {
        // Arrange
        String sectionTitle = "1.1 Introduction";
        String chapterTitle = "Chapter 1";
        Integer chapterNumber = 1;
        Integer sectionNumber = 1;
        int chunkIndex = 0;
        int totalChunks = 1;

        // Act
        String result = titleGenerator.generateSectionChunkTitle(sectionTitle, chapterTitle,
                chapterNumber, sectionNumber, chunkIndex, totalChunks);

        // Assert
        assertEquals("1.1 Introduction", result);
    }

    @Test
    void generateSectionChunkTitle_WithNullSectionTitle_UsesChapterAndSectionNumbers() {
        // Arrange
        String sectionTitle = null;
        String chapterTitle = "Introduction";
        Integer chapterNumber = 1;
        Integer sectionNumber = 2;
        int chunkIndex = 0;
        int totalChunks = 1;

        // Act
        String result = titleGenerator.generateSectionChunkTitle(sectionTitle, chapterTitle,
                chapterNumber, sectionNumber, chunkIndex, totalChunks);

        // Assert
        assertEquals("1.2 Introduction", result);
    }

    @Test
    void generateDocumentChunkTitle_WithValidTitle_ReturnsCorrectTitle() {
        // Arrange
        String documentTitle = "Research Paper";
        int chunkIndex = 0;
        int totalChunks = 3;

        // Act
        String result = titleGenerator.generateDocumentChunkTitle(documentTitle, chunkIndex, totalChunks);

        // Assert
        assertEquals("Research Paper (Part 1)", result);
    }

    @Test
    void generateDocumentChunkTitle_WithNullTitle_UsesDefault() {
        // Arrange
        String documentTitle = null;
        int chunkIndex = 0;
        int totalChunks = 1;

        // Act
        String result = titleGenerator.generateDocumentChunkTitle(documentTitle, chunkIndex, totalChunks);

        // Assert
        assertEquals("Document", result);
    }

    @Test
    void extractSubtitle_WithShortSentence_ReturnsFullSentence() {
        // Arrange
        String content = "This is a short sentence. This is another sentence.";
        int maxLength = 50;

        // Act
        String result = titleGenerator.extractSubtitle(content, maxLength);

        // Assert
        assertEquals("This is a short sentence.", result);
    }

    @Test
    void extractSubtitle_WithLongSentence_ReturnsTruncatedContent() {
        // Arrange
        String content = "This is a very long sentence that exceeds the maximum length allowed for subtitles.";
        int maxLength = 20;

        // Act
        String result = titleGenerator.extractSubtitle(content, maxLength);

        // Assert
        assertTrue(result.length() <= maxLength);
        assertTrue(result.startsWith("This is a very"));
    }

    @Test
    void extractSubtitle_WithNoSentenceEnd_ReturnsFirstWords() {
        // Arrange
        String content = "This is content without sentence ending punctuation";
        int maxLength = 15;

        // Act
        String result = titleGenerator.extractSubtitle(content, maxLength);

        // Assert
        assertTrue(result.length() <= maxLength);
        assertEquals("This is content", result);
    }

    @Test
    void extractSubtitle_WithEmptyContent_ReturnsEmptyString() {
        // Arrange
        String content = "";
        int maxLength = 50;

        // Act
        String result = titleGenerator.extractSubtitle(content, maxLength);

        // Assert
        assertEquals("", result);
    }

    @Test
    void extractSubtitle_WithNullContent_ReturnsEmptyString() {
        // Arrange
        String content = null;
        int maxLength = 50;

        // Act
        String result = titleGenerator.extractSubtitle(content, maxLength);

        // Assert
        assertEquals("", result);
    }

    @Test
    void isValidChunkTitle_WithValidTitle_ReturnsTrue() {
        // Arrange
        String title = "Valid Title";

        // Act
        boolean result = titleGenerator.isValidChunkTitle(title);

        // Assert
        assertTrue(result);
    }

    @Test
    void isValidChunkTitle_WithNullTitle_ReturnsFalse() {
        // Arrange
        String title = null;

        // Act
        boolean result = titleGenerator.isValidChunkTitle(title);

        // Assert
        assertFalse(result);
    }

    @Test
    void isValidChunkTitle_WithEmptyTitle_ReturnsFalse() {
        // Arrange
        String title = "";

        // Act
        boolean result = titleGenerator.isValidChunkTitle(title);

        // Assert
        assertFalse(result);
    }

    @Test
    void isValidChunkTitle_WithWhitespaceOnly_ReturnsFalse() {
        // Arrange
        String title = "   ";

        // Act
        boolean result = titleGenerator.isValidChunkTitle(title);

        // Assert
        assertFalse(result);
    }

    @Test
    void isValidChunkTitle_WithTooLongTitle_ReturnsFalse() {
        // Arrange
        StringBuilder longTitle = new StringBuilder();
        for (int i = 0; i < 250; i++) {
            longTitle.append("a");
        }

        // Act
        boolean result = titleGenerator.isValidChunkTitle(longTitle.toString());

        // Assert
        assertFalse(result);
    }

    @Test
    void generateSummaryTitle_WithValidTitle_ReturnsCorrectSummary() {
        // Arrange
        String originalTitle = "Research Paper";
        int totalChunks = 5;

        // Act
        String result = titleGenerator.generateSummaryTitle(originalTitle, totalChunks);

        // Assert
        assertEquals("Research Paper (5 parts)", result);
    }

    @Test
    void generateSummaryTitle_WithNullTitle_UsesDefault() {
        // Arrange
        String originalTitle = null;
        int totalChunks = 3;

        // Act
        String result = titleGenerator.generateSummaryTitle(originalTitle, totalChunks);

        // Assert
        assertEquals("Document (3 parts)", result);
    }

    @Test
    void generateSummaryTitle_WithExistingPartNumber_RemovesAndReplaces() {
        // Arrange
        String originalTitle = "Introduction (Part 1)";
        int totalChunks = 2;

        // Act
        String result = titleGenerator.generateSummaryTitle(originalTitle, totalChunks);

        // Assert
        assertEquals("Introduction (2 parts)", result);
    }
} 