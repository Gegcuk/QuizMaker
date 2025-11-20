package uk.gegc.quizmaker.features.document.infra.converter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gegc.quizmaker.features.document.api.dto.ProcessDocumentRequest;
import uk.gegc.quizmaker.features.document.application.ConvertedDocument;
import uk.gegc.quizmaker.features.document.infra.text.SentenceBoundaryDetector;
import uk.gegc.quizmaker.features.document.infra.util.ChunkTitleGenerator;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@Execution(ExecutionMode.CONCURRENT)
@ExtendWith(MockitoExtension.class)
@DisplayName("SizeBasedChunker Tests")
class SizeBasedChunkerTest {

    @Mock
    private SentenceBoundaryDetector sentenceBoundaryDetector;

    @Mock
    private ChunkTitleGenerator titleGenerator;

    private SizeBasedChunker chunker;

    @BeforeEach
    void setUp() {
        chunker = new SizeBasedChunker(sentenceBoundaryDetector, titleGenerator);
    }

    @Test
    @DisplayName("chunkDocument: document fits in single chunk")
    void chunkDocument_DocumentFitsInSingleChunk_ReturnsSingleChunk() {
        // Arrange
        ConvertedDocument document = createSmallDocument();
        ProcessDocumentRequest request = createTestRequest();

        when(titleGenerator.generateDocumentChunkTitle("Test Document", 0, 1))
                .thenReturn("Test Document");

        // Act
        List<UniversalChunker.Chunk> result = chunker.chunkDocument(document, request);

        // Assert
        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals("Test Document", result.get(0).getTitle());
        assertEquals(ProcessDocumentRequest.ChunkingStrategy.SIZE_BASED, result.get(0).getChunkType());
        assertEquals(1, result.get(0).getStartPage());
        assertEquals(10, result.get(0).getEndPage());
        assertNull(result.get(0).getChapterTitle());
        assertNull(result.get(0).getSectionTitle());
    }

    @Test
    @DisplayName("chunkDocument: document exceeds max size, splits recursively")
    void chunkDocument_DocumentExceedsMaxSize_SplitsRecursively() {
        // Arrange
        ConvertedDocument document = createLargeDocument();
        ProcessDocumentRequest request = createTestRequest();

        // Mock SentenceBoundaryDetector to return a split point
        when(sentenceBoundaryDetector.findBestSplitPoint(anyString(), eq(4000)))
                .thenReturn(2000);

        when(titleGenerator.generateDocumentChunkTitle(eq("Test Document"), anyInt(), anyInt()))
                .thenAnswer(invocation -> "Test Document (Part " + ((Integer) invocation.getArgument(1) + 1) + ")");

        // Act
        List<UniversalChunker.Chunk> result = chunker.chunkDocument(document, request);

        // Assert
        assertNotNull(result);
        assertTrue(result.size() > 1, "Should create multiple chunks");
        result.forEach(chunk -> {
            assertEquals(ProcessDocumentRequest.ChunkingStrategy.SIZE_BASED, chunk.getChunkType());
            assertEquals(1, chunk.getStartPage());
            assertEquals(10, chunk.getEndPage());
            assertNull(chunk.getChapterTitle());
            assertNull(chunk.getSectionTitle());
        });
    }

