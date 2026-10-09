# MCP client and host compatibility

This launch-facing matrix records what was actually exercised, with exact tool
versions and a manual-smoke date. It is not a candidate release gate and does
not create a release-validation PASS receipt.

## October 8 current-source protocol verification

The remediation build passes **45 strict modern scenarios**, the unchanged
reviewed `server-stateless` exception with independent Elicitation controls,
48 final-schema goldens and eight task-notification socket checks. Both 2025
revisions pass all **41 selected official combinations** and **15 native HTTP
contracts each**. The pinned TypeScript client **2.0.0** also negotiates modern
MCP, lists/calls the fixture tool and shuts down cleanly.

Established URI grant loss now retires the whole legacy session. The HTTP
supplement verifies neutral `404`, fresh initialization/resubscription and
restored delivery; refreshing a GET does not replace historical URI credentials.
Earlier dated observations below describe their original builds. This new run
does not qualify automatic recovery or OAuth UI behavior in a named host, or an
immutable release candidate. Raw output remains outside Git.

## October 2 expanded release-conformance integration

Candidate validation now requires the existing modern checks plus all **41
selected legacy official combinations** and **15 HTTP runtime contracts per
2025 revision**. Both runners must use the same exact candidate commit and
four artifact identities. The default legacy development runner also executes
the HTTP supplement. It covers URI/catalog delivery, independent GET/URI
credentials, renewal, revocation, gap reconnect, unsubscribe and DELETE; it
does not qualify host display or OAuth UI behavior.

The combined legacy run and independent receipt verification passed against
development JAR SHA-256
`78db80cf18605f00b7bd6a7ee213250ed056f649c9194443ee1ff4bd15264e32`,
with **40 receipt rejection cases**, runner controls, **54 targeted JDK 17
tests**, Javadoc and API compatibility/freezes passing. The private scheduler
holder changed from a record to a plain class; public signatures and dispatch
behavior are unchanged. Earlier JDK 21/25 and host observations below retain
their original artifact identities.

The checkout is dirty, and release mode correctly rejected it before executing
scenarios. The new legacy lifecycle scopes and imported fixtures have now been
reviewed: the audit covers 1,449 scopes across 222 JUnit files, with shared
request/read deadlines and composed outer guards. Its verifier and 138-case
adversarial self-test pass, as do 157 focused tests on each of JDK 17 and 25.
This is development evidence, not a release-candidate PASS. Downstream pins
are unchanged and raw output stays outside Git.

## October 2 delayed-fence correction and Bearer credential qualification

Two deterministic regressions reproduced a real GET/URI retirement defect.
The store fences authorization before invoking the deferred target callback.
A renewal can begin under that already-fenced generation before the callback
arrives; canceling whichever check is then current incorrectly retired that
fresh renewal as `AUTHORIZATION_FAILED`, before lease expiry. Cancellation now
targets only older-generation checks. Both regressions pass for both 2025
revisions and still verify resumed notification delivery. This changes no public
type or signature. The original lost-grant stress trace lacks identity, deadline
and retirement cause, so this establishes a repaired loss mechanism, not certain
attribution of that historical incident.

**332 focused JDK 17 tests**, **54 targeted tests each on JDK 21 and 25**, API
compatibility/freezes, Javadoc and website source-contract/link checks passed.
The rebuilt development JAR is SHA-256
`1863f35500193df8130e25f231e3716175c92922da14eed35c328b7b16ede4f4`.
All **41 official legacy scenarios** passed against that JAR: **86 SUCCESS**,
**3 expected INFO**, **192 schema-checked messages**, and **41 clean fixture
shutdowns**. Runner negative controls and public-only fixture compilation/dependency
checks passed. Toolchain: released Client/Core **2.2.0**, Node **26.5.0**,
npm **11.17.0**, Corretto **17.0.20.1**.

A separate **16.848-second** public-API fixture check sent actual disposable
Bearer credentials over HTTP through the unmodified released SDK for both exact
2025 revisions. It verified:

- same-session GET refresh from credential A to B and renewal using B;
- GET B did not refresh the exact URI grant's historical A credentials;
- duplicate template subscribe replaced its evidence with B; fresh exact
  resubscribe restored delivery after A revocation;
- both B URI grants renewed twice during a seven-second quiet window, retained
  B's application context and did not repeat a delivered URI hint;
- revoking B denied URI renewal and closed B's own GET on the wire; subsequent
  publications produced no notification;
- revoked reconnect returned `401` with an `invalid_token` Bearer challenge;
  a valid credential for the same owner could still DELETE with `204`.

The fixture stopped normally with no signal or truncated capture. This qualifies
the exercised HTTP credential/evidence behavior, not OAuth token issuance,
named-host token refresh UI, or an immutable release candidate.

One **30.389-second causal diagnostic** on the same JAR retained **32 URI grants**
across **8 clients**, **12,320 publications**, **5,890 notifications**, **4 GET
gaps** and **4 explicit reconnects**, with a **2-second effective lease cap**.
Observed retirements were **20 replacements**, **20 explicit unsubscribes** and
**32 session closes**, with no observed unexpected cause. Peaks were four
maintenance jobs and 60 dispatches in a sampled sliding second; every measured
retention ledger drained to zero after DELETE. It wrapped internal grant targets
to record causes while delegating their calls, so it is diagnostic evidence,
not an unmodified-runtime qualification or heap-reachability proof. It exited
normally. Raw fixtures, logs, wire captures and run data remain outside Git.

## October 2 maintenance deadline-priority correction

