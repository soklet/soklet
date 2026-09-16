# Gitleaks local historical-scan triage — 2026-09-16

This is a read-only local triage, **not an approval, exception registry, candidate scan receipt, or evidence that a release gate passed**. No finding was suppressed and no Git history was changed.

Subsequent owner disposition: the owner approved these exact 39 findings after
reviewing their explanation. The separate [approval record](SCAN_FALSE_POSITIVE_APPROVAL_2026-09-16.md)
and [exception registry](release-scan-exceptions.json) implement that decision,
effective through `2026-10-16T14:26:19Z`. The original scan result below is retained;
this technical triage itself is not a release-acceptance receipt.

- Source: `soklet` HEAD `0fa219ded6704b08c7dbd671276996a0f9a548a6`, full reachable ancestry (`gitleaks git --log-opts=HEAD`), 1,257 commits, approximately 39.88 MB scanned. Uncommitted changes are not included in this history scan.
- Scanner: Gitleaks 8.30.1, official Darwin arm64 archive SHA-256 `b40ab0ae55c505963e365f271a8d3846efbc170aa17f2607f13df610a9aeb6a5`.
- Exact existing release config SHA-256: `e163e53b9e7e8a8511e77271e2b323ed057759542a6d988258afe3a1fa329caf`.
- Result: scanner exit 1, 39 findings, all `generic-api-key`. Source context at each exact historical commit was inspected. They classify as 4 synthetic privacy canaries, 13 public format identifiers, 1 published test vector, 1 artifact checksum, 4 semantic inventory entries, 3 deterministic test keys, 7 golden test outputs, and 6 ordinary prose matches. None of these 39 inspected matches appears to be a real deployment credential.
- At triage time the exception registry was empty. The subsequently approved exact, time-bounded exceptions are now recorded separately; other findings and expired exceptions still fail the policy. The live candidate workflow must rerun after final owner commits; this macOS scan is not a substitute for its pinned Linux artifacts.
- No raw matched value or secret is included below. The companion JSON retains exact line/column metadata and scanner fingerprints for owner review.

