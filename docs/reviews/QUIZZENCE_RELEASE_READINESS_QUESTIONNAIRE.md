# Owner questionnaire: complete the release-readiness backlog

17 September 2026 · **Step 1: review and questions. Step 2: your answers. Step 3: update/create issues.**

The current issues do not fully cover the review. The companion [coverage register](QUIZZENCE_RELEASE_READINESS_COVERAGE.md) maps all **25 findings (24 backend, one frontend)**, additional observations, technical debt and missing release evidence to existing issues or proposed new work. No GitHub issue, runtime code or deployment has been changed during this step.

This questionnaire was intended to carry forward August decisions, but the 22 September correction identifies repeated or unnecessarily reopened choices. Approved issue decisions take precedence. A recommendation never changes an approved answer or accepted deferral by itself.

> **Correction — 22 September 2026:** The owner is right that substantial decisions were already answered. The original 78 entries were not 78 verified unanswered owner decisions: they mixed unresolved details, engineering preparation and proposals to reopen existing decisions. Five confirmed over-asks below now explicitly require no repeat answer. L-01's execution baseline is also already approved. Other entries must be read together with their existing approved decisions; do not treat every remaining answer field as a missing product decision. A complete new count of genuinely unresolved owner choices has not been established by this targeted correction.

## How to answer without working through every future feature

1. Carry forward the approved initial execution scope in **L-01**. Only an explicit proposal to expand that scope needs another decision; the owner need not approve personal practice again.
2. Answer the account/security, current practice, billing, AI/operations and applicable data-governance questions. Each question states exactly when it is needed.
3. For a feature outside your chosen release, you can answer **DEFER** for the whole group. Its issues remain explicitly blocked and the capability must remain unavailable. Deferral never resolves a reachable defect or silently approves a policy.
4. Use the question IDs, for example `SEC-02: A`, and supply any requested role/value for your selected option. You can approve a named group’s recommendations with exceptions; that is an explicit choice. Blank answers are pending, not approval. A custom answer is welcome.
5. After your answers, I will update/create the issues, their dependency links, the decision ledger and roadmap. If an option needs additional values, they are listed in that question now; missing values remain a named blocker rather than being invented during implementation.

This is a long reference questionnaire because the backlog includes future classroom, Learning, Live, offline and adaptive features. **You do not have to approve those features to release the core product.** Some later work, such as selecting a concrete Learning library manifest, needs a bounded investigation before a final approval; the investigation issue will be ready, while activation remains gated.

Completing the issue list is planning completion. Real-user readiness additionally requires implementing the fixes, passing the release gates and a separate owner go/no-go. This questionnaire does not authorize a reset, charge, paid-provider call, infrastructure change or deployment.

## Question index

