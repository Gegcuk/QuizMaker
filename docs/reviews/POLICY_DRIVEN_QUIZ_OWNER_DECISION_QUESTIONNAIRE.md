# Policy-Driven Quiz Owner Decision Questionnaire

**Status:** Open owner decisions after the 2026-08-08 backlog remediation.

This is the canonical list of product, privacy, security, operations, and user-experience choices that still need an owner answer. It includes long-term extension decisions, not only blockers for the first release. Implementation issues must not infer an answer from a recommendation.

## How to answer

Reply with the question ID and an option, for example `A01: A`, `D03: B`, or provide a custom answer. You can answer one group at a time. An answer is complete only when any numeric limit, default, exception, and legacy behavior is explicit. The selected answer will then be copied into the owning GitHub issue and the architecture decision ledger.

Priority:

- **Phase 0:** answer the `Core` rows before the first policy-driven implementation PR except independent security hardening #690.
- **Before feature:** answer the row before its owning implementation issue starts.
- **Before activation:** implementation may be built dark, but the row must be answered before users are routed to it.
- **Future:** no current delivery is blocked, but the architecture must not silently choose a value.

## Decisions already made

These are not questions and should not be reopened accidentally:

- Existing API/frontend behavior remains supported while target activation is off. No frontend change is required to keep current quizzes working.
- Existing attempts remain explicit legacy evidence; the migration never invents revision, policy, form, subject, response, or result history.
- Published content, resolved policy, participant forms, accepted responses, and result facts are immutable/versioned.
- Identical canonical retries return the same success; the same key with different input returns conflict.
- Accepted responses are not edited in place. Any future correction uses explicit immutable supersession and audit.
- Atomic answer batches are all-or-nothing. Partial item acceptance is not the current batch contract.
- The default pass threshold is 70% per immutable revision.
- Best-result ties use faster completion, then earlier completion.
- Unreconstructable legacy results remain viewable as legacy but are excluded from comparable ranking.
- Existing Fill Gap questions without distractors and new Fill Gap questions with options/distractors both remain valid; options do not alter grading or correct-answer extraction.
- Learning schedules remain separate from assessment results. Live orchestration remains a separate bounded context.
- Client flags never grant identity, engine selection, state changes, grading, release, or protected answer disclosure.
- Automated tests use local fakes/stubs and never call real OpenAI, Stripe, email, storage, or another paid/remote provider.
- Engineering ownership is settled: #529 is the sole terminal-submit transaction; #543 owns accepted-response uniqueness; #599 owns generic command receipts; #600 owns dark start; #609 owns public activation; #603 owns automatic graded facts; #566 owns ungraded classification; #607 owns aggregate reports; #631 owns per-attempt history; #585/#587/#655/#657 own Live state/event/outbox/projector/public snapshot boundaries respectively.

## Question index

| Group | IDs | Primary owners |
|---|---|---|
| Policy and presets | A01-A05, D01-D06, D34 | #612, #680, #515-#518, #665/#708 |
| Assignment delivery | A06-A11, D38 | #672, #531-#534, #668 |
| Selection | A12-A16 | #673, #545/#604/#546/#547 |
| Timing and release | A17-A21, D31-D32, D37 | #674/#675/#689, #601/#702/#697 |
| Grading and review | A22-A26, D02-D03, D07-D09 | #612/#675, #598/#701/#529/#629 |
| Privacy and reporting | A27-A32, D35-D36 | #664/#679, #606/#607/#699 |
| Learning | A33-A36, D33 | #681/#577, #575-#650/#692 |
| Live | A37-A43 | #678, #582-#658/#671 |
| Practice feedback | D11-D13 | #687/#605 |
| Guest capabilities | D14-D15 | #676, #611/#623-#626 |
| Accommodations | D16-D17 | #677, #563-#565/#640/#641/#712 |
| Offline | D18-D20 | #682, #579-#581/#651/#652/#691 |
| Retakes | D21-D22 | #683, #548/#549 |
| Ungraded | D23-D24 | #684, #566-#568/#642-#644/#695/#709 |
| Adaptive and Branching | D25-D28 | #685/#686, #569/#571/#645/#693/#694 and #572-#574/#646/#670 |
| Migration | D10, D29-D30 | #688, #608-#610/#690/#703-#705 |

## Architecture catalogue questions

### A01. What is the exact initial policy schema?

**Owner / timing:** #612 and #515; **Phase 0**.

**Decision:** Choose the smallest set of policy fields that is authoritative in v1, including selection, delivery, navigation, timing, response, grading, release, and limits.

**User example:** Maria opens her existing 20-question biology practice quiz. The server must know whether all questions are delivered together, whether she may move back, whether there is a timer, and when answers appear without asking the old frontend for new fields.

**Options:**

- **A. Minimal fixed practice schema:** only the fields needed for one authenticated, untimed, fixed-ALL practice preset; unknown fields fail closed.
- **B. Broad formal-ready schema:** include assignments, timers, attempts, manual release, sections, and accommodations now, even if inactive.
- **C. Generic JSON rule bag:** persist arbitrary named settings interpreted by handlers.

**Recommendation:** A. Add typed/versioned fields only when a real preset consumes them; do not create a generic rules engine.

**Your answer:** _Pending._

### A02. Which presets exist in the initial inventory?

**Owner / timing:** #612/#516; **Phase 0**.

**Decision:** Choose which user-recognizable execution modes ship as supported server presets first.

**User example:** An author clicks "practice" on a current quiz. Should the server expose only the current open-practice behavior, or also advertise formal exam and self-assessment modes that are not yet complete?

**Options:**

- **A. One built-in open-practice preset only.**
- **B. Open practice plus formal timed assignment.**
- **C. Open practice, formal assessment, and ungraded self-assessment.**

**Recommendation:** A. Add B/C only after their conformance gates pass.

**Your answer:** _Pending._

### A03. Which preset fields may authors override?

**Owner / timing:** #680/#665; **Before managed presets**.

**Decision:** Decide whether authors can change preset behavior and which fields remain platform-controlled.

**User example:** A teacher selects "formal assessment" but wants 45 minutes instead of 30 and score-only review instead of answer-key review.

**Options:**

- **A. No overrides:** authors choose a preset exactly as published.
- **B. Explicit allow-list with platform bounds:** for example duration, attempt count, selection count, and release categories.
- **C. Any policy field or arbitrary JSON override.**

**Recommendation:** B. Validate and snapshot every override; never branch runtime behavior by preset name.

**Your answer:** _Pending._

### A04. Which practice settings may participants choose?

**Owner / timing:** #680/#687; **Before participant-configurable practice**.

**Decision:** Separate author policy from harmless learner preferences.

**User example:** Alex wants shuffled options and one hint in a private practice session, but the quiz author did not configure those choices.

**Options:**

- **A. No participant choices.**
- **B. Only practice-safe choices allowed by the author/preset, such as presentation shuffle, hints, or retry count within bounds.**
- **C. Any author-overridable field, including timing and release.**

**Recommendation:** B, snapshotted at start with provenance. Formal presets default to no participant choices.

**Your answer:** _Pending._

### A05. What is the policy compatibility lifecycle?

