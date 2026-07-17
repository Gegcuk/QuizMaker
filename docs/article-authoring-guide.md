# QuizMaker Article Authoring Guide

This guide is for article authors, editors, and AI assistants preparing content for the Articles API. The current Articles OpenAPI group and `ArticleUpsertRequest` are the contract when a field or rule differs from this guide.

## Authoring Principles

- Answer the title's question early and accurately. Do not use clickbait or promise more than the article delivers.
- Do not invent facts, figures, quotations, studies, or references. Attribute material claims to a source that actually supports them.
- State meaningful uncertainty, limits, and counterarguments rather than presenting mixed evidence as settled fact.
- Write for readers first: plain language, short paragraphs, descriptive headings, and useful supporting elements.
- Use natural terminology and avoid keyword stuffing or repeated shallow content.

## Prepare The Article

Provide a URL-safe slug, title, meta/OG description, excerpt, estimated reading-time label, publication status, and publish timestamp when publishing. Add a canonical URL, Open Graph image, and `noindex` only when they are needed.

Use the supported content groups: `blog`, `marketing`, `research`, `instructions`, `guide`, `case_study`, `product_update`, `announcement`, and `roadmap`.

Provide author name and role, a trimmed unique tag list, and optional hero kicker. Upload media before creating the article, then reference the returned media `assetId`. Every hero or inline image needs meaningful alt text; captions are optional.

## Compose The Body

Use structured `blocks` unless there is a clear reason to use `sections` instead. Blocks are ordered and support `HEADING`, `PARAGRAPH`, `CALLOUT`, and `IMAGE`.

- Keep block text plain rather than embedding HTML or Markdown.
- Start with a short TL;DR that gives the answer, its intended audience, relevant limitations, and one to three practical takeaways.
- Break long passages with useful headings, key points, checklists, callouts, images, statistics, or FAQs. Do not add supporting elements merely to fill space.
- Use `IMAGE` blocks only with `assetId` and alt text. `caption` and `align` are optional presentation hints.
- Include an explicit methods, evidence, or limitations section when the topic makes factual recommendations.

## Evidence And References

Prefer, in order: systematic reviews or peer-reviewed research for scientific claims; official documentation, standards bodies, governments, universities, or primary sources; then reputable organisations with transparent methodology.

Every important non-obvious claim should either have a matching reference or be clearly qualified as an opinion or uncertainty. A reference list should identify the source title and URL, and sources should be ordered consistently with the claims they support.

## Handoff Checklist

- [ ] The title, description, excerpt, and TL;DR make the same honest promise.
- [ ] Required article metadata and publication status are present.
- [ ] Blocks or sections are complete and ordered for reading.
- [ ] Images have already been uploaded and have useful alt text.
- [ ] Tags are unique and references support the claims made.
- [ ] The article explains material uncertainty or limitations.
- [ ] The final payload is checked against the Articles OpenAPI group before submission.

## Handoff Order

Use this order when preparing a complete article payload:

1. Slug, title, description, excerpt, and hero kicker.
2. Hero image, tags, author, reading time, publication time, and status.
3. Canonical URL, Open Graph image, noindex, and content group.
4. Primary and secondary calls to action.
5. Statistics, key points, checklist, body blocks or sections, FAQs, and references.

The API supports both `blocks` and `sections`, but the author should normally supply one primary body representation to prevent conflicting content.
