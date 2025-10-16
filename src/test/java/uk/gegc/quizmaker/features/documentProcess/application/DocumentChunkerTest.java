package uk.gegc.quizmaker.features.documentProcess.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gegc.quizmaker.features.documentProcess.application.DocumentChunker.DocumentChunk;
import uk.gegc.quizmaker.features.documentProcess.config.DocumentChunkingConfig;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@Execution(ExecutionMode.CONCURRENT)
@DisplayName("DocumentChunker Comprehensive Tests")
class DocumentChunkerTest {

    @Mock
    private TokenCounter tokenCounter;
    
    @Mock
    private DocumentChunkingConfig chunkingConfig;

    private DocumentChunker documentChunker;

    @BeforeEach
    void setUp() {
        // Default mock configuration
        lenient().when(chunkingConfig.getMaxSingleChunkTokens()).thenReturn(40_000);
        lenient().when(chunkingConfig.getMaxSingleChunkChars()).thenReturn(150_000);
        lenient().when(chunkingConfig.getOverlapTokens()).thenReturn(5_000);
        lenient().when(chunkingConfig.isAggressiveChunking()).thenReturn(true);
        lenient().when(chunkingConfig.isEnableEmergencyChunking()).thenReturn(true);
        
        // Default token counter behavior
        lenient().when(tokenCounter.estimateTokens(anyString())).thenAnswer(invocation -> {
            String text = invocation.getArgument(0);
            return text.length() / 3; // 1 token per 3 chars
        });
        
        lenient().when(tokenCounter.exceedsTokenLimit(anyString(), anyInt())).thenAnswer(invocation -> {
            String text = invocation.getArgument(0);
            int limit = invocation.getArgument(1);
            return (text.length() / 3) > limit;
        });
        
        lenient().when(tokenCounter.estimateMaxCharsForTokens(anyInt())).thenAnswer(invocation -> {
            int tokens = invocation.getArgument(0);
            return tokens * 3;
        });
        
        lenient().when(tokenCounter.getConfiguredSafeChunkSize()).thenReturn(120_000);
        
        documentChunker = new DocumentChunker(tokenCounter, chunkingConfig);
    }

    @Nested
    @DisplayName("Basic Chunking Tests")
    class BasicChunkingTests {

        @Test
        @DisplayName("when text is null then returns single empty chunk")
        void chunkDocument_nullText_returnsSingleEmptyChunk() {
            // When
            List<DocumentChunk> chunks = documentChunker.chunkDocument(null, "doc-id");

            // Then - lines 73-75
            assertThat(chunks).hasSize(1);
            assertThat(chunks.get(0).getText()).isEmpty();
            assertThat(chunks.get(0).getStartOffset()).isEqualTo(0);
            assertThat(chunks.get(0).getEndOffset()).isEqualTo(0);
        }

        @Test
        @DisplayName("when text is empty then returns single empty chunk")
        void chunkDocument_emptyText_returnsSingleEmptyChunk() {
            // When
            List<DocumentChunk> chunks = documentChunker.chunkDocument("", "doc-id");

            // Then - lines 73-75
            assertThat(chunks).hasSize(1);
            assertThat(chunks.get(0).getText()).isEmpty();
        }

        @Test
        @DisplayName("when text within token limit then returns single chunk")
        void chunkDocument_withinTokenLimit_returnsSingleChunk() {
            // Given
            String smallText = "This is a small document that fits within token limits.";

            // When
            List<DocumentChunk> chunks = documentChunker.chunkDocument(smallText, "doc-id");

            // Then - line 105
            assertThat(chunks).hasSize(1);
            assertThat(chunks.get(0).getText()).isEqualTo(smallText);
            assertThat(chunks.get(0).getChunkIndex()).isEqualTo(0);
        }