**Owner / timing:** #680/#708; **Before managed preset lifecycle**.

**Decision:** Define what happens when a policy/preset version is replaced, deprecated, or no longer supported.

**User example:** Preset v1 allowed immediate answers. Preset v2 hides answers until completion, while 50 active attempts still use v1.

**Options:**

- **A. Immutable publish, supersede, deprecate, and retire only when no protected use remains.**
- **B. Mutate the existing preset and let active attempts read the new value.**
- **C. Keep every version forever with no retirement state.**

**Recommendation:** A. Existing snapshots remain executable/readable; unknown runtime versions fail closed.

**Your answer:** _Pending._

### A06. What is the formal assignment audience and roster model?

**Owner / timing:** #672; **Before assignment implementation**.

**Decision:** Define who can start an assignment and how membership is represented.

**User example:** A teacher assigns a quiz to Class 7B, adds one late student, and shares a guest link with an external examiner.

**Options:**

- **A. Explicit participant subjects only.**
- **B. Versioned groups/rosters expanded to immutable assignment membership, plus separately scoped guest capabilities.**
- **C. Anyone with a public link; roster is reporting-only.**

**Recommendation:** B. Start authorization must resolve immutable audience evidence, not trust a client user/group ID.

**Your answer:** _Pending._

### A07. May an active assignment attempt continue after the assignment deadline?

**Owner / timing:** #672/#674; **Before formal activation**.

**Decision:** Separate the deadline for new starts from the deadline/effective duration of an already-started attempt.

**User example:** Sam starts a 60-minute exam at 15:45; the assignment closes at 16:00. At 16:05, should Sam continue?

**Options:**

- **A. Assignment deadline ends all attempts immediately.**
- **B. No new starts after the deadline; an active attempt continues only until its snapshotted effective deadline.**
- **C. Every started attempt always receives its full duration, even beyond assignment close.**

**Recommendation:** B, with A/C available only as explicit versioned policy if later needed.

**Your answer:** _Pending._

### A08. What happens to active attempts after assignment revocation?

**Owner / timing:** #672; **Before assignment activation**.

**Decision:** Define revocation versus historical start authority and any emergency cancellation.

**User example:** A teacher revokes an assignment after noticing a wrong answer while three students are already answering.

**Options:**

- **A. Revoke future starts only; active attempts continue under their snapshots.**
- **B. Cancel active attempts immediately.**
- **C. Default to A, with a separately authorized audited emergency-cancel command.**

**Recommendation:** C. Never reinterpret active attempts silently.

**Your answer:** _Pending._

### A09. Are participant-count limits hard, soft, or absent?

**Owner / timing:** #672/#683; **Before capacity-limited assignments**.

**Decision:** Define whether an assignment has a maximum number of unique participants/starts and how the last seat is claimed.

**User example:** An instructor sold 30 seats; participants 30 and 31 click Start at the same time.

**Options:**

- **A. No capacity limit in v1.**
- **B. Hard atomic limit; one last-seat winner, typed denial for the loser.**
- **C. Soft limit that warns the owner but lets starts continue.**

**Recommendation:** B when configured; absent by default for current user-owned practice.

**Your answer:** _Pending._

### A10. What guest name and email information is required?

**Owner / timing:** #672/#676; **Before guest assignment activation**.

**Decision:** Minimize identity while still supporting the intended roster/reporting experience.

**User example:** A guest opens a school quiz link. The teacher wants to identify the submission, but the guest does not want to create an account.

**Options:**

- **A. Optional display name; no email.**
- **B. Required display name; optional email.**
- **C. Required verified email and name.**

**Recommendation:** A for open guest practice; B/C only for an explicit roster policy. Store no email by default.

**Your answer:** _Pending._

### A11. Do participants receive the same form or individualized forms?

**Owner / timing:** #672/#673; **Before randomized assignment activation**.

**Decision:** Define the author-visible choice and what "equivalent" means for reporting.

**User example:** Thirty students take the same exam. The teacher wants cheating resistance but also comparable results.

**Options:**

- **A. One shared materialized form for the assignment.**
- **B. One deterministic individualized form per participant/attempt.**
- **C. Policy-selectable A or B, with exact strategy/config/equivalence metadata.**

**Recommendation:** C. The selected form and order are persisted; reports never assume identical questions without equivalence evidence.

**Your answer:** _Pending._

### A12. What random source and reproducibility evidence are authoritative?

**Owner / timing:** #673; **Before random selection**.

**Decision:** Choose server randomness and evidence sufficient to reproduce the delivered form.

**User example:** A student disputes receiving a harder form. An operator must reproduce exactly which questions/options were delivered without trusting the client.

**Options:**

- **A. Server cryptographic randomness plus persist only the final selected IDs/order.**
- **B. Server keyed deterministic seed plus persist seed version and final IDs/order.**
- **C. Client-provided seed/order.**

**Recommendation:** B with final IDs/order as the primary evidence; the seed alone is never sufficient.

**Your answer:** _Pending._

### A13. How are percentage quotas rounded?

**Owner / timing:** #673; **Before percentage selection**.

**Decision:** Choose deterministic integer counts for category/difficulty percentages.

**User example:** A 7-question form requests 30% hard questions, which mathematically equals 2.1 questions.

**Options:**

- **A. Floor each bucket, allocate remainder by largest fractional part and stable tie order.**
- **B. Round half-up independently, then correct total by priority.**
- **C. Always ceil each non-zero bucket, even if the sum exceeds requested count.**

**Recommendation:** A. It preserves the exact total and is deterministic; define minimum-one only as a separate constraint.

**Your answer:** _Pending._

### A14. What happens when stratification cannot satisfy requested quotas?

**Owner / timing:** #673; **Before stratified selection**.

**Decision:** Define fail, partial, or fallback behavior by execution context.

**User example:** An author requests five hard algebra questions, but the published revision contains only three.

**Options:**

- **A. Fail publication/start for every context.**
- **B. Fill shortages from other buckets silently.**
- **C. Formal presets fail; practice may return an explicitly marked partial/fallback form only if policy allows it.**

**Recommendation:** C. Never silently weaken a formal form.

**Your answer:** _Pending._

### A15. What is the versioned difficulty taxonomy?

**Owner / timing:** #673; **Before difficulty stratification**.

**Decision:** Define stable values and migration when difficulty labels evolve.

**User example:** Existing questions use EASY/MEDIUM/HARD; a future author wants "expert" or a numeric 1-10 scale.

**Options:**

- **A. Version the existing enum and add new taxonomy versions later.**
- **B. Replace it now with a numeric scale.**
- **C. Treat arbitrary tags as difficulty.**

**Recommendation:** A. Published revisions bind the taxonomy version; never reinterpret old labels.

**Your answer:** _Pending._

### A16. How are individualized forms reported as equivalent?

**Owner / timing:** #673/#679; **Before equivalent-form reports**.

**Decision:** Define when results may share a comparison cohort.

**User example:** Lee receives questions A/B/C and Noor receives D/E/F, both selected by the same blueprint. The teacher asks for one class ranking.

**Options:**

