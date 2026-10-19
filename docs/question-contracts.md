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

- A participant submits `{"selectedRegionId":2}`.
- An included correct-answer payload uses the existing canonical `{"regionId":2}`.
  No `correctRegionId` alias is introduced. With multiple correct regions, existing
  grading still accepts any correct region; review retains the existing first
  correct-region representation.
- Safe regions contain IDs and coordinates, never `correct`. Likewise safe MCQ,
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
