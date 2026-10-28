package uk.gegc.quizmaker.features.attempt.application;

import uk.gegc.quizmaker.features.attempt.domain.model.trace.ExecutionTrace.*;

/**
 * Dark writer for already authorized server commands. Scope IDs grant no access. Requires the
 * caller transaction: a returned event is not proof of commit. Consumers own real business
 * writes, receipts and access checks. An unknown commit outcome must be reconciled in a fresh
 * transaction using the SAME event identity/data; this writer never retries automatically.
 * Protected query/purpose authorization is #540, HTTP is #627, activation is #609.
 */
public interface ExecutionTraceService {
    Appended append(Scope scope, Event event);
}
