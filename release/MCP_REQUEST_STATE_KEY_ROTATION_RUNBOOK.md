# MCP request-state key-rotation runbook

This runbook covers live rotation of Soklet's built-in production MCP request-
state key ring. It applies only to servers built with
`McpProtectionConfig.withKeyRing(...)`. Development-ephemeral protection has
no durable key to rotate, and a custom protector owns its own rotation
procedure.

The safe routine sequence is:

```text
generate -> persist staged configuration -> stage everywhere
-> compare everywhere -> activate everywhere -> compare everywhere
-> persist activated roles -> wait for old state and sealing reservations
-> freeze starts -> persist final configuration -> remove everywhere
-> verify and release the start freeze
```

Do not begin with `rotateTo(...)` in a fleet. It can make a node seal under a
key that peers do not yet possess. Stage the key on every node first.

## Guarantees and boundaries

Every Soklet server owns an independent live copy of its configured ring.
Soklet provides an atomic publication point on one server; it does not provide
a fleet transaction, key distribution, orchestration, durable configuration,
or traffic coordination.

On one server, stage, activation, snapshots, seal reservations, and removal
serialize on the protection-control lock. Successful activation constructs the
new sealer context before publication, then atomically:

- promotes the staged key to active;
- demotes the former active key to verification-only; and
- publishes a fresh activation prefix, epoch zero, and invocation counter zero.

A seal that reserved the former key before this publication may finish under
that key. Every seal reservation after publication uses the new context.
Removal rejects a former key until its already-reserved seals have drained.
An opener that copied a key before removal may finish; a later opener cannot
acquire the removed key.

The live control plane is memory-local. Listener stop/start does not reset it,
but process restart, replacement, or autoscaling reconstructs the ring from
durable deployment configuration. The deployment configuration must therefore
advance with each phase, or an old role assignment can return after restart.

## Preconditions

Before changing any node:

1. Generate a new purpose-specific key with a cryptographically secure random
   source and at least 256 bits of entropy. Use a new 1–64-byte ASCII HTTP-token
   ID and new material. Never reuse the trace-correlation key.
2. Store the new secret in the normal durable secret manager. Ensure every
   current and newly starting node can receive the same bytes under the same
   ID. Never copy secret bytes into an operator log or evidence record.
3. Record the configured maximum request-state lifetime. The default is 15
   minutes. Choose an explicit additional margin for deployment duration and
   clock skew.
4. Inventory every serving, standby, canary, replacement, and autoscaling
   group. A node omitted from staging can fail to open new state or can later
   reintroduce an old active role.
5. Decide how restarts and autoscaling are controlled during the transition.
   Prefer deploying durable configuration with the new key verification-only
   before live activation.
6. Capture a baseline from every node with
   `McpServer.getProtectionControl().getKeyRingSnapshot()`. The optional must
   be present and every node must agree on active ID, sorted verification IDs,
   fingerprint version/profile, and fingerprint value.

The fingerprint covers all key IDs, roles, profile identifiers, and key
material without exposing raw material. It proves ring equality, not key
provenance, node identity, readiness, or reservation drain.

## Phase 1: stage everywhere

On each node, call:

```java
McpProtectionControl control = server.getProtectionControl();
control.stageVerificationKey(newKey);
```

Staging a byte-identical key under an existing active or verification-only ID
is an idempotent no-op. The call rejects:

- the same ID with different material;
- the same material under another protection-key ID; or
- material shared with the active trace-correlation key.

A rejected call leaves the ring unchanged. Stop the rollout and correct the
node or secret distribution; do not activate on any node while staging is
incomplete.

After every stage call succeeds, collect a fresh snapshot from every node.
Require all of these exact conditions:

- the former active ID is still active;
- the new ID is verification-only;
- the complete sorted verification-ID set agrees; and
- the complete ring fingerprint agrees.

A matching ID with different bytes will not produce a matching fingerprint.
Do not treat ID agreement alone as convergence.

## Phase 2: activate everywhere

Once every node has the new verification key, call on each node:

```java
control.activateStagedKey(newKeyId);
```

