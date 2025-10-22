# Conversion Service Notes

No public REST controller is exposed for document conversion. Conversion happens behind the scenes when other document endpoints are hit.

Base path: *not applicable* (internal service)

## Auth

| Operation | Auth | Roles/Permissions |
| ---------- | ---- | ----------------- |
| – | – | – |

---

## DTOs

Conversion operates on internal models. Refer to document/document-process APIs for user-facing DTOs (`DocumentDto`, `DocumentChunkDto`, etc.).

---

## Endpoints

There are no direct endpoints. Conversion is triggered by:
- `POST /api/documents/upload`
- `POST /api/v1/documentProcess/documents`

---

## Notes for Agents

- When documenting or using conversion capabilities, rely on `document_controller.md` and `document_process_controller.md`.
- Supported input formats: PDF (`application/pdf`), EPUB (`application/epub+zip`), plain text (`text/plain`); additional adapters exist for HTML/SRT/VTT internally.
- Conversion errors surface as `ConversionFailedException` or `UnsupportedFileTypeException`, which bubble up through the document APIs as 400/422 responses.
