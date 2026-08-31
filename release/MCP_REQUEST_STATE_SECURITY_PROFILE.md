# MCP request-state security profile

This document is the normative Soklet 4.0 profile for built-in protection of
`FRAMEWORK_PROTECTED` MCP request state. It records the exact format and
cryptographic inputs implemented by the production code and frozen by the
executable vectors named below.

The sole built-in profile identifier is the case-sensitive ASCII string
`soklet-mcp-protection-v1`. Production key-ring and development-ephemeral
modes use this same profile. Development-ephemeral keys are process-local and
are not suitable for production, restarts, or a fleet.

This profile does not apply to:

- `APPLICATION_PROTECTED` state, which Soklet passes through as an opaque
  application-owned string without confidentiality, integrity, expiry,
  binding, round, or replay protection; or
- `CUSTOM_PROTECTOR`, whose application provider owns its algorithm, wire
  format, keys, randomness, invocation limits, rotation, and fleet behavior.
  Soklet still supplies the exact binding bytes described below and requires
  the provider to authenticate them. Soklet also retains canonical plaintext,
  lifetime, round, and prior-request-ID validation.

## Key and randomness requirements

A built-in master key has:

- a 1–64-byte ASCII HTTP-token ID; and
- at least 32 bytes of key material.

The operator must generate each master key with a cryptographically secure
random source and at least 256 bits of entropy. The length check cannot prove
entropy or provenance. Protection keys must not reuse material under another
ID or share material with the trace-correlation key. Key IDs are operational
metadata and appear in the protected envelope; they are not secrets.

Production randomness comes from `SecureRandom`. A new sealer activation takes
a new random 24-byte activation-prefix draw, and every seal takes a new random
12-byte nonce draw. Uniqueness is probabilistic; Soklet does not retain a
collision registry. Only tests can inject deterministic entropy.

## Key derivation

All quoted strings in this section are their ASCII bytes. `0x00` is one
terminal NUL byte, `||` is concatenation, and `u64be` is unsigned eight-byte
big-endian encoding.

For master-key bytes `K`:

```text
salt = SHA-256("soklet-mcp-protection-v1" || 0x00)
PRK  = HMAC-SHA-256(key = salt, data = K)

sealerEpoch = activationPrefix[24] || u64be(epochNumber)
info = "soklet-mcp-request-state-aead-v1" || 0x00 || sealerEpoch
epochKey = HMAC-SHA-256(key = PRK, data = info || 0x01)
```

`epochKey` is the single 32-byte HKDF-SHA-256 expand block and is used as an
AES-256 key. A new activation starts at epoch number zero. At most `2^32`
sealing reservations are assigned to one epoch key. A reserved slot is never
refunded, including when nonce generation or encryption later fails. At the
limit, the server increments the unsigned epoch number, derives and publishes
the next epoch key, and then permits another reservation. Exhaustion of the
unsigned 64-bit epoch number fails sealing closed instead of wrapping.

## Wire envelope

The exact ASCII wire prefix is:

```text
soklet-mcp-request-state-v1.
```

It is followed by canonical unpadded RFC 4648 Base64URL. The suffix contains
only `A-Z`, `a-z`, `0-9`, `_`, and `-`; it contains no whitespace or `=` and
must equal the result of decoding and re-encoding its bytes.

The decoded envelope is:

```text
u8(1)
|| u8(keyIdLength) || ASCII(masterKeyId)
|| u8(profileLength) || ASCII("soklet-mcp-protection-v1")
|| sealerEpoch[32]
|| nonce[12]
|| ciphertext[1..]
|| tag[16]
```

`keyIdLength` is 1–64 and `profileLength` is exactly 24. The header ends after
the nonce and is exactly `71 + keyIdLength` bytes. There is no magic field,
flags byte, ciphertext-length field, compression, or padding. The AES-GCM tag
is the full 128-bit tag.

