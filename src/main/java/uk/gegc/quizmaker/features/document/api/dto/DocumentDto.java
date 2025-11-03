package uk.gegc.quizmaker.features.document.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import uk.gegc.quizmaker.features.document.domain.model.Document;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Schema(name = "DocumentDto", description = "Document with chunks and processing metadata")
public class DocumentDto {
    @Schema(description = "Document UUID")
    private UUID id;
    
    @Schema(description = "Original filename", example = "sample.pdf")
    private String originalFilename;
    
    @Schema(description = "MIME content type", example = "application/pdf")
    private String contentType;
    
    @Schema(description = "File size in bytes", example = "1048576")
    private Long fileSize;
    
    @Schema(description = "Processing status", example = "COMPLETED")
    private Document.DocumentStatus status;
    
    @Schema(description = "Upload timestamp")
    private LocalDateTime uploadedAt;
    
    @Schema(description = "Processing completion timestamp")
    private LocalDateTime processedAt;
    
    @Schema(description = "Extracted document title", example = "Introduction to Java")
    private String title;
    
    @Schema(description = "Extracted author name", example = "John Doe")
    private String author;
    
    @Schema(description = "Total number of pages", example = "150")
    private Integer totalPages;
    
    @Schema(description = "Total number of chunks", example = "12")
    private Integer totalChunks;
    
    @Schema(description = "Processing error message if status is FAILED")
    private String processingError;
    
    @Schema(description = "List of document chunks")
    private List<DocumentChunkDto> chunks;
} 