- **A. Pool every form from the same assignment.**
- **B. Keep every exact form separate.**
- **C. Pool only forms with the same versioned strategy/config/quota/equivalence group and expose the form dimension.**

**Recommendation:** C; require enough evidence to audit comparability.

**Your answer:** _Pending._

### A17. Which deadline wins when assignment close and attempt duration differ?

**Owner / timing:** #674/#601; **Before timed formal execution**.

**Decision:** Define the authoritative effective deadline from assignment availability, start time, duration, section deadline, and accommodation.

**User example:** Priya starts a 45-minute test at 14:30; the assignment closes at 15:00, and her accommodation adds 15 minutes.

**Options:**

- **A. Earliest applicable persisted deadline wins.**
- **B. Started-attempt duration always wins.**
- **C. Assignment deadline always wins, ignoring duration/accommodation beyond it.**

**Recommendation:** A, with explicit policy precedence and the final instant snapshotted at start.

**Your answer:** _Pending._

### A18. What grace period exists after a deadline?

**Owner / timing:** #674; **Before timed activation**.

**Decision:** Choose whether late commands are accepted and whether grace affects submission, new answers, or transport only.

**User example:** A student's final submit reaches the server two seconds after the displayed deadline because of network latency.

**Options:**

- **A. Zero grace; server receipt time is final.**
- **B. One fixed platform grace window for final submit only.**
- **C. Versioned bounded policy grace, with exact allowed commands.**

**Recommendation:** A for v1 formal execution. Add C only with persisted bounds and race fixtures; never trust client timestamps.

**Your answer:** _Pending._

### A19. What happens to drafts when an attempt expires?

**Owner / timing:** #674/#628; **Before drafts plus timing**.

**Decision:** Decide whether unaccepted draft text becomes an answer, remains private evidence, or is removed.

**User example:** Jamie typed an essay draft but never clicked Save/Submit before time expired.

**Options:**

- **A. Automatically submit the draft.**
- **B. Preserve it temporarily as private, non-scoring draft evidence; do not submit it.**
- **C. Delete it immediately on expiry.**

**Recommendation:** B, followed by #664-governed cleanup. Automatic submission changes user intent.

**Your answer:** _Pending._

### A20. How does pause affect each timer type?

**Owner / timing:** #674/#602; **Before pause support**.

**Decision:** Define whether pause shifts attempt duration, section timers, assignment close, and scheduled release.

**User example:** A teacher pauses an exam for a 10-minute fire alarm while the assignment still closes at 16:00.

**Options:**

- **A. No pause in formal v1.**
- **B. Pause shifts attempt/section effective time but never assignment availability or scheduled release.**
- **C. Pause shifts every deadline.**

**Recommendation:** A initially; B when pause ships. Persist a pause ledger and calculate effective time, not mutable deadlines.

**Your answer:** _Pending._

### A21. How are missed deadlines recovered after server restart?

**Owner / timing:** #674/#702; **Before timed activation**.

**Decision:** Choose worker, request-time, and reconciliation responsibility.

**User example:** The backend is down from 14:59 to 15:04 while 100 attempts become due at 15:00.

**Options:**

- **A. Lazy enforcement only when a participant next calls the API.**
- **B. Background due worker only.**
- **C. Persisted due worker plus request-time allowed-action checks and reconciliation.**

**Recommendation:** C. Both paths call the same idempotent expiry command.

**Your answer:** _Pending._

### A22. Which v1 question types use partial credit?

**Owner / timing:** #612/#675/#701; **Phase 0 for selected families**.

**Decision:** Name every selected question family and its earned/possible units, denominator, and rounding.

**User example:** A multi-select question has four correct choices; a learner selects three correct and one wrong. A matching question has eight pairs with six correct.

**Options:**

- **A. Preserve current binary all-or-nothing grading for every v1 family.**
- **B. Per-component partial credit for MCQ_MULTI, MATCHING, ORDERING, and multi-gap questions, with explicit wrong-choice penalties.**
- **C. Author-selectable binary/partial behavior per question.**

**Recommendation:** A for the first compatible release; introduce B later as a new scoring-contract version, never by changing old algorithms.

**Your answer:** _Pending._

### A23. What is the grade-correction model?

**Owner / timing:** #675/#638/#639; **Before correction support**.

**Decision:** Define who may correct, what precondition/version is required, and how results/certificates/reports change.

**User example:** A grader awarded 4/10 instead of 8/10 and corrects it after the participant already viewed the result.

**Options:**

- **A. Edit the existing grade/result row in place.**
- **B. Append an immutable correction decision, superseding result, and one correction event.**
- **C. Require a new participant attempt.**

**Recommendation:** B, with stale-version conflict and immutable history.

**Your answer:** _Pending._

### A24. Who may perform manual result release?

**Owner / timing:** #675/#697/#633; **Before manual release**.

**Decision:** Choose the permission and assignment/organization ownership boundary.

**User example:** A teaching assistant finishes grading. Can they release results, or must the quiz owner/teacher do it?

**Options:**

- **A. Quiz owner only.**
- **B. Any assigned grader.**
- **C. Dedicated release permission within exact assignment/organization scope; grader and releaser may differ.**

**Recommendation:** C. Record the actor, purpose, version, and audit evidence.

**Your answer:** _Pending._

### A25. How long is released review available?

**Owner / timing:** #675/#555/#556; **Before formal review activation**.

**Decision:** Define opening, closing, corrections, and participant cache behavior for review.

**User example:** A student returns two months later to inspect explanations after a teacher corrected one grade.

**Options:**

- **A. Available indefinitely while the account/result is retained.**
- **B. Fixed platform window, such as 30 days after release.**
- **C. Versioned policy window; open practice defaults to retained availability, formal assignments choose a bounded window.**

**Recommendation:** C. The server rechecks release/revocation; cached client data is non-authoritative.

**Your answer:** _Pending._

### A26. What happens to certificates after result correction or invalidation?

**Owner / timing:** #675/#635; **Before certificates with corrections**.

**Decision:** Define whether a certificate is revoked, superseded, or remains valid.

**User example:** A correction lowers a participant from 72% to 68%, below the 70% pass threshold, after a certificate was issued.

**Options:**

- **A. Leave the certificate valid forever.**
- **B. Mark the old certificate revoked and issue a replacement only if the corrected result remains eligible.**
- **C. Delete the old certificate record.**

**Recommendation:** B. Public verification shows current status without exposing private correction details; history remains audited.

**Your answer:** _Pending._

### A27. How long are attempt and participant-form records retained?

**Owner / timing:** #664/#699; **Before cleanup or retirement**.

**Decision:** Choose retention by purpose and context, including active, completed, abandoned, legacy, and certificate-linked records.

**User example:** A user deletes an account 18 months after completing a practice quiz; a school assignment may need an audit trail longer than the detailed form.

**Options:**

- **A. Keep detailed attempts/forms until account deletion.**
- **B. Keep detailed attempts/forms for a fixed period, such as 12 months after terminal state, then delete/anonymize; retain minimal credential/result facts only when justified.**
- **C. Let every author choose arbitrary retention.**

**Recommendation:** B as an initial privacy-minimizing rule, configurable later only within platform/legal bounds. Legal review is still required before production deletion.

