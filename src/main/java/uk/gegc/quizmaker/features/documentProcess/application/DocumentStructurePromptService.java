package uk.gegc.quizmaker.features.documentProcess.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import uk.gegc.quizmaker.features.documentProcess.domain.model.DocumentNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
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
        return buildStructurePrompt(content, options, null, 0, 1);
    }

    /**
     * Builds a context-aware prompt for document structure generation with chunk information.
     *
     * @param content the document content to analyze
     * @param options structure generation options
     * @param previousNodes previously generated nodes for context
     * @param chunkIndex current chunk index (0-based)
     * @param totalChunks total number of chunks
     * @return formatted prompt string
     */
    public String buildStructurePrompt(String content, LlmClient.StructureOptions options, 
                                     List<DocumentNode> previousNodes, 
                                     int chunkIndex, int totalChunks) {
        try {
            // Choose appropriate prompt template based on whether we have context
            String templateName = (previousNodes != null && !previousNodes.isEmpty()) 
                ? "document-structure/system-prompt-chunked.txt"
                : "document-structure/system-prompt.txt";
            
            // Load system prompt
            String systemPrompt = loadPromptTemplate(templateName);

            // Build the complete prompt
            StringBuilder prompt = new StringBuilder();
            prompt.append(systemPrompt);

            // Replace placeholders
            String result = prompt.toString()
                    .replace("{content}", content)
                    .replace("{profile}", options.profile())
                    .replace("{granularity}", options.granularity())
                    .replace("{charCount}", String.valueOf(content.length()))
                    .replace("{chunkIndex}", String.valueOf(chunkIndex + 1))
                    .replace("{totalChunks}", String.valueOf(totalChunks));

            // Add previous structure context if available (limit to last 10 nodes to avoid overwhelming)
            if (previousNodes != null && !previousNodes.isEmpty()) {
                StringBuilder previousStructure = new StringBuilder();
                int startIndex = Math.max(0, previousNodes.size() - 10); // Show only last 10 nodes
                for (int i = startIndex; i < previousNodes.size(); i++) {
                    DocumentNode node = previousNodes.get(i);
                    previousStructure.append("- ").append(node.getTitle())
                                   .append(" (depth: ").append(node.getDepth()).append(")\n");
                }
                if (previousNodes.size() > 10) {
                    previousStructure.insert(0, "... and ").insert(0, String.valueOf(previousNodes.size() - 10))
                                   .insert(0, "(").append(" more nodes)\n");
                }
                result = result.replace("{previousStructure}", previousStructure.toString());
            } else {
                result = result.replace("{previousStructure}", "None (first chunk)");
            }

            return result;

        } catch (Exception e) {
            log.error("Error building structure prompt", e);
            // Fallback to simple prompt
            return String.format("""
                Analyze the following document chunk and create a hierarchical structure.

                Profile: %s
                Granularity: %s
                Chunk Length: %d characters
                Chunk Position: %d of %d

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
                """, options.profile(), options.granularity(), content.length(), 
                     chunkIndex + 1, totalChunks, content);
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
