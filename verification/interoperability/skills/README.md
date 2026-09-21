# Skills example through the released Inspector client

This runs the [standalone Skills example](../../../examples/skills/README.md)
against a packaged candidate JAR, then invokes the **unmodified Inspector
2.7.0 CLI**. No SDK substitute or custom HTTP call is counted as client evidence.
The client uses explicit modern HTTP (`2026-07-28`) and isolated memory-only
secret storage. No accounts, stored credentials, user configuration, installs,
upstream patches, browser, or external server are involved.

Use Node 26.5.0, JDK 17+, and the original dependency installation matching the
existing [Inspector package/lock review](../inspector/dependency-review.md).
The runner checks the complete installed tree against its original reviewed
identity; an auth-patched installation is deliberately rejected.

```sh
node verification/interoperability/skills/run.mjs \
  --candidate-jar /absolute/path/to/soklet-candidate.jar \
  --candidate-pom /absolute/path/to/original-pom.xml \
  --java /absolute/path/to/jdk/bin/java \
  --dependencies /absolute/path/to/original-locked-install \
  --work-dir /absolute/path/to/new-run-directory
```

The work directory must be new and outside source directories. The candidate
POM must match its embedded JAR copy. Compilation uses only the candidate JAR,
`--release 17 -proc:none -Xlint:all -Werror`; `jdeps` rejects internal/missing
dependencies. The listener and observer bind only IPv4 loopback on ephemeral
ports. Each child has a timeout and output bound; all children are supervised
and the example must report clean shutdown. Private per-run client state is
removed; the candidate, compiled classes, sources and dependency tree are
rechecked before success. The retained receipt contains only static identities,
structural wire observations, and checked outcomes, not raw responses.

Nine client invocations exercise:

- List, exact get, and an empty ordinary resource listing.
- Byte-exact root Markdown, CSV, and binary reads, checked against the example.
- Both `skills/list --verify` and `skills/get --verify`: one fully verified
  manifest, all three files, byte sizes/SHA-256, and matching root frontmatter.
- A separate negative control: the observer flips one binary byte after Soklet
  responds. Inspector must report exactly one digest mismatch and exit 7.
  No normal response or candidate artifact is modified.

All observed requests must carry modern protocol metadata, mirrored method and
Skills capabilities, with no initialization or session state. Normal wire
responses are forwarded unchanged. This does not claim client extension-OFF
behavior (the pinned CLI has a known toggle limitation), agent activation or
consent, script execution, paginated/authorized/localized host qualification,
all YAML/name-profile compatibility, or a release-wide conformance pass.

Run the small harness checks with:

```sh
node --test verification/interoperability/skills/*-self-test.mjs
```
