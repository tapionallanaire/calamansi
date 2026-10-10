# 3DS-inspired Android HOME UI

## Approved intent

Revamp Azahar's Android UI to feel like the 3DS HOME Menu. The user selected a dual-screen HOME Menu and later clarified that the game library must persist on the lower screen while Games preview, Search, All Apps or Options occupy the upper screen. All Apps is a separate fourth navigation destination using real installed Android apps. Phones retain a complete single-screen layout.

Preserve the Fates Vulkan/HD portrait fixes, opt-in bloom reduction, FXAA, sparse per-game settings, cartridge support, shared `/sdcard/citra-emu` access and all saves. Do not change graphics defaults, emulator screen-layout settings, game performance presets, firmware or save formats. The user subsequently approved an optional Android HOME role; no launcher replacement occurs until Android grants that role.

## Visual contract

- Silver-white and mint classic styling across Games, Search, All Apps and Options, independent of Android/app night mode. This supersedes the earlier HOME-only theme scope. Do not rewrite app preferences; native emulation and separate settings activities retain their existing themes.
- Embossed silver background, softly beveled square icon-only game tiles, compact native typography and mint corner brackets for selection/focus. Search retains visible result titles. No fake Nintendo system utilities, cover art, new fonts or continuous animation.
- Use real SMDH icons and localized metadata. The top screen previews the selected game; the bottom exposes its full title and real Open/Properties actions outside the tiles.
- Tiles have at least 72dp interactive bounds; controls retain 48dp targets and accessible names. The grid uses its own display width, scrolls when needed and remains usable at enlarged font sizes.
- A compact lower-screen utility strip exposes Games, Search, Options and All Apps. Keep navigation authority in MainActivity and emulator screen layout untouched.

## Direction contract

- THESIS: make the user's handheld read as a classic 3DS HOME library, not a browser of tall Material cards.
- OWN-WORLD: permanently silver-white embossed ground, beveled square tiles, mint corner brackets and compact Roboto UI roles. The user-provided reference pins the direction; no random concept selection.
- STORY: select a real game, see its full name and icon on the top display, then Open or inspect Properties. Existing navigation remains familiar.
- FIRST VIEWPORT: selected icon and metadata centered above; compact utility strip, dense icon-only grid and slim selected-game action shelf below. Phones retain both work areas.
- FORM: native Android Operate surface; XML Views and themed Material controls. Signature interaction is immediate mint-bracket selection with the top preview following controller focus or touch.
- FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance.

## Architecture and ownership

Retain XML views, RecyclerView, the current primary Navigation Component graph, game model and icon helper. Keep one navigation stack and one game scanner. A persistent Activity-owned GamesFragment hosts the lower library outside the changing upper NavHost; no new UI framework or native HOME renderer.

### Activity and fragment

- MainActivity stays on the existing primary/top display and remains navigation authority. Do not relocate it to the bottom display: emulation must keep its current launch display.
- GamesFragment owns the HOME Presentation and library view lifetime in a persistent Activity FragmentContainerView. Upper navigation cannot destroy it. The primary NavHost displays the selected-game preview, Search, All Apps or Options; the lower library remains interactive on a suitable secondary display. Never detach/reparent either managed fragment root.
- Only one interactive library panel/RecyclerView host is active at a time. Attach the existing adapter/list behavior to that host; preserve selection and scroll state when switching hosts. Loading, refresh, empty-state and footer controls move together, not just the grid.
- The secondary HOME footer drives the Activity's upper NavController. Its checked destination follows that controller. Hide the primary navigation view while dual HOME replaces it. On a single display, show the complete GamesFragment preview/library when Games is selected, and the normal upper destination for Search/Apps/Options.
- Presentation views use their own resources, theme and WindowInsets. They must not inherit primary-window column counts or inset reservations. Result launchers, fragment observers, settings launches and navigation stay with the host Activity/Fragment.

### Selection and actions

