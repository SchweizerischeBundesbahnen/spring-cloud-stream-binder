# AI Agents for Spring Cloud Stream Binder for Solace

This document describes specialized AI agents that can work on this codebase. Each agent is designed for specific tasks and has defined capabilities and constraints.

---

## Agent: Binder Core Developer

**Description:** Implements and modifies core binder functionality including message flow, bindings, and Solace JCSMP integration.

**Capabilities:**
- Modify inbound message handling (`FlowXMLMessageListener`, `JCSMPInboundQueueMessageProducer`)
- Modify outbound message handling (`JCSMPOutboundMessageHandler`)
- Work with Solace session management (`JCSMPSessionEventHandler`, `JCSMPSessionProducerManager`)
- Implement message mapping (`XMLMessageMapper`)
- Handle acknowledgment logic (`inbound/acknowledge/`)

**Key Files:**
- `src/main/java/com/solace/spring/cloud/stream/binder/SolaceMessageChannelBinder.java`
- `src/main/java/com/solace/spring/cloud/stream/binder/inbound/queue/*.java`
- `src/main/java/com/solace/spring/cloud/stream/binder/outbound/*.java`
- `src/main/java/com/solace/spring/cloud/stream/binder/util/XMLMessageMapper.java`

**Testing Requirements:**
- Run unit tests: `mvn test`
- Run integration tests: `mvn -B verify -Dmaven.test.skip=false -P it_tests --file pom.xml`
- Requires Docker for PubSub+ broker or external broker configuration

**Constraints:**
- Must maintain backwards compatibility with existing bindings
- Must support both persistent (queue) and non-persistent (topic) messaging
- Must handle client acknowledgment modes correctly

---

## Agent: Configuration & Properties Developer

**Description:** Manages configuration properties, Spring Boot auto-configuration, and binding properties.

**Capabilities:**
- Add/modify consumer and producer properties
- Update auto-configuration classes
- Work with SpEL expressions for queue naming
- Manage default values and validation

**Key Files:**
- `src/main/java/com/solace/spring/cloud/stream/binder/properties/SolaceConsumerProperties.java`
- `src/main/java/com/solace/spring/cloud/stream/binder/properties/SolaceProducerProperties.java`
- `src/main/java/com/solace/spring/cloud/stream/binder/properties/SolaceCommonProperties.java`
- `src/main/java/com/solace/spring/cloud/stream/binder/config/*.java`
- `API.adoc` (property documentation sections)

**Testing Requirements:**
- Run `SolaceBinderConfigIT` and `SolaceConsumerPropertiesTest`
- Verify property binding with Spring Boot context

**Constraints:**
- All properties must be documented in `API.adoc`
- Property names must follow Spring conventions
- Breaking changes require deprecation period

---

## Agent: Metrics & Monitoring Developer

**Description:** Implements and maintains Micrometer metrics, health indicators, and observability features.

**Capabilities:**
- Add/modify Micrometer meters (counters, gauges, distribution summaries)
- Implement health indicators for actuator
- Work with tracing integration
- Create monitoring documentation and dashboards

**Key Files:**
- `src/main/java/com/solace/spring/cloud/stream/binder/meter/SolaceMessageMeterBinder.java`
- `src/main/java/com/solace/spring/cloud/stream/binder/meter/SolaceMeterAccessor.java`
- `src/main/java/com/solace/spring/cloud/stream/binder/health/**/*.java`
- `src/main/java/com/solace/spring/cloud/stream/binder/tracing/*.java`
- `src/main/java/com/solace/spring/cloud/stream/binder/util/WatchdogLogger.java`

**Testing Requirements:**
- Run `SolaceBinderMeterIT` and `SolaceBinderHealthIT`
- Verify metrics registration and recording
- Test health indicator state transitions

**Constraints:**
- Micrometer is optional - code must work without it
- Metrics must be documented in `API.adoc` (Solace Binder Metrics section)
- Health indicators must follow Spring Boot Actuator conventions

---

## Agent: Provisioning Developer

**Description:** Manages queue/topic provisioning, endpoint configuration, and broker resource management.

**Capabilities:**
- Implement queue provisioning logic
- Handle subscription management
- Work with endpoint properties
- Manage error queue infrastructure

**Key Files:**
- `src/main/java/com/solace/spring/cloud/stream/binder/provisioning/SolaceEndpointProvisioner.java`
- `src/main/java/com/solace/spring/cloud/stream/binder/provisioning/SolaceProvisioningUtil.java`
- `src/main/java/com/solace/spring/cloud/stream/binder/provisioning/QueueNameDestinationEncoding.java`
- `src/main/java/com/solace/spring/cloud/stream/binder/util/ErrorQueueInfrastructure.java`

**Testing Requirements:**
- Run `SolaceBinderProvisioningLifecycleIT` and `SolaceBinderSubscriptionsIT`
- Verify queue name generation with `SolaceProvisioningUtilQueueNameTest`

