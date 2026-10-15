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

## Platform bridge and assets

`res/values/colors.xml` mirrors only the colors required by platform launch/window/launcher resources. `values-night/colors.xml` supplies the dark window background. XML cannot reference Kotlin constants; keep this bridge aligned with `Color.kt`.

Auth, profile, workout and history gradient roles are owned by `IMFITGradients`. Training Hub banner colors are named tokens; its original Pencil image contains the artwork and background. Anatomy assets retain their illustration colors and are not globally tinted.

Semantic status text/container pairs are checked for contrast by `SemanticPaletteTest`. Existing unit and instrumentation tests remain the behavior checks; visual/font-scale verification is also required on a device.
