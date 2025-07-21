package uk.gegc.quizmaker.service.ai.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import uk.gegc.quizmaker.model.question.Difficulty;
import uk.gegc.quizmaker.model.question.QuestionType;
import uk.gegc.quizmaker.service.ai.PromptTemplateService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Implementation of PromptTemplateService for building AI prompts
 */
@Service
@Slf4j
public class PromptTemplateServiceImpl implements PromptTemplateService {

    private final Map<String, String> templateCache = new HashMap<>();

    @Override
    public String buildPromptForChunk(
            String chunkContent,
            QuestionType questionType,
            int questionCount,
            Difficulty difficulty
    ) {
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
            ClassPathResource resource = new ClassPathResource("prompts/" + templateName);
            return new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("Failed to load template: {}", templateName, e);
            return "Template not found: " + templateName;
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
            case HOTSPOT -> "mcq-single.txt"; // Fallback to MCQ for hotspot
        };
    }
} 