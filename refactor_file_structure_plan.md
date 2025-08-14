### Refactor plan: file/package structure and naming

This plan proposes a pragmatic re-organization of packages, file names, and responsibilities to improve modularity, testability, and navigability, while keeping changes incremental and low-risk. It assumes Spring Boot default component scanning at base package `uk.gegc.quizmaker` and keeps controllers thin per your preference [[memory:3826250]].

---

## Package skeleton (folders only)

Create this directory structure first; it mirrors the Recommended target structure but omits classes/files for quick scaffolding.

```text
src/main/java/uk/gegc/quizmaker/
  features/
    auth/
      api/
        dto/
      application/
      domain/
        model/
        repository/
      infra/
        security/
        mapping/
    document/
      api/
        dto/
      application/
      domain/
        model/
        repository/
      infra/
        converter/
        chunker/
        mapping/
        text/
        util/
    category/
      api/
        dto/
      application/
      domain/
        model/
        repository/
      infra/
        mapping/
    tag/
      api/
        dto/
      application/
      domain/
        model/
        repository/
      infra/
        mapping/
    admin/
      api/
        dto/
      application/
      domain/
      infra/
        mapping/
    ai/
      api/
        dto/
      application/
      domain/
      infra/
        parser/
        analysis/
    result/
      api/
      application/
      domain/
      infra/
        mapping/

  shared/
    api/
      advice/
    config/
    exception/
    security/
      aspect/
      annotation/
    util/
    mapping/
    email/
    rate_limit/

  application/
    scheduler/
    events/

src/main/resources/
  ai/
    prompts/
      base/
      examples/
      question-types/
  db/
    migration/
```

## Goals and principles

- **Feature-first structure**: Group code by business feature (quiz, question, attempt, user, auth, document, social, tag, category, notification, admin, ai, importexport).
- **Layering inside features**: Within each feature, separate `api` (HTTP), `application` (use cases/services/orchestrators), `domain` (entities, repositories, events), and `infra` (mappers, persistence impls, schedulers, external clients).
- **Cross-cutting goes to `shared/`**: Common config, security, validation, exceptions, utilities, and generic mapping helpers.
- **Keep controllers minimal**: Controllers delegate to application services; no heavy business logic in `api`.
- **Name consistency**: Requests end with `Request`, responses with `Response`; DTOs live next to the controllers that use them; repositories are domain-level interfaces.
- **Incremental migration**: Move one feature at a time with green builds in between.

---

## High-level observations (current state)

- Layered-by-technology structure (`controller`, `service`, `dto`, `model`, `repository`, `mapper`, etc.) causes cross-feature sprawl and long files.
- Very large classes: `QuizController` (~918 lines), `ShareLinkController` (~502 lines), `AttemptServiceImpl` (~649 lines). These are indicators to split responsibilities.
- AI-related items live in multiple places (`service/ai`, `util/AiResponseAnalyzer`, `config/AiRateLimitConfig`, `controller/Ai*`). This should be a single feature module.
- Validation annotations and security are cross-cutting; good candidates for `shared/validation` and `shared/security`.
- Events and schedulers are mixed at root; better organized under feature or shared infra.
- Docs: there is a misspelled `docs/conroller/` directory that should be `docs/controller/`.

---

## Recommended target structure (feature-first inside `uk.gegc.quizmaker`)

Place features under `features`, and cross-cutting under `shared`. The tree below is fully populated with your current classes placed in their recommended destinations. Items not present today are omitted; suggested future splits/renames remain in later sections.