**Your answer:** _Pending._

### A28. How long are accepted answers retained?

**Owner / timing:** #664/#699; **Before answer cleanup**.

**Decision:** Answers are more sensitive than aggregate results; choose their retention and erasure/anonymization relationship.

**User example:** A participant wants the score history kept but asks for free-text answers to be removed after the review period.

**Options:**

- **A. Same retention as detailed attempt/form.**
- **B. Shorter than attempts, with score/provenance retained after answer deletion/anonymization where reproducibility permits.**
- **C. Keep answers indefinitely.**

**Recommendation:** A for the first coherent matrix unless reproducibility can be preserved under B; free-text and manual-grading evidence may need a shorter explicit class.

**Your answer:** _Pending._

### A29. How long is execution trace evidence retained?

**Owner / timing:** #664/#538/#698; **Before trace cleanup/operator APIs**.

**Decision:** Balance dispute/security debugging against participant privacy and storage.

**User example:** An operator investigates a submit-versus-expiry dispute 45 days after the attempt.

**Options:**

- **A. 30 days after terminal state.**
- **B. 90 days after terminal state, longer only under legal/security hold.**
- **C. Same as full attempt history indefinitely.**

**Recommendation:** B, retaining bounded event facts rather than raw answers/content.

**Your answer:** _Pending._

### A30. How long is guest identity/capability evidence retained?

**Owner / timing:** #664/#676; **Before guest activation/cleanup**.

**Decision:** Separate display identity, capability hash, use/revocation evidence, and security audit.

**User example:** A guest assignment expires today; the owner reports a stolen link two weeks later.

**Options:**

- **A. Delete all guest evidence at expiry.**
- **B. Retain minimal display/binding until expiry plus 30 days and security use/revocation evidence for about 90 days; never retain raw capability.**
- **C. Retain guest identity like a registered account.**

**Recommendation:** B, subject to the final privacy/legal matrix.

**Your answer:** _Pending._

### A31. How long is accommodation metadata retained?

**Owner / timing:** #664/#677; **Before accommodation activation**.

**Decision:** Define retention for operational grant values, history, audit, and any rationale.

**User example:** Extra time was granted for one assignment; the participant later asks what was applied, but private medical details should not be stored.

**Options:**

- **A. Keep grant/effective value through assignment plus a short audit window, such as 90 days; store no medical rationale.**
- **B. Keep for the account lifetime.**
- **C. Delete immediately after attempt completion.**

**Recommendation:** A. Store only operational reason codes if necessary, never medical narrative in v1.

**Your answer:** _Pending._

### A32. What public aggregate privacy threshold and suppression rule apply?

**Owner / timing:** #679; **Before cohort reports**.

**Decision:** Choose the minimum cohort and whether small cells are suppressed or the whole request is denied.

**User example:** A public report filters a class down to three participants, making individual scores easy to infer.

**Options:**

- **A. Minimum 5; suppress affected cells and return an explicit insufficient-cohort marker.**
- **B. Minimum 10; suppress affected cells.**
- **C. Configurable threshold with a platform-enforced minimum.**

**Recommendation:** A for initial usefulness, evolving to C if organizations need stricter rules. Never return exact values below the minimum.

**Your answer:** _Pending._

### A33. Which spaced-repetition strategy is Learning v1?

**Owner / timing:** #681/#577; **Before Learning implementation**.

**Decision:** Choose the first schedule algorithm and rating vocabulary, with a version that preserves old evidence.

**User example:** A learner rates a card "Hard" today. After a deployment next week, the due date must not change because the backend silently swapped algorithms.

**Options:**

- **A. Version and preserve the current SM-2-style behavior.**
- **B. Introduce FSRS immediately with calibrated parameters.**
- **C. Use simple fixed intervals first.**

**Recommendation:** A for compatibility; add FSRS later as a new strategy/version with explicit migration.

**Your answer:** _Pending._

### A34. What happens to Learning progress when a question revision changes?

**Owner / timing:** #681/#649; **Before Learning migration**.

**Decision:** Define session stability and whether schedule/mastery transfers to edited content.

**User example:** An author fixes a wrong answer while a learner has reviewed the old question ten times and has an active session.

**Options:**

- **A. Active sessions finish on their bound revision; a new revision creates new Learning state unless an explicit lineage migration is approved.**
- **B. Automatically copy all schedule/mastery to the new revision.**
- **C. Reset the learner's entire quiz progress.**

**Recommendation:** A. Any transfer must be a versioned auditable migration based on stable lineage, never title/text matching.

**Your answer:** _Pending._

### A35. What does Learning mastery mean?

**Owner / timing:** #681; **Before Learning reports/completion**.

**Decision:** Separate mastery from assessment pass/fail and choose its evidence grain.

**User example:** A learner repeatedly recalls 18 of 20 items, while two items remain overdue. Is the quiz "mastered"?

**Options:**

- **A. Per stable Learning item from versioned review evidence; quiz mastery is a derived summary.**
- **B. One quiz percentage threshold.**
- **C. Reuse the latest assessment score.**

**Recommendation:** A. Do not store mastery in assessment result facts.

**Your answer:** _Pending._

### A36. Is Learning state shared across quizzes?

**Owner / timing:** #681; **Before cross-quiz Learning**.

**Decision:** Define whether identical/similar questions share schedule/mastery.

**User example:** The same question appears in a teacher's Biology Basics and Exam Revision quizzes. A learner masters it in one quiz.

**Options:**

- **A. Quiz-local Learning state in v1.**
- **B. Share only through exact stable content identity/lineage.**
- **C. Semantically deduplicate similar text across quizzes.**

**Recommendation:** A initially; B can be added with explicit identity. C is out of scope without a separate privacy/content model.

**Your answer:** _Pending._

### A37. What Live transport is supported?

**Owner / timing:** #678/#587/#657; **Before Live activation**.

**Decision:** Choose the real-time delivery channel and mandatory HTTP recovery path.

**User example:** A participant's socket disconnects during a round and reconnects after three events were published.

**Options:**

- **A. WebSocket or SSE adapter over a project-owned outbox, plus authenticated HTTP snapshot/resume.**
- **B. Polling only.**
- **C. Vendor-specific socket API as domain authority.**

**Recommendation:** A. Domain commits never depend on transport success.

**Your answer:** _Pending._

### A38. What is the Live session capacity rule?

**Owner / timing:** #678/#583; **Before Live admission**.

**Decision:** Choose hard versus advisory limits and last-seat behavior.

**User example:** A 100-seat session has 99 members and five people join concurrently.

**Options:**

- **A. Hard atomic capacity; one last-seat winner.**
- **B. Soft warning, allow over-capacity.**
- **C. Unlimited in v1.**

**Recommendation:** A with a bounded configured maximum and idempotent membership retry.

**Your answer:** _Pending._

### A39. How are participants admitted to Live sessions?

**Owner / timing:** #678/#583; **Before Live admission**.

**Decision:** Define join capability, explicit roster, public lobby, and host approval.

**User example:** A host starts a private classroom session; one outsider guesses the short join code.

**Options:**

- **A. Scoped expiring join capability/code with rate limits and optional roster check.**
- **B. Explicit roster only.**
- **C. Public lobby with no capability.**

