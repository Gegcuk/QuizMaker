package uk.gegc.quizmaker.features.documentProcess.infra.mapper;

import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.features.documentProcess.api.dto.DocumentView;
import uk.gegc.quizmaker.features.documentProcess.api.dto.IngestResponse;
import uk.gegc.quizmaker.features.documentProcess.api.dto.TextSliceResponse;
import uk.gegc.quizmaker.features.documentProcess.domain.model.NormalizedDocument;

import java.util.UUID;

/**
 * Mapper for converting between DocumentProcess entities and DTOs.
 */
@Component("documentProcessMapper")
public class DocumentMapper {

    /**
     * Converts a NormalizedDocument entity to DocumentView DTO.
     */
    public DocumentView toDocumentView(NormalizedDocument document) {
        return new DocumentView(
                document.getId(),
                document.getOriginalName(),
                document.getMime(),
                document.getSource(),
                document.getCharCount(),
                document.getLanguage(),
                document.getStatus(),
                document.getCreatedAt(),
                document.getUpdatedAt()
        );
    }

    /**
     * Converts a NormalizedDocument entity to IngestResponse DTO.
     */
    public IngestResponse toIngestResponse(NormalizedDocument document) {
        return new IngestResponse(
                document.getId(),
                document.getStatus()
        );
    }

    /**
     * Creates a TextSliceResponse DTO.
     */
    public TextSliceResponse toTextSliceResponse(UUID documentId, int start, int end, String text) {
        return new TextSliceResponse(documentId, start, end, text);
    }
}
