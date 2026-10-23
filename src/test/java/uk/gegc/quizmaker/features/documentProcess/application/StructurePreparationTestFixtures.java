package uk.gegc.quizmaker.features.documentProcess.application;

import org.mockito.ArgumentCaptor;
import uk.gegc.quizmaker.features.documentProcess.domain.model.DocumentNode;
import uk.gegc.quizmaker.features.documentProcess.infra.repository.DocumentNodeRepository;

import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Orchestration fixtures only; repository invocations do not prove durable publication. */
final class StructurePreparationTestFixtures {
    private StructurePreparationTestFixtures() { }

    static void stubLevelOffsets(AnchorOffsetCalculator calculator, List<DocumentNode> templates) {
        when(calculator.calculateOffsets(anyList(), anyString())).thenAnswer(call -> {
            List<DocumentNode> level = call.getArgument(0);
            for (DocumentNode node : level) {
                DocumentNode template = templates.stream()
                        .filter(candidate -> Objects.equals(candidate.getTitle(), node.getTitle())
                                && Objects.equals(candidate.getDepth(), node.getDepth())
                                && Objects.equals(candidate.getStartAnchor(), node.getStartAnchor()))
                        .findFirst().orElseThrow();
                node.setStartOffset(template.getStartOffset());
                node.setEndOffset(template.getEndOffset());
            }
            return level;
        });
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    static void assertEachNodePublishedOnce(DocumentNodeRepository repository, List<DocumentNode> expected) {
        ArgumentCaptor<List<DocumentNode>> levels = ArgumentCaptor.forClass((Class) List.class);
        int depthCount = (int) expected.stream().map(DocumentNode::getDepth).distinct().count();
        verify(repository, times(depthCount)).saveAll(levels.capture());
        List<DocumentNode> actual = levels.getAllValues().stream().flatMap(List::stream).toList();
        assertThat(actual).hasSize(expected.size()).doesNotHaveDuplicates();
        assertThat(actual).extracting(DocumentNode::getTitle)
                .containsExactlyInAnyOrderElementsOf(expected.stream().map(DocumentNode::getTitle).toList());
        assertThat(actual).allSatisfy(node -> {
            assertThat(node.getDocument()).isNotNull();
            if (node.getParent() != null) {
                assertThat(actual).contains(node.getParent());
                assertThat(node.getParent().getDepth()).isLessThan(node.getDepth());
            }
        });
    }
}
