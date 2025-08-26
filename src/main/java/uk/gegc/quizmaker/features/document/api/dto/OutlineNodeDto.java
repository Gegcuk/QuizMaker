package uk.gegc.quizmaker.features.document.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import uk.gegc.quizmaker.features.document.domain.model.DocumentNode;

import java.util.List;

/**
 * DTO representing a node in the document outline structure
 * Used for structured output from LLM outline extraction
 */
public record OutlineNodeDto(
        @JsonProperty("type")
        String type,
        
        @JsonProperty("title")
        String title,
        
        @JsonProperty("start_anchor")
        String startAnchor,
        
        @JsonProperty("end_anchor")
        String endAnchor,
        
        @JsonProperty("children")
        List<OutlineNodeDto> children
) {
    
    public OutlineNodeDto {
        children = (children == null) ? List.of() : List.copyOf(children);
        // Don't silently coerce type - let validation catch invalid types
    }
    
    /**
     * Convert to DocumentNode.NodeType enum
     */
    public DocumentNode.NodeType getNodeType() {
        return switch (type.trim().toUpperCase()) {
            case "PART" -> DocumentNode.NodeType.PART;
            case "CHAPTER" -> DocumentNode.NodeType.CHAPTER;
            case "SECTION" -> DocumentNode.NodeType.SECTION;
            case "SUBSECTION" -> DocumentNode.NodeType.SUBSECTION;
            case "PARAGRAPH" -> DocumentNode.NodeType.PARAGRAPH;
            default -> DocumentNode.NodeType.OTHER;
        };
    }
    
    /**
     * Get the level based on node type
     */
    public int getLevel() {
        return switch (type.trim().toUpperCase()) {
            case "PART" -> 1;
            case "CHAPTER" -> 2;
            case "SECTION" -> 3;
            case "SUBSECTION" -> 4;
            case "PARAGRAPH" -> 5;
            default -> 0;
        };
    }
}
