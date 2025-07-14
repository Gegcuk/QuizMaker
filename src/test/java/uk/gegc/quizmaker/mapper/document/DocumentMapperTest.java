package uk.gegc.quizmaker.mapper.document;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gegc.quizmaker.dto.document.DocumentChunkDto;
import uk.gegc.quizmaker.dto.document.DocumentDto;
import uk.gegc.quizmaker.model.document.Document;
import uk.gegc.quizmaker.model.document.DocumentChunk;
import uk.gegc.quizmaker.model.user.User;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class DocumentMapperTest {

    @InjectMocks
    private DocumentMapper documentMapper;

    private User testUser;
    private Document testDocument;
    private DocumentChunk testChunk;

    @BeforeEach
    void setUp() {
        testUser = new User();
        testUser.setId(UUID.randomUUID());
        testUser.setUsername("testuser");

        testDocument = new Document();
        testDocument.setId(UUID.randomUUID());
        testDocument.setOriginalFilename("test.pdf");
        testDocument.setContentType("application/pdf");
        testDocument.setFileSize(1000L);
        testDocument.setFilePath("/uploads/test.pdf");
        testDocument.setStatus(Document.DocumentStatus.PROCESSED);
        testDocument.setUploadedAt(LocalDateTime.now());
        testDocument.setProcessedAt(LocalDateTime.now());
        testDocument.setUploadedBy(testUser);
        testDocument.setTitle("Test Document");
        testDocument.setAuthor("Test Author");
        testDocument.setTotalPages(10);
        testDocument.setTotalChunks(5);

        testChunk = new DocumentChunk();
        testChunk.setId(UUID.randomUUID());
        testChunk.setChunkIndex(0);
        testChunk.setTitle("Chapter 1");
        testChunk.setContent("This is chapter 1 content.");
        testChunk.setStartPage(1);
        testChunk.setEndPage(5);
        testChunk.setWordCount(6);
        testChunk.setCharacterCount(25);
        testChunk.setCreatedAt(LocalDateTime.now());
        testChunk.setDocument(testDocument);
        testChunk.setChapterTitle("Chapter 1");
        testChunk.setChapterNumber(1);
        testChunk.setChunkType(DocumentChunk.ChunkType.CHAPTER);
    }

    @Test
    void toDto_Document_Success() {
        // Act
        DocumentDto result = documentMapper.toDto(testDocument);

        // Assert
        assertNotNull(result);
        assertEquals(testDocument.getId(), result.getId());
        assertEquals(testDocument.getOriginalFilename(), result.getOriginalFilename());
        assertEquals(testDocument.getContentType(), result.getContentType());
        assertEquals(testDocument.getFileSize(), result.getFileSize());
        assertEquals(testDocument.getStatus(), result.getStatus());
        assertEquals(testDocument.getUploadedAt(), result.getUploadedAt());
        assertEquals(testDocument.getProcessedAt(), result.getProcessedAt());
        assertEquals(testDocument.getTitle(), result.getTitle());
        assertEquals(testDocument.getAuthor(), result.getAuthor());
        assertEquals(testDocument.getTotalPages(), result.getTotalPages());
        assertEquals(testDocument.getTotalChunks(), result.getTotalChunks());
    }

    @Test
    void toDto_DocumentWithNullValues_HandlesGracefully() {
        // Arrange
        Document document = new Document();
        document.setId(UUID.randomUUID());
        document.setOriginalFilename("test.pdf");

        // Act
        DocumentDto result = documentMapper.toDto(document);

        // Assert
        assertNotNull(result);
        assertEquals(document.getId(), result.getId());
        assertEquals(document.getOriginalFilename(), result.getOriginalFilename());
        assertNull(result.getContentType());
        assertNull(result.getFileSize());
        assertNull(result.getStatus());
        assertNull(result.getUploadedAt());
        assertNull(result.getProcessedAt());
        assertNull(result.getTitle());
        assertNull(result.getAuthor());
        assertNull(result.getTotalPages());
        assertNull(result.getTotalChunks());
    }

    @Test
    void toChunkDto_DocumentChunk_Success() {
        // Act
        DocumentChunkDto result = documentMapper.toChunkDto(testChunk);

        // Assert
        assertNotNull(result);
        assertEquals(testChunk.getId(), result.getId());
        assertEquals(testChunk.getChunkIndex(), result.getChunkIndex());
        assertEquals(testChunk.getTitle(), result.getTitle());
        assertEquals(testChunk.getContent(), result.getContent());
        assertEquals(testChunk.getStartPage(), result.getStartPage());
        assertEquals(testChunk.getEndPage(), result.getEndPage());
        assertEquals(testChunk.getWordCount(), result.getWordCount());
        assertEquals(testChunk.getCharacterCount(), result.getCharacterCount());
        assertEquals(testChunk.getCreatedAt(), result.getCreatedAt());
        assertEquals(testChunk.getChapterTitle(), result.getChapterTitle());
        assertEquals(testChunk.getChapterNumber(), result.getChapterNumber());
        assertEquals(testChunk.getChunkType(), result.getChunkType());
    }

    @Test
    void toChunkDto_DocumentChunkWithNullValues_HandlesGracefully() {
        // Arrange
        DocumentChunk chunk = new DocumentChunk();
        chunk.setId(UUID.randomUUID());
        chunk.setChunkIndex(0);
        chunk.setTitle("Test Chunk");

        // Act
        DocumentChunkDto result = documentMapper.toChunkDto(chunk);

        // Assert
        assertNotNull(result);
        assertEquals(chunk.getId(), result.getId());
        assertEquals(chunk.getChunkIndex(), result.getChunkIndex());
        assertEquals(chunk.getTitle(), result.getTitle());
        assertNull(result.getContent());
        assertNull(result.getStartPage());
        assertNull(result.getEndPage());
        assertNull(result.getWordCount());
        assertNull(result.getCharacterCount());
        assertNull(result.getCreatedAt());
        assertNull(result.getChapterTitle());
        assertNull(result.getChapterNumber());
        assertNull(result.getChunkType());
    }

    @Test
    void toDto_DocumentWithChunks_Success() {
        // Arrange
        testDocument.setChunks(Arrays.asList(testChunk));

        // Act
        DocumentDto result = documentMapper.toDto(testDocument);

        // Assert
        assertNotNull(result);
        assertNotNull(result.getChunks());
        assertEquals(1, result.getChunks().size());
        assertEquals(testChunk.getId(), result.getChunks().get(0).getId());
        assertEquals(testChunk.getTitle(), result.getChunks().get(0).getTitle());
    }

    @Test
    void toDto_DocumentWithNullChunks_HandlesGracefully() {
        // Arrange
        testDocument.setChunks(null);

        // Act
        DocumentDto result = documentMapper.toDto(testDocument);

        // Assert
        assertNotNull(result);
        assertNull(result.getChunks());
    }

    private DocumentChunk createTestChunk(int index, String title) {
        DocumentChunk chunk = new DocumentChunk();
        chunk.setId(UUID.randomUUID());
        chunk.setChunkIndex(index);
        chunk.setTitle(title);
        chunk.setContent("This is " + title.toLowerCase() + " content.");
        chunk.setStartPage(index * 2 + 1);
        chunk.setEndPage(index * 2 + 2);
        chunk.setWordCount(5);
        chunk.setCharacterCount(20);
        chunk.setCreatedAt(LocalDateTime.now());
        chunk.setDocument(testDocument);
        chunk.setChapterTitle(title);
        chunk.setChapterNumber(index + 1);
        chunk.setChunkType(DocumentChunk.ChunkType.CHAPTER);
        return chunk;
    }
} 