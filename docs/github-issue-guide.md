# GitHub Issue Writing Guide

This guide defines the issue format for QuizMaker. It is written for maintainers, contributors, and AI agents.

## Issue Workflow

Do not implement an issue until it identifies the user or system problem, testable acceptance criteria, explicit scope and exclusions, data owner, permission model, failure behavior, offline behavior where relevant, API/data changes, OpenAPI compatibility impact, and dependencies.

An incomplete issue is not ready. Refine it before implementation rather than silently making product, security, privacy, persistence, or client-contract decisions. A good issue describes an independently verifiable vertical slice; it does not prescribe a class name or design pattern unless an approved architectural constraint requires one.

Aim for about **750 added handwritten lines of implementation logic**, excluding tests and test fixtures. This is a preferred reviewability guideline, not a hard limit or an automatic reason to split. A larger coherent change is acceptable; split only into independently useful, compatible and verifiable outcomes.

Prefer a vertical slice that delivers useful behavior across the necessary layers. Do not split one outcome into separate entity, repository, service, and controller issues unless they are independently releasable and explicitly linked as dependencies.

### Definition Of Ready

An issue may be implemented only when all of the following are true:

- the required structure below is complete, using `Not applicable` only with a reason;
- acceptance criteria are observable and testable, including failure behavior and offline behavior when relevant;
- an accountable data owner and all authorization, ownership, visibility, and privacy decisions are named;
- API contracts, OpenAPI grouping and compatibility impact are known for every client-visible change;
- additive migration/backfill/retention/rollback considerations are explicit for data changes;
- dependencies, sequencing, and any decision that still needs project-owner approval are explicit;
- the issue is small enough for one focused branch and local commit series, or is split into independently testable child issues.

## Title Format

Use:

```text
[Area] Outcome-oriented description
```

Examples:

```text
[Documents] Publish typed tree and flat structure responses
[Security] Reject private-network link ingestion targets
[Flashcards] Create and review a basic deck end to end
[Bug] Clamp generation progress to the documented range
```

Avoid titles that only name an implementation artifact, such as `Create XServiceImpl`.

## Required Issue Structure

Every issue must use every heading below, in this order. Write `Not applicable because ...` rather than omitting a section. The detail under each heading must be sufficient for a contributor or AI agent to implement the work without guessing.

```markdown
## Problem

Who is affected, what currently happens, why it matters, and reproducible evidence or links where available.

## User story

As a ...
I want ...
So that ...

## Scope

- Required behavior
- Important business rules
- Supported inputs and outputs

## Out of scope

- Explicitly excluded behavior
- Follow-up work that should not expand this issue

## Acceptance criteria

- [ ] Given ..., when ..., then ...
- [ ] Given ..., when ..., then ...
- [ ] Failure behaviour is defined.
- [ ] Offline behaviour is defined where relevant.

## API changes

- OpenAPI operation and logical group, named request/response schemas, and representative examples
- Authentication, authorization, ownership/visibility, RFC 7807 error responses, validation, and idempotency
- Pagination/filtering/sorting when applicable
- Backward-compatibility impact and iOS compatibility plan, even if no iOS client exists yet

## Data changes

- Data owner for every new or changed record
- Additive Flyway migration, indexes, constraints, retention, backfill, and rollback considerations
- Existing data and client compatibility

## Permissions and privacy

- Authentication requirement and permission names
- Ownership, organization, tenant, audience, and visibility rules
- Negative authorization cases and default-deny behavior
- Personal data, secrets, abuse, rate-limit, SSRF, upload, audit, and redaction concerns

## Failure cases

- Validation, concurrency, partial-write, provider/outage, retry/idempotency, and recovery behavior
- Offline/reconnect behavior where relevant, or why it is not applicable
- What must be rolled back, retained, retried, rejected, or surfaced operationally

## Observability

- Logs, metrics, traces, audit events, alerts, dashboards, and operational ownership where affected
- Low-cardinality metric dimensions and redaction requirements

## Testing requirements

- Unit tests for business rules and error paths
- MVC/OpenAPI contract tests for client-visible HTTP behavior
- MySQL integration tests for persistence, transaction, locking, or concurrency behavior where applicable
- Compatibility fixtures for existing APIs/data and authorization/ownership negative tests
- Fakes/stubs for external systems; never require a real paid API

## Documentation

- OpenAPI, user documentation, developer/architecture docs, runbooks, migration notes, and frontend guidance where affected
- A `docs/manual-testing/issue-<number>-<slug>.md` guide only when directly requested by the repository owner; otherwise record essential manual/operator verification in the implementation handoff

## Dependencies

- Blocking issues, issues this work blocks, external prerequisites, and frontend counterparts
- Exact assumptions that must be approved before implementation

## Definition of ready

- [ ] All decisions required by this issue are explicit and approved, or this issue is marked blocked pending a named decision.
- [ ] The scope is a focused vertical slice with known dependencies.
- [ ] API/data/security compatibility is explicit.

## Definition of done

- [ ] Acceptance criteria and relevant tests pass.
- [ ] Migrations, API contracts, architecture boundaries, authorization, observability, and documentation are updated where affected.
- [ ] Client-visible endpoints have an OpenAPI contract and contract-validation coverage.
- [ ] Injected application services are used through their interfaces and feature package boundaries remain intact.
- [ ] Logs expose no secrets or sensitive location data.
- [ ] Automated checks and essential manual verification are recorded.
```