Due GET and URI renewals now share dispatch ordering by shortest remaining
authorization lease. Two regressions reproduced starvation with the old
scheduler: four blocked longer-lived URI renewals occupied every maintenance
slot ahead of a shorter GET or URI lease. Both pass with the correction on both
2025 revisions. Capacity, physical callback ownership, generation fences and
authorization deadlines remain enforced. No public API changed.

**329 focused JDK 17 tests**, **51 targeted tests each on JDK 21 and 25**,
API compatibility/freezes, Javadoc, and website source-contract/link checks passed.
The rebuilt development JAR is SHA-256
`96701989a681ead498b5340476706bdb79b0602607d1e06eea494468d77dc6ed`.
All **41 official legacy scenarios** passed against that same JAR, with **86
success observations**, **3 expected INFO observations**, **192 schema-checked
messages**, and **41 clean fixture shutdowns**. The runner negative controls
also passed.
Released Client/Core **2.2.0**, Node **26.5.0**, and Corretto **17.0.20.1** passed
both exact 2025 revisions: quiet URI renewals, policy-generation reconciliation,
automatic GET recovery, dirty-gap delivery, unsubscribe and DELETE `204`.
A **30.584-second** stress check with a **2-second effective lease cap** retained
all **32 URI grants** across **8 clients**, **12,320 publications**, **5,790
notifications**, **4 GET gaps** and **4 explicit reconnects**. Peaks stayed within
the existing **4-job / 64-dispatches-per-second** budget (four jobs, 61 dispatches
in a sampled sliding second). Every measured retention ledger drained to zero
after DELETE, and both disposable fixtures exited normally. The stress fixture
observed counters and deadlines through read-only reflection; it did not replace
grant targets or alter authorization decisions. Ledger counts do not establish
heap reachability or total application-object retention.

The separate **30.477-second** causal diagnostic on the earlier `3a9539f0` JAR
also passed. It wrapped internal grant targets solely to observe retirement
causes, so it is diagnostic evidence, not an unmodified runtime qualification.
Its retirements were replacement, explicit unsubscribe or session close.
Neither that diagnostic nor the passing corrected-artifact checks establish why
the original stress run lost a grant. That incident remains unresolved. These
checks do not qualify actual Bearer credential rotation, named-host behavior on
the new JAR, or an immutable release candidate. Raw data stays outside Git.

## October 2 fixed-artifact legacy notification qualification

The checks in this section used the corrected development JAR, SHA-256
`3a9539f03239966fe98ca0a2e0b194247a239a6e7cc2aa71601245c9f6adb7d7`.
The internal renewal/duplicate-subscribe correction preserves an already written
URI hint while invalidating queued frames under their old authorization
generation. It adds no public API. **302 focused JDK 17 tests**, **24 grant-store
tests on JDK 21 and 25**, and the API compatibility/freeze gates passed.

The official toolchain exercised **41 legacy scenarios**, recording **86
success observations**, **3 expected INFO observations**, **192 schema-checked
messages**, and **41 clean server stops**. Released TypeScript Client/Core
**2.2.0** additionally passed both exact 2025 revisions: automatic GET recovery,
current-policy reconciliation, and a **13-second quiet window spanning two URI
grant renewals without repeating a delivered hint**. Actual Bearer credential
rotation was not exercised.

| Unmodified released host | Exact revision | Observed behavior and limits |
| --- | --- | --- |
| Inspector **2.9.0**, Chrome **154.0.8037.97** | `2025-06-18` | Tool, prompt and resource lists refreshed automatically and visibly after their hints. Subscribe and resource-update delivery passed. The resource preview required its manual Refresh action. After a clean GET drop, the host automatically reconnected with the same session ID and received the dirty URI hint. Unsubscribe returned `200`; a subsequent publication produced no URI hint before the supervised Inspector stopped. Inspector expiry did not send DELETE, so this run does not qualify June host DELETE. |
| VS Code **1.139.1** | `2025-11-25` | The tools picker showed `catalog_after`; unsent prompt autocomplete showed `prompt_after`. A resource hint caused an automatic read, and a different-length update refreshed the visible editor. After a clean GET drop, the host automatically reconnected with the same session ID; its fresh read and visible editor showed the updated content. Stop Server sent DELETE and received `204`. This configuration requires an explicit static `MCP-Protocol-Version: 2025-11-25` header. |

Each named host received exactly **two URI hints** across the original
publication and the publication during its GET gap. Reconciliation of the
current policy generation did not repeat a delivered hint. The disposable
fixture exited normally without a termination signal. Host OAuth and actual
Bearer credential rotation were not exercised.

VS Code's installed MCP resource filesystem has a **3-second read cache** and
derives its ETag from a fixed modification time and content length. A same-size
update was read on the wire but did not change the visible editor; the
different-length control did. These are observations about this host build.
Its public configuration cannot express a dynamic negotiated version header
for a June-only endpoint, so native `2025-06-18` qualification remains open.
The host was not patched and no proxy rewrote its negotiation.

A normal stress check with a **4-second effective lease cap** ran **8 clients / 32 grants**, with
**12,320 publications**, **5,811 notifications**, **4 GET gaps**, and **4 explicit
reconnects**; every retention ledger returned to zero. An earlier **2-second**
stress check ended with **31 of 32** expected live URI grants. The cause of that
grant retirement remains unresolved and is not established by the passing normal
check. An instrumented **30.744-second** repeat at the 2-second cap retained
all **32 grants** before DELETE and drained every measured counter afterward;
its sampled disappearances matched explicit unsubscribe/resubscribe or DELETE
requests. The original missing grant persisted for **5.273 seconds**, but that
run did not record its identity/deadline or maintenance retirement outcome.
The repeat does not explain the original failure.

