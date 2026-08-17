#!/usr/bin/env bash
set -euo pipefail

script_directory=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)

if [[ $# -eq 0 ]]; then
  exec node "$script_directory/release-promotion.mjs"
fi

case "$1" in
  prepare|upload|status|verify-published)
    exec node "$script_directory/release-promotion.mjs" "$@"
    ;;
  *)
    echo "Unsupported promotion mode: $1" >&2
    echo "Allowed modes: prepare, upload, status, verify-published" >&2
    exit 64
    ;;
esac
