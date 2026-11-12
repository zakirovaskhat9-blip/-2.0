"""FastLauncher v0.3 - быстрый лаунчер Minecraft (Fabric) с автоподбором настроек под ПК."""
import ctypes
import hashlib
import json
import lzma
import os
import re
import shutil
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path

import customtkinter as ctk
import minecraft_launcher_lib as mll
import psutil
import requests

ROOT = (Path(os.environ["APPDATA"]) if os.name == "nt" else Path.home()) / ".fastlauncher"
GAME = ROOT / "game"
CONF = ROOT / "config.json"
MODS_JSON = ROOT / "mods.json"
UA = {"User-Agent": "FastLauncher/0.1 (personal project)"}
DEFAULT_VERSION = "1.21.11"

# ---------- зеркала (на случай блокировки серверов Mojang / Modrinth) ----------
BMCL = "https://bmclapi2.bangbang93.com/"
GAME_RULES = [  # (официальный адрес, адрес на зеркале BMCLAPI)
    ("https://piston-meta.mojang.com/", BMCL),
    ("https://piston-data.mojang.com/", BMCL),
    ("https://launchermeta.mojang.com/", BMCL),
    ("https://launcher.mojang.com/", BMCL),
    ("https://resources.download.minecraft.net/", BMCL + "assets/"),
    ("http://resources.download.minecraft.net/", BMCL + "assets/"),
    ("https://libraries.minecraft.net/", BMCL + "maven/"),
]
MOD_RULES = [  # зеркало модов MCIM
    ("https://api.modrinth.com/", "https://mod.mcimirror.top/modrinth/"),
    ("https://cdn.modrinth.com/", "https://mod.mcimirror.top/"),
]
MIRROR = {"game": False, "mods_first": False, "allow": True}
NOTE = {"cb": None}
SOURCES = {"источник: авто": "auto", "источник: официальный": "official", "источник: зеркало": "mirror"}


def note_mirror(name):
    if NOTE["cb"]:
        NOTE["cb"](name)


def enable_game_mirror():
    MIRROR["game"] = True
    note_mirror("BMCLAPI (bangbang93)")


_orig_request = requests.sessions.Session.request


def _request(self, method, url, *a, **kw):
    """Все запросы библиотеки minecraft-launcher-lib идут через requests - подменяем адреса на зеркало."""
    if MIRROR["game"] and isinstance(url, str):
        for src, dst in GAME_RULES:
            if url.startswith(src):
                url = dst + url[len(src):]
                break
    return _orig_request(self, method, url, *a, **kw)


requests.sessions.Session.request = _request


def log_file(text):
    try:
        ROOT.mkdir(parents=True, exist_ok=True)
        with open(ROOT / "launcher.log", "a", encoding="utf-8") as f:
            f.write(time.strftime("%H:%M:%S ") + text + "\n")
    except Exception:
        pass


def _candidates(url):
    """Список адресов для одного файла: сначала то, что сейчас работает, потом запасной."""
    mirror = next((dst + url[len(src):] for src, dst in GAME_RULES if url.startswith(src)), None)
    if not MIRROR["allow"] or not mirror:
        return [url]
    return [mirror, url] if MIRROR["game"] else [url, mirror]