This is development evidence, not immutable candidate qualification. A future
candidate must rerun the **41 legacy scenarios and local supplements** against
its exact artifact. The chronological receipts below retain their original
artifacts and narrower scopes. Raw run data and screenshots remain outside Git.

## October 2 development legacy notification SDK check

Unmodified released TypeScript Client/Core **2.2.0**, Node **24.19.0**, and
Corretto **17.0.20.1** passed a bounded loopback check on both exact 2025
revisions. Automatic GET opening, URI subscribe/update/duplicate/unsubscribe
(including neutral unknown-unsubscribe `result: {}`), current GET/URI renewal,
and DELETE `204` passed. Tool/prompt/dynamic-resource invalidations coalesced
until freshly admitted lists rearmed them. An intentional GET gap followed by
explicit same-session transport reconnection delivered a newly synthesized dirty
URI hint. Clean replacement GET captures contained only legacy notification
fields and no SSE `id:` or `retry:`. The intentionally aborted first GET was
observed through SDK handlers, not asserted as a complete captured body.

This development receipt used JAR SHA-256
`b20cfdd49fc824d060ae2eec0fdd99f19185397730734e022369b7c9a176e182`.
It does not establish named-host display/refresh, automatic recovery, replay,
refreshed-credential host behavior, or immutable candidate/release qualification.
Raw disposable evidence remains outside this repository.

## October 2 development GET/DELETE SDK check

Released TypeScript Client/Core **2.2.0**, Node **24.19.0**, and Corretto
**17.0.20.1** passed a bounded loopback check on both exact 2025 revisions.
The SDK automatically opened GET with the negotiated version/session headers;
fresh renewal and ordinary RPC use alongside GET passed. `terminateSession()`
sent DELETE, received empty `204`, cleared its stored ID, and observed clean
zero-message GET EOF. Renewal denial closed only GET while ordinary RPC remained
usable. Plain initial `403` carried no implicit `WWW-Authenticate` challenge and
surfaced the expected SDK stream-open failure. Explicit fresh Client/Transport
recreation obtained a new session ID and GET `200`.

This development receipt used JAR SHA-256
`7a2558ef06ff6a35861a29f83c192c3aac66fa35512c15b19a73c8ff7447c48b`.
The check was repeated against this final slice build after the internal
simulator/suppressed-callback cleanup fixes. Automatic recovery, URI/catalog notification
payload delivery, named-host/UI behavior, OAuth flow, and immutable candidate or
release qualification were not exercised. The earlier GET `405` receipt below
retains its own artifact and minimum-session scope.

## October 1 development minimum-session SDK check

Released TypeScript Client and Core **2.2.0**, Node **24.19.0** and JDK **17**
passed a bounded disposable loopback check through both exact 2025 revisions.
The SDK automatically retained the initialization session ID, acknowledged
initialization, and sent later POSTs with the negotiated session/version headers.
The server preserved remembered public client metadata while exposing each
request's current metadata separately. Explicit client cancellation won active
finite and progress calls: finite cancellation returned an empty SSE response,
and progress cancellation retained the previously emitted update without a
terminal result; the application token reported `CLIENT_CANCELED`.

The SDK leaves its canceled tool-call promise pending after sending the
notification alone; the probe bounded it with a **1,400 ms** local timer. It
does not automatically reinitialize after a neutral `404`. Explicit fresh
client recreation passed; automatic recovery remains unqualified. `GET`
returned `405`, as expected before the delivery slice. These are recorded SDK
limitations, not a named-host or immutable-release qualification.

The tested working-tree JAR SHA-256 was
`42dcf567830c9abcdfafaa9f17dd261d9c9ea4b9ac2eb6f7f7d303d51bebbf58`.
Later internal constructor-cleanup and stale-handle retirement corrections
are covered by Java regression evidence separately; the SDK receipt is not
silently assigned to their artifact. Raw evidence remains outside Git.

## October 1 development static catalog pagination check

Released `@modelcontextprotocol/client` **2.2.0** with
`@modelcontextprotocol/core` **2.2.0**, Node **24.18.0** and JDK **17** passed
a disposable loopback HTTP check for each exact `2025-06-18` and `2025-11-25`
revision. The SDK's normal no-cursor list methods automatically collected
**16 tools / 15 prompts / 17 resources / 15 resource templates** over
**8 / 8 / 9 / 8 pages**, respectively. Current-caller tool/prompt filtering,
Portuguese (`pt-BR`) page localization, negotiated protocol headers, final
cursor exhaustion, no missing/duplicate entries and clean shutdown passed.

The working-tree JAR SHA-256 was
`1d909c85c0866aa872be845804fc0083e9017c01a4b0021bc0e494cae0d43b97`.
SDK client/core entry-point SHA-256 values were
`b5891864a6ebcef27d8d999d662d03d7095368a2c0ff072ac578c2f31b27afa4` /
`dcf5e4173148f276be335db8a85fadf417a8728634be5ec3ef5159bd18a6ab8f`;
their source commits were not verified. Raw logs and fixture output remain
outside Git. This is a released-SDK development supplement, not named-host,
official-conformance or immutable-candidate qualification. Codex CLI and
VS Code pagination remain unqualified. This pagination check did not exercise
sessions or notification delivery; the separate minimum-session check above
records its narrower evidence.

