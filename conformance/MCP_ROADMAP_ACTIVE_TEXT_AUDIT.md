# MCP roadmap active-text audit

Generated deterministically by `scripts/verify-mcp-public-evolution.mjs` from
`conformance/roadmap-readiness-active-text-rules.json`. Do not edit by hand.

Contract format: 2.

| Rule | Classification | Matcher | Expectation | Notice matcher | Scoped regions | Allowed matches | Rationale | Decision |
| --- | --- | --- | --- | --- | ---: | ---: | --- | --- |
| PROFILE-001 | latest-profile-qualification | <code>regex (case-sensitive) /automatic\s+"latest"\s+profile/gu</code> | nonzero-with-notice | <code>regex (case-sensitive) /neither selects an automatic\s+"latest"\s+profile nor falls back(?: to another revision)?/gu</code> | 2 | 2 | Every retained automatic/latest-profile reference is qualified by explicit fixed-profile and no-fallback language. | PASS |
| PROFILE-002 | latest-default-profile-prohibition | <code>regex (case-insensitive) /\b(?:latest-only&#124;automatically\s+selects?\s+(?:the\s+)?latest&#124;(?:the\s+)?latest\s+(?:MCP\s+)?profile\s+(?:is\s+)?selected\s+automatically&#124;default\s+profile)\b/giu</code> | zero | — | 6 | 0 | Public guidance must not claim latest-only selection, automatic latest selection, or an implicit default profile. | PASS |
| PROFILE-003 | fixed-production-profile | <code>literal (case-sensitive) "Soklet 4.0.x supports exactly the MCP `2026-07-28`"</code> | nonzero-with-notice | <code>literal (case-sensitive) "Soklet 4.0.x supports exactly the MCP `2026-07-28`"</code> | 2 | 2 | Default-path guidance names the one production profile explicitly. | PASS |
| AUTH-001 | authorization-isolation-prohibition | <code>regex (case-insensitive) /authorization[- ]isolat(?:ed&#124;ion)&#124;authorization\s+partition[\s\S]{0,160}(?:filters&#124;targets&#124;authorizes)\s+(?:the\s+)?subscription\s+events?/giu</code> | zero | — | 6 | 0 | Authorization partitions isolate registration, quota, and streams; they do not filter, target, or authorize subscription events. | PASS |
| AUTH-002 | subscription-authorization-boundary | <code>literal (case-sensitive) "authorization partition only scopes registration, quota accounting, and stream isolation"</code> | nonzero-with-notice | <code>literal (case-sensitive) "it is not an event target or semantic URI-authorization check"</code> | 1 | 1 | Security guidance states the exact non-authorizing role of a subscription authorization partition. | PASS |
| AUTH-003 | authorization-conformance-isolation | <code>literal (case-sensitive) "Transporting a challenge does not by itself make core Soklet or"</code> | nonzero-with-notice | <code>regex (case-sensitive) /Transporting a challenge does not by itself make core Soklet or\s+the deployment conformant with MCP Authorization/gu</code> | 1 | 1 | Transporting an application-owned challenge is not itself an MCP Authorization conformance claim. | PASS |
| COUNT-001 | reviewed-event-instrument-snapshots | <code>regex (case-insensitive) /(?:exactly\s+(?:22&#124;23)[\s\S]{0,80}(?:OpenTelemetry\s+instruments?&#124;public\s+final\s+(?:event\s+)?variants?&#124;event\s+variants?&#124;event-record\s+schemas?)&#124;(?:event\s+variants?&#124;event\s+hierarchy)[\s\S]{0,80}exactly\s+(?:22&#124;23)[\s\S]{0,40}(?:downstream&#124;OpenTelemetry\s+instruments?))/giu</code> | nonzero-with-notice | <code>regex (case-insensitive) /event variants?&#124;event hierarchy&#124;OpenTelemetry instruments?&#124;event-record schemas?&#124;downstream/giu</code> | 10 | 7 | Exact 23 and exact 22 claims are limited to the reviewed 23-event and 22-instrument snapshots. | PASS |
| CACHE-001 | cache-overclaim-prohibition | <code>regex (case-insensitive) /no-store[\s\S]{0,160}(?:only&#124;solely)[\s\S]{0,100}shared\s+caches?&#124;shared\s+caches?[\s\S]{0,160}(?:only&#124;solely)[\s\S]{0,100}no-store&#124;ETags?[\s\S]{0,160}(?:can\s+never&#124;cannot&#124;impossible&#124;will\s+never)&#124;(?:can\s+never&#124;cannot&#124;impossible&#124;will\s+never)[\s\S]{0,160}ETags?/giu</code> | zero | — | 6 | 0 | No text may narrow no-store to shared caches or promise that MCP ETags can never be added. | PASS |
| CACHE-002 | protocol-cache-versus-http-storage | <code>regex (case-sensitive) /Protocol cache hints do not turn the HTTP transport into a shared cache&#124;Every MCP HTTP response family—including early parser errors/gu</code> | nonzero-with-notice | <code>regex (case-sensitive) /Cache-Control: no-store/gu</code> | 2 | 2 | Protocol cache hints remain separate from the no-store HTTP storage policy. | PASS |
| TRANSPORT-001 | transport-and-tls-overclaim-prohibition | <code>regex (case-insensitive) /\btransport[- ]agnostic\b&#124;\bstdio\b[\s\S]{0,160}\b(?:impossible&#124;cannot&#124;interface)\b&#124;\b(?:impossible&#124;cannot)\b[\s\S]{0,160}\bstdio\b&#124;\bTLS\b[\s\S]{0,160}\broadmap\b&#124;\broadmap\b[\s\S]{0,160}\bTLS\b/giu</code> | zero | — | 6 | 0 | Guidance must not call Soklet transport-agnostic, call stdio impossible, or conflate TLS deployment policy with the protocol roadmap. | PASS |
| TRANSPORT-002 | stdio-current-limitation | <code>literal (case-sensitive) "Soklet 4.0.0 does not provide stdio transport"</code> | nonzero-with-notice | <code>literal (case-sensitive) "Soklet 4.0.0 does not provide stdio transport"</code> | 1 | 1 | The compatibility section describes stdio as an absent current transport, not an impossible one. | PASS |
| DPOP-001 | dpop-overclaim-prohibition | <code>regex (case-insensitive) /(?:built-in&#124;browser-complete&#124;browser\s+complete)[\s\S]{0,120}DPoP&#124;DPoP[\s\S]{0,120}(?:built-in&#124;browser-complete&#124;browser\s+complete)/giu</code> | zero | — | 6 | 0 | Core must not be described as having built-in or browser-complete DPoP support. | PASS |
| DPOP-002 | dpop-implementation-boundary | <code>literal (case-sensitive) "DPoP"</code> | nonzero-with-notice | <code>literal (case-sensitive) "core Soklet does not implement DPoP-bound access tokens"</code> | 1 | 1 | The retained DPoP reference states that core does not implement DPoP-bound tokens. | PASS |
| EXTENSION-001 | server-extension-overclaim-prohibition | <code>regex (case-insensitive) /(?:advertis(?:e&#124;es&#124;ed&#124;ing)&#124;support(?:s&#124;ed&#124;ing)?)[\s\S]{0,140}server\s+extensions?&#124;server\s+extensions?\s+(?:(?:is&#124;are)\s+)?support(?:s&#124;ed&#124;ing)?&#124;arbitrary\s+extension\s+methods?&#124;extension\s+methods?[\s\S]{0,120}(?:enabled&#124;support(?:s&#124;ed&#124;ing)?)/giu</code> | zero | — | 6 | 0 | Public guidance must not claim supported server-extension advertisement or arbitrary extension-method registration. | PASS |
| EXTENSION-002 | client-extension-isolation | <code>literal (case-sensitive) "Client extension settings are open but do not implicitly enable server\nbehavior."</code> | nonzero-with-notice | <code>literal (case-sensitive) "without inventing a core capability, advertising matching server support"</code> | 1 | 1 | Open client settings remain inspectable without enabling or advertising server behavior. | PASS |
| LIFECYCLE-001 | mcp-deprecated-whole-file-census | <code>regex (case-sensitive) /\b(?:Roots&#124;Sampling&#124;Logging)\b/gu</code> | nonzero-with-notice | <code>literal (case-sensitive) "SEP-2577"</code> | 3 | 20 | The whole-file census fails closed on every added, removed, or duplicated Roots, Sampling, and Logging token in the three target documents. | PASS |
| LIFECYCLE-002 | mcp-deprecated-default-mrtr | <code>regex (case-sensitive) /\b(?:Roots&#124;Sampling&#124;Logging)\b/gu</code> | zero | — | 2 | 0 | The default MRTR introduction and flagship Java example contain no deprecated MCP capability name. | PASS |
| LIFECYCLE-003 | mcp-deprecated-readme-default | <code>regex (case-sensitive) /\b(?:Roots&#124;Sampling&#124;Logging)\b/gu</code> | zero | — | 2 | 0 | The README recommended MCP setup and its Java quick-start contain no deprecated MCP capability name. | PASS |
| LIFECYCLE-004 | mcp-deprecated-reviewed-regions | <code>regex (case-sensitive) /\b(?:Roots&#124;Sampling&#124;Logging)\b/gu</code> | nonzero-with-notice | <code>regex (case-sensitive) /SEP-2577 (?:marks Roots,\s+Sampling, and Logging deprecated&#124;deprecates Roots and Sampling at the MCP layer)/gu</code> | 3 | 19 | Deprecated MCP capability references are allowed only in clearly labeled compatibility/security regions carrying the lifecycle notice. | PASS |
| LIFECYCLE-005 | reviewed-non-mcp-logging-reference | <code>literal (case-sensitive) "Logging"</code> | nonzero-with-notice | <code>literal (case-sensitive) "Logging via"</code> | 1 | 1 | The README's SLF4J/Logback bullet is an explicit non-MCP Logging false positive. | PASS |
| DCR-001 | dynamic-client-registration | <code>literal (case-sensitive) "Dynamic Client Registration is reviewed and not applicable because Soklet has"</code> | nonzero-with-notice | <code>literal (case-sensitive) "no OAuth/DCR implementation"</code> | 1 | 1 | Dynamic Client Registration remains reviewed N/A because Soklet provides no OAuth/DCR implementation. | PASS |
| EXAMPLE-001 | active-default-example | <code>literal (case-sensitive) "this raw-JSON tool requests active form Elicitation"</code> | nonzero-with-notice | <code>literal (case-sensitive) "this raw-JSON tool requests active form Elicitation"</code> | 1 | 1 | The flagship MRTR example explicitly teaches active form Elicitation. | PASS |

## Scoped regions

| Rule | Path | Complete scope identity |
| --- | --- | --- |
| PROFILE-001 | <code>MCP.md</code> | <code>headingSubtree&#124;headingPath=["Model Context Protocol (MCP)","Multi-round-trip input and request state"]&#124;role=defaultPath</code> |
| PROFILE-001 | <code>README.md</code> | <code>headingSubtree&#124;headingPath=["What Else Does It Do?","Model Context Protocol (MCP)","Deprecated compatibility surfaces"]&#124;role=compatibility</code> |
| PROFILE-002 | <code>MCP.md</code> | <code>wholeFile&#124;role=factualSupport</code> |
| PROFILE-002 | <code>README.md</code> | <code>wholeFile&#124;role=factualSupport</code> |
| PROFILE-002 | <code>SECURITY.md</code> | <code>wholeFile&#124;role=security</code> |
| PROFILE-002 | <code>api/mcp/README.md</code> | <code>wholeFile&#124;role=factualSupport</code> |
| PROFILE-002 | <code>CHANGELOG.md</code> | <code>wholeFile&#124;role=migration</code> |
| PROFILE-002 | <code>release/README.md</code> | <code>wholeFile&#124;role=factualSupport</code> |
| PROFILE-003 | <code>MCP.md</code> | <code>headingSubtree&#124;headingPath=["Model Context Protocol (MCP)","Multi-round-trip input and request state"]&#124;role=defaultPath</code> |
| PROFILE-003 | <code>README.md</code> | <code>headingSubtree&#124;headingPath=["What Else Does It Do?","Model Context Protocol (MCP)","Recommended MCP setup"]&#124;role=defaultPath</code> |
| AUTH-001 | <code>MCP.md</code> | <code>wholeFile&#124;role=factualSupport</code> |
| AUTH-001 | <code>README.md</code> | <code>wholeFile&#124;role=factualSupport</code> |
| AUTH-001 | <code>SECURITY.md</code> | <code>wholeFile&#124;role=security</code> |
| AUTH-001 | <code>api/mcp/README.md</code> | <code>wholeFile&#124;role=factualSupport</code> |
| AUTH-001 | <code>CHANGELOG.md</code> | <code>wholeFile&#124;role=migration</code> |
| AUTH-001 | <code>release/README.md</code> | <code>wholeFile&#124;role=factualSupport</code> |
| AUTH-002 | <code>SECURITY.md</code> | <code>headingSubtree&#124;headingPath=["Security Policy","MCP Deployment Security"]&#124;role=security</code> |
| AUTH-003 | <code>SECURITY.md</code> | <code>headingSubtree&#124;headingPath=["Security Policy","MCP Deployment Security"]&#124;role=security</code> |
| COUNT-001 | <code>MCP.md</code> | <code>wholeFile&#124;role=factualSupport</code> |
| COUNT-001 | <code>MCP.md</code> | <code>headingSubtree&#124;headingPath=["Model Context Protocol (MCP)","Lifecycle and metrics"]&#124;role=factualSupport</code> |
| COUNT-001 | <code>README.md</code> | <code>wholeFile&#124;role=factualSupport</code> |
| COUNT-001 | <code>README.md</code> | <code>headingSubtree&#124;headingPath=["What Else Does It Do?","Metrics Collection"]&#124;role=factualSupport</code> |
| COUNT-001 | <code>SECURITY.md</code> | <code>wholeFile&#124;role=security</code> |
| COUNT-001 | <code>SECURITY.md</code> | <code>headingSubtree&#124;headingPath=["Security Policy","MCP Deployment Security"]&#124;role=security</code> |
| COUNT-001 | <code>api/mcp/README.md</code> | <code>wholeFile&#124;role=factualSupport</code> |
| COUNT-001 | <code>CHANGELOG.md</code> | <code>wholeFile&#124;role=migration</code> |
| COUNT-001 | <code>CHANGELOG.md</code> | <code>headingSubtree&#124;headingPath=["Changelog","4.0.0 (2026-09-01)","Detailed Implementation Record"]&#124;role=migration</code> |
| COUNT-001 | <code>release/README.md</code> | <code>wholeFile&#124;role=factualSupport</code> |
| CACHE-001 | <code>MCP.md</code> | <code>wholeFile&#124;role=factualSupport</code> |
| CACHE-001 | <code>README.md</code> | <code>wholeFile&#124;role=factualSupport</code> |
| CACHE-001 | <code>SECURITY.md</code> | <code>wholeFile&#124;role=security</code> |
| CACHE-001 | <code>api/mcp/README.md</code> | <code>wholeFile&#124;role=factualSupport</code> |
| CACHE-001 | <code>CHANGELOG.md</code> | <code>wholeFile&#124;role=migration</code> |
| CACHE-001 | <code>release/README.md</code> | <code>wholeFile&#124;role=factualSupport</code> |
| CACHE-002 | <code>MCP.md</code> | <code>headingSubtree&#124;headingPath=["Model Context Protocol (MCP)","Resources and pagination"]&#124;role=factualSupport</code> |
| CACHE-002 | <code>SECURITY.md</code> | <code>headingSubtree&#124;headingPath=["Security Policy","MCP Deployment Security"]&#124;role=security</code> |
| TRANSPORT-001 | <code>MCP.md</code> | <code>wholeFile&#124;role=factualSupport</code> |
| TRANSPORT-001 | <code>README.md</code> | <code>wholeFile&#124;role=factualSupport</code> |
| TRANSPORT-001 | <code>SECURITY.md</code> | <code>wholeFile&#124;role=security</code> |
| TRANSPORT-001 | <code>api/mcp/README.md</code> | <code>wholeFile&#124;role=factualSupport</code> |
| TRANSPORT-001 | <code>CHANGELOG.md</code> | <code>wholeFile&#124;role=migration</code> |
| TRANSPORT-001 | <code>release/README.md</code> | <code>wholeFile&#124;role=factualSupport</code> |
| TRANSPORT-002 | <code>MCP.md</code> | <code>headingSubtree&#124;headingPath=["Model Context Protocol (MCP)","Compatibility and unsupported features"]&#124;role=compatibility</code> |
| DPOP-001 | <code>MCP.md</code> | <code>wholeFile&#124;role=factualSupport</code> |
| DPOP-001 | <code>README.md</code> | <code>wholeFile&#124;role=factualSupport</code> |
| DPOP-001 | <code>SECURITY.md</code> | <code>wholeFile&#124;role=security</code> |
| DPOP-001 | <code>api/mcp/README.md</code> | <code>wholeFile&#124;role=factualSupport</code> |
| DPOP-001 | <code>CHANGELOG.md</code> | <code>wholeFile&#124;role=migration</code> |
| DPOP-001 | <code>release/README.md</code> | <code>wholeFile&#124;role=factualSupport</code> |
| DPOP-002 | <code>MCP.md</code> | <code>headingSubtree&#124;headingPath=["Model Context Protocol (MCP)","Compatibility and unsupported features"]&#124;role=compatibility</code> |
| EXTENSION-001 | <code>MCP.md</code> | <code>wholeFile&#124;role=factualSupport</code> |
| EXTENSION-001 | <code>README.md</code> | <code>wholeFile&#124;role=factualSupport</code> |
| EXTENSION-001 | <code>SECURITY.md</code> | <code>wholeFile&#124;role=security</code> |
| EXTENSION-001 | <code>api/mcp/README.md</code> | <code>wholeFile&#124;role=factualSupport</code> |
| EXTENSION-001 | <code>CHANGELOG.md</code> | <code>wholeFile&#124;role=migration</code> |
| EXTENSION-001 | <code>release/README.md</code> | <code>wholeFile&#124;role=factualSupport</code> |
| EXTENSION-002 | <code>MCP.md</code> | <code>headingSubtree&#124;headingPath=["Model Context Protocol (MCP)","Compatibility and unsupported features"]&#124;role=compatibility</code> |
| LIFECYCLE-001 | <code>MCP.md</code> | <code>wholeFile&#124;role=factualSupport</code> |
| LIFECYCLE-001 | <code>README.md</code> | <code>wholeFile&#124;role=factualSupport</code> |
| LIFECYCLE-001 | <code>SECURITY.md</code> | <code>wholeFile&#124;role=security</code> |
| LIFECYCLE-002 | <code>MCP.md</code> | <code>headingSubtree&#124;headingPath=["Model Context Protocol (MCP)","Multi-round-trip input and request state"]&#124;role=defaultPath</code> |
| LIFECYCLE-002 | <code>MCP.md</code> | <code>fencedBlock&#124;headingPath=["Model Context Protocol (MCP)","Multi-round-trip input and request state"]&#124;fenceLanguage="java"&#124;role=defaultPath</code> |
| LIFECYCLE-003 | <code>README.md</code> | <code>headingSubtree&#124;headingPath=["What Else Does It Do?","Model Context Protocol (MCP)","Recommended MCP setup"]&#124;role=defaultPath</code> |
| LIFECYCLE-003 | <code>README.md</code> | <code>fencedBlock&#124;headingPath=["What Else Does It Do?","Model Context Protocol (MCP)","Recommended MCP setup"]&#124;fenceLanguage="java"&#124;role=defaultPath</code> |
| LIFECYCLE-004 | <code>MCP.md</code> | <code>headingSubtree&#124;headingPath=["Model Context Protocol (MCP)","Compatibility and unsupported features","Deprecated compatibility surfaces"]&#124;role=compatibility</code> |
| LIFECYCLE-004 | <code>README.md</code> | <code>headingSubtree&#124;headingPath=["What Else Does It Do?","Model Context Protocol (MCP)","Deprecated compatibility surfaces"]&#124;role=compatibility</code> |
| LIFECYCLE-004 | <code>SECURITY.md</code> | <code>headingSubtree&#124;headingPath=["Security Policy","MCP Deployment Security","Deprecated compatibility surfaces"]&#124;role=security</code> |
| LIFECYCLE-005 | <code>README.md</code> | <code>headingSubtree&#124;headingPath=["Building Real-World Apps"]&#124;role=factualSupport</code> |
| DCR-001 | <code>MCP.md</code> | <code>headingSubtree&#124;headingPath=["Model Context Protocol (MCP)","Current Phase 6 and release state"]&#124;role=factualSupport</code> |
| EXAMPLE-001 | <code>MCP.md</code> | <code>headingSubtree&#124;headingPath=["Model Context Protocol (MCP)","Multi-round-trip input and request state"]&#124;role=defaultPath</code> |

## Allowed-match fingerprints

| Rule | Path | Matched text | Complete scope identity |
| --- | --- | --- | --- |
| PROFILE-001 | <code>MCP.md</code> | <code>"automatic\n\"latest\" profile"</code> | <code>headingSubtree&#124;headingPath=["Model Context Protocol (MCP)","Multi-round-trip input and request state"]&#124;role=defaultPath</code> |
| PROFILE-001 | <code>README.md</code> | <code>"automatic\n\"latest\" profile"</code> | <code>headingSubtree&#124;headingPath=["What Else Does It Do?","Model Context Protocol (MCP)","Deprecated compatibility surfaces"]&#124;role=compatibility</code> |
| PROFILE-002 | — | — | — |
| PROFILE-003 | <code>MCP.md</code> | <code>"Soklet 4.0.x supports exactly the MCP `2026-07-28`"</code> | <code>headingSubtree&#124;headingPath=["Model Context Protocol (MCP)","Multi-round-trip input and request state"]&#124;role=defaultPath</code> |
| PROFILE-003 | <code>README.md</code> | <code>"Soklet 4.0.x supports exactly the MCP `2026-07-28`"</code> | <code>headingSubtree&#124;headingPath=["What Else Does It Do?","Model Context Protocol (MCP)","Recommended MCP setup"]&#124;role=defaultPath</code> |
| AUTH-001 | — | — | — |
| AUTH-002 | <code>SECURITY.md</code> | <code>"authorization partition only scopes registration, quota accounting, and stream isolation"</code> | <code>headingSubtree&#124;headingPath=["Security Policy","MCP Deployment Security"]&#124;role=security</code> |
| AUTH-003 | <code>SECURITY.md</code> | <code>"Transporting a challenge does not by itself make core Soklet or"</code> | <code>headingSubtree&#124;headingPath=["Security Policy","MCP Deployment Security"]&#124;role=security</code> |
| COUNT-001 | <code>MCP.md</code> | <code>"event\nvariants to exactly 22 OpenTelemetry instruments"</code> | <code>headingSubtree&#124;headingPath=["Model Context Protocol (MCP)","Lifecycle and metrics"]&#124;role=factualSupport</code> |
| COUNT-001 | <code>MCP.md</code> | <code>"exactly 23 public final variants"</code> | <code>headingSubtree&#124;headingPath=["Model Context Protocol (MCP)","Lifecycle and metrics"]&#124;role=factualSupport</code> |
| COUNT-001 | <code>README.md</code> | <code>"exactly 22 OpenTelemetry instruments"</code> | <code>headingSubtree&#124;headingPath=["What Else Does It Do?","Metrics Collection"]&#124;role=factualSupport</code> |
| COUNT-001 | <code>README.md</code> | <code>"exactly 23 public final event variants"</code> | <code>headingSubtree&#124;headingPath=["What Else Does It Do?","Metrics Collection"]&#124;role=factualSupport</code> |
| COUNT-001 | <code>SECURITY.md</code> | <code>"event variants map to exactly 22 downstream"</code> | <code>headingSubtree&#124;headingPath=["Security Policy","MCP Deployment Security"]&#124;role=security</code> |
| COUNT-001 | <code>CHANGELOG.md</code> | <code>"exactly 23 event-record schemas"</code> | <code>headingSubtree&#124;headingPath=["Changelog","4.0.0 (2026-09-01)","Detailed Implementation Record"]&#124;role=migration</code> |
| COUNT-001 | <code>CHANGELOG.md</code> | <code>"exactly 22\n  OpenTelemetry instruments"</code> | <code>headingSubtree&#124;headingPath=["Changelog","4.0.0 (2026-09-01)","Detailed Implementation Record"]&#124;role=migration</code> |
| CACHE-001 | — | — | — |
| CACHE-002 | <code>MCP.md</code> | <code>"Protocol cache hints do not turn the HTTP transport into a shared cache"</code> | <code>headingSubtree&#124;headingPath=["Model Context Protocol (MCP)","Resources and pagination"]&#124;role=factualSupport</code> |
| CACHE-002 | <code>SECURITY.md</code> | <code>"Every MCP HTTP response family—including early parser errors"</code> | <code>headingSubtree&#124;headingPath=["Security Policy","MCP Deployment Security"]&#124;role=security</code> |
| TRANSPORT-001 | — | — | — |
| TRANSPORT-002 | <code>MCP.md</code> | <code>"Soklet 4.0.0 does not provide stdio transport"</code> | <code>headingSubtree&#124;headingPath=["Model Context Protocol (MCP)","Compatibility and unsupported features"]&#124;role=compatibility</code> |
| DPOP-001 | — | — | — |
| DPOP-002 | <code>MCP.md</code> | <code>"DPoP"</code> | <code>headingSubtree&#124;headingPath=["Model Context Protocol (MCP)","Compatibility and unsupported features"]&#124;role=compatibility</code> |
| EXTENSION-001 | — | — | — |
| EXTENSION-002 | <code>MCP.md</code> | <code>"Client extension settings are open but do not implicitly enable server\nbehavior."</code> | <code>headingSubtree&#124;headingPath=["Model Context Protocol (MCP)","Compatibility and unsupported features"]&#124;role=compatibility</code> |
| LIFECYCLE-001 | <code>MCP.md</code> | <code>"Roots"</code> | <code>wholeFile&#124;role=factualSupport</code> |
| LIFECYCLE-001 | <code>MCP.md</code> | <code>"Sampling"</code> | <code>wholeFile&#124;role=factualSupport</code> |
| LIFECYCLE-001 | <code>MCP.md</code> | <code>"Logging"</code> | <code>wholeFile&#124;role=factualSupport</code> |
| LIFECYCLE-001 | <code>MCP.md</code> | <code>"Roots"</code> | <code>wholeFile&#124;role=factualSupport</code> |
| LIFECYCLE-001 | <code>MCP.md</code> | <code>"Sampling"</code> | <code>wholeFile&#124;role=factualSupport</code> |
| LIFECYCLE-001 | <code>MCP.md</code> | <code>"Sampling"</code> | <code>wholeFile&#124;role=factualSupport</code> |
| LIFECYCLE-001 | <code>MCP.md</code> | <code>"Roots"</code> | <code>wholeFile&#124;role=factualSupport</code> |
| LIFECYCLE-001 | <code>MCP.md</code> | <code>"Logging"</code> | <code>wholeFile&#124;role=factualSupport</code> |
| LIFECYCLE-001 | <code>MCP.md</code> | <code>"Logging"</code> | <code>wholeFile&#124;role=factualSupport</code> |
| LIFECYCLE-001 | <code>README.md</code> | <code>"Logging"</code> | <code>wholeFile&#124;role=factualSupport</code> |
| LIFECYCLE-001 | <code>README.md</code> | <code>"Roots"</code> | <code>wholeFile&#124;role=factualSupport</code> |
| LIFECYCLE-001 | <code>README.md</code> | <code>"Sampling"</code> | <code>wholeFile&#124;role=factualSupport</code> |
| LIFECYCLE-001 | <code>README.md</code> | <code>"Logging"</code> | <code>wholeFile&#124;role=factualSupport</code> |
| LIFECYCLE-001 | <code>README.md</code> | <code>"Roots"</code> | <code>wholeFile&#124;role=factualSupport</code> |
| LIFECYCLE-001 | <code>README.md</code> | <code>"Sampling"</code> | <code>wholeFile&#124;role=factualSupport</code> |
| LIFECYCLE-001 | <code>README.md</code> | <code>"Logging"</code> | <code>wholeFile&#124;role=factualSupport</code> |
| LIFECYCLE-001 | <code>README.md</code> | <code>"Logging"</code> | <code>wholeFile&#124;role=factualSupport</code> |
| LIFECYCLE-001 | <code>SECURITY.md</code> | <code>"Roots"</code> | <code>wholeFile&#124;role=security</code> |
| LIFECYCLE-001 | <code>SECURITY.md</code> | <code>"Sampling"</code> | <code>wholeFile&#124;role=security</code> |
| LIFECYCLE-001 | <code>SECURITY.md</code> | <code>"Logging"</code> | <code>wholeFile&#124;role=security</code> |
| LIFECYCLE-002 | — | — | — |
| LIFECYCLE-003 | — | — | — |
| LIFECYCLE-004 | <code>MCP.md</code> | <code>"Roots"</code> | <code>headingSubtree&#124;headingPath=["Model Context Protocol (MCP)","Compatibility and unsupported features","Deprecated compatibility surfaces"]&#124;role=compatibility</code> |
| LIFECYCLE-004 | <code>MCP.md</code> | <code>"Sampling"</code> | <code>headingSubtree&#124;headingPath=["Model Context Protocol (MCP)","Compatibility and unsupported features","Deprecated compatibility surfaces"]&#124;role=compatibility</code> |
| LIFECYCLE-004 | <code>MCP.md</code> | <code>"Logging"</code> | <code>headingSubtree&#124;headingPath=["Model Context Protocol (MCP)","Compatibility and unsupported features","Deprecated compatibility surfaces"]&#124;role=compatibility</code> |
| LIFECYCLE-004 | <code>MCP.md</code> | <code>"Roots"</code> | <code>headingSubtree&#124;headingPath=["Model Context Protocol (MCP)","Compatibility and unsupported features","Deprecated compatibility surfaces"]&#124;role=compatibility</code> |
| LIFECYCLE-004 | <code>MCP.md</code> | <code>"Sampling"</code> | <code>headingSubtree&#124;headingPath=["Model Context Protocol (MCP)","Compatibility and unsupported features","Deprecated compatibility surfaces"]&#124;role=compatibility</code> |
| LIFECYCLE-004 | <code>MCP.md</code> | <code>"Sampling"</code> | <code>headingSubtree&#124;headingPath=["Model Context Protocol (MCP)","Compatibility and unsupported features","Deprecated compatibility surfaces"]&#124;role=compatibility</code> |
| LIFECYCLE-004 | <code>MCP.md</code> | <code>"Roots"</code> | <code>headingSubtree&#124;headingPath=["Model Context Protocol (MCP)","Compatibility and unsupported features","Deprecated compatibility surfaces"]&#124;role=compatibility</code> |
| LIFECYCLE-004 | <code>MCP.md</code> | <code>"Logging"</code> | <code>headingSubtree&#124;headingPath=["Model Context Protocol (MCP)","Compatibility and unsupported features","Deprecated compatibility surfaces"]&#124;role=compatibility</code> |
| LIFECYCLE-004 | <code>MCP.md</code> | <code>"Logging"</code> | <code>headingSubtree&#124;headingPath=["Model Context Protocol (MCP)","Compatibility and unsupported features","Deprecated compatibility surfaces"]&#124;role=compatibility</code> |
| LIFECYCLE-004 | <code>README.md</code> | <code>"Roots"</code> | <code>headingSubtree&#124;headingPath=["What Else Does It Do?","Model Context Protocol (MCP)","Deprecated compatibility surfaces"]&#124;role=compatibility</code> |
| LIFECYCLE-004 | <code>README.md</code> | <code>"Sampling"</code> | <code>headingSubtree&#124;headingPath=["What Else Does It Do?","Model Context Protocol (MCP)","Deprecated compatibility surfaces"]&#124;role=compatibility</code> |
| LIFECYCLE-004 | <code>README.md</code> | <code>"Logging"</code> | <code>headingSubtree&#124;headingPath=["What Else Does It Do?","Model Context Protocol (MCP)","Deprecated compatibility surfaces"]&#124;role=compatibility</code> |
| LIFECYCLE-004 | <code>README.md</code> | <code>"Roots"</code> | <code>headingSubtree&#124;headingPath=["What Else Does It Do?","Model Context Protocol (MCP)","Deprecated compatibility surfaces"]&#124;role=compatibility</code> |
| LIFECYCLE-004 | <code>README.md</code> | <code>"Sampling"</code> | <code>headingSubtree&#124;headingPath=["What Else Does It Do?","Model Context Protocol (MCP)","Deprecated compatibility surfaces"]&#124;role=compatibility</code> |
| LIFECYCLE-004 | <code>README.md</code> | <code>"Logging"</code> | <code>headingSubtree&#124;headingPath=["What Else Does It Do?","Model Context Protocol (MCP)","Deprecated compatibility surfaces"]&#124;role=compatibility</code> |
| LIFECYCLE-004 | <code>README.md</code> | <code>"Logging"</code> | <code>headingSubtree&#124;headingPath=["What Else Does It Do?","Model Context Protocol (MCP)","Deprecated compatibility surfaces"]&#124;role=compatibility</code> |
| LIFECYCLE-004 | <code>SECURITY.md</code> | <code>"Roots"</code> | <code>headingSubtree&#124;headingPath=["Security Policy","MCP Deployment Security","Deprecated compatibility surfaces"]&#124;role=security</code> |
| LIFECYCLE-004 | <code>SECURITY.md</code> | <code>"Sampling"</code> | <code>headingSubtree&#124;headingPath=["Security Policy","MCP Deployment Security","Deprecated compatibility surfaces"]&#124;role=security</code> |
| LIFECYCLE-004 | <code>SECURITY.md</code> | <code>"Logging"</code> | <code>headingSubtree&#124;headingPath=["Security Policy","MCP Deployment Security","Deprecated compatibility surfaces"]&#124;role=security</code> |
| LIFECYCLE-005 | <code>README.md</code> | <code>"Logging"</code> | <code>headingSubtree&#124;headingPath=["Building Real-World Apps"]&#124;role=factualSupport</code> |
| DCR-001 | <code>MCP.md</code> | <code>"Dynamic Client Registration is reviewed and not applicable because Soklet has"</code> | <code>headingSubtree&#124;headingPath=["Model Context Protocol (MCP)","Current Phase 6 and release state"]&#124;role=factualSupport</code> |
| EXAMPLE-001 | <code>MCP.md</code> | <code>"this raw-JSON tool requests active form Elicitation"</code> | <code>headingSubtree&#124;headingPath=["Model Context Protocol (MCP)","Multi-round-trip input and request state"]&#124;role=defaultPath</code> |

Total: 22 active-text rules passed.