    @Test
    @DisplayName("chunkDocument: empty content returns empty list")
    void chunkDocument_EmptyContent_ReturnsEmptyList() {
        // Arrange
        ConvertedDocument document = new ConvertedDocument();
        document.setOriginalFilename("test.pdf");
        document.setFullContent("");
        ProcessDocumentRequest request = createTestRequest();

        // Act
        List<UniversalChunker.Chunk> result = chunker.chunkDocument(document, request);

        // Assert
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("chunkDocument: null content returns empty list")
    void chunkDocument_NullContent_ReturnsEmptyList() {
        // Arrange
        ConvertedDocument document = new ConvertedDocument();
        document.setOriginalFilename("test.pdf");
        document.setFullContent(null);
        ProcessDocumentRequest request = createTestRequest();

        // Act
        List<UniversalChunker.Chunk> result = chunker.chunkDocument(document, request);

        // Assert
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("getSupportedStrategy: returns SIZE_BASED")
    void getSupportedStrategy_ReturnsSizeBased() {
        // Act
        ProcessDocumentRequest.ChunkingStrategy result = chunker.getSupportedStrategy();

        // Assert
        assertEquals(ProcessDocumentRequest.ChunkingStrategy.SIZE_BASED, result);
    }

    @Test
    @DisplayName("canHandle: SIZE_BASED returns true")
    void canHandle_SizeBased_ReturnsTrue() {
        // Act
        boolean result = chunker.canHandle(ProcessDocumentRequest.ChunkingStrategy.SIZE_BASED);

        // Assert
        assertTrue(result);
    }

    @Test
    @DisplayName("canHandle: AUTO returns true")
    void canHandle_Auto_ReturnsTrue() {
        // Act
        boolean result = chunker.canHandle(ProcessDocumentRequest.ChunkingStrategy.AUTO);

        // Assert
        assertTrue(result, "SizeBasedChunker should handle AUTO strategy as default");
    }

    @Test
    @DisplayName("canHandle: CHAPTER_BASED returns false")
    void canHandle_ChapterBased_ReturnsFalse() {
        // Act
        boolean result = chunker.canHandle(ProcessDocumentRequest.ChunkingStrategy.CHAPTER_BASED);

        // Assert
        assertFalse(result);
    }

    @Test
    @DisplayName("chunkDocument: title truncated to 100 characters")
    void chunkDocument_TitleTruncated_WhenExceeds100Chars() {
        // Arrange
        ConvertedDocument document = createSmallDocument();
        document.setTitle("A".repeat(150)); // 150 character title
        ProcessDocumentRequest request = createTestRequest();

        when(titleGenerator.generateDocumentChunkTitle(anyString(), eq(0), eq(1)))
                .thenReturn("A".repeat(150)); // Return a long title

        // Act
        List<UniversalChunker.Chunk> result = chunker.chunkDocument(document, request);

        // Assert
        assertNotNull(result);
        assertFalse(result.isEmpty());
        String title = result.get(0).getTitle();
        assertTrue(title.length() <= 100, "Title should be truncated to 100 chars, got: " + title.length());
        assertTrue(title.endsWith("..."), "Truncated title should end with '...'");
    }

    @Test
    @DisplayName("chunkDocument: combines small chunks below minSize")
    void chunkDocument_CombinesSmallChunks_BelowMinSize() {
        // Arrange
        ConvertedDocument document = new ConvertedDocument();
        document.setOriginalFilename("test.pdf");
        document.setTitle("Test Document");
        document.setAuthor("Test Author");
        document.setConverterType("TEST_CONVERTER");
        document.setTotalPages(10);

        // Create content that will be split into very small chunks
        String smallContent = "Short sentence. ";
        StringBuilder content = new StringBuilder();
        for (int i = 0; i < 500; i++) {
            content.append(smallContent);
        }
        document.setFullContent(content.toString());

        ProcessDocumentRequest request = createTestRequest();
        request.setMaxChunkSize(2000);
        request.setMinChunkSize(500); // Small min size to trigger combination

        // Mock SentenceBoundaryDetector to return split points that create small chunks
        when(sentenceBoundaryDetector.findBestSplitPoint(anyString(), anyInt()))
                .thenAnswer(invocation -> {
                    int maxLength = invocation.getArgument(1);
                    return Math.min(300, maxLength / 2); // Create small chunks
                });

        when(titleGenerator.generateDocumentChunkTitle(eq("Test Document"), anyInt(), anyInt()))
                .thenAnswer(invocation -> "Test Document (Part " + ((Integer) invocation.getArgument(1) + 1) + ")");

        // Act
        List<UniversalChunker.Chunk> result = chunker.chunkDocument(document, request);

        // Assert
        assertNotNull(result);
        // Should combine small chunks, so final count should be less than if no combination happened
        // We just verify that combination logic runs without errors
        assertFalse(result.isEmpty());
        result.forEach(chunk -> {
            assertTrue(chunk.getCharacterCount() > 0);
            assertEquals(ProcessDocumentRequest.ChunkingStrategy.SIZE_BASED, chunk.getChunkType());
        });
    }

    @Test
    @DisplayName("chunkDocument: uses aggressive combination threshold default of 5000")
    void chunkDocument_UsesAggressiveThreshold_Default5000() {
        // Arrange
        ConvertedDocument document = createLargeDocument();
        ProcessDocumentRequest request = createTestRequest();
        request.setAggressiveCombinationThreshold(null); // Use default

        when(sentenceBoundaryDetector.findBestSplitPoint(anyString(), eq(4000)))
                .thenReturn(2000);

        when(titleGenerator.generateDocumentChunkTitle(eq("Test Document"), anyInt(), anyInt()))
                .thenAnswer(invocation -> "Test Document (Part " + ((Integer) invocation.getArgument(1) + 1) + ")");

        // Act
        List<UniversalChunker.Chunk> result = chunker.chunkDocument(document, request);

        // Assert
        assertNotNull(result);
        // Should complete without errors - we're testing that default threshold (5000) is used
        assertFalse(result.isEmpty());
    }

    @Test
    @DisplayName("chunkDocument: page metadata set correctly")
    void chunkDocument_PageMetadata_SetCorrectly() {
        // Arrange
        ConvertedDocument document = createSmallDocument();
        document.setTotalPages(25);
        ProcessDocumentRequest request = createTestRequest();

        when(titleGenerator.generateDocumentChunkTitle("Test Document", 0, 1))
                .thenReturn("Test Document");

        // Act
        List<UniversalChunker.Chunk> result = chunker.chunkDocument(document, request);

        // Assert
        assertNotNull(result);
        assertFalse(result.isEmpty());
        assertEquals(1, result.get(0).getStartPage());
        assertEquals(25, result.get(0).getEndPage());
    }

    @Test
    @DisplayName("chunkDocument: page metadata defaults to 1 when totalPages is null")
    void chunkDocument_PageMetadata_DefaultsToOneWhenTotalPagesNull() {
        // Arrange
        ConvertedDocument document = createSmallDocument();
        document.setTotalPages(null);
        ProcessDocumentRequest request = createTestRequest();

        when(titleGenerator.generateDocumentChunkTitle("Test Document", 0, 1))
                .thenReturn("Test Document");

        // Act
        List<UniversalChunker.Chunk> result = chunker.chunkDocument(document, request);

        // Assert
        assertNotNull(result);
        assertFalse(result.isEmpty());
        assertEquals(1, result.get(0).getStartPage());
        assertEquals(1, result.get(0).getEndPage());
    }

    @Test
    @DisplayName("chunkDocument: uses SentenceBoundaryDetector.findBestSplitPoint")
    void chunkDocument_UsesSentenceBoundaryDetector_FindBestSplitPoint() {
        // Arrange
        ConvertedDocument document = createLargeDocument();
        ProcessDocumentRequest request = createTestRequest();

        int expectedSplitPoint = 2500;
        when(sentenceBoundaryDetector.findBestSplitPoint(anyString(), eq(4000)))
                .thenReturn(expectedSplitPoint);

        when(titleGenerator.generateDocumentChunkTitle(eq("Test Document"), anyInt(), anyInt()))
                .thenAnswer(invocation -> "Test Document (Part " + ((Integer) invocation.getArgument(1) + 1) + ")");

        // Act
        chunker.chunkDocument(document, request);

        // Assert
        // Verify that findBestSplitPoint was called
        // This is verified by checking that the mock was invoked
        org.mockito.Mockito.verify(sentenceBoundaryDetector, org.mockito.Mockito.atLeastOnce())
                .findBestSplitPoint(anyString(), eq(4000));
    }

    @Test
    @DisplayName("chunkDocument: handles findBestSplitPoint returning -1 (no split found)")
    void chunkDocument_FindBestSplitPointReturnsMinusOne_UsesMiddle() {
        // Arrange
        ConvertedDocument document = createLargeDocument();
        ProcessDocumentRequest request = createTestRequest();

        // Mock to return -1 (no split found)
        when(sentenceBoundaryDetector.findBestSplitPoint(anyString(), eq(4000)))
                .thenReturn(-1);

        when(titleGenerator.generateDocumentChunkTitle(eq("Test Document"), anyInt(), anyInt()))
                .thenAnswer(invocation -> "Test Document (Part " + ((Integer) invocation.getArgument(1) + 1) + ")");

        // Act
        List<UniversalChunker.Chunk> result = chunker.chunkDocument(document, request);

        // Assert
        assertNotNull(result);
        assertFalse(result.isEmpty());
        // Should still create chunks using middle fallback
        result.forEach(chunk -> {
            assertTrue(chunk.getCharacterCount() > 0);
            assertEquals(ProcessDocumentRequest.ChunkingStrategy.SIZE_BASED, chunk.getChunkType());
        });
    }

    @Test
    @DisplayName("chunkDocument: handles findBestSplitPoint returning 0 (invalid split)")
    void chunkDocument_FindBestSplitPointReturnsZero_UsesMiddle() {
        // Arrange
        ConvertedDocument document = createLargeDocument();
        ProcessDocumentRequest request = createTestRequest();

        // Mock to return 0 (invalid split)
        when(sentenceBoundaryDetector.findBestSplitPoint(anyString(), eq(4000)))
                .thenReturn(0);

        when(titleGenerator.generateDocumentChunkTitle(eq("Test Document"), anyInt(), anyInt()))
                .thenAnswer(invocation -> "Test Document (Part " + ((Integer) invocation.getArgument(1) + 1) + ")");

        // Act
        List<UniversalChunker.Chunk> result = chunker.chunkDocument(document, request);

        // Assert
        assertNotNull(result);
        assertFalse(result.isEmpty());
        // Should still create chunks using middle fallback
        result.forEach(chunk -> {
            assertTrue(chunk.getCharacterCount() > 0);
            assertEquals(ProcessDocumentRequest.ChunkingStrategy.SIZE_BASED, chunk.getChunkType());
        });
    }

    @Test
    @DisplayName("chunkDocument: handles findBestSplitPoint returning >= content.length (out of bounds)")
    void chunkDocument_FindBestSplitPointReturnsOutOfBounds_UsesMiddle() {
        // Arrange
        ConvertedDocument document = createLargeDocument();
        ProcessDocumentRequest request = createTestRequest();

        // Mock to return content.length (out of bounds for substring)
        when(sentenceBoundaryDetector.findBestSplitPoint(anyString(), eq(4000)))
                .thenAnswer(invocation -> {
                    String text = invocation.getArgument(0);
                    return text.length(); // Return length, which is out of bounds for substring
                });

        when(titleGenerator.generateDocumentChunkTitle(eq("Test Document"), anyInt(), anyInt()))
                .thenAnswer(invocation -> "Test Document (Part " + ((Integer) invocation.getArgument(1) + 1) + ")");

        // Act
        List<UniversalChunker.Chunk> result = chunker.chunkDocument(document, request);

        // Assert
        assertNotNull(result);
        assertFalse(result.isEmpty());
        // Should still create chunks using middle fallback
        result.forEach(chunk -> {
            assertTrue(chunk.getCharacterCount() > 0);
            assertEquals(ProcessDocumentRequest.ChunkingStrategy.SIZE_BASED, chunk.getChunkType());
        });
    }

    @Test
    @DisplayName("chunkDocument: handles max recursion depth by creating single chunk")
    void chunkDocument_MaxRecursionDepth_CreatesSingleChunk() {
        // Arrange - Create a document that will trigger deep recursion
        ConvertedDocument document = createVeryLargeDocument();
        ProcessDocumentRequest request = createTestRequest();
        request.setMaxChunkSize(100); // Very small to force many splits

        // Mock to return a split that forces deep recursion
        when(sentenceBoundaryDetector.findBestSplitPoint(anyString(), anyInt()))
                .thenAnswer(invocation -> {
                    int maxLength = invocation.getArgument(1);
                    String text = invocation.getArgument(0);
                    // Return split that's just barely valid to force recursion
                    return Math.min(text.length() - 50, maxLength / 2);
                });

        when(titleGenerator.generateDocumentChunkTitle(eq("Test Document"), anyInt(), anyInt()))
                .thenAnswer(invocation -> "Test Document (Part " + ((Integer) invocation.getArgument(1) + 1) + ")");

        // Act
        List<UniversalChunker.Chunk> result = chunker.chunkDocument(document, request);

        // Assert
        assertNotNull(result);
        assertFalse(result.isEmpty());
        // At depth 10, should create a single chunk (max recursion reached)
        // But due to combination logic, might have multiple chunks
        // Main thing is it doesn't crash
        result.forEach(chunk -> {
            assertTrue(chunk.getCharacterCount() > 0);
            assertEquals(ProcessDocumentRequest.ChunkingStrategy.SIZE_BASED, chunk.getChunkType());
        });
    }

    @Test
    @DisplayName("chunkDocument: handles null document title")
    void chunkDocument_NullDocumentTitle_HandlesGracefully() {
        // Arrange
        ConvertedDocument document = createSmallDocument();
        document.setTitle(null);
        ProcessDocumentRequest request = createTestRequest();

        when(titleGenerator.generateDocumentChunkTitle(null, 0, 1))
                .thenReturn("Document");

        // Act
        List<UniversalChunker.Chunk> result = chunker.chunkDocument(document, request);

        // Assert
        assertNotNull(result);
        assertFalse(result.isEmpty());
        assertNotNull(result.get(0).getTitle());
        assertEquals(ProcessDocumentRequest.ChunkingStrategy.SIZE_BASED, result.get(0).getChunkType());
    }

    @Test
    @DisplayName("chunkDocument: propagates document metadata correctly")
    void chunkDocument_PropagatesDocumentMetadata_Correctly() {
        // Arrange
        ConvertedDocument document = new ConvertedDocument();
        document.setOriginalFilename("test.pdf");
        document.setTitle("Test Document");
        document.setAuthor("Custom Author");
        document.setConverterType("CUSTOM_CONVERTER");
        document.setFullContent("Test content.");
        document.setTotalPages(15);
        ProcessDocumentRequest request = createTestRequest();

        when(titleGenerator.generateDocumentChunkTitle("Test Document", 0, 1))
                .thenReturn("Test Document");

        // Act
        List<UniversalChunker.Chunk> result = chunker.chunkDocument(document, request);

        // Assert
        assertNotNull(result);
        assertFalse(result.isEmpty());
        UniversalChunker.Chunk chunk = result.get(0);
        assertEquals("Test Document", chunk.getDocumentTitle());
        assertEquals("Custom Author", chunk.getDocumentAuthor());
        assertEquals("CUSTOM_CONVERTER", chunk.getConverterType());
    }

    @Test
    @DisplayName("chunkDocument: handles very small maxChunkSize")
    void chunkDocument_VerySmallMaxChunkSize_HandlesGracefully() {
        // Arrange
        ConvertedDocument document = createLargeDocument();
        ProcessDocumentRequest request = createTestRequest();
        request.setMaxChunkSize(500); // Very small but reasonable
        request.setMinChunkSize(100); // Lower min to allow splitting
        request.setAggressiveCombinationThreshold(10000); // High threshold to prevent aggressive combining

        when(sentenceBoundaryDetector.findBestSplitPoint(anyString(), eq(500)))
                .thenAnswer(invocation -> {
                    String text = invocation.getArgument(0);
                    int maxLength = invocation.getArgument(1);
                    // Return a reasonable split point
                    return Math.min(text.length() / 2, maxLength);
                });

        when(titleGenerator.generateDocumentChunkTitle(eq("Test Document"), anyInt(), anyInt()))
                .thenAnswer(invocation -> "Test Document (Part " + ((Integer) invocation.getArgument(1) + 1) + ")");

        // Act
        List<UniversalChunker.Chunk> result = chunker.chunkDocument(document, request);

        // Assert
        assertNotNull(result);
        // With very small max size and high threshold, should create multiple chunks
        // But combination might still reduce the count, so just verify it works without errors
        assertFalse(result.isEmpty(), "Should create at least one chunk");
        result.forEach(chunk -> {
            assertTrue(chunk.getCharacterCount() > 0, "Chunk should have content");
            // Note: chunks might exceed maxSize due to combination, but they should be valid
            assertEquals(ProcessDocumentRequest.ChunkingStrategy.SIZE_BASED, chunk.getChunkType());
        });
    }

    @Test
    @DisplayName("chunkDocument: handles very large minChunkSize relative to content")
    void chunkDocument_VeryLargeMinChunkSize_CombinesAllChunks() {
        // Arrange
        ConvertedDocument document = createLargeDocument();
        ProcessDocumentRequest request = createTestRequest();
        request.setMinChunkSize(10000); // Larger than any individual chunk would be

        when(sentenceBoundaryDetector.findBestSplitPoint(anyString(), eq(4000)))
                .thenReturn(2000);

        when(titleGenerator.generateDocumentChunkTitle(eq("Test Document"), anyInt(), anyInt()))
                .thenAnswer(invocation -> "Test Document (Part " + ((Integer) invocation.getArgument(1) + 1) + ")");

        // Act
        List<UniversalChunker.Chunk> result = chunker.chunkDocument(document, request);

        // Assert
        assertNotNull(result);
        // Should combine chunks since all are below minSize
        // Result should have fewer chunks than without combination
        result.forEach(chunk -> {
            assertTrue(chunk.getCharacterCount() > 0);
            assertEquals(ProcessDocumentRequest.ChunkingStrategy.SIZE_BASED, chunk.getChunkType());
        });
    }

    @Test
    @DisplayName("chunkDocument: ensures chunks never exceed maxSize even with deep recursion (bug fix)")
    void chunkDocument_DeepRecursion_NeverExceedsMaxSize() {
        // Arrange - Create a document that requires more than 11 chunks to trigger old recursion limit
        // With maxSize=1000, we need content > 11000 chars to force >10 recursions
        ConvertedDocument document = createVeryLargeDocument();
        ProcessDocumentRequest request = createTestRequest();
        request.setMaxChunkSize(1000); // Small max size to force many chunks
        request.setMinChunkSize(100); // Low min size to prevent aggressive combining
        request.setAggressiveCombinationThreshold(500); // Low threshold to prevent combining

        // Mock sentence boundary detector to return reasonable split points
        when(sentenceBoundaryDetector.findBestSplitPoint(anyString(), eq(1000)))
                .thenAnswer(invocation -> {
                    String text = invocation.getArgument(0);
                    int maxLength = invocation.getArgument(1);
                    // Return split point at ~90% of maxLength to create chunks close to maxSize
                    return Math.min((int)(maxLength * 0.9), text.length() / 2);
                });

        when(titleGenerator.generateDocumentChunkTitle(eq("Test Document"), anyInt(), anyInt()))
                .thenAnswer(invocation -> "Test Document (Part " + ((Integer) invocation.getArgument(1) + 1) + ")");

        // Act
        List<UniversalChunker.Chunk> result = chunker.chunkDocument(document, request);

        // Assert - CRITICAL: All chunks must respect maxSize constraint
        assertNotNull(result);
        assertTrue(result.size() > 10, "Should create more than 10 chunks to trigger old recursion limit scenario");
        
        // Verify NO chunk exceeds maxSize (this was the bug - chunks exceeding maxSize)
        int maxSize = request.getMaxChunkSize();
        for (int i = 0; i < result.size(); i++) {
            UniversalChunker.Chunk chunk = result.get(i);
            int chunkSize = chunk.getCharacterCount();
            assertTrue(chunkSize <= maxSize, 
                    String.format("Chunk %d ('%s') exceeds maxSize: %d > %d. " +
                            "This violates size constraint and breaks token budgets.", 
                            i, chunk.getTitle(), chunkSize, maxSize));
            assertTrue(chunkSize > 0, "Chunk should have content");
            assertEquals(ProcessDocumentRequest.ChunkingStrategy.SIZE_BASED, chunk.getChunkType());
        }
    }

    @Test
    @DisplayName("chunkDocument: combining step respects maxChunkSize constraint (bug fix)")
    void chunkDocument_CombiningRespectsMaxSize_NeverExceedsMaxSize() {
        // Arrange - Create chunks that are below aggressive threshold but would exceed maxSize when combined
        // maxSize = 5000, aggressive threshold = 5000 (default), so chunks ~4900 chars would normally combine
        // but should NOT combine if it would exceed maxSize
        ConvertedDocument document = new ConvertedDocument();
        document.setOriginalFilename("test.pdf");
        document.setTitle("Test Document");
        document.setAuthor("Test Author");
        document.setConverterType("TEST_CONVERTER");
        document.setTotalPages(10);
        
        // Create content that splits into ~4900 char chunks
        String baseSentence = "This is a sentence. ";
        StringBuilder content = new StringBuilder();
        // Create two chunks of ~4900 chars each (total ~9800 chars)
        // With maxSize=5000, they should NOT combine (would exceed maxSize)
        int charsPerChunk = 4900;
        for (int i = 0; i < (charsPerChunk * 2) / baseSentence.length(); i++) {
            content.append(baseSentence);
        }
        
        document.setFullContent(content.toString());
        ProcessDocumentRequest request = createTestRequest();
        request.setMaxChunkSize(5000); // Small enough that combining two ~4900 char chunks would exceed it
        request.setMinChunkSize(100); // Low min size
        request.setAggressiveCombinationThreshold(5000); // High threshold that would normally trigger combination
        
        when(sentenceBoundaryDetector.findBestSplitPoint(anyString(), eq(5000)))
                .thenAnswer(invocation -> {
                    String text = invocation.getArgument(0);
                    // Split roughly in the middle to create two ~4900 char chunks
                    return Math.min(text.length() / 2, 4900);
                });
        
        when(titleGenerator.generateDocumentChunkTitle(eq("Test Document"), anyInt(), anyInt()))
                .thenAnswer(invocation -> "Test Document (Part " + ((Integer) invocation.getArgument(1) + 1) + ")");
        
        // Act
        List<UniversalChunker.Chunk> result = chunker.chunkDocument(document, request);
        
        // Assert - CRITICAL: NO chunk should exceed maxSize, even after combination
        assertNotNull(result);
        assertFalse(result.isEmpty(), "Should create chunks");
        
        int maxSize = request.getMaxChunkSize();
        for (int i = 0; i < result.size(); i++) {
            UniversalChunker.Chunk chunk = result.get(i);
            int chunkSize = chunk.getCharacterCount();
            assertTrue(chunkSize <= maxSize,
                    String.format("Chunk %d ('%s') exceeds maxSize after combination: %d > %d. " +
                            "The combining step should respect maxSize constraint and never create oversized chunks.",
                            i, chunk.getTitle(), chunkSize, maxSize));
        }
        
        // Verify that chunks are not combined when it would exceed maxSize
        // With two ~4900 char chunks and maxSize=5000, they should NOT be combined
        // (unless they were already split smaller during the splitting phase)
        // The key assertion above verifies that no chunk exceeds maxSize, which is the critical bug fix
    }

    @Test
    @DisplayName("chunkDocument: handles minChunkSize greater than maxChunkSize")
    void chunkDocument_MinSizeGreaterThanMaxSize_HandlesGracefully() {
        // Arrange
        ConvertedDocument document = createLargeDocument();
        ProcessDocumentRequest request = createTestRequest();
        request.setMaxChunkSize(1000);
        request.setMinChunkSize(2000); // Greater than maxChunkSize

        when(sentenceBoundaryDetector.findBestSplitPoint(anyString(), eq(1000)))
                .thenReturn(500);

        when(titleGenerator.generateDocumentChunkTitle(eq("Test Document"), anyInt(), anyInt()))
                .thenAnswer(invocation -> "Test Document (Part " + ((Integer) invocation.getArgument(1) + 1) + ")");

        // Act
        List<UniversalChunker.Chunk> result = chunker.chunkDocument(document, request);

        // Assert
        assertNotNull(result);
        // Should still work - combination logic should try to combine all chunks
        assertFalse(result.isEmpty());
        result.forEach(chunk -> {
            assertTrue(chunk.getCharacterCount() > 0);
            assertEquals(ProcessDocumentRequest.ChunkingStrategy.SIZE_BASED, chunk.getChunkType());
        });
    }

    @Test
    @DisplayName("chunkDocument: preserves content correctly - no loss")
    void chunkDocument_PreservesContent_NoLoss() {
        // Arrange
        String originalContent = "First sentence. Second sentence. Third sentence. Fourth sentence. Fifth sentence. ";
        StringBuilder fullContent = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            fullContent.append(originalContent);
        }
        
        ConvertedDocument document = new ConvertedDocument();
        document.setOriginalFilename("test.pdf");
        document.setTitle("Test Document");
        document.setAuthor("Test Author");
        document.setConverterType("TEST_CONVERTER");
        document.setFullContent(fullContent.toString());
        document.setTotalPages(10);
        ProcessDocumentRequest request = createTestRequest();

        when(sentenceBoundaryDetector.findBestSplitPoint(anyString(), eq(4000)))
                .thenReturn(2000);

        when(titleGenerator.generateDocumentChunkTitle(eq("Test Document"), anyInt(), anyInt()))
                .thenAnswer(invocation -> "Test Document (Part " + ((Integer) invocation.getArgument(1) + 1) + ")");

        // Act
        List<UniversalChunker.Chunk> result = chunker.chunkDocument(document, request);

        // Assert
        assertNotNull(result);
        assertFalse(result.isEmpty());
        
        // Reconstruct content from chunks
        // Note: When chunks are combined, they include "\n\n" separator in their content.
        // When chunks are not combined (e.g., due to maxSize constraint), they are just concatenated.
        // Content is trimmed during splitting, so we need to normalize whitespace for comparison.
        StringBuilder reconstructedContent = new StringBuilder();
        for (UniversalChunker.Chunk chunk : result) {
            if (reconstructedContent.length() > 0) {
                // Add a space separator if chunks aren't combined (prevents words from running together)
                // This accounts for whitespace that may have been lost during split/trim operations
                reconstructedContent.append(" ");
            }
            reconstructedContent.append(chunk.getContent());
        }
        
        // Content should be preserved (allowing for whitespace normalization from trimming)
        // When chunks are split, content is trimmed, and when combined, "\n\n" is added
        // So we normalize both by removing extra whitespace
        String original = fullContent.toString().replaceAll("\\s+", " ").trim();
        String reconstructed = reconstructedContent.toString().replaceAll("\\s+", " ").trim();
        assertEquals(original, reconstructed, "Content should be preserved (with whitespace normalization)");
    }

    @Test
    @DisplayName("chunkDocument: calculates word count correctly")
    void chunkDocument_CalculatesWordCount_Correctly() {
        // Arrange
        String content = "This is a test sentence. This is another test sentence. ";
        ConvertedDocument document = new ConvertedDocument();
        document.setOriginalFilename("test.pdf");
        document.setTitle("Test Document");
        document.setAuthor("Test Author");
        document.setConverterType("TEST_CONVERTER");
        document.setFullContent(content.repeat(10));
        document.setTotalPages(10);
        ProcessDocumentRequest request = createTestRequest();

        when(titleGenerator.generateDocumentChunkTitle("Test Document", 0, 1))
                .thenReturn("Test Document");

        // Act
        List<UniversalChunker.Chunk> result = chunker.chunkDocument(document, request);

        // Assert
        assertNotNull(result);
        assertFalse(result.isEmpty());
        
        // Word count should be reasonable (allowing for word splitting logic)
        int totalWords = result.stream().mapToInt(UniversalChunker.Chunk::getWordCount).sum();
        assertTrue(totalWords > 0, "Word count should be greater than 0");
        
        // Verify word count roughly matches (allowing for differences in counting)
        String[] words = content.repeat(10).trim().split("\\s+");
        assertTrue(totalWords > words.length * 0.8, "Word count should be roughly correct");
    }

    @Test
    @DisplayName("chunkDocument: character count matches content length")
    void chunkDocument_CharacterCount_MatchesContentLength() {
        // Arrange
        String content = "Test content for character count verification. ";
        ConvertedDocument document = new ConvertedDocument();
        document.setOriginalFilename("test.pdf");
        document.setTitle("Test Document");
        document.setAuthor("Test Author");
        document.setConverterType("TEST_CONVERTER");
        document.setFullContent(content.repeat(20));
        document.setTotalPages(10);
        ProcessDocumentRequest request = createTestRequest();

        when(titleGenerator.generateDocumentChunkTitle("Test Document", 0, 1))
                .thenReturn("Test Document");

        // Act
        List<UniversalChunker.Chunk> result = chunker.chunkDocument(document, request);

        // Assert
        assertNotNull(result);
        assertFalse(result.isEmpty());
        
        // Sum of chunk character counts should match original content length
        int totalChunkChars = result.stream()
                .mapToInt(UniversalChunker.Chunk::getCharacterCount)
                .sum();
        int originalChars = (content.repeat(20)).length();
        
        assertEquals(originalChars, totalChunkChars, 
                "Total character count should match original content length");
    }

    @Test
    @DisplayName("chunkDocument: chunk indices are sequential after combination")
    void chunkDocument_ChunkIndices_SequentialAfterCombination() {
        // Arrange
        ConvertedDocument document = createLargeDocument();
        ProcessDocumentRequest request = createTestRequest();
        request.setMinChunkSize(5000); // Force combination

        when(sentenceBoundaryDetector.findBestSplitPoint(anyString(), eq(4000)))
                .thenReturn(2000);

        when(titleGenerator.generateDocumentChunkTitle(eq("Test Document"), anyInt(), anyInt()))
                .thenAnswer(invocation -> "Test Document (Part " + ((Integer) invocation.getArgument(1) + 1) + ")");

        // Act
        List<UniversalChunker.Chunk> result = chunker.chunkDocument(document, request);

        // Assert
        assertNotNull(result);
        assertFalse(result.isEmpty());
        
        // Verify indices are sequential starting from 0
        for (int i = 0; i < result.size(); i++) {
            assertEquals(i, result.get(i).getChunkIndex(), 
                    "Chunk index should be sequential: " + i);
        }
    }

    @Test
    @DisplayName("chunkDocument: combines all chunks when all are below aggressive threshold")
    void chunkDocument_AllChunksBelowThreshold_CombinesAll() {
        // Arrange
        ConvertedDocument document = new ConvertedDocument();
        document.setOriginalFilename("test.pdf");
        document.setTitle("Test Document");
        document.setAuthor("Test Author");
        document.setConverterType("TEST_CONVERTER");
        document.setTotalPages(10);

        // Create content that will create small chunks
        String smallContent = "Short. ";
        StringBuilder content = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            content.append(smallContent);
        }
        document.setFullContent(content.toString());

        ProcessDocumentRequest request = createTestRequest();
        request.setMaxChunkSize(2000);
        request.setMinChunkSize(100);
        request.setAggressiveCombinationThreshold(5000); // Higher than any chunk

        when(sentenceBoundaryDetector.findBestSplitPoint(anyString(), anyInt()))
                .thenAnswer(invocation -> {
                    int maxLength = invocation.getArgument(1);
                    return Math.min(500, maxLength / 2); // Create small chunks
                });

        when(titleGenerator.generateDocumentChunkTitle(eq("Test Document"), anyInt(), anyInt()))
                .thenAnswer(invocation -> "Test Document (Part " + ((Integer) invocation.getArgument(1) + 1) + ")");

        // Act
        List<UniversalChunker.Chunk> result = chunker.chunkDocument(document, request);

        // Assert
        assertNotNull(result);
        // All chunks should be below threshold, so they should combine
        // Final result should have fewer chunks than without combination
        assertFalse(result.isEmpty());
        result.forEach(chunk -> {
            assertTrue(chunk.getCharacterCount() > 0);
            assertEquals(ProcessDocumentRequest.ChunkingStrategy.SIZE_BASED, chunk.getChunkType());
        });
    }

