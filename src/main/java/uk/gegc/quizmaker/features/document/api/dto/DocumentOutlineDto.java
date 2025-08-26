package uk.gegc.quizmaker.features.document.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * DTO representing the complete document outline structure
 * Used for structured output from LLM outline extraction
 */
public record DocumentOutlineDto(
        @JsonProperty("nodes")
        List<OutlineNodeDto> nodes
) {
    public DocumentOutlineDto {
        nodes = (nodes == null) ? List.of() : List.copyOf(nodes);
    }
}
