# Week 1-2: Foundation Sprint - Detailed Implementation Plan

## 📋 Sprint Overview

**Duration**: 2 weeks (10 working days)  
**Goal**: Establish core foundation for QuizMaker MVP with moderation, share-links, org structure, and observability  
**Success Criteria**: All core moderation workflows functional, share-links working, org skeleton in place, and observability implemented

---

## 🎯 Sprint Objectives

1. **Moderation System** - Complete workflow with audit trails
2. **Share-Links** - Secure token-based sharing with analytics
3. **Organization Structure** - Multi-tenant foundation with RBAC
4. **Observability** - Correlation IDs, structured logging, idempotency
5. **Search Enhancement** - Faceted search with ETags

---

## 🔧 Technical Corrections (Apply Before Coding)

### 1. **Use UUIDs, not Longs**
- All new entities use `UUID` (`BINARY(16)` in MySQL)
- Use `Instant` over `LocalDateTime` for storage
- Update all controller signatures and service methods

### 2. **Moderation Audit: Single Pattern**
- **Keep only** `QuizModerationAudit` table
- **Remove** JSON `auditTrail` from Quiz entity
- Query "last 10" when needed from the table

### 3. **Permission-Based Authorization**
- Replace `@PreAuthorize("hasRole('MODERATOR')")` with permission checks
- Use `@auth.can(authentication, 'QUIZ_MODERATE', #orgId)`
- Use `@auth.hasPermission(authentication, #quizId, 'Quiz', 'UPDATE')`

### 4. **Scoped Role Bindings**
- Introduce `user_role_bindings(user_id, role_id, scope='ORG', scope_id)`
- Keep `Role` → `Permission` bundles
- Store enum as data for easy migration later

### 5. **Hash-Based Material Change Detection**
- Add `content_hash` and `presentation_hash` to Quiz
- Recompute hashes from DTOs on save
- Avoid deep object comparisons

### 6. **Secure Share-Links**
- Store **hashed token** (SHA256) in DB
- Mint httpOnly, SameSite=Lax, Secure cookies
- Add revoke endpoint with cookie invalidation

### 7. **Derived Balance Design**
- Treat balance as **derived** from double-entry ledger
- Keep wallet metadata only (caps, reset date)
- Don't persist balance as truth

---

## 📅 Day-by-Day Implementation Plan

### Day 1: Database Schema & Moderation Status

#### Tasks
1. **Add Moderation Status to Quiz Model**
   - Add `PENDING_REVIEW` and `REJECTED` to existing `QuizStatus` enum
   - Add `reviewedAt`, `reviewedBy`, `rejectionReason` fields to Quiz entity
   - Add `content_hash` and `presentation_hash` fields for change detection
   - **Remove** `auditTrail` JSON field (use table instead)

2. **Create Moderation Audit Model**
   ```java
   @Entity
   public class QuizModerationAudit {
       @Id
       private UUID id;
       
       @ManyToOne
       private Quiz quiz;
       
       @ManyToOne
       private User moderator;
       
       @Enumerated(EnumType.STRING)
       private ModerationAction action; // SUBMIT, APPROVE, REJECT, UNPUBLISH
       
       private String reason;
       private Instant createdAt;
       private String correlationId;
   }
   ```

3. **Database Migration**
   - Create Flyway migration script
   - Add indexes for `quizzes(status, visibility, created_at)`
   - Add indexes for `quiz_moderation_audit(quiz_id, created_at)`
   - Use `BINARY(16)` for UUID fields

#### Deliverables
- [ ] Quiz entity updated with moderation fields and hashes
- [ ] QuizModerationAudit entity created with UUID
- [ ] Database migration script ready
- [ ] Unit tests for new fields

#### Acceptance Criteria
- Quiz can transition between DRAFT → PENDING_REVIEW → PUBLISHED/REJECTED
- Audit trail captures all moderation actions in table
- Content and presentation hashes are computed correctly
- Database indexes are optimized for moderation queries

---

### Day 2: Moderation Service & Business Logic

