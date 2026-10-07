"""
Releases Ages and the Art for the beta: tests and builds a commit in throwaway worktrees, tags it, and puts
the jars, the pack and the three bundles under dist/<version>/. notes/release-process.md is the design.

    scripts/release.sh <version> [<commit>]   release; the commit defaults to HEAD
    scripts/release.sh --resume <version>     carry on from the step that failed
    scripts/release.sh --rehearse <version>   everything but the tag and the pack commit, into dist/<version>-rehearsal/
    scripts/release.sh --offline ...          never asks origin anything, and pushes nothing

Nothing leaves the machine before it asks. A failure before then removes the tag it made and leaves the
worktrees under build/release/<version>/ for a look.
"""

import argparse
import io
import json
import os
import re
import shutil
import subprocess
import sys
import socket
import tarfile
import threading
import tomllib
import urllib.request
import zipfile
from pathlib import Path

import bundles

ROOT = Path(__file__).resolve().parents[2]
EPHEMERIS = ROOT.parent / "ephemeris"
SETTINGS_FILE = ROOT / ".release" / "beta.properties"
SETTINGS_EXAMPLE = Path(__file__).resolve().parent / "beta.example.properties"
TESTER_GUIDE = Path(__file__).resolve().parent / "TESTERS.md"
CACHE = ROOT / "build" / "release-cache"
SERVER_RUN_FILES = ["eula.txt", "server.properties"]
SMOKE_SERVER = CACHE / "smoke-server"
SMOKE_SECONDS = 300
PACK_BRANCH = "pack"

STEPS = ["tag", "worktrees", "checks", "build", "pack", "bundles", "smoke", "publish"]

# Which pack entry carries each library the catalog pins, and how to read its version off the jar's name.
LIBRARY_PINS = [
    ("fabricApi", "fabric-api", r"fabric-api-(.+)\.jar"),
    ("flk", "fabric-language-kotlin", r"fabric-language-kotlin-(.+)\.jar"),
    ("forgeConfigApiPort", "forge-config-api-port", r"ForgeConfigAPIPort-v([0-9.]+)-"),
]


def fail(message: str) -> None:
    raise SystemExit(f"release: {message}")


def say(message: str) -> None:
    print(f"release: {message}", flush=True)


def git(*arguments: str, cwd: Path = ROOT, check: bool = True) -> str:
    result = subprocess.run(["git", *arguments], cwd=cwd, capture_output=True, text=True)
    if check and result.returncode != 0:
        fail(f"git {' '.join(arguments)}: {result.stderr.strip()}")
    return result.stdout.strip()


def number_parts(version: str) -> tuple[int, ...]:
    """`0.161.0+26.3` reads (0, 161, 0): the dotted numbers before anything else."""
    match = re.match(r"\d+(\.\d+)*", version)
    if not match:
        fail(f"'{version}' has no version number in it")
    return tuple(int(part) for part in match.group(0).split("."))


def compatibility_line(version: str) -> tuple[int, ...]:
    """Below 1.0 the minor version is the breaking one, as `BuildMatch` reads it; from 1.0, the major."""
    major, minor, _ = number_parts(version)
    return (0, minor) if major == 0 else (major,)


def read_properties(path: Path) -> dict[str, str]:
    properties = {}
    for line in path.read_text().splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            key, value = line.split("=", 1)
            properties[key.strip()] = value.strip()
    return properties


