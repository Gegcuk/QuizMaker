# Manual Test: Document Schema Compatibility Migration

## Purpose

Verify that deployment automatically repairs the historical camelCase document
schema without manual database changes, and that text-based quiz generation can
store and process its temporary document again.

## Preconditions

- Deploy a backend build that contains Flyway migration `V64`.
- Do not run manual `ALTER TABLE`, `REPAIR`, or data-update statements.
- Run production checks over SSH on the Droplet. Use the deployed Docker Compose
  directory.

## 1. Verify the automatic migration

1. Over SSH on the Droplet, run:

   ```bash
   cd /var/www/quizmaker-backend
   docker compose --env-file .env logs --since 10m quizmaker-backend | grep -E 'Migrating schema.*64|Successfully applied.*64|Schema.*up to date'
   ```

2. Confirm the output reports that Flyway applied version `64`, or that the
   schema is already up to date after a previous successful deployment.
3. Confirm the backend container remains healthy:

   ```bash
   cd /var/www/quizmaker-backend
   docker compose --env-file .env ps quizmaker-backend
   ```

4. The status must be `Up` and `healthy`.

## 2. Verify user-facing generation

1. In the existing frontend, sign in with a normal user account.
2. Generate a quiz from pasted text using the same workflow that previously
   produced a failed generation job.
3. Confirm the job completes and its questions appear in the generated quiz.
4. Generate a second quiz from an uploaded document if document generation is
   available in the environment. Confirm it also completes.

## 3. Verify compatibility

1. Open a quiz that was created before this deployment.
2. Confirm it remains readable and playable.
3. Confirm that a newly generated quiz can be opened, answered, and saved using
   the existing frontend without a frontend deployment.

## Expected Result

Flyway applies `V64` once during backend startup. It preserves document and
chunk data while changing only legacy column names and converting legacy chunk
type names to the current enum storage format. No client request, billing
behavior, or manual database intervention is required.
