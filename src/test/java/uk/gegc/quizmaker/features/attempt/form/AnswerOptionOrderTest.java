package uk.gegc.quizmaker.features.attempt.form;

import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import uk.gegc.quizmaker.features.attempt.domain.model.ordering.*;
import uk.gegc.quizmaker.features.question.application.contract.VersionedResponseContract;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.question.domain.model.contract.*;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.QuestionRevision;

import java.util.*;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.*;
import static uk.gegc.quizmaker.features.attempt.form.FixedFormFixtures.*;
import static uk.gegc.quizmaker.features.attempt.form.OptionOrderFixtures.*;

class AnswerOptionOrderTest {
    @Test
    void shuffledV1HasAFrozenVectorIndependentOfCollectionAndUuidSorting() {
        var original=question("golden",QuestionType.MCQ_SINGLE,0);
        var q=new QuestionRevision(original.id(),original.questionId(),1,original.type(),original.difficulty(),
                original.questionText(),original.content(),null,null,null,null,original.createdAt(),
                IntStream.rangeClosed(1,6).mapToObj(n->new QuestionRevision.Option(new UUID(0,n),"option-"+n,n*3)).toList());
        assertThat(AnswerOptionOrder.order(new UUID(0,100),new UUID(0,200),SHUFFLED,q,layout(q)))
                .containsExactly(new UUID(0,5),new UUID(0,3),new UUID(0,2),new UUID(0,6),new UUID(0,1),new UUID(0,4));
    }

    @ParameterizedTest
    @EnumSource(value=QuestionType.class,names={"MCQ_SINGLE","MCQ_MULTI","FILL_GAP","ORDERING"})
    void exactIdentitiesAndStableOrderDoNotChangeComponentValidationOrScore(QuestionType type) {
        var q=question("identity",type,4);
        var pair=registry(OptionOrderFixtures::layout).resolve(binding(q));
        var expected=q.options().stream().map(QuestionRevision.Option::id).toList();
        var a=AnswerOptionOrder.order(id("form"),id("item"),AUTHORED,q,pair.response().optionLayout(q));
        var b=AnswerOptionOrder.order(id("form"),id("item"),SHUFFLED,q,pair.response().optionLayout(q));
        assertThat(a).containsExactlyElementsOf(expected);
        assertThat(b).containsExactlyInAnyOrderElementsOf(expected);
        for(int i=0;i<20;i++) assertThat(AnswerOptionOrder.order(id("form"),id("item"),SHUFFLED,q,layout(q))).isEqualTo(b);
        var response=TextNode.valueOf(expected.get(0).toString());
        pair.response().validate(q,response);
        assertThat(pair.scoring().score(q,response).earned()).isEqualByComparingTo("1");
        assertThat(pair.scoring().score(q,TextNode.valueOf(expected.get(1).toString())).earned()).isZero();
        assertThat(q.options()).extracting(QuestionRevision.Option::id).containsExactlyElementsOf(expected);
        assertThat(q.content()).contains("privateCorrectId");
    }

    @Test
    void blocksPinsAndSemanticOrderRemainAuthoritativeAcrossManyForms() {
        var q=question("constraints",QuestionType.MCQ_SINGLE,8);
        var ids=q.options().stream().map(QuestionRevision.Option::id).toList();
        var blocks=List.of(new OptionLayout.Block(ids.subList(0,1),OptionLayout.Pin.FIRST),
                new OptionLayout.Block(ids.subList(1,3),OptionLayout.Pin.NONE),
                new OptionLayout.Block(ids.subList(3,5),OptionLayout.Pin.NONE),
                new OptionLayout.Block(ids.subList(5,7),OptionLayout.Pin.NONE),
                new OptionLayout.Block(ids.subList(7,8),OptionLayout.Pin.LAST));
        Set<List<UUID>> orders=new HashSet<>();
        for(int n=0;n<50;n++) {
            var result=AnswerOptionOrder.order(id("form-"+n),id("item"),SHUFFLED,q,new OptionLayout(1,true,blocks));
            orders.add(result);
            assertThat(result.get(0)).isEqualTo(ids.get(0)); assertThat(result.get(7)).isEqualTo(ids.get(7));
            for(int start:List.of(1,3,5)) assertThat(result.indexOf(ids.get(start+1))).isEqualTo(result.indexOf(ids.get(start))+1);
            assertThat(AnswerOptionOrder.order(id("form-"+n),id("item"),SHUFFLED,q,new OptionLayout(1,false,blocks)))
                    .containsExactlyElementsOf(ids);
        }
        assertThat(orders.size()).isGreaterThan(1);
    }

