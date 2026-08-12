package uk.gegc.quizmaker.features.document.application;

import java.nio.file.Path;

/** Schedules published document source cleanup against the current transaction outcome. */
public interface DocumentSourceFileCleanup {

    /**
     * Removes the published source only after the current transaction commits.
     * Implementations must preserve the source when the transaction rolls back.
     */
    void deleteAfterCommit(Path publishedPath);
}
