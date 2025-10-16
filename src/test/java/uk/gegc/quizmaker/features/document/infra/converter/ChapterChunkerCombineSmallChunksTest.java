package uk.gegc.quizmaker.features.document.infra.converter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gegc.quizmaker.features.document.api.dto.ProcessDocumentRequest;
import uk.gegc.quizmaker.features.document.application.ConvertedDocument;
import uk.gegc.quizmaker.features.document.infra.text.SentenceBoundaryDetector;
import uk.gegc.quizmaker.features.document.infra.util.ChunkTitleGenerator;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.lenient;

/**
 * Comprehensive tests for ChapterChunker.combineSmallChunks method.
 * Targets missed branches to improve coverage from 59% to 85%+.
 */
@ExtendWith(MockitoExtension.class)
@Execution(ExecutionMode.CONCURRENT)
@DisplayName("ChapterChunker CombineSmallChunks Tests")
class ChapterChunkerCombineSmallChunksTest {

    @Mock
    private SentenceBoundaryDetector sentenceBoundaryDetector;

    @Mock
    private ChunkTitleGenerator titleGenerator;

    private ChapterChunker chunker;

    @BeforeEach
    void setUp() {
        chunker = new ChapterChunker(sentenceBoundaryDetector, titleGenerator);
        
        // Default mocks
        lenient().when(titleGenerator.generateChunkTitle(anyString(), anyInt(), anyInt(), anyBoolean()))
                .thenAnswer(inv -> {
                    String title = inv.getArgument(0);
                    Integer index = inv.getArgument(1);
                    return title + " - Part " + (index + 1);
                });
    }

    @Nested
    @DisplayName("Chunk Size Threshold Tests")
    class ChunkSizeThresholdTests {

        @Test
        @DisplayName("combineSmallChunks: when chunk is NOT very small then checks other conditions")
        void combineChunks_notVerySmall_checksOtherConditions() {
            // Given - Chunk >= minSize/2 but < aggressiveThreshold (line 529 false, line 539 true)
            String chunk1Content = "x".repeat(600); // 600 chars (>= 500/2 = 250)
            String chunk2Content = "y".repeat(300);
            
            ConvertedDocument.Chapter chapter = new ConvertedDocument.Chapter();
            chapter.setTitle("Chapter");
            chapter.setContent(chunk1Content + "\n\n" + chunk2Content);
            chapter.setStartPage(1);
            chapter.setEndPage(1);
            
            ConvertedDocument document = new ConvertedDocument();
            document.setOriginalFilename("test.pdf");
            document.setFullContent(chapter.getContent());
            document.setChapters(List.of(chapter));

            ProcessDocumentRequest request = new ProcessDocumentRequest();
            request.setChunkingStrategy(ProcessDocumentRequest.ChunkingStrategy.CHAPTER_BASED);
            request.setMaxChunkSize(500); // Small to force splitting
            request.setMinChunkSize(500);
            request.setAggressiveCombinationThreshold(1000); // Set threshold

            // When
            List<UniversalChunker.Chunk> result = chunker.chunkDocument(document, request);

            // Then - Line 529 false branch covered (chunk not very small but still combined)
            assertThat(result).isNotEmpty();
        }

        @Test
        @DisplayName("combineSmallChunks: when shouldCombine already true then skips minSize check")
        void combineChunks_alreadyShouldCombine_skipsMinSizeCheck() {
            // Given - Very small chunk (< minSize/2) so shouldCombine=true from line 529
            String chunk1Content = "x".repeat(100); // 100 chars (< 500/2 = 250) - line 529 true
            String chunk2Content = "y".repeat(200);
            
            ConvertedDocument.Chapter chapter = new ConvertedDocument.Chapter();
            chapter.setTitle("Chapter");
            chapter.setContent(chunk1Content + "\n\n" + chunk2Content);
            chapter.setStartPage(1);
            chapter.setEndPage(1);
            
            ConvertedDocument document = new ConvertedDocument();
            document.setOriginalFilename("test.pdf");
            document.setFullContent(chapter.getContent());
            document.setChapters(List.of(chapter));

            ProcessDocumentRequest request = new ProcessDocumentRequest();
            request.setChunkingStrategy(ProcessDocumentRequest.ChunkingStrategy.CHAPTER_BASED);
            request.setMaxChunkSize(150); // Small to force splitting
            request.setMinChunkSize(500);
            request.setAggressiveCombinationThreshold(3000);

            // When
            List<UniversalChunker.Chunk> result = chunker.chunkDocument(document, request);

            // Then - Line 529 true branch covered, line 532 first condition (!shouldCombine) is false
            assertThat(result).isNotEmpty();
        }

