package uk.gegc.quizmaker.dto.document;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Document processing configuration")
public record DocumentConfigDto(
        @Schema(description = "Default maximum chunk size in characters", example = "50000")
        Integer defaultMaxChunkSize,
        
        @Schema(description = "Default chunking strategy", example = "CHAPTER_BASED")
        String defaultStrategy
) {
} 