package uk.gegc.quizmaker.features.attempt.application.impl;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gegc.quizmaker.features.attempt.api.dto.*;
import uk.gegc.quizmaker.features.attempt.application.*;
import uk.gegc.quizmaker.features.attempt.domain.model.command.AttemptCommands;
import uk.gegc.quizmaker.features.attempt.domain.model.form.AttemptDeliveryException;
import uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.AttemptLifecycle;
import uk.gegc.quizmaker.features.attempt.domain.repository.AttemptViewRouteRepository;

import static uk.gegc.quizmaker.features.attempt.application.ParticipantViewDisclosureReader.ContentVisibility.*;
import static uk.gegc.quizmaker.features.attempt.domain.model.form.AttemptDeliveryException.Reason.*;

@Service
@ConditionalOnProperty(name = "quizmaker.attempts.participant-view-enabled", havingValue = "true")
public class ParticipantAttemptViewServiceImpl implements ParticipantAttemptViewService {
    private final AuthenticatedSubjectResolver identity;
    private final AttemptViewRouteRepository routes;
    private final AttemptService legacy;
    private final AttemptReadFactsService facts;
    private final ParticipantViewDisclosureReader disclosure;
    private final ParticipantViewMediaReader media;
    private final TransactionTemplate transaction;
    private final MeterRegistry metrics;

    public ParticipantAttemptViewServiceImpl(AuthenticatedSubjectResolver identity, AttemptViewRouteRepository routes,
            AttemptService legacy, AttemptReadFactsService facts, ParticipantViewDisclosureReader disclosure,
            ParticipantViewMediaReader media, PlatformTransactionManager manager, MeterRegistry metrics) {
        this.identity = identity; this.routes = routes; this.legacy = legacy; this.facts = facts;
        this.disclosure = disclosure; this.media = media; this.metrics = metrics;
        transaction = new TransactionTemplate(manager); transaction.setReadOnly(true);
    }

    @Override public AttemptDetailsResponse read(UUID attemptId) {
        Timer.Sample timer = Timer.start(); String outcome = "unavailable";
        try {
            var result = transaction.execute(status -> {
                try { return readAuthorized(attemptId); }
                catch (RuntimeException failure) { throw AttemptReadFailures.translate(failure); }
            });
            outcome = result instanceof AttemptDetailsDto ? "legacy" : "view";
            return result;
        } catch (RuntimeException failure) {
            var safe = AttemptReadFailures.translate(failure);
            outcome = safe.reason().name().toLowerCase(Locale.ROOT); throw safe;
        } finally {
            try { timer.stop(Timer.builder("quizmaker.attempt.view").tags("version", "1", "outcome", outcome).register(metrics)); }
            catch (RuntimeException ignored) { /* telemetry never changes disclosure */ }
        }
    }

