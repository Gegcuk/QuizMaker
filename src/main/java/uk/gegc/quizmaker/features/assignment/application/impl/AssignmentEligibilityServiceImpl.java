package uk.gegc.quizmaker.features.assignment.application.impl;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import uk.gegc.quizmaker.features.assignment.application.AssignmentEligibilityService;
import uk.gegc.quizmaker.features.assignment.domain.model.AssignmentEligibility;
import uk.gegc.quizmaker.features.assignment.domain.model.AssignmentEligibility.*;
import uk.gegc.quizmaker.features.attempt.application.AuthenticatedSubjectResolver;
import uk.gegc.quizmaker.features.attempt.domain.model.subject.SubjectResolutionException;
import uk.gegc.quizmaker.shared.exception.DatabaseConflictException;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

@Service
public final class AssignmentEligibilityServiceImpl implements AssignmentEligibilityService {
    private final AuthenticatedSubjectResolver subjects;
    private final Clock clock;
    private final MeterRegistry metrics;

    public AssignmentEligibilityServiceImpl(AuthenticatedSubjectResolver subjects, Clock clock, MeterRegistry metrics) {
        this.subjects = Objects.requireNonNull(subjects);
        this.clock = Objects.requireNonNull(clock);
        this.metrics = Objects.requireNonNull(metrics);
    }

    @Override public Decision evaluate(Context context) {
        var decision = decide(context);
        try {
            metrics.counter("quizmaker.assignment.eligibility",
                    "version", context != null && context.version() == AssignmentEligibility.VERSION ? "1" : "unsupported",
                    "outcome", decision.outcome().name().toLowerCase(Locale.ROOT),
                    "reason", decision.reason().name().toLowerCase(Locale.ROOT)).increment();
        } catch (RuntimeException unavailable) { /* Telemetry cannot change eligibility or disclose its failure. */ }
        return decision;
    }

    private Decision decide(Context context) {
        if (context == null) return AssignmentEligibility.denied(Reason.INVALID_INPUT);
        if (context.version() != AssignmentEligibility.VERSION) return AssignmentEligibility.denied(Reason.UNSUPPORTED_VERSION);
        if (context.subject() == null) return AssignmentEligibility.denied(Reason.SUBJECT_UNAVAILABLE);
        try {
            // A structurally valid AttemptSubject is evidence, never authentication by itself.
            var authenticated = subjects.resolve(context.subject().attemptId());
            if (!context.subject().equals(authenticated)) return AssignmentEligibility.denied(Reason.SUBJECT_MISMATCH);
        } catch (SubjectResolutionException | DatabaseConflictException unavailable) {
            return AssignmentEligibility.denied(Reason.SUBJECT_UNAVAILABLE);
        }
        Instant now = null;
        try { now = clock.instant(); }
        catch (RuntimeException unavailable) { /* Never substitute a client timestamp or fallback clock. */ }
        return AssignmentEligibility.evaluate(context, now);
    }
}
