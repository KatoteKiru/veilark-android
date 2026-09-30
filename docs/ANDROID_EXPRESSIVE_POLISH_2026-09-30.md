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
