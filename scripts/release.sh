#!/usr/bin/env bash
# Releases Ages and the Art for the beta. scripts/release.sh --help, and notes/release-process.md.
exec python3 "$(dirname "$0")/release/release.py" "$@"
