# Current SpotBugs constructor-annotation analysis

Reviewed September 16, 2026 against SpotBugs Maven plugin **4.10.4.1**, engine
**4.10.4**, JSpecify **1.0.1**, and javac 21 targeting Java 17.

This is technical evidence, not an approval inferred from a scanner result. The
owner subsequently approved the four exact exclusions in the separate
[September 16 approval record](../release/SCAN_FALSE_POSITIVE_APPROVAL_2026-09-16.md).
Historical sealed inventories, filter bytes, exception approvals, and their
provenance remain unchanged. That scoped decision is not candidate acceptance or
approval of other release-policy changes.

## September 18 descriptor-retarget reassessment

The `McpApplicationExecution$Exchange` constructor subsequently gained
`catalogAccessView` and `selectedLocaleSlot` parameters. Because each exclusion
is intentionally bound to the full descriptor, the two `Exchange` selectors
stopped resolving rather than silently widening.

A clean empty-filter JDK 21 scan with the same pinned SpotBugs plugin and engine
reproduced both previously classified Exchange findings with zero analyzer
errors. After unrelated genuine current-source findings were fixed without
suppressions, a normal baseline-filter scan with the stale selectors reported
exactly those two findings and no others: local `?` (displayed `$L3`) and local
`request`, with the same exact bug pattern. The owner approved only the
corresponding full-signature retarget in the
[September 18 amendment](../release/SCAN_FALSE_POSITIVE_APPROVAL_AMENDMENT_2026-09-18.md).
No broader rule or new false-positive classification was authorized.

## September 16 review outcome

The fresh upgrade scan reported 66 findings. Checked local variables now make
nullable immutable-record accessor proofs explicit. Two internal implementation
parameters incorrectly annotated nullable now match their existing non-null runtime
preconditions. Defensive handling of contract-violating application callbacks is
preserved using `Optional.ofNullable`, `Objects.requireNonNullElse`, or existing
failure-safe catch boundaries. Only impossible checks on internally constructed,
non-null values were removed.

At that review, four diagnostics remained without the new exact exceptions. All
four stem from the
same reproducible analyzer defect: constructor type-use annotation indexes are
interpreted without accounting for compiler-inserted constructor parameters.
Correct JSpecify annotations and nullable behavior have not been changed to satisfy
the scanner.

| Exact target | Reported parameter | Reason |
| --- | --- | --- |
| `McpApplicationExecution$Exchange.<init>` | XML local `?` (displayed `$L3`) | Enclosing-instance parameter shifts the analyzer's authored-parameter interpretation; `$L3` is not an authored nullable reference parameter. |
| `McpApplicationExecution$Exchange.<init>` | `request` | Authored `McpJsonRpcMessage.@NonNull Request request` is incorrectly assigned adjacent nullable context metadata. |
| Eight-argument `McpHttpServerRuntime.submitRequest` returning `RequestControl` | `lifecycleAdmission` | Nullable by design for simulation. The analyzer propagates an incorrect non-null requirement from the nonstatic `RequestControl` constructor, whose following callback parameter is non-null. |
| Five-bytecode-argument `McpTypedSchemaScalar.<init>` | `jsonType` | Authored `@NonNull String jsonType` is incorrectly assigned the nullable numeric-bound annotation after the synthetic enum name and ordinal. |

Each exception in `config/spotbugs-exclude.xml` matches an exact class, full method
signature (including synthetic parameters), exact local-variable name, and only
`NP_PARAMETER_MUST_BE_NONNULL_BUT_MARKED_AS_NULLABLE`. No package, category,
overload-family, or global nullability suppression was added. The `?` local is the
literal unknown-name value in SpotBugs XML, not a regular expression or wildcard;
its class, constructor descriptor, and bug pattern remain exact.

The exclusion verifier resolves signatures against actual compiled descriptors and
requires exact signature/local/pattern scoping for these exceptions. Its negative
controls reject absent or wrong signatures, parameter-wide or category-wide scope,
extra bug patterns, regex selectors, and global suppression. The scanner itself
remains fail-on-error at the existing effort and threshold.

## Independent minimal reproduction

The standalone fixture is in
`conformance/spotbugs-jspecify-constructor-reproducer`. Copy it to a scratch directory
before building to keep generated files out of the repository, then run:

```sh
mvn -B -ntp clean compile spotbugs:check
java -cp target/classes analysisfixture.ConstructorAnnotations
javap -v -p target/classes/analysisfixture/ConstructorAnnotations\$EnumCase.class
javap -v -p target/classes/analysisfixture/ConstructorAnnotations\$InnerCase.class
```

The scanner command is expected to fail with three false
`NP_PARAMETER_MUST_BE_NONNULL_BUT_MARKED_AS_NULLABLE` findings: enum `required`,
inner-class `required`, and caller `createInner(optional)`. Its unrelated suggestion
that the deliberately nonstatic fixture could be static is expected. The equivalent
`StaticControl` and `createStatic(optional)` are negative controls and must not report
this nullability pattern. The runtime command must succeed with null optional
arguments in all three forms.

For the enum constructor, `javap` reports the bytecode descriptor
`(Ljava/lang/String;ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;)V`, with
synthetic `$enum$name` and `$enum$ordinal` preceding authored `required`. The
`RuntimeVisibleTypeAnnotations` indexes are **0=NonNull, 1=Nullable, 2=Nullable**,
describing the three authored parameters. Index 2 is not a nullable annotation on
the descriptor's third parameter. The inner-class reproduction similarly has an
extra enclosing-instance constructor parameter. Adding `createInner` demonstrates
that the wrong interpretation also propagates to a caller's valid nullable input.

Remove these exact exceptions when a future engine correctly handles this fixture,
and rerun the unfiltered core scan. A green filtered scan alone is not evidence that
the upstream defect is fixed.