def robust_download(url, path, callback=None, sha1=None, lzma_compressed=False, session=None,
                    minecraft_directory=None, overwrite=False):
    """Замена download_file из minecraft-launcher-lib.
    Оригинал молча пропускает файл, если сервер ответил не 200 (например, зеркало вернуло 404/429),
    и потом установка падает с WinError 2. Здесь файл перекачивается с повторами и с обоих адресов,
    а если не получилось - выбрасывается понятная ошибка."""
    path = str(path)
    if os.path.isfile(path) and not overwrite:
        if sha1 is None:
            return False
        with open(path, "rb") as f:
            if hashlib.sha1(f.read()).hexdigest() == sha1:
                return False
    os.makedirs(os.path.dirname(path), exist_ok=True)
    if callback and callback.get("setStatus"):
        callback["setStatus"]("Скачиваю " + os.path.basename(path))
    sess = session or requests.Session()
    last = None
    for attempt in range(5):
        for u in _candidates(url):
            try:
                r = _orig_request(sess, "GET", u, headers=UA, timeout=(10, 60))
                if r.status_code != 200:
                    last = f"HTTP {r.status_code} ({u.split('/')[2]})"
                    if r.status_code == 429:
                        time.sleep(2 + attempt * 2)
                    continue
                data = r.content
                if lzma_compressed:
                    data = lzma.decompress(data)
                if sha1 and hashlib.sha1(data).hexdigest() != sha1:
                    last = f"не совпала контрольная сумма ({u.split('/')[2]})"
                    continue
                tmp = path + ".part"
                with open(tmp, "wb") as f:
                    f.write(data)
                os.replace(tmp, path)
                return True
            except Exception as e:
                last = f"{type(e).__name__} ({u.split('/')[2]})"
        time.sleep(1.5 * (attempt + 1))
    msg = f"не скачался файл {os.path.basename(path)}: {last}"
    log_file(msg + "  <- " + url)
    raise RuntimeError(msg)


def patch_library_downloads():
    """Подменяем download_file во всех модулях библиотеки (они импортируют его по имени)."""
    import minecraft_launcher_lib._helper as helper
    orig = helper.download_file
    for name, mod in list(sys.modules.items()):
        if name.startswith("minecraft_launcher_lib") and getattr(mod, "download_file", None) is orig:
            mod.download_file = robust_download


patch_library_downloads()


def reachable(url, timeout=5):
    try:
        urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=timeout)
        return True
    except urllib.error.HTTPError:
        return True  # сервер ответил, значит доступен
    except Exception:
        return False


# Профили производительности: память, настройки игры, графика
TIERS = {
    "low": dict(xmx=2048, render=6, sim=5, particles=2, fps=60, graphics=0),
    "mid": dict(xmx=4096, render=10, sim=8, particles=1, fps=120, graphics=1),
    "high": dict(xmx=6144, render=16, sim=12, particles=0, fps=260, graphics=1),
}
JVM_FLAGS = ["-XX:+UseG1GC", "-XX:MaxGCPauseMillis=100", "-XX:+ParallelRefProcEnabled"]


# ---------- утилиты ----------
def load_json(path, default):
    try:
        return json.loads(Path(path).read_text("utf-8"))
    except Exception:
        return default


def save_json(path, data):
    Path(path).parent.mkdir(parents=True, exist_ok=True)
    Path(path).write_text(json.dumps(data, ensure_ascii=False, indent=2), "utf-8")


def retry(fn, say=None, tries=5):
    """Повторяет действие при обрывах сети."""
    for i in range(1, tries + 1):
        try:
            return fn()
        except Exception:
            if i == tries:
                raise
            if say:
                say(f"Обрыв связи, повторяю ({i}/{tries - 1})...")
            time.sleep(2 * i)


def modrinth_open(url, timeout):
    """Сначала официальный Modrinth, при неудаче зеркало MCIM (и запоминаем, что оно работает)."""
    mirror = next((dst + url[len(src):] for src, dst in MOD_RULES if url.startswith(src)), None)
    if not MIRROR["allow"]:
        mirror = None
    order = [mirror, url] if (MIRROR["mods_first"] and mirror) else [url, mirror]
    last = None
    for u in order:
        if not u:
            continue
        try:
            r = urllib.request.urlopen(urllib.request.Request(u, headers=UA), timeout=timeout)
            if u != url:
                MIRROR["mods_first"] = True
                note_mirror("MCIM (mcimirror.top)")
            return r
        except Exception as e:
            last = e
    raise last


