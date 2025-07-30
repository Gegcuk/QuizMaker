package uk.gegc.quizmaker.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility class to analyze logged AI responses and identify patterns of non-compliance
 */
@Component
@Slf4j
public class AiResponseAnalyzer {

    private static final Pattern RESPONSE_START_PATTERN = Pattern.compile("=== AI RESPONSE START ===");
    private static final Pattern RESPONSE_END_PATTERN = Pattern.compile("=== AI RESPONSE END ===");
    private static final Pattern QUESTION_TYPE_PATTERN = Pattern.compile("Question Type: (.+)");
    private static final Pattern EXPECTED_COUNT_PATTERN = Pattern.compile("Expected Count: (\\d+)");
    private static final Pattern DIFFICULTY_PATTERN = Pattern.compile("Difficulty: (.+)");

    /**
     * Analyze AI responses from the most recent log file
     */
    public void analyzeRecentResponses() {
        try {
            Path logsDir = Paths.get("logs");
            if (!Files.exists(logsDir)) {
                log.info("No logs directory found. No AI responses to analyze.");
                return;
            }

            // Find the most recent AI response log file
            Path latestLogFile = findLatestAiResponseLog(logsDir);
            if (latestLogFile == null) {
                log.info("No AI response log files found.");
                return;
            }

            analyzeLogFile(latestLogFile);

        } catch (Exception e) {
            log.error("Error analyzing AI responses", e);
        }
    }

    /**
     * Analyze a specific log file
     */
    public void analyzeLogFile(Path logFile) throws IOException {
        log.info("Analyzing AI responses from: {}", logFile);
        
        List<String> lines = Files.readAllLines(logFile);
        List<AiResponse> responses = parseResponses(lines);
        
        log.info("Found {} AI responses to analyze", responses.size());
        
        // Analyze each response
        for (AiResponse response : responses) {
            analyzeResponse(response);
        }
        
        // Generate summary
        generateSummary(responses);
    }

    private Path findLatestAiResponseLog(Path logsDir) throws IOException {
        return Files.list(logsDir)
                .filter(path -> path.getFileName().toString().startsWith("ai-responses_"))
                .filter(path -> path.getFileName().toString().endsWith(".log"))
                .max(Path::compareTo)
                .orElse(null);
    }

    private List<AiResponse> parseResponses(List<String> lines) {
        List<AiResponse> responses = new ArrayList<>();
        AiResponse currentResponse = null;
        StringBuilder responseContent = new StringBuilder();
        boolean inResponse = false;

        for (String line : lines) {
            if (RESPONSE_START_PATTERN.matcher(line).find()) {
                inResponse = true;
                currentResponse = new AiResponse();
                responseContent = new StringBuilder();
            } else if (RESPONSE_END_PATTERN.matcher(line).find()) {
                if (currentResponse != null) {
                    currentResponse.setContent(responseContent.toString().trim());
                    responses.add(currentResponse);
                }
                inResponse = false;
                currentResponse = null;
            } else if (inResponse && currentResponse != null) {
                // Parse metadata
                Matcher questionTypeMatcher = QUESTION_TYPE_PATTERN.matcher(line);
                if (questionTypeMatcher.find()) {
                    currentResponse.setQuestionType(questionTypeMatcher.group(1));
                }

                Matcher expectedCountMatcher = EXPECTED_COUNT_PATTERN.matcher(line);
                if (expectedCountMatcher.find()) {
                    currentResponse.setExpectedCount(Integer.parseInt(expectedCountMatcher.group(1)));
                }

                Matcher difficultyMatcher = DIFFICULTY_PATTERN.matcher(line);
                if (difficultyMatcher.find()) {
                    currentResponse.setDifficulty(difficultyMatcher.group(1));
                }

                // Add to response content if it's not a metadata line
                if (!line.startsWith("Question Type:") && 
                    !line.startsWith("Expected Count:") && 
                    !line.startsWith("Difficulty:") &&
                    !line.startsWith("Response:")) {
                    responseContent.append(line).append("\n");
                } else if (line.startsWith("Response:")) {
                    // Extract the actual response content
                    String responseText = line.substring("Response:".length()).trim();
                    responseContent.append(responseText).append("\n");
                }
            }
        }

        return responses;
    }

    private void analyzeResponse(AiResponse response) {
        log.info("=== Analyzing Response ===");
        log.info("Question Type: {}", response.getQuestionType());
        log.info("Expected Count: {}", response.getExpectedCount());
        log.info("Difficulty: {}", response.getDifficulty());
        
        // Check for common issues
        List<String> issues = new ArrayList<>();
        
        // Check if response contains JSON
        if (!response.getContent().contains("{") || !response.getContent().contains("}")) {
            issues.add("No JSON structure found");
        }
        
        // Check if response contains markdown formatting
        if (response.getContent().contains("```") || response.getContent().contains("#")) {
            issues.add("Contains markdown formatting (should be pure JSON)");
        }
        
        // Check if response is too short
        if (response.getContent().length() < 50) {
            issues.add("Response too short");
        }
        
        // Check if response contains explanations outside JSON
        if (response.getContent().contains("Here are") || response.getContent().contains("I've created")) {
            issues.add("Contains explanatory text outside JSON");
        }
        
        if (!issues.isEmpty()) {
            log.warn("Issues found: {}", issues);
            log.warn("Response content: {}", response.getContent());
        } else {
            log.info("Response appears to follow instructions correctly");
        }
        
        log.info("=== End Analysis ===");
    }

    private void generateSummary(List<AiResponse> responses) {
        log.info("=== SUMMARY ===");
        log.info("Total responses analyzed: {}", responses.size());
        
        // Count by question type
        responses.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        AiResponse::getQuestionType,
                        java.util.stream.Collectors.counting()))
                .forEach((type, count) -> log.info("{}: {} responses", type, count));
        
        // Count by difficulty
        responses.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        AiResponse::getDifficulty,
                        java.util.stream.Collectors.counting()))
                .forEach((difficulty, count) -> log.info("Difficulty {}: {} responses", difficulty, count));
        
        log.info("=== END SUMMARY ===");
    }

    /**
     * Data class to hold parsed AI response information
     */
    private static class AiResponse {
        private String questionType;
        private Integer expectedCount;
        private String difficulty;
        private String content;

        // Getters and setters
        public String getQuestionType() { return questionType; }
        public void setQuestionType(String questionType) { this.questionType = questionType; }
        
        public Integer getExpectedCount() { return expectedCount; }
        public void setExpectedCount(Integer expectedCount) { this.expectedCount = expectedCount; }
        
        public String getDifficulty() { return difficulty; }
        public void setDifficulty(String difficulty) { this.difficulty = difficulty; }
        
        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
    }
} 