```text
src/main/java/uk/gegc/quizmaker/
  features/
     user/
      api/
        UserController.java
        ProfileController.java
        dto/
          AuthenticatedUserDto.java
          UpdateProfileRequest.java
          UpdateUserProfileRequest.java
          UserProfileDto.java
          UserProfileResponse.java
          AvatarUploadResponse.java
      application/
        UserProfileService.java
        UserProfileServiceImpl.java
        AvatarService.java
        AvatarServiceImpl.java
      domain/
        model/
          Permission.java
          PermissionName.java
          Role.java
          RoleName.java
          User.java
        repository/
          PermissionRepository.java
          RoleRepository.java
          UserRepository.java
          UserRoleRepository.java
      infra/
        mapping/
          UserMapper.java
          RoleMapper.java
          PermissionMapper.java

    auth/
      api/
        AuthController.java
        dto/
          ChangePasswordRequest.java
          ForgotPasswordRequest.java
          ForgotPasswordResponse.java
          LoginRequest.java
          RegisterRequest.java
          ResendVerificationRequest.java
          ResendVerificationResponse.java
          ResetPasswordRequest.java
          ResetPasswordResponse.java
          TwoFaVerifyRequest.java
          VerifyEmailRequest.java
          VerifyEmailResponse.java
      application/
        AuthService.java
        AuthServiceImpl.java
      domain/
        model/
          EmailVerificationToken.java
          PasswordResetToken.java
        repository/
          EmailVerificationTokenRepository.java
          PasswordResetTokenRepository.java
      infra/
        security/
          JwtAuthenticationFilter.java
          JwtTokenService.java
          UserDetailsServiceImpl.java
        mapping/
          AuthMapper.java

    document/
      api/
        DocumentController.java
        dto/
          DocumentChunkDto.java
          DocumentConfigDto.java
          DocumentDto.java
          ProcessDocumentRequest.java
      application/
        DocumentProcessingService.java
        DocumentProcessingServiceImpl.java
        DocumentConversionService.java
        DocumentChunkingService.java
        DocumentConverterFactory.java
        DocumentConverter.java
        ConvertedDocument.java
        DocumentProcessingConfig.java
      domain/
        model/
          Document.java
          DocumentChunk.java
        repository/
          DocumentRepository.java
          DocumentChunkRepository.java
      infra/
        converter/
          EpubDocumentConverter.java
          PdfDocumentConverter.java
          TextDocumentConverter.java
        chunker/
          ChapterChunker.java
          UniversalChunker.java
        mapping/
          DocumentMapper.java
        text/
          SentenceBoundaryDetector.java
        util/
          ChunkTitleGenerator.java

    category/
      api/
        CategoryController.java
        dto/
          # none today beyond mapper usage
      application/
        CategoryService.java
        CategoryServiceImpl.java
      domain/
        model/
          Category.java
        repository/
          CategoryRepository.java
      infra/
        mapping/
          CategoryMapper.java

    tag/
      api/
        TagController.java
        dto/
          CreateTagRequest.java
          TagDto.java
          UpdateTagRequest.java
      application/
        TagService.java
        TagServiceImpl.java
      domain/
        model/
          Tag.java
        repository/
          TagRepository.java
      infra/
        mapping/
          TagMapper.java

    admin/
      api/
        AdminController.java
        dto/
          CreateRoleRequest.java
          RoleDto.java
          UpdateRoleRequest.java
      application/
        PermissionService.java
        PermissionServiceImpl.java
        RoleService.java
        RoleServiceImpl.java
      domain/
        # uses user domain models and repositories
      infra/
        mapping/
          # reuses user mappers

    ai/
      api/
        AiAnalysisController.java
        AiChatController.java
        dto/
          ChatRequestDto.java
          ChatResponseDto.java
      application/
        AiQuizGenerationService.java
        AiQuizGenerationServiceImpl.java
        PromptTemplateService.java
        PromptTemplateServiceImpl.java
      domain/
        # relies on quiz/question domain
      infra/
        parser/
          ComplianceQuestionParser.java
          FillGapQuestionParser.java
          HotspotQuestionParser.java
          McqQuestionParser.java
          OpenQuestionParser.java
          OrderingQuestionParser.java
          QuestionParserFactory.java
          QuestionResponseParser.java
          QuestionResponseParserImpl.java
          TrueFalseQuestionParser.java
        analysis/
          AiResponseAnalyzer.java

    result/
      api/
        ReportController.java
      application/
        # future analytics/reporting services
      domain/
        # leverages quiz attempt/view aggregates
      infra/
        mapping/
          ReportMapper.java

  shared/
    api/
      advice/
        GlobalExceptionHandler.java
      UtilityController.java
    config/
      AiRateLimitConfig.java
      AiResponseLoggingConfig.java
      AsyncConfig.java
      CorsConfig.java
      OpenAiConfig.java
      OpenApiConfig.java
      DataInitializer.java
    exception/
      AIResponseParseException.java
      AiServiceException.java
      ApiError.java
      DocumentAccessDeniedException.java
      DocumentNotFoundException.java
      DocumentProcessingException.java
      DocumentStorageException.java
      ForbiddenException.java
      QuizGenerationException.java
      RateLimitExceededException.java
      ResourceNotFoundException.java
      UnauthorizedException.java
      UnsupportedFileTypeException.java
      UnsupportedQuestionTypeException.java
      ValidationException.java
      ShareLinkAlreadyUsedException.java
      UserNotAuthorizedException.java
    security/
      AppPermissionEvaluator.java
      PermissionUtil.java
      aspect/
        PermissionAspect.java
      annotation/
        RequirePermission.java
        RequireRole.java
        RequireResourceOwnership.java
    util/
      DateUtils.java
      JsonUtils.java
      TrustedProxyUtil.java
      XssSanitizer.java
    mapping/
      ScheduleMapper.java
    email/
      EmailService.java
      EmailServiceImpl.java
    rate_limit/
      RateLimitService.java

  application/
    scheduler/
      EmailVerificationTokenCleanupScheduler.java
      TokenCleanupScheduler.java
    events/
      # cross-feature application events (none today)
```

