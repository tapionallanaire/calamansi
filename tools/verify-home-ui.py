#!/usr/bin/env python3
"""Exercise HOME through adb; never edits a save, native config, theme or ROM.

Requires an awake-capable charging device with at least two visible library games,
multi-window accessibility display ancestry, and a lower HOME on logical display 4.
Selected fixture titles must be unique: device identity uses the selected tile/title,
while duplicate-path identity is covered by the standalone real-Game selection check.
With --home-role, also requires the real Android Settings launcher label "Settings".
Exit fails on absent accessibility/window evidence; screenshots alone are not a pass.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import time
import uuid
import xml.etree.ElementTree as ET


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--package', default='org.azahar_emu.azahar.fatesfix')
    parser.add_argument('--evidence', type=Path, required=True)
    parser.add_argument('--game-title', default='Fire Emblem Fates')
    parser.add_argument('--state-slot', type=int, choices=range(1, 10), default=3)
    parser.add_argument('--home-role', action='store_true',
                        help='Require Android HOME role; verify All Apps, Android Settings and same-session HOME/resume')
    parser.add_argument('--data-root', default='/sdcard/citra-emu')
    parser.add_argument('--adb', default=shutil.which('adb'))
    args = parser.parse_args()
    if not args.adb:
        sdk = os.environ.get('ANDROID_SDK_ROOT') or os.environ.get('ANDROID_HOME')
        if sdk:
            args.adb = str(Path(sdk) / 'platform-tools' / ('adb.exe' if os.name == 'nt' else 'adb'))
    if not args.adb:
        parser.error('Pass --adb or set ANDROID_SDK_ROOT; adb is not installed on PATH')
    args.evidence.mkdir(parents=True, exist_ok=True)
    nonce = '/sdcard/azahar-home-check-' + uuid.uuid4().hex + '.xml'
    package = args.package
    home_activity = 'ConsoleHomeActivity' if args.home_role else 'MainActivity'
    baseline = None
    owns_device = False
    started_emulation = False
    report = {'package': package, 'serial': args.serial, 'steps': [], 'passed': False}

    def adb(*command, binary=False):
        result = subprocess.run([args.adb, '-s', args.serial, *command], capture_output=True, timeout=45)
        if result.returncode:
            raise RuntimeError(result.stderr.decode(errors='replace').strip() or result.stdout.decode(errors='replace'))
        return result.stdout if binary else result.stdout.decode(errors='replace')

    def shell(command):
        return adb('shell', command)

    def wait_for(condition, message, seconds=20):
        end = time.monotonic() + seconds
        while time.monotonic() < end:
            value = condition()
            if value is not None and value is not False:
                return value
            time.sleep(0.5)
        raise AssertionError(message)

    def windows():
        return shell('dumpsys window windows')

    def resumed(activity_name):
        return re.search(
            r'(?:mResumedActivity|topResumedActivity)[^\n]*' + re.escape(package) +
            r'[^\n]*' + re.escape(activity_name),
            shell('dumpsys activity activities')
        )

    def home_count(dump=None):
        return len(re.findall(r'Window #\d+ Window\{[^\n]*Calamansi HOME\}',
                              windows() if dump is None else dump))

    def manifest():
        roots = [args.data_root + '/' + name for name in ('sdmc', 'nand', 'states')]
        paths = shell('find ' + ' '.join(shlex.quote(p) for p in roots) + ' -type f').splitlines()
        saves = [p.strip() for p in paths if p.strip().endswith('.cst') or '/data/' in p or '/extdata/' in p]
        configs = shell('find ' + shlex.quote(args.data_root + '/config') + ' -type f').splitlines()
        result = {}
        for path in sorted(set(saves + [p.strip() for p in configs])):
            digest = hashlib.sha256(adb('exec-out', 'cat ' + shlex.quote(path), binary=True)).hexdigest()
            result[path] = digest
        if not any(p.endswith('.cst') for p in result):
            raise AssertionError('No savestates found: refusing unverifiable save preservation')
        return result

    def hierarchy():
        shell('uiautomator dump --windows --compressed ' + shlex.quote(nonce))
        return ET.fromstring(adb('exec-out', 'cat ' + shlex.quote(nonce)))

    def text_node(pattern):
        return next((n for n in hierarchy().iter('node')
                     if n.get('package') == package and re.search(pattern, n.get('text', '')) and
                     n.get('enabled') == 'true'), None)

    def emulation_record():
        dump = shell('dumpsys activity activities')
        match = re.search(r'ActivityRecord\{([0-9a-f]+)[^\n]*' + re.escape(package) +
                          r'/org\.citra\.citra_emu\.activities\.EmulationActivity', dump)
        if not match:
            raise AssertionError('No live emulation activity record')
        return match[1]
    def native_thread(pid):
        threads = shell('ps -T -p ' + shlex.quote(pid) + ' -o TID,COMM')
        matches = re.findall(r'^\s*(\d+)\s+\[?NativeEmulation\]?\s*$', threads, re.MULTILINE)
        if not matches:
            raise AssertionError('No actual NativeEmulation thread: ' + threads)
        # ponytail: fresh-process minimum TID; use ART identity if this ever reuses a process.
        return min(matches, key=int)


    def return_to_console_home():
        shell('input keyevent KEYCODE_BACK')
        tap(required_node('menu_console_home'), 0)
        wait_for(lambda: resumed(home_activity) and home_count() == 1,
                 'In-game HOME did not return to the real dual-screen library')

    def verify_apps_roundtrip():
        identity = selected_identity(hierarchy())
        tap(required_node('appsFragment'), secondary)
        wait_upper('appsFragment')
        verify_home_page('appsFragment', identity, fates)

        previous_page = None
        labels_seen = set()
        while True:
            root = hierarchy()
            if node('alertTitle', root) is not None:
                raise AssertionError('Unexpected dialog on All Apps')
            verify_home_page('appsFragment', identity, fates, root)
            grid = display_node('grid_apps', 0, root)
            if grid is None:
                raise AssertionError('All Apps lost its physical upper grid')
            cards = [n for n in grid.iter('node') if n.get('package') == package and
                     n.get('resource-id', '').endswith(':id/card_app')]
            labels = [card.get('content-desc', '') for card in cards]
            if not labels or any(not label.strip() for label in labels):
                raise AssertionError('All Apps has no populated, labelled application cards')
            if any(card.get('clickable') != 'true' or card.get('enabled') != 'true' for card in cards):
                raise AssertionError('All Apps contains a non-launchable application card')
            names = [n.get('text', '') for n in grid.iter('node') if n.get('package') == package and
                     n.get('resource-id', '').endswith(':id/text_app_name')]
            if not names or any(not name.strip() or name not in labels for name in names):
                raise AssertionError('All Apps does not display its real application labels')
            labels_seen.update(labels)
            settings_card = next((card for card in cards if card.get('content-desc') == 'Settings' and
                                  node('text_app_name', card) is not None and
                                  node('text_app_name', card).get('text') == 'Settings'), None)
            if settings_card is not None:
                break
            # Scroll the actual alphabetic grid, stopping at its end rather than retrying a failed launch.
            page = tuple((card.get('content-desc'), card.get('bounds')) for card in cards)
            if page == previous_page:
                raise AssertionError('Real Android Settings application is absent from All Apps')
            previous_page = page
            bounds = [int(v) for v in re.findall(r'\d+', grid.get('bounds', ''))]
            if len(bounds) != 4 or bounds[2] <= bounds[0] or bounds[3] <= bounds[1]:
                raise AssertionError('Invalid visible All Apps grid bounds: ' + str(grid.attrib))
            x = (bounds[0] + bounds[2]) // 2
            height = bounds[3] - bounds[1]
            shell(f'input -d 0 swipe {x} {bounds[1] + height * 3 // 4} '
                  f'{x} {bounds[1] + height // 4} 400')
            time.sleep(0.5)
        capture('05-active-apps')
        tap(settings_card, 0)
        # This is the only cross-package matching: prove the real Android Settings resumed owner.
        wait_for(lambda: re.search(
            r'(?:mResumedActivity|topResumedActivity)[^\n]*\bcom\.android\.settings/',
            shell('dumpsys activity activities')),
            'Settings application card did not resume the actual com.android.settings package')
        wait_for(lambda: home_count() == 0, 'Secondary HOME leaked over Android Settings')
        capture('05-android-settings')
        shell('input keyevent KEYCODE_HOME')
        wait_for(lambda: resumed('ConsoleHomeActivity') and home_count() == 1,
                 'Android HOME from Settings did not restore the console Games destination')
        verify_home_page('gamesFragment', identity, fates)
        capture('05-apps-role-home')
        report['apps_destination'] = 'appsFragment'
        report['apps_display'] = 0
        report['observed_app_labels'] = sorted(labels_seen)
        report['android_settings_package'] = 'com.android.settings'
        report['apps_home_return'] = True

    def verify_active_session():
        wait_for(lambda: resumed('EmulationActivity') and node('drawer_layout') is not None and
                 node('loading_text') is None and (node('alertTitle') is None or
                 text_node(r'^Warning: Savestates are NOT a replacement') is not None),
                 'Game never reached its actual ready surface', seconds=90)
        if text_node(r'^Warning: Savestates are NOT a replacement'):
            tap(required_node('button1'), 0)
        # Load, never save: capture the user's battlefield/HD portrait regression scene.
        shell('input keyevent KEYCODE_BACK')
        # This native drawer is bound to the started game, not the possibly stale HOME tile focus.
        started_title = required_node('text_game_title').get('text')
        if started_title != fates:
            raise AssertionError('Open started the wrong ROM: expected ' + fates + ', got ' + str(started_title))
        report['started_game_title'] = started_title
        capture('05-started-title')
        tap(required_node('menu_emulation_savestates'), 0)
        tap(wait_for(lambda: text_node(r'^Load State$'), 'Load State action absent'), 0)
        capture('05-load-state-menu')
        slot = wait_for(lambda: text_node(r'^Slot ' + str(args.state_slot) + r'(?:$| -)'),
                        'Occupied load slot absent: ' + str(args.state_slot))
        tap(slot, 0)
        wait_for(lambda: node('menu_console_home') is None, 'State load left drawer open')
        time.sleep(2)
        state_dialog = node('alertTitle')
        if state_dialog is not None:
            raise AssertionError('State load was rejected: ' + state_dialog.get('text', ''))
        capture('05-slot-' + str(args.state_slot) + '-before-home')
        record = emulation_record()
        pid = shell('pidof ' + shlex.quote(package)).strip()
        thread = native_thread(pid)
        before_log = adb('logcat', '-d', '--pid=' + pid, '-v', 'raw')
        return_to_console_home()
        if required_node('home_active_title').get('text') != fates:
            raise AssertionError('HOME identifies the wrong active game')
        capture('05-active-game-home')
        density_before = shell(f'wm density -d {secondary}')
        density = re.search(r'(?:Override|Physical) density: (\d+)', density_before)
        override = re.search(r'Override density: (\d+)', density_before)
        if density is None:
            raise AssertionError('Cannot safely restore secondary display density')
        current_density = int(override.group(1) if override else density.group(1))
        try:
            shell(f'wm density {current_density + 1} -d {secondary}')
            time.sleep(1)
            verify_home_page('gamesFragment', active_title=fates)
            capture('05-paused-display-changed')
            if native_thread(pid) != thread:
                raise AssertionError('Display change restarted the suspended native session')
        finally:
            restore = override.group(1) if override else 'reset'
            shell(f'wm density {restore} -d {secondary}')
        wait_for(lambda: home_count() == 1, 'HOME did not restore after display change')
        if shell(f'wm density -d {secondary}') != density_before:
            raise AssertionError('Display density was not restored exactly')
        report['paused_display_change'] = True
        verify_upper_navigation('05-active-upper', fates)
        tap(required_node('home_active_settings'), secondary)
        wait_for(lambda: resumed('SettingsActivity'), 'Active game gear did not open settings')
        if home_count() != 0:
            raise AssertionError('HOME leaked during active-game configuration')
        if not any('0004000000179600' in n.get('text', '').upper()
                   for n in hierarchy().iter('node')):
            raise AssertionError('Gear opened settings for the wrong title')
        capture('05-active-game-settings')
        shell('input keyevent KEYCODE_BACK')
        wait_for(lambda: home_count() == 1, 'HOME failed to restore after active-game settings')
        # Another title must not silently throw away the suspended game.
        select_title(other)
        tap(required_node('home_open'), secondary)
        wait_for(lambda: home_count() == 1 and node('button2') is not None,
                 'Switching games did not require closing the current session')
        tap(required_node('button2'), 0)
        wait_for(lambda: home_count() == 1, 'Cancelling a game switch lost HOME')
        tap(required_node('home_resume_active'), secondary)
        wait_for(lambda: resumed('EmulationActivity') and home_count() == 0,
                 'Active icon did not resume directly')
        if emulation_record() != record or shell('pidof ' + shlex.quote(package)).strip() != pid:
            raise AssertionError('Resume replaced the suspended activity/process')
        wait_for(lambda: node('drawer_layout') is not None and node('loading_text') is None,
                 'Resume cold-launched instead of restoring gameplay')
        if native_thread(pid) != thread:
            raise AssertionError('HOME/resume restarted the actual native emulation thread')
        capture('05-resumed-slot-' + str(args.state_slot))
        report['session_activity'] = record
        report['session_pid'] = pid
        report['loaded_state_slot'] = args.state_slot
        report['native_emulation_thread'] = thread
        if args.home_role:
            shell('input keyevent KEYCODE_HOME')
            wait_for(lambda: resumed('ConsoleHomeActivity') and home_count() == 1,
                     'Android HOME did not reach the console HOME activity')
            if required_node('home_active_title').get('text') != fates:
                raise AssertionError('Physical HOME lost the active game')
            capture('05-physical-home')
            verify_apps_roundtrip()
            tap(required_node('home_resume_active'), secondary)
            wait_for(lambda: resumed('EmulationActivity') and home_count() == 0 and
                     node('drawer_layout') is not None and node('loading_text') is None and
                     node('alertTitle') is None,
                     'All Apps/Settings/HOME active icon did not restore ready gameplay')
            if (emulation_record() != record or shell('pidof ' + shlex.quote(package)).strip() != pid or
                    native_thread(pid) != thread):
                raise AssertionError('All Apps/Settings/HOME replaced the activity/process/native session')
            capture('05-apps-resumed-slot-' + str(args.state_slot))
            report['physical_home_route'] = True
        after_log = adb('logcat', '-d', '--pid=' + pid, '-v', 'raw')
        (args.evidence / 'session-log-before.txt').write_text(before_log, encoding='utf-8')
        (args.evidence / 'session-log-after.txt').write_text(after_log, encoding='utf-8')
        if before_log in after_log:
            extra_log = after_log.split(before_log, 1)[1]
            if 'Starting emulation thread.' in extra_log or 'Stopping emulation.' in extra_log:
                raise AssertionError('Native emulation restarted during HOME/resume')

    def node(resource, root=None):
        root = root if root is not None else hierarchy()
        return next((n for n in root.iter('node') if n.get('package') == package and
                     n.get('resource-id', '').endswith(':id/' + resource)), None)

    def display_node(resource, display_id, root):
        # Use real dump ancestry, never a coordinate guess or a package-only match.
        for display in root.iter():
            actual = display.get('display-id', display.get('displayId'))
            if display.tag == 'display':
                actual = display.get('id', actual)
            if actual == str(display_id):
                found = node(resource, display)
                if found is not None:
                    return found
        return None

    def visible_node(resource, display_id, root):
        found = display_node(resource, display_id, root)
        if found is None:
            raise AssertionError(f'{resource} absent from logical display {display_id}')
        bounds = [int(v) for v in re.findall(r'\d+', found.get('bounds', ''))]
        if len(bounds) != 4 or bounds[2] <= bounds[0] or bounds[3] <= bounds[1]:
            raise AssertionError('Control has no visible bounds: ' + str(found.attrib))
        return found

    def selected_identity(root):
        title = visible_node('home_selected_title', secondary, root).get('text', '')
        grid = visible_node('grid_games', secondary, root)
        cards = [n for n in grid.iter('node') if n.get('resource-id', '').endswith(':id/card_game')]
        selected = [card for card in cards if card.get('selected') == 'true']
        # The real-device fixture has unique visible titles; duplicate paths use HomeSelectionTest.
        if (not title or len(selected) != 1 or selected[0].get('content-desc') != title or
                sum(card.get('content-desc') == title for card in cards) != 1):
            raise AssertionError('Need one selected lower tile with a unique full-title label')
        return (selected[0].get('content-desc'), title)

    def verify_home_page(destination, identity=None, active_title=None, root=None):
        root = hierarchy() if root is None else root
        dump = windows()
        if home_count(dump) != 1:
            raise AssertionError('Upper navigation must retain exactly one lower HOME window')
        blocks = re.split(r'(?=Window #\d+ Window\{)', dump)
        component = package + '/org.citra.citra_emu.ui.main.' + home_activity
        for title, display_id in ((component, 0), ('Calamansi HOME}', secondary)):
            owners = [block for block in blocks if block.startswith('Window #') and
                      title in block.splitlines()[0]]
            if len(owners) != 1 or not re.search(
                    rf'(?:mDisplayId|displayId)={display_id}\b', owners[0]):
                raise AssertionError(f'{title} must have one owner on logical display {display_id}')
        grids = [n for n in root.iter('node') if n.get('package') == package and
                 n.get('resource-id', '').endswith(':id/grid_games')]
        if len(grids) != 1:
            raise AssertionError('Expected one accessible library grid, not duplicate hosts')
        grid = visible_node('grid_games', secondary, root)
        if node('card_game', grid) is None:
            raise AssertionError('Lower library lost its visible game tiles')
        actual_identity = selected_identity(root)
        if identity is not None and actual_identity != identity:
            raise AssertionError('Upper navigation changed the selected lower tile/title')
        for resource in ('home_open', 'home_properties'):
            control = visible_node(resource, secondary, root)
            if control.get('enabled') != 'true':
                raise AssertionError('Selected game action disabled: ' + resource)
        for resource in ('gamesFragment', 'searchFragment', 'homeSettingsFragment', 'appsFragment'):
            item = visible_node(resource, secondary, root)
            checked = item.get('checked') == 'true' or item.get('selected') == 'true'
            if checked != (resource == destination):
                raise AssertionError('Lower menu checked state disagrees with upper page: ' + resource)
            if sum(n.get('resource-id', '').endswith(':id/' + resource)
                   for n in root.iter('node') if n.get('package') == package) != 1:
                raise AssertionError('Primary navigation must be hidden while lower footer owns it')
        if active_title is not None:
            if visible_node('home_active_title', secondary, root).get('text') != active_title:
                raise AssertionError('Upper navigation lost the live active-game title')
            for resource in ('home_resume_active', 'home_active_settings'):
                visible_node(resource, secondary, root)
        controls = {
            'gamesFragment': ('home_preview_title',),
            'searchFragment': ('search_text', 'chip_recently_played', 'chip_recently_added'),
            'homeSettingsFragment': ('home_settings_list', 'option_card', 'option_title'),
            'appsFragment': ('apps_title', 'grid_apps', 'card_app', 'text_app_name'),
        }
        for resource in controls[destination]:
            visible_node(resource, 0, root)
        if destination == 'gamesFragment':
            if visible_node('home_preview_title', 0, root).get('text') != actual_identity[1]:
                raise AssertionError('Upper preview disagrees with lower selected title')
            for resource in ('search_text', 'grid_apps', 'home_settings_list'):
                if display_node(resource, 0, root) is not None:
                    raise AssertionError('Back/HOME retained a stale upper page: ' + resource)
        return root

    def wait_upper(destination):
        def ready():
            root = hierarchy()
            item = display_node(destination, secondary, root)
            control = {
                'gamesFragment': 'home_preview_title', 'searchFragment': 'search_text',
                'homeSettingsFragment': 'option_card', 'appsFragment': 'card_app',
            }[destination]
            return root if item is not None and display_node(control, 0, root) is not None and (
                item.get('checked') == 'true' or item.get('selected') == 'true') else None
        return wait_for(ready, 'Lower footer never selected upper page: ' + destination)

    def verify_shoulder_navigation():
        identity = selected_identity(hierarchy())
        for key, destinations in (
                ('KEYCODE_BUTTON_R1', ('searchFragment', 'homeSettingsFragment', 'appsFragment', 'gamesFragment')),
                ('KEYCODE_BUTTON_L1', ('appsFragment', 'homeSettingsFragment', 'searchFragment', 'gamesFragment'))):
            for destination in destinations:
                report['shoulder_step'] = {'key': key, 'expected': destination}
                shell('input -d 0 keyevent ' + key)
                root = wait_upper(destination)
                verify_home_page(destination, identity, root=root)
        capture('01-shoulder-wraparound')
        report['shoulder_navigation'] = True

    def verify_primary_input_focus():
        focus_dump = shell('dumpsys window displays')
        focus = re.findall(r'mCurrentFocus=Window\{[^\n]*\}', focus_dump)
        component = package + '/org.citra.citra_emu.ui.main.' + home_activity
        if not any(component in focused for focused in focus):
            raise AssertionError('Upper page lacks the native input-focused activity window')

    def verify_upper_navigation(label, active_title=None):
        root = verify_home_page('gamesFragment', active_title=active_title)
        identity = selected_identity(root)
        grid = visible_node('grid_games', secondary, root)
        tiles = [(n.get('content-desc'), n.get('bounds')) for n in grid.iter('node')
                 if n.get('resource-id', '').endswith(':id/card_game')]
        def search_has_text(value):
            search = display_node('search_text', 0, hierarchy())
            return search is not None and search.get('text') == value
        for destination, name in (('searchFragment', 'search'),
                                  ('homeSettingsFragment', 'options'), ('appsFragment', 'apps')):
            tap(required_node(destination), secondary)
            wait_upper(destination)
            root = verify_home_page(destination, identity, active_title)
            if destination == 'searchFragment':
                initial_search_text = visible_node('search_text', 0, root).get('text', '')
                tap(visible_node('search_text', 0, root), 0)
                shell('input -d 0 text Fire%sEmblem')
                wait_for(lambda: search_has_text('Fire Emblem'),
                         'Primary text input did not reach upper Search')
                for key in ('KEYCODE_DPAD_LEFT', 'KEYCODE_DPAD_RIGHT'):
                    shell('input -d 0 keyevent ' + key)
                    root = wait_upper(destination)
                    verify_home_page(destination, identity, active_title, root)
                root = verify_home_page(destination, identity, active_title)
                search = visible_node('search_text', 0, root)
                if search.get('focused') != 'true' or search.get('text') != 'Fire Emblem':
                    raise AssertionError('Lower key routing stole the upper text input focus')
                verify_primary_input_focus()
                visible_node('grid_games_search', 0, root)
                capture(label + '-search')
                tap(visible_node('clear_button', 0, root), 0)
                wait_for(lambda: search_has_text(initial_search_text),
                         'Search clear did not clear actual upper text')
                # First Back dismisses the real IME; the second returns the upper preview.
                shell('input -d 0 keyevent KEYCODE_BACK')
                if display_node('search_text', 0, hierarchy()) is not None:
                    shell('input -d 0 keyevent KEYCODE_BACK')
            elif destination == 'appsFragment':
                # Focus the real upper window without activating an application.
                tap(visible_node('apps_title', 0, root), 0)
                for key in ('KEYCODE_DPAD_DOWN', 'KEYCODE_DPAD_RIGHT'):
                    shell('input -d 0 keyevent ' + key)
                    root = verify_home_page(destination, identity, active_title)
                grid = visible_node('grid_apps', 0, root)
                if not any(card.get('resource-id', '').endswith(':id/card_app') and
                           card.get('focused') == 'true' for card in grid.iter('node')):
                    raise AssertionError('Upper Apps controller focus was stolen by the lower library')
                verify_primary_input_focus()
                capture(label + '-' + name)
                shell('input -d 0 keyevent KEYCODE_BACK')
            else:
                capture(label + '-' + name)
                shell('input -d 0 keyevent KEYCODE_BACK')
            wait_upper('gamesFragment')
            root = verify_home_page('gamesFragment', identity, active_title)
            grid = visible_node('grid_games', secondary, root)
            after_tiles = [(n.get('content-desc'), n.get('bounds')) for n in grid.iter('node')
                           if n.get('resource-id', '').endswith(':id/card_game')]
            if after_tiles != tiles:
                raise AssertionError('Upper-page switching changed lower visible tiles/scroll position')
        if args.home_role:
            tap(required_node('homeSettingsFragment'), secondary)
            wait_upper('homeSettingsFragment')
            verify_home_page('homeSettingsFragment', identity, active_title)
            shell('input -d 0 keyevent KEYCODE_HOME')
            wait_for(lambda: resumed('ConsoleHomeActivity'), 'Console HOME did not resume its owner')
            wait_upper('gamesFragment')
            verify_home_page('gamesFragment', identity, active_title)
            capture(label + '-console-home-preview')
        report.setdefault('persistent_lower_navigation', []).append({
            'label': label, 'selected_tile_label': identity[0], 'selected_title': identity[1],
            'lower_display': secondary, 'upper_display': 0, 'active_title': active_title,
            'pages': ['searchFragment', 'homeSettingsFragment', 'appsFragment'],
            'identity_assumption': 'Unique visible titles; duplicate-path identity uses HomeSelectionTest',
        })

    def required_node(resource):
        return wait_for(lambda: node(resource), 'Missing actual accessibility node: ' + resource)

    def tap(n, display_id):
        values = [int(v) for v in re.findall(r'\d+', n.get('bounds', ''))]
        if len(values) != 4 or values[2] <= values[0] or values[3] <= values[1]:
            raise AssertionError('Invalid visible target bounds: ' + str(n.attrib))
        shell(f'input -d {display_id} tap {(values[0] + values[2]) // 2} {(values[1] + values[3]) // 2}')

    def launch_home():
        role_flags = '-a android.intent.action.MAIN -c android.intent.category.HOME ' if args.home_role else ''
        shell('am start -W -f 0x10020000 --ez ConsoleHome true ' + role_flags + '-n ' +
              shlex.quote(package + '/org.citra.citra_emu.ui.main.' + home_activity))
        wait_for(lambda: home_count() == 1, 'Expected exactly one actual Calamansi HOME window')

    def capture(label):
        dump = windows()
        (args.evidence / (label + '-hierarchy.xml')).write_bytes(ET.tostring(hierarchy()))
        (args.evidence / (label + '-activities.txt')).write_text(
            shell('dumpsys activity activities'), encoding='utf-8')
        (args.evidence / (label + '-windows.txt')).write_text(dump, encoding='utf-8')
        for logical, physical in displays.items():
            image = adb('exec-out', f'screencap -p -d {physical}', binary=True)
            if not image.startswith(b'\x89PNG\r\n\x1a\n'):
                raise AssertionError(f'No PNG capture for display {logical}/{physical}')
            (args.evidence / f'{label}-display-{logical}.png').write_bytes(image)
        report['steps'].append(label)

    def selected_title():
        n = display_node('home_selected_title', secondary, hierarchy())
        return None if n is None else n.get('text')

    def select_title(title):
        root = hierarchy()
        selected = display_node('home_selected_title', secondary, root)
        if selected is not None and selected.get('text') == title:
            return
        grid = wait_for(lambda: display_node('grid_games', secondary, hierarchy()),
                        'Lower library was not ready for title selection')
        tile = next((n for n in grid.iter('node') if n.get('resource-id', '').endswith(':id/card_game') and n.get('content-desc') == title), None)
        if tile is None:
            raise AssertionError('Required visible game tile absent: ' + title)
        tap(tile, secondary)
        wait_for(lambda: selected_title() == title, 'Selection did not update to ' + title)

    try:
        adb('get-state')
        battery = shell('dumpsys battery')
        if not re.search(r'(AC|USB|Wireless|Dock) powered: true', battery):
            raise AssertionError('Device must be charging; no battery drain test')
        level = re.search(r'level: (\d+)', battery)
        temperature = re.search(r'temperature: (\d+)', battery)
        if not level or int(level[1]) < 40 or (temperature and int(temperature[1]) >= 400):
            raise AssertionError('Need battery >=40% and temperature <40C')
        if shell('[ -e ' + shlex.quote(nonce) + ' ] && echo EXISTS || echo CLEAR').strip() != 'CLEAR':
            raise AssertionError('Nonce test path already exists')
        if re.search(r'ActivityRecord\{[^\n]*' + re.escape(package) + r'[^\n]*EmulationActivity',
                     shell('dumpsys activity activities')):
            raise AssertionError('Refusing to interrupt an existing emulation session')
        if args.home_role:
            resolved = shell('cmd package resolve-activity --brief -a android.intent.action.MAIN '
                             '-c android.intent.category.HOME')
            if package + '/org.citra.citra_emu.ui.main.ConsoleHomeActivity' not in resolved:
                raise AssertionError('Grant the Android HOME role through Options before this check')
        owns_device = True
        shell('am force-stop ' + shlex.quote(package))
        display_dump = shell('dumpsys display')
        (args.evidence / 'displays.txt').write_text(display_dump, encoding='utf-8')
        displays = {}
        for block in re.split(r'(?=\n\s*Display \d+:)', display_dump):
            logical = re.search(r'mDisplayId=(\d+)', block)
            physical = re.search(r'local:(\d+)', block)
            if logical and physical and 'HiddenDisplay' not in block:
                displays[int(logical[1])] = physical[1]
        if len(displays) < 2:
            raise AssertionError('Could not map two actual logical/physical displays from dumpsys display')
        baseline = manifest()
        (args.evidence / 'save-config-before.json').write_text(json.dumps(baseline, indent=2), encoding='utf-8')
        shell('input keyevent KEYCODE_WAKEUP')
        launch_home()
        home_dump = windows()
        home_window = next(line for line in home_dump.splitlines() if re.search(r'Window #\d+ Window\{[^\n]*Calamansi HOME\}', line))
        section = home_dump.split(home_window, 1)[1].split('Window #', 1)[0]
        actual_display = re.search(r'(?:mDisplayId|displayId)=(\d+)', section)
        if actual_display is None:
            raise AssertionError('HOME window has no observable display ID')
        secondary = int(actual_display[1])
        if secondary != 4 or secondary not in displays or 0 not in displays:
            raise AssertionError('This physical dual-screen check requires mapped upper 0 and lower 4')
        root = hierarchy()
        verify_home_page('gamesFragment', root=root)
        verify_shoulder_navigation()
        grid = visible_node('grid_games', secondary, root)
        titles = [n.get('content-desc') for n in grid.iter('node') if n.get('resource-id', '').endswith(':id/card_game')]
        fates = next((t for t in titles if t and args.game_title.casefold() in t.casefold()), None)
        other = next((t for t in titles if t and t != fates), None)
        if not fates or not other:
            raise AssertionError('Need Fates and another visible tile in real accessibility hierarchy')
        select_title(other)
        capture('01-other-selection')
        select_title(fates)
        capture('02-fates-selection')
        verify_upper_navigation('02-upper')
        root = hierarchy()
        props_identity = selected_identity(root)
        props_positions = [(n.get('content-desc'), n.get('bounds'))
                           for n in visible_node('grid_games', secondary, root).iter('node')
                           if n.get('resource-id', '').endswith(':id/card_game')]
        other_tile = next(n for n in visible_node('grid_games', secondary, root).iter('node')
                          if n.get('resource-id', '').endswith(':id/card_game') and
                          n.get('content-desc') == other)
        tap(required_node('home_properties'), secondary)
        wait_for(lambda: node('game_settings') is not None and home_count() == 1,
                 'Properties must retain the lower library')
        if display_node('grid_games', secondary, hierarchy()) is not None:
            raise AssertionError('Modal background remained exposed to accessibility actions')
        tap(other_tile, secondary)
        if node('game_settings') is None:
            raise AssertionError('Lower-library tap escaped the Properties modal')
        capture('03-properties')
        # Safe nested dialog: cancel without deleting any shader cache or changing settings.
        tap(required_node('delete_cache'), 0)
        cancel = wait_for(lambda: node('button2'), 'Shader picker Cancel absent')
        tap(cancel, 0)
        if home_count() != 1 or display_node('grid_games', secondary, hierarchy()) is not None:
            raise AssertionError('Nested Properties dialog lost the read-only lower library')
        shell('input keyevent KEYCODE_BACK')
        wait_for(lambda: home_count() == 1, 'HOME did not restore after final modal closure')
        wait_for(lambda: selected_title() == fates, 'Selection lost across modal')
        if selected_identity(hierarchy()) != props_identity:
            raise AssertionError('Read-only lower library changed selection or scroll behind Properties')
        restored_positions = [(n.get('content-desc'), n.get('bounds'))
                              for n in visible_node('grid_games', secondary, hierarchy()).iter('node')
                              if n.get('resource-id', '').endswith(':id/card_game')]
        if restored_positions != props_positions:
            raise AssertionError('Read-only lower library changed scroll behind Properties')
        tap(required_node('home_properties'), secondary)
        tap(required_node('cheats'), 0)
        wait_for(lambda: display_node('fab', 0, hierarchy()),
                 'Properties did not enter the actual upper Cheats screen')
        capture('03-cheats')
        for _ in range(2):
            shell('input -d 0 keyevent KEYCODE_BACK')
            if display_node('home_preview_title', 0, hierarchy()) is not None:
                break
        wait_upper('gamesFragment')
        verify_home_page('gamesFragment')
        if selected_title() != fates:
            raise AssertionError('Cheats/Back changed the selected game')
        capture('03-cheats-return')
        tap(required_node('home_properties'), secondary)
        tap(required_node('game_settings'), 0)
        wait_for(lambda: resumed('SettingsActivity'), 'Per-game settings did not resume')
        if home_count() != 0:
            raise AssertionError('HOME leaked during per-title settings')
        capture('03-per-title-settings')
        shell('input keyevent KEYCODE_BACK')
        wait_for(lambda: home_count() == 1, 'HOME did not restore after settings')
        wait_for(lambda: selected_title() == fates, 'Selection lost across settings')
        if args.home_role:
            tap(required_node('homeSettingsFragment'), secondary)
            wait_upper('homeSettingsFragment')
            verify_home_page('homeSettingsFragment')
            launcher = wait_for(lambda: text_node(r'^Android launcher$'), 'Android-launcher action absent')
            tap(launcher, 0)
            wait_for(lambda: not resumed(home_activity), 'Android-launcher action did not leave Calamansi HOME')
        else:
            shell('input keyevent KEYCODE_HOME')
        wait_for(lambda: home_count() == 0, 'HOME leaked while app backgrounded')
        launch_home()
        wait_for(lambda: selected_title() == fates, 'Selection lost across resume')
        shell('input keyevent KEYCODE_SLEEP')
        wait_for(lambda: home_count() == 0, 'HOME leaked while device slept')
        shell('input keyevent KEYCODE_WAKEUP')
        launch_home()
        capture('04-restored-home')
        shell('input -d 0 keyevent KEYCODE_DPAD_LEFT')
        before_key = selected_title()
        shell('input -d 0 keyevent KEYCODE_DPAD_LEFT')
        wait_for(lambda: selected_title() is not None and selected_title() != before_key,
                 'D-pad did not select another real tile')
        capture('04-controller-selection')
        select_title(fates)
        started_emulation = True
        tap(required_node('home_open'), secondary)
        wait_for(lambda: resumed('EmulationActivity'), 'Open did not resume actual EmulationActivity')
        wait_for(lambda: home_count() == 0, 'HOME leaked during emulation handoff')
        capture('05-emulation-handoff')
        verify_active_session()
        # Stop execution immediately; never invoke save or overwrite a state.
        shell('am force-stop ' + shlex.quote(package))
        started_emulation = False
        launch_home()
        select_title(fates)
        capture('06-final-home')
        shell('input -d 0 keyevent KEYCODE_DPAD_LEFT')
        shell('input -d 0 keyevent KEYCODE_DPAD_LEFT')
        controller_title = selected_title()
        if not controller_title:
            raise AssertionError('Controller has no selected game')
        started_emulation = True
        shell('input -d 0 keyevent KEYCODE_BUTTON_A')
        wait_for(lambda: resumed('EmulationActivity'), 'A did not launch actual EmulationActivity')
        wait_for(lambda: home_count() == 0, 'HOME leaked during controller handoff')
        capture('07-controller-handoff')
        report['controller_game'] = controller_title
        shell('am force-stop ' + shlex.quote(package))
        started_emulation = False
        report['passed'] = True
    except Exception as error:
        report['error'] = str(error)
        if owns_device and baseline is not None:
            capture('failure')
        raise
    finally:
        try:
            if started_emulation:
                shell('am force-stop ' + shlex.quote(package))
            if baseline is not None:
                after = manifest()
                (args.evidence / 'save-config-after.json').write_text(json.dumps(after, indent=2), encoding='utf-8')
                if after != baseline:
                    report['passed'] = False
                    report['error'] = 'Save/state/native-config bytes changed; see manifests'
                    raise AssertionError('Save/state/native-config bytes changed; see manifests (no automatic rollback)')
        finally:
            try:
                if owns_device:
                    shell('rm -f ' + shlex.quote(nonce))
                    shell('input keyevent KEYCODE_HOME')
                    shell('input keyevent KEYCODE_SLEEP')
            except (RuntimeError, subprocess.TimeoutExpired):
                pass
            (args.evidence / 'home-smoke.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
    print('HOME/persistent-upper-pages/controller/modal/session/gear/resume' +
          ('/All Apps/Android Settings' if args.home_role else '') +
          ' and save/config preservation checks passed')


if __name__ == '__main__':
    main()
