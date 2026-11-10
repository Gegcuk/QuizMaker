package uk.gegc.quizmaker.features.attempt.application.impl;

import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import uk.gegc.quizmaker.features.attempt.application.*;
import uk.gegc.quizmaker.features.attempt.application.AttemptExpiryService.Failure;
import uk.gegc.quizmaker.features.attempt.domain.model.navigation.FlatNavigation;
import uk.gegc.quizmaker.features.attempt.domain.repository.*;
import uk.gegc.quizmaker.features.question.application.QuestionRevisionContractService;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizRevisionRepository;
import static uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy.*;

/** System evidence reader, not a participant permission bypass or a new content-serving path. */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class AttemptExpiryFactsServiceImpl implements AttemptExpiryFactsService {
    private final PolicySnapshotService snapshots;
    private final FixedFormRepository forms;
    private final FormOptionOrderRepository orders;
    private final QuizRevisionRepository revisions;
    private final QuestionRevisionContractService contracts;
    private final NavigationRepository navigation;
    private final AttemptTimingService timing;
    public AttemptExpiryFactsServiceImpl(PolicySnapshotService snapshots, FixedFormRepository forms, FormOptionOrderRepository orders,
            QuizRevisionRepository revisions, QuestionRevisionContractService contracts, NavigationRepository navigation, AttemptTimingService timing) {
        this.snapshots=snapshots; this.forms=forms; this.orders=orders; this.revisions=revisions;
        this.contracts=contracts; this.navigation=navigation; this.timing=timing;
    }
    @Override public AttemptReadFactsService.Facts read(AttemptAccessService.Access captured) {
        try {
            var owner=captured.contentOwnerId();
            var snapshot=snapshots.readCurrent(owner,captured.policySnapshotId());
            var policy=snapshot.resolution().policy();
            check(policy.schemaVersion()==1 && policy.content().formMode()==FormMode.PRECOMPUTED
                    && policy.content().sections()==SectionMode.IMPLICIT_FLAT && policy.content().cases()==CaseMode.NOT_CONFIGURED
                    && policy.timing().mode()==TimingMode.TOTAL_ATTEMPT && policy.pause().mode()==PauseMode.DISABLED
                    && policy.answerChange().mode()==AnswerChangeMode.LOCK_ON_ACCEPTANCE, Failure.Reason.UNSUPPORTED);
            check(policy.content().quizRevisionId().equals(captured.quizRevisionId()),Failure.Reason.CORRUPT);
            // Same immutable question-before-timing lock order as start and participant readers.
            contracts.declareScope(owner,Set.of(captured.quizRevisionId()));
            var checkpoint=timing.read(captured,snapshot);
            var form=forms.findByIdsForShare(owner,List.of(captured.formId()));
            var order=orders.findByIdsForShare(owner,List.of(captured.formId()));
            check(form.size()==1 && order.size()==1 && order.get(0).policy().equals(policy.answerOrdering()),Failure.Reason.CORRUPT);
            var revision=revisions.findByIdForShare(owner,captured.quizRevisionId()).orElseThrow(() -> new Failure(Failure.Reason.CORRUPT));
            var resolved=contracts.resolve(owner,revision.id());
            var cursor=policy.navigation().mode()==NavigationMode.SEQUENTIAL
                    ? navigation.read(captured.attemptId()).orElseThrow(() -> new Failure(Failure.Reason.CORRUPT)) : null;
            var nav=new FlatNavigation.Context(policy.navigation(),form.get(0).id(),
                    form.get(0).items().stream().map(i->i.id()).toList(),Set.of(),cursor);
            // This validates captured form/revision/contract bindings without returning content.
            ParticipantFormProjector.project(form.get(0),revision,order.get(0),resolved);
            return new AttemptReadFactsService.Facts(captured,policy,form.get(0),order.get(0),revision,resolved,snapshot.resolution().hash(),nav,checkpoint);
        } catch (RuntimeException failure) {
            // Sanitize before the transaction interceptor can log driver/content diagnostics.
            if(failure instanceof Failure safe) throw new Failure(safe.reason());
            if(failure instanceof uk.gegc.quizmaker.features.attempt.domain.model.snapshot.PolicySnapshotException snapshot)
                throw new Failure(switch(snapshot.reason()) {
                    case UNSUPPORTED_VERSION, UNSUPPORTED_HASH_ALGORITHM -> Failure.Reason.UNSUPPORTED;
                    case CORRUPT_SNAPSHOT, SNAPSHOT_CONFLICT -> Failure.Reason.CORRUPT;
                    default -> Failure.Reason.UNAVAILABLE;
                });
            if(failure instanceof uk.gegc.quizmaker.features.attempt.domain.model.timing.AttemptTimingException timingFailure)
                throw new Failure(switch(timingFailure.reason()) {
                    case UNSUPPORTED -> Failure.Reason.UNSUPPORTED;
                    case MISSING, CORRUPT -> Failure.Reason.CORRUPT;
                    default -> Failure.Reason.UNAVAILABLE;
                });
            throw new Failure(Failure.Reason.UNAVAILABLE);
        }
    }
    private static void check(boolean ok, Failure.Reason reason) { if(!ok) throw new Failure(reason); }
}