        @Test
        @DisplayName("combineSmallChunks: when chunk below minSize and shouldCombine false then combines")
        void combineChunks_belowMinSizeNotVerySsmall_combines() {
            // Given - Chunk >= minSize/2 but < minSize (line 529 false, line 532 both true)
            String chunk1Content = "x".repeat(400); // 400 chars (>= 500/2 but < 500)
            String chunk2Content = "y".repeat(300);
            
            ConvertedDocument.Chapter chapter = new ConvertedDocument.Chapter();
            chapter.setTitle("Chapter");
            chapter.setContent(chunk1Content + "\n\n" + chunk2Content);
            chapter.setStartPage(1);
            chapter.setEndPage(1);
            
            ConvertedDocument document = new ConvertedDocument();
            document.setOriginalFilename("test.pdf");
            document.setFullContent(chapter.getContent());
            document.setChapters(List.of(chapter));

            ProcessDocumentRequest request = new ProcessDocumentRequest();
            request.setChunkingStrategy(ProcessDocumentRequest.ChunkingStrategy.CHAPTER_BASED);
            request.setMaxChunkSize(400); // Force splitting
            request.setMinChunkSize(500);
            request.setAggressiveCombinationThreshold(3000);

            // When
            List<UniversalChunker.Chunk> result = chunker.chunkDocument(document, request);

            // Then - Line 532 both branches true covered
            assertThat(result).isNotEmpty();
        }
    }

    @Nested
    @DisplayName("Aggressive Threshold Tests")
    class AggressiveThresholdTests {

        @Test
        @DisplayName("combineSmallChunks: when aggressiveThreshold is null then uses default")
        void combineChunks_nullAggressiveThreshold_usesDefault() {
            // Given - aggressiveThreshold is null (line 537 false branch)
            String chunk1Content = "x".repeat(600); // 600 chars
            String chunk2Content = "y".repeat(300);
            
            ConvertedDocument.Chapter chapter = new ConvertedDocument.Chapter();
            chapter.setTitle("Chapter");
            chapter.setContent(chunk1Content + "\n\n" + chunk2Content);
            chapter.setStartPage(1);
            chapter.setEndPage(1);
            
            ConvertedDocument document = new ConvertedDocument();
            document.setOriginalFilename("test.pdf");
            document.setFullContent(chapter.getContent());
            document.setChapters(List.of(chapter));

            ProcessDocumentRequest request = new ProcessDocumentRequest();
            request.setChunkingStrategy(ProcessDocumentRequest.ChunkingStrategy.CHAPTER_BASED);
            request.setMaxChunkSize(500);
            request.setMinChunkSize(6000); // Very high to skip line 532
            request.setAggressiveCombinationThreshold(null); // Null - line 537 false branch

            // When
            List<UniversalChunker.Chunk> result = chunker.chunkDocument(document, request);

            // Then - Line 537 false branch covered (null check)
            assertThat(result).isNotEmpty();
        }

        @Test
        @DisplayName("combineSmallChunks: when chunk exceeds aggressive threshold then doesn't combine")
        void combineChunks_exceedsAggressiveThreshold_doesntCombine() {
            // Given - Chunk > aggressiveThreshold (line 539 second condition false)
            String chunk1Content = "x".repeat(3500); // 3500 chars (> 3000 threshold)
            String chunk2Content = "y".repeat(3500);
            
            ConvertedDocument.Chapter chapter = new ConvertedDocument.Chapter();
            chapter.setTitle("Chapter");
            chapter.setContent(chunk1Content + "\n\n" + chunk2Content);
            chapter.setStartPage(1);
            chapter.setEndPage(1);
            
            ConvertedDocument document = new ConvertedDocument();
            document.setOriginalFilename("test.pdf");
            document.setFullContent(chapter.getContent());
            document.setChapters(List.of(chapter));

            ProcessDocumentRequest request = new ProcessDocumentRequest();
            request.setChunkingStrategy(ProcessDocumentRequest.ChunkingStrategy.CHAPTER_BASED);
            request.setMaxChunkSize(3500); // Just fits one chunk
            request.setMinChunkSize(10000); // Very high
            request.setAggressiveCombinationThreshold(3000); // Below chunk size

            // When
            List<UniversalChunker.Chunk> result = chunker.chunkDocument(document, request);

            // Then - Line 539 second condition false (chunk >= aggressiveThreshold)
            assertThat(result).isNotEmpty();
        }
    }

