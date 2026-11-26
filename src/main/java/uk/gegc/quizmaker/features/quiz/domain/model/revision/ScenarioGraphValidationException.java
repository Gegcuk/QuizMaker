package uk.gegc.quizmaker.features.quiz.domain.model.revision;

/** Publication diagnostics use only fixed codes and indexes within the author's graph. */
public final class ScenarioGraphValidationException extends RuntimeException {
    public enum Reason {
        NODE_LIMIT, EDGE_LIMIT, PATH_LIMIT, CONDITION_SIZE_LIMIT, TOTAL_CONDITION_SIZE_LIMIT,
        UNREACHABLE_NODE, CYCLE, TERMINAL_HAS_TRANSITIONS, MISSING_DEFAULT, MULTIPLE_DEFAULTS,
        INVALID_CONDITION_SOURCE, UNSUPPORTED_CONDITION_VERSION, INVALID_CONDITION,
        UNSUPPORTED_OPERATOR, AMBIGUOUS_CONDITIONS
    }

    private final Reason reason;
    private final String path;

    ScenarioGraphValidationException(Reason reason, String path) {
        super("Scenario graph rejected: " + reason + " at " + path, null, false, true);
        this.reason = reason;
        this.path = path;
    }

    public Reason reason() { return reason; }

    public String path() { return path; }
}
