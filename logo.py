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