The key ID, profile, epoch, and nonce are untrusted parser inputs. A valid key
ID selects a candidate active or verification-only master key, but a
structurally accepted header remains untrusted until the GCM tag authenticates
it. An unknown valid key ID and an authentication failure produce the same
sanitized invalid-state response.

## Operation binding and associated data

All lengths below are unsigned four-byte big-endian byte lengths. Text uses
strict UTF-8 without Unicode normalization.

Let `canonicalParams` be the dedicated canonical JSON encoding of the complete
validated request `params` object after deleting only:

- top-level `inputResponses` and `requestState`; and
- immediate `_meta` members `progressToken`, `traceparent`, `tracestate`, and
  `baggage`.

Same-named nested members, unknown schema-open parameters, tool arguments,
prompt names, resource URIs, and every other stable method parameter remain.
The request ID is a JSON-RPC envelope member rather than a parameter and is not
part of `canonicalParams`.

```text
paramsDigest = SHA-256(
    "soklet-mcp-request-state-params-v1" || 0x00
    || u32be(canonicalParams.length) || canonicalParams)
```

The external binding is:

```text
"soklet-mcp-request-state-binding-v1" || 0x00
|| u32be(endpoint.length) || UTF8(normalizedEndpointPath)
|| u32be(protocol.length) || UTF8(protocolVersion)
|| u32be(method.length) || UTF8(jsonRpcMethod)
|| u8(authorizationKind)
|| u32be(authorization.length) || authorization
|| paramsDigest[32]
```

`authorizationKind` is `0` with a zero-length value for the endpoint-scoped
anonymous partition. It is `1` followed by the 1–256 strict UTF-8 bytes of the
application-supplied authorization-partition key otherwise. Rate-limit keys,
principal rendering, Java object identity, and raw authorization headers do
not participate. Validated client-information and client-capability values
that are members of request `_meta` are not on the transient exclusion list
and therefore do participate in `canonicalParams`.

For built-in protection, let `header` be the envelope bytes through the nonce
and let `binding` be the complete external binding above. AES-GCM associated
data is exactly:

```text
"soklet-mcp-request-state-gcm-aad-v1" || 0x00
|| u32be(header.length) || header
|| u32be(binding.length) || binding
```

The encryption operation is AES-256-GCM with `epochKey`, the 12-byte envelope
nonce, this associated data, and the canonical framework plaintext. A custom
protector receives the external `binding` bytes in
`McpRequestStateProtectionContext.getAssociatedData()`, not the built-in GCM
associated-data wrapper.

## Canonical framework plaintext

The plaintext is a closed canonical JSON object. Version 1 has exactly the
following member set; the whitespace and display order here are illustrative,
not canonical bytes:

```json
{
  "version": 1,
  "bindingDigest": "<43-character unpadded Base64URL SHA-256(binding)>",
  "issuedAtEpochSecond": 0,
  "issuedAtNanoAdjustment": 0,
  "expiresAtEpochSecond": 0,
  "expiresAtNanoAdjustment": 0,
  "round": 1,
  "originatingRequestId": "string-or-integer-request-id",
  "state": null
}
```

Version 2 has the same members, `version` equal to `2`, and exactly one
additional `selectedLocale` member. That value is the canonical non-root ASCII
BCP 47 locale selected for the continuation and is at most 255 bytes. A
version/field-set mismatch is invalid.

Canonical JSON uses strict UTF-8 with no insignificant whitespace and rejects
duplicate object member names. Object members sort by unsigned lexicographic
comparison of UTF-8 key bytes, and arrays retain order. Strings use `\"`,
`\\`, `\b`, `\f`, `\n`, `\r`, and `\t`, lowercase `\u00xx` for other
controls, and shortest raw UTF-8 for every other valid scalar value; no Unicode
normalization occurs. Numbers use the canonical
`BigDecimal.stripTrailingZeros().toString()` form, with zero written as `0`.
An opened plaintext must parse within the configured limits and reproduce the
exact same bytes when canonicalized.

