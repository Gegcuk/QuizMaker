Introduction & Context

## Service Architecture & Integration

This chunk process service is designed as a **separate documentProcess feature** that operates independently from existing services. It follows these key principles:

- **No Impact on Existing Services**: This service doesn't change anything in existing services or features. It's completely self-contained.
- **Reuses Existing Configuration**: The service can leverage existing config files, primarily for AI-related settings (API keys, rate limits, model configurations) also it uses the same database.
- **Document Converter Reuse**: It can copy and adapt document converters from the existing document feature to maintain consistency and avoid duplication.

## Implementation Approach

The service maintains isolation by:
- Using its own feature-based package structure (`features/documentProcess/`)
- Having dedicated database tables (`documents`, `document_nodes`)
- Implementing its own API endpoints under `/api/v1/documentProcess/`
- Maintaining separate service boundaries while potentially sharing configuration

---

## Goal

Build a small, reliable Spring Boot + MySQL feature that:

- accepts a file or raw text,
- converts (if needed) to plain text,
- normalizes it to a canonical form with stable character offsets,
- stores it, and
- (Phase 2) asks an LLM to produce a structure tree with absolute offsets so you can extract any section precisely.

## Design Choices (MVP-friendly)

- **Feature-based packages** (conversion, document, structure). Keeps layers small and swappable.
- A single table (`documents`) in Phase 1; a second table (`document_nodes`) only when you add structure in Phase 2.
- `application.properties` for toggles; keep config minimal.
- In Phase 2, start with single-pass AI (no chunking yet). You can bolt on chunking later if you hit context limits.

## Day 1 — Project skeleton, entities, normalization (Phase 1 foundation)

### What you'll implement

- Feature folders
- `documents` table (Flyway V1)
- `NormalizationService` (pure function)
- Minimal `DocumentConversionService` with a TXT passthrough converter
- `DocumentIngestionService` (wire convert→normalize→persist)

### Why these pieces?

#### NormalizationService
- **Purpose**: create stable offsets so any later structure (chapters/sections) can refer to exact [start,end) positions.
- Keeps rules in one place (line endings, spaces, hyphenation), making results deterministic.

#### DocumentConversionService (+ DocumentConverter)
- **Purpose**: a tiny strategy/factory that converts file bytes to text.
- MVP: start with `TxtPassthroughConverter`; later drop in `PdfBoxDocumentConverter`, `EpubDocumentConverter`, etc. The interface doesn't change.

#### DocumentIngestionService
- **Purpose**: glue for ingest flow. Accepts text/file, calls conversion (if file), calls normalization, saves to DB.
- Thin orchestration keeps controllers dumb and testable.

### Tasks

#### Folders

```
features/
  conversion/{application,domain,infra}
  document/{api,application,domain,infra}
common/error
```

#### Flyway V*__init_documents.sql

```sql
CREATE TABLE documents (
  id BINARY(16) PRIMARY KEY,
  original_name VARCHAR(255),
  mime VARCHAR(100),
  source ENUM('UPLOAD','TEXT') NOT NULL,
  language VARCHAR(32),
  normalized_text LONGTEXT,
  char_count INT,
  status ENUM('PENDING','NORMALIZED','FAILED') NOT NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);
CREATE INDEX ix_doc_created ON documents(created_at);
```

#### application.properties

```properties
spring.servlet.multipart.max-file-size=25MB
spring.servlet.multipart.max-request-size=25MB
docproc.normalization.dehyphenate=true
docproc.normalization.collapse-spaces=true
```

#### NormalizationService

**Rules**: `\r\n|\r→\n`, collapse 2+ spaces→1 (config), NFC unicode, remove zero-width/BOM, safe de-hyphenate across line breaks, normalize quotes/dashes (optional).

Returns `NormalizationResult(text, charCount)`.

#### Conversion service

- `DocumentConverter` (interface): `supports(filenameOrMime)`, `convert(bytes)`.
- `TxtPassthroughConverter` only (for now).
- `DocumentConversionService.convert(originalName, bytes)` chooses a converter (simple by extension/MIME) and returns `ConversionResult(text)`.

#### Ingestion service

- `ingestFromText(originalName, language, text)`
- `ingestFromFile(originalName, bytes)` (calls conversion)
- Persists `DocumentEntity` → `status=NORMALIZED`.

### Acceptance checks (end of Day 1)

