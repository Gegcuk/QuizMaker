# Quiz Generation Finalization

## Invariant

A quiz-generation job is `COMPLETED` only when its generated quiz and billing
settlement have been committed in the same database transaction. A failed or
interrupted finalization must not leave newly generated content available to a
user without a settled entitlement.

AI/provider calls run outside database transactions. The only short,
pessimistically locked transactions are reservation lease renewal, finalization
claim, generated-output checkpointing, completion/settlement, cancellation, and
recovery state updates.

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
stored in MySQL. Recovery never calls the AI provider. Jobs with no durable
output fail without exposing partial content, and reservation release is
idempotent. Candidate scans return IDs in bounded pages; the job is locked and
checkpoint existence is rechecked before an apparently uncheckpointed job can
be failed.

## Crash windows

| Crash point | Durable state | Recovery result |
| --- | --- | --- |
| Before checkpoint commit | No validated output | An expired paid job fails and releases its reservation; there is no provider retry. |
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
