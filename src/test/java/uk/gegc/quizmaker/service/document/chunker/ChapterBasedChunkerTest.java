package uk.gegc.quizmaker.service.document.chunker;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import uk.gegc.quizmaker.dto.document.ProcessDocumentRequest;
import uk.gegc.quizmaker.service.document.chunker.ContentChunker.Chunk;
import uk.gegc.quizmaker.service.document.parser.ParsedDocument;
import uk.gegc.quizmaker.util.ChunkTitleGenerator;
import uk.gegc.quizmaker.util.SentenceBoundaryDetector;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChapterBasedChunkerTest {

    @InjectMocks
    private uk.gegc.quizmaker.service.document.chunker.impl.ChapterBasedChunker chunker;

    @Mock
    private SentenceBoundaryDetector sentenceBoundaryDetector;

    @Mock
    private ChunkTitleGenerator titleGenerator;

    private ProcessDocumentRequest request;

    @BeforeEach
    void setUp() {
        request = new ProcessDocumentRequest();
        request.setMaxChunkSize(100);
        request.setChunkingStrategy(ProcessDocumentRequest.ChunkingStrategy.CHAPTER_BASED);
        
        // Mock the utilities
        when(sentenceBoundaryDetector.findBestSplitPoint(anyString(), anyInt()))
                .thenAnswer(invocation -> {
                    String text = invocation.getArgument(0);
                    Integer maxLength = invocation.getArgument(1);
                    return Math.min(text.length(), maxLength);
                });
        
        when(titleGenerator.generateChunkTitle(anyString(), anyInt(), anyInt(), anyBoolean()))
                .thenAnswer(invocation -> {
                    String title = invocation.getArgument(0);
                    Integer chunkIndex = invocation.getArgument(1);
                    Integer totalChunks = invocation.getArgument(2);
                    Boolean isMultipleChunks = invocation.getArgument(3);
                    
                    if (isMultipleChunks) {
                        return title + " (Part " + (chunkIndex + 1) + ")";
                    }
                    return title;
                });
    }

    @Test
    void chunkDocument_WithChapters_Success() {
        // Arrange
        ParsedDocument document = createDocumentWithChapters();
        
        // Act
        List<Chunk> chunks = chunker.chunkDocument(document, request);
        
        // Assert
        assertNotNull(chunks);
        assertEquals(2, chunks.size());
        
        Chunk firstChunk = chunks.get(0);
        assertEquals("Chapter 1", firstChunk.getTitle());
        assertEquals("This is chapter 1 content.", firstChunk.getContent());
        assertEquals(0, firstChunk.getChunkIndex());
        assertEquals(26, firstChunk.getCharacterCount());
        assertEquals(5, firstChunk.getWordCount());
        
        Chunk secondChunk = chunks.get(1);
        assertEquals("Chapter 2", secondChunk.getTitle());
        assertEquals("This is chapter 2 content.", secondChunk.getContent());
        assertEquals(1, secondChunk.getChunkIndex());
        assertEquals(26, secondChunk.getCharacterCount());
        assertEquals(5, secondChunk.getWordCount());
    }

    @Test
    void chunkDocument_WithLargeChapter_SplitsIntoMultipleChunks() {
        // Arrange
        ParsedDocument document = createDocumentWithLargeChapter();
        request.setMaxChunkSize(50); // Small chunk size to force splitting
        
        // Act
        List<Chunk> chunks = chunker.chunkDocument(document, request);
        
        // Assert
        assertNotNull(chunks);
        assertTrue(chunks.size() > 1);
        
        // Check that chunks don't exceed max size
        for (Chunk chunk : chunks) {
            assertTrue(chunk.getCharacterCount() <= request.getMaxChunkSize());
        }
        
        // Check that chunks are properly named
        for (int i = 0; i < chunks.size(); i++) {
            assertTrue(chunks.get(i).getTitle().contains("Chapter 1 (Part " + (i + 1) + ")"));
        }
    }

    @Test
    void chunkDocument_WithChaptersAndSections_Success() {
        // Arrange
        ParsedDocument document = createDocumentWithChaptersAndSections();
        
        // Act
        List<Chunk> chunks = chunker.chunkDocument(document, request);
        
        // Assert
        assertNotNull(chunks);
        assertEquals(4, chunks.size()); // 2 chapters with 2 sections each
        
        // Check first chapter sections
        Chunk firstSection = chunks.get(0);
        assertEquals("1.1 Introduction", firstSection.getTitle());
        assertEquals("This is the introduction section.", firstSection.getContent());
        assertEquals(0, firstSection.getChunkIndex());
        
        Chunk secondSection = chunks.get(1);
        assertEquals("1.2 Main Content", secondSection.getTitle());
        assertEquals("This is the main content section.", secondSection.getContent());
        assertEquals(1, secondSection.getChunkIndex());
    }

    @Test
    void chunkDocument_WithLargeSection_SplitsIntoMultipleChunks() {
        // Arrange
        ParsedDocument document = createDocumentWithLargeSection();
        request.setMaxChunkSize(30); // Small chunk size to force splitting
        
        // Act
        List<Chunk> chunks = chunker.chunkDocument(document, request);
        
        // Assert
        assertNotNull(chunks);
        assertTrue(chunks.size() > 1);
        
        // Check that chunks don't exceed max size
        for (Chunk chunk : chunks) {
            assertTrue(chunk.getCharacterCount() <= request.getMaxChunkSize());
        }
        
        // Check that chunks are properly named
        for (int i = 0; i < chunks.size(); i++) {
            assertTrue(chunks.get(i).getTitle().contains("1.1 Introduction (Part " + (i + 1) + ")"));
        }
    }

    @Test
    void chunkDocument_NoChapters_FallsBackToSizeBased() {
        // Arrange
        ParsedDocument document = createDocumentWithoutChapters();
        
        // Act
        List<Chunk> chunks = chunker.chunkDocument(document, request);
        
        // Assert
        assertNotNull(chunks);
        assertTrue(chunks.size() > 1);
        
        // Check that chunks don't exceed max size
        for (Chunk chunk : chunks) {
            assertTrue(chunk.getCharacterCount() <= request.getMaxChunkSize());
        }
        
        // Check that chunks are properly named
        for (int i = 0; i < chunks.size(); i++) {
            assertTrue(chunks.get(i).getTitle().contains("Document (Part " + (i + 1) + ")"));
        }
    }

    @Test
    void chunkDocument_RespectsSentenceBoundaries() {
        // Arrange
        ParsedDocument document = createDocumentWithSentences();
        request.setMaxChunkSize(50);
        
        // Act
        List<Chunk> chunks = chunker.chunkDocument(document, request);
        
        // Assert
        assertNotNull(chunks);
        
        // Check that chunks don't break in the middle of sentences
        for (Chunk chunk : chunks) {
            String content = chunk.getContent();
            // Should not end with a sentence that's clearly incomplete
            assertFalse(content.endsWith("This is"));
            assertFalse(content.endsWith("The second"));
        }
    }

    @Test
    void getSupportedStrategy_ReturnsChapterBased() {
        // Act
        ProcessDocumentRequest.ChunkingStrategy strategy = chunker.getSupportedStrategy();
        
        // Assert
        assertEquals(ProcessDocumentRequest.ChunkingStrategy.CHAPTER_BASED, strategy);
    }

    private ParsedDocument createDocumentWithChapters() {
        ParsedDocument document = new ParsedDocument();
        document.setTitle("Test Document");
        document.setAuthor("Test Author");
        document.setContent("This is test content for the document.");
        document.setTotalPages(10);
        
        ParsedDocument.Chapter chapter1 = new ParsedDocument.Chapter();
        chapter1.setTitle("Chapter 1");
        chapter1.setContent("This is chapter 1 content.");
        chapter1.setStartPage(1);
        chapter1.setEndPage(5);
        
        ParsedDocument.Chapter chapter2 = new ParsedDocument.Chapter();
        chapter2.setTitle("Chapter 2");
        chapter2.setContent("This is chapter 2 content.");
        chapter2.setStartPage(6);
        chapter2.setEndPage(10);
        
        document.getChapters().add(chapter1);
        document.getChapters().add(chapter2);
        
        return document;
    }

    private ParsedDocument createDocumentWithLargeChapter() {
        ParsedDocument document = new ParsedDocument();
        document.setTitle("Test Document");
        document.setAuthor("Test Author");
        document.setContent("This is test content for the document.");
        document.setTotalPages(10);
        
        ParsedDocument.Chapter chapter = new ParsedDocument.Chapter();
        chapter.setTitle("Chapter 1");
        chapter.setContent("This is a very long chapter content that should be split into multiple chunks. " +
                "It contains many sentences and paragraphs that will exceed the maximum chunk size. " +
                "The chunking algorithm should split this content intelligently while respecting sentence boundaries. " +
                "Each chunk should be properly sized and maintain the logical flow of the content.");
        chapter.setStartPage(1);
        chapter.setEndPage(10);
        
        document.getChapters().add(chapter);
        
        return document;
    }

    private ParsedDocument createDocumentWithChaptersAndSections() {
        ParsedDocument document = new ParsedDocument();
        document.setTitle("Test Document");
        document.setAuthor("Test Author");
        document.setContent("This is test content for the document.");
        document.setTotalPages(10);
        
        ParsedDocument.Chapter chapter1 = new ParsedDocument.Chapter();
        chapter1.setTitle("Chapter 1");
        chapter1.setStartPage(1);
        chapter1.setEndPage(5);
        
        ParsedDocument.Section section1 = new ParsedDocument.Section();
        section1.setTitle("1.1 Introduction");
        section1.setContent("This is the introduction section.");
        section1.setStartPage(1);
        section1.setEndPage(2);
        section1.setChapterTitle("Chapter 1");
        section1.setChapterNumber(1);
        section1.setSectionNumber(1);
        
        ParsedDocument.Section section2 = new ParsedDocument.Section();
        section2.setTitle("1.2 Main Content");
        section2.setContent("This is the main content section.");
        section2.setStartPage(3);
        section2.setEndPage(5);
        section2.setChapterTitle("Chapter 1");
        section2.setChapterNumber(1);
        section2.setSectionNumber(2);
        
        chapter1.getSections().add(section1);
        chapter1.getSections().add(section2);
        
        ParsedDocument.Chapter chapter2 = new ParsedDocument.Chapter();
        chapter2.setTitle("Chapter 2");
        chapter2.setStartPage(6);
        chapter2.setEndPage(10);
        
        ParsedDocument.Section section3 = new ParsedDocument.Section();
        section3.setTitle("2.1 Conclusion");
        section3.setContent("This is the conclusion section.");
        section3.setStartPage(6);
        section3.setEndPage(8);
        section3.setChapterTitle("Chapter 2");
        section3.setChapterNumber(2);
        section3.setSectionNumber(1);
        
        ParsedDocument.Section section4 = new ParsedDocument.Section();
        section4.setTitle("2.2 References");
        section4.setContent("This is the references section.");
        section4.setStartPage(9);
        section4.setEndPage(10);
        section4.setChapterTitle("Chapter 2");
        section4.setChapterNumber(2);
        section4.setSectionNumber(2);
        
        chapter2.getSections().add(section3);
        chapter2.getSections().add(section4);
        
        document.getChapters().add(chapter1);
        document.getChapters().add(chapter2);
        
        return document;
    }

    private ParsedDocument createDocumentWithLargeSection() {
        ParsedDocument document = new ParsedDocument();
        document.setTitle("Test Document");
        document.setAuthor("Test Author");
        document.setContent("This is test content for the document.");
        document.setTotalPages(10);
        
        ParsedDocument.Chapter chapter = new ParsedDocument.Chapter();
        chapter.setTitle("Chapter 1");
        chapter.setStartPage(1);
        chapter.setEndPage(10);
        
        ParsedDocument.Section section = new ParsedDocument.Section();
        section.setTitle("1.1 Introduction");
        section.setContent("This is a very long section content that should be split into multiple chunks. " +
                "It contains many sentences that will exceed the maximum chunk size. " +
                "The chunking algorithm should split this content intelligently while respecting sentence boundaries. " +
                "Each chunk should be properly sized and maintain the logical flow of the content.");
        section.setStartPage(1);
        section.setEndPage(10);
        section.setChapterTitle("Chapter 1");
        section.setChapterNumber(1);
        section.setSectionNumber(1);
        
        chapter.getSections().add(section);
        document.getChapters().add(chapter);
        
        return document;
    }

    private ParsedDocument createDocumentWithoutChapters() {
        ParsedDocument document = new ParsedDocument();
        document.setTitle("Test Document");
        document.setAuthor("Test Author");
        document.setContent("This is a document without chapters. " +
                "It contains a lot of content that should be split into chunks. " +
                "The chunking algorithm should fall back to size-based chunking. " +
                "This content should be split into multiple chunks based on the maximum chunk size.");
        document.setTotalPages(10);
        
        return document;
    }

    private ParsedDocument createDocumentWithSentences() {
        ParsedDocument document = new ParsedDocument();
        document.setTitle("Test Document");
        document.setAuthor("Test Author");
        document.setContent("This is the first sentence. The second sentence follows. " +
                "This is a third sentence. And here is a fourth sentence. " +
                "The chunking should respect sentence boundaries when splitting content.");
        document.setTotalPages(10);
        
        return document;
    }
} 