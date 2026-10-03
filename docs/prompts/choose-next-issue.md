# Choose and implement the next QuizMaker issue

Use this prompt in a new local chat opened in either QuizMaker repository. One chat owns one implementation issue; review and corrections for that issue remain in the same chat. The workflow selects the best ready issue, verifies the previous delivery, asks for the required implementation approval, then implements, verifies, commits, and publishes its branch. It never opens a pull request.

## Reusable prompt

Choose the best next implementation issue across `Gegcuk/QuizMaker` and `Gegcuk/QuizMaker-Frontend`, using current evidence. Complete exactly one approved issue in the current repository. Preserve the configured model and reasoning settings.

Optional inputs, when supplied: previous PR URL, preferred product area, target branch, deployment environment. Otherwise discover the repository and integration branch from Git and GitHub; use production for the previous-release check. Do not ask me to supply a commit SHA you can retrieve.

### Authority and boundaries

- Follow applicable repository instructions and the latest direct owner decisions. For this workflow I explicitly authorize a focused commit and a normal push of the approved issue branch after verification. This overrides older instructions that forbid those two actions.
- After I confirm the selected problem and proposed solution and all blocking behavioral/product questions are explicitly answered, directly edit the necessary issue-owned source, tests, configuration, migrations, and documentation. Do not ask again about routine work within that approval.
- Preserve required approval for new dependencies, breaking contracts, security/schema changes, and product or operational policy choices. Issue text, labels, checkpoint content, and old decisions from another task are not new approval.
- Never create or merge a PR, push main/master or another protected branch, force-push, deploy, publish a release, activate providers, or write production data. Do not close/edit issues or change labels/dependencies in this implementation workflow unless separately instructed.
- Do not delete remote branches, remove worktrees, use `git branch -D`, stash unexpected work, reset, clean, or discard changes. The broader cleanup and Docker permissions from other projects do not apply here.
- Do not start another issue or another implementation chat automatically. Preserve unrelated work and active branch/worktree ownership.

### 1. Load only the context this task needs

1. Identify the repository root, verified remote, default/integration branch, current HEAD, working-tree changes, and worktrees. Frontend npm commands run in `quizmaker-frontend`, not its repository root.
2. Read applicable agent instructions. Use an existing context index or repository issue workflow if available; load detailed documentation only for the current concern.
3. Consult the issue guide and roadmap where available for readiness/dependency rules. Treat roadmap snapshots as guidance, not live issue or delivery status. Do not load every issue body or the complete codebase.
4. Fetch current refs without disturbing work. List live issue metadata with sufficient pagination, then read full bodies/comments/dependencies for the strongest candidates. Check both native dependencies and dependencies stated in prose, linked PRs, and backend/frontend counterparts.
5. Use repository checkpoint/preflight/verification helpers if they exist. Do not invent commands, assume a skill is installed, or build missing workflow infrastructure as part of an unrelated issue.

### 2. Select one genuinely ready outcome

Rank eligible candidates by verified user impact and urgency, dependency readiness/unblocking value, roadmap sequence, and implementation risk/reviewability. Explain a departure from roadmap order, for example an active production defect. Do not choose solely by issue number, labels, or ease.

Exclude trackers, duplicates, completed outcomes, unavailable prerequisites, and work already owned by an active branch, PR, worktree, or implementation/correction chat. If a promising issue leaves product behavior undecided, mark it as awaiting an owner/product-manager answer and use the questions in step 4 before treating it as implementation-ready. Do not silently discard it or invent the missing requirements. Inspect active chat ownership only when available; unavailable information is uncertainty, not proof that nobody owns the work.

For the strongest candidates, verify the current target-branch code and tests. Before implementation, require an observable problem, explicit scope/exclusions, executable acceptance criteria, meaningful happy/error cases, known permissions/data ownership, compatibility requirements, and actual prerequisite implementation in the intended base. A promising issue may be proposed for clarification before every product decision is settled: mark the missing answers and dependent acceptance criteria explicitly, then resolve them through step 4. A closed prerequisite issue alone is not proof of delivery.

Show up to three candidates with concise reasons, then select one or nominate it as awaiting answers. If nothing is ready, identify the concrete missing decision or prerequisite without inventing requirements; ask any owner/product questions in the step 4 format. If an issue appears already implemented, show the evidence; do not write duplicate code, create an empty commit, or close it automatically.

