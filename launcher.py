"""Enchanted - быстрый лаунчер Minecraft (Fabric)."""
import hashlib
import json
import lzma
import os
import re
import shutil
import subprocess
import sys
import threading
from concurrent.futures import ThreadPoolExecutor
import tkinter as tk
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
from PIL import ImageTk

from logo import draw_icon, draw_logo, make_ico

APP_NAME = "Enchanted"
_BASE = Path(os.environ["APPDATA"]) if os.name == "nt" else Path.home()
ROOT = _BASE / ".enchanted"
_OLD = _BASE / ".fastlauncher"
if _OLD.exists() and not ROOT.exists():
    try:
        _OLD.rename(ROOT)  # переносим уже скачанную игру в папку нового лаунчера
    except OSError:
        ROOT = _OLD
GAME = ROOT / "game"
CONF = ROOT / "config.json"
MODS_JSON = ROOT / "mods.json"
UA = {"User-Agent": "Enchanted/1.0 (personal project)"}
DEFAULT_VERSION = "1.21.11"
VERSIONS = ["1.21.11", "1.21.4"]


def instance_dir(mc):
    """Папка игрока для версии (сохранения, моды, ресурспаки). Библиотеки и ассеты общие, в GAME."""
    return GAME if mc == DEFAULT_VERSION else ROOT / "instances" / mc


def manifest_path(mc):
    return MODS_JSON if mc == DEFAULT_VERSION else ROOT / f"mods-{mc}.json"

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
DEAD = {"net": False}  # Modrinth и зеркало недоступны - остальные моды с них не пробуем
FAIL = {}  # slug -> причина, почему мод не поставился
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


LOG4J_FALLBACK = """<?xml version="1.0" encoding="UTF-8"?>
<Configuration status="WARN">
    <Appenders>
        <Console name="SysOut" target="SYSTEM_OUT">
            <PatternLayout pattern="[%d{HH:mm:ss}] [%t/%level]: %msg{nolookups}%n" />
        </Console>
        <RollingRandomAccessFile name="File" fileName="logs/latest.log" filePattern="logs/%d{yyyy-MM-dd}-%i.log.gz">
            <PatternLayout pattern="[%d{HH:mm:ss}] [%t/%level]: %msg{nolookups}%n" />
            <Policies>
                <TimeBasedTriggeringPolicy />
                <OnStartupTriggeringPolicy />
            </Policies>
        </RollingRandomAccessFile>
    </Appenders>
    <Loggers>
        <Root level="info">
            <AppenderRef ref="SysOut"/>
            <AppenderRef ref="File"/>
        </Root>
    </Loggers>
</Configuration>
"""


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
    errs = {}
    for attempt in range(5):
        for u in _candidates(url):
            try:
                r = _orig_request(sess, "GET", u, headers=UA, timeout=(10, 60))
                if r.status_code != 200:
                    last = errs[u.split('/')[2]] = f"HTTP {r.status_code}"
                    if r.status_code == 429:
                        time.sleep(2 + attempt * 2)
                    continue
                data = r.content
                if lzma_compressed:
                    data = lzma.decompress(data)
                if sha1 and hashlib.sha1(data).hexdigest() != sha1:
                    last = errs[u.split('/')[2]] = "не совпала контрольная сумма"
                    continue
                tmp = path + ".part"
                with open(tmp, "wb") as f:
                    f.write(data)
                os.replace(tmp, path)
                return True
            except Exception as e:
                last = errs[u.split('/')[2]] = type(e).__name__
        time.sleep(1.5 * (attempt + 1))
    name = os.path.basename(path)
    if re.fullmatch(r"client-[\d.]+\.xml", name):
        # настройки логов log4j: игре не обязательны, пишем стандартный конфиг сами
        with open(path, "w", encoding="utf-8") as f:
            f.write(LOG4J_FALLBACK)
        log_file(f"{name}: не скачался ({errs}), создан стандартный конфиг")
        return True
    msg = f"не скачался файл {name}: " + "; ".join(f"{h}: {e}" for h, e in errs.items())
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
    """Открывает адрес Modrinth через зеркало MCIM и напрямую.
    Если игру пришлось качать с зеркала (значит, зарубежные сайты режутся) или зеркало уже
    выручало раньше - зеркало идёт первым. Каждый адрес пробуем 2 раза, ошибки копим,
    чтобы в логе было видно, что именно не работает."""
    if DEAD["net"]:
        raise RuntimeError("Modrinth недоступен")
    mirror = next((dst + url[len(src):] for src, dst in MOD_RULES if url.startswith(src)), None)
    if not MIRROR["allow"]:
        mirror = None
    mirror_first = mirror and (MIRROR["mods_first"] or MIRROR["game"])
    order = [mirror, url] if mirror_first else [url, mirror]
    errs = {}
    for attempt in range(2):
        for u in order:
            if not u:
                continue
            host = u.split("/")[2]
            try:
                r = urllib.request.urlopen(urllib.request.Request(u, headers=UA),
                                           timeout=timeout if attempt else min(timeout, 15))
                if u != url:
                    MIRROR["mods_first"] = True
                    note_mirror("MCIM (mcimirror.top)")
                return r
            except urllib.error.HTTPError as e:
                errs[host] = f"HTTP {e.code}"
            except Exception as e:
                errs[host] = type(e).__name__
        time.sleep(1.5)
    msg = "; ".join(f"{h}: {e}" for h, e in errs.items())
    log_file(f"{url} -> {msg}")
    if errs and not any(e.startswith("HTTP") for e in errs.values()):
        DEAD["net"] = True
    raise RuntimeError(msg)


