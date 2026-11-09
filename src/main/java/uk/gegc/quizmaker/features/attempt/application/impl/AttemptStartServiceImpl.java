package uk.gegc.quizmaker.features.attempt.application.impl;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import uk.gegc.quizmaker.features.attempt.application.*;
import uk.gegc.quizmaker.features.attempt.domain.model.command.AttemptCommands;
import uk.gegc.quizmaker.features.attempt.domain.model.form.*;
import uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.AttemptLifecycle;
import uk.gegc.quizmaker.features.attempt.domain.model.ordering.AuthoredQuestionOrder;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy.*;
import uk.gegc.quizmaker.features.attempt.domain.model.preset.*;
import uk.gegc.quizmaker.features.attempt.domain.model.receipt.*;
import uk.gegc.quizmaker.features.attempt.domain.model.resolution.PolicyResolutionRequest;
import uk.gegc.quizmaker.features.attempt.domain.model.resolution.PolicyResolutionRequest.*;
import uk.gegc.quizmaker.features.attempt.domain.model.selection.QuestionSelection;
import uk.gegc.quizmaker.features.attempt.domain.model.selection.QuestionSelectionException;
import uk.gegc.quizmaker.features.attempt.domain.model.snapshot.PolicySnapshot;
import uk.gegc.quizmaker.features.attempt.domain.model.start.AttemptStartException;
import uk.gegc.quizmaker.features.attempt.domain.model.subject.*;
import uk.gegc.quizmaker.features.attempt.domain.model.trace.ExecutionTrace;
import uk.gegc.quizmaker.features.attempt.infra.receipt.CanonicalCommandInput;
import uk.gegc.quizmaker.features.attempt.infra.receipt.ReceiptDatabaseFailure;
import uk.gegc.quizmaker.features.question.application.QuestionRevisionContractService;
import uk.gegc.quizmaker.features.question.domain.model.contract.ContractResolutionException;
import uk.gegc.quizmaker.features.quiz.application.QuizPracticeSourceService;
import uk.gegc.quizmaker.shared.exception.DatabaseConflictException;

import java.util.*;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import static uk.gegc.quizmaker.features.attempt.domain.model.start.AttemptStartException.Reason.*;
import static uk.gegc.quizmaker.features.attempt.domain.model.start.AttemptStartException.require;
import static uk.gegc.quizmaker.features.attempt.domain.model.preset.LegacyPracticeRequest.*;

@Service
public class AttemptStartServiceImpl implements AttemptStartService {
    private final AuthenticatedSubjectResolver identity;
    private final QuizPracticeSourceService sources;
    private final AttemptPresetService presets;
    private final AttemptPolicyResolver policies;
    private final PolicySnapshotService snapshots;
    private final QuestionRevisionContractService contracts;
    private final QuestionSelectionService selection;
    private final FixedFormMaterializer forms;
    private final AttemptSubjectService subjects;
    private final AttemptLifecycleService lifecycle;
    private final ExecutionTraceService trace;
    private final CommandReceiptService receipts;
    private final MeterRegistry metrics;
    private final uk.gegc.quizmaker.features.attempt.domain.repository.NavigationRepository navigation;

    public AttemptStartServiceImpl(AuthenticatedSubjectResolver identity, QuizPracticeSourceService sources,
                                   AttemptPresetService presets, AttemptPolicyResolver policies,
                                   PolicySnapshotService snapshots, QuestionRevisionContractService contracts,
                                   QuestionSelectionService selection, FixedFormMaterializer forms,
                                   AttemptSubjectService subjects, AttemptLifecycleService lifecycle,
                                   ExecutionTraceService trace, CommandReceiptService receipts, MeterRegistry metrics,
                                   uk.gegc.quizmaker.features.attempt.domain.repository.NavigationRepository navigation) {
        this.identity = identity; this.sources = sources; this.presets = presets; this.policies = policies;
        this.snapshots = snapshots; this.contracts = contracts; this.selection = selection; this.forms = forms;
        this.subjects = subjects; this.lifecycle = lifecycle; this.trace = trace; this.receipts = receipts; this.metrics = metrics; this.navigation = navigation;
    }

