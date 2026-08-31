#!/usr/bin/env node

import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  buildLifecycleScopeObservations,
  buildReviewedLifecycleScopeRows,
  collectLifecycleBoundHarnessEvidence,
  INVENTORY_PATH,
  javascriptGuards,
  LifecycleBoundHarnessInventoryError,
  soakProfiles,
  standardJunitGuard,
  verifyLifecycleBoundHarnessInventory,
  verifyLifecycleHostWiring,
  verifyNoSurvivingLegacySites,
  verifyRequiredExecutingRows,
  verifySpecialSourceWiring,
} from './verify-lifecycle-bound-harness-inventory.mjs';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const INVENTORY = JSON.parse(readFileSync(join(ROOT, INVENTORY_PATH), 'utf8'));
const EVIDENCE = collectLifecycleBoundHarnessEvidence({ root: ROOT });
let cases = 0;

function run(name, body) {
  body();
  cases += 1;
  process.stdout.write(`PASS ${name}\n`);
}

function expectFailure(body, pattern) {
  assert.throws(body, (error) =>
    error instanceof LifecycleBoundHarnessInventoryError
      && pattern.test(error.message),
  `Expected LifecycleBoundHarnessInventoryError matching ${pattern}`);
}

function clone(value) { return structuredClone(value); }

function rowByName(document, name) {
  const matches = document.lifecycleScopes.filter((row) =>
    row.source.scopeName === name);
  assert.equal(matches.length, 1, `Expected one lifecycle row named ${name}`);
  return matches[0];
}

function verifyDocument(document) {
  const temporary = mkdtempSync(join(tmpdir(), 'soklet-lifecycle-inventory-'));
  const path = join(temporary, 'inventory.json');
  try {
    writeFileSync(path, `${JSON.stringify(document)}\n`, 'utf8');
    return verifyLifecycleBoundHarnessInventory({ inventoryPath: path, root: ROOT });
  } finally {
    rmSync(temporary, { force: true, recursive: true });
  }
}

function sourceTexts(...paths) {
  return new Map(paths.map((path) =>
    [path, readFileSync(join(ROOT, path), 'utf8')]));
}

function syntheticScopes(source) {
  return buildLifecycleScopeObservations(new Map([
    ['src/test/java/com/soklet/SyntheticLifecycleTests.java', source],
  ]));
}

function syntheticOverrideMap(scopes, reviewsByName) {
  return new Map(scopes.filter((scope) => reviewsByName[scope.scopeName])
    .map((scope) => [
      `${scope.path}#${scope.scopeName}#${scope.scopeKind}`,
      {
        fileSha256: scope.fileSha256,
        scopeSha256: scope.scopeSha256,
        ...reviewsByName[scope.scopeName],
      },
    ]));
}

function reviewedSyntheticRows(scopes, reviewsByName) {
  return buildReviewedLifecycleScopeRows(scopes, {
    requireRegistryCompleteness: false,
    reviewedOverrides: syntheticOverrideMap(scopes, reviewsByName),
  });
}

run('positive production closure', () => {
  const result = verifyLifecycleBoundHarnessInventory({ root: ROOT });
  assert.equal(result.lifecycleScopes, INVENTORY.lifecycleScopes.length);
  assert.ok(result.discoveryCandidates > 4_000);
});

run('unresolved lifecycle row rejected', () => {
  const document = clone(INVENTORY);
  document.lifecycleScopes.find((row) => row.source.hasExecution)
    .review.closureStatus = 'PENDING';
  expectFailure(() => verifyDocument(document), /semantic closure rows/u);
});

run('unknown lifecycle classification and action rejected', () => {
  for (const [field, value] of [
    ['classification', 'TRUST_ME'],
    ['requiredAction', 'APPROVE_ANYWAY'],
  ]) {
    const document = clone(INVENTORY);
    document.lifecycleScopes.find((row) => row.source.hasExecution)
      .review[field] = value;
    expectFailure(() => verifyDocument(document), /semantic closure rows/u);
  }
});

for (const [name, reduced] of [
  ['cursorFailuresPreserveOpaqueBytesAndCollapseToOneNeutralError', 2],
  ['successfulChargesAreRetainedAfterEveryDownstreamFailure', 2],
  ['rejectedAndIrrelevantWorkNeverInvokesTheProvider', 1],
  ['testLargeRequestBodyMemoryHandling', 1],
]) {
  run(`source-bound generation count ${name}`, () => {
    const document = clone(INVENTORY);
    rowByName(document, name).review.generationCount = reduced;
    expectFailure(() => verifyDocument(document), /semantic closure rows/u);
  });
}

run('independent reviewed generation registry rejects lowered authority', () => {
  const rows = clone(INVENTORY.lifecycleScopes);
  rowByName({ lifecycleScopes: rows },
    'cursorFailuresPreserveOpaqueBytesAndCollapseToOneNeutralError')
    .review.generationCount = 2;
  expectFailure(() => verifyRequiredExecutingRows(rows),
    /Required reviewed lifecycle generation topology drifted/u);
});

run('manual generation file hash is independent authority', () => {
  const observations = clone(EVIDENCE.lifecycleScopeObservations);
  observations.find((source) => source.scopeName
    === 'cursorFailuresPreserveOpaqueBytesAndCollapseToOneNeutralError')
    .fileSha256 = '0'.repeat(64);
  expectFailure(() => buildReviewedLifecycleScopeRows(observations),
    /override is stale/u);
});

run('configured policy understatement rejected', () => {
  const document = clone(INVENTORY);
  const row = document.lifecycleScopes.find((candidate) =>
    candidate.review.phasePolicy.startupMillis === 5_000);
  row.review.phasePolicy.forcedShutdownMillis = 0;
  expectFailure(() => verifyDocument(document), /semantic closure rows/u);
});

run('helper policy body drift invalidates full-file proof', () => {
  const observations = clone(EVIDENCE.lifecycleScopeObservations);
  observations.find((source) => source.scopeName
    === 'forceResponsiveHandlerIsInterruptedOnlyAfterTheGraceDeadline')
    .fileSha256 = 'f'.repeat(64);
  expectFailure(() => buildReviewedLifecycleScopeRows(observations),
    /override is stale/u);
});

run('InternalLifecyclePolicy Optional.empty cannot be laundered finite', () => {
  const document = clone(INVENTORY);
  const row = rowByName(document,
    'mixedIncompleteAndNotStartedTerminalTraceIsOrderedAndComplete');
  row.review.phasePolicy.startupMillis = 1;
  row.review.phasePolicy.controlledStartupMillis = null;
  row.review.phasePolicy.mode = 'SOURCE_CONFIGURED_FINITE_WITH_PROOF';
  expectFailure(() => verifyDocument(document), /semantic closure rows/u);
});

run('cleanup is complete-branch-only and report is exact', () => {
  const row = INVENTORY.lifecycleScopes.find((candidate) =>
    candidate.source.cleanupConfigured && candidate.source.hasExecution
      && candidate.review.applicationCleanupMillis > 0);
  assert.ok(row);
  assert.equal(row.review.branchBoundsMillis.COMPLETE_CORE,
    row.review.lifecycleCoreBranchBoundsMillis.COMPLETE_CORE
      + row.review.controlJoinMillis + row.review.applicationCleanupMillis
        * row.review.applicationCleanupCount
      + row.review.terminalReportMillis);
  assert.equal(row.review.branchBoundsMillis.INCOMPLETE_CORE,
    row.review.lifecycleCoreBranchBoundsMillis.INCOMPLETE_CORE
      + row.review.controlJoinMillis + row.review.applicationCleanupMillis
        * row.review.incompleteBranchCleanupCount
      + row.review.terminalReportMillis);
  const document = clone(INVENTORY);
  rowByName(document, row.source.scopeName).review.terminalReportMillis += 1;
  expectFailure(() => verifyDocument(document), /semantic closure rows/u);
});

run('repeated application runs repeat cleanup and terminal reporting', () => {
  const name = 'startupFailureAndTimeoutRemainPrimaryWithIncompleteRollback';
  const row = rowByName(INVENTORY, name);
  assert.equal(row.review.generationCount, 2);
  assert.equal(row.review.applicationCleanupCount, 2);
  assert.equal(row.review.incompleteBranchCleanupCount, 0);
  assert.equal(row.review.terminalReportCount, 2);
  assert.equal(row.review.terminalReportMillis, 500);
  assert.equal(row.review.branchBoundsMillis.COMPLETE_CORE,
    row.review.lifecycleCoreBranchBoundsMillis.COMPLETE_CORE
      + row.review.controlJoinMillis
      + row.review.applicationCleanupMillis * 2 + 500);
  assert.equal(row.review.branchBoundsMillis.INCOMPLETE_CORE,
    row.review.lifecycleCoreBranchBoundsMillis.INCOMPLETE_CORE
      + row.review.controlJoinMillis
      + row.review.applicationCleanupMillis
        * row.review.incompleteBranchCleanupCount + 500);
  for (const field of ['applicationCleanupCount',
    'incompleteBranchCleanupCount', 'terminalReportCount']) {
    const document = clone(INVENTORY);
    rowByName(document, name).review[field] = field === 'incompleteBranchCleanupCount'
      ? 1 : 1;
    expectFailure(() => verifyDocument(document), /semantic closure rows/u);
  }
});

run('mixed repeated application branch retains prior complete cleanup', () => {
  const scopes = syntheticScopes(`
    import java.time.Duration;
    import org.junit.jupiter.api.Test;
    import org.junit.jupiter.api.Timeout;
    class SyntheticLifecycleTests {
      @Test @Timeout(120) void twoRuns() {
        SokletApplicationOptions options = SokletApplicationOptions.builder()
          .afterCompleteShutdown(Duration.ofSeconds(1), () -> {}).build();
        SokletApplication.run(null, options);
        SokletApplication.run(null, options);
      }
    }
  `);
  expectFailure(() => buildReviewedLifecycleScopeRows(scopes,
    { requireRegistryCompleteness: false }), /cleanup\/report multiplicities/u);
  const row = reviewedSyntheticRows(scopes, {
    twoRuns: {
      applicationCleanupCount: 2,
      incompleteBranchCleanupCount: 1,
      generation: {
        complete: 2, count: 2, incomplete: 1,
        mode: 'SEQUENTIAL', prior: 1,
      },
      terminalReportCount: 2,
    },
  })[0].review;
  assert.equal(row.branchBoundsMillis.COMPLETE_CORE, 98_500);
  assert.equal(row.branchBoundsMillis.INCOMPLETE_CORE, 99_500);
  assert.equal(row.totalComposedBoundMillis, 99_500);
});