| # | Path and lines | Historical commit | Classification / rationale |
|---|---|---|---|
| 1 | `src/test/java/com/soklet/RequestParameterFailureDiagnosticsTests.java:42` | `56f1e1518afd1fd4b1e6f494f6e223b4b584abf8` | Deliberate synthetic privacy/redaction canary in a regression test; not a deployment credential. |
| 2 | `verification/operational/src/main/java/com/soklet/OperationalHistoryHarness.java:92` | `8706955f443bddf9f26a489961d6ef917e2da84f` | Public trace-log format/version identifier or formatting expectation; the match is not key material. |
| 3 | `verification/operational/src/test/java/com/soklet/OperationalHistoryHarnessSelfTest.java:104` | `8706955f443bddf9f26a489961d6ef917e2da84f` | Public trace-log format/version identifier or formatting expectation; the match is not key material. |
| 4 | `verification/operational/src/test/java/com/soklet/OperationalHistoryHarnessSelfTest.java:309` | `8706955f443bddf9f26a489961d6ef917e2da84f` | Public trace-log format/version identifier or formatting expectation; the match is not key material. |
| 5 | `src/test/java/com/soklet/McpPrivacyBoundaryTests.java:66` | `960b8e05b1b2565522d30225b8a1be632e3b83ba` | Deliberate synthetic privacy/redaction canary in a regression test; not a deployment credential. |
| 6 | `src/test/java/com/soklet/McpPrivacyBoundaryTests.java:67–68` | `960b8e05b1b2565522d30225b8a1be632e3b83ba` | Deliberate synthetic privacy/redaction canary in a regression test; not a deployment credential. |
| 7 | `src/test/java/com/soklet/internal/mcp/protocol/McpPrivacyBoundaryInternalTests.java:39` | `960b8e05b1b2565522d30225b8a1be632e3b83ba` | Deliberate synthetic privacy/redaction canary in a regression test; not a deployment credential. |
| 8 | `release/MCP_REQUEST_STATE_SECURITY_PROFILE.md:291` | `720fdf14f63e0888683cac95051c6fb178f9ee02` | Documented frozen executable cryptographic vector; source explicitly identifies inputs as public test values forbidden as deployment keys. |
| 9 | `scripts/produce-release-history.mjs:33` | `4dbb2cd65f00ba5d50425c9a7a8af595960c6b67` | Published Jazzer API JAR SHA-256 integrity pin; not an API credential. |
| 10 | `scripts/verify-lifecycle-bound-harness-inventory.mjs:287` | `28600435201de21953a699a82541b8c0a0fcf83f` | Method-name/semantic inventory metadata matched after a token-related identifier; not a credential. |
| 11 | `scripts/verify-lifecycle-bound-harness-inventory.mjs:306` | `28600435201de21953a699a82541b8c0a0fcf83f` | Method-name/semantic inventory metadata matched after a token-related identifier; not a credential. |
| 12 | `scripts/verify-lifecycle-bound-harness-inventory.mjs:660` | `28600435201de21953a699a82541b8c0a0fcf83f` | Method-name/semantic inventory metadata matched after a token-related identifier; not a credential. |
| 13 | `scripts/verify-lifecycle-bound-harness-inventory.mjs:688` | `28600435201de21953a699a82541b8c0a0fcf83f` | Method-name/semantic inventory metadata matched after a token-related identifier; not a credential. |
| 14 | `src/test/java/examples/mcp/McpResourceCursorApplicationPatternsTests.java:86–87` | `d7c2aaea92db7bcf30f64c54d62c4d87ba6c1ab8` | Deterministic fixed test key for cursor/state integration tests; source constructs local test servers or explicitly documents managed production keys as separate. |
| 15 | `src/main/java/com/soklet/LogEventType.java:230` | `35b0700ad890975cb4168489f4bc59e70fc8a615` | Public trace-log format/version identifier or formatting expectation; the match is not key material. |
| 16 | `src/main/java/com/soklet/LogEventType.java:231` | `35b0700ad890975cb4168489f4bc59e70fc8a615` | Public trace-log format/version identifier or formatting expectation; the match is not key material. |
| 17 | `src/main/java/com/soklet/McpTraceLogRecord.java:38` | `35b0700ad890975cb4168489f4bc59e70fc8a615` | Public trace-log format/version identifier or formatting expectation; the match is not key material. |
| 18 | `src/test/java/com/soklet/McpRequestObservationPublicRuntimeTests.java:234` | `35b0700ad890975cb4168489f4bc59e70fc8a615` | Public trace-log format/version identifier or formatting expectation; the match is not key material. |
| 19 | `src/test/java/com/soklet/McpRequestObservationPublicRuntimeTests.java:262` | `35b0700ad890975cb4168489f4bc59e70fc8a615` | Public trace-log format/version identifier or formatting expectation; the match is not key material. |
| 20 | `src/test/java/com/soklet/McpRequestObservationPublicRuntimeTests.java:338` | `35b0700ad890975cb4168489f4bc59e70fc8a615` | Public trace-log format/version identifier or formatting expectation; the match is not key material. |
| 21 | `src/test/java/com/soklet/McpRequestObservationPublicRuntimeTests.java:437` | `35b0700ad890975cb4168489f4bc59e70fc8a615` | Public trace-log format/version identifier or formatting expectation; the match is not key material. |
| 22 | `src/test/java/com/soklet/McpRequestObservationPublicRuntimeTests.java:586` | `35b0700ad890975cb4168489f4bc59e70fc8a615` | Public trace-log format/version identifier or formatting expectation; the match is not key material. |
| 23 | `src/test/java/com/soklet/McpTraceLogRecordTests.java:32` | `35b0700ad890975cb4168489f4bc59e70fc8a615` | Golden trace-correlation token/HMAC expected output computed from deterministic test inputs; not a production credential. |
| 24 | `src/test/java/com/soklet/McpTraceLogRecordTests.java:48` | `35b0700ad890975cb4168489f4bc59e70fc8a615` | Public trace-log format/version identifier or formatting expectation; the match is not key material. |
| 25 | `src/test/java/com/soklet/McpTraceLogRecordTests.java:54` | `35b0700ad890975cb4168489f4bc59e70fc8a615` | Public trace-log format/version identifier or formatting expectation; the match is not key material. |
| 26 | `MCP.md:1860–1861` | `12cb7f300e51f923d104c76d85c7a78a03705c76` | Ordinary documentation prose describing unchanged API/owner-signature inventories; not a credential. |
| 27 | `SECURITY.md:796–797` | `12cb7f300e51f923d104c76d85c7a78a03705c76` | Ordinary documentation prose describing unchanged API/owner-signature inventories; not a credential. |
| 28 | `api/mcp/README.md:1028` | `12cb7f300e51f923d104c76d85c7a78a03705c76` | Ordinary documentation prose describing unchanged API/owner-signature inventories; not a credential. |
| 29 | `MCP.md:1765` | `a7d70e33e349a335ea3b0f0917307bd5b273d236` | Ordinary documentation prose describing unchanged API/owner-signature inventories; not a credential. |
| 30 | `README.md:1735–1736` | `65ab058d8f82d3572e71fcb797163f3b95c881db` | Ordinary documentation prose describing unchanged API/owner-signature inventories; not a credential. |
| 31 | `SECURITY.md:404` | `65ab058d8f82d3572e71fcb797163f3b95c881db` | Ordinary documentation prose describing unchanged API/owner-signature inventories; not a credential. |
| 32 | `src/test/java/com/soklet/McpRequestObservationPublicRuntimeTests.java:63–64` | `65ab058d8f82d3572e71fcb797163f3b95c881db` | Golden trace-correlation token/HMAC expected output computed from deterministic test inputs; not a production credential. |
| 33 | `src/test/java/com/soklet/McpRequestObservationPublicRuntimeTests.java:65–66` | `65ab058d8f82d3572e71fcb797163f3b95c881db` | Golden trace-correlation token/HMAC expected output computed from deterministic test inputs; not a production credential. |
| 34 | `src/test/java/com/soklet/McpSecurityControlsTests.java:50–51` | `81ad5daa87a90d87c909811330d6396fdf7e0361` | Golden trace-correlation token/HMAC expected output computed from deterministic test inputs; not a production credential. |
| 35 | `src/test/java/com/soklet/McpSecurityControlsTests.java:53–54` | `81ad5daa87a90d87c909811330d6396fdf7e0361` | Golden trace-correlation token/HMAC expected output computed from deterministic test inputs; not a production credential. |
| 36 | `src/test/java/com/soklet/McpSecurityControlsTests.java:55–56` | `81ad5daa87a90d87c909811330d6396fdf7e0361` | Golden trace-correlation token/HMAC expected output computed from deterministic test inputs; not a production credential. |
| 37 | `src/test/java/com/soklet/McpSecurityControlsTests.java:57–58` | `81ad5daa87a90d87c909811330d6396fdf7e0361` | Golden trace-correlation token/HMAC expected output computed from deterministic test inputs; not a production credential. |
| 38 | `src/test/java/com/soklet/McpRequestStatePublicRuntimeTests.java:299` | `72ae67dfc4355274ac5d8e4d35b54e17e83ecd70` | Deterministic fixed test key for cursor/state integration tests; source constructs local test servers or explicitly documents managed production keys as separate. |
| 39 | `src/test/java/com/soklet/McpRequestStatePublicRuntimeTests.java:301` | `72ae67dfc4355274ac5d8e4d35b54e17e83ecd70` | Deterministic fixed test key for cursor/state integration tests; source constructs local test servers or explicitly documents managed production keys as separate. |


