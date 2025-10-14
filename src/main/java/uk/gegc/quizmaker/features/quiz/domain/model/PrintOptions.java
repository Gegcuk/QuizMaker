package uk.gegc.quizmaker.features.quiz.domain.model;

/**
 * Value object for print-specific export options.
 * Controls layout and formatting for print-friendly outputs.
 */
public record PrintOptions(
    Boolean includeCover,
    Boolean includeMetadata,
    Boolean answersOnSeparatePages
) {
    /**
     * Default print options with sensible defaults
     */
    public static PrintOptions defaults() {
        return new PrintOptions(true, true, true);
    }
    
    /**
     * Compact print options (minimal formatting)
     */
    public static PrintOptions compact() {
        return new PrintOptions(false, false, true);
    }
}