run('local strict-fit equality rejected', () => {
  const scopes = syntheticScopes(`
    import java.time.Duration;
    import org.junit.jupiter.api.Test;
    import org.junit.jupiter.api.Timeout;
    class SyntheticLifecycleTests {
      @Test @Timeout(60) void exactEquality() {
        LifecyclePolicy.builder().startupTimeout(Duration.ofSeconds(30))
          .startupCancellationTimeout(Duration.ofSeconds(12))
          .gracefulShutdownDuration(Duration.ofSeconds(15))
          .forcedShutdownDuration(Duration.ofSeconds(3)).build();
        Soklet soklet = Soklet.fromConfig(null); soklet.start(); soklet.close();
      }
    }
  `);
  expectFailure(() => buildReviewedLifecycleScopeRows(scopes,
    { requireRegistryCompleteness: false }), /does not fit/u);
});

run('class method and default timeout precedence', () => {
  const scopes = syntheticScopes(`
    import org.junit.jupiter.api.Test;
    import org.junit.jupiter.api.Timeout;
    @Timeout(90) class SyntheticLifecycleTests {
      @Test void inherited() { Soklet s = Soklet.fromConfig(null); s.start(); }
      @Test @Timeout(value=2, unit=java.util.concurrent.TimeUnit.MINUTES)
      void method() { Soklet s = Soklet.fromConfig(null); s.start(); }
    }
    class DefaultGuardTests {
      @Test void defaults() { Soklet s = Soklet.fromConfig(null); s.start(); }
    }
  `);
  const byName = new Map(scopes.map((scope) => [scope.scopeName, scope]));
  assert.deepEqual([
    byName.get('inherited').outerTimeoutScope,
    byName.get('inherited').effectiveOuterTimeoutMillis,
    byName.get('method').outerTimeoutScope,
    byName.get('method').effectiveOuterTimeoutMillis,
    byName.get('defaults').outerTimeoutScope,
    byName.get('defaults').effectiveOuterTimeoutMillis,
  ], ['TYPE', 90_000, 'METHOD', 120_000, 'DEFAULT', 60_000]);
});

run('fully-qualified JUnit timeout obeys type and method precedence', () => {
  const scopes = syntheticScopes(`
    import org.junit.jupiter.api.Test;
    @org.junit.jupiter.api.Timeout(value=90,
      unit=java.util.concurrent.TimeUnit.SECONDS)
    class SyntheticLifecycleTests {
      @Test void typeGuard() { Soklet s = Soklet.fromConfig(null); s.start(); }
      @Test @org.junit.jupiter.api.Timeout(value=120,
        unit=java.util.concurrent.TimeUnit.SECONDS)
      void methodGuard() { Soklet s = Soklet.fromConfig(null); s.start(); }
    }
  `);
  const byName = new Map(scopes.map((scope) => [scope.scopeName, scope]));
  assert.deepEqual([
    byName.get('typeGuard').outerTimeoutScope,
    byName.get('typeGuard').effectiveOuterTimeoutMillis,
    byName.get('methodGuard').outerTimeoutScope,
    byName.get('methodGuard').effectiveOuterTimeoutMillis,
  ], ['TYPE', 90_000, 'METHOD', 120_000]);
});

run('short lifecycle guard rejected', () => {
  const scopes = syntheticScopes(`
    import org.junit.jupiter.api.Test;
    import org.junit.jupiter.api.Timeout;
    class SyntheticLifecycleTests {
      @Test @Timeout(10) void shortGuard() {
        Soklet s = Soklet.fromConfig(null); s.start(); s.close();
      }
    }
  `);
  expectFailure(() => buildReviewedLifecycleScopeRows(scopes,
    { requireRegistryCompleteness: false }), /shorter than 60 seconds/u);
});

run('comment and string annotations cannot change JUnit roles or guards', () => {
  const scopes = syntheticScopes(`
    import org.junit.jupiter.api.Test;
    /* @org.junit.jupiter.api.Timeout(1) */
    class SyntheticLifecycleTests {
      // @Disabled @Timeout(1) @TestFactory
      @Test void executes() {
        String fake = "@Disabled @Timeout(1) @TestFactory";
        String block = """
          @Disabled @Timeout(1) @TestFactory
          """;
        Soklet s = Soklet.fromConfig(null); s.start(); s.close();
      }
      // @Test
      void commentedTest() {
        Soklet s = Soklet.fromConfig(null); s.start(); s.close();
      }
    }
  `);
  assert.equal(scopes.length, 1);
  assert.equal(scopes[0].scopeName, 'executes');
  assert.equal(scopes[0].disabled, false);
  assert.equal(scopes[0].testFactory, false);
  assert.equal(scopes[0].effectiveOuterTimeoutMillis, 60_000);
  assert.equal(scopes[0].outerTimeoutScope, 'DEFAULT');
});

run('approved JUnit timeout configuration is exact and exclusive', () => {
  const path = 'src/test/resources/junit-platform.properties';
  const approved = 'junit.jupiter.execution.timeout.default = 60 s\n';
  assert.equal(standardJunitGuard(new Map([[path, approved]])).millis,
    60_000);
  for (const [candidatePath, candidateText, pattern] of [
    [path, `${approved}junit.jupiter.execution.timeout.mode = disabled\n`,
      /must contain exactly/u],
    [path, 'junit.jupiter.execution.timeout.default = 60 s',
      /must contain exactly/u],
    ['.mvn/maven.config', '-Djunit.jupiter.execution.timeout.default=120s',
      /Higher-precedence/u],
    ['pom.xml', '<configurationParameters>junit.jupiter.execution.timeout.test.method.default=120s</configurationParameters>',
      /Higher-precedence/u],
    ['.github/workflows/ci.yml', 'MAVEN_OPTS: -Djunit.jupiter.execution.timeout.testfactory.method.default=120s',
      /Higher-precedence/u],
    ['scripts/run-tests.sh', 'junit.jupiter.execution.timeout.mode=disabled',
      /Higher-precedence/u],
    ['junit-platform.properties', 'junit.jupiter.execution.timeout.default=120s',
      /Higher-precedence/u],
  ]) {
    const texts = new Map([[path, approved]]);
    texts.set(candidatePath, candidateText);
    expectFailure(() => standardJunitGuard(texts), pattern);
  }
});

run('routine CI excludes release-only lifecycle closure', () => {
  const ciPath = '.github/workflows/ci.yml';
  const releasePath = 'scripts/validate-release-candidate.sh';
  const pristine = sourceTexts(ciPath, releasePath);
  assert.deepEqual(verifyLifecycleHostWiring(pristine), { ciPath, releasePath });
  for (const command of [
    'node scripts/verify-lifecycle-bound-harness-inventory-self-test.mjs',
    'node scripts/verify-lifecycle-bound-harness-inventory.mjs',
  ]) {
    const texts = new Map(pristine);
    texts.set(ciPath, `${texts.get(ciPath)}\n      - name: Inject release-only check\n        run: ${command}\n`);
    expectFailure(() => verifyLifecycleHostWiring(texts),
      /Routine CI must not invoke release-only lifecycle closure checks/u);
  }
});

run('release lifecycle host is one exact ordered fail-closed block', () => {
  const ciPath = '.github/workflows/ci.yml';
  const releasePath = 'scripts/validate-release-candidate.sh';
  const pristine = sourceTexts(ciPath, releasePath);
  const selfTest = '\tnode "$lifecycle_bound_harness_self_test"';
  const verifier = '\tnode "$lifecycle_bound_harness_verifier"';
  const block = [
    '{',
    '\tnode "$version_transition_self_test"',
    '\tnode "$version_transition_verifier" --stage final',
    selfTest,
    verifier,
    '\tnode "$d1p_evidence_self_test"',
    '\tmvn -B -ntp -Dgpg.skip=true clean verify',
    '} 2>&1 | tee "$build_log"',
  ].join('\n');
  for (const mutate of [
    (text) => text.replace(`${selfTest}\n${verifier}`,
      `${verifier}\n${selfTest}`),
    (text) => text.replace(selfTest, `\t# ${selfTest.trim()}`),
    (text) => text.replace(selfTest, `${selfTest} || true`),
    (text) => text.replace(selfTest, `\techo ${selfTest.trim()}`),
    (text) => text.replace(selfTest, `\tignored=$(${selfTest.trim()})`),
    (text) => text.replace('set -euo pipefail', 'set +e'),
    (text) => `${text}\n${block}\n`,
  ]) {
    const texts = new Map(pristine);
    texts.set(releasePath, mutate(texts.get(releasePath)));
    expectFailure(() => verifyLifecycleHostWiring(texts),
      /release host|release-candidate lifecycle closure host/u);
  }
});

run('helper propagation repeated direct calls and literal loop', () => {
  const scopes = syntheticScopes(`
    import org.junit.jupiter.api.Test;
    class SyntheticLifecycleTests {
      @Test void repeated() { runOwner(); runOwner(); }
      @Test void looped() { for (int i=0; i<4; i++) { runOwner(); } }
      void runOwner() { Soklet s = Soklet.fromConfig(null); s.start(); s.close(); }
    }
  `);
  const byName = new Map(scopes.map((scope) => [scope.scopeName, scope]));
  assert.equal(byName.get('repeated').generationSiteCount, 2);
  assert.equal(byName.get('looped').generationSiteCount, 4);
  assert.ok(byName.get('repeated').propagatedHelperEvidence.length > 0);
});

