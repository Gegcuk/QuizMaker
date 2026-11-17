# Question contracts v1

The active question API publishes separate named schemas for authoring content,
participant-safe content, submitted responses, and released correct answers for
all nine question families. These are available in the `questions`, `attempts`,
and relevant `quizzes` OpenAPI documents, discoverable through
`GET /api/v1/api-summary`.

`GET /api/v1/questions/schemas/{questionType}` also returns `contracts` with
`version`, `author`, `safe`, `response`, and `correct` JSON Schema definitions.
Its existing `schema` field remains the legacy AI-generation envelope. AI prompt
restrictions, such as the generated number of options, do not redefine the manual
question contract.

## Compatibility and validation

- Existing paths, permission checks, ownership checks, inclusion flags, and
  grading algorithms are unchanged.
- Create/update question and answer-submission bodies accept optional
  `contractVersion: 1`. Omission means v1. Explicit null, another version, and a
  non-integer version return a bounded `400 application/problem+json` response.
- New and changed content and submitted responses are checked before persistence
  or scoring. Required fields and JSON types are enforced without coercing a
  string into a boolean or an integer. Incorrect answers of a valid shape still
  use the existing grading rules.
- Stored content also passes the existing authoring rules before delivery, review,
  and completion. For example an MCQ without a correct option is unavailable,
  even though its JSON fields have the expected types. Recorded grades are retained.
- Validation problems keep bounded field diagnostics without echoing rejected
  values or author-only answers. Large batches return at most 16 field errors,
  with `errorsTruncated: true` when more diagnostics exist.
- Unknown input fields are ignored by validation and have no new grading meaning.
  This does not allow solution fields in participant-safe output: that output is
  constructed from an allowlist and validated against closed schemas.
- Limits per content/response JSON value are 65,536 UTF-8 JSON bytes, 10,000 Unicode
  characters per string, and 100 entries per array, unless an existing smaller
  bound applies. Serialization escapes count toward the JSON byte limit.
  The raw stored representation and the serialized value are both bounded.
- Existing stricter authoring rules remain in the handlers: for example HOTSPOT
  has 2–6 regions, COMPLIANCE has 2–6 statements, ORDERING has 2–10 items, and an
  optional FILL_GAP choice pool has at most 10 options with its existing distractor
  rules. FILL_GAP without a choice pool remains supported. ORDERING supports both
  authored item order and the existing explicit `correctOrder` representation.
- Media asset IDs use canonical 36-character UUID text, accepting either hex case.
  Shortened forms accepted by Java's UUID parser do not satisfy the published format.

## Availability and review

Question DTOs include `contractVersion` and `contentAvailability`:

| Value | Meaning |
| --- | --- |
| `AVAILABLE` | The corresponding content matches its known v1 shape. |
| `NOT_INCLUDED` | An optional review field was not requested/included under existing rules. This says nothing about answer correctness. |
| `UNAVAILABLE` | Stored data is malformed, unsupported, or exceeds the bounds. The corresponding content is null/omitted and `contentProblem` contains a bounded repair diagnostic. |

Reviews report these states separately for `questionSafeContent`, `userResponse`,
and `correctAnswer` through `contentAvailability`, `responseAvailability`, and
`correctAnswerAvailability`. Recorded `isCorrect` and `score` are preserved even
when historical content can no longer be rendered. Omitted correctness is never
converted into `false`, zero, or an empty answer.

A damaged question does not hide healthy siblings. It is not silently skipped,
answered, or repaired. Submission against unavailable question content and
completion with unavailable questions return `409 application/problem+json`
without recording a replacement answer or completing the attempt. A malformed
answer in a batch rolls back the entire existing submission transaction.

## HOTSPOT and answer privacy

HOTSPOT content supports these optional author-entered strings:

| Field | Maximum Unicode characters |
| --- | --- |
| `imageDescription` | 2,000 |
| `regions[].label` | 120 |
| `regions[].description` | 1,000 |

Manual create/update accepts omitted, null, or blank descriptions. Authors may
change or remove descriptions from an AI-created question after it is saved.
Neither publication nor attempts require descriptions; legacy questions remain
usable. Provided fields must be strings within their bounds. Safe delivery omits
null/blank descriptions and copies other descriptive strings by region ID, never
by array position. Missing descriptions do not constitute a complete textual
alternative; clients must not invent descriptions from IDs or coordinates.

