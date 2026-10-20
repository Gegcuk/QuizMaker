package uk.gegc.quizmaker.features.quiz.application.generation;

import java.util.UUID;

public record GenerationWorkClaim(UUID jobId, long fence, UUID documentId, String requestData) { }
