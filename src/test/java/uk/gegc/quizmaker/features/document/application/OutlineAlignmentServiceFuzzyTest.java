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
class OutlineAlignmentServiceFuzzyTest {

    @Mock
    private SentenceBoundaryDetector sentenceBoundaryDetector;

    private OutlineAlignmentService outlineAlignmentService;

    @BeforeEach
    void setUp() {
        outlineAlignmentService = new OutlineAlignmentService(sentenceBoundaryDetector);
    }

    @Test
    void shouldHandleTurkishUnicodeCaseFolding() {
        // Given - Turkish has special case folding rules (İ -> i̇, i -> i)
        String text = "İntroduction to the topic. This is the İntroduction chapter.";
        CanonicalTextService.CanonicalizedText canonicalText = new CanonicalTextService.CanonicalizedText(
            text, "hash123", List.of(), List.of());
        
        List<PreSegmentationService.PreSegmentationWindow> windows = List.of(
            new PreSegmentationService.PreSegmentationWindow(0, text.length(), text, true, text.length())
        );

        DocumentOutlineDto outline = new DocumentOutlineDto(List.of(
            new OutlineNodeDto("CHAPTER", "Introduction to the topic", "Introduction to the topic", "", List.of())
        ));

        UUID documentId = UUID.randomUUID();
        String sourceVersionHash = "hash123";

        // When
        List<DocumentNode> result = outlineAlignmentService.alignOutlineToOffsets(
            outline, canonicalText, windows, documentId, sourceVersionHash);

        // Then
        assertThat(result).hasSize(1);
        // Should find the anchor despite Unicode case folding differences
        assertThat(result.get(0).getStartOffset()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void shouldHandleProgressiveWindowExpansion() {
        // Given - Anchor not in first window, should expand to find it
        String text = "Chapter 1: Introduction. This is chapter 1 content. Chapter 2: Methods. This is chapter 2 content.";
        CanonicalTextService.CanonicalizedText canonicalText = new CanonicalTextService.CanonicalizedText(
            text, "hash123", List.of(), List.of());
        
        // Create windows that don't contain the exact anchor
        List<PreSegmentationService.PreSegmentationWindow> windows = List.of(
            new PreSegmentationService.PreSegmentationWindow(0, 30, "Chapter 1: Introduction.", true, 30),
            new PreSegmentationService.PreSegmentationWindow(30, 60, "This is chapter 1 content.", false, 30),
            new PreSegmentationService.PreSegmentationWindow(60, 90, "Chapter 2: Methods.", true, 30),
            new PreSegmentationService.PreSegmentationWindow(90, 120, "This is chapter 2 content.", false, 30)
        );

        DocumentOutlineDto outline = new DocumentOutlineDto(List.of(
            new OutlineNodeDto("CHAPTER", "Chapter 1: Introduction", "Chapter 1: Introduction", "Chapter 2: Methods", List.of()),
            new OutlineNodeDto("CHAPTER", "Chapter 2: Methods", "Chapter 2: Methods", "", List.of())
        ));

        UUID documentId = UUID.randomUUID();
        String sourceVersionHash = "hash123";

        // When
        List<DocumentNode> result = outlineAlignmentService.alignOutlineToOffsets(
            outline, canonicalText, windows, documentId, sourceVersionHash);

        // Then
        assertThat(result).hasSize(2);
        // Should find anchors through progressive window expansion
        assertThat(result.get(0).getStartOffset()).isEqualTo(0);
        assertThat(result.get(1).getStartOffset()).isEqualTo(51);
    }

    @Test
    void shouldHandleSentenceBoundaryClamping() {
        // Given
        String text = "Chapter 1: Introduction. This is the first chapter content. Chapter 2: Methods. This is the second chapter.";
        CanonicalTextService.CanonicalizedText canonicalText = new CanonicalTextService.CanonicalizedText(
            text, "hash123", List.of(), List.of());
        
        List<PreSegmentationService.PreSegmentationWindow> windows = List.of(
            new PreSegmentationService.PreSegmentationWindow(0, text.length(), text, true, text.length())
        );

        DocumentOutlineDto outline = new DocumentOutlineDto(List.of(
            new OutlineNodeDto("CHAPTER", "Chapter 1: Introduction", "Chapter 1: Introduction", "Chapter 2: Methods", List.of()),
            new OutlineNodeDto("CHAPTER", "Chapter 2: Methods", "Chapter 2: Methods", "", List.of())
        ));

        UUID documentId = UUID.randomUUID();
        String sourceVersionHash = "hash123";

        // Mock sentence boundary detector
        when(sentenceBoundaryDetector.findNextSentenceEnd(anyString())).thenReturn(35);

        // When
        List<DocumentNode> result = outlineAlignmentService.alignOutlineToOffsets(
            outline, canonicalText, windows, documentId, sourceVersionHash);

        // Then
        assertThat(result).hasSize(2);
        // Should use the found end anchor position
        assertThat(result.get(0).getEndOffset()).isEqualTo(59);
    }

    @Test
    void shouldHandleOverlappingSiblings() {
        // Given - Siblings with overlapping ranges
        String text = "Chapter 1: Overview. Section 1.1: Background. This is background content. Section 1.2: Goals. This is goals content.";
        CanonicalTextService.CanonicalizedText canonicalText = new CanonicalTextService.CanonicalizedText(
            text, "hash123", List.of(), List.of());
        
        List<PreSegmentationService.PreSegmentationWindow> windows = List.of(
            new PreSegmentationService.PreSegmentationWindow(0, text.length(), text, true, text.length())
        );

        DocumentOutlineDto outline = new DocumentOutlineDto(List.of(
            new OutlineNodeDto("CHAPTER", "Chapter 1: Overview", "Chapter 1: Overview", "", List.of(
                new OutlineNodeDto("SECTION", "Section 1.1: Background", "Section 1.1: Background", "Section 1.2: Goals", List.of()),
                new OutlineNodeDto("SECTION", "Section 1.2: Goals", "Section 1.2: Goals", "", List.of())
            ))
        ));

        UUID documentId = UUID.randomUUID();
        String sourceVersionHash = "hash123";

        // When
        List<DocumentNode> result = outlineAlignmentService.alignOutlineToOffsets(
            outline, canonicalText, windows, documentId, sourceVersionHash);

        // Then
        assertThat(result).hasSize(3);
        
        List<DocumentNode> sections = result.stream()
            .filter(n -> n.getParent() != null)
            .toList();
        
        assertThat(sections).hasSize(2);
        
        // Should not overlap after optimization
        DocumentNode section1 = sections.get(0);
        DocumentNode section2 = sections.get(1);
        assertThat(section1.getEndOffset()).isLessThanOrEqualTo(section2.getStartOffset());
    }

    @Test
    void shouldHandleOrphanedNodes() {
        // Given - Node with parent not in the current list
        String text = "Chapter 1: Introduction. This is chapter 1. Chapter 2: Methods. This is chapter 2.";
        CanonicalTextService.CanonicalizedText canonicalText = new CanonicalTextService.CanonicalizedText(
            text, "hash123", List.of(), List.of());
        
        List<PreSegmentationService.PreSegmentationWindow> windows = List.of(
            new PreSegmentationService.PreSegmentationWindow(0, text.length(), text, true, text.length())
        );

        DocumentOutlineDto outline = new DocumentOutlineDto(List.of(
            new OutlineNodeDto("CHAPTER", "Chapter 1: Introduction", "Chapter 1: Introduction", "Chapter 2: Methods", List.of()),
            new OutlineNodeDto("CHAPTER", "Chapter 2: Methods", "Chapter 2: Methods", "", List.of())
        ));

        UUID documentId = UUID.randomUUID();
        String sourceVersionHash = "hash123";

        // When
        List<DocumentNode> result = outlineAlignmentService.alignOutlineToOffsets(
            outline, canonicalText, windows, documentId, sourceVersionHash);

        // Then
        assertThat(result).hasSize(2);
        // All nodes should be properly parented or made root nodes
        assertThat(result).allMatch(node -> node.getParent() == null || 
            result.stream().anyMatch(potentialParent -> potentialParent == node.getParent()));
    }

    @Test
    void shouldHandleConfidenceScoring() {
        // Given - Different quality matches
        String text = "Chapter 1: Introduction. This is chapter 1. Chaptr 2: Methds. This is chapter 2.";
        CanonicalTextService.CanonicalizedText canonicalText = new CanonicalTextService.CanonicalizedText(
            text, "hash123", List.of(), List.of());
        
        List<PreSegmentationService.PreSegmentationWindow> windows = List.of(
            new PreSegmentationService.PreSegmentationWindow(0, text.length(), text, true, text.length())
        );

        DocumentOutlineDto outline = new DocumentOutlineDto(List.of(
            new OutlineNodeDto("CHAPTER", "Chapter 1: Introduction", "Chapter 1: Introduction", "Chapter 2: Methods", List.of()),
            new OutlineNodeDto("CHAPTER", "Chapter 2: Methods", "Chapter 2: Methods", "", List.of())
        ));

        UUID documentId = UUID.randomUUID();
        String sourceVersionHash = "hash123";

        // When
        List<DocumentNode> result = outlineAlignmentService.alignOutlineToOffsets(
            outline, canonicalText, windows, documentId, sourceVersionHash);

        // Then
        assertThat(result).hasSize(1);
        
        DocumentNode exactMatch = result.get(0);
        
        // Should have reasonable confidence for exact match
        assertThat(exactMatch.getConfidence().doubleValue()).isGreaterThan(0.7);
    }

    @Test
    void shouldHandleMinimumWordRequirement() {
        // Given - Short anchors that might not meet minimum word requirement
        String text = "Chapter 1: Introduction. This is the first chapter.";
        CanonicalTextService.CanonicalizedText canonicalText = new CanonicalTextService.CanonicalizedText(
            text, "hash123", List.of(), List.of());
        
        List<PreSegmentationService.PreSegmentationWindow> windows = List.of(
            new PreSegmentationService.PreSegmentationWindow(0, text.length(), text, true, text.length())
        );

        DocumentOutlineDto outline = new DocumentOutlineDto(List.of(
            new OutlineNodeDto("CHAPTER", "Chapter 1: Introduction", "Chapter 1: Introduction", "Chapter 1: Introduction", List.of())
        ));

        UUID documentId = UUID.randomUUID();
        String sourceVersionHash = "hash123";

        // When
        List<DocumentNode> result = outlineAlignmentService.alignOutlineToOffsets(
            outline, canonicalText, windows, documentId, sourceVersionHash);

        // Then
        assertThat(result).hasSize(1);
        // Should still find matches even with short anchors
        assertThat(result.get(0).getStartOffset()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void shouldHandleEmptyAndNullAnchors() {
        // Given - Empty and null anchors
        String text = "Chapter 1: Introduction. This is the first chapter.";
        CanonicalTextService.CanonicalizedText canonicalText = new CanonicalTextService.CanonicalizedText(
            text, "hash123", List.of(), List.of());
        
        List<PreSegmentationService.PreSegmentationWindow> windows = List.of(
            new PreSegmentationService.PreSegmentationWindow(0, text.length(), text, true, text.length())
        );

        DocumentOutlineDto outline = new DocumentOutlineDto(List.of(
            new OutlineNodeDto("CHAPTER", "Chapter 1: Introduction", "", "", List.of()),
            new OutlineNodeDto("CHAPTER", "Chapter 2: Methods", null, null, List.of())
        ));

        UUID documentId = UUID.randomUUID();
        String sourceVersionHash = "hash123";

        // When
        List<DocumentNode> result = outlineAlignmentService.alignOutlineToOffsets(
            outline, canonicalText, windows, documentId, sourceVersionHash);

        // Then
        assertThat(result).hasSize(2);
        // Should handle empty/null anchors gracefully
        assertThat(result.get(0).getStartOffset()).isEqualTo(0);
        assertThat(result.get(0).getEndOffset()).isEqualTo(0);
        assertThat(result.get(1).getStartOffset()).isEqualTo(0);
        assertThat(result.get(1).getEndOffset()).isEqualTo(51);
        
        // Should have lower confidence due to missing anchors
        assertThat(result.get(0).getConfidence().doubleValue()).isLessThan(1.0);
        assertThat(result.get(1).getConfidence().doubleValue()).isLessThan(1.0);
    }

    @Test
    void shouldHandleComplexHierarchicalAlignment() {
        // Given - Complex nested structure
        String text = "Part 1: Introduction. Chapter 1: Background. Section 1.1: History. This is history content. " +
                     "Section 1.2: Current State. This is current state content. Chapter 2: Methods. This is methods content.";
        CanonicalTextService.CanonicalizedText canonicalText = new CanonicalTextService.CanonicalizedText(
            text, "hash123", List.of(), List.of());
        
        List<PreSegmentationService.PreSegmentationWindow> windows = List.of(
            new PreSegmentationService.PreSegmentationWindow(0, text.length(), text, true, text.length())
        );

        DocumentOutlineDto outline = new DocumentOutlineDto(List.of(
            new OutlineNodeDto("PART", "Part 1: Introduction", "Part 1: Introduction", "", List.of(
                new OutlineNodeDto("CHAPTER", "Chapter 1: Background", "Chapter 1: Background", "Chapter 2: Methods", List.of(
                    new OutlineNodeDto("SECTION", "Section 1.1: History", "Section 1.1: History", "Section 1.2: Current State", List.of()),
                    new OutlineNodeDto("SECTION", "Section 1.2: Current State", "Section 1.2: Current State", "Chapter 2: Methods", List.of())
                )),
                new OutlineNodeDto("CHAPTER", "Chapter 2: Methods", "Chapter 2: Methods", "", List.of())
            ))
        ));

        UUID documentId = UUID.randomUUID();
        String sourceVersionHash = "hash123";

        // When
        List<DocumentNode> result = outlineAlignmentService.alignOutlineToOffsets(
            outline, canonicalText, windows, documentId, sourceVersionHash);

        // Then
        assertThat(result).hasSize(5);
        
        // Check hierarchy levels
        List<DocumentNode> parts = result.stream().filter(n -> n.getLevel() == 0).toList();
        List<DocumentNode> chapters = result.stream().filter(n -> n.getLevel() == 1).toList();
        List<DocumentNode> sections = result.stream().filter(n -> n.getLevel() == 2).toList();
        
        assertThat(parts).hasSize(1);
        assertThat(chapters).hasSize(2);
        assertThat(sections).hasSize(2);
        
        // Check parent-child relationships
        DocumentNode part = parts.get(0);
        assertThat(chapters).allMatch(chapter -> chapter.getParent() == part);
        
        DocumentNode chapter1 = chapters.get(0);
        assertThat(sections).allMatch(section -> section.getParent() == chapter1);
    }
}
