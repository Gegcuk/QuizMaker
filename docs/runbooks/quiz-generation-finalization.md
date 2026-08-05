# Quiz Generation Finalization

## Invariant

A quiz-generation job is `COMPLETED` only when its generated quiz and billing
settlement have been committed in the same database transaction. A failed or
interrupted finalization must not leave newly generated content available to a
user without a settled entitlement.

AI/provider calls run outside database transactions. The only short,
pessimistically locked transactions are reservation lease renewal, finalization
claim, completion/settlement, cancellation, and recovery state updates.

## State flow

```text
PROCESSING + NOT_STARTED
  -> FINALIZING                 (short, durable claim transaction)
  -> COMPLETED + SUCCEEDED      (quiz rows and billing settlement commit together)

FINALIZING
  -> FAILED + FAILED            (assembly or settlement throws)
  -> FAILED + RELEASED          (reservation release succeeds)

PROCESSING / FINALIZING
  -> CANCELLED + CANCELLED      (cancellation wins before completion commits)
```

The recovery scheduler handles:

- `FINALIZING` claims older than `quiz.jobs.finalization.recovery-grace-seconds`.
- failed finalizations whose billing reservation is still `RESERVED`.

It marks the job failed, makes no quiz available, and retries an idempotent
reservation release. It does not retry quiz assembly because generated question
data exists only in the completed worker event and must never be reconstructed
from incomplete data.

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

## Operational signals

Watch for these logs:

- `Recovered ... stalled quiz-generation finalization(s)` indicates scheduler
  compensation occurred.
- `Reservation release is pending for failed quiz-generation finalization`
  indicates the next recovery scan must retry the idempotent release.
- `Unable to recover stalled quiz-generation finalizations` indicates a
  scheduler/database dependency failure that needs investigation.

Do not update generation or billing rows manually. For an incident, collect the
job ID, its public status, finalization state, billing state, and the relevant
application log correlation data. A retry must use a new generation request;
there is no endpoint that changes a failed job into `COMPLETED`.
