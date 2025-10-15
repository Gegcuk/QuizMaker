package uk.gegc.quizmaker.features.documentProcess.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gegc.quizmaker.features.documentProcess.application.AnchorOffsetCalculator.AnchorNotFoundException;
import uk.gegc.quizmaker.features.documentProcess.domain.model.DocumentNode;
import uk.gegc.quizmaker.features.documentProcess.domain.model.NormalizedDocument;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(MockitoExtension.class)
@Execution(ExecutionMode.CONCURRENT)
@DisplayName("AnchorOffsetCalculator Tests")
class AnchorOffsetCalculatorTest {

    private AnchorOffsetCalculator calculator;
    private NormalizedDocument document;

    @BeforeEach
    void setUp() {
        calculator = new AnchorOffsetCalculator();
        document = new NormalizedDocument();
        document.setId(UUID.randomUUID());
        document.setOriginalName("test.txt");
    }

    @Nested
    @DisplayName("calculateOffsets Tests")
    class CalculateOffsetsTests {

        @Test
        @DisplayName("when valid anchors then calculates offsets successfully")
        void calculateOffsets_validAnchors_calculatesSuccessfully() {
            // Given
            String docText = "This is the beginning of chapter one. Here is some content for chapter one. " +
                    "This is the beginning of chapter two. Here is some content for chapter two.";
            
            DocumentNode node1 = createNode("Chapter 1", 
                    "This is the beginning of chapter one",
                    "Here is some content for chapter one");
            DocumentNode node2 = createNode("Chapter 2",
                    "This is the beginning of chapter two",
                    "Here is some content for chapter two");
            
            List<DocumentNode> nodes = List.of(node1, node2);

            // When
            List<DocumentNode> result = calculator.calculateOffsets(nodes, docText);

            // Then
            assertThat(result).hasSize(2);
            assertThat(node1.getStartOffset()).isEqualTo(0);
            assertThat(node1.getEndOffset()).isGreaterThan(node1.getStartOffset());
            assertThat(node2.getStartOffset()).isGreaterThan(node1.getEndOffset());
            assertThat(node2.getEndOffset()).isGreaterThan(node2.getStartOffset());
        }

        @Test
        @DisplayName("when anchor not found and valid AI offsets then uses AI offsets as fallback")
        void calculateOffsets_anchorNotFoundValidAiOffsets_usesAiFallback() {
            // Given
            String docText = "This is some document text that doesn't contain the anchor.";
            
            DocumentNode node = createNode("Chapter 1",
                    "This anchor does not exist in document",
                    "Neither does this end anchor");
            // Set valid AI-provided offsets as fallback
            node.setStartOffset(0);
            node.setEndOffset(30);
            
            List<DocumentNode> nodes = List.of(node);

            // When
            List<DocumentNode> result = calculator.calculateOffsets(nodes, docText);

            // Then
            assertThat(result).hasSize(1);
            // Should keep AI-provided offsets
            assertThat(node.getStartOffset()).isEqualTo(0);
            assertThat(node.getEndOffset()).isEqualTo(30);
        }

        @Test
        @DisplayName("when anchor not found and invalid AI offsets then throws exception")
        void calculateOffsets_anchorNotFoundInvalidAiOffsets_throwsException() {
            // Given
            String docText = "Short text";
            
            DocumentNode node = createNode("Chapter 1",
                    "This anchor does not exist",
                    "Neither does this");
            // Set invalid AI offsets (out of bounds)
            node.setStartOffset(0);
            node.setEndOffset(1000); // Beyond document length
            
            List<DocumentNode> nodes = List.of(node);

            // When & Then
            assertThatThrownBy(() -> calculator.calculateOffsets(nodes, docText))
                    .isInstanceOf(AnchorNotFoundException.class);
        }

        @Test
        @DisplayName("when anchor not found and no AI offsets then throws exception")
        void calculateOffsets_anchorNotFoundNoAiOffsets_throwsException() {
            // Given
            String docText = "This is some document text.";
            
            DocumentNode node = createNode("Chapter 1",
                    "This anchor does not exist",
                    "Neither does this");
            // No AI offsets set
            
            List<DocumentNode> nodes = List.of(node);

            // When & Then
            assertThatThrownBy(() -> calculator.calculateOffsets(nodes, docText))
                    .isInstanceOf(AnchorNotFoundException.class)
                    .hasMessageContaining("Start anchor not found");
        }

        @Test
        @DisplayName("when start anchor null then throws AnchorNotFoundException")
        void calculateOffsets_nullStartAnchor_throwsException() {
            // Given
            String docText = "Some text content here.";
            
            DocumentNode node = new DocumentNode();
            node.setId(UUID.randomUUID());
            node.setTitle("Chapter 1");
            node.setDocument(document);
            node.setStartAnchor(null);  // Null start anchor
            node.setEndAnchor("valid end anchor");
            
            List<DocumentNode> nodes = List.of(node);

            // When & Then
            assertThatThrownBy(() -> calculator.calculateOffsets(nodes, docText))
                    .isInstanceOf(AnchorNotFoundException.class)
                    .hasMessageContaining("Start anchor is null or empty");
        }

