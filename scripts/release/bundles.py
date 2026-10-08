"""
What a release hands out: the Prism instance, the same instance inside a portable Prism for Windows, and the
server's folder. None of them carries a mod. Each carries the packwiz bootstrap and the pack's address, and
fetches the mods named by the pack's index when it starts, which is also how it updates.
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


BOOTSTRAP_URL = "https://github.com/packwiz/packwiz-installer-bootstrap/releases/download/{version}/packwiz-installer-bootstrap.jar"
BOOTSTRAP_NAME = "packwiz-installer-bootstrap.jar"


@dataclass(frozen=True)
class Mod:
    """One jar the pack names: what it is called once installed, where a copy of it is, and which side wants it."""
    filename: str
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
    pack_url: str


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


def fetched(url: str, cache: Path, name: str | None = None, hash_format: str | None = None, expected: str | None = None) -> Path:
    """The file at [url], kept in [cache] as [name], from the cache when it is there and still matches."""
    target = cache / (name or url.rsplit("/", 1)[-1])
    is_cached_and_whole = target.exists() and (expected is None or _hash_matches(target, hash_format, expected))
    if not is_cached_and_whole:
        _download(url, target)
    if expected is not None and not _hash_matches(target, hash_format, expected):
        raise SystemExit(f"release: {url} does not match the pack's {hash_format}")
    return target


def mods_of(pack: Path, cache: Path, own_jars: Path | None = None) -> list[Mod]:
    """
    Every mod the pack's index names, each downloaded and checked against the hash the pack states. A jar
    that [own_jars] holds is taken from there instead, checked the same way, for a release whose jars are
    not published yet.
    """
    index = tomllib.loads((pack / "index.toml").read_text())
    mods = []
    for entry in index.get("files", []):
        relative = Path(entry["file"])
        if relative.parts[0] != "mods":
            continue
        if not entry.get("metafile"):
            raise SystemExit(f"release: {relative} is a file in the pack; mods are named by metafiles")
        meta = tomllib.loads((pack / relative).read_text())
        download = meta["download"]
        hash_format, expected = download["hash-format"], download["hash"]
        own = own_jars / meta["filename"] if own_jars else None
        if own and own.exists():
            if not _hash_matches(own, hash_format, expected):
                raise SystemExit(f"release: {own.name} is not the file the pack's {relative.name} describes")
            path = own
        else:
            path = fetched(download["url"], cache, meta["filename"], hash_format, expected)
        mods.append(Mod(meta["filename"], path, meta.get("side", "both")))
    return mods


def bootstrap_jar(settings: dict[str, str], cache: Path) -> Path:
    version = settings["packwiz.bootstrap"]
    return fetched(BOOTSTRAP_URL.format(version=version), cache, f"packwiz-installer-bootstrap-{version}.jar")


def _write_tree(archive: zipfile.ZipFile, prefix: str, files: dict[str, bytes | Path]) -> None:
    for name, content in files.items():
        if isinstance(content, Path):
            archive.write(content, prefix + name)
        else:
            archive.writestr(prefix + name, content)


def _instance_files(release: Release, settings: dict[str, str], bootstrap: Path) -> dict[str, bytes | Path]:
    """A Prism instance that joins the server on launch, and installs and updates its mods from the pack before it starts."""
    pre_launch = f'"$INST_JAVA" -jar {BOOTSTRAP_NAME} {release.pack_url}'
    quoted_pre_launch = '"' + pre_launch.replace('"', '\\"') + '"'
    instance_cfg = "\n".join([
        "[General]",
        "InstanceType=OneSix",
        "name=Ages and the Art",
        "OverrideCommands=true",
        f"PreLaunchCommand={quoted_pre_launch}",
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
        f".minecraft/{BOOTSTRAP_NAME}": bootstrap,
    }
    return files


def prism_instance(out: Path, release: Release, settings: dict[str, str], bootstrap: Path) -> Path:
    """The zip Prism imports: Add Instance, Import, this file."""
    target = out / f"AgesAndTheArt-{release.version}.zip"
    with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as archive:
        _write_tree(archive, "", _instance_files(release, settings, bootstrap))
    return target


def windows_portable(out: Path, release: Release, settings: dict[str, str], bootstrap: Path, cache: Path) -> Path:
    """Portable Prism for Windows with the instance already in it: unzip anywhere, run prismlauncher.exe."""
    prism_version = settings["prism.version"]
    prism_zip = fetched(
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
        _write_tree(archive, f"{folder}instances/AgesAndTheArt/", _instance_files(release, settings, bootstrap))
    return target


def server(out: Path, release: Release, settings: dict[str, str], bootstrap: Path, cache: Path) -> Path:
    """
    The server's folder. It carries no mods: start.bat runs the bootstrap, which installs and updates them
    from the pack. server.properties is not in it, so unzipping a new one never resets the server's settings.
    """
    launcher = fetched(
        f"https://meta.fabricmc.net/v2/versions/loader/{release.minecraft}/{release.fabric_loader}/"
        f"{settings['fabric.installer']}/server/jar",
        cache,
        f"fabric-server-launch-{release.minecraft}-{release.fabric_loader}-{settings['fabric.installer']}.jar",
    )
    files: dict[str, bytes | Path] = {"fabric-server-launch.jar": launcher, BOOTSTRAP_NAME: bootstrap}
    for template in sorted(SERVER_FILES.iterdir()):
        text = template.read_text()
        text = (text.replace("@SERVER_MEMORY@", settings["server.memory"])
                .replace("@VERSION@", release.version).replace("@PACK_URL@", release.pack_url))
        newline = "\r\n" if template.suffix in (".bat", ".ps1", ".txt", ".properties") else "\n"
        files[template.name] = text.replace("\r\n", "\n").replace("\n", newline).encode()
    target = out / f"AgesAndTheArt-{release.version}-server.zip"
    with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as archive:
        _write_tree(archive, "", files)
    return target
