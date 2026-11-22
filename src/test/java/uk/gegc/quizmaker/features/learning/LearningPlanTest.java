package uk.gegc.quizmaker.features.learning;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import uk.gegc.quizmaker.features.learning.domain.model.LearningPlan.*;
import uk.gegc.quizmaker.features.learning.domain.model.LearningSessionException;
import uk.gegc.quizmaker.features.learning.infra.scheduling.LearningPlanFormat;
import static org.assertj.core.api.Assertions.*;

class LearningPlanTest {
    static Interval interval(String value,Unit unit) { return new Interval(new BigDecimal(value),unit); }
    static Definition definition(String name,int... days) {
        return new Definition(1,name,Arrays.stream(days).mapToObj(i -> interval(""+i,Unit.DAYS)).toList(),Mode.FINITE,null,"Europe/London","09:00");
    }
    @Test void acceptedBoundariesRetainEqualStepsAndUnits() {
        for(int count:List.of(1,100)) {
            var d=new Definition(1,"Private",Collections.nCopies(count,interval("1",Unit.MINUTES)),Mode.INDEFINITE,BigDecimal.ONE,null,null);
            assertThat(d.intervals()).hasSize(count); assertThat(d.coefficient()).isEqualByComparingTo("1");
            assertThatThrownBy(() -> d.intervals().clear()).isInstanceOf(UnsupportedOperationException.class);
        }
        var mixed=new Definition(1,"Mixed",List.of(interval("20",Unit.HOURS),interval("20",Unit.HOURS),interval("1",Unit.DAYS)),Mode.FINITE,null,"Europe/London","09:00");
        assertThat(mixed.intervals()).extracting(Interval::unit).containsExactly(Unit.HOURS,Unit.HOURS,Unit.DAYS);
        assertThat(mixed.coefficient()).isEqualByComparingTo("2");
        assertThat(interval("0.5",Unit.HOURS).value()).isEqualByComparingTo("0.5");
        assertThat(definition("Maximum",3650).intervals()).hasSize(1);
    }
    @Test void invalidSizesValuesOrderAndCalendarSettingsRejectWithoutSorting() {
        for(int count:List.of(0,101)) assertThatThrownBy(() -> new Definition(1,"Private",Collections.nCopies(count,interval("1",Unit.MINUTES)),Mode.FINITE,null,null,null)).isInstanceOf(LearningSessionException.class);
        for(String value:List.of("0","-1","0.5","5256001","1.001")) assertThatThrownBy(() -> interval(value,Unit.MINUTES)).isInstanceOf(LearningSessionException.class);
        for(String value:List.of("0.5","3651")) assertThatThrownBy(() -> interval(value,Unit.DAYS)).isInstanceOf(LearningSessionException.class);
        assertThatThrownBy(() -> definition("Decreasing",1,14,3,7)).isInstanceOf(LearningSessionException.class);
        assertThatThrownBy(() -> new Definition(1,"Private",List.of(interval("1",Unit.DAYS)),Mode.FINITE,null,null,null)).isInstanceOf(LearningSessionException.class);
        assertThatThrownBy(() -> new Definition(1,"Private",List.of(interval("1",Unit.DAYS)),Mode.FINITE,BigDecimal.ZERO,"Europe/London","09:00")).isInstanceOf(LearningSessionException.class);
        assertThatThrownBy(() -> new Definition(1,"Private",List.of(interval("1",Unit.DAYS)),Mode.FINITE,null,"bad/zone","09:00")).isInstanceOf(LearningSessionException.class);
    }
    @Test void retainedFormatIsStrictBoundedAndRoundTripsCapturedConfiguration() {
        var codec=new LearningPlanFormat(); var d=definition("Private",1,3,3,14);
        String json=codec.encode(d); assertThat(codec.decode(json,Definition.class)).isEqualTo(d);
        for(String bad:List.of(json+" {}",json+" {",json.replace("\"version\":1","\"version\":1.5"),
                json.replace("\"version\":1","\"version\":\"1\""),json.replace("\"version\":1","\"version\":1,\"version\":1"),
                json.replace("\"version\":1","\"version\":2")," ".repeat(32769)))
            assertThatThrownBy(() -> codec.decode(bad,Definition.class)).isInstanceOf(LearningSessionException.class).hasMessageNotContaining("Private");
        assertThat(d.toString()).doesNotContain("Private","London");
    }
}
