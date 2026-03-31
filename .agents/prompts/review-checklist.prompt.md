---
description: "Run the 8-point PR review checklist against the current changes or specified files. Covers dispatcher safety, resource leaks, metrics, documentation sync, topic/queue parity, and security."
agent: "reviewer"
tools: [read, search]
---

Review the current code changes against the project's 8-point checklist:

1. **Dispatcher Validation:** Does any code block or stall the Solace JCSMP dispatcher thread?
2. **Resource Leaks:** Are JCSMP sessions, flows, and producers properly closed? Are test suites calling `listener.stopReceiverThreads()`?
3. **Metrics Tracking:** Are metrics reporting cleanly without incorrect Micrometer time conversions (milliseconds, not seconds)?
4. **Documentation Sync:** Is `API.md` and/or `MIGRATION.md` synchronized with property/behavior changes?
5. **KISS Principle:** Is the logic straightforward or overly contrived?
6. **Topic & Queue Parity:** If the change affects queue-based inbound handling, does it also need to apply to topic-based handling (or vice versa)?
7. **OAuth2 & Security:** Are credentials, tokens, and trust store paths handled safely? Are optional dependencies properly `@ConditionalOn...` gated?
8. **Header Mapping Consistency:** Do new/modified headers appear in both `SolaceHeaderMeta`/`SolaceBinderHeaderMeta` AND `API.md`?

Report findings using categories: **Critical/Bug**, **Architecture/Design**, **Code Smell**, **Documentation/Improvement**.
