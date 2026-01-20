package uk.gegc.quizmaker.features.quiz.domain.model;

/**
 * Value object describing import options for quiz bulk import.
 */
public record QuizImportOptions(
    UpsertStrategy strategy,
    boolean dryRun,
    boolean autoCreateTags,
    boolean autoCreateCategory,
    int maxItems
) {
    public QuizImportOptions {
        if (strategy == null) {
            strategy = UpsertStrategy.CREATE_ONLY;
        }
        if (maxItems <= 0) {
            throw new IllegalArgumentException("maxItems must be positive");
        }
    }

    public static QuizImportOptions defaults(int maxItems) {
        return new QuizImportOptions(UpsertStrategy.CREATE_ONLY, false, false, false, maxItems);
    }
}
