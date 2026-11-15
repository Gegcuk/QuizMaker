package uk.gegc.quizmaker.features.assignment.application;

import java.util.Map;
import java.util.UUID;
import uk.gegc.quizmaker.features.assignment.domain.model.AssignmentContract.*;

/**
 * Trusted internal source boundary, called inside the writer transaction. Implementations must
 * verify current source authority/visibility, exact scope, immutable roster/group provenance and
 * the entire requested definition, retaining necessary protection until commit. Return only
 * assignment participant designations, never contacts or profile fallbacks. This is not a grant
 * supplied by an HTTP client. Missing or ambiguous implementations deny; #533/#622 integrations
 * must supply real source readers before exposing authoring. Local fixtures prove #621 only.
 */
public interface AssignmentSourceVerifier {
    Map<UUID, String> verify(UUID actorId, Scope scope, Definition definition);
}
