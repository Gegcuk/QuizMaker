package uk.gegc.quizmaker.features.documentProcess.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gegc.quizmaker.features.documentProcess.application.impl.StructureServiceImpl;
import uk.gegc.quizmaker.features.documentProcess.config.DocumentChunkingConfig;
import uk.gegc.quizmaker.features.documentProcess.domain.model.DocumentNode;
import uk.gegc.quizmaker.features.documentProcess.infra.repository.DocumentNodeRepository;
import uk.gegc.quizmaker.features.documentProcess.infra.repository.NormalizedDocumentRepository;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Preparation tests only; real durability/rollback is proved by the owner-bound MySQL tests. */
@ExtendWith(MockitoExtension.class)
class LayerByLayerResilienceTest {
    @Mock DocumentNodeRepository nodes;
    @Mock NormalizedDocumentRepository documents;
    @Mock LlmClient provider;
    @Mock ChunkedStructureService chunks;
    @Mock NodeHierarchyBuilder hierarchy;
    private AnchorOffsetCalculator offsets;
    private StructureService service;
    private StructureBuildSource source;

    @BeforeEach
    void setUp() {
        GuardedLlmClientDefaults.enable(provider);
        offsets = spy(new AnchorOffsetCalculator());
        service = new StructureServiceImpl(nodes, documents, null, provider, offsets, hierarchy, null,
                chunks, new ImmediateTransactionTemplate(), Clock.systemUTC());
        source = new StructureBuildSource(UUID.randomUUID(), UUID.randomUUID(), 0,
                "root\nsection\nleaf\nend", 21);
        when(chunks.getChunkingConfig()).thenReturn(new DocumentChunkingConfig());
    }

    @Test
    void preparesEveryLevelAndItsRelationshipsBeforeAnyDatabaseWrite() {
        when(provider.generateStructure(anyString(), any())).thenReturn(List.of(
                node("root", (short) 0), node("section", (short) 1), node("leaf", (short) 2)));
        AtomicBoolean authorized = new AtomicBoolean();
        List<DocumentNode> prepared = service.prepare(source, LlmClient.StructureOptions.defaultOptions(),
                () -> authorized.set(true));
        assertThat(authorized).isTrue();
        assertThat(prepared).hasSize(3);
        assertThat(prepared.get(1).getParent()).isSameAs(prepared.get(0));
        assertThat(prepared.get(2).getParent()).isSameAs(prepared.get(1));
        assertThat(prepared).allSatisfy(n -> {
            assertThat(n.getId()).isNull();
            assertThat(n.getIdx()).isZero();
            assertThat(n.getEndOffset()).isEqualTo(source.text().length());
        });
        verifyNoInteractions(nodes, documents);
    }

    @Test
    void failureAtDepthTwoDoesNotPersistEarlierPreparedLevels() {
        when(provider.generateStructure(anyString(), any())).thenReturn(List.of(
                node("root", (short) 0), node("section", (short) 1), node("leaf", (short) 2)));
        AtomicBoolean reached = new AtomicBoolean();
        doAnswer(call -> {
            List<DocumentNode> level = call.getArgument(0);
            if (level.get(0).getDepth() == 2) {
                reached.set(true);
                throw new IllegalStateException("controlled later-level fault");
            }
            return call.callRealMethod();
        }).when(offsets).calculateOffsets(anyList(), anyString());
        assertThatThrownBy(() -> service.prepare(source, LlmClient.StructureOptions.defaultOptions(), () -> { }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(reached).isTrue();
        verifyNoInteractions(nodes, documents);
    }

    @Test
    void providerFailureCannotProducePartialPublication() {
        when(provider.generateStructure(anyString(), any())).thenThrow(new LlmClient.LlmException("outage"));
        assertThatThrownBy(() -> service.prepare(source, LlmClient.StructureOptions.defaultOptions(), () -> { }))
                .isInstanceOf(IllegalStateException.class).hasMessage("Failed to generate document structure");
        verifyNoInteractions(nodes, documents);
    }

    private DocumentNode node(String start, short depth) {
        DocumentNode node = new DocumentNode();
        node.setStartAnchor(start);
        node.setEndAnchor("end");
        node.setTitle(start);
        node.setDepth(depth);
        node.setType(DocumentNode.NodeType.SECTION);
        return node;
    }
}
