# Model Context Protocol (MCP)

Soklet supports MCP `2026-07-28` and explicitly selected `2025-06-18` and
`2025-11-25` compatibility profiles on the same endpoint URL. Both 2025 profiles
support synchronous tools, ordinary prompts/resources, argument completion,
request-scoped POST progress, and static catalog pagination. Explicitly enabled
sessions retain public client metadata and enable active-request cancelation.
Configured HTTP transport admission enables verified DELETE and, with selected
subscriptions and effective change sources, leased GET resource/catalog
invalidations backed by session-owned URI grants. Skills, Apps UI, durable Tasks,
and multi-round input remain exclusive to `2026-07-28`. MCP support is part
of core Soklet and uses a dedicated `McpServer` listener; it is not mounted in
the ordinary `HttpServer` or `SseServer`. The API and implementation ship in
the zero-runtime-dependency `com.soklet:soklet` artifact; there is no separate
`soklet-mcp` component.

The examples in this guide use Soklet `4.0.0`.

Start with the copy/paste [MCP quickstart](MCP_QUICKSTART.md): it includes the
dependency and annotation-processor setup, `-parameters`, one annotated tool,
endpoint/server construction, application lifecycle, raw localhost discovery,
and an exact Inspector command.

This reference covers multi-round-trip request state, durable Tasks,
progress/cancelation, subscriptions, localization, lifecycle and aggregate
metrics, bounded off-network simulation, downstream OpenTelemetry integration,
and bounded structured trace-correlation logging.

The [MCP privacy boundary](release/MCP_PRIVACY_BOUNDARY.md) explains which
diagnostic, metric, callback, and simulator values core Soklet redacts or
deliberately leaves application- and operator-owned.
The worked [OAuth resource-server pattern](release/MCP_OAUTH_RESOURCE_SERVER.md)
shows the application-owned authentication boundary, and the dated
[client compatibility matrix](release/MCP_CLIENT_COMPATIBILITY.md) records
exactly which host/tool versions were manually exercised.

## Current `2026-07-28` support

| Area | Current behavior |
| --- | --- |
| Transport | Dedicated HTTP/1.1 listener and port; direct first-request discovery; no initialization or session lifecycle for `2026-07-28` requests |
| Endpoints | One or more exact, non-root paths on one server; capability and operation catalogs remain endpoint-local |
| Tools | Annotated and programmatic discovery, typed or JSON-object arguments, complete typed results, content results, rate limiting, interception, and output sanitization |
| Prompts | Annotated and programmatic catalogs plus string-argument prompt rendering |
| Resources | Exact URIs, bounded RFC 6570 Level 1 URI templates, reads, static catalogs, and application-owned custom listing/pagination |
| Skills | Programmatic immutable bundles, `skills/list` and `skills/get`, authorized canonical file reads, locale groups, and application-owned pagination |
| Multi-round-trip | Declared `input_required` results and retries for tools, prompt gets, and resource reads; application- or framework-protected request state |
| Tasks | `tools/call` task augmentation, durable application-owned task state, polling, input, cooperative cancelation, typed deferred-result validation, and optional status notifications |
| Invocation control | Request-scoped progress over the MCP response stream plus cooperative cancelation for every application handler |
| Subscriptions | Long-lived `subscriptions/listen` streams for resource/tool/prompt list changes, requested-resource updates, and authorized task IDs; bounded authorization leases and application-owned local or distributed broadcast publishing |
| Localization | Request-scoped library-neutral localization for framework-owned server, tool, prompt, resource, and schema text; no protocol capability or `_meta` extension |
| Simulation | Asynchronous off-network MCP HTTP requests, including POST, session-enabled 2025 GET/DELETE, and OPTIONS preflight, through the real processor/lifecycle with bounded JSON and exact SSE capture; no listener, bound address, or public diagnostic activity |
| Bounded observation | Exactly one clean/residual outcome per successfully started listener generation, plus server-wide active-handler, queued-request, queue-full-rejection, and immutable handler-capacity, live-stream, protection, and trace-configuration diagnostics |
| Trace logging | Default-off pseudonymous correlation and a separate raw-validated-trace-ID opt-in through bounded `MCP_TRACE_CORRELATION` log records; no trace metric dimensions |
| Policy | Host and Origin checks, application admission, optional request limiting, mandatory fallback tool limiting for tool-bearing servers, bounded execution, and shared Soklet observation hosts |
| Schema | Closed Soklet MCP Tool Schema Profile 1 with Java-derived schemas and public authored input-schema registration |

## Exact protocol revisions

The 4.0 development API requires at least one exact `McpProtocolVersion` on
every endpoint and independently callable operation. `@McpServerEndpoint`,
`@McpTool`, `@McpPrompt`, `@McpResource`, `@McpResourceList`,
`@McpPromptCompletion`, and `@McpResourceCompletion` have required
`protocolVersions` members. Programmatic endpoint, tool, prompt, resource,
and Skill starting factories take a nonempty `Set<McpProtocolVersion>`.
`@McpAppTool` and `McpAppToolMetadata.withProtocolVersions(...)` separately
identify the revisions that carry Apps presentation metadata; that set must
fit inside the owning tool's revisions.
An operation's revisions must be a subset of its endpoint's revisions. There
is no implicit "latest" or default protocol revision. The endpoint's
`version` member is the *application implementation version* reported to
clients, not the MCP protocol version.

For example, one annotated endpoint can serve a modern tool and expose the
same synchronous tool to two 2025 revisions at one URL:

```java
@McpServerEndpoint(
    path = "/catalog/mcp", name = "catalog", version = "1.0.0",
    protocolVersions = {
        McpProtocolVersion.V2026_07_28,
        McpProtocolVersion.V2025_06_18,
        McpProtocolVersion.V2025_11_25})
public final class CatalogMcpEndpoint {
  @McpTool(
      name = "catalog.search",
      protocolVersions = {
          McpProtocolVersion.V2026_07_28,
          McpProtocolVersion.V2025_06_18,
          McpProtocolVersion.V2025_11_25})
  public SearchResult search(
      @McpToolArgument(name = "query") String query) {
    return new SearchResult(List.of("Match for " + query));
  }

  public record SearchResult(List<String> matches) {}
}
```

The equivalent programmatic entry points are
`McpEndpoint.withPath(path, implementation, protocolVersions)` and
`McpToolRegistration.withName(name, protocolVersions)`. A tool may select a
smaller set than the endpoint. The selected revision determines both catalog
visibility and call eligibility; a name omitted from `tools/list` cannot be
invoked by guessing it. `McpRequestContext`, `McpAdmissionContext`, and
`McpRateLimitContext` expose the selected `McpProtocolVersion` through
`getProtocolVersion()` for application policy.

