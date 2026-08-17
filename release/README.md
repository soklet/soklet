# Release-candidate validation

The release validator is an explicit-dispatch, fail-closed skeleton for the
Soklet 3.6.0 candidate. It is deliberately separate from ordinary CI: its input
is a full candidate commit SHA, the workflow checks out that exact commit, and
any repository change requires a new commit and a complete new run.

The current manifest is intentionally **not runnable yet**. It records exact
committed pins without treating uncommitted sibling work as evidence:

- ToyStore's local 3.6 MCP migration passes its focused tests but still needs a
  reviewed commit to replace the manifest's pre-migration pin;
- the current `soklet-otel` and `soklet.com` migrations are uncommitted and are
  therefore not represented by their pinned commits;
- Barebones and both servlet pins still require candidate validation.

The checksum-pinned TypeScript and Go interoperability harnesses are checked in,
pass against the local snapshot candidate, and are `READY`; the release run must
still execute them against the exact immutable candidate before either can
produce PASS evidence.

`scripts/validate-release-candidate.sh` stops on these statuses before building.
Change a gate to `READY` only in the same reviewed commit that supplies its
immutable pin and working validation entry point. A branch, tag, dirty sibling
checkout, or local substitution is never accepted as a pin.

## Contract

Once every gate is ready, the validator:

1. requires a clean checkout whose `HEAD` is the supplied 40-character SHA;
2. verifies the checked-in manifest and exact Corretto, Maven, Node, npm, and
   Go toolchains, installing both Corretto JDKs, Maven, Node/npm, and Go from
   versioned, checksum-pinned upstream distributions;
3. performs one unsigned JDK 17 `clean verify` build and hashes the POM plus the
   main, sources, and Javadocs JARs;
4. installs the already-built POM and main JAR with the pinned `install-file`
   goal into a fresh isolated Maven repository and byte-compares the result;
5. runs the checked-in `release` soak profile behind a 3,600-second outer
   timeout and requires its Markdown and Surefire verifier;
6. runs official conformance in release mode against the exact candidate bytes,
   requires `IMMUTABLE_RELEASE_CANDIDATE`, `releaseCandidateEvidence: true`, and
   terminal `PASSED` evidence, then compiles and runs a library-neutral
   localization provider against the candidate JAR alone;
7. checks out every downstream at its exact manifest commit and invokes its
   candidate hook, including default/candidate servlet matrices, candidate-only
   ToyStore and OpenTelemetry 3.6 migrations, Barebones startup/probe/termination,
   website generated-artifact cleanliness, and the interoperability entry
   points; ToyStore alone runs under the separately pinned Corretto 25
   compiler/runtime because its POM requires release 25; and
8. rehashes the candidate and assembles a canonical evidence manifest only
   after the exact complete gate set has PASS evidence.

The soak module compiles source at the candidate commit, as documented in
`soak/README.md`; it does not claim to consume the candidate JAR. Artifact-based
gates use the checksum-matched JAR or the isolated Maven repository.

The candidate build and every gate other than ToyStore retain the exact
Corretto 17 default. The workflow installs ToyStore's exact Corretto 25 archive
first and the candidate Corretto 17 archive last; the validator verifies the
full Corretto vendor build, runtime version, compiler version, and Maven runtime
before any build, then supplies the Corretto 25 home only to ToyStore's single
candidate-version Maven invocation. ToyStore and `soklet-otel` intentionally
have no default compatibility leg: both migrated sources target the new 3.6
API and currently default to an unpublished snapshot, while the servlet
integrations retain their released-default and candidate-version legs. Every
Maven leg sets `failIfNoTests`, then independently verifies and retains
nonempty Surefire XML with at least one executed test and zero failures or
errors. Both Java archive identities and checksums are retained as gate
evidence. No `current`, `latest`, branch, or feature-only Java resolver is used.

The workflow has no publish or signing authority. Promotion remains a separate
maintainer-authorized operation and must consume the completed evidence
manifest without rebuilding. The release manifest pins the exact
`scripts/release-promotion.mjs` helper and
`scripts/promote-release-candidate.sh` wrapper paths and SHA-256 values;
configuration validation fails if either checked-in tool drifts from its
reviewed pin. The offline, upload, status-recovery, and published-verification
procedure is documented in `release/PROMOTION.md`.

## Checks

The dependency-free structural tests are safe to run without Maven or network
access:

```sh
node scripts/release-validation-evidence.mjs \
  validate-config release/release-validation-manifest.json
node scripts/release-validation-self-test.mjs
bash -n scripts/validate-release-candidate.sh
bash -n release/scripts/install-pinned-corretto-linux-x64.sh
```

The first command validates the currently recorded pins and statuses. Adding
`--require-ready` is expected to fail until every blocker above is resolved.

After the final candidate commit exists, dispatch
`.github/workflows/release-validation.yml` from that commit and supply the same
full SHA as its input. A successful run
uploads the four unsigned candidate inputs, release-soak evidence, official
conformance evidence, and `target/release-validation/evidence/`. Missing files,
extra or missing gate rows, failed/skipped suites, checksum drift, a changed
`HEAD`, or a workflow SHA mismatch prevents final evidence assembly.
