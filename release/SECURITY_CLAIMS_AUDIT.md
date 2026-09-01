# Security-claims audit for 4.0.0

Audit date: **2026-09-01**

This review covers the release-facing claims in `README.md`, `MCP.md`,
`SECURITY.md`, the migration/quickstart/OAuth documents, and the release notes.
It compares them with the implemented public boundary and checked-in evidence.
It is an engineering claims review, not a penetration test or independent
security assessment.

## Approved claim boundaries

Release documentation may claim only the following at the stated scope:

- the runtime has no external dependency artifacts, while credited Microhttp,
  Spring, Selenium, Tomcat/Commons FileUpload, and predecessor-project source is
  compiled into the artifact as recorded by the
  [third-party audit](THIRD_PARTY_AUDIT.md);
- Soklet implements the exact modern MCP `2026-07-28` profile described in
  `MCP.md`, subject to the frozen capability inventory and immutable-candidate
  conformance receipts;
- concrete parsers, queues, schemas, streams, cursors, request state,
  localization, trace correlation, and lifecycle phases are bounded where the
  public documentation names an exact limit or deadline;
- the built-in MCP listener applies the documented validation/admission/
  limiting/dispatch/output precedence, Host and Origin policies, response-
  header safety, and `no-store` behavior;
- Tool Schema Profile 1 derives and evaluates only its documented closed Java
  and keyword subset;
- framework-protected request state and pseudonymous trace correlation follow
  their documented cryptographic profiles and checked-in vectors; and
- the simulator, goldens, compatibility smoke, conformance suite, static
  analysis, scans, benchmarks, and optional histories prove only their named
  cases and candidate identities.

“Bounded,” “validated,” “fail closed,” “exact,” and “supported” must always be
read with the surrounding input, phase, profile, and application/custom-code
boundary. They are not global adjectives for an entire deployment.

## Claims explicitly rejected or narrowed

| Overbroad claim | Release wording |
| --- | --- |
| Zero dependencies means first-party-only code or no third-party vulnerability surface | Zero external runtime dependency artifacts; embedded credited source remains in scope. |
| Soklet provides OAuth or MCP Authorization end to end | Soklet transports an opaque challenge and invokes per-request admission. The application owns tokens, metadata, authorization server, scopes, DCR, provider policy, and RFC conformance. |
| CORS, Host validation, or network bind policy authenticates a caller | These are independent request/network controls and never substitute for authentication or authorization. |
| A custom transport/decorator's attestation proves its implementation honest | Soklet validates the presented contract; it cannot detect deliberately false custom evidence. |
| Java-derived schema means arbitrary JSON Schema is safe or supported | Tool Schema Profile 1 is a closed bounded subset and does not validate semantic business rules or sensitive-data policy. |
| Prompt/resource/input examples prevent prompt injection, data disclosure, path attacks, or authorization bugs universally | They are compile-checked application patterns for named cases; the application owns semantic policy and downstream behavior. |
| Built-in cryptography is independently reviewed or formally secure | The profile has vectors/tests and a documented key boundary; no independent cryptographic audit, formal proof, or certification is claimed. |
| Fuzz/soak/conformance/scan/benchmark evidence proves absence of vulnerabilities | Each result covers its exact inputs, duration, tool version, and candidate; none is an exhaustive security proof. |
| Localhost/Inspector success establishes compatibility with every MCP host or live model | The compatibility matrix records exact tested versions and leaves other hosts explicitly untested. |
| Simulator behavior proves kernel TCP, proxy, TLS, or live backpressure behavior | Simulation is off-network; real-listener tests and deployment smoke have separate scopes. |
| Soklet protects logs, databases, keys, proxies, identity systems, handlers, or downstream services | Those remain application/operator responsibilities outside the documented framework boundary. |

## Corrections made for this release

- `SECURITY.md` now says that zero runtime dependencies does not remove the
  embedded third-party source vulnerability surface.
- `SECURITY.md` has a prominent boundary/non-claims section covering OAuth,
  custom attestation, schema scope, cryptographic review, deployment ownership,
  and limits of evidence.
- The OAuth example uses application-owned token/partition seams, fixed safe
  errors, fixed application scope vocabulary, and explicit notification/body,
  Host, Origin, TLS, and metadata-hosting boundaries.
- The compatibility matrix separates a dated pre-release manual smoke from an
  immutable candidate receipt and marks untested mainstream hosts honestly.
- Release notes and migration prose identify simulator, conformance,
  cryptographic, cursor, resource, and request-diagnostic limits without
  elevating examples into universal guarantees.
- The support table now states the exact 3.x EOL point and does not imply
  security maintenance for every published coordinate.

## Final candidate check

Before G4 approval, search the complete rendered documentation and Javadocs for
new uses of `secure`, `safe`, `guarantee`, `protect`, `conformant`, `verified`,
`authenticated`, `zero dependency`, `OAuth`, and `audit`. Review each in
context; a mechanical word match is a review queue, not proof of a defect.

Any new claim must name its owner and evidence or be framed as application/
operator guidance. An independent security or cryptographic review may be
claimed only after a separately retained report identifies the exact source and
artifact hashes it reviewed.
