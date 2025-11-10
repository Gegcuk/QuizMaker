package uk.gegc.quizmaker.features.documentProcess.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import uk.gegc.quizmaker.features.conversion.application.DocumentConversionService;
import uk.gegc.quizmaker.features.conversion.application.MimeTypeDetector;
import uk.gegc.quizmaker.features.conversion.domain.ConversionFailedException;
import uk.gegc.quizmaker.features.conversion.domain.UnsupportedFormatException;
import uk.gegc.quizmaker.features.documentProcess.domain.NormalizationFailedException;
import uk.gegc.quizmaker.features.documentProcess.api.dto.DocumentView;
import uk.gegc.quizmaker.features.documentProcess.api.dto.ExtractResponse;
import uk.gegc.quizmaker.features.documentProcess.api.dto.IngestRequest;
import uk.gegc.quizmaker.features.documentProcess.api.dto.IngestResponse;
import uk.gegc.quizmaker.features.documentProcess.api.dto.TextSliceResponse;
import uk.gegc.quizmaker.features.documentProcess.application.DocumentIngestionService;
import uk.gegc.quizmaker.features.documentProcess.application.DocumentQueryService;
import uk.gegc.quizmaker.features.documentProcess.application.NormalizationService;
import uk.gegc.quizmaker.features.documentProcess.application.StructureService;
import uk.gegc.quizmaker.features.documentProcess.domain.ValidationErrorException;
import uk.gegc.quizmaker.features.documentProcess.domain.model.NormalizedDocument;
import uk.gegc.quizmaker.features.documentProcess.infra.mapper.DocumentMapper;
import uk.gegc.quizmaker.shared.exception.ResourceNotFoundException;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(DocumentProcessController.class)
@DisplayName("DocumentProcessController Tests")
class DocumentProcessControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private DocumentIngestionService ingestionService;

    @MockitoBean
    private DocumentQueryService queryService;

    @MockitoBean
    private DocumentMapper mapper;

    @MockitoBean
    private DocumentConversionService conversionService;

    @MockitoBean
    private MimeTypeDetector mimeTypeDetector;

    @MockitoBean
    private NormalizationService normalizationService;

    @MockitoBean
    private StructureService structureService;

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
    @WithMockUser
    @DisplayName("unsupportedFormat_returns415 - POST multipart .unknown → 415 with body 'Unsupported Format'")
    void unsupportedFormat_returns415() throws Exception {
        // Given
        MockMultipartFile file = new MockMultipartFile(
                "file", "test.unknown", "application/octet-stream", "test content".getBytes()
        );
        
        when(ingestionService.ingestFromFile(anyString(), any(byte[].class)))
                .thenThrow(new UnsupportedFormatException("Unsupported file format: .unknown"));

        // When & Then
        mockMvc.perform(multipart("/api/v1/documentProcess/documents")
                        .file(file)
                        .with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.status").value(415))
                .andExpect(jsonPath("$.type").value("https://quizzence.com/docs/errors/unsupported-format"))
                .andExpect(jsonPath("$.title").value("Unsupported Format"))
                .andExpect(jsonPath("$.detail").value("Unsupported file format: .unknown"))
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
        
        when(ingestionService.ingestFromFile(anyString(), any(byte[].class)))
                .thenThrow(new ConversionFailedException("PDF conversion failed"));

        // When & Then
        mockMvc.perform(multipart("/api/v1/documentProcess/documents")
                        .file(file)
                        .with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.type").value("https://quizzence.com/docs/errors/conversion-failed"))
                .andExpect(jsonPath("$.title").value("Conversion Failed"))
                .andExpect(jsonPath("$.detail").value("PDF conversion failed"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    @WithMockUser
    @DisplayName("normalizationFailed_returns422 - NormalizationFailedException → 422 'Processing Failed'")
    void normalizationFailed_returns422() throws Exception {
        // Given
        when(ingestionService.ingestFromText(anyString(), anyString(), anyString()))
                .thenThrow(new NormalizationFailedException("Text normalization failed"));

        // When & Then
        mockMvc.perform(post("/api/v1/documentProcess/documents")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(testIngestRequest)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.type").value("https://quizzence.com/docs/errors/normalization-failed"))
                .andExpect(jsonPath("$.title").value("Normalization Failed"))
                .andExpect(jsonPath("$.detail").value("Text normalization failed"))
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
        when(queryService.getTextSlice(any(UUID.class), anyInt(), anyInt()))
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
        when(queryService.getDocument(unknownId))
                .thenThrow(new ResourceNotFoundException("Document not found: " + unknownId));

        // When & Then
        mockMvc.perform(get("/api/v1/documentProcess/documents/{id}", unknownId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.type").value("https://quizzence.com/docs/errors/resource-not-found"))
                .andExpect(jsonPath("$.title").value("Resource Not Found"))
                .andExpect(jsonPath("$.detail").value("Document not found: " + unknownId))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    @WithMockUser
    @DisplayName("illegalState_returns422 - for 'no normalized text'")
    void illegalState_returns422() throws Exception {
        // Given
        when(queryService.getTextSlice(any(UUID.class), anyInt(), anyInt()))
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
    @WithMockUser
    @DisplayName("postDocuments_json_201_createdAndLocationHeader - Body {text:'hi',language:'en'} → 201, Location header present, body has id, status")
    void postDocuments_json_201_createdAndLocationHeader() throws Exception {
        // Given
        when(ingestionService.ingestFromText(anyString(), anyString(), anyString()))
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
    }

    @Test
    @WithMockUser
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
        
        when(ingestionService.ingestFromFile(anyString(), any(byte[].class)))
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
    @DisplayName("postDocuments_multipart_filenameFallback_uploadBin - null original filename")
    void postDocuments_multipart_filenameFallback_uploadBin() throws Exception {
        // Given
        MockMultipartFile file = new MockMultipartFile(
                "file", null, "text/plain", "Hello world".getBytes()
        );
        
        when(ingestionService.ingestFromFile(anyString(), any(byte[].class)))
                .thenReturn(testDocument);
        when(mapper.toIngestResponse(testDocument)).thenReturn(testIngestResponse);

        // When & Then
        mockMvc.perform(multipart("/api/v1/documentProcess/documents")
                        .file(file)
                        .with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(documentId.toString()));
    }

    @Test
    @WithMockUser
    @DisplayName("postDocuments_multipart_unsupportedExt_415 - bubbles from service")
    void postDocuments_multipart_unsupportedExt_415() throws Exception {
        // Given
        MockMultipartFile file = new MockMultipartFile(
                "file", "test.xyz", "application/octet-stream", "test content".getBytes()
        );
        
        when(ingestionService.ingestFromFile(anyString(), any(byte[].class)))
                .thenThrow(new UnsupportedFormatException("Unsupported format: .xyz"));

        // When & Then
        mockMvc.perform(multipart("/api/v1/documentProcess/documents")
                        .file(file)
                        .with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.type").value("https://quizzence.com/docs/errors/unsupported-format"))
                .andExpect(jsonPath("$.title").value("Unsupported Format"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    @WithMockUser
    @DisplayName("getDocument_200_returnsDocumentView - Get document metadata")
    void getDocument_200_returnsDocumentView() throws Exception {
        // Given
        when(queryService.getDocument(documentId)).thenReturn(testDocument);
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
    }

    @Test
    @WithMockUser
    @DisplayName("getTextSlice_onlyStart_defaultsToCharCount - ensures controller uses getTextLength() projection")
    void getTextSlice_onlyStart_defaultsToCharCount() throws Exception {
        // Given
        when(queryService.getTextLength(documentId)).thenReturn(11);
        when(queryService.getTextSlice(documentId, 0, 11)).thenReturn("Hello world");
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
        when(queryService.getTextSlice(documentId, 0, 5)).thenReturn("Hello");
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
        when(queryService.getTextSlice(documentId, 10, 5))
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

        when(structureService.extractByNode(documentId, nodeId)).thenReturn(expectedResponse);

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

        when(structureService.extractByNode(documentId, nodeId))
                .thenThrow(new ResourceNotFoundException("Document not found: " + documentId));

        // When & Then
        mockMvc.perform(get("/api/v1/documentProcess/documents/{id}/extract", documentId)
                        .param("nodeId", nodeId.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.type").value("https://quizzence.com/docs/errors/resource-not-found"))
                .andExpect(jsonPath("$.title").value("Resource Not Found"))
                .andExpect(jsonPath("$.detail").value("Document not found: " + documentId))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    @DisplayName("Extract by node ID - Node not found")
    @WithMockUser
    void extractByNodeId_NodeNotFound() throws Exception {
        // Given
        UUID documentId = UUID.randomUUID();
        UUID nodeId = UUID.randomUUID();

        when(structureService.extractByNode(documentId, nodeId))
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

        when(structureService.extractByNode(documentId, nodeId))
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
