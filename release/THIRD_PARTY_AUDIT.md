# Third-party attribution and redistribution audit

Audit date: **2026-09-01**  
Release target: **Soklet 4.0.0**  
Scope: tracked production/source bytes, Maven binary and source artifacts,
annotation-generated output, conformance material, schema fixtures, goldens,
and vendored archives.

This is an engineering attribution audit, not legal advice. The audited tree
contains compatible Apache-2.0 and MIT material with retained provenance. The
audit found no unlicensed third-party byte in scope. Final candidate review
must still verify that the built JARs contain the tracked `META-INF/LICENSE`
and `META-INF/NOTICE` bytes and that source-archive construction has not added
unreviewed material.

## Production and binary-distributed code

| Material | Tracked location | Origin/license record | Distribution decision |
| --- | --- | --- | --- |
| Soklet | `src/main/java/com/soklet` outside the rows below | Revetware LLC / Transmogrify LLC, Apache-2.0 headers and root `LICENSE` | Retain. Root Apache-2.0 license is copied into the binary JAR as `META-INF/LICENSE`. |
| Microhttp | 32 Java files under `src/main/java/com/soklet/internal/microhttp`, including `package-info.java` | Elliot Barlas, MIT; complete notice in `package-info.java` and root/JAR `NOTICE` | Retain repackaged/modified sources and MIT notice. |
| Spring Framework utilities | `internal/spring/CollectionUtils.java`, `LinkedCaseInsensitiveMap.java`, `ObjectUtils.java`, and `package-info.java`; the credited cookie-validation portion of `ResponseCookie.java` | Copyright 2002-2023 original authors, Apache-2.0; headers remain in every copied file or adjacent credited section | Retain with attribution in root/JAR `NOTICE`. |
| Selenium header parsing | Credited section in `DefaultMultipartParser.java` | Selenium committers and Software Freedom Conservancy, Apache-2.0; complete notice remains adjacent to the code | Retain with attribution in root/JAR `NOTICE`. |
| Apache Tomcat / Commons FileUpload multipart code | Credited section in `DefaultMultipartParser.java` | Apache Tomcat fork of Apache Commons FileUpload, Apache-2.0; source attribution remains adjacent | Retain; applicable Apache Software Foundation attribution added to root/JAR `NOTICE`. |
| Generic `TypeReference` implementation | `src/main/java/com/soklet/converter/TypeReference.java` | Copyright 2015 Transmogrify LLC, Apache-2.0 header | Retain; attribution added to root/JAR `NOTICE`. |

The production JAR contains no third-party dependency JAR or native binary.
“Zero runtime dependencies” describes dependency resolution; it does not mean
that every compiled class was authored solely by the current Soklet copyright
holder. The notices above remain required for the repackaged source.

Remediation added by this audit:

- root [`NOTICE`](../NOTICE), including the complete applicable MIT notices and
  Apache attributions;
- `src/main/resources/META-INF/NOTICE`, so the same notices ship in the main
  and source artifacts as applicable; and
- `src/main/resources/META-INF/LICENSE`, a complete copy of Apache License 2.0
  for binary recipients.

Do not replace these with a URL-only license reference.

## MCP specification and official conformance material

| Material | Provenance | License handling | Packaging |
| --- | --- | --- | --- |
| Final MCP protocol schema | `modelcontextprotocol/modelcontextprotocol` tag `2026-07-28`, commit `5f5440bb26a62e2cf3440b92da5a667efa03b267`; source `schema/2026-07-28/schema.json`; 181,474 bytes; SHA-256 `ef70b61f99b6d2e5e3b46863822eab08dff6a45bedc7a08914e0e5b133f40203` | Exact upstream licensing-transition notice and Apache text retained at `conformance/official/final-schema/LICENSE.upstream`, SHA-256 `0382b0057770ca05e9c350a50aa3b1c1fea84da0bc81d723bf00b9aa841be58a` | Tracked conformance/source material; not packaged in the runtime JAR. |
| Official MCP conformance suite | `modelcontextprotocol/conformance` commit `49103de6ed70804e940637bf3e9e29e4a3f54e64`; exact tree/package/build hashes in `conformance/official/upstream-pins.json` | Not copied into this repository. The release workflow obtains the exact pinned checkout and builds it with scripts disabled before execution. | External candidate-time tool; no suite source or built CLI in Soklet artifacts. |
| Soklet conformance fixture and scenario manifest | `conformance/official/public-fixture-src`, `scenarios.json`, and Soklet scripts | Soklet-authored Apache-2.0 source; scenario names and protocol interactions describe the upstream contract but do not copy the suite implementation | Source/release evidence only; no runtime-JAR inclusion. |

