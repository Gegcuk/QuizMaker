# Document Ingestion Observability

## Purpose And Compatibility

Document upload, conversion, chunking, publication, compensation, cleanup, and storage reconciliation emit privacy-safe Micrometer signals. These signals do not change the public document or quiz-generation APIs, response bodies, status codes, permissions, storage layout, or database schema.

Metrics are operational data. Production exposes Actuator only on container loopback `127.0.0.1:8081`; endpoints other than the status-only health groups require `SYSTEM_ADMIN`. Do not proxy `/actuator/metrics` through the public web server.

## Signal Catalog

| Metric | Type and unit | Allowed tags | Interpretation |
| --- | --- | --- | --- |
| `document.ingestion.events` | Counter, events | `stage`, `outcome`, `reason` | Terminal result for one bounded ingestion/storage stage. |
| `document.ingestion.duration` | Timer, seconds | `stage`, `outcome` | Wall-clock stage duration. |
| `document.ingestion.active` | Gauge, requests | none | Upload or reprocess operations currently past staging and inside processing. |
| `document.ingestion.extracted.characters` | Distribution summary, characters | `format` | Extracted text size after successful conversion. |
| `document.ingestion.pages` | Distribution summary, pages | `format` | Positive page count reported after successful conversion. Text/EPUB inputs without a page count do not emit it. |
| `document.storage.reconciliation.candidates` | Distribution summary, files | none | Expired published-file candidates inspected by one reconciliation run. |

The timer and distribution summaries publish fixed service-level buckets. Backend-specific histogram labels such as `le` are created by Micrometer/exporters and are not application-controlled dimensions.

### Allowed tag values

- `stage`: `validation`, `staging`, `conversion`, `chunking`, `promotion`, `publication`, `processing`, `compensation`, `cleanup`, `reconciliation`.
- `outcome`: `accepted`, `rejected`, `succeeded`, `failed`, `skipped`.
- `reason`: `none`, `invalid_input`, `upload_size`, `type_mismatch`, `resource_limit`, `timeout`, `capacity`, `storage`, `processing`, `persistence`, `not_found`, `access_denied`, `cleanup`, `unknown`.
- `format`: `pdf`, `epub`, `text`, `unknown`.

Not every Cartesian combination is emitted. Examples include `validation/accepted/none`, `conversion/rejected/timeout`, `promotion/failed/storage`, and `compensation/failed/cleanup`.

Parser child-process lifecycle remains available through `document.parser.workers.active` and `document.parser.worker.events`; see [Document Parser Isolation](document-parser-isolation.md).

## Privacy And Cardinality

Document telemetry never records:

- document content, prompts, extracted text, chapter or section titles;
- original filenames, storage paths, or exception messages;
- usernames, email addresses, owner IDs, document IDs, quiz IDs, or other unbounded identifiers;
- credentials, tokens, request payloads, or provider responses.

Application logs use fixed operation, stage, outcome, count, size, and bounded reason fields. Request correlation may be supplied by the platform's access-log or tracing context, but feature logs must not add a document/user identifier. Do not add a metric label without updating the bounded-label and privacy tests.

## Failure Semantics

Telemetry is behind `DocumentIngestionMetrics`. The primary application adapter contains Micrometer failures, logs one fixed warning without exception details, and never changes a document request, transaction, cleanup decision, or retry result.

Outcome meanings are:

- `accepted`: boundary validation accepted the request.
- `rejected`: the request was intentionally denied by validation, size/type/resource, timeout, capacity, not-found, or access rules.
- `succeeded`: the stage completed its intended effect.
- `failed`: storage, parsing, persistence, cleanup, or another operational failure prevented the intended effect.
- `skipped`: reserved for an explicitly inapplicable stage; it must not be used to hide a failure.

Timeout remains externally compatible with the existing HTTP `422` `document-resource-limit-exceeded` response. The typed internal timeout exception exists only to distinguish the operational reason.

## Operator Triage

1. Check `document.ingestion.events` by `stage`, `outcome`, and `reason` over the incident window.
2. Check `document.ingestion.active`, conversion/processing duration, and parser-worker metrics together. Capacity rejection with sustained active workers indicates saturation; capacity rejection with no active workers indicates a permit/lifecycle defect.
3. For `promotion/failed/storage`, check free space, inode availability, mount ownership, and write access under `DOCUMENT_STORAGE_ROOT`. Do not inspect or publish filenames.
4. For `publication/failed/persistence`, check private readiness and database errors. A matching `compensation/succeeded/none` means the promoted file was removed; `compensation/failed/cleanup` means reconciliation owns recovery.
5. For `reconciliation/failed/cleanup`, inspect storage permissions and the candidate distribution. Follow [Document Storage Reconciliation](document-storage-reconciliation.md).
6. For timeout/resource-limit growth, compare extracted-size/page distributions with configured limits before changing capacity. Do not increase parser heap, upload size, and concurrency together without a host-memory review.

## Alert Guidance

Calibrate final thresholds from production traffic before paging. Start with these symptom-based signals:

- Page on any sustained `compensation/failed/cleanup` combined with a failed reconciliation run, because automatic recovery is unavailable.
- Alert on sustained `promotion/failed/storage` or `publication/failed/persistence` rates relative to processing starts.
- Alert when `capacity` rejection persists across the normal maximum conversion duration, or when the active gauge does not return to zero after traffic stops.
- Alert when timeout/resource-limit rejection changes materially from its established baseline; these may indicate hostile inputs, a content shift, or insufficient limits.
- Alert when reconciliation candidate counts grow across multiple retention windows or reconciliation duration approaches its configured interval.

Every alert should identify this runbook, its owner, evaluation window, and recovery signal. Do not include filenames, paths, users, documents, or raw exception text in alert annotations.

## Query Bound

Instrumentation adds no repository call. Reconciliation retains its existing bound: one batch lookup per 250 candidates plus one final authoritative lookup for each apparent orphan. Live referenced files do not cause per-file queries. Metrics are recorded from already available counts and outcomes and never query business data.
