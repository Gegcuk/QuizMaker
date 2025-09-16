package uk.gegc.quizmaker.features.documentProcess.application;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.features.documentProcess.domain.model.DocumentNode;

import java.text.Normalizer;
import java.util.*;

import java.util.Arrays;
import java.util.List;

/**
 * Service for calculating character offsets from text anchors.
 * This provides more reliable offset calculation than AI-generated offsets.
 */
@Component
@Slf4j
public class AnchorOffsetCalculator {

    /**
     * Calculates offsets for a list of document nodes using their text anchors.
     * Falls back to AI-provided offsets if anchor matching fails.
     * 
     * @param nodes the nodes with anchors to calculate offsets for
     * @param documentText the full document text to search in
     * @return the same list with calculated offsets
     * @throws AnchorNotFoundException if any anchor cannot be found and no valid fallback exists
     */
    public List<DocumentNode> calculateOffsets(List<DocumentNode> nodes, String documentText) {

        int anchorSuccesses = 0;
        int offsetFallbacks = 0;
        
        for (DocumentNode node : nodes) {
            try {
                calculateNodeOffsets(node, documentText);
                anchorSuccesses++;
            } catch (AnchorNotFoundException e) {
                // Try to use AI-provided offsets as fallback
                if (node.getStartOffset() != null && node.getEndOffset() != null) {
                    log.warn("Anchor matching failed for '{}', using AI-provided offsets: [{}:{})", 
                            node.getTitle(), node.getStartOffset(), node.getEndOffset());
                    
                    // Validate AI offsets are within document bounds
                    if (node.getStartOffset() >= 0 && 
                        node.getEndOffset() <= documentText.length() && 
                        node.getStartOffset() < node.getEndOffset()) {
                        
                        offsetFallbacks++;
                    } else {
                        log.error("AI-provided offsets are invalid for '{}': [{}:{}), document length: {}", 
                                node.getTitle(), node.getStartOffset(), node.getEndOffset(), documentText.length());
                        throw e; // Re-throw the original anchor exception
                    }
                } else {
                    log.error("No AI-provided offsets available as fallback for '{}'", node.getTitle());
                    throw e; // Re-throw the original anchor exception
                }
            }
        }
        
        log.info("Offset calculation completed: {} anchor successes, {} AI offset fallbacks", 
                anchorSuccesses, offsetFallbacks);
        return nodes;
    }

    /**
     * Calculates start and end offsets for a single node.
     */
    private void calculateNodeOffsets(DocumentNode node, String documentText) {
        String startAnchor = node.getStartAnchor();
        String endAnchor = node.getEndAnchor();
        
        if (startAnchor == null || startAnchor.trim().isEmpty()) {
            throw new AnchorNotFoundException("Start anchor is null or empty for node: " + node.getTitle());
        }
        
        if (endAnchor == null || endAnchor.trim().isEmpty()) {
            throw new AnchorNotFoundException("End anchor is null or empty for node: " + node.getTitle());
        }
        
        // Find start offset
        int startOffset = findAnchorPosition(documentText, startAnchor, node.getTitle(), "start");
        if (startOffset == -1) {
            throw new AnchorNotFoundException("Start anchor not found: '" + startAnchor + "' for node: " + node.getTitle());
        }
        
        // Find end offset (search from start position to avoid wrong matches)
        int endOffset = findAnchorPosition(documentText, endAnchor, node.getTitle(), "end", startOffset);
        if (endOffset == -1) {
            // Try to find a reasonable fallback end position
            log.warn("End anchor not found: '{}' for node: {}. Attempting fallback positioning.", 
                    endAnchor.substring(0, Math.min(50, endAnchor.length())), node.getTitle());
            
            // Look for the next major section or chapter after the start position
            endOffset = findNextMajorSection(documentText, startOffset);
            if (endOffset == -1) {
                // If no next section found, use document end
                endOffset = documentText.length();
                log.warn("No next section found for node: {}. Using document end as fallback.", node.getTitle());
            }
        }
        
        // For end anchors, we want the position after the anchor text
        endOffset += endAnchor.length();
        
        // Validate that start < end
        if (startOffset >= endOffset) {
            throw new IllegalArgumentException(
                "Invalid anchor positions for node '" + node.getTitle() + "': start=" + startOffset + ", end=" + endOffset
            );
        }
        
        // Validate that offsets are within document bounds
        if (startOffset < 0 || endOffset > documentText.length()) {
            throw new IllegalArgumentException(
                "Anchor positions out of bounds for node '" + node.getTitle() + "': start=" + startOffset + 
                ", end=" + endOffset + ", documentLength=" + documentText.length()
            );
        }
        
        node.setStartOffset(startOffset);
        node.setEndOffset(endOffset);
    }

