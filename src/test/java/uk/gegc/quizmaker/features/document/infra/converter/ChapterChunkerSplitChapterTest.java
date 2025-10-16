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

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.lenient;

/**
 * Comprehensive tests for ChapterChunker.splitChapterRespectingBoundaries method.
 * Covers all branches to improve coverage from 0% to target.
 */
@ExtendWith(MockitoExtension.class)
@Execution(ExecutionMode.CONCURRENT)
@DisplayName("ChapterChunker SplitChapterRespectingBoundaries Tests")
class ChapterChunkerSplitChapterTest {

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
    @DisplayName("Null/Empty Chapter Content Tests")
    class NullEmptyContentTests {

        @Test
        @DisplayName("splitChapterRespectingBoundaries: when chapter content is null then checks sections")
        void splitChapter_nullContent_checksSections() {
            // Given - Chapter with null content
            ConvertedDocument.Chapter chapter = new ConvertedDocument.Chapter();
            chapter.setTitle("Chapter with Null Content");
            chapter.setContent(null); // Null content - line 208
            chapter.setStartPage(1);
            chapter.setEndPage(2);
            chapter.setSections(new ArrayList<>()); // Empty sections
            
            ConvertedDocument document = new ConvertedDocument();
            document.setOriginalFilename("test.pdf");
            document.setFullContent(""); // Empty full content too
            document.setChapters(List.of(chapter)); // Has chapters
            
            ProcessDocumentRequest request = createDefaultRequest();

            // When
            List<UniversalChunker.Chunk> result = chunker.chunkDocument(document, request);

            // Then - Lines 208-221 covered (null content path, no sections)
            assertThat(result).isEmpty(); // No content, no sections -> empty
        }

        @Test
        @DisplayName("splitChapterRespectingBoundaries: when chapter content is empty then checks sections")
        void splitChapter_emptyContent_checksSections() {
            // Given - Chapter with empty content
            ConvertedDocument.Chapter chapter = new ConvertedDocument.Chapter();
            chapter.setTitle("Chapter with Empty Content");
            chapter.setContent("   "); // Empty (whitespace only) - line 208
            chapter.setStartPage(1);
            chapter.setEndPage(2);
            chapter.setSections(new ArrayList<>());
            
            ConvertedDocument document = new ConvertedDocument();
            document.setOriginalFilename("test.pdf");
            document.setFullContent("   ");
            document.setChapters(List.of(chapter));

            ProcessDocumentRequest request = createDefaultRequest();

            // When
            List<UniversalChunker.Chunk> result = chunker.chunkDocument(document, request);

            // Then - Lines 208-221 covered (empty content path)
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("splitChapterRespectingBoundaries: when chapter has no content but has sections then processes sections")
        void splitChapter_noContentWithSections_processesSections() {
            // Given - Chapter with no content but with sections
            ConvertedDocument.Section section1 = new ConvertedDocument.Section();
            section1.setTitle("Section 1");
            section1.setContent("This is section 1 content. It has some text.");
            section1.setStartPage(1);
            section1.setEndPage(1);
            
            ConvertedDocument.Section section2 = new ConvertedDocument.Section();
            section2.setTitle("Section 2");
            section2.setContent("This is section 2 content. It also has text.");
            section2.setStartPage(2);
            section2.setEndPage(2);
            
            ConvertedDocument.Chapter chapter = new ConvertedDocument.Chapter();
            chapter.setTitle("Chapter with Sections");
            chapter.setContent(null); // No content
            chapter.setSections(List.of(section1, section2)); // Has sections - line 212
            chapter.setStartPage(1);
            chapter.setEndPage(2);
            
            ConvertedDocument document = new ConvertedDocument();
            document.setOriginalFilename("test.pdf");
            document.setFullContent("Combined content");
            document.setChapters(List.of(chapter));

            ProcessDocumentRequest request = createDefaultRequest();

            // When
            List<UniversalChunker.Chunk> result = chunker.chunkDocument(document, request);

            // Then - Lines 212-218 covered (has sections path)
            assertThat(result).isNotEmpty(); // Should create chunks from sections
        }
    }

