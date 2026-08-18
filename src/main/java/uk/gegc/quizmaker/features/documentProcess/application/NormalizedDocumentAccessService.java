package uk.gegc.quizmaker.features.documentProcess.application;

import uk.gegc.quizmaker.features.documentProcess.api.dto.ExtractResponse;
import uk.gegc.quizmaker.features.documentProcess.api.dto.StructureFlatResponse;
import uk.gegc.quizmaker.features.documentProcess.api.dto.StructureTreeResponse;
import uk.gegc.quizmaker.features.documentProcess.domain.model.NormalizedDocument;

import java.util.UUID;

/** Owner-scoped application boundary for the normalized-document API. */
public interface NormalizedDocumentAccessService {

    NormalizedDocument ingestFromText(String username, String originalName, String language, String text);

    NormalizedDocument ingestFromFile(String username, String originalName, byte[] bytes);

    NormalizedDocument getDocument(String username, UUID documentId);

    int getTextLength(String username, UUID documentId);

    String getTextSlice(String username, UUID documentId, int start, int end);

    StructureTreeResponse getTree(String username, UUID documentId);

    StructureFlatResponse getFlat(String username, UUID documentId);

    void buildStructure(String username, UUID documentId);

    ExtractResponse extractByNode(String username, UUID documentId, UUID nodeId);
}