    @Override public Result start(Command command) {
        Timer.Sample timer = null;
        try { timer = Timer.start(metrics); } catch (RuntimeException ignored) { /* best effort */ }
        String outcome = "rejected";
        try {
            validate(command);
            var subject = identity.resolve(UUID.randomUUID());
            var request = new CommandReceipt.Request(1, subject.userId(), command.quizId(), AttemptCommands.Type.START,
                    command.content().quizRevisionId(), command.publicationVersion(), command.key(), fingerprint(command));
            // Invocation-local state: never retained across a retry, thread or process restart.
            class Invocation {
                QuizPracticeSourceService.Access access;
                AttemptStartException failure;
                <T> T checked(Supplier<T> action) {
                    try { return action.get(); }
                    catch (RuntimeException rejected) { failure = translate(rejected); throw failure; }
                }
            }
            var call = new Invocation();
            CommandReceipt.Result result;
            try {
                result = receipts.execute(request,
                        () -> call.checked(() -> call.access = sources.lockAccess(subject.userId(), command.quizId())),
                        id -> call.checked(() -> create(command, subject, call.access, id)));
            } catch (ReceiptException failure) {
                // Only the receipt's confirmed callback denial restores the bounded business reason.
                // Commit uncertainty/deadlock/connection failure must retain the receipt semantics.
                if (call.failure != null && (failure.reason() == ReceiptException.Reason.COMMAND_DENIED
                        || failure.reason() == ReceiptException.Reason.ACCESS_DENIED)) throw call.failure;
                throw failure;
            }
            var ack = result.receipt().acknowledgement();
            outcome = result.replayed() ? "replayed" : "start_returned";
            return new Result(ack.attemptId(), ack.resultId(), command.content().quizRevisionId(), ack.stateVersion(), result.replayed());
        } catch (RuntimeException failure) {
            var bounded = translate(failure);
            outcome = bounded.reason().name().toLowerCase(Locale.ROOT);
            throw bounded;
        } finally {
            try {
                if (timer != null) timer.stop(Timer.builder("quizmaker.attempt.start")
                        .tags("version", "1", "strategy", command == null || command.selection() == null
                                ? "none" : command.selection().strategy().name(), "outcome", outcome).register(metrics));
            } catch (RuntimeException ignored) { /* telemetry never changes command authority */ }
        }
    }