Do not retain the old `## Dependencies and related work` heading in new issues; use the required `## Dependencies` heading.

## Acceptance ownership and semantic reconciliation

- Replace superseded AC in place. Appending a new contract while retaining contradictory old requirements leaves the issue unready.
- For a shared scenario, name the assertion this issue owns, the realistic collaborator fake/contract it may use, and the downstream issue that proves the integrated outcome. A generic “shared scenarios do not expand scope” disclaimer is insufficient.
- Replace copied full-flow checkboxes and matrix rows with those local assertions; do not retain them as mandatory runtime tests under a scope disclaimer. State whether a fixture proves configuration, mapping, orchestration or actual durable writes. Put the complete scenario in its named integration owner, including any conditional capability activation gate.
- Follow both prerequisites and consumers when checking ownership. An acyclic native graph is insufficient if a prerequisite's AC requires its consumer's completed feature. Every real integration test must name the implementation that supplies each runtime operation; “section-runtime issue” or a generic fake is not an implementation dependency.
- Check the transitive native prerequisite closure for every mandatory runtime operation, including adjacent foundation tasks. Add the smallest set of direct edges that supplies the consumed contracts; distinguish already delivered foundations and conditional extensions. A mandatory baseline cannot substitute ALL_AT_ONCE for ONE_AT_A_TIME or immediate release for scheduled release just because its declared prerequisites implement only the former.
- Check both missing and unnecessary prerequisites against the exact selected baseline. A strategy contract needed by a producer must be upstream of that producer, not merely elsewhere in its consumer's graph. An unused stratified strategy must not block a RANDOM_COUNT baseline. For event pipelines, name the first real source writer and prove source/outbox atomicity separately from the consumer's durable delivery behavior; require their actual composition before activating the corresponding notification path.
- Assign restore/replay per data family. An object/source erasure owner does not implicitly own restored history/result rows. The DB-family owner must prove real replay and serving guards with a committed pre-deletion snapshot, independently retained deletion facts, missing/incomplete evidence, restart and fresh reads. Provider/topology evidence is a separate deployment gate and cannot replace this local proof.
- A denial test for an optional capability needs a successful positive control through the same implemented path. For example, complete formal translation must create one attempt and consume one allowance before an incomplete-translation fixture can prove the specific unavailable-language denial. Universal unsupported-flow rejection proves neither translation validation nor safe allowance handling.
- Keep preparation/design tasks independently completable; distinguish deployment/capability activation evidence from unconditional native blockers. Team/custom/full-cutover checks must not block a narrower individual/FSRS/limited-activation outcome.
- Use fresh-transaction reads for rollback/durability tests, committed fixtures for worker threads and controlled barriers for races. “Both workers failed” or mocked save counts cannot establish successful concurrency/durability.
- Establish that a failure fixture reaches the intended operation through the current public/application preconditions. Assert the injected checkpoint was reached. A status guard before writes is a compatibility test, not rollback proof; do not add a public rebuild/reset operation merely to make a rollback fixture reachable.
- Verify current issue bodies, relevant owner comments, native dependencies and source-document availability. A local document path or outdated `master` URL does not identify an unpublished reviewed snapshot.

