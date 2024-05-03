# Changelog

All notable changes to this project will be documented in this file.

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