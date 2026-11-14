# Policy-Driven Quiz Execution Architecture

## Learning clean-start refinement

The owner confirms that no current quizzes/questions use repetitions. Plan new Learning without SM-2 import or mandatory legacy repetition adapters; ordinary quiz/API/training compatibility remains separate. The [approved clean-start contract](decision-register.md#learning-clean-start) supersedes earlier preservation/migration assumptions for the unused repetition subsystem. Verify the target-scope assumption before any later destructive cleanup; do not invent migration work or reset unexpected records.

## Status, Purpose, and Authority

This document is the architectural reference for evolving QuizMaker from its current practice and assessment implementation into a general-purpose learning and assessment platform.

It defines:

- the target product capabilities;
- the core domain concepts;
- architectural and security invariants;
- policy composition and resolution;
- ownership boundaries between features;
- compatibility and migration principles;
- extension points for future execution modes;
- the intended delivery sequence behind the [Quiz Execution tracker](https://github.com/Gegcuk/QuizMaker/issues/472).

This document is broader than any individual implementation issue. It explains how the individual issues fit together and what architectural assumptions future work must preserve.

It is not a commitment to implement every capability immediately. Each capability remains subject to product prioritisation, security review, data-retention decisions, operational constraints, and the acceptance criteria of its owning issue.

Apply the most recent explicit owner decision within its stated scope. The dated refinements in the [decision register](./decision-register.md) take precedence over older linked issue wording on that same point; otherwise a more specific approved issue clause governs. When a decision conflicts with an invariant or another still-applicable decision, resolve the conflict explicitly rather than silently interpreting it.

The [decision register](./decision-register.md) records the approved target decisions and their owning issues, synchronized with owner refinements through 22 October 2026. The [open questions](./open-questions.md) separate remaining choices and conflicting decisions from those already approved. Approval describes intended product behaviour; it is not evidence that a feature is implemented or publicly activated. Candidate strategies and future scenarios below are an extension catalogue, not permission to enable them.

Read [P1–P5 preparation evidence](./open-questions.md#preparation-evidence) for the verified current code, source issues, rate-limit distinctions, lifecycle gaps and bounded calculations. Proposed technical candidates and illustrative workloads there are not runtime defaults.

The [approved Q13–Q15 tag scope](./decision-register.md#quiz-certificate-batch-approved) keeps one author-provided display name initially and separate group-catalog management authority. Retirement hides a tag from new selection while preserving existing question bindings, including authorized drafts, immutable revisions and historical topic/report evidence. Existing published quizzes still work; no questions or attempts are deleted. A rename/merge is a different operation and does not authorize rewriting published revisions.

---

## Isolated Deployments and Operator Authority

The [22 October Q08 refinement](./decision-register.md#isolated-deployment-operator-boundary) requires an architectural foundation for on-premises and organization-dedicated virtual deployments with isolated organizational data and a local platform operator. Operator authority is deployment/trust-boundary scoped and still permission/resource checked; it is not inherently a central vendor account or an automatic privilege of every organization administrator.

Isolation must cover persistence, images/documents, queues/jobs, caches/projections, notification data, audit/logs/metrics, exports and backups. Configuration and project-owned external-system ports must support deployment-local identities, storage and services without mandatory transfer into a shared control plane or inherited central credentials. One tenant column or one separate database is not evidence that all these paths are isolated. Provisioning, keys/storage topology, bootstrap, backups and isolation tests remain engineering design; no infrastructure vendor or change to the existing shared-service topology is selected.

For example, a school-local operator may place an authorized hold on its examination evidence within its own deployment; that role grants no access to another installation or the shared SaaS environment.

---

## Product Vision

QuizMaker should support two equally important product families without maintaining two separate execution engines.

### Learning and self-improvement

A learner should be able to:

- practise an entire quiz;
- receive immediate feedback after each submitted answer;
- repeat only incorrectly answered questions;
- practise questions not seen before;
- keep a stable question order between sessions;
- receive a different random selection on every session;
- use hints and explanations;
- retry until correct where the policy allows it;
- rate confidence in an answer;
- review due material through spaced repetition;
- work towards topic mastery;
- resume an unfinished session;
- use flashcard or self-assessment workflows;
- complete ungraded reflective activities.

### Teaching and assessment

An author, teacher, or assignment manager should be able to:

- maintain a large reusable question bank;
- publish an immutable version of a quiz;
- create an assignment for a particular audience;
- distribute an authenticated or anonymous participant link;
- choose a fixed or random number of questions from a larger bank;
- require balanced selection across topics and difficulty levels;
- give every participant the same form or equivalent individual forms;
- set opening time, deadline, and attempt duration;
- configure total, section, or question-level timers;
- prevent backtracking or allow free navigation;
- limit the number of attempts;
- delay scores, correct answers, or explanations until a specified release point;
- manually grade free-text or file-based answers;
- view participant and cohort results;
- issue certificates where appropriate;
- provide participant-specific accommodations;
- investigate disputed attempts using reproducible evidence.

These workflows must reuse the same immutable content, policy-resolution, form, answer, grading, and review foundations.

---

## Representative Product Scenarios

The architecture must be capable of expressing the following scenarios without controller-specific mode branches.

### Scenario A: approved free-practice target

A learner opens their own quiz and chooses practice.

The system:

1. uses the current published revision;
2. selects all questions;
3. uses authored question and option order by default, with permitted option shuffling resolved and persisted before start;
4. allows free navigation;
5. accepts each submitted answer atomically and locks it immediately;
6. reveals correctness, question score, correct answer, and explanation only after that acceptance;
7. offers an authored hint before submission where the saved practice policy permits it;
8. restores the same form, order, accepted answers, and unanswered state after reconnect;
9. requires explicit final submission, allowing confirmed unanswered items without inventing accepted incorrect answers;
10. permits later practice through a separate attempt/session rather than editing the accepted answers.

This is the approved untimed, no-pause baseline in [#612 D01–D03/R01](https://github.com/Gegcuk/QuizMaker/issues/612) and [#687 D11–D13](https://github.com/Gegcuk/QuizMaker/issues/687). The [ONE_BY_ONE default and skip/return configuration](./decision-register.md#one-by-one-navigation) and [additional view/edit/pending-confirmation capabilities](./decision-register.md#answer-finalization-and-review) are also approved. Remaining finalization interactions and `TIMED` mapping details retain their activation gates; no unsupported capability becomes active implicitly.

### Scenario B: consistent learner practice

A learner wants to compare progress across repeated attempts.

The system:

- selects the same question set for that learner;
- uses a stable order derived from a versioned deterministic key;
- preserves the option order where configured;
- records each attempt separately;
- calculates progress without changing historical results.

### Scenario C: future timed randomized student assignment

A teacher has a bank of 120 questions and creates an assignment that:

- opens at 09:00 on 10 October;
- closes at 18:00 on 17 October;
- gives every student 45 minutes after first start;
- selects 30 questions;
- includes 10 questions from each of three topics;
- includes a defined balance of easy, medium, and difficult questions;
- gives every participant an individually randomized but equivalent form;
- allows one attempt;
- prevents returning to submitted questions;
- reveals no correctness or score during the attempt;
- automatically submits when time expires;
- releases the total score after the deadline;
- releases correct answers and explanations only after the teacher manually approves review.

The student accesses the assignment through an authenticated invitation or a capability-backed link. The participant cannot change any of these rules.

This broader capability example is not the approved initial formal fixture. The exact authenticated 40-item-bank/20-item-form, 30-minute, binary-scored fixture is owned by [#668 D38](https://github.com/Gegcuk/QuizMaker/issues/668); guest, stratified, manual-release, and other extensions require their own gates.

### Scenario D: future mixed automatic and manual grading

An assignment contains:

- multiple-choice questions;
- fill-gap questions;
- short free-text questions;
- one essay graded using a rubric.

The participant can submit before manual grading is complete.

The system represents:

```text
Execution: SUBMITTED
Answer submission: COMPLETE
Automatic grading: COMPLETE
Manual grading: PENDING
Result release: WAITING_FOR_GRADING
Review availability: HIDDEN
```

Submission, grading, result release, and review are therefore separate concepts.

Manual/rubric grading is deferred from v1 by [#675 R05](https://github.com/Gegcuk/QuizMaker/issues/675). This scenario describes the extension boundary, not the initial grading contract.

### Scenario E: branching scenario

A participant receives the next scenario node based on their previous decision. Future nodes are not preselected or visible.

Each selected edge and newly delivered node is persisted before delivery so that the participant path remains reproducible.

### Scenario F: live competition

A host opens a synchronized session. Participants receive rounds at the same server-controlled time.

Live coordination manages:

- lobby membership;
- teams;
- current round;
- answer windows;
- synchronized delivery;
- provisional scores;
- leaderboard publication.

The live feature reuses immutable content and scoring contracts but is not implemented as another `AttemptMode`.

---

## Architectural Objective

Support practice, assessments, assignments, review, manual grading, adaptive learning, branching scenarios, spaced repetition, surveys, and live sessions without creating a separate copy of the execution engine for each mode.

An execution mode is a reusable server-owned preset.

At attempt creation, the preset, published content, assignment configuration, participant context, accommodations, and only explicitly permitted overrides are resolved into one immutable versioned policy snapshot.

```text
Platform defaults
    + run preset
    + published quiz revision constraints
    + assignment or legacy share-link configuration
    + participant eligibility
    + authorized accommodation overlay
    + explicitly permitted start-time override
    = immutable ResolvedAttemptPolicy
```

The attempt executes its saved policy and saved form.

It must not reinterpret mutable quiz, link, template, accommodation, question-bank, or algorithm data after it starts.

---

## Fundamental Architectural Principles

### 1. Modes are presets, not runtime branches

`PRACTICE`, `ASSESSMENT`, `FLASHCARDS`, and similar names may be exposed as convenient product presets, but they must not become the primary runtime architecture.

Avoid:

```java
if (mode == PRACTICE) {
    ...
} else if (mode == ASSESSMENT) {
    ...
} else if (mode == LIVE) {
    ...
}
```

Prefer:

```text
Practice preset
    -> SelectionPolicy
    -> OrderingPolicy
    -> DeliveryPolicy
    -> NavigationPolicy
    -> SubmissionPolicy
    -> DisclosurePolicy
    -> TimingPolicy
    -> GradingPolicy
    -> ReviewPolicy
```

A new conventional mode should usually be expressible as another combination of existing policies.

### 2. Attempts execute immutable snapshots

After creation, an attempt never continuously reads mutable execution settings.

It executes:

- a specific immutable quiz revision;
- a versioned resolved policy;
- a persisted fixed or progressive form;
- stored answer and lifecycle state;
- versioned grading contracts.

### 3. The server owns every consequential decision

The server decides:

- who the participant is;
- whether they may start;
- which questions are selected;
- which question is currently available;
- which actions are allowed;
- whether time remains;
- whether an answer may change;
- how the answer is graded;
- which protected information may be released;
- whether review is available.

The client renders server state and requests actions. It does not authorize them.

### 4. Participant experience is persisted as evidence

A seed is useful but insufficient.

The system persists what the participant actually received:

- selected questions;
- section and case membership;
- question order;
- option order;
- progressive selections;
- branch transitions;
- applied accommodations;
- algorithm versions.

### 5. Submission, grading, release, and review are independent

Completing the participant interaction does not necessarily mean:

- every answer has been graded;
- the total score is final;
- results have been released;
- correct answers may be reviewed.

### 6. Fundamentally different runtimes use separate bounded contexts

Learning schedules, live orchestration, and potentially advanced analytics have different lifecycles from assessment attempts.

They may reuse shared immutable content and strategy interfaces, but they must not overload the ordinary attempt lifecycle.

---

## Starting Constraints

The repository contains an existing practice/assessment implementation and API contracts that remain authoritative until an explicit activation gate. The [22 October owner clarification](./decision-register.md#legacy-compatibility-and-retained-fallback) confirms that existing quizzes and a frontend that may lag behind backend changes must be supported. Exact production/attempt inventory still needs evidence; an empty or disposable environment must not be assumed.

Current constraints include:

- `Attempt` has a direct relationship to a mutable `Quiz`;
- `Answer` has a direct relationship to a mutable `Question`;
- execution uses a compact `AttemptMode` enum;
- current routes contain mode-specific or route-specific behaviour;
- existing request and response shapes are used by the frontend;
- existing historical attempts do not contain complete policy or form evidence;
- legacy anonymous access uses a sentinel user;
- share links currently act as capabilities but not immutable assignments;
- some request shapes contain disclosure-related flags;
- existing practice and assessment behaviour must continue during migration.

The target architecture is additive.

It introduces policy-aware application services and persistence alongside the retained legacy implementation. Legacy attempt execution stays on `/api/v1`; policy-driven attempt execution uses explicit `/api/v2` routes. Clients adopt v2 deliberately; enabling target capabilities does not redirect legacy requests.

The [owner's preservation decision](./decision-register.md#legacy-compatibility-and-retained-fallback) retains the existing API, quizzes/user data and required legacy execution path, including its writers. It refines [#688 D10/D29](https://github.com/Gegcuk/QuizMaker/issues/688): a preproduction cleanup cannot remove that required path or treat existing retained data as disposable. Any eligible disposable-data cleanup still needs an environment check, backup evidence and exact owner-confirmed scope. Target attempts retain their evidence and execution binding. [Operator-controlled fallback](./decision-register.md#operator-controlled-legacy-fallback) is decided; the exact [coordination and readiness evidence](./open-questions.md#oq-cutover) still precede activation.

The migration must not:

- rewrite all controllers in one change;
- destructively reinterpret historical attempts;
- alter active attempts in place;
- silently infer historical policies that were never stored;
- remove legacy routes before their migration lifecycle is complete.

---

## Core Terms

| Term | Meaning | Primary owner |
| --- | --- | --- |
| Mutable Quiz | Authoring aggregate that authors may edit. | Quiz |
| Quiz Draft | Mutable unpublished authoring state. | Quiz |
| Quiz Revision | Immutable published content and response contracts. | Quiz Revision |
| Question Revision | Immutable version of one question and its grading contract. | Quiz Revision |
| Section | Ordered grouping with optional policy overrides and completion rules. | Quiz Revision |
| Case Block | Atomic collection of related questions sharing context. | Quiz Revision |
| Question Bank | Eligible immutable questions used by selection strategies. | Quiz Revision |
| Run Preset | Immutable versioned policy defaults; initially code-defined, later managed within an approved ownership scope. | Quiz/Assignment |
| Assignment | Published delivery configuration bound to one quiz revision and policy configuration. | Assignment |
| Legacy Share Link | Existing capability-backed access path preserved through an adapter. | Sharing |
| Resolved Attempt Policy | Versioned rules applied to one attempt. | Attempt |
| Participant Subject | Server-resolved participant identity abstraction. | Attempt/Identity |
| Attempt | One participant execution governed by one policy and form. | Attempt |
| Attempt Form | Exact fixed or progressively constructed participant-visible content. | Attempt |
| Form Item | Immutable question occurrence within an attempt form. | Attempt |
| Answer Draft | Mutable, unsubmitted participant work where policy permits it. | Answer Submission |
| Provisional Response | Saved participant response that remains editable until the policy's finalization boundary; distinct from final accepted evidence. | Answer Submission |
| Pending Confirmation | Explicit intent to defer confirmation, optionally retaining a proposed response; still unanswered if the attempt completes without confirmation. | Answer Submission |
| Answer Submission | Accepted answer aligned to a form item and response contract. | Answer Submission |
| Grade | Automatic or manual evaluation of a submitted answer. | Grading |
| Result | Aggregated assessment outcome derived from eligible grades. | Results |
| Review | Participant-visible post-submission content controlled by release policy. | Review |
| Execution Trace | Append-only protected evidence of decisions and accepted commands. | Attempt |
| Learning Session | Non-assessment practice and scheduling lifecycle. | Learning |
| Live Session | Multi-participant synchronized orchestration. | Live |

---

## Bounded Contexts and Ownership

```mermaid
flowchart LR
    Quiz["Quiz Authoring"] --> Revision["Immutable Quiz Revision"]
    Revision --> Assignment["Assignment"]
    Preset["Run Preset"] --> Assignment

    Assignment --> Resolver["Policy Resolution"]
    Revision --> FormService["Attempt Form Service"]
    Resolver --> Attempt["Attempt Execution"]
    FormService --> Attempt

    Attempt --> Submission["Answer Submission"]
    Submission --> Grading["Grading"]
    Grading --> Results["Results"]
    Results --> Review["Review / Release"]

    Revision --> Learning["Learning Sessions"]
    Revision --> Live["Live Sessions"]

    Identity["Identity / Capability"] --> Assignment
    Identity --> Attempt
    Accommodation["Accommodation"] --> Resolver
```

### Quiz and Quiz Revision

Own:

- mutable authoring;
- publication;
- immutable question content;
- immutable response and grading contracts;
- sections;
- case blocks;
- authored order;
- question metadata;
- shuffle constraints;
- rubric definitions.

Do not own:

- participant progress;
- attempt policy snapshots;
- answers;
- assignment eligibility;
- result release.

### Assignment

Own:

- revision binding;
- policy or preset binding;
- audience and eligibility;
- availability window;
- participant limits;
- assignment publication and revocation;
- assignment-level reporting configuration.

Do not own:

- active attempt state;
- answer submissions;
- grades;
- participant review representation.

### Attempt Execution

Own:

- resolved policy snapshot;
- participant subject;
- attempt form;
- current execution state;
- allowed-action calculation;
- navigation;
- timing checkpoints;
- command idempotency;
- execution trace references.

### Answer Submission

Own:

- drafts;
- submitted response values;
- answer state;
- answer locking;
- response-contract validation;
- answer command concurrency.

### Grading

Own:

- automatic grade decisions;
- manual grading queue;
- rubric decisions;
- grading state;
- grading algorithm version;
- score correction or supersession records where supported.

### Results and Review

Own:

- result aggregation;
- pass/fail evaluation;
- result selection across attempts;
- release decisions;
- review windows;
- participant-visible review content;
- certificates;
- leaderboard projections where applicable.

### Learning

Own:

- due state;
- mistake-review selection;
- mastery state;
- scheduling strategy;
- learning-session outcomes.

Learning progress must not be stored as assessment result data.

### Live

Own:

- lobby;
- participants;
- teams;
- rounds;
- synchronized question windows;
- connection and reconnection state;
- provisional scoring;
- leaderboard timing.

---

## Domain Model

```mermaid
flowchart TD
    Q["Mutable Quiz"] --> QR["QuizRevision"]
    QR --> SEC["Sections / Case Blocks"]
    QR --> QREV["QuestionRevisions"]

    PRESET["RunPreset"] --> ASG["Assignment"]
    QR --> ASG
    ACCESS["Audience / Capability Rules"] --> ASG

    ASG --> ATT["Attempt"]
    SUBJECT["ParticipantSubject"] --> ATT
    ACC["Accommodation Overlay"] --> ATT

    ATT --> POLICY["ResolvedAttemptPolicy"]
    ATT --> FORM["AttemptForm"]
    ATT --> EXEC["Execution State"]
    ATT --> TRACE["Execution Trace"]

    FORM --> ITEM["AttemptFormItem"]
    ITEM --> ORDER["Question and Option Order"]

    ATT --> SUB["AnswerSubmissions"]
    SUB --> GRADE["Grades"]
    GRADE --> RESULT["Result"]
    RESULT --> REVIEW["Review Availability"]
    RESULT --> CERT["Certificate"]
```

---

## Policy Resolution and Precedence

### Authorized launch context

The [access-aware launch decision](./decision-register.md#access-aware-start) distinguishes ordinary launches from assignment participation. An ordinary launch offers a choice among policies authorized for the participant and quiz. Full rights over the particular quiz give an untimed training default with immediate feedback after immutable answer acceptance; take-only access is limited to the policies provided by the quiz creator, including their timing and disclosure restrictions.

The [22 October full-rights criterion](./decision-register.md#full-rights-practice-authority) includes the quiz owner and users authorized to fully edit that quiz's questions and correct answers. Qualifying editors need no separate free-practice grant. Determine effective ownership/content-editing authority on the server for the authenticated subject and specific quiz, with applicable resource, organization and membership checks. Taking/viewing a quiz, editing only metadata or a subset of questions, rights over another quiz, and a teacher/moderator/administrator role name alone do not establish this entitlement. Administrative or delegated authority qualifies when its actual permissions satisfy the criterion. A take-only participant may still use a practice policy explicitly authorized by the creator.

An assignment launch strictly uses that assignment's policy, including for a participant with full rights over the quiz. An ordinary-launch policy cannot replace it, and a separate practice attempt does not complete the assignment. Approved policy composition, such as an authorized accommodation, still occurs within that assignment context.

The server resolves resource-specific authority and the authorized launch context before composing the effective policy. A client mode cannot grant the training path, substitute the assignment policy or remove creator-policy restrictions. The owner/full-content-editor eligibility criterion is decided; mapping it to existing permission/resource checks remains engineering preparation and grants no new role permissions. Compatibility-request context/policy references, remaining mode compatibility and selection/unavailable-policy error contracts remain [open details](./open-questions.md#oq-r01).

The [22 October ambiguous-start decision](./decision-register.md#ambiguous-ordinary-policy-selection) requires an explicit policy choice before creating an ordinary attempt when several policies are authorized and the choice is ambiguous. Offer only participant-safe information about authorized alternatives. Pending selection creates no attempt, placeholder, form or attempt-bound policy snapshot, starts no timer, consumes no attempt allowance and emits no `AttemptStarted` evidence. Do not silently select the first or least restrictive policy. Revalidate the chosen policy and authority at the subsequent start boundary. The existing owner/full-editor practice default, strict assignment binding and unambiguous legacy mappings remain intact; ambiguous legacy starts require client choice support before activation. This decision does not choose an HTTP status or a new single-policy confirmation rule.

The separate [22 October legacy TIMED decision](./decision-register.md#legacy-timed-with-disabled-timer) preserves an untimed new start through the old API when the quiz timer is explicitly disabled and an authorized untimed policy is unambiguously resolved. The legacy enum does not create a duration or practice authority; preserve other legacy behavior and response contracts while capturing the effective policy. A stored duration for the disabled timer does not activate it. Ambiguity still requires a choice, and required assignment/policy timing cannot be removed by the legacy quiz flag. Enabled but invalid timing stays subject to validation before attempt creation; no invented duration or untimed fallback is authorized. Existing attempts retain their captured timers and historical evidence.

Once created, an attempt keeps its captured policy and timer. Quiz edits do not turn that attempt into untimed practice; pause follows the saved policy, and a permitted untimed run is a different attempt. A full-rights user explicitly entering a policy-bound self-test follows that selected policy for the whole attempt.

### Composition within the authorized context

Policy composition must use deterministic precedence.

A recommended order is:

1. platform safety defaults;
2. preset defaults;
3. quiz-revision structural constraints;
4. assignment configuration;
5. audience or cohort restrictions;
6. participant accommodation overlay;
7. explicitly permitted start-time overrides;
8. server-computed derived fields.

Later inputs may override earlier inputs only where the schema explicitly allows them.

For example:

- an assignment may disable pause even if the preset allows it;
- an accommodation may add time where its approval allows it;
- a participant may choose a permitted question count for personal practice;
- a participant may not disable an assignment deadline;
- an option ordering policy may not override a question-level `shuffleAllowed = false`.

Policy resolution must return both:

- the final immutable `ResolvedAttemptPolicy`;
- provenance explaining which source contributed each material override.

Example:

```text
timing.totalDuration = PT45M
    source: Assignment version 3

timing.extraDuration = PT15M
    source: Accommodation code EXTRA_TIME_33

timing.effectiveDuration = PT60M
    source: Derived policy value
```

Detailed accommodation rationale remains outside the attempt snapshot. Only the minimum approved code, version, and effective changes belong in the snapshot.

---

## Resolved Attempt Policy

The complete target model is composed from typed, versioned sub-policies.

```text
ResolvedAttemptPolicy
├── content
├── questionSelection
├── questionOrdering
├── answerOrdering
├── delivery
├── navigation
├── submission
├── answerChange
├── disclosure
├── hints
├── timing
├── pause
├── attempts
├── access
├── grading
├── review
├── results
├── accessibility
└── integrity
```

The approved v1 schema includes all of these typed dimensions. Unimplemented capabilities have explicit disabled/not-configured values and cannot be activated by arbitrary data. Only the code-defined `FREE_PRACTICE/v1` preset is initially supported; other capabilities require their own conformance gates. This is the broad-schema choice in [#612 A01/A02/D06](https://github.com/Gegcuk/QuizMaker/issues/612), not permission to omit dimensions or build a generic extension bag.

### Implemented policy vocabulary foundation (#515)

`features/attempt/domain/model/policy/ResolvedAttemptPolicy` is an immutable, transport-neutral
Java value with all 19 dimensions. Its constructor accepts the initial authenticated, flat,
untimed practice contract and rejects unsupported versions, missing values and unsupported
combinations. It has no Spring, JPA, Jackson, preset-name or `AttemptMode` dependency.
No existing endpoint or attempt writer consumes it yet.

The value follows #612 A01/A02, D01–D03, D07 and R01, A22 binary grading, and the subsequent
practice-retention, server-authorization and [policy-controlled completeness](./decision-register.md#voluntary-completion-requirement)
refinements. Every sub-policy is mandatory:
omitting a dimension is not equivalent to disabling it. Nullable configuration/default
inference and arbitrary extension maps are not supported.

| Dimension | Initial value contract |
|---|---|
| `content` | Exact quiz revision UUID and revision schema `1`; `PRECOMPUTED`, `IMPLICIT_FLAT`, cases `NOT_CONFIGURED`. The UUID is a reference, not proof of existence or access. |
| `questionSelection` | `ALL`, algorithm version `1`. |
| `questionOrdering` | `AUTHORED`, algorithm version `1`. |
| `answerOrdering` | `AUTHORED` or the approved `SHUFFLED_PER_ATTEMPT` choice, algorithm version `1`. This is vocabulary, not an ordering implementation. |
| `delivery` | `ALL_AT_ONCE`. |
| `navigation` | `FREE`; membership and answer immutability still apply. |
| `submission` | Drafts/autosave disabled; policy-controlled require-all accepts both `false` and `true`, with `false` the future preset default in #516. Completion confirmation remains required, with single and atomic batch acceptance, identical replay/changed conflict, payload-free terminal submit, and no credit for unanswered items without fabricating accepted incorrect answers. |
| `answerChange` | `LOCK_ON_ACCEPTANCE`. The known `EDIT_UNTIL_FINALIZATION` value is rejected by the aggregate; it does not activate the separately owned editable-answer capability. |
| `disclosure` | Correctness, question score, correct answer and explanation `AFTER_ACCEPTANCE`; total score `AFTER_COMPLETION`; rubric, grader comments and cohort statistics explicitly `NEVER`. |
| `hints` | Authored hints `ON_REQUEST`, or `DISABLED` for the permitted participant choice; no generated hint or retry mechanism. |
| `timing` | `NONE`; no invented duration, deadline or grace default. |
| `pause` | `DISABLED`. |
| `attempts` | `UNLIMITED`; this is not exemption from authorization or existing request-rate limits. |
| `access` | `AUTHENTICATED` with `SERVER_REVALIDATED_AT_START_AND_READ`; the value grants no identity, permission, ownership or visibility. |
| `grading` | `BINARY`, exact points with integer basis points. Score calculation and rounding remain owned by #603 and exact question contracts. |
| `review` | `AFTER_COMPLETION`, all four categories: submitted answers, correctness, correct answers and explanations; while the account/result is retained and current access permits it. |
| `results` | Running progress enabled; explicit pass threshold in integer basis points, inclusive range `0–10000`. D02's default `7000` is a named constant for the future preset owner, not a missing-value fallback. Result selection is `NOT_CONFIGURED`; leaderboard and certificates are `DISABLED`. |
| `accessibility` | Accommodations `NOT_CONFIGURED`; this does not disable ordinary accessible presentation. |
| `integrity` | Exact persisted identities/order, immutable engine binding, rejection of unknown versions. This describes required evidence; construction does not persist or verify it. |

`submission.requireAllAnswers` retains the supplied value without substituting a default.
Both values are supported with the same flat, untimed, immediate-lock configuration;
they do not depend on editable answers, timers or sections. The approved default `false`
belongs to the preset owner #516, not to value validation. #529 owns the actual completeness
check: with eight accepted answers out of ten, OFF permits confirmed completion, while ON
denies incomplete voluntary submission and preserves the answers and nonterminal state.
This foundation represents that choice only; terminal-command writes and their atomicity
must be proved by #529.

Disclosure rules must contain each closed category exactly once. Review categories must be
unique and complete for the supported aggregate. Both collections are defensively copied
and normalized into vocabulary order, making equivalent permutations equal. Their maximum
cardinality is the number of declared categories, not a new numerical product quota.
This normalization defines value equality only; it is not canonical JSON or a hashing format.

`parseValue` accepts exact declared symbolic names without aliases, ordinal conversion,
case folding or whitespace trimming. Unknown names fail with `UNKNOWN_VALUE`. Version
checks distinguish policy schema, content schema and strategy versions. Other bounded
`PolicyValidationException` reasons distinguish missing/invalid values, contradictory
combinations, unsupported combinations and deferred capabilities. Messages contain only
the fixed reason and never echo input. These are internal failures; no new HTTP status,
`ProblemDetail` mapping or public schema is introduced.

For example, locking an accepted answer and then disclosing its correctness is a valid
practice value. Asking to keep that answer editable while revealing its key fails with
`INVALID_COMBINATION`. Withholding feedback does not enable editing: that capability still
fails with `UNSUPPORTED_CAPABILITY`. A failed construction has no persistence side effects.

Pure JUnit vectors exercise the approved tuple, permitted hint/option choices, basis-point
boundaries, all required references, category completeness/duplicates, deterministic
permutations, immutable copies and safe failures. A compiled-class dependency check guards
the pure boundary. These tests prove the value contract, not authorization, answer writes,
algorithm execution, scoring, release, retries or a complete attempt.

Integration ownership remains separate: #516 supplies presets and legacy mappings; #517
resolves precedence/provenance and canonical bytes/hashes; #518 persists snapshots; #520,
#521 and #544 supply actual ordering/selection. Exact question response/scoring bindings
remain revision-owned under #597/#598 and are resolved through their existing boundary,
not copied into a competing policy authority. Consumers must check content, permissions,
question-level constraints and actual algorithm availability before writing. The dark
start is #600 and public activation is #609. Approved formal, timed, section, editable,
guest, Learning and other capabilities require their own typed extensions and conformance;
they cannot be enabled by supplying arbitrary policy data.

### Content policy

Defines:

- quiz revision;
- eligible sections;
- eligible question bank;
- case-block treatment;
- whether the form is fixed or progressive;
- response-contract versions.

### Question selection policy

Candidate strategies:

```text
ALL
MANUAL_SET
RANDOM_COUNT
RANDOM_PERCENTAGE
STRATIFIED
WEIGHTED_RANDOM
UNSEEN_ONLY
INCORRECT_ONLY
LOW_CONFIDENCE_ONLY
DUE_FOR_REVIEW
ADAPTIVE
```

#### Canonical selected-strategy start (#547)

The internal authenticated flat start supports explicit server-authorized `MANUAL_SET`,
`RANDOM_COUNT` and `RANDOM_PERCENTAGE` configurations alongside the unchanged `ALL`
default. The existing owner/full-content-editor check supplies author authority;
participant overrides cannot grant selection authority. This is a dark application
boundary, not a new start endpoint, assignment implementation or public activation.

The resolved policy captures the exact typed configuration and provenance. Historical
`ALL` canonical JSON/hashes and start fingerprints retain their original representation.
Non-default selection participates in command identity, so reusing a key with another
configuration conflicts. Random selection derives its context from the server-created
canonical receipt ID, never a client seed, selected result or display order.

The existing start transaction materializes the form and writes immutable
`attempt_form_selection` metadata before completing the subject/lifecycle/trace/receipt.
`attempt_form_item` and the existing option-order tables retain the exact delivered IDs
and order; metadata stores configuration/version and bounded seed provenance/fingerprint,
never raw seed/key material or rejected candidates. A committed replay reads its saved
acknowledgement without selecting again, including when seed configuration is unavailable.
A failed strategy or reached persistence fault rolls back all start-owned writes.
Non-default empty selections cannot start an attempt. Missing evidence on an older form
stays explicitly absent; the additive migration does not infer or backfill it.

The original standalone `materialize` boundary remains compatible; canonical start uses
`materializeSelected` to require metadata persistence in the same transaction. There is
no second start orchestrator. Structured/stratified selection remains gated by #546/#604;
formal D38 activation still requires real composition and conformance in #668. A valid
strategy test double proves only this extension boundary, not an algorithm or formal flow.

### Question ordering policy

Candidate strategies:

```text
AUTHORED
SHUFFLED_PER_ATTEMPT
STABLE_PER_USER
STABLE_PER_ASSIGNMENT
STABLE_PER_SHARE_LINK
STABLE_PER_COHORT
SEEDED
DIFFICULTY_ASC
DIFFICULTY_DESC
TOPIC_GROUPED
TOPIC_INTERLEAVED
ADAPTIVE
```

### Answer ordering policy

Candidate strategies:

```text
AUTHORED
SHUFFLED_PER_ATTEMPT
STABLE_PER_USER
STABLE_PER_ASSIGNMENT
STABLE_PER_COHORT
SEEDED
```

Question-level constraints override general shuffling where necessary.

### Delivery policy

Candidate modes:

```text
ALL_AT_ONCE
SECTION_AT_ONCE
ONE_AT_A_TIME
PREFETCH_NEXT
PROGRESSIVE
```

### Navigation policy

The [approved ONE_BY_ONE decision](./decision-register.md#one-by-one-navigation) preserves the legacy answer-before-next, no-skip default and also requires an authorized configuration that allows skipping and returning to unanswered questions. One-at-a-time delivery does not itself select either navigation behavior. Resolve the configuration before start and preserve it in the attempt policy; ordinary choice and assignment binding follow the authorized launch context.

Candidate modes:

```text
FREE
SEQUENTIAL
SEQUENTIAL_WITH_BACKTRACK
SECTION_LOCKED
BRANCHING
```

### Submission policy

Defines:

- draft availability;
- autosave support;
- answer requirement;
- skip behaviour;
- single or batch submission;
- answer locking;
- idempotency;
- completion confirmation;
- automatic submission.

### Disclosure policy

Defines independent rules for:

- correctness;
- per-question score;
- total score;
- correct answer;
- explanation;
- hints;
- rubric;
- grader comments;
- participant answers;
- cohort statistics.

### Timing policy

Defines:

```text
NONE
TOTAL_ATTEMPT
PER_SECTION
PER_QUESTION
COMBINED
```

It also defines availability, deadline, grace periods, and expiry behaviour.

### Pause policy

Candidate values:

```text
DISABLED
IMMEDIATE
AFTER_CURRENT_SUBMISSION
BETWEEN_SECTIONS
LIMITED_COUNT
LIMITED_DURATION
MODERATOR_ONLY
```

### Attempt policy

Defines:

```text
UNLIMITED
FIXED_COUNT
ONE_PER_PERIOD
ONE_PER_REVISION
MANUAL_RESET
```

It also defines cooldown, retake selection, and result-selection rules.

### Grading policy

Candidate strategies:

```text
BINARY
PARTIAL_CREDIT
NEGATIVE_MARKING
WEIGHTED
MANUAL
RUBRIC
CONFIDENCE_WEIGHTED
MASTERY
UNGRADED
```

### Review policy

Defines:

- review eligibility;
- release timing;
- review duration;
- content visible in review;
- revocation behaviour;
- whether only incorrect answers are shown.

### Result policy

Defines:

- participant visibility;
- author visibility;
- assignment reporting;
- pass thresholds;
- section thresholds;
- best/latest result selection;
- leaderboard behaviour;
- certificate eligibility.

---

## Typed Policies, Not a Generic Rules Engine

Policies are typed Java records or value objects.

For example:

```java
public record TimingPolicy(
        TimingMode mode,
        Duration totalDuration,
        Map<SectionId, Duration> sectionDurations,
        Duration defaultQuestionDuration,
        Instant opensAt,
        Instant deadline,
        Duration gracePeriod,
        ExpiryAction expiryAction
) {}
```

Canonical JSON acts as:

- a persistence boundary;
- a hashing boundary;
- a compatibility boundary;
- an operational inspection format.

It must not become an arbitrary expression language or generic runtime workflow engine.

Algorithmic behaviour belongs behind narrow interfaces:

```java
interface QuestionSelectionStrategy {
    SelectionResult select(SelectionContext context);
}

interface QuestionOrderingStrategy {
    OrderedQuestions order(OrderingContext context);
}

interface AnswerOrderingStrategy {
    OrderedOptions order(AnswerOrderingContext context);
}

interface GradingStrategy {
    GradeDecision grade(GradingContext context);
}

interface ReviewReleaseStrategy {
    ReviewDecision evaluate(ReviewContext context);
}
```

Each implementation must have a stable version identifier.

---

## Policy Validation

A central validator must reject unsupported or dangerous combinations before an attempt exists.

Examples:

```text
ALL_AT_ONCE
+ future questions hidden
= invalid
```

```text
ADAPTIVE selection
+ fully precomputed fixed form
= invalid unless the adaptive policy explicitly precomputes
```

```text
MANUAL grading
+ immediate final score release
= invalid
```

```text
correct answer released after submission
+ scored answer editable after submission
= invalid answer oracle
```

```text
timed protected assessment
+ unlimited participant-controlled pause
= invalid or explicit high-risk override
```

```text
branching navigation
+ client-selected next question
= invalid
```

The validator should distinguish:

- structurally invalid policy;
- unsupported combination;
- unauthorized override;
- insufficient content;
- incompatible revision structure;
- unavailable algorithm version.

Failures return stable RFC 7807 responses and create no partial attempt.

---

### Built-in practice preset and legacy mapping (#516)

`AttemptPresetService` exposes one code-defined `FREE_PRACTICE/v1` through
`AttemptPresetServiceImpl`. `BuiltInPracticePreset` supplies the complete typed #515 tuple
for explicit immutable `ContentPolicy` revision metadata. It reads no mutable quiz configuration.
This is an internal foundation: no controller or existing attempt writer consumes it.

The frozen defaults follow #612 A01/A02, D01–D03, D06 and R01: `ALL` selection, authored
question/option order, `ALL_AT_ONCE` delivery, `FREE` navigation, untimed/no pause, authored
hints on request, immutable accepted responses and immediate per-item feedback after acceptance.
Final total and complete review follow completion. `requireAllAnswers=false`, completion
confirmation is required and the pass threshold is `7000` integer basis points. Other typed
disabled/not-configured dimensions retain the #515 values. Later participant choices and
authorized overrides are resolver work; they do not mutate this preset version.

`LegacyPracticeRequest` mapping version `1` accepts exact legacy mode names and trusted
server-prepared launch facts. It only describes a **new** launch. The caller must establish
identity, resource/organization/membership/visibility access, full question-and-answer editing
authority and authorized policy versions; role labels and client-supplied identifiers cannot
supply those facts. `AuthorizedPresetPolicy` references an unmodified preset policy version,
not a managed or overridden policy disguised as a built-in default.

| Input | Internal outcome |
|---|---|
| Unauthenticated or missing/ambiguous resource authority | `Unavailable`; no owner/default elevation. |
| Ordinary owner/full-content-editor without an explicit selection | The established built-in default; no additional practice grant or ambiguity from other alternatives. |
| Take-only with zero / one / multiple authorized alternatives | `Unavailable` / mapped supported preset / `ChoiceRequired`. No first-item selection or filtering away unsupported alternatives. |
| Explicit policy version selection | It must occur in the captured authorized alternatives, even for an owner. Unknown preset/version is unsupported; no built-in fallback. |
| Assignment, including an owner/full editor | `LegacyOnly(ASSIGNMENT_REQUIRES_BOUND_POLICY)`; this mapper supplies no replacement assignment policy. |
| `ALL_AT_ONCE` with unambiguous compatible authorization | Mapped preset reference plus selected authorized policy-version identity; that identity is absent for the full-rights default. The quiz timer flag does not make this mode timed. |
| `TIMED` with an explicitly disabled quiz timer and an unambiguous authorized untimed preset/default | Mapped untimed preset. An inactive stored duration does not participate. |
| `TIMED` with enabled / unknown quiz timing | Explicit `LegacyOnly` / `Unavailable`; no dropped timer or invented duration. |
| `ONE_BY_ONE` | Explicit `LegacyOnly`; never silently changed to all-at-once delivery. |
| Unknown mapping version, mode or selected preset/version | Explicit `Unsupported`; exact symbols/versions only. |

Assignment classification takes precedence over ordinary preset mapping. Within an ordinary
launch, the authorized preset reference/version is checked before delivery/timing limitations:
an unsupported version returns `Unsupported(UNSUPPORTED_PRESET)` even for `ONE_BY_ONE` or
`TIMED` with enabled/unknown quiz timing. A supported v1 keeps the mode outcomes listed above.

All outcomes are classifications, not authority to route to a retained engine. In particular,
`LegacyOnly` requires separate current eligibility checks before any future routing. No mapping
outcome starts an attempt, creates a form/snapshot, consumes an allowance, starts a clock or
releases feedback. Input and choice collections are defensively copied, duplicate policy-version
identities are rejected, and failure messages contain fixed reasons rather than input content.
Existing attempts and historical engine/policy evidence are never reinterpreted.

For example, an old client requesting `TIMED` with the quiz timer off and one authorized
`FREE_PRACTICE/v1` policy receives a compatible resolver input. Two ambiguous alternatives
produce a choice requirement. Required assignment timing remains outside this mapper and cannot
be removed by the disabled quiz flag. These are unit-level mapping outcomes, not HTTP-start proof.

The complete golden tuple and a fixed SHA-256 of a small, explicitly sorted **test-only** JSON
fixture guard version stability and serialization reconstruction. They define no production
canonical format/hash and never read document content. Additional tests cover mapping/version
boundaries, immutable captures, denied/ambiguous inputs and the absence of runtime wiring or
repository/provider/clock dependencies. No JPA read/write path is added, so N+1 does not apply.

Integration ownership remains #517 precedence/canonical bytes/hashes, #518 snapshot persistence,
#600 actual dark start, #833 public choice and current-access revalidation, #609 activation and
#668 formal/timed conformance. HTTP/API summary/OpenAPI contracts, current security rules,
legacy success/error responses, database schema, retry policy and frontend behavior are unchanged.

### Canonical practice-policy resolution (#517)

`AttemptPolicyResolver` is a dark internal application interface. It consumes trusted server facts
for an already selected ordinary `FREE_PRACTICE/v1` policy and returns an immutable
`PolicyResolution`. The implementation does not load identity, grants, revisions, assignments,
documents or accommodations, create attempts, persist snapshots, or activate an execution route.
The caller must establish current resource authority and revision existence and revalidate at
actual start. Prepared grant facts in unit tests are not integration proof of authorization.

The supported precedence is:

1. Fixed platform-safety invariants and the exact code-defined preset defaults.
2. Explicit immutable content metadata: quiz revision ID, revision schema and flat structure.
3. Authorized author choices within the supported typed allow-list.
4. Authorized participant choices within the exact values permitted by the preset/author.
5. Aggregate validation through the existing `ResolvedAttemptPolicy` constructor.

Only answer-option order (`AUTHORED`/`SHUFFLED_PER_ATTEMPT`) and authored hints
(`ON_REQUEST`/`DISABLED`) are override inputs in this increment, following A03/A04/R01. Each source
carries explicit allowed-value sets resolved by the server; an empty set grants no change.
For example, an author may disable hints and permit the participant only `DISABLED`; an attempted
`ON_REQUEST` choice then fails instead of silently restoring the preset default. Where both values
are explicitly permitted, a participant choice wins over the author choice. An explicitly selected
value receives that source even when it equals the preset default. Missing choices retain the
previous source/value. Input list/set iteration order never establishes precedence.

Identity, access, scoring, pass thresholds, submission/retry semantics, release, versions and
integrity have no generic override channel. The content metadata retains #597's typed structural
constraints. Per-question shuffle restrictions and exact option order remain #521's responsibility:
a global shuffled strategy does not authorize shuffling a protected question. Question-count,
question-order, managed-preset, assignment and accommodation extensions retain their own owners;
their unsupported values are not enabled by this resolver. No derived timing/arithmetic rule is
introduced for the untimed baseline.

Resolver, canonical format and source versions are exactly `1`; the hash algorithm is exactly
`SHA-256`. Unknown versions/algorithms, duplicate sources, duplicate settings and disallowed values
fail explicitly without a partial result or permissive fallback. Existing preset/version errors
retain their typed non-sensitive failures. Collections are defensively copied. The service has no
clock/random/provider/persistence dependency, so independent instances and concurrent invocations
produce the same result from the same facts.

Canonical format v1 is project-owned compact UTF-8 JSON, **not RFC 8785**. Its envelope contains
`resolverVersion`, `canonicalVersion`, `hashAlgorithm`, `preset`, `policy` and `provenance`.
Properties and map keys are sorted alphabetically, enum names are symbolic, UUIDs are lowercase,
and integers/booleans retain their JSON scalar types. Policy constructors normalize category
arrays; semantically ordered arrays are never generically sorted. The codec owns an isolated
mapper, independent of application JSON configuration. A material representation change requires
a new supported format/version and explicit golden evidence.

Provenance has one entry per effective-policy scalar leaf, using dotted paths and `[index]` array
positions. Each entry contains a closed source name and version. Content leaves originate from
`REVISION`; `schemaVersion` and integrity leaves from `PLATFORM_SAFETY`; remaining unchanged leaves
from `PRESET`; supplied choices from `AUTHOR` or `PARTICIPANT`. The lowercase hexadecimal hash
covers the complete envelope, including provenance. Equal values selected from different actual
sources can therefore have different hashes. The hash identifies resolution evidence; it is not
an authorization token, command identity or replacement for the captured policy. Only the bounded
policy structure is encoded/hashed; no document bytes or source text are read.

Independent frozen fixtures in `attempt-policy-resolution/` cover the baseline and a composition.
Tests cover every leaf's source, explicit equal-value choices, source/property/collection
permutations, semantic changes, forbidden choices with successful controls, unknown versions,
duplicate inputs, defensive copies, concurrent calls and reconstruction with a separate codec.
These are pure component/contract proofs, not database restart or actual-start integration.
No JPA read/write path changes, so N+1 is inapplicable.

Metrics `attempt.policy.resolutions` and `attempt.policy.resolution.sources` use only bounded
outcome/reason/source/version labels; they carry no policy values, IDs or sensitive rationale.
Telemetry failure cannot change a result or replace its original rejection. No new alert threshold
is selected for this dark component. #518 owns persisted snapshot verification, #600 actual start,
#666 any authorized preview and #609 activation. Existing HTTP/OpenAPI/security contracts and
legacy routes remain unchanged.

### Immutable resolved-policy snapshot storage (#518)

`PolicySnapshotService` is an internal application interface for already authorized server callers.
It stores #517's exact canonical UTF-8 representation, including effective values, preset reference
and per-leaf provenance, with a stable server-supplied snapshot UUID. `attempt_policy_snapshot`
also stores the snapshot/resolver/canonical versions, algorithm/hash and immutable quiz-revision
reference. The UUID is the replay identity; a hash alone is neither identity nor authorization.
Equal policies under different snapshot UUIDs are separate evidence, not globally deduplicated.

`createOrReplay` verifies the incoming evidence before writing. Its unique root key serializes
concurrent identical and conflicting requests, and no existing payload is overwritten. A current
locking read uses `FOR UPDATE OF s` to lock only the snapshot while retaining the revision-owner
scope check. Independent snapshot IDs referencing one revision do not upgrade their shared FK
locks on that revision. The read verifies the winner: exact replay returns the same evidence;
different evidence under that ID is a typed conflict. Both JDBC and JPA callers can include the
write in their transaction; a later outer rollback cancels a new snapshot. Ordinary reads retain
the caller's isolation semantics. In an older `REPEATABLE_READ` snapshot, use the returned replay
value rather than expecting an ordinary read to see another transaction's commit.

The trusted `revisionOwnerId` scope is resolved by the server and checked against the immutable
revision. It is not the current participant ID and grants no quiz/assignment/participant authority.
The actual caller must enforce those checks at its own boundary. Missing and foreign target IDs
share `SNAPSHOT_UNAVAILABLE`. `PolicySnapshotReference.Legacy.NO_SNAPSHOT` explicitly returns
legacy/no-snapshot without resolving settings or writing anything. A missing required target
snapshot is never automatically classified as legacy. Existing attempts receive no backfill or
new engine binding; #600 owns atomic start and #609 owns public activation.

Every stored read checks the exact supported versions/algorithm, revision binding, complete
structurally valid provenance, strict named JSON fields/types, exact canonical bytes and SHA-256.
Unknown versions, corrupt hashes, duplicate/unknown/missing JSON fields, coercion, trailing data,
changed canonical representation and impossible provenance fail closed. Verification never calls
the current preset service, re-resolves current grants/settings, or reads/hashes document bytes.
Hash verification detects corruption; it is not protection against an administrator replacing
both a payload and its digest. Application immutability comes from the write-only-once port and
the insert/no-op/compare transaction. There is no update/delete operation in that port.

V87 is additive, preserves the exact JSON string using text storage, validates version/hash/JSON
shape, indexes the immutable revision and restricts deletion of a referenced revision. There is
no FK to the legacy attempts table. Before any target writes, application rollback can leave this
empty table in place. After target writes, preserve retained evidence and use a forward fix rather
than dropping/rebuilding snapshots. Future erasure/lifecycle work retains its own decision gates.

`attempt.policy.snapshots` uses closed operation/outcome/version labels without payloads, hashes,
IDs or provenance values. `verified` means the service verified and returned evidence; it does not
claim that an enclosing transaction has committed. Telemetry failure cannot change the operation.
Database/JSON failures cross the service boundary as content-free typed errors without raw causes.

Focused tests use real MySQL transactions for migration, identical/different concurrent writes,
rollback after actual INSERT, commit acknowledgement loss/reconciliation, recreated-service reads,
JPA transaction participation, corruption and scoped isolation. Frozen #517 fixtures verify byte
compatibility independently of the resolver. Each single-snapshot read uses one joined metadata
query across a multi-revision fixture; no JPA relationship-loading path is introduced. These tests
prove the store, not an activated participant start, assignment, accommodation or offline package.

## Example Policy: Approved Free-Practice Baseline

This is a conceptual excerpt of the approved target in [#612](https://github.com/Gegcuk/QuizMaker/issues/612) and [#687](https://github.com/Gegcuk/QuizMaker/issues/687), not a complete serialized DTO or a claim of runtime availability. The complete typed schema also represents disabled/not-configured dimensions explicitly.

This is the default training context for a full-rights user. A take-only participant receives these settings only when the creator's applicable authorized policy supplies them; this example is not a universal participant fallback.

```yaml
schemaVersion: 1
preset: FREE_PRACTICE/v1

content:
  revisionId: quiz-revision-42
  formMode: PRECOMPUTED

questionSelection:
  strategy: ALL

questionOrdering:
  strategy: AUTHORED

answerOrdering:
  strategy: AUTHORED

navigation:
  mode: FREE

delivery:
  mode: ALL_AT_ONCE

submission:
  completionConfirmationRequired: true

answerChange:
  allowedUntil: NEVER_AFTER_SUBMISSION

disclosure:
  hint: ON_REQUEST
  correctness: AFTER_EACH_SUBMISSION
  questionScore: AFTER_EACH_SUBMISSION
  correctAnswer: AFTER_EACH_SUBMISSION
  explanation: AFTER_EACH_SUBMISSION
  totalScore: AFTER_ATTEMPT

timing:
  mode: NONE

pause:
  mode: DISABLED

grading:
  strategy: BINARY

review:
  availability: AFTER_ATTEMPT
  content:
    - SUBMITTED_ANSWERS
    - CORRECTNESS
    - CORRECT_ANSWERS
    - EXPLANATIONS
```

An accepted answer is locked before protected feedback becomes available. There are no within-attempt answer retries. Before start, permitted option shuffling is computed by the server and its exact stable-ID order is persisted; clients never reshuffle an active form. Final submit carries no answer payload. Confirmed unanswered items receive no credit without becoming accepted incorrect answers. The [initial personal-practice retention decision](./decision-register.md#initial-practice-history-retention) keeps detail and result evidence without age-based cleanup until retention is explicitly revisited. The separate [account-deletion decision](./decision-register.md#account-deletion-history-scope) deletes the user's personal training history while preserving non-training history from that operation; other record classes remain separately scoped.

---

## Example Policy: Approved Formal Conformance Fixture

[#668 D38](https://github.com/Gegcuk/QuizMaker/issues/668) defines this exact target fixture. It is a conformance requirement before formal activation, not a second initially active preset. The excerpt omits other typed dimensions and authentication fields rather than defining a complete wire schema.

```yaml
schemaVersion: 1

content:
  revisionId: formal-fixture-revision
  formMode: PRECOMPUTED
  fixtureBankSize: 40

questionSelection:
  strategy: RANDOM_COUNT
  totalCount: 20
  fixtureComposition:
    MCQ_SINGLE: 8
    MCQ_MULTI: 4
    TRUE_FALSE: 4
    FILL_GAP: 2
    ORDERING: 2

delivery:
  mode: ONE_AT_A_TIME

navigation:
  mode: SEQUENTIAL
  allowSkip: false
  allowBacktrack: false
  requireAnswerBeforeNext: true

answerChange:
  allowedUntil: NEVER_AFTER_SUBMISSION

disclosure:
  totalScore: AFTER_DEADLINE
  passFail: AFTER_DEADLINE
  correctness: NEVER
  questionScore: NEVER
  correctAnswer: NEVER
  explanation: NEVER

timing:
  mode: TOTAL_ATTEMPT
  duration: PT30M
  gracePeriod: PT0S
  effectiveExpiry: EARLIEST_APPLICABLE_DEADLINE

pause:
  mode: DISABLED

attempts:
  limit: FIXED_COUNT
  count: 1
  activeLimit: 1

grading:
  strategy: BINARY
  pointsPerItem: 1
  passThresholdBasisPoints: 7000

review:
  availability: NEVER

access:
  audience: AUTHENTICATED_ROSTER
```

The versioned deterministic fixture selects and persists the expected twenty-item form from forty immutable items. The family counts above describe that fixture's expected output, not additional runtime stratification. Passing requires 14/20; expiry is the earlier of assignment close and start plus 30 minutes. Final submit contains no answer payload. Guests, sections, accommodations, manual grading, Offline, Adaptive, Branching, and Live are excluded from this baseline.

---

## Attempt Creation

Starting an attempt is a single server-owned operation.

Before applying start limits or composing the snapshot, resolve the authorized launch context described above: full-rights default training or a creator-policy-bound run. When several ordinary-launch policies are authorized and selection is ambiguous, return the [choice-required outcome](./decision-register.md#ambiguous-ordinary-policy-selection) before creating an attempt or consuming its allowance. After a valid choice, revalidate start eligibility and persist that context's policy/version and effective settings atomically with the attempt. Missing or ambiguous access cannot be replaced with a more permissive practice policy.

A recommended transaction flow is:

1. resolve the entry resource;
2. resolve participant identity or exact capability;
3. check assignment visibility and eligibility;
4. verify opening time, deadline, revocation, capacity, and attempt limits;
5. load the immutable quiz revision;
6. capture the exact preset and assignment versions;
7. apply permitted accommodation overlays;
8. apply only authorized overrides;
9. validate the complete configuration;
10. persist the resolved policy snapshot;
11. create the participant subject binding;
12. create the attempt;
13. construct and persist the fixed form, or initialise a progressive form;
14. persist initial execution and lifecycle state;
15. append `AttemptStarted` evidence;
16. commit;
17. return the participant-safe representation.

If any required stage fails:

- no participant-visible attempt exists;
- no partial form is published;
- no attempt limit is consumed;
- no orphan snapshot remains;
- the failure is observable without exposing sensitive data.

Attempt creation must support canonical idempotency.

Concurrent start requests with the same semantic operation must not create duplicate attempts or consume multiple allowed attempts.

### Dark atomic authenticated ALL start (#600)

`AttemptStartService` composes the existing publication, contract, policy, form, subject,
lifecycle, receipt and trace boundaries in one database transaction. It has no HTTP
controller or route activation. Existing `AttemptService` routes remain authoritative.

The command names a quiz, exact published revision/publication version, preset, launch
context, legacy mode mapping, answer ordering and canonical command key. It accepts no
user/owner/subject ID, supplied allowed actions, form items or answer keys. Authentication
uses #536's supported server principal. `QuizPracticeSourceService` locks active-user,
effective permissions, quiz and publication metadata through commit. The owner qualifies
for the approved practice default. A non-owner qualifies only with the existing
`QUESTION_UPDATE` gate plus `QUESTION_ADMIN` or `QUIZ_MODERATE`, which confer full
question/answer editing in the current feature. Role names, `QUIZ_UPDATE`, `QUIZ_ADMIN`
alone and take-only access do not confer that authority. There is no organization-bound
launch in this slice; assignment/organization integration remains #534. Creator-authorized
take-only policy selection requires its real policy source and #833 integration; no
client-supplied grant or implicit public-quiz practice grant is introduced here.

A fresh start requires the current publication reference and the real, already bound
#701 contracts. It declares the complete #598 revision scope, resolves `FREE_PRACTICE/v1`,
persists its snapshot, materializes ALL items in authored order, binds the subject,
applies the #523/#527 start decision and persists `IN_PROGRESS/NOT_STARTED`. `FORM_CREATED`
and `ATTEMPT_STARTED` trace events and the #599 receipt commit with these writes.
The initial form uses authored option order or the approved participant-selected
`SHUFFLED_PER_ATTEMPT` order under the existing question constraints. Only the flat,
untimed mapping is supported. Legacy `TIMED` with an explicitly disabled timer retains
the approved untimed mapping. Legacy `TIMED` with an enabled or unknown timer,
assignments, unknown modes and unsupported presets cannot trigger a mutable-content
or legacy fallback.

The acknowledgement contains only attempt/form/revision references, the original state
version and replay classification. Identical retries, including after restart or lost
commit acknowledgement, use the persisted receipt. Reusing a key for changed input
conflicts. A later publication makes an old reference stale for a **fresh** command,
but an authorized replay still returns its original acknowledgement. Current account
and resource authority is checked before every receipt lookup. The command fingerprint
covers bounded request metadata, not the full document/question content. Different keys
remain independent under the existing unlimited practice policy; no new quota or retry
policy is introduced. Offline starts are unsupported: after reconnecting, the caller
reconciles with the authoritative server using the same semantic command identity.

All component writes join #599's REQUIRED transaction. A returned value is provisional
when the caller owns an outer transaction. A reached write failure rolls back all new
start evidence; existing publications/bindings remain intact. Unknown commit outcomes
retain that classification and require the same request, without automatic re-execution
or switching engines. Typed internal errors expose no rejected content or underlying
database diagnostics. The `quizmaker.attempt.start` metric uses only fixed version and
outcome labels; `start_returned` deliberately does not claim an outer transaction committed.

Outer `READ_COMMITTED` and `REPEATABLE_READ` transactions remain supported even when
their snapshot predates the requested publication. Current reads cover revision roots,
items, question/option contents, contract membership and snapshot/form write checks.
Contract scope takes canonical question locks before shared aggregate reads, so two
independent starts do not need to upgrade shared question locks. The ordinary revision
read API retains its snapshot semantics; write composition uses `findByIdForShare`.
SQL conflicts are classified at the first diagnostic-sanitizing service boundary,
including identity, question contracts, snapshots, forms, option order, subjects,
lifecycle and trace. The internal `DatabaseConflictException` carries only a fixed
kind, with no driver cause, suppressed exceptions or SQL message. Start/receipt maps
MySQL `1213` to `RETRYABLE` and `1205` to `IN_PROGRESS`; ordinary storage failures keep
their existing unavailable reason. Neither classification introduces an automatic retry
or overrides commit uncertainty.

`AttemptStartMySqlIntegrationTest` composes real publication, five-family bindings and
start services, with external storage replaced at its port. It covers successful
AUTHORED/shuffled starts, fresh-transaction rollback, concurrent identical/conflicting
requests, receipt recovery, current-access denial and bounded relationship reads.
Regression cases publish after an outer snapshot, exercise outer rollback after successful
composition, and force actual authorization and nested question-lock deadlock/timeout
errors under both supported isolation levels before an explicit retry. Separate fault
injection at each SQL dependency boundary verifies safe reason propagation, rollback and
same-key recovery; it does not replace the real InnoDB conflict scenarios. Receipt tests
also preserve `UNKNOWN_OUTCOME` for completion failures carrying a sanitized conflict.
`AttemptStartValidationTest` checks malformed input without protected lookup or writes.
Public delivery, answer acceptance, feedback/release and API activation remain their
separate integration outcomes; this service does not claim those flows are enabled.

---

## Immutable Quiz Revisions

A mutable quiz is an authoring workspace.

A quiz revision is immutable delivery content.

A revision contains or references immutable versions of:

- question text;
- stimulus material;
- options;
- stable option IDs;
- response schema;
- correct-answer interpretation;
- explanation;
- hints;
- scoring contract;
- topic and difficulty metadata;
- authored order;
- section membership;
- case-block membership;
- shuffle restrictions;
- rubric;
- branching graph where applicable.

Editing a quiz after publication creates another revision.

It must not modify:

- existing assignments;
- active attempts;
- completed attempts;
- historical scoring;
- review content;
- learning sessions already started from the previous revision.

A physical deep clone and a strictly immutable versioned representation may be implementation alternatives, but their external semantics must be identical.

[#612 D04/D05](https://github.com/Gegcuk/QuizMaker/issues/612) selects explicit authorized publication and forms that retain revision references plus exact item/option order. An ordinary draft save does not publish. External media whose immutability and retention cannot be guaranteed needs retained snapshot/reference evidence; storing a URL alone does not prove that guarantee.

The [22 October initial image-publication decision](./decision-register.md#initial-stored-image-publication) requires an image already saved in platform-controlled storage, uploaded by the author or produced through an existing managed pipeline. Automatic external-URL import is deferred. An external-only image reference requires the stored image to be supplied before new publication; an already managed, authorized image can be reused without another upload. Publication binds the exact retained image version, not a mutable object key or transient delivery URL. Validate existence/readiness and applicable use authority server-side, and coordinate publication with removal/replacement so it cannot claim a complete binding to unusable evidence.

The first-stage decision preserves existing quiz APIs, active/historical attempts and the approved legacy path until compatible activation. It neither reconstructs past images from today's remote content nor expands #597/#818 or introduces a new fetching policy.

The [22 October ordinary-removal decision](./decision-register.md#referenced-image-author-removal) removes an image from the author's available materials while retaining the exact image version and bindings required by existing revisions and attempts. Do not refuse list removal merely because those references exist. Removal does not unpublish the quiz, change its original image/regions, revoke ordinary access or block permitted new attempts on the unchanged published revision. Existing reads remain subject to normal authority and release checks; the removed image is no longer offered for new selection. This action must not be presented as physical erasure of referenced bytes.

For a completed attempt with intact immutable form, image bindings, answers and result, the [22 October review decision](./decision-register.md#completed-review-unavailable-image) shows permitted available content with an explicit marker when an image is temporarily unavailable. Do not fail the whole review solely for that image or present it as fully available. Original references and grades remain unchanged; no substitute image, partial authoritative form or new snapshot is created. Missing/corrupt form evidence and uncertain authorization still follow their existing failure/denial contracts.

[Q36](./decision-register.md#preparation-batch-approved) separately covers completed review after an authorized exceptional display restriction: hide the prohibited part with an explicit marker, retaining only the independently permitted remainder. [Q57](./decision-register.md#exceptional-material-display-authority) requires a platform operator with a dedicated scoped permission and a recorded reason to impose that restriction, including an operator of an isolated installation. Teacher or organization-administrator status alone is insufficient. Enforce the prohibition on affected representations/delivery paths without rewriting immutable revisions; do not automatically alter grades/certificates. [Q63/Q66](./decision-register.md#exceptional-material-and-backup-lifecycle) limit initial grounds to confirmed disclosure of others' personal data, secrets/closed information or absence of permission to use material, and allow verified, recorded lifting by an authorized operator, including the original operator. Ordinary instructional errors/obsolescence use new revisions and applicable correction rules. Physical erasure requires Q64's separate authority/safeguards and an admissible request; Q65 governs authorized backup erasure. Active-attempt integrity/cancellation and ordinary author removal retain their own contracts.

[Q69–Q71](./decision-register.md#historical-original-erasure-approved) permit exceptional physical erasure of historical originals for confirmed grounds, require explicit resolution of preservation conflicts and set the working-storage deletion target. Storage lifetime, pre-existing draft/copy/republication references, restriction/lifting/erasure execution, case verification, unavailable-content handling and delivery ownership remain [implementation/coverage gates](./open-questions.md#oq-media). The completed-review decision selects no active-attempt behavior, timer pause, compensation, retry schedule or permanent-loss recovery policy.

The [approved Q01/Q02/Q11 batch](./decision-register.md#quiz-certificate-batch-approved) extends ordinary-removal survival to existing authorized draft bindings, while new selection/copy still needs an available authorized asset. Initially no automatic physical cleanup of ordinarily removed unreferenced images is enabled. During an active attempt, temporary image unavailability is explicit and does not automatically alter clocks; an organizer with the relevant scoped authority may compensate a verified fault through an eligible extension or a separate new-attempt allowance. Absolute assignment close and immutable finalized evidence remain protected. Permanent loss/corruption, exceptional erasure and storage-lifecycle evidence retain their separate gates.

### Retained saved-image boundary (#830)

Question creation/update prepares platform-owned images outside the authoring transaction,
then writes `saved_image_bindings` in the same transaction as the question. Each asset has
one `saved_image_versions` identity; replacing an image requires a new asset, not a metadata
edit. The identity includes an independently retained object key, trusted source ETag,
MIME type, byte size and dimensions. A media row's optimistic-lock version and CDN URL
are not image-generation identities. Client-provided delivery URLs are stripped from
question content and cannot choose the retained object.

Preparation first commits a durable pending identity. The storage port conditionally copies
the captured source generation to its server-owned `retained-images/` key and verifies
destination identity/metadata. That key is never offered as a client upload target.
After response loss or restart, the same identity is reconciled before another copy is
attempted. An unknown destination outcome or changed source fails closed; it never
silently substitutes newer source bytes. Storage calls run outside SQL transactions;
the subsequent transaction rechecks current ownership/library state under an asset lock.
These separate transactions discard any OSIV read cache before loading authoritative state;
a request-scoped persistence context must not reuse ownership from before the storage wait.
Existing caller-owned transactions are preserved, and preparation rejects provider work inside them.
Question and binding writes roll back together; a prepared but unbound object is internal
evidence, not a saved draft or published revision. No automatic physical cleanup is enabled.

`DELETE /api/v1/media/{assetId}` ordinarily removes a READY image from selection using
`library_removed`, without deleting either source or retained bytes. Existing question
bindings and question/article reads survive; repeated removal is idempotent. New question
references and article hero/inline selections reject removed images, including an old
reference that was detached and subsequently re-added. Existing question validation
continues to use HTTP 400 for invalid media; preparation conflicts/unavailability use
409/503 `ProblemDetail` and leave the question unchanged. Document deletion keeps its
existing behavior.

`POST /api/v1/media/{assetId}/restore` requires `MEDIA_DELETE` plus current ownership or
the existing media/article/system administrator authority. It verifies the exact retained
object outside SQL, rechecks authority in the write transaction, and returns the existing
`MediaAssetResponse` shape. Identical retries are idempotent. Expected errors are 401/403,
404 for an unavailable restorable identity, 409 for a conflicting identity, and 503 for
storage unavailability; a failed restore leaves library state unchanged. Library reads
and restored responses return the retained generation's key, URL and image metadata,
even if mutable source metadata now describes something else. The endpoint belongs to
the `media` OpenAPI group linked from `/api/v1/api-summary`.

`RetainedImageService.acquireForPublication` is an internal consumer contract. Its caller
must recheck current resource/publication authority and the current referenced assets in
the supplied transactional authorization callback. An asset ID or historical evidence row
alone grants no copy/publication authority. Acquisition returns existing binding/version
identities and exact object metadata, including after ordinary removal; it creates no
revision and performs no provider I/O. The #700 service below owns the real revision/current-pointer
transaction, complete publication validation and combined publication/removal conformance.
This boundary does not activate target execution or change grading/feedback policy.

Apply additive migration V92 before running this application version. Storage credentials
must support HEAD, conditional CopyObject and the existing public-image ACL; the retained
prefix must not be subject to an automatic object-expiration rule. Adapter tests use a fake
provider and do not certify deployment bucket permissions/lifecycle configuration. V92 defaults existing
assets to available and creates no invented historical generations or bindings. Legacy
unbound question/article reads retain their existing projection until a prospective version
is prepared. A new save can establish a binding while the image remains selectable. An unchanged legacy
reference can also adopt an already retained version after ordinary removal, using the current
reference read from the freshly locked question. This creates a prospective binding, not a
reconstruction of past image generations. A detached or newly selected reference still requires
an available library asset. Removal can preserve current legacy bytes but does not prove past
publication identity; acquisition rejects an unbound reference until that authorized save.
Quiz import treats attachment and nested media asset IDs in the external payload as new selections,
including upserts; exported IDs do not carry retained-use authority. Its per-quiz transaction checks
current ownership/administrator rights, READY image metadata and library availability using ordered,
batched locking projections. These scalar reads bypass stale managed media entities, and locks remain
held through question persistence and commit. Dry runs perform the same checks without writes or
storage calls; skipped duplicates keep their existing no-write behavior. A failed item rolls back its
quiz/questions while other valid items may succeed through the existing import summary contract.
An authorized import committed before removal remains editable through the legacy-reference transition;
a new import after removal cannot manufacture that prior-reference evidence. Import does not claim
that an unbound reference is an immutable published generation; publication acquisition still requires
a binding established by the authorized question writer.

Question writes refresh the locked question even in a caller-owned transaction, without clearing
unrelated pending changes. Removal likewise refreshes asset state under its lock and routes an
image finalized since the initial read through retained removal rather than marking it deleted.
Preserve the new tables
and retained objects on rollback; an older application must not resume its image-deletion
path against this evidence. Exceptional restrictions/erasure remain separately owned.

Automated evidence uses the real question/article writers, current permissions, complete
Flyway schema and MySQL transactions with a generation-aware fake only at storage.
It covers generation A versus replacement B, restore authority/outages, response loss and
service restart, concurrent removal/acquisition, and failures after real binding writes
followed by fresh rollback reads. An OSIV regression revokes a moderator permission while storage is
blocked and proves the former moderator cannot commit a question using cached authority. MVC tests use the real security configuration and permission
aspect, verify RFC 7807 errors, named restore schema/examples, grouping and discovery.
Multi-parent question/library projections compare five and ten rows; article list/detail
tests measure bounded SQL through serialization. Pure adapter tests cover conditional copy,
unknown outcomes and transaction guards without calling a real provider.
Metrics `quizmaker.media.retained` distinguish pending preparation, committed and failed
operations; `quizmaker.media.retained.duration` records bounded operation/outcome latency.
Resource identifiers, URLs, source content and provider diagnostics are not metric labels.

### Internal immutable publication boundary (#700)

`QuizRevisionPublicationService` is an internal authoring boundary. It is not wired to HTTP,
legacy save/status routes or participant start. Ordinary edits continue to change drafts;
an explicit `publish` command snapshots the current locked draft, ordered questions, options
and exact retained image bindings. The command supplies its version (`1`), quiz ID, stable
command ID, expected publication counter (`0` for unpublished) and the complete unique
question order. The legacy question `Set` is not an order authority. No existing quiz is
backfilled or activated, and existing attempts/forms retain their previous identities.

Publication resolves the authenticated, active account and current database permissions,
then checks ownership or existing quiz moderation/administration authority. Ordinary owners
need `QUIZ_UPDATE`; PUBLIC content additionally requires moderation/administration authority.
Caller-supplied owner IDs and cached OSIV roles are not authority. Current/history/content reads
use the same author ownership/permission boundary; they are not participant read projections.
Unauthorized, deleted or foreign resources produce the same bounded `UNAVAILABLE` reason.

A target quiz association is not sufficient source authority. `questions.source_owner_id`
records the resolved author when the real question, import or generation writer creates a new
question. It is not supplied by public DTOs, inferred from memberships, or replaced on edits,
import upserts, association changes or account deletion. V94 makes a known source owner immutable
at the database boundary using `question_source_authorities` and a composite foreign key with
`ON UPDATE RESTRICT`. A new question and its authority row commit together; confirmation writes
the owner, authority row and audit evidence atomically. Source checks require the matching
authority row, so an unsealed owner value alone grants nothing. This works with schema-scoped
migration privileges and binary logging enabled; no trigger, `SUPER`, or trusted-routine setting
is required. Physical authoring-question deletion cascades to its authority row, preserving prior
deletion behavior; confirmation audit evidence and immutable published content remain independent.
Association and publication require this recorded owner or the existing
`QUIZ_MODERATE`/`QUIZ_ADMIN`/`QUESTION_ADMIN` authority, independently of destination permission.
`QUESTION_ADMIN` alone never grants destination access. Public readability is not a reuse grant.
Current source-administration permissions are read with locked scalar SQL, without trusting
cached roles or loading one permissions collection per role.

V94 deliberately leaves legacy source owners unknown. An unknown source cannot be newly linked
or published, including by an administrator, until its provenance is explicitly confirmed.
`QuestionSourceConfirmationService.confirm(questionIds, verifiedOwnerId, evidence)` is an internal
moderation operation with no HTTP route. After verifying original ownership, a currently active
`QUIZ_MODERATE`, `QUIZ_ADMIN` or `QUESTION_ADMIN` actor can confirm up to 100 explicit question IDs
for an active owner. The transaction records owner, moderator, injected-clock time and bounded
evidence in `question_source_confirmations`. It does not infer ownership from the sole remaining
quiz or an image ID. A repeated confirmation for the same owner is a no-op preserving the first
evidence; a different established owner, missing/deleted question or inactive owner denies the
whole batch. Failure rolls back both ownership and evidence. Legacy authoring/reads and existing
published revisions remain available; confirm known legacy ownership before enabling new
publication or reusing those questions. Existing authorized retained images, including removed
library images, remain reusable after confirmation without selecting or copying them again.

Publication reads source authority from the already locked question rows in bounded batches.
The lock sequence is target quiz, target memberships, ordered questions, then retained/revision
metadata; source checking never locks a different quiz or its memberships. Two quizzes sharing
questions can therefore publish without the former target-quiz/shared-question/source-quiz cycle.
Removing or soft-deleting the original quiz cannot grant a stale foreign association authority.
The association endpoint retains its 204 success and RFC 7807 403 denial contract.

One service-owned transaction locks the quiz, membership and question rows, validates content
through the existing authoring handlers, acquires saved-image metadata in batches, and writes
the immutable aggregate, publication metadata, pointer and receipt atomically. Storage calls
remain in the earlier #830 preparation boundary. A retained draft binding remains publishable
after ordinary library removal; an unbound/foreign asset or external-only image fails closed.
Saved-image IDs, exact generations and object metadata are persisted; mutable source/CDN values
cannot replace the retained generation. Publication does not hash document bytes or fetch URLs.
The publication transaction explicitly uses `READ_COMMITTED`. Under MySQL's default
`REPEATABLE_READ`, a receipt lookup before a concurrent question save could otherwise pin an
older binding snapshot while the subsequent locking question read sees the newly committed
image. The explicit isolation keeps those reads consistent; connection defaults and caller-owned
transactions remain unchanged. The service still rejects publication inside a caller transaction.

Canonical format `1` preserves array order and numeric precision, sorts object keys, and omits
generated revision IDs/timestamps from the content hash. Identical content reuses its immutable
revision. A changed publication requires the expected counter to match; two different commands
from the same current version have one winner and one `STALE_PUBLICATION`. Identical concurrent
content converges successfully, while a future expected counter is rejected. Returning to an
older identical revision still advances the counter, preventing an ABA overwrite. Reusing a
command ID with different command fields produces `COMMAND_CONFLICT`. Replaying the original
command returns its durable acknowledgement without rereading edited content or moving the
current pointer backwards. After `UNKNOWN_OUTCOME`, retry the exact command/ID to reconcile;
do not assume rollback or construct a new command. No automatic retry schedule is introduced.
Unsupported command versions and malformed inputs fail before writes. SQL failures inside the
transaction return `PERSISTENCE_UNAVAILABLE`; commit/acknowledgement uncertainty returns
`UNKNOWN_OUTCOME`. Offline mutations are unsupported: reconnect to read authoritative state
and reconcile the same command. A legacy quiz without a head remains unpublished at this
boundary even if its old mutable status says `PUBLISHED`; that status is not revision evidence.

History returns a typed page (size 1–100) of unique immutable revisions ordered by publication
time and revision ID; `current` returns its separate monotonic counter. Full content reads use
the owner-scoped #597 aggregate and exact images. Authored option source keys are copied from
the family schema; matching sides use `left/` and `right/`, and legacy fill-gap strings use
`options/<index>`. Each immutable option receives an independent UUID. These keys do not select
a target response/scoring adapter: #701 owns those bindings and #600 owns dark start. Participant
delivery, exam/review and HOTSPOT activation still require their real downstream integrations.

Apply additive V93 after V92. It creates publication metadata, heads, receipts and image links,
with unique content identities and same-quiz/composite image foreign keys. Keep these tables,
receipts and retained objects on application rollback; do not delete historical evidence.
There is no new cleanup/retention policy. `quiz.publication` records operation/version/outcome
duration without resource IDs or content in labels; telemetry failure cannot undo a commit.

`QuizPublicationMySqlIntegrationTest` composes real authoring, retention, publication, permission
and fixed-form boundaries against full Flyway MySQL. It covers concurrent publication/removal,
generation A survival after source B, current permission revocation, changed-draft replay,
faults after real aggregate/pointer/receipt writes, acknowledgement loss and service restart.
Query counts compare five and ten questions with distinct images through serialized reads and
publication, plus history page sizes. This proves the internal foundation; it does not claim
participant HTTP, exam or review integration from a prepared form fixture.

### Immutable identity storage boundary (#597)

`QuizRevisionRepository` stores complete immutable content aggregates in `quiz_revisions`,
`question_revisions`, `quiz_revision_items`, and `question_option_revisions`. Its supported
operations are insert and owner-scoped reads; it exposes no update or delete operation.
Revision IDs identify immutable versions. Item and option IDs remain stable independently
of authored/display positions and are scoped by their containing revision. A new revision
can therefore reorder the same logical items/options without rewriting the old revision.

The repository accepts already authorized, server-owned publication input. Its owner argument
is an additional persistence scope, not a substitute for principal/permission/visibility checks.
Publication authorization, idempotency and the current pointer belong to #700. Response/scoring
contract versions and family-specific option-key interpretation belong to #598/#701. Question
content is validated as a JSON object and stored as the accepted canonical string in UTF-8
`LONGTEXT`. Object keys are sorted, array order is preserved, integers retain arbitrary precision,
and decimal numbers use Jackson `BigDecimal` nodes without stripping trailing zeros. MySQL binary
JSON is not used: it can round large integers and decimals. JSON validation stays at the immutable
Java boundary because MySQL JSON validation functions also reject valid numbers outside the
`DOUBLE` range. Malformed/non-object stored text fails closed on read. Option source keys use the
case-sensitive, `NO PAD` binary collation `utf8mb4_0900_bin`, so `a`, `A`, and `a ` are distinct;
exact duplicates are rejected both in Java and by the unique index. This storage boundary does
not reinterpret grading rules or hash documents.

An insert is one transaction across the complete aggregate. Existing question-revision IDs may
be reused only with exactly matching immutable content and metadata. A conflicting identity,
constraint error or concurrent conflicting insert aborts the transaction; no partial graph or
silent overwrite is accepted. Publication retry/idempotency remains the responsibility of #700.
Reads perform three SQL queries per batch of at most 500 requested revision IDs, independently
of the number of questions/options. Missing or wrong-owner revisions return no result; invalid
stored versions/content fail with a bounded integrity error and never fall back to mutable rows.

The additive migration does not backfill or rebind current attempts. `RevisionReference` classifies
missing identity as `LEGACY`; a non-null reference still requires successful repository resolution.
Historical content is not reconstructed from the current draft. Revision tables have no foreign
keys or cascades to mutable authoring/user records, so deleting a draft cannot silently remove or
rewrite retained revision content. Retention/erasure remains owned by the governance programme;
this port adds no retention duration or public history endpoint.

Current controllers, training feedback, scoring and legacy attempt reads retain their existing
behavior. A saved revision is not publicly activated by this foundation alone. Application rollback
before new writes leaves empty additive tables; after revision writes, preserve them and use a
forward fix rather than dropping history or editing an applied migration.

V82 upgrades V81 in place without rewriting its migration checksum. Conversion from binary JSON
retains the representation actually stored in each existing row; it cannot recover digits already
rounded by MySQL or Jackson. Historical snapshots are never reconstructed from mutable drafts.
Existing stored snapshots remain readable/reusable. An original pre-write object that differs from
an already rounded snapshot still conflicts under strict immutable equality; the migration does not
invent lost content or weaken identity checks. New writes retain the exact accepted snapshot.
The column/index alterations can rebuild revision storage and take metadata locks; migration
duration depends on retained history size. Apply V82 before accepting new writes with this fix.

---

## Question and Response Contract Versioning

Every form item references an immutable question revision and response contract.

Existing Fill Gap content without distractor options remains valid; option-aware Fill Gap behaviour is additive. [#701](https://github.com/Gegcuk/QuizMaker/issues/701) owns old/new payload compatibility and golden family fixtures rather than reinterpreting historical answers.

The owner-approved [all-nine-family compatibility decision](./decision-register.md#all-nine-question-families) requires support for all nine current families before fully switching training to target execution. #701 remains the five-family baseline; separate follow-ups must cover OPEN, MATCHING, HOTSPOT and COMPLIANCE with existing response and grading behavior. The formal D38 fixture is unchanged. This is an activation requirement, not evidence that the additional adapters already exist. [Legacy-path retention and operator-controlled fallback](./decision-register.md#legacy-compatibility-and-retained-fallback) are decided separately; their compatibility, coordination and readiness evidence remain activation gates.

Response validation and grading must not depend on the current Java implementation alone.

Examples of response contracts include:

- single option ID;
- multiple option IDs;
- ordered option IDs;
- free text;
- normalized fill-gap values;
- matrix responses;
- matching pairs;
- hotspot coordinates;
- confidence value;
- uploaded artefact reference.

A contract must carry a stable type and version.

Example:

```text
questionType: MULTIPLE_CHOICE_SINGLE
responseContractVersion: 2
gradingAlgorithmVersion: binary-option-id/v1
```

Display order must never affect scoring.

Correct answers use stable domain IDs rather than:

- array indexes;
- letters such as A, B, or C;
- text equality against the displayed option.

---

### Versioned registry and immutable bindings (#598)

`QuestionRevisionContractService` binds the complete distinct question-revision set of an
immutable quiz revision to exact response/scoring `(id, version)` pairs. Its caller must
already have authorized the operation and resolved the owner from trusted server context;
the persistence owner filter is additional protection, not permission or visibility authority.
This internal boundary adds no public endpoint or permission and does not route existing attempts.

V84 adds `question_revision_contract_bindings`, keyed by question-revision ID with a foreign
key to the immutable question snapshot. One question revision has one binding even when reused
in several quiz revisions. Supported ports expose no replacement/deletion. An identical bind
is idempotent; a different pair conflicts. A new pair needs a new question revision.
This is an invariant of the supported write boundary, not an independent database prohibition
on privileged SQL UPDATE/DELETE. The metadata digest detects inconsistency, not an administrative
rewrite that also recomputes the digest; V84 adds no triggers or new database access policy.
Existing rows are not backfilled: absent bindings fail with `UNBOUND_REVISION`, rather than
inferring a contract from the current question type, `QuestionContracts.VERSION`, or Java handler.
Missing/wrong-owner quiz revisions have the same `REVISION_UNAVAILABLE` failure.

`QuestionContractRegistry` resolves both exact references before returning a pair of project-owned
response/scoring ports. Duplicate registrations fail at startup. Unknown or unavailable versions
fail with `UNSUPPORTED_CONTRACT`; there is no latest-version alias or fallback. The registry has
no built-in family implementations in this foundation. #701 and #826–#829 supply the separately
owned adapters; existing validation, grading, feedback and web/iOS API shapes remain unchanged.
`ContractScore` carries exact decimal earned/possible points only; it defines no normalization,
pass threshold, stored result, release rule or concrete family algorithm.

Binding format v1 is fixed-order JSON encoded in UTF-8: `bindingFormatVersion`,
`questionRevisionId`, `response` (`id`, `version`), then `scoring` (`id`, `version`).
Identifiers use bounded lowercase ASCII names, positive integer versions and no class-name lookup.
SHA-256 covers only this bounded metadata; the saved digest is checked on read. No document,
question payload, media or source text is loaded/hashed to resolve or compare versions.
An external transaction must declare its scope before any bind or resolve. Use
`declareScope(ownerId, quizRevisionIds)` (READ) for resolution, or the overload with
`ContractScopeIntent.WRITE` when any binding will occur later. The declaration covers the
entire outer transaction. The service checks ownership of
**all** declared quiz revisions, reads their immutable membership in bulk and computes the union
of question-revision IDs itself. It reserves existing question identity rows, including unbound
questions, in canonical BINARY(16) primary-key order. READ uses FOR SHARE even inside a
read-write transaction; WRITE uses FOR UPDATE and also permits resolution. This intent is
independent of Spring's read-only flag. Disjoint question sets remain parallel and overlapping
READ scopes can proceed together. Lock queries select IDs only, without payloads/options.
Standalone bind/resolve creates its own transaction with a singleton WRITE/READ scope respectively.
Declaring a scope without an active external transaction fails with `TRANSACTION_REQUIRED`;
using an external transaction without a declaration fails with `SCOPE_REQUIRED`.

The first declaration freezes the owner, quiz-revision set and lock intent until completion.
Repeated declarations of the same set or a subset are idempotent and do not shrink the scope.
READ cannot upgrade to WRITE, and even an identical bind in a READ scope fails before binding
SQL. A repeated READ declaration inside WRITE does not downgrade the original reservation.
Every later bind/resolve must name a declared quiz revision, even when another undeclared revision
contains the same question union. An attempted expansion or READ-to-WRITE upgrade fails with `SCOPE_VIOLATION` and marks
the whole transaction rollback-only, including when the caller catches the exception. The scope
also retains a terminal violation flag outside JDBC savepoint state. Its `beforeCommit` callback
rejects the outer commit with `SCOPE_VIOLATION` even if a manual or NESTED savepoint rollback cleared
Spring's rollback-only marker. Ordinary savepoint rollback without a scope violation remains valid.
REQUIRES_NEW owns an independent scope/intent/flag and restores the suspended outer state on return. A missing
scope or intent argument fails with `INVALID_SCOPE`; null identity, unavailable or foreign-owner revisions
fail with `REVISION_UNAVAILABLE`. Scope state follows the actual Spring transaction: inner
REQUIRED calls and recreated service components share it, REQUIRES_NEW suspends/restores it, and
completion clears it. Each operation rechecks persisted owner/membership, so a savepoint rollback
cannot turn cached membership into authorization for a revision that no longer exists.

For example, two callers both declare quiz revisions containing A, B and A+B. One then resolves
A followed by B under READ; the other declares WRITE before resolving B followed by A and binding A+B. Their traversal order cannot
create the former lock cycle: the second writer waits for the full union before its first read.
Declaring only A and later requesting B is a transaction error, not an implicit scope extension.
This addresses contract-operation lock cycles, not every possible deadlock in an arbitrary outer
transaction. #700 must coordinate publication/current-pointer and revision-creation/FK locks with
this declaration order; callers must not first acquire overlapping identity locks in a conflicting
order. There is no global quiz mutex and no automatic transaction retry.

Phase A of #906 changes only scope intent. For example, an authorized owner's answer and a
full editor's answer/start/form read on the same revision can complete independently inside
explicit RC caller transactions. Binding still waits for an active reader. Existing authorization,
per-attempt/receipt serialization, isolation defaults and old-RR support remain unchanged.
RR range/gap coupling at other reads is not resolved here: #905 owns the new RC boundary,
and #906 phase B owns the subsequent freshness-only read simplification. No schema, index,
projection or extra SQL query is introduced by the intent switch.

Scope setup uses two metadata queries for up to 500 quiz revisions and 500 distinct questions;
larger declarations use bounded batches in the same canonical order. Locking reads use
UNION ALL batches of exact primary-key lookups: an IN predicate can choose a full scan and lock
unrelated identities/bindings, even with FORCE INDEX. Each arm takes its point lock in canonical
order, keeping disjoint sets parallel. Within the scope, resolution uses one membership query
and a current FOR SHARE metadata query per batch of up to 500 distinct questions, regardless of
option/payload size. A standalone nonempty resolution with up to 500 questions uses four queries.
The binding read sees a concurrently committed identical binding even inside an older REPEATABLE
READ transaction. Empty revisions need no identity or binding lock query and
remain distinct from unavailable revisions. Joining an outer transaction preserves its effective
read-only flag, isolation and completion boundary.

The bind service joins the caller's transaction, including an existing JPA transaction. It
requires the complete question set for the requested quiz revision and orders writes canonically.
Membership checks read immutable metadata without additional source-graph locks. The unique-key
insert/no-op and current binding read preserve an existing winner under READ COMMITTED and
REPEATABLE READ; no metadata is overwritten. A later unknown version, conflicting binding,
scope violation or SQL failure rolls back earlier bindings and participating revision writes;
a joined transaction is rollback-only even if its caller catches the failure.
TransactionTemplate execution is inside the sanitizing boundary, covering begin, SQL, and owned
commit/rollback failures as bounded `PERSISTENCE_UNAVAILABLE` without exposing technical causes.
If an existing transaction is joined, its eventual outer commit happens after this service returns
and its failure handling belongs to the caller. A commit/rollback failure is not proof of rollback:
the database may have committed before its acknowledgement was lost. Callers must reread durable
state before retrying the same binding after an unknown outcome. There are no automatic retries.
Domain failures extend `RuntimeException` directly so Spring JPA's repository exception translation
preserves conflict/integrity categories rather than reclassifying them as database failures.
`question.contract.resolutions` records only fixed `supported`/`unsupported` outcome labels.

CONTRACTS-01 uses persisted v1/v2 bindings and explicit registry doubles to prove dispatch after
component recreation, not concrete family grading. CONTRACTS-02 proves reached SQL and fresh-read
rollback of bindings plus participating #597 revision writes. Actual publication/current-pointer
composition remains #700; actual family compatibility remains #701 and the other family owners.
CONTRACTS-03 proves real MySQL concurrent success/conflict, same-transaction identical replay and
resolution, including different quiz parents sharing several questions in opposite input orders
under READ COMMITTED and REPEATABLE READ. Connection-open, reached SQL, rollback and commit faults
exercise real Spring transaction boundaries; fresh reads distinguish rollback from lost commit acknowledgement.
Additional JDBC/JPA races cover same-set resolve-then-bind, opposite-order subset reads followed
by a superset bind, resolve-only A/B composition and opposite-order binds of initially unbound
questions. Both outer transactions must commit and preserve the binding. Separate races prove
parallel disjoint writers and overlapping READ scopes in read-only or write transactions while
both transactions stay open. Resolve-then-bind compositions declare WRITE from the beginning.
Caught declaration/resolve/bind expansions must roll back a binding that already reached SQL.
Scope lifecycle checks cover component recreation, input mutation, subset redeclaration,
REQUIRES_NEW suspension/restoration, completion and savepoint rollback. JDBC regressions prove
that manual/NESTED rollback cannot erase a scope violation after a reached binding write; fresh
reads must find no binding after the rejected outer commit. The current Hibernate JPA dialect
rejects savepoints even with nested transactions enabled; JPA tests cover that boundary and
independent REQUIRES_NEW failure state. JPA invokes beforeCommit even for a global rollback-only
transaction, so its scope guard reports the typed violation before provider commit. The concurrency
and rollback matrix uses both isolation levels and runs against MySQL 8.0 and 8.4. CONTRACTS-04 checks
bounded metadata SQL for bulk scope setup and read-only/read-write resolution against multiple
quiz parents and large stored payloads.
These local proofs do not activate target execution or claim the unavailable downstream integrations.

Apply V84 before using this boundary. Preserve additive tables and retained bindings on application
rollback; use a forward fix after writes rather than dropping history or editing an applied migration.
There is no new retention period, offline mutation capability or destructive cleanup.

## Assignments and Legacy Share Links

### Assignment

An assignment is a published immutable delivery binding.

It should contain:

- immutable quiz revision reference;
- preset or explicit policy configuration;
- audience definition;
- availability window;
- participant limits;
- attempt limits;
- same-form or individualized-form policy;
- result visibility;
- review release configuration;
- reporting ownership;
- status and publication version.

Changing material assignment behaviour creates a new version or assignment. It does not rewrite active participant attempts.

[Q04–Q06](./decision-register.md#quiz-certificate-batch-approved) approve exact-assignment management/delegation through dedicated scope, separate from ownership of the source quiz. Assignment transfer offers an explicit retained-access choice for the former owner, default off, and gives the new owner an effective-access view including inherited/independent grant sources. Transfer is not a hidden permanent grant or a power to revoke authorities outside the actor's scope. An authorized emergency action may cancel one active attempt or the whole assignment with reason/confirmation/audit. The whole-assignment action prevents new starts and cancels affected active attempts; it does not automatically revoke finished results/certificates or refund committed start allowances. Prepare permission mapping and transactional/race-safe behavior while preserving legacy contracts.

Under [#672 A08](https://github.com/Gegcuk/QuizMaker/issues/672), revocation blocks new starts by default while active attempts retain their snapshots. Cancelling active attempts requires the separately explicit, authorized and audited `cancelActiveAttempts` decision; checking current assignment state must not silently turn ordinary revocation into cancellation.

### Authenticated assignment eligibility (#532)

`AssignmentEligibilityService` is an internal, read-only preflight over the real #531 immutable
snapshot and #535/#536 subject contracts. Its facade re-resolves the current authenticated
principal through `AuthenticatedSubjectResolver`; a claimed foreign subject, anonymous/string
principal or unavailable active-user lookup cannot establish identity. It samples the injected
`Clock` once after identity validation. Lookup conflict/unavailability denies without retries;
clock failure denies without client-time fallback. No HTTP operation or security rule changes.

The pure decision requires independently verified resource authority, visibility and applicable
organization membership, bound to the exact assignment ID, snapshot ID/version, participant,
owner and scope. Personal scope explicitly marks organization membership not applicable.
Ownership/admin roles do not bypass captured roster/group membership; an empty audience denies.
Draft, superseded, revoked, missing or unknown current-publication observations deny. Current
publication/revocation and access observations are trusted reader inputs, not client fields or
state transitions implemented here. Structurally valid IDs alone never establish source existence
or authority. Unknown input/fact versions deny; #531 rejects unknown assignment/audience versions
before they can become a snapshot.

The [approved availability boundary](decision-register.md#assignment-eligibility-window) is
`[opensAt, closesAt)`, with absent bounds unbounded. Persisted bounds use #531 UTC microseconds;
the clock is not rounded. For a 10:00–11:00 window, eligibility begins exactly at 10:00 and ends
exactly at 11:00. This does not expire/cancel an existing attempt or allocate a place/allowance.

An internal allow names the exact eligible assignment version. `publicDecision()` returns only
decision version and ALLOW/DENY; every denial has the same shape, without internal reason,
resource identity, window or audience. Future API adapters must use this projection and their
own approved HTTP contract. Internal diagnostic strings redact identifiers. Metrics use only
bounded version/outcome/reason dimensions; metric failure cannot alter eligibility.

`AssignmentEligibilityTest` covers the decision table, exact time boundaries, scope/reference
mismatches, immutable membership, version rejection, non-enumerating projection and legacy-link
classification. `AssignmentEligibilityServiceTest` composes the real #536 resolver with a fake
active-user query to prove authentication/claimed-subject isolation, Spring interface wiring,
server-clock behavior and bounded telemetry. These are local contract/composition proofs, not
real publication, organization grants, assignment start, revocation persistence or guest execution.

#621 supplies publication/current-pointer readers and #798 revocation; #534 must reload current
access/publication/audience facts and locking authenticated identity in its atomic start boundary.
An old allow cannot authorize a later start or offline replay. #611/#625 remain required only for
guest capabilities. No assignment repository, migration, write, provider, mapper or JPA relationship
read is added; N+1 does not apply to this slice. Existing APIs, attempts and share links are unchanged.

### Assignment storage foundation (#531)

`AssignmentContract` defines a version-1 draft/published snapshot with its own stable ID,
assignment ID and positive version, captured owner and personal/organization scope. The
configuration names an immutable quiz revision, exact preset and schema versions, a resolved
roster/group membership snapshot and its source-version ID, optional UTC availability bounds,
explicit capacity/enforcement and independent guest name/email requirements. Membership is
copied into an immutable collection; diagnostics redact resource and participant identifiers.
An empty roster is empty membership, never implicit public access. Guest configuration records
requirements only; it does not issue a capability or authorize a start.

V109 adds empty `assignments`, `assignment_drafts` and `assignment_versions` storage. Foreign keys
bind snapshot identity and retained quiz revision; unique assignment/version keys prevent two
competing versions from silently replacing one another. Check constraints reject unknown schema
versions, malformed/duplicate member IDs, inconsistent scope, invalid availability and capacity,
and contradictory guest identity fields. Published configuration and membership occupy one row;
update/delete guards preserve that immutable evidence. Draft mutation and publication/current
pointer orchestration are not provided by this migration.

The only delivered policy reference is the exact code-defined `FREE_PRACTICE/v1` tuple from #516.
This is a supported storage fixture, not a formal-assessment preset, default release rule or proof
of guest/assignment execution. Unknown preset versions and mutable/current aliases are rejected.
Additional preset/policy sources require their own typed validation and schema evolution; #621
must resolve current source authority, exact revision/policy and roster provenance before writing.
A structurally valid snapshot, source UUID, owner UUID or organization UUID never grants access.
Formal publication must also satisfy the approved explicit release-configuration contract;
that integration remains outside this storage foundation.

No existing rows or share links are converted, no production writer/endpoint is registered and
no new start is routed here. #621 owns publication, #532 eligibility, #534 start binding and #623
the legacy-link adapter. The retained identity fields deliberately have no cascading dependency
on mutable users/organizations. These guards do not select a retention period: #664/#699 retain
their record-class rules, and a future authorized erasure implementation must explicitly reconcile
them with immutable storage. Disable unused code before target writes; after writes use forward
fixes rather than destructive downgrade. MySQL DDL is not transactional; inspect Flyway history
and any partially created objects before a reviewed repair/retry, never automatic repair.

`AssignmentContractTest` verifies immutable values, versions, bounds, independent identity fields,
redacted failures and existing legacy-link classification. `AssignmentSchemaMySqlIntegrationTest`
upgrades a private real MySQL catalog from V108, preserves an actual legacy link and quiz revision,
checks FK/unique/check/index behavior, published immutability, reached-write rollback with fresh
reads, and a two-writer version race with exactly one successful winner. These are schema tests,
not publication/start integration evidence. No JPA relationship read, mapper or serialization path
is introduced, so N+1 is not applicable to this increment. Existing HTTP/OpenAPI contracts remain
authoritative; this feature adds no endpoint or API group.

### Legacy share links

Existing share links remain supported through an adapter.

They are not silently renamed or rewritten into assignments.

The adapter may resolve a legacy link into:

- the current legacy access behaviour;
- an equivalent default preset;
- a legacy participant subject;
- a compatibility form and result representation.

The adapter must preserve documented success behaviour without preserving security vulnerabilities.

### Capability separation

These concepts must remain distinct:

```text
Quiz share capability
Assignment access capability
Attempt execution capability
Attempt review capability
Public certificate verification capability
```

Possession of a quiz or assignment link must not automatically authorize access to another participant’s attempt review.

---

## Participant Subjects

An attempt must not assume that every participant is a registered `User`.

Candidate subject types include:

```text
AUTHENTICATED_USER
CAPABILITY_GUEST
EXTERNAL_PARTICIPANT
COHORT_MEMBER
TEAM
```

The first free-practice walking skeleton is authenticated. Capability-bound guests have separate decisions and conformance gates; listing them here does not activate them. The target model must not use a common anonymous sentinel as authorization identity.

Participant identity and access capability are related but separate concepts.

For example:

```text
Participant subject:
    CAPABILITY_GUEST / pseudonymous subject 9271

Current authorization:
    exact attempt capability X

Display identity:
    "Student 14"
```

The server resolves the subject. A client-supplied `userId`, `participantId`, team ID, or subject type cannot establish ownership.

### Authenticated subject resolution (#536)

`AuthenticatedSubjectResolver` is an internal application interface returning the existing
`AUTHENTICATED_USER/v1` value for a server-allocated attempt ID. Its Spring adapter reads only
the current `SecurityContext`: the authenticated `UsernamePasswordAuthenticationToken` with
`UserDetails` produced by the session/JWT boundary. Anonymous, unauthenticated, unsupported or
invalid principals fail closed. Principal account flags must be valid; a contradictory token
name and principal username is rejected. String/UUID principals and authentication details
cannot supply an alternative user, subject or guest identity. No raw credentials are inspected.

The user-owned `AuthenticatedUserLookup` performs one indexed scalar username query for current
active, non-deleted users, excluding the reserved `anonymous` sentinel. It does not load user
entities, roles, permissions, passwords or contact fields, and does not fall back to email or
interpret UUID-shaped usernames as user IDs. A missing or ineligible account receives the same
non-enumerating failure as an invalid principal. Database/transaction failures produce a typed
unavailable outcome; bounded outcome-only timing must never change success or failure semantics.

Resolution performs no writes and grants no resource permission. Its lookup checks current
eligibility, not a lifetime guarantee: #535 rechecks and locks eligibility when binding within
the eventual #600 start transaction. #537 owns attempt access, #625 owns guest resolution and
mixed-credential transport integration, and #609 owns public activation. Existing routes and
authentication responses do not invoke this resolver and remain unchanged. No endpoint, schema
migration or historical identity inference is introduced.

### Authenticated self-attempt access (#537)

`AttemptAccessService.requireAccess(attemptId, operation)` is the dark application boundary for
authenticated self-attempt `VIEW`, `ANSWER` and `RESULT`. The [6 November access decision](./decision-register.md#authenticated-self-attempt-access)
requires `ATTEMPT_READ` for view/result and `ATTEMPT_CREATE` for answer, plus current quiz ownership
or the same full-editor authority as #600. Public visibility, `ATTEMPT_READ_ALL`, role names and
caller-supplied identity cannot replace the immutable participant binding. A private quiz remains
accessible to its qualifying owner/editor for their own attempt; a removed quiz or revoked rights
deny access. Loss of editing rights also denies access to an already started public-quiz practice.

The existing #536 principal validation is reused through `resolveCurrent`, whose user-owned scalar
query takes a current shared lock rather than using the caller's earlier transaction snapshot.
The ordinary `resolve` lookup remains unchanged. First, a separate current locking read checks the
#535 binding using the exact `(user_id, attempt_id)` range and forces `ix_attempt_subject_user`.
Only a matching participant binding permits the subsequent bounded metadata read of lifecycle, trace scope, form,
policy snapshot and immutable quiz revision. This ordering and index selection prevent a locked
foreign subject/lifecycle/trace row from turning `DENIED` into an observable lock-timeout outcome;
a foreign or absent binding never reaches the metadata read. Missing roots,
unsupported metadata versions, mismatched revision references or unstarted/inconsistent lifecycle
evidence deny access. These queries read no questions, answers, policy JSON or source documents. The
quiz-owned access boundary locks current account, effective permissions and non-deleted quiz facts;
the revision's content owner must match the resource owner. A newer publication does not invalidate
the old attempt's saved revision or cause mutable-content reconstruction.

All reads join a caller transaction when one exists. Consumers must call this boundary inside
their own operation transaction after acquiring any command serialization lock. Shared access
locks last until that transaction finishes, including when it started before a permission change
under `REPEATABLE_READ`. Calling it without an outer transaction provides a current check only.
The returned references are invocation/operation-scoped facts, not a bearer credential or a cached
authorization grant. They do not authorize a lifecycle transition, delivery, grading, release or
an answer write; #523/#524/#543/#555/#556 retain those guards and their real integration tests.
`RESULT` can pass access checking while the result remains unreleased or does not yet exist.

The service writes no domain records. All missing/foreign/legacy/resource/version refusals use
the same content-free `DENIED` reason, without exception causes or suppressed diagnostics.
Absent authentication is `UNAUTHENTICATED`; ordinary storage failure is `UNAVAILABLE`.
Existing database-conflict classification is retained as `IN_PROGRESS` for MySQL 1205 and
`RETRYABLE` for 1213, without automatic retries. Failures within a joined transaction mark it
for rollback. The bounded `quizmaker.attempt.access` timer records operation/outcome only and
cannot change authorization. No HTTP route or exception advice is installed; future adapters
own uniform non-enumerating RFC 7807 mapping. Existing legacy routes remain authoritative.

`AttemptAccessMySqlIntegrationTest` creates real published, contract-bound content and invokes
#600 before checking access. It covers independent operation permissions, full-editor revocation,
wrong-owner and caller-identity probes, absent/legacy/partial/version-incompatible evidence,
old transaction snapshots, republishing, storage failure and actual lock contention. Under both
`READ_COMMITTED` and `REPEATABLE_READ`, locked foreign subject/lifecycle/trace rows return the same
`DENIED` as absent attempts for all three operations, while own contention retains `IN_PROGRESS`
and succeeds after the lock is released. It compares persisted state before/after reads, verifies
outer rollback, checks the binding lookup's actual index plan and measures SQL across multiple
owners/quizzes with small and larger forms (at most six SELECTs per successful check). External
storage is replaced only at its port.
These checks prove the access boundary; they do not claim target answer/result delivery or public
activation, which remain with their named consumers and #609.

### Authenticated subject storage (#535)

`AttemptSubjectService` is an internal, dark persistence boundary for `AUTHENTICATED_USER/v1`.
The immutable subject key is its type/version and user ID; the binding's natural key is the
server-allocated attempt ID. `bindOrReplay(trustedUserId, subject)` rejects a mismatched user,
missing/inactive/soft-deleted users and the reserved legacy `anonymous` sentinel. The trusted
argument must originate in the server authentication boundary, never a request field. #536
owns that resolver and #537 owns resource access; storage alone grants neither authentication
nor authorization. No current attempt route invokes this service.

V86 adds `attempt_participant_subject` with one row per attempt, exact type/version checks and
a non-cascading user FK. A read-only `AttemptSubjectEntity` mapping makes Hibernate schema
validation and test schema lifecycle aware of that FK; no runtime read traverses its lazy user
association and JDBC remains the only application writer. It does not read, backfill or alter legacy attempts. The table deliberately
has no FK to the legacy `attempts` model: #600 must create the target attempt and binding atomically
in its own transaction, using the same allocated ID. This storage increment does not prove that
start integration. It adds no guest subtype, account-erasure job or retention period; those remain
with #624/#851/#699 and the approved record-class rules.

First writes and identical retries serialize on the binding key. A conflicting user cannot replace
the winner. `bindOrReplay` returns the verified current stored binding and joins an existing
transaction, so a successful return is not an independent commit. Its later rollback removes the
new binding. Confirmed rollback and lost commit acknowledgement can both be reconciled by retrying
the same natural key after recreating the service. A shared user-row lock keeps eligibility stable
until commit; it does not prevent later account deactivation.

`read` returns either `Bound` evidence or `LegacyOrUnbound`. Absence proves no target binding, not
that an attempt exists or belongs to a particular user. It never derives identity from a legacy
user, sentinel or null reference. Retained evidence remains readable after soft deletion without
granting login/access. Unknown stored types/versions fail closed. Ordinary reads honor transaction
isolation: an older `REPEATABLE_READ` snapshot may still omit another transaction's binding after
an identical retry; use the verified return value or a fresh transaction.

Unit and real-MySQL fixtures cover identity disagreement, eligibility, unique/FK/type/version
constraints, concurrent identical/conflicting writers under both isolation levels, reached-write
rollback, commit uncertainty, recreated-service replay and participation in real JPA transactions.
The nullable historical-source fixture verifies preservation only; the current legacy `attempts`
schema does not permit null users. Reads use one indexed scalar query per binding and never traverse
JPA user/role/answer graphs. The bounded `quizmaker.attempt.subject.operations` timer uses only
operation/outcome tags and emits no identities. A database failure is a typed unavailable outcome,
not a successful empty read. Timer startup, registration and recording failures never change the
command result or replace its original error. Database tests prove successful standalone/outer
commits and preserved domain/rollback semantics with a real registry meter-type collision.

Schema conformance has two independent fixtures, each in its own temporary MySQL catalog: the
complete Flyway chain through V86 followed by Hibernate `validate` with the real mappings, and a
controlled Hibernate `create-drop` over the explicitly prepared retained-subject FK. An unmapped-FK
negative control fails on DDL errors. These fixtures do not recreate the shared legacy schema or
claim that mapping the new FK reconciles old Flyway/Hibernate constraint names. Timestamp precision
is preserved during validation; the controlled lifecycle alone asserts Hibernate's generated schema.

Before target writes, recovery can disable the dark consumer; after
target writes, preserve the binding evidence and use a forward fix rather than destructive downgrade.

After account deletion, [minimal participant identification is retained in non-training results](./decision-register.md#retained-non-training-identity) and may be read only with current access rights to that result and identity. The retained fields are the participant's name/designation from the assignment and stable participant identifier, without contact data. Do not use email or phone as a fallback name/identifier or copy the rest of the profile. The identifier distinguishes equal names within the applicable scope; the display name does not establish verified real-world identity. The retained subject/identity does not preserve login capability or grant access. Source/capture timing, identity corrections and handling of missing or contact-based historical labels remain [contract work](./open-questions.md#oq-r03); do not invent historical identity.

---

## Question Bank Selection

[Q38](./decision-register.md#preparation-batch-approved) requires an independently editable question copy for authorized reuse of another author's content, with retained provenance and only authorized material bindings. A source correction does not silently alter that copy; no existing published revision changes. Reading the source is not permission to copy it. Prepare explicit question ownership/source authority and compatible handling of existing shared/orphaned records; the current attachment method is not proof of the approved reuse contract.

Question selection is separate from ordering.

Selection answers:

> Which questions belong in this attempt?

Ordering answers:

> In what order are those selected questions shown?

### Pure ALL/MANUAL_SET selection boundary (#544)

`QuestionSelectionService` is an internal, side-effect-free boundary for already authorized
server callers. It selects immutable **item occurrence IDs**, not mutable question IDs or
question-revision IDs. Repeated occurrences of the same question remain separate items.
`QuestionSelection` defines version 1 of its input, configuration, output, evidence and supplied
atomic-group metadata. Each strategy also has an explicit algorithm version. The #544 increment
supports version 1 of the exact identifiers `ALL` and `MANUAL_SET`; #545 extends the internal
boundary with the random strategies described below.

The caller supplies the complete eligible metadata from one immutable revision. `ALL` selects
each eligible item exactly once. `MANUAL_SET` selects exactly the configured IDs, bounded by
that eligible membership, without a new numerical quiz-size quota. Both return authored
revision order, independent of metadata/configuration iteration order. Positions may have gaps;
duplicate IDs or positions are errors rather than a reason to deduplicate or break ties by UUID.
Manual configuration IDs are canonicalized as a set; their supplied order is not display order.
An `ALL` configuration cannot also carry unused manual IDs.

An empty eligible revision produces an empty result. An explicitly empty `MANUAL_SET` also
produces an empty result against a nonempty revision. This represents selection only: it does
not authorize starting an empty quiz. Start eligibility belongs to the consuming use case.
The existing resolved-policy vocabulary remains `ALL`; an internal `MANUAL_SET` configuration
does not add a public policy option, change canonical policy bytes or activate a new route.

`Input.fromRevision` checks the exact content binding from #515 against #597's real immutable
revision and projects item IDs/positions only. All items are eligible in this projection.
Callers applying further eligibility rules supply their complete verified metadata to `Input`;
the selector does not resolve ownership, permissions, visibility, contract availability or bank
membership from client claims. Neither constructor nor result is authorization evidence.

Optional atomic groups are supplied metadata, not section/case authoring or a database read.
Every group must be nonempty with distinct known eligible IDs; duplicate group declarations
are rejected. Each supplied group must be selected whole or not at all. Partial selection fails
without expanding the set or dropping requested items. Overlapping supplied constraints are
evaluated independently. These fixtures prove this local validator, not future case publication
or section-runtime integration owned by #552/#546.

The immutable result contains only selected IDs and evidence of the input/configuration/strategy
and revision versions. It omits question content, answers and unselected bank/group membership.
Evidence is not a complete input digest, command receipt or substitute for persisted exact form
membership/order. #522 owns materialization and persistence; #547/#600 own start integration,
and the applicable conformance/activation gates remain separate. No content hashing occurs.

Unknown versions/strategies, malformed metadata, missing/ineligible IDs and invalid/partial groups
fail through bounded `QuestionSelectionException` reasons without rejected values or causes.
The input's diagnostic string includes counts only. The #544 increment introduces no log, metric label, transaction,
repository, clock, random source, public endpoint, migration or offline package.
N+1 is inapplicable: the selector performs no storage/relationship reads. Plain tests cover
golden results, permutations, empty/invalid inputs, defensive copies and real in-memory
revision/policy/ordering composition; that composition is not persistence or start proof.

### Pure deterministic random selection (#545)

The same `QuestionSelectionService` accepts internal `RANDOM_COUNT` and `RANDOM_PERCENTAGE`
configurations over immutable eligible occurrence IDs. Both remain dark: the resolved policy,
public API, `FREE_PRACTICE/v1` and canonical ALL start remain unchanged. These are algorithms,
not a second start service or evidence that formal assignments are available.

The [9 November owner decision](decision-register.md#random-percentage-selection-ceiling)
selects upward rounding for percentage size. Percentages use integer basis points from zero
to 10000 inclusive. `ceil(candidateCount × percentageBasisPoints / 10000)` uses a long product,
so 33% of ten gives four, 50% of three gives two, and even int-sized pool arithmetic does not
overflow. Zero and empty selections remain valid at this pure boundary; the start consumer
owns empty-attempt eligibility. Count exceeding eligible membership fails with
`INSUFFICIENT_CANDIDATES`; there is no partial result, resampling or fallback.

Random input additionally requires a versioned `SelectionContext` carrying a **server-owned**
operation identity. The authorized consuming use case supplies the immutable revision,
complete eligible metadata, configuration and operation identity. Neither this record nor its
constructor is authorization evidence. No request accepts seed bytes, key material, a selected
replacement result or a client-chosen authoritative operation identity. #547 must establish
that authority at the actual start boundary; #545 does not claim integrated HTTP enforcement.

`SelectionSeedSource` is the project-owned port. `HmacSelectionSeedSource` implements
`selection-seed/v1` with HMAC-SHA-256 and separately configured secret material. The optional
Spring properties are `quizmaker.attempt.selection.seed-key-version` (positive integer) and
`quizmaker.attempt.selection.seed-key-base64` (canonical Base64 of a dedicated 32-byte key).
Provision the dedicated key through the installation's secret configuration; never reuse an
authentication/provider key or put it in source, logs, public DTOs or metric labels. Missing or
malformed configuration yields `SEED_UNAVAILABLE` on random selection, while application
startup and ALL/MANUAL_SET remain usable.

The seed message is the ASCII domain `quizmaker/selection-seed/v1` followed by a zero byte;
big-endian 32-bit input, revision-schema, configuration, algorithm, context and key versions;
count and percentage (unused value `-1`); revision and operation UUIDs as two big-endian
64-bit words each; then 32-bit strategy-name byte length and exact ASCII strategy name.
Candidate order, mutable content and system time are absent. Reusing an operation identity
under another revision/configuration/key version derives different seed evidence.

`random-selection/v1` ranks each occurrence with HMAC-SHA-256 keyed by that derived seed.
The message is ASCII `quizmaker/random-selection/v1`, a zero byte, and the occurrence UUID's
two big-endian 64-bit words. Compare digest bytes unsigned, with canonical UUID-string order
as the deterministic collision tie-break. Select the required prefix, then return IDs in
authored revision order. Selection membership and question display ordering remain separate.
The calculation uses O(n log n) time and O(n) extra space for n eligible items; no storage or
network access occurs. Supplied atomic groups are explicitly unsupported for random strategies
until #546; existing ALL/MANUAL_SET whole-group validation remains unchanged.

Bounded `RandomEvidence` records algorithm/context versions, server operation identity, key
version, a SHA-256 fingerprint of the **32-byte derived seed**, candidate count and exact
selected count. It contains no key, raw seed, question content or unselected bank membership.
Seed material uses defensive copies and redacted diagnostics. Request/seed diagnostics do
not print operation/revision identities or secret material. The only digest inputs here are
bounded metadata and seed bytes; documents are never read or hashed for selection/versioning.

Repeat construction with the same key version, key material and context reproduces exact IDs
and evidence after a new service/source instance. A key rotation is explicit new evidence;
historical forms are never regenerated using the current key. #547 must persist final IDs,
ordering and evidence atomically with start. #668 must use the real #545/#547 composition for
start/resume before formal activation; #604 owns stratified quotas, not this algorithm.

Tests use a public synthetic key and an independently calculated HMAC golden vector. The D38
fixture has 40 fixed occurrences (16/8/8/4/4 by authored family), operation UUID integer 6 and
key version 7; RANDOM_COUNT selects 20 exact golden IDs with composition 8/4/4/2/2. Family is
not an algorithm input. This proves selection only, not grading, persistence or restart of an
attempt. Property-style cases cover sizes, rounding, permutations, uniqueness and server
contexts; negatives cover insufficient pools, malformed versions/metadata, unavailable seeds
and unsupported constraints. New-instance, concurrent-call and Spring wiring tests use the
real seed adapter. N+1 is inapplicable because this increment adds no JPA read path.

### Candidate selection strategies

```text
ALL
MANUAL_SET
RANDOM_COUNT
RANDOM_PERCENTAGE
STRATIFIED
WEIGHTED_RANDOM
UNSEEN_ONLY
INCORRECT_ONLY
LOW_CONFIDENCE_ONLY
DUE_FOR_REVIEW
ADAPTIVE
```

### Stratified selection

Example:

```text
Total: 20 questions

Topic quotas:
- Java: 5
- Spring: 5
- SQL: 5
- Security: 5

Within each topic:
- Easy: 2
- Medium: 2
- Hard: 1
```

This permits different participants to receive different questions while preserving comparable form composition.

### Candidate constraints

Selection should eventually support:

- minimum questions by tag;
- maximum questions by tag;
- required questions;
- recently seen exclusions;
- mutually exclusive questions;
- one question from a group of equivalent alternatives;
- prerequisite constraints;
- complete case-block inclusion;
- question-type quotas;
- difficulty quotas;
- content-exposure constraints;
- exclusion of retired or invalid items.

### Impossible selections

Under [#673 A14](https://github.com/Gegcuk/QuizMaker/issues/673), an unsatisfiable formal selection fails at publication/start. Practice also fails unless an explicit saved typed policy authorizes a partial/fallback form and records its unmet constraints and actual composition. This exception never permits silent degradation or invents a fallback for a policy that has none.

The system must not silently:

- reduce the requested count;
- ignore a topic quota;
- split an atomic case block;
- replace stratified selection with random selection;
- downgrade an unknown strategy to `ALL`.

### Equivalent forms

The architecture must distinguish:

```text
sameContent
sameOrder
equivalentComposition
equivalentDifficulty
individualRandomization
```

Two students may receive different questions while still receiving forms designed to be equivalent.

This equivalence should be auditable through composition metadata without exposing hidden questions or answers.

Advanced statistical equating is a future product capability, not implied by simple topic and difficulty quotas.

---

## Question Ordering

Candidate strategies include:

```text
AUTHORED
SHUFFLED_PER_ATTEMPT
STABLE_PER_USER
STABLE_PER_ASSIGNMENT
STABLE_PER_SHARE_LINK
STABLE_PER_COHORT
SEEDED
DIFFICULTY_ASC
DIFFICULTY_DESC
TOPIC_GROUPED
TOPIC_INTERLEAVED
ADAPTIVE
```

A fixed authored order is necessary when:

- later questions depend on earlier ones;
- the quiz follows a teaching sequence;
- all students discuss question numbers together;
- the content represents one story or case;
- stable comparison is more important than randomization;
- accessibility requires predictable navigation.

### Authored-order contract boundary (#520)

`AuthoredQuestionOrder` is a pure, internal `AUTHORED/v1` contract implementing the
authored-order part of #612 R01. Its immutable input contains the ordering format version,
quiz-revision ID/schema version, existing `QuestionOrderingPolicy`, complete trusted item
metadata (source occurrence ID and authored position), and explicit already-selected item IDs.
Selection remains a separate operation; ordering a supplied subset does not implement `ALL`
or `MANUAL_SET`. `Input.fromRevision` projects the real #597 immutable revision and checks
that #515's resolved content binding names that same revision. The metadata-only constructor
accepts already-resolved server facts; neither constructor proves existence or authorization.

Ordering uses authored position, independently of input/query order and UUID ordering. Positions
are nonnegative integers and may have gaps, matching #597/#519; missing positions, repeated
positions, duplicate source/selected IDs and selected IDs outside the revision fail explicitly.
Ambiguous unselected source metadata is rejected too. There is no UUID tie-break, silent
deduplication, renumbering, randomization or fallback to mutable authoring data. Empty selections
are representable without deciding whether a later start operation may accept them.

The immutable result contains the exact ordered source item IDs and typed evidence of the
ordering format, revision/schema and strategy/algorithm version. Two occurrences of the same
question revision retain their separate item IDs. Unknown ordering/revision versions fail closed;
unknown strategy versions are also rejected by the existing policy contract. Errors use bounded
`QuestionOrderingException` reasons without content or resource identifiers. Input/evidence/result
contain no question text, answer keys, hints, explanations or caller identity.

This boundary performs no I/O, relationship loading or writes and introduces no endpoint,
migration, telemetry, selection quota or application-service dependency. #521 owns option order;
#522 owns actual form materialization/persistence, including #612 D05's exact persisted order;
#600 owns start composition and #609 owns public activation. A deterministic calculation is not
proof of persisted resume/review behavior or activation of those future integrations.

### Shuffle scope

Randomization may operate on different units:

```text
QUESTIONS
SECTIONS
QUESTIONS_WITHIN_SECTION
CASE_BLOCKS
QUESTIONS_WITHIN_CASE
```

For example, section order may remain fixed while questions within each section are randomized.

---

## Answer Option Ordering

Option ordering is independent from question ordering.

Candidate strategies:

```text
AUTHORED
SHUFFLED_PER_ATTEMPT
STABLE_PER_USER
STABLE_PER_ASSIGNMENT
STABLE_PER_COHORT
SEEDED
```

Question- and option-level constraints include:

```text
shuffleAllowed
shuffleGroup
pinnedPosition: FIRST | LAST | NONE
preserveRelativeOrder
semanticOrder
```

These constraints support:

- “All of the above”;
- “None of the above”;
- Likert scales;
- Strongly disagree to strongly agree;
- chronological choices;
- grouped dependent options;
- matching questions;
- matrix questions.

The exact option order is persisted in the form and never regenerated during resume or review.

### Dark option-order storage and calculation (#521)

`FormOptionOrderService` adds exact option order alongside #519 without changing its identity-only
contract. Its already-authorized internal caller supplies trusted owner scope, a fixed form and
the resolved `AnswerOrderingPolicy`. The service loads the owner-scoped immutable revision and
resolves its exact saved response/scoring bindings through #598. External transactions must first
declare their complete revision scope using `QuestionRevisionContractService.declareScope`.
The operation joins that transaction; returning successfully does not commit an outer transaction.

The exact response adapter explicitly supplies `OptionLayout/v1`. Its default implementation
rejects unsupported contracts, so an existing adapter never silently acquires option support.
The descriptor partitions the complete authored option list into contiguous ordered blocks.
Members preserve their relative order; unpinned blocks may move as units; FIRST/LAST blocks stay
anchored. A semantic-order or shuffle-disabled question declares `shuffleAllowed=false`.
These are trusted adapter facts, not new authoring fields or an interpretation of arbitrary JSON.
Unsupported constraints must not be weakened into a permissive descriptor by a future adapter.

`AUTHORED/v1` preserves immutable authored positions. `SHUFFLED_PER_ATTEMPT/v1` uses Fisher-Yates
over movable blocks, seeded by the first eight SHA-256 bytes of the fixed big-endian format:
32-bit version 1, form UUID (two 64-bit words), then form-item UUID (two 64-bit words). Java Random's
specified algorithm and a frozen vector define this calculation version. Only 36 bytes of bounded
identity metadata are hashed; no document/question text is hashed. This is option display order,
not an unpredictable formal question-selection strategy or proof of persisted form evidence.
The exact stored order remains authoritative; reads never run the algorithm or reshape it from
mutable authoring data. A changed strategy, form or descriptor that produces different evidence
cannot overwrite an existing order.

The initial boundary accepts the five baseline family types. Concrete response/scoring adapters
remain #701's work; registry availability and adapter-supplied complete metadata are required
before a type can actually be written. No binding/version is inferred from a question-type enum
or the legacy handler. Tests use explicit registry component adapters and do not prove #701's
grading semantics. An adapter for an option-free question supplies an explicitly empty layout;
options are never fabricated. Repeated occurrences of a question have separate form-item order.

V89 adds `attempt_form_option_order`, `attempt_form_item_option_order` and `attempt_form_option`,
plus supporting unique indexes. Composite foreign keys bind each stored option to the correct
immutable question, source occurrence, fixed-form item and exact contract pair. Unique item-option
and display-position keys reject duplicate membership. Root/item counts distinguish a complete
empty order from absent or incomplete evidence. Existing forms are not backfilled and remain
without option-order evidence until explicitly processed through this boundary. There are no
cascades, retention jobs, destructive downgrade or legacy-attempt rebinding; recovery after writes
uses forward-fix.

Form and order writes commit atomically. Per-form serialization/current reads preserve identical
replay under READ_COMMITTED and REPEATABLE_READ, including an older snapshot; changed input conflicts.
The order root retains its exclusive lock through commit. Final comparison uses current FOR SHARE
reads without upgrading shared revision/binding locks. Different empty forms of the same revision
can therefore commit independently; MySQL tests synchronize both writers after real inserts and
before comparison under both isolation levels.
Unknown commit outcome is reconciled by replaying the same identities, never by allocating a new
form. Missing/foreign IDs are omitted by owner-scoped metadata reads; persistence failure is not an
empty result. Invalid stored versions, counts, binding metadata or positions fail closed.
Reads use three metadata SELECTs per batch of at most 500 requested IDs, without question content
or JPA relationship traversal. Multi-parent/increasing-option fixtures measure this bound.

The bounded `quizmaker.attempt.option.order.operations` timer carries operation/outcome only;
unavailable/unknown commit acknowledgement is `persistence_unavailable`, not a proven rejection.
Timer startup/recording failure cannot change command results. No new operational threshold is selected.
This increment adds no endpoint, permission, participant response, attempt start or activation.
#522/#600/#526 own materialization/start/delivery composition; #609 owns public activation.
Stored component fixtures are not proof of those future runtime integrations.

---

## Attempt Forms

An attempt form represents exactly what may be delivered to a participant.

### Fixed-form identity storage boundary (#519)

`FixedFormService` is a dark internal application boundary for already authorized server callers.
It stores identity and supplied positions only; it does not start an attempt, select questions,
resolve participant authority, deliver content or activate a new execution route. The trusted
owner scope is checked against the immutable quiz revision. It is additional persistence scoping,
not permission to publish, view or take a quiz and not a client-supplied identity claim.

V85 adds `attempt_form` and `attempt_form_item`. A v1 form has an immutable ID, schema version,
quiz-revision reference and item count. Each item has a stable ID scoped by its form, one source
item in that exact quiz revision and a nonnegative position. Item identity, source occurrence
and position are independently unique within a form. Composite foreign keys prevent attaching
an item from a different revision even when both sources exist. The source item identifies its
immutable question revision; question text, answer keys and response/scoring algorithms are not
copied or inferred. This foundation can retain unbound question identities: resolving supported
response/scoring implementations remains the later materialization/start boundary.

The service preserves supplied positions (including gaps) and returns items in position order;
it does not implement an ordering algorithm. Empty forms are structurally representable without
deciding whether a later use case may start one. Option order is #521, materialization is #522,
fingerprint/legacy-read interpretation is #613, and canonical start is #600.

Create/replay is atomic and keyed by the form ID. Identical immutable metadata replays successfully;
changed revision, cardinality, item identity, source or position conflicts. The root's immutable
item count prevents a larger retry from appending items. Unique-key no-op inserts serialize
competing writers without overwriting the winner; a final current read compares all item metadata
before commit. Any late mismatch rolls back provisional inserts. Different forms do not require
an exclusive quiz lock. These guarantees cover the supported service/repository boundary, not
privileged SQL updates/deletes. No trigger or database access-policy change is introduced.

`findByIds` omits missing and foreign-owner forms, deduplicates requested IDs and retains request
order. It reads two metadata queries per batch of up to 500 visible form IDs, independently of
item count; an entirely missing batch needs only the root query. Parameter batching is not a
quiz-size or selection limit. Both queries are ordinary non-locking reads governed by transaction
isolation. An older `REPEATABLE READ` snapshot may still omit a form committed by another transaction
after that snapshot, even after an identical replay succeeds; `READ COMMITTED` sees the committed
form, and a new transaction sees it after commit. A transaction sees its own new insert immediately.
Callers needing the verified form after creation/replay use the result of `createOrReplay` directly,
rather than requiring a subsequent `findByIds` to refresh a snapshot. Successful return inside an
enclosing transaction does not commit it; a later rollback still cancels the new form and its items.
Write serialization and exact comparison remain inside `createOrReplay`. Ordinary reads take no
shared row/gap locks that would need upgrading when concurrent callers subsequently create/replay.
No JPA relationships, question payloads, media objects or document contents are loaded by these
operations.

Failures use bounded `FixedFormException` reasons without identifiers or SQL diagnostics.
Transaction/connection failure, including an unknown commit outcome, is reported as
`PERSISTENCE_UNAVAILABLE`: reconcile using the same form ID and exact metadata, never assume the
write disappeared or allocate a replacement identity. No automatic transaction retry is added.
The operations timer uses only `operation` (`create_or_replay` or `read`) and bounded `outcome`
tags. Success describes completion of this service boundary; an enclosing transaction may still
roll back. The combined create/replay operation does not claim to distinguish first insertion
from replay using driver-specific affected-row counts.

The migration neither alters nor backfills legacy attempts. Foreign keys retain the exact revision
references without deletion cascades. Retention/erasure remains purpose-scoped under #664 and its
implementation owners; this store adds no cleanup worker or retention period. Before target
writes, application rollback leaves empty additive tables. After writes, retain the tables and
use a forward fix. Existing quiz, training, authentication, scoring and public API contracts stay
on their current paths until their separately owned activation gates pass.

### Dark redacted fixed-form materialization (#522)

`FixedFormMaterializer` composes the existing immutable revision, selection, authored question
order and exact option-order boundaries. Its internal command supplies a fixed form with stable
form-item IDs, #544's selection result, #520's ordering result and the option-order policy. The
trusted server caller establishes eligibility and authorization; the owner ID only scopes the
retained revision. No client-supplied owner, include flag or raw question JSON is accepted as
disclosure authority. Selection IDs denote revision occurrences, including separate occurrences
of the same question. The materializer validates revision evidence, membership and the exact
authored source sequence before writing. Gapped positions and structurally empty forms retain
the existing #519 semantics; this does not decide whether a start may accept an empty form.

The service resolves the exact saved response/scoring bindings and calls the real #521 writer.
Form identity, items, contract bindings and complete option order join one transaction. A caller
already in a transaction must declare its complete #598 revision scope first; successful return
does not commit that transaction. A projection failure after actual inserts rolls back the new
form/order together. A caller cannot catch that failure and commit partial rows. Identical retries
reuse all identities; changed form/order conflicts without overwriting retained evidence. An
unknown commit acknowledgement is reconciled with the same command, not a new form identity.
Current write comparisons support replay even inside an older REPEATABLE_READ snapshot.

The returned `ParticipantForm` is separate from `FixedForm`, `QuestionRevision` and contract
evidence. It exposes only form/item identity, display position, question family, question text and
closed `ParticipantContent` records. It has no raw JSON, source key, question/revision/owner ID,
correctness, answer, hint, explanation, scoring or private provenance field. Options carry immutable
option UUIDs and text, and the projector applies the exact order verified by #521. Missing, extra,
duplicate or wrong-family presentation data fails; source payloads and adapter diagnostics are
not retained as exception causes. #612 D05 is implemented with existing immutable content
references and persisted order, without copying complete question payloads or a new migration.
Fingerprint verification remains separately owned by #613.

The shared option-order service sanitizes unexpected `optionLayout` adapter exceptions at the
invocation itself, before any `TransactionTemplate` can log rollback diagnostics. Known contract,
option-order and revision failures retain their bounded type/reason but are rebuilt without causes
or suppressed exceptions. The same protection applies to direct option-order callers and callers
through the materializer. Regression fixtures enable the actual transaction DEBUG logger, assert
that throwable-bearing events occurred without protected markers, and verify rollback and a
successful retry through both paths.

The projector also rebuilds known `participantContent` contract/materialization failures with the
same reason, removing causes and suppressed diagnostics before rollback logging. Two reached-write
MySQL regression cases verify the returned exception, actual DEBUG events, complete rollback and
a successful retry; a safe top-level message alone is not sufficient evidence of redaction.

Presentation support is explicit on the exact `VersionedResponseContract` through
`participantContent`; its default rejects unsupported contracts. The first presentation vocabulary
covers text-only MCQ_SINGLE, MCQ_MULTI, TRUE_FALSE, FILL_GAP (with or without options) and ORDERING.
The adapter owns source-key interpretation and must describe the complete presentation rather
than silently dropping unsupported material. This boundary rejects attachments without a supported
retained representation; embedded media must be rejected by the exact adapter when it cannot
retain and represent it. Media-aware presentation needs the actual publication/binding/retention
integrations, including #830 where applicable. No binary-lifetime or media delivery guarantee is
inferred from an asset ID. The other four families keep their separately owned adapter gates.

`ParticipantFormProjectorTest` verifies the closed representation, stable option identity,
reordering, defensive copies, hidden-item omission, optional Fill Gap options and protected-field
absence. `FixedFormMaterializerMySqlIntegrationTest` composes real Flyway/JDBC repositories,
registry, contract service, form/order services and transactions. It proves reached-write rollback,
outer rollback-only behavior, restart/lost-ack replay, both isolation levels, concurrent successful
controls and typed conflicts, owner denial, changed immutable revisions and bounded SQL for
multiple forms/increasing child counts. Its family adapters are explicit component fixtures with
valid source shapes: they do not prove #701's actual validation/scoring, #700's publication writer,
#600's start or #526's participant-read integration. No JPA relationship loading is introduced.

The `quizmaker.attempt.form.materialization` timer has fixed format and bounded outcome labels.
Success covers a completed materialization boundary, including identical replay; it does not claim
an outer transaction committed or distinguish insert/replay with driver-dependent affected counts.
Telemetry failures cannot change the business outcome, and unavailable persistence is never an
empty form. The increment introduces no endpoint, policy change, permission, provider call,
attempt/lifecycle/subject/trace write, offline package or public routing change. #600 owns start
composition, #524/#526 delivery, and #609 public activation and compatibility conformance.

### Precomputed form

All selected questions and option orders are resolved at start.

Suitable for:

- ordinary practice;
- randomized tests;
- section-based assessments;
- offline-eligible full forms.

### Progressive form

The form grows during execution.

Suitable for:

- adaptive selection;
- branching scenarios;
- some mastery workflows.

```text
AttemptForm
├── mode: PRECOMPUTED | PROGRESSIVE
├── formVersion
├── formHash
├── itemSequence
├── sectionMembership
├── caseMembership
├── optionOrder
├── selectionEvidence
└── algorithmVersions
```

Every progressive extension is persisted before the item is delivered.

A progressive decision may record:

```text
selectedItem: question-revision-219
strategyVersion: adaptive-rules/v2
reasonCode: TOPIC_MASTERY_BELOW_THRESHOLD
candidateSetFingerprint: ...
precedingSubmission: answer-submission-18
```

The reason code should explain the category of decision without exposing protected internal data to participants.

---

## Sections and Case Blocks

### Immutable section storage (#551)

`QuizRevision` format 1 remains the original flat aggregate. Format 2 explicitly carries
ordered `RevisionSection` values: stable identity, section schema version 1, authored position,
title, optional instructions and exact revision-item identities. Titles/instructions use the
existing revision metadata bounds of 100/1000 characters. Authored positions may have gaps;
they are preserved, not renumbered. Every item belongs to exactly one section, and concatenating
the sections in authored order reproduces the revision's authored item order. Empty structural
sections are representable; this does not establish executable eligibility or permit an empty attempt.

V110 adds nullable `section_count` to `quiz_revisions`, permits the explicit format 2, and adds
`quiz_revision_sections` / `quiz_revision_section_items`. The existing revision repository inserts
the entire aggregate in one transaction. Section and member ordinal slots are bounded by their
declared counts and unique within the parent. A complete aggregate has no free slots for later
insertion. Composite foreign keys reject cross-revision membership. Triggers reject section/member
updates/deletes and format-2 root updates/deletes, including downgrade to format 1. Supported ports
cannot attach sections to an existing flat revision. A later author edit uses a new revision ID;
it does not mutate an earlier publication or its retained bindings. This is internal persistence
for an already authorized publication caller, not a new publication HTTP command or permission grant.

`QuizRevisionStructureService` is the internal author-side adapter. Its owner ID must come from
the caller's authenticated publication/author boundary, never a request field. Missing and
wrong-owner revision reads both return absence. A known flat revision maps to one `IMPLICIT_FLAT`
section with exact item order and a stable ID derived only from the revision UUID. It invents no
title or instructions and persists no backfill. A missing historical revision reference returns
`LEGACY` with no fabricated revision, sections or mutable-authoring lookup. Neither adapter output
grants participant delivery access. Section structures contain no copied answers, question payloads,
contacts or case stimulus. Metrics expose only bounded version/outcome labels.
Repository read failures discard driver diagnostics before transaction interception; the service's
bounded outward error alone is insufficient to protect nested transactional logs.

Reads batch authorized roots before loading sections/membership. Multiple structured parents in
one repository batch use five physical SQL queries including questions/options; flat-only reads
retain three. Current shared reads preserve caller transaction/isolation semantics, including an
older `REPEATABLE_READ` snapshot. Missing slots/members, inconsistent counts, unknown versions and
invalid partitions fail closed rather than falling back to implicit-flat structure. Administrator
corruption is not repaired or reinterpreted. No document content is read or hashed for identity.

Existing publication/participant REST contracts stay unchanged. Current canonical policy/start
contracts accept revision format 1; flat materialization and participant projection also reject
structured content explicitly. Case blocks #552, section overrides #553, section-aware forms #554,
author/participant APIs #632 and runtime/timers #659/#660 retain their separate scopes and activation
gates. This storage slice does not activate any of them or change retention/erasure policy.

The migration does not reclassify existing attempts or rewrite flat revision content. Before
format-2 writes, unused code can be disabled. After such writes, retain this schema/evidence and
use an additive forward fix; an old reader must reject format 2, not treat it as a flat revision.

### Immutable case storage (#552)

Revision format 3 adds ordered `RevisionCaseBlock` values to the existing complete revision
aggregate. Formats 1 (flat) and 2 (sections without cases) retain their storage semantics.
Each case records its stable ID, case schema version 1, exact section ID, authored position,
opaque immutable stimulus-revision ID, explicitly supplied atomicity and nonempty ordered
revision-item IDs. Case positions are unique within a section; canonical case order follows
section order and then case position. Member order is retained exactly, including authored
order different from the enclosing section's item order. Members must belong to that exact
revision and section, and an item cannot belong to two cases. Ordinary items outside cases
remain representable. Atomicity is stored evidence, not new runtime selection eligibility.

The stimulus ID is a reference, not stimulus bytes, a mutable document URL or a promise that
the content has been resolved. This slice neither ingests stimulus material nor grants access
to it. An authorized future author/content boundary must supply the actual immutable source
and its applicable access/retention evidence before content delivery can be activated.
`QuizRevision` is the internal author-side value; `RevisionStructure.CaseBlock` omits the
stimulus reference and all question/answer payloads. Neither representation authorizes
participant reads. #554/#632/#615/#711 retain their materialization, API and runtime proof.

`QuizRevisionCaseService` is an internal interface for an already authorized publication
caller. It requires trusted owner context matching the aggregate, reads current evidence
and inserts the entire aggregate through `QuizRevisionRepository`. An identical committed
replay is read-only; the same identity with different content conflicts. Concurrent missing
publications may return a bounded conflict/storage failure to a losing caller; a later
same-input retry reads the winner rather than rewriting it. There is no automatic retry
policy. The service joins an enclosing transaction, so a failure in a consumer's subsequent
reference write also rolls publication back. It does not replace #700's authoring command,
advance a current pointer, activate a route or create an attempt.

V111 adds nullable `case_count` on revision roots and case/member tables. Composite foreign
keys enforce both case-section and actual section-item membership. Counts, unique ordinal
slots and immutable triggers seal complete aggregates against updates, deletion, downgrade
and late insertion. There is no backfill, cascade deletion or change to retention policy.
After format-3 writes, retain the schema/evidence and recover through an additive forward fix.
MySQL DDL is not a multi-statement transaction: a failed migration stops startup; inspect its
actual committed DDL and Flyway state before an explicit recovery. No automatic repair or
destructive downgrade is introduced.

Reads batch authorized roots and use seven physical SQL queries per case-bearing root batch
(at most 500 requested root IDs), independent of case/member count. Flat-only and section-only
batches retain three and five respectively. Current shared reads include all case/member rows
while retaining the caller's isolation and older nonlocking snapshot. Missing counts/slots,
invalid bindings and noncanonical persisted order fail closed. Read and format-3 write
diagnostics are sanitized inside the repository before transaction interception. The bounded
`quiz.revision.case.publish` outcome counter describes boundary execution, not an enclosing
caller's eventual commit. No full document read or digest is used for version comparisons.

Sections deserve explicit revision and form models.

```text
Section
├── title and instructions
├── ordered items or blocks
├── selection override
├── ordering override
├── timing override
├── navigation override
├── grading threshold
├── disclosure override
└── completion rule
```

Examples:

- untimed theory followed by timed practice;
- fixed first section and randomized second section;
- next section opens only after passing the previous section;
- first-section feedback is released before the next section;
- mandatory and optional sections;
- separate pause rules by section.

A case block contains shared stimulus material and multiple related questions.

Atomic case blocks must never be partially selected unless the authored block explicitly allows it.

Section policy precedence must be explicit:

```text
Global resolved policy
    overridden by permitted section policy
        constrained by immutable question/case rules
```

---

## Delivery

Candidate delivery modes:

```text
ALL_AT_ONCE
SECTION_AT_ONCE
ONE_AT_A_TIME
PREFETCH_NEXT
PROGRESSIVE
```

The delivery policy defines:

- whether future questions may be fetched;
- whether future question IDs are visible;
- how many questions may be prefetched;
- whether the full participant-safe form is downloadable;
- when an item becomes “delivered”;
- whether repeated retrieval changes delivery state;
- what happens after reconnect.

The server should distinguish:

```text
selectedFormItemIds
deliveredFormItemIds
currentFormItemId
submittedFormItemIds
lockedFormItemIds
```

Selection does not automatically mean delivery.

Delivery may have security, timing, or exposure consequences.

### Persisted flat delivery (#524)

`AttemptDeliveryService.read` composes real #600 start evidence, #537 current participant access,
#522 fixed form/order, persisted question-contract bindings and #528 lifecycle state. The additive
`GET /api/v2/attempts/{attemptId}/form` adapter is absent unless
`quizmaker.attempts.flat-delivery-enabled=true`; public activation remains gated by #609. Existing
attempt routes, immediate practice feedback and legacy envelopes keep their behavior.

Only active, text-only, implicit-flat `ALL_AT_ONCE` / `PRECOMPUTED` forms are supported. The five
baseline families are `MCQ_SINGLE`, `MCQ_MULTI`, `TRUE_FALSE`, `FILL_GAP` and `ORDERING`. The response
contains stable form-item/option IDs and saved order, participant-safe content, `stateVersion`
and server-derived `allowedActions`. Reconnects and subsequent author publications return the
original saved form. This is an online-only read with `Cache-Control: no-store`; it neither creates
delivery state nor supplies an offline package. It exposes no keys, correctness, explanations,
private source references or raw author JSON. Client include flags do not expand disclosure.

The service holds current access and contract locks until its read transaction finishes. Access
requires the bound participant, `ATTEMPT_READ` and current ownership/full-editor authority under
the [authenticated self-attempt access decision](./decision-register.md#authenticated-self-attempt-access).
The locked lifecycle returned by #537 supplies the version/state, even inside an older caller
snapshot. Delivery also reads the policy snapshot, form roots/items and option-order roots/items/options
through explicit current shared reads. This includes attempts committed after the caller established
its `REPEATABLE_READ` snapshot: current authorization must not be followed by stale content reads.
Question guards precede contract-binding locks, and shared read locks allow simultaneous readers.
The operation still joins the caller transaction; it neither changes its isolation nor opens an
independent delivery transaction. Existing ordinary repository reads retain their snapshot semantics.
Missing accepted-response and completion facts remain unknown: #523 currently derives
only `VIEW_ATTEMPT`. Real answer/navigation/completion consumers (#543/#525/#529) must supply and
verify their facts before advertising their actions; this increment does not claim those integrations.

The named target DTOs use a discriminated content union in the `attempts-v2` OpenAPI group and API summary.
Failures return RFC 7807 without partial form content: `400` malformed ID, `401` unauthenticated,
uniform `404` absent/foreign/legacy/ineligible roots (including unknown root versions), `409`
inactive/busy, `422` unsupported saved delivery contract, `500` corrupt evidence and `503` storage
unavailability. Reads never regenerate, rebind, repair or fall back to mutable quiz content.
The `quizmaker.attempt.delivery` timer has fixed version/outcome tags only; `delivered` includes
successful resume. Telemetry failure cannot change the result.

`AttemptDeliveryMySqlIntegrationTest` publishes and binds real five-family content, starts through
#600 and reads through the service and HTTP adapter. It verifies both ordering policies, service
recreation, republishing, access loss, corrupt/versioned evidence, fresh lifecycle reads, storage
failure and a real permission-revocation race during projection. Regressions establish the caller's
snapshot before a real independently committed start, with publication both before and after the
snapshot, under `READ_COMMITTED` and `REPEATABLE_READ`. They compare the complete resumed view,
require a successful caller commit, prove simultaneous reads and preserve rollback of an outer
write after a caught delivery failure. It measures the complete read
for three independent participants/quizzes with one versus twenty questions: 57 SELECTs in either
case, with no authoritative writes. Lifecycle terminal-state fixtures demonstrate read behavior,
not the separately owned terminal command. MVC tests use the real security chain and verify typed
JSON, errors, examples, full/grouped OpenAPI, discovery and default-disabled activation.


### Canonical participant view (#526)

`GET /api/v2/attempts/{attemptId}` is owned by `ParticipantAttemptViewController` and
`ParticipantAttemptViewService`, registered only with
`quizmaker.attempts.participant-view-enabled=true`. The default remains disabled. Its
concrete response is `ParticipantAttemptView` (`representation: POLICY_DRIVEN`,
`schemaVersion: 1`); HTTP v2 does not change persisted policy/contract versions.

`GET /api/v1/attempts/{attemptId}` always uses the legacy `AttemptService` and returns
`AttemptDetailsDto`, including its existing answers and nullable `completedAt`. It gains
no target-only fields and never delegates to the target reader when flags change. The
v2 reader rejects legacy IDs, unknown/incomplete target roots and mixed identity; it never
calls legacy answer readers. Existing identity resolution, owner checks and current-root
probes remain in place, with their existing transaction/isolation and lock behavior.

The target view uses the same `AttemptReadFactsService` as #524. It holds current access,
lifecycle, policy, form/order, revision and contract evidence through projection, including
inside an older `READ_COMMITTED` or `REPEATABLE_READ` caller transaction. `/form` keeps its
active-only, text-only behavior. The view adds immutable quiz/revision/form IDs, current
execution/answer-collection state, state/policy versions, original UTC start/submission times,
and the saved item/option order. The supported policy is untimed; `timing: UNTIMED` does not
invent a deadline. Actions come from #523 with unknown item/completion facts, currently only
`VIEW_ATTEMPT`. Reads neither create delivery/answer state nor grant more time.

For example, reconnecting after an author republishes a changed question still shows the
participant's original saved text and order. A committed submission changes `execution` and
`stateVersion`, but terminal status alone is not review-release proof: the default disclosure
reader returns `WITHHELD` and omits `items`. Keys, submitted responses, scores, explanations
and private provenance are structurally absent from the target DTO, regardless of query flags.

`ParticipantViewDisclosureReader` and `ParticipantViewMediaReader` are explicit boundaries
for the separately owned current release/display decisions. The media reader takes the exact
saved question/asset references in one batch. A released completed projection retains permitted
text and represents each top-level attachment as `AVAILABLE` with its original permitted URL,
`UNAVAILABLE` without a URL, or `RESTRICTED` without a URL. Family contracts explicitly opt in
to text projection with a separately represented attachment; embedded media is still unsupported.
Corrupt/missing form evidence fails before either reader can authorize partial content.
The shipped media adapter rejects media with `422`, rather than pretending missing integration
is a storage outage. Active-media failure from a typed reader rejects the whole read with `503`;
it cannot pause a timer, create a grade or perform technical cancellation.

Target responses use `Cache-Control: no-store`; the schema is online-only, with no pagination,
filters, sorting or idempotency key. The route belongs to `attempts-v2`; legacy stays in
`attempts`. Both groups are discoverable through `/api/v1/api-summary`.
Target RFC 7807 failures are `400` malformed ID, `401` unauthenticated, uniform `404`
absent/foreign/ineligible/unknown root version, `409` lock contention, `422` unsupported saved
contract, `500` corrupt evidence and `503` dependency failure. Exceptions contain no private
causes or partial view. A caught failed read still marks an enclosing transaction for rollback.
The `quizmaker.attempt.view` timer uses only fixed `version`/`outcome` tags and cannot affect
success or denial. No new migration, dependency, command, retention or cleanup behavior is added.

Automated evidence combines actual publication/binding/#600 start and HTTP reads, legacy writers
for all nine question families and all three modes, real permission-revocation contention,
concurrent readers, older caller snapshots, service recreation, corrupt/future-version evidence,
rollback after injected SQL failure, and full/grouped OpenAPI examples. Complete target reads
and serialization use 66 SELECTs for three independent participants/quizzes with either one or
twenty questions; legacy HTTP mapping uses six JPA statements for three parents with either one or
twenty accepted answers after #904. The v1 reader no longer performs target route-selection probes.

The `PARTICIPANT-VIEW-MEDIA` tests use real persisted publication/image bindings, form, lifecycle
and trace evidence plus typed disclosure/availability doubles. They prove DTO projection after
service recreation, current restriction changes, omitted restricted URLs, and unchanged evidence;
they do not prove media start, storage/restriction integration (#818/#831), real completed-review
release (#556), or real timed behavior (#668/#711). Those capabilities and public activation
(#609) retain their separate gates.

---

## Navigation

For the existing `ONE_BY_ONE` compatibility mapping, the default remains sequential answer acceptance without skipping. An explicitly authorized configuration permits skipping and returning to unanswered items. A skip creates no accepted answer and releases no pre-acceptance feedback; returning cannot edit an already finalized accepted answer. Preserve the form order, delivered items, response/confirmation states and durable navigation state across reconnects. The flexible configuration cannot infer the current item solely from accepted-answer count.

The [additional approved configurations](./decision-register.md#answer-finalization-and-review) support both read-only revisit and prohibition of revisit for finalized items, alongside provisional responses editable until final attempt submission. Navigation, revisit, finalization and disclosure are separate typed dimensions. Free navigation grants neither response editing nor protected feedback. A no-revisit rule limits subsequent server retrieval; it cannot erase question content or feedback already delivered to the client. A prohibition on revisiting finalized items does not by itself prohibit returning to an unanswered pending item. Post-completion review follows its own policy.

Candidate modes:

```text
FREE
SEQUENTIAL
SEQUENTIAL_WITH_BACKTRACK
SECTION_LOCKED
BRANCHING
```

Parameters may include:

- skip allowed;
- return to skipped item allowed;
- answer required before next;
- submitted answer editable;
- previous section locked;
- bookmark allowed;
- complete with unanswered items allowed;
- completion confirmation required;
- current item fixed until submission;
- direct item retrieval permitted.

The frontend must not determine navigation validity from its local array alone.

Every request is checked against authoritative server state.

---

## Server-Reported Allowed Actions

The participant representation should include actions computed from:

- resolved policy;
- attempt state;
- answer state;
- form state;
- timing state;
- participant subject;
- assignment and capability state.

Example:

```json
{
  "attemptState": "PAUSE_PENDING",
  "allowedActions": {
    "canViewCurrent": true,
    "canSaveDraft": true,
    "canSubmitCurrent": true,
    "canRequestNext": false,
    "canGoBack": false,
    "canSkip": false,
    "canPause": false,
    "canResume": false,
    "canComplete": false,
    "canReview": false
  }
}
```

This reduces client duplication of policy logic.

The server still validates every requested action. `allowedActions` is not an authorization token.

### Implemented initial command decisions (#523)

`AttemptCommands` is a pure, dark v1 contract consuming `ResolvedAttemptPolicy` (#515) and
`AttemptLifecycle` (#527). It has five initial participant commands: `START`, `VIEW_ATTEMPT`,
`NAVIGATE_TO_ITEM`, `SUBMIT_ANSWER` and `SUBMIT_ATTEMPT`. Its values have no Spring, HTTP,
database, clock, provider or legacy-mode dependency. No existing route calls this contract.

The following table assumes current verified access, supported versions, a fresh command at the
current optimistic state version, a delivered member item with no accepted answer, confirmed
completion and require-all OFF. View means a safe participant view, never an answer-key/review grant.

| Execution / answer collection | Start | View attempt | Navigate to item | Submit answer | Submit attempt |
|---|---|---|---|---|---|
| `CREATED / NOT_STARTED` | Allow | Not started | Not started | Not started | Not started |
| `IN_PROGRESS / NOT_STARTED` | Already started | Allow | Allow | Allow | Allow |
| `IN_PROGRESS / IN_PROGRESS` | Already started | Allow | Allow | Allow | Allow |
| `SUBMITTED / COMPLETE` | Terminal | Allow | Terminal | Terminal | Terminal |

`CREATED` remains a hypothetical pre-start value, not permission to persist an ambiguous-launch
placeholder. Start, response activity, terminal guards and replay-state checks delegate to #527;
the command layer does not introduce another transition table or perform its proposed writes.

- Each consumer supplies current identity/access checks, immutable policy and lifecycle facts,
  the authoritative optimistic counter, and exact target membership/delivery/response facts.
  `Access.VERIFIED` is a trusted server precondition, never a participant-provided flag. It covers
  all applicable permission, ownership, organization, visibility and subject boundaries. Missing
  or denied access is checked before protected facts and always returns public `DENIED` with no
  actions, even for stale, conflicting or previously committed commands.
- Navigation can revisit an accepted item; a fresh answer command cannot rewrite it. Non-members,
  undelivered/missing items and contradictory response facts do not grant item actions. Draft,
  editable and pending-confirmation item commands are outside this initial immediate-lock slice.
- Final submission requires every form item in saved order through trusted `CompletionFacts`.
  Captured require-all, immediate-lock finalization and completion scopes must match policy.
  `IMPLICIT_FLAT` requires `APPLICABLE` for every item, including already accepted responses and
  policies with require-all OFF. A `PERMANENTLY_CLOSED` scope returns `POLICY_FACTS_MISMATCH` and
  removes `SUBMIT_ATTEMPT` from allowed actions; #527's closed-section exception does not apply
  to a flat form. #527 applies confirmation
  and completeness: two unanswered items are permitted with require-all OFF and denied with ON.
  This contract accepts no answer payload and neither finalizes editable work nor releases results.
- Stateful commands require server-resolved metadata: expected optimistic version, an internal
  operation/receipt UUID and verified command identity. That UUID does not prescribe a transport
  idempotency-key format. #599 must prove operation, subject/context, target and canonical-input
  identity. `FRESH` with a different version returns `STALE`; conflicting reuse returns `CONFLICT`.
  `VERIFIED_IDENTICAL` can return no-effect `REPLAY` despite a later state version, without
  reevaluating new completion payload. The receipt still owns the original acknowledgement.
  Navigation replay requires a started state; lifecycle events use #527's exact replay table.
  `VIEW_ATTEMPT` is read-only and needs no mutation metadata or receipt.
- `allowedActions` is a deterministic, immutable list in command-vocabulary order, using the same
  fresh eligibility function as evaluation. It reflects the supplied item/completion context and
  currently satisfied guards; it is not a promise that a later command/version remains eligible.
  A caller must reevaluate every actual command. Client mode/status/allowed-action assertions are
  absent from the command input and cannot grant authority.
- Public `Acknowledgement` exposes only `ALLOWED`, `REPLAY`, `DENIED`, `STALE` or `CONFLICT`.
  `Decision` and its bounded reason/lifecycle reason are internal diagnostics and must not be
  serialized into participant responses. No existence, resource IDs, version, response or hidden
  content is included in the acknowledgement. HTTP status/ProblemDetail mapping belongs to the
  consuming endpoint; this foundation defines no new public HTTP contract.

Unknown names/schema versions fail closed with bounded, content-free errors. Typed policy and
lifecycle constructors retain their own strict version/tuple validation; no adapter may replace
invalid input with a current default. `Command` itself can represent an unsupported version/null
type so evaluation returns a denial. The broader command catalogue above/below does not enable
drafts (#541/#542), hints (#687), batch handling (#629), review/release (#555/#556), timing,
pause, sections, assignments or operator actions. Those capabilities need their own contracts
and real integrations; a vocabulary/parser fixture is not runtime evidence.

`AttemptCommandsTest` carries the explicit 20-row fresh table, 16-row verified replay table,
policy/completion/item guards and projection consistency across require-all, option order,
confirmation and response-state variations. Eight closed-scope regression cases explicitly assert
both command denial and absence of the completion action, with applicable-scope positive controls.
`CommandBoundaryTest` proves non-enumerating denial,
strict parsing, lack of client authority, immutable values and isolation from legacy wiring.
These are local contract tests, not receipt durability, authentication, HTTP or transaction proof.
#599 owns durable receipts; #528 owns state; #537/#600 own actual access/start composition;
#543/#529 own response/terminal writes; #526 owns participant mapping and #609 activation.
No persistence or relationship-loading path changes, so N+1 does not apply. No document content
is read/hashed, no new telemetry/retry/retention policy is added, and current API/training behavior
remains on its existing path.

---

## Answer Lifecycle

Candidate answer states:

```text
EMPTY
DRAFT
SUBMITTED
LOCKED
GRADED
REVIEWED
```

Not every question needs every state, but the architecture must preserve the distinction between mutable work and accepted submission. This candidate list is not an exhaustive enum: the approved editable-response and pending-confirmation capabilities require explicit response/confirmation intent as described below.

### Draft

A draft:

- may be overwritten where allowed;
- is not graded;
- does not trigger feedback;
- does not count as a submitted answer;
- may be returned only to the same authorized subject.

#### Implemented private draft storage (#541)

`AnswerDraftStore` is an internal persistence boundary, separate from draft commands and
immutable accepted answers. V99 introduced `attempt_answer_draft` without public commands,
autosave, cleanup or historical backfill. The opt-in #542 commands are described below. It reuses the delivered
canonical start's authenticated subject, lifecycle and trace/form binding, and the
materialized item's exact question/response-contract version. Current ownership of a
quiz does not grant access to another participant's draft.

The natural key is `(attempt_id, form_item_id)`; the attempt's immutable subject binding
supplies participant identity. Expected version zero means no prior record. Each successful
save or delete increments the version. Deletion removes the response value and retains a
versioned tombstone; recreation uses that tombstone's version. Thus a delayed delete or
save cannot overwrite work saved after deletion/recreation. An absent record and a deleted
value are distinct. Retention/physical cleanup remains #628/#699, not an age-based worker here.

The store validates bounded normalized JSON against the exact saved response contract
(existing v1 limit: 65,536 UTF-8 bytes), rejects writes to accepted items or non-active
attempts, and joins the caller's transaction. Concurrent mutations serialize with the
canonical acceptance/submission lifecycle lock and use a database version precondition.
Current reads retain their meaning inside an older REPEATABLE READ snapshot. Errors and
telemetry omit values and subject/resource identifiers; storage outcome counters and draft
age in seconds use bounded labels. A database/commit failure can leave an uncertain outcome:
reread the persisted version before deciding whether a mutation committed; do not infer absence.

Trusted callers remain responsible for authenticated identity, permissions, current content
access and the enabled policy/section rules. #542 owns public save/delete commands; #834 owns
ordinary editable-response versus pending-confirmation intent and atomic finalization. Tests
calling this internal store under an allowed-state fixture do not activate those capabilities
or prove their integration. In particular, a stored value neither advances lifecycle nor
creates acceptance, grading, terminal receipts or results. The default immediate-acceptance
practice path continues to use its existing writer and feedback rules.

#### Canonical online draft commands (#542)

`DraftCommandService` owns save/delete for authenticated, untimed, flat `ALL_AT_ONCE`
attempts. The internal `AttemptStartService.Command` has an explicit `draftsEnabled` choice;
its old constructor still means false. After current owner/full-content-editor authorization,
start captures the choice through an AUTHOR-only typed resolver override. Participant overrides
cannot enable it. Existing preset values, disabled-start fingerprints, historical snapshots and
active attempts remain unchanged. Automatic client autosave scheduling remains disabled.

The additive HTTP contract is `PUT`/`DELETE /api/v2/attempts/{attemptId}/drafts/{formItemId}`,
registered only with `quizmaker.attempts.draft-commands-enabled=true` (default false).
The registration flag never grants draft permission to an existing policy. Both commands
require `Idempotency-Key`, expected attempt version and expected draft version. PUT carries
the versions and a named five-family response union in JSON; DELETE uses required
`expectedStateVersion`/`expectedDraftVersion` query parameters and no request body.
Stable option UUIDs are normalized through the same saved response contracts as acceptance.

Every request, including replay, checks the current participant, `ATTEMPT_CREATE`, resource
authority and immutable target binding. Fresh commands additionally check saved draft policy,
active lifecycle, delivered form membership, accepted-answer lock and both versions. The attempt
version/lifecycle do not advance. Saving a draft never accepts, grades or releases its value.
The default immediate-lock acceptance and feedback behavior is unchanged.

Draft mutation, content-free `DRAFT_SAVED`/`DRAFT_DELETED` trace and command receipt/result
commit together. V100 expands existing command/event allow-lists and adds the immutable
`attempt_draft_command_result` receipt extension containing only references, draft version and
PRESENT/DELETED status. It follows receipt deletion via its foreign key; no new retention job
or answer-history deletion is introduced. Replay returns the original acknowledgement metadata,
not a newer draft's version or value. Unknown commit outcomes require the exact same key/request;
no automatic retries, alternative writer or inferred absence is allowed.

Each successful new save/delete increments the draft version. Deleting a never-saved item
with expected version zero creates DELETED version one, blocking delayed first saves at zero.
A repeated identical command replays; a new command key must carry the current version even
when the current value is a tombstone. For example, deletion at zero wins over a delayed first
save, while an intentional later save using version one can create version two.

The acknowledgement contains attempt/item references, original attempt/draft versions, status
and replay flag, without response content. Expected failures use RFC 7807: 400 malformed input,
401 unauthenticated, non-enumerating 404, 409 conflict/stale/inactive, 422 invalid/unsupported,
500 inconsistent evidence, and 503 unavailable/retryable/unknown outcome. Responses are no-store.
Both target operations belong only to the `attempts-v2` OpenAPI group and API summary discovery.
Bounded command outcome metrics and sanitized exceptions omit values and idempotency keys.

The real MySQL/HTTP command tests use authorized publication and opt-in start, not fabricated
policy rows or a fake finalizer. They cover stable replay after later changes/completion,
save/delete and identical-command races, reached rollback checkpoints, lost commit acknowledgement,
fresh service instances, current-access denials, five response families and bounded SQL.
MVC/OpenAPI tests separately cover the security chain, strict payload parsing and full/grouped
schemas/examples. #834 retains editable-response/proposal conversion and edit-versus-close
conformance; #628 cleanup, #581 offline replay, #526 presentation and production activation
remain their own capabilities.

Apply V100 before enabling this capability. Older binaries cannot interpret its new command/event
vocabulary; after target writes, preserve the additive schema/evidence and use a forward fix.

The multi-parent SQL fixture measures three attempts with one versus twenty questions per
form: save executes 120/120 SELECTs and delete 123/123, including authorization and receipt
reads. No new JPA relationship traversal is introduced by receipt metadata persistence.

### Provisional responses and pending confirmation

The [21 October owner decision](./decision-register.md#answer-finalization-and-review) approves a configuration where an ordinary participant response remains editable until final attempt submission. A separate [approved configuration](./decision-register.md#section-answer-finalization) permits editing until section submission. Such work is provisional until the applicable finalization boundary; a saved payload is not proof of immutable acceptance. The existing immediate-lock default continues to finalize each individually confirmed answer.

An explicit pending-confirmation mark has stronger semantics than a bookmark. It may retain a proposed answer, but the item is still unanswered until explicitly confirmed. Before final completion, the confirmation flow presents pending items with their saved proposals. Opening that flow, navigating, autosaving or submitting the attempt does not itself confirm them. If completion proceeds while a mark remains, the item stays unanswered and no accepted incorrect answer is fabricated. An enabled [require-all completion policy](./decision-register.md#voluntary-completion-requirement) prevents voluntary submission while such items remain in applicable non-closed sections; the requirement is disabled by default and never blocks authoritative expiry.

Persist the proposal and confirmation state for durable resume and apply the same server-side rules to single, batch and finalization operations. Never turn a finalized accepted answer back into an unanswered item by applying a mark. Exact state representation and finalization command composition require engineering preparation; ordinary ready-to-finalize work must remain distinguishable from an autosaved draft or a pending-confirmation proposal.

### Section finalization boundary

With [editing until section submission](./decision-register.md#section-answer-finalization), provisional responses remain editable while their section is active and its saved timing/navigation rules permit it. Present the section's pending-confirmation proposals before voluntary submission. Successful `SubmitSection` finalizes eligible ordinary responses and atomically locks the section under R17; afterwards neither an existing answer nor a previously empty/pending item may be changed or confirmed. The [saved require-all setting](./decision-register.md#voluntary-completion-requirement), disabled by default, determines whether voluntary submission with unanswered items is allowed.

Permitted later views are read-only and release-filtered. Reload, a later section or final attempt submission cannot reopen the section, and the final attempt overview must not offer edits or confirmation of its abandoned pending proposals. Editing until final attempt submission is compatible with sections used only for grouping, without intermediate irreversible submission. The policy validator must reject a combination promising that editing window while requiring an earlier section lock. Finalization does not automatically release feedback. The [saved section-timeout policy](./decision-register.md#section-timeout-scope) determines whether expiry closes only the section or the entire attempt.

### Voluntary completion requirement

The [approved require-all setting](./decision-register.md#voluntary-completion-requirement) is resolved in the saved policy and disabled by default. When disabled, voluntary section/attempt submission may proceed after explicit warning/confirmation of unanswered items. When enabled, every question in the applicable completion scope needs an answer, with the [approved exception for already permanently closed sections](./decision-register.md#closed-section-completeness). In other applicable sections, an empty item or a pending-confirmation proposal is insufficient. An eligible ordinary provisional response can satisfy the check through finalization in the successful completion operation; prior individual irreversible acceptance is not required in an editable configuration.

Rejecting incomplete voluntary submission must leave answers, section state and attempt lifecycle unchanged, without partial acceptance or a terminal result. Existing time continues to run. Authoritative expiry bypasses this voluntary-completion requirement: ordinary saved responses finalize under the approved expiry rule, while marked proposals remain unanswered. It never extends the deadline or grants disclosure beyond the saved policy.

The [22 October owner refinement](./decision-register.md#closed-section-completeness) excludes permanently closed sections from blocking final attempt submission. Their omissions remain unanswered with the original form membership and grading rules; no answers are invented and no closed section is reopened. Closure must be authoritative server lifecycle state, not a client flag, temporary access restriction or a not-yet-started section. The requirement continues to apply in other applicable sections. For example, eight answers plus two omissions in a closed first section and ten answers in the second permit final submission with eighteen answered and two unanswered items; an unanswered item in a still-open second section continues to block voluntary submission when require-all is enabled.

### Submitted answer

A submitted answer:

- has passed response-contract validation;
- has an accepted server timestamp;
- is aligned to one immutable form item;
- may trigger grading;
- may trigger disclosure;
- is immutable once finalized; subsequent navigation cannot reopen it.

### Answer change policy

The default practice contract locks an accepted answer immediately and allows no within-attempt retry ([#687 D12](https://github.com/Gegcuk/QuizMaker/issues/687)). Additional approved configurations allow provisional responses to change until [section submission](./decision-register.md#section-answer-finalization) or [final attempt submission](./decision-register.md#answer-finalization-and-review). They change when the response becomes final, not the immutability of finalized evidence or published quiz/question revisions. They do not authorize answer retries after protected feedback.

The following names describe policy dimensions, not a declaration that every combination is implemented or approved. Editing until section submission and until final attempt submission are approved additional capabilities, alongside [ordinary-response finalization at expiry](./decision-register.md#editable-response-expiry), [require-all completion with closed-section handling](./decision-register.md#voluntary-completion-requirement), [section-timeout scope](./decision-register.md#section-timeout-scope), [automatic completion at last-unclosed-section expiry](./decision-register.md#last-unclosed-section-expiry) and [clock activation with separate delivery, including immediate start when breaks are prohibited](./decision-register.md#section-start-and-break). Remaining command composition and compatibility work stays in [open questions](./open-questions.md#oq-answer-finalization). Existing defaults and section locks retain their contracts.

```text
UNTIL_SUBMISSION
UNTIL_NEXT_QUESTION
UNTIL_SECTION_SUBMISSION
UNTIL_ATTEMPT_SUBMISSION
NEVER_AFTER_SUBMISSION
```

### Retry policy

Candidate rules:

```text
NONE
FIXED_COUNT
UNTIL_CORRECT
UNTIL_SECTION_END
```

Retry attempts must remain distinguishable from attempt-level retakes.

### Locking after feedback

Under the approved scored-answer contract, the answer must be finalized and locked before protected feedback is disclosed. A response that remains editable receives no correctness, correct answer or protected explanation; delayed finalization therefore delays those categories. A revisit permission does not override disclosure.

Otherwise a participant could:

1. submit an answer;
2. observe correctness or the correct answer;
3. change the answer;
4. receive a score based on disclosed information.

An explicitly unscored learning activity would require a separately approved feedback/retry contract for different behaviour; the new editable-response capability does not approve that exception.

---

## Answer Submission, Idempotency, and Concurrency

All mutating commands require defined idempotency behaviour.

Canonical rule:

- same idempotency key and same semantic payload returns the previous result;
- same key and different semantic payload returns conflict;
- a retry never grants more disclosure than the original successful operation.

Submission must support:

- single answer submission;
- batch submission where approved;
- atomic answer submission plus transition request;
- optimistic version checking;
- duplicate request handling;
- two-device conflict handling;
- deterministic partial-batch semantics.

A batch must not apply looser policy than individual commands.

### Dark single-response acceptance (#543)

`ResponseAcceptanceService` composes the real saved form/contract readers, #523 guards, #528
lifecycle, #538 trace and #599 receipt transaction. The additive
`POST /api/v2/attempts/{attemptId}/responses` adapter is absent unless
`quizmaker.attempts.response-acceptance-enabled=true`; normal configuration leaves it disabled.
It accepts `formItemId`, `expectedStateVersion`, a named five-family `response` union and the
existing exact `Idempotency-Key` identity. Authentication, `ATTEMPT_CREATE`, bound participant
identity and current owner/full-question-editor authority are required, including on replay.
The route belongs to the Attempts OpenAPI group and API summary. It does not require the
separate `ATTEMPT_READ` permission to answer.

The supported slice is the existing authenticated, untimed, flat, text-only `ALL_AT_ONCE`
practice policy with `LOCK_ON_ACCEPTANCE`. Stable option UUIDs resolve against the frozen
question revision; display position and mutable authoring content cannot select an answer.
`MCQ_SINGLE`, `MCQ_MULTI`, `TRUE_FALSE`, `FILL_GAP` and `ORDERING` use their actual v1 validators.
Multi-choice selections normalize as a set; ordering preserves sequence; gap entries sort by
gap ID without rewriting answer text. Empty arrays retain the frozen family semantics.
The new HTTP parser rejects duplicate properties, unknown fields, scalar coercion and malformed
UUIDs without changing legacy JSON parsing. Arrays are bounded to 100 entries, gap answer text
to 10,000 Unicode code points, and normalized response JSON to 65,536 UTF-8 bytes.

V95 stores immutable learner evidence in `attempt_accepted_response`, including attempt/form/item,
question revision, response contract/version, normalized payload, command identity and acceptance
time. Natural uniqueness on attempt/item survives receipt expiry. Composite foreign keys bind
the form item to its actual question and attempt stream. There is no receipt foreign key,
legacy backfill or cascade. Existing erasure/retention ownership remains with #664; successful
target writes require forward-fix recovery rather than destructive downgrade.

The command serializes on the attempt lifecycle before shared content/access reads. Accepted
row, lifecycle version, `ANSWER_SUBMITTED` trace and receipt acknowledgement commit together.
Identical retries return the original accepted-response reference and version; stale versions,
changed same-key input and a new key for an already accepted item cannot overwrite evidence.
The acknowledgement exposes only `attemptId`, `acceptedResponseId`, `stateVersion`, `replayed`.
Both success and errors use `Cache-Control: no-store`; errors are bounded RFC 7807 bodies:
400 malformed input/key, 401 unauthenticated, 404 inaccessible attempt/item, 409 conflict/stale/
inactive/busy/expired, 422 invalid response/unsupported contract, 500 corrupt saved evidence,
503 unavailable/retryable/unknown transaction outcome. An uncertain outcome is reconciled with
the exact same command in a fresh transaction; there are no automatic retries. Metrics contain
only bounded version/outcome labels, never answer text, identifiers or keys.

For example, losing the network acknowledgement after confirming a shuffled choice and retrying
with the same key returns the same acceptance; it neither creates a second answer nor interprets
the choice as its new display position. This foundation performs no grading, feedback release,
whole-attempt completion, editable conversion or batch submission. Those compositions remain
with #603, #555/#556, #529, #834 and #629; #609 owns initial-flow activation. Legacy `/answers`
and immediate practice feedback stay unchanged. Before browser activation, the deployment CORS
allowlist must permit `Idempotency-Key`; this issue does not alter the existing security config.

### Dark atomic terminal submission (#529)

`AttemptSubmissionService` owns the participant command for the existing authenticated,
untimed, flat `ALL_AT_ONCE` immediate-acceptance slice. Its additive
`POST /api/v2/attempts/{attemptId}/submit` adapter is absent unless
`quizmaker.attempts.submission-enabled=true`. Public routing activation, including any
adaptation of the existing `/complete` route, remains #609. Existing legacy request/response
envelopes and immediate practice feedback are preserved; web/iOS clients need no change while
the adapter is disabled. Browser activation also requires the existing CORS deployment gate
for `Idempotency-Key`.

The named `AttemptSubmitRequest` accepts only `expectedStateVersion` and
`completionConfirmed`; final submit carries no answers, participant identity or policy overrides.
The strict adapter rejects unknown/duplicate fields, trailing JSON and scalar coercion without
changing legacy parsing. Authentication, `ATTEMPT_CREATE`, the bound participant and current
owner/full-question-editor authority are rechecked before both fresh execution and receipt replay.
The separate read permission is not required to submit. Form membership, immutable contracts,
saved require-all and confirmation are server-owned facts. Completeness reads one indexed set of
accepted-response metadata, without loading response payloads or querying once per item.

The command takes the same lifecycle root lock as #543 before shared content/access reads.
`AttemptTerminalSubmission` joins that receipt transaction, checks completeness and the expected
version, invokes the response-finalization boundary, closes collection, writes `ANSWER_LOCKED`
and `ATTEMPT_SUBMITTED` trace events, and records the submission plus one finalization intent.
Terminal state prevents a later answer from joining the accepted set. All writes commit together;
another connection cannot see the finalization intent before that commit. V96 adds retained
`attempt_submission` and `attempt_finalization_intent` evidence with natural uniqueness, immutable
form/policy/revision references, counts and a server timestamp. It introduces no legacy backfill,
cascade or cleanup. Receipt expiry does not erase these records. Keep the additive tables during
application rollback; forward-fix retained target attempts. #664/#699 retain lifecycle/erasure
ownership, and #603 owns actual intent consumption, automatic scoring and its recovery protocol.

`AttemptSubmitAcknowledgement` contains only `attemptId`, `submissionId`, `stateVersion` and
`replayed`; submission does not grant result/review disclosure. An identical retry returns the
original acknowledgement/version after current authorization. Changed same-key input, a stale
version or another fresh submission conflicts. Unknown outcomes retain their identity and are
reconciled by an exact retry; no automatic retry policy is added. RFC 7807 `AttemptSubmitProblem`
documents 400 malformed input/key, 401 authentication, 404 inaccessible attempt, 409 conflict/
stale/inactive/busy/expired receipt, 422 missing required answers/confirmation/unsupported
capability, 500 corrupt evidence and 503 unavailable/retryable/unknown outcome. Only an authorized
incomplete response includes the safe `missingFormItemIds`; other failures have an empty list.
Success and errors use `Cache-Control: no-store`. Metrics use only version/outcome labels.

For example, eight accepted answers in a ten-item form finish with two unanswered items when
require-all is OFF and completion is confirmed. ON denies without state/receipt/trace/intent
writes; after the remaining two answers are accepted, a fresh version can complete. Standard
minimal start retains its OFF preset. The ON submission fixture captures a separate policy via
real foundation services; it does not claim a new public ON starter.

MySQL tests use actual publication, contracts, start, acceptance, receipts and terminal writes.
They cover both isolation levels, controlled answer-versus-submit orderings, concurrent submits,
recreated-service replay, commit acknowledgement loss, reached-write rollback, metadata corruption,
current access, history/receipt cleanup and multi-parent query counts. Closed-scope fixtures prove
the terminal contract with captured scope facts, not a working section/timer writer. The named
`SubmissionResponseFinalizer` double proves orchestration and rollback only: its eight eligible
values and pending/empty items are not proof of eight persisted conversions. Real editable
conversion remains #834; real timing/expiry is #601/#702, section composition #711, grading #603,
release #555/#556 and public conformance/activation #609. No unsupported capability is activated.

### Dark automatic result facts (#603)

`AutomaticResultService` consumes committed #529 submission intents through the internal
`SubmittedAttemptSource` boundary. It is a trusted system service, not participant read or
disclosure authority. The source derives scope from retained submission/subject/form/policy
references, verifies the terminal lifecycle and accepted set, and loads immutable revisions
and exact contract versions. It never reads mutable authoring questions or requires an active
participant session to finish already submitted work. Existing participant access checks and
all legacy routes remain unchanged.

`binary-result/v1` uses the five #701 binary family contracts. Each form item contributes one
possible point. Accepted correct responses earn one; accepted incorrect responses and allowed
unanswered items earn zero. An unanswered item retains a null response reference; no accepted
incorrect answer is fabricated. V97 stores integer earned/possible totals, per-item revision,
response/scoring versions and response references, the captured threshold, policy hash and
immutable form/policy/revision/submission references. [D02's owner-approved HALF_UP refinement](decision-register.md#automatic-result-normalization)
governs normalized basis points. Exact cross multiplication determines pass/fail independently
of rounding, and no floating-point value is authoritative.

One transaction locks the retained intent, validates source evidence and creates the immutable
result, item grades, `AUTOMATIC_GRADE_PRODUCED` trace and one `attempt_result_created` outbox fact.
The outbox ID is the result/trace ID. Unique submission and attempt keys protect the initial
automatic result; repeated processing returns the original result without rescoring or changing
its timestamp. Receipt expiration, author edits and service restart do not change it. Unknown
or missing contracts, inconsistent counts/scope, malformed evidence and unsupported stored
grading modes fail closed, leaving no partial result. No manual, mixed or ungraded writer is
introduced; future corrections create separately owned superseding evidence.
Stored responses round-trip through #543's frozen source-key adapter and canonical membership
validation before scoring. Unknown option/gap references cannot become synthetic incorrect
grades. Real accepted empty arrays retain their existing incorrect-answer semantics and their
accepted-response identity; they are not reclassified as unanswered.

Recovery uses bounded `pending(limit)` discovery and re-drives `finalizeSubmission` with the
same retained submission ID in a fresh transaction. A committed result consumes the intent by
natural identity; the intent itself is retained. After an unavailable/unknown commit outcome,
re-drive that identity rather than create another result. `BUSY` and `RETRYABLE` distinguish
lock timeout and deadlock. No automatic retry count, polling schedule or external dispatch is
selected here. Integrating runtime triggers and controlled public activation remains #609;
disclosure/release remains #555/#556. New metrics use only bounded version/operation/outcome
labels (`quizmaker.result.automatic`); failures contain no answer content or driver diagnostics.
For the grading path, runtime callback failures are sanitized at every participating
`TransactionTemplate`: the result command, lifecycle access, policy-snapshot read, question-contract
declaration/resolution and trace append. Cleanup happens before each template can log rollback
diagnostics. Known failure reasons and database-conflict classifications survive; original causes,
suppressed exceptions and raw driver messages do not cross these callback boundaries. Joined
transactions remain rollback-only after failure, and contract-scope violations retain their
transaction-wide poison flag even across savepoints. Same-submission recovery retains its identity.

For example, seven correct, one incorrect and two unanswered items produce `7/10`, `7000`, pass
at the default threshold, with eight accepted-response references. Restarting the consumer or
editing the author's current answer key preserves exactly that saved fact. This foundation
does not make the total participant-visible, issue a certificate or emit a delivered notice.
Keep additive tables on application rollback; forward-fix retained evidence. #664/#699 retain
erasure/retention ownership; this migration adds no cascade, age cleanup or legacy backfill.

### Implemented canonical command receipts (#599)

`CommandReceiptService` is an internal, dark authenticated-command boundary. Its Spring
implementation joins or creates a transaction and the V91 JDBC repository stores bounded
receipts in `attempt_command_receipt`. Existing controllers, legacy attempts, permissions and
response envelopes remain unchanged. #600 owns real start composition; #543/#529 own accepted
responses and terminal commands; #609/#617 own public adapters. These foundations do not prove
those consumers, guest/system commands, offline replay or public activation.

**Identity and consumer contract:** resolve the authenticated user and trusted context on the
server. The future `Idempotency-Key` transport carries an exact, case-sensitive opaque value of
1–200 printable ASCII characters without whitespace. Only its SHA-256 digest is persisted.
The key namespace is the authenticated user: changing operation, context, target, expected
state version or canonical input under that user's same key conflicts. Different users have
independent namespaces and cannot discover one another's receipts. Supported operations are
`START`, `NAVIGATE_TO_ITEM`, `SUBMIT_ANSWER` and `SUBMIT_ATTEMPT`; `VIEW_ATTEMPT` needs no receipt.
START can reserve its command before an attempt exists; it targets the immutable input revision,
not a newly generated attempt ID. Retries must reuse the original expected version and inputs.

`CanonicalCommandInput` v1 hashes already validated semantic command input. It sorts object
properties, normalizes finite numeric spelling, preserves exact strings and array order, and
distinguishes absent properties from explicit null. Consumers must reject duplicate JSON keys
when parsing and normalize any domain-specific unordered input before hashing. Encoding is
bounded to 5 MiB, depth 64 and 100,000 nodes; these are internal work bounds, not new public
endpoint limits. The fingerprint is computed once at the command boundary, never by rereading
stored document bytes/text. Unknown versions and unsupported nodes fail closed.

The authorization callback runs before every receipt lookup, including replay, conflict and
expiry. The caller must check current permission, ownership, organization, visibility and any
release rules; neither a key nor stored identity is authorization. Work runs only for the new
reservation and receives its stable internal command UUID. It returns a versioned acknowledgement
containing only attempt/result references and state version. Receipt storage checks the real
authenticated subject binding and authoritative lifecycle version. Command-specific validation,
target membership, natural uniqueness and actual business writes remain the consumer's duty.
Never serialize internal requests/receipts as participant DTOs or cache answer/feedback bodies.
Replay retrieves the original acknowledgement; any subsequent view still applies current access
and disclosure rules and cannot increase disclosure merely because the command was retried.

**Transaction and failure semantics:**

| Situation | Receipt-boundary outcome |
|---|---|
| New authorized command | Reserve `IN_PROGRESS`, execute work, set `COMMITTED` and persist its acknowledgement in the same transaction as the domain effect. |
| Identical committed retry, including after restart or later state changes | Return the original command UUID, references, version and timestamp with `replayed=true`; do not call work. |
| Conflicting reuse | Bounded `CONFLICT`; the future HTTP consumer maps it to 409 without exposing stored input or foreign scope. |
| Concurrent first requests | MySQL uniqueness serializes that user's key. The waiter reads the committed winner or executes after the winner rolls back. Current reads also work inside an older `REPEATABLE_READ` snapshot. |
| Lock wait timeout / deadlock | `IN_PROGRESS` / `RETRYABLE`, with no hidden retry or losing partial writes. Future adapters distinguish pending-key conflict (409) from transient database failure (503). |
| Validation/work failure before commit | Roll back receipt and domain writes together; no independently committed failed reservation or execution lease. A caught inner failure marks the enclosing transaction rollback-only. |
| Failed connection acquisition | `PERSISTENCE_UNAVAILABLE`; no success acknowledgement. |
| Commit acknowledgement lost / transaction completion uncertain | `UNKNOWN_OUTCOME`; reconcile in a fresh transaction with the exact same request. Never infer rollback or switch to a new key. |
| `beforeCommit` / `afterCommit` synchronization throws | Clean `UNKNOWN_OUTCOME`, even for a `ReceiptException` carrying a business-denial reason. A failing `beforeCommit` rolls back; a failing observer in `afterCommit` leaves the receipt and effect committed, so an identical retry replays. |
| Unexpected persisted `IN_PROGRESS` or reentrant same-key execution | `IN_PROGRESS`, no takeover; an orphan requires separately owned investigation/repair. |

Callbacks must share the same database and transaction manager. `REQUIRES_NEW`, remote effects,
independent commits and after-commit business writes violate this contract. A returned result
inside an outer transaction is provisional until that transaction commits. Consumer business
denials use fixed `ReceiptException` reasons. Work failures are distinguished from transaction
completion failures. Runtime diagnostics and causes/suppressed exceptions from work and registered
transaction synchronizations are discarded before Spring can log them. Synchronization ordering,
resource callbacks and Spring's log-only semantics for `beforeCompletion` / `afterCompletion` are
preserved. Protection covers synchronizations registered when the receipt call exits, including
later completion by an outer transaction and callbacks registered by protected callbacks during
completion. Synchronizations added by the outer owner after return, global transaction-manager
listeners and diagnostics emitted directly by collaborators are outside this local boundary.
Optional telemetry uses
only bounded version/operation/outcome labels and cannot replace the command outcome; `returned`
does not assert that an outer transaction committed.

**D08 retention:** a receipt remains replayable while its attempt is nonterminal, then until
exactly its authoritative terminal timestamp plus 30 days. At that boundary a retained receipt
returns `EXPIRED`, with no work execution. `purgeExpired` is a caller-transaction primitive,
bounded to 100 rows, with authoritative terminal/version checks and indexed attempt scope.
It deletes receipts only; natural answer/result/certificate constraints and lifecycle facts
survive independently. After physical receipt removal there is no perpetual key-replay promise:
consumers must still enforce their natural uniqueness and current eligibility.

#699 owns purpose classification, cleanup authorization, hold coordination and scheduling. It
must lock/verify the relevant facts in the same transaction before invoking the primitive; missing
or ambiguous classification/hold evidence must not invoke deletion. No scheduler, hold decision,
organization retention override, automatic orphan takeover or historical backfill is added here.
Before any target writes, the dark application can be disabled while leaving the additive table;
after writes, preserve receipts and domain evidence and use a compatible forward fix.

`CommandReceiptTest` verifies canonical identity, opaque keys, bounded encoding and exact D08
boundaries. `CommandReceiptMySqlIntegrationTest` uses real subject/lifecycle/receipt writes,
fresh reads, controlled database lock waits, both `READ_COMMITTED` and `REPEATABLE_READ`, stale
snapshots, actual SQL/commit faults, rollback-only composition, restart, JPA transactions and
sanitized work/completion diagnostics, observer failure after commit, outer completion, and
synchronization ordering/resource lifecycle. Guard doubles prove callback ordering, not implementation of
#537 authorization. Lifecycle fixtures prove local atomicity, not #600/#543 business integration.
Replay uses two indexed SELECTs with 3 and 30 real parent attempts; EXPLAIN also confirms the
cleanup index. No JPA relationship-loading path is added. Full hold/deletion orchestration remains
#699 evidence, separate from local D08 eligibility and bounded transactional deletion tests.

[#629 D09](https://github.com/Gegcuk/QuizMaker/issues/629) approves `ATOMIC_ALL_OR_NOTHING`: at most 500 items, a 5 MiB request body, and 32 KiB per textual response unless its contract is stricter. An invalid item rejects the entire batch with no accepted answers, locks, grading trigger, or feedback. Errors retain stable form-item order; malformed JSON uses `400`, limits `413`, deterministic validation `422`, and lifecycle/version/idempotency conflicts `409`.

Possible future batch policies include:

```text
ATOMIC_ALL_OR_NOTHING
VALID_ITEMS_ONLY_WITH_ITEM_ERRORS
```

`VALID_ITEMS_ONLY_WITH_ITEM_ERRORS` is not the approved v1 behaviour. A later policy requires a separate contract; final attempt submission remains a distinct command without answer payload under #612 D07.

The approved editable-until-final-submission configuration requires explicit orchestration between acceptance of ready provisional responses and the terminal transition. A generic final-submit operation must not promote pending-confirmation proposals or arbitrary autosaved drafts. Define version/precondition and transaction boundaries that prevent an edit/confirmation race or ambiguous partial finalization while retaining D07/D09 responsibilities; the exact command composition is [engineering preparation](./open-questions.md#oq-answer-finalization), not an implicit API change.

---

## Execution, Grading, and Review Lifecycles

One attempt status is insufficient.

### Execution lifecycle

A possible target model:

```text
CREATED
READY
IN_PROGRESS
PAUSE_PENDING
PAUSED
SUBMITTED
EXPIRED
ABANDONED
CANCELLED
```

`SUBMITTED` is a terminal participant execution state, but not necessarily a terminal grading or review state.

### Answer-submission lifecycle

```text
NOT_STARTED
IN_PROGRESS
COMPLETE
```

Per-answer state remains separately represented.

### Implemented v1 lifecycle contract (#527)

`AttemptLifecycle` is a pure domain contract with `version = 1`. It is dark: no controller,
legacy service, persistence model or execution route calls it. The wider candidate vocabulary
above does not enable timing, pause, abandon/cancel, sections, grading, release or review.

Exactly four execution/answer-collection tuples are valid. `COMPLETE` means collection is
closed, not that every item has an accepted answer. `CREATED` is an internal pre-start value;
it does not authorize persisting a placeholder during an ambiguous policy choice.

| Current execution / collection | Fresh `START` | Fresh `RESPONSE_RECORDED` | Fresh `SUBMIT` |
|---|---|---|---|
| `CREATED / NOT_STARTED` | `IN_PROGRESS / NOT_STARTED` | Deny `NOT_STARTED` | Deny `NOT_STARTED` |
| `IN_PROGRESS / NOT_STARTED` | Deny `ALREADY_STARTED` | `IN_PROGRESS / IN_PROGRESS` | `SUBMITTED / COMPLETE`, subject to completion guards |
| `IN_PROGRESS / IN_PROGRESS` | Deny `ALREADY_STARTED` | Allowed, same tuple | `SUBMITTED / COMPLETE`, subject to completion guards |
| `SUBMITTED / COMPLETE` | Deny `TERMINAL_STATE` | Deny `TERMINAL_STATE` | Deny `TERMINAL_STATE` |

`RESPONSE_RECORDED` describes validated response activity, not an answer payload or a request
to accept one. Collection may remain `IN_PROGRESS` after provisional work is cleared.
Conversely, `NOT_STARTED` cannot be combined with non-empty response facts at submission.

State alone is not idempotency proof. The caller must first establish the same committed
operation/scope/key/canonical input using #599. With that `VERIFIED_IDENTICAL` context, the
following are no-effect `Replay` decisions; every other tuple/event denies
`REPLAY_STATE_MISMATCH`:

| State | Replayable events |
|---|---|
| `CREATED / NOT_STARTED` | None |
| `IN_PROGRESS / NOT_STARTED` | `START` |
| `IN_PROGRESS / IN_PROGRESS` | `START`, `RESPONSE_RECORDED` |
| `SUBMITTED / COMPLETE` | `START`, `RESPONSE_RECORDED`, `SUBMIT` |

`CONFLICTING` command identity always denies `CONFLICTING_REPLAY`. Replay returns no plan and
does not rerun completion against a new payload. The receipt owns the original acknowledgement;
current authorization and disclosure remain separately enforced by its consumer.

`CompletionFacts` contains trusted form-item IDs in persisted form order, response states,
captured finalization/require-all/confirmation facts and authoritative applicable/closed scope.
It contains no response payload. It distinguishes `EMPTY`, `DRAFT`, `EDITABLE`,
`PENDING_CONFIRMATION` and `ACCEPTED`. The immediate-lock contract rejects ordinary `EDITABLE`
facts. Editable-until-attempt-submission facts model the approved later capability without
enabling it in `ResolvedAttemptPolicy` or implementing #834.

- Already accepted answers remain untouched. Only ordinary eligible editable items enter the
  proposed finalization plan. A closed scope cannot contain an ordinary still-editable answer.
- Empty items, drafts and pending-confirmation proposals remain unanswered. With require-all
  enabled they deny voluntary submission in applicable scope; permanently closed scope does
  not block it. The model does not itself close any scope.
- Voluntary completion requires confirmation, including confirmation of omissions. Denial
  returns no partial plan and cannot change the immutable input.
- For eight editable responses, one pending proposal and one empty item, confirmed submission
  with require-all OFF proposes eight finalizations and two unanswered items. ON denies it.
  For eight already accepted responses, the same OFF submission proposes no new acceptances.

Every evaluation returns exactly one `Advance`, `Replay` or typed `Denied`. Structural invalid
input, contradictory tuples/facts and unknown versions/symbolic values are rejected with bounded
`LifecycleException` reasons; no input or protected content is echoed. Public error mapping is
owned by the consuming HTTP boundary. No default version, access or release is inferred.

`AttemptLifecycleTest` covers every valid tuple/event for fresh, verified identical and
conflicting identities, terminal replay and invalid tuples/versions. `LifecycleCompositionTest`
covers completion, immutable inputs/plans, eligible versus pending work and closed-scope facts.
`LifecyclePurityTest` checks strict symbolic parsing and framework/persistence/transport isolation.
No JPA relationship is read, so N+1 does not apply to this slice. These are pure contract proofs:
#528 owns storage; #529/#834 own real acceptance/terminal transactions; #600/#609 own start and
activation. Expiry workers, section runtime, durable command receipts and offline execution need
their separately owned integration evidence. No deployed behavior changes in #527.

### Authoritative lifecycle storage (#528)

`AttemptLifecycleService` persists the #527 execution/answer-collection tuple in the separate
`attempt_lifecycle_state` table (V88). It is an internal boundary for trusted, already authorized
server callers. It grants no access from an attempt ID and has no controller or public route.
No legacy `AttemptStatus` row is read, changed, backfilled or treated as target evidence. There
is deliberately no foreign key to legacy `attempts`; #600 owns atomic target start/binding.

The lifecycle contract version (`1`) is distinct from the nonnegative `row_version` concurrency
counter. Initialization creates only `CREATED / NOT_STARTED` at counter zero. `save` locks one
root using a current `SELECT ... FOR UPDATE`, checks the expected counter, and updates with
an explicit compare-and-set predicate. A successful write increments the counter once, including
new response activity whose tuple remains `IN_PROGRESS / IN_PROGRESS`. Stale writes and duplicate
initialization conflict. Storage prevents skipping start, regression and terminal rewriting;
callers still own the #527 guards, response/finalization writes, authorization and command identity.
Saving a tuple alone is not proof that an answer was accepted or an attempt command executed.

The injected application `Clock` supplies creation/update and first-start/submission timestamps.
They are stored as UTC `DATETIME(6)` values with microsecond precision. The original creation
and start instants survive later updates. Invalid time ordering, unsupported contract versions,
unknown exact symbolic names and contradictory tuples fail closed with bounded, content-free
errors; reads neither repair corruption nor select a current default. V88 also enforces the
supported tuple/version/time constraints, including rejection of case and trailing-space variants.
These timestamps do not introduce deadlines, pause, expiry or scheduling behavior.

`LifecycleReference.Legacy.NO_TARGET_STATE` returns an explicit legacy marker without database
access. A missing `Target` reference is an error, never an invitation to construct target state.
Ordinary reads respect the caller's transaction isolation. `lock` requires an existing caller
transaction and retains the root lock until that transaction ends. Current locking reads avoid
using an older `REPEATABLE_READ` snapshot when checking the expected write version.

Writes join real JDBC/JPA caller transactions: returning a value does not commit an outer
transaction, and a caught nested storage failure still makes that transaction rollback-only.
There is no automatic deadlock retry. Competing first inserts may also select a real MySQL
deadlock victim: one creator succeeds and the victim receives a typed storage failure. After
fresh reconciliation, trying to initialize the existing winner conflicts without overwriting it.
After an unavailable/unknown commit outcome, a caller
must reconcile through a fresh transaction; state equality does not establish command replay.
#599 owns durable receipts and original acknowledgements. The `attempt.lifecycle.storage` timer
uses bounded version/operation/outcome tags, and telemetry failure cannot change persistence.
Its `returned` outcome describes the service boundary rather than an outer transaction commit.

`LifecycleStateTest` checks metadata, tuple progression and compatibility with #527 advances.
`AttemptLifecycleMySqlIntegrationTest` applies the full V1–V87 history followed by V88 in a
generated test-owned catalog. It covers constraints/corruption, independent concurrency under
both isolation levels, an actual MySQL deadlock with one committed winner, explicit retry after
fresh reconciliation, reached-write rollback, lost commit acknowledgement, reconstructed services,
caller locks and real JPA composition. JDBC/JPA fixtures also catch a real deadlock, reach a
subsequent SQL write, and prove that the outer commit rejects the rollback-only transaction.
Fresh reads retain neither the victim's original changes nor its later writes.
`LegacyLifecycleIsolationIntegrationTest` exercises the
real existing start/read/answer/complete services and wrong-owner denial in all legacy modes,
with committed commands and fresh reads, without creating target state. A synthetic legacy
marker in the focused storage suite proves only migration/transaction isolation; it is not a
substitute for that real legacy-service test.

State reads load one indexed root and no JPA relationships. The focused SQL-count test measures
one query for an ordinary or locking read with multiple roots present, and verifies the primary-key
lookup plan. No list, aggregate, mapper or relationship-serialization path is added.

The migration is additive and creates no historical state. Before target writes, deployment may
disable the unused boundary. After target writes, preserve its evidence and use forward fixes;
destructive downgrade or new retention/erasure behavior is outside this increment. Actual start,
answer acceptance, terminal orchestration, grading and activation remain #600, #543, #529/#834,
#603 and #609 respectively. No public API, client rollout or production activation is implied.

### Grading lifecycle

```text
NOT_REQUIRED
PENDING_AUTOMATIC
PENDING_MANUAL
PARTIALLY_GRADED
GRADED
FAILED_RETRYABLE
```

### Result lifecycle

```text
NOT_AVAILABLE
CALCULATING
FINAL
INVALIDATED
```

### Release lifecycle

```text
HIDDEN
WAITING_FOR_GRADING
WAITING_FOR_DEADLINE
WAITING_FOR_MANUAL_RELEASE
AVAILABLE
REVOKED
```

### Review lifecycle

```text
NOT_AVAILABLE
AVAILABLE
EXPIRED
REVOKED
```

The final state names may differ, but these concepts must remain separate.

---

## Disclosure and Feedback

Correctness, score, correct answers, and explanations are different information categories.

Each requires its own release rule.

### Protected categories

```text
correctnessRelease
questionScoreRelease
totalScoreRelease
correctAnswerRelease
explanationRelease
hintRelease
rubricRelease
graderCommentRelease
participantAnswerRelease
statisticsRelease
```

### Candidate release conditions

```text
NEVER
ON_REQUEST
AFTER_HINT_REQUEST
AFTER_EACH_SUBMISSION
AFTER_N_FAILED_ATTEMPTS
AFTER_QUESTION_LOCK
AFTER_SECTION
AFTER_ATTEMPT
AFTER_PASSING
AFTER_DEADLINE
AFTER_ALL_PARTICIPANTS_FINISH
MANUAL_RELEASE
```

Examples:

- show only correct/incorrect immediately;
- show a hint after the second failed attempt;
- show explanation after the third failed attempt;
- show total score immediately but delay correct answers;
- release full review after the deadline;
- never release the answer key;
- release grader comments but not the complete rubric.

Disclosure is evaluated by a central server-owned service.

Request flags such as:

```text
includeCorrectness
includeCorrectAnswer
includeExplanation
```

must not override the stored policy.

Legacy flags may temporarily be accepted and ignored for request compatibility.

---

## Timing and Deadlines

The server clock is authoritative.

The client clock is display-only.

### Implemented overall-timing foundation (#601)

The internal authenticated starter accepts an explicit, server-authorized `TOTAL_ATTEMPT`
configuration with a positive duration, an overall close, or both. Its existing owner/full-editor
check supplies authority; participants cannot supply a timing override. The built-in default remains
`NONE`, and legacy `TIMED` mapping and public start routing retain their existing behavior.
Real assignment availability/binding remains #534; these internal overall-close inputs do not prove
that integration or activate formal assessment.

The canonical policy records exact integer seconds/nanoseconds and author provenance. Historical
`{"mode":"NONE"}` canonical bytes/hashes and old start fingerprints remain unchanged. Timing inputs
use MySQL-compatible microsecond precision; unsupported precision or unrepresentable deadlines
are rejected rather than rounded or replaced with a default.

V104 adds insert-only `attempt_timing` storage and the `(effective_deadline, attempt_id)` due index.
The checkpoint binds the exact policy snapshot and lifecycle start, and saves duration-derived and
overall terminal deadlines separately, their minimum, version 1 and zero grace. All writes share
the start/receipt/allowance transaction. For example, 17:45 plus thirty minutes with an 18:00 close
saves 18:15 as the duration deadline and 18:00 as the effective deadline. Author edits and restart
do not move either value. Exact committed replay returns the original attempt without restarting
its clock; a changed timing configuration under the same key conflicts.

New command entries sample the injected `Clock` before receipt/lifecycle lock waits. The pure timing
decision is open from the saved start up to, but excluding, the effective deadline. Acceptance,
draft, navigation, active delivery and voluntary submission consume that decision; expired fresh
operations return their existing inactive failure without writes. A committed identical command
can still replay after the deadline. No client timestamp participates in this decision.

Participant reads declare and lock question-revision scope before reading the timing checkpoint,
matching the starter's lock order. A missing untimed checkpoint can acquire a gap lock under
`REPEATABLE_READ`. After #906 phase A, participant question scopes are shared: a view can project
while a start holds the same question identities. SQL-barrier fixtures retain two successful
outcomes under `READ_COMMITTED` and `REPEATABLE_READ`, for read-only and ordinary reads, with
timed and untimed new starts. RC starts commit while the view transaction remains open. An RR
timed start can still wait on the completed view's missing-timing gap lock; the fixture verifies
that actual wait and releases the view before requiring both commits, without a deadlock or retry.
Removing that RR freshness-lock coupling is outside phase A.

The participant projection adds `TIMED` and the named optional `ParticipantAttemptTiming` field
`timingDetails`: version, start, effective deadline, zero grace seconds, server time and an expired
indicator. `UNTIMED` envelopes omit that field. Reads retain current access checks and `no-store`;
source limits, snapshot IDs and operator facts are not disclosed. An expired indicator does not
mean terminal processing has committed; it removes fresh actions while preserving the actual
lifecycle evidence. Full/grouped OpenAPI share schema-valid examples in the `attempts-v2` group.

There is no historical backfill: a verified `NONE` snapshot with no checkpoint stays untimed.
A timed snapshot with missing, corrupt, mismatched or unsupported checkpoint fails closed and
never falls back to current settings or legacy execution. After timing writes, recovery is a
forward fix retaining the additive table, not a destructive downgrade.
The checkpoint belongs to the detailed-attempt data family and follows its existing scoped
retention/erasure rules; this increment adds no cleanup job or new retention period.

#702 owns due claims, expiry and restart recovery through the canonical terminal writer; #602
owns pauses, #660 section clocks, and #668 the actual formal composition and activation.
#601 does not auto-submit, finalize drafts, release results or turn on public timed starts.
Regression fixtures cover exact boundaries, committed replay, reached-write rollback, response
loss, concurrent starts, reader restart, author edits, negative access and bounded relationship
queries through participant serialization with multiple parent quizzes.

### Timing modes

```text
NONE
TOTAL_ATTEMPT
PER_SECTION
PER_QUESTION
COMBINED
```

Timing policy may include:

- assignment opening time;
- assignment deadline;
- duration from first start;
- section duration;
- question duration;
- grace period;
- warning thresholds;
- automatic submission;
- draft handling on expiry;
- participant accommodation;
- behaviour during pause;
- behaviour after reconnect.

### Important time concepts

These must not be conflated:

```text
assignmentOpensAt
assignmentDeadline
attemptStartedAt
attemptExpiresAt
sectionStartedAt
sectionExpiresAt
questionDeliveredAt
questionExpiresAt
submittedAt
serverReceivedAt
```

An assignment may close at 18:00 while an attempt started at 17:45 has a 45-minute limit. [#674 A07/A17/A18](https://github.com/Gegcuk/QuizMaker/issues/674) selects earliest-applicable-deadline behaviour and no formal v1 grace. With only these two deadlines, the saved expiry is:

```text
min(assignmentDeadline, startedAt + duration)
```

Persist the overall terminal deadline separately from section/question timing events. Each event retains its exact UTC instant, scope, saved policy outcome and adjustment evidence. The earliest event tells the worker what to process next; it does not automatically end the attempt. Default section expiry closes only that section; a configured whole-attempt outcome or expiry of the last unclosed section terminates the attempt. An overall deadline always terminates, including when it coincides with a section event. A question deadline follows its own explicitly supported saved outcome; do not infer whole-attempt termination merely because it is the earliest event.

For example, Grammar expires at 10:10 and the overall exam closes at 10:40. Under section-only expiry Vocabulary may still proceed through its saved transition/break rules; under the whole-attempt override the attempt ends at 10:10. Persist all timing evidence; do not recompute from later mutable configuration. Extensions follow #674 A21 and cannot silently rewrite the original facts.

### Expiry action

Expiry follows the approved response category and the saved event scope/outcome below. This is not an open menu of interchangeable submit/discard/no-result behaviors: accepted evidence stays immutable, eligible ordinary saved editable responses finalize, and pending-confirmation proposals remain unanswered. Existing unsubmitted legacy drafts retain their separate contract.

For existing v1 policies, [#674 A19](https://github.com/Gegcuk/QuizMaker/issues/674) retains an unsubmitted draft temporarily as restricted, ungraded evidence. It is not accepted automatically, does not affect score/completion, and follows detailed-attempt retention. The additional approved configuration below distinguishes ordinary saved responses intended for submission from arbitrary drafts; it does not enable generic `SUBMIT_DRAFTS_WHERE_VALID` behavior.

For the additional editable-until-final-submission configuration, the [owner's expiry decision](./decision-register.md#editable-response-expiry) requires automatic finalization of ordinary saved responses intended for submission when time expires. Marked pending-confirmation proposals stay unanswered even when their proposed response validates. For example, nine ordinary saved responses and one marked proposal yield nine finalized answers and one unanswered item.

The saved policy and authoritative server deadline determine eligibility. Apply expiry through the shared idempotent operation; reconcile concurrent edits/manual completion and delayed workers without extending time, duplicating acceptance or changing pending proposals into answers. Finalized answers become immutable, while grading and feedback release continue to follow the captured policy. The exact acceptance/terminal transaction composition remains engineering preparation. Existing policies retain A19's draft handling.

### Scope of section timeout

The [approved section-timeout policy](./decision-register.md#section-timeout-scope) closes and permanently locks only the expired section by default, permitting continuation to a later authorized section where one remains. When expiry closes the last unclosed section, automatically complete the attempt as specified below. A configured alternative terminates the whole attempt on any section expiry and prevents further section participation. In both cases, finalize eligible ordinary saved editable responses and leave pending-confirmation/empty items unanswered; require-all completion cannot delay expiry. Overall attempt expiry or assignment close prevents continuation, including when deadlines coincide.

The [approved start/break policy](./decision-register.md#section-start-and-break) determines next-section clock activation separately from question delivery. A permitted break allows explicit early start or automatic clock start at its expiry; its maximum defaults to five minutes. When breaks are prohibited, the next permitted section's clock starts immediately at previous-section closure. Both automatic-start paths wait for an explicit question request. Worker latency, client rendering or reconnect cannot silently establish a new timing policy.

### Expiry of the last unclosed section

The [22 October owner decision](./decision-register.md#last-unclosed-section-expiry) requires automatic attempt completion when expiry permanently closes the last unclosed section in the attempt's applicable scope, including under section-only timeout handling. Determine that condition from authoritative lifecycle state across the scope. Being the highest ordinal section is insufficient if another section remains open or unstarted.

No explicit final-submit action or participant presence is required. Use the effective saved deadline, preserve earlier outer-deadline precedence, finalize eligible ordinary responses, and leave pending-confirmation/empty items unanswered without changing grading rules. Coordinate finalization, section closure, terminal completion and result-processing effects through the shared idempotent expiry flow; retries or manual-completion races must not duplicate accepted evidence/results or leave an additional manual-submit requirement. Exact D07/D09 transaction composition remains engineering preparation. Terminal completion starts result processing but does not itself authorize release of results, correctness or explanations.

This also applies when a break automatically started the last section's clock and no question was requested before it expired. Do not fabricate delivery, reopen the section or grant fresh time. Resume reconciles expiry and returns the terminal state with disclosure filtered by the saved policy. For example, Grammar is closed and Vocabulary expires at 10:20: returning at 10:30 shows an attempt completed at the effective 10:20 deadline, subject to any earlier outer expiry. This refinement does not choose a new automatic-completion rule for voluntary last-section submission or reaching a Branching terminal node.

### Next-section activation and inter-section breaks

The [start/break decision](./decision-register.md#section-start-and-break) distinguishes previous-section closure, permission to continue, waiting, clock activation and question delivery. With a permitted break, an authorized explicit participant action may start the next section before the break expires; otherwise its clock starts automatically at the saved break deadline. When breaks are prohibited, the clock starts immediately at previous-section closure. All starts remain subject to outer deadlines and continuation authority. Starting a clock never by itself means that any form item was delivered.

Break availability and maximum duration come from the saved policy. The default maximum permitted inter-section break is five minutes (`PT5M`). A break does not pause the overall attempt clock, extend assignment availability, stop an active section timer or reopen the previous section. Earlier outer expiry prevents activation even if the break allowance has time remaining.

After automatic start, wait for an explicit participant request before delivering the next question. Until then, status/resume shows the running timer and a next-question action with no question content, including no hidden prefetch in a response or client cache. Persist clock and delivery states separately. A later delivery request rechecks authorization and deadlines and never grants a fresh duration. Once an item was delivered, ordinary durable resume/navigation preserves its delivery evidence.

Persist break/start timing so reload, device switching, reconnect, repeated commands and worker latency cannot restart the allowance or grant extra time. For automatic activation after a permitted break, the section start is the saved break deadline, not when the worker runs or the participant returns. For example, after a break from 10:00 to 10:05, a 15-minute section has ten minutes remaining at a 10:10 return even though no question has been requested. If its time has already elapsed, process the saved expiry outcome instead of serving an expired question or extending time. Manual-start/expiry races converge on one start/deadline.

The [22 October prohibited-break refinement](./decision-register.md#no-break-section-transition) starts the next permitted section's clock at the previous section's authoritative effective closure time, after either successful voluntary submission or section-only expiry. For expiry, use its saved deadline even if processing occurs later. A rejected submission leaves the current section active and cannot start another clock; terminal/outer expiry prevents further activation. The five-minute permitted-break default supplies no waiting time when breaks are prohibited. Preserve one idempotent closure/start outcome and keep question delivery explicit, including in the closure response. For example, Grammar closes at 10:00 and Vocabulary has ten minutes: its first question requested at 10:03 comes with seven minutes left. This changes no saved duration and creates no timer for an untimed policy. If the undelivered section has already expired, apply expiry, including automatic attempt completion when it was the last unclosed section.

---

## Pause Semantics

Candidate policies:

```text
DISABLED
IMMEDIATE
AFTER_CURRENT_SUBMISSION
BETWEEN_SECTIONS
LIMITED_COUNT
LIMITED_DURATION
MODERATOR_ONLY
```

For `AFTER_CURRENT_SUBMISSION`:

```text
IN_PROGRESS
    -> PAUSE_PENDING
    -> current question remains active
    -> current timer continues
    -> current answer is submitted
    -> PAUSED
    -> no next question is available
```

The architecture must define behaviour for:

- browser closure;
- connectivity loss;
- application backgrounding;
- share-link expiry;
- assignment revocation;
- capability revocation;
- another device login;
- server restart;
- deadline reached during pause;
- per-question timer expiry while pause is pending.

A disconnect is not automatically a pause.

[#674 A20](https://github.com/Gegcuk/QuizMaker/issues/674) permits an enabled pause to affect only the covered attempt/section-duration clocks. Assignment close, capability expiry and scheduled release do not shift. Pause is disabled in the initial free-practice and formal baseline fixtures; enabled-pause combinations still require their owning conformance and unresolved timing decisions.

---

## Attempt Limits and Retakes

Candidate limits:

```text
UNLIMITED
FIXED_COUNT
ONE_PER_PERIOD
ONE_PER_REVISION
MANUAL_RESET
```

Retake policy may define:

- cooldown;
- same or new question set;
- same or new order;
- same or new option order;
- incorrect-only selection;
- whether previous feedback remains visible;
- whether previous revealed answers exclude questions from the next attempt;
- retry penalties;
- abandoned-attempt continuation;
- assignment-level reset;
- participant-specific reset.

### Result selection across attempts

Candidate rules:

```text
FIRST_VALID
LATEST_VALID
BEST_VALID
AVERAGE_VALID
```

For the new reporting/result-selection policy, [Q23 selects BEST_VALID by default](./decision-register.md#best-result-and-risk-retake). The other listed strategies remain candidates unless separately authorized. A policy may offer a risk retake after explicit informed consent before start: a new valid result becomes definitive even when lower. With 80% selected, an ordinary 40% attempt leaves the selected best at 80%; a consented risk retake selects 40%. Preserve both immutable results and the versioned selection/consent evidence; do not let a generic MAX query undo that commitment.

[Q23А](./decision-register.md#best-result-and-risk-retake) initially permits risk retakes only with a preconfigured finite deadline, shown before consent/start. Closing a page does not cancel the stored attempt, commitment or server clock. At that deadline, ordinary expiry finalizes eligible saved answers and grades them; pending-confirmation proposals remain unanswered. Ordinary earlier submission also honors the commitment. No hidden timeout/default duration or change to ordinary untimed free practice follows. [Q28](./decision-register.md#quiz-certificate-followup-approved) now restores the previously selected still-valid eligible result, in its current corrected state, after verified terminal technical/organizer cancellation without a trustworthy new result. It does not select an arbitrary old maximum, resolve unknown commits by guessing, invent zero or refund an allowance. [Q29](./decision-register.md#certificate-after-examination-finality) requires examination finality before certificate issuance; a released passing attempt with an eligible continuation is insufficient. Existing attempt limits and genuine correction/release rules remain authoritative.

[Q56](./decision-register.md#late-technical-risk-invalidation) also resolves later technical invalidation after a risk-retake result has already been released as definitive: restore the previously selected result only if still valid and eligible, using its current corrected state. For example, selected 80 becomes a released definitive 60 after a consented risk retake; a later confirmed technical invalidation of that retake restores the still-valid eligible 80. A valid 60 remains definitive. Persist selection/invalidation history and protect restoration against concurrent corrections; do not run a historical MAX query, invent missing results or automatically grant another attempt. Retain release/current-access checks, mandatory visible-score-change notifications and A26 certificate correction/revocation/replacement conditions, without silently reactivating an old certificate. Unknown outcomes, nontechnical invalidation and an absent/invalid/ineligible predecessor do not qualify for this restoration by analogy.

Tie behaviour must be explicit.

Failed attempt creation must not consume an attempt allowance.

Concurrent starts must not exceed the limit.

[#683 D21/D22](https://github.com/Gegcuk/QuizMaker/issues/683) makes committed participant starts consume an allowance and permits at most one active attempt per participant subject and immutable assignment/publication context. Technical failure before commit consumes nothing. Resets create audited allowance adjustments rather than deleting attempts.

### Internal retake eligibility calculation (#548)

`RetakeEligibilityService` evaluates current server-verified immutable facts without reading or
writing persistence. Its input port accepts an exact subject plus immutable assignment/publication
identity/version, an explicitly saved unlimited/finite limit and non-negative cooldown, entry
availability, complete start history and already authorized audited restoration facts. It accepts
neither client time nor a claimed remaining/used count. The facade samples the injected `Clock`
once; the pure domain calculation takes that instant and defensively copies evidence lists.

Every committed participant start consumes one allowance, including active, completed, expired,
abandoned and cancelled attempts. A classified technical failure before commit consumes none.
Pending, available or invalidated results do not refund a start. `StartFact.of` projects the existing
#527 lifecycle vocabulary: `SUBMITTED` is terminal before grading, while `CREATED` is not proof of
a committed start. `ResultFact.of` projects a real #603 automatic-result value without copying
scores or deciding the selected result. The wider terminal fact vocabulary does not implement or
activate expiry/cancellation writers.

The result contains only allow/deny, a bounded internal reason and, for cooldown denial only, the
next eligible UTC instant. Cooldown starts at the latest actual terminal instant, not result creation
or release; the exact deadline is eligible. For example, with three allowed starts, one committed
completion at 14:00 and a saved one-hour cooldown, 14:59:59 is denied until 15:00 and 15:00 is
allowed. An exhausted allowance does not promise future eligibility. A restoration reduces consumed
allowance but neither closes an active attempt nor removes cooldown. Duplicate/audit-replayed,
unscoped, future-dated or excessive restoration evidence is rejected, never silently clamped.

Missing access, unavailable/incomplete history, unresolved legacy classification, unknown versions,
unknown commit outcomes, contradictory scope/state/timestamps and arithmetic overflow fail closed.
An unavailable history is not an empty history. Diagnostic representations omit identity/audit data;
the outcome counter uses only bounded version/outcome/reason labels, and failed telemetry cannot
change a decision. No runtime route, saved policy format, public DTO, migration, transaction,
legacy writer or start orchestrator is changed. No JPA relationships are read, so this calculation
has no N+1 path.

`RetakeEligibilityTest`, `RetakeEligibilityBoundaryTest`, `RetakeEligibilityServiceTest` and
`RetakeEligibilityCompositionTest` cover decision tables, immutable evidence, scope/authority
negatives, server-clock boundaries, missing/unknown facts, restoration and telemetry failure.
Composition fixtures use real lifecycle/result value contracts; they do not prove durable starts,
concurrency, finality, issuance or a working facts reader. #549 owns actual transactional revalidation
and consumption with #600; #836 supplies real examination-finality/relative-window facts, #550
selected-result projection and #557 certificate issuance. Cached eligibility never grants online
or offline start authority; current facts and access must be revalidated at the actual boundary.

### Atomic canonical-start allowance (#549)

The internal `AttemptStartService` now applies D21/D22 in the existing #600 receipt transaction.
The authenticated user, current full-content access and current immutable publication are resolved
server-side. This remains an additive dark path: no public start operation, legacy attempt writer,
permission, participant DTO or OpenAPI group is switched. The current owner/full-editor boundary
can request typed author configuration; it is not a participant grant or a client-reported count.

`AttemptLimitPolicy` supports an explicit nonnegative fixed count (including zero), or unlimited,
and an explicit nonnegative cooldown. Unlimited/zero-cooldown retains the historical canonical
bytes, hashes, provenance and command fingerprint. Configured durations use exact integer seconds
and nanoseconds. No finite count, nonzero cooldown or automatic database retry is a new default.
The resolver requires author authority for limit overrides; forged participant provenance is rejected.

`attempt_allowance_scope` locks exactly authenticated user + immutable publication revision +
publication version until the shared transaction completes. It freezes that scope's authorized
limit/cooldown. A fresh key or different proposed configuration cannot raise the limit or remove
cooldown within that context. The row is a serialization/configuration guard, not a consumed-count
projection; unlimited has no separate counter. Its start-ID directory is updated atomically with
binding/trace/receipt writes. Current bulk point reads use the directory as an index, not as
proof of complete history. After validating the listed binding identities, a current authoritative
binding census must equal the directory length, including when the directory is empty. Missing
entries return ELIGIBILITY_UNAVAILABLE; the transaction does not repair either source of evidence.

The locking census runs before a rejecting decision, or after this command's binding/directory
writes and before receipt completion/commit. On that successful path no binding insert follows
the census: it cannot take a shared empty-range gap lock before independent first inserts in
REPEATABLE_READ. A mismatch rolls back every tentative start write, leaving the original binding,
lifecycle and malformed directory unchanged. A nonlocking count from an old RR snapshot is not
completeness evidence. SQL parameters remain batched without imposing an attempt quota; measured
multi-parent/history fixtures and independent-scope races cover the added census.

Current reads join durable `attempt_start_binding`, actual lifecycle, immutable policy and start
trace evidence in bulk. A committed start consumes one allowance even before grading. A real
terminal submission releases the active-attempt restriction but does not refund that consumption.
The latest terminal lifecycle instant determines cooldown; grading/release does not restart it.
One remaining attempt and two tabs produce one committed start; an exact retained-key retry returns
that same attempt, while a fresh competing key is denied. A reached write failure rolls back the
guard, binding, form, subject, lifecycle, trace and receipt together. Unknown commit outcomes retain
#599's reconciliation semantics; they never trigger a second start or legacy fallback.

V103 adds a durable publication-version binding so receipt cleanup cannot restore an allowance.
Its backfill copies only exact committed v1 START receipt metadata corroborated by immutable
form/publication/subject identity. It never infers target facts from legacy attempts or mutable
quiz settings. A pre-extension target root without durable context evidence is explicitly
unclassified and blocks new starts in the affected revision; unavailable history is not empty.
V103 records missing-context roots in the immutable `attempt_unclassified_publication` upgrade
classification; it is not a new runtime writer or automatic repair path. Existing historical
reads remain unchanged. Schema recovery after target writes is forward-fix,
without deleting retained history. The learner/publication context owns these new records; later
record-family erasure/retention work must preserve the same scoped evidence boundaries.

Internal failures distinguish active attempt, exhausted limit, cooldown (with safe next-eligible
instant), conflicting captured limits, closed entry and unknown/unavailable evidence. A future
canonical HTTP adapter must document and test its 409 conflict/eligibility, 429 cooldown and 422
invalid-configuration mappings, authentication/non-enumeration and RFC7807 schemas before exposure;
this increment adds no route and does not claim those future HTTP responses are activated.
Lock timeout remains IN_PROGRESS, deadlock RETRYABLE, and other storage failure is bounded and
sanitized. The allowance repository requires an active caller transaction explicitly and has no
transactional interceptor of its own. The owning service sanitizes JDBC failures before the first
transactional proxy can log them, including TRACE message text and nested exception diagnostics.
There is no hidden retry schedule. Existing bounded start/eligibility metrics contain
only version, strategy, outcome and reason values; identity and protected content are excluded.

The versioned `AttemptEntryReader` supplies current entry facts. Its publication implementation
uses the current publication/access already locked by #600. Contract fixtures for permitted,
closed and unknown entry prove gate consumption only. #836 owns actual examination finality and
its competing writer; #534/#668 own formal assignment composition, #557 certificate issuance.
Administrative allowance-adjustment commands, cancellation/expiry writers and their integrations
are not introduced here. The actual publication baseline uses real canonical writers and MySQL;
no mocked runtime writer is presented as formal/certificate integration proof.

---

## Grading

Grading is a separate domain from execution, even though execution policy determines when grading is triggered.

Approved v1 grading is binary all-or-nothing ([#675 A22](https://github.com/Gegcuk/QuizMaker/issues/675)); the baseline five-family binding belongs to #701. Exact earned/possible values and normalized integer basis points (`10000 = 100.00%`) follow [#612 D02](https://github.com/Gegcuk/QuizMaker/issues/612). The strategy catalogue below describes future extensions. Manual/rubric grading remains deferred by #675 R05.

### Candidate grading strategies

```text
BINARY
PARTIAL_CREDIT
NEGATIVE_MARKING
WEIGHTED
MANUAL
RUBRIC
CONFIDENCE_WEIGHTED
MASTERY
UNGRADED
```

### Grading configuration may include

- question weight;
- section weight;
- penalty for incorrect answer;
- penalty for hint use;
- penalty for retry;
- critical questions;
- total pass threshold;
- minimum section score;
- normalization rule;
- rounding policy;
- confidence weighting;
- manual grading requirement;
- mixed automatic and manual grading.

### Reproducibility

A grade decision must identify:

- question revision;
- response contract version;
- grading strategy version;
- participant response;
- configuration version;
- result;
- decision timestamp.

Historical answers must remain gradable after the current code or question draft changes.

---

## Manual Grading and Rubrics

A submitted attempt may contain both automatic and manual items.

Manual grading should support:

- immutable revision-bound rubric;
- grader queue;
- explicit grader permission;
- item-level score;
- participant-visible feedback;
- private grader notes;
- idempotent decision submission;
- audit history;
- total-score recalculation;
- release re-evaluation.

An accepted grading decision should not be silently edited.

Correction should use explicit supersession:

```text
GradeDecision v1
    superseded by GradeDecision v2
```

Each decision records actor, time, reason, and previous decision reference.

Multi-grader moderation may be deferred, but the initial design must not store grading directly in a way that makes supersession impossible.

---

## Results

A result is a derived and versioned interpretation of eligible grades.

It may include:

- raw score;
- maximum score;
- percentage;
- pass/fail;
- section results;
- critical-question failure;
- mastery outcome;
- completion-only outcome for ungraded activities;
- selected attempt under best/latest rules.

A result must not be treated as automatically participant-visible.

### Terminal disclosure decision contract (#555)

`ResultDisclosureService` exposes the pure, versioned `ResultDisclosure` decision table.
Its trusted internal inputs describe current self-participant access, the captured category
allow-list, lifecycle, immutable result and committed release facts, review access and available
content. `Binding` compares bounded attempt/form/policy-snapshot/revision IDs and the already
verified policy hash; it never reads or hashes document content. The consumer must obtain
current `RESULT` access through #537 and read these facts in its own consistent transaction.
An `Access.VERIFIED` input is not a capability or an alternative authentication mechanism.

Every known category receives exactly one `ALLOW`, `DENY` or `PENDING` decision with a bounded
reason. Missing/denied/wrong-subject access wins before protected-context diagnostics. Unknown
context/policy/result/release versions, unknown category names, missing state and inconsistent
bindings fail closed. A terminal status alone grants nothing: an eligible category is pending
until its result and release are committed; revoked release denies it. Pending carries no data
and is never permitted by the projection helper.

`Policy.practice` adapts the existing saved #515/#516 policy without changing its schema or
canonical snapshot. The supported final practice categories are total score, question score,
correctness, correct answer, explanation, submitted answers and review. `PASS_FAIL` and a
separate `FEEDBACK` payload have no saved practice disclosure rule and are not inferred from
the existence of grading facts. R05's separate `Policy.formalDefault` permits only total score
and pass/fail; it does not select a release trigger or activate formal execution. Explicit
captured allow-lists are evaluated category by category. Permitting review never grants its
protected fields implicitly, and permitting a total never grants item details.

Current review-window/retention/revocation facts can withhold detailed review independently
of a released total. Unavailable authored content, such as an absent explanation, is denied
independently; it does not erase an otherwise permitted score. Personal-practice review has
no age cutoff in this evaluator, following the approved retained-history rule. D03's active
per-item feedback after immutable acceptance remains a separate #605 boundary; terminal
review decisions must not replace that feedback policy.

`Decisions.requested` is the contract adapter for legacy include flags and future projections:
it intersects requested categories with `ALLOW` only. Flags may hide authorized fields but
cannot reveal pending/denied ones. Later DTOs must omit disallowed fields structurally, rather
than send hidden values with a visibility flag. Cached/offline representations may consume
only already released categories and need their separately owned access/lifetime contracts;
this evaluator issues no offline grant. Existing HTTP envelopes, OpenAPI groups, legacy reads
and activation settings are unchanged.

`quizmaker.result.disclosure` counts category/outcome with bounded contract-version labels
(`1` or `unsupported`), never identities, content or input version values. Telemetry failure
does not affect decisions. The model performs no I/O, transactions, time lookup or writes;
there is no JPA relationship read or N+1 path. Unit evidence includes every explicit allow-list
combination, include-filter combinations, successful/denied controls, identity/version/binding
failures, pending/revoked release, current review restrictions and absent explanation. These
are decision-contract fixtures, not proof of real release persistence. #556 owns the actual
immediate-release transaction and participant/legacy-compatible projection; #633/#634 own
manual/scheduled writers, and #609 owns public activation.

The [22 October formal-assignment release decision](./decision-register.md#explicit-formal-result-release) requires an explicit supported release rule in the assignment's policy/settings before assignment publication. A rule already supplied by the selected policy needs no duplicate choice. If no valid unambiguous rule is resolved, require configuration before publication; do not silently choose manual release, a scheduled time, immediate release or the assignment close as a fallback. This is an assignment-publication requirement, not a new prerequisite for publishing ordinary quiz-content revisions.

Manual release retains A24's dedicated scoped authority. Scheduled release retains D37's exact persisted instant, time-zone/DST evidence and idempotent manual/scheduler race behavior. Use the approved override authority, retain the effective rule and provenance, and version supported later changes rather than rewriting released results. Result readiness and disclosure gates still apply: formal defaults expose TOTAL_SCORE/PASS_FAIL only, with other categories requiring explicit saved allowance. Preserve practice feedback, D38's explicit fixture and existing clients/legacy behavior until compatible activation.

The [22 October visible-score notification decision](./decision-register.md#visible-total-score-change-notification) requires participant notification for every actual correction to an already available total score, including increases/decreases that leave pass/fail and certificate eligibility unchanged. A 17/20 pass becoming an 18/20 pass qualifies without a minimum-delta threshold. Compare authorized before/after score values under the existing score contract; formatting/internal-only changes, unchanged visible totals and repeated handling of the same committed correction are not new score-change events.

Notification follows the durable correction/result transition under existing audit/outbox and idempotency contracts. Rollback produces no successful-change notification, and external delivery failure cannot undo the committed result. Notification is not a grant to hidden outcomes or private correction details and does not create a new retained-contact or deleted-account delivery policy. Initial release and hidden-score corrections are outside this score-change rule; [issued certificate revocation has its own approved event rule](./decision-register.md#issued-certificate-revocation-notification), while other category-only changes retain their scoped classification questions.

The [22 October channel decision](./decision-register.md#score-correction-notification-channels) makes in-app notification mandatory for these corrections. Email is an additional channel requiring the recipient's separate explicit opt-in and is off by default, including an absent preference. Email off does not suppress the in-app notification; email on does not replace it. Thus a 17/20 to 18/20 correction produces an in-app notification by default and also email when separately enabled. This category-specific decision does not change other platform emails or grant access/contact-retention rights. Email failure cannot undo the correction or remove the required in-app notification.

The [22 October email-content decision](./decision-register.md#score-correction-email-content) requires both the new total score and a link to the corresponding result: for example, “New score: 18/20” with a result link. The old score is not required by this choice. Bind the score to the committed result version represented by the notice; preserve existing access/release checks when following the link. An already delivered score copy cannot be revoked with application access, while the result page may show a newer authorized correction. This accepted consequence does not authorize hidden result details.

The [22 October consolidation decision](./decision-register.md#pending-score-correction-email-consolidation) combines pending emails for the same recipient/result and sends the latest committed eligible score with the result link. In-app notifications remain per actual change; immutable corrections and mandatory audit/outbox evidence are preserved. If 17/20 → 18/20 → 16/20 occurs before provider handoff, retain two in-app notices and consolidate the pending email to 16/20. Do not merge different results/recipients or let an older delayed event overwrite a newer pending version. Already handed-off messages cannot be assumed retractable; unknown handoff outcomes require an explicit delivery contract. Consolidation selects no extra waiting interval.

The [22 October daily retry decision](./decision-register.md#daily-score-correction-email-retries) requires one retry per day for the following seven days after an initial temporary sending failure, stopping after confirmed successful sending, exhaustion of the effective period or email opt-out cancellation. Without a new correction there are up to seven daily retries after the initial failure; repeated failures or event replays do not reset the period. Success means confirmed provider acceptance, not inbox delivery or reading. Keep delivery state durable, coordinate workers, preserve the approved latest-score consolidation and avoid resending already successful logical messages; uncertain outcomes need reconciliation rather than an assumed rejection. Email failure never undoes a committed result or removes in-app notices.

The [22 October extension decision](./decision-register.md#score-correction-retry-deadline-extension) gives a new actual score correction seven days from its authoritative committed time for active pending email retry work. Preserve the already scheduled next retry and daily cadence; no immediate extra send is introduced. A correction on day six moves the deadline from day seven to day thirteen, so seven is not a lifetime retry-count cap across distinct corrections. Use immutable correction identity/version/time; duplicate or older events, unchanged visible scores, processing delay by itself and repeated sending errors do not extend the deadline. Coordinate this with latest-score selection and dispatch, without losing newer corrections or assuming in-flight messages can be retracted.

The [22 October email opt-out decision](./decision-register.md#score-correction-email-opt-out-cancellation) cancels the recipient's still-pending score-correction emails, including consolidated and retry work. Re-enabling does not restore cancelled messages or restart their retry period. Preserve cancellation across off/on transitions, restarts and delayed/repeated events; a stale preference read or claimed retry must not bypass it. Each in-app notice and immutable result/correction/audit record remains. Already handed-off email cannot be assumed retractable; unknown outcomes require reconciliation. A genuinely new eligible correction after re-enabling can create new work under the existing rules.

The [22 October manual-attempt decision](./decision-register.md#single-manual-score-correction-email-attempt) allows an authorized operator one manual sending attempt after automatic retries are exhausted. The allowance is per logical exhausted delivery, not per operator/request; success or failure starts no new automatic cycle. Check current operator authority/scope, recipient eligibility, email opt-in and result access/release; send the latest eligible score and result link. The action cannot restore opt-out-cancelled messages, resend successful work or bypass an active/unknown sending outcome. Persist allowance, audit and dispatch intent consistently; repeated requests, concurrent operators or restarts must not create extra submissions. Preserve all immutable result/correction evidence and in-app notifications.

The [22 October operator-authority decision](./decision-register.md#platform-operator-manual-email-authority) initially limits the manual action to authorized platform operators with a dedicated permission within their assigned scope. Platform staff or generic administrator status alone is insufficient; organization representatives, teachers and quiz owners do not gain the action through those roles. Enforce authoritative principal, permission and resource/organization scope checks server-side, including revocation and missing-context denial. The permission does not itself grant access to scores, answers, contacts, grading or release. Preserve all manual-attempt limits and recipient protections; actual permission identifiers and integration with existing grants require implementation evidence.

The [22 October read-state decision](./decision-register.md#score-correction-notification-read-state) uses ordinary in-app read/unread status for score-correction notices, without separate participant acknowledgement. Notification creation and provider acceptance are not proof of reading. Maintain recipient-scoped state per logical notification, preserve it across duplicate processing and keep later corrections distinct. Corrected results take effect independently of reading; no new confirmation gate, reminder, email suppression or staff access is implied. Approved email delivery and immutable result/audit contracts remain intact.

The [22 October certificate-revocation decision](./decision-register.md#issued-certificate-revocation-notification) independently requires participant notification when an already issued certificate is actually revoked, even with an unchanged score. Preserve A26, the certificate's committed transition and immutable evidence; rollback produces no successful-revocation notice and delivery failure cannot restore validity. Duplicate handling does not create another logical revocation event, and simultaneous score correction must not hide the revocation fact. Existing recipient/privacy and account-deletion boundaries remain intact. Initial issuance, expiry and supersession are separate event classes; do not silently transfer score-only email preferences or message-consolidation rules. [Certificate-revocation channels are separately approved](./decision-register.md#certificate-revocation-notification-channels). Delivery implementation evidence remains in [R05](./open-questions.md#oq-r05), with shared retention in R03; [unknown acceptance blocks resending until clarified](./decision-register.md#unknown-notification-email-outcome), and the [N1–N8 notification choices are approved](./decision-register.md#notification-first-stage-batch).

The [22 October certificate-channel decision](./decision-register.md#certificate-revocation-notification-channels) makes in-app revocation notices mandatory and email additional, with its own explicit user opt-in, off by default. Keep certificate-revocation and score-correction email settings independent; neither an absent preference nor existing score/general email consent enables revocation email. Ivan can enable revocation emails while leaving score-correction emails off, with in-app notices still required for both eligible event categories. Preserve recipient/access/privacy boundaries and revocation effectiveness independently of delivery. [Separate score/revocation notifications are decided](./decision-register.md#separate-score-and-certificate-revocation-notices). [Revocation-email retry/cancellation/manual-attempt rules are decided](./decision-register.md#certificate-revocation-email-delivery). [Required revocation content is decided](./decision-register.md#certificate-revocation-notification-content); [predefined reasons are decided](./decision-register.md#predefined-certificate-revocation-reasons), and [unavailable-reason fallback is decided](./decision-register.md#unavailable-certificate-revocation-reason), and [separate later clarification is decided](./decision-register.md#certificate-revocation-reason-clarification), and [pending original cancellation is decided](./decision-register.md#pending-revocation-email-replaced-by-clarification), and [no resending during unknown acceptance is decided](./decision-register.md#unknown-notification-email-outcome); ordinary read-state integration remains preparation and the N1–N8 product choices are approved.

The [22 October notification-separation decision](./decision-register.md#separate-score-and-certificate-revocation-notices) keeps score-correction and certificate-revocation notices distinct even when one correction causes both. A visible 18/20 to 12/20 correction with certificate revocation requires two in-app notices. Both email preferences enabled means separate logical emails; only one enabled means only that category's email; with neither email preference enabled, both in-app notices remain required. Preserve consolidation within pending score-correction email, without merging it with or overwriting revocation messages. Deduplication and successful delivery of one category cannot suppress the other; immutable domain/audit evidence and recipient boundaries remain intact. [Revocation-email retry/cancellation/manual-attempt rules are decided](./decision-register.md#certificate-revocation-email-delivery); [required revocation content is decided](./decision-register.md#certificate-revocation-notification-content), and [predefined reasons are decided](./decision-register.md#predefined-certificate-revocation-reasons), and [unavailable-reason fallback is decided](./decision-register.md#unavailable-certificate-revocation-reason), and [separate later clarification is decided](./decision-register.md#certificate-revocation-reason-clarification), and [pending original cancellation is decided](./decision-register.md#pending-revocation-email-replaced-by-clarification), and [no resending during unknown acceptance is decided](./decision-register.md#unknown-notification-email-outcome); the N1–N8 choices are approved and delivery preparation stays scoped to R05.

The [22 October revocation-email delivery decision](./decision-register.md#certificate-revocation-email-delivery) applies daily retries for seven days after an initial temporary sending failure, cancellation of pending work when revocation email is disabled without restoration on re-enabling, and one manual attempt after automatic exhaustion by a platform operator with the dedicated in-scope permission. A score correction does not extend the revocation email's deadline; duplicate events, failures and restarts do not reset it. Maintain separate category preferences and delivery state, stop on confirmed provider acceptance, reconcile uncertain outcomes and preserve the one-attempt allowance across competing operators. Manual sending cannot restore cancelled or successful work or start a new cycle. In-app notices and certificate validity remain independent of email delivery; [required content is decided](./decision-register.md#certificate-revocation-notification-content), and [predefined reasons are decided](./decision-register.md#predefined-certificate-revocation-reasons); [unavailable-reason fallback is decided](./decision-register.md#unavailable-certificate-revocation-reason), and [separate later clarification is decided](./decision-register.md#certificate-revocation-reason-clarification), and [pending original cancellation is decided](./decision-register.md#pending-revocation-email-replaced-by-clarification), and [no resending during unknown acceptance is decided](./decision-register.md#unknown-notification-email-outcome); the N1–N8 choices are approved, with implementation preparation in R05.

The [22 October revocation-content decision](./decision-register.md#certificate-revocation-notification-content) requires the affected certificate identifier, revocation fact, concise participant-facing reason and associated result link in both the in-app notice and enabled email. Exclude internal comments and hidden/private/cross-participant data; keep all content associated with the same committed revocation across delayed delivery and retries. The result link retains current authorization/release checks, while A26's public verification remains minimal without the reason. For example, “Certificate #123 was revoked: after recalculation, the result no longer meets the issuance requirements,” with a result link. Separate score-email content and delivery contracts remain intact. [Initial reasons use predefined wording without free text](./decision-register.md#predefined-certificate-revocation-reasons). [Unavailable reason wording has an approved fallback](./decision-register.md#unavailable-certificate-revocation-reason). [Separate later clarification is decided](./decision-register.md#certificate-revocation-reason-clarification). [Pending original cancellation is decided](./decision-register.md#pending-revocation-email-replaced-by-clarification). [No resending during unknown acceptance is decided](./decision-register.md#unknown-notification-email-outcome). Catalog/mapping and provider integration evidence remain preparation; the N1–N8 choices are approved, with implementation preparation in R05.

The [22 October predefined-reason decision](./decision-register.md#predefined-certificate-revocation-reasons) initially permits only prepared participant-facing wording tied to the supported, verified revocation ground. Automatic revocations map the actual cause, and authorized human actions use the applicable supported reason. No free-text explanation, supplement, arbitrary label/template parameter or generated per-event narrative may bypass this constraint. Internal audit/correction comments remain separate under their existing access rules. Reason mapping adds neither new revocation grounds nor authority, and manual-email permission does not grant reason-authoring powers. Prepare supported reason mappings and fixtures; a missing mapping is not permission to invent a cause or apply an unrelated phrase. For an eligible valid committed revocation with unavailable wording, use the [approved fallback without holding notification](./decision-register.md#unavailable-certificate-revocation-reason); [separate later clarification is decided](./decision-register.md#certificate-revocation-reason-clarification), and [pending original cancellation is decided](./decision-register.md#pending-revocation-email-replaced-by-clarification), and [no resending during unknown acceptance is decided](./decision-register.md#unknown-notification-email-outcome); the N1–N8 choices are approved, with implementation preparation in R05.

The [22 October unavailable-reason decision](./decision-register.md#unavailable-certificate-revocation-reason) requires notifying an eligible valid committed revocation without a hold for missing reason wording. Both the in-app notice and enabled email include the certificate identifier, revocation fact, exact prepared text “Причина отзыва сейчас недоступна” and associated result link. This is the explicit exception to the actual-reason requirement; use the applicable predefined reason when available. Preserve the stored cause and immutable evidence, recipient/access/privacy boundaries, asynchronous delivery and approved retry/cancellation/manual-attempt rules. The fallback neither authorizes an invalid revocation nor guesses a cause or restores validity. Public verification remains minimal. [Later verified reason availability requires a separate clarification](./decision-register.md#certificate-revocation-reason-clarification); [pending original cancellation is decided](./decision-register.md#pending-revocation-email-replaced-by-clarification), and [no resending during unknown acceptance is decided](./decision-register.md#unknown-notification-email-outcome); the N1–N8 choices are approved, with implementation preparation in R05.

The [22 October reason-clarification decision](./decision-register.md#certificate-revocation-reason-clarification) requires a distinct in-app notice when a previously unavailable reason becomes verified and available, plus a separate email when revocation email is currently enabled and the recipient remains eligible. The authorized result view can show the verified predefined reason. Preserve the original notification/content/read state and immutable revocation evidence; clarification changes neither certificate validity nor score. Use a distinct logical clarification delivery under the approved seven-day temporary-failure retries, category opt-out cancellation and one scoped manual attempt after exhaustion. Replays do not create new notices/allowances, and the clarification cannot restart or restore original delivery work. [Cancel an unaccepted pending original in favor of the complete clarification](./decision-register.md#pending-revocation-email-replaced-by-clarification), while preserving original content and history. Accepted original email remains sent; unknown acceptance requires reconciliation.

The [22 October pending-original decision](./decision-register.md#pending-revocation-email-replaced-by-clarification) cancels the original unavailable-reason email when provider non-acceptance is established and sends only the complete clarification email under its existing rules. Preserve both in-app notices, original content and delivery history; cancellation stops old initial/retry submissions and cannot be bypassed by a stale worker or replay. The clarification retains its own bounded delivery state and current preference/access checks. Coordinate cancellation with handoff: already accepted email cannot be recalled, and an unresolved outcome is not proof of non-acceptance. This changes no certificate validity or reason and does not merge score and certificate emails. [An outcome still unknown after checks blocks resending until clarified](./decision-register.md#unknown-notification-email-outcome); the N1–N8 choices are approved, with implementation preparation in R05.

The [22 October unknown-outcome decision](./decision-register.md#unknown-notification-email-outcome) prohibits additional automatic or manual submission of the same result-notification email while provider acceptance remains unknown after available checks. Keep the in-app notice, original delivery evidence and actual uncertain status; do not infer success or failure from elapsed time. Later authoritative evidence applies the existing success/failure, opt-in/access, cancellation and retry/manual-limit contracts without resetting periods or restoring cancelled work. Independent genuine new events retain their own eligibility. This applies to score corrections, certificate revocations and reason clarifications, not unrelated authentication/security email. The [N1–N8 product decisions](./decision-register.md#notification-first-stage-batch) are approved; provider integration, ordinary read-state mapping, race handling and tests are engineering preparation, while the approved Q12 notification-retention class mapping stays in R03.

The [22 October first-stage notification batch](./decision-register.md#notification-first-stage-batch) approves in-app notices for first result availability, certificate issuance/replacement and visible validity/pass-fail changes, without new email categories. Ordinary expiry only changes displayed validity. Existing scoped correction/revocation actors may correct a published reason through a new audited version and the existing clarification-notice scheme. Permanent email failure ends that delivery; access loss cancels unsent work without restoration on access return; enabling email sends no historical backfill. Existing operator diagnostics suffice, without a new incident workflow/digest/SLA. Preserve all earlier score/revocation channel, cancellation, unknown-outcome, immutable-history and access contracts. Q12 now supplies the shared retention periods; class/cleanup mapping remains in R03 and delivery evidence in R05.

### Immediate practice release (#556)

`ImmediateResultReleaseService.release(submissionId)` is an internal command for an already
committed #603 automatic result under `FREE_PRACTICE/v1`. It neither grades an attempt nor
selects a retry budget, schedules work or exposes a release endpoint. The retained finalization
intent serializes this command with finalization and other identical release requests.
The command verifies the captured submission, form, revision, policy hash and result binding.
Missing result facts remain `NOT_READY`; unsupported or inconsistent facts fail closed.

Only this `REQUIRES_NEW` publication transaction explicitly uses `READ_COMMITTED`. Two different
results may both be absent from the release index; their locking reads must not acquire shared
`REPEATABLE_READ` gap locks that deadlock at insertion. The retained intent still serializes
requests for the same submission. Server defaults, the suspended caller and participant reads
keep their configured isolation. Regression coverage synchronizes different submissions after
their missing-row reads, requires both publications and exactly one event per result, and checks
that an older RR caller retains its snapshot while reading committed publication evidence.

One insert-only `attempt_result_release` row and one `RESULT_RELEASED` trace event commit in
the same transaction. The unique result binding is the durable receipt; it does not depend
on expiring client-command receipts. Its schema version is `1`, and its timestamp uses the
injected clock and database microsecond precision. Concurrent callers converge on the same
identity. After an uncertain commit response, retry the same submission ID to reconcile the
stored outcome. No automatic retry count or recovery policy is introduced. The release row
references its result and trace evidence; future governance cleanup must account for those
references within #699's record-family implementation. This increment adds no cleanup or
retention policy and does not backfill legacy attempts.

`ParticipantResultService` rechecks current `RESULT` access inside the participant projection
transaction, then uses #555 with matching committed release evidence. A submitted status or
calculated grade alone grants no protected category. `ParticipantAttemptView.result` is absent
until publication commits. Once released, each nested field is independently omitted unless
authorized. Practice exposes its captured total, item scores, correctness, submitted answers,
answer key, authored explanations and review availability; it does not infer `PASS_FAIL` or
a separate `FEEDBACK` payload. Missing explanations stay absent. An unanswered item has
`responseState=UNANSWERED`, no submitted answer and no correctness boolean, while its zero
points retain #603's denominator semantics.

Released answer payloads are named typed unions: `OPTIONS`, `ORDERED_ITEMS`, `BOOLEAN` and
`GAPS`. Option/item UUIDs come from the original frozen revision, matching the saved form's
display identifiers. Authoring source keys and display indexes are not response authority.
No current mutable authoring content or scoring recalculation is used by the projection.

With the participant-view flag enabled, `GET /api/v2/attempts/{attemptId}/review`
and `/answer-key` return the concrete target `ParticipantAttemptView`. Their v1 equivalents
always use the legacy service and its `AttemptReviewDto` envelope and error behavior.
Target review requires committed release and current RESULT access; include flags only
narrow permitted fields. Target IDs are never converted to legacy attempts.

Public start/answer/complete activation remains #609. Manual/scheduled release, corrections,
certificates, #837 notification events and #800 delivery remain separate integrations. This
command's trace event is not proof of notification production or delivery. Real media adapters
remain required before media-dependent target paths activate; this increment does not turn
the existing text-only adapter into evidence of image availability or temporary storage loss.

`ImmediateResultReleaseMySqlIntegrationTest` composes real publication, contract binding,
start, accepted responses, terminal submission, grading, release and HTTP participant reads.
Only external image storage and the unused legacy fallback collaborator are test doubles.
It checks concurrent successes, reached-write rollback, lost acknowledgement/recreation,
read-versus-commit/rollback, authorization, include flags and bounded SQL through serialization.
The three-parent fixture uses 102 queries for both 1 and 20 items per attempt. The local
`PracticeReviewProjectorTest` separately composes #555's real decision table and the projector
for category isolation; its decision fixtures do not prove a future runtime policy writer.

### Result visibility

Candidate scopes:

```text
PARTICIPANT_ONLY
AUTHOR_ONLY
PARTICIPANT_AND_AUTHOR
ASSIGNMENT_MANAGER
TEAM
COHORT
PUBLIC_LEADERBOARD
```

### Review content

Candidate content:

```text
SUBMITTED_ANSWERS
CORRECTNESS
QUESTION_SCORES
CORRECT_ANSWERS
EXPLANATIONS
HINTS
RUBRICS
GRADER_COMMENTS
COHORT_STATISTICS
```

### Review rules

Review may be:

- delayed;
- time limited;
- manually released;
- revoked;
- limited to incorrect questions;
- available without correct answers;
- available only after passing;
- disabled permanently.

Review uses the original attempt form and original display order.

For a completed attempt whose original form and result remain intact, [temporary image unavailability](./decision-register.md#completed-review-unavailable-image) is represented explicitly at the affected review item while otherwise permitted available content remains readable. Do not substitute an image, hide the affected question, change an answer/score or bypass release/access checks. A later authorized read can render the same bound image when it is available; exact availability transport/rendering remains implementation work. This tolerance does not apply to corrupt/missing authoritative form evidence or uncertain authorization.

[#675 A25](https://github.com/Gegcuk/QuizMaker/issues/675) approves practice review while account/result evidence is retained, and a formal window of 30 days by default within 1–365 days. The [22 October initial-retention refinement](./decision-register.md#initial-practice-history-retention) resolves the personal-practice conflict with [#664 A27/A28/R03](https://github.com/Gegcuk/QuizMaker/issues/664): initially retain personal-practice attempts/forms/answers/results and the immutable references needed for full review without scheduled age-based deletion or irreversible anonymization. Its previous twelve-month detail/answer and twenty-four-month result limits do not cause a result-only transition or removal in this phase. Every review still checks saved release, authority and revocation and uses original evidence.

Revisit this initial policy explicitly if measured storage/cost or performance raises concerns. Investigate query plans, indexes, table/media growth and backup/restore costs; access-preserving archival may address storage pressure. None of those signals automatically authorizes deletion or selects a threshold. At month thirteen or twenty-five, a personal-practice result and its authorized full review therefore remain available unless a separate applicable lifecycle rule intervenes.

This scoped decision does not change formal-assignment review/retention, technical/security metadata or temporary-record policies. Keeping evidence initially is not a decision to ignore deletion requests or to retain every record class indefinitely.

The [22 October account-deletion decision](./decision-register.md#account-deletion-history-scope) deletes the account holder's personal training history, including personal forms, answers and results, rather than retaining an anonymized training archive. Non-training attempts/results and their required evidence are preserved from that deletion. This does not reset their separate retention periods or extend review/release rights. Classify history using authoritative saved purpose and launch context; neither a missing timer nor a legacy mode proves personal practice. Historical records with insufficient classification evidence require a verified migration/deletion contract before destructive cleanup.

[Q37](./decision-register.md#preparation-batch-approved) permits unambiguously authorized parts of account/history erasure to proceed while disputed historical records remain protected pending classification, with honest incomplete progress. Do not block all proven personal-history erasure because some records lack purpose evidence, and do not guess or falsely declare full completion. Ownerless-document classification and backup replay retain their separate contracts; [Q69–Q71](./decision-register.md#historical-original-erasure-approved) now define exceptional historical-original erasure, preservation-conflict resolution and the working-storage deletion target.

[Q57](./decision-register.md#exceptional-material-display-authority) establishes dedicated scoped platform-operator authority for exceptional display restrictions. [Q63–Q66](./decision-register.md#exceptional-material-and-backup-lifecycle) now select the initial confirmed grounds, verified lifting, and separate physical-erasure controls: one authorized operator, an admissibility check, a preview of affected material/dependencies, explicit confirmation and recorded reason/outcome. Display permission alone is insufficient; no second operator is mandatory. [Q69/Q70](./decision-register.md#historical-original-erasure-approved) permit exceptional erasure despite historical references for confirmed disclosure of others' personal data or secrets; use-right grounds must cover retaining the original, not merely its display. A preservation conflict keeps erasure pending until explicit recorded resolution: retain, release or narrow the hold. The same operator may act with both required grants; destruction permission does not automatically override preservation. Conflicting external mandatory requirements are not resolved by an invented application priority.

[Q71](./decision-register.md#working-storage-erasure-target) targets erasure from working storage within 24 hours after final exceptional-erasure authorization with scope/obstacles resolved, or after a confirmed personal-training-history request and its existing prerequisites, including ending a selected active training attempt. There is no additional cancellation grace period or new fixed operator-review SLA. Q37's independently authorized parts proceed while disputed records remain pending. Persist scope/timing across retries; incomplete or overdue erasure must not be reported as completed. Validate this target against the actual installation before activation.

An uploaded source document and a delivered examination stimulus may have different dependencies. Prove whether deleting a source PDF preserves independently retained delivered content; do not infer a blanket deletion permission. Exceptional erasure of a delivered image may permanently prevent full reconstruction. Cover affected managed copies/representations within the verified scope and current authority; retain independently permitted identities, relationships and result facts plus bounded deletion evidence. Never replace historical content with a current image, copy erased sensitive content into audit, or cascade erasure into unrelated answers/results. Q36's partial review marker, Q27's confirmed irrecoverable ungradable active-attempt cancellation and existing correction/certificate rules retain their distinct triggers. Erasure alone does not change grades, revoke certificates, extend clocks or grant retakes.

For data authorized and erased from working storage at time T, Q65 permits isolated old backups to expire without immediate rewriting but requires the erased data to disappear from them by T plus 30 days. Replay applicable deletions before opening restored user access, exclude erased data from subsequent usable backups and report backup erasure as pending until complete. A restore/rebackup does not restart the deadline. Verify actual DB/object/backup coverage; do not treat the 30-day bound as permission to retain live deleted history or erase unrelated formal/shared evidence. For example, hiding a telephone-list image under Q36 is not proof that its originals/backups have been erased.

Account deletion must not cascade into another participant's history or shared immutable content/media needed by retained evidence. For example, deleting personal practice on a quiz must preserve an exam result and the same revision needed to interpret it. The [minimal retained-identity decision](./decision-register.md#retained-non-training-identity) keeps that non-training result attributable, with identity visible only to users with current access rights. Recheck result/identity authority and applicable resource, organization and disclosure boundaries on each read, including newly served reports/exports/cached responses. A teacher who loses those rights can no longer retrieve the retained identity; having taught the participant previously is insufficient.

The retained identity consists of the assignment's participant name/designation and participant identifier, with no contact data. Account deletion alone [does not revoke already issued non-training certificates](./decision-register.md#certificate-validity-after-account-deletion) or [disable their existing public verification links](./decision-register.md#certificate-verification-after-account-deletion). [Q03/Q07–Q12](./decision-register.md#quiz-certificate-batch-approved) fix the identity snapshot at attempt start with authorized versioned correction, permit separate selected/all personal-training history deletion, scope holds to the deployment's platform operator, initially disallow organization retention overrides, and archive organizations on closure under existing rights/retention. Notification history follows the related result/certificate lifetime; detailed technical delivery traces last 90 days after completion, while minimal safe cancellation/deduplication/unknown-outcome facts and mandatory audit keep their distinct purposes. Contacts of deleted accounts are not retained. Retaining identity does not authorize copying a profile, rewriting submitted answers, restoring account access or making identity public. Historical classification, exceptional shared-content erasure, backup/concurrent-write handling and class/cleanup evidence remain [R03 preparation/questions](./open-questions.md#oq-r03).

---

## Teacher Reporting

The assignment and reporting model should eventually support:

- participant status;
- not started / in progress / submitted;
- remaining time where appropriate;
- grading pending;
- result released;
- selected result;
- section performance;
- question-level aggregate performance;
- completion rate;
- score distribution;
- manually graded work queue;
- form composition metadata;
- audit access for disputes.

Reporting must distinguish:

- assignment-level facts;
- attempt-level facts;
- result-selection projections;
- learning analytics;
- legacy analytics.

Participant answers and identities must not be exposed through aggregate endpoints unless authorization and privacy rules permit it.

[#679 A32/D35](https://github.com/Gegcuk/QuizMaker/issues/679) requires at least five eligible distinct participants for public/cohort aggregate cells, with explicit suppression and protection against complementary-cell inference. A participant's own authorized result does not depend on that cohort threshold. Reporting requires the dedicated permission in the exact assignment/organization scope; quiz ownership alone is insufficient.

[Q24/Q25](./decision-register.md#quiz-certificate-batch-approved) approve separately authorized individual teacher summaries of released identity/status/result without raw answers, and bounded aggregate CSV export with its own permission and the same k=5/complementary-cell and current-access checks. Individual summaries do not authorize individual-row or raw-answer export. [Q23](./decision-register.md#best-result-and-risk-retake) supplies BEST_VALID and informed risk-retake selection; [Q33](./decision-register.md#quiz-certificate-followup-approved) separates success among completed, completion among assigned and success among assigned. For 100 assigned, 60 completed and 45 passed these are 75%, 60%, 45%.

[Q45–Q48](./decision-register.md#preparation-batch-approved) refine those reports: technical cancellation without an eligible result remains in the assigned base, separately labelled and not counted as failed; a cancelled retake does not erase a valid prior selected result. Historical periods retain their actual assignment/roster history despite later membership changes. Interim statistics may show the eligible released subset with explicit coverage: 30 successful among 40 released out of 60 completed is 75% among released, not an unlabeled final success rate for all 60. Privacy applies to coverage as well as grades. Initially reject oversized foreground aggregate CSV and request narrower filters; no silent truncation or background large-export feature. [Q56](./decision-register.md#late-technical-risk-invalidation) supplies the selected result after later technical invalidation of a released risk retake; reports must use that versioned selection and its ordinary release/eligibility rules. [Q68](./decision-register.md#aggregate-csv-first-stage-limits) sets the first aggregate CSV target to at most 10,000 rows and at most 10 MiB (10,485,760 bytes), both applying. A 12,000-row, 8-MiB report requires narrower filters under Q48. Define counting/encoding and cancellation precisely and validate query, memory and concurrent-load behavior before claiming support; do not silently lower the selected target or truncate output. Query/form equivalence, eligibility, correction/selection races and empty denominators remain preparation.

---

## Certificates

Certificate-bearing examinations have configured finite submission/attempt bounds and a finite attempt allowance, **one attempt by default**. The [Q29 owner refinement](./decision-register.md#certificate-after-examination-finality) selects **immediate issuance after an eligible successful final outcome by default**, with explicit deferred result/certificate conditions. Certificates still require examination finality and an eligible released result; ordinary continuation must not cause provisional issuance followed by revocation.

Model completion opportunity, result release and certificate release separately. A one-attempt result may await the configured deadline or responsible-person approval. Multiple attempts may share a finite submission window measured from the first start; later starts do not restart it. A certificate may be issued after success, approval or the configured certificate-release date/deadline. A delayed certificate does not extend the answer window, and approval does not introduce manual grading. No numerical duration is selected; saved configuration carries the applicable defaults and explicit release rules. Existing untimed training and legacy APIs keep their approved compatibility boundaries.

[Q42–Q44](./decision-register.md#preparation-batch-approved) make completion and issuance behavior creator-configurable when the certificate-bearing quiz is created, captured in its immutable execution/assignment configuration. Support automatic finalization on eligible success and an explicit learner-choice mode to finalize or keep improving within saved limits. Continuing never issues a provisional certificate. Configured mandatory release conditions compose with AND: an approval on Thursday plus release not before Friday permits issuance no earlier than Friday; approval only on Monday delays it until Monday. When issuance awaits the submission deadline, a passing result alone does not close otherwise eligible improvement attempts under the chosen completion mode. Keep the existing one-attempt/immediate-success default; do not infer a universal multi-attempt mode.

For example, a successful single attempt with immediately available result produces its certificate immediately. In a three-attempt automatic-success configuration, passing the second closes the exam; in a learner-choice configuration, the participant can finalize or use the last opportunity before issuance. A seven-day first-start window is only an illustration, not a default. Date/approval gates do not grant continuation beyond the saved window. Finalization, active/new starts, approval tied to a result version, extensions and issuance need [concurrency/conformance preparation](./open-questions.md#prepared-p1). Actual correction/invalidation still follows A26.

Certificate issuance must be:

- idempotent;
- based on an immutable result reference;
- attributable to a policy version;
- auditable;
- revocable or invalidatable through an explicit lifecycle if corrections are supported.

A certificate must not be issued for:

- pending manual grading;
- unreleased results;
- invalidated results;
- ungraded activities;
- attempts that fail certificate policy.

Public verification, if introduced, follows [A26](./decision-register.md#a26): only the current minimal validity state is disclosed, without private correction details. Its capability is separate from assignment access, attempt execution and private result review.

The [22 October owner decision](./decision-register.md#certificate-validity-after-account-deletion) preserves already issued non-training certificates from revocation merely because the participant deletes their account. The existing certificate/result/policy binding and required verification evidence survive that account cleanup. Existing expiry and A26 correction/invalidation/idempotent replacement rules still apply; deletion does not reissue a certificate, reset expiry or reactivate an expired/revoked/superseded certificate. For example, deleting an account in October does not change an issued certificate's 31 December expiry or protect it from a later result invalidation.

The existing R03 minimal verification-record retention baseline remains while valid and twelve months after expiry/revocation/supersession; this does not extend all detailed attempt/answer retention. The separate [22 October verification-link decision](./decision-register.md#certificate-verification-after-account-deletion) preserves previously issued public certificate-verification links from disablement merely on account deletion. Their own scope, expiry and revocation rules remain unchanged; expired/revoked verification access is not restored and need not depend on the holder retaining a login account. Preserve its scoped binding and minimal evidence through account cleanup without retaining unrelated account credentials or capabilities.

A usable verification link returns the current minimal certificate state, including an expired/revoked/superseded state where applicable; it does not guarantee that the certificate is still valid. Neither the link nor preserved certificate validity grants public access to the retained participant identity, full result, answers or private correction history. For example, an employer using the original QR after the holder deletes the account can still check the certificate under the link's existing rules, but receives its current status rather than a stale pre-deletion assertion.

---

## Ungraded and Self-Assessment Activities

Not every activity has a correct answer or numeric score.

The platform should support:

- reflective free text;
- confidence rating;
- self-rating;
- surveys without correct answers;
- participation-based completion;
- mixed graded and ungraded sections.

An ungraded activity must not create:

- fabricated score;
- fabricated correctness;
- pass/fail;
- certificate eligibility;
- assessment leaderboard entry.

Ungraded responses may require additional privacy and consent rules beyond ordinary assessment answers.

---

## Accessibility and Accommodations

Accommodations are authorized overlays applied before attempt creation.

Candidate overlays include:

- additional time;
- disabled per-question timer;
- stable question order;
- stable option order;
- adjusted navigation;
- additional pause allowance;
- alternative delivery setting.

The attempt snapshot stores only the applied code, version, and effective policy changes.

It should not contain medical evidence or detailed sensitive rationale.

An accommodation:

- does not modify the assignment for other participants;
- cannot be self-declared through the attempt request;
- cannot silently alter an active attempt;
- must remain auditable under restricted access.

Accessibility also requires:

- screen-reader-friendly navigation;
- no dependence on visual A/B/C positions;
- semantic labels;
- stable order after reconnect;
- preserved scale ordering;
- predictable focus and error handling.

[Q16/Q17](./decision-register.md#quiz-certificate-batch-approved) choose the maximum valid EXTRA_TIME_25/50/100 increase without addition and round computed duration up to seconds. Resolve before start and retain the grant snapshot. The earliest absolute assignment close still wins: a participant nominally entitled to 45 minutes with only 25 left may start after an explicit shorter-time warning. There is no automatic assignment extension or mutation of an active captured allowance. Bounds/overflow/compatibility evidence remains preparation.

---

## Adaptive Execution

Adaptive selection is a progressive-form strategy.

Inputs may include:

- previous correctness;
- response time;
- answer confidence;
- topic mastery;
- question difficulty;
- prerequisites;
- current attempt evidence;
- prior learning state where privacy policy permits it.

Stopping rules may include:

- mastery threshold reached;
- diagnostic confidence reached;
- minimum questions completed;
- maximum questions reached;
- time exhausted;
- no eligible questions remain.

Every next-question decision is persisted before delivery.

Initial adaptive execution should use deterministic project-owned rules rather than an opaque external model.

[#685 D25](https://github.com/Gegcuk/QuizMaker/issues/685) approves current-attempt accepted correctness plus versioned difficulty/topic metadata, a minimum of five items, a maximum of twenty, and an 8000-basis-point mastery threshold after the minimum. The [approved Q18 contract](./decision-register.md#adaptive-bank-sufficiency-direction) permits incomplete drafts but requires sufficient distinct eligible questions for every supported selection path to its normal stopping bound before executable publication. Revalidate applicable participant-specific dynamic eligibility before start without partial attempt/allowance on rejection, and provide bounded author diagnostics by level/topic/reserve. Author-configured lower/higher reserve levels and their explicit preference order apply only after genuine requested-level depletion. No global fallback distance/direction/order default is selected. This narrowly refines the former blanket difficulty-fallback prohibition, not other topic/access/exclusion, grading or D25 evidence boundaries. Checking five questions or even twenty globally cannot prove all eligible paths; a non-mastering path may need twenty. The immutable policy/bank basis stays fixed; a later publication cannot repair an active attempt by substitution. The proof algorithm and conformance evidence are [engineering preparation](./open-questions.md#oq-adaptive-sufficiency-proposal). Unexpected integrity/storage/implementation failure remains exceptional. [Q27](./decision-register.md#quiz-certificate-followup-approved) selects technical cancellation without a grade after authorized confirmation of irrecoverability and inability to assess reliably. Saved evidence remains; a new attempt needs separate authorization and no allowance is automatically refunded. Temporary image outage remains Q02; unknown/recoverable states retain integrity/quarantine gates and exact repair preparation.

Adaptive selection must remain distinct from branching:

- adaptive selection chooses a suitable question from an eligible bank;
- branching follows authored scenario edges.

---

## Branching Scenarios

A branching revision contains an immutable directed graph.

```text
ScenarioGraph
├── nodes
├── entry node
├── edges
├── server-evaluated conditions
├── terminal outcomes
└── graph version
```

Publication validation must detect:

- missing entry node;
- missing targets;
- unreachable nodes;
- forbidden cycles;
- invalid conditions;
- ambiguous transitions;
- missing terminal behaviour.

The participant cannot request an arbitrary branch or hidden node.

Each selected edge is persisted as execution evidence.

Review normally displays only the participant’s path unless broader exploration is explicitly permitted.

For v1, [#686 D27/D28/R16](https://github.com/Gegcuk/QuizMaker/issues/686) requires an acyclic graph, hides alternative paths from participants, and makes a reached terminal node terminal-ready rather than automatically submitted. Final submission remains explicit; the remaining required-data/deadline/confirmation semantics are still open in that issue.

---

## Learning and Spaced Repetition

Spaced repetition and mistake review belong in a separate Learning bounded context.

They reuse:

- immutable question revisions;
- participant-safe content representations;
- selection and ordering infrastructure where appropriate.

They own:

- per-learner due state;
- review intervals;
- last-reviewed timestamp;
- confidence/self-rating;
- scheduling strategy version;
- mistake history;
- learning-session forms.

Learning sessions do not create assessment results unless explicitly designed as assessments.

Candidate learning selections include:

```text
DUE_FOR_REVIEW
INCORRECT_ONLY
UNSEEN_ONLY
LOW_CONFIDENCE_ONLY
TOPIC_MASTERY_GAP (future; requires separately approved mastery definition)
```

[#681 A33–A36](https://github.com/Gegcuk/QuizMaker/issues/681) selects pinned versioned FSRS-6 and quiz-local Learning state. Active sessions finish against their original question revision; a new revision starts separate state unless a separately approved lineage migration transfers it. [Q49–Q53](./decision-register.md#preparation-batch-approved) select the first configuration to validate: desired retention 90%, learning steps 1 and 10 minutes, relearning 10 minutes, profile-local calendar days, no additional fuzz and a shared fixed parameter vector with individual state/schedules. Profile calendar grouping does not alter authoritative due instants or silently reschedule deadlines. [Q67](./decision-register.md#learning-maximum-interval) selects a 3650-day maximum interval for this first configuration. [Q91](./decision-register.md#fsrs-transcription-implementation-selection) selects Java FSRS 1.0.0 and its full explicit 21-weight vector. Bind the complete versioned manifest, supply project-owned identity/clock values, and verify dependency compatibility, exact rating/rounding/overdue/timezone mapping, the selected maximum, replay and workload before activation. The selected model target is not measured recall quality.

The [clean-start clarification](./decision-register.md#learning-clean-start) confirms that no current quiz/question uses repetitions. New Learning starts clean by explicit user action, without SM-2 import, mandatory old-route adapters or dual-writer cutover. Do not infer FSRS state from old fields or ordinary quiz scores. Verify the target-scope assumption before any later obsolete-code/data cleanup; ordinary quiz and training API behavior remains protected. Personal model fitting and any actual future import are separately scoped.

### First enrollment, selection and public progress

[23 October scope](decision-register.md#learning-initial-selection-and-mastery) selects bounded page/session sizes without a daily Learning quota. With 100 due items and a batch of 20, finishing the batch permits continuing that day. Command-rate limits are independent. Public mastery is deferred to #847; desired retention, exam scores and finite-plan completion retain distinct names and meanings.

#578 owns the explicit authenticated `EnrollLearningItems` boundary and invokes #649. Start/resume/due reads do not silently enroll or advance schedules. #649 owns common active-cycle uniqueness across strategies; #839 reuses it for custom enrollment/restart/switch. The first #667 fixture starts with empty new Learning tables and calls real enrollment/start/review commands. No schedule repository seeding substitutes for a reachable user action.

A switch uses an expected current cycle/version; old committed review receipts stay attached to their historical cycle and cannot advance the replacement. Finish captured active sessions before switching as already approved. Timezone preview confirmation in #840 compares the preview's cycle/schedule version; a review committed since preview requires a fresh preview. Nested membership in #841 rejects self/ancestor cycles even under concurrent edits; bounded paging alone does not bound graph traversal.

<a id="custom-learning-intervals"></a>
### User-defined repetition intervals — Q94–Q114 APPROVED

**Approved on 22 October 2026:** a learner may use a personal interval sequence, review both questions and quizzes, and organize review through nested Learning sets. The [Q94–Q114 decisions](./decision-register.md#custom-interval-decisions) include unit-preserving duration/calendar scheduling and DST resolution, a two-interval rollback, coefficient-based indefinite continuation and nondecreasing entered sequences. All Q108–Q113 answers are A, including the explicitly accepted DST rules. [Q114 A](./decision-register.md#custom-interval-decisions) also approves nominal mixed-unit ordering with unchanged due calculation. No product question remains open within this selected scope; implementation evidence is still required.

For example, a learner preparing for an anatomy examination can use `1 day, 3 days, 7 days, 14 days`, a quiz default and a different plan for one difficult question. The first review is available immediately; later intervals follow actual review activity. Again on the review due after the 14-day step returns to the 3-day step. Successful advancement after the final entered step completes a finite plan; in indefinite mode the default coefficient 2 produces 28, then 56, then 112 days, subject to the 3650-day ceiling. Day scheduling uses the learner's selected local time, such as 09:00; that example time is not a default.

This is a scheduling capability within **Learning**, using its project-owned scheduling boundary. It is not a new strategy line in the legacy repetition feature and does not replace or reconfigure the approved first FSRS release. The approved scope does not select a delivery date, activate custom schedules or establish implementation readiness.

#### Existing architectural boundaries

- Learning state remains personal, quiz-local and bound to immutable question revisions. Reusing a sequence definition across quizzes does not share progress or merge similar questions. A new content revision follows the already approved separate-state rule; custom intervals do not authorize lineage transfer.
- A schedule does not grant access to content. Existing permission, visibility and release rules apply at start, review and resume; a custom plan cannot expose a hidden examination question, answer or explanation. Within content already available for Learning, Q95 lets the learner select FSRS or a personal plan without an additional author-controlled strategy restriction.
- A custom review is not an assessment result. Its progress, completion, pause or restart cannot alter an examination timer, accepted answer, final grade, attempt allowance or certificate.
- New Learning starts explicitly under its own approved context. Because no current quiz/question uses repetitions, the new design need not inherit `Quiz.isRepetitionEnabled` or adapt the unused legacy writer. Content access and strategy permissions remain server-enforced; ordinary training is unaffected.
- Each active schedule has one authoritative strategy/version. Durable review identity, state version and idempotency prevent one event from being applied twice. Any strategy switch must be explicit and preserve evidence; it must not fabricate FSRS state or reinterpret old reviews.
- Persist the exact sequence/configuration version and the facts needed to explain transitions. Q101 applies edited definitions to new bindings only. Existing schedules retain their captured versions and due dates; applying a changed plan is an explicit confirmed new cycle. An active Learning session retains its captured strategy/content until its safe completion boundary. Q102 preserves history without inferred progress transfer when restarting or switching FSRS/custom.
- Use injected time, preserved interval units and persisted due instants. Q109 defines duration/calendar calculations below. The selected FSRS arithmetic and profile-local activity grouping remain unchanged. A late worker or restart does not by itself prove that a learner reviewed anything.

#### Approved lifecycle and selection

A finite sequence has an explicit completed state with no next due review after its required progression; it does not mean “assessment passed” or prove mastery. Again at the final step must follow the chosen rollback rather than claim completion. The learner may restart a completed plan or switch strategies through a confirmed new cycle. Pausing hides items from active due selection without freezing their due dates; resuming shows an overdue item once. Explicit early review may advance a step, while ordinary extra practice does not.

Quiz and individual-question review are both included, along with nested sets. For example, a learner can choose “Biology”, its nested “Anatomy” set, a quiz within it or a particular question. Under Q108 these are entry points selecting the same authoritative quiz-local question schedules, recursively for nested sets. Reviewing a question through Anatomy does not make it due again when opening Biology. There is no independent quiz/set schedule or automatic advancement of all members on quiz completion. Whole-quiz practice remains available; non-due questions advance only through explicit early review. Selection does not grant access to unavailable content, merge separate quiz/revision identities, start a formal assignment or consume an examination attempt. Flashcard feature expansion is not introduced by the analogy.

Personal reusable plans are private initially. Q96 permits a quiz default for new enrollments and explicit question overrides. Reusing a definition across quizzes does not merge progress. Nested organization does not silently introduce inherited scheduling overrides or cross-quiz identity. Shared templates and teacher-controlled updates to personal schedules remain outside this increment.

#### Calendar and duration calculation

Q109 preserves the entered scheduling unit. Minutes/hours are elapsed durations from the actual advancing review. Day intervals add calendar days to that review's profile-local date, at the learner-selected local time. Thus ten minutes remains ten minutes, while one day can mean tomorrow at 09:00. Do not convert a day into 24 elapsed hours before calculating its due instant. Late and explicit early reviews anchor their next interval to their actual review, without manufacturing missed events.

For a local time skipped by a clock change, choose the first valid local time afterward. For a local time occurring twice, use its earlier occurrence once. A repeated clock hour must not create two due items or two review events. Existing due instants are preserved on a profile timezone change unless the learner explicitly confirms a preview of the resulting rescheduling. Persist enough zone/local-time and resolved-instant evidence to explain the calculation and reproduce the selected transition; do not rely on an unexamined library resolver default.

#### Entered stages, rollback and generated continuation

Q106/Q113 accept 1–100 positive entered intervals, from 1 minute through 3650 days, with whole-minute duration precision and supported hour/day input units. Entered intervals must be nondecreasing; equal intervals remain allowed. Reject zero/negative/empty/decreasing sequences with an explanation rather than silently sorting them. For example, `1, 14, 3, 7` days is invalid. Q114 compares nominal lengths for validation, with 1 day = 24 hours only for ordering; due calculation still preserves the entered scheduling unit.

Q110 counts rollback from the interval of the review being performed, before successful advancement: in `1, 3, 7, 14, 30` days, Again at 14 returns to 3. At either of the first two stages it returns to the first interval. Initial review starts at the beginning; rollback preserves review history and does not delete two events. Hard/Good/Easy advance one stage without rating-specific multipliers. Again at the final finite stage rolls back rather than completing the plan.

Under Q111/Q112, each plan version has a coefficient of at least 1, default 2. Indefinite continuation multiplies the previously produced actual interval by that coefficient, rounds upward to a whole calendar day or whole duration minute, and caps the generated interval at the selected 3650-day maximum. The rounded/capped result is the next multiplication's input: 7 days with coefficient 1.2 gives 9 days, then 11 days. Preserve the duration/calendar distinction in generated intervals too.

When a generated interval stops growing because of the ceiling or coefficient 1, retain one plateau stage instead of creating indistinguishable later growth stages. Each review remains a separate history event, and Again can return to earlier stages under Q110. Explicit equal input stages remain intentional user steps; this generated-plateau rule does not collapse them or erase history. Generate only the needed continuation state, not an infinite list of future reviews. Changes to the coefficient follow immutable plan versioning and the same explicit new-cycle boundary as other plan edits.

#### Approved mixed-unit ordering and rollback

Under Q114 A, compare entered lengths nominally to validate a nondecreasing sequence, using 1 day = 24 hours only for that comparison. Calculate actual due instants under Q109, preserving duration/calendar units. Rollback selects the earlier stage and applies its interval as entered; it does not clamp to the current stage's next date.

For example, `20 hours, 20 hours, 1 calendar day` is valid. With day appointments at 09:00, a learner reviewing the day stage at 22:00 and selecting Again returns to the first 20-hour step and sees tomorrow at 18:00 on an ordinary day. Tomorrow at 09:00 would apply to a calendar-day step, not to this rolled-back duration step. Show the resulting next date clearly; moving back two stages does not guarantee a strictly shorter elapsed wait across these unit families. This rule does not change FSRS arithmetic or existing training behavior.

Q107 initially provides due/upcoming views without new reminders, email or push. The former proposed reminder-inheritance hierarchy is not part of this scope; existing result/certificate notifications remain unaffected.

Implementation preparation requires a precise transition table with dated examples; nested quiz/question selection and identity; finite completion and generated-tail/plateau rollback; calendar/DST and overdue/early-review cases; version/switch boundaries; permission negatives; duplicate/concurrent reviews and restart; bounded due/history reads. These are verification needs, not additional product questions, restored class-by-class tasks or a new migration sequence. Mixed-unit acceptance cases must reproduce Q114 A. The [issue-alignment review](./issue-alignment-review.md#custom-interval-issue-alignment) identifies the remaining contract/implementation coverage rather than reopening approved product decisions.

---

## Live and Team Sessions

Live execution is a separate bounded context.

```text
LiveSession
├── host
├── lobby
├── participants
├── teams
├── current phase
├── current round
├── synchronized form item
├── answer window
├── provisional scores
└── leaderboard release
```

Live policy may define:

- admission rule;
- participant capacity;
- late joining;
- team membership;
- synchronized or individualized forms;
- speed-based scoring;
- answer-window duration;
- reconnection;
- participant elimination;
- leaderboard release.

The server clock controls all phase transitions.

Live orchestration may reuse ordinary form items, response contracts, and grading strategies, but it must not add live-specific branches throughout the ordinary attempt service.

[Q19–Q22](./decision-register.md#quiz-certificate-batch-approved) approve host-managed teams with a random-allocation action before lock, default two teams and a nonempty team count bounded by participants/overall capacity. [Q32](./decision-register.md#quiz-certificate-followup-approved) balances random allocation with team counts differing by at most one, without ability/profile-based placement. The host can inspect/edit before locking; persist the allocation rather than reshuffling after reconnect. Individual late joins begin at the next round, while locked team rosters remain fixed. Disconnect alone does not remove a participant from early-close waiting, but explicit authorized removal does. For equal scores, sum correct-answer server elapsed time plus full effective durations of other rounds; keep the existing score/time/earlier-completion ordering and no speed bonus to points. Visibility, capacity and release restrictions remain; event-window/recovery and random-allocation conformance evidence still need preparation.

---

## Offline Boundary

Offline behaviour is explicit opt-in functionality, not a general permission to execute without the server.

[#682 D18–D20](https://github.com/Gegcuk/QuizMaker/issues/682) limits Offline v1 to untimed personal free practice with a precomputed fixed form. Packages are subject/attempt-bound, not device-bound, use versioned authenticated encryption, and last at most seven days bounded by attempt/capability expiry. Formal, timed, progressive, Adaptive, Branching and Live execution are ineligible in v1.

An offline package may contain:

- participant-safe immutable form data;
- package version;
- package expiry;
- integrity information;
- display order;
- already authorized participant instructions.

It must not contain:

- correct answers;
- unreleased explanations;
- capability secrets;
- grader data;
- privileged policy internals.

While offline, the client may:

- display already delivered package content;
- collect local drafts or queued commands according to the client contract.

It may not:

- start an attempt;
- authorize a participant;
- choose adaptive questions;
- evaluate branches;
- extend the form;
- trust its clock for final timing;
- grade answers authoritatively;
- release results.

Every replayed command is revalidated after reconnect.

---

## API Representation Boundaries

A universal `QuestionDto` with nullable privileged fields is prohibited.

Separate purpose-specific schemas are required.

### Authoring representation

May contain:

- answer keys;
- explanations;
- rubric;
- internal metadata;
- unpublished content;
- validation warnings.

### Participant representation

Contains only:

- delivered question content;
- visible options in saved order;
- response schema;
- permitted instructions;
- current allowed actions;
- permitted timing summary.

### Answer acknowledgement

Contains:

- accepted submission reference;
- answer state;
- server timestamp;
- permitted feedback;
- updated actions;
- updated lifecycle state.

### Grader representation

Contains:

- submitted response;
- permitted participant context;
- immutable rubric;
- current grading state;
- previous decisions where authorized.

### Review representation

Contains only categories currently released by the review policy.

### Reporting representation

Contains authorized result and aggregate projections, not authoring DTOs.

---

## API Contract Principles

Every public operation must:

- belong to one logical grouped OpenAPI document;
- use named request and response schemas;
- define authentication or capability authorization;
- define expected RFC 7807 errors;
- define idempotency where mutating;
- define concurrency and stale-version behaviour;
- define ordering;
- define pagination and filtering where lists are exposed;
- preserve additive client compatibility;
- avoid exposing internal policy implementation details.

A participant-facing policy summary may expose:

- duration;
- attempt count;
- navigation restrictions;
- feedback timing;
- deadline;
- whether answers may be changed.

It should not expose:

- hidden strategy thresholds;
- answer-key metadata;
- candidate question bank identifiers;
- private accommodation rationale;
- internal security rules.

---

## Command Model

Attempt interactions should be represented as application commands rather than controller-specific mutations.

Candidate commands:

```text
StartAttempt
ViewAttempt
SaveDraft
DeleteDraft
DeliverCurrentItem
RequestNextItem
NavigateToItem
SubmitAnswer
SubmitSection
RequestPause
ResumeAttempt
SubmitAttempt
ExpireAttempt
ReleaseResult
ReleaseReview
```

Each command:

1. resolves actor and subject;
2. loads immutable policy and form;
3. checks lifecycle;
4. checks timing;
5. checks assignment and capability state;
6. validates the command;
7. applies one atomic transition;
8. records required evidence;
9. returns the updated participant-safe representation.

---

## Persistence Model

A possible conceptual schema:

```text
quiz
quiz_revision
question_revision
quiz_revision_section
quiz_revision_case_block
quiz_revision_item
question_option_revision
rubric_revision

run_preset
run_preset_version

assignment
assignment_version
assignment_audience
assignment_capability

attempt
attempt_participant_subject
attempt_policy_snapshot
attempt_form
attempt_form_item
attempt_form_option_order
attempt_progressive_decision

answer_draft
answer_submission
answer_submission_version

grading_record
manual_grading_queue
grade_decision
grade_decision_supersession

attempt_result
result_release
review_release
certificate

attempt_command_idempotency
attempt_execution_event

learning_session
learning_form_item
learning_schedule

live_session
live_membership
live_team
live_round
live_response
```

The exact table split may differ, but ownership and immutability boundaries must remain clear.

### Database rules

New persistence uses additive Flyway migrations with:

- foreign keys;
- uniqueness constraints;
- check constraints where appropriate;
- optimistic version fields;
- indexes for start eligibility;
- indexes for resume and grading queues;
- bounded idempotency retention;
- explicit retention ownership;
- no destructive reinterpretation of legacy rows.

JSON policy snapshots should be accompanied by searchable typed columns only where operational queries require them. The JSON snapshot remains authoritative for the attempt.

---

## Snapshot Versioning

Each persisted policy, form, response contract, grading algorithm, graph, and scheduling strategy requires a version.

Example:

```text
policySchemaVersion: 1
formSchemaVersion: 1
selectionAlgorithm: stratified-random/v2
questionOrderingAlgorithm: seeded-fisher-yates/v1
gradingAlgorithm: mcq-partial-credit/v3
responseContract: multi-option/v2
```

Unknown versions fail closed.

The system must not:

- assume the latest implementation;
- silently migrate at read time;
- replace an unknown strategy with a default;
- regenerate a historical form.

Operational tooling may later support controlled offline migration, but it must not happen implicitly during participant requests.

---

## Hashing and Integrity

Useful integrity values include:

- policy hash;
- form hash;
- revision content hash;
- response-contract hash;
- execution-event chain hash where approved.

Hashes support:

- corruption detection;
- audit evidence;
- duplicate detection;
- operational comparison.

A hash is not a replacement for storing the actual immutable evidence.

If a persisted hash does not match:

- do not regenerate from mutable content;
- stop the affected operation safely;
- record an operational incident;
- return a non-sensitive support-oriented failure.

---

## Execution Trace

### Dark transactional trace storage (#538)

`ExecutionTraceService.append` stores internal evidence only and requires an existing caller
transaction. It neither executes nor authorizes a business command. The initial typed v1
vocabulary is `ATTEMPT_STARTED`, `FORM_CREATED`, `QUESTION_DELIVERED`, `ANSWER_SUBMITTED`,
`ANSWER_LOCKED`, `ATTEMPT_SUBMITTED`, `AUTOMATIC_GRADE_PRODUCED` and `RESULT_RELEASED`.
Defining an evidence type does not implement or activate its producer. Other event types and
versions fail closed; future capabilities extend their own explicit contracts.

V90 adds `attempt_trace_stream`, `attempt_trace_identity` and `attempt_trace_event`. A stream
references an existing target lifecycle and authenticated-subject binding plus an immutable
policy snapshot and form from the same quiz revision. Its scope cannot change on replay.
There is no legacy backfill, inference from mutable quizzes or cascade deletion. The subject
reference is the existing attempt binding, without copying user IDs or contacts into events.
The policy snapshot retains its original hash. No form fingerprint is fabricated before the
separately owned fingerprint contract is available; document content is never read or hashed.

Appending locks the stream root and reserves a globally unique server event UUID. Reserving
an existing identity row before replay lookup avoids shared absent-key gap locks between
unrelated first appends under `REPEATABLE_READ`. The reservation, event and sequence counter
commit or roll back with the caller's business writes. Identical scope/type/actor/metadata
returns the original sequence and server timestamp, including after restart or a lost commit
acknowledgement. Changed semantic data or cross-attempt reuse conflicts. Sequence order is
the serialized append order, not timestamp order or a promise about which racing caller wins.
Rolled-back allocations consume no sequence. No automatic deadlock or database retry occurs;
an uncertain commit must be reconciled in a fresh transaction with the same identity/data.

There is no arbitrary JSON, text, map or extension payload. Metadata is limited to an internal
command reference, optional form-item reference, optional paired typed lifecycle states and a
closed reason code. The actor is participant or system. Constructors, exact enum parsing,
database checks and form-item foreign keys reject unsupported or incompatible values.
Diagnostics omit identities and rejected values. Counter/tail inconsistencies, unknown stored
versions and corrupt metadata fail closed. The writer exposes no event update/delete method;
this boundary does not change database-administrator privileges or implement retention jobs.

The repository's sequence cursor reads at most 100 rows per call through the attempt/sequence
primary key. This is an internal I/O bound, not a public pagination or learning allowance.
It is not the protected purpose/authorization query service (#540) or operator HTTP API (#627).
Metrics use only fixed version/type/outcome labels; `append_returned` and `replay_returned`
describe the method return, not successful commit of an outer transaction. Telemetry failure
cannot replace a business result or commit partial evidence.

`ExecutionTraceTest` and `ExecutionTraceMySqlIntegrationTest` cover the structural allow-list,
real prerequisite persistence, immutable replay, reached-write rollback, concurrent append and
identity conflicts, stale snapshots, restart/commit ambiguity, real MySQL deadlocks and JDBC/JPA
transaction composition. Multi-parent fixtures measure one bounded SQL query per repository
page and inspect the primary-key plan; no JPA relationships or document content are loaded.
These are component persistence proofs, not completed start/answer/grading/release integrations.
Their real producers remain owned by #600/#543/#529/#603/#556 and activation by #609.
Existing legacy routes, grading, immediate practice feedback and public schemas remain unchanged.

Approved A29's 90-day-after-terminal retention and documented hold exceptions remain separate
from this writer. Cleanup/hold execution and protected query behavior require their owning
implementations. Disable unused code before first target writes; after writes, preserve evidence
and use additive forward fixes rather than destructive schema downgrade.

### Candidate extensions and access evidence

Full event sourcing is not required.

However, the system needs append-only evidence sufficient to explain important outcomes.

Candidate events:

```text
AttemptStarted
FormCreated
QuestionSelected
QuestionDelivered
DraftSaved
AnswerSubmitted
AnswerLocked
PauseRequested
AttemptPaused
AttemptResumed
SectionCompleted
AttemptSubmitted
AttemptExpired
AutomaticGradeProduced
ManualGradeProduced
ResultFinalized
ResultReleased
ReviewReleased
ReviewRevoked
CertificateIssued
```

An event should contain only the minimum evidence necessary:

- event type;
- sequence;
- server time;
- actor/subject reference;
- policy hash;
- form hash;
- state transition;
- bounded reason code;
- command idempotency reference.

[Q34/Q35](./decision-register.md#preparation-batch-approved) define a distinct mandatory access-evidence contract: viewing another person's personal result, exporting a report and protected operator investigation require durable evidence. Own-result, privacy-safe aggregate and public-verification views do not require a mandatory event per view. Deny a sensitive consequential operation if required local evidence cannot be committed; do not block exempt views merely for that failure. All ordinary authorization/disclosure checks still apply. External audit-export outage does not undo the committed action. Preserve a non-exportable event and an explicit gap while letting later events export. [Q55](./decision-register.md#manual-audit-export-authority) restricts manual export retry to a platform operator with a dedicated scoped permission, including isolated-installation operators; organization administration, repair or email-retry permission alone does not grant it. Retry transfers existing evidence without replaying the original domain action. [Q58/Q59](./decision-register.md#audit-export-first-stage-configuration) select waits of 1, 5, 15 and 60 minutes after successive temporary failures, then hourly, for at most seven days from the first failure. Success ends retries; restarts do not reset the window. A delivery delay of 15 minutes alerts the operator at export-destination/incident scope, without an alert per event. After automatic exhaustion, authorized manual export remains possible while the source audit record is retained; failure does not extend retention or create an indefinite payload copy. Persist retry identity/state and validate bounded dispatch, deduplication and source-retention races; email and recovery-command budgets remain separate. These records prove access operations, not that a person read or understood the page.

Raw answers, correct answers, capability values, and sensitive personal information should not appear in the general execution trace.

Detailed protected data remains in its owning aggregate and is retrieved only under explicit authorization.

---

## Security and Privacy Invariants

1. The server determines authorization, ownership, participant identity, navigation, timing, grading, result visibility, and disclosure.
2. Missing or ambiguous identity and capability context defaults to deny.
3. Assignment access does not automatically grant attempt review.
4. Participant, author, grader, reviewer, and operator representations remain separate.
5. Correct answers never enter participant responses before release policy allows them.
6. Client include flags cannot override release decisions.
7. Future questions and question-bank membership remain hidden where policy requires it.
8. Direct item endpoints cannot bypass form delivery restrictions.
9. Capability tokens never appear in logs, metrics, trace payloads, public errors, or cache keys.
10. Raw participant answers do not appear in metric labels or ordinary application logs.
11. Sensitive accommodation data is minimised and access-audited.
12. Caches vary by representation and authorization context.
13. Error responses do not permit assignment, user, capability, or attempt enumeration.
14. Every sensitive endpoint has negative authorization tests.
15. Client time, state, score, subject ID, and allowed-action claims are untrusted.

---

## Caching Rules

Authoring, participant, grader, and review representations require distinct cache handling.

A participant-safe form cache key may include stable internal references such as:

- attempt ID;
- form version;
- authorized representation type;
- review state version.

It must not include:

- raw capability token;
- participant email;
- answer text;
- correct answer;
- private accommodation details.

A cached review representation must not remain usable after review revocation without appropriate invalidation or short bounded expiry.

Public caches must never store capability-authorized private responses.

---

## Failure Semantics

Expected failures use stable RFC 7807 responses.

Important categories include:

- invalid configuration;
- unauthorized override;
- assignment unavailable;
- attempt limit reached;
- attempt expired;
- invalid state transition;
- stale attempt version;
- idempotency conflict;
- form integrity failure;
- unsupported policy version;
- unsupported algorithm version;
- insufficient eligible questions;
- review not released;
- grading pending;
- capability revoked.

[Q41](./decision-register.md#preparation-batch-approved) permits deterministic automatic recovery of derived views from committed facts, due attempt expiry via the existing authoritative command, and result release under proved saved conditions. Require version/identity/authority preconditions, receipts, transaction boundaries and required audit. Reconcile uncertain commits before retry; missing or conflicting source evidence stays quarantined. Manual review retains Q30's scoped operator authority. [Q60–Q62](./decision-register.md#recovery-first-stage-configuration) allow at most three retries after a confirmed safe recovery execution fails, waiting 1, 5 and 15 minutes, then operator handoff. Persist retry progress; these do not consume learner attempts. Show the checking state and last-update time without a fixed review/resolution promise or unverified saved-answer claim. After manual resolution, an authorized operator explicitly resumes the stopped processing; clocks, allowances and result-release gates retain their existing rules. Unknown outcomes still require reconciliation before replay. Concrete command/receipt mapping, detection thresholds, durable budgets and race evidence remain preparation.

Failures must preserve invariants:

- no partial attempt;
- no partial form;
- no partial state transition;
- no additional disclosure;
- no consumed retry on failed creation;
- no silent fallback;
- no hidden server exception details in public responses.

---

## Observability

Use low-cardinality metrics for:

- policy resolution;
- policy validation rejection;
- attempt starts;
- attempt-start conflicts;
- form creation failures;
- selection strategy outcomes;
- invalid transitions;
- command idempotency hits and conflicts;
- timing expiry;
- grading queue age;
- result-release state;
- legacy-adapter usage;
- unknown version failures;
- integrity mismatches.

Metrics may use bounded values such as:

- policy schema version;
- strategy type;
- lifecycle state;
- result code;
- legacy/new execution path.

Metrics must not use:

- user IDs;
- participant names;
- emails;
- capability tokens;
- quiz titles;
- question text;
- answer text;
- correct answers.

Structured operational logs and protected audit records serve different purposes and should not be conflated.

---

## Legacy Compatibility and Migration

Compatibility rules below protect existing clients, quizzes, retained history and behavior throughout migration. The [22 October owner decision](./decision-register.md#legacy-compatibility-and-retained-fallback) retains the required legacy execution path and limits any older preproduction cleanup language accordingly.

### Read-only compatibility inventory (#608)

`LegacyCompatibilityService` exposes an internal inventory and the existing #516 request mapping.
It has no HTTP endpoint, scheduler, migration writer or engine-routing hook. Existing successful
attempt, shared-attempt, result and repetition routes retain their current authority. Inventory
classification is diagnostic: it neither grants resource access nor disables a retained legacy
writer. An operator integration must supply an authorized database scope; this service is not a
tenant-facing arbitrary-database reader.

Two different questions must remain separate:

- **Can this new request be mapped?** `classifyLaunch` delegates to the actual versioned
  `AttemptPresetService`. An eligible authenticated `ALL_AT_ONCE`, or `TIMED` with disabled quiz
  timing and an unambiguous authorized untimed policy, has a supported **request adapter**.
  This is not evidence that #600's target start, #701's family adapters or #609 activation exists.
  `ONE_BY_ONE`, enabled timing and assignments retain their separately owned capability boundary;
  no-authority, unknown-version and choice-required outcomes retain their typed reasons in #516.
- **What can be known about existing rows?** Legacy `attempts`/`answers` have mutable quiz/question
  references, not immutable revision/policy/form provenance. All otherwise recognized rows remain
  `LEGACY_ONLY`. Today's quiz flags, question text, answer key or aggregate score never become
  fabricated historical target evidence. The existing completed-review reader still resolves
  current question context; the nine-family HTTP fixture explicitly demonstrates that limitation.

Inventory vocabulary v1 has four classifications. `SUPPORTED_ADAPTER` applies to a proven request
mapping only. `LEGACY_ONLY` means retain the existing representation and its established operations,
not impose a new read-only restriction on an active legacy attempt. `UNSUPPORTED` describes a
known unavailable mapping/selection/access outcome, without changing existing HTTP statuses.
`UNKNOWN` requires reconciliation before a proposed target transformation; it is a report bucket,
not a physical quarantine, deletion or regrading operation.

Each row contributes to exactly one fixed finding with an exact downstream issue owner. Overlapping
anomalies use explicit precedence: missing references, unknown vocabulary, ambiguous sentinel
identity, duplicate answer, inconsistent state, unexpected repetition, then the known legacy
family. The report therefore counts rows, not the sum of all anomalies on those rows. Absent a
higher-precedence discrepancy, every duplicate answer is reported under #848, including identical
and conflicting payloads; no response content is read to select a winner. Missing current
quiz/question membership is a discrepancy, not proof that the original historical answer was unauthorized.
This is a metadata inventory, not validation of stored question/response payloads. Recognizing a
family name does not prove that its historical payload is valid or gradeable by a target adapter.

| Source / current contract | Classification and boundary | Owner / executable evidence |
|---|---|---|
| `attempts`: `ALL_AT_ONCE`, `ONE_BY_ONE`, `TIMED`; `IN_PROGRESS`, `PAUSED`, `COMPLETED`, `ABANDONED` | Recognized history is legacy-only; malformed state or unknown vocabulary is explicit. No target policy/state is inferred. Legacy abandoned rows need not have the newer timeout timestamp. | #704 historical reads, #619 state mapping; `LegacyInventoryMySqlIntegrationTest`, `LegacyLifecycleIsolationIntegrationTest` |
| `POST /attempts/quizzes/{quizId}`, current-question, pause/resume | Optional start body defaults to `ALL_AT_ONCE`. Disabled timing does not become enabled because an inactive duration is stored. These existing contracts do not prove target launch authorization. | #600/#609 integration; `LegacyCompatibilityHttpIntegrationTest`, `LegacyPracticeMappingTest` |
| `answers`, single/batch answer, completion | All nine current family names remain known. Duplicate attempt/question rows are unknown, with no deletion or score rewrite. Accepted practice feedback remains immediate when requested; sequential duplicate remains the existing `422`. | #848 retained-writer integrity, #543/#529 target writers; HTTP nine-family fixtures, `QuestionContractGradingCompatibilityTest`, `LegacyAttemptTimeoutMySqlIntegrationTest` |
| Active detail, completed review, answer-key, lists/summary/stats and quiz result/leaderboard reads | Existing authorization/disclosure and response shapes remain. Completed review is legacy context, not proof of the original rendered question. Quiz analytics is a derived legacy aggregate, not an immutable grade fact. | #704/#606, #555/#556 disclosure; `AttemptControllerReviewTest`, `AttemptControllerSummaryTest`, `AttemptServiceImplSummaryTest`, `QuizAnalyticsSnapshotIntegrationTest` |
| `share_links`, `share_link_usage`, `share_link_analytics`; shared start/answer/current-question/complete | Known scopes/events, consumed one-time links and expired/revoked links remain legacy evidence. A sentinel without its link, a foreign-quiz link, or a non-sentinel linked attempt is not converted into a target subject. IP hashes, user-agent, referrer and token hashes are not selected. | #623/#624/#625/#626; `AnonymousAttemptIntegrationTest`, `AnonymousAttemptAnswerIntegrationTest`, `ShareLinkControllerIntegrationTest`, inventory fixtures |
| `quizzes` launch configuration, including archived/private/draft rows | Mutable configuration only. Enabled timing without a positive duration is inconsistent; a disabled timer's stored duration is inactive. A repetition-enabled quiz is reported as an unexpected enrollment signal. | #700/#608, #667 repetition discrepancy; inventory and HTTP fixtures |
| `spaced_repetition_entry`, `repetition_review_log`, current repetition routes | Any existing schedule/review row contradicts the clean-empty assumption in the inspected scope and is retained for classification. No SM-2 import, reset or new Learning adapter is implied. | #667, #577/#649 clean Learning; inventory fixtures, existing `RepetitionControllerQueryIntegrationTest` and `RepetitionControllerActionIntegrationTest` |
| Missing references, unknown future enum values, unavailable schema/query failure | Fixed non-sensitive findings or `LegacyInventoryException`; no default mapping and no successful partial report after query failure. | #608 reconciliation; inventory negative/fault fixtures |

`LegacyCompatibility.routingInput` distinguishes proven-absent, committed-target and
unknown/in-flight start evidence. Committed identity stays target-owned even when the requested
mapping is unsupported. Unknown never becomes absent. A compatible candidate still requires
#703's real operator authorization, synchronized no-target check, competing-engine/allowance race
and audit transaction; this pure classification supplies none of those effects.

The inventory uses one independent read-only `REPEATABLE_READ` transaction across all nine families
and all pages. Its bounded scalar SQL windows use an exclusive binary-UUID keyset, explicit column
lists and one look-ahead row. No answer JSON, question content, document bytes, names, email or
capability material enters the projections/report. Existing foreign-key indexes support relationship
lookups; answer-duplicate detection checks the same attempt/question using the retained answer index,
without assuming a uniqueness constraint exists. Work still depends on the inspected rows and
answer fan-out, so query count alone is not a latency guarantee.
The multi-parent fixture observes nine queries for both 3 attempts/24 answers and 67 attempts/536
answers when each family fits one page, and fourteen for the smaller fixture with four-row pages.
After refreshing statistics in the newly populated fixture, `EXPLAIN` verifies indexed relationship
lookups and primary-key range access for continuation pages. The inventory never changes table
statistics; actual plans depend on database statistics and MySQL's cost estimates.

The caller supplies `Limits(batchSize, maxRowsPerFamily, timeoutSeconds)` explicitly. Technical
input bounds are 1–1000 rows per batch, 1–1,000,000 examined rows per family, and 1–300 seconds per
transaction; there is no automatic production scan/default or new execution/retry/retention policy.
Reaching the row budget with a look-ahead row returns `complete=false` for that family and report.
Its reconciled counts are only the examined prefix, never advertised as whole-database totals.
Transaction/query failure returns no report. `emptyRepetitionConfirmed()` requires complete clean
quiz-configuration coverage plus both empty repetition tables in that same snapshot. A synthetic
empty fixture cannot establish that production is empty.

The only metric is `attempt.legacy.inventory`, with bounded `complete`, `truncated`, `unavailable`
outcomes. Reports contain per-family row counts, fixed findings and completeness, without row IDs.
Metric registration/update is best-effort: a telemetry failure neither discards a complete/truncated
report nor replaces the sanitized inventory exception with telemetry diagnostics.
Query failures are sanitized before transaction rollback logging. The MySQL fixture migrates a fresh
isolated catalog through V91 and tests counts, missing links, duplicate preservation, unknown values,
query plans, SQL counts, transaction read-only enforcement, cross-page concurrent commits and failure
redaction. JDBC scalar projections introduce no JPA relationship traversal. Current OpenAPI group
and discovery coverage is retained through `QuizOpenApiContractTest`; no new operation is published.

Known compatibility gates remain explicit: #690 requires a demonstrated saved-disclosure bypass
and must preserve immediate practice feedback; #849 already serializes answer/completion and
persists timeout. The new HTTP integration class also races identical and different same-question
answers in independent MySQL transactions for all three modes: one immutable answer and one score
contribution survive, and subsequent retries keep the existing rejection.
Inventory does not activate those routes or replace real writer evidence with a target fake.

#### Retained-answer uniqueness and rollout (#848)

V106 adds `uq_answers_attempt_question` on `answers(attempt_id, question_id)`; the `Answer` mapping
also declares it for Hibernate compatibility fixtures. The same question in different attempts
and different questions in one attempt remain valid. Existing success DTOs and `200`/`422`
answer/duplicate/terminal behavior, ownership, shared routes, timing and feedback remain unchanged.
There is no new client idempotency key, grading policy, target routing or automatic retry policy.

**10 November 2026 owner decision:** commands use a synchronous database-maintained projection of
minimal accepted-answer facts in the attempt row, without independently rereading physical
`answers` rows on each command. Exact correspondence is established at migration and maintained
transactionally on every supported source mutation. This is a deliberate replacement of the
previous per-command physical-row check, not an assertion that the two guarantees are identical.
The [decision register](decision-register.md#legacy-answer-projection) records the scope.

V107 introduces `attempts.accepted_answer_projection` as nullable JSON: NULL means uninitialized,
not an empty attempt. V108 installs the three answer triggers, rebuilds from stored values, runs a
separate exact source/projection comparison, then makes the column NOT NULL with a valid empty
default. Its version-1 format is `{"version":1,"answers":{answerUUID:fact,...}}`. Each fact contains
`questionId`, Boolean `isCorrect`, nullable saved `score` and numeric `answeredAtEpochSeconds`
(UTC epoch seconds at the source timestamp precision). Answer text, correct choices, explanations
and other large/sensitive content are absent. A null saved score retains the legacy zero-point
contribution; unavailable correctness is an error, never inferred or regraded.

Hibernate maps this field with both `insertable=false` and `updatable=false`. `AttemptCommandState`
selects current scalars and the raw projection under the attempt's row lock, independently of an
already cached entity. The guard flushes previous writes in the caller's existing transaction,
without committing, clearing or detaching its persistence context. It then copies the fresh values
into that managed attempt. Ownership/state/timing guards precede projection decoding. Current
immutable `AcceptedAnswerFact` values supply duplicate/ONE_BY_ONE checks, score and result DTOs.
Batch processing loads that working set once, adds each accepted answer to it, and reads requested
questions in groups of 256 while retaining input order, deadline checks and complete rollback.
Pause/resume retain the current scalar guard; attempt deletion acquires the parent lock before
a native parent DELETE delegates removal of its current children to the existing FK cascade.
It evicts only that parent and the child IDs from its current projection. DETACH does not cascade
through the cached inverse collection: an answer already rebound to another participant remains
stored and managed, and unrelated caller changes after deletion still flush normally.

`JsonCurrentAnswerRepository` decodes this already selected JSON and issues no SQL. Unknown
versions, missing/extra fields, invalid canonical UUIDs, duplicate question IDs, incorrect types or
unusable timestamps fail with bounded `AcceptedAnswerEvidenceException`. Parser diagnostics are
not retained as cause/suppressed exceptions or logged with the projection. Commands do not clear
or rebuild an orphan-removing association merely to read evidence. Their correctness does not
depend on a cached `Attempt.answers` or cached `Answer` scalar. No locking child SELECT is used.

The earlier range read, and the candidate UNION of primary-key point reads, are not the retained
strategy. Isolated JDBC reproductions on MySQL 8.4.11 and 8.4.13 showed independent inserts waiting
on PRIMARY/supremum even for an existing unique key with LIMIT 1. Binary binding, UUID_TO_BIN and
UNION branch limits did not satisfy the behavioral criterion. This evidence is specific to those
versions/fixtures; it does not prove every possible point-read implementation is impossible.
The replacement avoids acquiring child locks for reads instead of accepting a favorable EXPLAIN.
It does not promise the absence of every possible deadlock elsewhere in InnoDB.

V108's triggers maintain INSERT, DELETE and changes to any copied field, answer identity or parent
binding. They lock the parent and check the previous projected fact before replacing/removing it;
a missing, unknown or mismatched previous state aborts the source statement. Explicit rebinds
update both parents in stable order. Source and projection remain in one InnoDB transaction, so a
later service/caller failure rolls back both and the caller's other writes. Noncopied response
changes do not rewrite this projection. Parent/quiz FK cascades do not execute child triggers in
MySQL; the parent and its projection disappear together and require explicit cascade regressions.

This is not an independent runtime cross-check against `answers`. Structurally valid arbitrary
administrator tampering can evade command validation. Trigger bypass, constraint bypass or manual
schema/data manipulation requires a separately controlled exact reconciliation before reopening
writers. An adjacent counter/hash does not establish physical correspondence and is not added.

**Required rollout boundary:** pause and drain all paths capable of legacy writes on every
instance, including corresponding background tasks, service/maintenance writers and affected
cascade operations. Keep that external pause throughout installation, population and validation.
It is not automatically released by application startup, migration completion callbacks or failure
cleanup. The operator resumes writes only after all stages below succeed; the duration must be
measured on the actual data volume. This change defines the gate, not a new public maintenance API
or an automatic deployment action.

Use the existing #608 inventory first and require a complete `ANSWER / DUPLICATE_ANSWER` report.
Identical and conflicting historical duplicates both require separate classification. V106's
atomic unique-index DDL rejects them without choosing a winner, deleting answers, changing
published scores or rewriting analytics. A truncated/unavailable report does not prove readiness.

With writers paused, run V106 uniqueness, V107 uninitialized column and V108 triggers/rebuild/exact
verification/final default in order. Rebuild copies the saved score/correctness/time, never invokes
question handlers. Per-parent transactions on the migration connection use READ_COMMITTED and
parent-before-source ordering; application/caller isolation is unchanged. A separate second pass
compares each complete projection with all of that parent's original rows. Missing mandatory facts
or any discrepancy aborts rollout. The application's existing Flyway validation and Hibernate
`ddl-auto=validate` must remain enabled; Hibernate schema update cannot replace these migrations.

MySQL DDL is nontransactional. If V108 fails, keep every writer paused, inspect the failed history
and installed objects, resolve the cause, and explicitly review repair/retry before continuing.
The script's owned-object DROP IF EXISTS permits that controlled retry; the application never
calls Flyway repair automatically. A later startup must continue to fail on unresolved migration
history. Neither partial population nor an empty/default value is evidence that validation passed.
V106 remains unchanged; retain the successful constraint/projection/triggers on code rollback.
V107/V108 were unpublished working files; the checked local application of the earlier candidate
was confined to the disposable test-schema history.

The migration identity needs ALTER, TRIGGER, CREATE/ALTER ROUTINE and the required binary-log
routine privileges. Provision those before rollout; the migration does not change server-wide
trust settings. Runtime code needs no new dependency, public endpoint, permission model or
transaction/isolation boundary.

Storage grows with accepted-answer count. Every source insert updates a growing JSON object;
large batches may incur more than linear total JSON rewriting. There is no new answer-count limit.
Acceptance therefore measures real write time and JSON size, not just SELECT count. The full-Flyway
application fixture exercises 1/129/512/2048 answers, multiple independent attempts and adjacent UUID
inserts while a real command/caller transaction holds its own attempt lock. SQL capture includes
DTO construction/serialization where applicable and requires bounded reads with no answer-table
read dependency. Application SELECT counts exclude statements inside database triggers; elapsed
write times include their cost. Its figures are local regression evidence, not a production
capacity guarantee.

`LegacyAnswerUniquenessMySqlIntegrationTest` owns migration/preflight, stored-evidence preservation,
exact reconciliation, partial failure and explicit recovery/retry evidence in isolated catalogs.
`LegacyAnswerMembershipMySqlIntegrationTest` uses a complete Flyway schema with ORM schema creation
disabled for actual triggers, field ownership, all copied-field updates, rebind/delete/cascades,
projection errors, old snapshots, caller/batch rollback and independent-command cost/lock evidence.
Older Hibernate-created compatibility fixtures install the same production V108 through Flyway;
they are compatibility coverage rather than substitutes for the complete-schema fixture.

Local MySQL 8.4.11 evidence (10 November 2026): the focused command
`./mvnw -o -B clean test -Dtest=AttemptServiceDeleteTest,LegacyAnswerMembershipMySqlIntegrationTest,LegacyAttemptTimeoutMySqlIntegrationTest`
passed 92 tests. The 512-answer batch took approximately 0.68 seconds and the 2048-answer batch
8.10 seconds, including trigger maintenance; their projections occupied 93,237 and 372,789 bytes.
Batch SELECT counts for 1/129/512/2048 answers were 3/3/4/10; completion used 4 at every size,
without answer-table reads. Completion's own measured time, excluding the deliberately gated peer
write, was approximately 25/36/55/132 ms. Independent writes committed before the caller completed,
including the frozen 512-key boundary fixture. These costs expose substantial large-batch write
amplification; they do not establish production latency or remove the paused-rollout gate.

`LegacyCompatibilityHttpIntegrationTest` retains all nine families, immutable duplicate winners,
all three modes, feedback/history, wrong-owner and unauthenticated contracts. The timeout/current-state
fixture additionally forces both answer/completion orders, preloaded shared HTTP state under RC/RR,
old-snapshot scoring/navigation, cached pause/resume and real rollback checkpoints. One completion
event survives and no accepted answer appears after completion. Full verify remains a handoff gate.

### Compatible cutover with a retained legacy path

Retain the old public API and the legacy implementation needed by supported flows and fallback. Existing quizzes and retained user data must remain usable under their supported contracts; users need not recreate them for the new architecture. Backend readiness does not imply frontend readiness. Legacy v1 requests stay on the legacy engine. New target flows use v2 explicitly after their activation gates; frontend migration is required to adopt those capabilities. API version selection never grants permission or bypasses launch policy. There is no transparent target execution behind v1.

The owner refinement supersedes unconditional target-only routing and removal of required legacy writers after the conditional [#688 D10/D29](https://github.com/Gegcuk/QuizMaker/issues/688) reset. Any separate cleanup is limited to proven disposable development/test data with exact scope, backup evidence and owner confirmation. Account, billing, quiz authoring, retained attempts and other user data remain protected; current inventory cannot be inferred from the existence of a preproduction exception.

Fallback retention concerns eligible new starts, never conversion of an existing target attempt into legacy. Equivalent timing, disclosure, authorization and grading behavior must be supported, and authoritative operation/idempotency state must establish that the target start did not commit. A lost response or timeout is not sufficient proof; recover an already committed target attempt instead of creating a duplicate. Preserve established integrity/rate/burst stops and target-bound evidence.

The [22 October operator-control decision](./decision-register.md#operator-controlled-legacy-fallback) requires a scoped operator action to enable fallback for compatible new-start scenarios; an individual error, timeout or retry does not switch engines automatically. Compatibility and participant/resource authorization remain server-enforced for every start. Operator control cannot permit unsupported timing, disclosure, grading or navigation behavior, and normal integrity stops remain authoritative.

The no-target-attempt check must be coordinated with alternate creation and concurrent routing/target commits. An isolated read followed by an unguarded legacy write does not establish safety. Recover committed target outcomes, and do not start legacy while the target operation's outcome is unknown or it may still commit. Preserve one semantic operation, one attempt and one allowance effect across duplicates or operator switching. Persist the execution binding and durable operator/scope/routing audit evidence using existing operational authority and audit contracts. Exact coordination and control implementation remains [engineering preparation](./open-questions.md#oq-cutover).

Return to target already follows [#703 R09](https://github.com/Gegcuk/QuizMaker/issues/703): the owner or designated deployment/on-call operator authorizes resume after cause identification, deployed fix, focused/conformance testing, reconciliation and confirmation that no corruption remains, with a clean canary of at least thirty minutes and five hundred meaningful target requests; a repeat resets the window. Already created legacy attempts continue on their original engine. No automatic return or changed threshold is selected by the new fallback decision. Complete compatibility, concurrency, authority/audit and readiness evidence before #609/#703 activation.

Retirement of a retained legacy boundary continues to follow #688 D30: at least 14 days and two releases of observation, zero critical attributable integrity/security incidents, reconciliation and backup/restore evidence, no confirmed consumers, and owner go/no-go approval. Backend readiness or elapsed time alone cannot remove the compatibility path or fallback while it is still needed.

### Legacy attempts

Historical legacy attempts remain readable through their existing v1 services; existing target attempts remain on their v2 services. Neither version reconstructs or converts the other engine's evidence.

The retained writer's timeout guard (#849) preserves its existing timing rule: only `TIMED` with an enabled quiz timer expires, strictly after `startedAt + timerDuration`. Equality remains on time; a stored duration does not enable a disabled timer. Answer and completion commands serialize on the attempt row and commit `ABANDONED` plus its completion timestamp before returning the existing `422 ProblemDetail`. A later completion cannot create a score or completion event. Pause/resume use the same row guard so a concurrent command cannot overwrite terminal state. Other command failures still roll back, including failures after real answer inserts or completion writes. These are legacy compatibility repairs, not activation of target expiry/finalization policy.

`LegacyAttemptTimeoutMySqlIntegrationTest` covers fresh-transaction reads, reopened persistence/service components, before/equal/after boundaries, controlled command races, authenticated/shared routes, immediate practice feedback, unrelated-write rollback, and bounded relationship queries with multiple attempts and answers. OpenAPI contracts retain the existing DTOs and document the `422` response in the `attempts` and `quizzes` groups.

They are not retroactively assigned invented policy snapshots unless a separate migration can derive the information unambiguously and is explicitly approved.

Legacy attempts may therefore have lower reproducibility guarantees than new attempts. The API may identify their provenance internally or in authorized operator views.

### Legacy modes

Existing `AttemptMode` values map to explicit legacy-compatible presets.

The mapping is tested and versioned.

For [legacy `TIMED` with a disabled quiz timer](./decision-register.md#legacy-timed-with-disabled-timer), preserve untimed execution only when the launch resolves unambiguously to an authorized untimed policy. This compatibility case does not override assignment binding, enable a disabled timer using its stored duration, remove required timing, relax other legacy behavior or rewrite existing attempts. Ambiguous ordinary selection must be resolved before creation. The complete mode/error matrix remains a conformance prerequisite.

New code should not add more business behaviour directly to the legacy enum.

### Legacy routes

Existing routes initially call adapter services that translate legacy requests into:

- new commands where possible;
- legacy behaviour where translation is not yet safe.

Business logic must not be duplicated indefinitely between the adapter and new execution services.

### Historical data

No migration should reinterpret a mutable current question as definitive historical content when the original delivered content is unknown.

Unknown history remains explicitly legacy rather than fabricated.

---

## Extension Rules

Use the smallest suitable extension point.

| Product need | Preferred extension |
| --- | --- |
| New conventional practice or assessment mode | New preset |
| Additional configurable behaviour | Typed policy value |
| New selection algorithm | Versioned selection strategy |
| New ordering algorithm | Versioned ordering strategy |
| New grading algorithm | Versioned grading strategy |
| New release timing | Versioned release strategy |
| New authored immutable structure | Quiz revision extension |
| New participant execution command | Attempt application command |
| Spaced repetition | Learning bounded context |
| Live competition | Live bounded context |
| Cross-feature side effect | Domain/application event where direct coupling is harmful |

Do not create:

- a generic untyped workflow engine;
- a central `switch` covering every mode;
- one endpoint family per preset;
- a universal DTO containing all privileged fields;
- algorithms that rely on mutable current entities for historical attempts.

---

## Expected Preset Catalogue

The architecture should eventually be able to express the following without separate execution engines.

### Learning

- open practice;
- sequential practice;
- stable-order practice;
- immediate-feedback learning;
- work-on-mistakes practice;
- unseen-question practice;
- mastery learning;
- hint-assisted practice;
- flashcards;
- spaced repetition;
- confidence-based practice;
- explain-after-answer;
- self-assessment;
- ungraded reflection.

### Assessment

- untimed test;
- timed test;
- no-backtracking test;
- section-based examination;
- open-book assessment;
- certification attempt;
- diagnostic test;
- placement test;
- homework assignment;
- manually graded assignment;
- delayed-review assignment;
- mixed automatic/manual assessment.

### Shared and group delivery

- public practice link;
- authenticated assignment;
- invited-user assignment;
- anonymous capability assignment;
- one-time attempt link;
- same-form cohort;
- individualized randomized cohort;
- team assignment;
- classroom session;
- live competition.

### Non-linear execution

- branching scenario;
- adaptive difficulty;
- case study;
- decision simulator.

### Operational

- author preview;
- moderator validation;
- grader review;
- support dispute review.

---

## Delivery Roadmap

The programme is sequenced by architectural invariants rather than by visible modes.

### Foundation

The current foundation owner is [#591](https://github.com/Gegcuk/QuizMaker/issues/591), with implementation in its exact children. Historical #447/#461 are context, not executable prerequisites.

1. [#597](https://github.com/Gegcuk/QuizMaker/issues/597): immutable quiz/question/item/option revision identity.
2. [#700](https://github.com/Gegcuk/QuizMaker/issues/700): idempotent publication and the current pointer after #597.
3. [#598](https://github.com/Gegcuk/QuizMaker/issues/598): versioned response/scoring registry after #597; [#701](https://github.com/Gegcuk/QuizMaker/issues/701): approved family bindings after #598/#700.
4. [#474](https://github.com/Gegcuk/QuizMaker/issues/474), [#477](https://github.com/Gegcuk/QuizMaker/issues/477), [#475](https://github.com/Gegcuk/QuizMaker/issues/475), and [#480](https://github.com/Gegcuk/QuizMaker/issues/480): policy, form, command, and separate lifecycle tracks through their implementation children.
5. [#599](https://github.com/Gegcuk/QuizMaker/issues/599): canonical command receipts after form-item identity #519; downstream start, submission, results and release use these foundations.

[#612](https://github.com/Gegcuk/QuizMaker/issues/612) owns the core product decisions; [#609](https://github.com/Gegcuk/QuizMaker/issues/609) is the separate technical conformance/activation gate. The exact native dependency graph and delivery order remain in [#472](https://github.com/Gegcuk/QuizMaker/issues/472); a tracker or closed planning issue is not proof of implemented behaviour.

### Assignment, Identity, and Evidence

1. [#476](https://github.com/Gegcuk/QuizMaker/issues/476): immutable assignments while preserving legacy share links.
2. [#489](https://github.com/Gegcuk/QuizMaker/issues/489): participant subjects without weakening capability security.
3. [#478](https://github.com/Gegcuk/QuizMaker/issues/478): protected execution trace.
4. [#479](https://github.com/Gegcuk/QuizMaker/issues/479): drafts and safe final submission.
5. [#481](https://github.com/Gegcuk/QuizMaker/issues/481): validated question-bank selection.
6. [#482](https://github.com/Gegcuk/QuizMaker/issues/482): attempt limits, retakes, and selected results.

### Content and Assessment Extensions

1. [#473](https://github.com/Gegcuk/QuizMaker/issues/473): sections and atomic case blocks.
2. [#483](https://github.com/Gegcuk/QuizMaker/issues/483): result release and certificates.
3. [#485](https://github.com/Gegcuk/QuizMaker/issues/485): manual grading and rubrics.
4. [#492](https://github.com/Gegcuk/QuizMaker/issues/492): accessibility accommodations.
5. [#491](https://github.com/Gegcuk/QuizMaker/issues/491): ungraded and self-assessment activities.

### Progressive and Offline Extensions

1. [#484](https://github.com/Gegcuk/QuizMaker/issues/484): adaptive selection over the shared neutral progressive-form contract #661.
2. [#487](https://github.com/Gegcuk/QuizMaker/issues/487): immutable branching graph and decision append over the same #661 contract; it does not depend on Adaptive algorithms.
3. [#486](https://github.com/Gegcuk/QuizMaker/issues/486): eligible offline full-form packages and replay through ordinary server command handlers.

These extend shared content/form/command foundations rather than introducing competing attempt engines.

### Separate Runtime Bounded Contexts

1. [#488](https://github.com/Gegcuk/QuizMaker/issues/488): learning sessions, schedules and mastery.
2. [#490](https://github.com/Gegcuk/QuizMaker/issues/490): live session and team orchestration.

### Related Existing Work

- [#445](https://github.com/Gegcuk/QuizMaker/issues/445): feedback and answer privacy.
- [#446](https://github.com/Gegcuk/QuizMaker/issues/446): canonical idempotency.
- [#448](https://github.com/Gegcuk/QuizMaker/issues/448): analytics policy.
- [#450](https://github.com/Gegcuk/QuizMaker/issues/450): anonymous capability security.
- [#462](https://github.com/Gegcuk/QuizMaker/issues/462): related execution work.
- [#465](https://github.com/Gegcuk/QuizMaker/issues/465): operational design.
- [#494](https://github.com/Gegcuk/QuizMaker/issues/494): publication and maintenance of this reference.

---

## Approved Decisions and Their Authority

The [decision register](./decision-register.md) is the local index of approved A/D decisions and R follow-ups, with source issue, status and remaining qualifications. It distinguishes approved target behaviour from implementation and activation evidence. The owning issue remains authoritative when more specific; unresolved conflicts must be settled explicitly rather than interpreted by an implementation.

The foundations established by those decisions include:

- broad typed `ResolvedAttemptPolicy` v1, with unsupported capabilities explicitly disabled;
- a code-defined `FREE_PRACTICE/v1` initial preset and versioned legacy mappings;
- explicit publication, immutable quiz/question/item/option identity, and persisted exact form order;
- accepted answers locked at acceptance, identical retry success and conflicting retry rejection;
- immediate practice feedback only after immutable acceptance, with authored hints permitted earlier;
- binary v1 scoring, exact earned/possible values and normalized basis points;
- independently versioned execution, grading, result, release and review responsibilities;
- additive foundations and compatible activation while preserving existing clients/data and the required legacy execution path;
- explicit scoped authorization, retention ownership, and separate Learning/Live lifecycles.

These are not assertions that all corresponding services or policies exist in the current runtime. In particular, the #597 storage foundation does not by itself publish content, bind attempts, version grading contracts, retain external media, or activate a new engine.

## Remaining Decisions and Coverage Gaps

The [Q34–Q54 approvals](./decision-register.md#preparation-batch-approved) close the discussed audit/access, failed-export, review restriction, partial erasure, copy reuse, explicit linking, quiz-only scope, recovery, configurable certificate, reporting and first Learning settings. [Q55–Q57](./decision-register.md#operational-and-result-followup-approved) additionally choose scoped operator-only manual audit-export retry, restoration of the prior still-valid eligible selection after later technical risk-result invalidation, and scoped operator-only exceptional display restrictions with a recorded reason. [Q58–Q68](./decision-register.md#operational-values-and-lifecycle-approved) additionally select export/recovery retries, no fixed review SLA, explicit manual resumption, restriction grounds/lifting, one-operator erasure safeguards, at-most-30-day backup erasure, the 3650-day Learning maximum and 10,000-row/10-MiB CSV target. [Q69–Q71](./decision-register.md#historical-original-erasure-approved) additionally select exceptional historical-original erasure, explicit preservation-conflict resolution and the 24-hour working-storage erasure target. [Remaining gates](./open-questions.md#remaining-after-q71) separate factual case verification and implementation evidence from downstream prerequisites and [broader unselected product requirements](./open-questions.md#broader-product-questions); [the remaining-decision inventory](./open-questions.md#remaining-owner-decisions) does not reopen these approved first-stage settings. [Q39 code/test evidence](./decision-register.md#q39-current-linking-evidence) confirms that current automatic email-match linking differs from the approved explicit existing-account-proof flow; completed login transport does not prove safe linking.

[The later source-input and invitation refinements](./decision-register.md#source-input-and-invitation-refinements) defer initial personal-data/secret content filters for every source sent to external AI, including documents, recordings and transcripts. This does not remove ordinary access/technical validation, prompt trust or logging protections, and the product must not claim automatic anonymization. Q77/Q78 select audio-only extraction from MP3/M4A/WAV or MP4/WebM with audio, at most 120 minutes and 250,000,000 bytes per recording, with a clear pre-processing explanation that images/text on screen are not analyzed. Q79/Q80 select separately priced transcription/generation stages, a paid owner-accessible transcript retained after later generation failure/cancellation, and at most one additional automatic paid call per recording after confirmed temporary failure, at platform expense within ten minutes from first transcription execution including waits/retry. Reuse successful parts, reconcile unknown outcomes and persist budgets across restarts; existing text-generation policy remains separate. [Q92/Q93](./decision-register.md#fsrs-transcription-implementation-selection) select OpenAI `gpt-transcribe` behind a project-owned port and two explicit confirmations: exact transcription price before transcription, then exact generation price after the transcript exists. Before transcription, generation is a clearly labelled estimate/rule. A teacher may stop with the paid source. Split provider requests within their limits without lowering the recording limits or multiplying retry allowances. The [owner’s pricing refinement](./decision-register.md#transcription-tokens-per-minute) sets N internal billing tokens per minute of source duration, with numerical N configured later; pack prices or USD conversion are not architecture/issue-synchronization prerequisites. Specify partial-minute and token-rounding semantics before implementing charges, preserve the confirmed quote and keep retries platform-funded. Codec/quality/cost/capacity and transaction evidence remain preparation. [Q90](./decision-register.md#personal-source-retention) retains durable personal sources without age cleanup and selects authorized owner/account erasure, preserving protected dependencies.

[Q81/Q83](./decision-register.md#invitation-recipient-and-current-authority) require the intended verified-email account and the inviter's current authority to grant the specified organization role at acceptance. Losing inviter authority blocks that pending admission, not existing memberships. Q82's lifetime is configurable at creation. [Q86–Q89](./decision-register.md#invitation-and-personal-source-lifecycle) set an editable seven-day default, inclusive one-hour/30-day bounds, explicit reissue replacing prior pending links within the actor's authority and membership-only acceptance preserving existing roles. Delivery retries do not restart expiry; expiry/reissue/concurrent acceptance require atomic enforcement.

[Q84/Q85](./decision-register.md#multilingual-attempt-presentation) require complete requested-language translations for formal starts, with no attempt/allowance effects on failure. Practice may use author-permitted mixed-language content disclosed before start. Language switching is creator-configurable, on by default for practice and off for formal examinations; languages and exact translation versions are bound at start. Switching cannot reopen closed/prohibited content or expose translations added later, and existing equivalence/scoring/release rules remain.

[Q74](./decision-register.md#single-server-confirmed-data-durability) requires preservation of confirmed examination data and balance operations after loss of the working server and its disk. Prove the defined failure scenario with independently durable facts/material and a recovery exercise; this is not a claim that current deployment meets the target or survives every catastrophe. Q75 has no numerical service-restoration target and remains operational planning rather than a universal prerequisite for quiz-domain development.

[Deployment sequencing](./decision-register.md#deployment-separation-readiness) keeps the current small-VPS assumption. The accepted preparation uses location-independent backend/frontend/database/storage boundaries, streamed storage ports and stable version metadata, shared durable job/attempt/billing/idempotency state, short transactions and recovery safe across restart or later concurrent backends. When a balancer is added, correctness must not depend on sticky routing. No additional S3 daemon on the same VPS is selected. Restore preserves exact required originals and replays authorized deletions before reopening access. This is architectural readiness, not proof of Q74 or a selected operational recovery-time target. [Q91–Q93 and the recovery boundaries](./open-questions.md#concrete-transcription-fsrs-recovery-options) are approved; [the remaining inventory](./open-questions.md#remaining-owner-decisions) separates remaining billing precision, deferred operations and technical verification from issue synchronization; numerical N does not block it.

Remaining owner questions are maintained separately in [open questions](./open-questions.md), rather than mixing approved defaults with unresolved choices. That register identifies the source, owner and affected implementation/activation boundary for each item.

Partial decisions retain their approved clauses. Their undecided parts cannot be filled by a candidate strategy or an illustrative example in this document. The [Q01–Q26 batch](./decision-register.md#quiz-certificate-batch-approved) records approved media, administration, retention, accommodations, Live, reporting and repetition refinements. Q13 tag retirement preserves existing bindings while hiding new selection. Q18 approves incomplete drafts, full publication/start sufficiency and author-configured reserve levels/order; proof implementation and exceptional recovery remain. Q23 chooses best-result default and informed risk retakes with a preconfigured finite deadline initially. The [Q27–Q33 follow-up](./decision-register.md#quiz-certificate-followup-approved) resolves confirmed irrecoverable cancellation, no-result risk selection, operator repair authority, no initial rate exemptions, balanced random teams and separate report metrics. Q29 requires finality before certificate issuance. The [P1–P5 evidence](./open-questions.md#preparation-evidence) records actual code boundaries, calculations and remaining implementation/policy inputs; it does not select unapproved values. Initial practice retention, legacy-path retention, operator-controlled fallback and guarded resume remain decided; exceptional erasure, compatibility, coordination and operational readiness keep their gates. Implementation issues must reference the applicable approved decision and any remaining gate; they must not reopen already approved policy without a new owner decision.

---

## Deliberate Non-Goals

[Q40](./decision-register.md#preparation-batch-approved) explicitly limits the initial product to quizzes, with no standalone or in-quiz AI chat and no conversational clarification/follow-up flow. Non-conversational ingestion/generation serving quizzes remains in scope. Existing utility endpoint exposure and client compatibility need an implementation inventory; this scope decision is not runtime evidence that those routes are already disabled.

This architecture does not require:

- one release containing every listed capability;
- a universal workflow scripting language;
- AI-based adaptive selection;
- AI grading;
- proctoring or surveillance;
- plagiarism detection;
- video or chat;
- public matchmaking;
- distributed global live infrastructure;
- full offline execution for every mode;
- immediate migration of historical attempts;
- a current iOS implementation.

These may require separate product and architectural decisions.

---

## Testing Strategy

### Unit tests

Use for:

- pure policy validation;
- policy composition;
- selection;
- ordering;
- timing calculations;
- lifecycle transitions;
- grading algorithms;
- review-release decisions;
- stable hashing and canonicalization.

### MVC and OpenAPI tests

Use for:

- participant-safe representations;
- privileged representation isolation;
- authorization negatives;
- RFC 7807 contracts;
- idempotency headers and conflicts;
- schema compatibility;
- legacy-route responses.

### MySQL integration tests

Use for:

- transactional attempt creation;
- immutable form persistence;
- start-limit concurrency;
- answer-save/submit races;
- grading races;
- release races;
- uniqueness and foreign-key constraints;
- adapter compatibility with historical rows.

### Compatibility fixtures

Maintain fixtures for:

- every existing `AttemptMode`;
- existing practice flow;
- existing assessment flow;
- legacy share links;
- authenticated attempts;
- sentinel anonymous attempts;
- historical rows without policy/form data;
- old request fields and response shapes.

### External systems

Automated tests never call real:

- AI providers;
- payment systems;
- email providers;
- storage providers;
- identity providers;
- certificate providers.

---

## Quality Gates

Every implementation issue must:

- follow the repository issue-writing guide;
- have explicit readiness decisions;
- remain narrowly scoped;
- identify its domain owner;
- define API and data changes;
- define authorization and privacy behaviour;
- define failure and offline behaviour;
- define compatibility impact;
- include automated tests at the correct level;
- record essential manual/operator verification in the handoff when automated evidence is insufficient; create a manual-testing file only when the owner directly requests one;
- use feature-owned interfaces across package boundaries;
- use additive Flyway migrations;
- preserve logs and metrics free of secrets and sensitive content;
- follow the repository's local-only delivery rule: commits require an explicit request; AI agents do not push, create/merge pull requests, publish releases, or deploy.

Prefer about 1,000 or fewer added handwritten implementation lines per issue; tests and fixtures do not count toward that recommendation. This is a reviewability preference, not a hard gate. Split only into independently useful, backward-compatible, verifiable outcomes, and retain necessary test coverage. Use the current repository guidance when estimating a new implementation issue.

Documentation-only changes validate links, Markdown, and consistency with the tracker and child issues.

---

## Cross-Issue Invariants

Every issue in this programme must preserve these invariants.

1. The server, never the client, decides authorization, participant identity, navigation, timing, grading, and disclosure.
2. Every new policy-driven attempt references immutable content.
3. Every new policy-driven attempt executes a versioned resolved policy snapshot.
4. Every delivered fixed form is persisted.
5. Every progressive form extension is persisted before delivery.
6. Resume never changes previously delivered content or order.
7. Correct answers use stable domain IDs, not visual positions.
8. Question selection and question ordering remain separate.
9. Submission, grading, result finalization, release, and review remain separate.
10. Participant, authoring, grader, review, and reporting representations remain separate.
11. Quiz, assignment, execution, and review capabilities have explicit scopes.
12. Unknown persisted versions fail closed.
13. Replays, caches, batch requests, and offline queues cannot gain authority.
14. Historical attempts are not regenerated from mutable authoring data.
15. New conventional modes extend presets and typed policies.
16. New algorithms extend narrow versioned strategies.
17. Fundamentally different runtimes use separate bounded contexts.
18. Security-sensitive content does not appear in logs, traces, metrics, public errors, or public cache keys.
19. Full and grouped OpenAPI documents describe the same security and policy contract.
20. Legacy compatibility is explicit and tested rather than assumed.

---

## Architecture Completion Criterion

The architecture is sufficiently extensible when all of the following are true:

- a new conventional practice or assessment mode can be added through a preset and typed policy composition;
- a new question-selection algorithm can be added without rewriting attempt controllers;
- a new ordering algorithm can be added without changing grading semantics;
- a new grading or release strategy can be added behind a narrow versioned interface;
- a teacher can create an immutable timed assignment from a large bank and distribute it safely;
- participants can receive fixed, randomized, or stratified forms that remain reproducible;
- students can use learning-oriented feedback without weakening protected assessments;
- manual grading does not distort attempt completion or result release;
- participant-specific accommodations do not change other participants’ assignments;
- adaptive and branching execution can extend forms progressively;
- live sessions can coordinate ordinary immutable content from a separate bounded context;
- legacy attempts and routes remain readable during migration;
- historical attempts remain reviewable without reading mutable current questions;
- adding a feature does not require expanding one central mode switch across controllers and services.

The intended outcome is not the literal absence of future refactoring. No architecture can guarantee that.

The intended outcome is that new capabilities usually arrive as:

- a preset;
- a typed policy value;
- a versioned strategy;
- an immutable revision extension;
- an application command;
- or a separate bounded context;

rather than requiring a rewrite of the core attempt model.


## Transaction and lifecycle evidence clarified after audit

Document structure processing uses the target short authorize/claim → external AI → short reauthorize/version-check/publication boundary described in [normalized ownership](normalized-document-ownership.md). #850 owns the correction; current implementation still holds the transaction through AI. Public build requires `NORMALIZED`; repeated build of `STRUCTURED` is rejected before AI and is not an existing public replacement operation. First publication stays atomic. Its rollback test must reach real writes and read after rollback in a new transaction; a repeat-build status guard, self-invoked transaction annotations or mocked save calls cannot prove this. No public reset/rebuild or partial-success mode is added.

For transcription Q79, #846 owns cancellation/delivery serialization and #308 owns settlement/release against its committed stage outcome. Cancel-first means an unfinished stage cannot become delivered/charged through a late callback; future dispatch stops, though an in-flight provider call may complete. Durable-delivery-first remains payable once even if settlement is delayed. Unknown provider or commit outcomes are reconciled, not guessed. #306's frozen quote does not itself authorize settlement or quiz generation.

For erasure, #832/#844 capture scope before losing owner/dependency evidence. Restore of a backup predating a tombstone must use deletion records surviving independently of the failed server; no test may recreate them from lost process memory. #845 compares and prepares proof for concrete durability designs. Selected-installation evidence gates activation, not unrelated registry work.

### Runtime ownership and capability gates

The prerequisite graph must supply actual operations used by conformance tests. Section state/closure is #659, participant section delivery is #615, authoritative section clocks are #660 and their real composition is #711. Flat #524 does not implement section delivery. With Grammar closed at 12:00, prohibited breaks and ten-minute Vocabulary, a real first request at 12:10 handles expiry before content. A separate 12:03 positive-control fixture delivers content with seven minutes remaining.

The section foundation also needs its consumed contracts in the native graph: #552 requires #551; #553 requires #517 and #552; #546 requires #553 and #544; #554 requires #546 and #522 in addition to #696. These minimal edges supply the other structure, policy and canonical-form prerequisites transitively. A dependency recorded only in prose does not make materialization ready.

Formal D38 #668 unconditionally requires #614 persisted ONE_AT_A_TIME delivery/resume, #525 SEQUENTIAL navigation and #634 scheduled release with #697/#675 configuration/decisions. The real #543 answer command must enable a successful next-item transition after an unanswered request was denied; resume retains that position. After the assignment deadline the actual #634 worker releases only TOTAL_SCORE/PASS_FAIL, with #526 participant reads applying #555 disclosure to persisted release facts. Neither flat delivery nor immediate release proves D38. These requirements do not become prerequisites of first practice or FSRS.

### Flat navigation boundary (#525)

`POST /api/v2/attempts/{attemptId}/navigation` is additive and disabled unless
`quizmaker.attempts.navigation-commands-enabled=true`. It requires `ATTEMPT_READ`, the bound
participant and current quiz ownership/full-content-editor authority. It accepts an immutable
`formItemId`, `expectedStateVersion`, and a positive `expectedNavigationVersion` for sequential
policies; FREE requests omit the latter. The case-sensitive `Idempotency-Key` follows the existing
canonical receipt contract. Unknown outcomes are retried with the original input/key.

The internal start accepts an authorized `NavigationPolicy` with independent versioned skip/return
and accepted-revisit settings. Omitted settings preserve the historical FREE representation,
provenance, hashes and start fingerprints. Explicit configuration is author-owned, never a
participant-supplied grant. No active policy is rewritten. Sequential start stores the first form
item at position version 1. FREE never creates a cursor. V101 adds only target metadata and a
content-free receipt extension; it performs no legacy backfill. Disable the endpoint for rollback;
retain captured policies, positions and receipts for a forward fix rather than deleting evidence.

Default sequential navigation advances to the next available item after the current answer is
accepted. Explicit skip/return permits advancing unanswered and returning to an earlier unanswered item; accepted
revisit is a separate read-only choice. Forward navigation passes over accepted items hidden by
the no-revisit policy, but cannot jump over an available item. Returning to a skipped item and
answering it therefore cannot strand the participant before a hidden accepted neighbour.
A move updates only position metadata, trace and receipt,
not answers, lifecycle or grades. Commands for another item cannot bypass the saved position.
Active delivery and participant reads omit accepted items when revisit is prohibited; this does
not erase browser copies or change separately authorized post-completion review.

Identical replay returns the original position/version after current read authorization, even
after a later move or completion. Acknowledgements expose no content or feedback. Participant
views add an optional `navigation` position for sequential policies. Wrong/hidden targets return
non-enumerating 404; stale versions, answer-required and prohibited transitions use typed 409;
unsupported contracts use 422. Malformed requests use 400, authentication 401, corrupt evidence
500 and unavailable/retryable/unknown storage outcomes 503. Responses use `no-store`.
The endpoint belongs only to OpenAPI group `attempts` and is discoverable through API summary.
Metrics use bounded version/outcome labels without resource IDs, answers or keys.

The initial #525 boundary is online, flat and ALL_AT_ONCE. #614 extends that same position and
command boundary with the one-at-a-time contract below. Section locks, editable/proposal
finalization and public rollout retain their own gates; #834 owns its real composed
response/navigation scenarios and #616/#711/#668/#609 retain their activation responsibilities.

### Current-item delivery boundary (#614)

An explicitly author-authorized internal start may capture `ONE_AT_A_TIME` with `SEQUENTIAL`
navigation. Existing constructors, ordinary starts and `FREE_PRACTICE/v1` still default to
`ALL_AT_ONCE`; legacy mode mapping, historical canonical bytes/hashes and old start fingerprints
are unchanged. The new delivery choice has AUTHOR provenance and cannot be granted by a
participant override. No active snapshot is rewritten, and no additional table or migration is
needed: `attempt_navigation` remains the sole current-position authority.

Start atomically assigns the first current item with version 1 and a `QUESTION_DELIVERED`
event. A successful move atomically updates that same position and stores its content-free
result, receipt, navigation event and delivery event. Delivery evidence means the question was
made available at the committed current position, not that a person saw an HTTP response.
Reads/resume do not move the cursor or append another delivery event. An outer rollback or a
failure after a real insert leaves no partial assignment, receipt or evidence. Unknown commit
outcomes require the original request/key; there is no speculative legacy fallback.

`GET /api/v2/attempts/{attemptId}/form` returns only the current readable item and an optional
`navigation` position/version for this mode. Its existing ALL_AT_ONCE envelope is unchanged.
The active participant view applies the same current-item filter, including media reads; future
text, options and media URLs are absent. A current accepted item may remain unreadable under
captured no-revisit rules; its position is still available for a permitted next command.
Read/navigation actions do not imply permission to answer. Direct answer and draft commands
reject a non-current item with the same non-enumerating 404 as an unknown item. Post-completion
review remains governed by its separate persisted release and per-category disclosure checks.

`POST /api/v2/attempts/{attemptId}/navigation/next` accepts the named `NextAttemptItemRequest`:
`expectedStateVersion` and positive `expectedNavigationVersion`, plus `Idempotency-Key`.
It returns `NavigateAttemptAcknowledgement`. The server selects the next permitted stop, so the
client never needs a future item ID. Default answer-before-next, captured skip/return and
independent accepted-revisit settings use the real #525 rules. A guessed explicit forward target
through `/navigation` is unavailable; that endpoint can still revisit a permitted current/earlier
stop. Repeated reads and concurrent commands cannot select a different second next item for the
same expected version. Identical retries return original metadata even after later moves or
completion; clients read the current form to reconcile it. At the last stop, next is a typed 409;
submission remains a separate command.

Both navigation routes use existing `ATTEMPT_READ`, immutable self binding and current
ownership/full-content-editor checks, including on replay. Malformed input is 400,
unauthenticated access 401, unavailable attempts/items 404, stale/answer-required/last-item or
identity conflicts 409, unsupported contracts 422, inconsistent evidence 500 and transient or
unknown storage outcomes 503. Responses are `no-store`; errors contain no content or driver
causes. The endpoint belongs only to `attempts`, with named request/response schemas, examples
and `/api/v1/api-summary` discovery. It uses the existing disabled-by-default navigation gate;
no public start selection or rollout is activated by this increment.

Existing delivery-duration, navigation outcome and typed trace metrics remain bounded.
`quizmaker.attempt.delivery.active.age` samples seconds since the authorized IN_PROGRESS
attempt started, using an injected clock; it is not time spent on the current question, a
deadline or a learning allowance. Telemetry failures cannot change access or command outcomes.
No participant IDs, keys, answers or source text are metric labels.

`NavigationMySqlIntegrationTest` exercises real start/access/receipt/trace/answer/submission
boundaries, both transaction isolation levels, concurrent next and resume, reached-write
rollback, unknown commits, independent app-context recovery and multi-parent SQL counts.
`AttemptDeliveryMySqlIntegrationTest` and the MVC/OpenAPI contracts protect existing flat
behavior. These local checks do not prove timed D38, section delivery or public activation;
#668/#711/#609 retain their actual composition and rollout gates.

D38 selection is RANDOM_COUNT through actual #545/#547, with a fixed bank and server-controlled fixture seed yielding the approved five-family composition; it is not stratification by family/topic/difficulty quotas. #545 requires #544's strategy contract. #547 requires #600, which supplies #522/#544 transitively; a typed selector double can prove only this extension's real persistence transaction, while D38 uses the actual #545 algorithm. #604 separately requires #547 for real stratified start integration and retains its own structured/topic inputs. It is not a prerequisite of #668, #545 or the generic #547 extension. Readiness checks must inspect each producer's own ancestor closure, not rely on a common downstream conformance issue to provide implementation order.

Release configuration #697 owns immutable versions, validation and saved instants, not release execution or certificates. Its R05 choices are approved. #633/#634 prove their actual release transitions, authority, replay and restart; #633 consumes #698 mandatory audit. The dual manual/scheduled trigger mode requires both real commands as a conditional integration gate. #836 owns finality proof and #557 retains actual certificate issuance, including conditional approval/date modes. Assignment publication #621 likewise owns publication/supersession, while #835 owns transfer/access and #798 revocation/cancellation. Real start/submit races in #798 additionally require #534/#529 for the enabled integration; a prebuilt active-attempt fixture proves only the local cancellation writer.

Notification producer #837 has a mandatory first real writer: #556 immediate release. The release/evidence/receipt and bounded semantic event/outbox write commit together. Tests must reach a failure after actual release writes and before event completion/commit, observe neither side from a fresh transaction after rollback, and prove successful retry/restart with one source version and stable event identity. A best-effort after-commit callback or fake release cannot establish this. Consumer #800 is not a prerequisite of event production; it owns in-app state, preferences, daily retries, cancellation, unknown-outcome reconciliation and the single scoped manual send.

Before notifications from a source activate, #800 composes its actual consumer with #837 and that source writer, faking only external sending/status providers in the full path. #837's classifier/event-reader fixtures do not prove visible notices, and #800's synthetic persisted-event fixtures do not prove source atomicity. Optional source integrations use #633/#634 release modes, #550 selected-result/validity transitions, #557 issuance, #635 certificate lifecycle/reason clarification, or deferred #639 manual/mixed correction. These owners retain their state machines; #837 does not implement manual grading or reinterpret initial automatic grading as a correction. Each enabled producer needs its own atomic source/event proof, while first release remains independent of later producer types.

Certificate lifecycle #635 owns real status/history/causality and verified reason-clarification writes; #558 owns public status/privacy/cache reads. Before lifecycle verification activates, #558 composes real #557 issuance, #635 correction/revocation/supersession and its actual public endpoint. Warm the read, commit a real transition and verify its new minimal status. Separately reach a failure after actual lifecycle writes/flush before commit, verify the previous state in fresh transactions and HTTP reads, then retry successfully and restart. Prepared status fixtures remain useful for local projection tests but cannot replace this composition. Event atomicity remains #837 and delivery #800; #635/#558 need no mutual unconditional dependency.

Account deletion has a separate command owner #851: real account/profile/authentication writes and resumable orchestration using #844 personal-source cleanup and, transitively, #699 core-history cleanup. Neither a User deletion flag nor #699's core-family apply is full account deletion. Exact family scope and truthful partial/completed evidence must survive ownership removal; do not infer an unapproved post-deletion status-access policy. #558 owns conditional integration through the actual #851 command, real #699 and the public certificate endpoint, with #635 additionally for correction/deletion races. A holder can lose their account and personal history while the old usable verification link still reports the retained certificate's actual status, without contact/private result disclosure or reactivation after correction. The account task does not depend on certificate reads; these conditional gates do not block D38, first-release notifications or independent foundations.

Practice translation #95 uses real #600 start and #526 participant views over immutable #94/#700 versions. Formal translation additionally composes #534 assignment binding and #549 allowance in the #668 capability gate; section translation uses #615/#616 in #711. Both remain conditional extensions rather than blockers of authoring or first practice. An incomplete-language denial only establishes the intended invariant if the same formal path successfully starts a complete-language control and consumes exactly one allowance. Unsupported capability, exhausted allowance and legacy status-only resume are not substitutes for that proof.

Draft storage #541, commands #542 and immutable acceptance #543 prove their own real writes; #834 proves editable conversion and rollback, and #628 proves cleanup eligibility without destroying unresolved work. #702 owns real base expiry through the canonical #529 terminal persistence/receipt/trigger boundary. An expiry cause does not pretend to be voluntary submission or disable require-all in the saved policy. Scoped extension doubles cannot replace real base expiry, while #668/#711 provide full timed/section integration.

Navigation integration in #834 additionally requires real #525 and successful next/skip/answer/return controls. Base editable-finalization can complete independently. #614 one-at-a-time delivery and #616 section locks are needed only for variants that use them; a missing route or fake cursor does not establish either navigation denial or success.

Retention matrix #664 is a specification outcome. #699 owns deletion and actual DB restore/replay for its enumerated core aggregate family, including history with no uploaded files. Its test commits deletion, retains bounded deletion facts outside the database snapshot, restores old attempts/forms/answers in a fresh context and runs the real replay handler before serving history. A reached write/checkpoint failure, restart and fresh reads must prove that erased rows do not become visible; missing or incomplete independent evidence keeps the affected restored family unserved. #699 cannot claim object/backup erasure from a handoff event. #832/#844 inspect actual object generations and replay for their object/source families, while #845 proves independent evidence and durability on the selected installation. Local DB replay needs no backup-provider selection, and neither storage tests nor deployment design replace it. Personal practice retains details and results beyond the old 12/24-month thresholds; formal and technical baselines remain class-specific, and organizations cannot override periods initially.


### Implemented overall expiry foundation (#702)

The internal system-expiry command reuses #529 terminal persistence with an explicit EXPIRY
cause. It preserves immutable accepted answers, closes missing responses without disabling the
saved require-all rule, and records effective completion at the saved overall deadline separately
from processing time. A durable system receipt, SYSTEM trace event, submission and existing
finalization intent commit atomically. The real automatic-result reader recognizes this cause;
voluntary completion retains its original validation.

A derived deadline-indexed work queue and transaction-scoped root claims support bounded batches,
multiple workers and restart. There is no persistent lease to strand, participant impersonation,
legacy backfill, or new public API. The scheduler is disabled unless explicitly configured and
remains subject to #668/timed activation gates. See the [expiry runbook](../runbooks/attempt-expiry.md).
Editable conversion (#834), section events (#660/#711), pause (#602), full timed conformance
(#668), and public activation retain their independent owners and evidence requirements.
