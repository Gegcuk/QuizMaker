# Known Risk Register

This register preserves verified concerns found while cleaning older reviews. Each item must be rechecked against the current branch before implementation and converted into a properly scoped issue. It does not grant permission to change code or deployment configuration without approval.

## P0: Deployment Workflow Exposes Sensitive Values In Logs

Evidence: `.github/workflows/deploy-backend.yml` enables `MYSQL_INIT_SHOW_SECRETS` and prints database credential fragments and debug information while creating and loading the production `.env` file.

Risk: deployment logs can expose credentials or enough material to weaken their protection. This should be remediated before the next deployment by removing secret-oriented diagnostics, rotating any potentially exposed credentials, and verifying that logs show variable names or lengths only where operationally necessary.

## P1: Logout Contract Does Not Match Behaviour

Evidence: `AuthController` documents logout as revoking the access token, but `AuthServiceImpl.logout` has no implementation.

Risk: clients can reasonably assume a successful logout invalidates the token when it does not. Decide and document one supported session model: token revocation or blacklist, refresh-token/session invalidation, or explicit stateless logout that only clears the client. Align OpenAPI and tests with that decision.

## P2: No Automated Dependency Or Static Security Scanning

Evidence: the current GitHub workflows have build/test and deployment jobs but no CodeQL, dependency vulnerability scan, or equivalent scheduled security check.

Risk: known vulnerable dependencies and common static-analysis findings can remain undetected between manual reviews. Create a separate CI issue that defines the chosen tools, false-positive process, severity threshold, report retention, and whether findings block merges.
