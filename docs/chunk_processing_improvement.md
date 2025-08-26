# Document Chunk Processing Improvement Plan

## Overview

This document outlines a comprehensive 10-day plan to improve document chunk processing by introducing a thin,
AI-agnostic structure layer without touching existing `document_chunks`.

## Day 1 — Data Model + Migrations + Contracts

**Goal**: Introduce a thin, AI-agnostic structure layer without touching existing document_chunks.

### Database (Flyway/Liquibase or SQL migration)

New table `document_nodes` (thin "coordinates of chunks"):

```sql
CREATE TABLE document_nodes
(
    id                  UUID PRIMARY KEY,
    document_id         UUID        NOT NULL REFERENCES documents (id),
    parent_id           UUID REFERENCES document_nodes (id),
    level               SMALLINT    NOT NULL, -- 0=doc, 1=part, 2=chapter, 3=section, 4=subsection, 5=paragraph
    type                VARCHAR(32) NOT NULL, -- enum-like: DOCUMENT|PART|CHAPTER|SECTION|SUBSECTION|PARAGRAPH|OTHER
    title               TEXT,
    start_offset        INT         NOT NULL,
    end_offset          INT         NOT NULL,
    start_anchor        TEXT,
    end_anchor          TEXT,
    ordinal             INT         NOT NULL,
    strategy            VARCHAR(16),          -- REGEX|AI|HYBRID
    confidence          NUMERIC(3, 2),
    source_version_hash VARCHAR(64) NOT NULL,
    created_at          TIMESTAMP   NOT NULL DEFAULT now()
);

-- Indexes
CREATE INDEX idx_document_nodes_doc_ordinal ON document_nodes (document_id, ordinal);
CREATE INDEX idx_document_nodes_doc_offset ON document_nodes (document_id, start_offset);
-- Optional: exclusion constraint to forbid overlapping ranges under the same parent
```

### JPA Entities

- `features/document/domain/model/DocumentNode.java`
- `features/document/domain/repository/DocumentNodeRepository.java`

### Mappers (MapStruct)

- `features/document/infra/mapping/DocumentNodeMapper.java` → `DocumentNodeDto`

### API Contracts

- `GET /api/v1/documents/{id}/structure?format=tree|flat`
- `POST /api/v1/documents/{id}/structure?strategy=ai|regex` (async job)

## Day 2 — Canonical Text Pipeline

**Goal**: One canonical UTF-8 text string per document + offset index (pages/paragraphs → char offsets).

### Service

`features/document/application/CanonicalTextService.java`

```java
CanonicalizedText loadOrBuild(UUID documentId) → {
text,
sourceVersionHash,
pageOffsets,
paragraphOffsets 
}
```

### Implementation

- Reuse existing converters (`ConvertedDocument`) to build the final text
- If PDF extraction is noisy, optionally add an adapter for Unstructured with `strategy=hi_res` to preserve layout
  semantics (only if needed; keep dependency optional)

### Persistence

- Store canonical text in filesystem alongside upload (not DB)
- Save `source_version_hash` in documents and on each node for determinism

### Tests

- Round-trip offsets (page→text slice) and determinism by hash

## Day 3 — Heuristic Pre-segmentation (Cheap Anchors)

**Goal**: Produce candidate anchors/windows so the LLM doesn't see raw megatext.

### Service

`features/document/application/PreSegmentationService.java`

- Split into coarse blocks (likely: chapter-like headings, `\n\n` paragraphs, page headers) with offsets
- Use existing `SentenceBoundaryDetector` and `ChunkTitleGenerator` carefully (read-only) to produce windows, not final
  chunks

### Output

For each window: `{ start_offset, end_offset, first_line_text, is_heading_guess }`

### Tests

Ensure windows cover 100% of text, are ordered, and non-overlapping.

## Day 4 — LLM Outline Extractor (Structured Outputs)

