package uk.gegc.quizmaker.features.attempt.application.impl;

import uk.gegc.quizmaker.features.attempt.application.transaction.AttemptTransactions;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gegc.quizmaker.features.attempt.api.dto.*;
import uk.gegc.quizmaker.features.attempt.application.*;
import uk.gegc.quizmaker.features.attempt.domain.model.command.AttemptCommands;
import uk.gegc.quizmaker.features.attempt.domain.model.form.AttemptDeliveryException;
import uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.AttemptLifecycle;
import uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.AttemptLifecycle;
import uk.gegc.quizmaker.features.attempt.domain.repository.AttemptViewRouteRepository;

import static uk.gegc.quizmaker.features.attempt.application.ParticipantViewDisclosureReader.ContentVisibility.*;
import static uk.gegc.quizmaker.features.attempt.domain.model.form.AttemptDeliveryException.Reason.*;

@Service
@ConditionalOnProperty(name = "quizmaker.attempts.participant-view-enabled", havingValue = "true")
public class ParticipantAttemptViewServiceImpl implements ParticipantAttemptViewService {
    private final AuthenticatedSubjectResolver identity;
    private final AttemptViewRouteRepository routes;
    private final AttemptReadFactsService facts;
    private final ParticipantViewDisclosureReader disclosure;
    private final ParticipantViewMediaReader media;
    private final TransactionTemplate transaction;
    private final MeterRegistry metrics;
    private final java.time.Clock clock;
    private final boolean responsesEnabled;
    private final boolean submissionEnabled;

    public ParticipantAttemptViewServiceImpl(AuthenticatedSubjectResolver identity, AttemptViewRouteRepository routes,
            AttemptReadFactsService facts, ParticipantViewDisclosureReader disclosure,
            ParticipantViewMediaReader media, PlatformTransactionManager manager, MeterRegistry metrics, java.time.Clock clock,
            @Value("${quizmaker.attempts.response-acceptance-enabled:false}") boolean responsesEnabled,
            @Value("${quizmaker.attempts.submission-enabled:false}") boolean submissionEnabled) {
        this.identity = identity; this.routes = routes; this.facts = facts;
        this.disclosure = disclosure; this.media = media; this.metrics = metrics; this.clock = clock;
        this.responsesEnabled = responsesEnabled;
        this.submissionEnabled = submissionEnabled;
        transaction = AttemptTransactions.readCommitted(manager); transaction.setReadOnly(true);
    }

    @Override public ParticipantAttemptView read(UUID attemptId) {
        AttemptTransactions.requireCompatible(transaction.getTransactionManager(), false);
        Timer.Sample timer = Timer.start(); String outcome = "unavailable";
        try {
            var result = transaction.execute(status -> {
                try { return readAuthorized(attemptId); }
                catch (RuntimeException failure) { throw AttemptReadFailures.translate(failure); }
            });
            outcome = "view";
            return result;
        } catch (RuntimeException failure) {
            var safe = AttemptReadFailures.translate(failure);
            outcome = safe.reason().name().toLowerCase(Locale.ROOT); throw safe;
        } finally {
            try { timer.stop(Timer.builder("quizmaker.attempt.view").tags("version", "1", "outcome", outcome).register(metrics)); }
            catch (RuntimeException ignored) { /* telemetry never changes disclosure */ }
        }
    }

