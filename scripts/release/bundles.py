"""
What a release hands out, built from the pack it assembled: the Prism instance, the same instance inside a
portable Prism for Windows, and the server's folder. Every mod comes from the pack's index, so the three
cannot disagree about what is in it.
"""

import hashlib
import json
import shutil
import tomllib
import urllib.request
import zipfile
from dataclasses import dataclass
from pathlib import Path

import nbt

HERE = Path(__file__).resolve().parent
SERVER_FILES = HERE / "server"

# Minecraft 26.3's data version, which a starter options.txt names so the game does not treat it as old.
OPTIONS_DATA_VERSION = 5023

# What a first launch would otherwise stop to ask; the rest the game fills in itself.
STARTER_OPTIONS = {
    "onboardAccessibility": "false",
    "skipMultiplayerWarning": "true",
    "joinedFirstServer": "true",
    "tutorialStep": "none",
}


@dataclass(frozen=True)
class Mod:
    """One jar the pack carries: what it is called there, a name that stays put across releases, and which side wants it."""
    filename: str
    stable_name: str
    path: Path
    side: str

    @property
    def is_for_clients(self) -> bool:
        return self.side in ("both", "client")

    @property
    def is_for_servers(self) -> bool:
        return self.side in ("both", "server")


@dataclass(frozen=True)
class Release:
    version: str
    minecraft: str
    fabric_loader: str
    lwjgl: str


def _hash_matches(path: Path, hash_format: str, expected: str) -> bool:
    digest = hashlib.new(hash_format)
    digest.update(path.read_bytes())
    return digest.hexdigest() == expected


def _download(url: str, destination: Path) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    partial = destination.with_suffix(destination.suffix + ".part")
    request = urllib.request.Request(url, headers={"User-Agent": "agesandtheart-release"})
    with urllib.request.urlopen(request) as response, partial.open("wb") as out:
        shutil.copyfileobj(response, out)
    partial.rename(destination)


def _fetched(url: str, cache: Path, name: str | None = None, hash_format: str | None = None, expected: str | None = None) -> Path:
    """The file at [url], kept in [cache] as [name], from the cache when it is there and still matches."""
    target = cache / (name or url.rsplit("/", 1)[-1])
    is_cached_and_whole = target.exists() and (expected is None or _hash_matches(target, hash_format, expected))
    if not is_cached_and_whole:
        _download(url, target)
    if expected is not None and not _hash_matches(target, hash_format, expected):
        raise SystemExit(f"release: {url} does not match the pack's {hash_format}")
    return target


def mods_of(pack: Path, cache: Path) -> list[Mod]:
    """Every mod in the pack's index: metafiles downloaded and checked, files carried in the pack taken as they are."""
    index = tomllib.loads((pack / "index.toml").read_text())
    mods = []
    for entry in index.get("files", []):
        relative = Path(entry["file"])
        if relative.parts[0] != "mods":
            continue
        if entry.get("metafile"):
            meta = tomllib.loads((pack / relative).read_text())
            download = meta["download"]
            path = _fetched(download["url"], cache, meta["filename"], download["hash-format"], download["hash"])
            stable_name = relative.name.removesuffix(".pw.toml") + ".jar"
            mods.append(Mod(meta["filename"], stable_name, path, meta.get("side", "both")))
        else:
            mods.append(Mod(relative.name, _stable_name_of_our_jar(relative.name), pack / relative, "both"))
    return mods


def _stable_name_of_our_jar(filename: str) -> str:
    """`agesandtheart-fabric-26.3-0.1.0+26.3.jar` is `agesandtheart.jar`, `ephemeris-fabric-…` `ephemeris.jar`."""
    return filename.split("-", 1)[0] + ".jar"


def _write_tree(archive: zipfile.ZipFile, prefix: str, files: dict[str, bytes | Path]) -> None:
    for name, content in files.items():
        if isinstance(content, Path):
            archive.write(content, prefix + name)
        else:
            archive.writestr(prefix + name, content)