#### Tasks
1. **Create ModerationService**
   ```java
   @Service
   public class ModerationService {
       public void submitForReview(UUID quizId, UUID userId);
       public void approveQuiz(UUID quizId, UUID moderatorId, String reason);
       public void rejectQuiz(UUID quizId, UUID moderatorId, String reason);
       public void unpublishQuiz(UUID quizId, UUID moderatorId, String reason);
       public List<Quiz> getPendingReviewQuizzes(UUID orgId);
       public List<QuizModerationAudit> getQuizAuditTrail(UUID quizId);
   }
   ```

2. **Implement Hash-Based Change Detection**
   ```java
   public class QuizHashCalculator {
       public String calculateContentHash(QuizDto quizDto);
       public String calculatePresentationHash(QuizDto quizDto);
       public boolean hasContentChanged(Quiz original, QuizDto updated);
       public boolean hasPresentationChanged(Quiz original, QuizDto updated);
   }
   ```

3. **Update QuizService with Moderation Logic**
   - Add validation: can't edit while PENDING_REVIEW
   - Auto-transition to PENDING_REVIEW on content hash changes of PUBLISHED quizzes
   - Auto-revert to DRAFT on any edits of PENDING_REVIEW quizzes
   - Recompute hashes on save

#### Deliverables
- [ ] ModerationService with all core methods
- [ ] QuizHashCalculator for change detection
- [ ] QuizService updated with moderation rules
- [ ] Integration tests for moderation workflows

#### Acceptance Criteria
- Hash-based change detection works correctly
- Non-material edits (tags, category) don't affect status
- All moderation actions are audited in table
- Business rules are enforced consistently

---

### Day 3: Moderation Controllers & API Endpoints

#### Tasks
1. **Create ModerationController**
   ```java
   @RestController
   @RequestMapping("/api/v1/admin/quizzes")
   public class ModerationController {
       @PostMapping("/{quizId}/approve")
       @PreAuthorize("@auth.can(authentication, 'QUIZ_MODERATE', #orgId)")
       public ResponseEntity<Void> approveQuiz(@PathVariable UUID quizId, @RequestBody ApproveRequest request);
       
       @PostMapping("/{quizId}/reject")
       @PreAuthorize("@auth.can(authentication, 'QUIZ_MODERATE', #orgId)")
       public ResponseEntity<Void> rejectQuiz(@PathVariable UUID quizId, @RequestBody RejectRequest request);
       
       @GetMapping("/pending-review")
       @PreAuthorize("@auth.can(authentication, 'QUIZ_MODERATE', #orgId)")
       public ResponseEntity<Page<QuizDto>> getPendingReviewQuizzes(@RequestParam UUID orgId, Pageable pageable);
   }
   ```

2. **Update QuizController**
   ```java
   @PostMapping("/{quizId}/submit-for-review")
   @PreAuthorize("@auth.hasPermission(authentication, #quizId, 'Quiz', 'UPDATE')")
   public ResponseEntity<Void> submitForReview(@PathVariable UUID quizId);
   
   @PostMapping("/{quizId}/unpublish")
   @PreAuthorize("@auth.hasPermission(authentication, #quizId, 'Quiz', 'UPDATE')")
   public ResponseEntity<Void> unpublishQuiz(@PathVariable UUID quizId, @RequestBody UnpublishRequest request);
   ```

3. **Create DTOs**
   - `ApproveRequest`, `RejectRequest`, `UnpublishRequest`
   - `QuizModerationAuditDto`
   - `PendingReviewQuizDto`

#### Deliverables
- [ ] ModerationController with permission-based authorization
- [ ] QuizController updated with moderation endpoints
- [ ] All DTOs created and validated
- [ ] OpenAPI documentation updated

#### Acceptance Criteria
- All moderation endpoints use permission checks
- Authorization is enforced with org scope
- Request/response DTOs are properly validated
- API documentation is complete

---

### Day 4: Share-Links Foundation

#### Tasks
1. **Create ShareLink Entity**
   ```java
   @Entity
   public class ShareLink {
       @Id
       private UUID id;
       
       @ManyToOne
       private Quiz quiz;
       
       @ManyToOne
       private User createdBy;
       
       private String tokenHash; // SHA256 hash of the token
       private ShareLinkScope scope; // QUIZ_VIEW, QUIZ_ATTEMPT_START
       private Instant expiresAt;
       private boolean oneTime;
       private Instant revokedAt;
       private Instant createdAt;
   }
   ```