Resources:

```text
src/main/resources/
  application.properties
  application-dev.properties
  application-prod.properties.example
  secret.properties  # avoid committing secrets; prefer env/profiles
  ValidationMessages.properties
  db/migration/
    V1__create_core_tables.sql
    V2__create_password_reset_tokens.sql
    V3__create_email_verification_tokens.sql
    V5__add_email_verification_audit_fields.sql
    V6__add_email_verification_indexes.sql
    V7__add_email_unique_constraint.sql
    V8__add_user_profile_fields.sql
    V9__add_user_version_column.sql
    V10__add_quiz_indexes.sql
  ai/prompts/
    base/
      context-template.txt
      system-prompt.txt
    examples/
      mcq-single-example.json
      open-question-example.json
      true-false-example.json
    question-types/
      compliance.txt
      fill-gap.txt
      hotspot.txt
      matching.txt
      mcq-multi.txt
      mcq-single.txt
      open-question.txt
      ordering.txt
      true-false.txt
```

Tests should mirror the main structure:

```text
src/test/java/uk/gegc/quizmaker/
  features/quiz/api/QuizControllerTest.java
  features/quiz/application/QuizCreationServiceTest.java
  ...
  shared/... (if any shared utilities are tested)
```

---

## Concrete renames and moves

- Move `src/main/java/uk/gegc/quizmaker/controller/**` into `features/<feature>/api/`.
  - Split `QuizController` into smaller controllers by responsibility, e.g. `QuizCrudController`, `QuizPublishingController`, `QuizAccessController`.
  - Keep `ShareLinkController` under `features/quiz/api/` and consider extracting link generation/access logic into `application` services.

- Move `src/main/java/uk/gegc/quizmaker/service/**` into feature `application/` folders. Split `AttemptServiceImpl` into focused services as listed above.

- Move `src/main/java/uk/gegc/quizmaker/dto/<feature>/**` into `features/<feature>/api/dto/`. Ensure suffixes: `*Request`, `*Response`.

- Move `src/main/java/uk/gegc/quizmaker/model/<feature>/**` into `features/<feature>/domain/model/`.

- Move `src/main/java/uk/gegc/quizmaker/repository/<feature>/**` into `features/<feature>/domain/repository/`.

- Move `src/main/java/uk/gegc/quizmaker/mapper/**` into the nearest feature `infra/mapping/`. Keep only truly cross-cutting mappers in `shared/mapping`.

- Move `src/main/java/uk/gegc/quizmaker/event/**` into the relevant feature `domain/event/`. Only generic application events should live under `application/events`.

- Move `src/main/java/uk/gegc/quizmaker/scheduler/**` into either `application/scheduler` (cross-feature) or feature-local `infra/scheduling/`.

- Consolidate AI:
  - `controller/Ai*` → `features/ai/api/`
  - `service/ai/**` and `service/AiChatService.java` → `features/ai/application/`
  - `util/AiResponseAnalyzer.java` → `shared/util/` (if used outside AI) or `features/ai/infra/`
  - `config/Ai*` → `shared/config/`
  - `resources/prompts/**` → `resources/ai/prompts/**`

- Validation and security:
  - `validation/**` → `shared/validation/**`
  - `security/**` → `shared/security/**` (keep JWT and auth concerns in `features/auth/infra/security` if tightly coupled)

- Docs:
  - Rename `docs/conroller/` → `docs/controller/`.

---

## Remaining leftovers to move (current state)

Based on the current filesystem, the following classes still live in the old structure. Move them to the indicated destinations:

