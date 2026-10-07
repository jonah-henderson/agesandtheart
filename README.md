# The Ages and the Art pack

**An orphan branch: no code, only packwiz's files**, and never merged into `main`. It is what the beta's
players are on. `notes/release-process.md` on `main` carries the design.

- The libraries (Fabric API, Fabric Language Kotlin, Forge Config API Port) are moved by hand with packwiz
  (`packwiz mr update <name>`, or `packwiz mr add <version url>`), never older than `main`'s
  `libs.versions.toml`, which `scripts/release.sh` checks.
- Ages and the Art and Ephemeris are **not** entries here while the pack is not hosted: there is no URL to
  give them. Each release copies this branch to `dist/<version>/pack/`, puts both jars in it as files,
  refreshes the index there, and builds the Prism instance and the server bundle from that. Here it commits
  the pack's version and nothing else.
