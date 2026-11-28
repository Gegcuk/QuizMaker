# QuizMaker Testing Guide

This guide explains which test style to use, what behavior to prove, and how to keep tests trustworthy. It applies to human contributors and AI agents.

## Participant branch progress (#646)

With JDK 17, the offline test environment and disposable MySQL, start with:

```sh
./mvnw -o test -Dtest=BranchingAttemptMySqlIntegrationTest,ParticipantAttemptViewOpenApiContractTest,ParticipantAttemptViewActivationTest
```

The HTTP integration uses the internal trusted admission fixture with real immutable graph,
response, branch, submission, and release writers. It checks a lost-response replay, denied
hidden or foreign targets, completion over the delivered path only, terminal readiness before
explicit confirmation, corrupt persisted evidence, and committed-result disclosure.
Unsupported images in matching options or compliance statements return 422 on first request
and retry. Fresh reads verify the previously delivered path and accepted answer remain, with
no new append, decision, edge, or trace event. Text-only controls advance and resume. MVC
TRACE captures the acknowledgement on success and exact replay and verifies it hides the
delivered item identity while the HTTP body retains it.
The read records SQL counts for different committed prefix lengths. OpenAPI checks cover the
named progress and command schemas in the full and grouped specs and the authentication boundary.
Run the full four-worker verification on the final inputs before publishing the issue branch.

The local Postman stack cannot admit scenario attempts through its supported fixtures and HTTP
operations. This issue uses the HTTP/MySQL integration boundary. A repeatable local end-to-end
scenario becomes applicable when #670 provides supported scenario admission and conformance.

## Durable branching execution (#574)

Run the evaluator and append contract first, followed by the real MySQL suite.

```sh
./mvnw -o test -Dtest=ScenarioGraphValidatorTest,ProgressiveFormAppendTest,ProgressiveFormAppendPlannerTest,ProgressiveFormAppendBoundaryTest
./mvnw -o test -Dtest=BranchingAttemptMySqlIntegrationTest
```

The MySQL suite owns a disposable catalog on `TEST_MYSQL_PORT`. It publishes immutable graphs
and exercises subject/policy binding, canonical answer acceptance, append, submit, expiry and
grading. It checks both score outcomes, skip at a fork, exact retry and restart, a hidden item,
lost permission, terminal readiness, require-all and deadline contention. Faults injected after
decision, edge and cursor writes test rollback. Lost commit acknowledgements, overlapping
commands and two forms sharing one revision exercise independent transactions. Legacy opaque
rules and incomplete routing must fail before admission commits. Resume measures SQL for
multiple forms and checks that neither answer nor condition payloads are selected.
Regression cases cover unsupported attachments at entry and on a selected branch, unavailable
receipt reads after a lost commit acknowledgement, gaps or substitutions in recorded edges,
and a single path check during expiry. A selected MCQ with an image in an option returns
`UNSUPPORTED` on advance and retry. The same question with text-only options can advance and replay.
Grading read faults exercise transaction TRACE redaction
and distinguish lock timeouts, deadlocks and other database errors.

#574 has no HTTP operation. The two branch decisions and the terminal command are tested through
internal application interfaces. Participant progress and public conformance belong to #646 and #670.

## Executable scenario graph validation (#573)

Use JDK 17, the offline test environment and a disposable MySQL server.

```bash
./mvnw test -Dtest=ScenarioGraphValidatorTest,ScenarioGraphTest,ScenarioGraphMySqlIntegrationTest
```

- The deterministic validator tests exercise invalid references, strict condition versions and grammar,
  equivalent input permutations, explicit priorities, topology, and exact acceptance/rejection at
  each of the five bounds. The longest-path fixture includes a shared successor reached by a shortcut.
- Reachability cases distinguish losing edges and a default covered by both score outcomes from
  selectable routes. Cycles remain invalid in any declared component. Item cases include a repeat
  through one side of a merge, shared items in exclusive branches and distinct items for one question revision.
- The MySQL cases exercise the real first-publication path with a cycle or invalid grammar. A statement
  hook observes writing the publication key before rejection. Fresh reads prove the transaction leaves
  no key, revision, graph or question rows. Correcting the same candidate ID then publishes and replays.
  The same fresh-state and retry assertions cover a question reached only by losing priority and a
  repeated source item. A different route or two distinct item IDs, respectively, permits publication.
  Direct aggregate insertion uses the same validation. A retained V128 graph with an opaque condition
  still reads and replays, preserving its supplementary Unicode and exact condition text.
- Existing concurrent publication, lock scope, rollback, fixed-format compatibility and measured SQL
  count tests remain applicable. Validation runs on the in-memory graph and introduces neither queries
  nor JPA traversal. The internal boundary has no new HTTP scenario.

Run the full four-worker verification on the final implementation before publishing the branch.

## Immutable scenario graph revisions (#572)

With JDK 17, the offline test environment and a disposable MySQL server, start with:

```bash
./mvnw test -Dtest=ScenarioGraphTest,ScenarioGraphMySqlIntegrationTest,QuizRevisionCasesMySqlIntegrationTest,FixedFormMaterializerMySqlIntegrationTest
```

- Structural tests cover stable graph identity and authored order, exact condition retention,
  missing and duplicate identities, unknown format, and an item reference from another revision.
- The MySQL suite upgrades real format 1–3 rows from V127, verifies replay of a graph sealed
  in V128, and seals an open V132 graph after V133. The scenario writer and reads exercise exact
  replay, a conflicting replay, caller ownership, new revisions after edits and reading the original after rebuilding the
  service and repository. An older outer repeatable-read snapshot can replay the committed winner.
- Faults are injected after actual node, edge and graph header writes. Fresh transactions confirm
  rollback of the whole aggregate and then a successful retry. Two separate publication
  transactions meet at the real publication-key insert. Equal requests both succeed with one
  stored graph, and different requests have one complete winner. A separate revision commits
  while another caller holds an uncommitted sealed graph, then the first caller rolls it back.
  This includes neighboring revision UUIDs with a single terminal node and no questions.
  An open `REPEATABLE READ` caller also refuses to seal a graph when another transaction
  commits an extra node outside its snapshot.
  Two other authors publish neighboring revisions while a repeatable-read exact replay remains
  open. Distinct scenario revisions contend for the same question with equal or changed options.
  The former both commit and the latter leave one complete winner. Older repeatable-read
  snapshots can reuse a question already committed by either supported writer.
- A replay of a 512-question scenario stays open in repeatable read while other authors
  commit graphs with one new question apiece, using UUIDs inside and above the original key
  range. MySQL reports record locks only on the publication key. The first scenario use of
  one or 512 ordinary questions also allows another author to commit a neighboring question.
  MySQL reports shared locks on the question metadata, with record-only locks in the
  single-question fixture, and no question or option-index locks for these visible existing
  questions. Bulk foreign-key validation can also take shared metadata gap locks.
  Adoption compares current question and option fingerprints even when a reused question
  or a change to its content was committed after the caller's repeatable-read snapshot.
  The V134 upgrade backfills fingerprints for questions published while its tracking triggers
  are being installed. A same-ID, same-count option replacement holds the metadata lock before
  the backfill and commits while the migration waits. The original content then conflicts and
  the current content can publish, read back and replay. Equivalent legacy JSON fixtures verify
  whitespace and reordered object keys under a stale snapshot, plus bounded retries for
  one or 128 questions with legacy fingerprints. The question-creation regression opens a
  repeatable-read snapshot before another transaction commits 32 or 128 questions. The
  disposable MySQL server's general log records one additional call for either batch size.
  Direct changes to sealed questions, their options and recorded replay fingerprints fail.
  First publication of 128 new questions uses one question-creation procedure call.
  The upgrade exercises ordinary question publications on separate connections before and
  between V131 trigger installations. V132 reconciles a missing key and a zero count,
  and the scenario writer successfully adopts both questions.
  SQL constraints and guards cover cross-revision references, invalid entry, incomplete graphs,
  and late mutation.
- A multi-revision read measures six JDBC statements per batch with both two small graphs and
  six larger graphs, with indexed graph table lookups. A flat-only read uses three statements.
  Exact replay with a recorded fingerprint uses one select regardless of graph size.
  The real fixed materializer succeeds for a flat revision. Both direct fixed-form and option
  order services reject scenarios and continue to support formats 1–3. Actual JDBC TRACE events
  exclude distinct title, description, option key, question, answer, explanation and condition
  markers. A supplementary Unicode
  character is preserved through publication, reload and replay. Lone surrogates are rejected.
- The suite uses a generated catalog and belongs to the context manifest as a database companion.
  The full four-worker verify remains the final backend gate. Scenario graph storage adds no
  public HTTP behavior for Postman.

## Neutral progressive-form append contract (#661)

On JDK 17, start with the pure contract/planner/boundary suites and existing identity/fingerprint
compatibility tests, using the existing offline provider environment:

```bash
./mvnw test -Dtest=ProgressiveFormAppendTest,ProgressiveFormAppendPlannerTest,ProgressiveFormAppendBoundaryTest,FixedFormTest,QuizRevisionTest,FormFingerprintTest
```

- Both decision kinds use the same reference-only contract. First/later suffixes retain selected
  order, stable identities and the unchanged prefix. Complete immutable revision membership bounds
  selection; no new numerical quota, content loading, document hashing or strategy algorithm exists.
- Exact replay returns the original receipt before the stale-version check, including after later
  progress and after reconstructing all metadata values. Changed kind/evidence/expected version/items
  conflict; a receipt for another form/decision, future version or missing/changed prefix is refused.
- Fixed forms retain supported position gaps and explicitly reject append. Unknown append/revision
  versions, missing inputs, invalid state/order, duplicate identity/source and cross-revision inputs
  fail closed. Successful controls accompany denial cases. Defensive copies and redacted diagnostics
  preserve the metadata/privacy boundary.
- A controlled `CopyOnWriteArrayList` mutation between the first input observation and constructor
  completion exercises both state and request capture. Validation and retention must use the same
  owned snapshot; later planning sees distinct identities, not a newly substituted duplicate.
  Reached barriers and worker completion prove the mutation without sleep-based timing assumptions.
- A controlled `CopyOnWriteArraySet` null insertion before/after membership capture verifies the
  same rule for `RevisionScope`: a captured null gives `INVALID_INPUT`, while a later insertion
  cannot alter the retained non-null snapshot or cause `Set.copyOf` to throw `NullPointerException`.
- A real try-with-resources close failure during an `INVALID_INPUT` planner rejection cannot attach
  suppressed diagnostics. The close checkpoint is reached; the safe exception retains its reason
  and useful stack while its cause/suppressed collection and printed trace exclude the private marker.
- Seeded permutations with varied batch sizes validate contiguous suffixes and replay of every
  earlier request against the final metadata observation. The competing-plan fixture explicitly
  permits two valid proposals against the same observation, then rejects the stale request on
  reread. It does not claim a database race winner, durable restart, rollback or delivery integration.
- The compiled-boundary test excludes Spring, persistence, content, provider, clock, random and
  delivery dependencies from the pure model/planner/port. No context/database suite is added, so
  the context inventory remains unchanged; the new ordinary unit tests are discovered automatically.

After stable implementation inputs, run the existing full four-worker clean-verify launcher and
compare its source fingerprint with the candidate tree before commit:

```bash
python3 tools/testing/context_benchmark.py verify --workers 4 --output /private/tmp/quizmaker-661-verify-<unique-run>
```

`ProgressiveFormAppendPort` deliberately has no storage implementation. #571/#574 must use real
writers and committed fixtures to prove same-form CAS/receipt uniqueness, independent-form progress,
decision/evidence/form atomicity, reached failure rollback through fresh transactions, restart/lost
acknowledgement and commit-before-delivery. Current authorization must be checked even for replay.
Only later adapters can provide those proofs; a pure plan or caller-constructed receipt/state cannot.
Neither algorithm nor progressive HTTP behavior is activated here. No new Postman scenario is needed
because public operations remain unchanged. No JPA/persistence read is added; N+1 is not applicable.

## Read-only mistake and unanswered Learning selection (#648)

Start with `LearningHistorySelectionTest`, then run the `history*` methods in
`LearningConformanceMySqlIntegrationTest` using JDK 17 and the existing disposable MySQL/offline
helper. The complete four-worker `context_benchmark.py verify` remains the final backend gate.
The conformance class already belongs to the existing context inventory.

- The real publication binder, canonical assessment start/accept/submit, #603 result and #556
  release writers supply mistake history. A later correct answer removes a mistake; a later
  unanswered submission does not, including the interval before its result worker runs. Only a
  pending result for the latest accepted answer blocks selection; a superseded pending answer
  and later completion of its worker do not change the selected evidence.
- Result integrity is checked before incorrect-answer filtering and pagination: changed earned
  points, missing grades and noncontiguous positions produce `CORRUPT`, including an exhausted
  cursor. All grades of a referenced result contribute to validation, even when some answers
  have since been superseded. The real #603 reader independently rejects corrupted fixtures;
  restoring them restores selection. Reads preserve assessment and Learning table checksums
  and never repair source history.
- Result provenance follows #603's retained creation evidence: missing `attempt_result_created`,
  a mismatched creation time, or the wrong trace type, actor, reason or attempt/form scope yields
  `CORRUPT`. Grade response/scoring contract IDs and versions must match the saved question
  revision binding. Tests change each dimension independently, compare #603's rejection, check
  refusal before pagination and prove recovery after restoration. Contract validation includes
  superseded grades when their result still supplies another latest answer.
- #603 replay metadata checks also require each grade's position to match its captured form item,
  exact response identity for every item, matching accepted-response contract metadata, and
  complete form membership/counts. Swapping positions without changing their bounds is rejected.
  Contract IDs use byte equality: trailing spaces are not discarded by MySQL's PAD SPACE
  collation. The result's pass threshold must match the captured policy, and its header must
  retain the submission's form/policy/revision binding. Regressions independently corrupt these
  facts, including superseded responses, compare #603, preserve source checksums and restore
  successful controls. A padded unsupported root contract remains explicitly `UNSUPPORTED`.
  The extra corrupted form-count control currently maps to #603 `UNAVAILABLE`; selection
  still refuses that inconsistent metadata as `CORRUPT`.
- Completed-answer chronology is checked across history before consuming its latest-answer
  ordering. A damaged old timestamp cannot revive a mistake after a later correct response,
  or silently pass merely because it does not win the ordering. Independent submission
  accepted/unanswered counts, balanced count changes, terminal version and submission time
  are compared with real retained answers and lifecycle facts; restoration recovers selection.
  Voluntary acceptance is bounded inclusively by `submission.submitted_at`; expiry acceptance
  is bounded by `lifecycle.updated_at` and requires matching expiry receipt metadata. The real
  response writer waits on an observed MySQL lifecycle lock across the deadline, then the real
  expiry/result/release writers preserve that answer at the inclusive processing-time boundary.
  Later acceptance, a contradictory voluntary cause and a mismatched expiry receipt are refused.
- Both modes check accepted-response counts from every completed graded attempt root, before an answer
  join can drop a root with no surviving rows. Real pending submissions lose one or all response
  rows in separate regressions; #603 and both selectors refuse the incomplete history, including
  an exhausted cursor, without modifying storage. Restoring the rows restores pending/answered
  classifications; real grading then leaves only the remaining mistake. Genuine empty submissions
  (`0 == 0`) preserve both views before and after grading, and active attempts without a submission
  do not trigger the completed-attempt check.
- In mixed history for the same learner and revision, accepting and completing a real ungraded
  activity preserves both selections and their evidence. A corrupt accepted-response count on
  the graded submission still fails both modes with `CORRUPT`; restoring the count restores both results.
- Real Learning enrollment/delivery leaves questions unanswered. An actual HTTP response removes
  only that exact question revision; a later publication has separate state and old reads retain
  their captured identity. No assessment facts are repurposed as Learning grades.
- Unreleased correctness yields an explicit unavailable empty selection. A real legacy answer
  yields `LEGACY_UNKNOWN`, without manufacturing a revision mapping or falling back to another
  selection mode. Empty complete history remains a different result.
- Self/current-source authority, anonymous/foreign denial, RC participation and incompatible
  outer isolation are checked alongside successful controls. Unit tests cover configuration,
  unknown versions, bounded continuation, unavailable adapters, sanitized failures and diagnostics.
  Invalid input, denied source access, lock conflicts and reached storage faults retain separate
  bounded metric outcomes; invalid input executes no SQL and does not count as storage failure.
- The measured JDBC adapter reads one then four real assessment parents on the same publication.
  Its permission probe and history query return bounded candidate metadata, without per-question
  statements or normalized response/content reads. The same query batches grade count, point sum
  and exact form positions/membership, creation evidence, submission/policy and contract bindings
  for referenced result roots. Accepted responses contribute identity/contract metadata only.
  A real `EXPLAIN FORMAT=JSON` plan is retained
  in the test report. MySQL still examines/sorts source history;
  statement counts alone do not establish constant work. No JPA relationship read is added.

The implementation is internal only. It changes no public HTTP operation, so no new Postman
scenario is required. Existing session OpenAPI/HTTP conformance remains relevant; public
mistake/unseen session modes require their own integration before activation.

## Private nested Learning sets and selections (#841)

Start with `LearningSetTest`, `LearningSetOpenApiTest` and `LearningSetMySqlIntegrationTest`.
Then run the existing plan, due-selection and session-contract companions on JDK 17 with the
disposable MySQL/offline helper. The final backend gate remains the complete four-worker
`context_benchmark.py verify`; the two new context/database suites are in its existing inventory.

- NESTED_LEARNING-01/03: parent/child/diamond and quiz selection return one unchanged cycle
  identity. A real internal #649 review changes both views, and replay does not advance it twice.
  Repeated reads preserve schedule/event/receipt/credit rows. UPCOMING identifies the exact
  non-due item without granting or executing early review. This is reader/#649 composition,
  not the #650 nested-session activation proof owned by #667.
- NESTED_LEARNING-02: different publications with copied question text keep independent cycles.
  Real current author checks exclude lost ownership/permission, deny foreign sets and exact
  add replay after access loss, and permit removing an inaccessible member. Inactive/missing
  identity cannot read private set metadata. Organization membership grants no new authority.
- NESTED_LEARNING-04: equal due instants page without duplicates; scope/window/graph changes
  invalidate incompatible cursors. Real custom enrollment/pause/resume and a completed-state
  consumer fixture preserve the authoritative cycle while excluding inactive custom items.
  That fixture uses the real custom calculator/state-event writer; it does not claim a #650 session.
- NESTING-CYCLE: self-links fail; a reached real receipt write holds one edge transaction while
  the opposite edge is observed in MySQL's lock-wait table. One commits, the other is refused,
  and fresh reads contain only the permitted edge.
- Reached faults after member/receipt writes roll back membership, graph/set versions, debit
  and caught outer-caller writes. Lost commit acknowledgement survives service reconstruction
  with one set/receipt. Exhausted credit still permits exact replay and changed-input conflict.
  Supported outer RC rollback and incompatible-caller refusal retain their existing meanings.
- The MVC suite checks authentication, bounded strict body parsing, named schemas/examples,
  explicit nullable fields, expected ProblemDetail responses, no-store, unique OpenAPI grouping,
  API-summary discovery and both opt-in flags. Publication-source version enums must contain
  JSON numbers, not strings, so real selection responses validate against OpenAPI 3.1.
  Existing session contracts remain unchanged.

The local Postman child `24 Nested Learning / Private sets and shared due views` independently
checks structure, deduplication, shared identities, bounded/stale paging, replay, validation,
ownership, unchanged schedules and guarded cleanup. Its live-schema assertion exposed the
numeric-enum regression; it remains in the scenario. See `postman/README.md` for Runner setup.

The measured multi-parent fixture uses one then four distinct authorized publications, shared
descendants and overlapping whole-quiz/question paths. Each item read executes five statements:
identity, graph guard, set ownership, batched source grants and the recursive candidate query.
With four source parents and a one-item page it receives nine JDBC rows, including two candidate
rows for lookahead. It selects no question/document bodies. The graph/slot examination and sort
still grow with reachable data; this is a bounded fetch plan, not constant work per page size.
No new JPA relationship path is introduced.

The existing Q114 HTTP regression retains its November 23/24, 22:00-to-next-day-18:00 vector
in the next calendar year. JWT parsing checks wall time; the former fixed 2026 fixture began
rejecting expired credentials before the Learning assertion. Real authentication, intervals,
progression, replay and persisted-due assertions remain in the scenario.

Direct member paging locks only its own source grants, without traversing descendant sources.
The one-child fixture returns four JDBC rows (identity, graph, set, direct member), with five
statements including an empty direct-grant query. Schema checks additionally refuse incomplete
publication references and foreign-owner child edges; the service verifies exact question
membership independently of those foreign keys.

Review regressions additionally pause the real JDBC grant query before the candidate read and
use a second MySQL connection to commit a grant in that gap. Both direct-member and nested-item
reads exclude this unheld publication; revocation of the new grant completes while the outer
GET transaction remains open. A fresh read is the positive control. The complementary scenario
observes the revoker's specific connection in `performance_schema.data_lock_waits` both before
and after selection, proving returned publications retain their grants until the enclosing
read-only READ_COMMITTED transaction completes. Revocation then commits and a fresh read denies.