    private AttemptDetailsResponse readAuthorized(UUID attemptId) {
        if (attemptId == null) throw new AttemptDeliveryException(NOT_FOUND);
        var subject = identity.resolveCurrent(attemptId);
        if (routes.isOwnedLegacyOnly(attemptId, subject.userId())) {
            // The server principal was resolved above. No request-supplied username/subject is used.
            try { return legacy.getAttemptDetail(SecurityContextHolder.getContext().getAuthentication().getName(), attemptId); }
            catch (org.springframework.security.access.AccessDeniedException
                   | uk.gegc.quizmaker.shared.exception.ResourceNotFoundException denied) {
                throw new AttemptDeliveryException(NOT_FOUND);
            }
        }
        var saved = facts.read(attemptId, AttemptReadFactsService.Purpose.PARTICIPANT_VIEW);
        var granted = saved.access();
        var lifecycle = granted.lifecycle();
        var sources = saved.revision().items().stream().collect(Collectors.toMap(i -> i.id(), i -> i.question()));
        var references = new ArrayList<ParticipantViewMediaReader.Reference>();
        for (var item : saved.form().items()) {
            if (saved.oneAtATime() && !lifecycle.state().terminal() && !saved.visible(item.id())) continue;
            var question = sources.get(item.sourceItemId());
            if (question == null) throw new AttemptDeliveryException(CORRUPT);
            if (question.attachmentAssetId() != null || question.attachmentUrl() != null) {
                if (question.attachmentAssetId() == null) throw new AttemptDeliveryException(CORRUPT);
                references.add(new ParticipantViewMediaReader.Reference(item.id(), question.id(), question.attachmentAssetId()));
            }
        }
        var mediaIds = references.stream().map(ParticipantViewMediaReader.Reference::formItemId).collect(Collectors.toSet());
        // Validate the complete immutable form even when its content must be withheld.
        var allMediaIds = saved.form().items().stream().filter(i -> {
            var q = sources.get(i.sourceItemId()); return q != null && q.attachmentAssetId() != null;
        }).map(uk.gegc.quizmaker.features.attempt.domain.model.form.FixedForm.Item::id).collect(Collectors.toSet());
        var projected = ParticipantFormProjector.projectForView(saved.form(), saved.revision(), saved.order(), saved.contracts(), allMediaIds);
        var disclosed = disclosure.project(saved);
        var visibility = disclosed.visibility();
        if (visibility == null) throw new AttemptDeliveryException(CORRUPT);
        List<ParticipantAttemptView.Item> items = null;
        if (visibility == VISIBLE) {
            var images = media.read(attemptId, saved.revision().id(), List.copyOf(references));
            if (images == null || !images.keySet().equals(mediaIds) || images.values().stream().anyMatch(Objects::isNull))
                throw new AttemptDeliveryException(CORRUPT);
            // Partial active-attempt media behavior is not approved here. Never grant time or mark an answer wrong.
            if (!lifecycle.state().terminal() && images.values().stream()
                    .anyMatch(image -> image.availability() != ParticipantViewMediaReader.Availability.AVAILABLE))
                throw new AttemptDeliveryException(UNAVAILABLE);
            items = AttemptFormResponse.items(projected).stream()
                    .filter(item -> lifecycle.state().terminal() || saved.visible(item.id())).map(item -> {
                var image = images.get(item.id());
                return new ParticipantAttemptView.Item(item.id(), item.position(), item.type(), item.questionText(), item.content(),
                        image == null ? null : new ParticipantAttemptView.Image(image.availability(), image.url()));
            }).toList();
        }
        var context = new AttemptCommands.Context(AttemptCommands.Access.VERIFIED, saved.policy(), lifecycle.state(),
                granted.stateVersion(), null, null);
        var actions = new ArrayList<>(AttemptCommands.allowedActions(context).actions());
        if (!lifecycle.state().terminal() && saved.policy().navigation().rules() != null
                && saved.navigation().items().stream().anyMatch(saved.navigation()::readable))
            actions.add(AttemptCommands.Type.NAVIGATE_TO_ITEM);
        if (saved.oneAtATime()) actions = new ArrayList<>(CurrentItemDelivery.actions(saved));
        return new ParticipantAttemptView(ParticipantAttemptView.Representation.POLICY_DRIVEN, 1, attemptId,
                granted.quizId(), granted.quizRevisionId(), granted.formId(), granted.stateVersion(), saved.policy().schemaVersion(),
                lifecycle.state().execution(), lifecycle.state().answers(), lifecycle.startedAt(), lifecycle.submittedAt(),
                ParticipantAttemptView.Timing.UNTIMED, visibility, items, actions, disclosed.result(), NavigationPosition.from(saved.navigation().cursor()));
    }

    @Override public AttemptReviewResponse review(UUID attemptId, boolean includeUserAnswers,
            boolean includeCorrectAnswers, boolean includeQuestionContext) {
        boolean legacyOnly;
        try {
            legacyOnly = Boolean.TRUE.equals(transaction.execute(status -> {
                try {
                    if (attemptId == null) throw new AttemptDeliveryException(NOT_FOUND);
                    var subject = identity.resolveCurrent(attemptId);
                    return routes.isOwnedLegacyOnly(attemptId, subject.userId());
                } catch (RuntimeException failure) { throw AttemptReadFailures.translate(failure); }
            }));
        } catch (RuntimeException failure) { throw AttemptReadFailures.translate(failure); }
        // Historical success/error envelopes remain owned by the existing legacy service.
        if (legacyOnly) return legacy.getAttemptReview(SecurityContextHolder.getContext().getAuthentication().getName(),
                attemptId, includeUserAnswers, includeCorrectAnswers, includeQuestionContext);
        var response = read(attemptId);
        if (!(response instanceof ParticipantAttemptView view)) throw new AttemptDeliveryException(CORRUPT);
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
                view.allowedActions(), filtered, view.navigation());
    }
}