## October 1 development completion and POST progress expansion

Current development source implements `completion/complete` for explicitly
selected `2025-06-18` and `2025-11-25` prompt arguments and resource-template
variables. The existing annotated and programmatic completers use the same
handlers and request-wide policy, with capability advertisement and routing
filtered by exact revision. On a Completion-enabled legacy view, a visible
registered target and declared argument without an enabled completer return
empty suggestions.

Selected 2025 operations also use the existing progress reporter when the
request supplies a valid token and SSE is safe. The first update commits POST
SSE; no update returns JSON. Progress and one whole terminal result/error use
the selected legacy projection. A committed legacy SSE disconnect or lost-writer
write failure detaches delivery without itself canceling the handler; deadlines and physical worker
ownership remain. Finite/uncommitted and queued legacy disconnects still cancel.
Stateless tokens are POST-local. Streams intentionally omit November's
recommended empty priming event and event IDs because they are persistent and
nonresumable, with no polling/replay or lost-POST-result recovery.

Inspector **2.9.0** in isolated Chrome **154.0.8037.59** passed a scoped local
POST-progress check for each exact 2025 revision on October 1. Its visible
"Tool progress" notification showed `12.5 / 100 (13%)` while the handler was
held and before any terminal result. Releasing the handler produced the whole
final result. A token-bearing control with no progress completed normally in
the UI and returned HTTP `200` / `application/json` in the direct wire check.

This used a working-tree build of `soklet-4.0.0.jar`, SHA-256
`38b2523a93401a703642a155b5c0fe1d60cfb1142eeec9e7e3ba8f14cd3ffd9a`.
Raw development-run evidence remains outside source control. This display
check does not qualify host cancellation, Completion, November priming or an
immutable release candidate; no new official scenario PASS is claimed.
A bounded Claude Code **2.1.274** terminal check invoked neither fixture tool,
so its progress presentation remains untested; that attempt does not establish
a Soklet progress failure.
The historical results below retain their original source identities and
feature limits. The owner selected the complete
[2025 expansion](MCP_LEGACY_EXPANSION_PLAN_2026-10-01.md) for 4.0.0.
Current source also implements framework pagination for the four static 2025
catalogs, with fresh admission/current catalog policy, page-local localization,
and independently bounded navigation cursors. This does not add a named-host
pagination PASS or immutable candidate qualification. Sessions/remembered
metadata and active-request cancellation are implemented behind explicit
endpoint/session-owner configuration. Their host behavior, expiry/recovery, and
operational defaults remain pending qualification; earlier stateless receipts
provide no session PASS. Current source additionally implements explicit HTTP
admission for leased GET opening/keepalives and verified DELETE retirement.
Current source additionally implements session-owned URI grants and bounded
resource/catalog invalidations over freshly authorized GET streams. This is not
a named-host refresh, credential renewal, or reconnect PASS. GET/DELETE host
behavior, renewal/reconciliation, and exact-candidate
qualification are pending; the earlier minimum-session receipt's GET `405`
remains evidence only for its recorded artifact.

## September 30 Claude Apps security and localization check

Claude's unchanged web custom connector and cloud backend passed these scoped
real-App checks using exact revision `2026-07-28`:

- Current-caller tenant/language refresh, and clearing protected content after
  permission denial or credential revocation.
- Empty CSP origin exclusions; declared fetch/image/script allowance;
  undeclared-origin blocking; effective browser permission-policy delegation.
- Arabic RTL rendering, same-credential English/LTR refresh, and clearing the
  view after an actual host locale change. Unchanged full context and a
  dimensions-only notification preserved the ready view.
- An actual English-to-Portuguese host locale change while refresh was pending
  invalidated the App and cleared all seven protected fields. A completed
  server HTTP 200 reply was held by the transport proxy, then released late;
  the view remained invalidated and empty. This verifies cancellation and
  suppression of stale content in this host.

**Host restrictions:** declared nested frames and cross-origin base URLs were
blocked. Those positive capabilities remain unqualified. Permission checks did
not invoke device APIs or request browser grants. Revocation challenged discovery
with 401; no production OAuth recovery was exercised.

The JAR was built from pushed core commit
`3fe09465fd1241ffb785493fdff4a2074f8b0e0a`, with unchanged production sources
and POM in reviewed checkout `4c57cbae55fe306dde57dc2f6564e2f8f3c06241`.
Web/backend build versions were not exposed. The first locale-check attempt
expired and failed cleanup; the repeat completed cleanly. English was restored,
connectors disconnected, and owned processes/listeners confirmed stopped.
Raw development-run evidence is stored outside source control.

The pending-refresh check retained the existing 10-second bridge deadline.
Its late HTTP reply was released after 20 seconds, following cancellation;
no successful post-invalidation App bridge delivery is claimed. An earlier
attempt changed the language in another tab and reached the bridge timeout
without notifying the App. A same-tab attempt canceled before a request reached
the server. Those attempts do not establish the delayed-reply result above.

### Release-scope review

| Remaining item | Owner and disposition |
| --- | --- |
| Time-zone change | The example shell handles host notifications; its mock test passes. Claude's settings search exposed no time-zone control. The owner elected to leave the real-host change untested. |
| Already displayed content | Applications own expiry and idle-view invalidation. Admission rechecks the next request; it cannot retract results already delivered or automatically revoke an in-flight admission. |
| OAuth | Soklet validates Bearer challenge construction and carries safe admission responses. Applications own token verification, protected-resource metadata and the provider; this fixture does not qualify a production OAuth flow. |
| Frames, base URLs and device permissions | Soklet validates declarations; hosts enforce browser policy and may restrict valid requests. Claude blocked the named frame/base capabilities; no device grant or device API was exercised. |

