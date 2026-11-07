package uk.gegc.quizmaker.features.result.domain;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import uk.gegc.quizmaker.features.question.domain.model.contract.ContractVersion;
import uk.gegc.quizmaker.features.result.domain.model.*;
import static org.assertj.core.api.Assertions.*;

class AutomaticResultTest {
    @ParameterizedTest
    @CsvSource({"0,10,7000,0,false","7,10,7000,7000,true","2,3,7000,6667,false",
            "1,32,7000,313,false","10000,200000001,7000,0,false","13999,20000,7000,7000,false","14000,20000,7000,7000,true",
            "0,10,0,0,true","10,10,10000,10000,true","9,10,10000,9000,false",
            "9223372036854775806,9223372036854775807,10000,10000,false"})
    void halfUpPresentationNeverDecidesExactPass(long earned,long possible,int threshold,int basisPoints,boolean passed) {
        assertThat(AutomaticResult.Score.calculate(earned,possible,threshold))
                .isEqualTo(new AutomaticResult.Score(earned,possible,basisPoints,threshold,passed));
    }
    @ParameterizedTest
    @CsvSource({"-1,1,7000","2,1,7000","0,0,7000","0,-1,7000","1,1,-1","1,1,10001"})
    void invalidTotalsHaveNoSyntheticScore(long earned,long possible,int threshold) {
        assertThatThrownBy(()->AutomaticResult.Score.calculate(earned,possible,threshold)).isInstanceOf(AutomaticResultException.class);
    }
    @Test void storedRoundedPassAndWrongRoundingAreRejected() {
        assertThatThrownBy(()->new AutomaticResult.Score(13999,20000,7000,7000,true)).isInstanceOf(AutomaticResultException.class);
        assertThatThrownBy(()->new AutomaticResult.Score(2,3,6666,7000,false)).isInstanceOf(AutomaticResultException.class);
    }
    @Test void evidenceIsImmutableAndMustMatchTheExactAggregate() {
        var item=new AutomaticResult.Item(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),0,
                new ContractVersion("response",1),new ContractVersion("scoring",1),1);
        var items=new ArrayList<>(List.of(item));
        var result=result(1,AutomaticResult.Score.calculate(1,1,7000),items);
        items.clear();
        assertThat(result.items()).containsExactly(item);
        assertThatThrownBy(()->result.items().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(()->result(1,AutomaticResult.Score.calculate(0,1,7000),List.of(item))).isInstanceOf(AutomaticResultException.class);
        assertThatThrownBy(()->result(2,AutomaticResult.Score.calculate(1,1,7000),List.of(item))).isInstanceOf(AutomaticResultException.class);
        assertThat(result.toString()).isEqualTo("AutomaticResult[version=1]");
        assertThatThrownBy(()->new AutomaticResult.Item(UUID.randomUUID(),UUID.randomUUID(),null,0,item.responseContract(),item.scoringContract(),1))
                .isInstanceOf(AutomaticResultException.class);
    }
    private AutomaticResult result(int version,AutomaticResult.Score score,List<AutomaticResult.Item> items) {
        return new AutomaticResult(UUID.randomUUID(),version,UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),
                UUID.randomUUID(),"a".repeat(64),score,Instant.parse("2026-11-07T12:00:00Z"),items);
    }
}
