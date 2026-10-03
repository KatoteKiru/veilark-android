# Android visual refinement — 2026-09-30

## Direction

Keep the monochrome Veilark identity and familiar Material layout. Make the
primary action easier to recognise, selected states more decisive, and the
supporting controls visually consistent. Shape changes should follow a tap or
state change, without animation loops while the screen is idle.

The Material 3 Expressive guidance treats shape, typography, component states
and motion as one system:
https://developer.android.com/develop/ui/compose/designsystems/material3

This iteration uses the existing stable Material 3 dependency. It does not
introduce a new UI library or change network configuration.

## Implemented

- Connection emblem: an 88 dp rounded surface replaces the 104 dp circle. Its
  corners change with the connection state, retaining the existing logo.
- Connection button: 60 dp minimum height, a contextual Material icon and a
  short spring transition between its resting and pressed corner radii.
- Connection/error surfaces use their matching foreground colours.
- Engine selection: a compact 4 dp gap, a solid selected surface, quieter
  unavailable choices, and consistent text styling. Layout stacks when its
  available width is below 300 dp or font scale is at least 1.3.
- Engine foreground and background switch together to retain contrast during
  selection; shape supplies the transition.
- Section headings share the same typography, inset and bottom spacing.
- Server and routing choices use the same native RadioButton indicator. Its
  semantics are decorative because the whole selectable row already exposes
  its role and selected state.
- Routing descriptions receive the remaining row width, allowing text to wrap.

## Review configurations

Actual Compose previews in `MainScreen.kt` cover disconnected light mode,
connected TrustTunnel dark mode, connecting light mode, and failure dark mode.
The compact preview uses a 320 dp screen and 1.5 font scale; the failure preview
uses 360 dp and 1.3 font scale. Landscape (800 × 360 dp) and tablet (840 × 900 dp)
configurations are included. Preview profiles contain no credentials.

## Local verification

- Private/OSS Kotlin compilation and private unit tests passed.
- Existing OSS instrumentation tests compile; they were not run on a device in
  this iteration.
- `lintOssDebug` passed. The final addition of preview configurations and
  selectable-group/heading semantics was recompiled separately.
- Static token contrast: selected mode 15.10:1 light / 12.07:1 dark; secondary
  text on the supporting surface 6.52:1 light / 9.47:1 dark. These values do not
  replace a rendered-screen review.

This pass does not use the owner's phone. The previews are configurations for
Android Studio, not evidence that these screens have been rendered on a device.
Local compilation, unit tests and lint are recorded in the project map.
This iteration was published as private rc38 after the owner's release request;
see [rc38 release evidence](RC38_OTA_NOTES.md).

---

# Full Material 3 Expressive pass — 2026-09-30 (second iteration)

Owner requirement: "на андройд материал экспрессив должен быть в полном
объеме". The first iteration above only imitated Expressive with stable
Material 3 1.4 (hand-animated corner radii, `CircularProgressIndicator`). This
iteration switches to the real Expressive APIs.

## Dependency and trade-off

- `androidx.compose.material3:material3` is pinned to **1.5.0-alpha18** in
  `gradle/libs.versions.toml`, overriding the BOM (which still maps to stable
  1.4.0). The Compose BOM moves from 2026.03.01 to 2026.06.01 (Compose 1.11.4),
  the line alpha18 was built against.
- alpha18 is deliberately not the newest alpha: **alpha19 … alpha29 declare
  `minCompileSdk=37`** in their AAR metadata, while the project compiles
  against API 36 and AGP 9.0.1 supports at most 36. Moving to a later alpha
  requires an AGP upgrade and `compileSdk = 37` (targetSdk can stay 36); that
  is a toolchain change left for a separate, owner-approved step.
- In alpha18 three APIs used here are still annotated library-group restricted
  (they are plain public API from alpha19 on): `MaterialExpressiveTheme`, the
  expressive `Shapes` constructor (`largeIncreased`, `extraLargeIncreased`,
  `extraExtraLarge`) and `LinearWavyProgressIndicator`. Their call sites carry a
  narrow `@SuppressLint("RestrictedApi")`; remove it after the upgrade.
