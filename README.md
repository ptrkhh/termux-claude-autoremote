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
git clone https://github.com/ptrkhh/termux-claude-autoremote.git ~/git/termux-claude-autoremote
mkdir -p ~/bin ~/.shortcuts
cp ~/git/termux-claude-autoremote/bin/claude-launch ~/bin/
cp ~/git/termux-claude-autoremote/shortcuts-Claude ~/.shortcuts/Claude
chmod +x ~/bin/claude-launch ~/.shortcuts/Claude
```

Place the **Claude** shortcut from Termux:Widget on the Android homescreen.

## Use

Tap the shortcut, then either:

- start a new session by selecting a directory, model, and effort; or
- choose **Resume session…** to reopen a recent Claude conversation.

The launcher remembers recently used directories and the last new-session
selection. Use `~/bin/claude-launch --selftest` to run its built-in checks.

## Security

The launcher passes `--dangerously-skip-permissions` so remote sessions can run
without terminal interaction. Claude can therefore execute commands against the
files Termux can access. Only use it with an account, device, and working
directories you trust; adjust permissions inside Claude when needed.
