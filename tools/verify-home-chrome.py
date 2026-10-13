#!/usr/bin/env python3
"""Check real HOME strip visibility across Android notification-shade transitions.

Open Calamansi HOME on an awake Thor first. Never launches/stops a game or edits
preferences/saves. Requires adb; checks native View visibility, not source text.
"""
import argparse
import re
import subprocess
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--package', default='org.azahar_emu.azahar.fatesfix')
    args = parser.parse_args()

    def shell(command):
        result = subprocess.run(['adb', '-s', args.serial, 'shell', command],
                                capture_output=True, text=True, timeout=30)
        if result.returncode:
            raise RuntimeError(result.stderr.strip() or result.stdout.strip())
        return result.stdout

    activities = shell('dumpsys activity activities')
    if re.search(r'Hist[^\n]*' + re.escape(args.package) +
                 r'/[^\n]*(?:EmulationActivity|NdsActivity|RetroArchActivity|DolphinActivity|CemuActivity)',
                 activities):
        raise AssertionError('Close any native game normally before this check')
    resumed = re.search(
        r'(?:mResumedActivity|topResumedActivity)[^\n]*ActivityRecord\{([0-9a-f]+) u\d+ (' +
        re.escape(args.package) + r'/[^\s}]*(?:MainActivity|ConsoleHomeActivity))',
        activities
    )
    if not resumed:
        raise AssertionError('Open Calamansi HOME on the upper display first')

    def status_visible():
        # Other HOME tasks can remain in the dump; inspect only the resumed one.
        block = re.search(
            r'(?ms)^  ACTIVITY ' + re.escape(resumed[2]) + ' ' + resumed[1] +
            r' pid=[^\n]*\n(.*?)(?=^TASK |^  ACTIVITY |\Z)',
            shell('dumpsys activity top')
        )
        assert block, 'Foreground HOME disappeared during the check'
        lines = [line for line in block[1].splitlines()
                 if re.search(r':id/home_status\}', line)]
        assert len(lines) == 1, 'Need exactly one foreground HOME status strip: ' + repr(lines)
        flags = re.search(r'\{[0-9a-f]+ ([VIG])', lines[0])
        assert flags, 'Missing native visibility flags: ' + lines[0]
        return flags[1] == 'V'

    def expect(visible):
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            if status_visible() == visible:
                return
            time.sleep(0.25)
        raise AssertionError('HOME strip must be ' + ('visible' if visible else 'hidden'))

    expect(True)
    try:
        # The first edge swipe can reveal Android's transient status bar;
        # the second expands the notification shade on immersive Android.
        shell('input touchscreen -d 0 swipe 960 4 960 540 450')
        shell('input touchscreen -d 0 swipe 960 4 960 540 450')
        expect(False)
        shell('cmd statusbar collapse')
        expect(True)
        shell('cmd statusbar expand-notifications')
        expect(False)
    finally:
        shell('cmd statusbar collapse')
    expect(True)
    print('PASS: native HOME Menu/clock/battery strip hides for notifications and restores')


if __name__ == '__main__':
    main()
