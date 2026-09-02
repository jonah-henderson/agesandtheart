#!/usr/bin/env bash
#
# The word forge — a guided way to write a word of the Art.
#
#   scripts/author-word.sh                 begin a new word
#   scripts/author-word.sh <name>          open an authored one
#   scripts/author-word.sh --audit         every authored word, worst first
#   scripts/author-word.sh --refresh       ask a server what only it knows, and remember it
#   scripts/author-word.sh --help          the tool's own usage
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
ensure_java() {
    if command -v java >/dev/null 2>&1; then return; fi
    local home="$HOME/.sdkman/candidates/java/current"
    if [[ -x "$home/bin/java" ]]; then
        export JAVA_HOME="$home"
        export PATH="$JAVA_HOME/bin:$PATH"
        return
    fi
    echo "author-word: no java on PATH and none at $home" >&2
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
    # The client launch is written down here too, so the age workshop can start a game without a Gradle
    # build of its own. It is exported rather than depended on: a stale spec is what "open in minecraft"
    # would fail on, and it costs nothing to keep current beside the one we already write.
    ./gradlew --console=plain -q :common:exportAuthoringLaunch :fabric:exportClientLaunch >&2 < /dev/null

    local -a jvm_arguments=()
    local main_class="" working_directory=""
    while IFS=$'\t' read -r key value; do
        case "$key" in
            workingDir) working_directory="$value" ;;
            mainClass) main_class="$value" ;;
            jvmArg) jvm_arguments+=("$value") ;;
        esac
    done < "$LAUNCH"

    if [[ -z "$main_class" ]]; then
        echo "author-word: $LAUNCH names no main class" >&2
        exit 1
    fi

    cd "$working_directory"
    exec java "${jvm_arguments[@]}" "$main_class" "$@"
}

# Sourcing gets you the functions and nothing else, which is how the parsing above is tested.
if [[ ${BASH_SOURCE[0]} == "$0" ]]; then
    main "$@"
fi
