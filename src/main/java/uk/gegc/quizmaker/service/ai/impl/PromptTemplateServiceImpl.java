package uk.gegc.quizmaker.service.ai.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.service.ai.PromptTemplateService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Implementation of PromptTemplateService for building AI prompts
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PromptTemplateServiceImpl implements PromptTemplateService {

    private final ResourceLoader resourceLoader;
    private final Map<String, String> templateCache = new ConcurrentHashMap<>();

    @Override
    public String buildPromptForChunk(
            String chunkContent,
            QuestionType questionType,
            int questionCount,
            Difficulty difficulty
    ) {
        // Input validation
        if (chunkContent == null) {
            throw new IllegalArgumentException("Chunk content cannot be null");
        }
        if (chunkContent.trim().isEmpty()) {
            throw new IllegalArgumentException("Chunk content cannot be empty");
        }
        if (questionCount < 0) {
            throw new IllegalArgumentException("Question count cannot be negative");
        }
        if (difficulty == null) {
            throw new IllegalArgumentException("Difficulty cannot be null");
        }
        if (questionType == null) {
            throw new IllegalArgumentException("Question type cannot be null");
        }

        try {
            // Load system prompt
            String systemPrompt = buildSystemPrompt();

            // Load context template
            String contextTemplate = loadPromptTemplate("base/context-template.txt");

            // Load question type specific template
            String questionTemplate = loadPromptTemplate("question-types/" + getQuestionTypeTemplateName(questionType));

            // Build the complete prompt
            StringBuilder prompt = new StringBuilder();
            prompt.append(systemPrompt).append("\n\n");
            prompt.append(contextTemplate).append("\n\n");
            prompt.append(questionTemplate);

            // Replace placeholders
            String finalPrompt = prompt.toString()
                    .replace("{content}", chunkContent)
                    .replace("{questionType}", questionType.name())
                    .replace("{questionCount}", String.valueOf(questionCount))
                    .replace("{difficulty}", difficulty.name());

            return finalPrompt;

        } catch (Exception e) {
            log.error("Error building prompt for question type: {}", questionType, e);
            // Fallback to simple prompt
            return String.format("""
                    Generate %d %s questions with %s difficulty based on the following content:
                    
                    %s
                    
                    Please provide the questions in JSON format.
                    """, questionCount, questionType, difficulty, chunkContent);
        }
    }

    @Override
    public String loadPromptTemplate(String templateName) {
        return templateCache.computeIfAbsent(templateName, this::loadTemplateFromResources);
    }

    @Override
    public String buildSystemPrompt() {
        return loadPromptTemplate("base/system-prompt.txt");
    }

    private String loadTemplateFromResources(String templateName) {
        try {
            Resource resource = resourceLoader.getResource("classpath:prompts/" + templateName);
            return new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("Failed to load template: {}", templateName, e);
            throw new RuntimeException("Failed to load template: " + templateName, e);
        }
    }

    private String getQuestionTypeTemplateName(QuestionType questionType) {
        return switch (questionType) {
            case MCQ_SINGLE -> "mcq-single.txt";
            case MCQ_MULTI -> "mcq-multi.txt";
            case TRUE_FALSE -> "true-false.txt";
            case OPEN -> "open-question.txt";
            case FILL_GAP -> "fill-gap.txt";
            case ORDERING -> "ordering.txt";
            case COMPLIANCE -> "compliance.txt";
            case HOTSPOT -> "hotspot.txt";
            case MATCHING -> "matching.txt";
        };
    }
} 