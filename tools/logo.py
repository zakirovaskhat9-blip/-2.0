"""LT6MS identity: compact cyan L6 monogram, replacing the legacy E avatar.

Created at build time by make_icon.py. Icons have independent crisp sizes, and
the single-file EXE's embedded application icon is refreshed on every CI build.
"""
import io
import math
import struct

from PIL import Image, ImageDraw, ImageFilter


def draw_icon(size):
    S = 1024
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))

    # Minimal dark-blue rounded square, unobstructed at taskbar size.
    base = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    bd = ImageDraw.Draw(base)
    bd.rounded_rectangle((24, 24, 1000, 1000), radius=219,
                         fill=(10, 19, 32, 255),
                         outline=(61, 116, 155, 255), width=22)
    # Restrained ice-blue inset reflection.
    bd.rounded_rectangle((54, 54, 970, 970), radius=192,
                         outline=(31, 65, 95, 220), width=13)
    img = Image.alpha_composite(img, base)

    symbol = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    sd = ImageDraw.Draw(symbol)
    # Symmetric diamond frame in the same family as Nursultan Target ESP.
    points = [(512, 166), (852, 512), (512, 858), (172, 512), (512, 166)]
    sd.line(points, fill=(98, 196, 255, 240), width=56, joint="curve")
    for x, y in points[:4]:
        sd.ellipse((x - 28, y - 28, x + 28, y + 28), fill=(98, 196, 255, 240))

    # Letter L, hand-built geometry so it remains legible at 16x16.
    sd.line([(297, 365), (297, 662), (451, 662)],
            fill=(241, 250, 255, 255), width=73, joint="curve")
    for x, y in [(297, 365), (451, 662)]:
        sd.ellipse((x - 36, y - 36, x + 36, y + 36), fill=(241, 250, 255, 255))

    # Large stylized digit 6, not the old Enchanted E.
    sd.arc((474, 423, 740, 697), 0, 359,
           fill=(115, 220, 255, 255), width=70)
    sd.line([(680, 338), (540, 474)], fill=(115, 220, 255, 255),
            width=72, joint="curve")
    sd.ellipse((651, 309, 707, 365), fill=(115, 220, 255, 255))

    # Subtle halo on large icons; small windows get sharp opaque lines.
    if size >= 48:
        glow = symbol.filter(ImageFilter.GaussianBlur(32))
        glow.putalpha(glow.getchannel("A").point(lambda v: int(v * 0.30)))
        img = Image.alpha_composite(img, glow)
    img = Image.alpha_composite(img, symbol)
    return img.resize((size, size), Image.Resampling.LANCZOS)


def draw_logo(size=512):
    return draw_icon(size)


def make_ico(path):
    sizes = (16, 20, 24, 32, 40, 48, 64, 128, 256)
    blobs = []
    for size in sizes:
        data = io.BytesIO()
        draw_icon(size).save(data, "PNG")
        blobs.append(data.getvalue())
    header = struct.pack("<HHH", 0, 1, len(sizes))
    offset = 6 + 16 * len(sizes)
    entries = b""
    for size, data in zip(sizes, blobs):
        entries += struct.pack("<BBBBHHII", size % 256, size % 256, 0, 0,
                               1, 32, len(data), offset)
        offset += len(data)
    with open(path, "wb") as stream:
        stream.write(header + entries + b"".join(blobs))