- Alpha risk: APIs marked `@ExperimentalMaterial3ExpressiveApi` can change
  between alphas. All usages are opted in at file level in `MainScreen.kt` and
  confined to UI code; ProGuard/R8 needs no rules. The dependency verification
  metadata was regenerated additively.

## Component mapping

| Before (imitation) | Now (Expressive component) |
| --- | --- |
| `MaterialTheme` + static shapes | `MaterialExpressiveTheme`, `MotionScheme.expressive()`, expressive shape ladder, `*Emphasized` type roles |
| Rounded `Surface` emblem with animated corner radius | Emblem clipped to a `Morph` of `MaterialShapes.Cookie4Sided` → `Cookie12Sided`, driven by `motionScheme.slowSpatialSpec()` |
| `CircularProgressIndicator` (emblem, icon buttons) | `LoadingIndicator` |
| Hand-animated Connect button corners | `Button(shapes = ButtonDefaults.shapesFor(MediumContainerHeight))` with medium size tokens (padding, icon, text style) |
| `LinearProgressIndicator` for OTA download | `LinearWavyProgressIndicator` (determinate and indeterminate) |
| Custom selectable engine surfaces | Connected `ToggleButton` group (`ButtonGroupDefaults.connectedLeading/TrailingButtonShapes`), radio semantics and stacked layout at font scale ≥ 1.3 or width < 300 dp retained |
| Small `TopAppBar` on About / licence / log | `LargeFlexibleTopAppBar` + `exitUntilCollapsedScrollBehavior` |
| Icon-only "add" in top bar | `FloatingActionButtonMenu` + `ToggleFloatingActionButton`: enter link, paste, scan QR, choose file |
| Row of icon buttons in the connection sheet | `HorizontalFloatingToolbar` |
| Icon-only dialog actions (✓, ✕, import) | Labelled `TextButton` / `Button` with `ButtonDefaults.shapes()`; labelled tonal source buttons in the import dialog |
| `tween`/`spring` literals in selection rows | `MaterialTheme.motionScheme` fast spatial/effects specs |
| `Modifier.clickable` on rounded cards | `Surface(onClick = …)` / `Surface(selected = …, onClick = …)` (ripple clipped to shape, roles kept) |
| Icon buttons | `IconButton`/`FilledTonalIconButton` with `IconButtonDefaults.shapes()` (press morph); QR scanner overlay uses the same |

SplitButton was not used: no Veilark action has a primary action with a menu
of variants, so forcing one would add a control without a purpose.

## Bugs fixed

- Back: `BackHandler` on About, licence and technical-log screens and for the
  expanded FAB menu; `android:enableOnBackInvokedCallback="true"` enables
  predictive back.
- State: screen/overlay flags, the import URL and the routing draft use
  `rememberSaveable`.
- Technical log: keys come from `technicalLogKeys()` (content + occurrence
  number), fixing the duplicate-key crash; covered by `TechnicalLogKeysTest`.
- Insets: main list and secondary screens add Scaffold start/end/bottom
  padding (navigation bar, gesture area, landscape cutouts).
- Routing dialog: the draft is seeded once and no longer reset by upstream
  recomposition.
- Full-screen dialogs (import, routing) dismiss on a scrim tap; the import
  dialog still refuses while an import is running.
- Accessibility: connection state text is a polite live region (also the
  update status and error title).
- Cold start: `values-night` redefines the base theme with a dark parent and
  window background, removing the white flash in dark mode.
- `windowSoftInputMode` moved from `<application>` (ignored there) to the
  activities.
- The Connect button shows a loading indicator, not the add/power icon, while
  a profile is being checked.
- WARN log accents use the new amber tertiary role; INFO stays primary.
  `PaletteContrastTest` checks tertiary contrast and hue distance.

## Verification (local, no device)

`./gradlew testOssDebugUnitTest lintOssRelease assembleOssDebug
assembleOssDebugAndroidTest` passes. Instrumented tests (including new ones for
engine radio semantics, back from About and duplicate log entries) compile but
were not run: the native VPN libraries are ARM-only and no device was
available. Previews were added for import/update progress, the technical log
with duplicates and the About screen; they have not been reviewed on a device.
