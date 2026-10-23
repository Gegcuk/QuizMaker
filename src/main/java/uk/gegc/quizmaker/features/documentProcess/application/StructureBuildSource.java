package uk.gegc.quizmaker.features.documentProcess.application;

import java.util.UUID;

/** Captured source and optimistic publication claim; never persisted or exposed as an API DTO. */
public record StructureBuildSource(UUID documentId, UUID ownerId, long version, String text, Integer charCount) {
}