    /**
     * Finds the position of an anchor text in the document.
     * Uses case-sensitive search first, then falls back to case-insensitive.
     * 
     * @param documentText the full document text
     * @param anchor the anchor text to find
     * @param nodeTitle the node title for logging
     * @param anchorType "start" or "end" for logging
     * @param fromIndex minimum position to search from (for end anchors)
     * @return the position of the anchor, or -1 if not found
     */
    private int findAnchorPosition(String documentText, String anchor, String nodeTitle, String anchorType, int fromIndex) {
        // Validate anchor length
        if (anchor.length() < 20) {
            log.warn("{} anchor '{}' for node '{}' is too short ({} chars), should be at least 20 characters", 
                    anchorType, anchor, nodeTitle, anchor.length());
        }
        
        // Normalize both document and anchor for Unicode consistency
        String normalizedDoc = Normalizer.normalize(documentText, Normalizer.Form.NFC);
        String normalizedAnchor = Normalizer.normalize(anchor, Normalizer.Form.NFC);
        
        // Also normalize newline characters in anchors to match document format
        String anchorNormalized = normalizedAnchor.replace("\\n", " ").replace("\n", " ");
        
        // First try exact match from the specified position
        int position = normalizedDoc.indexOf(normalizedAnchor, fromIndex);
        if (position != -1) {
            return position;
        }
        
        // Try with newline normalization
        position = normalizedDoc.indexOf(anchorNormalized, fromIndex);
        if (position != -1) {
            return position;
        }
        
        // Try with whitespace normalization (collapse multiple whitespace to single space)
        String docWhitespaceNormalized = normalizeWhitespace(normalizedDoc);
        String anchorWhitespaceNormalized = normalizeWhitespace(normalizedAnchor);
        
        // Find position in whitespace-normalized text
        int normalizedPosition = docWhitespaceNormalized.indexOf(anchorWhitespaceNormalized, 
                findNormalizedPosition(normalizedDoc, docWhitespaceNormalized, fromIndex));
        if (normalizedPosition != -1) {
            // Convert back to original text position
            int originalPosition = findOriginalPosition(normalizedDoc, docWhitespaceNormalized, normalizedPosition);
            return originalPosition;
        }
        
        // Try with quote normalization (unescape JSON quotes)
        String anchorUnescaped = unescapeJsonQuotes(normalizedAnchor);
        String docUnescaped = unescapeJsonQuotes(normalizedDoc);
        
        // Try exact match with unescaped quotes
        position = docUnescaped.indexOf(anchorUnescaped, fromIndex);
        if (position != -1) {
            log.debug("Found {} anchor '{}' for node '{}' using quote unescaping at position {}", 
                    anchorType, anchor.substring(0, Math.min(50, anchor.length())), nodeTitle, position);
            return position;
        }
        
        // Try whitespace normalization with unescaped quotes
        String docUnescapedWhitespaceNormalized = normalizeWhitespace(docUnescaped);
        String anchorUnescapedWhitespaceNormalized = normalizeWhitespace(anchorUnescaped);
        
        normalizedPosition = docUnescapedWhitespaceNormalized.indexOf(anchorUnescapedWhitespaceNormalized, 
                findNormalizedPosition(docUnescaped, docUnescapedWhitespaceNormalized, fromIndex));
        if (normalizedPosition != -1) {
            // Convert back to original text position
            int originalPosition = findOriginalPosition(docUnescaped, docUnescapedWhitespaceNormalized, normalizedPosition);
            log.debug("Found {} anchor '{}' for node '{}' using whitespace normalization + quote unescaping at position {}", 
                    anchorType, anchor.substring(0, Math.min(50, anchor.length())), nodeTitle, originalPosition);
            return originalPosition;
        }
        
        // Try case-insensitive match from the specified position
        String lowerDoc = normalizedDoc.toLowerCase(java.util.Locale.ROOT);
        String lowerAnchor = normalizedAnchor.toLowerCase(java.util.Locale.ROOT);
        position = lowerDoc.indexOf(lowerAnchor, fromIndex);
        if (position != -1) {
            log.warn("Found {} anchor '{}' for node '{}' using case-insensitive search at position {}", 
                    anchorType, anchor, nodeTitle, position);
            return position;
        }
        
        // Note: No fallback to before start position for end anchors to avoid wrong spans
        
        // Try fallback strategy: shortened anchor search
        int fallbackPosition = findShortenedAnchor(documentText, anchor, nodeTitle, anchorType, fromIndex);
        if (fallbackPosition != -1) {
            return fallbackPosition;
        }
        
        // Try fuzzy matching strategy: find the longest matching substring
        int fuzzyPosition = findFuzzyMatch(documentText, anchor, nodeTitle, anchorType, fromIndex);
        if (fuzzyPosition != -1) {
            return fuzzyPosition;
        }
        
        // Try to find partial matches for debugging
        log.warn("{} anchor '{}' not found for node '{}'. Document preview: '{}'", 
                anchorType, anchor, nodeTitle, 
                documentText.substring(0, Math.min(200, documentText.length())));
        
        return -1;
    }

