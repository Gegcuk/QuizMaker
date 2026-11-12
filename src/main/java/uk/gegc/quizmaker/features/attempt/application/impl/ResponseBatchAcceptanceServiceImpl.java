package uk.gegc.quizmaker.features.attempt.application.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gegc.quizmaker.features.attempt.application.*;
import uk.gegc.quizmaker.features.attempt.domain.model.command.AttemptCommands;
import uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.LifecycleState;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy.DeliveryMode;
import uk.gegc.quizmaker.features.attempt.domain.model.receipt.*;
import uk.gegc.quizmaker.features.attempt.domain.model.response.*;
import uk.gegc.quizmaker.features.attempt.domain.model.response.ResponseBatch.*;
import uk.gegc.quizmaker.features.attempt.domain.repository.*;
import uk.gegc.quizmaker.features.attempt.infra.receipt.CanonicalCommandInput;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import static uk.gegc.quizmaker.features.attempt.domain.model.response.ResponseAcceptanceException.Reason.*;
import static uk.gegc.quizmaker.features.attempt.domain.model.response.ResponseAcceptanceException.require;

@Service
public class ResponseBatchAcceptanceServiceImpl implements ResponseBatchAcceptanceService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final AuthenticatedSubjectResolver identity;
    private final AttemptAccessService access;
    private final AttemptReadFactsService facts;
    private final AttemptLifecycleService lifecycle;
    private final AcceptedResponseRepository answers;
    private final CommandReceiptService receipts;
    private final ExecutionTraceService trace;
    private final ResponseBatchResultRepository results;
    private final Clock clock;
    private final MeterRegistry metrics;
    private final TransactionTemplate write;

    public ResponseBatchAcceptanceServiceImpl(AuthenticatedSubjectResolver identity, AttemptAccessService access,
            AttemptReadFactsService facts, AttemptLifecycleService lifecycle, AcceptedResponseRepository answers,
            CommandReceiptService receipts, ExecutionTraceService trace, ResponseBatchResultRepository results,
            Clock clock, MeterRegistry metrics, PlatformTransactionManager transactions) {
        this.identity = identity; this.access = access; this.facts = facts; this.lifecycle = lifecycle;
        this.answers = answers; this.receipts = receipts; this.trace = trace; this.results = results;
        this.clock = clock; this.metrics = metrics; write = new TransactionTemplate(transactions);
    }

    @Override public BatchResult acceptBatch(BatchCommand command) {
        var timer = Timer.start(); String outcome = "unavailable";
        class Invocation {
            RuntimeException failure;
            boolean entered, finished;
            <T> T checked(Supplier<T> work) {
                try { return work.get(); }
                catch (RuntimeException error) {
                    failure = error instanceof ResponseBatchException ? error : AttemptCommandFailures.translate(error);
                    throw failure;
                }
            }
        }
        var call = new Invocation();
        try {
            var receivedAt = clock.instant();
            require(command != null && command.attemptId() != null && command.expectedStateVersion() >= 0
                    && command.key() != null && command.responses() != null, INVALID_INPUT);
            if (command.responses().size() > MAX_ITEMS) throw new ResponseBatchException(ResponseBatchException.Reason.LIMIT);
            if (command.responses().isEmpty()) throw new ResponseBatchException(ResponseBatchException.Reason.INVALID_ITEMS);
            var subject = identity.resolve(command.attemptId());
            // Freeze caller-owned mutable JSON before identity calculation or locking.
            var items = command.responses().stream().map(i -> {
                require(i != null && i.formItemId() != null && i.type() != null && i.response() != null, INVALID_INPUT);
                return new BatchItem(i.formItemId(), i.type(), i.response().deepCopy());
            }).toList();
            var fingerprint = fingerprint(items);
            // SUBMIT_ANSWER is the receipt operation; target=attempt plus the typed fingerprint
            // separates the aggregate command from single-item acceptance without changing view actions.
            var request = new CommandReceipt.Request(1, subject.userId(), command.attemptId(), AttemptCommands.Type.SUBMIT_ANSWER,
                    command.attemptId(), command.expectedStateVersion(), command.key(), fingerprint);
            var result = write.execute(status -> { call.entered = true; return call.checked(() -> {
                CommandReceipt.Result receipt;
                try {
                    receipt = receipts.execute(request, () -> call.checked(() -> {
                        require(answers.lockOwnBinding(subject.userId(), command.attemptId()), NOT_FOUND);
                        lifecycle.lock(command.attemptId());
                        return access.requireAccess(command.attemptId(), AttemptAccessService.Operation.ANSWER);
                    }), id -> call.checked(() -> acceptFresh(command, items, id, receivedAt)));
                } catch (ReceiptException failure) {
                    if (call.failure != null && (failure.reason() == ReceiptException.Reason.ACCESS_DENIED
                            || failure.reason() == ReceiptException.Reason.COMMAND_DENIED)) throw call.failure;
                    throw failure;
                }
                var ack = receipt.receipt().acknowledgement();
                require(ack.resultId().equals(receipt.receipt().id()), CORRUPT);
                var saved = results.read(ack.resultId(), ack.attemptId(), ack.stateVersion());
                var value = new BatchResult(saved.attemptId(), saved.responses(), saved.stateVersion(), receipt.replayed());
                call.finished = true; return value;
            }); });
            outcome = result.replayed() ? "replayed" : "accepted"; return result;
        } catch (RuntimeException failure) {
            // Only the callback's own failure proves rollback. Completion failure may follow commit.
            var safe = call.finished || call.entered && failure != call.failure
                    ? new ResponseAcceptanceException(UNKNOWN_OUTCOME) : failure;
            if (safe instanceof ResponseBatchException batch) {
                outcome = batch.reason().name().toLowerCase(Locale.ROOT); throw batch;
            }
            var bounded = AttemptCommandFailures.translate(safe); outcome = bounded.reason().name().toLowerCase(Locale.ROOT); throw bounded;
        } finally {
            int size = command == null || command.responses() == null ? 0 : command.responses().size();
            String bucket = size < 1 || size > MAX_ITEMS ? "invalid" : size == 1 ? "1" : size <= 10 ? "2_10" : size <= 100 ? "11_100" : "101_500";
            try { timer.stop(Timer.builder("quizmaker.attempt.response.batch").tags("version", "1", "outcome", outcome, "size", bucket).register(metrics)); }
            catch (RuntimeException ignored) { /* optional telemetry cannot alter acceptance */ }
        }
    }

    private CommandReceipt.Acknowledgement acceptFresh(BatchCommand command, List<BatchItem> input, UUID commandId,
                                                       java.time.Instant receivedAt) {
        var saved = facts.read(command.attemptId(), AttemptReadFactsService.Purpose.ACCEPT_RESPONSE, receivedAt);
        require(saved.policy().delivery().mode() == DeliveryMode.ALL_AT_ONCE, CONFLICT);
        ParticipantFormProjector.project(saved.form(), saved.revision(), saved.order(), saved.contracts());
        require(saved.access().lifecycle().rowVersion() == command.expectedStateVersion(), STALE);
        var accepted = new HashSet<UUID>();
        for (var reference : answers.references(command.attemptId())) {
            require(reference.formId().equals(saved.form().id()), CORRUPT);
            require(accepted.add(reference.formItemId()), CORRUPT);
        }
        var positions = new HashMap<UUID, Integer>();
        saved.form().items().forEach(i -> positions.put(i.id(), i.position()));
        var counts = new HashMap<UUID, Integer>(); input.forEach(i -> counts.merge(i.formItemId(), 1, Integer::sum));
        var indexes = new ArrayList<Integer>(); for (int i = 0; i < input.size(); i++) indexes.add(i);
        indexes.sort(Comparator.<Integer>comparingInt(i -> positions.getOrDefault(input.get(i).formItemId(), Integer.MAX_VALUE))
                .thenComparingInt(i -> i));
        var errors = new ArrayList<ResponseBatchException.ItemError>();
        var prepared = new ArrayList<AcceptedResponseWork.Prepared>(); boolean conflict = false;
        for (int index : indexes) {
            var item = input.get(index); ResponseBatchException.Code code = null;
            if (!positions.containsKey(item.formItemId())) code = ResponseBatchException.Code.UNKNOWN_ITEM;
            else if (counts.get(item.formItemId()) > 1) code = ResponseBatchException.Code.DUPLICATE_ITEM;
            else if (accepted.contains(item.formItemId())) { code = ResponseBatchException.Code.ANSWER_LOCKED; conflict = true; }
            else try {
                validateElements(item.response());
                var stable = StableResponseNormalizer.canonical(item.type(), item.response());
                // Single-response MCQ normalization deduplicates UUID aliases. A batch must
                // reject those aliases, including case differences, rather than silently merge.
                if (item.type() == QuestionType.MCQ_MULTI) require(stable.path("selectedOptionIds").size()
                        == item.response().path("selectedOptionIds").size(), INVALID_INPUT);
                validateElements(stable);
                prepared.add(AcceptedResponseWork.prepare(saved, item.formItemId(), item.type(), stable,
                        command.expectedStateVersion(), commandId, () -> false));
            } catch (uk.gegc.quizmaker.features.question.application.QuestionContractException invalid) {
                code = ResponseBatchException.Code.INVALID_RESPONSE;
            } catch (ResponseAcceptanceException invalid) {
                if (invalid.reason() != INVALID_INPUT && invalid.reason() != UNSUPPORTED) throw invalid;
                code = invalid.reason() == UNSUPPORTED ? ResponseBatchException.Code.UNSUPPORTED_CONTRACT : ResponseBatchException.Code.INVALID_RESPONSE;
            }
            if (code != null) errors.add(new ResponseBatchException.ItemError(index, code));
        }
        if (!errors.isEmpty()) throw new ResponseBatchException(conflict ? ResponseBatchException.Reason.CONFLICT
                : ResponseBatchException.Reason.INVALID_ITEMS, errors);
        require(command.expectedStateVersion() <= Long.MAX_VALUE - prepared.size(), CORRUPT);
        var current = saved.access().lifecycle(); var acknowledgements = new ArrayList<AcceptedItem>();
        for (var item : prepared) {
            // Each immutable response retains its own command identity; the parent projection
            // records this aggregate's exact result. No extra receipt or transaction is opened.
            var itemCommand = ResponseBatch.itemCommand(commandId, item.itemId());
            var written = AcceptedResponseWork.persist(item, saved, current, itemCommand, answers, lifecycle, trace, clock);
            acknowledgements.add(new AcceptedItem(item.itemId(), written.acknowledgement().resultId()));
            current = written.state();
        }
        var result = new BatchResult(command.attemptId(), acknowledgements, current.rowVersion(), false);
        results.insert(commandId, result);
        return new CommandReceipt.Acknowledgement(1, command.attemptId(), commandId, result.stateVersion());
    }

    static void validateElements(JsonNode response) {
        if (response.isTextual()) require(response.textValue().getBytes(StandardCharsets.UTF_8).length <= MAX_TEXT_BYTES, INVALID_INPUT);
        if (response.isObject()) response.elements().forEachRemaining(ResponseBatchAcceptanceServiceImpl::validateElements);
        if (response.isArray()) {
            var unique = new HashSet<JsonNode>();
            for (var element : response) { require(unique.add(element), INVALID_INPUT); validateElements(element); }
        }
    }

    private static CommandReceipt.Fingerprint fingerprint(List<BatchItem> items) {
        var raw = JSON.createObjectNode(); var values = raw.putArray("responses");
        items.forEach(i -> values.addObject().put("formItemId", i.formItemId().toString()).set("response", i.response()));
        class Sink extends java.io.OutputStream {
            int remaining = MAX_BODY_BYTES; boolean exceeded;
            @Override public void write(int b) throws java.io.IOException { write(new byte[]{(byte)b}, 0, 1); }
            @Override public void write(byte[] b, int off, int len) throws java.io.IOException {
                remaining -= len;
                if (remaining < 0) { exceeded = true; throw new java.io.IOException("Batch input limit"); }
            }
        }
        var sink = new Sink();
        try { JSON.writeValue(new java.io.OutputStreamWriter(sink, StandardCharsets.UTF_8), raw); }
        catch (java.io.IOException invalid) { throw new ResponseBatchException(sink.exceeded
                ? ResponseBatchException.Reason.LIMIT : ResponseBatchException.Reason.MALFORMED); }
        // Component hashes keep the bounded canonicalizer below its node ceiling even for
        // 500 valid responses with 100 gaps each. No stored content/document is reread or hashed.
        var identity = JSON.createObjectNode().put("command", "RESPONSE_BATCH_V1"); var identities = identity.putArray("responses");
        items.forEach(i -> identities.addObject().put("formItemId", i.formItemId().toString()).put("type", i.type().name())
                .put("response", CanonicalCommandInput.fingerprint(1, JSON.createObjectNode().set("response", i.response())).digest()));
        return CanonicalCommandInput.fingerprint(1, identity);
    }
}
