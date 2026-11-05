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

The internal revision path has five production adapters. The active question and
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