    private CommandReceipt.Acknowledgement create(Command command, AttemptSubject subject,
                                                  QuizPracticeSourceService.Access access, UUID commandId) {
        require(command.content().quizRevisionId().equals(access.currentRevisionId())
                && command.publicationVersion() == access.publicationVersion(), STALE_PUBLICATION);
        require(BuiltInPracticePreset.REFERENCE.equals(command.preset()), UNSUPPORTED_PRESET);
        var mapping = presets.mapLegacy(new LegacyPracticeRequest(1, command.legacyMode(), command.context(), true,
                access.owner() ? ResourceAuthority.OWNER : ResourceAuthority.FULL_CONTENT_EDITOR,
                access.timerEnabled() == null ? QuizTimerState.UNKNOWN
                        : access.timerEnabled() ? QuizTimerState.ENABLED : QuizTimerState.DISABLED, List.of(), null));
        if (mapping instanceof LegacyPracticeMapping.Unsupported) throw new AttemptStartException(UNSUPPORTED_LEGACY_MODE);
        require(mapping instanceof LegacyPracticeMapping.Mapped, UNSUPPORTED_CAPABILITY);
        // Resolve current membership and take canonical exclusive question locks before any
        // shared aggregate read. Otherwise simultaneous starts could both need a lock upgrade.
        contracts.declareScope(access.ownerId(), Set.of(command.content().quizRevisionId()));
        var revision = sources.readRevision(access, command.content().quizRevisionId());
        require(revision.schemaVersion() == command.content().revisionSchemaVersion(), UNSUPPORTED_VERSION);
        contracts.resolve(access.ownerId(), revision.id());
        var overrides = new ArrayList<SourceOverrides>(command.answerOrder() == AnswerOrder.AUTHORED ? List.<SourceOverrides>of()
                : List.of(new SourceOverrides(OverrideSource.PARTICIPANT, 1,
                new AllowedChoices(Set.of(AnswerOrder.AUTHORED, AnswerOrder.SHUFFLED_PER_ATTEMPT), Set.of()),
                List.of(new AnswerOrderChoice(command.answerOrder())))));
        // lockAccess already proved current owner/full-editor authority. No participant grant is accepted.
        var authorChoices = new ArrayList<Choice>();
        if (command.draftsEnabled()) authorChoices.add(new DraftsChoice(true));
        boolean configuredNavigation = !command.navigation().equals(new NavigationPolicy(NavigationMode.FREE));
        if (configuredNavigation) authorChoices.add(new NavigationChoice(command.navigation()));
        boolean configuredDelivery = command.delivery() != DeliveryMode.ALL_AT_ONCE;
        if (configuredDelivery) authorChoices.add(new DeliveryChoice(command.delivery()));
        boolean configuredSelection = !command.selection().equals(QuestionSelection.Configuration.all());
        if (configuredSelection) authorChoices.add(new SelectionChoice(SelectionPolicy.from(command.selection())));
        if (!authorChoices.isEmpty()) overrides.add(new SourceOverrides(OverrideSource.AUTHOR, 1,
                new AllowedChoices(Set.of(), Set.of(), command.draftsEnabled() ? Set.of(true) : Set.of(),
                        configuredNavigation ? Set.of(command.navigation()) : Set.of(),
                        configuredDelivery ? Set.of(command.delivery()) : Set.of(),
                        configuredSelection ? Set.of(SelectionPolicy.from(command.selection())) : Set.of()), authorChoices));
        var resolution = policies.resolve(new PolicyResolutionRequest(1, 1, PolicyResolutionRequest.HASH_ALGORITHM,
                command.preset(), command.content(), overrides));
        var snapshot = snapshots.createOrReplay(access.ownerId(), new PolicySnapshot(UUID.randomUUID(), resolution));
        var policy = resolution.policy();
        var config = policy.questionSelection().configuration();
        var context = config.strategy().random() ? new QuestionSelection.SelectionContext(1, commandId) : null;
        var input = QuestionSelection.Input.fromRevision(policy.content(), revision, config, List.of(), context);
        var selected = selection.select(input);
        require(selected != null && selected.evidence().configuration().equals(config)
                && selected.evidence().quizRevisionId().equals(revision.id())
                && selected.evidence().revisionSchemaVersion() == revision.schemaVersion()
                && selected.itemIds().size() == config.selectionSize(revision.items().size())
                && (!configuredSelection || !selected.itemIds().isEmpty()), SELECTION_REJECTED);
        if (config.strategy().random()) require(selected.evidence().random().context().equals(context)
                && selected.evidence().random().candidateCount() == revision.items().size(), SELECTION_REJECTED);
        var ordered = AuthoredQuestionOrder.order(AuthoredQuestionOrder.Input.fromRevision(policy, revision, selected.itemIds()));
        var form = new FixedForm(UUID.randomUUID(), 1, revision.id(), IntStream.range(0, ordered.itemIds().size())
                .mapToObj(n -> new FixedForm.Item(UUID.randomUUID(), ordered.itemIds().get(n), n)).toList());
        forms.materializeSelected(access.ownerId(), new FixedFormMaterializer.Command(form, selected, ordered, policy.answerOrdering()));
        subjects.bindOrReplay(subject.userId(), subject);
        var initial = lifecycle.initialize(subject.attemptId());
        var decision = AttemptCommands.evaluate(new AttemptCommands.Command(1, AttemptCommands.Type.START),
                new AttemptCommands.Context(AttemptCommands.Access.VERIFIED, policy, initial.state(), 0, null, null),
                new AttemptCommands.Metadata(0, commandId, AttemptLifecycle.CommandIdentity.FRESH));
        require(decision.outcome() == AttemptCommands.Outcome.ALLOWED, UNSUPPORTED_CAPABILITY);
        var transition = AttemptLifecycle.evaluate(initial.state(), AttemptLifecycle.Event.START, AttemptLifecycle.Context.fresh());
        require(transition instanceof AttemptLifecycle.Advance, UNSUPPORTED_CAPABILITY);
        var started = lifecycle.save(subject.attemptId(), 0, ((AttemptLifecycle.Advance) transition).state());
        if (policy.navigation().mode() == NavigationMode.SEQUENTIAL) {
            require(!form.items().isEmpty(), UNSUPPORTED_CAPABILITY);
            navigation.initialize(subject.attemptId(), new uk.gegc.quizmaker.features.attempt.domain.model.navigation.FlatNavigation.Cursor(
                    form.id(), form.items().get(0).id(), 1));
        }
        var scope = new ExecutionTrace.Scope(subject.attemptId(), snapshot.id(), form.id());
        trace.append(scope, new ExecutionTrace.Event(1, UUID.randomUUID(), ExecutionTrace.Type.FORM_CREATED,
                ExecutionTrace.Actor.SYSTEM, new ExecutionTrace.Data(commandId, null, null, null, ExecutionTrace.ReasonCode.MATERIALIZED)));
        trace.append(scope, new ExecutionTrace.Event(1, UUID.randomUUID(), ExecutionTrace.Type.ATTEMPT_STARTED,
                ExecutionTrace.Actor.PARTICIPANT, new ExecutionTrace.Data(commandId, null, initial.state(), started.state(), ExecutionTrace.ReasonCode.ACCEPTED)));
        if (policy.delivery().mode() == DeliveryMode.ONE_AT_A_TIME)
            trace.append(scope, new ExecutionTrace.Event(1, UUID.randomUUID(), ExecutionTrace.Type.QUESTION_DELIVERED,
                    ExecutionTrace.Actor.PARTICIPANT, new ExecutionTrace.Data(commandId, form.items().get(0).id(), null, null, ExecutionTrace.ReasonCode.DELIVERED)));
        return new CommandReceipt.Acknowledgement(1, subject.attemptId(), form.id(), started.rowVersion());
    }

