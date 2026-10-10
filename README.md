# Calamansi

Calamansi is this fork of Azahar/Citra, with Android 3DS-style HOME, per-game settings and opt-in rendering enhancements. Existing package IDs, save/config formats and user-data paths are preserved; the Thor build continues using `/sdcard/citra-emu` for saves and custom textures.

Source: [tapionallanaire/calamansi](https://github.com/tapionallanaire/calamansi). Download APKs from [Calamansi releases](https://github.com/tapionallanaire/calamansi/releases), or download the `calamansi-android` artifact from a successful [Android workflow run](https://github.com/tapionallanaire/calamansi/actions/workflows/build.yml). The artifact contains only these two APKs and `SHA256SUMS`:

| APK | Package ID | Usage |
| --- | --- | --- |
| `calamansi-android.apk` | `org.azahar_emu.azahar.fatesfix` | Primary Thor build; installs alongside the original package. |
| `calamansi-android-original-package.apk` | `org.azahar_emu.azahar` | Original-package build; replacing an installed APK requires the same signing identity. |

Both display **Calamansi**. Fork releases use a stable fork signer, not upstream Azahar's signing key. The packages can share the same selected user folder: side-by-side installation does **not** isolate saves/config/textures. Back up that folder and avoid simultaneous writes. Select the existing folder through **Options → Select User Folder** to reuse your data.

### Preview status and savestates

The first public release is a **preview**: full Thor/device acceptance checks are incomplete. CI checks APK signatures, ZIP CRCs, labels, package IDs, nondebuggable status and the `arm64-v8a`/`x86_64` ABIs; these checks do not establish a hardware/runtime pass.

Native builds retain their actual source commit identity and strict savestate version guards. A build from this new repository can reject savestates from earlier builds because the commit identity differs, even when the save/config paths are unchanged. Keep ordinary in-game saves and backups for migration; do not rely on cross-build savestate compatibility.

### Android build and release

Clone with public submodules using `git clone --recurse-submodules https://github.com/tapionallanaire/calamansi.git`. Install Java 17 and the Android SDK with Android 35, build-tools 35.0.0, CMake 3.30.3 and NDK 27.3.13750724. From `src/android`, run:

```sh
./gradlew assembleVanillaRelease assembleVanillaFatesFix
```

For a stable local signer, set `ANDROID_KEYSTORE_FILE`, `ANDROID_KEYSTORE_PASS` and `ANDROID_KEY_ALIAS`; both tasks use that signer. Without a configured key both fall back to the local Android debug signer, while remaining nondebuggable. For the same bounded build settings as CI, install `ccache` and run `bash .ci/android.sh` from the repository root.

The Android-only workflow runs on pushes to `main`, pull requests, manual dispatches and `v*` tags. Main and version-tag builds require the repository secrets `ANDROID_KEYSTORE_B64` (base64-encoded keystore), `ANDROID_KEYSTORE_PASS` and `ANDROID_KEY_ALIAS`. Preserve this key for upgrades; the decoded key lives only in runner temporary storage and is never cached or uploaded. PRs receive no signing secrets and use a temporary debug signer; their artifacts are not upgrade-compatible releases. Manual dispatches on other branches also use a temporary debug signer.

After a signed tag build succeeds, the publisher uploads exactly the two APKs and `SHA256SUMS` to a GitHub Release. Hyphenated tags such as `v0.1.0-preview.1` are marked prereleases; use a preview tag for the first release. Tags without a hyphen produce regular releases. Release publishing needs GitHub Actions enabled and permission for the publisher job's `GITHUB_TOKEN` to write repository contents. Check downloaded APK hashes with `sha256sum --check SHA256SUMS` in the download directory.

The original Azahar badges, distribution and community links below describe **upstream Azahar**, not Calamansi releases or support.

## Upstream Azahar

![Azahar Emulator](https://azahar-emu.org/resources/images/logo/azahar-name-and-logo.svg)

![Current Release](https://img.shields.io/github/v/release/azahar-emu/azahar?label=Current%20Release)
![Current Prerelease](https://img.shields.io/github/v/release/azahar-emu/azahar?include_prereleases&label=Current%20Prerelease)

![GitHub Downloads](https://img.shields.io/github/downloads/azahar-emu/azahar/total?logo=github&label=GitHub%20Downloads)
![Google Play Downloads](https://playbadges.pavi2410.com/badge/downloads?id=io.github.lime3ds.android&pretty&label=Play%20Store%20Downloads)
![Flathub Downloads](https://img.shields.io/flathub/downloads/org.azahar_emu.Azahar?logo=flathub&label=Flathub%20Downloads)
![CI Build Status](https://github.com/azahar-emu/azahar/actions/workflows/build.yml/badge.svg)

**Azahar** is a free and open-source high level Nintendo 3DS emulator for PC and mobile devices. Our goal is to give 3DS owners a place to enjoy their library of titles with improvements to the original hardware, such as higher resolutions, modern controllers or save states. The emulator also serves as a debugging hub for homebrew developers and as a research and preservation platform for the 3DS ecosystem.

The project continues the legacy of **Citra** and is actively developed by a community of contributors.

*Azahar is not affiliated with or endorsed by Nintendo.*

# Installation

### Windows

Azahar is available as both an installer and a zip archive.

Download the latest release in your preferred format from the [Releases](https://github.com/azahar-emu/azahar/releases) page.

If you are unsure of whether you want to use MSVC or MXE, use MXE.

---

### MacOS

To download a build that will work on all Macs, you can download the `macos-universal` build on the [Releases](https://github.com/azahar-emu/azahar/releases) page.

Alternatively, if you wish to download a build specifically for your Mac, you can choose either:

- `macos-arm64` for Apple Silicon Macs
- `macos-x86_64` for Intel Macs

---

### Android

There are two variants of Azahar available on Android, those being the Vanilla and Google Play builds.

The Vanilla build is technically superior, as it uses an alternative method of file management which is faster, but isn't permitted on the Google Play store.

For most users, we currently recommended downloading Azahar on Android via the Google Play Store for ease of accessibility:

<a href='https://play.google.com/store/apps/details?id=io.github.lime3ds.android'><img width='180' alt='Get it on Google Play' src='https://raw.githubusercontent.com/pioug/google-play-badges/06ccd9252af1501613da2ca28eaffe31307a4e6d/svg/English.svg'/></a>

Alternatively, you can install the app using Obtainium, allowing you to use the Vanilla variant:
1. Download and install Obtainium from [here](https://github.com/ImranR98/Obtainium/releases) (use the file named `app-release.apk`)
2. Open Obtainium and click 'Add App'
3. Type `https://github.com/azahar-emu/azahar` into the 'App Source URL' section
4. Click 'Add'
5. Click 'Install', and select the preferred variant

If you wish, you can also simply install the latest APK from the [Releases](https://github.com/azahar-emu/azahar/releases) page.

Keep in mind that you will not recieve automatic updates when installing via the APK.

#### Local fork: game-specific settings and rendering options

Open a game's properties and choose **Game settings**, or open settings from its
in-game menu. Missing values inherit global settings. Long-press a setting to use
its global value; **Use global settings** removes only that title's overrides.
Overrides are stored in `GameSettings/<16-digit-uppercase-title-ID>.ini` inside
the user directory. Controller mappings and multiplayer credentials remain global.

Decrypted `.3ds` cartridges are accepted alongside `.cci`; renaming an encrypted
cartridge does not decrypt it. Encrypted or corrupt cartridges still report an error.

**FXAA (builtin)** is an opt-in Vulkan presentation filter for normal and
side-by-side output. Restart the game after changing it; anaglyph and interlaced
output retain their existing shaders. **Disable Game Bloom/Blur** is a separate,
default-off enhancement that removes intended game effects, not an accuracy fix.
For Fates at 4x, set these in **Game settings → Graphics** to reduce the game's
low-resolution bloom/blur overlay and soften un-antialiased edges. The result is
darker, deliberately less hazy, and does not remove scripted unit fades. Default
rendering remains unchanged. Blur-chain samplers are already linear; no forced
blur-texture filtering is needed.

HD replacements keep their pack dimensions. Vulkan also initializes the sampled
image when texture filtering is enabled, preventing missing replacement portraits
and UI. To reuse existing saves and texture packs, select their existing user
directory through **Options → Select User Folder**; copying the packs is unnecessary.

---

### Linux

The recommended format for using Azahar on Linux is the Flatpak available on Flathub:

<a href='https://flathub.org/apps/org.azahar_emu.Azahar'><img width='180' alt='Download on Flathub' src='https://dl.flathub.org/assets/badges/flathub-badge-en.png'/></a>

Azahar is also available as an AppImage on the [Releases](https://github.com/azahar-emu/azahar/releases) page.

There are two variants of the AppImage available, those being `azahar.AppImage` and `azahar-wayland.AppImage`.

If you are unsure of which variant to use, we recommend using the default `azahar.AppImage`. This is because of upstream issues in the Wayland ecosystem which may cause problems when running the emulator (e.g. [#1162](https://github.com/azahar-emu/azahar/issues/1162)).

Unless you explicitly require native Wayland support (e.g. you are running a system with no Xwayland), the non-Wayland variant is recommended.

The Flatpak build of Azahar also has native Wayland support disabled by default. If you require native Wayland support, it can be enabled using [Flatseal](https://flathub.org/en/apps/com.github.tchx84.Flatseal).

# Build instructions

Please refer this repository's [wiki](https://github.com/azahar-emu/azahar/wiki/Building-From-Source) for build instructions

# How can I contribute?

### Pull requests

If you want to implement a change and have the technical capability to do so, we would be happy to accept your contributions.

If you are contributing a new feature, it is highly suggested that you first make a Feature Request issue to discuss the addition before writing any code. This is to ensure that your time isn't wasted working on a feature which isn't deemed appropriate for the project.

After creating a pull request, please don't repeatedly merge `master` into your branch. A maintainer will update the branch for you if/ when it is appropriate to do so.

### Language translations

Additionally, we are accepting language translations on [Transifex](https://app.transifex.com/azahar/azahar). If you know a non-english language listed on our Transifex page, please feel free to contribute.

> [!NOTE]
> We are not currently accepting new languages for translation. Please do not request for new languages or language variants to be added.

### Compatibility reports

Even if you don't wish to contribute code or translations, you can help the project by reporting game compatibility data to our compatibility list.

To do so, simply read https://github.com/azahar-emu/compatibility-list/blob/master/CONTRIBUTING.md and follow the instructions.

Contributing compatibility data helps more accurately reflect the current capabilities of the emulator, so it would be highly appreciated if you could go through the reporting process after completing a game.

# Minimum requirements

Below are the minimum requirements to run Azahar:

### Desktop

```
Operating System: Windows 10 (64-bit), MacOS 13.4 (Ventura), or modern 64-bit Linux
CPU: x86-64/ARM64 CPU (Windows for ARM not supported).
     Single core performance higher than 1,800 on Passmark.
     SSE4.2 required on x86_64.
GPU: OpenGL 4.3 or Vulkan 1.1 support
Memory: 2GB of RAM. 4GB is recommended
```
### Android

```
Operating System: Android 10.0+ (64-bit)
CPU: Snapdragon 835 SoC or better
GPU: OpenGL ES 3.2 or Vulkan 1.1 support
Memory: 2GB of RAM. 4GB is recommended
```

# What's next?

We share public roadmaps for upcoming releases in the form of GitHub milestones.

You can find these at https://github.com/azahar-emu/azahar/milestones.

# Join the conversation

We have a community Discord server where you can chat about the project, keep up to date with the latest announcements, or coordinate emulator development.

Join at https://discord.gg/4ZjMpAp3M6
