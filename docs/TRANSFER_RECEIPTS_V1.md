# Transfer receipts V1

This is an additive, data-only contract. It does not alter the live transfer queue, daemon, CLI,
or Server Monitor queue-status schema. Legacy queue `COMPLETED` is still projected as
`VERIFYING_FILES`, never delivered.

`TransferReceiptV1` distinguishes `COMMAND_ACCEPTED`, `QUEUE_ADMITTED`, `DEFERRED`, `ACTIVE`,
`VERIFYING_BYTES`, `VERIFYING_FILES`, `DESTINATION_COMMITTED`, `COMPLETED`, `FAILED`, and
`CANCELLED`. It carries opaque bounded transfer/attempt/observation IDs, authority labels, route
generation and fingerprint, content expectations, timestamps, failure code, and prior-attempt
summaries. Labels and fingerprints are deliberately not endpoints, paths, accounts, or hostnames.

Timestamps are `Instant` values and transition code records admission, start, verification, commit,
completion, failure, cancellation, and the latest observation. Observations must be monotonic. A
receipt persists a bounded immutable recent-ID list plus sequence watermark: writers must persist
both atomically with the receipt or duplicate suppression is not durable across a crash.

Completion requires a coherent `DESTINATION_COMMITTED` lineage: admission, start, verification,
destination-commit, completion, and last-observed timestamps must all be present and monotonic.
Completion also requires a destination-authoritative proof and a caller-supplied
`DestinationEvidenceVerifier`. The proof declares a supported V1 scheme, destination authority-key
fingerprint, transfer ID, attempt ID, route generation/fingerprint, and either a canonical receipt
SHA-256 digest/signature or a local authoritative-probe attestation. The verifier must
cryptographically authenticate those binding fields as part of the proof; matching fields alone are
not authoritative. The default Manager decoder supplies no verifier and therefore never reports
delivery. Only a verifier that authenticates the proof can permit `COMPLETED`.

At least one content expectation (bytes, files, or hash) is required. Declared and observed hashes
currently support only exact canonical `SHA-256` lowercase hexadecimal values. Counts and route
generation are nonnegative. Duplicate/conflicting hash algorithms, blanks, unknown algorithms,
and invalid identifiers are rejected.

Restarting is allowed only from `DEFERRED` or `FAILED`. It returns a new receipt value with a new
attempt ID and retains a durable summary of the former attempt; the prior receipt is unchanged.
An allowed route change is only a transition to `DEFERRED`; it clears destination evidence so that
evidence from the old route cannot be reused.

## Manager decode seam

`ManagerTransferProjection.decode` returns `DecodeResult(views, rejections)` and never echoes input
payloads. It tolerates malformed JSON and bad entries with sanitized rejection codes, supports the
current queue-status form (`schema: 1`, lowercase statuses), the earlier Manager queue form, a bare
legacy queue array, a single V1 receipt, and the `choam.transfer-receipts.v1` envelope. Unknown,
mixed, malformed, or child schemas are rejected per entry or envelope. Legacy failure text is
always replaced with `LEGACY_FAILURE_DETAIL_REDACTED`.

The later live integration must write receipt observations separately from the legacy queue, retain
the observation watermark/IDs durably, obtain a real destination proof, and inject the verifier at
the trusted Manager boundary.

## Queue producer tranche

`QueueReceiptStore` keeps private `queue_transfer_receipts` rows in the existing queue SQLite
database. Each row contains the complete V1 receipt JSON plus a private version. A zero-wait,
optimistic compare-and-swap replaces the complete JSON (including its bounded applied-observation
IDs and sequence watermark) in one statement. Reads and reduction happen before that minimal
write lock; a concurrent change or lock contention is only sanitized receipt-unavailable, never a
legacy queue write dependency. Malformed rows are never replaced or reopened. Queue clearing and
normal queue expiry only remove legacy queue rows, never receipt rows.

`QueueProcessor` admits a receipt only once local byte/file expectations are available, marks it
`ACTIVE` only after `SourceGuard` ownership is acquired, and emits fixed-code `DEFERRED`, `FAILED`,
or `CANCELLED` facts. No path, account, host, endpoint, route address, queue error, or process
output is placed in a receipt. Each newly admitted or reconciled attempt receives a fresh opaque
random route fingerprint that differs from every current and retained prior receipt fingerprint;
it is never derived from queue IDs, retry counts, paths, users, hosts, endpoints, or route
addresses. Route generation is allocated monotonically from every persisted
current/prior receipt route with checked overflow, so a legacy manual reset to retry zero still
creates a distinct generation and fingerprint. Retry/reopen clears evidence. An rsync exit of zero
progresses only through
`VERIFYING_BYTES` and `VERIFYING_FILES`; this tranche never writes destination evidence,
`DESTINATION_COMMITTED`, or `COMPLETED`.

Receipt persistence is best-effort additive isolation, not a queue dependency. Initialization,
schema and I/O errors, malformed stored rows, and lock contention yield only a sanitized internal
receipt-unavailable condition; they never fail, defer, retry, cancel, or otherwise change legacy
queue processing. No observation is claimed when it was not durably stored. Terminal receipt rows
remain fail-closed. When a legacy reset or stale-running recovery makes an `ACTIVE` or verifying
receipt claimable again, the producer first durably records `STALE_ATTEMPT_RECONCILED` as a
deferred old attempt and then creates a fresh attempt/route. Receipt reconciliation adds no
additional retry solely because of receipt state; the established legacy stale-running recovery
may apply its own retry policy unchanged.

For a new or reopened receipt attempt, admission reads current `BasicFileAttributes` with
`NOFOLLOW_LINKS` before any durable expectation reuse and accepts only `isRegularFile`.
Directories, symbolic links, FIFOs, sockets, devices, and unreadable sources are ineligible
because root metadata cannot establish a stable transferable file. A valid current receipt may
reuse its durable expectations on retry only after that current-source revalidation, while legacy
processing of all source kinds continues unchanged. The already-present-at-destination legacy
success path
records `VERIFYING_BYTES` and `VERIFYING_FILES` only after matching checksums or an authoritative
hash comparison. A metadata-only COPY remains a legacy success but records sanitized `DEFERRED`
with `AUTHORITATIVE_VERIFICATION_REQUIRED`, never verification. Receipt gaps are therefore
expected whenever this optional side channel is unavailable or expectations cannot safely be
established; they are not evidence of a missing legacy transfer outcome.

Receipt and legacy queue updates currently use separate transactions on the same database. This is
intentional: the established queue mutations and schema-1 status snapshot remain untouched. A
future integration may introduce a single cross-table queue-state/receipt transaction only after
its queue lifecycle ownership is widened and reviewed; callers must not infer that it exists now.
