# QuizMaker — DTO Layer & Data Performance Improvement Plan

**Owner:** Alex (@Gegcuk)  
**Target areas:** DTO/Repository layer, pagination, fetch strategies, indexing, batching, prod config  
**Why now:** Current list endpoints and relation mapping risk N+1 queries and slow growth behavior; prod config mixes dev logging & lacks batching/indexes. This plan delivers **predictable latency** and **capacity headroom** without changing product behavior.

---

## 1) Goals

- Eliminate N+1 in read paths (especially **Questions list** returning tags/quizzes).
- Add **proper DB indexes** and constraints for hot paths.
- Enable **batch writes** and **batched lazy loads**.
- Separate **dev vs prod** persistence & logging behavior.
- Keep API contracts stable (unless switching to a summary DTO for listing, see options).

---

## 2) Scope (What we’ll touch)

- DTO layer around `Question` list vs detail.
- Repositories for `Question`, optional `Attempt` leaderboard projection.
- Hibernate props (batch size, default batch fetch size).
- MySQL JDBC URL perf flags.
- Flyway migration for indexes/constraints.
- Minor naming clarifications (`quizId` → `quizzes`).

---

## 3) Key Decisions & Recommendations

### 3.1 Fix the Questions list N+1
Two good paths—**choose one**.

**Option A — Summary DTO (Recommended for simplicity & speed)**  
- **List endpoint** returns `QuestionSummaryDto` without `tagIds`/`quizIds`.  
- **Detail endpoint** remains `QuestionDto` with related IDs.  
- Pros: smallest change to data layer; paging remains simple; no heavy joins.  
- Cons: Frontend change (use summary for grids).

**Option B — Two-step load with bulk fetch**  
- Step 1: page **IDs only** (`Page<UUID>`).  
- Step 2: `findAllWithRelationsByIdIn(:ids)` with `left join fetch` for tags & quizzes (de-dup and map).  
- Pros: keeps current response shape.  
- Cons: extra repo method; need careful handling of duplicates + pageable total; fetch joining many-to-many can fan out.

> My take: **Option A** is cleaner and future-proof. If you must keep `tagIds`/`quizIds` in list responses today, go **Option B**.

### 3.2 Add DB indexes and constraints (Flyway)
- Apply the migration in **Appendix A**. It adds: multi-column indexes for attempts; join-table uniques; chunk indexes; spaced repetition uniques; and more.

### 3.3 Turn on batching & batch fetching
- `hibernate.jdbc.batch_size=50`, order inserts/updates, default batch fetch size.  
- MySQL URL with `rewriteBatchedStatements=true` and prepared statement caching.

### 3.4 Safer Prod profile
- `ddl-auto=validate`, `show-sql=false`, toned-down logging; basic Hikari tuning.

### 3.5 Minor repository & naming improvements
- Rename `Question.quizId` → `questions.quizzes` (a `List<Quiz>`), and use `findAllByQuizzes_Id` for clarity.
- Leaderboard: use interface projection instead of `List<Object[]>`.

---

## 4) Implementation Steps

### Step 0 — Branch & safety
1. Create branch `feat/data-perf-plan`.
2. Ensure you have a **Flyway baseline** (`flyway_schema_history` exists). If not, bootstrap with a baseline migration correlating to your current schema.

### Step 1 — DTO layer changes (Pick Option A or B)

#### Option A — Summary/Detail split (**recommended**)
1. **Create** `QuestionSummaryDto` with fields: `id`, `type`, `difficulty`, `questionText`, `createdAt`, `updatedAt` (no tags/quizzes).
2. **Add** `QuestionSummaryMapper` (map from entity without touching lazy collections).
3. **Controller**: in list endpoint, return `Page<QuestionSummaryDto>`.
4. **Docs**: update OpenAPI annotations so consumers know the difference between list vs detail responses.