Activating the already-active ID is an idempotent no-op. An unknown ID is
rejected. If activation-prefix generation or key derivation fails, activation
throws without changing that node's old ring or sealer context.

The fleet is intentionally mixed while this loop is in progress. That is safe
only because every node already holds both keys: nodes may seal under different
active keys, but all nodes can open both. Continue activation or initiate the
rollback below; do not remove either key.

Record the time of the last successful activation. Then collect a fresh
snapshot everywhere and require:

- the new ID is active;
- the former active ID is verification-only;
- the complete sorted verification-ID set agrees; and
- the complete ring fingerprint agrees.

Update durable deployment configuration so new and restarted nodes use the
new key as active and retain the former key verification-only. Verify that the
replacement/autoscaling path produces the same snapshot before allowing an
uncontrolled restart.

## Phase 3: overlap and drain

Keep the former key verification-only until both conditions are satisfied:

1. At least the configured maximum request-state lifetime, plus the chosen
   rollout/clock margin, has elapsed since the last successful activation.
   This lets every state emitted under the former key expire.
2. No sealing reservation under the former key remains.

Soklet intentionally exposes no reservation counter or passive drain metric.
The authoritative drain check is the removal call itself. A matching ring
fingerprint does not prove drain. Quiescing traffic can contain additional work
but does not release an existing reservation: its seal must complete or fail,
or the owning process must be retired under the deployment's service policy.

## Phase 4: remove everywhere

First freeze process starts, restarts, replacements, and autoscaling. Update
the durable startup ring to remove the former key, but do not release that
freeze until live removal and convergence complete. This ordering prevents a
restart from reintroducing the retired key while removal is in progress.

On each node, call:

```java
Boolean removed = control.removeVerificationKey(formerKeyId);
```

Interpret the result exactly:

- `true`: the verification-only key was removed and Soklet cleared its owned
  live key bytes;
- `false`: the ID was already absent, so the retry is complete on that node;
- `McpKeyInUseException`: a sealing reservation under that key remains; leave
  the key installed, let the seal complete or fail (or retire a stuck process),
  and retry; or
- `IllegalArgumentException`: the ID is active, indicating unexpected role
  state; stop and reconcile rather than removing a different key.

After removal completes everywhere, collect another snapshot and require the
former ID to be absent and the remaining complete fingerprint to agree. Keep
the general start freeze, permit one controlled replacement node to start from
the final durable configuration, and require the same final snapshot. Release
the freeze only after that check passes.

Removal is logical revocation of Soklet's server-owned copy. It does not erase
caller objects, environment variables, secret-manager versions, JVM/OS copies,
backups, or operator records. Retire those copies under the deployment's
normal secret-destruction policy.

## Rollback before removal

Rollback remains lossless while the former key is installed. On every node,
activate the former ID:

```java
control.activateStagedKey(formerKeyId);
```

On a node that was never activated, this is an idempotent already-active
operation. On an activated node, the former key is verification-only and is
promoted with a fresh sealer activation prefix; the new key is demoted to
verification-only. Already-reserved or issued state under either key remains
openable.

After rollback, require the former ID active, the new ID verification-only,
and complete fingerprint convergence everywhere. Restore durable role
configuration. If the new key will be removed, wait from the last rollback for
the maximum state lifetime plus margin, then follow Phase 4 with the new key as
the removal target, including its durable-final-configuration and start-freeze
ordering.

Once a key has been removed, rollback to it requires securely restaging its
material. Future opens of outstanding state using it are already invalid on
that node, although an opener that copied the key before removal may finish.
Routine rollback must therefore occur before removal.

## Emergency revocation

Emergency revocation trades continuation availability for containment. Do not
wait for the maximum state lifetime when a key is believed compromised.

1. Quiesce or tightly control request-state-producing traffic.
2. Stage a trusted replacement on every node and prove snapshot/fingerprint
   convergence.
3. Activate the trusted key everywhere. If the currently active key is the
   compromised new key and the former key remains trusted, this can be the
   rollback procedure above.
4. Freeze starts, restarts, replacements, and autoscaling. Remove the
   compromised key from durable startup configuration before allowing any new
   process to start.
