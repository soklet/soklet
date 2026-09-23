# Exact alpha.11 dependency repin proposal — 2026-09-23

Status: **prepared for owner review; not an approved release-toolchain disposition.**
The candidate-conformance gate remains `BLOCKED_TOOLCHAIN_SECURITY_REVIEW`.
This directory is a proposal. The committed release runner does not consume its
lockfile; this preparation branch stages a runner integration for review.

## Immutable inputs and output

| Input or output | Identity |
| --- | --- |
| Upstream source | `modelcontextprotocol/conformance` commit `a983ba93c91e0bb31d0b6849eeb52f0ad1083107` (`0.2.0-alpha.11`) |
| Original `package.json` | SHA-256 `f699ac5e56ffeaad1090ee26e126c0d9f9d68e7fad6db30923d30fd7b429c640`; unchanged |
| Original upstream `package-lock.json` | SHA-256 `8c30fe8f15735bc4660c682225b12ec84bbd08c22e839127445d06b5476c4945` |
| [Proposed `package-lock.json`](package-lock.json) | SHA-256 `4bbf44df937f30f99f56dcb359ec5ca67c8200241b279f49f25d4b646e38fa1f` |
| Build toolchain | checksum-pinned Node `26.5.0`, npm `11.17.0`, Linux x64 |
| Built `dist/index.js` | SHA-256 `b8355fba248c019b667a9c16289748ebfd85ca2668890812054b7997b8df8f3b`, identical to the existing reviewed CLI |

The proposed lock was produced in an isolated checkout of the exact upstream
commit with `npm audit fix --package-lock-only --ignore-scripts --no-fund`.
`package.json`, source, scenario manifests, and protocol schema were not
changed. The lock has 43 version changes among existing package records,
21 added records (including optional platform bindings), and no removals.
Review the entire lock diff and each new integrity value before approval.

The 12 changed runtime package records are:

| Package | Original | Proposed |
| --- | --- | --- |
| `@hono/node-server` | `1.19.14` | `1.19.17` |
| `body-parser` | `2.2.2` | `2.3.0` |
| `es-object-atoms` | `1.1.1` | `1.1.2` |
| `fast-uri` | `3.1.0` | `3.1.8` |
| `hasown` | `2.0.2` | `2.0.4` |
| `hono` | `4.12.15` | `4.13.8` |
| `ip-address` | `10.2.0` | `10.7.2` |
| `qs` | `6.15.0` | `6.16.0` |
| `side-channel` | `1.1.0` | `1.1.1` |
| `side-channel-list` | `1.0.0` | `1.0.1` |
| `type-is` | `2.0.1` | `2.1.0` |
| `undici` | `7.25.0` | `7.29.1` |

The other 31 version changes are development dependencies. The reviewed
selected CLI path uses Undici directly; its update is material to the risk
disposition even though the bundled CLI bytes remained unchanged.

## Audit and executable review

Fresh audits used the exact lockfiles with the pinned Node/npm version on
September 23. Counts are affected **packages**, not advisory counts:

| Lockfile | Critical | High | Moderate | Low | Total |
| --- | ---: | ---: | ---: | ---: | ---: |
| Original alpha.11, all dependencies | 0 | 9 | 5 | 2 | 16 |
| Proposed repin, all dependencies | 0 | 0 | 0 | 1 | 1 |
| Proposed repin, `--omit=dev` | 0 | 0 | 0 | 0 | 0 |

