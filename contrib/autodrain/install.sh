#!/bin/bash
# Install the green-gated queue drain under Server Monitor's signed supervisor.
# A legacy per-script LaunchAgent remains available only through the explicit
# compatibility flag. The default never creates a second infrastructure owner.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
BIN="${BIN:-$HOME/.local/bin}"
AGENTS="$HOME/Library/LaunchAgents"
LABEL="com.user.choam-autodrain"
UNIFIED_LABEL="vision.salient.InfrastructureAgent"
UID_NUM="$(id -u)"
APP="${SERVER_MONITOR_APP:-/Applications/ServerMonitor.app}"
CONFIG="${SERVER_MONITOR_AGENT_CONFIG:-$HOME/.config/server-monitor/infrastructure-agent.json}"
STATUS="${SERVER_MONITOR_AGENT_STATUS:-$HOME/Library/Application Support/ServerMonitor/infrastructure-agent-status.json}"
LEGACY=0

for arg in "$@"; do
  case "$arg" in
    --legacy-agent) LEGACY=1 ;;
    *) echo "Usage: ./install.sh [--legacy-agent]" >&2; exit 2 ;;
  esac
done

mkdir -p "$BIN" "$AGENTS" \
  "$HOME/Library/Logs/choam-autodrain" \
  "$HOME/.local/state/choam-autodrain"
install -m 0755 "$HERE/choam-autodrain" "$BIN/choam-autodrain"

install_legacy() {
  sed -e "s|__BIN__|$BIN|g" -e "s|__HOME__|$HOME|g" \
    "$HERE/$LABEL.plist" > "$AGENTS/$LABEL.plist"
  launchctl bootout "gui/$UID_NUM/$LABEL" 2>/dev/null || true
  launchctl bootstrap "gui/$UID_NUM" "$AGENTS/$LABEL.plist"
  launchctl enable "gui/$UID_NUM/$LABEL" 2>/dev/null || true
  echo "installed legacy scheduler $LABEL"
}

update_unified_config() { # add|remove
  local action="$1"
  mkdir -p "$(dirname "$CONFIG")"
  /usr/bin/python3 - "$CONFIG" "$BIN/choam-autodrain" "$action" <<'PY'
import json, os, sys, tempfile
path, helper, action = sys.argv[1:]
if os.path.exists(path):
    with open(path, encoding="utf-8") as stream:
        payload = json.load(stream)
else:
    payload = {
        "schema": 1,
        "statusFile": os.path.expanduser(
            "~/Library/Application Support/ServerMonitor/infrastructure-agent-status.json"
        ),
        "persistentChildren": [],
        "scheduledJobs": [],
        "watchNetworkChanges": False,
        "watchPaths": [],
    }
if payload.get("schema") != 1:
    raise SystemExit("unsupported infrastructure-agent config schema")
jobs = [row for row in payload.setdefault("scheduledJobs", [])
        if row.get("id") != "choam-autodrain"]
if action == "add":
    jobs.append({
        "id": "choam-autodrain",
        "command": [helper],
        "intervalSeconds": 90,
        "timeoutSeconds": 86400,
        "runAtStart": True,
    })
elif action != "remove":
    raise SystemExit("invalid config action")
payload["scheduledJobs"] = jobs
payload.setdefault("persistentChildren", [])
payload.setdefault("watchPaths", [])
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
}

if [[ "$LEGACY" == 0 && -x "$APP/Contents/Resources/InfrastructureAgent" ]]; then
  # The containing app owns SMAppService registration. Approval may still be
  # required. Do not add a second scheduler to its config until the service is
  # actually running; otherwise later approval would silently activate both it
  # and the legacy fallback.
  /usr/bin/open -gj "$APP"
  unified_loaded=0
  for _ in {1..45}; do
    if launchctl print "gui/$UID_NUM/$UNIFIED_LABEL" 2>/dev/null \
      | grep -qE 'state = running|pid = [0-9]+'; then
      unified_loaded=1
      break
    fi
    sleep 1
  done

  if [[ "$unified_loaded" == 0 ]]; then
    echo "ERROR: signed infrastructure agent is not active/approved" >&2
    echo "Approve Server Monitor in Login Items, then rerun this installer." >&2
    exit 1
  else
    update_unified_config add
    unified_ready=0
    for _ in {1..45}; do
      if [[ -r "$STATUS" ]] \
      && /usr/bin/python3 - "$STATUS" <<'PY' >/dev/null 2>&1
import json, os, sys, time
path = sys.argv[1]
data = json.load(open(path))
jobs = {row.get("id"): row for row in data.get("scheduledJobs", [])}
job = jobs.get("choam-autodrain", {})
ok = data.get("schema") == 2 and data.get("configLoaded") is True
ok = ok and time.time() - os.path.getmtime(path) < 90
ok = ok and bool(job) and (job.get("running") is True or job.get("lastExitStatus") == 0)
raise SystemExit(0 if ok else 1)
PY
      then
        unified_ready=1
        break
      fi
      sleep 1
    done

    if [[ "$unified_ready" == 1 ]]; then
      launchctl bootout "gui/$UID_NUM/$LABEL" 2>/dev/null || true
      rm -f "$AGENTS/$LABEL.plist"
      echo "installed choam-autodrain under the signed infrastructure agent"
    else
      # Roll back the unproven unified job. A later agent restart must not
      # silently activate a scheduler that this installation failed to verify.
      update_unified_config remove
      echo "ERROR: signed agent did not confirm the choam-autodrain job" >&2
      exit 1
    fi
  fi
else
  if [[ "$LEGACY" == 1 ]]; then
    install_legacy
  else
    echo "ERROR: Server Monitor's signed infrastructure agent is unavailable" >&2
    echo "Install or update Server Monitor, then rerun this installer." >&2
    exit 1
  fi
fi

echo "  helper: $BIN/choam-autodrain"
echo "  logs:   ~/Library/Logs/choam-autodrain/"
echo "  state:  ~/.local/state/choam-autodrain/status.json"
