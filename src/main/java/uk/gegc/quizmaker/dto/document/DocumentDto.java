package uk.gegc.quizmaker.dto.document;

import lombok.Data;
import uk.gegc.quizmaker.model.document.Document;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Data
public class DocumentDto {
    private UUID id;
    private String originalFilename;
    private String contentType;
    private Long fileSize;
    private Document.DocumentStatus status;
    private LocalDateTime uploadedAt;
    private LocalDateTime processedAt;
    private String title;
    private String author;
    private Integer totalPages;
    private Integer totalChunks;
    private String processingError;
    private List<DocumentChunkDto> chunks;
} 