    /**
     * Overload for backward compatibility - searches from the beginning.
     */
    private int findAnchorPosition(String documentText, String anchor, String nodeTitle, String anchorType) {
        return findAnchorPosition(documentText, anchor, nodeTitle, anchorType, 0);
    }

    /**
     * Validates that sibling nodes don't overlap after offset calculation.
     * Parent-child overlaps are expected in a hierarchy.
     */
    public void validateSiblingNonOverlap(List<DocumentNode> nodes) {
        // Group nodes by parent for sibling validation
        Map<UUID, List<DocumentNode>> childrenByParent = new HashMap<>();
        
        for (DocumentNode node : nodes) {
            UUID parentId = node.getParent() != null ? node.getParent().getId() : null;
            childrenByParent.computeIfAbsent(parentId, k -> new ArrayList<>()).add(node);
        }

        // Validate each group of siblings
        for (Map.Entry<UUID, List<DocumentNode>> entry : childrenByParent.entrySet()) {
            List<DocumentNode> siblings = entry.getValue();
            String parentName = entry.getKey() != null ? 
                    siblings.get(0).getParent().getTitle() : "ROOT";

            // Sort siblings by start offset
            siblings.sort((a, b) -> Integer.compare(a.getStartOffset(), b.getStartOffset()));

            // Check for overlapping siblings
            for (int i = 0; i < siblings.size() - 1; i++) {
                DocumentNode current = siblings.get(i);
                DocumentNode next = siblings.get(i + 1);

                if (current.getEndOffset() > next.getStartOffset()) {
                    throw new IllegalArgumentException(
                        "Overlapping siblings in " + parentName + ": " +
                        current.getTitle() + " [" + current.getStartOffset() + "," + current.getEndOffset() + ") and " +
                        next.getTitle() + " [" + next.getStartOffset() + "," + next.getEndOffset() + ")"
                    );
                }
            }

            log.debug("Validated {} siblings under {} for non-overlap", siblings.size(), parentName);
        }
    }

    /**
     * Finds the next major section or chapter after the given position.
     * This is used as a fallback when end anchors cannot be found.
     * 
     * @param documentText the full document text
     * @param fromPosition the position to search from
     * @return the position of the next major section, or -1 if not found
     */
    private int findNextMajorSection(String documentText, int fromPosition) {
        if (fromPosition >= documentText.length()) {
            return -1;
        }
        
        // Look for common section/chapter patterns
        String[] patterns = {
            "CHAPTER", "Chapter", "chapter",
            "PART", "Part", "part", 
            "SECTION", "Section", "section",
            "Introduction", "INTRODUCTION",
            "About the", "ABOUT THE",
            "Acknowledgments", "ACKNOWLEDGMENTS"
        };
        
        for (String pattern : patterns) {
            int pos = documentText.indexOf(pattern, fromPosition + 1);
            if (pos != -1) {
                return pos;
            }
        }
        
        return -1;
    }