    private static void validate(Command c) {
        require(c != null, INVALID_INPUT);
        require(c.version() == 1, UNSUPPORTED_VERSION);
        require(c.quizId() != null && c.content() != null && c.publicationVersion() > 0 && c.key() != null
                && c.preset() != null && c.context() != null && c.legacyMode() != null && c.legacyMode().length() <= 32
                && c.answerOrder() != null && c.navigation() != null && c.delivery() != null && c.selection() != null, INVALID_INPUT);
    }

    private static CommandReceipt.Fingerprint fingerprint(Command c) {
        var json = JsonNodeFactory.instance.objectNode();
        json.put("version", c.version()).put("quiz", c.quizId().toString())
                .put("revision", c.content().quizRevisionId().toString()).put("publicationVersion", c.publicationVersion())
                .put("revisionVersion", c.content().revisionSchemaVersion()).put("formMode", c.content().formMode().name())
                .put("sections", c.content().sections().name()).put("cases", c.content().cases().name())
                .put("preset", c.preset().id()).put("presetVersion", c.preset().version())
                .put("context", c.context().name()).put("legacyMode", c.legacyMode()).put("answerOrder", c.answerOrder().name());
        if (c.delivery() != DeliveryMode.ALL_AT_ONCE) json.put("delivery", c.delivery().name());
        // Preserve exact fingerprints for starts created before #542 with drafts disabled.
        if (c.draftsEnabled()) json.put("draftsEnabled", true);
        if (!c.navigation().equals(new NavigationPolicy(NavigationMode.FREE))) {
            var nav = json.putObject("navigation").put("mode", c.navigation().mode().name());
            var rules = c.navigation().rules();
            if (rules != null) nav.putObject("rules").put("version", rules.version())
                    .put("allowSkipAndReturn", rules.allowSkipAndReturn()).put("allowAcceptedRevisit", rules.allowAcceptedRevisit());
        }
        if (!c.selection().equals(QuestionSelection.Configuration.all())) {
            var selected = json.putObject("selection").put("version", c.selection().version())
                    .put("strategy", c.selection().strategy().name()).put("algorithmVersion", c.selection().algorithmVersion());
            if (c.selection().count() != null) selected.put("count", c.selection().count());
            if (c.selection().percentageBasisPoints() != null) selected.put("percentageBasisPoints", c.selection().percentageBasisPoints());
            if (c.selection().strategy() == QuestionSelection.Strategy.MANUAL_SET) {
                var ids = selected.putArray("manualItemIds"); c.selection().manualItemIds().forEach(id -> ids.add(id.toString()));
            }
        }
        return CanonicalCommandInput.fingerprint(1, json);
    }