2. **Create ShareLinkService**
   ```java
   @Service
   public class ShareLinkService {
       public ShareLink createShareLink(UUID quizId, UUID userId, CreateShareLinkRequest request);
       public ShareLink validateToken(String token);
       public void revokeShareLink(UUID shareLinkId, UUID userId);
       public List<ShareLink> getUserShareLinks(UUID userId);
       public void recordShareLinkUsage(String tokenHash, String userAgent, String ipAddress);
   }
   ```

3. **Token Generation & Security**
   - Implement secure token generation (cryptographically random)
   - Hash tokens with SHA256 before storage
   - Add rate limiting for token creation and consumption
   - Implement token validation with proper error handling

#### Deliverables
- [ ] ShareLink entity with hashed tokens
- [ ] ShareLinkService with core methods
- [ ] Secure token generation and hashing utility
- [ ] Rate limiting configuration

#### Acceptance Criteria
- Tokens are cryptographically secure and hashed
- Rate limiting prevents abuse on creation and consumption
- All share link operations are properly validated
- Database indexes are optimized for token lookups

---

### Day 5: Share-Links Controllers & Cookie Management

#### Tasks
1. **Create ShareLinkController**
   ```java
   @RestController
   @RequestMapping("/api/v1/quizzes")
   public class ShareLinkController {
       @PostMapping("/{quizId}/share-link")
       @PreAuthorize("@auth.hasPermission(authentication, #quizId, 'Quiz', 'SHARE')")
       public ResponseEntity<ShareLinkDto> createShareLink(@PathVariable UUID quizId, @RequestBody CreateShareLinkRequest request);
       
       @DeleteMapping("/shared/{tokenId}")
       @PreAuthorize("@auth.hasPermission(authentication, #tokenId, 'ShareLink', 'DELETE')")
       public ResponseEntity<Void> revokeShareLink(@PathVariable UUID tokenId);
       
       @GetMapping("/shared/{token}")
       public ResponseEntity<Void> accessSharedQuiz(@PathVariable String token, HttpServletResponse response);
   }
   ```

2. **Implement Cookie Management**
   ```java
   @Component
   public class ShareLinkCookieManager {
       public void setShareLinkCookie(HttpServletResponse response, String token, UUID quizId);
       public String getShareLinkToken(HttpServletRequest request);
       public void clearShareLinkCookie(HttpServletResponse response);
       public void invalidateCookiesForQuiz(UUID quizId);
   }
   ```

3. **Create Share Link Analytics**
   - Track share link usage events with hashed IP
   - Record view/attempt metrics with `shareTokenId`
   - Implement analytics aggregation

#### Deliverables
- [ ] ShareLinkController with all endpoints
- [ ] Cookie management with httpOnly, Secure, SameSite
- [ ] Share link analytics tracking with privacy protection
- [ ] Integration tests for share link flow

#### Acceptance Criteria
- Share links can be created, accessed, and revoked
- Cookies are properly set with security attributes
- Analytics events are tracked with privacy protection
- Security is maintained throughout the flow

---

### Day 6: Organization Foundation

#### Tasks
1. **Create Organization Entity**
   ```java
   @Entity
   public class Organization {
       @Id
       private UUID id;
       
       private String name;
       private String slug; // unique identifier
       private String description;
       private OrganizationStatus status; // ACTIVE, SUSPENDED, DELETED
       
       @OneToMany(mappedBy = "organization")
       private Set<OrganizationMembership> memberships;
       
       @OneToOne(mappedBy = "organization")
       private OrganizationWallet wallet;
       
       private Instant createdAt;
       private Instant updatedAt;
   }
   ```

2. **Create OrganizationMembership Entity**
   ```java
   @Entity
   public class OrganizationMembership {
       @Id
       private UUID id;
       
       @ManyToOne
       private Organization organization;
       
       @ManyToOne
       private User user;
       
       @Enumerated(EnumType.STRING)
       private OrganizationRole role; // For quick start, migrate to bindings later
       
       private Instant joinedAt;
       private Instant updatedAt;
   }
   ```