def get_json(url):
    def go():
        with modrinth_open(url, 10) as r:
            return json.load(r)
    return retry(go, tries=2)


# ---------- определение железа ----------
def has_vulkan():
    names = ["vulkan-1.dll"] if os.name == "nt" else ["libvulkan.so.1", "libvulkan.1.dylib"]
    for n in names:
        try:
            ctypes.CDLL(n)
            return True
        except OSError:
            pass
    return False


def gpu_name():
    if os.name != "nt":
        return ""
    try:
        out = subprocess.run(
            ["powershell", "-NoProfile", "-Command",
             "(Get-CimInstance Win32_VideoController | Select-Object -ExpandProperty Name) -join '; '"],
            capture_output=True, text=True, timeout=8, creationflags=0x08000000)
        return out.stdout.strip()
    except Exception:
        return ""


def is_weak_gpu(gpu):
    g = gpu.lower()
    if not g:
        return True
    if "intel" in g and "arc" not in g and not any(x in g for x in ("nvidia", "geforce", "radeon rx")):
        return True
    return bool(re.search(r"geforce\s+(gt\s?\d|gtx\s?[4-7]\d\d\b)|radeon\s+(hd|r[3-7]\b)", g))


def detect():
    ram = psutil.virtual_memory().total / 2**30
    cores = psutil.cpu_count(logical=True) or 2
    gpu = gpu_name()
    weak = is_weak_gpu(gpu)
    score = (2 if ram >= 16 else 1 if ram >= 8 else 0)
    score += (2 if cores >= 12 else 1 if cores >= 6 else 0)
    score += 0 if weak else 2
    # слабая или старая видеокарта -> всегда low, игра упирается именно в неё
    tier = "low" if weak else "low" if score <= 2 else "mid" if score <= 4 else "high"
    return dict(ram=ram, cores=cores, gpu=gpu or "не определена", vulkan=has_vulkan(), tier=tier)


# ---------- моды (Modrinth) ----------
def install_mod(slug, mc, manifest, mods_dir, log):
    q = urllib.parse.urlencode({"loaders": json.dumps(["fabric"]), "game_versions": json.dumps([mc])})
    versions = get_json(f"https://api.modrinth.com/v2/project/{slug}/version?{q}")
    if not versions:
        log(f"{slug}: нет версии под {mc}")
        return False
    files = versions[0]["files"]
    f = next((x for x in files if x.get("primary")), files[0])
    dest = mods_dir / f["filename"]
    old = manifest.get(slug)
    if old and old != f["filename"]:
        (mods_dir / old).unlink(missing_ok=True)
    if not dest.exists():
        log(f"Скачиваю {slug}...")
        def download():
            tmp = dest.parent / (dest.name + ".part")
            h = hashlib.sha512()
            with modrinth_open(f["url"], 60) as r, open(tmp, "wb") as out:
                while chunk := r.read(1 << 16):
                    out.write(chunk)
                    h.update(chunk)
            want = f.get("hashes", {}).get("sha512")
            if want and h.hexdigest() != want:
                tmp.unlink(missing_ok=True)
                raise RuntimeError("хеш файла не совпал")
            os.replace(tmp, dest)
        retry(download, log, tries=2)
    manifest[slug] = f["filename"]
    return True


def sync_mods(slugs, mc, log):
    """Приводит папку mods к нужному набору. Возвращает список модов, которые не удалось поставить."""
    mods_dir = GAME / "mods"
    mods_dir.mkdir(parents=True, exist_ok=True)
    manifest = load_json(MODS_JSON, {})
    for slug in list(manifest):
        if slug not in slugs:
            (mods_dir / manifest.pop(slug)).unlink(missing_ok=True)
    missing = []
    for slug in slugs:
        try:
            if not install_mod(slug, mc, manifest, mods_dir, log):
                missing.append(slug)
        except Exception as e:
            log(f"{slug}: {e}")
            missing.append(slug)
    save_json(MODS_JSON, manifest)
    return missing