5. Retry live removal of the now-verification-only compromised key until every
   already-reserved seal completes or fails; retire a stuck process rather than
   restoring the compromised key.
6. Complete removal everywhere without waiting for old state to expire, prove
   live/final-startup convergence, release the start freeze, and revoke/destroy
   all external copies.

Existing envelopes under the removed key then fail with the same sanitized
HTTP 400 / JSON-RPC `-32602` invalid-state result as tampering. This deliberate
invalidation is the containment cost. A compromised active key cannot be
removed directly; a trusted key must become active first.

## Failure and recovery table

| Observation | Meaning | Operator action |
| --- | --- | --- |
| Stage rejects duplicate ID/material | Node or secret input differs from the planned ring | Stop; compare secret-manager version and ID without logging bytes |
| Activation rejects unknown ID | Staging did not complete on that node | Stage and recompare before retrying |
| Activation initialization fails | New sealer context was not published; old state remains active | Correct entropy/provider health, then retry or roll back fleet |
| Snapshots or fingerprints differ | Nodes have different roles, IDs, or material | Stop progression; reconcile the complete ring |
| Removal throws `McpKeyInUseException` | A seal under the target key is still reserved | Keep the key; let the seal finish/fail or retire a stuck process, then retry |
| Removal says key is active | Node missed activation or reverted after restart | Reconcile live and durable role state |
| Old state fails before planned removal | Key missing, wrong material, binding mismatch, expiry, or tampering | Treat as invalid state; inspect secret-free rollout evidence, not client bytes |
| A restarted node shows old roles | Durable configuration lagged live rotation | Remove it from traffic, update configuration, and repeat convergence checks |

`rotateTo(...)` is retry-safe for a byte-identical already-active key and can
atomically stage/activate on one server. It is a convenience for a controlled
single instance, not a substitute for the fleet stage barrier.

## Operator evidence

Retain one bounded record per node and phase containing only:

- deployment/candidate identity and node identity;
- phase and timestamp;
- mutation attempted and success/failure category;
- active key ID and sorted verification-only IDs;
- fingerprint version, profile, and value;
- configured maximum state lifetime and chosen margin;
- last successful activation time; and
- removal attempt count and final boolean outcome.

Never record key bytes, secret-manager payloads, request-state strings,
plaintext, nonces, epochs, authorization partitions, or exception internals.
Fingerprints reveal configuration equality and can change at every rotation;
keep them out of metric dimensions and per-request logs and retain them only
under a bounded operational policy.

## Executable evidence

The three primary rotation contracts required by the 4.0 closure plan are:

- `McpSecurityControlsTests#protectionMutationsAreRetrySafeAndRejectedMutationsAreAtomic` — stage/activate/rotate/remove retry behavior and unchanged snapshots after rejection;
- `McpSecurityControlsTests#inFlightSealBlocksFormerKeyRemovalButNotRotation` — old reservation completion, post-publication use of the new key, removal refusal while reserved, and invalidation after removal; and
- `McpSecurityControlsTests#concurrentProtectionRotationPublishesOnlyCompleteSnapshots` — concurrent readers see only complete old-role or new-role snapshots, including repeated reactivation of the former key.

Supplemental contracts are:

- `McpSecurityControlsTests#stagedActivationWipesRetiredSealerContextButKeepsOldKey`;
- `McpSecurityControlsTests#failedActivationPreservesTheExistingContextAndKeyRing`;
- `McpSecurityControlsTests#retiredServerOwnedKeysAreWipedWithoutMutatingCallers`;
- `McpSecurityControlsTests#protectionControlsAreIndependentPerServer`; and
- `McpProtectionTraceDiagnosticsPublicRuntimeTests#liveRotationsChangeOnlyFreshSnapshotsAcrossStopAndRestart`.

These tests prove node-local publication and race behavior. They do not prove
that an operator staged every real fleet node, updated durable secrets, waited
the chosen interval, or destroyed external copies; the bounded operator record
above is required for those deployment facts.

The implementation, concurrency tests, and this operational sequence have
been reviewed for internal consistency. They are not a claim of third-party
security audit; independent security review remains recommended before general
availability.
