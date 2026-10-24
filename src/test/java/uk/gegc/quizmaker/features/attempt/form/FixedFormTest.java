package uk.gegc.quizmaker.features.attempt.form;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import uk.gegc.quizmaker.features.attempt.domain.model.form.FixedForm;
import uk.gegc.quizmaker.features.attempt.domain.model.form.FixedFormException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static uk.gegc.quizmaker.features.attempt.form.FixedFormFixtures.*;
import static uk.gegc.quizmaker.features.attempt.domain.model.form.FixedFormException.Reason.*;

class FixedFormTest {
    @Test
    void retainsSuppliedStableIdentitiesAndPositionsWithAnImmutableOrderedCopy() {
        var revision = revision("identity", 2);
        var original = form("identity-form", revision, 2);
        var mutable = new ArrayList<>(List.of(original.items().get(1), original.items().get(0)));
        var restored = new FixedForm(original.id(), 1, revision.id(), mutable);
        mutable.clear();
        assertThat(restored).isEqualTo(original);
        assertThat(restored.items()).extracting(FixedForm.Item::position).containsExactly(0, 2);
        assertThatThrownBy(() -> restored.items().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void emptyFormIsStructurallyValidWithoutSelectingOrStartingAnAttempt() {
        var empty = new FixedForm(id("empty"), 1, id("revision"), List.of());
        assertThat(empty.items()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {Integer.MIN_VALUE, -1, 0, 2, Integer.MAX_VALUE})
    void unknownVersionFailsClosed(int version) {
        rejected(() -> new FixedForm(id("form"), version, id("revision"), List.of()), UNSUPPORTED_VERSION);
    }

    @Test
    void nullIdentityCollectionAndItemAreRejectedWithoutLeakingIdentifiers() {
        rejected(() -> new FixedForm(null, 1, id("revision"), List.of()), INVALID_FORM);
        rejected(() -> new FixedForm(id("form"), 1, null, List.of()), INVALID_FORM);
        rejected(() -> new FixedForm(id("form"), 1, id("revision"), null), INVALID_FORM);
        rejected(() -> new FixedForm(id("form"), 1, id("revision"), Arrays.asList((FixedForm.Item) null)), INVALID_FORM);
        rejected(() -> new FixedForm.Item(null, id("source"), 0), INVALID_FORM);
        rejected(() -> new FixedForm.Item(id("item"), null, 0), INVALID_FORM);
        rejected(() -> new FixedForm.Item(id("item"), id("source"), -1), INVALID_FORM);
    }

    @Test
    void itemIdentitySourceOccurrenceAndPositionAreIndependentNaturalConstraints() {
        var first = new FixedForm.Item(id("item"), id("source"), 0);
        for (var second : List.of(new FixedForm.Item(first.id(), id("other-source"), 1),
                new FixedForm.Item(id("other-item"), first.sourceItemId(), 1),
                new FixedForm.Item(id("other-item"), id("other-source"), 0))) {
            rejected(() -> new FixedForm(id("form"), 1, id("revision"), List.of(first, second)), INVALID_FORM);
        }
    }

    private static void rejected(Runnable action, FixedFormException.Reason reason) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(FixedFormException.class,
                failure -> assertThat(failure.reason()).isEqualTo(reason))
                .hasMessage("Fixed form rejected: " + reason).hasNoCause();
    }
}
