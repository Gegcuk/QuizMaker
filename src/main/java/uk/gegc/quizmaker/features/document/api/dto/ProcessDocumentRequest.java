package uk.gegc.quizmaker.features.document.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(name = "ProcessDocumentRequest", description = "Document processing and chunking configuration")
public class ProcessDocumentRequest {
    @Schema(description = "Chunking strategy", example = "CHAPTER_BASED")
    private ChunkingStrategy chunkingStrategy;
    
    @Schema(description = "Maximum chunk size in characters", example = "50000")
    private Integer maxChunkSize;
    
    @Schema(description = "Minimum chunk size in characters", example = "300")
    private Integer minChunkSize = 300;
    
    @Schema(description = "Threshold for combining small chunks", example = "3000")
    private Integer aggressiveCombinationThreshold = 3000;
    
    @Schema(description = "Whether to store chunks in database", example = "true")
    private Boolean storeChunks = true;

    @Schema(description = "Chunking strategy options")
    public enum ChunkingStrategy {
        @Schema(description = "Automatically determine best strategy")
        AUTO,
        @Schema(description = "Split by chapters only")
        CHAPTER_BASED,
        @Schema(description = "Split by sections only")
        SECTION_BASED,
        @Schema(description = "Split by size only")
        SIZE_BASED,
        @Schema(description = "Split by page count")
        PAGE_BASED
    }
} 