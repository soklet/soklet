# Exact conformance toolchain dependency review — 2026-09-13

Disposition: **OPEN — candidate conformance/publication blocked pending explicit toolchain-risk disposition.** This is an exposure inventory, not a waiver or an npm-audit-clean claim. The measured repin and development verification may proceed; immutable release PASS evidence must not silently override this blocker.

## Reviewed input and process

The public [alpha.11 checkout](https://github.com/modelcontextprotocol/conformance/tree/a983ba93c91e0bb31d0b6849eeb52f0ad1083107) is pinned to commit `a983ba93c91e0bb31d0b6849eeb52f0ad1083107`. Its package-lock SHA-256 is `8c30fe8f15735bc4660c682225b12ec84bbd08c22e839127445d06b5476c4945`; exact Node 26.5.0/npm 11.17.0 produced CLI SHA-256 `b8355fba248c019b667a9c16289748ebfd85ca2668890812054b7997b8df8f3b`. Source/package/schema/build hashes are enforced by upstream-pins.json and verify.mjs.

The install was `npm ci --ignore-scripts` with isolated empty npm user/global configurations and cache, followed by the reviewed explicit `npm run build` (tsdown, minified Node20-targeted CLI). No audit fix, override, install hook, prepack, upstream test, lint, dev server, floated version, or dependency suppression was applied. The audit request contained only the public upstream dependency tree, not Soklet files or credentials. Audit queried 2026-09-13: **16 affected packages, 9 high, 5 moderate, 2 low, zero critical**; package counts are not advisory counts. These dependencies are external release tooling, not shipped Soklet JAR dependencies.

## Alpha.10 to alpha.11 dependency delta

Comparing the exact upstream lockfiles at `49103de6ed70804e940637bf3e9e29e4a3f54e64` and the new commit changes package metadata and only two dependency versions: `express-rate-limit 8.3.1 -> 8.5.1`, `ip-address 10.1.0 -> 10.2.0`. All other affected installed versions below were already present in alpha.10. This does not grandfather their risk; alpha.11's ip-address remains affected and the mapped-address advisory's reported range includes its new version.

## Selection and unresolved exposure

The gate runs 49 exact Java-server scenario profiles, not upstream client, authorization-server, mock-server, browser, or dev-server commands. Each CLI and Java fixture is process-supervised with finite command/shutdown bounds and bounded output; children receive only PATH/JAVA_HOME/locale/temp variables and NO_COLOR, not service credentials or proxy configuration. The CLI and source tree are verified against their pinned checksums before scenario execution.

The directly executed Undici `request()` path cannot be dismissed as dev-only. Its high issues below require features not configured by the selected DNS scenario, but the low [keep-alive queue poisoning advisory](https://github.com/advisories/GHSA-35p6-xmwp-9g52) overlaps the underlying request/connection machinery. Static-schema Ajv reaches fast-uri; static references and no network resolver limit its SSRF/authority-confusion implications. This review has not approved residual exposure or proven all possible selected-path interactions irrelevant. Candidate promotion therefore remains blocked until the owner explicitly accepts the documented bounded use or a separately reviewed exact upstream dependency/build repin fixes the issues and repeats all observations. Do not run these tools against untrusted remote servers using this disposition.

Primary source paths: [DNS scenario](https://github.com/modelcontextprotocol/conformance/blob/a983ba93c91e0bb31d0b6849eeb52f0ad1083107/src/scenarios/server/dns-rebinding.ts), [wire schema validator](https://github.com/modelcontextprotocol/conformance/blob/a983ba93c91e0bb31d0b6849eeb52f0ad1083107/src/validation/wire-schema.ts), [package metadata](https://github.com/modelcontextprotocol/conformance/blob/a983ba93c91e0bb31d0b6849eeb52f0ad1083107/package.json), [lockfile](https://github.com/modelcontextprotocol/conformance/blob/a983ba93c91e0bb31d0b6849eeb52f0ad1083107/package-lock.json). The following GHSA links are the exact npm audit reports; CVE aliases are not guessed.

## Package-by-package advisory inventory

### @hono/node-server — moderate

`node_modules/@hono/node-server` = `1.19.14` (runtime).

Runtime transitive via @modelcontextprotocol/sdk. Windows serve-static traversal concerns a server adapter not started by selected server-conformance commands; development observation was macOS and CI pin is Linux.

- [GHSA-frvp-7c67-39w9](https://github.com/advisories/GHSA-frvp-7c67-39w9) — moderate: Node.js Adapter for Hono: Path traversal in 'serve-static' on Windows via encoded backslash ('%5C').

### @humanfs/node — moderate

`node_modules/@humanfs/node` = `0.16.7` (dev-only).

Dev-only via eslint. Upstream lint is not invoked by the selected npm build or exact built CLI execution; no recursive copy of untrusted inputs is requested.

- [GHSA-p498-v437-472g](https://github.com/advisories/GHSA-p498-v437-472g) — moderate: humanfs: Recursive copy follows symlinked files and copies data from outside the source tree.

### @vitest/mocker — moderate

`node_modules/@vitest/mocker` = `4.1.5` (dev-only).

Dev-only via vitest. Upstream Vitest and its mock redirect server are not run. Soklet's node selftests do not invoke Vitest.

- [GHSA-82fw-gwwq-j7x9](https://github.com/advisories/GHSA-82fw-gwwq-j7x9) — moderate: Vitest: Path Traversal / Arbitrary File Read via @vitest/mocker Redirect Mock.

### body-parser — low

`node_modules/body-parser` = `2.2.2` (runtime).

Runtime via express -> body-parser (also SDK Express dependency). Express mock servers are in src/mock-server and client/authorization scenarios; the selected Java-server scenarios do not rely on body-parser size limits.

- [GHSA-v422-hmwv-36x6](https://github.com/advisories/GHSA-v422-hmwv-36x6) — low: body-parser vulnerable to denial of service when invalid limit value silently disables size enforcement.

### brace-expansion — high

`node_modules/@typescript-eslint/typescript-estree/node_modules/brace-expansion` = `5.0.5` (dev-only). `node_modules/brace-expansion` = `1.1.14` (dev-only).

Dev-only through ESLint/typescript-eslint minimatch dependencies. The reviewed build operates on exact trusted source paths, not attacker-provided glob expressions; no lint invocation. Denial-of-service advisories remain recorded, not removed from audit.

- [GHSA-jxxr-4gwj-5jf2](https://github.com/advisories/GHSA-jxxr-4gwj-5jf2) — moderate: brace-expansion: Large numeric range defeats documented 'max' DoS protection.
- [GHSA-3jxr-9vmj-r5cp](https://github.com/advisories/GHSA-3jxr-9vmj-r5cp) — high: brace-expansion: DoS via exponential-time expansion of consecutive non-expanding {} groups.
- [GHSA-mh99-v99m-4gvg](https://github.com/advisories/GHSA-mh99-v99m-4gvg) — high: brace-expansion: DoS via unbounded expansion length causing an out-of-memory process crash.
- [GHSA-rgw5-rvv9-x895](https://github.com/advisories/GHSA-rgw5-rvv9-x895) — high: brace-expansion: DoS via unbounded intermediate arrays, bypassing the CVE-2026-14257 mitigation.

### esbuild — low

`node_modules/esbuild` = `0.27.4` (dev-only).

Dev-only through tsx/Vite tooling. The reviewed build command uses tsdown/rolldown; no esbuild development server, particularly the affected Windows server, is started.

- [GHSA-g7r4-m6w7-qqqr](https://github.com/advisories/GHSA-g7r4-m6w7-qqqr) — low: esbuild allows arbitrary file read when running the development server on Windows.

### fast-uri — high

`node_modules/fast-uri` = `3.1.0` (runtime).

Runtime via ajv -> fast-uri. src/validation/wire-schema.ts compiles checksum-pinned schemas with internal mcp:// identifiers and static $ref values; no loadSchema/compileAsync or network resolver is configured. Authority/SSRF/security-boundary exploitation is not demonstrated in this usage. This is a contextual reachability assessment, not a general package safety claim.

- [GHSA-v2hh-gcrm-f6hx](https://github.com/advisories/GHSA-v2hh-gcrm-f6hx) — high: fast-uri vulnerable to host confusion via literal backslash authority delimiter.
- [GHSA-7p8r-x3mc-p8w7](https://github.com/advisories/GHSA-7p8r-x3mc-p8w7) — high: fast-uri vulnerable to host confusion via backslash authority introducer.
- [GHSA-q3j6-qgpj-74h6](https://github.com/advisories/GHSA-q3j6-qgpj-74h6) — high: fast-uri vulnerable to path traversal via percent-encoded dot segments.
- [GHSA-v39h-62p7-jpjc](https://github.com/advisories/GHSA-v39h-62p7-jpjc) — high: fast-uri vulnerable to host confusion via percent-encoded authority delimiters.
- [GHSA-f65p-4m7j-42xc](https://github.com/advisories/GHSA-f65p-4m7j-42xc) — high: fast-uri vulnerable to server-side request forgery via malformed IPv6 normalization.
- [GHSA-jqff-g426-hqxp](https://github.com/advisories/GHSA-jqff-g426-hqxp) — high: fast-uri vulnerable to host confusion via percent-encoded scheme normalization.
- [GHSA-4c8g-83qw-93j6](https://github.com/advisories/GHSA-4c8g-83qw-93j6) — high: fast-uri vulnerable to host confusion via failed IDN canonicalization.

### hono — high

`node_modules/hono` = `4.12.15` (runtime).

Runtime transitive via @modelcontextprotocol/sdk. Reported surfaces include JSX/SSR, middleware, JWT, proxy, adapters and static serving. The selected command tests the Java server; it does not start an SDK Hono server or use Hono as a proxy/security boundary. Not a Soklet runtime dependency. Changing to mock-client, authorization-server or SDK server commands requires renewed review.

- [GHSA-qp7p-654g-cw7p](https://github.com/advisories/GHSA-qp7p-654g-cw7p) — moderate: Hono has CSS Declaration Injection via Style Object Values in JSX SSR.
- [GHSA-hm8q-7f3q-5f36](https://github.com/advisories/GHSA-hm8q-7f3q-5f36) — low: Hono has improper validation of NumericDate claims (exp, nbf, iat) in JWT verify().
- [GHSA-p77w-8qqv-26rm](https://github.com/advisories/GHSA-p77w-8qqv-26rm) — moderate: Hono's Cache Middleware ignores Vary: Authorization / Vary: Cookie leading to cross-user cache leakage.
- [GHSA-9vqf-7f2p-gf9v](https://github.com/advisories/GHSA-9vqf-7f2p-gf9v) — moderate: Hono: bodyLimit() can be bypassed for chunked / unknown-length requests.
- [GHSA-69xw-7hcm-h432](https://github.com/advisories/GHSA-69xw-7hcm-h432) — moderate: hono/jsx has Unvalidated JSX Tag Names that May Allow HTML Injection.
- [GHSA-xrhx-7g5j-rcj5](https://github.com/advisories/GHSA-xrhx-7g5j-rcj5) — moderate: Hono: IP Restriction bypasses static deny rules for non-canonical IPv6 .
- [GHSA-3hrh-pfw6-9m5x](https://github.com/advisories/GHSA-3hrh-pfw6-9m5x) — moderate: Hono: Cookie helper does not sanitize sameSite and priority, allowing Set-Cookie injection.
- [GHSA-f577-qrjj-4474](https://github.com/advisories/GHSA-f577-qrjj-4474) — moderate: Hono: JWT middleware accepts any Authorization scheme, not only Bearer.
- [GHSA-2gcr-mfcq-wcc3](https://github.com/advisories/GHSA-2gcr-mfcq-wcc3) — moderate: Hono: app.mount() strips mount prefix using undecoded path, causing incorrect routing for percent-encoded paths.
- [GHSA-rv63-4mwf-qqc2](https://github.com/advisories/GHSA-rv63-4mwf-qqc2) — moderate: hono: Body Limit Middleware can be bypassed on AWS Lambda by understating 'Content-Length'.
- [GHSA-wgpf-jwqj-8h8p](https://github.com/advisories/GHSA-wgpf-jwqj-8h8p) — moderate: hono: Lambda@Edge adapter keeps only the last value of a repeated request header, dropping the rest.
- [GHSA-88fw-hqm2-52qc](https://github.com/advisories/GHSA-88fw-hqm2-52qc) — high: hono: CORS Middleware reflects any Origin with credentials when 'origin' defaults to the wildcard.
- [GHSA-wwfh-h76j-fc44](https://github.com/advisories/GHSA-wwfh-h76j-fc44) — moderate: hono: Path traversal in 'serve-static' on Windows via encoded backslash ('%5C').
- [GHSA-j6c9-x7qj-28xf](https://github.com/advisories/GHSA-j6c9-x7qj-28xf) — moderate: hono: AWS Lambda adapter merges multiple 'Set-Cookie' headers into one value, dropping cookies on ALB single-header and Lattice.
- [GHSA-xgm2-5f3f-mvvc](https://github.com/advisories/GHSA-xgm2-5f3f-mvvc) — moderate: Hono: API Gateway v1 adapter can drop a distinct repeated request header value during de-duplication.
- [GHSA-hvrm-45r6-mjfj](https://github.com/advisories/GHSA-hvrm-45r6-mjfj) — moderate: hono/jsx does not isolate context per request, leading to cross-request data disclosure.
- [GHSA-w62v-xxxg-mg59](https://github.com/advisories/GHSA-w62v-xxxg-mg59) — moderate: Hono: Server-Side XSS via JSX Escaping Bypass in cx() Utility.
- [GHSA-8j4g-w8fx-2239](https://github.com/advisories/GHSA-8j4g-w8fx-2239) — moderate: Hono: ReDoS in CORS middleware via Access-Control-Request-Headers.
- [GHSA-f23p-vx2j-j53r](https://github.com/advisories/GHSA-f23p-vx2j-j53r) — moderate: Hono: 'memo()' retains SSR output across requests, leading to cross-user data disclosure.
- [GHSA-79qm-7rj5-m7r9](https://github.com/advisories/GHSA-79qm-7rj5-m7r9) — low: Hono: Proxy Helper does not remove response headers listed in the 'Connection' header.
- [GHSA-54fx-42gc-7vw4](https://github.com/advisories/GHSA-54fx-42gc-7vw4) — moderate: Hono: Algorithmic Complexity DoS in Language Middleware.
- [GHSA-gqvv-2mrq-wpjv](https://github.com/advisories/GHSA-gqvv-2mrq-wpjv) — moderate: Hono: Incomplete fix for CVE-2026-39408: 'toSSG()' still writes files outside the output directory.
- [GHSA-g6gw-c38x-mqfc](https://github.com/advisories/GHSA-g6gw-c38x-mqfc) — moderate: Hono: Unbounded dot-notation nesting in 'parseBody()' can cause memory exhaustion.
- [GHSA-crvj-82cr-hjcx](https://github.com/advisories/GHSA-crvj-82cr-hjcx) — moderate: Hono: Query parser reads parameters after the URL fragment, causing cache-key and proxy interpretation differentials.

### ip-address — high

`node_modules/ip-address` = `10.2.0` (runtime).

Runtime transitive via @modelcontextprotocol/sdk -> express-rate-limit -> ip-address. Used for Express rate-limit IP classification in SDK server paths, not Java fixture Host authorization. Current run does not use those mock-server endpoints as its server under test. Version changed in alpha.11; see delta below.

- [GHSA-mwp4-54f8-5fhr](https://github.com/advisories/GHSA-mwp4-54f8-5fhr) — high: ip-address: Address4 decodes leading-zero octets as decimal while resolvers decode them as octal, allowing SSRF and trust-boundary bypass.
- [GHSA-4xrf-jv44-h6hh](https://github.com/advisories/GHSA-4xrf-jv44-h6hh) — moderate: ip-address: a CIDR suffix on the parsed address suppresses special-use classification and can bypass SSRF and trust-boundary checks.
- [GHSA-22jq-vg5j-6vgg](https://github.com/advisories/GHSA-22jq-vg5j-6vgg) — moderate: ip-address: misclassification of IPv4-mapped/NAT64 IPv6 addresses can bypass SSRF and trust-boundary checks.

### js-yaml — high

`node_modules/js-yaml` = `4.1.1` (dev-only).

Dev-only via eslint. Upstream CLI uses the separate yaml package, not js-yaml, for its YAML inputs. No ESLint or attacker-authored YAML is processed in this gate.

- [GHSA-h67p-54hq-rp68](https://github.com/advisories/GHSA-h67p-54hq-rp68) — moderate: JS-YAML: Quadratic-complexity DoS in merge key handling via repeated aliases.
- [GHSA-52cp-r559-cp3m](https://github.com/advisories/GHSA-52cp-r559-cp3m) — high: js-yaml: YAML merge-key chains can force quadratic CPU consumption.
- [GHSA-5p4m-2wfm-xmqj](https://github.com/advisories/GHSA-5p4m-2wfm-xmqj) — high: JS-YAML: Quadratic CPU consumption in !!omap resolution (3.x and 4.x) — CVE-2026-59870 fix not backported.
- [GHSA-2883-xcg3-v3hh](https://github.com/advisories/GHSA-2883-xcg3-v3hh) — high: js-yaml: maxTotalMergeKeys does not limit CPU use for empty merge sources.

### nanoid — high

`node_modules/nanoid` = `3.3.11` (dev-only).

Dev-only through postcss/Vite. No custom generator with attacker-controlled size is used by the selected server CLI; the reviewed build does not process untrusted CSS.

- [GHSA-28wg-ghj8-5hjv](https://github.com/advisories/GHSA-28wg-ghj8-5hjv) — high: nanoid: non-secure generators can loop indefinitely with negative size.
- [GHSA-2v37-7h3g-55p8](https://github.com/advisories/GHSA-2v37-7h3g-55p8) — high: nanoid: custom generators can loop indefinitely when size is zero.
- [GHSA-xwg4-73v4-xw9w](https://github.com/advisories/GHSA-xwg4-73v4-xw9w) — high: nanoid: Integer Overflow or Wraparound.

### postcss — high

`node_modules/postcss` = `8.5.10` (dev-only).

Dev-only through Vite/Vitest. No attacker-controlled CSS/sourceMappingURL processing is performed by the reviewed TypeScript CLI build and Java-server scenario run.

- [GHSA-6g55-p6wh-862q](https://github.com/advisories/GHSA-6g55-p6wh-862q) — high: PostCSS: Arbitrary file read and information disclosure via attacker-controlled sourceMappingURL in CSS comments.
- [GHSA-fxqj-rqcc-2cmp](https://github.com/advisories/GHSA-fxqj-rqcc-2cmp) — moderate: PostCSS: incomplete fix of GHSA-6g55-p6wh-862q — attacker-controlled sourceMappingURL reads arbitrary .map files when 'from' is unset.
- [GHSA-r28c-9q8g-f849](https://github.com/advisories/GHSA-r28c-9q8g-f849) — high: PostCSS: Path Traversal in Previous Source Map Auto-Loading (sourceMappingURL) leads to Arbitrary .map File Disclosure.

### qs — moderate

`node_modules/qs` = `6.15.0` (runtime).

Runtime via express/body-parser -> qs. Vulnerable stringify and query parsing are not the Java fixture's parser; selected server scenarios do not start the Express mock server. Re-review if client/authorization commands are enabled.

- [GHSA-q8mj-m7cp-5q26](https://github.com/advisories/GHSA-q8mj-m7cp-5q26) — moderate: qs has a remotely triggerable DoS: qs.stringify crashes with TypeError on null/undefined entries in comma-format arrays when encodeValuesOnly is set.
- [GHSA-x5fp-wj9c-mxmx](https://github.com/advisories/GHSA-x5fp-wj9c-mxmx) — moderate: qs array-limit bypass via bracket-key comma parsing.
- [GHSA-4mjr-xmp4-gh2g](https://github.com/advisories/GHSA-4mjr-xmp4-gh2g) — moderate: qs: Denial of Service via Attacker Controlled isBuffer.

### undici — high

`node_modules/undici` = `7.25.0` (runtime).

Runtime, direct dependency. The selected dns-rebinding-protection scenario imports request() in src/scenarios/server/dns-rebinding.ts and sends JSON strings to the runner-owned plain-HTTP loopback fixture. No ProxyAgent, WebSocket, cache, cookie helper, retry interceptor or blob-like body is configured in that path. Those feature-specific high advisories are not shown reachable; the low keep-alive response-queue advisory does overlap the actual client/socket machinery and is not waived. Each scenario uses a fresh process, known fixture and bounded deadline, which limits but does not prove absence of exposure.

- [GHSA-vmh5-mc38-953g](https://github.com/advisories/GHSA-vmh5-mc38-953g) — high: undici vulnerable to TLS certificate validation bypass via dropped requestTls in SOCKS5 ProxyAgent.
- [GHSA-p88m-4jfj-68fv](https://github.com/advisories/GHSA-p88m-4jfj-68fv) — moderate: undici vulnerable to HTTP header injection via Set-Cookie percent-decoding.
- [GHSA-vxpw-j846-p89q](https://github.com/advisories/GHSA-vxpw-j846-p89q) — high: undici WebSocket client vulnerable to denial of service via fragment count bypass.
- [GHSA-hm92-r4w5-c3mj](https://github.com/advisories/GHSA-hm92-r4w5-c3mj) — high: undici vulnerable to cross-origin request routing via SOCKS5 proxy pool reuse.
- [GHSA-g8m3-5g58-fq7m](https://github.com/advisories/GHSA-g8m3-5g58-fq7m) — low: undici vulnerable to Set-Cookie SameSite attribute downgrade via permissive substring matching.
- [GHSA-pr7r-676h-xcf6](https://github.com/advisories/GHSA-pr7r-676h-xcf6) — moderate: undici vulnerable to cross-user information disclosure via shared cache whitespace bypass.
- [GHSA-8xcm-r25x-g524](https://github.com/advisories/GHSA-8xcm-r25x-g524) — moderate: undici vulnerable to downstream response desynchronization via retry interceptor.
- [GHSA-4cwx-7wf7-3272](https://github.com/advisories/GHSA-4cwx-7wf7-3272) — high: undici vulnerable to cross-user information disclosure and parse-time crash via degenerate private cache directives.
- [GHSA-m8rv-5g2x-5cg5](https://github.com/advisories/GHSA-m8rv-5g2x-5cg5) — moderate: undici vulnerable to CRLF Injection via blob-like body 'type' property.
- [GHSA-jr45-8vmc-qm54](https://github.com/advisories/GHSA-jr45-8vmc-qm54) — moderate: undici vulnerable to cross-user information disclosure via whitespace around equals in Cache-Control directives.
- [GHSA-v3r7-h72x-cjcm](https://github.com/advisories/GHSA-v3r7-h72x-cjcm) — moderate: undici vulnerable to cookie attribute injection via unsanitized domain and unparsed setCookie fields.
- [GHSA-35p6-xmwp-9g52](https://github.com/advisories/GHSA-35p6-xmwp-9g52) — low: undici vulnerable to HTTP response queue poisoning via keep-alive socket reuse.

### vite — high

`node_modules/vite` = `8.0.10` (dev-only).

Dev-only through Vitest. No Vite dev server or launch-editor endpoint is started; reviewed hosts are macOS/Linux, not the affected Windows server environment.

- [GHSA-v6wh-96g9-6wx3](https://github.com/advisories/GHSA-v6wh-96g9-6wx3) — moderate: launch-editor: NTLMv2 hash disclosure via UNC path handling on Windows.
- [GHSA-fx2h-pf6j-xcff](https://github.com/advisories/GHSA-fx2h-pf6j-xcff) — high: vite: 'server.fs.deny' bypass on Windows alternate paths.

### vitest — moderate

`node_modules/vitest` = `4.1.5` (dev-only).

Direct dev-only test dependency. No upstream Vitest mock server or test command is executed during the reviewed build and selected scenario run.

- [GHSA-82fw-gwwq-j7x9](https://github.com/advisories/GHSA-82fw-gwwq-j7x9) — moderate: Vitest: Path Traversal / Arbitrary File Read via @vitest/mocker Redirect Mock.
