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

## Shared picture-filter check

The framework-free check exercises the production mask's Off pass-through, CRT/LCD repeat boundaries, hue preservation and invalid-mode rejection:

```sh
kotlinc \
  src/android/engines/common/src/main/java/org/calamansi/engine/PictureFilter.kt \
  src/android/engines/common/src/test/java/org/calamansi/engine/PictureFilterCheck.kt \
  -include-runtime -d picture-filter-check.jar
java -jar picture-filter-check.jar
```

This does not prove Android composition or input/lifecycle behavior. On a connected handheld inspect the selector's Original/Preview pair, both game screens, Off restoration, controller/stylus input, pause/resume/recreation and orderly shutdown. Cover RetroArch's native-window popup and Dolphin's untouched controller overlays. Recreating a per-game settings category without edits must not create overrides; Cemu custom values must remain visible and preset zero must commit. Compare protected saves and unrelated engine settings before/after.

## Console wording checks

`python src/android/engines/dolphin/prepare_check.py` also checks console-only generated UI text without changing actual link targets, format arguments, hardware identities or license attribution.

`cmd.exe /c "gradlew.bat -Pcalamansi.cmakeVersion=3.31.4 :engine-retroarch:prepareRetroarch"` from `src/android` applies the pinned native patch and exercises the production message rewrite across C comments, sentence punctuation, protected filenames/URLs/format arguments and legal attribution.


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

`verify-home-chrome.py` is the small notification-strip regression check. Open HOME on an awake Thor with no native game session first:

```sh
python tools/verify-home-chrome.py --serial d884f526
```

It observes the real native view's visibility across edge swipes and Android notification expansion/collapse, restores the shade afterward, and never launches/stops games or changes preferences/saves. Native gesture acceptance and visual cartridge/font inspection require working ADB; a build or the script's `--help` output is not a hardware pass.

`verify-home-cartridge.py` checks real upper-cartridge motion across eight frames and a pixel-identical lower game grid. Use an awake, idle Thor on Applications with a selected game, no native guest, enabled Android animations and a completed library scan:

```sh
python tools/verify-home-cartridge.py --serial d884f526 --evidence /path/to/cartridge-captures
```

It saves both panels and a cropped animation GIF without launching games or changing settings. Inspect the front, side walls and unmapped back face; motion alone is not proof of correct 3D geometry. Also check All Apps/navigation hides the preview without continuing frame production, and disabled system animations show a still cartridge; restore the prior Android animation setting afterward.

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


## Embedded DS shutdown and library check

With the combined APK installed, a legally owned raw DS cartridge available and no
existing guest running:

```sh
python tools/verify-nds-stop.py --serial d884f526 \
  --rom-uri 'file:///storage/EB7E-7C48/Roms/nds/Castlevania%20-%20Dawn%20of%20Sorrow%20%28USA%29.nds' \
  --library-title 'Castlevania Dawn of Sorrow' \
  --library-title 'Castlevania: Lords of Shadow Mirror of Fate'
```

The check boots the real native engine twice, destroys each DS Activity through
Android task replacement, verifies shutdown without a receiver crash or process
kill, and relaunches. Optional repeated `--library-title` arguments require those
visible library tiles exactly once after each teardown, detecting missing games
and overlapping-folder duplicates. It does not clear app data or verify gameplay,
stylus input or SRAM persistence.

Add `--drawer` on an awake English-language Thor to exercise the top screen's
left-edge sidebar, native pause, the active cartridge's DS settings, preservation
of explicit pause after returning, resume and scrim dismissal before each teardown.
This option requires Pillow. It observes actual changing/frozen game frames rather
than treating menu labels alone as proof of native pause/resume.

Add `--shell` on Thor to check that both panels' margins show their white DS shells,
both native 1024×768 picture apertures remain open, the bottom button row is absent,
and one accessible microphone icon is centred below the lower picture without
covering it. This option also requires Pillow and can be combined with `--drawer`
to check sidebar interaction and microphone disable/enable across pause, settings
return and resume.