The DEBUG/TRACE MVC regression captures the real body processors, DispatcherServlet and handler
argument diagnostics. It follows `next` from actual JSON on all four paged routes, including
quiz-source query parameters, and verifies that the service receives each unchanged `after`.
Each level runs with both literal paths and equivalent percent-encoded segments on the second
page. The encoded case also requires four actual encoded request-path diagnostics, so a builder
that double-encodes `%` or silently uses the literal route cannot satisfy the regression.
It requires three request-read, twelve response-write and nine GET diagnostics; TRACE must also
record twelve resolved argument lists. All eight query-bearing GET diagnostics must retain a
redaction marker. Complete expected wire objects are compared on both pages. Command IDs,
question/source/cycle IDs, private names and cursors extracted from those actual responses must
be absent from the captured diagnostics (set IDs remain legitimate path arguments).
Spring's explicit request-detail logging remains disabled, as in the application defaults;
turning it on deliberately logs raw parameter maps and headers and is outside this guarantee.
The route-scoped filter changes only raw query-string presentation, preserving parsed parameter
values, repeated parameters and unrelated routes. Separate unit checks cover that boundary and
every set command, response, retained receipt and cursor's diagnostic representation.
Path matching uses Spring's parsed request path and `PathPattern`, including context and servlet
prefix handling. Tests preserve the previous MVC path cache, exclude encoded separators and
double-encoded letters from unrelated paths, and leave malformed-path rejection to the existing
chain while masking its query string.

Publication rows are committed immutable source fixtures, not proof of the #700 publication
command. Initial empty legacy repetition tables, ordinary training, assessment results and
allowances remain separate compatibility checks. #667's full nested review gate stays closed.

## Custom and mixed Learning reviews (#840)

Start with `CustomSchedulingStrategyTest`, then run `LearningCustomMySqlIntegrationTest`,
`LearningPlanMySqlIntegrationTest`, `LearningSessionOpenApiTest` and
`LearningConformanceMySqlIntegrationTest` on JDK 17 using the existing disposable MySQL/offline
environment. The full four-worker `context_benchmark.py verify` remains the final backend gate.

The calculation suite covers all ratings, finite completion, initial/overdue anchoring, two-stage
Again, explicit duplicate stages, recursive 14/28/56/112 and 7/9/11 generation, minute rounding,
the 3650-day ceiling, constant/capped plateaus, DST gap/overlap, and Q114's next-day 18:00 result.
Generated stages are retained only when reached. No review reads or rebuilds the whole lineage.

The HTTP suite runs the real application, JWT security, publication writer, plan/cycle commands,
mixed selector and response transaction on its own Flyway catalog. It verifies:

- One fixed session advances custom and FSRS independently; exact responses survive service
  reconstruction and a later strategy switch without advancing the replacement.
- Ordinary practice preserves active/completed schedules; finite success completes, and real
  restart, pause/resume and generated history retain their respective meanings.
- A barrier in MySQL's lock-wait table admits two competing requests. One scheduled response
  wins and supersedes its peer; identical concurrent retries return the same acknowledgement.
- A fault after real custom, progress and response writes rolls back schedule, event, receipt,
  progress and command credit; fresh reads and the original command prove successful recovery.
- A reached first custom-cycle fault rolls back the real whole-publication contract preparation.
  An identical enrollment retry prepares the entire publication and creates one cycle.
- A classified V125 cycle without preparation remains readable without repair. A new explicit
  enrollment prepares its source without replacing the cycle; the first actual response succeeds.
  Constant and 1.2 tails also prove durable repeated events, rounded predecessor dates, reconstruction
  and exact replay, with zero generated stages for the constant plateau.
- Timezone preview spends no credit or writes. A later review invalidates it; fresh confirmation
  preserves review count/stage, records one rescheduling change and replays exactly. Outer RC
  rollback restores due/history/credit; RR, RU and read-only composition are refused.
- Foreign ownership and current permission loss deny the new routes, including an old replay.
  Named OpenAPI requests, examples, errors, discovery and strict body parsing are checked.
- A measured due query returns one/four rows using one statement across four different private
  plan parents. It selects bounded schedule/configuration metadata, never source bodies. Sorting
  still examines this learner/publication's slots; LIMIT is not a constant-work claim. No JPA
  relationship read is introduced; existing content/publication conformance remains applicable.

Legacy repetition and assessment stores remain empty in the custom fixture. Existing FSRS-only
conformance separately proves unchanged ordinary training/results/allowances and the original
FSRS configuration. The local Docker profile explicitly enables synthetic mixed HTTP checks with the separate
`quizmaker.learning.custom-reviews-enabled=true` opt-in; existing plan-management settings alone
do not activate progression.
These settings do not enable a deployment or claim nested-review/production readiness.

The #840 Learning OpenAPI contract explicitly represents nullable acknowledgement,
cycle and cursor fields using OpenAPI 3.1 unions. Its ProblemDetail URI fields
accept URI references, including relative request paths. These contract checks
were added after the initial local HTTP scenario exposed the generated-schema gap.

The review regressions additionally exercise accepted nanosecond local times through one- and
two-question HTTP sessions, exact response replay, resume, fresh cycle reads and timezone
confirmation. All persisted due instants use microsecond precision. Q114 now reaches the
calendar stage through real commands before verifying the API's next-day 18:00 acknowledgement,
exact replay and persisted due.

Barrier-controlled MySQL tests cover GET versus the final response in both arrival orders and
an overlapping session's supersession. They wait for the real command-coordination row lock,
assert both HTTP outcomes and exactly-once events, and verify that inspection never spends or
refills command credit. A retained session also remains readable after idle admission metadata
has been recycled. Response and timezone UUID collisions are tested in both directions: repeated
409s preserve state/progress/credit while the original accepted command still replays.
The exhausted-quota regressions spend credit through real plan commands with a fixed admission
clock, then verify repeated cross-operation 409s, ordinary fresh-command 429/Retry-After,
unchanged state/history/credit and original replay. Both arrival orders also race a response and
a timezone confirmation on different cycles for the last credit using a common UUID. The loser
waits on the real admission lock and must observe the winner's key before any quota refusal.
A separate barrier pauses the first real receipt read, commits an identical peer command, and
then releases the retry. Both response and timezone retries must return the original 200 at zero
credit. The conflict probes exclude their own receipt within one statement snapshot, preventing
a false conflict between the initial receipt lookup and shared-key lookup. FSRS/practice keep
their existing namespaces and still debit a fresh response rather than bypassing throttling.

## Private Learning plans and cycles (#839)

Run `LearningPlanTest`, `LearningPlanOpenApiTest` and `LearningPlanMySqlIntegrationTest` with
JDK 17 and the existing isolated MySQL/offline launcher. The MVC suite verifies the named
schemas, schema-valid creation example, authentication, sanitized errors, strict whole-body
parsing, no-store responses, unique Learning group and API-summary discovery. Both capability
flags are required. The new suites retain the existing four-worker inventory and scheduling.

The MySQL suite uses real plan, binding, shared-cycle, FSRS receipt and command-budget writers.
Committed immutable/publication fixtures supply the source facts; the real #692 author checker
verifies current ownership and permission. Those fixtures do not prove #700 publication.

- CUSTOM_CONFIG-01/02: new default enrollment captures v2 while old cycles and pinned overrides
  keep v1/due; 1/100 interval bounds, equal stages, invalid/decreasing/fractional values and units.
- CUSTOM_CONFIG-03/CYCLE-CAS: a reached shared-slot/receipt transaction blocks a competing switch
  in MySQL; one commits and the stale request conflicts. Real form materialization and capture
  block replacement. The separately named completion-contract fixture permits canonical release;
  it does not replace #650's end-to-end completion proof.
  A plan edit between target choice and confirmation conflicts instead of silently capturing
  the newer version; a fresh explicit confirmation can then succeed.
- CUSTOM_CONFIG-04: pause for a month, stable due pages, paused enrollment and resume preserve
  one item per question and original stage/due without review writes.
- Reached custom-state, version and receipt failures roll back through fresh transactions, including
  command credit and a caught failure in outer RC. A lost commit acknowledgement survives service
  reconstruction and exact replay. Incompatible/read-only callers reject before domain SQL.
- Wrong plan owner is denied through an otherwise authorized source; foreign source, permission
  revocation, account deactivation and missing identity deny without forbidden writes. Exact
  replay rechecks source authority. Exhaustion spends no additional credit on replay.
- List tests compare real SELECT/row counts across multiple plan parents and custom question
  cycles: two SELECTs (including account lookup) for one/three plans return two/four total rows.
  Three question cycles bound to three different plans use one CTE with three SQL candidates
  for a two-item output page. No JPA relationship path is introduced. Reads select bounded configuration/metadata,
  not question or document bodies; a limited page does not claim constant source-scan cost.

A completed custom-state fixture tests only #839's restart consumer. Actual custom review,
calendar/DST transitions and durable completion remain #840 activation gates. The unchanged
FSRS conformance and publication suites must still pass before the full four-worker clean verify.
Do not enable a custom session or interpret this fixture as that integration evidence.

Review regressions cover both directions of strategy replacement while a real GET holds the
shared slot after reading its header. Observe the competing writer in MySQL's slot lock-wait
table; a second GET of the same slot and an unrelated question both complete before the held
reader is released. GET returns its captured strategy, the replacement then commits, and the next
GET returns the new strategy.
The inverse barrier holds the writer after real state/receipt writes: a GET waits on a shared slot
lock, then reads the newly committed header and state together in both replacement directions.
Missing-cycle GET creates no slot. A reached FSRS SQL fault returns 503 through MVC; the same GET then succeeds.
Unknown strategies return 422 for detail and all page windows, even behind a future cursor or
pause; missing state under a known strategy still returns 500. An accepted FSRS answer replays
its original receipt after switching to custom, retaining one review event and an untouched new
cycle. Metrics remain provisional inside outer RC: rollback emits no success; commit emits one
success and uncertain commit emits `unknown`. Mixed-strategy selection remains unavailable for
the learner's whole quiz revision until #840; a second healthy FSRS question stays individually
readable. These tests do not activate or implement mixed-strategy sessions.

## Author publication HTTP command (#935)

`QuizOpenApiContractTest` exercises the request example through MVC and checks the acknowledgement
shape, error schemas and discovery in the Quizzes group. Invalid numeric versions, including fractions
and out-of-range values, fail before dispatch and leave request data out of the error body.

`QuizPublicationHttpMySqlIntegrationTest` uses real JWTs, ordinary quiz and question creation, and
the #700 writer on a Flyway-built MySQL catalog. Fresh transactions compare the authored order with
the immutable question IDs, current pointer and persisted receipts. The same route proves
non-enumerating denials, invalid order, one committed winner for concurrent edits, an exact replay
after replacement, rollback after the receipt write and reconciliation after a committed response is
lost. It exercises legacy draft edit, moderation and training on the same quiz. The acknowledgement
projection is measured using actual statements, rows and bytes at two quiz sizes.
Trailing JSON is rejected without writing a revision, pointer or receipt. The same command with
trailing whitespace publishes successfully. MVC diagnostics at DEBUG and TRACE record redacted
request and acknowledgement representations and content-free validation errors. The log assertions
use the draft UUIDs and the distinct published question revision UUIDs returned in JSON.
`QuizPublicationMySqlIntegrationTest` continues to cover the #700 storage contract and retained images.

Run the scoped suites on JDK 17 with the disposable MySQL and offline provider setup described below,
then run the complete four-worker verification launcher.

## First Learning conformance (#667)

`LearningConformanceMySqlIntegrationTest` is the single-quiz FSRS phase gate. It starts the full
`@SpringBootTest` application with the Learning setting enabled, real JWT access tokens, the real
security filter chain and error handlers, and a class-owned catalog built by Flyway and checked by
Hibernate `validate`. Every Learning row comes from HTTP. The suite prepares its source revision
through the #700 writer. The #935 publication HTTP suite separately starts with an ordinary author
draft. Public activation requires the integrated publish-to-enrollment and first-review scenario.

Use JDK 17, the offline fake environment and a disposable MySQL server:

```bash
./mvnw test -Dtest=LearningConformanceMySqlIntegrationTest,ContextTestManifestTest,DatabaseTestExecutionTagTest
```

| Acceptance case | Evidence |
|---|---|
| LEARNING-EMPTY-START | An independent connection holds the learner's account row while two identical first enrollments wait in `performance_schema`. Both return the same body; one receipt and one cycle per item exist. Due start, saved order, lost-response replay, completion, `EMPTY_COMPLETE` at the same instant and the AGAIN item 60 seconds later follow. Credit equals accepted commands. |
| FSRS-03 | Two ratings racing for one current state stay in `LearningScheduleMySqlIntegrationTest#realLockRacesHaveOneWinnerAndReplayOrExplicitConflict` and, through the response command, `QuizPublicationMySqlIntegrationTest#concurrentLearningResponsesGiveOneReviewAndKeepTheExactReplay`. The conformance suite adds the exact HTTP replay of an accepted response. |
| Multiple entry points | A second enrollment command reuses the active cycles. EARLY advances its selected cycle once. PRACTICE leaves the FSRS state text and event count unchanged. |
| FSRS-01 | First ratings return the pinned +600 s, +60 s and +16 days. Golden traces and serialization stay in `FsrsSchedulingStrategyTest` and `FsrsStateFormatTest`. |
| FSRS-02 | Explicit instants, DST and full-day arithmetic stay in `FsrsSchedulingStrategyTest`. HTTP adds no profile-time input. |
| FSRS-04 | An unknown retained strategy and a changed saved FSRS weight each make both GET of the active session and the response return 422, so RESPOND is never offered for an item that cannot be reviewed. Learning checksums are compared before the fixture is restored: no event, progress, credit or self-repair. An exact start retry keeps its original acknowledgement, and a completed session stays readable. The restored state accepts GOOD with the approved +600 s. |
| Final answer readiness | After Anna answers the first of three questions, the schedule of that answered question is damaged in each of the two ways. Resume still offers the middle answer, which reads only its own schedule and is accepted. Before the final answer, which releases every capture, GET and the response both return 422 with unchanged Learning checksums. After restoration the final answer completes and the event count matches. `QuizPublicationMySqlIntegrationTest#finalItemResumeChecksEachCapturedScheduleOnceLikeTheFinalRelease` measured 24, 28 and 32 statements for one, two and three captures: one bounded schedule read per capture, as the final release performs. |
| Resume during completion | `QuizPublicationMySqlIntegrationTest#resumeOverlappingTheCompletingResponseShowsTheCommittedTerminalStateInsteadOfAnError` holds two GETs after their progress read while the final response commits, completing one session and superseding the other. Both return 200 with `COMPLETED` and `SUPERSEDED`, not 500, and one review event exists. |
| LEARNING_BOUNDARY-01 | `CHECKSUM TABLE` over every non-Learning table changes only `question_revision_contract_bindings`. Ordinary v1 start, answer and completion on the same quiz keep their envelopes and change no Learning row. A scored v1 result with its refreshed analytics, and a submitted v2 result with a three-attempt allowance from the real start, response and submission writers, then exist. The v1 review, quiz results and attempt statistics, the v2 earned points, the allowance and every non-Learning checksum stay identical after the final Learning answer and its exact replay. Legacy repetition remains empty; no certificate store exists yet. |
| LEARNING_BOUNDARY-02 | After an author edit and publication B, the open session still shows A and accepts its answer. B enrollment creates new cycles at sequence 0, including for unchanged content. A remains at sequence 1. |
| LEARNING_BOUNDARY-03 | Missing and forged bearer tokens return 401. Another user, a known foreign session ID, a public foreign quiz and a missing session return the same 404. No foreign credit row is created. |
| LEARNING_BOUNDARY-04, D33 | `custom/v1`, a future FSRS version, `NESTED_SET` and an unknown mode fail before writes. Twenty accepted starts exhaust the burst; the next start or enrollment returns 429 with `Retry-After: 1`. An exact replay still succeeds, and one second refills one command. |
| Invalid item, failure | A foreign or stale item returns 409 and a response of another family returns 400; Learning checksums and credit stay unchanged. Rollback after reached form, schedule, receipt and budget writes stays in the #578/#650 trigger fixtures of `QuizPublicationMySqlIntegrationTest`. |

`quizmaker.learning.study` must keep only its four bounded tags, and the HTTP metric must record route
templates. Service reconstruction after restart remains covered by the #578/#650 publication suite.
NESTED-REVIEW-INTEGRATION and mistake/unseen selection have no implementation yet. Their
scenarios must be added here before those capabilities activate.

Three harness rules matter. MockMvc's security support keeps one context object per test thread, and the
JWT filter writes into it, so the suite clears it before each simulated request. Issue tokens from the
stored user row: the Flyway column `users.password_changed_at` has whole-second precision. Failure reports
name the operation, status, problem type or expectation only; body, JSON and ID comparisons never put
either value into the assertion message or its expected/actual payload.

## Learning responses and completion (#650)

The publication MySQL fixture starts from a real owned revision and uses the actual Learning
enrollment, start, response and resume boundaries. Verify the saved presentation order,
original acknowledgement after service reconstruction, rejection after loss of access, due/early
advancement, and a practice response that preserves the full schedule. A blocked peer races a
committed winner on the same captured step; GET shows `SUPERSEDED`, all affected cycle restarts
succeed once valid captures have completed, and exact replays retain their historical progress.
V124 runs against a reconstructed V123 overlap with a committed event and public acknowledgement.
A trigger after the real response insert checks that the schedule event, cursor, supersession
and capture release were reached before failing. Fresh reads verify rollback of all four, restored
command credit and a successful exact retry.
The nonterminal response SQL comparison across two- and five-item forms measures one immutable
question row per command and equal statement counts. Form metadata still scales with the form.

`LearningSessionOpenApiTest` checks typed requests and acknowledgements, exact error contracts,
strict enum parsing, the unique group and API discovery. The MySQL suite compares GET and POST
against the same corrupt retained revision, then restores it and retries the response successfully.
Use the existing offline starter and the four-worker verification gate after the focused MVC
and MySQL suites.

## Single-quiz Learning sessions (#578)

Use `LearningFormTest` for the optional question permutation, saved option order and empty
form. `QuizMapperTest` and `QuizOpenApiContractTest` cover the additive quiz preference.
`LearningSessionOpenApiTest` checks the security filter, request validation, errors, named schemas,
the three paths and their unique discovery group.

`QuizPublicationMySqlIntegrationTest` starts with real published questions and empty Learning
and legacy repetition storage. Its HTTP fixture enrolls one of two questions, checks both
publication bindings but only one schedule, then starts and resumes the form. Verify command
credit after the start, read and exact replay using frozen admission time. A different fixture
holds the winning enrollment open and observes the competitor in MySQL's lock-wait table.

The same suite changes the quiz setting, publishes revision B, and reads the original form
through a new application-service instance. It compares resume statement counts and counts
actual immutable question rows for one and three selected items. First preparation intentionally
reads the whole revision. Enrollment and capture each invoke the existing transactional schedule
writer per selected item. Exercise rollback after reached form/capture writes, corruption on an
unselected first-enrollment binding, permission loss and the absence of assessment/review writes.
A MySQL trigger can fail the start receipt after the form and cycle captures exist. Check that
transaction TRACE redacts its diagnostic, SQL and keys, then replay the rolled-back command.
Exercise both GET and start replay with a damaged binding or content row, an unavailable database
read and revoked access. Change the saved response contract version and remove its binding in
separate cases. After restoring the binding both routes must return the original form. An invalid
candidate still fails before the first form write. Hold a second author permission on a separate
MySQL connection during first enrollment so the nested publication read times out. Check HTTP
409 `data-conflict`, rollback of the credit and preparation, and one accepted enrollment on retry.
Repeated occurrences must produce a single enrollment and due item;
a saved form referencing the second occurrence must still resolve that exact occurrence.
Use the normal four-worker `context_benchmark.py verify` gate after these scoped suites.

## Learning command accounting (#692)

Use JDK 17, the offline provider environment and disposable MySQL. Run
`LearningSessionSpringTest` for configuration bounds and
`LearningSessionSpringMySqlIntegrationTest` for the real session, receipt and budget writes.
Freeze server time, accept 20 distinct commands, replay the first at exhaustion, reject a conflicting
command, and reject the next new mutation with an RFC 7807 response and a one-second
`Retry-After`. Restore exactly one command at the next second. A lower fractional refill rate
must round the HTTP wait up without granting an early token. Reads spend no credit.

Race two distinct session creations for the final credit and assert one committed session and one
rate denial. Inject failure after the receipt and balance are visible inside the transaction,
then inspect all three tables after rollback. Also exercise a failed budget write, caller rollback,
and a successful retry. The MVC transport fixture calls the real creation service and the central
error handler. Public Learning routes and their OpenAPI discovery belong to #578 and #650.

Run `QuizPublicationMySqlIntegrationTest` for the real #700 writer and the #692
source verifier. Check capture of revision A, an author edit and publication of B, continued
read/replay on A, and denial after permission revocation, quiz deletion or ownership transfer.
Cover an inactive account and a foreign moderator with a visible quiz. Compare scoped SQL counts
across two authors with different role counts and published revisions with 1 and 12 questions.
Question text, answers and option lists are outside the authority query. Missing and foreign
session IDs share the same HTTP 404. A guest has no supported Learning identity. During an open
author transaction, observe the competing revocation waiting on the actual membership row while a
second author commits against their own revision. After revocation, deny the first author's replay.

After the scoped tests, run the complete four-worker clean verify on the reviewed inputs:

```bash
python3 tools/testing/context_benchmark.py verify --workers 4 --output "/private/tmp/quizmaker-692-verify-$(date -u +%Y%m%dT%H%M%SZ)"
```

## Read-only due selection (#576)

Use JDK 17, the existing fake/offline environment and disposable MySQL:

