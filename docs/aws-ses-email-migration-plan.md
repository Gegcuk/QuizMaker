# AWS SES Email Service Migration Plan

This plan replaces the current SMTP-based email delivery with Amazon Simple Email Service (SES) using the AWS SDK (HTTPS API), not SMTP. It is written for a junior developer to implement confidently, with rationale for each architectural choice. No application code is provided here; only structure, configuration, and implementation guidance.

---

## Goals

- Deliver all application emails (verification, password reset, notifications) via AWS SES API over HTTPS 443 (works even when port 25 is blocked by the host).
- Keep email provider pluggable and isolated behind an internal `EmailService` interface to avoid vendor lock-in.
- Maintain current call sites (e.g., registration and password reset flows) with zero or minimal changes.
- Add observability and safe fallbacks for production.

Key files in the project today:

- `src/main/java/uk/gegc/quizmaker/shared/email/EmailService.java:1` — interface used by the app.
- `src/main/java/uk/gegc/quizmaker/shared/email/impl/EmailServiceImpl.java:1` — current SMTP-based implementation.
- `src/main/java/uk/gegc/quizmaker/shared/config/EmailConfig.java:1` — config for mail sender fallback.
- `src/main/java/uk/gegc/quizmaker/features/auth/application/impl/AuthServiceImpl.java:1` — calls `EmailService` for verification and reset flows.

---

## High-Level Architecture

- Provider-agnostic interface
  - Keep `EmailService` as the single abstraction the rest of the app uses.
  - Add a new implementation `AwsSesEmailService` that talks to SES via the AWS SDK (SESv2).
  - Keep the existing `EmailServiceImpl` (SMTP) available for local-dry-run or as a fallback.

- Conditional wiring
  - Introduce a property `app.email.provider` with allowed values: `ses`, `smtp`, `noop`.
  - Provide a Spring configuration class that conditionally exposes a `@Primary` `EmailService` bean based on the property value.
  - Default to `noop` in local dev if credentials are missing to avoid accidental sends.

- Message composition and templates
  - Start with “simple” messages: subject + plain text + optional HTML body built in the service (mirrors today’s approach).
  - Optionally adopt SES templates later for higher deliverability and consistency.

- Observability
  - Log message IDs returned by SES for traceability.
  - Emit metrics/counters for attempted/sent/failed emails.

- Reliability
  - Use safe, bounded retries with exponential backoff on transient failures (e.g., 5xx, throttling).
  - Make sending non-blocking to the user flow when appropriate (see “Transactional Events” below).

- Compliance and reputation
  - Configure bounces/complaints via Amazon SNS and ingest them through a small webhook controller to suppress problematic recipients.

---

## AWS Prerequisites and Setup

1) Choose an SES region
   - Pick a single region (e.g., `eu-west-1` or `us-east-1`). Keep it consistent across credentials, SDK config, and DNS setup.

2) Verify a sending identity
   - Preferred: verify your domain (e.g., `example.com`) in SES. SES will provide DNS records to add:
     - Domain verification TXT
     - DKIM CNAMEs (3 records)
     - Optional: custom MAIL FROM domain records
   - Alternative (for quick start): verify a single sender email (e.g., `noreply@example.com`). Domain verification is still recommended for deliverability.

3) Authenticate your domain
   - Ensure SPF (included if you use a custom MAIL FROM) and DMARC (TXT) are set to align with your sending practices.
   - Wait for SES to show “verified” and “DKIM passing” before live sending.

4) Move out of the SES sandbox
   - Request production access in the SES console with expected volumes and use case.
   - In sandbox, you can only send to verified identities.

5) IAM credentials and permissions
   - Create an IAM role (preferred for EC2/ECS) or an IAM user (for local/dev) with the minimal permissions:
     - `ses:SendEmail`
     - `ses:SendRawEmail` (if you plan to support attachments)
     - `ses:GetAccount` (optional: diagnostics)
   - Do not use wide `*` permissions.

6) Event feedback (recommended)
   - Create an SNS topic for SES bounces/complaints.
   - In SES, configure your identity’s feedback to publish events to that topic.
   - You will later expose a webhook endpoint to receive and process SNS notifications.

---

## Application Changes (Java / Spring Boot)

1) Dependencies (no code yet)
   - Add AWS SDK v2 SES client to `pom.xml`:
     - `software.amazon.awssdk:sesv2`
     - `software.amazon.awssdk:auth` (pulled transitively, but listing makes intent explicit)
   - Keep `spring-boot-starter-mail` only if you want SMTP fallback; otherwise it can remain until full cutover.