run('enhanced-for lifecycle repetition cannot close as one generation', () => {
  const scopes = syntheticScopes(`
    import java.util.List;
    import org.junit.jupiter.api.Test;
    class SyntheticLifecycleTests {
      @Test void enhanced() {
        for (String ignored : List.of("a", "b", "c", "d")) runOwner();
      }
      void runOwner() { Soklet s = Soklet.fromConfig(null); s.start(); s.close(); }
    }
  `);
  const row = scopes.find((scope) => scope.scopeName === 'enhanced');
  assert.ok(row.unresolvedLifecycleRepetitionCount > 0);
  expectFailure(() => buildReviewedLifecycleScopeRows(scopes,
    { requireRegistryCompleteness: false }), /repetition topology/u);
});

for (const [label, source] of [
  ['direct', `
    import org.junit.jupiter.api.Test;
    class SyntheticLifecycleTests {
      @Test void recursive() { runOwner(3); }
      void runOwner(int n) { if (n <= 0) return; Soklet s = Soklet.fromConfig(null); s.start(); s.close(); runOwner(n - 1); }
    }
  `],
  ['mutual', `
    import org.junit.jupiter.api.Test;
    class SyntheticLifecycleTests {
      @Test void recursive() { first(3); }
      void first(int n) { if (n <= 0) return; Soklet s = Soklet.fromConfig(null); s.start(); s.close(); second(n - 1); }
      void second(int n) { if (n <= 0) return; first(n); }
    }
  `],
]) {
  run(`${label} recursive lifecycle helper requires reviewed topology`, () => {
    const scopes = syntheticScopes(source);
    assert.ok(scopes[0].unresolvedLifecycleRepetitionCount > 0);
    expectFailure(() => buildReviewedLifecycleScopeRows(scopes,
      { requireRegistryCompleteness: false }), /repetition topology/u);
  });
}

run('same-name overload propagates to outer test', () => {
  const scopes = syntheticScopes(`
    import org.junit.jupiter.api.Test;
    class SyntheticLifecycleTests {
      @Test void overloaded() { overloaded(1); overloaded(2); }
      void overloaded(int value) { Soklet s = Soklet.fromConfig(null); s.start(); s.close(); }
    }
  `);
  assert.equal(scopes.find((scope) => scope.scopeKind === 'TEST')
    .generationSiteCount, 2);
});

run('method reference and constructor evidence retained', () => {
  const scopes = syntheticScopes(`
    import org.junit.jupiter.api.Test;
    class SyntheticLifecycleTests {
      @Test void references() { Owner owner = new Owner(); Runnable r = owner::start; r.run(); owner.close(); }
      static final class Owner {
        final Soklet soklet;
        Owner() { this.soklet = Soklet.fromConfig(null); }
        void start() { soklet.start(); }
        void close() { soklet.close(); }
      }
    }
  `);
  const row = scopes.find((scope) => scope.scopeKind === 'TEST');
  assert.equal(row.hasExecution, true);
  assert.ok(row.propagatedHelperEvidence.some((proof) =>
    proof.scopeKind === 'CONSTRUCTOR'));
});

run('var owner and harness accessor lifecycle calls execute', () => {
  const scopes = syntheticScopes(`
    import org.junit.jupiter.api.Test;
    class SyntheticLifecycleTests {
      @Test void directVar() { var owner = Soklet.fromConfig(null); owner.start(); owner.close(); }
      @Test void accessor() {
        try (OwnerHarness harness = new OwnerHarness(Soklet.fromConfig(null))) {
          harness.owner().start(); harness.soklet().close();
        }
      }
      @Test void reusedResource() {
        OwnerHarness harness = new OwnerHarness(Soklet.fromConfig(null));
        try (harness) { harness.owner(); }
      }
      record OwnerHarness(Soklet owner) implements AutoCloseable {
        Soklet soklet() { return owner; }
        public void close() { owner.close(); }
      }
    }
  `);
  const byName = new Map(scopes.map((scope) => [scope.scopeName, scope]));
  for (const name of ['directVar', 'accessor', 'reusedResource']) {
    assert.equal(byName.get(name).hasExecution, true);
    assert.ok(byName.get(name).generationSiteCount >= 1);
  }
});

run('all named production execution invariants remain live', () => {
  const names = [
    'attachmentLosingShutdownFreezeReturnsBeforeTerminalAsExactNotStarted',
    'concurrentCloseCallsJoinOnceAndRestoreEntryInterrupt',
    'prematureTerminationBeforeReadinessNeverBecomesCloseUnexpected',
    'startRacingNewOriginShutdownWaitsForExactNotAttemptedResult',
    'coordinatorFreezesGateBeforeReadingEvidence',
    'stopBeforeRuntimeInstallAndBeforeMarkReadyWinsDeterministically',
    'sealedScopeRetainsRejectedMcpSessionUntilRollbackTerminates',
    'unknownHeaderOccurrencesAreExactRedactedAndMethodBoundedAcrossPolicies',
  ];
  for (const name of names) {
    const row = EVIDENCE.lifecycleScopeObservations.find((scope) =>
      scope.scopeName === name);
    assert.ok(row, name);
    assert.equal(row.hasExecution, true, name);
    assert.ok(row.generationSiteCount >= 1, name);
  }
  const sealedRollback = EVIDENCE.lifecycleScopeObservations.find((scope) =>
    scope.scopeName
      === 'sealedScopeRetainsRejectedMcpSessionUntilRollbackTerminates');
  assert.ok(sealedRollback.fixedControlWaitCount >= 6);
  assert.ok(sealedRollback.unresolvedFixedControlWaitCount > 0);
});

run('socket thread and stream methods are not lifecycle receivers', () => {
  const scopes = syntheticScopes(`
    import java.io.InputStream;
    import java.net.Socket;
    import org.junit.jupiter.api.Test;
    class SyntheticLifecycleTests {
      @Test void unrelated() throws Exception {
        Socket socket = new Socket(); socket.close();
        Thread thread = new Thread(); thread.start(); thread.join();
        InputStream stream = InputStream.nullInputStream(); stream.close();
      }
    }
  `);
  assert.equal(scopes.length, 0);
});

for (const [label, declarations, execution] of [
  ['cast receiver',
    'Object alias = Soklet.fromConfig(null);',
    '((Soklet) alias).start(); ((Soklet) alias).close();'],
  ['identity receiver',
    'Soklet owner = Soklet.fromConfig(null);',
    'identity(owner).start(); identity(owner).close();'],
  ['array receiver',
    'Soklet[] aliases = { Soklet.fromConfig(null) };',
    'aliases[0].start(); aliases[0].close();'],
]) {
  run(`${label} cannot launder lifecycle execution as construction-only`, () => {
    const scopes = syntheticScopes(`
      import org.junit.jupiter.api.Test;
      class SyntheticLifecycleTests {
        @Test void aliased() {
          ${declarations}
          ${execution}
        }
        static <T> T identity(T value) { return value; }
      }
    `);
    assert.equal(scopes[0].hasExecution, true);
    assert.ok(scopes[0].unresolvedLifecycleReceiverCount > 0);
    expectFailure(() => buildReviewedLifecycleScopeRows(scopes,
      { requireRegistryCompleteness: false }), /receiver aliasing is unresolved/u);
  });
}

run('unrelated accessor close does not trigger lifecycle receiver ambiguity', () => {
  const scopes = syntheticScopes(`
    import java.net.Socket;
    import org.junit.jupiter.api.Test;
    class SyntheticLifecycleTests {
      @Test void constructionOnly() throws Exception {
        Soklet owner = Soklet.fromConfig(null);
        Socket socket = new Socket(); identity(socket).close();
      }
      static <T> T identity(T value) { return value; }
    }
  `);
  assert.equal(scopes[0].hasExecution, false);
  assert.equal(scopes[0].unresolvedLifecycleReceiverCount, 0);
});

for (const [label, member] of [
  ['instance initializer', '{ Soklet s = Soklet.fromConfig(null); s.start(); s.close(); }'],
  ['static initializer', 'static { Soklet s = Soklet.fromConfig(null); s.start(); s.close(); }'],
  ['field initializer', 'Soklet field = Soklet.fromConfig(null);'],
]) {
  run(`${label} lifecycle execution fails closed`, () => {
    expectFailure(() => syntheticScopes(`
      import org.junit.jupiter.api.Test;
      class SyntheticLifecycleTests {
        ${member}
        @Test void constructs() { new SyntheticLifecycleTests(); }
      }
    `), /outside a parsed callable scope/u);
  });
}

for (const [label, wait] of [
  ['Thread.sleep', 'Thread.sleep(20_000);'],
  ['timed await', 'gate.await(20, java.util.concurrent.TimeUnit.SECONDS);'],
  ['Duration join', 'thread.join(java.time.Duration.ofSeconds(20));'],
  ['waitForEof helper', 'waitForEof(socket, 20_000);'],
  ['LockSupport.parkNanos',
    'java.util.concurrent.locks.LockSupport.parkNanos(20_000_000_000L);'],
  ['CompletableFuture.orTimeout',
    'future.orTimeout(20, java.util.concurrent.TimeUnit.SECONDS);'],
  ['CompletableFuture.completeOnTimeout',
    'future.completeOnTimeout(null, 20, java.util.concurrent.TimeUnit.SECONDS);'],
  ['connectWithRetry helper', 'connectWithRetry(socket, 20_000);'],
]) {
  run(`${label} participates in lifecycle arithmetic`, () => {
    const scopes = syntheticScopes(`
      import org.junit.jupiter.api.Test;
      class SyntheticLifecycleTests {
        @Test void boundedControl() throws Exception {
          Soklet s = Soklet.fromConfig(null); s.start();
          ${wait}
          s.close();
        }
      }
    `);
    assert.ok(scopes[0].fixedControlWaitMillis >= 20_000);
    expectFailure(() => buildReviewedLifecycleScopeRows(scopes,
      { requireRegistryCompleteness: false }), /does not fit/u);
  });
}