```bash
./mvnw test -Dtest=LearningDueSelectionSpringTest,LearningScheduleMySqlIntegrationTest,LearningScheduleSpringTest,LearningSessionSpringMySqlIntegrationTest
```

The internal selector requires an explicit positive server setting,
`quizmaker.learning.due.max-batch-size`. There is no production default or daily study quota.
The configured value 20 in tests is a fixture. Missing/invalid configuration denies selection;
the request cannot raise the bound or supply a learner or clock. The bound must leave room for
one lookahead row. Results distinguish a full batch, a partial batch and no currently due items.

Use real #649 enrollment/review writers over #597 immutable sources. Verify earliest due first,
inclusive server-time boundary, unsigned UUID ties, stable repeat, keyset pages and reconstruction.
An unchanged 100-item fixture pages without writes; five real 20-item review batches remain
available on the same date. Compare all authoritative Learning rows before/after reads and retain
the existing legacy fixtures. Selection itself creates no session, cycle, review or assessment.

Current source access is a named contract fixture, as in #649. Fail closed for absent/ambiguous
authority, foreign sources, inactive users and unknown strategy/state/manifest versions. An
unsupported future-due state must not be disguised as an empty result. Corrupt selected evidence
fails without repair. Custom pause/finite-completion and nested-source eligibility require their
real #839/#840/#841 integrations; this FSRS-only boundary does not manufacture those states.

The MySQL companion measures three SELECTs with the scalar identity/access fixture: identity,
source identity and one batch query. Returned rows are bounded by the requested size plus three
(two metadata rows and one lookahead). Compare one versus twenty items across learners/sources
and a 501-question source with large content; inspect the actual bound EXPLAIN plans. No JPA
relationships, question/answer content, schedule history scan or per-item round trip is used.
SQL may inspect/sort all active slot metadata within the authorized source: bounded returned
rows and query count do not establish constant CPU cost or production capacity.

The Spring/MySQL companion uses the real method-security, user lookup, repository and
SafeJpaTransactionManager proxies. Prove actual RC on the RR-default pool, read-only standalone
ownership, compatible read-only/writable outer RC and caller rollback. Unsupported/default
caller metadata rejects before selection work. Reach real source/batch queries, inject ordinary,
1205 and 1213 driver failures, inspect nested transaction TRACE messages/throwable graphs and
verify caller writes before and after a caught read failure roll back. Retry the successful path.

After stable implementation inputs, use the complete existing gate and match its source/bytecode
fingerprints to the reviewed candidate tree; inspect Markdown separately:

```bash
python3 tools/testing/context_benchmark.py verify --workers 4 \
  --output /private/tmp/quizmaker-576-verify-<unique-run>
```

No public Learning endpoint, form materialization, random presentation or arbitrary response
order is activated. #578/#650/#692/#667 retain their real integration and activation evidence.

## Durable Learning schedules and common cycles (#649)

Use JDK 17, the existing offline/fake environment and a disposable MySQL server:

```bash
./mvnw test -Dtest=LearningScheduleSpringTest,LearningScheduleMySqlIntegrationTest,LearningSessionSpringMySqlIntegrationTest,LearningFormMySqlIntegrationTest,LearningSessionMySqlIntegrationTest,FsrsManifestTest,FsrsSchedulingStrategyTest,FsrsStateFormatTest,FsrsBoundaryTest,ContextTestManifestTest
```

The schedule companion migrates real V119 data to additive V120, retaining actual legacy
quiz/attempt/answer rows. Enrollment and reviews use the real #597 revision writer, pinned
#577 engine, #649 application service and JDBC repositories. Capture tests create actual
#575 sessions and #647 forms. Current content access is an explicit contract fixture; the
completion verifier is a named future #650 contract fixture. Neither proves public Learning
access, session completion, custom scheduling or feature activation.

Persist and reconstruct all 28 pinned golden traces, including learning/relearning states,
four ratings and the 23h59m/24h boundary. Normalize server time to microseconds before calling
the engine, never afterward. Same-command replay must retain the original state/time even
after further reviews or cycle replacement; conflicting input must not overwrite the receipt.
New quiz/question revisions retain separate state. Reads never implicitly enroll.
For stability/difficulty against the external frozen oracle, reuse #577's shared `1e-12`
comparison. Java [Math](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/Math.html)
does not promise bit-identical pow/exp results across JVM platforms; Linux/amd64 CI exposed
1–2 ULP differences from the macOS/arm64 fixture. Phases, steps and due instants remain exact.
Keep strict equality between the actual calculated state and its serialized/persisted/replayed
state. State/events/receipt results retain exact JSON text with database validity/shape/size
checks, avoiding native binary-JSON rendering changes. Never apply oracle tolerance to these
round trips, round scheduler parameters/state, or regenerate the oracle from the adapter.

Reach faults after actual cycle, state, active-pointer, event and receipt writes, plus a
before-commit synchronization. Inspect state through fresh transactions and retry the same
command. A separate lost acknowledgement occurs after an actual commit and is reconciled
from the durable receipt after component reconstruction. Preserve outer RC rollback and
rollback-only after a caught participating failure; incompatible/default/read-only callers
must reject before identity/domain SQL.

Concurrency tests use reached barriers and Performance Schema receipt/slot waits. Identical
commands replay; conflicting identities and stale sequences have explicit losers, with one
successful committed winner. Capture/restart races prove both permitted serial orders. An
independent item, another learner on the same revision and an unrelated-quiz control must
commit before releasing a held review. Watchdogs bound hangs, not promised throughput. The strategy-neutral repository
CAS fixture proves shared slot ownership only, not an implemented custom strategy/switch API.
After releasing a session's question binding and replacing its cycle, a new capture command
using that old session must fail without a new binding or receipt. Exact original capture/release
replays retain their receipts; a fresh session may capture the replacement. A multi-question
session keeps independent bindings for its other questions. The guard reads historical metadata
under the already-held slot lock, not only the current cycle/session pair.

The existing Spring/MySQL companion additionally uses real method-security, JDBC MANDATORY
proxies, UserRepository and SafeJpaTransactionManager. Prove actual RC/pool restoration,
caller rollback, reached ordinary/1205/1213 failures and clean transaction TRACE messages and
throwable graphs. A real AFTER INSERT MySQL SIGNAL separately exercises the driver boundary.

Read-volume tests execute against multiple schedules/parents and a 501-question source with
large raw payloads. Each single-item read selects three rows in three SELECTs, including
identity and exact source membership. EXPLAIN uses the actual executed SQL and bound values;
no full scans, question/answer payload or whole-history reads are allowed in this fixture.
The new state/cycle paths use JDBC scalar projections, without JPA relationship traversal.

After stable inputs, run the existing complete gate and match its source/bytecode fingerprints
to the reviewed candidate; check documentation separately:

```bash
python3 tools/testing/context_benchmark.py verify --workers 4 \
  --output /private/tmp/quizmaker-649-verify-<unique-run>
```

Public enrollment/start/review, real session completion and current Learning access/rates
remain #578/#650/#692/#667 gates. Custom plans use #839/#840; no daily quota, mastery metric,
SM-2 import or ordinary assessment mutation is introduced.

## Immutable Learning forms (#647)

On JDK 17, use the existing offline/fake environment and disposable MySQL launcher for:

```bash
./mvnw test -Dtest=LearningFormTest,LearningFormSpringTest,LearningFormMySqlIntegrationTest,LearningSessionSpringMySqlIntegrationTest,QuizPublicationMySqlIntegrationTest,ContextTestManifestTest
```

The form companion migrates a real V118 schema with retained legacy attempt/answer rows to V119.
It uses the real #597 revision and #575 session writers; exact contract bindings and current
Learning access/content are named fixtures. Verify saved item/option order, immutable resume,
component reconstruction, identical replay, conflicting selection, explicit empty completion,
unknown/mismatched metadata/contracts, duplicates, missing/corrupt evidence and current access
denials with successful controls. Serialization/presentation must not expose raw answers/content.

Reach failures after header/item/option SQL and before commit, then inspect fresh state and retry.
Lose acknowledgement after an actual commit and reconstruct the component to prove one retained
form. Verify outer RC rollback, caught-failure rollback-only and incompatible/default/read-only
callers rejecting before SQL. Race identical/conflicting creators with reached barriers and an
actual Performance Schema session-row wait; one creator commits and the peer replays/conflicts.
An independent learner must commit forms on the same and an unrelated revision before the holder
is released. Finite watchdogs are test safety bounds, not throughput promises.

The existing Spring/MySQL session companion additionally exercises real form method-security,
JDBC and SafeJpaTransactionManager proxies, actual RC and pool restoration. Inject ordinary,
1205 and 1213 failures after real form SQL and require reached proxy TRACE events with clean
formatted messages and throwable graphs. Successful caller writes roll back with the failure;
fresh reads and same-selection retry prove recovery. A real AFTER INSERT MySQL SIGNAL separately
proves driver diagnostics are sanitized before nested transaction logging.

The publication companion calls the real #700 author edit/publish path for A and B, then resumes
the actual #647 form on A. Its safe Learning source/access and response binding are fixtures:
this is publication/form compatibility evidence, not public Learning conformance. The primary
read fixture measures statement and returned-row counts across multiple forms and 1/3/500/501
selected items in a 1001-item revision with large raw payloads. It checks 9 SELECTs at up to 500
items, 11 at 501, and exactly 5 + 6 × selected-item rows (two options per fixture item), plus
bounded 500-row insert batches. New JDBC paths select only bounded
requested metadata, never question text/answer JSON; future real content adapters retain their
own volume/N+1 gate. Assessment/schedule state and legacy rows must remain unchanged.

After stable implementation inputs, run the complete existing four-worker gate:

```bash
python3 tools/testing/context_benchmark.py verify --workers 4 \
  --output /private/tmp/quizmaker-647-verify-<unique-run>
```

Match the tested source fingerprint to the candidate tree; review Markdown separately. This
foundation enables no endpoint, enrollment, review, schedule mutation or public Learning flow.

## Independent Learning session storage (#575)

On JDK 17, begin with `LearningSessionTest` and `LearningSessionSpringTest`, then use the existing
offline/fake environment and disposable MySQL launcher for:

```bash
./mvnw test -Dtest=LearningSessionMySqlIntegrationTest,LearningSessionSpringMySqlIntegrationTest,ContextTestManifestTest
```

The MySQL companion migrates the real schema through V117, retains actual legacy quiz/attempt/answer
rows, then applies additive V118. It uses the real #597 revision writer and real #575 persistence.
Verify exact source/manifest/microsecond retention, same-key replay after component reconstruction,
conflicting payloads, unknown/missing/wrong source metadata, foreign ownership, current source denial
and inactive accounts. Current identity uses a SQL-backed test adapter; source access is an explicit
contract fake, not proof of future public Learning authorization.

Reach failures after the session insert, after the real receipt insert and before commit; fresh
connections must see neither record, and an independent retry must succeed. Lose acknowledgement
after actual commit and reconstruct the service/repository: the same command must replay one stored
session. Outer RC rollback removes both records; a caught participating write failure marks the
caller rollback-only. Incompatible/default/read-only callers reject before identity/domain SQL.

Race identical and conflicting creators with reached barriers and an actual MySQL key-wait check.
One creator must commit; the identical waiter replays, and conflicting data cannot overwrite it.
Hold a creator after its receipt insert and prove another learner commits on the same revision and
an unrelated revision before releasing the holder. Finite watchdogs bound hangs, not product latency.

`LearningSessionSpringMySqlIntegrationTest` additionally exercises the real method-security,
repository and `AuthenticatedUserLookup` proxies, UserRepository and SafeJpaTransactionManager.
Verify actual RC, participating rollback, reached SQL failure/retry and pool isolation restoration.
The ordinary Spring-only suite proves absent/ambiguous source providers deny and no scheduler is
required. Both context suites and the database companion are assigned in the existing inventory.

The Spring/MySQL suite also enables TRACE on the real nested transaction interceptors. Inject
driver failures after actual SQL for all five repository methods, including create, read and exact
replay; inspect both formatted messages and rendered throwable graphs for private SQL/driver,
cause, next-exception and suppressed markers. Require a reached repository exception event so an
empty capture cannot pass. Direct proxy calls retain only sanitized DataAccessException categories
and bounded 1205/1213 conflict evidence. A caught service failure rolls back the caller's successful
control write and all failed-command writes; fresh reads and same-key retry prove recovery. A real
MySQL SIGNAL trigger independently reproduces the original diagnostic leak and verifies cleanup
before repository TransactionInterceptor logging, not just the later service translation.

Apply the same boundary rule to `AuthenticatedUserLookupImpl`: both ordinary and locking lookup
sanitize DataAccessException before their own transaction proxy. The Learning Spring/MySQL suite
injects faults after the real Spring Data/JPA identity query for create, read and exact replay, with
ordinary/1205/1213 failures. Require the reached lookup-proxy TRACE event, clean messages/throwable
graphs, preserved conflict classification, rollback of successful caller writes and same-command
retry. Direct facade tests cover both methods, including standalone read-only lookup. Run
`AuthenticatedUserLookupTest`, `AuthenticatedSubjectResolverTest`,
`AuthenticatedSubjectResolverMySqlIntegrationTest` and `AuthenticatedSubjectResolverWiringTest`
for shared-consumer compatibility. The multi-user role/permission fixture measures one scalar
query per identity and zero entity/collection loading for both ordinary and locking reads.

The metadata read fixture has three sessions for each of 1- and 100-item revisions. Count selected
statements and assert no question/form/answer/schedule content is queried. These JDBC metadata paths
have no JPA relationship traversal; the test proves bounded source loading rather than relying on
an annotation. Retained legacy rows/results and repetition tables must remain unchanged.

No endpoint, new access policy, active-cycle authority or public Learning capability is verified by
this slice. #647/#649/#578/#650/#692/#667 retain their real integration/activation responsibilities.
After stable inputs, run the existing full four-worker gate:

```bash
python3 tools/testing/context_benchmark.py verify --workers 4 \
  --output /private/tmp/quizmaker-575-verify-<unique-run>
```

Match its source fingerprint to the reviewed candidate tree before commit; Markdown is reviewed
separately. Keep complete diagnostics outside the repository and report actual results in the handoff.

## Reproducible FSRS contract (#577)

On JDK 17, run the pure manifest, calculation, format and boundary cases before the small
Spring/Jackson compatibility context and existing SM-2 compatibility tests:

```bash
./mvnw test -Dtest=FsrsManifestTest,FsrsSchedulingStrategyTest,FsrsStateFormatTest,FsrsBoundaryTest,FsrsSpringCompatibilityTest,Sm2AlgorithmTest,ContextTestManifestTest
```

Use the existing offline/fake environment. No scoped FSRS test needs MySQL or an external
provider. Resolve the approved `io.github.open-spaced-repetition:fsrs:1.0.0` artifact before
offline execution; do not import its Jackson versions over Spring Boot dependency management.
The current resolved Jackson components are 2.18.3. The compatibility context exercises the
actual library's Card/Scheduler JSON as well as Boot's mapper and the project-owned format;
library JSON remains test evidence, never persistence authority.

`src/test/resources/learning/fsrs-v1-golden.tsv` freezes 28 transitions from the direct pinned
binary, independent of the project adapter. Every source state is reached from a fresh card
through the histories in `FsrsSchedulingStrategyTest.source`: all four ratings, both learning
steps, early/overdue review, and short/long relearning. Compare phases, steps, stability/difficulty
state (floating tolerance 1e-12), exact nanosecond due/review Instants, manifest and identity.
Do not regenerate expected values from the adapter being tested. The separate approved first
rating controls assert 60/330/600 seconds and 16 days; repeated reachable reviews must hit the
3650-day ceiling without fuzz.

Reconstruct both source and output with fresh codecs/components. Verify defensive array/list
copies, unknown/malformed versions/configuration, strict JSON shape, scalar/enum coercions,
missing/null required fields, duplicate/trailing data, identity/cycle mismatch, stale sequence,
backwards time and controlled calculation failure. An already advanced state is not a replay
input: #649 owns durable command receipts and must reconcile retries before calculating again.
Numeric compatibility fixtures also reject Instant overflow and the pinned library's rounded
day interval overflow before its int conversion can return a wrapped delay. These are explicit
calculation failures, not a different scheduler or a workload/capacity claim.
Concurrent pure calculations must retain independent learner/cycle state. Profile-zone clocks
and London spring/fall fixtures preserve elapsed 24-hour days; do not change the JVM-global
timezone in parallel tests.

The Spring-only suite has one context-manifest entry; it adds no database companion and
preserves the four-worker/exclusive inventory. No JPA-backed read is touched, so N+1 is not
applicable. Existing SM-2, quiz, training, assessment, release and certificate paths remain
separate compatibility checks. No API/OpenAPI, enrollment, database-write, workload or public
Learning activation claim follows from this pure contract.

After stable implementation inputs, use the full release gate:

```bash
python3 tools/testing/context_benchmark.py verify --workers 4 \
  --output /private/tmp/quizmaker-577-verify-<unique-run>
```

Compare the launcher's tested source fingerprint with the candidate inputs before commit.
The fingerprint includes `src/`, `.github/`, `tools/`, `.mvn/`, `pom.xml` and `mvnw`, including
new nonignored files; architecture/testing Markdown is reviewed separately. #649/#667 own
real review persistence, transaction rollback, durable duplicate races and activation evidence.

## Durable first-release events (#837)

On JDK 17, run `ResultNotificationEventTest` first, then
`ImmediateResultReleaseMySqlIntegrationTest#events*` using the existing offline environment and
disposable MySQL launcher. The new cases share the established real publication/start/acceptance/
submission/grading/release context. The final four-worker gate includes the complete release
suite and disclosure/configuration tests; existing scenarios and fixture sizes stay intact.

The real #556 transaction must save exactly one release/trace/notification reference. Reach
failures after release, before event insertion, after the actual event insertion and at commit;
fresh transactions must see none of the three. Retry through the same real writer successfully.
Lose the actual commit acknowledgement, reconstruct repositories/readers and reconcile the same
source/event identity. Repeated reads with a lost consumer acknowledgement retain the same row;
this local consumer double proves no #800 delivery or deduplication implementation.

Hold the first event insert, prove its duplicate reaches the serialization boundary and waits,
and require another authorized participant on the same revision to commit before releasing the
holder. Both identical callers must succeed with one new release and one replay. Read before
commit to prove the event is invisible, then start a fresh scan after commit. Scan cursors are
not commit watermarks; every new sweep starts at null so a late lower-ID commit remains eligible.

Publish a real later revision, accept/grade/release a new result, and reconstruct/replay the old
event: its references/categories/bytes and original result remain unchanged. This tests immutable
source references, not a corrected result or #550 selection. Conditional score/certificate tests
use typed source facts only and are explicitly classification evidence. Every additionally enabled
producer still requires its named real writer and its atomic integration scenarios.

Check duplicate/schema/source constraints, bounded page validation, actual RC, incompatible caller
refusal before domain SQL and outer-RC rollback. Inject repository insert/find/scan failures through
real transaction proxies at TRACE and inspect messages/throwable graphs for SQL, driver/contact,
cause, suppressed and chained payloads. Retain successful controls after removing each fault.
Measure one scalar event-scan SELECT for three one-item parents, then after adding three 20-item
parents; inspect its primary-key range plan and absence of grade/answer/content collection reads.
New reads are JDBC scalars, so JPA
relationship N+1 does not apply; existing multi-parent participant projections still run.

The initial integration enables immediate release only. Scheduled release remains functional but
creates no #837 event until its separately owned producer gate is met. There is no email provider,
participant event endpoint, historical backfill or read-time deletion.

After final implementation inputs are stable, use a fresh output directory:

```bash
python3 tools/testing/context_benchmark.py verify --workers 4 \
  --output /private/tmp/quizmaker-837-verify-<unique-run>
```

Record the launcher's actual evidence and source fingerprint, then match them to the candidate
inputs/tree before committing. A source fingerprint is not itself proof that a test passed.

## Scheduled result release (#634)

On JDK 17, run `ScheduledReleaseTest` first. Use the existing offline/disposable-MySQL launcher
for `ImmediateResultReleaseMySqlIntegrationTest#scheduled*`, then the complete release suite,
`ReleaseConfigurationMySqlIntegrationTest` and disclosure tests. The scheduled scenarios share
the established release context; they do not introduce a duplicate database/context fixture.

The worker fixtures use real publication/start/answers/submit/automatic-result commands to supply
committed binary results, and real configuration publication/reading, trace and release writers.
Attaching a captured rule at this component boundary does not prove formal-start capture; #668
owns that integration. No external provider or fake release flag supplies a successful outcome.

Check T minus one millisecond, T, saved configuration A after authoring B, timezone changes,
strict unknown-version handling, authorization negatives and category withholding. Reach faults
after trace, release and completed-work writes; inspect fresh transactions for rollback, persisted
retry time and a successful same-path control. Lose actual commit acknowledgements for registration
and execution, then reconstruct and verify the same retained identities and unchanged retry budget.
Advance all three approved retry waits across reconstruction and prove exhaustion cannot auto-resume.

Use actual Spring transaction proxies for worker dependencies, including reconstructed repositories.
Inject reached failures in release lookup and insertion and capture the nested `TransactionInterceptor`
at TRACE, proving that the exception completion was logged without driver messages, SQL, cause,
suppressed or chained-exception payloads. Retain bounded deadlock/timeout classification, real rollback
and successful retry/replay controls through those same proxies. An unproxied `new` repository cannot
establish this logging guarantee.

