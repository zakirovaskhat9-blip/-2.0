"""Рисует иконку exe (enchanted.ico) - запускается при сборке на GitHub."""
from logo import draw_logo

img = draw_logo(256)
img.save("enchanted.ico", sizes=[(256, 256), (128, 128), (64, 64), (48, 48), (32, 32), (16, 16)])
img.save("logo.png")