## Exact scanner fingerprints

All rows use rule `generic-api-key`. These are scanner identities, not approvals. The release producer derives its own SHA-256 finding identity from the same exact commit/path/rule/line/column fields.

| # | Lines:columns (inclusive) | Gitleaks fingerprint |
|---|---|---|
| 1 | 42:11–42:45 | `56f1e1518afd1fd4b1e6f494f6e223b4b584abf8:src/test/java/com/soklet/RequestParameterFailureDiagnosticsTests.java:generic-api-key:42` |
| 2 | 92:9–92:52 | `8706955f443bddf9f26a489961d6ef917e2da84f:verification/operational/src/main/java/com/soklet/OperationalHistoryHarness.java:generic-api-key:92` |
| 3 | 104:29–104:72 | `8706955f443bddf9f26a489961d6ef917e2da84f:verification/operational/src/test/java/com/soklet/OperationalHistoryHarnessSelfTest.java:generic-api-key:104` |
| 4 | 309:11–309:54 | `8706955f443bddf9f26a489961d6ef917e2da84f:verification/operational/src/test/java/com/soklet/OperationalHistoryHarnessSelfTest.java:generic-api-key:309` |
| 5 | 66:31–66:64 | `960b8e05b1b2565522d30225b8a1be632e3b83ba:src/test/java/com/soklet/McpPrivacyBoundaryTests.java:generic-api-key:66` |
| 6 | 67:31–68:27 | `960b8e05b1b2565522d30225b8a1be632e3b83ba:src/test/java/com/soklet/McpPrivacyBoundaryTests.java:generic-api-key:67` |
| 7 | 39:31–39:64 | `960b8e05b1b2565522d30225b8a1be632e3b83ba:src/test/java/com/soklet/internal/mcp/protocol/McpPrivacyBoundaryInternalTests.java:generic-api-key:39` |
| 8 | 291:8–291:87 | `720fdf14f63e0888683cac95051c6fb178f9ee02:release/MCP_REQUEST_STATE_SECURITY_PROFILE.md:generic-api-key:291` |
| 9 | 33:4–33:74 | `4dbb2cd65f00ba5d50425c9a7a8af595960c6b67:scripts/produce-release-history.mjs:generic-api-key:33` |
| 10 | 287:8–287:134 | `28600435201de21953a699a82541b8c0a0fcf83f:scripts/verify-lifecycle-bound-harness-inventory.mjs:generic-api-key:287` |
| 11 | 306:8–306:137 | `28600435201de21953a699a82541b8c0a0fcf83f:scripts/verify-lifecycle-bound-harness-inventory.mjs:generic-api-key:306` |
| 12 | 660:8–660:134 | `28600435201de21953a699a82541b8c0a0fcf83f:scripts/verify-lifecycle-bound-harness-inventory.mjs:generic-api-key:660` |
| 13 | 688:8–688:137 | `28600435201de21953a699a82541b8c0a0fcf83f:scripts/verify-lifecycle-bound-harness-inventory.mjs:generic-api-key:688` |
| 14 | 86:31–87:38 | `d7c2aaea92db7bcf30f64c54d62c4d87ba6c1ab8:src/test/java/examples/mcp/McpResourceCursorApplicationPatternsTests.java:generic-api-key:86` |
| 15 | 230:6–230:49 | `35b0700ad890975cb4168489f4bc59e70fc8a615:src/main/java/com/soklet/LogEventType.java:generic-api-key:230` |
| 16 | 231:6–231:49 | `35b0700ad890975cb4168489f4bc59e70fc8a615:src/main/java/com/soklet/LogEventType.java:generic-api-key:231` |
| 17 | 38:23–38:70 | `35b0700ad890975cb4168489f4bc59e70fc8a615:src/main/java/com/soklet/McpTraceLogRecord.java:generic-api-key:38` |
| 18 | 234:8–234:51 | `35b0700ad890975cb4168489f4bc59e70fc8a615:src/test/java/com/soklet/McpRequestObservationPublicRuntimeTests.java:generic-api-key:234` |
| 19 | 262:8–262:51 | `35b0700ad890975cb4168489f4bc59e70fc8a615:src/test/java/com/soklet/McpRequestObservationPublicRuntimeTests.java:generic-api-key:262` |
| 20 | 338:8–338:51 | `35b0700ad890975cb4168489f4bc59e70fc8a615:src/test/java/com/soklet/McpRequestObservationPublicRuntimeTests.java:generic-api-key:338` |
| 21 | 437:8–437:51 | `35b0700ad890975cb4168489f4bc59e70fc8a615:src/test/java/com/soklet/McpRequestObservationPublicRuntimeTests.java:generic-api-key:437` |
| 22 | 586:9–586:52 | `35b0700ad890975cb4168489f4bc59e70fc8a615:src/test/java/com/soklet/McpRequestObservationPublicRuntimeTests.java:generic-api-key:586` |
| 23 | 32:31–32:62 | `35b0700ad890975cb4168489f4bc59e70fc8a615:src/test/java/com/soklet/McpTraceLogRecordTests.java:generic-api-key:32` |
| 24 | 48:7–48:50 | `35b0700ad890975cb4168489f4bc59e70fc8a615:src/test/java/com/soklet/McpTraceLogRecordTests.java:generic-api-key:48` |
| 25 | 54:7–54:50 | `35b0700ad890975cb4168489f4bc59e70fc8a615:src/test/java/com/soklet/McpTraceLogRecordTests.java:generic-api-key:54` |
| 26 | 1860:61–1861:17 | `12cb7f300e51f923d104c76d85c7a78a03705c76:MCP.md:generic-api-key:1860` |
| 27 | 796:61–797:17 | `12cb7f300e51f923d104c76d85c7a78a03705c76:SECURITY.md:generic-api-key:796` |
| 28 | 1028:31–1028:61 | `12cb7f300e51f923d104c76d85c7a78a03705c76:api/mcp/README.md:generic-api-key:1028` |
| 29 | 1765:42–1765:69 | `a7d70e33e349a335ea3b0f0917307bd5b273d236:MCP.md:generic-api-key:1765` |
| 30 | 1735:70–1736:17 | `65ab058d8f82d3572e71fcb797163f3b95c881db:README.md:generic-api-key:1735` |
| 31 | 404:14–404:41 | `65ab058d8f82d3572e71fcb797163f3b95c881db:SECURITY.md:generic-api-key:404` |
| 32 | 63:31–64:28 | `65ab058d8f82d3572e71fcb797163f3b95c881db:src/test/java/com/soklet/McpRequestObservationPublicRuntimeTests.java:generic-api-key:63` |
| 33 | 65:31–66:28 | `65ab058d8f82d3572e71fcb797163f3b95c881db:src/test/java/com/soklet/McpRequestObservationPublicRuntimeTests.java:generic-api-key:65` |
| 34 | 50:30–51:38 | `81ad5daa87a90d87c909811330d6396fdf7e0361:src/test/java/com/soklet/McpSecurityControlsTests.java:generic-api-key:50` |
| 35 | 53:31–54:28 | `81ad5daa87a90d87c909811330d6396fdf7e0361:src/test/java/com/soklet/McpSecurityControlsTests.java:generic-api-key:53` |
| 36 | 55:31–56:28 | `81ad5daa87a90d87c909811330d6396fdf7e0361:src/test/java/com/soklet/McpSecurityControlsTests.java:generic-api-key:55` |
| 37 | 57:31–58:28 | `81ad5daa87a90d87c909811330d6396fdf7e0361:src/test/java/com/soklet/McpSecurityControlsTests.java:generic-api-key:57` |
| 38 | 299:7–299:52 | `72ae67dfc4355274ac5d8e4d35b54e17e83ecd70:src/test/java/com/soklet/McpRequestStatePublicRuntimeTests.java:generic-api-key:299` |
| 39 | 301:7–301:52 | `72ae67dfc4355274ac5d8e4d35b54e17e83ecd70:src/test/java/com/soklet/McpRequestStatePublicRuntimeTests.java:generic-api-key:301` |
