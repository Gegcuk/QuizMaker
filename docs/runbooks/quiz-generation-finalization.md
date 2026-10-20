# Quiz Generation Finalization

## Invariant

A quiz-generation job is `COMPLETED` only when its generated quiz and billing
settlement have been committed in the same database transaction. A failed or
interrupted finalization must not leave newly generated content available to a
user without a settled entitlement.

AI/provider calls run outside database transactions. Short, pessimistically
locked transactions protect execution ownership, progress, reservation lease
renewal, finalization claim, generated-output checkpointing,
completion/settlement, cancellation, and recovery state updates.

## Durable dispatch and pre-checkpoint execution

Newly accepted jobs have one `quiz_generation_work` row, committed in the same
transaction as acceptance. An in-memory event is only a prompt dispatch hint;
losing it does not lose the work. Absence of a work row identifies a legacy job;
the migration deliberately does not adopt old active jobs.

The durable dispatcher admits jobs in acceptance-sequence FIFO order under a
short MySQL gate lock. Four active jobs and two concurrent chunks per job are the
defaults. A bounded recovery scan wakes the dispatcher; work stays in MySQL when
local workers or global slots are occupied. Scans and heartbeats have independent
scheduler capacity, and generation never runs inline on the publisher thread.

| Setting | Default | Meaning |
| --- | --- | --- |
| `quiz.jobs.execution.lease-seconds` | 120 | Persisted execution ownership interval. |
| `quiz.jobs.execution.heartbeat-seconds` | 30 | Interval between ownership renewals. |
| `quiz.jobs.execution.scan-seconds` | 10 | Interval between lost/expired dispatch scans. |

The lease must be positive and longer than the heartbeat interval. A heartbeat
never revives an already expired lease. Execution leases keep their existing
injected primary clock and `app.timezone` so persisted ownership timestamps retain
their meaning. Reservation expiry and job/finalization completion timestamps use
the injected `systemClock`, matching the local timestamps stored by billing and
checkpoint/finalization recovery; a different `app.timezone` cannot reinterpret a
paid reservation's lifetime or give a failed job a negative duration.
The record progresses from `READY` to `RUNNING` to `DONE`.
Each claim increments a fencing number: every progress, coverage, output, and
worker failure write checks that number under the job lock in the transaction
which performs the write. Authority is explicitly propagated into provider pool
tasks and removed when they finish; pooled threads do not inherit it.

At most two executions are allowed: the original execution and one automatic
recovery after an interrupted lease. This is a restart allowance, not another
retry for an ordinary terminal generation error. Existing per-call retries,
fallbacks, customer tariffs, cancellation rules, and settlement remain unchanged.
An interrupted second execution becomes `FAILED`; reservation release uses the
existing idempotent release operation. The terminal failure and `DONE` work state
commit before release is attempted in a separate transaction. A release error
leaves a durable `FAILED` finalization with `RESERVED` billing; the existing
finalization recovery scan retries the release without rerunning generation.
Legacy activation-timeout cleanup excludes jobs with durable work records.

An expired lease remains eligible for redispatch even if its former thread is
still running. A free local worker and global admission slot are required.
Heartbeat registration keeps the greatest fencing number when claimants return
out of order. A new provider-attempt record checks ownership inside its accounting
transaction; late usage reports for previously dispatched attempts remain valid
audit facts and do not create a new customer charge.
The worker suspends any caller transaction for its entire execution.
Each fenced write opens its own short transaction. The after-commit event only
requests asynchronous dispatch and never executes generation itself.

No provider call is automatically repeated once durable validated output exists.
Checkpoint ownership transfers to the existing finalization recovery path.
If an interrupted execution saved a coverage decision without its output,
the next claim removes that unpublished decision before regenerating. Task
counters restart while the externally reported progress percentage remains
monotonic and below 100 until completion.

The source is bound by document ID, owner, processed status, and a persisted
`content_revision` counter captured at acceptance. Reprocessing increments this
counter under the document lock when replacement chunks are published. Locking
metadata reads and eager snapshot loading prevent a mixed-version read. A
changed, missing, or no-longer-owned document causes an explicit failure; recovery
does not silently switch to newer content. No document text or file is hashed for
this comparison, and no additional source copy is retained.