3. **Create UserRoleBinding Entity (Future-Proof)**
   ```java
   @Entity
   public class UserRoleBinding {
       @Id
       private UUID id;
       
       @ManyToOne
       private User user;
       
       @ManyToOne
       private Role role;
       
       private String scope; // 'ORG', 'GLOBAL'
       private UUID scopeId; // org_id for ORG scope, null for GLOBAL
       
       private Instant createdAt;
   }
   ```

4. **Create OrganizationWallet Entity**
   ```java
   @Entity
   public class OrganizationWallet {
       @Id
       private UUID id;
       
       @OneToOne
       private Organization organization;
       
       // Don't store balance - derive from ledger
       private Long monthlyBudget;
       private Instant lastResetAt;
       
       @OneToMany(mappedBy = "wallet")
       private List<WalletTransaction> transactions;
   }
   ```

#### Deliverables
- [ ] Organization entity with UUID
- [ ] OrganizationMembership entity for role management
- [ ] UserRoleBinding entity for future flexibility
- [ ] OrganizationWallet entity (balance derived from ledger)
- [ ] Database migration scripts

#### Acceptance Criteria
- Organizations can be created and managed
- Members can be assigned roles
- Role bindings support future B2B needs
- Wallet structure supports token management without balance drift

---

### Day 7: Organization Services & RBAC

#### Tasks
1. **Create OrganizationService**
   ```java
   @Service
   public class OrganizationService {
       public Organization createOrganization(CreateOrganizationRequest request, UUID adminUserId);
       public OrganizationMembership addMember(UUID orgId, UUID userId, OrganizationRole role);
       public void removeMember(UUID orgId, UUID userId);
       public void updateMemberRole(UUID orgId, UUID userId, OrganizationRole newRole);
       public List<OrganizationMembership> getOrganizationMembers(UUID orgId);
       public boolean hasRole(UUID orgId, UUID userId, OrganizationRole role);
   }
   ```

2. **Create OrganizationWalletService**
   ```java
   @Service
   public class OrganizationWalletService {
       public OrganizationWallet getWallet(UUID orgId);
       public void addTokens(UUID orgId, Long amount, String reason);
       public boolean consumeTokens(UUID orgId, Long amount, String reason);
       public List<WalletTransaction> getTransactionHistory(UUID orgId);
       public void setMonthlyBudget(UUID orgId, Long budget);
       public Long getBalance(UUID orgId); // Derived from ledger
   }
   ```

3. **Implement Permission-Based Authorization**
   ```java
   @Component
   public class AuthorizationService {
       public boolean can(Authentication auth, String permission, UUID orgId);
       public boolean hasPermission(Authentication auth, UUID resourceId, String resourceType, String action);
       public boolean hasRole(Authentication auth, UUID orgId, String role);
   }
   ```

#### Deliverables
- [ ] OrganizationService with member management
- [ ] OrganizationWalletService with derived balance
- [ ] Permission-based authorization service
- [ ] Unit tests for all services

#### Acceptance Criteria
- Organizations can be created and members managed
- Permission-based authorization is enforced
- Token operations use derived balance
- All business logic is tested

---

### Day 8: Organization Controllers & Quiz Visibility

#### Tasks
1. **Create OrganizationController**
   ```java
   @RestController
   @RequestMapping("/api/v1/orgs")
   public class OrganizationController {
       @PostMapping
       @PreAuthorize("isAuthenticated()")
       public ResponseEntity<OrganizationDto> createOrganization(@RequestBody CreateOrganizationRequest request);
       
       @PostMapping("/{orgId}/members")
       @PreAuthorize("@auth.can(authentication, 'ORG_MANAGE_MEMBERS', #orgId)")
       public ResponseEntity<OrganizationMemberDto> addMember(@PathVariable UUID orgId, @RequestBody AddMemberRequest request);
       
       @GetMapping("/{orgId}/catalog")
       @PreAuthorize("@auth.can(authentication, 'ORG_VIEW_CATALOG', #orgId)")
       public ResponseEntity<Page<QuizDto>> getOrgCatalog(@PathVariable UUID orgId, Pageable pageable);
   }
   ```

