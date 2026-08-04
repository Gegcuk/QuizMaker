# Manual Test: Generation Idempotency Schema Validation (#441)

## Purpose

Verify that the backend starts against a database created by the generation
idempotency migration. The SHA-256 request hash is stored as fixed-width
`CHAR(64)`, matching both Flyway and Hibernate.

## Preconditions

- Run this procedure locally against the normal development database.
- The database has already applied migration `V62`.
- Do not manually alter `quiz_generation_operations.request_hash`.

## Verify startup

1. In a local terminal, run:

   ```bash
   ./mvnw spring-boot:run
   ```

2. Wait for the application startup-complete message.
3. Confirm there is no error containing either `request_hash` or
   `Schema-validation`.
4. Stop the application normally after startup is confirmed.

## Verify generation idempotency

1. Generate a quiz using the current frontend.
2. Repeat the same request with the same idempotency key, where the client
   supplies one.
3. Confirm that the existing generation is returned instead of a duplicate
   operation being created.

## Expected Result

The backend starts without Hibernate schema-validation errors. Existing
idempotency records remain valid, and no migration or data rewrite is needed
for the fixed-width request hash column.
