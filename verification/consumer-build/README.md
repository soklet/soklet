# Packaged consumer build smoke

Use the driver to materialize this fixture into a new temporary directory for
each build. It must not compile against core `target/classes`,
`target/test-classes`, or their indexes.
Install the exact main JAR and POM into an isolated Maven repository first;
verify their SHA-256 identities before and after each run. Maven uses
`-Dmaven.repo.local=/absolute/rehearsal/repository`; Gradle uses
`-Dsoklet.consumer.repository=/absolute/rehearsal/repository`.

The driver creates a fresh copy, verifies candidate JAR identity before and after
the build, runs the packaged application, and retains logs and a development-only
result (never an immutable release receipt):

```sh
node verification/consumer-build/verify.mjs --mode maven \
  --java-home /absolute/jdk --maven /absolute/maven/bin/mvn \
  --jar /absolute/soklet-4.0.0.jar --sha256 EXACT_MAIN_JAR_SHA256 \
  --repository /absolute/rehearsal/repository --output /absolute/new-output-directory
```

Use `--mode gradle --gradle /absolute/gradle/bin/gradle` instead of the Maven
options, or `--mode javac` without build-tool/repository options. The driver is
for macOS/Linux classpath recipes and runs Maven with `-o` and Gradle with
`--offline`; pre-provision their dependencies in the isolated repository.
Gradle resolves the candidate directly from that file-based Maven repository,
even with a fresh `GRADLE_USER_HOME` for each run.

The driver detects the selected Java runtime, copies common sources from
`src/main/java`, and adds `src/sse/java` to the prepared fixture's ordinary main
source root only on Java 21+. Java 17 packages HTTP/MCP routes only: packaging an
SSE route would require an SSE server, whose live runtime requires Java 21+.
This same prepared source tree is used for Maven, Gradle, and direct javac; no
reflective `fromClasses` resolver substitutes for generated-index discovery.

For a manual repeat, use the driver's prepared `output/fixture` directory,
not a raw copy of the source fixture. Run `mvn -o -B -ntp clean package` or
`gradle --offline --no-daemon clean jar` with the repository properties above.
Then run:

```sh
java -cp /absolute/consumer-build-1.0.0.jar:/absolute/soklet-4.0.0.jar example.ConsumerSmoke
```

Repeat using Java 17, 21, and 25. Gradle 9.1.0 supports all three runtimes;
its official binary distribution SHA-256 is
`a17ddd85a26b6a7f5ddb71ff8b05fc5104c0202c6e64782429790c933686c806`.
See the [Gradle compatibility matrix](https://docs.gradle.org/current/userguide/compatibility.html).
Use checksum-verified tool distributions, not an unpinned wrapper download.

The smoke checks both generated indexes and uses the default framework resolver
to execute HTTP, MCP discovery,
typed tool binding, required/optional prompt binding, and (on Java 21+) SSE
through real loopback listeners. It exercises ordinary classpath packaging with
only the consumer JAR and Soklet at runtime. Port reservations are released
immediately before startup; a bind collision fails the run rather than hiding
it as a successful smoke.

Every build mode also runs two packaged negative controls. A bounded JDK-only
helper removes or corrupts just `META-INF/soklet/resource-method-lookup-table`
in separate copies of the consumer JAR. It verifies the MCP index and every
other archive entry remain byte-identical. These copies bypass the positive
smoke's HTTP-index presence assertion and invoke actual default framework
startup: the test requires the specific HTTP discovery/decoding failure wrapped
in `SokletStartupException`, then confirms no listener remains. A successful
negative test exits zero and prints `PASS` only after observing that expected
framework failure. The corrupt index is present but contains invalid Base64;
a mere presence check cannot detect it.

Direct javac additionally compiles the prepared sources with `-proc:none` into
a separate output directory and proves missing-metadata startup failure through
the framework, without requiring the intentionally absent MCP descriptor index.
A compile-only success never establishes working routing.

`result.json` records the Java feature version, selected `HTTP_MCP` or
`HTTP_MCP_SSE` source variant, candidate and consumer JAR identities, positive
runtime output, and each negative control's log. Mutated JAR controls also
record the preserved MCP-index digest and unchanged-entry count. Commands are
bounded to 180 seconds and 16 MiB of captured output; mutation inputs are bounded
to 64 MiB compressed/expanded, 16 MiB per entry, and 1,024 entries.

Run the fixture's own 24-case contract tests with a JDK 17+ on `JAVA_HOME` or
`PATH`:

```sh
node verification/consumer-build/self-test.mjs
```

These tests cover source selection, output validation, and real mutation of a
synthetic JAR. They require no Soklet artifact or live listener and do not replace
the packaged consumer matrix. The helper is never packaged into the consumer.

These are development/preparation results until rerun with immutable-candidate
provenance. They do not create a release PASS receipt by themselves.
