package uk.gegc.quizmaker.features.quiz.application.generation;

/**
 * The versioned hash binds an idempotency key to the material command fields.
 * It deliberately excludes raw upload/text content and the idempotency key.
 */
public record GenerationRequestFingerprint(String hash, String canonicalizationVersion) {
}
