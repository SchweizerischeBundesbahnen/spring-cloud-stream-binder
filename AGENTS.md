# AI Agents for Spring Cloud Stream Binder for Solace

This document describes specialized AI agents that can work on this codebase. Each agent is designed for specific tasks and has defined capabilities and constraints.

**Project versions:** Spring Boot 4.0.4, Spring Cloud 2025.1.1, Binder 9.0.0, Java 17+.

---

## Agent: Binder Core Developer

**Description:** Implements and modifies core binder functionality including message flow, bindings, and Solace JCSMP integration.

**Capabilities:**
- Modify **queue-based** inbound message handling (`FlowXMLMessageListener`, `JCSMPInboundQueueMessageProducer`, `SolaceFlowEventHandler`)
- Modify **topic-based** inbound message handling (`JCSMPInboundTopicMessageProducer`, `JCSMPInboundTopicMessageMultiplexer`, `TopicFilterTree`)
- Modify outbound message handling (`JCSMPOutboundMessageHandler`)
- Work with Solace session management (`JCSMPSessionEventHandler`, `JCSMPSessionProducerManager`, `SharedResourceManager`)
- Implement message mapping (`XMLMessageMapper`) and header metadata (`SolaceHeaders`, `SolaceBinderHeaders`, `HeaderMeta`, `SolaceHeaderMeta`, `SolaceBinderHeaderMeta`)
- Handle acknowledgment logic (`JCSMPAcknowledgementCallback`, `NestedAcknowledgementCallback`, `SolaceAckUtil`)
- Maintain watchdog deadlock detection and thread concurrency in `FlowXMLMessageListener`
- Work with retry logic (`RetryTemplate` integration in `JCSMPInboundQueueMessageProducer`)
- Implement large message chunking support (`LargeMessageSupport`)

**Key Files:**
- `src/main/java/com/solace/spring/cloud/stream/binder/SolaceMessageChannelBinder.java`
- `src/main/java/com/solace/spring/cloud/stream/binder/inbound/queue/*.java`
- `src/main/java/com/solace/spring/cloud/stream/binder/inbound/topic/*.java`
- `src/main/java/com/solace/spring/cloud/stream/binder/inbound/acknowledge/*.java`
- `src/main/java/com/solace/spring/cloud/stream/binder/outbound/*.java`
- `src/main/java/com/solace/spring/cloud/stream/binder/messaging/*.java`
- `src/main/java/com/solace/spring/cloud/stream/binder/util/XMLMessageMapper.java`
- `src/main/java/com/solace/spring/cloud/stream/binder/util/SharedResourceManager.java`
- `src/main/java/com/solace/spring/cloud/stream/binder/util/LargeMessageSupport.java`

**Constraints:**
- Must maintain backwards compatibility with existing bindings
- Must support both persistent (queue) and non-persistent (topic) messaging
- Must handle client acknowledgment modes correctly
- **CRITICAL:** The Solace JCSMP dispatcher thread MUST remain non-blocking. Synchronous operations must be offloaded to worker threads.
- Topic-based `JCSMPInboundTopicMessageMultiplexer.LivecycleHooks` interface must be respected when implementing lifecycle changes
- `SharedResourceManager<T>` subclasses must correctly implement caching and explicit cleanup

---

## Agent: Configuration & Properties Developer

**Description:** Manages configuration properties, Spring Boot auto-configuration, binding properties, and optional OAuth2 integration.

**Capabilities:**
- Add/modify consumer and producer properties
- Update auto-configuration classes (session, meter, tracing, binding handler mappings)
- Work with SpEL expressions for queue naming (`ExpressionContextRoot`)
- Manage default values and validation
- Configure optional OAuth2 token provider integration (`SolaceSessionOAuth2TokenProvider` in `JCSMPSessionConfiguration`)
- Work with `@ConditionalOn...` annotations for optional dependencies (Micrometer, tracing, OAuth2)