**Recommendation:** A, with B available by policy. A short display code is exchanged for scoped authority and is never logged as a bearer secret.

**Your answer:** _Pending._

### A40. What identity appears in Live rosters?

**Owner / timing:** #678/#654; **Before Live roster API**.

**Decision:** Choose real name, display name, pseudonym, and host versus participant fields.

**User example:** A public revision game has minors participating; the host needs moderation controls, but players should not see account emails or internal IDs.

**Options:**

- **A. Server-approved display name/pseudonym; host gets bounded operational status, participants get phase/policy-redacted identity.**
- **B. Full account name/email for everyone.**
- **C. No names, anonymous numeric positions only.**

**Recommendation:** A. Never expose capabilities, account IDs, email, or audit data.

**Your answer:** _Pending._

### A41. When is team membership visible?

**Owner / timing:** #678/#584/#654; **Before team mode**.

**Decision:** Define teammate/opponent visibility by phase and whether teams are hidden before lock.

**User example:** Revealing teams before the round could let participants coordinate or leave/rejoin for a better team.

**Options:**

- **A. Teammates visible after team lock; opponents only if the phase policy allows.**
- **B. All teams visible from lobby entry.**
- **C. Teams hidden until final results.**

**Recommendation:** A. Individual mode must remain complete without team records.

**Your answer:** _Pending._

### A42. How does Live reconnection recover membership?

**Owner / timing:** #678/#653/#657; **Before Live reconnect**.

**Decision:** Choose whether reconnect creates a new member or resumes the old one.

**User example:** A phone loses connectivity for 20 seconds and reconnects while the same round is open.

**Options:**

- **A. Resume the same persisted membership through a scoped credential and cursor/snapshot.**
- **B. Create a new membership each time.**
- **C. Require host approval after every disconnect.**

**Recommendation:** A. Removal/revocation still wins and prevents resume.

**Your answer:** _Pending._

### A43. When is the Live leaderboard released?

**Owner / timing:** #678/#658; **Before Live standings**.

**Decision:** Choose continuous, round-end, session-end, or host-controlled visibility and tie behavior.

**User example:** Showing scores during a question can reveal who already answered and influence strategy.

**Options:**

- **A. Hidden during response; release at approved round/session phase.**
- **B. Continuously visible.**
- **C. Host manually chooses at any moment.**

**Recommendation:** A, with deterministic score then approved speed/earlier tie rules and server-owned release.

**Your answer:** _Pending._

## Delivery decisions discovered by the re-review

### D01. What navigation mode does the first open-practice preset use?

**Owner / timing:** #612/#523; **Phase 0**.

**Decision:** Choose free navigation versus sequential/forward-only state for the first compatible preset.

**User example:** A learner opens question 10, returns to question 2, and checks unanswered questions before submitting.

**Options:**

- **A. FREE navigation over an ALL_AT_ONCE form.**
- **B. SEQUENTIAL with forward/back controls.**
- **C. Forward-only.**

**Recommendation:** A. It most closely preserves current practice and keeps the first command model small.

**Your answer:** _Pending._

### D02. What score representation, precision, and rounding are authoritative?

**Owner / timing:** #612/#603; **Phase 0**.

**Decision:** Choose persisted raw values, normalized scale, display derivation, and threshold comparison.

**User example:** A learner earns 2 of 3 points (66.666...%). The server, API, report, and certificate must agree on whether a threshold was met.

**Options:**

- **A. Exact fixed-scale raw earned/possible plus normalized integer basis points 0-10000; display percentage is derived.**
- **B. BigDecimal 0-1 with one fixed scale/rounding.**
- **C. Double/float percentage.**

**Recommendation:** A. Define threshold comparison on exact raw/normalized authority, not rendered text.

**Your answer:** _Pending._

### D03. Which categories are released immediately in user-owned open practice?

**Owner / timing:** #612/#555/#556; **Phase 0**.

**Decision:** Choose score, per-item correctness, correct answer, explanation, and review availability after finalization.

**User example:** After submitting a private vocabulary quiz, a learner expects to see mistakes and learn the right answers immediately.

**Options:**

- **A. Score, per-item correctness, correct answer, explanation, and review after committed finalization.**
- **B. Score and correctness only.**
- **C. Score only.**

**Recommendation:** A for user-owned open practice; nothing is released before terminal finalization. Formal modes decide separately.

**Your answer:** _Pending._

### D04. What action creates an immutable published quiz revision?

**Owner / timing:** #612/#700; **Phase 0**.

**Decision:** Choose explicit publication versus automatic publication on edit/start.

**User example:** An author fixes a typo while ten learners have active attempts. Does saving the edit instantly create a new executable version?

**Options:**

- **A. Explicit Publish action creates a revision; edits remain mutable drafts.**
- **B. Every save automatically publishes.**
- **C. First participant start snapshots and publishes whatever is current.**

**Recommendation:** A. Current legacy authoring remains compatible through an adapter until frontend publication UX is introduced.

**Your answer:** _Pending._

### D05. Does a participant form store references or duplicate full question content?

**Owner / timing:** #612/#519/#522; **Phase 0**.

**Decision:** Define the immutable evidence needed for exact replay without unnecessary duplication.

**User example:** A quiz has 500 long questions; 10,000 attempts would make full per-attempt copies expensive, but later author edits must not change delivered content.

**Options:**

- **A. Persist revision item/option IDs and exact order, resolving immutable revision content.**
- **B. Copy the full question/options into every form.**
- **C. Persist only a random seed.**

**Recommendation:** A. Copy only fields whose external dependency or deletion risk cannot be satisfied by immutable revision ownership; seed alone is insufficient.

**Your answer:** _Pending._

### D06. Is the first built-in preset code-backed or database-managed?

**Owner / timing:** #612/#516; **Phase 0**.

**Decision:** Choose operational ownership before managed preset authoring exists.

**User example:** A deployment changes the built-in preset. Existing snapshots must retain old meaning, and a missing database seed must not break all starts.

**Options:**

- **A. Versioned code-backed definition with canonical identity and explicit legacy mapping.**
- **B. Flyway-seeded database row edited by operators.**
- **C. Recreate it dynamically from current quiz fields at every start.**

**Recommendation:** A initially; #665 introduces immutable database-managed presets later.

**Your answer:** _Pending._

### D07. May terminal submit include a final answer payload?

**Owner / timing:** #612/#529/#629; **Phase 0**.

**Decision:** Define whether answers must already be accepted before completion or submit can atomically accept final responses.

**User example:** The user edits the last answer and immediately clicks Finish; the frontend sends either two commands or one combined payload.

**Options:**

- **A. Core submit contains no answers; all responses must be accepted first.**
- **B. Submit may include one final response atomically.**
- **C. Submit always contains the complete answer batch.**

**Recommendation:** A for the first path. #629 provides a separate atomic batch; combining answer acceptance and terminal transition materially widens #529.

**Your answer:** _Pending._

### D08. How long are command idempotency receipts retained?

**Owner / timing:** #612/#599/#664; **Before command persistence**.

**Decision:** Choose retry horizon separately from permanent domain uniqueness.

