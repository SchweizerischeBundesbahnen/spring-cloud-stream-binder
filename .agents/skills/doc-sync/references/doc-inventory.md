# Documentation Inventory

## Documentation Files

| File | Content | Update Trigger |
|------|---------|----------------|
| `API.md` | Properties, headers, metrics, usage guide | Property/header/metric changes |
| `README.md` | Overview, version table, Maven coordinates | Releases, version bumps in pom.xml |
| `CHANGELOG.md` | Release notes per version | Every PR with user-facing changes |
| `MIGRATION.md` | Breaking changes between major versions | Major version releases |
| `DEVELOPER.md` | Build setup, test commands, release process | Toolchain changes |
| `COMPARE_WITH_SOLACE.md` | Fork differences vs upstream Solace binder | Feature additions/removals |
| `doc/SPECIAL_HEADER.md` | SolaceHeaders and SolaceBinderHeaders constants | Header changes |

## Version Sync Points (pom.xml → README.md)

| pom.xml Source | README Column |
|----------------|---------------|
| `<parent><version>` (spring-boot-starter-parent) | Spring Boot |
| `<spring-cloud.version>` property | Spring Cloud |
| Transitive via `spring-boot-starter-solace-client-config` | sol-jcsmp |
| `<version>` (project version) | Spring Cloud Stream Binder Solace |

## CHANGELOG Format Reference

```markdown
## [version] (YYYY-MM-DD)

### Breaking Changes
- **Category**: Description of breaking change

### Added
- **Category**: Description of new feature

### Changed
- **Category**: Description of modification

### Removed
- **Category**: Description of removal

### Fixed
- **Category**: Description of bug fix
```

## Property Classes → API.md Mapping

| Source Class | API.md Section |
|-------------|----------------|
| `SolaceConsumerProperties` | Solace Consumer Properties |
| `SolaceProducerProperties` | Solace Producer Properties |
| `SolaceCommonProperties` | Common Properties |

## Header Classes → Documentation Mapping

| Source Class | API.md Section | Also In |
|-------------|----------------|---------|
| `SolaceHeaders` / `SolaceHeaderMeta` | Solace Message Headers | `doc/SPECIAL_HEADER.md` |
| `SolaceBinderHeaders` / `SolaceBinderHeaderMeta` | Solace Binder Headers | `doc/SPECIAL_HEADER.md` |

## Metrics Source → API.md Mapping

| Source Class | API.md Section |
|-------------|----------------|
| `SolaceMessageMeterBinder` | Solace Binder Metrics |
| `SolaceMeterAccessor` | Solace Binder Metrics |

## Auto-Configuration → API.md Mapping

| Source Class | API.md Section |
|-------------|----------------|
| `JCSMPSessionConfiguration` | Solace Session Properties |
| `SolaceHealthIndicatorsConfiguration` | Connection Health-Check Properties |