- `src/main/java/uk/gegc/quizmaker/util/AiResponseAnalyzer.java` → `src/main/java/uk/gegc/quizmaker/features/ai/infra/analysis/AiResponseAnalyzer.java`

---

## Additional target folders to create (new feature detected)

Add a dedicated repetition feature for spaced repetition logic used by results/analytics and attempts.

```text
src/main/java/uk/gegc/quizmaker/features/repetition/
  api/
    dto/
  application/
    RepetitionService.java
  domain/
    model/
      SpacedRepetitionEntry.java
    repository/
      SpacedRepetitionEntryRepository.java
  infra/
    mapping/
```

## Naming conventions

- **DTOs**: `XxxRequest`, `XxxResponse`. For lists: `XxxListResponse` or `Page<XxxResponse>`.
- **Controllers**: `XxxController` mapped to clear resource paths; split by responsibility to keep files < 300 lines.
- **Services (application)**: Verb/verb-phrase names, e.g., `AttemptSubmissionService`, `QuizPublishingService`.
- **Domain**: Nouns only. Avoid "Impl" in domain; repositories are interfaces.
- **Acronyms**: Prefer `AiChatService` over `AIChatService` for Java class names.

---

## Incremental migration plan (safe steps)

1) Create skeleton packages `features/` and `shared/` plus `application/` for cross-feature schedulers/events.

2) Migrate the smallest, low-risk feature first (e.g., `tag` or `category`) to validate patterns. Run tests.

3) Migrate `user` and `auth`. Move JWT classes into `features/auth/infra/security` or keep generic JWT into `shared/security` if reused.

4) Migrate `quiz`, splitting `QuizController` by responsibility. Move `QuizGenerationCompletedEvent` to `features/quiz/domain/event`.

5) Migrate `question` and `attempt`. Split `AttemptServiceImpl` into 3–4 focused services; keep transactional boundaries in application layer.

6) Migrate `ai` and relocate prompts to `resources/ai/prompts`.

7) Move schedulers: `TokenCleanupScheduler`, `EmailVerificationTokenCleanupScheduler`, `NotificationScheduler` to `application/scheduler/` or feature-local.

8) Move validation, security, exceptions, and common utilities into `shared/`.

9) Update imports and package names. Spring will still auto-scan under `uk.gegc.quizmaker/**`.

10) Ensure tests mirror the new package structure and names. Update imports and mock wiring accordingly.

11) Remove any committed uploaded files under `uploads/documents/` from VCS; store outside repo and configure via properties.

---

## Optional: multi-module Maven later

After stabilizing the feature-first layout, consider splitting into Maven modules only if needed:

- `quizmaker-shared` (shared config/util/security/validation)
- `quizmaker-features` (all features)
- `quizmaker-app` (Spring Boot app module, depends on above)

This is optional and only if you want stronger boundaries and faster builds.

---

## Quick mapping examples (old → new)

- `controller/QuizController.java` → `features/quiz/api/QuizController.java` (and further split)
- `controller/ShareLinkController.java` → `features/quiz/api/ShareLinkController.java`
- `service/attempt/impl/AttemptServiceImpl.java` → `features/attempt/application/AttemptSubmissionService.java` (and peers)
- `dto/quiz/**` → `features/quiz/api/dto/**`
- `model/quiz/**` → `features/quiz/domain/model/**`
- `repository/quiz/**` → `features/quiz/domain/repository/**`
- `mapper/**` → `features/<feature>/infra/mapping/**` or `shared/mapping/**`
- `event/QuizGenerationCompletedEvent.java` → `features/quiz/domain/event/QuizGenerationCompletedEvent.java`
- `scheduler/*Cleanup*` → `application/scheduler/**`
- `security/Jwt*` → `features/auth/infra/security/**` (or `shared/security/**` if generic)
- `util/AiResponseAnalyzer.java` → `shared/util/AiResponseAnalyzer.java`
- `config/AiRateLimitConfig.java` → `shared/config/AiRateLimitConfig.java`
- `resources/prompts/**` → `resources/ai/prompts/**`
- `docs/conroller/` → `docs/controller/`

---

## Post-migration checklist

- All controllers are < 300 lines and delegate to application services.
- DTOs are colocated with controllers and follow naming conventions.
- Domain models and repositories are colocated under each feature.
- Cross-cutting concerns live under `shared/`.
- Tests mirror main structure and still pass.
- Uploads are not committed; paths come from configuration.