2) Configuration properties
   - New properties to introduce:
     - `app.email.provider=ses|smtp|noop`
     - `app.email.from=No Reply <noreply@example.com>`
     - `app.email.region=eu-west-1`
     - `app.email.enable-html=true|false`
     - `app.email.ses.configuration-set` (optional, if you will use SES Configuration Sets)
   - Credentials strategy:
     - Prefer the AWS Default Credentials Chain (env vars, profile, EC2/ECS role). For local/dev: set `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, and `AWS_REGION`.
   - Update `.env` and `env.example` accordingly. Do not commit real secrets.

3) Bean wiring
   - Create `AwsSesConfig` (Spring `@Configuration`) to instantiate an `SesV2Client` using the region in `app.email.region` and the default credentials provider.
   - Create `EmailProviderConfig` (Spring `@Configuration`) that:
     - Conditionally exposes `AwsSesEmailService` as `@Primary` when `app.email.provider=ses`.
     - Optionally exposes the old SMTP-based implementation when `app.email.provider=smtp`.
     - Exposes a `NoopEmailService` when `app.email.provider=noop` (logs only, never sends).

4) Service implementation
   - Create `AwsSesEmailService` implementing `EmailService`:
     - Fields: `SesV2Client ses`, `String fromEmail`, boolean `enableHtml`, optional `configurationSetName`.
     - Methods map 1:1 to `EmailService`:
       - `sendEmailVerificationEmail(String email, String token)`
       - `sendPasswordResetEmail(String email, String token)`
     - Build the verification/reset URLs exactly as the current implementation does, using the existing properties:
       - `app.frontend.base-url`
       - `app.auth.reset-token-ttl-minutes`
       - `app.auth.verification-token-ttl-minutes`
     - Compose SES `SendEmailRequest` with `Simple` content:
       - `From` = `app.email.from`
       - `Destination` = the recipient email
       - `Content.Subject` = existing subject properties
       - `Content.Body.Text` = current text content
       - `Content.Body.Html` = optional (if you enable HTML)
     - Send via `ses.sendEmail(...)`, capture and log the SES message ID.

5) Error handling and retries
   - Catch client exceptions and classify them:
     - Retryable (5xx, throttling): back off with jitter and retry a limited number of times.
     - Non-retryable (address suppressed, invalid identity): log and drop. Never leak recipient details in logs.
   - Never throw provider exceptions back to controllers handling user actions to avoid information disclosure. Log and continue (mirrors today’s behavior).

6) Transactional events (recommended improvement)
   - To avoid sending emails when a transaction fails, publish domain events after commit and handle sending in an event listener:
     - Define `UserRegisteredEvent(userId, email)` and `PasswordResetRequestedEvent(email, token)`.
     - Use `@TransactionalEventListener(phase = AFTER_COMMIT)` to call `EmailService`.
   - This keeps `AuthServiceImpl` simple while ensuring emails only go out after successful persistence.

7) Optional: templates
   - Phase 1 (simple): keep building strings in the service as today.
   - Phase 2 (HTML): introduce a `TemplateRenderer` interface with an implementation (e.g., Thymeleaf) and switch `AwsSesEmailService` to call it.
   - Phase 3 (SES templates): manage templates in SES and send with `SendEmailRequest` using the Template variant. Store template names and variables in config.

8) Attachments (if ever needed)
   - Use `SendRawEmail` (SES raw message) to support attachments. This requires building a MIME message. Keep this out of scope for the first iteration.

---

## Using the Service in the App

- Current usage remains the same: `AuthServiceImpl` already calls `EmailService` for both verification and reset.
  - Verification: `src/main/java/uk/gegc/quizmaker/features/auth/application/impl/AuthServiceImpl.java:1` (see `generateEmailVerificationToken(...)`).
  - Reset: `src/main/java/uk/gegc/quizmaker/features/auth/application/impl/AuthServiceImpl.java:1` (see `generatePasswordResetToken(...)`).
- After wiring `AwsSesEmailService` as the primary bean, no changes at these call sites are required.

---

## Configuration and Environment

- New `.env` keys to add (examples, do not commit real values):
  - `APP_EMAIL_PROVIDER=ses`
  - `APP_EMAIL_FROM="No Reply <noreply@example.com>"`
  - `APP_EMAIL_REGION=eu-west-1`
  - `AWS_ACCESS_KEY_ID=...` (local only; use roles in prod)
  - `AWS_SECRET_ACCESS_KEY=...` (local only)
  - Optional: `APP_EMAIL_SES_CONFIGURATION_SET=...`
- Map these into Spring via `application-*.properties`:
  - `app.email.provider=${APP_EMAIL_PROVIDER:noop}`
  - `app.email.from=${APP_EMAIL_FROM:noreply@example.com}`
  - `app.email.region=${APP_EMAIL_REGION:eu-west-1}`
  - Keep `spring.mail.*` present but unused when provider != `smtp`.

---

## Bounce and Complaint Handling (SNS)

1) Create an SNS topic (e.g., `ses-feedback`).
2) In the SES console, configure your identity to publish bounces and complaints to that topic.
3) Add a controller endpoint (e.g., `POST /webhooks/ses/sns`) to accept SNS notifications.
4) Implement SNS message verification and subscription confirmation (do not process unverified messages).
5) On `Bounce` or `Complaint` notifications:
   - Extract recipient(s) and mark them as suppressed in your DB (e.g., add a `suppressed` boolean to the user or a suppression table).
   - Log the SES `feedbackId` for traceability.
   - Make processing idempotent using the SNS `MessageId`.
6) Consider wiring SES Configuration Sets + Event Destinations for richer metrics if needed.

Rationale: Handling bounces/complaints protects your sender reputation and prevents repeat sends to problematic addresses.

---

## Observability and Monitoring

- Log: SES message ID and provider outcome (success/failure) at INFO level; full exception details at WARN/ERROR.
- Metrics: counters for attempted/sent/failed, bounce/complaint counts from SNS.
- CloudWatch: set alarms for bounce rate and complaint rate exceeding thresholds.

---

## Security and Compliance

- Use IAM roles in production (EC2/ECS) rather than static keys.
- Follow least-privilege: restrict IAM policy to `ses:SendEmail` (and `ses:SendRawEmail` only if needed).
- Never log full recipient emails; mask as done today.
- Ensure DKIM and DMARC records are configured on the domain.

---

## Rollout Plan

1) Infrastructure
   - Verify domain/email and set DKIM/SPF/DMARC.
   - Move SES out of sandbox.
   - Prepare IAM role/user and rotate keys where applicable.

2) Application
   - Add SES dependencies and new config classes.
   - Implement `AwsSesEmailService` and provider switching via `app.email.provider`.
   - Add `NoopEmailService` for local/dev.

3) Testing
   - Local smoke test: set provider to `ses`, send to a test inbox, verify receipt and headers.
   - Integration test path: register a new user and ensure verification email is sent (watch logs/SES console).
   - Negative tests: invalid recipient and throttling paths (ensure graceful handling, no user-facing leaks).

4) Production
   - Set `APP_EMAIL_PROVIDER=ses` and `APP_EMAIL_FROM` to your verified identity.
   - Monitor CloudWatch and SES dashboards for bounces/complaints.
   - Keep SMTP fallback available for one release; remove later if not needed.

---

## Implementation Checklist (Developer To-Do)

- [ ] Add `software.amazon.awssdk:sesv2` to `pom.xml`.
- [ ] Introduce `app.email.*` properties and wire environment variables.
- [ ] Create `AwsSesConfig` (build `SesV2Client` with region + default creds).
- [ ] Implement `AwsSesEmailService` (map existing subjects/bodies, log SES message ID).
- [ ] Add `EmailProviderConfig` to select `ses|smtp|noop` and annotate `@Primary` appropriately.
- [ ] Add `NoopEmailService` (logs only) for local/dev.
- [ ] Optional: add `TemplateRenderer` abstraction and a simple HTML template for nicer emails.
- [ ] Add SNS webhook controller and suppression handling (phase 2).
- [ ] Update `.env` and `env.example` with new keys (no secrets committed).
- [ ] Manual test in dev, then enable in prod and monitor.

---

## Why This Architecture

- Decoupled provider: The rest of the app stays unaware of SES specifics and keeps using `EmailService`, minimizing blast radius of changes.
- HTTPS API over SMTP: Works in environments where outbound SMTP ports are blocked (e.g., DigitalOcean), improves reliability and observability.
- Gradual adoption: You can start with simple text/HTML content and move to SES templates later without changing callers.
- Operational safety: Metrics, logging, and SNS feedback loops protect sender reputation and simplify troubleshooting.

---

## Where to Put Things

- New classes
  - `src/main/java/uk/gegc/quizmaker/shared/email/impl/AwsSesEmailService.java`
  - `src/main/java/uk/gegc/quizmaker/shared/email/impl/NoopEmailService.java`
  - `src/main/java/uk/gegc/quizmaker/shared/config/AwsSesConfig.java`
  - `src/main/java/uk/gegc/quizmaker/shared/config/EmailProviderConfig.java`
  - Optional (phase 2): `src/main/java/uk/gegc/quizmaker/shared/email/template/TemplateRenderer.java`
- Webhook (phase 2)
  - `src/main/java/uk/gegc/quizmaker/shared/email/webhook/SesSnsController.java`

No other call sites should change: `AuthServiceImpl` continues to use `EmailService` as-is.

---

## Example Use Cases in Project

- On registration, after saving user and creating a verification token, `AuthServiceImpl` triggers `emailService.sendEmailVerificationEmail(...)`.
- On password reset request, a token is generated, saved, and `emailService.sendPasswordResetEmail(...)` is called.
- Future: transactional emails for billing receipts or notifications can call `EmailService` similarly without knowing about SES.

---

If you want me to implement these classes and wire the configuration, say “implement SES now” and I’ll proceed with minimal, focused changes following this plan.