def get_json(url):
    def go():
        with modrinth_open(url, 10) as r:
            return json.load(r)
    return retry(go, tries=2)


def ram_gb():
    return psutil.virtual_memory().total / 2**30


# ---------- моды (Modrinth) ----------
# ---------- моды без Modrinth: локальная папка и GitHub Release ----------
GH_REPO = "__GH_REPO__"  # при сборке на GitHub подставляется имя этого репозитория (владелец/репо)
GH_CACHE = {}
PINS = {}  # версия игры -> {slug: версия}, например {"1.21.11": {"sodium": "0.8.12"}}


def pin_ok(slug, mc, *texts):
    pin = PINS.get(mc, {}).get(slug)
    return not pin or any(re.search(rf"(?<!\d){re.escape(pin)}(?!\d)", t or "") for t in texts)


def _norm(s):
    return re.sub(r"[^a-z0-9]", "", s.lower())


def _match(slug, filename):
    return _norm(filename).startswith(_norm(slug))


def bundle_dirs():
    base = Path(sys.executable).parent if getattr(sys, "frozen", False) else Path(__file__).resolve().parent
    return [base / "mods_bundle", ROOT / "mods_bundle"]


def mod_from_bundle(slug, mc, mods_dir):
    """Берёт мод из папки mods_bundle рядом с лаунчером (или в .enchanted)."""
    for d in bundle_dirs():
        if not d.is_dir():
            continue
        jars = sorted((j for j in d.glob("*.jar") if _match(slug, j.name) and pin_ok(slug, mc, j.name)),
                      key=lambda j: j.stat().st_mtime, reverse=True)
        if jars:
            dest = mods_dir / jars[0].name
            if not dest.exists():
                shutil.copyfile(jars[0], dest)
            return jars[0].name
    return None


def _gh_open(url, timeout):
    req = urllib.request.Request(url, headers={"User-Agent": "Enchanted", "Accept": "application/vnd.github+json"})
    return urllib.request.urlopen(req, timeout=timeout)


def gh_release(mc):
    """Список файлов релиза mods-<версия> и его index.json (slug -> имя файла, зависимости)."""
    if "/" not in GH_REPO or "__" in GH_REPO:
        return [], {}
    if mc not in GH_CACHE:
        assets, index = [], {}
        try:
            with _gh_open(f"https://api.github.com/repos/{GH_REPO}/releases/tags/mods-{mc}", 15) as r:
                assets = json.load(r).get("assets", [])
            idx = next((a for a in assets if a["name"] == "index.json"), None)
            if idx:
                with _gh_open(idx["browser_download_url"], 15) as r:
                    index = json.load(r)
        except Exception as e:
            log_file(f"GitHub release mods-{mc}: {e!r}")
        GH_CACHE[mc] = (assets, index)
    return GH_CACHE[mc]


