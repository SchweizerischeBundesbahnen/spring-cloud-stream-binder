---
description: "Test development guidelines. Use when writing or modifying unit tests (*Test.java) or integration tests (*IT.java). Covers naming conventions, PubSubPlusExtension, test cleanup, and build commands."
applyTo: "src/test/**/*.java"
---

# Test Development Guidelines

## Naming Conventions
- Unit tests: `*Test.java` — no broker required, mock JCSMP dependencies
- Integration tests: `*IT.java` — requires Docker (TestContainers) or external Solace broker
- Spring Boot ITs in `springBootTests/` run in isolated JVMs (`forkCount=2C`)
- All ITs (including `@Isolated` ones) run with `forkCount=2C, reuseForks=false` so each test class gets its own JVM and Docker broker, enabling full parallelism

## Integration Test Setup
- Annotate with `@ExtendWith(PubSubPlusExtension.class)` for broker lifecycle
- PubSubPlusExtension auto-provisions a Solace broker via TestContainers
- SEMP v2 API client is available for broker management operations
- For network fault injection, use the ToxiProxy TestContainers integration

## Resource Cleanup (CRITICAL)
- Always stop receiver threads: `listener.stopReceiverThreads()`
- Close JCSMP sessions in `@AfterEach` or `@AfterAll`
- Avoid orphaned threads that leak between test classes
- JUnit 5 parallel execution is enabled (8 threads) — ensure thread safety in shared test state

## Assertions
- Use AssertJ: `assertThat(...).isEqualTo(...)` (not JUnit `assertEquals`)
- Use `@DisplayName` for readable test descriptions

## Running Tests
```shell
# Unit tests (output MUST be redirected)
mvn test -DskipITs -P it_tests > maven_tests.log 2>&1

# Integration tests (requires Docker, output MUST be redirected)
mvn -B verify -Dmaven.test.skip=false -P it_tests --file pom.xml > maven_it_tests.log 2>&1

# Specific test class
mvn verify -P it_tests -Dit.test=<ClassName>
```