The final schema importer refuses overwrite and accepts only the currently
reviewed bytes. A repin must update schema, upstream license, commit/tag,
checksums, inventory, goldens, and review together.

## JSON Schema test material

The test tree contains 104 imported files from the JSON Schema Test Suite plus
its local pin record and retained upstream license (106 tracked files total):

- repository `json-schema-org/JSON-Schema-Test-Suite`;
- commit `0c7b65dc16dd8eaa7bd83e21099c76610c3b246a`;
- imported-archive SHA-256
  `405fa34d133c5a5dd3280399e0dafa379bcbf5adb17d180bd7b1b1aaa5afaa1b`;
- exact imported roots and manifest in
  `src/test/resources/com/soklet/internal/mcp/schema/json-schema-test-suite/upstream-pin.json`; and
- Julian Berman MIT notice retained at
  `src/test/resources/com/soklet/internal/mcp/schema/json-schema-test-suite/LICENSE.upstream`,
  SHA-256 `837402bd25fad9b704265801ca3f92566a98157c1f9a7acd6f446299ba1c305a`.

These files are test/source-distribution material and are not runtime-JAR
resources. Their MIT notice is also reproduced in root `NOTICE` so a repository
source archive remains self-describing.

## Fixtures, goldens, generated files, and archives

- Soklet's checked-in wire goldens are generated from Soklet's own listener and
  fixtures, then independently validated against the pinned schema. They are
  first-party output, not copied examples from the MCP specification or
  conformance-suite source.
- Annotation processing emits Soklet endpoint descriptors and index/provider
  resources from application declarations. It does not copy the MCP schema,
  upstream suite, or third-party source into an application artifact. Shaded
  applications must nevertheless preserve the generated resources.
- Generated release JSON and checksum files describe first-party source,
  messages, evidence, or tool output. Their generators and exact inputs are
  tracked; they do not change the license of an upstream byte they identify.
- No `.jar`, `.zip`, `.tgz`, or `.tar.gz` is tracked under `src` or
  `conformance`. The official suite's built JavaScript and dependency tree are
  candidate-time external tools, not vendored product artifacts.
- The separate `barebones-app` repository intentionally vendors the released
  Soklet JAR and must carry the JAR's embedded license/notice. Its artifact and
  distribution audit belongs to that downstream release receipt.

## Candidate verification checklist

Before immutable-candidate approval:

1. Build the exact main and sources JARs from a clean tree.
2. Require `META-INF/LICENSE` and `META-INF/NOTICE` in the main JAR and compare
   their bytes with the tracked resource files.
3. Confirm the source JAR retains each copied source header and includes the
   license/notice resources.
4. Re-run the pin/import verifiers for the MCP schema and JSON Schema test
   corpus; no unpinned upstream byte may appear.
5. List every binary/archive under the candidate source tree and explain or
   remove any addition.
6. Review generated/site/download packages independently; a Maven JAR result
   does not prove the contents of a GitHub source archive or website bundle.
7. Retain the candidate JAR inventories, license/notice hashes, schema/license
   hashes, and audit reviewer decision with the release receipts.

Any source, generated output, or archive introduced after this audit returns
the affected row to review. License compatibility is not inferred from a file
extension, repository reputation, or the fact that a dependency is used only
by tests.
