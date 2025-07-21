package uk.gegc.quizmaker.dto.quiz;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Scope for quiz generation")
public enum QuizScope {

    @Schema(description = "Generate quiz for the entire document (all chunks)")
    ENTIRE_DOCUMENT,

    @Schema(description = "Generate quiz for specific chunks by indices")
    SPECIFIC_CHUNKS,

    @Schema(description = "Generate quiz for a specific chapter")
    SPECIFIC_CHAPTER,

    @Schema(description = "Generate quiz for a specific section")
    SPECIFIC_SECTION
} 