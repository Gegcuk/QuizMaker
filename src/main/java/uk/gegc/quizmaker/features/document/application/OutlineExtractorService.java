package uk.gegc.quizmaker.features.document.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import uk.gegc.quizmaker.features.document.api.dto.DocumentOutlineDto;
import uk.gegc.quizmaker.features.document.api.dto.OutlineNodeDto;
import uk.gegc.quizmaker.features.document.domain.model.DocumentNode;
import uk.gegc.quizmaker.shared.exception.AIResponseParseException;
import uk.gegc.quizmaker.shared.exception.AiServiceException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Service for extracting document outlines using LLM with structured outputs
 * Implements Day 4 of the chunk processing improvement plan
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OutlineExtractorService {

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;

    private static final String OUTLINE_PROMPT_TEMPLATE_PATH = "prompts/outline-extraction.txt";
    private static final int MAX_OUTLINE_DEPTH = 4;
    private static final int MAX_NODES_PER_LEVEL = 50;
    private static final int MAX_TOTAL_NODES = 1000;

    /**
     * Extract document outline from text content using LLM
     * 
     * @param documentContent The document text content to analyze
     * @return DocumentOutlineDto containing the hierarchical structure
     * @throws AiServiceException if AI service fails
     * @throws AIResponseParseException if response parsing fails
     */
    public DocumentOutlineDto extractOutline(String documentContent) {
        return extractOutlineWithDepth(documentContent, MAX_OUTLINE_DEPTH);
    }

    /**
     * Extract document outline with a maximum depth hint for the model and validation.
     */
    public DocumentOutlineDto extractOutlineWithDepth(String documentContent, int maxDepth) {
        if (documentContent == null || documentContent.trim().isEmpty()) {
            throw new IllegalArgumentException("Document content cannot be null or empty");
        }

        log.info("Extracting outline from document content (length: {} chars)", documentContent.length());

        try {
            // Configure ObjectMapper to be forgiving for unknown properties
            objectMapper.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            
            // Load prompt template
            String promptTemplate = loadPromptTemplate();
            
            // Build the complete prompt
            String prompt = buildPrompt(promptTemplate, documentContent, maxDepth);
            
            // Call AI service
            ChatResponse response = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .chatResponse();

            if (response == null || response.getResult() == null) {
                throw new AiServiceException("No response received from AI service");
            }

            String aiResponse = response.getResult().getOutput().getText();
            
            if (aiResponse == null || aiResponse.trim().isEmpty()) {
                throw new AiServiceException("Empty response received from AI service");
            }

            // Log first 200 chars of response for debugging (redact if needed)
            String responsePreview = aiResponse.length() > 200 ? 
                aiResponse.substring(0, 200) + "..." : aiResponse;
            log.debug("AI response received (preview: {}), parsing outline structure", responsePreview);

            // Parse the structured response
            DocumentOutlineDto outline = parseOutlineResponse(aiResponse);
            
            // Validate the outline structure against the source text
            validateOutline(outline, documentContent, maxDepth);
            
            // Check total node count to prevent explosion
            int totalNodes = countTotalNodes(outline);
            if (totalNodes > MAX_TOTAL_NODES) {
                throw new AIResponseParseException("Outline too large: " + totalNodes + " nodes (max: " + MAX_TOTAL_NODES + ")");
            }
            
            log.info("Successfully extracted outline with {} root nodes", outline.nodes().size());
            
            return outline;

        } catch (Exception e) {
            log.error("Failed to extract document outline", e);
            if (e instanceof AiServiceException || e instanceof AIResponseParseException) {
                throw e;
            }
            throw new AiServiceException("Failed to extract document outline: " + e.getMessage(), e);
        }
    }

    /**
     * Load the prompt template from resources
     */
    private String loadPromptTemplate() {
        try {
            ClassPathResource resource = new ClassPathResource(OUTLINE_PROMPT_TEMPLATE_PATH);
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AiServiceException("Failed to load outline prompt template", e);
        }
    }

    /**
     * Build the complete prompt with document content
     */
    private String buildPrompt(String template, String documentContent, int maxDepth) {
        // Provide an explicit instruction about the desired outline depth to reduce tokens
        String depthHint = "\n\nCONSTRAINTS:\n- Max outline depth: " + Math.max(1, Math.min(MAX_OUTLINE_DEPTH, maxDepth)) +
                " (e.g., up to CHAPTER for 2, up to SECTION for 3).";
        return template + depthHint + "\n\nDOCUMENT CONTENT:\n" + documentContent;
    }

    /**
     * Parse the AI response into DocumentOutlineDto
     */
    private DocumentOutlineDto parseOutlineResponse(String aiResponse) throws AIResponseParseException {
        try {
            // Clean the response - remove any markdown formatting
            String cleanedResponse = cleanAIResponse(aiResponse);
            
            // Extract JSON from the response
            String jsonContent = extractJsonFromResponse(cleanedResponse);
            
            // Try to parse as DocumentOutlineDto first
            try {
                // Handle common wrapper patterns
                com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(jsonContent);
                if (root.has("outline")) {
                    root = root.get("outline");
                }
                if (root.has("nodes")) {
                    DocumentOutlineDto outline = objectMapper.treeToValue(root, DocumentOutlineDto.class);
                    if (outline != null && outline.nodes() != null) {
                        return outline;
                    }
                }
            } catch (Exception e) {
                log.debug("Failed to parse as DocumentOutlineDto, trying single node fallback");
            }
            
            // Try to parse as single node and wrap it
            try {
                OutlineNodeDto singleNode = objectMapper.readValue(jsonContent, OutlineNodeDto.class);
                if (singleNode != null) {
                    return new DocumentOutlineDto(List.of(singleNode));
                }
            } catch (Exception e) {
                log.debug("Failed to parse as single node");
            }
            
            throw new AIResponseParseException("Invalid outline structure - could not parse as outline or single node");
            
        } catch (Exception e) {
            log.error("Failed to parse AI response as outline", e);
            throw new AIResponseParseException("Failed to parse outline response: " + e.getMessage(), e);
        }
    }

    /**
     * Clean AI response by removing markdown formatting
     */
    private String cleanAIResponse(String response) {
        return response.replaceAll("```json\\s*", "")
                      .replaceAll("```\\s*", "")
                      .trim();
    }

    /**
     * Extract JSON content from AI response with quote-aware parsing
     */
    private String extractJsonFromResponse(String response) throws AIResponseParseException {
        // Try to find JSON object first
        int startBrace = response.indexOf('{');
        
        if (startBrace != -1) {
            return extractJsonObject(response, startBrace);
        }
        
        // If no object, try array fallback
        int startBracket = response.indexOf('[');
        if (startBracket != -1) {
            String arrayJson = extractJsonArray(response, startBracket);
            // Wrap array as nodes
            return "{\"nodes\":" + arrayJson + "}";
        }
        
        throw new AIResponseParseException("No JSON object or array found in response");
    }
    
    /**
     * Extract JSON object with quote-aware parsing
     */
    private String extractJsonObject(String s, int start) throws AIResponseParseException {
        int depth = 0;
        boolean inString = false;
        boolean escaping = false;

        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inString) {
                if (escaping) {
                    escaping = false;
                } else if (c == '\\') {
                    escaping = true;
                } else if (c == '"') {
                    inString = false;
                }
            } else {
                if (c == '"') inString = true;
                else if (c == '{') depth++;
                else if (c == '}') {
                    depth--;
                    if (depth == 0) return s.substring(start, i + 1);
                }
            }
        }
        throw new AIResponseParseException("Invalid JSON structure - unmatched braces");
    }
    
    /**
     * Extract JSON array with quote-aware parsing
     */
    private String extractJsonArray(String s, int start) throws AIResponseParseException {
        int depth = 0;
        boolean inString = false;
        boolean escaping = false;

        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inString) {
                if (escaping) {
                    escaping = false;
                } else if (c == '\\') {
                    escaping = true;
                } else if (c == '"') {
                    inString = false;
                }
            } else {
                if (c == '"') inString = true;
                else if (c == '[') depth++;
                else if (c == ']') {
                    depth--;
                    if (depth == 0) return s.substring(start, i + 1);
                }
            }
        }
        throw new AIResponseParseException("Invalid JSON structure - unmatched brackets");
    }

    /**
     * Count total nodes in the outline
     */
    private int countTotalNodes(DocumentOutlineDto outline) {
        return outline.nodes().stream()
                .mapToInt(this::countNodes)
                .sum();
    }
    
    /**
     * Count nodes in a single node tree
     */
    private int countNodes(OutlineNodeDto node) {
        return 1 + node.children().stream()
                .mapToInt(this::countNodes)
                .sum();
    }

    /**
     * Validate the extracted outline structure against source text
     */
    private void validateOutline(DocumentOutlineDto outline, String doc, int maxDepth) throws AIResponseParseException {
        if (outline.nodes().isEmpty()) {
            throw new AIResponseParseException("Outline contains no nodes");
        }

        // Validate each node recursively
        for (OutlineNodeDto node : outline.nodes()) {
            validateNode(node, 0, doc, Math.max(1, Math.min(MAX_OUTLINE_DEPTH, maxDepth)));
        }
    }

    /**
     * Validate a single node and its children recursively
     */
    private void validateNode(OutlineNodeDto node, int depth, String doc, int maxDepth) throws AIResponseParseException {
        if (depth >= maxDepth) {
            throw new AIResponseParseException("Outline depth exceeds maximum allowed depth: " + maxDepth);
        }

        // Validate required fields
        if (node.type() == null || node.type().trim().isEmpty()) {
            throw new AIResponseParseException("Node type is required");
        }

        if (node.title() == null || node.title().trim().isEmpty()) {
            throw new AIResponseParseException("Title is required");
        }

        if (node.startAnchor() == null || node.startAnchor().trim().isEmpty()) {
            throw new AIResponseParseException("Start anchor is required for node: " + node.title());
        }

        if (node.endAnchor() == null || node.endAnchor().trim().isEmpty()) {
            throw new AIResponseParseException("End anchor is required for node: " + node.title());
        }

        // Validate node type - don't allow OTHER type
        DocumentNode.NodeType nodeType = node.getNodeType();
        if (nodeType == DocumentNode.NodeType.OTHER) {
            throw new AIResponseParseException("Invalid node type: " + node.type());
        }

        // Validate anchor length (2-15 words for robustness)
        if (!wordCountBetween(node.startAnchor(), 2, 15) || !wordCountBetween(node.endAnchor(), 2, 15)) {
            throw new AIResponseParseException("Anchors must be 2–15 words: " + node.title());
        }

        // Validate anchors appear in document and are ordered correctly
        int startIdx = indexOrFail(doc, node.startAnchor(), 0, "start", node.title());
        int endIdx = indexOrFail(doc, node.endAnchor(), startIdx, "end", node.title());
        if (endIdx < startIdx) {
            throw new AIResponseParseException("End anchor precedes start anchor: " + node.title());
        }

        // Validate children
        if (node.children() != null) {
            if (node.children().size() > MAX_NODES_PER_LEVEL) {
                throw new AIResponseParseException("Too many children for node: " + node.title());
            }
            
            for (OutlineNodeDto child : node.children()) {
                validateNode(child, depth + 1, doc, maxDepth);
            }
        }
    }

    /**
     * Check if a string has word count between min and max (inclusive)
     */
    private boolean wordCountBetween(String s, int min, int max) {
        int wordCount = (int) java.util.Arrays.stream(s.trim().split("\\s+"))
                .filter(w -> !w.isEmpty())
                .count();
        return wordCount >= min && wordCount <= max;
    }

    /**
     * Find anchor in document or throw exception
     */
    private int indexOrFail(String doc, String anchor, String kind, String title) throws AIResponseParseException {
        int index = indexOfIgnoreCase(doc, anchor, 0);
        if (index < 0) {
            throw new AIResponseParseException("Cannot find " + kind + " anchor in document for: " + title);
        }
        return index;
    }

    /**
     * Find anchor in document starting from specific index or throw exception
     */
    private int indexOrFail(String doc, String anchor, int fromIndex, String kind, String title) throws AIResponseParseException {
        int index = indexOfIgnoreCase(doc, anchor, fromIndex);
        if (index < 0) {
            throw new AIResponseParseException("Cannot find " + kind + " anchor in document for: " + title);
        }
        return index;
    }

    /**
     * Case-insensitive string search
     */
    private int indexOfIgnoreCase(String haystack, String needle, int fromIndex) {
        return haystack.toLowerCase().indexOf(needle.toLowerCase(), Math.max(0, fromIndex));
    }
}