    /**
     * Normalizes whitespace by collapsing multiple whitespace characters into single spaces.
     * This helps match AI-generated anchors that may have different line breaks.
     */
    private String normalizeWhitespace(String text) {
        return text.replaceAll("\\s+", " ").trim();
    }

    /**
     * Unescapes JSON quotes in text. AI-generated anchors may contain escaped quotes like \"Core APIs\"
     * but the document text has regular quotes like "Core APIs".
     */
    private String unescapeJsonQuotes(String text) {
        return text.replace("\\\"", "\"");
    }

    /**
     * Fallback strategy: tries to find shortened versions of the anchor.
     * This helps when AI generates very long anchors that don't exist exactly in the document.
     * 
     * @param documentText the full document text
     * @param anchor the original anchor text
     * @param nodeTitle the node title for logging
     * @param anchorType "start" or "end" for logging
     * @param fromIndex minimum position to search from
     * @return the position of the shortened anchor, or -1 if not found
     */
    private int findShortenedAnchor(String documentText, String anchor, String nodeTitle, String anchorType, int fromIndex) {
        // Try different shortened versions of the anchor
        String[] shortenedVersions = {
            anchor.substring(0, Math.min(50, anchor.length())),  // First 50 chars
            anchor.substring(0, Math.min(40, anchor.length())),  // First 40 chars
            anchor.substring(0, Math.min(30, anchor.length())),  // First 30 chars
            anchor.substring(0, Math.min(25, anchor.length())),  // First 25 chars
            anchor.substring(0, Math.min(20, anchor.length()))   // First 20 chars (minimum)
        };
        
        for (String shortened : shortenedVersions) {
            if (shortened.length() < 20) {
                continue; // Skip if too short
            }
            
            // Normalize newlines in shortened version
            String shortenedNormalized = shortened.replace("\\n", " ").replace("\n", " ");
            
            // Try exact match with shortened version
            int position = documentText.indexOf(shortened, fromIndex);
            if (position != -1) {
                // Verify this shortened version is unique in the document
                int secondOccurrence = documentText.indexOf(shortened, position + 1);
                if (secondOccurrence == -1) {
                    log.debug("Found {} anchor using shortened version '{}' for node '{}' at position {}", 
                            anchorType, shortened.substring(0, Math.min(30, shortened.length())), nodeTitle, position);
                    return position;
                } else {
                    log.debug("Shortened anchor '{}' for node '{}' appears multiple times, trying shorter version", 
                            shortened.substring(0, Math.min(30, shortened.length())), nodeTitle);
                }
            }
            
            // Try with newline normalization
            position = documentText.indexOf(shortenedNormalized, fromIndex);
            if (position != -1) {
                // Verify this shortened version is unique in the document
                int secondOccurrence = documentText.indexOf(shortenedNormalized, position + 1);
                if (secondOccurrence == -1) {
                    log.debug("Found {} anchor using shortened newline-normalized version '{}' for node '{}' at position {}", 
                            anchorType, shortenedNormalized.substring(0, Math.min(30, shortenedNormalized.length())), nodeTitle, position);
                    return position;
                } else {
                    log.debug("Shortened newline-normalized anchor '{}' for node '{}' appears multiple times, trying shorter version", 
                            shortenedNormalized.substring(0, Math.min(30, shortenedNormalized.length())), nodeTitle);
                }
            }
            
            // Try with whitespace normalization
            String docWhitespaceNormalized = normalizeWhitespace(documentText);
            String shortenedWhitespaceNormalized = normalizeWhitespace(shortened);
            
            int normalizedPosition = docWhitespaceNormalized.indexOf(shortenedWhitespaceNormalized, 
                    findNormalizedPosition(documentText, docWhitespaceNormalized, fromIndex));
            if (normalizedPosition != -1) {
                // Verify uniqueness in normalized text
                int secondOccurrence = docWhitespaceNormalized.indexOf(shortenedWhitespaceNormalized, normalizedPosition + 1);
                if (secondOccurrence == -1) {
                    int originalPosition = findOriginalPosition(documentText, docWhitespaceNormalized, normalizedPosition);
                    log.debug("Found {} anchor using shortened whitespace-normalized version '{}' for node '{}' at position {}", 
                            anchorType, shortened.substring(0, Math.min(30, shortened.length())), nodeTitle, originalPosition);
                    return originalPosition;
                }
            }
            
            // Try with quote unescaping
            String shortenedUnescaped = unescapeJsonQuotes(shortened);
            String docUnescaped = unescapeJsonQuotes(documentText);
            
            position = docUnescaped.indexOf(shortenedUnescaped, fromIndex);
            if (position != -1) {
                // Verify uniqueness
                int secondOccurrence = docUnescaped.indexOf(shortenedUnescaped, position + 1);
                if (secondOccurrence == -1) {
                    log.debug("Found {} anchor using shortened unescaped version '{}' for node '{}' at position {}", 
                            anchorType, shortened.substring(0, Math.min(30, shortened.length())), nodeTitle, position);
                    return position;
                }
            }
        }
        
        log.debug("No shortened anchor found for {} anchor '{}' for node '{}'", 
                anchorType, anchor.substring(0, Math.min(50, anchor.length())), nodeTitle);
        return -1;
    }

