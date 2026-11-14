"""Рисует enchanted.ico для exe (запускается при сборке на GitHub)."""
import sys
from logo import make_ico

make_ico(sys.argv[1] if len(sys.argv) > 1 else "enchanted.ico")
