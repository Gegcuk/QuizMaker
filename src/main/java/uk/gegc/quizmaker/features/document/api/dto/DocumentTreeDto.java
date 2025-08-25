package uk.gegc.quizmaker.features.document.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record DocumentTreeDto(
    UUID documentId,
    String documentTitle,
    List<DocumentNodeDto> nodes
) {
    // Empty constructor for Jackson
    public DocumentTreeDto {
        if (documentId == null) {
            throw new IllegalArgumentException("Document ID cannot be null");
        }
        if (nodes == null) {
            throw new IllegalArgumentException("Nodes cannot be null");
        }
    }
}