run('Duration toNanos control expressions retain their full bound', () => {
  const scopes = syntheticScopes(`
    import java.time.Duration;
    import java.util.concurrent.TimeUnit;
    import org.junit.jupiter.api.Test;
    class SyntheticLifecycleTests {
      static final Duration WAIT = Duration.ofSeconds(5);
      @Test void boundedControl() throws Exception {
        Soklet s = Soklet.fromConfig(null); s.start();
        gate.await(WAIT.toNanos(), TimeUnit.NANOSECONDS);
        s.close();
      }
    }
  `);
  assert.equal(scopes[0].fixedControlWaitMillis, 5_000);
  assert.equal(scopes[0].unresolvedFixedControlWaitCount, 0);
});

run('deadline poll helpers contribute the deadline instead of sleep intervals', () => {
  const scopes = syntheticScopes(`
    import java.util.concurrent.TimeUnit;
    import org.junit.jupiter.api.Test;
    class SyntheticLifecycleTests {
      @Test void boundedPoll() throws Exception {
        Soklet s = Soklet.fromConfig(null); s.start();
        awaitIdle(); s.close();
      }
      static void awaitIdle() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() - deadline < 0L) Thread.sleep(10L);
      }
    }
  `);
  assert.equal(scopes[0].fixedControlWaitMillis, 5_000);
  assert.equal(scopes[0].unresolvedFixedControlWaitCount, 0);
  assert.ok(scopes[0].fixedControlWaitSites.some((site) =>
    site.method === 'deadlinePoll' && site.composedMillis === 5_000));
});

run('do-while deadline polls contribute the whole deadline', () => {
  const scopes = syntheticScopes(`
    import java.util.concurrent.TimeUnit;
    import org.junit.jupiter.api.Test;
    class SyntheticLifecycleTests {
      @Test void boundedPoll() throws Exception {
        Soklet s = Soklet.fromConfig(null); s.start();
        awaitIdle(); s.close();
      }
      static void awaitIdle() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        do { Thread.sleep(5L); }
        while (System.nanoTime() - deadline < 0L);
      }
    }
  `);
  assert.equal(scopes[0].fixedControlWaitMillis, 5_000);
  assert.equal(scopes[0].unresolvedFixedControlWaitCount, 0);
});

run('an unrelated deadline cannot launder an unbounded polling loop', () => {
  const scopes = syntheticScopes(`
    import java.util.concurrent.TimeUnit;
    import org.junit.jupiter.api.Test;
    class SyntheticLifecycleTests {
      @Test void unboundedPoll() throws Exception {
        Soklet s = Soklet.fromConfig(null); s.start();
        awaitIdle(); s.close();
      }
      static void awaitIdle() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!ready) {
          if (deadline > 0L) Thread.onSpinWait();
          Thread.sleep(10L);
        }
      }
    }
  `);
  assert.ok(scopes[0].unresolvedFixedControlWaitCount > 0);
  expectFailure(() => buildReviewedLifecycleScopeRows(scopes,
    { requireRegistryCompleteness: false }), /fixed-control waits are unresolved/u);
});

run('symbolically repeated deadline helpers remain unresolved', () => {
  const scopes = syntheticScopes(`
    import java.util.List;
    import java.util.concurrent.TimeUnit;
    import org.junit.jupiter.api.Test;
    class SyntheticLifecycleTests {
      @Test void repeatedPoll() throws Exception {
        Soklet s = Soklet.fromConfig(null); s.start();
        for (String value : List.of("a", "b", "c", "d")) awaitIdle();
        s.close();
      }
      static void awaitIdle() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() - deadline < 0L) Thread.sleep(10L);
      }
    }
  `);
  assert.ok(scopes[0].unresolvedFixedControlWaitCount > 0);
  assert.ok(scopes[0].fixedControlWaitSites.some((site) =>
    site.method === 'repeatedHelper:awaitIdle' && site.unresolved));
  expectFailure(() => buildReviewedLifecycleScopeRows(scopes,
    { requireRegistryCompleteness: false }), /fixed-control waits are unresolved/u);
});

run('String join is not a lifecycle control wait', () => {
  const scopes = syntheticScopes(`
    import org.junit.jupiter.api.Test;
    class SyntheticLifecycleTests {
      @Test void unrelatedJoin() {
        String value = String.join(",", "a", "b");
        Soklet s = Soklet.fromConfig(null); s.start(); s.close();
      }
    }
  `);
  assert.equal(scopes[0].fixedControlWaitCount, 0);
  assert.equal(scopes[0].unresolvedFixedControlWaitCount, 0);
});

run('lifecycle-core join is not double-counted as a control wait', () => {
  const scopes = syntheticScopes(`
    import org.junit.jupiter.api.Test;
    class SyntheticLifecycleTests {
      @Test void lifecycleJoin() {
        Soklet s = Soklet.fromConfig(null); s.start();
        s.shutdown().toCompletableFuture().join();
        s.close();
      }
    }
  `);
  assert.equal(scopes[0].fixedControlWaitCount, 0);
  assert.equal(scopes[0].unresolvedFixedControlWaitCount, 0);
});

run('background join is deduplicated under its timed Future get', () => {
  const scopes = syntheticScopes(`
    import java.util.concurrent.Future;
    import java.util.concurrent.TimeUnit;
    import org.junit.jupiter.api.Test;
    class SyntheticLifecycleTests {
      @Test void boundedWorker() throws Exception {
        Soklet s = Soklet.fromConfig(null); s.start();
        Future<?> stopping = executor.submit(() ->
          s.shutdown().toCompletableFuture().join());
        releaseWorker();
        stopping.get(2, TimeUnit.SECONDS);
        s.close();
      }
    }
  `);
  assert.equal(scopes[0].unresolvedFixedControlWaitCount, 0);
  assert.equal(scopes[0].fixedControlWaitMillis, 2_000);
});

for (const [label, wait] of [
  ['Thread join', 'worker.join();'],
  ['CountDownLatch await', 'latch.await();'],
  ['CompletableFuture join', 'future.join();'],
]) {
  run(`zero-argument ${label} requires controlled-completion review`, () => {
    const scopes = syntheticScopes(`
      import org.junit.jupiter.api.Test;
      class SyntheticLifecycleTests {
        @Test void unboundedControl() throws Exception {
          Soklet s = Soklet.fromConfig(null); s.start();
          ${wait}
          s.close();
        }
      }
    `);
    assert.ok(scopes[0].fixedControlWaitCount > 0);
    assert.ok(scopes[0].unresolvedFixedControlWaitCount > 0);
    expectFailure(() => buildReviewedLifecycleScopeRows(scopes,
      { requireRegistryCompleteness: false }), /fixed-control waits are unresolved/u);
  });
}

for (const [label, wait] of [
  ['LockSupport.parkUntil',
    'java.util.concurrent.locks.LockSupport.parkUntil(deadline);'],
  ['awaitRuntime helper', 'awaitRuntime(server, expectedStreams);'],
  ['awaitCondition helper', 'awaitCondition(() -> ready);'],
]) {
  run(`${label} requires explicit control review`, () => {
    const scopes = syntheticScopes(`
      import org.junit.jupiter.api.Test;
      class SyntheticLifecycleTests {
        @Test void boundedControl() throws Exception {
          Soklet s = Soklet.fromConfig(null); s.start();
          long deadline = System.currentTimeMillis() + 20_000;
          ${wait}
          s.close();
        }
      }
    `);
    assert.ok(scopes[0].unresolvedFixedControlWaitCount > 0);
    expectFailure(() => buildReviewedLifecycleScopeRows(scopes,
      { requireRegistryCompleteness: false }),
    /fixed-control waits are unresolved/u);
  });
}

run('explicitly disabled lifecycle test is nonexecuting until re-enabled', () => {
  const source = `
    import org.junit.jupiter.api.Disabled;
    import org.junit.jupiter.api.Test;
    class SyntheticLifecycleTests {
      @Disabled("manual stress") @Test void stress() throws Exception {
        Soklet s = Soklet.fromConfig(null); s.start();
        Thread.sleep(4_000_000); s.close();
      }
    }
  `;
  const disabled = syntheticScopes(source);
  assert.equal(disabled.length, 1);
  assert.equal(disabled[0].disabled, true);
  assert.equal(disabled[0].hasExecution, false);
  assert.equal(buildReviewedLifecycleScopeRows(disabled,
    { requireRegistryCompleteness: false })[0].review.classification,
  'NON_EXECUTING_LIFECYCLE_EVIDENCE');
  const enabled = syntheticScopes(source.replace(
    '@Disabled("manual stress") ', ''));
  assert.equal(enabled[0].hasExecution, true);
  expectFailure(() => buildReviewedLifecycleScopeRows(enabled,
    { requireRegistryCompleteness: false }), /does not fit/u);
});

run('symbolic timed wait requires source-bound review', () => {
  const scopes = syntheticScopes(`
    import java.util.concurrent.TimeUnit;
    import org.junit.jupiter.api.Test;
    class SyntheticLifecycleTests {
      @Test void symbolic(long timeoutMillis) throws Exception {
        Soklet s = Soklet.fromConfig(null); s.start();
        gate.await(timeoutMillis, TimeUnit.MILLISECONDS); s.close();
      }
    }
  `);
  assert.ok(scopes[0].unresolvedFixedControlWaitCount > 0);
  expectFailure(() => buildReviewedLifecycleScopeRows(scopes,
    { requireRegistryCompleteness: false }), /fixed-control waits are unresolved/u);
});

run('manual override schema rejects unknown unsafe and inapplicable fields', () => {
  const scopes = syntheticScopes(`
    import org.junit.jupiter.api.Test;
    class SyntheticLifecycleTests {
      @Test void executes() { Soklet s = Soklet.fromConfig(null); s.start(); s.close(); }
    }
  `);
  for (const review of [
    { mystery: 1 },
    { controlComposition: 'TRUST_ME', controlJoinMillis: 0 },
    { controlComposition: 'REVIEWED_OVERLAP_OR_DUPLICATE' },
    { controlledLifecycleCoreMillis: -1 },
    { controlJoinMillis: 1.5 },
    { requiredAction: 'APPROVE_ANYWAY' },
    { requiredAction: 'RAISE_OUTER_BOUND' },
    { requiredAction: 'DELETE_OBSOLETE_ASSERTION' },
    { controlledStartupMillis: 1 },
    { applicationCleanupCount: 1 },
    { dynamicNodeCount: 1 },
  ]) {
    expectFailure(() => reviewedSyntheticRows(scopes, { executes: review }),
      /(?:unknown field|nonnegative safe integer|unknown required action|is invalid|not source-applicable)/u);
  }
});