No runtime or Java API change was required by this review. The public guide and
website now state these ownership boundaries and make example invalidation
conditional on an actual host notification. The aggregate API-freeze gate and
260 focused Java regressions passed across Apps, challenge/notification transport,
subscription admission and complete-result sanitization. All 27 existing
shell/runner tests passed.
Run output remains outside Git.

These dispositions close the framework-boundary review. They do not create a
full-host or immutable-candidate PASS. Production OAuth, real host time-zone
changes, subscriptions, legacy Apps and other host builds remain unqualified.
Earlier reports retain their original scopes.

## September 30 Apps server security recheck

The same pushed core artifact as the Claude Apps check below passed **13
simulator scenarios / 35 requests**, **13 live HTTP authorization requests**,
and **27 shell/runner tests**. These checked independent UI resource
authorization, denied/changed/revoked callers, current caller language and
tenant, sanitizer privacy, capability gating, and plain subscription-policy
403 without an OAuth challenge. The live listener stopped cleanly. See the
[server security review and retained receipts](mcp-apps-security-qualification-2026-09-30/REVIEW.md).

This is server and mocked-shell evidence. In that execution, Claude's UI checks
were **NOT RUN** because native UI automation timed out before any host fixture
service started. The later web-host checks above supply separate tenant-change
and denial/revocation evidence. Full host security, CSP/permission enforcement,
and production OAuth remain unqualified. The September 29 render/refresh result
retains its original scope.

## September 29 modern Apps cloud check

The existing public-API Apps fixture ran against clean pushed core commit
`3fe09465fd1241ffb785493fdff4a2074f8b0e0a`, using the same exact JAR as the
prompt/resource check below. Its temporary HTTPS endpoint served only
`2026-07-28`. See the [Apps cloud review and retained wire/UI evidence](mcp-apps-cloud-qualification-2026-09-29/REVIEW.md).

| Client or host | Exact version | Observed operations | Status |
| --- | --- | --- | --- |
| Claude Desktop custom connector and Claude cloud backend | Desktop `2.16120.0`; backend version not exposed; unchanged host | Selected modern `2026-07-28` through discovery; current Apps capabilities accompanied later requests. Catalogs and one UI resource read succeeded. A model-driven `show_catalog` rendered the server-provided App; clicking its own Refresh catalog button sent a successful app-only `refresh_catalog` and rendered the view again. | **PASS (bounded modern cloud Apps render/refresh smoke)** |

This is direct evidence of modern MCP and Apps working in this named cloud
host. The fixture retained its authorization and sanitizer policy. The temporary
tunnel stopped and the connector was disconnected. This smoke does not qualify
production OAuth, host denial/revocation, CSP or permission enforcement,
localization transitions, subscriptions, legacy Apps, or full host compatibility.
The earlier Inspector failure and candidate gate requirements remain unchanged.

## September 29 prompt and resource development check

The expanded public-API fixture used a clean export of pushed core commit
`3fe09465fd1241ffb785493fdff4a2074f8b0e0a`. Its JAR SHA-256 was
`c454eac7ff5fd58d564a2c1603133ed1c47a01210d2910be4d60f392ebe1184f`.
Each fixture served one exact 2025 revision. See the
[qualification review and raw receipts](mcp-legacy-qualification-2026-09-29/REVIEW.md).

| Client or host | Exact version | Observed operations | Status |
| --- | --- | --- | --- |
| MCP Inspector CLI | `2.3.0`, unmodified cached build | Both `2025-06-18` and `2025-11-25`: tools list/call; prompt list/simple/arguments/embedded-resource/image; resource and template lists; text/blob/template reads; expected unknown-resource error. Unicode arguments and template values passed. | **PASS (26 bounded development client cases; no model session)** |
| Claude Code | `2.1.274` | Both exact 2025 revisions: actual model-driven static/template resource reads and a prompt command with Unicode arguments returned the expected content. Four model sessions and six retrievals completed; fixtures stopped cleanly. | **PASS (bounded local development prompt/resource smoke)** |
| Claude Desktop custom connector and Claude cloud backend | Desktop `2.16120.0`; backend version not exposed | `2025-11-25`: cloud picker fetched a parameterized prompt with Unicode arguments and read the static text resource; Claude quoted both returned attachments exactly. A model-driven control tool call also passed. Modern discovery fell back to November initialization. | **PASS (bounded cloud development text prompt/resource smoke)** |

The pinned official alpha.11 suite separately passed 14 selected scenarios per
revision: 57 `SUCCESS`, two optional-session `INFO`, and zero failure, warning,
or skipped checks across 136 schema-validated messages. This is a bounded
protocol subset, not full 2025 conformance or a host PASS. Unknown-resource
reads returned HTTP 400 with JSON-RPC `-32002`; Inspector reported the HTTP
error and did not attempt OAuth. The earlier tool rows below retain their
original source identities and do not imply prompt/resource host coverage.

