package uk.gegc.quizmaker.features.document.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import uk.gegc.quizmaker.features.document.api.dto.DocumentOutlineDto;
import uk.gegc.quizmaker.features.document.api.dto.OutlineNodeDto;
import uk.gegc.quizmaker.features.document.domain.model.DocumentNode;
import uk.gegc.quizmaker.features.document.infra.text.SentenceBoundaryDetector;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Service for aligning document outline anchors to hard character offsets.
 * <p>
 * This service maps start_anchor/end_anchor from the LLM outline extraction
 * to start_offset/end_offset in the canonical text, using fuzzy search and
 * enforcing non-overlap constraints.
 * <p>
 * Implementation of Day 5 — Anchor Alignment → Hard Offsets from the
 * chunk processing improvement plan.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OutlineAlignmentService {

    private final SentenceBoundaryDetector sentenceBoundaryDetector;

    // Configuration constants
    private static final int MAX_ANCHOR_SEARCH_RADIUS = 200; // characters to search around predicted boundary
    private static final int MIN_ANCHOR_WORDS = 2; // minimum anchor words to consider
    private static final double FUZZY_MATCH_THRESHOLD = 0.8; // similarity threshold for fuzzy matching
    private static final int MAX_EXPANSION_ATTEMPTS = 3; // maximum window expansion attempts

    // Patterns for sentence boundary detection
    private static final Pattern ABBREVIATION_PATTERN = Pattern.compile(
            "\\b(Mr|Mrs|Ms|Dr|Prof|Sr|Jr|Inc|Ltd|Corp|Co|vs|etc|i\\.e|e\\.g|a\\.m|p\\.m|U\\.S|U\\.K|Ph\\.D|M\\.A|B\\.A)\\."
    );
    private static final Pattern DECIMAL_PATTERN = Pattern.compile("\\d+\\.\\d+");
    private static final Pattern ELLIPSIS_PATTERN = Pattern.compile("\\.{3,}");

    /**
     * Match result containing position and similarity score.
     */
    private static final class Match {
        final int pos;
        final double sim;
        
        Match(int pos, double sim) {
            this.pos = pos;
            this.sim = sim;
        }
    }

    /**
     * Anchor match result containing offset and quality.
     */
    private static final class AnchorMatch {
        final int offset;
        final double quality;
        
        AnchorMatch(int offset, double quality) {
            this.offset = offset;
            this.quality = quality;
        }
    }

    /**
     * Align outline nodes to hard offsets in the canonical text.
     *
     * @param outline the extracted outline with anchors
     * @param canonicalText the canonical text service result
     * @param preSegmentationWindows the pre-segmentation windows
     * @param documentId the document ID
     * @param sourceVersionHash the source version hash
     * @return list of aligned document nodes ready for persistence
     */
    public List<DocumentNode> alignOutlineToOffsets(
            DocumentOutlineDto outline,
            CanonicalTextService.CanonicalizedText canonicalText,
            List<PreSegmentationService.PreSegmentationWindow> preSegmentationWindows,
            UUID documentId,
            String sourceVersionHash) {

        // Validate input parameters
        if (outline == null) {
            throw new IllegalArgumentException("Outline cannot be null");
        }
        if (canonicalText == null) {
            throw new IllegalArgumentException("Canonical text cannot be null");
        }
        if (documentId == null) {
            throw new IllegalArgumentException("Document ID cannot be null");
        }

        log.info("Aligning outline with {} root nodes to canonical text (length: {})",
                outline.nodes().size(), canonicalText.getText().length());

        List<DocumentNode> alignedNodes = new ArrayList<>();
        int nextOrdinal = 1;

        // Process root nodes first
        for (OutlineNodeDto rootNode : outline.nodes()) {
            DocumentNode alignedNode = alignNode(
                    rootNode, null, canonicalText, preSegmentationWindows,
                    documentId, sourceVersionHash, nextOrdinal++);
            
            if (alignedNode != null) {
                alignedNodes.add(alignedNode);
                
                // Process children recursively
                processChildren(rootNode, alignedNode, canonicalText, preSegmentationWindows,
                        documentId, sourceVersionHash, alignedNodes);
            }
        }

        // Optimize sibling boundaries before enforcing non-overlap constraints
        optimizeSiblingBoundaries(alignedNodes);
        
        // Enforce non-overlap constraints and re-parent loose nodes
        enforceNonOverlapConstraints(alignedNodes);
        reparentLooseNodes(alignedNodes);

        // Assign final ordinals
        assignFinalOrdinals(alignedNodes);

        log.info("Successfully aligned {} nodes with hard offsets", alignedNodes.size());
        return alignedNodes;
    }

    /**
     * Align a single node to hard offsets.
     */
    private DocumentNode alignNode(
            OutlineNodeDto nodeDto,
            DocumentNode parent,
            CanonicalTextService.CanonicalizedText canonicalText,
            List<PreSegmentationService.PreSegmentationWindow> preSegmentationWindows,
            UUID documentId,
            String sourceVersionHash,
            int ordinal) {

        String text = canonicalText.getText();
        
        // Find start offset with match quality
        AnchorMatch startMatch = findAnchorOffsetWithQuality(nodeDto.startAnchor(), text, preSegmentationWindows, true);
        if (startMatch.offset == -1) {
            log.warn("Could not find start anchor '{}' for node '{}'", 
                    nodeDto.startAnchor(), nodeDto.title());
            return null;
        }

        // Find end offset with match quality
        AnchorMatch endMatch = findAnchorOffsetWithQuality(nodeDto.endAnchor(), text, preSegmentationWindows, false);
        if (endMatch.offset == -1) {
            // If end anchor not found, try to find a reasonable boundary
            endMatch = new AnchorMatch(findReasonableEndBoundary(startMatch.offset, text, preSegmentationWindows), 1.0);
        }

        // Ensure end offset is after start offset
        if (endMatch.offset <= startMatch.offset) {
            endMatch = new AnchorMatch(Math.min(startMatch.offset + 1000, text.length()), 1.0); // fallback
        }

        // Clamp to sentence boundaries when possible
        int startOffset = clampToSentenceBoundary(text, startMatch.offset, true);
        int endOffset = clampToSentenceBoundary(text, endMatch.offset, false);

        // Calculate overall match quality (average of start and end anchor quality)
        double matchQuality = (startMatch.quality + endMatch.quality) / 2.0;

        // Create the document node
        DocumentNode node = new DocumentNode();
        node.setDocument(new uk.gegc.quizmaker.features.document.domain.model.Document());
        node.getDocument().setId(documentId);
        node.setParent(parent);
        node.setLevel(parent == null ? 0 : parent.getLevel() + 1);
        node.setType(mapNodeType(nodeDto.type()));
        node.setTitle(nodeDto.title());
        node.setStartOffset(startOffset);
        node.setEndOffset(endOffset);
        node.setStartAnchor(nodeDto.startAnchor());
        node.setEndAnchor(nodeDto.endAnchor());
        node.setOrdinal(ordinal);
        node.setStrategy(DocumentNode.Strategy.AI);
        node.setConfidence(calculateConfidence(nodeDto.startAnchor(), nodeDto.endAnchor(), startOffset, endOffset, text, matchQuality));
        node.setSourceVersionHash(sourceVersionHash);

        log.debug("Aligned node '{}' to offsets [{}, {}] with quality {}", 
                nodeDto.title(), startOffset, endOffset, matchQuality);

        return node;
    }

    /**
     * Process children nodes recursively.
     */
    private void processChildren(
            OutlineNodeDto parentDto,
            DocumentNode parentNode,
            CanonicalTextService.CanonicalizedText canonicalText,
            List<PreSegmentationService.PreSegmentationWindow> preSegmentationWindows,
            UUID documentId,
            String sourceVersionHash,
            List<DocumentNode> allNodes) {

        if (parentDto.children() == null || parentDto.children().isEmpty()) {
            return;
        }

        int childOrdinal = 1;
        for (OutlineNodeDto childDto : parentDto.children()) {
            DocumentNode childNode = alignNode(
                    childDto, parentNode, canonicalText, preSegmentationWindows,
                    documentId, sourceVersionHash, childOrdinal++);
            
            if (childNode != null) {
                allNodes.add(childNode);
                
                // Process grandchildren recursively
                processChildren(childDto, childNode, canonicalText, preSegmentationWindows,
                        documentId, sourceVersionHash, allNodes);
            }
        }
    }

    /**
     * Check if a string has minimum word count.
     */
    private boolean hasMinWords(String s, int min) {
        return s != null && s.trim().split("\\s+").length >= min;
    }



    /**
     * Find the offset of an anchor in the text using fuzzy search with quality.
     */
    private AnchorMatch findAnchorOffsetWithQuality(
            String anchor,
            String text,
            List<PreSegmentationService.PreSegmentationWindow> windows,
            boolean isStartAnchor) {

        if (anchor == null || anchor.trim().isEmpty()) {
            return new AnchorMatch(isStartAnchor ? 0 : text.length(), 1.0);
        }

        String cleanAnchor = anchor.trim();
        if (!hasMinWords(cleanAnchor, MIN_ANCHOR_WORDS)) {
            return new AnchorMatch(-1, 0.0);
        }

        // First, try case-insensitive exact match on original text
        int exactMatch = indexOfIgnoreCase(text, cleanAnchor);
        if (exactMatch != -1) {
            return new AnchorMatch(exactMatch, 1.0);
        }

        // Try fuzzy match within pre-segmentation windows
        Match fuzzyMatch = findFuzzyMatchInWindowsWithQuality(cleanAnchor, text, windows, isStartAnchor);
        if (fuzzyMatch.pos != -1) {
            return new AnchorMatch(fuzzyMatch.pos, fuzzyMatch.sim);
        }

        // Try fuzzy match in the entire text
        Match fullMatch = findFuzzyMatchWithQuality(text, cleanAnchor, isStartAnchor);
        return new AnchorMatch(fullMatch.pos, fullMatch.sim);
    }

    /**
     * Fold a string for case-insensitive comparison.
     */
    private static String fold(String s) {
        return java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFC)
                .toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * Case-insensitive exact search without reallocating whole strings.
     */
    private int indexOfIgnoreCase(String text, String needle) {
        int n = needle.length();
        for (int i = 0, max = text.length() - n; i <= max; i++) {
            if (text.regionMatches(true, i, needle, 0, n)) return i;
        }
        return -1;
    }

    /**
     * Find fuzzy match within pre-segmentation windows with progressive expansion and quality.
     */
    private Match findFuzzyMatchInWindowsWithQuality(
            String anchor,
            String text,
            List<PreSegmentationService.PreSegmentationWindow> windows,
            boolean isStartAnchor) {

        for (int radius = 0; radius <= MAX_EXPANSION_ATTEMPTS; radius++) {
            for (int i = 0; i < windows.size(); i++) {
                int from = Math.max(0, i - radius);
                int to = Math.min(windows.size() - 1, i + radius);
                int start = windows.get(from).startOffset();
                int end = windows.get(to).endOffset();
                String slice = text.substring(start, end);
                
                // Try case-insensitive exact match in expanded window
                int exactInSlice = indexOfIgnoreCase(slice, anchor);
                if (exactInSlice != -1) {
                    return new Match(start + exactInSlice, 1.0);
                }

                // Try fuzzy match in expanded window
                Match fuzzyInSlice = bestFuzzyMatchInText(slice, anchor);
                if (fuzzyInSlice.pos != -1) {
                    return new Match(start + fuzzyInSlice.pos, fuzzyInSlice.sim);
                }
            }
        }

        return new Match(-1, 0.0);
    }

    /**
     * Find fuzzy match in the entire text with quality.
     */
    private Match findFuzzyMatchWithQuality(String text, String anchor, boolean isStartAnchor) {
        return bestFuzzyMatchInText(text, anchor);
    }

    /**
     * Find the best fuzzy match in text using similarity scoring.
     */
    private Match bestFuzzyMatchInText(String baseText, String anchor) {
        String fAnchor = fold(anchor);
        int w = anchor.length();
        if (baseText.length() < w) return new Match(-1, 0.0);

        double best = 0.0;
        int bestPos = -1;
        for (int i = 0; i <= baseText.length() - w; i++) {
            String candidate = baseText.substring(i, i + w);
            double sim = calculateSimilarity(fold(candidate), fAnchor);
            if (sim >= FUZZY_MATCH_THRESHOLD && sim > best) {
                best = sim;
                bestPos = i;              // <-- index stays in original text
            }
        }
        return new Match(bestPos, best);
    }



    /**
     * Calculate similarity between two strings using Levenshtein distance.
     */
    private double calculateSimilarity(String s1, String s2) {
        if (s1.equals(s2)) {
            return 1.0;
        }

        int maxLength = Math.max(s1.length(), s2.length());
        if (maxLength == 0) {
            return 1.0;
        }

        int distance = levenshteinDistance(s1, s2);
        return 1.0 - ((double) distance / maxLength);
    }

    /**
     * Calculate Levenshtein distance between two strings.
     */
    private int levenshteinDistance(String s1, String s2) {
        int[][] dp = new int[s1.length() + 1][s2.length() + 1];

        for (int i = 0; i <= s1.length(); i++) {
            dp[i][0] = i;
        }
        for (int j = 0; j <= s2.length(); j++) {
            dp[0][j] = j;
        }

        for (int i = 1; i <= s1.length(); i++) {
            for (int j = 1; j <= s2.length(); j++) {
                if (s1.charAt(i - 1) == s2.charAt(j - 1)) {
                    dp[i][j] = dp[i - 1][j - 1];
                } else {
                    dp[i][j] = 1 + Math.min(dp[i - 1][j], Math.min(dp[i][j - 1], dp[i - 1][j - 1]));
                }
            }
        }

        return dp[s1.length()][s2.length()];
    }

    /**
     * Find a reasonable end boundary when end anchor is not found.
     */
    private int findReasonableEndBoundary(
            int startOffset,
            String text,
            List<PreSegmentationService.PreSegmentationWindow> windows) {

        // Find the window containing the start offset
        PreSegmentationService.PreSegmentationWindow containingWindow = null;
        for (PreSegmentationService.PreSegmentationWindow window : windows) {
            if (startOffset >= window.startOffset() && startOffset < window.endOffset()) {
                containingWindow = window;
                break;
            }
        }

        if (containingWindow != null) {
            // Use the end of the containing window
            return containingWindow.endOffset();
        }

        // Fallback: use a reasonable chunk size
        return Math.min(startOffset + 2000, text.length());
    }

    /**
     * Clamp offset to sentence boundary.
     */
    private int clampToSentenceBoundary(String text, int offset, boolean isStart) {
        if (offset <= 0 || offset >= text.length()) {
            return offset;
        }

        if (isStart) {
            // For start offset, find the previous sentence start
            String searchText = text.substring(Math.max(0, offset - MAX_ANCHOR_SEARCH_RADIUS), offset);
            int sentenceStart = findPreviousSentenceStart(searchText);
            if (sentenceStart != -1) {
                return Math.max(0, offset - MAX_ANCHOR_SEARCH_RADIUS) + sentenceStart;
            }
        } else {
            // For end offset, find the next sentence end
            String searchText = text.substring(offset, Math.min(offset + MAX_ANCHOR_SEARCH_RADIUS, text.length()));
            int sentenceEnd = sentenceBoundaryDetector.findNextSentenceEnd(searchText);
            if (sentenceEnd != -1) {
                return offset + sentenceEnd;
            }
        }

        return offset;
    }

    /**
     * Find the previous sentence start in the given text.
     */
    private int findPreviousSentenceStart(String text) {
        if (text == null || text.isEmpty()) {
            return -1;
        }

        // Start from the end and work backwards
        for (int i = text.length() - 1; i >= 0; i--) {
            char c = text.charAt(i);
            if (isSentenceEnding(c, text, i)) {
                // Return position after the sentence ending
                return i + 1;
            }
        }

        return -1;
    }

    /**
     * Check if a character at the given position is a sentence ending.
     */
    private boolean isSentenceEnding(char c, String text, int position) {
        // Handle CJK punctuation (Chinese/Japanese) first
        if (c == '。' || c == '！' || c == '？') {
            return true;
        }

        // Handle standard punctuation
        if (c != '.' && c != '!' && c != '?') {
            return false;
        }

        // Skip closing quotes/brackets after punctuation using Unicode-aware check
        int i = position + 1;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            if (!isCloser(cp)) break;
            i += Character.charCount(cp);
        }

        // Accept whitespace OR space chars (covers NBSP)
        if (i < text.length()) {
            char ch = text.charAt(i);
            if (!(Character.isWhitespace(ch) || Character.isSpaceChar(ch))) {
                return false;
            }
        }

        // Handle special cases for periods
        if (c == '.') {
            return !isAbbreviation(text, position) &&
                    !isDecimal(text, position) &&
                    !isEllipsis(text, position);
        }

        return true;
    }

    /**
     * Check if a code point is a closing punctuation character
     */
    private static boolean isCloser(int cp) {
        int t = Character.getType(cp);
        return t == Character.END_PUNCTUATION || t == Character.FINAL_QUOTE_PUNCTUATION;
    }

    /**
     * Check if the period is part of an abbreviation
     */
    private boolean isAbbreviation(String text, int position) {
        // Look for common abbreviations before the period
        int start = Math.max(0, position - 20); // Look back up to 20 characters
        String beforePeriod = text.substring(start, position + 1);

        return ABBREVIATION_PATTERN.matcher(beforePeriod).find();
    }

    /**
     * Check if the period is part of a decimal number
     */
    private boolean isDecimal(String text, int position) {
        // Look for digits before and after the period
        int start = Math.max(0, position - 10);
        int end = Math.min(text.length(), position + 10);
        String aroundPeriod = text.substring(start, end);

        return DECIMAL_PATTERN.matcher(aroundPeriod).find();
    }

    /**
     * Check if the period is part of an ellipsis
     */
    private boolean isEllipsis(String text, int position) {
        // Look for multiple periods around this position
        int start = Math.max(0, position - 2);
        int end = Math.min(text.length(), position + 3);
        String aroundPeriod = text.substring(start, end);

        return ELLIPSIS_PATTERN.matcher(aroundPeriod).find();
    }

    /**
     * Map outline node type to document node type.
     */
    private DocumentNode.NodeType mapNodeType(String outlineType) {
        return switch (outlineType.toUpperCase()) {
            case "PART" -> DocumentNode.NodeType.PART;
            case "CHAPTER" -> DocumentNode.NodeType.CHAPTER;
            case "SECTION" -> DocumentNode.NodeType.SECTION;
            case "SUBSECTION" -> DocumentNode.NodeType.SUBSECTION;
            case "PARAGRAPH" -> DocumentNode.NodeType.PARAGRAPH;
            default -> DocumentNode.NodeType.OTHER;
        };
    }

    /**
     * Calculate confidence score for the alignment.
     */
    private java.math.BigDecimal calculateConfidence(
            String startAnchor,
            String endAnchor,
            int startOffset,
            int endOffset,
            String text,
            double matchQuality) {

        double confidence = 1.0;

        // Include match quality in confidence
        confidence *= matchQuality;

        // Penalize if anchors are missing
        if (startAnchor == null || startAnchor.trim().isEmpty()) {
            confidence -= 0.2;
        }
        if (endAnchor == null || endAnchor.trim().isEmpty()) {
            confidence -= 0.2;
        }

        // Penalize if offsets are at boundaries
        if (startOffset == 0) {
            confidence -= 0.1;
        }
        if (endOffset == text.length()) {
            confidence -= 0.1;
        }

        // Penalize if range is too small or too large
        int range = endOffset - startOffset;
        if (range < 50) {
            confidence -= 0.3;
        } else if (range > 10000) {
            confidence -= 0.2;
        }

        return java.math.BigDecimal.valueOf(Math.max(0.0, Math.min(1.0, confidence)));
    }

    /**
     * Group nodes by parent reference using identity semantics.
     */
    private Map<DocumentNode, List<DocumentNode>> groupByParent(List<DocumentNode> nodes) {
        Map<DocumentNode, List<DocumentNode>> m = new IdentityHashMap<>();
        for (DocumentNode n : nodes) {
            m.computeIfAbsent(n.getParent(), k -> new ArrayList<>()).add(n);
        }
        return m;
    }

    /**
     * Optimize sibling boundaries by setting each child's endOffset to the next child's startOffset.
     */
    private void optimizeSiblingBoundaries(List<DocumentNode> nodes) {
        Map<DocumentNode, List<DocumentNode>> byParent = groupByParent(nodes);
        for (List<DocumentNode> siblings : byParent.values()) {
            if (siblings.size() < 2) continue;
            
            siblings.sort(Comparator.comparingInt(DocumentNode::getStartOffset));
            
            // Set each child's endOffset to the next child's startOffset when it would reduce overlap
            for (int i = 0; i < siblings.size() - 1; i++) {
                DocumentNode current = siblings.get(i);
                DocumentNode next = siblings.get(i + 1);
                
                if (current.getEndOffset() > next.getStartOffset()) {
                    current.setEndOffset(next.getStartOffset());
                    log.debug("Optimized sibling boundary: '{}' end set to '{}' start at position {}", 
                            current.getTitle(), next.getTitle(), next.getStartOffset());
                }
            }
        }
    }

    /**
     * Enforce non-overlap constraints within the same parent.
     */
    private void enforceNonOverlapConstraints(List<DocumentNode> nodes) {
        Map<DocumentNode, List<DocumentNode>> byParent = groupByParent(nodes);
        for (List<DocumentNode> sibs : byParent.values()) {
            if (sibs.size() < 2) continue;
            sibs.sort(Comparator.comparingInt(DocumentNode::getStartOffset));
            for (int i = 0; i < sibs.size() - 1; i++) {
                DocumentNode a = sibs.get(i), b = sibs.get(i + 1);
                if (a.getEndOffset() > b.getStartOffset()) {
                    int mid = (a.getEndOffset() + b.getStartOffset()) / 2;
                    a.setEndOffset(mid);
                    b.setStartOffset(mid);
                    log.debug("Fixed overlap between nodes '{}' and '{}' at position {}", 
                            a.getTitle(), b.getTitle(), mid);
                }
            }
        }
    }

    /**
     * Re-parent loose nodes to closest valid parent.
     */
    private void reparentLooseNodes(List<DocumentNode> nodes) {
        // Find root nodes
        List<DocumentNode> rootNodes = nodes.stream()
                .filter(node -> node.getParent() == null)
                .sorted(Comparator.comparing(DocumentNode::getStartOffset))
                .toList();

        // Find orphaned nodes (nodes with parent not in the list)
        Set<DocumentNode> nodeSet = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        nodeSet.addAll(nodes);
        List<DocumentNode> orphanedNodes = nodes.stream()
                .filter(node -> node.getParent() != null && !nodeSet.contains(node.getParent()))
                .toList();

        for (DocumentNode orphan : orphanedNodes) {
            // Find the smallest containing parent span (tightest fit)
            DocumentNode bestParent = null;
            int bestSpan = Integer.MAX_VALUE;

            for (DocumentNode root : rootNodes) {
                if (root.getStartOffset() <= orphan.getStartOffset() && 
                    orphan.getEndOffset() <= root.getEndOffset()) {
                    int span = root.getEndOffset() - root.getStartOffset();
                    if (span < bestSpan) {
                        bestSpan = span;
                        bestParent = root;
                    }
                }
            }

            if (bestParent != null) {
                orphan.setParent(bestParent);
                orphan.setLevel(bestParent.getLevel() + 1);
                log.debug("Re-parented orphan node '{}' to '{}'", 
                        orphan.getTitle(), bestParent.getTitle());
            } else {
                // If no suitable parent found, make it a root node
                orphan.setParent(null);
                orphan.setLevel(0);
                log.debug("Made orphan node '{}' a root node", orphan.getTitle());
            }
        }
    }

    /**
     * Assign final ordinals to nodes.
     */
    private void assignFinalOrdinals(List<DocumentNode> nodes) {
        Map<DocumentNode, List<DocumentNode>> byParent = groupByParent(nodes);
        for (List<DocumentNode> sibs : byParent.values()) {
            sibs.sort(Comparator.comparingInt(DocumentNode::getStartOffset));
            for (int i = 0; i < sibs.size(); i++) {
                sibs.get(i).setOrdinal(i + 1);
            }
        }
    }
}
