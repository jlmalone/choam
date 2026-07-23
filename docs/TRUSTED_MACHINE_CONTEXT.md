# Trusted-machine context

The public repository contains everything required to build and test CHOAM. Private
operator context and runtime configuration remain outside Git.

## Context classes

- `AI.local.md` holds private topology, integration history, and agent guidance shared
  across trusted operator machines.
- `ROADMAP.local.md` holds private motivations and workload-specific plans removed from
  the public roadmap.
- `~/.choam/config.json` and `~/.config/choam/autodrain-ssh-keys` are per-host runtime
  configuration. Review and merge them for each destination rather than copying blindly.
- SSH keys, tokens, passwords, Keychain values, databases, catalogs, and transfer history
  are secrets or operational data, not agent context.

## Propagation

Use an authenticated encrypted channel such as Tailscale SSH. Keep overlays mode `0600`,
run both local and remote transfer processes at low priority, and compare before replacing
destination context.

```bash
chmod 600 AI.local.md ROADMAP.local.md
nice -n 19 rsync -a --chmod=F600 --rsync-path='nice -n 19 rsync' \
  AI.local.md ROADMAP.local.md \
  "$TRUSTED_HOST:$CHOAM_CHECKOUT/"
```

For runtime configuration, copy to a temporary destination name, review the machine names,
paths, and identities on that host, then install it deliberately. Never propagate SSH
private keys or Keychain contents through this mechanism.
