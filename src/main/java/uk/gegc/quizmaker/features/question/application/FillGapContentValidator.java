package uk.gegc.quizmaker.features.question.application;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Centralized validation logic for FILL_GAP question content.
 * Validates that:
 * - Text contains {N} placeholders
 * - Gap IDs are sequential integers starting from 1
 * - All placeholders have corresponding gaps
 * - All gaps are referenced in text
 * 
 * Used by:
 * - FillGapHandler (user-created questions)
 * - FillGapQuestionParser (AI-generated questions)
 * - SpringAiStructuredClient (structured AI output)
 */
public class FillGapContentValidator {
    
    private static final Pattern GAP_PATTERN = Pattern.compile("\\{(\\d+)\\}");
    
    /**
     * Validation result containing either success or detailed error
     */
    public record ValidationResult(boolean valid, String errorMessage) {
        public static ValidationResult success() {
            return new ValidationResult(true, null);
        }
        
        public static ValidationResult error(String message) {
            return new ValidationResult(false, message);
        }
    }
    
    /**
     * Validates FILL_GAP content structure and gap/text consistency.
     * Returns ValidationResult with specific error message if invalid.
     */
    public static ValidationResult validate(JsonNode contentNode) {
        // Basic structure validation
        if (contentNode == null || !contentNode.isObject()) {
            return ValidationResult.error("Content must be a JSON object");
        }
        
        JsonNode textNode = contentNode.get("text");
        if (textNode == null || textNode.asText().isBlank()) {
            return ValidationResult.error("FILL_GAP requires non-empty 'text' field");
        }
        
        JsonNode gapsNode = contentNode.get("gaps");
        if (gapsNode == null || !gapsNode.isArray() || gapsNode.isEmpty()) {
            return ValidationResult.error("FILL_GAP must have at least one gap defined");
        }
        
        String text = textNode.asText();
        
        // Collect and validate gap IDs from gaps array
        Set<Integer> gapIds = new HashSet<>();
        for (JsonNode gap : gapsNode) {
            if (!gap.has("id") || !gap.has("answer")) {
                return ValidationResult.error("Each gap must have an 'id' and 'answer'");
            }
            if (gap.get("answer").asText().isBlank()) {
                return ValidationResult.error("Gap answer cannot be blank");
            }
            if (!gap.get("id").canConvertToInt()) {
                return ValidationResult.error("Gap 'id' must be an integer");
            }
            
            int id = gap.get("id").asInt();
            if (id < 1) {
                return ValidationResult.error("Gap IDs must be positive integers starting from 1, found: " + id);
            }
            if (gapIds.contains(id)) {
                return ValidationResult.error("Gap IDs must be unique, found duplicate ID: " + id);
            }
            gapIds.add(id);
        }
        
        // Validate IDs are sequential (1, 2, 3...)
        List<Integer> sortedIds = gapIds.stream().sorted().toList();
        for (int i = 0; i < sortedIds.size(); i++) {
            int expectedId = i + 1;
            if (!sortedIds.get(i).equals(expectedId)) {
                return ValidationResult.error(
                    "Gap IDs must be sequential integers starting from 1. Expected id=" + expectedId +
                    " but found id=" + sortedIds.get(i)
                );
            }
        }
        
        // Extract gap IDs from text using {N} pattern
        Matcher matcher = GAP_PATTERN.matcher(text);
        Set<Integer> textGapIds = new HashSet<>();
        
        while (matcher.find()) {
            int gapId = Integer.parseInt(matcher.group(1));
            textGapIds.add(gapId);
            
            // Check if this gap ID exists in gaps array
            if (!gapIds.contains(gapId)) {
                return ValidationResult.error(
                    "Gap {" + gapId + "} found in text but no corresponding gap with id=" + gapId + " in gaps array"
                );
            }
        }
        
        // Verify at least one gap in text
        if (textGapIds.isEmpty()) {
            return ValidationResult.error(
                "No gaps found in text. Use {N} format (e.g., {1}, {2}) to mark gap positions"
            );
        }
        
        // Check all gaps are used in text
        for (Integer gapId : gapIds) {
            if (!textGapIds.contains(gapId)) {
                return ValidationResult.error(
                    "Gap with id=" + gapId + " defined in gaps array but {" + gapId + "} not found in text"
                );
            }
        }
        
        return ValidationResult.success();
    }
}