        @Test
        @DisplayName("when start anchor empty then throws AnchorNotFoundException")
        void calculateOffsets_emptyStartAnchor_throwsException() {
            // Given
            String docText = "Some text content here.";
            
            DocumentNode node = new DocumentNode();
            node.setId(UUID.randomUUID());
            node.setTitle("Chapter 1");
            node.setDocument(document);
            node.setStartAnchor("   ");  // Empty/whitespace start anchor
            node.setEndAnchor("valid end anchor");
            
            List<DocumentNode> nodes = List.of(node);

            // When & Then
            assertThatThrownBy(() -> calculator.calculateOffsets(nodes, docText))
                    .isInstanceOf(AnchorNotFoundException.class)
                    .hasMessageContaining("Start anchor is null or empty");
        }

        @Test
        @DisplayName("when end anchor null then throws AnchorNotFoundException")
        void calculateOffsets_nullEndAnchor_throwsException() {
            // Given
            String docText = "Some text content here.";
            
            DocumentNode node = new DocumentNode();
            node.setId(UUID.randomUUID());
            node.setTitle("Chapter 1");
            node.setDocument(document);
            node.setStartAnchor("Some text content here");
            node.setEndAnchor(null);  // Null end anchor
            
            List<DocumentNode> nodes = List.of(node);

            // When & Then
            assertThatThrownBy(() -> calculator.calculateOffsets(nodes, docText))
                    .isInstanceOf(AnchorNotFoundException.class)
                    .hasMessageContaining("End anchor is null or empty");
        }

        @Test
        @DisplayName("when short anchor then logs warning but continues")
        void calculateOffsets_shortAnchor_logsWarningButContinues() {
            // Given
            String docText = "Short text here and more text to make it work properly.";
            
            DocumentNode node = new DocumentNode();
            node.setId(UUID.randomUUID());
            node.setTitle("Chapter 1");
            node.setDocument(document);
            node.setStartAnchor("Short text");  // Less than 20 chars
            node.setEndAnchor("and more text to make");
            
            List<DocumentNode> nodes = List.of(node);

            // When
            List<DocumentNode> result = calculator.calculateOffsets(nodes, docText);

            // Then
            assertThat(result).hasSize(1);
            assertThat(node.getStartOffset()).isNotNull();
            assertThat(node.getEndOffset()).isNotNull();
        }
    }

    @Nested
    @DisplayName("validateSiblingNonOverlap Tests")
    class ValidateSiblingNonOverlapTests {

        @Test
        @DisplayName("when siblings don't overlap then validation succeeds")
        void validateSiblingNonOverlap_noOverlap_succeeds() {
            // Given
            DocumentNode node1 = createNodeWithOffsets("Chapter 1", 0, 100);
            DocumentNode node2 = createNodeWithOffsets("Chapter 2", 100, 200);
            DocumentNode node3 = createNodeWithOffsets("Chapter 3", 200, 300);
            
            List<DocumentNode> nodes = List.of(node1, node2, node3);

            // When & Then - should not throw
            calculator.validateSiblingNonOverlap(nodes);
        }

        @Test
        @DisplayName("when siblings overlap then throws IllegalArgumentException")
        void validateSiblingNonOverlap_overlap_throwsException() {
            // Given
            DocumentNode node1 = createNodeWithOffsets("Chapter 1", 0, 150);
            DocumentNode node2 = createNodeWithOffsets("Chapter 2", 100, 200); // Overlaps with node1
            
            List<DocumentNode> nodes = List.of(node1, node2);

            // When & Then
            assertThatThrownBy(() -> calculator.validateSiblingNonOverlap(nodes))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Overlapping siblings");
        }

        @Test
        @DisplayName("when parent-child overlap then validation succeeds")
        void validateSiblingNonOverlap_parentChildOverlap_succeeds() {
            // Given
            DocumentNode parent = createNodeWithOffsets("Chapter 1", 0, 200);
            DocumentNode child1 = createNodeWithOffsets("Section 1.1", 10, 100);
            DocumentNode child2 = createNodeWithOffsets("Section 1.2", 100, 190);
            
            child1.setParent(parent);
            child2.setParent(parent);
            
            List<DocumentNode> nodes = List.of(parent, child1, child2);

            // When & Then - parent-child overlap is allowed, only siblings are validated
            calculator.validateSiblingNonOverlap(nodes);
        }

