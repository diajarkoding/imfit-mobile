# IMFIT design system

## Ownership

- `app/src/main/java/com/diajarkoding/imfit/theme/Color.kt`: raw brand colors and semantic light/dark palettes. Global primary remains `#3179FF`; Training Hub accents preserve the Pencil design.
- `theme/DesignSystem.kt`: public colors, Inter typography, spacing, sizes, radii, gradients, opacity and motion tokens.
- `theme/Theme.kt`: installs Material colors, custom palette and `LocalIsDarkTheme`; Material and Training Hub colors share the same transition.
- `theme/Type.kt` and `theme/Shape.kt`: adapters of the shared tokens.

Use `MaterialTheme.typography` for standard text and `IMFITTypography` for named product styles. Do not define screen-local font families, palettes or raw color values. Inter is bundled locally in `res/font/inter.ttf`.

## Controls

Ordinary text/CTA buttons have a **48 dp minimum height**, using `IMFITSizes.buttonHeight` and `heightIn`, not a fixed height. Primary, gradient, outlined and secondary variants accept the same height override, clamped to the minimum interactive target; compact buttons also have a 48 dp minimum. Content may grow for font scaling and translated labels. Use 12 dp vertical padding for normal buttons and 8 dp for compact buttons.

Icon controls use `IMFITSizes.minimumInteractiveTarget` (48 dp); glyph size is a separate token. Navigation cards, anatomy tiles and bottom navigation retain their own size tokens.

All pages with an app bar or back/title header share `IMFITSpacing.headerContentGap` (**8 dp**)
before their first content, including Register and legacy header pages. This replaces the earlier
main-tab-only 12 dp / 0 dp revisions. Keep internal app-bar padding and system insets; replace
old content-top padding rather than adding another layer. Dialog/sheet content is not affected.
Only Home shows the app-bar profile avatar/action; Exercises and Progress do not. The Progress
profile row remains available. The root
exercise grid uses only its remaining Scaffold viewport: 3×4 categories on normal phone
widths, 8 dp row gaps and scaled square artwork. Short/narrow or large-font layouts may
scroll rather than clip labels or shrink their text. Do not reinstate a fixed 154 dp row minimum.

Login/Profile language and theme controls share `IMFITPreferenceSizes`: visual track
72×36 dp, thumb 30 dp, with touch targets at least 48 dp. Role/state and selection
behavior remain owned by each control. The opt-in `RoutineButton.matchOutlineToContent`
makes only Add exercise outlined borders follow their text color, including disabled state.

## Modal backdrops

All app dialogs, both bottom sheets and the fullscreen sync overlay use a **black Compose
scrim at 50%**, owned by the single token `IMFITOpacity.scrim = 0.50f`, without blur.
This supersedes the earlier native 60% dialog / 28% sheet split and conditional-blur proposals.

| Modal | Backdrop owner |
| --- | --- |
| Cancel active session (`IMFITDialog`) | `IMFITPlatformDialog` |
| Rest settings, countdown and workout confirmations | `IMFITPlatformDialog` |
| Create routine in Home and delete routine in Detail (`RoutineDialog`) | `IMFITPlatformDialog` |
| Birth-date picker (`AuthBirthDatePicker.kt`) | `IMFITPlatformDialog` |
| Shared `IMFITInputDialog` (no current screen caller) | `IMFITPlatformDialog` |
| Photo-source and muscle-filter bottom sheets | Material `scrimColor = Scrim.copy(alpha = IMFITOpacity.scrim)` |
| Fullscreen sync progress | Compose background `Scrim.copy(alpha = IMFITOpacity.scrim)` |

The wrapper retains a native Dialog for focus/back/security but renders its own fullscreen
Compose scrim. Its native `FLAG_DIM_BEHIND` is off and `dimAmount` is zero; blur is off.
It restores the previous dim/blur attributes and flags when removed. Never enable native
dim over this scrim. The host is edge-to-edge/full width; content alone uses safe/IME
insets and its existing width constraints. The platform-width option uses framework
theme sizing with the framework preferred-dialog-width fallback.
Outside taps are handled by the scrim only when the caller allows them. Content taps
are consumed without dismissing or stealing child controls. Native back policy and
theme-aware card surfaces remain; dark cards are not forced white.
Preserve each caller's dismissal and processing guards: a shared backdrop does not make
the countdown dismissible or apply an unconfirmed draft.

`SyncProgressDialog` is included in the same 50% policy; only its shared opacity changes,
not sync state or operations. Android-owned permission, camera and document-picker UI
remains outside app backdrop ownership.

## Platform bridge and assets

`res/values/colors.xml` mirrors only the colors required by platform launch/window/launcher resources. `values-night/colors.xml` supplies the dark window background. XML cannot reference Kotlin constants; keep this bridge aligned with `Color.kt`.

Auth, profile, workout and history gradient roles are owned by `IMFITGradients`. Training Hub banner colors are named tokens; its original Pencil image contains the artwork and background. Anatomy assets retain their illustration colors and are not globally tinted.

Semantic status text/container pairs are checked for contrast by `SemanticPaletteTest`. Existing unit and instrumentation tests remain the behavior checks; visual/font-scale verification is also required on a device.
