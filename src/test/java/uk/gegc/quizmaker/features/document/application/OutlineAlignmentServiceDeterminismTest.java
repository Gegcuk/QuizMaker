package uk.gegc.quizmaker.features.document.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gegc.quizmaker.features.document.api.dto.DocumentOutlineDto;
import uk.gegc.quizmaker.features.document.api.dto.OutlineNodeDto;
import uk.gegc.quizmaker.features.document.domain.model.DocumentNode;
import uk.gegc.quizmaker.features.document.infra.text.SentenceBoundaryDetector;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutlineAlignmentServiceDeterminismTest {

    @Mock
    private SentenceBoundaryDetector sentenceBoundaryDetector;

    private OutlineAlignmentService outlineAlignmentService;

    @BeforeEach
    void setUp() {
        outlineAlignmentService = new OutlineAlignmentService(sentenceBoundaryDetector);
    }

    @Test
    void alignOutlineToOffsets_identicalInputs_producesIdenticalResults() {
        // Given
        String text = "Chapter 1: Introduction. This is the first chapter. " +
                     "Chapter 2: Main Content. This is the second chapter. " +
                     "Chapter 3: Conclusion. This is the final chapter.";
        
        DocumentOutlineDto outline = new DocumentOutlineDto(List.of(
            new OutlineNodeDto("CHAPTER", "Chapter 1: Introduction", "Chapter 1: Introduction", "Chapter 1: Introduction end", List.of()),
            new OutlineNodeDto("CHAPTER", "Chapter 2: Main Content", "Chapter 2: Main Content", "Chapter 2: Main Content end", List.of()),
            new OutlineNodeDto("CHAPTER", "Chapter 3: Conclusion", "Chapter 3: Conclusion", "Chapter 3: Conclusion end", List.of())
        ));
        
        CanonicalTextService.CanonicalizedText canonicalText = new CanonicalTextService.CanonicalizedText(text, "hash", List.of(), List.of());
        List<PreSegmentationService.PreSegmentationWindow> windows = List.of();
        UUID documentId = UUID.randomUUID();
        String sourceVersionHash = "test-hash";
        


        // When - run alignment multiple times
        List<DocumentNode> result1 = outlineAlignmentService.alignOutlineToOffsets(outline, canonicalText, windows, documentId, sourceVersionHash);
        List<DocumentNode> result2 = outlineAlignmentService.alignOutlineToOffsets(outline, canonicalText, windows, documentId, sourceVersionHash);
        List<DocumentNode> result3 = outlineAlignmentService.alignOutlineToOffsets(outline, canonicalText, windows, documentId, sourceVersionHash);

        // Then - all results should be identical
        assertThat(result1).isNotNull();
        assertThat(result2).isNotNull();
        assertThat(result3).isNotNull();
        
        assertThat(result1).hasSameSizeAs(result2);
        assertThat(result2).hasSameSizeAs(result3);
        
        // Compare each node's properties
        for (int i = 0; i < result1.size(); i++) {
            DocumentNode node1 = result1.get(i);
            DocumentNode node2 = result2.get(i);
            DocumentNode node3 = result3.get(i);
            
            assertThat(node1.getStartOffset()).isEqualTo(node2.getStartOffset());
            assertThat(node2.getStartOffset()).isEqualTo(node3.getStartOffset());
            
            assertThat(node1.getEndOffset()).isEqualTo(node2.getEndOffset());
            assertThat(node2.getEndOffset()).isEqualTo(node3.getEndOffset());
            
            assertThat(node1.getConfidence()).isEqualTo(node2.getConfidence());
            assertThat(node2.getConfidence()).isEqualTo(node3.getConfidence());
            
            assertThat(node1.getTitle()).isEqualTo(node2.getTitle());
            assertThat(node2.getTitle()).isEqualTo(node3.getTitle());
        }
    }

    @Test
    void alignOutlineToOffsets_withFuzzyMatching_producesConsistentResults() {
        // Given - text with slight variations in chapter titles
        String text = "Chapter 1: Introduction to the topic. This is the first chapter. " +
                     "Chapter 2: Main Content and details. This is the second chapter. " +
                     "Chapter 3: Conclusion and summary. This is the final chapter.";
        
        DocumentOutlineDto outline = new DocumentOutlineDto(List.of(
            new OutlineNodeDto("CHAPTER", "Chapter 1: Introduction", "Chapter 1: Introduction", "Chapter 1: Introduction end", List.of()),
            new OutlineNodeDto("CHAPTER", "Chapter 2: Main Content", "Chapter 2: Main Content", "Chapter 2: Main Content end", List.of()),
            new OutlineNodeDto("CHAPTER", "Chapter 3: Conclusion", "Chapter 3: Conclusion", "Chapter 3: Conclusion end", List.of())
        ));
        
        CanonicalTextService.CanonicalizedText canonicalText = new CanonicalTextService.CanonicalizedText(text, "hash", List.of(), List.of());
        List<PreSegmentationService.PreSegmentationWindow> windows = List.of();
        UUID documentId = UUID.randomUUID();
        String sourceVersionHash = "test-hash";

        // When - run alignment multiple times
        List<DocumentNode> result1 = outlineAlignmentService.alignOutlineToOffsets(outline, canonicalText, windows, documentId, sourceVersionHash);
        List<DocumentNode> result2 = outlineAlignmentService.alignOutlineToOffsets(outline, canonicalText, windows, documentId, sourceVersionHash);

        // Then - fuzzy matching should produce consistent results
        assertThat(result1).isNotNull();
        assertThat(result2).isNotNull();
        assertThat(result1).hasSameSizeAs(result2);
        
        // Compare offsets and confidence scores
        for (int i = 0; i < result1.size(); i++) {
            DocumentNode node1 = result1.get(i);
            DocumentNode node2 = result2.get(i);
            
            assertThat(node1.getStartOffset()).isEqualTo(node2.getStartOffset());
            assertThat(node1.getEndOffset()).isEqualTo(node2.getEndOffset());
            assertThat(node1.getConfidence()).isEqualTo(node2.getConfidence());
        }
    }

    @Test
    void alignOutlineToOffsets_withComplexHierarchy_producesDeterministicResults() {
        // Given - complex hierarchical structure
        String text = "Part 1: Overview. " +
                     "Chapter 1: Introduction. This is the first chapter. " +
                     "Section 1.1: Background. This is the background section. " +
                     "Section 1.2: Methodology. This is the methodology section. " +
                     "Chapter 2: Analysis. This is the analysis chapter. " +
                     "Section 2.1: Data Collection. This is the data collection section. " +
                     "Section 2.2: Results. This is the results section. " +
                     "Part 2: Conclusion. " +
                     "Chapter 3: Summary. This is the summary chapter.";
        
        DocumentOutlineDto outline = new DocumentOutlineDto(List.of(
            new OutlineNodeDto("PART", "Part 1: Overview", "Part 1: Overview", "Part 1: Overview end", List.of(
                new OutlineNodeDto("CHAPTER", "Chapter 1: Introduction", "Chapter 1: Introduction", "Chapter 1: Introduction end", List.of(
                    new OutlineNodeDto("SECTION", "Section 1.1: Background", "Section 1.1: Background", "Section 1.1: Background end", List.of()),
                    new OutlineNodeDto("SECTION", "Section 1.2: Methodology", "Section 1.2: Methodology", "Section 1.2: Methodology end", List.of())
                )),
                new OutlineNodeDto("CHAPTER", "Chapter 2: Analysis", "Chapter 2: Analysis", "Chapter 2: Analysis end", List.of(
                    new OutlineNodeDto("SECTION", "Section 2.1: Data Collection", "Section 2.1: Data Collection", "Section 2.1: Data Collection end", List.of()),
                    new OutlineNodeDto("SECTION", "Section 2.2: Results", "Section 2.2: Results", "Section 2.2: Results end", List.of())
                ))
            )),
            new OutlineNodeDto("PART", "Part 2: Conclusion", "Part 2: Conclusion", "Part 2: Conclusion end", List.of(
                new OutlineNodeDto("CHAPTER", "Chapter 3: Summary", "Chapter 3: Summary", "Chapter 3: Summary end", List.of())
            ))
        ));
        
        CanonicalTextService.CanonicalizedText canonicalText = new CanonicalTextService.CanonicalizedText(text, "hash", List.of(), List.of());
        List<PreSegmentationService.PreSegmentationWindow> windows = List.of();
        UUID documentId = UUID.randomUUID();
        String sourceVersionHash = "test-hash";

        // When - run alignment multiple times
        List<DocumentNode> result1 = outlineAlignmentService.alignOutlineToOffsets(outline, canonicalText, windows, documentId, sourceVersionHash);
        List<DocumentNode> result2 = outlineAlignmentService.alignOutlineToOffsets(outline, canonicalText, windows, documentId, sourceVersionHash);

        // Then - complex hierarchy should produce deterministic results
        assertThat(result1).isNotNull();
        assertThat(result2).isNotNull();
        assertThat(result1).hasSameSizeAs(result2);
        
        // Compare all nodes recursively
        compareNodeLists(result1, result2);
    }

    private void compareNodeLists(List<DocumentNode> nodes1, List<DocumentNode> nodes2) {
        assertThat(nodes1).hasSameSizeAs(nodes2);
        
        for (int i = 0; i < nodes1.size(); i++) {
            DocumentNode node1 = nodes1.get(i);
            DocumentNode node2 = nodes2.get(i);
            
            // Compare basic properties
            assertThat(node1.getStartOffset()).isEqualTo(node2.getStartOffset());
            assertThat(node1.getEndOffset()).isEqualTo(node2.getEndOffset());
            assertThat(node1.getConfidence()).isEqualTo(node2.getConfidence());
            assertThat(node1.getTitle()).isEqualTo(node2.getTitle());
            assertThat(node1.getType()).isEqualTo(node2.getType());
            
            // Recursively compare children if they exist
            if (node1.getChildren() != null && node2.getChildren() != null) {
                compareNodeLists(node1.getChildren(), node2.getChildren());
            }
        }
    }
}
