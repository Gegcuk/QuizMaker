package uk.gegc.quizmaker.features.attempt.application;

import uk.gegc.quizmaker.features.attempt.domain.model.trace.TraceQuery.*;

/**
 * Internal protected query only; #627 owns HTTP, concrete operator permissions and access audit.
 * Every page reauthorizes; the cursor is an untrusted continuation position, never a credential.
 * Owns read-only RC or joins compatible RC without suspending its caller. No business writes,
 * retention changes, retries, participant access or runtime activation are introduced.
 */
public interface TraceQueryService {
    Page read(Request request);
}
