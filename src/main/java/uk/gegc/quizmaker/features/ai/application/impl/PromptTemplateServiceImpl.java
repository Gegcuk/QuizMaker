package uk.gegc.quizmaker.features.ai.application.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import uk.gegc.quizmaker.features.ai.application.PromptTemplateService;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Implementation of PromptTemplateService for building AI prompts
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PromptTemplateServiceImpl implements PromptTemplateService {

    private static final List<String> RENDERED_PLACEHOLDERS = List.of(
            "{questionType}",
            "{questionCount}",
            "{difficulty}",
            "{language}"
    );
    private static final List<String> KNOWN_PLACEHOLDERS = List.of(
            "{content}",
            "{questionType}",
            "{questionCount}",
            "{difficulty}",
            "{language}"
    );

    private final ResourceLoader resourceLoader;
    private final Map<String, String> templateCache = new ConcurrentHashMap<>();

    @Override
    public String buildPromptForChunk(
            String chunkContent,
            QuestionType questionType,
            int questionCount,
            Difficulty difficulty,
            String targetLanguage
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

        String language = (targetLanguage == null || targetLanguage.isBlank()) ? "en" : targetLanguage.trim();

        Map<String, String> variables = Map.of(
                "{questionType}", questionType.name(),
                "{questionCount}", String.valueOf(questionCount),
                "{difficulty}", difficulty.name(),
                "{language}", language
        );

        String context = renderTrustedTemplate(
                loadPromptTemplate("base/context-template.txt"),
                variables,
                "base/context-template.txt");
        String questionContract = renderTrustedTemplate(
                loadPromptTemplate("question-types/" + getQuestionTypeTemplateName(questionType)),
                variables,
                "question type " + questionType);
        SourceDelimiters delimiters = createSourceDelimiters(chunkContent);

        return context
                + "\n\nQUESTION TYPE CONTRACT:\n"
                + questionContract
                + "\n\nUNTRUSTED DOCUMENT SOURCE:\n"
                + delimiters.start()
                + "\n"
                + chunkContent
                + "\n"
                + delimiters.end();
    }

    @Override
    public String loadPromptTemplate(String templateName) {
        return templateCache.computeIfAbsent(templateName, this::loadTemplateFromResources);
    }

    @Override
    public String buildSystemPrompt() {
        String systemPrompt = loadPromptTemplate("base/system-prompt.txt");
        assertNoKnownPlaceholders(systemPrompt, "base/system-prompt.txt");
        return systemPrompt;
    }

    private String renderTrustedTemplate(
            String template,
            Map<String, String> variables,
            String templateName
    ) {
        String rendered = template;
        for (String placeholder : RENDERED_PLACEHOLDERS) {
            rendered = rendered.replace(placeholder, variables.get(placeholder));
        }
        assertNoKnownPlaceholders(rendered, templateName);
        return rendered;
    }

    private void assertNoKnownPlaceholders(String renderedTemplate, String templateName) {
        for (String placeholder : KNOWN_PLACEHOLDERS) {
            if (renderedTemplate.contains(placeholder)) {
                throw new IllegalStateException(
                        "Unresolved required prompt placeholder in " + templateName + ": " + placeholder);
            }
        }
    }

    private SourceDelimiters createSourceDelimiters(String sourceContent) {
        SourceDelimiters delimiters;
        do {
            String boundaryId = UUID.randomUUID().toString();
            delimiters = new SourceDelimiters(
                    "<<<QUIZMAKER_UNTRUSTED_SOURCE_" + boundaryId + "_START>>>",
                    "<<<QUIZMAKER_UNTRUSTED_SOURCE_" + boundaryId + "_END>>>");
        } while (sourceContent.contains(delimiters.start()) || sourceContent.contains(delimiters.end()));
        return delimiters;
    }

    private String loadTemplateFromResources(String templateName) {
        try {
            Resource resource = resourceLoader.getResource("classpath:prompts/" + templateName);
            return new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("Failed to load prompt template: {}", templateName);
            throw new IllegalStateException("Failed to load prompt template: " + templateName);
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

    private record SourceDelimiters(String start, String end) {
    }
}
