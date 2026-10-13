#!/usr/bin/env python3
"""Exercise real NDS shutdown after Activity destruction; never clears app data.

Requires Thor, an installed Calamansi APK, and a readable, legally owned NDS ROM.
The game is stopped through normal Activity teardown, so its SRAM is flushed.
"""
import argparse
import re
import shlex
import subprocess
import time
import xml.etree.ElementTree as ET


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--rom-uri', required=True)
    parser.add_argument('--package', default='org.azahar_emu.azahar.fatesfix')
    parser.add_argument('--library-title', action='append', default=[],
                        help='Expected game tile visible after teardown; repeat for DS and 3DS')
    parser.add_argument('--drawer', action='store_true',
                        help='Also exercise top-edge drawer, native pause and DS settings on English Thor')
    parser.add_argument('--shell', action='store_true',
                        help='Also check both white DS shells, open game apertures and absent toolbar on Thor')
    args = parser.parse_args()

    def adb(*command):
        result = subprocess.run(
            ['adb', '-s', args.serial, *command],
            capture_output=True, text=True, timeout=45, check=True)
        return result.stdout

    def shell(command):
        return adb('shell', command)

    def hierarchy():
        xml_path = '/sdcard/calamansi-nds-stop-check.xml'
        try:
            shell('uiautomator dump --windows --compressed ' + xml_path)
            return ET.fromstring(adb('exec-out', 'cat ' + xml_path))
        finally:
            shell('rm -f ' + xml_path)

    def library_ready():
        tree = hierarchy()
        titles = [
            node.get('content-desc') for node in tree.iter('node')
            if node.get('resource-id') == args.package + ':id/card_game'
        ]
        return all(titles.count(title) == 1 for title in args.library_title)

    def wait_for(condition, message):
        end = time.monotonic() + 30
        while time.monotonic() < end:
            value = condition()
            if value:
                return value
            time.sleep(0.25)
        raise AssertionError(message)

    def exercise_shell():
        import io
        from PIL import Image

        def visible_shell():
            panels = (
                ('4630946441858561667', (1920, 1080), (448, 156, 1472, 924),
                 ((200, 450), (1720, 450), (960, 80), (960, 1000))),
                ('4630946482288158084', (1240, 1080), (108, 156, 1132, 924),
                 ((40, 300), (1200, 300), (620, 80), (780, 1000))),
            )
            for display, size, aperture, margins in panels:
                result = subprocess.run(
                    ['adb', '-s', args.serial, 'exec-out', 'screencap', '-p', '-d', display],
                    capture_output=True, timeout=20, check=True)
                image = Image.open(io.BytesIO(result.stdout)).convert('RGB')
                assert image.size == size, 'This shell scenario requires Thor'
                if not all(min(image.getpixel(point)) > 225 for point in margins):
                    return False
                if not any(low != high for low, high in image.crop(aperture).getextrema()):
                    return False
            return True

        wait_for(visible_shell, 'White DS shells are missing or cover a native game picture')
        lower_nodes = [
            node for display in hierarchy().iter('display') if display.get('id') == '4'
            for node in display.iter('node') if node.get('package') == args.package
        ]
        assert not any(node.get('class') == 'android.widget.Button' for node in lower_nodes), \
            'Bottom emulator toolbar is still present'
        microphones = [node for node in lower_nodes if node.get('class') == 'android.widget.ImageButton']
        assert len(microphones) == 1, 'Lower shell must have one accessible microphone icon'
        microphone = microphones[0]
        assert microphone.get('content-desc'), 'Microphone needs an accessible name'
        left, top, right, bottom = map(int, re.findall(r'\d+', microphone.get('bounds')))
        assert (left + right) // 2 == 620 and (top + bottom) // 2 == 1002, \
            'Microphone is not centred in the lower shell margin'
        assert 924 <= top < bottom <= 1080, 'Microphone covers the native game picture'
        print('Both white DS shells, open native pictures and bottom-centre microphone checked')

    def exercise_drawer():
        import io
        from PIL import Image

        def frame():
            result = subprocess.run(
                ['adb', '-s', args.serial, 'exec-out', 'screencap', '-p',
                 '-d', '4630946482288158084'],
                capture_output=True, timeout=20, check=True)
            image = Image.open(io.BytesIO(result.stdout))
            assert image.size == (1240, 1080), 'This drawer scenario requires Thor'
            return image.crop((108, 156, 1132, 924)).tobytes()

        def node(text):
            tree = hierarchy()
            return next((n.attrib for d in tree.iter('display') if d.get('id') == '0'
                         for n in d.iter('node')
                         if n.get('package') == args.package and n.get('text') == text), None)

        def microphone_enabled():
            return next((n.get('enabled') for d in hierarchy().iter('display') if d.get('id') == '4'
                         for n in d.iter('node') if n.get('package') == args.package and
                         n.get('class') == 'android.widget.ImageButton'), None)

        def tap(text):
            item = wait_for(lambda: node(text), 'Missing drawer/settings action: ' + text)
            left, top, right, bottom = map(int, re.findall(r'\d+', item.get('bounds')))
            shell(f'input -d 0 tap {(left + right) // 2} {(top + bottom) // 2}')

        first = frame()
        wait_for(lambda: frame() != first, 'Guest must animate to prove native pause/resume')
        shell('input touchscreen -d 0 swipe 32 500 700 500 600')
        tap('Pause Emulation')
        wait_for(lambda: node('Resume Emulation'), 'Pause action did not enter paused state')
        time.sleep(0.5)
        paused = frame()
        time.sleep(0.5)
        assert frame() == paused, 'Native guest continued animating while paused'
        assert microphone_enabled() == 'false', 'Microphone accepts input while explicitly paused'

        tap('Settings')
        wait_for(
            lambda: any('ResumedActivity' in line and
                        '/org.calamansi.nds.NdsSettingsActivity' in line
                        for line in shell('dumpsys activity activities').splitlines()),
            'Drawer opened the wrong engine settings')
        shell('input -d 0 keyevent 4')
        time.sleep(0.75)
        paused_after_settings = frame()
        time.sleep(0.5)
        assert frame() == paused_after_settings, 'Settings return incorrectly resumed a paused guest'
        assert microphone_enabled() == 'false', 'Settings return re-enabled a paused microphone'
        shell('input touchscreen -d 0 swipe 32 500 700 500 600')
        tap('Resume Emulation')
        shell('input -d 0 tap 1500 500')
        wait_for(lambda: not node('Pause Emulation'), 'Drawer scrim did not close the menu')
        wait_for(lambda: frame() != paused_after_settings, 'Native guest did not resume')
        wait_for(lambda: microphone_enabled() == 'true', 'Resumed microphone is still disabled')
        print('Top-screen drawer, native pause/settings/resume and scrim close checked')

    # A live process after teardown is not enough: relaunch must reacquire the
    # real owner and boot a second native guest without a shutdown exception.
    for attempt in range(2):
        started = shell("date '+%m-%d %H:%M:%S.%3N'").strip()
        before = adb('logcat', '-b', 'crash', '-d')
        # Task replacement keeps Android's former root-component identity.
        # A fresh router task ensures the next launch runs its onCreate again.
        shell('am start -W --display 0 -f 0x18000000 -a android.intent.action.VIEW -n ' +
              shlex.quote(args.package + '/org.citra.citra_emu.activities.NintendoLaunchActivity') +
              ' -d ' + shlex.quote(args.rom_uri))
        pid = wait_for(
            lambda: shell('pidof ' + shlex.quote(args.package + ':engine_nds')).strip(),
            'NDS process did not start')
        wait_for(
            lambda: 'Game is now booting' in adb('logcat', '--pid=' + pid, '-d', '-T', started),
            'Native NDS guest did not boot')
        if args.shell:
            exercise_shell()
        if args.drawer:
            exercise_drawer()
        activity = re.search(
            r'^\s*\*\s*Hist\b[^\n]*ActivityRecord\{[^\n]*' + re.escape(args.package) +
            r'/org\.calamansi\.nds\.NdsActivity\}? t(\d+)\}',
            shell('dumpsys activity activities'), flags=re.MULTILINE)
        assert activity, 'Native NDS Activity has no task'
        shell('am start -W --display 0 --task ' + activity.group(1) +
              ' -f 0x10008000 -n ' +
              shlex.quote(args.package + '/org.citra.citra_emu.ui.main.MainActivity'))
        wait_for(
            lambda: not any('Hist' in line and
                            args.package + '/org.calamansi.nds.NdsActivity' in line
                            for line in shell('dumpsys activity activities').splitlines()),
            'NDS Activity did not finish after task replacement')
        time.sleep(3)
        after = adb('logcat', '-b', 'crash', '-d')
        new_crash = after[len(before):] if after.startswith(before) else after
        assert args.package + ':engine_nds' not in new_crash, new_crash
        assert pid in shell('pidof ' + shlex.quote(args.package + ':engine_nds')).split(), \
            'Native NDS process died during asynchronous shutdown'
        print('NDS Activity destruction, native shutdown and owner release:', attempt + 1)
        if args.library_title:
            wait_for(library_ready, 'Expected DS/3DS tiles are missing or duplicated after teardown')
            print('Mixed-console library retained:', ', '.join(args.library_title))


if __name__ == '__main__':
    main()
