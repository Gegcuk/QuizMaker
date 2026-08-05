package uk.gegc.quizmaker.features.document.application;

import java.util.concurrent.Callable;

/** Executes one untrusted parse within the configured concurrency and time budget. */
public interface DocumentParseExecutor {

    <T> T execute(String ownerKey, Callable<T> parseOperation);
}