The first isolated Claude Code attempt failed model authentication; its raw
failed receipt remains preserved. Retrying with the normal authenticated
client environment passed. The isolated failure did not establish that the
user's account was logged out. Prompt wire captures confirm fallback from
modern discovery to the exact 2025 initialization, expected GET 405, and
successful `prompts/get`. These local MCP sessions remain separate from the
cloud connector row. The cloud run used the same exact core and fixture bytes
through a temporary HTTPS tunnel. Its 17 captured exchanges include one
`prompts/get`, two reads of the same static text resource, and one control
`tools/call`. Prompt and resource selection was user-driven; Claude consumed
the retrieved attachments. The fixture and tunnel stopped and the temporary
connector was disconnected. June cloud behavior, cloud templates, binary/image
consumption, OAuth/denial, GET/SSE, and Apps were not exercised. No full host
compatibility or candidate release PASS is claimed.

## September 28 real-host development check

On **2026-09-28**, a disposable, loopback-bound fixture built with Soklet from
exact commit `fd5e0d479216aeda7565a379f27bd32fa79604cc` exposed one
`interop.echo` tool. The locally built Soklet JAR had SHA-256
`e09bb1edf4dfccc99c6ff481183ba2694c8a20e68aa944cb7d0db57e3a546216`.
The dual-era URL explicitly served `2026-07-28`, `2025-06-18`, and
`2025-11-25`; separate fixture URLs restricted selection to both 2025
revisions or to one exact 2025 revision. Each passing local row below includes an
actual model-driven tool invocation and its returned echo value.

| Client or host | Exact version | URL revisions and observed negotiation | Observed operation and result | Status |
| --- | --- | --- | --- | --- |
| Codex CLI | `0.155.0-alpha.16.4` | Dual-era URL; offered and selected `2025-06-18` through `initialize`. | `tools/list` exposed `interop.echo`; `tools/call` returned `soklet-echo:codex-dual-approved` with the `2025-06-18` protocol header. | **PASS (local development tool smoke)** |
| Codex CLI | `0.155.0-alpha.16.4` | `2025-11-25`-only URL; offered `2025-06-18`, then selected the server's `2025-11-25` response. | `tools/list` exposed `interop.echo`; `tools/call` returned `soklet-echo:codex-november-20260928` with the `2025-11-25` protocol header. | **PASS (local development tool smoke)** |
| Claude Code | `2.1.274` | 2025-only URL; probed `server/discover` for `2026-07-28`, then fell back to `initialize` and selected `2025-11-25`. | `tools/list` exposed `interop.echo`; `tools/call` returned `soklet-echo:claude-legacy-default`. | **PASS (local development tool smoke)** |
| Claude Code | `2.1.274` | Dual-era URL; selected `2026-07-28` through `server/discover`. | `tools/list` exposed `interop.echo`; `tools/call` returned `soklet-echo:claude-dual-default`. | **PASS (local development tool smoke)** |
| Visual Studio Code / Copilot | VS Code `1.139.1`, bundled Copilot `0.67.0` | Installed locally; no authenticated Copilot model session or tool invocation completed. | None. | **NOT TESTED** |

These observations establish that current initialization clients can use an
endpoint **when the application explicitly declares the matching 2025
revision**, while Claude Code can also choose `2026-07-28` on a dual-era URL.
They do not establish support for every 2025 operation or every host. The
fixture exercised synchronous `tools/list` and `tools/call`, not denial,
OAuth recovery, disconnect behavior, prompts, resources, Apps, Skills, Tasks,
subscriptions, or SSE streaming. Other host rows are still pending. This was a
local development build from a known commit, not an immutable release
candidate or public Maven artifact; the release gate must repeat relevant
checks against the exact candidate.

### Claude Desktop custom connector cloud check

The cloud test used a **different, uncommitted working-tree build at test time**
with validated hybrid 2025 header handling. Its Soklet JAR SHA-256 was
`64253678715421002d70ec2ea049650429a7af5ec31700cf03e6f5e1d784a727`.
A temporary token-gated HTTPS tunnel forwarded the client's MCP headers and
payloads unchanged to the disposable 2025-only fixture. The temporary URL and
token are intentionally omitted from this record.

| Client or host | Exact version/state | Observed negotiation and operation | Status |
| --- | --- | --- | --- |
| Claude Desktop custom connector and Claude cloud backend | Desktop `2.9939.2`; cloud backend version not exposed | `server/discover` probing `2026-07-28` received the expected unsupported HTTP 400; fallback `initialize` selected `2025-11-25`; `notifications/initialized` received HTTP 202; `tools/list` received HTTP 200 and exposed `interop.echo`; a model-driven `tools/call` received HTTP 200 and returned `soklet-echo:claude-cloud-unmodified-20260928`. | **PASS (cloud development tool smoke)** |

This demonstrates one cloud-hosted connector reaching the stateless 2025 tool
path through an HTTPS endpoint. It does not qualify cloud GET SSE, denial,
OAuth recovery, disconnect behavior, or any non-tool operation. On 2026-09-29,
an isolated export of committed source `98092d27ce2b5bcc64d69b5416261002081e3155`
produced a Soklet JAR with the same SHA-256 as the cloud-tested build. This
ties the observed behavior to reproducible committed bytes, but neither the
temporary tunnel nor that build is an immutable release candidate or public
artifact. Candidate qualification must repeat the applicable checks on the
owner's exact release commits.

The focused legacy-wire and adjacent runtime regression batch passed **44/44**
tests on both JDK 17 and JDK 26. This verifies the observed cloud fallback and
malformed mixed-framing cases, but does not replace the full release test suite.

## September 1 baseline matrix

Manual smoke date: **2026-09-01**

Server target: Soklet 4.0.0, exact MCP profile `2026-07-28`, Streamable HTTP
`POST`, endpoint `http://127.0.0.1:8081/catalog/mcp`.

