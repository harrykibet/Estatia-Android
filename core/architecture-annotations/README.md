# Estatia Architecture Annotations

`:core:architecture-annotations` is the lightweight, pure-Kotlin annotation API used by production modules to declare architectural identities.

It contains only annotation declarations such as:

- `@Data.DomainModel`, `@Data.UiState`, and the other data-role annotations
- `@Identity.Repository`, `@Identity.Contract`, and the other structural identity annotations
- `@Logic.Utility`, `@Logic.Mapper`, and the other logic-role annotations
- `@Safety.AllowedArchitectureDependency`
- `@Ui.UiScreen`, `@Ui.UiComponent`, and the other UI-role annotations

The package name remains `com.estatia.realestate.apps.core.architecture.annotations` so existing source imports do not need to change.

## Responsibility boundary

This module deliberately has no dependency on Android, `:core:common`, `:core:model`, or the architectural enforcement engine.

The annotations are compile-time metadata consumed by source code and inspected by Lint, KSP, and Konsist. Their definitions do not own the rules or decide whether a declaration is valid.

The authoritative law registry, rationale, risk/confidence/enforcement metadata, and structural policy remain in `:core:architecture`.

## Dependency direction

Production modules may depend on this lightweight API. Enforcement tools depend on `:core:architecture` for policy metadata and inspect the annotations by their qualified names.
