#!/usr/bin/env bash
# Prints the self-contained install.sh (launcher + shortcut embedded as base64)
# that users pipe into bash. Upload the output as a release asset named
# install.sh — the one-liner in the README and in the Android app points there.
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
launcher=$(base64 < "$root/bin/claude-launch" | tr -d '\n')
shortcut=$(base64 < "$root/shortcuts-Claude" | tr -d '\n')

# base64 output is [A-Za-z0-9+/=] only, so it is safe as a sed replacement with
# '|' as the delimiter (no |, &, or backslash can appear in it).
sed -e "s|@@LAUNCHER_B64@@|$launcher|" \
    -e "s|@@SHORTCUT_B64@@|$shortcut|" \
    "$root/scripts/install-template.sh"
