# Apps server security recheck — September 30

Recorded run output was removed from the checkout on September 30. Historical
results below describe the original runs; artifact links refer to that earlier
commit. New run output belongs outside source control.

**PASS for the bounded server authorization and shell regression checks.**
No production code or public API fix was needed. The Claude host's tenant-change
and denial/revocation UI checks did not run because native UI automation was
unavailable. This result is separate from the
[September 29 Claude render/refresh pass](../mcp-apps-cloud-qualification-2026-09-29/REVIEW.md).
It is development evidence, with no full host or release-candidate acceptance.

## Inputs and execution

The checks used the same clean, pushed core artifact as the September 29 cloud
check: commit `3fe09465fd1241ffb785493fdff4a2074f8b0e0a`, JAR SHA-256
`c454eac7ff5fd58d564a2c1603133ed1c47a01210d2910be4d60f392ebe1184f`.
The original POM matched the embedded POM. The same reviewed standalone shell
and build receipt matched the current sources; shell SHA-256 was
`3229c8e0a9ee17dcbb2030040fac282b172715588c7b25963275529c0b650f60`.
The exact protocol revision was `2026-07-28`.

The existing public-API fixture and tests compiled with Java 17, annotation
processing disabled, and all warnings treated as errors. Both dependency audits
found no Soklet internal dependencies or missing classes. Source, artifact,
shell, and helper identities were checked again after execution. The live test
used one ephemeral loopback listener, synthetic credentials, a 256-MiB heap,
two processors, a 45-second independent process deadline, and supervised process
group cleanup. Soklet reported graceful termination before a successful exit.
No external service or user credential was used for these server checks.

The first restricted-environment identity check stopped before compilation or
listener startup because Git rejected repository ownership. Its failed receipt
is retained. The successful retry supplied a command-scoped `safe.directory`
setting; it did not change global Git configuration.

## Checked behavior

- **13 simulator scenarios, 35 requests:** capability ON/OFF/wrong MIME,
  independently authorized resource prefetch/read, sanitizer privacy, localized
  titles and fallback, repeated caller/tenant/language changes, and permanent
  subscription denial without an authentication challenge.
- **13 real HTTP requests:** an allowed caller refreshed and read the UI;
  a denied caller saw empty catalogs, could not call hidden tools or read the
  resource; a changed caller received Portuguese tenant-beta data without old
  tenant-alpha data; revocation made the next tool call and read return 401
  without protected content. Hidden tool errors matched unknown-tool errors.
  Denied and unknown resource reads both exposed no content, with no promise
  that their errors are identical. All responses used `Cache-Control: no-store`
  and omitted the fixture's synthetic private canary. The subscription policy
  returned plain 403 without `WWW-Authenticate`.
- **27 shell and runner tests:** text-only hostile-label handling, English,
  Portuguese and Arabic formatting, clearing data before refresh, clearing on
  failure/timeout/teardown, language/time-zone invalidation, and suppression of
  late results. These use mocked DOM/SDK bridges and do not prove actual host
  behavior or browser enforcement.

## Host checks pending at the time of this run

Later Claude checks completed the caller/denial/revocation, CSP, permission-policy,
RTL and real-locale portions below. Their current scope and remaining limitations
are recorded in the [compatibility matrix](../MCP_CLIENT_COMPATIBILITY.md).

Two native automation entry points timed out: selecting Claude and reading the
surface inventory. No test listener, tunnel, or connector was started for the
planned Claude UI run. No Claude version or host-security PASS is asserted here.
The next host check remains: render alpha, change the same admitted credential
to beta/Portuguese, click the App's real Refresh button, then deny access and
verify that another real refresh clears the view. Credential revocation, host
CSP and permission enforcement, RTL rendering, host context invalidation, and
production OAuth remain separate outstanding qualification work.

The [evidence manifest](https://github.com/soklet/soklet/blob/4c57cbae55fe306dde57dc2f6564e2f8f3c06241/release/mcp-apps-security-qualification-2026-09-30/evidence.json) binds the retained simulator, live-HTTP,
shell, and initial setup receipts by compressed and decoded SHA-256. Temporary
run files remain in `/private/tmp/soklet-apps-security-20260930`.
