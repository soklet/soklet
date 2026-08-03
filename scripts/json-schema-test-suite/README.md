# Pinned JSON Schema Test Suite import

Soklet vendors the Draft 2020-12 fixtures from one reviewed upstream snapshot.
The import is test-only and adds no runtime dependency.

## Current pin

- Repository: `https://github.com/json-schema-org/JSON-Schema-Test-Suite`
- Commit: `0c7b65dc16dd8eaa7bd83e21099c76610c3b246a`
- Archive URL:
  `https://github.com/json-schema-org/JSON-Schema-Test-Suite/archive/0c7b65dc16dd8eaa7bd83e21099c76610c3b246a.tar.gz`
- Archive SHA-256:
  `405fa34d133c5a5dd3280399e0dafa379bcbf5adb17d180bd7b1b1aaa5afaa1b`

## Profile 1 applicability manifest

The machine-readable selection and claim boundary is
[`profile-1/manifest.json`](../../src/test/resources/com/soklet/internal/mcp/schema/profile-1/manifest.json).
It pins Soklet MCP Tool Schema Profile 1's supported keyword shapes, explicit
exclusions, local-reference policy, upstream files and group indexes, and the
exact official MCP schema fixture.

For the pinned JSON Schema Test Suite snapshot, the manifest exhaustively
partitions the reviewed source files as follows:

- **189 groups / 657 cases classified**;
- **133 groups / 500 cases selected**, all of which compile and evaluate to the
  upstream expected result; and
- **56 groups / 157 cases rejected**, with every rejected group failing closed
  during Profile 1 compilation.

The separately pinned official MCP `json-schema-2020-12` tool fixture has
SHA-256
`172e598d4345d7688bafa08e35addf26d6b16cb50db1a36adf6e0352470fd6bc`
and has **5 valid / 8 invalid** local evaluation cases. Those results prove the
documented closed profile and exact fixture locally; they do not claim complete
Draft 2020-12 support or an official MCP server-conformance run.

## Reproduce the import

Prerequisites are Node.js, `tar`, `curl`, and a SHA-256 utility. Start from a
clean source tree in which the destination test-resource directory does not
exist.

```sh
curl --fail --location \
  --output /tmp/soklet-json-schema-test-suite.tar.gz \
  https://github.com/json-schema-org/JSON-Schema-Test-Suite/archive/0c7b65dc16dd8eaa7bd83e21099c76610c3b246a.tar.gz
shasum -a 256 /tmp/soklet-json-schema-test-suite.tar.gz
node scripts/json-schema-test-suite/import.mjs \
  --archive /tmp/soklet-json-schema-test-suite.tar.gz
node scripts/json-schema-test-suite/verify.mjs
```

The reported archive digest must match the pin above before import. The
importer verifies it again, selects only the reviewed Draft 2020-12 tests and
remotes, preserves the upstream MIT license, and writes a bytewise path-sorted
manifest.

## Re-pin review checklist

1. Review the upstream commit and its changes from the current pin.
2. Record the new commit, exact archive URL, and archive SHA-256 here.
3. Update the immutable pin constants in `import.mjs`, `verify.mjs`, and
   `JsonSchemaTestSuitePinTests`.
4. Import into an absent destination directory and inspect every resulting
   resource change, including the upstream license.
5. Update and review the expected manifest digest, license digest, file count,
   required/optional/remote counts, and the Profile 1 applicability manifest's
   exhaustive group/case partition.
6. Run the Node verifier and the complete Maven test suite before accepting the
   new pin.

Soklet 3.6.0 uses this corpus selectively for its documented closed MCP schema
profile. The checked-in applicability manifest names every reviewed upstream
group as selected or rejected. Passing the selection and rejecting the excluded
schemas is evidence for the closed profile only; it is not a claim of full JSON
Schema Draft 2020-12 conformance.
