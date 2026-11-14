"""Логотип Enchanted: светящаяся буква E на фоне зелёной туманности."""
import random

from PIL import Image, ImageChops, ImageDraw, ImageFilter


def draw_logo(size=512):
    S = 1024
    bg = Image.new("L", (S, S), 0)
    ImageDraw.Draw(bg).rounded_rectangle((24, 24, S - 24, S - 24), radius=230, fill=255)

    glow = Image.new("RGB", (S, S), (0, 0, 0))
    g = ImageDraw.Draw(glow)
    g.ellipse((100, 260, 700, 840), fill=(18, 110, 76))
    g.ellipse((420, 100, 940, 640), fill=(26, 150, 104))
    g.ellipse((330, 330, 700, 700), fill=(70, 220, 150))
    glow = glow.filter(ImageFilter.GaussianBlur(110))
    img = ImageChops.screen(Image.new("RGB", (S, S), (5, 12, 10)), glow).convert("RGBA")

    rnd = random.Random(3)
    d = ImageDraw.Draw(img)
    for _ in range(46):
        x, y, r = rnd.randint(70, S - 70), rnd.randint(70, S - 70), rnd.choice((2, 2, 3, 4))
        v = rnd.randint(150, 255)
        d.ellipse((x - r, y - r, x + r, y + r), fill=(v, v, v, 255))

    segs = [((330, 284), (330, 740)), ((330, 284), (694, 284)),
            ((330, 512), (630, 512)), ((330, 740), (694, 740))]
    letter = Image.new("L", (S, S), 0)
    ld = ImageDraw.Draw(letter)
    for a, b in segs:
        ld.line([a, b], fill=255, width=84)
        for x, y in (a, b):
            ld.ellipse((x - 42, y - 42, x + 42, y + 42), fill=255)

    halo = Image.new("RGBA", (S, S), (124, 255, 178, 255))
    halo.putalpha(letter.filter(ImageFilter.GaussianBlur(42)).point(lambda v: int(v * 0.85)))
    img = Image.alpha_composite(img, halo)
    body = Image.new("RGBA", (S, S), (236, 255, 246, 255))
    body.putalpha(letter)
    img = Image.alpha_composite(img, body)

    ImageDraw.Draw(img).rounded_rectangle((24, 24, S - 24, S - 24), radius=230,
                                          outline=(40, 120, 86, 255), width=6)
    img.putalpha(bg)
    return img.resize((size, size), Image.LANCZOS)


def draw_icon(size):
    """Чёткая иконка для значка окна и панели задач: крупная плоская E без свечения на мелких размерах."""
    S = 1024
    small = size <= 48
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle((6, 6, S - 6, S - 6), radius=210, fill=(9, 32, 24, 255),
                        outline=(70, 210, 150, 255), width=34 if small else 20)
    w = 170 if small else 140
    x0, x1, y0, y1 = 262, 762, 196, 828
    r = w // 6
    letter = Image.new("L", (S, S), 0)
    ld = ImageDraw.Draw(letter)
    ld.rounded_rectangle((x0, y0, x0 + w, y1), radius=r, fill=255)
    ld.rounded_rectangle((x0, y0, x1, y0 + w), radius=r, fill=255)
    ld.rounded_rectangle((x0, 512 - w // 2, x1 - 70, 512 + w // 2), radius=r, fill=255)
    ld.rounded_rectangle((x0, y1 - w, x1, y1), radius=r, fill=255)
    if not small:
        halo = Image.new("RGBA", (S, S), (124, 255, 178, 255))
        halo.putalpha(letter.filter(ImageFilter.GaussianBlur(26)).point(lambda v: int(v * 0.6)))
        img = Image.alpha_composite(img, halo)
    body = Image.new("RGBA", (S, S), (232, 255, 244, 255))
    body.putalpha(letter)
    img = Image.alpha_composite(img, body)
    return img.resize((size, size), Image.LANCZOS)


def make_ico(path):
    """Пишет .ico, где каждый размер нарисован отдельно (а не уменьшен из одной картинки)."""
    import io
    import struct
    sizes = (16, 20, 24, 32, 40, 48, 64, 128, 256)
    blobs = []
    for s in sizes:
        b = io.BytesIO()
        draw_icon(s).save(b, "PNG")
        blobs.append(b.getvalue())
    head = struct.pack("<HHH", 0, 1, len(sizes))
    off = 6 + 16 * len(sizes)
    ent = b""
    for s, data in zip(sizes, blobs):
        ent += struct.pack("<BBBBHHII", s % 256, s % 256, 0, 0, 1, 32, len(data), off)
        off += len(data)
    with open(path, "wb") as f:
        f.write(head + ent + b"".join(blobs))