If the reservation has expired before recovery, the job fails and the reservation
is reconciled rather than resurrected. The ordinary reservation renewal before
provider calls is unchanged. Billing-disabled jobs use the same dispatch and
fencing lifecycle without requiring a reservation.

During shutdown, persisted ownership is not immediately released to a second
worker. Existing tasks may finish during the executor's shutdown allowance;
otherwise a new instance recovers after lease expiry. A stale process may still
receive an already-dispatched provider response, but cannot commit generation
effects after ownership changes. This does not promise exactly-once provider
execution: a crash can leave the provider's cost unknown, and the single allowed
recovery can incur an additional provider cost. Provider attempt accounting
continues to record actual observed usage independently of customer settlement.

Execution records contain bounded metadata, reuse the job's persisted request,
and follow its existing retention lifecycle. Migration V79 cascades their
deletion with the parent job. No new endpoint, permission, public status value,
request field, response field, or client rollout is required.

## Owner-visible FIFO admission position

`GET /api/v1/quizzes/generation-status/{jobId}` and the existing paginated
`GET /api/v1/quizzes/generation-jobs` add three optional, read-only fields:

- `queuePosition`: positive, 1-based position among eligible waiting admissions.
- `jobsAhead`: `queuePosition - 1`.
- `queuePositionAsOf`: UTC observation timestamp shared by both numbers.

For example, while A, B, and C wait in acceptance order, B returns `2` and `1`.
Once A starts or is cancelled/terminal, B returns `1` and `0`. Once B is admitted,
all three fields become null. Active jobs consume execution slots but are not
counted as waiting admissions. This does not predict start or completion time.
The existing estimate, progress, billing, status, and cancellation fields retain
their meanings; no client rollout or UI change is required.

Eligible interrupted work keeps its original acceptance sequence. If A loses its
lease while B is already running and C is waiting, A counts ahead of C. B keeps
running and has no queue position. Restoring A uses the next available slot; it
never preempts B. A permanently `FAILED` job does not re-enter the queue. A waiting
position can therefore increase when earlier work becomes eligible for recovery.
Expired/exhausted recoveries, expired billing reservations, terminal jobs, and
jobs with durable output checkpoints do not count. Source validation and every
scheduling/state transition remain owned by the dispatcher; a position is a
snapshot of persisted queue eligibility, not a guarantee of successful admission.

The read ranks global work before filtering to requested IDs and the authenticated
owner. It returns no other owner's identifiers, names, or content. Position and
count use one scalar SQL snapshot, with the existing V80 `(state,
admission_sequence)` index; paginated status enrichment does not issue a query
per job or traverse user/document relationships. Repeated polling writes no data.
Lease timestamps use the application clock zone; billing timestamps use
`systemClock`, and absolute deadlines retain their instant semantics.

All three fields are null for legacy/missing or inconsistent queue metadata,
processing/terminal jobs, or a failed optional queue read. The optional read runs
in its own read-only transaction; a database timeout or transaction failure there
cannot roll back an otherwise successful status read. Failure of the primary
status/ownership read retains its existing error behavior. No stale cached or
fabricated position is substituted. An old client can ignore the new fields; a
new client must handle null and must not promise monotonic movement.

Observe `quiz.generation.queue.position.duration` and
`quiz.generation.queue.position.reads{outcome=available|unavailable|inconsistent|query_failed}`.
The existing `quiz.generation.execution.waiting` gauge remains the operational
queue-depth signal. `unavailable` includes normal non-waiting/legacy states;
position movement alone is not an alert. Operators investigating persistent
`inconsistent` or `query_failed` outcomes should inspect database health and the
existing durable-work recovery signals. Metrics contain no identity labels and
metrics failures cannot change responses. This change introduces no new alert
threshold, retry, scheduler, billing, retention, or recovery policy.

No migration is required: the feature reads the V79/V80 schema. Rolling back the
application removes the additive fields without changing the queue or its data.

## Execution recovery signals and operations

