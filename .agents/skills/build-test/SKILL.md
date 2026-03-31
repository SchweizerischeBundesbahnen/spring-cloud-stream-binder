---
name: build-test
description: "Build and test the Solace binder project. Use when running Maven builds, unit tests, integration tests, diagnosing test failures, or verifying changes locally before pushing. Includes commands for all test modes and result parsing."
---

# Build & Test Workflow

## When to Use
- After code changes to verify correctness
- Before committing to run the full test suite
- When diagnosing test failures from CI
- To run specific test classes or methods

## Procedure

### Step 1: Compile
```shell
mvn clean compile -P it_tests
```
Compiles main sources and resolves all dependencies including the test profile.

### Step 2: Run Unit Tests
```shell
mvn test-compile -P it_tests && mvn test -DskipITs -P it_tests > maven_tests.log 2>&1
```
**IMPORTANT**: Always redirect output to a log file. Test output is extremely verbose and will crash the IDE terminal.

Parse results:
```shell
tail -20 maven_tests.log
grep -E "Tests run:|BUILD" maven_tests.log | tail -5
```

### Step 3: Run Integration Tests
Requires Docker for TestContainers or an external Solace broker.
```shell
mvn -B verify -Dmaven.test.skip=false -P it_tests --file pom.xml > maven_it_tests.log 2>&1
```

Parse results:
```shell
tail -30 maven_it_tests.log
grep -E "Tests run:|BUILD" maven_it_tests.log | tail -10
```

### Step 4: Run Specific Tests
```shell
# Specific IT class
mvn verify -P it_tests -Dit.test=<TestClassName>

# Specific unit test class
mvn test -P it_tests -Dtest=<TestClassName>

# With external broker (no Docker needed)
SOLACE_JAVA_HOST=tcp://localhost:55555 mvn verify -P it_tests > maven_it_tests.log 2>&1
```

### Step 5: Diagnose Failures
If tests fail:
1. Check unit test reports: `target/surefire-reports/`
2. Check IT reports: `target/failsafe-reports/`
3. Search for failures: `grep -A 20 "FAILURE\|ERROR" maven_tests.log | head -60`
4. Look for specific test: `grep -B 2 -A 10 "<TestMethodName>" maven_tests.log`

## Test Execution Architecture

The Maven build has 2 failsafe executions (configured in `pom.xml` under the `it_tests` profile):

| Execution ID | What runs | Parallelism | Notes |
|---|---|---|---|
| `integration-test` | All non-springBoot ITs (regular + `@Isolated`) | `forkCount=2C, reuseForks=false` (parallel JVM forks) | Each class gets its own JVM + Docker broker. `@Isolated` tests run in parallel across forks. |
| `integration-test-springboot` | `springBootTests/**/*IT.java` | `forkCount=2C, reuseForks=false` | Tagged `junit.jupiter.tags=isolated` |

**Why `@Isolated` + `forkCount`:** `@Isolated` prevents JUnit 5 from running a class concurrently with other classes *in the same JVM*. With `reuseForks=false`, each class gets its own JVM fork, so all test classes — including `@Isolated` ones — run in parallel across forks while remaining isolated within each fork. Each fork starts its own Docker broker via TestContainers.

**Maven limitation:** The two failsafe executions run sequentially (they're bound to the same Maven lifecycle phase). The `integration-test` execution wall time is bounded by the slowest single test class (`SolaceBinderBasicIT` ~200s).

**JUnit 5 config** (`src/test/resources/junit-platform.properties`): parallel mode enabled, 8-thread fixed pool, classes + methods concurrent by default.