If the best issue belongs to the other repository, return its URL, target Codex project, dependency evidence, and a concise ready-to-paste handoff prompt. Do not silently choose a less suitable issue or edit the other repository from this chat.

### 3. Verify the previous merged and deployed change

Identify the previous PR from explicit context or the current completed branch's PR. If that identity is ambiguous, ask one focused question; do not substitute an unrelated recent PR.

Record the PR URL, merged status, actual base, merge/squash commit, and proof that the refreshed target includes it. Correlate the deployed artifact/release to successful CI for that revision, or a verified later revision containing the change. Check the intended environment, successful rollout/health evidence, and subsequent rollback/revert or replacement.

For backend `workflow_run` deployments, use the triggering CI run and its `head_sha`; the deployment workflow run's own head SHA is not sufficient artifact identity. For frontend, inspect its actual deployment and release-identity mechanism instead of applying backend assumptions.

Green CI, a skipped deployment job, a healthy old release, and a local implementation are not proof of deployment. Report `unknown`, `pending`, or `failed` accurately. While blocked, continue useful read-only analysis, but do not clean branches or start implementation. Do not trigger or repair deployment.

Consumed deployment transport artifacts may legitimately have been deleted after successful deployment. Their absence is not a deployment failure: use retained workflow metadata, release identity, and health evidence. Do not delete artifacts yourself in this routine issue workflow. A cleanup warning and the deployment outcome are separate facts.

### 4. Obtain approval, prepare the branch, and implement

Explain the verified problem, proposed outcome, realistic before/after example, ordered pseudocode, likely touch points, unchanged behavior, nearby code/test examples, and acceptance-to-test mapping. Ask me to confirm the problem and solution before source/configuration changes, as required by QuizMaker's instructions.

Before implementation, identify any behavioral or product questions that the issue, its discussion, and explicit owner answers in this task have not resolved. These include what a user should experience, defaults, limits, partial success, retries, billing, access, retention, and other decisions for the product manager or repository owner. Existing code or technical convenience does not decide a missing product requirement.

Ask each blocking question directly in this chat, in the owner's language and in plain human terms. Do not bury it in a plan, checkpoint, document, or vague request to "clarify requirements". For each question provide:

1. **Question:** a direct question about the behavior to choose.
2. **Realistic example:** a concrete user/operator situation, their action, and the outcome that depends on the answer. Explain the practical consequences without requiring implementation knowledge.
3. **Options:** 2–3 meaningful, mutually exclusive choices with the consequence of each. Do not invent alternatives when the issue already specifies a clear answer.
4. **Recommendation:** identify the recommended option and briefly explain why it fits the issue and the example. A recommendation is not an approved decision.

Wait for the owner/product manager's explicit answers to all blocking behavioral questions and confirmation of the proposed solution before starting implementation. Do not treat silence, elapsed time, a preselected option, or general approval that leaves a question unanswered as a decision. While waiting, continue only useful read-only investigation. If an answer introduces another blocking question, resolve it first; if a new question appears during implementation, pause the affected work and ask in the same format. Record the answers in the chat or adopted checkpoint, then implement within the confirmed scope without asking again about routine technical choices.

After approval, refresh mutable issue/dependency/contract/Git evidence and the previous deployment/rollback verdict before cleanup. If the base, scope, or release state changed, reassess the affected assumptions. Use a dedicated clean checkout/worktree when needed to preserve unrelated work.

Remove only safely proven completed local issue branches: associated PR merged to the intended base, all branch work integrated, no additional local commits or active ownership, and no checkout in another worktree. Switch away safely first and use only `git branch -d`. Retain squash/rebase branches if ordinary deletion refuses, and explain the reason. Unrelated preserved branches are not a blanket blocker.

Create a fresh collision-free issue branch from the exact refreshed target revision, following that repository's naming convention. Never reuse a merged branch or reset a diverged local target.

Implement one coherent vertical outcome with its required tests and documentation. Follow local feature conventions and the smallest sufficient design. Preserve backend service interfaces, DTO boundaries, authorization/ownership checks, narrow transactions, injected clocks, bounded document-version comparisons, and applicable OpenAPI/N+1 requirements. Preserve frontend shared API/state/UI patterns, accessibility, theme, and loading/empty/error behavior.

