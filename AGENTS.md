# AGENTS.md

Apply any machine-level instructions first, then this repository file.

## Purpose

CHOAM is a Kotlin/JVM toolkit for verified, resumable file and SQLite transfer across
machines. It uses Sietch for content-addressed indexing and publishes queue state consumed
by Server Monitor.

## Public-content contract

Treat the complete tracked tree, Git history, release artifacts, CLI help, tests, and
repository metadata as public.

- Name supported technologies and protocols when technically relevant.
- Use neutral machines such as `workstation`, `server-a`, and `server-b`.
- Use synthetic paths, datasets, databases, and content examples.
- Never track real hostnames, tailnet addresses, SSH users, mount labels, private project
  names, workload names, media inventories, tracker details, credentials, recovery
  datasets, or cross-project storage topology.
- Legal copyright and GitHub-linked commit identity are intentional public identity.
- Keep private topology and operational history in ignored `AI.local.md` and
  `ROADMAP.local.md`. Runtime values belong in `~/.choam/config.json` and other untracked
  configuration.
- Do not put secrets, SSH keys, Keychain values, passwords, or tokens in local overlays.

## Compatibility

- Preserve the queue-status schema consumed by Server Monitor.
- Coordinate `sietch-core` API changes with the public Sietch repository.
- Destructive data operations remain explicit, reversible where possible, and verified
  before source deletion.
- Run `./gradlew test` and `contrib/autodrain/test.sh` after relevant changes.
- Read `docs/TRUSTED_MACHINE_CONTEXT.md` before propagating private context.

## Release

- Build release artifacts from committed source.
- Scan CLI help and the distribution archive for private context before publishing.
- Keep `VERSION` monotonic. Its final component is the release build number and must be
  incremented explicitly; it does not depend on rewritable Git history.
- Tag a release with the first three components, for example `VERSION` `2.0.11.123` uses
  tag `v2.0.11`.
- Keep release binaries in GitHub Releases, not in Git.
