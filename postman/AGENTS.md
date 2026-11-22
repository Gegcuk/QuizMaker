# Postman scenario rules

These rules apply to collections, environments and scenarios in `postman/`.
Write all instructions, documentation, request and folder descriptions, comments
and assertion messages in English.

## OpenAPI first

- Before creating or changing a scenario, read `/api/v1/api-summary` from the
  running local application and the relevant `/v3/api-docs/{group}` specifications.
- Derive requests and expected results only from OpenAPI: paths, methods,
  authentication, parameters, required fields, constraints, named schemas,
  examples, statuses, errors and documented replay semantics.
- Identify the contract group/operationId and the feature under test in the
  scenario. Verify that the container revision matches the selected published
  implementation.
- Prepared users, questions and local environment values are inputs; they do
  not replace the public API contract.
- Before the first run, do not read controllers, services, repositories or
  existing Java tests to derive expected behavior. After a failed HTTP check or
  an initial verification run, implementation code may be read for diagnosis
  or additional verification.
- Record differences between Swagger and runtime explicitly. Do not replace an
  expected result with observed behavior or weaken an assertion to make a run
  pass. Do not invent undocumented guarantees. Agree application or OpenAPI
  fixes separately when they are outside the current task.
- Track confirmed discrepancies in [GitHub #938](https://github.com/Gegcuk/QuizMaker/issues/938).
  Each entry includes a stable ID, application revision, expected and actual
  results, reproduction, impact, status, and a linked fix with recheck evidence.

## Scenario selection and repeatability

- Prioritize the latest completed feature published on the main branch: verify
  its merged PR, completed owning issue and available OpenAPI. An open branch
  or an approved architectural decision alone does not establish readiness.
- Preserve existing scenarios when adding coverage.
- When a section contains several independent scenarios, place them in child
  folders at one level. Each child includes its own setup, assertions, temporary
  resource cleanup and logout, and runs independently or with its siblings from
  the parent folder. Use separate transient variables and unique request names
  for transitions. Completed cleanup must allow the next independent scenario
  to run after an already reported contract assertion. Do not create runnable
  folders for dependent phases that cannot execute independently.
- Use the local Runner, existing local fixtures and unique command/resource
  identifiers for each independent run.
- Distinguish exact command replay from a new run: replay retains the original
  commandId and entire request body; a new run uses new UUIDs.
- Check happy paths and significant documented errors, plus the absence of
  unwanted effects through available public operations.
- Delete only resources created by the current run, after verifying ownership
  and a unique run marker. Preserve existing publication-history retention.
- Store fixture passwords only in the environment's local Value. Shared Value
  and public exports must stay empty. Keep JWTs and intermediate state in
  `pm.variables`, clear them at completion and explicitly log out sessions.
- Use only the local Docker environment in `server/local/`. Never target remote
  or production services. Preserve the localhost guard, disabled redirects and
  cookies, and disabled storage of sensitive Runner responses/logs.
