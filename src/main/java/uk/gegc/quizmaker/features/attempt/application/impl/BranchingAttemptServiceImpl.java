package uk.gegc.quizmaker.features.attempt.application.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronization;
import uk.gegc.quizmaker.shared.exception.DatabaseConflictException;
import uk.gegc.quizmaker.features.attempt.application.*;
import uk.gegc.quizmaker.features.attempt.application.transaction.AttemptTransactionException;
import uk.gegc.quizmaker.features.attempt.application.transaction.AttemptTransactions;
import uk.gegc.quizmaker.features.attempt.domain.model.access.AttemptAccessException;
import uk.gegc.quizmaker.features.attempt.domain.model.form.*;
import uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.AttemptLifecycle;
import uk.gegc.quizmaker.features.attempt.domain.model.subject.SubjectResolutionException;
import uk.gegc.quizmaker.features.attempt.domain.model.ordering.*;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy.*;
import uk.gegc.quizmaker.features.attempt.domain.model.timing.AttemptTiming;
import uk.gegc.quizmaker.features.attempt.domain.model.trace.ExecutionTrace;
import uk.gegc.quizmaker.features.attempt.domain.repository.*;
import uk.gegc.quizmaker.features.question.application.QuestionRevisionContractService;
import uk.gegc.quizmaker.features.question.application.contract.ResolvedQuestionContracts;
import uk.gegc.quizmaker.features.question.domain.model.contract.ContractResolutionException;
import uk.gegc.quizmaker.features.quiz.application.QuizPracticeSourceService;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.*;
import uk.gegc.quizmaker.features.quiz.domain.repository.ScenarioExecutionSource;
import uk.gegc.quizmaker.features.user.domain.model.PermissionName;

import static uk.gegc.quizmaker.features.attempt.domain.model.form.BranchingException.Reason.*;
import static uk.gegc.quizmaker.features.attempt.domain.model.form.BranchingException.require;

@Service
public class BranchingAttemptServiceImpl implements BranchingAttemptService {
    private final BranchingRepository store;
    private final AuthenticatedSubjectResolver subjects;
    private final QuizPracticeSourceService quizzes;
    private final AttemptLifecycleService lifecycle;
    private final AcceptedResponseRepository answers;
    private final AttemptAccessService access;
    private final PolicySnapshotService snapshots;
    private final FixedFormRepository forms;
    private final FormOptionOrderRepository orders;
    private final ScenarioExecutionSource source;
    private final QuestionRevisionContractService contracts;
    private final AttemptTimingService timing;
    private final ExecutionTraceService trace;
    private final PlatformTransactionManager transactions;
    private final MeterRegistry metrics;
    private final Clock clock;
    private final TransactionTemplate rc;
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    public BranchingAttemptServiceImpl(BranchingRepository store, AuthenticatedSubjectResolver subjects,
            QuizPracticeSourceService quizzes, AttemptLifecycleService lifecycle, AcceptedResponseRepository answers,
            AttemptAccessService access, PolicySnapshotService snapshots, FixedFormRepository forms, FormOptionOrderRepository orders,
            ScenarioExecutionSource source, QuestionRevisionContractService contracts, AttemptTimingService timing,
            ExecutionTraceService trace, PlatformTransactionManager transactions, MeterRegistry metrics, Clock clock) {
        this.store = store; this.subjects = subjects; this.quizzes = quizzes; this.lifecycle = lifecycle; this.answers = answers;
        this.access = access; this.snapshots = snapshots; this.forms = forms; this.orders = orders; this.source = source;
        this.contracts = contracts; this.timing = timing; this.trace = trace; this.transactions = transactions;
        this.metrics = metrics; this.clock = clock; this.rc = AttemptTransactions.readCommitted(transactions);
    }

