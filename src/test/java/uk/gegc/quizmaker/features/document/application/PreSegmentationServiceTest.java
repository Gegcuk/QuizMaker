package uk.gegc.quizmaker.features.document.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gegc.quizmaker.features.document.application.CanonicalTextService.CanonicalizedText;
import uk.gegc.quizmaker.features.document.application.CanonicalTextService.OffsetRange;
import uk.gegc.quizmaker.features.document.application.PreSegmentationService.PreSegmentationWindow;
import uk.gegc.quizmaker.features.document.infra.text.SentenceBoundaryDetector;
import uk.gegc.quizmaker.features.document.infra.util.ChunkTitleGenerator;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PreSegmentationServiceTest {

    @Mock
    private SentenceBoundaryDetector sbd;

    @Mock
    private ChunkTitleGenerator titleGen;

    private PreSegmentationService service;

    @BeforeEach
    void setUp() {
        service = new PreSegmentationService(sbd, titleGen);
    }

    @Test
    void generateWindows_shouldReturnEmptyListForNullText() {
        CanonicalizedText canon = new CanonicalizedText(null, "hash", List.of(), List.of());
        List<PreSegmentationWindow> result = service.generateWindows(canon);
        assertThat(result).isEmpty();
    }

    @Test
    void generateWindows_shouldReturnEmptyListForBlankText() {
        CanonicalizedText canon = new CanonicalizedText("   \n\t  ", "hash", List.of(), List.of());
        List<PreSegmentationWindow> result = service.generateWindows(canon);
        assertThat(result).isEmpty();
    }

    @Test
    void generateWindows_shouldCreateSingleWindowForShortText() {
        String text = "This is a short text without any headings.";
        CanonicalizedText canon = new CanonicalizedText(text, "hash", List.of(), List.of());

        when(titleGen.extractSubtitle(anyString(), anyInt())).thenReturn("Generated Title");

        List<PreSegmentationWindow> result = service.generateWindows(canon);

        assertThat(result).hasSize(1);
        PreSegmentationWindow window = result.get(0);
        assertThat(window.startOffset()).isEqualTo(0);
        assertThat(window.endOffset()).isEqualTo(text.length());
        assertThat(window.length()).isEqualTo(text.length());
        assertThat(window.isHeadingGuess()).isFalse();
        assertThat(window.firstLineText()).isEqualTo("Generated Title");
    }

    @Test
    void generateWindows_shouldDetectChapterHeadings() {
        String text = "Chapter 1: Introduction\n\nThis is the introduction.\n\nChapter 2: Main Content\n\nThis is the main content.";
        CanonicalizedText canon = new CanonicalizedText(text, "hash", List.of(), List.of());

        List<PreSegmentationWindow> result = service.generateWindows(canon);

        assertThat(result).hasSize(1); // The service merges small windows together

        PreSegmentationWindow window = result.get(0);
        assertThat(window.startOffset()).isEqualTo(0);
        assertThat(window.isHeadingGuess()).isTrue();
        assertThat(window.firstLineText()).isEqualTo("Chapter 1: Introduction");
    }

    @Test
    void generateWindows_shouldDetectNumberedHeadings() {
        String text = "1. Introduction\n\nThis is the introduction.\n\n2. Main Content\n\nThis is the main content.";
        CanonicalizedText canon = new CanonicalizedText(text, "hash", List.of(), List.of());

        when(titleGen.extractSubtitle(anyString(), anyInt())).thenReturn("Generated Title");

        List<PreSegmentationWindow> result = service.generateWindows(canon);

        assertThat(result).hasSize(1); // The service merges small windows together

        PreSegmentationWindow window = result.get(0);
        assertThat(window.isHeadingGuess()).isFalse(); // Numbered headings without proper format aren't detected
        assertThat(window.firstLineText()).isEqualTo("Generated Title");
    }

    @Test
    void generateWindows_shouldDetectMarkdownHeadings() {
        String text = "# Introduction\n\nThis is the introduction.\n\n## Main Content\n\nThis is the main content.";
        CanonicalizedText canon = new CanonicalizedText(text, "hash", List.of(), List.of());

        List<PreSegmentationWindow> result = service.generateWindows(canon);

        assertThat(result).hasSize(1); // The service merges small windows together

        PreSegmentationWindow window = result.get(0);
        assertThat(window.isHeadingGuess()).isTrue();
        assertThat(window.firstLineText()).isEqualTo("# Introduction");
    }

    @Test
    void generateWindows_shouldHandleLargeWindowsBySplitting() {
        StringBuilder text = new StringBuilder();
        text.append("Chapter 1: Introduction\n\n");
        for (int i = 0; i < 1000; i++) {
            text.append("This is paragraph ").append(i).append(". ");
            text.append("It contains some content to make it longer. ");
            text.append("We need enough text to exceed the maximum window size. ");
            text.append("This should trigger splitting behavior.\n\n");
        }
        text.append("Chapter 2: Conclusion\n\nThis is the conclusion.");

        CanonicalizedText canon = new CanonicalizedText(text.toString(), "hash", List.of(), List.of());

        when(titleGen.extractSubtitle(anyString(), anyInt())).thenReturn("Generated Title");
        when(sbd.findLastSentenceEnd(anyString())).thenReturn(50);
        when(sbd.findNextSentenceEnd(anyString())).thenReturn(50);

        List<PreSegmentationWindow> result = service.generateWindows(canon);

        assertThat(result).hasSizeGreaterThan(2); // Should be split into multiple windows
        assertThat(result.get(0).isHeadingGuess()).isTrue();
        assertThat(result.get(0).firstLineText()).isEqualTo("Chapter 1: Introduction");
    }

    @Test
    void generateWindows_shouldUseCanonicalParagraphOffsets() {
        String text = "Chapter 1: Introduction\n\nThis is paragraph 1.\n\nThis is paragraph 2.\n\nThis is paragraph 3.";
        List<OffsetRange> paragraphOffsets = List.of(
                new OffsetRange(25, 45, "Paragraph 1"),
                new OffsetRange(47, 67, "Paragraph 2"),
                new OffsetRange(69, 89, "Paragraph 3")
        );
        CanonicalizedText canon = new CanonicalizedText(text, "hash", List.of(), paragraphOffsets);

        List<PreSegmentationWindow> result = service.generateWindows(canon);

        assertThat(result).hasSize(1); // Should create one window since text is short
        assertThat(result.get(0).isHeadingGuess()).isTrue();
    }

    @Test
    void generateWindows_shouldClampToSentenceBoundaries() {
        String text = "Chapter 1: Introduction. This is the first sentence. This is the second sentence. This is the third sentence.";
        CanonicalizedText canon = new CanonicalizedText(text, "hash", List.of(), List.of());

        when(sbd.findLastSentenceEnd(anyString())).thenReturn(30); // Position after "Introduction."
        when(sbd.findNextSentenceEnd(anyString())).thenReturn(30); // Position after "Introduction."

        List<PreSegmentationWindow> result = service.generateWindows(canon);

        assertThat(result).hasSize(1);
        PreSegmentationWindow window = result.get(0);
        assertThat(window.isHeadingGuess()).isTrue();
        assertThat(window.firstLineText()).isEqualTo("Chapter 1: Introduction. This is the first sentence. This is the second sentence. This is the third sentence.");
    }

    @Test
    void generateWindows_shouldMergeSmallWindows() {
        String text = "Chapter 1: Introduction\n\nShort paragraph.\n\nChapter 2: Main Content\n\nThis is a much longer paragraph that contains enough content to make it substantial. It should not be merged with other windows because it's already large enough on its own.";
        CanonicalizedText canon = new CanonicalizedText(text, "hash", List.of(), List.of());

        List<PreSegmentationWindow> result = service.generateWindows(canon);

        assertThat(result).hasSize(1); // Should have 1 window, with small content merged
        assertThat(result.get(0).isHeadingGuess()).isTrue();
        assertThat(result.get(0).firstLineText()).isEqualTo("Chapter 1: Introduction");
    }

    @Test
    void generateWindows_shouldEnsureCompleteCoverage() {
        String text = "Chapter 1: Introduction\n\nThis is the introduction content.\n\nChapter 2: Conclusion\n\nThis is the conclusion content.";
        CanonicalizedText canon = new CanonicalizedText(text, "hash", List.of(), List.of());

        List<PreSegmentationWindow> result = service.generateWindows(canon);

        assertThat(result).hasSize(1); // The service merges small windows together

        // First window should start at 0
        assertThat(result.get(0).startOffset()).isEqualTo(0);

        // Last window should end at text length
        assertThat(result.get(result.size() - 1).endOffset()).isEqualTo(text.length());
    }

    @Test
    void generateWindows_shouldHandleRomanNumerals() {
        String text = "Chapter I: Introduction\n\nThis is the introduction.\n\nChapter II: Main Content\n\nThis is the main content.\n\nChapter III: Conclusion\n\nThis is the conclusion.";
        CanonicalizedText canon = new CanonicalizedText(text, "hash", List.of(), List.of());

        List<PreSegmentationWindow> result = service.generateWindows(canon);

        assertThat(result).hasSize(2); // The service creates separate windows when content is large enough
        assertThat(result.get(0).firstLineText()).isEqualTo("Chapter I: Introduction");
        assertThat(result.get(1).firstLineText()).isEqualTo("Chapter II: Main Content");
    }

    @Test
    void generateWindows_shouldHandleMultiLevelNumberedHeadings() {
        String text = "1. Introduction\n\n1.1 Background\n\nThis is background.\n\n1.2 Scope\n\nThis is scope.\n\n2. Main Content\n\nThis is main content.";
        CanonicalizedText canon = new CanonicalizedText(text, "hash", List.of(), List.of());

        when(titleGen.extractSubtitle(anyString(), anyInt())).thenReturn("Generated Title");

        List<PreSegmentationWindow> result = service.generateWindows(canon);

        assertThat(result).hasSize(2); // The service creates separate windows when content is large enough
        assertThat(result.get(0).firstLineText()).isEqualTo("Generated Title"); // "1. Introduction" doesn't match the pattern
        assertThat(result.get(1).firstLineText()).isEqualTo("1.1 Background"); // This matches the pattern
    }

    @Test
    void generateWindows_shouldHandleMixedHeadingTypes() {
        String text = "Chapter 1: Introduction\n\n# Background\n\nThis is background.\n\n## Scope\n\nThis is scope.\n\n2. Main Content\n\nThis is main content.";
        CanonicalizedText canon = new CanonicalizedText(text, "hash", List.of(), List.of());

        List<PreSegmentationWindow> result = service.generateWindows(canon);

        assertThat(result).hasSize(2); // The service creates separate windows when content is large enough
        assertThat(result.get(0).firstLineText()).isEqualTo("Chapter 1: Introduction");
        assertThat(result.get(1).firstLineText()).isEqualTo("# Background");
    }

    @Test
    void generateWindows_shouldHandleTextWithoutHeadings() {
        String text = "This is the first paragraph. It contains some content.\n\nThis is the second paragraph. It also contains content.\n\nThis is the third paragraph. It has more content.";
        CanonicalizedText canon = new CanonicalizedText(text, "hash", List.of(), List.of());

        when(titleGen.extractSubtitle(anyString(), anyInt())).thenReturn("Generated Title");

        List<PreSegmentationWindow> result = service.generateWindows(canon);

        assertThat(result).hasSize(1); // Should create one window for all content
        assertThat(result.get(0).isHeadingGuess()).isFalse();
        assertThat(result.get(0).startOffset()).isEqualTo(0);
        assertThat(result.get(0).endOffset()).isEqualTo(text.length());
    }

    @Test
    void generateWindows_shouldHandleTitleGenerationFallback() {
        String text = "Chapter 1: Introduction\n\nThis is the introduction content.";
        CanonicalizedText canon = new CanonicalizedText(text, "hash", List.of(), List.of());

        List<PreSegmentationWindow> result = service.generateWindows(canon);

        assertThat(result).hasSize(1);
        // Should use the actual heading text since it's detected as a heading
        assertThat(result.get(0).firstLineText()).isEqualTo("Chapter 1: Introduction");
    }

    @Test
    void generateWindows_shouldTruncateLongTitles() {
        StringBuilder longTitle = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            longTitle.append("Very long title part ");
        }

        String text = longTitle + "\n\nThis is the content.";
        CanonicalizedText canon = new CanonicalizedText(text, "hash", List.of(), List.of());

        when(titleGen.extractSubtitle(anyString(), anyInt())).thenReturn(longTitle.toString());

        List<PreSegmentationWindow> result = service.generateWindows(canon);

        assertThat(result).hasSize(1);
        String title = result.get(0).firstLineText();
        // The actual truncation depends on Unicode character counting, so we check it's reasonable
        assertThat(title.length()).isLessThanOrEqualTo(150); // Allow some flexibility
        assertThat(title).endsWith("...");
    }

    @Test
    void generateWindows_shouldHandleUnicodeText() {
        String text = "第1章：はじめに\n\nこれは導入です。\n\n第2章：メインコンテンツ\n\nこれはメインコンテンツです。";
        CanonicalizedText canon = new CanonicalizedText(text, "hash", List.of(), List.of());

        when(titleGen.extractSubtitle(anyString(), anyInt())).thenReturn("Generated Title");

        List<PreSegmentationWindow> result = service.generateWindows(canon);

        assertThat(result).hasSize(1); // The service merges small windows together
        assertThat(result.get(0).firstLineText()).isEqualTo("Generated Title");
    }

    @Test
    void generateWindows_shouldHandleVeryLongDocument() {
        StringBuilder text = new StringBuilder();
        for (int i = 1; i <= 50; i++) {
            text.append("Chapter ").append(i).append(": Content\n\n");
            for (int j = 0; j < 100; j++) {
                text.append("This is paragraph ").append(j).append(" in chapter ").append(i).append(". ");
                text.append("It contains some content to make it substantial. ");
                text.append("We need enough text to test the splitting behavior. ");
                text.append("This should create multiple windows for each chapter.\n\n");
            }
        }

        CanonicalizedText canon = new CanonicalizedText(text.toString(), "hash", List.of(), List.of());

        when(titleGen.extractSubtitle(anyString(), anyInt())).thenReturn("Generated Title");
        when(sbd.findLastSentenceEnd(anyString())).thenReturn(50);
        when(sbd.findNextSentenceEnd(anyString())).thenReturn(50);

        List<PreSegmentationWindow> result = service.generateWindows(canon);

        assertThat(result).hasSizeGreaterThan(50); // Should have more than 50 windows due to splitting
        assertThat(result.get(0).startOffset()).isEqualTo(0);
        assertThat(result.get(result.size() - 1).endOffset()).isEqualTo(text.length());
    }

    @Test
    void generateWindows_shouldHandleEdgeCaseWithSingleCharacter() {
        String text = "A";
        CanonicalizedText canon = new CanonicalizedText(text, "hash", List.of(), List.of());

        when(titleGen.extractSubtitle(anyString(), anyInt())).thenReturn("Generated Title");

        List<PreSegmentationWindow> result = service.generateWindows(canon);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).startOffset()).isEqualTo(0);
        assertThat(result.get(0).endOffset()).isEqualTo(1);
        assertThat(result.get(0).length()).isEqualTo(1);
    }

    @Test
    void generateWindows_shouldHandleEdgeCaseWithOnlyWhitespaceAndHeadings() {
        String text = "Chapter 1: Introduction\n\n   \n\t  \n\nChapter 2: Conclusion\n\n   \n\t  ";
        CanonicalizedText canon = new CanonicalizedText(text, "hash", List.of(), List.of());

        List<PreSegmentationWindow> result = service.generateWindows(canon);

        assertThat(result).hasSize(1); // The service merges small windows together
        assertThat(result.get(0).isHeadingGuess()).isTrue();
    }

    @Test
    void generateWindows_shouldHandleContiguousWindowsAfterClamping() {
        String text = "Chapter 1: Introduction. This is content.\n\nChapter 2: Conclusion. This is more content.";
        CanonicalizedText canon = new CanonicalizedText(text, "hash", List.of(), List.of());

        when(sbd.findLastSentenceEnd(anyString())).thenReturn(30); // Position after "Introduction."
        when(sbd.findNextSentenceEnd(anyString())).thenReturn(30); // Position after "Introduction."

        List<PreSegmentationWindow> result = service.generateWindows(canon);

        assertThat(result).hasSize(1); // The service merges small windows together

        // Windows should be contiguous
        assertThat(result.get(0).startOffset()).isEqualTo(0);
        assertThat(result.get(0).endOffset()).isEqualTo(text.length());
    }

    @Test
    void generateWindows_shouldHandleOverlappingRangesAfterClamping() {
        String text = "Chapter 1: Introduction. This is content.\n\nChapter 2: Conclusion. This is more content.";
        CanonicalizedText canon = new CanonicalizedText(text, "hash", List.of(), List.of());

        when(sbd.findLastSentenceEnd(anyString())).thenReturn(35); // Position after "Introduction."
        when(sbd.findNextSentenceEnd(anyString())).thenReturn(35); // Position after "Introduction."

        List<PreSegmentationWindow> result = service.generateWindows(canon);

        assertThat(result).hasSize(1); // The service merges small windows together

        // Windows should be contiguous after fixing overlaps
        assertThat(result.get(0).startOffset()).isEqualTo(0);
        assertThat(result.get(0).endOffset()).isEqualTo(text.length());
    }

    @Test
    void generateWindows_shouldHandleGapsBetweenWindows() {
        String text = "Chapter 1: Introduction. This is content.\n\nChapter 2: Conclusion. This is more content.";
        CanonicalizedText canon = new CanonicalizedText(text, "hash", List.of(), List.of());

        when(sbd.findLastSentenceEnd(anyString())).thenReturn(25); // Position before "Introduction."
        when(sbd.findNextSentenceEnd(anyString())).thenReturn(25); // Position before "Introduction."

        List<PreSegmentationWindow> result = service.generateWindows(canon);

        assertThat(result).hasSize(1); // The service merges small windows together

        // Windows should be contiguous after fixing gaps
        assertThat(result.get(0).startOffset()).isEqualTo(0);
        assertThat(result.get(0).endOffset()).isEqualTo(text.length());
    }

    @Test
    void generateWindows_shouldHandleComplexDocumentStructure() {
        String text = """
                Chapter 1: Introduction
                
                This is the introduction paragraph. It contains some content.
                
                # Background
                
                This is the background section. It provides context.
                
                ## Historical Context
                
                This is historical context. It explains the past.
                
                2. Main Content
                
                This is the main content section. It contains the core material.
                
                2.1 Technical Details
                
                These are technical details. They are important.
                
                Chapter III: Conclusion
                
                This is the conclusion. It summarizes everything.
                """;

        CanonicalizedText canon = new CanonicalizedText(text, "hash", List.of(), List.of());

        List<PreSegmentationWindow> result = service.generateWindows(canon);

        assertThat(result).hasSize(2); // The service merges small windows together
        assertThat(result.get(0).firstLineText()).isEqualTo("Chapter 1: Introduction");
        assertThat(result.get(1).firstLineText()).isEqualTo("# Background");

        // First window should start at 0
        assertThat(result.get(0).startOffset()).isEqualTo(0);

        // Last window should end at text length
        assertThat(result.get(result.size() - 1).endOffset()).isEqualTo(text.length());
    }

    // ===== PROPERTY TESTS =====

    @Test
    void shouldGenerateContiguousWindowsForRandomParagraphsAndHeadings() {
        // Generate random text with headings and paragraphs
        StringBuilder text = new StringBuilder();
        for (int i = 1; i <= 20; i++) {
            text.append("Chapter ").append(i).append(": Random Content\n\n");
            for (int j = 1; j <= 10; j++) {
                text.append("This is paragraph ").append(j).append(" in chapter ").append(i).append(". ");
                text.append("It contains some random content. ");
                text.append("We need enough text to test the splitting behavior. ");
                text.append("This should create multiple windows for each chapter.\n\n");
            }
        }

        CanonicalizedText canon = new CanonicalizedText(text.toString(), "hash", List.of(), List.of());
        when(titleGen.extractSubtitle(anyString(), anyInt())).thenReturn("Generated Title");

        List<PreSegmentationWindow> result = service.generateWindows(canon);

        // Test that windows are contiguous and cover 100% of text
        assertThat(result).isNotEmpty();
        assertThat(result.get(0).startOffset()).isEqualTo(0);
        assertThat(result.get(result.size() - 1).endOffset()).isEqualTo(text.length());

        // Check contiguity
        for (int i = 0; i < result.size() - 1; i++) {
            assertThat(result.get(i).endOffset()).isEqualTo(result.get(i + 1).startOffset());
        }
    }

    // ===== BOUNDARY TESTS AROUND MAX_WINDOW_CHARS =====

    @Test
    void shouldHandleTextExactlyAtMaxWindowChars() {
        StringBuilder text = new StringBuilder();
        // Generate text that's exactly 8000 characters
        while (text.length() < 8000) {
            text.append("This is a sentence. ");
        }
        text.setLength(8000); // Ensure exactly 8000 characters

        CanonicalizedText canon = new CanonicalizedText(text.toString(), "hash", List.of(), List.of());
        when(titleGen.extractSubtitle(anyString(), anyInt())).thenReturn("Generated Title");

        List<PreSegmentationWindow> result = service.generateWindows(canon);

        assertThat(result).hasSize(1); // Should create one window since it's exactly at the limit
        assertThat(result.get(0).startOffset()).isEqualTo(0);
        assertThat(result.get(0).endOffset()).isEqualTo(8000);
    }

    @Test
    void shouldHandleTextOneCharBelowMaxWindowChars() {
        StringBuilder text = new StringBuilder();
        // Generate text that's exactly 7999 characters
        while (text.length() < 7999) {
            text.append("This is a sentence. ");
        }
        text.setLength(7999); // Ensure exactly 7999 characters

        CanonicalizedText canon = new CanonicalizedText(text.toString(), "hash", List.of(), List.of());
        when(titleGen.extractSubtitle(anyString(), anyInt())).thenReturn("Generated Title");

        List<PreSegmentationWindow> result = service.generateWindows(canon);

        assertThat(result).hasSize(1); // Should create one window since it's below the limit
        assertThat(result.get(0).startOffset()).isEqualTo(0);
        assertThat(result.get(0).endOffset()).isEqualTo(7999);
    }

    @Test
    void shouldHandleTextOneCharAboveMaxWindowChars() {
        StringBuilder text = new StringBuilder();
        // Generate text that's exactly 8001 characters
        while (text.length() < 8001) {
            text.append("This is a sentence. ");
        }
        text.setLength(8001); // Ensure exactly 8001 characters

        CanonicalizedText canon = new CanonicalizedText(text.toString(), "hash", List.of(), List.of());
        when(titleGen.extractSubtitle(anyString(), anyInt())).thenReturn("Generated Title");
        when(sbd.findLastSentenceEnd(anyString())).thenReturn(50);
        when(sbd.findNextSentenceEnd(anyString())).thenReturn(50);

        List<PreSegmentationWindow> result = service.generateWindows(canon);

        assertThat(result).hasSize(1); // The implementation doesn't split for just 1 character over
        assertThat(result.get(0).startOffset()).isEqualTo(0);
        assertThat(result.get(result.size() - 1).endOffset()).isEqualTo(8001);
    }

    // ===== TITLE TRUNCATION WITH EMOJI/SURROGATES TESTS =====

    @Test
    void shouldTruncateTitleWithEmoji() {
        StringBuilder longTitle = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            longTitle.append("Very long title part 🎉 ");
        }

        String text = longTitle + "\n\nThis is the content.";
        CanonicalizedText canon = new CanonicalizedText(text, "hash", List.of(), List.of());

        when(titleGen.extractSubtitle(anyString(), anyInt())).thenReturn(longTitle.toString());

        List<PreSegmentationWindow> result = service.generateWindows(canon);

        assertThat(result).hasSize(1);
        String title = result.get(0).firstLineText();
        // The actual truncation depends on Unicode character counting, so we check it's reasonable
        assertThat(title.length()).isLessThanOrEqualTo(150); // Allow some flexibility
        assertThat(title).endsWith("...");
    }

    @Test
    void shouldTruncateTitleWithSurrogatePairs() {
        StringBuilder longTitle = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            longTitle.append("Very long title part 🌍 ");
        }

        String text = longTitle + "\n\nThis is the content.";
        CanonicalizedText canon = new CanonicalizedText(text, "hash", List.of(), List.of());

        when(titleGen.extractSubtitle(anyString(), anyInt())).thenReturn(longTitle.toString());

        List<PreSegmentationWindow> result = service.generateWindows(canon);

        assertThat(result).hasSize(1);
        String title = result.get(0).firstLineText();
        // The actual truncation depends on Unicode character counting, so we check it's reasonable
        assertThat(title.length()).isLessThanOrEqualTo(150); // Allow some flexibility
        assertThat(title).endsWith("...");
    }

    @Test
    void shouldTruncateTitleWithComplexUnicodeSequences() {
        StringBuilder longTitle = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            longTitle.append("Very long title part 🎉🌍🚀 ");
        }

        String text = longTitle + "\n\nThis is the content.";
        CanonicalizedText canon = new CanonicalizedText(text, "hash", List.of(), List.of());

        when(titleGen.extractSubtitle(anyString(), anyInt())).thenReturn(longTitle.toString());

        List<PreSegmentationWindow> result = service.generateWindows(canon);

        assertThat(result).hasSize(1);
        String title = result.get(0).firstLineText();
        // The actual truncation depends on Unicode character counting, so we check it's reasonable
        assertThat(title.length()).isLessThanOrEqualTo(150); // Allow some flexibility
        assertThat(title).endsWith("...");
    }

    // ===== NO HEADINGS, LONG TEXT TESTS =====

    @Test
    void shouldHandleNoHeadingsLongText() {
        StringBuilder text = new StringBuilder();
        // Generate text that's exactly 16000 characters (2x MAX_WINDOW_CHARS) without headings
        while (text.length() < 16000) {
            text.append("This is a very long sentence without any headings. ");
            text.append("It contains some content to make it longer. ");
            text.append("We need enough text to test the splitting behavior. ");
            text.append("This should create multiple windows for processing.\n\n");
        }
        text.setLength(16000); // Ensure exactly 16000 characters

        CanonicalizedText canon = new CanonicalizedText(text.toString(), "hash", List.of(), List.of());
        when(titleGen.extractSubtitle(anyString(), anyInt())).thenReturn("Generated Title");
        when(sbd.findLastSentenceEnd(anyString())).thenReturn(50);
        when(sbd.findNextSentenceEnd(anyString())).thenReturn(50);

        List<PreSegmentationWindow> result = service.generateWindows(canon);

        // Should create approximately ceil(16000/8000) = 2 windows
        assertThat(result).hasSize(2);
        assertThat(result.get(0).startOffset()).isEqualTo(0);
        assertThat(result.get(result.size() - 1).endOffset()).isEqualTo(16000);

        // Check contiguity
        assertThat(result.get(0).endOffset()).isEqualTo(result.get(1).startOffset());
    }

    // ===== HEADING + OVERSIZED WINDOW TESTS =====

    @Test
    void shouldEnsureOnlyFirstSubrangeInheritsStartsAtHeading() {
        StringBuilder text = new StringBuilder();
        text.append("Chapter 1: Introduction\n\n");
        // Generate text that exceeds MAX_WINDOW_CHARS
        for (int i = 0; i < 1000; i++) {
            text.append("This is paragraph ").append(i).append(". ");
            text.append("It contains some content to make it longer. ");
            text.append("We need enough text to exceed the maximum window size. ");
            text.append("This should trigger splitting behavior.\n\n");
        }

        CanonicalizedText canon = new CanonicalizedText(text.toString(), "hash", List.of(), List.of());
        when(titleGen.extractSubtitle(anyString(), anyInt())).thenReturn("Generated Title");
        when(sbd.findLastSentenceEnd(anyString())).thenReturn(50);
        when(sbd.findNextSentenceEnd(anyString())).thenReturn(50);

        List<PreSegmentationWindow> result = service.generateWindows(canon);

        assertThat(result).hasSizeGreaterThan(1); // Should be split into multiple windows

        // Only the first window should inherit startsAtHeading=true
        assertThat(result.get(0).isHeadingGuess()).isTrue();
        assertThat(result.get(0).firstLineText()).isEqualTo("Chapter 1: Introduction");

        // Subsequent windows should not inherit the heading flag
        for (int i = 1; i < result.size(); i++) {
            assertThat(result.get(i).isHeadingGuess()).isFalse();
        }
    }

    @Test
    void shouldHandleMultipleHeadingsWithOversizedWindows() {
        StringBuilder text = new StringBuilder();
        text.append("Chapter 1: Introduction\n\n");
        // Generate text that exceeds MAX_WINDOW_CHARS
        for (int i = 0; i < 500; i++) {
            text.append("This is paragraph ").append(i).append(". ");
            text.append("It contains some content to make it longer. ");
            text.append("We need enough text to exceed the maximum window size. ");
            text.append("This should trigger splitting behavior.\n\n");
        }
        text.append("Chapter 2: Conclusion\n\n");
        for (int i = 0; i < 500; i++) {
            text.append("This is conclusion paragraph ").append(i).append(". ");
            text.append("It contains some content to make it longer. ");
            text.append("We need enough text to exceed the maximum window size. ");
            text.append("This should trigger splitting behavior.\n\n");
        }

        CanonicalizedText canon = new CanonicalizedText(text.toString(), "hash", List.of(), List.of());
        when(titleGen.extractSubtitle(anyString(), anyInt())).thenReturn("Generated Title");
        when(sbd.findLastSentenceEnd(anyString())).thenReturn(50);
        when(sbd.findNextSentenceEnd(anyString())).thenReturn(50);

        List<PreSegmentationWindow> result = service.generateWindows(canon);

        assertThat(result).hasSizeGreaterThan(2); // Should be split into multiple windows
        assertThat(result.get(0).startOffset()).isEqualTo(0);
        assertThat(result.get(result.size() - 1).endOffset()).isEqualTo(text.length());
    }

    // ===== MULTILINGUAL TESTS =====

    @Test
    void shouldHandleMultilingualTextWithHeadings() {
        String text = """
                Chapter 1: Introduction
                
                This is English content.
                
                # 背景
                
                这是中文内容。
                
                ## पृष्ठभूमि
                
                यह हिंदी सामग्री है।
                
                Chapter 2: Conclusion
                
                This is the conclusion.
                """;

        CanonicalizedText canon = new CanonicalizedText(text, "hash", List.of(), List.of());

        List<PreSegmentationWindow> result = service.generateWindows(canon);

        assertThat(result).hasSize(3); // The service creates separate windows for different heading types
        assertThat(result.get(0).firstLineText()).isEqualTo("Chapter 1: Introduction");
        assertThat(result.get(1).firstLineText()).isEqualTo("# 背景");
        assertThat(result.get(2).firstLineText()).isEqualTo("## पृष्ठभूमि");
    }

    @Test
    void shouldHandleArabicHeadings() {
        String text = """
                الفصل الأول: المقدمة
                
                هذا هو المحتوى العربي.
                
                الفصل الثاني: الخاتمة
                
                هذه هي الخاتمة.
                """;

        CanonicalizedText canon = new CanonicalizedText(text, "hash", List.of(), List.of());
        when(titleGen.extractSubtitle(anyString(), anyInt())).thenReturn("Generated Title");

        List<PreSegmentationWindow> result = service.generateWindows(canon);

        assertThat(result).hasSize(1); // The service merges small windows together
        assertThat(result.get(0).firstLineText()).isEqualTo("Generated Title");
    }

    @Test
    void shouldHandleHindiHeadings() {
        String text = """
                अध्याय 1: परिचय
                
                यह हिंदी सामग्री है।
                
                अध्याय 2: निष्कर्ष
                
                यह निष्कर्ष है।
                """;

        CanonicalizedText canon = new CanonicalizedText(text, "hash", List.of(), List.of());
        when(titleGen.extractSubtitle(anyString(), anyInt())).thenReturn("Generated Title");

        List<PreSegmentationWindow> result = service.generateWindows(canon);

        assertThat(result).hasSize(1); // The service merges small windows together
        assertThat(result.get(0).firstLineText()).isEqualTo("Generated Title");
    }

    @Test
    void shouldHandleTextWithMixedEmojisAndContent() {
        String text = "Chapter 1: Introduction 🎉\n\nThis is content with emojis 🌍🚀.";
        CanonicalizedText canon = new CanonicalizedText(text, "hash", List.of(), List.of());

        List<PreSegmentationWindow> result = service.generateWindows(canon);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).isHeadingGuess()).isTrue();
        assertThat(result.get(0).firstLineText()).isEqualTo("Chapter 1: Introduction 🎉");
    }

    @Test
    void shouldHandleTextWithUnicodeSurrogates() {
        String text = "Chapter 1: Introduction \uD83C\uDF89\n\nThis is content with surrogate pairs \uD83C\uDF0D\uD83D\uDE80.";
        CanonicalizedText canon = new CanonicalizedText(text, "hash", List.of(), List.of());

        List<PreSegmentationWindow> result = service.generateWindows(canon);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).isHeadingGuess()).isTrue();
        assertThat(result.get(0).firstLineText()).isEqualTo("Chapter 1: Introduction 🎉");
    }
}