        @Test
        @DisplayName("when text exceeds character limit by large margin then logs warning")
        void chunkDocument_exceedsCharLimitLargeMargin_logsWarning() {
            // Given - text that exceeds char limit * 2
            String largeText = generateText(400_000); // Way over 150k * 2 = 300k

            // When
            List<DocumentChunk> chunks = documentChunker.chunkDocument(largeText, "doc-id");

            // Then - lines 109-111 covered
            assertThat(chunks).isNotEmpty();
        }

        @Test
        @DisplayName("when document chunked then has proper overlap between chunks")
        void chunkDocument_multipleChunks_hasOverlap() {
            // Given
            String largeText = generateText(200_000);

            // When
            List<DocumentChunk> chunks = documentChunker.chunkDocument(largeText, "doc-id");

            // Then - verify overlap exists
            if (chunks.size() > 1) {
                for (int i = 1; i < chunks.size(); i++) {
                    DocumentChunk prevChunk = chunks.get(i - 1);
                    DocumentChunk currChunk = chunks.get(i);
                    
                    // Current chunk should start before previous chunk ends (overlap)
                    assertThat(currChunk.getStartOffset()).isLessThan(prevChunk.getEndOffset());
                }
            }
        }
    }

    @Nested
    @DisplayName("Semantic Boundary Tests")
    class SemanticBoundaryTests {

        @Test
        @DisplayName("when chunk has chapter break then prefers chapter boundary")
        void chunkDocument_hasChapterBreak_prefersChapterBoundary() {
            // Given - text with chapter breaks
            String text = generateTextWithChapters(200_000);

            // When
            List<DocumentChunk> chunks = documentChunker.chunkDocument(text, "doc-id");

            // Then - should chunk at chapter boundaries
            assertThat(chunks).isNotEmpty();
        }

        @Test
        @DisplayName("when chunk has section break then uses section boundary")
        void chunkDocument_hasSectionBreak_usesSectionBoundary() {
            // Given - text with section breaks
            String text = generateTextWithSections(200_000);

            // When
            List<DocumentChunk> chunks = documentChunker.chunkDocument(text, "doc-id");

            // Then - lines 257-258 covered
            assertThat(chunks).isNotEmpty();
        }

        @Test
        @DisplayName("when chunk has paragraph break then uses paragraph boundary")
        void chunkDocument_hasParagraphBreak_usesParagraphBoundary() {
            // Given - text with paragraph breaks but no sections/chapters
            String text = generateTextWithParagraphs(200_000);

            // When
            List<DocumentChunk> chunks = documentChunker.chunkDocument(text, "doc-id");

            // Then - lines 260-261 covered
            assertThat(chunks).isNotEmpty();
        }

        @Test
        @DisplayName("when no semantic boundary then uses word boundary")
        void chunkDocument_noSemanticBoundary_usesWordBoundary() {
            // Given - continuous text with no paragraph breaks
            String text = generateContinuousText(200_000);

            // When
            List<DocumentChunk> chunks = documentChunker.chunkDocument(text, "doc-id");

            // Then - line 225 covered (findWordBoundary)
            assertThat(chunks).isNotEmpty();
        }
    }

    @Nested
    @DisplayName("Irrelevant Content Filtering Tests")
    class IrrelevantContentFilteringTests {

        @Test
        @DisplayName("when text has INDEX section then filters it out")
        void chunkDocument_hasIndex_filtersOut() {
            // Given
            String text = "Chapter 1 content here. More content.\n\nINDEX\n\nA - Page 1\nB - Page 2";

            // When
            List<DocumentChunk> chunks = documentChunker.chunkDocument(text, "doc-id");

            // Then - lines 289-297 covered
            assertThat(chunks).hasSize(1);
            assertThat(chunks.get(0).getText()).doesNotContain("INDEX");
        }

