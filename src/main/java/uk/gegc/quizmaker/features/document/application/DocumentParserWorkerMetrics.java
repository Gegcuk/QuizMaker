package uk.gegc.quizmaker.features.document.application;

/** Low-cardinality operational events for the isolated parser lifecycle. */
public interface DocumentParserWorkerMetrics {

    void workerStarted();

    void workerStopped();

    void record(Outcome outcome);

    enum Outcome {
        CAPACITY_REJECTED("capacity_rejected"),
        SPAWN_FAILED("spawn_failed"),
        SUCCEEDED("succeeded"),
        PROCESSING_FAILED("processing_failed"),
        PROCESS_CRASHED("process_crashed"),
        INVALID_OUTPUT("invalid_output"),
        INCOMPATIBLE_PROTOCOL("incompatible_protocol"),
        TIMED_OUT("timed_out"),
        FORCED_KILL("forced_kill"),
        KILL_FAILED("kill_failed"),
        INTERRUPTED("interrupted");

        private final String tagValue;

        Outcome(String tagValue) {
            this.tagValue = tagValue;
        }

        public String tagValue() {
            return tagValue;
        }
    }
}
