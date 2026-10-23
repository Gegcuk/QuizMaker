# Normalized Document Ownership

## Scope

This boundary protects `/api/v1/documentProcess/documents` and all derived metadata, normalized text, structure, and node extraction. It does not change the separate primary `/api/documents` model.

## Invariants

- The authenticated principal is resolved server-side. Requests never contain an owner identifier.
- Every newly ingested `NormalizedDocument` has an active, non-deleted `User` owner.
- Metadata, text, structure reads/builds, and extraction execute through `NormalizedDocumentAccessService`.
- Short reads lock the document and load only bounded owner-decision fields in one transaction. Structure builds use short source capture and publication transactions; AI and level preparation run with transactions forbidden.
- Missing documents, wrong owners, deleted owners, and legacy rows with no owner all return the same `404 Document not found` response.
- Existing paths and successful DTOs are unchanged.
- There is no administrator or compatibility bypass. Future sharing requires a separate explicit visibility policy.

## Data Policy

Migration `V74__add_normalized_document_owner.sql` adds nullable `normalized_documents.owner_id`, its lookup index, and a foreign key to `users.user_id` with `ON DELETE SET NULL`.

Nullability is intentional for rollout compatibility. Existing rows have no trustworthy owner evidence, remain null, and are quarantined by the application. Do not infer ownership from filenames, titles, access history, or another document table. New application writes always assign the authenticated owner.

Physical user deletion sets `owner_id` to null, so retained document content becomes inaccessible. Soft-deleted and inactive owners are also denied. Deleting quarantined content requires a separately approved retention/backup procedure.