run('reduced fixed-wait sums require an explicit reviewed composition', () => {
  const scopes = syntheticScopes(`
    import java.util.concurrent.TimeUnit;
    import org.junit.jupiter.api.Test;
    import org.junit.jupiter.api.Timeout;
    class SyntheticLifecycleTests {
      @Test @Timeout(120) void overlapping() throws Exception {
        Soklet s = Soklet.fromConfig(null); s.start();
        gate.await(20, TimeUnit.SECONDS); s.close();
      }
    }
  `);
  expectFailure(() => reviewedSyntheticRows(scopes, {
    overlapping: { controlJoinMillis: 10_000 },
  }), /understates fixed source waits/u);
  const [row] = reviewedSyntheticRows(scopes, {
    overlapping: {
      controlComposition: 'REVIEWED_OVERLAP_OR_DUPLICATE',
      controlJoinMillis: 10_000,
    },
  });
  assert.equal(row.review.controlTopology,
    'REVIEWED_OVERLAP_OR_DUPLICATE');
});

run('unresolved controls cannot hide a resolved sequential wait', () => {
  const scopes = syntheticScopes(`
    import java.util.concurrent.TimeUnit;
    import org.junit.jupiter.api.Test;
    import org.junit.jupiter.api.Timeout;
    class SyntheticLifecycleTests {
      @Test @Timeout(120) void mixedControls() throws Exception {
        Soklet s = Soklet.fromConfig(null); s.start();
        gate.await(20, TimeUnit.SECONDS);
        awaitRuntime(s, expectedState);
        s.close();
      }
    }
  `);
  assert.ok(scopes[0].fixedControlWaitMillis >= 20_000);
  assert.ok(scopes[0].unresolvedFixedControlWaitCount > 0);
  expectFailure(() => reviewedSyntheticRows(scopes, {
    mixedControls: {
      controlComposition: 'REVIEWED_SEQUENTIAL_SOURCE_BOUND',
      controlJoinMillis: 10_000,
    },
  }), /sequential control\/join allowance understates fixed source waits/u);
});

run('construction-only noStartup differs from execution', () => {
  const scopes = syntheticScopes(`
    import org.junit.jupiter.api.Test;
    class SyntheticLifecycleTests {
      @Test void constructionOnly() { LifecyclePolicy.builder().noStartupTimeout().build(); }
      @Test void executes() {
        LifecyclePolicy.builder().noStartupTimeout().build();
        Soklet s = Soklet.fromConfig(null); s.start();
      }
    }
  `);
  const byName = new Map(scopes.map((scope) => [scope.scopeName, scope]));
  assert.equal(byName.get('constructionOnly').hasExecution, false);
  assert.equal(byName.get('constructionOnly').hasNoStartupTimeout, true);
  assert.equal(byName.get('executes').hasExecution, true);
  expectFailure(() => buildReviewedLifecycleScopeRows(scopes,
    { requireRegistryCompleteness: false }), /controlled-completion override/u);
});

run('zero-argument lifecycle policy accessor is not an installation', () => {
  const scopes = syntheticScopes(`
    import org.junit.jupiter.api.Test;
    class SyntheticLifecycleTests {
      @Test void readsPolicy() { diagnostics.lifecyclePolicy(); }
      @Test void installsPolicy() { config.lifecyclePolicy(policy); }
    }
  `);
  assert.equal(scopes.some((scope) => scope.scopeName === 'readsPolicy'), false);
  const installation = scopes.find((scope) =>
    scope.scopeName === 'installsPolicy');
  assert.ok(installation);
  assert.deepEqual(installation.observedOperations, ['CONFIGURE_POLICY']);
  assert.equal(installation.unresolvedPolicyInstallationCount, 1);
});

run('duplicate lifecycle setters use the effective last value', () => {
  const scopes = syntheticScopes(`
    import java.time.Duration;
    import org.junit.jupiter.api.Test;
    class SyntheticLifecycleTests {
      @Test void duplicateSetter() {
        LifecyclePolicy policy = LifecyclePolicy.builder()
          .startupTimeout(Duration.ofSeconds(1))
          .startupTimeout(Duration.ofSeconds(120))
          .startupCancellationTimeout(Duration.ZERO)
          .gracefulShutdownDuration(Duration.ZERO)
          .forcedShutdownDuration(Duration.ZERO).build();
        Soklet s = Soklet.fromConfig(null); s.start(); s.close();
      }
    }
  `);
  assert.equal(scopes[0].literalPhasePolicies[0].startupMillis, 120_000);
  expectFailure(() => buildReviewedLifecycleScopeRows(scopes,
    { requireRegistryCompleteness: false }), /does not fit/u);
});

run('InternalLifecyclePolicy field cannot silently inherit defaults', () => {
  const scopes = syntheticScopes(`
    import java.time.Duration;
    import java.util.Optional;
    import org.junit.jupiter.api.Test;
    class SyntheticLifecycleTests {
      static final InternalLifecyclePolicy POLICY = new InternalLifecyclePolicy(
        Optional.of(Duration.ofHours(2)), Duration.ZERO, Duration.ZERO, Duration.ZERO);
      @Test void internalField() {
        SokletConfig config = SokletConfig.withHttpServer(null)
          .internalLifecyclePolicy(POLICY).build();
        Soklet s = Soklet.fromConfig(config); s.start(); s.close();
      }
    }
  `);
  assert.equal(scopes[0].literalPhasePolicies[0].startupMillis, 7_200_000);
  expectFailure(() => buildReviewedLifecycleScopeRows(scopes,
    { requireRegistryCompleteness: false }), /does not fit/u);
});

run('InternalLifecyclePolicy Optional.empty cannot be overridden finite', () => {
  const scopes = syntheticScopes(`
    import java.time.Duration;
    import java.util.Optional;
    import org.junit.jupiter.api.Test;
    class SyntheticLifecycleTests {
      static final InternalLifecyclePolicy POLICY = new InternalLifecyclePolicy(
        Optional.empty(), Duration.ZERO, Duration.ZERO, Duration.ZERO);
      @Test void unboundedInternal() {
        SokletConfig config = SokletConfig.withHttpServer(null)
          .internalLifecyclePolicy(POLICY).build();
        Soklet s = Soklet.fromConfig(config); s.start();
      }
    }
  `);
  expectFailure(() => reviewedSyntheticRows(scopes, {
    unboundedInternal: {
      phasePolicy: {
        forcedShutdownMillis: 0,
        gracefulShutdownMillis: 0,
        startupCancellationMillis: 0,
        startupMillis: 1,
      },
    },
  }), /cannot be replaced by a finite review/u);
});

for (const nestedFirst of [false, true]) {
  run(`lexical field policy resolution (${nestedFirst ? 'nested first' : 'outer first'})`, () => {
    const outer = `static final LifecyclePolicy POLICY = LifecyclePolicy.builder()
      .startupTimeout(Duration.ofHours(2)).build();`;
    const nested = `static final class Nested { static final LifecyclePolicy POLICY =
      LifecyclePolicy.builder().startupTimeout(Duration.ofSeconds(1)).build(); }`;
    const scopes = syntheticScopes(`
      import java.time.Duration;
      import org.junit.jupiter.api.Test;
      class SyntheticLifecycleTests {
        ${nestedFirst ? nested : outer}
        ${nestedFirst ? outer : nested}
        @Test void outerPolicy() {
          SokletConfig c = SokletConfig.withHttpServer(null)
            .lifecyclePolicy(POLICY).build();
          Soklet s = Soklet.fromConfig(c); s.start(); s.close();
        }
      }
    `);
    assert.ok(scopes[0].literalPhasePolicies.some((policy) =>
      policy.startupMillis === 7_200_000));
    expectFailure(() => buildReviewedLifecycleScopeRows(scopes,
      { requireRegistryCompleteness: false }), /does not fit/u);
  });
}

run('ambiguous duration aliases fail closed', () => {
  const scopes = syntheticScopes(`
    import java.time.Duration;
    import org.junit.jupiter.api.Test;
    class SyntheticLifecycleTests {
      @Test void earlier() {
        Duration BUDGET = Duration.ofHours(2);
        LifecyclePolicy.builder().startupTimeout(BUDGET).build();
        Soklet s = Soklet.fromConfig(null); s.start(); s.close();
      }
      @Test void later() { Duration BUDGET = Duration.ofSeconds(1); }
    }
  `);
  expectFailure(() => buildReviewedLifecycleScopeRows(scopes,
    { requireRegistryCompleteness: false }), /unresolved policy builders/u);
});

run('config field lifecycle policy provenance fails outside callable scope', () => {
  expectFailure(() => syntheticScopes(`
    import java.time.Duration;
    import org.junit.jupiter.api.Test;
    class SyntheticLifecycleTests {
      static final SokletConfig CONFIG = SokletConfig.withHttpServer(null)
        .lifecyclePolicy(LifecyclePolicy.builder()
          .startupTimeout(Duration.ofHours(2)).build()).build();
      @Test void fieldConfig() { Soklet s = Soklet.fromConfig(CONFIG); s.start(); }
    }
  `), /outside a parsed callable scope/u);
});