#### Option B — Two-step bulk fetch
1. **Repo**:
   ```java
// Page IDs only
@Query("select q.id from Question q join q.quizId qq where (:quizId is null or qq.id = :quizId)")
Page<UUID> findPageIdsByQuiz(@Param("quizId") UUID quizId, Pageable page);

// Eager load relations for a page of IDs
@Query("""
  select distinct q
  from Question q
  left join fetch q.tags
  left join fetch q.quizId
  where q.id in :ids
""")
List<Question> findAllWithRelationsByIdIn(@Param("ids") List<UUID> ids);
   ```
2. **Service**:
   ```java
var idsPage = questionRepository.findPageIdsByQuiz(quizId, pageable);
var items = idsPage.getContent().isEmpty()
    ? List.<Question>of()
    : questionRepository.findAllWithRelationsByIdIn(idsPage.getContent());
return new PageImpl<>(items.stream().map(QuestionMapper::toDto).toList(),
                      pageable, idsPage.getTotalElements());
   ```
3. **Mapper**: unchanged.

> Guardrail: never put `EntityGraph` with many-to-many on global `findAll(pageable)`; it can explode row counts.

### Step 2 — Repository & naming polish
- Rename `Question.quizId` → `Question.quizzes`.  
  - Update mapper references `getQuizId()` → `getQuizzes()`; and `quizIds` derivation stays the same.  
  - Adjust derived queries: `findAllByQuizId_Id` → `findAllByQuizzes_Id`.
- Leaderboard:
  ```java
public interface LeaderboardRow {
  UUID getUserId();
  String getUsername();
  Double getMaxScore();
}

@Query("""
  select u.id as userId, u.username as username, max(a.totalScore) as maxScore
  from Attempt a join a.user u
  where a.quiz.id = :quizId and a.status = 'COMPLETED'
  group by u.id, u.username
  order by max(a.totalScore) desc
""")
List<LeaderboardRow> getLeaderboard(@Param("quizId") UUID quizId);
  ```

### Step 3 — Hibernate & JDBC performance flags
- **application-prod.properties** (see Appendix B):
  - `spring.jpa.properties.hibernate.jdbc.batch_size=50`
  - `spring.jpa.properties.hibernate.order_inserts=true`
  - `spring.jpa.properties.hibernate.order_updates=true`
  - `spring.jpa.properties.hibernate.default_batch_fetch_size=50`
  - JDBC URL params:
    `rewriteBatchedStatements=true&cachePrepStmts=true&prepStmtCacheSize=250&prepStmtCacheSqlLimit=2048&useServerPrepStmts=true`
- Keep `open-in-view=false` in prod (and ensure service/repo boundaries load all data inside tx).

### Step 4 — Indexes & constraints (Flyway)
- Add **Appendix A** SQL as a new migration, e.g. `V2025_08_10__add_perf_indexes.sql`.
- Deploy to a staging DB first; validate existing constraints don’t conflict.

### Step 5 — Controller pagination guardrails
- Enforce a **server-side max page size** (e.g., cap at 100). Reject larger sizes via validation or clamp.
- Ensure **Sort** uses indexed columns (e.g., `createdAt`, `startedAt`).

### Step 6 — Batch-insert hotspots
- Replace per-row `save` in loops with `saveAll` when feasible (Answers, DocumentChunks).  
- If business logic requires per-row decisions, at least group per-tx and avoid repeated flushes.

### Step 7 — Read-only hints (optional)
- For large read queries, add:
  ```java
  @QueryHints(@QueryHint(name = "org.hibernate.readOnly", value = "true"))
  ```
  on repository methods that return immutable views. This can reduce dirty checking overhead.

### Step 8 — Spaced Repetition consistency
- Unique `(user_id, question_id)` enforced via **Appendix A**.  
- On save, upsert carefully (either catch constraint violation or use a “get-or-create” flow).

### Step 9 — Documents & chunks
- Keep `findByIdWithChunks` for single-document views.  
- For large documents, prefer paged `findByDocument(document, pageable)` in UIs to avoid giant payloads.  
- Ensure chunk table has `(document_id, chunk_index)` index (Appendix A).

