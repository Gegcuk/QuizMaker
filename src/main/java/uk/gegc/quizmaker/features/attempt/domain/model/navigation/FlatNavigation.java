package uk.gegc.quizmaker.features.attempt.domain.model.navigation;

import java.util.*;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy.*;
import static uk.gegc.quizmaker.features.attempt.domain.model.navigation.NavigationException.*;

/** Pure flat-form rules. Delivery, authorization and response writes have separate owners. */
public final class FlatNavigation {
    private FlatNavigation() { }

    public record Cursor(UUID formId, UUID currentFormItemId, long version) {
        public Cursor { require(formId != null && currentFormItemId != null && version > 0, Reason.CORRUPT); }
    }

    public static final class Context {
        private final NavigationPolicy policy;
        private final UUID formId;
        private final List<UUID> items;
        private final Set<UUID> accepted;
        private final Cursor cursor;
        private final Map<UUID, Integer> positions;
        public Context(NavigationPolicy policy, UUID formId, List<UUID> items, Set<UUID> accepted, Cursor cursor) {
            require(policy != null && formId != null && items != null && accepted != null, Reason.CORRUPT);
            items = List.copyOf(items); accepted = Set.copyOf(accepted);
            var positions = new HashMap<UUID, Integer>();
            for (int i = 0; i < items.size(); i++) require(positions.put(items.get(i), i) == null, Reason.CORRUPT);
            require(positions.keySet().containsAll(accepted), Reason.CORRUPT);
            require(policy.mode() == NavigationMode.FREE ? cursor == null
                    : cursor != null && cursor.formId().equals(formId) && positions.containsKey(cursor.currentFormItemId()), Reason.CORRUPT);
            this.policy = policy; this.formId = formId; this.items = items; this.accepted = accepted;
            this.cursor = cursor; this.positions = Map.copyOf(positions);
        }
        public NavigationPolicy policy() { return policy; }
        public UUID formId() { return formId; }
        public List<UUID> items() { return items; }
        public Set<UUID> accepted() { return accepted; }
        public Cursor cursor() { return cursor; }
        public boolean readable(UUID item) {
            return item != null && positions.containsKey(item) && (policy.acceptedRevisit() || !accepted.contains(item));
        }
        public boolean answerable(UUID item) {
            return item != null && positions.containsKey(item) && !accepted.contains(item)
                    && (cursor == null || cursor.currentFormItemId().equals(item));
        }
        /** Select the next permitted stop without revealing a future item's identity to a caller. */
        public UUID next() {
            require(cursor != null, Reason.TRANSITION_DENIED);
            int index = positions.get(cursor.currentFormItemId()) + 1;
            while (index < items.size() && !readable(items.get(index))) index++;
            require(index < items.size(), Reason.TRANSITION_DENIED);
            UUID target = items.get(index);
            check(target, cursor.version());
            return target;
        }
        public void check(UUID target, Long expectedNavigationVersion) {
            require(readable(target), Reason.NOT_FOUND);
            if (cursor == null) { require(expectedNavigationVersion == null, Reason.INVALID_INPUT); return; }
            require(expectedNavigationVersion != null && expectedNavigationVersion > 0, Reason.INVALID_INPUT);
            require(cursor.version() == expectedNavigationVersion, Reason.STALE);
            int from = positions.get(cursor.currentFormItemId()), to = positions.get(target);
            if (from == to) return;
            int next = from + 1;
            while (next < items.size() && !readable(items.get(next))) next++;
            if (to == next) {
                require(accepted.contains(cursor.currentFormItemId()) || policy.skipAndReturn(), Reason.ANSWER_REQUIRED);
                return;
            }
            require(to < from && (accepted.contains(target) ? policy.acceptedRevisit() : policy.skipAndReturn()), Reason.TRANSITION_DENIED);
        }
    }
}