def gh_deps(mc):
    """Зависимости модов (например, Cloth Config), которые workflow положил в релиз."""
    return list(gh_release(mc)[1].get("deps", {}))


def mod_from_github(slug, mc, mods_dir, log):
    """Берёт мод из GitHub Release с тегом mods-<версия> в этом же репозитории."""
    assets, index = gh_release(mc)
    if not assets:
        return None
    wanted = {**index.get("deps", {}), **index.get("mods", {})}.get(slug)
    if wanted:
        asset = next((a for a in assets if a["name"] == wanted), None)
    else:
        asset = next((a for a in assets if a["name"].endswith(".jar") and _match(slug, a["name"])
                      and pin_ok(slug, mc, a["name"])), None)
    if not asset:
        return None
    dest = mods_dir / asset["name"]
    if not dest.exists():
        log(f"Скачиваю {slug} с GitHub...")
        def download():
            tmp = dest.parent / (dest.name + ".part")
            h = hashlib.sha256()
            with _gh_open(asset["browser_download_url"], 60) as r, open(tmp, "wb") as out:
                while chunk := r.read(1 << 16):
                    out.write(chunk)
                    h.update(chunk)
            want = (asset.get("digest") or "").removeprefix("sha256:")
            if want and h.hexdigest() != want:
                tmp.unlink(missing_ok=True)
                raise RuntimeError("хеш файла не совпал")
            os.replace(tmp, dest)
        retry(download, log, tries=2)
    return asset["name"]


def install_mod(slug, mc, manifest, mods_dir, log):
    # 1) локальная папка, 2) GitHub, 3) Modrinth и его зеркала
    for source in (lambda: mod_from_bundle(slug, mc, mods_dir),
                   lambda: mod_from_github(slug, mc, mods_dir, log)):
        try:
            name = source()
        except Exception as e:
            log_file(f"{slug}: {e!r}")
            name = None
        if name:
            old = manifest.get(slug)
            if old and old != name:
                (mods_dir / old).unlink(missing_ok=True)
            manifest[slug] = name
            return True
    return install_mod_modrinth(slug, mc, manifest, mods_dir, log)


NETCHK = {}
CLIENT_MOD_VERSIONS = ("1.21.11",)  # для каких версий игры есть встроенный клиентский мод (GUI на левый Shift)


def modrinth_alive():
    """Один раз за запуск проверяет Modrinth и зеркало (до 5 с). Нет связи - дальше идём с теми модами, что уже есть."""
    if DEAD["net"]:
        return False
    if "alive" not in NETCHK:
        base = "https://api.modrinth.com/v2/"
        urls = [base] + [dst + base[len(src):] for src, dst in MOD_RULES if base.startswith(src)]
        with ThreadPoolExecutor(len(urls)) as ex:
            NETCHK["alive"] = any(ex.map(lambda u: reachable(u, 5), urls))
        if not NETCHK["alive"]:
            DEAD["net"] = True
    return NETCHK["alive"]


def client_mod_src():
    base = Path(getattr(sys, "_MEIPASS", Path(__file__).resolve().parent))
    return base / "client_mod" / "enchanted-client.jar"


def install_client_mod(mc, mods_dir, log):
    """Встроенный клиентский мод Enchanted (меню на левый Shift): копируется из самого лаунчера."""
    dest = mods_dir / "enchanted-client.jar"
    src = client_mod_src()
    if mc not in CLIENT_MOD_VERSIONS:
        dest.unlink(missing_ok=True)
        return
    if not src.is_file():
        log_file("встроенный клиентский мод не найден в лаунчере")
        return
    if not dest.exists() or dest.stat().st_size != src.stat().st_size or dest.read_bytes() != src.read_bytes():
        shutil.copyfile(src, dest)


FABRIC_API_ID = "P7dR8mSH"


