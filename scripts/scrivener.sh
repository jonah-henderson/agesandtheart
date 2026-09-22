#!/usr/bin/env bash
#
# Scrivener — a vocabulary editor for Ages and the Art.
#
#   scripts/scrivener.sh                   begin a new word
#   scripts/scrivener.sh <name>            open an authored one
#   scripts/scrivener.sh --audit           every authored word, worst first
#   scripts/scrivener.sh --refresh         ask a server what only it knows, and remember it
#   scripts/scrivener.sh --help            the tool's own usage
#
# IT WORKS OFFLINE, AND THAT IS THE POINT. The corpus, every tag table and vanilla's own biomes, features
# and structure sets are read straight off the source tree by `Vocabulary.load` — the same call
# `VocabularyCheck` makes — so what a word reaches, what it costs, what its query keeps and what it
# contradicts are computed from the real code with no server anywhere. The one thing offline cannot know
# is a tag only a bound registry grants (`ore` is the case), and `--refresh` writes that down once for
# every later run to read.
#
# WHY A SCRIPT RATHER THAN A GRADLE TASK. A full-screen editor needs a TTY, and Gradle gives a `JavaExec`
# neither: it owns stdin and strips the control characters a redraw is made of. So the build's only job is
# `:common:exportAuthoringLaunch`, which writes down how to start the JVM, and this starts it. The same
# split, for the same reason, as `:fabric:exportServerLaunch` and the server checks.
#
# The launch is re-exported on every run, so a change to the tool is picked up without anyone remembering
# to rebuild. That costs a Gradle up-to-date check and nothing else.

set -euo pipefail

readonly HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly LAUNCH="$HERE/common/build/authoring-launch.txt"

usage() {
    awk '/^# /{sub(/^# ?/, ""); print; next} /^#$/{print ""; next} NR>1{exit}' "${BASH_SOURCE[0]}"
}

# SDKMAN's init lives in ~/.bashrc and is not sourced by a non-login shell, so `java` can be missing from
# PATH even where the toolchain is installed. Same fallback as scripts/drive-server.sh.
#
# THIS IS ONLY FOR GRADLE. The tool itself is started with the JVM the launch spec names, which is the
# *toolchain's* — a PATH `java` several releases behind it does not merely warn, it refuses: 26.3's
# `--sun-misc-unsafe-memory-access` is unrecognised before 24 and the JVM will not come up at all.
ensure_java() {
    if command -v java >/dev/null 2>&1; then return; fi
    local home="$HOME/.sdkman/candidates/java/current"
    if [[ -x "$home/bin/java" ]]; then
        export JAVA_HOME="$home"
        export PATH="$JAVA_HOME/bin:$PATH"
        return
    fi
    echo "scrivener: no java on PATH and none at $home" >&2
    exit 1
}

main() {
    if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
        usage
        exit 0
    fi
    ensure_java
    cd "$HERE"
    # Quiet, and to stderr, so the tool's own first frame is the first thing on the screen. **Its stdin is
    # closed**, or Gradle drains the terminal's input buffer and the first keystrokes go to the build.
    #
    # The client and server launches are written down here too, so the age workshop can start a game and
    # `--refresh` a server without a Gradle build of its own. They are exported rather than depended on: a
    # stale spec is what "open in minecraft" would fail on, and it costs nothing to keep them current
    # beside the one we already write.
    #
    # **The server one was left out, and a version port is exactly when that bites.** Only the checks
    # depend on `exportServerLaunch`, so the spec on disk was whatever the last `:common:serverTest` wrote
    # — a 26.1 server, still being launched with a 26.3 mod after the port, which Fabric refuses to load.
    # Every screen that reaches a server reads this one file: `--refresh` and the workshop's `^o` both.
    ./gradlew --console=plain -q \
        :common:exportAuthoringLaunch :fabric:exportClientLaunch :fabric:exportServerLaunch \
        >&2 < /dev/null

    local -a jvm_arguments=()
    local main_class="" working_directory="" java_executable=""
    while IFS=$'\t' read -r key value; do
        case "$key" in
            workingDir) working_directory="$value" ;;
            java) java_executable="$value" ;;
            mainClass) main_class="$value" ;;
            jvmArg) jvm_arguments+=("$value") ;;
        esac
    done < "$LAUNCH"

    if [[ -z "$main_class" ]]; then
        echo "scrivener: $LAUNCH names no main class" >&2
        exit 1
    fi
    # An older spec, written before the toolchain's JVM was recorded, names no java; PATH is then the only
    # thing left to try and its failure says so plainly.
    if [[ -z "$java_executable" ]]; then
        java_executable="java"
    elif [[ ! -x "$java_executable" ]]; then
        echo "scrivener: $LAUNCH names $java_executable, which is not executable" >&2
        exit 1
    fi

    cd "$working_directory"
    exec "$java_executable" "${jvm_arguments[@]}" "$main_class" "$@"
}

# Sourcing gets you the functions and nothing else, which is how the parsing above is tested.
if [[ ${BASH_SOURCE[0]} == "$0" ]]; then
    main "$@"
fi