    @Nested
    @DisplayName("Chapter Fits in Single Chunk Tests")
    class SingleChunkTests {

        @Test
        @DisplayName("splitChapterRespectingBoundaries: when chapter fits in maxSize then creates single chunk")
        void splitChapter_fitsInMaxSize_createsSingleChunk() {
            // Given - Small chapter that fits in one chunk
            ConvertedDocument.Chapter chapter = new ConvertedDocument.Chapter();
            chapter.setTitle("Small Chapter");
            chapter.setContent("This is a small chapter that fits easily within the maximum chunk size limit.");
            chapter.setStartPage(1);
            chapter.setEndPage(1);
            chapter.setSections(new ArrayList<>());
            
            ConvertedDocument document = new ConvertedDocument();
            document.setOriginalFilename("test.pdf");
            document.setFullContent(chapter.getContent());
            document.setChapters(List.of(chapter));

            ProcessDocumentRequest request = createDefaultRequest();
            request.setMaxChunkSize(10000); // Large enough

            // When
            List<UniversalChunker.Chunk> result = chunker.chunkDocument(document, request);

            // Then - Lines 228-239 covered (single chunk path)
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getContent()).isEqualTo(chapter.getContent());
            assertThat(result.get(0).getTitle()).contains("Small Chapter");
        }

