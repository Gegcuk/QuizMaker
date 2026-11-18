package uk.gegc.quizmaker.features.attempt.domain.model.trace;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.AttemptLifecycle.State;
import uk.gegc.quizmaker.features.attempt.domain.model.trace.ExecutionTrace.*;

/** Protected internal read contract. Request identifiers and cursors never confer authority. */
public final class TraceQuery {
    public static final int VERSION = 1;
    private TraceQuery() { }

    public enum Purpose { DISPUTE, OPERATIONS }
    public enum Operation { READ_PAGE }
    /** These are current trusted governance facts, never inferred from missing event rows. */
    public enum Availability { AVAILABLE, EXPIRED, RETAINED_AWAY, UNKNOWN }

    public record Request(int version, UUID attemptId, Purpose purpose, String cursor, int limit) {
        @Override public String toString() { return "TraceQueryRequest[redacted]"; }
    }

    /** Returned only after the context reader authorizes the current caller and exact resource. */
    public record Context(Scope scope, Purpose purpose, Operation operation, Availability availability) {
        @Override public String toString() { return "TraceQueryContext[redacted]"; }
    }

    /** Separate allow-list: no resource, event, command, actor identity, item or private payload IDs. */
    public record Entry(long sequence, Instant recordedAt, Type type, Actor actor, ReasonCode reason,
                        State from, State to) {
        @Override public String toString() { return "TraceQueryEntry[sequence=" + sequence + "]"; }
    }

    public record Page(int version, List<Entry> events, String nextCursor) {
        public Page { events = List.copyOf(events); }
        @Override public String toString() { return "TraceQueryPage[eventCount=" + events.size() + "]"; }
    }
}