    @Override @Transactional(propagation = Propagation.MANDATORY)
    public Result bind(Admission a) {
        return checked("bind", true, () -> {
            require(a != null && a.attemptId() != null && a.participantId() != null && a.quizId() != null && a.ownerId() != null
                    && a.launchCommandId() != null && a.revisionId() != null && a.formId() != null && a.policySnapshotId() != null, INVALID);
            var user = subjects.resolveCurrent(a.attemptId()).userId();
            require(user.equals(a.participantId()) && answers.lockOwnBinding(user, a.attemptId()), DENIED);
            var started = lifecycle.lock(a.attemptId());
            require(started.rowVersion() == 1 && started.state().execution() == AttemptLifecycle.Execution.IN_PROGRESS, INACTIVE);
            require(quizzes.lockParticipantAccess(user, a.quizId(), PermissionName.ATTEMPT_CREATE).ownerId().equals(a.ownerId()), DENIED);
            var policy = snapshots.readCurrent(a.ownerId(), a.policySnapshotId()).resolution().policy();
            require(policy.content().quizRevisionId().equals(a.revisionId()) && policy.content().formMode() == FormMode.PROGRESSIVE
                    && policy.content().revisionSchemaVersion() == QuizRevision.SCENARIO_FORMAT, CORRUPT);
            // Other navigation/delivery modes need their own integrated cursor and disclosure.
            require(policy.navigation().mode() == NavigationMode.FREE && policy.delivery().mode() == DeliveryMode.ALL_AT_ONCE
                    && policy.questionSelection().mode() == SelectionMode.ALL && !policy.submission().draftsEnabled(), UNSUPPORTED);
            contracts.declareScope(a.ownerId(), Set.of(a.revisionId()));
            store.bind(a);
            var form = new FixedForm(a.formId(), 1, a.revisionId(), List.of());
            orders.createOrReplay(a.ownerId(), form, new FormOptionOrder(a.formId(), 1, policy.answerOrdering(), List.of()));
            var scope = new ExecutionTrace.Scope(a.attemptId(), a.policySnapshotId(), a.formId());
            trace.append(scope, new ExecutionTrace.Event(1, UUID.randomUUID(), ExecutionTrace.Type.FORM_CREATED,
                    ExecutionTrace.Actor.SYSTEM, new ExecutionTrace.Data(a.launchCommandId(), null, null, null,
                    ExecutionTrace.ReasonCode.MATERIALIZED)));
            var initial = new AttemptLifecycle.State(1, AttemptLifecycle.Execution.CREATED, AttemptLifecycle.AnswerCollection.NOT_STARTED);
            trace.append(scope, new ExecutionTrace.Event(1, UUID.randomUUID(), ExecutionTrace.Type.ATTEMPT_STARTED,
                    ExecutionTrace.Actor.PARTICIPANT, new ExecutionTrace.Data(a.launchCommandId(), null, initial, started.state(),
                    ExecutionTrace.ReasonCode.ACCEPTED)));
            // The existing graded completion contract needs a nonempty form, including on immediate expiry.
            var first = decide(new Command(a.attemptId(), a.launchCommandId(), started.rowVersion(), 0), clock.instant()).result();
            require(first.outcome() == Outcome.APPENDED, UNSUPPORTED);
            return first;
        });
    }

    @Override public Result advance(Command command) {
        AttemptTransactions.requireCompatible(transactions, true);
        boolean joined = TransactionSynchronizationManager.isActualTransactionActive();
        Instant receivedAt = clock.instant();
        try {
            return rc.execute(status -> checked("advance", true, () -> decide(command, receivedAt))).result();
        } catch (TransactionException | DataAccessException ambiguous) {
            if (joined || command == null || command.attemptId() == null || command.decisionId() == null)
                throw new BranchingException(UNAVAILABLE);
            // Only a durable original receipt resolves a lost commit acknowledgement.
            try {
                return rc.execute(status -> checked("reconcile", false, () -> reconcile(command)));
            } catch (TransactionException | DataAccessException unavailable) { throw new BranchingException(UNKNOWN_OUTCOME); }
        }
    }

    private Result reconcile(Command command) {
        try {
            var granted = access.requireBranchAccess(command.attemptId(), AttemptAccessService.Operation.ANSWER);
            head(granted);
            var prior = store.decision(command.attemptId(), granted.formId(), command.decisionId())
                    .orElseThrow(() -> new BranchingException(UNKNOWN_OUTCOME));
            require(prior.command().equals(command), CONFLICT);
            return prior.result();
        } catch (RuntimeException failure) {
            AttemptTransactionException.preserve(failure);
            var reason = reason(failure);
            // A failed receipt lookup cannot establish whether the original command committed.
            throw new BranchingException(reason == UNAVAILABLE || reason == RETRYABLE ? UNKNOWN_OUTCOME : reason);
        }
    }

