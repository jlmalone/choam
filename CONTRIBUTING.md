# Contributing

CHOAM accepts focused changes to verified transfer, queueing, content indexing, federation,
and operator diagnostics.

## Development setup

Install JDK 21 and clone CHOAM beside the public Sietch repository:

```bash
git clone https://github.com/jlmalone/sietch.git
git clone https://github.com/jlmalone/choam.git
cd choam
./gradlew test
contrib/autodrain/test.sh
```

## Safety and privacy

- Preserve source data until destination verification succeeds.
- Keep destructive operations explicit and narrowly scoped.
- Use synthetic machines, paths, databases, and content in tests and documentation.
- Never submit live CHOAM config, queue databases, sync history, catalogs, diagnostics, or
  private network details.
- Preserve versioned JSON contracts or coordinate an intentional schema transition.

Before opening a pull request:

```bash
git diff --check
./gradlew test
contrib/autodrain/test.sh
```

Explain the failure mode, safety invariant, compatibility impact, tests, and any manual
network validation still required.

Maintainers publish releases by incrementing the final, monotonic build component in
`VERSION` and tagging the first three components. For example, `2.0.11.123` is released
from tag `v2.0.11`.