### Step 10 — Observability & regression checks
- Add a smoke perf test (e.g., seed 5–10k questions with tags/quizzes; run the paged list and measure P95).  
- Enable slow query logging in **staging** only if needed to catch regressions.

---

## 5) Acceptance Criteria

- Listing questions **does not trigger** per-row lazy loads for tags/quizzes.
- P95 latency for `GET /questions?page=0&size=20` under a dataset with 10k+ questions is **stable** (< your current SLO) after warm-up.
- `Attempt` leaderboard returns via a **typed projection** (no `Object[]`).
- Flyway migration applies cleanly; indexes exist.
- Production profile uses **batching** and **limited logging**; dev retains verbose logs.

---

## 6) Risk & Rollback

- **Risk:** Two-step fetch (Option B) can produce duplicate rows if `distinct` is omitted.  
  **Mitigation:** Always `select distinct q` and map by ID.
- **Risk:** New unique constraints may conflict with legacy dupes.  
  **Mitigation:** Pre-migration query to spot duplicates; clean up before applying.
- **Rollback:** Revert the branch; Flyway migration rollback via down migration or fresh schema on staging.

---

## 7) Follow-ups (not blocking)

- If you start filtering by JSON fields, consider **generated columns** to index JSON keys.
- Add **DB-level FKs** on join tables if not already present.
- Consider **second-level cache** only after you see read patterns stabilise (don’t add prematurely).

---

## Appendix A — Flyway migration: indexes & constraints

```sql
-- Flyway migration: add performance indexes and constraints
-- Date: 2025-08-10

-- ===== attempts =====
CREATE INDEX IF NOT EXISTS idx_attempts_quiz_user_status_started
  ON attempts (quiz_id, user_id, status, started_at);
CREATE INDEX IF NOT EXISTS idx_attempts_started_at ON attempts (started_at);
CREATE INDEX IF NOT EXISTS idx_attempts_completed_at ON attempts (completed_at);
CREATE INDEX IF NOT EXISTS idx_attempts_status ON attempts (status);

-- ===== answers =====
CREATE INDEX IF NOT EXISTS idx_answers_attempt_id ON answers (attempt_id);
CREATE INDEX IF NOT EXISTS idx_answers_question_id ON answers (question_id);

-- ===== quizzes =====
CREATE INDEX IF NOT EXISTS idx_quiz_visibility ON quizzes (visibility);
CREATE INDEX IF NOT EXISTS idx_quiz_category ON quizzes (category_id);
CREATE INDEX IF NOT EXISTS idx_quiz_is_deleted ON quizzes (is_deleted);

-- ===== questions =====
CREATE INDEX IF NOT EXISTS idx_questions_difficulty ON questions (difficulty);
CREATE INDEX IF NOT EXISTS idx_questions_created_at ON questions (created_at);
CREATE INDEX IF NOT EXISTS idx_questions_is_deleted ON questions (is_deleted);

-- ===== join tables =====
CREATE INDEX IF NOT EXISTS idx_quiz_questions_question ON quiz_questions (question_id);
CREATE INDEX IF NOT EXISTS idx_quiz_questions_quiz ON quiz_questions (quiz_id);
ALTER TABLE quiz_questions
  ADD CONSTRAINT uq_quiz_questions UNIQUE (quiz_id, question_id);

CREATE INDEX IF NOT EXISTS idx_question_tags_question ON question_tags (question_id);
CREATE INDEX IF NOT EXISTS idx_question_tags_tag ON question_tags (tag_id);
ALTER TABLE question_tags
  ADD CONSTRAINT uq_question_tags UNIQUE (question_id, tag_id);

-- ===== documents =====
CREATE INDEX IF NOT EXISTS idx_documents_user_status ON documents (user_id, status);
CREATE INDEX IF NOT EXISTS idx_documents_uploaded_at ON documents (uploaded_at);

-- ===== document_chunks =====
CREATE INDEX IF NOT EXISTS idx_doc_chunks_document ON document_chunks (document_id);
CREATE INDEX IF NOT EXISTS idx_doc_chunks_document_index ON document_chunks (document_id, chunk_index);
CREATE INDEX IF NOT EXISTS idx_doc_chunks_type ON document_chunks (chunk_type);

-- ===== spaced_repetition_entry =====
CREATE INDEX IF NOT EXISTS idx_sre_user_next_review ON spaced_repetition_entry (user_id, next_review_at);
ALTER TABLE spaced_repetition_entry
  ADD CONSTRAINT uq_sre_user_question UNIQUE (user_id, question_id);

-- ===== audit_logs =====
CREATE INDEX IF NOT EXISTS idx_audit_logs_created_at ON audit_logs (created_at);
CREATE INDEX IF NOT EXISTS idx_audit_logs_user_id ON audit_logs (user_id);

```

