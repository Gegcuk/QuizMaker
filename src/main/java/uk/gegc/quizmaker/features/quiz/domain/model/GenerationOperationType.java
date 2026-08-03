package uk.gegc.quizmaker.features.quiz.domain.model;

/**
 * Source-specific operation types keep client idempotency keys scoped to one
 * generation command while allowing a client to use independent keys per flow.
 */
public enum GenerationOperationType {
    DOCUMENT,
    UPLOAD,
    TEXT
}
