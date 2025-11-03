package uk.gegc.quizmaker.features.document.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import uk.gegc.quizmaker.features.document.domain.model.DocumentChunk;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Schema(name = "DocumentChunkDto", description = "Individual chunk of a processed document")
public class DocumentChunkDto {
    @Schema(description = "Chunk UUID")
    private UUID id;
    
    @Schema(description = "Chunk index within document (0-based)", example = "0")
    private Integer chunkIndex;
    
    @Schema(description = "Chunk title or heading", example = "Chapter 1: Introduction")
    private String title;
    
    @Schema(description = "Chunk text content")
    private String content;
    
    @Schema(description = "Starting page number", example = "1")
    private Integer startPage;
    
    @Schema(description = "Ending page number", example = "5")
    private Integer endPage;
    
    @Schema(description = "Word count", example = "500")
    private Integer wordCount;
    
    @Schema(description = "Character count", example = "3000")
    private Integer characterCount;
    
    @Schema(description = "Creation timestamp")
    private LocalDateTime createdAt;
    
    @Schema(description = "Chapter title", example = "Introduction")
    private String chapterTitle;
    
    @Schema(description = "Section title", example = "Getting Started")
    private String sectionTitle;
    
    @Schema(description = "Chapter number", example = "1")
    private Integer chapterNumber;
    
    @Schema(description = "Section number", example = "1")
    private Integer sectionNumber;
    
    @Schema(description = "Chunk type", example = "CHAPTER")
    private DocumentChunk.ChunkType chunkType;
} 