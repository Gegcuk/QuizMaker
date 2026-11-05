package uk.gegc.quizmaker.features.media.domain.model;

public final class SavedImageException extends RuntimeException {
    public enum Reason { CONFLICT, UNAVAILABLE }
    private final Reason reason;
    public SavedImageException(Reason reason) {
        super(reason == Reason.CONFLICT ? "Saved image state changed; reload before retrying"
                : "The exact saved image is temporarily unavailable");
        this.reason = reason;
    }
    public Reason reason() { return reason; }
}
