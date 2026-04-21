---
name: dependency-update
description: "Check and update Maven dependencies. Use when upgrading libraries, verifying Spring Boot/Cloud BOM compatibility, running Dependabot-like checks locally, or planning version upgrades. Validates builds and updates documentation."
---

# Dependency Update Workflow

## When to Use
- Periodic dependency freshness check
- After Dependabot PRs to verify compatibility
- Before a release to ensure all dependencies are current
- When upgrading Spring Boot or Spring Cloud versions

## Procedure

### Step 1: Analyze Current Dependencies
Read `pom.xml` and catalog all dependencies AND plugins with explicit versions vs BOM-managed versions.
Key explicitly-versioned dependencies to check:
- `spring-boot-starter-solace-client-config`
- `jakarta.annotation-api`
- `junit-pioneer`
- `testcontainers-solace` and `testcontainers-*`
- `okhttp`
- `swagger-codegen-maven-plugin`
- `swagger-annotations`, `swagger-core`, `swagger-models`
- `gson`
- `wavefront-sdk-java`
- All Maven plugins with explicit versions (e.g. `maven-source-plugin`, `central-publishing-maven-plugin`)

### Step 2: Check for Updates
For each explicitly-versioned dependency and plugin:
- Check Maven Central for the latest **stable** release via web search (skip RC/alpha/beta)
- Only update if the version is actually available and downloadable from Maven Central
- Verify compatibility with the current Spring Boot and Spring Cloud BOMs
- Assess risk level: patch (safe), minor (review changelog), major (breaking — review carefully)

### Step 3: Apply Updates
For approved updates:
1. Update the version in `pom.xml`
2. Run build verification:
   ```shell
   mvn verify "-Dgpg.skip" -DskipTests
   ```
   Note: Use `-Dgpg.skip` because GPG signing is not available in dev environments.
3. If build succeeds, run tests:
   ```shell
   mvn test-compile -P it_tests && mvn test -DskipITs -P it_tests > maven_tests.log 2>&1
   ```
4. If tests pass, run integration tests:
   ```shell
   mvn -B verify -Dmaven.test.skip=false -P it_tests --file pom.xml > maven_it_tests.log 2>&1
   ```
5. Parse results: `tail -20 maven_tests.log` and `grep -E "Tests run:|BUILD" maven_tests.log`

### Step 3b: Update Examples
All example projects under `examples/` must also be updated:
1. Check all explicitly-versioned dependencies and plugins in example `pom.xml` files (e.g. `testcontainers-solace.version`, `maven-failsafe-plugin`, `spring-boot-starter-parent`)
2. For each, check Maven Central for the latest **stable** release (same rules as Step 2)
3. Apply all applicable updates across all example `pom.xml` files
4. Verify examples still compile after updates

### Step 4: Version Bump
After successful `mvn verify`, bump the project version to the next patch release:
1. Update `<version>` in `pom.xml` (e.g. 9.0.0 → 9.0.1)
2. Update the Maven dependency snippet in `README.md` to the new version

### Step 5: Update Documentation

#### README.md — Version Compatibility Table
- **Only ADD a new line** at the top of the table for the new version
- **NEVER modify or replace existing lines** in the table — they are historical records
- Update the Maven dependency snippet `<version>` to the new version

#### CHANGELOG.md
- First check if `CHANGELOG.md` exists in the repository
- If it exists, add a new version entry (e.g. `## [9.0.1] - 2026-04-20`) with a `### Changed` section listing all dependency updates
- Do NOT use `[Unreleased]` — use the concrete version number and today's date directly

### Step 6: Verify Dependabot Coverage
Check `.github/dependabot.yml` to ensure new dependencies are covered by automated updates.

### Step 7: Verify CI
```shell
gh run list --limit 5
```
