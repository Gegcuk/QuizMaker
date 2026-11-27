package uk.gegc.quizmaker.features.attempt.application.impl;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gegc.quizmaker.features.attempt.api.dto.UngradedParticipantResponse.*;
import uk.gegc.quizmaker.features.attempt.application.*;
import uk.gegc.quizmaker.features.attempt.application.transaction.AttemptTransactions;
import uk.gegc.quizmaker.features.attempt.domain.model.activity.ActivityContract;
import uk.gegc.quizmaker.features.attempt.domain.model.activity.ActivityContractException;
import uk.gegc.quizmaker.features.attempt.domain.model.form.AttemptDeliveryException;
import uk.gegc.quizmaker.features.attempt.domain.model.form.FixedForm;
import uk.gegc.quizmaker.features.attempt.domain.model.form.FormMaterializationException;
import uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.AttemptLifecycle;
import uk.gegc.quizmaker.features.attempt.domain.model.response.ResponseAcceptanceException;
import uk.gegc.quizmaker.features.attempt.domain.model.submission.AttemptSubmissionException;
import uk.gegc.quizmaker.features.attempt.domain.repository.UngradedActivityRepository;

import static uk.gegc.quizmaker.features.attempt.domain.model.form.AttemptDeliveryException.Reason.*;

@Service
@ConditionalOnProperty(name = "quizmaker.attempts.ungraded-participant-enabled", havingValue = "true")
public class UngradedParticipantServiceImpl implements UngradedParticipantService {
    private final TransactionTemplate reads;
    private final AuthenticatedSubjectResolver subjects;
    private final UngradedActivityRepository activities;
    private final AttemptReadFactsService facts;
    private final UngradedActivityService commands;
    private final ParticipantViewMediaReader media;
    private final MeterRegistry metrics;
    private final boolean responsesEnabled;
    private final boolean submissionEnabled;

    public UngradedParticipantServiceImpl(PlatformTransactionManager manager, AuthenticatedSubjectResolver subjects,
            UngradedActivityRepository activities, AttemptReadFactsService facts, UngradedActivityService commands,
            ParticipantViewMediaReader media, MeterRegistry metrics,
            @Value("${quizmaker.attempts.response-acceptance-enabled:false}") boolean responsesEnabled,
            @Value("${quizmaker.attempts.submission-enabled:false}") boolean submissionEnabled) {
        this.reads = AttemptTransactions.readCommitted(manager);
        this.reads.setReadOnly(true);
        this.subjects = subjects;
        this.activities = activities;
        this.facts = facts;
        this.commands = commands;
        this.media = media;
        this.metrics = metrics;
        this.responsesEnabled = responsesEnabled;
        this.submissionEnabled = submissionEnabled;
    }

    @Override public Optional<View> read(UUID attemptId, boolean hasQueryParameters) {
        AttemptTransactions.requireCompatible(reads.getTransactionManager(), false);
        try {
            Optional<View> result = reads.execute(status -> {
                try {
                    var actor = subjects.resolve(attemptId).userId();
                    if (!activities.isOwnedUngraded(attemptId, actor)) return Optional.empty();
                    var saved = facts.read(attemptId, AttemptReadFactsService.Purpose.UNGRADED_VIEW);
                    if (hasQueryParameters) throw new AttemptDeliveryException(NOT_FOUND);
                    return Optional.of(view(saved));
                } catch (RuntimeException failure) {
                    throw readFailure(failure);
                }
            });
            if (result.isPresent()) recordRead("returned");
            return result;
        } catch (RuntimeException failure) {
            var safe = readFailure(failure);
            recordRead(safe.reason().name().toLowerCase(java.util.Locale.ROOT));
            throw safe;
        }
    }

    private void recordRead(String outcome) {
        try {
            metrics.counter("quizmaker.attempt.ungraded", "version", "1", "operation", "view", "outcome", outcome).increment();
        } catch (RuntimeException ignored) { /* Metrics cannot affect participant access. */ }
    }

    private static AttemptDeliveryException readFailure(RuntimeException failure) {
        if (failure instanceof ActivityContractException invalid)
            return new AttemptDeliveryException(switch (invalid.reason()) {
                case UNKNOWN_KIND, UNKNOWN_VALUE, UNSUPPORTED_VERSION -> UNSUPPORTED;
                default -> CORRUPT;
            });
        if (failure instanceof FormMaterializationException invalid)
            return new AttemptDeliveryException(switch (invalid.reason()) {
                case UNSUPPORTED_FAMILY, UNSUPPORTED_MEDIA -> UNSUPPORTED;
                case PERSISTENCE_UNAVAILABLE -> UNAVAILABLE;
                default -> CORRUPT;
            });
        return AttemptReadFailures.translate(failure);
    }

