"""LT6MS launcher mark: a calm teal/lavender portal with a floating voxel cube.

Generated at build time for the Windows EXE/taskbar/desktop shortcut.
Drawn at 1024px before downscaling so all ICO resolutions stay legible.
"""
import io
import struct

from PIL import Image, ImageDraw, ImageFilter


SIZE = 1024


def draw_icon(size):
    s = SIZE
    img = Image.new("RGBA", (s, s), (16, 29, 49, 255))
    d = ImageDraw.Draw(img)

    # Deep blue vertical gradient, not a flat black / white background.
    for y in range(s):
        t = y / (s - 1)
        color = (
            round(22 - 9 * t),
            round(39 - 11 * t),
            round(65 - 15 * t),
            255,
        )
        d.line((0, y, s, y), fill=color)

    ambient = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    a = ImageDraw.Draw(ambient)
    a.ellipse((30, 15, 700, 700), fill=(55, 171, 173, 85))
    a.ellipse((490, 110, 1100, 840), fill=(126, 109, 192, 85))
    ambient = ambient.filter(ImageFilter.GaussianBlur(165))
    img = Image.alpha_composite(img, ambient)

    # Portal: stable recognisable silhouette even on a 16px shortcut icon.
    portal = [(512, 170), (748, 310), (748, 694),
              (512, 834), (276, 694), (276, 310), (512, 170)]
    halo = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    h = ImageDraw.Draw(halo)
    h.line(portal, fill=(83, 190, 205, 115), width=70, joint="curve")
    img = Image.alpha_composite(img, halo.filter(ImageFilter.GaussianBlur(49)))

    d = ImageDraw.Draw(img)
    segments = [
        ((512, 170), (748, 310), (142, 134, 207, 255)),
        ((748, 310), (748, 694), (123, 122, 198, 255)),
        ((748, 694), (512, 834), (113, 113, 198, 255)),
        ((512, 834), (276, 694), (85, 178, 196, 255)),
        ((276, 694), (276, 310), (78, 191, 196, 255)),
        ((276, 310), (512, 170), (105, 209, 207, 255)),
    ]
    for start, end, color in segments:
        d.line([start, end], fill=color, width=41)
        for point in (start, end):
            x, y = point
            d.ellipse((x - 20, y - 20, x + 20, y + 20), fill=color)

    # Three geometric faces of a floating block, with muted dimensional light.
    top = [(512, 349), (671, 441), (512, 535), (353, 441)]
    left = [(353, 441), (512, 535), (512, 708), (353, 616)]
    right = [(671, 441), (512, 535), (512, 708), (671, 616)]
    d.polygon(left, fill=(44, 111, 141, 255))
    d.polygon(right, fill=(80, 88, 153, 255))
    d.polygon(top, fill=(118, 203, 179, 255))
    d.line([top[0], top[1], top[2], top[3], top[0]], fill=(173, 233, 217, 255), width=8)
    d.line([(512, 535), (512, 708)], fill=(121, 154, 191, 255), width=5)

    # Thin outer edge keeps the icon clear against a light desktop wallpaper.
    d.rounded_rectangle((24, 24, s - 25, s - 25), radius=225,
                        outline=(104, 145, 179, 200), width=10)

    rounded = Image.new("L", (s, s), 0)
    ImageDraw.Draw(rounded).rounded_rectangle(
        (12, 12, s - 13, s - 13), radius=235, fill=255
    )
    img.putalpha(rounded)
    return img.resize((size, size), Image.Resampling.LANCZOS)


def draw_logo(size=512):
    return draw_icon(size)


def make_ico(path):
    """Include native 16..256px sizes for Explorer, shortcuts and the taskbar."""
    sizes = (16, 20, 24, 32, 40, 48, 64, 128, 256)
    blobs = []
    for size in sizes:
        buffer = io.BytesIO()
        draw_icon(size).save(buffer, format="PNG")
        blobs.append(buffer.getvalue())
    head = struct.pack("<HHH", 0, 1, len(sizes))
    offset = 6 + 16 * len(sizes)
    directory = b""
    for size, blob in zip(sizes, blobs):
        directory += struct.pack(
            "<BBBBHHII", size % 256, size % 256, 0, 0, 1, 32, len(blob), offset
        )
        offset += len(blob)
    with open(path, "wb") as target:
        target.write(head + directory + b"".join(blobs))
