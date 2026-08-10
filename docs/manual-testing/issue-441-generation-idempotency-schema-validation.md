# Manual Test: Complete Generation Command Identity (#441)

## Purpose

Verify that the backend starts after the additive operation-tariff migration,
that actual upload/text content is bound to an idempotency key without being
persisted, and that exact retries retain the tariff captured at first claim.

## Preconditions

- Run this procedure locally against the normal development database.
- The database has already applied migrations through `V67`.
- Do not manually alter `quiz_generation_operations.request_hash`.
- Authenticate as a user with `QUIZ_CREATE` and enough billing tokens.
- Prepare two UTF-8 text files with the same filename and byte length but
  different content.

## Verify startup

1. In a local terminal, run:

   ```bash
   JAVA_HOME=<JDK_17_HOME> ./mvnw spring-boot:run
   ```

2. Wait for the application startup-complete message.
3. Confirm migration `V68__add_generation_operation_tariff_snapshot.sql`
   succeeds and there is no `request_hash`, tariff-snapshot, or
   schema-validation error.
4. Stop the application normally after startup is confirmed.

## Verify generation idempotency

1. Submit the first file to `POST /api/v1/quizzes/generate-from-upload` with
   `Idempotency-Key: manual-upload-441` and valid generation parameters.
2. Record the returned `jobId`; expect HTTP `202`.
3. Repeat the identical request with the same file and key.
4. Confirm HTTP `202` returns the same `jobId` and does not create a second
   reservation or job.
5. Submit the different same-size file with the same key and otherwise
   identical parameters.
6. Confirm HTTP `409` with the idempotency-conflict problem type and no new
   document processing, reservation, or job.

## Verify text identity

1. Submit `POST /api/v1/quizzes/generate-from-text` with
   `Idempotency-Key: manual-text-441`.
2. Repeat the identical body and confirm the same `jobId` is returned.
3. Change the text to different content with the same character count, keeping
   every other field and the key unchanged.
4. Confirm HTTP `409` and no billing mutation.

## Verify tariff snapshot

1. Start a generation with a unique key and record the operation row's tariff
   version, base tokens, and per-thousand-character rate.
2. Change only the **local** generation tariff configuration and restart the
   backend. Do not alter production configuration for this test.
3. Retry the original request with the original key.
4. Confirm the existing operation/job is returned and the original tariff
   snapshot remains unchanged.
5. Submit the same command with a new key and confirm the new operation captures
   the newly configured tariff.

## Verify privacy and compatibility

1. Inspect the matching `quiz_generation_operations` row locally.
2. Confirm `request_hash` is a 64-character digest and no raw source content,
   standalone source digest, JWT, or content-derived value appears in logs.
3. Submit a request without `Idempotency-Key`; confirm the existing one-off
   behavior still starts normally without replay guarantees.
4. Open `/v3/api-docs/quizzes` and confirm upload/text operations document exact
   source retries, `409`, and one-way source-digest behavior.

## Expected Result

The backend starts without migration/schema errors. Exact retries are stable,
changed source content conflicts before side effects, existing commands retain
their claimed tariff, new commands use the current tariff, and legacy no-header
requests continue to work.
