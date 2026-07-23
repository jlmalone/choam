# CHOAM COPY Policy — Consistency Guarantees

## Overview

CHOAM `send` operates in two modes: COPY (default) and MOVE (`--move`). Each mode
has different consistency guarantees, verification costs, and failure behaviors.

This document defines the explicit policy for each mode so operators and ecosystem
tools know what to expect.

## MOVE: Strong Consistency

**Guarantee:** The destination is a byte-for-byte replica of the source at transfer time.
Source is deleted only after all verification passes.

**Pre-transfer:**
- SourceGuard acquires sidecar lock (`.choam_lock`)
- Double lsof check — hard-fail if any process has source open for write
- SHA-256 fingerprint recorded
- SQLite: WAL checkpoint (TRUNCATE), verify WAL empty, lsof on full trio (.db, .db-wal, .db-shm)

**Post-transfer:**
- SHA-256 of source re-computed — must match pre-transfer fingerprint
- Remote file size + hash verified via SSH
- SQLite: re-check lsof, verify WAL still absent/empty, SHM still absent/empty
- Source deleted (with .db-wal and .db-shm for SQLite) only if ALL checks pass

**Failure mode:** Source preserved. Transfer marked FAILED. No data loss.

**Cost:** Two full reads of the source (one for fingerprint, one for verification).

## COPY: Default — Best-Effort Consistency

**Guarantee:** The destination matches the source's mtime and size at the moment the
transfer started. If the source changed during transfer, the transfer is marked FAILED.

**Pre-transfer:**
- SourceGuard acquires sidecar lock (`.choam_lock`)
- Double lsof check — hard-fail if any process has source open for write
- mtime + size fingerprint recorded (no SHA-256 — avoids doubling source I/O)

**Post-transfer:**
- mtime and size of source re-checked — must match pre-transfer fingerprint
- rsync's own checksum verification ensures wire integrity

**Failure mode:** Transfer marked FAILED if source changed. Destination may contain
a partial or inconsistent snapshot.

**Cost:** Minimal — no extra reads beyond rsync itself.

**When this is sufficient:**
- Media files (video, images, audio) — immutable after creation
- Log files being archived — append-only, rsync handles partial
- Databases not actively written to — quiescent DBs are safe

## COPY: SQLite — Additional Risks

SQLite databases are NOT safe to COPY while a writer holds the WAL open:
- rsync copies the `.db` file, which may be behind the WAL
- The WAL may be partially checkpointed
- The resulting copy may be internally inconsistent

**Current behavior:** SourceGuard's lsof check catches most active writers. If a writer
opens the DB AFTER the lsof check but BEFORE rsync reads the data, the post-transfer
mtime/size check catches the change and marks the transfer FAILED.

**The race window:** Between lsof check and rsync read start (~milliseconds). SourceGuard
is fail-safe (detects after the fact) but not race-free (cannot prevent the race).

### `--allow-stale-sqlite-copy` (NOT YET IMPLEMENTED)

For disk-constrained environments where `VACUUM INTO` is not an option:

When this flag is set, CHOAM will COPY a SQLite database even if the WAL is non-empty,
provided the base `.db` file is quiescent (no active writers at lsof time). The resulting
copy contains only checkpointed data — any WAL-only writes are lost in the copy.

**Use case:** Archival/backup where "last checkpoint" is good enough.
**Risk:** The copy is stale by definition — it's missing WAL-only writes.
**Operator responsibility:** Understand that the copy may be behind the live database.

### SQLite Snapshot COPY (NOT YET IMPLEMENTED)

For fully consistent SQLite copies:

1. **`VACUUM INTO`** — Creates a compacted copy of the database. Requires ~1x disk space.
   Atomic, consistent, includes all WAL data. Preferred when disk space allows.

2. **SQLite Backup API** — Incremental page-level copy. Lower CPU than VACUUM INTO.
   Handles concurrent readers (not writers). Requires `sqlite3` CLI or JDBC.

3. **Read transaction hold** — NOT RECOMMENDED for MOVE. A held read transaction prevents
   checkpoint, but writes after the snapshot are invisible to the transaction holder.
   Safe for COPY (snapshot is consistent), dangerous for MOVE (writes after snapshot are
   lost when source is deleted).

## Summary Table

| Mode | Fingerprint | Post-Check | SQLite Safe? | Cost |
|------|------------|------------|-------------|------|
| MOVE | SHA-256 | SHA-256 + remote hash | Yes (full quiescent enforcement) | 2x source read |
| COPY | mtime + size | mtime + size | Mostly (lsof gate, fail-safe) | Minimal |
| COPY + `--allow-stale-sqlite-copy` | mtime + size | mtime + size | Stale but intentional | Minimal |
| COPY + snapshot (future) | SHA-256 of snapshot | SHA-256 | Yes (atomic snapshot) | 1x source read + disk |

## Invariants (Must Always Hold)

1. **MOVE never deletes source unless ALL verification passes.** No exceptions.
2. **COPY never overwrites destination without preflight classification.** NEW = safe, CONFLICT = abort.
3. **lsof failure = transfer failure.** If lsof can't run, we can't verify. Fail closed.
4. **SHM existence during MOVE = fail.** Non-empty SHM means a process has the DB open.
5. **Sidecar lock is always cleaned up.** Guard.close() in finally block. Stale locks from dead PIDs are auto-replaced.
6. **Post-transfer verification is the real gate.** lsof is best-effort. Fingerprint comparison is the truth.
