package uk.gegc.quizmaker.features.attempt.domain.model;

/** Missing/corrupt derived membership must never become a plausible incomplete score. */
public final class AcceptedAnswerEvidenceException extends RuntimeException {
    public AcceptedAnswerEvidenceException() {
        // Parser/SQL diagnostics can contain persisted data. Never retain them as a cause/suppressed exception.
        super("Accepted answer evidence is unavailable", null, false, true);
    }
}
