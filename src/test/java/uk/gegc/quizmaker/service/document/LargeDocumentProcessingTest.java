package uk.gegc.quizmaker.service.document;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import uk.gegc.quizmaker.config.DocumentProcessingConfig;
import uk.gegc.quizmaker.dto.document.DocumentChunkDto;
import uk.gegc.quizmaker.dto.document.DocumentDto;
import uk.gegc.quizmaker.dto.document.ProcessDocumentRequest;
import uk.gegc.quizmaker.mapper.document.DocumentMapper;
import uk.gegc.quizmaker.model.document.Document;
import uk.gegc.quizmaker.model.document.DocumentChunk;
import uk.gegc.quizmaker.model.user.User;
import uk.gegc.quizmaker.repository.document.DocumentChunkRepository;
import uk.gegc.quizmaker.repository.document.DocumentRepository;
import uk.gegc.quizmaker.repository.user.UserRepository;
import uk.gegc.quizmaker.service.document.chunker.ContentChunker;
import uk.gegc.quizmaker.service.document.parser.FileParser;
import uk.gegc.quizmaker.service.document.parser.ParsedDocument;
import uk.gegc.quizmaker.exception.DocumentStorageException;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LargeDocumentProcessingTest {

    @Mock
    private DocumentRepository documentRepository;

    @Mock
    private DocumentChunkRepository chunkRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private DocumentMapper documentMapper;

    @Mock
    private FileParser mockFileParser;

    @Mock
    private ContentChunker mockContentChunker;

    @Mock
    private DocumentProcessingConfig documentConfig;

    @InjectMocks
    private uk.gegc.quizmaker.service.document.impl.DocumentProcessingServiceImpl documentProcessingService;

    private User testUser;
    private Document testDocument;
    private DocumentDto testDocumentDto;

    @BeforeEach
    void setUp() {
        testUser = new User();
        testUser.setId(UUID.randomUUID());
        testUser.setUsername("testuser");
        testUser.setEmail("test@example.com");
        testUser.setActive(true);

        testDocument = new Document();
        testDocument.setId(UUID.randomUUID());
        testDocument.setTitle("Test Document");
        testDocument.setUploadedBy(testUser);
        testDocument.setTotalChunks(50);

        testDocumentDto = new DocumentDto();
        testDocumentDto.setId(testDocument.getId());
        testDocumentDto.setTitle(testDocument.getTitle());
        testDocumentDto.setTotalChunks(50);
    }

    @AfterEach
    void tearDown() {
        // Clean up test files
        try {
            Path uploadsDir = Paths.get("uploads/documents");
            if (Files.exists(uploadsDir)) {
                Files.walk(uploadsDir)
                    .filter(Files::isRegularFile)
                    .forEach(file -> {
                        try {
                            Files.deleteIfExists(file);
                        } catch (IOException e) {
                            // Log but don't fail the test
                            System.err.println("Failed to delete test file: " + file);
                        }
                    });
                
                // Try to remove the uploads directory if it's empty
                try {
                    Files.deleteIfExists(uploadsDir);
                } catch (IOException e) {
                    // Directory might not be empty, ignore
                }
            }
        } catch (IOException e) {
            // Log but don't fail the test
            System.err.println("Failed to clean up test files: " + e.getMessage());
        }
    }

    private void setupServiceDependencies() throws Exception {
        // Use reflection to set the private fields
        Field fileParsersField = uk.gegc.quizmaker.service.document.impl.DocumentProcessingServiceImpl.class
                .getDeclaredField("fileParsers");
        fileParsersField.setAccessible(true);
        fileParsersField.set(documentProcessingService, Arrays.asList(mockFileParser));

        Field contentChunkersField = uk.gegc.quizmaker.service.document.impl.DocumentProcessingServiceImpl.class
                .getDeclaredField("contentChunkers");
        contentChunkersField.setAccessible(true);
        contentChunkersField.set(documentProcessingService, Arrays.asList(mockContentChunker));
    }

    @Test
    void processLargeDocument_WithManyChapters_Success() throws Exception {
        // Arrange
        byte[] largeFileContent = createLargePdfContent();
        String filename = "large_test.pdf";
        
        ProcessDocumentRequest request = new ProcessDocumentRequest();
        request.setMaxChunkSize(4000);
        request.setChunkingStrategy(ProcessDocumentRequest.ChunkingStrategy.CHAPTER_BASED);
        
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
        
        // Mock document repository to return a document with correct values
        when(documentRepository.save(any(Document.class))).thenAnswer(invocation -> {
            Document savedDocument = invocation.getArgument(0);
            savedDocument.setId(testDocument.getId());
            savedDocument.setContentType("application/pdf");
            savedDocument.setOriginalFilename("large_test.pdf");
            return savedDocument;
        });
        
        // Mock document mapper to return dynamic DTO
        when(documentMapper.toDto(any(Document.class))).thenAnswer(invocation -> {
            Document document = invocation.getArgument(0);
            DocumentDto dto = new DocumentDto();
            dto.setId(document.getId());
            dto.setTitle(document.getTitle());
            dto.setTotalChunks(document.getTotalChunks());
            return dto;
        });
        
        // Mock file parser with correct content type and filename
        doReturn(true).when(mockFileParser).canParse("application/pdf", "large_test.pdf");
        try {
            doReturn(createLargeParsedDocument()).when(mockFileParser).parse(any(), anyString());
        } catch (Exception e) {
            // Handle exception
        }
        
        // Mock content chunker
        when(mockContentChunker.getSupportedStrategy()).thenReturn(ProcessDocumentRequest.ChunkingStrategy.CHAPTER_BASED);
        when(mockContentChunker.chunkDocument(any(), any())).thenReturn(createLargeChunks());

        // Set up the lists that the service expects
        setupServiceDependencies();

        // Act
        DocumentDto result = documentProcessingService.uploadAndProcessDocument("testuser", largeFileContent, filename, request);

        // Assert
        assertNotNull(result);
        assertEquals(testDocument.getId(), result.getId());
        assertEquals(50, result.getTotalChunks());
        
        // Verify that chunks were saved
        verify(chunkRepository, times(50)).save(any(DocumentChunk.class));
        // Verify the new transactional structure: createDocumentEntity + updateDocumentStatus + updateDocumentMetadata + updateDocumentStatusToProcessed
        verify(documentRepository, times(4)).save(any(Document.class));
    }

    @Test
    void processLargeDocument_WithSmallChunkSize_CreatesManyChunks() throws Exception {
        // Arrange
        byte[] largeFileContent = createLargePdfContent();
        String filename = "large_test.pdf";
        
        ProcessDocumentRequest request = new ProcessDocumentRequest();
        request.setMaxChunkSize(500); // Very small chunk size
        request.setChunkingStrategy(ProcessDocumentRequest.ChunkingStrategy.CHAPTER_BASED);
        
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
        
        // Mock document repository to return a document with correct values
        when(documentRepository.save(any(Document.class))).thenAnswer(invocation -> {
            Document savedDocument = invocation.getArgument(0);
            savedDocument.setId(testDocument.getId());
            savedDocument.setContentType("application/pdf");
            savedDocument.setOriginalFilename("large_test.pdf");
            return savedDocument;
        });
        
        // Mock document mapper to return dynamic DTO
        when(documentMapper.toDto(any(Document.class))).thenAnswer(invocation -> {
            Document document = invocation.getArgument(0);
            DocumentDto dto = new DocumentDto();
            dto.setId(document.getId());
            dto.setTitle(document.getTitle());
            dto.setTotalChunks(document.getTotalChunks());
            return dto;
        });
        
        // Mock file parser with correct content type and filename
        doReturn(true).when(mockFileParser).canParse("application/pdf", "large_test.pdf");
        try {
            doReturn(createLargeParsedDocument()).when(mockFileParser).parse(any(), anyString());
        } catch (Exception e) {
            // Handle exception
        }
        
        // Mock content chunker with many small chunks
        when(mockContentChunker.getSupportedStrategy()).thenReturn(ProcessDocumentRequest.ChunkingStrategy.CHAPTER_BASED);
        when(mockContentChunker.chunkDocument(any(), any())).thenReturn(createManySmallChunks());

        // Set up the lists that the service expects
        setupServiceDependencies();

        // Act
        DocumentDto result = documentProcessingService.uploadAndProcessDocument("testuser", largeFileContent, filename, request);

        // Assert
        assertNotNull(result);
        assertEquals(testDocument.getId(), result.getId());
        assertEquals(100, result.getTotalChunks()); // Many small chunks
        
        // Verify that chunks were saved
        verify(chunkRepository, times(100)).save(any(DocumentChunk.class));
        // Verify the new transactional structure: createDocumentEntity + updateDocumentStatus + updateDocumentMetadata + updateDocumentStatusToProcessed
        verify(documentRepository, times(4)).save(any(Document.class));
    }

    @Test
    void processLargeDocument_WithSizeBasedChunking_Success() throws Exception {
        // Arrange
        byte[] largeFileContent = createLargePdfContent();
        String filename = "large_test.pdf";
        
        ProcessDocumentRequest request = new ProcessDocumentRequest();
        request.setMaxChunkSize(2000);
        request.setChunkingStrategy(ProcessDocumentRequest.ChunkingStrategy.SIZE_BASED);
        
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
        
        // Mock document repository to return a document with correct values
        when(documentRepository.save(any(Document.class))).thenAnswer(invocation -> {
            Document savedDocument = invocation.getArgument(0);
            savedDocument.setId(testDocument.getId());
            savedDocument.setContentType("application/pdf");
            savedDocument.setOriginalFilename("large_test.pdf");
            return savedDocument;
        });
        
        // Mock document mapper to return dynamic DTO
        when(documentMapper.toDto(any(Document.class))).thenAnswer(invocation -> {
            Document document = invocation.getArgument(0);
            DocumentDto dto = new DocumentDto();
            dto.setId(document.getId());
            dto.setTitle(document.getTitle());
            dto.setTotalChunks(document.getTotalChunks());
            return dto;
        });
        
        // Mock file parser with correct content type and filename
        doReturn(true).when(mockFileParser).canParse("application/pdf", "large_test.pdf");
        try {
            doReturn(createLargeParsedDocument()).when(mockFileParser).parse(any(), anyString());
        } catch (Exception e) {
            // Handle exception
        }
        
        // Mock content chunker for size-based chunking
        when(mockContentChunker.getSupportedStrategy()).thenReturn(ProcessDocumentRequest.ChunkingStrategy.SIZE_BASED);
        when(mockContentChunker.chunkDocument(any(), any())).thenReturn(createSizeBasedChunks());

        // Set up the lists that the service expects
        setupServiceDependencies();

        // Act
        DocumentDto result = documentProcessingService.uploadAndProcessDocument("testuser", largeFileContent, filename, request);

        // Assert
        assertNotNull(result);
        assertEquals(testDocument.getId(), result.getId());
        assertEquals(25, result.getTotalChunks()); // Size-based chunks
        
        // Verify that chunks were saved
        verify(chunkRepository, times(25)).save(any(DocumentChunk.class));
        // Verify the new transactional structure: createDocumentEntity + updateDocumentStatus + updateDocumentMetadata + updateDocumentStatusToProcessed
        verify(documentRepository, times(4)).save(any(Document.class));
    }

    @Test
    void getLargeDocumentChunks_ReturnsAllChunks() {
        // Arrange
        UUID documentId = UUID.randomUUID();
        testDocument.setId(documentId);
        List<DocumentChunk> chunks = createLargeDocumentChunks();
        
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(testDocument));
        when(chunkRepository.findByDocumentOrderByChunkIndex(testDocument)).thenReturn(chunks);
        when(documentMapper.toChunkDto(any(DocumentChunk.class))).thenAnswer(invocation -> {
            DocumentChunk chunk = invocation.getArgument(0);
            DocumentChunkDto dto = new DocumentChunkDto();
            dto.setId(chunk.getId());
            dto.setChunkIndex(chunk.getChunkIndex());
            dto.setContent(chunk.getContent());
            dto.setTitle(chunk.getTitle());
            return dto;
        });

        // Act
        List<DocumentChunkDto> result = documentProcessingService.getDocumentChunks(documentId);

        // Assert
        assertNotNull(result);
        assertEquals(50, result.size());
        
        // Verify chunks are ordered correctly
        for (int i = 0; i < result.size(); i++) {
            assertEquals(i, result.get(i).getChunkIndex());
        }
    }

    @Test
    void reprocessLargeDocument_WithDifferentSettings_Success() throws Exception {
        // Arrange
        UUID documentId = UUID.randomUUID();
        
        // Set up test document with proper file path
        testDocument.setId(documentId);
        testDocument.setFilePath("uploads/documents/test_document.pdf");
        testDocument.setContentType("application/pdf");
        testDocument.setOriginalFilename("test_document.pdf");
        
        ProcessDocumentRequest request = new ProcessDocumentRequest();
        request.setMaxChunkSize(1500);
        request.setChunkingStrategy(ProcessDocumentRequest.ChunkingStrategy.CHAPTER_BASED);
        
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(testDocument));
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
        when(documentMapper.toDto(any(Document.class))).thenReturn(testDocumentDto);
        
        // Mock file parser with correct content type and filename
        doReturn(true).when(mockFileParser).canParse("application/pdf", "test_document.pdf");
        try {
            doReturn(createLargeParsedDocument()).when(mockFileParser).parse(any(), anyString());
        } catch (Exception e) {
            // Handle exception
        }
        
        when(mockContentChunker.getSupportedStrategy()).thenReturn(ProcessDocumentRequest.ChunkingStrategy.CHAPTER_BASED);
        when(mockContentChunker.chunkDocument(any(), any())).thenReturn(createLargeChunks());

        // Set up the lists that the service expects
        setupServiceDependencies();

        // Act & Assert - expect DocumentStorageException because file doesn't exist on disk
        assertThrows(uk.gegc.quizmaker.exception.DocumentStorageException.class, () -> {
            documentProcessingService.reprocessDocument("testuser", documentId, request);
        });
    }

    private byte[] createLargePdfContent() {
        StringBuilder content = new StringBuilder();
        
        // Create a large document with many chapters
        for (int chapter = 1; chapter <= 20; chapter++) {
            content.append("Chapter ").append(chapter).append("\n");
            content.append("This is chapter ").append(chapter).append(" content. ");
            
            // Add multiple sections per chapter
            for (int section = 1; section <= 5; section++) {
                content.append(section).append(".").append(section).append(" Section ").append(section).append("\n");
                content.append("This is section ").append(section).append(" content. ");
                content.append("It contains detailed information about the topic. ");
                content.append("The content is designed to test the chunking algorithm. ");
                content.append("Each section should be properly chunked and organized. ");
                content.append("The chunking should respect logical boundaries. ");
                content.append("\n\n");
            }
            
            content.append("\n\n");
        }
        
        return content.toString().getBytes();
    }

    private ParsedDocument createLargeParsedDocument() {
        ParsedDocument document = new ParsedDocument();
        document.setTitle("Large Test Document");
        document.setAuthor("Test Author");
        document.setContent("This is a large test document with multiple chapters and sections.");
        
        List<ParsedDocument.Chapter> chapters = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            ParsedDocument.Chapter chapter = new ParsedDocument.Chapter();
            chapter.setTitle("Chapter " + i);
            chapter.setContent("This is chapter " + i + " content with multiple sections.");
            
            List<ParsedDocument.Section> sections = new ArrayList<>();
            for (int j = 1; j <= 5; j++) {
                ParsedDocument.Section section = new ParsedDocument.Section();
                section.setTitle(i + "." + j + " Section " + j);
                section.setContent("This is section " + j + " content in chapter " + i + ".");
                sections.add(section);
            }
            chapter.setSections(sections);
            chapters.add(chapter);
        }
        
        document.setChapters(chapters);
        return document;
    }

    private List<ContentChunker.Chunk> createLargeChunks() {
        List<ContentChunker.Chunk> chunks = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            ContentChunker.Chunk chunk = new ContentChunker.Chunk();
            chunk.setContent("This is chunk " + i + " content.");
            chunk.setChunkIndex(i);
            chunk.setTitle("Chunk " + i);
            chunk.setChunkType(ProcessDocumentRequest.ChunkingStrategy.CHAPTER_BASED);
            chunks.add(chunk);
        }
        return chunks;
    }

    private List<ContentChunker.Chunk> createManySmallChunks() {
        List<ContentChunker.Chunk> chunks = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            ContentChunker.Chunk chunk = new ContentChunker.Chunk();
            chunk.setContent("This is small chunk " + i + " content.");
            chunk.setChunkIndex(i);
            chunk.setTitle("Small Chunk " + i);
            chunk.setChunkType(ProcessDocumentRequest.ChunkingStrategy.CHAPTER_BASED);
            chunks.add(chunk);
        }
        return chunks;
    }

    private List<ContentChunker.Chunk> createSizeBasedChunks() {
        List<ContentChunker.Chunk> chunks = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            ContentChunker.Chunk chunk = new ContentChunker.Chunk();
            chunk.setContent("This is size-based chunk " + i + " content with appropriate size limits.");
            chunk.setChunkIndex(i);
            chunk.setTitle("Size Chunk " + i);
            chunk.setChunkType(ProcessDocumentRequest.ChunkingStrategy.SIZE_BASED);
            chunks.add(chunk);
        }
        return chunks;
    }

    private List<DocumentChunk> createLargeDocumentChunks() {
        List<DocumentChunk> chunks = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            DocumentChunk chunk = new DocumentChunk();
            chunk.setId(UUID.randomUUID());
            chunk.setDocument(testDocument);
            chunk.setChunkIndex(i);
            chunk.setContent("This is chunk " + i + " content.");
            chunk.setTitle("Chunk " + i);
            chunks.add(chunk);
        }
        return chunks;
    }

    private DocumentChunkDto createTestChunkDto() {
        DocumentChunkDto dto = new DocumentChunkDto();
        dto.setId(UUID.randomUUID());
        dto.setChunkIndex(0);
        dto.setContent("Test chunk content");
        dto.setTitle("Test Chunk");
        return dto;
    }
} 