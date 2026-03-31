---
description: "Generate a JUnit 5 integration test (*IT.java) with PubSubPlusExtension for Solace broker testing. Follows project patterns for broker setup, message flow, and cleanup."
agent: "agent"
tools: [read, search, edit]
---

Generate a JUnit 5 integration test for the provided functionality:

1. Name file `<Feature>IT.java` in the appropriate test package under `src/test/java/`
2. Annotate with `@ExtendWith(PubSubPlusExtension.class)` for automatic broker lifecycle
3. Use real Solace broker interactions — do NOT mock JCSMP
4. Follow cleanup patterns: close flows, stop listeners, release sessions in `@AfterEach`
5. Use AssertJ assertions and `@DisplayName` for readable tests
6. Check existing `*IT.java` files in `src/test/java/com/solace/spring/cloud/stream/binder/` for consistent patterns
7. If the test needs isolated JVM execution (e.g., Spring Boot context), place it under `springBootTests/`
8. Ensure thread safety — tests run in parallel with 8 threads
9. Never leave orphaned receiver threads — always call `listener.stopReceiverThreads()`
