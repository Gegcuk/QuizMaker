package uk.gegc.quizmaker.features.result.application.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gegc.quizmaker.features.attempt.application.*;
import uk.gegc.quizmaker.features.attempt.domain.model.trace.ExecutionTrace;
import uk.gegc.quizmaker.features.attempt.domain.model.snapshot.PolicySnapshotException;
import uk.gegc.quizmaker.features.attempt.domain.model.response.ResponseAcceptanceException;
import uk.gegc.quizmaker.features.question.application.QuestionContractException;
import uk.gegc.quizmaker.features.question.application.contract.ResolvedQuestionContracts;
import uk.gegc.quizmaker.features.question.application.contract.v1.BaselineQuestionContracts;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.question.domain.model.contract.ContractResolutionException;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.QuestionRevision;
import uk.gegc.quizmaker.features.result.application.AutomaticResultService;
import uk.gegc.quizmaker.features.result.domain.model.*;
import uk.gegc.quizmaker.features.result.domain.repository.AutomaticResultRepository;
import uk.gegc.quizmaker.shared.exception.DatabaseConflictException;
import static uk.gegc.quizmaker.features.result.domain.model.AutomaticResultException.*;
import static uk.gegc.quizmaker.features.result.domain.model.AutomaticResultException.Reason.*;

