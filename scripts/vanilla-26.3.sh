#!/usr/bin/env bash
#
# Launch a vanilla Minecraft 26.3 client with the structure-authoring tools in it.
#
# This is for building the lost library (and anything else that wants authoring) **while the mod is being
# ported**, so it deliberately touches nothing the mod's own build uses: its own Gradle build under
# `tools/vanilla-26.3`, its own Minecraft version, its own run directory. The mod is not loaded.
#
# It needs no Mojang account: Loom's dev client authenticates offline, the same way `:fabric:runClient`
# does.
#
# The tools are downloaded on first run and kept in `tools/vanilla-26.3/run/mods`:
#
#   Axiom                 the in-game editor the structures are built with (free for non-commercial use)
#   worldgen-devtools     `/resetchunks`, `reloadRegistries`, colour-coded jigsaw blocks (MIT)
#
# See `notes/authoring-tools.md` Part I for what each is for and how the pipeline fits together.

set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
project="tools/vanilla-26.3"
mods="$here/$project/run/mods"

# SDKMAN's init lives in ~/.bashrc and may not be sourced in a non-login shell.
export JAVA_HOME="${JAVA_HOME:-$HOME/.sdkman/candidates/java/current}"
export PATH="$JAVA_HOME/bin:$PATH"

AXIOM_URL="https://cdn.modrinth.com/data/N6n5dqoA/versions/EjUybJv3/Axiom-6.1.2-for-MC26.3.jar"
AXIOM_JAR="Axiom-6.1.2-for-MC26.3.jar"

DEVTOOLS_URL="https://github.com/jacobsjo/worldgen-devtools/releases/download/v1.4.1%2B26.3/worldgenDevtools-1.4.1%2B26.3.jar"
DEVTOOLS_JAR="worldgenDevtools-1.4.1+26.3.jar"

fetch() {
    local url="$1" into="$2"
    if [ -f "$mods/$into" ]; then
        return
    fi
    echo "vanilla-26.3: fetching $into"
    curl --fail --location --silent --show-error --output "$mods/$into.part" "$url"
    mv "$mods/$into.part" "$mods/$into"
}

mkdir -p "$mods"

if [ "${1:-}" = "--no-tools" ]; then
    echo "vanilla-26.3: skipping the tool downloads, launching with whatever is in run/mods"
else
    fetch "$AXIOM_URL" "$AXIOM_JAR"
    fetch "$DEVTOOLS_URL" "$DEVTOOLS_JAR"
fi

echo "vanilla-26.3: mods in $mods"
ls -1 "$mods" | sed 's/^/  /'

# `-p` runs the standalone build in tools/, so the mod's own build is never configured and its Minecraft
# version is never resolved. The wrapper is shared; only the project directory differs.
exec "$here/gradlew" -p "$project" runClient