Epoch-second fields are signed 64-bit integers. Nanosecond adjustments are
`0..999999999`. The originating ID preserves its JSON string or integer type
and value; integer magnitude is limited only by the configured JSON token
limits rather than by a Java primitive width. `state` may be any supported MCP
JSON value.

The first emission records round 1, the current instant, that instant plus the
configured maximum lifetime, and the emitting request ID. Re-emission keeps
the original issue and expiry instants, increments the round, and records the
current request ID. Open requires:

- a matching binding digest;
- `issuedAt < expiresAt` and an encoded lifetime no greater than the current
  configured maximum;
- `now < expiresAt`;
- a signed 32-bit round in `1..configured maximum`; and
- a current request ID that is not equal as a typed JSON-RPC ID to the ID that
  emitted this state. String `"7"` and integer `7` are distinct IDs.

A future issue instant is not by itself rejected. Soklet checks issue-before-
expiry, the encoded issue-to-expiry lifetime, and expiry relative to the local
clock, but applies no separate bound to how far issuance may be in the future.
This accommodates fleet clock skew without claiming to bound it; clock
correctness remains an operator responsibility. The prior-ID and round checks
are not a single-use replay database: applications that require at-most-once
approval, consumption, or side effects must persist and enforce that policy
themselves.

## Limits and failure behavior

The default limits are:

| Setting | Default | Accounting |
| --- | ---: | --- |
| Maximum encoded state | 65,536 bytes | Exact UTF-8 bytes of the complete wire string |
| Maximum decoded state | 49,152 bytes | Both canonical plaintext and complete decoded envelope |
| Maximum lifetime | 15 minutes | Issue-to-expiry duration |
| Maximum rounds | 10 | Protected continuation round number |

`McpProtectionConfig.Builder` can configure different positive values within
its validated contract. State is never compressed.

Incoming empty, oversized, malformed, noncanonical, wrong-prefix, wrong-
version, invalid-key-ID, or alternate-profile envelopes fail structural
validation. Structurally valid state is opened only after capability checks
and accepted admission have established the authorization partition. This
ordering prevents a pre-admission cryptographic-validity oracle; admission may
itself deliberately accept an anonymous caller.

Malformed state, unknown or removed keys, tag failure, wrong binding,
noncanonical plaintext, expiry, round failure, and immediate request-ID reuse
all collapse to the same response: HTTP 400 with JSON-RPC `-32602` and no
sensitive diagnostic data. Temporary entropy, cryptographic provider, or
custom-provider unavailability maps to HTTP 503 with `-32603`. Invalid handler
output, a contract-violating custom-provider result, or another application
invariant maps to HTTP 500 with `-32603`. None reflects protected bytes,
provider details, or raw exception text.

## Secret handling and operational boundary

Each server copies its immutable initial ring into independent live state.
The public control snapshot exposes key IDs, roles, and a secret-free ring
fingerprint; general server diagnostics expose the mode and fingerprint but no
key IDs or roles. Neither surface exposes raw key material or per-key
authentication tags. Soklet clears explicit server-owned master-key copies,
derived keys, plaintext copies, and cryptographic work buffers when they
retire. This is best-effort handling inside the Java memory model, not a claim
that the JVM or operating system cannot retain other copies.

The ring fingerprint is deployment-comparison metadata, not an authentication,
authorization, or key-derivation input. Equality is observable. Do not use it
as a metric label, emit it per request, or retain it without bounds. Follow
[the key-rotation runbook](MCP_REQUEST_STATE_KEY_ROTATION_RUNBOOK.md) for live
production changes.

This profile does not supply authentication, OAuth, authorization policy,
TLS, one-time workflow consumption, durable state storage, or protection for
application-owned cursors. Those remain deployment or application concerns.

## Frozen executable vector

The vector inputs are intentionally public test values and must never be used
as deployment keys:

