# Spaced Repetition Notes

The legacy spaced repetition REST API has been removed. No `/api/v1/repetition` endpoints exist in the current service.

Base path: *not applicable*

## Auth

| Operation | Auth | Roles/Permissions |
| ---------- | ---- | ----------------- |
| – | – | – |

---

## Notes for Agents

- Client apps should derive study schedules from quiz and attempt analytics (see `quiz_controller.md` and `attempt_controller.md`).
- Internal repetition services located under `features/repetition` are invoked by the scheduling engine only.
- If you relied on old endpoints, migrate to the attempt statistics and quiz result summaries documented elsewhere.
