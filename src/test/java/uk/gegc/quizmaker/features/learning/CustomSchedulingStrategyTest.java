package uk.gegc.quizmaker.features.learning;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import uk.gegc.quizmaker.features.learning.application.impl.CustomSchedulingStrategyImpl;
import uk.gegc.quizmaker.features.learning.domain.model.CustomSchedule.*;
import uk.gegc.quizmaker.features.learning.domain.model.LearningPlan.*;
import uk.gegc.quizmaker.features.learning.domain.model.LearningSchedule.Rating;
import uk.gegc.quizmaker.features.learning.domain.model.LearningSessionException;
import static org.assertj.core.api.Assertions.*;

class CustomSchedulingStrategyTest {
    final CustomSchedulingStrategyImpl strategy=new CustomSchedulingStrategyImpl();
    final Instant at=Instant.parse("2026-11-23T10:00:00Z");
    final Map<Integer,Interval> history=new HashMap<>();
    Definition plan(Mode mode,String coefficient,Interval... intervals) {
        return new Definition(1,"Private",List.of(intervals),mode,new BigDecimal(coefficient),"Europe/London","09:00");
    }
    static Interval days(int n) { return new Interval(BigDecimal.valueOf(n),Unit.DAYS); }
    State state(int stage) { return new State(5,5,stage,at.minusSeconds(86400),at,"Europe/London",false); }
    State next(Definition plan,State state,Rating rating,Instant instant) {
        var transition=strategy.review(plan,state,rating,instant,history::get);
        if(transition.generated()!=null) history.put(transition.state().stage(),transition.generated());
        return transition.state();
    }
    @Test void initialAndLateReviewsAnchorOnceToTheActualReviewWithoutCatchUp() {
        var plan=plan(Mode.FINITE,"2",days(1),days(3),days(7),days(14),days(30));
        var initial=new State(0,0,0,null,at,"Europe/London",false);
        var first=next(plan,initial,Rating.GOOD,at.plusSeconds(31L*86400));
        assertThat(first.stage()).isZero(); assertThat(first.sequence()).isOne();
        assertThat(first.dueAt()).isEqualTo(Instant.parse("2026-12-25T09:00:00Z"));
        for(int stage:new int[]{0,1,3,4}) {
            var again=next(plan,state(stage),Rating.AGAIN,at);
            assertThat(again.stage()).isEqualTo(Math.max(0,stage-2));
            assertThat(again.dueAt()).isEqualTo(strategy.due(plan.intervals().get(again.stage()),at,"Europe/London","09:00"));
        }
    }
    @ParameterizedTest @EnumSource(Rating.class)
    void finalFiniteStepCompletesOnlyForSuccessfulAdvancement(Rating rating) {
        var result=next(plan(Mode.FINITE,"2",days(1),days(3),days(7)),state(2),rating,at);
        assertThat(result.completed()).isEqualTo(rating!=Rating.AGAIN);
        assertThat(result.stage()).isEqualTo(rating==Rating.AGAIN?0:2);
    }
    @Test void generatedIntervalsUseTheActualRoundedPredecessorAndSurviveRollback() {
        var plan=plan(Mode.INDEFINITE,"1.2",days(7)); var current=state(0);
        current=next(plan,current,Rating.GOOD,at); assertThat(history.get(1)).isEqualTo(days(9));
        current=next(plan,current,Rating.EASY,at); assertThat(history.get(2)).isEqualTo(days(11));
        current=next(plan,current,Rating.AGAIN,at); assertThat(current.stage()).isZero();
        current=next(plan,current,Rating.HARD,at); assertThat(current.stage()).isOne();
        assertThat(history).hasSize(2);
        history.clear(); plan=plan(Mode.INDEFINITE,"2",days(14)); current=state(0);
        for(int expected:new int[]{28,56,112}) {
            current=next(plan,current,Rating.GOOD,at); assertThat(history.get(current.stage())).isEqualTo(days(expected));
        }
    }
    @Test void constantAndCappedTailsRetainOnePlateauWithSeparateEventsAndActualLineage() {
        for(String coefficient:List.of("1","2","1E+2000000000")) {
            history.clear(); var plan=plan(Mode.INDEFINITE,coefficient,days(1),days(3),days(3650)); var current=state(2);
            for(int i=0;i<20;i++) current=next(plan,current,Rating.GOOD,at);
            assertThat(current.stage()).isEqualTo(2); assertThat(current.sequence()).isEqualTo(25); assertThat(history).isEmpty();
            assertThat(next(plan,current,Rating.AGAIN,at).stage()).isZero();
        }
        var duplicate=plan(Mode.FINITE,"2",days(1),days(1),days(1));
        assertThat(next(duplicate,state(0),Rating.GOOD,at).stage()).isOne();
    }
    @Test void generatedDurationsRoundUpToWholeMinutesAndCapAt3650Days() {
        var plan=plan(Mode.INDEFINITE,"1.2",new Interval(new BigDecimal("0.5"),Unit.HOURS));
        var result=next(plan,state(0),Rating.GOOD,at);
        assertThat(history.get(1)).isEqualTo(new Interval(new BigDecimal("36"),Unit.MINUTES));
        assertThat(result.dueAt()).isEqualTo(at.plusSeconds(36*60));
        history.clear(); plan=plan(Mode.INDEFINITE,"2",new Interval(new BigDecimal("5255999"),Unit.MINUTES));
        result=next(plan,state(0),Rating.GOOD,at);
        assertThat(history.get(1).value()).isEqualByComparingTo("5256000");
        assertThat(next(plan,result,Rating.GOOD,at).stage()).isOne();
    }
    @Test void calendarGapUsesFirstValidTimeAndOverlapUsesEarlierOccurrenceOnce() {
        assertThat(strategy.due(days(1),Instant.parse("2026-03-28T10:00:00Z"),"Europe/London","01:30"))
                .isEqualTo(Instant.parse("2026-03-29T01:00:00Z"));
        assertThat(strategy.due(days(1),Instant.parse("2026-10-24T10:00:00Z"),"Europe/London","01:30"))
                .isEqualTo(Instant.parse("2026-10-25T00:30:00Z"));
        assertThat(strategy.due(new Interval(BigDecimal.valueOf(24),Unit.HOURS),Instant.parse("2026-03-28T10:00:00Z"),null,null))
                .isEqualTo(Instant.parse("2026-03-29T10:00:00Z"));
    }
    @Test void mixedUnitAgainDoesNotClampToTheCalendarTime() {
        var hours=new Interval(BigDecimal.valueOf(20),Unit.HOURS);
        var plan=plan(Mode.FINITE,"2",hours,hours,days(1));
        var result=next(plan,state(2),Rating.AGAIN,Instant.parse("2026-11-23T22:00:00Z"));
        assertThat(result.dueAt()).isEqualTo(Instant.parse("2026-11-24T18:00:00Z"));
    }
    @Test void completedBackwardsTimeAndUnstorableDatesFailBeforeAResult() {
        var plan=plan(Mode.FINITE,"2",days(1));
        assertThatThrownBy(()->next(plan,new State(1,1,0,at,at,"Europe/London",true),Rating.GOOD,at)).isInstanceOf(LearningSessionException.class);
        assertThatThrownBy(()->next(plan,state(0),Rating.GOOD,at.minusSeconds(86401))).isInstanceOf(LearningSessionException.class);
        assertThatThrownBy(()->strategy.due(days(3650),Instant.parse("9999-12-31T00:00:00Z"),"UTC","09:00"))
                .isInstanceOf(LearningSessionException.class);
    }
}