    private Step decide(Command command, Instant receivedAt) {
        require(command != null && command.attemptId() != null && command.decisionId() != null
                && command.expectedStateVersion() >= 0 && command.expectedFormVersion() >= 0, INVALID);
        UUID attempt = command.attemptId();
        var user = subjects.resolveCurrent(attempt).userId();
        require(answers.lockOwnBinding(user, attempt), DENIED);
        lifecycle.lock(attempt);
        var granted = access.requireBranchAccess(attempt, AttemptAccessService.Operation.ANSWER);
        var head = head(granted);
        var prior = store.decision(attempt, granted.formId(), command.decisionId());
        if (prior.isPresent()) {
            require(prior.get().command().equals(command), CONFLICT);
            require(prior.get().result().formVersion() <= head.formVersion(), CORRUPT);
            return new Step(prior.get().result(), true);
        }
        require(!head.terminalReady() && !granted.lifecycle().state().terminal(), INACTIVE);
        require(command.expectedStateVersion() == granted.stateVersion() && command.expectedFormVersion() == head.formVersion(), STALE);
        var snapshot = snapshots.readCurrent(granted.contentOwnerId(), granted.policySnapshotId());
        contracts.declareScope(granted.contentOwnerId(), Set.of(granted.quizRevisionId()));
        var timer = timing.read(granted, snapshot);
        require(timer == null || timer.decide(receivedAt) == AttemptTiming.Decision.OPEN, INACTIVE);
        var form = prefix(granted, head);
        var order = orders.findByIds(granted.contentOwnerId(), List.of(form.id())).stream().findFirst()
                .orElseThrow(() -> new BranchingException(CORRUPT));
        require(order.policy().equals(snapshot.resolution().policy().answerOrdering()), CORRUPT);
        var revision = source.readGraph(granted.contentOwnerId(), granted.quizId(), granted.quizRevisionId());
        var routes = ScenarioGraphValidator.compile(revision.graph());
        var nodes = revision.graph().nodes().stream().collect(Collectors.toMap(ScenarioGraph.Node::id, n -> n));
        var node = nodes.get(head.currentNode() == null ? revision.graph().entryNodeId() : head.currentNode());
        require(node != null, CORRUPT);
        var edges = new ArrayList<UUID>();
        BranchingRepository.Answer accepted = null;
        var bindings = contracts.resolve(granted.contentOwnerId(), granted.quizRevisionId());
        if (head.currentNode() != null) {
            require(node.type() == ScenarioGraph.NodeType.ITEM && !form.items().isEmpty(), CORRUPT);
            var last = form.items().get(form.items().size()-1);
            require(last.sourceItemId().equals(node.itemId()), CORRUPT);
            var edge = routes.select(node.id(), ScenarioGraphValidator.Evidence.NO_ACCEPTED_ANSWER);
            if (edge.isEmpty()) {
                accepted = store.answer(attempt, form.id(), last.id()).orElse(null);
                if (accepted == null) return new Step(new Result(Outcome.WAITING_FOR_ANSWER, null, head.formVersion()), false);
                var question = source.readQuestion(granted.contentOwnerId(), form.quizRevisionId(), last.sourceItemId());
                var pair = bindings.get(question.id());
                require(pair != null && question.id().equals(accepted.question()) && pair.response().version().equals(accepted.contract()), CORRUPT);
                edge = routes.select(node.id(), score(accepted, question, pair));
            }
            var chosen = edge.orElseThrow(() -> new BranchingException(CORRUPT));
            edges.add(chosen.id());
            node = nodes.get(chosen.toNodeId());
        }
        while (node.type() == ScenarioGraph.NodeType.ROUTING) {
            var edge = routes.select(node.id(), ScenarioGraphValidator.Evidence.NO_ACCEPTED_ANSWER)
                    .orElseThrow(() -> new BranchingException(CORRUPT));
            edges.add(edge.id());
            node = nodes.get(edge.toNodeId());
        }
        UUID evidenceId = accepted == null ? null : accepted.id();
        Result result = node.type() == ScenarioGraph.NodeType.TERMINAL
                ? new Result(Outcome.TERMINAL_READY, null, head.formVersion())
                : appendQuestion(granted, form, order, node.itemId(), command, evidenceId, revision, bindings);
        var traceId = UUID.randomUUID();
        trace.append(new ExecutionTrace.Scope(attempt, granted.policySnapshotId(), form.id()),
                new ExecutionTrace.Event(1, traceId, ExecutionTrace.Type.BRANCH_DECIDED, ExecutionTrace.Actor.SYSTEM,
                        new ExecutionTrace.Data(command.decisionId(), result.formItemId(), null, null, ExecutionTrace.ReasonCode.ACCEPTED)));
        var saved = new BranchingRepository.Saved(command, result, node.id(), evidenceId);
        store.record(form.id(), form.quizRevisionId(), head, saved, edges,
                inputFingerprint(form.quizRevisionId(), command, head.currentNode(), saved.evidenceId()), traceId);
        return new Step(result, false);
    }

