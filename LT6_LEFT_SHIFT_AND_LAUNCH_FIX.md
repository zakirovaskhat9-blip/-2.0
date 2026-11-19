# LT6 / LT6MS — Left Shift + Mojang metadata fix

## LT6 GUI

- LT6 opens with **Left Shift** by default **in a world**.
- In Minecraft: **Options → Controls → Key Binds → LT6 → Open LT6 menu** (Настройки → Управление → Назначение клавиш → LT6). You can reassign the key there.
- **Important:** Minecraft may keep an older LT6 key assignment in `options.txt`. If it still opens on Right Shift, use **Reset** next to the LT6 binding in Controls to apply the new default.
- Left Shift is also Minecraft's default Sneak key. If both actions interfere, change Sneak to a different key in Controls, or rebind LT6.
- Client command **`/lt6`** also opens the menu after the chat closes, if key binding does not work.

## Launcher / GitHub

- Mojang metadata requests get more reasonable timeouts and can try alternate mirrors instead of failing instantly.
- The installation watchdog waits longer so it does not cancel a valid download during slow responses.
- GitHub Actions builds **`lt6.jar`** and embeds it into **LT6MS Launcher.exe**.
- Six provided Custom Sky panoramas are preserved; previous sky collections and video sky are not included.

## Limits

Full Fabric/.NET compilation and actual Minecraft launch were **not tested** in this environment. Review GitHub Actions output after uploading all files. If launcher errors persist, provide `%APPDATA%\.enchanted\launcher.log`.
