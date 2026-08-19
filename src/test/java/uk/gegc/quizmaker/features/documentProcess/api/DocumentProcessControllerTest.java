package uk.gegc.quizmaker.features.documentProcess.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import uk.gegc.quizmaker.features.conversion.domain.ConversionFailedException;
import org.springframework.web.multipart.MultipartFile;
import uk.gegc.quizmaker.features.documentProcess.domain.NormalizationFailedException;
import uk.gegc.quizmaker.features.documentProcess.api.dto.DocumentView;
import uk.gegc.quizmaker.features.documentProcess.api.dto.ExtractResponse;
import uk.gegc.quizmaker.features.documentProcess.api.dto.IngestRequest;
import uk.gegc.quizmaker.features.documentProcess.api.dto.IngestResponse;
import uk.gegc.quizmaker.features.documentProcess.api.dto.TextSliceResponse;
import uk.gegc.quizmaker.features.documentProcess.application.NormalizedDocumentAccessService;
import uk.gegc.quizmaker.features.documentProcess.domain.ValidationErrorException;
import uk.gegc.quizmaker.features.documentProcess.domain.model.NormalizedDocument;
import uk.gegc.quizmaker.features.documentProcess.infra.mapper.DocumentMapper;
import uk.gegc.quizmaker.shared.exception.ResourceNotFoundException;
import uk.gegc.quizmaker.shared.exception.DocumentTypeMismatchException;
import uk.gegc.quizmaker.testsupport.WebMvcSecurityTestConfig;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;

