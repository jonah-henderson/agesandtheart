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
# EXPECTATIONS. A line beginning `#?` is a claim about the output of the next command, and turns this
# from a thing you read into a thing that passes or fails. What a check file used to record in prose —
# "MEASURED 2026-07-27: 248,734 blocks" — can be written as one of these instead:
#
#   #? expect    <extended regex>       the command's output must contain a line matching this
#   #? reject    <extended regex>       it must not
#   #? at-least  <number> <regex>       the first number on the matching line is >= number
#   #? at-most   <number> <regex>       ...is <= number
#
# Several may stack on one command; all must hold. `at-least`/`at-most` read the first integer on the
# matching line, commas and all, so `248,734 block(s) differ` reads as 248734.
#
#   #? at-least 100000 block\(s\) differ
#   age compare riddledonly riddledsolid 6
#
# They are evaluated after the server stops, against the log, and a failure makes this script exit 1.
# A file with no expectations behaves exactly as it always did: it drives, and you read the output.
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
# `#?` lines are gathered as they are passed and attached to the command that follows, so an expectation
# reads above the command it is about — the same place the prose it replaces already sat. Every other
# comment is stripped as before.
read_commands() {
    local source=${1:-/dev/stdin}
    if [[ -z ${1:-} ]]; then
        [[ -t 0 ]] && fail "give me a command file, or pipe commands in. --help for the shape of one"
    else
        [[ -f $source ]] || fail "no such command file: $source"
    fi

    commands=()
    expectations=()
    local pending="" line
    while IFS= read -r line; do
        if [[ $line =~ ^[[:space:]]*#\?[[:space:]]*(.*)$ ]]; then
            local directive=${BASH_REMATCH[1]%"${BASH_REMATCH[1]##*[![:space:]]}"}
            [[ $directive ]] && pending+="$directive"$'\n'
            continue
        fi
        line=${line%%#*}
        line=${line%"${line##*[![:space:]]}"}
        [[ $line ]] || continue
        commands+=("$line")
        expectations+=("$pending")
        pending=""
    done < "$source"

    ((${#commands[@]} > 0)) || fail "no commands to run"
    [[ -z $pending ]] || fail "expectations at the end of the file name no command: $pending"
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

# The output of command [index], read out of the finished log.
#
# Sliced on the barrier tokens the driving already emits: the token after command N lands in the log
# once N has finished, so everything between token N-1 and token N is N's output and nothing else. That
# is the same fact the waiting relies on, used a second time.
# The driving numbers barriers from one, so the command at [index] is followed by token index+1 and
# preceded by token index — and the very first command has the top of the log in front of it instead.
output_of() {
    local index=$1
    local before="drive-server-$$-$index" after="drive-server-$$-$((index + 1))"
    # The barrier lines themselves are dropped: they carry this script's pid, which is a number, and
    # `at-least` reads the first number on whatever line it matched. A slightly loose pattern would
    # otherwise be measuring the process id.
    if ((index == 0)); then
        sed -n "1,/$after/p" "$LOG" | grep -vF "drive-server-$$-"
    else
        sed -n "/$before/,/$after/p" "$LOG" | grep -vF "drive-server-$$-"
    fi
}

# The first integer on [line], commas stripped — so "248,734 block(s) differ" reads as 248734.
first_number_in() {
    local found
    found=$(printf '%s' "$1" | tr -d ',' | grep -oE '[0-9]+' | head -1)
    printf '%s' "${found:-}"
}

# Every expectation, against the log the run just wrote. Returns non-zero if any failed.
check_expectations() {
    local failures=0 index directive verb number pattern slice matched observed
    for index in "${!commands[@]}"; do
        [[ ${expectations[index]} ]] || continue
        # `|| true` because a command that printed nothing leaves `output_of`'s filter with nothing to
        # pass through, and grep says 1 for that. Under `set -e` an empty slice would end the run rather
        # than fail the expectation it should fail.
        slice=$(output_of "$index") || true
        while IFS= read -r directive; do
            [[ $directive ]] || continue
            verb=${directive%% *}
            case $verb in
                expect | reject) pattern=${directive#* } ;;
                at-least | at-most)
                    number=${directive#* }
                    number=${number%% *}
                    pattern=${directive#* }
                    pattern=${pattern#* }
                    ;;
                *)
                    note "drive-server: unknown expectation '$verb' on '${commands[index]}'"
                    failures=$((failures + 1))
                    continue
                    ;;
            esac

            matched=$(grep -E -- "$pattern" <<< "$slice" | head -1) || true
            case $verb in
                expect)
                    [[ $matched ]] || {
                        note "  FAILED  '${commands[index]}' — nothing matched /$pattern/"
                        failures=$((failures + 1))
                    }
                    ;;
                reject)
                    [[ -z $matched ]] || {
                        note "  FAILED  '${commands[index]}' — /$pattern/ matched: $matched"
                        failures=$((failures + 1))
                    }
                    ;;
                at-least | at-most)
                    if [[ -z $matched ]]; then
                        note "  FAILED  '${commands[index]}' — nothing matched /$pattern/, so there is no number to check"
                        failures=$((failures + 1))
                        continue
                    fi
                    observed=$(first_number_in "$matched")
                    if [[ -z $observed ]]; then
                        note "  FAILED  '${commands[index]}' — no number on the matching line: $matched"
                        failures=$((failures + 1))
                    elif [[ $verb == at-least ]] && ((observed < number)); then
                        note "  FAILED  '${commands[index]}' — expected at least $number, got $observed: $matched"
                        failures=$((failures + 1))
                    elif [[ $verb == at-most ]] && ((observed > number)); then
                        note "  FAILED  '${commands[index]}' — expected at most $number, got $observed: $matched"
                        failures=$((failures + 1))
                    fi
                    ;;
            esac
        done <<< "${expectations[index]}"
    done

    if ((failures > 0)); then
        note "drive-server: $failures expectation(s) failed"
        return 1
    fi
    return 0
}

# How many expectations the file carries at all, so a run says whether it asserted anything.
count_expectations() {
    local total=0 block
    for block in "${expectations[@]}"; do
        [[ $block ]] || continue
        total=$((total + $(grep -c . <<< "$block")))
    done
    printf '%s' "$total"
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

    # Expectations are evaluated here rather than inside `drive`, deliberately: `drive` runs in the
    # subshell feeding the server's stdin, where anything printed becomes a command and an exit status
    # goes nowhere. The log is complete by now, and the barrier tokens are still in it.
    local declared
    declared=$(count_expectations)
    if ((declared == 0)); then
        note "drive-server: no expectations in this file — read the output above."
        return 0
    fi
    if check_expectations; then
        note "drive-server: all $declared expectation(s) held."
    else
        return 1
    fi
}

# Sourcing gets you the functions and nothing else, which is how the waiting above is tested.
if [[ ${BASH_SOURCE[0]} == "$0" ]]; then
    main "$@"
fi
