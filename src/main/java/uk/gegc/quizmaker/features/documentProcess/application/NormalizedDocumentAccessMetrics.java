package uk.gegc.quizmaker.features.documentProcess.application;

import java.util.Locale;

/** Low-cardinality access outcomes without document or user identifiers. */
public interface NormalizedDocumentAccessMetrics {

    void record(Outcome outcome);

    enum Outcome {
        AUTHORIZED,
        UNAUTHENTICATED,
        OWNER_DENIED,
        LEGACY_DENIED,
        DEPENDENCY_FAILURE;

        public String tagValue() {
            return name().toLowerCase(Locale.ROOT);
        }
    }
}
