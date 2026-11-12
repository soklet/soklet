# MCP SDK interoperability checks

These checks exercise the selected Soklet JAR through the public MCP HTTP
transport with locked TypeScript and Go SDK dependencies. Both clients negotiate
`2026-07-28`, list the fixture's tools, call `test_simple_text`, validate its exact
result, close, and require the fixture to shut down cleanly.

From `soklet/`, with Java 21, Node/npm, and Go 1.25 or newer on `PATH`:

```sh
node scripts/verify-mcp-interoperability.mjs target/soklet-4.0.0.jar
# Or select one client:
node scripts/verify-mcp-interoperability.mjs target/soklet-4.0.0.jar typescript
node scripts/verify-mcp-interoperability.mjs target/soklet-4.0.0.jar go
```

The individual hooks take only the built JAR:

```sh
verification/interoperability/typescript/verify.sh /absolute/path/to/soklet.jar
verification/interoperability/go/verify.sh /absolute/path/to/soklet.jar
```

The wrapper snapshots that JAR into a temporary directory so every selected
client tests the same bytes. The fixture classpath uses the built JAR rather
than Soklet source or `target/classes`, and its hash is checked after execution.
No release manifest, receipt, SDK source checkout, or Git commit is required.

The TypeScript client is installed from `package-lock.json`, with npm checking
its recorded integrity. Go SDK v1.7.0 is verified through `go.sum` and the public
checksum database. Installation, verification, and compilation run with explicit
timeouts, bounded output, and managed process-group cleanup. Temporary caches
and fixture files are removed after the checks.

The shared runner accepts the client-specific success marker, validates fixture
control messages and exact results, and rejects unexpected client output. Its
PASS message describes the protocol check, without generating release receipts.
The manual **Release Verification** workflow runs both clients against its JDK 21
publication JAR.

Lightweight checks of the runners, including failure propagation and cleanup:

```sh
node scripts/verify-mcp-interoperability-self-test.mjs
node verification/interoperability/run-against-public-fixture-self-test.mjs
node verification/interoperability/run-command-self-test.mjs
```

The separate [Inspector harness](inspector/README.md) exercises the CLI and web
client in isolated Chrome. It is additional development coverage, not a
replacement for these SDK roundtrips.