def _instance_files(release: Release, settings: dict[str, str], mods: list[Mod]) -> dict[str, bytes | Path]:
    """A Prism instance that joins the server on launch, with every client mod in it."""
    instance_cfg = "\n".join([
        "[General]",
        "InstanceType=OneSix",
        f"name=Ages and the Art {release.version}",
        "OverrideMemory=true",
        f"MinMemAlloc={settings['client.memory.min']}",
        f"MaxMemAlloc={settings['client.memory.max']}",
        "JoinServerOnLaunch=true",
        f"JoinServerOnLaunchAddress={settings['server.address']}",
        "",
    ])
    components = {
        "formatVersion": 1,
        "components": [
            {"uid": "org.lwjgl3", "version": release.lwjgl, "dependencyOnly": True},
            {"uid": "net.minecraft", "version": release.minecraft, "important": True},
            {"uid": "net.fabricmc.intermediary", "version": release.minecraft, "dependencyOnly": True},
            {"uid": "net.fabricmc.fabric-loader", "version": release.fabric_loader},
        ],
    }
    options = "\n".join([f"version:{OPTIONS_DATA_VERSION}"] + [f"{key}:{value}" for key, value in STARTER_OPTIONS.items()]) + "\n"
    files: dict[str, bytes | Path] = {
        "instance.cfg": instance_cfg.encode(),
        "mmc-pack.json": (json.dumps(components, indent=2) + "\n").encode(),
        ".minecraft/servers.dat": nbt.servers_dat([(settings["server.name"], settings["server.address"])]),
        ".minecraft/options.txt": options.encode(),
    }
    for mod in mods:
        if mod.is_for_clients:
            files[f".minecraft/mods/{mod.filename}"] = mod.path
    return files


def prism_instance(out: Path, release: Release, settings: dict[str, str], mods: list[Mod]) -> Path:
    """The zip Prism imports: Add Instance, Import, this file."""
    target = out / f"AgesAndTheArt-{release.version}.zip"
    with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as archive:
        _write_tree(archive, "", _instance_files(release, settings, mods))
    return target


def windows_portable(out: Path, release: Release, settings: dict[str, str], mods: list[Mod], cache: Path) -> Path:
    """Portable Prism for Windows with the instance already in it: unzip anywhere, run prismlauncher.exe."""
    prism_version = settings["prism.version"]
    prism_zip = _fetched(
        f"https://github.com/PrismLauncher/PrismLauncher/releases/download/{prism_version}/"
        f"PrismLauncher-Windows-MSVC-Portable-{prism_version}.zip",
        cache,
    )
    folder = "AgesAndTheArt/"
    target = out / f"AgesAndTheArt-{release.version}-windows.zip"
    with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as archive, zipfile.ZipFile(prism_zip) as prism:
        for item in prism.infolist():
            if not item.is_dir():
                archive.writestr(folder + item.filename, prism.read(item))
        _write_tree(archive, f"{folder}instances/AgesAndTheArt/", _instance_files(release, settings, mods))
    return target


def server(out: Path, release: Release, settings: dict[str, str], mods: list[Mod], cache: Path) -> Path:
    """
    The server's folder, to unzip over the last one once its mods folder is deleted. Jars carry names that
    stay put across releases, so an update replaces rather than adds; server.properties is not in it, so
    unzipping never resets the server's settings.
    """
    launcher = _fetched(
        f"https://meta.fabricmc.net/v2/versions/loader/{release.minecraft}/{release.fabric_loader}/"
        f"{settings['fabric.installer']}/server/jar",
        cache,
        f"fabric-server-launch-{release.minecraft}-{release.fabric_loader}-{settings['fabric.installer']}.jar",
    )
    files: dict[str, bytes | Path] = {"fabric-server-launch.jar": launcher}
    for template in sorted(SERVER_FILES.iterdir()):
        text = template.read_text()
        text = text.replace("@SERVER_MEMORY@", settings["server.memory"]).replace("@VERSION@", release.version)
        newline = "\r\n" if template.suffix in (".bat", ".ps1", ".txt", ".properties") else "\n"
        files[template.name] = text.replace("\r\n", "\n").replace("\n", newline).encode()
    for mod in mods:
        if mod.is_for_servers:
            files[f"mods/{mod.stable_name}"] = mod.path
    target = out / f"AgesAndTheArt-{release.version}-server.zip"
    with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as archive:
        _write_tree(archive, "", files)
    return target
