package uk.gegc.quizmaker.features.document.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import uk.gegc.quizmaker.features.document.api.dto.DocumentOutlineDto;
import uk.gegc.quizmaker.features.document.api.dto.OutlineNodeDto;

import java.util.*;

/**
 * Day 6 implementation: Long Docs, Hierarchical Passes & Fallbacks.
 *
 * Strategy:
 * - Pass 1: Outline on overlapped slices, stitch top-level nodes (PART/CHAPTER) by title/anchor similarity.
 * - Pass 2: For each top-level node, extract inner sections/subsections within its span. Paragraphs by heuristics first.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class HierarchicalStructureService {

    private final DocumentStructureProperties props;
    private final OutlineExtractorService outlineExtractor;

    /**
     * Build a hierarchical outline suitable for alignment for long documents.
     */
    public DocumentOutlineDto buildHierarchicalOutline(CanonicalTextService.CanonicalizedText canon) {
        String text = canon.getText();
        if (text == null || text.isBlank()) return new DocumentOutlineDto(List.of());

        // Pass 1: slice + stitch top-level (PART/CHAPTER)
        List<Slice> slices = buildOverlappedSlices(text.length());
        List<OutlineNodeDto> ordered = stitchTopLevel(slices, text);

        // Pass 2: per top-level chapter, extract sections/subsections inside range
        List<OutlineNodeDto> enriched = new ArrayList<>();
        for (int i = 0; i < ordered.size(); i++) {
            OutlineNodeDto tl = ordered.get(i);
            int start = firstIndex(text, tl);
            if (start == Integer.MAX_VALUE) {
                // Fallback to anchors, or skip node
                Span span = guessSpanFromAnchors(tl, text);
                if (span.start >= text.length()) continue; // skip safely
                var inner = extractInnerSections(text, span.start, span.end);
                if (props.isParagraphHeuristicsEnabled()) {
                    inner = addHeuristicParagraphs(text, inner, span.start, span.end);
                }
                enriched.add(new OutlineNodeDto(
                        tl.type(),
                        tl.title(),
                        tl.startAnchor(),
                        tl.endAnchor(),
                        inner
                ));
                continue;
            }
            int end = (i + 1 < ordered.size()) ? firstIndex(text, ordered.get(i + 1)) : text.length();
            if (end == Integer.MAX_VALUE) end = text.length();
            if (end <= start) end = Math.min(text.length(), start + props.getPass1SliceSizeChars());
            Span span = new Span(start, end);
            var inner = extractInnerSections(text, span.start, span.end);
            if (props.isParagraphHeuristicsEnabled()) {
                inner = addHeuristicParagraphs(text, inner, span.start, span.end);
            }
            enriched.add(new OutlineNodeDto(
                    tl.type(),
                    tl.title(),
                    tl.startAnchor(),
                    tl.endAnchor(),
                    inner
            ));
        }

        return new DocumentOutlineDto(enriched);
    }

    private record Span(int start, int end) {}

    private Span guessSpanFromAnchors(OutlineNodeDto node, String text) {
        int start = indexOfIgnoreCase(text, safe(node.startAnchor()));
        if (start < 0) start = 0;
        int end = indexOfIgnoreCase(text, safe(node.endAnchor()), Math.max(0, start));
        if (end < 0 || end <= start) end = Math.min(text.length(), start + props.getPass1SliceSizeChars());
        return new Span(start, end);
    }

    private String safe(String s) { return s == null ? "" : s; }

    private List<Slice> buildOverlappedSlices(int totalLen) {
        int size = props.getPass1SliceSizeChars();
        int overlap = Math.max(0, Math.min(100, props.getPass1OverlapPercent()));
        int overlapChars = size * overlap / 100;
        List<Slice> out = new ArrayList<>();
        int start = 0;
        while (start < totalLen) {
            int end = Math.min(totalLen, start + size);
            out.add(new Slice(start, end));
            if (end == totalLen) break;
            start = end - overlapChars;
            if (start < 0) start = 0;
            if (start >= end) break; // safety
        }
        log.info("Built {} overlapped slices (size={}, overlap%={})", out.size(), size, overlap);
        return out;
    }

    private record Slice(int start, int end) {}

    private List<OutlineNodeDto> stitchTopLevel(List<Slice> slices, String text) {
        List<OutlineNodeDto> all = new ArrayList<>();
        for (Slice s : slices) {
            String sliceText = text.substring(s.start, s.end);
            DocumentOutlineDto partial = outlineExtractor.extractOutlineWithDepth(sliceText, props.getMaxDepthRoot());
            for (OutlineNodeDto n : partial.nodes()) {
                if (isTopLevel(n)) all.add(n);
            }
        }
        // Deduplicate and stitch by title/anchor similarity
        var result = mergeSimilarTopLevel(all);
        // Sort by first occurrence in the full text for cleaner ordinals and spans
        result.sort(Comparator.comparingInt(n -> firstIndex(text, n)));
        return result;
    }

    private boolean isTopLevel(OutlineNodeDto n) {
        String t = n.type() == null ? "" : n.type().toUpperCase(Locale.ROOT);
        return props.getTopLevelTypes().stream()
                .anyMatch(type -> type.equalsIgnoreCase(t));
    }

    private List<OutlineNodeDto> mergeSimilarTopLevel(List<OutlineNodeDto> nodes) {
        if (nodes.isEmpty()) return nodes;
        List<OutlineNodeDto> result = new ArrayList<>();
        boolean[] used = new boolean[nodes.size()];
        for (int i = 0; i < nodes.size(); i++) {
            if (used[i]) continue;
            OutlineNodeDto a = nodes.get(i);
            List<OutlineNodeDto> group = new ArrayList<>();
            group.add(a);
            used[i] = true;
            for (int j = i + 1; j < nodes.size(); j++) {
                if (used[j]) continue;
                OutlineNodeDto b = nodes.get(j);
                if (similar(a, b) >= props.getTopLevelSimilarityThreshold()) { // similarity threshold
                    group.add(b);
                    used[j] = true;
                }
            }
            // choose the best representative (longest title)
            OutlineNodeDto rep = group.stream()
                    .max(Comparator.comparingInt(o -> safe(o.title()).length()))
                    .orElse(a);
            result.add(rep);
        }
        return result;
    }

    private double similar(OutlineNodeDto a, OutlineNodeDto b) {
        // Title similarity dominates; also consider start anchors
        double t = jaccardWords(safe(a.title()), safe(b.title()));
        double s = jaccardWords(safe(a.startAnchor()), safe(b.startAnchor()));
        return 0.7 * t + 0.3 * s;
    }

    private double jaccardWords(String a, String b) {
        Set<String> sa = new HashSet<>(Arrays.asList(a.toLowerCase(Locale.ROOT).split("\\s+")));
        Set<String> sb = new HashSet<>(Arrays.asList(b.toLowerCase(Locale.ROOT).split("\\s+")));
        sa.removeIf(String::isBlank);
        sb.removeIf(String::isBlank);
        if (sa.isEmpty() && sb.isEmpty()) return 1.0;
        // Similarity safeguard: two empty strings should not be considered similar
        if (safe(a).isBlank() && safe(b).isBlank()) return 0.0;
        Set<String> inter = new HashSet<>(sa);
        inter.retainAll(sb);
        Set<String> union = new HashSet<>(sa);
        union.addAll(sb);
        return union.isEmpty() ? 0.0 : ((double) inter.size()) / union.size();
    }

    private int indexOfIgnoreCase(String haystack, String needle) {
        if (needle == null || needle.isBlank()) return -1;
        int n = needle.length();
        for (int i = 0, max = haystack.length() - n; i <= max; i++) {
            if (haystack.regionMatches(true, i, needle, 0, n)) return i;
        }
        return -1;
    }

    private int indexOfIgnoreCase(String haystack, String needle, int from) {
        if (needle == null || needle.isBlank()) return -1;
        int n = needle.length();
        for (int i = Math.max(0, from), max = haystack.length() - n; i <= max; i++) {
            if (haystack.regionMatches(true, i, needle, 0, n)) return i;
        }
        return -1;
    }

    private List<OutlineNodeDto> extractInnerSections(String text, int start, int end) {
        String slice = text.substring(Math.max(0, start), Math.min(text.length(), end));
        DocumentOutlineDto o = outlineExtractor.extractOutlineWithDepth(slice, props.getMaxDepthPerChapter());
        List<OutlineNodeDto> sections = new ArrayList<>();
        for (OutlineNodeDto root : o.nodes()) {
            collectSections(root, sections);
        }
        return sections;
    }

    private void collectSections(OutlineNodeDto n, List<OutlineNodeDto> out) {
        if (isSectionOrBelow(n)) out.add(n);
        for (OutlineNodeDto c : n.children()) {
            collectSections(c, out);
        }
    }

    private boolean isSectionOrBelow(OutlineNodeDto n) {
        String t = n.type() == null ? "" : n.type().toUpperCase(Locale.ROOT);
        return "SECTION".equals(t) || "SUBSECTION".equals(t) || "PARAGRAPH".equals(t);
    }

    private List<OutlineNodeDto> addHeuristicParagraphs(String text, List<OutlineNodeDto> sections, int regionStart, int regionEnd) {
        // For each deepest section, if no children, synthesize paragraphs by splitting by paragraphMin/Max chars
        int min = props.getParagraphMinChars();
        int max = props.getParagraphMaxChars();
        
        // Early exit for tiny chapters
        if (regionEnd - regionStart < min) {
            return sections; // keep sections without paras
        }
        
        List<OutlineNodeDto> out = new ArrayList<>();
        for (OutlineNodeDto sec : sections) {
            if (sec.children() != null && !sec.children().isEmpty()) {
                out.add(sec);
                continue;
            }
            // synthesize a few paragraph nodes based on region windows
            List<OutlineNodeDto> paras = new ArrayList<>();
            String slice = text.substring(Math.max(0, regionStart), Math.min(text.length(), regionEnd));
            int cur = 0;
            while (cur < slice.length()) {
                int to = Math.min(slice.length(), cur + max);
                if (to - cur < min) break;
                String seg = slice.substring(cur, to);
                String firstLine = firstLine(seg);
                paras.add(new OutlineNodeDto("PARAGRAPH", truncate(firstLine, 80), snippet(seg, props.getParagraphAnchorWords()), snippetTail(seg, props.getParagraphAnchorWords()), List.of()));
                cur = to;
            }
            out.add(new OutlineNodeDto(sec.type(), sec.title(), sec.startAnchor(), sec.endAnchor(), paras));
        }
        return out;
    }

    private String firstLine(String s) {
        int nl = s.indexOf('\n');
        return (nl == -1) ? s.trim() : s.substring(0, nl).trim();
    }

    private String truncate(String s, int n) {
        if (s == null) return "";
        if (s.length() <= n) return s;
        return s.substring(0, n) + "...";
    }

    private String snippet(String s, int words) {
        String[] parts = s.trim().split("\\s+");
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < Math.min(words, parts.length); i++) {
            if (i > 0) b.append(' ');
            b.append(parts[i]);
        }
        return b.toString();
    }

    private String snippetTail(String s, int words) {
        String[] parts = s.trim().split("\\s+");
        int start = Math.max(0, parts.length - words);
        StringBuilder b = new StringBuilder();
        for (int i = start; i < parts.length; i++) {
            if (i > start) b.append(' ');
            b.append(parts[i]);
        }
        return b.toString();
    }

    /**
     * Find the first occurrence of a node in the text for ordering.
     */
    private int firstIndex(String text, OutlineNodeDto n) {
        int i = indexOfIgnoreCase(text, safe(n.startAnchor()));
        if (i >= 0) return i;
        i = indexOfIgnoreCase(text, safe(n.title()));
        return i >= 0 ? i : Integer.MAX_VALUE; // push unknowns to the end
    }
}


