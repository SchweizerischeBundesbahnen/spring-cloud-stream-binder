---
description: "Documentation standards for project markdown files. Use when updating API.md, MIGRATION.md, CHANGELOG.md, README.md, DEVELOPER.md, or doc/ files. Covers version tables, property cross-referencing, and formatting conventions."
applyTo:
  - "API.md"
  - "MIGRATION.md"
  - "CHANGELOG.md"
  - "README.md"
  - "DEVELOPER.md"
  - "COMPARE_WITH_SOLACE.md"
  - "doc/**"
---

# Documentation Standards

## API.md
- Primary reference for ALL configuration properties, headers, and metrics
- Every property entry must have: name, default value, and description
- Headers must document read/write access levels
- Metrics must specify base units (milliseconds for distribution summaries)
- Must match the actual code behavior — verify defaults against `SolaceConsumerProperties`, `SolaceProducerProperties`, `SolaceCommonProperties`

## CHANGELOG.md
- Format: `## [version] (YYYY-MM-DD)` with subsections: Added, Changed, Removed, Fixed
- Every user-facing change (feature, fix, deprecation, removal, dependency upgrade) needs a changelog entry
- Breaking changes must be called out explicitly under a `### Breaking Changes` subsection
- If no section exists for the current in-development version, create one at the top with `## [Unreleased]`
- When releasing, replace `[Unreleased]` with `[version] (YYYY-MM-DD)`

## README.md
- The version compatibility table MUST always include the current binder version in the top row
- When any of these change in `pom.xml`, update the table immediately:
  - `spring-boot-starter-parent` version → Spring Boot column
  - `spring-cloud.version` property → Spring Cloud column
  - Transitive `sol-jcsmp` version → sol-jcsmp column
- Maven coordinate snippets must show the current version
- Never remove old version rows from the compatibility table

## MIGRATION.md
- Required for ALL breaking changes between major versions
- Include: what changed, why, and specific migration steps with code examples
- List removed properties with their replacements (if any)

## README.md Version Table
- Update the compatibility table when releasing a new binder version
- Columns: Spring Cloud Stream, Binder, Spring Boot, sol-jcsmp
- Current: Binder 9.0.0, Spring Boot 4.0.4, Spring Cloud 2025.1.1

## doc/SPECIAL_HEADER.md
- Must list ALL constants from `SolaceHeaders` and `SolaceBinderHeaders`
- Include access level (Read/Write) as defined in `SolaceHeaderMeta` / `SolaceBinderHeaderMeta`

## Cross-Reference Rules
- Property names and defaults in code MUST match API.md exactly
- Header constants in `SolaceHeaderMeta` / `SolaceBinderHeaderMeta` MUST appear in both API.md and `doc/SPECIAL_HEADER.md`
- Metric names in `SolaceMessageMeterBinder` MUST appear in API.md
