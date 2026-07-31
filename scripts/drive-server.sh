#!/usr/bin/env bash
#
# Runs a list of server commands against the headless Fabric dev server, then stops it.
#
# The point is to make a headless check cost what the work costs. Commands go in one at a time and
# each is *waited for* rather than slept over, so a check that takes two seconds takes two seconds.
#
# How the waiting works: after each command we send `say <token>` with a token unique to this run.
# Console commands are queued by the reader thread and drained by the server thread in order, so a
# long command simply blocks the queue — which means seeing the token echoed back is proof that the
# command before it has *finished*. No per-command knowledge of what "done" looks like is needed.
#
# It also writes to a throwaway world by default, never the one you have been playing in. See the
# --level option; server.properties is put back on the way out, including on Ctrl-C.
#
# Usage:
#   scripts/drive-server.sh commands.txt
#   scripts/drive-server.sh < commands.txt
#   scripts/drive-server.sh --level world commands.txt      # deliberately use the real save
#
# Command files are one command per line, without a leading slash. Blank lines and #-comments are
# ignored, so a file can explain itself:
#
#   # Does the same sentence give the same world twice?
#   age compose bareA 777 landform=eroded medium=sea dressing=bare_rock
#   age compose bareB 777 landform=eroded medium=sea dressing=bare_rock
#   age compare bareA bareB 2
#
# IT ASSERTS NOTHING, AND THAT IS THE POINT. This drives a server and prints what it says; whether an
# answer is right is `./gradlew :common:serverTest`'s business, where a Kotest failure carries a real
# diagnostic instead of "nothing matched /at-least 10000/".
#
# It used to carry an assertion layer of its own — `#? expect`, `#? at-least` and friends. That layer read
# the *first integer on the matching line*, which on every line a real server writes is the hour off the
# timestamp: `at-least 100` could never pass and `at-most 2000` could never fail. Its own self-check could
# not see it, having written fixture lines with no timestamps. The layer is gone; `#?` lines are skipped so
# an old file still drives.
#
# So what remains here is the exploratory half: files that are meant to be *read* — `aspects.txt`,
# `regions.txt`, `generator-parity.txt`. Anything that should pass or fail belongs in a spec beside
# `common/src/test/kotlin/.../server/`, which drives a server over RCON and gets each command's output back
# on its own rather than sliced out of a shared log.
set -euo pipefail

readonly REPOSITORY=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
readonly RUN_DIRECTORY="$REPOSITORY/fabric/runs/server"
readonly PROPERTIES="$RUN_DIRECTORY/server.properties"
# Overridable so the waiting can be exercised against a fake log without a server behind it.
readonly LOG=${DRIVE_SERVER_LOG:-$RUN_DIRECTORY/logs/latest.log}

# How long any one command may take before we give up on it. Generous, because a /age compare or
# bench over a wide radius genuinely does take minutes on a cold Age.
readonly COMMAND_TIMEOUT_SECONDS=${COMMAND_TIMEOUT_SECONDS:-600}
readonly STARTUP_TIMEOUT_SECONDS=${STARTUP_TIMEOUT_SECONDS:-600}
readonly POLL_SECONDS=1

# A world nobody minds losing. Overridable, but the default is deliberately not `world`: a headless
# check should never be able to disturb a save someone has been building in.
level="smoke-$(date +%Y%m%d-%H%M%S)"

note() { printf '%s\n' "$*" >&2; }
fail() { note "drive-server: $*"; exit 1; }

parse_arguments() {
    commands_from=""
    while (($# > 0)); do
        case $1 in
            --level)
                [[ ${2:-} ]] || fail "--level needs a world name"
                level=$2
                shift 2
                ;;
            -h | --help)
                # The comment block at the top of this file, which is the only documentation there is.
                awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "${BASH_SOURCE[0]}"
                exit 0
                ;;
            -*) fail "unknown option '$1'" ;;
            *)
                [[ -z $commands_from ]] || fail "only one command file, got '$commands_from' and '$1'"
                commands_from=$1
                shift
                ;;
        esac
    done
}