    private static AttemptStartException translate(RuntimeException failure) {
        if (failure instanceof AttemptStartException start) return new AttemptStartException(start.reason());
        if (failure instanceof SubjectResolutionException subject) return new AttemptStartException(switch (subject.reason()) {
            case UNAUTHENTICATED -> UNAUTHENTICATED;
            case UNAVAILABLE -> PERSISTENCE_UNAVAILABLE;
            default -> ACCESS_DENIED;
        });
        if (failure instanceof ReceiptException receipt) return new AttemptStartException(switch (receipt.reason()) {
            case CONFLICT -> CONFLICT;
            case IN_PROGRESS -> IN_PROGRESS;
            case RETRYABLE -> RETRYABLE;
            case EXPIRED -> EXPIRED;
            case UNKNOWN_OUTCOME -> UNKNOWN_OUTCOME;
            case ACCESS_DENIED -> ACCESS_DENIED;
            case INVALID_INPUT -> INVALID_INPUT;
            case UNSUPPORTED_VERSION -> UNSUPPORTED_VERSION;
            default -> PERSISTENCE_UNAVAILABLE;
        });
        if (failure instanceof QuestionSelectionException selected)
            return new AttemptStartException(switch (selected.reason()) {
                case SEED_UNAVAILABLE -> SELECTION_UNAVAILABLE;
                case UNSUPPORTED_VERSION, UNSUPPORTED_REVISION_VERSION, UNSUPPORTED_STRATEGY_VERSION -> UNSUPPORTED_VERSION;
                default -> SELECTION_REJECTED;
            });
        if (failure instanceof ContractResolutionException contract)
            return new AttemptStartException(contract.reason() == ContractResolutionException.Reason.PERSISTENCE_UNAVAILABLE
                    ? PERSISTENCE_UNAVAILABLE : CONTRACT_UNAVAILABLE);
        if (failure instanceof FixedFormException form)
            return new AttemptStartException(form.reason() == FixedFormException.Reason.PERSISTENCE_UNAVAILABLE
                    ? PERSISTENCE_UNAVAILABLE : MATERIAL_UNAVAILABLE);
        if (failure instanceof FormMaterializationException form)
            return new AttemptStartException(form.reason() == FormMaterializationException.Reason.PERSISTENCE_UNAVAILABLE
                    ? PERSISTENCE_UNAVAILABLE : MATERIAL_UNAVAILABLE);
        if (failure instanceof uk.gegc.quizmaker.features.quiz.domain.model.revision.PublicationException)
            return new AttemptStartException(ACCESS_DENIED);
        if (failure instanceof PresetResolutionException) return new AttemptStartException(UNSUPPORTED_PRESET);
        if (failure instanceof DataAccessException || failure instanceof TransactionException
                || failure instanceof DatabaseConflictException)
            return new AttemptStartException(switch (ReceiptDatabaseFailure.reason(failure)) {
                case RETRYABLE -> RETRYABLE;
                case IN_PROGRESS -> IN_PROGRESS;
                default -> PERSISTENCE_UNAVAILABLE;
            });
        // A dependency failure never retains its cause, payload or arbitrary exception message.
        return new AttemptStartException(PERSISTENCE_UNAVAILABLE);
    }
}