## Instruction Quality

Every setup or operational issue must be executable by the project owner without guessing.

- List terminal commands as numbered, copyable lines. State whether each command runs **locally**, **over SSH on the Droplet**, or in a **provider console**.
- Use placeholders such as `<DROPLET_IP>`, `<LOCAL_PATH>`, `<DOMAIN>`, and `<SSH_USER>` rather than real credentials, tokens, or production identifiers.
- For Apple Developer, DigitalOcean, Cloudflare, GitHub, Stripe, or another provider-console action, provide numbered click-by-click console steps and a clear verification signal.
- Until a domain exists, use the Droplet IP only for SSH, firewall verification, health checks, and SSH tunnels. Do not present it as a production browser/API origin or an Apple web callback URL.
- State rollback and recovery commands/steps where an operational action can affect availability or data.

## Branches And Commits

- One issue, one branch. Never work directly on `main` or `master`.
- Name branches by intent and issue number: `feature/123-record-local-activity`, `fix/167-prevent-duplicate-upload`, `refactor/204-extract-route-policy`, or `chore/219-upgrade-testcontainers`.
- Use Conventional Commits, for example `feat(activity): persist local recording checkpoints`.
- Keep commits local until the project owner reviews the work and explicitly instructs a push.
- AI agents must not push, create/merge pull requests, deploy, or alter production outside an explicit user instruction.

## Bug-Specific Evidence

A bug report must add:

- exact reproduction steps;
- expected and actual behavior;
- environment or API version;
- sanitized logs, screenshots, or response samples when useful;
- likely scope only if supported by evidence;
- a regression-test expectation.

Do not include secrets, access tokens, private user data, or raw production credentials.

## Priority Rules

Priority expresses impact and urgency, not feature size.

- `priority:p0`: active security incident, data loss, or production outage;
- `priority:p1`: severe user-facing failure or release blocker;
- `priority:p2`: important product work or meaningful reliability risk;
- `priority:p3`: useful improvement with no immediate blocker.

Do not mark every dependency of a large epic as high priority. The roadmap order and dependency links should carry sequencing information.

## Required Labels

Each issue should normally have:

- one type: `bug`, `enhancement`, `documentation`, `tech-debt`, or `testing`;
- one or more areas: for example `api`, `backend`, `database`, `security`, `ai-generation`, `metrics`;
- one priority using the `priority:p0` to `priority:p3` convention;
- a feature/epic label when several issues belong to one delivery stream.

## Review Checklist For Issue Authors

- Is the outcome useful on its own?
- Are acceptance criteria observable rather than implementation-shaped?
- Are permissions, ownership, visibility, privacy, and abuse cases explicit?
- Are API schemas named and typed rather than generic `object` or raw `Page`?
- Is the OpenAPI group identified?
- Is backward compatibility clear?
- Are data ownership, failure behavior, offline behavior, and observability explicit?
- Are tests expected at the correct layers?
- Are dependencies linked in both directions?
- Is the issue small enough for one focused branch and local commit series?

If any answer is unclear, refine or split the issue before implementation starts.
