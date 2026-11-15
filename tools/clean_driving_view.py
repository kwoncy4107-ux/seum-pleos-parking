# -*- coding: utf-8 -*-
"""Clean the Pleos driving view (left panel) of a recorded scene for the demo video.

The emulator's driving view shows highway-like traffic and many lanes, which looks odd for a parking demo.
For every frame this keeps only our own car and the two lines of our own lane, and paints the rest with the
row's background colour. Output is a video of just that panel region, to be overlaid by make_demo_video.py.

Usage: python -I tools/clean_driving_view.py <scene.mp4> <out.mp4> [until_sec]
"""
import subprocess, sys
import numpy as np
from PIL import Image, ImageFilter
from scipy import ndimage

SRC, OUT = sys.argv[1], sys.argv[2]
UNTIL = float(sys.argv[3]) if len(sys.argv) > 3 else None
X0, Y0, W, H = 0, 190, 640, 648          # panel region below the speed, above the media bar
FPS = 30
ys, xs = np.mgrid[0:H, 0:W]


def lane_lines(d):
    """Track our lane's two lines bottom-up. Returns a boolean mask of line pixels."""
    light = (d > 2.5) & (d < 40)
    mask = np.zeros((H, W), bool)
    for side in (-1, 1):
        x, miss = None, 0
        for y in range(H - 1, -1, -1):
            cols = np.where(light[y])[0]
            if len(cols) == 0:
                miss += 1
            else:
                runs = np.split(cols, np.where(np.diff(cols) > 1)[0] + 1)
                runs = [r for r in runs if len(r) <= 14]
                if x is None:   # seed: nearest thin run on this side of the centre
                    cand = [r for r in runs if (r.mean() - 320) * side > 60]
                    pick = min(cand, key=lambda r: abs(r.mean() - 320), default=None)
                else:
                    cand = [r for r in runs if abs(r.mean() - x) <= 7]
                    pick = min(cand, key=lambda r: abs(r.mean() - x), default=None)
                if pick is None:
                    miss += 1
                else:
                    x, miss = pick.mean(), 0
                    mask[y, max(pick[0] - 1, 0):pick[-1] + 2] = True
            if x is not None and miss > 60:
                break
    return mask


def clean(reg):
    bg = np.median(reg, axis=1, keepdims=True)
    d = (bg - reg).mean(axis=2)
    lines = lane_lines(d)
    body = (np.abs(d) > 2.5) & ~lines & (xs > 150) & (xs < 490) & (ys > 250)
    lab, n = ndimage.label(ndimage.binary_closing(body, iterations=2))
    car = np.zeros_like(body)
    if n:
        sizes = ndimage.sum(body, lab, range(1, n + 1))
        cents = ndimage.center_of_mass(body, lab, range(1, n + 1))
        best = max(range(n), key=lambda i: sizes[i] if 250 < cents[i][1] < 390 else -1)
        car = ndimage.binary_fill_holes(lab == best + 1)
    a = np.asarray(Image.fromarray((car * 255).astype('uint8')).filter(ImageFilter.MaxFilter(5))
                   .filter(ImageFilter.GaussianBlur(1.2))).astype(float)[..., None] / 255
    a = np.maximum(a, lines[..., None].astype(float))
    return (reg * a + bg * (1 - a)).clip(0, 255).astype(np.uint8)


dec = subprocess.Popen(["ffmpeg", "-hide_banner", "-loglevel", "error", "-i", SRC, *(["-t", str(UNTIL)] if UNTIL else []),
                        "-vf", f"fps={FPS},crop={W}:{H}:{X0}:{Y0}", "-f", "rawvideo", "-pix_fmt", "rgb24", "-"],
                       stdout=subprocess.PIPE)
enc = subprocess.Popen(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-f", "rawvideo", "-pix_fmt", "rgb24",
                        "-s", f"{W}x{H}", "-r", str(FPS), "-i", "-", "-c:v", "libx264", "-crf", "16", "-pix_fmt", "yuv420p", OUT],
                       stdin=subprocess.PIPE)
n = 0
while True:
    buf = dec.stdout.read(W * H * 3)
    if len(buf) < W * H * 3:
        break
    enc.stdin.write(clean(np.frombuffer(buf, np.uint8).reshape(H, W, 3).astype(float)).tobytes())
    n += 1
enc.stdin.close(); enc.wait(); dec.wait()
print("cleaned", n, "frames ->", OUT)