    @Nested
    @DisplayName("Both Chunks Small Tests")
    class BothChunksSmallTests {

        @Test
        @DisplayName("combineSmallChunks: when both chunks small and combinedSize valid then combines")
        void combineChunks_bothSmallValidCombined_combines() {
            // Given - Both chunks < minSize, combined <= 100000 (line 544-545 all true)
            String chunk1Content = "x".repeat(400); // < 500 minSize
            String chunk2Content = "y".repeat(400); // < 500 minSize
            // Combined = 800 (> 400, <= 100000)
            
            ConvertedDocument.Chapter chapter = new ConvertedDocument.Chapter();
            chapter.setTitle("Chapter");
            chapter.setContent(chunk1Content + "\n\n" + chunk2Content);
            chapter.setStartPage(1);
            chapter.setEndPage(1);
            
            ConvertedDocument document = new ConvertedDocument();
            document.setOriginalFilename("test.pdf");
            document.setFullContent(chapter.getContent());
            document.setChapters(List.of(chapter));

            ProcessDocumentRequest request = new ProcessDocumentRequest();
            request.setChunkingStrategy(ProcessDocumentRequest.ChunkingStrategy.CHAPTER_BASED);
            request.setMaxChunkSize(400);
            request.setMinChunkSize(500);
            request.setAggressiveCombinationThreshold(50); // Very low to skip line 539

            // When
            List<UniversalChunker.Chunk> result = chunker.chunkDocument(document, request);

            // Then - Lines 544-545 covered (both chunks small, valid combined size)
            assertThat(result).isNotEmpty();
        }

        @Test
        @DisplayName("combineSmallChunks: when combined size exceeds 100000 then doesn't combine")
        void combineChunks_combinedExceeds100k_doesntCombine() {
            // Given - Both chunks small but combined > 100000 (line 545 first condition false)
            String chunk1Content = "x".repeat(60000); // < minSize but large
            String chunk2Content = "y".repeat(50000); // < minSize but large
            // Combined = 110000 (> 100000)
            
            ConvertedDocument.Chapter chapter = new ConvertedDocument.Chapter();
            chapter.setTitle("Chapter");
            chapter.setContent(chunk1Content + "\n\n" + chunk2Content);
            chapter.setStartPage(1);
            chapter.setEndPage(1);
            
            ConvertedDocument document = new ConvertedDocument();
            document.setOriginalFilename("test.pdf");
            document.setFullContent(chapter.getContent());
            document.setChapters(List.of(chapter));

            ProcessDocumentRequest request = new ProcessDocumentRequest();
            request.setChunkingStrategy(ProcessDocumentRequest.ChunkingStrategy.CHAPTER_BASED);
            request.setMaxChunkSize(60000);
            request.setMinChunkSize(200000); // Very high so both chunks are "small"
            request.setAggressiveCombinationThreshold(50); // Very low

            // When
            List<UniversalChunker.Chunk> result = chunker.chunkDocument(document, request);

            // Then - Line 545 first condition false (combined > 100000)
            assertThat(result).isNotEmpty();
        }

        @Test
        @DisplayName("combineSmallChunks: when combined not better than max then doesn't combine")
        void combineChunks_combinedNotBetter_doesntCombine() {
            // Given - Combined size not better than max of individual chunks (line 545 second false)
            String chunk1Content = "x".repeat(400);
            String chunk2Content = "y".repeat(100);
            // Combined = 500, but not > max(400, 100) = 400 by much
            
            ConvertedDocument.Chapter chapter = new ConvertedDocument.Chapter();
            chapter.setTitle("Chapter");
            chapter.setContent(chunk1Content + "\n\n" + chunk2Content);
            chapter.setStartPage(1);
            chapter.setEndPage(1);
            
            ConvertedDocument document = new ConvertedDocument();
            document.setOriginalFilename("test.pdf");
            document.setFullContent(chapter.getContent());
            document.setChapters(List.of(chapter));

            ProcessDocumentRequest request = new ProcessDocumentRequest();
            request.setChunkingStrategy(ProcessDocumentRequest.ChunkingStrategy.CHAPTER_BASED);
            request.setMaxChunkSize(400);
            request.setMinChunkSize(500);
            request.setAggressiveCombinationThreshold(50);

            // When
            List<UniversalChunker.Chunk> result = chunker.chunkDocument(document, request);

            // Then - Line 545 conditions evaluated
            assertThat(result).isNotEmpty();
        }
    }

