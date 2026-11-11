package uk.gegc.quizmaker.features.attempt.domain.model;

import java.time.Instant;
import java.util.UUID;

/** Current scalar evidence, independent of a caller's cached Answer entity/association. */
public record AcceptedAnswerFact(UUID id, UUID questionId, Boolean isCorrect, Double score, Instant answeredAt) { }
