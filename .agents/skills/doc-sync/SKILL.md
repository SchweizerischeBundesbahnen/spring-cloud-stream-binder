---
name: doc-sync
description: "Verify documentation is synchronized with codebase. Use when checking API.md accuracy, finding undocumented properties or headers, verifying version tables, auditing CHANGELOG/MIGRATION completeness, or before releases."
---

# Documentation Sync Workflow

## When to Use
- Before a release to verify all docs are current
- After adding new properties, headers, or metrics
- When reviewing documentation accuracy
- After removing or deprecating features

## Procedure

### Step 1: Verify Properties
Cross-reference all fields in these property classes against API.md:
- `SolaceConsumerProperties.java` — Consumer binding properties
- `SolaceProducerProperties.java` — Producer binding properties
- `SolaceCommonProperties.java` — Shared properties

Reference: [doc-inventory](./references/doc-inventory.md)

For each field: verify the property name, Java type, default value, and description match API.md.

### Step 2: Verify Headers
Cross-reference header constants:
- `SolaceHeaders.java` + `SolaceHeaderMeta.java` — Solace message headers
- `SolaceBinderHeaders.java` + `SolaceBinderHeaderMeta.java` — Binder-specific headers

Verify each appears in:
1. API.md "Solace Message Headers" section
2. `doc/SPECIAL_HEADER.md`

### Step 3: Verify Metrics
Cross-reference all metrics registered in `SolaceMessageMeterBinder.java` against the "Solace Binder Metrics" section of API.md.
Verify base units are documented correctly (milliseconds for distribution summaries, NOT seconds).

### Step 4: Verify Version Table
Check `README.md` compatibility table includes the current binder version (9.0.0).
Verify Spring Boot (4.0.4), Spring Cloud (2025.1.1), and sol-jcsmp version entries are accurate.

### Step 5: Verify Deprecations
Search for `@Deprecated` annotations in the main source tree:
```shell
grep -rn "@Deprecated" src/main/java/ --include="*.java"
```
Verify each deprecated item has:
- JavaDoc with `@deprecated` tag pointing to the replacement
- Coverage in `MIGRATION.md` if it's a property or public API removal

### Step 6: Verify CHANGELOG Currency
- Confirm `CHANGELOG.md` has an entry for the current version (9.0.0)
- Check that the entry date and content match the actual release
- Verify all user-facing changes since the last release have corresponding CHANGELOG entries
- Verify format: `## [version] (YYYY-MM-DD)` with subsections: Added, Changed, Removed, Fixed

### Step 7: Verify README Completeness
- Verify the version compatibility table in `README.md` includes the current binder version
- Cross-reference the top row against `pom.xml` versions:
  - `spring-boot-starter-parent` → Spring Boot column
  - `spring-cloud.version` property → Spring Cloud column
  - Transitive `sol-jcsmp` version → sol-jcsmp column
- Check that Maven coordinate snippets show the current version
- Verify table of contents links are not broken

### Step 8: Report
Produce a sync report with categories:
- **Missing**: Items in code but not in docs
- **Stale**: Items in docs that no longer exist in code
- **Mismatch**: Items where code and docs disagree (defaults, descriptions)
- **OK**: Areas that are fully in sync