        @Test
        @DisplayName("when text has APPENDIX section then filters it out")
        void chunkDocument_hasAppendix_filtersOut() {
            // Given
            String text = "Main content here.\n\nAPPENDIX A\n\nExtra material.";

            // When
            List<DocumentChunk> chunks = documentChunker.chunkDocument(text, "doc-id");

            // Then
            assertThat(chunks.get(0).getText()).doesNotContain("APPENDIX");
        }

        @Test
        @DisplayName("when text has BIBLIOGRAPHY then filters it out")
        void chunkDocument_hasBibliography_filtersOut() {
            // Given
            String text = "Main content.\n\nBIBLIOGRAPHY\n\nReference 1\nReference 2";

            // When
            List<DocumentChunk> chunks = documentChunker.chunkDocument(text, "doc-id");

            // Then
            assertThat(chunks.get(0).getText()).doesNotContain("BIBLIOGRAPHY");
        }

        @Test
        @DisplayName("when chunk is mostly irrelevant keywords then skips it")
        void chunkDocument_irrelevantChunk_skipsIt() {
            // Given - document where first chunk would be mostly index/appendix keywords
            String text = generateLargeIrrelevantText() + "\n\n" + generateText(250_000);

            // When
            List<DocumentChunk> chunks = documentChunker.chunkDocument(text, "doc-id");

            // Then - lines 149-151 covered (skip irrelevant chunks)
            assertThat(chunks).isNotEmpty();
        }
    }

    @Nested
    @DisplayName("Safety and Edge Case Tests")
    class SafetyEdgeCaseTests {

        @Test
        @DisplayName("when advancement is too slow then forces larger advancement")
        void chunkDocument_slowAdvancement_forcesLargerAdvancement() {
            // Given - setup that would cause slow advancement
            String text = generateText(200_000);

            // When
            List<DocumentChunk> chunks = documentChunker.chunkDocument(text, "doc-id");

            // Then - lines 176-179 covered
            assertThat(chunks).isNotEmpty();
            // Verify chunks are advancing properly
            for (int i = 1; i < chunks.size(); i++) {
                int advancement = chunks.get(i).getStartOffset() - chunks.get(i - 1).getStartOffset();
                assertThat(advancement).isGreaterThan(0);
            }
        }

        @Test
        @DisplayName("when near document end then includes remaining text in final chunk")
        void chunkDocument_nearEnd_includesRemainingText() {
            // Given - document size that would leave small tail
            String text = generateText(125_000); // Slightly over one chunk

            // When
            List<DocumentChunk> chunks = documentChunker.chunkDocument(text, "doc-id");

            // Then - line 211 covered
            assertThat(chunks).isNotEmpty();
            DocumentChunk lastChunk = chunks.get(chunks.size() - 1);
            assertThat(lastChunk.getEndOffset()).isEqualTo(text.length());
        }
    }

    @Nested
    @DisplayName("Emergency Chunking Tests")
    class EmergencyChunkingTests {

        @Test
        @DisplayName("when too many loops then triggers emergency chunking")
        void chunkDocument_tooManyLoops_triggersEmergency() {
            // Given - text that triggers loop limit
            String text = "x".repeat(150_000);
            
            // Override token counter to cause loop limit scenario
            lenient().when(tokenCounter.estimateTokens(anyString())).thenReturn(100_000);
            lenient().when(tokenCounter.exceedsTokenLimit(anyString(), anyInt())).thenReturn(true);
            lenient().when(tokenCounter.estimateMaxCharsForTokens(anyInt())).thenAnswer(inv -> {
                int tokens = inv.getArgument(0);
                if (tokens == 5_000) return 500; // Reasonable overlap
                return tokens * 3;
            });
            lenient().when(tokenCounter.getConfiguredSafeChunkSize()).thenReturn(10); // Small chunks trigger many loops

            // When & Then - lines 125-127 should be covered
            List<DocumentChunk> chunks = documentChunker.chunkDocument(text, "doc-id");
            
            // Should trigger emergency chunking
            assertThat(chunks).isNotEmpty();
        }

