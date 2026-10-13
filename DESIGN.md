---
name: Calamansi Classic HOME
description: A silver-white native handheld library with square cartridges and mint selection brackets.
colors:
  home-silver: "#EFF2F4"
  home-silver-deep: "#DFE5E9"
  home-tile-highlight: "#FFFFFF"
  home-tile-base: "#E7ECEF"
  home-outline: "#BEC8CF"
  home-ink: "#283B46"
  home-secondary-ink: "#526670"
  home-mint: "#5CD1A2"
  home-action-green: "#216C52"
  home-action-container: "#D1F2E3"
  home-status-blue: "#36B4DC"
rounded:
  cartridge: "10dp"
  action: "8dp"
spacing:
  small: "8dp"
  panel: "12dp"
  preview: "24dp"
components:
  primary-action:
    backgroundColor: "{colors.home-action-green}"
    textColor: "{colors.home-tile-highlight}"
    rounded: "{rounded.action}"
  cartridge:
    backgroundColor: "{colors.home-tile-base}"
    rounded: "{rounded.cartridge}"
---

## Overview

**Creative North Star: "A real handheld HOME library"**

Classic 3DS material character, implemented with the emulator's existing native Android views. The user's reference sets the silver embossed ground, compact square cartridges and mint corner selection. Real game icons and metadata lead; controls do not pretend to be Nintendo system applications.

**Key Characteristics:**
- Permanently silver-white Games, Search, All Apps and Options.
- Square icon-only cartridges with full-title accessibility labels.
- Static embossed texture, shallow native elevation and immediate mint selection.
- Existing Android navigation, dialogs, system Back and controller affordances.

## Colors

The frontmatter maps the actual `home_colors.xml` palette. `Theme.Azahar.Home.Classic` assigns primary, surface, outline and secondary-container roles. Deep green is the legible action color; mint is the non-text focus/selection signal. System-bar surfaces follow HOME while it owns the primary window.

**The Scoped Silver Rule.** All four HOME destinations remain silver-white under Android dark mode. Native gameplay and separate settings activities retain their own themes; do not rewrite preferences to achieve the HOME appearance.

## Typography

Use inherited Material 3 Title, Body and Label sizes with scalable text. Upper HOME status/title/metadata use bundled Pixelify Sans under SIL OFL 1.1; this is a retro interpretation, not Nintendo's proprietary lettering. The lower library and separate settings retain their existing system sans-serif face. Selected and active titles sit outside icon-only grid tiles. Preview metadata wraps naturally; never turn a lower icon tile into a tall text card.

## Layout

The Thor's upper display shows selected-game preview, Search, All Apps or Options; its lower display retains one interactive game library throughout those page changes. The persistent library fragment sits outside the changing upper NavHost, but there is only one navigation stack and scanner. A single-display Games layout retains preview/library/actions; other destinations use the full primary work area.

Controls retain at least 48dp targets; cartridges retain at least 72dp bounds. The grid scrolls independently from selected-game and active-session actions. Enlarged text may grow the action rows rather than shrinking touch targets.

## Elevation & Depth

Lower cartridges retain their white-to-silver gradient, fine outline and native 2dp elevation. The upper preview uses an extruded notched cartridge with perspective-projected side walls, a bevelled vector front, connector contacts, recessed real icon, moulded back and a fixed slight roll. A native eight-second Y-axis rotation shows actual thickness rather than mirroring the icon onto the back. LTR artwork geometry keeps the icon inside its label in RTL locales. The repeating embossed ground stays static.

**The Still Surface Rule.** Only the upper selected cartridge rotates, while its view is visible and its owning lifecycle is resumed; disabled Android animations keep it still. The lower grid, text and wallpaper never join that animation. No periodic scanning or separate 3D renderer.

## Shapes

Cartridges are softly beveled squares. Mint brackets mark their four corners, without replacing readable labels or native focus behavior. Primary and secondary actions retain compact rounded native button shapes.

## Components

- **Cartridge:** real SMDH icon; hidden duplicate text on HOME only; native ripple, selected semantics and mint focus brackets. Search retains visible titles.
- **Preview:** one real icon inside the dimensional upper cartridge, plus unrotated wrapping title, company and region/platform metadata; decorative art is not an accessibility stop.
- **Utility strip:** Games, Search, Options and All Apps change the upper page while retaining the lower library, with native navigation and accessible names.
- **Selected-game shelf:** wrapping title plus real Properties and Open actions. Disable actions for an empty library.
- **Console indicator:** compact selected-console label above the lower grid, rebound with selection and hidden for an empty selection; not another controller focus target. Backend product names are not ordinary UI copy. Preserve required legal attribution, true links and user-authored values.
- **Active-session shelf:** visible paused-game identity, a directly resumable icon and a labeled gear opening that title's configuration. Game switching requires an unsaved-progress decision.
- **Android app tile:** real installed launcher icon and wrapping name, sorted alphabetically; normal Android launch intents, no invented utilities.
- **Status strip:** native date/time and a non-animated horizontal four-segment blue battery. Capacity snapshots on resume; unsupported values are hidden. The whole strip becomes invisible while its HOME window loses focus or Android reports visible status bars, and returns after immersive reentry. Preserve layout space, cutout safety and existing controller/modal ownership.

## Settings

Follow [Impeccable's native Android guidance](https://github.com/pbakaus/impeccable/blob/main/.agents/skills/impeccable/reference/android.md): Material 3 type/controls, semantic grouping, at least 48dp targets, system Back and scalable text. Options is a compact grouped list, not a cartridge grid or logo-led landing page. Use Emulators, Library & files, Application, and Tools & support; label 3DS-only folders/system files/drivers explicitly.

Console editors share category vocabulary—General, Graphics, Audio, Controls, System and Advanced—with console-specific categories retained. A persistent scope identifies global defaults or game overrides; Cemu's global categories must not pretend to be per-game profiles. Derived form rows rebind from their backend, never from duplicate Android widget saved-state IDs. Preserve each engine's native options, ownership, controller capture, imports, theme and reset semantics.

Picture filter is the deliberate app-wide presentation exception: one selector and before/after preview for Off, CRT scanlines and LCD pixel grid. Preview and gameplay use the same static tiled drawable; do not bundle game art, copy framebuffer pixels continuously or mask the Android settings/control chrome. NativeActivity's in-buffer menu is part of that guest picture. Off has no drawing layer. Internal resolution, renderer processing, saves and engine resets remain separate.


## Do's and Don'ts

### Do:
- Do use actual game icons, metadata and existing native actions.
- Do preserve visible focus, system Back, scalable text and native touch targets.
- Do keep HOME and emulation presentation ownership separate.

### Don't:
- Don't restore tall Material cards as the HOME visual direction.
- Don't fabricate Nintendo utilities, banners or cover art.
- Don't add continuous visual work or change renderer settings for the HOME theme.
