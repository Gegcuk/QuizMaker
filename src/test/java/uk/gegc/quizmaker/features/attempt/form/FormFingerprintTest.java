package uk.gegc.quizmaker.features.attempt.form;

import org.junit.jupiter.api.Test;
import uk.gegc.quizmaker.features.attempt.domain.model.form.FixedForm;
import uk.gegc.quizmaker.features.attempt.domain.model.form.FormFingerprint;
import uk.gegc.quizmaker.features.attempt.domain.model.ordering.FormOptionOrder;
import uk.gegc.quizmaker.features.question.domain.model.contract.ContractVersion;
import uk.gegc.quizmaker.features.question.domain.model.contract.QuestionContractBinding;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.*;
import static uk.gegc.quizmaker.features.attempt.form.OptionOrderFixtures.*;

class FormFingerprintTest {
    private static UUID id(long value) { return new UUID(0, value); }
    private static FixedForm form() {
        return new FixedForm(id(1),1,id(2),List.of(new FixedForm.Item(id(3),id(4),2)));
    }
    private static QuestionContractBinding binding() {
        return new QuestionContractBinding(id(5),new ContractVersion("mcq.single",1),new ContractVersion("mcq.score",1));
    }
    private static FormOptionOrder order() {
        return new FormOptionOrder(id(1),1,AUTHORED,
                List.of(new FormOptionOrder.Item(id(3),id(4),binding(),List.of(id(6),id(7)))));
    }

    @Test void v1GoldenUsesExplicitBigEndianLengthsAndOnly191MetadataBytes() {
        // Independently encoded with Python struct.pack('>i') and UUID.bytes, not this encoder.
        var value = FormFingerprint.compute(form(),order());
        assertThat(value.hex()).isEqualTo("9d1351585d3818df2041b4dcc278e8e2eafbf1dcbc7c4934613788135e253110");
        assertThat(value.inputBytes()).isEqualTo(191);
        assertThat(value.digest()).hasSize(32);
        assertThat(FormFingerprint.compute(form(),order())).isEqualTo(value);
        var copy = value.digest(); copy[0]++;
        assertThat(value.digest()).isNotEqualTo(copy);
        assertThat(value.toString()).doesNotContain(value.hex());
    }

    @Test void eachSavedIdentityPositionPolicyContractAndOptionSequenceAffectsTheDigest() {
        var original = FormFingerprint.compute(form(),order()).hex();
        for (var changed : List.of(new FixedForm(id(8),1,id(2),form().items()),
                new FixedForm(id(1),1,id(8),form().items()),
                new FixedForm(id(1),1,id(2),List.of(new FixedForm.Item(id(3),id(4),3))))) {
            var corresponding = new FormOptionOrder(changed.id(),1,AUTHORED,order().items());
            assertThat(FormFingerprint.compute(changed,corresponding).hex()).isNotEqualTo(original);
        }
        var changedItem = new FixedForm(id(1),1,id(2),List.of(new FixedForm.Item(id(8),id(9),2)));
        assertThat(FormFingerprint.compute(changedItem,new FormOptionOrder(id(1),1,AUTHORED,
                List.of(new FormOptionOrder.Item(id(8),id(9),binding(),List.of(id(6),id(7)))))).hex()).isNotEqualTo(original);
        var changedBindings = List.of(new QuestionContractBinding(id(9),binding().response(),binding().scoring()),
                new QuestionContractBinding(id(5),new ContractVersion("other.response",1),binding().scoring()),
                new QuestionContractBinding(id(5),new ContractVersion("mcq.single",2),binding().scoring()),
                new QuestionContractBinding(id(5),binding().response(),new ContractVersion("other.score",1)),
                new QuestionContractBinding(id(5),binding().response(),new ContractVersion("mcq.score",2)));
        for (var binding : changedBindings) assertThat(FormFingerprint.compute(form(),new FormOptionOrder(id(1),1,AUTHORED,
                List.of(new FormOptionOrder.Item(id(3),id(4),binding,List.of(id(6),id(7)))))).hex()).isNotEqualTo(original);
        for (var options : List.of(List.of(id(7),id(6)),List.of(id(6)),List.<UUID>of(),List.of(id(6),id(8))))
            assertThat(FormFingerprint.compute(form(),new FormOptionOrder(id(1),1,AUTHORED,
                    List.of(new FormOptionOrder.Item(id(3),id(4),binding(),options)))).hex()).isNotEqualTo(original);
        assertThat(FormFingerprint.compute(form(),new FormOptionOrder(id(1),1,SHUFFLED,order().items())).hex()).isNotEqualTo(original);
    }

