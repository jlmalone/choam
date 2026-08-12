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

Completion requires a destination-authoritative proof and a caller-supplied
`DestinationEvidenceVerifier`. The proof declares a supported V1 scheme, destination authority-key
fingerprint, and either a canonical receipt SHA-256 digest/signature or a local authoritative-probe
attestation. The default Manager decoder supplies no verifier and therefore never reports delivery.
Only a verifier that authenticates the proof can permit `COMPLETED`.

At least one content expectation (bytes, files, or hash) is required. Declared and observed hashes
currently support only exact canonical `SHA-256` lowercase hexadecimal values. Counts and route
generation are nonnegative. Duplicate/conflicting hash algorithms, blanks, unknown algorithms,
and invalid identifiers are rejected.

Restarting is allowed only from `DEFERRED` or `FAILED`. It returns a new receipt value with a new
attempt ID and retains a durable summary of the former attempt; the prior receipt is unchanged.

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