        @Test
        @DisplayName("splitChapterRespectingBoundaries: when chapter exactly equals maxSize then creates single chunk")
        void splitChapter_exactlyMaxSize_createsSingleChunk() {
            // Given - Chapter exactly at maxSize boundary
            String content = "x".repeat(5000); // Exactly maxSize
            
            ConvertedDocument.Chapter chapter = new ConvertedDocument.Chapter();
            chapter.setTitle("Exact Size Chapter");
            chapter.setContent(content);
            chapter.setStartPage(1);
            chapter.setEndPage(5);
            chapter.setSections(new ArrayList<>());
            
            ConvertedDocument document = new ConvertedDocument();
            document.setOriginalFilename("test.pdf");
            document.setFullContent(content);
            document.setChapters(List.of(chapter));

            ProcessDocumentRequest request = createDefaultRequest();
            request.setMaxChunkSize(5000); // Exactly the content length

            // When
            List<UniversalChunker.Chunk> result = chunker.chunkDocument(document, request);

            // Then - Line 228 boundary condition (length == maxSize)
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getContent()).hasSize(5000);
        }
    }

    @Nested
    @DisplayName("Chapter Needs Splitting Tests")
    class ChapterNeedsSplittingTests {

        @Test
        @DisplayName("splitChapterRespectingBoundaries: when chapter exceeds maxSize and has sections then splits by sections")
        void splitChapter_exceedsMaxSizeHasSections_splitsBySections() {
            // Given - Large chapter with sections that needs splitting
            ConvertedDocument.Section section1 = new ConvertedDocument.Section();
            section1.setTitle("Section 1");
            section1.setContent("This is section 1 with substantial content to process.");
            section1.setStartPage(1);
            section1.setEndPage(1);
            
            ConvertedDocument.Section section2 = new ConvertedDocument.Section();
            section2.setTitle("Section 2");
            section2.setContent("This is section 2 with even more substantial content.");
            section2.setStartPage(2);
            section2.setEndPage(2);
            
            // Create a large content string that exceeds maxSize
            StringBuilder largeContent = new StringBuilder();
            for (int i = 0; i < 200; i++) {
                largeContent.append("This is sentence ").append(i).append(" with content. ");
            }
            
            ConvertedDocument.Chapter chapter = new ConvertedDocument.Chapter();
            chapter.setTitle("Large Chapter with Sections");
            chapter.setContent(largeContent.toString()); // Large content
            chapter.setStartPage(1);
            chapter.setEndPage(10);
            chapter.setSections(List.of(section1, section2)); // Has sections - line 243
            
            ConvertedDocument document = new ConvertedDocument();
            document.setOriginalFilename("test.pdf");
            document.setFullContent(largeContent.toString());
            document.setChapters(List.of(chapter));

            ProcessDocumentRequest request = createDefaultRequest();
            request.setMaxChunkSize(1000); // Small size to force splitting

            // When
            List<UniversalChunker.Chunk> result = chunker.chunkDocument(document, request);

            // Then - Lines 243-248 covered (chapter too big, has sections, splits by sections)
            assertThat(result.size()).isGreaterThan(0);
        }

        @Test
        @DisplayName("splitChapterRespectingBoundaries: when chapter exceeds maxSize and has no sections then splits by size")
        void splitChapter_exceedsMaxSizeNoSections_splitsBySize() {
            // Given - Large chapter with NO sections that needs splitting
            StringBuilder largeContent = new StringBuilder();
            for (int i = 0; i < 200; i++) {
                largeContent.append("This is sentence ").append(i)
                        .append(" with some content to make it longer. ");
            }
            
            ConvertedDocument.Chapter chapter = new ConvertedDocument.Chapter();
            chapter.setTitle("Large Chapter No Sections");
            chapter.setContent(largeContent.toString());
            chapter.setStartPage(1);
            chapter.setEndPage(10);
            chapter.setSections(new ArrayList<>()); // NO sections - line 249
            
            ConvertedDocument document = new ConvertedDocument();
            document.setOriginalFilename("test.pdf");
            document.setFullContent(largeContent.toString());
            document.setChapters(List.of(chapter));

            ProcessDocumentRequest request = createDefaultRequest();
            request.setMaxChunkSize(1000); // Small size to force splitting

            // When
            List<UniversalChunker.Chunk> result = chunker.chunkDocument(document, request);

            // Then - Lines 249-254 covered (chapter too big, no sections, splits by size)
            assertThat(result.size()).isGreaterThan(1);
        }
    }

    @Nested
    @DisplayName("Mixed Content Tests")
    class MixedContentTests {

        @Test
        @DisplayName("splitChapterRespectingBoundaries: when chapter has both content and sections then uses content")
        void splitChapter_hasContentAndSections_usesContent() {
            // Given - Chapter with both content and sections
            ConvertedDocument.Section section = new ConvertedDocument.Section();
            section.setTitle("Section");
            section.setContent("Section content");
            
            ConvertedDocument.Chapter chapter = new ConvertedDocument.Chapter();
            chapter.setTitle("Mixed Chapter");
            chapter.setContent("Chapter level content that should be used.");
            chapter.setSections(List.of(section)); // Has sections but should use content
            chapter.setStartPage(1);
            chapter.setEndPage(2);
            
            ConvertedDocument document = new ConvertedDocument();
            document.setOriginalFilename("test.pdf");
            document.setFullContent(chapter.getContent());
            document.setChapters(List.of(chapter));

            ProcessDocumentRequest request = createDefaultRequest();

            // When
            List<UniversalChunker.Chunk> result = chunker.chunkDocument(document, request);

            // Then - Should process chapter content, not sections (line 208 == false)
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getContent()).isEqualTo("Chapter level content that should be used.");
        }
    }

    // Helper methods

    private ProcessDocumentRequest createDefaultRequest() {
        ProcessDocumentRequest request = new ProcessDocumentRequest();
        request.setChunkingStrategy(ProcessDocumentRequest.ChunkingStrategy.CHAPTER_BASED);
        request.setMaxChunkSize(10000);
        request.setMinChunkSize(500);
        request.setAggressiveCombinationThreshold(2000);
        return request;
    }

    private ConvertedDocument createMinimalDocument() {
        ConvertedDocument document = new ConvertedDocument();
        document.setOriginalFilename("test.pdf");
        document.setFullContent("");
        document.setChapters(new ArrayList<>());
        return document;
    }
}