[Q90's approved target](decision-register.md#personal-source-retention) retains durable personal source documents, including paid transcripts, without age-based cleanup and requires authorized owner/account erasure of the independently eligible personal-source scope within 24 hours after prerequisites. Preserve separately protected shared/formal evidence, resolve disputed ownership/dependencies and apply Q65's independent backup rule. Temporary ingestion files keep their separate lifecycle.

This ownership implementation does not yet perform that physical erasure: `ON DELETE SET NULL` and access denial can retain source text. Deletion orchestration must establish authorized scope before losing ownership/dependency evidence; do not infer ownership for quarantined legacy rows. The approved target does not grant document-sharing permissions. Implementation preparation remains tracked in [open questions: normalized documents](open-questions.md#oq-documents).

## Bounded Multipart Ingestion

Multipart uploads use the same `DocumentUploadStagingService` and singleton `DocumentParseExecutor` as `/api/documents`. Files are streamed to server-owned staging, checked against the shared size/type/resource limits, and parsed under the same global and per-owner admission budget. The controller never calls `MultipartFile.getBytes()`.

Parsing and normalization run with transactions forbidden. Publication then re-locks the active owner and inserts the normalized document in one short transaction, so owner deletion cannot race an unowned row into the database. A parse or publication failure leaves no partial normalized row; staging cleanup is idempotent and eligible for the existing reconciliation policy.

## Concurrency

Owner authorization uses a native MySQL `FOR SHARE` query that returns only owner username/active/deleted fields. It does not materialize normalized document text for a denied or length-only request. For current delegated reads the lock remains until the read completes. Structure building releases the initial authorization transaction before external AI. Each chunk dispatch and each physical retry in the OpenAI structure adapter rechecks current ownership and the captured source version in a separate short transaction. Publication locks the owner, then the document row, rechecks access/version, and commits the complete tree atomically.

The owner decision is fetched in one authorization statement, regardless of how many other documents exist. Authorized content is loaded only by the requested operation. Structure node queries remain bounded by the existing document-level queries; no list endpoint was added.

### Required long-operation transaction boundary

1. In a short transaction, authorize the active owner and capture the source once, its owner identity and the persisted `source_version`. That version is an optimistic publication claim; it is not a lease or a publicly visible processing status.
2. Perform bounded source processing and provider calls outside database transactions; no connection/owner lock is held during retry/backoff.
3. In a short transaction, lock/recheck the active owner, acquire the document write lock before the shared authorization read, and compare the captured version. A conditional metadata update advances `source_version` and status together with all node inserts. A stale/unauthorized result cannot publish nodes or overwrite another build's committed tree.

Account deactivation/deletion must be able to commit while the fake provider is blocked. An already-authorized in-flight provider request cannot be retroactively unsent; after revocation no new dispatch/publication is authorized. Compare persisted bounded versions, not a hash of complete document text.

Migration `V83__add_normalized_document_source_version.sql` adds a non-null `BIGINT` counter defaulting to zero. Existing content/status/owners are preserved. JPA updates increment the counter; publication compares and advances it without rereading `LONGTEXT`. Source updates outside JPA must preserve this invariant too. Keep the additive column when rolling application code back; a forward fix is preferred over dropping version evidence.

Concurrent callers may prepare the same captured source, as before, but only one can publish it. The loser receives the existing sanitized `400` / `StructureBuildResponse` failure envelope. There is no new automatic retry, claim timeout, provider-attempt limit or recovery scheduler. A failed pre-commit publication rolls back nodes, status and version, allowing an explicit first-build retry. After a committed success or a lost success response, a fresh read is authoritative; repeating build still refuses a `STRUCTURED` document before any further provider call.

The current public build accepts only `NORMALIZED` documents. `STRUCTURED` is rejected before AI; the current API provides no reset/rebuild operation. Preserve that status guard and atomic first publication. The internal deletion of existing nodes in `StructureService` does not establish a public replacement capability.

Tests go through `NormalizedDocumentAccessService`, commit fixtures before worker threads, propagate worker exceptions and read state in a new transaction after failure. At least one build must succeed in the valid concurrent-build fixture; “both failed and no nodes” is not success. Keep these separate proofs:

- **First-build rollback:** start with a committed active-owned `NORMALIZED` document and no published nodes. Separately fail processing at a later depth and fail publication after real inserts/flush but before commit. Prove each fault checkpoint was reached. Fresh reads show no partial tree or `STRUCTURED` success, with the original public source/status preserved. Processing failure alone does not prove rollback of database writes; internal claim bookkeeping is not public completion.
- **Repeat-build compatibility:** complete the first build, then request build again for its `STRUCTURED` document. Preserve the existing refusal, zero additional provider calls and the unchanged committed tree/status. This guard test cannot satisfy the rollback case.

An optional internal `NORMALIZED` fixture with existing nodes needs a concrete reachable origin and a separately named invariant; it cannot justify introducing public rebuild. Mock `saveAll` counts and self-invoked `REQUIRES_NEW` do not prove durability. A failure after commit needs outcome reconciliation, not an expectation that committed nodes disappear. No public partial-success policy is introduced.

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

Apply V74 and V83 before starting the new application. The migration is additive and old binaries ignore the nullable column, but old binaries do not enforce this security boundary.

After the owner-aware version has served traffic, do not roll back to a pre-V74 application by itself: that would make UUID-only reads possible again and new rows created by the old binary would be unowned. Use one of these rollback paths:

1. Deploy a compatibility build that retains `NormalizedDocumentAccessService` while reverting the unrelated failing change.
2. Temporarily block `/api/v1/documentProcess/documents/**` at the reverse proxy, then roll back and restore the owner fix before reopening the route.

Do not drop `owner_id` or backfill legacy rows during an application rollback. The forward migration preserves quarantined state and permits a corrected build to resume safely.

## Offline Testing

Tests use repository doubles, a fake `LlmClient` failure, MockMvc, Micrometer's in-memory registry, and local MySQL. They never contact OpenAI or another external service. No document-content hashing is used for ownership or version control.


`NormalizedDocumentStructureBuildMySqlIntegrationTest` proves transaction-free provider dispatch, owner mutation during provider wait, source/claim conflicts, a successful concurrent winner, real first-build rollback, and repeat-build refusal after a fresh persistence unit and service restart. Its multiple-document fixture measures the tree and flat reads at 21 SQL statements for three documents with either one or eight parent groups (three levels each); statement count does not grow with those relationships. Source capture is the only full-text SELECT in a successful build. The real OpenAI adapter is also composed with the real ownership transactions and a fake ChatModel: allowed processing succeeds, while revocation after its first failed call prevents another physical dispatch; retry limits and backoff settings are unchanged.

`NormalizedDocumentSourceVersionMigrationTest` applies the actual V83 Flyway migration to a connection-local MySQL table and proves legacy content/status preservation and non-null zero defaults. `DocumentStructureOpenApiContractTest` covers the full/group specifications, named success/failure envelopes, examples and discovery. `StructureServiceIntegrationTest` checks the real HTTP security, owner-only success, wrong-owner denial and unchanged repeat-build refusal using a fake provider.