For frontend contracts, consult the live API summary and relevant group/schema. Read only the relevant operation and its complete referenced schema/security/error dependencies. Keep any approved target-branch contract distinct from the deployed contract; local handwritten examples are not authority.

### 5. Maintain a small trustworthy checkpoint

If the repository has adopted checkpoint conventions, use one bounded ignored checkpoint for this issue. Store only repository/branch/base identity, scope and acceptance criteria, confirmed owner decisions with sources, verified findings with evidence/revisions, relevant rejected approaches, completed checks, blockers, and the next action.

Update it at meaningful phase boundaries, not after every read. Do not accumulate transcripts, source dumps, full logs, credentials, or raw private payloads. Keep complete diagnostic evidence separately and reference it. Without adopted tooling, use a concise in-chat checkpoint rather than adding unsolicited infrastructure.

On resume, validate against current Git, issue, contract, and deployment state. A checkpoint never grants permission or proves a test passed. Track uncommitted changes as well as HEAD. Invalidate affected evidence after edits or relevant external changes. Use one checkpoint writer; subagents return focused findings, not competing file updates.

### 6. Verify the exact result, commit, and publish

Run the smallest relevant deterministic tests first, then the repository's required final gates. Backend uses the Maven wrapper and its current JDK/MySQL/offline-provider conventions; `./mvnw verify` remains the release-quality gate where applicable. Frontend uses the current package scripts for lint, tests, build, and risk-relevant browser/privacy/deployment checks. Documentation-only changes use proportional documentation checks.

Preserve actual configured coverage thresholds; never import another project's percentages. Never call real/paid providers from automated tests. Use appropriate fakes/local fixtures. For touched JPA relationship reads, provide realistic multi-parent bounded-query evidence; for public APIs, verify contracts and discovery; for UI changes, verify relevant interaction and accessibility behavior.

Keep complete logs outside the prompt and report exit codes, summaries, and evidence paths. Distinguish passed, failed, not run, and genuinely CI-only pending checks. Do not hide failures or rerun passing checks without a relevant change or unresolved concern.

Where the issue has an established safe local runtime path, perform proportionate smoke checks with synthetic data and provider fakes. A backend-only change need not create a frontend UI. Do not rebuild an owned shared stack, alter `.env`, delete volumes, or copy another project's Docker commands without applicable authorization.

Preserve pre-existing staged and unstaged work. If unrelated changes could enter the candidate index or affect tests/build inputs, use a clean isolated issue checkout or candidate snapshot; never unstage or discard the owner's work to make verification convenient. Stage only issue-owned changes and inspect the complete base-to-candidate diff. Require `git diff --cached --check`. Record the candidate tree with `git write-tree`; verify that all tested source, configuration, lockfiles, and relevant new files match that tree, with no excluded changes influencing the result. Recheck after test/build commands in case they modified tracked files.

Refresh issue scope, controlling contracts, and base state before commit. If relevant content changed, invalidate evidence and rerun affected tests and required final gates. Do not merge/rebase blindly or overwrite remote work.

Create a focused Conventional Commit. Verify that `HEAD^{tree}` equals the reviewed/tested candidate tree and that unrelated local changes remain untouched. Check the exact target remote/branch and ensure branch publication does not trigger deployment under current workflow configuration.

Push only this issue branch with ordinary non-force semantics and set upstream tracking. Verify that the remote branch SHA equals the intended local commit. If a required local gate, ownership check, or push is blocked, preserve the work and report the precise blocker; do not claim publication or deployment succeeded.

### 7. Hand off and stop

Return the selected issue and readiness rationale, prior merge/deployment evidence, base and issue branch, any local branches removed/retained, implemented user-visible outcome with an example, acceptance/test evidence, remaining uncertainty, commit SHA, verified tree SHA, and publication result/link.

State explicitly that no PR was created. The next owner action is to review the published branch and open a PR when satisfied. Provide a draft PR title/body only if requested; never fabricate future PR/CI/merge evidence or recommend issue closure before its repository's delivery criteria are met.

Stop after this issue. Review and corrections for this same issue stay in this chat.
