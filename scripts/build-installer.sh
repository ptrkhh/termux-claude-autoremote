#!/usr/bin/env bash
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
launcher=$(base64 < "$root/bin/claude-launch" | tr -d '\n')
shortcut=$(base64 < "$root/shortcuts-Claude" | tr -d '\n')

cat <<INSTALLER
#!/usr/bin/env bash
set -eu

if [ "\${PREFIX:-}" != /data/data/com.termux/files/usr ]; then
  echo "This installer must run inside Termux." >&2
  exit 1
fi

mkdir -p "\$HOME/bin" "\$HOME/.shortcuts"
printf '%s' '$launcher' | base64 -d > "\$HOME/bin/claude-launch"
printf '%s' '$shortcut' | base64 -d > "\$HOME/.shortcuts/Claude"
chmod +x "\$HOME/bin/claude-launch" "\$HOME/.shortcuts/Claude"
echo "Installed claude-launch to ~/bin and the Claude shortcut to ~/.shortcuts."
INSTALLER
