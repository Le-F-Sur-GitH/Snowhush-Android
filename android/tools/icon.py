#!/usr/bin/env python3
"""Draws the Android app's icon: the night scene of scripts/make-icon.swift, snow falling and drifting under a street
lamp, as the two layers of an adaptive icon. The night and the lamp's light are the background; the lamp, the flakes
and the drift the foreground. Android masks both to whatever shape the home screen uses and keeps the middle two
thirds, so the scene is laid out for that part and carries on past it.

Needs numpy and Pillow: python3 android/tools/icon.py. The pictures it writes are kept in the repository.
"""

import os

import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(os.path.dirname(HERE), "app", "src", "main", "res", "mipmap-xxxhdpi")

SIDE = 432  # 108 dp at four pixels each
OVER = 3  # drawn this many times bigger and scaled down, for smooth edges
BODY = 824.0  # the side of the scene in the original's units
SHOWN = 320.0  # the pixels it takes here: a little more than the 288 a mask keeps


def rgb(value):
    return np.array([(value >> 16) & 255, (value >> 8) & 255, value & 255], dtype=np.float64) / 255.0


def ramp(t, stops):
    """A gradient: `stops` are (place, colour with alpha) pairs; before the first and after the last it holds."""
    places = [place for place, _ in stops]
    colours = np.array([colour for _, colour in stops], dtype=np.float64)
    return np.stack([np.interp(t, places, colours[:, channel]) for channel in range(colours.shape[1])], axis=-1)


def over(under, colour, alpha):
    """`colour` at `alpha` laid over an opaque picture."""
    return under * (1.0 - alpha[..., None]) + colour * alpha[..., None]


def lay(picture, colour, alpha):
    """`colour` at `alpha` laid over a see-through picture of straight red, green, blue and alpha."""
    under = picture[..., 3]
    out = alpha + under * (1.0 - alpha)
    mixed = (colour * alpha[..., None] + picture[..., :3] * (under * (1.0 - alpha))[..., None]) / np.maximum(out, 1e-6)[..., None]
    picture[..., :3] = mixed
    picture[..., 3] = out


def bezier(p0, p1, p2, p3, steps=200):
    t = np.linspace(0.0, 1.0, steps)[:, None]
    return (1 - t) ** 3 * p0 + 3 * (1 - t) ** 2 * t * p1 + 3 * (1 - t) * t**2 * p2 + t**3 * p3