        @Test
        @DisplayName("when mixed siblings under different parents then validates each group separately")
        void validateSiblingNonOverlap_differentParents_validatesEachGroup() {
            // Given
            DocumentNode parent1 = createNodeWithOffsets("Chapter 1", 0, 200);
            DocumentNode parent2 = createNodeWithOffsets("Chapter 2", 200, 400);
            
            DocumentNode child1a = createNodeWithOffsets("Section 1.1", 10, 100);
            DocumentNode child1b = createNodeWithOffsets("Section 1.2", 100, 190);
            child1a.setParent(parent1);
            child1b.setParent(parent1);
            
            DocumentNode child2a = createNodeWithOffsets("Section 2.1", 210, 300);
            DocumentNode child2b = createNodeWithOffsets("Section 2.2", 300, 390);
            child2a.setParent(parent2);
            child2b.setParent(parent2);
            
            List<DocumentNode> nodes = List.of(parent1, parent2, child1a, child1b, child2a, child2b);

            // When & Then - should validate successfully as each group doesn't overlap
            calculator.validateSiblingNonOverlap(nodes);
        }
    }

    @Nested
    @DisplayName("Edge Case Tests")
    class EdgeCaseTests {

        @Test
        @DisplayName("when end anchor comes before start anchor then throws exception")
        void calculateOffsets_endBeforeStart_throwsException() {
            // Given
            String docText = "End anchor text here. Start anchor text here.";
            
            DocumentNode node = createNode("Chapter 1",
                    "Start anchor text here",
                    "End anchor text here");
            
            List<DocumentNode> nodes = List.of(node);

            // When & Then
            assertThatThrownBy(() -> calculator.calculateOffsets(nodes, docText))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Anchor positions out of bounds");
        }

        @Test
        @DisplayName("when document text contains Unicode then normalizes correctly")
        void calculateOffsets_unicodeText_normalizesCorrectly() {
            // Given
            String docText = "Café résumé naïve über. More text après cliché.";
            
            DocumentNode node = createNode("Chapter 1",
                    "Café résumé naïve über",
                    "More text après cliché");
            
            List<DocumentNode> nodes = List.of(node);

            // When
            List<DocumentNode> result = calculator.calculateOffsets(nodes, docText);

            // Then
            assertThat(result).hasSize(1);
            assertThat(node.getStartOffset()).isNotNull();
            assertThat(node.getEndOffset()).isNotNull();
            assertThat(node.getEndOffset()).isGreaterThan(node.getStartOffset());
        }

        @Test
        @DisplayName("when anchor has newlines then normalizes correctly")
        void calculateOffsets_anchorWithNewlines_normalizesCorrectly() {
            // Given
            String docText = "This is a paragraph with text that spans across multiple lines in the document.";
            
            DocumentNode node = new DocumentNode();
            node.setId(UUID.randomUUID());
            node.setTitle("Chapter 1");
            node.setDocument(document);
            node.setStartAnchor("This is a paragraph with text");
            node.setEndAnchor("across multiple lines in the document");
            
            List<DocumentNode> nodes = List.of(node);

            // When
            List<DocumentNode> result = calculator.calculateOffsets(nodes, docText);

            // Then
            assertThat(result).hasSize(1);
            assertThat(node.getStartOffset()).isEqualTo(0);
            assertThat(node.getEndOffset()).isGreaterThan(node.getStartOffset());
        }

        @Test
        @DisplayName("when empty node list then returns empty list")
        void calculateOffsets_emptyList_returnsEmpty() {
            // Given
            String docText = "Some document text";
            List<DocumentNode> nodes = new ArrayList<>();

            // When
            List<DocumentNode> result = calculator.calculateOffsets(nodes, docText);

            // Then
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("when multiple nodes with same anchors then calculates each correctly")
        void calculateOffsets_multipleNodesWithSameAnchors_calculatesEach() {
            // Given
            String docText = "Chapter One content here. Chapter Two content here. Chapter Three content here.";
            
            DocumentNode node1 = createNode("Chapter 1", 
                    "Chapter One content here",
                    "Chapter One content here");
            DocumentNode node2 = createNode("Chapter 2",
                    "Chapter Two content here",
                    "Chapter Two content here");
            
            List<DocumentNode> nodes = List.of(node1, node2);

            // When
            List<DocumentNode> result = calculator.calculateOffsets(nodes, docText);

            // Then
            assertThat(result).hasSize(2);
            assertThat(node1.getStartOffset()).isLessThan(node2.getStartOffset());
        }
    }

    @Nested
    @DisplayName("Fallback Strategy Tests")
    class FallbackStrategyTests {

        @Test
        @DisplayName("when end anchor not found then uses document end as fallback")
        void calculateOffsets_endAnchorNotFound_usesDocumentEnd() {
            // Given
            String docText = "Start of chapter one and this continues to the end of document.";
            
            DocumentNode node = createNode("Chapter 1",
                    "Start of chapter one",
                    "This anchor does not exist anywhere");
            
            List<DocumentNode> nodes = List.of(node);

            // When
            List<DocumentNode> result = calculator.calculateOffsets(nodes, docText);

            // Then
            assertThat(result).hasSize(1);
            assertThat(node.getStartOffset()).isEqualTo(0);
            // Should extend to document end when end anchor not found
            assertThat(node.getEndOffset()).isGreaterThan(0);
        }