Only creation from a new AI response requires a nonblank image description and
a nonblank label/description for every region. AI labels must be distinct after
trimming and case-insensitive comparison. The generation schema, prompt, parser,
and runtime candidate check enforce this requirement before accepting generated
content. Existing retry, partial-output, coverage, and billing rules are unchanged.
This requirement is not reapplied to persisted questions or manual edits.

Descriptions should neutrally identify visible content without identifying the
correct choice. Structural redaction removes correctness and undeclared fields;
it cannot establish the truth of a description or detect every answer embedded
in free text. Image content is not fetched or independently verified here.

Attempt delivery and review continue to read current question content. This
change adds no snapshots, migrations, historical backfill, or new attempt routes.
Immutable historical presentation belongs to the revision/form workstream.

- A participant submits `{"selectedRegionId":2}`.
- An included correct-answer payload uses the existing canonical `{"regionId":2}`.
  No `correctRegionId` alias is introduced. With multiple correct regions, existing
  grading still accepts any correct region; review retains the existing first
  correct-region representation.
- Safe regions contain IDs, coordinates, and optional descriptions, never `correct`. Likewise safe MCQ,
  FILL_GAP, MATCHING, COMPLIANCE, and ORDERING content excludes solution-bearing
  fields such as `correct`, gap `answer`, `matchId`, `compliant`, and `correctOrder`.
- This change does not introduce or activate new release/authorization rules.
  Legacy disclosure-policy hardening remains separate work under #690.

Web and iOS clients should select schemas using the question type, accept additive
availability metadata, and handle `UNAVAILABLE` without inventing answer state or
discarding the surrounding result. Backend golden examples live in
`src/main/resources/question-contracts/v1-examples.json`; controller, serialization,
and grading-compatibility tests use the same examples. No client build or deployment
is required to verify the backend contract.

## Immutable baseline family contracts (#701)

The frozen #701 catalogue defines five production adapters. OPEN is an additive
family-owned contract described below. The active question and
attempt APIs above retain their existing routes, payloads, permissions and legacy
handlers; registering these adapters does not activate target execution.

| Family | Exact response contract ID | Exact scoring contract ID | Version |
| --- | --- | --- | --- |
| MCQ_SINGLE | `question.mcq-single.response` | `question.mcq-single.binary` | 1 |
| MCQ_MULTI | `question.mcq-multi.response` | `question.mcq-multi.binary` | 1 |
| TRUE_FALSE | `question.true-false.response` | `question.true-false.binary` | 1 |
| FILL_GAP | `question.fill-gap.response` | `question.fill-gap.binary` | 1 |
| ORDERING | `question.ordering.response` | `question.ordering.binary` | 1 |

Each score is an exact decimal pair: earned `0` or `1`, possible `1` (A22/D38).
Malformed responses and unavailable content raise bounded typed errors; they do
not produce a zero grade. Valid but incorrect/unknown selections keep the legacy
zero-score behavior. MCQ_MULTI compares sets, including its existing duplicate
selection behavior. ORDERING uses captured `correctOrder` when present, otherwise
the captured authored item IDs. FILL_GAP accepts content with and without a choice
pool, grades gap answers rather than distractors, and preserves its response
fields (`answers[].gapId`, `answers[].answer`). Missing required gap answers score
zero; duplicate gap IDs are malformed; extra well-formed gap answers retain the
legacy accepted classification.