def install_mod_modrinth(slug, mc, manifest, mods_dir, log, key=None, depth=0):
    key = key or slug
    if not modrinth_alive():
        FAIL[slug] = "нет связи с Modrinth"
        log(f"{slug}: нет связи с Modrinth, пропускаю")
        return False
    q = urllib.parse.urlencode({"loaders": json.dumps(["fabric"]), "game_versions": json.dumps([mc])})
    versions = get_json(f"https://api.modrinth.com/v2/project/{slug}/version?{q}")
    if not versions:
        # зеркало может игнорировать фильтры или не знать такого запроса - берём все версии и фильтруем сами
        allv = get_json(f"https://api.modrinth.com/v2/project/{slug}/version")
        versions = [v for v in allv if "fabric" in v.get("loaders", []) and mc in v.get("game_versions", [])]
        versions.sort(key=lambda v: (v.get("version_type") == "release", v.get("date_published", "")), reverse=True)
    else:
        versions = [v for v in versions if "fabric" in v.get("loaders", []) and mc in v.get("game_versions", [])]
    versions = [v for v in versions if pin_ok(slug, mc, v.get("version_number"), *(f["filename"] for f in v["files"]))]
    if not versions:
        FAIL[slug] = f"нет версии под {mc}" + (f" (закреплена {PINS[mc][slug]})" if slug in PINS.get(mc, {}) else "")
        log(f"{slug}: нет версии под {mc}")
        return False
    files = versions[0]["files"]
    f = next((x for x in files if x.get("primary")), files[0])
    dest = mods_dir / f["filename"]
    old = manifest.get(key)
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
    manifest[key] = f["filename"]
    # обязательные зависимости (Cloth Config, Architectury и т.п.) - они хранятся в манифесте как "dep:<slug>"
    if depth < 2:
        for d in versions[0].get("dependencies", []):
            pid = d.get("project_id")
            if d.get("dependency_type") != "required" or not pid or pid == FABRIC_API_ID:
                continue
            try:
                dslug = get_json(f"https://api.modrinth.com/v2/project/{pid}")["slug"]
                if dslug in manifest or "dep:" + dslug in manifest:
                    continue
                install_mod_modrinth(dslug, mc, manifest, mods_dir, log, key="dep:" + dslug, depth=depth + 1)
            except Exception as e:
                log_file(f"зависимость {pid} для {slug}: {e!r}")
    return True


def sync_mods(slugs, mc, log):
    """Приводит папку mods к нужному набору. Возвращает список модов, которые не удалось поставить."""
    mods_dir = instance_dir(mc) / "mods"
    mods_dir.mkdir(parents=True, exist_ok=True)
    manifest = load_json(manifest_path(mc), {})
    slugs = list(slugs) + [d for d in gh_deps(mc) if d not in slugs]
    for slug in list(manifest):
        if slug not in slugs and not slug.startswith("dep:"):
            (mods_dir / manifest.pop(slug)).unlink(missing_ok=True)
    missing = []
    for slug in slugs:
        try:
            if not install_mod(slug, mc, manifest, mods_dir, log):
                missing.append(slug)
        except Exception as e:
            FAIL[slug] = str(e)[:120]
            log(f"{slug}: {e}")
            log_file(f"мод {slug}: {e!r}")
            missing.append(slug)
    save_json(manifest_path(mc), manifest)
    try:
        install_client_mod(mc, mods_dir, log)
    except Exception as e:
        log_file(f"клиентский мод: {e!r}")
    return missing


# Набор модов: ставится всегда
OPT_MODS = ["fabric-api", "sodium", "lithium", "ferrite-core", "modernfix",
            "immediatelyfast", "memoryleakfix", "modmenu",
            "ebe", "cull-leaves", "moreculling", "entityculling",
            "immersive-optimization", "too-many-entities", "entityopt",
            "c2me-fabric", "async", "iris", "ggtu"]


def fabric_id(mc):
    ids = [v["id"] for v in mll.utils.get_installed_versions(GAME)
           if v["id"].startswith("fabric-loader-") and v["id"].endswith("-" + mc)]
    return ids[-1] if ids else None


