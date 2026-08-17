# Pinned MCP SDK interoperability

These hooks exercise the checksum-matched Soklet candidate through the public
MCP HTTP transport with the pinned TypeScript and Go SDK releases. Both clients
negotiate `2026-07-28`, list the candidate fixture's tools, call
`test_simple_text`, validate its exact result, close, and require the public
fixture to report a clean shutdown.

The shared runner accepts only an exact client-specific success marker, rejects
all client stderr and trailing stdout, strictly validates the fixture's control
messages, and rechecks the candidate JAR hash after the fixture exits. Its
success log includes a structured receipt that binds the SDK name, protocol,
tool call, clean fixture shutdown, exact candidate SHA-256, and the SDK's
manifest-matched artifact identity, lock checksum, and source commit. Release
evidence assembly rejects a receipt unless its separately retained candidate
JAR evidence is byte-identical to the main candidate artifact descriptor.

SDK installation, verification, and compilation each run with an explicit
timeout, a combined 16 MiB output bound, and an isolated process group. Timeout
and signal exits terminate the group before the per-run temporary tree is
removed; the hooks also make read-only package-cache files writable for cleanup.

The TypeScript hook consumes the exact npm artifact through the checked-in
`package-lock.json`; npm verifies the recorded SHA-512 integrity. The Go hook
consumes `go-sdk` v1.7.0 through the checked-in `go.sum` and the public checksum
database. The release manifest additionally pins each release's peeled source
commit, and the hooks prove that the supplied clean checkout has that identity.

The release validator invokes each executable `verify.sh` with the candidate
JAR and its isolated pinned SDK checkout. The hooks use fresh package caches and
do not add Soklet source or `target/classes` to the fixture classpath.

The dependency-free structural self-test is:

```sh
node verification/interoperability/run-against-public-fixture-self-test.mjs
node verification/interoperability/run-command-self-test.mjs
```
