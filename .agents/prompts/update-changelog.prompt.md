---
description: "Update CHANGELOG.md and README.md after code changes. Use after implementing features, fixes, deprecations, dependency upgrades, or version bumps. Ensures CHANGELOG has proper entries and README version table is current."
agent: "agent"
tools: [read, search, edit]
---

Update project documentation to reflect recent code changes:

## CHANGELOG.md
1. Read the current `CHANGELOG.md` and identify the topmost version section
2. If no `## [Unreleased]` or current version section exists, create one at the top
3. Add entries for the recent changes under the appropriate subsection:
   - `### Added` — new features, properties, headers, metrics
   - `### Changed` — modified behavior, dependency upgrades, default value changes
   - `### Removed` — deleted properties, deprecated API removals
   - `### Fixed` — bug fixes
   - `### Breaking Changes` — anything requiring user migration
4. Follow the existing entry style (prefix with bold category like `**Configuration**:`, `**Watchdog**:`)

## README.md Version Table
1. Read `pom.xml` and extract:
   - Binder version from `<version>` tag
   - Spring Boot version from `spring-boot-starter-parent`
   - Spring Cloud version from `spring-cloud.version` property
2. Run `mvn dependency:tree -P it_tests 2>/dev/null | grep sol-jcsmp` to get the transitive sol-jcsmp version
3. If the top row of the README compatibility table doesn't match these versions, add a new row at the top
4. Never remove existing rows from the table
