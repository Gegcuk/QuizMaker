# Document Parser Isolation Runbook

## Purpose and compatibility

PDF, EPUB, and text conversion runs in a short-lived child JVM so a timed-out parser can be terminated without stopping the backend. The public upload, reprocess, and quiz-generation endpoints, DTOs, permissions, and `ProblemDetail` status codes are unchanged.

Converter selection remains backward compatible. Canonical MIME types, legacy aliases (`text/txt`, `application/epub`, and `application/x-epub`), and filename-extension fallback continue to select the same PDF, EPUB, or text converter in the same order.

## Process lifecycle

1. The parent acquires the existing global and per-owner permits.
2. It validates the server-owned source path and copies the bounded input into a random owner-only directory under `<DOCUMENT_STORAGE_ROOT>/.parse-workers`.
3. It writes a private protocol-v1 request and starts the same application artifact as a child JVM with a fixed `-Xmx` limit. The child inherits no environment variables, standard input is closed, and standard output/error are discarded.
4. The child validates the protocol and private input, converts exactly one document, and writes an atomic size-bounded response.
5. The parent accepts only a protocol-v1 response whose filename, size, converter, content type, text size, chapters, and page count agree with its request and server limits.
6. The parent removes the operation directory and returns permits only after the child process is confirmed dead.

On timeout, the parent sends a graceful termination request, waits the configured grace period, then uses forced process termination. If termination cannot be confirmed, capacity stays unavailable and the low-cardinality kill-failure signal is emitted. A daemon reaper returns capacity only after a later confirmed exit.

During application shutdown, admission stops first. Existing workers receive graceful termination, then forced termination, within the configured shutdown budget. Each worker also monitors its parent PID and halts if the parent disappears. Crash leftovers are eligible for owned-directory cleanup after `DOCUMENT_STAGING_RETENTION`; unrelated directories are never removed.

## Resource settings

| Environment variable | Default | Meaning |
| --- | ---: | --- |
| `DOCUMENT_PARSE_TIMEOUT` | `PT60S` | Wall-clock conversion budget before termination starts. |
| `DOCUMENT_PARSER_WORKER_MAX_HEAP_BYTES` | `402653184` | Maximum heap for each parser child JVM (384 MiB). |
| `DOCUMENT_PARSER_WORKER_MAX_OUTPUT_BYTES` | `16777216` | Maximum protocol response size (16 MiB). |
| `DOCUMENT_PARSER_TERMINATION_GRACE` | `PT1S` | Time allowed after graceful termination. |
| `DOCUMENT_PARSER_FORCE_KILL_TIMEOUT` | `PT5S` | Time allowed to confirm forced termination. |
| `DOCUMENT_PARSER_SHUTDOWN_TIMEOUT` | `PT10S` | Total backend shutdown budget for active parser workers. |
| `DOCUMENT_MAX_CONCURRENT_PARSES` | `2` | Maximum active parser processes across all users. |
| `DOCUMENT_MAX_CONCURRENT_PARSES_PER_USER` | `1` | Maximum active parser process for one owner key. |
| `DOCUMENT_STAGING_RETENTION` | `PT24H` | Retention before private crash-leftover parser workspaces are eligible for cleanup. |

Durations must be positive. Worker heap must not be lower than `DOCUMENT_MAX_PDF_MAIN_MEMORY_BYTES`, and worker output must not be lower than `DOCUMENT_MAX_EXTRACTED_CHARACTERS`. Invalid combinations stop application startup.

The maximum resident memory is not exactly `-Xmx`: each child also uses JVM native memory and mapped libraries. Size the host/container for the backend plus `DOCUMENT_MAX_CONCURRENT_PARSES` workers. Do not raise concurrency and heap independently without checking available memory and swap behavior.

## Failure contract

| Failure | Existing external outcome | Capacity/files |
| --- | --- | --- |
| No global/per-owner permit | HTTP `503`, `document-processing-capacity-exceeded` | No worker or operation directory. |
| Spawn/input-workspace failure | Existing safe document-processing failure | Permit returned; owned partial directory removed. |
| Parse timeout | HTTP `422`, `document-resource-limit-exceeded` | Returned only after confirmed worker exit. |
| Worker heap exhaustion | HTTP `422`, `document-resource-limit-exceeded` | JVM exits; owned directory and permit reclaimed. |
| Malformed, oversized, or incompatible output | Existing safe document-processing failure | Output rejected; no document published; worker discarded. |
| Converter type mismatch | Existing HTTP `415` type-mismatch contract | No document published; worker discarded. |
| Worker crash | Existing safe document-processing failure | Owned directory and permit reclaimed after exit. |
| Forced kill cannot be confirmed | Existing safe document-processing failure | Permit intentionally retained until exit is observed. |
| Parent disappears | Child halts itself | Crash directory is removed by retention cleanup. |

No worker failure publishes a new document or replaces existing reprocess chunks.

## Metrics

Use the private Actuator metrics endpoint. Metric names are:

- `document.parser.workers.active`: current child-process count.
- `document.parser.worker.events`: counter tagged only with bounded `outcome` values: `capacity_rejected`, `spawn_failed`, `succeeded`, `processing_failed`, `process_crashed`, `invalid_output`, `incompatible_protocol`, `timed_out`, `forced_kill`, `kill_failed`, and `interrupted`.

No filename, path, owner, document content, exception message, or identifier is a tag. Alert immediately on `kill_failed`; investigate sustained `forced_kill`, `timed_out`, or `spawn_failed` growth and an active gauge that does not return to zero after traffic stops.

## Privacy and runtime boundary

Protocol files and the input copy are owner-only and use random operation paths. Child commands contain no filename, source path, owner, or document text. Environment clearing prevents inherited database, JWT, OpenAI, Stripe, email, and storage credentials. The production container runs the parent and children as non-root and does not mount the Docker socket. Converters use local streams only and automated tests require no network.

PDF workers use PDFBox's bundled fallback font for non-embedded fonts. Embedded PDF fonts continue to be read from the document, while the one-shot child does not scan or cache every font installed on the host. This keeps memory behavior deterministic across local macOS, CI Linux, and the Alpine production image.

This is a resource/lifecycle boundary around trusted QuizMaker worker code, not a general sandbox for arbitrary executable code. The child shares the container UID and network namespace. [Issue #733](https://github.com/Gegcuk/QuizMaker/issues/733) owns OS-enforced syscall, filesystem, process-namespace, and network isolation; do not load user-supplied code or parser plugins into this worker.

## Troubleshooting

1. Check `document.parser.worker.events` by outcome and `document.parser.workers.active`.
2. Check free memory, disk space under `DOCUMENT_STORAGE_ROOT`, and process limits. Do not log or inspect private request/response files in shared support channels.
3. List only operation directory names: `find "${DOCUMENT_STORAGE_ROOT:-uploads/documents}/.parse-workers" -maxdepth 1 -type d -name 'parse-*' -print`.
4. If old operation directories remain after a crash, verify `DOCUMENT_STAGING_RETENTION` and filesystem ownership before cleanup. Never delete unrelated upload or published directories.
5. A protocol mismatch after a rolling deploy indicates mixed or unexpected artifacts. Verify the deployed application SHA; protocol v1 is internal and old workers should die with their old parent.

## Rolling deployment

The parser protocol is internal and versioned. A child is owned by exactly one backend PID. During replacement, the old backend drains or kills its workers; if it disappears, their parent monitors halt them. The new backend ignores fresh operation directories and removes only expired managed leftovers, so it does not consume another version's response.