    private ParticipantAttemptView readAuthorized(UUID attemptId) {
        if (attemptId == null) throw new AttemptDeliveryException(NOT_FOUND);
        var subject = identity.resolveCurrent(attemptId);
        if (routes.isOwnedLegacyOnly(attemptId, subject.userId())) {
            throw new AttemptDeliveryException(NOT_FOUND);
        }
        var saved = facts.read(attemptId, AttemptReadFactsService.Purpose.PARTICIPANT_VIEW);
        var granted = saved.access();
        var lifecycle = granted.lifecycle();
        var projected = ParticipantViewItems.project(saved);
        var disclosed = disclosure.project(saved);
        var visibility = disclosed.visibility();
        if (visibility == null) throw new AttemptDeliveryException(CORRUPT);
        List<ParticipantAttemptView.Item> items = null;
        if (visibility == VISIBLE) {
            items = ParticipantViewItems.visible(attemptId, saved, projected, media);
        }
        var context = new AttemptCommands.Context(AttemptCommands.Access.VERIFIED, saved.policy(), lifecycle.state(),
                granted.stateVersion(), null, null);
        var actions = new ArrayList<>(AttemptCommands.allowedActions(context).actions());
        if (!lifecycle.state().terminal() && saved.policy().navigation().rules() != null
                && saved.navigation().items().stream().anyMatch(saved.navigation()::readable))
            actions.add(AttemptCommands.Type.NAVIGATE_TO_ITEM);
        if (saved.oneAtATime()) actions = new ArrayList<>(CurrentItemDelivery.actions(saved));
        var timingDetails = ParticipantAttemptTiming.from(saved.timing(), clock.instant());
        boolean timingOpen = timingDetails == null
                || !timingDetails.expired() && !timingDetails.serverTime().isBefore(timingDetails.startedAt());
        if (!timingOpen) actions.removeIf(action -> action != AttemptCommands.Type.VIEW_ATTEMPT);
        ParticipantAttemptView.BranchProgress branch = null;
        if (saved.branchProgress() != null) {
            var progress = saved.branchProgress();
            var delivered = saved.form().items().stream().map(item -> item.id()).toList();
            boolean active = lifecycle.state().execution() == AttemptLifecycle.Execution.IN_PROGRESS;
            var current = active && !progress.terminalReady() ? delivered.get(delivered.size() - 1) : null;
            boolean canWrite = active && timingOpen && granted.canCreateAttempt();
            actions.clear();
            actions.add(AttemptCommands.Type.VIEW_ATTEMPT);
            boolean allAnswered = progress.acceptedItems().containsAll(delivered);
            if (canWrite && responsesEnabled && !allAnswered) actions.add(AttemptCommands.Type.SUBMIT_ANSWER);
            if (canWrite && submissionEnabled && progress.terminalReady() && (allAnswered || !saved.policy().submission().requireAllAnswers()))
                actions.add(AttemptCommands.Type.SUBMIT_ATTEMPT);
            branch = new ParticipantAttemptView.BranchProgress(progress.formVersion(), delivered, current,
                    progress.terminalReady(), canWrite && progress.canAdvance());
        }
        return new ParticipantAttemptView(ParticipantAttemptView.Representation.POLICY_DRIVEN, 1, attemptId,
                granted.quizId(), granted.quizRevisionId(), granted.formId(), granted.stateVersion(), saved.policy().schemaVersion(),
                lifecycle.state().execution(), lifecycle.state().answers(), lifecycle.startedAt(), lifecycle.submittedAt(),
                saved.timing() == null ? ParticipantAttemptView.Timing.UNTIMED : ParticipantAttemptView.Timing.TIMED,
                visibility, items, actions, disclosed.result(), NavigationPosition.from(saved.navigation().cursor()), timingDetails, branch);
    }

    @Override public ParticipantAttemptView review(UUID attemptId, boolean includeUserAnswers,
            boolean includeCorrectAnswers, boolean includeQuestionContext) {
        var view = read(attemptId);
        var result = view.result();
        if (result == null || !Boolean.TRUE.equals(result.reviewAvailable())) throw new AttemptDeliveryException(INACTIVE);
        var filtered = new uk.gegc.quizmaker.features.result.api.dto.ParticipantResultView(result.releaseVersion(), result.releasedAt(),
                result.totalScore(), result.reviewAvailable(), result.items() == null ? null : result.items().stream().map(item ->
                new uk.gegc.quizmaker.features.result.api.dto.ParticipantResultView.Item(item.formItemId(), item.score(), item.correctness(),
                        includeUserAnswers ? item.responseState() : null, includeUserAnswers ? item.submittedAnswer() : null,
                        includeCorrectAnswers ? item.correctAnswer() : null, includeCorrectAnswers ? item.explanation() : null)).toList());
        return new ParticipantAttemptView(view.representation(), view.schemaVersion(), view.attemptId(), view.quizId(), view.quizRevisionId(),
                view.formId(), view.stateVersion(), view.policyVersion(), view.execution(), view.answerCollection(), view.startedAt(),
                view.submittedAt(), view.timing(), view.contentVisibility(), includeQuestionContext ? view.items() : null,
                view.allowedActions(), filtered, view.navigation(), view.timingDetails(), view.branchProgress());
    }
}
