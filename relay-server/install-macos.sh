#!/bin/bash
# Registers the relay server as a launchd agent so it runs 24/7 on the Mac mini
# (starts at login, restarts automatically if it crashes).
set -euo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"
LABEL="com.claude-relay.server"
PLIST="$HOME/Library/LaunchAgents/$LABEL.plist"
LOG_DIR="$HOME/.claude-relay"
NODE_BIN="$(command -v node || true)"
CLAUDE_BIN="$(command -v claude || true)"

if [ -z "$NODE_BIN" ]; then echo "node not found. Install it first: brew install node"; exit 1; fi
if [ -z "$CLAUDE_BIN" ]; then echo "claude not found. Install Claude Code and log in first."; exit 1; fi

mkdir -p "$LOG_DIR" "$HOME/Library/LaunchAgents"
(cd "$DIR" && npm install --omit=dev)

cat > "$PLIST" <<PL
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>$LABEL</string>
  <key>ProgramArguments</key>
  <array>
    <string>$NODE_BIN</string>
    <string>$DIR/server.js</string>
  </array>
  <key>WorkingDirectory</key><string>$DIR</string>
  <key>EnvironmentVariables</key>
  <dict>
    <key>PATH</key><string>$(dirname "$CLAUDE_BIN"):$(dirname "$NODE_BIN"):/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin</string>
    <key>CLAUDE_BIN</key><string>$CLAUDE_BIN</string>
    <key>HOME</key><string>$HOME</string>
  </dict>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
  <key>StandardOutPath</key><string>$LOG_DIR/server.log</string>
  <key>StandardErrorPath</key><string>$LOG_DIR/server.log</string>
</dict>
</plist>
PL

launchctl bootout "gui/$(id -u)/$LABEL" 2>/dev/null || true
launchctl bootstrap "gui/$(id -u)" "$PLIST"
launchctl enable "gui/$(id -u)/$LABEL"

sleep 2
echo
echo "Relay server installed: $PLIST"
echo "Log: $LOG_DIR/server.log"
echo
grep -E "app server URL|token:" "$LOG_DIR/server.log" | tail -4 || true
echo
echo "Keep the Mac awake (run once):  sudo pmset -a sleep 0 disksleep 0"