for (const [label, configExpression, extra] of [
  ['helper-returned config', 'config()', `
    static SokletConfig config() {
      return SokletConfig.withHttpServer(null)
        .lifecyclePolicy(LifecyclePolicy.builder()
          .startupTimeout(Duration.ofHours(2)).build()).build();
    }`],
  ['reassigned config alias', 'config()', `
    static SokletConfig config() {
      return SokletConfig.withHttpServer(null)
        .lifecyclePolicy(LifecyclePolicy.builder()
          .startupTimeout(Duration.ofHours(2)).build()).build();
    }`],
  ['qualified policy alias', `SokletConfig.withHttpServer(null)
      .lifecyclePolicy(Policies.POLICY).build()`, `
    static final class Policies {
      static final LifecyclePolicy POLICY = LifecyclePolicy.builder()
        .startupTimeout(Duration.ofHours(2)).build();
    }`],
]) {
  run(`${label} cannot silently inherit defaults`, () => {
    const assignment = label === 'reassigned config alias'
      ? `SokletConfig config = ${configExpression}; config = config();`
      : `SokletConfig config = ${configExpression};`;
    const scopes = syntheticScopes(`
      import java.time.Duration;
      import org.junit.jupiter.api.Test;
      class SyntheticLifecycleTests {
        ${extra}
        @Test void consumesAlias() {
          ${assignment}
          Soklet s = Soklet.fromConfig(config); s.start(); s.close();
        }
      }
    `);
    expectFailure(() => buildReviewedLifecycleScopeRows(scopes,
      { requireRegistryCompleteness: false }),
    /(?:does not fit|unresolved policy)/u);
  });
}

for (const nestedFirst of [false, true]) {
  run(`duration alias shadowing fails closed (${nestedFirst ? 'nested first' : 'outer first'})`, () => {
    const outer = 'static final Duration BUDGET = Duration.ofHours(2);';
    const nested = `static final class Nested {
      static final Duration BUDGET = Duration.ofSeconds(1); }`;
    const scopes = syntheticScopes(`
      import java.time.Duration;
      import org.junit.jupiter.api.Test;
      class SyntheticLifecycleTests {
        ${nestedFirst ? nested : outer}
        ${nestedFirst ? outer : nested}
        @Test void consumesBudget() {
          LifecyclePolicy.builder().startupTimeout(BUDGET).build();
          Soklet s = Soklet.fromConfig(null); s.start(); s.close();
        }
      }
    `);
    expectFailure(() => buildReviewedLifecycleScopeRows(scopes,
      { requireRegistryCompleteness: false }), /unresolved policy builders/u);
  });
}

run('dynamic nodes require an explicit per-node wrapper', () => {
  const observations = clone(EVIDENCE.lifecycleScopeObservations);
  const row = observations.find((scope) => scope.scopeName === 'containmentMatrix');
  row.dynamicNodeGuardMillis = null;
  row.outerTimeoutScope = 'DEFAULT';
  expectFailure(() => buildReviewedLifecycleScopeRows(observations),
    /dynamic-test nodes lack/u);
});

run('dynamic wrapper must directly own every node executable', () => {
  const scopes = syntheticScopes(`
    import java.time.Duration;
    import java.util.stream.Stream;
    import org.junit.jupiter.api.Assertions;
    import org.junit.jupiter.api.DynamicTest;
    import org.junit.jupiter.api.TestFactory;
    class SyntheticLifecycleTests {
      @TestFactory Stream<DynamicTest> nodes() {
        Assertions.assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {});
        return Stream.of(DynamicTest.dynamicTest("unwrapped", () -> {
          Soklet s = Soklet.fromConfig(null); s.start(); s.close();
        }));
      }
    }
  `);
  assert.equal(scopes[0].unwrappedDynamicNodeCount, 1);
  expectFailure(() => buildReviewedLifecycleScopeRows(scopes,
    { requireRegistryCompleteness: false }), /dynamic-test nodes lack/u);
});

run('dynamic producer loop cannot claim a one-node census', () => {
  const scopes = syntheticScopes(`
    import java.time.Duration;
    import java.util.ArrayList;
    import java.util.List;
    import org.junit.jupiter.api.Assertions;
    import org.junit.jupiter.api.DynamicTest;
    import org.junit.jupiter.api.TestFactory;
    class SyntheticLifecycleTests {
      @TestFactory List<DynamicTest> nodes() {
        List<DynamicTest> tests = new ArrayList<>();
        for (int i = 0; i < 20; i++) tests.add(DynamicTest.dynamicTest("n" + i,
          () -> Assertions.assertTimeoutPreemptively(Duration.ofSeconds(60),
            () -> { Soklet s = Soklet.fromConfig(null); s.start(); s.close(); })));
        return tests;
      }
    }
  `);
  assert.equal(scopes[0].dynamicNodeSiteCount, 1);
  assert.equal(scopes[0].dynamicNodeCount, 20);
  expectFailure(() => buildReviewedLifecycleScopeRows(scopes,
    { requireRegistryCompleteness: false }), /reviewed node census/u);
});

run('explicit dynamic scenario list has an exact per-node census', () => {
  const scopes = syntheticScopes(`
    import java.time.Duration;
    import java.util.List;
    import org.junit.jupiter.api.Assertions;
    import org.junit.jupiter.api.DynamicTest;
    import org.junit.jupiter.api.TestFactory;
    class SyntheticLifecycleTests {
      @TestFactory List<DynamicTest> nodes() {
        List<Case> cases = List.of(new Case("a"), new Case("b"), new Case("c"));
        return cases.stream().map(item -> DynamicTest.dynamicTest(item.name(),
          () -> Assertions.assertTimeoutPreemptively(Duration.ofSeconds(120),
            () -> { Soklet s = Soklet.fromConfig(null); s.start(); s.close(); })))
          .toList();
      }
      record Case(String name) {}
    }
  `);
  assert.equal(scopes[0].dynamicNodeCount, 3);
  assert.equal(scopes[0].dynamicNodeSiteCount, 1);
  assert.equal(scopes[0].dynamicNodeGuardMillis, 120_000);
  assert.equal(scopes[0].generationSiteCount, 1);
  const [row] = reviewedSyntheticRows(scopes, {
    nodes: { dynamicNodeCount: 3 },
  });
  assert.equal(row.review.generationMode, 'SINGLE');
  assert.equal(row.review.generationCount, 1);
});

run('enhanced-for dynamic producer binds its explicit scenario list', () => {
  const scopes = syntheticScopes(`
    import java.time.Duration;
    import java.util.ArrayList;
    import java.util.List;
    import org.junit.jupiter.api.Assertions;
    import org.junit.jupiter.api.DynamicTest;
    import org.junit.jupiter.api.TestFactory;
    class SyntheticLifecycleTests {
      @TestFactory List<DynamicTest> nodes() {
        List<Case> cases = List.of(new Case("a"), new Case("b"), new Case("c"));
        List<DynamicTest> tests = new ArrayList<>();
        for (Case item : cases) {
          tests.add(DynamicTest.dynamicTest(item.name(),
            () -> Assertions.assertTimeoutPreemptively(Duration.ofSeconds(60),
              () -> { Soklet s = Soklet.fromConfig(null); s.start(); s.close(); })));
        }
        return tests;
      }
      record Case(String name) {}
    }
  `);
  assert.equal(scopes[0].dynamicNodeCount, 3);
  assert.equal(scopes[0].dynamicNodeGuardMillis, 60_000);
  reviewedSyntheticRows(scopes, { nodes: { dynamicNodeCount: 3 } });
});

run('returned dynamic producer helper propagates only its node guard', () => {
  const scopes = syntheticScopes(`
    import java.time.Duration;
    import java.util.List;
    import org.junit.jupiter.api.Assertions;
    import org.junit.jupiter.api.DynamicTest;
    import org.junit.jupiter.api.TestFactory;
    class SyntheticLifecycleTests {
      @TestFactory List<DynamicTest> nodes() {
        List<NamedScenario> scenarios = List.of(
          new NamedScenario("a", () -> runOwner()),
          new NamedScenario("b", () -> runOwner()));
        return dynamicTests(scenarios);
      }
      static List<DynamicTest> dynamicTests(List<NamedScenario> scenarios) {
        return scenarios.stream().map(scenario -> DynamicTest.dynamicTest(
          scenario.name(), () -> Assertions.assertTimeoutPreemptively(
            Duration.ofSeconds(120), () -> scenario.body().run()))).toList();
      }
      static void runOwner() {
        Soklet s = Soklet.fromConfig(null); s.start(); s.close();
      }
      record NamedScenario(String name, Runnable body) {}
    }
  `);
  assert.equal(scopes[0].dynamicNodeCount, 2);
  assert.equal(scopes[0].dynamicNodeGuardMillis, 120_000);
  reviewedSyntheticRows(scopes, { nodes: { dynamicNodeCount: 2 } });
});

run('dynamic node guard cannot cover pre-node factory lifecycle work', () => {
  const scopes = syntheticScopes(`
    import java.time.Duration;
    import java.util.List;
    import org.junit.jupiter.api.Assertions;
    import org.junit.jupiter.api.DynamicTest;
    import org.junit.jupiter.api.TestFactory;
    class SyntheticLifecycleTests {
      @TestFactory List<DynamicTest> nodes() throws Exception {
        runOwner();
        Thread.sleep(70_000);
        return List.of(DynamicTest.dynamicTest("n",
          () -> Assertions.assertTimeoutPreemptively(Duration.ofSeconds(120),
            () -> runOwner())));
      }
      static void runOwner() {
        Soklet s = Soklet.fromConfig(null); s.start(); s.close();
      }
    }
  `);
  assert.equal(scopes[0].dynamicNodeGuardMillis, 120_000);
  assert.equal(scopes[0].factoryGenerationHasExecution, true);
  assert.equal(scopes[0].factoryGenerationOuterTimeoutMillis, 60_000);
  assert.equal(scopes[0].factoryGenerationOuterTimeoutScope, 'DEFAULT');
  expectFailure(() => reviewedSyntheticRows(scopes, {
    nodes: { dynamicNodeCount: 1 },
  }), /pre-node generation work/u);
});

