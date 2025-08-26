package uk.gegc.quizmaker.features.document.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import uk.gegc.quizmaker.features.document.application.CanonicalTextService.CanonicalizedText;
import uk.gegc.quizmaker.features.document.application.CanonicalTextService.OffsetRange;
import uk.gegc.quizmaker.features.document.infra.text.SentenceBoundaryDetector;
import uk.gegc.quizmaker.features.document.infra.util.ChunkTitleGenerator;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Service for heuristic pre-segmentation of documents.
 * <p>
 * This service produces candidate anchors/windows so the LLM doesn't see raw megatext.
 * It splits documents into coarse blocks (chapter-like headings, paragraphs, page headers)
 * with offsets for further processing.
 * <p>
 * Implementation of Day 3 — Heuristic Pre-segmentation (Cheap Anchors) from the
 * chunk processing improvement plan.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PreSegmentationService {

    // Headings like: "Chapter 1", "Section 2.1", "Part III", plus localized words
    private static final Pattern HEADING_PATTERN = Pattern.compile(
            "(?m)^\\s*(?:chapter|section|part|book|volume|unit)\\s+(?:[\\p{Nd}]+|[IVXLCDMivxlcdm]+)(?:[\\s:.-].*)?$",
            Pattern.CASE_INSENSITIVE
    );
    // Numbered headings like "1. Introduction", "2.1 Background" - more language-friendly
    private static final Pattern NUMBERED_HEADING_PATTERN = Pattern.compile(
            "(?m)^\\s*\\p{Nd}+(?:\\.\\p{Nd}+)*\\s+\\p{L}[^\\n]{0,100}$"
    );
    // Markdown headings
    private static final Pattern MD_HEADING_PATTERN = Pattern.compile("(?m)^#{1,6}\\s+.+$");
    // Configurable knobs - TODO: move to application.yml
    private static final int MAX_WINDOW_CHARS = 8000;  // ~2k tokens (language dependent)
    private static final int MIN_WINDOW_CHARS = 600;   // avoid tiny slivers
    private static final int CLAMP_LOOKAROUND = 256;   // sentence clamp radius
    private static final int MAX_TITLE_LENGTH = 120;   // maximum title length
    private final SentenceBoundaryDetector sbd;
    private final ChunkTitleGenerator titleGen;

    private static void collect(java.util.Set<Integer> set, Pattern p, String t) {
        var m = p.matcher(t);
        while (m.find()) set.add(m.start());
    }

    private static String firstNonEmptyLine(String t, int from, int to) {
        int i = from;
        while (i < to) {
            int nl = t.indexOf('\n', i);
            int end = (nl == -1 || nl > to) ? to : nl;
            String line = t.substring(i, end).trim();
            if (!line.isBlank()) return line;
            if (nl == -1 || nl >= to) break;
            i = nl + 1;
        }
        return "";
    }

    private static boolean looksLikeHeading(String s) {
        return HEADING_PATTERN.matcher(s).matches()
                || NUMBERED_HEADING_PATTERN.matcher(s).matches()
                || MD_HEADING_PATTERN.matcher(s).matches();
    }

    /**
     * Generate pre-segmentation windows for the given canonical text.
     *
     * @param canon the canonical text to segment
     * @return list of pre-segmentation windows with offsets and metadata
     */
    public List<PreSegmentationWindow> generateWindows(CanonicalizedText canon) {
        final String text = canon.getText();
        if (text == null || text.isBlank()) return List.of();

        log.info("Generating pre-segmentation windows for text of length: {}", text.length());

        // 1) Collect heading starts (without 0/len)
        var headingStarts = new java.util.HashSet<Integer>();
        collect(headingStarts, HEADING_PATTERN, text);
        collect(headingStarts, NUMBERED_HEADING_PATTERN, text);
        collect(headingStarts, MD_HEADING_PATTERN, text);

        // 2) Build cuts (include 0 and len)
        var cuts = new java.util.TreeSet<Integer>();
        cuts.add(0);
        cuts.addAll(headingStarts);
        cuts.add(text.length());

        // 3) Build coarse heading windows with correct flag
        var coarse = new ArrayList<Range>();
        Integer prev = null;
        for (Integer cur : cuts) {
            if (prev == null) {
                prev = cur;
                continue;
            }
            if (cur > prev) coarse.add(new Range(prev, cur, headingStarts.contains(prev)));
            prev = cur;
        }

        // 4) For any coarse window > MAX_WINDOW_CHARS, split inside by paragraph/sentences
        var finalRanges = new ArrayList<Range>();
        for (Range r : coarse) {
            if (r.len() <= MAX_WINDOW_CHARS) {
                finalRanges.add(r);
            } else {
                splitRange(finalRanges, r, text, canon);
            }
        }

        // 5) Clamp to nearest sentence boundaries
        var clamped = new ArrayList<Range>();
        for (Range r : finalRanges) {
            int s = clampStart(text, r.start, CLAMP_LOOKAROUND);
            int e = clampEnd(text, r.end, CLAMP_LOOKAROUND);
            if (e <= s) {
                s = r.start;
                e = r.end;
            } // fallback
            clamped.add(new Range(s, e, r.startsAtHeading));
        }

        // 6) Ensure contiguity after clamping
        var contiguous = ensureContiguity(clamped);

        // 7) Merge small windows
        var merged = mergeSmall(contiguous, text.length());

        // 8) Ensure complete coverage
        ensureCompleteCoverage(merged, text.length());

        // 9) Materialize DTOs with titles
        var out = new ArrayList<PreSegmentationWindow>();
        for (Range r : merged) {
            String snippet = firstNonEmptyLine(text, r.start, Math.min(text.length(), r.start + 200));
            boolean isHeadingGuess = r.startsAtHeading && !snippet.isBlank() && looksLikeHeading(snippet);
            String title = isHeadingGuess ? snippet : generateTitle(text.substring(r.start, r.end));
            out.add(new PreSegmentationWindow(r.start, r.end, title, isHeadingGuess, r.end - r.start));
        }

        // Validation (ordered & contiguous)
        for (int i = 0; i + 1 < out.size(); i++) {
            if (out.get(i).endOffset() != out.get(i + 1).startOffset()) {
                log.warn("Gap between windows {} and {}", i, i + 1);
            }
        }

        log.info("Generated {} pre-segmentation windows", out.size());
        return out;
    }

    private void splitRange(List<Range> out, Range r, String t, CanonicalizedText canon) {
        // Prefer paragraph boundaries first, then sentences
        int start = r.start;
        while (start < r.end) {
            int target = Math.min(r.end, start + MAX_WINDOW_CHARS);
            int split = findParaBreakNear(t, start, target, 400, canon);
            if (split == -1) split = findSentenceBreakNear(t, start, target, 400);
            if (split == -1) split = target;

            out.add(new Range(start, split, start == r.start && r.startsAtHeading));
            start = split;
        }
    }

    private int findParaBreakNear(String t, int start, int target, int wiggle, CanonicalizedText canon) {
        // First try to use canonical paragraph offsets if available
        if (canon.getParagraphOffsets() != null && !canon.getParagraphOffsets().isEmpty()) {
            for (OffsetRange para : canon.getParagraphOffsets()) {
                if (para.getStartOffset() > start && para.getStartOffset() <= target + wiggle) {
                    return para.getStartOffset();
                }
            }
        }

        // Fallback to scanning for paragraph breaks
        int right = t.indexOf("\n\n", target);
        if (right != -1 && right - target <= wiggle) return right;
        int left = t.lastIndexOf("\n\n", target);
        if (left != -1 && target - left <= wiggle && left > start) return left;
        return -1;
    }

    private int findSentenceBreakNear(String t, int start, int target, int wiggle) {
        // Ask SBD for the nearest sentence end around target
        int left = Math.max(start, target - wiggle);
        int right = Math.min(t.length(), target + wiggle);
        int rel = sbd.findLastSentenceEnd(t.substring(left, right));
        return rel >= 0 ? left + rel : -1;   // only add left when found
    }

    private int clampStart(String t, int pos, int r) {
        int left = Math.max(0, pos - r);
        int right = Math.min(t.length(), pos + r);
        int b = sbd.findLastSentenceEnd(t.substring(left, right)) + left; // adjust for substring offset
        return b == left - 1 ? pos : b; // -1 means no sentence end found
    }

    private int clampEnd(String t, int pos, int r) {
        int left = Math.max(0, pos - r);
        int right = Math.min(t.length(), pos + r);
        int rel = sbd.findNextSentenceEnd(t.substring(left, right)); // -1 if none
        if (rel >= 0) return left + rel;
        // fallback: last sentence end but never before pos if we can avoid gaps
        int relLast = sbd.findLastSentenceEnd(t.substring(left, right));
        int candidate = (relLast >= 0) ? left + relLast : pos;
        return Math.max(candidate, pos); // don't shrink past pos
    }

    private List<Range> ensureContiguity(List<Range> ranges) {
        if (ranges.isEmpty()) return ranges;

        var out = new ArrayList<Range>();
        for (int i = 0; i < ranges.size(); i++) {
            Range current = ranges.get(i);
            if (i > 0) {
                Range prev = out.get(out.size() - 1);

                // Handle overlaps: if current starts before or at previous end, adjust it
                if (current.start <= prev.end) {
                    // Skip this range if it's completely contained within the previous one
                    if (current.end <= prev.end) {
                        continue;
                    }
                    // Otherwise, adjust the start to be after the previous end
                    current = new Range(prev.end, current.end, current.startsAtHeading);
                } else {
                    // Handle small gaps (≤ 2 characters)
                    int delta = current.start - prev.end;
                    if (delta <= 2) {
                        current = new Range(prev.end, current.end, current.startsAtHeading);
                    }
                }
            }
            out.add(current);
        }
        return out;
    }

    private List<Range> mergeSmall(List<Range> in, int n) {
        var out = new ArrayList<Range>();
        for (Range r : in) {
            if (!out.isEmpty() && r.len() < MIN_WINDOW_CHARS && !r.startsAtHeading) {
                // merge into previous
                Range prev = out.remove(out.size() - 1);
                out.add(new Range(prev.start, r.end, prev.startsAtHeading));
            } else {
                out.add(r);
            }
        }
        // ensure last window is not tiny; if it is, merge into previous
        if (out.size() >= 2) {
            Range last = out.get(out.size() - 1);
            if (last.end - last.start < MIN_WINDOW_CHARS) {
                Range prev = out.remove(out.size() - 2);
                out.set(out.size() - 1, new Range(prev.start, last.end, prev.startsAtHeading));
            }
        }
        return out;
    }

    private void ensureCompleteCoverage(List<Range> merged, int textLength) {
        if (merged.isEmpty()) return;

        // Force first start = 0
        if (merged.get(0).start > 0) {
            Range first = merged.get(0);
            merged.set(0, new Range(0, first.end, first.startsAtHeading));
        }

        // Force last end = text.length()
        Range last = merged.get(merged.size() - 1);
        if (last.end < textLength) {
            merged.set(merged.size() - 1, new Range(last.start, textLength, last.startsAtHeading));
        }
    }

    private String generateTitle(String windowText) {
        String title = titleGen.extractSubtitle(windowText, 100);
        if (title == null || title.isBlank()) {
            // fallback to first non-empty line
            title = firstNonEmptyLine(windowText, 0, Math.min(windowText.length(), 200));
        }

        // Truncate title to reasonable length with proper code point counting
        if (title != null) {
            int cp = title.codePointCount(0, title.length());
            if (cp > MAX_TITLE_LENGTH) {
                int end = title.offsetByCodePoints(0, MAX_TITLE_LENGTH);
                title = title.substring(0, end) + "...";
            }
        }

        return title != null ? title : "Document Section";
    }

    private record Range(int start, int end, boolean startsAtHeading) {
        int len() {
            return end - start;
        }
    }

    /**
     * Pre-segmentation window with metadata.
     */
    public record PreSegmentationWindow(
            int startOffset,
            int endOffset,
            String firstLineText,
            boolean isHeadingGuess,
            int length
    ) {
        /**
         * Get the window text from the full canonical text.
         */
        public String getWindowText(String canonicalText) {
            return canonicalText.substring(startOffset, endOffset);
        }

        /**
         * Check if this window is small enough for direct processing.
         */
        public boolean isSmallWindow(int maxSize) {
            return length <= maxSize;
        }
    }
}
