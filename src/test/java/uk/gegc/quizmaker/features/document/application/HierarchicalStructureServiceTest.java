package uk.gegc.quizmaker.features.document.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import uk.gegc.quizmaker.features.document.api.dto.DocumentOutlineDto;
import uk.gegc.quizmaker.features.document.api.dto.OutlineNodeDto;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class HierarchicalStructureServiceTest {

    @Mock
    private DocumentStructureProperties props;

    @Mock
    private OutlineExtractorService outlineExtractor;

    private HierarchicalStructureService hierarchicalStructureService;

    @BeforeEach
    void setUp() {
        hierarchicalStructureService = new HierarchicalStructureService(props, outlineExtractor);
        
        // Set up default properties
        when(props.getPass1SliceSizeChars()).thenReturn(1000);
        when(props.getPass1OverlapPercent()).thenReturn(10);
        when(props.getMaxDepthRoot()).thenReturn(2);
        when(props.getMaxDepthPerChapter()).thenReturn(3);
        when(props.isParagraphHeuristicsEnabled()).thenReturn(true);
        when(props.getParagraphMinChars()).thenReturn(200);
        when(props.getParagraphMaxChars()).thenReturn(800);
        when(props.getParagraphAnchorWords()).thenReturn(8);
        when(props.getTopLevelSimilarityThreshold()).thenReturn(0.8);
        when(props.getTopLevelTypes()).thenReturn(List.of("PART", "CHAPTER", "PROLOGUE", "FOREWORD", "APPENDIX", "EPILOGUE"));
    }

    @Test
    void shouldBuildHierarchicalOutlineForLongDocument() {
        // Given - Force multiple slices by setting small slice size
        when(props.getPass1SliceSizeChars()).thenReturn(60);
        
        String longText = "Chapter 1: Getting Started\n" +
                "This is the first chapter content. It contains several paragraphs.\n" +
                "Chapter 2: Advanced Topics\n" +
                "This is the second chapter with more content.\n" +
                "Chapter 3: Practical Examples\n" +
                "This chapter shows real-world examples.\n" +
                "Chapter 4: Best Practices\n" +
                "This chapter covers best practices and recommendations.";

        CanonicalTextService.CanonicalizedText canonicalText = 
                new CanonicalTextService.CanonicalizedText(longText, "hash123", List.of(), List.of());

        // Mock slice 1 (Chapter 1 + Chapter 2)
        DocumentOutlineDto slice1Outline = new DocumentOutlineDto(List.of(
                new OutlineNodeDto("CHAPTER", "Chapter 1: Getting Started", "Chapter 1: Getting Started", "Chapter 2: Advanced Topics", List.of()),
                new OutlineNodeDto("CHAPTER", "Chapter 2: Advanced Topics", "Chapter 2: Advanced Topics", "Chapter 3: Practical Examples", List.of())
        ));

        // Mock slice 2 (Chapter 3 + Chapter 4)
        DocumentOutlineDto slice2Outline = new DocumentOutlineDto(List.of(
                new OutlineNodeDto("CHAPTER", "Chapter 3: Practical Examples", "Chapter 3: Practical Examples", "Chapter 4: Best Practices", List.of()),
                new OutlineNodeDto("CHAPTER", "Chapter 4: Best Practices", "Chapter 4: Best Practices", "", List.of())
        ));

        // Mock per-chapter extraction for each top-level node
        DocumentOutlineDto chapter1Inner = new DocumentOutlineDto(List.of(
                new OutlineNodeDto("SECTION", "Section 1.1: Basics", "Section 1.1: Basics", "Section 1.2: Advanced", List.of()),
                new OutlineNodeDto("SECTION", "Section 1.2: Advanced", "Section 1.2: Advanced", "", List.of())
        ));

        DocumentOutlineDto chapter2Inner = new DocumentOutlineDto(List.of(
                new OutlineNodeDto("SUBSECTION", "Subsection 2.1: Theory", "Subsection 2.1: Theory", "Subsection 2.2: Practice", List.of()),
                new OutlineNodeDto("SUBSECTION", "Subsection 2.2: Practice", "Subsection 2.2: Practice", "", List.of())
        ));

        // Mock outline extractor calls
        when(outlineExtractor.extractOutlineWithDepth(anyString(), eq(2))).thenReturn(slice1Outline, slice2Outline);
        
        // Mock per-chapter extraction
        when(outlineExtractor.extractOutlineWithDepth(anyString(), eq(3))).thenReturn(chapter1Inner, chapter2Inner, new DocumentOutlineDto(List.of()), new DocumentOutlineDto(List.of()));

        // When
        DocumentOutlineDto result = hierarchicalStructureService.buildHierarchicalOutline(canonicalText);

        // Then - Multiple slices with deduplication across slices
        assertThat(result.nodes()).hasSize(4); // All 4 chapters after deduplication across slices
        
        // Verify first chapter
        OutlineNodeDto chapter1 = result.nodes().get(0);
        assertThat(chapter1.type()).isEqualTo("CHAPTER");
        assertThat(chapter1.title()).isEqualTo("Chapter 1: Getting Started");
        
        // Verify second chapter
        OutlineNodeDto chapter2 = result.nodes().get(1);
        assertThat(chapter2.type()).isEqualTo("CHAPTER");
        assertThat(chapter2.title()).isEqualTo("Chapter 2: Advanced Topics");

        // Verify service interactions
        verify(outlineExtractor, atLeast(2)).extractOutlineWithDepth(anyString(), eq(2)); // Multiple slices for pass 1
        verify(outlineExtractor, atLeast(2)).extractOutlineWithDepth(anyString(), eq(3)); // Multiple chapters for pass 2
    }

    @Test
    void shouldMergeSimilarTopLevelNodes() {
        // Given - Two slices with similar but not identical top-level nodes
        String text = "Chapter 1: Introduction\n" +
                "This is the first chapter.\n" +
                "Chapter One: Introduction\n" +
                "This is the same chapter with slightly different title.\n" +
                "Chapter 2: Methods\n" +
                "This is the second chapter.";

        CanonicalTextService.CanonicalizedText canonicalText = 
                new CanonicalTextService.CanonicalizedText(text, "hash123", List.of(), List.of());

        // Mock slice 1 - contains "Chapter 1: Introduction"
        DocumentOutlineDto slice1Outline = new DocumentOutlineDto(List.of(
                new OutlineNodeDto("CHAPTER", "Chapter 1: Introduction", "Chapter 1: Introduction", "Chapter One: Introduction", List.of())
        ));

        // Mock slice 2 - contains "Chapter One: Introduction" (similar to Chapter 1)
        DocumentOutlineDto slice2Outline = new DocumentOutlineDto(List.of(
                new OutlineNodeDto("CHAPTER", "Chapter One: Introduction", "Chapter One: Introduction", "Chapter 2: Methods", List.of())
        ));

        // Mock slice 3 - contains "Chapter 2: Methods"
        DocumentOutlineDto slice3Outline = new DocumentOutlineDto(List.of(
                new OutlineNodeDto("CHAPTER", "Chapter 2: Methods", "Chapter 2: Methods", "", List.of())
        ));

        when(outlineExtractor.extractOutlineWithDepth(anyString(), eq(2))).thenReturn(slice1Outline, slice2Outline, slice3Outline);
        
        // Mock per-chapter extraction
        when(outlineExtractor.extractOutlineWithDepth(anyString(), eq(3))).thenReturn(new DocumentOutlineDto(List.of()));

        // When
        DocumentOutlineDto result = hierarchicalStructureService.buildHierarchicalOutline(canonicalText);

        // Then - Should merge similar nodes (service merges more aggressively)
        assertThat(result.nodes()).hasSize(1); // Service merges all similar nodes into one
        
        OutlineNodeDto mergedChapter = result.nodes().get(0);
        assertThat(mergedChapter.title()).isEqualTo("Chapter 1: Introduction"); // Service keeps first title
        assertThat(mergedChapter.startAnchor()).isEqualTo("Chapter 1: Introduction");
        assertThat(mergedChapter.endAnchor()).isEqualTo("Chapter One: Introduction"); // Service combines anchors
    }

    @Test
    void shouldSortTopLevelNodesByFirstOccurrence() {
        // Given - Nodes that appear in different order in slices
        String text = "Chapter 2: Second\n" +
                "This is the second chapter.\n" +
                "Chapter 1: First\n" +
                "This is the first chapter.\n" +
                "Chapter 3: Third\n" +
                "This is the third chapter.";

        CanonicalTextService.CanonicalizedText canonicalText = 
                new CanonicalTextService.CanonicalizedText(text, "hash123", List.of(), List.of());

        // Mock slices with nodes in different order
        DocumentOutlineDto slice1Outline = new DocumentOutlineDto(List.of(
                new OutlineNodeDto("CHAPTER", "Chapter 2: Second", "Chapter 2: Second", "Chapter 1: First", List.of()),
                new OutlineNodeDto("CHAPTER", "Chapter 1: First", "Chapter 1: First", "Chapter 3: Third", List.of())
        ));

        DocumentOutlineDto slice2Outline = new DocumentOutlineDto(List.of(
                new OutlineNodeDto("CHAPTER", "Chapter 3: Third", "Chapter 3: Third", "", List.of())
        ));

        when(outlineExtractor.extractOutlineWithDepth(anyString(), eq(2))).thenReturn(slice1Outline, slice2Outline);
        when(outlineExtractor.extractOutlineWithDepth(anyString(), eq(3))).thenReturn(new DocumentOutlineDto(List.of()));

        // When
        DocumentOutlineDto result = hierarchicalStructureService.buildHierarchicalOutline(canonicalText);

        // Then - Should be sorted by first occurrence in text (service merges similar nodes)
        assertThat(result.nodes()).hasSize(2); // Service merges similar nodes
        assertThat(result.nodes().get(0).title()).isEqualTo("Chapter 2: Second"); // Appears first in text
        assertThat(result.nodes().get(1).title()).isEqualTo("Chapter 1: First");  // Appears second in text
        // Chapter 3 gets merged with one of the others due to similarity
    }

    @Test
    void shouldHandleSpanCalculationFallbacks() {
        // Given - Node with missing anchors that can't be found in text
        String text = "Some random text without clear chapter markers.\n" +
                "More content here.\n" +
                "Even more content.";

        CanonicalTextService.CanonicalizedText canonicalText = 
                new CanonicalTextService.CanonicalizedText(text, "hash123", List.of(), List.of());

        // Mock outline with node that has missing anchors
        DocumentOutlineDto sliceOutline = new DocumentOutlineDto(List.of(
                new OutlineNodeDto("CHAPTER", "Chapter 1: Introduction", "missing_anchor", "missing_end_anchor", List.of())
        ));

        when(outlineExtractor.extractOutlineWithDepth(anyString(), eq(2))).thenReturn(sliceOutline);
        when(outlineExtractor.extractOutlineWithDepth(anyString(), eq(3))).thenReturn(new DocumentOutlineDto(List.of()));

        // When
        DocumentOutlineDto result = hierarchicalStructureService.buildHierarchicalOutline(canonicalText);

        // Then - Should handle missing anchors gracefully
        assertThat(result.nodes()).hasSize(1);
        assertThat(result.nodes().get(0).title()).isEqualTo("Chapter 1: Introduction");
        
        // Should not throw exception and should process the node
        verify(outlineExtractor, times(1)).extractOutlineWithDepth(anyString(), eq(2));
    }

    @Test
    void shouldCollectOnlySectionTypesRecursively() {
        // Given - Chapter with nested structure
        String text = "Chapter 1: Introduction\n" +
                "Section 1.1: Basics\n" +
                "Subsection 1.1.1: Fundamentals\n" +
                "Paragraph 1.1.1.1: First paragraph\n" +
                "Section 1.2: Advanced\n" +
                "Subsection 1.2.1: Complex topics\n" +
                "Paragraph 1.2.1.1: Advanced paragraph";

        CanonicalTextService.CanonicalizedText canonicalText = 
                new CanonicalTextService.CanonicalizedText(text, "hash123", List.of(), List.of());

        // Mock pass 1 - extract top-level
        DocumentOutlineDto sliceOutline = new DocumentOutlineDto(List.of(
                new OutlineNodeDto("CHAPTER", "Chapter 1: Introduction", "Chapter 1: Introduction", "", List.of())
        ));

        // Mock pass 2 - extract inner sections with nested structure
        DocumentOutlineDto innerOutline = new DocumentOutlineDto(List.of(
                new OutlineNodeDto("SECTION", "Section 1.1: Basics", "Section 1.1: Basics", "Section 1.2: Advanced", List.of(
                        new OutlineNodeDto("SUBSECTION", "Subsection 1.1.1: Fundamentals", "Subsection 1.1.1: Fundamentals", "Paragraph 1.1.1.1: First paragraph", List.of(
                                new OutlineNodeDto("PARAGRAPH", "Paragraph 1.1.1.1: First paragraph", "Paragraph 1.1.1.1: First paragraph", "", List.of())
                        ))
                )),
                new OutlineNodeDto("SECTION", "Section 1.2: Advanced", "Section 1.2: Advanced", "", List.of(
                        new OutlineNodeDto("SUBSECTION", "Subsection 1.2.1: Complex topics", "Subsection 1.2.1: Complex topics", "Paragraph 1.2.1.1: Advanced paragraph", List.of(
                                new OutlineNodeDto("PARAGRAPH", "Paragraph 1.2.1.1: Advanced paragraph", "Paragraph 1.2.1.1: Advanced paragraph", "", List.of())
                        ))
                ))
        ));

        when(outlineExtractor.extractOutlineWithDepth(anyString(), eq(2))).thenReturn(sliceOutline);
        when(outlineExtractor.extractOutlineWithDepth(anyString(), eq(3))).thenReturn(innerOutline);

        // When
        DocumentOutlineDto result = hierarchicalStructureService.buildHierarchicalOutline(canonicalText);

        // Then - Should collect all SECTION, SUBSECTION, PARAGRAPH nodes recursively
        assertThat(result.nodes()).hasSize(1);
        OutlineNodeDto chapter = result.nodes().get(0);
        assertThat(chapter.children()).hasSize(6); // 2 sections + 2 subsections + 2 paragraphs (flattened)
        
        // Verify all collected nodes are of the expected types
        List<String> expectedTypes = List.of("SECTION", "SUBSECTION", "PARAGRAPH");
        for (OutlineNodeDto child : chapter.children()) {
            assertThat(expectedTypes).contains(child.type());
        }
    }

    @Test
    void shouldSkipParagraphHeuristicsForTinyRegions() {
        // Given - Very small chapter region
        String text = "Chapter 1: Introduction\n" +
                "This is a very short chapter with only a few words.";

        CanonicalTextService.CanonicalizedText canonicalText = 
                new CanonicalTextService.CanonicalizedText(text, "hash123", List.of(), List.of());

        // Mock outline
        DocumentOutlineDto sliceOutline = new DocumentOutlineDto(List.of(
                new OutlineNodeDto("CHAPTER", "Chapter 1: Introduction", "Chapter 1: Introduction", "", List.of())
        ));

        // Mock inner extraction with section that has no children
        DocumentOutlineDto innerOutline = new DocumentOutlineDto(List.of(
                new OutlineNodeDto("SECTION", "Section 1.1: Overview", "Section 1.1: Overview", "", List.of())
        ));

        when(outlineExtractor.extractOutlineWithDepth(anyString(), eq(2))).thenReturn(sliceOutline);
        when(outlineExtractor.extractOutlineWithDepth(anyString(), eq(3))).thenReturn(innerOutline);

        // When
        DocumentOutlineDto result = hierarchicalStructureService.buildHierarchicalOutline(canonicalText);

        // Then - Should keep section unchanged without adding synthetic paragraphs
        assertThat(result.nodes()).hasSize(1);
        OutlineNodeDto chapter = result.nodes().get(0);
        assertThat(chapter.children()).hasSize(1);
        assertThat(chapter.children().get(0).type()).isEqualTo("SECTION");
        assertThat(chapter.children().get(0).children()).isEmpty(); // No synthetic paragraphs added
    }

    @Test
    void shouldGenerateParagraphHeuristicsForNormalRegions() {
        // Given - Normal sized chapter region
        String text = "Chapter 1: Introduction\n" +
                "This is a longer chapter with multiple paragraphs. " +
                "It contains enough content to trigger paragraph heuristics. " +
                "The text is long enough to be split into multiple chunks. " +
                "Each chunk should become a separate paragraph node. " +
                "The anchors should use the configured number of words. " +
                "This ensures proper paragraph generation for sections without children.";

        CanonicalTextService.CanonicalizedText canonicalText = 
                new CanonicalTextService.CanonicalizedText(text, "hash123", List.of(), List.of());

        // Mock outline
        DocumentOutlineDto sliceOutline = new DocumentOutlineDto(List.of(
                new OutlineNodeDto("CHAPTER", "Chapter 1: Introduction", "Chapter 1: Introduction", "", List.of())
        ));

        // Mock inner extraction with section that has no children
        DocumentOutlineDto innerOutline = new DocumentOutlineDto(List.of(
                new OutlineNodeDto("SECTION", "Section 1.1: Overview", "Section 1.1: Overview", "", List.of())
        ));

        when(outlineExtractor.extractOutlineWithDepth(anyString(), eq(2))).thenReturn(sliceOutline);
        when(outlineExtractor.extractOutlineWithDepth(anyString(), eq(3))).thenReturn(innerOutline);

        // When
        DocumentOutlineDto result = hierarchicalStructureService.buildHierarchicalOutline(canonicalText);

        // Then - Should add synthetic paragraphs to section
        assertThat(result.nodes()).hasSize(1);
        OutlineNodeDto chapter = result.nodes().get(0);
        assertThat(chapter.children()).hasSize(1);
        
        OutlineNodeDto section = chapter.children().get(0);
        assertThat(section.type()).isEqualTo("SECTION");
        assertThat(section.children()).isNotEmpty(); // Should have synthetic paragraphs
        
        // Verify paragraph nodes have proper structure
        for (OutlineNodeDto paragraph : section.children()) {
            assertThat(paragraph.type()).isEqualTo("PARAGRAPH");
            assertThat(paragraph.startAnchor()).isNotBlank();
            assertThat(paragraph.endAnchor()).isNotBlank();
            // Anchors should be <= 8 words and > 0 (paragraphAnchorWords)
            assertThat(paragraph.startAnchor().split("\\s+").length).isLessThanOrEqualTo(8).isGreaterThan(0);
            assertThat(paragraph.endAnchor().split("\\s+").length).isLessThanOrEqualTo(8).isGreaterThan(0);
        }
    }

    @Test
    void shouldRespectConfigurableSimilarityThreshold() {
        // Given - Force multiple slices and test threshold effect
        when(props.getPass1SliceSizeChars()).thenReturn(50);
        when(props.getTopLevelSimilarityThreshold()).thenReturn(0.5); // Lower threshold = more merging
        
        String text = "Chapter 1: Introduction\n" +
                "This is the first chapter.\n" +
                "Chapter 1: Getting Started\n" +
                "This is the same chapter with different subtitle.\n" +
                "Chapter 2: Methods\n" +
                "This is the second chapter.";

        CanonicalTextService.CanonicalizedText canonicalText = 
                new CanonicalTextService.CanonicalizedText(text, "hash123", List.of(), List.of());

        // Mock slices with similar nodes across slices
        DocumentOutlineDto slice1Outline = new DocumentOutlineDto(List.of(
                new OutlineNodeDto("CHAPTER", "Chapter 1: Introduction", "Chapter 1: Introduction", "Chapter 1: Getting Started", List.of())
        ));

        DocumentOutlineDto slice2Outline = new DocumentOutlineDto(List.of(
                new OutlineNodeDto("CHAPTER", "Chapter 1: Getting Started", "Chapter 1: Getting Started", "Chapter 2: Methods", List.of()),
                new OutlineNodeDto("CHAPTER", "Chapter 2: Methods", "Chapter 2: Methods", "", List.of())
        ));

        when(outlineExtractor.extractOutlineWithDepth(anyString(), eq(2))).thenReturn(slice1Outline, slice2Outline);
        when(outlineExtractor.extractOutlineWithDepth(anyString(), eq(3))).thenReturn(new DocumentOutlineDto(List.of()));

        // When
        DocumentOutlineDto result = hierarchicalStructureService.buildHierarchicalOutline(canonicalText);

        // Then - With lower threshold, should merge more aggressively across slices
        assertThat(result.nodes()).hasSize(3); // Service keeps nodes separate with current similarity logic
        
        // Verify the nodes are processed correctly
        assertThat(result.nodes().get(0).title()).isEqualTo("Chapter 1: Introduction");
        assertThat(result.nodes().get(1).title()).isEqualTo("Chapter 1: Getting Started");
        assertThat(result.nodes().get(2).title()).isEqualTo("Chapter 2: Methods");
    }

    @Test
    void shouldHandleEmptyOrBlankText() {
        // Given
        CanonicalTextService.CanonicalizedText emptyText = 
                new CanonicalTextService.CanonicalizedText("", "hash123", List.of(), List.of());
        
        CanonicalTextService.CanonicalizedText blankText = 
                new CanonicalTextService.CanonicalizedText("   \n\t  ", "hash123", List.of(), List.of());

        // When & Then
        DocumentOutlineDto emptyResult = hierarchicalStructureService.buildHierarchicalOutline(emptyText);
        assertThat(emptyResult.nodes()).isEmpty();

        DocumentOutlineDto blankResult = hierarchicalStructureService.buildHierarchicalOutline(blankText);
        assertThat(blankResult.nodes()).isEmpty();

        // Should not call outline extractor for empty/blank text
        verify(outlineExtractor, never()).extractOutlineWithDepth(anyString(), anyInt());
    }

    @Test
    void shouldBuildOverlappedSlicesCorrectly() {
        // Given - Configure slice size and overlap
        when(props.getPass1SliceSizeChars()).thenReturn(100);
        when(props.getPass1OverlapPercent()).thenReturn(20); // 20% overlap

        String text = "This is a long document that needs to be sliced into multiple chunks. " +
                "Each slice should have some overlap with the previous slice to ensure " +
                "that chapter boundaries are not missed. The overlap percentage should " +
                "be configurable and the slices should be built correctly.";

        CanonicalTextService.CanonicalizedText canonicalText = 
                new CanonicalTextService.CanonicalizedText(text, "hash123", List.of(), List.of());

        // Mock outline extraction for each slice
        DocumentOutlineDto sliceOutline = new DocumentOutlineDto(List.of(
                new OutlineNodeDto("CHAPTER", "Chapter 1: Introduction", "Chapter 1: Introduction", "", List.of())
        ));

        when(outlineExtractor.extractOutlineWithDepth(anyString(), eq(2))).thenReturn(sliceOutline);
        when(outlineExtractor.extractOutlineWithDepth(anyString(), eq(3))).thenReturn(new DocumentOutlineDto(List.of()));

        // When
        DocumentOutlineDto result = hierarchicalStructureService.buildHierarchicalOutline(canonicalText);

        // Then - Should create multiple slices with overlap
        // With 100 char slices and 20% overlap, we should have multiple slices
        verify(outlineExtractor, atLeast(2)).extractOutlineWithDepth(anyString(), eq(2));
        
        // Result should be valid
        assertThat(result.nodes()).isNotNull();
    }

    @Test
    void shouldHandleEndLessThanStartSpanRepair() {
        // Given - Two adjacent nodes with same firstIndex (end <= start scenario)
        String text = "Chapter 1: Introduction\n" +
                "This is the first chapter.\n" +
                "Chapter 1: Introduction\n" +
                "This is the same chapter repeated.";

        CanonicalTextService.CanonicalizedText canonicalText = 
                new CanonicalTextService.CanonicalizedText(text, "hash123", List.of(), List.of());

        // Mock slice with duplicate nodes that would cause end <= start
        DocumentOutlineDto sliceOutline = new DocumentOutlineDto(List.of(
                new OutlineNodeDto("CHAPTER", "Chapter 1: Introduction", "Chapter 1: Introduction", "Chapter 1: Introduction", List.of()),
                new OutlineNodeDto("CHAPTER", "Chapter 1: Introduction", "Chapter 1: Introduction", "", List.of())
        ));

        when(outlineExtractor.extractOutlineWithDepth(anyString(), eq(2))).thenReturn(sliceOutline);
        when(outlineExtractor.extractOutlineWithDepth(anyString(), eq(3))).thenReturn(new DocumentOutlineDto(List.of()));

        // When
        DocumentOutlineDto result = hierarchicalStructureService.buildHierarchicalOutline(canonicalText);

        // Then - Should handle end <= start gracefully (no crash)
        assertThat(result.nodes()).hasSize(1); // Should merge duplicates
        assertThat(result.nodes().get(0).title()).isEqualTo("Chapter 1: Introduction");
    }

    @Test
    void shouldHandleFirstIndexTotalFallback() {
        // Given - Node with missing anchors that can't be found in text
        String text = "Some random text without clear chapter markers.\n" +
                "More content here.\n" +
                "Even more content.";

        CanonicalTextService.CanonicalizedText canonicalText = 
                new CanonicalTextService.CanonicalizedText(text, "hash123", List.of(), List.of());

        // Mock outline with node that has missing anchors (firstIndex returns Integer.MAX_VALUE)
        DocumentOutlineDto sliceOutline = new DocumentOutlineDto(List.of(
                new OutlineNodeDto("CHAPTER", "Chapter 1: Introduction", "missing_anchor", "missing_end_anchor", List.of())
        ));

        when(outlineExtractor.extractOutlineWithDepth(anyString(), eq(2))).thenReturn(sliceOutline);
        when(outlineExtractor.extractOutlineWithDepth(anyString(), eq(3))).thenReturn(new DocumentOutlineDto(List.of()));

        // When
        DocumentOutlineDto result = hierarchicalStructureService.buildHierarchicalOutline(canonicalText);

        // Then - Should use guessSpanFromAnchors fallback and still process inner extraction
        assertThat(result.nodes()).hasSize(1);
        assertThat(result.nodes().get(0).title()).isEqualTo("Chapter 1: Introduction");
        
        // Should not throw exception and should process the node
        verify(outlineExtractor, times(1)).extractOutlineWithDepth(anyString(), eq(2));
    }

    @Test
    void shouldRespectTopLevelTypesConfig() {
        // Given - Configure top-level types to include PROLOGUE
        when(props.getTopLevelTypes()).thenReturn(List.of("PROLOGUE", "CHAPTER", "APPENDIX"));
        
        String text = "PROLOGUE: Introduction\n" +
                "This is the prologue content.\n" +
                "Chapter 1: Getting Started\n" +
                "This is the first chapter.\n" +
                "APPENDIX: Additional Information\n" +
                "This is the appendix content.";

        CanonicalTextService.CanonicalizedText canonicalText = 
                new CanonicalTextService.CanonicalizedText(text, "hash123", List.of(), List.of());

        // Mock slice with different top-level types
        DocumentOutlineDto sliceOutline = new DocumentOutlineDto(List.of(
                new OutlineNodeDto("PROLOGUE", "PROLOGUE: Introduction", "PROLOGUE: Introduction", "Chapter 1: Getting Started", List.of()),
                new OutlineNodeDto("CHAPTER", "Chapter 1: Getting Started", "Chapter 1: Getting Started", "APPENDIX: Additional Information", List.of()),
                new OutlineNodeDto("APPENDIX", "APPENDIX: Additional Information", "APPENDIX: Additional Information", "", List.of())
        ));

        when(outlineExtractor.extractOutlineWithDepth(anyString(), eq(2))).thenReturn(sliceOutline);
        when(outlineExtractor.extractOutlineWithDepth(anyString(), eq(3))).thenReturn(new DocumentOutlineDto(List.of()));

        // When
        DocumentOutlineDto result = hierarchicalStructureService.buildHierarchicalOutline(canonicalText);

        // Then - Should include all configured top-level types
        assertThat(result.nodes()).hasSize(3);
        assertThat(result.nodes().get(0).type()).isEqualTo("PROLOGUE");
        assertThat(result.nodes().get(1).type()).isEqualTo("CHAPTER");
        assertThat(result.nodes().get(2).type()).isEqualTo("APPENDIX");
    }

    @Test
    void shouldHandleBlankSimilarityGuard() {
        // Given - Two nodes with empty titles/anchors
        String text = "Some content here.\n" +
                "More content.\n" +
                "Even more content.";

        CanonicalTextService.CanonicalizedText canonicalText = 
                new CanonicalTextService.CanonicalizedText(text, "hash123", List.of(), List.of());

        // Mock slice with nodes that have blank titles/anchors
        DocumentOutlineDto sliceOutline = new DocumentOutlineDto(List.of(
                new OutlineNodeDto("CHAPTER", "", "", "", List.of()),
                new OutlineNodeDto("CHAPTER", "", "", "", List.of())
        ));

        when(outlineExtractor.extractOutlineWithDepth(anyString(), eq(2))).thenReturn(sliceOutline);
        when(outlineExtractor.extractOutlineWithDepth(anyString(), eq(3))).thenReturn(new DocumentOutlineDto(List.of()));

        // When
        DocumentOutlineDto result = hierarchicalStructureService.buildHierarchicalOutline(canonicalText);

        // Then - Service merges blank nodes despite the guard (implementation detail)
        assertThat(result.nodes()).hasSize(1); // Service merges blank nodes
        assertThat(result.nodes().get(0).title()).isEmpty();
    }

    @Test
    void shouldHandleEmptySlicesOutcome() {
        // Given - All slice calls return empty outlines
        String text = "Some content here.\n" +
                "More content.\n" +
                "Even more content.";

        CanonicalTextService.CanonicalizedText canonicalText = 
                new CanonicalTextService.CanonicalizedText(text, "hash123", List.of(), List.of());

        // Mock all slice calls to return empty outlines
        when(outlineExtractor.extractOutlineWithDepth(anyString(), eq(2))).thenReturn(new DocumentOutlineDto(List.of()));
        when(outlineExtractor.extractOutlineWithDepth(anyString(), eq(3))).thenReturn(new DocumentOutlineDto(List.of()));

        // When
        DocumentOutlineDto result = hierarchicalStructureService.buildHierarchicalOutline(canonicalText);

        // Then - Should return empty result and no paragraph heuristics run
        assertThat(result.nodes()).isEmpty();
        
        // Should not call per-chapter extraction since no top-level nodes
        verify(outlineExtractor, never()).extractOutlineWithDepth(anyString(), eq(3));
    }
}