**Goal**: Top-down outline → a clean tree with anchors (titles + short verbatim phrases).

### Schema (Compact to Save Tokens)

```json
{
  "type": "object",
  "properties": {
    "nodes": {
      "type": "array",
      "items": {
        "type": "object",
        "properties": {
          "type": {
            "type": "string",
            "enum": [
              "PART",
              "CHAPTER",
              "SECTION",
              "SUBSECTION",
              "PARAGRAPH"
            ]
          },
          "title": {
            "type": "string"
          },
          "start_anchor": {
            "type": "string"
          },
          "end_anchor": {
            "type": "string"
          },
          "children": {
            "$ref": "#/$defs/n"
          }
        },
        "required": [
          "type",
          "start_anchor"
        ]
      }
    }
  },
  "$defs": {
    "n": {
      "type": "object",
      "properties": {}
    }
  }
}
```

### Service

`features/document/application/OutlineExtractorService.java`

- Uses Spring AI `ChatClient` to call OpenAI Responses API with Structured Outputs (JSON Schema)
- Return the tree (no offsets yet)

### Model Policy

- Start with small/efficient model for outline
- Escalate only if needed (hierarchical passes; see Day 6)
- For very long docs, use long-context models as a fallback

### Validation

- JSON schema compliance (client-side) + depth/size sanity checks

## Day 5 — Anchor Alignment → Hard Offsets

**Goal**: Map `start_anchor`/`end_anchor` to `start_offset`/`end_offset`.

### Service

`features/document/application/OutlineAlignmentService.java`

- For each node, run a fuzzy search for the anchor inside the pre-segmentation window(s)
- If ambiguous, expand window progressively
- If missing, fall back to local semantic splits near the predicted boundary

### Rules

- Clamp to sentence boundaries when possible (reuse your detector)
- Enforce non-overlap within the same parent
- Re-parent loose nodes to closest valid parent

### Output

- In-memory tree with absolute offsets + computed ordinal

### Persist

`DocumentStructureService.saveNodes(documentId, nodes, sourceVersionHash, strategy=AI)`

## Day 6 — Long Docs, Hierarchical Passes & Fallbacks

**Goal**: Make it work cost-effectively on huge texts.

### Strategy

**Pass 1**: Run outline on overlapped slices (2–3 big slices with ~3–5% overlap). Stitch top-level (PART/CHAPTER) using
title+anchor similarity.

**Pass 2**: Per chapter, run section/subsection extraction; for paragraphs, consider a rules-first pass (cheaper) and
use LLM only when needed.

- Use long-context models only if stitching fails or doc is extreme
- Cost remains token-based, Structured Outputs improves reliability (fewer retries)

### Config

Externalize thresholds (slice size, overlap %, max depth) in `application.yml`.

## Day 7 — REST API + Jobs + ProblemDetails

**Goal**: Expose endpoints, make them async, standardize errors.

### Controllers

`features/document/api/DocumentStructureController.java`

- `POST /documents/{id}/structure?strategy=ai|regex` → 202 Accepted + job handle
- `GET /documents/{id}/structure?format=tree|flat` → DTOs from mapper
- Keep your `DocumentController` upload/processing path as is; add links to structure

### Jobs

- Reuse your async execution style from `agents.md` (e.g., `@Async` + executor)
- If you don't have a job entity yet, add a lightweight jobs table + `GET /jobs/{id}`
- Follow the LRO 202/polling pattern

### Errors

- Global handler returns RFC 9457 ProblemDetails for validation, missing anchors, etc.

### Security

- Method security on doc ownership
- Rate-limit structure extraction

## Day 8 — Bridge to Quiz Generation (From Nodes)

**Goal**: Let quiz gen target any node(s) or a chosen granularity.

### New Endpoint

`POST /api/v1/quizzes/from-structure`

Body: `{ documentId, nodeIds:[], generationMode, difficulty, questionTypes, … }`

### Service

