#!/usr/bin/env bash
#
# The whole Termux-side setup, in one command:
#
#   pkg install -y curl && curl -fsSL \
#     https://github.com/ptrkhh/termux-claude-autoremote/releases/latest/download/install.sh | bash
#
# Packages, launcher, widget shortcut, and the allow-external-apps property the
# "Open Claude Code here" share app needs. Re-running it is safe — that is also
# how you update.
#
# This file is attached verbatim to each release, alongside the claude-launch it
# downloads, so both come from the same published release.
set -eu

BASE="https://github.com/ptrkhh/termux-claude-autoremote/releases/latest/download"

say()  { printf '%s\n' "$*"; }
ok()   { printf '  \033[32m✓\033[0m %s\n' "$*"; }
warn() { printf '  \033[33m!\033[0m %s\n' "$*"; }
die()  { printf '\033[31m%s\033[0m\n' "$*" >&2; exit 1; }

if [ "${PREFIX:-}" != /data/data/com.termux/files/usr ]; then
  die "This installer must run inside Termux."
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

# ---------- 2. launcher ----------
# Downloaded rather than embedded so this file stays readable before you pipe it
# into a shell. Written to a temp file and sanity-checked first: a proxy or a
# 404 page must never land in ~/bin as an executable.
tmp=$(mktemp)
trap 'rm -f "$tmp"' EXIT
curl -fsSL "$BASE/claude-launch" -o "$tmp" \
  || die "Couldn't download claude-launch. Check your connection and try again."
head -n 1 "$tmp" | grep -q '^#!' \
  || die "Downloaded claude-launch doesn't look like a script — aborting."
mkdir -p "$HOME/bin"
mv "$tmp" "$HOME/bin/claude-launch"
chmod +x "$HOME/bin/claude-launch"
trap - EXIT
ok "launcher (~/bin/claude-launch)"

# ---------- 3. widget shortcut ----------
# Two lines, so it is written here rather than shipped as its own file. Quoted
# heredoc: $HOME must stay literal and be expanded when the shortcut runs.
mkdir -p "$HOME/.shortcuts"
cat > "$HOME/.shortcuts/Claude" <<'SHORTCUT'
#!/data/data/com.termux/files/usr/bin/bash
exec "$HOME/bin/claude-launch"
SHORTCUT
chmod +x "$HOME/.shortcuts/Claude"
ok "widget shortcut (~/.shortcuts/Claude)"

# ---------- 4. allow-external-apps ----------
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
  # duplicate keys.
  sed -i -E '/^[[:space:]]*#?[[:space:]]*allow-external-apps[[:space:]]*=/d' "$props"
  # Don't glue onto a file that lacks a trailing newline.
  if [ -s "$props" ] && [ "$(tail -c1 "$props" | wc -l)" -eq 0 ]; then printf '\n' >> "$props"; fi
  printf 'allow-external-apps=true\n' >> "$props"
  ok "allow-external-apps=true"
  restart_needed=1
fi
termux-reload-settings >/dev/null 2>&1 || true

# ---------- 5. advisory checks ----------
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

# ---------- 6. what's left, and only what's left ----------
say ""
say "Done. Next:"
say "  • Long-press your homescreen → Widgets → Termux:Widget → pick \"Claude\"."
if [ "$restart_needed" = 1 ]; then
  say "  • Using the \"Open Claude Code here\" share app? Fully close Termux"
  say "    (swipe it from Recents, or Exit from its notification) and reopen it."
fi
say ""