run('dynamic node guard cannot cover factory-generation control waits', () => {
  const scopes = syntheticScopes(`
    import java.time.Duration;
    import java.util.List;
    import org.junit.jupiter.api.Assertions;
    import org.junit.jupiter.api.DynamicTest;
    import org.junit.jupiter.api.TestFactory;
    class SyntheticLifecycleTests {
      @TestFactory List<DynamicTest> nodes() throws Exception {
        Thread.sleep(70_000);
        return List.of(DynamicTest.dynamicTest("n",
          () -> Assertions.assertTimeoutPreemptively(Duration.ofSeconds(120),
            () -> { Soklet s = Soklet.fromConfig(null); s.start(); s.close(); })));
      }
    }
  `);
  assert.equal(scopes[0].factoryGenerationHasExecution, false);
  assert.equal(scopes[0].factoryGenerationFixedControlWaitMillis, 70_000);
  expectFailure(() => reviewedSyntheticRows(scopes, {
    nodes: { dynamicNodeCount: 1 },
  }), /pre-node control waits do not fit/u);
});

run('eager dynamic producer lambda is factory-generation work', () => {
  const scopes = syntheticScopes(`
    import java.time.Duration;
    import java.util.List;
    import org.junit.jupiter.api.Assertions;
    import org.junit.jupiter.api.DynamicTest;
    import org.junit.jupiter.api.TestFactory;
    class SyntheticLifecycleTests {
      @TestFactory List<DynamicTest> nodes() {
        return List.of("n").stream().map(name -> {
          runOwner();
          return DynamicTest.dynamicTest(name,
            () -> Assertions.assertTimeoutPreemptively(
              Duration.ofSeconds(120), () -> runOwner()));
        }).toList();
      }
      static void runOwner() {
        Soklet s = Soklet.fromConfig(null); s.start(); s.close();
      }
    }
  `);
  assert.equal(scopes[0].factoryGenerationHasExecution, true);
  expectFailure(() => reviewedSyntheticRows(scopes, {
    nodes: { dynamicNodeCount: 1 },
  }), /pre-node generation work/u);
});

run('eager named-scenario invocation cannot borrow its later node guard', () => {
  const scopes = syntheticScopes(`
    import java.time.Duration;
    import java.util.List;
    import org.junit.jupiter.api.Assertions;
    import org.junit.jupiter.api.DynamicTest;
    import org.junit.jupiter.api.TestFactory;
    class SyntheticLifecycleTests {
      @TestFactory List<DynamicTest> nodes() throws Exception {
        NamedScenario scenario = new NamedScenario("n", () -> runOwner());
        scenario.body().run();
        return List.of(DynamicTest.dynamicTest("n",
          () -> Assertions.assertTimeoutPreemptively(Duration.ofSeconds(120),
            () -> scenario.body().run())));
      }
      static void runOwner() {
        Soklet s = Soklet.fromConfig(null); s.start(); s.close();
      }
      record NamedScenario(String name, ThrowingRunnable body) {}
      interface ThrowingRunnable { void run() throws Exception; }
    }
  `);
  assert.equal(scopes[0].factoryGenerationHasExecution, true);
  expectFailure(() => reviewedSyntheticRows(scopes, {
    nodes: { dynamicNodeCount: 1 },
  }), /pre-node generation work/u);
});

run('unrelated scenario list cannot launder symbolic dynamic cardinality', () => {
  const scopes = syntheticScopes(`
    import java.time.Duration;
    import java.util.ArrayList;
    import java.util.List;
    import org.junit.jupiter.api.Assertions;
    import org.junit.jupiter.api.DynamicTest;
    import org.junit.jupiter.api.TestFactory;
    class SyntheticLifecycleTests {
      @TestFactory List<DynamicTest> nodes(int count) {
        List<Case> cases = List.of(new Case("a"), new Case("b"), new Case("c"));
        cases.stream().count();
        List<DynamicTest> tests = new ArrayList<>();
        for (int index = 0; index < count; ++index)
          tests.add(DynamicTest.dynamicTest("n" + index,
            () -> Assertions.assertTimeoutPreemptively(Duration.ofSeconds(60),
              () -> { Soklet s = Soklet.fromConfig(null); s.start(); s.close(); })));
        return tests;
      }
      record Case(String name) {}
    }
  `);
  assert.equal(scopes[0].dynamicNodeCount, 0);
  expectFailure(() => buildReviewedLifecycleScopeRows(scopes,
    { requireRegistryCompleteness: false }), /source-bound node census/u);
});

run('discarded wrapped node cannot guard an opaque returned producer', () => {
  const scopes = syntheticScopes(`
    import java.time.Duration;
    import java.util.List;
    import org.junit.jupiter.api.Assertions;
    import org.junit.jupiter.api.DynamicTest;
    import org.junit.jupiter.api.TestFactory;
    class SyntheticLifecycleTests {
      @TestFactory List<DynamicTest> nodes() {
        List<NamedScenario> scenarios = List.of(
          new NamedScenario("a", () -> runOwner()),
          new NamedScenario("b", () -> runOwner()));
        return dynamicTests(scenarios);
      }
      static List<DynamicTest> dynamicTests(List<NamedScenario> scenarios) {
        DynamicTest ignored = DynamicTest.dynamicTest("ignored",
          () -> Assertions.assertTimeoutPreemptively(Duration.ofSeconds(120),
            () -> runOwner()));
        return opaque(scenarios);
      }
      static List<DynamicTest> opaque(List<NamedScenario> scenarios) {
        return List.of();
      }
      static void runOwner() {
        Soklet s = Soklet.fromConfig(null); s.start(); s.close();
      }
      record NamedScenario(String name, Runnable body) {}
    }
  `);
  assert.equal(scopes[0].dynamicNodeCount, 2);
  assert.equal(scopes[0].dynamicNodeGuardMillis, null);
  expectFailure(() => reviewedSyntheticRows(scopes, {
    nodes: { dynamicNodeCount: 2 },
  }), /pre-node generation work/u);
});

run('dynamic factory rejects aggregate sequential generation review', () => {
  const scopes = syntheticScopes(`
    import java.time.Duration;
    import java.util.List;
    import org.junit.jupiter.api.Assertions;
    import org.junit.jupiter.api.DynamicTest;
    import org.junit.jupiter.api.TestFactory;
    class SyntheticLifecycleTests {
      @TestFactory List<DynamicTest> nodes() {
        List<Case> cases = List.of(new Case("a"), new Case("b"));
        return cases.stream().map(item -> DynamicTest.dynamicTest(item.name(),
          () -> Assertions.assertTimeoutPreemptively(Duration.ofSeconds(120),
            () -> { Soklet s = Soklet.fromConfig(null); s.start(); s.close(); })))
          .toList();
      }
      record Case(String name) {}
    }
  `);
  expectFailure(() => reviewedSyntheticRows(scopes, {
    nodes: {
      dynamicNodeCount: 2,
      generation: { complete: 2, count: 2, incomplete: 1,
        mode: 'SEQUENTIAL', prior: 1 },
    },
  }), /must model one independently guarded node/u);
});

run('dynamic scenario count is source-bound', () => {
  const observations = clone(EVIDENCE.lifecycleScopeObservations);
  const row = observations.find((scope) => scope.scopeName
    === 'recognizedRequestMethodsReplayExactJsonOrSseShapes');
  row.dynamicNodeCount -= 1;
  expectFailure(() => buildReviewedLifecycleScopeRows(observations),
    /node census drifted/u);
});

run('helpers never claim the global JUnit guard', () => {
  assert.ok(EVIDENCE.lifecycleScopeObservations.every((scope) =>
    ['TEST', 'SETUP_TEARDOWN'].includes(scope.scopeKind)));
  assert.ok(EVIDENCE.orphanLifecycleHelperObservations.every((helper) =>
    !Object.hasOwn(helper, 'effectiveOuterTimeoutMillis')));
});

run('unreviewed orphan helper row rejected', () => {
  const document = clone(INVENTORY);
  document.orphanLifecycleHelpers.push(clone(document.orphanLifecycleHelpers[0]));
  expectFailure(() => verifyDocument(document), /helper evidence/u);
});

run('orphan helper invocation proof is mandatory and source-bound', () => {
  for (const mutation of [
    (review) => { delete review.invocationProof; },
    (review) => { review.invocationProof.lineSha256 = '0'.repeat(64); },
  ]) {
    const document = clone(INVENTORY);
    mutation(document.orphanLifecycleHelpers[0].review);
    expectFailure(() => verifyDocument(document),
      /orphan lifecycle helper|invocation proof|fields must be exactly/iu);
  }
});

run('line relocation invalidates scope address', () => {
  const document = clone(INVENTORY);
  document.lifecycleScopes[0].source.lineSha256 = '0'.repeat(64);
  expectFailure(() => verifyDocument(document), /method scope set/u);
});

run('broad discovery row removal rejected', () => {
  const document = clone(INVENTORY);
  document.discoveryCensus.candidates.pop();
  expectFailure(() => verifyDocument(document), /Broad discovery census/u);
});

run('accepted-D1 row removal rejected', () => {
  const document = clone(INVENTORY);
  document.acceptedD1Occurrences.pop();
  expectFailure(() => verifyDocument(document), /Accepted-D1/u);
});

run('phase-4 ledger cannot be historical exclusion', () => {
  const document = clone(INVENTORY);
  const row = document.acceptedD1Occurrences.find((candidate) =>
    candidate.path === 'api/mcp/phase-4.signatures.jsonl');
  assert.ok(row);
  row.classification = 'REVIEWED_EXCLUSION';
  row.closureStatus = 'NOT_APPLICABLE';
  row.requiredAction = 'NONE';
  expectFailure(() => verifyDocument(document), /not independently allowed/u);
});

run('surviving legacy lifecycle setter hard-fails', () => {
  expectFailure(() => verifyNoSurvivingLegacySites(new Map([
    ['src/test/java/com/soklet/LegacyTests.java',
      'class LegacyTests { void test() { server.shutdownTimeout(Duration.ZERO); } }'],
  ])), /Surviving non-excluded/u);
});