    /**
     * Fuzzy matching strategy: finds the longest matching substring of the anchor in the document.
     * This handles cases where AI generates anchors that are longer than the actual text.
     * 
     * @param documentText the full document text
     * @param anchor the original anchor text
     * @param nodeTitle the node title for logging
     * @param anchorType "start" or "end" for logging
     * @param fromIndex minimum position to search from
     * @return the position of the fuzzy match, or -1 if not found
     */
    private int findFuzzyMatch(String documentText, String anchor, String nodeTitle, String anchorType, int fromIndex) {
        // Normalize the anchor and document text
        String normalizedAnchor = normalizeWhitespace(unescapeJsonQuotes(anchor.replace("\\n", " ").replace("\n", " ")));
        String normalizedDoc = normalizeWhitespace(documentText);
        
        // Try case-insensitive versions
        String normalizedAnchorLower = normalizedAnchor.toLowerCase(java.util.Locale.ROOT);
        String normalizedDocLower = normalizedDoc.toLowerCase(java.util.Locale.ROOT);
        
        // Try to find overlapping substrings from different positions
        int minLength = 15; // Minimum meaningful length
        
        for (int length = Math.min(normalizedAnchor.length(), 80); length >= minLength; length -= 5) {
            // Try substring from start (original behavior)
            String substringFromStart = normalizedAnchor.substring(0, length);
            int position = tryFindSubstring(normalizedDoc, normalizedDocLower, substringFromStart, 
                    documentText, fromIndex, anchorType, nodeTitle, "from-start");
            if (position != -1) return position;
            
            // Try substring from end
            if (normalizedAnchor.length() > length) {
                String substringFromEnd = normalizedAnchor.substring(normalizedAnchor.length() - length);
                position = tryFindSubstring(normalizedDoc, normalizedDocLower, substringFromEnd, 
                        documentText, fromIndex, anchorType, nodeTitle, "from-end");
                if (position != -1) return position;
            }
            
            // Try substring from middle
            if (normalizedAnchor.length() > length + 10) {
                int startPos = (normalizedAnchor.length() - length) / 2;
                String substringFromMiddle = normalizedAnchor.substring(startPos, startPos + length);
                position = tryFindSubstring(normalizedDoc, normalizedDocLower, substringFromMiddle, 
                        documentText, fromIndex, anchorType, nodeTitle, "from-middle");
                if (position != -1) return position;
            }
        }
        
        // Try word-by-word matching for very difficult cases
        String[] anchorWords = normalizedAnchorLower.split("\\s+");
        if (anchorWords.length >= 3) {
            // Try matching first 3-5 words (case-insensitive)
            for (int wordCount = Math.min(anchorWords.length, 5); wordCount >= 3; wordCount--) {
                String wordSubstring = String.join(" ", Arrays.copyOf(anchorWords, wordCount));
                
                int position = normalizedDocLower.indexOf(wordSubstring, 
                        findNormalizedPosition(documentText, normalizedDocLower, fromIndex));
                
                if (position != -1) {
                    // Verify uniqueness
                    int secondOccurrence = normalizedDocLower.indexOf(wordSubstring, position + 1);
                    if (secondOccurrence == -1) {
                        int originalPosition = findOriginalPosition(documentText, normalizedDocLower, position);
                        log.debug("Found {} anchor using case-insensitive word-based fuzzy match '{}' ({} words) for node '{}' at position {}", 
                                anchorType, wordSubstring.substring(0, Math.min(30, wordSubstring.length())), 
                                wordCount, nodeTitle, originalPosition);
                        return originalPosition;
                    }
                }
            }
        }
        
        log.debug("No fuzzy match found for {} anchor '{}' for node '{}'", 
                anchorType, anchor.substring(0, Math.min(50, anchor.length())), nodeTitle);
        return -1;
    }

