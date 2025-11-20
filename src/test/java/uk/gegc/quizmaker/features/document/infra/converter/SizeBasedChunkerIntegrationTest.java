package uk.gegc.quizmaker.features.document.infra.converter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import uk.gegc.quizmaker.features.document.api.dto.ProcessDocumentRequest;
import uk.gegc.quizmaker.features.document.application.ConvertedDocument;
import uk.gegc.quizmaker.features.document.application.DocumentChunkingService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@DisplayName("SizeBasedChunker Integration Tests")
class SizeBasedChunkerIntegrationTest {

    @Autowired
    private DocumentChunkingService documentChunkingService;

    @Test
    @DisplayName("DocumentChunkingService reports SIZE_BASED in getSupportedStrategies")
    void documentChunkingService_ReportsSizeBased_InSupportedStrategies() {
        // Act
        List<ProcessDocumentRequest.ChunkingStrategy> supportedStrategies =
                documentChunkingService.getSupportedStrategies();

        // Assert
        assertNotNull(supportedStrategies);
        assertTrue(supportedStrategies.contains(ProcessDocumentRequest.ChunkingStrategy.SIZE_BASED),
                "SIZE_BASED should be in supported strategies: " + supportedStrategies);
    }

    @Test
    @DisplayName("DocumentChunkingService uses SizeBasedChunker when chunkingStrategy is SIZE_BASED")
    void documentChunkingService_UsesSizeBasedChunker_WhenStrategyIsSizeBased() {
        // Arrange
        ConvertedDocument document = createTestDocument();
        ProcessDocumentRequest request = new ProcessDocumentRequest();
        request.setChunkingStrategy(ProcessDocumentRequest.ChunkingStrategy.SIZE_BASED);
        request.setMaxChunkSize(4000);
        request.setMinChunkSize(1000);
        request.setStoreChunks(true);

        // Act
        List<UniversalChunker.Chunk> chunks = documentChunkingService.chunkDocument(document, request);

        // Assert
        assertNotNull(chunks);
        assertFalse(chunks.isEmpty());

        // Verify all chunks are SIZE_BASED
        chunks.forEach(chunk -> {
            assertEquals(ProcessDocumentRequest.ChunkingStrategy.SIZE_BASED, chunk.getChunkType(),
                    "All chunks should have SIZE_BASED type");
            assertNull(chunk.getChapterTitle(), "Chapter title should be null for SIZE_BASED chunks");
            assertNull(chunk.getSectionTitle(), "Section title should be null for SIZE_BASED chunks");
            assertNotNull(chunk.getTitle(), "Chunk title should not be null");
            assertTrue(chunk.getCharacterCount() > 0, "Chunk should have content");
        });
    }

    @Test
    @DisplayName("DocumentChunkingService properly chunks large document with SIZE_BASED strategy")
    void documentChunkingService_ChunksLargeDocument_WithSizeBasedStrategy() {
        // Arrange
        ConvertedDocument document = createLargeDocument();
        ProcessDocumentRequest request = new ProcessDocumentRequest();
        request.setChunkingStrategy(ProcessDocumentRequest.ChunkingStrategy.SIZE_BASED);
        request.setMaxChunkSize(5000);
        request.setMinChunkSize(1000);
        request.setStoreChunks(true);

        // Act
        List<UniversalChunker.Chunk> chunks = documentChunkingService.chunkDocument(document, request);

        // Assert
        assertNotNull(chunks);
        assertTrue(chunks.size() > 1, "Large document should be split into multiple chunks");

        // Verify chunks respect max size
        chunks.forEach(chunk -> {
            assertTrue(chunk.getCharacterCount() <= request.getMaxChunkSize() * 1.1, // Allow small overhead
                    "Chunk size should respect max chunk size: " + chunk.getCharacterCount());
            assertEquals(ProcessDocumentRequest.ChunkingStrategy.SIZE_BASED, chunk.getChunkType());
            assertEquals(1, chunk.getStartPage(), "Start page should be 1");
            assertEquals(20, chunk.getEndPage(), "End page should match document total pages");
        });

        // Verify chunk indices are sequential
        for (int i = 0; i < chunks.size(); i++) {
            assertEquals(i, chunks.get(i).getChunkIndex(),
                    "Chunk indices should be sequential starting from 0");
        }
    }

    @Test
    @DisplayName("DocumentChunkingService isStrategySupported returns true for SIZE_BASED")
    void documentChunkingService_IsStrategySupported_ReturnsTrueForSizeBased() {
        // Act
        boolean isSupported = documentChunkingService.isStrategySupported(
                ProcessDocumentRequest.ChunkingStrategy.SIZE_BASED);

        // Assert
        assertTrue(isSupported, "SIZE_BASED strategy should be supported");
    }

    @Test
    @DisplayName("SizeBasedChunker is discovered and available in DocumentChunkingService")
    void sizeBasedChunker_IsDiscovered_ByDocumentChunkingService() {
        // Act
        List<DocumentChunkingService.ChunkerInfo> chunkerInfo =
                documentChunkingService.getChunkerInfo();

        // Assert
        assertNotNull(chunkerInfo);
        assertTrue(chunkerInfo.stream()
                        .anyMatch(info -> info.supportedStrategy() ==
                                ProcessDocumentRequest.ChunkingStrategy.SIZE_BASED),
                "SizeBasedChunker should be in chunker info list");
    }

    private ConvertedDocument createTestDocument() {
        ConvertedDocument document = new ConvertedDocument();
        document.setOriginalFilename("test.pdf");
        document.setTitle("Test Document");
        document.setAuthor("Test Author");
        document.setConverterType("TEST_CONVERTER");
        document.setFullContent("This is a test document content for chunking. " +
                "It contains multiple sentences. " +
                "Each sentence should be properly handled by the chunker. " +
                "The content should be split appropriately based on size constraints.");
        document.setTotalPages(10);
        return document;
    }

    private ConvertedDocument createLargeDocument() {
        ConvertedDocument document = new ConvertedDocument();
        document.setOriginalFilename("large-test.pdf");
        document.setTitle("Large Test Document");
        document.setAuthor("Test Author");
        document.setConverterType("TEST_CONVERTER");
        document.setTotalPages(20);

        // Create content that exceeds max chunk size to force splitting
        String baseContent = "This is a sentence in a large document. " +
                "The document contains many sentences. " +
                "Each sentence provides context for the next. " +
                "The chunker should split this content appropriately. ";

        StringBuilder longContent = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            longContent.append(baseContent);
        }

        document.setFullContent(longContent.toString());
        return document;
    }
}

