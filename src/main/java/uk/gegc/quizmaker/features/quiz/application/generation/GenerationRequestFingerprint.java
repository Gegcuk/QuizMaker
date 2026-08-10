package uk.gegc.quizmaker.features.quiz.application.generation;

/**
 * The versioned hash binds an idempotency key to the material command fields.
 * Raw upload/text content and the idempotency key are never stored; source
 * content contributes only through a one-way digest inside the command hash.
 */
public record GenerationRequestFingerprint(String hash, String canonicalizationVersion) {
}
