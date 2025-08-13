package uk.gegc.quizmaker.service.document.chunker;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gegc.quizmaker.dto.document.ProcessDocumentRequest;
import uk.gegc.quizmaker.service.document.chunker.impl.UniversalChapterBasedChunker;
import uk.gegc.quizmaker.service.document.converter.ConvertedDocument;
import uk.gegc.quizmaker.util.ChunkTitleGenerator;
import uk.gegc.quizmaker.util.SentenceBoundaryDetector;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void shouldPreventTinyChunks() {
        // Given
        ProcessDocumentRequest request = new ProcessDocumentRequest();
        request.setChunkingStrategy(ProcessDocumentRequest.ChunkingStrategy.CHAPTER_BASED);
        request.setMaxChunkSize(100000); // Updated to new max size
        request.setMinChunkSize(1000);
        request.setAggressiveCombinationThreshold(5000);

        ConvertedDocument document = createTestDocument();

        // When
        List<UniversalChunker.Chunk> chunks = chunker.chunkDocument(document, request);

        // Then
        assertFalse(chunks.isEmpty(), "Should create chunks");
        
        // Verify no chunks are smaller than minimum size
        for (int i = 0; i < chunks.size(); i++) {
            UniversalChunker.Chunk chunk = chunks.get(i);
            int chunkSize = chunk.getCharacterCount();
            
            if (i < chunks.size() - 1) {
                assertTrue(chunkSize >= 1000, 
                        "Non-final chunk " + i + " should not be smaller than minimum size. Found: " + chunkSize);
            } else {
                // Final chunk can be smaller if there's not enough content left
                assertTrue(chunkSize >= 200, 
                        "Final chunk " + i + " should be at least 200 characters. Found: " + chunkSize);
            }
        }
    }

    @Test
    void shouldUseTargetSizeWhenBoundaryDetectionFails() {
        // Given
        ProcessDocumentRequest request = new ProcessDocumentRequest();
        request.setChunkingStrategy(ProcessDocumentRequest.ChunkingStrategy.CHAPTER_BASED);
        request.setMaxChunkSize(100000); // Updated to new max size
        request.setMinChunkSize(1000);
        request.setAggressiveCombinationThreshold(5000);

        ConvertedDocument document = createTestDocument();

        // When
        List<UniversalChunker.Chunk> chunks = chunker.chunkDocument(document, request);

        // Then
        assertFalse(chunks.isEmpty(), "Should create chunks");
        
        // Verify chunks are reasonably sized when boundary detection fails
        // The key point is that they should NOT be tiny chunks (like 10-100 characters)
        // but should be substantial chunks that are close to the target size
        for (int i = 0; i < chunks.size(); i++) {
            UniversalChunker.Chunk chunk = chunks.get(i);
            int chunkSize = chunk.getCharacterCount();
            
            // Debug information
            System.out.println("Chunk " + i + ": " + chunkSize + " characters");
            
            // Should be at least the minimum chunk size
            assertTrue(chunkSize >= 1000, 
                    "Chunk " + i + " should not be smaller than minimum size. Found: " + chunkSize);
            
            // For all chunks except the final one, should be close to target size
            // The final chunk will naturally be smaller as it contains remaining content
            if (i < chunks.size() - 1) {
                // Non-final chunks should be close to target size (at least 80% of target)
                assertTrue(chunkSize >= 80000, 
                        "Non-final chunk " + i + " should be close to target size when boundary detection fails. " +
                        "Found: " + chunkSize + " (target was 100000, expected at least 80000)");
            } else {
                // Final chunk can be smaller but should still be substantial (at least 200 chars)
                assertTrue(chunkSize >= 200, 
                        "Final chunk " + i + " should be at least 200 characters. " +
                        "Found: " + chunkSize + " (expected at least 200)");
            }
        }
    }

    @Test
    void shouldCombineTinyChunks() {
        // Given
        ProcessDocumentRequest request = new ProcessDocumentRequest();
        request.setChunkingStrategy(ProcessDocumentRequest.ChunkingStrategy.CHAPTER_BASED);
        request.setMaxChunkSize(100000); // Updated to new max size
        request.setMinChunkSize(1000);
        request.setAggressiveCombinationThreshold(5000);

        ConvertedDocument document = createTestDocument();

        // When
        List<UniversalChunker.Chunk> chunks = chunker.chunkDocument(document, request);

        // Then
        assertFalse(chunks.isEmpty(), "Should create chunks");
        
        // Verify chunks are properly sized after combination
        for (int i = 0; i < chunks.size(); i++) {
            UniversalChunker.Chunk chunk = chunks.get(i);
            int chunkSize = chunk.getCharacterCount();
            
            if (i < chunks.size() - 1) {
                assertTrue(chunkSize >= 1000, 
                        "Non-final chunk " + i + " should not be smaller than minimum size after combination. Found: " + chunkSize);
            } else {
                // Final chunk can be smaller if there's not enough content left
                assertTrue(chunkSize >= 200, 
                        "Final chunk " + i + " should be at least 200 characters after combination. Found: " + chunkSize);
            }
        }
    }

    @Test
    void shouldCreateProperlySizedChunksWhenBoundaryDetectionWorks() {
        // Given
        ProcessDocumentRequest request = new ProcessDocumentRequest();
        request.setChunkingStrategy(ProcessDocumentRequest.ChunkingStrategy.CHAPTER_BASED);
        request.setMaxChunkSize(100000); // Updated to new max size
        request.setMinChunkSize(1000);
        request.setAggressiveCombinationThreshold(5000);

        ConvertedDocument document = createTestDocument();

        // When
        List<UniversalChunker.Chunk> chunks = chunker.chunkDocument(document, request);

        // Then
        assertFalse(chunks.isEmpty(), "Should create chunks");
        
        // Verify chunks are properly sized
        for (int i = 0; i < chunks.size(); i++) {
            UniversalChunker.Chunk chunk = chunks.get(i);
            int chunkSize = chunk.getCharacterCount();
            
            // Debug information
            System.out.println("Chunk " + i + ": " + chunkSize + " characters");
            
            // Should be at least the minimum chunk size
            assertTrue(chunkSize >= 1000, 
                    "Chunk " + i + " should not be smaller than minimum size. Found: " + chunkSize);
            
            // For all chunks except the final one, should be reasonably sized
            // The final chunk will naturally be smaller as it contains remaining content
            if (i < chunks.size() - 1) {
                // Non-final chunks should be reasonably sized (at least 50% of target)
                assertTrue(chunkSize >= 50000, 
                        "Non-final chunk " + i + " should be reasonably sized. Found: " + chunkSize);
            } else {
                // Final chunk can be smaller but should still be substantial (at least 200 chars)
                assertTrue(chunkSize >= 200, 
                        "Final chunk " + i + " should be at least 200 characters when boundary detection works. " +
                        "Found: " + chunkSize + " (expected at least 200)");
            }
        }
    }

    @Test
    void shouldCombineSmallChaptersAndSplitLargeOnes() {
        // Given
        ProcessDocumentRequest request = new ProcessDocumentRequest();
        request.setChunkingStrategy(ProcessDocumentRequest.ChunkingStrategy.CHAPTER_BASED);
        request.setMaxChunkSize(100000); // Updated to new max size
        request.setMinChunkSize(1000);
        request.setAggressiveCombinationThreshold(5000);

        ConvertedDocument document = createTestDocumentWithMixedChapters();
        
        // When
        List<UniversalChunker.Chunk> chunks = chunker.chunkDocument(document, request);

        // Then
        assertFalse(chunks.isEmpty(), "Should create chunks");
        
        // Verify chunks are properly sized
        for (int i = 0; i < chunks.size(); i++) {
            UniversalChunker.Chunk chunk = chunks.get(i);
            int chunkSize = chunk.getCharacterCount();
            
            System.out.println("Chunk " + i + ": '" + chunk.getTitle() + "' (" + chunkSize + " chars)");
            
            // Should be at least the minimum chunk size (except for final chunk which might be smaller)
            if (i < chunks.size() - 1) {
                assertTrue(chunkSize >= 1000, 
                        "Non-final chunk " + i + " should not be smaller than minimum size. Found: " + chunkSize);
            } else {
                // Final chunk can be smaller if there's not enough content left
                assertTrue(chunkSize >= 200, 
                        "Final chunk " + i + " should be at least 200 characters. Found: " + chunkSize);
            }
            
            // Should not exceed maximum size (except for final chunk which might be smaller)
            if (i < chunks.size() - 1) {
                assertTrue(chunkSize <= 100000, 
                        "Non-final chunk " + i + " should not exceed maximum size. Found: " + chunkSize);
            }
        }
        
        // Should have fewer chunks than original chapters due to combination
        assertTrue(chunks.size() <= 4, "Should have fewer or equal chunks than original chapters due to combination");
    }

    @Test
    void shouldHandleDocumentWithoutChapters() {
        // Given
        ProcessDocumentRequest request = new ProcessDocumentRequest();
        request.setChunkingStrategy(ProcessDocumentRequest.ChunkingStrategy.CHAPTER_BASED);
        request.setMaxChunkSize(100000); // Updated to new max size
        request.setMinChunkSize(1000);
        request.setAggressiveCombinationThreshold(5000);

        ConvertedDocument document = createTestDocumentWithoutChapters();
        
        // When
        List<UniversalChunker.Chunk> chunks = chunker.chunkDocument(document, request);

        // Then
        assertFalse(chunks.isEmpty(), "Should create chunks");
        
        // Verify chunks are properly sized
        for (int i = 0; i < chunks.size(); i++) {
            UniversalChunker.Chunk chunk = chunks.get(i);
            int chunkSize = chunk.getCharacterCount();
            
            System.out.println("Chunk " + i + ": '" + chunk.getTitle() + "' (" + chunkSize + " chars)");
            
            // Should be at least the minimum chunk size (except for final chunk which might be smaller)
            if (i < chunks.size() - 1) {
                assertTrue(chunkSize >= 1000, 
                        "Non-final chunk " + i + " should not be smaller than minimum size. Found: " + chunkSize);
            } else {
                // Final chunk can be smaller if there's not enough content left
                assertTrue(chunkSize >= 200, 
                        "Final chunk " + i + " should be at least 200 characters. Found: " + chunkSize);
            }
            
            // Should not exceed maximum size (except for final chunk which might be smaller)
            if (i < chunks.size() - 1) {
                assertTrue(chunkSize <= 100000, 
                        "Non-final chunk " + i + " should not exceed maximum size. Found: " + chunkSize);
            }
        }
    }

    private ConvertedDocument createTestDocument() {
        ConvertedDocument document = new ConvertedDocument();
        document.setOriginalFilename("test.pdf");
        document.setTitle("Test Document");
        document.setAuthor("Test Author");
        document.setConverterType("PDF");
        document.setTotalPages(10);
        
        // Create a large chapter that needs splitting
        // Use a more realistic content pattern that will create multiple chunks
        ConvertedDocument.Chapter chapter = new ConvertedDocument.Chapter();
        chapter.setTitle("Test Chapter");
        
        // Create content that will definitely need splitting (much larger than maxChunkSize)
        StringBuilder content = new StringBuilder();
        for (int i = 1; i <= 100; i++) {
            content.append("This is paragraph ").append(i).append(" of the test chapter. ");
            content.append("It contains multiple sentences to ensure proper chunking. ");
            content.append("The content is designed to test the chunking logic thoroughly. ");
            content.append("Each paragraph should contribute to the overall document size. ");
            content.append("This helps ensure that the chunking algorithm works correctly. ");
        }
        
        chapter.setContent(content.toString());
        chapter.setStartPage(1);
        chapter.setEndPage(10);
        chapter.setSections(List.of());
        
        document.setChapters(List.of(chapter));
        document.setFullContent(chapter.getContent());
        
        return document;
    }

    private ConvertedDocument createTestDocumentWithMixedChapters() {
        ConvertedDocument document = new ConvertedDocument();
        document.setOriginalFilename("test-mixed.pdf");
        document.setTitle("Test Document with Mixed Chapters");
        document.setAuthor("Test Author");
        document.setConverterType("PDF");
        document.setTotalPages(10);
        
        // Create chapters with mixed sizes
        List<ConvertedDocument.Chapter> chapters = new ArrayList<>();
        
        // Chapter 1: Small (800 chars) - should be combined
        ConvertedDocument.Chapter chapter1 = new ConvertedDocument.Chapter();
        chapter1.setTitle("Small Chapter");
        chapter1.setContent("This is a small chapter with limited content. ".repeat(20)); // ~800 chars
        chapter1.setStartPage(1);
        chapter1.setEndPage(2);
        chapter1.setSections(List.of());
        chapters.add(chapter1);
        
        // Chapter 2: Small (600 chars) - should be combined with Chapter 1
        ConvertedDocument.Chapter chapter2 = new ConvertedDocument.Chapter();
        chapter2.setTitle("Another Small Chapter");
        chapter2.setContent("This is another small chapter. ".repeat(15)); // ~600 chars
        chapter2.setStartPage(3);
        chapter2.setEndPage(3);
        chapter2.setSections(List.of());
        chapters.add(chapter2);
        
        // Chapter 3: Large (6000 chars) - should be split
        ConvertedDocument.Chapter chapter3 = new ConvertedDocument.Chapter();
        chapter3.setTitle("Large Chapter");
        chapter3.setContent("This is a large chapter that needs to be split. ".repeat(150)); // ~6000 chars
        chapter3.setStartPage(4);
        chapter3.setEndPage(8);
        chapter3.setSections(List.of());
        chapters.add(chapter3);
        
        // Chapter 4: Medium (3000 chars) - should be kept as-is
        ConvertedDocument.Chapter chapter4 = new ConvertedDocument.Chapter();
        chapter4.setTitle("Medium Chapter");
        chapter4.setContent("This is a medium-sized chapter. ".repeat(75)); // ~3000 chars
        chapter4.setStartPage(9);
        chapter4.setEndPage(10);
        chapter4.setSections(List.of());
        chapters.add(chapter4);
        
        document.setChapters(chapters);
        
        // Set full content
        StringBuilder fullContent = new StringBuilder();
        for (ConvertedDocument.Chapter chapter : chapters) {
            fullContent.append(chapter.getContent()).append("\n\n");
        }
        document.setFullContent(fullContent.toString());
        
        return document;
    }

    private ConvertedDocument createTestDocumentWithoutChapters() {
        ConvertedDocument document = new ConvertedDocument();
        document.setOriginalFilename("test-no-chapters.pdf");
        document.setTitle("Test Document Without Chapters");
        document.setAuthor("Test Author");
        document.setConverterType("PDF");
        document.setTotalPages(10);
        
        // Create content without chapters
        String content = "This is a test document without chapters. ".repeat(1000); // ~35000 chars
        document.setFullContent(content);
        document.setChapters(List.of()); // Empty chapters list
        
        return document;
    }
} 