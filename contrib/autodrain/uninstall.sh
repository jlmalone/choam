#!/bin/bash
# Remove choam-autodrain from both the signed supervisor and legacy launchd.
set -euo pipefail

BIN="${BIN:-$HOME/.local/bin}"
AGENTS="$HOME/Library/LaunchAgents"
LABEL="com.user.choam-autodrain"
UID_NUM="$(id -u)"
CONFIG="${SERVER_MONITOR_AGENT_CONFIG:-$HOME/.config/server-monitor/infrastructure-agent.json}"

if [[ -f "$CONFIG" ]]; then
  /usr/bin/python3 - "$CONFIG" <<'PY'
import json, os, sys, tempfile
path = sys.argv[1]
with open(path, encoding="utf-8") as stream:
    payload = json.load(stream)
payload["scheduledJobs"] = [row for row in payload.get("scheduledJobs", [])
                            if row.get("id") != "choam-autodrain"]
fd, temp = tempfile.mkstemp(prefix="infrastructure-agent.", suffix=".json",
                            dir=os.path.dirname(path))
try:
    with os.fdopen(fd, "w", encoding="utf-8") as stream:
        json.dump(payload, stream, indent=2)
        stream.write("\n")
    os.replace(temp, path)
except Exception:
    try: os.unlink(temp)
    except OSError: pass
    raise
PY
fi

launchctl bootout "gui/$UID_NUM/$LABEL" 2>/dev/null || true
rm -f "$AGENTS/$LABEL.plist" "$BIN/choam-autodrain"

echo "removed choam-autodrain (logs and state were preserved)"