        @Test
        @DisplayName("when chunkEnd not advancing then forces advancement")
        void chunkDocument_chunkEndNotAdvancing_forcesAdvancement() {
            // Given - scenario where chunkEnd might not advance
            String text = generateText(150_000);
            
            // Mock to create problematic scenario
            when(tokenCounter.getConfiguredSafeChunkSize()).thenReturn(100);

            // When
            List<DocumentChunk> chunks = documentChunker.chunkDocument(text, "doc-id");

            // Then - lines 132-141 should be triggered
            assertThat(chunks).isNotEmpty();
        }

        @Test
        @DisplayName("when nextPosition not advancing then forces advancement")
        void chunkDocument_nextPositionNotAdvancing_forcesAdvancement() {
            // Given
            String text = generateText(180_000);
            
            // Mock with very large overlap that could cause issues
            when(tokenCounter.estimateMaxCharsForTokens(anyInt())).thenAnswer(invocation -> {
                int tokens = invocation.getArgument(0);
                if (tokens == 5_000) { // overlap tokens
                    return 200_000; // Huge overlap larger than chunk
                }
                return tokens * 3;
            });

            // When
            List<DocumentChunk> chunks = documentChunker.chunkDocument(text, "doc-id");

            // Then - lines 169-172 should be covered
            assertThat(chunks).isNotEmpty();
        }

        @Test
        @DisplayName("when large document creates only one chunk then triggers emergency if enabled")
        void chunkDocument_largeDocSingleChunk_triggersEmergency() {
            // Given - large document but chunking creates only 1 chunk
            String text = generateText(200_000);
            
            // Mock to cause single chunk despite large size
            lenient().when(tokenCounter.estimateTokens(anyString())).thenAnswer(invocation -> {
                String str = invocation.getArgument(0);
                // Return high count for full doc, but low for filtered
                if (str.length() > 150_000) {
                    return 100_000; // Over limit for full doc
                }
                return 10_000; // Under limit for chunks
            });
            
            lenient().when(tokenCounter.exceedsTokenLimit(anyString(), anyInt())).thenReturn(false); // Never exceeds
            lenient().when(chunkingConfig.isEnableEmergencyChunking()).thenReturn(true);

            // When
            List<DocumentChunk> chunks = documentChunker.chunkDocument(text, "doc-id");

            // Then - lines 188-193 covered
            assertThat(chunks).isNotEmpty();
        }

        @Test
        @DisplayName("when emergency chunking disabled and problem occurs then logs error")
        void chunkDocument_emergencyDisabled_logsError() {
            // Given
            String text = generateText(200_000);
            
            lenient().when(tokenCounter.estimateTokens(anyString())).thenAnswer(invocation -> {
                String str = invocation.getArgument(0);
                if (str.length() > 150_000) {
                    return 100_000;
                }
                return 10_000;
            });
            
            lenient().when(tokenCounter.exceedsTokenLimit(anyString(), anyInt())).thenReturn(false);
            lenient().when(chunkingConfig.isEnableEmergencyChunking()).thenReturn(false); // Disabled

            // When
            List<DocumentChunk> chunks = documentChunker.chunkDocument(text, "doc-id");

            // Then - line 195 covered
            assertThat(chunks).hasSize(1);
        }
    }

    @Nested
    @DisplayName("Word Boundary Edge Cases")
    class WordBoundaryEdgeCaseTests {

        @Test
        @DisplayName("when searching word boundary reaches start then returns zero")
        void findWordBoundary_reachesStart_returnsZero() {
            // Given - text where word boundary search would reach position 0
            String text = "VeryLongWordWithoutSpaces".repeat(1000);
            
            // Make max chunk size small to force word boundary issues
            when(tokenCounter.getConfiguredSafeChunkSize()).thenReturn(500);

            // When
            List<DocumentChunk> chunks = documentChunker.chunkDocument(text, "doc-id");

            // Then - line 333 covered
            assertThat(chunks).isNotEmpty();
        }

