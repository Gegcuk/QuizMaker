package uk.gegc.quizmaker.features.attempt.application.impl;

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

    public AttemptDeliveryServiceImpl(AttemptReadFactsService facts, PlatformTransactionManager manager, MeterRegistry metrics) {
        this.facts = facts; this.metrics = metrics;
        this.transaction = new TransactionTemplate(manager);
        this.transaction.setReadOnly(true);
    }

    @Override public View read(UUID attemptId) {
        Timer.Sample timer = Timer.start();
        String outcome = "unavailable";
        try {
            var result = transaction.execute(status -> {
                try { return readAuthorized(attemptId); }
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

    private View readAuthorized(UUID attemptId) {
        var saved = facts.read(attemptId, AttemptReadFactsService.Purpose.ACTIVE_DELIVERY);
        var granted = saved.access();
        var state = granted.lifecycle().state();
        if (state.execution() != AttemptLifecycle.Execution.IN_PROGRESS) throw new AttemptDeliveryException(INACTIVE);
        var participant = ParticipantFormProjector.project(saved.form(), saved.revision(), saved.order(), saved.contracts());
        // Missing response/completion facts remain unknown. No new command is advertised by a read.
        var context = new AttemptCommands.Context(AttemptCommands.Access.VERIFIED, saved.policy(), state,
                granted.stateVersion(), null, null);
        return new View(attemptId, granted.stateVersion(), participant, AttemptCommands.allowedActions(context).actions());
    }
}
