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

    public static ValidationResult validate(JsonNode contentNode, ValidationMode mode){
        ValidationResult baseResult = validate(contentNode);
        if(!baseResult.valid()){
            return baseResult;
        }

        JsonNode optionsNode = contentNode.get("options");
        if (optionsNode != null && !optionsNode.isArray()) {
            return ValidationResult.error("If present, 'options' must be an array");
        }

        boolean hasOptions = optionsNode != null;
        if(mode == ValidationMode.STRICT_AI && !hasOptions){
            return ValidationResult.error(
                    "AI-generated FILL_GAP must include 'options' array with correct answers + 6-7 distractors");
        }

        if(!hasOptions){
            return ValidationResult.success();
        }

        if (optionsNode.isEmpty()){
            return ValidationResult.error("Options array cannot be empty");
        }

        JsonNode gaps = contentNode.get("gaps");
        List<String> gapAnswers = new ArrayList<>();
        Set<String> gapAnswersNormalized = new HashSet<>();

        for(JsonNode gap : gaps){
            String answer = gap.get("answer").asText().trim();
            gapAnswers.add(answer);
            gapAnswersNormalized.add(answer.toLowerCase());
        }

        if(gapAnswersNormalized.size() < gapAnswers.size()){
            return ValidationResult.error(
                    "When options are provided, gap answers must be unique (case-insensitive). " +
                            "Found duplicates: " + findDuplicates(gapAnswers)
            );
        }

        List<String> options = new ArrayList<>();
        Set<String> optionsNormalized = new HashSet<>();

        for (JsonNode optionNode : optionsNode) {
            if (!optionNode.isTextual()) {
                return ValidationResult.error(
                        "All options must be strings, found: " + optionNode.getNodeType());
            }

            String option = optionNode.asText().trim();

            if (option.isBlank()) {
                return ValidationResult.error("Options cannot be blank");
            }

            String normalized = option.toLowerCase();
            if (!optionsNormalized.add(normalized)) {
                return ValidationResult.error(
                        "Options must be unique (case-insensitive). Duplicate: " + option);
            }

            options.add(option);
        }

        for(String gapAnswer : gapAnswers){
            if (!optionsNormalized.contains(gapAnswer.toLowerCase())) {
                return ValidationResult.error(
                        "Options must include all correct answers for drag-and-drop pool. " +
                                "Missing: '" + gapAnswer + "'. " +
                                "Add this to options array along with 6-7 distractors."
                );
            }
        }

        int numCorrectAnswers = gaps.size();
        int expectedMin = numCorrectAnswers + 6;
        int expectedMax = numCorrectAnswers + 7;
        int actualSize = options.size();

        if(actualSize < expectedMin || actualSize > expectedMax){
            return ValidationResult.error(
                    String.format(
                            "Options array should have %d-%d items (correct answers=%d + distractors=6-7), found %d. " +
                                    "Include all gap answers in options plus 6-7 additional distractors.",
                            expectedMin, expectedMax, numCorrectAnswers, actualSize
                    )
            );
        }

        int distractorCount = actualSize - numCorrectAnswers;
        if (distractorCount < 6) {
            return ValidationResult.error(
                    String.format(
                            "Not enough distractors. Found %d distractor(s), need 6-7. " +
                                    "Options currently has %d items (%d correct + %d distractors).",
                            distractorCount, actualSize, numCorrectAnswers, distractorCount
                    )
            );
        }

        return ValidationResult.success();

    }

    private static String findDuplicates(List<String> answers) {
        Map<String, List<String>> normalized = new HashMap<>();

        for (String answer : answers) {
            String key = answer.toLowerCase();
            normalized.computeIfAbsent(key, k -> new ArrayList<>()).add(answer);
        }

        return normalized.values().stream()
                .filter(strings -> strings.size() > 1)
                .map(strings -> String.join(", ", strings))
                .findFirst()
                .orElse("");
    }

    public enum ValidationMode{
        LENIENT,
        STRICT_AI
    }
}
