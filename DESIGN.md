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

Use inherited Material 3 Title, Body and Label text appearances, with Android's system sans-serif face and scalable text. The selected title and active title sit outside icon-only cartridges. Preview metadata wraps naturally; never turn an icon tile into a tall text card.

## Layout

The Thor's upper display shows selected-game preview, Search, All Apps or Options; its lower display retains one interactive game library throughout those page changes. The persistent library fragment sits outside the changing upper NavHost, but there is only one navigation stack and scanner. A single-display Games layout retains preview/library/actions; other destinations use the full primary work area.

Controls retain at least 48dp targets; cartridges retain at least 72dp bounds. The grid scrolls independently from selected-game and active-session actions. Enlarged text may grow the action rows rather than shrinking touch targets.

## Elevation & Depth

Cartridges use a white-to-silver gradient, a fine outline and native 2dp elevation; the selected preview uses 3dp. The authored repeating embossed raster is static, not a render loop. Its generation recipe and authorship are embedded in PNG metadata.

**The Still Surface Rule.** No continuous HOME animation, periodic scanning or simulated 3D console scene.

## Shapes

Cartridges are softly beveled squares. Mint brackets mark their four corners, without replacing readable labels or native focus behavior. Primary and secondary actions retain compact rounded native button shapes.

## Components

- **Cartridge:** real SMDH icon; hidden duplicate text on HOME only; native ripple, selected semantics and mint focus brackets. Search retains visible titles.
- **Preview:** one real icon and title, company and translated region; decorative duplicate icons are not accessibility stops.
- **Utility strip:** Games, Search, Options and All Apps change the upper page while retaining the lower library, with native navigation and accessible names.
- **Selected-game shelf:** wrapping title plus real Properties and Open actions. Disable actions for an empty library.
- **Active-session shelf:** visible paused-game identity, a directly resumable icon and a labeled gear opening that title's configuration. Game switching requires an unsaved-progress decision.
- **Android app tile:** real installed launcher icon and wrapping name, sorted alphabetically; normal Android launch intents, no invented utilities.
- **Status strip:** native date/time and a non-animated horizontal four-segment blue battery. The meter snapshots real capacity on resume; unsupported values are hidden. Protect the strip from cutouts and avoid duplicate Android status chrome.

## Do's and Don'ts

### Do:
- Do use actual game icons, metadata and existing native actions.
- Do preserve visible focus, system Back, scalable text and native touch targets.
- Do keep HOME and emulation presentation ownership separate.

### Don't:
- Don't restore tall Material cards as the HOME visual direction.
- Don't fabricate Nintendo utilities, banners or cover art.
- Don't add continuous visual work or change renderer settings for the HOME theme.
