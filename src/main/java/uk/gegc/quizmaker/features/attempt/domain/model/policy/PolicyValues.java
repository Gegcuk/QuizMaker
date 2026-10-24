package uk.gegc.quizmaker.features.attempt.domain.model.policy;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.function.Function;

import static uk.gegc.quizmaker.features.attempt.domain.model.policy.PolicyValidationException.Reason.*;

final class PolicyValues {
    private PolicyValues() {}

    static void present(Object... values) {
        for (Object value : values) require(value != null, INVALID_VALUE);
    }

    static void require(boolean valid, PolicyValidationException.Reason reason) {
        if (!valid) throw new PolicyValidationException(reason);
    }

    static void strategyVersion(int version) {
        require(version == 1, UNSUPPORTED_STRATEGY_VERSION);
    }

    /** Cardinality comes from the closed vocabulary, not a new product quota. */
    static <T, E extends Enum<E>> List<T> orderedValues(List<T> values, Class<E> type,
                                                      Function<T, E> key, boolean complete) {
        present(values);
        int bound = type.getEnumConstants().length;
        require(values.size() <= bound && (!complete || values.size() == bound), INVALID_VALUE);
        var keys = new HashSet<E>();
        for (T value : values) {
            present(value);
            E identity = key.apply(value);
            require(identity != null && keys.add(identity), INVALID_VALUE);
        }
        return values.stream().sorted(Comparator.comparingInt(value -> key.apply(value).ordinal())).toList();
    }
}
