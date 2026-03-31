---
description: "Generate a JUnit 5 unit test for the selected code or class. Follows project conventions: *Test.java naming, AssertJ assertions, Mockito mocking."
agent: "agent"
tools: [read, search, edit]
---

Generate a JUnit 5 unit test for the provided code:

1. Name file `<ClassName>Test.java` in the matching test package under `src/test/java/`
2. Use AssertJ for assertions (`assertThat(...).isEqualTo(...)`)
3. Use Mockito `@Mock` and `@InjectMocks` for dependency injection
4. Annotate with `@ExtendWith(MockitoExtension.class)`
5. Cover: happy path, edge cases, error scenarios, null/empty inputs
6. Use `@DisplayName` for readable test descriptions
7. Do NOT require a running Solace broker — mock all JCSMP dependencies
8. Check existing `*Test.java` files in the same package for consistent patterns
9. Follow the project's Lombok conventions (`@Slf4j` if logging is needed)