`quiz.generation.execution.operations` is tagged only by the bounded `outcome`:
`claimed`, `recovered`, `heartbeat`, `fenced`, or `failed`.
`quiz.generation.execution.outstanding` reports unfinished durable work;
`quiz.generation.execution.oldest.seconds` reports its oldest acceptance age.
Backlog measurements refresh on scans using scalar queries, with no per-job
relationship traversal. Metric recording failures do not change settlement or
dispatch outcomes. Existing finalization metrics still describe the later stage.

When outstanding age grows, first check database connectivity, executor
saturation, heartbeat errors, and the existing reservation/finalization signals.
For one affected job, inspect its work state, schema version, execution count,
fencing number, lease deadline, source revision, checkpoint presence, and billing
state. Do not infer that an unreported provider call cost zero. Do not reset the
execution counter, extend reservations, or manually replay paid calls to repair
the symptom. An unchanged `READY` row remains recoverable; an expired `RUNNING`
row is reclaimed within the configured scan interval when service resumes.

Use a forward fix for rollout problems. Keep V79 and its bounded metadata in
place; do not remove the work table or reset revisions while jobs reference it.
All document-content writers must run revision-aware code before new managed
jobs are accepted. The current candidate/replacement deployment should stop and
drain the previous public instance before routing requests to the replacement;
do not mix an older document writer with newly managed work. An older binary
cannot maintain the counter. Before any rollback to it, cancel/reconcile managed
jobs through existing authorized operations; do not later resume them on an
unverifiable source version. Rolling back also stops dispatch recovery. Legacy
jobs retain their previous cleanup behavior.

## State flow

```text
PROCESSING + NOT_STARTED
  -> PROCESSING + NOT_STARTED   (private generated-output checkpoint commits)
  -> FINALIZING                 (short, durable claim transaction)
  -> COMPLETED + SUCCEEDED      (quiz rows and billing settlement commit together)

FINALIZING
  -> FAILED + FAILED            (assembly or settlement throws)
  -> FAILED + RELEASED          (reservation release succeeds)

PROCESSING / FINALIZING
  -> CANCELLED + CANCELLED      (cancellation wins before completion commits)
```

The recovery scheduler handles:

- checkpointed `PROCESSING + NOT_STARTED` jobs older than
  `quiz.jobs.finalization.recovery-grace-seconds`;
- checkpointed `FINALIZING` claims older than the same grace period;
- stale `FINALIZING` jobs with no checkpoint;
- paid `PROCESSING + NOT_STARTED` jobs with no checkpoint and an expired
  reservation;
- failed finalizations whose billing reservation is still `RESERVED`.

Checkpointed jobs retry quiz assembly from the exact validated output already
stored in MySQL. Finalization recovery never calls the AI provider. Jobs with no
durable output and an expired reservation fail without exposing partial content;
new managed jobs can first recover through the execution path above. Reservation
release is idempotent. Candidate scans return IDs in bounded pages. Under the job
lock, recovery rechecks checkpoint existence and the exact current eligibility:
processing status, finalization stage, and either the expired reservation or the
stale finalization claim. A reservation renewed after the scan, or a refreshed
finalization claim, invalidates that candidate; recovery skips it without changing
the job or releasing its balance.

## Crash windows

| Crash point | Durable state | Recovery result |
| --- | --- | --- |
| Before dispatch hint is delivered | Committed managed work and accepted request | A scan claims and executes the work. Legacy jobs keep their existing cleanup path. |
| Before checkpoint commit, first worker interrupted | Managed work with an expired lease, no validated output | One fenced recovery may regenerate if source, reservation and the persisted execution deadline remain valid. |
| Before checkpoint commit, second worker interrupted | Two executions used, no validated output | The job fails visibly and its reservation is reconciled. |
| Before checkpoint commit, source changed or reservation expired | No reusable validated output | The job fails without regenerating or resurrecting its reservation. |
| After checkpoint commit, before event dispatch | Private checkpoint, `NOT_STARTED` | Scheduler claims and finalizes from the checkpoint. |
| After event dispatch, before finalization claim | Private checkpoint, `NOT_STARTED` | Event handling or scheduler claims exactly once. |
| After claim, before completion commit | Private checkpoint, stale `FINALIZING` | Scheduler reclaims the stale claim and retries from the same checkpoint. |
| During quiz assembly or billing settlement | Enclosing transaction rolls back | Checkpoint remains; failure handling marks the job failed and releases the reservation. |
| After completion commit | Quiz, settlement, and terminal state are durable | Checkpoint was deleted in that same transaction; duplicate delivery is a terminal skip. |