**User example:** A mobile client retries a submit after 24 hours because it never received the response; the attempt is already terminal.

**Options:**

- **A. Until terminal state plus 30 days; permanent natural/domain uniqueness remains elsewhere.**
- **B. 24 hours only.**
- **C. Forever with complete response bodies.**

**Recommendation:** A, storing only bounded acknowledgement/outcome references. Exact values can vary by operation but must be versioned/configured.

**Your answer:** _Pending._

### D09. What are the answer-batch bounds and error contract?

**Owner / timing:** #629; **Before batch implementation**.

**Decision:** Define maximum items/bytes, duplicate item handling, status code, and deterministic error ordering.

**User example:** A 300-question quiz submits all answers; one form item appears twice and another payload is malformed.

**Options:**

- **A. Bounded full-form batch (for example max 500 items plus payload-byte limit), reject duplicates, all-or-none, stable per-item errors.**
- **B. Maximum 100 items, requiring multiple non-atomic batches.**
- **C. Unlimited payload.**

**Recommendation:** A, with the exact cap derived from product quiz limits and request-size policy before coding.

**Your answer:** _Pending._

### D10. Which engine owns existing routes during rollout and rollback?

**Owner / timing:** #612/#688/#609; **Phase 0 and before activation**.

**Decision:** Define routing for current starts, active target attempts, and legacy historical attempts.

**User example:** The new engine is disabled after creating 20 target attempts. Existing frontend clients continue calling the same endpoints.

**Options:**

- **A. Activation off keeps existing routes/writers authoritative; eligible new starts route to target only when enabled; created target attempts remain on target-compatible forward-fix/read adapters.**
- **B. Roll active target attempts back into legacy tables.**
- **C. Let the client choose the engine.**

**Recommendation:** A. Never convert active target evidence into invented legacy state.

**Your answer:** _Pending._

### D11. Are hints available and do they reduce score?

**Owner / timing:** #687/#605; **Before practice feedback**.

**Decision:** Define hint source, timing, disclosure, and any penalty.

**User example:** A learner requests a clue before answering a private practice question.

**Options:**

- **A. Policy-authored hints in practice, zero default penalty, recorded as Learning/practice evidence.**
- **B. Hints reduce the question score by a fixed versioned amount.**
- **C. No hints.**

**Recommendation:** A for practice; formal presets disable hints unless explicitly designed and conformance-tested.

**Your answer:** _Pending._

### D12. How many practice retries are allowed and when is a response locked?

**Owner / timing:** #687/#605; **Before practice retries**.

**Decision:** Reconcile retries with immutable accepted responses.

**User example:** A learner answers incorrectly, sees feedback, and tries again twice.

**Options:**

- **A. One retry after feedback; every response attempt is immutable evidence.**
- **B. Bounded policy count, including unlimited for private practice; every response attempt remains immutable.**
- **C. Edit the original accepted response in place.**

**Recommendation:** B with a conservative default such as one retry; never C.

**Your answer:** _Pending._

### D13. When is practice feedback shown and which retry evidence is retained?

**Owner / timing:** #687/#605; **Before practice feedback**.

**Decision:** Choose immediate correctness/explanation versus end-of-session release and evidence visibility.

**User example:** Showing the correct answer after the first attempt makes a second attempt useful for recall but not independent assessment.

**Options:**

- **A. Immediate correctness and explanation; later retries are explicitly marked post-feedback practice evidence.**
- **B. Immediate correctness only; explanation after final retry/session.**
- **C. All feedback at terminal completion.**

**Recommendation:** B as a balanced default, with category/timing versioned in policy.

**Your answer:** _Pending._

### D14. How are guest capabilities transported and reused?

**Owner / timing:** #676/#611; **Before guest capability implementation**.

**Decision:** Define browser/native transport, one-time exchange, reuse scope, and lifetime.

**User example:** A guest follows an emailed link; browser history, proxy logs, and screenshots must not expose a long-lived bearer token.

**Options:**

- **A. One-time path/query exchange into Secure HttpOnly SameSite cookie for web and a dedicated secure header/keychain value for native; reusable only for exact scoped operations until expiry/revocation.**
- **B. Long-lived bearer token in every URL.**
- **C. One new token for every command.**

**Recommendation:** A. Persist only a hash/fingerprint and never log raw material.

**Your answer:** _Pending._

### D15. What wins in capability revoke/use races and mixed credentials?

**Owner / timing:** #676/#611/#626; **Before guest activation**.

**Decision:** Define one transactional winner and behavior when authenticated identity and capability are both supplied.

**User example:** The owner revokes a guest link while the guest submits; another request sends Alice's login token with Bob's guest capability.

**Options:**

- **A. Lock/version determines one revoke/use winner; reject mixed credentials unless both resolve to the exact same approved subject/context.**
- **B. Use always wins over concurrent revoke; authenticated identity overrides any capability.**
- **C. Capability always overrides account identity.**

**Recommendation:** A, defaulting to reject ambiguous mixed credentials and returning non-enumerating errors.

**Your answer:** _Pending._

### D16. Who may grant accommodations and what rationale is stored?

**Owner / timing:** #677/#563/#698; **Before accommodations**.

**Decision:** Define authority, scope, allowed fields, reason data, and audit purpose.

**User example:** A support manager gives one student 25% extra time for a school assignment. The quiz author should not see private medical information.

**Options:**

- **A. Dedicated accommodation permission in exact organization/assignment scope; allow-listed operational fields; store an operational reason code only.**
- **B. Any quiz owner may add arbitrary policy overrides and free-text medical notes.**
- **C. Platform administrator only.**

**Recommendation:** A. Do not build a medical-record system.

**Your answer:** _Pending._

### D17. How do accommodation revocation and participant/operator views work?

**Owner / timing:** #677/#565/#640/#641/#712; **Before accommodation activation**.

**Decision:** Define revoke/start precedence, active-attempt effect, and visible fields.

**User example:** Extra time is revoked one minute after the participant starts; the participant opens the timer while an operator audits the grant.

**Options:**

- **A. Revocation blocks future starts; active attempt keeps its snapshot unless an audited emergency cancel exists. Participant sees effective behavior only; authorized operator sees redacted grant/audit fields.**
- **B. Revocation immediately recomputes every active deadline.**
- **C. Participant sees the full grant and rationale.**

**Recommendation:** A.

**Your answer:** _Pending._

### D18. Which attempts are eligible offline and are packages device-bound?

**Owner / timing:** #682/#579; **Before Offline implementation**.

**Decision:** Choose supported policy features and package binding.

**User example:** A learner downloads a quiz on a tablet, then tries to open the package on a laptop; the quiz has a timer and manual grading.

**Options:**

- **A. Fixed-form non-Live attempts only; bind authority to subject+attempt, with optional registered-device binding for higher-risk formal policies.**
- **B. Untimed user-owned practice only, no device binding.**
- **C. Every mode, transferable package.**

**Recommendation:** Start with B or the narrow part of A. Explicitly exclude Adaptive, Branching, Live, and unsupported release/manual workflows.

**Your answer:** _Pending._

### D19. What is the offline package lifetime and key-rotation behavior?

