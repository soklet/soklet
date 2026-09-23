# Soklet Fuzz Tests

This module contains Jazzer fuzz targets for Soklet's hand-rolled parsers. It
is intentionally separate from the main Maven reactor so fuzzing dependencies
stay out of the published `soklet` artifact.

The module uses Jazzer 0.30.0 with JUnit 6.1.3 on Java 17 or newer. The JUnit
BOM aligns the API, parameterized-test support, engine, launcher, and Platform
commons modules, including Jazzer's transitive dependencies. Upgrading only
the explicit API/engine/launcher dependencies leaves older Platform classes
on the test classpath and can fail before any corpus input is executed.

## Running Locally

Replay the checked-in corpus:

```sh
mvn -f fuzz/pom.xml test
```

Run a short coverage-guided fuzzing session for one target:

```sh
JAZZER_FUZZ=1 mvn -f fuzz/pom.xml \
  -Dtest=RequestParserFuzzTest#parseIncrementalRequestOnlyRejectsWithDeclaredExceptions \
  -Djazzer.max_duration=30s \
  test
```

The current targets are:

- `RequestParserFuzzTest`
- `DefaultMultipartParserFuzzTest`
- `HttpDateFuzzTest`
- `ParameterizedHeaderValueFuzzTest`
- `MediaRangeFuzzTest`
- `QueryFormatFuzzTest`
- `ResponseCookieFuzzTest`
- `TraceContextFuzzTest`
- `McpJsonCodecFuzzTest`
- `McpJsonRpcEnvelopeCodecFuzzTest`
- `McpMirroredHeaderCodecFuzzTest`
- `McpToolSchemaProfileFuzzTest`
- `McpCursorValidatorFuzzTest`
- `McpRequestStatePlaintextCodecFuzzTest`
- `McpSimulationCaptureFuzzTest`
- `McpLocalizationFuzzTest`
- `SkillYamlFuzzTest`

These 17 classes expose 21 coverage-guided `@FuzzTest` methods. The existing public MCP targets
use production parser, compiler, evaluator, validation, and simulator-capture
entry points with deterministic configuration. The Profile 1 target bounds a
single fuzz input to 64 KiB and uses a literal `---INSTANCE---` line to split a
schema document from an optional instance. The simulator target likewise
bounds input to 64 KiB, interprets at most 64 actions with payloads no larger
than 256 bytes, and derives item and cumulative-byte limits of 1..16 and
1..4,096. Its six synthetic ASCII seeds raise the MCP corpus from 21 to 27 and
exercise JSON completion, SSE terminal duplication and coalescing, item-first
and cumulative-byte rejection, cancel idempotence, and first-terminal
stability. This coverage complements the exact production-limit unit tests; it
does not claim exhaustive fuzzing at every configured or hard maximum.

`SkillYamlFuzzTest` exercises the private Skills stream parser/resolver and the
end-to-end UTF-8/frontmatter pipeline, with 16 small authored seeds and a separate
curated-path assertion test. Input is bounded to 16 KiB, syntax depth to 32,
shared nodes to 2,048, and work to 1,000,000 units. These are explicit exploration
settings, not public or aggregate memory defaults. Only `SkillYamlException`
is an expected rejection; other exceptions and failed successful-value JSON
round trips are findings. Frontmatter checks original bytes and defensive copies.

Focused replay and local smoke runs:

```sh
mvn -o -f fuzz/pom.xml -Dtest=SkillYamlFuzzTest test
JAZZER_FUZZ=1 mvn -o -f fuzz/pom.xml \
  -Dtest=SkillYamlFuzzTest#streamParsingAndResolutionRemainTypedAndBounded \
  -Djazzer.max_duration=30s test
JAZZER_FUZZ=1 mvn -o -f fuzz/pom.xml \
  -Dtest=SkillYamlFuzzTest#frontmatterRemainsTypedBoundedAndByteExact \
  -Djazzer.max_duration=30s test
```

Jazzer needs permission to attach its agent to the local test JVM. A denied
attachment is a harness failure, not parser evidence. The initial Skills
checkpoint passed 19 seed/test invocations on Java 17 and 26 and two local
Java 26 smoke runs; it does not satisfy the planned 24-hour qualification.

## Corpus Policy

Checked-in inputs under `src/test/resources/**/<FuzzTest>Inputs/<method>/`
are curated regression seeds. They are reviewed, named, and should remain small
enough for fast deterministic replay on every PR and push.

The protocol-neutral fixtures under
`src/test/resources/com/soklet/json-corpus/` were retained from the removed MCP
codec for the greenfield JSON implementation. The fuzz-module resource setup
attaches that one byte-exact corpus to both `McpJsonCodecFuzzTest` methods at
build time, without duplicating the source fixtures. Their compact checksum
manifest is verified by the main codec regression suite and with:

```sh
node scripts/verify-json-corpus.mjs
```

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

MCP corpus seeds are synthetic protocol values only. They must not contain
captured requests, protected state from a deployment, secrets, credentials,
raw trace context, or other production data. Passing corpus replay is a parser
and validator regression gate, not a comprehensive privacy, security, or
release-readiness result.

## CI Behavior

Pull requests and pushes run deterministic corpus replay with:

```sh
mvn -B -ntp -f fuzz/pom.xml test
```

The scheduled nightly run, or a manual workflow dispatch, uses a matrix with
one Maven invocation per `@FuzzTest` method. Jazzer's JUnit integration runs
only one coverage-guided fuzz test per JVM when `JAZZER_FUZZ=1`, so each target
needs its own matrix slot. The current matrix has 21 slots, runs each target for
five minutes, and bounds each job to 15 minutes. Each slot restores the latest
generated Jazzer corpus, runs coverage-guided fuzzing, uploads artifacts, and saves a
target-specific corpus cache under a run-specific key. The key rotates on every
run so nightly exploration can compound over time; restore keys keep each
target seeded from the newest available corpus for the branch.

The two private Skills targets now have dedicated nightly slots and registered
release-history receipts. Each nightly slot runs for five minutes; this does
not replace the separately required 24-hour Skills campaign. The expanded
history contract needs a fresh complete run for every registered target on
each of its required consecutive dates. Local smoke success is not evidence
that the nightly or long-duration gates ran.
