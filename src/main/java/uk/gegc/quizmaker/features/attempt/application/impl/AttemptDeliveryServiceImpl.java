package uk.gegc.quizmaker.features.attempt.application.impl;

import uk.gegc.quizmaker.features.attempt.application.transaction.AttemptTransactions;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gegc.quizmaker.features.attempt.application.*;
import uk.gegc.quizmaker.features.attempt.domain.model.command.AttemptCommands;
import uk.gegc.quizmaker.features.attempt.domain.model.form.AttemptDeliveryException;
import uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.AttemptLifecycle;

import static uk.gegc.quizmaker.features.attempt.domain.model.form.AttemptDeliveryException.Reason.INACTIVE;

@Service
public class AttemptDeliveryServiceImpl implements AttemptDeliveryService {
    private final AttemptReadFactsService facts;
    private final TransactionTemplate transaction;
    private final MeterRegistry metrics;
    private final java.time.Clock clock;

    public AttemptDeliveryServiceImpl(AttemptReadFactsService facts, PlatformTransactionManager manager, MeterRegistry metrics, java.time.Clock clock) {
        this.facts = facts; this.metrics = metrics; this.clock = clock;
        this.transaction = AttemptTransactions.readCommitted(manager);
        this.transaction.setReadOnly(true);
    }

    @Override public View read(UUID attemptId) {
        AttemptTransactions.requireCompatible(transaction.getTransactionManager(), false);
        Timer.Sample timer = Timer.start();
        String outcome = "unavailable";
        try {
            var receivedAt = clock.instant();
            var result = transaction.execute(status -> {
                try { return readAuthorized(attemptId, receivedAt); }
                catch (RuntimeException failure) { throw AttemptReadFailures.translate(failure); }
            });
            outcome = "delivered";
            return result;
        } catch (RuntimeException failure) {
            var safe = AttemptReadFailures.translate(failure);
            outcome = safe.reason().name().toLowerCase(Locale.ROOT);
            throw safe;
        } finally {
            try { timer.stop(Timer.builder("quizmaker.attempt.delivery")
                    .tags("version", "1", "outcome", outcome).register(metrics)); }
            catch (RuntimeException ignored) { /* telemetry cannot change delivery or expose content */ }
        }
    }

    private View readAuthorized(UUID attemptId, java.time.Instant receivedAt) {
        var saved = facts.read(attemptId, AttemptReadFactsService.Purpose.ACTIVE_DELIVERY, receivedAt);
        var granted = saved.access();
        var state = granted.lifecycle().state();
        if (state.execution() != AttemptLifecycle.Execution.IN_PROGRESS) throw new AttemptDeliveryException(INACTIVE);
        var participant = ParticipantFormProjector.project(saved.form(), saved.revision(), saved.order(), saved.contracts());
        participant = new uk.gegc.quizmaker.features.attempt.domain.model.form.ParticipantForm(participant.id(), participant.schemaVersion(),
                participant.items().stream().filter(i -> saved.visible(i.id())).toList());
        // Sample execution age only; this never establishes a per-question timer or write authority.
        if (saved.oneAtATime()) try {
            io.micrometer.core.instrument.DistributionSummary.builder("quizmaker.attempt.delivery.active.age")
                    .baseUnit("seconds").tag("version", "1").register(metrics)
                    .record(Math.max(0, java.time.Duration.between(granted.lifecycle().startedAt(), clock.instant()).toSeconds()));
        } catch (RuntimeException ignored) { /* optional telemetry never changes disclosure */ }
        var context = new AttemptCommands.Context(AttemptCommands.Access.VERIFIED, saved.policy(), state,
                granted.stateVersion(), null, null);
        return new View(attemptId, granted.stateVersion(), participant, saved.oneAtATime()
                ? CurrentItemDelivery.actions(saved) : AttemptCommands.allowedActions(context).actions(),
                saved.oneAtATime() ? saved.navigation().cursor() : null);
    }
}