On `2026-07-28`, clients may send a direct versioned request or call
`server/discover`. On the explicitly selected `2025-06-18` and `2025-11-25` path, clients
start with `initialize`, may send `notifications/initialized`, and can call
`ping`. Later POST requests use their selected `MCP-Protocol-Version` header;
the default stateless view does not issue a session ID. Explicit endpoint and
server configuration enables the [minimum 2025 session package](#explicitly-enabled-2025-sessions).

Initialization negotiates from `params.protocolVersion`: a supported body
version is returned unchanged. If that proposal is unsupported, a supplied,
supported 2025 `MCP-Protocol-Version` header can guide the counteroffer;
otherwise Soklet offers its newest configured 2025 revision. A present invalid
or unsupported header still fails with HTTP 400. For example, a June-only
endpoint accepts a November proposal with a static June header and replies
with June. This does not enable March support or reinterpret modern framing.

Later legacy-shaped requests may omit the version header only when the endpoint
serves exactly one protocol revision and that revision is supported 2025.
That explicit endpoint configuration identifies the revision for POST,
notifications, and configured session GET/DELETE. A multi-version or modern
endpoint still requires the header, including when a session ID is present;
Soklet does not inspect an unauthenticated session to select an admission view.
Clients should send the negotiated version on every subsequent request.
Well-formed unsupported 2025 client notifications, including
`notifications/roots/list_changed`, are admitted, request-limited, and ignored
with empty HTTP 202. Session-enabled views verify the session first, so retired
or wrong-owner sessions receive the same neutral HTTP 404. This acknowledgment
does not enable roots, sampling, or other server-initiated calls. Modern
unsupported notifications retain their HTTP 400 behavior.

An optional HTTP transport admission controller enables leased GET opening and
verified DELETE retirement on configured session views. Selected legacy subscriptions
also enable session-owned URI grants and resource/catalog invalidations over GET. The implemented compatibility surface covers synchronous
`tools/list`/`tools/call`, ordinary `prompts/list`/`prompts/get`, ordinary
`resources/list`, `resources/templates/list`, and `resources/read`, plus
`completion/complete` for declared prompt arguments and resource-template
variables. A valid progress token also enables the existing reporter over the
originating POST response's lazily committed SSE stream. A handler that emits
no progress returns JSON. Framework static catalogs support bounded pages on
both 2025 revisions, as described [below](#static-catalog-pages-on-2025-revisions).
The `2025-03-26` revision has additional wire differences and is
not implemented.
A declared enum constant
does not by itself mean that the runtime supports or has qualified its wire
revision; unsupported endpoint/operation combinations fail construction.

Tasks and `subscriptions/listen` are separate endpoint opt-ins through
`taskProtocolVersions` and `subscriptionProtocolVersions`. Empty sets mean
disabled. Tasks select only `V2026_07_28`. Subscription selection may also name session-enabled
2025 revisions to offer the HTTP GET families described below. URI subscriptions
use real legacy POST operations and independent authorization grants. Installing
a server-wide task manager or event publisher alone does not advertise a facility
on an endpoint. Skills and Apps metadata also select exact revisions. The 2025
adapter does not include Skills, Apps UI, Tasks, `subscriptions/listen`,
multi-round input, or server-initiated requests. In a stateless 2025 call after `initialize`, the request context
cannot attribute the earlier client's capabilities or information to that
call; application policy must treat those values as unknown.

An ordinary prompt selects its revisions through `@McpPrompt(protocolVersions = ...)`
or `McpPromptRegistration.withName(name, protocolVersions)`, using a subset of
the endpoint's exact revisions. String arguments, user/assistant messages and
application metadata use the same public handler on each selected revision.
Modern prompt catalogs remain one page and reject cursors; the two 2025 views
use framework pagination. Caller catalog policy is checked again before
`prompts/get`, including when the caller already
listed the prompt. Icons are advertised for `2025-11-25` and omitted for
`2025-06-18`; a returned resource link with icons cannot be represented on
`2025-06-18` and fails safely. A prompt that declares input requests or
request state cannot select a 2025 revision. Completion selects its own
explicit subset of the prompt's revisions, using the existing completer and
handler signature.

Ordinary resources select revisions through `@McpResource(protocolVersions = ...)`
or the exact-URI and URI-template registration factories. Custom listing uses
`@McpResourceList(protocolVersions = ...)` or `resourceListHandler(handler, protocolVersions)`.
Each selection must be a subset of the endpoint's revisions. Exact and RFC 6570
Level 1 template reads use the same admission, request limiter, interceptor,
and handler pipeline. A custom list receives exact registrations enabled for
the selected revision; its returned URIs must have readable routes in that
revision. Application handlers remain responsible for resource permissions and
for binding opaque cursors to the intended caller, snapshot, and expiry.
Resource catalogs do not use the tools/prompts catalog access policy.

Modern static resource and template catalogs are one page and reject cursors;
the two 2025 views use framework pagination. Custom resource lists retain
application pagination, result metadata, and bounded
opaque cursors, including an empty string. Text and base64 blob reads retain
their content metadata. The adapter omits 2026 cache fields and result framing,
omits June catalog icons, and retains November icons. Missing resources use the
legacy `-32002` error. Template completion selects its own explicit subset of
the registered template's revisions. Resource input requests and request
state remain 2026-only. Apps resource declarations and returned Apps content are
rejected on the legacy adapter.

The client is configured with the endpoint URL. Soklet does not discover or
choose a sibling URL on the client's behalf. Client support depends on the
selected revision and features; the dated
[client compatibility matrix](release/MCP_CLIENT_COMPATIBILITY.md) records
tested host versions and the scope of each check.

`McpLocalizationContext` is a Soklet-owned final request value built through
`withLocale(locale, localizationLookup)`, with an optional revision and a
thread-safe `McpLocalizationLookup` over the captured translation snapshot;
applications do not implement or subtype the context.

On the modern view, framework-generated `tools/list`, `prompts/list`, static
`resources/list`, and `resources/templates/list` localize the included
`_meta["io.modelcontextprotocol/serverInfo"]` title and description. This also
applies to empty or caller-filtered catalogs and catalogs without translatable
descriptor text. The server fields reuse canonical translation coordinates,
count toward the same response-wide lookup and byte limits, and follow the
whole-response failure policy. Server name/version remain canonical. The 2025
catalog projections omit this metadata and do not perform its lookups.

Custom `resources/list` results and other application results remain
application-owned and are not post-processed. Their appended server-information
metadata retains canonical text; handlers use the selected localization context
to translate their own result text.

MCP language preferences ignore empty comma-separated `Accept-Language`
elements and normalize SP/HTAB at element edges and around the quality-value
semicolon before JDK parsing. Empty elements still count toward the existing
4,096-code-unit raw-input limit. The 32-range limit after JDK alias expansion,
physical header order, first-occurrence duplicate handling, and zero-weight
exclusions are unchanged. Malformed tokens, remaining controls, or over-limit
input produce the existing empty preference view for application fallback.

On explicitly enabled 2025 views, `initialize` localizes `serverInfo.title`
and endpoint `instructions`; the `2025-11-25` view also localizes
`serverInfo.description`. The `2025-06-18` projection omits description and
never looks it up. These fields reuse the same canonical text coordinates as
modern discovery, with the existing per-response lookup and output limits.
The required initialization `serverInfo` is independent of the optional
server-information result-metadata setting. A successful render emits
`Content-Language`; whole-response `USE_DEFAULT_TEXT` fallback emits the
configured fallback locale. Under `FAIL_REQUEST`, initialization returns the
fixed private internal error and releases any pending session without offering
its ID, so a later initialization may retry.

Modern subscription terminal metadata uses the same localizer when the endpoint
explicitly enables `2026-07-28` subscriptions, including framework localization
or task notifications without an application `McpSubscriptionConfig` publisher.
Its audience is selected before the SSE response head is committed.

Localization is node-local by design. Modern and session-disabled 2025 requests
may be routed round-robin because each node creates a fresh context from the
request's portable inputs and one response retains one immutable catalog snapshot. A live SSE
subscription remains on the listener that accepted it; after node loss, the
client reconnects to a survivor and that node creates a fresh context rather
than recovering a distributed localization session. `invalidateCatalogs()` is
also server-local: after an atomic catalog swap, the application calls it on
every applicable instance. A rolling activation may temporarily serve
different revisions on different nodes, while a fleet-atomic cutover requires
an application-owned coordinator or traffic switch. A failed candidate is not
installed and produces no invalidation.

Public MCP value carriers follow the same Soklet construction style: they are
final immutable classes with named factories or builders, private
constructors, and conventional `get...` accessors. In particular,
`McpJsonString`, `McpJsonBoolean`, and `McpJsonNumber` use
`fromValue(...)`/`getValue()`; `McpInputRequest` uses
`fromDeclaration(...)`; and prompt messages use
`fromUserContent(...)` or `fromAssistantContent(...)` with `getRole()` and
`getContent()`. Admission, rate-limit, localization, subscription, and metric
event values are created through factories on their sealed root interfaces.
Their nested final variants remain public for typed pattern matching and expose
only conventional getters; applications do not invoke variant constructors.
Metric aggregate keys use `fromDimensions(...)` and dimension getters. The
trace-correlation fingerprint is returned by
`McpTraceCorrelationKeyManager.getFingerprint()` and
`McpServerDiagnostics.getTraceCorrelationFingerprint()`, and exposes
`getValue()`; applications do not construct it.

The downstream OpenTelemetry metric migration, modern admitted-request span
policy, naming, parenting, and terminal behavior, and bounded off-network MCP
simulation are implemented. `MCP_TRACE_CORRELATION` is also implemented: one
bounded record is attempted at an admitted request's exactly-once finish
authority when the pseudonymous token or separately opted-in raw validated MCP
trace ID is available. It carries neither the full trace context nor request,
throwable, method, or response objects. The simulator is a local test facility
and does not cause Soklet to advertise a protocol capability.

## Skills

Soklet implements the `io.modelcontextprotocol/skills`
extension: discover manifests through `skills/list` and `skills/get`, then read
their exact files through `resources/read`. Configure immutable bundles and
locale groups programmatically; annotation-based Skills authoring is not provided.
A Skills-only endpoint advertises the base Resources surface, but its files are
not automatically added to `resources/list` or `resources/templates/list`.
Empty groups alone do not enable Skills or Resources capabilities.
Skills registrations and custom list handlers must declare exactly the modern
revision. A dual-era endpoint can also serve ordinary legacy tools, prompts,
and resources, but its legacy view omits generated Skills manifests and files.
Knowing a file URI does not make it available through that legacy view.

The [runnable Skills example](examples/skills/README.md) publishes authored
Markdown, a UTF-8 CSV reference and a binary asset using only the public API.
Its separate Inspector CLI check exercises real-client retrieval and digest/
frontmatter verification; it does not activate or execute a Skill. The scoped
authorization, localization, pagination, parser, and client checks are recorded
in [Skills verification](verification/skills/README.md). Agent activation and
general YAML compatibility are separate from Soklet's server-side Skills claim.

Supply complete file bytes under logical bundle-relative paths, including
`SKILL.md`. The application loads or generates those bytes; Soklet does not open
files, fetch URLs, watch directories, or execute skill instructions.

Each file must have a distinct NFC logical path, and a file cannot also be a
directory prefix of another file: `ref` and `ref/notes.md`, or `SKILL.md` and
`SKILL.md/notes.md`, fail bundle construction before root parsing or byte copying.
Logical paths are case-sensitive and retain their exact spelling. Applications
loading or materializing a bundle on a case-insensitive filesystem must account
for names such as `README.md` and `readme.md` that collide there.

Metadata validation retains the Agent Skills field rules and limits. An invalid
field produces an `IllegalArgumentException` identifying the fixed field and
rule, such as `Skills field 'description' exceeds 1024 code points.` Authored
values and custom metadata keys are omitted, and the document is not repaired
or rewritten. These messages are diagnostics rather than a structured validation
API; a construction failure inside a request callback remains a fixed private
internal error on the wire.

```java
McpSkillBundle bundle = McpSkillBundle.fromFiles(files);
McpSkillRegistration registration = McpSkillRegistration
    .withUriAndSkillBundle(URI.create("skill://example/my-skill/SKILL.md"), bundle,
        Set.of(McpProtocolVersion.V2026_07_28))
    .build();
McpEndpoint endpoint = McpEndpoint.withPath("/mcp",
        McpImplementation.withNameAndVersion("example", "1.0").build(),
        Set.of(McpProtocolVersion.V2026_07_28))
    .skillRegistrations(List.of(registration))
    .build();
```

Here `files` is a `Map<String, byte[]>`, and its root document must declare
`name: my-skill` and a valid description. Registration validates URI/name
agreement and generates an immutable URI/digest/raw-byte-size manifest.
Omitted locale stays absent; cache policy defaults to private with zero TTL.
Neither locale nor cache policy grants access. Bundle, registration, and
generated resource entries have structural equality and redacted `toString()`.

To attach Skills to an annotation-generated endpoint, keep the generated registry:

```java
McpEndpointRegistry registry = McpEndpointRegistry.fromClasses(MyEndpoint.class)
    .withSkillRegistrations(MyEndpoint.class, List.of(registration));
```

This returns a new registry, replacing the selected endpoint's entire standalone
Skills list while preserving generated handlers, endpoint settings, groups, and
subscription configuration. An empty list clears standalone Skills; null is
rejected. Selection uses the exact annotated class retained during discovery,
not a path match or a reconstructed programmatic endpoint. Invalid replacements
fail before publication and leave the original registry unchanged.

For locale alternatives, attach a group instead:

```java
McpSkillGroup variants = McpSkillGroup.fromKeyAndSkillRegistrations(
    "my-skill", List.of(englishRegistration, germanRegistration));
McpEndpointRegistry registry = McpEndpointRegistry.fromClasses(MyEndpoint.class)
    .withSkillGroups(MyEndpoint.class, List.of(variants));
```

The registrations above contain explicitly authored bundles with the same Skill
name, distinct locale-specific URIs, and declared locales. Configure a server
`skillVariantSelector(...)` for this multivariant group. `withSkillGroups(...)`
replaces the complete group list while preserving standalone registrations and
the same generated endpoint state; an empty list clears groups, and null is
rejected. The two registry methods preserve one another's property and perform
the same endpoint-wide collision and representation checks. One Skill name
cannot occupy both a standalone registration and a group.

Metadata getters return the complete immutable parsed header, including unknown
fields. Paths iterate root-first, then unsigned UTF-8 order. `findFileBytes(path)`
copies only the requested file. Original bytes, UTF-8 BOMs, and line endings are
preserved, and hashes are computed once from owned snapshots. Do not mutate
inputs during construction. There is no public memory owner or lease.

The construction profile permits at most 512 files and 16 MiB raw content, with
8,192-byte NFC logical paths and a 4-MiB complete root document ceiling. The
private YAML profile uses depth 128, 1,000,000 combined syntax/resolution nodes,
1,048,576 UTF-16 units per scalar, 4,194,304 combined scalar units, and 50,000,000
work units. Existing production JSON limits remain independent: 4 MiB output
and 1,048,576 units per string/token. Individual representations may therefore
fail before the raw bundle ceiling, including binary files above 786,432 bytes.

File representation depends only on the final filename and bytes, consistently
for a file shared by parent and nested skills. A fixed, case-insensitive suffix
table recognizes Markdown, JSON, HTML, CSS, XML, and common text/script formats
(`txt`, `yaml`, `yml`, `toml`, `csv`, `tsv`, `py`, `js`, `ts`, `jsx`, `tsx`, `sh`,
`bash`, `zsh`, `sql`). These use strict UTF-8 text when valid. Malformed UTF-8
falls back to canonical base64; a valid text value exceeding output limits is
rejected, not reclassified. PNG/JPEG/GIF/WebP/PDF/ZIP use their fixed MIME types
and base64; unrecognized suffixes use `application/octet-stream` and base64.
No platform-dependent MIME lookup is used. Nested `SKILL.md` remains supporting
content unless separately registered.

Registration preflights canonical file reads, skill gets, and one-entry list
responses, including JSON-RPC wrappers and conservative cache-field overhead.
Endpoint construction also checks the configured server metadata and worst-case
automatic page. Like existing startup checks, this uses request ID `0`; actual
request IDs, caller-dependent projections, and complete final responses are
validated again before publication.

Configure standalone registrations with `McpEndpoint.Builder.skillRegistrations`
and locale alternatives with `skillGroups`. Both replace their entire list;
null or empty clears only that property. Endpoint getters retain the two sources
separately. Internal owner order is standalone registrations first, then groups
and their members in supplied order, regardless of setter order.
`McpSkillGroup.fromKeyAndSkillRegistrations(key, registrations)` accepts an
application-local nonblank key and ordered alternatives with one name and
distinct URIs/locales (including at most one undeclared locale). Empty groups
are permitted. A name occupies exactly one standalone/group listing slot;
group keys and registration URIs must be unique across the endpoint.

Each registered descendant's complete snapshot must already be present in every
enclosing bundle; Soklet neither merges nor repairs incomplete snapshots.
Shared files use `URI.equals` identity and require identical actual bytes and
representation, with at most 16 owners. Their cache policy is private if any
owner is private and uses the shortest owner TTL. Ordinary exact resources and
matching URI templates cannot shadow these files. Template collision checks
reuse the existing router's URI/count/work limits and reject uncertainty.
These endpoint checks do not select a locale or authorize a read.

Server configuration now accepts `skillAccessPolicy(...)` and
`skillVariantSelector(...)`, with matching getters. A null access policy resets
to `McpSkillAccessPolicy.allowAllInstance()`; a null selector clears it. Both
survive simulator derivation without replacing application callback identities.
Any declared multivariant group requires a selector at server construction,
regardless of how many variants a caller might be allowed to see.

Each list request checks access before discoverability. Hidden but accessible
registrations remain eligible for exact lookup. Initial-page selectors receive
only accessible, discoverable group members, the group key, and the existing
bounded language ranges with weights/exclusions preserved. They return an exact
supplied instance or empty, not an equal copy. Every nonempty filtered group in
initial selection uses a configured selector, including singletons; empty groups
do not invoke it.
Custom selectors own language matching and fallback, including handling
zero-weight exclusions; Soklet passes those preferences intact and does not
apply the default singleton guard to a custom selection.

Without a selector, standalone locales are descriptive, not negotiated. A
declared-locale singleton is omitted only if its most-specific matching basic
language range has weight zero (earliest range wins ties). An undeclared-locale
singleton is omitted for any nonempty bounded preference list. Missing,
malformed, or over-limit headers that collapse to the existing empty list allow
that singleton; applications wanting stricter behavior should configure a
selector. No implicit regional fallback is chosen.

`skills/get` freshly checks only the exact registration's access, independently
of discovery and variant selection. File reads freshly check all owners in
canonical order under one admitted request deadline/cancelation boundary. One
grant can allow a shared file, but any callback failure or null result fails the
request closed. Denied and unknown targets return neutral unavailable errors;
an earlier manifest or cached response is never an authorization grant.
Authorized file reads pass through `McpHandlerInterceptor` with canonical
resource output. Interceptors may add result metadata or shorten freshness, but
cannot change the canonical URI, MIME type, bytes, or representation. Automatic
Skills list/get results are framework-owned; custom list handlers are intercepted.
An accessible parent necessarily exposes included child bytes even when the
child's separate skill entry is denied. Soklet does not execute instructions or
scripts, activate skills, or grant tool permissions; host consent remains separate.

### Skills pages and cursors

Every `McpSkillPage` contains at most 32 registrations, with each complete
manifest kept together. Without `skillListHandler(...)`, an endpoint produces
one automatic page. Endpoint construction counts every standalone registration
plus one entry per nonempty group and checks conservative byte/node/depth limits
with server metadata, before caller filtering. Configure a custom handler when
that page cannot fit; pagination cannot repair an oversized individual manifest
or file.

A custom `McpSkillListHandler` returns `McpSkillPage` directly, without a
`McpCompleteResult` wrapper. On the first page,
`McpSkillListContext.getInitialSkillRegistrations()` is present, even for an
empty selection. Return an ordered subsequence of those exact registration
instances. A present cursor, including `""`, is a continuation: the initial-list
optional is absent, and the handler restores its original selection and position.
Soklet rechecks current access/discoverability without rerunning variant selection.
Foreign or reconstructed registrations, duplicate page URIs, and invalid
first-page ordering fail before publication.

The application owns opaque cursor authentication, caller/endpoint binding,
expiry, original selection/content identities, cross-page duplicate prevention,
and retained snapshots across nodes or deployments. Soklet does not store or
sign cursors. `McpLocalizationRequest.getSkillListCursor()` exposes the same
Skills cursor before localization context creation; the resource-list accessor
remains exclusive to `resources/list`.

Skills caching defaults to private with zero TTL. `skillListCachePolicy(...)`
sets the list policy; a page may override freshness but not scope. Caller/access
policies, localization, groups/selectors, or a custom list handler conservatively
clamp Skills responses to private zero-TTL caching. HTTP responses remain
`Cache-Control: no-store`. `Vary: Accept-Language` is added for localization or
group/selector language dependence, not merely for caller-dependent access.
Localization never rewrites the authored bytes or their hashes.

## Server and request model

`McpServer.withPort(port)` creates an independent listener. By default, the
builder discovers every generated endpoint visible from the thread context
class loader at build time and admits requests anonymously. Use
`endpointRegistry(...)` to select an explicit registry and
`admissionController(...)` to install application-owned admission. A Soklet
application may manage HTTP, SSE, and MCP servers together through the
corresponding `SokletConfig` builder setters, but each retains its own bind
address and port.

The MCP builder does not expose the HTTP/SSE builders' `IdGenerator` or
`MultipartParser` settings. Its underlying HTTP `Request` uses the default
request-ID generator. `McpRequestContext.getRequestId()` is the JSON-RPC ID,
whereas `getRequest().getId()` identifies the underlying HTTP request. Optional
trace-correlation keys produce privacy-preserving tokens from validated trace
metadata; those tokens do not replace either request identifier.

MCP `2026-07-28` is stateless. Clients do not initialize a session and do not
need to reuse a connection. A client may call `server/discover` immediately.
Every request restates its protocol version and client capabilities; Soklet
validates those fields before application admission and exposes normalized,
bounded request information through `McpRequestContext` and the operation-
specific context.

Task requests are classified as
[`McpOperationType.TASKS_GET`](https://javadoc.soklet.com/com/soklet/McpOperationType.html#TASKS_GET),
[`TASKS_UPDATE`](https://javadoc.soklet.com/com/soklet/McpOperationType.html#TASKS_UPDATE),
or
[`TASKS_CANCEL`](https://javadoc.soklet.com/com/soklet/McpOperationType.html#TASKS_CANCEL);
the operation name is the validated task ID.

`McpRequestContext`, `McpAdmissionContext`, and `McpRateLimitContext` expose
the semantic `McpOperationType` through `getOperationType()`. Application
branching should normally use that type instead of comparing protocol strings.
The separate `getJsonRpcMethod()` accessor preserves the exact validated wire
value for diagnostics and extension-aware policy; `McpOperationType.OTHER`
classifies an unrecognized, future, or extension method without discarding that
value. Because the recognized operation set may grow with later MCP
profiles, enum switches should retain a forward-compatible default.

One server may host multiple `McpEndpoint` instances. Endpoint selection uses
the exact raw request path. Both builders and annotations require normalized
ASCII raw URI paths and reject trailing slashes, repeated slashes, whitespace
and dot segments rather than rewriting a declared URL. Clients must use the
configured path verbatim, including percent encoding. Tool, prompt, and resource names may repeat
on different endpoint paths without leaking across them, while handler slots,
the admitted queue, and the server lifecycle remain server-wide.

Endpoint paths are fixed. Both annotated and programmatic endpoint
registration reject `{...}` path templates, and `McpServer` exposes only its
built-in HTTP/1.1 listener rather than a public MCP transport or routing SPI.
For a bounded, startup-known tenant set, register one fixed endpoint path per
tenant. For dynamic tenancy, carry the tenant through application-owned
admission identity or register a required `Mcp-Param-*` header and have
application admission authenticate and authorize its value; do not treat a
self-reported header as an authorization decision. Because every endpoint path
is fixed, `McpRequestContext.getEndpointPathParameters()` and
`McpAdmissionContext.getEndpointPathParameters()` are always empty.

Configure the built-in listener's transport bounds through
`McpServer.Builder`. Request-header and request-body read timeouts each default
to 60 seconds; the request-body limit defaults to 10 MiB and may be configured
only from 1 byte through the reviewed 16 MiB production-JSON ceiling. That
aggregate body limit does not widen the independent 1,048,576-character limit
on decoded UTF-16 units and on the escaped token spelling of any single JSON
string or member name. See [JSON and schema limits](#json-and-schema-limits)
for escape accounting; larger logical documents must be divided across fields
or transferred out of band. The defaults are 100 headers, 64 KiB
of aggregate headers, an 8,192-byte request target, a 64 KiB request-read
buffer, and 8,192 concurrent connections. A zero concurrent-connection limit
disables Soklet's cap and therefore requires an effective external bound.
`connectionQueueCapacity(...)` is an alias for
`streamQueueCapacity(...)`; both configure the same per-stream outbound queue,
whose default is 128, and the most recent call wins.

On the modern stateless path, JSON-RPC requires a sender not to reuse an ID
while an earlier request from that sender is still in flight. That is a sender obligation, not a receiver-
side global namespace. Because this protocol is stateless, Soklet cannot
reliably infer whether two HTTP requests came from one sender; a connection,
endpoint, admitted identity, or authorization partition is not a protocol
sender identity. Soklet therefore correlates each response within its own
request/stream and permits independent concurrent requests to carry the same
string or integer ID. It does not reserve IDs across the listener or reject a
request merely because another live request has an equal ID. Enabled 2025
sessions have a separate active-ID registry and the protocol's whole-session
no-reuse obligation, described [below](#explicitly-enabled-2025-sessions).

Every server must resolve:

- a nonempty `McpEndpointRegistry`, using classpath introspection by default;
- one `McpAdmissionController`, using the accept-all controller by default; and
- a server-level fallback `McpRateLimiter` if any endpoint has a tool.

The fallback tool limiter remains required even if every tool has an endpoint
or tool override. Each tool call is charged by exactly one limiter, resolved in
tool, endpoint, then server-fallback order; requiring the fallback makes that
resolution total without relying on override coverage.

The listener binds to `127.0.0.1` by default. A loopback literal or
`localhost` seeds its effective authority into Host validation. A non-loopback
`host(...)` requires at least one explicit deployment hostname or IP literal
in `allowedHosts(...)`, or server construction fails. Soklet does not
terminate TLS.

Explicitly allowlisted names accept a valid public port or an omitted port;
the public port need not match the private listener's port. Automatically
allowed loopback aliases still require the listener port. Host syntax, one
physical Host occurrence and independent Origin authorization remain required.
Forwarded headers cannot authorize an otherwise unlisted hostname.

## Construction and builder conventions

MCP construction entrypoints include every unconditional required value. In
addition to the server and endpoint factories above, the primary forms are:

- `McpResourceOutput.withContents(resourceContents)` or
  `McpResourceOutput.fromContents(resourceContents)` for a nonempty resource
  list; `withContent(resourceContents)` and `fromContent(resourceContents)`
  remain convenient single-value forms;
- `McpInputRequiredResult.withInputRequest(key, inputRequest)`,
  `withFrameworkRequestState(frameworkRequestState)`, or
  `withApplicationRequestState(applicationRequestState)`, according to which
  required value begins the result;
- `McpLocalizer.withFallbackLocale(fallbackLocale,
  localizationContextProvider)` and `McpLocalizationContext.withLocale(locale,
  localizationLookup)`; and
- `McpSubscriptionConfig.withEventPublisherAndNotificationTypes(subscriptionEventPublisher,
  subscriptionNotificationTypes)`, where the initial notification-type set is nonempty.

Resource-output `contents(...)` and subscription `notificationTypes(...)`
replace the entire nonempty collection with an immutable snapshot. Invalid
replacement attempts leave the builder unchanged. Construct the list or set in
application code; these two builders do not expose additive collection methods.

Collection methods use one grammar throughout MCP: `addX(...)` and
`addXs(...)` append, while a retained plural property setter such as
`allowedHosts(...)`, `audience(...)`, `sizes(...)`, or
`notificationTypes(...)` replaces the previous collection. Additive methods
reject null elements.

For optional or defaulted builder properties, passing null clears the optional
feature or restores the documented built-in default. This includes the server
endpoint registry and admission controller, host and operational budgets,
optional endpoint features, localizer revision, protection limits, simulation
options, token-bucket refill settings, and metrics maps. Required construction
values, mode-defining values, and additive elements remain non-null.
Security-sensitive resets remain secure: for example, a null CORS authorizer
restores reject-all behavior for present origins, and null allowed hosts
restores the empty deployment-specific set. A null admission controller
restores the documented accept-all development default and emits a startup
configuration diagnostic; it must not be used as a production authentication
policy. Supplying `McpAdmissionController.acceptAllInstance()` explicitly
records a deliberate anonymous-access decision and suppresses that diagnostic.

`McpTokenBucketConfig.withCapacity(capacity)` is a complete builder entrypoint.
Without overrides it replenishes 60 tokens every one minute; passing null to
`refillTokens(...)` or `refillInterval(...)` restores those values. Capacity
remains required and has no null reset.

## Endpoint authoring

### Annotations

`@McpServerEndpoint` declares the path and implementation information.
`@McpTool`, `@McpPrompt`, `@McpResource`, and `@McpResourceList` declare its
operations. `SokletProcessor` validates the declarations and writes immutable
descriptors at compile time; Soklet performs no runtime classpath scan of
handler methods. Load selected generated endpoint classes through
`McpEndpointRegistry.fromClasses(...)`.

Compile annotated endpoints with `SokletProcessor`, retain parameter names,
and preserve the generated endpoint provider classes when shading. Combine all
`META-INF/soklet/mcp-endpoint-descriptor-providers` indexes from the input
modules into one newline-delimited index. Overwriting one index with another
silently removes those modules' endpoints from classpath discovery; having one
surviving index does not prove the catalog is complete. A service-file merger
alone does not handle this Soklet-specific resource.

For Maven Shade, add this transformer inside the plugin's
`<configuration><transformers>` element:

```xml
<transformer implementation="org.apache.maven.plugins.shade.resource.AppendingTransformer">
  <resource>META-INF/soklet/mcp-endpoint-descriptor-providers</resource>
</transformer>
```

For other packaging tools, configure concatenation for the same exact path.
Check that the packaged index contains every expected provider and that the
packaged application exposes every configured endpoint. See the
[Maven Shade resource transformer documentation](https://maven.apache.org/plugins/maven-shade-plugin/examples/resource-transformers.html).

For a named Java module, open or export the endpoint package to Soklet. A
package containing a non-public record used for runtime conversion must be
open to Soklet.

`McpEndpointRegistry.fromClasses(...)` selects generated endpoint classes in an
explicit order. `fromClasspathIntrospection(...)` loads every generated
endpoint visible from the context class loader in binary-name order and is the
server builder's default. Discovery is provider-neutral. Each annotated
operation acquires its endpoint instance
from the `InstanceProvider` on the `SokletConfig` that owns the MCP server, at
the point of handler invocation. The provider is not called during discovery or
framework-owned static catalog listing; a custom annotated resource-list handler
is an operation invocation and therefore does acquire its endpoint instance.
Soklet does not retain or close the returned instance. This is the same
application-wide provider used for annotation-created HTTP and SSE resource
instances. If an application directly invokes a generated registration handler
outside a Soklet-managed request, no owning configuration exists and the handler
uses `InstanceProvider.defaultInstance()`.

Java annotation elements are never nullable. Optional annotation text and
name overrides use the non-null empty string to mean absent, inherited, or
source-parameter-named as documented by each element. Annotation spelling
matches the programmatic surface: `rateLimiterName`, `toolRateLimiterName`,
`sizeInBytes`, and the fully spelled-out
`cacheTimeToLiveInMilliseconds` family. The time-to-live elements use whole
milliseconds because Java annotations cannot accept `Duration` values.

For 4.0.0, `@McpTool` declarations cannot publish tool icons or the
`readOnly`, `destructive`, `idempotent`, and `openWorld` behavioral hints.
Those values are intentionally deferred on the annotation surface until 4.1.
When a client needs them for presentation or approval policy, declare that
tool with `McpToolRegistration`, which supports `icons(List)` and
`toolAnnotations(...)`, and add the registration to a programmatic endpoint.
Annotations on inherited methods are not MCP operations: place each MCP
operation annotation directly on a method declared by the endpoint class.

The annotation surface also derives schemas only from its documented Java
shape family: it does not translate validation annotations into numeric,
string, collection, or format constraints, and it does not derive UUID or
`java.time` scalar formats. Invalid annotated shapes fail deterministically,
but richer source-location diagnostics are deferred to 4.1. An annotated
`@McpTool` cannot simultaneously publish a typed output schema and return an
inline structured result with an explicit `isError` value; use the
programmatic typed-output `inlineOperationHandler(...)` path when that
combination is required. An annotation-native equivalent is deferred to 4.1.

### Programmatic registration

Programmatic endpoints use the same immutable runtime model. Start with
`McpEndpoint.withPath(path, implementation, protocolVersions)`, supply complete ordered lists with
`toolRegistrations(...)`, `promptRegistrations(...)`, and `resourceRegistrations(...)`,
and pass the built endpoints to `McpEndpointRegistry.fromEndpoints(...)`.

These setters replace, rather than append to, the previous list. Optional list
properties accept `null` or an empty list to clear; each successful call snapshots
the supplied list, and a null element leaves the previous value unchanged.

Registration order is discovery order. Names and resource addresses must be
unique within one endpoint. The advertised capability set is derived from the
registrations; there is no separate capability switch that can drift from the
handlers.

### JSON and content values

`McpJsonObject` and `McpJsonArray` are immutable structural values. Object
member insertion order does not affect equality or hashing; array element order
does. Use `McpJsonArray.emptyInstance()` for the shared empty value. Its builder
accepts JSON values plus `String`, `BigDecimal`, `Integer`, `Long`, `Double`,
and `Boolean`, and `addNull()` appends JSON `null`. A `Double` must be finite.

`McpJsonNumber` equality and hashing use numeric value, so `1`, `1.0` and
`1E+0` are equal and have the same hash code. Object and array equality follow
that rule for numeric leaves. `getValue()` retains the supplied `BigDecimal`,
including its scale; ordinary request parsing and JSON conversion do not
remove trailing zeros. If scale is meaningful to an application, inspect the
returned decimal explicitly rather than relying on JSON-value equality.

The five `McpContentBlock` variants expose `getAnnotations()` and
`getMetadata()` through the common interface. Their equality and hashing cover
the complete value, including annotations, metadata, ordered icons, embedded
resource contents, and image, audio, or embedded-blob bytes. These types do not
render payload content through `toString()`.

For common construction, `McpPromptMessage.fromUserText(...)` and
`fromAssistantText(...)` create plain-text prompt messages, and
`McpResourceLink.fromResourceDescriptor(...)` performs a lossless immutable
conversion. `McpClientCapabilities.fromJson(...)`,
`McpRequestStateProtectionContext.fromComponents(...)`,
`McpTextCoordinate.fromComponents(...)`, and
`McpLocalizableText.fromCoordinateAndDefaultText(...)` let applications test
their capability, protection, and localization code without starting a server.
Soklet still constructs the authoritative request-scoped values used at
runtime.

`McpToolOutput` structured content remains typed as `McpJsonValue`, not
`McpJsonObject`, because the target protocol schema permits every JSON value.

Structured-content text mirroring is a compatibility aid, not the authoritative
typed result. It is enabled by default and can be disabled with
`structuredContentMirroredAsText(false)`. When enabled, Soklet appends the
canonical JSON text only when adding it fits the production JSON-string and
serialized-response-byte ceilings. If adding the mirror would exceed either
ceiling, Soklet omits it and still returns the valid `structuredContent`;
clients must therefore consume `structuredContent` rather than require the
optional text mirror.

## Tools and typed schemas

The staged `McpToolRegistration` builder makes the argument/result choice
before a handler can be supplied:

| Stage | Use it when |
| --- | --- |
| `argumentAndOutputTypes(argumentType, resultType)` | The tool always completes with a supported structured Java result. Soklet derives and enforces both schemas and converts in both directions. |
| `argumentType(argumentType)` | Input should be converted to Java, but the advanced handler needs to return a recognized `McpOperationResult` directly. |
| `jsonObjectArguments()` | The handler wants the immutable `McpJsonObject` directly. Soklet publishes and enforces the fixed `{"type":"object"}` input schema. |
| `inputSchema(inputSchema)` | The handler wants the validated immutable `McpJsonObject` directly under an authored Profile 1 object-root schema, including constraints or mirrored headers that Java derivation cannot express. |

Class tokens cover ordinary types; `TypeReference<T>` preserves nested generic
types such as `List<Item>`. Advanced handlers may produce supported text,
image, audio, embedded-resource, and structured tool content. Soklet rejects a
null result, an unknown `McpOperationResult` implementation, a result that is
wrong for the selected method, or structured output that does not match the
derived output schema.

Typed derivation accepts this closed Java shape family:

- `boolean`, `byte`, `short`, `int`, `long`, `float`, and `double`, plus their
  wrappers;
- `BigInteger`, `BigDecimal`, and `String`;
- enums, arrays, `List<T>`, and `Map<String, T>`;
- records, including supported generic record instantiations; and
- `Optional<T>` only at a record-property or annotated-argument boundary.

Derived `float` and `double` schemas publish finite minimum and maximum values,
matching the binder's rejection of non-finite results. `BigDecimal` remains an
unbounded JSON number subject to the ordinary JSON number limits.

Typed integer input conversion, including `BigInteger`, accepts at most 1,024
characters in the expanded decimal spelling, counting a minus sign. Each
JSON-to-Java binding also permits at most 4,194,304 expanded integer characters
in total across its records, arrays, lists and maps. Repeated values count each
time they are converted. Soklet checks these bounds and fixed-width Java ranges
before integer allocation; a compact spelling such as `1e9999` cannot bypass
them. These are runtime binding limits in addition to schema validation and
ordinary JSON limits. `BigDecimal` input is retained without integer expansion.

For `tools/call` using `2025-11-25` or `2026-07-28`, input-schema validation
and Java binding failures return HTTP 200 with a completed `isError: true`
tool result. Its fixed text is "Arguments do not match the tool's inputSchema."
Submitted values, property names and exception details are not included. The
handler is not invoked; interception still precedes complete input validation,
and the generated error result passes through `McpToolResultSanitizer` and the
normal output limits. This includes binding failures when an interceptor tries
to create a task. A `2025-06-18` call retains its JSON-RPC `-32602` response.
Malformed request envelopes/params and unknown tools remain protocol errors
for every revision. Application handler exceptions retain their existing
failure behavior; applications can return `McpCompleteResult.fromToolErrorText`
for safe, actionable business-validation feedback.

A typed tool input root must be a record, a `Map<String, T>`, or the synthetic
object formed from annotated tool arguments. A bare typed `String` output is
rejected because it is ambiguous with text content. Arbitrary beans,
`Object`, non-`String` map keys, raw generics, sets, unresolved wildcards/type
variables, unsupported `CharSequence` implementations, and unsafe recursive
record shapes fail at registration or annotation processing.

A tool selecting either 2025 revision must publish an object-root output
schema. Annotated non-object outputs fail at compile time; programmatic typed
registrations fail when the endpoint is built, with the tool name, path and
selected revisions in the error. Modern-only tools may retain array and other
supported output roots. Advanced handlers serving 2025 must also return
object-valued structured content when they include it.

Runtime schema evaluation deliberately exposes only the generic
invalid-arguments result; its internal, bounded instance-free diagnostics are
not projected into the public exception or JSON-RPC error. A reviewed
diagnostic carrier that preserves the privacy and byte-limit contract is
deferred to 4.1.

### Tool Schema Profile 1

Soklet MCP Tool Schema Profile 1 is a closed generation and evaluation profile
based on JSON Schema Draft 2020-12. It is not complete Draft 2020-12 support.
Applications may inspect an `McpToolSchema` and may provide an authored
object-root input document through `inputSchema(...)`; Soklet compiles it
synchronously, publishes the exact immutable document, and evaluates every
invocation before the handler runs. Applications cannot construct or replace
an `McpToolSchema` directly or author an output schema, and Soklet never fetches
a network reference.

Profile 1 recognizes `$schema`, `$defs`, `$anchor`, `$ref`, `$comment`,
`properties`, `additionalProperties`, `items`, `allOf`, `anyOf`, `if`, `then`,
`else`, `type`, `enum`, `const`, `required`, `minimum`, `maximum`, `title`,
`description`, `default`, `examples`, `deprecated`, `readOnly`, `writeOnly`,
`format`, and `x-mcp-header`.

Every other keyword fails closed. In particular, Profile 1 explicitly rejects
`$id`, `$vocabulary`, `$dynamicAnchor`, `$dynamicRef`, `oneOf`, `not`,
dependent schemas, tuple/contains keywords, regex-bearing `pattern` and
`patternProperties`, property-name constraints, length/item/property-count
constraints, `multipleOf`, exclusive numeric bounds, unevaluated keywords, and
content-schema keywords. `$ref` is limited to same-document `#` JSON Pointer
fragments and local plain-name anchors.

Production parsing and evaluation are bounded independently. The HTTP JSON
input-byte limit follows the configured request-body limit (10 MiB by default,
at most 16 MiB); JSON output remains capped at 4 MiB. Independent defaults
include 1,048,576 characters per string or token, JSON depth 128, 100,000 JSON
or typed binding nodes, 4,096 compiled schema nodes, schema depth 64, 32,768
keywords, one million evaluation operations, and 128 active evaluation calls.
These are resource ceilings, not recommended payload sizes. Soklet charges
bounded work before allocation and returns sanitized validation failures.

### JSON and schema limits

The string and token ceilings are independent, and both count **UTF-16 code
units**, not Unicode code points or UTF-8 bytes. Each production ceiling is
1,048,576 units and also applies to object member names. The decoded-string
ceiling counts the resulting Java string. The token ceiling counts the spelling
between quotation marks, including escapes but excluding the surrounding quotes:

| Text unit | Decoded units | Token units when Soklet writes JSON |
| --- | --- | --- |
| Ordinary ASCII character | 1 | 1 |
| Quote, backslash, newline, tab, carriage return, backspace or form feed | 1 | 2 |
| Other C0 control character, such as U+0001 | 1 | 6 (`\u0001`) |
| Supplementary Unicode character, such as 🚀 | 2 | 2 (4 UTF-8 bytes) |

For example, a string containing only newlines can hold 524,288 of them under
the token ceiling. A client can spend more token units by escaping ordinary
characters: `\u0061` uses six incoming token units for one decoded `a`, and
`\uD83D\uDE80` uses twelve for one two-unit supplementary character. Soklet's
writer emits those ordinary characters directly. Aggregate UTF-8 body/response
limits still apply. Constructing `McpJsonString` or `McpTextContent` does not
prevalidate an eventual serialized response.

Public `McpTextContent`, `McpTextResourceContents`, `McpJsonString`, JSON object
member names, prompt-output descriptions, and argument-completion values reject
unpaired UTF-16 surrogates at their factory or setter with
`IllegalArgumentException`. Valid text remains exact, including empty strings,
controls, supplementary characters, and distinct Unicode normalization forms.
Construction validates well-formedness; size ceilings remain serialization
checks. If an application lets a construction exception escape its handler,
the client receives the same fixed internal error described below.

Image and audio MIME types use the existing MCP Apps ASCII media-type parser:
type/subtype and optional token or quoted parameters, with malformed syntax,
controls, non-ASCII characters, and case-insensitive duplicate parameter names
rejected at construction. Supplied spelling and parameter values are retained.
Soklet does not inspect binary bytes, restrict formats to a registry, or promise
that a client supports a declared format; applications must label their bytes
accurately.

Each `McpImageContent`, `McpAudioContent`, and `McpBlobResourceContents` item can
contain at most **786,432 raw bytes** on the built-in server's wire. Its Base64
string must fit the 1,048,576-character JSON scalar ceiling; 786,433 bytes cannot
fit. Complete responses also obey the independent 4 MiB UTF-8 JSON ceiling,
including wrappers and metadata. Binary constructors still defensively copy
data without prevalidating the eventual response size. A larger binary output
fails with the fixed internal error below. Use `McpResourceLink` with an
authorized delivery route for larger files; embedding a blob or returning one
from `resources/read` does not bypass these limits.

An incoming JSON token/string limit failure produces the fixed JSON-RPC parse
error `-32700` before handler execution. An outgoing JSON limit failure produces
the fixed internal error `-32603`; submitted or generated text and exception
details are not reflected in that error. The early JSON parse-limit rejection
uses HTTP 400 for all three supported revisions. An output error uses HTTP 500
for an uncommitted modern response; the 2025 adapters carry that error in HTTP
200.

The schema-operation budget limits work, rather than only the number of JSON
nodes. Repeated `allOf`/`anyOf` branches, references, structural equality and
object-member sorting may exhaust it even for a semantically valid value below
the JSON limits. Valid traversal uses a bounded per-call diagnostic-path stack;
full paths are copied only for diagnostics, and suppressed branch diagnostics
require no path construction. This avoids charging ancestor-copy work at every
child while preserving the one-million-operation ceiling and bounded diagnostics.

Schema-budget exhaustion remains a generic validation rejection. For tool input,
`2025-11-25` and `2026-07-28` return HTTP 200 with the safe completed `isError`
result; `2025-06-18` returns JSON-RPC `-32602` in HTTP 200. The application handler
is not invoked. Output-schema exhaustion produces the fixed internal error
`-32603` (HTTP 500 for an uncommitted modern response, HTTP 200 for 2025).
Detailed internal limit outcomes and diagnostic paths are not a public error API.
Keep schemas and individual results bounded; use multiple calls or pagination
where an application needs larger logical collections.

### Icon declarations

`McpIcon.withSource(URI)` requires an absolute source URI with well-formed
UTF-16 at the factory. It retains the supplied URI without normalization.
The optional `mimeType(String)` setter uses the same ASCII media-type syntax
parser as image/audio content. `sizes(List)` accepts ASCII decimal digits on
each side of a lowercase `x`, or exactly `any`. Size hints retain order,
duplicates and decimal spelling; no integer parsing, dimension ceiling or
image-dimension verification is added. Null or empty clears sizes, and an
invalid replacement leaves the prior value intact. Invalid declarations throw
input-free `IllegalArgumentException` messages before serialization. Eventual
JSON scalar and response-size ceilings still apply.

These are public application-declaration checks. Incoming client metadata
remains untrusted informational data, with its existing wire-shape and absolute
URI checks; it is not an authenticated identity. Soklet does not fetch icons,
enforce a URI scheme/domain allowlist, validate inline image bytes or sanitize
SVG. Prefer trusted HTTPS sources or image data URIs. Applications and consuming
clients own trusted-source and rendering policy, as described by the
[MCP Icon definition](https://modelcontextprotocol.io/specification/2026-07-28/schema#icon).

Icon revision projection is unchanged: November 2025 and modern catalogs and
tool resource links retain declared icons; June 2025 catalogs and tool resource
links omit them without mutating application values. June prompt resource links
with icons remain unrepresentable and fail safely.

## Prompts

A prompt is a named, discoverable template that returns ordered user and
assistant messages. Prompt arguments are strings rather than JSON-Schema
values. `McpPromptRegistration` declares required/optional
`McpPromptArgumentDeclaration` entries; `@McpPromptArgument` is the annotated
equivalent. Missing, duplicate, unknown, or non-string arguments fail before
the application handler runs.

Soklet validates prompt structure, but prompt injection, business allowlists,
authorization, output classification, and the safety of any referenced
application resource remain handler responsibilities. Treat prompt text and
arguments as untrusted input. Authorize the admitted principal before reading
a referenced resource, and collapse unknown, unauthorized, unsafe, and missing
inputs to a neutral failure that reveals no protected value. The
[durable-handle and prompt-security example](src/test/java/examples/mcp/McpDurableHandlePromptApplicationPatternsTests.java)
compile-checks one deployment-specific allowlist and canary policy; it is not a
universal injection detector.

On `2026-07-28`, the prompt catalog follows registration order and is returned
as one page; a present cursor is invalid. The two 2025 revisions use
[framework static catalog pages](#static-catalog-pages-on-2025-revisions).

Tool and prompt catalogs are caller-neutral unless the server configures an
`McpCatalogAccessPolicy`. With a policy, Soklet evaluates canonical,
untranslated registrations after admission and the
request limiter, on bounded application execution. `tools/list` and
`prompts/list` return only the admitted caller's permitted descriptors while
preserving the selected revision's canonical relative order; filtering every registration produces a
successful empty catalog. Policy callbacks receive the admitted
`McpRequestContext`, a cancellation feature, and the same applicable
`McpLocalizationContext` later used for rendering or handler dispatch.

Policy evaluation uses the endpoint's bounded application dispatcher and the
request's original absolute deadline. A deadline that wins while the policy is
queued returns correlated HTTP 503/JSON-RPC `-32603`; one that wins during
active policy work returns correlated HTTP 504/`-32603`. No later evaluator is
entered after deadline or stop wins. Forced stop fixes `SERVER_STOPPING`, wakes
queued policy callers, and cooperatively interrupts active policy work before
application cancelation callbacks can delay dispatcher signaling. The policy
cancellation feature reports the winning reason exactly once and exposes no
framework-internal throwable as its cause.

The same policy guards `tools/call` and `prompts/get`. When authorization-context
creation and policy evaluation complete normally, an unknown name and a
registered but hidden name return the same neutral invalid-parameters response,
consume the same request-limiter state, and stop before a tool-specific limiter,
interceptor, or handler. Contract failures in the context provider or policy use
the fixed redacted internal-error path instead of being treated as a denial. For
an accessible tool, the order is admission,
request limiter, neutral name resolution, authorization-context preparation,
catalog policy, tool limiter, registration-specific validation, interceptor,
and handler; prompts omit the tool limiter. Authorization-context preparation
is a narrow prerequisite for a structurally valid framework-protected retry:
it selects the route's state mode, authenticates and decodes the continuation,
establishes the admitted lifecycle/request context, and creates the one pinned
localization context that policy and handler share. A custom state protector or
localization provider can therefore run before the catalog evaluator. No
schema, argument, input, task, progress, interceptor, handler, sanitizer, or
registration-specific diagnostic work runs in that phase. Protected-state
failures discovered during preparation remain latent until policy permits the
target, preserving the hidden/unknown response and limiter boundary. A
localization-context creation failure instead uses the fixed redacted internal-
error path because no evaluator can receive its required context. A detailed
completed-task read rechecks the current origin-tool registration before
sanitizing its result. A missing or newly hidden origin fails the detailed read
with HTTP 500 and the fixed redacted JSON-RPC internal error (`-32603`). It does
not emit `status: "completed"` without the required result, run the sanitizer,
or change the persisted task. An explicit later poll can recover after access
is restored or on a node with the origin registration; the error does not
promise automatic client retries. Null or throwing policy decisions likewise
fail through the fixed redacted internal-error path.
This reauthorization runs inside the active `tasks/get` application Exchange
with that Exchange's cancellation token and absolute deadline. Soklet checks
cancellation and deadline state again after the evaluator returns, so a callback
that absorbs interruption and returns allow after timeout or stop still cannot
enter the sanitizer.

Caller-specific tool and prompt projections always carry zero TTL and private
scope, and HTTP responses remain `Cache-Control: no-store`. Configuring a policy
bypasses shared pre-rendered catalog objects and encoded-length memoization;
Soklet does not retain a per-caller catalog cache. Omitting the policy, or
passing null to `McpServer.Builder.catalogAccessPolicy`, restores the shared
allow-all policy and the caller-neutral fast path.

For an explicit policy, aggregate tool/prompt startup bounds that depend on the
unfiltered catalog are enforced on each exact caller-visible projection after
filtering. Localization-slot and JSON-node overflow then fail atomically before
a response is exposed. Resource and resource-template catalogs remain subject
to their existing startup-strict bounds.

An accessible operation's descriptor remains discoverable even when the
operation declares a required client capability. For example, a tool that
declares required form elicitation remains in `tools/list`; a `tools/call`
without that capability receives the standard `-32021` error. When a catalog
policy is configured, capability and other registration-specific checks occur
only after that policy permits the target. This list/call distinction must not
be used as an authorization boundary.

## Resources and pagination

A resource-read handler that cannot find the selected URI can report the standard
revision-specific error explicitly:

```java
throw new McpJsonRpcException(
    McpJsonRpcError.fromResourceNotFound(resourceReadContext.getUri()));
```

`fromResourceNotFound(URI resourceUri)` requires an absolute, normalized URI in
ASCII wire form. The immutable error has canonical code `-32602`, message
`"Resource not found"`, and `data.uri`. At the resource-read handler boundary,
Soklet renders `-32002` for `2025-06-18` and `2025-11-25`, and `-32602` for
`2026-07-28`. Accepted 2025 operation errors use HTTP 200; modern invalid-parameter
errors use HTTP 400. A plain `fromInvalidParameters(...)` error retains `-32602`
on every revision, even with the same message and URI data. These two errors
are unequal because their revision intent differs. An interceptor can inspect
and rethrow the exact handler exception to preserve that intent. Interceptor
failures remain private internal errors; constructing a new exception with this
factory does not turn an interceptor failure into a client-visible resource error.


An exact resource registration has one concrete URI and contributes to the
static `resources/list` fallback. A URI-template registration uses bounded RFC
6570 Level 1 variables, is advertised by `resources/templates/list`, and is
selected for reads only after exact-resource matching. Exact URI identity uses
RFC 3986 syntax equivalence; declared descriptor spelling is preserved.

The same Level 1 routing subset applies to all three supported revisions.
Simple `{variable}` expansion encodes a slash inside a variable as `%2F`;
a variable cannot consume a raw `/`. For the template `file:///{path}`:

| Client URI | Routing result / decoded `path` |
| --- | --- |
| `file:///src/main.rs` | No template match |
| `file:///src%2Fmain.rs` | `src/main.rs` |
| `file:///src%252Fmain.rs` | `src%2Fmain.rs` |
| `file:///src%2Fcaf%C3%A9.rs` | `src/café.rs` |

`McpResourceReadContext.getUri()` retains the original client URI spelling.
`getUriTemplateVariables()` and `@McpResourceUriParameter` provide immutable
strings decoded exactly once using UTF-8 percent escapes. Do not decode these
values again. Encoded separators do not authorize filesystem access; the
application still owns path validation, containment and symlink policy.

Reserved expansion (`{+path}`), explode (`{path*}`) and prefix (`{path:5}`)
modifiers are unsupported and fail server construction. For known raw
multi-segment URIs, register an exact resource; for a fixed path structure,
use literal separators such as `file:///{directory}/{name}`. There is no
arbitrary-depth raw-slash wildcard. See [RFC 6570 simple expansion](https://www.rfc-editor.org/rfc/rfc6570.html#section-3.2.2)
and [reserved expansion](https://www.rfc-editor.org/rfc/rfc6570.html#section-3.2.3).

Programmatic exact-resource registrations and exact-routed read URIs have a
1,048,576-byte ASCII wire ceiling, aligned with the production JSON string
ceiling. Java annotation declarations have a separate 65,534-byte exact-URI
ceiling imposed before the JVM class-file string limit. A read routed through a
URI template instead has a 65,535-byte URI-wire ceiling. Each endpoint may
register at most 256 templates; each template has an 8,192-byte UTF-8 source and
normalized-wire limit, at most 32 variables, and at most 128 UTF-8 bytes per
variable name. Routing may evaluate at most 8,388,608 dynamic-programming cells
per request. Overlap validation examines at most 65,536 states per template
pair and 1,048,576 states per endpoint, failing closed when either limit would
be exceeded. These fixed limits bound parsing, matching, and pairwise overlap
validation without introducing a regex engine.

Without a custom list handler, `2026-07-28` returns exact registrations in
registration order as one page, excludes templates, and rejects every present
cursor. The two 2025 views page exact registrations and templates separately.
With `McpResourceListHandler` or `@McpResourceList`, the application
handler is the sole authority for every `McpResourcePage`—Soklet never merges
static registrations into the handler result. The handler reads the optional
cursor from `McpResourceListContext` and places any following cursor on the
returned page.

Every descriptor URI returned by a custom list handler must be readable
through an exact resource or URI-template read route registered on that same
endpoint. This is a reachability invariant: a custom-list-only endpoint may
return an empty page, but it cannot return a nonempty page until a matching
read route is registered. Soklet rejects the complete page if any listed URI
does not match a route; the invariant violation is reported as HTTP 500 with
JSON-RPC `-32603`, not as a partially filtered page. Applications should
validate database-backed or otherwise dynamic catalog entries against their
registered templates before returning them.

`list.getRegisteredResourceDescriptors()` is only an immutable convenience
view of exact registrations. It excludes templates and is not automatically
authorization-filtered.

Custom-list cursors are opaque application strings. Soklet preserves the distinction
between absent and present-empty cursor values and enforces a positive UTF-8
size limit (4,096 bytes by default) on incoming and outgoing cursors. The
largest configurable limit is 174,762 bytes: one sixth of the production JSON
token-character limit, so even a cursor made entirely of control characters
fits after worst-case six-character JSON escaping. Values above that reviewed
wire ceiling fail server construction. The application owns cursor encoding,
validation, expiry, integrity,
authorization binding, backing-snapshot behavior, and cross-instance
portability. Bind each cursor to its page position, retained snapshot, catalog
revision, expiry, and current authorization context as the deployment
requires. Tampered, expired, cross-principal, missing-snapshot, wrong-revision,
and malformed values should produce the same neutral application error;
Soklet has no application cursor store or signing key.

### Static catalog pages on 2025 revisions

On explicitly selected `2025-06-18` and `2025-11-25` views, Soklet pages
`tools/list`, `prompts/list`, static `resources/list`, and
`resources/templates/list` in stable canonical key order. A catalog that fits
the existing response byte, JSON-node, and localization-lookup limits remains
one page. Larger catalogs return `nextCursor`; an individual descriptor that
cannot fit a page fails safely. No new application handler or public API is
required. Custom resource-list handlers keep their application-owned pages
and cursors; modern static catalogs remain unpaged.

Every page passes fresh admission and request limiting. Tools and prompts use
the caller's current catalog policy; a previous page or cursor grants no
permission to a later listing, call, or resource read. Soklet localizes only
the selected page, retaining one context and cached lookup outcomes while
shrinking a page that exceeds its publication budget.

Framework cursors have a fixed 2,048-byte ceiling independent of
`maximumCursorSizeInBytes`, which still controls application cursors. They
identify a resume position in the endpoint/revision/catalog and, when
localized, its negotiated locale. Wrong-kind/path/revision, changed catalog
or locale, corrupt, unknown, or caller-hidden anchors produce the same neutral
JSON-RPC `-32602` error. The cursor is unsigned navigation data, not an
authorization grant: it has no MAC, server-side session, retained translation
snapshot, or promise of consistent permissions and translations across pages.
Restart enumeration when the catalog or negotiated locale changes. Named-host
pagination and exact-candidate release qualification remain pending.

Soklet ships no `file://` mapper. A handler that maps resource URIs to a
filesystem owns root containment, traversal rejection, canonicalization,
symlink policy, authorization, and races between validation and opening.
Canonicalize both the configured root and requested target, contain the target
after symlink resolution, then authorize the canonical target before opening
it. Separately allowlist schemes, authorities, credentials, queries, and
fragments according to delivery intent: a URI intended for direct client
loading has a different trust boundary from a handler-only custom URI. The
[resource and cursor-security example](src/test/java/examples/mcp/McpResourceCursorApplicationPatternsTests.java)
exercises these application-owned policies without claiming Soklet implements
them or that its in-process snapshot test double is fleet-portable.
The separate
[localized cursor fleet example](src/test/java/examples/mcp/McpLocalizedCursorFleetApplicationPatternsTests.java)
uses two independently configured public simulators and separately populated
repositories to demonstrate one portable application pattern: it transfers
only the cursor across nodes, pins snapshot/catalog and locale/localization
revisions plus expiry and offset, keeps authorization in HMAC associated data,
and maps every exercised invalid classification to one neutral error. It is
not a Soklet distributed store, key-management system, replication protocol,
affinity guarantee, or positive cache-TTL claim.

Resources and catalogs carry an `McpCachePolicy` with private/public scope and
a nonnegative time to live. A dynamic resource page may override only its time
to live; its endpoint-level scope remains fixed across pages. Use private scope
for any catalog whose contents vary by caller identity or authorization. Use
public scope only when the same descriptors are safe to share across callers.
Protocol cache hints do not turn the HTTP transport into a shared cache: MCP
transport responses use `Cache-Control: no-store`.

The static exact-resource fallback is caller-neutral: modern lists use
registration order, and the two 2025 views use canonical key order. Tool and
prompt catalogs additionally apply any configured caller catalog policy. A custom
`McpResourceListHandler` is dynamic and application-owned: it may return
identity-specific descriptors, but the application must keep the endpoint
cache scope conservative enough for every page it can return. Framework
localization can also vary catalog text by request locale. Because locale is
not a protocol cache-key dimension, localization-enabled cacheable output is
kept private with a zero TTL; HTTP remains `no-store` in every case.

## Multi-round-trip input and request state

Tools, prompt gets, and resource reads may return `McpInputRequiredResult`
when they need a supported client request, state for a later retry, or both.
Programmatic operations declare every possible `McpInputRequestDeclaration`
with `inputRequestDeclarations(List)`; annotated operations use
`@McpMayRequestInput`. For an annotated operation, `type` derives both the
JSON-RPC method and its base client capability:

| `McpInputRequestType` | JSON-RPC method | Base capability |
| --- | --- | --- |
| `ELICITATION_FORM` | `elicitation/create` | `ELICITATION_FORM` |
| `ELICITATION_URL` | `elicitation/create` | `ELICITATION_URL` |

Generated-registration validation derives the method and capability from the
selected elicitation mode. For example:

```text
@McpTool(name = "catalog.continue",
    protocolVersions = {McpProtocolVersion.V2026_07_28},
    mayRequestInput = @McpMayRequestInput(
        type = McpInputRequestType.ELICITATION_FORM,
        requirement = McpInputRequirement.CONDITIONAL))
public McpOperationResult continueCatalog(...) {
  // ...
}
```

The programmatic factories remain
`McpInputRequestDeclaration.fromElicitationForm(...)`,
and `fromElicitationUrl(...)`. A
declaration exposes the selected type through `getInputRequestType()`, the
derived wire method through `getJsonRpcMethod()`, and the complete derived
capability set through `getCapabilities()`.

This multi-round input path is available on the explicitly selected MCP
`2026-07-28` revision. Soklet 4.0.0 supports MCP
`2026-07-28`. Soklet neither selects an automatic
"latest" profile nor falls back to another revision. Form and URL elicitation
are the supported client input operations on that revision.

Those client operations are embedded values, not standalone JSON-RPC
requests. Soklet writes each `method`/`params` pair only inside the
`inputRequests` member of the correlated `input_required` result. It never
writes a server-originated top-level JSON-RPC request to an HTTP response or
request-scoped SSE stream. At the top level, a method-bearing server message
is a notification without an `id`, while a correlated response has an `id`
and no `method`. After performing an embedded operation, the client sends a
fresh POST to retry the original tool, prompt, or resource request; there is
no bidirectional session carrying an independent server request.

`McpInputRequirement.REQUIRED` makes the declaration's capabilities mandatory
after successful application admission and before execution on every call. `CONDITIONAL` defers that check until the
handler actually emits the request. All missing capabilities from one result
are reported together before result metadata, request parameters, request
state, or a custom protector is evaluated. A retry's exact responses are
available through `McpRequestContext.getInputResponses()` as raw
`McpJsonValue` values or through its intrinsic typed lookup methods. The same
admitted `McpRequestContext` instance, including verified responses and state,
is supplied to lifecycle callbacks, the handler interceptor, and the handler
for that request. These request-data accessors are a required part of the
framework-owned context contract; they do not have empty compatibility
defaults that can silently discard retry data.

Wire validation does not establish application semantics. The handler must
correlate every response key to the request it emitted, handle missing,
`accept`, `decline`, and `cancel` outcomes explicitly, and validate accepted
form content against the exact requested policy before a side effect. The
application likewise owns secret-field classification, verified-user binding
for URL flows, and side-effect authorization. The public-API-only
[input-security examples](src/test/java/examples/mcp/McpInputSecurityApplicationPatternsTests.java)
compile-check each of those patterns; [SECURITY.md](SECURITY.md#mcp-deployment-security)
defines the deployment boundary.

Elicitation `content` values may be strings, numbers (including decimals),
booleans, or arrays of strings. A decimal such as `3.50` reaches the application
as an exact `BigDecimal` through `McpJsonNumber.getValue()`; Soklet does not
coerce it to an integer or floating-point value. Numbers must fit the production
JSON limits: at most 1,024 characters per number token and an absolute exponent
magnitude of at most 10,000, including the serialization preflight. Nulls,
nested objects, and arrays containing non-string elements do not match this
content union. `McpInputRequest.matchesInputResponse(...)` checks that union,
not the requested schema: enforcing an `integer` field, range, required field,
or other application constraint remains the application's responsibility.

For example, this raw-JSON tool requests active form Elicitation and lets Soklet
carry JSON state between calls:

```java
McpInputRequestDeclaration form = McpInputRequestDeclaration.fromElicitationForm(McpInputRequirement.CONDITIONAL);

McpToolRegistration<McpJsonObject> tool = McpToolRegistration
  .withName("catalog.continue", Set.of(McpProtocolVersion.V2026_07_28))
  .jsonObjectArguments()
  .handler((request, arguments, features) -> {
    if (request.getFrameworkRequestState().isEmpty()) {
      return McpInputRequiredResult.withInputRequest(
          "approval", McpInputRequest.fromDeclaration(
          form, McpJsonObject.builder()
            .put("message", "Approve catalog access?")
			.put("mode", "form")
			.put("requestedSchema", McpJsonObject.builder()
			  .put("type", "object").put("properties", McpJsonObject.emptyInstance()).build())
            .build()))
        .frameworkRequestState(McpJsonObject.builder()
          .put("phase", "waiting-for-approval")
          .build())
        .build();
    }

    McpJsonObject state = (McpJsonObject) request.getFrameworkRequestState().orElseThrow();
    request.getInputResponses().find("approval").orElseThrow();
    return McpCompleteResult.fromToolText(((McpJsonString)
      state.find("phase").orElseThrow()).getValue());
  })
  .inputRequestDeclarations(List.of(form))
  .requestStateMode(McpRequestStateMode.FRAMEWORK_PROTECTED)
  .build();
```

`McpRequestStateMode.NONE` is the default. The other modes have deliberately
different ownership:

- `APPLICATION_PROTECTED` sends the exact nonempty string supplied through
  `applicationRequestState(...)` and returns the exact echoed value through
  `McpRequestContext.getApplicationRequestState()`. Soklet applies a fixed
  65,536-byte UTF-8 bound
  but does not parse, protect, expire, authorize, round-limit, or otherwise
  interpret it. For durable continuation, persist application state in a
  durable repository, return only an opaque unguessable handle, bind the
  record to the admitted principal and authorization context, and consume or
  rotate it atomically according to the replay policy. The client must carry
  the current handle on every retry or new connection. No
  `McpProtectionConfig` or Soklet repository is involved. The
  [durable-handle example](src/test/java/examples/mcp/McpDurableHandlePromptApplicationPatternsTests.java)
  compile-checks this application boundary.
- `FRAMEWORK_PROTECTED` accepts application JSON through
  `frameworkRequestState(...)`, emits an opaque protected string, and returns
  verified JSON through `McpRequestContext.getFrameworkRequestState()`. Any
  operation using this mode makes a server-wide `McpProtectionConfig`
  mandatory.

On an initial request both typed state accessors are empty. On a retry, only
the accessor corresponding to the operation's declared mode can be present;
applications read their value directly without constructing a carrier or
performing a type cast. `McpInputRequiredResult` exposes the same two typed
accessors for application tests and result inspection.

Framework-protected state uses canonical numbers on a verified retry:
`100` returns as `1E+2`, `1.50` as `1.5`, and `0.0` as `0`. Soklet removes
trailing decimal zeros and gives zero scale zero. Numeric value, JSON-tree
equality and hash lookup are preserved; the original `BigDecimal` scale and
number spelling are not. Use exact numeric conversion or `compareTo` for
numeric comparisons. `toPlainString()` avoids exponent notation but does not
recover the original scale. Store identifiers or text requiring an exact
decimal spelling as JSON strings, such as `"5000"` or `"1.50"`.

Choose framework protection explicitly:

- `McpProtectionConfig.withKeyring(...)` is the production built-in. Supply
  operator-generated `McpProtectionKey` material through an initial
  `McpProtectionKeyring`; each server copies the ring and exposes live rotation
  through `McpServer.getProtectionKeyringManager()`.
- `withDevelopmentEphemeralProtection()` creates process-local keys and emits
  a startup diagnostic. State cannot survive a restart or move between server
  instances, so this mode is for development only.
- `withRequestStateProtector(...)` delegates sealing and opening to one
  thread-safe application provider, suitable for a fleet-owned key service or
  envelope. The provider must authenticate the exact associated data in
  `McpRequestStateProtectionContext`; Soklet still owns canonical JSON,
  binding, size, lifetime, round, and prior-request-ID checks.

An initial `McpProtectionKeyring` exposes only the non-secret
`getActiveKeyId()` and `getVerificationKeyIds()` views. Live server-owned state
is inspected through `McpProtectionKeyringManager.getKeyringSnapshot()` and changed
through stage, activate, `rotateActiveKey(...)`, and remove operations; no
public keyring view exposes key material.

The [built-in request-state security profile](release/MCP_REQUEST_STATE_SECURITY_PROFILE.md)
is authoritative for the sole `soklet-mcp-protection-v1` spelling, frozen
HKDF-SHA-256 labels, AES-256-GCM envelope and associated data, canonical
plaintext, and rejection rules. Production operators should use the
[key-rotation runbook](release/MCP_REQUEST_STATE_KEY_ROTATION_RUNBOOK.md) for
the exact stage/compare/activate/drain/remove and rollback sequence.

The defaults are 65,536 encoded bytes, 49,152 decoded bytes, a 15-minute
lifetime, and 10 rounds. `McpProtectionConfig.Builder` exposes
`maximumEncodedRequestStateSizeInBytes(...)` and
`maximumDecodedRequestStateSizeInBytes(...)` alongside the lifetime and round
settings, and validates their combined contract. Framework state is bound to the normalized
endpoint path, protocol version, JSON-RPC method, admitted authorization
partition, and stable request parameters. The parameter digest excludes only
the retry's `inputResponses` and `requestState` plus transient `_meta`
progress/trace/baggage fields, allowing those fields to change without moving
state to a different operation or authorization partition. Every other
`_meta` member, including namespaced vendor extensions and client identity or
capability metadata, is deliberately part of the stable binding and must be
identical on a retry. Applications should use the excluded progress, trace, or
baggage fields for per-attempt correlation rather than varying another
extension member during a multi-round operation.

Stable-parameter binding supports accepted request bodies within the configured
MCP request limit: 10 MiB by default, configurable up to 16 MiB. The binding
uses a separate bounded canonical-parameter profile with 32 MiB of output
headroom because canonical number spelling can be longer than the wire form.
The 4 MiB response-output limit does not cap this digest input. Existing
production depth, node, token and scalar limits still apply. Arguments are
represented by a fixed-size digest in the state binding; they are not copied
into the protected continuation. Encoded and decoded state limits remain
independent of request-body size.

The first emission records round 1, issuance/expiry, and the emitting request
ID. Re-emission preserves the original expiry, increments the round, and
records the current request ID. The next retry must use an ID different from
the request that emitted that particular state. This prior-ID check is not a
server-side single-use store: an application that needs stronger replay or
workflow-consumption semantics must enforce them itself.

The maximum round counts emitted continuations, not handler invocations. A
valid retry carrying the final allowed round can complete normally, but cannot
emit another framework continuation. Likewise, a state valid when opened can
expire while the handler runs. Re-emission checks the original expiry and the
next round after the handler returns. Either failure is a sanitized JSON-RPC
`-32603` internal error (HTTP 500 when the response is still uncommitted), with
no new state emitted and no rollback of application side effects. Incoming
state already expired or beyond the configured round limit remains HTTP 400 /
`-32602` before handler entry.

`McpRequestContext.getFrameworkRequestState()` exposes only application JSON,
not the framework's hidden round, issuance or expiry metadata. Put application
step counters or deadlines in that JSON when handlers need to decide whether
to complete, ask again or return a business error. Keep application deadlines
within the configured lifetime, and use durable consumption/idempotency for
side effects that must not repeat. An application-owned workflow policy cannot
make sealing atomic with a side effect or prevent an expiry race during the
handler. `APPLICATION_PROTECTED` state delegates these policies to the
application; it does not inherit framework round or lifetime checks.

Built-in framework state can continue on another Soklet instance when both
instances use the same production protection material and admission resolves
the retry to the same authorization partition. A matching key ID with
different bytes is not equivalent. Different material or a different
partition fails as the same sanitized HTTP 400 / JSON-RPC `-32602` invalid-
state response before lifecycle observation, interception, or handler entry.
Development-ephemeral protection is intentionally not portable. A custom
protector may provide fleet portability, but it must preserve the same binding
and associated-data contract.

The lifecycle-order statement above applies to the caller-neutral path. An
explicit `McpCatalogAccessPolicy` requires the same admitted request and pinned
localization context for authorization and handler execution. On that path,
Soklet opens a structurally valid continuation before the catalog evaluator and
starts lifecycle observation to obtain that admitted context. A protected-state
failure remains deferred while policy runs; lifecycle observes no recovered
framework state, and an allowed target then receives the same fixed invalid-
state response before interception or handler entry. A denied hidden target
retains the ordinary unavailable-target response, so protected-state validity
cannot become a caller-visible catalog oracle.

After successful admission, Soklet checks registration-dependent request-state
shape and size before required capability checks. It cryptographically opens
structurally valid state only after authorization-partition resolution. Consequently
a missing required capability or admission rejection wins over a later
tamper/binding failure. Invalid, tampered, expired, wrong-bound, over-round, or
same-prior-ID framework state returns HTTP 400 / JSON-RPC `-32602`; temporary
protector unavailability returns HTTP 503 / `-32603`. Invalid-state reports
and malformed, noncanonical, empty, or oversized plaintext returned while
opening custom-protected state collapse to the same 400 / `-32602` response.
Null or unexpected provider behavior, invalid sealing output, and invalid
application output fail closed as HTTP 500 / `-32603`, without reflecting
provider diagnostics.

`input_required` results intentionally carry no protocol cache hints, and tool
input-required results bypass the complete-output sanitizer. A completed
resource read on any retry carrying `inputResponses` or `requestState` is
forced to private scope with zero TTL, regardless of its registration cache
policy. Every HTTP transport response remains `Cache-Control: no-store`.

## Durable Tasks

Soklet implements the
[MCP Tasks extension (SEP-2663)](https://modelcontextprotocol.io/seps/2663-tasks-extension)
for `tools/call`. A task is a durable handle to application-owned work, not a
request continuation retained by Soklet. A client can lose its connection or
the serving node and later use `tasks/get` against any eligible node that can
reach the same application task backend.

Configure one application-wide
[`McpTaskManager`](https://javadoc.soklet.com/com/soklet/McpTaskManager.html)
through
[`McpServer.Builder::taskManager`](<https://javadoc.soklet.com/com/soklet/McpServer.Builder.html#taskManager(com.soklet.McpTaskManager)>):

```java
McpServer mcpServer = McpServer.withPort(8082)
    .taskManager(taskManager)
    .toolRateLimiter(toolRateLimiter)
    .build();
```

There is no implicit task manager. An endpoint must also include
`McpProtocolVersion.V2026_07_28` in its `taskProtocolVersions` set;
`@McpServerEndpoint` has the equivalent optional annotation member. Together,
that endpoint gate and the server-wide manager enable the Tasks capability,
`tasks/get`, `tasks/update`, and `tasks/cancel` for that endpoint. Other
endpoints on the same server do not inherit Tasks. The client must declare the
extension capability on each applicable request. A statically task-required
tool presented by `tools/list` remains discoverable, but Soklet rejects its
invocation with `-32021` after successful admission and before rate limiting,
interception or handler execution when the client
did not declare Tasks.

An advanced handler or interceptor must complete inline when
`McpInvocationFeatures.getTaskCreationContext()` is empty. Returning a task
handle anyway is an application contract failure, reported as the fixed
JSON-RPC internal error (`-32603`): HTTP 500 for the modern revision and HTTP
200 for the 2025 revisions. The application callback has already run, so this
error does not roll back its effects. It is distinct from the statically
task-required tool's preflight capability rejection.

The client declares that it understands task handles; it does not command the
server to run a tool asynchronously. The selected application handler decides
whether to return a task. Tasks do not augment prompts, resource reads, or
other operations. The obsolete request-level `task` member is ignored by
`tools/call`; it does not opt into task creation. `tasks/list` and `tasks/result`
remain unsupported methods.

### Authoring a task-returning tool

An annotated tool that always creates a task returns
[`McpTaskCreatedResult<R>`](https://javadoc.soklet.com/com/soklet/McpTaskCreatedResult.html),
where `R` is the eventual typed output. It may receive one unannotated
[`McpTaskCreationContext`](https://javadoc.soklet.com/com/soklet/McpTaskCreationContext.html)
parameter:

```java
@McpTool(name = "reports.generate",
    protocolVersions = {McpProtocolVersion.V2026_07_28})
public McpTaskCreatedResult<GeneratedReport> generateReport(
    @McpToolArgument(name = "accountId") String accountId,
    McpTaskCreationContext taskCreationContext) {
  String ownerKey = ownerKey(taskCreationContext.getRequestContext());
  String persistedOrigin = taskCreationContext.getTaskOrigin().toPersistedString();
  String taskId = reportJobs.persistAndPublish(
      accountId, ownerKey, persistedOrigin);
  return McpTaskCreatedResult.fromTaskId(taskId);
}
```

`persistAndPublish(...)` above represents application code. Before returning,
it must atomically persist the work description, a stable authorization
binding, and the complete framework-derived
[`McpTaskOrigin`](https://javadoc.soklet.com/com/soklet/McpTaskOrigin.html),
then make the work recoverably visible to application workers. Soklet asks the
configured manager for the new task before sending its handle and requires the
returned origin to match. Returning a Java callback, retaining the request
context, or publishing work before it can be recovered does not satisfy this
contract.

Store the exact UTF-8 representation returned by `toPersistedString()`
byte-for-byte in a non-normalizing text or binary field. Treat it as opaque,
sensitive data: do not use a database JSON/JSONB column or another
parse-and-render cycle that may rewrite the representation. When any manager
node reads the row, reconstruct the origin before building the authoritative
task snapshot:

```java
McpTaskOrigin taskOrigin =
    McpTaskOrigin.fromPersistedString(row.persistedOrigin());

McpTask.Builder taskBuilder = McpTask.withTaskId(
        row.taskId(), taskOrigin, row.status(),
        row.createdAt(), row.lastUpdatedAt());
restorePersistedTaskFields(taskBuilder, row);
McpTask task = taskBuilder.build();
```

When task creation has a selected locale, Soklet persists that locale
inside this framework-owned origin. The locale remains opaque: no current
application API exposes it. Later `tasks/get`, `tasks/update`, and
`tasks/cancel` requests, and task-subscription projections, do not construct,
expose, or enforce an `McpLocalizationContext` from it. An application that
needs localized task fields must separately persist its locale choice in
application-owned task data and use that choice when rendering those fields
on every node.

The codec accepts semantically equivalent JSON objects, including insignificant
whitespace, member reordering, and decimal-scale normalization, and emits one
canonical form. Do not parse and selectively rebuild the origin, depend on its
members, or use `toString()` for persistence; `toString()` is deliberately
redacted. Protect the stored text with the same confidentiality and integrity
controls as the task row. The internal durable-origin codec has separate
headroom for the accepted request plus framework wrapper and schema state: 32
MiB, 2,000,000 JSON nodes, and depth 256. This is not an expanded transport
acceptance limit.

The type argument on `McpTaskCreatedResult<R>` is operational. Soklet retains
the original tool's output schema and, when a later `tasks/get` observes a
completed task, applies the configured
[`McpToolResultSanitizer`](https://javadoc.soklet.com/com/soklet/McpToolResultSanitizer.html),
typed schema validation, and ordinary node, depth, byte, and response limits.
The opaque origin enables that lookup and may contain sensitive validated
arguments; applications must protect it like the task itself and must never
log it or send it to the client.

Programmatic typed registration uses
[`McpToolRegistration.CompleteHandlerStage::operationHandler`](<https://javadoc.soklet.com/com/soklet/McpToolRegistration.CompleteHandlerStage.html#operationHandler(com.soklet.McpToolHandler)>).
That path is statically task-required and preserves its eventual output type.
Use
[`inlineOperationHandler`](<https://javadoc.soklet.com/com/soklet/McpToolRegistration.CompleteHandlerStage.html#inlineOperationHandler(com.soklet.McpToolHandler)>)
when the same typed-output tool always completes inline but needs to return
explicit content or an `isError` tool result. An advanced handler that may
complete inline or create a task uses the ordinary operation-result handler
and first inspects
[`McpInvocationFeatures::getTaskCreationContext`](<https://javadoc.soklet.com/com/soklet/McpInvocationFeatures.html#getTaskCreationContext()>).

### Manager and state contract

[`McpTaskManager`](https://javadoc.soklet.com/com/soklet/McpTaskManager.html)
is the application-owned, thread-safe authority. A production implementation
normally fronts durable storage and job infrastructure shared by every node
that may serve a task. The manager contract owns:

- high-entropy task-ID generation, persistence, retention, and cleanup;
- atomic authorization using the selected endpoint and independently admitted
  request identity;
- legal task transitions and immutable terminal state;
- idempotent input-response consumption and durable cancelation intent;
- task-event publication after the corresponding state is durable.

The application infrastructure behind and alongside the manager owns work
publication, leases, fencing, retries, and crash recovery. Soklet calls the
manager at protocol boundaries but does not start, schedule, retry, lease,
fence, or otherwise run application workers.

Every manager lookup and mutation must independently authorize the task.
Possession of a task ID is not authorization. Unknown and unauthorized IDs
must be indistinguishable: lookup returns an empty optional and mutation throws
the fixed, non-disclosing
[`McpTaskNotFoundException`](https://javadoc.soklet.com/com/soklet/McpTaskNotFoundException.html).
Task IDs and task contents must not become unbounded metric labels or default
log fields.

[`McpTask`](https://javadoc.soklet.com/com/soklet/McpTask.html) is an immutable
authoritative snapshot. Its
[`McpTaskStatus`](https://javadoc.soklet.com/com/soklet/McpTaskStatus.html) is
`WORKING`, `INPUT_REQUIRED`, `COMPLETED`, `FAILED`, or `CANCELED`; the last is
rendered as the extension-required wire value `"cancelled"`. Status-specific
payloads are exclusive: outstanding input requests belong to
`INPUT_REQUIRED`, a complete tool result to `COMPLETED`, and a JSON-RPC error
to `FAILED`. A complete tool result whose `isError` value is true is still a
completed task. Worker failures should use a client-safe application error,
for example `McpJsonRpcError.fromApplication(-31903, "Operation failed")`,
with no secrets or internal exception messages. Reserved JSON-RPC errors such
as `-32603` are framework-owned and cannot be constructed through that factory.
Keep the full worker exception in application-owned logs.

An `INPUT_REQUIRED` poll is checked against the independently admitted
`tasks/get` request's current client capabilities after the manager's
authorized lookup. If any outstanding form or URL elicitation mode is
unsupported, Soklet returns HTTP 400 / `-32021` with only the deduplicated
missing modes in `data.requiredCapabilities`. It sends no partial task snapshot
or input payload and leaves durable state unchanged. A later capable poll
returns all outstanding requests. Capabilities from task creation or a prior
poll are not remembered, and unused origin declarations do not require a
capability on a poll. Working and terminal snapshots require no elicitation
capability. Unknown and unauthorized tasks retain the same neutral error
before this check.

Task construction validates status-specific payload shape. It does not
validate a stored result against the origin tool's output schema or verify
outstanding input requests against its declarations. The development manager's
`completeTask` and `requestTaskInput` methods also defer those checks to
delivery. Application workers must validate their output and input requests
before storing them, including required typed structured content. Soklet
supplies no worker-side API for running the complete delivery pipeline;
preserve the origin as opaque data instead of interpreting its persisted
members.

Invalid stored payloads cause a fixed internal error (`-32603`, HTTP 500) on
`tasks/get`; invalid notification projections are suppressed. Reads do not
mutate authoritative task state, publish a state transition, or notify the
worker. Soklet does not synthesize `FAILED` from a delivery error: current
access policy, sanitizer behavior, and transport limits can also prevent a
successful task from being delivered. A later read can recover after those
conditions change. Corrections to stored state remain application-owned;
terminal in-memory tasks cannot be overwritten, while invalid outstanding
input on a nonterminal task can be superseded with `markTaskWorking` before
starting a valid input round.

`tasks/update` carries any partial subset of outstanding input responses.
Unknown, already consumed, superseded, and union-mismatched responses are
ignored idempotently. `tasks/cancel` records cooperative application-owned
cancelation intent; it does not interrupt a worker or promise that `CANCELED`
will beat concurrent completion or failure. Both differ from the original
request's
[`CancelationToken`](https://javadoc.soklet.com/com/soklet/CancelationToken.html),
which ends with that HTTP request and must never be retained as durable work.

Each task protocol request uses the normal `Mcp-Method` routing header and
requires `Mcp-Name` to equal its task ID. This can assist load-balancer
affinity, but correctness cannot depend on sticky routing. A production
backend should assume at-least-once execution and use application-specific
idempotency, an outbox or equivalent recovery path, and leases with fencing
when work can run on multiple nodes. Soklet supplies no database, queue,
scheduler, worker pool, lease implementation, or exactly-once guarantee.

Graceful Soklet shutdown stops new MCP admission but does not cancel durable
tasks or mark them failed. Application workers finish, checkpoint, or release
their leases according to application policy. A task remains recoverable from
another eligible node even when the node that created its handle disappears,
provided the application uses shared durable backing.

### Development-only in-memory manager

[`McpTaskManager::fromInMemoryDefaults`](<https://javadoc.soklet.com/com/soklet/McpTaskManager.html#fromInMemoryDefaults()>)
returns a bounded
[`McpInMemoryTaskManager`](https://javadoc.soklet.com/com/soklet/McpInMemoryTaskManager.html)
for tests, simulation, development, and deliberately ephemeral single-process
applications:

```java
McpInMemoryTaskManager taskManager =
    McpTaskManager.fromInMemoryDefaults();

McpTask task = taskManager.createTask(taskCreationContext);
// Application-owned test or development work runs separately.
taskManager.completeTask(
    task.getTaskId(),
    McpCompleteResult.fromToolStructuredContent(resultJson),
    "Report ready");
```

The manager is task-count bounded and expires tasks opportunistically. It owns
no executor or worker and provides no durable storage, outbox, replication,
leases, fencing, failover, or crash recovery. State disappears on JVM restart
and is invisible to another process, so this implementation is not a
production distributed-task backend and is never selected silently.

### Task notifications

Polling through `tasks/get` is authoritative. Notifications are an optional,
best-effort projection of already-durable state. A manager enables them by
returning a stable
[`McpTaskEventPublisher`](https://javadoc.soklet.com/com/soklet/McpTaskEventPublisher.html)
from
[`McpTaskManager::getTaskEventPublisher`](<https://javadoc.soklet.com/com/soklet/McpTaskManager.html#getTaskEventPublisher()>).
The default is empty, which leaves polling fully functional. The in-memory
manager supplies a process-local publisher automatically. This is a stable
manager property: every call must remain empty or return the same publisher
instance for the manager's lifetime, and each built server snapshots it once
during construction.

A capable client includes up to 256 deduplicated task IDs in
`subscriptions/listen.notifications.taskIds`. Soklet independently admits the
listen request, resolves every requested ID through the manager, and includes
only authorized, existing IDs in the first acknowledgment. A later coarse
task-ID event triggers a fresh authorized lookup before Soklet renders the
complete current snapshot as `notifications/tasks`. Event payloads never
override manager state, and delayed or duplicate events cannot regress a task
after a terminal snapshot has been sent.

After reserving listen capacity, Soklet retains task-change hints received during
initial authorization and projects only the acknowledged IDs after the
acknowledgment. This closes the authorization-to-activation gap without adding an
initial snapshot or replaying events from before the listen. Clients should still
subscribe first, buffer new notifications, and poll authoritative task state.

Completed notification results pass through a fresh catalog-access policy using
the subscription's current authorized identity, just as polling results do. If
the originating tool is missing or hidden, Soklet suppresses the completed
notification and does not run the result sanitizer. The stored task remains
unchanged, and other eligible task notifications continue. A later task-change
hint or subscription reconciliation can retry projection after access is
restored. Suppression does not consume terminal delivery or start an automatic
retry loop. Current input-capability checks also apply. Reconciliation and
lease expiry fence queued task frames; an unwritten suppressed terminal can be
projected again under a fresh grant. The [Tasks schema](https://github.com/modelcontextprotocol/ext-tasks/blob/main/schema/2026-07-28/schema.ts)
requires the final result in a detailed completed task for both polling and
notifications.

[`McpTaskEventPublisher`](https://javadoc.soklet.com/com/soklet/McpTaskEventPublisher.html)
has broadcast, not competing-consumer, semantics. A fleet implementation must
reach every eligible Soklet node; it must not let one node consume an event on
behalf of the others. Delivery may be lost, so clients should poll after a
disconnect or reconnect rather than expect notification replay. Projection
work and stream queues are bounded; an oversized notification or exhausted
queue closes only the affected stream with a backpressure reason. Soklet closes
its listener registration during shutdown and never closes the
application-owned publisher.

Each subscription owns a bounded FIFO over its accepted task IDs and contributes
at most one queued or running task-projection job. Distinct IDs retain first-event
order. Repeated hints for one ID coalesce to its newest generation, including one
that arrives while the manager lookup is in progress. Each continuation returns
behind waiting peers and to the protocol executor's tail.

Subscription admission reserves separate coalesced work slots for task projection,
catalog projection, authorization, and resource invalidations. The aggregate
modern-subscription admission limit follows `concurrentConnectionLimit`, with an
8,192-subscription bound when that transport limit is disabled. Pending and active
subscriptions both count; the per-partition limit still applies. Capacity rejects
new listen requests before a stream opens, rather than retiring admitted peers
when a publisher fans out. The shared scheduler uses up to four workers, with
ordinary request-processing capacity reserved when concurrency exceeds one.

Manager lookups are serialized within one subscription. Task notification lookup,
catalog policy and result sanitization share a bounded application invocation
using `subscriptionAuthorizationTimeout`, also bounded by the remaining lease and
stream lifetime. Failure or timeout suppresses that generation; a later hint or
reauthorization can request a fresh projection. A callback that ignores
cancelation retains the subscription's task-projection slot until it physically
exits, so it cannot accumulate concurrent replacement callbacks. Other
subscriptions retain bounded parallel progress while application capacity is
available. Authorization, task and catalog callback timeouts begin at dispatch;
waiting work cannot extend an existing authorization lease or total stream lifetime.
An expired lease fences delivery even while its renewal is waiting. Per-stream
output exhaustion or an oversized notification still closes the affected stream
with a backpressure reason. Clients must treat `tasks/get` polling as authoritative.

### Public Tasks API map

| Concern | Public API |
| --- | --- |
| Server configuration and production authority | [`McpServer.Builder::taskManager`](<https://javadoc.soklet.com/com/soklet/McpServer.Builder.html#taskManager(com.soklet.McpTaskManager)>), [`McpTaskManager`](https://javadoc.soklet.com/com/soklet/McpTaskManager.html) |
| Handler-side creation | [`McpTaskCreationContext`](https://javadoc.soklet.com/com/soklet/McpTaskCreationContext.html), [`McpTaskOrigin`](https://javadoc.soklet.com/com/soklet/McpTaskOrigin.html), [`McpTaskCreatedResult`](https://javadoc.soklet.com/com/soklet/McpTaskCreatedResult.html) |
| Durable snapshots | [`McpTask`](https://javadoc.soklet.com/com/soklet/McpTask.html), [`McpTask.Builder`](https://javadoc.soklet.com/com/soklet/McpTask.Builder.html), [`McpTaskStatus`](https://javadoc.soklet.com/com/soklet/McpTaskStatus.html) |
| Manager request inputs and neutral absence | [`McpTaskRequestContext`](https://javadoc.soklet.com/com/soklet/McpTaskRequestContext.html), [`McpTaskUpdateContext`](https://javadoc.soklet.com/com/soklet/McpTaskUpdateContext.html), [`McpTaskNotFoundException`](https://javadoc.soklet.com/com/soklet/McpTaskNotFoundException.html) |
| Explicit in-memory development backend | [`McpInMemoryTaskManager`](https://javadoc.soklet.com/com/soklet/McpInMemoryTaskManager.html), [`McpInMemoryTaskManager.Builder`](https://javadoc.soklet.com/com/soklet/McpInMemoryTaskManager.Builder.html) |
| Advisory notification boundary | [`McpTaskEventPublisher`](https://javadoc.soklet.com/com/soklet/McpTaskEventPublisher.html), [`McpTaskEventListener`](https://javadoc.soklet.com/com/soklet/McpTaskEventListener.html), [`McpSubscriptionEventRegistration`](https://javadoc.soklet.com/com/soklet/McpSubscriptionEventRegistration.html) |

## Admission and identity

`McpAdmissionController` is the authentication, authorization, and
admission boundary for every structurally valid MCP request and notification.
It is mandatory and may be invoked concurrently. Failures and null decisions
fail closed.

For `tools/call`, `prompts/get` and `resources/read`, application admission runs
before target membership, descriptor-dependent prompt arguments, custom mirrored
headers, required client capabilities and registration-dependent request-state
checks. This applies to all supported protocol revisions. A rejected caller
receives its admission response for both known and unknown targets, without
invoking an operation handler. `getOperationName()` is the syntactically validated
requested name or URI; its presence does not establish that a target exists.
Malformed wire shapes, URI syntax, endpoint/version selection and required
protocol/name header consistency still fail before admission. Caller-aware
catalog access policy continues to protect direct tool and prompt access after
admission.

`McpAdmissionController.acceptAllInstance()` deliberately accepts the
canonical anonymous identity. It is convenient for a loopback example, not a
production authentication mechanism. An authenticated acceptance supplies an
`McpAdmissionIdentity` with stable, bounded rate-limit and authorization
partition keys and may attach an application principal. Those keys must not be
self-reported client values. Client information, client capabilities, request
`_meta`, and server information are informational rather than authenticated
identity.

Client implementation `name` and `version` must be strings but may be blank.
`McpImplementation.withNameAndVersion(...)` preserves those strings exactly;
legacy sessions remember them without treating them as identity. Missing or
non-string required fields remain protocol errors. Configured endpoint server
information requires nonblank names and versions in both programmatic and
annotation configuration.

An admission rejection may carry an application-authored
`WWW-Authenticate` Bearer challenge, including an absolute
`resource_metadata` URI and operation scopes. `BearerAuthenticationChallenge`
validates and renders a Bearer field value through `getHeaderValue()`;
`McpAdmissionRejection.withBearerAuthenticationChallengeAndError(...)` attaches
it with the recommended status. Manually supplied challenge values receive
response-header safety checks. The application owns token verification,
protected-resource metadata, authorization-server selection, and scope policy. Unsafe
or reserved response headers fail closed, and notifications retain the HTTP
status and safe headers without acquiring a JSON-RPC body.

`McpAdmissionRejection.Builder.headers(...)` and `addHeader(...)` validate eagerly
and leave previous headers intact if validation fails. Names must be ASCII HTTP
tokens, unique ignoring case; lists must be nonempty and values may contain only
visible ASCII or horizontal tabs (an empty string is permitted). The fixed
profile allows at most 100 fields and 65,536 bytes, counting name length plus
value length plus four bytes for each field. Reserved names are `Cache-Control`,
`Connection`, `Content-Encoding`, `Content-Length`, `Content-Type`, `Keep-Alive`,
`Proxy-Authenticate`, `Proxy-Authorization`, `Proxy-Connection`, `TE`, `Trailer`,
`Transfer-Encoding`, `Upgrade`, `Retry-After`, `Mcp-Session-Id`, `Last-Event-ID`
and every `Access-Control-*` name, ignoring case. Safe headers preserve original
spelling and value order in an immutable snapshot; custom names and values do
not appear in validation errors.


Authorization policy can switch on
`McpAdmissionContext.getOperationType()` and then refine a named operation
through `getOperationName()`. This avoids coupling ordinary policy code to wire
spellings such as `"tools/call"`; inspect `getJsonRpcMethod()` only when the
exact method text is itself part of the policy.

HTTP `Forwarded` and `X-Forwarded-For` headers are equally inert at this
boundary: Soklet never turns them into an admission identity or silently
replaces the application's partition key. If an application deliberately uses
a client IP for an anonymous quota, its admission controller must resolve that
IP from `McpAdmissionContext.getRequest()` through
`EffectiveClientIpResolver` with an explicit
`EffectiveOriginResolver.TrustPolicy`. Use `TRUST_NONE` for a directly reached
listener. Behind known proxies, prefer `TRUST_PROXY_ALLOWLIST` with an
allowlist that covers every possible physical socket peer and every trusted
proxy-hop address expected in the forwarded chain, never end-client addresses.
An untrusted physical peer then causes forwarded values to be ignored in favor
of the raw socket peer. The trusted proxy or network edge must strip or
overwrite both header families; usable `Forwarded: for=` values take precedence
over `X-Forwarded-For`. Never use
`McpAdmissionContext.getClientInfo()` as a partition key, and never use
`TRUST_ALL` where an untrusted client can reach Soklet directly.

Each request is independent. Applications must not rely on authentication,
capabilities, or other metadata from an earlier request on the same TCP
connection. Cross-request application state needs its own explicit identifier
on every request. A durable continuation should carry an opaque handle on each
retry and resolve it from an application-owned repository bound to the current
admitted security context; connection or handler-object identity is not a
continuation authority.

## Rate limiting

`McpRateLimiter` is the single thread-safe application SPI. Soklet never closes
an application limiter. An implementation can store state in-process or call a
distributed system such as Redis.

The optional server request limiter runs once after admission for every
request or notification. A tool call then runs exactly one resolved tool
limiter in this order: tool override, endpoint override, server fallback.
Named and direct setters are mutually exclusive and last-call-wins; every name
in `McpRateLimiterRegistry` resolves when the immutable server is built.

Custom limiters have the same distinction:
`McpRateLimitContext.getOperationType()` is the stable application-facing
classification, while `getJsonRpcMethod()` is the exact validated method text.
For example, a policy that distinguishes tool calls should compare the former
to `McpOperationType.TOOLS_CALL`, not compare the latter to a string literal.

The built-in `McpRateLimiter.fromInMemoryDefaults()` is a finite, bounded token
bucket local to one JVM. It is not fleet-wide enforcement. A denial returns
HTTP 429, `Retry-After`, and MCP error `-31999` for a request; a notification
has the HTTP status but no JSON-RPC body. The first denial wins and successful
charges are never refunded after a later denial, failure, timeout,
cancellation, or write failure. Refill accounting uses a private monotonic
clock; there is no public clock or reset/test-mode seam. At the retained
partition cap, each new-partition acquisition examines only a bounded rotating
sample. It may fail closed even when a fully replenished partition exists
outside that sample; a later acquisition continues from the advanced cursor.

The built-in limiter partitions only on the key in the accepted
`McpAdmissionIdentity`; it has no hidden client-IP or forwarded-header mode.
A custom limiter can inspect the raw `Request` through
`McpRateLimitContext.getRequest()` and must likewise treat forwarding headers
and self-reported MCP metadata as untrusted unless the application deliberately
resolves them under this policy. Applications using a proxy-derived IP must
keep direct reachability and proxy header normalization within their deployment
threat model. A trusted-proxy allowlist is not a substitute for preventing an
unintended path around the trusted proxy.

## Handler execution, interception, and output

Handlers are synchronous. Defaults are 32 active application handlers, 128
queued requests, and a 60-second absolute request timeout. Queue capacity and
handler concurrency are independent positive finite bounds. A supplied
executor changes where work runs but cannot bypass them. Capacity rejection is
HTTP 503 with JSON-RPC `-32603`. A queued request keeps its original absolute
deadline. If that deadline owns while the request is still queued and writable,
Soklet removes it without application dispatch and returns the same fixed
503/`-32603` response; a client disconnect owns without writing a response.
Promotion first ends the queued state. If the absolute deadline then owns an
active request, Soklet returns HTTP 504 with a correlated JSON-RPC `-32603`
error; if an SSE response stream is already open, that error is its terminal
frame. Only an unwritable terminal falls back to stream failure. These
transitions produce one queue-depth removal and one observable request outcome
even when a reserved deadline response becomes unwritable before transport
handoff.

A custom executor must execute accepted work or throw on rejection; silent
discard policies are unsupported. Initial executor rejection, including an
attempt to run application code inline on the protocol submitting thread,
returns the fixed capacity response before application entry. This applies to
bounded request-policy callbacks as well as operation handlers. An application
callback that itself throws `RejectedExecutionException` retains ordinary
application-failure handling.

When a slot releases, Soklet first attempts the normal executor handoff for
the next queued ticket. If that handoff rejects, the already-accepted worker
continues with that ticket before returning to the executor. This lets a
direct-handoff pool drain the admitted queue without rejecting every queued
request. It also preserves admitted work during graceful drain if the executor
has stopped accepting new submissions. Forced cancelation and the original
request deadlines still apply. Worker reuse is iterative, retains the handler
and queue bounds, and adds no retry jobs or replacement workers. Soklet clears
interrupt status between tickets and reapplies each ticket's own requested
interruption. Executor task boundaries may cover multiple application
invocations; application-owned thread-local cleanup remains the application's
responsibility.

That application-handler mapping is distinct from a deadline that expires
while Soklet still owns framework protocol work, before an application handoff.
A framework-owned protocol-operation deadline returns a bodyless HTTP 504 with
the normal bounded response headers; there is no JSON-RPC `-32603` body in that
case. Both paths are recorded as deadline-exceeded outcomes.

An absolute request timeout, forced shutdown after the graceful
budget, or response-stream backpressure failure cancels the invocation's
`CancelationToken`. Graceful shutdown itself fences new work but preserves the
response path for already-admitted finite unary and request-scoped progress
requests. Soklet interrupts the dispatch thread where applicable after
cancelation, but Java cannot forcibly stop a non-cooperative handler. Such a
handler retains its execution slot until it actually exits even if the client
request has already completed.

A committed legacy POST SSE disconnect or lost-writer write failure detaches
that writer without canceling work solely because delivery was lost. Blocked
reporters wake, later reports are inert, and the eventual result is discarded. The absolute deadline and
physical execution reservation remain owned until their normal boundaries.
Finite, uncommitted, or queued legacy requests and modern requests retain
disconnect cancellation.

The built-in MCP listener treats input EOF as loss of the response channel.
This includes a normal TCP FIN and a deliberate client `shutdownOutput()`;
clients must keep their sending side open while awaiting the response.
Before response commitment, EOF cancels the request. After commitment,
modern requests cancel and legacy POST SSE writers detach under the existing
rules above. TCP cannot distinguish full close from input half-close, so this
is an MCP transport policy. Ordinary HTTP preserves half-close and buffered
pipelining; an idle HTTP feed still needs a finite timeout or further writes
to bound an otherwise undetectable abandoned lifetime.

The same non-forcible rule applies to application-supplied request-pipeline
callbacks such as admission, rate limiting, and custom request-state
protection. Terminal ownership prevents a protector or handler that returns
late from publishing a result. Framework request/transport state is released
at the terminal boundary, while the finite application execution remains
accounted until it actually exits.

One `McpHandlerInterceptor` wraps every application-owned tool call, prompt
get, resource read, and custom resource list handler. Its continuation is
synchronous, same-thread, call-lifetime-bound, and one-shot. Framework-owned
discovery and static catalogs do not pass through it because no application
handler exists to intercept.

An interceptor can branch on the same semantic operation type without knowing
the JSON-RPC wire spelling:

```java
mcpServerBuilder.handlerInterceptor((context, features, continuation) -> {
  if (context.getOperationType() == McpOperationType.TOOLS_CALL)
    auditToolCall(context);
  return continuation.proceed();
});
```

Use `context.getJsonRpcMethod()` instead only when exact wire-method text is
needed, such as extension-aware diagnostics.

For a tool call, the application pipeline is structural and required-capability
validation, admission, framework-state opening when present, observation,
optional request limiting, resolved tool limiting, bounded dispatch, handler
interception, complete input conversion/validation and handler invocation,
then output sanitization and final result validation. A capacity or deadline
rejection happens before application interception. A successful dispatch slot
remains charged until the handler/interceptor call actually exits.

`McpToolResultSanitizer` receives and returns the entire `McpCompleteResult`,
including application-owned result metadata. Interceptor short-circuits and
sanitizer replacements still undergo method compatibility, recognized-result,
content, structured-output, and metadata-inclusive limit validation. Only the
returned payload and metadata are written. Null returns, non-tool payloads,
exceptions, and unsafe outputs fail closed without reflecting original/partial
results or exception-derived details. An `input_required` result follows its
separate declaration/request-state path and bypasses this hook.

Configure it with `McpServer.Builder.toolResultSanitizer(...)`; omission or null
restores `McpToolResultSanitizer.nonSanitizingInstance()`, which returns the same
result without application-level redaction. Normal framework validation remains
active. To change selected fields while preserving the rest:

```java
mcpServerBuilder.toolResultSanitizer((requestContext, toolName, rawArguments,
    completeResult) -> completeResult.toBuilder()
        .metadata(redactResultMetadata(requestContext, completeResult.getMetadata()))
        .build());
```

Use `McpCompleteResult.withToolOutput(output).metadata(metadata).build()` for
new tool results, or the corresponding `withPromptOutput`/`withResourceOutput`
builders. Existing `from...` factories still return constructed results;
the old instance-returning `withMetadata` method is removed.

Each authorized detailed completed-task read runs this hook again with the
current polling context and durable original tool name/arguments. It does not
replace the persisted result with a caller's view. Implementations must be
thread-safe and deterministic/idempotent for equivalent calls across nodes,
without one-shot side effects. Task-status metadata, progress notifications,
and other JSON-RPC response families are not covered by this tool-result hook.

Application UI data may use an application key in result metadata, but `_meta`
is not a secret channel: hosts may log or forward it. Content and structured
content can be model-visible. Soklet never copies UI-directed result metadata
into text/structured content for non-Apps clients; author a meaningful model
response separately. Authorization, redaction, tenant policy, and translation
of application JSON remain application responsibilities.

### Apps authorization and browser lifecycle

`McpAppToolMetadata` associates a tool with a UI resource and selects its
audiences. `McpAppResourceMetadata` supplies CSP, browser-permission requests,
and presentation hints. These values do not authenticate the caller. A host
may impose stricter browser restrictions than the resource requests, including
blocking declared nested frames or base URLs. Test the host used by the
application before depending on those features.

Every UI resource read must be authorized independently, including a host
prefetch before a tool call. A resource descriptor's visibility is separate
from permission to read its contents. Keep shared HTML free of credentials and
personalized data; return caller-specific data through authorized tool results.

Each App refresh through the host makes a new MCP request and passes admission
again. Application policy changes govern that request. They do not erase
content already delivered to a host, model, or browser, or automatically revoke
an in-flight admission. An application requiring an expiry or idle-view
invalidation must implement that policy in its UI and supported host bridge.

The example shell clears protected content before refresh and on error,
timeout, cancelation, or teardown. When the host delivers a changed locale or
time zone, it invalidates the view, aborts pending work, and requires a new
authorized instance. Host preferences do not override the admitted caller's
tenant or language. Soklet's Java server does not supply a browser lifecycle;
applications own their HTML and bridge handlers.

## Progress and cooperative cancelation

Every application-owned tool, prompt, resource-read, and custom resource-list
handler receives one invocation-scoped `CancelationToken`. Programmatic
handlers use the direct built-in accessors on `McpInvocationFeatures`:

```java
CancelationToken cancelation = features.getCancelationToken();

features.getProgressReporter().ifPresent(reporter ->
    reporter.report(McpProgressUpdate.withProgress(50.0d)
        .total(100.0d)
        .message("Halfway")
        .build()));

cancelation.throwIfCanceled();
```

The generic `find(...)` and `require(...)` methods remain available for
extension features. For the built-in token and reporter, the direct and
generic accessors return the same invocation-scoped instances.

Annotated tool, prompt, resource, and resource-list methods may instead inject
one `CancelationToken` and one `Optional<McpProgressReporter>` directly. They
may also request `McpInvocationFeatures`; direct injection and feature lookup
return the same invocation-scoped instances. A bare `McpProgressReporter`
parameter is rejected because progress is legitimately unavailable for some
requests.

The cancelation token is always present after an application handler is
selected. It is signaled by the absolute request deadline,
forced shutdown after graceful drain expires, or a response-stream
timeout, internal failure, or backpressure failure. Disconnect cancellation
follows the revision and commitment rules below. Beginning graceful drain does
not signal it for an already-admitted finite request. Cancelation is cooperative: handlers should
check between expensive operations, register a short nonblocking callback with
`onCancel(...)`, or call `throwIfCanceled()`. MCP application and bounded-policy
cancelation callbacks run on Soklet's bounded callback executor, after the
handler is signaled and the terminal response is offered or transport ownership
is detached. A callback cannot delay the network event loop or other request
deadlines. The handler's physical capacity remains charged until both handler
work and callback delivery exit, including during shutdown. Registering after
callback delivery has begun may invoke inline on the registering application
thread. Reports made after cancelation or terminal completion have no effect.

For framework-supplied MCP tokens, `getCancelationReason()` exposes one fixed
`StreamTerminationReason`, while `getCancelationCause()` is always empty.
`throwIfCanceled()` preserves that same fixed category in a bounded
`StreamingResponseCanceledException` and does not attach an underlying
throwable. Applications may log the fixed reason under their own retention
policy, but must not replace it with untrusted free-form text or turn a
cancellation detail into a metric dimension. Exact runtime coverage iterates
every non-`COMPLETED` termination category and proves the reason, empty cause,
and bounded exception message in
`McpProgressAndCancelationRuntimeTests#every_cancelation_category_is_bounded_observable_and_carries_no_framework_cause`.

A progress reporter is present only when the initiating request supplied a
valid string or integer at `params._meta.progressToken` and Soklet can safely
commit request-scoped SSE. Soklet preserves that opaque token's string or
integer form exactly. `McpProgressUpdate` accepts finite floating-point
`progress` and optional `total` values plus an optional message. Accepted
progress values must strictly increase: an equal value is coalesced and a
decrease while the invocation is active throws `IllegalArgumentException`.
Delivery is synchronous through the request's bounded SSE queue, so a slow
client applies bounded backpressure to the reporting handler. Progress and
keep-alive writes never extend the absolute request deadline.

The same reporter works on explicitly selected `2025-06-18`, `2025-11-25`, and
`2026-07-28` operations. On either 2025 revision, the first accepted update
commits the originating POST response to SSE; no update leaves the terminal
response as JSON. Each notification preserves the exact token, and one whole,
sanitized, size-checked terminal result or error uses the selected legacy wire
projection. Tool results are complete results, not incremental result chunks.
Stateless progress tokens are POST-local; clients must still choose unique
tokens across active requests. An explicitly session-enabled 2025 view also
checks tokens across its logically active calls: a collision suppresses the
reporter but does not reject either call. Detaching a writer releases that
call's progress-token registration; it does not recover the lost result.

After legacy POST SSE commitment, a client disconnect or lost-writer write
failure detaches delivery rather than canceling the handler. Soklet wakes
blocked reporters, ignores later reports, and discards the undeliverable
terminal result. Deadlines, forced shutdown, stream timeouts, internal failures,
and backpressure still enforce cancellation, and a handler retains its execution
slot until physical exit. Before commitment, including
queued work, legacy disconnects still cancel; modern disconnect behavior is
unchanged. No lost POST result is replayed or moved to another connection.

The [November transport specification](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports)
recommends an initial empty event with an event ID to prime reconnect/polling.
Soklet intentionally omits that SHOULD behavior for its persistent,
nonresumable legacy POST streams: there is no empty priming event, `id:` or
`retry:` field, intentional polling, event history, or `Last-Event-ID` recovery.
This disposition does not establish client interoperability; named-host and
official progress/priming qualification remain pending for the exact candidate.

If an operation has a missing `CONDITIONAL` input-request capability, Soklet
must keep the response uncommitted until the handler chooses a complete or
`input_required` result. The progress reporter is therefore absent for that
invocation even if the request carried a token, and suppressed reports are
never replayed later. A missing `REQUIRED` capability still fails during
preflight before the handler runs.

## Resource subscriptions

Soklet implements modern `subscriptions/listen` as a framework-owned, long-lived
POST SSE stream on the dedicated MCP listener. Applications select its exact
protocol revision per endpoint and attach an `McpSubscriptionConfig` with an
application-owned `McpSubscriptionEventPublisher` and a nonempty subset of four
notification families: `RESOURCES_LIST_CHANGED`, `RESOURCE_UPDATED`,
`TOOLS_LIST_CHANGED`, and `PROMPTS_LIST_CHANGED`. Authorized task IDs use the
separate Tasks publisher described under [Task notifications](#task-notifications).

The following example assumes an application-owned
`List<McpResourceRegistration> resources` and a thread-safe
`subscriptionAuthorizer` that checks current permission for the whole requested
subscription. The server requires an explicitly selected authorizer; an endpoint
configuration alone is insufficient. An explicitly selected
`McpSubscriptionAuthorizer.denyAllInstance()` is valid and rejects every grant.
For these resource notification families, `resources` must include an exact or
templated resource enabled for `V2026_07_28`. A custom resource-list handler can
also provide the resource catalog in a different endpoint configuration.

```java
McpSubscriptionEventPublisher publisher =
    McpSubscriptionEventPublisher.fromInMemoryDefaults();

McpSubscriptionConfig subscriptions =
    McpSubscriptionConfig.withEventPublisherAndNotificationTypes(publisher, Set.of(
            McpSubscriptionNotificationType.RESOURCES_LIST_CHANGED,
            McpSubscriptionNotificationType.RESOURCE_UPDATED))
        .build();

McpImplementation implementation = McpImplementation
    .withNameAndVersion("example", "1.0.0")
    .build();
McpEndpoint endpoint = McpEndpoint.withPath("/mcp", implementation,
        Set.of(McpProtocolVersion.V2026_07_28))
    .resourceRegistrations(resources)
    .subscriptionProtocolVersions(Set.of(McpProtocolVersion.V2026_07_28))
    .subscriptionConfig(subscriptions)
    .build();

McpServer server = McpServer.withPort(8082)
    .endpointRegistry(McpEndpointRegistry.fromEndpoints(Set.of(endpoint)))
    .subscriptionAuthorizer(subscriptionAuthorizer)
    .build();
```

`McpSubscriptionAuthorizer.authorize(subscriptionAuthorizationContext,
invocationFeatures)` runs after initial request admission and before the first
acknowledgment, then again for renewal or reconciliation. The initial context
describes the candidate notification families, resource URIs, and task IDs;
later checks describe the acknowledged subscription and cannot expand it.
`getInitialRequestContext()` retains historical admission evidence. The
authorizer must check current application authority rather than treating old
credentials or an earlier admission decision as a continuing grant.

Return `McpSubscriptionAuthorization.Allowed.fromValidUntil(validUntil)` or an
`Allowed.withValidUntil(validUntil)` builder with an application context when
permission is established, and `deniedInstance()` otherwise. A null result or
exception fails closed. The effective lease is capped by the application's
expiry, `maximumSubscriptionAuthorizationDuration` (one minute by default),
and the subscription's fixed total lifetime. Each callback has the
queue-inclusive `subscriptionAuthorizationTimeout` (five seconds by default)
and cooperative cancelation. Renewal rechecks current authority before the
lease expires; keep-alives and catalog events do not extend a grant.

After policy invalidation or recovery, call
`server.getSubscriptionReconciler().reconcileSubscriptions()` to fence local
delivery and schedule fresh checks. Return from this method confirms fencing
and scheduling, not callback completion or recall of bytes already written.
Distributed applications make the current policy available on each applicable
node and invoke its reconciler there. These callbacks are application-owned and
may run concurrently; the reconciler does not supply a distributed registry or
migrate streams. Established denial, expiry, and failed required checks end the
listen as described below. Exact 2025 URI-grant renewal has the separate session
retirement contract under [Legacy URI grants and catalog invalidations](#legacy-uri-grants-and-catalog-invalidations).

The built-in publisher broadcasts synchronously within one process. A custom
thread-safe implementation may bridge Redis or another distributed system,
but it must retain broadcast semantics: every attached Soklet listener gets
the event. Soklet subscribes when its server starts, closes only its listener
registration when the server stops, and never closes the application-owned
publisher. Shutdown first fences the old generation's callback, then invokes
application registration close outside lifecycle locks on bounded daemon
cleanup workers. A completed close attempt that threw is retried in the force
phase; an attempt still running is retained and interrupted, without starting
a concurrent close on the same registration. Failed or still-running cleanup
at the deadline can leave residual activity or missing termination proof in
the immutable `ShutdownResult`; a later exit does not rewrite that result. The one-shot
Soklet lifecycle uses the shared graceful/forced budgets in `LifecyclePolicy`;
it retains physical cleanup evidence until termination is proven. Application
code publishes coarse change events with `publishResourcesListChanged()`,
`publishResourceUpdated(URI)`, `publishToolsListChanged()`, or
`publishPromptsListChanged()`; Soklet owns
requested-filter matching, per-stream coalescing, bounded queues, backpressure,
and wire serialization. Publisher events contain no endpoint, principal,
authorization partition, or connected-client target. Two subscriptions to the same URI in different admitted partitions therefore receive the same coarse event.
The stored partition scopes registration, quota accounting, and stream isolation; it is not a per-event authorization check and does not establish semantic authority to read a URI.

Tool and prompt projections advance their comparison digest when a list-change
hint is accepted or folded into a wholly unwritten hint for the same family. Once
writing starts, a later observation needs a bounded successor hint because the
client may already have consumed the earlier event. Releasing the earlier frame
preserves the successor's coalescing key. This prevents a catalog that changes
back and forth from silently leaving the client on an earlier view. The baseline
is an invalidation comparison, not a client receipt or a retained catalog snapshot;
clients re-list under current authorization when they receive a hint.

Long-lived catalog and localization checks use a sanitized request context.
They do not retain authentication headers, bodies, trace identifiers or baggage.
Use the current admitted principal, authorization partition and
`applicationContext` for those checks. The subscription authorizer can inspect
its original `initialRequestContext` when needed.

Modern subscription maintenance concurrency is derived from the existing
protocol and application handler concurrency settings, reserving one worker
from each budget when possible and bounded by the retained owner count. With
the default concurrency settings this allows 31 maintenance jobs. Each owner
has one coalesced pending slot. This bound does not guarantee that arbitrary
callback durations or short leases can be sustained; measure authorization,
task lookup and catalog refresh work together when sizing a deployment.

Resource invalidations received during authorization reconciliation retain bounded,
coalesced dirty markers: at most one resource-list marker and one per accepted URI
(up to 256). Successful reauthorization sends catch-up hints under the current
grant; denial, expiry, or termination discards them. A renewed registration cannot
lose a resource hint merely because it replaces an earlier registration object.
Queued frames check the current authorization fence and lease before socket writes.
Revoked unwritten frames are removed; a partially written revoked frame closes
before further bytes. This is invalidation catch-up, not an event history, durable
replay, or a client receipt guarantee.

When Soklet ends an established modern listen because of denial, authorization
expiry, a required-check failure or bounded queue pressure, it fences delivery
and cancels maintenance work. A usable stream receives the tagged empty
`complete` result for the original listen request before HTTP chunk termination,
following the [modern subscription closure rule](https://modelcontextprotocol.io/specification/2026-07-28/basic/patterns/subscriptions#graceful-closure).
The closing transition removes unwritten notifications while retaining the
acknowledgment before the terminal. The terminal has a separate bounded
reservation even when regular frames fill the output queue. A stalled writer remains subject to its write-idle timeout;
a disconnect, failed writer or partially written revoked frame can end abruptly.

The completion contains no authorization exception or policy data. Stream and
subscription metrics retain the actual reason: denial, expired lease, failed
check, failed reconciliation or backpressure. An explicit denial during
reconciliation is `SUBSCRIPTION_AUTHORIZATION_DENIED`, and its reconciliation
maintenance event is `DENIED`; it is not a failed-check classification. Request
outcomes and diagnostics also retain the underlying cancellation or failure.
The simulator keeps that reason together with the terminal message. Exact
2025-era GET streams have no listen request to complete and retain their
existing close behavior.

Admission receives the immutable validated, deduplicated requested-resource URI list when authorizing a listen request.
`McpAdmissionContext.getRequestedResourceSubscriptionUris()` preserves first-encounter order and is empty outside applicable subscription requests.
Applications must authorize confidential or capability-bearing subscription URIs during admission and must not infer secrecy merely because a URI is difficult to guess.
A rejected or failed admission never activates a subscription, even though the server generation's single shared publisher listener may already be registered.
With `McpAdmissionController.acceptAllInstance()`, all anonymous callers on one endpoint share its empty authorization/quota partition; one caller can exhaust the configured per-partition subscription bucket for the rest.

The listener parses all protocol filter fields. It acknowledges requested
resource-list/update and tool/prompt list-change families supported by the
selected endpoint view, plus authorized task IDs when Tasks and its event
publisher are enabled. Task filtering and notification
semantics are described under [Task notifications](#task-notifications). Tool and
prompt list-change filters require an effective configured source and caller-visible
catalog projection support; registration structure remains immutable, while access
policy or localization can change the caller's view. The acknowledgment is always
the first stream message. Every subscription message carries the listen request's exact string
or integer ID as `io.modelcontextprotocol/subscriptionId`. That reuse does not
make the ID listener-global: independent subscriptions with equal IDs remain
separate streams and may coexist, including across authorization partitions.

Soklet computes the accepted tool and prompt catalog baselines before
acknowledgment. A catalog invalidation during opening keeps the current
application grant and schedules a bounded comparison after acknowledgment;
it does not repeat subscription authorization merely because the catalog
changed. Actual authorization reconciliation still fences activation and
requires a fresh grant. An unchanged view emits no list-change hint.

Catalog localization fallback under `USE_DEFAULT_TEXT` uses canonical source
text for both the initial baseline and subsequent comparisons. Recovery to
translated text, or a later return to canonical fallback, can emit a hint when
the caller's catalog changes. `FAIL_REQUEST` still rejects a failed initial
render with a sanitized internal error and releases the subscription capacity.
Rendering fallback does not replace the required context for an explicitly
configured catalog access policy: failure to create that context still fails
before evaluation, and policy failures do not become an allow decision.

One filter may contain at most 256 distinct normalized resource-subscription
URIs and, when Tasks is negotiated, at most 256 distinct task IDs. Duplicate
values are deduplicated before the bound is applied. Exceeding either limit is
invalid parameters and fails before application admission or task-manager
lookup.

A valid listen request traverses admission and the optional request limiter;
it does not invoke an application handler, `McpHandlerInterceptor`, a tool
limiter, or consume an application handler slot. Stream count per endpoint and admitted authorization/quota partition, duration, pending queue size, and write-idle time are bounded.
Distinct admitted principals that provide no authorization partition key share the endpoint's empty partition and therefore share one subscription bucket.
Keep-alive comments prevent an otherwise idle writable stream from reaching
its write-idle timeout; `keepAliveInterval` must therefore be strictly shorter
than `writeTimeout`, and `McpServer.Builder.build()` rejects an invalid pair.
A slow or disconnected subscriber is cleaned up without blocking unrelated
subscribers. Because a subscription is intentionally indefinite, graceful HTTP
server shutdown completes it promptly with only the tagged empty terminal
`complete` result when writable. This differs from finite request-scoped
progress SSE, which retains its response path through the graceful-drain
budget. The HTTP subscription graceful-closure contract is that terminal
`subscriptions/listen` result; server-sent `notifications/cancelled` is the
stdio counterpart, so Soklet does not additionally emit it on HTTP.

## Off-network simulation

Pass a completed
[`SokletConfig`](https://javadoc.soklet.com/com/soklet/SokletConfig.html) to
[`SokletSimulator::run(SokletConfig, Simulation)`](<https://javadoc.soklet.com/com/soklet/SokletSimulator.html#run(com.soklet.SokletConfig,com.soklet.SokletSimulator.Simulation)>)
to test the application through a fresh off-network transport graph:

```java
SokletSimulator.run(sokletConfig, simulator -> {
  McpSimulation request = simulator.startMcpRequest(mcpRequest);
  McpSimulationResponse response =
      request.awaitResponse(Duration.ofSeconds(2)).orElseThrow();
  // assertions
});
```

The source configuration describes application behavior and which transports
are present. The simulator call never starts, claims, or changes its live HTTP,
SSE, and MCP objects. Explicit application collaborators are reused by identity,
while configuration-dependent defaults are derived again. Soklet copies an
imported MCP server's construction settings into a simulated server with fresh
framework-owned listener and runtime state. Application-supplied MCP
collaborators, including rate limiters and the task manager, are reused by
identity. A task manager's stable event publisher therefore remains the same
application object as well; the production and simulator runtimes attach
independent listener registrations. Tests remain responsible for isolating
their mutable collaborator state.

Use
[`SimulatorConfig::fromSokletConfig`](<https://javadoc.soklet.com/com/soklet/SimulatorConfig.html#fromSokletConfig(com.soklet.SokletConfig)>)
to obtain a completed single-use simulator configuration, or
[`SimulatorConfig::withSokletConfig`](<https://javadoc.soklet.com/com/soklet/SimulatorConfig.html#withSokletConfig(com.soklet.SokletConfig)>)
to apply test-specific overrides before building. The
[`configureMcpServer(Consumer)`](<https://javadoc.soklet.com/com/soklet/SimulatorConfig.Builder.html#configureMcpServer(java.util.function.Consumer)>)
method customizes the imported MCP construction and can replace a shared
collaborator with a test-scoped implementation:

```java
SimulatorConfig simulatorConfig = SimulatorConfig
    .withSokletConfig(sokletConfig)
    .configureMcpServer(builder -> builder
        .requestTimeout(Duration.ofSeconds(2))
        .toolRateLimiter(McpRateLimiter.fromInMemoryDefaults()))
    .build();
```

For a standalone simulator graph, start with
[`SimulatorConfig::builder`](<https://javadoc.soklet.com/com/soklet/SimulatorConfig.html#builder()>).
The same
[`configureMcpServer(Consumer)`](<https://javadoc.soklet.com/com/soklet/SimulatorConfig.Builder.html#configureMcpServer(java.util.function.Consumer)>)
method supplies a fresh MCP builder whose logical port defaults to `0`. Override
it with [`port(Integer)`](<https://javadoc.soklet.com/com/soklet/McpServer.Builder.html#port(java.lang.Integer)>)
when a test needs another logical value. The fresh builder uses the same
generated-endpoint discovery and accept-all admission defaults as
[`McpServer::withPort`](<https://javadoc.soklet.com/com/soklet/McpServer.html#withPort(java.lang.Integer)>).
A discovered tool still requires a configured fallback tool rate limiter, just
as it does on the production builder. Override endpoint discovery or admission
by calling [`endpointRegistry(...)`](<https://javadoc.soklet.com/com/soklet/McpServer.Builder.html#endpointRegistry(com.soklet.McpEndpointRegistry)>)
or [`admissionController(...)`](<https://javadoc.soklet.com/com/soklet/McpServer.Builder.html#admissionController(com.soklet.McpAdmissionController)>)
on the supplied builder:

```java
SimulatorConfig simulatorConfig = SimulatorConfig.builder()
    .configureMcpServer(builder -> builder
        .port(8082)
        .endpointRegistry(testEndpointRegistry)
        .admissionController(testAdmissionController)
        .toolRateLimiter(McpRateLimiter.fromInMemoryDefaults()))
    .build();
```

A built [`SimulatorConfig`](https://javadoc.soklet.com/com/soklet/SimulatorConfig.html)
can be claimed by exactly one run; derive or build a new one for every run.

Config derivation is transport-isolated, not a deep copy. It preserves every
transport type present in the source; use the standalone builder above when a
test must omit one. Soklet does not inspect or rebind a dependency-injection
provider or other collaborator that captured the source MCP server, so that
object continues to reference the source server unless the test replaces it.

Simulation uses the real MCP processor, admission controller, handlers,
lifecycle callbacks, metrics, streams, and subscriptions, but does not exercise
listener sockets, operating-system buffering, proxies, TLS, or live
backpressure. See the
[README integration-testing guide](README.md#integration-testing) for HTTP,
SSE, capture, and cleanup examples.

## Explicitly enabled 2025 sessions

Sessions are disabled by default and are available only on explicitly selected
`2025-06-18` and `2025-11-25` endpoint revisions. Configure both
`McpServer.Builder.sessionConfig(McpSessionConfig)` and the endpoint's
`sessionProtocolVersions` subset, through its builder or `@McpServerEndpoint`.
A session-enabled endpoint requires the server configuration; an unused server
configuration is rejected. `2026-07-28` stays stateless. GET and DELETE require the
additional HTTP transport admission configuration described below.

Create the configuration with
`McpSessionConfig.withOwnerKeyResolver(sessionOwnerKeyResolver).build()`.
The required `McpSessionOwnerKeyResolver` maps the freshly admitted identity to
one stable, opaque owner key, including issuer/tenant/subject distinctions as
needed. It must be local, nonblocking, thread-safe, nonblank, and at most 256
UTF-8 bytes. Principal equality and authorization/rate-limit partition keys do
not necessarily identify one owner. Resolver failure or timeout fails closed.
Anonymous allocation is disabled by default. `anonymousSessionsAllowed(true)`
is an explicit opt-in: a constant anonymous key deliberately shares a quota,
and a fresh key per initialization must not bypass owner limits.

Successful `initialize` publishes a framework-minted 256-bit opaque ID before
response bytes expose it. The record is usable during the 30-second
acknowledgement window; `notifications/initialized` acknowledges it, and accepted
owner-bound use also proves delivery. Initialization stores a bounded public
client-info/capability snapshot, never the original identity as current
permission. Every later POST is freshly admitted and request-limited before
owner, exact endpoint path, revision, and lifecycle generation are verified.
Only then does its request context receive the remembered client metadata.
A well-formed stale ID on `initialize` requests a fresh allocation; it does not
revive or replace that referenced session.

Malformed, duplicate, oversized, or missing required session framing returns
`400`. After fresh admission, unknown, expired, wrong-owner, and wrong-path IDs
share a neutral `404`; a verified owner/path with the wrong stored revision
returns `400` and preserves the record. Session-path admission rejections remap
application `400`/`404`/`405` to neutral `403`, preserving validated safe headers.
Accepted JSON-RPC operation results/errors use `200`. Per-owner capacity returns
`429`, global allocation pressure returns `503`. Session capacity failures do
not invent a `Retry-After` delay: recovery depends on expiry, DELETE, or actual
physical work exit. Application rate-limit decisions retain their supplied
retry hints. There is no implicit stateless downgrade or unrelated-owner eviction.

The default 256-session count can be filled by 16 owners with 16 sessions each;
new owners then receive `503`. Abandoned, acknowledged sessions can occupy those
slots for the default 24-hour idle window. Existing owners can reclaim their own
quiescent records without freeing space for another owner. Opted-in anonymous
sessions additionally share a 64-session global count. This policy provides
owner isolation, not a fair share of global capacity. Size both count limits for
the expected owner population and shorten idle retention for abandoned clients.
For example, these bounds allow up to four sessions per owner, 1,024 globally,
with abandoned sessions expiring after 15 minutes of actual quiescence:

```java
McpSessionConfig sessionConfig = McpSessionConfig
    .withOwnerKeyResolver(sessionOwnerKeyResolver)
    .maximumSessions(1024)
    .maximumSessionsPerOwner(4)
    .maximumSessionIdleDuration(Duration.ofMinutes(15))
    .build();
```

This still has a finite global ceiling and separate byte/anonymous limits;
256 owners at their four-session cap fill it. Active clients prevent idle
expiry, and absolute lifetime remains separately bounded. Configure routing,
capacity and client reconnection for the deployment's actual traffic.

Physically active initialization, POST and GET work prevents session idle
expiry. Background URI authorization renewal does not count as client activity:
an abandoned session can expire or be evicted for a new initialization by the
same owner even while renewal is unfinished. Logical retirement fences its
grants immediately; unfinished callbacks retain their metadata/evidence and
physical accounting until they actually exit. Neither renewal completion nor
transport keep-alives refresh the client's idle lifetime.

The HTTP `200` operation-result rule also applies to session-disabled 2025
views. HTTP framing/decoding, unsupported protocol selection, admission-hook
failures/rejections, rate/capacity limits and session lookup failures retain
their HTTP failure statuses. A committed POST SSE carries its terminal RPC
outcome in the stream.

For 2025 tools, structured content and declared output schemas require object
shape. Raw/advanced handlers must return a representable shape; arrays,
scalars and null fail projection rather than being silently wrapped or omitted.
Soklet never retries the handler to obtain another shape. Modern tool results
permit other JSON shapes. June tool resource links omit optional icons while
November and modern tool links retain them, without mutating application values
or discarding the completed tool result.

Initial defaults are 256 sessions per server, 16 per owner, 24 hours of actual
quiescence, seven days absolute lifetime, and a 64 KiB initialization projection
also capped at 4,096 nodes and existing JSON limits. Active calls are capped at
32 per session. Request IDs and progress tokens retained for correlation have
separate 256-byte UTF-8 bounds; integer `1` and string `"1"` remain distinct.
Anonymous allocation additionally shares a 64-session global sub-budget.
Persistent session metadata, GET evidence, and historical subscribe requests
have session/owner/global byte bounds separate from transient initialization
and POST evidence. Persistent session bytes have a 1 MiB floor, owner bytes a
2 MiB floor, and global bytes a 16 MiB floor; these bounds also scale with the
configured header ceiling. For transient evidence, let `R` be the configured
maximum body bytes plus maximum header bytes plus 16,384 bytes of accounting
allowance. The session, owner, and global ceilings are `2R`, `4R`, and `32R`.
Exhausting a transient memory ceiling returns HTTP 503 and recovers when the
physical request exits. Thus a valid large call is not rejected solely because
its body exceeds the persistent session ceiling.

When a URI grant retains a subscribe request for renewal, its full evidence is
atomically moved into the persistent budget; no request body or credentials
are discarded from the authorizer's historical context. Failed promotion
changes no accounting or grant. A full persistent budget does not consume the
transient budget needed for unsubscribe, ping, notifications, or DELETE.
Verified session controls have a separate finite reservation. Valid `ping`,
`resources/unsubscribe`, `notifications/initialized`, and
`notifications/cancelled` bodies of at most 16 KiB bypass the ordinary request
limiter after fresh admission, identity/owner verification, and session binding.
Envelope, selector, session, request-ID and collision checks still apply.
Malformed, oversized, stateless, and unsupported notification traffic does not
receive this exemption. Control requests do not need an ordinary handler slot.
DELETE transport admission and owner resolution also use the independent
bounded policy dispatcher, so a full tool handler queue cannot block termination.

The separate evidence ceiling per control is maximum header bytes plus 32,768
bytes. At most four physical controls per session, eight per owner, and 64
globally may retain this evidence. Independently, cleanup traffic shares four
simultaneous reservations and 64 attempts in a sliding second per effective
authorization partition; at most 256 partitions are retained. GET/URI maintenance
uses a separate demand budget. Exhausted control capacity returns HTTP 503.
Fresh admission or owner-policy rejection still fails closed; control capacity
does not make a blocked application authorization callback unbounded.

All evidence budgets retain physical accounting through logical cancellation or
session retirement until the actual callback/request exits. Limits are
ceilings, not reserved memory entitlements or a bound on graphs retained by
application-owned principals/callbacks. Positive configurable values are
required; null tuning arguments restore defaults.

Idle expiry requires actual quiescence; invalid/foreign messages and keepalive
bytes do not refresh activity. Capacity reclamation may retire only expired or
eligible same-owner quiescent records; active work and other owners' live state
are preserved. Logical retirement fences new use immediately, while callback,
worker, and evidence reservations remain until physical exit. Hard expiry can
signal active work. A terminal result whose reservation already won is
preserved; otherwise server expiry attempts a neutral correlated `-32603` on a
usable POST and signals the token's `RESPONSE_TIMEOUT`, with MCP stream reason
`SESSION_EXPIRED`.

After successful framing, fresh admission, applicable request/control limiting, and session binding,
`notifications/cancelled` receives its own empty `202`. Within that verified
session, a usable request ID can cancel matching logically active client work if cancellation wins the terminal reservation. Unknown,
completed, initialization, and otherwise uncancellable targets are ignored.
Invalid optional reason text does not invalidate a usable target; free-form
reasons never enter built-in logs or metric labels. Winning cancellation signals
`CLIENT_CANCELED`, fences later progress/results, and completes the target as
zero-event `200` SSE if it was finite, or cleanly ends an already committed SSE
response without a result. Cancellation is cooperative and does not roll back
side effects. A losing cancellation preserves reserved terminal bytes. A signal
that overtakes target registration may be ignored. Active duplicate IDs return
`-32600`; clients still own the protocol's no-reuse rule for the whole session,
and Soklet keeps no lifetime ID history.

Sessions are node-local. Route initialization and subsequent requests to the same
node using affinity learned from the initial response or an equivalent routing
policy; hashing a newly minted ID cannot route that first request. Restart/node
loss yields neutral `404`. Transparent recovery and operational defaults remain
pending real-host qualification; document manual reconnection until verified.
No shared store, event history, `Last-Event-ID` recovery, GET result recovery, or
restoration of the 3.5.1 session-store/context/ID-generator APIs is provided.

#### Multi-node deployments

Modern and session-disabled legacy requests can reach any eligible node, provided
application data, authorization policy, protected-state keys, and catalog
configuration are available there. Application-owned resource cursors also need
shared or replicated snapshots and keys. Framework legacy catalog cursors carry
navigation state rather than a server-side session; eligible nodes must have the
matching endpoint/revision/catalog fingerprint and negotiated locale.

Session-enabled legacy deployments require affinity to the initializing node for
POST, GET, DELETE, and cancellation messages. Soklet owns the local session ID,
remembered client metadata, active request IDs, and resource grants. The owner
resolver and transport admission controller authorize that state; neither is a
shared-store hook. Adding application storage does not make these sessions
portable. A rolling replacement or node failure loses the affected sessions;
clients must initialize again and recreate their subscriptions. An open stream
cannot move between nodes, and reconnecting provides no event replay.

Applications can supply a durable `McpTaskManager`, distributed
`McpSubscriptionEventPublisher` and `McpTaskEventPublisher` implementations,
authorization callbacks backed by shared policy, and a distributed `McpRateLimiter`.
Publish change events to every eligible node, rather than distributing them among
competing consumers. Broadcast policy invalidation to each server's
`getSubscriptionReconciler().reconcileSubscriptions()`; localization invalidation
also needs to reach every applicable server after its local snapshot is installed.
Soklet's session, connection, subscription, and maintenance-capacity bounds apply
to one server instance. Fleet-wide quotas and coordination remain application-owned.

### Leased GET and verified DELETE

Set the optional `McpSessionConfig.Builder.transportAdmissionController(...)`
to authorize real HTTP GET/DELETE requests. Null clears it. The controller sees
an immutable `McpSessionTransportAdmissionContext` containing the original
request, endpoint, exact revision, offered notification families,
`isReauthorization()`, and a queue-inclusive deadline. Invocation features expose
cooperative cancellation; progress and task creation are unavailable. There is
no fabricated JSON-RPC method or request context.

`McpSessionTransportAdmissionDecision.accepted(identity, validUntil, notificationTypes)`
requires a fresh identity, explicit future expiry, and a subset of the offered
families. DELETE offers and requires an empty set. Rejection uses an existing
`McpAdmissionRejection`; its JSON-RPC error is not published for these HTTP-only
operations. Safe headers and explicitly authored authentication challenges are
preserved, while reserved application `400`/`404`/`405` statuses map to neutral
`403` on session paths.

Both methods require an empty body, one explicit selected protocol revision,
and the live session ID. Host/Origin/CORS and framing run before fresh HTTP
admission and owner/path/revision/generation verification. DELETE is available
on a session view with the controller even without notification families. A
verified, still-authorized DELETE returns empty `204`, fences new use, and
retires that session with `SESSION_CLOSED`; active POST cancellation uses the
neutral token reason `CLIENT_CANCELED`. A terminal result already reserved
remains authoritative.

GET additionally requires selected `subscriptionProtocolVersions`, nonempty
effective notification sources for that revision, and `Accept: text/event-stream`.
Legacy subscription revisions must also select sessions. RESOURCE_UPDATED needs
an existing subscription authorizer; legacy Tasks cannot supply an effective
source. GET can open before the initialization acknowledgement. It returns a
persistent SSE stream with keepalives and authorized resource/catalog invalidations.
GET admission itself grants no resource URI. It never carries POST progress or a
recovered POST result.
These persistent, nonresumable GET streams also omit November's recommended
empty priming event, event IDs, `retry:`, and event history; ignored
`Last-Event-ID` input never supplies recovery.

A GET lease ends at the earliest application expiry, configured maximum
authorization duration, fixed `maximumSubscriptionDuration`, or session deadline.
Renewal reuses the original GET credentials and repeats current HTTP admission,
owner verification, identity and partition checks; it cannot extend that fixed
total lifetime or revive a retired stream. Reconciliation fences earlier GET
authorization before bounded reevaluation. A session allows at most two logical
GETs and one shared modern/legacy subscription registration charged to its fixed
first partition. Capacity rejection is `503` and preserves existing live
streams. Replaced GET request evidence remains charged through physical exit;
active GET leases prevent quiescent session reclamation.

### Legacy URI grants and catalog invalidations

On an explicitly subscription-enabled 2025 session view, `resources/subscribe`
and `resources/unsubscribe` are real POST requests through fresh admission
and session verification. Subscribe uses ordinary request limiting; small,
valid unsubscribe uses the reserved control quota. Subscribe exposes the one validated
URI through `McpAdmissionContext.getRequestedResourceSubscriptionUris()` and requires a
readable route in that revision. Unsubscribe exposes its validated URI through
`getOperationName()` with an empty selection. Both successful operations return
the matching JSON-RPC ID with `result: {}`. Missing and denied subscribe targets
share a neutral error; route existence does not grant notification permission.

The existing `McpSubscriptionAuthorizer` grants and renews each URI lease using
the real subscribe request context. Its historical credentials and latest
successful application context remain retained evidence, so the authorizer must
check current authority. Duplicate subscribe obtains fresh authorization and
atomically replaces that evidence without extending the original total grant
lifetime. Unsubscribe fences both establishing and active grants. Reconciliation
synchronously fences GET and URI authorization before bounded reevaluation;
a late result cannot restore a revoked generation. Deferred cancellation actions
cancel only older-generation checks, preserving a fresh renewal that has already
started under the fenced generation.

Refreshing credentials requires a newly admitted GET for stream permission and
a fresh or duplicate subscribe for each URI permission. A GET with refreshed
credentials does not replace the historical credentials of existing URI grants.
Revocation must be checked against current authority during both renewals;
request reconciliation when permission changes need prompt reevaluation.

URI grants belong to the session and survive GET loss within their lease,
fixed total lifetime, and session deadlines. A new GET needs fresh HTTP admission
and receives notifications only for its admitted families and current URI grants.
Transient URI renewal exceptions or timeouts fence delivery and allow at most
three consecutive failed attempts under the unchanged lease, with 50 ms then
100 ms retry delays. A successful authorization resets the failure count.
Capacity retries remain bounded by control/maintenance quotas and lease expiry.
An unfinished callback keeps its physical slot and prevents overlapping checks
for that URI; a timeout cannot extend permission or release its retained evidence.

Explicit renewal denial, exhausted failure retries, or an expired lease/total
lifetime on an established URI grant retires the whole session. Its GETs close,
current calls are cancelled, and subsequent verified session lookup returns
neutral HTTP 404. The client can reinitialize, refresh credentials as needed,
and resubscribe. This avoids an apparently healthy stream silently forgetting a
subscription. Initial subscribe rejection and explicit unsubscribe do not
themselves retire a session, and stale callbacks cannot retire a newer grant.
The newest eligible GET is preferred. Each invalidation uses only one connected
stream per session, including when GETs overlap.
Resource updates carry only the URI; resource, tool, and prompt catalog changes
are coarse invalidation hints. Configured publisher hints and applicable
localization/caller-dependent catalogs determine the advertised `subscribe` and
`listChanged` capabilities; immutable caller-independent catalogs do not
advertise a false change source. Modern `subscriptions/listen` remains separate.

One pending dirty bit per catalog family or URI grant survives GET gaps when
no stream has completed the notification write. A new authorized GET can receive
that pending invalidation. A message fully written to one GET is not resent to
another GET or on reconnect. A failed offer or dropped unwritten frame leaves
its invalidation pending; another writer must wait until the original frame's
write/drop outcome is known. URI changes arriving during a queued frame remain
pending for a subsequent hint. Catalog hints remain coalesced until a corresponding first list page is
successfully rendered. A failed or later page does not acknowledge the hint; a
concurrent catalog change keeps it pending. This follows the
[2025 single-stream rule](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports#multiple-connections).
These bits provide neither event history nor receipt guarantees. No legacy
subscription acknowledgement, subscription-ended, or Tasks notification is sent.

A session/owner has at most 64 URI grants, with 512 per server and 65,536 retained
URI bytes per session. GETs and grants share the fixed session partition quota.
Encoded queued GET invalidations, including SSE framing, are capped at 2 MiB per
owner and 16 MiB globally, in addition to existing stream frame limits. Pressure
coalesces hints, then sheds the largest pending stream in the affected budget;
dirty state remains bounded for a later authorized GET. Guarded frames recheck
GET/grant generations and expiry before every socket write. Revoked unwritten
frames are purged; a partially written revoked frame closes before further bytes.
Byte reservations release exactly once after write, drop, or close.

GET and URI maintenance share the four-job and 64-dispatches-per-second scheduler;
due renewals are ordered by the shortest remaining authorization lease across
both kinds of work, including after reconciliation. Priority does not interrupt
an already running callback or extend an authorization deadline. The fixed
four-job and 64-dispatches-per-second budget limits renewal throughput: size
leases and retained sessions for the measured duration of application callbacks.
A renewal backlog can exhaust a lease and retire the session. Tenant-scoped
authorization partitions share the four-concurrent/64-attempts-per-second
control budget, so a burst of cancellation, ping, initialization or other
cleanup controls can receive capacity rejections even across distinct owners.
Short leases also consume a bounded aggregate maintenance-demand reservation.
Physical callbacks and historical evidence stay charged until actual exit.
Framework byte accounting does not measure arbitrary application principal or
context graphs: keep those retained objects small and safe. Named-host refresh
and reconnect development observations are recorded in the
[compatibility record](release/MCP_CLIENT_COMPATIBILITY.md). Released-SDK
development checks also exercised actual Bearer refresh and revocation;
named-host OAuth recovery and exact-candidate qualification remain pending.

GET/DELETE use the existing generic HTTP lifecycle/metrics boundary with
`ServerType.HTTP` and no `ResourceMethod`; they do not create MCP RPC request or
limiter events. Generic finish callbacks run on dedicated bounded workers,
never on the connection selector. Their worker count is the smaller of four
and `requestHandlerConcurrency`; pending capacity is `requestHandlerQueueCapacity`.
One slot is reserved before the start callback and held through physical finish,
including the whole GET lifetime. A full or quiesced observation budget skips
both callbacks and emits a fixed diagnostic; it does not deliver an unpaired
start or delay protocol traffic. Slow callbacks still consume their reserved
slots and can make shutdown incomplete. Transport end during a blocked start
is remembered and delivers finish once that start returns. GET lifetimes use `SubscriptionOpened`/`SubscriptionClosed`, not
RPC `RequestStreamOpened`/`RequestStreamClosed`. Sensitive original requests and
Throwables at application observer boundaries require application retention
policy. No public session lifecycle callback or session/owner metric dimension
is added. Real-host GET/DELETE behavior, renewal, and recovery still require
qualification against the exact candidate.

The existing simulator supports GET/DELETE through the same session and lease
path. DELETE ends a simulated GET with `SESSION_CLOSED` and no JSON-RPC
message; disconnect uses `CLIENT_DISCONNECTED` and leaves ordinary POST usable.
Real-socket tests supplement simulation for physical writes and cleanup.
Bounded released TypeScript Client/Core `2.2.0` development checks exercised
both exact 2025 revisions, including URI/catalog hint delivery, automatic GET
recovery, policy-generation reconciliation, quiet lease renewal, and verified
DELETE. Separate Inspector and VS Code observations cover their exercised
revisions and specific display/refresh limits. A separate released-SDK HTTP
check exercised real disposable Bearer credentials: a refreshed GET did not
refresh historical URI evidence, fresh/duplicate subscribe replaced that
evidence, and revocation closed GET and denied URI renewal. Revoked reconnect
received `401` with an `invalid_token` challenge; a valid same-owner credential
could still DELETE with `204`. These checks do not establish OAuth token
issuance, named-host credential recovery or immutable release qualification. See the
[development compatibility record](release/MCP_CLIENT_COMPATIBILITY.md).

## HTTP and error policy

MCP messages use POST. `OPTIONS` exists only for the CORS preflight path. Modern
and session-disabled views return `405` for GET/DELETE; opted-in 2025 views use
[HTTP transport admission](#leased-get-and-verified-delete). POST requires `Content-Type: application/json`, and
`Accept` must permit both `application/json` and `text/event-stream` according
to the protocol's negotiation rules.

On `2026-07-28`, every JSON-RPC request carries `Mcp-Method`. Tool calls,
prompt gets, and resource reads also carry `Mcp-Name`; each header must agree
with the JSON-RPC method or selected operation. Notifications are exempt from
these modern header requirements. Modern and session-disabled 2025 views ignore
`MCP-Session-Id`; an explicitly session-enabled 2025 view requires its initialized
ID on later POSTs. `Last-Event-ID` is ignored at every revision and supplies no
replay position. Both names remain forbidden in application-authored MCP response
headers; only the framework may publish a session ID on successful initialization.

An identifiable HTTP `notifications/cancelled` message still traverses version
validation, admission, and applicable request/control limiting. An accepted notification returns an
empty HTTP 202; session-enabled paths also verify framing and session binding.
Modern and session-disabled 2025 views ignore its payload. A verified 2025
session can target a matching active client request, subject to its atomic
terminal reservation. See [2025 sessions](#explicitly-enabled-2025-sessions).
Deadline, forced shutdown after graceful drain, and stream failure signals also
drive cooperative cancelation; disconnect and lost-writer failure follow the
legacy commitment rules above. Other notifications never receive a JSON-RPC
response body.

## Validation precedence

The `2026-07-28` JSON-RPC request path has 17 ordered groups. The first failure
wins:

1. connection, header-count, header-size, request-size, and timeout limits;
2. endpoint routing;
3. Host authority, followed by Origin prevalidation and CORS authorization;
4. POST-only HTTP method and `Content-Type`/`Accept` negotiation;
5. strict JSON parsing;
6. JSON-RPC envelope validation;
7. required mirrored-header cardinality/form and method/name agreement, plus
   registration-independent custom-header policy for non-tool methods;
8. the non-failing readable nested body-revision probe, followed by exact selector membership/profile selection and unsupported dispatch;
9. selected-profile required `_meta`, metadata-key, extension/settings, and universal-spine validation, followed by post-map header/body revision agreement;
10. cheap method/structural parameter validation;
11. application admission, then caller-neutral target/descriptor lookup, custom
    tool mirrored headers, request-state shape and required client capabilities,
    followed by the optional request limiter and resolved tool limiter;
12. bounded handler-queue admission and handler-slot acquisition;
13. the application handler interceptor;
14. complete application input conversion and validation, including Profile 1
    tool input-schema evaluation;
15. handler invocation;
16. preliminary result-shape recognition, applicable tool-output sanitization,
    and remaining result/output-schema validation; and
17. envelope generation and response write.

Caller-aware tool/prompt catalogs defer descriptor checks through their existing
access-policy and quota ordering after admission. Unsupported selectors on tool
calls fail at profile selection without consulting registered custom headers;
required protocol/method/name header errors still take precedence.

Notifications have a separate, shorter path. The shared transport prefix is
limits, routing, Host, Origin/CORS, POST/media/Accept, strict JSON, and envelope classification.
A classified notification then validates protocol-selector cardinality/form and exact registry membership
before any profile-specific present `_meta`, admission, and the optional request limiter.
Unsupported selectors return an empty HTTP 400 without profile mapping. An identifiable `notifications/cancelled` skips parameter and present-`_meta` validation, but traverses the other stages before its empty HTTP 202 result.
The small, valid, owner-verified legacy control subset described above uses the
independent cleanup quota instead of the ordinary request limiter. Malformed or
unsupported notifications and session-disabled views retain ordinary limiting.
Notifications do not acquire request-only mirrored-header, required-`_meta`/capability, tool-limiter, queue/slot, interceptor, handler, sanitizer, or response-envelope semantics.

The `2026-07-28` request path does not implement `initialize`, an
initialization handshake, or a session. Its exact-revision registry is the
authority for modern profile selection and diagnostics. The separate 2025
adapter handles `initialize` at the same URL when an implemented 2025 revision
is explicitly selected; that adapter is not part of the modern request path.
On a modern-only endpoint, a bounded unsupported-profile diagnostic is attached
after either of two triggers: strict JSON exposes the exact readable method
`initialize`, or selector cardinality/plain-string validation succeeds and the
selector is absent from that registry.
Ordinary eligible failures use `data.supportedVersions`; an actual unsupported version retains its defined registry-ordered `supported` then exact header-derived `requested` shape.
Pre-JSON transport failures, unparseable JSON, unreadable methods, and row-1 envelope failures for other methods cannot acquire selector-derived data because selector validation has not run.

The universal HTTP `no-store` policy includes early parser/transport errors,
fixed JSON and empty responses, CORS preflight, notification responses, and
production SSE. Application policy cannot replace it with a cacheable value;
an attempted `Cache-Control` response header fails closed.

Implemented framework mappings are stable:

| Condition | HTTP | JSON-RPC/MCP code |
| --- | ---: | ---: |
| Rate-limit denial | 429 | `-31999` |
| Strict unknown mirrored header | 400 | `-31998` |
| Handler capacity exhausted | 503 | `-32603` |
| Queued request deadline | 503 | `-32603` |
| Active application-handler request deadline | 504 | `-32603` |
| Framework-owned protocol-operation deadline | 504 | bodyless |
| Standard or custom header mismatch | 400 | `-32020` |
| Unsupported protocol version | 400 | `-32022` |
| Missing required capability | 400 | `-32021` |
| Specified invalid parameters | 400 | `-32602` |
| Invalid, expired, or wrong-bound request state | 400 | `-32602` |
| Request-state protector unavailable | 503 | `-32603` |
| Application/protector output contract failure | 500 | `-32603` |
| Unknown request method | 404 | `-32601` |

Readable request IDs retain their original string or integer identity. Error
factories prevent applications from spoofing JSON-RPC-, MCP-, or Soklet-owned
reserved codes, and framework failures omit unsafe application diagnostics.

## Host, Origin, and CORS

Host validation is independent of CORS and runs before protocol parsing or
application side effects. A loopback bind literal or `localhost` seeds the
listener's effective authority. Every non-loopback bind requires at least one
explicit `McpServer.Builder.allowedHosts(...)` hostname or IP literal, or
server construction fails; Soklet does not implicitly accept the bind
authority in that case.

An absent `Origin` is allowed by default and may instead be required with
`McpAbsentOriginPolicy.REQUIRE_ORIGIN`. A present Origin is rejected unless
the existing shared `CorsAuthorizer` approves it. Omitting the authorizer uses
reject-all behavior for present origins and emits one fixed startup diagnostic;
supplying `CorsAuthorizer.rejectAllInstance()` makes that choice explicit.

Custom CORS implementations must be thread-safe and implement the shared
transport-neutral `authorizePreflight(Request, CorsPreflight, Set<HttpMethod>)`
overload. Its default implementation rejects; implementing only the ordinary
HTTP `Map<HttpMethod, ResourceMethod>` overload does not authorize MCP
preflights. Deliberate denial is HTTP 403. A null, throwing, or out-of-surface
authorizer result fails closed without CORS allow headers. See
[SECURITY.md](SECURITY.md#mcp-deployment-security) for deployment guidance.

The allowed request-header surface contains the modern protocol/name headers,
registered `Mcp-Param-*` headers, and `Authorization`. A path enabling 2025
sessions additionally permits and exposes the framework-owned `Mcp-Session-Id`;
session-disabled paths still reject it in preflight. A URL with configured legacy
GET also permits bounded `Last-Event-ID` input, which is ignored and supplies no
replay position. Other paths reject it in preflight. Preflight without a revision
selector uses the URL's configured facility union; actual dispatch and `Allow` use
the explicit selected revision. Both session/replay names remain forbidden in
admission-policy response headers. Successful CORS responses can expose `WWW-Authenticate` for
application-owned authentication challenges.

## Mirrored tool headers

`@McpHeader(name = "Tenant")` on a typed tool argument publishes the `x-mcp-header`
schema extension and requires `Mcp-Param-Tenant` to agree with that property
already parsed from the JSON arguments. It never supplies an absent or null
argument from the header. Mirroring is limited to statically reachable
properties whose direct schema type is `string`, `boolean`, or `integer`.
Both values remain untrusted input. Tools declaring mirrors must select only
`2026-07-28`; endpoint construction rejects either 2025 revision on such a tool.

Integer mirroring constrains each request value independently of the published
schema range. `long`, `Long`, and `BigInteger` declarations are accepted;
derived `long`/`Long` schemas retain Java's full range and `BigInteger` has no
derived minimum/maximum. A mirrored integer must be in the inclusive range
`-9007199254740991` through `9007199254740991`, even when its input schema accepts
a wider range. Authored schemas may declare narrower bounds if desired.

The integer header uses canonical decimal spelling: `0`, or digits without
leading zeros, optionally preceded by `-`. `+1`, `01`, and `-0` are invalid.
An integral JSON number spelled `1.0` or `1e0` can match the header `1`.
An unsafe integer or other mismatch produces the fixed HTTP 400 / JSON-RPC
`-32020` response with message `"Header mismatch"`, after admission and before
handler entry. Submitted names and values are not included in that response.

In an authored input schema, `x-mcp-header` must be a string containing the
nonempty RFC 9110 field-name-token suffix, such as `"Tenant"`, not the complete
`Mcp-Param-Tenant` field name. It may appear only on a property reached from the
schema root solely through `properties` chains; that property must declare the
direct type `string`, `boolean`, or `integer`. Suffixes must be unique
case-insensitively within the tool schema. Soklet rejects invalid placement,
type, token syntax, or collisions when `inputSchema(...)` compiles the document.

Unknown `Mcp-Param-*` headers are ignored by default and never become tool
arguments. `McpUnknownMirroredHeaderPolicy.REJECT_REQUESTS` enables request-
only strict rejection with HTTP 400/MCP `-31998`. Name-bearing diagnostics are
separate, bounded, disabled by default, and may expose attacker-supplied header
names to application logging and retention systems; Soklet never logs their
values through that diagnostic.

## Lifecycle and metrics

MCP uses Soklet's existing `LifecycleObserver` and `MetricsCollector` hosts.
Use lifecycle observers for request tracing and audit hooks, and metrics
collectors for bounded counters, gauges and histograms. `McpHandlerInterceptor`
is an application-handler hook, not a replacement for either observation API.

Pair `com.soklet:soklet:4.0.0` with `com.soklet:soklet-otel:2.0.0` when using
the official OpenTelemetry integration.

### Request and server lifecycle

Admitted framework and application operations receive
`didStartMcpRequestHandling` and `didFinishMcpRequestHandling` with the same
immutable `McpRequestContext`. Start occurs after admission and before request
limiting, handler-queue admission, interception and handler execution. Finish
occurs once at the client-visible terminal outcome; an uncooperative handler
can remain physically active afterward. Callbacks can run on different threads
and independent requests can overlap. Observer exceptions are contained.

Callback context, error values and exact observed `Throwable` objects are
application-owned sensitive data. The built-in MCP failure log omits request
and throwable attachments; this does not redact the objects supplied to an
application observer or a custom log event.

`Soklet` owns one lifecycle for its configured transports. `ServerStarted`
requires listener readiness. Coordinated shutdown projects one
`McpMetricsEvent.ServerStopped` from the configured MCP component's published
`ShutdownComponentResult`, including `NOT_STARTED` when the listener never
started. Failed or unfinished registration cleanup at the shared lifecycle
deadline remains residual or unproven evidence in that immutable result.
Repeated shutdown calls and eventual residual-work exit do not duplicate the
terminal outcome. A new lifecycle requires fresh transport instances.

Semantic metric delivery is asynchronous and may follow result publication.
Tests inspecting counters must await the corresponding observation. A stopped
owner cannot restart, and separate owners have independent event queues.

### Tasks observability boundary

A task-producing tool invocation is an ordinary admitted `tools/call`
request, so its lifecycle callbacks and request metrics use `tools/call`.
Admitted `tasks/get`, `tasks/update`, and `tasks/cancel` requests receive the
same exactly-once request start/finish lifecycle pair and
`McpMetricsEvent.RequestStarted`/`RequestFinished` events as other MCP
requests. Those three task methods are recognized bounded method values, and
their request outcomes and durations are available through the ordinary
aggregate metric families. The live listener and off-network simulator use
the same observation path.

These observations cover Soklet's protocol and manager-call boundary, not the
durable work lifecycle. Soklet provides no separate public task-created or
task-transition observability callback or metrics event, and no built-in
counter or gauge for task status changes, active tasks, retries, leases, queue
depth or backlog, or worker execution. An outbound `notifications/tasks`
projection likewise has no dedicated task-notification counter; the
surrounding subscription and stream still use their ordinary metrics.

Applications should instrument the task manager, repository or outbox, queue,
and worker layers where those transitions actually become authoritative. Use
bounded application-defined dimensions such as a task kind, transition, or
fixed failure category. Never use a task ID, task contents, status message,
principal, origin, or other per-task value as a metric label. See
[Durable Tasks](#durable-tasks) for the ownership and authorization contract.

### Server diagnostics

`McpServer.getDiagnostics()` returns an immutable instantaneous
`McpServerDiagnostics` snapshot even when metrics are disabled:

| Accessor | Meaning |
| --- | --- |
| `getStatus()` | Captured lifecycle status |
| `getBoundAddress()` | Effective address, including an ephemeral port; empty until binding succeeds and retained after shutdown |
| `getRequestHandlerConcurrency()` | Configured active-handler limit |
| `getRequestHandlerQueueCapacity()` | Configured admitted wait-queue limit |
| `getActiveHandlerExecutions()` | Physically occupied handler slots, including residual work |
| `getRequestHandlerQueueDepth()` | Admitted requests waiting for a handler slot |
| `getActiveRequestStreams()` | Open streams, including subscription and configured 2025 GET bodies |
| `getActiveSubscriptions()` | Open subscription streams, including configured 2025 GET bodies |
| `getProtectionMode()` | Selected framework request-state protection mode |
| `isApplicationRequestStateProtectorConfigured()` | Whether the selected mode uses a custom protector |
| `getProtectionKeyringFingerprint()` | Optional live production-keyring fingerprint |
| `getTraceCorrelationFingerprint()` | Optional live trace-correlation configuration fingerprint |

Status, address, configured bounds and live counts form one runtime-owned
atomic tuple. Security configuration forms a separately owned atomic tuple;
the combined view has no single global linearization point. Handler counts
stay within their configured limits, and
`0 <= activeSubscriptions <= activeRequestStreams`. An ordinary request SSE
stream contributes `1/0` to the stream/subscription pair; a subscription
contributes `1/1`. Opening a stream does not prove client receipt.

A proof-complete stop reports zero live counts. Residual handlers and streams
remain counted until their physical exit or cleanup; publishing a shutdown
result does not itself prove that they have drained. Transient failure-cleanup
snapshots can retain work that has not yet been fenced or drained. Retaining a
snapshot freezes its values; collector reset does not change diagnostics.

The custom-protector flag is true exactly for `CUSTOM_PROTECTOR`; it does not
describe an operation's `APPLICATION_PROTECTED` mode. The protection
fingerprint is present exactly for `PRODUCTION_KEYRING`. The trace fingerprint
is present when correlation was enabled at construction. Successful key
rotation changes subsequent snapshots, not retained snapshots.

Fingerprints expose no raw keys, per-key tags or correlation tokens. They are
operational comparison values, not authentication or derivation inputs. They
reveal configuration equality and can change during rotation, so do not use
them as per-request metric labels. For production-keyring convergence,
compare `getVersion()`, `getProfile()` and `getValue()` together. Diagnostic
encoding `v2` compares exact raw key bytes; the earlier `v1` encoding could
conflate keys that cannot open each other's state. Different versions are
incomparable during a rollout. This diagnostic change does not change sealed
state or trace tokens and requires no secret rotation.

### Semantic event delivery

`MetricsCollector.didRecordMcpMetricsEvent(McpMetricsEvent)` receives immutable
transition-specific events. Implementations must be thread-safe, nonblocking
and avoid I/O. Soklet serializes eligible semantic metric delivery and contains
collector failures. Short runtime transitions can withhold their own pending
records; lifecycle deferral preserves start/shutdown ordering. An unrelated
request can make progress while another transition is deferred. This is
enqueue/delivery ordering, not a total causal order across racing requests.

At most 4,096 pending records are retained. When the queue is full, new
ordinary records are omitted; `ServerStarted` and `ServerStopped` can reclaim
an ordinary record to preserve lifecycle evidence. Withheld records count
against that same bound. Omission does not change the wire outcome, and
observed counters or gauges can be incomplete after overflow. Use diagnostics
when an instantaneous live-state view is required.

Connection acceptance means a socket was accepted and capacity reserved;
later setup failure can still produce `TransportFailure`. Connection rejection
means the configured capacity was full. Accept-loop, setup and event-loop
faults use a fixed transport-failure reason instead of a capacity rejection.
These events and built-in MCP failure logs retain no request, remote address
or throwable. A byte-free idle connection closes quietly; genuinely partial
requests follow the parser/read-timeout failure path.

`RequestAccepted` means submission to the bounded protocol processor.
Submission rejection emits `RequestRejected` without a retained accepted
record. Admitted semantic handling has its separate `RequestStarted` and
terminal `RequestFinished` pair; the boundary counters are not conservation
equations for admitted outcomes. A full handler queue emits
`HandlerCapacityRejected`; queued deadline, disconnect, cancelation and forced
shutdown removal produce a dequeue instead.

Verified 2025 GET/DELETE requests use generic HTTP lifecycle/metrics callbacks
with the configured endpoint route and `ServerType.HTTP`; they create no
fabricated RPC context, RPC method or request-limiter call. GET lifetimes emit
subscription open/close events, while RPC request-stream open/close events
remain tied to RPC streams. Physical GETs awaiting cleanup remain included in
stream/subscription diagnostics. There is no separate session metric family
or session-ID/owner-key dimension. See [2025 sessions](#explicitly-enabled-2025-sessions).

### Default metric families

`MetricsCollector.Snapshot.getMcpMetrics()` exposes an immutable
`McpMetricsSnapshot`. Scalar counts use boxed `Long` values; duration maps
contain `MetricsCollector.HistogramSnapshot` values, with boxed counts,
boundaries and a `Double` sum. Maps and keys are defensively retained. The
built-in Prometheus/OpenMetrics renderer uses these families:

| Family | Kind | Labels |
| --- | --- | --- |
| `soklet_mcp_server_starts_total` | Counter | None |
| `soklet_mcp_shutdowns_total` | Counter | `outcome` |
| `soklet_mcp_connections_accepted_total` | Counter | None |
| `soklet_mcp_connections_rejected_total` | Counter | None |
| `soklet_mcp_requests_accepted_total` | Counter | None |
| `soklet_mcp_requests_rejected_total` | Counter | None |
| `soklet_mcp_requests_active` | Gauge | None |
| `soklet_mcp_requests_total` | Counter | `endpoint`, `method`, `outcome` |
| `soklet_mcp_request_duration_nanos` | Histogram | `endpoint`, `method`, `outcome` |
| `soklet_mcp_request_streams_active` | Gauge | None |
| `soklet_mcp_request_stream_duration_nanos` | Histogram | `endpoint`, `method`, `reason` |
| `soklet_mcp_subscriptions_active` | Gauge | None |
| `soklet_mcp_subscription_duration_nanos` | Histogram | `endpoint`, `reason` |
| `soklet_mcp_subscription_maintenance_total` | Counter | `endpoint`, `work`, `outcome` |
| `soklet_mcp_cancelations_signaled_total` | Counter | `endpoint`, `method` |
| `soklet_mcp_progress_emitted_total` | Counter | `endpoint`, `method` |
| `soklet_mcp_keep_alives_emitted_total` | Counter | None |
| `soklet_mcp_protocol_errors_total` | Counter | `code` |
| `soklet_mcp_unknown_mirrored_headers_total` | Counter | `endpoint`, `method` |
| `soklet_mcp_handler_executions_active` | Gauge | None |
| `soklet_mcp_handler_queue_depth` | Gauge | None |
| `soklet_mcp_handler_capacity_rejections_total` | Counter | None |
| `soklet_transport_failures_total` | Shared counter | `server_type="MCP"`, fixed `reason` |

Configured MCP scalar counters and live gauges render at zero. Labeled maps
and histograms are sparse; an empty or fully filtered family emits no orphan
HELP/TYPE metadata. HTTP/SSE/MCP transport failures share one family. Filters
operate on individual samples, and OpenMetrics output ends with one EOF.

Framework-produced dimensions are registered endpoint paths, recognized
methods or `<unrecognized>`, fixed enum values and fixed protocol-error codes.
They never contain tool names, resource URIs, principals, session IDs, header
identity, request IDs, trace data, arguments, results or throwables. Enum
outcomes and stream reasons use lower-snake spelling; shared transport-failure
reasons retain their enum spelling.

`ProtocolError` counts only framework errors with codes `-32700`, `-32600`,
`-32601`, `-32602`, `-32603`, `-32020`, `-32021`, `-32022`, `-31999` and
`-31998` after successful response encoding or an accepted terminal-stream
reservation. Failed provisional terminals, application error codes, tool
`isError` results and empty-notification HTTP errors do not enter that family.
Unknown mirrored headers count per occurrence under IGNORE and REJECT policy,
using only endpoint and method; name/value diagnostics are a separate opt-in.

Subscription-maintenance counts use `getSubscriptionMaintenance()` and
`SubscriptionMaintenanceKey`, with the existing `Work` and `Outcome` enums.
They include delivered coalescing and stale-result-discard records, not unique
subscriptions or a count of all started attempts. Progress and keep-alive
counts describe accepted delivery, not proof of client receipt. Cancelation
counts describe an accepted cooperative signal, not proof of handler exit.

Dimensioned default-collector maps have an 8,192-key capacity with approximate
LRU eviction. New dimensions can evict older aggregates. Public event
factories and snapshot builders allow application-supplied dimensions and are
not a sensitive-data classifier or the framework method/code allowlist.
Applications own the confidentiality and cardinality of manual events,
custom collectors and downstream storage.

### Histograms and reset

MCP request durations have these finite boundaries, expressed here in seconds:
`0.001`, `0.002`, `0.005`, `0.01`, `0.025`, `0.05`, `0.1`, `0.2`, `0.4`, `0.8`,
`1.5`, `3`, `7`, `15`, `30`, `60`, `120` and `300`, followed by `+Inf`.
Snapshots and text exports use nanoseconds. The layout covers the default
60-second request timeout and longer configured deadlines through five
minutes; larger samples use overflow. All supported MCP revisions share it.

Request-stream and subscription durations use finite boundaries of `1`, `5`,
`10`, `30`, `60`, `120`, `300`, `600`, `1800`, `3600`, `7200` and `14400`
seconds plus overflow, also exported in nanoseconds. These layouts are
independent of ordinary HTTP request histograms. Recheck queries and filters
that select exact `le` values when migrating.

`reset()` clears cumulative counters, maps and histograms while preserving
live request, handler, handler-queue, request-stream and subscription gauges.
A lifetime crossing reset contributes its full original duration when it
finishes. Residual physical handlers remain active until exit. Retained
snapshots never mutate, but independently sampled maps and instruments have
no shared transaction or conservation invariant.

Histogram count is derived from the captured final cumulative bucket. Sum,
min, max, buckets and reset observations do not form one atomic sample.
`Double` sums retain the original unit and can lose integer precision for
large totals. See [histogram migration](MIGRATING_TO_4_0.md#histogram-sums-and-snapshot-values)
for the boxed/List API and precision contract.

### OpenTelemetry metrics and request spans

`OpenTelemetryMetricsCollector.didRecordMcpMetricsEvent(...)` maps the same
semantic events to dedicated `soklet.mcp.*` instruments and the shared
`soklet.server.transport.failures` counter. Its MCP metric schema is independent
of the HTTP metric naming strategy. Durations are recorded in seconds with
the same boundary advice listed above; SDK views can override that advice.
The SDK and telemetry backend own series retention, export and filtering;
snapshot zero rendering, reset and cross-instrument atomicity are not SDK
guarantees. See the [integration metric reference](https://github.com/soklet/soklet-otel#emitted-metrics)
for instrument names, kinds, units and attributes.

With `OpenTelemetryLifecycleObserver` installed,
`SpanPolicy.recordMcpRequestSpans()` defaults to true. An admitted semantic
request or notification creates one SERVER span through its client-visible
terminal outcome, including stream/subscription lifetime. The default name is
`MCP <method>` with a recognized method or `<unrecognized>`. Custom naming
receives the full context and remains application-owned.

Only validated MCP request metadata supplies the remote parent. Physical HTTP
trace headers, ambient context and baggage are not fallback parents. Terminal
projection uses the fixed request outcome and, when present, the client-visible
error code. Error messages/data and exact lifecycle throwables are not exported
as span attributes, descriptions or exception events. Physical client-address
and Soklet-request-ID attributes remain separate default-off opt-ins; the MCP
JSON-RPC request ID is not exported. See the
[integration tracing reference](https://github.com/soklet/soklet-otel#emitted-spans)
for exact attributes, terminal status rules and SDK ownership.

### Trace correlation and logging

Trace correlation is default-off and uses only validated MCP trace metadata.
Configuring `traceCorrelationKey(...)` enables a versioned pseudonymous token;
`logRawValidatedTraceIds(true)` independently opts into the validated lowercase
trace ID. At admitted-request finish, Soklet attempts one bounded
`LogEventType.MCP_TRACE_CORRELATION` record when either value is available.

Token logging carries only format, key ID and token, plus the trace ID if
separately enabled. Raw-ID-only logging is supported without a correlation key.
The record carries no complete trace context, parent/span ID, flags,
`tracestate`, baggage, request, resource method, response or throwable. Neither
mode adds trace values to metrics. Correlation tokens and raw IDs are sensitive,
high-cardinality log data; operators own access, retention and export policy.

`McpServer.getTraceCorrelationKeyManager()` exposes enabled state, active
non-secret key ID, secret-free fingerprint and `rotateActiveKey(...)`.
Rotation atomically replaces the active key; there is no public history or
historical-token derivation. Reusing the active ID with different bytes or
reusing protection-key material is rejected. A request captures its token once;
later rotation does not rewrite that request. Fingerprints compare deployment
configuration, not identity or authorization. See the
[privacy boundary](release/MCP_PRIVACY_BOUNDARY.md) and
[observability guide](https://www.soklet.com/docs/mcp-observability-and-testing).

## Compatibility and unsupported features

Soklet exposes exact `2026-07-28`, `2025-06-18`, and `2025-11-25` views behind
endpoint and operation version declarations. Both 2025 profiles support
synchronous tools, ordinary prompts/resources, argument completion,
request-scoped POST progress, and static catalog pagination. Their
[optional sessions](#explicitly-enabled-2025-sessions) retain public client
metadata and enable active-request cancelation. Configured HTTP transport
admission enables verified DELETE and, with selected subscriptions and effective
change sources, leased GET resource/catalog notifications and session-owned URI
grants. GET admission alone grants no URI permission. Sessions are node-local:
initialization and later POST, GET, and DELETE traffic require affinity to the
initializing node; node loss requires reinitialization and resubscription.

Skills, Apps UI, Tasks, `subscriptions/listen`, multi-round input, and
server-initiated requests are not exposed through either 2025 profile.
Notification delivery is best effort without event history, replay, or
lost-POST-result recovery. `2025-03-26` is unsupported. There is no implicit
latest revision; see [exact protocol revisions](#exact-protocol-revisions) for
initialization negotiation and header selection. Named-client and release
claims require qualification of that particular supported profile and artifact.

Client extension settings are open but do not implicitly enable server
behavior. Keys in `clientCapabilities.extensions` must use a valid namespaced
extension identifier. A valid unsupported setting remains inspectable through
the request's client capabilities, while Soklet continues core processing
without inventing a core capability, advertising matching server support, or
reflecting the setting into the response. Malformed extension containers,
identifiers, or settings fail before admission, and unsupported extension
methods retain the normal explicit unknown-method behavior.

Applications can use that compatibility surface on an existing core method:
admission, an `McpHandlerInterceptor`, and the operation handler observe the
same request-scoped capabilities; the handler can call `findExtension(...)`,
inspect namespaced inbound request metadata, and return nonreserved
`McpCompleteResult` metadata that the interceptor may transform or replace.
This remains application-owned behavior. It does not advertise matching server
support or register a new protocol method.

Tasks is a namespaced protocol extension implemented by Soklet on the
`2026-07-28` profile. It is advertised only when an application configures an
[`McpTaskManager`](https://javadoc.soklet.com/com/soklet/McpTaskManager.html)
and opts the endpoint into Tasks for that revision,
and it is negotiated only when the current request declares the exact
`io.modelcontextprotocol/tasks` client extension capability. The legacy `task`
member of `tools/call` is ignored rather than treated as opt-in, and obsolete
`tasks/list` and `tasks/result` methods remain unknown. See
[Durable Tasks](#durable-tasks) for the supported API and protocol boundary.

The extension-field locations remain distinct. JSON-RPC notification envelope
extension fields serialize at the top level; namespaced request metadata,
including the per-request client capability map, belongs inside `params._meta`.
An arbitrary extension field or an unsupported extension capability still does
not register a method or enable matching server behavior.

Soklet supports MCP argument Completion for declared prompt arguments
and literal registered resource templates at explicitly selected
`2025-06-18`, `2025-11-25`, and `2026-07-28` revisions. Completer revisions must
fit inside the owning prompt/template and endpoint revisions. It advertises
`completions` only where a completer is available in the selected revision.
On a Completion-enabled 2025 endpoint/revision, a visible registered target
and declared argument without an enabled completer return empty suggestions;
unknown or hidden targets and
undeclared arguments fail with invalid params. Each suggestion must be
authorized by the application callback; Completion neither invokes a
prompt/resource read handler nor makes client-supplied arguments trustworthy.
Enabling a completer requires a server-wide request limiter, which applies to
all admitted MCP methods, not just Completion. See
[Argument completion](README.md#argument-completion) for registrations,
annotations, limits, and the request-wide policy boundary.

Soklet does not provide stdio transport, public arbitrary JSON Schema
registration, MCP logging capability, or an application result-extension
registry. OAuth protected-
resource metadata and identity-provider behavior remain deployment
responsibilities; core Soklet does not implement DPoP-bound access tokens. A deployment claiming MCP Authorization must publish RFC
9728 protected-resource metadata with at least one authorization server through
the applicable challenge URL or well-known URI, and it must not require
`offline_access` as a protected-resource scope. Applications may implement
authentication and standards-compliant challenges at the admission boundary.
Doing so does not by itself make core Soklet or the deployment fully conformant
with MCP Authorization; the deployment must meet every applicable
authorization-server and resource-server obligation.

### Protocol scope and unsupported features

Soklet does not implement MCP Roots, Sampling, or Logging. Pass file or
directory information through explicit tool parameters, resource URIs, or
server configuration, and integrate directly with a model provider when
needed. Use application logging and Soklet's existing observability and
OpenTelemetry integrations.

Dynamic Client Registration is reviewed and not applicable because Soklet has
no OAuth/DCR implementation. The deprecated standalone legacy HTTP+SSE
transport is unsupported. Soklet's MCP SSE response streams belong to its
Streamable HTTP transport and do not enable that deprecated transport.
