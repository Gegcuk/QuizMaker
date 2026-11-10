package uk.gegc.quizmaker.features.attempt.application.impl;

import java.util.*;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import uk.gegc.quizmaker.features.attempt.application.*;
import uk.gegc.quizmaker.features.attempt.domain.repository.*;
import uk.gegc.quizmaker.features.question.application.QuestionRevisionContractService;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizRevisionRepository;
import static uk.gegc.quizmaker.features.attempt.application.SubmittedAttemptSource.Failure.Reason.*;
import static uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy.*;

@Service
@Transactional(propagation = Propagation.MANDATORY)
public class SubmittedAttemptSourceImpl implements SubmittedAttemptSource {
    private final SubmittedAttemptRepository repository;
    private final AttemptLifecycleService lifecycle;
    private final PolicySnapshotService snapshots;
    private final FixedFormRepository forms;
    private final QuizRevisionRepository revisions;
    private final QuestionRevisionContractService contracts;
    public SubmittedAttemptSourceImpl(SubmittedAttemptRepository repository, AttemptLifecycleService lifecycle,
            PolicySnapshotService snapshots, FixedFormRepository forms, QuizRevisionRepository revisions,
            QuestionRevisionContractService contracts) {
        this.repository=repository; this.lifecycle=lifecycle; this.snapshots=snapshots;
        this.forms=forms; this.revisions=revisions; this.contracts=contracts;
    }
    @Override public Facts lock(UUID submissionId) {
        check(submissionId != null, INVALID_INPUT);
        var h=repository.lock(submissionId).orElseThrow(() -> new Failure(NOT_FOUND));
        var s=h.submission();
        check(h.submissionVersion()==1 && h.intentVersion()==1 && h.streamVersion()==1 && h.subjectVersion()==1
                && "AUTHENTICATED_USER".equals(h.subjectType()), UNSUPPORTED);
        check(s.formId().equals(h.boundFormId()) && s.policySnapshotId().equals(h.boundPolicyId()), CORRUPT);
        var state=lifecycle.lock(s.attemptId());
        check(state.state().terminal() && state.rowVersion()==s.stateVersion() && s.at().equals(state.submittedAt()), CORRUPT);
        var snapshot=snapshots.readCurrent(h.revisionOwnerId(),s.policySnapshotId());
        var p=snapshot.resolution().policy();
        check(p.grading().mode()==GradingMode.BINARY && p.grading().units()==ScoreUnits.EXACT_POINTS_WITH_INTEGER_BASIS_POINTS
                && p.answerChange().mode()==AnswerChangeMode.LOCK_ON_ACCEPTANCE, UNSUPPORTED);
        check(p.content().quizRevisionId().equals(s.quizRevisionId()), CORRUPT);
        contracts.declareScope(h.revisionOwnerId(),Set.of(s.quizRevisionId()));
        var form=forms.findByIdsForShare(h.revisionOwnerId(),List.of(s.formId()));
        var revision=revisions.findByIdForShare(h.revisionOwnerId(),s.quizRevisionId()).orElseThrow(() -> new Failure(CORRUPT));
        check(form.size()==1 && form.get(0).quizRevisionId().equals(revision.id()) && !form.get(0).items().isEmpty(),CORRUPT);
        var fixed=form.get(0);
        check(fixed.items().stream().map(i->i.sourceItemId()).collect(Collectors.toSet())
                .equals(revision.items().stream().map(i->i.id()).collect(Collectors.toSet())),CORRUPT);
        var resolved=contracts.resolve(h.revisionOwnerId(),revision.id());
        var responses=repository.responses(s.attemptId());
        check(s.acceptedCount()==responses.size() && (long)s.acceptedCount()+s.unansweredCount()==fixed.items().size()
                && (s.cause()==AttemptSubmissionRepository.Cause.EXPIRY || !p.submission().requireAllAnswers() || s.unansweredCount()==0),CORRUPT);
        var questions=revision.items().stream().collect(Collectors.toMap(i->i.id(),i->i.question()));
        var items=fixed.items().stream().collect(Collectors.toMap(i->i.id(),i->questions.get(i.sourceItemId())));
        var seen=new HashSet<UUID>();
        // acceptedAt is persistence time: an on-time command may wait for a root lock and
        // persist after the deadline. Expiry preserves authoritative accepted rows, bounded
        // by actual terminal processing time, while submission.at remains the effective deadline.
        for(var a:responses) {
            var q=items.get(a.formItemId());
            check(seen.add(a.formItemId()) && q!=null && a.formId().equals(fixed.id()) && a.questionRevisionId().equals(q.id())
                    && resolved.containsKey(q.id()) && a.contract().equals(resolved.get(q.id()).response().version())
                    && !a.acceptedAt().isAfter(s.cause()==AttemptSubmissionRepository.Cause.EXPIRY ? state.updatedAt() : s.at()),CORRUPT);
        }
        return new Facts(s,snapshot,fixed,revision,resolved,responses);
    }
    private static void check(boolean value, Failure.Reason reason) { if(!value) throw new Failure(reason); }

    @Override public List<AcceptedResponseRepository.Value> responses(UUID attemptId) {
        check(attemptId != null, INVALID_INPUT);
        try { return List.copyOf(repository.responses(attemptId)); }
        catch (RuntimeException failure) {
            var conflict = uk.gegc.quizmaker.shared.exception.DatabaseConflictException.from(failure);
            if (conflict.isPresent()) throw conflict.get();
            if (failure instanceof Failure known) throw new Failure(known.reason());
            throw new Failure(UNAVAILABLE);
        }
    }
}