@Service
public class AutomaticResultServiceImpl implements AutomaticResultService {
    private static final ObjectMapper JSON=new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private final SubmittedAttemptSource source;
    private final AutomaticResultRepository results;
    private final ExecutionTraceService trace;
    private final Clock clock;
    private final MeterRegistry metrics;
    private final TransactionTemplate write,read;
    public AutomaticResultServiceImpl(SubmittedAttemptSource source,AutomaticResultRepository results,ExecutionTraceService trace,
            Clock clock,MeterRegistry metrics,PlatformTransactionManager transactions) {
        this.source=source; this.results=results; this.trace=trace; this.clock=clock; this.metrics=metrics;
        write=new TransactionTemplate(transactions); write.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        read=new TransactionTemplate(transactions); read.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW); read.setReadOnly(true);
    }
    @Override public AutomaticResult finalizeSubmission(UUID id) {
        require(id!=null,INVALID_INPUT);
        return execute("finalize",write,()->{
            var facts=source.lock(id);
            var existing=results.find(id);
            if(existing.isPresent()) { verifyReplay(existing.get(),facts); return existing.get(); }
            var s=facts.submission();
            var questions=facts.revision().items().stream().collect(Collectors.toMap(i->i.id(),i->i.question()));
            var answers=facts.responses().stream().collect(Collectors.toMap(a->a.formItemId(),a->a));
            var grades=new ArrayList<AutomaticResult.Item>();
            long earned=0;
            for(var item:facts.form().items()) {
                var q=questions.get(item.sourceItemId()); var contracts=facts.contracts().get(q.id());
                require(contracts!=null && contracts.response().version().equals(BaselineQuestionContracts.response(q.type()))
                        && contracts.scoring().version().equals(BaselineQuestionContracts.scoring(q.type())),UNSUPPORTED);
                var answer=answers.get(item.id());
                // Even unanswered items require a valid captured contract/content; missing evidence
                // must never be mistaken for a permitted unanswered response.
                contracts.response().optionLayout(q);
                int points=0;
                if(answer!=null) {
                    JsonNode response;
                    try { response=JSON.readTree(answer.normalizedResponse()); }
                    catch(JsonProcessingException invalid) { throw new AutomaticResultException(CORRUPT); }
                    contracts.response().validate(q,response);
                    validateAcceptedBinding(q,contracts,response);
                    var score=contracts.scoring().score(q,response);
                    require(score.possible().compareTo(java.math.BigDecimal.ONE)==0,UNSUPPORTED);
                    points=score.earned().intValueExact(); require(points==0 || points==1,UNSUPPORTED);
                }
                earned+=points;
                grades.add(new AutomaticResult.Item(item.id(),q.id(),answer==null?null:answer.id(),grades.size(),
                        contracts.response().version(),contracts.scoring().version(),points));
            }
            var result=new AutomaticResult(UUID.randomUUID(),1,id,s.attemptId(),s.formId(),s.policySnapshotId(),s.quizRevisionId(),
                    facts.snapshot().resolution().hash(),AutomaticResult.Score.calculate(earned,grades.size(),
                    facts.snapshot().resolution().policy().results().passThresholdBasisPoints()),clock.instant(),grades);
            results.insert(result);
            trace.append(new ExecutionTrace.Scope(s.attemptId(),s.policySnapshotId(),s.formId()),new ExecutionTrace.Event(1,result.id(),
                    ExecutionTrace.Type.AUTOMATIC_GRADE_PRODUCED,ExecutionTrace.Actor.SYSTEM,
                    new ExecutionTrace.Data(s.commandId(),null,null,null,ExecutionTrace.ReasonCode.GRADED)));
            results.enqueue(result);
            return result;
        });
    }
    private static void validateAcceptedBinding(QuestionRevision q,ResolvedQuestionContracts contracts,JsonNode response) {
        // Reverse only the frozen source-key adapter, then use the SAME canonicalization and
        // membership validation as #543. This preserves accepted empty/partial/duplicate-order
        // semantics while rejecting stored values the actual accepting writer cannot produce.
        var stable=((ObjectNode)response).deepCopy();
        var ids=q.options().stream().collect(Collectors.toMap(o->o.sourceKey(),o->o.id().toString()));
        java.util.function.Function<JsonNode,String> id=key->{
            String value=ids.get(key.asText()); require(value!=null,CORRUPT); return value;
        };
        switch(q.type()) {
            case MCQ_SINGLE -> stable.put("selectedOptionId",id.apply(response.get("selectedOptionId")));
            case MCQ_MULTI, ORDERING -> {
                String field=q.type()==QuestionType.MCQ_MULTI?"selectedOptionIds":"orderedItemIds";
                var values=stable.putArray(field); response.get(field).forEach(key->values.add(id.apply(key)));
            }
            case TRUE_FALSE, FILL_GAP -> { }
            default -> throw new AutomaticResultException(UNSUPPORTED);
        }
        try {
            require(StableResponseNormalizer.bind(q,contracts,StableResponseNormalizer.canonical(q.type(),stable)).equals(response),CORRUPT);
        } catch(ResponseAcceptanceException invalid) {
            throw new AutomaticResultException(CORRUPT);
        }
    }
    private static void verifyReplay(AutomaticResult r,SubmittedAttemptSource.Facts f) {
        var s=f.submission();
        require(r.submissionId().equals(s.id()) && r.attemptId().equals(s.attemptId()) && r.formId().equals(s.formId())
                && r.policySnapshotId().equals(s.policySnapshotId()) && r.quizRevisionId().equals(s.quizRevisionId())
                && r.policyHash().equals(f.snapshot().resolution().hash())
                && r.score().thresholdBasisPoints()==f.snapshot().resolution().policy().results().passThresholdBasisPoints(),CORRUPT);
        require(r.items().stream().map(AutomaticResult.Item::formItemId).toList().equals(f.form().items().stream().map(i->i.id()).toList()),CORRUPT);
        var answers=f.responses().stream().collect(Collectors.toMap(a->a.formItemId(),a->a.id()));
        var questions=f.revision().items().stream().collect(Collectors.toMap(i->i.id(),i->i.question()));
        for(int i=0;i<r.items().size();i++) {
            var item=r.items().get(i); var q=questions.get(f.form().items().get(i).sourceItemId());
            var binding=f.contracts().get(q.id());
            require(Objects.equals(item.responseId(),answers.get(item.formItemId())) && item.questionRevisionId().equals(q.id())
                    && binding!=null && item.responseContract().equals(binding.response().version())
                    && item.scoringContract().equals(binding.scoring().version()),CORRUPT);
        }
    }
    @Override public List<UUID> pending(int limit) {
        require(limit>0 && limit<=100,INVALID_INPUT);
        return execute("pending",read,()->List.copyOf(results.pending(limit)));
    }
    private <T>T execute(String operation,TransactionTemplate tx,Supplier<T> action) {
        var timer=Timer.start(); String outcome="unavailable";
        try {
            var result=tx.execute(s->{
                try { return action.get(); }
                // TransactionTemplate logs the callback failure before returning control here.
                // Drop driver/content diagnostics before that rollback boundary sees them.
                catch(RuntimeException failure) { throw translate(failure); }
            });
            outcome="committed"; return result;
        }
        catch(RuntimeException failure) {
            var safe=translate(failure); outcome=safe.reason().name().toLowerCase(Locale.ROOT); throw safe;
        } finally {
            try { timer.stop(Timer.builder("quizmaker.result.automatic").tags("version","1","operation",operation,"outcome",outcome).register(metrics)); }
            catch(RuntimeException ignored) { /* Optional telemetry cannot change a committed result. */ }
        }
    }
    private static AutomaticResultException translate(RuntimeException failure) {
        if(failure instanceof AutomaticResultException known) return new AutomaticResultException(known.reason());
        var conflict=DatabaseConflictException.from(failure);
        if(conflict.isPresent()) return new AutomaticResultException(conflict.get().kind()==DatabaseConflictException.Kind.DEADLOCK?RETRYABLE:BUSY);
        if(failure instanceof SubmittedAttemptSource.Failure known) return new AutomaticResultException(Reason.valueOf(known.reason().name()));
        if(failure instanceof ContractResolutionException contract) return new AutomaticResultException(switch(contract.reason()) {
            case PERSISTENCE_UNAVAILABLE -> UNAVAILABLE;
            case UNSUPPORTED_CONTRACT -> UNSUPPORTED;
            default -> CORRUPT;
        });
        if(failure instanceof PolicySnapshotException snapshot) return new AutomaticResultException(switch(snapshot.reason()) {
            case PERSISTENCE_UNAVAILABLE -> UNAVAILABLE;
            case UNSUPPORTED_VERSION, UNSUPPORTED_HASH_ALGORITHM -> UNSUPPORTED;
            default -> CORRUPT;
        });
        if(failure instanceof QuestionContractException || failure instanceof IllegalArgumentException || failure instanceof ArithmeticException)
            return new AutomaticResultException(CORRUPT);
        return new AutomaticResultException(UNAVAILABLE);
    }
}