2. **Update Quiz Entity for Organization Support**
   - Add `organization` field to Quiz entity
   - Add `visibility` field with values: `PUBLIC`, `PRIVATE`, `ORG`
   - Update quiz creation to support organization context

3. **Update QuizService for Organization Scoping**
   - Filter quizzes by organization membership
   - Enforce visibility rules based on user's org permissions
   - Update quiz listing to respect org boundaries

#### Deliverables
- [ ] OrganizationController with permission-based authorization
- [ ] Quiz entity updated for organization support
- [ ] QuizService updated with org scoping
- [ ] Integration tests for org workflows

#### Acceptance Criteria
- Organizations can be created via API
- Members can be added with proper permissions
- Quiz visibility respects organization boundaries
- All operations use permission-based authorization

---

### Day 9: Observability & Idempotency

#### Tasks
1. **Implement Correlation IDs (Single Filter)**
   ```java
   @Component
   public class CorrelationIdFilter extends OncePerRequestFilter {
       @Override
       protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain);
   }
   ```

2. **Create Idempotency Store**
   ```java
   @Entity
   public class IdempotencyKey {
       @Id
       private String key;
       
       private UUID actorId;
       private Instant firstSeenAt;
       private String resultHash; // Hash of response, not full body
       private String resourceLocation; // Location/ID of created resource
       private Instant ttl;
   }
   
   @Service
   public class IdempotencyService {
       public IdempotencyResult processRequest(String key, UUID actorId, Supplier<String> operation);
       public void cleanupExpiredKeys();
   }
   ```

3. **Implement Structured Logging**
   ```java
   @Component
   public class StructuredLoggingFilter extends OncePerRequestFilter {
       @Override
       protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain);
   }
   ```

#### Deliverables
- [ ] Single correlation ID filter
- [ ] Idempotency store with hash and resource location
- [ ] Structured logging with privacy protection
- [ ] Cleanup job for expired keys

#### Acceptance Criteria
- Correlation IDs are propagated across all requests
- Idempotency prevents duplicate operations
- Structured logs are generated with privacy protection
- All components are properly integrated

---

### Day 10: Search Enhancement & Integration Testing

#### Tasks
1. **Enhance Search with Facets**
   ```java
   @Service
   public class EnhancedSearchService {
       public SearchResult searchQuizzes(SearchRequest request);
       public List<SearchFacet> getSearchFacets(String query);
   }
   
   public class SearchRequest {
       private String query;
       private Set<String> categories;
       private Set<String> tags;
       private QuizStatus status;
       private QuizVisibility visibility;
       private UUID organizationId; // for org-scoped search
       private Pageable pageable;
   }
   ```

2. **Implement ETags for Caching**
   ```java
   @Component
   public class ETagService {
       public String generateETag(Object entity);
       public boolean isNotModified(String etag, HttpServletRequest request);
       public void setETagHeader(String etag, HttpServletResponse response);
   }
   ```

3. **Comprehensive Integration Testing**
   - Test complete moderation workflow
   - Test share link creation and access
   - Test organization creation and member management
   - Test search with various filters
   - Test idempotency across all endpoints

#### Deliverables
- [ ] Enhanced search with faceted filtering
- [ ] ETag implementation for caching
- [ ] Comprehensive integration test suite
- [ ] Performance benchmarks

#### Acceptance Criteria
- Search works with multiple filters
- ETags improve caching performance
- All integration tests pass
- Performance meets requirements

---

## 🧪 Testing Strategy

### Unit Tests
- [ ] ModerationService business logic
- [ ] ShareLinkService token operations
- [ ] OrganizationService member management
- [ ] IdempotencyService key management
- [ ] All DTOs validation
- [ ] Hash calculation and change detection

### Integration Tests
- [ ] Complete moderation workflow
- [ ] Share link creation and access flow
- [ ] Organization creation and RBAC
- [ ] Search with various filters
- [ ] Idempotency across endpoints
- [ ] Permission-based authorization

