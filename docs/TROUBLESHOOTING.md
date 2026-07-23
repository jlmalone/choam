# CHOAM Troubleshooting

## Build Issues

- **Gradle cannot find a JDK** — Ensure JDK 21+ is available. Set `JAVA_HOME` to Corretto 21 (`~/Library/Java/JavaVirtualMachines/corretto-21.0.7/Contents/Home`) or let Gradle toolchains download one.
- **Sietch composite build fails** — Ensure `../sietch` exists relative to CHOAM root. The `settings.gradle.kts` uses `includeBuild("../Sietch")`.

## Configuration

- **Configuration not found** — Verify `~/.choam/config.json` exists. Run `choam init` to generate a template, or copy from `config.json.example`.
- **Machine not detected** — `choam status` matches your system hostname to `MachineProfile.hostname` in config. Run `hostname` to check what your machine reports and ensure it matches.

## Catalog & Search

- **No search results** — Run `choam catalog-sync` first to download remote registries, then `choam rebuild-index` to build the FTS5 index.
- **Stale data in search** — Run `choam catalog-sync` to refresh from remote machines. Data older than 30 days shows `[stale]` markers.
- **Large registries scanning slowly** — `catalog-all` does a full CID+SHA-256 hash of every file (50h for 1.28M files). Use `choam catalog-update --drive <label>` for incremental updates.
- **Search returns macOS junk** — Run `choam catalog-purge` to remove `._*`, `.DS_Store`, `.Spotlight-V100` etc. from the registry, then `choam rebuild-index`.

## Network & Sync

- **Machine shows unreachable** — Check Tailscale is running (`tailscale status`). Verify the IP in config matches `tailscale ip`.
- **SSH permission denied** — Check that `sshUser` in your config matches the actual SSH username on the remote machine.
- **Sync is slow** — Check `choam status` for bandwidth estimates. Use `--dry-run` first to preview transfer size.

## Drives

- **Drive shows NOT MOUNTED** — Drives are identified by UUID. Run `choam drives scan` to verify UUIDs match. Check `/Volumes/` for the mount point.
- **Wrong storage class displayed** — Add `"storageClass": "HOT"` (or WARM/COLD) to the drive entry in `~/.choam/config.json`. Default is WARM.