    @ParameterizedTest
    @ValueSource(ints={0,1,2,257})
    void emptySingletonLargeAndGappedAuthoredPositionsHaveNoInventedOptions(int count) {
        var q=question("count",QuestionType.FILL_GAP,count);
        var result=AnswerOptionOrder.order(id("form"),id("item"),SHUFFLED,q,layout(q));
        assertThat(result).hasSize(count).containsExactlyInAnyOrderElementsOf(q.options().stream().map(QuestionRevision.Option::id).toList());
        assertThatThrownBy(()->result.add(id("other"))).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void malformedCompleteMembershipAndPinDeclarationsAreRejectedInBothStrategies() {
        var q=question("bad",QuestionType.MCQ_MULTI,3); var ids=q.options().stream().map(QuestionRevision.Option::id).toList();
        var bad=List.of(new OptionLayout(1,true,List.of()),
                new OptionLayout(1,true,List.of(new OptionLayout.Block(List.of(id("foreign")),OptionLayout.Pin.NONE))),
                new OptionLayout(1,true,List.of(new OptionLayout.Block(List.of(ids.get(1),ids.get(0),ids.get(2)),OptionLayout.Pin.NONE))),
                new OptionLayout(1,true,List.of(new OptionLayout.Block(ids.subList(0,1),OptionLayout.Pin.LAST),
                        new OptionLayout.Block(ids.subList(1,3),OptionLayout.Pin.NONE))));
        for(var policy:List.of(AUTHORED,SHUFFLED)) for(var invalid:bad)
            assertThatThrownBy(()->AnswerOptionOrder.order(id("form"),id("item"),policy,q,invalid))
                    .isInstanceOf(OptionOrderException.class).hasMessage("Option order rejected: INVALID_ORDER");
        assertThatThrownBy(()->new OptionLayout(1,true,List.of(new OptionLayout.Block(List.of(ids.get(0),ids.get(0)),OptionLayout.Pin.NONE))))
                .isInstanceOf(ContractResolutionException.class);
        assertThatThrownBy(()->new OptionLayout(2,true,List.of())).isInstanceOf(ContractResolutionException.class);
        assertThatThrownBy(()->AnswerOptionOrder.order(null,id("item"),SHUFFLED,q,layout(q))).isInstanceOf(OptionOrderException.class);
    }

    @Test
    void existingAdapterDoesNotAcquireOptionSupportAndCollectionsAreDefensive() {
        var q=question("default",QuestionType.TRUE_FALSE,0);
        var old=new VersionedResponseContract() {
            public ContractVersion version(){ return new ContractVersion("fixture.old",1); }
            public void validate(QuestionRevision question,com.fasterxml.jackson.databind.JsonNode response) { }
        };
        assertThatThrownBy(()->old.optionLayout(q)).hasMessage("Question contract rejected: UNSUPPORTED_CONTRACT");
        var ids=new ArrayList<>(List.of(id("a"))); var blocks=new ArrayList<>(List.of(new OptionLayout.Block(ids,OptionLayout.Pin.NONE)));
        var layout=new OptionLayout(1,true,blocks); ids.clear(); blocks.clear();
        assertThat(layout.blocks().get(0).optionIds()).containsExactly(id("a"));
        assertThatThrownBy(()->layout.blocks().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void storedOrderValuesRejectAmbiguousIdentityAndUnknownSchemaWithoutLeakingInput() {
        var q=question("values",QuestionType.MCQ_SINGLE,2);
        var ids=new ArrayList<>(q.options().stream().map(QuestionRevision.Option::id).toList());
        var item=new FormOptionOrder.Item(id("item"),id("source"),binding(q),ids);
        var items=new ArrayList<>(List.of(item));
        var order=new FormOptionOrder(id("form"),1,AUTHORED,items);
        ids.clear();items.clear();assertThat(order.items().get(0).optionIds()).hasSize(2);
        assertThatThrownBy(()->order.items().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(()->new FormOptionOrder(id("form"),2,AUTHORED,List.of()))
                .hasMessage("Option order rejected: UNSUPPORTED_VERSION");
        assertThatThrownBy(()->new FormOptionOrder(id("form"),1,AUTHORED,List.of(item,item)))
                .hasMessage("Option order rejected: INVALID_ORDER");
        assertThatThrownBy(()->new FormOptionOrder.Item(id("item"),id("source"),binding(q),List.of(id("a"),id("a"))))
                .hasMessage("Option order rejected: INVALID_ORDER");
    }
}
