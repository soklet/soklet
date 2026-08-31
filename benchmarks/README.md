# MCP release benchmark harness

The ordinary benchmark module still builds and runs the existing exploratory
JMH benchmarks. Its release-specific path additionally compares the exact
released `com.soklet:soklet:3.5.1` JAR with the exact candidate
`com.soklet:soklet:4.0.0` JAR. Each implementation is loaded in an isolated
class loader; neither comparison leg resolves Soklet classes from the harness
class path.

The candidate workflow performs the long-running measurements and writes a
canonical draft plus every raw JMH result. It intentionally does not call the
draft release evidence. The successful workflow log prints the SHA-256 of the
exact canonical draft. A project owner must inspect the retained artifact,
record that exact digest in the durable review, and supply both the reviewed
digest and durable sign-off reference during finalization:

```text
node scripts/produce-release-benchmarks.mjs finalize \
  --candidate-root /absolute/path/to/clean-candidate \
  --work-root /absolute/path/to/downloaded-benchmark-work \
  --evidence-root /absolute/path/to/new-evidence-directory \
  --bundle-output /absolute/path/to/new-mcp-benchmarks-bundle.json \
  --reviewed-draft-sha256 exact-lowercase-sha256-from-review \
  --signoff-reference 'review-system:signoff/456#sha256=exact-lowercase-sha256-from-review'
```

Finalization first requires the downloaded canonical draft to match the
reviewed digest. It then re-derives the frozen candidate identity, re-parses
every retained JMH JSON file, reconstructs all normalized scores, verifies the
reviewed log, and passes the two registered evidence roles through the
canonical release bundle builder. The retained review record includes that
draft digest, and the durable sign-off reference must end with the same digest.
The accepted results role retains the exact reviewed draft and every canonical
raw JMH document. The shared bundle validator independently derives the JMH
scores from the complete raw sample arrays, enforces the exact Profile 1
compile/evaluate mapping, and proves that the log contains each retained raw
document. Changing the draft, raw data, inline score, mapping, or sign-off
binding therefore fails closed even when the generic bundle builder is called
directly. A JSON parse or write ratio below the registered `0.90` threshold
also fails closed; the current contract does not permit the producer to
self-authorize a regression.
