package uk.gegc.quizmaker.features.document.application;

/** Starts one isolated parser worker for a validated server-owned source. */
public interface DocumentParserWorkerFactory {

    DocumentParserWorker start(DocumentParseRequest request);
}