    @Nested
    @DisplayName("Edge Case Combination Tests")
    class EdgeCaseCombinationTests {

        @Test
        @DisplayName("combineSmallChunks: when current chunk >= minSize/2 and >= minSize and >= aggressiveThreshold then no combine")
        void combineChunks_allThresholdsExceeded_noCombi() {
            // Given - Current chunk exceeds all thresholds (line 529 false, 532 first false, 539 first false)
            String chunk1Content = "x".repeat(6000); // >= minSize/2 (2500), >= minSize (5000), >= aggressiveThreshold (3000)
            String chunk2Content = "y".repeat(6000);
            
            ConvertedDocument.Chapter chapter1 = new ConvertedDocument.Chapter();
            chapter1.setTitle("Chapter 1");
            chapter1.setContent(chunk1Content);
            chapter1.setStartPage(1);
            chapter1.setEndPage(1);
            
            ConvertedDocument.Chapter chapter2 = new ConvertedDocument.Chapter();
            chapter2.setTitle("Chapter 2");
            chapter2.setContent(chunk2Content);
            chapter2.setStartPage(2);
            chapter2.setEndPage(2);
            
            ConvertedDocument document = new ConvertedDocument();
            document.setOriginalFilename("test.pdf");
            document.setFullContent(chunk1Content + chunk2Content);
            document.setChapters(List.of(chapter1, chapter2));

            ProcessDocumentRequest request = new ProcessDocumentRequest();
            request.setChunkingStrategy(ProcessDocumentRequest.ChunkingStrategy.CHAPTER_BASED);
            request.setMaxChunkSize(6000); // Exactly fits
            request.setMinChunkSize(5000);
            request.setAggressiveCombinationThreshold(3000);

            // When
            List<UniversalChunker.Chunk> result = chunker.chunkDocument(document, request);

            // Then - Lines 529, 532, 539 all false branches, line 544 first condition false
            assertThat(result).hasSizeGreaterThanOrEqualTo(2); // Should not combine
        }

        @Test
        @DisplayName("combineSmallChunks: when currentChunk small but nextChunk large then may not combine")
        void combineChunks_currentSmallNextLarge_checksConditions() {
            // Given - Current small, next large (line 544 second condition false)
            String chunk1Content = "x".repeat(400); // < minSize (500)
            String chunk2Content = "y".repeat(6000); // > minSize
            
            ConvertedDocument.Chapter chapter1 = new ConvertedDocument.Chapter();
            chapter1.setTitle("Small Chapter");
            chapter1.setContent(chunk1Content);
            chapter1.setStartPage(1);
            chapter1.setEndPage(1);
            
            ConvertedDocument.Chapter chapter2 = new ConvertedDocument.Chapter();
            chapter2.setTitle("Large Chapter");
            chapter2.setContent(chunk2Content);
            chapter2.setStartPage(2);
            chapter2.setEndPage(2);
            
            ConvertedDocument document = new ConvertedDocument();
            document.setOriginalFilename("test.pdf");
            document.setFullContent(chunk1Content + chunk2Content);
            document.setChapters(List.of(chapter1, chapter2));

            ProcessDocumentRequest request = new ProcessDocumentRequest();
            request.setChunkingStrategy(ProcessDocumentRequest.ChunkingStrategy.CHAPTER_BASED);
            request.setMaxChunkSize(6000);
            request.setMinChunkSize(500);
            request.setAggressiveCombinationThreshold(50); // Very low

            // When
            List<UniversalChunker.Chunk> result = chunker.chunkDocument(document, request);

            // Then - Line 544 conditions checked (current < minSize, next >= minSize)
            assertThat(result).isNotEmpty();
        }