```text
master key ID       = active
master key bytes    = 000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f
activation prefix   = 404142434445464748494a4b4c4d4e4f5051525354555657
epoch number        = 0000000000000000
nonce               = 606162636465666768696a6b
external binding    = 202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f
plaintext           = 7b227374617465223a226f6b227d

salt                = e32752b853923d1dec2b9fd7f9c6768d7669006cf82dc8a4bc5ffd1fb167e99c
PRK                 = d475b6dd059c218b22c4fc1bd3277462abb8e25482a65edaa3066d1437dff53a
epoch key           = 6afbea4eb96db3a304ae39bde5e8e30faa29fb5babc2220f7cfec6a9aeb5afda
header              = 010661637469766518736f6b6c65742d6d63702d70726f74656374696f6e2d7631404142434445464748494a4b4c4d4e4f50515253545556570000000000000000606162636465666768696a6b
associated data     = 736f6b6c65742d6d63702d726571756573742d73746174652d67636d2d6161642d7631000000004d010661637469766518736f6b6c65742d6d63702d70726f74656374696f6e2d7631404142434445464748494a4b4c4d4e4f50515253545556570000000000000000606162636465666768696a6b00000020202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f
ciphertext          = 3521814e7915bc5dbeb62f865723
tag                 = d36248da9ccacdf2a4939ee49c87f564
```

The complete wire value is:

```text
soklet-mcp-request-state-v1.AQZhY3RpdmUYc29rbGV0LW1jcC1wcm90ZWN0aW9uLXYxQEFCQ0RFRkdISUpLTE1OT1BRUlNUVVZXAAAAAAAAAABgYWJjZGVmZ2hpams1IYFOeRW8Xb62L4ZXI9NiSNqcys3ypJOe5JyH9WQ
```

## Executable evidence

The exact profile is independently pinned by:

- `McpSecurityControlsTests#builtInRequestStateProtectionMatchesFrozenVectorAndBinding`;
- `McpSecurityControlsTests#builtInProfilePinsKdfAeadHeaderCiphertextAndTag`;
- `McpSecurityControlsTests#builtInStructureRejectsInvalidAndUnknownKeyIdsAndOtherProfiles`;
- `McpSecurityControlsTests#builtInStructureRejectsNoncanonicalAndMalformedEnvelopes`;
- `McpSecurityControlsTests#builtInProtectionEnforcesEnvelopeAndWireSizeLimits`;
- `McpSecurityControlsTests#invocationCapRollsEpochAndFailedNonceConsumesItsSlot`;
- `McpSecurityControlsTests#concurrentSealsNeverOverallocateAnEpoch`;
- `McpSecurityControlsTests#unsignedEpochExhaustionFailsClosed`;
- `McpRequestStateBindingTests#matchesDeterministicDigestBindingAndAadVectors`;
- `McpRequestStateBindingTests#removesOnlyTheFrozenTransientLocations`;
- `McpRequestStatePlaintextCodecTests#matchesDeterministicCanonicalPlaintextVector`;
- `McpRequestStatePlaintextCodecTests#rejectsWrongBindingNoncanonicalBytesAndSizeViolations`;
- `McpRequestStateSelectedLocaleCodecTests#versionTwoMatchesTheDeterministicCanonicalVector`; and
- `McpRequestStatePublicRuntimeTests#malformedTamperedAndUnavailableStateHaveFixedPrecedence`.

The first two security-control vectors exercise the production envelope and
production AES-GCM path. The binding test's similarly named associated-data
helper is a separate test oracle, not the production encryption method.

Soklet 4.0 accepts no alternate built-in profile or envelope spelling. A
future cryptographic profile requires an explicit profile identifier and
rolling compatibility design; a changed binary grammar requires a new wire
prefix/version. Silent changes to labels, framing, canonicalization, or
acceptance rules are incompatible.

The implementation and these deterministic vectors have been reviewed for
internal consistency. They are not a claim of third-party cryptographic audit;
independent security review remains recommended before general availability.