# ---------- интерфейс ----------
def open_folder(path):
    """Открывает папку в проводнике (создаёт, если её ещё нет)."""
    path.mkdir(parents=True, exist_ok=True)
    try:
        if os.name == "nt":
            os.startfile(str(path))
        elif sys.platform == "darwin":
            subprocess.Popen(["open", str(path)])
        else:
            subprocess.Popen(["xdg-open", str(path)])
    except Exception as e:
        log_file(f"не открылась папка {path}: {e!r}")


_NEBULA = {}


def nebula_base():
    """Туманность без звёзд: рисуется один раз и потом растягивается под любой размер окна."""
    if "img" not in _NEBULA:
        import random
        from PIL import Image, ImageChops, ImageDraw, ImageFilter
        w, h = 960, 540
        rnd = random.Random(7)
        img = Image.new("RGB", (w, h), (4, 8, 7))
        for color, n, rmin, rmax, blur in (((18, 84, 58), 9, 120, 280, 55), ((26, 150, 104), 6, 60, 160, 36),
                                           ((110, 255, 175), 4, 22, 70, 18)):
            layer = Image.new("RGB", (w, h), (0, 0, 0))
            d = ImageDraw.Draw(layer)
            for _ in range(n):
                x, y, r = rnd.randint(w // 3, w), rnd.randint(0, h), rnd.randint(rmin, rmax)
                d.ellipse((x - r, y - r // 2, x + r, y + r // 2), fill=color)
            img = ImageChops.screen(img, layer.filter(ImageFilter.GaussianBlur(blur)).point(lambda v: int(v * 0.72)))
        _NEBULA["img"] = img
    return _NEBULA["img"]


def _font(size, bold=False):
    from PIL import ImageFont
    for name in (("segoeuib.ttf" if bold else "segoeui.ttf"), "arialbd.ttf", "DejaVuSans-Bold.ttf"):
        try:
            return ImageFont.truetype(name, size)
        except OSError:
            pass
    try:
        return ImageFont.load_default(size)
    except TypeError:
        return ImageFont.load_default()


def draw_round(img, x, y, w, h, r, fill, outline=None, bw=1):
    """Скруглённый прямоугольник со сглаженными краями (рисуется в 4 раза крупнее и уменьшается)."""
    from PIL import Image, ImageDraw
    k = 4

    def mask(inset):
        m = Image.new("L", (w * k, h * k), 0)
        ImageDraw.Draw(m).rounded_rectangle((inset * k, inset * k, w * k - 1 - inset * k, h * k - 1 - inset * k),
                                            radius=max(1, r - inset) * k, fill=255)
        return m.resize((w, h), Image.LANCZOS)

    if outline:
        img.paste(outline, (x, y), mask(0))
        img.paste(fill, (x, y), mask(bw))
    else:
        img.paste(fill, (x, y), mask(0))


def make_background(w, h, shapes=(), left_edge=0, radius=22, bw=1, fill=(11, 16, 14), line=(28, 58, 44)):
    """Фон окна под текущий размер: туманность, звёзды, скруглённые панели, логотип и название справа.
    Панели рисуются прямо в картинке - так у них нет острых углов поверх туманности."""
    import random
    from PIL import Image, ImageDraw
    img = nebula_base().resize((w, h), Image.BILINEAR)
    d = ImageDraw.Draw(img)
    rnd = random.Random(7)
    for _ in range(max(90, w * h // 9000)):
        x, y, v = rnd.randint(0, w - 1), rnd.randint(0, h - 1), rnd.randint(90, 255)
        d.point((x, y), fill=(v, v, v))
    for x, y, sw, sh in shapes:
        draw_round(img, x, y, sw, sh, radius, fill, line, bw)

    cx, cy = int((max(left_edge, 0) + w) / 2), int(h * 0.44)
    logo_size = max(150, min(300, int(h * 0.30)))
    logo = draw_logo(logo_size)
    img.paste(logo, (cx - logo_size // 2, cy - logo_size // 2 - int(logo_size * 0.28)), logo)

    title_font = _font(max(34, int(min(h * 0.085, w * 0.05))), True)
    d = ImageDraw.Draw(img)
    y = cy + int(logo_size * 0.30)
    letters = list(APP_NAME.upper())
    gap = int(title_font.size * 0.22)
    widths = [d.textlength(c, font=title_font) for c in letters]
    x = cx - (sum(widths) + gap * (len(letters) - 1)) / 2
    for c, cw in zip(letters, widths):
        d.text((x, y), c, font=title_font, fill=(236, 255, 246))
        x += cw + gap
    sub_font = _font(max(15, int(h * 0.026)))
    sub = "Fabric  ·  быстрый запуск"
    d.text((cx - d.textlength(sub, font=sub_font) / 2, y + int(title_font.size * 1.35)), sub,
           font=sub_font, fill=(124, 255, 178))
    return img


class App(ctk.CTk):
    ACCENT = "#7CFFB2"
    CARD = "#0b100e"
    FIELD = "#121a17"
    LINE = "#1c3a2c"
    MUTED = "#6b7f76"
    BTN = "#10201a"
    BTN_HOVER = "#1b3a2d"
    CW = 360      # ширина полей
    PANEL_W = 400  # ширина содержимого левой панели
    IN = 14       # отступ панели от края скруглённой плашки
    RAD = 22      # радиус скругления плашек
    MARGIN = 56

    def __init__(self):
        super().__init__()
        ctk.set_appearance_mode("dark")
        self.title(APP_NAME)
        self.configure(fg_color="#040807")
        self.set_icon()
        try:
            self.scale = float(self._get_window_scaling())
        except Exception:
            self.scale = 1.0
        sw, sh = self.winfo_screenwidth() / self.scale, self.winfo_screenheight() / self.scale
        w, h = int(min(1280, sw - 80)), int(min(820, sh - 110))
        self.geometry(f"{w}x{h}+{int((sw - w) / 2 * self.scale)}+{int(max(0, (sh - h) / 3) * self.scale)}")
        self.resizable(False, False)  # размер фиксирован: без растягивания мышкой и кнопки "развернуть"
        self.fullscreen = False
        self.bind("<F11>", lambda e: self.toggle_fullscreen())
        self.bind("<Escape>", lambda e: self.toggle_fullscreen(False))

        self.conf = load_json(CONF, {"name": "Player", "version": DEFAULT_VERSION, "extra": [], "source": "auto"})
        if self.conf.get("version") not in VERSIONS:
            self.conf["version"] = DEFAULT_VERSION
        MIRROR["allow"] = self.conf.get("source", "auto") != "official"
        self.mirror_names = set()
        NOTE["cb"] = self.on_mirror_note
        self._max = 1
        self.proc = None

        self.bg_label = tk.Label(self, bd=0, highlightthickness=0, bg="#040807")
        self.bg_label.place(x=0, y=0, relwidth=1, relheight=1)
        self.panel = ctk.CTkFrame(self, width=self.PANEL_W, height=10, fg_color=self.CARD, corner_radius=0)
        self.build_panel(self.panel)
        self.dock = ctk.CTkFrame(self, fg_color=self.CARD, corner_radius=0)
        self.build_dock(self.dock)
        # размеры по содержимому, дальше они фиксированы
        self.update_idletasks()
        self.ph = self.panel.winfo_reqheight() / self.scale
        self.dw = self.dock.winfo_reqwidth() / self.scale
        self.dh = self.dock.winfo_reqheight() / self.scale
        self.panel.configure(height=self.ph)
        self.panel.pack_propagate(False)

        self._key = None
        self._job = None
        self.bind("<Configure>", self.on_resize)
        self.after(20, self.relayout)

    # --- окно ---
    def set_icon(self):
        try:
            ROOT.mkdir(parents=True, exist_ok=True)
            ico = ROOT / "enchanted2.ico"
            if not ico.exists():
                make_ico(str(ico))
            if os.name == "nt":
                self.iconbitmap(str(ico))  # до этого вызова customtkinter ставит свой значок
            else:
                self._icon = ImageTk.PhotoImage(draw_icon(64))
                self.iconphoto(True, self._icon)
        except Exception as e:
            log_file(f"иконка окна: {e!r}")

    def toggle_fullscreen(self, state=None):
        self.fullscreen = (not self.fullscreen) if state is None else state
        self.attributes("-fullscreen", self.fullscreen)

    def on_resize(self, event):
        if event.widget is not self:
            return
        if self._job:
            self.after_cancel(self._job)
        self._job = self.after(60, self.relayout)

    def relayout(self):
        """Расставляет панели и перерисовывает фон под них (картинка и виджеты двигаются вместе)."""
        self._job = None
        W, H, s, IN = self.winfo_width(), self.winfo_height(), self.scale, self.IN
        if W < 300 or H < 300:
            return
        lh = H / s
        pw, ph = self.PANEL_W + 2 * IN, self.ph + 2 * IN
        dw, dh = self.dw + 2 * IN, self.dh + 2 * IN
        px = self.MARGIN
        dy = int(lh - 28 - dh)
        top, bottom = 28, dy - 18
        py = int(max(top, top + (bottom - top - ph) / 2))
        key = (W, H, py)
        if key == self._key:
            return
        self._key = key
        shapes = [tuple(int(round(v * s)) for v in box) for box in
                  ((px, py, pw, ph), (px, dy, dw, dh))]
        self._bg_photo = ImageTk.PhotoImage(make_background(
            W, H, shapes, left_edge=int((px + pw) * s), radius=int(self.RAD * s), bw=max(1, round(s))))
        self.bg_label.configure(image=self._bg_photo)
        self.panel.place(x=px + IN, y=py + IN)
        self.dock.place(x=px + IN, y=dy + IN)

    # --- виджеты ---
    def field(self, parent, **kw):
        return ctk.CTkEntry(parent, width=self.CW, height=40, corner_radius=10, fg_color=self.FIELD,
                            border_color="#1f3a2e", text_color="#e6f4ec", **kw)

    def menu(self, parent, values):
        return ctk.CTkOptionMenu(parent, width=self.CW, height=40, corner_radius=10, values=values,
                                 fg_color=self.FIELD, button_color="#1b3a2d", button_hover_color="#245a43",
                                 dropdown_fg_color=self.FIELD, dropdown_hover_color="#1b3a2d",
                                 text_color="#e6f4ec")

    def caption(self, parent, text):
        ctk.CTkLabel(parent, text=text, font=("Segoe UI", 11), text_color=self.MUTED).pack(anchor="w", padx=20)

    def build_panel(self, p):
        head = ctk.CTkFrame(p, fg_color="transparent")
        head.pack(pady=(18, 14))
        self.logo_img = ctk.CTkImage(draw_logo(128), size=(46, 46))
        ctk.CTkLabel(head, text="", image=self.logo_img).pack(side="left", padx=(0, 12))
        ctk.CTkLabel(head, text=APP_NAME.upper(), font=("Segoe UI", 26, "bold"),
                     text_color=self.ACCENT).pack(side="left")

        self.caption(p, "Ник")
        self.name = self.field(p)
        self.name.insert(0, self.conf["name"])
        self.name.pack(pady=(0, 10))

        self.caption(p, "Версия Minecraft")
        self.version = self.menu(p, VERSIONS)
        self.version.set(self.conf["version"])
        self.version.pack(pady=(0, 10))

        self.source = self.menu(p, list(SOURCES))
        back = {v: k for k, v in SOURCES.items()}
        self.source.set(back.get(self.conf.get("source", "auto"), "источник: авто"))
        self.source.pack()

        self.play = ctk.CTkButton(p, text="ИГРАТЬ", width=self.CW, height=54, corner_radius=14,
                                  font=("Segoe UI", 19, "bold"), fg_color=self.ACCENT, hover_color="#a6ffc9",
                                  text_color="#04110a", command=self.on_play)
        self.play.pack(pady=(16, 0))
        self.bar = ctk.CTkProgressBar(p, width=self.CW, height=8, corner_radius=4, progress_color=self.ACCENT,
                                      fg_color="#16211c")
        self.bar.set(0)
        self.bar.pack(pady=(16, 6))
        self.status = ctk.CTkLabel(p, text="Готов к запуску", text_color="#9fb3a9", font=("Segoe UI", 12),
                                   wraplength=self.CW, height=52, justify="center")
        self.status.pack()
        self.credit = ctk.CTkLabel(p, text="", text_color="#566860", font=("Segoe UI", 10),
                                   wraplength=self.CW, height=26)
        self.credit.pack(pady=(0, 14))

    def build_dock(self, d):
        """Три кнопки папок: открывают папки выбранной версии игры."""
        row = ctk.CTkFrame(d, fg_color="transparent")
        row.pack()
        for text, width, sub in (("Minecraft", 122, ""), ("Моды", 92, "mods"), ("Ресурспаки", 132, "resourcepacks")):
            ctk.CTkButton(row, text=text, width=width, height=38, corner_radius=12, font=("Segoe UI", 13),
                          fg_color=self.BTN, hover_color=self.BTN_HOVER, text_color="#bfe8d2",
                          border_width=1, border_color=self.LINE,
                          command=lambda sub=sub: self.open_instance(sub)).pack(side="left", padx=(0, 8))

    def open_instance(self, sub):
        base = instance_dir(self.version.get())
        open_folder(base / sub if sub else base)

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
        self.conf.update(name=self.name.get().strip() or "Player", version=self.version.get(),
                         source=SOURCES[self.source.get()])
        MIRROR["allow"] = self.conf["source"] != "official"
        save_json(CONF, self.conf)
        self.play.configure(state="disabled")
        self.bar.set(0)
        threading.Thread(target=self.work, daemon=True).start()

    def work(self):
        try:
            mc, name = self.conf["version"], self.conf["name"]
            inst = instance_dir(mc)
            inst.mkdir(parents=True, exist_ok=True)

            src = self.conf.get("source", "auto")
            MIRROR["game"] = False
            DEAD["net"] = False
            NETCHK.clear()
            self.say("Проверяю доступ к серверам...")
            if src == "mirror" or (src == "auto" and not reachable("https://piston-meta.mojang.com/")):
                enable_game_mirror()

            if not mll.fabric.is_minecraft_version_supported(mc):
                raise RuntimeError(f"Fabric не поддерживает версию {mc}")
            vid = fabric_id(mc)
            if not vid:
                self.say(f"Устанавливаю Minecraft {mc} + Fabric (один раз)...")
                self.install_game(mc, src)
                vid = fabric_id(mc)

            # моды ставим только если что-то изменилось - так запуск быстрее
            skipped = []
            extra = self.conf.get("extra", [])
            mods = OPT_MODS + [m for m in extra if m not in OPT_MODS]
            sig = f"{mc}|{','.join(sorted(mods))}"
            sigs = self.conf.setdefault("sigs", {})
            done = sigs.get(mc) or (self.conf.get("sig") if mc == DEFAULT_VERSION else None)
            if sig != done:
                self.say("Проверяю моды...")
                missing = sync_mods(mods, mc, self.say)
                # сохраняем, если не хватает только тех модов, которых под эту версию точно нет
                if all(FAIL.get(m, "").startswith("нет версии") for m in missing):
                    sigs[mc] = sig
                skipped = missing
            save_json(CONF, self.conf)

            xmx = max(1024, min(4096, int(ram_gb() * 1024 * 0.5)))
            opts = {"username": name,
                    "uuid": str(uuid.uuid3(uuid.NAMESPACE_OID, "OfflinePlayer:" + name)),
                    "token": "",
                    "gameDirectory": str(inst),
                    "jvmArguments": [f"-Xmx{xmx}M", f"-Xms{min(xmx, 1024)}M"] + JVM_FLAGS}
            cmd = mll.command.get_minecraft_command(vid, GAME, opts)
            self.say("Запускаю игру...")
            self.ui(lambda: self.bar.set(1))
            self.proc = subprocess.Popen(cmd, cwd=inst, creationflags=0x08000000 if os.name == "nt" else 0)
            self.say("Игра запущена" + (f" (без модов: {', '.join(skipped)}; причина: {FAIL.get(skipped[0], '?')})" if skipped else ""))
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


def set_app_id():
    """Чтобы значок и название в панели задач были свои, а не от Python."""
    if os.name == "nt":
        try:
            import ctypes
            ctypes.windll.shell32.SetCurrentProcessExplicitAppUserModelID("Enchanted.Launcher")
        except Exception:
            pass


if __name__ == "__main__":
    set_app_id()
    App().mainloop()
