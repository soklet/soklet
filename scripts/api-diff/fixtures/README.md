# japicmp parser fixtures

These XML files are normalized outputs from
`com.github.siom79.japicmp:japicmp-maven-plugin:0.26.1`, compared against the
released `com.soklet:soklet:3.5.1` JAR.

- `no-changes.xml` is a complete report with no modified API and proves that
  an empty comparison produces an empty JSONL set.
- `removals.xml` compares the baseline with a copy from which
  `McpClientInfo.class`, `McpRequestOutcome.class`, and
  `McpServer$RequestHandler.class` were removed. It covers top-level and nested
  binary names plus constructor, field, method, object, primitive, and array
  descriptors.
- `changed-descriptor.xml` compares the baseline with a copy whose
  `McpClientInfo.class` was replaced by a Java 17 fixture that changes
  `version()` from `String` to `Object`. It covers old/new JVM method IDs and a
  simultaneous superclass incompatibility.
- `compatible-removals.xml` covers japicmp's counterintuitive
  `binaryCompatible="true" sourceCompatible="true"` classification for
  removed exported symbols. It exercises a removed class, constructor, field,
  and public method plus a removed protected method on a surviving class;
  harmless `MODIFIED` containers are intentionally absent from the set.

An all-classes baseline-to-empty-JAR comparison was also generated during the
parser review to exercise every emitted japicmp 0.26.1 element shape; the 5.4
MB report is intentionally not checked in. The compact controlled variants are
the durable self-test corpus. Each corresponding `.jsonl` file is the exact
canonical output expected from `japicmp-symbols.mjs`.