| ID | Decision | When needed |
|---|---|---|
| [L-01](#l-01) | Approved execution baseline; optional scope expansion | Do not reapprove personal practice. |
| [SEC-01](#sec-01) | How may an author reuse another author’s questions? | Required for question ownership and reuse issue scopes. |
| [SEC-02](#sec-02) | What proof is needed when connecting a new social login to an existing account? | Required for the OAuth trust fix. |
| [SEC-03](#sec-03) | Should standalone AI chat and document-structure utilities be public features? | Required to close the paid-provider bypass. |
| [SEC-04](#sec-04) | Should an email-verification link work while the user is signed out? | Required for an intentional onboarding contract. |
| [AUTH-01](#auth-01) | How much shared-network traffic should new login, signup and refresh limits allow? | Required to make [#457](https://github.com/Gegcuk/QuizMaker/issues/457) numerically implementable; existing rate limits remain unchanged. |
| [EXEC-01](#exec-01) | What happens when TIMED practice has no valid duration? | First public authenticated practice release. |
| [EXEC-02](#exec-02) | May learners skip or revisit questions in ONE_BY_ONE practice? | First public authenticated practice release. |
| [EXEC-03](#exec-03) | Must first public practice keep all nine current question families? | First public release compatibility and cutover. |
| [EXEC-04](#exec-04) | Can paused starts fall back to legacy after the reset? | First public cutover. |
| [EXEC-05](#exec-05) | What should “time spent on this question” honestly mean? | Before exposing time-spent-per-question statistics. |
| [BILL-01](#bill-01) | When do subscription plan changes affect the token allowance? | Required if plan changes/proration invoices are supported at release. |
| [BILL-02](#bill-02) | Are spend-dependent refund restrictions needed now? | Answer to narrow the supported financial policy surface. |
| [BILL-03](#bill-03) | Should the three currently ignored billing deployment settings be fixed or configurable? | Required for [#503](https://github.com/Gegcuk/QuizMaker/issues/503). |
| [OPS-01](#ops-01) | What simultaneous AI workload must the first release prove it can handle? | Required for a capacity claim. |
| [OPS-02](#ops-02) | How quickly must service and data be recoverable after a failure? | Required before real customer data is accepted. |
| [OPS-03](#ops-03) | Monitoring remains owner-deferred — no repeat answer | ACCEPTED DEFERRAL; no repeat answer. |
| [OPS-04](#ops-04) | Parser OS isolation is explicitly deferred — no repeat answer | ACCEPTED DEFERRAL; no repeat answer. |
| [OPS-05](#ops-05) | How long may an accepted generation request wait before AI work begins? | Required for durable queue and reservation behavior. |
| [AI-01](#ai-01) | How much real-model quality evaluation should be planned? | Required for AI quality sign-off; this answer only records a future evaluation budget. |
| [AI-02](#ai-02) | Is a second AI provider part of the supported release? | Conditional: answer B only if alternate-provider support is required. Otherwise A records current scope. |
| [AI-03](#ai-03) | How should future AI ingestion handle personal information in source material? | Conditional: before flashcard/transcript masking features or a new personal-data ingestion claim is enabled. |
| [DATA-01](#data-01) | Who may stop deletion for a dispute, and later release that hold? | Required before held real-user data or retention cleanup is operated. |
| [DATA-02](#data-02) | How may organizations change the approved retention periods? | Required before organization overrides or automated erasure is enabled. |
| [DATA-03](#data-03) | What remains after a person deletes their account? | Required for the account-erasure contract. |
| [DATA-04](#data-04) | What happens when an organization closes? | Conditional on organization support; required before closure is offered. |
| [DATA-05](#data-05) | May anonymous aggregate history remain after detailed records expire? | Required for cleanup/reporting interaction. |
| [GUEST-01](#guest-01) | How should the approved guest-link allowance refill? | Conditional: before any guest exchange is enabled. |
| [GUEST-02](#guest-02) | What should users and support experience when guest-link exchange protection is unavailable? | Conditional: before guest exchange is enabled. |
| [FUT-ASSIGN-01](#fut-assign-01) | Which resources does their creator automatically manage? | Before formal assignment administration. |
| [FUT-ASSIGN-02](#fut-assign-02) | Who may add or remove resource managers? | Before formal assignment administration. |
| [FUT-ASSIGN-03](#fut-assign-03) | Which rights does the previous owner retain after a transfer? | Before formal assignment administration. |
| [FUT-ASSIGN-04](#fut-assign-04) | May an authorized manager cancel one individual attempt? | Before formal assignment administration. |
| [FUT-ASSIGN-05](#fut-assign-05) | What should the participant see after authority cancellation? | Before formal assignment administration. |
| [FUT-TAGS-01](#fut-tags-01) | May a tag be retired while published quizzes still reference it? | Before taxonomy-dependent selection or reports. |
| [FUT-TAGS-02](#fut-tags-02) | Do tags need translated display names in their first release? | Before taxonomy-dependent selection or reports. |
| [FUT-TAGS-03](#fut-tags-03) | Who manages group tags after membership changes? | Before taxonomy-dependent selection or reports. |
| [FUT-TAGS-04](#fut-tags-04) | Which visually similar Unicode tag names count as the same name? | Before taxonomy-dependent selection or reports. |
| [FUT-RELEASE-01](#fut-release-01) | How does an assignment choose when results become visible? | Before formal result release or visible correction notifications. |
| [FUT-RELEASE-02](#fut-release-02) | Which changes to a visible result should notify the learner? | Before formal result release or visible correction notifications. |
| [FUT-RELEASE-03](#fut-release-03) | Which channels deliver result-change notifications? | Before formal result release or visible correction notifications. |
| [FUT-RELEASE-04](#fut-release-04) | How long should failed result notifications be retried? | Before formal result release or visible correction notifications. |
| [FUT-RELEASE-05](#fut-release-05) | Must learners explicitly acknowledge result-change notifications? | Before formal result release or visible correction notifications. |
| [FUT-TIME-01](#fut-time-01) | How precisely should extra time be calculated? | Before extra-time accommodations. |
| [FUT-TIME-02](#fut-time-02) | What maximum duration applies after an extra-time grant? | Before extra-time accommodations. |
| [FUT-TIME-03](#fut-time-03) | Which extra-time grant wins if several are active? | Before extra-time accommodations. |
| [FUT-AUDIT-01](#fut-audit-01) | Which actions and reads require mandatory audit evidence? | Before consequential operator actions and sensitive-access audit/export. |
| [FUT-AUDIT-02](#fut-audit-02) | Which reads remain available if mandatory audit storage fails? | Before consequential operator actions and sensitive-access audit/export. |
| [FUT-AUDIT-03](#fut-audit-03) | How should audit-export failures be retried and retained? | Before consequential operator actions and sensitive-access audit/export. |
| [FUT-AUDIT-04](#fut-audit-04) | Who may manually retry audit delivery? | Before consequential operator actions and sensitive-access audit/export. |
| [FUT-REPAIR-01](#fut-repair-01) | Which proven recovery operations may run automatically? | Before automatic repair; core release still needs a safe manual/quarantine operating boundary. |
| [FUT-REPAIR-02](#fut-repair-02) | How many automatic repair attempts should precede quarantine? | Before automatic repair; core release still needs a safe manual/quarantine operating boundary. |
| [FUT-REPAIR-03](#fut-repair-03) | Who handles quarantined attempts and what does the learner see? | Before automatic repair; core release still needs a safe manual/quarantine operating boundary. |
| [FUT-REPAIR-04](#fut-repair-04) | How does a resolved quarantined attempt resume? | Before automatic repair; core release still needs a safe manual/quarantine operating boundary. |
| [FUT-REPORT-01](#fut-report-01) | What does a report’s average score measure? | Before teacher/cohort reporting. |
| [FUT-REPORT-02](#fut-report-02) | Which repeated attempts contribute to report metrics? | Before teacher/cohort reporting. |
| [FUT-REPORT-03](#fut-report-03) | Do teacher reports initially include individual participant summaries? | Before teacher/cohort reporting. |
| [FUT-REPORT-04](#fut-report-04) | May authorized users download aggregate reports? | Before teacher/cohort reporting. |
| [FUT-REPORT-05](#fut-report-05) | What page sizes and query response target should reports have? | Before teacher/cohort reporting. |
| [FUT-REPORT-06](#fut-report-06) | Report comparability is already decided — no repeat answer | DECIDED; no repeat answer. |
| [FUT-LIVE-01](#fut-live-01) | How are Live teams formed and fixed before play? | Before Live activation; team-specific choices before team mode. |
| [FUT-LIVE-02](#fut-live-02) | Does a disconnected participant still count toward early round closure? | Before Live activation; team-specific choices before team mode. |
| [FUT-LIVE-03](#fut-live-03) | When may a late joiner start scoring? | Before Live activation; team-specific choices before team mode. |
| [FUT-LIVE-04](#fut-live-04) | How is elapsed time combined to break Live score ties? | Before Live activation; team-specific choices before team mode. |
| [FUT-LIVE-05](#fut-live-05) | How long can clients replay Live room events? | Before Live activation; team-specific choices before team mode. |
| [FUT-LIVE-06](#fut-live-06) | Which Live scenarios must pass before the first activation? | Before Live activation; team-specific choices before team mode. |
| [FUT-LEARN-01](#fut-learn-01) | Preparing the Learning configuration is already assigned — no repeat answer | ENGINEERING PREPARATION ALREADY ASSIGNED; no repeat answer. |
| [FUT-ADAPT-01](#fut-adapt-01) | What if Adaptive has insufficient questions before attempt creation? | Before Adaptive activation. |
| [FUT-ADAPT-02](#fut-adapt-02) | What if Adaptive runs out before five answered items? | Before Adaptive activation. |
| [FUT-ADAPT-03](#fut-adapt-03) | What if Adaptive runs out after five items but before its normal stop? | Before Adaptive activation. |
| [FUT-ADAPT-04](#fut-adapt-04) | Does a provably insufficient Adaptive bank block publication? | Before Adaptive activation. |
| [FUT-ADAPT-05](#fut-adapt-05) | Can an active Adaptive attempt use a newly edited bank? | Before Adaptive activation. |
| [FUT-BRANCH-01](#fut-branch-01) | Can a Branching attempt finish with required data unanswered? | Before Branching activation. |
| [FUT-BRANCH-02](#fut-branch-02) | What confirmation is required at a Branching terminal node? | Before Branching activation. |
| [FUT-SECTION-01](#fut-section-01) | Can a participant voluntarily submit an incomplete required section? | Before sectioned quizzes. |
| [FUT-SECTION-02](#fut-section-02) | What happens when only the current section timer expires? | Before sectioned quizzes. |
| [FUT-SECTION-03](#fut-section-03) | Section submission already requires explicit confirmation — no repeat answer | DECIDED; contract details remain; no repeat answer. |
| [FUT-SECTION-04](#fut-section-04) | Which stable error should a submitted-section mutation return? | Before sectioned quizzes. |

## Decision questions

<a id="l-01"></a>
### L-01 — Approved execution baseline; broader scope changes are optional

**Status:** The execution baseline is already approved in [#612 A02/R01](https://github.com/Gegcuk/QuizMaker/issues/612): personal free practice initially, with the three approved practice-mode mappings. The alternatives below are optional proposals to expand release scope, not evidence that this baseline was unanswered. The wider billing/authoring release inventory still needs engineering reconciliation; do not infer its exact scope solely from A02.

**Problem:** The backlog mixes repairs to today’s product with a large future programme. There is no single current release boundary.

**In plain language:** A complete development backlog can contain future features. A release needs an exact list of features people can actually use and must block access to unfinished ones.

**Real-life example:** A learner buying tokens and taking a practice quiz needs accurate billing and results. A teacher running a live classroom needs additional identity, timing, privacy and recovery guarantees.

**Options:**

- **A. Core product first (recommended)** — Accounts, existing paid token/subscription flows, quiz authoring/import, document-based AI generation, and authenticated practice/review. Preserve the already approved ALL_AT_ONCE, ONE_BY_ONE and TIMED mappings; their remaining decisions must be resolved. Keep assignments/guest assessment, Learning, Live, offline, adaptive/branching and other future capabilities unavailable until their own gates pass.
- **B. Core plus classroom assessment** — Everything in A plus assignments, guest participants, accommodations, teacher reports and result-release controls. More useful for schools; every related conditional question becomes a launch dependency.
- **C. Entire planned execution programme** — Everything in B plus Learning, Live, offline, adaptive/branching and all approved extended presets. Largest scope and longest route to launch; all conditional decisions and feature gates must be completed.

**Recommendation:** A gives a defined first release without discarding future work. It does not reopen the approved target-engine cutover or excuse existing reachable Critical/High defects.

**Decision details:** Name any capability you need added or excluded. An exclusion must be reflected in server-side access and client documentation, not only in the UI.

**Issues/evidence:** [#472](https://github.com/Gegcuk/QuizMaker/issues/472), [#612](https://github.com/Gegcuk/QuizMaker/issues/612) R01, [#688](https://github.com/Gegcuk/QuizMaker/issues/688); entire audit

**Your answer:** Not required to preserve the approved execution scope. State a change only if you want to expand it.

<a id="sec-01"></a>
### SEC-01 — How may an author reuse another author’s questions?

**When needed:** Required for question ownership and reuse issue scopes.

**Problem:** A quiz owner can currently link another person’s private question, and an unlinked question can lose effective ownership.

**In plain language:** Questions need an owner even when they are not in a quiz. Legitimate reuse also needs a rule so one author cannot silently change another author’s assessment.

**Real-life example:** Alice publishes a geography question. Bob wants to use it, but Alice later changes the correct answer. Bob’s old results must not change.

**Options:**

- **A. Own questions only initially (recommended)** — Fix attachment/orphan ownership now; another author’s question cannot be attached. Keep public copying as later [#91](https://github.com/Gegcuk/QuizMaker/issues/91) work. Smallest safe release scope.
- **B. Independent public copies at launch** — View-authorized public content may be copied into a separately owned question with retained provenance and authorized media references. The original cannot change the copy. Private content still needs explicit source authorization.
- **C. Explicit sharing of fixed revisions** — Authors grant reuse of an immutable published revision. Supports a shared question library, but needs sharing, revocation, revision and media-retention rules before launch.

**Recommendation:** A closes the demonstrated access problem with the least new product behavior. B is a sound later vertical feature; C is a larger collaboration capability. None permits private-source access or public editing of ownerless rows.

**Issues/evidence:** [#91](https://github.com/Gegcuk/QuizMaker/issues/91); September SEC-001 and SEC-002

**Your answer:** `SEC-01: ___`

<a id="sec-02"></a>
### SEC-02 — What proof is needed when connecting a new social login to an existing account?

**When needed:** Required for the OAuth trust fix.

**Problem:** A returned email string currently links a new provider identity even when the provider has not proved that email belongs to the person.

**In plain language:** Two systems reporting the same email address is not always proof that they represent the same person.

**Real-life example:** A provider reports Alice’s email for a newly presented identity. The product must not open Alice’s existing quizzes until ownership is proved.

**Options:**

- **A. Confirm through the existing account (recommended)** — Require a current local sign-in or verified recovery confirmation before adding a new provider identity. One extra confirmation, one consistent trust rule.
- **B. Allow narrowly trusted automatic links** — Automatically link only provider/domain combinations with explicitly verified authoritative email proof; require A for every other case. Smoother login but a larger provider-specific security/test matrix.

**Recommendation:** A is easiest to explain and verify. Existing proven provider-subject bindings remain valid; a different returned email does not silently reassign them.

**Decision details:** If B: name each provider to support. Unsupported or ambiguous authority must fall back to confirmation; no secret configuration is requested.

**Issues/evidence:** September SEC-004; completed [#454](https://github.com/Gegcuk/QuizMaker/issues/454) is transport context only

**Your answer:** `SEC-02: ___`

<a id="sec-03"></a>
### SEC-03 — Should standalone AI chat and document-structure utilities be public features?

**When needed:** Required to close the paid-provider bypass.

**Problem:** These routes can call a paid AI provider outside the quiz-generation balance/admission contract; chat also logs full user text.

**In plain language:** A logged-in person can create platform cost and occupy AI capacity even when they could not start a normal paid generation job.

**Real-life example:** An account with no generation tokens repeatedly calls chat while another customer’s study-guide generation waits.

**Options:**

- **A. Unavailable to ordinary users for now (recommended)** — Deny these standalone provider calls until a separate product contract is approved. Normal approved quiz-generation/document paths stay in scope. Remove raw-content logs independently.
- **B. A specified free allowance** — Enable only explicitly listed utilities for eligible accounts, with a fixed allowance and platform cost ceiling. Shared provider admission and redacted logs still apply.
- **C. Separately priced features** — Specify a visible price/quote, eligible users, reservation, settlement and failure/refund behavior before enabling them. More implementation and product work.

**Recommendation:** A removes the bypass without inventing new prices or hidden free usage. B/C can remain planned features until their complete values are supplied.

**Decision details:** If B, answer now with: routes; eligible users; requests per user per day; global daily provider-spend ceiling; outage behavior (recommended: reject new requests with no charge). If C, supply the same route/eligibility details plus quote unit/price and whether failed or canceled operations cost the customer. Leaving these blank means the feature stays blocked, not an inferred allowance.

**Issues/evidence:** [#269](https://github.com/Gegcuk/QuizMaker/issues/269); September SEC-005; [#465](https://github.com/Gegcuk/QuizMaker/issues/465) privacy sub-scope

**Your answer:** `SEC-03: ___`

<a id="sec-04"></a>
### SEC-04 — Should an email-verification link work while the user is signed out?

**When needed:** Required for an intentional onboarding contract.

**Problem:** Verification and resend currently require login although the email contains a verification token.

**In plain language:** A link can prove access to one mailbox without giving access to the account’s quizzes or automatically signing the person in.

**Real-life example:** A student registers on a laptop, opens the email on a phone, and currently reaches an authentication failure.

**Options:**

- **A. A valid one-use link works signed out (recommended)** — Verify only the account bound to the token. Resend gives a generic response under rate limits; expired/used tokens cannot verify another account. No automatic login.
- **B. Sign in before verification** — Preserve the login-first boundary and make the client clearly guide the person through sign-in, then token verification. More friction, but an explicit rule.

**Recommendation:** A provides the expected cross-device flow with narrowly scoped proof. Passwordless login remains a separate feature.

**Issues/evidence:** Audit §14.3; [#457](https://github.com/Gegcuk/QuizMaker/issues/457) abuse controls

**Your answer:** `SEC-04: ___`

<a id="auth-01"></a>
### AUTH-01 — How much shared-network traffic should new login, signup and refresh limits allow?

**When needed:** Required to make [#457](https://github.com/Gegcuk/QuizMaker/issues/457) numerically implementable; existing rate limits remain unchanged.

**Problem:** Login has no shared limiter, and the planned new login/signup/refresh policies lack exact values. Network-only protection can block a school; account-and-network-pair-only protection can be evaded by changing networks.

**In plain language:** Each request needs both personal protection and a limit on the total traffic coming from one connection. Successful requests must not reset a shared safety budget.

**Real-life example:** Twenty-five students sign in through one school router while an attacker guesses one student’s password from several networks.

**Options:**

- **A. Higher shared-network allowance (recommended)** — Approve profile A in the numeric appendix: tight independent account/session limits with larger network limits for new login, signup and refresh. Supports the example burst if measured infrastructure capacity is sufficient.
- **B. Tighter consumer-network allowance** — Approve profile B: the same account/session protection but lower network totals. Less traffic per connection, more legitimate classroom throttling.

**Recommendation:** A matches the education use case. Both are proposed starting policies, not a security standard or complete bot protection. Existing verification remains 10 per IP/minute, so A does not promise 25 simultaneous verification links will all succeed.

**Decision details:** Approve the complete appendix matrix with A/B, or state changes explicitly. MySQL, atomic shared counters, generic 429/Retry-After and fail-closed 503 are already decided. A separate capacity test must prove the chosen admitted workload is safe; no hidden automatic tuning is authorized.

**Issues/evidence:** [#457](https://github.com/Gegcuk/QuizMaker/issues/457); current AuthController/RateLimitService; proposed matrix in the appendix below

**Your answer:** `AUTH-01: ___`

<a id="exec-01"></a>
### EXEC-01 — What happens when TIMED practice has no valid duration?

**When needed:** First public authenticated practice release.

**Problem:** The three practice mode mappings are approved, but missing TIMED duration and ONE_BY_ONE navigation remain undefined.

**In plain language:** A request must produce the saved mode the learner understands. Accepted answers remain immutable under every choice; the formal [#668](https://github.com/Gegcuk/QuizMaker/issues/668) fixture stays strict.

**Real-life example:** A participant chooses TIMED on a quiz whose author never supplied a duration. Should start fail, or may the participant explicitly accept an untimed attempt?

**Options:**

- **A. Reject invalid timed start (recommended)** — Reject start with 422. Author/client fixes missing duration and no attempt/allowance is created.
- **B. Offer explicit untimed start** — Start untimed with explicit participant acknowledgement/response mode. More forgiving but the requested timer disappears.

**Recommendation:** Option A. Reject start with 422.

**Decision details:** Do not reopen the approved ALL_AT_ONCE/ONE_BY_ONE/TIMED first-release scope.

**Issues/evidence:** [#612](https://github.com/Gegcuk/QuizMaker/issues/612) R01; [#516](https://github.com/Gegcuk/QuizMaker/issues/516)/#523/#524/#600/#601/#609

**Your answer:** `EXEC-01: ___`

<a id="exec-02"></a>
### EXEC-02 — May learners skip or revisit questions in ONE_BY_ONE practice?

**When needed:** First public authenticated practice release.

**Problem:** The three practice mode mappings are approved, but missing TIMED duration and ONE_BY_ONE navigation remain undefined.

**In plain language:** A request must produce the saved mode the learner understands. Accepted answers remain immutable under every choice; the formal [#668](https://github.com/Gegcuk/QuizMaker/issues/668) fixture stays strict.

**Real-life example:** A learner wants to skip question 2, answer question 3, then return to question 2. An already accepted answer must remain locked even when revisited.

**Options:**

- **A. Allow skip and read-only revisit (recommended)** — Allow skip and read-only return to accepted items. Accepted items stay locked and final unanswered confirmation applies.
- **B. Answer required, revisit read-only** — Require answer to advance, allow read-only previous accepted items. Simple, but participants cannot leave a difficult item unanswered.
- **C. Answer required, no revisit** — Require answer/no revisit. Strictest sequential flow.

**Recommendation:** Option A. Allow skip and read-only return to accepted items.

**Decision details:** Do not reopen the approved ALL_AT_ONCE/ONE_BY_ONE/TIMED first-release scope.

**Issues/evidence:** [#612](https://github.com/Gegcuk/QuizMaker/issues/612) R01; [#516](https://github.com/Gegcuk/QuizMaker/issues/516)/#523/#524/#600/#601/#609

**Your answer:** `EXEC-02: ___`

<a id="exec-03"></a>
### EXEC-03 — Must first public practice keep all nine current question families?

**When needed:** First public release compatibility and cutover.

**Problem:** The implementation issue binds five question families, while current practice supports nine.

**In plain language:** The new engine currently plans five types: single choice, multiple choice, true/false, fill-in-the-blank and ordering. Today’s product also has open response, compliance, map/image hotspot and matching types. A release must support an existing quiz honestly or clearly say it is unavailable; it cannot silently drop its questions.

**Real-life example:** A previously usable map quiz includes HOTSPOT questions. After target-only cutover, the quiz must still work or clearly explain why it is temporarily unsupported.

**Options:**

- **A. All nine current families (recommended)** — Support all nine before broad current-quiz activation. With a separate bounded compatibility/scoring-adapter issue for the four families; more work, existing author content remains usable.
- **B. Five families with clear exclusions** — Ship only the five approved families with explicit eligibility rejection, clear author/participant message and deferred follow-up. Earlier narrow release, some existing quizzes unavailable.
- **C. Keep staged legacy compatibility** — Separate staged activation retaining a documented compatible legacy route for these families. Preserves availability but reopens the target-only/reset decision in EXEC-04.

**Recommendation:** Option A. Support all nine before broad current-quiz activation.

**Decision details:** If narrowing, specify exact eligibility error/client display and retained author data; if supporting nine, add a separate bounded four-family adapter issue.

**Issues/evidence:** [#701](https://github.com/Gegcuk/QuizMaker/issues/701); [#612](https://github.com/Gegcuk/QuizMaker/issues/612) R01; [#688](https://github.com/Gegcuk/QuizMaker/issues/688); SCORE-001/QUESTION-001

**Your answer:** `EXEC-03: ___`

<a id="exec-04"></a>
### EXEC-04 — Can paused starts fall back to legacy after the reset?

**When needed:** First public cutover.

**Problem:** One approved note permits safe legacy fallback; another authorizes reset and target-only writing.

**In plain language:** After replacing the old quiz engine, a temporary problem in the new engine can either pause new tests or send eligible new tests to a retained old engine. Tests already started must always stay with their original engine and saved evidence.

**Real-life example:** Ten minutes after reset and target activation, the new start service develops an incident. A person who has not started either receives the approved temporary-unavailable response or is explicitly routed to a separate compatible legacy attempt.

**Options:**

- **A. Fallback only before reset (recommended)** — Fallback only before reset. After reset every paused start returns the approved 503, existing target attempts receive supported forward fixes.
- **B. Retain equivalent legacy fallback** — Retain a safe equivalent legacy writer for post-activation new starts. Better availability only where equivalence is proved, but explicitly reopen reset/removal/retirement design and test two authorities.

**Recommendation:** Option A. Fallback only before reset.

**Decision details:** Approved stop thresholds, 503 Retry-After 900, 100% routing and retirement 14 days/two releases are not reopened. Any reset still requires fresh execution confirmation.

**Issues/evidence:** [#688](https://github.com/Gegcuk/QuizMaker/issues/688) D10/D29/R09; [#703](https://github.com/Gegcuk/QuizMaker/issues/703)

**Your answer:** `EXEC-04: ___`

<a id="exec-05"></a>
### EXEC-05 — What should “time spent on this question” honestly mean?

**When needed:** Before exposing time-spent-per-question statistics.

**Problem:** The current value is elapsed since attempt start, which does not measure time spent on an individual question.

**In plain language:** Meaningful per-item duration needs a definition and suitable evidence; cannot be reconstructed from cumulative acceptance times during free navigation.

**Real-life example:** Question 1 is accepted 30 seconds after start and question 2 at 60 seconds. The second value is cumulative elapsed time, not proof of 60 seconds spent on question 2.

**Options:**

- **A. Label only measured elapsed facts (recommended)** — Initially expose only accurately labelled elapsed-since-start/acceptedAt and omit per-question duration where unmeasured. No extra telemetry, honest data.
- **B. Measure item exposure intervals** — Measure server-observed item exposure intervals/navigation and disclose an explicitly named engaged/exposed duration. Useful for one-by-one but client/offline/disconnect limitations and pause/multi-tab semantics must be defined.
- **C. Label an interval estimate** — Estimate interval since previous acceptance and label it an estimate. Easy, misleading when learners navigate freely. Never present estimate C as measured active study time. This is distinct from enforcing the already approved deadline.

**Recommendation:** Option A. Initially expose only accurately labelled elapsed-since-start/acceptedAt and omit per-question duration where unmeasured.

**Decision details:** If measuring exposure, define multi-tab, pause, disconnect, free navigation, offline and missing-evidence behavior. This is separate from authoritative timer enforcement.

**Issues/evidence:** September per-question-time observation; [#411](https://github.com/Gegcuk/QuizMaker/issues/411)/#538/#631

**Your answer:** `EXEC-05: ___`

<a id="bill-01"></a>
### BILL-01 — When do subscription plan changes affect the token allowance?

**When needed:** Required if plan changes/proration invoices are supported at release.

**Problem:** A delayed invoice currently takes facts from the subscription as it exists today, rather than from what was actually paid. Mid-period changes also lack an explicit entitlement rule.

**In plain language:** Regular renewal credits can be fixed using the invoice’s own period and purchased plan. An upgrade halfway through a month needs a separate rule about additional tokens and additional money.

**Real-life example:** A September renewal arrives late, after the customer upgrades in October. September must receive September’s allowance, not October’s upgraded allowance.

**Options:**

- **A. Plan changes take effect at the next renewal (recommended)** — One allowance per paid regular renewal. Schedule supported plan changes for the next billing period so cash and benefits change together; no second full allowance from a proration invoice. Unexpected mid-period invoices go to explicit reconciliation, not guessed credit.
- **B. Immediate, prorated changes** — Upgrade benefits start immediately and the additional token grant follows an approved proportional rule. Downgrades, rounding, refunds and already-spent tokens must be specified together.
- **C. Do not offer plan changes yet** — Keep the selected plan until cancellation/renewal; disable plan-change controls in the app and provider portal where applicable. Still process ordinary renewals and unexpected provider events truthfully.

**Recommendation:** A is the simplest complete plan-change product. It must not charge an immediate upgrade while withholding its promised benefit. Immutable renewal settlement and refund bug fixes proceed independently.

**Decision details:** If B: specify the token difference formula, rounding, effective timestamp, downgrade timing, and treatment of spent tokens. Recommended starting proposal for review: upgrades grant floor(max(new allowance − old allowance, 0) × remaining-period fraction), keyed once to paid adjustment; downgrades apply next renewal. Selecting B without these values leaves only the proration feature blocked.

**Issues/evidence:** September STRIPE-003; [#471](https://github.com/Gegcuk/QuizMaker/issues/471) remains a separate subscription-owner constraint

**Your answer:** `BILL-01: ___`

<a id="bill-02"></a>
### BILL-02 — Are spend-dependent refund restrictions needed now?

**When needed:** Answer to narrow the supported financial policy surface.

**Problem:** Optional refund rules attribute all later spending to a recent purchase and may try to block a refund after the payment provider already returned the money.

**In plain language:** A cash refund that has happened must be recorded honestly. Deciding whether a customer is eligible for it must happen beforehand under a clear rule.

**Real-life example:** A customer has 1,000 old tokens, buys 100 more, then spends 50. Which purchase supplied those 50 determines whether the new purchase is “unused.”

**Options:**

- **A. Keep the current default; defer alternatives (recommended)** — Preserve full token debit with a possible negative balance and fix duplicate/reversal correctness. Mark spend-dependent CAP/BLOCK settings unsupported until defined.
- **B. Implement spend-dependent eligibility** — Use a separate pre-refund workflow with explicit purchase attribution and limits; always reconcile money already returned externally. Larger finance scope.

**Recommendation:** A preserves the existing default and avoids inventing purchase-lot accounting inside a bug fix.

**Decision details:** If B: specify oldest-credit-first or newest-credit-first; whether partially used purchases are partially refundable; treatment of promotional credits; and who may approve a refund. These are policy inputs, not permission to send a refund.

**Issues/evidence:** Audit §14.4; STRIPE-001/002 fixes preserve the current default

**Your answer:** `BILL-02: ___`

<a id="bill-03"></a>
### BILL-03 — Should the three currently ignored billing deployment settings be fixed or configurable?

**When needed:** Required for [#503](https://github.com/Gegcuk/QuizMaker/issues/503).

**Problem:** The workflow displays settings that the running service does not receive. Its dormant reservation default is 30 minutes while the application default is 120 minutes.

**In plain language:** An operator can believe a setting changed when nothing changed. Connecting the dormant value blindly would unexpectedly shorten how long tokens remain reserved.

**Real-life example:** A worker crashes and leaves an unresolved reservation. The deployment appears to say “30 minutes,” but the service uses its 120-minute expiry default; normal failed-job handling should release without waiting for this expiry.

**Options:**

- **A. Keep effective defaults fixed (recommended)** — Use 120 minutes, factor 1.2 and currency usd; remove misleading unused overrides. Preflight and service validate the same effective configuration.
- **B. Make all three supported controls** — Wire and validate them consistently, initially still 120 minutes, 1.2 and usd. Invalid values stop deployment before replacing the healthy service; later policy changes need approval.

**Recommendation:** A is the smallest truthful configuration repair. B is useful when the operator needs tuning. Neither changes current prices, past reservations, or the customer generation tariff.

**Decision details:** Before implementation, the operator confirms effective deployed values without exposing secrets. The factor affects operational estimation; currency metadata does not independently rewrite the Stripe catalog.

**Issues/evidence:** [#503](https://github.com/Gegcuk/QuizMaker/issues/503); actual Spring defaults vs dormant workflow values

**Your answer:** `BILL-03: ___`

<a id="ops-01"></a>
### OPS-01 — What simultaneous AI workload must the first release prove it can handle?

**When needed:** Required for a capacity claim.

**Problem:** No production-safe concurrency number has been demonstrated. One healthy large job and a 25-job synthetic trial already lost work.

**In plain language:** Five short requests are not equivalent to five large study guides. Capacity needs a repeatable document size, question mix and provider response time.

**Real-life example:** Five teachers each request 20 chunks × 3 question types × 2 questions: 120 questions and 60 planned calls per teacher. At ten seconds per call this is a substantial shared workload.

**Options:**

- **A. Certify five simultaneous starts (recommended)** — Five distinct owners, the example workload, fake provider 10-second normal/30-second degraded responses, and a 60-minute normal soak. Also test large-job stress, saturation, cancellation and restart. Smaller initial service envelope.
- **B. Certify twenty-five simultaneous starts** — The same workload per owner and the same checks with 25 owners. May require greater capacity/provider quotas or longer admission waits before work starts; more evidence and infrastructure effort.

**Recommendation:** A is a realistic initial certification target, not a statement that today’s code passes. In either option, healthy representative jobs must finish within the approved five-minute work deadline; degraded jobs must finish or terminate/reconcile correctly. No admitted work may disappear. The deadline is five minutes from durable work start, excluding prior FIFO admission wait; restart/reclaim does not restart it.

**Decision details:** The approved total call budget is twice the planned initial calls (selected chunks × requested question types). Simultaneous request starts do not mean all jobs execute simultaneously: the approved active-job window still controls that. Measure maximum/p95 queue wait and throughput; this is not an invented queue-wait guarantee. OPS-05 sets the separate maximum waiting policy. Keep >80% coverage and the upload ceiling. Resource settings follow measured evidence, not guessed thread counts.

**Issues/evidence:** [#442](https://github.com/Gegcuk/QuizMaker/issues/442)/#443; GATE-01

**Your answer:** `OPS-01: ___`

<a id="ops-02"></a>
### OPS-02 — How quickly must service and data be recoverable after a failure?

**When needed:** Required before real customer data is accepted.

**Problem:** The backlog requires a migration backup but does not define an ongoing recovery promise for database records and uploaded files.

**In plain language:** A backup must actually restore a consistent service. “Data loss” is how far back recoverable data may be; “recovery time” is how long customers may wait.

**Real-life example:** The server fails at 16:00. A one-hour data-loss target means recovery through at least 15:00, followed by reconciliation of later known payments.

**Options:**

- **A. One-hour data loss, four-hour restoration target (recommended)** — Encrypted recoverable backups retained 30 days; monthly isolated restore and another before risky schema work. Include database/files and payment reconciliation.
- **B. Fifteen-minute data loss, one-hour restoration target** — Same retention and exercises, with tighter capture/recovery automation and operator coverage. Better continuity, higher engineering and operating cost.
- **C. Internal disposable-data trials only** — Up to 24-hour data loss/restoration, seven-day backups and a restore before each trial release. Lower burden; does not satisfy the requested real-customer release goal.

**Recommendation:** A is a concrete initial target to implement and measure. No option permits silently recreating balances or losing known payment obligations.

**Decision details:** Name the recovery operator or role. These proposed targets are not current capabilities, legal retention rules, or approval to move private backups to a new provider.

**Issues/evidence:** [#430](https://github.com/Gegcuk/QuizMaker/issues/430) plus proposed GATE-05 ongoing recovery issue

**Your answer:** `OPS-02: ___`

<a id="ops-03"></a>
### OPS-03 — Monitoring remains owner-deferred — no repeat answer

**Status:** ACCEPTED DEFERRAL. No repeat owner answer requested.

**Recorded decision:** On 18 August the owner selected DigitalOcean plus Grafana Cloud, accepted 14-day retention and the private notification destination, and explicitly deferred implementation/registration to the very deep backlog. The original questionnaire incorrectly presented reactivation as an unanswered baseline choice.

**Required follow-through:** Carry that scheduling decision forward. If a later release risk assessment recommends changing it, present the changed assumptions and a distinct proposal to reopen the decision. Do not silently classify the accepted deferral as a new unanswered question or infer that the owner now allows only internal trials.

**Authoritative source:** [#465](https://github.com/Gegcuk/QuizMaker/issues/465) and the owner ledger in [#466](https://github.com/Gegcuk/QuizMaker/issues/466)

<a id="ops-04"></a>
### OPS-04 — Parser OS isolation is explicitly deferred — no repeat answer

**Status:** ACCEPTED DEFERRAL. No repeat owner answer requested.

**Recorded decision:** The owner’s later 12 August comment defers OS-grade parser sandboxing and explicitly says it is not a local or production launch blocker under the current trust/traffic assumptions. Existing resource/process controls remain; the security residual is acknowledged.

**Required follow-through:** Preserve that decision and its revisit triggers: broadly accepting hostile uploads, a relevant parser vulnerability, or materially increased volume. The original A/B options wrongly replaced this with “implement before public use or internal use only.” The separate newly reproduced chunk-memory/content defects still require fixes.

**Authoritative source:** [Owner decision on #733](https://github.com/Gegcuk/QuizMaker/issues/733#issuecomment-5269845084)

<a id="ops-05"></a>
### OPS-05 — How long may an accepted generation request wait before AI work begins?

**When needed:** Required for durable queue and reservation behavior.

**Problem:** FIFO order and the five-minute work deadline are approved, but a healthy accepted job has no approved maximum wait before work starts.

**In plain language:** Waiting for a place in the queue and generating the quiz are different phases. A queued job should not hold the customer’s tokens forever or be mistaken for a lost job.

**Real-life example:** A request is accepted at 10:00. Under A it expires at 10:05 if still waiting. If work begins at 10:04, its separate five-minute work deadline is 10:09.

**Options:**

- **A. Wait up to five minutes (recommended)** — If not claimed by then, atomically finish with a clear queue-timeout reason, release its reservation, charge nothing and prevent later dispatch. A claimed job keeps the separately approved work deadline.
- **B. Wait up to fifteen minutes** — Same guarantees with a longer queue allowance. More bursts can wait for capacity, but users wait longer and their tokens remain reserved longer.

**Recommendation:** A gives customers a bounded initial wait. This changes neither FIFO order nor the five-minute work deadline; restart/reclaim must preserve the original timestamps and shared attempt budget.

**Decision details:** The worker-start versus expiry race has one transactional winner. The client shows waiting separately from generating. A new retry uses the documented idempotency/new-operation rule, not a second hidden dispatch for the expired job.

**Issues/evidence:** [#442](https://github.com/Gegcuk/QuizMaker/issues/442)/#443; the current two-minute stale-PENDING rule is not an approved healthy queue timeout

**Your answer:** `OPS-05: ___`

<a id="ai-01"></a>
### AI-01 — How much real-model quality evaluation should be planned?

**When needed:** Required for AI quality sign-off; this answer only records a future evaluation budget.

**Problem:** Fake-provider tests prove application behavior but cannot show whether the actual model writes factually supported questions.

**In plain language:** A model giving itself a high confidence score is not independent evidence. A person needs to compare accepted questions and correct answers with the source.

**Real-life example:** A document contains an instruction to invent a fact. The generated question may look valid while its marked answer is unsupported.

**Options:**

- **A. Bounded first evaluation (recommended)** — Maximum 200 total dispatches and US$25, whichever comes first. Plan 180 sanitized cases across released question types, three difficulties, six source-risk classes and multilingual checks; remaining dispatches cover recorded retries/rechecks.
- **B. Repeated evaluation** — Maximum 1,000 dispatches and US$100, whichever comes first. Repeat the 180-case corpus five times and reserve up to 100 dispatches for recorded retries/rechecks. Better evidence of variable output, much more review effort.
- **C. Defer paid evaluation** — Continue all deterministic/offline work, but leave real-model quality sign-off blocked. Do not weaken the already approved grounding/language policy.

**Recommendation:** A is a bounded initial evidence plan. A known accepted factual/answer error, wrong requested language, instruction disclosure or cross-source leak blocks the candidate until repaired and retested; sampling cannot prove perfect future accuracy.

**Decision details:** Name the human reviewer and languages you intend to advertise. Before any paid call, review the exact sanitized corpus, model/settings, current official pricing estimate and hard spending-control method. Stop at either cap and mark incomplete if necessary. Automated CI stays free of real paid calls. Design cases as single-call evaluations: for nine released types, 9 × 3 difficulties × 6 source classes = 162 cases plus 18 multilingual cases. Adjust and document distribution for the actual released families; a multi-call application scenario counts every dispatch against the same cap. The human reviewer checks every accepted question, marked correct answer and explanation against its source—not just a sample of accepted output.

**Issues/evidence:** [#444](https://github.com/Gegcuk/QuizMaker/issues/444)/#455; GATE-02

**Your answer:** `AI-01: ___`

<a id="ai-02"></a>
### AI-02 — Is a second AI provider part of the supported release?

**When needed:** Conditional: answer B only if alternate-provider support is required. Otherwise A records current scope.

**Problem:** An old issue asks for non-OpenAI schema support, but the current configured implementation is OpenAI-specific.

**In plain language:** Changing a provider name does not prove that its answer format, errors, billing and quality work correctly.

**Real-life example:** An operator changes to another vendor and discovers it does not enforce the structured question format expected by the backend.

**Options:**

- **A. One configured OpenAI contract initially (recommended)** — Validate its required capabilities before dispatch; keep alternate providers unsupported and narrow/defer [#172](https://github.com/Gegcuk/QuizMaker/issues/172) based on a real reproduction.
- **B. OpenAI plus one named second provider** — Add an explicit adapter/capability/privacy/error/usage/quality matrix and evaluation scope. More flexibility and more work.

**Recommendation:** A avoids speculative compatibility work. All approved quality, deadline, budget and billing rules still apply.

**Decision details:** If B: provide provider/model and why it is required. This is not permission to add credentials or invoke it.

**Issues/evidence:** [#172](https://github.com/Gegcuk/QuizMaker/issues/172)

**Your answer:** `AI-02: ___`

<a id="ai-03"></a>
### AI-03 — How should future AI ingestion handle personal information in source material?

**When needed:** Conditional: before flashcard/transcript masking features or a new personal-data ingestion claim is enabled.

**Problem:** Two old issues request personal-information masking without defining which data, whether masking is mandatory, or what to do when it changes the educational content.

**In plain language:** Removing a name or email may protect privacy, but it can also remove the fact a question is meant to test. Redacting logs is a separate requirement and remains mandatory.

**Real-life example:** A classroom transcript contains student names and medical details, while a history text legitimately names historical figures.

**Options:**

- **A. Restricted intake with an explicit data-use policy (recommended)** — Do not claim automatic anonymization. Initially accept only material the uploader is authorized to send to the configured provider, prohibit the specified sensitive categories, disclose processing, and keep the proposed transcript/flashcard masking feature gated pending a reviewed detection policy.
- **B. Mandatory defined masking for those features** — Use an approved category/rule matrix, stable placeholders and a quality check; reject cases where sensitive data cannot safely be separated or masking destroys the requested learning objective. Higher implementation/evaluation burden; detection is not infallible.

**Recommendation:** A is the honest narrow scope until the privacy and educational tradeoffs are defined. It cannot be used to claim legal compliance or guaranteed detection; the owner must specify permitted data and provider arrangements.

**Decision details:** State permitted/prohibited source categories, intended jurisdictions/audience and the person responsible for privacy review. If B, identify categories to mask (for example contact details, student identities, health details), permitted exceptions such as public historical figures, and whether original-to-placeholder mappings may be retained. Never paste real private examples.

**Issues/evidence:** [#274](https://github.com/Gegcuk/QuizMaker/issues/274)/#318; [#455](https://github.com/Gegcuk/QuizMaker/issues/455) privacy boundary

**Your answer:** `AI-03: ___`

<a id="data-01"></a>
### DATA-01 — Who may stop deletion for a dispute, and later release that hold?

**When needed:** Required before held real-user data or retention cleanup is operated.

**Problem:** The retention policy says holds override deletion, but does not fully assign the authority to create or release them.

**In plain language:** A hold is a recorded instruction to preserve specific evidence. Someone must be accountable for keeping it justified and ending it safely.

**Real-life example:** A disputed exam result reaches its normal deletion date. An ordinary teacher must not be able to erase protected evidence by removing its hold.

**Options:**

- **A. Dedicated platform privacy/security role (recommended)** — Organizations request holds through support. Require scope, reason, owner, review and audited release; preserve the approved 90-day review and no automatic expiry.
- **B. Designated organization privacy officers** — They manage holds within their own organization under platform oversight. Faster local handling, but needs delegation and cross-organization negative tests.

**Recommendation:** A keeps initial authority narrow and auditable. A hold never grants access to unrelated private content.

**Decision details:** Name the accountable role/person and independent reviewer. Product approval of a workflow does not replace any required legal assessment.

**Issues/evidence:** [#664](https://github.com/Gegcuk/QuizMaker/issues/664) R03; [#699](https://github.com/Gegcuk/QuizMaker/issues/699); account-erasure follow-up

**Your answer:** `DATA-01: ___`

<a id="data-02"></a>
### DATA-02 — How may organizations change the approved retention periods?

**When needed:** Required before organization overrides or automated erasure is enabled.

**Problem:** Default retention periods are decided, but the allowed override process and boundaries remain open.

**In plain language:** Different records serve different purposes. A blanket “keep everything longer” or immediate bulk deletion can conflict with certificate proof, holds or a school’s obligations.

**Real-life example:** A school requests shorter storage of written answers but longer storage of its valid certificate records.

**Options:**

- **A. Reviewed shorter or longer policies (recommended)** — Keep the already approved defaults. An organization submits a per-record policy for privacy review; only the explicitly approved version is applied through scheduled cleanup. No arbitrary self-service numeric field at launch.
- **B. Self-service within an approved per-record range** — Organizations choose shorter/longer periods within fixed minima/maxima. More autonomy, but the complete range table, notice and destructive-change safeguards must be approved before enabling it.

**Recommendation:** A preserves the approved configurable direction while avoiding invented universal ranges. Unknown legacy rows and active holds remain protected.

**Decision details:** State launch jurisdictions and whether minors/schools are intended users, so the required privacy review has a real scope. If B, provide minimum/maximum for each data class in [#664](https://github.com/Gegcuk/QuizMaker/issues/664); blank classes stay on fixed approved defaults and cannot be changed through self-service.

**Issues/evidence:** [#664](https://github.com/Gegcuk/QuizMaker/issues/664) R03 (organization-configurable matrix already approved)

**Your answer:** `DATA-02: ___`

<a id="data-03"></a>
### DATA-03 — What remains after a person deletes their account?

**When needed:** Required for the account-erasure contract.

**Problem:** Account deletion is not yet a complete cross-feature workflow, and result/certificate survival is only partly specified.

**In plain language:** Deleting a profile, deleting private answers and retaining limited proof of an earned certificate are separate actions.

**Real-life example:** A learner deletes their account, then an employer tries to verify a still-valid certificate.

**Options:**

- **A. Keep minimal valid proof where justified (recommended)** — Remove ordinary profile/linkage and private payloads under the approved deletion schedule; retain only explicitly justified result/certificate validity fields for their already approved period. Separate held/billing facts remain restricted.
- **B. Revoke certificates with ordinary account erasure** — Erase non-held identifying proof and revoke public verification under the approved schedule. Stronger removal, but previously issued credentials stop verifying.

**Recommendation:** A can preserve the useful credential while minimizing the retained record. The exact fields, purpose and access must pass privacy review; a re-linkable record is not anonymous.

**Decision details:** Confirm the publicly visible verification fields (recommended minimum: opaque credential reference, validity/status and issuer; display name only if explicitly justified). Author-owned quizzes/documents and legally held records must have explicit lifecycle rules in the same workflow, never blind cascading deletion.

**Issues/evidence:** [#664](https://github.com/Gegcuk/QuizMaker/issues/664) R03; [#699](https://github.com/Gegcuk/QuizMaker/issues/699); proposed account-lifecycle child

**Your answer:** `DATA-03: ___`

<a id="data-04"></a>
### DATA-04 — What happens when an organization closes?

**When needed:** Conditional on organization support; required before closure is offered.

**Problem:** The older closure issue does not define how authorized exports and retention interact.

**In plain language:** Closing access should not accidentally delete evidence before the organization can retrieve it, or leave former members with permanent access.

**Real-life example:** A school stops using the product in July and needs its approved results before normal retention cleanup.

**Options:**

- **A. Controlled 30-day export period (recommended)** — Stop new collection and ordinary member access; one verified authorized representative has export-only access for 30 days, then that access ends and the approved retention/holds continue. Requires a bounded export workflow.
- **B. Platform-operated closure after verified export** — Support confirms authorized export is complete before closing access. No self-service grace product; the same retention/hold matrix applies. More operator work.

**Recommendation:** A makes the user experience predictable without treating closure as permission for immediate deletion. The proposed 30 days needs your approval and changes [#79](https://github.com/Gegcuk/QuizMaker/issues/79)’s scope.

**Decision details:** Name who may request closure and receive the export (recommended: organization owner plus verified privacy/export permission). Exports never include another organization’s data or raw content outside the approved scope.

**Issues/evidence:** [#664](https://github.com/Gegcuk/QuizMaker/issues/664) R03; [#79](https://github.com/Gegcuk/QuizMaker/issues/79) currently excludes export/delay

**Your answer:** `DATA-04: ___`

<a id="data-05"></a>
### DATA-05 — May anonymous aggregate history remain after detailed records expire?

**When needed:** Required for cleanup/reporting interaction.

**Problem:** Deletion rules do not yet settle whether long-term charts lose expired contributions or keep non-identifying totals.

**In plain language:** An individual attempt record is different from a genuinely anonymous course total. Removing names alone may still identify somebody in a small group.

**Real-life example:** After detailed records expire, a teacher wants to see that last year’s course averaged 72%, without finding any named learner’s answers.

**Options:**

- **A. Keep only proven non-identifying aggregates (recommended)** — Delete individual evidence and retain bounded aggregates that pass the existing distinct-person and complementary-suppression rules. No person/attempt trail can be reconstructed.
- **B. Remove the historical contribution too** — Delete evidence and its derived contribution. Simpler minimization, but old charts change as retention runs.

**Recommendation:** A supports useful trends only where irreversible anonymization is demonstrated. Otherwise the record must remain classified as personal and follow its approved retention or be deleted.

**Issues/evidence:** [#664](https://github.com/Gegcuk/QuizMaker/issues/664) R03; [#679](https://github.com/Gegcuk/QuizMaker/issues/679) privacy rules

**Your answer:** `DATA-05: ___`

<a id="guest-01"></a>
### GUEST-01 — How should the approved guest-link allowance refill?

**When needed:** Conditional: before any guest exchange is enabled.

**Problem:** The numeric limits are approved but their burst/window behavior is not.

**In plain language:** A per-minute limit can mean “in any last 60 seconds” or “a bucket that gradually refills.” They allow different bursts at minute boundaries.

**Real-life example:** Thirty students behind one school connection open separate links at once. A retry should tell them the earliest safe time, not repeatedly fail without explanation.

**Options:**

- **A. Rolling 60-second window (recommended)** — At most the approved 30 network exchanges and 5 exchanges per link in any preceding 60 seconds. Retry-After is the time until both limits permit a request; no boundary double burst.
- **B. Smoothly refilling buckets** — Capacity 30 per network and 5 per link, refilling at 30/minute and 5/minute. A burst can be followed by gradually available requests; this differs from a strict rolling ceiling.

**Recommendation:** A has the clearest meaning for the already approved “per minute” limits. Keep both counters shared and atomic, raw secrets out of storage/logs, and known/unknown links indistinguishable.

**Issues/evidence:** [#676](https://github.com/Gegcuk/QuizMaker/issues/676) R06; [#801](https://github.com/Gegcuk/QuizMaker/issues/801) (30 per trusted IP/minute and 5 per capability/minute already approved)

**Your answer:** `GUEST-01: ___`

<a id="guest-02"></a>
### GUEST-02 — What should users and support experience when guest-link exchange protection is unavailable?

**When needed:** Conditional: before guest exchange is enabled.

**Problem:** Fail-closed new exchange is approved, but the exact client response and any support exemption remain unspecified.

**In plain language:** If the service cannot check safe admission, it cannot create a new guest session. Already exchanged valid guest sessions should continue under their normal rules.

**Real-life example:** A class opens links during a database limiter outage; repeatedly treating it as their fault would produce confusing “too many requests” messages.

**Options:**

- **A. Temporary-unavailable response, no bypass (recommended)** — 503 CAPABILITY_EXCHANGE_TEMPORARILY_UNAVAILABLE with a proposed 30-second Retry-After; no production support exemption. Trust only configured ingress/proxy hops, otherwise use the connection’s peer address. Record bounded outage counts; alert after one minute of sustained failure when monitoring is active.
- **B. Temporary scoped support exemption** — Same fail-closed 503 outage response. While shared persistence, replay and revocation checks are healthy, allow a dedicated audited, organization-scoped, expiring quota exception. It never permits exchange during a limiter-store outage; exact authority, maximum lifetime and usage cap must be approved.

**Recommendation:** A is simpler and preserves the approved fail-closed protection. The proxy inventory is a deployment fact engineers must verify, not an invitation to trust user-supplied forwarding headers.

**Decision details:** If B, specify authorized role, maximum exemption lifetime and request cap. An override cannot bypass source/participant ownership or revive a revoked/expired link. Any exception affects only a healthy limiter’s quota ceilings; it cannot bypass an outage or security/identity checks.

**Issues/evidence:** [#676](https://github.com/Gegcuk/QuizMaker/issues/676) R06; [#801](https://github.com/Gegcuk/QuizMaker/issues/801); [#465](https://github.com/Gegcuk/QuizMaker/issues/465) monitoring remains separately scheduled

**Your answer:** `GUEST-02: ___`

<a id="fut-assign-01"></a>
### FUT-ASSIGN-01 — Which resources does their creator automatically manage?

**When needed:** Before formal assignment administration.

**Problem:** Creation and transfer do not yet say who retains administration, or the exact individual cancellation contract.

**In plain language:** Creating a quiz, managing a class assignment and administering a school are different responsibilities. We need to say exactly who can hand those responsibilities to someone else and who can end a learner’s test.

**Real-life example:** Anna creates a quiz; Boris creates a school assignment from it. Does Anna automatically manage Boris’s assignment, or only her own quiz?

**Options:**

- **A. Resource creator manages that resource (recommended)** — Default management belongs to each resource's creator only. Quiz transfer does not grant administration of every assignment.
- **B. Quiz creator also manages assignments** — Quiz creator also defaults manager of every derived assignment. Convenient, broader visibility/authority.

**Recommendation:** Option A. Default management belongs to each resource's creator only.

**Decision details:** Authority checks must distinguish quiz, assignment and organization; preserve evidence and do not expose internal rationale.

**Issues/evidence:** [#672](https://github.com/Gegcuk/QuizMaker/issues/672) A08/R02; [#798](https://github.com/Gegcuk/QuizMaker/issues/798)

**Your answer:** `FUT-ASSIGN-01: ___`

<a id="fut-assign-02"></a>
### FUT-ASSIGN-02 — Who may add or remove resource managers?

**When needed:** Before formal assignment administration.

**Problem:** Creation and transfer do not yet say who retains administration, or the exact individual cancellation contract.

**In plain language:** Creating a quiz, managing a class assignment and administering a school are different responsibilities. We need to say exactly who can hand those responsibilities to someone else and who can end a learner’s test.

**Real-life example:** Boris is one of three assignment managers and wants to add a fourth teacher. Must an organization administrator approve, or may Boris delegate with a specific permission?

**Options:**

- **A. Organization admin or personal owner (recommended)** — Organization admin (or current personal owner outside an org) only. It limits delegation powers to accountable resource authorities.
- **B. Managers with delegation permission** — Every current manager with a distinct delegation permission. Distributed administration, requires privilege-escalation constraints.

**Recommendation:** Option A. It limits delegation powers to accountable resource authorities.

**Decision details:** Authority checks must distinguish quiz, assignment and organization; preserve evidence and do not expose internal rationale.

**Issues/evidence:** [#672](https://github.com/Gegcuk/QuizMaker/issues/672) A08/R02; [#798](https://github.com/Gegcuk/QuizMaker/issues/798)

**Your answer:** `FUT-ASSIGN-02: ___`

<a id="fut-assign-03"></a>
### FUT-ASSIGN-03 — Which rights does the previous owner retain after a transfer?

**When needed:** Before formal assignment administration.

**Problem:** Creation and transfer do not yet say who retains administration, or the exact individual cancellation contract.

**In plain language:** Creating a quiz, managing a class assignment and administering a school are different responsibilities. We need to say exactly who can hand those responsibilities to someone else and who can end a learner’s test.

**Real-life example:** Anna transfers her quiz to Boris. On the next day, may Anna still edit/manage it, only read it, or neither unless Boris explicitly retained her rights?

**Options:**

- **A. Previous owner loses rights by default (recommended)** — Old owner loses management unless explicitly retained during transfer. Transfers have a clear end to the previous owner’s authority unless both parties deliberately retain it.
- **B. Previous owner remains co-manager** — Old owner remains a visible co-manager by default.. Old owner remains a visible co-manager by default.
- **C. Previous owner becomes read-only** — Old owner becomes read-only. Receiving manager always sees the full management list.

**Recommendation:** Option A. Transfers have a clear end to the previous owner’s authority unless both parties deliberately retain it.

**Decision details:** Authority checks must distinguish quiz, assignment and organization; preserve evidence and do not expose internal rationale.

**Issues/evidence:** [#672](https://github.com/Gegcuk/QuizMaker/issues/672) A08/R02; [#798](https://github.com/Gegcuk/QuizMaker/issues/798)

**Your answer:** `FUT-ASSIGN-03: ___`

<a id="fut-assign-04"></a>
### FUT-ASSIGN-04 — May an authorized manager cancel one individual attempt?

**When needed:** Before formal assignment administration.

**Problem:** Creation and transfer do not yet say who retains administration, or the exact individual cancellation contract.

**In plain language:** Creating a quiz, managing a class assignment and administering a school are different responsibilities. We need to say exactly who can hand those responsibilities to someone else and who can end a learner’s test.

**Real-life example:** One participant’s attempt is compromised while the other 29 learners continue. Can the manager cancel just that attempt, or only the entire assignment’s active attempts?

**Options:**

- **A. Individual and assignment-wide cancellation (recommended)** — Support both individual cancellation and already-approved assignment-wide cancellation, require reason for each operation. It resolves one compromised or disrupted attempt without cancelling the entire class.
- **B. Assignment-wide cancellation initially** — Only the already-approved assignment-wide operation initially. Less implementation, harder to resolve one compromised attempt. Bulk behavior itself is not up for reapproval.

**Recommendation:** Option A. It resolves one compromised or disrupted attempt without cancelling the entire class.

**Decision details:** Authority checks must distinguish quiz, assignment and organization; preserve evidence and do not expose internal rationale.

**Issues/evidence:** [#672](https://github.com/Gegcuk/QuizMaker/issues/672) A08/R02; [#798](https://github.com/Gegcuk/QuizMaker/issues/798)

**Your answer:** `FUT-ASSIGN-04: ___`

<a id="fut-assign-05"></a>
### FUT-ASSIGN-05 — What should the participant see after authority cancellation?

**When needed:** Before formal assignment administration.

**Problem:** Creation and transfer do not yet say who retains administration, or the exact individual cancellation contract.

**In plain language:** Creating a quiz, managing a class assignment and administering a school are different responsibilities. We need to say exactly who can hand those responsibilities to someone else and who can end a learner’s test.

**Real-life example:** A teacher cancels an active attempt for an operational reason. The participant should understand that an authority ended it, without receiving the teacher’s private investigation notes.

**Options:**

- **A. Explicit authority-cancelled status (recommended)** — CANCELLED_BY_AUTHORITY with neutral message, saved evidence retained and no successful result fabricated. The learner can distinguish their own cancellation from an administrative decision.
- **B. Generic cancelled status** — Existing generic CANCELLED with separate authorized reason view. Simpler contract, less user clarity. Internal sensitive rationale is never copied directly to participant text. Legacy quiz-owner endpoints must use explicit adapters, not grant broad assignment authority.

**Recommendation:** Option A. The learner can distinguish their own cancellation from an administrative decision.

**Decision details:** Authority checks must distinguish quiz, assignment and organization; preserve evidence and do not expose internal rationale.

**Issues/evidence:** [#672](https://github.com/Gegcuk/QuizMaker/issues/672) A08/R02; [#798](https://github.com/Gegcuk/QuizMaker/issues/798)

**Your answer:** `FUT-ASSIGN-05: ___`

<a id="fut-tags-01"></a>
### FUT-TAGS-01 — May a tag be retired while published quizzes still reference it?

**When needed:** Before taxonomy-dependent selection or reports.

**Problem:** Stable flat tag identity is approved, but retirement, localization and group/Unicode behavior remain undefined.

**In plain language:** Tags are topic labels used to find questions and build reports. Changing a label today should not change what an old quiz or report meant. We need rules for names, languages and who may edit them.

**Real-life example:** A tag named Algebra is used in 100 published quiz revisions but is no longer wanted in new authoring. Should retirement hide it for new work or remain forbidden while those revisions exist?

**Options:**

- **A. Retire for new use, preserve history (recommended)** — Hide retired tags from new authoring, preserve historical IDs/labels. Authors can clean up current catalogues without rewriting published references.
- **B. Block retirement while referenced** — Forbid retirement while a published revision references a tag. Simpler but accumulates unusable tags.

**Recommendation:** Option A. Authors can clean up current catalogues without rewriting published references.

**Decision details:** The selected normalization must be pinned and covered by locale-independent golden fixtures; no hierarchy is introduced.

**Issues/evidence:** [#673](https://github.com/Gegcuk/QuizMaker/issues/673) R04; [#799](https://github.com/Gegcuk/QuizMaker/issues/799)/#604

**Your answer:** `FUT-TAGS-01: ___`

<a id="fut-tags-02"></a>
### FUT-TAGS-02 — Do tags need translated display names in their first release?

**When needed:** Before taxonomy-dependent selection or reports.

**Problem:** Stable flat tag identity is approved, but retirement, localization and group/Unicode behavior remain undefined.

**In plain language:** Tags are topic labels used to find questions and build reports. Changing a label today should not change what an old quiz or report meant. We need rules for names, languages and who may edit them.

**Real-life example:** A bilingual school wants the same tag ID displayed as Mathematics in English and Математика in Russian. Is that supported initially or does the tag have one label?

**Options:**

- **A. One display label (recommended)** — One display label in v1. It keeps the first catalogue small while preserving stable IDs for later translations.
- **B. Localized labels** — Localized labels keyed by language with one stable ID. Supports multilingual catalogues, larger authoring/uniqueness rules.

**Recommendation:** Option A. It keeps the first catalogue small while preserving stable IDs for later translations.

**Decision details:** The selected normalization must be pinned and covered by locale-independent golden fixtures; no hierarchy is introduced.

**Issues/evidence:** [#673](https://github.com/Gegcuk/QuizMaker/issues/673) R04; [#799](https://github.com/Gegcuk/QuizMaker/issues/799)/#604

**Your answer:** `FUT-TAGS-02: ___`

<a id="fut-tags-03"></a>
### FUT-TAGS-03 — Who manages group tags after membership changes?

**When needed:** Before taxonomy-dependent selection or reports.

**Problem:** Stable flat tag identity is approved, but retirement, localization and group/Unicode behavior remain undefined.

**In plain language:** Tags are topic labels used to find questions and build reports. Changing a label today should not change what an old quiz or report meant. We need rules for names, languages and who may edit them.

**Real-life example:** A teacher creates a group tag and then leaves the group. Who may rename it afterward, and how do previously published quizzes retain its historical meaning?

**Options:**

- **A. Group administrators manage tags (recommended)** — Group administrators manage tags; membership loss blocks future management/use without rewriting existing published references. Group authority stays explicit without deleting published historical meaning.
- **B. Authorized members manage tags** — Members with a dedicated tag permission may manage. More collaboration, broader powers.

**Recommendation:** Option A. Group authority stays explicit without deleting published historical meaning.

**Decision details:** The selected normalization must be pinned and covered by locale-independent golden fixtures; no hierarchy is introduced.

**Issues/evidence:** [#673](https://github.com/Gegcuk/QuizMaker/issues/673) R04; [#799](https://github.com/Gegcuk/QuizMaker/issues/799)/#604

**Your answer:** `FUT-TAGS-03: ___`

<a id="fut-tags-04"></a>
### FUT-TAGS-04 — Which visually similar Unicode tag names count as the same name?

**When needed:** Before taxonomy-dependent selection or reports.

**Problem:** Stable flat tag identity is approved, but retirement, localization and group/Unicode behavior remain undefined.

**In plain language:** Tags are topic labels used to find questions and build reports. Changing a label today should not change what an old quiz or report meant. We need rules for names, languages and who may edit them.

**Real-life example:** One author creates Algebra and another enters Ａlgebra using a full-width first letter in the same scope. Should those names be distinct or collide under the versioned comparison rule?

**Options:**

- **A. Preserve compatibility distinctions (recommended)** — Canonical Unicode normalization plus locale-independent lowercase, preserve width/compatibility distinctions. It avoids silently merging distinct author labels beyond the approved case-insensitive rule.
- **B. Collapse compatibility-equivalent names** — Compatibility normalization plus the approved case-insensitive rule. E.g. visually similar full-width Latin names collide. Both need fixed version/golden cases. This is the remaining exact comparison contract, not permission to introduce hierarchical topics.

**Recommendation:** Option A. It avoids silently merging distinct author labels beyond the approved case-insensitive rule.

**Decision details:** The selected normalization must be pinned and covered by locale-independent golden fixtures; no hierarchy is introduced.

**Issues/evidence:** [#673](https://github.com/Gegcuk/QuizMaker/issues/673) R04; [#799](https://github.com/Gegcuk/QuizMaker/issues/799)/#604

**Your answer:** `FUT-TAGS-04: ___`

<a id="fut-release-01"></a>
### FUT-RELEASE-01 — How does an assignment choose when results become visible?

**When needed:** Before formal result release or visible correction notifications.

**Problem:** Release defaults and material notification delivery are not yet fully defined.

**In plain language:** Students need to know when a result becomes visible and when an already visible result changes. A message delivery failure must not undo a valid corrected result or reveal answers early.

**Real-life example:** A teacher publishes an assessment without selecting result timing. Is publication blocked, are results scheduled at assignment close, or do they wait for manual release?

**Options:**

- **A. Require assignment release choice (recommended)** — Assignment must explicitly choose manual or scheduled release before publication. No accidental early release.
- **B. Default to assignment close** — Default scheduled at assignment close. Less configuration.
- **C. Default to manual release** — Default manual. Teacher controls timing but can forget.

**Recommendation:** Option A. Assignment must explicitly choose manual or scheduled release before publication.

**Decision details:** Notification retry interval/max attempts, expiry/dead-letter ownership and acknowledgement must all be explicit before implementation; proposed periods are not existing approvals.

**Issues/evidence:** [#675](https://github.com/Gegcuk/QuizMaker/issues/675) R05; [#800](https://github.com/Gegcuk/QuizMaker/issues/800)

**Your answer:** `FUT-RELEASE-01: ___`

<a id="fut-release-02"></a>
### FUT-RELEASE-02 — Which changes to a visible result should notify the learner?

**When needed:** Before formal result release or visible correction notifications.

**Problem:** Release defaults and material notification delivery are not yet fully defined.

**In plain language:** Students need to know when a result becomes visible and when an already visible result changes. A message delivery failure must not undo a valid corrected result or reveal answers early.

**Real-life example:** A correction changes 13/20 fail to 14/20 pass; another changes 17/20 pass to 18/20 pass. The first must be material; should the second also notify the learner?

**Options:**

- **A. Every changed visible outcome (recommended)** — Any changed already-visible score/pass/certificate status. Both examples notify.
- **B. Pass or certificate changes only** — Only pass/fail or certificate eligibility;17→18 stays quiet.. Only pass/fail or certificate eligibility;17→18 stays quiet.
- **C. Saved event-type allowlist** — Explicit per-assignment allowlist of those event types. Flexible, more policy surface.

**Recommendation:** Option A. Any changed already-visible score/pass/certificate status.

**Decision details:** Notification retry interval/max attempts, expiry/dead-letter ownership and acknowledgement must all be explicit before implementation; proposed periods are not existing approvals.

**Issues/evidence:** [#675](https://github.com/Gegcuk/QuizMaker/issues/675) R05; [#800](https://github.com/Gegcuk/QuizMaker/issues/800)

**Your answer:** `FUT-RELEASE-02: ___`

<a id="fut-release-03"></a>
### FUT-RELEASE-03 — Which channels deliver result-change notifications?

**When needed:** Before formal result release or visible correction notifications.

**Problem:** Release defaults and material notification delivery are not yet fully defined.

**In plain language:** Students need to know when a result becomes visible and when an already visible result changes. A message delivery failure must not undo a valid corrected result or reveal answers early.

**Real-life example:** A learner rarely opens the app after an assessment. Should a visible result correction appear only in the app, or also arrive at their verified email address?

**Options:**

- **A. In-app initially (recommended)** — In-app only initially. Simplest privacy/reliability.
- **B. In-app and verified email** — In-app plus verified-address email respecting notification preferences. More reach, delivery/provider burden.

**Recommendation:** Option A. In-app only initially.

**Decision details:** Notification retry interval/max attempts, expiry/dead-letter ownership and acknowledgement must all be explicit before implementation; proposed periods are not existing approvals.

**Issues/evidence:** [#675](https://github.com/Gegcuk/QuizMaker/issues/675) R05; [#800](https://github.com/Gegcuk/QuizMaker/issues/800)

**Your answer:** `FUT-RELEASE-03: ___`

<a id="fut-release-04"></a>
### FUT-RELEASE-04 — How long should failed result notifications be retried?

**When needed:** Before formal result release or visible correction notifications.

**Problem:** Release defaults and material notification delivery are not yet fully defined.

**In plain language:** Students need to know when a result becomes visible and when an already visible result changes. A message delivery failure must not undo a valid corrected result or reveal answers early.

**Real-life example:** An eligible result-change email cannot be delivered for two days. Should it already be in a dead-letter queue, or should automatic retries continue?

**Options:**

- **A. Retry up to 24 hours (recommended)** — Make at most eight delivery attempts at 0, 1 minute, 5 minutes, 30 minutes, 2 hours, 6 hours, 12 hours and 23 hours after the durable notification is recorded. At 24 hours, keep an undelivered item for operator review instead of retrying forever. All these times are proposals requiring approval.
- **B. Retry up to seven days** — Use the same first eight attempts, then attempts at 48, 96 and 144 hours (11 total); expire automatic delivery at seven days and alert after 24 hours. More chance of delivery after a long outage, but an old message may arrive much later.

**Recommendation:** Option A. It bounds stale delivery and hands persistent failures to an operator.

**Decision details:** Success stops retries; duplicate deliveries use one stable notification identity. No replay may reveal content that is no longer authorized. A manually retried old notification must recheck current relevance/access. This schedule is proposed for this notification feature only, not AI/provider retries.

**Issues/evidence:** [#675](https://github.com/Gegcuk/QuizMaker/issues/675) R05; [#800](https://github.com/Gegcuk/QuizMaker/issues/800)

**Your answer:** `FUT-RELEASE-04: ___`

<a id="fut-release-05"></a>
### FUT-RELEASE-05 — Must learners explicitly acknowledge result-change notifications?

**When needed:** Before formal result release or visible correction notifications.

**Problem:** Release defaults and material notification delivery are not yet fully defined.

**In plain language:** Students need to know when a result becomes visible and when an already visible result changes. A message delivery failure must not undo a valid corrected result or reveal answers early.

**Real-life example:** A learner has seen a corrected result but has not clicked an acknowledgement button. Should that create an outstanding task visible to a teacher, or is read/unread information sufficient?

**Options:**

- **A. Informational read/unread notification (recommended)** — Read/unread informational state, no required acknowledgement, scoped support role owns dead letters. It informs learners without creating a new mandatory acknowledgement workflow.
- **B. Require explicit acknowledgement** — Require explicit acknowledgement and teacher-visible outstanding status. Adds participant tracking/privacy behavior. Neither can roll back a valid corrected result.

**Recommendation:** Option A. It informs learners without creating a new mandatory acknowledgement workflow.

**Decision details:** Notification retry interval/max attempts, expiry/dead-letter ownership and acknowledgement must all be explicit before implementation; proposed periods are not existing approvals.

**Issues/evidence:** [#675](https://github.com/Gegcuk/QuizMaker/issues/675) R05; [#800](https://github.com/Gegcuk/QuizMaker/issues/800)

**Your answer:** `FUT-RELEASE-05: ___`

<a id="fut-time-01"></a>
### FUT-TIME-01 — How precisely should extra time be calculated?

**When needed:** Before extra-time accommodations.

**Problem:** Approved 25/50/100% grants still lack duration precision, maximum and multiple-grant precedence.

**In plain language:** Extra-time permission changes how long a learner has. The saved time allowance must be predictable even if the screen rounds its display, several grants exist, or the assessment has a maximum length. The already approved assignment closing time remains a hard limit.

**Real-life example:** A 25% grant on a 90-second duration produces 112.5 seconds. Should the deadline preserve that half second, or round upward to 113 seconds?

**Options:**

- **A. Exact millisecond timing (recommended)** — Integer milliseconds with exact percentage arithmetic, round upward only below that unit. It preserves small entitlement fractions independently of display formatting.
- **B. Round upward to seconds** — Round upward to whole seconds. Easier display, small extra time. UI formatting never truncates server entitlement.

**Recommendation:** Option A. It preserves small entitlement fractions independently of display formatting.

**Decision details:** If a new absolute effective cap is selected, supply the number/unit. Do not infer it from the ordinary author-input 1440-minute bound.

**Issues/evidence:** [#677](https://github.com/Gegcuk/QuizMaker/issues/677) R07; [#674](https://github.com/Gegcuk/QuizMaker/issues/674) A17; [#564](https://github.com/Gegcuk/QuizMaker/issues/564)/#565

**Your answer:** `FUT-TIME-01: ___`

<a id="fut-time-02"></a>
### FUT-TIME-02 — What maximum duration applies after an extra-time grant?

**When needed:** Before extra-time accommodations.

**Problem:** Approved 25/50/100% grants still lack duration precision, maximum and multiple-grant precedence.

**In plain language:** Extra-time permission changes how long a learner has. The saved time allowance must be predictable even if the screen rounds its display, several grants exist, or the assessment has a maximum length. The already approved assignment closing time remains a hard limit.

**Real-life example:** A preset’s maximum duration is 120 minutes and a 50% grant would produce 180. Must configuration/start reject that combination, or may an approved accommodation-specific cap permit it?

**Options:**

- **A. Preset maximum remains effective cap (recommended)** — Cap effective duration at the preset's maximum and reject incompatible grants before start. It prevents a grant from silently exceeding the policy’s stated operational duration.
- **B. Separate accommodation maximum** — Allow accommodation beyond ordinary duration cap up to a separately approved absolute maximum. Supports long assessments, requires that number. Do not silently infer 1440 minutes from author-input bounds.

**Recommendation:** Option A. It prevents a grant from silently exceeding the policy’s stated operational duration.

**Decision details:** If a new absolute effective cap is selected, supply the number/unit. Do not infer it from the ordinary author-input 1440-minute bound.

**Issues/evidence:** [#677](https://github.com/Gegcuk/QuizMaker/issues/677) R07; [#674](https://github.com/Gegcuk/QuizMaker/issues/674) A17; [#564](https://github.com/Gegcuk/QuizMaker/issues/564)/#565

**Your answer:** `FUT-TIME-02: ___`

<a id="fut-time-03"></a>
### FUT-TIME-03 — Which extra-time grant wins if several are active?

**When needed:** Before extra-time accommodations.

**Problem:** Approved 25/50/100% grants still lack duration precision, maximum and multiple-grant precedence.

**In plain language:** Extra-time permission changes how long a learner has. The saved time allowance must be predictable even if the screen rounds its display, several grants exist, or the assessment has a maximum length. The already approved assignment closing time remains a hard limit.

**Real-life example:** A participant has active 25% and 50% grants from different authorized actions. Should the largest percentage win, or should the latest explicitly superseding grant win?

**Options:**

- **A. Largest percentage wins (recommended)** — Largest active approved percentage wins. It is predictable and avoids unintended stacking.
- **B. Newest superseding grant wins** — Newest explicitly superseding grant wins. Stacking is not among approved timing codes and would require reopening scope. A 25% plus 50% grant therefore gives 50%, not 75%, under A.

**Recommendation:** Option A. It is predictable and avoids unintended stacking.

**Decision details:** If a new absolute effective cap is selected, supply the number/unit. Do not infer it from the ordinary author-input 1440-minute bound.

**Issues/evidence:** [#677](https://github.com/Gegcuk/QuizMaker/issues/677) R07; [#674](https://github.com/Gegcuk/QuizMaker/issues/674) A17; [#564](https://github.com/Gegcuk/QuizMaker/issues/564)/#565

**Your answer:** `FUT-TIME-03: ___`

<a id="fut-audit-01"></a>
### FUT-AUDIT-01 — Which actions and reads require mandatory audit evidence?

**When needed:** Before consequential operator actions and sensitive-access audit/export.

**Problem:** The audit write boundary is approved, but mandatory inventory and export operations are incomplete.

**In plain language:** A required record of a sensitive action is saved together with that action. If the required record cannot be saved, the action cannot complete. Sending a copy to a separate monitoring system happens later and must not undo an action that was recorded successfully.

**Real-life example:** An operator revokes an assignment, a teacher opens a purpose-authorized raw answer, and a participant opens their own released total. Which operations require a separate mandatory access-audit fact?

**Options:**

- **A. Operator actions and sensitive reads (recommended)** — All consequential operator/admin commands plus protected/raw-answer reads; ordinary participant redacted reads need no extra operator audit. It records consequential and sensitive access without collecting every routine participant read.
- **B. Also audit participant result/review reads** — All protected reads including participant result/review. Richer access evidence, higher volume/privacy retention. Enumerate exact routes/commands before implementation, not “sensitive operations” alone.

**Recommendation:** Option A. It records consequential and sensitive access without collecting every routine participant read.

**Decision details:** Specify exact audited commands/reads, retry intervals/max attempts, delivery retention, backlog alerts and manual-retry authority. An option with missing values is not implementation-ready.

**Issues/evidence:** [#698](https://github.com/Gegcuk/QuizMaker/issues/698) R08

**Your answer:** `FUT-AUDIT-01: ___`

<a id="fut-audit-02"></a>
### FUT-AUDIT-02 — Which reads remain available if mandatory audit storage fails?

**When needed:** Before consequential operator actions and sensitive-access audit/export.

**Problem:** The audit write boundary is approved, but mandatory inventory and export operations are incomplete.

**In plain language:** A required record of a sensitive action is saved together with that action. If the required record cannot be saved, the action cannot complete. Sending a copy to a separate monitoring system happens later and must not undo an action that was recorded successfully.

**Real-life example:** The mandatory audit store is unavailable. A teacher requests sensitive raw answers while a participant requests an ordinary redacted attempt view. Must both fail, or only the read requiring audit evidence?

**Options:**

- **A. Block only mandatory-audit operations (recommended)** — Fail closed only operations whose evidence is mandatory; allow authorized ordinary reads not in that inventory. It preserves ordinary availability without weakening mandatory evidence.
- **B. Block all execution reads** — Block every execution read. Simpler outage rule, larger availability impact.

**Recommendation:** Option A. It preserves ordinary availability without weakening mandatory evidence.

**Decision details:** Specify exact audited commands/reads, retry intervals/max attempts, delivery retention, backlog alerts and manual-retry authority. An option with missing values is not implementation-ready.

**Issues/evidence:** [#698](https://github.com/Gegcuk/QuizMaker/issues/698) R08

**Your answer:** `FUT-AUDIT-02: ___`

<a id="fut-audit-03"></a>
### FUT-AUDIT-03 — How should audit-export failures be retried and retained?

**When needed:** Before consequential operator actions and sensitive-access audit/export.

**Problem:** The audit write boundary is approved, but mandatory inventory and export operations are incomplete.

**In plain language:** A required record of a sensitive action is saved together with that action. If the required record cannot be saved, the action cannot complete. Sending a copy to a separate monitoring system happens later and must not undo an action that was recorded successfully.

**Real-life example:** A revocation committed successfully, but the external audit destination rejects the event repeatedly. After the approved retry window, does the event enter a dead-letter queue or remain automatically retryable until retention expiry?

**Options:**

- **A. Bounded retry then dead-letter (recommended)** — Bounded retry with dead-letter, retain pending evidence until approved audit retention, alert when oldest pending exceeds five minutes. It isolates poison events and provides a visible operator recovery path.
- **B. Retry until retention expires** — Retry without fixed attempt cap until retention expiry, same alert. Less operator replay, poison events require quarantine. Exact backoff/max attempts/retention must be a versioned operational configuration and must not silently borrow command-receipt retention.

**Recommendation:** Option A. It isolates poison events and provides a visible operator recovery path.

**Decision details:** Specify exact audited commands/reads, retry intervals/max attempts, delivery retention, backlog alerts and manual-retry authority. An option with missing values is not implementation-ready. This question is independent of the other choices in its topic; any unspecified required numeric or operational fields must be supplied before that slice is implementation-ready.

**Issues/evidence:** [#698](https://github.com/Gegcuk/QuizMaker/issues/698) R08

**Your answer:** `FUT-AUDIT-03: ___`

<a id="fut-audit-04"></a>
### FUT-AUDIT-04 — Who may manually retry audit delivery?

**When needed:** Before consequential operator actions and sensitive-access audit/export.

**Problem:** The audit write boundary is approved, but mandatory inventory and export operations are incomplete.

**In plain language:** A required record of a sensitive action is saved together with that action. If the required record cannot be saved, the action cannot complete. Sending a copy to a separate monitoring system happens later and must not undo an action that was recorded successfully.

**Real-life example:** A support employee sees a failed audit-export event. May any platform administrator retry it, or only someone with a dedicated audit-operations permission?

**Options:**

- **A. Dedicated audit operations permission (recommended)** — Dedicated audit-operations permission, preserve event ID and record retry. It keeps sensitive delivery control scoped and auditable.
- **B. Platform administrator permission** — Broader platform admin permission. Simpler roles, wider sensitive power. Neither may edit source evidence.

**Recommendation:** Option A. It keeps sensitive delivery control scoped and auditable.

**Decision details:** Specify exact audited commands/reads, retry intervals/max attempts, delivery retention, backlog alerts and manual-retry authority. An option with missing values is not implementation-ready.

**Issues/evidence:** [#698](https://github.com/Gegcuk/QuizMaker/issues/698) R08

**Your answer:** `FUT-AUDIT-04: ___`

<a id="fut-repair-01"></a>
### FUT-REPAIR-01 — Which proven recovery operations may run automatically?

**When needed:** Before automatic repair; core release still needs a safe manual/quarantine operating boundary.

**Problem:** Only deterministic repair is approved; exact patterns, ages, retries and operator resolution remain open.

**In plain language:** The system can repair a missing summary from facts it already saved. Conflicting answers or uncertain ownership need a person to investigate. Automatic repair must never make up a learner’s answers, marks or identity.

**Real-life example:** A result exists but its report projection is missing; another attempt is stuck between two workflow steps. Should automation handle only the first, or also the second when immutable facts prove exactly one outcome?

**Options:**

- **A. Derived projections and proven due actions (recommended)** — Only missing/stale derived projections and overdue expiry/release evaluation whose facts prove one outcome. It starts with narrow patterns whose source evidence is unambiguous.
- **B. Also proven core workflow repairs** — Additionally recover committed command/result workflow states with a proven unique outcome. Broader restoration, requires a separate fixture for every pattern. “Stuck” alone is never sufficient.

**Recommendation:** Option A. It starts with narrow patterns whose source evidence is unambiguous.

**Decision details:** For every pattern specify age threshold, backoff/max attempts, bounded reason code, operator role/SLA, participant message and resumption. Proposed retry/SLA values need approval.

**Issues/evidence:** [#706](https://github.com/Gegcuk/QuizMaker/issues/706)/#707 R10

**Your answer:** `FUT-REPAIR-01: ___`

<a id="fut-repair-02"></a>
### FUT-REPAIR-02 — How many automatic repair attempts should precede quarantine?

**When needed:** Before automatic repair; core release still needs a safe manual/quarantine operating boundary.

**Problem:** Only deterministic repair is approved; exact patterns, ages, retries and operator resolution remain open.

**In plain language:** The system can repair a missing summary from facts it already saved. Conflicting answers or uncertain ownership need a person to investigate. Automatic repair must never make up a learner’s answers, marks or identity.

**Real-life example:** A deterministic projection repair encounters temporary database errors. Should one failed recovery hand off to an operator, or may it make three bounded attempts first?

**Options:**

- **A. Three bounded automatic attempts (recommended)** — Pattern-specific grace after ordinary worker SLO, three bounded retries then quarantine. It tolerates transient failure without endless mutation attempts.
- **B. One attempt then operator** — One automatic attempt then operator. Less automated churn, more support. Publish each age/backoff/limit per pattern before enabling it; no global guessed timeout.

**Recommendation:** Option A. It tolerates transient failure without endless mutation attempts.

**Decision details:** For every pattern specify age threshold, backoff/max attempts, bounded reason code, operator role/SLA, participant message and resumption. Proposed retry/SLA values need approval. This question is independent of the other choices in its topic; any unspecified required numeric or operational fields must be supplied before that slice is implementation-ready.

**Issues/evidence:** [#706](https://github.com/Gegcuk/QuizMaker/issues/706)/#707 R10

**Your answer:** `FUT-REPAIR-02: ___`

<a id="fut-repair-03"></a>
### FUT-REPAIR-03 — Who handles quarantined attempts and what does the learner see?

**When needed:** Before automatic repair; core release still needs a safe manual/quarantine operating boundary.

**Problem:** Only deterministic repair is approved; exact patterns, ages, retries and operator resolution remain open.

**In plain language:** The system can repair a missing summary from facts it already saved. Conflicting answers or uncertain ownership need a person to investigate. Automatic repair must never make up a learner’s answers, marks or identity.

**Real-life example:** A participant cannot resume because conflicting evidence forced quarantine. Does a dedicated platform execution operator own resolution, or can the school’s specifically authorized support team resolve its own case?

**Options:**

- **A. Dedicated execution operations role (recommended)** — Dedicated execution-operations role; neutral temporary-unavailable message, preserve resume position, owner/on-call review within one working day. It provides accountable recovery while preserving the learner’s saved work.
- **B. Scoped organization support** — Organization support may resolve its scoped quarantines. Faster local support, broader powers and training.

**Recommendation:** Option A. It provides accountable recovery while preserving the learner’s saved work.

**Decision details:** For every pattern specify age threshold, backoff/max attempts, bounded reason code, operator role/SLA, participant message and resumption. Proposed retry/SLA values need approval.

**Issues/evidence:** [#706](https://github.com/Gegcuk/QuizMaker/issues/706)/#707 R10

**Your answer:** `FUT-REPAIR-03: ___`

<a id="fut-repair-04"></a>
### FUT-REPAIR-04 — How does a resolved quarantined attempt resume?

**When needed:** Before automatic repair; core release still needs a safe manual/quarantine operating boundary.

**Problem:** Only deterministic repair is approved; exact patterns, ages, retries and operator resolution remain open.

**In plain language:** The system can repair a missing summary from facts it already saved. Conflicting answers or uncertain ownership need a person to investigate. Automatic repair must never make up a learner’s answers, marks or identity.

**Real-life example:** An operator has reviewed and resolved the cause of a quarantined attempt. Is an explicit approved resume command required, or does marking the case resolved automatically trigger the normal command again?

**Options:**

- **A. Explicit approved resume command (recommended)** — Explicit reviewed resolution followed by the ordinary idempotent command and reconciliation. It gives the operator an explicit final check before participant execution resumes.
- **B. Automatic retry after marked resolution** — Automatically retry after operator marks evidence repaired. Fewer clicks, less explicit go/no-go. In both cases approved first-release stop/resume safety checks remain; nobody fabricates an answer or paid result.

**Recommendation:** Option A. It gives the operator an explicit final check before participant execution resumes.

**Decision details:** For every pattern specify age threshold, backoff/max attempts, bounded reason code, operator role/SLA, participant message and resumption. Proposed retry/SLA values need approval.

**Issues/evidence:** [#706](https://github.com/Gegcuk/QuizMaker/issues/706)/#707 R10

**Your answer:** `FUT-REPAIR-04: ___`

<a id="fut-report-01"></a>
### FUT-REPORT-01 — What does a report’s average score measure?

**When needed:** Before teacher/cohort reporting.

**Problem:** Report labels, denominators and query scope still lack exact definitions.

**In plain language:** A class report must say how its average is calculated and whose attempts count. Only authorized, released results may be shown; small groups stay hidden so one learner cannot be identified. Five retries by one person are still one person.

**Real-life example:** Selected eligible results are 1/1 and 1/9. The mean percentage is 55.56%, while earned/possible across both is 20%. Which should the displayed headline mean?

**Options:**

- **A. Mean normalized score (recommended)** — Mean normalized score. Average the normalized percentage of each selected eligible released result; every selected result has equal weight. The next question separately chooses all/latest/best results.
- **B. Point-weighted success** — Point-weighted success. Divide total earned by total possible across the selected eligible released results and label it point-weighted success; longer assessments have more influence.

**Recommendation:** Option A. Mean normalized score.

**Decision details:** Headline metric and repeat selection are independent. Preserve units; approved projection freshness is not a query-latency SLO. Record fixture/data size for any latency objective.

**Issues/evidence:** [#679](https://github.com/Gegcuk/QuizMaker/issues/679) R12/A32/D35/D36; [#606](https://github.com/Gegcuk/QuizMaker/issues/606)/#607/#631/#644

**Your answer:** `FUT-REPORT-01: ___`

<a id="fut-report-02"></a>
### FUT-REPORT-02 — Which repeated attempts contribute to report metrics?

**When needed:** Before teacher/cohort reporting.

**Problem:** Report labels, denominators and query scope still lack exact definitions.

**In plain language:** A class report must say how its average is calculated and whose attempts count. Only authorized, released results may be shown; small groups stay hidden so one learner cannot be identified. Five retries by one person are still one person.

**Real-life example:** The same learner scores 40%, 60% and 90% on three attempts. Does the report count all three, only the latest, or only the best? Privacy still counts one distinct person.

**Options:**

- **A. All eligible attempts (recommended)** — All eligible completed/released attempts. Show attempt count and distinct participant count separately.
- **B. Latest per participant/context** — Latest eligible attempt per participant/context.. Latest eligible attempt per participant/context.
- **C. Best per participant/context** — Best eligible attempt per participant/context. Privacy k is distinct people regardless of this choice; five retries by one person never qualify as five people.

**Recommendation:** Option A. All eligible completed/released attempts.

**Decision details:** Headline metric and repeat selection are independent. Preserve units; approved projection freshness is not a query-latency SLO. Record fixture/data size for any latency objective.

**Issues/evidence:** [#679](https://github.com/Gegcuk/QuizMaker/issues/679) R12/A32/D35/D36; [#606](https://github.com/Gegcuk/QuizMaker/issues/606)/#607/#631/#644

**Your answer:** `FUT-REPORT-02: ___`

<a id="fut-report-03"></a>
### FUT-REPORT-03 — Do teacher reports initially include individual participant summaries?

**When needed:** Before teacher/cohort reporting.

**Problem:** Report labels, denominators and query scope still lack exact definitions.

**In plain language:** A class report must say how its average is calculated and whose attempts count. Only authorized, released results may be shown; small groups stay hidden so one learner cannot be identified. Five retries by one person are still one person.

**Real-life example:** An authorized teacher sees a seven-person assignment aggregate and wants each learner’s released total. Is that individual summary in the first report release, or are aggregate and self-result views sufficient initially?

**Options:**

- **A. Aggregate and own-result views initially (recommended)** — Aggregate + participant self-view only first. It delivers useful reports without adding individual teaching-data access prematurely.
- **B. Also scoped participant teacher summaries** — Add authorized per-participant released totals/status under exact assignment scope. Helps teaching, needs fields/retention/access tests. Quiz ownership alone grants neither.

**Recommendation:** Option A. It delivers useful reports without adding individual teaching-data access prematurely.

**Decision details:** Headline metric and repeat selection are independent. Preserve units; approved projection freshness is not a query-latency SLO. Record fixture/data size for any latency objective.

**Issues/evidence:** [#679](https://github.com/Gegcuk/QuizMaker/issues/679) R12/A32/D35/D36; [#606](https://github.com/Gegcuk/QuizMaker/issues/606)/#607/#631/#644

**Your answer:** `FUT-REPORT-03: ___`

<a id="fut-report-04"></a>
### FUT-REPORT-04 — May authorized users download aggregate reports?

**When needed:** Before teacher/cohort reporting.

**Problem:** Report labels, denominators and query scope still lack exact definitions.

**In plain language:** A class report must say how its average is calculated and whose attempts count. Only authorized, released results may be shown; small groups stay hidden so one learner cannot be identified. Five retries by one person are still one person.

**Real-life example:** A teacher wants to download a CSV of a report whose small cells have been suppressed. Should a bounded export preserve exactly that scope/suppression, or is online viewing the only initial option?

**Options:**

- **A. No download initially (recommended)** — No download first. It avoids a second data distribution and retention channel before the online contract is stable.
- **B. Bounded suppressed aggregate export** — Paginated/bounded export of the same authorized suppressed aggregates. Useful offline work, creates revocation/retention obligations. Raw answers remain excluded.

**Recommendation:** Option A. It avoids a second data distribution and retention channel before the online contract is stable.

**Decision details:** Headline metric and repeat selection are independent. Preserve units; approved projection freshness is not a query-latency SLO. Record fixture/data size for any latency objective.

**Issues/evidence:** [#679](https://github.com/Gegcuk/QuizMaker/issues/679) R12/A32/D35/D36; [#606](https://github.com/Gegcuk/QuizMaker/issues/606)/#607/#631/#644

**Your answer:** `FUT-REPORT-04: ___`

<a id="fut-report-05"></a>
### FUT-REPORT-05 — What page sizes and query response target should reports have?

**When needed:** Before teacher/cohort reporting.

**Problem:** Report labels, denominators and query scope still lack exact definitions.

**In plain language:** A class report must say how its average is calculated and whose attempts count. Only authorized, released results may be shown; small groups stay hidden so one learner cannot be identified. Five retries by one person are still one person.

**Real-life example:** A report contains 500 authorized rows. Should the first page return 25 or 50, what is the hard maximum, and against which realistic fixture must the chosen response target be measured?

**Options:**

- **A. 25 default, 100 max, p95 one second (recommended)** — 25 rows default/100 maximum, p95≤1 s for explicitly documented representative cohort fixtures. It gives a bounded starting envelope that can be verified with realistic fixtures.
- **B. 50 default, 200 max, p95 two seconds** — 50/200 and p95≤2 s for larger administrative scans. Numbers require owner acceptance and measured evidence; existing projection-lag SLO is not query latency.

**Recommendation:** Option A. It gives a bounded starting envelope that can be verified with realistic fixtures.

**Decision details:** Headline metric and repeat selection are independent. Preserve units; approved projection freshness is not a query-latency SLO. Record fixture/data size for any latency objective.

**Issues/evidence:** [#679](https://github.com/Gegcuk/QuizMaker/issues/679) R12/A32/D35/D36; [#606](https://github.com/Gegcuk/QuizMaker/issues/606)/#607/#631/#644

**Your answer:** `FUT-REPORT-05: ___`

<a id="fut-report-06"></a>
### FUT-REPORT-06 — Report comparability is already decided — no repeat answer

**Status:** DECIDED. No repeat owner answer requested.

**Recorded decision:** A16 permits combining forms only when the versioned selection strategy, configuration, quotas, immutable source revision, scoring contract and explicit equivalence-group identifier all match. Missing or different required evidence means separate reporting.

**Required follow-through:** Copy this rule into report criteria and fixtures. Do not ask again whether to always separate forms or allow arbitrary pooling. Exact query/serialization work remains engineering work under the approved comparability rule.

**Authoritative source:** [#673, A16](https://github.com/Gegcuk/QuizMaker/issues/673)

<a id="fut-live-01"></a>
### FUT-LIVE-01 — How are Live teams formed and fixed before play?

**When needed:** Before Live activation; team-specific choices before team mode.

**Problem:** Capacity, round timing and no speed bonus are approved; teams, disconnection and late join are still incomplete.

**In plain language:** Learners play shared timed rounds. The server decides whether an answer arrived on time. Speed only breaks a score tie; it does not add points. Disconnects, late arrivals and teams need equally clear rules.

**Real-life example:** Twelve learners join a room. Does the host assign fixed teams before round 1, or may learners choose teams until the host approves the final roster? Team counts and aggregation still need explicit values.

**Options:**

- **A. Host fixes teams before start (recommended)** — Host assigns/finalizes roster before first round, 2–20 teams. It freezes team membership before scoring starts.
- **B. Participants choose, host approves** — Participants self-select until first round with host approval. Friendlier, requires uneven-team/late-change rules. Define team score aggregation before activating teams; team count alone is insufficient.

**Recommendation:** Option A. It freezes team membership before scoring starts.

**Decision details:** Pin team default/maximum and score aggregation, active/disconnect behavior, elapsed aggregation, event/snapshot retention and exact fixture. Replay window differs from raw 90-day retention. This question is independent of the other choices in its topic; any unspecified required numeric or operational fields must be supplied before that slice is implementation-ready.

**Issues/evidence:** [#678](https://github.com/Gegcuk/QuizMaker/issues/678) R11; [#584](https://github.com/Gegcuk/QuizMaker/issues/584)/#585/#586/#657/#658/#671

**Your answer:** `FUT-LIVE-01: ___`

<a id="fut-live-02"></a>
### FUT-LIVE-02 — Does a disconnected participant still count toward early round closure?

**When needed:** Before Live activation; team-specific choices before team mode.

**Problem:** Capacity, round timing and no speed bonus are approved; teams, disconnection and late join are still incomplete.

**In plain language:** Learners play shared timed rounds. The server decides whether an answer arrived on time. Speed only breaks a score tie; it does not add points. Disconnects, late arrivals and teams need equally clear rules.

**Real-life example:** Everyone except one disconnected participant has answered. May the round close immediately after a defined disconnect grace, or must that participant remain in the quorum until timeout or explicit removal?

**Options:**

- **A. Disconnected participants remain active (recommended)** — Admitted nonremoved participants remain active through disconnect until round closes. Prevents network trouble triggering early close.
- **B. Remove from quorum after grace** — Disconnect after an explicit grace removes them from the early-close quorum. Faster rounds, grace must be fixed.

**Recommendation:** Option A. Admitted nonremoved participants remain active through disconnect until round closes.

**Decision details:** Pin team default/maximum and score aggregation, active/disconnect behavior, elapsed aggregation, event/snapshot retention and exact fixture. Replay window differs from raw 90-day retention.

**Issues/evidence:** [#678](https://github.com/Gegcuk/QuizMaker/issues/678) R11; [#584](https://github.com/Gegcuk/QuizMaker/issues/584)/#585/#586/#657/#658/#671

**Your answer:** `FUT-LIVE-02: ___`

<a id="fut-live-03"></a>
### FUT-LIVE-03 — When may a late joiner start scoring?

**When needed:** Before Live activation; team-specific choices before team mode.

**Problem:** Capacity, round timing and no speed bonus are approved; teams, disconnection and late join are still incomplete.

**In plain language:** Learners play shared timed rounds. The server decides whether an answer arrived on time. Speed only breaks a score tie; it does not add points. Disconnects, late arrivals and teams need equally clear rules.

**Real-life example:** A learner arrives 20 seconds into a 30-second round. Do they watch until the next round, get only the remaining 10 seconds to score, or fail admission because play already started?

**Options:**

- **A. Score from next round (recommended)** — Spectate current round, join scoring next round. It gives every scored participant the same round exposure.
- **B. No late join** — No join once first round begins.. No join once first round begins.
- **C. Score with remaining round time** — Join current round with remaining time. Uneven time exposure.

**Recommendation:** Option A. It gives every scored participant the same round exposure.

**Decision details:** Pin team default/maximum and score aggregation, active/disconnect behavior, elapsed aggregation, event/snapshot retention and exact fixture. Replay window differs from raw 90-day retention.

**Issues/evidence:** [#678](https://github.com/Gegcuk/QuizMaker/issues/678) R11; [#584](https://github.com/Gegcuk/QuizMaker/issues/584)/#585/#586/#657/#658/#671

**Your answer:** `FUT-LIVE-03: ___`

<a id="fut-live-04"></a>
### FUT-LIVE-04 — How is elapsed time combined to break Live score ties?

**When needed:** Before Live activation; team-specific choices before team mode.

**Problem:** Capacity, round timing and no speed bonus are approved; teams, disconnection and late join are still incomplete.

**In plain language:** Learners play shared timed rounds. The server decides whether an answer arrived on time. Speed only breaks a score tie; it does not add points. Disconnects, late arrivals and teams need equally clear rules.

**Real-life example:** Two participants have the same score, but one skipped a difficult round. The tie-break must specify whether that missed round contributes its full duration rather than disappearing from the time average.

**Options:**

- **A. Sum elapsed with full missed-round time (recommended)** — Sum authoritative elapsed time over scored rounds, missed rounds count full round duration. It prevents skipping a difficult round from producing an artificially fast tie-break.
- **B. Average answered time plus penalty** — Average elapsed over answered rounds with missing-round penalty separately fixed. Can change rankings, harder to explain.

**Recommendation:** Option A. It prevents skipping a difficult round from producing an artificially fast tie-break.

**Decision details:** Pin team default/maximum and score aggregation, active/disconnect behavior, elapsed aggregation, event/snapshot retention and exact fixture. Replay window differs from raw 90-day retention.

**Issues/evidence:** [#678](https://github.com/Gegcuk/QuizMaker/issues/678) R11; [#584](https://github.com/Gegcuk/QuizMaker/issues/584)/#585/#586/#657/#658/#671

**Your answer:** `FUT-LIVE-04: ___`

<a id="fut-live-05"></a>
### FUT-LIVE-05 — How long can clients replay Live room events?

**When needed:** Before Live activation; team-specific choices before team mode.

**Problem:** Capacity, round timing and no speed bonus are approved; teams, disconnection and late join are still incomplete.

**In plain language:** Learners play shared timed rounds. The server decides whether an answer arrived on time. Speed only breaks a score tie; it does not add points. Disconnects, late arrivals and teams need equally clear rules.

**Real-life example:** A participant reconnects the morning after a completed evening session. Can the client replay events from its cursor, or must it load the safe authoritative snapshot?

**Options:**

- **A. Replay through closure plus 24 hours (recommended)** — Retain room event replay for the live room plus 24 hours, then safe snapshot; retain final facts by [#664](https://github.com/Gegcuk/QuizMaker/issues/664). It supports short-term reconnection without unbounded replay.
- **B. Replay only during session** — Session-only replay with immediate snapshot after closure. Less storage, less investigation. Reconcile raw 90-day retention purpose: replay eligibility need not equal raw-evidence retention.

**Recommendation:** Option A. It supports short-term reconnection without unbounded replay.

**Decision details:** Pin team default/maximum and score aggregation, active/disconnect behavior, elapsed aggregation, event/snapshot retention and exact fixture. Replay window differs from raw 90-day retention.

**Issues/evidence:** [#678](https://github.com/Gegcuk/QuizMaker/issues/678) R11; [#584](https://github.com/Gegcuk/QuizMaker/issues/584)/#585/#586/#657/#658/#671

**Your answer:** `FUT-LIVE-05: ___`

<a id="fut-live-06"></a>
### FUT-LIVE-06 — Which Live scenarios must pass before the first activation?

**When needed:** Before Live activation; team-specific choices before team mode.

**Problem:** Capacity, round timing and no speed bonus are approved; teams, disconnection and late join are still incomplete.

**In plain language:** Learners play shared timed rounds. The server decides whether an answer arrived on time. Speed only breaks a score tie; it does not add points. Disconnects, late arrivals and teams need equally clear rules.

**Real-life example:** The individual room passes timing, reconnect, force-close, tie and cursor-expiry tests, but teams are not implemented. May individual Live activate by itself?

**Options:**

- **A. Certify individual mode first (recommended)** — Individual room including tie, disconnect/resume, force-close, late receipt, expired cursor. It allows a complete individual mode to ship before the separate team contract is ready.
- **B. Certify individual and teams together** — Require both individual and team room fixtures before any Live activation. Later delivery, one feature release. Team activation always needs its own full fixture.

**Recommendation:** Option A. It allows a complete individual mode to ship before the separate team contract is ready.

**Decision details:** Pin team default/maximum and score aggregation, active/disconnect behavior, elapsed aggregation, event/snapshot retention and exact fixture. Replay window differs from raw 90-day retention.

**Issues/evidence:** [#678](https://github.com/Gegcuk/QuizMaker/issues/678) R11; [#584](https://github.com/Gegcuk/QuizMaker/issues/584)/#585/#586/#657/#658/#671

**Your answer:** `FUT-LIVE-06: ___`

<a id="fut-learn-01"></a>
### FUT-LEARN-01 — Preparing the Learning configuration is already assigned — no repeat answer

**Status:** ENGINEERING PREPARATION ALREADY ASSIGNED. No repeat owner answer requested.

**Recorded decision:** R13 already assigns #577 to pin the implementation artifact and complete immutable FSRS/v1 scheduler configuration. Asking the owner whether engineering should prepare that proposal or the owner should provide a specialist manifest adds an unnecessary approval step.

**Required follow-through:** Prepare the bounded engineering proposal under #577 first. Learning activation remains blocked until its actual unresolved configuration choices are settled. This does not approve unspecified algorithm parameters, a new dependency or runtime changes.

**Authoritative source:** [#681, R13](https://github.com/Gegcuk/QuizMaker/issues/681) and [#577](https://github.com/Gegcuk/QuizMaker/issues/577)

<a id="fut-adapt-01"></a>
### FUT-ADAPT-01 — What if Adaptive has insufficient questions before attempt creation?

**When needed:** Before Adaptive activation.

**Problem:** D26 permits fallback/completion after five while R15 requires insufficient-items failure without broadening eligibility.

**In plain language:** An adaptive quiz chooses the next suitable question as the learner progresses. Sometimes its approved question bank has nothing suitable left. The app needs an honest end or waiting state instead of silently substituting unrelated questions.

**Real-life example:** Before start, the immutable eligible bank cannot supply the required first items. Should no attempt be created, or should a waiting attempt exist with separately defined allowance/expiry rules?

**Options:**

- **A. Reject without creating an attempt (recommended)** — Reject without creating/consuming attempt. It avoids consuming an allowance for an attempt that cannot begin.
- **B. Create a waiting attempt** — Create an explicit waiting attempt. Participant can resume, but allowance and waiting expiry must be approved.

**Recommendation:** Option A. It avoids consuming an allowance for an attempt that cannot begin.

**Decision details:** Keep stage-specific outcomes separate; every approved result classification and allowance effect must be explicit. New bank publication cannot replace an active immutable bank.

**Issues/evidence:** [#685](https://github.com/Gegcuk/QuizMaker/issues/685) D26/R15; [#569](https://github.com/Gegcuk/QuizMaker/issues/569)/#571/#693; frontend#211

**Your answer:** `FUT-ADAPT-01: ___`

<a id="fut-adapt-02"></a>
### FUT-ADAPT-02 — What if Adaptive runs out before five answered items?

**When needed:** Before Adaptive activation.

**Problem:** D26 permits fallback/completion after five while R15 requires insufficient-items failure without broadening eligibility.

**In plain language:** An adaptive quiz chooses the next suitable question as the learner progresses. Sometimes its approved question bank has nothing suitable left. The app needs an honest end or waiting state instead of silently substituting unrelated questions.

**Real-life example:** A participant answers three items and the fixed eligible bank has no next item. Should the attempt end as insufficient evidence, or remain recoverable only if something permitted within that same fixed bank can change?

**Options:**

- **A. Terminal insufficient-evidence outcome (recommended)** — Terminal unsuccessful/insufficient-evidence state. It does not leave an apparently active attempt waiting for an impossible immutable-bank change.
- **B. Recoverable within immutable bank** — Remain blocked/recoverable within the same immutable bank. Must define what can actually change to restore eligibility. Unrelated fallback is forbidden.

**Recommendation:** Option A. It does not leave an apparently active attempt waiting for an impossible immutable-bank change.

**Decision details:** Keep stage-specific outcomes separate; every approved result classification and allowance effect must be explicit. New bank publication cannot replace an active immutable bank.

**Issues/evidence:** [#685](https://github.com/Gegcuk/QuizMaker/issues/685) D26/R15; [#569](https://github.com/Gegcuk/QuizMaker/issues/569)/#571/#693; frontend#211

**Your answer:** `FUT-ADAPT-02: ___`

<a id="fut-adapt-03"></a>
### FUT-ADAPT-03 — What if Adaptive runs out after five items but before its normal stop?

**When needed:** Before Adaptive activation.

**Problem:** D26 permits fallback/completion after five while R15 requires insufficient-items failure without broadening eligibility.

**In plain language:** An adaptive quiz chooses the next suitable question as the learner progresses. Sometimes its approved question bank has nothing suitable left. The app needs an honest end or waiting state instead of silently substituting unrelated questions.

**Real-life example:** A participant answers six items but has not met mastery or maximum length when eligible items run out. Should that be insufficient evidence or a completed attempt explicitly stopped by shortage?

**Options:**

- **A. Terminal insufficient-evidence outcome (recommended)** — Same explicit insufficient-evidence outcome. It distinguishes insufficient evidence from successful mastery and consistently honors R15.
- **B. Complete with shortage stopping reason** — Successful completion with labelled shortage stopping reason, preserving D26. No invented mastery/pass. This is the actual conflict requiring choice.

**Recommendation:** Option A. It distinguishes insufficient evidence from successful mastery and consistently honors R15.

**Decision details:** Keep stage-specific outcomes separate; every approved result classification and allowance effect must be explicit. New bank publication cannot replace an active immutable bank.

**Issues/evidence:** [#685](https://github.com/Gegcuk/QuizMaker/issues/685) D26/R15; [#569](https://github.com/Gegcuk/QuizMaker/issues/569)/#571/#693; frontend#211

**Your answer:** `FUT-ADAPT-03: ___`

<a id="fut-adapt-04"></a>
### FUT-ADAPT-04 — Does a provably insufficient Adaptive bank block publication?

**When needed:** Before Adaptive activation.

**Problem:** D26 permits fallback/completion after five while R15 requires insufficient-items failure without broadening eligibility.

**In plain language:** An adaptive quiz chooses the next suitable question as the learner progresses. Sometimes its approved question bank has nothing suitable left. The app needs an honest end or waiting state instead of silently substituting unrelated questions.

**Real-life example:** An author configures a five-item minimum but the static bank can supply only three eligible questions. Is publication blocked, or does it proceed with a warning?

**Options:**

- **A. Block static shortage, warn personal risk (recommended)** — Block configurations provably below the static minimum; warn about participant-specific unseen shortages. It catches predictable failures without claiming to know every participant’s future unseen-item state.
- **B. Warnings only** — Warnings only. More flexible experiments, more user-visible failures.

**Recommendation:** Option A. It catches predictable failures without claiming to know every participant’s future unseen-item state.

**Decision details:** Keep stage-specific outcomes separate; every approved result classification and allowance effect must be explicit. New bank publication cannot replace an active immutable bank.

**Issues/evidence:** [#685](https://github.com/Gegcuk/QuizMaker/issues/685) D26/R15; [#569](https://github.com/Gegcuk/QuizMaker/issues/569)/#571/#693; frontend#211

**Your answer:** `FUT-ADAPT-04: ___`

<a id="fut-adapt-05"></a>
### FUT-ADAPT-05 — Can an active Adaptive attempt use a newly edited bank?

**When needed:** Before Adaptive activation.

**Problem:** D26 permits fallback/completion after five while R15 requires insufficient-items failure without broadening eligibility.

**In plain language:** An adaptive quiz chooses the next suitable question as the learner progresses. Sometimes its approved question bank has nothing suitable left. The app needs an honest end or waiting state instead of silently substituting unrelated questions.

**Real-life example:** An Adaptive attempt is blocked, then the author adds new questions and publishes another revision. Must the learner start a new attempt, or can an explicit separately approved migration preserve and extend the old one?

**Options:**

- **A. New revision and new attempt (recommended)** — New revision/new attempt only. It preserves the original attempt’s immutable evidence and selection rules.
- **B. Explicit versioned migration procedure** — Resume only through a separately versioned explicit migration procedure with preserved old evidence. More complex and not ordinary mutable refresh. New publication never silently replaces an active bank.

**Recommendation:** Option A. It preserves the original attempt’s immutable evidence and selection rules.

**Decision details:** Keep stage-specific outcomes separate; every approved result classification and allowance effect must be explicit. New bank publication cannot replace an active immutable bank.

**Issues/evidence:** [#685](https://github.com/Gegcuk/QuizMaker/issues/685) D26/R15; [#569](https://github.com/Gegcuk/QuizMaker/issues/569)/#571/#693; frontend#211

**Your answer:** `FUT-ADAPT-05: ___`

<a id="fut-branch-01"></a>
### FUT-BRANCH-01 — Can a Branching attempt finish with required data unanswered?

**When needed:** Before Branching activation.

**Problem:** Terminal-ready state is approved; required unanswered items and final confirmation remain unspecified.

**In plain language:** A branching quiz takes different paths based on answers. Reaching the end of a path does not automatically submit the test. The learner needs a clear final action and a rule for any required answers still missing.

**Real-life example:** A learner reaches a terminal node but one delivered field is marked required and remains blank. Can final submit proceed with explicit incomplete classification?

**Options:**

- **A. Required items block final submit (recommended)** — Block final submission where the saved branch policy marks required items. It respects the saved meaning of a required response.
- **B. Allow confirmed incomplete submission** — Allow explicit incomplete finalization with unanswered/no-credit classification. Useful self-study, changes completion policy. Free-practice optional unanswered approval does not erase required formal data.

**Recommendation:** Option A. It respects the saved meaning of a required response.

**Decision details:** Carry forward common deadline expiry while terminal-ready; a new auto-submit-at-expiry exception would reopen existing policy. Use the ordinary receipt/command contract.

**Issues/evidence:** [#686](https://github.com/Gegcuk/QuizMaker/issues/686) R16; [#573](https://github.com/Gegcuk/QuizMaker/issues/573)/#574

**Your answer:** `FUT-BRANCH-01: ___`

<a id="fut-branch-02"></a>
### FUT-BRANCH-02 — What confirmation is required at a Branching terminal node?

**When needed:** Before Branching activation.

**Problem:** Terminal-ready state is approved; required unanswered items and final confirmation remain unspecified.

**In plain language:** A branching quiz takes different paths based on answers. Reaching the end of a path does not automatically submit the test. The learner needs a clear final action and a rule for any required answers still missing.

**Real-life example:** A learner reaches a terminal node with every required answer accepted. Must a separate confirmation summarize the irreversible final submit, or may a clearly labelled explicit submit button suffice?

**Options:**

- **A. Always show terminal confirmation (recommended)** — Terminal-ready summary lists unanswered items and irreversible submit, then ordinary idempotent command. It makes the irreversible final action and omissions clear to the participant.
- **B. Skip extra screen if complete** — Explicit submit without additional confirmation screen where no unanswered items. Fewer clicks, API still no answer payload. Request/receipt shape should reuse the core contract, not invent a second terminal writer.

**Recommendation:** Option A. It makes the irreversible final action and omissions clear to the participant.

**Decision details:** Carry forward common deadline expiry while terminal-ready; a new auto-submit-at-expiry exception would reopen existing policy. Use the ordinary receipt/command contract.

**Issues/evidence:** [#686](https://github.com/Gegcuk/QuizMaker/issues/686) R16; [#573](https://github.com/Gegcuk/QuizMaker/issues/573)/#574

**Your answer:** `FUT-BRANCH-02: ___`

<a id="fut-section-01"></a>
### FUT-SECTION-01 — Can a participant voluntarily submit an incomplete required section?

**When needed:** Before sectioned quizzes.

**Problem:** Section locking is fixed; incomplete submission and section-only timeout still lack exact outcomes.

**In plain language:** A quiz may have separately timed parts. Once a part is submitted, its answers stay locked. We must decide whether the end of one part’s timer moves the learner forward or ends the whole attempt.

**Real-life example:** A learner presses Submit Section with one required item blank and time still remaining. Should submission be blocked or permitted only after explicit incomplete-section confirmation?

**Options:**

- **A. Required items block voluntary submit (recommended)** — Block voluntary section submit until required answers accepted. It preserves the declared required-item rule on a voluntary action.
- **B. Allow confirmed incomplete section** — Permit explicitly confirmed incomplete section. No credit and no fake accepted wrong answers.

**Recommendation:** Option A. It preserves the declared required-item rule on a voluntary action.

**Decision details:** Grade corrections supersede immutable grading evidence; they do not reopen participant answers. Reopening would require a separate explicit new policy.

**Issues/evidence:** [#689](https://github.com/Gegcuk/QuizMaker/issues/689) D31/D32/R17; [#659](https://github.com/Gegcuk/QuizMaker/issues/659)/#660

**Your answer:** `FUT-SECTION-01: ___`

<a id="fut-section-02"></a>
### FUT-SECTION-02 — What happens when only the current section timer expires?

**When needed:** Before sectioned quizzes.

**Problem:** Section locking is fixed; incomplete submission and section-only timeout still lack exact outcomes.

**In plain language:** A quiz may have separately timed parts. Once a part is submitted, its answers stay locked. We must decide whether the end of one part’s timer moves the learner forward or ends the whole attempt.

**Real-life example:** Section 1 reaches its own deadline while 15 minutes remain overall. Does it lock and open section 2, or end the entire attempt?

**Options:**

- **A. Lock section and advance (recommended)** — Lock current section and advance, preserving accepted responses/restricted drafts. It uses the remaining overall time without reopening an expired section.
- **B. Expire the whole attempt** — Expire the whole attempt. Stricter, simpler. Total-attempt deadline still expires the attempt under either.

**Recommendation:** Option A. It uses the remaining overall time without reopening an expired section.

**Decision details:** Grade corrections supersede immutable grading evidence; they do not reopen participant answers. Reopening would require a separate explicit new policy.

**Issues/evidence:** [#689](https://github.com/Gegcuk/QuizMaker/issues/689) D31/D32/R17; [#659](https://github.com/Gegcuk/QuizMaker/issues/659)/#660

**Your answer:** `FUT-SECTION-02: ___`

<a id="fut-section-03"></a>
### FUT-SECTION-03 — Section submission already requires explicit confirmation — no repeat answer

**Status:** DECIDED; contract details remain. No repeat owner answer requested.

**Recorded decision:** R17 already requires explicit confirmation before the idempotent section-submit operation locks answers and advances. The original option offering confirmation only sometimes would change that approved behavior.

**Required follow-through:** Keep explicit confirmation. Prepare the concrete wording/request example for review if needed; do not reopen whether confirmation exists. This does not settle the separate genuinely unresolved section-expiry or required-unanswered-item rules.

**Authoritative source:** [#689, R17](https://github.com/Gegcuk/QuizMaker/issues/689)

<a id="fut-section-04"></a>
### FUT-SECTION-04 — Which stable error should a submitted-section mutation return?

**When needed:** Before sectioned quizzes.

**Problem:** Section locking is fixed; incomplete submission and section-only timeout still lack exact outcomes.

**In plain language:** A quiz may have separately timed parts. Once a part is submitted, its answers stay locked. We must decide whether the end of one part’s timer moves the learner forward or ends the whole attempt.

**Real-life example:** A participant retries an old answer-changing request after the section has permanently locked. Should the safe contract identify a persisted-state conflict with 409 or a disallowed action with 422?

**Options:**

- **A. 409 section already submitted (recommended)** — 409 SECTION_ALREADY_SUBMITTED with participant-safe current state. It identifies a conflict with the persisted locked state without changing that state.
- **B. 422 action not allowed** — 422 ACTION_NOT_ALLOWED. Consistent if shared policy errors already use 422. Both preserve authorization/nonenumeration before returning resource state.

**Recommendation:** Option A. It identifies a conflict with the persisted locked state without changing that state.

**Decision details:** Grade corrections supersede immutable grading evidence; they do not reopen participant answers. Reopening would require a separate explicit new policy.

**Issues/evidence:** [#689](https://github.com/Gegcuk/QuizMaker/issues/689) D31/D32/R17; [#659](https://github.com/Gegcuk/QuizMaker/issues/659)/#660

**Your answer:** `FUT-SECTION-04: ___`

## Appendix A — Decisions already approved; do not answer again

The 20–21 August issue-body decisions are later than the older pending questionnaires and their recommendations. They remain the policy authority until explicitly changed. The refreshed decision issues had no newer answers in their comments.

| Subject | Approved direction | Source |
|---|---|---|
| Practice and scores | Broad typed/versioned schema; code-defined practice initially; all three practice-mode mappings at first public activation; binary v1 scoring; exact earned/possible authority; 70% pass; unknown/ungraded is not zero. | [#612](https://github.com/Gegcuk/QuizMaker/issues/612)/#675 |
| Feedback and history | Per-item feedback after immutable acceptance; optional authored hint; no within-attempt answer retry; explicit final submission; immutable published revisions/forms/results; authored stable order and approved pre-start option shuffle. | [#612](https://github.com/Gegcuk/QuizMaker/issues/612)/#687 |
| Timing and attempts | Server-owned saved deadlines; hard assignment close; no formal grace; accepted-start allowance; one active attempt per subject/context. | [#674](https://github.com/Gegcuk/QuizMaker/issues/674)/#683 |
| Formal fixture | Exactly 20 of 40, specified five-family mix, strict sequential required answers, 30 minutes, no pause/grace, one attempt, 14/20 pass, total/pass release after close. This does not settle compatibility for every existing practice family. | [#668](https://github.com/Gegcuk/QuizMaker/issues/668) |
| Guests | One-use raw-link exchange; scoped cookie/native credential; hashed storage; exact participant authority; 30/IP and 5/capability exchanges per minute; new exchanges fail closed; mixed credentials rejected. | [#676](https://github.com/Gegcuk/QuizMaker/issues/676)/#801 |
| Privacy and retention | Detailed terminal evidence 12 months; trace 90 days; guest binding expiry+30 days; accommodation end+90 days; receipts terminal+30 days. Existing feature-specific result/certificate/Learning/offline/Live defaults remain. Legal holds override cleanup with 90-day review and no automatic expiry. | [#664](https://github.com/Gegcuk/QuizMaker/issues/664) |
| Reports | At least five distinct people per public aggregate cell plus complementary suppression; scope/permission beyond quiz ownership; p95 freshness ≤60s, p99 ≤5min; ordinary non-Live leaderboard deferred. | [#679](https://github.com/Gegcuk/QuizMaker/issues/679) |
| Cutover | One controlled preproduction reset of disposable execution data only; 100% eligible target starts; fresh execution confirmation/environment/backup evidence required; retirement ≥14 days and two releases with evidence. R09 fallback conflict is EXEC-04. | [#688](https://github.com/Gegcuk/QuizMaker/issues/688) |
| AI scheduling | Global durable FIFO, configurable active-job window; MySQL work/outbox with lease/heartbeat/fencing; at most twice planned initial calls; five minutes from durable work start, preserved through recovery. | [#442](https://github.com/Gegcuk/QuizMaker/issues/442)/#443 |
| AI quality and price | Strictly greater than 80% success, confidence at least 0.70 and reject wrong-language/ungrounded/contradictory output; no quarantine UI. Deterministic content/type tariff, not measured provider usage. Lowercase language metadata, default en; current 150 MB-labelled upload ceiling is 157,286,400 bytes. | [#444](https://github.com/Gegcuk/QuizMaker/issues/444)/#455/#466 |
| Auth limiter and sessions | Shared atomic MySQL; auth mutations fail closed with 503 if unavailable; ordinary bearer requests do not newly consult limiter. Four-day rolling refresh inactivity and current access-token policy stay. | [#457](https://github.com/Gegcuk/QuizMaker/issues/457)/#793 |
| Operations | Java17, production amd64 and supported local arm64; additive Flyway forward fixes; chosen private DigitalOcean/Grafana monitoring with 14-day retention. Monitoring setup was deferred; OPS-03 asks timing only. | [#460](https://github.com/Gegcuk/QuizMaker/issues/460)/#430/#465/#779 |
| Future policy already settled | Managed preset scopes/overrides; untimed personal offline practice and replay; retake allowances; no v1 ungraded self-rating; feedback rules. Do not reopen these just because their implementation issues remain open. | [#680](https://github.com/Gegcuk/QuizMaker/issues/680)/#682/#683/#684/#687 |

Pure defects do not need a new product vote: duplicate refunds must not change totals twice; a canceled refund cannot restore a debit that never occurred; foreign private questions and disabled accounts must not bypass existing rules; malformed answers must not earn credit; accepted work must not silently disappear. Their concrete implementation still follows the repository’s separate approval rule.

## Appendix B — Exact proposed values for AUTH-01

These are proposed versioned initial values, not an external security standard. Account-wide protection matters because an attacker can change network addresses; temporary throttling also needs a deliberate availability tradeoff. See [OWASP authentication guidance](https://cheatsheetseries.owasp.org/cheatsheets/Authentication_Cheat_Sheet.html#login-throttling).

All new-operation counts below include successful and failed admitted requests over the preceding rolling window. Every applicable dimension must allow the request atomically. Success, restart, another replica or token rotation does not reset counters. Rejected requests create no account/session/email/token effect.

| New operation / independent dimension | A: larger shared network | B: tighter network |
|---|---:|---:|
| Login: canonical account, all networks | 10 / 60s | 10 / 60s |
| Login: account + trusted network | 5 / 60s | 5 / 60s |
| Login: trusted network, all accounts | 60 / 60s | 20 / 60s |
| Registration: claimed normalized email | 3 / hour | 3 / hour |
| Registration: requested username (provider+subject for first provider-created account) | 3 / hour | 3 / hour |
| Registration: network burst | 30 / 60s | 5 / 60s |
| Registration: network sustained | 60 / hour | 10 / hour |
| Refresh: verified stable session | 10 / 60s | 10 / 60s |
| Refresh: verified user across sessions/networks | 30 / 60s | 30 / 60s |
| Refresh: trusted network, including invalid-token attempts | 300 / 60s | 60 / 60s |

Login username/email aliases share a canonical-account bucket; unknown identifiers receive equivalent generic treatment. Network admission precedes expensive hashing. Refresh identity comes only from verified claims; existing single-use/replay handling remains separate, and a 429 must not consume a fresh refresh token. First provider-created accounts share the registration boundary, without treating an existing provider login as a new signup.

Preserve existing limits and their first-admitted-request 60-second window: change-password 3 per user+IP; forgot-password and resend-verification 5 per email+IP; reset-password 5 per IP; verify-email 10 per IP; OAuth exchange 30 per IP and 5 per code digest. Keep logout/revocation usable under existing rules rather than silently adding a new throttle.

Use trusted deployment proxy boundaries, hashed keys, bounded indexed cleanup and generic 429 with the ceiling of the wait until all limiting dimensions permit a request. Limiter-store failure follows the approved 503 policy with no local permissive fallback. This does not eliminate distributed denial of service or all possible account-enumeration surfaces; prove capacity, boundary races, alias handling, NAT behavior, restart durability and no effects on denial. Changes to the preserved recovery/verification limits require a separate explicit policy choice.

## Answer format

You can reply in batches. Example:

```text
L-01: A
SEC-01: A
SEC-02: B — providers: ...
OPS-02: A — operator: ...
FUT-LIVE group: DEFER
Other answers / changes: ...
```

If a group is deferred, stage 3 records the exact unresolved questions on its decision issue and keeps its activation blocked. If you approve recommendations as a group, requested names/values still need answers where specified. No unchecked or empty field is treated as a decision.
