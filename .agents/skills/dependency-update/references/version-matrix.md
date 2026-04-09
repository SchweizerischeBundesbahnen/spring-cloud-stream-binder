# Version Compatibility Matrix

## Current Versions (Binder 9.0.0)

| Component | Version | Source |
|-----------|---------|--------|
| Spring Boot | 4.0.4 | Parent BOM |
| Spring Cloud | 2025.1.1 | Dependency BOM |
| Java | 17+ | Compiler config |
| sol-jcsmp (via starter) | 3.0.2 | Direct dependency |
| Jakarta Annotation | 3.0.0 | Direct |
| Lombok | managed | Spring Boot BOM |
| commons-lang3 | managed | Spring Boot BOM |
| Micrometer | managed | Spring Boot BOM |

## Test Dependencies (it_tests profile)

| Component | Version | Source |
|-----------|---------|--------|
| JUnit Pioneer | 2.3.0 | Direct |
| TestContainers Core | 2.0.4 | Direct |
| TestContainers JUnit/Toxiproxy/Solace | 2.0.3 | Direct |
| OkHttp | 5.3.2 | Direct |
| Swagger Codegen Plugin | 3.0.78 | Plugin |
| GSON | 2.13.2 | Direct |
| Wavefront SDK | 3.4.3 | Direct |

## Maven Plugins with Explicit Versions

| Plugin | Version |
|--------|---------|
| maven-compiler-plugin | 3.14.0 |
| swagger-codegen-maven-plugin | 3.0.78 |
| build-helper-maven-plugin | 3.6.1 |
| maven-failsafe-plugin | 3.5.5 |
| properties-maven-plugin | 1.3.0 |
| maven-gpg-plugin | 3.2.8 |
| central-publishing-maven-plugin | 0.10.0 |

## Dependabot Coverage

- **GitHub Actions**: weekly updates
- **Maven**: weekly updates, Spring dependencies grouped
- **Strategy**: Major version bumps for `org.springframework.*` are blocked (require manual review)
