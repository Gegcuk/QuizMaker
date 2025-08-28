package uk.gegc.quizmaker.features.documentProcess.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for building AI prompts for document structure generation.
 * Follows the same pattern as the existing PromptTemplateService.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentStructurePromptService {

    private final ResourceLoader resourceLoader;
    private final ConcurrentHashMap<String, String> templateCache = new ConcurrentHashMap<>();

    /**
     * Builds a complete prompt for document structure generation.
     *
     * @param content the document content to analyze
     * @param options structure generation options
     * @return formatted prompt string
     */
    public String buildStructurePrompt(String content, LlmClient.StructureOptions options) {
        try {
            // Load system prompt
            String systemPrompt = loadPromptTemplate("document-structure/system-prompt.txt");

            // Load structure template
            String structureTemplate = loadPromptTemplate("document-structure/structure-template.txt");

            // Build the complete prompt
            StringBuilder prompt = new StringBuilder();
            prompt.append(systemPrompt).append("\n\n");
            prompt.append(structureTemplate);

            // Replace placeholders
            return prompt.toString()
                    .replace("{content}", content)
                    .replace("{profile}", options.profile())
                    .replace("{granularity}", options.granularity())
                    .replace("{charCount}", String.valueOf(content.length()));

        } catch (Exception e) {
            log.error("Error building structure prompt", e);
            // Fallback to simple prompt
                               return String.format("""
                           Analyze the following document and create a hierarchical structure.

                           Profile: %s
                           Granularity: %s
                           Document Length: %d characters

                           Document Content:
                           %s

                           Return a JSON object with the following structure:
                           {
                             "nodes": [
                               {
                                 "type": "SECTION|CHAPTER|PARAGRAPH|SUBSECTION|UTTERANCE|OTHER",
                                 "title": "Descriptive title for this section",
                                 "start_anchor": "exact text where this section starts (at least 10 characters)",
                                 "end_anchor": "exact text where this section ends (at least 10 characters)",
                                 "depth": 0,
                                 "confidence": 0.95
                               }
                             ]
                           }
                           """, options.profile(), options.granularity(), content.length(), content);
        }
    }

    /**
     * Loads a prompt template from resources.
     *
     * @param templateName the template file name
     * @return the template content
     */
    public String loadPromptTemplate(String templateName) {
        return templateCache.computeIfAbsent(templateName, this::loadTemplateFromResources);
    }

    /**
     * Loads a template from the classpath resources.
     *
     * @param templateName the template file name
     * @return the template content
     */
    private String loadTemplateFromResources(String templateName) {
        try {
            Resource resource = resourceLoader.getResource("classpath:prompts/" + templateName);
            return new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("Failed to load template: {}", templateName, e);
            throw new RuntimeException("Failed to load template: " + templateName, e);
        }
    }
}
