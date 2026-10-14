> **Deprecated.** This project moved to [claude-dashboard](https://github.com/ptrkhh/claude-dashboard). The repo is archived and will not change. Termux setup: [Android client](https://github.com/ptrkhh/claude-dashboard#android-client). Not carried over: the Android share-sheet entry point. Already installed? Nothing changes on your device.

# Termux Claude Auto Remote

Touch-first launcher for [Claude Code](https://docs.anthropic.com/en/docs/claude-code)
on Android. Add it as a Termux:Widget homescreen shortcut, choose a working
directory/model/effort from native dialogs, and it starts Claude Code in Remote
Control mode before opening the matching conversation in the Claude app.

It also provides a touch-friendly picker for recent Claude sessions, so they can
be resumed without using the terminal's interactive `claude --resume` picker.

## Setup

### Recommended — let the app walk you through it

The companion app is the guided installer: it detects what's missing, links each
piece, and verifies the result. Nothing to copy out of this page.

**1.** Install `open-claude-code-here.apk` from the
[latest release](https://github.com/ptrkhh/termux-claude-autoremote/releases/latest).
Your browser or file manager will ask you to allow installing unknown apps.

**2.** Open **Open Claude Code Here** and work down its three steps:

- **Install Termux** — Termux, Termux:API and Termux:Widget each get a status
  line and a button straight to their F-Droid page. Installed ones show a ✓ and
  their button disappears.
- **Paste this into Termux** — tap **Copy & open Termux**, then long-press the
  terminal, tap Paste, and press Enter. That one command is the entire Termux
  side (see [what it does](#what-the-command-does)).
- **Allow this app to run it** — grants Termux's "Run commands" permission. This
  is an Android runtime permission; installing the app does *not* cover it.

**3.** Tap **Check setup**. It runs a command through Termux and names whichever
step is still missing, so you never have to guess whether it worked.

**4.** Long-press your homescreen → Widgets → Termux:Widget → pick **Claude**.

Installing the app here also gets you the **Open Claude Code here** share-sheet
entry described [below](#share-a-file-to-claude-open-claude-code-here).

### Alternative — no APK

Same result, minus the share-sheet entry and the guided checks.

**1.** Install [Termux](https://f-droid.org/packages/com.termux/),
[Termux:API](https://f-droid.org/packages/com.termux.api/) and
[Termux:Widget](https://f-droid.org/packages/com.termux.widget/) — all three,
all from the same source (F-Droid *or* GitHub; mixing them breaks the plugins).

**2.** Open Termux and paste this:

```sh
pkg install -y curl && curl -fsSL https://github.com/ptrkhh/termux-claude-autoremote/releases/latest/download/install.sh | bash
```

**3.** Long-press your homescreen → Widgets → Termux:Widget → pick **Claude**.

### What the command does

Both paths run the same one-liner. It installs the packages (`termux-api`, `jq`,
`coreutils`), writes `~/bin/claude-launch` and `~/.shortcuts/Claude`, enables
`allow-external-apps` for the share-sheet app, and prints a check of anything it
couldn't do for you. Re-running it is safe — that's also how you update.

It's [`install.sh`](install.sh) in this repo, unmodified, so you can read it
before piping it into a shell.

### Claude Code

The launcher assumes `claude` is installed and signed in. The minimal install:

```sh
pkg install -y nodejs-lts && npm install -g @anthropic-ai/claude-code
claude   # run once to sign in
```

Three things are known to break Claude Code on Android. Everything else about a
Termux dev setup is out of scope here — this is only the list that stops
`claude` from running at all.

**Don't let anything run `patchelf` on the Claude binary.** Some Termux guides
install the native `linux-arm64` build and repoint its ELF interpreter with
`patchelf --set-interpreter` so it loads under Termux's glibc. Claude Code is a
Bun standalone: it finds its embedded JS bundle through a trailer at the end of
the file, and patchelf shifts segment offsets and appends a relocated
section-header table at EOF, displacing that trailer. The symptom is a module
name that is visibly a fragment of minified JavaScript:

```
error: Module not found 'ntent.at(-1);switch(t.delta.type){c'
```

The binary can't be repaired in place — truncating gets past module resolution
and then dies with SIGBUS. The npm install above avoids this class entirely. If
you are already on a native install, run the *unmodified* binary through the
glibc loader (`exec $PREFIX/glibc/lib/ld-linux-aarch64.so.1 <binary>`) instead of
patching the ELF, and make sure no wrapper script re-patches it on update.

**Android 12+ kills Termux background processes.** Past ~32 child processes
Android silently SIGKILLs them, which shows up as:

```
[Process completed (signal 9) - press Enter]
```

This launcher is exposed to it: it backgrounds a watcher subshell that polls for
the remote-control session id while `claude` runs in the foreground. Fix it with
[Shizuku](https://f-droid.org/packages/moe.shizuku.privileged.api/) — no PC
needed. Install it, start it, export `rish` into Termux, then:

```sh
rish -c "settings put global settings_enable_monitor_phantom_procs false"
rish -c "device_config put activity_manager max_phantom_processes 2147483647"
```

Re-run after OS updates.

**`claude /login` timing out is usually DNS, not your account.** If sign-in
fails with `getaddrinfo ETIMEOUT platform.claude.com`, the cause is that Claude
Code resolves through bundled c-ares, which reads the literal `/etc/resolv.conf`
— a path that frequently doesn't exist on Android, where `/etc` maps to
`/system/etc`. With no config, c-ares falls back to nameserver `127.0.0.1` and
times out. This is easy to misdiagnose because everything else keeps working:
Termux's `curl` goes through Android's netd and glibc's resolver reads a
different file. The fix is to create `/etc/resolv.conf` with real nameservers,
which needs root since `/system` is read-only.

## Use

Tap the shortcut, then either:

- start a new session by selecting a directory, model, and effort; or
- choose **Resume session…** to reopen a recent Claude conversation.

The launcher remembers recently used directories. Use
`~/bin/claude-launch --selftest` to run its built-in checks.

### Picker style

Lists are shown with Termux:API's `radio` dialog: a centred dialog that sizes
itself to its content, so the whole list is visible on the first tap (select a
row, tap **OK**).

The alternative is `sheet`, which selects with a single tap but is an Android
bottom sheet. Termux:API never sets its peek height or expanded state, so on
some devices it opens as a collapsed sliver at the bottom edge showing only the
first (often clipped) row until you swipe it up. If your device does not do
that and you prefer the single tap, export the widget you want before launching
— e.g. in `~/.shortcuts/Claude`:

```sh
export CLAUDE_LAUNCH_PICKER=sheet   # radio (default) | sheet | spinner
```

## Share a file to Claude ("Open Claude Code here")

A tiny companion app (`android/`) adds an **Open Claude Code here** entry to the
Android share sheet. Share any file from a file explorer (Solid Explorer,
MiXplorer, Files by Google, …), pick **Open Claude Code here**, and Claude Code
launches with that file's folder as the working directory — skipping straight to
the model/effort pickers and the usual Remote Control flow.

Under the hood the app resolves the shared file's real folder and calls
`~/bin/claude-launch --dir <folder>` through Termux's `RUN_COMMAND` service.

If you followed the recommended setup above, this already works — the app you
installed to run the setup *is* the share target. Coming from the no-APK path
instead, install the APK and open it; only the permission step will be left,
and **Check setup** will confirm it.

If you enabled `allow-external-apps` during this session, **fully close and
reopen Termux** (swipe it from Recents, or *Exit* from its notification) before
sharing — `termux-reload-settings` alone does not always pick that property up.

Building the app yourself needs only the Android SDK:
`cd android && ./gradlew assembleDebug`.

### File-explorer compatibility

Whether "here" can be recovered depends entirely on *how* the file explorer
shares a file. Explorers that share a `file://` path or a system storage
document work; explorers that share through their own private `FileProvider`
expose only an opaque stream with no path, so the app can't tell where the file
lives and shows a "try a different file explorer" toast (it also copies the
failing URI to the clipboard to make diagnosing easy).

The table below is a **prediction based on each app's known share behavior — not
yet verified on-device.** Help fill it in: share a file, and if it fails, paste
the clipboard URI into an issue.

| File explorer            | Predicted | Why |
| ------------------------ | --------- | --- |
| Files by Google          | ✅ likely | Shares system storage/MediaStore document URIs (path decodable) |
| System file picker / DocumentsUI | ✅ likely | External-storage document URIs |
| MiXplorer (file:// mode) | ✅ likely | Can share raw `file://` paths when its content-provider option is off |
| Total Commander          | ✅ likely | Shares `file://` paths |
| Ghost Commander          | ✅ likely | Shares `file://` paths |
| MiXplorer (default)      | ⚠️ maybe  | Uses its own FileProvider unless configured for `file://` |
| Solid Explorer           | ❌ likely | Shares via its private FileProvider — no path exposed |
| CX File Explorer         | ❌ likely | Private FileProvider — no path exposed |
| FX File Explorer         | ❌ likely | Private FileProvider — no path exposed |

Legend: ✅ folder resolves, ⚠️ depends on settings, ❌ folder hidden (error toast).

## Releasing

Publishing a GitHub release is what makes a change live. CI attaches three
assets to it: `install.sh`, `claude-launch`, and `open-claude-code-here.apk`.
The setup one-liner fetches `install.sh` from the latest release, and that
script fetches `claude-launch` from the same place — so a fresh install always
gets an installer and a launcher from the same published release, and pushing
to `main` never changes what new users get until you publish.

CI also syntax-checks and shellchecks both scripts and runs
`claude-launch --selftest` on every push.

## Security

The launcher passes `--dangerously-skip-permissions` so remote sessions can run
without terminal interaction. Claude can therefore execute commands against the
files Termux can access. Only use it with an account, device, and working
directories you trust; adjust permissions inside Claude when needed.
