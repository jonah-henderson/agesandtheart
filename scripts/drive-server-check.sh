#!/usr/bin/env bash
#
# Checks `drive-server.sh`'s own expectation layer, with no server behind it.
#
# The expectation layer is the thing that turns a check file from something you read into something that
# passes or fails, so it is exactly the wrong thing to take on trust — a slicing bug that handed every
# command the whole log would make every expectation pass, and the check files would look green while
# asserting nothing. That failure is silent, which is the argument for this file existing.
#
# It works by sourcing `drive-server.sh` (which defines its functions and runs nothing) and pointing it at
# a synthetic log shaped like a real run. Run it in a second:
#
#     scripts/drive-server-check.sh
#
# Every bug it currently guards against is one it actually caught while being written: inverted slice
# boundaries, an off-by-one on the barrier index, `at-least` reading this script's process id off a
# barrier line, and a silent command ending the run instead of failing its expectation.
set -uo pipefail

# Not `REPOSITORY`, and not readonly: the script being sourced below declares that name itself.
here=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
readonly WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

export DRIVE_SERVER_LOG="$WORK/latest.log"
source "$here/drive-server.sh"
# The script under test sets -e; this one deliberately runs things that return 1.
set +e

passed=0
failed=0

# outcome <what> <expected exit> <actual exit>
outcome() {
    if [[ $2 == "$3" ]]; then
        passed=$((passed + 1))
    else
        failed=$((failed + 1))
        printf 'FAILED: %s (expected exit %s, got %s)\n' "$1" "$2" "$3"
    fi
}

# A log shaped like a real run: each command's output followed by its barrier token. The driving numbers
# barriers from one, so command N is followed by token N+1 — the fact all the slicing rests on.
{
    echo "starting"
    echo "  248,734 block(s) differ across 284 of 289 chunks"
    echo "[Server] drive-server-$$-1"
    echo "The Art knows 1097 words."
    echo "[Server] drive-server-$$-2"
    echo "  identical: 25 chunks agree block for block"
    echo "  a line with no digits at all"
    echo "[Server] drive-server-$$-3"
    echo "[Server] drive-server-$$-4"
} > "$DRIVE_SERVER_LOG"

# run_with <directives for each of the four commands> — returns check_expectations' exit status.
run_with() {
    commands=("age compare a b 6" "age words" "age compare c d 2" "age gen quiet")
    expectations=("$1" "$2" "$3" "${4:-}")
    check_expectations > /dev/null 2>&1
    echo $?
}

# --- slicing: each command sees its own output and nobody else's ---
outcome "a command sees its own number" 0 "$(run_with $'at-least 100000 block\\(s\\) differ\n' '' '')"
outcome "a later command cannot see an earlier one's output" 1 "$(run_with '' $'expect block\\(s\\) differ\n' '')"
outcome "the last command sees its own line" 0 "$(run_with '' '' $'expect identical:\n')"

# --- at-least / at-most, including the comma stripping ---
outcome "at-least holds at the boundary" 0 "$(run_with $'at-least 248734 block\\(s\\) differ\n' '' '')"
outcome "at-least fails one above" 1 "$(run_with $'at-least 248735 block\\(s\\) differ\n' '' '')"
outcome "at-most holds at the boundary" 0 "$(run_with $'at-most 248734 block\\(s\\) differ\n' '' '')"
outcome "at-most fails one below" 1 "$(run_with $'at-most 248733 block\\(s\\) differ\n' '' '')"
outcome "a word count reads as a number" 0 "$(run_with '' $'at-least 1000 The Art knows\n' '')"

# --- expect / reject ---
outcome "expect matches" 0 "$(run_with '' $'expect The Art knows [0-9]+ words\n' '')"
outcome "expect fails when absent" 1 "$(run_with '' $'expect Vocabulary problem\n' '')"
outcome "reject holds when absent" 0 "$(run_with '' $'reject Vocabulary problem\n' '')"
outcome "reject fails when present" 1 "$(run_with '' $'reject The Art knows\n' '')"

# --- a match with no number on it is a failure, never a silent pass ---
outcome "at-least on a line with no number fails" 1 "$(run_with '' '' $'at-least 5 no digits at all\n')"
# The barrier lines carry this script's pid. If they reached the matcher, this would read it as a number.
outcome "a barrier line cannot be measured" 1 "$(run_with '' '' $'at-least 1 drive-server\n')"

# --- stacking: every directive on a command must hold ---
outcome "several directives all hold" 0 "$(run_with $'at-least 1000 block\\(s\\) differ\nreject identical:\n' '' '')"
outcome "one bad directive among good ones fails" 1 "$(run_with $'at-least 1000 block\\(s\\) differ\nexpect nonsense\n' '' '')"

# --- a command that printed nothing fails its expectation, rather than ending the run ---
outcome "a silent command fails 'expect' cleanly" 1 "$(run_with '' '' '' $'expect anything\n')"
outcome "a silent command fails 'at-least' cleanly" 1 "$(run_with '' '' '' $'at-least 1 anything\n')"
outcome "a silent command satisfies 'reject'" 0 "$(run_with '' '' '' $'reject anything\n')"

# --- an unknown verb is a failure, never ignored ---
outcome "an unknown verb fails loudly" 1 "$(run_with $'assert-ish 5 whatever\n' '' '')"

# --- parsing ---
cat > "$WORK/commands.txt" <<'EOF'
# an ordinary comment, stripped
#? at-least 100 block\(s\) differ
#? reject identical:
age compare a b 6

age words   # trailing comment
EOF
read_commands "$WORK/commands.txt"
outcome "two commands parsed" 0 "$([[ ${#commands[@]} == 2 ]] && echo 0 || echo 1)"
outcome "the first command survives intact" 0 "$([[ ${commands[0]} == "age compare a b 6" ]] && echo 0 || echo 1)"
outcome "a trailing comment is stripped" 0 "$([[ ${commands[1]} == "age words" ]] && echo 0 || echo 1)"
outcome "both directives attach to the command below them" 0 \
    "$([[ $(grep -c . <<< "${expectations[0]}") == 2 ]] && echo 0 || echo 1)"
outcome "a command with no directives has none" 0 "$([[ -z ${expectations[1]} ]] && echo 0 || echo 1)"
outcome "count_expectations counts them" 0 "$([[ $(count_expectations) == 2 ]] && echo 0 || echo 1)"

# --- an expectation naming no command is refused, rather than silently doing nothing ---
cat > "$WORK/dangling.txt" <<'EOF'
age words
#? expect something
EOF
(read_commands "$WORK/dangling.txt") > /dev/null 2>&1
outcome "a dangling expectation is refused" 1 "$?"

printf '\n%d passed, %d failed\n' "$passed" "$failed"
((failed == 0))
