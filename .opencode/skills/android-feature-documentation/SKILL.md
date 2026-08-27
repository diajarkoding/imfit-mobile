---
name: android-feature-documentation
description: Use when documenting or updating a Kotlin Android native and Jetpack Compose feature from repository evidence in docs/features.
---

# Android Feature Documentation

Generate or update `docs/features/<feature-name>.md` for a Kotlin Android native feature by reading its actual implementation. Use this skill for Jetpack Compose, ViewModel, Hilt, Navigation, Room, DataStore, WorkManager, services, and Android platform integration.

This skill documents **what the code currently does**. Do not document imagined intent, planned behavior, or generic best practices unless explicitly labelled as a recommendation, gap, or `TODO/VERIFY`. The canonical output structure is `docs/features/template-feature-documentation.md`.

---

## Core Rule: Zero Hallucination

Documentation is a source of truth for engineers, QA, PMs, and future agents. A wrong claim is worse than an incomplete section.

### Never do this

- Invent endpoint, model field, route, navigation key, permission, analytics event, feature flag, storage key, class, package, Gradle dependency, or file path not read from the repository.
- Infer acceptance criteria, business rules, edge cases, user flow, Compose behavior, or Material design requirements without code, test, comment, existing documentation, design, or user-provided evidence.
- Assume MVVM, MVI, Clean Architecture, Hilt, Room, Navigation Compose, Retrofit, Ktor, WorkManager, DataStore, or Compose solely from naming conventions.
- Copy unreplaced boilerplate into a feature document.
- Treat generated sources, build outputs, previews, or a flavor-only implementation as the primary source when the active runtime path is available.
- Hide uncertainty by deleting template sections or checking unverified items.

### Always do this

- Read real source, manifest, Gradle configuration, resources, tests, and active flavor wiring before writing.
- Use exact Kotlin symbols, package paths, navigation keys, composable names, ViewModel state names, repository methods, permissions, endpoints, dependency aliases, and resource identifiers.
- Mark missing evidence with `> ⚠️ TODO/VERIFY: <what is missing and why>`.
- Keep snippets under 15 lines and use them only when they clarify contracts, state, serialization, DI, permissions, or error handling.
- Preserve every section in the canonical template. Write `N/A` or `TODO/VERIFY` when evidence is unavailable.
- Report verified, partial, inferred, and unverified coverage.

---

## Evidence and Confidence Rules

Every non-trivial technical claim must be traceable to repository evidence. Record file path, relevant symbol or line, evidence type, and confidence.

### Evidence types

- `code`, `test`, `config`, `manifest`, `resource`, `comment`, `existing-doc`, `design`, `generated`, `inferred-from-model`, `runtime-sample`

### Confidence levels

- `Verified`: directly observed in active production code, tests, manifest, or configuration.
- `Inferred`: derived from a type, model, call graph, or generated output but not confirmed by runtime data.
- `Partial`: only part of the flow or behavior is observed.
- `Unverified`: cannot be confirmed from available files.
- `TODO/VERIFY`: requires human confirmation or an external source.

### Evidence Map format

```md
## Evidence Map

| Claim | Source | Evidence Type | Confidence |
| --- | --- | --- | --- |
| Uses `LoginViewModel` state | `presentation/ui/auth/LoginViewModel.kt:18-96` | code | Verified |
| Navigates to `Profile` | `presentation/navigation/NavGraph.kt:197-205` | code | Verified |
| Persists a preference via DataStore | `theme/ThemeManager.kt:15-30` | code | Verified |
```

---

## Conflict Resolution Policy

When sources disagree, use this priority:

1. Current production code path for the active build flavor.
2. Tests that exercise current production code.
3. Active Gradle, manifest, flavor, build-type, and runtime configuration.
4. Design files, existing documentation, comments, and previews.
5. Ticket text or external description.

Document material conflicts under `Open Questions & Risks`; do not silently choose a source.

---

## Workflow Overview