def main():
    n = SIDE * OVER
    scale = SHOWN / BODY * OVER
    edge = (SIDE - SHOWN) / 2 * OVER
    px, py = np.meshgrid(np.arange(n) + 0.5, np.arange(n) + 0.5)
    # The original's coordinates: across from the scene's left edge, up from its bottom edge.
    x = (px - edge) / scale
    y = (n - py - edge) / scale
    soft = 1.0 / scale  # one pixel, in those units

    lamp = np.array([BODY / 2, BODY - 150.0])
    away = np.hypot(x - lamp[0], y - lamp[1])

    # Behind: the night, the lamp's halo and its beam.
    back = ramp(np.clip((BODY - y) / BODY, 0.0, 1.0), [(0.0, rgb(0x221B15)), (1.0, rgb(0x0E0C0A))])
    halo = ramp(np.clip(away / 420.0, 0.0, 1.0), [(0.0, [0.42]), (0.4, [0.12]), (1.0, [0.0])])[..., 0]
    back = over(back, rgb(0xFFD696), halo)
    below = np.clip(lamp[1] - y, 0.0, None)
    fall = np.clip(below / lamp[1], 0.0, 1.0)
    for layer in range(30):
        spread = 20.0 + (200.0 + layer * 7.0) * fall
        inside = np.clip((spread - np.abs(x - lamp[0])) / soft + 0.5, 0.0, 1.0) * (y <= lamp[1])
        back = over(back, rgb(0xFFDCA5), inside * (0.018 + (0.002 - 0.018) * fall))
    Image.fromarray((np.clip(back, 0, 1) * 255 + 0.5).astype(np.uint8), "RGB").resize((SIDE, SIDE), Image.LANCZOS).save(
        os.path.join(RES, "ic_launcher_background.png"), optimize=True
    )

    # In front: the lamp on its arm, the flakes, and the drift.
    front = np.zeros((n, n, 4))
    arm = (x >= lamp[0]) * np.clip((3.5 - np.abs(y - (BODY - 118.0 + 3.5))) / soft + 0.5, 0.0, 1.0)
    lay(front, rgb(0x2E2924), arm)
    up = np.clip((y - (lamp[1] + 2.0)) / 44.0, 0.0, 1.0)
    half = 54.0 + (26.0 - 54.0) * up
    head = np.clip((half - np.abs(x - lamp[0])) / soft + 0.5, 0.0, 1.0) * np.clip((22.0 - np.abs(y - (lamp[1] + 24.0))) / soft + 0.5, 0.0, 1.0)
    lay(front, rgb(0x2C2722), head)
    glow = ramp(np.clip(away / 70.0, 0.0, 1.0), [(0.0, list(rgb(0xFFF1D6)) + [0.9]), (1.0, list(rgb(0xFFD99A)) + [0.0])])
    lay(front, glow[..., :3], glow[..., 3])
    bulb = np.clip((1.0 - np.hypot((x - lamp[0]) / 34.0, (y - (lamp[1] + 1.0)) / 7.0)) * 7.0 / soft + 0.5, 0.0, 1.0)
    lay(front, rgb(0xFFF6E4), bulb)

    flakes = [(-120, 520, 9), (40, 590, 6), (150, 470, 11), (-40, 420, 7), (90, 360, 13), (-170, 330, 8),
              (200, 300, 7), (-90, 250, 12), (20, 230, 6), (250, 410, 6), (-230, 470, 5), (130, 560, 5)]
    for dx, height, radius in flakes:
        reach = np.hypot(x - (BODY / 2 + dx), y - height) / (radius * 1.9)
        flake = ramp(np.clip(reach, 0.0, 1.0), [(0.0, list(rgb(0xFFF3DC)) + [0.95]), (0.35, list(rgb(0xFFF3DC)) + [0.55]), (1.0, list(rgb(0xFFD696)) + [0.0])])
        lay(front, flake[..., :3], flake[..., 3])

    points = np.concatenate([
        bezier(np.array([-10.0, 196.0]), np.array([90.0, 214.0]), np.array([200.0, 160.0]), np.array([300.0, 172.0])),
        bezier(np.array([300.0, 172.0]), np.array([400.0, 184.0]), np.array([470.0, 222.0]), np.array([560.0, 214.0])),
        bezier(np.array([560.0, 214.0]), np.array([660.0, 206.0]), np.array([740.0, 160.0]), np.array([834.0, 168.0])),
    ])
    # Past the scene's sides the drift carries on level, out to the picture's edges.
    top = np.interp(x[0], points[:, 0], points[:, 1])
    drift = np.clip((top[None, :] - y) / soft + 0.5, 0.0, 1.0)
    pool = np.hypot(x - BODY / 2, y - 120.0) / 520.0
    snow = ramp(np.clip(pool, 0.0, 1.0), [(0.0, rgb(0xF7ECD9)), (0.35, rgb(0xC9BBA6)), (0.72, rgb(0x5D5A5C)), (1.0, rgb(0x2A2B30))])
    crust = np.clip((y - 120.0) / 100.0, 0.0, 1.0) * 0.16
    snow = over(snow, np.array([1.0, 1.0, 1.0]), crust)
    lay(front, snow, drift)

    picture = np.dstack([front[..., :3], front[..., 3:4]])
    Image.fromarray((np.clip(picture, 0, 1) * 255 + 0.5).astype(np.uint8), "RGBA").resize((SIDE, SIDE), Image.LANCZOS).save(
        os.path.join(RES, "ic_launcher_foreground.png"), optimize=True
    )
    print("wrote", RES)


if __name__ == "__main__":
    main()