def plan_mods(tier, vulkan):
    """Слабый ПК -> Sodium. Иначе, если есть Vulkan -> VulkanMod. Запасной вариант всегда Sodium."""
    base = ["fabric-api", "lithium", "ferrite-core"]
    sodium = base + ["sodium"] + (["iris"] if tier == "high" else [])
    if tier != "low" and vulkan:
        return base + ["vulkanmod"], sodium
    return sodium, sodium


def apply_options(tier):
    t = TIERS[tier]
    want = {"renderDistance": t["render"], "simulationDistance": t["sim"], "particles": t["particles"],
            "maxFps": t["fps"], "graphicsMode": t["graphics"]}
    p = GAME / "options.txt"
    lines = p.read_text("utf-8").splitlines() if p.exists() else []
    seen = set()
    for i, line in enumerate(lines):
        k = line.split(":", 1)[0]
        if k in want:
            lines[i] = f"{k}:{want[k]}"
            seen.add(k)
    lines += [f"{k}:{v}" for k, v in want.items() if k not in seen]
    GAME.mkdir(parents=True, exist_ok=True)
    p.write_text("\n".join(lines) + "\n", "utf-8")


def fabric_id(mc):
    ids = [v["id"] for v in mll.utils.get_installed_versions(GAME)
           if v["id"].startswith("fabric-loader-") and v["id"].endswith("-" + mc)]
    return ids[-1] if ids else None


# ---------- интерфейс ----------
def cores_word(n):
    return "ядро" if n % 10 == 1 and n % 100 != 11 else "ядра" if 2 <= n % 10 <= 4 and not 12 <= n % 100 <= 14 else "ядер"


