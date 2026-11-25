package uk.gegc.quizmaker.features.attempt.application;

import uk.gegc.quizmaker.features.attempt.domain.model.form.ProgressiveFormAppend.Receipt;
import uk.gegc.quizmaker.features.attempt.domain.model.form.ProgressiveFormAppend.Request;

/**
 * Form-owned contract for future #571/#574 storage integrations. No implementation, Spring bean,
 * route or progressive activation is supplied by #661. Strategy choice remains outside this port.
 *
 * <p>The trusted server use case establishes current participant/resource/scope authority before
 * invocation, including exact replay; request IDs are never grants. The adapter revalidates exact
 * immutable revision membership and current form binding through authoritative metadata. Missing,
 * ambiguous or legacy facts deny; no mutable-authoring fallback or content rehash is permitted.
 *
 * <p>Adapters require the caller's existing writable READ_COMMITTED transaction under #905. They
 * neither suspend it nor independently commit. The real strategy decision, evidence, form append,
 * version guard and receipt must commit or roll back together. A failure caught by a participating
 * caller must not allow partial work to commit. Persistence errors must be sanitized before the
 * adapter's transaction interceptor, retaining bounded failure categories only.
 *
 * <p>Serialize by form, enforce expected version and unique (formId, decisionId), and reload current
 * metadata after a competing writer. Only one fresh append can win a version; independent forms
 * must not share a global lock. Resolve the exact durable receipt before rejecting a stale version:
 * identical replay returns the original receipt even after later appends, and changed reuse of a
 * decision ID conflicts, including a changed decision kind. Persist an immutable contiguous suffix;
 * never rewrite the prefix, create duplicate occurrences or reinterpret a fixed form as progressive.
 *
 * <p>A returned receipt is provisional until the enclosing transaction commits. Neither a pure
 * plan, an in-memory receipt nor a boolean supplied by a caller certifies commit. Delivery/resume
 * must read committed, authorized form and decision/evidence through their real reader; rollback
 * exposes no new item. Lost commit acknowledgement is reconciled using the same complete request,
 * not a new decision or an assumed rollback. Offline append/delivery is not enabled.
 *
 * <p>Use only bounded contract/decision-kind/outcome diagnostics (append/replay/conflict/reject).
 * Never log request IDs, membership, evidence content, answers or private strategy candidates.
 * Real rollback, restart, CAS concurrency, independent-form progress and commit-before-delivery
 * conformance are mandatory in each storage integration; pure #661 tests do not prove them.
 */
public interface ProgressiveFormAppendPort {
    Receipt append(Request request);
}
