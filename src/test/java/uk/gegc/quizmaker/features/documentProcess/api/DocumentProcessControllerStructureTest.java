package uk.gegc.quizmaker.features.documentProcess.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import uk.gegc.quizmaker.features.documentProcess.api.dto.FlatNode;
import uk.gegc.quizmaker.features.documentProcess.api.dto.NodeView;
import uk.gegc.quizmaker.features.documentProcess.api.dto.StructureFlatResponse;
import uk.gegc.quizmaker.features.documentProcess.api.dto.StructureTreeResponse;
import uk.gegc.quizmaker.features.documentProcess.application.DocumentIngestionService;
import uk.gegc.quizmaker.features.documentProcess.application.DocumentQueryService;
import uk.gegc.quizmaker.features.documentProcess.application.StructureService;
import uk.gegc.quizmaker.features.documentProcess.domain.model.DocumentNode;
import uk.gegc.quizmaker.features.documentProcess.infra.mapper.DocumentMapper;
import uk.gegc.quizmaker.shared.exception.ResourceNotFoundException;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(DocumentProcessController.class)
@DisplayName("DocumentProcessController Structure Endpoints")
class DocumentProcessControllerStructureTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private DocumentIngestionService ingestionService;

    @MockitoBean
    private DocumentQueryService queryService;

    @MockitoBean
    private StructureService structureService;

    @MockitoBean
    private DocumentMapper mapper;

    private final UUID documentId = UUID.randomUUID();

    @Test
    @DisplayName("getStructure_tree_ok_200_andBodyMatchesContract")
    @WithMockUser
    void getStructure_tree_ok_200_andBodyMatchesContract() throws Exception {
        // Given
        NodeView rootNode = new NodeView(
                UUID.randomUUID(), documentId, null, 1, DocumentNode.NodeType.CHAPTER,
                "Chapter 1", 0, 100, (short) 0, new BigDecimal("0.95"), null, List.of()
        );
        StructureTreeResponse expectedResponse = new StructureTreeResponse(
                documentId, List.of(rootNode), 1
        );

        when(structureService.getTree(documentId)).thenReturn(expectedResponse);

        // When & Then
        mockMvc.perform(get("/api/v1/documentProcess/documents/{id}/structure", documentId)
                        .param("format", "tree")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json(objectMapper.writeValueAsString(expectedResponse)));
    }

    @Test
    @DisplayName("getStructure_flat_ok_200_andBodyMatchesContract")
    @WithMockUser
    void getStructure_flat_ok_200_andBodyMatchesContract() throws Exception {
        // Given
        FlatNode flatNode = new FlatNode(
                UUID.randomUUID(), documentId, null, 1, DocumentNode.NodeType.CHAPTER,
                "Chapter 1", 0, 100, (short) 0, new BigDecimal("0.95"), null
        );
        StructureFlatResponse expectedResponse = new StructureFlatResponse(
                documentId, List.of(flatNode), 1
        );

        when(structureService.getFlat(documentId)).thenReturn(expectedResponse);

        // When & Then
        mockMvc.perform(get("/api/v1/documentProcess/documents/{id}/structure", documentId)
                        .param("format", "flat")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json(objectMapper.writeValueAsString(expectedResponse)));
    }

    @Test
    @DisplayName("getStructure_invalidFormat_400")
    @WithMockUser
    void getStructure_invalidFormat_400() throws Exception {
        // When & Then
        mockMvc.perform(get("/api/v1/documentProcess/documents/{id}/structure", documentId)
                        .param("format", "invalid")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("Invalid format. Use 'tree' or 'flat'"));
    }

    @Test
    @DisplayName("getStructure_unknownDocument_404")
    @WithMockUser
    void getStructure_unknownDocument_404() throws Exception {
        // Given
        when(structureService.getTree(documentId))
                .thenThrow(new ResourceNotFoundException("Document not found: " + documentId));

        // When & Then
        mockMvc.perform(get("/api/v1/documentProcess/documents/{id}/structure", documentId)
                        .param("format", "tree")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("getStructure_defaultFormat_usesTree")
    @WithMockUser
    void getStructure_defaultFormat_usesTree() throws Exception {
        // Given
        StructureTreeResponse expectedResponse = new StructureTreeResponse(
                documentId, List.of(), 0
        );

        when(structureService.getTree(documentId)).thenReturn(expectedResponse);

        // When & Then
        mockMvc.perform(get("/api/v1/documentProcess/documents/{id}/structure", documentId)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().json(objectMapper.writeValueAsString(expectedResponse)));
    }
}