    /**
     * Helper method to try finding a substring with both case-sensitive and case-insensitive matching.
     */
    private int tryFindSubstring(String normalizedDoc, String normalizedDocLower, String substring, 
                                String documentText, int fromIndex, String anchorType, String nodeTitle, String strategy) {
        
        String substringLower = substring.toLowerCase(java.util.Locale.ROOT);
        
        // Try case-sensitive first
        int position = normalizedDoc.indexOf(substring, 
                findNormalizedPosition(documentText, normalizedDoc, fromIndex));
        
        if (position != -1) {
            // Verify uniqueness
            int secondOccurrence = normalizedDoc.indexOf(substring, position + 1);
            if (secondOccurrence == -1) {
                int originalPosition = findOriginalPosition(documentText, normalizedDoc, position);
                log.debug("Found {} anchor using fuzzy match {} '{}' for node '{}' at position {}", 
                        anchorType, strategy, substring.substring(0, Math.min(30, substring.length())), 
                        nodeTitle, originalPosition);
                return originalPosition;
            }
        }
        
        // Try case-insensitive
        position = normalizedDocLower.indexOf(substringLower, 
                findNormalizedPosition(documentText, normalizedDocLower, fromIndex));
        
        if (position != -1) {
            // Verify uniqueness in lowercase
            int secondOccurrence = normalizedDocLower.indexOf(substringLower, position + 1);
            if (secondOccurrence == -1) {
                int originalPosition = findOriginalPosition(documentText, normalizedDocLower, position);
                log.debug("Found {} anchor using case-insensitive fuzzy match {} '{}' for node '{}' at position {}", 
                        anchorType, strategy, substring.substring(0, Math.min(30, substring.length())), 
                        nodeTitle, originalPosition);
                return originalPosition;
            }
        }
        
        return -1;
    }

    /**
     * Finds the corresponding position in whitespace-normalized text.
     */
    private int findNormalizedPosition(String originalText, String normalizedText, int originalPosition) {
        if (originalPosition == 0) return 0;
        
        // Count characters up to originalPosition, tracking whitespace compression
        int normalizedPos = 0;
        boolean inWhitespace = false;
        
        for (int i = 0; i < Math.min(originalPosition, originalText.length()); i++) {
            char c = originalText.charAt(i);
            if (Character.isWhitespace(c)) {
                if (!inWhitespace) {
                    normalizedPos++; // First whitespace becomes a space
                    inWhitespace = true;
                }
                // Subsequent whitespace is collapsed, don't increment normalizedPos
            } else {
                normalizedPos++;
                inWhitespace = false;
            }
        }
        
        return Math.min(normalizedPos, normalizedText.length());
    }

    /**
     * Converts a position in whitespace-normalized text back to original text position.
     */
    private int findOriginalPosition(String originalText, String normalizedText, int normalizedPosition) {
        if (normalizedPosition == 0) return 0;
        
        int originalPos = 0;
        int normalizedPos = 0;
        boolean inWhitespace = false;
        
        while (originalPos < originalText.length() && normalizedPos < normalizedPosition) {
            char c = originalText.charAt(originalPos);
            if (Character.isWhitespace(c)) {
                if (!inWhitespace) {
                    normalizedPos++; // First whitespace counts as one position
                    inWhitespace = true;
                }
                // Skip additional whitespace
            } else {
                normalizedPos++;
                inWhitespace = false;
            }
            originalPos++;
        }
        
        return originalPos;
    }

    /**
     * Exception thrown when an anchor cannot be found in the document.
     */
    public static class AnchorNotFoundException extends RuntimeException {
        public AnchorNotFoundException(String message) {
            super(message);
        }
        
        public AnchorNotFoundException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