- Keep selected game identity as a path key in the activity-scoped GamesViewModel, with saved-state restoration. Resolve it against the newest active visible list, not a recycled ViewHolder, position or title ID alone.
- Preserve the selected path across reload, recreation and HOME display transitions if still visible. If absent, select the first visible game; if empty, clear the preview and disable Open/Properties. Only the active library destination reconciles HOME selection.
- Touch selects a HOME tile; a second activation or Open launches it. Touch synchronizes tile focus after applying selection, so stale controller focus cannot change the selected ROM. D-pad changes focus and selection. Android gamepad A/confirm and Enter activate Open; B/system Back retain navigation. Upper search text and app-grid input must not be intercepted by the lower library; lower-window keys retain their scoped route. Do not change emulator controller mappings.
- Long-press and a visible Properties action use existing game properties/per-title settings. Activity-owned modal dialogs keep the lower library visible but read-only, including touch, controller, generic motion and accessibility input. Restore the same selection and scroll after the final dialog closes. Separate settings activities still release lower HOME ownership.
- GameAdapter receives explicit host navigation ownership for relocated views. Update both GamesFragment and SearchFragment callers. Do not depend on view.findNavController finding a NavHost through a separate window.
- Consolidate library launch into one Game-based action shared by tile activation, Open/A and properties Play. Preserve path/build-specific missing-file checks, existing launch throttling and last-played bookkeeping; return if validation fails. Capture the Game being acted on, never a holder that can be rebound. Keep direct URI/shortcut/system-title launch paths independent of HOME selection.
- In-game HOME suspends, rather than stops, the existing native session. Release held input and the emulation presentation before showing console HOME. Display the active title and a dedicated gear action for its per-title settings. Tapping its icon resumes the same activity and native thread, without a ROM reload or savestate round trip.
- Opening another title requires confirmation that unsaved progress will be lost. Cancelling preserves the paused session.
- ConsoleHomeActivity reuses MainActivity UI in a separate Android HOME task. Launch and resume emulation with NEW_TASK plus REORDER_TO_FRONT; never place EmulationActivity above a singleTask HOME root that could clear it on the next physical HOME press.
- Options requests Android's HOME role with the system consent dialog and records the previous launcher. The user chose HOME to open Calamansi outside gameplay too; expose an Android-launcher action rather than an accessibility key-filter service or firmware remap. Repeated HOME intents must not dismiss unfinished first-launch setup.
- Creating console HOME while a session exists must not reload global settings over the active title's effective settings or reset multiplayer.

### Display selection and lifecycle

- Reuse the existing display-discovery/selection policy through a small stateless helper shared with SecondaryDisplay. Preserve the emulation manager's current behavior. Snapshot candidates once per reconciliation and never hard-code Thor names or display IDs.
- HOME does not instantiate SecondaryDisplay, create HiddenDisplay, call native secondary-surface/touch methods, or read/write enable_secondary_display/secondary_display_layout. Those remain emulation-owned settings.
- HOME is eligible with a resumed, live persistent GamesFragment, an interactive host, a normal HOME destination (not first-time setup), and a valid, on, non-host display. Register the display listener while interactive; reconcile changes to the active lower display while any upper HOME page is active.
- Keep HOME during upper-destination navigation and Activity-owned Properties dialogs. Modal dialogs disable lower input; native emulation/settings/external-app handoff, pause/stop/view destruction and sleep dismiss the presentation. Inhibit reopening during handoff. Upper-page changes preserve the lower adapter, selected path, scroll state and active-game shelf.
- Allow normal Activity/theme/orientation recreation. Clear old window/binding references and reconstruct from selected state. Recreate secondary content when that display's configuration changes. HOME uses a non-focusable lower window so upper Search retains IME focus; route lower controller keys through the same Activity actions.
- Handle cancellation, invalid-display and bad-window-token races by clearing the failed window and restoring the primary library. Reconcile again on the next eligible resume/display event; no retry loop or invisible render target.
- On no suitable secondary display, secondary screen off/removal, presentation failure or screen sleep, keep or restore one complete primary-display library. Do not rewrite game settings to force a second screen.

## Accessibility and battery