Register a due job at 13:00:10, then execute with another host's clock at 13:00:09 and just before
the captured registration instant. Both calls must return NOT_DUE without writes or retry-budget use.
At 13:00:10, the first release or its lost-acknowledgement reconciliation succeeds; reconstruct and
repeat with recovered and subsequently regressed clocks, preserving one release and its exact receipt.

For concurrency, hold a reached write, prove the duplicate is waiting, then require both callers to
converge on one committed release. A separate participant attempt using the same revision and
configuration must commit before the holder is released. Race registration with immediate release
to prove the immediate worker cannot bypass a captured schedule. Verify actual RC on the enlisted
connection and restoration of a suspended outer RR caller; worker work remains independently committed.

Measure worker SQL for three parents with 1 and 20 items. No question/document/response payload or
grade collection is selected by the worker, so JPA relationship N+1 is inapplicable there. The
existing participant-read multi-parent tests also remain part of compatibility coverage. Validate
the due cursor query's covering index and absence of a filesort, and ensure a poison row cannot
starve later pages. Keep inventory, four-worker scheduling and coverage gates unchanged.

After the final inputs are stable:

```bash
python3 tools/testing/context_benchmark.py verify --workers 4 \
  --output /private/tmp/quizmaker-634-verify-<RUN>
```

## Immutable release configuration (#697)

Start on JDK 17 with `ReleaseConfigurationTest` and `ReleaseConfigurationActivationTest`.
Use the existing offline/disposable-MySQL launcher for `ReleaseConfigurationOpenApiTest` and
`ReleaseConfigurationMySqlIntegrationTest`. The context manifest includes both programmatic/MVC
contexts and the standalone database companion; exclusive scheduling and existing suites remain.

Validate explicit trigger/category combinations, the independent canonical golden bytes, strict
unknown/null/version parsing, rejection of numeric enum ordinals, IANA gap/overlap controls and
exact existing practice mapping.
For each denied time input retain a successful same-path control. Reconstruct the codec/service
under another process timezone and compare the saved local/zone/offset/UTC evidence unchanged.
The HTTP suite checks real security filters, named full/grouped schemas, actual responses,
request examples, safe ProblemDetail errors, discovery and default-disabled authoring. SQL-backed
author tests prove owner/permission/public-moderation/active-account checks, including replay.
The wrong-owner control must have real `QUIZ_UPDATE`, so denial reaches ownership validation.
Source reads use the actual `TransactionInterceptor` around `JdbcQuizPublicationRepository`.
Inject driver failures after each reached user/permission/quiz/revision SELECT, capture TRACE
messages and throwable graphs, and assert that private diagnostics and SQL are absent before
the outer adapter translates the error. Catch the safe failure inside an outer RC transaction,
prove writes both before and after it were reached, then verify their rollback in fresh reads.
Disable the fault and prove the same authorized publication/replay path still succeeds.

The MySQL suite owns actual version/head/receipt writes. Inject faults after each reached SQL
checkpoint, inspect through fresh transactions, and prove one successful retry. Preserve outer-RC
rollback, caught-failure rollback-only and incompatible-caller refusal. Race identical and stale
commands with real committed fixtures; prove one durable version and a permitted winner. Hold
one publication after INSERT and require another quiz with the same author to commit before
releasing the holder. Finite watchdogs guard the barriers, not performance promises.
Lose the acknowledgement after the real commit, reconstruct the service, and reconcile the
same durable version/receipt. A future-version fixture must carry its corresponding canonical
metadata hash; reject it without moving the current binding or inventing a current format.

Configuration reads select bounded scalar metadata, without JPA relationships or content reads;
JPA N+1 is inapplicable. The source revision fixture uses the real immutable revision repository
but does not claim full publication/start/release conformance. Manual/scheduled execution,
notification delivery and certificate issuance remain separate integration gates.

After final inputs are stable, run the unchanged four-worker complete clean-verify gate with a
fresh output path and compare its `source` fingerprint with the candidate implementation:

```bash
python3 tools/testing/context_benchmark.py verify --workers 4 \
  --output /private/tmp/quizmaker-697-verify-<RUN>
```

## Internal ungraded response and completion (#567)

On JDK 17 and disposable MySQL, with the standard offline test environment:

```bash
./mvnw test '-Dtest=ActivityContractTest,AttemptStartValidationTest,AttemptSubmissionMySqlIntegrationTest#ungraded*+*Ungraded*+repeatableReadAccessDistinguishesBothActivityKindsCommittedAfterItsSnapshot'
```

The MySQL cases start real fixed-form activities and exercise both response retention modes.
They check required versus optional participation, private binding and replay authorization,
post-terminal attempts, same-key changed content, conflicts, and no response payload or
payload-derived command fingerprint in the no-retention mode. The graded control invokes the
same OPEN scorer through the real automatic-result worker. Ungraded commands must produce no
scorer invocation, graded submission, finalization intent or result row.

With Spring JDBC parameter TRACE enabled, the retained OPEN response keeps the exact normalized
text in MySQL and logs only the private parameter marker. A separate graded RR access transaction
holds its own attempt open until a second user commits a real ungraded start on a different quiz.
The test exercises both relative user ID orders in the participant binding index.
An older RR snapshot also grants each kind only through its matching access operation, even when
both attempts committed after that snapshot.

The suite injects failures after real participation and terminal writes, checks rollback through
fresh reads, and retries using a reconstructed service and real receipts. It also covers outer
rollback, uncertain commit, and overlapping completion transactions at the lifecycle row lock.
Completion loads participation IDs in one statement and measures the total SELECT count across
three forms with different item counts. The test compares statement counts and checks the
participation read excludes response values. Existing fixed-form reads still load the form's
immutable revision, so row volume depends on form and revision size.

Run the full four-worker clean verification before publishing a branch. The feature's internal
commands have no HTTP scenario. Public ungraded start/notice production and the participant
workflow are separate integration and activation checks.

## Ungraded activity contract (#566)

On JDK 17, start with the pure contract and existing scored compatibility cases in the offline
test environment:

```bash
./mvnw test -Dtest=ActivityContractTest,ActivityContractSerializationTest,ActivityContractPurityTest,ResolvedAttemptPolicyTest,AttemptLifecycleTest,AutomaticResultTest
```

Cover both explicit response-retention modes. No-retention start requires the advance notice;
ordinary retained-response start remains a successful control without that additional gate.
Exhaust required/optional participation subsets and both final-submit choices. Missing required
participation, foreign/duplicate items, not-started, abandoned, expired and completed states must
not produce a new completion. These are pure plans over trusted form/participation facts, not
proof of real response storage, final-submit transactions or durable replay.

Verify actual graded zero/nonzero results as positive controls, with unchanged pass semantics,
canonical policy bytes/hash and provenance. Include an unanswered graded item with its valid
explicit null `responseId`; missing fields and null required identities still reject.
An ungraded completion must structurally omit score,
denominator, correctness, pass, grader and rating fields. JSON rejects these fields even when
null/zero, at the root and nested levels. Also cover both retention modes, every incomplete state,
missing/null fields, unknown kind/version, partial legacy metadata, invalid timestamps/UUIDs,
duplicate keys, trailing documents and forbidden scalar coercions. Reconstruct the codec for a
round trip; no global ObjectMapper or default-typing change is needed. Failures must retain no
private input, parser cause or suppressed exception.

The purity check excludes framework, I/O, application-scorer and clock-lookup dependencies.
The contract performs no persistence or JPA relationship read, so transaction/concurrency and
N+1 tests are inapplicable here. Actual writer/access/disclosure/API integration belongs to
#567/#642, reporting/certificate/ranking consumers to their named issues, and activation to #695.
Do not present these contract tests as proof of those integrations.

After implementation inputs are stable, run the existing complete four-worker clean-verify
launcher with a fresh output path, preserving inventory, exclusive scheduling and merged coverage:

```bash
python3 tools/testing/context_benchmark.py verify --workers 4 \
  --output "/private/tmp/quizmaker-566-verify-<RUN>"
```

## Fixed-form integrity (#613)

Start on JDK 17 with `./mvnw test -Dtest=FormFingerprintTest,FixedFormTest,LegacyCompatibilityTest`.
The independent golden fixture fixes the v1 byte format; mutation cases cover identity, revision,
position, policy, response/scoring bindings and option sequence. The large fixture measures actual
canonical bytes for 500 items/3000 options and maximum-length contract IDs. The encoder accepts
metadata only and streams to SHA-256 without source reads or aggregate JSON allocation.

Use the existing disposable MySQL/offline environment for `FormOptionOrderMySqlIntegrationTest`
and `FixedFormMaterializerMySqlIntegrationTest`. The V89-to-V114 fixture preserves an existing
unverified row without inventing a fingerprint. Exercise structurally valid position/option
changes, altered digest, unsupported version and absent evidence with successful controls.
Exact replay must not reseal corruption or an unverified row. Restore only each deliberately
corrupted field and prove the same read/replay succeeds after service reconstruction. A corrupt
second parent must reject the entire batch. Keep constraint, lost-acknowledgement, reached-write
rollback, outer rollback and concurrency fixtures; both racing workers failing is not success.
Synthetic coherent future-contract fixtures must also carry their corresponding form fingerprint;
changing binding metadata alone is corruption and cannot prove unsupported-contract behavior.

The measured metadata read uses three parent forms of 500 items and six options each. Assert
three SELECTs, actual returned rows/bytes and absence of question/document payload columns; inspect
the joined item lookup plan. Smaller existing multi-parent fixtures retain the same query count.
The extra join follows `(form_id,item_id)` keys and loads no JPA relationship graph.
Measured on 18 November 2026: three 500-item/six-option forms returned 10,503 metadata rows and
784,617 bytes in three SELECTs. The maximum-contract-ID encoder fixture consumes 212,068 bytes
for AUTHORED and 212,080 for SHUFFLED_PER_ATTEMPT; these are input sizes, not latency guarantees.

Then run `AttemptDeliveryMySqlIntegrationTest`, `ParticipantAttemptViewMySqlIntegrationTest`,
`LegacyCompatibilityHttpIntegrationTest`, the affected OpenAPI suites and terminal submission/expiry
coverage. Real HTTP tests must preserve the existing safe 500 integrity problem (503 is storage
unavailability), return no partial content and perform no repair. Preserve authentication/permission/
ownership denials, compatible RC participation, incompatible-caller refusal and no target-to-legacy
fallback. All nine legacy families must still answer/review through the actual old path without
creating any target forms. Finish with the unchanged four-worker clean-verify, inventory, exclusive
scheduling and merged coverage gates. Do not infer production row absence from local fixtures.

## Protected internal trace query (#540)

Start with `TraceQueryTest`, `ExecutionTraceTest` and `ContextTestManifestTest` on JDK 17.
Use the existing disposable MySQL/offline environment for `TraceQueryMySqlIntegrationTest`
and `ExecutionTraceMySqlIntegrationTest`. The former opens a small programmatic Spring context
and has one context inventory entry; preserve all existing worker/exclusive selections.

Run the real append writer and prerequisite stores before querying. Cover ascending pages,
changed page sizes, same timestamps, append after the first cut, service reconstruction and
exact continuation. An append must successfully commit before releasing a held reader barrier;
neither both workers failing nor a runner-speed assertion proves independence. Verify that a
new first-page query includes the later event while the original cut excludes it.

Exercise authentication through the actual Spring method-security proxy. The SQL-backed context
fixture grants only exact principal/attempt/purpose rows; global operator-role text alone grants
nothing. Cover missing/ambiguous context integration, foreign resource/organization/no grant,
revocation between pages, cross-attempt/purpose cursor reuse and successful controls. Context
fixtures establish consumer enforcement, not the production #627 permissions or #698 audit.
Explicit expired/retained-away/unknown facts test query classification only, not #699 cleanup.
Missing rows must never be silently classified as erased or reconstructed from mutable content.

Corrupt the second stored event, stream version/counter and an interior sequence under isolated
test-only constraint control. Assert a bounded typed failure and no partial page, restore each
constraint and prove the valid control still succeeds. Inject an actual query timeout checkpoint,
prove it was reached and confirm fresh reads retain all committed evidence. Verify actual RC,
compatible outer writable/read-only RC, caller rollback, early incompatible-caller refusal and
restoration of isolation/read-only state across the entire small pool. Existing writer RR cases
remain enabled and unchanged.

For three parents with 3, 30 and 250 events, serialize a two-row query page and measure SQL and
actual returned rows/bytes. Expect two scalar trace statements plus the context fixture's one
authorization statement; the event page selects the same two bounded metadata rows regardless
of total history. Inspect both head/tail and page plans for primary-key access without filesort.
No JPA graph or document/question content is involved. Future production context readers must
separately establish their own bounds. Finish with the unchanged four-worker clean-verify,
complete inventory, exclusive scheduling and merged coverage gates. No HTTP schema or endpoint
is introduced by this issue; #627 retains that contract work.

## HOTSPOT internal response/scoring contract (#828)

Start with `HotspotContractV1Test`, `HotspotHandlerTest`, `HotspotSemanticsTest`,
`ComplianceContractV1Test`, `MatchingContractV1Test` and `StableResponseNormalizerTest`.
Golden tests use the real public legacy handler; direct `doHandle` coercion is not a supported
HTTP response contract. Cover any correct region, unknown integer IDs, malformed responses,
2–6 regions, signed IDs, zero dimensions, overlap, nonnegative geometry and bounded optional
descriptions. Blank strings still have their schema length bound. Ignored extensions must
not bypass byte/string/array limits. Stable UUID transport rejects foreign form-item references.

Run `ImmediateResultReleaseMySqlIntegrationTest#hotspot*` through the existing disposable
MySQL/offline launcher, then the full affected result/publication/form suites. The HOTSPOT
fixture uses actual retained-image preparation and binding, publication, start, acceptance,
batch, submission and automatic grading. External storage is fake; no remote URL is fetched.
Public v2 HOTSPOT schemas/delivery are not implemented by this fixture and remain #818/#609.

Assert distinguishable saved v1 and test-only v2 resolution after edits/reconstruction.
Unknown-version and reached-INSERT binding failures must preserve the previously committed
publication/current pointer/receipt while leaving zero partial bindings. A binding race must
have one successful durable winner and one conflict. Reach failures after real answer,
lifecycle, trace and receipt SQL; inspect fresh transactions before retry and reconstructed
replay. Keep unauthenticated, wrong-user, insufficient-permission and real cross-item-reference
negatives with successful controls. Prove joined RC caller rollback with the real atomic batch.

Measure internal-form reads for three parent attempts with 1 and 20 HOTSPOT questions and
require equal bounded SELECT counts. Metadata-only contract resolution must select no question
or document content and perform zero storage calls after preparation. Finish with the unchanged
four-worker clean-verify launcher, complete-inventory checks, exclusive scheduling and merged
coverage thresholds. Existing v1 and all other family fixtures remain enabled.

## COMPLIANCE immutable answer contract (#829)

Run `ComplianceContractV1Test`, `ComplianceHandlerTest`, `MatchingContractV1Test`,
`OpenContractV1Test`, `FamilyContractV1Test` and `StableResponseNormalizerTest` first.
Then run `PublishedFamilyContractsMySqlIntegrationTest`,
`ImmediateResultReleaseMySqlIntegrationTest`, `DraftCommandMySqlIntegrationTest`,
`ResponseAcceptanceOpenApiTest`, `DraftCommandOpenApiTest`, `AttemptFormOpenApiContractTest`
and `ParticipantAttemptViewOpenApiContractTest` through the existing isolated MySQL/offline
launcher. Finish with the full four-worker clean-verify gate; keep all previous inventory
entries, exclusive scheduling and coverage thresholds.

Golden fixtures compare the actual public legacy handler with frozen COMPLIANCE v1.
Cover multiple compliant statements, exact/partial/extra/empty/unknown selections,
order independence and duplicate selections. Duplicates are valid answers worth zero;
canonical transport sorts UUIDs but must never deduplicate them. Public transport rejects
foreign or malformed UUIDs before an accepted answer is written. Author fixtures include
2–6 statements, missing/non-boolean flags, duplicate/signed-boundary IDs and media-only
statements. The five-family #701 catalogue stays frozen.

Publication evidence must distinguish saved v1 from deliberately different test-only v2,
reach binding-write failures, retain the independent committed publication/pointer, and
prove one durable concurrent binding winner. Production supports only COMPLIANCE v1.
The existing metadata-only registry tests cover large scopes without selecting document
or question content; text-only family storage fakes reject all external access.

Execution evidence uses real publication, start, acceptance, batch, terminal submission,
grading and release writers. Reach failures after answer/state/trace/receipt SQL and inspect
fresh transactions before retry. Preserve caller RC rollback, replay authorization, original
statement UUID/order and historical results after author edits. Draft save/restart/replay
must retain duplicates without confirming or grading the answer. Measure serialized released
views for three parents with 1 and 20 COMPLIANCE questions and assert equal bounded SELECTs.

Media fixtures prepare/bind/remove an actual retained image and publish media-only statements.
Only storage and the current display-authority port are faked. Repeated use of one asset in
two statements must retain two occurrence identities. Reader-authorized URLs must differ
from captured publication URLs; unavailable/restricted review keeps markers and scores,
active delivery refuses missing access, and missing/foreign occurrence output fails closed.
The default missing integration must refuse media. No provider availability or public
activation is established by these local component integrations; #818/#831 and #609 retain
their respective rollout gates.

## MATCHING immutable answer contract (#827)

Run `MatchingContractV1Test`, `MatchingHandlerTest`, `FamilyContractV1Test`,
`OpenContractV1Test` and `StableResponseNormalizerTest` first. Then run
`PublishedFamilyContractsMySqlIntegrationTest`, `ImmediateResultReleaseMySqlIntegrationTest`,
`ResponseAcceptanceOpenApiTest`, `DraftCommandOpenApiTest`, `AttemptFormOpenApiContractTest`
and `ParticipantAttemptViewOpenApiContractTest` with the existing isolated MySQL/offline
launcher. Finish with the unchanged four-worker clean-verify gate. The new programmatic
Spring catalogue test has one context inventory entry; existing suites and fixtures remain.

Golden fixtures compare the actual public legacy handler with frozen MATCHING v1. Cover
correct/partial/wrong/empty pairs, last-pair-wins duplicates, many-to-one authoring, signed
integer boundaries, malformed values, duplicate IDs, missing right references and media-only
items. Invalid-media and missing-reference fixtures must contain two items in both columns
so the intended check is reached. Stable transport separately rejects foreign/wrong-side UUIDs.

Publication tests prove exact v1/test-only-v2 dispatch with distinguishable points, actual
binding rollback on an unknown version with the independent published pointer/receipt preserved, reached binding-write rollback,
and one durable concurrent binding winner after reconstruction. Publication still rejects an outer
transaction before work; these tests do not invent atomic publication/binding composition. Existing large-content
metadata-only resolver tests retain the bounded version-check proof; storage fakes reject
unexpected content access.

Execution tests use real publication, start, accepted-response, batch, terminal submission,
grading and release writers. Inject faults after real answer/lifecycle/trace/receipt SQL and
inspect fresh transactions before retry. Retained-media tests call the actual image preparation
and binding services with a storage fake, publish the media-only question, remove it from the
library, then accept and grade against its retained original. Storage calls during execution
fail the test. Inline images in both columns must reach the same batched current-authority port
as attachments. Test reader-authorized URLs different from the captured CDN URL, RESTRICTED,
UNAVAILABLE, the default missing-integration reader, incomplete output and wrong occurrence
keys. Active `/form` and view refuse unavailable images; released review emits markers without
URLs and preserves results. Reusing one image in two column options must produce two scoped
references. A successful real start/accept/grade/release control precedes each denial.
This does not prove provider availability or authorize public activation.

Measure released views across three parent attempts with 1 and 20 MATCHING questions and
assert equal bounded application SELECT counts. Typed requests, forms, drafts and released
pairs must validate against both full and `attempts-v2` OpenAPI documents; malformed HTTP
values never reach the mocked application writer. Preserve all existing family controls.

## OPEN immutable answer contract (#826)

Run `OpenContractV1Test`, `OpenQuestionHandlerTest`, `FamilyContractV1Test` and
`StableResponseNormalizerTest` first, then `PublishedFamilyContractsMySqlIntegrationTest`,
`ImmediateResultReleaseMySqlIntegrationTest`, `ResponseAcceptanceOpenApiTest`,
`DraftCommandOpenApiTest`, `AttemptFormOpenApiContractTest` and `ParticipantAttemptViewOpenApiContractTest` in the existing
isolated MySQL/offline environment. The existing database suites retain their inventory slots; the programmatic-context
`OpenContractV1Test` is added to the context inventory. No new worker group or reduced fixture
is introduced. Finish with the unchanged full
four-worker clean-verify launcher.

Preserve the five-family controls. New OPEN evidence must reach real response inserts,
rollback accepted rows/receipts/state/trace together, retry the same identity, reauthorize replay
and prove that a later author edit/publication cannot change historical text or scores. The
version-2 adapters in the publication suite are deliberately different test doubles only for
exact saved-version resolution; production still supports OPEN contract version 1 exclusively.
Use the second revision's correct answer to distinguish real v1 scoring (one point) from the
test-only v2 scorer (zero), and assert the resolved scorer version as well as its binding.
An OPEN draft accepted by HTTP must validate against both the full and grouped OpenAPI schemas.
Use English/Turkish/Lithuanian locale controls for the approved ROOT rule and assert the
unchanged legacy Turkish difference. Empty/blank valid text scores zero; malformed public
input never becomes an accepted wrong answer.

