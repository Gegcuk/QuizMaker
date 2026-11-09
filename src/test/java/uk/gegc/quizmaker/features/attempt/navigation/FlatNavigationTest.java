package uk.gegc.quizmaker.features.attempt.navigation;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import uk.gegc.quizmaker.features.attempt.domain.model.navigation.*;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy.*;
import static org.assertj.core.api.Assertions.*;
import static uk.gegc.quizmaker.features.attempt.domain.model.navigation.NavigationException.Reason.*;

class FlatNavigationTest {
    private final UUID form = UUID.randomUUID();
    private final List<UUID> items = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    private FlatNavigation.Context context(boolean skip, boolean revisit, int current, Set<UUID> accepted) {
        return new FlatNavigation.Context(new NavigationPolicy(NavigationMode.SEQUENTIAL, new NavigationRules(1, skip, revisit)),
                form, items, accepted, new FlatNavigation.Cursor(form, items.get(current), 3));
    }
    @Test void defaultRequiresAnAcceptedAnswerBeforeNextAndHasASuccessfulControl() {
        rejected(() -> context(false, true, 0, Set.of()).check(items.get(1), 3L), ANSWER_REQUIRED);
        context(false, true, 0, Set.of(items.get(0))).check(items.get(1), 3L);
        rejected(() -> context(false, true, 0, Set.of(items.get(0))).check(items.get(2), 3L), TRANSITION_DENIED);
    }
    @Test void explicitSkipAllowsNextAndReturnWithoutInventingAnAnswer() {
        var before = context(true, true, 0, Set.of()); before.check(items.get(1), 3L);
        var after = context(true, true, 1, Set.of(items.get(1))); after.check(items.get(0), 3L);
        assertThat(after.accepted()).containsExactly(items.get(1));
        assertThat(after.answerable(items.get(1))).isFalse();
    }
    @Test void forwardContinuesPastHiddenAcceptedItemsAfterAnsweringAReturnedItem() {
        var c = context(true, false, 0, Set.of(items.get(0), items.get(1)));
        c.check(items.get(2), 3L);
        rejected(() -> c.check(items.get(1), 3L), NOT_FOUND);
        assertThat(c.accepted()).containsExactlyInAnyOrder(items.get(0), items.get(1));
    }
    @Test void forwardCannotJumpOverAnAvailableItemEvenWhenEarlierAcceptedItemsAreHidden() {
        var ordered = List.of(items.get(0), items.get(1), UUID.randomUUID(), items.get(2), UUID.randomUUID());
        var c = new FlatNavigation.Context(new NavigationPolicy(NavigationMode.SEQUENTIAL, new NavigationRules(1, true, false)),
                form, ordered, Set.copyOf(ordered.subList(0, 3)), new FlatNavigation.Cursor(form, ordered.get(0), 3));
        c.check(ordered.get(3), 3L);
        rejected(() -> c.check(ordered.get(4), 3L), TRANSITION_DENIED);
        rejected(() -> c.check(ordered.get(1), 3L), NOT_FOUND);
        rejected(() -> c.check(ordered.get(2), 3L), NOT_FOUND);
    }
    @Test void acceptedItemsRemainSequentialStopsWhenRevisitIsPermitted() {
        var c = context(true, true, 0, Set.of(items.get(0), items.get(1)));
        rejected(() -> c.check(items.get(2), 3L), TRANSITION_DENIED);
        c.check(items.get(1), 3L);
    }
    @ParameterizedTest @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void readbackDoesNotGrantEditingAndNeverHidesUnansweredWork(boolean skip, boolean revisit) {
        var c = context(skip, revisit, 1, Set.of(items.get(0)));
        assertThat(c.readable(items.get(0))).isEqualTo(revisit);
        assertThat(c.answerable(items.get(0))).isFalse();
        assertThat(c.readable(items.get(1))).isTrue(); assertThat(c.answerable(items.get(1))).isTrue();
        assertThat(c.answerable(items.get(2))).isFalse();
        if (revisit) c.check(items.get(0), 3L); else rejected(() -> c.check(items.get(0), 3L), NOT_FOUND);
    }
    @Test void freeAllowsAnyDeliveredMemberWithoutCursorAuthority() {
        var c = new FlatNavigation.Context(new NavigationPolicy(NavigationMode.FREE), form, items, Set.of(), null);
        for (var item : items) { c.check(item, null); assertThat(c.answerable(item)).isTrue(); }
        assertThat(c.cursor()).isNull();
        rejected(() -> c.check(items.get(0), 1L), INVALID_INPUT);
        rejected(() -> c.check(UUID.randomUUID(), null), NOT_FOUND);
        rejected(() -> c.check(null, null), NOT_FOUND);
    }
    @Test void versionsAndCorruptMembershipFailClosed() {
        var c = context(true, true, 0, Set.of());
        rejected(() -> c.check(items.get(1), 2L), STALE);
        rejected(() -> c.check(items.get(1), null), INVALID_INPUT);
        rejected(() -> c.check(UUID.randomUUID(), 3L), NOT_FOUND);
        rejected(() -> context(true, true, 0, Set.of(UUID.randomUUID())), CORRUPT);
        rejected(() -> new FlatNavigation.Context(c.policy(), UUID.randomUUID(), items, Set.of(), c.cursor()), CORRUPT);
        rejected(() -> new FlatNavigation.Context(c.policy(), form, items, Set.of(), null), CORRUPT);
    }
    private static void rejected(Runnable work, NavigationException.Reason reason) {
        assertThatThrownBy(work::run).isInstanceOfSatisfying(NavigationException.class, e -> assertThat(e.reason()).isEqualTo(reason)).hasNoCause();
    }
    @Test void serverSelectedNextUsesTheSameAnswerAndSkipRulesWithoutDisclosingTargetsOnFailure() {
        rejected(() -> context(false, true, 0, Set.of()).next(), ANSWER_REQUIRED);
        assertThat(context(false, true, 0, Set.of(items.get(0))).next()).isEqualTo(items.get(1));
        assertThat(context(true, true, 0, Set.of()).next()).isEqualTo(items.get(1));
        assertThat(context(true, false, 0, Set.of(items.get(0), items.get(1))).next()).isEqualTo(items.get(2));
        rejected(() -> context(true, true, 2, Set.of()).next(), TRANSITION_DENIED);
    }

}
