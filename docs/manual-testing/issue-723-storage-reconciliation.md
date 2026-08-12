# Manual Test Guide: #723 Storage Reconciliation

## Purpose

Verify that expired unreferenced document files are removed while committed and retention-protected files remain. This issue changes no HTTP endpoint or client contract.

Use a disposable local database and storage directory only. Never create test orphans in production storage.

## Automated Verification

Run locally with Java 17:

1. `./mvnw -Dtest=DocumentStorageReconciliationSchedulerTest,DocumentStorageReconciliationFilesystemTest,DocumentFileReferenceLookupImplTest,LocalDocumentUploadStagingServiceTest test`
2. `./mvnw -Dtest=DocumentStorageReconciliationMySqlIntegrationTest,DocumentFilePathIndexMigrationTest test`

Expected result:

- The non-database tests pass without external services.
- The MySQL tests pass against the configured local test database.
- The 251-live-file test executes exactly two reference projection queries and no per-file reference query.
- A reference committed between batch lookup and final recheck preserves its file.

## Migration Verification

After running Flyway against a disposable local MySQL database, run locally:

1. `mysql -h <MYSQL_HOST> -u <MYSQL_USER> -p <MYSQL_DATABASE> -e "SHOW INDEX FROM documents WHERE Key_name = 'idx_documents_file_path';"`

Expected result: one index named `idx_documents_file_path` covers `file_path`.

## Local Orphan Cleanup

1. Stop the local backend.
2. Run locally: `export DOCUMENT_STORAGE_ROOT='<LOCAL_PATH>/document-reconciliation-test'`
3. Run locally: `mkdir -p "$DOCUMENT_STORAGE_ROOT/published"`
4. Run locally: `printf 'orphan fixture' > "$DOCUMENT_STORAGE_ROOT/published/orphan.txt"`
5. Run locally: `touch -t 202001010000 "$DOCUMENT_STORAGE_ROOT/published/orphan.txt"`
6. Run locally: `QUIZMAKER_DOCUMENT_PROCESSING_STORAGE_ROOT="$DOCUMENT_STORAGE_ROOT" QUIZMAKER_DOCUMENT_PROCESSING_STAGING_RETENTION=PT5S QUIZMAKER_DOCUMENT_PROCESSING_RECONCILIATION_INTERVAL=PT5S ./mvnw spring-boot:run`
7. Wait for one configured reconciliation interval.
8. Run locally: `test ! -e "$DOCUMENT_STORAGE_ROOT/published/orphan.txt"`

Expected result: the command exits successfully because the expired unreferenced file was removed.

## Retention Protection

1. Stop the local backend and recreate `published/fresh.txt` without changing its modification time.
2. Start the backend with a retention period longer than the age of `fresh.txt`.
3. Wait for one reconciliation interval.
4. Run locally: `test -e "$DOCUMENT_STORAGE_ROOT/published/fresh.txt"`

Expected result: the fresh unreferenced file remains until retention elapses.

## Referenced File Protection

1. Upload a document normally to the disposable local backend so the server publishes the file and commits its `documents.file_path` row.
2. Keep the document row present and wait until the configured retention period has elapsed.
3. Wait for a reconciliation run.
4. Confirm the stored file still exists and the document remains readable through the existing document API.

Expected result: committed document references are preserved. Repeating or overlapping reconciliation runs does not change that result.

## Failure Safety

Database and storage failure injection is covered by automated tests. Do not stop a shared or production database to test it manually. The expected invariant is that an uncertain path is preserved and retried during a later reconciliation run.