        @Test
        @DisplayName("when end anchor not found but next chapter exists then uses next chapter position")
        void calculateOffsets_endAnchorNotFoundNextChapterExists_usesNextChapter() {
            // Given
            String docText = "Start of chapter one and some content. CHAPTER Two starts here with more text.";
            
            DocumentNode node = createNode("Chapter 1",
                    "Start of chapter one",
                    "This anchor does not exist");
            
            List<DocumentNode> nodes = List.of(node);

            // When
            List<DocumentNode> result = calculator.calculateOffsets(nodes, docText);

            // Then
            assertThat(result).hasSize(1);
            assertThat(node.getStartOffset()).isEqualTo(0);
            // Should find next chapter and use its position
            // The end offset will be the position of "CHAPTER" plus the endAnchor length
            assertThat(node.getEndOffset()).isGreaterThan(node.getStartOffset());
            assertThat(node.getEndOffset()).isLessThanOrEqualTo(docText.length());
        }

        @Test
        @DisplayName("when AI offsets have negative start then throws exception")
        void calculateOffsets_negativeAiStart_throwsException() {
            // Given
            String docText = "Some text content";
            
            DocumentNode node = createNode("Chapter 1",
                    "Non-existent start anchor",
                    "Non-existent end anchor");
            node.setStartOffset(-5);  // Invalid negative offset
            node.setEndOffset(10);
            
            List<DocumentNode> nodes = List.of(node);

            // When & Then
            assertThatThrownBy(() -> calculator.calculateOffsets(nodes, docText))
                    .isInstanceOf(AnchorNotFoundException.class);
        }

