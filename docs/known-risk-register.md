# Known Risk Register

Current source review: 21 October 2026. This file retains unresolved verification
and coverage risks only. Current implemented contracts belong in their runbooks;
completed fixes are not open product decisions.

## Deployment security evidence remains unverified

The checked-in deploy workflow disables `MYSQL_INIT_SHOW_SECRETS` and uses
`docker compose config --quiet`. Source inspection does not establish which
workflow versions ran, whether accessible logs contain credentials, or whether
any necessary log-access restriction and credential rotation was completed.
Verify those facts through the responsible deployment/security process without
requesting or reproducing secrets. This is an evidence gap, not a claim of an
active exposure or a reason to change the working authentication model.

Related scope: [release verification](architecture/open-questions.md#oq-release)
and [#427](https://github.com/Gegcuk/QuizMaker/issues/427).

## Security scanning coverage is not established

The checked-in workflows provide build/test and deployment jobs but no CodeQL,
dependency-vulnerability scan or equivalent scheduled check. Repository settings
or external scanners have not been verified.

Confirm actual coverage before choosing a tool or declaring scanning absent.
Any missing policy must identify triage responsibility, false-positive handling,
severity thresholds, report retention and whether findings block merges. No tool,
dependency or merge-blocking threshold is selected by this register.

Related scope: [release verification](architecture/open-questions.md#oq-release),
[#460](https://github.com/Gegcuk/QuizMaker/issues/460), and
[#427](https://github.com/Gegcuk/QuizMaker/issues/427).
