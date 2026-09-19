# Inspector dependency review — September 19, 2026

This is the isolated P0-H host-harness dependency foundation, not an Apps or
Skills host PASS, a production security assessment, or release-gate evidence.
No Inspector process, npm lifecycle script, browser, account, or credential
store was used during this dependency review. Package resolution and audit
used a fresh cache, explicit isolated user/global npm configuration, a cleared
environment, and the public npm registry. `node_modules` was not installed.

## Reviewed identities

Node `26.5.0` and npm `11.17.0` generated lockfile version 3 using
`npm install --package-lock-only --ignore-scripts --no-audit --no-fund`.
Both direct dependencies are exact, not ranges. An override forces Inspector's
Apps SDK range to the same exact direct Apps dependency.

| Input | Reviewed identity |
| --- | --- |
| `package.json` SHA-256 | `8fc642c9ec63e951bf987d4ccf2c7a198a33f350f7d424ad2e95a1f48dbfe2c0` |
| `package-lock.json` SHA-256 | `37dfcdb1476f7f31c4aeea13971a729588bad7719c67bb8349f7bd8c52d29f29` |
| Inspector | `@modelcontextprotocol/inspector@2.7.0`; npm `gitHead` and source tag `2.7.0`: `2e90a628e6296c62e4bef942afbb43d3faa4baf4` |
| Apps SDK | `@modelcontextprotocol/ext-apps@2.0.0`; npm `gitHead`: `352f6ced4d80772e92b4e7a311854481a8d65b04` |
| Inspector tarball SHA-256 | `6c47dea0a6b230fbca12c4b10472e5cbae8a72b03eaf5335daf83d017b0fdfcd` |
| Apps SDK tarball SHA-256 | `5f99f083b3d4efcab10c77cb9432fe6f12830986fe2eeabefd549ed16dcd18da` |

Exact registry metadata and the downloaded tarball bytes independently match
the SHA-512 values recorded in the September 17 host-qualification report:

```text
@modelcontextprotocol/inspector@2.7.0
sha512-V1SqfR+m3NWMkEe2i3v2GMm00pZtAl/JISAHQzYnOZElEuwuLLkU8vTWBYg5HMCYTgpvuMmzb+MZz98HjdcGuw==
@modelcontextprotocol/ext-apps@2.0.0
sha512-a6tXzFcIbIIdnqumQ7W8Oxd8W/KAPkAKYpoxpD9nDgxZ24ywgxG5pK/8G9W5pOFUygS7gWHQhPv8cwRB44/8yg==
```

Metadata matching is not independent verification of npm provenance
attestations or a reproducible-build proof. The Apps SDK release pin is also
distinct from the Apps specification snapshot used by the host investigation.

## Resolved tree

The lock contains 226 dependency records excluding the root package. Every
record has a public `https://registry.npmjs.org/` tarball URL and SHA-512
integrity; there are no Git, local-path, workspace, or unpinned URL sources.
It retains 39 optional platform records and 53 peer-marked records. These
flags describe the complete lock, not the packages installed on one host.

Inspector's 22 direct dependencies resolve as follows; the checked-in lock
records the complete transitive graph:

```text
@hono/node-server                 2.1.1
@modelcontextprotocol/client      2.0.0
@modelcontextprotocol/core        2.0.0
@modelcontextprotocol/ext-apps    2.0.0
@modelcontextprotocol/server      2.0.0
@modelcontextprotocol/server-legacy 2.0.0
@napi-rs/keyring                  1.3.0
@vitejs/plugin-react              6.1.1
ajv                              8.20.0
atomically                       2.1.1
chokidar                         4.0.3
commander                        13.1.0
hono                             4.13.8
ink                              6.8.0
open                             10.2.0
pino                             9.14.0
proper-lockfile                   4.1.2
react                            19.3.0
undici                           8.10.2
vite                             8.3.0
yaml                             2.9.1
zod                              4.6.5
```

Do not regenerate this graph implicitly during a qualification run. Use
`npm ci --ignore-scripts --no-audit --no-fund` with isolated npm configuration
and cache, then recheck the lock and required package identities. That is a
separate install operation, not something performed by this review.

## Install and execution risks

The lock flags exactly two packages with install scripts:

- Inspector `2.7.0`: `postinstall` runs `scripts/install-clients.mjs`. The exact
  downloaded script exits when installed beneath `node_modules`; in a source
  checkout it can spawn additional `npm install` operations for each client.
  The published archive contains compiled launcher, CLI, TUI, and web output
  and no client `package.json` files. No build or lifecycle execution is
  needed to obtain those shipped bytes. Keep scripts disabled regardless.
- Optional macOS `fsevents@2.3.3`: registry metadata declares
  `install: node-gyp rebuild`. Do not allow this native build or associated
  toolchain/network behavior during harness installation.

The tree also contains native/platform packages: `@napi-rs/keyring@1.3.0`
with 12 platform variants, `rolldown@1.2.9` with 15 binding variants,
`lightningcss@1.33.0` with 11 variants, and `fsevents`. Script suppression
prevents lifecycle execution; it does not make loading these native modules
safe or prevent later runtime credential-store access.

Ten packages expose executable entry points: Inspector, `is-docker`,
`is-in-ci`, `is-inside-container`, `nanoid`, `pino`, `rolldown`, `vite`,
`which`, and `yaml`. Do not execute arbitrary package bins or unpinned `npx`
downloads. Inspector's reviewed bin is `clients/launcher/build/index.js`;
the harness must select its installed, checksum-bound copy explicitly.

Inspector's default secret-store selection probes the OS keyring, including
a test write. A future run must explicitly select
`MCP_INSPECTOR_SECRET_STORE=memory` and isolated paths for
`MCP_STORAGE_DIR`, `MCP_INSPECTOR_OAUTH_STATE_PATH`,
`MCP_CLIENT_CONFIG_PATH`, `MCP_INSPECTOR_SECRET_FILE`, and the catalog/session
configuration. It must not reuse the user's npm settings, OAuth state,
credential store, or writable catalog. Disable automatic browser opening
and use a disposable loopback target; `--stored-auth-only` alone is not
credential isolation. Network installation should fetch only locked registry
artifacts. Runtime connections, OAuth, and browser rendering require separate
review and evidence.

Source references: [install script](https://github.com/modelcontextprotocol/inspector/blob/2e90a628e6296c62e4bef942afbb43d3faa4baf4/scripts/install-clients.mjs),
[package layout](https://github.com/modelcontextprotocol/inspector/blob/2e90a628e6296c62e4bef942afbb43d3faa4baf4/package.json),
[secret-store selection](https://github.com/modelcontextprotocol/inspector/blob/2e90a628e6296c62e4bef942afbb43d3faa4baf4/core/auth/node/secret-store-selection.ts).

## Advisory check and disposition

On September 19, 2026, isolated
`npm audit --package-lock-only --ignore-scripts --json` returned exit 0,
audit report version 2, an empty `vulnerabilities` object, and zero info, low,
moderate, high, or critical advisories. npm reported 226 total dependencies.
This is a time-bound registry advisory result, not proof that the packages
are vulnerability-free or that runtime behavior is qualified.

The locked graph is ready for the separately reviewed script-disabled
installation and isolated host harness. Apps rendering, Skills activation,
authorization, localization, and tenant isolation remain unqualified by this
dependency-only review. No official conformance or release gate changes.
