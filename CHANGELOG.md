# Changelog

All notable changes to this project will be documented in this file.


## [5.1.0] - 2025-09-05 - Backport from 7.1.0 all features and fixes
### Fixed
- reapply the subscriptions to queues after reconnect to ensure they are present after the temporary queue has been removed during a long disconnect
- Fix false positive "messages is still in progress" log warn message
- New metric "oldest messages in the processing queue" that exposes the same age calculation used for WARN and ERROR log thresholds to monitoring systems
- Improved restart/reconnection reliability with proper null checks and cleanup in flow receiver handling
- Enhanced flow receiver lifecycle management to ensure clean restarts
- Updated sol-jcsmp to 10.28.1 because of window size 0 and other fixed bugs

### Added
- Add concurrent processing on exclusive queues and anonymous consumer groups
- New integration test for binder restart functionality (SolaceBinderRestartIT)

### Changed
- Introduced log trace to get detail information about message processing times
- Health indicator now immediately reports `DOWN` status when connection is down or reconnecting
- Simplified health check behavior for more accurate connection state reporting
- Improved restart/reconnection reliability with proper null checks and cleanup in flow receiver handling
- Enhanced flow receiver lifecycle management to ensure clean restarts

## [7.1.0] - 2025-09-04
### Fixed
- reapply the subscriptions to queues after reconnect to ensure they are present after the temporary queue has been removed during a long disconnect
### Added
- Add concurrent processing on exclusive queues and anonymous consumer groups

### Changed
- Updated sol-jcsmp to 10.28.1
- Updated solace-java-spring-boot-autoconfigure to 5.4.2
- Updated Spring Boot parent to 3.5.5
- Updated spring-boot-maven-plugin to 3.5.4
- Updated maven-javadoc-plugin to 3.11.2
- Updated maven-source-plugin to 3.3.1
- Updated central-publishing-maven-plugin to 0.8.0
- Updated swagger-codegen-maven-plugin to 3.0.71
- Updated build-helper-maven-plugin to 3.6.1
- Updated maven-failsafe-plugin to 3.5.3

## [7.0.3] - 2025-08-29
### Fixed
- Fix false positive "messages is still in progress" log warn message
### Changed
- Introduced log trace to get detail information about message processing times

## [7.0.2] - 2025-08-26
### Added
- New metric "oldest messages in the processing queue" that exposes the same age calculation used for WARN and ERROR log thresholds to monitoring systems

## [7.0.1] - 2025-08-04
### Fixed
- Updated sol-jcsmp to 10.27.3 from 10.27.2 to fix binary update on retransmitting a message can lead to corrupt message, finally provoking broker restart (EBP-1167)
### Changed
- Updated Spring Boot to 3.5.4 from 3.5.3
- Updated okhttp (okhttp3) to 5.1.0 from 4.12.0
- Updated logging-interceptor (okhttp3) to 5.1.0 from 4.12.0
- Updated swagger-annotations to 2.2.35 from 2.2.30
- Updated swagger-models to 2.2.35 from 2.2.30
- Updated swagger-codegen-maven-plugin to 3.0.71 from 3.0.69
- Updated spring-boot-maven-plugin to 3.5.4 from 3.5.3

## [7.0.0] - 2025-07-22
### Removed
- **BREAKING CHANGE**: Removed the `reconnectAttemptsUntilDown` feature from health check configuration
- Removed `SolaceSessionHealthProperties.reconnectAttemptsUntilDown` property

### Changed
- Health indicator now immediately reports `DOWN` status when connection is down or reconnecting
- Simplified health check behavior for more accurate connection state reporting

## [6.0.1] - 2025-06-16
### Fixed
- Improved restart/reconnection reliability with proper null checks and cleanup in flow receiver handling
- Enhanced flow receiver lifecycle management to ensure clean restarts

### Changed
- Increased default maxProcessingTimeMs from 1000ms to 2000ms
- Replaced the log.error with a log.warn

### Added
- New integration test for binder restart functionality (SolaceBinderRestartIT)

## [6.0.0] - 2025-06-15
### Changed
- **MAJOR VERSION UPGRADE**: Created a major release due to Spring Boot update which may introduce breaking changes
- Updated Spring Boot to 3.5.3 from 3.4.5
- Updated spring-boot-maven-plugin to 3.5.3
- Updated spring-cloud.version to 2025.0.0
- Updated sol-jcsmp to 10.27.2
- Updated solace-java-spring-boot-autoconfigure to 5.4.0
- Updated jakarta.annotation-api to 3.0.0
- Updated maven-gpg-plugin to 3.2.7
- Updated toxiproxy to 1.21.3
- Updated swagger-annotations, swagger-core, and swagger-models to 2.2.30
- Updated gson to 2.13.1
- Updated swagger-codegen-maven-plugin to 3.0.69
- Updated build-helper-maven-plugin to 3.6.1
- Updated maven-failsafe-plugin to 3.5.3




