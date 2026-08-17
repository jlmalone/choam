# Primary transfer gap analysis

## Decision

CHOAM should be the default mechanism for moving files between trusted machines. It may use rsync
as its data plane, but callers should enter through `choam send` so path validation, overwrite
protection, resumability, retry policy, history, and Sietch registration remain one coherent
operation. Calling rsync directly is an emergency fallback, not an equivalent workflow.

## Why bypassing CHOAM is a problem

A raw rsync command proves only what that invocation was configured to prove. It does not
automatically preserve CHOAM's machine and drive resolution, source guard, conflict preflight,
queue state, retry classification, transfer history, or Sietch location registration. Operators
then have to reconstruct those decisions manually. That creates several failure modes:

- a correct payload can land at the wrong directory because the destination was typed directly;
- an interrupted copy can be restarted with incompatible rsync implementations or flags;
- an exit-zero copy can be mistaken for content verification;
- the destination can exist without a corresponding searchable content-location record;
- background work can run at normal priority on a shared destination;
- release promotion can become detached from the transfer receipt that delivered its bytes.

The result is two operational truths: files on disk and CHOAM's recorded state. Once those diverge,
Server Monitor and Sietch cannot reliably answer where content lives or whether a transfer is safe
to resume.

## Audit findings

### Fixed in 2.0.14.127

The `send` path already lowered the local rsync and SSH clients to nice level 19, but it did not
consistently lower the processes launched by SSH. Remote rsync, preflight shells, directory-tree
inspection, post-transfer hashing, and manifest reads and writes could therefore compete with
foreground work. The send pipeline now starts those remote processes through `nice -n 19`, and the
remote rsync path test locks that behavior in.

CHOAM also probes Homebrew rsync before the operating-system fallback. That matters when the two
machines ship incompatible rsync generations: CHOAM selects the resumable implementation on both
ends instead of making every caller rediscover a compatible command line.

### Open gaps

1. **COPY completion is weaker than MOVE completion.** MOVE verifies every destination file by
   size and SHA-256 before source deletion. COPY currently accepts rsync success plus an unchanged
   source, then writes manifests. A verified-transfer tool should not label COPY complete until the
   destination is independently content-verified or an equivalent authenticated receipt exists.

2. **Directory catalog registration is best effort and not a complete directory identity.** The
   current `send` registration path is file-oriented. A directory send can produce a CHOAM Merkle
   manifest, but that root is not yet committed as a first-class Sietch directory object with all
   member locations.

3. **Immutable release naming needs staging.** `choam send` preserves the source basename. A build
   directory named `site` or `m4` cannot be placed directly as `releases/<release-id>` without first
   creating a local staging directory whose basename is the release ID. An explicit destination
   name option would remove that extra local step while retaining verification semantics.

4. **Promotion is outside the transfer transaction.** CHOAM can deliver an immutable directory,
   but it does not atomically update a service's `current` pointer or verify application health.
   Project-owned deployment code must perform checksum verification, atomic promotion, restart,
   and route smoke tests after CHOAM finishes.

5. **Sietch registration is non-fatal.** A successful copy can finish even when catalog
   registration fails. That is appropriate for generic transport availability, but insufficient
   for workflows that require discoverability. Such workflows need a strict mode that fails the
   operation or leaves it in a reconciliation state until registration succeeds.

## Required operating model

For ordinary trusted-machine transfers:

1. Use `choam send` or its queue, not an ad hoc rsync command.
2. Keep local and remote work at nice level 19.
3. Treat the CHOAM result as delivery evidence, not service-deployment evidence.
4. For immutable releases, stage under the release ID, send that directory, independently verify
   the release checksum manifest, then atomically promote it with project-owned tooling.
5. If raw transport is unavoidable, record why CHOAM could not perform the operation and reconcile
   the destination into Sietch and transfer history before considering the work complete.

## Completion criteria for CHOAM as the sole primary path

CHOAM becomes sufficient without project-specific compensation when COPY has destination-authenticated
content verification, directory roots and members are registered durably, callers can name an
immutable destination object without staging, and a strict registration mode can prevent an
unindexed success. Until then, it is still the primary transport, while release promotion and the
remaining proof obligations stay explicit in the owning project's deploy procedure.
