# Candidate-backed Apps fixture

This separate public-API fixture exercises Soklet's server-side Apps contracts
against a packaged candidate JAR. It also supplies a self-contained UI shell
using the pinned official Apps SDK. The separate `run-host.mjs` profile drives
actual Inspector rendering and its refresh bridge. Neither profile is an official
core-conformance scenario or a production authentication example. Ordinary-tools
Inspector profiles are qualified separately.

## Scope

The fixture contains one `show_catalog` tool visible to model and app, linked to
the exact `ui://soklet/catalog-v1` resource. `refresh_catalog` is app-only and has
no resource link, avoiding recursive view creation. Both accept only `{}`.
Per-request bearer admission snapshots an application-owned immutable caller;
client metadata, UI arguments, host locale, and `Accept-Language` cannot change
the selected tenant or language. The eight-entry credential table is disposable
test infrastructure, not JWT/OAuth verification or a production credential store.

The custom resource list filters denied callers. The exact resource handler
also authorizes every read independently, including prefetch before a tool call.
Hidden tool calls match unknown-tool errors. Resource denial uses its own fixed
application error; this fixture does not claim indistinguishable resource
existence errors. Missing/revoked credentials fail admission. Changes affect
future admissions, not requests already admitted; there is no promise of
mid-flight revocation or a push notification to an already rendered UI.

The static shell is identical across principals, locales, and tenants, contains
no credentials or personalized data, and needs no browser-side CDN/package
fetches. Tools supply English, Portuguese, or Arabic structured data and a
separately authored localized text summary. The complete-result sanitizer
replaces raw text and metadata, removing a fixed synthetic private canary from
both. Invalid tool arguments produce a sanitized text-only tool error with no
structured catalog data. Harmless `example/view` metadata stays metadata, never fallback text.
Currency and time zone are explicit application data (`USD`/`UTC`), not inferred
from locale. The catalog localizer uses the same admitted caller locale.

The renderer sets `lang`/`dir`, uses logical CSS and `Intl` formatting, and writes
translations only through `textContent`. A deliberately hostile-looking item
label tests literal display, not HTML execution. Refresh calls the official SDK's
`callServerTool` with no identity overrides and clears old data before awaiting
the response. Failure, timeout, cancellation, or a host language/time-zone
change clears the view and requires reopening it. Theme/dimension-only changes
do not invalidate it. Late results cannot repopulate an invalidated view.

All MCP responses are checked for `Cache-Control: no-store`. Resource metadata
declares empty CSP domain allowlists and requests no browser permissions. These
are host-facing declarations, **not evidence that a browser enforces them**.
No Tasks, admitted subscriptions, external links, storage, or application network
calls are used. Catalog localization's subscription mechanism is explicitly
denied; Inspector's automatic catalog-change subscription attempt is checked as
an exact policy denial, not accepted as a stream or ignored.

## Build the shell

Push and pull-request CI first compiles all fixture and contract-test sources
against the packaged JAR with `--release 21`, then runs the Simulator and
loopback HTTP authorization contracts on each supported CI JDK:

```sh
node verification/interoperability/apps/ci-contracts.mjs \
  target/soklet-4.0.0.jar /absolute/path/to/jdk/bin/java
```

This fast check supplies an inert HTML shell and needs no SDK installation.
It catches public-API and server-result drift; it does not qualify browser
rendering or replace the candidate-backed shell and host checks below.

Use Node 26.5.0/npm 11.17.0 and the exact existing
[Inspector lock and dependency review](../inspector/dependency-review.md).
In a fresh temporary directory copy `../inspector/package.json` and
`../inspector/package-lock.json`, then install using
`npm ci --ignore-scripts --no-audit --no-fund` with an isolated npm cache/config.
Do not run upstream lifecycle scripts or regenerate the lock. This brings in
the already pinned Apps SDK 2.0.0 and Rolldown 1.2.9; it does not start Inspector,
open a browser, use an account, or change any user host configuration.

```sh
node verification/interoperability/apps/build-shell.mjs \
  --dependencies /absolute/path/to/locked-install \
  --output /absolute/path/to/new/catalog.html
```

The build requires a new output file and emits `catalog.html.receipt.json`.
Its supervised child has a 60-second bound. The standalone HTML has one inline
bundle, no runtime imports, and a 512-KiB ceiling; script-ending sentinels are
escaped and the resulting script syntax is checked. Because the pinned SDK
logs whole bridge messages, the reviewed minification step removes console
calls and rejects any remaining console call sites. This is an explicit build
transformation, not unchanged SDK bytes. Bundled-package license notices are
retained. Source and dependency snapshots are taken before bundling and checked
again before writing; a concurrent edit fails the build. The receipt binds
dependency pins, source/module/package identities,
the transformation, and final output bytes. No generated bundle belongs in the
source directory: retain it with a run's evidence outside this tree.

## Run server contracts against the candidate

Use the candidate's original POM, whose bytes must match the POM embedded in
the JAR; a temporary POM with a local Maven coordinate is not interchangeable.
The Java path must point to a real JDK's executable (with sibling `javac` and
`jdeps`), not the macOS system launcher. The work directory must be new and
outside this source directory. The runner requires the shell's build receipt
to match the current shell sources.