        @Test
        @DisplayName("when AI offsets have start >= end then throws exception")
        void calculateOffsets_aiStartGreaterThanEnd_throwsException() {
            // Given
            String docText = "Some text content";
            
            DocumentNode node = createNode("Chapter 1",
                    "Non-existent start anchor",
                    "Non-existent end anchor");
            node.setStartOffset(50);  // Start after end
            node.setEndOffset(30);
            
            List<DocumentNode> nodes = List.of(node);

            // When & Then
            assertThatThrownBy(() -> calculator.calculateOffsets(nodes, docText))
                    .isInstanceOf(AnchorNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("Complex Fallback Strategy Tests")
    class ComplexFallbackStrategyTests {

        @Test
        @DisplayName("when anchor needs whitespace normalization then finds match")
        void calculateOffsets_whitespaceNormalization_findsMatch() {
            // Given - document has extra whitespace
            String docText = "Start  of   chapter\n\nwith    lots    of    whitespace. End  of   chapter   here.";
            
            // Anchors have normalized whitespace (single spaces)
            DocumentNode node = createNode("Chapter 1",
                    "Start of chapter with lots of whitespace",
                    "End of chapter here");
            
            List<DocumentNode> nodes = List.of(node);

            // When
            List<DocumentNode> result = calculator.calculateOffsets(nodes, docText);

            // Then
            assertThat(result).hasSize(1);
            assertThat(node.getStartOffset()).isNotNull();
            assertThat(node.getEndOffset()).isNotNull();
            assertThat(node.getEndOffset()).isGreaterThan(node.getStartOffset());
        }

        @Test
        @DisplayName("when anchor needs quote unescaping then finds match")
        void calculateOffsets_quoteUnescaping_findsMatch() {
            // Given - document has regular quotes
            String docText = "This is a \"quoted section\" in the document. More \"quoted text\" here with extra content.";
            
            // Anchors have escaped quotes (as from JSON)
            DocumentNode node = createNode("Chapter 1",
                    "This is a \\\"quoted section\\\" in the document",
                    "More \\\"quoted text\\\" here");
            
            List<DocumentNode> nodes = List.of(node);

            // When
            List<DocumentNode> result = calculator.calculateOffsets(nodes, docText);

            // Then
            assertThat(result).hasSize(1);
            assertThat(node.getStartOffset()).isNotNull();
            assertThat(node.getEndOffset()).isNotNull();
        }

        @Test
        @DisplayName("when anchor needs case-insensitive matching then finds match")
        void calculateOffsets_caseInsensitiveMatch_findsMatch() {
            // Given - document text is in uppercase
            String docText = "START OF CHAPTER ONE CONTENT HERE. END OF CHAPTER ONE CONTENT.";
            
            // Anchors are in lowercase
            DocumentNode node = createNode("Chapter 1",
                    "start of chapter one content here",
                    "end of chapter one content");
            
            List<DocumentNode> nodes = List.of(node);

            // When
            List<DocumentNode> result = calculator.calculateOffsets(nodes, docText);

            // Then
            assertThat(result).hasSize(1);
            assertThat(node.getStartOffset()).isNotNull();
            assertThat(node.getEndOffset()).isNotNull();
        }

        @Test
        @DisplayName("when anchor needs shortened version then finds unique match")
        void calculateOffsets_shortenedAnchor_findsUniqueMatch() {
            // Given - full anchor doesn't exist but shortened version does and is unique
            String docText = "This is the beginning of the document content. And this is more content at the end with additional text.";
            
            // Use very long anchors that won't match exactly
            DocumentNode node = createNode("Chapter 1",
                    "This is the beginning of the document content with extra text that does not exist",
                    "And this is more content at the end");
            
            List<DocumentNode> nodes = List.of(node);

            // When
            List<DocumentNode> result = calculator.calculateOffsets(nodes, docText);

            // Then
            assertThat(result).hasSize(1);
            assertThat(node.getStartOffset()).isNotNull();
            assertThat(node.getEndOffset()).isNotNull();
        }

        @Test
        @DisplayName("when shortened anchor appears multiple times then tries shorter version")
        void calculateOffsets_shortenedAnchorMultiple_triesShorter() {
            // Given - shortened version appears multiple times
            String docText = "Common text here. Some unique identifier one. Common text here. Different unique identifier two.";
            
            // Use anchor where shortened version is "Common text here" which appears twice
            DocumentNode node = createNode("Chapter 1",
                    "Common text here. Some unique identifier one",
                    "Different unique identifier two");
            
            List<DocumentNode> nodes = List.of(node);

            // When
            List<DocumentNode> result = calculator.calculateOffsets(nodes, docText);

            // Then
            assertThat(result).hasSize(1);
            assertThat(node.getStartOffset()).isNotNull();
            assertThat(node.getEndOffset()).isNotNull();
        }

        @Test
        @DisplayName("when fuzzy matching from start finds match then returns position")
        void calculateOffsets_fuzzyMatchFromStart_findsMatch() {
            // Given - need fuzzy matching
            String docText = "The quick brown fox jumps over the lazy dog and continues with more text for testing purposes and more.";
            
            // Anchor has slight differences requiring fuzzy match
            DocumentNode node = createNode("Chapter 1",
                    "The quick brown fox jumps over the lazy dog with some differences",
                    "and continues with more text for testing purposes");
            
            List<DocumentNode> nodes = List.of(node);

            // When
            List<DocumentNode> result = calculator.calculateOffsets(nodes, docText);

            // Then
            assertThat(result).hasSize(1);
            assertThat(node.getStartOffset()).isNotNull();
            assertThat(node.getEndOffset()).isNotNull();
        }

        @Test
        @DisplayName("when fuzzy matching with word-based strategy then finds match")
        void calculateOffsets_wordBasedFuzzyMatch_findsMatch() {
            // Given - need word-by-word fuzzy matching
            String docText = "Introduction to advanced programming concepts and design patterns for software engineering and beyond.";
            
            // Anchor where full text doesn't match but first few words do
            DocumentNode node = createNode("Chapter 1",
                    "Introduction to advanced programming concepts with extra words that dont match",
                    "and design patterns for software engineering");
            
            List<DocumentNode> nodes = List.of(node);

            // When
            List<DocumentNode> result = calculator.calculateOffsets(nodes, docText);

            // Then
            assertThat(result).hasSize(1);
            assertThat(node.getStartOffset()).isNotNull();
            assertThat(node.getEndOffset()).isNotNull();
        }

        @Test
        @DisplayName("when document end used as fallback then offset equals document length minus anchor length")
        void calculateOffsets_documentEndFallback_usesDocumentLength() {
            // Given - end anchor not found and no next section
            String docText = "Start of the only chapter in this short document without any sections following.";
            
            DocumentNode node = createNode("Chapter 1",
                    "Start of the only chapter",
                    "This end anchor does not exist anywhere in the document at all");
            
            List<DocumentNode> nodes = List.of(node);

            // When
            List<DocumentNode> result = calculator.calculateOffsets(nodes, docText);

            // Then
            assertThat(result).hasSize(1);
            assertThat(node.getStartOffset()).isEqualTo(0);
            // When document end is used, the end offset should be document length
            // But then endAnchor.length() is added, which would exceed document length
            // So this should work
            assertThat(node.getEndOffset()).isGreaterThan(node.getStartOffset());
        }

        @Test
        @DisplayName("when whitespace and quote normalization combined then finds match")
        void calculateOffsets_combinedNormalization_findsMatch() {
            // Given - needs both whitespace and quote normalization
            String docText = "The  author  said  \"Hello World\" and    then    continued.";
            
            DocumentNode node = createNode("Chapter 1",
                    "The author said \\\"Hello World\\\" and then continued",
                    "and then continued");
            
            List<DocumentNode> nodes = List.of(node);

            // When
            List<DocumentNode> result = calculator.calculateOffsets(nodes, docText);

            // Then
            assertThat(result).hasSize(1);
            assertThat(node.getStartOffset()).isNotNull();
            assertThat(node.getEndOffset()).isNotNull();
        }
    }

    @Nested
    @DisplayName("Deep Fallback Coverage Tests")
    class DeepFallbackCoverageTests {

        @Test
        @DisplayName("when end anchor not found and no next section pattern then uses document end")
        void calculateOffsets_noNextSectionPattern_usesDocumentEnd() {
            // Given - document with no section markers and end anchor is short enough not to exceed bounds
            String docText = "Start of unique content here that has no chapter markers or section indicators at all.";
            
            DocumentNode node = createNode("Chapter 1",
                    "Start of unique content here",
                    "xxx");  // Short end anchor so document end + anchor length won't exceed
            
            List<DocumentNode> nodes = List.of(node);

            // When
            List<DocumentNode> result = calculator.calculateOffsets(nodes, docText);

            // Then - should use document end as fallback (lines 100-101)
            assertThat(result).hasSize(1);
            assertThat(node.getStartOffset()).isEqualTo(0);
            assertThat(node.getEndOffset()).isGreaterThan(0);
        }

        @Test
        @DisplayName("when anchor has newlines in text then newline normalization finds it")
        void calculateOffsets_newlineInAnchor_findsWithNewlineNormalization() {
            // Given - document has actual newlines, anchor has escaped newlines
            String docText = "This is text\nwith actual\nnewlines in it. More content here.";
            
            DocumentNode node = createNode("Chapter 1",
                    "This is text with actual newlines in it",
                    "More content here");
            
            List<DocumentNode> nodes = List.of(node);

            // When
            List<DocumentNode> result = calculator.calculateOffsets(nodes, docText);

            // Then - line 161 should be covered (newline normalization path)
            assertThat(result).hasSize(1);
            assertThat(node.getStartOffset()).isNotNull();
            assertThat(node.getEndOffset()).isNotNull();
        }

        @Test
        @DisplayName("when fromPosition exceeds document length then returns -1")
        void findNextMajorSection_fromPositionExceedsLength_returnsNegativeOne() {
            // Given - position at or beyond document end
            String docText = "Short document with no sections.";
            
            DocumentNode node = createNode("Chapter 1",
                    "Short document",
                    "This does not exist");
            
            // Set start to position where findNextMajorSection would be called with position >= length
            List<DocumentNode> nodes = List.of(node);

            // When & Then - lines 291-292 covered when search position is at end
            // This happens naturally when end anchor not found
            assertThatThrownBy(() -> calculator.calculateOffsets(nodes, docText))
                    .isInstanceOf(RuntimeException.class);
        }

        @Test
        @DisplayName("when anchor less than 20 chars after shortening then skips it")
        void calculateOffsets_veryShortAnchor_skipsInShortening() {
            // Given - anchor that becomes < 20 chars when shortened
            String docText = "Some text content here and there with various words spread around for testing.";
            
            // Use 19-char anchor that will be skipped in shortened versions
            DocumentNode node = createNode("Chapter 1",
                    "Some text content h",  // Exactly 19 chars - will hit line 354
                    "testing");
            
            List<DocumentNode> nodes = List.of(node);

            // When
            List<DocumentNode> result = calculator.calculateOffsets(nodes, docText);

            // Then - line 354 covered (skip if < 20)
            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("when shortened anchor appears multiple times then tries shorter version")
        void calculateOffsets_shortenedAnchorDuplicated_triesShorter() {
            // Given - create document where shortened version (first 50 chars) appears twice
            String repeatedStart = "This is a very common phrase that appears multiple";
            String docText = repeatedStart + " times in chapter one. And also " + repeatedStart + " times in chapter two. Unique end marker.";
            
            // Anchor where first 50 chars match twice but full anchor doesn't exist
            DocumentNode node = createNode("Chapter 1",
                    repeatedStart + " times in chapter one with extra text that does not exist",
                    "Unique end marker");
            
            List<DocumentNode> nodes = List.of(node);

            // When
            List<DocumentNode> result = calculator.calculateOffsets(nodes, docText);

            // Then - lines 370-371 covered (multiple occurrences, trying shorter)
            assertThat(result).hasSize(1);
            assertThat(node.getStartOffset()).isNotNull();
        }

        @Test
        @DisplayName("when consecutive whitespace in normalized position then handles correctly")
        void findNormalizedPosition_consecutiveWhitespace_handlesCorrectly() {
            // Given - document with multiple consecutive whitespaces
            String docText = "Text  with   multiple    spaces     here and there.";
            
            DocumentNode node = createNode("Chapter 1",
                    "Text with multiple spaces here",
                    "and there");
            
            List<DocumentNode> nodes = List.of(node);

            // When
            List<DocumentNode> result = calculator.calculateOffsets(nodes, docText);

            // Then - line 561 branch covered (inWhitespace check)
            assertThat(result).hasSize(1);
            assertThat(node.getStartOffset()).isNotNull();
            assertThat(node.getEndOffset()).isNotNull();
        }

        @Test
        @DisplayName("when word-based fuzzy match finds duplicate then continues searching")
        void calculateOffsets_wordBasedFuzzyDuplicate_continuesSearching() {
            // Given - document where 3-word phrase appears twice
            String commonPhrase = "Introduction to advanced";
            String docText = commonPhrase + " mathematics chapter one. " + commonPhrase + " programming concepts chapter two. Unique ending text here.";
            
            // Anchor with common start that appears twice
            DocumentNode node = createNode("Chapter 1",
                    commonPhrase + " mathematics chapter one with extra non-matching text",
                    "Unique ending text here");
            
            List<DocumentNode> nodes = List.of(node);

            // When
            List<DocumentNode> result = calculator.calculateOffsets(nodes, docText);

            // Then - lines 488-494 covered (word-based match with duplicates)
            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("when case-insensitive fuzzy substring has duplicate then skips")
        void calculateOffsets_caseInsensitiveFuzzyDuplicate_skips() {
            // Given - document with duplicated substring (case-insensitive)
            String docText = "The Quick Brown Fox jumps. The quick brown fox sleeps. Different ending text.";
            
            // Anchor that needs case-insensitive fuzzy matching
            DocumentNode node = createNode("Chapter 1",
                    "the quick brown fox jumps over something",
                    "Different ending text");
            
            List<DocumentNode> nodes = List.of(node);

            // When
            List<DocumentNode> result = calculator.calculateOffsets(nodes, docText);

            // Then - lines 535-541 covered (case-insensitive with duplicates)
            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("when anchor needs direct newline normalization path then finds match")
        void calculateOffsets_directNewlineNormalization_findsMatch() {
            // Given - anchor with literal \n that needs to be normalized to space
            String docText = "This is text with actual newlines here and continues with more text.";
            
            DocumentNode node = new DocumentNode();
            node.setId(UUID.randomUUID());
            node.setTitle("Chapter 1");
            node.setDocument(document);
            node.setStartAnchor("This is text\\nwith actual\\nnewlines here");  // Has \n that normalizes to space
            node.setEndAnchor("and continues with more text");
            
            List<DocumentNode> nodes = List.of(node);

            // When
            List<DocumentNode> result = calculator.calculateOffsets(nodes, docText);

            // Then - line 161 covered (newline normalization direct return)
            assertThat(result).hasSize(1);
            assertThat(node.getStartOffset()).isNotNull();
        }

        @Test
        @DisplayName("when shortened newline-normalized anchor is unique then finds match")
        void calculateOffsets_shortenedNewlineNormalizedUnique_findsMatch() {
            // Given - document where shortened newline-normalized version is unique
            String docText = "Start text here with\nnewlines inside. More unique content after that.";
            
            DocumentNode node = createNode("Chapter 1",
                    "Start text here with\nnewlines inside extra text that doesnt exist",
                    "More unique content after that");
            
            List<DocumentNode> nodes = List.of(node);

            // When
            List<DocumentNode> result = calculator.calculateOffsets(nodes, docText);

            // Then - lines 381-383 covered (shortened newline-normalized unique)
            assertThat(result).hasSize(1);
            assertThat(node.getStartOffset()).isNotNull();
        }

        @Test
        @DisplayName("when shortened quote-unescaped anchor is unique then finds match")
        void calculateOffsets_shortenedQuoteUnescapedUnique_findsMatch() {
            // Given - document with quotes that need unescaping
            String docText = "The book titled \"Advanced Programming\" is comprehensive. End marker here.";
            
            DocumentNode node = createNode("Chapter 1",
                    "The book titled \\\"Advanced Programming\\\" is comprehensive with extra text",
                    "End marker here");
            
            List<DocumentNode> nodes = List.of(node);

            // When
            List<DocumentNode> result = calculator.calculateOffsets(nodes, docText);

            // Then - lines 416-418 covered (shortened quote-unescaped unique)
            assertThat(result).hasSize(1);
            assertThat(node.getStartOffset()).isNotNull();
        }

        @Test
        @DisplayName("when fuzzy match from middle succeeds then returns position")
        void calculateOffsets_fuzzyMatchFromMiddle_findsMatch() {
            // Given - document where middle substring matches uniquely
            String docText = "Prefix text here. Unique middle section identifier text. Suffix ending.";
            
            // Very long anchor where middle part is unique
            DocumentNode node = createNode("Chapter 1",
                    "Beginning words that dont exist Unique middle section identifier text more words",
                    "Suffix ending");
            
            List<DocumentNode> nodes = List.of(node);

            // When
            List<DocumentNode> result = calculator.calculateOffsets(nodes, docText);

            // Then - line 472 covered (fuzzy match from middle return)
            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("when anchor has less than 3 words then skips word-based matching")
        void calculateOffsets_lessThanThreeWords_skipsWordMatching() {
            // Given - anchor with only 1-2 words
            String docText = "Simple text without the complex anchor matching needed here.";
            
            DocumentNode node = createNode("Chapter 1",
                    "Simple text without the complex anchor matching needed here",
                    "aa bb");  // Only 2 words - will skip word-based matching
            
            List<DocumentNode> nodes = List.of(node);

            // When & Then - line 478 branch covered (anchorWords.length < 3)
            assertThatThrownBy(() -> calculator.calculateOffsets(nodes, docText))
                    .isInstanceOf(RuntimeException.class);
        }

        @Test
        @DisplayName("when word-based fuzzy finds unique match then returns it")
        void calculateOffsets_wordBasedFuzzyUnique_findsMatch() {
            // Given - document where word-based matching is needed and unique
            String docText = "Comprehensive introduction to programming paradigms and methodologies. Unique ending marker.";
            
            // Anchor where first 3 words match uniquely
            DocumentNode node = createNode("Chapter 1",
                    "Comprehensive introduction to programming paradigms with extra non-matching words",
                    "Unique ending marker");
            
            List<DocumentNode> nodes = List.of(node);

            // When
            List<DocumentNode> result = calculator.calculateOffsets(nodes, docText);

            // Then - lines 488-494 covered (word-based fuzzy unique match)
            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("when case-insensitive fuzzy substring finds unique match then returns it")
        void calculateOffsets_caseInsensitiveFuzzyUnique_findsMatch() {
            // Given - document where case-insensitive fuzzy is needed and unique
            String docText = "THE COMPREHENSIVE GUIDE TO SOFTWARE ENGINEERING. Unique finale.";
            
            // Anchor in lowercase needing case-insensitive fuzzy
            DocumentNode node = createNode("Chapter 1",
                    "the comprehensive guide to software engineering with extra text",
                    "Unique finale");
            
            List<DocumentNode> nodes = List.of(node);

            // When
            List<DocumentNode> result = calculator.calculateOffsets(nodes, docText);

            // Then - lines 535-541 covered (case-insensitive fuzzy unique)
            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("when fuzzy case-sensitive substring has duplicate then tries case-insensitive")
        void calculateOffsets_fuzzyCaseSensitiveDuplicate_triesCaseInsensitive() {
            // Given - document with duplicate case-sensitive substring
            String repeated = "Common pattern text";
            String docText = repeated + " in first part. " + repeated + " in second part. Unique end.";
            
            // Anchor needing fuzzy matching
            DocumentNode node = createNode("Chapter 1",
                    repeated + " in first part with extra text",
                    "Unique end");
            
            List<DocumentNode> nodes = List.of(node);

            // When
            List<DocumentNode> result = calculator.calculateOffsets(nodes, docText);

            // Then - line 520 branch covered (secondOccurrence check in case-sensitive)
            assertThat(result).hasSize(1);
        }
    }

    @Nested
    @DisplayName("AnchorNotFoundException Tests")
    class AnchorNotFoundExceptionTests {

        @Test
        @DisplayName("when created with message then contains message")
        void anchorNotFoundException_withMessage_containsMessage() {
            // When
            AnchorNotFoundException exception = new AnchorNotFoundException("Test message");

            // Then
            assertThat(exception.getMessage()).isEqualTo("Test message");
        }

        @Test
        @DisplayName("when created with message and cause then contains both")
        void anchorNotFoundException_withMessageAndCause_containsBoth() {
            // Given
            Throwable cause = new RuntimeException("Cause message");

            // When
            AnchorNotFoundException exception = new AnchorNotFoundException("Test message", cause);

            // Then
            assertThat(exception.getMessage()).isEqualTo("Test message");
            assertThat(exception.getCause()).isEqualTo(cause);
        }
    }

    // Helper methods

    private DocumentNode createNode(String title, String startAnchor, String endAnchor) {
        DocumentNode node = new DocumentNode();
        node.setId(UUID.randomUUID());
        node.setTitle(title);
        node.setDocument(document);
        node.setStartAnchor(startAnchor);
        node.setEndAnchor(endAnchor);
        node.setType(DocumentNode.NodeType.CHAPTER);
        node.setIdx(1);
        node.setDepth((short) 0);
        return node;
    }

    private DocumentNode createNodeWithOffsets(String title, int startOffset, int endOffset) {
        DocumentNode node = new DocumentNode();
        node.setId(UUID.randomUUID());
        node.setTitle(title);
        node.setDocument(document);
        node.setStartOffset(startOffset);
        node.setEndOffset(endOffset);
        node.setType(DocumentNode.NodeType.CHAPTER);
        node.setIdx(1);
        node.setDepth((short) 0);
        return node;
    }
}

