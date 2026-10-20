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
