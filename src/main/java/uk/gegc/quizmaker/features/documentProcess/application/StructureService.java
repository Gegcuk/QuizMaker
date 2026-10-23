package uk.gegc.quizmaker.features.documentProcess.application;

import uk.gegc.quizmaker.features.documentProcess.api.dto.ExtractResponse;
import uk.gegc.quizmaker.features.documentProcess.api.dto.StructureFlatResponse;
import uk.gegc.quizmaker.features.documentProcess.api.dto.StructureTreeResponse;
import uk.gegc.quizmaker.features.documentProcess.domain.model.DocumentNode;

import java.util.List;
import java.util.UUID;

/** Structure preparation and atomic persistence; public builds require the owner-scoped boundary. */
public interface StructureService {
    StructureTreeResponse getTree(UUID documentId);
    StructureFlatResponse getFlat(UUID documentId);
    ExtractResponse extractByNode(UUID documentId, UUID nodeId);
    void buildStructure(UUID documentId);
    void buildStructure(UUID documentId, LlmClient.StructureOptions options);
    StructureBuildSource readBuildSource(UUID documentId);
    List<DocumentNode> prepare(StructureBuildSource source, LlmClient.StructureOptions options,
                               Runnable authorizeDispatch);
    void publish(StructureBuildSource source, List<DocumentNode> nodes);
}
