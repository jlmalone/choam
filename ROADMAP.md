# CHOAM Roadmap: From Sync Tool to Personal Data Infrastructure

> "He who controls the data controls the universe."

**CHOAM** (Cross-Host Orchestrated Asset Management) manages terabytes of media, databases, and archives across multiple machines connected via Tailscale and SSH. This roadmap traces the path from a working sync tool to a federated personal cloud — without overengineering the present.

**Safety philosophy:** CHOAM wields enormous power over user data. Every destructive operation follows: least surprise, maximum reversibility, explicit confirmation, two-phase execution (mark then act), and full audit logging. No silent deletions. No cascading deletions. The fate of every file is explicitly chosen.

**Core data principle:** The registry is a **LOCATION MAP**. It answers "where is this CID?" — nothing more. A file disappearing from one machine means that machine no longer has it. Other copies are never affected. There is no automatic cross-machine deletion. Ever.

For the long-term unified protocol vision (multi-tier trust, censorship resistance, capability URLs), see `VISION_DOC.md`.

---

## Phase 1: Make CHOAM the Default Reach — COMPLETED (2026-02-22)

Made CHOAM faster to use than raw rsync. All items shipped. 196 tests passing.

| Item | What | Status |
|------|------|--------|
| 1.1 | `choam push` / `choam pull` commands | Done |
| 1.2 | Live progress TUI (rsync progress parsing, ANSI) | Done |
| 1.3 | `choam status` with drives, repos, machines, reachability | Done |
| 1.4 | Sync history persistence (`~/.choam/sync_history.jsonl`) | Done |
| 1.5 | `choam history` command | Done |
| 1.6 | `choam catalog-all` — full CID+SHA-256 cataloging via Kubo IPFS | Done |
| 1.7 | `choam index` — FTS5 search, ingest, duplicates, at-risk | Done |
| 1.8 | `choam drives scan` — UUID-based portable drive detection | Done |

**Proof of scale:** `catalog-all` ran 50 hours on the remote server, cataloging 1.28M files / 2.3TB on an external drive with IPFS CID hashing. A second machine has 1.15M files cataloged with CIDs.

---

## Phase 2: Catalog Intelligence — COMPLETED (2026-03-03)

Cross-machine catalog visibility, search, and content-aware operations. All 11 sub-phases shipped. 385 tests passing at phase completion.

### 2.1 Catalog Sync Across Machines — DONE (2026-03-03)

```
choam catalog-sync              # sync from all reachable machines
choam catalog-sync --from server  # sync from one machine
choam search "isaac arthur"     # search across everything
choam search "*.mkv" --machine server --limit 100
choam rebuild-index             # rebuild FTS5 from unified registry (no network)
```

**What shipped (2.1a — core sync):**
- `CatalogSyncCommand` — WAL checkpoint on remote, rsync with `--partial --compress` (resumable, gzip on remote → rsync .gz → decompress locally, ~600MB → ~150MB), merge into `~/.choam/unified_registry.db` via `INSERT OR REPLACE`
- `GlobalSearchCommand` — top-level `choam search` with `--machine` filter and staleness markers (>30 days = `[stale]`)
- `CatalogIndex.rebuildFromRegistry()` — builds FTS5 index from unified registry, derives drive labels from `/Volumes/<X>/` paths or config, deduplicates same-path-different-CID entries, filters macOS metadata
- `StatusCommand` catalog section — per-machine file counts, last sync timestamps, shared staleness threshold with search
- `RebuildIndexCommand` — offline FTS5 rebuild from unified registry
- `last_synced_at` staleness tracking on every row
- 19 new tests (merge idempotency, cross-machine preservation, FTS rebuild, staleness, drive label derivation, dedup, exclude filtering)

**What shipped (2.1b — machine name remap + delta sync):**
- **Machine name aliases** — `MachineProfile.aliases` field in config. On sync: backfill UPDATE canonicalizes existing old-hostname rows, new inserts remap via `machineNameMap`. Applied in `mergeRegistry()`, `rebuildFromRegistry()`, and `RebuildIndexCommand`.
- **Delta sync** — `sinceTimestamp` parameter on `mergeRegistry()` filters with `WHERE registered_at >= ?` (boundary-safe `>=`). Watermarks persisted in `sync_metadata` table in unified DB. First sync = full, subsequent syncs = delta from watermark.
- **`._*` resource fork filtering** — macOS AppleDouble files on exFAT/NTFS drives filtered during index rebuild (628K junk files eliminated from ext-drive)
- **Staleness consistency** — `StatusCommand` catalog section uses `GlobalSearchCommand.isStale()` (shared 30-day threshold)
- 8 new tests (remap with/without map, backfill canonicalization, delta filtering, boundary-second inclusion, watermark round-trip, `._*` filtering, remap in FTS)

**Design decisions:**
- Full-DB-fetch, incremental-merge with delta sync. Each sync copies the entire remote registry (~50-100MB compressed) but only merges rows newer than the watermark. The merge is an idempotent upsert (`INSERT OR REPLACE` by primary key). Re-processing boundary-second rows is harmless.
- Watermarks sourced from remote's `MAX(registered_at)`, not local clock — eliminates clock skew.
- No automatic deletion propagation. Registry is a location map. If a machine removes a file, we don't propagate that. Rows just become stale (old `last_synced_at`).

### 2.1c Registry Cleanup — Purge Junk from Unified DB — DONE (2026-03-03)

**Problem:** The unified registry contains 628K junk rows (49% of 1.28M total) — `._*` resource forks, `.DS_Store`, `.Spotlight-V100`, `.fseventsd`, `.Trashes`, `*.tmp`, `*.part`. These were cataloged before exclude patterns were added. The `rebuildFromRegistry()` filters them at search-index time, but they still bloat the 634MB unified DB and slow every merge.

**Approach:**
- `choam catalog-purge` CLI command — deletes rows from `content_locations` matching exclude patterns
- Uses the same patterns as `rebuildFromRegistry()` (Sietch defaults + CHOAM extras like `._*`)
- Shows count before deleting, requires no confirmation (idempotent, rows are just location records)
- Runs `VACUUM` after delete to reclaim disk space
- Also purges from remote registries if `--remote` flag specified (SSH + sqlite3)

**Expected impact:** 634MB → ~320MB unified DB. Future syncs/merges process ~650K rows instead of 1.28M.

### 2.2 Incremental Reindex — DONE (2026-03-03)

`CatalogUpdateCommand` (290 lines) — `choam catalog-update --drive <label>` scans for changed files using mtime comparison, hashes only new/modified files, falls back to SHA-256 if IPFS unavailable. State persisted in `~/.choam/catalog_state.json`. Supports `--dry-run` and `--ipfs` flags.

### 2.3 `choam move` — Verified Relocation — DONE (2026-03-03)

`MoveCommand` (434 lines) — 4-phase atomic relocation: Transfer → Verify CID → Delete Source → Update Registry. Source preserved on verification failure. SSH support for remote deletions. `--dry-run` flag. Machine alias resolution.

### 2.4 `choam verify` — Location Audit — DONE (2026-03-03)

`VerifyCommand` (320 lines) — checks registered files still exist via local `File.exists()` or SSH batch check. `--machine` filter, `--sample` for random sampling, `--verbose` to list missing files. Non-destructive — marks missing as stale, never deletes.

### 2.5 Real Network Detection — DONE (2026-03-03)

`NetworkDetector.testConnectivity()` now runs actual `ping -c 3` with RTT parsing. Estimates bandwidth from latency (<5ms → 100 MiB/s gigabit, <20ms → 50 MiB/s, <100ms → 10 MiB/s, etc). 5-minute caching per target. Returns `ConnectivityTest` with reachable, latency, bandwidth.

### 2.6 CatalogDiffer — DONE (2026-03-03)

`CatalogDiffer` (174 lines) — CID-based cross-machine diff. `diffMachines()` compares two machines, returns `CatalogDiff` with `onlyOnA`, `onlyOnB`, `onBoth` sets with file sizes. CLI via `DiffCommand` with `--min-size`, `--verbose`, `--limit`.

### 2.7 CatalogMerger — DONE (2026-03-03)

`CatalogMerger` (259 lines) — merges source registry into target with 3 conflict strategies: `NEWER_WINS`, `KEEP_EXISTING`, `INCOMING_WINS`. Returns `MergeResult` with insert/update/skip counts + conflict audit trail. Batch commits every 100K rows. CLI via `CatalogMergeCommand`.

### 2.8 Advanced CatalogSearcher — DONE (2026-03-03)

`GlobalSearchCommand` + `CatalogIndex.advancedSearch()` — all filter types shipped: `--min-size`/`--max-size`, `--after`/`--before`, `--ext mkv,mp4`, `--cid QmXXX`, `--path "*/tv/*"`. Filters combine with FTS5 text query. CID exact lookup is a fast path (no FTS needed).

### 2.9 `.sietchignore` — Gitignore-Style Exclude System — DONE (2026-03-03)

**Problem:** Exclude patterns are currently programmatic only — hardcoded in `DEFAULT_EXCLUDE_PATTERNS`, per-repo in CHOAM config, and CHOAM-specific extras in `rebuildFromRegistry()`. There's no way to drop a file on a drive and say "ignore this subtree." The ext-drive exFAT drive had 628K `._*` resource forks and 326 `.Spotlight-V100` entries cataloged before patterns were added. Users need a familiar, file-based way to control what gets indexed.

