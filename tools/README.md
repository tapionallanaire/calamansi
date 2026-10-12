# Tools

This directory contains several scripts which are intended to both document and ease the convenience of certain development processes.

Most of the scripts in this directory assume that your current working directory is the Azahar root directory and the script is being called via `./tools/xxx.sh`.

## Pre-release checklist

- [ ] Update compatibility list
- [ ] Update translations if either of the following apply:
    - This is a major release (e.g. 2125.x --> 2126.0)
    - This is a minor release, but the release branch hasn't yet diverged from master

## Post-release checklist

- [ ] Publish to Google Play Store
- [ ] Publish files to the official Internet Archive account (incl. changelog as .md file)
- [ ] Publish to Flathub
- [ ] Force-push tagged commit to retroarch-live branch

### Note:

For reasons unknown, some part of the translation update process can inexplicably produce files with different content depending on the environment in which it is running, even when using the same version of the tool and the same distro.

For consistency, when updating the translations to be committed to the repository, always perform the update within the the Docker environment we use for Azahar's CI (`opensauce04/azahar-build-environment:latest`).

## Frame-matched rendering regression checks

`verify-fates-rendering.py` requires Python, Pillow and NumPy. Compare PNG exports
with identical filenames, dimensions, savestate and emulated frame numbers.
Load Fates slots 1 and 2, capture the same subsequent frames for each configuration,
and flush the rasterizer before exporting guest framebuffer data. Keep capture
instrumentation out of release builds.

```sh
python tools/verify-fates-rendering.py reference/ default/ --effect accurate
python tools/verify-fates-rendering.py bloom-on/ bloom-off/ --effect bloom --pattern 'host-*.png'
python tools/verify-fates-rendering.py fxaa-off/ fxaa-on/ --effect fxaa --pattern 'host-*.png'
```

Use `--unchanged-region X Y WIDTH HEIGHT` (repeatable) with bloom comparisons
to guard opaque dialogue/UI rectangles. Accuracy checks reject excessive pixel
drift; FXAA checks require reduced sequence-wide high-frequency edge energy without
a per-frame brightness shift; enhancement checks reject a no-op. All supplied frames
are checked, including a loaded state's initial LCD contents before its first new draw.
These checks do not establish frame alignment, temporal stability or subjective image
quality; inspect paired captures too. Host FXAA must leave guest framebuffer bytes
identical at every matched tick. Use `--pattern` when guest and host exports share a folder.

The native cartridge-loader and unused-TEV-input regressions run with
`tests "[loader],[postfx]"`, including standalone Android binaries. Title override
inheritance has a framework-free check:

```sh
kotlinc \
  src/android/app/src/main/java/org/citra/citra_emu/features/settings/model/AbstractSetting.kt \
  src/android/app/src/main/java/org/citra/citra_emu/features/settings/model/SettingSection.kt \
  src/android/app/src/test/java/org/citra/citra_emu/features/settings/model/GameSettingsOverridesTest.kt \
  -include-runtime -d game-settings-check.jar
java -jar game-settings-check.jar
```

## Calamansi dual-screen HOME device check

Games, Search, Options and All Apps occupy the upper screen while the game library
stays below. LB/RB switch these pages in either direction with wraparound. Properties
keeps the lower library visible but read-only until the last dialog closes; a separate
settings or external application releases that presentation.

HOME tiles stay square on their initial layout, and upper All Apps uses adaptive
columns. Gamepad input works from either display: All Apps owns navigation until
Down leaves its last row, then focus transfers to the lower game grid. Up from the
first game row returns to All Apps. Exactly one green cursor marks the active tile
across both screens; game selection remains available to the lower title panel.
Touching either grid transfers cursor ownership without restoring focus or scrolling
before the touch lands. Controller handoffs restore focus explicitly. Keyboard arrows
remain available to upper Search and Apps controls.

A game icon tap launches that ROM immediately, or resumes its active session.
D-pad movement selects without launching; A opens the selected game. Long-press
selects and opens Properties. Automatic view-focus restoration never selects a
different game.

`verify-home-ui.py` uses adb, Pillow and NumPy (also used by the rendering checker).
Run with Thor charging,
its physical D-pad available to `sendevent`, Fates and a second game visible,
and no existing emulation session:

```sh
python tools/verify-home-ui.py --serial d884f526 --home-role --state-slot 3 \
  --package org.azahar_emu.azahar.fatesfix --adb /path/to/adb \
  --evidence /path/to/new-evidence-directory
```

`--home-role` requires Calamansi already selected through Android's HOME-role consent
dialog and an installed Android Settings launcher. The check loads the existing state
without saving, captures both physical displays, checks modal/navigation ownership,
and verifies that HOME/gear/icon resume retains the activity, process and native thread.
It hashes saves, savestates and native configuration before/after; any byte change fails.
It checks initial square tiles, multiple upper app columns, physical D-pad input
after lower touches, the last-row handoff and return without premature library movement,
and exactly one painted cursor across both screenshots at each transfer.
The launch checks exercise an unselected icon with one tap, controller A, and
same-session icon resume; selection fixtures use long-press rather than launch taps.
It never rewrites files to conceal a failure. Keep each run's screenshots and
`home-smoke.json`; partial progress with `"passed": false` is not a full acceptance pass.

