#!/bin/bash
LABEL="com.claude-relay.server"
launchctl bootout "gui/$(id -u)/$LABEL" 2>/dev/null || true
rm -f "$HOME/Library/LaunchAgents/$LABEL.plist"
echo "Relay server removed (config kept in ~/.claude-relay)."
