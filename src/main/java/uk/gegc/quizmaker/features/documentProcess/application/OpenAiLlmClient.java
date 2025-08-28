package uk.gegc.quizmaker.features.documentProcess.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Service;
import uk.gegc.quizmaker.features.documentProcess.domain.model.DocumentNode;
import uk.gegc.quizmaker.shared.config.AiRateLimitConfig;

import java.util.List;
import java.util.stream.IntStream;

/**
 * OpenAI-based implementation of LlmClient for document structure generation.
 * Uses Spring AI ChatClient to interact with OpenAI's API.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OpenAiLlmClient implements LlmClient {

    private final ChatClient chatClient;
    private final AiRateLimitConfig rateLimitConfig;
    private final DocumentStructurePromptService promptService;

    @Override
    public List<DocumentNode> generateStructure(String text, StructureOptions options) {
        log.info("Generating structure for text with {} characters using model: {}", 
                text.length(), options.model());

        try {
            String prompt = promptService.buildStructurePrompt(text, options);
            
            int maxRetries = rateLimitConfig.getMaxRetries();
            int retryCount = 0;
            
            while (retryCount < maxRetries) {
                try {
                    // Use structured output with function calling
                    DocumentStructureRecords.DocumentStructureResponse response = chatClient.prompt()
                            .user(prompt)
                            .call()
                            .entity(DocumentStructureRecords.DocumentStructureResponse.class);

                    if (response == null || response.nodes() == null) {
                        throw new LlmException("No structured response received from AI service");
                    }

                    log.debug("AI structure response received, converting to DocumentNodes...");
                    return convertToDocumentNodes(response.nodes());

                } catch (Exception e) {
                    retryCount++;
                    if (retryCount >= maxRetries) {
                        throw new LlmException("Failed to generate structure after " + maxRetries + " retries", e);
                    }
                    
                    log.warn("Structure generation attempt {} failed, retrying: {}", retryCount, e.getMessage());
                    
                    try {
                        long delay = calculateBackoffDelay(retryCount);
                        Thread.sleep(delay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new LlmException("Structure generation interrupted", ie);
                    }
                }
            }
            
            throw new LlmException("Unexpected error: exceeded retry loop");
            
        } catch (LlmException e) {
            throw e;
        } catch (Exception e) {
            throw new LlmException("Unexpected error during structure generation", e);
        }
    }

    /**
     * Convert structured response nodes to DocumentNode entities.
     */
    private List<DocumentNode> convertToDocumentNodes(List<DocumentStructureRecords.StructureNode> structureNodes) {
        if (structureNodes.isEmpty()) {
            throw new LlmException("No nodes generated");
        }

        List<DocumentNode> nodes = IntStream.range(0, structureNodes.size())
                .mapToObj(i -> {
                    DocumentStructureRecords.StructureNode structureNode = structureNodes.get(i);
                    return convertToDocumentNode(structureNode, i);
                })
                .toList();

        // Validate basic structure
        validateStructure(nodes);
        
        log.info("Successfully converted {} nodes from structured response", nodes.size());
        return nodes;
    }

    /**
     * Convert a single structure node to DocumentNode entity.
     */
    private DocumentNode convertToDocumentNode(DocumentStructureRecords.StructureNode structureNode, int index) {
        // Validate required fields
        if (structureNode.title() == null || structureNode.title().trim().isEmpty()) {
            throw new LlmException("Node missing title at index " + index);
        }
        
        if (structureNode.startAnchor() == null || structureNode.startAnchor().trim().isEmpty()) {
            throw new LlmException("Node '" + structureNode.title() + "' missing start anchor");
        }
        
        if (structureNode.endAnchor() == null || structureNode.endAnchor().trim().isEmpty()) {
            throw new LlmException("Node '" + structureNode.title() + "' missing end anchor");
        }

        // Log warnings for invalid values (clamping will be handled in toDocumentNode)
        if (structureNode.depth() < 0) {
            log.warn("Negative depth {} for node '{}', will be clamped to 0", structureNode.depth(), structureNode.title());
        }

        double confidence = structureNode.confidence();
        if (Double.isNaN(confidence) || confidence < 0.0 || confidence > 1.0) {
            log.warn("Invalid confidence {} for node '{}', will be clamped to valid range", confidence, structureNode.title());
        }

        return structureNode.toDocumentNode(index);
    }

    /**
     * Validate basic structure requirements.
     */
    private void validateStructure(List<DocumentNode> nodes) {
        if (nodes.isEmpty()) {
            throw new LlmException("No nodes generated");
        }
        
        // Validate that all nodes have required anchor fields
        for (DocumentNode node : nodes) {
            if (node.getStartAnchor() == null || node.getStartAnchor().trim().isEmpty()) {
                throw new LlmException("Node missing start anchor: " + node.getTitle());
            }
            if (node.getEndAnchor() == null || node.getEndAnchor().trim().isEmpty()) {
                throw new LlmException("Node missing end anchor: " + node.getTitle());
            }
        }
        
        log.debug("Basic structure validation passed for {} nodes", nodes.size());
    }

    private long calculateBackoffDelay(int retryCount) {
        long baseDelay = rateLimitConfig.getBaseDelayMs();
        long maxDelay = rateLimitConfig.getMaxDelayMs();
        double jitterFactor = rateLimitConfig.getJitterFactor();
        
        // Exponential backoff with jitter
        long delay = Math.min(baseDelay * (1L << retryCount), maxDelay);
        double jitter = 1.0 + (Math.random() * 2 - 1) * jitterFactor;
        return Math.round(delay * jitter);
    }
}
