package uk.gegc.quizmaker.features.document.application;

import uk.gegc.quizmaker.shared.exception.DocumentProcessingException;

/** Safe, typed failure raised by the internal parser-process boundary. */
public class DocumentParserWorkerException extends DocumentProcessingException {

    private final FailureReason reason;
    private final Integer exitCode;

    public DocumentParserWorkerException(FailureReason reason, String safeMessage) {
        this(reason, safeMessage, null);
    }

    public DocumentParserWorkerException(FailureReason reason, String safeMessage, Integer exitCode) {
        super(safeMessage);
        this.reason = reason;
        this.exitCode = exitCode;
    }

    public FailureReason getReason() {
        return reason;
    }

    public Integer getExitCode() {
        return exitCode;
    }

    public enum FailureReason {
        PROCESS_CRASH,
        INVALID_OUTPUT,
        INCOMPATIBLE_PROTOCOL
    }
}