`features/quiz/application/QuizFromStructureService.java`

- For each `nodeId`: take `start_offset..end_offset`, slice canonical text, feed your existing generator
- You don't need to materialize new `document_chunks`

### Compatibility

- Keep your current `GenerateQuizFromDocumentRequest` & `QuizScope` as is
- Internally, resolve a SECTION request to a set of `nodeIds` (type=SECTION)

## Day 9 — Observability, Config, and Resilience

**Goal**: Production knobs + insights.

### Metrics (Micrometer)

- `ai.outline.tokens_in/out`
- `ai.outline.calls`
- `ai.outline.failures`
- `anchor.align.hit_rate`
- `node.overlap.repaired`
- `structure.extract.ms`

### Logging

- Summarize prompts, schema version, and hash (never log PII or full text)

### Resilience

- Retry/backoff on 429/5xx
- Idempotency keys on POSTs
- Timeouts per call

### Config Surface

`DocumentStructureProperties` for model choice, temperature, max output tokens, slice overlap, thresholds.

### Feature Flag

`feature.document-structure.ai.enabled` to flip on/off quickly.

## Day 10 — Tests, QA Data, and Polish

**Goal**: High confidence & docs.

### Tests

- **Unit**: extractor, aligner, validators, mappers
- **Integration**: `POST /documents/{id}/structure` → nodes persisted → `GET tree`
- **Property-based**: random headings & anchors → alignment is monotonic & non-overlapping
- **Golden files**: a book-like sample, subtitles sample, long article

### Documentation

- Update `docs/MVP_ENDPOINTS_IMPLEMENTATION_PLAN.md`
- Add `docs/document_structure.md` (schema, endpoints, examples)

### Performance

- Load test on 50–200 page docs
- Watch token costs, time, memory

### Security

- Verify ownership checks
- Ensure canonical text path not exposed

## File & Package Structure

```
features/document/
├── api/
│   ├── DocumentStructureController.java
│   └── dto/
│       ├── DocumentNodeDto.java
│       └── DocumentTreeDto.java
├── application/
│   ├── CanonicalTextService.java
│   ├── PreSegmentationService.java
│   ├── OutlineExtractorService.java
│   ├── OutlineAlignmentService.java
│   └── DocumentStructureService.java
├── domain/
│   ├── model/
│   │   └── DocumentNode.java
│   └── repository/
│       └── DocumentNodeRepository.java
└── infra/
    ├── mapping/
    │   └── DocumentNodeMapper.java   // MapStruct
    └── ai/
        └── OpenAiOutlineClient.java  // wraps Spring AI ChatClient
```

And in quizzes:

```
features/quiz/application/QuizFromStructureService.java
```

## Prompts & Model Notes

### Structured Outputs

Use Structured Outputs (Responses API) to force schema-valid JSON—this drastically reduces brittle parsing & retries.
Keep fields short to reduce token out.

### Model Tiering

- **Default**: small/efficient model for outline
- **Switch to long-context** (e.g., GPT-4.1) only for huge docs or when stitching fails

### PDFs & Layout

If pages/headers are messy, consider Unstructured's `hi_res` partitioner in the converter to preserve structure before
LLM.

## Acceptance Criteria (Per Day Buckets)

### Data Layer

- `document_nodes` created
- Nodes persist with correct parent/ordinal and non-overlap guarantees

### Extractor

- Returns schema-valid tree
- Configurable depth
- Measurable token usage

### Aligner

- ≥95% anchors resolved on golden samples
- Coverage 100%
- No crossings

### APIs

- `POST /documents/{id}/structure` (202/LRO) + `GET /documents/{id}/structure` (tree/flat)
- RFC9457 errors

### Quiz Bridge

- `POST /quizzes/from-structure` produces quizzes identical in quality (or better) than current chunk-based flow

### Operations

- Metrics emitted
- Feature flag toggles
- Rate limits enforced