**Constraints:**
- Must handle pre-provisioned queues (`provisionDurableQueue=false`)
- Queue naming must follow documented syntax
- Must support both consumer groups and anonymous consumers

---

## Agent: Documentation Writer

**Description:** Maintains API documentation, README, changelog, and creates user guides.

**Capabilities:**
- Update `API.adoc` with property documentation
- Maintain `README.md` version compatibility table
- Document new features and migration guides
- Create example configurations and dashboards

**Key Files:**
- `API.adoc` (main API documentation)
- `README.md` (overview and version table)
- `CHANGELOG.md` (release notes)
- `COMPARE_WITH_SOLACE.md` (fork differences)
- `doc/` (additional documentation)

**Documentation Sections in API.adoc:**
- Lines 1-100: Overview and getting started
- Lines 250-400: Consumer/Producer properties
- Lines 1180-1220: Publisher confirmations
- Lines 1220-1240: Health indicators
- Lines 1241-1270: Metrics documentation
- Lines 1290-1314: Watchdog explanation

**Constraints:**
- Use AsciiDoc format for `API.adoc`
- Keep version table in `README.md` up to date
- All properties must have descriptions and defaults documented

---

## Agent: Test Developer

**Description:** Creates and maintains unit tests and integration tests.

**Capabilities:**
- Write JUnit 5 unit tests
- Write integration tests with PubSubPlusExtension
- Create test utilities and mocks
- Work with Spring test context

**Key Files:**
- `src/test/java/com/solace/spring/cloud/stream/binder/*IT.java` (integration tests)
- `src/test/java/com/solace/spring/cloud/stream/binder/**/*Test.java` (unit tests)
- `src/test/java/com/solace/spring/cloud/stream/binder/test/**/*.java` (test utilities)

**Test Categories:**
| Test File | Description |
|-----------|-------------|
| `SolaceBinderBasicIT` | Core messaging functionality |
| `SolaceBinderClientAckIT` | Acknowledgment handling |
| `SolaceBinderHealthIT` | Health indicator tests |
| `SolaceBinderMeterIT` | Metrics tests |
| `SolaceBinderTracingIT` | Distributed tracing tests |
| `SolaceBinderProvisioningLifecycleIT` | Queue provisioning |
| `FlowXMLMessageListenerTest` | Message listener unit tests |
| `XMLMessageMapperTest` | Message mapping unit tests |

**Testing Commands:**
```shell
# Unit tests only
mvn test

# Integration tests (requires Docker)
mvn -B verify -Dmaven.test.skip=false -P it_tests --file pom.xml

# With external broker
SOLACE_JAVA_HOST=tcp://localhost:55555 mvn verify -P it_tests
```

**Constraints:**
- Integration tests require PubSub+ broker (Docker or external)
- Use `@ExtendWith(PubSubPlusExtension.class)` for broker access
- Follow naming convention: `*Test.java` for unit, `*IT.java` for integration

---

## Agent: Error Handling Developer

**Description:** Implements error handling, retry logic, and dead letter queue functionality.

**Capabilities:**
- Implement error message handlers
- Configure error queues and DLQ
- Handle message republishing on failure
- Manage correlation keys for error tracking

**Key Files:**
- `src/main/java/com/solace/spring/cloud/stream/binder/util/SolaceErrorMessageHandler.java`
- `src/main/java/com/solace/spring/cloud/stream/binder/util/ErrorQueueInfrastructure.java`
- `src/main/java/com/solace/spring/cloud/stream/binder/util/ErrorChannelSendingCorrelationKey.java`
- `src/main/java/com/solace/spring/cloud/stream/binder/util/ErrorQueueRepublishCorrelationKey.java`

**Testing Requirements:**
- Run `SolaceBinderCustomErrorMessageHandlerIT`
- Test error queue republishing scenarios

**Constraints:**
- Must support `autoBindErrorQueue` configuration
- Must handle republish failures gracefully
- Error messages must include original message context

---

## Agent: Large Message Developer

**Description:** Implements large message support and message chunking functionality.

**Capabilities:**
- Handle messages exceeding broker limits
- Implement message chunking/reassembly
- Optimize payload handling

**Key Files:**
- `src/main/java/com/solace/spring/cloud/stream/binder/util/LargeMessageSupport.java`

**Testing Requirements:**
- Run `SolaceBinderLargeMessagingIT`
- Test with messages of various sizes

**Constraints:**
- Must maintain message ordering
- Must handle chunk reassembly failures

---

## Build & Release Information

**Build Command:**
```shell
mvn package
```

**Test Command:**
```shell
mvn -B verify -Dmaven.test.skip=false -P it_tests --file pom.xml
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

- Use Lombok annotations (`@Getter`, `@Setter`, `@Slf4j`, `@RequiredArgsConstructor`)
- Follow Spring Boot conventions for configuration properties
- Use Spring Integration patterns for message channels
- Prefer Optional over null checks
- Use `@Deprecated` annotation with JavaDoc explanation for deprecations

