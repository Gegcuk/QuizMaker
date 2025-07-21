package uk.gegc.quizmaker.service.document.chunker;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gegc.quizmaker.dto.document.ProcessDocumentRequest;
import uk.gegc.quizmaker.exception.DocumentProcessingException;
import uk.gegc.quizmaker.service.document.converter.ConvertedDocument;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@Execution(ExecutionMode.CONCURRENT)
@ExtendWith(MockitoExtension.class)
class UniversalChunkingServiceTest {

    @Mock
    private UniversalChunker mockChunker;

    private UniversalChunkingService chunkingService;

    @BeforeEach
    void setUp() {
        chunkingService = new UniversalChunkingService(Arrays.asList(mockChunker));
    }

    @Test
    void chunkDocument_Success() throws Exception {
        // Arrange
        ConvertedDocument document = createTestConvertedDocument();
        ProcessDocumentRequest request = createTestRequest();
        List<UniversalChunker.Chunk> expectedChunks = createTestChunks();

        when(mockChunker.canHandle(ProcessDocumentRequest.ChunkingStrategy.CHAPTER_BASED)).thenReturn(true);
        when(mockChunker.chunkDocument(eq(document), eq(request))).thenReturn(expectedChunks);

        // Act
        List<UniversalChunker.Chunk> result = chunkingService.chunkDocument(document, request);

        // Assert
        assertNotNull(result);
        assertEquals(expectedChunks.size(), result.size());
        assertEquals(expectedChunks.get(0).getTitle(), result.get(0).getTitle());
    }

    @Test
    void chunkDocument_NoChunkerFound_ThrowsException() {
        // Arrange
        ConvertedDocument document = createTestConvertedDocument();
        ProcessDocumentRequest request = createTestRequest();

        when(mockChunker.canHandle(ProcessDocumentRequest.ChunkingStrategy.CHAPTER_BASED)).thenReturn(false);

        // Act & Assert
        DocumentProcessingException exception = assertThrows(
                DocumentProcessingException.class,
                () -> chunkingService.chunkDocument(document, request)
        );

        assertTrue(exception.getMessage().contains("No chunker found for strategy"));
    }

    @Test
    void chunkDocument_ChunkerThrowsException_ThrowsDocumentProcessingException() throws Exception {
        // Arrange
        ConvertedDocument document = createTestConvertedDocument();
        ProcessDocumentRequest request = createTestRequest();

        when(mockChunker.canHandle(ProcessDocumentRequest.ChunkingStrategy.CHAPTER_BASED)).thenReturn(true);
        when(mockChunker.chunkDocument(eq(document), eq(request)))
                .thenThrow(new RuntimeException("Chunker error"));

        // Act & Assert
        DocumentProcessingException exception = assertThrows(
                DocumentProcessingException.class,
                () -> chunkingService.chunkDocument(document, request)
        );

        assertTrue(exception.getMessage().contains("Failed to chunk document"));
        assertNotNull(exception.getCause());
    }

    @Test
    void getSupportedStrategies_ReturnsFromChunkers() {
        // Arrange
        when(mockChunker.getSupportedStrategy()).thenReturn(ProcessDocumentRequest.ChunkingStrategy.CHAPTER_BASED);

        // Act
        List<ProcessDocumentRequest.ChunkingStrategy> result = chunkingService.getSupportedStrategies();

        // Assert
        assertEquals(Arrays.asList(ProcessDocumentRequest.ChunkingStrategy.CHAPTER_BASED), result);
    }

    @Test
    void isStrategySupported_StrategySupported_ReturnsTrue() {
        // Arrange
        when(mockChunker.canHandle(ProcessDocumentRequest.ChunkingStrategy.CHAPTER_BASED)).thenReturn(true);

        // Act
        boolean result = chunkingService.isStrategySupported(ProcessDocumentRequest.ChunkingStrategy.CHAPTER_BASED);

        // Assert
        assertTrue(result);
    }

    @Test
    void isStrategySupported_StrategyNotSupported_ReturnsFalse() {
        // Arrange
        when(mockChunker.canHandle(ProcessDocumentRequest.ChunkingStrategy.SIZE_BASED)).thenReturn(false);

        // Act
        boolean result = chunkingService.isStrategySupported(ProcessDocumentRequest.ChunkingStrategy.SIZE_BASED);

        // Assert
        assertFalse(result);
    }

    @Test
    void getChunkerInfo_ReturnsChunkerInformation() {
        // Arrange
        when(mockChunker.getSupportedStrategy()).thenReturn(ProcessDocumentRequest.ChunkingStrategy.CHAPTER_BASED);
        when(mockChunker.canHandle(ProcessDocumentRequest.ChunkingStrategy.AUTO)).thenReturn(true);

        // Act
        List<UniversalChunkingService.ChunkerInfo> result = chunkingService.getChunkerInfo();

        // Assert
        assertNotNull(result);
        assertEquals(1, result.size());
        assertTrue(result.get(0).chunkerType().contains("Mock")); // Mockito creates dynamic class names
        assertEquals(ProcessDocumentRequest.ChunkingStrategy.CHAPTER_BASED, result.get(0).supportedStrategy());
        assertTrue(result.get(0).supportsAuto());
    }

    private ConvertedDocument createTestConvertedDocument() {
        ConvertedDocument document = new ConvertedDocument();
        document.setOriginalFilename("test.pdf");
        document.setFullContent("This is test content for chunking.");
        document.setTitle("Test Document");
        document.setAuthor("Test Author");
        document.setConverterType("PDF_DOCUMENT_CONVERTER");
        return document;
    }

    private ProcessDocumentRequest createTestRequest() {
        ProcessDocumentRequest request = new ProcessDocumentRequest();
        request.setChunkingStrategy(ProcessDocumentRequest.ChunkingStrategy.CHAPTER_BASED);
        request.setMaxChunkSize(4000);
        request.setStoreChunks(true);
        return request;
    }

    private List<UniversalChunker.Chunk> createTestChunks() {
        UniversalChunker.Chunk chunk = new UniversalChunker.Chunk();
        chunk.setTitle("Test Chunk");
        chunk.setContent("This is test content.");
        chunk.setChunkIndex(0);
        chunk.setCharacterCount(20);
        chunk.setWordCount(4);
        chunk.setChunkType(ProcessDocumentRequest.ChunkingStrategy.CHAPTER_BASED);
        return Arrays.asList(chunk);
    }
} 