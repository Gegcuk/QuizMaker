package uk.gegc.quizmaker.features.repetition.domain.model;

import java.util.UUID;

public record ContentKey(RepetitionContentType type, UUID id) {
}
