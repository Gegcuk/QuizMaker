package uk.gegc.quizmaker.features.quiz.domain.model;

/**
 * Value object for print-specific export options.
 * Controls layout and formatting for print-friendly outputs.
 */
public record PrintOptions(
    Boolean includeCover,
    Boolean includeMetadata,
    Boolean answersOnSeparatePages,
    Boolean includeHints,
    Boolean includeExplanations,
    Boolean groupQuestionsByType
) {
    /**
     * Default print options with sensible defaults
     */
    public static PrintOptions defaults() {
        return new PrintOptions(true, true, true, false, false, false);
    }
    
    /**
     * Compact print options (minimal formatting)
     */
    public static PrintOptions compact() {
        return new PrintOptions(false, false, true, false, false, false);
    }
    
    /**
     * Teacher edition with hints and explanations
     */
    public static PrintOptions teacherEdition() {
        return new PrintOptions(true, true, true, true, true, false);
    }
}

