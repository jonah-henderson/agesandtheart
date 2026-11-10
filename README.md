# The Ages and the Art pack

**An orphan branch: no code, only packwiz's files**, and never merged into `main`. It is what the beta's
players are on. `notes/release-process.md` on `main` carries the design.

- **Ages and the Art and Ephemeris are entries**, not files: each names its GitHub release asset and its
  sha512, and `scripts/release.sh` rewrites both and commits the pack's new version as the last step of a
  release, after the asset is up and checked. No jar is ever committed here.
- **Every other mod is moved by hand** with packwiz (`packwiz mr update <name>`, or `packwiz mr add <version
  url>`): the libraries (Fabric API, Fabric Language Kotlin, Forge Config API Port) never older than `main`'s
  `libs.versions.toml`, which the release checks, and the conveniences the beta ships beside them (JEI, the
  inventory mods, Mouse Tweaks, and what they need).
- **This README is ignored by packwiz** (`.packwizignore`), so editing it moves nothing for players.
