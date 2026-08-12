package uk.gegc.quizmaker.features.document.application;

import java.util.UUID;

/** Deletes an owned document while preserving its source until the database commit succeeds. */
public interface DocumentDeletionService {

    void deleteDocument(String username, UUID documentId);
}