## [5.0.10] - 2025-05-26
### Fixed
- updated sol-jcsmp to 27.0.1 to fix windows size 0 issue
- fix a NPE when Micrometer is not available


## [5.0.8] - 2025-04-15
### Feature
- meter for local queue and active processing

### Fixed
- exclude Source Data and Acknowledgement callback headers for producer
- backpressure but blocking the solace dispatcher thread

### Changed
- One flow per binding, multiple threads if concurrency is set, push from jcsmp library instead of polling
- Updated Libraries

## [5.0.7] - 2025-03-21
### Added
- logging of flowId on consumer and producer

### Changed
- One flow per binding, multiple threads if concurrency is set, push from jcsmp library instead of polling
- Updated Libraries

## [5.0.6] - 2025-02-07
### Fixed
- micrometer trace header on specified header field "traceparent"
- support @EnableTestBinder - do not load JCSMPSession if not needed
- support @DirtiesContext - clean JCSMPSession cache on destroy

## [5.0.5] - 2025-01-28
### Updated
- spring boot to 3.4.2
- spring cloud to 2024.0.0

### Fixed
- (solace/merged) DATAGO-69335: Fix for header having value of type byte[] or ByteArray (#338)
- (solace/merged) DATAGO-68275: fix SolaceErrorMessageHandler acknowledgmentCallback detection and error handling (#331)
- (solace/merged) DATAGO-82456: fix queueAdditionalSubscriptions when addDestinationAsSubscription=false (#325)
- (solace/merged) OAuth2 Login

## [5.0.3] - 2024-12-02
### Fixed
- Ensure subscriptions on temporary queues after reconnect
- Fix NPE when tracing is enabled and not tracing header on the message

## [5.0.2] - 2024-09-19
### Added
- Support for Micrometer Tracing

## [5.0.1] - 2024-07-29
### Added
- Support for tests without excluding Autoconfig

## [5.0.0] - 2024-07-28
### Added
- Large message support

### Changed
- Harmonized Logging to SLF4J

### Removed
- Batch processing
- Transactions on batch processing
- Pollable message sources
- TopicEndpoint

## [4.2.4] - 2024-07-22
### Changed
- Change Bean name of context to jcsmpContext to avoid name clashes with jooq

## [4.2.3] - 2024-07-19
### Added
- Cache JCSMPSessions and provide them as Bean to avoid multiple connections to the same broker.
- Add .editorconfig and reformat the whole code.

### Changed
- Bump versions to spring boot 3.3.2
- Bump versions to spring cloud 2023.0.3
- Bump versions of solace jcsmp to 10.24.1
- Use only Slf4j to log.

## [4.2.2] - 2024-07-04
### Changed
- Bump versions of solace jcsmp to 10.24.0

## [4.2.1] - 2024-06-24
### Changed
- Bump versions to spring boot 3.3.1
### Fixed
- Fix a bug when sending Direct Messages

## [4.2.0] - 2024-06-12
### Changed
- By Solace #290 migrate to producer bindings to use JCSMP producer flows
- By Solace #269 give consumer binding threads readable names
- By Solace #294 DATAGO-76828: add transacted producer support
- Bump versions to spring boot 3.3.0 and spring cloud 2023.0.2

## [4.0.1] - 2024-05-13
### Fixed

- Initialisation error of health indicator resulting in a NPE when checking /actuator/health too early.
- Exception on shutdown after trying to reconnect to the broker for some minutes.

## [4.0.0] - 2024-05-07

### Added

- Support for non-persistent publish and subscribe.
- Support for groups in direct subscription using #share subscription on topics. (https://docs.solace.com/Messaging/Direct-Msg/Direct-Messages.htm -> Shared Subscriptions )
- NACK Support for Consumer bindings (by solace https://github.com/SolaceProducts/solace-spring-cloud/pull/270).
- Reapply subscriptions on temporary queues after reconnect with more than 60 sec interruption.

### Fixed

- Startup error with anonymous queues when broker is under load  (fix: https://github.com/SolaceProducts/solace-spring-cloud/issues/266).

### Changed

- Flatten maven structure into a single project (no need for dependency management or starter).
- Upgrade dependencies:
  - spring-boot: 3.2.5
  - spring-cloud: 2023.0.1
  - sol-jcsmp: 10.23.0
  - others: to latest version
- Deprecated batch messaging processing.

### Removed

- git submodule: solace-integration-test-support (integrated into the test now).
- multimodule with starter (integrated into then main project).

## before 4.0.0

Check forked repository https://github.com/SolaceProducts/solace-spring-cloud