**Design: Three-tier ignore hierarchy** (identical to git's model):

| Tier | File | Scope | Analogous to |
|------|------|-------|-------------|
| **Global** | `~/.sietch/ignore` | All Sietch operations everywhere | `~/.config/git/ignore` |
| **Per-directory** | `.sietchignore` in any dir | That directory + all subdirectories | `.gitignore` in any dir |
| **Programmatic** | `excludePatterns` parameter / CHOAM config | API callers and CHOAM config.json | `.git/info/exclude` |

**Syntax:** Identical to `.gitignore`:
- `*.tmp` — glob match on filename
- `build/` — ignore directory and all contents
- `!important.tmp` — negation (re-include a previously excluded pattern)
- `**/logs` — match `logs` directory at any depth
- `/root-only.txt` — anchored to the `.sietchignore`'s directory (not recursive)
- `#` comments, blank lines ignored

**Implementation (Sietch library — `sietch-core`):**

1. **`SietchIgnore` parser class** — reads a `.sietchignore` or `~/.sietch/ignore` file, compiles patterns into matchers. Handles negation (`!`), anchoring (`/`), directory markers (`dir/`), and `**` recursive globs. Returns `shouldIgnore(relativePath): Boolean`.

2. **`SietchIgnoreChain`** — hierarchical chain of `SietchIgnore` instances. Global → parent dirs → current dir. Later rules override earlier ones. Negation in a child `.sietchignore` can re-include something excluded by a parent.

3. **`walkTree()` integration** — during `preVisitDirectory()`, check for `.sietchignore` file in the directory being entered. If present, push it onto the ignore chain. On `postVisitDirectory()`, pop it. File/directory exclusion checks go through the chain instead of the flat matcher list.

4. **Sietch CLI `--no-sietchignore` flag** — opt-out for when you want to index everything (e.g., forensic scan of a drive).

5. **CHOAM integration** — `CatalogAllCommand` and `rebuildFromRegistry()` use the chain. Existing `excludePatterns` from config merge as the programmatic tier. The three tiers compose naturally.

**What `.sietchignore` at `/Volumes/ext-drive/.sietchignore` would look like:**
```gitignore
# macOS resource forks (AppleDouble on exFAT/NTFS)
._*

# macOS system directories
.Spotlight-V100/
.fseventsd/
.Trashes/
.TemporaryItems/
.DocumentRevisions-V100/

# Common junk
.DS_Store
Thumbs.db
*.tmp
*.part

# Torrent temp files
*.!qB
```

**Migration path:** The current `DEFAULT_EXCLUDE_PATTERNS` constant becomes the seed content for `~/.sietch/ignore` on first run. Existing programmatic patterns continue to work unchanged. The `.sietchignore` system is purely additive.

---

## Phase 3: Tiered Storage + Content Lifecycle — COMPLETED (2026-03-03)

Drive-aware storage management with explicit content lifecycle. 4 new CLI commands, 3 new DB tables, 45 tests. 430 total tests passing at phase completion.

### 3.1 Drive Classification — DONE

`StorageClass` enum (HOT/WARM/COLD) added to `Drive` and `MountedDrive`. Default: WARM. Displayed in `choam status` and `choam drives`. Serializes to/from config.json. Backward compatible — old configs without `storageClass` default to WARM.

| Class | Description | Example |
|-------|-------------|---------|
| `HOT` | Always-on, fast access (NAS, internal SSD) | Server internal SSD |
| `WARM` | Attached JBOD, swapped periodically | ext-drive (4TB exFAT) |
| `COLD` | Disconnected archive, manual access | cold-6tb (NTFS, needs reformat) |

### 3.2 Replication Policies — DONE

`ReplicationPolicy` data class on `RepositoryConfig`: `minCopies` (default 1), `preferredCopies` (default 2), `geoDistribute`, `preferredClass`. Backward compatible — old configs get sensible defaults.

### 3.3 `choam plan` — Gap Analysis — DONE

`PlanCommand` — queries unified registry + config to show replication status per repository vs policy. Three states: under-replicated (red), meets-minimum-below-preferred (yellow), meets-preferred (green). Recommends target machines with free space. `--verbose` shows single-copy CIDs by size.

```
$ choam plan
film:   1 copy (server/ext-drive) — needs 1 more copy
        Recommendation: sync to laptop (800GB free)
tv:     1 copy (server/ext-drive) — needs 1 more copy
backup: 2 copies (server + local) — meets minimum
```

### 3.4 `choam request-copy` / `choam fulfill` — DONE

Queued migrations via `copy_requests` table in unified_registry.db.

- `choam request-copy <repo> --to <machine>` — validates config, detects duplicate pending requests, inserts pending entry
- `choam fulfill [--dry-run] [--list]` — scans pending requests, checks reachability, runs push for reachable targets, marks completed with timestamp. Failed requests reset to pending.

Status lifecycle: pending → in_progress → completed (or cancelled).

### 3.5 Junk Classification — The ONLY Path to Deletion — DONE

Two-phase deletion via `junk_list` table in unified_registry.db. Purge history logged to `~/.choam/purge_history.jsonl`.

- `choam junk mark <CID> [--reason "..."]` — shows all locations, requires typing JUNK to confirm. Prevents duplicates.
- `choam junk list` — shows all marked content with timestamps and reasons
- `choam junk unjunk <CID>` — removes mark, content is safe again
- `choam junk purge` — IRREVERSIBLE. Shows all items, requires typing PURGE. Deletes files from all reachable machines (local File.delete + SSH rm), removes from content_locations, removes from junk_list, logs to purge_history.jsonl.

Grace period between mark and purge. No shortcuts. No silent deletions.

---

## Phase 4: Smart Management — COMPLETED (2026-03-03)

Intelligence layer with health reporting, content classification, dedup-aware counting, and geographic scoring. 1 new CLI command (`choam report`), 13 new tests. 443 total tests passing at phase completion.

### 4.1 Content-Class Profiling — DONE

`ReportCommand` classifies every CID by file extension into 5 categories: Media (video/audio/image), Document (pdf/doc/xls), Archive (zip/tar/dmg), Code (kt/py/js/swift), Other. Shows CID count and total size per class.

### 4.2 Dedup-Aware Replication — DONE

Same CID = same content = counts as a copy regardless of filename or path. `choam report` dedup section distinguishes:
- Same-machine multi-path duplicates (renamed/copied files — wasteful)
- Cross-machine replication (healthy — means backup is working)

### 4.3 Geographic Diversification Scoring — DONE

`choam report` geo diversity section counts CIDs by machine spread (1/2/3+ machines) and computes average spread score. Higher spread = better geographic safety.

### 4.4 `choam report` — Health Dashboard — DONE

`ReportCommand` — comprehensive health report with 8 sections:
1. **Coverage** — total files, unique CIDs, total size, machine count, multi-copy percentage
2. **Replication** — per-repo copy count vs policy (green/yellow/red status icons)
3. **Risk** — single-copy files over 100MB (count + total size, --verbose shows top 10)
4. **Staleness** — per-machine last-sync age with color coding (green <7d, yellow <30d, red >30d)
5. **Geographic Diversity** — CID distribution across 1/2/3+ machines, average spread
6. **Content Classes** — media/document/archive/code/other breakdown by CID count and size
7. **Deduplication** — same-machine multi-path dupes vs cross-machine healthy replication
8. **Recommendations** — under-replicated repos, stale machines, at-risk data, pending requests, junk awaiting purge

---

## Phase 5: Web Dashboard + Media Browsing — COMPLETED (2026-03-03)

Browser-based UI for monitoring and content exploration. 1 new CLI command (`choam serve`), 4 web pages, HTMX partial endpoints. 465 total tests passing.

### 5.1 Ktor + HTMX Dashboard — DONE

`choam serve [--port 8742]` — Ktor CIO server with kotlinx-html DSL + HTMX.

**Pages shipped:**
- **Dashboard** (`/`) — stats cards (total files, unique CIDs, backup %, at-risk), machine reachability (HTMX auto-refresh 30s), catalog staleness, replication status table
- **Search** (`/search`) — FTS5 search with extension/machine filters, HTMX partial results, grouped by machine/drive
- **Drives** (`/drives`) — drive status with storage class badges (HOT/WARM/COLD), mount status, free/total space, repository mappings
- **History** (`/history`) — sync history table (time, source, target, repos, files, size, status)

**HTMX partials:** `/htmx/machines`, `/htmx/catalog-stats`, `/htmx/search-results`, `/htmx/drive-status`
**API:** `/api/health` returns JSON status

**Design:** Matrix-inspired dark theme (green `#00CC66` on `#0A0A0A`), monospace font, no JS build step. HTMX loaded from CDN.

### 5.2 Jellyfin Library Integration

Generate Jellyfin library configs pointing at available content across machines. Content explorer shows online/offline status per drive. Direct integration with CHOAM's location registry — Jellyfin reads from wherever the content currently lives.

*Deferred — waiting for real-world dashboard usage feedback before adding media-specific features.*

---

## Phase 6: Federation — CHOAM Houses — COMPLETED (2026-03-03)

> From `VISION_DOC.md`: "Pragmatic, trust-aware, resource-conscious data layer that respects how humans actually share information."

3 new CLI command groups (house/share/backup with 13 subcommands), federation persistence layer, 39 tests. 504 total tests passing.

### 6.1 House Identity — DONE

`choam house init --name <name>` generates EC keypair, derives House ID from public key SHA-256 fingerprint, saves to config.json. Private key stored at `~/.choam/house_key` (owner-only permissions).

`choam house status` shows identity + federation stats. `choam house peers` lists known peers. `choam house add-peer <name> --id <id> --ip <ip>` registers a trusted remote House.

### 6.2 Repository Sharing — DONE

```bash
choam share grant film --with <peer-id> --access read
choam share revoke film --from <peer-id>
choam share list
```

Three access levels from VISION_DOC trust tiers:
- **STORE** — Encrypted blob storage. Peer cannot read content.
- **READ** — Peer can pull content. Has view key.
- **WRITE** — Peer can push changes. Full collaboration.

Upsert semantics (re-granting upgrades access level). Revocation is soft (timestamp-based, auditable). All operations logged to `federation_log` table.

### 6.3 Mutual Backup — DONE

```bash
choam backup offer --to <peer-id> --size 2TB
choam backup accept --from <peer-id> --their-size 1TB
choam backup list
choam backup suspend <peer-id>
choam backup terminate <peer-id>
```

5-state lifecycle: PROPOSED → ACCEPTED → ACTIVE → SUSPENDED/TERMINATED. Human-readable size parsing (2TB, 500GB, 100MB). Terminate requires typing TERMINATE.

### 6.4 Federation Persistence — DONE

`FederationStore` — SQLite at `~/.choam/federation.db`:
- `share_grants` — per-repo ACLs with soft revocation
- `backup_agreements` — mutual storage offers with status tracking
- `federation_log` — audit trail for all federation operations

### 6.5 Identity

EC keypair (JDK standard crypto). House ID = SHA-256 fingerprint of public key (first 16 bytes hex). Future: Ed25519 via EdDSA provider, blockchain identity bridge for cross-network trust.

### 6.6 Discovery

Explicit trust only — no auto-discovery. Peers added manually via `house add-peer`. Share grants are explicitly created. No global directory.

---

## Phase 7: Advanced — COMPLETED (2026-03-03)

Infrastructure for large-scale federation: mobile device support, gossip coordination, content streaming, and bandwidth reciprocity. 1 new CLI command group (gossip with 4 subcommands), 3 new infrastructure modules, 23 tests. 558 total tests passing.

### 7.1 Mobile Nodes — DONE

`NodeCapability` model with 7 device types (PHONE/TABLET/LAPTOP/DESKTOP/SERVER/NAS/CLOUD), 5 sync schedules (ON_DEMAND/ON_WIFI/ON_POWER/CONTINUOUS/SCHEDULED). `MobileProfile` for phone/tablet constraints: cache limit, upload-only/download-only repos, LRU eviction, Wi-Fi/charging requirements.

### 7.2 Gossip Protocol — DONE

`GossipProtocol` — SQLite at `~/.choam/gossip.db`. Nodes create announcements with inventory (CID count, total size), capabilities, replication needs, and shared repos. Announcements stored and exchanged via peer sync.

`choam gossip announce` — create announcement from local registry stats.
`choam gossip peers` — show latest announcement from each peer.
`choam gossip prune` — clean old announcements (keep latest N per house).

### 7.3 Content Streaming Adapter — DONE

`StreamingAdapter` in web module — HTTP range request support for Jellyfin-compatible streaming:
- `GET /stream/{cid}` — serve content by CID with range headers (Ktor `respondFile`)
- `GET /stream/info/{cid}` — content metadata (size, type, locations, stream URL)
- Content-type detection for 25+ media formats (video/audio/image/document)
- Local file resolution from unified registry; future: SSH/IPFS proxy

### 7.4 Bandwidth Economy — DONE

`BandwidthEconomy` — transfer ledger tracking uploads/downloads per peer. Balance = bytes_uploaded - bytes_downloaded. Four priority tiers:
- HIGH — net contributor >1GB (prioritize their requests)
- NORMAL — balanced or new peer
- LOW — net consumer <1GB deficit
- THROTTLED — heavy consumer >1GB deficit

`choam gossip economy` — show all peer balances sorted by contribution.

---

## Phase 8: Web + CLI Full Parity — COMPLETED (2026-03-04)

Web dashboard expanded from 4 pages to 8 pages with full parity to CLI features. CLI enhanced with `inspect` and `federation` commands. Full CID display everywhere.

### 8.1 CLI — Full CID Display — DONE

`GlobalSearchCommand` now shows full CID on its own line + IPFS gateway URL in search results. No more truncated CIDs.

### 8.2 CLI — `choam inspect` — DONE

Deep drill-down by CID or filename FTS search. Shows:
- Filename, size, content type
- Full CID + IPFS gateway URL
- All copies: machine, drive label, full path, verified date
- Replication status vs policy (which repo, copies vs preferred)
- Transfer estimates to missing machines (bandwidth + ETA)

Supports both exact CID lookup and filename search (FTS → top match → drill-down).

### 8.3 CLI — `choam federation` — DONE

Single combined view replacing `house status` + `share list` + `backup list`. Shows House identity, all peers with IPs/SSH users, active shares with access levels, and backup agreements with capacity usage.

### 8.4 CLI — Enhanced `choam report` — DONE

Two new sections added:
- **Copy Distribution** — CIDs by copy count (1/2/3+) + top 5 largest single-copy files with full CIDs
- **Transfer Speeds** — per-machine estimated bandwidth from NetworkDetector

### 8.5 CLI — Enhanced `choam gossip economy` — DONE

Added estimated bandwidth column to economy display.

### 8.6 Web — 4 New Pages — DONE

| Page | Route | Content |
|------|-------|---------|
| Inspect | `/inspect/{cid}` | File info, content address, copies table, replication status, transfer estimates |
| Federation | `/federation` | House card, peers table, shares with access badges, backup agreements with status badges |
| Report | `/report` | Full 10-section health dashboard (web layout of all ReportCommand sections) |
| Network | `/network` | Gossip announcements table, bandwidth economy with balance/priority badges |
| Media | `/media` | Search + browse with content-type icons, full CIDs, IPFS links, stream buttons |

### 8.7 Web — Search + Dashboard Enhancements — DONE

- Search results show full CIDs as clickable links to inspect page
- IPFS gateway links on every CID
- Stream buttons for video/audio content
- Dashboard: federation summary card, quick actions card with links to all pages

### 8.8 Web — Navigation Update — DONE

Full nav: `Dashboard | Search | Media | Drives | Federation | Report | Network | History`

**Files changed:** 14 (5 new, 9 edited). Build passing, all tests green.

---

## Phase 9: Content Proxy — COMPLETED (2026-03-04)

Universal content resolution — `/stream/{cid}` now works for ALL content, not just local files.

### 9.1 ContentProxy — Tiered Resolution — DONE

`ContentProxy.kt` — given a CID, finds the fastest way to serve bytes:
- **Tier 0 (local):** query unified_registry.db → File.exists() → serve directly (instant)
- **Tier 1 (IPFS gateway):** construct `http://{machine_tailscale_ip}:{gatewayPort}/ipfs/{cid}` → HEAD check → streaming proxy via Ktor HttpClient
- **Tier 2 (diagnostic 404):** JSON with all known locations, reachability status, and suggestions

Streaming proxy forwards Range headers for seeking/resume. No in-memory buffering.

### 9.2 Updated /stream/{cid} — DONE

Previously returned 404 for remote content. Now proxies from IPFS gateway transparently. Media browser "Stream" buttons work for all content.

### 9.3 /resolve/{path...} — Universal Content Proxy — DONE

Cross-project integration endpoint. Accepts CID or machine/drive/path:
```
GET /resolve/bafkrei...abc
GET /resolve/server-a/ext-drive/movies/Aliens.mkv
```

### 9.4 /api/resolve/{cid} — JSON Metadata — DONE

Resolution metadata without streaming bytes. Returns tier, gateway URL, locations, content type.

### 9.5 CORS — DONE

Ktor CORS plugin installed. Browser apps on any port can access CHOAM content proxy.

### 9.6 Open Source Audit — DONE

Full PII scrub: all real Tailscale IPs, SSH usernames, machine names (server-b/server), drive names (backup-4tb) replaced with generic placeholders across 25+ files. Personal docs (ECOSYSTEM.md, CHOAM_USAGE_GUIDE.md, CONTENT_PROXY_PROPOSAL.md) gitignored. Progressive HTMX loading for all dashboard sections.

---

## Phase 9.8: Queue Architecture Overhaul — COMPLETED (2026-04-01)

Replaced the unlocked JSON queue with SQLite, fixed 3 data-safety bugs, unified duplicated queue processing code, improved rsync error handling. All existing tests pass + 29 new tests.

### 9.8.1 Safety-Critical Bug Fixes — DONE

Three bugs fixed in `DaemonScheduler.kt` that could cause data loss or silent misbehavior:
- **Pre-flight fails closed:** When SSH is unreachable for pre-flight check, daemon and queue now defer the entry (leave PENDING) instead of proceeding blind. Interactive `send` still warns and proceeds since user is present.
- **Backup failure checked:** Daemon now checks `backupRemoteFiles()` return value and refuses to overwrite if backup fails (was silently ignored).
- **MOVE with verification:** Daemon now verifies remote file size via SSH and deletes source, matching CLI behavior (was silently skipping deletion — "move verification deferred").

### 9.8.2 SQLite Queue — DONE

Replaced `~/.choam/transfer_queue.json` (unlocked file, load-modify-save) with `~/.choam/transfer_queue.db` (SQLite, WAL mode, busy_timeout=5000ms).

- **Atomic claim pattern:** `claimNext()` uses SELECT+UPDATE in a transaction — two processors cannot claim the same entry.
- **Deduplication:** Same source/dest/machine in PENDING or RUNNING state returns existing entry.
- **PID tracking:** Stores claimer PID; stale detection checks `ProcessHandle.of(pid).isPresent` + time-based fallback.
- **Cancel RUNNING:** `cancelRunning()` kills the process via PID and marks CANCELLED.
- **JSON migration:** On first open, reads `transfer_queue.json`, inserts all entries, renames to `.migrated`.
- 25 new tests in `TransferQueueStoreTest.kt`.

### 9.8.3 Rsync Error Handling — DONE

- **Stderr captured separately:** Removed `redirectErrorStream(true)`. Stderr read in background thread, included in failure messages. Operators now see actual rsync error text, not just "exit code N".
- **Exit code classification:** New `RsyncErrorClass` enum (TRANSIENT/PERMANENT/PARTIAL) and `classifyRsyncExit()`. Transient failures (12, 20, 30, 35, 255, 137) don't count against retry limit.
- **Dropped `--checksum` default:** Was forcing full-file hashing on both sides before transfer. Removed — rsync size+mtime is sufficient (pre-flight already does SHA-256).
- **Adaptive timeout:** Default changed from 120s to 300s. Callers can override via `timeoutSeconds` parameter.
- **`--partial-dir`:** Replaces bare `--partial` — partial files stored in `.rsync-partial/` instead of destination directory.

### 9.8.4 Unified Queue Processor — DONE

Extracted `QueueProcessor.kt` from duplicated code in `QueueCommand` (~200 lines) and `DaemonScheduler` (~180 lines). Single implementation with `ProcessingMode.INTERACTIVE` vs `UNATTENDED`.

Unified behaviors:
- Backup failure always fatal (from QueueCommand's correct behavior)
- MOVE always verify+delete (from QueueCommand's correct behavior)
- Transient-aware retry (from DaemonScheduler's smart retry)
- Pre-flight null: INTERACTIVE proceeds, UNATTENDED defers

### 9.8.5 Web Queue Processing — DONE

`POST /api/queue/process` no longer spawns `./gradlew run` subprocess. When daemon scheduler is available, triggers its queue task. Otherwise processes in-process on a background thread.

### 9.8.6 Windows-Style MOVE — DONE

When pre-flight finds all files IDENTICAL at destination and mode is MOVE: verifies full integrity (size + SHA-256 match for every file in the directory tree at full depth), then deletes source. If any file fails verification, MOVE aborted and source kept.

### 9.8.7 Known Gap: Directory Transfer Progress

Queue progress sidecar only tracks current file being rsynced. No aggregate progress (files done / total, bytes done / total). Operators cannot answer "how close is this directory transfer?" from CHOAM data alone. **Next priority.**

---

## UX Philosophy: Idiot-Proof by Design

> CHOAM must be usable by someone who doesn't know what a CID is.

### Principles

1. **Show, don't tell.** Never display a raw CID without a filename next to it. Never show a path without indicating which machine and drive it's on. Every piece of data has context.

2. **One command does the right thing.** `choam` with no arguments shows status + recommendations. `choam fix` executes all recommendations. The user doesn't need to know which sub-commands exist.

3. **Dangerous actions have friction.** Anything that deletes data requires typing a confirmation word. Anything that sends data to a peer shows exactly what will be sent and waits for confirmation. Auto-replication is opt-in per repo and has a human approval queue by default.

4. **The web UI is the primary interface.** CLI is for power users and automation. New users should be able to do everything from the browser. Every CLI command has a web equivalent.

5. **Progressive disclosure.** Dashboard shows 4 cards. Click one → see the detail. Click a file → see all copies. Click a copy → see transfer options. Each layer adds detail without overwhelming.

### CLI UX Improvements

**Zero-config quick start:**
```bash
choam                          # Shows: status, recommendations, "run choam fix to resolve"
choam fix                      # Executes all safe recommendations (sync stale catalogs, fulfill pending requests)
choam fix --approve            # Shows what it will do, asks for confirmation
```

**Guided setup wizard:**
```bash
choam setup                    # Interactive wizard:
                               #   1. What machines do you have? (hostname/IP prompts)
                               #   2. What do you want to sync? (browse local dirs)
                               #   3. How many copies? (1/2/3 with plain-English explanations)
                               #   4. Generate config.json automatically
```

**Plain-English output everywhere:**
```
$ choam status
Your data: 2.4M files (3.5 TB) across 3 machines

  Healthy: 1.2M files have 2+ copies ✓
  At risk: 800K files have only 1 copy ✗

  Recommendations:
    1. Sync catalog from server (12 days stale) — run: choam catalog-sync --from server
    2. Replicate 500GB of at-risk video to laptop — run: choam push film --to laptop

  Run "choam fix" to resolve all issues automatically.
```

### Web UI Design

**Dashboard redesign:**
- **Hero section:** Single sentence: "3.5 TB protected across 3 machines. 2 issues need attention."
- **Issue cards:** Red/yellow cards with one-click "Fix" buttons. "800K files at risk → [Replicate Now]"
- **Machine map:** Visual diagram showing machines with connection lines. Green = connected, gray = offline. Click a machine → see its drives, repos, free space.
- **Activity feed:** Live stream of what the daemon is doing (syncing, transferring, verifying)

**Replication wizard (web):**
When a user clicks "Replicate Now" or navigates to `/replicate`:
1. **What:** Show under-replicated repos with file counts and sizes
2. **Where:** Show available target machines with free space, checkboxes to select targets
3. **How much:** Slider for bandwidth limit ("Use up to 50% of your connection")
4. **When:** "Now", "Tonight (2-6 AM)", "Over the next week (background)"
5. **Review:** Summary of what will be transferred, estimated time, disk space impact
6. **Approve:** Big green button. Transfer starts. Progress bar on dashboard.

**Media browser redesign:**
- Thumbnail grid (not just text list) for images/video
- Lazy-loaded thumbnails via `/resolve/{cid}` with image resize parameter
- Playback in-browser for video/audio (HTML5 `<video>` tag pointing at `/stream/{cid}`)
- "Available on: [local] [server] [laptop]" badges on each file
- Download button with machine selection for offline access

**Federation onboarding:**
Instead of requiring CLI commands, the web UI walks through federation:
1. `/federation/setup` — "Create Your House" form (name, description)
2. `/federation/add-peer` — "Add a Friend" form (their House ID, IP)
3. `/federation/share` — "Share a Library" — checkboxes for repos + access level with plain-English descriptions:
   - ☐ Movies → "Your friend can stream your movies" (READ)
   - ☐ Backups → "Your friend stores an encrypted copy for disaster recovery" (STORE)
4. QR code display of your House ID for easy sharing

### Human-in-the-Loop Replication

**The default is NOT fully automatic.** Auto-replication requires explicit opt-in per repo AND uses an approval queue:

```json
{
  "repositories": {
    "film": {
      "replication": {
        "minCopies": 2,
        "preferredCopies": 3,
        "autoReplicate": false,        // DEFAULT: false — requires manual trigger
        "approvalRequired": true,       // DEFAULT: true — daemon queues, human approves
        "approvalMode": "web"           // "web" = approve from dashboard, "cli" = approve from terminal
      }
    }
  }
}
```

**Three replication modes:**

| Mode | Config | Behavior |
|------|--------|----------|
| **Manual** (default) | `autoReplicate: false` | User runs `choam push/pull` or clicks "Replicate" in web UI. CHOAM never moves data without being asked. |
| **Supervised** | `autoReplicate: true, approvalRequired: true` | Daemon detects gaps, queues proposed transfers, shows them in dashboard. User reviews and approves batch. Like a PR review for data. |
| **Autonomous** | `autoReplicate: true, approvalRequired: false` | Daemon handles everything within configured bandwidth/schedule limits. For power users who trust their policy config. |

**Approval queue (web UI at `/replicate/queue`):**

```
Pending Replication Requests:

  ☐ Push "film" to laptop (500 GB, ~2 hours)
    Reason: Only 1 copy, policy requires 2
    Source: server/ext-drive → laptop (Tailscale, ~50 MB/s)

  ☐ Push "tv" to laptop (200 GB, ~1 hour)
    Reason: Only 1 copy, policy requires 2
    Source: server/ext-drive → laptop (Tailscale, ~50 MB/s)

  [Approve Selected]  [Approve All]  [Dismiss]  [Schedule for Tonight]
```

**CLI equivalent:**
```bash
choam replicate --review       # Show pending queue
choam replicate --approve-all  # Approve everything in queue
choam replicate --approve 1,3  # Approve specific items by ID
choam replicate --schedule tonight --approve-all  # Approve but run at 2 AM
```

---

## Phase 9.9: SourceGuard — Source File Protection (2026-04-03)

Prevents silent corruption during transfers. Designed after a writer opened a database for write during a multi-hour rsync transfer.

### 9.9.1 SourceGuard Core — DONE

- **Sidecar lock** (`.choam_lock`) next to source file with PID, transfer ID, timestamp, mode
- **Double lsof hard-fail**: check before fingerprint + before rsync. For SQLite: checks `.db`, `.db-wal`, `.db-shm`
- **Source fingerprint**: MOVE = SHA-256 pre/post. COPY = mtime + size pre/post
- **Directory fingerprinting**: manifest hash (MOVE) or total size+max mtime (COPY) — catches edits inside dirs
- **Atomic lock creation**: `createNewFile()` prevents race between concurrent acquires
- **Stale lock cleanup**: dead PID = safe to replace
- **20 unit tests** covering lsof parsing, fingerprint verification, SQLite detection, lock lifecycle, directory fingerprinting, atomic locking

### 9.9.2 SQLite MOVE Quiescent Enforcement — DONE

- `PRAGMA wal_checkpoint(TRUNCATE)` before transfer
- Verify WAL empty (header-only) before rsync
- Post-transfer: re-check lsof + verify WAL still absent/empty
- **SHM fail-closed**: non-empty SHM = process has DB open = MOVE blocked (not just a warning)
- **lsof fail-closed**: lsof execution errors throw instead of warn-and-proceed
- All three SQLite files (`.db`, `.db-wal`, `.db-shm`) deleted only after all checks pass

### 9.9.3 Integration — DONE

- `SendCommand.kt`: direct send loop wrapped with SourceGuard
- `QueueProcessor.kt`: all paths wrapped including identical-fast-path delete

### 9.9.4 Observability — DONE (2026-04-04)

- **Error categorization**: SourceGuard failures stored with `[SG_ACQUIRE]`, `[SG_WAL]`, `[SG_VERIFY]` prefixes in queue `error` column
- **CLI**: `queue --status` shows purple SourceGuard category badges on failure lines
- **Web dashboard**: SourceGuard errors get visual badges + full error on hover tooltip
- **Lock inspection CLI**: `choam lock` lists active locks, `choam lock --clean` removes stale locks from dead PIDs, `choam lock --force-clean` for live PIDs

### 9.9.5 COPY Policy & Integration Tests — DONE (2026-04-04)

- **`docs/COPY_POLICY.md`**: Explicit consistency guarantees for MOVE vs COPY, SQLite risks, `--allow-stale-sqlite-copy` spec, snapshot COPY roadmap, and 6 invariants that must always hold
- **`SourceGuardIntegrationTest.kt`**: 10 integration tests covering race conditions, concurrent locking, WAL/SHM changes, directory mutations, stale lock recovery, error message fitness

### 9.9.6 Ecosystem `.choam_lock` Adoption — ACTION ITEMS ADDED (Phase 2)

Until writers respect `.choam_lock`, SourceGuard is fail-safe but not race-free. Action items added to each project's CLAUDE.md:

- [ ] Any application that writes to SQLite databases managed by CHOAM should check for `.choam_lock` before opening for write
- [ ] Update each project's docs with lock-checking guidance

### 9.9.7 SQLite Snapshot COPY — NOT STARTED (Phase 2)

When disk space is available:
- [ ] `VACUUM INTO` for compact snapshot creation
- [ ] SQLite backup API as alternative (fewer CPU cycles, incremental)
- [ ] `--allow-stale-sqlite-copy` flag for disk-constrained environments

---

## Phase 9.10: Daemon Hardening & Operator UX — COMPLETED (2026-04-04)

Practical reliability improvements to the daemon, queue processing, and operator visibility. Not new architecture — strengthening what exists before Phase 10/11.

**Full plan:** `SESSION_PLAN_DAEMON_HARDENING.md`

### 9.10.1 Single-Writer Enforcement

- [ ] Switch `processAll()` to `claimNext()` loop — atomic claim prevents double-processing
- [ ] Remove bare `Thread` spawn from web UI `POST /api/queue/process` — daemon-only processing
- [ ] Add daemon health file (`~/.choam/daemon-health.json`) with heartbeat, state, active transfer

### 9.10.2 Operator Visibility

- [ ] `choam daemon status` shows queue depth, active transfer progress, scheduled task state
- [ ] `choam status` gains daemon section (PID, uptime, heartbeat age, state)
- [ ] Web dashboard daemon health card + `/api/daemon/health` endpoint
- [ ] Queue entries surface skip/defer reasons (unreachable, backoff, locked)

### 9.10.3 Faster Crash Recovery

- [ ] Stale RUNNING recovery: PID check → progress file staleness (15min) → time fallback (2h, down from 18h)
- [ ] Recovery events logged to daemon activity and surfaced in status
- [ ] Orphaned progress file cleanup on daemon startup and transfer completion

### 9.10.4 Queue UX

- [ ] CLI `--verbose` flag shows retry count, next retry time, full error messages
- [ ] Web queue page: daemon status indicator, deferred reasons, retry state, expandable errors
- [ ] "Process Queue" button disabled when daemon not running (shows start instructions instead)

---

## Phase 9.12: Database-Aware Chunked Transfer (`choam db-transfer`) — PLANNED

> SQLite databases are among CHOAM's most critical payloads: catalogs, application
> state, and large operational indexes. They are also difficult to move safely over
> constrained links. `db-transfer` understands the data inside the file instead of
> treating the database as an opaque binary.

**Motivation:** Transferring a 53 GB `archive.db` from a workstation to an external
store over a 600 KB/s link. Binary chunking works but retains the full source until
verification. A database-aware approach exports row ranges into standalone
mini-databases, transfers each, and can reclaim source space incrementally.

### 9.12.1 Schema Introspection & Chunking Strategy

```bash
# Analyze a database and propose a chunking plan
choam db-transfer ~/data/archive.db external-store:/archives/ --plan

# Output:
#   Table: records            12.4M rows  38 GB   PK: (record_key, source_id)
#   Table: batches             8.1M rows  11 GB   PK: (batch_key)
#   Table: metadata            3.2M rows   2 GB   PK: (record_key)
#   Table: import_runs            847 rows  <1 MB  PK: (id) AUTOINCREMENT
#   Table: source_files            23 rows  <1 MB  PK: (id) AUTOINCREMENT
#   ---
#   Proposed: 53 chunks × ~1 GB each, chunked by rowid ranges on records
#   Small tables (import_runs, source_files) bundled into chunk 0
#   Estimated transfer time at 600 KB/s: ~25 hours
```

- [ ] `SchemaIntrospector` — discovers tables, row counts, PKs, estimated sizes, FK relationships
- [ ] `ChunkPlanner` — proposes row-range splits based on `--chunk-size` target. Groups FK-related rows together. Small tables bundled into first chunk.
- [ ] Handles AUTOINCREMENT PKs: uses `rowid` ranges for chunking, natural PKs for merge dedup

### 9.12.2 Chunk Export — Standalone Sub-Databases

- [ ] `DbChunkExporter` — for each chunk: `CREATE TABLE` (same schema) → `INSERT INTO ... SELECT ... WHERE rowid BETWEEN ? AND ?` → standalone `.db` file
- [ ] Each sub-database is fully operable — can be queried, inspected, attached
- [ ] Includes `_transfer_metadata` table: source file, chunk index, row range, table, SHA-256 of source at export time, timestamp
- [ ] `PRAGMA journal_mode=DELETE` on sub-DBs (no WAL needed for read-only transfer payloads)

### 9.12.3 Transfer & Verify — Via Existing CHOAM Send

- [ ] Each sub-DB transferred via `choam send` (reuses SourceGuard, rsync, SSH keepalive, bandwidth limiting)
- [ ] Post-transfer verification: row count match + optional checksum of sorted PKs
- [ ] Progress tracking in `~/.choam/db_transfers.json`: source path, dest, table, chunk index, status, row range, transferred_at
- [ ] Resume: on restart, reads progress file, skips already-verified chunks

### 9.12.4 Incremental Shrink (Optional `--shrink`)

```bash
# Transfer AND free space as each chunk lands
choam db-transfer ~/data/archive.db external-store:/archives/ \
  --chunk-size 1GB --shrink
```

- [ ] After verified transfer of chunk N: `DELETE FROM table WHERE rowid BETWEEN ? AND ?` on source
- [ ] Every K chunks (configurable): `PRAGMA incremental_vacuum` to reclaim pages without full rewrite
- [ ] Full `VACUUM` only at the end (or `--vacuum-every N` for periodic)
- [ ] `--shrink` requires exclusive access (SourceGuard lock on source for duration)
- [ ] Safety: never delete rows from source until remote chunk verified + row count confirmed

### 9.12.5 Remote Import

```bash
# On destination: import all sub-DBs into target
choam db-import /Volumes/EXTERNAL/archives/archive_chunks/ \
  --into /Volumes/EXTERNAL/archives/archive.db
```

- [ ] `DbChunkImporter` — `ATTACH` each sub-DB, `INSERT OR IGNORE` (natural PK dedup) or `INSERT OR REPLACE` (configurable)
- [ ] Handles AUTOINCREMENT tables: strips source IDs, lets destination assign new IDs (FK remapping via temp table)
- [ ] Validates `_transfer_metadata` — warns if chunks are out of order or from different source snapshots
- [ ] Reports: rows imported, rows skipped (already existed), rows conflicted

### 9.12.6 CLI Interface

```bash
# Full pipeline: plan → export → transfer → verify (no shrink)
choam db-transfer <source.db> <dest-drive-or-machine>:<path>/ [options]

# With incremental space reclaim
choam db-transfer <source.db> <dest>:<path>/ --shrink --chunk-size 1GB

# Plan only (dry run)
choam db-transfer <source.db> <dest>:<path>/ --plan

# Specific tables only
choam db-transfer <source.db> <dest>:<path>/ --tables executed_masks,generated_masks

# Import on destination
choam db-import <chunks-dir>/ --into <target.db>

# Resume a failed transfer
choam db-transfer <source.db> <dest>:<path>/ --resume
```

### Design Decisions

- **Chunk by rowid, not PK** — rowid ranges are fast (index scan), predictable sizes, and work for any table regardless of PK type. Natural PKs used for dedup at import time.
- **Standalone sub-DBs, not SQL dumps** — each chunk is queryable, portable, and can be verified with standard SQLite tools. SQL dumps are fragile (encoding, escaping) and can't be queried.
- **`PRAGMA incremental_vacuum` over full `VACUUM`** — full VACUUM rewrites the entire DB (doubles disk temporarily). Incremental vacuum frees pages without rewrite, ideal for gradual shrink.
- **FK-aware grouping** — rows with foreign key relationships travel together in the same chunk. The `ChunkPlanner` detects FK constraints and ensures referencing rows aren't split across chunks.
- **Reuses `choam send` for transport** — no new transfer code. Each sub-DB is just a file that goes through the existing CHOAM pipeline.

### Dependencies

- Phase 9.5 (`choam send`) — transport layer
- Phase 9.9 (SourceGuard) — exclusive access during `--shrink`
- Phase 9.10 (daemon hardening) — nice-to-have for background transfers

### Estimated Effort

~2-3 days. `SchemaIntrospector` + `ChunkPlanner` (~200 lines), `DbChunkExporter` (~300 lines), `DbChunkImporter` (~250 lines), `DbTransferCommand` CLI (~200 lines), progress tracking (~100 lines), tests (~400 lines).

---

## Phase 9.5: Ad-Hoc File Transfer (`choam send`) — COMPLETED (2026-03-15)

`choam send` is fully implemented with pre-flight checks, overwrite protection, `--move`, `--queue`, `--backup`, `--force`, drive label resolution, Sietch CID registration, SourceGuard protection, and transient retry. See CLAUDE.md for full usage.

The original gap description below is preserved for historical context.

### Original Gap (2026-03-12) — RESOLVED

> **Gap identified 2026-03-12.** CHOAM replaced the old `transfer` gist but dropped the most basic use case: "send this file to that machine, resume if interrupted, optionally delete the original." Every repo-based command requires pre-configured repos. There is no way to do a one-off file transfer through CHOAM — you have to drop down to raw rsync, which defeats the purpose of having CHOAM at all.

### The Problem

```bash
# What you WANT to say:
choam send ~/Desktop/recording.mov server:/Volumes/media-4tb/ --move

# What you HAVE to say today:
nice -n 19 rsync -avh --partial --progress ~/Desktop/recording.mov user@192.168.1.50:/Volumes/media-4tb/
# ...and manually remember the SSH user, IP, and to delete the original
```

CHOAM already knows every machine's IP, SSH user, and drive paths. It already has resumable rsync infrastructure. It just doesn't expose it for ad-hoc files.

### 9.5.1 `choam send` — Ad-Hoc Resumable Transfer

```bash
# Basic: send a file to a machine (uses CHOAM's known SSH config)
choam send ~/Desktop/recording.mov server:/Volumes/media-4tb/

# Send to a drive label (CHOAM resolves machine + mount point)
choam send ~/Desktop/recording.mov media-4tb:/

# Move mode: delete source after verified transfer (SHA-256 check)
choam send ~/Desktop/recording.mov media-4tb:/ --move

# Send multiple files / directories
choam send ~/Desktop/*.mov ~/Downloads/snapshots/ media-4tb:/archive/

# Dry run
choam send ~/Desktop/recording.mov media-4tb:/ --dry-run

# Queue mode: add to transfer queue, process later or in background
choam send ~/Desktop/recording.mov media-4tb:/ --queue
choam send ~/Downloads/snapshots/ backup-4tb:/archive/ --queue --move
```

**Behavior:**
- Resolves machine name → Tailscale IP + SSH user from `~/.choam/config.json`
- Resolves drive label → machine + mount path from drive registry
- Uses rsync `--partial --progress` (resumable on interrupt — re-run same command)
- `--move`: after transfer completes, verify SHA-256 of remote file matches local, then delete local. Two-phase: never deletes before verification.
- Logs to sync history (`~/.choam/sync_history.jsonl`) like all other operations
- Respects `nice -n 19` when targeting remote machines (lowest priority rule)

### 9.5.2 Transfer Queue

```bash
# View pending transfers
choam queue

# Process all queued transfers now
choam queue --run

# Process in background (nohup + nice)
choam queue --run --background

# Cancel a queued transfer
choam queue --cancel 3

# Queue status (running, pending, completed, failed)
choam queue --status
```

**Queue storage:** `~/.choam/transfer_queue.json` — each entry has: id, source path, destination (machine:path or drive:path), mode (copy/move), status (pending/running/completed/failed/cancelled), created_at, started_at, completed_at, bytes_transferred, error.

**Resume on failure:** If a queued transfer fails (network drop, machine unreachable), it stays in the queue with status=failed. Next `choam queue --run` retries failed items. rsync `--partial` means it picks up where it left off.

**Bandwidth & scheduling:**
```bash
# Throttle to avoid saturating a busy link
choam send bigfile.mov media-4tb:/ --bwlimit 5m    # cap at 5 MB/s

# Schedule: queue now, run later (when that other download is done)
choam send bigfile.mov media-4tb:/ --queue --after 2am

# Priority levels: low yields to other rsync/network traffic via nice + ionice
choam send bigfile.mov media-4tb:/ --queue --priority low

# Global throttle on all queued transfers to a machine
choam queue --run --bwlimit 2m --to server
```

**Bandwidth awareness:** Before starting a queued transfer, CHOAM can SSH to the destination and check load (`uptime` load average, active rsync/curl processes, disk I/O). If the machine is under heavy load, defer the transfer and retry later. This prevents the "20 KB/s because something else is saturating the pipe" scenario — CHOAM waits for a good window instead of crawling for 80 hours.

**Low Data Mode — Network-Aware Transfer Gates:**

Some networks are metered (phone hotspot, travel WiFi, satellite). Large transfers should never run on these unless explicitly overridden.

```bash
# Mark current network as low-data (e.g., Pixel hotspot)
choam network mark-low "Pixel_hotspot"
choam network mark-low "iPhone_tether"

# List low-data networks
choam network list

# Remove a network from low-data list
choam network unmark "Pixel_hotspot"
```

**How it works:**
- `~/.choam/low_data_networks.json` stores a list of SSID patterns (or interface names) flagged as metered
- Before `queue --run` processes a transfer, CHOAM checks the current WiFi SSID via `networksetup -getairportnetwork en0` (macOS) or `/System/Library/PrivateFrameworks/Apple80211.framework/.../airport -I`
- If current SSID matches a low-data pattern, skip all transfers UNLESS the queue entry has `--force` or `--priority urgent`
- `choam queue --run` prints: `Skipping 3 transfers — on low-data network "Pixel_hotspot". Use --force to override.`

```bash
# Queue a transfer that's allowed even on low-data networks
choam send important.db media-4tb:/ --queue --priority urgent

# Force queue processing on low-data network
choam queue --run --force
```

**Detection heuristic (future):** If no networks are explicitly marked, CHOAM could auto-detect metered connections by checking if the SSID contains "iPhone", "Pixel", "hotspot", "tether", or if the interface is `bridge100` (macOS tethering). But explicit marking is safer and more predictable.

### 9.5.3 Sietch Registration (Both Sides)

Every `choam send` is a Sietch event. Not optional — this is the default behavior.

**Before transfer (source side):**
1. CID-hash the file locally via Sietch (if not already in local registry)
2. Register in local `sietch_registry.db` with current machine + path

**After transfer (destination side):**
3. SSH to destination machine, CID-hash the arrived file via remote Sietch
4. Register in destination's `sietch_registry.db` with destination machine + path
5. On next `catalog-sync`, both registrations flow into `unified_registry.db` automatically

**On `--move` (source deletion):**
6. After SHA-256 verification confirms destination integrity
7. Delete local file
8. Update local Sietch registry: mark file as removed from this machine (location entry deleted, CID preserved in unified catalog pointing to destination only)

**Result:** After `choam send recording.mov media-4tb:/ --move`, the unified catalog shows the file exists on media-4tb with its CID — fully searchable, inspectable, and tracked. No orphaned files. No ghost entries. The transfer IS the catalog update.

```bash
# Skip Sietch registration (raw transfer only, for speed on bulk/temp files)
choam send ~/tmp/junk.zip server:/tmp/ --no-catalog
```

### 9.5.4 Cleanup Items

**Directory naming mismatch (example):**
During a bulk transfer, a raw `nohup rsync` was launched outside CHOAM (to bypass `--checksum` slowness and hostname/config issues). The destination directory name didn't match the source convention. ~4,700 of 26,327 files transferred before this was noticed.

**Root cause:** Transfer was done outside CHOAM because (1) `--checksum` flag made first-time bulk transfers painfully slow (full checksum scan before any bytes move), (2) hostname kept changing on network switch breaking `TargetResolver`, (3) drive label path resolution had a bug (fixed in `4c6e4b7`). These workarounds bypassed CHOAM's path management. All three issues are now fixed.

**Lesson:** All transfers should go through `choam send` — raw rsync bypasses path validation, queue tracking, Sietch registration, and progress reporting.

### Why Phase 9.5 (Not Later)

This is the single most common CHOAM interaction: "put this file over there." It's embarrassing that the tool built to replace raw rsync still requires raw rsync for the most basic operation. Every other feature (federation, DAG config, mobile nodes) is useless if the user can't do the one thing they reach for CHOAM to do 10 times a day.

---

## Phase 9.7: Merkle Tree Pre-Flight — COMPLETED (2026-04-01)

> **Problem identified 2026-04-01.** Pre-flight checks scale linearly with file count. A 94K-file directory requires 94K individual stat+shasum calls over SSH — even when nothing changed. For deep directory trees with thousands of files, pre-flight takes longer than the actual transfer.

### The Problem

Current pre-flight: pipe a bash script to the remote that checks every file individually. Works for <1K files. At 94K files:
- A ~28MB bash script gets piped via SSH stdin
- 94K `stat` + conditional `shasum` calls execute sequentially on the remote
- Takes 5-10+ minutes for the pre-flight alone
- SSH connections can time out mid-flight

**Quick fix (shipped 2026-04-01):** Auto-skip pre-flight when destination directory doesn't exist. If it's a first-time transfer, every file is NEW — no conflicts possible. This eliminates the most wasteful case.

### 9.7.1 Directory Merkle Tree

Build a Merkle hash tree per directory:
```
root_hash = hash(child_dir_hashes... + child_file_hashes...)
child_file_hash = hash(relative_path + size + mtime)  # fast, no content read
child_dir_hash = hash(its own children recursively)
```

**Pre-flight becomes:**
1. Compare root hashes (one SSH call). If match → everything identical, skip.
2. If mismatch → descend: compare child directory hashes to find which subtrees differ.
3. Only do per-file checking within the differing subtrees.

For a 94K-file tree where 50 files changed in 3 subdirectories: ~20 hash comparisons instead of 94K stat calls.

**Sietch already computes CIDs per file.** Directory-level Merkle roots are a natural extension — hash the sorted list of (filename, CID) pairs per directory, then hash directories recursively.

### 9.7.2 Manifest Caching

After a successful transfer, write a manifest file on both sides:
```
.choam_manifest.json  — { "built": "2026-04-01T...", "root_hash": "abc...", "files": { "path": { "size": N, "mtime": T, "sha256": "..." } } }
```

Pre-flight first checks if a cached manifest exists on the remote. If it does, diff against the local filesystem — no SSH stat calls needed for unchanged files. Only verify files where local mtime/size differs from the cached manifest.

### 9.7.3 Transfer Metadata Lifecycle — IN PROGRESS

Manifests are now written after successful directory transfers (both local and remote for COPY, remote-only after full verification for MOVE). `.choam_manifest.json` excluded from rsync via `--exclude`. `choam manifest-cache --list` and `--cleanup` commands added for lifecycle management.

Remaining work:
- **Hot (0-30 days):** Full manifest with per-file checksums. Enables undo — can identify exactly which files were transferred and verify they're still intact.
- **Warm (30 days - 6 months):** Archived to `~/.choam/manifest_archive/` (compressed). Enough to verify integrity but not instant rollback.
- **Cold (6+ months):** Deleted from archive. Summary-only audit trail via sync history.

Transition: `choam manifest-cache --cleanup` handles archival/deletion based on age. Never compressed in-place inside content directories.

---

## Phase 10: CHOAM DAG — Decentralized Config & Access Control

> The most important architectural change. Config becomes an event-sourced DAG that propagates across all nodes. No more manual config.json per machine.

### Why This Is Phase 10 (Not Later)

The current federation is a facade — share grants live in local SQLite, config is manually maintained per machine, nothing synchronizes. Adding a drive on server-b means manually editing config.json on every other machine. That's not a federation, it's a spreadsheet.

The REDO protocol (already battle-tested across 4 platforms) proves the DAG model works: immutable nodes, canonical JSON hashing, Ed25519 signatures, cross-platform sync. CHOAM needs the same architecture for its config layer.

### 10.1 The CHOAM Event DAG

Every config change becomes an immutable, signed event:

```
Event structure:
{
  "type": "ShareGranted",
  "houseId": "a1b2c3...",           // Author's House ID
  "parentCids": ["Qm..."],          // DAG parent references
  "timestamp": 1709568000000,       // Millisecond precision (like REDO)
  "payload": {
    "repository": "film",
    "peerHouseId": "d4e5f6...",
    "accessLevel": "READ"
  },
  "signature": "ed25519sig..."      // Signed by House private key
}

CID = SHA-256(canonical JSON of the event)
```

Event types:

| Event | Payload | Effect |
|-------|---------|--------|
| `HouseCreated` | name, publicKey | Establishes identity |
| `MachineJoined` | name, hostname, tailscaleIp, sshUser | Node enters the federation |
| `MachineUpdated` | name, changed fields | IP change, hostname change, etc. |
| `MachineLeft` | name | Node leaves (drives/repos remain in history) |
| `DriveAdded` | label, uuid, storageClass, machine, repos | New drive on any node |
| `DriveRemoved` | label | Drive disconnected/decommissioned |
| `RepoCreated` | name, type, localPath, replicationPolicy | New repository |
| `RepoPolicyChanged` | name, minCopies, preferredCopies, autoReplicate | Policy update |
| `PeerTrusted` | peerHouseId, peerPublicKey, peerName, ip | Establish trust |
| `PeerRevoked` | peerHouseId | Remove trust |
| `ShareGranted` | repo, peerHouseId, accessLevel, expiresAt? | Grant access |
| `ShareRevoked` | repo, peerHouseId | Revoke access |
| `BackupOffered` | peerHouseId, offeredBytes | Propose backup |
| `BackupAccepted` | peerHouseId, theirOfferedBytes | Accept backup |
| `BackupTerminated` | peerHouseId | End agreement |

### 10.2 DAG Sync Protocol

When two nodes connect (via Tailscale SSH, HTTP, or direct):

```
1. Exchange HEAD CIDs (each node's latest event)
2. Compute missing events (set difference on CID sets)
3. Pull missing events from peer
4. Verify each event:
   - Signature valid? (Ed25519 verify with author's public key)
   - Author authorized? (is the signing House ID recognized?)
   - Parents exist? (no orphan events)
   - No conflicts with existing events? (same-field concurrent edits)
5. Append valid events to local DAG
6. Recompute materialized state (current config)
7. Both nodes now converge to identical state
```

Conflict resolution (concurrent edits to same field):
- **Last-writer-wins** by timestamp (like REDO's NEWER_WINS)
- Conflicts are recorded, never silently dropped
- `choam dag conflicts` shows unresolved conflicts for manual review

### 10.3 Materialized State

The DAG is the source of truth. The "current config" is computed by replaying all events:

```
DAG events (immutable log)     →    Materialized state (computed view)
─────────────────────────────       ──────────────────────────────────
HouseCreated(my-house)         →    house.name = "my-house"
MachineJoined(server-b, ...)       →    machines["server-b"] = { ... }
DriveAdded(ext-drive, server-b)      →    drives["ext-drive"] = { machine: "server-b" }
MachineJoined(local, ...)      →    machines["local"] = { ... }
ShareGranted(film, peer-B)     →    shares = [{ film → B, READ }]
```

On startup, CHOAM replays the DAG to build the current config. This replaces `config.json` for all federation-managed state. Local-only settings (daemon port, log level) remain in a thin local config.

### 10.4 Ed25519 Identity

Replace the current EC keypair with Ed25519:
- Smaller keys (32 bytes), faster signatures
- Compatible with SSH, IPFS, Nostr, and blockchain identity systems
- Every event signed by the authoring House
- Peer identity verified by public key in the DAG (the `PeerTrusted` event contains their public key)
- `choam house upgrade-key` for migration

### 10.5 Storage

DAG events stored in `~/.choam/dag.db` (SQLite):

```sql
CREATE TABLE events (
    cid TEXT PRIMARY KEY,              -- SHA-256 of canonical JSON
    type TEXT NOT NULL,
    house_id TEXT NOT NULL,            -- Author
    parent_cids TEXT NOT NULL,         -- JSON array of parent CIDs
    timestamp INTEGER NOT NULL,        -- Millisecond epoch
    payload TEXT NOT NULL,             -- JSON payload
    signature TEXT NOT NULL,           -- Ed25519 signature (hex)
    received_at TEXT NOT NULL          -- When we first saw this event
);

CREATE TABLE dag_heads (
    house_id TEXT PRIMARY KEY,
    head_cid TEXT NOT NULL             -- Latest event CID per house
);
```

### 10.6 Auto-Sync on Connect

When the daemon detects a peer is reachable (Tailscale ping succeeds):
1. Pull their DAG head
2. Exchange missing events
3. Update materialized config
4. Log what changed: "server-b came online — learned about new drive ext-drive, 2 new repos"

No manual config editing ever again. Add a drive on server-b → local machine learns about it within 15 minutes (daemon poll interval) or instantly (if peer push is implemented).

### 10.7 Access Control Verification

Before any transfer, verify the DAG chain:

```kotlin
fun canAccess(repo: String, peerHouseId: String): AccessLevel? {
    // Walk DAG: find latest ShareGranted/ShareRevoked for this repo+peer
    // Verify signature chain: HouseCreated → PeerTrusted → ShareGranted
    // Check expiry if set
    // Return access level or null (denied)
}
```

The proof is in the data. No external authority needed.

### 10.8 Blockchain Bridge (Optional)

For cryptographic trust beyond the local mesh:
- Publish House ID to a L2 chain via identity contracts
- Anchor DAG head CIDs on-chain periodically (proof of state at time T)
- Verify peer identity on-chain before establishing trust
- Optional — CHOAM works fine without blockchain

### 10.9 Migration from config.json

Backward-compatible transition:
1. `choam dag init` — reads existing config.json and generates equivalent DAG events
2. Old config.json becomes read-only fallback for settings not in the DAG
3. New settings always go through DAG events
4. Eventually config.json only contains local-only settings (port, log level, IPFS gateway URL)

---

## Phase 11: Always-On Daemon

> Transform CHOAM from "a tool you run" to "infrastructure that runs."

The single most important architectural shift. CHOAM becomes a persistent background service that monitors, replicates, and serves content automatically.

### 10.1 launchd Service (macOS)

`choam daemon install` — generates and loads a `~/Library/LaunchAgents/com.choam.daemon.plist` that:
- Starts on login, restarts on crash
- Runs `choam serve --daemon` on configured port (default 8742)
- Logs to `~/.choam/logs/daemon.log` with rotation (keep 7 days)
- `choam daemon uninstall` removes the plist
- `choam daemon status` shows PID, uptime, port, log tail

The daemon IS the web server + content proxy + auto-replication engine. Everything runs in one JVM.

### 10.2 Auto-Replication Engine

The daemon periodically evaluates replication policy and acts:

```
Every 15 minutes:
  1. For each repo with replication policy:
     - Count copies vs preferred/min
     - If under-replicated: auto-create copy requests for reachable machines with space
  2. For pending copy requests:
     - Check target reachability (ping cache, 5-min TTL)
     - If reachable: execute transfer (rsync) with bandwidth limit from config
     - Update gossip announcements with new inventory
  3. For catalog freshness:
     - If any machine's catalog is >7 days stale: auto-run catalog-sync
```

**Safety constraints:**
- Never auto-delete (junk lifecycle is always manual)
- Bandwidth limit respected (default: 50% of estimated link speed)
- Only replicate repos with `autoReplicate: true` in config (opt-in per repo)
- Transfers interruptible — daemon shutdown waits for current transfer to finish, marks incomplete
- All auto-actions logged to `~/.choam/daemon_activity.jsonl`

### 10.3 Health Watchdog

The daemon monitors its own health:
- Drive space warnings at 90%/95%/99% thresholds → logged + optional webhook
- Machine reachability changes → logged (peer came online/went offline)
- Catalog staleness alerts → logged when any machine exceeds 30-day threshold
- Transfer failures → logged with retry backoff (1min → 5min → 30min → 1hr)

### 10.4 Notification Hooks

`~/.choam/config.json` gains a `notifications` section:
```json
{
  "notifications": {
    "webhook": "http://localhost:8080/webhook",
    "onTransferComplete": true,
    "onReplicationMet": true,
    "onDriveSpaceWarning": true,
    "onPeerStatusChange": true
  }
}
```

Webhook POST with JSON payload. Compatible with Slack incoming webhooks, Discord, ntfy.sh, or custom receivers.

### 10.5 Daemon API Endpoints

The web server gains action endpoints (dashboard stops being read-only):

| Method | Endpoint | Action |
|--------|----------|--------|
| POST | `/api/sync` | Trigger manual sync for a repo/machine |
| POST | `/api/push` | Push a repo to a target machine |
| POST | `/api/pull` | Pull a repo from a source machine |
| POST | `/api/fulfill` | Execute pending copy requests |
| POST | `/api/catalog-sync` | Trigger catalog sync |
| POST | `/api/junk/mark` | Mark a CID as junk |
| GET | `/api/daemon/status` | Daemon health, uptime, active transfers |
| GET | `/api/daemon/activity` | Recent activity log |
| POST | `/api/daemon/pause` | Pause auto-replication (until resumed or next restart) |
| POST | `/api/daemon/resume` | Resume auto-replication |

All POST endpoints require a simple bearer token from config (`apiToken` field) to prevent accidental triggers from scripts.

### 10.6 Web Dashboard — Action Buttons

Every read-only section gains action controls:
- **Dashboard:** "Sync All", "Fulfill Pending" buttons
- **Inspect page:** "Push to {machine}" buttons per missing copy
- **Report page:** "Fix" buttons on recommendations (triggers the recommended action)
- **Federation page:** "Announce" button to broadcast gossip

Actions fire via HTMX POST to the daemon API, show a spinner, then refresh the section on completion.

---

## Phase 12: Sharing & Access Control Enforcement

> Make federation usable by real humans, not just the developer who built it.

### 12.1 docs/SHARING_GUIDE.md — DONE (2026-03-04)

Comprehensive guide with real-world walkthrough:

**Part 1: Identity**
- What a House is and why you need one
- `choam house init --name my-house` step-by-step
- Where the keypair is stored, how to back it up
- How House IDs work (public key fingerprint)

**Part 2: Adding Peers**
- Getting a friend's House ID (they run `choam house status`, share the ID)
- `choam house add-peer` with their Tailscale IP
- Verifying the peer (checking fingerprint out-of-band)
- What "trust" means in CHOAM — explicit only, no auto-discovery

**Part 3: Sharing Repositories**
- The three access levels with concrete scenarios:
  - **STORE** — "Hold my encrypted backup. You can't read it." (offsite disaster recovery)
  - **READ** — "Browse my movie collection from your machine." (media sharing between friends)
  - **WRITE** — "We're collaborating on this project. Both of us can add/modify files." (shared workspace)
- `choam share grant film --with <peer-id> --access read`
- How access levels compose with replication policy
- Revoking access: what happens to data already transferred

**Part 4: Mutual Backup**
- The backup agreement flow (offer → accept → active)
- Storage quotas and how they're tracked
- The bandwidth economy — reciprocity, not payment
- Suspending and terminating agreements
- What happens when a peer goes offline for weeks

**Part 5: Security Model**
- Content addressing means you can verify what you received
- STORE access uses encryption at rest (peer has blob, not plaintext)
- No global directory — peers are added manually
- Tailscale provides the network layer encryption
- Threat model: trusted peers, untrusted network
- What CHOAM does NOT protect against (compromised peer machine, physical access)

### 12.2 Access Control Enforcement

Currently, share grants are recorded but not enforced — any peer with SSH access can pull anything. Phase 11 adds real enforcement:

- **Pre-transfer ACL check** — before `choam push/pull`, verify the requesting peer's House ID has the required access level for the repository
- **Signed requests** — transfer requests include a signature from the requester's House key, verified by the provider
- **Audit trail** — every access attempt (granted or denied) logged to federation_log
- **Expiring grants** — `--expires 30d` flag on `choam share grant`, auto-revoked after expiry
- **Rate limiting** — per-peer bandwidth caps derived from backup agreement quotas

### 12.3 Encrypted Backup (STORE tier)

For STORE-level access, content is encrypted before transfer:
- **Encryption:** AES-256-GCM with a per-repo key derived from the House keypair
- **Key management:** Repo encryption key stored locally at `~/.choam/repo_keys/{repo}.key`, never transmitted
- **Peer sees:** opaque blobs with CID-based filenames, cannot read content
- **Restore:** only the repo owner (with the key) can decrypt
- **Verification:** encrypted blobs still have CIDs — peer can verify integrity without reading content

### 12.4 Web Dashboard — Access Control UI

- **Federation page:** "Grant Access" button → modal with repo/peer/level selector
- **Share grants table:** "Revoke" buttons with confirmation
- **Peer detail view:** click a peer → see all shares + backup agreement + transfer history
- **Audit log page:** `/audit` showing all federation events with filters

---

## Phase 13: Mobile Nodes

> Your phone becomes a first-class CHOAM citizen.

### 13.1 Mobile Sync Profile

Extend `NodeCapability` (already has PHONE/TABLET types) with real mobile constraints:
- **Cache limit** — phone gets at most N GB of content (LRU eviction)
- **Sync schedule** — ON_WIFI only, ON_POWER only, or SCHEDULED (e.g., 2-4 AM)
- **Upload-only repos** — phone camera roll auto-syncs to desktop, but phone doesn't pull the full movie library
- **Download-only repos** — phone can stream/cache from the media library but doesn't become a replication target

### 13.2 Android Client (Kotlin/Compose)

Minimal first version:
- Browse catalog (search, filter by machine/type)
- Stream content from CHOAM daemon (via content proxy)
- Auto-upload camera photos to a configured repo
- View federation status (house, peers, shares)
- Push notifications for transfer completion

Architecture: Ktor client talking to CHOAM daemon API. No local IPFS node — phone uses the daemon as its gateway.

### 13.3 iOS Client (SwiftUI)

Same feature set as Android. Uses the daemon's REST API + content proxy. Background upload via iOS background URL session for camera sync.

### 13.4 Progressive Web App

Before native apps: a PWA wrapper around the web dashboard that:
- Works offline (cached dashboard shell, catalog search via local IndexedDB mirror)
- Installable on home screen
- Push notifications via service worker
- Stream media via `/stream/{cid}` (already works)

This is the fastest path to mobile — zero app store friction.

---

## Phase 14: Smart Automation

> CHOAM anticipates what you need before you ask.

### 14.1 Content-Aware Replication Policies

Beyond simple copy counts — policies that understand content:
- **"Keep new content hot"** — files added in the last 30 days get 3 copies, older files can drop to 2
- **"Prioritize large files"** — single-copy files >1GB get replicated before small files
- **"Geo-distribute video"** — video content must exist on at least 2 different geographic locations
- **"Archive cold storage"** — files unaccessed for 6 months move to COLD drives only

### 14.2 Predictive Transfers

The daemon learns access patterns:
- Track which files are streamed/accessed via content proxy
- Pre-replicate popular content to the machine that accesses it most
- Example: if you always stream movies from the laptop, auto-pull your watchlist there

### 14.3 Deduplication Actions

`choam report` already detects same-machine duplicates. Add:
- `choam dedup --dry-run` — show what would be cleaned
- `choam dedup --execute` — replace duplicates with hardlinks (same-drive) or remove extras (cross-drive)
- Never removes the last copy — dedup only targets redundant paths, not replication

### 14.4 Integrity Verification Daemon Task

Periodic background verification:
- Random sample of 1% of files per day (full verification cycles in ~3 months)
- SHA-256/CID recomputation to detect bitrot
- Alert on mismatches with specific file + expected vs actual hash
- Auto-quarantine corrupted files (move to `.choam/quarantine/`, log, attempt re-pull from healthy copy)

---

## Phase 15: Ecosystem Integration

> CHOAM becomes the file layer that every project depends on.

### 15.1 Jellyfin Library Sync

Auto-generate Jellyfin library configurations:
- Scan catalog for video content by repo
- Generate library XML pointing to local paths (or CHOAM proxy URLs for remote)
- Auto-update when content moves between machines
- Jellyfin watches the generated library paths — content appears/disappears automatically

### 15.2 MCP Server

Claude Code / LLM integration via Model Context Protocol:
- `choam-search` tool: search catalog from any Claude session
- `choam-stream` tool: stream content for LLM analysis (video frames, audio transcription)
- `choam-inspect` tool: get file details for any CID
- `choam-status` tool: check system health

### 15.3 Project-Specific Adapters

Each consumer project gets a thin adapter:
- **Image archive app** — Screenshots/photos: `CHOAM_BASE=http://localhost:8742` → `/resolve/{cid}` for all images
- **Media catalog** — Movie metadata + posters: catalog search → poster URLs via content proxy
- **Video analysis** — Video analysis frames: content proxy serves keyframes from any machine
- **Download manager** — Automated downloads: auto-catalog new downloads, replicate to backup machine

### 15.4 Import/Export

- `choam export --repo film --format tar` — export a repo as a tar archive with CID manifest
- `choam import manifest.json` — import content from a manifest (verify CIDs, register locations)
- Compatible with IPFS CAR files for interop with the broader IPFS ecosystem

---

## Technical Constraints



- **Language:** Kotlin (strongly preferred for all tooling)
- **JDK:** 21 — `-Xmx2g` heap
- **Build:** Gradle with Kotlin DSL — `./gradlew build` must pass before any work is declared complete
- **Default branch:** `master`
- **Sietch dependency:** Composite build at `../sietch` relative path
- **Config:** JSON at `~/.choam/config.json`
- **Testing:** JUnit 5 + MockK — new features must have tests
- **No fake data:** All testing uses real directory structures or temp dirs
- **CI:** GitHub Actions on push to master and PRs. Release via `v*` tags.
- **Homebrew:** `brew install jlmalone/tap/choam`

---

## Current State (2026-03-04)

| Metric | Value |
|--------|-------|
| Tests | 625 passing (0 failures) |
| CLI commands | 37 (+ 17 subcommands) |
| Web pages | 8 (+ inspect, resolve, stream routes) |
| Phases complete | Phase 1 through Phase 9.9 (incl. 9.7 Merkle/Manifests, 9.8 Queue Overhaul, 9.9 SourceGuard) |
| Tests | 939 |
| Content proxy | Tiered resolution: local → IPFS gateway → diagnostic 404 |
| CORS | Enabled for cross-project browser access |
| Machines | 3 |
| Cataloged files | ~2.43M across 2 remote machines |
| Cataloged data | ~3.5TB across 2 machines |
| Registry DB | `~/.choam/catalogs/sietch_registry.db` per machine |
| Unified registry | `~/.choam/unified_registry.db` (merged via catalog-sync) |
| Search index | `~/.choam/catalog-index.db` (FTS5) |
| Drives tracked | 2 external drives (4TB exFAT + 6TB NTFS) |

### What's Next

**Housekeeping:**
1. Help-text sweep — 5 remaining commands need expanded help (24/29 done)
2. Open source remediation — Sietch needs LICENSE, IP scrubbing (see below)
3. Update stale docs — docs/TROUBLESHOOTING.md (says JDK 17, should be 21), docs/USAGE.md (draft)
4. Compress choam.png logo (1.1MB → target <100KB)

**Open source remediation (from audit):**

*Sietch (CRITICAL — must fix before any public release):*
- [ ] Add LICENSE file (MIT, matching CHOAM)
- [ ] Untrack + gitignore RESUME_CLAUDE.md and CLAUDE.md (contain personal infrastructure details)
- [ ] Replace any hardcoded IPFS node IPs with `localhost` in source code
- [ ] Scrub README.md — use generic placeholders throughout
- [ ] Consider `git filter-repo` to clean history if needed

**Done:**
5. ~~MCP server integration~~ — **DONE** (2026-03-03). CHOAM catalog searchable from LLM tooling.
6. ~~Phase 4 — `choam report`~~ — **DONE** (2026-03-03). 8-section health dashboard with content classes, geo diversity, dedup, recommendations.

**Done:**
7. ~~Phase 5 — web dashboard~~ — **DONE** (2026-03-03). Ktor+HTMX, 4 pages, Matrix dark theme.
8. ~~Phase 6 — federation~~ — **DONE** (2026-03-03). House identity, share ACLs, mutual backup, audit log.
9. ~~Phase 7 — advanced~~ — **DONE** (2026-03-03). Mobile nodes, gossip protocol, content streaming, bandwidth economy.

**Phases 1-9.9 complete.** Next: Phase 9.10 (daemon hardening & operator UX), then Phase 10 (DAG protocol), Phase 11 (always-on daemon), Phase 12+.

---

## Phase 16: Cross-Machine Inventory & Provable Reclaim (`choam inventory` / `choam reclaim`) — PROPOSED

**Proposed 2026-06-17.** A read/query layer over data CHOAM already records — per-transfer
SHA-256 `.choam_manifest.json` on both source and destination (ChoamManifest / ManifestWriter),
SyncHistoryStore, and the transfer queue DB `~/.choam/transfer_queue.db` (TransferQueueStore,
per-transfer status + move/copy mode). No new transfer machinery — this just surfaces what's there.

**Why:** "What's safe to delete locally to free space?" is answered by hand today — ssh to the
target, match titles by name, eyeball `du`, reason about move-vs-copy and whether the transfer
actually finished. Slow and risky: a title only 1% transferred *looks* present on the target and
nearly got deleted prematurely. (Observed 2026-06-17: recent sync history is ~90% FAILED, so
partial/failed copies are common — making by-hand reclaim genuinely dangerous.)

### 16.1 `choam inventory [--machine <m>] [--json]`
For every known title: which machines/paths hold it, whether a SHA-256-VERIFIED copy currently
exists elsewhere, transfer mode (move/copy), and last-verified time.

### 16.2 `choam reclaim [--machine <m>] [--min-size <GB>] [--json] [--execute]`
List LOCAL items that have a confirmed verified remote copy, ranked by size, with total reclaimable
space. **Dry-run by default**; `--execute` deletes only the safe ones.

### 16.3 Hard safety requirements (the whole point)
- NEVER mark reclaimable from stale history. At reclaim time RE-VERIFY the remote copy exists AND its
  SHA-256 matches (re-check the remote manifest + file-count/sizes live). An in-progress/partial
  transfer on the target is NOT a valid copy (the 1%-transferred trap).
- Skip anything locked by a running transfer (SourceGuard) or being actively written.
- `--execute` deletes a given item only after its own live re-verification passes; log each deletion
  with the verifying manifest/hash.
- Optional (advisory, off by default): flag titles the external distribution client is actively
  serving, so reclaim warns before removing served content.

### 16.4 Constraints
- Kotlin/JDK21, existing module layout (`vision.salient.choam.*`). Reuse TransferQueueStore /
  SyncHistoryStore / ChoamManifest — don't re-parse rsync output.
- Name supported protocols and client integrations when technically relevant. Keep credentials,
  tracker details, workload names, and operator activity in untracked `~/.choam` configuration.
- Tests: JUnit5 + `@TempDir` like the existing suite. No AI attribution in commits.

### 16.5 server_monitor consumer requirements (added 2026-06-17)
server_monitor will surface these as a **CHOAM window** — a History tab ships first off
`~/.choam/sync_history.jsonl`; Inventory + Reclaim tabs drop in when 16.1/16.2 land. For clean, safe
consumption by that (public, history-scrubbed) app:
- **`--json` on BOTH `inventory` AND `reclaim`** (16.2 only specced it on inventory). The reclaim
  dry-run is the most valuable thing to render — emit `{ items[], totalReclaimableBytes, count }` so a
  panel shows the headline number without re-deriving it.
- **Per-item `blockingReason`** (`locked` / `partial` / `unverified` / `serving`). Don't silently omit
  unsafe items — emit them flagged with *why*, so the UI (and a human) can audit the safe-delete set.
- **Versioned, stable JSON schema** (`"schema": N`); rename keys only with a bump + transition window.
  (A sibling tool renamed a status key and hard-crashed server_monitor's panel with a raw decode
  error — don't repeat that.)
- **Exit-code contract** (0 = clean, nonzero = problem) so `inventory`/`reclaim` can double as a
  server_monitor *check*.
- **Generic JSON field names** (e.g. `servingFlag`, not the client's name) so the public repo consumes
  them without leaking.
- **GUI safety boundary:** server_monitor exposes inventory + reclaim **dry-run only, read-only**. It
  will NOT wire `--execute` into a menu-bar click (deletion from a menu bar is a footgun); `--execute`
  stays a deliberate CLI act with the live re-verify above.

**Acceptance:** `choam reclaim` reproduces, in one command, the safe-delete answer that today needs
manual ssh+du — and provably refuses to list any title whose remote copy is missing or partial.

## Backlog: Repository Model Revisit (Product)

**Added 2026-03-15.** The current repository model needs a product-level rethink. Today repos are just named path aliases with advisory replication policies — no automatic sync, no enforcement. Key questions:

- **Should repos auto-replicate?** When a repo has `minCopies: 2` and only 1 copy exists, should the daemon automatically queue a transfer? What about bandwidth/cost awareness?
- **Some drives have no repos mapped.** Data is actively stored on drives but the config doesn't reflect it — projects that move data between machines need explicit repo mappings.
- **Repo discovery:** Should CHOAM detect data directories on a drive and suggest repo mappings, rather than requiring manual config?
- **Repo vs. ad-hoc sends:** `choam send` bypasses repos entirely. Should completed sends auto-register as repo locations? Or should repos and sends stay separate concepts?
- **Granularity:** Is one repo per top-level directory right? A project may have bulk media (14 GB) and databases (small, critical) — different replication needs. Should sub-repos exist?
- **Config as code:** The current JSON config is fragile and manual. Should repo definitions live in the DAG (Phase 10) so they sync across machines automatically?

This is not a code task — it's a product/UX design session to figure out what repos *should be* before building more on top of the current model.

---

## Backlog: Torrent / Swarm Replication System (Design Brief)

**Added 2026-03-21.** Dual-mode transfer system — internal mesh replication (trusted peers over Tailscale) and external BitTorrent (public/private trackers). Same content, same CIDs, switchable transport. **Not scheduled for implementation — saved for future consideration.**

### Mode 1: Internal Swarm (Mesh Torrent)

BitTorrent-like chunk-based transfer between CHOAM nodes (server-b, server, local) over Tailscale. No external trackers. Trust is pre-established via CHOAM federation (Ed25519 keypairs, share grants).

**Why not just rsync:** Replicating a 50GB file to 2 machines via rsync = 50GB × 2 = 100GB from source. With swarm, machine B seeds chunks to machine C while still downloading from A. Total from source: ~50GB. Both peers get it faster.

**Key design decisions:**
- **Chunk size:** 4MB (matches IPFS default block size, allows CID per chunk)
- **Piece hashing:** Use IPFS CID for each chunk — chunks are already content-addressed and verifiable
- **Tracker:** None needed. CHOAM's `unified_registry.db` IS the tracker — it knows which machines have which CIDs. Gossip protocol can announce chunk availability.
- **Peer discovery:** Tailscale IPs from `~/.choam/config.json`. No DHT needed for internal mesh.
- **Wire protocol:** Raw TCP sockets, or leverage IPFS Bitswap (which is literally a torrent protocol for CID-addressed blocks). Bitswap is already in Kubo.
- **Trust:** All internal peers pre-trusted via federation. No encryption needed beyond Tailscale's WireGuard.

**Implementation path:**
1. Split files into 4MB chunks, compute CID per chunk, store chunk manifest as a DAG node
2. The manifest is the "torrent file" — list of chunk CIDs + file metadata
3. To replicate: requesting machine reads manifest, checks which chunks it has (via local registry), requests missing chunks from any peer that has them
4. Peers serve chunks from local filesystem (no central seeder)
5. As chunks arrive, receiver registers them in its local Sietch registry
6. When all chunks received, file reassembled and verified against root CID

### Mode 2: External BitTorrent (qBittorrent Compatible)

Generate real `.torrent` files from CHOAM-managed content. Upload to private trackers. Download via qBittorrent or any standard client.

**Why:** Share content with people outside the mesh, or download from external sources into the mesh.

**Key design decisions:**
- **Torrent creation:** Generate `.torrent` from file + CHOAM metadata. Include CID in torrent's comment field for cross-reference.
- **Tracker support:** Configurable — private tracker URLs, or trackerless (DHT + PEX)
- **Client integration:** Don't reimplement a torrent client. Shell out to `qbittorrent-nox` (headless) or `transmission-cli`. CHOAM manages lifecycle: create torrent → add to client → monitor → register completed download in Sietch registry.
- **Seeding policy:** Configurable per repo — seed ratio, seed time, bandwidth limits
- **Import path:** When qBittorrent finishes a download, CHOAM picks it up (via watch directory), computes CID, registers in Sietch, optionally replicates to mesh via Mode 1.

### The Switch

```jsonc
// ~/.choam/config.json
"transfer": {
  "mode": "mesh",           // "mesh" (internal swarm), "rsync" (current), "bittorrent" (external)
  "meshSwarm": {
    "enabled": true,
    "chunkSizeMb": 4,
    "maxUploadKbps": 5000,
    "maxPeers": 10
  },
  "bittorrent": {
    "enabled": false,
    "client": "qbittorrent-nox",
    "clientApi": "http://localhost:8080",
    "watchDirectory": "~/.choam/torrents/watch",
    "completedDirectory": "~/.choam/torrents/completed",
    "defaultTrackers": [],
    "seedRatio": 2.0,
    "seedTimeMinutes": 1440
  }
}
```

### CLI

```bash
# Internal mesh swarm
choam replicate <file-or-cid> --to server-b,server    # swarm replication
choam replicate <file-or-cid> --to all                # all trusted peers

# External torrent
choam torrent create <file-or-cid> --tracker "https://..."  # generate .torrent
choam torrent add <torrent-file>                              # import external torrent
choam torrent status                                          # show active torrents
```

### Architecture Layers

```
CHOAM CLI
  ├── choam send         → rsync (point-to-point, current)
  ├── choam replicate    → mesh swarm (multi-peer, CID-chunked)
  └── choam torrent      → external BitTorrent (qBittorrent integration)

All three register results in:
  └── Sietch ContentLocationRegistry (CID → machine → path)
```

### IPFS Bitswap Shortcut (MVP)

Before building a custom swarm protocol, consider: IPFS Bitswap already does this. If a file is `ipfs add`'d on machine A and pinned, machine B can `ipfs get <CID>` and Bitswap fetches chunks from A (and C if C also has it). Missing pieces:
1. Automatic pinning on destination machines (CHOAM could trigger this)
2. Bandwidth limiting (Kubo has `Swarm.ResourceMgr` config)
3. Progress reporting (Kubo API has pin progress endpoints)

**So the MVP might be:** `choam replicate` = `ssh target "ipfs pin add <CID>"` + monitor progress. The swarm behavior comes free from IPFS.

### Data Model (DAG Events)

```
DagEvent: REPLICATION_REQUESTED
  payload: { cid, fromMachines: [...], toMachines: [...], priority, mode: "mesh"|"rsync"|"bittorrent" }

DagEvent: CHUNK_AVAILABLE
  payload: { parentCid, chunkCid, chunkIndex, machine }

DagEvent: REPLICATION_COMPLETED
  payload: { cid, machine, path, verifiedAt, transferMode, bytesTransferred, durationMs }
```

### Trust Model (for future multi-user)

| Level | Description | Topology |
|-------|-------------|----------|
| Full trust | All chunks available to peer (current federation peers) | Internal mesh |
| Selective trust | Only specific repos/CIDs shared (CHOAM share grants) | Internal mesh + ACLs |
| Zero trust | Content is public, verified by info_hash | External BitTorrent |

### Suggested Phasing

This belongs after current roadmap phases (post-Phase 15):
- **Phase N.1:** IPFS Bitswap-based replication (`choam replicate` → `ipfs pin add`)
- **Phase N.2:** Custom mesh swarm with chunk manifests (if Bitswap performance is insufficient)
- **Phase N.3:** External BitTorrent integration (`qbittorrent-nox` wrapper)
- **Phase N.4:** Hybrid mode — internal swarm seeds to external trackers