**Key Files:**
- `src/main/java/com/solace/spring/cloud/stream/binder/properties/SolaceConsumerProperties.java`
- `src/main/java/com/solace/spring/cloud/stream/binder/properties/SolaceProducerProperties.java`
- `src/main/java/com/solace/spring/cloud/stream/binder/properties/SolaceCommonProperties.java`
- `src/main/java/com/solace/spring/cloud/stream/binder/properties/SolaceBindingProperties.java`
- `src/main/java/com/solace/spring/cloud/stream/binder/properties/SolaceExtendedBindingProperties.java`
- `src/main/java/com/solace/spring/cloud/stream/binder/config/SolaceMessageChannelBinderConfiguration.java`
- `src/main/java/com/solace/spring/cloud/stream/binder/config/SolaceServiceAutoConfiguration.java`
- `src/main/java/com/solace/spring/cloud/stream/binder/config/SolaceHealthIndicatorsConfiguration.java`
- `src/main/java/com/solace/spring/cloud/stream/binder/config/autoconfigure/JCSMPSessionConfiguration.java`
- `src/main/java/com/solace/spring/cloud/stream/binder/config/autoconfigure/SolaceMeterConfiguration.java`
- `src/main/java/com/solace/spring/cloud/stream/binder/config/autoconfigure/SolaceTracerConfiguration.java`
- `src/main/java/com/solace/spring/cloud/stream/binder/config/autoconfigure/ExtendedBindingHandlerMappingsProviderConfiguration.java`

**Constraints:**
- All properties must be documented in `API.md`
- Property names must follow Spring Boot conventions
- Breaking changes require a deprecation period and must be documented in `MIGRATION.md`

---

## Agent: Metrics & Monitoring Developer

**Description:** Implements and maintains Micrometer metrics, health indicators, watchdog deadlock detection, and observability features.

**Capabilities:**
- Add/modify Micrometer meters (counters, gauges, distribution summaries)
- Implement health indicators for actuator (`SessionHealthIndicator`, `SolaceBinderHealthContributor`, `BindingsHealthContributor`)
- Manage health accessor patterns (`SolaceBinderHealthAccessor`, `SolaceSessionEventHandler`)
- Work with distributed tracing integration (`TracingImpl`, `TracingProxy`)
- Create monitoring documentation and dashboards

**Key Files:**
- `src/main/java/com/solace/spring/cloud/stream/binder/meter/SolaceMessageMeterBinder.java`
- `src/main/java/com/solace/spring/cloud/stream/binder/meter/SolaceMeterAccessor.java`
- `src/main/java/com/solace/spring/cloud/stream/binder/health/SolaceBinderHealthAccessor.java`
- `src/main/java/com/solace/spring/cloud/stream/binder/health/base/SolaceHealthIndicator.java`
- `src/main/java/com/solace/spring/cloud/stream/binder/health/handlers/SolaceSessionEventHandler.java`
- `src/main/java/com/solace/spring/cloud/stream/binder/health/contributors/*.java`
- `src/main/java/com/solace/spring/cloud/stream/binder/health/indicators/SessionHealthIndicator.java`
- `src/main/java/com/solace/spring/cloud/stream/binder/tracing/TracingImpl.java`
- `src/main/java/com/solace/spring/cloud/stream/binder/tracing/TracingProxy.java`

**Constraints:**
- Micrometer is optional - code must work without it
- Time-based distribution summaries must clearly document their base units (e.g. milliseconds)
- Metrics must be documented in `API.md` (Solace Binder Metrics section)

---

## Agent: Provisioning Developer

**Description:** Manages queue/topic provisioning, endpoint configuration, and broker resource management.

**Capabilities:**
- Implement queue provisioning logic
- Handle subscription management
- Work with endpoint properties
- Manage error queue infrastructure
- Implement consumer and producer destination construction (`SolaceConsumerDestination`, `SolaceProducerDestination`)
- Configure SpEL expression context for dynamic queue naming (`ExpressionContextRoot`)

