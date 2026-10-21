# Manual Test: Issue 458 Production Billing Configuration Preflight

## Purpose

Verify that the release image and the Compose backend receive the same resolved production billing ratio, and that the image validates it before MySQL, a candidate backend, or the live backend is started. The accepted value is a positive base-10 integer. The canonical fallback is `1000`.

## Production Billing Settings

`BILLING_TOKEN_TO_LLM_RATIO` remains a supported deployment override with the canonical default `1000`.

Production deployment retains the application defaults for these settings:

- Reservation TTL: `120` minutes. This controls the reservation expiration deadline, including renewed leases; it is not the generation-job deadline.
- Operational LLM estimate safety factor: `1.2`. Customer generation prices continue to use the separate content-length and question-type tariff.
- Estimate currency: `usd`. Actual checkout currency continues to come from the selected payment pack.

Issue #503 removes the unused `BILLING_RESERVATION_TTL_MINUTES`, `BILLING_SAFETY_FACTOR`, and `BILLING_CURRENCY` entries from the generated environment and its example. Compose does not forward these keys, including entries left in an older deployment `.env` file. Setting them as GitHub secrets does not configure the backend. Changes to this deployment contract require a reviewed configuration change.

The Spring property bindings remain unchanged for other launch configurations. Existing reservations, tariff values, prices, API responses, and the Compose-based preflight command are unchanged.

## Preconditions

- A staging server or maintenance environment has Docker, Docker Compose, the deployment `.env` file, and a locally loaded candidate image.
- Use a non-production release image and configuration. Do not expose or copy secrets into terminal output, issue comments, or screenshots.
- The existing backend, if any, is healthy before the test begins.

## Valid Configuration

1. Over SSH on the staging server, change to the deployment directory:

   ```bash
   cd /var/www/quizmaker-backend
   ```

2. Over SSH on the staging server, run the preflight against the candidate image:

   ```bash
   docker compose --env-file .env run --rm --no-deps quizmaker-backend --config-preflight
   ```

3. Verify the command exits with code `0` and prints `Production billing configuration preflight passed.` The command must not start or replace a Compose service.

## Invalid Configuration

1. Over SSH on the staging server, make a temporary copy of the environment file:

   ```bash
   cp .env /tmp/quizmaker-preflight-invalid.env
   ```

2. Over SSH on the staging server, replace only the ratio with a deliberately invalid value such as `1.0`:

   ```bash
   sed -i 's/^BILLING_TOKEN_TO_LLM_RATIO=.*/BILLING_TOKEN_TO_LLM_RATIO=1.0/' /tmp/quizmaker-preflight-invalid.env
   ```

   Repeat this step with `0`, `-1`, an overflowing integer, and whitespace if validating every rejection boundary. Do not alter any other line.

3. Over SSH on the staging server, run the same command against the temporary file:

   ```bash
   docker compose --env-file /tmp/quizmaker-preflight-invalid.env run --rm --no-deps quizmaker-backend --config-preflight
   ```

4. Verify the command exits non-zero, identifies `billing.token-to-llm-ratio`, and does not print the broader environment or any secret values.

5. Over SSH on the staging server, confirm the existing service remains healthy:

   ```bash
   docker compose --env-file .env ps quizmaker-backend
   ```

6. Over SSH on the staging server, remove the temporary file:

   ```bash
   rm -f /tmp/quizmaker-preflight-invalid.env
   ```

## Deployment Verification

1. In the GitHub Actions console, open a deployment run for a valid commit.
2. Verify `Production billing configuration preflight passed.` appears after the release image is loaded and before MySQL or the candidate backend is started.
3. For an intentionally invalid staging value, verify deployment stops at preflight and the healthy release continues serving traffic.

## Automated Coverage

- `BillingConfigurationPreflightTest` verifies canonical default, valid positive integer, zero, negative, decimal, overflow, and whitespace inputs through Spring binding and validation. It also binds the actual production configuration without deployment overrides and verifies the unchanged reservation, estimate, currency, ratio, and tariff defaults.
- `DeploymentBillingConfigurationContractTest` verifies the workflow fallback, Compose environment mapping, absence of the three unsupported overrides in the workflow/Compose/example, and that preflight is ordered before service startup.
