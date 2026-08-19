# Normalized Document Ownership

## Scope

This boundary protects `/api/v1/documentProcess/documents` and all derived metadata, normalized text, structure, and node extraction. It does not change the separate primary `/api/documents` model.

## Invariants

- The authenticated principal is resolved server-side. Requests never contain an owner identifier.
- Every newly ingested `NormalizedDocument` has an active, non-deleted `User` owner.
- Metadata, text, structure reads/builds, and extraction execute through `NormalizedDocumentAccessService`.
- The service locks the document and loads only the bounded owner-decision fields in one transaction before invoking the existing document-processing implementation.
- Missing documents, wrong owners, deleted owners, and legacy rows with no owner all return the same `404 Document not found` response.
- Existing paths and successful DTOs are unchanged.
- There is no administrator or compatibility bypass. Future sharing requires a separate explicit visibility policy.

## Data Policy

Migration `V74__add_normalized_document_owner.sql` adds nullable `normalized_documents.owner_id`, its lookup index, and a foreign key to `users.user_id` with `ON DELETE SET NULL`.

Nullability is intentional for rollout compatibility. Existing rows have no trustworthy owner evidence, remain null, and are quarantined by the application. Do not infer ownership from filenames, titles, access history, or another document table. New application writes always assign the authenticated owner.

Physical user deletion sets `owner_id` to null, so retained document content becomes inaccessible. Soft-deleted and inactive owners are also denied. Deleting quarantined content requires a separately approved retention/backup procedure.

## Bounded Multipart Ingestion

Multipart uploads use the same `DocumentUploadStagingService` and singleton `DocumentParseExecutor` as `/api/documents`. Files are streamed to server-owned staging, checked against the shared size/type/resource limits, and parsed under the same global and per-owner admission budget. The controller never calls `MultipartFile.getBytes()`.

Parsing and normalization run with transactions forbidden. Publication then re-locks the active owner and inserts the normalized document in one short transaction, so owner deletion cannot race an unowned row into the database. A parse or publication failure leaves no partial normalized row; staging cleanup is idempotent and eligible for the existing reconciliation policy.

## Concurrency

Owner authorization uses a native MySQL `FOR SHARE` query that returns only owner username/active/deleted fields. It does not materialize normalized document text for a denied or length-only request. The lock remains held through the delegated read or structure operation. A concurrent owner update/deletion must therefore complete before authorization starts or wait until the authorized operation ends; the next operation sees the new owner state and denies access.

The owner decision is fetched in one authorization statement, regardless of how many other documents exist. Authorized content is loaded only by the requested operation. Structure node queries remain bounded by the existing document-level queries; no list endpoint was added.

## Privacy And Observability

The metric `document.normalized.access` uses only the bounded `outcome` tag:

- `authorized`
- `unauthenticated`
- `owner_denied`
- `legacy_denied`
- `dependency_failure`

Document IDs and user IDs are not metric labels. Telemetry failure never changes an authorization decision.
An outer HTTP filter records `unauthenticated` when the security chain returns `401` for this route; authenticated outcomes remain owned by the application service. The filter matches only the exact normalized-document path prefix and does not inspect or label request content.

Normalized-document processing must not log or expose filenames, source text, section titles, anchors, document previews, usernames, paths, prompts, raw AI responses, or exception messages that may contain those values. Operational logs may contain opaque document IDs, sizes, offsets, counts, and bounded outcomes.

## Failure Behaviour

| Condition | Result |
|---|---|
| No valid authentication | Security filter returns `401` |
| Missing document | Non-enumerating `404` |
| Wrong owner | Same `404` |
| Null/legacy owner | Same `404` and `legacy_denied` metric |
| Inactive/deleted owner | Same `404` |
| Database unavailable | Existing dependency error handling; no authorization fallback |
| AI/conversion failure | Existing status, with sanitized logs and client detail |
| Metrics unavailable | Operation continues with the original authorization result |

## Deployment And Rollback

Apply V74 before starting the new application. The migration is additive and old binaries ignore the nullable column, but old binaries do not enforce this security boundary.

After the owner-aware version has served traffic, do not roll back to a pre-V74 application by itself: that would make UUID-only reads possible again and new rows created by the old binary would be unowned. Use one of these rollback paths:

1. Deploy a compatibility build that retains `NormalizedDocumentAccessService` while reverting the unrelated failing change.
2. Temporarily block `/api/v1/documentProcess/documents/**` at the reverse proxy, then roll back and restore the owner fix before reopening the route.

Do not drop `owner_id` or backfill legacy rows during an application rollback. The forward migration preserves quarantined state and permits a corrected build to resume safely.

## Offline Testing

Tests use repository doubles, a fake `LlmClient` failure, MockMvc, Micrometer's in-memory registry, and local MySQL. They never contact OpenAI or another external service. No document-content hashing is used for ownership or version control.