**Key Files:**
- `src/main/java/com/solace/spring/cloud/stream/binder/provisioning/SolaceEndpointProvisioner.java`
- `src/main/java/com/solace/spring/cloud/stream/binder/provisioning/SolaceProvisioningUtil.java`
- `src/main/java/com/solace/spring/cloud/stream/binder/provisioning/QueueNameDestinationEncoding.java`
- `src/main/java/com/solace/spring/cloud/stream/binder/provisioning/SolaceConsumerDestination.java`
- `src/main/java/com/solace/spring/cloud/stream/binder/provisioning/SolaceProducerDestination.java`
- `src/main/java/com/solace/spring/cloud/stream/binder/provisioning/ExpressionContextRoot.java`

**Constraints:**
- Must handle pre-provisioned queues (`provisionDurableQueue=false`)
- Queue naming must follow documented syntax, supporting maximum queue name length limits reliably

---

## Agent: Documentation Writer

**Description:** Maintains API documentation, README, changelog, migration guides, and creates user guides.

**Capabilities:**
- Update `API.md` with property documentation, descriptions, and defaults
- Maintain `README.md` version compatibility table
- Document new features and migration guides (`MIGRATION.md`)
- Create example configurations and dashboards
- Maintain developer guides (`DEVELOPER.md`, `COMPARE_WITH_SOLACE.md`)
- Document special header handling (`doc/SPECIAL_HEADER.md`)
- Keep runnable example modules, example READMEs, and example integration tests aligned with the current implementation

**Key Files:**
- `API.md` (main API documentation)
- `README.md` (overview and version table)
- `CHANGELOG.md` (release notes)
- `MIGRATION.md` (breaking changes guide)
- `DEVELOPER.md` (developer setup and contribution guide)
- `COMPARE_WITH_SOLACE.md` (comparison document)
- `doc/SPECIAL_HEADER.md` (special header documentation)

**Constraints:**
- Use Markdown format natively and concisely for guides
- Keep version table in `README.md` up-to-date
- Ensure backward compatibility breaks strictly require `MIGRATION.md` updates
- When example behavior changes, update the example source, its README, `examples/README.md`, and `examples/pom.xml` together
- Remove or replace examples that only demonstrate generic Spring features and not Solace binder-specific behavior

---

## Agent: Test Developer

**Description:** Creates and maintains unit tests and integration tests.

**Capabilities:**
- Write JUnit 5 unit tests
- Write integration tests with PubSubPlusExtension
- Create test utilities and mocks
- Validate concurrency by asserting strict test leak cleanup (e.g. stopping receiver threads)
- Keep example integration tests asserting the behavior described in each example README

**Key Files:**
- `src/test/java/com/solace/spring/cloud/stream/binder/*IT.java` (integration tests)
- `src/test/java/com/solace/spring/cloud/stream/binder/**/*Test.java` (unit tests)

**Testing Commands:**
```shell
# Unit tests only (skip ITs but enforce test compilation using the correct profile to fetch API dependencies)
# Output is redirected to prevent huge logs from crashing the IDE
mvn test-compile -P it_tests
mvn test -DskipITs -P it_tests > maven_tests.log 2>&1

# Integration tests (requires Docker)
# Output MUST be redirected to prevent huge test logs from crashing the IDE
mvn -B verify -Dmaven.test.skip=false -P it_tests --file pom.xml > maven_it_tests.log 2>&1

# Run specific Integration test
mvn verify -P it_tests -Dit.test=MultiBinderOAuth2IT

# With external broker
SOLACE_JAVA_HOST=tcp://localhost:55555 mvn verify -P it_tests > maven_it_tests.log 2>&1
```

