# Manual Test Guide: #730 Post-Commit Document Deletion

## Purpose

Verify that the existing document delete operation removes database state first and removes the published source only after commit. A rollback preserves both, while a post-commit storage failure leaves an orphan that reconciliation can remove later.

This issue changes no endpoint, request, response, authorization rule, or web/iOS contract. Use a disposable local database and storage directory only.

## Automated Verification

Run locally with Java 17 and the existing local MySQL test profile:

1. `./mvnw -Dtest=AfterCommitDocumentSourceFileCleanupTest,DocumentDeletionServiceImplTest,DocumentProcessingServiceImplTest,LocalDocumentUploadStagingServiceTest test`
2. `./mvnw -Dtest=DocumentDeletionMySqlIntegrationTest test`

Expected result:

- Unit tests prove authorization and database operations occur before cleanup scheduling.
- Commit invokes source cleanup; rollback does not invoke it.
- A real MySQL foreign-key failure after cleanup scheduling rolls back the row/chunks and suppresses source cleanup.
- A cleanup failure cannot undo a committed database deletion.
- Concurrent duplicate deletes produce one successful delete and one not-found result without leaving a live row pointing to a missing file.
- The deferred-cleanup test proves reconciliation removes an expired orphan.
- A 25-chunk deletion stays within three application read queries, one collection fetch, and two loaded entities, proving chunks are bulk-deleted without a per-chunk read-query N+1.

## Successful Existing-API Delete

Use the existing Documents Swagger group at `http://localhost:8080/swagger-ui/index.html?urls.primaryName=documents`, or run these commands locally:

1. `export BASE_URL='http://localhost:8080'`
2. `export ACCESS_TOKEN='<OWNER_JWT>'`
3. `export DOCUMENT_ID='<OWNED_DOCUMENT_UUID>'`
4. `export SOURCE_PATH='<FILE_PATH_RECORDED_FROM_THE_DISPOSABLE_DATABASE>'`
5. `test -e "$SOURCE_PATH"`
6. `curl --request DELETE --silent --show-error --output /dev/null --write-out '%{http_code}\n' --header "Authorization: Bearer $ACCESS_TOKEN" "$BASE_URL/api/documents/$DOCUMENT_ID"`
7. `test ! -e "$SOURCE_PATH"`
8. `mysql -h <MYSQL_HOST> -u <MYSQL_USER> -p <MYSQL_DATABASE> -e "SELECT COUNT(*) AS documents_remaining FROM documents WHERE id = UNHEX(REPLACE('<OWNED_DOCUMENT_UUID>', '-', '')); SELECT COUNT(*) AS chunks_remaining FROM document_chunks WHERE document_id = UNHEX(REPLACE('<OWNED_DOCUMENT_UUID>', '-', ''));"`

Expected result: step 6 prints `204`, both counts are `0`, and the source no longer exists.

## Ownership Rejection

Run locally with a token belonging to a different user:

1. `export OTHER_ACCESS_TOKEN='<NON_OWNER_JWT>'`
2. `curl --request DELETE --silent --show-error --output /dev/null --write-out '%{http_code}\n' --header "Authorization: Bearer $OTHER_ACCESS_TOKEN" "$BASE_URL/api/documents/$DOCUMENT_ID"`
3. `test -e "$SOURCE_PATH"`
4. `mysql -h <MYSQL_HOST> -u <MYSQL_USER> -p <MYSQL_DATABASE> -e "SELECT COUNT(*) AS documents_remaining FROM documents WHERE id = UNHEX(REPLACE('<OWNED_DOCUMENT_UUID>', '-', ''));"`

Expected result: step 2 prints `403`, the row count remains `1`, and the source still exists.

## Rollback Safety

The rollback case is intentionally failure-injected in `DocumentDeletionMySqlIntegrationTest`. Do not add constraints, stop MySQL, or corrupt rows in a shared environment to reproduce it manually.

Expected invariant: a rolled-back request leaves the document row, chunks, and source intact, and does not call the storage cleanup adapter.

## Deferred Cleanup Recovery

Run only against a disposable local storage root:

1. Make immediate source removal fail using a local test double or filesystem permission fixture; do not change production permissions.
2. Delete the document through the existing endpoint.
3. Verify the response is `204`, the document row is gone, and the source remains as an unreferenced file.
4. Restore normal filesystem permissions.
5. Wait for `quizmaker.document.processing.staging-retention` and one `quizmaker.document.processing.reconciliation-interval`, or use the short disposable-local values documented in `docs/manual-testing/issue-723-storage-reconciliation.md`.
6. Run locally: `test ! -e "$SOURCE_PATH"`

Expected result: immediate cleanup uncertainty does not resurrect or roll back the deleted document; reconciliation later removes the orphan.

## Compatibility And Query Review

- Existing clients continue to receive `204`, `403`, and `404` through the same route.
- No schema migration or frontend change is required.
- The operation reads and locks one document, resolves one user, bulk-deletes chunks, and deletes the document. It introduces no per-item relationship lookup or chunk entity loading, so no new N+1 path is present.
- Deletion and reconciliation compare stored paths only; they never read or hash document content.
