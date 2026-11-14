"""Рисует иконку exe (enchanted.ico) - запускается при сборке на GitHub."""
from logo import draw_logo, make_ico

make_ico("enchanted.ico")
draw_logo(256).save("logo.png")
