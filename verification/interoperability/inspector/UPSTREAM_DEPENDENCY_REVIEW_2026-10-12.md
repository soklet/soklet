# Inspector upstream source build — October 12, 2026

The current local harness uses the unmodified upstream merge commit
`1974465ccb5454a5110e609e4140171fa360a544`, containing
[PR #2690](https://github.com/modelcontextprotocol/inspector/pull/2690). This fixes
[issue #2515](https://github.com/modelcontextprotocol/inspector/issues/2515): a
403 without a nonempty `WWW-Authenticate` header is an ordinary denial. The
upstream 401 and challenged-403 authorization behavior remains intact.

The source package version is still `2.10.1`. This is an **unreleased upstream
source build**, not the npm/GitHub 2.10.1 release, whose source lacks this fix.
The previous package/lock and September 19 review remain unchanged historical
inputs and the separate Apps SDK 2.0.0 shell-build dependency installation.

## Immutable source and dependency inputs

`upstream-source-pin.json` records:

- Exact codeload commit archive, SHA-256
  `62fee5e07546c79a2c52eb4a63eaddb57487dafe30af9f418599596fd706b183`.
- All 1,658 original source files, SHA-256 of the sorted path/content digest
  manifest `b4d6aa6c13f151206e81dfcba9edb0a74764687305b285df64874b9dd7364d08`.
- Original upstream lockfiles for the root, web, CLI and launcher packages,
  containing respectively 392, 649, 109 and 1 package entries, including each
  root entry. These counts include platform-conditional packages and overlap
  across installs; they are not a unique installed dependency count.
- Node 26.5.0 and npm 11.17.0.

All non-root lock entries have npm registry HTTPS URLs and SHA-512 integrity.
No version resolution, dependency overrides, source edits or lock regeneration
occurs. The source fixes the root MCP client/core/server dependencies at 2.3.1.
The full source manifest includes every package/config/build script, not just
the authentication changes. Archive extraction occurs only after exact checksum
verification; the reviewed archive contains ordinary files/directories only.

## Installation and build behavior

Each required package runs `npm ci --ignore-scripts --no-audit --no-fund` with
private HOME, npm user/global config, cache and temporary paths. The environment
does not inherit user npm credentials or Inspector state. No upstream
`postinstall`, `prepack`, npm build hook, browser download or Git operation runs.
The root cascade installer would otherwise run unconstrained per-client installs;
it is explicitly disabled. The launcher has an empty dependency lock but is
still checked and installed explicitly. TUI and mcpdo are not needed or built.

The root lock marks optional fsevents installation; web and CLI additionally mark
esbuild installation. Those lifecycle scripts remain disabled. Installed
platform packages provide the esbuild/native bundler executables consumed by
the explicit build commands; no native compilation was required on this macOS
arm64 run. Keyring packages may be installed as upstream dependencies, but the
build/test environment selects Inspector's memory store and never uses a user
keychain. Runtime probes independently isolate all auth/browser state.

`BUILD_COMMANDS` explicitly invokes the locked TypeScript compiler, Vite and
tsup with the unchanged upstream configurations: web typecheck/client/runner,
CLI runner, launcher, and the two upstream authentication regression suites.
The focused suites use the web installation's Vitest so its declared happy-dom
dependency resolves correctly. Direct invocation also prevents launcher
`postbuild` and test `pretest` scripts from running implicitly. The launcher is
called with Node and does not need its optional executable-bit adjustment.

Every child is process-group supervised with bounded output and deadlines:
10 seconds for npm identity, 30 seconds for archive extraction, and 300 seconds
per install/build/test command. Downloading has a 60-second deadline and 16-MiB
limit. A failed step cannot produce a passing receipt.

## Verification and scope

The successful October 12 build preserved every original source file and
lockfile, completed the web/CLI/launcher builds, and passed **67 upstream tests**
across `challenge.test.ts` and `authChallengeFetch.test.ts`. The source checks run
before installation, after installation and after compilation/tests. Complete
installed/build-tree hashes include generated assets, dependency files and
symlinks; escaping symlinks fail. Runtime verification checks the archive,
source manifest, all locks, recipe and process-supervisor identity, receipt,
platform and full build tree. Its six independent regression tests cover source
mutation/injection/removal, generated-output drift, symlink escapes, forged
incomplete receipts and refusal to overwrite/relabel an existing installation.

Build logs/receipts remain outside source control. An initial build used root
Vitest and failed to resolve web-only happy-dom; its failed receipt is retained.
A fresh build using web Vitest passed without modifying upstream source or
dependencies. Vite's forward-looking native-config-loader warnings were retained.

This review establishes a reproducible recipe and exact local inputs, not an
independent security audit of every transitive dependency, an npm publication,
or a complete Apps/OAuth/security host qualification. Separate real-browser
receipts determine whether the source build actually renders/refreshes against
the selected Soklet candidate without unwanted recovery traffic.
