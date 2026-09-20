# Isolated Inspector auth-patch experiment

This is a local A/B experiment against the exact Inspector 2.7.0 installation
already pinned by the ordinary Inspector harness. It is **not** an official
dependency update, a Soklet change, a released-host qualification, or an
end-to-end OAuth test. The original `inspector-auth` diagnostic and its failed
host evidence remain unchanged.

## Patch boundary

`patch.mjs` copies package/lock/node_modules into a new directory, checks the
original installed-tree identity before and after, and changes precisely one
compiled web-backend file. All other paths, types, link targets and file bytes
must match; corresponding runtime files must not share inodes. No npm install,
lifecycle scripts, original installation edits or upstream writes are involved.

The patch changes only the 403 challenge decision: OAuth recovery requires an
explicit, structurally parsed Bearer `insufficient_scope` challenge. A plain
policy denial remains the original HTTP response, including its readable body.
Malformed or ambiguous 403 challenges do not initiate recovery. The existing
401 parser/recovery path is left unchanged. The 403 parser bounds header size,
recognizes challenge/parameter boundaries outside quoted strings, and avoids
mistaking Bearer text inside another scheme's quoted parameter for a challenge.
It is deliberately conservative and is not an upstream-approved general-purpose
authentication parser.
In particular, headers longer than 16,384 characters, multiple Bearer
challenges, or empty comma-list elements are rejected. The latter two are
conservative restrictions, not claims that all such HTTP header lists are
invalid. The positive controls establish compatibility only for their tested
challenge shapes; broader upstream parser compatibility remains review work.

## Reproduce

From the core repository, substitute explicit existing paths and a **new** copy
and result directory:

```sh
node --input-type=module -e 'import {prepareCopy} from "./verification/interoperability/inspector-auth-patch/patch.mjs"; console.log(JSON.stringify(prepareCopy("/path/to/original", "/path/to/new-copy"), null, 2));'
SOKLET_INSPECTOR_AUTH_DEPENDENCIES=/path/to/original node --test verification/interoperability/inspector-auth-patch/*-self-test.mjs
node verification/interoperability/inspector-auth-patch/run.mjs --original-dependencies /path/to/original --dependencies /path/to/new-copy --browser '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome' --work-dir /path/to/new-results
```

The runner requires the pinned Node runtime and Chrome distribution shape. It
records and rechecks the complete browser distribution, sources and original /
patched dependency identities. The tests load only the pinned bundle's auth
functions in a VM; they do not execute its host startup code.

## A/B matrix and interpretation

Each installation runs the same seven cases with a fresh host, browser, static
bearer, readonly in-memory secret store and private storage:

| Response / settings | Original | Patched |
| --- | --- | --- |
| No subscription advertisement | No subscription or OAuth | No subscription or OAuth |
| Subscription JSON-RPC error over HTTP 200 | Subscription retries, no OAuth | Subscription retries, no OAuth |
| Plain policy 403 | False recovery reproduced | Denial retained, no OAuth |
| Plain 403 + insufficient-scope `throw` | False recovery reproduced | Denial retained, no OAuth |
| Plain 403 + auto refresh disabled | False recovery reproduced | Denial retained, no OAuth |
| 401 with Bearer `invalid_token` | Recovery initiated | Recovery initiated |
| 403 with Bearer `insufficient_scope` | Recovery initiated | Recovery initiated |

Positive controls stop at local refusal of metadata/registration requests. They
prove that recovery still starts, not successful login, token renewal, consent,
or scope escalation. No account or external authorization server is used. The
fixture is not Soklet; Apps and Skills are disabled and no tool is invoked.

All cases require the same six-second observation, actual DOM connect and
disconnect, one discovery and two catalog requests, zero browser exceptions,
zero unexpected browser requests, authenticated/origin-restricted host API,
gap-free bounded HTTP trace, clean process exits, removal of private state and
unchanged inputs. Real challenges and original false recovery must cause local
OAuth refusal after the first denied subscription, including a registration
attempt. Patched plain policy cases must produce **zero** OAuth requests. The
same denied subscriptions may still be retried; retry policy is not patched.

`LOCAL_PATCH_VALIDATED_WITH_CONTROLS` never means a passing released Inspector
or Apps host. Receipts always set `hostQualification:false` and
`candidateEvidence:false`. The old runner is not given an override to accept a
different dependency tree; this explicitly separate experiment carries its own
patch provenance and A/B adjudication.

## Lifecycle / evidence limits

The copied diagnostic retains the established host/browser/process/CDP bounds:
60-second host, 45-second browser, 10-second acquisition/startup, 6-second fixed
observation, 5-second DOM disconnect, 3-second graceful exit, then supervised
2-second TERM and KILL grace. Cancellation prevents subsequent case scheduling.
Late browser requests stay paused during intentional closure. The fixture has
64 KiB bodies, 64 HTTP requests, 32 connections and 10-second exchange guards.
Arbitrary output, raw credentials, request IDs, private bodies and browser
storage are never archived. Browser policy blocks non-allowlisted requests;
all host-triggered OAuth destinations in these fixed controls are loopback and
are refused by the fixture. This is not an OS-level network sandbox.

The copy is retained as a local experimental runtime; per-case private profiles
and configuration are removed. Source-level tests are not a replacement for
the real fourteen-case browser matrix. Any future upstream patch, dependency
repin or Apps-host qualification requires a separate review and receipt.
