#!/usr/bin/env bash
#
# The whole Termux-side setup, in one command:
#
#   pkg install -y curl && curl -fsSL \
#     https://github.com/ptrkhh/termux-claude-autoremote/releases/latest/download/install.sh | bash
#
# Packages, launcher, and the allow-external-apps property the app needs to
# drive it. Re-running it is safe — that is also how you update.
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
# jq/coreutils are usually present in a fresh Termux. Only shell out to pkg for
# what is actually missing so a re-run is instant.
missing=""
command -v jq      >/dev/null 2>&1 || missing="$missing jq"
command -v timeout >/dev/null 2>&1 || missing="$missing coreutils"
if [ -n "$missing" ]; then
  say "  installing:$missing"
  # shellcheck disable=SC2086
  pkg install -y $missing >/dev/null
fi
ok "packages (jq, coreutils)"

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

# ---------- 3. retire the widget shortcut ----------
# The change here is that we no longer CREATE this shortcut. An existing one is
# left in place and needs no edit: its body is already `exec claude-launch` with
# no arguments, and bare invocation now prints the migration notice, so a
# stranded widget tap explains itself for free.
#
# Deleting the file instead would be worse: the tap would then land on a missing
# script and show a raw shell error, and the notice — which only exists in the
# script this same run installs — would have no surface to appear on. Once
# Termux:Widget is uninstalled Android disables its pinned shortcuts anyway, so
# this only has to cover the window between updating the script and removing the
# app.
if [ -f "$HOME/.shortcuts/Claude" ]; then
  chmod +x "$HOME/.shortcuts/Claude"
  ok "widget shortcut left in place; it now prints the migration notice"
fi

# ---------- 4. allow-external-apps ----------
# Required. Without this property, RUN_COMMAND intents from the app are
# silently refused by Termux — there is no error, the app just never launches
# anything. This is not a convenience setting; it is the one thing that makes
# the app work at all.
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
if command -v claude >/dev/null 2>&1; then
  ok "Claude Code installed"
else
  warn "Claude Code not found. Install it with:"
  warn "    pkg install -y nodejs-lts && npm install -g @anthropic-ai/claude-code"
  warn "  then run 'claude' once to sign in. Avoid installers that patchelf the"
  warn "  native binary — see the repo README (\"Claude Code\")."
fi

# ---------- 6. what's left, and only what's left ----------
say ""
say "Done. Next:"
say "  • Install the Open Claude Code Here app from the project's releases page."
say "    It is what starts sessions now — this script only sets up the Termux side."
if [ "$restart_needed" = 1 ]; then
  say "  • You just enabled allow-external-apps. Fully close Termux (swipe it from"
  say "    Recents, or Exit from its notification) and reopen it, or the app will"
  say "    not be able to run anything at all."
fi
say ""
say "Already installed the app? If it still shows Termux:API and Termux:Widget"
say "steps and a homescreen-widget step, it is the old version — install the latest"
say "APK, then you can uninstall both plugin apps and the now-unused termux-api"
say "package (pkg uninstall -y termux-api)."
say ""
