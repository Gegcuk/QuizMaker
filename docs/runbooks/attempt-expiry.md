# Authoritative attempt expiry

Issue #702 implements the dark, flat, immediate-acceptance expiry foundation. It uses A17–A21
from the decision register and the saved `attempt_timing` checkpoint. It does not activate a
public timed assignment, implement pause/section clocks, or convert editable responses.

For an attempt due at 10:30, processing at 10:35 records effective completion at 10:30 and
processing at 10:35. Accepted responses survive; unanswered items receive no credit. Expiry
ignores missing required answers without changing the captured require-all rule. It writes
one system `ATTEMPT_EXPIRED` event, terminal submission, finalization intent and durable system
receipt in the same transaction. The existing result finalizer consumes that actual submission.
A voluntarily submitted attempt retains its original outcome.

## Activation and configuration

The scheduler is absent unless `quizmaker.attempt-expiry.enabled=true`. Leave it disabled until
the relevant timed conformance/activation gate (#668, or an explicitly enabled timed #609 variant)
passes. This implementation does not change production configuration.

When enabling an independently verified installation, supply both technical settings explicitly:

- `quizmaker.attempt-expiry.batch-size`: integer from 1 to 100; maximum examined candidates per poll.
- `quizmaker.attempt-expiry.poll-delay`: positive delay supported by Spring scheduling, for example
  an ISO-8601 duration. Select it using measured batch duration/database capacity; there is no new
  default delay or participant completion SLA.

Each poll advances a keyset cursor, including past failed/busy candidates. At the end of a sweep
it restarts from the beginning. A process restart also starts a new sweep. The cursor is not a
claim or authority and does not change deadlines, allowances, release or retry policy.

## Claims, restart and reconciliation

`attempt_expiry_work` is derived pending work, indexed by `(effective_deadline,attempt_id)`.
Timing capture enqueues it atomically. V105 seeds it only from actual active timing checkpoints;
there is no legacy deadline inference. Successful voluntary submission or expiry removes it
in the terminal transaction. Terminal timing/evidence remains retained.

Claims are lifecycle-root locks, following the participant-binding-before-root order used by
participant commands. A busy root is skipped with `BUSY`. Connection loss/rollback releases the
claim automatically: no durable lease is left stale. Work remains queued until its terminal
transaction commits. An independent failure does not undo other committed attempts in a batch.

The internal `AttemptExpiryService.expire` is the single system command boundary for a known
attempt. `runBatch` performs bounded scans and the scheduler calls it. Future request-time and
operations reconciliation callers must reuse this boundary rather than create another terminal
writer. These internal methods are not HTTP or operator authorization endpoints.

After an uncertain commit, invoke the same command through the trusted recovery path. A committed
system receipt returns the original acknowledgement; an uncommitted attempt remains pending.
Never manufacture a participant command, change the saved require-all flag, delete immutable
answers, or create a second result-processing trigger to recover a timeout.

## Signals and operator response

Operations owns these signals:

- `attempt.expiry.command`: timer tagged only by version and bounded outcome (`expired`,
  `replayed`, `already_terminal`, `not_due`, `busy`, `invalid`, `unsupported`, `corrupt`, `unavailable`).
- `attempt.expiry.lag`: observed delay from saved deadline to expiry processing; it is diagnostic,
  not proof of a commit or a new timing allowance. Use the command outcome for commit success.
- Redacted warnings identify a failed scan or a deferred candidate by bounded reason, without
  participant/resource IDs, answer content, credentials or database diagnostics.

Investigate any `corrupt`/`unsupported` outcome before enabling the affected capability. On an
`unavailable` or scan warning, inspect database connectivity and transaction health; preserve
pending work and retained evidence. Compare lag with the installation's measured polling and
batch capacity. No numerical alert SLA or automatic deadline extension is introduced here.
A permanently failing early candidate cannot prevent the cursor from visiting later candidates.

Database failures are sanitized inside the dependency repositories before their transactional
proxies can log them, including at TRACE. Spring failure categories and bounded deadlock/lock-timeout
evidence survive; SQL text, driver messages, original causes and suppressed diagnostics do not.
Transaction annotations remain in place: catching a participating repository failure does not
permit a caller to commit a rollback-only transaction.

Disable the scheduler to pause dispatch. Do not roll back schema after terminal writes: retain
V105 tables, receipts and the completion cause and use a forward fix. Destructive removal would
break retained evidence and replay. No worker failure refunds an attempt or reopens a result.

## Verification and integration boundaries

Run with JDK 17 and the repository's test MySQL configuration:

```bash
./mvnw test -Dtest=SafeDataAccessTest,AttemptExpiryContractTest,AttemptExpirySchedulerTest,AttemptSubmissionMySqlIntegrationTest,AutomaticResultMySqlIntegrationTest
./mvnw verify
```

Tests use real publication, timing/start, acceptance, terminal persistence, system receipts and
result finalization. They cover deadline equality/delayed processing, require-all, concurrent
workers, rollback after reached writes, restart after commit-response loss, partial batches,
query plans and measured multi-parent query bounds. External image storage is fail-on-use for
text fixtures; no remote provider is called.

Prepared editable plans or failed extension collaborators prove contract/orchestration only.
#834 must prove actual editable conversion/proposal handling; #660/#711 own section due events
and section-only versus terminal outcomes. The overall-only queue cannot treat a future section
clock as an attempt terminal deadline. #668 owns the full timed start/answer/submit-expiry race.
None of these integrations is claimed from a prepared fixture.
