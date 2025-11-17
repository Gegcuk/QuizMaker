# Agents: Using GPT-5.1 Effectively

This repo includes lightweight guidance for getting the most out of ChatGPT/GPT‑5.1 as an engineering assistant on QuizMaker. It summarizes tested prompting patterns from the OpenAI Cookbook and provides ready‑to‑use templates for common workflows.

- Primary reference: https://cookbook.openai.com/examples/gpt-5/gpt-5-1_prompting_guide
- Target models: `gpt-5.1` (balanced), `gpt-5.1-mini` (fast/cost‑efficient)

What you’ll find:

- docs/agents/gpt-5.1/prompting-guide.md — core patterns and anti‑patterns, tailored to this Spring Boot repo
- docs/agents/gpt-5.1/templates.md — project‑specific templates for controllers, WebMvc tests, code review, bug triage, extraction
- docs/agents/gpt-5.1/auth-review.md — focused checklist/template for auth endpoints

Quick start (recommended flow):

1) Pick a model: prefer `gpt-5.1` for non‑trivial coding; use `gpt-5.1-mini` for quick triage or structured extraction.
2) Use the templates as your system/developer messages; put the exact task and acceptance criteria first in the user message.
3) When you need machine‑readable results, request JSON with a schema and enable strict JSON mode.
4) Ask for a brief plan first, then the final result, and include a short self‑check against your criteria.

Notes

- These docs are informational guidance for humans using GPT‑5.1 with this repo; they do not impose additional repository rules.
- Keep prompts concise, include only relevant context (logs, file snippets, specs), and state the desired output format explicitly.
