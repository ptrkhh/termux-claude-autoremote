# Termux Claude Auto Remote

Touch-first launcher for [Claude Code](https://docs.anthropic.com/en/docs/claude-code)
on Android. Add it as a Termux:Widget homescreen shortcut, choose a working
directory/model/effort from native dialogs, and it starts Claude Code in Remote
Control mode before opening the matching conversation in the Claude app.

It also provides a touch-friendly picker for recent Claude sessions, so they can
be resumed without using the terminal's interactive `claude --resume` picker.

## Requirements

- Termux, Termux:API, and Termux:Widget installed from the same source
- Termux packages: `termux-api`, `jq`, and `coreutils`
- Claude Code installed and signed in
- Claude mobile app installed and signed in

## Install

In Termux:

```sh
curl -fsSL https://github.com/ptrkhh/termux-claude-autoremote/releases/latest/download/install.sh | bash
```

This writes `~/bin/claude-launch` and `~/.shortcuts/Claude`.

Place the **Claude** shortcut from Termux:Widget on the Android homescreen.

## Use

Tap the shortcut, then either:

- start a new session by selecting a directory, model, and effort; or
- choose **Resume session…** to reopen a recent Claude conversation.

The launcher remembers recently used directories and the last new-session
selection. Use `~/bin/claude-launch --selftest` to run its built-in checks.

## Share a file to Claude ("Open Claude Code here")

A tiny companion app (`android/`) adds an **Open Claude Code here** entry to the
Android share sheet. Share any file from a file explorer (Solid Explorer,
MiXplorer, Files by Google, …), pick **Open Claude Code here**, and Claude Code
launches with that file's folder as the working directory — skipping straight to
the model/effort pickers and the usual Remote Control flow.

Under the hood the app resolves the shared file's real folder and calls
`~/bin/claude-launch --dir <folder>` through Termux's `RUN_COMMAND` service.

### Install the app

1. Grab `open-claude-code-here.apk` from the latest
   [release](https://github.com/ptrkhh/termux-claude-autoremote/releases/latest),
   or from the **Build APK** workflow's artifacts under the repo's Actions tab.
2. Sideload it (allow "install unknown apps" for your browser/file manager).
3. One-time Termux setup so the app is allowed to start `claude-launch`:

   ```sh
   mkdir -p ~/.termux
   printf 'allow-external-apps=true\n' >> ~/.termux/termux.properties
   ```

   Then **fully close and reopen Termux** (swipe it away from Recents, or use
   *Exit* from its notification). `termux-reload-settings` alone does not always
   pick up `allow-external-apps`.

4. Open the **Open Claude Code Here** app and tap **Grant Termux "Run commands"
   permission**. This runtime permission is *not* granted just by installing the
   app — Android must show you the grant dialog once. The app's home screen shows
   ✓/✗ so you can confirm it stuck. (You can also grant it on first share, but
   granting it here up front is the most reliable.)

Building it yourself needs only the Android SDK: `cd android && ./gradlew assembleDebug`.

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

## Security

The launcher passes `--dangerously-skip-permissions` so remote sessions can run
without terminal interaction. Claude can therefore execute commands against the
files Termux can access. Only use it with an account, device, and working
directories you trust; adjust permissions inside Claude when needed.