The released-view test measures actual application SELECTs for three parents with 1 and 20
OPEN items. Metadata-only contract resolution additionally uses the existing CONTRACTS-04
large-content fixture; version checks must not fetch/hash stored document content. External
storage fakes fail on any access in the text-only publication path. No activation,
capacity or complete nine-family support follows from these local checks.

## Assignment publication (#621)

Run `AssignmentPublicationTest`, `AssignmentPublicationMySqlIntegrationTest`,
`AssignmentPublicationSpringMySqlIntegrationTest`,
`AssignmentContractTest`, `AssignmentSchemaMySqlIntegrationTest`, `AssignmentEligibilityTest`
and `AssignmentEligibilityServiceTest` first using the isolated MySQL/offline environment below.
The database companion and Spring suite are included in the complete four-worker inventory.

Publication tests must use the real service and JDBC writer. Versioned source/membership
fixtures may establish already-valid source facts; they do not establish real roster management,
HTTP authoring, assignment start, transfer or cancellation. The active-user service facade uses
a SQL-backed repository test adapter in the standalone suite. Its current SQL scope-role fixture
binds role permissions to the actor's exact personal/organization scope; global role permissions
plus membership are insufficient. Include teacher-in-A/student-in-B controls, refusal before
identity writes, permission loss with membership intact, persisted-scope rather than caller-scope
checks, missing/ambiguous authority, and reauthorization of all reads and exact command replays.
The Spring suite uses real method-security/repository/identity proxies, the actual JPA user
repository and production `SafeJpaTransactionManager`, with caller rollback, reached-insert
rollback/retry, actual connection isolation and pool restoration. Only source/membership/scoped
grant readers remain fixtures; they do not prove production grant/delegation integration.

For publication races, hold after the real version INSERT, observe the peer's root lock wait,
then check one committed winner or exact replay. For independent assignments on the same source,
prove the peer committed before releasing the holder. Reach INSERT/pointer/receipt fault points
and inspect fresh transactions for full rollback; a successful retry must commit once. Preserve
old A after publishing B and reconstruct the service before replaying A. Three current-publication
reads each use four scalar application SELECTs with the SQL authority fixture, with no question
aggregate load. Production authority readers need their own bounded-query evidence. Driver
transaction-control commands are separate from this measured application count.

## V2 READ_COMMITTED contract (#905)

V2 standalone start/answer/submit/draft/navigation and participant reads explicitly own RC.
Tests composing them must set outer RC explicitly; DEFAULT on a server-default RR database
is intentionally rejected. `hibernate.connection.isolation=2` was ineffective with the configured
pool and has been removed from both test profiles. Do not replace it with a global test RC default:
that would hide missing transaction ownership and weaken legacy RR coverage.

Run scoped boundaries first, using the existing isolated MySQL launcher and fake external ports:

```bash
./mvnw test -Dtest=AttemptTransactionsTest,CommandReceiptMySqlIntegrationTest,ResponseAcceptanceMySqlIntegrationTest
./mvnw test -Dtest=AttemptStartMySqlIntegrationTest,AttemptDeliveryMySqlIntegrationTest,ParticipantAttemptViewMySqlIntegrationTest,DraftCommandMySqlIntegrationTest,NavigationMySqlIntegrationTest,AttemptSubmissionMySqlIntegrationTest,AutomaticResultMySqlIntegrationTest,ImmediateResultReleaseMySqlIntegrationTest,ParticipantViewLegacyHttpIntegrationTest
```

The rejection matrix covers DEFAULT-on-RR, explicit RR, READ_UNCOMMITTED, SERIALIZABLE,
and read-only write callers before identity/domain SELECTs or reservations. Read-only RC reads
remain permitted. NOT_FOUND still makes joined work rollback-only; post-read review INACTIVE
still does not. Real JPA composition explicitly flushes inputs, refreshes JDBC outputs and
keeps unrelated pending changes, with commit and outer rollback controls. Existing real deadlock,
unknown-commit, authorization, sanitization and independent-participant tests remain.
Only obsolete successful target-RR cases are withdrawn. Phase B also makes fixed-form/option
writers and materialization RC-only; metadata-only snapshot reads remain available. Legacy,
canonical question binding and unrelated storage RR tests remain.

Workers retain independent RC read/write transactions: hold an outer RR snapshot, run expiry,
grading or release, roll back the caller, and verify the worker's committed evidence in a fresh
transaction. Validate actual connection isolation, not annotations. The receipt suite uses
Performance Schema's per-thread statement history and a real Hikari pool for driver overhead,
restoration/reuse, DEFAULT validation once across 500 calls, and suspension/resumption cleanup.
The measured overhead on MySQL 8.0.46 and 8.4.11 is +3 server commands for standalone RC over RR,
+1 over an RC connection, and one check for a DEFAULT participating transaction.
Use disposable MySQL 8.0 and 8.4 servers; the statement-history consumer must be enabled (the
images enable it by default). Keep this observation separate from bounded application SELECTs.
Suites change only their own pool default. Never change GLOBAL isolation inside a shared worker:
other cached pools could reconnect during the change. A server-default RC comparison uses a
separate disposable server and the receipt suite, followed by deletion of that server.

After focused checks, run the unchanged full `verify` and evidence gate with four workers.
Post-merge CI/CD checks remain enabled. Multi-parent fixtures with 1/20 items retain bounded
query counts: view 66/66, delivery 60/60, draft save 123/123, draft delete 126/126, and
navigation plus view JDBC 186/186 and JPA 15/15. Legacy view remains 6/6. These are measured
application query counts across three parents; they do not count driver isolation commands.
RC is not evidence of reduced whole-quiz rows/bytes,
removal of all contention, or activation readiness; #906 B, #629/#907 and #609 retain those gates.

## V2 replay and regression hardening (#913)

Run the publication/binding and response suites together after changing replay. The exact-set
path must preserve current authorization, all expected contract IDs/versions/hash, caller-owned
state and scope rules. The three-party regression holds a standalone HTTP single/batch request
before its actual response INSERT; two authenticated binding repeats and another participant's
answer/batch/form read (or internal start) complete before the holder is released. Inspect the
actual RC connection on an RR-default server and prove durable peer state from a fresh observer.
Use the existing owner/full-editor fixtures; do not invent assignment/student authority.

The ordinary READ-to-WRITE prohibition, predeclared WRITE scope, first-binding exclusivity,
500/501 metadata batching, stale-RR safe fallback and rollback/savepoint tests remain important.
A snapshot hint miss must not acquire binding-row shared locks or poison the transaction before
WRITE. A successful hint must be revalidated under protection; corruption never becomes replay.

The RC guard assumes isolation was established through the supported transaction manager
before work. A characterization deliberately changes session isolation inside a snapshotted
DEFAULT/RR transaction and proves that the session variable can report RC while the old data
view remains. That is an unsupported caller and a documented guard limitation, not a passing
example of supported stale-RR composition. Normal DEFAULT/RR refusal tests remain unchanged.

Existing raw-SQL cycles in deadlock tests prove exception classification and rollback/retry
behavior; they do not estimate how often normal application call order deadlocks. Correctness
races use reached barriers and committed outcomes rather than sleep-based success guesses.
Application SQL counters omit some Connector/J transaction commands: retain separate server
statement/row/byte diagnostics and representative TRUE_FALSE and MCQ_SINGLE fixtures.

Verification before commit is identified by **base HEAD plus the tested source fingerprint**.
The result's `commit` field alone names the starting commit when the worktree is dirty. Check
`source` in the verify result (`source_fingerprint` in optional diagnostic metadata) against
the final source, preserve the build/worker/coverage artifacts,
and record the eventual commit/tree when publishing. Do not claim that an unmodified base
commit was the tested implementation merely because it appears in the pre-commit result.

For separate read-volume and transaction-overhead measurements, use the existing opt-in probe:

```bash
python3 tools/testing/response_write_cost.py --read-cost --output /private/tmp/response-read-cost
```

This collects 36 samples: form read and one single answer, TRUE_FALSE and MCQ_SINGLE,
5/50/500 questions, three repetitions. Setup and assertions are outside the measured interval.
`jdbc_statements` counts application execute calls. Server counters separately report total
statements, SET/COMMIT/ROLLBACK events (`session_commands`) and isolation SELECTs
(`isolation_reads`). These categories describe this driver/configuration, not a universal
subtraction formula. Rows and bytes include server responses; neither excludes repeated data.

Local diagnostic, 15 November 2026: base `4eb124b05450f475d5112ebea9dc72a577095922`,
source fingerprint `33bbc99382b975f0366258de2129bbe4ca74807cbb9dea426ae92a35422b106e`,
JDK 17.0.17, Connector/J 9.1.0, Docker MySQL 8.4.11, 128 MiB buffer pool, ROW/FULL binlog,
server default RR and standalone RC operations. Medians of three local samples follow;
KB means 1,000 bytes. These are remaining-cost evidence, not capacity guarantees.

| Family | Questions | Form rows / KB | One-answer rows / KB |
|---|---:|---:|---:|
| TRUE_FALSE | 5 | 48 / 22.6 | 66 / 36.1 |
| TRUE_FALSE | 50 | 363 / 53.9 | 381 / 67.4 |
| TRUE_FALSE | 500 | 3,513 / 367.9 | 3,531 / 381.4 |
| MCQ_SINGLE | 5 | 68 / 24.0 | 86 / 37.5 |
| MCQ_SINGLE | 50 | 563 / 67.9 | 581 / 81.4 |
| MCQ_SINGLE | 500 | 5,513 / 507.9 | 5,531 / 521.4 |

Every form sample used 20 application / 28 server statements (7 session commands and one
isolation read); every answer used 45 / 59 (12 and two). Constant statement counts do not
remove whole-quiz growth. The metadata-only replay probe separately verifies 1/500/501
questions in 6/6/9 SELECTs with no content read, hash of document content or binding write.
The authenticated publication boundary still reads and validates the exact revision itself.

Verification of #913 on that base, 15 November 2026: final source fingerprint
`1755f7efaa4cbc28a2aae9322f0d60728b579f64b4f56fa08429848776668a78`.
Scoped persistence/consumer suites passed on MySQL 8.0.43; the final nine tightened race/rollback
cases and full four-worker `clean verify` passed on MySQL 8.4.11. The full run executed 9,808
cases, with zero failures/errors/skips: all 9,786 identifiers from the #907 baseline plus 22.
Maven reported 13:41; the launcher measured 13:43 for the command and 13:55 including DB setup
and evidence validation. All four database identities, the exclusive legacy assignment,
coverage merge/check and packaging passed; owned containers were cleaned up.

All 38 original legacy cases remain, plus the conflicting-lock control. On MySQL 8.0.43 the
2,048-answer peer took 21.0 seconds and committed before reader release, which would exceed
the former 20-second deadline. In the full 8.4.11 run it took 6.8 seconds. The three-second DB
lock budget still rejects the deliberately blocked writer. These timings are not a controlled
MySQL-version comparison or an improvement in V108 write cost.

Merged coverage is 88.42% lines / 67.93% branches (32,665 lines / 14,519 branches covered).
Coverage is not bit-for-bit identical to #907. Eight previously covered lines belong to the
unchanged ten-minute token cleanup jobs; three are the unchanged AI-start tracking catch path,
which the baseline log reached through an optimistic-lock failure on an asynchronous provider
thread and the current run did not. No unchanged source line lost branch coverage. These
incidental executions are not deterministic regression evidence; all existing test cases and
business/data/rollback/bounded-SQL assertions are retained. No coverage threshold was lowered.

## Choose The Smallest Correct Test

| Concern | Preferred test | What it proves |
| --- | --- | --- |
| Pure transformation, validator, parser, calculation, or service rule | JUnit 5 unit test | Business behavior without Spring or MySQL. |
| Controller mapping, validation, HTTP response, security, or `ProblemDetail` | `@WebMvcTest` with MockMvc | The HTTP boundary and serialization contract. |
| Custom JPA query, index-sensitive behavior, mapping, locking, or constraint | `@DataJpaTest` or focused database test | Actual persistence semantics. |
| Transaction, event listener, security chain, migration, async job, or several real layers | Focused integration test | Cross-layer behavior that a unit test cannot prove. |
| Provider protocol or API contract | Fake/stub plus contract/schema test | The boundary without calling a real provider. |

Do not start Spring or MySQL for JSON parsing, schema validation, deterministic scoring, or other pure logic. Conversely, do not mock the database when the behavior being claimed is a JPA query, lock, or database constraint.

## Project Test Environment

The full test suite uses the `test` profile and a MySQL test database. CI starts MySQL and runs Flyway migrations before the suite. Follow the existing profile and base-test conventions in the feature you are changing; do not add ad hoc database setup to individual tests.

Use `BaseIntegrationTest` only when the test genuinely needs the full Spring context. Keep unit and MVC tests independent of application startup.

### Shared MySQL Schema Isolation

The `test` and `test-mysql` profiles share schemas within one test JVM. A Spring Boot or JPA test that uses either schema must be marked `@Tag("db-serial")`. Without `context-forks`, Maven Surefire runs these tests in the dedicated `tests-db-serial` lane with JUnit parallel execution disabled. With `context-forks`, manifest-selected suites run sequentially within each isolated worker, each with its own MySQL server. The tag does not serialize different workers globally. Several legacy integration tests use Hibernate `create` or `create-drop`, which changes the complete shared schema within their worker.

- Plain JUnit and Mockito tests remain eligible for parallel execution. Do not disable JUnit parallelism globally to address a database failure.
- Add the tag to tests using `@SpringBootTest` or `@DataJpaTest`; `@WebMvcTest` normally does not create a database context and should remain in the parallel lane unless it deliberately connects to MySQL.
- Do not use `@Execution(CONCURRENT)` on a `db-serial` test. Data cleanup, transactions, and `@DirtiesContext` keep one serial test independent from the next; the tag prevents destructive schema lifecycles from overlapping.
- `DatabaseTestExecutionTagTest` enforces this policy against compiled test classes, including nested Spring contexts.
- Preserve the existing `@DirtiesContext`, transaction, and cleanup conventions in the test being changed. The serial lane protects schema lifecycle, not test data that a scenario intentionally commits.

`AttemptSubjectSchemaMySqlIntegrationTest` compares Flyway/`validate` and controlled `create-drop`
in separate, generated `quizmaker_535_schema_<id>` catalogs. It never drops shared test catalogs.
Its test-only administrative connection uses `TEST_MYSQL_PORT` and optional
`TEST_MYSQL_ADMIN_USERNAME`/`TEST_MYSQL_ADMIN_PASSWORD`; defaults match the disposable MySQL
service in CI. Those credentials need catalog create/drop privileges. This is independent of the
application datasource credentials. Each catalog is created exclusively and removed in `finally`,
including after a failing migration/validation. The fixture uses real `User`, `Role`, `Permission`
and `AttemptSubjectEntity` mappings without starting the application or provider clients.

### Canonical Verification Gate

The release-quality command is `./mvnw verify`. It runs the bounded parallel lane, the serial MySQL lane, JaCoCo reporting and threshold checks, then packages the tested application. CI runs this same command; `test` alone is not a release gate.

- Builds require JDK 17. Maven fails during `validate` with a clear message on another JDK. Set `JAVA_HOME` to a JDK 17 installation before running a build.
- The non-database JUnit lane uses a fixed pool of four workers. Do not change it to unlimited workers or disable parallelism globally. The `db-serial` lane remains one-at-a-time because legacy tests change the shared MySQL schema.
- Default and CI verification are offline with respect to third-party providers. Tests must use fakes, stubs, WireMock, or local test infrastructure; a nonblank environment variable must never be enough to authorize a paid provider call.
- CI preloads Maven dependencies before removing network access. The JUnit Platform provider is declared as a Surefire plugin dependency using Surefire's managed version. Because `dependency:go-offline` does not discover compiler `annotationProcessorPaths` or fully initialize the provider's runtime closure, CI then runs only `MavenVerificationContractTest`. This plain JUnit file/POM/workflow contract uses no Spring context, database, or external provider client; executing it resolves the exact provider engine, launcher, and commons artifacts selected by Maven. CI then runs `clean verify` in Maven offline mode inside a Linux network namespace with no default route, rebuilding and executing the complete suite without provider access. Namespace loopback port `3306` reaches the baseline MySQL service; accelerated runs also bridge the isolated workers' loopback ports `33901`–`33904`. The Actions runner remains online so logs and artifacts can still be uploaded.
- The isolated Maven process receives the job's captured environment through NUL-delimited stdin and `run_with_ci_environment.py` after returning to the runner user. This preserves the JDK 17 selected by `setup-java`, `HOME`, `PATH` and test settings even when `sudo` ignores `-E`. Values are neither shell-evaluated nor placed in command arguments or diagnostic output. An invalid capture or missing `JAVA_HOME` fails before Maven; Maven's exit status still propagates through the verification log pipeline. Python regression tests exercise the actual workflow command with simulated environment loss; Linux namespace execution remains covered by CI.
- Every test capable of contacting OpenAI, Stripe, Stripe CLI, or another live provider must use the test-only `@RealProviderTest` annotation. It assigns the `db-serial` and `real-provider` tags and disables direct IDE/class execution unless the manual Maven profile sets the explicit opt-in property.
- `live-provider-tests` is an explicit human-only Maven profile for tagged provider smoke tests. It is never enabled in CI and requires non-production credentials supplied securely in the local environment. It is not required for normal verification and must not use production user content.
- Test profiles disable automatic Stripe pack synchronization. A test that needs pack synchronization must provide a fake `StripePackSyncService` or explicitly opt into the live-provider lane; a real-looking key must never activate background network access.
- When CI fails, download the `surefire-reports` artifact. It includes Surefire/Failsafe reports, fork dumps, and JaCoCo output, subject to the workflow retention policy.

### CI runner interruption diagnostics

Every CI run that reaches the offline verification step collects runner metrics. For
per-class JVM measurements and timing summaries, select **Collect detailed test JVM
diagnostics and timing summaries** (`resource_diagnostics`) under **Actions → CI → Run workflow**.

During verification, a separate stdlib-only Python process emits a compact
`[ci-resources]` JSON record at startup, once every 60 seconds, and when stopped. It runs
outside the test network namespace, reads Linux counters without elevated privileges, and
does not inspect command arguments, environments, test payloads, or arbitrary process names.
Records include available memory/swap, load and pressure, OOM/swap counters, visible cgroup-v2
memory/PID limits and events, free disk space/inodes, and bounded process RSS/thread summaries.
Compare counters between samples: an existing nonzero `oom_kill` count alone does not prove
that this verification was killed. Summed process RSS can count shared pages more than once.
Missing or unreadable metrics are marked unavailable; v1-only cgroups have no v2 evidence.

The primary evidence is the live Actions log. `quizmaker-resources.jsonl` lives in runner temp
so Maven `clean` cannot remove it, and an independent best-effort `ci-resource-diagnostics`
artifact uses the existing seven-day diagnostic retention. A terminated runner may never
reach artifact upload or the final sample; the last live sample remains useful but cannot
prove a shutdown cause by itself. For example, rising memory use plus a new OOM event supports
memory-pressure investigation; `shutdown signal` without those observations remains unexplained.

The collector uses no subprocesses or Docker queries, scans at most 4096 PIDs per sample,
reports the eight largest RSS entries plus up to 64 Java/MySQL processes with PID, start ticks,
CPU ticks and RSS (including Maven), and records its
own sample wall/CPU time. It waits between samples. Diagnostic failures do not change the
verification result; cleanup allows at most five 100 ms polling intervals before stopping an
unresponsive collector. Test selection, worker settings, offline isolation, coverage thresholds,
Maven exit propagation, and post-merge verification remain the same. Focused checks are:

```bash
python3 -B -m unittest discover -s .github/scripts -p 'test_*.py' -v
python3 -B -m unittest discover -s tools/testing -p 'test_*.py' -v
```

With detailed JVM diagnostics enabled, each Surefire lane also emits `[test-resources]` JSONL from an automatically registered
JUnit extension. Records join by `jvm_id`, PID, lane, fork and `class_id`; class names are
the only test identifiers recorded. `class_start` precedes class setup, and `class_end`
follows class cleanup, including Spring's callbacks. Nested containers get separate windows;
`max_active_classes` includes enclosing/nested and parallel containers. Explicit
`context_close_start/end/failed` markers bracket JPA and JDBC slice context closure.
No contexts are created, beans initialized or GC forced for diagnostics.

JVM snapshots contain heap/non-heap used, committed and maximum bytes, metaspace, direct/mapped
buffers, loaded/unloaded classes, live/daemon/peak threads, cumulative GC count/time, process
CPU time, uptime and Linux RSS/high-water RSS. `-1` means unsupported, including RSS on macOS.
Class-end records include process-wide CPU/GC/memory deltas and sampled peaks. These are
observations of a JVM during a class window: they include other active classes and background
threads. Do not sum overlapping rows, treat heap growth as retained/live memory, or infer an
object leak from one window. RSS includes native memory; the heap limit alone cannot bound it.

A daemon samples once per second and emits every five samples; begin/end/cleanup records
also take snapshots. Delays under load and shorter spikes can be missed. Every emitted record
carries cumulative JVM peaks and up to 16 active class windows with their peaks, so the last
live line is useful when a killed class produces no Surefire XML or `class_end`. It remains a
lower bound, not an exact peak or proof that the active class caused the kill.
A 256-record queue separates test threads from console/file I/O; dropped records and sink
errors are counted, sampling cost is recorded, and shutdown waits at most 500 ms for the daemon
writer. Diagnostic errors do not change test outcomes. SIGKILL cannot run shutdown hooks;
absence of `jvm_end` is evidence of an incomplete stream, not by itself evidence of OOM.

