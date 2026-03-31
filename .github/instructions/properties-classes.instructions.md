---
description: "Configuration properties guidelines. Use when adding, modifying, or removing Spring Boot @ConfigurationProperties, consumer/producer properties, or binding property defaults."
applyTo: "**/properties/*.java"
---

# Configuration Properties Guidelines

## API.md Sync (MANDATORY)
Every property change MUST be reflected in `API.md`:
- **New properties**: Add to the appropriate table with name, default value, and description
- **Modified defaults**: Update the default column and add a note
- **Removed properties**: Remove from API.md AND add entry to `MIGRATION.md`

## Spring Boot Conventions
- Use `@ConfigurationProperties` with typed fields
- Provide sensible defaults for ALL properties
- Property names follow Spring Boot kebab-case (`max-processing-time-ms`)
- Validate constraints with Jakarta Validation annotations where applicable

## Deprecation Protocol
1. Mark with `@Deprecated(forRemoval = true, since = "X.Y.Z")`
2. Add `@deprecated` Javadoc tag pointing to the replacement
3. Keep the property functional for one minor release cycle
4. Document in `MIGRATION.md` before removing in the next major version
5. Add to `CHANGELOG.md` under "Deprecated" or "Removed"

## Breaking Changes
- Removing or renaming a property is a breaking change
- Must be documented in both `MIGRATION.md` and `CHANGELOG.md`
- Provide a deprecation period in a minor release before removal in a major release
