# Product

<!-- impeccable:product-schema 1 -->

## Platform

android

## Users and Purpose

The user plays 3DS games on an AYN Thor dual-screen Android handheld. Calamansi is this fork of Azahar, retaining its installed package identity and user-data paths. Its persistent lower console HOME library selects/resumes games; the upper screen switches game preview, Search, All Apps or Options. All Apps launches real installed Android applications. The user approved an optional Android HOME role: physical HOME opens Calamansi in and outside games, with an action to access the previous launcher.

## Capabilities and Constraints

Retain XML Views, RecyclerView and existing activity-owned navigation. Keep native emulation surfaces, input mappings, graphics fixes, HD texture loading, save formats and `/sdcard/citra-emu` access unchanged. HOME owns a temporary secondary-display Presentation; emulation owns its own screens. Phones must retain a complete single-screen interface. Do not fabricate Nintendo system applications, animated banners or cover art: use actual SMDH game icons and metadata.

## Brand Commitments

The user's classic 3DS HOME Menu image is the visual reference: silver-white embossed grid, square icon-only tiles, compact utility controls, mint corner selection and a static horizontal battery icon. Games, Search, All Apps and Options share this permanently light theme, even under Android dark mode. Preserve saved theme preferences for native gameplay and separate settings activities. Battery level is real, not a fictitious full-charge decoration.

## Accessibility and Operating Context

Touch and controller navigation must both work. Preserve full game names as accessible labels and outside the icon-only tiles, visible focus, 48dp controls and usable enlarged text. The Thor is charging; leave it asleep between bounded verification runs. No continuous HOME animation or background scanning.

## Evidence on Hand

The design and ownership contract is in `docs/superpowers/specs/2026-10-10-3ds-home-ui-design.md`. Real device captures and save/config hash manifests are in the sibling `azahar-fates-evidence/3ds-home-ui-20261010/device` folder. Performance and rendering claims require actual device evidence, not mockups.