The remaining low finding is `esbuild` `0.27.4`,
[GHSA-g7r4-m6w7-qqqr](https://github.com/advisories/GHSA-g7r4-m6w7-qqqr),
which concerns the Windows development server. It is a development dependency
of `tsx` `4.21.0`, whose declared `esbuild` range is `~0.27.0`; the selected
Linux server-conformance path runs the built CLI and does not start that
development server. Do not override the declared range without a separate
compatibility review. The residual low issue still needs an explicit owner
disposition; a zero-runtime-audit result is not a blanket security waiver.

With the exact proposed lock, `npm ci --ignore-scripts --no-audit --fund=false`
installed 306 packages on Linux x64, then the reviewed explicit `npm run build`
succeeded. No install scripts were run. The built CLI hash remained identical
to the original reviewed alpha.11 hash above, and its `list --server` output
passed Soklet's frozen 50-scenario/46-selected-scenario inventory verifier.
The unchanged CLI hash does not prove every installed dependency path is
equivalent; candidate conformance must be replayed using the repinned install.

Raw evidence is retained with the proposal; the SHA-256 values also verify the
local command outputs:

| File | SHA-256 |
| --- | --- |
| [`evidence/baseline-audit.json`](evidence/baseline-audit.json) | `39c56e971802ced3d2754432be981d2decd68e1fb93405de7c2aec981abf7797` |
| [`evidence/repin-audit.json`](evidence/repin-audit.json) | `8e574228e423119cc1c7e8fdf4cc97ddf17198a32d22ac56210d4248966d1dfc` |
| [`evidence/runtime-audit.json`](evidence/runtime-audit.json) | `acf01fa25924e4778d3248d318627d8b26534b8c429dc29ce8ce0eaf79b584ed` |
| [`evidence/linux-build.log.gz`](evidence/linux-build.log.gz) | `3180aea08451061a53595a970f9b7f26ba210b03454d02a5ece950a2530de097` (deterministic gzip; uncompressed log `e3caa1dac0ffb2eb86175db662e12248f6d35b1cd58c6eb8fd68979546a01093`) |
| [`evidence/overlay-simulation-build.log.gz`](evidence/overlay-simulation-build.log.gz) | `3e5b0a31523a79e6e57d8839e83c28f8daa785732b526db0a1a14eddf9ca23de` (deterministic gzip; uncompressed log `0fb5462095354de4640a6cb7324e0ed33d10fa006e144cf076256c7fca06323b`) |
| [`evidence/server-list.txt`](evidence/server-list.txt) | `8e5868988d76ba5b1d806b395943d22ecdd746ce5140e99154aabff76b1a8f03` |

## Staged integration and acceptance work

The current verifier pins the **original** upstream lock as part of the
279-file source-tree digest. Replacing it in the upstream checkout without
changing the release runner would fail exact-source verification. The staged
[`apply-repin.mjs`](apply-repin.mjs) and
[`validate-release-candidate.sh`](../../../../scripts/validate-release-candidate.sh)
change implement a fail-closed overlay path: verify the clean upstream commit,
original source tree and lock first; verify the candidate-tracked overlay's
hash; use only that overlay for `npm ci`; build; restore the original lock;
then verify the full original source tree, built CLI, and clean tracked files.
A local end-to-end simulation passed using a clean exact alpha.11 clone:
`prepare`, pinned Linux x64 `npm ci --ignore-scripts` and `npm run build`,
`restore`, `self-test.mjs --suite-dir`, and `runner-self-test.mjs`. Negative
simulations rejected a
dirty upstream checkout before changing its lock and rejected a tampered
repinned lock before restoration. The immutable candidate gate has
not run, and its eventual evidence must record the overlay identity and audit.
Do not silently change the original upstream source pin.
The reviewed version-transition and lifecycle-bound inventories have now been
amended for this exact proposal. The version inventory includes the overlay
lock and baseline audit; its four incidental old-line tokens in third-party
package versions are bound to the exact reviewed lock hash and line anchors.
The lifecycle inventory classifies this review note as discovery-only and
reseals the line-addressed census. The final-stage version check, its fixture
self-test, and the lifecycle inventory's 134 named cases pass on this branch.
The release-validation evidence self-test also passed with local loopback access.
Both inventory diffs and their independent verifier pins need review with any
accepted integration. These checks do not constitute an immutable candidate
conformance result.

Separately, the P0-C upstream diagnostic mismatch recorded in
[`P0C_CHECK_DISPOSITION_2026-09-22.md`](../../P0C_CHECK_DISPOSITION_2026-09-22.md)
still needs its runner integration and exact candidate replay. This dependency
proposal does not resolve that mismatch, qualify Apps/Skills, refreeze the MCP
API, or constitute an immutable candidate PASS.