class Release:
    def __init__(self, version: str, rehearsal: bool, offline: bool):
        self.version = version
        self.rehearsal = rehearsal
        self.offline = offline or rehearsal
        catalog = tomllib.loads((ROOT / "libs.versions.toml").read_text())["versions"]
        self.catalog = catalog
        self.minecraft = catalog["minecraft"]
        self.tag = f"v{version}-mc{self.minecraft}"
        self.build = f"{version}+{self.minecraft}"
        self.ephemeris_version = catalog["ephemeris"]
        self.ephemeris_tag = f"v{self.ephemeris_version}-mc{self.minecraft}"
        name = f"{version}-rehearsal" if rehearsal else version
        self.work = ROOT / "build" / "release" / name
        self.dist = ROOT / "dist" / name
        self.state_file = self.work / "state.json"
        self.state: dict = {"done": []}

    # --- state, so a failed release can be resumed rather than tagged again

    def load_state(self) -> None:
        if not self.state_file.exists():
            fail(f"nothing to resume: no {self.state_file}")
        self.state = json.loads(self.state_file.read_text())

    def save_state(self) -> None:
        self.work.mkdir(parents=True, exist_ok=True)
        self.state_file.write_text(json.dumps(self.state, indent=2) + "\n")

    def is_done(self, step: str) -> bool:
        return step in self.state["done"]

    def mark_done(self, step: str) -> None:
        self.state["done"].append(step)
        self.save_state()

    @property
    def codebase(self) -> Path:
        return self.work / "codebase"

    @property
    def ephemeris_worktree(self) -> Path:
        return self.work / "ephemeris"

    # --- step 0: refuse what cannot be released, before anything is made

    def preflight(self, commit_name: str) -> None:
        if not re.fullmatch(r"\d+\.\d+\.\d+", self.version):
            fail(f"'{self.version}' is not a version like 0.1.0")
        if self.rehearsal:
            self.remove_worktrees()
            shutil.rmtree(self.work, ignore_errors=True)
            shutil.rmtree(self.dist, ignore_errors=True)
        if self.work.exists():
            fail(f"{self.work} exists from an earlier attempt: --resume it, or remove it and run 'git worktree prune'")
        if self.dist.exists():
            fail(f"{self.dist} already holds a release")
        commit = git("rev-parse", "--verify", f"{commit_name}^{{commit}}")
        self.state["commit"] = commit

        released = [tag for tag in git("tag", "--list", f"v*-mc{self.minecraft}").splitlines() if tag]
        versions = sorted((number_parts(tag[1:]), tag) for tag in released)
        if versions and versions[-1][0] >= number_parts(self.version):
            fail(f"{self.version} is not after the last release, {versions[-1][1]}")
        if git("rev-parse", "-q", "--verify", f"refs/tags/{self.tag}", check=False):
            fail(f"{self.tag} already exists here")
        if not self.offline:
            result = subprocess.run(["git", "ls-remote", "--tags", "origin", f"refs/tags/{self.tag}"],
                                    cwd=ROOT, capture_output=True, text=True, timeout=30)
            if result.returncode != 0:
                fail("origin cannot be reached; fix the remote or pass --offline")
            if result.stdout.strip():
                fail(f"{self.tag} already exists on origin")

        record = git("show", f"{commit}:common/compatibility.record")
        recorded_line = next(line.split("=", 1)[1] for line in record.splitlines() if line.startswith("line="))
        if compatibility_line(self.version) < number_parts(recorded_line):
            fail(f"the content at {commit[:8]} needs a release on line {recorded_line} or later, and {self.version} is not")

        self.check_ephemeris()
        self.check_pack()
        self.check_settings()
        missing_run_files = [name for name in SERVER_RUN_FILES if not (ROOT / "fabric/runs/server" / name).exists()]
        if missing_run_files:
            fail("the server checks need fabric/runs/server set up: run ./gradlew :fabric:runServer once")
        self.state["last_tag"] = versions[-1][1] if versions else None

    def check_ephemeris(self) -> None:
        if not (EPHEMERIS / ".git").exists():
            fail(f"no Ephemeris repository at {EPHEMERIS}")
        tagged = git("rev-parse", "-q", "--verify", f"refs/tags/{self.ephemeris_tag}", cwd=EPHEMERIS, check=False)
        if tagged:
            self.state["ephemeris_ref"] = self.ephemeris_tag
        elif self.rehearsal:
            self.state["ephemeris_ref"] = git("rev-parse", "HEAD", cwd=EPHEMERIS)
            say(f"Ephemeris has no {self.ephemeris_tag}; rehearsing against its HEAD")
        else:
            fail(f"Ephemeris {self.ephemeris_version} is not released: run scripts/release.sh "
                 f"{self.ephemeris_version} in {EPHEMERIS}, or move the catalog's 'ephemeris'")

    def check_pack(self) -> None:
        if not git("rev-parse", "-q", "--verify", f"refs/heads/{PACK_BRANCH}", check=False):
            fail(f"no '{PACK_BRANCH}' branch (notes/release-process.md §4)")
        pack = tomllib.loads(git("show", f"{PACK_BRANCH}:pack.toml"))
        if pack["versions"]["minecraft"] != self.minecraft:
            fail(f"the pack is for Minecraft {pack['versions']['minecraft']}, not {self.minecraft}")
        if number_parts(pack["versions"]["fabric"]) < number_parts(self.catalog["fabricLoader"]):
            fail(f"the pack's Fabric loader {pack['versions']['fabric']} is older than the catalog's {self.catalog['fabricLoader']}")
        for catalog_key, entry, pattern in LIBRARY_PINS:
            meta = tomllib.loads(git("show", f"{PACK_BRANCH}:mods/{entry}.pw.toml"))
            match = re.search(pattern, meta["filename"])
            if not match:
                fail(f"cannot read a version off the pack's {meta['filename']}")
            if number_parts(match.group(1)) < number_parts(self.catalog[catalog_key]):
                fail(f"the pack's {entry} {match.group(1)} is older than the catalog's {self.catalog[catalog_key]}")

    def check_settings(self) -> None:
        if not SETTINGS_FILE.exists():
            fail(f"no {SETTINGS_FILE.relative_to(ROOT)}: copy {SETTINGS_EXAMPLE.relative_to(ROOT)} there and fill it in")
        settings = read_properties(SETTINGS_FILE)
        missing = [key for key in read_properties(SETTINGS_EXAMPLE) if key not in settings]
        if missing:
            fail(f"{SETTINGS_FILE.relative_to(ROOT)} is missing {', '.join(missing)}")
        if settings["server.address"].startswith("example.invalid") and not self.rehearsal:
            fail(f"{SETTINGS_FILE.relative_to(ROOT)} still names example.invalid as the server")

    # --- the steps

    def tag_commit(self) -> None:
        git("tag", "-a", self.tag, self.state["commit"], "-m", f"Ages and the Art {self.version} for Minecraft {self.minecraft}")

    def make_worktrees(self) -> None:
        git("worktree", "add", "--detach", str(self.codebase), self.tag)
        git("worktree", "add", "--detach", str(self.ephemeris_worktree), self.state["ephemeris_ref"], cwd=EPHEMERIS)
        server_run = self.codebase / "fabric/runs/server"
        server_run.mkdir(parents=True, exist_ok=True)
        for name in SERVER_RUN_FILES:
            shutil.copy2(ROOT / "fabric/runs/server" / name, server_run / name)

    def run_checks(self) -> None:
        self.gradle(self.codebase, "checks-compile", ":fabric:compileKotlin")
        self.gradle(self.codebase, "checks", ":common:serverTest", "-Pfast")

    def build_jars(self) -> None:
        self.gradle(self.codebase, "build", ":fabric:build")
        self.gradle(self.ephemeris_worktree, "build-ephemeris", ":fabric:assemble")
        ours = self.codebase / f"fabric/build/libs/agesandtheart-fabric-{self.minecraft}-{self.build}.jar"
        ephemeris_build = f"{self.ephemeris_version}+{self.minecraft}"
        ephemeris = self.ephemeris_worktree / f"fabric/build/libs/ephemeris-fabric-{self.minecraft}-{ephemeris_build}.jar"
        self.expect_jar(ours, self.build)
        if not self.rehearsal:
            self.expect_jar(ephemeris, ephemeris_build)
        elif not ephemeris.exists():
            built = (self.ephemeris_worktree / "fabric/build/libs").glob(f"ephemeris-fabric-{self.minecraft}-*.jar")
            ephemeris = next(jar for jar in built if not jar.stem.endswith(("-sources", "-javadoc")))
        with zipfile.ZipFile(ours) as jar:
            build_properties = jar.read("agesandtheart-build.properties").decode()
        if f"build={self.build}" not in build_properties.splitlines():
            fail(f"the jar's build properties do not say build={self.build}")
        jars = self.dist / "jars"
        jars.mkdir(parents=True, exist_ok=True)
        for jar in (ours, ephemeris):
            shutil.copy2(jar, jars / jar.name)

    def expect_jar(self, jar: Path, version: str) -> None:
        if not jar.exists():
            fail(f"no {jar.relative_to(self.work)}: the build did not read the tag")
        with zipfile.ZipFile(jar) as archive:
            declared = json.loads(archive.read("fabric.mod.json"))["version"]
        if declared != version:
            fail(f"{jar.name} says {declared}, not {version}")

    def assemble_pack(self) -> None:
        """The pack branch as it stands, with both jars in it as files and this release's version."""
        pack = self.dist / "pack"
        if pack.exists():
            shutil.rmtree(pack)
        pack.mkdir(parents=True)
        archive = subprocess.run(["git", "archive", PACK_BRANCH], cwd=ROOT, capture_output=True, check=True).stdout
        with tarfile.open(fileobj=io.BytesIO(archive)) as tar:
            tar.extractall(pack, filter="data")
        for jar in (self.dist / "jars").iterdir():
            shutil.copy2(jar, pack / "mods" / jar.name)
        self.set_pack_version(pack)

    def set_pack_version(self, pack: Path) -> None:
        manifest = pack / "pack.toml"
        manifest.write_text(re.sub(r'(?m)^version = ".*"$', f'version = "{self.build}"', manifest.read_text()))
        subprocess.run(["packwiz", "refresh"], cwd=pack, check=True, capture_output=True)

    def make_bundles(self) -> None:
        settings = read_properties(SETTINGS_FILE)
        release = bundles.Release(self.version, self.minecraft, self.catalog["fabricLoader"], self.lwjgl())
        mods = bundles.mods_of(self.dist / "pack", CACHE)
        made = [
            bundles.prism_instance(self.dist, release, settings, mods),
            bundles.windows_portable(self.dist, release, settings, mods, CACHE),
            bundles.server(self.dist, release, settings, mods, CACHE),
        ]
        guide = TESTER_GUIDE.read_text()
        for placeholder, value in (("@VERSION@", self.version), ("@ADDRESS@", settings["server.address"]),
                                   ("@FEEDBACK@", settings["feedback"])):
            guide = guide.replace(placeholder, value)
        (self.dist / "TESTERS.md").write_text(guide)
        (self.dist / "CHANGELOG.md").write_text(self.changelog())
        self.state["bundles"] = [str(path) for path in made]

    def boot_the_server_bundle(self) -> None:
        """
        Starts the server bundle as shipped, outside any dev environment, and writes an Age in it. The dev
        game is widened and transformed by the build, so this is the only step that sees the jars as a
        player's game will. The folder persists so the vanilla server is downloaded once.
        """
        say("booting the server bundle (log: build/release-cache/smoke-server/logs/latest.log)")
        for transient in ("mods", "world", "logs", "crash-reports"):
            shutil.rmtree(SMOKE_SERVER / transient, ignore_errors=True)
        SMOKE_SERVER.mkdir(parents=True, exist_ok=True)
        server_zip = next(Path(path) for path in self.state["bundles"] if path.endswith("-server.zip"))
        with zipfile.ZipFile(server_zip) as archive:
            archive.extractall(SMOKE_SERVER)
        shutil.copy2(ROOT / "fabric/runs/server/eula.txt", SMOKE_SERVER / "eula.txt")
        with socket.socket() as probe:
            probe.bind(("127.0.0.1", 0))
            port = probe.getsockname()[1]
        (SMOKE_SERVER / "server.properties").write_text(
            f"server-port={port}\nonline-mode=false\nenable-rcon=false\npause-when-empty-seconds=0\n"
        )
        java = Path(java_environment().get("JAVA_HOME", "")) / "bin" / "java"
        server = subprocess.Popen(
            [str(java) if java.exists() else "java", "-Xmx3G", "-jar", "fabric-server-launch.jar", "nogui"],
            cwd=SMOKE_SERVER, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
        )
        # Ends the read below by closing the server's output, should it go quiet.
        watchdog = threading.Timer(SMOKE_SECONDS, server.kill)
        watchdog.start()
        try:
            self.await_line(server, "Done (", "the server never finished starting")
            server.stdin.write("age write smoke 1 age gentle landmass\n")
            server.stdin.flush()
            self.await_line(server, "Created Age agesandtheart:smoke", "the server could not write an Age")
            server.stdin.write("stop\n")
            server.stdin.flush()
            server.wait(timeout=60)
        finally:
            watchdog.cancel()
            if server.poll() is None:
                server.kill()

    def await_line(self, server: subprocess.Popen, wanted: str, failure: str) -> None:
        for line in server.stdout:
            if wanted in line:
                return
            if "Exception" in line or "/ERROR]" in line:
                fail(f"{failure}: {line.strip()} (see {SMOKE_SERVER / 'logs/latest.log'})")
        fail(f"{failure} (see {SMOKE_SERVER / 'logs/latest.log'})")

    def lwjgl(self) -> str:
        """The LWJGL Prism pairs with this Minecraft, which an instance names beside it."""
        url = f"https://meta.prismlauncher.org/v1/net.minecraft/{self.minecraft}.json"
        with urllib.request.urlopen(url) as response:
            requires = json.load(response)["requires"]
        requirement = next(entry for entry in requires if entry["uid"] == "org.lwjgl3")
        return requirement.get("suggests") or requirement["equals"]

    def changelog(self) -> str:
        """Every commit's subject since the last release, oldest first; the first release has none to list."""
        heading = f"# Ages and the Art {self.build}\n\n"
        last = self.state.get("last_tag")
        if not last:
            return heading + "The first release of the beta.\n"
        subjects = git("log", "--reverse", "--format=- %s", f"{last}..{self.tag}")
        return heading + f"What changed since {last}:\n\n{subjects}\n"

    def confirm_and_publish(self) -> None:
        commit = git("log", "-1", "--format=%h %s", self.state["commit"])
        print()
        print(f"  Ages and the Art {self.build}")
        print(f"  commit     {commit}")
        print(f"  Ephemeris  {self.state['ephemeris_ref']}")
        print(f"  out        {self.dist}")
        for bundle in self.state["bundles"]:
            print(f"             {Path(bundle).name}")
        print()
        if self.rehearsal:
            return
        if self.offline:
            say(f"tagged {self.tag} locally (offline); push it and '{PACK_BRANCH}' when origin is back")
        else:
            answer = input(f"Push {self.tag} and the pack to origin? [y/N] ")
            if answer.strip().lower() != "y":
                fail("not published; nothing left the machine (the tag is removed)")
        self.commit_pack()
        if not self.offline:
            git("push", "origin", self.tag)
            git("push", "origin", PACK_BRANCH)

    def commit_pack(self) -> None:
        """The pack branch's record of this release: its version, and nothing else while the jars are not entries."""
        worktree = self.work / "pack"
        git("worktree", "add", str(worktree), PACK_BRANCH)
        self.set_pack_version(worktree)
        git("commit", "-q", "-am", f"Ages and the Art {self.build}", cwd=worktree)

    def gradle(self, project: Path, log_name: str, *tasks: str) -> None:
        log = self.work / "logs" / f"{log_name}.log"
        log.parent.mkdir(parents=True, exist_ok=True)
        say(f"{' '.join(tasks)} in {project.name} (log: {log.relative_to(ROOT)})")
        with log.open("w") as out:
            result = subprocess.run(["./gradlew", "--console=plain", *tasks], cwd=project, stdout=out,
                                    stderr=subprocess.STDOUT, env=java_environment())
        if result.returncode != 0:
            print("".join(log.read_text().splitlines(keepends=True)[-40:]), file=sys.stderr)
            fail(f"{' '.join(tasks)} failed; the whole log is {log}")

    def remove_worktrees(self) -> None:
        for worktree, repository in ((self.codebase, ROOT), (self.ephemeris_worktree, EPHEMERIS), (self.work / "pack", ROOT)):
            if worktree.exists():
                git("worktree", "remove", "--force", str(worktree), cwd=repository)

    def remove_tag(self) -> None:
        git("tag", "-d", self.tag, check=False)

    def run(self) -> None:
        actions = {
            "tag": self.tag_commit,
            "worktrees": self.make_worktrees,
            "checks": self.run_checks,
            "build": self.build_jars,
            "pack": self.assemble_pack,
            "bundles": self.make_bundles,
            "smoke": self.boot_the_server_bundle,
            "publish": self.confirm_and_publish,
        }
        try:
            for step in STEPS:
                if self.is_done(step):
                    continue
                actions[step]()
                self.mark_done(step)
        except BaseException:
            if not self.is_done("publish"):
                self.remove_tag()
                self.state["done"] = [step for step in self.state["done"] if step != "tag"]
                self.save_state()
            raise
        if self.rehearsal:
            self.remove_tag()
        self.remove_worktrees()
        say(f"{'rehearsed' if self.rehearsal else 'released'} {self.build}: {self.dist}")


def java_environment() -> dict[str, str]:
    """The JDK `.sdkmanrc` pins, when SDKMAN has it, since a non-login shell may have none at all."""
    environment = dict(os.environ)
    sdkmanrc = ROOT / ".sdkmanrc"
    if sdkmanrc.exists():
        pinned = read_properties(sdkmanrc).get("java")
        home = Path.home() / ".sdkman/candidates/java" / (pinned or "")
        if pinned and home.is_dir():
            environment["JAVA_HOME"] = str(home)
            environment["PATH"] = f"{home / 'bin'}:{environment['PATH']}"
    return environment


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("version")
    parser.add_argument("commit", nargs="?", default="HEAD")
    parser.add_argument("--resume", action="store_true")
    parser.add_argument("--rehearse", action="store_true")
    parser.add_argument("--offline", action="store_true")
    arguments = parser.parse_args()

    release = Release(arguments.version, rehearsal=arguments.rehearse, offline=arguments.offline)
    if arguments.resume:
        release.load_state()
        if not release.is_done("tag"):
            release.check_ephemeris()
    else:
        release.preflight(arguments.commit)
        release.save_state()
    release.run()


if __name__ == "__main__":
    main()
