#!/bin/bash
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
TMP="$(mktemp -d -t choam-autodrain-test)"
trap 'rm -rf "$TMP"' EXIT

HOME_DIR="$TMP/home"
CHOAM_HOME="$HOME_DIR/.choam"
mkdir -p "$CHOAM_HOME" "$HOME_DIR/bin" "$HOME_DIR/.ssh" "$HOME_DIR/.config/choam"
touch "$HOME_DIR/.ssh/id_ed25519" "$HOME_DIR/.ssh/id_ed25519.offsite"
cat > "$HOME_DIR/.config/choam/autodrain-ssh-keys" <<'EOF'
# Additional identity
~/.ssh/id_ed25519.offsite
EOF

cat > "$TMP/darkmesh-status.json" <<'JSON'
{"verdict":"GO","tailscale_ok":true,"pf":{"pf_kill_active":false}}
JSON

cat > "$HOME_DIR/bin/networksetup" <<'SH'
#!/bin/bash
exit 0
SH

cat > "$HOME_DIR/bin/ssh-add" <<'SH'
#!/bin/bash
printf '%s\n' "$*" >> "${SSH_ADD_CALLS:?}"
exit 0
SH

cat > "$HOME_DIR/bin/choam" <<'SH'
#!/bin/bash
printf '%s\n' "$*" >> "${CHOAM_CALLS:?}"
exit "${CHOAM_EXIT_CODE:-0}"
SH
chmod +x "$HOME_DIR/bin/"*

/usr/bin/sqlite3 "$CHOAM_HOME/transfer_queue.db" <<'SQL'
CREATE TABLE transfer_queue (
  id TEXT PRIMARY KEY,
  status TEXT NOT NULL,
  retry_count INTEGER NOT NULL DEFAULT 0,
  next_retry_at TEXT
);
SQL

run_guard() {
  HOME="$HOME_DIR" \
    CHOAM="$HOME_DIR/bin/choam" \
    CHOAM_HOME="$CHOAM_HOME" \
    DARKMESH_STATUS="$TMP/darkmesh-status.json" \
    HOTSPOT_PATTERNS_FILE="$TMP/hotspots.txt" \
    CHOAM_AUTODRAIN_INTERVAL=0 \
    NETSETUP="$HOME_DIR/bin/networksetup" \
    SSH_ADD="$HOME_DIR/bin/ssh-add" \
    SSH_ADD_CALLS="$TMP/ssh-add-calls" \
    CHOAM_CALLS="$TMP/choam-calls" \
    "$HERE/choam-autodrain"
}

run_guard
/usr/bin/python3 - "$HOME_DIR/.local/state/choam-autodrain/status.json" <<'PY'
import json, sys
data = json.load(open(sys.argv[1]))
assert data["schema"] == 1
assert data["state"] == "idle"
assert data["detail"] == "queue empty"
PY
[[ ! -e "$TMP/choam-calls" ]] || { echo "FAIL: empty queue launched CHOAM" >&2; exit 1; }

/usr/bin/sqlite3 "$CHOAM_HOME/transfer_queue.db" \
  "INSERT INTO transfer_queue(id,status,retry_count) VALUES('pending','PENDING',0);"
run_guard
grep -qx 'queue --run' "$TMP/choam-calls"
grep -Fqx -- "--apple-use-keychain $HOME_DIR/.ssh/id_ed25519" "$TMP/ssh-add-calls"
grep -Fqx -- "--apple-use-keychain $HOME_DIR/.ssh/id_ed25519.offsite" "$TMP/ssh-add-calls"
/usr/bin/python3 - "$HOME_DIR/.local/state/choam-autodrain/status.json" <<'PY'
import json, sys
data = json.load(open(sys.argv[1]))
assert data["state"] == "idle"
assert data["detail"] == "drain completed"
PY

set +e
HOME="$HOME_DIR" \
  CHOAM="$HOME_DIR/bin/choam" \
  CHOAM_HOME="$CHOAM_HOME" \
  DARKMESH_STATUS="$TMP/darkmesh-status.json" \
  HOTSPOT_PATTERNS_FILE="$TMP/hotspots.txt" \
  CHOAM_AUTODRAIN_INTERVAL=0 \
  NETSETUP="$HOME_DIR/bin/networksetup" \
  SSH_ADD="$HOME_DIR/bin/ssh-add" \
  SSH_ADD_CALLS="$TMP/ssh-add-calls" \
  CHOAM_CALLS="$TMP/choam-calls" \
  CHOAM_EXIT_CODE=7 \
  "$HERE/choam-autodrain"
rc=$?
set -e
[[ "$rc" == 7 ]] || { echo "FAIL: drain failure was not propagated" >&2; exit 1; }
/usr/bin/python3 - "$HOME_DIR/.local/state/choam-autodrain/status.json" <<'PY'
import json, sys
data = json.load(open(sys.argv[1]))
assert data["state"] == "blocked"
assert data["detail"] == "drain exited rc=7"
PY

echo "PASS: autodrain gates, success, and failure status"
