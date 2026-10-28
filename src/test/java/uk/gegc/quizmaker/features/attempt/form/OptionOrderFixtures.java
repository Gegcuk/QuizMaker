package uk.gegc.quizmaker.features.attempt.form;

import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import uk.gegc.quizmaker.features.attempt.domain.model.form.FixedForm;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy.AnswerOrder;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy.AnswerOrderingPolicy;
import uk.gegc.quizmaker.features.question.application.QuestionContractRegistry;
import uk.gegc.quizmaker.features.question.application.contract.*;
import uk.gegc.quizmaker.features.question.application.impl.QuestionContractRegistryImpl;
import uk.gegc.quizmaker.features.question.domain.model.*;
import uk.gegc.quizmaker.features.question.domain.model.contract.*;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.IntStream;

import static uk.gegc.quizmaker.features.attempt.form.FixedFormFixtures.*;

/** Explicit component adapters only. No claim of #701 family validation/scoring integration. */
final class OptionOrderFixtures {
    static final AnswerOrderingPolicy AUTHORED = new AnswerOrderingPolicy(AnswerOrder.AUTHORED,1);
    static final AnswerOrderingPolicy SHUFFLED = new AnswerOrderingPolicy(AnswerOrder.SHUFFLED_PER_ATTEMPT,1);
    static final List<QuestionType> FAMILIES = List.of(QuestionType.MCQ_SINGLE,QuestionType.MCQ_MULTI,
            QuestionType.TRUE_FALSE,QuestionType.FILL_GAP,QuestionType.ORDERING);
    static ContractVersion responseVersion(QuestionType type) {
        return new ContractVersion("fixture.option."+type.name().toLowerCase(Locale.ROOT).replace('_','-'),1);
    }
    static QuestionContractBinding binding(QuestionRevision q) {
        return new QuestionContractBinding(q.id(),responseVersion(q.type()),new ContractVersion("fixture.option.scoring",1));
    }
    static QuestionRevision question(String key, QuestionType type, int count) {
        return new QuestionRevision(id(key),id("draft-"+key),1,type,Difficulty.EASY,"Fixture question",
                "{\"privateCorrectId\":\""+id(key+"-option-0")+"\"}",null,"private explanation",null,null,
                Instant.parse("2026-10-27T12:00:00Z"),IntStream.range(0,count)
                .mapToObj(n->new QuestionRevision.Option(id(key+"-option-"+n),"key-"+n,n*2)).toList());
    }
    static QuizRevision quiz(String key, QuestionRevision... questions) {
        return new QuizRevision(id(key),id("draft-quiz"),OWNER,1,"Fixture quiz",null,
                Instant.parse("2026-10-27T12:00:00Z"),IntStream.range(0,questions.length)
                .mapToObj(n->new QuizRevision.Item(id(key+"-source-"+n),n,questions[n])).toList());
    }
    static FixedForm form(String key, QuizRevision revision) {
        return FixedFormFixtures.form(key,revision,revision.items().size());
    }
    static OptionLayout layout(QuestionRevision q) {
        return new OptionLayout(1,true,q.options().stream()
                .map(o->new OptionLayout.Block(List.of(o.id()),OptionLayout.Pin.NONE)).toList());
    }
    static QuestionContractRegistry registry(Function<QuestionRevision,OptionLayout> layout) {
        List<VersionedResponseContract> responses=FAMILIES.stream().map(type->(VersionedResponseContract)new VersionedResponseContract() {
            public ContractVersion version() { return responseVersion(type); }
            public OptionLayout optionLayout(QuestionRevision q) { return layout.apply(q); }
            public void validate(QuestionRevision q,JsonNode response) {
                if (response==null || !response.isTextual() || q.options().stream().noneMatch(o->o.id().toString().equals(response.textValue())))
                    throw new IllegalArgumentException("Invalid fixture option identity");
            }
        }).toList();
        var scoring=new VersionedScoringContract() {
            public ContractVersion version() { return new ContractVersion("fixture.option.scoring",1); }
            public ContractScore score(QuestionRevision q,JsonNode response) {
                boolean correct=!q.options().isEmpty() && q.options().get(0).id().toString().equals(response.textValue());
                return new ContractScore(correct?BigDecimal.ONE:BigDecimal.ZERO,BigDecimal.ONE);
            }
        };
        return new QuestionContractRegistryImpl(responses,List.of(scoring),new SimpleMeterRegistry());
    }
}
