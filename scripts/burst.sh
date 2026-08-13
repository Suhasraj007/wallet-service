#!/usr/bin/env sh
# One-command probe: ./scripts/burst.sh https://your-app.example.com
exec python3 "$(dirname "$0")/burst.py" "$@"