- Unit tests for normalization (line endings, spaces, de-hyphenation).
- Save a plain TXT; verify `char_count` and `status=NORMALIZED`.

## Day 2 — Controller & DTOs for Phase 1 (ingest + read)

### What you'll implement

- `DocumentProcessController` with 3 endpoints:
  - `POST /api/v1/documentProcess/documents`
  - `GET /api/v1/documentProcess/documents/{id}`
  - `GET /api/v1/documentProcess/documents/{id}/text?start=&end=`
- DTOs for requests/responses
- `DocumentQueryService` for safe reads/slices

### Why these pieces?

#### Controller (one class)
- **Purpose**: tiny HTTP boundary. Keeps business logic in services.

#### DTOs
- **Purpose**: explicit contracts; easy to evolve without breaking internals.

#### DocumentQueryService
- **Purpose**: read-only accessor for full text and slices (bounds checking in one spot).

### DTOs you'll add

- `IngestRequest` { text, language } (JSON variant; multipart uses file)
- `IngestResponse` { id, status }
- `DocumentView` { id, originalName, mime, source, charCount, language, status, createdAt, updatedAt }
- `TextSliceResponse` { documentId, start, end, text }

### Acceptance checks

- `POST /documents` with JSON text → 201 + id.
- `POST /documents` with .txt file → 201.
- `GET /documents/{id}` returns metadata.
- `GET /documents/{id}/text?start=0&end=100` returns slice matching `normalized_text.substring(0,100)`.

*(My opinion: keeping Day 1–2 this small is perfect. You already have a useful text store with safe slicing.)*

## Day 3 — Add optional converters (PDF/EPUB/HTML/SRT), polish errors

### What you'll implement

- `PdfBoxDocumentConverter`, `EpubDocumentConverter`, `HtmlDocumentConverter`, `SrtVttDocumentConverter` (stubs or basic)
- A lightweight MIME/extension detector
- `GlobalExceptionHandler` to return consistent `ApiError` codes:
  - `UNSUPPORTED_FORMAT`, `CONVERSION_FAILED`, `NORMALIZATION_FAILED`, `NOT_FOUND`, `VALIDATION_ERROR`

### Why these pieces?

- Converters now = zero later refactors. You keep the same ingest flow; only the factory grows.
- Error handler = predictable API for callers; easier debugging.

### Acceptance checks

- Upload a small PDF/EPUB/HTML/SRT, confirm it converts (or returns a clean `UNSUPPORTED_FORMAT` if not implemented yet).
- Bad range on `/text?start=&end=` returns `VALIDATION_ERROR`.

## Day 4 — Phase 2 begins: structure table + contracts (no AI yet)

### What you'll implement

- Flyway V2: `document_nodes` table
- DTOs and endpoints for reading structure (empty list for now)
- Skeleton `StructureService` with no AI logic (returns empty/specimen)

### Why these pieces?

#### document_nodes table
- **Purpose**: enables offset-based structure without touching documents. It's future-proof and lets you ship the read endpoints now.

#### StructureService (interface)
- **Purpose**: future home for AI build; today it just fetches/presents nodes.

### Tasks

#### Flyway V2__structure.sql

```sql
CREATE TABLE document_nodes (
  id BINARY(16) PRIMARY KEY,
  document_id BINARY(16) NOT NULL,
  parent_id BINARY(16),
  idx INT NOT NULL,
  type ENUM('BOOK','CHAPTER','SECTION','SUBSECTION','PARAGRAPH','UTTERANCE','OTHER') NOT NULL,
  title VARCHAR(512),
  start_offset INT NOT NULL,
  end_offset INT NOT NULL,
  depth SMALLINT NOT NULL,
  ai_confidence DECIMAL(4,3),
  meta_json JSON,
  FOREIGN KEY (document_id) REFERENCES documents(id) ON DELETE CASCADE,
  FOREIGN KEY (parent_id) REFERENCES document_nodes(id) ON DELETE CASCADE,
  KEY ix_doc_start (document_id, start_offset),
  KEY ix_parent_idx (document_id, parent_id, idx)
);
```

#### Read endpoints (same controller or new):

- `GET /api/v1/documentProcess/documents/{id}/structure?format=tree|flat`
- `GET /api/v1/documentProcess/documents/{id}/extract?start=&end=` (node-based extract arrives Day 6)

#### DTOs

- `NodeView` (tree)
- `FlatNode` (flat)
- `StructureTreeResponse` / `StructureFlatResponse`

