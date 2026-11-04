package uk.gegc.quizmaker.features.attempt.domain.model.compatibility;

/** Deliberately omits SQL, underlying connection diagnostics and stored values. */
public final class LegacyInventoryException extends RuntimeException {
    public LegacyInventoryException() { super("Legacy inventory unavailable"); }
}
