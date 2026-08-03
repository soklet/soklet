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
   required/optional/remote counts, and official group/case counts.
6. Run the Node verifier and the complete Maven test suite before accepting the
   new pin.

The official Draft 2020-12 meta-schema and its `meta/*` resources are not part
of this upstream archive. They require a separate, checksum-pinned built-in
bundle before Soklet can run the full offline schema-validation gate.
