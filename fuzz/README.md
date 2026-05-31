# Soklet Fuzz Tests

This module contains Jazzer fuzz targets for Soklet's hand-rolled parsers. It
is intentionally separate from the main Maven reactor so fuzzing dependencies
stay out of the published `soklet` artifact.

## Running Locally

Replay the checked-in corpus:

```sh
mvn -f fuzz/pom.xml test
```

Run a short coverage-guided fuzzing session for one target:

```sh
JAZZER_FUZZ=1 mvn -f fuzz/pom.xml \
  -Dtest=McpJsonCodecFuzzTest#parseArbitraryBytesOnlyRejectsWithIllegalArgumentException \
  -Djazzer.max_duration=30s \
  test
```

The current targets are:

- `McpJsonCodecFuzzTest`
- `RequestParserFuzzTest`
- `DefaultMultipartParserFuzzTest`

## Corpus Policy

Checked-in inputs under `src/test/resources/**/<FuzzTest>Inputs/<method>/`
are curated regression seeds. They are reviewed, named, and should remain small
enough for fast deterministic replay on every PR and push.

Generated fuzzing output is intentionally ignored:

- `fuzz/target/`
- `fuzz/.cifuzz-corpus/`
- `fuzz/src/test/resources/**/crash-*`

When Jazzer finds a real crash, do not commit the raw `crash-*` file directly.
First confirm the root cause, fix it, and then promote the reproducer into one
or both of:

- a focused unit/regression test next to the affected parser
- a named corpus seed that describes the behavior, such as
  `incomplete-object.json` or `unnamed-before-named.multipart`

Raw generated corpus entries are useful for exploration, but curated seeds are
the auditable gate.

## CI Behavior

Pull requests and pushes run deterministic corpus replay with:

```sh
mvn -B -ntp -f fuzz/pom.xml test
```

The scheduled/manual nightly job restores the latest generated Jazzer corpus,
runs coverage-guided fuzzing, uploads artifacts, and saves a new corpus cache
under a run-specific key. The key rotates on every run so nightly exploration can
compound over time; restore keys keep it seeded from the newest available corpus
for the branch.