def make_background(w, h):
    """Рисует туманность с звёздами и заголовок - картинка для фона окна."""
    import random
    from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageFont
    rnd = random.Random(7)
    img = Image.new("RGB", (w, h), (4, 8, 7))
    for color, n, rmin, rmax, blur in (((18, 84, 58), 9, 140, 300, 60), ((26, 150, 104), 6, 70, 170, 38),
                                       ((110, 255, 175), 4, 25, 80, 20)):
        layer = Image.new("RGB", (w, h), (0, 0, 0))
        d = ImageDraw.Draw(layer)
        for _ in range(n):
            x, y, r = rnd.randint(w // 3, w), rnd.randint(0, h), rnd.randint(rmin, rmax)
            d.ellipse((x - r, y - r // 2, x + r, y + r // 2), fill=color)
        img = ImageChops.screen(img, layer.filter(ImageFilter.GaussianBlur(blur)).point(lambda v: int(v * 0.72)))
    d = ImageDraw.Draw(img)
    for _ in range(140):
        x, y, v = rnd.randint(0, w - 1), rnd.randint(0, h - 1), rnd.randint(90, 255)
        d.point((x, y), fill=(v, v, v))

    def font(size, bold=False):
        for name in (("segoeuib.ttf" if bold else "segoeui.ttf"), "arialbd.ttf", "DejaVuSans-Bold.ttf"):
            try:
                return ImageFont.truetype(name, size)
            except OSError:
                pass
        try:
            return ImageFont.load_default(size)
        except TypeError:
            return ImageFont.load_default()

    d.text((438, 52), "MINECRAFT", font=font(62, True), fill=(236, 255, 246))
    d.text((442, 128), "Fabric  ·  быстрый запуск", font=font(19), fill=(124, 255, 178))
    return img


class App(ctk.CTk):
    W, H = 880, 560
    ACCENT = "#7CFFB2"
    CARD = "#0b100e"
    FIELD = "#121a17"
    LINE = "#1c3a2c"
    MUTED = "#6b7f76"

    def __init__(self):
        super().__init__()
        ctk.set_appearance_mode("dark")
        self.title("FastLauncher")
        self.geometry(f"{self.W}x{self.H}")
        self.resizable(False, False)
        self.conf = load_json(CONF, {"name": "Player", "version": DEFAULT_VERSION,
                                     "tier": "auto", "extra": [], "tuned": "", "sig": "",
                                     "source": "auto"})
        MIRROR["allow"] = self.conf.get("source", "auto") != "official"
        self.mirror_names = set()
        NOTE["cb"] = self.on_mirror_note
        self.hw = detect()
        self._max = 1
        self.proc = None

        self.bg = ctk.CTkImage(make_background(self.W, self.H), size=(self.W, self.H))
        ctk.CTkLabel(self, text="", image=self.bg).place(x=0, y=0, relwidth=1, relheight=1)
        self.build_left()
        self.build_right()

    def card(self, x, y, w, h):
        c = ctk.CTkFrame(self, width=w, height=h, corner_radius=0, fg_color=self.CARD,
                         border_width=1, border_color=self.LINE)
        c.place(x=x, y=y)
        c.pack_propagate(False)
        return c

    def field(self, parent, **kw):
        return ctk.CTkEntry(parent, width=326, height=38, corner_radius=10, fg_color=self.FIELD,
                            border_color="#1f3a2e", text_color="#e6f4ec", **kw)

    def menu(self, parent, values, width=159):
        return ctk.CTkOptionMenu(parent, width=width, height=36, corner_radius=10, values=values,
                                 fg_color=self.FIELD, button_color="#1b3a2d", button_hover_color="#245a43",
                                 dropdown_fg_color=self.FIELD, dropdown_hover_color="#1b3a2d",
                                 text_color="#e6f4ec")

    def caption(self, parent, text):
        ctk.CTkLabel(parent, text=text, font=("Segoe UI", 11), text_color=self.MUTED).pack(anchor="w", padx=22)

    def build_left(self):
        card = self.card(28, 24, 370, 512)
        ctk.CTkLabel(card, text="FASTLAUNCHER", font=("Segoe UI", 26, "bold"),
                     text_color=self.ACCENT).pack(pady=(20, 0))
        ctk.CTkLabel(card, text="авто-настройка под твой ПК", font=("Segoe UI", 12),
                     text_color=self.MUTED).pack(pady=(0, 10))

        hw = self.hw
        gpu = (hw["gpu"].replace("NVIDIA GeForce ", "").replace("NVIDIA ", "").replace("AMD ", "")
               .replace("(R)", "").replace("(TM)", ""))[:36]
        for text in (f"  GPU   {gpu}",
                     f"  RAM   {hw['ram']:.0f} ГБ   ·   {hw['cores']} {cores_word(hw['cores'])}",
                     f"  Vulkan   {'да' if hw['vulkan'] else 'нет'}   ·   профиль {hw['tier'].upper()}"):
            ctk.CTkLabel(card, text=text, width=326, height=26, anchor="w", corner_radius=8,
                         fg_color="#102019", text_color="#bfe8d2", font=("Segoe UI", 12)).pack(pady=2)

        self.caption(card, "Ник")
        self.name = self.field(card)
        self.name.insert(0, self.conf["name"])
        self.name.pack(pady=(0, 8))
        self.caption(card, "Версия Minecraft")
        self.version = self.field(card)
        self.version.insert(0, self.conf["version"])
        self.version.pack(pady=(0, 8))

        row = ctk.CTkFrame(card, fg_color="transparent")
        row.pack()
        self.tier = self.menu(row, ["auto", "low", "mid", "high"])
        self.tier.set(self.conf["tier"])
        self.tier.pack(side="left", padx=(0, 8))
        self.source = self.menu(row, list(SOURCES))
        back = {v: k for k, v in SOURCES.items()}
        self.source.set(back.get(self.conf.get("source", "auto"), "источник: авто"))
        self.source.pack(side="left")

        self.play = ctk.CTkButton(card, text="ИГРАТЬ", width=326, height=50, corner_radius=14,
                                  font=("Segoe UI", 18, "bold"), fg_color=self.ACCENT, hover_color="#a6ffc9",
                                  text_color="#04110a", command=self.on_play)
        self.play.pack(pady=(16, 0))
        self.bar = ctk.CTkProgressBar(card, width=326, height=8, progress_color=self.ACCENT, fg_color="#16211c")
        self.bar.set(0)
        self.bar.pack(pady=(14, 6))
        self.status = ctk.CTkLabel(card, text="Готов к запуску", text_color="#9fb3a9", font=("Segoe UI", 12),
                                   wraplength=326)
        self.status.pack()
        self.credit = ctk.CTkLabel(card, text="", text_color="#566860", font=("Segoe UI", 10), wraplength=326)
        self.credit.pack(pady=(4, 0))

    def build_right(self):
        card = self.card(430, 346, 420, 190)
        ctk.CTkLabel(card, text="Моды", font=("Segoe UI", 18, "bold"), text_color=self.ACCENT).pack(
            anchor="w", padx=22, pady=(16, 0))
        self.mods_label = ctk.CTkLabel(card, text="", font=("Segoe UI", 12), text_color="#bfe8d2",
                                       justify="left", anchor="w", wraplength=376)
        self.mods_label.pack(anchor="w", padx=22, pady=(4, 8))
        row = ctk.CTkFrame(card, fg_color="transparent")
        row.pack(padx=22, fill="x")
        self.mod = ctk.CTkEntry(row, width=250, height=36, corner_radius=10, fg_color=self.FIELD,
                                border_color="#1f3a2e", placeholder_text="slug мода, например: modmenu")
        self.mod.pack(side="left")
        ctk.CTkButton(row, text="Установить", width=110, height=36, corner_radius=10, fg_color="#1b3a2d",
                      hover_color="#245a43", text_color="#e6f4ec", command=self.on_add_mod).pack(side="left", padx=(8, 0))
        ctk.CTkLabel(card, text="Можно ставить и во время игры, мод заработает после её перезапуска.",
                     font=("Segoe UI", 11), text_color=self.MUTED, wraplength=376, justify="left").pack(
            anchor="w", padx=22, pady=(10, 0))
        self.refresh_mods()

    def refresh_mods(self):
        names = sorted(load_json(MODS_JSON, {}))
        self.mods_label.configure(text="  ·  ".join(names) if names else
                                  "Пока пусто: нужные моды появятся после первого запуска.")

    # --- помощники ---
    def ui(self, fn):
        self.after(0, fn)

    def say(self, text):
        self.ui(lambda: self.status.configure(text=text))

    def on_mirror_note(self, name):
        self.mirror_names.add(name)
        text = "Файлы скачаны через зеркала: " + ", ".join(sorted(self.mirror_names))
        self.ui(lambda: self.credit.configure(text=text))

    def callbacks(self):
        def set_max(m):
            self._max = max(m, 1)

        def set_progress(p):
            self.ui(lambda: self.bar.set(min(1, p / self._max)))

        return {"setStatus": self.say, "setMax": set_max, "setProgress": set_progress}

    # --- запуск ---
    def on_play(self):
        self.conf.update(name=self.name.get().strip() or "Player",
                         version=self.version.get().strip() or DEFAULT_VERSION, tier=self.tier.get(),
                         source=SOURCES[self.source.get()])
        MIRROR["allow"] = self.conf["source"] != "official"
        save_json(CONF, self.conf)
        self.play.configure(state="disabled")
        threading.Thread(target=self.work, daemon=True).start()

    def work(self):
        try:
            mc, name = self.conf["version"], self.conf["name"]
            tier = self.hw["tier"] if self.conf["tier"] == "auto" else self.conf["tier"]
            t = TIERS[tier]

            src = self.conf.get("source", "auto")
            MIRROR["game"] = False
            self.say("Проверяю доступ к серверам...")
            if src == "mirror" or (src == "auto" and not reachable("https://piston-meta.mojang.com/")):
                enable_game_mirror()

            if not mll.fabric.is_minecraft_version_supported(mc):
                raise RuntimeError(f"Fabric не поддерживает версию {mc}")
            vid = fabric_id(mc)
            if not vid:
                self.say("Устанавливаю Minecraft + Fabric (один раз)...")
                self.install_game(mc, src)
                vid = fabric_id(mc)

            # моды ставим только если что-то изменилось - так запуск быстрее
            skipped = []
            primary, fallback = plan_mods(tier, self.hw["vulkan"])
            extra = self.conf.get("extra", [])
            sig = f"{mc}|{','.join(sorted(primary + extra))}"
            if sig != self.conf.get("sig"):
                self.say("Подбираю моды под твой ПК...")
                missing = sync_mods(primary + extra, mc, self.say)
                if primary != fallback and "vulkanmod" in missing:
                    self.say("VulkanMod для этой версии недоступен, ставлю Sodium...")
                    missing = sync_mods(fallback + extra, mc, self.say)
                if not missing:
                    self.conf["sig"] = sig  # иначе в следующий раз попробуем докачать
                skipped = missing

            if self.conf.get("tuned") != tier:
                apply_options(tier)
                self.conf["tuned"] = tier
            save_json(CONF, self.conf)

            xmx = min(t["xmx"], int(self.hw["ram"] * 1024 * 0.5))
            opts = {"username": name,
                    "uuid": str(uuid.uuid3(uuid.NAMESPACE_OID, "OfflinePlayer:" + name)),
                    "token": "",
                    "jvmArguments": [f"-Xmx{xmx}M", f"-Xms{min(xmx, 1024)}M"] + JVM_FLAGS}
            cmd = mll.command.get_minecraft_command(vid, GAME, opts)
            self.say("Запускаю игру...")
            self.ui(self.refresh_mods)
            self.ui(lambda: self.bar.set(1))
            self.proc = subprocess.Popen(cmd, cwd=GAME, creationflags=0x08000000 if os.name == "nt" else 0)
            self.say("Игра запущена" + (f" (без модов: {', '.join(skipped)} - не скачались)" if skipped else ""))
            self.proc.wait()
            self.say("Игра закрыта")
        except Exception as e:
            log_file(f"Ошибка: {e!r}")
            self.say(f"Ошибка: {e}"[:230])
        finally:
            self.ui(lambda: self.play.configure(state="normal"))

    def install_game(self, mc, src):
        run = lambda: mll.fabric.install_fabric(mc, GAME, callback=self.callbacks())
        try:
            retry(run, self.say, tries=6 if (MIRROR["game"] or src == "official") else 3)
        except Exception:
            if MIRROR["game"] or src == "official":
                raise
            self.say("Официальные серверы не отвечают, перехожу на зеркало...")
            enable_game_mirror()
            retry(run, self.say, tries=6)

    # --- установка мода ---
    def on_add_mod(self):
        slug = self.mod.get().strip().lower()
        if not slug:
            return

        def job():
            try:
                mods_dir = GAME / "mods"
                mods_dir.mkdir(parents=True, exist_ok=True)
                manifest = load_json(MODS_JSON, {})
                if install_mod(slug, self.conf["version"], manifest, mods_dir, self.say):
                    save_json(MODS_JSON, manifest)
                    if slug not in self.conf["extra"]:
                        self.conf["extra"].append(slug)
                        save_json(CONF, self.conf)
                    self.say(f"Мод {slug} установлен. Заработает после перезапуска игры")
                    self.ui(self.refresh_mods)
                else:
                    self.say(f"Мод {slug} не найден для {self.conf['version']}")
            except Exception as e:
                self.say(f"Ошибка мода: {e}")

        threading.Thread(target=job, daemon=True).start()


if __name__ == "__main__":
    App().mainloop()
