package uk.gegc.quizmaker.features.document.api.dto;

import lombok.Data;

@Data
public class ProcessDocumentRequest {
    private ChunkingStrategy chunkingStrategy;
    private Integer maxChunkSize; // characters
    private Integer minChunkSize = 1000; // characters - minimum chunk size
    private Integer aggressiveCombinationThreshold = 3000; // characters - combine chunks smaller than this
    private Boolean storeChunks = true; // Whether to store chunks in database

    public enum ChunkingStrategy {
        AUTO,           // Automatically determine best strategy
        CHAPTER_BASED,  // Split by chapters only
        SECTION_BASED,  // Split by sections only
        SIZE_BASED,     // Split by size only
        PAGE_BASED      // Split by page count
    }
} 