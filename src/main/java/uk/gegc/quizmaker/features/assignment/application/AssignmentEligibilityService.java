package uk.gegc.quizmaker.features.assignment.application;

import uk.gegc.quizmaker.features.assignment.domain.model.AssignmentEligibility.Context;
import uk.gegc.quizmaker.features.assignment.domain.model.AssignmentEligibility.Decision;

/**
 * Internal authenticated eligibility preflight. Only server readers may assemble Context:
 * exact #531 snapshot, #536 subject and independently verified, scoped current access facts.
 * The service rechecks authenticated identity and uses its own Clock; inputs are not API DTOs.
 * No publication, capacity/attempt allowance, timer, start or write is performed. #534 must
 * revalidate all facts atomically, including current locking identity, before creating an attempt.
 * Guest access requires #611/#625; legacy links are not assignment snapshots. No route is enabled.
 */
public interface AssignmentEligibilityService {
    Decision evaluate(Context context);
}