```sh
node verification/interoperability/apps/run.mjs \
  --candidate-jar /absolute/path/to/candidate.jar \
  --candidate-pom /absolute/path/to/original-pom.xml \
  --java /absolute/path/to/jdk/bin/java \
  --shell /absolute/path/to/catalog.html \
  --work-dir /absolute/path/to/new-run
```

Compilation uses only the candidate JAR and `--release 21 -proc:none -Xlint:all
-Werror`. `jdeps` rejects internal or missing class dependencies. Thirteen fixed
scenarios execute 35 requests, including the supported subscription's unchallenged
policy denial, actual localized catalog titles,
capability ON/OFF/wrong MIME, independently authorized prefetch/read, sanitizer
privacy, useful fallback, and repeated tenant/locale/authorization changes.
The harness requires the exact complete summary, not a generic success string.

The runner records candidate/POM/shell/source identities and rechecks them after
execution. It uses a restricted child environment; raw protocol responses or
tokens are not archived. Failed stages remain non-PASS in `receipt.json`.
Compilation is bounded to 120 seconds, Java contracts and `jdeps` to 60 seconds
each, child output to 2 MiB, and process cleanup to two independent two-second
TERM/KILL phases. Simulation requests and completion each have five-second
bounds, with at most 60 requests. Lifecycle startup/cancellation/graceful/forced
bounds are 5/2/2/1 seconds. SDK handshake and refresh each have a ten-second
abort signal plus an independent local timeout.

This runner deliberately uses the off-network simulator, not the independently
supervised loopback listener below. Nothing here establishes a production-host pass.

For a separate, narrow live-HTTP authorization check, compile the public-API
fixture and `AppsFixtureHttpAuthorizationTest.java` against the same candidate
JAR with `--release 21 -proc:none -Xlint:all -Werror`, then run the test with
the candidate JAR on the classpath and the built shell as its argument:

```sh
mkdir -p /absolute/path/to/new-classes
"/absolute/path/to/jdk/bin/javac" --release 21 -proc:none -Xlint:all -Werror \
  -classpath /absolute/path/to/candidate.jar -d /absolute/path/to/new-classes \
  verification/interoperability/apps/src/com/soklet/interop/apps/AppsFixture.java \
  verification/interoperability/apps/test-src/com/soklet/interop/apps/AppsFixtureHttpAuthorizationTest.java
"/absolute/path/to/jdk/bin/java" \
  -classpath /absolute/path/to/new-classes:/absolute/path/to/candidate.jar \
  com.soklet.interop.apps.AppsFixtureHttpAuthorizationTest /absolute/path/to/catalog.html
```

This starts a real Soklet listener on an ephemeral loopback port and checks 13
requests across authorized, denied, changed-tenant/locale, and revoked states
for the same credential, including an explicit subscription-policy 403 without
`WWW-Authenticate`. It checks that hidden tool errors match unknown-tool
errors, while denied and unknown resource reads both expose no content; the
fixture does not promise identical resource errors. This is server authorization
evidence, not a browser-session or released-host authorization claim.

## Real Inspector render/refresh profile

```sh
node verification/interoperability/apps/run-host.mjs \
  --candidate-jar /absolute/path/to/candidate.jar \
  --candidate-pom /absolute/path/to/original-pom.xml \
  --java /absolute/path/to/jdk/bin/java \
  --shell /absolute/path/to/catalog.html \
  --dependencies /absolute/path/to/locked-install \
  --inspector-build /absolute/path/to/prepared-inspector-build \
  --browser '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome' \
  --work-dir /absolute/path/to/new-host-run
```

This local macOS profile requires Node 26.5.0, the exact dependency tree used to
build the Apps SDK shell, and the separately prepared Inspector source build
described in [the Inspector instructions](../inspector/README.md). The host is
unmodified upstream commit `1974465ccb5454a5110e609e4140171fa360a544`, containing
the plain-403 fix from PR #2690. Its package version is still `2.10.1`; receipts
explicitly identify it as an **unreleased upstream source build**. The older
locked installation supplies the shell dependencies only. The host runner performs
no npm install or upstream modification. The candidate, embedded POM, fixture
classes, shell/build receipt, harness sources, dependency tree, verified upstream
source/build receipt, and complete Chrome distribution are identified and
rechecked. Configuration is read-only modern HTTP with Apps enabled and Skills disabled.

The launcher receives a random disposable token through private stdin, binds
only `127.0.0.1` on an ephemeral port, and stops on EOF. It emits only fixed
readiness/shutdown controls; framework diagnostics are discarded through a
public lifecycle observer. Input has a ten-second deadline, the launcher's
independent lifetime limit is 150 seconds, and shutdown is bounded to five
seconds. The parent imposes a shorter 120-second fixture/host lifetime and a
90-second browser lifetime, with the shared process-group cleanup fallback.
The live launcher tests cover nine cases and two direct HTTP requests.