    @Test void canonicalOrderIsIndependentOfInputCollectionsAndEmptyFormsAreExplicit() {
        var items = List.of(new FixedForm.Item(id(3),id(4),2),new FixedForm.Item(id(8),id(9),4));
        var options = List.of(order().items().get(0),new FormOptionOrder.Item(id(8),id(9),binding(),List.of()));
        var backwards = new ArrayList<>(items); Collections.reverse(backwards);
        var backwardsOptions = new ArrayList<>(options); Collections.reverse(backwardsOptions);
        assertThat(FormFingerprint.compute(new FixedForm(id(1),1,id(2),items),new FormOptionOrder(id(1),1,AUTHORED,options)))
                .isEqualTo(FormFingerprint.compute(new FixedForm(id(1),1,id(2),backwards),new FormOptionOrder(id(1),1,AUTHORED,backwardsOptions)));
        var empty = FormFingerprint.compute(new FixedForm(id(1),1,id(2),List.of()),new FormOptionOrder(id(1),1,AUTHORED,List.of()));
        assertThat(empty.inputBytes()).isEqualTo(68);
        assertThat(empty.hex()).isNotEqualTo(FormFingerprint.compute(form(),order()).hex());
    }

    @Test void maximumLengthContractIdsAnd500ItemsWithSixOptionsHashOnly212068Bytes() {
        var binding = new QuestionContractBinding(id(5),new ContractVersion("a".repeat(128),1),new ContractVersion("b".repeat(128),1));
        var items = IntStream.range(0,500).mapToObj(i -> new FixedForm.Item(id(100+i),id(1000+i),i)).toList();
        var orders = items.stream().map(i -> new FormOptionOrder.Item(i.id(),i.sourceItemId(),binding,
                IntStream.range(0,6).mapToObj(n -> id(10000+n)).toList())).toList();
        var result = FormFingerprint.compute(new FixedForm(id(1),1,id(2),items),new FormOptionOrder(id(1),1,AUTHORED,orders));
        assertThat(result.inputBytes()).isEqualTo(212068).isLessThan(208*1024);
        assertThat(FormFingerprint.compute(new FixedForm(id(1),1,id(2),items),
                new FormOptionOrder(id(1),1,SHUFFLED,orders)).inputBytes()).isEqualTo(212080).isLessThan(208*1024);
        // The API accepts metadata types only; source size has no input path into this calculation.
        System.out.println("FORM_FINGERPRINT_METADATA items=500 options=3000 bytes=" + result.inputBytes());
    }

    @Test void missingForeignAndMismatchedEvidenceIsRejectedWithoutIdentifiers() {
        for (Runnable invalid : List.<Runnable>of(() -> FormFingerprint.compute(null,order()),
                () -> FormFingerprint.compute(form(),null),
                () -> FormFingerprint.compute(form(),new FormOptionOrder(id(9),1,AUTHORED,order().items())),
                () -> FormFingerprint.compute(form(),new FormOptionOrder(id(1),1,AUTHORED,List.of())),
                () -> FormFingerprint.compute(form(),new FormOptionOrder(id(1),1,AUTHORED,
                        List.of(new FormOptionOrder.Item(id(3),id(9),binding(),List.of())))),
                () -> FormFingerprint.compute(form(),new FormOptionOrder(id(1),1,AUTHORED,
                        List.of(new FormOptionOrder.Item(id(9),id(4),binding(),List.of())))))) {
            assertThatThrownBy(invalid::run).hasMessage("Option order rejected: INVALID_ORDER").hasNoCause();
        }
    }
}
