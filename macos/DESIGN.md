---
name: Veilark macOS
description: Monochrome desktop VPN client with native material navigation and a Compose task body.
colors:
  light-primary: "#303238"
  light-on-primary: "#FFFFFF"
  light-primary-container: "#E6E7EA"
  light-secondary-container: "#E3E4E7"
  light-surface: "#F7F7F8"
  light-on-surface: "#1B1C20"
  light-surface-variant: "#E8E8EC"
  light-on-surface-variant: "#565860"
  dark-primary: "#E5E5EA"
  dark-on-primary: "#202126"
  dark-primary-container: "#34353C"
  dark-secondary-container: "#35363D"
  dark-surface: "#17181C"
  dark-on-surface: "#ECECF0"
  dark-surface-variant: "#38393F"
  dark-on-surface-variant: "#BBBCC5"
typography:
  headline:
    fontSize: "24sp"
    lineHeight: "30sp"
    fontWeight: 600
  title:
    fontSize: "20sp"
    lineHeight: "26sp"
    fontWeight: 600
  body:
    fontSize: "13sp"
    lineHeight: "19sp"
  label:
    fontSize: "13sp"
    lineHeight: "18sp"
    fontWeight: 500
  small-label:
    fontSize: "11sp"
    lineHeight: "16sp"
rounded:
  connection-panel: "18dp"
  choice-card: "12dp"
  action: "10dp"
  native-navigation: "8pt"
  native-glass: "16pt"
spacing:
  body-horizontal: "32dp"
  body-vertical: "26dp"
  connection-inset: "24dp"
  overview-gap: "22dp"
  engine-gap: "8dp"
---

# Design System: Veilark macOS

## Overview

Scope: this file describes the implemented `macos/` client only. It is not an Android design directive or a proposal to rewrite the application in SwiftUI. The brief is Apple-native macOS/Liquid Glass character, a preserved monochrome Veilark brand, and smooth, efficient interaction.

AppKit owns navigation material, SF Symbols, native buttons and system typography. Compose owns the task body, session behavior and forms. Navigation recedes; the connection state and single primary action lead the overview.

Source of truth: `native/chrome.m`, `src/main/kotlin/app/veilark/macos/Main.kt`, `src/main/kotlin/com/example/veilark/theme/Theme.kt`, and `docs/NATIVE_GLASS_DESIGN.md`. Frontmatter records observed Compose tokens plus explicitly labelled native point dimensions; it is not CSS to implement in this native client. Refresh it when these sources change.

Visual evidence: `C:/AI-Agent/reports/mac-native-glass-20260930/final26/reports/veilark-window.png` shows the full light/English empty-profile overview, rounded native glass navigation and unobstructed Compose body. Native CI run `36684981729` passed macOS 14/26 for source `7f4031f`; the macOS 26.6.2 integration result reports material mode `2` (true `NSGlassEffectView`) with one test and zero failures. This establishes that tested material branch and the pictured state, not full UX acceptance. Physical-Mac/VoiceOver, populated-profile workflows, other appearance/language states, connected VPN and OTA acceptance remain unverified.

## Colors

The Compose light/dark palette uses graphite, near-white and cool neutral greys. Primary color is the high-contrast action and selected-state foreground; tonal containers distinguish selections without introducing a brand accent.

Native navigation deliberately uses dynamic `NSColor.labelColor`, `secondaryLabelColor` and `windowBackgroundColor`. Selection uses the user's system accent at 12% opacity, not a fixed Veilark blue. Appearance/accent notifications refresh native layer colors. Compose uses its light/dark theme; unspecified Material error and outline roles remain library defaults. Failures may therefore use semantic error color without changing the monochrome brand.

**The Material Boundary Rule.** Brand stays monochrome; system material and the user's selection accent remain native, not recolored into a decorative brand gradient.

## Typography

Native chrome uses `NSFont.systemFont`: brand at 20pt semibold, navigation at 13pt regular/selected semibold, status at 12pt medium, subtitle at 11pt and shortcut hint at 10pt. Native labels allow two lines and tail truncation.