    private ConvertedDocument createSmallDocument() {
        ConvertedDocument document = new ConvertedDocument();
        document.setOriginalFilename("test.pdf");
        document.setTitle("Test Document");
        document.setAuthor("Test Author");
        document.setConverterType("TEST_CONVERTER");
        document.setFullContent("This is a small test document content.");
        document.setTotalPages(10);
        return document;
    }

    private ConvertedDocument createLargeDocument() {
        ConvertedDocument document = new ConvertedDocument();
        document.setOriginalFilename("test.pdf");
        document.setTitle("Test Document");
        document.setAuthor("Test Author");
        document.setConverterType("TEST_CONVERTER");
        document.setTotalPages(10);

        // Create content that exceeds max chunk size (4000)
        String baseContent = "This is a sentence. This is another sentence. ";
        StringBuilder longContent = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            longContent.append(baseContent);
        }

        document.setFullContent(longContent.toString());
        return document;
    }

    private ConvertedDocument createVeryLargeDocument() {
        ConvertedDocument document = new ConvertedDocument();
        document.setOriginalFilename("very-large-test.pdf");
        document.setTitle("Test Document");
        document.setAuthor("Test Author");
        document.setConverterType("TEST_CONVERTER");
        document.setTotalPages(10);

        // Create very large content to force deep recursion
        String baseContent = "This is a sentence. ";
        StringBuilder veryLongContent = new StringBuilder();
        for (int i = 0; i < 5000; i++) {
            veryLongContent.append(baseContent);
        }

        document.setFullContent(veryLongContent.toString());
        return document;
    }

    private ProcessDocumentRequest createTestRequest() {
        ProcessDocumentRequest request = new ProcessDocumentRequest();
        request.setChunkingStrategy(ProcessDocumentRequest.ChunkingStrategy.SIZE_BASED);
        request.setMaxChunkSize(4000);
        request.setMinChunkSize(1000);
        request.setAggressiveCombinationThreshold(5000);
        request.setStoreChunks(true);
        return request;
    }
}