run('generated D1p semantic evidence is not live lifecycle source', () => {
  const path = 'release/d1p-canonical-semantic-digests.json';
  const source = readFileSync(join(ROOT, path), 'utf8');
  assert.match(source, /\bshutdownTimeout\s*\(/u);
  assert.deepEqual(verifyNoSurvivingLegacySites(new Map([[path, source]])), []);
  assert.equal(EVIDENCE.currentLegacyExclusions.length, 15);
  assert.equal(INVENTORY.currentLegacyExclusions.length, 15);
});

run('only exact generated D1p evidence paths bypass source scanning', () => {
  const generated = 'historical.shutdownTimeout(Duration.ZERO);';
  for (const path of [
    'release/d1p-canonical-semantic-digests.json',
    'release/d1p-public-cutover-manifest.json',
    'release/d1p-tracked-blobs.sha256',
  ])
    assert.deepEqual(verifyNoSurvivingLegacySites(new Map([[path, generated]])), []);
});

run('generated D1p evidence lookalikes remain fail closed', () => {
  const generated = 'historical.shutdownTimeout(Duration.ZERO);';
  for (const path of [
    'release/d1p-canonical-semantic-digests.json.copy',
    'release/unreviewed-evidence.json',
  ]) {
    expectFailure(() => verifyNoSurvivingLegacySites(new Map([
      [path, generated],
    ])), new RegExp(path.replace(/[.*+?^${}()|[\]\\]/gu, '\\$&'), 'u'));
  }
});

run('generated D1p evidence cannot hide a live lifecycle setter', () => {
  expectFailure(() => verifyNoSurvivingLegacySites(new Map([
    ['release/d1p-canonical-semantic-digests.json',
      'historical.shutdownTimeout(Duration.ZERO);'],
    ['src/test/java/com/soklet/LegacyTests.java',
      'class LegacyTests { void test() { server.shutdownTimeout(Duration.ZERO); } }'],
  ])), /src\/test\/java\/com\/soklet\/LegacyTests\.java/u);
});

run('official guard uses one shared absolute deadline', () => {
  const paths = ['conformance/official/run.mjs',
    'conformance/official/run-local-simulator.mjs'];
  const texts = sourceTexts(...paths);
  assert.equal(javascriptGuards(texts)[0].deadlineConsumerCount, 2);
  texts.set(paths[0], texts.get(paths[0]).replace(
    'remainingFixtureShutdownMilliseconds(shutdownDeadlineNanoseconds),\n    );',
    'shutdownTimeoutMilliseconds,\n    );'));
  expectFailure(() => javascriptGuards(texts),
    /(?:remaining-deadline consumer|one definition and two consumers)/u);
});

run('commented JavaScript deadline consumer is not executable wiring', () => {
  const paths = ['conformance/official/run.mjs',
    'conformance/official/run-local-simulator.mjs'];
  const texts = sourceTexts(...paths);
  const consumer = 'remainingFixtureShutdownMilliseconds(shutdownDeadlineNanoseconds),';
  texts.set(paths[0], texts.get(paths[0]).replace(consumer,
    `shutdownTimeoutMilliseconds, /* ${consumer} */`));
  expectFailure(() => javascriptGuards(texts),
    /(?:remaining-deadline consumer|one definition and two consumers)/u);
});

run('commented official fixture policy setter is not executable wiring', () => {
  const fixture = 'conformance/official/public-fixture-src/com/soklet/conformance/McpConformanceFixture.java';
  const paths = [fixture,
    'soak/src/test/java/com/soklet/HttpSoakTests.java',
    'soak/src/test/java/com/soklet/McpCrossFeatureSoakTests.java',
    'soak/src/test/java/com/soklet/McpLocalizationSoakTests.java',
    'soak/src/test/java/com/soklet/RealtimeTransportSoakTests.java'];
  const texts = sourceTexts(...paths);
  const setter = '.gracefulShutdownDuration(Duration.ofSeconds(5))';
  texts.set(fixture, texts.get(fixture).replace(setter, `/* ${setter} */`));
  expectFailure(() => verifySpecialSourceWiring(texts),
    /official 5-second graceful shutdown/u);
});

run('official remaining-deadline helper semantics are source-bound', () => {
  const paths = ['conformance/official/run.mjs',
    'conformance/official/run-local-simulator.mjs'];
  const texts = sourceTexts(...paths);
  texts.set(paths[0], texts.get(paths[0]).replace(
    /function remainingFixtureShutdownMilliseconds\(deadlineNanoseconds\) \{[\s\S]*?\n\}/u,
    'function remainingFixtureShutdownMilliseconds(deadlineNanoseconds) {\n  return 10_000;\n}'));
  expectFailure(() => javascriptGuards(texts), /helper semantics/u);
});

run('JavaScript join text is not a process guard false positive', () => {
  const paths = ['conformance/official/run.mjs',
    'conformance/official/run-local-simulator.mjs'];
  const texts = sourceTexts(...paths);
  texts.set(paths[1], `${texts.get(paths[1])}\nconst display = ['a', 'b'].join(', ');\n`);
  assert.equal(javascriptGuards(texts)[1].consumerCount, 1);
});

run('local simulator process guard drift rejected', () => {
  const paths = ['conformance/official/run.mjs',
    'conformance/official/run-local-simulator.mjs'];
  const texts = sourceTexts(...paths);
  texts.set(paths[1], texts.get(paths[1]).replace('120_000', '12_000'));
  expectFailure(() => javascriptGuards(texts), /local simulator process guard/u);
});

run('soak profile and source loop drift rejected', () => {
  const profilePaths = ['smoke', 'nightly', 'release'].map((name) =>
    `soak/src/test/resources/com/soklet/soak-profiles/${name}.properties`);
  const profiles = sourceTexts(...profilePaths);
  profiles.set(profilePaths[0], profiles.get(profilePaths[0]).replace(
    'mcp.shutdownCycles=2', 'mcp.shutdownCycles=3'));
  expectFailure(() => soakProfiles(profiles), /shutdown cycles/u);
  const crossPath = 'soak/src/test/java/com/soklet/McpCrossFeatureSoakTests.java';
  const localizationPath = 'soak/src/test/java/com/soklet/McpLocalizationSoakTests.java';
  const specialPaths = [
    'conformance/official/public-fixture-src/com/soklet/conformance/McpConformanceFixture.java',
    'soak/src/test/java/com/soklet/HttpSoakTests.java', crossPath,
    localizationPath,
    'soak/src/test/java/com/soklet/RealtimeTransportSoakTests.java',
  ];
  const sources = sourceTexts(...specialPaths);
  sources.set(crossPath, sources.get(crossPath).replace(
    '1 + PROFILE.shutdownCycles()', '2 + PROFILE.shutdownCycles()'));
  expectFailure(() => verifySpecialSourceWiring(sources), /generation count/u);
  const localization = sourceTexts(...specialPaths);
  localization.set(localizationPath, localization.get(localizationPath)
    .replace(/PROFILE\.cyclesPerClient\(\),\s*"measured"\);/u,
      '1, "measured");'));
  expectFailure(() => verifySpecialSourceWiring(localization),
    /measured generation/u);
});

run('special harness duplicate policy setters and extra entries fail', () => {
  const fixture = 'conformance/official/public-fixture-src/com/soklet/conformance/McpConformanceFixture.java';
  const http = 'soak/src/test/java/com/soklet/HttpSoakTests.java';
  const cross = 'soak/src/test/java/com/soklet/McpCrossFeatureSoakTests.java';
  const localization = 'soak/src/test/java/com/soklet/McpLocalizationSoakTests.java';
  const realtime = 'soak/src/test/java/com/soklet/RealtimeTransportSoakTests.java';
  const paths = [fixture, http, cross, localization, realtime];
  for (const [path, needle, duplicate] of [
    [fixture, '.forcedShutdownDuration(Duration.ofSeconds(1))',
      '.forcedShutdownDuration(Duration.ofSeconds(1))\n        .forcedShutdownDuration(Duration.ofHours(1))'],
    [http, '.gracefulShutdownDuration(Duration.ofSeconds(3))',
      '.gracefulShutdownDuration(Duration.ofSeconds(3))\n        .gracefulShutdownDuration(Duration.ofHours(1))'],
    [realtime, '.gracefulShutdownDuration(Duration.ofSeconds(3))',
      '.gracefulShutdownDuration(Duration.ofSeconds(3))\n        .gracefulShutdownDuration(Duration.ofHours(1))'],
    [cross, '.startupTimeout(Duration.ofSeconds(30))',
      '.startupTimeout(Duration.ofSeconds(30))\n        .startupTimeout(Duration.ofHours(1))'],
    [localization, '.startupTimeout(Duration.ofSeconds(30))',
      '.startupTimeout(Duration.ofSeconds(30))\n        .startupTimeout(Duration.ofHours(1))'],
  ]) {
    const texts = sourceTexts(...paths);
    texts.set(path, texts.get(path).replace(needle, duplicate));
    expectFailure(() => verifySpecialSourceWiring(texts), /unique/u);
  }
  const extraCross = sourceTexts(...paths);
  extraCross.set(cross, extraCross.get(cross).replace(
    'int expectedGenerations = 1 + PROFILE.shutdownCycles();',
    'try (Soklet extra = crossFeatureSoklet(null, null, null, null)) {}\n\t\tint expectedGenerations = 1 + PROFILE.shutdownCycles();'));
  expectFailure(() => verifySpecialSourceWiring(extraCross), /exact two entries/u);
  const directCross = sourceTexts(...paths);
  directCross.set(cross, directCross.get(cross).replace(
    'int expectedGenerations = 1 + PROFILE.shutdownCycles();',
    'Soklet extra = Soklet.fromConfig(null);\n\t\tint expectedGenerations = 1 + PROFILE.shutdownCycles();'));
  expectFailure(() => verifySpecialSourceWiring(directCross),
    /owner construction site/u);
  const extraLocalization = sourceTexts(...paths);
  extraLocalization.set(localization, extraLocalization.get(localization)
    .replace('runSimulatorWorkload(configFactory, server, state, 1, 1, "warmup");',
      'SokletSimulator.run(configFactory);\n\t\trunSimulatorWorkload(configFactory, server, state, 1, 1, "warmup");'));
  expectFailure(() => verifySpecialSourceWiring(extraLocalization),
    /simulator lifecycle entry/u);
});

process.stdout.write(
  `lifecycle-bound harness inventory self-test PASS (${cases} named cases)\n`,
);
