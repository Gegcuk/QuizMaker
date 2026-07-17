# GitHub Issue Writing Guide

This guide defines the issue format for QuizMaker. It is written for maintainers, contributors, and AI agents.

## What A Good Issue Does

A good issue explains the user or system problem, the observable outcome, constraints, and how completion can be verified. It should not force a class name or design pattern unless that is an approved architectural constraint.

Prefer a vertical slice that delivers usable behavior across the necessary layers. Avoid splitting one outcome into separate "create entity", "create repository", "create service", and "create controller" issues unless they are explicitly tracked as dependent tasks under one epic and cannot be delivered safely together.

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

## Required Issue Sections

Use all applicable sections. Write `Not applicable` rather than silently omitting a section that readers may expect.

```markdown
## Problem and context

Who is affected, what currently happens, and why it matters. Include reproducible evidence or links where available.

## User or system outcome

Describe the observable result after completion.

## Scope

- Required behavior
- Important business rules
- Supported inputs and outputs

## Out of scope

- Explicitly excluded behavior
- Follow-up work that should not expand this issue

## Acceptance criteria

- [ ] Testable outcome stated in externally observable terms
- [ ] Important error or edge case
- [ ] Backward-compatibility expectation

## API and documentation

- Endpoint and method, if already approved
- Request/response shape, pagination, status codes, and RFC 7807 errors
- OpenAPI group and `/api/v1/api-summary` discoverability
- Representative valid examples

## Security and privacy

- Authentication requirement
- Permission names and ownership/visibility rules
- Negative authorization cases
- PII, secret, rate-limit, abuse, SSRF, upload, or audit concerns

## Data and compatibility

- Migration, indexes, constraints, retention, and rollback considerations
- Existing clients/data that must continue to work

## Test expectations

- Unit tests for business rules
- MVC/contract tests for HTTP and OpenAPI behavior
- Repository tests for custom persistence behavior
- Integration tests for cross-layer, transaction, or security behavior
- Fakes/stubs for external systems; never require a real paid API

## Dependencies and related work

- Blocking issues
- Issues blocked by this work
- Frontend counterpart, if any

## Definition of done

- [ ] Acceptance criteria are satisfied
- [ ] Appropriate tests pass
- [ ] Security and compatibility have been reviewed
- [ ] OpenAPI and user/developer documentation are updated
- [ ] No unrelated changes are included
```

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
- Are tests expected at the correct layers?
- Are dependencies linked in both directions?
- Is the issue small enough for one focused local commit or a short commit series?

If any answer is unclear, refine or split the issue before implementation starts.
