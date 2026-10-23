# Quiz Generation Coverage

Quiz generation can finish with every requested question, finish with an eligible partial result, or fail the quantity threshold. The backend persists the authoritative reconciliation decision and exposes it through the existing generation-status responses so clients do not need to parse progress text or recount quiz questions.

OpenAPI contract: `GET /v3/api-docs/quizzes`. Local Swagger UI is available at [http://localhost:8080/swagger-ui/index.html](http://localhost:8080/swagger-ui/index.html), and the deployed API groups are discoverable from [https://www.quizzence.com/api/v1/api-summary](https://www.quizzence.com/api/v1/api-summary).

## Owner New markers

`GET /api/v1/quizzes?scope=me` includes the read-only boolean `isNew` on each quiz.
Only AI-generated quizzes completed by the new application receive a marker. Every
created chapter quiz and consolidated quiz has its own marker. They commit with
the quiz and generation settlement; failed or cancelled generation publishes none.
Manual creation, imports, and all pre-feature quizzes remain seen (`false`).

The marker is private creator state. Public/shared responses, individual quiz
responses, and moderator `scope=all` responses omit `isNew`. Listing, rendering,
filtering, sorting, pagination, and generation polling never acknowledge a quiz.

After pointer hover, keyboard focus within a card/row, or a phone tap, the client
explicitly sends `POST /api/v1/quizzes/{quizId}/seen` without a body. Opening a quiz
is not required for hover/focus; tapping keeps existing navigation behavior. The
operation requires authentication, `QUIZ_READ`, and strict creator ownership.
Moderator/admin permissions do not grant acknowledgement of another creator's quiz.
Success returns HTTP 200 with the named `QuizSeenAcknowledgement` schema:

```json
{"quizId":"d290f1ee-6c54-4b01-90e6-d701748f0851","isNew":false}
```

Repeated/concurrent acknowledgements are idempotent. They never restore freshness,
edit quiz content, change `updatedAt`, or reorder the list. Historical/manual/imported
quizzes can also be acknowledged safely and remain seen. Malformed IDs return RFC
7807 `400`; missing authentication returns `401`; missing `QUIZ_READ` returns `403`.
Missing, deleted, and wrong-owner quizzes return the same non-enumerating `404`.
A storage/commit failure returns a server error without confirming acknowledgement;
the previous durable state remains authoritative.

Clients reconcile the current account's caches from the confirmed response. After
failure/offline interaction, retain the last known state and retry on a later explicit
interaction; do not create a persistent background acknowledgement queue. Ignore late
responses after logout/account switching, and do not let a stale list response restore
New after confirmed acknowledgement. A later list fetch on another device sees the
committed state. Owner lists use `private, no-cache` and a validator that incorporates
the current account and returned markers. Old web/iOS clients may ignore the additive
field and operation; no client changes are required to keep existing API use working.

Migration V78 adds `quiz_owner_freshness`, with at most one boolean fact per quiz and
no interaction history, device identity, content copy, or time-based inference. No
metadata row means already seen, so existing quizzes need no backfill. The cutover
is successful finalization in the new application: an earlier accepted job completed
by the new application is new; results completed by the old application stay seen.
Old versions can continue writing quizzes without metadata during rolling replacement.
Rollback keeps the additive table for a forward fix. Soft-deleted quizzes retain their
metadata but cannot be acknowledged/read; physical quiz deletion cascades its removal.

Owner pagination selects a page of IDs before loading associations, question counts,
and freshness in batches. This keeps relationship query counts bounded as page size
grows and avoids collection-fetch pagination over the entire matching quiz collection.
The first query fetches only to-one associations so existing category/creator sorts
remain valid with MySQL's `DISTINCT` requirements. Owner-list validators cover every
returned card field, including tags and question counts, so ordinary edits invalidate
cached pages even when freshness or the parent timestamp is unchanged.

## Compatibility

`QuizGenerationStatus.coverage` is additive, read-only, and optional. Existing generation request fields, endpoint paths, HTTP statuses, lifecycle status values, authorization, ownership checks, and polling behavior are unchanged.

- Existing clients may ignore `coverage` and continue using `status` and `generatedQuizId`.
- Existing jobs and jobs that have not reached reconciliation return `coverage: null`.
- No guessed values are backfilled for historical jobs.
- Coverage may become visible while a job is still `PROCESSING` because it is persisted before checkpointing and finalization.
- Before a durable output checkpoint exists, coverage is preliminary. If an interrupted execution is claimed for recovery, the server removes its unpublished coverage before regenerating. Polling can therefore observe coverage become null and later see a different decision. Once a durable output checkpoint exists, recovery uses that output for finalization and does not regenerate it.
- Only `status == COMPLETED` means the quiz and billing settlement are durable and the quiz is available. A `COMPLETE` or `PARTIAL` coverage outcome alone never grants access.

## Response Contract

```json
{
  "jobId": "d290f1ee-6c54-4b01-90e6-d701748f0851",
  "status": "COMPLETED",
  "generatedQuizId": "f3e2d1c0-b9a8-4765-8432-10fedcba9876",
  "coverage": {
    "outcome": "PARTIAL",
    "thresholdPercent": 80,
    "requested": 10,
    "accepted": 9,
    "missing": 1,
    "discarded": 2,
    "types": [
      {
        "questionType": "MCQ_SINGLE",
        "requested": 5,
        "accepted": 5,
        "missing": 0
      },
      {
        "questionType": "FILL_GAP",
        "requested": 5,
        "accepted": 4,
        "missing": 1
      }
    ]
  }
}
```

The fields mean:

- `requested`: the full positive-count target across selected chunks and requested question types.
- `accepted`: runtime-valid questions retained in the requested type and difficulty buckets.
- `missing`: `requested - accepted`.
- `discarded`: generated candidates rejected as invalid, duplicated within the job, outside the requested bucket, or beyond the requested count. It is diagnostic and does not increase `missing`.
- `types`: one entry for every requested positive-count question type, in stable backend enum order. Zero-count and unrequested types are absent.
- `thresholdPercent`: the policy threshold used by the recorded decision. V1 succeeds only when accepted coverage is strictly greater than 80%, unless all requested questions were accepted.

Outcomes are:

- `COMPLETE`: every requested question was accepted.
- `PARTIAL`: some questions are missing, but accepted coverage is strictly greater than the threshold and finalization may continue.
- `FAILED_THRESHOLD`: accepted coverage is at or below the threshold. The job fails, no quiz is published, and the existing billing-release path applies.

## Distinct Generated Questions

Coverage counts an exact generated question only once within a generation job. The first occurrence in ascending chunk order and provider response order is retained; later copies are discarded before the quantity threshold, final quiz assembly, and valid-question billing.

Identity is deliberately conservative: it uses question type, a Unicode/case/whitespace-normalized stem, and canonical type-specific content. JSON property order and display-only shuffling do not make a copy distinct. Fill-gap drag options are excluded because the template and gap answers define what is assessed. Meaningfully different stems or assessed answers remain eligible; the backend does not use fuzzy, embedding, synonym, paraphrase, cross-language, or cross-quiz matching.

Redistribution still runs for the resulting shortfall. Only newly distinct, runtime-valid questions can close it. Existing manual questions and previously stored quizzes are not scanned or changed.

## Client Handling

Treat `coverage` as optional on every response. When it is null, retain the current UI and lifecycle behavior. When it is present, it can support an informational quantity summary such as `9 of 10 valid questions generated` and per-type shortfalls.

Optional frontend presentation is tracked separately in [QuizMaker-Frontend issue #177](https://github.com/Gegcuk/QuizMaker-Frontend/issues/177). Backend deployment does not depend on that issue because the existing frontend can ignore this additive field.

Use an unknown-value fallback for `coverage.outcome`. A client built before a future outcome is introduced must continue to rely on the stable job `status`, not fail the entire response or infer entitlement from an unfamiliar coverage value.

Offline clients do not need a separate recovery endpoint. Resume polling the existing status endpoint after reconnecting. Identical reconciliation retries within one execution retain the same fact; a conflicting retry fails closed rather than overwriting it. A new fenced recovery execution may discard an unpublished decision only when no durable output checkpoint exists, as described in [durable dispatch and pre-checkpoint execution](runbooks/quiz-generation-finalization.md#durable-dispatch-and-pre-checkpoint-execution). A `PROCESSING` coverage observation is not a final result.

## Persistence And Privacy

Coverage stores bounded counts, the threshold, question-type enums, outcome, timestamp, and job foreign key. It does not store or log source text, document content, filenames, prompts, provider responses, generated question text, answers, or user-supplied labels. Document content is not copied, reread, or hashed for this feature.

Aggregate and per-type rows are committed atomically. Per-type rows are unique by job and question type and are physically removed only when their owning generation job is physically removed. Status-page reads batch coverage for the page rather than issuing one query per job.
