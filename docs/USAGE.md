# CHOAM Usage

See `CLAUDE.md` in the project root for the full CLI reference (40 commands with examples).

## Quick Reference

```bash
# Status & discovery
choam status                          # Dashboard: drives, repos, machines, catalog
choam drives                          # List configured drives + mount status
choam drives scan                     # Detect new drives via UUID

# Search (requires catalog-sync first)
choam search "Aliens"                 # FTS5 search across all machines
choam search --ext mkv,mp4 --min-size 1073741824  # Filter by type+size
choam search --cid QmABC123           # Exact CID lookup

# Sync & transfer
choam push media --to server           # Upload to remote
choam pull media --from server         # Download from remote
choam sync media desktop→laptop       # Bidirectional sync

# Catalog management
choam catalog-sync                    # Download + merge remote registries
choam catalog-sync --from server       # Sync from one machine
choam catalog-update --drive ext-4tb   # Incremental reindex
choam rebuild-index                   # Rebuild FTS5 from unified registry

# Content operations
choam diff server laptop               # Compare two machines by CID
choam move film --from server --to laptop  # Verified relocation
choam verify --machine server          # Check registered files still exist

# Replication planning
choam plan                            # Gap analysis vs replication policy
choam request-copy film --to laptop     # Queue a migration
choam fulfill                         # Execute pending migrations

# Content lifecycle
choam junk mark QmABC123              # Mark for deletion (reversible)
choam junk list                       # Show marked content
choam junk unjunk QmABC123            # Unmark (safe again)
choam junk purge                      # PERMANENTLY delete marked content

# Maintenance
choam catalog-purge                   # Remove macOS junk from registry
choam history                         # View sync history
choam config                          # Show/validate config
```
