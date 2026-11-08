package uk.gegc.quizmaker.features.result.application.impl;

import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gegc.quizmaker.features.attempt.application.*;
import uk.gegc.quizmaker.features.attempt.domain.model.form.AttemptDeliveryException;
import uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.AttemptLifecycle;
import uk.gegc.quizmaker.features.result.api.dto.ParticipantResultView;
import uk.gegc.quizmaker.features.result.application.*;
import uk.gegc.quizmaker.features.result.domain.model.*;
import uk.gegc.quizmaker.features.result.domain.model.disclosure.ResultDisclosure.*;
import uk.gegc.quizmaker.features.result.domain.repository.*;
import static uk.gegc.quizmaker.features.result.domain.model.ResultReleaseException.*;
import static uk.gegc.quizmaker.features.result.domain.model.ResultReleaseException.Reason.*;

@Service
public class ParticipantResultServiceImpl implements ParticipantResultService {
    private final AttemptAccessService access;
    private final AuthenticatedSubjectResolver subjects;
    private final SubmittedAttemptSource responses;
    private final AutomaticResultRepository results;
    private final ResultReleaseRepository releases;
    private final ResultDisclosureService disclosure;
    private final TransactionTemplate transaction;

    public ParticipantResultServiceImpl(AttemptAccessService access, AuthenticatedSubjectResolver subjects,
            SubmittedAttemptSource responses, AutomaticResultRepository results, ResultReleaseRepository releases,
            ResultDisclosureService disclosure, PlatformTransactionManager manager) {
        this.access = access; this.subjects = subjects; this.responses = responses;
        this.results = results; this.releases = releases; this.disclosure = disclosure;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_MANDATORY);
    }
    @Override public Optional<ParticipantResultView> read(AttemptReadFactsService.Facts facts) {
        return transaction.execute(status -> {
            try { return project(facts); }
            catch (RuntimeException failure) { throw safeRead(failure); }
        });
    }
    private Optional<ParticipantResultView> project(AttemptReadFactsService.Facts facts) {
        require(facts != null, INVALID_INPUT);
        if (facts.access().lifecycle().state().execution() != AttemptLifecycle.Execution.SUBMITTED) return Optional.empty();
        var granted = access.requireAccess(facts.access().attemptId(), AttemptAccessService.Operation.RESULT);
        require(granted.operation() == AttemptAccessService.Operation.RESULT
                && granted.attemptId().equals(facts.access().attemptId()) && granted.formId().equals(facts.form().id())
                && granted.quizId().equals(facts.access().quizId()) && granted.contentOwnerId().equals(facts.access().contentOwnerId())
                && granted.quizRevisionId().equals(facts.revision().id())
                && granted.policySnapshotId().equals(facts.access().policySnapshotId())
                && granted.lifecycle().equals(facts.access().lifecycle()), CORRUPT);
        var subject = subjects.resolveCurrent(granted.attemptId());
        var submission = releases.submission(granted.attemptId());
        if (submission.isEmpty()) return Optional.empty();
        var found = results.find(submission.get());
        if (found.isEmpty()) return Optional.empty();
        var result = found.get();
        var binding = new Binding(granted.attemptId(), granted.formId(), granted.policySnapshotId(), granted.quizRevisionId(), facts.policyHash());
        require(binding.equals(Binding.of(result)) && result.items().size() == facts.form().items().size(), CORRUPT);
        var released = releases.find(result.id());
        if (released.isEmpty()) return Optional.empty();
        var release = released.get();
        require(!release.releasedAt().isBefore(result.createdAt()), CORRUPT);
        var decisions = disclosure.evaluate(new Context(1,
                new Audience(Access.VERIFIED, granted.attemptId(), subject.userId(), subject.userId()),
                Policy.practice(binding, facts.policy()), new AttemptFacts(binding, granted.lifecycle().state()),
                ResultFacts.finalized(result), new ReleaseFacts(release.version(), binding, result.id(), ReleaseState.RELEASED),
                ReviewAccess.AVAILABLE, EnumSet.allOf(Category.class)));
        var items = PracticeReviewProjector.items(facts, result, responses.responses(granted.attemptId()), decisions);
        boolean itemCategories = List.of(Category.QUESTION_SCORE, Category.CORRECTNESS, Category.CORRECT_ANSWER,
                Category.EXPLANATION, Category.SUBMITTED_ANSWERS).stream().anyMatch(decisions::allows);
        var score = result.score();
        return Optional.of(new ParticipantResultView(release.version(), release.releasedAt(),
                decisions.allows(Category.TOTAL_SCORE) ? new ParticipantResultView.Score(score.earned(), score.possible(), score.basisPoints()) : null,
                decisions.allows(Category.REVIEW) ? true : null, itemCategories ? items : null));
    }
    private static RuntimeException safeRead(RuntimeException failure) {
        if (failure instanceof uk.gegc.quizmaker.features.attempt.domain.model.access.AttemptAccessException access)
            return new uk.gegc.quizmaker.features.attempt.domain.model.access.AttemptAccessException(access.reason());
        if (failure instanceof uk.gegc.quizmaker.features.attempt.domain.model.subject.SubjectResolutionException subject)
            return new uk.gegc.quizmaker.features.attempt.domain.model.subject.SubjectResolutionException(subject.reason());
        var safe = ResultReleaseFailures.safe(failure);
        return new AttemptDeliveryException(switch (safe.reason()) {
            case INVALID_INPUT, CORRUPT -> AttemptDeliveryException.Reason.CORRUPT;
            case UNSUPPORTED -> AttemptDeliveryException.Reason.UNSUPPORTED;
            case BUSY -> AttemptDeliveryException.Reason.BUSY;
            case NOT_FOUND -> AttemptDeliveryException.Reason.NOT_FOUND;
            default -> AttemptDeliveryException.Reason.UNAVAILABLE;
        });
    }
}
