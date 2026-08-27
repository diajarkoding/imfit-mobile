---
name: android-feature-documentation-personal-light
description: Use when documenting or updating a personal Kotlin Android native and Jetpack Compose feature concisely in docs/features.
---

# Android Personal-Light Feature Documentation

Generate or update concise feature documentation for a personal Kotlin Android native project. Use this skill for Jetpack Compose, ViewModel, StateFlow, Hilt, Navigation, Room, DataStore, WorkManager, services, and Android platform integration.

Write to `docs/features/<feature-name>-personal-light.md` using `docs/features/template-feature-documentation-personal-light.md`.

This is a lightweight alternative to the full Android feature-documentation skill. It removes team-process overhead, but it must still describe actual implementation accurately.

---

## Core Rule: Evidence Before Documentation

Document what the repository currently does, not intended behavior or generic Android advice.

### Never do this

- Invent APIs, routes, models, permissions, storage keys, dependencies, feature flags, or behavior not found in code, tests, resources, manifest, or Gradle configuration.
- Assume architectural patterns or libraries from filenames alone.
- Mark a behavior, test, accessibility property, or security control as implemented without evidence.
- Delete required template sections to hide missing information.

### Always do this

- Read the relevant composable, ViewModel/state, navigation, repository/data source, DI wiring, resources, manifest, Gradle configuration, and tests before documenting a feature.
- Use exact Kotlin symbol names and paths.
- Mark unavailable evidence as `N/A` or `TODO/VERIFY`.
- Keep evidence concise: file path and symbol are sufficient; add line numbers when they clarify a claim.
- Prefer practical manual QA steps over speculative process documentation.

---

## Scope Discovery

1. Identify the feature entry point: screen, composable, navigation key, Activity, Fragment, service, or user-provided path.
2. Identify related UI components, ViewModel/UI state, repository/data source, models, navigation, DI, resources, manifest permissions, and tests.
3. Confirm active flavor and source set if the project uses build variants.
4. Ask for clarification only when multiple unrelated feature candidates exist.

For a typical Compose feature, inspect this runtime path where present:

```text
Composable screen -> ViewModel/UI state -> repository -> local/remote source -> model -> UI state
```

Do not include layers that are not present.

---

## What to Document

Keep the document focused on information that helps the project owner safely modify the feature later:

- What the feature does and where it is entered.
- Verified user flow and main UI states.
- Important Compose components and interactions.
- State, data flow, persistence, network, and navigation behavior.
- Business rules, validation, error/empty states, and edge cases.
- Android permissions, sensitive data, notifications, services, workers, or platform behavior when applicable.
- Existing tests and a focused manual QA checklist.
- Dependencies, known risks, and unresolved questions.

Use `N/A` for analytics, feature flags, remote config, rollout, design links, tickets, and other processes when they are not implemented.

---

## Evidence and Confidence

Use these confidence labels only when useful:

- `Verified`: direct code, test, config, manifest, or resource evidence.
- `Partial`: only part of the behavior was found.
- `Inferred`: derived from a type or call graph, not runtime evidence.
- `TODO/VERIFY`: needs human or runtime confirmation.

For technical claims, add an Evidence Map row:

```md
| Claim | Source | Confidence |
| --- | --- | --- |
| Collects UI state with `collectAsStateWithLifecycle` | `presentation/ui/example/ExampleScreen.kt:42` | Verified |
```

---

## Documentation Workflow

1. Read the existing personal-light document first if it exists.
2. Build a small file inventory and classify UI, state, data, navigation, DI, config, and tests.
3. Read source in runtime order and reconstruct only verified behavior.
4. Fill every section in the personal-light template.
5. Write `N/A` or `TODO/VERIFY` instead of guessing.
6. Verify paths, symbols, Gradle dependencies, permissions, and navigation references before saving.
7. Update the changelog and last verified commit when available.

---

## Android-Specific Checks

Check only when relevant to the feature:

- Compose state collection, recomposition-sensitive behavior, loading/error/empty UI, dialogs, sheets, and accessibility labels.
- `NavGraph`, navigation keys, deep links, notification intents, and back behavior.
- ViewModel state/events, coroutine scope, lifecycle collection, and cancellation.
- Room, DataStore, files, cache, network requests, serialization, and error mapping.
- Hilt modules and flavor-specific repository implementations.
- `AndroidManifest.xml`, runtime permissions, services, receivers, workers, notifications, and media/camera access.
- Unit, repository/DAO, Compose UI, and instrumented tests.

---

## Quality Gate

Before saving, ensure:

- Every claimed path and Kotlin symbol exists.
- Every described navigation path is supported by code or tests.
- Every checked item has direct evidence.
- Missing information is explicitly `N/A` or `TODO/VERIFY`.
- The document does not contain ticket ownership, PIC, review workflow, rollout, or analytics boilerplate unless the repository actually uses it.
- The Manual QA section includes meaningful feature-specific checks.

---

## Final Report

Report only:

- Output path.
- Android module/flavor and feature scope inspected.
- Verified behavior.
- Important `TODO/VERIFY` items or risks.
- Whether existing tests were found.

---

## Boundaries

Use this skill only for personal Kotlin Android native feature documentation. Do not use it for backend-only documentation, general README files, ADRs, product requirement documents, or speculative documentation without repository access.
