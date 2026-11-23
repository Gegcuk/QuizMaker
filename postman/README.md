# QuizMaker: local HTTP test environment

A collection for repeatable Postman requests against the application in Docker
on your own computer. It uses real MySQL and the Flyway-managed schema. Nginx
exposes only `http://127.0.0.1:8080`; the application and MySQL share a separate
internal network. Management port `8081` and MySQL are not published to the host.

## What server/local contains

`server/local/` defines this local test runtime. The `server` directory groups
container configurations; these files are used by the local Postman workflow,
not by a remote-server test or deployment workflow.

| File | Local purpose |
| --- | --- |
| `Dockerfile` | Packages the application JAR built from this checkout into a local container |
| `Dockerfile.dockerignore` | Restricts the build context to the JAR and local application configuration |
| `compose.yaml` | Starts the application, MySQL and Nginx, with local volumes and an internal application network |
| `application-local.properties` | Selects local settings, readiness checks, fake provider configuration and the existing no-op email provider |
| `nginx.conf` | Routes the localhost gateway to the application container |
| `.gitignore` | Excludes generated private fixture state and temporary files |

For example, a request follows Postman -> `127.0.0.1:8080` -> Nginx -> the local
application -> local MySQL. External providers stay isolated. The generated
`server/local/.env` holds local credentials and is excluded from Git.

## Start and update the application

From the primary checkout root, with Docker Desktop running:

```bash
./tools/local-api.sh up
./tools/local-api.sh status
```

Requires JDK 17, Python 3 and Docker Compose. On macOS, the script automatically
selects an installed JDK 17; set `JAVA_HOME` on other systems. The first build may
download Maven dependencies and base images. No Java dependencies are added.

Each `up` runs `clean package` with tests skipped, builds an image from the
resulting JAR and waits for all three containers to become healthy. It builds
the current checkout, including uncommitted changes; it does not run Git pull.
Finish concurrent edits in this checkout before building. The Git SHA is stored
in `org.opencontainers.image.revision`; changes under `src`, `pom.xml` or `.mvn`
add the `-dirty` suffix.

Local database passwords and application keys are generated on first startup in
`server/local/.env` with mode `600`. Git ignores this file. The script prevents
same-named credentials in the calling shell from overriding the generated
values. Keep this file with the existing Docker volumes: generating a new
password does not change the password of an already initialized MySQL instance.

## Postman collection and environment

Import both files if they are not already in Postman:

- `QuizMaker.postman_collection.json`: **QuizMaker — Local Docker** collection.
- `environments/Local-Docker.postman_environment.json`: the matching environment,
  with `baseUrl=http://127.0.0.1:8080` and an empty `accessFixtureData` placeholder.