Unlike the ordinary-tools proxy, this profile forwards the credential to
Soklet admission. Independent requests verify a wrong credential gets 401 and
the correct credential succeeds. The host uses only an isolated memory secret
store, with authenticated API and exact origin checks. Chrome uses a new private
profile and no user accounts, keychain, or saved browser state. Sandbox, CSP,
origin and certificate protections are not disabled. Browser requests are
intercepted before navigation and before child targets resume: only the exact
Inspector origin and its exact sandbox document are allowed. The pinned optional
Google Fonts stylesheet is blocked; system fonts are used.

The browser operates visible controls: connect, select Apps, select Show catalog,
then click the embedded catalog's own Refresh catalog button. Default execution
contexts are tracked across same-process frames and paused/attached cross-process
iframes. No synthetic MCP/SDK call is used as evidence of a button click.
The probe checks actual text-only rendering, `lang`/`dir`, currency/UTC date,
opaque `srcdoc` sandbox attributes, data clearing while pending, and successful
render after refresh. The current upstream-source profile requires exactly eight
successful MCP exchanges and one to eight individually validated subscription
policy denials (HTTP 403), at most sixteen retained exchanges. Each retry must
retain the exact authentication, protocol, capability, error-envelope and byte
bounds; the first denial precedes the initial tool call, and successful exchanges
retain their required order. This reuses the already reviewed bounded-denial
validator without changing the historical exact-one-denial profile or receipts.

A fixed six-second observation window follows the refresh and must complete
without a process exit, interruption, browser exception, proxy rejection or
unexpected traffic. The transport still caps all exchanges at 32. Real DOM
disconnect and supervised cleanup follow, and traces are sealed again after
all callers and the proxy stop. Late extra requests or errors cannot retain a
passing verdict. The receipt records both the completed observation and these
bounds, and input identity covers both trace validators.

Only allowlisted structural facts and static input identities are saved in
`receipt.json` and `sanitized-trace.json`. No raw browser/host diagnostics, live
config, token or token hash, private tool body, or private-body hash is archived.
The exact newly created private session directory is removed after cleanup;
the shared dependency installation and the user's host state are untouched.
Failed attempts remain non-PASS. Successful runs qualify only this narrow local
render/refresh profile, never the full host-security matrix.

### Historical Inspector 2.7.0 limitation: unsolicited OAuth recovery

The following describes retained historical receipts. The current source pin
contains the upstream fix for an unchallenged 403, which must be verified by a
fresh run. This does not change the fixture's denial policy or clear independent
real-401 revocation/OAuth and browser-permission limitations.

Inspector's web client attempts OAuth discovery after Soklet's intentional 403
catalog-subscription denial, even with the configured disposable bearer header.
It tries five metadata GETs and a dynamic-registration POST to the local proxy.
The proxy rejects all of them; none reaches Soklet, an account, an OAuth server,
or an external network. Rejections retain only fixed method/path categories.
There is no supported web equivalent of the CLI's `--stored-auth-only` switch
in this pinned build. `oauth.onInsufficientScope: "throw"` applies to an explicit
insufficient-scope challenge, not this generic policy denial.

The aggregate verdict remains non-passing. `BLOCKED_HOST_AUTH_FALLBACK` (exit 2)
is possible even when `renderRefresh` is `PASSED_NARROW_OBSERVATION`; it requires
the exact nine-exchange MCP trace, all browser/identity/cleanup checks, and the
exact six refused ancillary attempts. Repeated subscription/OAuth attempts or
browser exceptions remain `FAILED` (exit 1), not an expanded allowed sequence.
When both render and refresh were observed before such a failure, the receipt
says `OBSERVED_BEFORE_HOST_FAILURE`, not PASS. Final input identities and
supervised cleanup are checked even on a failed run.
It is not a permission to forward registration, ignore extra traffic, change the
fixture's authorization policy, or patch upstream code. Resolve this host
limitation through a supported upstream option/fix or an explicitly reviewed
host disposition before treating the aggregate profile as PASS.

## Harness tests and remaining work

```sh
node --test verification/interoperability/apps/runner-self-test.mjs \
  verification/interoperability/apps/shell-self-test.mjs \
  verification/interoperability/apps/browser-probe-self-test.mjs \
  verification/interoperability/apps/host-runner-self-test.mjs \
  verification/interoperability/apps/host-trace-self-test.mjs

node verification/interoperability/apps/launcher-self-test.mjs \
  --candidate-jar /absolute/path/to/candidate.jar \
  --java /absolute/path/to/jdk/bin/java \
  --shell /absolute/path/to/catalog.html \
  --work-dir /absolute/path/to/new-launcher-test
```

Shell tests use explicit DOM/bridge mocks; they are not real browser execution
of the bundled SDK. They cover malicious translations, formatting, races,
missing/conflicting host preferences, changed context, failed calls, and
teardown. Browser-probe self-tests use CDP mocks; only a real `run-host.mjs`
receipt establishes its narrow host result. Merely observing sandbox attributes
is not proof of CSP or permissions enforcement. The separate advanced Apps
profiles cover caller transitions, revocation, permissions, CSP denial/allowlisting,
and Apps OFF; each needs its own receipt. The MIME matrix provides direct-HTTP
evidence only. Later production-host qualification remains separate.