CI stores raw streams and a best-effort `summary.json` in
`$RUNNER_TEMP/quizmaker-test-resources`, alongside host samples in `ci-resource-diagnostics`.
Maven `clean` cannot erase them. For local `context_benchmark.py verify` or `compare`,
pass `--resource-diagnostics`. The `verify` command then writes streams directly to
`<output>/verify/test-resource-evidence`, including evidence from a failed Maven run.
For plain Maven, pass `-Dquizmaker.tests.resources.enabled=true` to write streams to
`target/test-resource-evidence`. To summarize a directory, run

```bash
python3 -B tools/testing/summarize_test_resources.py \
  --directory /path/to/test-resource-evidence --output /tmp/test-resource-summary.json
```

The summary ranks elapsed/CPU time, sampled RSS/heap and heap change at class boundaries, retains
incomplete windows and marks overlap. Correlate a suspect JVM's PID and timestamps with host
`MemAvailable`, pressure, OOM counters and the Java/MySQL process list. Compare several class
boundaries in one worker and multiple runs before choosing a memory or scheduling fix.

### CI JVM memory budget

CI sets `JAVA_TOOL_OPTIONS=-Xms256m -Xmx2g` for forked test JVMs and
`MAVEN_OPTS=-Xms128m -Xmx1g -XshowSettings:vm` for Maven itself. Maven's explicit
command-line heap flags override the inherited JVM flags; `MAVEN_OPTS` alone does not
budget Surefire child JVMs. The safe local launcher reads these same checked-in values
and adds only its existing IPv4 option, so local verification exercises the CI budgets.
Maven prints its effective heap settings at startup; each isolated worker's JSON evidence
records `max_heap_bytes` and `java_version` alongside its PID and database identity.

The four-worker pool permits at most **9 GiB Java heap** concurrently: 1 GiB for Maven
plus 2 GiB per test JVM. The unit lane and the exclusive fixture run in separate phases;
the exclusive JVM does not overlap the four-worker pool. These are heap limits, not RSS
limits: reserve additional memory for metaspace, code caches, stacks, MySQL and the OS.
Test inventory, four-worker scheduling, fixture sizes, context cleanup, offline execution,
merged coverage thresholds, complete verify/package and post-merge CI are preserved.

The first instrumented CI run at `cd61966d` (run `44118311685`) had four CPUs and about
15.6 GiB RAM. Four context JVMs reached sampled RSS peaks of 2.8–3.5 GiB each, Maven
reached about 1.7 GiB, available memory fell to 99 MiB, and only 7 MiB of swap remained.
OOM-kill counters stayed zero; this is evidence of memory pressure, not proof of the earlier
runner shutdown cause or a Java object leak. Closing JPA contexts after each top-level suite
was already enabled. The old local launcher capped JVMs at 2 GiB while CI relied on JVM
ergonomics, allowing each process to size itself independently from the host's memory.

Compare complete runs on the same runner class: exact test identities, coverage checks,
effective worker heap limits, elapsed time, available memory, swap deltas and pressure.
A small heap can increase GC activity or expose an OOM; a green result alone does not
prove adequate headroom. Retain the existing resource diagnostics for that comparison.

Local validation on 17 November 2026 passed the full four-worker clean verify: 9,960 tests,
zero failures/errors/skips, all 9,959 previous test identities retained plus the new budget
contract. Every context/exclusive JVM reported a 2 GiB maximum heap; Maven reported 1 GiB.
Maven elapsed time was 794.95 seconds versus 803.36 seconds for the earlier local run, with
identical application bytecode. A 15-second sampler observed a peak summed Java RSS of
6.80 GiB. This single local comparison is not a CI capacity or throughput guarantee; the
instrumented GitHub runner remains the reference for available-memory/swap measurements.

### Immediate Result Release

Run the focused #556 checks with Java 17 and the existing test MySQL configuration:

```bash
./mvnw test -Dtest=ImmediateResultReleaseMySqlIntegrationTest,PracticeReviewProjectorTest,ParticipantAttemptViewOpenApiContractTest,ParticipantAttemptViewActivationTest
./mvnw test -Dtest=ImmediateResultReleaseMySqlIntegrationTest -Dresult.test.isolation=TRANSACTION_READ_COMMITTED
```

The MySQL class creates its own `immediate_release_<id>` catalog through `TEST_MYSQL_PORT`
and optional `TEST_MYSQL_ADMIN_USERNAME`/`TEST_MYSQL_ADMIN_PASSWORD`. It uses the real internal
writers through publication, start, acceptance, submission, grading and release. No remote or
paid provider is called. Fixture/pool isolation defaults to `REPEATABLE READ`; v2 command/read
owners explicitly use RC. Release retains its independent RC transaction. The second command
also checks pool defaults at RC; a suspended RR caller remains RR.

Tests distinguish a missing result from a committed release, verify structural absence of
protected fields, current RESULT authority, deterministic frozen answer identifiers, concurrent
successful releases, failures after actual writes and commit-response loss. Fresh transactions
inspect rollback, and recreated services reconcile the same durable release identity. Real
HTTP reads cover include flags, wrong participants and lost permission. Multiple parents with
1 versus 20 items are serialized while measuring both JDBC and JPA queries. OpenAPI checks
cover full/grouped schemas, examples, expected errors and exclusive legacy/target envelopes.
The independent-submission race waits after both real missing-publication reads and requires
two successful publications, stable replays and one release event per result. A separate test
observes the publisher's RC transaction, the unchanged server default, and the resumed RR caller
and participant reader, including a genuinely old nonlocking snapshot.

### Documentation-Only Pull Requests

CI inspects the complete PR diff before starting `build-and-test`. It skips that job, including MySQL and Maven, only when every changed path is documentation: root `.md`/`.rst`/`.adoc` files; those formats plus `.txt`, `.png`, `.jpg`, `.jpeg`, `.gif`, `.svg`, `.webp`, and `.pdf` under `docs/`; or Markdown issue/PR templates under `.github/`. Other paths, including application prompts/resources, build files, workflows, and executable files under `docs/`, require the usual tests. Moving code into documentation still requires tests because the old code path is included in the diff.

The workflow still starts and the existing `build-and-test` check is marked skipped, so required checks do not remain pending. An empty diff or a failed change detector cannot disable tests. Pushes to `master`/`main` always run full verification; production deployment still consumes the tested JAR from the successful `master` push. For example, a PR editing only `docs/testing-guide.md` skips the expensive job, while one also editing `pom.xml` runs it.

Run the detector's Git-fixture regression tests without Java or MySQL:

```bash
python3 -m unittest discover -s .github/scripts -p 'test_*.py' -v
```

### Flat Navigation

Run #525 checks with JDK 17 and the existing local test MySQL configuration:

```bash
./mvnw test -Dtest=FlatNavigationTest,NavigationPolicyTest,NavigationOpenApiTest,NavigationActivationTest,NavigationMySqlIntegrationTest
```

`NavigationMySqlIntegrationTest` creates and removes its own `flat_navigation_<id>` catalog using
`TEST_MYSQL_PORT` and the existing optional test-admin credentials. It uses the real publication,
authorized internal start, navigation, answer/draft commands, receipts, transactions and participant
readers. External image storage is a fail-on-use fake for these text-only fixtures; the legacy
delegate is outside this target-flow fixture. No paid/remote provider is needed.

The navigation counter is separate from lifecycle state. Tests must distinguish a successful
cursor move from accepting an answer, and exercise successful controls for answer-before-next,
skip/return, readback and read-only access. The complete skip A → answer B → return A → answer A →
continue C route is checked with accepted revisit both off and on. Hidden accepted neighbours do
not block progress, while available questions cannot be jumped over. Concurrent navigation uses
both MySQL isolation levels;
reached SQL failures and lost commit responses use fresh reads and recreated services. Query
measurements include multiple attempts, different form sizes and participant DTO serialization.
Policy golden fixtures retain the historical FREE bytes/hash. Run `./mvnw verify` after scoped
checks for existing API, training, migration and shared-reader compatibility.

## Unit Tests

### Immutable revision sections (#551)

With JDK 17 and the existing disposable test MySQL configuration, run:

```bash
./mvnw test -Dtest=QuizRevisionSectionsTest,QuizRevisionStructureServiceTest,ParticipantFormProjectorTest
./mvnw test -Dtest=QuizRevisionSectionsMySqlIntegrationTest,QuizRevisionMySqlIntegrationTest,QuizRevisionAuthoringMySqlIntegrationTest,FixedFormMaterializerMySqlIntegrationTest
```

The new MySQL suite creates/drops only its own generated `revision_sections_<id>` catalog. It
migrates through V109, inserts a real flat revision, then applies V110 and checks repeat migration
without backfill. Real repository/adapter calls cover exact structured round trips, a later
revision with edited section metadata, wrong-owner absence, legacy classification without SQL,
immutable database guards, foreign membership, and incomplete-evidence rejection. Faults occur
after actual section/member inserts; fresh transactions verify whole-aggregate rollback before
the same input succeeds. Two independent publication contenders synchronize before the root
insert and require one successful complete winner. RC/RR old-snapshot fixtures use committed
worker writes and real current reads. These are internal storage/adapter proofs, not author HTTP
or section-runtime conformance.

Real JDBC section-read faults also cross the actual service/repository transaction proxies with
transaction logging enabled at TRACE. Single/batch regressions require bounded outward failures
and no driver message, SQL, nested cause or suppressed diagnostic in transaction logs. They prove
rollback-only after a caught read failure, fresh-transaction absence of writes made before/after
that failure, and successful recovery through the same read path. Sanitization must occur inside
the repository, before its transaction interceptor can log the exception; a mock repository does
not establish this boundary.

The real flat materializer, baseline response/scoring contracts and persistence provide a
successful control; the same flat command shape cannot reinterpret a structured revision or
persist a partial form. Five revisions with multiple sections are read and serialized while
counting physical JDBC statements: five per structured batch, three for flat-only reads. MySQL
`EXPLAIN` verifies indexed parent/membership lookup. No JPA associations or external providers
are substituted in these new tests. Run the full `./mvnw verify` gate for existing API, training,
publication, authorization and OpenAPI compatibility before handoff.

### Immutable revision cases (#552)

With the existing offline fake environment, JDK 17 and disposable test MySQL:

```bash
./mvnw test -Dtest=QuizRevisionCasesTest,QuizRevisionCaseServiceTest,QuizRevisionSectionsTest,QuizRevisionStructureServiceTest
./mvnw test -Dtest=QuizRevisionCasesMySqlIntegrationTest,QuizRevisionSectionsMySqlIntegrationTest,QuizRevisionMySqlIntegrationTest,QuizRevisionAuthoringMySqlIntegrationTest,FixedFormMaterializerMySqlIntegrationTest
```

The case suite uses its own generated `revision_cases_<id>` catalog. It first persists real
format-1/2 revisions at V110, then upgrades to V111 without changing those rows or inventing
cases. It asserts exact case/stimulus-reference/member/atomicity round trips, read-only replay,
later revision B with a retained FK reference to A, fresh-transaction/restarted-component reads,
ownership denial, database immutability and invalid/missing/foreign/overlapping membership.
Administrator-created partial rows test corruption refusal separately from real publication.

Write faults occur after real case/member inserts and assert the checkpoint was reached.
Fresh transactions check absence of roots, sections, cases, members and newly inserted question
evidence before a successful retry. A consumer-reference write and subsequent outer failure
prove shared transaction rollback. Concurrent contenders synchronize before the real root
insert, require a complete successful winner and replay it after replacing the components.
RC/RR fixtures compare current shared reads with an older nonlocking snapshot. Actual nested
transaction proxies with TRACE enabled must not expose injected private JDBC diagnostics.

The measured read fixture serializes five parents with one and twelve cases per section:
seven statements per case-bearing batch, with indexed parent lookups; flat/section-only controls
remain three/five. The real existing flat materializer has a successful flat control and must
reject a case-bearing revision without writing a partial form. The case suite passes on both
the CI MySQL 8.4 line and the Compose MySQL 8.0 line. This is JDBC persistence evidence, not JPA association loading or proof of
participant delivery. Stimulus IDs are fixture references; no real content-source writer,
attempt start, section runtime or external provider is simulated as an integrated capability.
The separately owned temporary upgrade catalog exercises atomic refusal of invalid existing
version data and explicit test-fixture correction/Flyway repair before a successful retry;
it does not provide automatic production migration recovery.

Run the complete `verify` gate using the established launcher below before handoff. The new
MySQL suite is registered as a database companion in the existing context manifest.

Use real input values and narrow collaborator doubles. A unit test should describe a rule in observable terms, for example:

```java
@Test
void calculateSchedule_whenGradeIsAgain_resetsRepetitionAndUsesMinimumEase() {
    // Given
    // When
    // Then
}
```

- Cover the happy path, validation boundary, and important failure semantics.
- Use parameterized tests for a compact matrix of equivalent rules or boundary values.
- Use builders or small factory methods for readable fixture creation; keep fixtures local unless they are genuinely shared.
- Mock only a real collaborator boundary. Do not invent impossible collaborator output merely to reach defensive code.
- Inject a fixed `Clock` rather than using sleeps or timing-sensitive assertions.

## MVC And API Contract Tests

Use `@WebMvcTest` or standalone MockMvc when testing controller behavior.

- Verify status, response DTO shape, validation errors, and relevant `ProblemDetail` fields.
- Include `.with(csrf())` for state-changing routes when CSRF is enabled for that test path.
- Use `@WithMockUser` or the project security test support for protected endpoints.
- Assert negative authorization paths where applicable: unauthenticated, missing permission, wrong owner, wrong organization, and private resource.
- Mock service interfaces, not their implementations. Controller tests are not service tests.

## Repository And Migration Tests

Use a real test database for custom queries and persistence invariants.

- Assert owner/tenant scoping, pagination ordering, soft-delete filtering, and null behavior.
- Test unique constraints, optimistic locking, and migration-backed invariants when they protect data integrity.
- Test fetch plans where an endpoint needs relationships. Use multiple parent rows and observe generated SQL or assert a bounded query count; the count must not increase linearly with parent count. Avoid claiming that N+1 is fixed from a functional assertion or annotation alone.
- Prefer deterministic fixtures and explicit ordering. Tests must not depend on execution order or left-over data.

The focused contract, fixed-form, and policy-snapshot MySQL suites migrate their owned tables once in `@BeforeAll` and drop them in `@AfterAll`. Before and after each scenario they clear committed rows in foreign-key order, restore legacy fixture markers, and reset fault hooks and SQL counters. Keep foreign keys enabled and preserve the scenarios' real JDBC/JPA transactions; an outer test rollback is not a substitute for this cleanup. Rollback assertions must inspect durable state before cleanup runs.

`QuestionRevisionContractsMigrationMySqlIntegrationTest` owns the scenario that deliberately reapplies a migration. Include it when running the contract suite. Snapshot corruption scenarios restore both the original value and CHECK enforcement in `finally`; cleanup verifies that every migrated CHECK is present with its original enforcement state.

```bash
./mvnw test '-Dtest=QuestionRevisionContracts*MySqlIntegrationTest,FixedFormMySqlIntegrationTest,PolicySnapshotMySqlIntegrationTest'
```

Before task handoff, repeat this N+1 check for every touched JPA-backed list, aggregate, mapper, and serialization path. Record the command/test and result, or state that N+1 is not applicable because no relationship-loading read path changed.

Immutable publication has a pure canonical-content suite and a real MySQL composition suite:

```bash
./mvnw test -Dtest=PublicationContentTest,QuizPublicationMySqlIntegrationTest
```

The MySQL suite uses `TEST_MYSQL_PORT` and optional `TEST_MYSQL_ADMIN_USERNAME` /
`TEST_MYSQL_ADMIN_PASSWORD` to create an exclusive `publication_<id>` catalog, applies the full
Flyway history and drops only that catalog after the class. It stays in the serial DB lane.
Internal authoring, retained-image, publication and fixed-form services are real; only object
storage is fake. Publication SQL faults occur after verified writes and assertions inspect
committed state before cleanup. Query counters include JDBC and JPA calls through full content
serialization and compare multiple questions/images, not just a single-parent fixture.
Its connection default is `REPEATABLE_READ`; a barrier after the receipt read lets the real
question writer commit a new image before publication locks the question. Assertions require
the new content and its exact retained binding to publish together. Source-authority regressions
exercise the real association/removal commands and pre-fix foreign memberships, before/after
library removal, alongside successful own-source and moderation controls. Detaching or
soft-deleting the original quiz must not grant the remaining foreign association publication.
A barrier after locking two different target quizzes reproduces the former shared-question
deadlock; both publications must commit and replay, with intact retained media.
The association regression also measures five versus ten actor roles and revokes source-question
administration while an OSIV role graph remains cached; the subsequent association must fail.
Legacy-confirmation tests exercise actual current permissions, atomic evidence writes, injected
post-write failure, idempotent confirmation, immutable ownership and reuse after library removal.
`QuestionSourceMigrationMySqlIntegrationTest` upgrades a pre-V94 row without inventing its owner
under a schema-scoped migration user without `SUPER`, and verifies the composite foreign-key
immutability constraint. A lone owner value without its authority row must not grant publication.
Import and generation tests cover trusted
new-source attribution and preservation of unknown legacy authority on import updates.

## Integration Tests

Flat attempt delivery has a focused real-MySQL composition suite and separate HTTP/activation checks:

```bash
./mvnw test -Dtest=AttemptDeliveryMySqlIntegrationTest,AttemptFormOpenApiContractTest,AttemptFormActivationTest,AttemptAccessServiceTest,AttemptAccessMySqlIntegrationTest
```

`AttemptDeliveryMySqlIntegrationTest` uses a generated `attempt_delivery_<id>` catalog via
`TEST_MYSQL_PORT` and the same optional administrative credentials as the publication suite.
It migrates the full schema, invokes real publication/binding/start/access/read services and
drops only its own catalog. Object storage is fake. Persisted-state comparisons surround reads
and injected SQL failures; a lock-wait assertion proves that permission revocation waits for
actual content projection. SQL counts cover multiple parents and complete form serialization.
The HTTP suite checks the real security chain; activation tests keep the new route absent by
default. These tests do not claim answer submission, navigation, completion-command or #609
activation conformance.

