# CHOAM Architecture

CHOAM is organized into the following core modules:

- `config` — Configuration models and loader for `~/.choam/config.json`. Includes `Drive` (with `StorageClass`), `MachineProfile` (with aliases), `RepositoryConfig` (with `ReplicationPolicy`), and `SyncRules`.
- `drive` — Portable drive detection via `diskutil` on macOS. Drives are identified by UUID and resolved to mount points at runtime. Storage class propagated from config.
- `sync` — Sync session models, core sync engine, database sync, conflict resolution, rsync transfer engine, and Sietch-powered cataloging.
- `network` — Network route detection (LAN/Tailscale/WAN), real ping-based connectivity testing with bandwidth estimation, transfer manager (delegates to rsync), and progress monitoring.
- `catalog` — CatalogIndex (FTS5 search), CatalogDiffer (cross-machine CID diff), CatalogMerger (registry merge with conflict strategies), and search filters.
- `cli` — Clikt-based CLI with 40 commands: sync/push/pull, send/queue, status/history/config, catalog operations, search, inspect, diff, move, verify, plan, request-copy/fulfill, junk/purge, drives, index, lock, daemon, db-sync, register, federation, report, serve.

## Key Design Decisions

1. **Drives identified by UUID** — Mount paths change; UUIDs don't. A drive plugged into Mac Mini A today works on Mac Mini B tomorrow without config changes.
2. **rsync as transport, CHOAM as orchestrator** — rsync moves bytes efficiently (delta transfers, resume, checksums). CHOAM decides what/where/when and handles bidirectional conflict resolution.
3. **Sietch for cataloging** — Sietch core (composite build) provides `walkTree()`, `computeHash()`, CID generation, `.sietchignore` support, and catalog parsing.
4. **Drive-aware cataloging** — Sietch catalogs stored on drives travel with the drive, enabling offline comparison without re-scanning.
5. **Composite build over published JAR** — `includeBuild("../Sietch")` in settings.gradle.kts. Simpler for single-developer workflow.
6. **SQLite unified registry** — Cross-machine file discovery without a centralized server. Location map only — "where is this CID?"
7. **Two-phase deletion** — `junk mark` (reversible) then `junk purge` (irreversible). No silent deletions. No cascading deletions.
8. **Replication policies** — Per-repo `minCopies`/`preferredCopies` compared against actual registry data via `choam plan`.
9. **Storage tiering** — HOT/WARM/COLD classification per drive for future smart placement.
10. **Delta sync with watermarks** — After first full merge, subsequent `catalog-sync` runs only process rows newer than the stored watermark.