**Owner / timing:** #682/#580; **Before package issuance**.

**Decision:** Define absolute expiry, deadline cap, encryption/authentication, key version, and revoked-key recovery.

**User example:** A learner downloads a package seven days before an assignment and stays offline past the assignment deadline while the server rotates keys.

**Options:**

- **A. Short absolute lifetime capped by assignment/attempt deadline, authenticated encryption with key version, old decryption keys retained only for the approved package window.**
- **B. Package never expires once downloaded.**
- **C. Package is plaintext and relies on app storage.**

**Recommendation:** A; choose the numeric maximum before #580 starts (for example seven days for practice, shorter for formal).

**Your answer:** _Pending._

### D20. How does offline replay handle conflicts, expiry, and late reconnect?

**Owner / timing:** #682/#581/#652; **Before Offline replay**.

**Decision:** Choose ordered commands, atomic final batch behavior, and whether client creation time can overcome server receipt after expiry.

**User example:** A device records three answers before the deadline but reconnects after it; one answer conflicts with a command already accepted online.

**Options:**

- **A. Replay canonical commands in order with per-command receipts; identical retry succeeds, conflicts stop/return resync, and server authority/deadline at replay wins unless a separately signed offline window says otherwise.**
- **B. Trust client timestamps and accept all commands created before deadline.**
- **C. Best-effort partial acceptance without stable receipts.**

**Recommendation:** A. A future signed offline-deadline rule can be versioned, but unsynchronized client time is not authority.

**Your answer:** _Pending._

### D21. Which attempt states consume retake allowance and when does cooldown start?

**Owner / timing:** #683/#548/#549; **Before retake limits**.

**Decision:** Define committed start, completed, expired, abandoned, cancelled, and technical failure.

**User example:** A user starts an exam, closes the browser immediately, later expires, and retries after a backend error on another start.

**Options:**

- **A. Every successfully committed participant start consumes allowance except a server-classified technical start failure; cooldown begins at terminal completion/expiry/abandon.**
- **B. Only completed attempts consume allowance.**
- **C. Every request, including failed starts, consumes allowance.**

**Recommendation:** A, with explicit admin adjustment rather than deleting attempts.

**Your answer:** _Pending._

### D22. Are simultaneous active attempts allowed and how are limits reset?

**Owner / timing:** #683/#549; **Before retake concurrency**.

**Decision:** Choose active-attempt uniqueness and owner/admin reset semantics.

**User example:** The same participant double-clicks Start on two devices; later a teacher grants one extra attempt after a technical incident.

**Options:**

- **A. One active attempt per subject+assignment/publication; reset is an immutable audited allowance adjustment.**
- **B. Multiple active attempts up to remaining allowance; reset deletes old attempts.**
- **C. Preset-configurable concurrency from the first release.**

**Recommendation:** A. Add configurable concurrency only with a real use case.

**Your answer:** _Pending._

### D23. Are ungraded responses stored and what feedback is shown?

**Owner / timing:** #684/#566/#567; **Before Ungraded implementation**.

**Decision:** Choose server evidence, self-rating, correctness/explanation, and consent language.

**User example:** A wellbeing self-assessment has no right answer, while a revision checklist may include optional explanations.

**Options:**

- **A. Store immutable non-scoring responses; feedback/explanations only where the activity explicitly defines them; no score/pass.**
- **B. Store completion only, no responses.**
- **C. Store responses and silently calculate a score for reports.**

**Recommendation:** A for flexible self-assessment, with B available as a separate activity policy and explicit privacy notice. Never C.

**Your answer:** _Pending._

### D24. What counts as ungraded completion and what appears in reports?

**Owner / timing:** #684/#642/#644/#695; **Before Ungraded activation**.

**Decision:** Define explicit completion and participant/teacher visibility separate from assessed reports.

**User example:** A participant opens all items but does not press Finish; a teacher wants a completion count but no scores.

**Options:**

- **A. Explicit final submit after required participation; separate completion-only participant/teacher report.**
- **B. Viewing every item automatically completes.**
- **C. No server completion/report at all.**

**Recommendation:** A. Ungraded is excluded from pass, rank, certificate, and assessed denominators by #568/#643/#709.

**Your answer:** _Pending._

### D25. Which evidence drives Adaptive selection and when does it stop?

**Owner / timing:** #685/#693/#569; **Before Adaptive implementation**.

**Decision:** Choose evidence inputs, bounds, mastery/confidence rule, and privacy.

**User example:** A learner answers three medium questions correctly. Should the next item be hard, and when may the attempt end early?

**Options:**

- **A. Current-attempt accepted correctness plus versioned difficulty/tags; hard min/max item bounds and a simple mastery threshold.**
- **B. Fixed item count with adaptive order only.**
- **C. Use all historical assessment/Learning data and an AI model.**

**Recommendation:** A, starting with deterministic rules and no cross-quiz personal model.

**Your answer:** _Pending._

### D26. What happens when Adaptive has no candidate and what may participants review?

**Owner / timing:** #685/#571/#645/#694; **Before Adaptive activation**.

**Decision:** Define fallback/termination and protected decision-path disclosure.

**User example:** All eligible hard questions were already delivered; the participant requests Next and later reviews the attempt.

**Options:**

- **A. End successfully with a typed reason when minimum requirements are met; otherwise deterministic approved fallback pool or fail without partial append. Participant sees delivered path only.**
- **B. Pick any unused question silently and show the full candidate trace.**
- **C. Leave the attempt stuck.**

**Recommendation:** A; record protected operator trace with no future candidate leakage.

**Your answer:** _Pending._

### D27. What happens when no Branching condition matches or multiple conditions match?

**Owner / timing:** #686/#573/#574; **Before Branching implementation**.

**Decision:** Define publication validation, default edges, explicit terminal nodes, and condition priority.

**User example:** A response satisfies two overlapping branches, or none because an author forgot a default path.

**Options:**

- **A. Reject ambiguous overlap unless explicit priority is authored; require a default edge or explicit terminal at publication.**
- **B. First authored edge wins and no-match fails at runtime.**
- **C. Randomly choose a matching edge.**

**Recommendation:** A.

**Your answer:** _Pending._

### D28. Which Branching paths are visible after completion?

**Owner / timing:** #686/#646/#670; **Before Branching activation**.

**Decision:** Separate participant review from author/operator graph/trace access.

**User example:** Revealing an unchosen remediation path could expose future assessment content to a participant.

**Options:**

- **A. Participant sees only delivered/chosen path and released fields; authorized author/operator sees full graph plus protected execution trace.**
- **B. Participant sees the full graph after completion.**
- **C. Nobody can inspect the chosen path.**

**Recommendation:** A.

**Your answer:** _Pending._

### D29. Which users/starts enter the new engine, and what happens on rollback?

**Owner / timing:** #688/#609; **Before first activation**.

**Decision:** Choose allow-list/percentage, stable cohort key, and behavior for already-created target attempts.

**User example:** Ten percent rollout shows an integrity error after 20 target attempts have started.

**Options:**

- **A. Allow-listed preset/route plus stable subject hash percentage; stop new target starts, keep created target attempts on compatible target/forward-fix handling.**
- **B. Random choice on every request and convert active target attempts to legacy on rollback.**
- **C. Activate all users at once.**