Use full-title accessible tile labels and selected semantics, visible focus outlines, labeled Open/Properties actions, and meaningful empty/loading/error states. Decorative duplicate icons are not additional accessibility stops. Keep search clear and other restyled controls focusable and at least 48dp.

Use native focus/ripple behavior only; no background render loop, permanently held wake lock, periodic game scanning or animated 3D HOME scene. Stop HOME listeners/window activity while paused/asleep. Do not change device performance modes, clocks, charging settings or user graphics presets. Leave the Thor charging asleep between bounded verification runs.

The upper status strip uses the native clock and a static horizontal four-segment 3DS-style battery drawable. Read real Thor charge on menu resume; hide unsupported charge values rather than showing a fictitious full battery. No animation, periodic battery polling or fake system utilities.

## Verification and acceptance

| Scenario | Required result |
| --- | --- |
| Dual-screen HOME | Installed clean app shows selected icon/metadata on physical top and usable library/footer on physical bottom; capture both surfaces. |
| Selection and launch | Touch and D-pad select distinct titles; preview/title/focus agree. Open and gamepad confirm launch the selected Game exactly once through the existing route. |
| HOME-to-game handoff | HOME window is gone before native emulation presentation appears. Fates still renders on both screens with HD portraits; no HOME/native overlap. |
| Properties/per-title settings | Long-press and visible Properties reach the existing game actions and title-specific settings. Search result launch and direct entry remain functional. |
| Navigation | Games, Search, All Apps and Options change the physical upper screen while exactly one lower library stays visible. Selected game, scroll state and paused-game controls survive. Back/HOME return to preview; no duplicate navigation stack or game scanner. |
| Lifecycle | Sleep/wake, foreground/background, theme/orientation recreation and repeated HOME/game returns produce no stale windows or invalid bindings. |
| Live session | Load slot 3 without saving, enter HOME, identify the active title, open its gear settings, cancel another-game launch and tap the active icon. Activity, process and NativeEmulation thread remain identical. |
| Physical HOME | With explicit Android HOME-role consent, the Thor HOME key opens console HOME during gameplay and outside it. Resume preserves the native session; the Android-launcher action reaches the recorded previous launcher. |
| Single-screen fallback | Exercise a real single-display environment or controlled display-unavailable scenario: complete preview/library/actions remain usable; restoring a secondary display preserves selection and a single active host. |
| Empty/removal boundaries | No games disables launch; removing/filtering the selected game resolves a valid fallback; missing-file activation does not launch stale content. Retain one small runnable consumer-visible check for uncertain selection/lifecycle logic. |
| Safety | User SD saves and savestates remain unchanged; global/native game settings remain unchanged except any explicitly exercised test option restored to its original bytes. Existing native rendering fixes are preserved. |
| Delivery | Build vanilla release and side-by-side Fates APKs; install/smoke the side-by-side APK; provide clean source diff, APKs and a short report with actual before/after screenshots and exercised limitations. |

Automate selection/launch/handoff/lifecycle smoke using the existing adb workflow and a minimal host-side check; observe actual surfaces, not just unit tests or hierarchy text. Protect config/save hashes and charging state. Any temporary diagnostics must be absent from delivered sources/APKs. Do not claim unexercised hardware or full-suite behavior.

## Chosen tradeoffs

A HOME-only Android Presentation reuses the current library and leaves emulation placement alone. Moving MainActivity to the secondary display risks inherited activity placement; a Compose rewrite adds another UI system. Neither is needed. The separately approved Android HOME role uses a dedicated activity reusing the existing UI, so system HOME cannot destroy an emulator placed above its task root. Animated proprietary banner rendering and invented system apps are not part of this refresh.

## Calamansi branding revision

Visible fork branding is Calamansi. Use the original green calamansi fruit, golden cut half and leaf artwork, not Nintendo artwork. Keep existing package IDs, namespaces, desktop executable/resource filenames, persisted shortcut/channel keys and save/config/user-data paths unchanged. Upstream historical attribution and service URLs remain explicitly Azahar. Android HOME identity remains the installed ConsoleHomeActivity.
