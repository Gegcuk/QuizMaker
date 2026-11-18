package uk.gegc.quizmaker.features.attempt.application;

import java.util.Optional;
import java.util.UUID;
import uk.gegc.quizmaker.features.attempt.domain.model.trace.TraceQuery.*;

/**
 * Current trusted purpose/scope and evidence-availability boundary, not an HTTP input mapper.
 * Resolve the authenticated principal, exact permission, ownership/organization/tenant/resource
 * scope and required access audit independently. IDs, global roles and quiz ownership alone
 * grant no trace access. Retain necessary authorization/governance protection until read commit.
 * Supply current retention/hold/erasure facts; missing evidence does not prove expiry or erasure.
 * An empty result denies without revealing existence. Missing/ambiguous readers also deny.
 *
 * This issue does not implement operator grants, holds or cleanup. Their real integrations and
 * #627 access audit are required before exposing this query. Implementations perform no remote
 * I/O under the caller transaction and sanitize failures before their own proxy boundary.
 */
public interface TraceQueryContextReader {
    Optional<Context> authorize(UUID attemptId, Purpose purpose, Operation operation);
}
