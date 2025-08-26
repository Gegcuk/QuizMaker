package uk.gegc.quizmaker.features.document.infra.mapping;

import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.features.document.api.dto.DocumentNodeDto;
import uk.gegc.quizmaker.features.document.api.dto.DocumentTreeDto;
import uk.gegc.quizmaker.features.document.domain.model.Document;
import uk.gegc.quizmaker.features.document.domain.model.DocumentNode;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public class DocumentNodeMapper {

    public DocumentNodeDto toDto(DocumentNode entity) {
        if (entity == null) {
            return null;
        }

        return new DocumentNodeDto(
                entity.getId(),
                entity.getDocument() != null ? entity.getDocument().getId() : null,
                entity.getParent() != null ? entity.getParent().getId() : null,
                entity.getLevel(),
                entity.getType(),
                entity.getTitle(),
                entity.getStartOffset(),
                entity.getEndOffset(),
                entity.getStartAnchor(),
                entity.getEndAnchor(),
                entity.getOrdinal(),
                entity.getStrategy(),
                entity.getConfidence(),
                entity.getSourceVersionHash(),
                entity.getCreatedAt(),
                toDtoList(entity.getChildren())
        );
    }

    public DocumentNodeDto toFlatDto(DocumentNode entity) {
        if (entity == null) {
            return null;
        }

        return new DocumentNodeDto(
                entity.getId(),
                entity.getDocument() != null ? entity.getDocument().getId() : null,
                entity.getParent() != null ? entity.getParent().getId() : null,
                entity.getLevel(),
                entity.getType(),
                entity.getTitle(),
                entity.getStartOffset(),
                entity.getEndOffset(),
                entity.getStartAnchor(),
                entity.getEndAnchor(),
                entity.getOrdinal(),
                entity.getStrategy(),
                entity.getConfidence(),
                entity.getSourceVersionHash(),
                entity.getCreatedAt(),
                List.of() // Empty list instead of null for flat DTOs
        );
    }

    public List<DocumentNodeDto> toDtoList(List<DocumentNode> entities) {
        if (entities == null) {
            return List.of();
        }
        return entities.stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    public List<DocumentNodeDto> toFlatDtoList(List<DocumentNode> entities) {
        if (entities == null) {
            return List.of();
        }
        return entities.stream()
                .map(this::toFlatDto)
                .collect(Collectors.toList());
    }

    /**
     * Maps a list of root nodes and a document to a DocumentTreeDto
     *
     * @param rootNodes List of root nodes (nodes with no parent)
     * @param document  The document entity containing title and ID
     * @return DocumentTreeDto with document info and tree structure
     */
    public DocumentTreeDto toTreeDto(List<DocumentNode> rootNodes, Document document) {
        if (rootNodes == null) {
            rootNodes = List.of();
        }

        return new DocumentTreeDto(
                document.getId(),
                document.getTitle(),
                toDtoList(rootNodes)
        );
    }

    /**
     * Alternative method that accepts document ID and title directly
     * Useful when you don't have the full Document entity
     */
    public DocumentTreeDto toTreeDto(List<DocumentNode> rootNodes, UUID documentId, String documentTitle) {
        if (documentId == null) {
            throw new IllegalArgumentException("Document ID cannot be null");
        }

        return new DocumentTreeDto(
                documentId,
                documentTitle,
                toDtoList(rootNodes)
        );
    }

    public void updateEntity(DocumentNode entity, DocumentNodeDto dto) {
        if (entity == null || dto == null) {
            return;
        }

        // Note: We don't update id, document, parent, children, or createdAt
        // as these are managed by the domain or should not be updated via DTO
        entity.setLevel(dto.level());
        entity.setType(dto.type());
        entity.setTitle(dto.title());
        entity.setStartOffset(dto.startOffset());
        entity.setEndOffset(dto.endOffset());
        entity.setStartAnchor(dto.startAnchor());
        entity.setEndAnchor(dto.endAnchor());
        entity.setOrdinal(dto.ordinal());
        entity.setStrategy(dto.strategy());
        entity.setConfidence(dto.confidence());
        entity.setSourceVersionHash(dto.sourceVersionHash());
    }
}
