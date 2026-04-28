"""Generate AegisPhone launcher icons (pure Python, no dependencies)
Design: Dark navy shield with gold "A" crest — clean & professional."""

import struct
import zlib
import os
import math


def make_png(width, height):
    """Generate PNG with a shield + A crest design"""
    def chunk(chunk_type, data):
        c = chunk_type + data
        return struct.pack(">I", len(data)) + c + struct.pack(
            ">I", zlib.crc32(c) & 0xFFFFFFFF
        )

    header = b"\x89PNG\r\n\x1a\n"
    ihdr = chunk(
        b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    )

    cx, cy = width // 2, height // 2
    r = cx * 0.9  # shield radius

    raw = b""
    for y in range(height):
        raw += b"\x00"  # filter byte
        for x in range(width):
            dx, dy = x - cx, y - cy
            dist = math.sqrt(dx * dx + dy * dy) / cx

            # Shield shape (squared circle with flat bottom)
            angle = math.atan2(dy, dx)
            shield_radius = r * (
                1.0 - 0.15 * abs(math.sin(angle))
            )
            inside = dist * cx < shield_radius

            if inside:
                # Gold crest area (central A shape)
                nx, ny = dx / cx, dy / cy
                # A-shape detection
                a_left = (ny < -0.1 and nx > -0.25 + 0.5 * (ny + 0.5) and nx < 0.25 - 0.5 * (ny + 0.5)) or \
                         (ny >= -0.1 and nx >= -0.08 + 0.35 * (ny + 0.5) and nx <= 0.08 - 0.35 * (ny + 0.5))
                if a_left:
                    raw += struct.pack("BBB", 255, 215, 50)  # Gold
                else:
                    raw += struct.pack("BBB", 15, 15, 46)  # Dark navy
            else:
                raw += struct.pack("BBB", 20, 20, 60)  # Outer edge

    idat = chunk(b"IDAT", zlib.compress(raw))
    iend = chunk(b"IEND", b"")
    return header + ihdr + idat + iend


def make_shield_foreground():
    """Return vector XML for the shield + A foreground"""
    return (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    android:width="108dp" android:height="108dp"\n'
        '    android:viewportWidth="108" android:viewportHeight="108">\n'
        # Shield outline (gold)
        '    <path android:fillColor="#FFD700"\n'
        '        android:pathData="'
        'M54,8L12,28v24c0,24 16,42 42,48'
        'c26,-6 42,-24 42,-48V28L54,8Z'
        '"/>\n'
        # Shield inner (dark)
        '    <path android:fillColor="#0F0F2E"\n'
        '        android:pathData="'
        'M54,16L20,32v20c0,19 13,34 34,39'
        'c21,-5 34,-20 34,-39V32L54,16Z'
        '"/>\n'
        # Gold A letter (crest)
        '    <path android:fillColor="#FFD700"\n'
        '        android:pathData="'
        'M54,36L40,72h8l3,-8h6l3,8h8L54,36Z'
        '"/>\n'
        # A crossbar
        '    <path android:fillColor="#FFD700"\n'
        '        android:pathData="'
        'M46,58h16v3H46Z'
        '"/>\n'
        # Small accent diamond at center
        '    <path android:fillColor="#FFFFFF"\n'
        '        android:pathData="'
        'M54,48l-3,3l3,3l3,-3Z'
        '"/>\n'
        '</vector>\n'
    )


def main():
    base = os.path.join(
        os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
        "hermes-android",
        "app",
        "src",
        "main",
        "res",
    )

    # PNG fallback icons for pre-v26
    icons = {
        "mipmap-mdpi": 48,
        "mipmap-hdpi": 72,
        "mipmap-xhdpi": 96,
        "mipmap-xxhdpi": 144,
        "mipmap-xxxhdpi": 192,
    }

    for folder, size in icons.items():
        dir_path = os.path.join(base, folder)
        os.makedirs(dir_path, exist_ok=True)
        filepath = os.path.join(dir_path, "ic_launcher.png")
        with open(filepath, "wb") as f:
            f.write(make_png(size, size))
        print(f"  {folder}/ic_launcher.png ({size}x{size})")

    # Drawable - background
    drawable_dir = os.path.join(base, "drawable")
    os.makedirs(drawable_dir, exist_ok=True)

    with open(os.path.join(drawable_dir, "ic_launcher_background.xml"), "w") as f:
        f.write(
            '<?xml version="1.0" encoding="utf-8"?>\n'
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    android:width="108dp" android:height="108dp"\n'
            '    android:viewportWidth="108" android:viewportHeight="108">\n'
            '    <path android:fillColor="#0F0F2E"\n'
            '        android:pathData="M0,0h108v108h-108z"/>\n'
            '</vector>\n'
        )

    # Drawable - foreground (shield + A)
    with open(os.path.join(drawable_dir, "ic_launcher_foreground.xml"), "w") as f:
        f.write(make_shield_foreground())

    # Adaptive icon
    anydpi_dir = os.path.join(base, "mipmap-anydpi-v26")
    os.makedirs(anydpi_dir, exist_ok=True)

    with open(os.path.join(anydpi_dir, "ic_launcher.xml"), "w") as f:
        f.write(
            '<?xml version="1.0" encoding="utf-8"?>\n'
            '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
            '    <background android:drawable="@drawable/ic_launcher_background"/>\n'
            '    <foreground android:drawable="@drawable/ic_launcher_foreground"/>\n'
            '</adaptive-icon>\n'
        )

    print("  🔱 AegisPhone icons regenerated!")
    print("    - Shield + gold A crest design")
    print("    - Adaptive icon (v26+) + PNG fallback (pre-v26)")


if __name__ == "__main__":
    main()
