package uk.gegc.quizmaker.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureWebMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import uk.gegc.quizmaker.config.DocumentProcessingConfig;
import uk.gegc.quizmaker.dto.document.DocumentChunkDto;
import uk.gegc.quizmaker.dto.document.DocumentDto;
import uk.gegc.quizmaker.dto.document.ProcessDocumentRequest;
import uk.gegc.quizmaker.exception.DocumentProcessingException;
import uk.gegc.quizmaker.exception.DocumentNotFoundException;
import uk.gegc.quizmaker.exception.UserNotAuthorizedException;
import uk.gegc.quizmaker.service.document.DocumentProcessingService;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureWebMvc
@ActiveProfiles("test")
class DocumentControllerIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    @MockitoBean
    private DocumentProcessingService documentProcessingService;

    @MockitoBean
    private DocumentProcessingConfig documentConfig;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .webAppContextSetup(context)
                .apply(springSecurity())
                .build();
        objectMapper = new ObjectMapper();
        
        // Setup default config values
        when(documentConfig.createDefaultRequest()).thenReturn(createDefaultRequest());
        when(documentConfig.getDefaultMaxChunkSize()).thenReturn(1000);
        when(documentConfig.getDefaultStrategy()).thenReturn("CHAPTER_BASED");
    }

    @Test
    @WithMockUser(username = "testuser")
    void uploadDocument_Success() throws Exception {
        // Arrange
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "test.pdf",
                "application/pdf",
                "This is test PDF content".getBytes()
        );

        DocumentDto documentDto = createTestDocumentDto();
        when(documentProcessingService.uploadAndProcessDocument(
                eq("testuser"), any(byte[].class), eq("test.pdf"), any(ProcessDocumentRequest.class)))
                .thenReturn(documentDto);

        // Act & Assert
        mockMvc.perform(multipart("/api/documents/upload")
                        .file(file)
                        .param("maxChunkSize", "3000")
                        .param("chunkingStrategy", "CHAPTER_BASED"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(documentDto.getId().toString()))
                .andExpect(jsonPath("$.originalFilename").value("test.pdf"))
                .andExpect(jsonPath("$.status").value("PROCESSED"));
    }

    @Test
    @WithMockUser(username = "testuser")
    void uploadDocument_WithDefaultSettings_Success() throws Exception {
        // Arrange
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "test.pdf",
                "application/pdf",
                "This is test PDF content".getBytes()
        );

        DocumentDto documentDto = createTestDocumentDto();
        when(documentProcessingService.uploadAndProcessDocument(
                eq("testuser"), any(byte[].class), eq("test.pdf"), any(ProcessDocumentRequest.class)))
                .thenReturn(documentDto);

        // Act & Assert
        mockMvc.perform(multipart("/api/documents/upload")
                        .file(file))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(documentDto.getId().toString()))
                .andExpect(jsonPath("$.originalFilename").value("test.pdf"));
    }

    @Test
    @WithMockUser(username = "testuser")
    void getDocument_Success() throws Exception {
        // Arrange
        UUID documentId = UUID.randomUUID();
        DocumentDto documentDto = createTestDocumentDto();
        documentDto.setId(documentId);

        when(documentProcessingService.getDocumentById(documentId, "testuser")).thenReturn(documentDto);

        // Act & Assert
        mockMvc.perform(get("/api/documents/{documentId}", documentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(documentId.toString()))
                .andExpect(jsonPath("$.originalFilename").value("test.pdf"));
    }

    @Test
    @WithMockUser(username = "testuser")
    void getDocument_NotFound() throws Exception {
        // Arrange
        UUID documentId = UUID.randomUUID();
        when(documentProcessingService.getDocumentById(documentId, "testuser"))
                .thenThrow(new DocumentNotFoundException(documentId.toString(), "Document not found"));

        // Act & Assert
        mockMvc.perform(get("/api/documents/{documentId}", documentId))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(username = "testuser")
    void getDocument_Unauthorized() throws Exception {
        // Arrange
        UUID documentId = UUID.randomUUID();
        when(documentProcessingService.getDocumentById(documentId, "testuser"))
                .thenThrow(new UserNotAuthorizedException("testuser", documentId.toString(), "access"));

        // Act & Assert
        mockMvc.perform(get("/api/documents/{documentId}", documentId))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "testuser")
    void getUserDocuments_Success() throws Exception {
        // Arrange
        DocumentDto documentDto = createTestDocumentDto();
        when(documentProcessingService.getUserDocuments(eq("testuser"), any()))
                .thenReturn(org.springframework.data.domain.Page.empty());

        // Act & Assert
        mockMvc.perform(get("/api/documents")
                        .param("page", "0")
                        .param("size", "10"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "testuser")
    void getDocumentChunks_Success() throws Exception {
        // Arrange
        UUID documentId = UUID.randomUUID();
        DocumentChunkDto chunk1 = createTestChunkDto(0);
        DocumentChunkDto chunk2 = createTestChunkDto(1);

        when(documentProcessingService.getDocumentChunks(documentId, "testuser"))
                .thenReturn(Arrays.asList(chunk1, chunk2));

        // Act & Assert
        mockMvc.perform(get("/api/documents/{documentId}/chunks", documentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].chunkIndex").value(0))
                .andExpect(jsonPath("$[1].chunkIndex").value(1))
                .andExpect(jsonPath("$[0].title").value("Chapter 1"))
                .andExpect(jsonPath("$[1].title").value("Chapter 2"));
    }

    @Test
    @WithMockUser(username = "testuser")
    void getDocumentChunks_Unauthorized() throws Exception {
        // Arrange
        UUID documentId = UUID.randomUUID();
        when(documentProcessingService.getDocumentChunks(documentId, "testuser"))
                .thenThrow(new UserNotAuthorizedException("testuser", documentId.toString(), "access chunks of"));

        // Act & Assert
        mockMvc.perform(get("/api/documents/{documentId}/chunks", documentId))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "testuser")
    void getDocumentChunk_Success() throws Exception {
        // Arrange
        UUID documentId = UUID.randomUUID();
        Integer chunkIndex = 1;
        DocumentChunkDto chunk = createTestChunkDto(chunkIndex);

        when(documentProcessingService.getDocumentChunk(documentId, chunkIndex, "testuser"))
                .thenReturn(chunk);

        // Act & Assert
        mockMvc.perform(get("/api/documents/{documentId}/chunks/{chunkIndex}", documentId, chunkIndex))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chunkIndex").value(chunkIndex))
                .andExpect(jsonPath("$.title").value("Chapter 2"));
    }

    @Test
    @WithMockUser(username = "testuser")
    void getDocumentChunk_Unauthorized() throws Exception {
        // Arrange
        UUID documentId = UUID.randomUUID();
        Integer chunkIndex = 1;
        when(documentProcessingService.getDocumentChunk(documentId, chunkIndex, "testuser"))
                .thenThrow(new UserNotAuthorizedException("testuser", documentId.toString(), "access chunks of"));

        // Act & Assert
        mockMvc.perform(get("/api/documents/{documentId}/chunks/{chunkIndex}", documentId, chunkIndex))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "testuser")
    void deleteDocument_Success() throws Exception {
        // Arrange
        UUID documentId = UUID.randomUUID();

        // Act & Assert
        mockMvc.perform(delete("/api/documents/{documentId}", documentId))
                .andExpect(status().isNoContent());
    }

    @Test
    @WithMockUser(username = "testuser")
    void reprocessDocument_Success() throws Exception {
        // Arrange
        UUID documentId = UUID.randomUUID();
        DocumentDto documentDto = createTestDocumentDto();
        ProcessDocumentRequest request = new ProcessDocumentRequest();
        request.setMaxChunkSize(2000);
        request.setChunkingStrategy(ProcessDocumentRequest.ChunkingStrategy.SIZE_BASED);

        when(documentProcessingService.reprocessDocument(eq("testuser"), eq(documentId), any(ProcessDocumentRequest.class)))
                .thenReturn(documentDto);

        // Act & Assert
        mockMvc.perform(post("/api/documents/{documentId}/reprocess", documentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(documentDto.getId().toString()));
    }

    @Test
    @WithMockUser(username = "testuser")
    void getDocumentStatus_Success() throws Exception {
        // Arrange
        UUID documentId = UUID.randomUUID();
        DocumentDto documentDto = createTestDocumentDto();
        documentDto.setId(documentId);

        when(documentProcessingService.getDocumentStatus(documentId, "testuser")).thenReturn(documentDto);

        // Act & Assert
        mockMvc.perform(get("/api/documents/{documentId}/status", documentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(documentId.toString()))
                .andExpect(jsonPath("$.status").value("PROCESSED"));
    }

    @Test
    @WithMockUser(username = "testuser")
    void getConfiguration_Success() throws Exception {
        // Arrange
        DocumentProcessingConfig config = new DocumentProcessingConfig();
        config.setDefaultMaxChunkSize(4000);
        config.setDefaultStrategy("CHAPTER_BASED");

        when(documentConfig.getDefaultMaxChunkSize()).thenReturn(4000);
        when(documentConfig.getDefaultStrategy()).thenReturn("CHAPTER_BASED");

        // Act & Assert
        mockMvc.perform(get("/api/documents/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.defaultMaxChunkSize").value(4000))
                .andExpect(jsonPath("$.defaultStrategy").value("CHAPTER_BASED"));
    }

    @Test
    @WithMockUser(username = "testuser")
    void uploadDocument_InvalidFile_ReturnsError() throws Exception {
        // Arrange
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "test.txt",
                "application/octet-stream", // Unsupported content type
                "This is not a supported file".getBytes()
        );

        // Act & Assert - should fail at file type validation before reaching service
        mockMvc.perform(multipart("/api/documents/upload")
                        .file(file))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.details[0]").value(org.hamcrest.Matchers.containsString("Unsupported file type")));
    }

    @Test
    @WithMockUser(username = "testuser")
    void uploadDocument_LargeFile_HandlesCorrectly() throws Exception {
        // Arrange
        StringBuilder largeContent = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            largeContent.append("This is a large document content. ");
        }

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "large_test.pdf",
                "application/pdf",
                largeContent.toString().getBytes()
        );

        DocumentDto documentDto = createTestDocumentDto();
        documentDto.setTotalChunks(50); // Large document with many chunks

        when(documentProcessingService.uploadAndProcessDocument(
                eq("testuser"), any(byte[].class), eq("large_test.pdf"), any(ProcessDocumentRequest.class)))
                .thenReturn(documentDto);

        // Act & Assert
        mockMvc.perform(multipart("/api/documents/upload")
                        .file(file)
                        .param("maxChunkSize", "1000"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalChunks").value(50));
    }
    
    @Test
    @WithMockUser(username = "testuser")
    void uploadDocument_ServiceError_ReturnsProcessingError() throws Exception {
        // Arrange
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "test.pdf",
                "application/pdf",
                "This is test PDF content".getBytes()
        );

        when(documentProcessingService.uploadAndProcessDocument(
                anyString(), any(byte[].class), anyString(), any(ProcessDocumentRequest.class)))
                .thenThrow(new RuntimeException("Processing failed"));

        // Act & Assert
        mockMvc.perform(multipart("/api/documents/upload")
                        .file(file))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("Document Processing Error"))
                .andExpect(jsonPath("$.details[0]").value(org.hamcrest.Matchers.containsString("Failed to upload document")));
    }
    
    @Test
    @WithMockUser(username = "testuser")
    void uploadDocument_ServiceThrowsDocumentProcessingException_ReturnsCorrectError() throws Exception {
        // Arrange
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "test.pdf",
                "application/pdf",
                "This is test PDF content".getBytes()
        );

        when(documentProcessingService.uploadAndProcessDocument(
                anyString(), any(byte[].class), anyString(), any(ProcessDocumentRequest.class)))
                .thenThrow(new DocumentProcessingException("Custom processing error"));

        // Act & Assert
        mockMvc.perform(multipart("/api/documents/upload")
                        .file(file))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("Document Processing Error"))
                .andExpect(jsonPath("$.details[0]").value("Failed to upload document: Custom processing error"));
    }

    @Test
    @WithMockUser(username = "testuser")
    void uploadDocument_ServiceThrowsDocumentStorageException_ReturnsStorageError() throws Exception {
        // Arrange
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "test.pdf",
                "application/pdf",
                "This is test PDF content".getBytes()
        );

        when(documentProcessingService.uploadAndProcessDocument(
                anyString(), any(byte[].class), anyString(), any(ProcessDocumentRequest.class)))
                .thenThrow(new uk.gegc.quizmaker.exception.DocumentStorageException("File storage failed"));

        // Act & Assert
        mockMvc.perform(multipart("/api/documents/upload")
                        .file(file))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("Document Storage Error"))
                .andExpect(jsonPath("$.details[0]").value("File storage failed"));
    }


    private DocumentDto createTestDocumentDto() {
        DocumentDto dto = new DocumentDto();
        dto.setId(UUID.randomUUID());
        dto.setOriginalFilename("test.pdf");
        dto.setContentType("application/pdf");
        dto.setFileSize(1000L);
        dto.setStatus(uk.gegc.quizmaker.model.document.Document.DocumentStatus.PROCESSED);
        dto.setUploadedAt(LocalDateTime.now());
        dto.setProcessedAt(LocalDateTime.now());
        dto.setTitle("Test Document");
        dto.setAuthor("Test Author");
        dto.setTotalPages(10);
        dto.setTotalChunks(5);
        return dto;
    }

    private DocumentChunkDto createTestChunkDto(int index) {
        DocumentChunkDto dto = new DocumentChunkDto();
        dto.setId(UUID.randomUUID());
        dto.setChunkIndex(index);
        dto.setTitle("Chapter " + (index + 1));
        dto.setContent("This is chapter " + (index + 1) + " content.");
        dto.setStartPage(index * 2 + 1);
        dto.setEndPage(index * 2 + 2);
        dto.setWordCount(6);
        dto.setCharacterCount(25);
        dto.setCreatedAt(LocalDateTime.now());
        dto.setChapterTitle("Chapter " + (index + 1));
        dto.setChapterNumber(index + 1);
        dto.setChunkType(uk.gegc.quizmaker.model.document.DocumentChunk.ChunkType.CHAPTER);
        return dto;
    }
    
    private ProcessDocumentRequest createDefaultRequest() {
        ProcessDocumentRequest request = new ProcessDocumentRequest();
        request.setMaxChunkSize(1000);
        request.setChunkingStrategy(ProcessDocumentRequest.ChunkingStrategy.CHAPTER_BASED);
        return request;
    }
} 