    private Result appendQuestion(AttemptAccessService.Access granted, FixedForm form, FormOptionOrder order,
            UUID sourceItemId, Command command, UUID evidenceId, ScenarioExecutionSource.Source revision,
            Map<UUID, ResolvedQuestionContracts> bindings) {
        var question = source.readQuestion(granted.contentOwnerId(), form.quizRevisionId(), sourceItemId);
        var pair = bindings.get(question.id());
        require(pair != null && question.id().equals(revision.questions().get(sourceItemId)), CORRUPT);
        var item = new ProgressiveFormAppend.Item(UUID.randomUUID(), sourceItemId, form.items().size());
        var ref = new ProgressiveFormAppend.Reference(form.id(), form.quizRevisionId(), QuizRevision.SCENARIO_FORMAT);
        var request = new ProgressiveFormAppend.Request(1, ref, command.expectedFormVersion(),
                new ProgressiveFormAppend.Decision(1, ProgressiveFormAppend.DecisionKind.BRANCHING, command.decisionId(), evidenceId == null ? command.decisionId() : evidenceId), List.of(item));
        var optionIds = AnswerOptionOrder.order(form.id(), item.id(), order.policy(), question, pair.response().optionLayout(question));
        ParticipantFormProjector.requireBranchDelivery(question, pair, optionIds);
        var options = new FormOptionOrder.Item(item.id(), item.sourceItemId(), pair.binding(), optionIds);
        var writer = new AuthorizedAppend(store, form, order, options, revision.questions().keySet());
        var receipt = writer.append(request);
        return new Result(Outcome.APPENDED, item.id(), receipt.resultingFormVersion());
    }

    private record Step(Result result, boolean replayed) { }

    /** Scoped to one locked, authorized decision. No independently callable append writer accepts arbitrary suffixes. */
    private record AuthorizedAppend(BranchingRepository store, FixedForm form, FormOptionOrder order,
            FormOptionOrder.Item options, Set<UUID> membership) implements ProgressiveFormAppendPort {
        @Override public ProgressiveFormAppend.Receipt append(ProgressiveFormAppend.Request request) {
            var prefix = form.items().stream().map(i -> new ProgressiveFormAppend.Item(i.id(), i.sourceItemId(), i.position())).toList();
            var state = new ProgressiveFormAppend.State(1, ProgressiveFormAppend.FormKind.PROGRESSIVE, request.reference(), prefix.size(), prefix);
            var revision = new ProgressiveFormAppend.RevisionScope(1, form.quizRevisionId(), QuizRevision.SCENARIO_FORMAT, membership);
            var plan = ProgressiveFormAppendPlanner.plan(state, revision, request, Optional.empty());
            var item = plan.itemsToAppend().get(0);
            store.appendItem(form, order, new FixedForm.Item(item.id(), item.sourceItemId(), item.position()), options);
            return plan.receipt();
        }
    }

    @Override public Path resume(UUID attempt) {
        AttemptTransactions.requireCompatible(transactions, false);
        try {
            return rc.execute(status -> checked("resume", false, () -> {
                require(attempt != null, INVALID);
                var granted = access.requireBranchAccess(attempt, AttemptAccessService.Operation.VIEW);
                var head = head(granted);
                var form = prefix(granted, head);
                return new Path(form.items().stream().map(FixedForm.Item::id).toList(), head.formVersion(), head.terminalReady());
            }));
        } catch (TransactionException | DataAccessException unavailable) { throw new BranchingException(UNAVAILABLE); }
    }

    private BranchingRepository.Head head(AttemptAccessService.Access granted) {
        return store.head(granted.attemptId(), granted.formId(), granted.quizRevisionId(), granted.policySnapshotId());
    }