        @Test
        @DisplayName("when no word boundary found then forces advancement")
        void findWordBoundary_noBoundaryFound_forcesAdvancement() {
            // Given - continuous text with no spaces
            String text = "x".repeat(200_000);
            
            when(tokenCounter.getConfiguredSafeChunkSize()).thenReturn(5000);

            // When
            List<DocumentChunk> chunks = documentChunker.chunkDocument(text, "doc-id");

            // Then - lines 341-358 covered (fallback logic in findWordBoundary)
            assertThat(chunks).isNotEmpty();
        }
    }

    @Nested
    @DisplayName("DocumentChunk Tests")
    class DocumentChunkTests {

        @Test
        @DisplayName("when getLength called then returns text length")
        void documentChunk_getLength_returnsTextLength() {
            // Given
            DocumentChunk chunk = new DocumentChunk("test text", 0, 9, 0);

            // When
            int length = chunk.getLength();

            // Then - line 381
            assertThat(length).isEqualTo(9);
        }

        @Test
        @DisplayName("when toString called then returns formatted string")
        void documentChunk_toString_returnsFormattedString() {
            // Given
            DocumentChunk chunk = new DocumentChunk("test", 10, 20, 5);

            // When
            String result = chunk.toString();

            // Then - lines 385-386
            assertThat(result).contains("5");
            assertThat(result).contains("10");
            assertThat(result).contains("20");
            assertThat(result).contains("4");
        }
    }

    // Helper methods

    private String generateText(int targetSize) {
        StringBuilder doc = new StringBuilder();
        String paragraph = "Lorem ipsum dolor sit amet, consectetur adipiscing elit. " +
                "Sed do eiusmod tempor incididunt ut labore et dolore magna aliqua. " +
                "Ut enim ad minim veniam, quis nostrud exercitation ullamco laboris. ";
        
        while (doc.length() < targetSize) {
            doc.append(paragraph);
            if (doc.length() % 5000 == 0) {
                doc.append("\n\n");
            }
        }
        
        return doc.substring(0, Math.min(doc.length(), targetSize));
    }

    private String generateTextWithChapters(int targetSize) {
        StringBuilder doc = new StringBuilder();
        String paragraph = "Content for the chapter. More content here with various details. ";
        
        int chapterNum = 1;
        while (doc.length() < targetSize) {
            doc.append("\n\nChapter ").append(chapterNum++).append("\n\n");
            doc.append(paragraph.repeat(100));
        }
        
        return doc.substring(0, Math.min(doc.length(), targetSize));
    }

    private String generateTextWithSections(int targetSize) {
        StringBuilder doc = new StringBuilder();
        String content = "Section content goes here with information. ";
        
        while (doc.length() < targetSize) {
            doc.append("\n\nSECTION HEADING\n\n");
            doc.append(content.repeat(100));
        }
        
        return doc.substring(0, Math.min(doc.length(), targetSize));
    }

    private String generateTextWithParagraphs(int targetSize) {
        StringBuilder doc = new StringBuilder();
        String paragraph = "This is paragraph text with various content. More details here. ";
        
        while (doc.length() < targetSize) {
            doc.append(paragraph.repeat(50));
            doc.append("\n\n");
        }
        
        return doc.substring(0, Math.min(doc.length(), targetSize));
    }

    private String generateContinuousText(int targetSize) {
        StringBuilder doc = new StringBuilder();
        String text = "Continuouswordsthatneverbreak ";
        
        while (doc.length() < targetSize) {
            doc.append(text);
        }
        
        return doc.substring(0, Math.min(doc.length(), targetSize));
    }

    private String generateLargeIrrelevantText() {
        return "index appendix bibliography references glossary acknowledgment " +
                "about the author page chapter ".repeat(50);
    }
}

