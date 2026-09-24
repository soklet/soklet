# Skills verification

The latest application/client checkpoint is the
[standalone Skills example through Inspector](#2026-09-21-runnable-example-and-inspector-retrieval).
The sections below also retain the earlier private YAML corpus procedure and
implementation history; their scope statements apply to their dated checkpoints.

**Current scope decision — 2026-09-20:** complex lifetime-wide shared memory
accounting is deferred by the owner. Keep `fromFiles(files)`, ordinary immutable
getters, and bundle/parser/output safeguards plus proportionate active-catalog/
concurrent-work bounds. No public budget, forced detached inspection snapshots,
or GC-based reclamation is required before bundle work. References to those
requirements in the dated checkpoints below are historical and superseded.
The unused ledger and its 20 tests are preserved in
[deferred-memory](deferred-memory/README.md), outside build source roots.
After that move, all **207 active focused tests** pass on Java 17 and 26. A clean
Java 17 build confirms the deferred ledger is absent from production and test
class directories. The older 227-test checkpoint includes the 20 archived
experiment tests; no parser/path/URI tests were removed.

The corpus probe is an **offline, report-only** check of the private parser, not a public
Skills API, compatibility certification, or fuzz qualification. Exit zero means
the pinned corpus completed without implementation crashes; unsupported syntax,
invalid input acceptance, and semantic mismatches remain visible failures in the
report. No production dependency or Maven configuration is changed.

Prepare a local `yaml/yaml-test-suite` checkout at commit
`6e6c296ae9c9d2d5c4134b4b64d01b29ac19ff6f` (`data-2022-01-17`) and compile the
candidate Soklet classes. Run from the core checkout, with a new output directory:

```sh
sh verification/skills/run-corpus.sh "$JAVA_HOME" "$PWD/target/classes" \
  /path/to/yaml-test-suite /path/to/new-report-directory \
  /path/to/snakeyaml-engine-3.0.1.jar
```

The fifth argument is optional: SnakeYAML Engine is used only as a second,
test-only **normalized syntax-event** comparison, never as the sole oracle or an
object constructor. Obtain it separately; this script performs no downloads.
The runner compiles with `--release 17` and can run on Java 17 or 26 against the
same candidate classes. A 256 MiB heap bounds the test process.

`cases.jsonl` records every case's independent syntax, document-count, JSON-model,
reference, normalized-event, and expected-value result. `summary.json` includes the sorted case/input SHA-256 pin,
expectation hash, compiled core-class identity, runner/helper-class identity, reference
jar hash, JDK identity, verification limits, and classification counts. The
class directory must stay unchanged throughout the run; concurrent compilation
is detected and fails the run instead of publishing a potentially mixed identity.
JSON, multi-document JSON-reader, and syntax-event self-tests run before corpus execution. The
input digest is SHA-256 over each sorted relative case ID, NUL, `in.yaml` bytes,
NUL; the expected count is 402. Dirty tracked corpus files, a wrong checkout pin,
input/expectation hash mismatch, a different reference jar, or crashes produce a nonzero exit. Existing report
directories are refused, so a failed invocation cannot leave stale success.
The pinned SnakeYAML Engine 3.0.1 jar SHA-256 is
`0b638c8806112215e02e58e95cbd9e9a69d4bd540991f47553d053919994cc94`.

Syntax probing decodes the **whole YAML fixture** through `SkillSource` and feeds
the stream syntax parser directly: it does not confuse Skills frontmatter
delimiters with the YAML suite's document markers. Actual document counts are
checked against `test.event`, including zero-document streams. The JSON resolver
then runs separately for each document with the same explicit per-case work
budget, without carrying anchors across documents. Approved metadata restrictions
(string keys, duplicate rejection, unsupported tags, finite numbers and bounded
acyclic aliases) are distinguished from syntax rejection. Numeric equality uses
exact `BigDecimal.compareTo`, including inside nested objects and arrays.
The corpus's whitespace-separated `in.json` values are read with a bounded
splitter and individually validated by the strict JSON codec. Every document's
value and order are compared; JSON count mismatches remain visible. Absent
expected JSON or expected JSON outside the strict codec profile is explicitly
non-comparable. Expected JSON is corpus evidence,
not proof that every fixture shares the approved metadata model; mismatches
require review. The runner is not the aggregate-memory owner, an adversarial
fuzzer, a full frontmatter integration test, or a public qualification gate.

Before metadata resolution, the runner independently compares normalized events
against `test.event` and the optional maintained reference. This retains document
and collection structure, entry order and duplicate/complex keys, scalar styles
and decoded values, explicit tag identities, anchors, and aliases. Only explicit
document-marker flags, collection flow/block style, and source positions are
outside this comparison; comments/directives are not syntax nodes. Complete
presentation-event equality is not claimed. Corpus-invalid fixtures can contain
partial event traces and are not treated as complete event oracles. Adapter
errors are crashes, not policy rejections. Each comparison has its own status
and event-stream hashes, with a bounded first-difference preview. A JSON match
does not erase an event mismatch, and metadata-policy rejection does not skip
the syntax comparison.

## 2026-09-20 initial corpus checkpoint

The retained Java 17 and Java 26 reports under `results/` identify each tested
compiled core and give identical per-case results for all 402 cases. The same
source was compiled separately with each JDK, so compiled-class hashes differ:

- 94 invalid inputs rejected; none accepted; no implementation crashes.
- 201 valid cases match the comparable upstream JSON exactly.
- 22 valid YAML cases fail the approved JSON model (18 type restrictions,
  4 unsupported tags); 5 accepted cases have no comparable single JSON value.
- 78 valid cases still fail syntax parsing, including multidocument streams;
  these remain compatibility work, not a newly approved grammar restriction.
- 2 expected-value disagreements remain visible: `JEF9/02` and `L24T/01`.

The implementation fixes six previously accepted malformed cases, compact
single-pair flow mappings, punctuation in block plain keys, and the anchor-only
sequence-item bug that consumed its following sibling. Ordinary braced flow
mapping keys now accept multiline forms and do not inherit the compact-pair
single-line/1024-character limit. YAML 1.2.2 productions 144–149 differ from the
restricted compact-pair productions 152–155; the prior authored restriction was
incorrect. All resource limits still apply.

For both value disagreements, the input ends in spaces without a final newline.
The pinned expected JSON adds a terminal LF; Soklet and SnakeYAML Engine 3.0.1
preserve EOF instead. [YAML 1.2.2 block chomping, production 165](https://yaml.org/spec/1.2.2/#8112-block-chomping-indicator)
distinguishes EOF from a line-feed terminator. These are recorded as oracle
conflicts, not silently converted to passes. The maintained reference also
accepts 7 corpus-invalid cases and rejects 49 corpus-valid cases; its behavior
does not override the specification. The runner's optional reference comparison
at that checkpoint checked event-parser acceptance, not event-stream equality.

The 99 focused Skills tests separately cover the fixes, bounds, and two complete
pinned real Skills documents. This checkpoint does not satisfy the full
compatibility, coverage-guided fuzz, aggregate-memory, or public runtime gates.

## 2026-09-20 grammar implementation checkpoint

The subsequent reports in `results/2026-09-20-grammar-jdk17/` and
`results/2026-09-20-grammar-jdk26/` retain the same corpus/expectation pins and
identify separately compiled candidates. All 402 per-case outcomes agree:

- 94 invalid inputs rejected; none accepted; no implementation crashes.
- 241 valid cases match the comparable upstream JSON exactly.
- 39 valid YAML cases fail the approved JSON model (26 type restrictions,
  13 unsupported tags); 7 accepted cases have no comparable single JSON value.
- 19 valid-syntax rejections remain, all multi-document streams. Every
  corpus-valid single-document input now passes syntax parsing. This is a
  corpus result, not a claim of general YAML conformance.
- The same 2 physical-EOF expected-value conflicts remain unmodified.

This slice implements `%YAML`/`%TAG` and reserved directives, explicit document
headers, named and overridden tag handles, explicit/compact block keys and
values, property-only continuation, empty tagged/anchored nodes, and valid tab
separation. Root block scalars use parent indentation −1; dedented trailing
comments and explicit scalar indentation no longer corrupt their contents.
Colon-bearing alias names are not mistaken for mapping delimiters. New negative
regressions require separation between properties and nonempty flow collections
and prevent a property continuation from swallowing document markers.

Tag expansion checks work, individual text length, and aggregate text limits
before concatenation. Declaring a numeric YAML 1.x version does not switch the
approved core-resolution rules; other major versions reject. Unknown directives
are ignored without executing application hooks. Custom tags remain syntax
only and reject at metadata resolution.

One additional **tag-identity oracle conflict** is explicit: [YAML 1.2.2 §5.6](https://yaml.org/spec/1.2.2/#56-miscellaneous-characters)
requires preserving percent escapes, while its Example 6.26 and corpus `6CK3`
decode `%21` in the presented tag identity. The implementation follows §5.6,
preserving escapes; `!!%73tr` is not promoted to the standard string tag. The
runner at that checkpoint checked acceptance and JSON, not event/tag identity,
so its summary did not measure this disagreement. Event comparison must
keep it visible rather than silently treating custom-tag model rejection as
event equivalence.

All 140 focused Skills tests pass on Java 17 and 26 (41 added in this slice).
Multi-document parsing, complete event/differential coverage, coverage-guided
fuzzing, and aggregate ownership/reclamation remain open. Public Skills wiring,
production memory defaults, and runtime dependencies are unchanged.

## 2026-09-20 document-stream implementation checkpoint

The reports in `results/2026-09-20-stream-jdk17/` and
`results/2026-09-20-stream-jdk26/` exercise the private `parseStream` entry point
and the stream-aware runner. All 402 per-case outcomes agree across the JDKs:

- All 308 valid inputs syntax-parse with the expected document counts.
- All 94 invalid inputs reject; none are accepted; no implementation crashes.
- 261 cases match every expected JSON document exactly, including empty streams.
- 43 cases reject under the approved metadata model (27 type restrictions,
  16 unsupported tags); 2 accepted cases lack comparable JSON expectations.
- The same 2 physical-EOF value conflicts remain recorded, not counted as passes.

The parser handles bare/explicit/directive document transitions, repeated end
markers, empty streams and documents, and document-prefix BOMs. Directives reset
per document; anchors resolve only within their own document. One input/work/
node/text budget spans the entire stream; every empty document consumes a node,
which also bounds the immutable document list. A fresh directive table avoids
repeatedly scanning a retained large table for later small documents. Embedded
BOMs are rejected outside quoted content or stream prefixes.

The existing single-document parser rejects a second document before parsing it;
Skills frontmatter still uses that entry point and requires a mapping. Stream
qualification is not permission to load multiple metadata documents. All 164
focused Skills tests pass on Java 17 and 26 (23 stream tests plus one frontmatter
boundary regression added in this slice).

The previously recorded tag-identity conflict, full event-stream comparison,
coverage-guided fuzzing, and aggregate-memory ownership/reclamation remain open.
Zero corpus syntax gaps is not general YAML certification. No public Skills
API, runtime wiring, memory default, or production dependency changed.

## 2026-09-20 event comparison and fuzz-target checkpoint

The `results/2026-09-20-events-jdk17/` and `...-jdk26/` reports retain independent
syntax, JSON, and normalized-event outcomes. The 308 valid/94 invalid syntax
results and JSON counts are unchanged. Event comparisons show:

- Soklet vs corpus: **305 matches, 3 mismatches**. The only differences are the
  previously recorded `6CK3` percent tag identity and `JEF9/02` / `L24T/01` EOF
  values, now visible even when JSON resolution rejects a custom tag.
- Soklet vs reference: **258 matches, 1 mismatch** (`6CK3`) for the 259 valid
  cases accepted by both parsers.
- Reference vs corpus: **257 matches, 2 mismatches** (the EOF cases). The
  reference still rejects 49 valid inputs and accepts 7 invalid ones; neither
  acceptance nor an event disagreement automatically overrides the corpus/spec.

No new unexplained event divergence or adapter crash was found. All 164 focused
core tests still pass on Java 17 and 26. The existing fuzz module now contains
two bounded Skills targets, 16 authored seeds, and a curated-path assertion test;
focused seed replay passes 19 invocations on each JDK. Local Java 26 coverage-
guided smoke runs completed 1,469,393 stream executions and 1,436,404 frontmatter
executions (31 seconds each), without a finding. These targets check typed/
redacted failures, shared bounds, successful JSON round trips, document-local
aliases, and byte-exact source ownership; they are not a differential fuzz oracle.
See [the fuzz instructions](../../fuzz/README.md).

The initial sandboxed replay could not attach the Jazzer agent to its own JVM;
the permission-enabled replay and smoke runs succeeded. No dependency repin was
needed. The 24-hour coverage campaign, resolved oracle conflicts, shared-memory
ownership/reclamation, and public Skills implementation remain open. No long-run
qualification, nightly-registration change, or production API change is claimed.

## 2026-09-20 discrepancy disposition and private path foundation

The three mismatches have been independently reviewed against the normative
productions. **Keep the current parser behavior; do not rewrite expectations or
count these raw results as passes.** This is a local implementation disposition,
not an upstream erratum or a claim of complete YAML conformance:

- `6CK3`: preserve percent-escape spelling in tag identity. [§5.6](https://yaml.org/spec/1.2.2/#56-miscellaneous-characters)
  forbids expanding these escapes, and [§6.9.1](https://yaml.org/spec/1.2.2/#691-node-tags)
  concatenates prefix and suffix. Example 6.26 and the corpus conflict with
  that rule. Regression coverage now includes verbatim tags, directive prefixes,
  escape case, and rejection of encoded core-tag promotion.
- `JEF9/02` and `L24T/01`: do not invent LF at physical EOF. [Production 165](https://yaml.org/spec/1.2.2/#8112-block-chomping-indicator)
  distinguishes EOF from a line break; [production 70](https://yaml.org/spec/1.2.2/#64-empty-lines)
  requires a physical break for an empty line. Paired tests now show that adding
  an actual LF, CR, or CRLF changes the result as expected.

The upstream source fixtures [6CK3](https://github.com/yaml/yaml-test-suite/blob/main/src/6CK3.yaml),
[JEF9](https://github.com/yaml/yaml-test-suite/blob/main/src/JEF9.yaml), and
[L24T](https://github.com/yaml/yaml-test-suite/blob/main/src/L24T.yaml) still disagree;
the [1.2.2 errata page](https://yaml.org/spec/1.2.2/ext/errata/) supplied no resolution
when checked. No upstream issue, corpus change, repin, event normalization, or
parser behavior change was made for this disposition.

Private `SkillPaths` and `SkillResourceUris` implement the already-agreed path
and URI contracts without introducing bundle byte ownership or public APIs:

- Validate at most 512 logical keys, each at most 8,192 UTF-8 bytes, including
  scalar Unicode/NFC, exact uniqueness, traversal/separator/control restrictions,
  and the required root `SKILL.md`. Validation never reads content arrays or disk.
- Snapshot an immutable order: root first, then unsigned UTF-8 order, independent
  of input collection/map ordering.
- Validate an ASCII normalized hierarchical root and its already-validated
  bundle-name binding, then append canonical UTF-8 percent-encoded paths. Root
  URI spelling/object identity is preserved, and collision checks use `URI.equals`.
- Reject malformed UTF-8 URI escapes, encoded separators/dot segments, and
  decoded controls; preserve other URI identity distinctions rather than silently
  case-folding or decoding the stored root.
- Preflight both the 1,048,576-byte individual URI ceiling and an explicitly
  supplied total URI-text ceiling before constructing derived strings/maps.
  This is a per-projection bound, **not** aggregate-memory accounting or a default.

Cross-registration shared-file ownership/collisions, file-byte snapshots,
metadata field validation, manifests, public runtime wiring, and the shared
memory-owner/reclamation decision remain separate work. The existing corpus
reports remain historical evidence for their recorded class identities.

Verification for this slice: all **207 focused Skills tests** pass on Java 17
and 26 (41 new path/URI tests plus two new discrepancy controls). Coverage
includes inclusive count/byte limits, long-root/many-file amplification, strict
Unicode handling, `HashMap`/`LinkedHashMap`/`TreeMap` ordering, immutable outputs,
and redacted diagnostics. No parser behavior or fuzz target changed in this
slice, so the earlier corpus/fuzz results are not presented as new runs.

## 2026-09-20 private memory-ledger and ownership proposal

`SkillMemoryLedger` is a policy-neutral two-counter primitive, not a bundle owner.
It atomically reserves/replaces retained and transient charges, preserves both
on rejected admission, refunds once on internal release, and uses subtraction
to avoid `long` overflow. An immutable snapshot observes both counters under
the same lock. Negative charges and exhausted dimensions have fixed diagnostics.
Zero charges are legal primitive inputs, not free production bookkeeping.

The ledger neither measures memory nor decides when a payload is unreachable.
Its reservations hold no payload references, and their `close()` is not a public
lease or permission to release live immutable data. It is not wired to parser,
bundle, registration, catalog, inspection, or transport code. No lifetime policy,
numeric charge formula, default, or public API has been adopted.

The separate [ownership/API proposal](../../../SOKLET_MCP_SKILLS_MEMORY_API_PROPOSAL_2026-09-20.md)
recommends private owned roots, detached caller-owned inspection snapshots, and
conservative GC-based reclamation. It explicitly requests approval for the
two-argument factory and new inspection allocation/admission/ownership semantics.
Watching only a bundle cannot safely account for shared metadata children and
collection views that applications retain independently. The proposal is not
proof of detachment, reference tracking, or an aggregate-memory guarantee.

All **227 focused Skills tests** pass on Java 17 and 26. The 20 new ledger tests
include exact-capacity and maximum-long boundaries, atomic rejection/rollback,
immutable observations, 16-way simultaneous admission, and 64 close/resize races.
Concurrency checks use bounded latch/future waits rather than sleeps or GC timing.
Independent review found no ledger arithmetic or synchronization defect. These
results do not qualify the proposed allocation formula, detachment, or reclamation.

## 2026-09-20 immutable bundle and manifest foundation

Private `SkillBundle.fromFiles` now validates all logical keys, actual iteration
count (at most 512 files), and the 16-MiB raw total before copying content. It
parses the owned root snapshot, preserves complete original bytes, and caches
each file's raw size and SHA-256 digest once. Only requested file-byte inspection
is copied; paths, metadata, and manifest values are immutable ordinary objects.
There is no shared-budget owner, retained-lifetime accounting, or filesystem I/O.
Inputs must not be mutated during construction; later mutations cannot change
the bundle. A nested `SKILL.md` is an ordinary supporting file, not automatically
a separate registration.

`SkillDocumentMetadata` validates required name/description and recognized
optional fields while preserving unknown top-level values and authored strings.
The [Agent Skills name rule](https://agentskills.io/specification#name-field) and
[official i18n cases](https://github.com/agentskills/agentskills/blob/main/skills-ref/tests/test_validator.py)
support Unicode lowercase/uncased letters and numbers. This private profile
counts Unicode code points (64 name, 1,024 description, 500 compatibility),
rejects uppercase/titlecase names and invalid hyphen placement, and requires
strings for license/allowed-tools and string values in the nested metadata map.
It does not copy the demonstration validator's trimming, NFKC normalization,
unknown-field filtering, or extra nonblank-description rule. The pinned
[MCP binding](https://github.com/modelcontextprotocol/ext-skills/blob/41e7c66db2510a3e98d9614eb1998f6b970006d7/specification/stable/skills.mdx#frontmatter)
requires preserving the complete frontmatter.

`SkillManifest` binds the bundle to a validated root URI and emits one immutable
skill entry with complete frontmatter and every file's URI/digest/size, root first
then unsigned UTF-8 path order. It reuses cached digests and checks the whole
entry against the supplied JSON profile, including wrapper depth/nodes/output.
URI text is preflighted against that output ceiling before projection.

This is still private, unpublished construction. Per-file text/base64/MIME
representation, full JSON-RPC envelope and list-page deliverability, public
bundle/registration APIs, endpoint routing, and cross-registration ownership
remain product work. Accepting a raw private bundle is not a promise that every
file is already transport-deliverable. Parser behavior and fuzz targets did not
change; previous corpus/fuzz receipts are not new runs for these classes.

All **257 focused Skills tests** pass on Java 17 and 26, with zero failures,
errors, or skips (`-Dtest='Skill*Tests'`). The 50 additions comprise 16 metadata,
19 bundle, 14 manifest, and one end-to-end test covering both pinned real
documents. They cover byte/metadata identity, concurrent immutable inspection,
malformed map iteration, exact count/raw-byte and entry JSON limits, canonical
ordering/URI encoding, and redacted failures. No full-suite, public runtime,
host, or long-duration fuzz qualification is claimed by this focused run.

## 2026-09-20 public construction and canonical delivery checkpoint

`McpSkillBundle` and `McpSkillRegistration` now expose the approved factory,
builder and immutable views. The narrow internal bridge converts public JSON
metadata once and retains canonical text/base64 values once per bundle, not per
read. Bundle equality compares exact owned bytes without inspection copies;
cached digests support hashing, not an equality shortcut. Locale is optional,
cache policy defaults to private/zero TTL, and all diagnostic rendering is
redacted. No endpoint is published by construction.

The fixed filename-based representation table and parser profile are documented
in [Skills construction](../../MCP.md#skills-construction-in-progress). The
public factory adopts the same explicit YAML profile used by the corpus runner;
production JSON limits remain unchanged. Strict UTF-8 preserves BOMs/line
endings. Malformed recognized text falls back to canonical base64; oversized
valid text rejects rather than changing representation. Raw/encoded length
prechecks precede decoding/encoding. A nested and root file with the same final
filename and bytes have the same representation.

Registration validates canonical `resources/read`, `skills/get`, and atomic
one-entry `skills/list` envelopes, including conservative maximum TTL/private
scope overhead. As with existing framework startup checks, the request ID is
numeric zero. Endpoint/server metadata, worst-case automatic pages, actual
request IDs, authorization, and final caller-specific serialization are still
required runtime work. This is construction evidence, not HTTP-serving support.

All **330 selected tests** pass on Java 17 and 26: **291 Skills tests** and
**39 public API inventory/reflection/Javadoc tests**, with zero failures,
errors, or skips. Additions are 12 file-representation, 10 bridge/wire-profile,
12 public-value, and two reflection tests. The bridge suite uses the actual JSON
codec to reconstruct original bytes and verify manifest hashes/sizes, both
pinned real documents, parent/nested equality, and exact envelope byte/node/
depth boundaries. Run with
`-Dtest='Skill*Tests,McpSkill*Tests,McpPublicApiInventoryTests,McpPublicApiReflectionContractTests,McpPublicJavadocTests'`.
Four Phase 4 owners and the reviewed nested nullability layout are updated;
historical signature/corpus/fuzz receipts are unchanged. Broad parser/host/fuzz
qualification and final API refreeze are not claimed.

## 2026-09-20 endpoint ownership checkpoint

`McpSkillGroup` and endpoint standalone/group configuration now build a canonical
file-owner index with no filesystem work or authorization callbacks. Validation
covers listing-slot/URI/key uniqueness, complete descendant snapshots, exact
shared bytes and representations, 16 owners per file, conservative merged
caches, and ordinary exact/template resource collisions. Tests cover `URI.equals`
aliases, authority-root/no-authority ancestry, subscription-copy identity, and
the server/simulator guard against silently ignoring unwired Skills routes.

All **441 selected tests in 32 suites** pass on Java **17.0.20.1** and **26.0.1**,
with zero failures/errors/skips: 330 Skills tests and 111 API/builder/resource/
subscription/simulator regressions. This slice adds 8 group, 18 endpoint/index,
13 template-collision, one reflection, and one builder-reset test. The first
sandboxed run encountered loopback bind restrictions; the final runs used local
test-server access. Selection:

```text
-Dtest='Skill*Tests,McpSkill*Tests,McpPublicApiInventoryTests,McpPublicApiReflectionContractTests,McpPublicJavadocTests,McpBuilderResetContractTests,McpAtomicBuilderSetterTests,McpResourceRegistrationTests,McpResourceProtocolTests,McpResourcePublicRuntimeTests,McpSubscriptionServerConfigurationTests,McpSimulatorPublicRuntimeTests'
```

No live Skills route, automatic multi-entry page preflight, locale selection,
access policy, aggregate/lifetime memory guarantee, or host/fuzz qualification
is claimed. Ordinary resource runtime behavior remains covered by the selected
regressions; HTTP compression was not changed.

## 2026-09-20 policy and selection checkpoint

The public access/discovery policy, variant selector/context, and server
configuration are implemented. The private request-policy core filters access
before discovery, preserves canonical candidate identity and ordering, applies
the approved singleton exclusions, and freshly checks exact skills and all
shared-file owners. Grant/error permutations, revocation, null/foreign callback
results, shared cancellation/deadline checks, and interruption/redaction are
covered. Server/simulator configuration retains callback identities and requires
an explicit selector for declared multivariant groups.

All **497 selected tests in 37 suites** pass on Java **17.0.20.1** and **26.0.1**,
with zero failures/errors/skips: 367 Skills tests and 130 API/adjacent regressions.
New Skills coverage is 9 policy/context, 18 evaluation-core, and 10 server
configuration tests; reflection and simulator configuration inventories are
updated. Selection:

```text
-Dtest='Skill*Tests,McpSkill*Tests,McpPublicApiInventoryTests,McpPublicApiReflectionContractTests,McpPublicJavadocTests,McpBuilderResetContractTests,McpAtomicBuilderSetterTests,McpCatalogAccessPolicyTests,SimulatorConfigDerivationTests,McpLocaleSupportTests,McpResourceRegistrationTests,McpResourceProtocolTests,McpResourcePublicRuntimeTests,McpSubscriptionServerConfigurationTests,McpSimulatorPublicRuntimeTests'
```

The core receives an existing feature carrier, bounded preferences, and overall
deadline; it does not create executors or negotiate localization. Actual admitted
dispatch, pagination/results, request-language sharing, cache/Vary behavior and
wire serialization are not yet connected. The server routing guard remains;
this is not end-to-end Skills runtime or host qualification.

## 2026-09-20 pagination and live runtime checkpoint

The earlier construction-only checkpoints are superseded: the Skills startup
guard is removed. Programmatic endpoint configuration now reaches admitted
`skills/list`, `skills/get`, and authorized canonical `resources/read` dispatch.
The extension/base Resources capability is advertised without leaking file
descriptors into ordinary listings. Automatic pages preflight all listing slots
before filtering; custom pages preserve exact configured identity, first-page
selection order, present-empty cursors, and fresh continuation authorization.

Eleven real-listener scenarios cover manifest/raw-text/base64 delivery,
Skills-only resource-list isolation, shared-file grants and revocation, group
selection with independent Vary/private-cache handling, first/continuation page
phase rules, access/discovery revocation without reselection, TTL clamps, UTF-8
cursor boundaries and redaction, ordinary custom-list bypass rejection,
canonical file interception after authorization, invalid page identity/order,
and operation-specific localization cursors without Content-Language relabeling.
Six endpoint preflight tests and two additional bridge tests cover independent
byte/node variant maxima, server metadata, actual request IDs and page metadata.

The broader regression selection passes in **265 suites** on both JDKs:
**2,136 tests on Java 17** (zero failures/errors; one expected virtual-thread
smoke-test skip) and **2,151 tests on Java 26** (zero failures/errors/skips).
The count difference includes JDK-dependent parameterization. Selection:

```text
-Dtest='Mcp*Tests,Skill*Tests,SimulatorConfigDerivationTests'
```

This checkpoint does not claim real Skills-host interoperability, updated
historical API/release receipts, new parser-corpus results, or long-running fuzz
qualification. No complex shared-memory API or HTTP compression change is added.

## 2026-09-21 runnable example and Inspector retrieval

The [runnable example](../../examples/skills/README.md) publishes one synthetic
Skill with Markdown, a multibyte UTF-8 CSV, and binary contents. It compiles
against only the packaged candidate's public API. The unmodified Inspector
**2.7.0** CLI passes all nine client probes on Java **17.0.20.1** and **26.0.1**,
using Node **26.5.0** and explicit modern HTTP. Retained receipts:

- [Java 17](results/2026-09-21-example-jdk17/receipt.json)
- [Java 26](results/2026-09-21-example-jdk26/receipt.json)

Each run records 27 checked exchanges: discovery, Skill listing/get, ordinary
resource-list isolation, exact text/binary reads, and both list/get verification.
Inspector verifies all three sizes/digests and the complete root frontmatter,
including unknown nested metadata. A separate one-byte binary corruption produces
exactly one digest mismatch and exit 7. Candidate/source/dependency identities
remain unchanged, the listener shuts down cleanly, and private client state is
removed. All 14 harness self-tests pass. The initial development attempt was
correctly rejected for source drift while the harness was still being edited;
these two final runs use frozen inputs.

See the [runner instructions](../interoperability/skills/README.md) for exact
commands and pins. This is actual-client retrieval/integrity evidence, not agent
activation, consent, execution, paginated/authorized/localized host qualification,
or release qualification. Production code and HTTP compression are unchanged by
this example slice; the prior broader Java regression results remain separate.

## 2026-09-24 fuzzing disposition

The owner stopped the two local Skills coverage-guided fuzz campaigns because
they were affecting workstation performance. Both exited cleanly on interrupt
without a reported finding; neither completed its planned 24-hour duration.
The owner accepted the existing CI nightly Skills fuzz slots instead of that
local duration requirement. This changes only the fuzzing evidence expected for
P4-Q: successful CI run receipts and any findings still need review, and the
remaining YAML boundary, operational, and host checks are still open. The
scheduled workflow runs on `master`; a feature commit needs the existing manual
dispatch to get candidate-specific CI evidence before merge.
