#!/bin/sh
DIR=$(dirname "$0")
if command -v python3 >/dev/null 2>&1; then
  exec python3 "$DIR/plugin.py"
fi
exec python "$DIR/plugin.py"