Compose uses the desktop hierarchy in `MacTypography`; no custom font family is declared. Headline and connection-title roles are in frontmatter. Section titles use 14sp/20sp medium (often explicitly semibold); supporting body uses 12sp/17sp, with 13sp/18sp medium action labels. Do not document the Compose body as guaranteed SF Pro: its family is the Compose default.

## Layout

The window opens at 1040 × 720dp with an AWT minimum of 900 × 620. Native navigation occupies a reserved 240dp left lane: the material panel is 224pt wide, inset 8pt from the leading, top and bottom edges. Its content has 12pt horizontal insets, 20pt top/16pt bottom insets and a 6pt stack gap. Navigation rows are 36pt high. Compose retains a vertical divider and a flexible body with the frontmatter insets.

The overview stacks heading, connection panel, two equal-width engine choices and active-profile content, with secondary profile/diagnostic actions below. The connection panel is a horizontal status/action row; engine choices have an 8dp gap. Forms and diagnostic lists use their existing scrollable sections rather than a new mobile or adaptive navigation model. If native attachment fails, the Compose sidebar remains available (220–240dp wide).

## Elevation & Depth

Native material is confined to navigation. Runtime-available public `NSGlassEffectView` supplies macOS 26 glass; otherwise `NSVisualEffectView` uses sidebar material, behind-window blending and active-window state. Reduce Transparency selects an opaque system-background view. Glass radius is applied only when the public selector is available.

The Compose body stays tonal and mostly flat: the normal connection surface uses `surfaceVariant` at 38% opacity; selected engine choices use `secondaryContainer`. This is a tinted fill, not backdrop blur or Liquid Glass. No custom body-card shadow system is defined.

## Shapes

Rounded rectangular surfaces distinguish hierarchy: the connection panel is broad and soft, engine choices are compact, and the primary connection button has smaller corners. Native navigation keeps its own point-based radii. Profile rows use 10dp corners; import panels use 14dp. Engine cards use a 1dp outline, with primary at 55% opacity when selected. Do not homogenize these into a single global radius.

## Components

- Native navigation: Overview, Profiles, Routing, Diagnostics and Settings are labelled `NSButton`s with SF Symbols, Cmd1–Cmd5 equivalents and confirmed selected state. The monochrome Veilark mark is a template image. System accent fill and semibold type reinforce selection.
- Connection panel: state icon and explicit status text accompany one Connect/Cancel/Disconnect action. Busy/unavailable states disable the appropriate actions; health check is available only when connected and not busy. Failed/degraded states show semantic error treatment and actual detail.
- Engine choices: equal-width radio-like cards with icon, title, explanation and selected checkmark; engine switching is disabled while connection state blocks offline changes. Semantics expose role, selection and localized state descriptions.
- Selectable profile rows: neutral hover fill, tonal selected fill, selected checkmark and 1dp primary keyboard-focus border. Preserve both text/state semantics and visible selection; color is not the only signal.
- Forms, tooltips and dialogs: retain the existing Material Compose controls, localized labels, validation/disabled states, destructive confirmation and icon-action descriptions. Do not describe them as native AppKit or SwiftUI widgets.

Motion is restrained: the fallback Compose navigation animates state color for 140ms, or 0ms with Reduce Motion. Native chrome adds no continuous animation or timer loop; installation attempts are bounded and observers are removed with the sidebar. Accessibility-display changes trigger preference refresh and native panel reinstallation. Native buttons provide accessibility labels; Compose icon actions provide descriptions/tooltips. VoiceOver traversal, focus interoperability, enlarged text, contrast and accessibility-toggle behavior still need physical-device acceptance.

## Do's and Don'ts

- Do preserve the native-navigation/Compose-body ownership boundary and fallback.
- Do retain the monochrome logo, compact hierarchy, explicit connection states and keyboard/selection cues.
- Do test both appearances, RU/EN, minimum window, Cmd1–Cmd5 and accessibility display settings when changing layout or material.
- Don't turn body cards, forms or the entire window into simulated glass.
- Don't add continuous decorative motion or claim performance measurements not obtained.
- Don't promote the confirmed macOS 26 true-glass CI branch and light/English empty overview into physical-device, VoiceOver, profile-workflow, connected-VPN or OTA acceptance.
