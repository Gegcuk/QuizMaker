package uk.gegc.quizmaker.features.document.application;

/** Executes one untrusted parse within the configured concurrency and time budget. */
public interface DocumentParseExecutor {

    ConvertedDocument execute(String ownerKey, DocumentParseRequest request);
}