# Commands are read up front, because this script's own stdin becomes the server's once it starts.
#
# `#?` lines are skipped, so a file written for the old assertion layer still drives; comments and blank
# lines go the same way.
read_commands() {
    local source=${1:-/dev/stdin}
    if [[ -z ${1:-} ]]; then
        [[ -t 0 ]] && fail "give me a command file, or pipe commands in. --help for the shape of one"
    else
        [[ -f $source ]] || fail "no such command file: $source"
    fi

    commands=()
    local line
    while IFS= read -r line; do
        # `#?` lines were an assertion layer; acceptance is Kotest's now (:common:serverTest). They are
        # skipped rather than rejected, so an old check file still drives.
        [[ $line =~ ^[[:space:]]*#\? ]] && continue
        line=${line%%#*}
        line=${line%"${line##*[![:space:]]}"}
        [[ $line ]] || continue
        commands+=("$line")
    done < "$source"

    ((${#commands[@]} > 0)) || fail "no commands to run"
}

# Point the server at the throwaway world, and undo that whatever happens next. Minecraft rewrites
# the timestamp comment at the top of this file on every boot; only the level-name is ours to put back.
use_throwaway_world() {
    [[ -f $PROPERTIES ]] || fail "no $PROPERTIES yet — run ./gradlew :fabric:runServer once first"
    original_level=$(sed -n 's/^level-name=//p' "$PROPERTIES")
    [[ $original_level ]] || fail "$PROPERTIES names no level-name"
    trap 'sed -i "s/^level-name=.*/level-name=$original_level/" "$PROPERTIES"' EXIT
    sed -i "s/^level-name=.*/level-name=$level/" "$PROPERTIES"
    note "drive-server: using world '$level' (yours is '$original_level', restored on exit)"
}

# Blocks until [pattern] appears in the server log. [what] only ever appears in the failure message.
await() {
    local pattern=$1 what=$2 timeout=$3 waited=0
    until [[ -f $LOG ]] && grep -qF -- "$pattern" "$LOG"; do
        sleep "$POLL_SECONDS"
        waited=$((waited + POLL_SECONDS))
        ((waited < timeout)) || fail "gave up after ${timeout}s waiting for $what"
    done
}

# The server rewrites latest.log at boot, so an old one left over from last time would otherwise
# answer for this run — hence waiting for the file itself to be new before believing anything in it.
await_startup() {
    local waited=0
    until [[ -f $LOG && $(stat -c %Y "$LOG") -ge $started_at ]]; do
        sleep "$POLL_SECONDS"
        waited=$((waited + POLL_SECONDS))
        ((waited < STARTUP_TIMEOUT_SECONDS)) || fail "the server never wrote a fresh log"
    done
    await 'For help, type "help"' "the server to finish starting" "$STARTUP_TIMEOUT_SECONDS"
}

# Everything on stdout here is a server command; progress goes to stderr so it cannot become one.
#
# Runs in the subshell feeding the server's stdin, so `stop` is sent from an EXIT trap rather than
# from the bottom of the function: giving up on a command must still stop the server, or a check
# that timed out leaves a server running until something else kills it.
drive() {
    local barrier=0
    trap 'printf "stop\n"' EXIT

    await_startup
    note "drive-server: server up, running ${#commands[@]} command(s)"

    for command in "${commands[@]}"; do
        note "  > $command"
        printf '%s\n' "$command"

        local token="drive-server-$$-$((++barrier))"
        printf 'say %s\n' "$token"
        await "$token" "'$command' to finish" "$COMMAND_TIMEOUT_SECONDS"
    done

    note "drive-server: done, stopping"
}





main() {
    parse_arguments "$@"
    read_commands "$commands_from"

    # SDKMAN lives in ~/.bashrc, which a non-login shell may never have read.
    if ! command -v java > /dev/null && [[ -d $HOME/.sdkman/candidates/java/current ]]; then
        export JAVA_HOME="$HOME/.sdkman/candidates/java/current"
        export PATH="$JAVA_HOME/bin:$PATH"
    fi

    use_throwaway_world
    started_at=$(date +%s)

    # Process substitution rather than a pipe, so a failing gradle run is this script's exit status.
    "$REPOSITORY/gradlew" :fabric:runServer --console=plain < <(drive)

    note "drive-server: done — read the output above."
}

# Sourcing gets you the functions and nothing else, which is how the waiting above is tested.
if [[ ${BASH_SOURCE[0]} == "$0" ]]; then
    main "$@"
fi
