# Soklet Skills example

This example starts a Skills-only MCP endpoint at `/mcp` and publishes one
synthetic, single-page Skill:

- `skill://soklet.example/toy-catalog-guide/SKILL.md`
- `skill://soklet.example/toy-catalog-guide/references/catalog.csv`
- `skill://soklet.example/toy-catalog-guide/assets/sample.bin`

The root document and CSV are fixed classpath resources. The small binary file is
created in Java, and `McpSkillBundle` snapshots all three files before the listener
starts. Soklet provides the standard `skills/list`, `skills/get`, and
`resources/read` behavior; the example does not register any Tools.
The root document's nested `example` frontmatter also demonstrates that authored,
unknown metadata is retained in the generated Skill manifest.

## Compile and run

Run these commands from the Soklet repository root, replacing the candidate JAR
path with the packaged candidate you want to exercise:

```sh
export SOKLET_CANDIDATE_JAR=/absolute/path/to/soklet-4.0.0-candidate.jar
SOKLET_SKILLS_CLASSES=$(mktemp -d)
javac --release 17 -proc:none \
  -cp "$SOKLET_CANDIDATE_JAR" \
  -d "$SOKLET_SKILLS_CLASSES" \
  examples/skills/src/com/soklet/examples/skills/SkillsExample.java
java -cp "$SOKLET_CANDIDATE_JAR:$SOKLET_SKILLS_CLASSES:examples/skills/resources" \
  com.soklet.examples.skills.SkillsExample
```

The optional command-line argument is a port from `0` through `65535`. Omitting
it, or passing `0`, asks the operating system to choose an available port:

```sh
java -cp "$SOKLET_CANDIDATE_JAR:$SOKLET_SKILLS_CLASSES:examples/skills/resources" \
  com.soklet.examples.skills.SkillsExample 8081
```

After the listener is ready, the program writes one JSON line containing its
loopback host, effective port, and endpoint path. Send a newline on standard
input, or close standard input, to stop it. A final JSON line is written only
after Soklet verifies a clean MCP shutdown.

## Retrieve it with Inspector

Inspector 2.7.0 must use modern HTTP, not its legacy default. With your example
running on port 8081, create a separate local configuration file:

```json
{
  "mcpServers": {
    "soklet": {
      "type": "http",
      "url": "http://127.0.0.1:8081/mcp",
      "protocolEra": "modern",
      "advertisedExtensions": { "io.modelcontextprotocol/skills": true }
    }
  }
}
```

Using an already-installed Inspector 2.7.0 launcher:

```sh
node /path/to/inspector/clients/launcher/build/index.js \
  --cli --config /path/to/example-session.json --server soklet \
  --method skills/list --format json --stored-auth-only

node /path/to/inspector/clients/launcher/build/index.js \
  --cli --config /path/to/example-session.json --server soklet \
  --method skills/get --uri skill://soklet.example/toy-catalog-guide/SKILL.md \
  --verify --format json --stored-auth-only
```

`--verify` retrieves all three files and checks manifest digests, byte sizes,
and root frontmatter. Success is an NDJSON report with `outcome: "verified"`
and three `verified` file rows—not merely `ok: true`, which can also accompany
an incomplete check. It does not activate the Skill or execute instructions.

For an isolated, repeatable check against your packaged JAR, use the
[Inspector verification runner](../../verification/interoperability/skills/README.md).
It compiles the example, exercises list/get/text/binary reads, verifies the
Skill, and checks that Inspector rejects an intentionally corrupted binary
response. It never installs packages or touches saved user client state.

## Security scope

This is intentionally public synthetic data. The example binds only to
`127.0.0.1`, rejects browser cross-origin access, restricts the Host header to
loopback, and explicitly admits every request. That accept-all admission policy
is suitable for this local demonstration only; a real application should install
its own authentication and authorization policy before exposing an MCP listener.
