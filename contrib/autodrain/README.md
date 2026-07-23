# choam-autodrain

Automatically drains the CHOAM transfer queue (`choam queue --run`), but only
when the machine is in a known-safe state, so queued transfers keep moving
without a human running the drain by hand.

The queue does not drain on its own (the choam daemon is intentionally off; you
drain on demand). The most common way it silently stalls is the ssh-agent
dropping its identities: choam's SSH to the destination then fails auth, the
queue marks the host "unreachable" and defers everything. This guard fixes that
class of stall on a schedule.

## Gates (all must hold before it drains)

1. **Not already draining.** Reads choam's own run-lock (`~/.choam/queue-run.lock`,
   holds the live PID). Cheap, no JVM.
2. **Machine green.** `/tmp/darkmesh-status.json` reports `verdict == "GO"`,
   `tailscale_ok == true` (choam's destination is reachable), and
   `pf_kill_active == false` (network is "safe", i.e. not VPN-down / hotspot).
3. **Not on a hotspot.** The current Wi-Fi SSID is not in
   `~/.config/vpn-guard/hotspot-ssids.txt` (the same list vpn-guard uses). This
   is what stops the drain from burning cellular data on a phone hotspot.
   choam runs over Tailscale/SSH, which the VPN kill-rules do NOT block, so
   without this gate it would happily transfer over a hotspot.
4. **There is eligible work.** The queue has a `PENDING`/`FAILED` entry whose
   retry budget is not exhausted and whose backoff has elapsed, with nothing
   `RUNNING` (WAL-aware, query-only SQLite on `~/.choam/transfer_queue.db`; no JVM).

When all gates pass it re-loads the SSH key from the Keychain
(`ssh-add --apple-use-keychain`), then runs `choam queue --run` in the foreground.
The signed infrastructure agent therefore owns the drain's full lifetime and can
terminate it cleanly during shutdown; no detached queue JVM is left behind.

## Exactly one scheduler

The guard is idempotent, exits 0 after normal evaluations, and self-throttles to
`CHOAM_AUTODRAIN_INTERVAL` seconds (default 90) via a shared stamp
(`~/.local/state/choam-autodrain/last-eval`). It is nevertheless intended to
have exactly one caller: Server Monitor's signed infrastructure agent. The
self-throttle and CHOAM's stable kernel-backed run-lock are defense in depth for
manual/concurrent invocations, not an invitation to register duplicate schedulers.

## Install / uninstall

```sh
./install.sh      # adds the job to Server Monitor's signed supervisor
./uninstall.sh    # removes that job and the helper
```

If the signed app is missing or awaiting Login Items approval, the default
installer stops without changing the supervisor configuration. Approve the
signed agent and rerun it. `./install.sh --legacy-agent` remains an explicit
compatibility escape hatch, but normal installations keep exactly one
infrastructure owner.

Server Monitor is a read-only consumer of the atomically written
`~/.choam/queue-status.json`; its Protection panel must not invoke this actuator.

## Observe

- `~/Library/Logs/choam-autodrain/choam-autodrain.log` : gate decisions
- `~/Library/Logs/choam-autodrain/drain.log` : each supervised `choam queue --run`
- `~/.local/state/choam-autodrain/status.json` : last verdict (armed/state/detail)

Run it once by hand to see what it decides:

```sh
CHOAM_AUTODRAIN_INTERVAL=0 ~/.local/bin/choam-autodrain; cat ~/.local/state/choam-autodrain/status.json
```

## Tunables (env)

| Var | Default | Meaning |
|-----|---------|---------|
| `CHOAM_AUTODRAIN_INTERVAL` | `90` | Min seconds between evaluations |
| `CHOAM_AUTODRAIN_REQUIRE_GO` | `1` | Require darkmesh `verdict=GO` (set `0` to gate on hotspot + queue only) |
| `CHOAM_MAX_RETRIES` | `5` | Maximum retry count eligible for automatic draining |
| `CHOAM` | `/opt/homebrew/bin/choam` | choam binary |
| `DARKMESH_STATUS` | `/tmp/darkmesh-status.json` | darkmesh status file |
| `HOTSPOT_PATTERNS_FILE` | `~/.config/vpn-guard/hotspot-ssids.txt` | Hotspot SSID substrings |
| `CHOAM_AUTODRAIN_SSH_KEY_FILE` | `~/.config/choam/autodrain-ssh-keys` | Optional newline-delimited additional SSH key paths |

The default key `~/.ssh/id_ed25519` is always considered. Put additional
machine-specific identities in the untracked key file, one path per line. Blank
lines and comments beginning with `#` are ignored.

## Caveat: hotspot detection is SSID-name based

Like vpn-guard, the hotspot check matches the Wi-Fi SSID against a substring
list; it does not otherwise detect a metered/cellular link. Keep the list
current. Also note macOS only returns the SSID to a background process that has
Location permission; if the SSID reads empty, the `pf_kill_active` gate (which
vpn-guard sets when it detects a hotspot) is the backstop.
