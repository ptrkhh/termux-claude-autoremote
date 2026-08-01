#!/usr/bin/env bash
#
# Installer template. `scripts/build-installer.sh` fills in the two @@…@@
# placeholders with base64 payloads and prints the finished install.sh, which is
# what users pipe into bash. Kept as a real file (not a heredoc) so it stays
# readable and shellcheck-able.
#
# It is the *whole* setup: packages, launcher, widget shortcut, and the
# allow-external-apps property the share-sheet app needs. Re-running it is safe.
set -eu

say()  { printf '%s\n' "$*"; }
ok()   { printf '  \033[32m✓\033[0m %s\n' "$*"; }
warn() { printf '  \033[33m!\033[0m %s\n' "$*"; }

if [ "${PREFIX:-}" != /data/data/com.termux/files/usr ]; then
  say "This installer must run inside Termux." >&2
  exit 1
fi

say ""
say "Installing Claude Auto Remote…"

# ---------- 1. packages ----------
# jq/coreutils are usually present in a fresh Termux; termux-api (the CLI half
# of Termux:API) never is. Only shell out to pkg for what is actually missing so
# a re-run is instant.
missing=""
command -v jq            >/dev/null 2>&1 || missing="$missing jq"
command -v termux-dialog >/dev/null 2>&1 || missing="$missing termux-api"
command -v timeout       >/dev/null 2>&1 || missing="$missing coreutils"
if [ -n "$missing" ]; then
  say "  installing:$missing"
  # shellcheck disable=SC2086
  pkg install -y $missing >/dev/null
fi
ok "packages (jq, termux-api, coreutils)"

# ---------- 2. launcher + widget shortcut ----------
mkdir -p "$HOME/bin" "$HOME/.shortcuts"
printf '%s' '@@LAUNCHER_B64@@' | base64 -d > "$HOME/bin/claude-launch"
printf '%s' '@@SHORTCUT_B64@@' | base64 -d > "$HOME/.shortcuts/Claude"
chmod +x "$HOME/bin/claude-launch" "$HOME/.shortcuts/Claude"
ok "launcher (~/bin/claude-launch) and widget shortcut (~/.shortcuts/Claude)"

# ---------- 3. allow-external-apps ----------
# Needed only by the "Open Claude Code here" share-sheet app, but setting it now
# means that app is a one-tap install later instead of a five-step ordeal.
props="$HOME/.termux/termux.properties"
mkdir -p "$HOME/.termux"
[ -f "$props" ] || : > "$props"
if grep -qE '^[[:space:]]*allow-external-apps[[:space:]]*=[[:space:]]*true' "$props"; then
  ok "allow-external-apps already enabled"
  restart_needed=0
else
  # Drop commented-out or =false variants first so re-running never stacks
  # duplicate keys (the old README's plain >> did).
  sed -i -E '/^[[:space:]]*#?[[:space:]]*allow-external-apps[[:space:]]*=/d' "$props"
  # Don't glue onto a file that lacks a trailing newline.
  if [ -s "$props" ] && [ "$(tail -c1 "$props" | wc -l)" -eq 0 ]; then printf '\n' >> "$props"; fi
  printf 'allow-external-apps=true\n' >> "$props"
  ok "allow-external-apps=true"
  restart_needed=1
fi
termux-reload-settings >/dev/null 2>&1 || true

# ---------- 4. advisory checks ----------
say ""
say "Checks:"
if timeout 3 termux-battery-status >/dev/null 2>&1; then
  ok "Termux:API app responding"
else
  warn "Termux:API app not responding — install the Termux:API *app* from the"
  warn "  same source as Termux (the termux-api package alone is not enough)."
fi
if command -v claude >/dev/null 2>&1; then
  ok "Claude Code installed"
else
  warn "Claude Code not found — install it and run 'claude' once to sign in."
fi

# ---------- 5. what's left, and only what's left ----------
say ""
say "Done. Next:"
say "  • Long-press your homescreen → Widgets → Termux:Widget → pick \"Claude\"."
if [ "$restart_needed" = 1 ]; then
  say "  • Using the \"Open Claude Code here\" share app? Fully close Termux"
  say "    (swipe it from Recents, or Exit from its notification) and reopen it."
fi
say ""