---

## Appendix B — Production properties template

```properties
# application-prod.properties
spring.datasource.url=jdbc:mysql://db:3306/quizmakerdb?useSSL=true&requireSSL=false&serverTimezone=UTC&rewriteBatchedStatements=true&cachePrepStmts=true&prepStmtCacheSize=250&prepStmtCacheSqlLimit=2048&useServerPrepStmts=true
spring.datasource.username=${DB_USERNAME}
spring.datasource.password=${DB_PASSWORD}

# HikariCP tuning (adjust to your instance size)
spring.datasource.hikari.maximum-pool-size=20
spring.datasource.hikari.minimum-idle=5
spring.datasource.hikari.idle-timeout=600000
spring.datasource.hikari.max-lifetime=1800000
spring.datasource.hikari.connection-timeout=30000

# JPA & Hibernate
spring.jpa.database-platform=org.hibernate.dialect.MySQL8Dialect
spring.jpa.hibernate.ddl-auto=validate
spring.jpa.open-in-view=false

# Hibernate performance
spring.jpa.properties.hibernate.jdbc.batch_size=50
spring.jpa.properties.hibernate.order_inserts=true
spring.jpa.properties.hibernate.order_updates=true
spring.jpa.properties.hibernate.generate_statistics=false
spring.jpa.properties.hibernate.default_batch_fetch_size=50

# Logging (production-safe)
logging.level.org.hibernate.SQL=INFO
logging.level.org.hibernate.type.descriptor.sql=INFO
logging.level.uk.gegc.quizmaker=INFO

```

---

## Appendix C — Example: QuestionSummaryDto (Option A)

```java
@Schema(name = "QuestionSummaryDto")
public record QuestionSummaryDto(
    UUID id,
    QuestionType type,
    Difficulty difficulty,
    String questionText,
    Instant createdAt,
    Instant updatedAt
) {}
```

## Appendix D — Example: QuestionSummaryMapper (Option A)

```java
@Component
public class QuestionSummaryMapper {
  public QuestionSummaryDto toDto(Question q) {
    return new QuestionSummaryDto(
      q.getId(),
      q.getType(),
      q.getDifficulty(),
      q.getQuestionText(),
      q.getCreatedAt(),
      q.getUpdatedAt()
    );
  }
}
```

## Appendix E — Controller snippet (Option A)

```java
@GetMapping
public ResponseEntity<Page<QuestionSummaryDto>> getQuestions(
    @RequestParam(required = false) UUID quizId,
    @RequestParam(defaultValue = "0") int pageNumber,
    @RequestParam(defaultValue = "20") int size
) {
    Pageable page = PageRequest.of(pageNumber, Math.min(size, 100), Sort.by("createdAt").descending());
    return ResponseEntity.ok(questionService.listQuestionSummaries(quizId, page));
}
```

## Appendix F — Service snippet (Option A)

```java
@Transactional(readOnly = true)
public Page<QuestionSummaryDto> listQuestionSummaries(UUID quizId, Pageable pageable) {
    Page<Question> retrieved = (quizId != null)
        ? questionRepository.findAllByQuizzes_Id(quizId, pageable)
        : questionRepository.findAll(pageable);
    return retrieved.map(summaryMapper::toDto);
}
```