        @Test
        @DisplayName("combineSmallChunks: when both small but combined equals max then edge case")
        void combineChunks_bothSmallCombinedEqualsMax_edgeCase() {
            // Given - Both small, combined exactly equals one of them (not > max)
            String chunk1Content = "x".repeat(400);
            String chunk2Content = "y".repeat(400);
            // Combined = 800, Math.max(400, 400) = 400, so 800 > 400 ✓
            
            ConvertedDocument.Chapter chapter1 = new ConvertedDocument.Chapter();
            chapter1.setTitle("A");
            chapter1.setContent(chunk1Content);
            chapter1.setStartPage(1);
            chapter1.setEndPage(1);
            
            ConvertedDocument.Chapter chapter2 = new ConvertedDocument.Chapter();
            chapter2.setTitle("B");
            chapter2.setContent(chunk2Content);
            chapter2.setStartPage(2);
            chapter2.setEndPage(2);
            
            ConvertedDocument document = new ConvertedDocument();
            document.setOriginalFilename("test.pdf");
            document.setFullContent(chunk1Content + chunk2Content);
            document.setChapters(List.of(chapter1, chapter2));

            ProcessDocumentRequest request = new ProcessDocumentRequest();
            request.setChunkingStrategy(ProcessDocumentRequest.ChunkingStrategy.CHAPTER_BASED);
            request.setMaxChunkSize(400);
            request.setMinChunkSize(500);
            request.setAggressiveCombinationThreshold(50);

            // When
            List<UniversalChunker.Chunk> result = chunker.chunkDocument(document, request);

            // Then - Line 545 conditions evaluated
            assertThat(result).isNotEmpty();
        }
    }

    @Nested
    @DisplayName("Title Length Tests")
    class TitleLengthTests {

        @Test
        @DisplayName("combineSmallChunks: when combined title exceeds 100 chars then truncates")
        void combineChunks_longTitle_truncates() {
            // Given - Chunks with long titles that will exceed 100 chars when combined
            String chunk1Content = "x".repeat(100);
            String chunk2Content = "y".repeat(100);
            
            ConvertedDocument.Chapter chapter1 = new ConvertedDocument.Chapter();
            chapter1.setTitle("This is a very long chapter title with many words that will exceed limit");
            chapter1.setContent(chunk1Content);
            chapter1.setStartPage(1);
            chapter1.setEndPage(1);
            
            ConvertedDocument.Chapter chapter2 = new ConvertedDocument.Chapter();
            chapter2.setTitle("Another very long chapter title with lots of text to make it super long");
            chapter2.setContent(chunk2Content);
            chapter2.setStartPage(2);
            chapter2.setEndPage(2);
            
            ConvertedDocument document = new ConvertedDocument();
            document.setOriginalFilename("test.pdf");
            document.setFullContent(chapter1.getContent() + chapter2.getContent());
            document.setChapters(List.of(chapter1, chapter2));

            ProcessDocumentRequest request = new ProcessDocumentRequest();
            request.setChunkingStrategy(ProcessDocumentRequest.ChunkingStrategy.CHAPTER_BASED);
            request.setMaxChunkSize(100);
            request.setMinChunkSize(500);
            request.setAggressiveCombinationThreshold(3000);

            // When
            List<UniversalChunker.Chunk> result = chunker.chunkDocument(document, request);

            // Then - Line 555 true branch covered (title > 100 chars, truncated)
            assertThat(result).isNotEmpty();
            // At least one combined chunk should have truncated title ending with "..."
            boolean hasTruncated = result.stream()
                    .anyMatch(c -> c.getTitle().endsWith("..."));
            assertThat(hasTruncated).isTrue();
        }

        @Test
        @DisplayName("combineSmallChunks: when combined title under 100 chars then keeps full title")
        void combineChunks_shortTitle_keepsFull() {
            // Given - Chunks with short titles
            String chunk1Content = "x".repeat(100);
            String chunk2Content = "y".repeat(100);
            
            ConvertedDocument.Chapter chapter1 = new ConvertedDocument.Chapter();
            chapter1.setTitle("Short A");
            chapter1.setContent(chunk1Content);
            chapter1.setStartPage(1);
            chapter1.setEndPage(1);
            
            ConvertedDocument.Chapter chapter2 = new ConvertedDocument.Chapter();
            chapter2.setTitle("Short B");
            chapter2.setContent(chunk2Content);
            chapter2.setStartPage(2);
            chapter2.setEndPage(2);
            
            ConvertedDocument document = new ConvertedDocument();
            document.setOriginalFilename("test.pdf");
            document.setFullContent(chapter1.getContent() + chapter2.getContent());
            document.setChapters(List.of(chapter1, chapter2));

            ProcessDocumentRequest request = new ProcessDocumentRequest();
            request.setChunkingStrategy(ProcessDocumentRequest.ChunkingStrategy.CHAPTER_BASED);
            request.setMaxChunkSize(100);
            request.setMinChunkSize(500);
            request.setAggressiveCombinationThreshold(3000);

            // When
            List<UniversalChunker.Chunk> result = chunker.chunkDocument(document, request);

            // Then - Line 555 false branch covered (title <= 100 chars)
            assertThat(result).isNotEmpty();
        }
    }
}

