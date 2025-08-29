package uk.gegc.quizmaker.features.documentProcess.infra.mapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uk.gegc.quizmaker.features.documentProcess.api.dto.DocumentView;
import uk.gegc.quizmaker.features.documentProcess.api.dto.IngestResponse;
import uk.gegc.quizmaker.features.documentProcess.api.dto.TextSliceResponse;
import uk.gegc.quizmaker.features.documentProcess.domain.model.NormalizedDocument;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DocumentMapperTest {

    private DocumentMapper mapper;
    private NormalizedDocument document;
    private UUID documentId;
    private Instant createdAt;
    private Instant updatedAt;

    @BeforeEach
    void setUp() {
        mapper = new DocumentMapper();
        
        documentId = UUID.randomUUID();
        createdAt = Instant.now().minusSeconds(3600); // 1 hour ago
        updatedAt = Instant.now();
        
        document = new NormalizedDocument();
        document.setId(documentId);
        document.setOriginalName("test-document.txt");
        document.setMime("text/plain");
        document.setSource(NormalizedDocument.DocumentSource.TEXT);
        document.setLanguage("en");
        document.setNormalizedText("This is the normalized text content");
        document.setCharCount(35);
        document.setStatus(NormalizedDocument.DocumentStatus.NORMALIZED);
        document.setCreatedAt(createdAt);
        document.setUpdatedAt(updatedAt);
    }

    @Test
    void toDocumentView_mapsAllFields() {
        DocumentView result = mapper.toDocumentView(document);
        
        assertEquals(documentId, result.id());
        assertEquals("test-document.txt", result.originalName());
        assertEquals("text/plain", result.mime());
        assertEquals(NormalizedDocument.DocumentSource.TEXT, result.source());
        assertEquals(35, result.charCount());
        assertEquals("en", result.language());
        assertEquals(NormalizedDocument.DocumentStatus.NORMALIZED, result.status());
        assertEquals(createdAt, result.createdAt());
        assertEquals(updatedAt, result.updatedAt());
    }

    @Test
    void toIngestResponse_mapsIdAndStatus() {
        IngestResponse result = mapper.toIngestResponse(document);
        
        assertEquals(documentId, result.id());
        assertEquals(NormalizedDocument.DocumentStatus.NORMALIZED, result.status());
    }

    @Test
    void toTextSliceResponse_echoesArgs() {
        UUID docId = UUID.randomUUID();
        int start = 10;
        int end = 25;
        String text = "slice content";
        
        TextSliceResponse result = mapper.toTextSliceResponse(docId, start, end, text);
        
        assertEquals(docId, result.documentId());
        assertEquals(start, result.start());
        assertEquals(end, result.end());
        assertEquals(text, result.text());
    }

    @Test
    void toDocumentView_withNullFields_handlesGracefully() {
        document.setLanguage(null);
        document.setCharCount(null);
        
        DocumentView result = mapper.toDocumentView(document);
        
        assertNull(result.language());
        assertNull(result.charCount());
        assertNotNull(result.id());
        assertNotNull(result.originalName());
    }

    @Test
    void toIngestResponse_withFailedStatus_mapsCorrectly() {
        document.setStatus(NormalizedDocument.DocumentStatus.FAILED);
        
        IngestResponse result = mapper.toIngestResponse(document);
        
        assertEquals(NormalizedDocument.DocumentStatus.FAILED, result.status());
    }
}