import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(DocumentProcessController.class)
@Import(WebMvcSecurityTestConfig.class)
@DisplayName("DocumentProcessController Tests")
class DocumentProcessControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private NormalizedDocumentAccessService documentAccessService;

    @MockitoBean
    private DocumentMapper mapper;

    private UUID documentId;
    private NormalizedDocument testDocument;
    private IngestRequest testIngestRequest;
    private IngestResponse testIngestResponse;
    private DocumentView testDocumentView;
    private TextSliceResponse testTextSliceResponse;

    @BeforeEach
    void setUp() {
        documentId = UUID.randomUUID();
        
        testDocument = new NormalizedDocument();
        testDocument.setId(documentId);
        testDocument.setOriginalName("test.txt");
        testDocument.setMime("text/plain");
        testDocument.setSource(NormalizedDocument.DocumentSource.TEXT);
        testDocument.setLanguage("en");
        testDocument.setNormalizedText("Hello world");
        testDocument.setCharCount(11);
        testDocument.setStatus(NormalizedDocument.DocumentStatus.NORMALIZED);
        testDocument.setCreatedAt(Instant.now());
        testDocument.setUpdatedAt(Instant.now());

        testIngestRequest = new IngestRequest("Hello world", "en");
        testIngestResponse = new IngestResponse(documentId, NormalizedDocument.DocumentStatus.NORMALIZED);
        testDocumentView = new DocumentView(
                documentId, "test.txt", "text/plain", NormalizedDocument.DocumentSource.TEXT, 
                11, "en", NormalizedDocument.DocumentStatus.NORMALIZED, Instant.now(), Instant.now()
        );
        testTextSliceResponse = new TextSliceResponse(documentId, 0, 5, "Hello");
    }

    // ===== GlobalExceptionHandler Mapping Tests =====

    @Test
    @WithMockUser(username = "owner")
    @DisplayName("Returns the shared 415 problem when content evidence and filename disagree")
    void documentTypeMismatchReturns415() throws Exception {
        // Given
        MockMultipartFile file = new MockMultipartFile(
                "file", "test.unknown", "application/octet-stream", "test content".getBytes()
        );
        
        when(documentAccessService.ingestFromFile(anyString(), anyString(), any(MultipartFile.class)))
                .thenThrow(new DocumentTypeMismatchException("mismatch"));

        // When & Then
        mockMvc.perform(multipart("/api/v1/documentProcess/documents")
                        .file(file)
                        .with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.status").value(415))
                .andExpect(jsonPath("$.type").value("https://quizzence.com/docs/errors/document-type-mismatch"))
                .andExpect(jsonPath("$.title").value("Unsupported Document Type"))
                .andExpect(jsonPath("$.detail").value("The document type does not match supported content."))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    @WithMockUser
    @DisplayName("conversionFailed_returns422 - Mock service to throw ConversionFailedException → 422 'Processing Failed'")
    void conversionFailed_returns422() throws Exception {
        // Given
        MockMultipartFile file = new MockMultipartFile(
                "file", "test.pdf", "application/pdf", "test content".getBytes()
        );
        
        when(documentAccessService.ingestFromFile(anyString(), anyString(), any(MultipartFile.class)))
                .thenThrow(new ConversionFailedException("Document conversion failed"));

        // When & Then
        mockMvc.perform(multipart("/api/v1/documentProcess/documents")
                        .file(file)
                        .with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.type").value("https://quizzence.com/docs/errors/conversion-failed"))
                .andExpect(jsonPath("$.title").value("Conversion Failed"))
                .andExpect(jsonPath("$.detail").value("Document conversion failed"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    @WithMockUser
    @DisplayName("normalizationFailed_returns422 - NormalizationFailedException → 422 'Processing Failed'")
    void normalizationFailed_returns422() throws Exception {
        // Given
        when(documentAccessService.ingestFromText(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new NormalizationFailedException("Document normalization failed"));

        // When & Then
        mockMvc.perform(post("/api/v1/documentProcess/documents")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(testIngestRequest)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.type").value("https://quizzence.com/docs/errors/normalization-failed"))
                .andExpect(jsonPath("$.title").value("Normalization Failed"))
                .andExpect(jsonPath("$.detail").value("Document normalization failed"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    @WithMockUser
    @DisplayName("validationErrors_return400 - JSON text blank (@NotBlank) → 400")
    void validationErrors_return400() throws Exception {
        // Given
        IngestRequest invalidRequest = new IngestRequest("", "en");

        // When & Then
        mockMvc.perform(post("/api/v1/documentProcess/documents")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.type").value("https://quizzence.com/docs/errors/validation-failed"))
                .andExpect(jsonPath("$.title").value("Validation Failed"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("text"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value("Text content cannot be blank"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    @WithMockUser
    @DisplayName("validationErrors_return400 - QueryService throws ValidationErrorException → 400")
    void validationErrors_queryServiceThrowsValidationErrorException_returns400() throws Exception {
        // Given
        when(documentAccessService.getTextSlice(anyString(), any(UUID.class), anyInt(), anyInt()))
                .thenThrow(new ValidationErrorException("End offset must be greater than or equal to start"));

        // When & Then
        mockMvc.perform(get("/api/v1/documentProcess/documents/{id}/text", documentId)
                        .param("start", "10")
                        .param("end", "5"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.type").value("https://quizzence.com/docs/errors/validation-failed"))
                .andExpect(jsonPath("$.title").value("Validation Failed"))
                .andExpect(jsonPath("$.detail").value("End offset must be greater than or equal to start"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    @WithMockUser
    @DisplayName("notFound_returns404 - for unknown IDs")
    void notFound_returns404() throws Exception {
        // Given
        UUID unknownId = UUID.randomUUID();
        when(documentAccessService.getDocument(anyString(), org.mockito.ArgumentMatchers.eq(unknownId)))
                .thenThrow(new ResourceNotFoundException("Document not found"));

        // When & Then
        mockMvc.perform(get("/api/v1/documentProcess/documents/{id}", unknownId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.type").value("https://quizzence.com/docs/errors/resource-not-found"))
                .andExpect(jsonPath("$.title").value("Resource Not Found"))
                .andExpect(jsonPath("$.detail").value("Document not found"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    @WithMockUser
    @DisplayName("illegalState_returns422 - for 'no normalized text'")
    void illegalState_returns422() throws Exception {
        // Given
        when(documentAccessService.getTextSlice(anyString(), any(UUID.class), anyInt(), anyInt()))
                .thenThrow(new IllegalStateException("Document has no normalized text: " + documentId));

        // When & Then
        mockMvc.perform(get("/api/v1/documentProcess/documents/{id}/text", documentId)
                        .param("start", "0")
                        .param("end", "5"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.type").value("https://quizzence.com/docs/errors/illegal-state"))
                .andExpect(jsonPath("$.title").value("Illegal State"))
                .andExpect(jsonPath("$.detail").value("Document has no normalized text: " + documentId))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    // ===== DocumentProcessController Tests =====

    @Test
    @WithMockUser(username = "owner")
    @DisplayName("postDocuments_json_201_createdAndLocationHeader - Body {text:'hi',language:'en'} → 201, Location header present, body has id, status")
    void postDocuments_json_201_createdAndLocationHeader() throws Exception {
        // Given
        when(documentAccessService.ingestFromText(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(testDocument);
        when(mapper.toIngestResponse(testDocument)).thenReturn(testIngestResponse);

        // When & Then
        mockMvc.perform(post("/api/v1/documentProcess/documents")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(testIngestRequest)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/documentProcess/documents/" + documentId))
                .andExpect(jsonPath("$.id").value(documentId.toString()))
                .andExpect(jsonPath("$.status").value("NORMALIZED"));

        verify(documentAccessService).ingestFromText("owner", "text-input", "en", "Hello world");
    }

    @Test
    @WithMockUser(username = "owner")
    @DisplayName("postDocuments_json_invalidBody_400 - Invalid JSON body")
    void postDocuments_json_invalidBody_400() throws Exception {
        // Given
        String invalidJson = "{ invalid json }";

        // When & Then
        mockMvc.perform(post("/api/v1/documentProcess/documents")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.type").value("https://quizzence.com/docs/errors/malformed-json"))
                .andExpect(jsonPath("$.title").value("Malformed JSON"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    @WithMockUser
    @DisplayName("postDocuments_multipart_201_createdAndLocationHeader - Multipart file upload")
    void postDocuments_multipart_201_createdAndLocationHeader() throws Exception {
        // Given
        MockMultipartFile file = new MockMultipartFile(
                "file", "test.txt", "text/plain", "Hello world".getBytes()
        );
        
        when(documentAccessService.ingestFromFile(anyString(), anyString(), any(MultipartFile.class)))
                .thenReturn(testDocument);
        when(mapper.toIngestResponse(testDocument)).thenReturn(testIngestResponse);

        // When & Then
        mockMvc.perform(multipart("/api/v1/documentProcess/documents")
                        .file(file)
                        .with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/documentProcess/documents/" + documentId))
                .andExpect(jsonPath("$.id").value(documentId.toString()))
                .andExpect(jsonPath("$.status").value("NORMALIZED"));

        verify(documentAccessService).ingestFromFile("user", "test.txt", file);
    }

    @Test
    @WithMockUser
    @DisplayName("postDocuments_multipart_emptyFile_400 - Empty file upload")
    void postDocuments_multipart_emptyFile_400() throws Exception {
        // Given
        MockMultipartFile emptyFile = new MockMultipartFile(
                "file", "empty.txt", "text/plain", new byte[0]
        );

        // When & Then
        mockMvc.perform(multipart("/api/v1/documentProcess/documents")
                        .file(emptyFile)
                        .with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.type").value("https://quizzence.com/docs/errors/invalid-argument"))
                .andExpect(jsonPath("$.title").value("Invalid Argument"))
                .andExpect(jsonPath("$.detail").value("File is required"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    @WithMockUser
    @DisplayName("Rejects a multipart upload whose filename is missing")
    void postDocumentsMultipartMissingFilenameReturns415() throws Exception {
        // Given
        MockMultipartFile file = new MockMultipartFile(
                "file", null, "text/plain", "Hello world".getBytes()
        );
        
        when(documentAccessService.ingestFromFile(
                anyString(), org.mockito.ArgumentMatchers.nullable(String.class), any(MultipartFile.class)))
                .thenThrow(new DocumentTypeMismatchException("missing filename"));

        // When & Then
        mockMvc.perform(multipart("/api/v1/documentProcess/documents")
                        .file(file)
                        .with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.type").value("https://quizzence.com/docs/errors/document-type-mismatch"));
    }

    @Test
    @WithMockUser
    @DisplayName("postDocuments_multipart_unsupportedExt_415 - bubbles from service")
    void postDocuments_multipart_unsupportedExt_415() throws Exception {
        // Given
        MockMultipartFile file = new MockMultipartFile(
                "file", "test.xyz", "application/octet-stream", "test content".getBytes()
        );
        
        when(documentAccessService.ingestFromFile(anyString(), anyString(), any(MultipartFile.class)))
                .thenThrow(new DocumentTypeMismatchException("mismatch"));

        // When & Then
        mockMvc.perform(multipart("/api/v1/documentProcess/documents")
                        .file(file)
                        .with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.type").value("https://quizzence.com/docs/errors/document-type-mismatch"))
                .andExpect(jsonPath("$.title").value("Unsupported Document Type"))
                .andExpect(jsonPath("$.detail").value("The document type does not match supported content."))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    @WithMockUser(username = "owner")
    @DisplayName("getDocument_200_returnsDocumentView - Get document metadata")
    void getDocument_200_returnsDocumentView() throws Exception {
        // Given
        when(documentAccessService.getDocument(anyString(), org.mockito.ArgumentMatchers.eq(documentId))).thenReturn(testDocument);
        when(mapper.toDocumentView(testDocument)).thenReturn(testDocumentView);

        // When & Then
        mockMvc.perform(get("/api/v1/documentProcess/documents/{id}", documentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(documentId.toString()))
                .andExpect(jsonPath("$.originalName").value("test.txt"))
                .andExpect(jsonPath("$.mime").value("text/plain"))
                .andExpect(jsonPath("$.source").value("TEXT"))
                .andExpect(jsonPath("$.language").value("en"))
                .andExpect(jsonPath("$.charCount").value(11))
                .andExpect(jsonPath("$.status").value("NORMALIZED"));

        verify(documentAccessService).getDocument("owner", documentId);
    }

    @Test
    @DisplayName("every normalized-document endpoint requires authentication")
    void everyEndpointRequiresAuthentication() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "test.txt", "text/plain", "content".getBytes()
        );
        UUID nodeId = UUID.randomUUID();

        mockMvc.perform(post("/api/v1/documentProcess/documents")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(testIngestRequest)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(multipart("/api/v1/documentProcess/documents")
                        .file(file)
                        .with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/documentProcess/documents/{id}", documentId))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/documentProcess/documents/{id}/head", documentId))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/documentProcess/documents/{id}/text", documentId))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/documentProcess/documents/{id}/structure", documentId))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/documentProcess/documents/{id}/structure", documentId)
                        .with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/documentProcess/documents/{id}/extract", documentId)
                        .param("nodeId", nodeId.toString()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "other-user")
    @DisplayName("wrong-owner reads, structure operations, and extraction are indistinguishable 404 responses")
    void wrongOwnerOperationsReturnNonEnumeratingNotFound() throws Exception {
        UUID nodeId = UUID.randomUUID();
        ResourceNotFoundException notFound = new ResourceNotFoundException("Document not found");
        when(documentAccessService.getDocument("other-user", documentId)).thenThrow(notFound);
        when(documentAccessService.getTextSlice("other-user", documentId, 0, 5)).thenThrow(notFound);
        when(documentAccessService.getTree("other-user", documentId)).thenThrow(notFound);
        doThrow(notFound).when(documentAccessService).buildStructure("other-user", documentId);
        when(documentAccessService.extractByNode("other-user", documentId, nodeId)).thenThrow(notFound);

        mockMvc.perform(get("/api/v1/documentProcess/documents/{id}", documentId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Document not found"));
        mockMvc.perform(get("/api/v1/documentProcess/documents/{id}/head", documentId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Document not found"));
        mockMvc.perform(get("/api/v1/documentProcess/documents/{id}/text", documentId)
                        .param("start", "0")
                        .param("end", "5"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Document not found"));
        mockMvc.perform(get("/api/v1/documentProcess/documents/{id}/structure", documentId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Document not found"));
        mockMvc.perform(post("/api/v1/documentProcess/documents/{id}/structure", documentId)
                        .with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Document not found"));
        mockMvc.perform(get("/api/v1/documentProcess/documents/{id}/extract", documentId)
                        .param("nodeId", nodeId.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Document not found"));
    }

    @Test
    @WithMockUser
    @DisplayName("getTextSlice_onlyStart_defaultsToCharCount - ensures controller uses getTextLength() projection")
    void getTextSlice_onlyStart_defaultsToCharCount() throws Exception {
        // Given
        when(documentAccessService.getTextLength(anyString(), org.mockito.ArgumentMatchers.eq(documentId))).thenReturn(11);
        when(documentAccessService.getTextSlice(anyString(), org.mockito.ArgumentMatchers.eq(documentId), org.mockito.ArgumentMatchers.eq(0), org.mockito.ArgumentMatchers.eq(11))).thenReturn("Hello world");
        when(mapper.toTextSliceResponse(documentId, 0, 11, "Hello world"))
                .thenReturn(new TextSliceResponse(documentId, 0, 11, "Hello world"));

        // When & Then
        mockMvc.perform(get("/api/v1/documentProcess/documents/{id}/text", documentId)
                        .param("start", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentId").value(documentId.toString()))
                .andExpect(jsonPath("$.start").value(0))
                .andExpect(jsonPath("$.end").value(11))
                .andExpect(jsonPath("$.text").value("Hello world"));
    }

    @Test
    @WithMockUser
    @DisplayName("getTextSlice_withStartEnd_returnsSlice - with both start and end parameters")
    void getTextSlice_withStartEnd_returnsSlice() throws Exception {
        // Given
        when(documentAccessService.getTextSlice(anyString(), org.mockito.ArgumentMatchers.eq(documentId), org.mockito.ArgumentMatchers.eq(0), org.mockito.ArgumentMatchers.eq(5))).thenReturn("Hello");
        when(mapper.toTextSliceResponse(documentId, 0, 5, "Hello")).thenReturn(testTextSliceResponse);

        // When & Then
        mockMvc.perform(get("/api/v1/documentProcess/documents/{id}/text", documentId)
                        .param("start", "0")
                        .param("end", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentId").value(documentId.toString()))
                .andExpect(jsonPath("$.start").value(0))
                .andExpect(jsonPath("$.end").value(5))
                .andExpect(jsonPath("$.text").value("Hello"));
    }

    @Test
    @WithMockUser
    @DisplayName("getTextSlice_negativeStart_400 - @Min(0) kicks in")
    void getTextSlice_negativeStart_400() throws Exception {
        // When & Then
        mockMvc.perform(get("/api/v1/documentProcess/documents/{id}/text", documentId)
                        .param("start", "-1")
                        .param("end", "5"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.type").value("https://quizzence.com/docs/errors/constraint-violation"))
                .andExpect(jsonPath("$.title").value("Constraint Violation"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    @WithMockUser
    @DisplayName("getTextSlice_negativeEnd_400 - @Min(0) kicks in")
    void getTextSlice_negativeEnd_400() throws Exception {
        // When & Then
        mockMvc.perform(get("/api/v1/documentProcess/documents/{id}/text", documentId)
                        .param("start", "0")
                        .param("end", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.type").value("https://quizzence.com/docs/errors/constraint-violation"))
                .andExpect(jsonPath("$.title").value("Constraint Violation"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    @WithMockUser
    @DisplayName("getTextSlice_endLessThanStart_400 - via service → handler")
    void getTextSlice_endLessThanStart_400() throws Exception {
        // Given
        when(documentAccessService.getTextSlice(anyString(), org.mockito.ArgumentMatchers.eq(documentId), org.mockito.ArgumentMatchers.eq(10), org.mockito.ArgumentMatchers.eq(5)))
                .thenThrow(new ValidationErrorException("End offset must be greater than or equal to start: end=5, start=10"));

        // When & Then
        mockMvc.perform(get("/api/v1/documentProcess/documents/{id}/text", documentId)
                        .param("start", "10")
                        .param("end", "5"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.type").value("https://quizzence.com/docs/errors/validation-failed"))
                .andExpect(jsonPath("$.title").value("Validation Failed"))
                .andExpect(jsonPath("$.detail").value("End offset must be greater than or equal to start: end=5, start=10"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    @DisplayName("Extract by node ID - Success")
    @WithMockUser
    void extractByNodeId_Success() throws Exception {
        // Given
        UUID documentId = UUID.randomUUID();
        UUID nodeId = UUID.randomUUID();
        ExtractResponse expectedResponse = new ExtractResponse(
                documentId,
                nodeId,
                "Chapter 1",
                0,
                50,
                "This is the content of chapter 1."
        );

        when(documentAccessService.extractByNode(anyString(), org.mockito.ArgumentMatchers.eq(documentId), org.mockito.ArgumentMatchers.eq(nodeId))).thenReturn(expectedResponse);

        // When & Then
        mockMvc.perform(get("/api/v1/documentProcess/documents/{id}/extract", documentId)
                        .param("nodeId", nodeId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentId").value(documentId.toString()))
                .andExpect(jsonPath("$.nodeId").value(nodeId.toString()))
                .andExpect(jsonPath("$.title").value("Chapter 1"))
                .andExpect(jsonPath("$.start").value(0))
                .andExpect(jsonPath("$.end").value(50))
                .andExpect(jsonPath("$.text").value("This is the content of chapter 1."));
    }

    @Test
    @DisplayName("Extract by node ID - Missing nodeId parameter")
    @WithMockUser
    void extractByNodeId_MissingNodeIdParameter() throws Exception {
        // Given
        UUID documentId = UUID.randomUUID();

        // When & Then
        mockMvc.perform(get("/api/v1/documentProcess/documents/{id}/extract", documentId))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Extract by node ID - Document not found")
    @WithMockUser
    void extractByNodeId_DocumentNotFound() throws Exception {
        // Given
        UUID documentId = UUID.randomUUID();
        UUID nodeId = UUID.randomUUID();

        when(documentAccessService.extractByNode(anyString(), org.mockito.ArgumentMatchers.eq(documentId), org.mockito.ArgumentMatchers.eq(nodeId)))
                .thenThrow(new ResourceNotFoundException("Document not found"));

        // When & Then
        mockMvc.perform(get("/api/v1/documentProcess/documents/{id}/extract", documentId)
                        .param("nodeId", nodeId.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.type").value("https://quizzence.com/docs/errors/resource-not-found"))
                .andExpect(jsonPath("$.title").value("Resource Not Found"))
                .andExpect(jsonPath("$.detail").value("Document not found"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    @DisplayName("Extract by node ID - Node not found")
    @WithMockUser
    void extractByNodeId_NodeNotFound() throws Exception {
        // Given
        UUID documentId = UUID.randomUUID();
        UUID nodeId = UUID.randomUUID();

        when(documentAccessService.extractByNode(anyString(), org.mockito.ArgumentMatchers.eq(documentId), org.mockito.ArgumentMatchers.eq(nodeId)))
                .thenThrow(new ResourceNotFoundException("Node not found: " + nodeId));

        // When & Then
        mockMvc.perform(get("/api/v1/documentProcess/documents/{id}/extract", documentId)
                        .param("nodeId", nodeId.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.type").value("https://quizzence.com/docs/errors/resource-not-found"))
                .andExpect(jsonPath("$.title").value("Resource Not Found"))
                .andExpect(jsonPath("$.detail").value("Node not found: " + nodeId))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    @DisplayName("Extract by node ID - Node doesn't belong to document")
    @WithMockUser
    void extractByNodeId_NodeDoesNotBelongToDocument() throws Exception {
        // Given
        UUID documentId = UUID.randomUUID();
        UUID nodeId = UUID.randomUUID();

        when(documentAccessService.extractByNode(anyString(), org.mockito.ArgumentMatchers.eq(documentId), org.mockito.ArgumentMatchers.eq(nodeId)))
                .thenThrow(new IllegalArgumentException("Node " + nodeId + " does not belong to document " + documentId));

        // When & Then
        mockMvc.perform(get("/api/v1/documentProcess/documents/{id}/extract", documentId)
                        .param("nodeId", nodeId.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.type").value("https://quizzence.com/docs/errors/invalid-argument"))
                .andExpect(jsonPath("$.title").value("Invalid Argument"))
                .andExpect(jsonPath("$.detail").value("Node " + nodeId + " does not belong to document " + documentId))
                .andExpect(jsonPath("$.timestamp").exists());
    }
}
