package uk.gegc.quizmaker.features.quiz.domain.model;

/**
 * Strategy for handling duplicates during quiz import.
 */
public enum UpsertStrategy {
    CREATE_ONLY,
    UPSERT_BY_ID,
    UPSERT_BY_CONTENT_HASH,
    SKIP_ON_DUPLICATE
}