| Client or host | Exact version/state | Transport/profile result | Status |
| --- | --- | --- | --- |
| MCP Inspector CLI | `@modelcontextprotocol/inspector` 2.3.0 | Modern HTTP with `protocolEra: "modern"`; `tools/list`, `tools/call`, `prompts/list`, and `resources/list` completed against a local pre-release source build. | **PASS (pre-release manual smoke)** |
| curl | 8.7.1 | Raw HTTP `server/discover` returned HTTP 200 and advertised exactly `2026-07-28`. | **PASS (pre-release manual smoke)** |
| Visual Studio Code | 1.135.0, commit `08d4889f9ec4a1685d257b9b95de036c8e1ce1e5`, arm64 | Installed locally; no MCP model/extension session was available, so no discovery or invocation was run. | **NOT TESTED** |
| Claude Code | Not installed; no version asserted | No connection was attempted. | **NOT TESTED** |
| Cursor | Not installed; no version asserted | No connection was attempted. | **NOT TESTED** |
| A client fixed to Soklet 3.5.1's initialization/session/GET-SSE contract | Legacy profile, independent of product version | The September 1 modern-only endpoint could not serve that contract. The September 28 checks above establish only the named, stateless 2025 tool paths. | **INCOMPATIBLE WITH SEPTEMBER 1 ENDPOINT** |

