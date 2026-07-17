# Contributing to QuizMaker

This repository accepts implementation work through a local-first workflow. The same rules apply to human contributors and AI agents.

## Start Here

- Read [the development rules](docs/agents.md) before changing production code.
- Use [the issue-writing guide](docs/github-issue-guide.md) when creating or refining an issue.
- Use [the developer workflow](docs/developer-workflow.md) to take an issue from discovery through verification.
- Use [the testing guide](docs/testing-guide.md) to select the correct test layer and assertions.
- Check [the open-issue roadmap](docs/open-issue-roadmap.md) before starting work that may depend on another issue.
- Read [the known risk register](docs/known-risk-register.md) before changing authentication, CI, deployment, or secret handling.

## Local-Only Git Policy

- Work only in the local checkout.
- Do not run `git push`, create or merge pull requests, publish releases, or trigger deployments from an implementation session.
- Create a local commit only when the repository owner explicitly asks for one.
- A human repository owner reviews local commits and decides whether and when to push them.
- Never include unrelated working-tree changes in a commit.

## Minimum Delivery Standard

Every completed change must:

- follow the feature-first package layout;
- keep controllers thin and depend on application-service interfaces;
- preserve authorization, ownership, and visibility rules;
- use DTOs at API boundaries and typed OpenAPI schemas;
- include logically meaningful tests at the appropriate layers;
- preserve backward compatibility unless the issue explicitly approves a breaking change;
- update user-facing and API documentation when behavior or contracts change;
- pass the smallest relevant tests first, then `./mvnw verify` before handoff when practical.

Do not add abstractions merely to satisfy a pattern. Apply SOLID principles and design patterns where they reduce coupling or represent real variation, while keeping the implementation as simple as the requirements allow.