Canonical participant views (#526) add the following scoped verification:

```bash
./mvnw test -Dtest=ParticipantAttemptViewMySqlIntegrationTest,ParticipantViewLegacyHttpIntegrationTest,ParticipantAttemptViewOpenApiContractTest,ParticipantAttemptViewActivationTest,FamilyContractV1Test,ParticipantFormProjectorTest,AttemptDeliveryMySqlIntegrationTest,AttemptFormOpenApiContractTest,AttemptFormActivationTest
```

The new view suites use generated `attempt_view_<id>` and `legacy_view_<id>` MySQL catalogs
with Flyway and the same `TEST_MYSQL_PORT`/administrative credential convention. They exercise
actual target start/read and legacy writer/mapper/HTTP paths, access revocation, repeatable-read
snapshots older than publication/start, concurrent reads, rollback of a caller write, and
non-enumerating failures. Bounded-query assertions include serialization for three parents at
one and twenty children. `PARTICIPANT-VIEW-MEDIA` explicitly uses persisted canonical fixtures
and typed disclosure/media reader doubles: it proves projection of available/unavailable/restricted
images after service recreation, not completed-review release, a real storage backend, media
start or timing compensation. Full/grouped OpenAPI validates both legacy and target examples;
v1 stays on its legacy reader with the participant-view flag absent, false or true, while v2
requires the flag. Run `./mvnw verify` after these checks for API/family-contract regressions;
real providers remain excluded.

Reserve integration tests for behavior that crosses real boundaries: service transactions, `AFTER_COMMIT` listeners, migration wiring, security configuration, serialization, or asynchronous job orchestration.

Immutable response acceptance (#543) has a focused suite:

```bash
./mvnw test -Dtest=StableResponseNormalizerTest,ResponseAcceptanceMySqlIntegrationTest,ResponseAcceptanceOpenApiTest,ResponseAcceptanceActivationTest
```

The MySQL class creates an exclusive `attempt_acceptance_<id>` catalog through `TEST_MYSQL_PORT`
and the existing optional administrative credentials, applies all migrations, and drops only
that catalog. Publication, contract binding, start, access, acceptance, lifecycle, trace and
receipt services are real; unexpected object-storage calls fail the fixture. Five families run
with authored and shuffled option order. Independent transactions at READ_COMMITTED require
an actual acceptance winner, one immutable row/event/receipt, and durable replay through recreated
services. RC callers observe the committed lifecycle version; RR callers reject before work. Faults after each real authoritative write and before/after
the actual commit are followed by fresh reads and successful same-command recovery.
Nested question-revision reads also preserve conflict classification: a real two-connection
InnoDB deadlock with an RC command and a READ_COMMITTED or REPEATABLE_READ blocker must return `RETRYABLE`, roll back the
reservation and all target state, and allow the same command to accept once after the competing
transaction releases its locks. Injected 1205/connection-failure controls retain `IN_PROGRESS`/
`UNAVAILABLE`; ordinary delivery keeps its existing `BUSY`/`UNAVAILABLE` read responses.

Negative controls cover current subject/permission/editor/account checks, foreign real form
items, invalid option IDs, stale versions, terminal state and unknown saved contracts. Future
contract fixtures keep foreign keys enabled and consistent; terminal fixtures do not claim the
future #529 writer. Receipt expiry/cleanup must retain the accepted row. HTTP-to-MySQL tests
exercise real acceptance and replay; the separate MVC/OpenAPI suite verifies the security chain,
strict request parsing, error mapping, named five-family schemas, valid examples, one logical
group, API-summary discovery and default-off activation. Unicode tests distinguish character
and UTF-8 byte bounds. SQL counters compare three parents with one versus twenty items,
including real JDBC/JPA readers and complete participant projection.

Run `./mvnw verify` for compatibility after this suite. In particular, retained legacy answer and
practice-feedback behavior, participant view/delivery readers and frozen family scoring remain
covered by their existing suites. These checks do not prove grading/result, terminal submission,
editable finalization, batch acceptance or #609 activation, which retain their separate owners.

Reuse the Spring context within a class when scenario isolation does not require a restart. `QuestionSoftDeleteTest` and `BalanceEndpointIntegrationTest` retain their test-managed rollback and verify fixture removal in `@AfterTransaction`. `AttemptControllerCurrentQuestionIntegrationTest` and `QuizControllerCachingAndRateLimitTest` retain committed fixtures and real request transactions; they physically remove only their own rows after each scenario, with foreign keys enabled. Rate-limit scenarios use a distinct client IP per scenario and keep one IP for every request within the 120/121 boundary check. ETag scenarios retain direct requests without forwarded headers. These classes still close their contexts after the class and run in the serial database lane.

Avoid adding `@Nested` containers beneath a class-level `@DirtiesContext` solely for display grouping: inherited context invalidation can cause a restart after each nested container. Balance endpoint scenarios use grouped display names in one class instead. Do not globally remove `@DirtiesContext` from shared integration bases, since other suites change the shared schema or bean state.

- Keep each scenario narrow and name the cross-layer contract it verifies.
- For asynchronous behavior, invoke a synchronous seam, use a controlled executor, or wait on a deterministic signal. Do not use arbitrary sleeps.
- Verify idempotency, retries, and rollback/partial-failure behavior where the feature uses them.

### Automatic result facts (#603)

```bash
./mvnw test -Dtest=AutomaticResultTest,AutomaticResultMySqlIntegrationTest
./mvnw test -Dtest=AutomaticResultMySqlIntegrationTest -Dresult.test.isolation=TRANSACTION_READ_COMMITTED
```

Use the existing test MySQL configuration (`TEST_MYSQL_PORT` and optional administrative
credentials). The integration suite creates and drops its own `automatic_result_<id>` catalog,
keeps foreign keys enabled and runs actual publication, contract binding, start, response
acceptance and terminal submission before grading. Only external image storage is a fake.
Pool/fixture default is explicitly `REPEATABLE READ`; v2 owners use RC. The second command
covers a READ_COMMITTED pool default. Grading stays independent of suspended caller rollback.

Coverage includes correct/incorrect golden inputs for all five families, MCQ_MULTI all-or-nothing
scoring, unanswered items, HALF_UP normalization and exact pass boundaries, immutable provenance,
author edits, receipt cleanup, missing deployed scorers through the real registry, corrupt scope/
counts/payloads, unsupported stored grading modes, real uniqueness/CHECK/FK constraints, concurrent
successful consumers and restart recovery. Reached-write faults after result, grades, trace and
outbox inserts prove rollback through fresh transactions. A 501-item fixture proves rollback after
the first real 500-grade batch. Lost commit acknowledgements are reconciled with the same retained
submission ID. Query counters compare three parents with five versus twenty-five items for both
creation and replay, including actual JDBC/JPA composition.
The four result/grade/trace/outbox write-fault scenarios explicitly capture `TransactionTemplate`
DEBUG logs, including throwable causes and suppressed exceptions. They require actual rollback
diagnostics and reject a synthetic private driver marker, while retaining reached-write, fresh-read
rollback and successful-retry assertions. Direct trace tests additionally exercise SQL, known-domain
and unexpected-runtime failures with sensitive nested diagnostics in a participating transaction.
Read-fault coverage exercises lifecycle `lock`, policy `readCurrent`, contract `declareScope` and
`resolve`, plus direct accepted-response reads as a protected control. Each point runs SQL codes
`0`, `1213` and `1205`: fifteen scenarios verify sanitized DEBUG diagnostics, preserved failure/
conflict classification, actual rolled-back completion and one successful same-submission retry.
Well-shaped impossible option/gap references fail closed; positive controls use real accepted
empty MCQ_MULTI/ORDERING/FILL_GAP arrays and preserve their zero-credit accepted identity.

No public endpoint, release, notification, scheduler or offline activation is claimed. Run the
full `./mvnw verify` gate for existing API/training/OpenAPI compatibility after focused checks.

## Separate attempt API versions (#904)

The legacy `AttemptController` owns `/api/v1/attempts` regardless of target flags.
`ParticipantAttemptViewController`, form, response, submission, navigation and draft
controllers own `/api/v2/attempts` and retain their existing feature gates. The public
v2 start belongs to #609 and is not introduced here. The integrated #629 batch uses
`POST /api/v2/attempts/{attemptId}/responses/batch` behind its independent gate.

Verify both full and grouped OpenAPI: legacy responses are `AttemptDetailsDto` or
`AttemptReviewDto`; target views are `ParticipantAttemptView`. `attempts` and `attempts-v2`
are disjoint groups, both listed by `/api/v1/api-summary`. HTTP version 2 is independent
of persisted schema version 1. Include flags never expand target disclosure.

```bash
./mvnw test -Dtest=ParticipantAttemptViewActivationTest,ParticipantAttemptViewOpenApiContractTest,AttemptFormOpenApiContractTest,ResponseAcceptanceOpenApiTest,AttemptSubmissionOpenApiTest,NavigationOpenApiTest,DraftCommandOpenApiTest,ApiDocumentationTest,ApiDocumentationServiceTest
./mvnw test -Dtest=ParticipantViewLegacyHttpIntegrationTest,ParticipantAttemptViewMySqlIntegrationTest,AttemptDeliveryMySqlIntegrationTest,ResponseAcceptanceMySqlIntegrationTest,AttemptSubmissionMySqlIntegrationTest,NavigationMySqlIntegrationTest,DraftCommandMySqlIntegrationTest,ImmediateResultReleaseMySqlIntegrationTest
```

Keep the real legacy all-family/mode/read/review fixtures and their SQL bounds. The target
fixtures retain concurrent access, revocation, rollback, restart and released content checks.
Target transaction composition now follows #905: reject incompatible old RR before work and
verify participating rollback under RC. Legacy RR fixtures remain unchanged. Reject legacy IDs
at v2 without fallback, and prove v1 still returns its own successful envelopes with target enabled.
Wrong-version detail/review/answer-key failures must mark a compatible joined caller transaction
rollback-only even when caught; check the rollback of an unrelated caller write under RC.
Scoped tests precede full `verify`; the isolated context worker launcher and post-merge
verification remain unchanged.

The checked frontend attempt endpoints still use legacy start/answers/complete and
v1 reads. On 13 November 2026 the owner confirmed that no clients/environments consume
the target v1 routes, so #904 introduces no temporary aliases. A future v2 frontend must
retain each attempt's engine/version and must not retry a failed v2 command through v1.

## Question-contract lock intent (#906 phase A)

Use the default `QuestionRevisionContractService.declareScope(owner, revisions)` for READ.
Any composition that will bind must pass `ContractScopeIntent.WRITE` on its first declaration,
before resolving a subset. A read-write transaction is not evidence of binding intent.
Tests deliberately blocking participants behind a binder must declare WRITE explicitly.
Do not silently replace a binding race with concurrent shared reads.

`ResponseAcceptanceMySqlIntegrationTest` holds a real answer transaction open while a different
authorized user answers, reads a form, or starts on the **same** revision. These fixtures set and
assert actual RC on their own connections; no application default is changed. The start fixture
has no pre-existing active attempt for the second user. Require the second operation to commit
before releasing the first and inspect durable data from a fresh transaction. The held reader's
question-record locks are inspected through `ENGINE_TRANSACTION_ID` and `innodb_trx`, not the
lock structure creator's `THREAD_ID`. Current eligibility is owner/full editor, not general students.

`QuestionRevisionContractsMySqlIntegrationTest` keeps the JDBC/JPA and RC/RR matrices for
canonical order, exact bindings, owner scope, retries and rollback. It additionally rejects
READ-to-WRITE upgrades/direct bind, including empty revisions, and proves that savepoints cannot
erase the terminal violation and REQUIRES_NEW restores the outer READ intent. A binder still
waits for an active shared reader. Existing bounded metadata/query and multi-parent response
tests remain; identical query shapes select the same columns in the same batch sizes.

The current v2 view/start timing regression uses RC, with read-only/read-write and timed/untimed
variants. The view must project while start is paused with shared question locks; start must
commit while the completed view transaction is still open. Unsupported outer isolation is
rejected at the #905 boundary. Do not restore the obsolete successful v2 RR variants; retain
RR cases for the separate canonical binding and legacy contracts.
Form replay tests synchronize at the unique form-root insert, where contenders now serialize,
instead of expecting participant question scopes to take an exclusive lock.

```bash
./mvnw test -Dtest=QuestionRevisionContractServiceTest,QuestionRevisionContractsMySqlIntegrationTest,QuestionRevisionContractsMigrationMySqlIntegrationTest,PublishedFamilyContractsMySqlIntegrationTest,ResponseAcceptanceMySqlIntegrationTest,AttemptStartMySqlIntegrationTest,FixedFormMaterializerMySqlIntegrationTest,FormOptionOrderMySqlIntegrationTest,ParticipantAttemptViewMySqlIntegrationTest
```

Run full `./mvnw verify` after the scoped suites; keep post-merge verification. Phase A is not
an RR gap-lock fix. #905 narrows target composition to RC with explicit rejection tests;
phase B supplies the immutable-content query simplification below. Legacy RR remains unchanged.

## Immutable RC reads (#906 phase B)

Form/option creation and materialization own or join writable RC. The three focused form suites
retain same-ID winner/conflict, empty forms, reached-write failures, outer JDBC/JPA rollback,
restart and owner-scope checks. Successful old-RR write composition is replaced by early refusal
for DEFAULT-on-RR, RR, RU, SERIALIZABLE and read-only writes. Do not weaken barriers or remove
business variants when updating the isolation dimension. #598 binding/publication storage keeps
its independent canonical-scope and RR tests.

The response suite holds Anna's real response transaction open while Boris's **standalone**
answer/form/start completes on the same revision, letting #905 choose RC on the actual default
connection. Additional real aggregate writers use adjacent form/revision/item UUIDs. Fresh reads
prove their commit before releasing Anna. The empty-form concurrency fixture keeps child tables
empty, without artificial gap anchors. Run the independence methods separately on dedicated
MySQL 8.0 and 8.4 servers with RR and RC defaults; never change GLOBAL isolation in a shared worker.

Inspect record locks through `ENGINE_TRANSACTION_ID` joined to `innodb_trx`, not the creator's
`THREAD_ID`. Before the change, a five-family answer held 44 record locks on form-item/order/option
and question-option tables. Afterward only the two exact item locks required by trace/response
FKs remain. This is a lock-footprint measurement, not proof of zero contention elsewhere.
Authorization, lifecycle, same-key receipt and canonical binding locks are expected.

`immutableReadCostRemainsBatchedAndRecordsRowsAndBytes` records SELECT counts plus server-side
`SUM_ROWS_SENT` statement-summary and `Bytes_sent` deltas from the command's connection (observed on a separate admin
connection, with no payload logging). On local MySQL 8.4.11, one answer with 1/20 MCQ items
used 36/36 application SELECTs, returned 40/249 rows and sent 33,606/51,770 bytes. These are
fixture measurements, not production capacity estimates. Keep multi-parent bounded-query tests for form/order,
revision, delivery/view, navigation and draft reads. Content volume still grows with quiz size;
this change removes lock clauses, not whole-quiz loading or duplicated authorization.

```bash
./mvnw test -Dtest=FixedFormMySqlIntegrationTest,FormOptionOrderMySqlIntegrationTest,FixedFormMaterializerMySqlIntegrationTest,ResponseAcceptanceMySqlIntegrationTest
./mvnw test -Dtest=QuestionRevisionContractsMySqlIntegrationTest,PublishedFamilyContractsMySqlIntegrationTest,QuizPublicationMySqlIntegrationTest,QuizRevisionMySqlIntegrationTest,QuizRevisionSectionsMySqlIntegrationTest,QuizRevisionCasesMySqlIntegrationTest
./mvnw test -Dtest=AttemptStartMySqlIntegrationTest,AttemptDeliveryMySqlIntegrationTest,ParticipantAttemptViewMySqlIntegrationTest,DraftCommandMySqlIntegrationTest,NavigationMySqlIntegrationTest,AttemptSubmissionMySqlIntegrationTest,AutomaticResultMySqlIntegrationTest,ImmediateResultReleaseMySqlIntegrationTest
./mvnw verify
```

Corrupt/incomplete/mixed content must fail closed. Author edits/new publication must leave old
form content unchanged, and reached failures must roll back form/order/response/trace/receipt
writes. Repository errors must be sanitized before their transaction interceptor logs them,
including the ordinary read entry points. Full verify and post-merge CI/CD gates remain required.

## External Systems And AI

Automated tests must never call real OpenAI, Stripe, email, storage, transcription, or other paid/remote services.

- Define project-owned ports/interfaces around providers.
- Use fakes or stubs that model valid provider responses and meaningful failures.
- Mark deliberate human-run provider smoke tests with `@RealProviderTest`; do not rely on filenames, environment-variable assumptions, or conditional skips to keep them out of default verification.
- Validate AI payloads at the structured-client or parser boundary.
- Test the application service only with collaborator output that the real collaborator can validly produce.
- Treat prompts, schemas, examples, and backward-compatible payload variants as contract assets; test them directly when they matter to generation.

## OpenAPI, Compatibility, And Security

For a changed public contract, add focused tests for:

- the endpoint's OpenAPI group and `/api/v1/api-summary` discovery;
- named request/response schemas, examples, enums, and expected RFC 7807 errors;
- request validation and authentication/permission behavior;
- old payloads or persisted data that must remain supported;
- the changed payload or response fields that the frontend relies on.

## Naming And Structure

Use names that identify the use case and observable outcome:

```text
methodOrRoute_whenCondition_thenExpectedOutcome
```

Examples:

```text
createQuiz_whenTitleIsBlank_thenReturnsBadRequest
parseFillGap_whenOptionsAreMissing_thenPreservesLegacyTypingMode
findDueEntries_whenEntryBelongsToAnotherUser_thenDoesNotReturnIt
```

Use Given/When/Then sections where they make a test easier to read. One test may have several assertions when they prove the same outcome.

## Commands

Use JDK 17 and run Maven commands from the repository root.

### Local feedback loop

Run the narrowest relevant tests while implementing a change:

```bash
./mvnw test -Dtest=PolicySnapshotTest
./mvnw test -Dtest=PolicySnapshotTest,PolicySnapshotMySqlIntegrationTest
```

Choose tests by changed behavior and affected callers. Changes to shared
security, transactions, configuration or migrations require broader checks
than changes confined to one feature.

Do not run `clean` after every small edit. Use it for final clean verification
or when stale compiled/generated classes are suspected. A focused run is not
a full-suite verification.

### Full verification

Run full verification on the completed change:

```bash
./mvnw clean verify
git diff --check
```

CI continues to run the complete offline `clean verify` gate for code changes
in pull requests and for pushes to `master`/`main`. Existing documentation-only
PR classification remains unchanged.

### Timing diagnostics

To generate a local timing summary, preserve Maven's failure status through
the logging pipeline:

```bash
set -o pipefail
./mvnw -B -Dorg.slf4j.simpleLogger.showDateTime=true clean verify \
  2>&1 | tee /tmp/quizmaker-verify.log
```

Then render the summary:

```bash
python3 -B .github/scripts/summarize_tests.py /tmp/quizmaker-verify.log
```

The summary describes one Maven invocation and is diagnostic only. Missing
or partial timing data is not a passing test result.

SQL and bind-value logging are quiet by default. Enable SQL logging only for
a focused diagnostic run:

```bash
./mvnw test -Dtest=UserRepositoryTest \
  -Dlogging.level.org.hibernate.SQL=DEBUG
```

In CI, a new run for the same PR cancels its outdated run. Push-triggered
verification runs have independent concurrency keys and are not cancelled
by this PR optimization.

### Manual provider smoke tests

Run the Maven command from the repository root. Use the live-provider profile only for a deliberate, owner-run smoke test after securely configuring test-mode provider credentials:

```bash
./mvnw -Plive-provider-tests test -Dtest=RealAiQuizGenerationIntegrationTest
./mvnw -Plive-provider-tests test -Dtest=RealStripeApiIntegrationTest
./mvnw -Plive-provider-tests test -Dtest=RealAwsSesE2ETest
```

Never use production Stripe credentials, production OpenAI data, or a production database for these smoke tests. Without `-Plive-provider-tests`, provider suites must be excluded rather than reported as skipped.

Report what ran, what did not run, and any residual risk. A passing test that asserts the wrong thing is not useful coverage.

### Atomic canonical-start allowance (#549)

With JDK 17 and the existing local test MySQL configuration, run:

```bash
./mvnw test -Dtest=AttemptLimitContractTest,AttemptStartMySqlIntegrationTest,AttemptSubmissionMySqlIntegrationTest
```

The start suite uses its exclusive generated catalog and actual canonical publication, receipt,
form, policy, subject, lifecycle, trace and allowance writers. It checks identical/different-key
races at RC, unsupported caller isolation rejection, terminal counting, immutable limits, exact cooldown,
real InnoDB lock timeout, classified storage faults, reached-write rollback, restart/commit
reconciliation and receipt-cleanup independence. The submission suite composes the real #529
submission command with the gate: terminal submission permits a subsequent unlimited/remaining
start, while an exhausted finite allowance remains exhausted. Start-only terminal fixtures use
the real lifecycle writer and do not claim full submission integration on their own.

The V103 SQL fixture recreates only the new tables in its exclusive test catalog after producing
real canonical roots, then proves exact pre-extension binding migration. An unclassified root is
separately denied without inventing publication-version history. Entry-reader doubles supply
versioned permitted/closed/unknown facts only, with a successful real-start control. They do not
prove #836 examination finality or #534 formal assignment behavior.

Existing multi-parent start fixtures measure SQL SELECT counts for 5/10/20-item banks and retained
receipt replay, including new bulk allowance reads; counts must remain constant. Separate races
require two distinct publication guards to be reached together and both starts to commit, with
and without prior history. Historical 1/3/5-start fixtures independently measure bounded bulk
reads. RC fixtures verify current terminal/count evidence after intervening commits.
`AttemptLimitContractTest` plus existing policy golden fixtures check the original unlimited
canonical bytes/hash, exact finite/duration round trips and forbidden participant overrides.
The allowance review regressions also cover empty and partially missing directories with real
retained bindings/lifecycle, finite and unlimited policies, and commits newer than an earlier
RC read. Old-RR callers fail before work. A cardinality mismatch must return ELIGIBILITY_UNAVAILABLE
without a second committed attempt or automatic repair. Restoring the fixture's directory must
restore ordinary active/limit denial or permit a genuinely eligible unlimited start.

TRACE capture targets the actual TransactionInterceptor logger. Faults at scope creation,
history reads, binding insert, directory update and the authoritative census include private
markers in the SQL exception/cause/suppressed/next-exception chain. Tests require a reached fault,
safe timeout/deadlock/storage classification, no marker in transaction messages/throwables,
fresh-transaction rollback assertions and successful retry. A plain unit test separately proves
that removing the repository's transactional proxy cannot permit autonomous JDBC access.

No public canonical-start endpoint is added; the full verification suite retains legacy HTTP,
participant-view, security and OpenAPI compatibility coverage. Run `./mvnw verify` before handoff.

## Isolated context workers (#897)

The optional `context-forks` profile distributes active Spring-context suites and
standalone database companions across reusable JVMs. Each pool JVM has its own MySQL server
on loopback port `33901`–`33904`. Classes remain sequential inside that JVM; threads and
transactions created by individual concurrency tests are unchanged. The default Maven
commands still use the existing fast and shared-database serial executions.

One resource-sensitive suite, `LegacyAnswerMembershipMySqlIntegrationTest`, runs in a
separate JVM after the pool finishes, reusing the first isolated MySQL server. The pool
contains the remaining manifest-selected suites; the exclusive execution retains all 38 scenarios, including concurrent
participants, the 2,048-answer fixture and rollback checks. This avoids other suites competing
for CPU during the large write-cost scenario. Scheduling alone is insufficient: a later
exclusive run committed 2,048 peer rows in 23.3 seconds, beyond the old 20-second Future budget.
#913 retains the three-second peer DB lock budget, uses a 120-second work/cleanup watchdog
and a 180-second whole-scenario watchdog, and records total time without a 60-second throughput
assertion. These are hang safeguards, not performance promises. Fresh observers must still
prove peer commit before reader release; timeout/cleanup cannot convert failure to success.
The original 38 cases remain, plus a real-lock negative control that must fail with MySQL 1205.
This scheduling exception does not serialize the test's own participants.

Within the opt-in profile, `ContextDatabaseLifecycle` closes cached JPA contexts after
each top-level suite, after transaction cleanup. A cached context must not survive while
a different create-drop context deletes its schema. Top-level `@JdbcTest` suites also close
their contexts to release their Hikari pools. Nested tests finish before this cleanup;
MVC contexts without JPA and the ordinary Maven execution retain their existing lifecycle.

`tools/testing/context-test-manifest.json` records context suites, database companions,
existing exclusions, and classes outside Surefire's default naming patterns. Its active
selection is mirrored by `context-suites.txt` for Surefire. `context-excludes.txt` excludes
both enclosing and nested discovery roots from the fast execution. `ContextTestManifestTest`
reconciles all lists against compiled inherited/meta/nested annotations and programmatic
contexts. Update the lists when adding or renaming a context/database suite. Ordinary
unit tests continue to enter the default selection automatically. Excluded provider suites
and the two existing `*IT` discovery gaps are not enabled by this profile.

The manifest's `exclusive_contexts` subset is mirrored by `context-exclusive-suites.txt`
and `context-exclusive-excludes.txt`: the selected classes run only after the pool.
Separate reports, assignment records, and `jacoco-context/exclusive.exec` are mandatory;
the evidence gate rejects missing or overlapping assignments and missing coverage.

Read `BaseIntegrationTest`, `DatabaseTestExecutionTagTest`, and
`MavenVerificationContractTest` before changing test isolation. Do not remove transaction
annotations, cleanup, or `@DirtiesContext` to improve timings. For example, a change to
Anna's attempt completion must still prove that Boris can write independently and that caller
rollback undoes every participating write. Preserve legacy old-REPEATABLE_READ tests; v2 has
explicit RC participation and unsupported-isolation refusal tests.

### Writing suites that remain reliable with multiple workers

Surefire assigns whole classes dynamically to available JVMs; there is no fixed class-to-worker
mapping. Each JVM runs its classes sequentially, but the predecessor of a class can change.
More workers also change CPU scheduling inside a test's own executors. A locally green run
does not prove independence from either class order or thread timing.

- **Own schema preparation.** Every class must run on an empty disposable server and after
  another class has closed a `create-drop` context. Do not rely on a predecessor's Flyway
  tables or on Hibernate `update` preserving non-entity tables, triggers and constraints.
  Tests needing these objects should use a generated, class-owned catalog with Flyway and
  Hibernate `validate`. Read `NormalizedDocumentOwnershipMySqlIntegrationTest` for dynamic
  datasource wiring and pool-close-before-drop cleanup. Always honor `TEST_MYSQL_PORT`;
  hard-coded port `3306` bypasses worker isolation. Never drop a shared catalog.
- **Prepare every selected scenario.** Apply required migrations in class setup, not in the
  first ordered test. For upgrade tests, capture pre-upgrade rows before migrating and keep
  assertions about preserved rows and repeated migration. `AssignmentSchemaMySqlIntegrationTest`
  demonstrates this while allowing rollback and concurrency methods to run independently.
- **Synchronize the state the assertion requires.** A Mockito invocation count proves that
  a method was entered, not that it returned, read a row, acquired a lock or committed.
  Place bounded latches/hooks after the real relevant event; delegate to the real method
  rather than manufacturing its result. For database lock claims, observe the lock/wait
  or the actual SQL boundary. Sleeps and larger timeouts do not establish ordering.
- **Cover the other valid order too.** In `SubscriptionMutationConcurrencyMySqlIntegrationTest`,
  one scenario waits until the competing command actually observes `IN_PROGRESS`. Another
  pauses after it captured an active provider snapshot, lets cancellation commit, and then
  resumes the stale command. Both must preserve cancellation. Merely delaying cancellation
  until the competitor reaches `WAIT` would hide the stale-snapshot bug exposed by CI.
- **Finish workers before cleanup.** Release all scenario barriers in `finally`, stop the
  executor and assert bounded `awaitTermination` before deleting rows or resetting mocks.
  `shutdownNow()` requests interruption; it does not prove that a worker has stopped.
  Reset hooks/counters between scenarios. Preserve committed fixtures, independent connections,
  actual transaction boundaries and fresh reads proving rollback before cleanup.
- **Keep isolation and assertions.** Do not mask a failure with retries, disabled scenarios,
  weaker expected outcomes, a new global lock or a smaller worker count. First distinguish a
  resource timeout from a broken ordering assumption or a genuine application race. Keep
  authorization negatives, exact-repeat checks and reached-write rollback assertions intact.

For a changed concurrent suite, run the specific failing method on a fresh database, then
the entire class and related feature tests. For a schema-lifecycle change, also run after a
destructive schema class in the same JVM and run relevant methods independently. Repeat the
deterministic interleavings before the full four-worker gate. Do not run concurrent Maven
commands against the same checkout's `target` directory.

```bash
./mvnw test '-Dtest=SubscriptionMutationConcurrencyMySqlIntegrationTest#cancellationRacingUpdateRejectsUpdateAfterTerminalState+cancellationCommittedBeforeUpdateClaimRejectsStaleSnapshot'
./mvnw test -Dtest=SubscriptionMutationConcurrencyMySqlIntegrationTest,SubscriptionMutationServiceImplTest,BillingSubscriptionMutationControllerWebMvcTest
python3 tools/testing/context_benchmark.py verify --workers 4 \
  --output /tmp/quizmaker-billing-full-verify
```

The scoped commands require the existing disposable test MySQL and fake test environment;
they do not enable `context-forks`. The launcher provisions isolated databases for the full
gate. Compare executed test identities, failures/skips and merged coverage evidence, not
just the green exit code. PR and post-merge `clean verify` remain required; scoped repetitions
are additional diagnostic evidence, not a replacement.

### Legacy on-time answer/completion race

`LegacyAttemptTimeoutMySqlIntegrationTest.onTimeAnswerAndCompletionKeepTheCommittedWinnerAndOneCompletionEvent`
keeps all six mode/order vectors and the real locked time check. Each gate/result has a
120-second watchdog inside one 180-second scenario budget, also applied to executor cleanup.
The JUnit timeout is a final backstop. These are test watchdogs, not application deadlines
or throughput requirements; a reached gate completes immediately.

A gate wait also observes its participant's future. An early exception keeps its original
cause; an early business rejection retains its reason, and even a successful return cannot
replace evidence that the required gate was reached. A timeout reports both participants'
last observed phases, future states, thread states and at most 24 stack frames each. Entering
locking SQL is identified separately from reaching the time check under the acquired lock.
The harness releases the held participant, interrupts both executors and checks termination
before clearing hooks; try-with-resources preserves the original failure if cleanup also fails.

`LegacyRaceWatchdogTest` tests those reporting/cleanup outcomes without starting Spring or
claiming database conformance. Repeat the real six-vector method on a fresh disposable MySQL,
then run the entire timeout class and related legacy isolation/answer-integrity suites before
the unchanged full four-worker verify. The increased budget does not establish that a CI
stall was resource pressure or rule out an application fault; use the newly retained cause
and phase/stack evidence if another failure occurs.

### Local commands

Prerequisites: JDK 17 in `JAVA_HOME`, Python 3, Docker, a locally available MySQL 8.4 image,
and cached Maven dependencies. Dependency/image preparation happens before offline testing.
The local execution launcher currently uses macOS `sandbox-exec` to deny external networking;
Linux CI uses the existing network namespace. The `prepare`, `check`, and `cleanup` commands
are platform-independent. The launcher uses checked-in fake environment values, never the
caller's provider credentials. Set no production connection overrides.

Run from the primary checkout root:

```bash
python3 -B -m unittest discover -s tools/testing -p 'test_*.py' -v
python3 tools/testing/context_benchmark.py inventory --check
python3 tools/testing/context_benchmark.py compare --scope contexts --workers 1,2,4 \
  --repeat-selected 1 --output /tmp/quizmaker-context-comparison
python3 tools/testing/context_benchmark.py verify --workers 1 --serial \
  --output /tmp/quizmaker-full-baseline
python3 tools/testing/context_benchmark.py compare --scope full --workers 1,2,4 \
  --repeat-selected 1 --output /tmp/quizmaker-full-comparison
```

Use a fresh output directory for every invocation. `compare --scope contexts` compiles once
and measures identical compiled tests; it is a scoped benchmark, not a release gate.
`verify` and `compare --scope full` execute complete `clean verify` runs. With the profile,
Maven runs the ordinary unit selection once, then the isolated context/database pool,
then the exclusive suite, merges coverage from every execution, and performs the existing
coverage checks and packaging.
The launcher additionally validates executed suites, unique server identities, and coverage
files before reporting success. Logs and resource ownership records survive `clean` outside
`target` and are copied to the selected output even when a run fails.

Use ordinary `./mvnw test -Dtest=...` for focused feature feedback. Do not combine `-Dtest`
with `context-forks`: Surefire selectors override include/exclude files and could execute
classes twice. The profile rejects this combination and the manual live-provider profile.
Worker counts are restricted to 1, 2, or 4. Provision independent servers before invoking the
profile directly; `-DforkCount=4` against a shared database is unsafe.

The local launcher records separate database setup, Maven execution, and total durations,
exact test identities, source/bytecode identity, and coverage differences. Class timings and
nested-container timings must not be summed as wall-clock time. Compare the same tree and
hardware with JaCoCo enabled. Missing covered lines or branch differences require investigation;
matching totals alone is insufficient. Choose two workers when four exhaust memory or run
slower. No automatic retries or relaxed checks compensate for resource shortages.

### Local measurements, 11 November 2026

Four full `clean verify` runs of this profile, based on `5450e297`, used identical source
and compiled application bytecode. Each run executed the same 9,575 test-case identifiers:
6,131 in the ordinary unit execution and 3,444 in the 243-suite context/database pool.
Every run passed with zero failures, errors, or skips; merged coverage checks and packaging
also passed. These measurements compare this profile at different worker counts, not the
ordinary no-profile execution or a GitHub runner.

| Workers | Complete Maven execution | Including database setup and evidence checks |
|---|---:|---:|
| 1 | 23:05.92 | 23:14.80 |
| 2 | 14:33.02 | 14:43.76 |
| 4 | 11:33.10 | 11:45.02 |
| 4, repeat with fresh databases | 11:26.84 | 11:38.34 |

Four workers reduced the full Maven duration by approximately 50%; two reduced it by 37%.
The total column excludes artifact copying and final resource cleanup. All 11 MySQL
instances had distinct server identities and were removed after their runs. The existing
development MySQL remained running. The launcher/CI helpers also passed 37 Python tests.

Environment: macOS, 10 host CPUs / 32 GiB RAM, JDK 17, JVM `-Xms256m -Xmx2g`,
Docker with 10 CPUs / 7.75 GiB, and MySQL 8.4.11 with 128 MiB buffer pools and server
REPEATABLE_READ. Every run resolved the same local MySQL image. A single observation during
the four-worker run showed approximately 3.4 GiB JVM RSS plus 2.6 GiB MySQL memory; this is
not a peak measurement and excludes Maven and other processes.

No source lines covered by the one-worker baseline were lost. Coverage was not bit-for-bit
identical: per-execution JaCoCo probes localized three scheduling-dependent differences to
the unchanged ordinary unit execution (`GenerationWorkDispatcherImpl:48`,
`ParentProcessMonitor:23,25,29`, and `AiQuizGenerationServiceImpl:223`). The repeated run
missed one baseline branch in the cancellation short-circuit, while all cancellation test
identifiers and assertions remained present. An additional admission-guard branch appeared
at `GenerationWorkServiceImpl:161` in the context execution. These variations are not newly
guaranteed coverage and must not be hidden by comparing rounded percentages alone.

On this machine, use the measured four-worker mode for a complete gate:

```bash
python3 tools/testing/context_benchmark.py verify --workers 4 \
  --output /tmp/quizmaker-full-verify
```

Choose a new output path for each run; use two workers on a more constrained machine until
measurements support four. Focused feature feedback still uses ordinary scoped tests.

### CI rollout and recovery

CI retains the serial baseline by default (`CONTEXT_TEST_WORKERS=0`). The workflow's manual
`context_workers` input supports 0/1/2/4 for measurements on the actual GitHub runner. A manual
run never publishes a deployment artifact. After repeated full-run evidence demonstrates an
improvement without lost tests/coverage, the repository variable `CONTEXT_TEST_WORKERS` may
select the measured 1/2/4 configuration for ordinary PR and post-merge runs. Do not infer that
four local workers are optimal on a smaller CI machine.

The accelerated CI path provisions independent MySQL instances, bridges only their loopback
ports into the offline namespace, runs one full Maven `clean verify`, and validates context
evidence before uploading the tested JAR. Required checks, migration smoke, post-merge
verification, and deployment's exact successful-run/SHA artifact binding remain unchanged.
`context-verification` artifacts include assignments, server identities, reports, coverage,
resource ownership/cleanup evidence, and the verification log.

To revert the optimization, set `CONTEXT_TEST_WORKERS` to `0` and use the ordinary full Maven
command locally. No application-data rollback is involved. If cleanup cannot reach Docker,
restore Docker and run `cleanup --output <run-resource-directory>` against the preserved
`resources.json`; it validates names and ownership labels before removing only that run's
containers and anonymous volumes. Never use a global prune for recovery.


## Authoritative response writes and atomic batch (#629 / #907)

Use `ResponseAcceptanceMySqlIntegrationTest` for the real single/batch commands, five response
families, deterministic error order, strict limits, same-key replay, changed-key conflicts,
permission revocation, foreign owners/items, unknown commit and reached-write rollback. The
fixture runs all Flyway migrations and Hibernate `validate`. V112 is the bounded batch receipt
acknowledgement; V113/V114 and their growing target projections must be absent on this baseline.
Schema checks also prove receipt cascade removes the acknowledgement without deleting answers.

Independent first writes cover all single/single, single/batch and batch/batch combinations with
empty and populated response/receipt tables. Barriers are attached to the real accepted INSERT,
not a similar query. Held-reader/replay tests use populated 500-item forms; adjacent UUIDs and
same-revision answer/batch/form/start controls must finish before the holder is released. The
command owner is actual RC even on a server-default RR connection. Independent controls do not
replace same-attempt serialization tests. Lifecycle/version checks and joined RC rollback remain.

`AttemptSubmissionMySqlIntegrationTest` composes real batch acceptance with voluntary submission,
expiry, submitted-source provenance validation and automatic grading. It compares exact accepted
IDs and form-ordered grading output, checks omissions and replay, and confirms no legacy rows are
written. Existing single-response, draft, navigation, terminal, receipt and legacy RR suites remain.
SQL faults retain safe reason/classification before nested transaction logging, without raw answer
text in messages, cause, suppressed exceptions or TRACE rollback diagnostics.

```bash
./mvnw clean test -Dtest=ResponseAcceptanceActivationTest,ResponseAcceptanceOpenApiTest,ResponseAcceptanceMySqlIntegrationTest,AttemptSubmissionMySqlIntegrationTest
./mvnw test -Dtest=CommandReceiptMySqlIntegrationTest,NavigationMySqlIntegrationTest,DraftCommandMySqlIntegrationTest,AutomaticResultMySqlIntegrationTest,LegacyAnswerUniquenessMySqlIntegrationTest,LegacyAnswerMembershipMySqlIntegrationTest
./mvnw verify
```

Use the existing offline context launcher with four isolated MySQL workers for full verification;
full verify, coverage merging and post-merge checks are unchanged. The batch relationship-read
control uses three parent attempts and 1 versus 20 submitted items: the source SELECT count must
stay equal. It does not imply bounded bytes independent of quiz size; the whole quiz is still read.

`ResponseWriteCostProbe` is an opt-in diagnostic whose name is deliberately outside the default
Surefire patterns (listed in the manifest's `not_discovered`). Run only its measurement method on
a disposable, otherwise idle MySQL server, with the same fake/offline environment as integration
tests. It runs real standalone RC commands, 100/250/500 responses, single and batch, three samples
per size/mode. Fixture/start/delivery and verification queries are outside measured intervals.
It records application-thread statements (including driver isolation operations), rows, bytes,
global redo/binlog deltas and latency. No millisecond threshold gates correctness.

With JDK 17 in `JAVA_HOME`, the launcher owns one isolated MySQL container, applies the existing
offline test environment, checks all 18 samples per mode and removes its container on completion:

```bash
python3 tools/testing/response_write_cost.py --output /private/tmp/response-write-cost --compare-historical
```

The output directory must be new. Omit `--compare-historical` for current-code measurements only;
`--mysql-image` selects an already available local image. Logs, JSON samples, server configuration,
source fingerprint and cleanup evidence remain under that directory. For an already prepared
isolated local test server, the underlying invocation is
`./mvnw test '-Dtest=ResponseWriteCostProbe#measureWriteCost'`.

For the controlled historical write-cost comparison, extract only V113/V114 from `703bfbe3` into
a temporary directory and supply `-Dresponse.cost.historicalMigrations=/absolute/temporary/path`.
This optional mode applies those migrations only to the probe's newly created schema. Use the
same current RC command implementation and server configuration for both modes: the comparison
isolates cumulative projection storage/maintenance (including their bytes in existing stream
`SELECT *` reads), not every difference from the old branch. Never
place those files in application migration resources or run the diagnostic against a shared DB.
`RESPONSE_WRITE_COST` JSON lines contain no response payloads or credentials. Keep setup, logs,
version/configuration and all samples with the measurement result, not just the fastest sample.

The deployment inventory selected the unapplied path: the checked server had V111 as latest
successful history, no failures, no V112–V114 entries or target projection objects. Recheck any
additional environment separately; this is not permission to repair history or change checksums.
Allowance `start_ids`, whole-quiz read volume and legacy V108/analytics remain separate cost work.

### Recorded local write-cost evidence (15 November 2026)

Controlled comparison on current #907 commands based on master `f8e6b07c`: JDK 17 with JaCoCo,
Docker MySQL 8.4.11, server default RR / actual commands RC, 128 MiB buffer pool, 16 KiB pages,
binlog enabled with ROW/FULL. Three samples per mode/size; TRUE_FALSE forms with N questions,
N standalone single commands or one atomic N-item batch. Setup and verification SQL are excluded.
Values below are medians; MB means 1,000,000 bytes. This is growth evidence, not production capacity.

| Mode | N | Server statements | Rows sent | Wire MB | Redo MB | Binlog MB | Seconds |
|---|---:|---:|---:|---:|---:|---:|---:|
| single | 100 | 5,900 | 73,100 | 10.220 | 0.791 | 0.323 | 3.840 |
| single | 250 | 14,750 | 445,250 | 51.726 | 1.903 | 0.807 | 17.103 |
| single | 500 | 29,500 | 1,765,500 | 190.703 | 3.860 | 1.613 | 48.667 |
| batch | 100 | 1,151 | 1,228 | 0.628 | 0.371 | 0.194 | 0.465 |
| batch | 250 | 2,801 | 3,028 | 1.527 | 0.938 | 0.483 | 1.437 |
| batch | 500 | 5,551 | 6,028 | 3.025 | 1.951 | 0.964 | 2.878 |

The same code with historical V113/V114 installed only in the disposable schema:

| Mode | N | Historical redo MB | Current redo MB | Historical binlog MB | Current binlog MB | Historical seconds | Current seconds |
|---|---:|---:|---:|---:|---:|---:|---:|
| single | 100 | 3.951 | 0.791 | 13.278 | 0.323 | 4.158 | 3.840 |
| single | 250 | 18.849 | 1.903 | 81.753 | 0.807 | 18.929 | 17.103 |
| single | 500 | 66.769 | 3.860 | 325.872 | 1.613 | 50.986 | 48.667 |
| batch | 100 | 3.503 | 0.371 | 13.149 | 0.194 | 0.599 | 0.465 |
| batch | 250 | 17.874 | 0.938 | 81.429 | 0.483 | 2.053 | 1.437 |
| batch | 500 | 64.946 | 1.951 | 325.222 | 0.964 | 5.401 | 2.878 |

Current single-command binlog grows 5.00× for 5× as many answers; batch grows 4.97×. Redo grows
4.88× / 5.26× respectively. The historical growing JSON causes roughly 24.5× / 24.7× binlog growth
for the same 5× increase. Batch source relationship reads stay bounded, while the established
per-item lifecycle/trace protocol still has linear SQL work (11N + 51 server statements here).
It has not been replaced with a different trace/version protocol or a bulk-write API.

Whole-quiz rereads remain visible: 500 standalone answers return 1,765,500 rows / 190.7 MB, and
most of their latency remains even after projections are removed. This requires the separate
read-volume slice before large-quiz activation; it is not hidden by the constant SELECT-count gate.
The historical stream `SELECT *` also transfers the added metadata columns, reflected in raw wire
samples. No performance threshold replaces deterministic rollback/concurrency tests.

Local evidence: `/private/tmp/quizmaker-907-cost-final/{authoritative,historical}.json`, `medians.json`,
matching Maven logs, `baseline.json` and `db/cleanup.json`. The launcher completed all 36 samples,
verified unchanged source fingerprints and removed its owned server. Scoped verification on
MySQL 8.0.43 completed 287 tests with no failures/errors/skips (response HTTP/activation, real
acceptance and submission/expiry/grading suites).

Full `clean verify` on four isolated MySQL 8.4.11 workers completed **9,786 tests**, zero failures,
errors or skips, with **88.43% line / 67.90% branch** coverage (820.5 seconds for Maven). All previous
case identities remain except the two deadlock cases whose signature expands from `(int)` to
`(int, boolean)`: both original blocker-isolation cases are retained for singles and additionally
run for batches. The total increases by 57; the optional performance probe is absent from default
verification. Measurement and full-verify source fingerprints match. Worker assignment, merged
coverage, unchanged-source and cleanup checks passed. Evidence is retained under
`/private/tmp/quizmaker-907-complete/verify/`, including `result.json`, `comparison-to-906.json` and
`maven.log`. CI/full verify and post-merge checks are unchanged.
