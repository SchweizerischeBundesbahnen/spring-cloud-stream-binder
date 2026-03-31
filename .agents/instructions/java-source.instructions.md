---
description: "Java source code standards for the Solace binder. Use when writing or modifying production Java code. Covers dispatcher thread safety, Lombok patterns, resource management, and Java 17+ idioms."
applyTo: "src/main/java/**/*.java"
---

# Java Source Guidelines

## Critical: Dispatcher Thread Safety
- NEVER block the Solace JCSMP dispatcher thread
- Offload synchronous or long-running operations to worker threads
- `FlowXMLMessageListener` callback methods must return immediately
- Do not perform I/O, network calls, or blocking waits in message callbacks

## Lombok Usage
- `@Slf4j` — instead of manual `LoggerFactory.getLogger()` declarations
- `@Getter` / `@Setter` — for simple property access
- `@RequiredArgsConstructor` — for constructor injection of `final` fields
- Avoid `@Data` on mutable domain objects (prefer explicit `@Getter` / `@Setter`)

## Java 17+
- Use `record` types for immutable data carriers
- Use `sealed` interfaces where subtype exhaustiveness matters
- Prefer `switch` expressions over if-else chains
- Use `Optional` wrappers over raw `null` checks at API boundaries
- Use text blocks for multi-line strings

## Resource Management
- `JCSMPSession`, `XMLMessageProducer`, `FlowReceiver` must be explicitly closed in shutdown paths
- `SharedResourceManager<T>` subclasses must implement correct caching and cleanup semantics
- Use try-with-resources for auto-closeable resources
- Verify no resource leaks in error/exception paths

## Optional Dependencies
- Gate optional features with `@ConditionalOnClass` or `@ConditionalOnBean`
- Micrometer, tracing, OAuth2, and actuator are all optional
- Never import optional-dependency classes directly — use reflection or conditional wiring

## Headers & Metadata
- New message headers must be registered in both `SolaceHeaderMeta` and API.md
- Binder-specific headers go in `SolaceBinderHeaderMeta` and `SolaceBinderHeaders`
