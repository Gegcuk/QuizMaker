# Document Storage Reconciliation

## Ownership

A committed row in `documents.file_path` is the authoritative owner of a published document file. Files under the configured `published` directory that have no committed owner may be removed only after `quizmaker.document.processing.staging-retention` has elapsed.

Reconciliation never reads or hashes document content. It compares server-generated storage paths only.

## Algorithm

`DocumentStorageReconciliationScheduler` runs at `quizmaker.document.processing.reconciliation-interval` and delegates filesystem traversal to `DocumentUploadStagingService`.

1. Expired staging files are cleaned using the existing retention rule.
2. The local storage adapter streams expired regular files from `published`. It does not follow symbolic links or materialize the full directory listing.
3. The scheduler collects at most 250 candidate paths.
4. One scalar repository query resolves committed owners for the batch using the `documents.file_path` index.
5. Paths found by that query are preserved.
6. Each apparent orphan receives an exact authoritative lookup in a fresh read-only transaction immediately before deletion. A newly committed reference therefore preserves the file even if a caller already has an older transaction snapshot.
7. Confirmed orphans are removed with idempotent `deleteIfExists` behavior.

The query bound is `ceil(candidate_count / 250)` batch queries plus one exact lookup for each apparent orphan. Live files do not cause per-file queries.

## Concurrency

- New promotion uses a server-generated path and starts inside the retention window, so reconciliation cannot select it while its document transaction is being published.
- A reference committed after batch resolution is detected by the final exact lookup.
- A reference deleted after batch resolution may preserve the file for one extra run, which is the safe outcome.
- Concurrent scheduler executions may inspect the same orphan. Deletion remains idempotent and neither execution may delete a committed live path.
- Reprocessing keeps the existing published path and is unaffected by this scheduler change.

## Failure And Recovery

Database and storage uncertainty always preserves files:

- A batch-query or final-lookup exception aborts the current run before deleting the uncertain candidate.
- A directory-scan failure aborts the current run with a storage exception handled by the scheduler.
- A failed individual file deletion is retried on a later run.
- No durable cursor is required. A later run safely starts from the directory beginning because reference checks and deletion are idempotent.

Repeated incomplete runs or deletion failures require operator investigation. Bounded metrics and alert thresholds are owned by issue #722.

## User-Requested Deletion

`DocumentDeletionService` owns the database transaction for the existing delete operation:

1. It locks the selected document row so concurrent duplicate deletes cannot both mutate the same committed owner.
2. It resolves the authenticated user and checks document ownership before any database or storage mutation.
3. It bulk-deletes chunks and deletes the document row inside the transaction without loading each chunk.
4. It registers source cleanup with `DocumentSourceFileCleanup`; the storage adapter is not called before commit.
5. After a successful commit, source removal uses the existing idempotent `discard` operation.

Rollback never runs the cleanup callback, so a committed document cannot be left pointing at a source removed by a failed delete. After commit, the row no longer owns the source. If the process stops or immediate cleanup fails, the unreferenced file remains recoverable and reconciliation removes it after retention. The row lock covers only database work; file removal does not prolong the database transaction.

## Schema

Flyway migration `V71__index_document_file_path.sql` adds `idx_documents_file_path` when it is absent. It changes no business data and requires no backfill. If an interrupted deployment leaves the index absent, repair the failed Flyway state as usual and rerun the migration; do not mark V71 successful without verifying the index.

## Privacy

Reconciliation and deletion-cleanup decisions must not log paths, filenames, owners, or content and must not use them as metric tags. Existing safe scheduler outcome logging remains operational; broader document-log redaction is tracked by issue #722.