See the [Postman import documentation](https://learning.postman.com/docs/getting-started/importing-and-exporting/importing-data/).
Select the environment, open the collection and choose **Run**. A routine smoke
check needs only **00 Smoke**, one iteration and no data file. Use the local
Collection Runner in the desktop application: a cloud runner cannot access your
computer's localhost.

| Folder | Purpose |
| --- | --- |
| `00 Smoke` | Four requests that do not create user data |
| `10 Auth` | Repeatable registration, login, me and logout scenario |
| `11 Access` | Existing reader/author/admin permissions, private quiz ownership and no writes after denial |
| `20 Quizzes and attempts` | Repeatable private quiz CRUD with five prepared questions |
| `21 Quiz revision publication` | OpenAPI coverage for completed feature #935: publication, exact replay and conflicts |
| `22 Publication ownership` | Independent child scenarios for quiz ownership and question-source authority during publication |
| `23 Custom Learning` | Issue #840: custom progression, mixed FSRS sessions, replay, timezone confirmation and practice |

Smoke checks `GET /api/v1/health`, `GET /api/v1/api-summary`,
`GET /v3/api-docs/auth` and unauthenticated `GET /api/v1/auth/me`.
Expected statuses are **200, 200, 200, 401**, with **12 passing assertions**.
For example, unauthenticated me returns `ProblemDetail` with `status=401`, while
public health returns only `{"status":"UP"}`. The application Docker healthcheck
separately checks MySQL and disk readiness.

The collection rejects destinations outside localhost:8080 and disables cookies
and redirects. Smoke uses `No Auth`; Auth sends Bearer credentials only where
needed. Keep tokens and user passwords out of exported files. After later
collection edits, export it back into this directory and review the diff before
committing.

## Repeatable Auth scenario

Open **10 Auth -> Run**, select **QuizMaker — Local Docker**, choose **Local** and
leave iteration data empty. Run all six requests in their original order. Use
two iterations to check repetition. Disable **Persist responses for a session**,
**Keep variable values** and **Save cookies after collection run**; enable
**Turn off logs during run** and **Run collection without using stored cookies**.

| Step | Request | Expected result |
| --- | --- | --- |
| 1 | `POST /api/v1/auth/register` | 201; unique new user with the existing registration roles |
| 2 | `POST /api/v1/auth/login` | 200; access/refresh tokens and positive lifetimes in milliseconds |
| 3 | `GET /api/v1/auth/me` | 200; the same UUID, username, email and roles |
| 4 | `POST /api/v1/auth/logout` | 204 with no response body |
| 5 | `GET /api/v1/auth/me` with the old access token | 401 ProblemDetail |
| 6 | `POST /api/v1/auth/refresh` with the old refresh token | 401 ProblemDetail |

One iteration has **15 assertions**; two have **30**. Smoke + Auth contain ten
requests and 27 assertions per iteration. `authRun*` exists only in run-scoped
`pm.variables`; passwords and JWTs are not persisted to environment/collection
variables, response examples or source JSON. The password is cleared after
login, and tokens after the last step. An assertion failure stops the chain.
Running a step without its prerequisites is rejected. See the
[Postman variable scope documentation](https://learning.postman.com/latest-v-12/docs/use/send-requests/variables/variables).

Each run creates a separate account, such as `qma_<unique-suffix>`. Logout revokes
the session; the account remains in the local database. There is no automatic
account deletion or registration-limit bypass. On `429`, respect `Retry-After`.
Email verification stays enabled: this uses the existing login behavior after
normal registration and the existing no-op email provider.

## Repeatable permission checks: 11 Access

Uses prepared `qmfx_reader`, `qmfx_author_a` and `qmfx_admin` accounts. The target
is the PRIVATE DRAFT **HTTP basics** quiz owned by `qmfx_author_b`. This scenario
does not create users, roles or questions.

1. Refresh local data files if needed: `./tools/local-api.sh fixtures`.
2. Open **11 Access -> Run**, select **QuizMaker — Local Docker** and **Local**.
3. In **Environments -> QuizMaker — Local Docker**, paste the contents of local
   `postman/data/Access.private.json` into the **Value** column for
   **accessFixtureData**. Leave **Shared Value** empty; show it using
   **Show as column -> Shared Value**. Do not use Share for this variable. Only
   the local value in this Postman installation is populated.
4. Return to Runner. Leave **Iteration data** empty, use **1 iteration** and run
   all 16 requests in their original order. Disable **Persist responses for a
   session**, **Keep variable values** and **Save cookies**; enable **Turn off
   logs during run** and **Run collection without using stored cookies**.
5. Choose **Rerun** to repeat. Multiple iterations reuse the prepared accounts
   and open/close fresh sessions each time.

On the installed Postman plan, attaching data files requires a subscription.
The local environment value lets this scenario run without data files. It
accepts a fixture object or a one-element array containing it. Missing or invalid
data stops the scenario before the first login.

Local Value is not synchronized to Postman Cloud; shared value is separate.
See [Postman environment variables](https://learning.postman.com/latest-v-12/docs/use/send-requests/variables/environment-variables).
The environment file in Git keeps `accessFixtureData.value` empty. Do not replace
this template with an export of a populated local environment.

| Check | Expected result and evidence |
| --- | --- |
| Administrator calls `GET /api/v1/admin/roles` | 200; named roles and permissions. ROLE_USER lacks QUIZ_CREATE, ROLE_QUIZ_CREATOR has QUIZ_UPDATE without moderation permissions, and ROLE_ADMIN has ROLE_READ |
| Reader calls `POST /api/v1/quizzes` with a valid body | 403 ProblemDetail; the reader's own quiz totalElements is unchanged |
| Author A calls `PATCH /api/v1/quizzes/{id}` for B's private quiz | 403 ProblemDetail; administrator reads before and after, and every response field matches |

Each actor first logs in and confirms UUID, username, active status and exact
roles through `/auth/me`. Target identity and distinct owners are checked before
writes. A denial is not accepted as evidence if the actor is wrong, the quiz is
missing or the author has lost QUIZ_UPDATE. Reader pagination uses `size=1`.

A successful run has **36 assertions**. Three explicit logout requests run at
the end. Assertion failures route execution to cleanup; one failed logout does
not cancel the others. Negative cases check subsequent state even after an
unexpected successful HTTP status. Temporary `accessRun*` is cleared at the end;
tokens are not saved to environment or collection variables. Manually stopping
Runner or losing the API can prevent logout completion.

`Access.private.json` is generated from the same private state, has mode `600`
and is ignored by Git. The collection contains variable references and does not
write credentials to shared values. On another computer, populate local Value
from that computer's private file; do not import it as a shared environment,
collection or cloud dataset. If broken access control unexpectedly creates or
changes a quiz, the scenario reports the failure rather than concealing it by
automatically restoring fixtures.

## Repeatable quiz lifecycle: 20 Quizzes and attempts

Uses `qmfx_author_a` from the existing local **Value** of `accessFixtureData`.
No additional passwords, UUIDs or data files are needed. The folder finds the
author's PRIVATE DRAFT **Java basics** quiz and checks its five questions against
`fixtures/question-sets.json`.

Open **20 Quizzes and attempts -> Run**, select **QuizMaker — Local Docker**,
**Local**, one iteration and all **19 requests** in their original order. Leave
**Iteration data** empty. Disable **Persist responses for a session**, **Keep
variable values**, **Save cookies after collection run** and **Stop run if an
error occurs**; enable **Turn off logs during run** and **Run collection without
using stored cookies**. Do not run individual steps: transient scenario state
enforces their order.

| Steps | Expected behavior |
| --- | --- |
| 01–04 | Login, verify author UUID/roles, find the source quiz and check five distinct prepared questions |
| 05 | Create a PRIVATE DRAFT named `Local CRUD <UUID>`; 201 |
| 06–10 | Link existing MCQ_SINGLE, MCQ_MULTI, TRUE_FALSE, FILL_GAP and ORDERING questions; 204 each |
| 11–12 | Read the new quiz and questions; verify owner, visibility, count, UUIDs and content |
| 13–14 | Update title, description, difficulty to MEDIUM and estimatedTime to 12; read back the saved changes |
| 90–92 | Recheck owner and unique title before deletion, DELETE 204, then GET 404 ProblemDetail |
| 93, 99 | Original questions/associations match their initial state; logout 204 and clear transient variables |

One successful run has **40 assertions**. **Rerun** creates a fresh quiz. Two full
consecutive desktop runs passed all 40 checks each. Original users, active quizzes,
questions and association rows matched their initial snapshots; zero active
fixture sessions or active scenario quizzes remained.

An assertion failure routes the chain to cleanup. Deletion permits only the UUID
created by this run, after checking its owner and one of the run's two unique
titles. Source and foreign fixture quizzes are explicitly excluded. Existing
questions are linked, never edited or deleted. The token and other `quizCrud*`
state stay in `pm.variables` and are cleared at logout. Manual Runner interruption
or an unavailable API can prevent deletion/logout completion.

Deletion uses the application's existing soft delete: the quiz disappears from
the API while its marked row remains in local MySQL. Tombstones are not purged.
External services are not called.

## OpenAPI and revision publication: 21 Quiz revision publication

[AGENTS.md](AGENTS.md) requires public OpenAPI from the local application before
requests and assertions. Paths, methods, authentication, parameters, constraints,
response schemas, errors and replay semantics come only from Swagger. Fixtures
are inputs. Implementation code may be read after a failed HTTP check or initial
run for diagnosis. A runtime discrepancy remains a visible contract failure;
observed behavior does not replace the expected result. Prioritize the latest
completed feature on the main branch with a merged PR, completed owning issue
and available contract; an unfinished branch does not count as published.

At preparation time, the selected completed feature in `master` was
[#935](https://github.com/Gegcuk/QuizMaker/issues/935), merged through
[PR #937](https://github.com/Gegcuk/QuizMaker/pull/937), with successful CI run 860.
The verified container revision was `1eb446d666a6a5a56c4ab3b7b2d1bc96d3ef9a27`.
Contracts came from `/api/v1/api-summary`, `/v3/api-docs/quizzes`,
`/v3/api-docs/questions` and `/v3/api-docs/auth`. The main operation is
`publishQuizRevision`: `POST /api/v1/quizzes/{quizId}/publications`, with named
`PublishQuizRevisionRequest` and `QuizRevisionPublicationResponse` schemas.

Open **21 Quiz revision publication -> Run**, use the same local environment,
one iteration and all **41 requests**, with no iteration data. It uses the
existing local `accessFixtureData` Value. Runner privacy settings match folder 20;
**Stop run if an error occurs** stays disabled so cleanup can run after failures.

The scenario downloads live Swagger and checks the operation, schemas,
authentication and errors. The author creates a unique PRIVATE quiz and links
five prepared Java questions. It then verifies:

- Missing Bearer and deliberately invalid Bearer both return **401**. Prepared
  `qmfx_reader` logs in and confirms active status, UUID and ROLE_USER before a
  denied private-quiz publication. The denial must be a documented 4xx with
  ProblemDetail; Swagger does not assign a separate status to the reader role.
- Missing commandId, malformed JSON/path UUID, a negative counter and empty
  question order return **400**; missing quiz returns **404**, and Content-Type
  `text/plain` returns **415**. Errors are checked as ProblemDetail. Duplicate
  question UUIDs must be rejected with documented **400/422**, because Swagger
  does not separately map duplicates between invalid fields and invalid order.
  The observed status is **400**. Incomplete nonempty order returns **422**.
- The owner's first publication after the negative cases, using
  expectedPublicationVersion=0, returns **200**, counter=1: denials created no
  publication and did not advance the counter. The response identifies an
  immutable quiz revision and five question revisions. Validation uses the named
  schema from live Swagger. Built-in Ajv is configured for int32/int64 numeric
  formats; scenario counters must stay exactly representable JavaScript integers.
  See the [Postman response validator documentation](https://learning.postman.com/docs/tests-and-scripts/write-scripts/postman-sandbox-reference/pm-response/).
- Exact replay of commandId and body returns the complete original acknowledgement,
  including identifier order.
- After a draft title change, a new command with stale counter=0 returns **409**.
  Reusing the original commandId with changed order also returns **409**.
- Counter=1 with reversed question order creates publication counter=2. Both old
  and new commands subsequently return their own original acknowledgements.
- Cleanup deletes only this run's draft, confirms absence from own quiz search,
  compares original questions with their initial snapshot, explicitly logs out
  reader and author, and clears `pubRun*`. One logout failure does not skip the
  other. An unexpected 4xx in a negative case retains a failed status assertion
  while allowing remaining cases; an unexpectedly accepted command, 5xx or
  invalid error shape routes execution to cleanup.

`publicationVersion` is the publication counter; `revisionSchemaVersion` is the
format version. Swagger does not promise reuse of question revision UUIDs across
different publications. The scenario checks ordered identifier stability for
each replay. This contract offers no separate public read of revision history
or content. Learning and hidden administrative operations are outside this
scenario.

**Confirmed OpenAPI discrepancy:** `POST /api/v1/quizzes` documents only **200**,
but successful creation returns **201**. It is **D-001** in
[discrepancy register #938](https://github.com/Gegcuk/QuizMaker/issues/938).
`Draft creation HTTP status matches live OpenAPI` deliberately reports this
failure. A valid successful creation lets later publication and cleanup checks
continue. This scenario does not fix the application or Swagger. After a
specification fix and container update, the assertion reads the new status from
live OpenAPI automatically.

The initial two full desktop runs of 29 requests produced **69 assertions:
68 passed, 1 failed** each, with zero script execution errors. D-001 was the only
failure; publication, replay, conflicts and cleanup passed. Original users,
active quizzes, questions and associations matched initial snapshots; no active
scenario quizzes or fixture sessions remained.

After expansion to 41 requests, two full desktop runs produced **102 assertions:
101 passed, 1 failed** each, with zero script errors. All **33 added assertions**
passed in both runs; D-001 remained. Reader denial returned documented **404**,
and the owner's subsequent first publication used counter=1. Fifteen scenario
controls checked step order, logout skipping without a session, independent
sessions, state clearing and protection of source/foreign fixture quizzes.
Each run creates unique commands and two publications. Existing retention keeps
immutable history after draft deletion; the scenario does not purge it. Original
fixtures and local Shared Value are preserved. Application/JPA paths are
unchanged. Earlier bounded SQL measurements below cover the indicated reads,
not the publication writer.

## Publication ownership: 22 Publication ownership

Uses `publishQuizRevision` in the `quizzes` group, plus `addQuestion`,
`getAllRoles`/`RoleDto`, authentication from `auth` and prepared-question reads
from `questions`.

Two independent child folders keep the section from becoming a flat list of
65 requests:

```text
22 Publication ownership
├── 01 Quiz ownership and moderation  (36 requests)
└── 02 Question source ownership      (29 requests)
```

Each child includes login, input verification, its own temporary quiz,
publications, guarded deletion and logout. Run a complete child or parent **22**
to execute both in sequence, for **65 requests**. Transient variables use separate
`ownRun*` and `srcRun*` prefixes, with unique transition request names. Completed
cleanup in the first child allows the second to run even after known D-001,
while the failed assertion stays visible. Invalid initial fixtures stop the run
before login or resource creation.

Folder 21 remains one cohesive 41-request scenario. Dependent phases without
their own setup/cleanup are not exposed as independently runnable folders.

Local `postman/data/Access.private.json` includes `authorBUsername`,
`authorBPassword`, `authorBUserId`, `moderatorUsername`, `moderatorPassword` and
`moderatorUserId`, while retaining reader, author A, administrator and source
HTTP-quiz fields. After refreshing fixtures, copy the full JSON into the existing
local `accessFixtureData` **Value** and leave **Shared Value** empty. The public
environment template stays empty. Account passwords and roles are not changed.

### 01 Quiz ownership and moderation

Open **22 Publication ownership -> 01 Quiz ownership and moderation -> Run**,
select **QuizMaker — Local Docker**, **Local**, one iteration and all **36
requests**. Leave iteration data empty. Use folder 21's Runner settings: disable
logs, saved responses, cookies and persisted values; keep **Stop run if an error
occurs** disabled so all four logout requests can run. Use **Rerun** to repeat.

| Steps | Check |
| --- | --- |
| 01–07 | Read live OpenAPI; log in A/B/admin and confirm distinct UUIDs, active status and exact roles |
| 07a–07b | Log in qmfx_moderator and confirm UUID, active status and only ROLE_MODERATOR |
| 08 | Live grants: authors have QUIZ_UPDATE without moderation; admin has QUIZ_ADMIN or QUIZ_MODERATE; moderator has QUIZ_MODERATE without QUIZ_ADMIN/QUESTION_ADMIN |
| 09–10 | B reads the prepared private HTTP quiz and five original questions; retain a content snapshot |
| 11–17 | A creates a unique PRIVATE DRAFT; administrator links B's prepared questions |
| 18–19 | B receives 404 ProblemDetail on A's quiz publication; every draft response field is unchanged |
| 20–21 | Administrator publishes at counter=1; acknowledgement matches the schema and A stays owner |
| 22–23 | B cannot replay the administrator's accepted command: 404; administrator receives the original exact acknowledgement |
| 24–26 | Moderator publishes the same quiz with reversed order at counter=2; A stays owner and exact replay is stable |
| 90–99 | A deletes only this run's quiz; original content/associations match; four independent logouts and `ownRun*` cleanup |

The prepared questions were originally authored by B. Administrator links them
to A's quiz through documented cross-owner reuse. B has author permissions and
source authority, isolating missing **quiz ownership**. Administrator succeeds
through the same publication route; counter one proves the earlier denial did
not publish. Replay additionally checks current authorization again.

The moderator uses existing **QUIZ_MODERATE**, which Swagger permits for both
cross-owner publication and reuse of another author's recorded sources. A new
command uses `expectedPublicationVersion=1` and the entire reversed question
order. Changed content must create publication **2** with a new `quizRevisionId`,
rather than potentially reuse identical current content. Replay retains every
acknowledged field and ordered UUID. Source content/questions are not edited.

As in folder 21, creation retains explicit **D-001** failure (Swagger 200, actual
201). Ownership checks continue after valid successful setup. Guarded cleanup
allows only this run's UUID after checking A's ownership and unique title, and
excludes the source HTTP quiz. Immutable history follows existing retention.

Before moderator coverage, two full desktop runs produced **68 assertions:
67 passed, 1 failed** each, with zero script errors. Only D-001 failed; no new
discrepancy was found. B received 404 initially and on administrator-command
replay; admin received 200 at counter=1 and the same replay acknowledgement.
Three logouts returned 204. Active quiz/question/association snapshots were
preserved; no active temporary quiz or fixture session remained. Five Python
fixture-export tests and 15 step-order/session/cleanup/deletion controls passed.

After adding the moderator, two full desktop runs of **36 requests** produced
**85 assertions: 84 passed, 1 failed** each, with zero script errors. All **17
added assertions** passed; only D-001 failed. Moderator received 200 at counter=2
with a new revision UUID, exact replay matched and A remained owner. Four logouts
returned 204. Initial active quiz/question/association snapshots and user count
were preserved; no active temporary quiz/session remained. Five Python export
tests and 15 additional controls for missing/colliding moderator identities,
step order, failed login, independent logout and final clearing passed.

### 02 Question source ownership

Open **22 Publication ownership -> 02 Question source ownership -> Run** with
no iteration data and the same local fixtures/Runner settings. Run all **29
requests** in order. It uses author A, author B and administrator; no new
accounts, roles or questions are created.

1. Live Swagger confirms recorded source ownership as a separate requirement.
   All three users confirm UUIDs, active status and roles. Live grants give A
   QUIZ_UPDATE without QUIZ_MODERATE, QUIZ_ADMIN or QUESTION_ADMIN.
2. B reads the prepared HTTP quiz and five questions. A creates a unique PRIVATE
   quiz named `Local source ownership <UUID>`; administrator links B's questions
   through documented `addQuestion`.
3. A reads the owned quiz, then receives **404 ProblemDetail** on publication:
   quiz ownership does not grant authority to publish another author's sources.
   Every draft response field must match the initial snapshot after denial.
4. Administrator publishes the same set with **200**, `publicationVersion=1`:
   the denial created no publication. A remains owner of the PRIVATE quiz.
   Administrator's exact replay returns the full original acknowledgement,
   including ordered question revision UUIDs.
5. A deletes only this run's quiz after verifying UUID, owner and unique title;
   source HTTP quiz is protected. Original content/associations match their
   initial snapshot. Three independent logouts close sessions and `srcRun*` is
   cleared. Publication history follows existing application retention.

Swagger promises that exact replay rechecks current **quiz** authority. This
scenario does not invent a separate source-authority replay denial for the owner
using an administrator's accepted command; the contract does not state that
guarantee. As in the sibling scenario, creation retains D-001, while valid
successful setup permits later assertions and cleanup.

A standalone desktop run produced **65 assertions: 64 passed, 1 failed**, with
zero script errors. Two full consecutive parent **22** runs executed all **65
requests** each: **150 assertions, 148 passed, 2 failed**, with zero script errors.
Both failures were D-001 at two independent temporary-quiz creations, not two
separate discrepancies. Both children reached logout, and the first child's
known failed assertion did not prevent the second from running. No additional
discrepancy was found.

In every run, A received 404 for B's sources, draft fields stayed unchanged and
administrator's first publication used counter=1 with stable replay. Initial
active quiz/question/association snapshots and user count were preserved. No
active temporary quizzes or fixture sessions remained. Fifteen existing quiz
ownership controls and 15 new controls passed: input validation, variable
isolation, transition order, protected source deletion, independent logout and
parent-run continuation after a reported failure.

Production Java/JPA paths are unchanged. These are functional checks of real
HTTP publication, ownership and replay with prepared sources.

## Prepared users and questions for future scenarios

With the local environment running:

```bash
./tools/local-api.sh fixtures
```

The command is restricted to the healthy `quizmaker-postman` Docker project,
`quizmaker_local` database and loopback gateway. It accepts no arbitrary database
or API destinations, follows no HTTP redirects and uses no system proxy.
It registers users through normal HTTP, then assigns the approved existing
roles and active flags through SQL only to these local fixture accounts: no
initial administrator is available to assign roles over HTTP. Application
permission mappings and the permission manifest are unchanged.

| Persona / username | Roles | Purpose |
| --- | --- | --- |
| `reader` / `qmfx_reader` | ROLE_USER | Can log in; cannot create questions/quizzes or read admin roles |
| `author_a` / `qmfx_author_a` | ROLE_USER + ROLE_QUIZ_CREATOR | Owns Java fixtures |
| `author_b` / `qmfx_author_b` | ROLE_USER + ROLE_QUIZ_CREATOR | Owns HTTP fixtures; foreign author for ownership checks |
| `moderator` / `qmfx_moderator` | ROLE_MODERATOR | Existing moderation permissions |
| `admin` / `qmfx_admin` | ROLE_ADMIN | Administrative permissions, including role reads |
| `disabled` / `qmfx_disabled` | ROLE_USER + ROLE_QUIZ_CREATOR; inactive | Login rejected with 401 |

No ROLE_SUPER_ADMIN is created. ROLE_ADMIN lacks SYSTEM_ADMIN; its name does not
grant access to every operational endpoint. Organization/tenant scenarios are
not modeled by these fixtures.

Definitions are in `fixtures/personas.json` and `fixtures/question-sets.json`.
Two **PRIVATE DRAFT** quizzes contain five questions each: A's **Java basics**
and B's **HTTP basics**. Both cover MCQ_SINGLE, MCQ_MULTI, TRUE_FALSE, FILL_GAP and
ORDERING. JSON includes hand-written request bodies and correct/incorrect answer
examples. AI, images and remote storage are not used. Attempt scenarios must
publish a quiz through the normal application operation first; seeding does not
publish fixtures.

Repeated `fixtures` preserves UUIDs/passwords and checks content, ownership,
private draft status and question count. Mismatches stop seeding instead of
overwriting user edits. The operation is not one transaction: state is saved
after each created object and an interrupted run can resume. Ambiguous matches
and unrelated accounts with matching usernames are never claimed.

Generated passwords/IDs are stored locally in `server/local/fixtures.private.json`
and `data/Personas.private.json`, both mode `600` and ignored by Git. Values are
not printed to the terminal. `data/Access.private.json` supplies local
`accessFixtureData` for **11 Access** and later scenarios. Refresh that Postman
value from the new private file after recreating fixture accounts or the database.

`Personas.private.json` is a JSON iteration-data array for future requests using
`{{username}}`, `{{password}}`, `{{userId}}`, `{{javaQuizId}}` and `{{httpQuizId}}`.
Attach it to local Runner **Iteration data** when a matching persona scenario is
available; current **10 Auth** does not need it. Do not import it as a shared
collection or environment.

Keep private state together with its database: without state, seeding neither
resets passwords nor claims existing accounts. Deleted/modified fixtures require
an explicit decision; there is no automatic repair, reset or deletion.

## Stop and diagnose

```bash
./tools/local-api.sh logs
./tools/local-api.sh stop
./tools/local-api.sh down
```

`stop` stops containers; `down` also removes containers and networks. Both keep
MySQL/uploads in `quizmaker-postman` volumes. If port `8080` is occupied, stop the
local process using it and retry. On migration failure, inspect logs: the script
does not run automatic Flyway repair or delete data. Local MySQL disables binlog
so migrations with stored functions can run as schema owner without global SUPER.

## Coverage boundaries

The application has no outbound internet access. It uses fake OpenAI, Stripe and
storage settings, the existing no-op email provider, empty Stripe Price IDs and
disabled local pack synchronization. Real root `.env` keys are not passed into
the container. Payments, provider OAuth, AI generation, email delivery and remote
media need a separate test environment with stubs or an agreed sandbox connection.

These checks do not replace the unit/integration suite. No JPA read paths are
added. Earlier MySQL `performance_schema.events_statements_summary_by_digest`
measurements found **11 SELECTs** for question pages containing one or five linked
questions, **9** for quiz details with one or five questions, and **9** for own
quiz pages with one or two items. Counts include authentication/permission reads
and exclude diagnostic MySQL client queries. Question SQL uses separate
pagination and two batched relationship loads; query count did not increase
within these fixtures. Evidence covers these local paths and sizes, not the
entire application.

Closest repository examples: `server/backend/Dockerfile`,
`server/backend/docker-compose.yml`, `docs/runbooks/health-probes.md`,
`shared/api/UtilityController`, `shared/config/SecurityConfig` and
`shared/api/docs/ApiDiscoveryController` under `src/main/java/uk/gegc/quizmaker`.

Auth references: `features/auth/api/AuthController`, `RegisterRequest`,
`LoginRequest`, `JwtResponse` and `features/auth/application/impl/AuthServiceImpl`.
Existing permissions: `src/main/resources/policy/role-permission-manifest.json`.
Question formats: `docs/question-contracts.md` and
`src/test/resources/question-contracts/baseline-v1-golden.json`.

Check local seeding safeguards without changing the database:

```bash
PYTHONPATH=tools python3 -B -m unittest tools/test_local_api_fixtures.py -v
```


## Custom review progression: 23 Custom Learning

The issue-branch implementation of [#840](https://github.com/Gegcuk/QuizMaker/issues/840)
adds custom review progression through the canonical Learning session commands.
This scenario deliberately targets that approved implementation before merge.
It requires `sessions-enabled`, `custom-plans-enabled` and the separate
`custom-reviews-enabled` opt-in. The dedicated local configuration enables all
three; enabling plan management alone preserves its earlier behavior.

Run the complete **23 Custom Learning** folder with **49 requests**, **Local**,
one iteration and no iteration data. Reuse the existing local, unshared
`accessFixtureData` Value with `qmfx_author_a` and `qmfx_author_b`; the public
export remains empty. Disable **Persist responses for a session**, **Keep
variable values**, **Save cookies after collection run** and **Stop run if an
error occurs**. Enable **Turn off logs during run** and **Run collection without
using stored cookies**. Respect `Retry-After` on a limited run; do not change
shared budgets or retry state-changing commands with new IDs automatically.

The first five requests read live discovery and the Learning, quizzes, questions
and auth specifications. Relevant Learning operations are `start`, `respond`,
`change`, `preview`, `confirm` and `cycle`. Named schemas are loaded from that
run's specification, retaining references and null unions. The scenario checks:

- Both active prepared author identities and their exact roles before writes.
- A unique private quiz reusing the original TRUE_FALSE and MCQ_SINGLE questions,
  followed by one immutable publication and a private finite calendar plan.
- One custom question and one FSRS peer in a saved due session; both responses
  advance once in saved order. Full-body exact replay returns the original
  custom acknowledgement; changed input with the same command returns 409.
- Another author cannot read the cycle (404). A timezone preview taken before
  the review conflicts after it (409), with the entire cycle unchanged.
- Fresh explicit timezone confirmation and its exact replay preserve review
  count and stage. Extra practice leaves the whole schedule unchanged.
- An explicit early review completes the finite plan. Its acknowledgement has
  no pending due, the cycle reports completion, and custom due selection omits
  it. The FSRS peer's complete metadata remains unchanged.

Cleanup pauses only the newly created private plan, verifies the new quiz's UUID,
owner and exact unique title before deleting it, rechecks the original question
content/associations, and logs out both sessions independently. `l840Run*` state
is cleared at the end. Original fixtures are never edited or deleted. Existing
immutable publication, review and plan history remains under normal retention;
this is not a purge. A stopped/interrupted run can leave incomplete cleanup or
captured sessions and must be reported rather than silently erased.

The existing D-001 quiz creation assertion stays failed while OpenAPI declares
200 and runtime returns 201. A valid creation acknowledgement still permits
Learning checks and cleanup. Other top-level scenarios and their transitions are
unchanged. Quiz deletion's 404 is checked against the `getQuiz` description;
that operation does not publish a 404 response schema, so the separate Learning
group's schema is not applied to it. Learning errors validate their own live
ProblemDetail contract.

The initial exploratory run exposed missing OpenAPI 3.1 null alternatives in
Learning acknowledgements despite existing `nullable` annotations. The Learning
contract now expresses explicit null unions and RFC 7807 URI references. The
backend verification and local build are repeated after that correction; the
scenario's schema checks remain strict.


Verified on **23 November 2026** against the final local image
`sha256:4062e0ca9db0e63573406966baeacfa7afc3ed2342dfbd4415a733a9bde0ed1e`,
label `b7f39c91f7fd578fa9093a1b3ffa669eaac7f09a-dirty`.
The label alone is insufficient: the verification source fingerprint is
`01788b84557b30cc8816a7154879cc83827ea6e433f02db21216168867d6aa69`,
the rebuilt class fingerprint matches the full backend gate, and the host and
container JAR SHA-256 both equal
`25555cd98fc0351cd6c91d2b86f8433764d62158d772e516aac9b347eb8b0ed2`.
The separately checked local configuration SHA-256 is
`c5aafa0a9014389d6c13ead199b89f7183b1e2b3574f87de0ed7503777e233fe`;
`server/local` is outside the backend helper's source fingerprint.

Two complete desktop runs at **01:53:50** and **01:54:29 UTC** each executed
**49 requests** and **129 assertions: 128 passed, one failed, zero skipped, zero
script errors**. The sole failure was D-001. Every #840 behavior and strict
Learning schema check passed. The runs used fresh quiz/command/session IDs,
retained whole-body exact replays within each run, and completed guarded deletion,
original-question comparison, plan pause and both 204 logout requests. Local
read-only counts afterwards confirmed zero active fixture sessions/captures and
zero unpaused run-created plans, with four custom review events and two timezone
changes across the two runs. Both legacy repetition tables remained empty.
The exploratory run's separate plan was also paused through the public API after
identifying its unique marker and creation time. No immutable history was purged.

D-002 in [the discrepancy register](https://github.com/Gegcuk/QuizMaker/issues/938)
records the initial Learning nullability gap and its verified correction in
#840. D-001 remains unresolved with its original failed assertion. The final
backend gate executed 10,807 tests with no failures, errors or skips, including
four-worker context execution, exclusive suites and merged coverage thresholds.
Local evidence is retained in
`/private/tmp/quizmaker-840-verify-final4-20261123/verify/` and
`/private/tmp/quizmaker-840-evidence.json`; live contract snapshots are in
`/private/tmp/quizmaker-840-openapi-final/`.
