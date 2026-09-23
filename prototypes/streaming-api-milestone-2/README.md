# Milestone 2: compiled streaming API decisions

These fixtures select the public surface before production migration. The
[decision record](../../docs/streaming-api-milestone-2.md) explains the choices.

- `api/`: proposed `com.soklet` signatures. Concrete operations deliberately
  throw `UnsupportedOperationException`; these are not executable implementations.
  Unrelated members omitted from builder excerpts are not proposed removals.
- `positive/`: compilable application/provider examples and server builders.
- `comparisons/`: independent policy and nested-block candidates compared with
  the selected operations. Rejected alternatives do not add selected API overloads.
- `negative/`: invalid caller shapes, each with one `// EXPECT-ERROR:` line
  specifying the compiler diagnostic code and required message fragments.

Use an installed JDK 17+ and Python 3, with core already compiled and annotation
dependencies in the local Maven repository:

```sh
python3 prototypes/streaming-api-milestone-2/verify.py --java-home "$JAVA_HOME"
```

On this checkout the verified Java 17 command is:

```sh
python3 prototypes/streaming-api-milestone-2/verify.py \
  --java-home /Users/agents/Java/amazon-corretto-17.jdk/Contents/Home
```

Run from the repository root. Use `--core-classes` or `--maven-repo` for alternate
local locations. If core classes are missing, first run `mvn -DskipTests compile`
with the repository's supported build environment. The verifier does not download
dependencies or modify production sources. It recreates only
`target/streaming-api-milestone-2/`, which holds class files, diagnostic logs, and
`verification.json`. The receipt copied to `docs/` identifies the verified input
sources; editing fixtures requires a fresh verification and receipt.

The verifier compiles the signature overlay, then positive examples and
comparisons independently against it plus unchanged core types. Negative cases
must fail at the intended line for exactly one compiler error matching that code
and those fragments. Java 17 release
compilation, disabled processing, and an empty source path prevent accidental
source discovery. There is no runtime invocation of the overlay. Production
compilation, processor integration, and behavioral tests belong to subsequent
milestones.
