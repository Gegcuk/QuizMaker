# QuizMaker

Backend API for creating, managing, and taking quizzes—with document import, AI-assisted question generation, spaced repetition, and optional billing.

## Tech stack

- **Java 17** · **Spring Boot 3.4**
- **Spring Security** (JWT + OAuth2)
- **Spring Data JPA** · **MySQL 8** · **Flyway**
- **OpenAPI / Swagger UI** (springdoc)
- **OpenAI** (optional, for AI quiz generation and document processing)
- **Stripe** (optional, for billing)
- **AWS SES** or SMTP (email)
- **DigitalOcean Spaces** (media/CDN)

## Prerequisites

- JDK 17+
- Maven 3.8+ (or use `./mvnw`)
- MySQL 8.x (local or Docker)
- Optional: OpenAI API key, Stripe keys, AWS SES, OAuth2 app credentials (see [Configuration](#configuration))

## Getting started

### 1. Clone and build

```bash
git clone <repo-url>
cd QuizMaker
./mvnw -q -DskipTests=true package
```

### 2. Database

Create a MySQL database and user (or use Docker):

```bash
# Example: MySQL 8 container
docker run -d --name quizmaker-mysql \
  -e MYSQL_DATABASE=quizmakerdb \
  -e MYSQL_USER=lovelyuser \
  -e MYSQL_PASSWORD=lovelyuser \
  -e MYSQL_ROOT_PASSWORD=root \
  -p 3306:3306 \
  mysql:8.4
```

Default dev URL: `jdbc:mysql://localhost:3306/quizmakerdb?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC`

### 3. Configuration

Copy the example env file and set at least the required values:

```bash
cp env.example .env
# Edit .env: JWT_SECRET, TOKEN_PEPPER_SECRET, DB credentials, etc.
```

For local development you can use:

- `APP_EMAIL_PROVIDER=noop` (no real email)
- `quizmaker.features.billing=false` if not using Stripe

See [env.example](env.example) for all options (OpenAI, Stripe, OAuth2, SES, media, etc.).

### 4. Run

```bash
./mvnw spring-boot:run
```

Or run the packaged JAR:

```bash
java -jar target/QuizMaker-0.0.1-SNAPSHOT.jar
```

- API base: `http://localhost:8080`
- Swagger UI: `http://localhost:8080/swagger-ui.html`
- Actuator health: `http://localhost:8080/actuator/health`

## Building and testing

```bash
# Compile and run tests (requires MySQL for integration tests)
./mvnw -q test

# Package without tests
./mvnw -q -DskipTests=true package
```

Tests use the `test` profile and expect a MySQL test database (e.g. `quizmaker_test`). See [src/test/resources/application-test.properties](src/test/resources/application-test.properties). CI runs the full suite with a MySQL service container (see [.github/workflows/ci.yml](.github/workflows/ci.yml)).

## Project structure

- **`src/main/java/uk/gegc/quizmaker/`**
  - **`QuizMakerApplication.java`** — entry point
  - **`features/`** — domain-oriented modules:
    - **auth** — registration, login, JWT, OAuth2
    - **user** — user profile and roles
    - **quiz** — quizzes, questions, categories, tags, import/export
    - **attempt** — taking quizzes, answers, results
    - **repetition** — spaced repetition (SM-2), due entries, reminders
    - **article** — content articles and media
    - **document** / **documentProcess** — document upload, chunking, AI processing
    - **ai** — AI quiz generation (OpenAI)
    - **billing** — Stripe checkout and subscriptions
    - **admin** — admin API
    - **quizgroup** — group management
    - **media** — media assets (e.g. Spaces)
    - **tag**, **category**, **result**, **bugreport**, **conversion**
  - **`shared/`** — config, security, email, rate limiting, exceptions, OpenAPI groups
- **`src/main/resources/`**
  - **`application.properties`** — main config
  - **`db/migration/`** — Flyway SQL migrations
  - **`email/`** — email templates
  - **`prompts/`** — AI prompts

## API documentation

When the app is running, OpenAPI docs and Swagger UI are available at:

- **Swagger UI:** `http://localhost:8080/swagger-ui.html`
- **OpenAPI JSON:** `http://localhost:8080/v3/api-docs`

API is grouped by area (quizzes, auth, repetition, documents, etc.) in [OpenApiGroupConfig](src/main/java/uk/gegc/quizmaker/shared/config/OpenApiGroupConfig.java).

## Configuration

Key configuration is via environment variables or `.env` (see [env.example](env.example)):

| Area        | Examples |
|------------|----------|
| **Database** | `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` (or defaults in properties) |
| **JWT**      | `JWT_SECRET`, `TOKEN_PEPPER_SECRET` |
| **OpenAI**   | `OPENAI_API_KEY`, `OPENAI_MODEL` |
| **Stripe**   | `STRIPE_SECRET_KEY`, `STRIPE_WEBHOOK_SECRET`, price IDs |
| **Email**    | `APP_EMAIL_PROVIDER` (ses / smtp / noop), AWS or SMTP vars |
| **OAuth2**   | `OAUTH2_REDIRECT_URI`, `GOOGLE_CLIENT_ID` / `_SECRET`, etc. |
| **Frontend** | `FRONTEND_BASE_URL` |
| **Media**    | `MEDIA_BUCKET`, `MEDIA_REGION`, `DO_SPACES_ACCESS_KEY`, etc. |

Feature flags (e.g. billing) can be set in properties or env.

## Deployment

- **Docker:** see [server/backend/](server/backend/) for `Dockerfile` and `docker-compose.yml`.
- **CI/CD:** GitHub Actions build and test on push/PR ([.github/workflows/ci.yml](.github/workflows/ci.yml)); optional deploy workflow in [deploy-backend.yml](.github/workflows/deploy-backend.yml).

## License

This project is licensed for **non-commercial use only**. You may use, modify, and distribute it for personal, educational, or other non-commercial purposes. Commercial use is not permitted without explicit permission.