**Recommendation:** A, starting with one allow-listed authenticated open-practice preset.

**Your answer:** _Pending._

### D30. What observation window and evidence permit legacy retirement?

**Owner / timing:** #688/#703-#705; **Before retirement**.

**Decision:** Choose duration/releases, critical thresholds, zero-use evidence, and go/no-go owner.

**User example:** No legacy writes were seen for eight days, but one old mobile client still uses a legacy read adapter weekly.

**Options:**

- **A. At least 14 days and two releases, zero critical integrity/security failures, completed reconciliation, and zero verified consumers before each boundary retires.**
- **B. Seven days and low usage is enough.**
- **C. Fixed calendar date regardless of usage.**

**Recommendation:** A. Physical deletion also requires #664, backup/restore rehearsal, and one schema/adapter family per PR.

**Your answer:** _Pending._

### D31. How does a section complete and what future section information is visible?

**Owner / timing:** #689/#696/#659; **Before section runtime**.

**Decision:** Choose explicit versus automatic completion, backtracking, and disclosure.

**User example:** A participant answers the last item in Section 1. Should Section 2 open automatically, and may they see its title/questions beforehand?

**Options:**

- **A. Explicit Submit/Continue completes the section; safe structural metadata only before activation; content hidden; backtracking follows snapshotted policy.**
- **B. Auto-complete as soon as required answers exist and reveal all future content.**
- **C. Author-selectable behavior for every section in v1.**

**Recommendation:** A initially. Expand through versioned section policy only after #711 conformance.

**Your answer:** _Pending._

### D32. How do section timers interact with the overall timer and expiry drafts?

**Owner / timing:** #689/#660; **Before section timers**.

**Decision:** Define timer start, earliest deadline, pause, and expiry effect.

**User example:** The attempt has 40 minutes left but the current section has 5 minutes; the user has an unsaved draft when the section expires.

**Options:**

- **A. Earliest persisted overall/section deadline wins; no pause in v1; preserve drafts as private non-submitted evidence.**
- **B. Section timer replaces the overall timer.**
- **C. Section timer is display-only and cannot expire work.**

**Recommendation:** A, using the same authoritative Clock/expiry command model.

**Your answer:** _Pending._

### D33. What Learning command rate limit applies?

**Owner / timing:** #681/#692; **Before Learning API activation**.

**Decision:** Choose per-user/session limits, retry window, and whether identical retries count again.

**User example:** A buggy client sends 100 Rate commands in one second; a normal learner quickly reviews 30 items.

**Options:**

- **A. Configurable per-user bounded token/window policy, for example 60 commands/minute with burst, identical idempotent retry counted once, Retry-After returned.**
- **B. No application limit.**
- **C. One global IP limit only.**

**Recommendation:** A; confirm the numeric initial rate with expected UI behavior before #692.

**Your answer:** _Pending._

### D34. Who may own and publish managed presets?

**Owner / timing:** #680/#665; **Before managed presets**.

**Decision:** Define platform, organization, and private author scopes plus visibility.

**User example:** A school administrator creates a standard exam preset for all teachers, while one teacher wants a private experimental preset.

**Options:**

- **A. Platform-managed only in the first managed release.**
- **B. Platform plus organization-managed presets with dedicated permission.**
- **C. Platform, organization, and private author presets immediately.**

**Recommendation:** B as the first useful managed model; private author presets can follow if there is demand. Every scope is owner-versioned and non-enumerating.

**Your answer:** _Pending._

### D35. What teacher/report scope and small-cohort response are allowed?

**Owner / timing:** #679/#607; **Before reports**.

**Decision:** Define who is a teacher for a result cohort, drill-down fields, and what the API returns below A32's threshold.

**User example:** A quiz owner who is not a member of the school organization requests student-level results for an assignment created by another teacher.

**Options:**

- **A. Immutable assignment/organization membership plus dedicated report permission; small cells suppressed with explicit marker; no raw answers by default.**
- **B. Quiz ownership alone grants every participant result.**
- **C. Platform analysts only.**

**Recommendation:** A, with participant self-access handled separately.

**Your answer:** _Pending._

### D36. How quickly do corrections/releases update reports and caches?

**Owner / timing:** #679/#606/#607; **Before report activation**.

**Decision:** Define projection lag SLO, stale marker, cache variation, and current versus historical result versions.

**User example:** A grade is corrected at 10:00; the teacher reloads a cached report at 10:01 and still sees the old cohort average.

**Options:**

- **A. Reports consume the current immutable version after idempotent projection, expose bounded staleness/as-of, invalidate authorization-varying caches, and keep old versions historical only.**
- **B. Update nightly with no stale indicator.**
- **C. Recalculate from raw answers on every request.**

**Recommendation:** A. Choose the initial projection-lag SLO before #606 (for example under five minutes, with alerts).

**Your answer:** _Pending._

### D37. What scheduled-release time-zone and manual-override rules apply?

**Owner / timing:** #675/#697/#633/#634; **Before scheduled release**.

**Decision:** Choose storage as an instant, author local-zone handling, DST ambiguity, and manual-versus-scheduled precedence.

**User example:** A teacher schedules 09:00 Europe/London on the daylight-saving transition day, then manually releases at 08:55 while the worker is due.

**Options:**

- **A. Resolve author local date/time+zone to one persisted UTC instant at publication; reject ambiguous/nonexistent local times unless explicitly disambiguated; first committed release wins idempotently.**
- **B. Store local text and recalculate on every worker run.**
- **C. Scheduled release always overwrites manual release state.**

**Recommendation:** A.

**Your answer:** _Pending._

### D38. What exact base formal-assessment fixture should #668 certify?

**Owner / timing:** #672/#673/#674/#668; **Before formal conformance**.

**Decision:** Pick one concrete selection, item count, timer, attempt limit, release behavior, and question families. Guest, sections, accommodations, and manual grading have separate fixtures.

**User example:** A teacher assigns a 20-question 30-minute exam with one retake and delayed score release to authenticated students.

**Options:**

- **A. Authenticated audience, one deterministic random strategy, fixed item count, one overall timer, one active attempt, automatic grading, score-only or selected release; no sections/accommodations/guest.**
- **B. Include guest links, sections, accommodations, manual grading, and every selection strategy in one fixture.**
- **C. Fixed authored-order untimed practice, which would not test the formal architecture.**

**Recommendation:** A. Fill in the exact numeric values and selected question types after A06-A18/A22/D21 are answered; #710-#712/#669 cover variants.

**Your answer:** _Pending._

## Completion checklist

- [ ] Every A01-A43 answer is selected or explicitly deferred with an owner and trigger.
- [ ] Every D01-D38 answer is selected or explicitly deferred with an owner and trigger.
- [ ] Phase-0 answers are copied to #612 and exact first-milestone consumers.
- [ ] Extension answers are copied to #672-#689/#664 and their exact consumers.
- [ ] Numeric bounds/defaults and legacy/offline behavior are included, not implied.
- [ ] The architecture decision ledger and backlog review link the approved answers.
- [ ] No implementation issue invents a product answer during coding.