## Checkpoint contents and retention

`quiz_generation_output_checkpoints` is a private, one-to-one child of the
generation job. It contains a versioned JSON snapshot of generated-question
scalar fields and chunk membership only. It does not contain source document
text, JPA relationships, credentials, provider payloads, or a document-content
hash. Payloads are validated and limited by
`quiz.jobs.finalization.checkpoint-max-bytes`.

The checkpoint is deleted in the transaction that completes, terminally fails,
or cancels the job. The foreign key also uses `ON DELETE CASCADE` for job
cleanup. A corrupt, incomplete, unsupported, or oversized checkpoint cannot
create a quiz.

## Reservation lease

Before each provider call, the generation worker renews the active reservation
lease using the reservation's user ID and job ID. The reservation row is locked,
so cancellation, settlement, and the expiration sweeper cannot change the same
reservation at the same time. A renewal failure does not hold a long-running
transaction or contact a provider again; finalization later fails safely if the
reservation cannot be settled.

## Compatibility

No public endpoint, status enum value, or response field is removed. Existing
clients continue to poll the generation-job endpoint and fetch the generated
quiz after `COMPLETED`. The strengthened contract is:

- `PENDING` and `PROCESSING` never entitle the client to generated content.
- `COMPLETED` means the generated quiz and settlement are durable.
- `FAILED` and `CANCELLED` do not expose a generated quiz.

Migration `V65__add_quiz_generation_finalization_state.sql` is additive.
Historical completed jobs retain their current visibility. Jobs that cannot be
proven to have a historical reservation are marked `LEGACY`; other historical
completed jobs are marked `REVIEW_REQUIRED` for audit only. The migration does
not charge, hide, delete, or alter public status for existing jobs.

Migration `V72__create_quiz_generation_output_checkpoints.sql` is also additive.
Historical terminal jobs are unchanged. An older active job has no recoverable
generated output; if its paid reservation expires, recovery fails it visibly
and releases the reservation rather than guessing or calling the provider
again. The existing direct in-process finalization method remains available for
legacy/internal callers, while the production completion event uses the
checkpoint. No public request, response, status enum, authorization rule, or
OpenAPI schema changes.

## Operational signals

Watch for these logs:

- `Checkpointed ... generated questions for job ...` confirms durable handoff.
- `Scanning bounded quiz-generation recovery batch ...` reports candidate
  classes without loading or logging generated content.
- `Reconciled ... stalled quiz-generation finalization candidate(s)` indicates
  scheduler compensation occurred.
- `Checkpointed quiz-generation recovery failed for job ...` identifies a
  poisoned or transiently failing checkpoint while the batch continues.
- `Reservation release is pending for failed quiz-generation finalization`
  indicates the next recovery scan must retry the idempotent release.
- `Unable to recover stalled quiz-generation finalizations` indicates a
  scheduler/database dependency failure that needs investigation.

Micrometer exposes `quiz.generation.checkpoint.operations` with bounded
`outcome` values and `quiz.generation.finalization.recovery.runs` with
`attempted`, `succeeded`, or `failed`. Metrics and logs never include generated
questions, answers, document content, usernames, reservation IDs, or payload
bytes.

Do not update generation or billing rows manually. For an incident, collect the
job ID, its public status, finalization state, billing state, and the relevant
application log correlation data. Do not select or log checkpoint payloads. A
failed job still requires a new generation request; there is no endpoint that
changes a failed job into `COMPLETED`.

## Execution deadline and finalization

The five-minute AI deadline begins at the first durable execution claim and is
retained across recovery. Queue wait is excluded. It stops provider work, not
checkpoint or entitlement persistence: validated coverage strictly above 80% may
continue through this finalization flow after the deadline. At or below 80%, the
normal failure/release path applies. Previously started work is grandfathered by
V80 because its true first execution time is unknown. See
[AI provider execution](ai-provider-execution.md) for FIFO limits and rollout constraints.