1. Preconditions and Android project topology detection.
2. Feature scope discovery.
3. File inventory and classification.
4. Evidence collection.
5. Behavior reconstruction.
6. Template filling.
7. Quality gate and self-check.
8. Save or update documentation.
9. Final report.

---

## Phase 0 — Preconditions and Android Project Topology Detection

Verify a Kotlin Android repository is available. Do not generate speculative documentation without source access. Use read-only inspection until the final save step.

### Detect project topology

Inspect `settings.gradle.kts`, root and module `build.gradle.kts`, `gradle/libs.versions.toml`, and `AndroidManifest.xml`. Classify the project as:

- Single Android application
- Multi-module Android project
- Android monorepo
- Unknown / needs confirmation

Identify build variants, product flavors, source sets, Compose versus View system usage, dependency injection, and the active module containing the feature. Do not assume code in `app/src/main` is the active implementation when a flavor overrides it.

### Shell safety

- Quote user-provided paths.
- Do not run application, Gradle, or deployment tasks unless requested.
- Do not run destructive commands.
- Prefer read-only inspection of source, Gradle config, manifest, resources, and tests.

---

## Phase 1 — Identify Feature Scope

If the user provides a module or path, use it as the initial scope and verify it exists. Otherwise search likely Android feature surfaces:

- `presentation/ui`, `presentation/components`, `presentation/navigation`
- `domain`, `data`, `di`, `core`, `theme`
- `src/main`, flavor source sets, `src/test`, and `src/androidTest`

Search by case variants in Kotlin, XML, resource, and test files. Also inspect navigation destinations, ViewModels, repositories, Hilt modules, string resources, manifests, and Gradle dependencies. If scope is ambiguous, ask for a feature path, screen/composable, navigation key, or ticket identifier.

Build a candidate map before deep reading:

| Role | Files |
| --- | --- |
| Entry composable/activity/fragment | |
| UI components | |
| ViewModel/state | |
| Repository/data source | |
| Model/entity/DTO | |
| Navigation | |
| DI/runtime wiring | |
| Resources/localization | |
| Permission/platform integration | |
| Tests | |

---

## Phase 2 — File Inventory and Classification

List relevant files before reading deeply. Classify each as:

- Activity, Fragment, composable screen, or reusable Compose component
- ViewModel, UI state, event/action, reducer, or use case
- Repository, local/remote data source, DAO, Room entity, DTO, mapper
- Domain model or service
- Navigation or deep link
- Hilt/Koin/manual dependency injection wiring
- Gradle, manifest, flavor/build-type, resource, localization, or theme
- Permission, notification, foreground service, broadcast receiver, worker, or other platform integration
- Analytics, feature flag, test, existing documentation, or generated file

For large features, build a coverage map and prioritize entry points, state, data flow, navigation, runtime wiring, and tests.

---

## Phase 3 — Evidence Collection

Extract only directly observed facts. Use `N/A` or `TODO/VERIFY` when a section lacks evidence.

### Architecture and state

Read feature folders, UI state types, ViewModels, composables, use cases, repositories, and Hilt modules. Record actual state fields and transitions such as loading, success, error, empty, validation, retry, and transient events. Confirm flow collection patterns such as `collectAsStateWithLifecycle`, `StateFlow`, `SharedFlow`, LiveData, or callbacks from code.

### UI and Compose

Read composables directly. Capture screen entry points, reusable components, user actions, dialogs, sheets, snackbars, loading/error/empty states, Material components, theme usage, responsive behavior, and accessibility evidence. Do not infer visual behavior from previews alone.

### Data, networking, and persistence

Inspect models, entities, DTOs, DAOs, repositories, mappers, API clients, serialization, Room migrations, DataStore keys, and flavor-specific implementations. Record only actual endpoints, request/response shapes, and error mappings. Mark shapes inferred only from Kotlin types as `Inferred`.

### Navigation and runtime wiring

Inspect Navigation Compose keys/routes, `NavGraph`, navigation handlers, deep links, activities, manifest entries, notification intents, and DI modules. Build Mermaid flows only from verified navigation calls, handlers, and tests.

