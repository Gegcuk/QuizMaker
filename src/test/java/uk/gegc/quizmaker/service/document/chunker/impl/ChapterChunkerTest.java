package uk.gegc.quizmaker.service.document.chunker.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gegc.quizmaker.dto.document.ProcessDocumentRequest;
import uk.gegc.quizmaker.service.document.chunker.UniversalChunker;
import uk.gegc.quizmaker.service.document.converter.ConvertedDocument;
import uk.gegc.quizmaker.util.ChunkTitleGenerator;
import uk.gegc.quizmaker.util.SentenceBoundaryDetector;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@Execution(ExecutionMode.CONCURRENT)
@ExtendWith(MockitoExtension.class)
class UniversalChapterBasedChunkerTest {

    @Mock
    private SentenceBoundaryDetector sentenceBoundaryDetector;

    @Mock
    private ChunkTitleGenerator titleGenerator;

    private UniversalChapterBasedChunker chunker;

    @BeforeEach
    void setUp() {
        chunker = new UniversalChapterBasedChunker(sentenceBoundaryDetector, titleGenerator);
    }

    @Test
    void chunkDocument_WithChapters_ReturnsChunks() {
        // Arrange
        ConvertedDocument document = createTestDocumentWithChapters();
        ProcessDocumentRequest request = createTestRequest();

        // Act
        List<UniversalChunker.Chunk> result = chunker.chunkDocument(document, request);

        // Assert
        assertNotNull(result);
        assertFalse(result.isEmpty());
        assertEquals("Test Chapter", result.get(0).getTitle());
        assertEquals("This is chapter content.", result.get(0).getContent());
        assertEquals(ProcessDocumentRequest.ChunkingStrategy.CHAPTER_BASED, result.get(0).getChunkType());
    }

    @Test
    void chunkDocument_WithoutChapters_FallsBackToSizeBased() {
        // Arrange
        ConvertedDocument document = createTestDocumentWithoutChapters();
        ProcessDocumentRequest request = createTestRequest();

        when(titleGenerator.generateChunkTitle(anyString(), anyInt(), anyInt(), anyBoolean()))
                .thenReturn("Document Part 1");

        // Act
        List<UniversalChunker.Chunk> result = chunker.chunkDocument(document, request);

        // Assert
        assertNotNull(result);
        assertFalse(result.isEmpty());
        // After combining small chunks, the title will be a concatenated string
        assertTrue(result.get(0).getTitle().contains("Document Part 1"));
        assertEquals(ProcessDocumentRequest.ChunkingStrategy.SIZE_BASED, result.get(0).getChunkType());
    }

    @Test
    void getSupportedStrategy_ReturnsChapterBased() {
        // Act
        ProcessDocumentRequest.ChunkingStrategy result = chunker.getSupportedStrategy();

        // Assert
        assertEquals(ProcessDocumentRequest.ChunkingStrategy.CHAPTER_BASED, result);
    }

    @Test
    void canHandle_ChapterBased_ReturnsTrue() {
        // Act
        boolean result = chunker.canHandle(ProcessDocumentRequest.ChunkingStrategy.CHAPTER_BASED);

        // Assert
        assertTrue(result);
    }

    @Test
    void canHandle_Auto_ReturnsTrue() {
        // Act
        boolean result = chunker.canHandle(ProcessDocumentRequest.ChunkingStrategy.AUTO);

        // Assert
        assertTrue(result);
    }

    @Test
    void canHandle_SizeBased_ReturnsFalse() {
        // Act
        boolean result = chunker.canHandle(ProcessDocumentRequest.ChunkingStrategy.SIZE_BASED);

        // Assert
        assertFalse(result);
    }

    private ConvertedDocument createTestDocumentWithChapters() {
        ConvertedDocument document = new ConvertedDocument();
        document.setOriginalFilename("test.pdf");
        document.setTitle("Test Document");
        document.setAuthor("Test Author");
        document.setConverterType("PDF_DOCUMENT_CONVERTER");
        document.setFullContent("This is test content.");

        ConvertedDocument.Chapter chapter = new ConvertedDocument.Chapter();
        chapter.setTitle("Test Chapter");
        chapter.setContent("This is chapter content.");
        chapter.setStartPage(1);
        chapter.setEndPage(5);

        document.getChapters().add(chapter);
        return document;
    }

    private ConvertedDocument createTestDocumentWithoutChapters() {
        ConvertedDocument document = new ConvertedDocument();
        document.setOriginalFilename("test.pdf");
        document.setTitle("Test Document");
        document.setAuthor("Test Author");
        document.setConverterType("PDF_DOCUMENT_CONVERTER");

        // Create content that's much longer than the max chunk size (4000)
        String baseContent = "This is a very long document content that needs to be chunked into smaller pieces for processing. " +
                "The content should be split into manageable chunks that can be processed by AI systems. " +
                "Each chunk should maintain context and readability while staying within size limits. " +
                "This is additional content to make the document longer so that it exceeds the maximum chunk size. " +
                "We need enough content to trigger the size-based chunking logic. " +
                "The sentence boundary detector should be called to find the best split points. " +
                "This content should be long enough to require multiple chunks. " +
                "Let's add more sentences to ensure we have enough content. " +
                "The chunking algorithm should respect sentence boundaries when splitting content. " +
                "This is important for maintaining readability and context in the resulting chunks. " +
                "We want to test that the fallback to size-based chunking works correctly. " +
                "The content should be processed in a way that preserves the meaning and structure. " +
                "Each chunk should be self-contained and meaningful on its own. " +
                "This test document contains multiple paragraphs and sentences. " +
                "The chunking process should handle this complexity appropriately. " +
                "We are testing the universal chunking system with various content types. " +
                "The system should be robust and handle different document structures. " +
                "This is the final paragraph to ensure we have sufficient content for testing. ";

        // Repeat the content multiple times to exceed 4000 characters
        StringBuilder longContent = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            longContent.append(baseContent);
        }

        document.setFullContent(longContent.toString());
        document.setTotalPages(10);
        return document;
    }

    private ProcessDocumentRequest createTestRequest() {
        ProcessDocumentRequest request = new ProcessDocumentRequest();
        request.setChunkingStrategy(ProcessDocumentRequest.ChunkingStrategy.CHAPTER_BASED);
        request.setMaxChunkSize(4000);
        request.setStoreChunks(true);
        return request;
    }
} 