**Test Execution Architecture (2 failsafe executions + surefire):**
- **Unit tests (surefire):** Shared JVM, JUnit 5 parallel with 8-thread fixed pool (`junit-platform.properties`)
- **`integration-test`:** All non-springBoot ITs run with `forkCount=2C, reuseForks=false` — each test class gets its own JVM and its own Docker broker via TestContainers. This means `@Isolated` tests and regular tests all run in parallel across separate forks.
- **`integration-test-springboot`:** Spring Boot ITs (`springBootTests/`) run with `forkCount=2C, reuseForks=false` and tagged `junit.jupiter.tags=isolated`.

**Why `@Isolated` + `forkCount`:** `@Isolated` tells JUnit 5 to run a test class alone (not concurrently with other classes in the same JVM). By giving each class its own JVM fork via `forkCount=2C, reuseForks=false`, all test classes — including `@Isolated` ones — run in parallel across forks while remaining isolated within each fork. Each fork starts its own Docker broker via TestContainers.

**Constraints:**
- Integration tests require a PubSub+ broker (Docker or external)
- Use `@ExtendWith(PubSubPlusExtension.class)` for broker access
- Follow naming convention: `*Test.java` for unit, `*IT.java` for integration

---

## Agent: Error Handling Developer

**Description:** Implements error handling, retry logic, and dead letter queue (DLQ) functionality.

**Capabilities:**
- Implement error message handlers
- Configure error queues and DLQ
- Handle message republishing on failure
- Manage correlation keys for error tracking

**Key Files:**
- `src/main/java/com/solace/spring/cloud/stream/binder/util/SolaceErrorMessageHandler.java`
- `src/main/java/com/solace/spring/cloud/stream/binder/util/ErrorQueueInfrastructure.java`
- `src/main/java/com/solace/spring/cloud/stream/binder/util/CorrelationData.java`
- `src/main/java/com/solace/spring/cloud/stream/binder/util/ErrorChannelSendingCorrelationKey.java`
- `src/main/java/com/solace/spring/cloud/stream/binder/util/ErrorQueueRepublishCorrelationKey.java`
- `src/main/java/com/solace/spring/cloud/stream/binder/util/SolaceMessageHeaderErrorMessageStrategy.java`
- `src/main/java/com/solace/spring/cloud/stream/binder/util/SolaceAcknowledgmentException.java`
- `src/main/java/com/solace/spring/cloud/stream/binder/util/SolaceMessageConversionException.java`
- `src/main/java/com/solace/spring/cloud/stream/binder/util/ClosedChannelBindingException.java`

**Constraints:**
- Must support `autoBindErrorQueue` configuration
- Must handle republish failures gracefully without risking a message drop
- Error messages must include original message context and correlation metrics

---

## Build & Release Information

**Build Command:**
```shell
mvn clean package
```

**Required Environment:**
- Java 17+
- Maven 3.x
- Docker (for integration tests) or external Solace broker

**External Broker Configuration:**
```shell
SOLACE_JAVA_HOST=tcp://localhost:55555
SOLACE_JAVA_CLIENT_USERNAME=default
SOLACE_JAVA_CLIENT_PASSWORD=default
SOLACE_JAVA_MSG_VPN=default
TEST_SOLACE_MGMT_HOST=http://localhost:8080
TEST_SOLACE_MGMT_USERNAME=admin
TEST_SOLACE_MGMT_PASSWORD=admin
```

---

## Code Style & Conventions

- **Dispatcher Thread Safety:** NEVER block the Solace dispatcher thread. Hand off messages to local worker threads immediately.
- Use Lombok annotations (`@Getter`, `@Setter`, `@Slf4j`, `@RequiredArgsConstructor`) to minimize boilerplate.
- Follow Spring Boot conventions for configuration properties and integration semantics.
- Prefer `Optional` wrappers over raw `null` checks where appropriate.
- Deprecations: Use `@Deprecated` annotation combined with thorough JavaDoc highlighting the newly-recommended replacement.