None of these host rows exercised the
[`io.modelcontextprotocol/tasks` extension](../MCP.md#durable-tasks). Soklet's
server implementation and protocol conformance coverage do not imply that a
listed host version negotiates Tasks. A future manual Tasks row must record the
exact client version and exercise capability negotiation, task creation,
`tasks/get`, input or cancelation where supported, reconnect recovery, and
optional `notifications/tasks` independently of the core smoke above.

## September 13 packaged-development recheck

On **2026-09-13**, the generated website quickstart consumer was executed with
only its consumer JAR and the corrected Soklet 4.0.0 main JAR at runtime. Core
SHA-256 was
`b192e36e7d92d319a1f60fa2cd7d6ad6ca2bd8481bd5f46d530a9721b41a0e50`.
The listener was `http://127.0.0.1:8082/catalog/mcp`, using the same modern
profile and loopback-only smoke policy described below.

| Client or host | Exact version/state | September 13 observation |
| --- | --- | --- |
| MCP Inspector CLI | Cached, installed `@modelcontextprotocol/inspector` 2.3.0; Node 26.5.0 | **PASS (packaged development smoke)**: `tools/list` exposed `catalog.search`; `tools/call` with query `sprocket` returned the fixture's typed `sprocket:10` result. No dependency install or credentials were used. |
| curl | 8.7.1 | **PASS (packaged development smoke)**: `server/discover` and complete `tools/call` with matching `Mcp-Name`, metadata, and arguments. |
| Visual Studio Code | 1.137.0, commit `645f29cc3176500b4b5762ba887cf2a7f0ffdf2c`, arm64 | Version rechecked; no model/extension MCP session was exercised. **NOT TESTED**. |
| Claude Code / Cursor | Neither CLI found on PATH in this environment | No integration run or version asserted. **NOT TESTED**. A separately installed Claude desktop application does not establish Claude Code availability. |

The server ran on Corretto 17 and was stopped after the smoke. Inspector prompt
and resource catalog checks from the September 1 row were not repeated here;
neither run establishes host-level Tasks support. The local working record is
`/private/tmp/soklet-inspector-smoke.SHBwj5/result.json` with adjacent per-command
logs. This is not immutable-candidate evidence or a durable release receipt;
repeat on the canonical artifact and retain final results through K/L.

## Tasks protocol conformance

On 2026-09-09, official MCP conformance CLI `0.2.0-alpha.11` at commit
`a983ba93c91e0bb31d0b6849eeb52f0ad1083107` exercised Soklet through the
public-API-only fixture. All nine runnable Tasks scenarios passed, totaling
**44/44 successful checks** across capability negotiation, lifecycle, wire
fields, removed request state, task input, routing headers, dispatch and
envelopes, required-task errors, and multi-round-input composition. Every
fixture process shut down cleanly.

The suite's `tasks-status-notifications` scenario reported its one check as
**SKIPPED** because the upstream runner does not yet open and observe a
`subscriptions/listen` task stream. Soklet's own production-path tests cover
task subscription authorization, fresh manager lookup, event ordering,
backpressure, reconnect, and terminal-state races. This is a local pre-release
protocol check, not a compatibility result for any client or host in the table
and not a release-candidate gate result.

On 2026-09-13 the normal conformance gate was repinned to that exact alpha.11
commit. All 49 selected profiles (the prior 39 core rows plus ten Tasks rows)
were re-observed against the packaged working-tree JAR. The nine runnable Tasks
scenarios again produced 44 successful checks; the notification row retains its
exact declared upstream skip and independent socket-test supplements. The gate
now replays those profiles, but this development verification does not replace
candidate provenance, client-host smokes, or the unresolved
[external-toolchain risk disposition](../conformance/official/UPSTREAM_DEPENDENCY_REVIEW_2026-09-13.md).

“PASS (pre-release manual smoke)” means only that the named local interaction
worked on the stated date. It does not mean every feature of that host was
tested, a live language model was involved, or the eventual published artifact
was exercised. The September 28 rows explicitly used model-driven calls but
carry the same development-evidence limitation. Before publishing, repeat the
relevant smoke against the exact candidate JAR; after Central synchronization,
repeat it from a clean directory against the public
`com.soklet:soklet:4.0.0` coordinate.

Test environment: macOS 26.6.2 (build 25G83) on arm64, Amazon Corretto
26.0.1+8-FR, Node.js 26.5.0, and npm 11.17.0. These are the manual client's
environment, not Soklet's supported or release-pinned toolchain statement.

## Server used for the smoke

The manual smoke used the annotated `catalog.search` endpoint from the
[copy/paste quickstart](../MCP_QUICKSTART.md), bound only to
`127.0.0.1:8081`. Its tool call with `{"query":"sprocket"}` returned a typed
structured result containing `"Match for sprocket"`. The prompt and resource
list checks used additional test-only declarations on the same endpoint.

Anonymous admission, a node-local in-memory tool limiter, reject-all Origin
policy, and a localhost Host allowlist were intentional for this loopback
smoke. They are not production authentication, distributed rate limiting, or
browser CORS policy.

## Reproduce the Inspector smoke

Start the quickstart application, then save this exact configuration as
`inspector.json`:

```json
{
  "mcpServers": {
    "soklet": {
      "type": "http",
      "url": "http://127.0.0.1:8081/catalog/mcp",
      "protocolEra": "modern"
    }
  }
}
```

Run:

```sh
npx --yes @modelcontextprotocol/inspector@2.3.0 --cli \
  --config ./inspector.json --server soklet \
  --method tools/list --format json

npx --yes @modelcontextprotocol/inspector@2.3.0 --cli \
  --config ./inspector.json --server soklet \
  --method tools/call --tool-name catalog.search \
  --tool-args-json '{"query":"sprocket"}' --format json
```

Expected observations:

- `tools/list` contains exactly the generated `catalog.search` definition for
  this endpoint and its Java-derived input/output schemas;
- `tools/call` completes and returns the typed structured result;
- no initialization call or session ID is required; and
- stopping the application completes and releases port 8081.

MCP Inspector documentation and releases are maintained by the MCP project:
[Inspector documentation](https://modelcontextprotocol.io/docs/tools/inspector)
and [Inspector releases](https://github.com/modelcontextprotocol/inspector/releases).

## Raw localhost HTTP recipe

This request checks the modern discovery boundary without relying on a host:

```sh
curl --fail-with-body --silent --show-error \
  --request POST http://127.0.0.1:8081/catalog/mcp \
  --header 'Host: 127.0.0.1:8081' \
  --header 'Content-Type: application/json' \
  --header 'Accept: application/json, text/event-stream' \
  --header 'MCP-Protocol-Version: 2026-07-28' \
  --header 'Mcp-Method: server/discover' \
  --data '{"jsonrpc":"2.0","id":1,"method":"server/discover","params":{"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{}}}}'
```

To invoke the quickstart tool, mirror its name in `Mcp-Name` as well:

```sh
curl --fail-with-body --silent --show-error \
  --request POST http://127.0.0.1:8081/catalog/mcp \
  --header 'Host: 127.0.0.1:8081' \
  --header 'Content-Type: application/json' \
  --header 'Accept: application/json, text/event-stream' \
  --header 'MCP-Protocol-Version: 2026-07-28' \
  --header 'Mcp-Method: tools/call' \
  --header 'Mcp-Name: catalog.search' \
  --data '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"catalog.search","arguments":{"query":"sprocket"},"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{}}}}'
```

The name header is invocation-specific: depending on the selected method it
mirrors the tool/prompt name, resource URI, or task ID. Discovery and other
methods with no such selector must omit `Mcp-Name`; an unexpected name header
is rejected as a header mismatch.

The `Host` value includes the port because Soklet validates both host and
effective listener port. For local development, use `127.0.0.1` consistently
rather than mixing it with `localhost` unless both names are explicitly
allowed. Browser-based clients also need a deliberate Origin policy.

Expected success is HTTP 200, `Cache-Control: no-store`, and a JSON-RPC result
whose supported version is exactly `2026-07-28`. A `GET` or `DELETE` request is
expected to return 405; that is the modern stateless contract, not a failed
legacy session setup.

## Mainstream host setup notes

The untested rows above are not implied compatible. When testing a host, pin
and record its exact version, use its HTTP/Streamable HTTP server form, and
point it at the application's configured endpoint URL. A client that uses
`initialize` may connect only if the endpoint explicitly declares a supported
2025 revision; the 2026-only configuration used for the September 1 Inspector
smoke does not accept that wire protocol. The implemented 2025 adapter for
synchronous tools, ordinary prompts/resources, and argument completion defaults
to stateless operation. Explicit 2025 session selection also requires server
ownership/bounds; it does not restore the 3.5.1 Java session API. Explicit
legacy subscription selection and a session transport admission controller
enable GET SSE; the removed standalone HTTP+SSE transport remains unavailable. Do not select an
stdio command or a deprecated transport when testing the HTTP endpoint.

- Visual Studio Code documents workspace/user MCP configuration in
  [Use MCP servers in VS Code](https://code.visualstudio.com/docs/copilot/chat/mcp-servers).
- Claude Code documents HTTP server configuration in
  [Connect Claude Code to tools via MCP](https://docs.anthropic.com/en/docs/claude-code/mcp).
- Cursor documents its host configuration in
  [Model Context Protocol](https://docs.cursor.com/context/model-context-protocol).

For every new host/version, record discovery, list, one invocation, expected
failure behavior, clean disconnect, and server shutdown/port release. Keep an
untested or incompatible result in the table instead of converting product
documentation into an unsupported compatibility claim.