    private View view(AttemptReadFactsService.Facts saved) {
        var access = saved.access();
        var state = access.lifecycle();
        var binding = activities.read(access.attemptId(), access.formId())
                .orElseThrow(() -> new AttemptDeliveryException(CORRUPT));
        ActivityContract.start(binding.policy(), binding.startFacts());
        var requirements = binding.policy().participation().items();
        var formIds = saved.form().items().stream().map(FixedForm.Item::id).toList();
        if (formIds.isEmpty() || !formIds.equals(requirements.stream().map(ActivityContract.ItemRequirement::formItemId).toList()))
            throw new AttemptDeliveryException(CORRUPT);
        var required = requirements.stream().filter(ActivityContract.ItemRequirement::required)
                .map(ActivityContract.ItemRequirement::formItemId).collect(Collectors.toSet());
        var participated = activities.participatedItems(access.attemptId());
        if (!Set.copyOf(formIds).containsAll(participated)) throw new AttemptDeliveryException(CORRUPT);
        if (state.state().execution() == AttemptLifecycle.Execution.SUBMITTED) {
            if (state.submittedAt() == null || !participated.containsAll(required)) throw new AttemptDeliveryException(CORRUPT);
            return new View(Kind.UNGRADED, access.attemptId(), access.formId(), access.stateVersion(), State.COMPLETED,
                    state.submittedAt(), null, List.of(Action.VIEW_ATTEMPT));
        }
        if (state.state().execution() != AttemptLifecycle.Execution.IN_PROGRESS || state.submittedAt() != null)
            throw new AttemptDeliveryException(CORRUPT);
        var questions = ParticipantViewItems.visible(access.attemptId(), saved, ParticipantViewItems.project(saved), media);
        var items = questions.stream().map(q -> new Item(q, required.contains(q.id()), participated.contains(q.id()))).toList();
        var actions = new ArrayList<Action>();
        actions.add(Action.VIEW_ATTEMPT);
        if (access.canCreateAttempt()) {
            if (responsesEnabled && participated.size() < formIds.size()) actions.add(Action.SUBMIT_ANSWER);
            if (submissionEnabled && participated.containsAll(required)) actions.add(Action.SUBMIT_ATTEMPT);
        }
        return new View(Kind.UNGRADED, access.attemptId(), access.formId(), access.stateVersion(),
                State.IN_PROGRESS, null, items, List.copyOf(actions));
    }

    // The immutable kind only selects a writer. The selected writer owns the actual transaction,
    // including current authorization, receipt and uncertain-commit recovery.
    private boolean ungradedCommand(UUID attemptId) {
        AttemptTransactions.requireCompatible(reads.getTransactionManager(), true);
        return Boolean.TRUE.equals(reads.execute(status -> {
            try {
                return activities.isOwnedUngraded(attemptId, subjects.resolve(attemptId).userId());
            } catch (RuntimeException failure) {
                throw AttemptCommandFailures.translate(failure);
            }
        }));
    }

    @Override public Optional<Participation> accept(ResponseAcceptanceService.Command command) {
        try {
            if (!ungradedCommand(command.attemptId())) return Optional.empty();
            // The strict HTTP DTO already resolved the type. The internal response contains
            // only answer fields and validates that no extra field contributes to its identity.
            if (!(command.response() instanceof ObjectNode wire)
                    || !command.type().name().equals(wire.path("type").asText()))
                throw new ResponseAcceptanceException(ResponseAcceptanceException.Reason.INVALID_INPUT);
            var value = wire.deepCopy();
            value.remove("type");
            var result = commands.accept(new UngradedActivityService.Response(command.attemptId(), command.formItemId(),
                    command.expectedStateVersion(), command.key(), command.type(), value));
            return Optional.of(new Participation(Kind.UNGRADED, result.attemptId(), result.participationId(),
                    result.stateVersion(), result.replayed()));
        } catch (ActivityContractException failure) {
            throw new ResponseAcceptanceException(activityFailure(failure));
        } catch (RuntimeException failure) {
            throw AttemptCommandFailures.translate(failure);
        }
    }

    @Override public Optional<Completion> submit(AttemptSubmissionService.Command command) {
        try {
            if (!ungradedCommand(command.attemptId())) return Optional.empty();
            var result = commands.complete(new UngradedActivityService.Submit(command.attemptId(),
                    command.expectedStateVersion(), command.key(), command.completionConfirmed()));
            return Optional.of(new Completion(Kind.UNGRADED, result.attemptId(), result.outcome().completedAt(),
                    result.stateVersion(), result.replayed()));
        } catch (ActivityContractException failure) {
            throw new AttemptSubmissionException(switch (failure.reason()) {
                case PARTICIPATION_REQUIRED -> AttemptSubmissionException.Reason.INCOMPLETE;
                case FINAL_SUBMIT_REQUIRED -> AttemptSubmissionException.Reason.CONFIRMATION_REQUIRED;
                default -> submissionReason(activityFailure(failure));
            }, failure.missingFormItemIds());
        } catch (RuntimeException failure) {
            throw new AttemptSubmissionException(submissionReason(AttemptCommandFailures.translate(failure).reason()));
        }
    }

    private static AttemptSubmissionException.Reason submissionReason(ResponseAcceptanceException.Reason reason) {
        return AttemptSubmissionException.Reason.valueOf(reason.name());
    }

    private static ResponseAcceptanceException.Reason activityFailure(ActivityContractException failure) {
        return switch (failure.reason()) {
            case UNSUPPORTED_VERSION, UNKNOWN_VALUE, UNKNOWN_KIND -> ResponseAcceptanceException.Reason.UNSUPPORTED;
            case NOT_IN_PROGRESS -> ResponseAcceptanceException.Reason.INACTIVE;
            default -> ResponseAcceptanceException.Reason.CORRUPT;
        };
    }
}
