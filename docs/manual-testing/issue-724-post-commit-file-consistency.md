# Issue #724: Post-Commit Document File Consistency

## Purpose

Verify that document upload keeps database and local-file ownership consistent across publication failures. Existing successful upload behavior and API contracts remain unchanged.

## Prerequisites

1. Run the backend locally with the normal development database and document upload directory.
2. Sign in as a user allowed to upload documents.
3. Prepare a small valid UTF-8 `.txt` document.
4. Record the configured published-document directory from the active application configuration.

Do not use production data for failure injection.

## Successful Upload

1. Upload the text document through the existing document upload or quiz-generation flow.
2. Confirm the API returns the same successful response shape used before this change.
3. Confirm the returned document can be retrieved through the existing document endpoint.
4. Confirm exactly one published file exists for the stored document path.
5. Confirm no matching temporary `.upload` file remains in the staging directory.

Expected result: the document row and promoted file both exist, and staging is clean.

## Publication Rollback

This path requires a controlled local failure because production must not intentionally reject document commits.

1. From the repository root on the local machine, run:

   ```bash
   ./mvnw -Dtest=DocumentProcessingServiceImplTest#uploadDiscardsPromotedFileWhenPublicationFails test
   ```

2. Confirm the test reports success.
3. Review the test assertion that the promoted path and staging path are discarded while response mapping is never invoked.

Expected result: a failed database publication leaves neither a document row nor an owned published file.

## Post-Commit Mapping Failure

This path is also covered by controlled failure injection because the mapper has no runtime feature flag.

1. From the repository root on the local machine, run:

   ```bash
   ./mvnw -Dtest=DocumentProcessingServiceImplTest#uploadKeepsPromotedFileWhenPostCommitMappingFails test
   ```

2. Confirm the test reports success.
3. Review the test assertion that publication completes before mapping fails.
4. Confirm the staging path is discarded but the promoted path is not discarded.

Expected result: once publication has completed, response construction cannot delete the file referenced by the committed document.

## Compatibility Checks

1. Repeat a normal upload with `storeChunks=false` through an existing client.
2. Confirm document metadata is still persisted and no chunks are stored.
3. Confirm endpoint paths, request fields, response fields, authorization, and documented status codes are unchanged.

## N+1 Review

Not applicable. This change does not add or modify repository reads, entity relationship traversal, pagination, or collection mapping.