### Performance Tests
- [ ] Search performance with large datasets
- [ ] Share link token validation performance
- [ ] Organization member queries
- [ ] Database query optimization

---

## 🔧 Configuration & Deployment

### Environment Variables
```properties
# Moderation
quizmaker.moderation.auto-approve=false
quizmaker.moderation.max-audit-trail-size=10

# Share Links
quizmaker.share-links.default-expiry-hours=168
quizmaker.share-links.rate-limit-per-minute=10
quizmaker.share-links.cookie-ttl-seconds=3600

# Organizations
quizmaker.organizations.default-monthly-budget=1000
quizmaker.organizations.max-members-per-org=100

# Idempotency
quizmaker.idempotency.ttl-hours=24
quizmaker.idempotency.cleanup-interval-minutes=60

# Observability
quizmaker.logging.correlation-id-header=X-Correlation-ID
quizmaker.logging.structured-format=true
quizmaker.logging.redact-sensitive=true
```

### Database Indexes
```sql
-- Quiz moderation
CREATE INDEX idx_quizzes_status_visibility_created ON quizzes(status, visibility, created_at);
CREATE INDEX idx_quizzes_org_status_visibility ON quizzes(org_id, status, visibility, created_at);
CREATE INDEX idx_quiz_moderation_audit_quiz_created ON quiz_moderation_audit(quiz_id, created_at);

-- Share links
CREATE INDEX idx_share_links_token_hash ON share_links(token_hash);
CREATE INDEX idx_share_links_quiz_expires ON share_links(quiz_id, expires_at);

-- Organizations
CREATE INDEX idx_org_memberships_org_user ON organization_memberships(organization_id, user_id);
CREATE UNIQUE INDEX idx_org_memberships_unique ON organization_memberships(organization_id, user_id);
CREATE INDEX idx_user_role_bindings_user_scope ON user_role_bindings(user_id, scope, scope_id);

-- Idempotency
CREATE UNIQUE INDEX idx_idempotency_key_actor ON idempotency_keys(key, actor_id);
CREATE INDEX idx_idempotency_ttl ON idempotency_keys(ttl);
```

---

## 📊 Success Metrics

### Functional Metrics
- [ ] 100% of moderation workflows functional
- [ ] Share links work for all quiz types
- [ ] Organization RBAC enforced correctly
- [ ] Search returns results within 200ms
- [ ] Idempotency prevents duplicate operations
- [ ] Permission-based authorization working

### Performance Metrics
- [ ] Database queries optimized (N+1 eliminated)
- [ ] Search response time < 200ms
- [ ] Share link validation < 50ms
- [ ] Organization member queries < 100ms
- [ ] Hash calculation < 10ms

### Quality Metrics
- [ ] 90%+ test coverage
- [ ] All integration tests passing
- [ ] No critical security vulnerabilities
- [ ] API documentation complete
- [ ] Privacy protection in place

---

## 🚨 Risk Mitigation

### Technical Risks
1. **Database Performance**: Monitor query performance, add indexes as needed
2. **Token Security**: Use cryptographically secure random generation and hashing
3. **RBAC Complexity**: Start with scoped bindings, migrate from enum gradually
4. **Idempotency Storage**: Use hash and resource location, not full response body
5. **Balance Drift**: Derive balance from ledger, never store as field

### Business Risks
1. **Moderation Backlog**: Implement auto-approval for trusted users
2. **Share Link Abuse**: Implement rate limiting and monitoring
3. **Organization Complexity**: Start with basic roles, expand with bindings
4. **Privacy Compliance**: Hash sensitive data in logs and analytics

---

## 📝 Definition of Done

A task is considered complete when:
- [ ] Code is implemented and tested
- [ ] Unit tests pass with >90% coverage
- [ ] Integration tests pass
- [ ] API documentation is updated
- [ ] Database migrations are tested
- [ ] Performance benchmarks meet requirements
- [ ] Security review is completed
- [ ] Code review is approved
- [ ] Privacy protection is implemented
- [ ] UUIDs are used consistently

---

*This sprint plan provides a detailed roadmap for implementing the foundation features with proper technical architecture. Each day builds upon the previous, ensuring a solid foundation for the QuizMaker MVP.*