FILL_GAP v1 trims and lowercases with `Locale.ROOT`, as explicitly
[approved on 5 November 2026](architecture/decision-register.md#fill-gap-v1-locale).
Pool validation uses the same normalization. Thus `I` and `i` compare equally on
English and Turkish hosts. The legacy path keeps its default-locale behavior.
This is the documented exception to golden equivalence, not a change to existing
attempt grading.

`FamilyShapeV1` and `baseline-v1-shapes.json` freeze the five author/response
schemas and validator semantics from base `16abc7e`. They do not dispatch to the
evolving active API schema builder or mutable handlers. The byte/string/array
bounds and ignored unknown input fields remain as above. Schema, validation,
normalization or grading changes require a new exact contract version; retain v1
implementations and fixtures while stored bindings refer to v1. New families
implement both ports, register unique IDs and add their own conformance fixtures.
There is no latest-version, family-name or legacy-handler fallback.

`PublishedQuestionContractService.bind(quizId, revisionId)` is an internal author
application boundary. It reads an actual publication through the existing
publication service, rechecks authentication, `QUIZ_UPDATE`/existing elevated
access and quiz ownership, validates the complete immutable family set, then
binds all question revisions in one transaction. Owner identity comes from the
authorized publication, not a caller-supplied owner. It uses the existing #598
binding table, uniqueness guards and canonical revision locks; no schema change,
backfill or legacy-result rewrite is introduced. Later draft edits or a new
publication cannot replace the old bindings or the captured content. For example,
changing the correct MCQ option from A to B affects the new revision while A still
earns one point when evaluated against the old revision after a component restart.

Identical concurrent calls and retries return the same durable binding set.
Conflicting exact bindings fail with `BINDING_CONFLICT`. Unknown families or
versions fail with `UNSUPPORTED_CONTRACT`; unbound revisions remain unavailable.
An interrupted write rolls back the complete new set. Persistence failures return
`PERSISTENCE_UNAVAILABLE`; after a lost acknowledgement or uncertain commit,
reconnect and read/retry the same revision identity to reconcile durable state.
Offline writes are not enabled. Authentication/ownership failures reuse the
publication boundary's non-enumerating errors, including on replay. No new public
HTTP status mapping is introduced by this internal service.

The adapters provide closed, text-only participant content and option layouts for
the existing flat form materializer. Captured option UUIDs survive display
reordering; the v1 source schema describes independent options, with no authored
pin/group constraints. Media-bearing content can be validated/scored, but text-only
projection rejects media instead of dropping it. Media presentation remains a
separate integration. Legacy-shaped scoring fields refer to **captured source
keys**, never display positions. A future canonical response boundary must resolve
form option UUIDs against the captured revision before invoking this payload
contract; direct UUID-as-legacy-key submission is not an activated API.

Automated evidence is in `FamilyContractV1Test` (fixed golden fixtures, limits,
locale and safe projection) and `PublishedFamilyContractsMySqlIntegrationTest`
(real publication, binding, forms, MySQL rollback/concurrency, authorization,
restart and measured bulk snapshot queries). Existing OpenAPI/compatibility tests
continue to cover current web/iOS payloads and questions/attempts/API-summary
contracts. Only external storage is replaced at the integration boundary. The
pure adapters perform no database or provider calls; bulk snapshot loading is
measured with multiple revisions, questions and options. Per-identity write locks
and inserts are intentional persistence operations, not lazy relationship fetches.

The bounded `question.contract.publication.binding` timer reports version `1` and
outcome `bound`, `rejected` or `persistence_unavailable`, alongside existing
`question.contract.resolutions` and publication signals. It contains no user,
question, answer or raw provider/database details. Alerting/activation ownership
remains with the later operational rollout; this increment adds no alert policy.

The verified internal composition creates neither an attempt nor an accepted
answer. Real start/acceptance/charging, disclosure and formal D38 flows still need
their owning integrations (#600, #543, #609, #668). OPEN, MATCHING, HOTSPOT and
COMPLIANCE remain explicitly unsupported here, owned by #826–#829. Full cutover
still requires all nine families and their immutable compatibility evidence.


## Immutable OPEN contract (#826)

`SupportedQuestionContracts` selects exact delivered pairs while leaving the five-family
`BaselineQuestionContracts` and its frozen shapes unchanged. OPEN registers
`question.open.response/1` and `question.open.binary/1` through `OpenQuestionContractConfig`.
Unknown versions/families fail closed; there is no mutable-handler or latest-version fallback.
Bindings, response rows, grades and receipts use existing storage without a migration or backfill.

OPEN v1 retains the active author/response shape and bounds: an author supplies a nonblank
string `answer`, and a participant supplies a string `answer`, which may be empty or blank.
Missing, null, boolean, numeric and non-object public responses are invalid. Strings have at
most 10,000 Unicode code points, arrays in ignored extensions at most 100 items, and JSON at
most 65,536 UTF-8 bytes. An ignored extension does not bypass those bounds. Exact comparison
trims outer characters using Java `trim()` and lowercases with `Locale.ROOT`, as
[approved for OPEN on 17 November 2026](architecture/decision-register.md#open-v1-locale).
Internal whitespace, punctuation and Unicode normalization are not rewritten. A valid wrong,
partial, empty or blank answer receives zero of one point. No partial credit is added.

The legacy handler remains unchanged, including its default-locale behavior. The new ROOT
rule is the explicit compatibility exception: `I` versus `i` scores one on both English and
Turkish servers. Original submitted text is retained; changing spaces/case under the same
idempotency key is still a conflict even when both texts would earn the same grade.

The existing, independently gated v2 command accepts an additive `ConfirmedOpenAnswer`:

```json
{"formItemId":"00000000-0000-0000-0000-000000000002","expectedStateVersion":1,"response":{"type":"OPEN","answer":" Paris "}}
```

It uses `POST /api/v2/attempts/{attemptId}/responses` and the same `Idempotency-Key` semantics,
with the existing batch command accepting the same variant. Acknowledgements contain no
submitted text, key, score or explanation. The safe form content is only `{"kind":"OPEN"}`;
there are no options or answer-key fields. Independently authorized released submitted/key
categories use `ReleasedPracticeText`, for example `{"kind":"TEXT","answer":"Paris"}`.
Form, participant-view and response unions are additive in the `attempts-v2` OpenAPI group;
existing envelopes, paths, v1 schemas, security and API-summary discovery remain unchanged.
Malformed transport input is 400; semantic bounds/type mismatches through a real command are
422; stale/conflicting commands are 409; unavailable ownership is non-enumerating 404.

This implements internal OPEN publication, materialization, acceptance, grading and release
composition. It does not enable the feature flags, activate #609, replace legacy routing or
claim complete nine-family cutover. Existing web/iOS clients continue on their current paths;
clients using the separately enabled v2 surface must understand the OPEN/TEXT variants before
that capability is exposed to them. External/embedded media is not silently discarded: the
ordinary text-only materializer rejects it, and the existing separately retained top-level
image path remains separately authorized.

Evidence: `OpenContractV1Test` compares the real public legacy handler and the new adapter,
including limits and the ROOT exception. `PublishedFamilyContractsMySqlIntegrationTest`
checks stored version resolution (including a deliberately different test-only v2), binding-set
rollback with an unchanged publication pointer, binding races/restart and storage isolation. `ImmediateResultReleaseMySqlIntegrationTest`
composes real start/accept/submit/grade/release/read, mixed atomic batches, reached-insert
rollback/retry, caller RC rollback, current access, historical edits and multi-parent measured
reads. The v2 OpenAPI suites check named OPEN/TEXT schemas, examples, strict parsing and the
existing grouping/discovery contracts.

## Revision-bound MATCHING execution (#827)

The internal v2 catalogue additionally registers `question.matching.response:1` and
`question.matching.binary:1`. This does not enable target routes or replace legacy training.
The five-family #701 catalogue remains frozen; OPEN and MATCHING have separate registrations.

Published MATCHING questions retain distinct `left/<integer>` and `right/<integer>` option
identities, including when the same integer occurs in both columns. V2 forms expose their
stable UUIDs in typed `MATCHING` left/right lists. The left column keeps authored order;
the existing shuffled ordering mode shuffles only the right column and saves that order.
Responses and drafts use `{"type":"MATCHING","matches":[{"leftId":"<left-option-uuid>",
"rightId":"<right-option-uuid>"}]}`. Both references must belong to the appropriate side
of this saved question. Legacy v1 continues to use its existing integer response IDs.

The frozen scorer preserves current binary behavior: every authored left pair must match
to earn one point; otherwise the score is zero. Empty and partial arrays remain valid wrong
answers. Repeated left references preserve request order, and the last pair wins. Many-to-one
authoring remains supported. There is no partial credit, inferred bijection, new normalization,
or automatic deduplication. At most 100 submitted pairs and the existing JSON/string limits apply.

Media-only items remain valid. Presentation requires publication-resolved image metadata from
the retained-image pipeline; an author-supplied asset ID alone cannot materialize an image.
The typed inline image contains only its captured URL, dimensions and MIME type, without separate asset,
binding or storage-version identifier fields. Execution does not fetch remote URLs, inspect storage,
or substitute the current author-library image. Removing an image from the library preserves
its existing published binding. This internal path is not production availability, exceptional
restriction or full-cutover evidence; the existing capability-specific activation gates remain.

Accepted pairs are immutable, and grading resolves the exact captured contract versions.
Released `MATCHING_PAIRS` answers use the same saved option UUIDs and retain submitted pair
order. Submitted answers, correct pairs and explanations remain independently disclosure-filtered.
Unknown versions, foreign/wrong-side UUIDs, and malformed input cannot leave a partial answer.
Single commands, drafts and atomic batches retain their existing authorization, idempotency,
READ_COMMITTED composition and rollback contracts. OpenAPI adds typed alternatives within the
existing `attempts-v2` group; existing endpoints, schemas and defaults remain compatible.
