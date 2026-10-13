#!/usr/bin/env python3
"""Check cartridge motion on an awake Thor HOME without launching or stopping games.

Requires adb and Pillow. Keep Thor idle on Applications with a selected game and
system animations enabled. Captures both panels; the lower grid must stay still.
"""
import argparse
import io
from pathlib import Path
import re
import subprocess
import time

from PIL import Image, ImageChops


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--package', default='org.azahar_emu.azahar.fatesfix')
    parser.add_argument('--evidence', type=Path, required=True)
    args = parser.parse_args()

    def adb(*command):
        result = subprocess.run(['adb', '-s', args.serial, *command],
                                capture_output=True, timeout=30)
        if result.returncode:
            raise RuntimeError(result.stderr.decode(errors='replace').strip())
        return result.stdout

    def shell(command):
        return adb('shell', command).decode(errors='replace')

    activities = shell('dumpsys activity activities')
    assert not re.search(r'Hist[^\n]*' + re.escape(args.package) +
                         r'/[^\n]*(?:EmulationActivity|NdsActivity|RetroArchActivity|DolphinActivity|CemuActivity)',
                         activities), 'Close any native game normally first'
    assert re.search(r'(?:mResumedActivity|topResumedActivity)[^\n]*' +
                     re.escape(args.package) + r'/[^\n]*(?:MainActivity|ConsoleHomeActivity)',
                     activities), 'Open Calamansi HOME first'
    scale = shell('settings get global animator_duration_scale').strip()
    assert scale != '0.0' and scale != '0', 'Enable system animations for the motion check'
    assert ':id/home_preview_title}' in shell('dumpsys activity top'), 'Open Applications first'

    args.evidence.mkdir(parents=True, exist_ok=True)
    upper = []
    lower = []
    for index in range(8):
        for display, physical in [(0, '4630946441858561667'), (4, '4630946482288158084')]:
            data = adb('exec-out', 'screencap', '-p', '-d', physical)
            (args.evidence / f'cartridge-{index}-display-{display}.png').write_bytes(data)
            image = Image.open(io.BytesIO(data)).convert('RGB')
            if display == 0:
                assert image.size == (1920, 1080), 'This check uses the Thor upper display'
                # Cartridge only: exclude the clock, title and Android system bar.
                upper.append(image.crop((700, 240, 1220, 685)))
            else:
                assert image.size == (1240, 1080), 'This check uses the Thor lower display'
                lower.append(image.crop((0, 190, 1240, 850)))
        time.sleep(0.6)

    changed = [ImageChops.difference(upper[0], image).getbbox() is not None for image in upper[1:]]
    assert all(changed), 'Cartridge stayed static instead of rotating'
    assert all(ImageChops.difference(lower[0], image).getbbox() is None for image in lower[1:]), \
        'The lower game grid changed during cartridge rotation; leave Thor idle'
    upper[0].save(args.evidence / 'cartridge-motion.gif', save_all=True,
                  append_images=upper[1:], duration=750, loop=0)
    print('PASS: cartridge moves across eight real frames; lower game grid stays pixel-identical')


if __name__ == '__main__':
    main()
