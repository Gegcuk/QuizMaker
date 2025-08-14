package uk.gegc.quizmaker.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import uk.gegc.quizmaker.features.document.application.DocumentProcessingConfig;
import uk.gegc.quizmaker.features.document.api.dto.DocumentDto;
import uk.gegc.quizmaker.features.document.api.dto.ProcessDocumentRequest;
import uk.gegc.quizmaker.shared.exception.DocumentNotFoundException;
import uk.gegc.quizmaker.shared.exception.DocumentProcessingException;
import uk.gegc.quizmaker.shared.exception.DocumentStorageException;
import uk.gegc.quizmaker.shared.exception.UnsupportedFileTypeException;
import uk.gegc.quizmaker.features.document.api.DocumentController;
import uk.gegc.quizmaker.features.document.application.DocumentProcessingService;
import uk.gegc.quizmaker.features.document.domain.model.Document;
import uk.gegc.quizmaker.service.document.DocumentValidationService;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DocumentController.class)
class DocumentControllerErrorTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DocumentProcessingService documentProcessingService;

    @MockitoBean
    private DocumentValidationService documentValidationService;

    @MockitoBean
    private DocumentProcessingConfig documentProcessingConfig;

    private DocumentDto testDocumentDto;

    @BeforeEach
    void setUp() {
        testDocumentDto = new DocumentDto();
        testDocumentDto.setId(UUID.randomUUID());
        testDocumentDto.setOriginalFilename("test.pdf");
        testDocumentDto.setStatus(Document.DocumentStatus.PROCESSED);
        testDocumentDto.setTotalChunks(5);

        // Reset mocks to ensure clean state
        reset(documentValidationService, documentProcessingService, documentProcessingConfig);

        // Setup default validation service behavior
        doNothing().when(documentValidationService).validateFileUpload(any(), any(), any());
        doNothing().when(documentValidationService).validateReprocessRequest(any());

        // Setup default config behavior
        when(documentProcessingConfig.createDefaultRequest()).thenReturn(createDefaultRequest());

        // DO NOT setup default processing service behavior - let each test set it up
    }

    private ProcessDocumentRequest createDefaultRequest() {
        ProcessDocumentRequest request = new ProcessDocumentRequest();
        request.setMaxChunkSize(4000);
        request.setChunkingStrategy(ProcessDocumentRequest.ChunkingStrategy.CHAPTER_BASED);
        return request;
    }

    @Test
    @WithMockUser(username = "testuser")
    void uploadDocument_EmptyFile_ReturnsError() throws Exception {
        // Arrange
        MockMultipartFile emptyFile = new MockMultipartFile(
                "file",
                "empty.pdf",
                "application/pdf",
                new byte[0]
        );

        doThrow(new IllegalArgumentException("File is empty"))
                .when(documentValidationService).validateFileUpload(any(), any(), any());

        // Act & Assert
        mockMvc.perform(multipart("/api/documents/upload")
                        .file(emptyFile)
                        .with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0]").value("File is empty"));
    }

    @Test
    @WithMockUser(username = "testuser")
    void uploadDocument_FileTooLarge_ReturnsError() throws Exception {
        // Arrange
        byte[] largeContent = new byte[11 * 1024 * 1024]; // 11MB
        MockMultipartFile largeFile = new MockMultipartFile(
                "file",
                "large.pdf",
                "application/pdf",
                largeContent
        );

        doThrow(new IllegalArgumentException("File size exceeds maximum limit"))
                .when(documentValidationService).validateFileUpload(any(), any(), any());

        // Act & Assert
        mockMvc.perform(multipart("/api/documents/upload")
                        .file(largeFile)
                        .with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0]").value("File size exceeds maximum limit"));

        // Verify that validation service was called
        verify(documentValidationService).validateFileUpload(any(), any(), any());
    }

    @Test
    @WithMockUser(username = "testuser")
    void uploadDocument_UnsupportedFileType_ReturnsError() throws Exception {
        // Arrange
        MockMultipartFile unsupportedFile = new MockMultipartFile(
                "file",
                "test.exe",
                "application/octet-stream",
                "test content".getBytes()
        );

        doThrow(new UnsupportedFileTypeException("Unsupported file type: .exe"))
                .when(documentValidationService).validateFileUpload(any(), any(), any());

        // Act & Assert
        mockMvc.perform(multipart("/api/documents/upload")
                        .file(unsupportedFile)
                        .with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0]").value("Unsupported file type: .exe"));
    }

    @Test
    @WithMockUser(username = "testuser")
    void uploadDocument_ProcessingError_ReturnsError() throws Exception {
        // Arrange
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "test.pdf",
                "application/pdf",
                "test content".getBytes()
        );

        // Reset mocks and set up explicitly
        reset(documentProcessingService, documentValidationService);

        // Setup validation service to pass
        doNothing().when(documentValidationService).validateFileUpload(any(), any(), any());

        // Setup processing service to throw exception
        when(documentProcessingService.uploadAndProcessDocument(
                anyString(), any(byte[].class), anyString(), any(ProcessDocumentRequest.class)))
                .thenThrow(new DocumentProcessingException("Failed to process document"));

        // Act & Assert
        mockMvc.perform(multipart("/api/documents/upload")
                        .file(file)
                        .with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.details[0]").value("Failed to process document"));

        // Verify that processing service was called
        verify(documentProcessingService).uploadAndProcessDocument(anyString(), any(byte[].class), anyString(), any(ProcessDocumentRequest.class));
    }

    @Test
    @WithMockUser(username = "testuser")
    void uploadDocument_StorageError_ReturnsError() throws Exception {
        // Arrange
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "test.pdf",
                "application/pdf",
                "test content".getBytes()
        );

        // Reset mocks to ensure clean state
        reset(documentProcessingService, documentValidationService);

        // Setup validation service to pass
        doNothing().when(documentValidationService).validateFileUpload(any(), any(), any());

        // Explicitly setup the processing service to throw exception
        doThrow(new DocumentStorageException("Failed to store document file"))
                .when(documentProcessingService).uploadAndProcessDocument(
                        anyString(), any(byte[].class), anyString(), any(ProcessDocumentRequest.class));

        // Act & Assert
        mockMvc.perform(multipart("/api/documents/upload")
                        .file(file)
                        .with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.details[0]").value("Failed to store document file"));

        // Verify that processing service was called
        verify(documentProcessingService).uploadAndProcessDocument(anyString(), any(byte[].class), anyString(), any(ProcessDocumentRequest.class));
    }

    @Test
    @WithMockUser(username = "testuser")
    void uploadDocument_InvalidChunkSize_ReturnsError() throws Exception {
        // Arrange
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "test.pdf",
                "application/pdf",
                "test content".getBytes()
        );

        doThrow(new IllegalArgumentException("Invalid chunk size: must be between 100 and 10000"))
                .when(documentValidationService).validateFileUpload(any(), any(), any());

        // Act & Assert
        mockMvc.perform(multipart("/api/documents/upload")
                        .file(file)
                        .param("maxChunkSize", "0")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())) // Invalid chunk size
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0]").value("Invalid chunk size: must be between 100 and 10000"));
    }

    @Test
    @WithMockUser(username = "testuser")
    void uploadDocument_InvalidChunkingStrategy_ReturnsError() throws Exception {
        // Arrange
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "test.pdf",
                "application/pdf",
                "test content".getBytes()
        );

        doThrow(new IllegalArgumentException("Invalid chunking strategy: INVALID_STRATEGY"))
                .when(documentValidationService).validateFileUpload(any(), any(), any());

        // Act & Assert
        mockMvc.perform(multipart("/api/documents/upload")
                        .file(file)
                        .param("chunkingStrategy", "INVALID_STRATEGY")
                        .with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0]").value("Invalid chunking strategy: INVALID_STRATEGY"));
    }

    @Test
    @WithMockUser(username = "testuser")
    void getDocument_NotFound_ReturnsError() throws Exception {
        // Arrange
        UUID documentId = UUID.randomUUID();
        when(documentProcessingService.getDocumentById(documentId, "testuser"))
                .thenThrow(new DocumentNotFoundException("Document not found: " + documentId));

        // Act & Assert
        mockMvc.perform(get("/api/documents/{id}", documentId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.details[0]").value("Document not found: " + documentId));
    }

    @Test
    @WithMockUser(username = "testuser")
    void getDocument_Unauthorized_ReturnsError() throws Exception {
        // Arrange
        UUID documentId = UUID.randomUUID();
        when(documentProcessingService.getDocumentById(documentId, "testuser"))
                .thenThrow(new RuntimeException("Access denied"));

        // Act & Assert
        mockMvc.perform(get("/api/documents/{id}", documentId))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.details[0]").value("Access denied"));
    }

    @Test
    @WithMockUser(username = "testuser")
    void getDocumentChunks_NotFound_ReturnsError() throws Exception {
        // Arrange
        UUID documentId = UUID.randomUUID();
        when(documentProcessingService.getDocumentChunks(documentId, "testuser"))
                .thenThrow(new DocumentNotFoundException("Document not found: " + documentId));

        // Act & Assert
        mockMvc.perform(get("/api/documents/{id}/chunks", documentId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.details[0]").value("Document not found: " + documentId));
    }

    @Test
    @WithMockUser(username = "testuser")
    void getDocumentChunk_NotFound_ReturnsError() throws Exception {
        // Arrange
        UUID documentId = UUID.randomUUID();
        int chunkIndex = 0;
        when(documentProcessingService.getDocumentChunk(documentId, chunkIndex, "testuser"))
                .thenThrow(new RuntimeException("Chunk not found"));

        // Act & Assert
        mockMvc.perform(get("/api/documents/{id}/chunks/{chunkIndex}", documentId, chunkIndex))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.details[0]").value("Chunk not found"));
    }

    @Test
    @WithMockUser(username = "testuser")
    void deleteDocument_NotFound_ReturnsError() throws Exception {
        // Arrange
        UUID documentId = UUID.randomUUID();
        doThrow(new DocumentNotFoundException("Document not found: " + documentId))
                .when(documentProcessingService).deleteDocument("testuser", documentId);

        // Act & Assert
        mockMvc.perform(delete("/api/documents/{id}", documentId)
                        .with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.details[0]").value("Document not found: " + documentId));
    }

    @Test
    @WithMockUser(username = "testuser")
    void deleteDocument_Unauthorized_ReturnsError() throws Exception {
        // Arrange
        UUID documentId = UUID.randomUUID();
        doThrow(new RuntimeException("Access denied"))
                .when(documentProcessingService).deleteDocument("testuser", documentId);

        // Act & Assert
        mockMvc.perform(delete("/api/documents/{id}", documentId)
                        .with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.details[0]").value("Access denied"));
    }

    @Test
    @WithMockUser(username = "testuser")
    void reprocessDocument_NotFound_ReturnsError() throws Exception {
        // Arrange
        UUID documentId = UUID.randomUUID();
        when(documentProcessingService.reprocessDocument(eq("testuser"), eq(documentId), any(ProcessDocumentRequest.class)))
                .thenThrow(new DocumentNotFoundException("Document not found: " + documentId));

        // Act & Assert
        mockMvc.perform(post("/api/documents/{id}/reprocess", documentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"maxChunkSize\": 3000, \"chunkingStrategy\": \"CHAPTER_BASED\"}")
                        .with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.details[0]").value("Document not found: " + documentId));
    }

    @Test
    @WithMockUser(username = "testuser")
    void reprocessDocument_ProcessingError_ReturnsError() throws Exception {
        // Arrange
        UUID documentId = UUID.randomUUID();
        when(documentProcessingService.reprocessDocument(eq("testuser"), eq(documentId), any(ProcessDocumentRequest.class)))
                .thenThrow(new DocumentProcessingException("Failed to reprocess document"));

        // Act & Assert
        mockMvc.perform(post("/api/documents/{id}/reprocess", documentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"maxChunkSize\": 3000, \"chunkingStrategy\": \"CHAPTER_BASED\"}")
                        .with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.details[0]").value("Failed to reprocess document"));
    }

    @Test
    @WithMockUser(username = "testuser")
    void reprocessDocument_InvalidRequest_ReturnsError() throws Exception {
        // Arrange
        UUID documentId = UUID.randomUUID();

        doThrow(new IllegalArgumentException("Invalid request"))
                .when(documentValidationService).validateReprocessRequest(any());

        // Act & Assert
        mockMvc.perform(post("/api/documents/{id}/reprocess", documentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"maxChunkSize\": -1, \"chunkingStrategy\": \"INVALID\"}")
                        .with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0]").exists());
    }

    @Test
    @WithMockUser(username = "testuser")
    void uploadDocument_MissingFile_ReturnsError() throws Exception {
        // Arrange
        MockMultipartFile nullFile = new MockMultipartFile(
                "file",
                "test.pdf",
                "application/pdf",
                (byte[]) null
        );

        doThrow(new IllegalArgumentException("No file provided"))
                .when(documentValidationService).validateFileUpload(any(), any(), any());

        // Act & Assert
        mockMvc.perform(multipart("/api/documents/upload")
                        .file(nullFile)
                        .with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0]").value("No file provided"));
    }

    @Test
    @WithMockUser(username = "testuser")
    void uploadDocument_InvalidContentType_ReturnsError() throws Exception {
        // Arrange
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "test.pdf",
                "invalid/content-type",
                "test content".getBytes()
        );

        doThrow(new IllegalArgumentException("Invalid content type: invalid/content-type"))
                .when(documentValidationService).validateFileUpload(any(), any(), any());

        // Act & Assert
        mockMvc.perform(multipart("/api/documents/upload")
                        .file(file)
                        .with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0]").value("Invalid content type: invalid/content-type"));
    }

    @Test
    @WithMockUser(username = "testuser")
    void uploadDocument_FileWithNullBytes_ReturnsError() throws Exception {
        // Arrange
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "test.pdf",
                "application/pdf",
                (byte[]) null
        );

        doThrow(new IllegalArgumentException("File content is null"))
                .when(documentValidationService).validateFileUpload(any(), any(), any());

        // Act & Assert
        mockMvc.perform(multipart("/api/documents/upload")
                        .file(file)
                        .with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0]").value("File content is null"));
    }

    @Test
    @WithMockUser(username = "testuser")
    void uploadDocument_SuccessfulUpload_ReturnsCreated() throws Exception {
        // Arrange
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "test.pdf",
                "application/pdf",
                "test content".getBytes()
        );

        // Reset mocks to ensure clean state
        reset(documentProcessingService, documentValidationService);

        // Setup validation service to pass
        doNothing().when(documentValidationService).validateFileUpload(any(), any(), any());

        // Setup processing service to return success
        doReturn(testDocumentDto)
                .when(documentProcessingService).uploadAndProcessDocument(
                        anyString(), any(byte[].class), anyString(), any(ProcessDocumentRequest.class));

        // Act & Assert
        mockMvc.perform(multipart("/api/documents/upload")
                        .file(file)
                        .with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(testDocumentDto.getId().toString()));

        // Verify that validation service was called
        verify(documentValidationService).validateFileUpload(any(), any(), any());
        verify(documentProcessingService).uploadAndProcessDocument(anyString(), any(byte[].class), anyString(), any(ProcessDocumentRequest.class));
    }

    @Test
    @WithMockUser(username = "testuser")
    void uploadDocument_FileWithUnicodeName_HandlesCorrectly() throws Exception {
        // Arrange
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "test-文档-文件.pdf",
                "application/pdf",
                "test content".getBytes()
        );

        // Reset mocks to ensure clean state
        reset(documentProcessingService, documentValidationService);

        // Setup validation service to pass
        doNothing().when(documentValidationService).validateFileUpload(any(), any(), any());

        // Setup processing service to return success
        doReturn(testDocumentDto)
                .when(documentProcessingService).uploadAndProcessDocument(
                        anyString(), any(byte[].class), anyString(), any(ProcessDocumentRequest.class));

        // Act & Assert
        mockMvc.perform(multipart("/api/documents/upload")
                        .file(file)
                        .with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(testDocumentDto.getId().toString()));

        // Verify that processing service was called
        verify(documentProcessingService).uploadAndProcessDocument(anyString(), any(byte[].class), anyString(), any(ProcessDocumentRequest.class));
    }


    @Test
    @WithMockUser(username = "testuser")
    void uploadDocument_ServiceThrowsUnexpectedException_ReturnsError() throws Exception {
        // Arrange
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "test.pdf",
                "application/pdf",
                "test content".getBytes()
        );

        // Reset mocks to ensure clean state
        reset(documentProcessingService, documentValidationService);

        // Setup validation service to pass
        doNothing().when(documentValidationService).validateFileUpload(any(), any(), any());

        // Explicitly setup the processing service to throw exception
        doThrow(new RuntimeException("Unexpected error"))
                .when(documentProcessingService).uploadAndProcessDocument(
                        anyString(), any(byte[].class), anyString(), any(ProcessDocumentRequest.class));

        // Act & Assert
        mockMvc.perform(multipart("/api/documents/upload")
                        .file(file)
                        .with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.details[0]").value("Failed to upload document: Unexpected error"));

        // Verify that processing service was called
        verify(documentProcessingService).uploadAndProcessDocument(anyString(), any(byte[].class), anyString(), any(ProcessDocumentRequest.class));
    }


} 