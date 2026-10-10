#!/usr/bin/env python3
"""Compare frame-matched top-screen PNG captures; requires Pillow and numpy.

Example: python tools/verify-fates-rendering.py before/ after/ --effect fxaa
Captures must have identical filenames, dimensions, savestate and frame numbers.
Use --effect accurate for default-path/reference checks, bloom for game-effect
removal, or fxaa for edge filtering. A no-op enhancement fails explicitly.
"""
import argparse
import json
from pathlib import Path

import numpy as np
from PIL import Image, ImageFilter


def compare(before, after, regions=()):
    a = Image.open(before).convert("RGB")
    b = Image.open(after).convert("RGB")
    if a.size != b.size:
        raise ValueError(f"Capture dimensions differ: {a.size} != {b.size}")
    if min(a.size) < 3:
        raise ValueError("Captures must be at least 3x3 pixels")
    aa, bb = np.asarray(a, dtype=np.float32), np.asarray(b, dtype=np.float32)
    delta = np.abs(aa - bb)
    blurred = np.abs(np.asarray(a.filter(ImageFilter.GaussianBlur(2)), dtype=np.float32)
                     - np.asarray(b.filter(ImageFilter.GaussianBlur(2)), dtype=np.float32))
    ui_differences = []
    for x, y, width, height in regions:
        if x < 0 or y < 0 or width <= 0 or height <= 0 or x + width > a.width or y + height > a.height:
            raise ValueError(f"Unchanged region is outside the capture: {(x, y, width, height)}")
        ui_differences.append(float(delta[y:y + height, x:x + width].max()))

    def edge_energy(pixels):
        luma = pixels @ np.array([0.299, 0.587, 0.114], dtype=np.float32)
        return float(np.abs(4 * luma[1:-1, 1:-1] - luma[:-2, 1:-1]
                            - luma[2:, 1:-1] - luma[1:-1, :-2] - luma[1:-1, 2:]).mean())

    return {
        "mean_absolute_difference": float(delta.mean()),
        "blurred_difference": float(blurred.mean()),
        "pixels_over_40_percent": float((delta.max(axis=2) > 40).mean() * 100),
        "changed_pixels_percent": float((delta.max(axis=2) > 1).mean() * 100),
        "brightness_change": float((bb - aa).mean()),
        "edge_energy_before": edge_energy(aa),
        "edge_energy_after": edge_energy(bb),
        "unchanged_region_max_differences": ui_differences,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("before", type=Path)
    parser.add_argument("after", type=Path)
    parser.add_argument("--effect", choices=("accurate", "bloom", "fxaa"), required=True)
    parser.add_argument("--pattern", default="*.png", help="Frame filename glob, e.g. host-*.png")
    parser.add_argument("--unchanged-region", type=int, nargs=4, action="append", default=[],
                        metavar=("X", "Y", "WIDTH", "HEIGHT"),
                        help="Require an opaque UI rectangle to remain unchanged (repeatable)")
    args = parser.parse_args()
    files = sorted(args.before.glob(args.pattern))
    if not files or {f.name for f in files} != {f.name for f in args.after.glob(args.pattern)}:
        parser.error("Both directories must contain the same non-empty set of PNG frames")
    failed = []
    changed_pixels = edge_before = edge_after = 0.0
    for frame in files:
        try:
            result = compare(frame, args.after / frame.name, args.unchanged_region)
        except ValueError as error:
            parser.error(str(error))
        print(json.dumps({"frame": frame.name, **result}))
        changed_pixels += result["changed_pixels_percent"]
        edge_before += result["edge_energy_before"]
        edge_after += result["edge_energy_after"]
        if args.effect == "accurate":
            ok = result["blurred_difference"] <= 3 and result["pixels_over_40_percent"] < 0.1
        elif args.effect == "fxaa":
            ok = abs(result["brightness_change"]) < 1
        else:
            ok = True
        ok = ok and all(difference <= 1 for difference in result["unchanged_region_max_differences"])
        if not ok:
            failed.append(frame.name)
    if failed:
        raise SystemExit(f"FAIL ({args.effect}): {', '.join(failed)}")
    # Restored LCD images can precede the first newly rendered game frame.
    # Keep every frame's accuracy/UI checks; assess enhancement gain across the whole sequence.
    if args.effect != "accurate":
        if (changed_pixels / len(files) <= (0.1 if args.effect == "fxaa" else 1)
                or (args.effect == "fxaa" and edge_after >= edge_before * 0.99)):
            raise SystemExit(f"FAIL ({args.effect}): no measured enhancement gain across the sequence")
    print(f"PASS: {len(files)} frame-matched {args.effect} comparisons")


if __name__ == "__main__":
    main()