    private FixedForm prefix(AttemptAccessService.Access granted, BranchingRepository.Head head) {
        var form = forms.findByIds(granted.contentOwnerId(), List.of(granted.formId())).stream().findFirst()
                .orElseThrow(() -> new BranchingException(CORRUPT));
        require(form.items().size() == head.formVersion() && form.quizRevisionId().equals(granted.quizRevisionId()), CORRUPT);
        return form;
    }

    private static ScenarioGraphValidator.Evidence score(BranchingRepository.Answer answer, QuestionRevision question,
                                                          ResolvedQuestionContracts pair) {
        try {
            var response = JSON.readTree(answer.normalized());
            pair.response().validate(question, response);
            StableResponseNormalizer.validateAcceptedBinding(question, pair, response);
            var score = pair.scoring().score(question, response);
            return score.earned().compareTo(score.possible()) == 0 ? ScenarioGraphValidator.Evidence.FULL_CREDIT
                    : ScenarioGraphValidator.Evidence.NOT_FULL_CREDIT;
        } catch (JsonProcessingException | RuntimeException invalid) { throw new BranchingException(CORRUPT); }
    }

    /** Hash the exact versioned inputs. The immutable revision and accepted row resolve their contracts.
     * The selected destination and traversed edges are stored independently as the decision output. */
    private static byte[] inputFingerprint(UUID revision, Command command, UUID sourceNode, UUID acceptedResponse) {
        String metadata = String.join("|", "scenario-input/v1", revision.toString(), command.attemptId().toString(),
                Long.toString(command.expectedStateVersion()), Long.toString(command.expectedFormVersion()),
                Objects.toString(sourceNode, "entry"), Objects.toString(acceptedResponse, "unconditional"));
        try {
            return MessageDigest.getInstance("SHA-256").digest(metadata.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private <T> T checked(String operation, boolean writing, Supplier<T> action) {
        AttemptTransactions.requireCompatible(transactions, writing);
        try {
            T result = action.get();
            String outcome = result instanceof Step step
                    ? (step.replayed() ? "replayed" : step.result().outcome().name().toLowerCase(Locale.ROOT)) : "ok";
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int status) {
                    count(operation, status == STATUS_COMMITTED ? outcome : "uncommitted");
                }
            });
            return result;
        } catch (RuntimeException failure) {
            AttemptTransactionException.preserve(failure);
            var reason = reason(failure);
            count(operation, reason.name().toLowerCase(Locale.ROOT));
            throw new BranchingException(reason);
        }
    }

    private static BranchingException.Reason reason(RuntimeException failure) {
        if (failure instanceof BranchingException known) return known.reason();
        if (DatabaseConflictException.from(failure).isPresent()) return RETRYABLE;
        if (failure instanceof AttemptAccessException denied) return switch (denied.reason()) {
            case UNAVAILABLE -> UNAVAILABLE;
            case RETRYABLE, IN_PROGRESS -> RETRYABLE;
            default -> DENIED;
        };
        if (failure instanceof SubjectResolutionException identity)
            return identity.reason() == SubjectResolutionException.Reason.UNAVAILABLE
                    ? UNAVAILABLE : DENIED;
        if (failure instanceof PublicationException scope)
            return scope.reason() == PublicationException.Reason.PERSISTENCE_UNAVAILABLE ? UNAVAILABLE : DENIED;
        if (failure instanceof ContractResolutionException contract
                && contract.reason() == ContractResolutionException.Reason.UNSUPPORTED_CONTRACT) return UNSUPPORTED;
        if (failure instanceof FormMaterializationException material) return switch (material.reason()) {
            case UNSUPPORTED_FAMILY, UNSUPPORTED_MEDIA -> UNSUPPORTED;
            case PERSISTENCE_UNAVAILABLE -> UNAVAILABLE;
            default -> CORRUPT;
        };
        if (failure instanceof ScenarioGraphValidationException || failure instanceof RevisionIntegrityException
                || failure instanceof ProgressiveFormAppendException) return CORRUPT;
        return UNAVAILABLE;
    }

    private void count(String operation, String outcome) {
        try { metrics.counter("quizmaker.attempt.branch", "version", "1", "operation", operation, "outcome", outcome).increment(); }
        catch (RuntimeException ignored) { /* optional telemetry */ }
    }
}