### Permissions, platform, security, analytics, and performance

Inspect `AndroidManifest.xml`, runtime permission checks, services, workers, receivers, notification channels, storage, auth/session handling, logging, feature flags, analytics calls, lazy lists, paging, caching, image loading, and background work. Document only evidence present in code or configuration.

### Tests and generated files

Read unit tests, instrumented tests, Compose UI tests, and flavor-specific test source sets. Prefer hand-written source over generated artifacts. Use generated sources only when they contain necessary runtime details unavailable elsewhere, and mark them secondary evidence.

---

## Phase 4 — Behavior Reconstruction

Reconstruct the verified behavior before writing:

- **User flow:** entry point → navigation/action → destination/result.
- **State flow:** initial → loading → success/error/empty/validation and transitions.
- **Data flow:** UI → ViewModel/controller → use case/repository → local/remote source → state → UI.
- **Error and empty handling:** derive from concrete `Result`, exceptions, sealed types, UI state, and tests.

Include only layers that exist in the implementation.

---

## Phase 5 — Fill the Canonical Template

Use `docs/features/template-feature-documentation.md` as the canonical structure.

- Do not delete sections.
- Remove instructional placeholders only after filling them with verified, feature-specific content.
- Use `N/A` or `TODO/VERIFY` for missing evidence.
- Mark checkboxes complete only when evidence confirms them.
- Label recommendations and gaps explicitly.
- Use Android-specific evidence paths, Kotlin symbols, navigation keys, Gradle aliases, manifest permissions, and resource names.

### Status rubric

- `Done`: implementation, runtime wiring, error handling, and minimal tests or release evidence exist.
- `Implemented - Untested`: code exists and appears wired but tests are missing.
- `In Progress`: main flow exists but implementation remains incomplete.
- `Partial`: route, source, API, flag, or runtime wiring is incomplete.
- `Draft`: files exist but no working flow is confirmed.
- `Deprecated`: explicitly marked deprecated.
- `Unknown`: scope cannot be verified.

---

## Phase 6 — Quality Gate and Self-Check

Before saving, verify:

- Every file path, Kotlin symbol, resource, navigation key, permission, endpoint, and dependency mentioned exists.
- Every checked item has evidence.
- Every unverified section says `N/A` or `TODO/VERIFY`.
- Existing documentation was compared against current code when updating.
- Conflicts are recorded in `Open Questions & Risks`.
- Generated files are not primary evidence when source is available.
- Claims about permissions, security, privacy, and flavor behavior are backed by manifest/config/code evidence.

Capture the current commit when available and verify the active module/flavor used for the document.

---

## Phase 7 — Save or Update Documentation

Create feature documentation at:

```text
docs/features/<feature-name-kebab-case>.md
```

When updating an existing document, read it first, compare every factual claim with current code, retain only still-true history, update version/date/commit metadata, and add a changelog row. Never overwrite existing documentation blindly.

---

## Phase 8 — Final Report to User

Report briefly:

- Output path and Android module/flavor scope inspected.
- Verified, partial, and `TODO/VERIFY` sections.
- Important conflicts or runtime risks.
- Useful next steps, if any.

---

## Large Feature Handling

For large Android features:

1. Build a complete file inventory.
2. Classify UI, state, domain, data, navigation, DI, resources, platform integrations, and tests.
3. Read entry points and public APIs first.
4. Read ViewModels/state and repositories/data sources next.
5. Inspect Gradle, manifest, flavor, and DI wiring.
6. Summarize evidence incrementally.
7. Avoid build/generated output unless necessary.
8. Produce partial documentation with an explicit Coverage Map when evidence is incomplete.

---

## Boundaries

Use this skill only for Kotlin Android native features, including Jetpack Compose and Android platform integrations.

Do not use it for:

- Backend-only features
- Web-only features
- React Native features
- General README files
- ADRs
- Product requirement documents
- API reference documentation unrelated to an Android feature
- Speculative documentation without repository access

For another technology, use an appropriate technology-specific documentation workflow.
