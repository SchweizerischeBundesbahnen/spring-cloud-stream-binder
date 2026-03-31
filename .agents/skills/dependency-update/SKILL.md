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
Read `pom.xml` and catalog all dependencies with explicit versions vs BOM-managed versions.

Reference: [version-matrix](./references/version-matrix.md)

Key explicitly-versioned dependencies to check:
- `spring-boot-starter-solace-client-config`
- `jakarta.annotation-api`
- `junit-pioneer`
- `testcontainers-solace` and `testcontainers-*`
- `okhttp`
- `swagger-codegen-maven-plugin`
- `gson`
- `wavefront-sdk-java`

### Step 2: Check for Updates
For each explicitly-versioned dependency:
- Check Maven Central for the latest release via web search
- Verify compatibility with the current Spring Boot and Spring Cloud BOMs
- Assess risk level: patch (safe), minor (review changelog), major (breaking — review carefully)

### Step 3: Apply Updates
For approved updates:
1. Update the version in `pom.xml`
2. Run unit tests:
   ```shell
   mvn test-compile -P it_tests && mvn test -DskipITs -P it_tests > maven_tests.log 2>&1
   ```
3. If tests pass, run integration tests:
   ```shell
   mvn -B verify -Dmaven.test.skip=false -P it_tests --file pom.xml > maven_it_tests.log 2>&1
   ```
4. Parse results: `tail -20 maven_tests.log` and `grep -E "Tests run:|BUILD" maven_tests.log`

### Step 4: Update Documentation
- Update `README.md` version compatibility table if Spring Boot/Cloud versions changed
- Add entry to `CHANGELOG.md` for dependency updates
- Update `DEVELOPER.md` if build tool requirements changed

### Step 5: Verify Dependabot Coverage
Check `.github/dependabot.yml` to ensure new dependencies are covered by automated updates.

### Step 6: Verify CI
```shell
gh run list --limit 5
```