#### StructureService (read-only)

- `getTree(documentId)`
- `getFlat(documentId)`

### Acceptance checks

- Returns `[]` for new docs; still works without AI.

## Day 5 — Single-pass AI structure (the smallest useful step)

### What you'll implement

- `POST /api/v1/documentProcess/documents/{id}/structure` to build structure once
- Minimal `LlmClient` and prompt (single pass, no chunking)
- Persist nodes, set document status to `STRUCTURED`

### Why these pieces?

- Single-pass AI gets you to value fast. No chunking complexity yet. For big docs, fail fast with a nice error (you'll add chunking later if needed).
- `LlmClient` abstraction keeps OpenAI specifics isolated; you can test logic without the network.

### Tasks

#### application.properties

```properties
docproc.ai.default-model=gpt-4o-mini
docproc.ai.headroom-ratio=0.25
```

#### StructureService (write path)

`buildStructure(documentId, options)`:

1. Fetch normalized text.
2. Build system/user prompts (include simple profile/granularity hints if you like).
3. Call `LlmClient.structuredJson(...)`.
4. Validate nodes: 0 ≤ start < end ≤ char_count; non-overlapping siblings; parent contains child.
5. Persist nodes (idx, depth).
6. Update document status to `STRUCTURED`.

#### Endpoint

- `POST /documents/{id}/structure` with optional { model, profile, granularity }.
- Response { status: "STRUCTURED" }.

#### DTO

- `StructureOptions` in service layer (not exposed directly unless you want).

### Acceptance checks

- Use a short article; AI returns a tree; nodes persisted; `GET /structure` shows the tree.

*(My opinion: keep temperature low, enforce JSON with a strict schema, and reject on schema violations. That alone gives you solid, repeatable output.)*

## Day 6 — Node-based extraction & finishing touches

### What you'll implement

- `GET /api/v1/documentProcess/documents/{id}/extract?nodeId=...`
- `StructureService.extractByNode(documentId, nodeId)` returns { text, start, end, title }
- Guardrails, small QA utilities

### Why these pieces?

- Extraction by node is the first real "payoff": you can grab any part exactly via offsets and stream it to downstream features (question generation, summarization, etc.).

### Tasks

#### DTO

- `ExtractResponse` { documentId, nodeId?, title?, start, end, text }

#### Service methods

- `extractByNode(documentId, nodeId)`
- (You already have `extractByOffsets` via Day 2 slice endpoint.)

#### Guardrails

- Return 404 if node/document mismatch or node not found.
- Enforce [start,end) within bounds.

### Acceptance checks

- Build structure on a short text.
- Pick a nodeId from `/structure?format=flat`; call `/extract?nodeId=...`; verify content matches `normalized_text.substring(start,end)`.

## Day 7 — (Optional) Quality polish & docs

### What you'll implement

- Basic coverage check (e.g., total covered characters / charCount)
- Minimal README for the feature
- A couple of Postman (or HTTPie) examples

### Why these pieces?

- Coverage quickly shows if your AI skipped big spans; useful for debugging.
- Docs help future you (and future AI chunking work).

## Final list of services & why they exist

### DocumentConversionService
**Why**: clean boundary to support many file formats later without touching controllers or ingestion logic.

### NormalizationService
**Why**: ensures deterministic offsets across all content types; foundational for precise extraction.

### DocumentIngestionService
**Why**: orchestration of convert→normalize→persist; keeps a single entry point for new docs.

### DocumentQueryService
**Why**: centralized, safe read access (full text, slices) with bounds checks.

### StructureService
**Why**: one place that (a) runs AI once (single-pass MVP) and (b) reads the resulting tree/flat lists and extracts by node.

### LlmClient
**Why**: isolates model/API specifics; easy to mock in tests; future-proof if you change providers.

## Final list of DTOs & why they exist

### IngestRequest / IngestResponse
**Why**: clean API for creating documents (text or file).

### DocumentView
**Why**: stable metadata contract for status pages/UIs.

### TextSliceResponse
**Why**: offset slicing guarantees; easy client consumption.

### NodeView / StructureTreeResponse / StructureFlatResponse
**Why**: two ways to browse structure depending on client UX.

### ExtractResponse
**Why**: the money shot: exact content for a node or range.

### ApiError
**Why**: consistent error surface for clients; simplifies frontend logic.