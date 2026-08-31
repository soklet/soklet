#!/usr/bin/env node

import { createHash } from 'node:crypto';
import { existsSync, lstatSync, readFileSync } from 'node:fs';
import { dirname, isAbsolute, join, resolve } from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { TextDecoder } from 'node:util';

export const INVENTORY_PATH = 'release/lifecycle-bound-harness-inventory.json';
export const ACCEPTED_D1_COMMIT =
  '315b759b97a3c32b420f34c3c137d72a09db9a11';
export const STANDARD_JUNIT_GUARD_PATH =
  'src/test/resources/junit-platform.properties';
export const STANDARD_JUNIT_GUARD_MILLIS = 60_000;
const STANDARD_JUNIT_GUARD_TEXT =
  'junit.jupiter.execution.timeout.default = 60 s\n';

const PLAN_PATH = 'SOKLET_APPLICATION_IMPLEMENTATION_PLAN_V4.md';
const PLAN_SECTION = 'Lifecycle-bound harness migration';
const UTF8_DECODER = new TextDecoder('utf-8', { fatal: true });
const LEGACY_PATTERN_SOURCE = String.raw`\bshutdownTimeout\s*\(`;
const LEGACY_GIT_PATTERN = String.raw`shutdownTimeout[[:space:]]*\(`;
const JUNIT_TIMEOUT_PATTERN = /@(?:org\.junit\.jupiter\.api\.)?Timeout\s*\(([\s\S]*?)\)/gu;
const JUNIT_ROOT = 'src/test/java/';
const SOAK_ROOT = 'soak/src/test/java/';
const CANDIDATE_PATH_PREFIXES = Object.freeze([
  '.github/workflows/',
  'benchmarks/',
  'conformance/',
  'fuzz/',
  'scripts/',
  'soak/',
  'src/test/',
  'verification/',
]);
const EXCLUDED_DISCOVERY_PATHS = new Set([
  INVENTORY_PATH,
  'scripts/verify-lifecycle-bound-harness-inventory-self-test.mjs',
  'scripts/verify-lifecycle-bound-harness-inventory.mjs',
]);
const GENERATED_D1P_EVIDENCE_PATHS = new Set([
  'release/d1p-canonical-semantic-digests.json',
  'release/d1p-public-cutover-manifest.json',
  'release/d1p-tracked-blobs.sha256',
]);
const SPECIAL_HARNESS_PATHS = Object.freeze([
  'conformance/official/run.mjs',
  'soak/src/test/java/com/soklet/HttpSoakTests.java',
  'soak/src/test/java/com/soklet/McpCrossFeatureSoakTests.java',
  'soak/src/test/java/com/soklet/McpLocalizationSoakTests.java',
  'soak/src/test/java/com/soklet/RealtimeTransportSoakTests.java',
]);
const OFFICIAL_FIXTURE_PATH =
  'conformance/official/public-fixture-src/com/soklet/conformance/McpConformanceFixture.java';
const LOCAL_SIMULATOR_PATH = 'conformance/official/run-local-simulator.mjs';
const SOAK_PROFILE_NAMES = Object.freeze(['smoke', 'nightly', 'release']);
const SOAK_PROFILE_ROOT =
  'soak/src/test/resources/com/soklet/soak-profiles';
const CLASSIFICATIONS = new Set([
  'CONSTRUCTION_ONLY',
  'JUNIT_LIFECYCLE',
  'PROCESS_HARNESS',
  'REVIEWED_DISCOVERY_ONLY',
  'SETTLED_HARNESS',
  'SETTLED_HARNESS_SUPPORT',
]);
const CLOSED_STATUSES = new Set(['CLOSED', 'NOT_APPLICABLE']);
const BASELINE_ACTIONS = new Set([
  'DELETE_OBSOLETE_ASSERTION',
  'MIGRATE_POLICY',
  'NONE',
]);
const DISCOVERY_KINDS = Object.freeze([
  'FIXED_WAIT_CANDIDATE',
  'JUNIT_OUTER_GUARD',
  'LIFECYCLE_SIGNAL',
  'NAMED_TIMEOUT_CANDIDATE',
  'PROCESS_OUTER_GUARD',
  'WORKFLOW_OUTER_GUARD',
]);
const LIFECYCLE_PATHS = Object.freeze([
  'NORMAL_STARTUP',
  'RUNNING_STOP',
  'NORMAL_START_THEN_RUNNING_STOP',
  'SHUTDOWN_DURING_STARTUP_FROM_OUTER_START',
  'STARTUP_TIMEOUT_PLUS_ROLLBACK',
]);
const LIFECYCLE_OPERATIONS = Object.freeze([
  'CONFIGURE_POLICY',
  'CONFIGURE_RUNNER',
  'CONSTRUCT_HTTP_SERVER',
  'CONSTRUCT_MCP_SERVER',
  'CONSTRUCT_SOKLET',
  'CONSTRUCT_SSE_SERVER',
  'CONSTRUCT_TEMPORARY_RUNTIME',
  'OPEN_SIMULATION_SESSION',
  'RUN_APPLICATION',
  'RUN_SIMULATOR',
  'START',
  'SHUTDOWN_OR_STOP',
  'AWAIT_TERMINATION',
  'CLOSE',
]);
const DEFAULT_PHASE_POLICY = Object.freeze({
  controlledStartupMillis: null,
  forcedShutdownMillis: 3_000,
  gracefulShutdownMillis: 15_000,
  mode: 'INHERITED_DEFAULT',
  startupCancellationMillis: 2_000,
  startupMillis: 30_000,
});
const SCOPE_CLASSIFICATIONS = new Set([
  'CONSTRUCTION_ONLY',
  'LOCAL_POLICY_STRICT_FIT',
  'NON_EXECUTING_LIFECYCLE_EVIDENCE',
  'STANDARD_60_SECOND_DEADLOCK_GUARD',
]);
const SCOPE_ACTIONS = new Set([
  'DELETE_OBSOLETE_ASSERTION',
  'MIGRATE_POLICY',
  'NONE',
  'RAISE_OUTER_BOUND',
]);
const REQUIRED_EXECUTING_SCOPES = Object.freeze({
  'src/test/java/com/soklet/SokletDirectLateStartupIntegrationTests.java': [
    'attachmentLosingShutdownFreezeReturnsBeforeTerminalAsExactNotStarted',
    'pendingAttachProofCannotCompleteCallStillLiveAtTerminalFreeze',
    'installedAttachmentGracefullyReleasedBeforeStartIsNotStarted',
    'installedAttachmentProvenOnlyAfterForceIsForced',
    'installedAttachmentMissingProofIsExactUnknown',
    'pendingAttachProofAndFailureBecomePreReadyEventsOnlyAfterCommit',
    'pendingAttachEventsCannotOverrideThrowOrNullPrecedence',
    'lateStartReturnDuringGraceCatchesUpAfterIndependentIngressQuiesce',
    'lateStartReturnAfterGraceReceivesForceAsItsFirstUnderlyingPhase',
    'startReturnAfterTerminalFreezeIsInertAndCannotRewriteUnknown',
    'shutdownBeforeClaimedStartWorkerEntryDeliversOneDeferredPhase',
    'rejectedStartWorkerLaunchClearsClaimAndRollsBackNotStarted',
    'catchUpFailureIsSecondaryEvidenceToExactLateStartFailure',
  ],
  'src/test/java/com/soklet/SokletDirectWaitSemanticsTests.java': [
    'interruptedWaiterCannotCancelPeerOrOwnerCompletion',
    'markerRejectsOnlyItsExactOwnerBeforePublication',
    'markedShutdownIsPromptAndReturnsTheCachedStage',
    'concurrentCloseCallsJoinOnceAndRestoreEntryInterrupt',
  ],
  'src/test/java/com/soklet/SokletDirectTerminationPrecedenceTests.java': [
    'proofDuringGraceIsUnexpectedAndRepeatedStopRetainsExactIdentities',
    'proofOnlyUnexpectedTerminationRetainsOneSyntheticCause',
    'proofAfterActualForceIsForcedWhileCloseRemainsUnexpected',
    'failureWithoutProofIsIncompleteButUnexpectedStillWins',
    'prematureTerminationBeforeReadinessNeverBecomesCloseUnexpected',
  ],
  'src/test/java/com/soklet/SokletDirectStartClaimTruthTableTests.java': [
    'startRacingNewOriginShutdownWaitsForExactNotAttemptedResult',
  ],
  'src/test/java/com/soklet/ExternallyCoordinatedTransportLifecycleAdapterTests.java': [
    'externalGenerationDefersCommitAndAdmissionAndPublishesExactOwnerResult',
    'completedExternalGenerationPermanentlyRejectsStandaloneAndSecondOwner',
    'externalUnexpectedFailureRecordsBeforeOneOwnerCallbackWithoutCoordinating',
    'externalStartFailureRecordsExactCauseWithoutLaunchingCoordinator',
    'externalSelfStopPublishesIntentBeforeOwnerScopedWaitFailsFast',
    'releaseFailureMustBeFoldedIntoDowngradedOwnerResultBeforePublication',
    'ownerFallbackPublicationReleasesWaitersAfterStrictValidationFailure',
    'mcpForwardsTheExactExternalGenerationAndParticipantEvidence',
  ],
  'src/test/java/com/soklet/DirectParticipantPhaseGateTests.java': [
    'coordinatorFreezesGateBeforeReadingEvidence',
  ],
  'src/test/java/com/soklet/McpLifecycleB3Tests.java': [
    'deterministicNoProofMapsToMcpUnknownAndRetainsEvidence',
    'exactMcpGenerationOperationsRejectForeignTokensWithoutMutation',
    'deterministicNoProofRetainsTheExactBoundEphemeralAddress',
    'blockedMcpQuiesceIsCancelledBeforeForceAndProof',
    'shutdownIntentFencesAdmissionBeforeDeferredMcpQuiesce',
    'mcpFailureAndProofOrderingPreservesTheExactGenerationAndBarrier',
    'stopBeforeRuntimeInstallAndBeforeMarkReadyWinsDeterministically',
  ],
  'src/test/java/com/soklet/SokletSimulatorIsolationTests.java': [
    'sealedScopeRetainsRejectedMcpSessionUntilRollbackTerminates',
  ],
  'src/test/java/com/soklet/McpPreAdmissionMetricsEventPublicRuntimeTests.java': [
    'unknownHeaderOccurrencesAreExactRedactedAndMethodBoundedAcrossPolicies',
  ],
});

const REQUIRED_GENERATION_COUNTS = new Map([
  ['src/test/java/com/soklet/BuiltInTransportLifecycleAdapterTests.java#admissionIsClosedUntilReadinessAndShutdownBeforeReadinessSealsIt#TEST', 2],
  ['src/test/java/com/soklet/BuiltInTransportLifecycleAdapterTests.java#positiveResidualAndUnknownBothRetainEvidenceWithoutRelease#TEST', 2],
  ['src/test/java/com/soklet/BuiltInTransportLifecycleAdapterTests.java#completedResultIsPublishedOnlyAfterCoordinatorRoleRelease#TEST', 2],
  ['src/test/java/com/soklet/BuiltInTransportLifecycleAdapterTests.java#exactGenerationOperationsRejectForeignTokensWithoutMutation#TEST', 2],
  ['src/test/java/com/soklet/ExternallyCoordinatedTransportLifecycleAdapterTests.java#completedExternalGenerationPermanentlyRejectsStandaloneAndSecondOwner#TEST', 2],
  ['src/test/java/com/soklet/McpLifecycleB3Tests.java#exactMcpGenerationOperationsRejectForeignTokensWithoutMutation#TEST', 2],
  ['src/test/java/com/soklet/McpLifecycleB3Tests.java#mcpFailureAndProofOrderingPreservesTheExactGenerationAndBarrier#TEST', 2],
  ['src/test/java/com/soklet/McpLifecycleB3Tests.java#stopBeforeRuntimeInstallAndBeforeMarkReadyWinsDeterministically#TEST', 2],
  ['src/test/java/com/soklet/SseTests.java#staleSseAcceptLoopFailureDoesNotClobberRestartedServer#TEST', 2],
  ['src/test/java/com/soklet/McpPreAdmissionMetricsEventPublicRuntimeTests.java#unknownHeaderOccurrencesAreExactRedactedAndMethodBoundedAcrossPolicies#TEST', 2],
]);
// Exact reviewed cardinalities whose source topology is intentionally broader
// than the conservative syntactic generation-site floor.  Keeping this
// authority separate from REVIEWED_SCOPE_OVERRIDES means neither a regenerated
// inventory nor an accidentally reduced manual override can silently turn a
// reviewed loop/helper topology back into a single generation.
const REQUIRED_REVIEWED_GENERATION_COUNTS = new Map([
  ['src/test/java/com/soklet/AdvancedTests.java#testLargeRequestBodyMemoryHandling#TEST', 11],
  ['src/test/java/com/soklet/McpLocalizationAdversarialTests.java#rejectedAndIrrelevantWorkNeverInvokesTheProvider#TEST', 5],
  ['src/test/java/com/soklet/McpRateLimitPipelinePublicRuntimeTests.java#successfulChargesAreRetainedAfterEveryDownstreamFailure#TEST', 6],
  ['src/test/java/com/soklet/McpRequestStatePublicRuntimeTests.java#frameworkProtectedStateContinuesAcrossInstancesOnlyWithinItsKeyAndAuthorizationPartition#TEST', 4],
  ['src/test/java/com/soklet/McpServerPublicRuntimeTests.java#executionConfigurationValidatesAndOwnsOneExecutorPerGeneration#TEST', 3],
  ['src/test/java/examples/mcp/McpLocalizedCursorFleetApplicationPatternsTests.java#cursorFailuresPreserveOpaqueBytesAndCollapseToOneNeutralError#TEST', 10],
]);
const REQUIRED_DISABLED_SCOPES = new Set([
  'src/test/java/com/soklet/AdvancedTests.java#testDefaultHttpServerMemoryStabilityUnderLoad#TEST',
  'src/test/java/com/soklet/AdvancedTests.java#testSseServerMemoryStabilityUnderLoad#TEST',
  'src/test/java/com/soklet/AdvancedTests.java#testDefaultHttpServerHeavyLoad#TEST',
  'src/test/java/com/soklet/AdvancedTests.java#testSseServerHeavyLoad#TEST',
]);

// Exact facts which the conservative source scanner cannot derive without a
// full Java data-flow engine.  Every entry is pinned to both the complete
// callable source hash and its full owning-file hash.  The checked-in inventory
// is therefore not the authority for
// reviewed generation counts, branch topology, dynamic policy arguments, or
// deterministic completion allowances.
function checkedReviewMap(entries, label) {
  const keys = entries.map(([key]) => key);
  if (new Set(keys).size !== keys.length)
    throw new Error(`Duplicate ${label} review key.`);
  return new Map(entries);
}

function reviewedScopeFile(path, fileSha256, rows) {
  return rows.map(([scopeName, scopeSha256, review]) => [
    `${path}#${scopeName}#TEST`,
    { fileSha256, scopeSha256, ...review },
  ]);
}

function reviewedPhasePolicyFile(path, fileSha256, rows) {
  return rows.map(([scopeName, scopeSha256, phasePolicy]) => [
    `${path}#${scopeName}#TEST`,
    { fileSha256, phasePolicy, scopeSha256 },
  ]);
}

function mergeReviewedScopeOverrideMaps(...maps) {
  const merged = new Map();
  for (const map of maps) {
    for (const [key, review] of map) {
      const prior = merged.get(key);
      if (prior === undefined) {
        merged.set(key, structuredClone(review));
        continue;
      }
      if (prior.fileSha256 !== review.fileSha256
          || prior.scopeSha256 !== review.scopeSha256)
        throw new Error(`Conflicting reviewed lifecycle source hashes: ${key}.`);
      for (const field of Object.keys(review)) {
        if (field !== 'fileSha256' && field !== 'scopeSha256'
            && Object.hasOwn(prior, field))
          throw new Error(`Duplicate reviewed lifecycle override field ${field}: ${key}.`);
      }
      merged.set(key, { ...prior, ...structuredClone(review) });
    }
  }
  return merged;
}

function reviewedOrphanFile(path, fileSha256, rows) {
  return rows.map(([scopeName, line, scopeSha256, invocationProof]) => [
    `${path}#${scopeName}#${line}`,
    { fileSha256, invocationProof, scopeSha256 },
  ]);
}

const REVIEWED_SCOPE_TOPOLOGY_OVERRIDES = checkedReviewMap([
  ...reviewedScopeFile("src/test/java/com/soklet/AdvancedTests.java", "497a85e18b77f5c2671cf1525e87147230c681db902f9a396607025abfa9d44f", [
    ["testSSERaceConditionOnConcurrentConnectionsAndDisconnections","4ba7f73845d5d616360c427a0d59ffcd46cae30af9e60437410f2074d0deb716",{"controlJoinMillis":10500,"controlComposition":"REVIEWED_CONCURRENT_MAX"}],
    ["testConcurrentRequestProcessing","a0385627f35884bc77ffd7f6a2e765d7caf3501d90e57d107d878aed2cb2a445",{"generation":{"count":3,"mode":"MIXED_MAX_PLUS_SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":45000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","requiredAction":"RAISE_OUTER_BOUND"}],
    ["testSseServerClearsCachesOnStop","0900dfd859757aae61d2d17a81bd0fd7209cd03a0841bb911c2cdb4a5d7aa7fa",{"controlJoinMillis":4000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["testLargeRequestBodyMemoryHandling","4c7a2c4037c2a1b3809e2cc0f3b097d6a90fddb6a8ba826936edc01909a7cfbe",{"generation":{"count":11,"mode":"SEQUENTIAL","complete":11,"prior":10,"incomplete":1},"controlJoinMillis":100,"requiredAction":"RAISE_OUTER_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/BuiltInTransportLifecycleAdapterTests.java", "f81b390c74c61c169704935138c4c221615a4f5b22cd6bd0135eed81d2dd17b7", [
    ["admissionIsClosedUntilReadinessAndShutdownBeforeReadinessSealsIt","b0fbc6599143c7cf95856f40ca7a5bb2593d8ce8d79b371f3e13700f9f9b863f",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlledLifecycleCoreMillis":0}],
    ["positiveResidualAndUnknownBothRetainEvidenceWithoutRelease","5019c4a4cace047f371afd9b60441df8492ea2a50b8ee1c57ca67120eb959365",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlledLifecycleCoreMillis":0}],
    ["completedResultIsPublishedOnlyAfterCoordinatorRoleRelease","26497fd8283a1027fb35bec05247d0c70a0925b80a15a038f27ca29bd235978d",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlledLifecycleCoreMillis":0}],
    ["exactGenerationOperationsRejectForeignTokensWithoutMutation","ff1b277adb13768eb39a9e28b2a43a292e48b75af80cf0ca8439c542f6a8535c",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlledLifecycleCoreMillis":0}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/DefaultHttpServerTests.java", "48e7a9c3b120e1d240902151054b85552ecf2f9c4fc27a74da16ceaba4c5df73", [
    ["earlySetupErrorRetainsIdentityCleansGenerationAndAllowsHttpRestart","be89031e4ac2882adc67fde27d104e5719931a3f4e9402463b2fc5026c982800",{"generation":{"count":2,"mode":"MIXED_MAX_PLUS_SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":5000}],
    ["staleHttpEventLoopFailureDoesNotClobberRestartedServer","cec3c46b78a87dd61c5fc5fe49b5ea59e9c13f4e2250a7948428bffa8c12ba4e",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/ExternallyCoordinatedTransportLifecycleAdapterTests.java", "db8038d680de6df9b72e366b4f6da188d8e0264fd68918c578650e95b61f387c", [
    ["externalGenerationDefersCommitAndAdmissionAndPublishesExactOwnerResult","7f59f519fa7d81ad817b074b2d998d5a386017e4a6c4ecc52c379e6d91378526",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlledLifecycleCoreMillis":0}],
    ["completedExternalGenerationPermanentlyRejectsStandaloneAndSecondOwner","cd656504cbd4b87bd2be7427d2888e7058b9b06806c9ceca3ddf27cd71b4fb60",{"generation":{"count":2,"mode":"ONE_FULL_PLUS_PREINIT_REJECTIONS","complete":1,"prior":0,"incomplete":1},"controlledLifecycleCoreMillis":0}],
    ["externalUnexpectedFailureRecordsBeforeOneOwnerCallbackWithoutCoordinating","d9d137e5e5b09a925f66df99ccbb858aa99a8aa295ef654ab5cc2ebd012bb188",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlledLifecycleCoreMillis":0}],
    ["externalStartFailureRecordsExactCauseWithoutLaunchingCoordinator","08cae3fa62e3a54cbfe895600fd4641b951c98681d20ac8924fafa7e684dfcf0",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlledLifecycleCoreMillis":0}],
    ["externalSelfStopPublishesIntentBeforeOwnerScopedWaitFailsFast","fad281bccc07111fcf7920cd61d49ee36f0140b70dbaf4b360199674951bbdd7",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlledLifecycleCoreMillis":0}],
    ["releaseFailureMustBeFoldedIntoDowngradedOwnerResultBeforePublication","9eac23e70b909db79e410506d3c75dbe5d20ae98b7e2993cd499e92cc53643dd",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlledLifecycleCoreMillis":0}],
    ["ownerFallbackPublicationReleasesWaitersAfterStrictValidationFailure","230e6a2f590591559e0c56e6952d7fa70a2b2b6f63b32f05a048a093b5a0e356",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlledLifecycleCoreMillis":0}],
    ["mcpForwardsTheExactExternalGenerationAndParticipantEvidence","59f8e5b093859068e26ab2d666f4135b33935bf1343286156542836c0713499c",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlledLifecycleCoreMillis":0}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpLifecycleB3Tests.java", "865ac2adca9b655732d92525be4b47531682754a4277f9f37e58236190843c5f", [
    ["cancelledFreshOwnerCannotMutateAnotherPreparedOwner","d6b9f41e5e9641fc5a8d015621b7da1ddb95ee37996e07fe21201b18ab7b1972",{"generation":{"count":2,"mode":"MIXED_MAX_PLUS_SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":5000,"controlComposition":"REVIEWED_CONCURRENT_MAX"}],
    ["executableEndpointPlansAreImmutableAndFreshPerServerFactory","99724b969af4a029c883848c69861ce8de38902c8f7129f9521afd7903115a43",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["exactMcpGenerationOperationsRejectForeignTokensWithoutMutation","67aedfa580c9dd8c6514285b0dd58076b13327801868c12f4c88cd974052d148",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlledLifecycleCoreMillis":0}],
    ["fixedPortBindIOExceptionPreservesExactCauseForFreshOwnerAfterRelease","1f2e5d20223b3f85dba86d8e7316e8855e25751e8c15294670b1003b07ce9042",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["forceResponsiveHandlerIsInterruptedOnlyAfterTheGraceDeadline","a4091a81d84f00ab3f37743a354fccfd4e81f4250cd5eebae3b15adb91e7e7da",{"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":1000}}],
    ["mcpFailureAndProofOrderingPreservesTheExactGenerationAndBarrier","074aac14faf7a5fbc66436178579688147b419b8c6e4e4347c12dc721acbc9f5",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlledLifecycleCoreMillis":2000,"controlJoinMillis":5000,"controlComposition":"REVIEWED_LIFECYCLE_CORE_DEDUPLICATION"}],
    ["noncooperativeHandlerClassifiesResidualAndRetainsItsGraphAndAddress","30babb3ab8bba243f67b4508d4820ef3b35e9e2edf937b7be5404dbfdc9a91a5",{"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":1000}}],
    ["oneServerStartupDoesNotMakeAnotherServerStopFailFast","6ba34566aaa3c7ce0aa398e3b675ea5c1d5491b34b9cd33e9f5356cc89f0f815",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["oneShotOwnerCannotConsumeUnexpectedGenerationBeforeExactResultPublication","5a50cff66d4c6138d313719cdc4d85110815f2f56595d5725d163c0468bd3a8d",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["ownerNormalizesRetainedUnexpectedGenerationOnceBeforeRejectingRestart","16782e87cd06c52a258951d2cc9fd71d3442fa6e079884911077a2232db711d4",{"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":1000}}],
    ["postBindPreReadySubscriptionFailurePreservesExactIdentityAndAddress","8f130a8cf179c6494b6d0ec5770972b5f0b5e57c7582abc1b1311a4da3be031a",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["readyEventLoopObserverReentrantShutdownReturnsPromptlyWithoutFalseResidual","0a066ced4124d75a67547b0adf71913c1b3b348fca51cecd5c610d62c3b1031d",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":10000,"controlComposition":"REVIEWED_LIFECYCLE_CORE_DEDUPLICATION"}],
    ["startupErrorPreservesIdentityAndFreshOwnerStartsAfterNeverBoundFailure","da8427c7ddfc8d6fd563fd0f4fa0f672cb37451b92137b7a1744c0a795becc8b",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["stopBeforeRuntimeInstallAndBeforeMarkReadyWinsDeterministically","dfbc3019541b94c163cf454aa3de26c895da5e635e3895327691a3b9b5ec62e9",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlledLifecycleCoreMillis":18000,"controlJoinMillis":5000,"controlComposition":"REVIEWED_LIFECYCLE_CORE_DEDUPLICATION"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpLocalizationAdversarialTests.java", "23348216ab27eae3693080e3810b421a02eb1566ba93d909f4a80062ccda1d70", [
    ["rejectedAndIrrelevantWorkNeverInvokesTheProvider","314ac4b16ec574dc021d47c15bf20ba61dc3e5d9a7285af060451a08a6d20977",{"generation":{"count":5,"mode":"SEQUENTIAL","complete":5,"prior":4,"incomplete":1}}],
    ["simultaneousLocaleSelectionAndInvalidationStayIsolated","2eaca9deef40dd9949ffdd8a37c79ac116d4f6f1c3255eb524876b36cefe41b8",{"controlJoinMillis":10000,"controlComposition":"REVIEWED_OVERLAP_OR_DUPLICATE"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpLocalizationFleetPublicRuntimeTests.java", "943a2862028aed129dd9244c0887498f531f9d65accf609f53fe8cf853b042bd", [
    ["failedFleetReloadPreservesBothOldSnapshotsAndPublishesNoInvalidation","e7c2ac03e01a3e70a99c29e5ca27b2e8fd38bac9ed21b6ae0ad9bc53b2e04159",{"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":1000},"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["nodeLossAndSubscriptionReconnectNeedNoSessionRecoveryAndReleaseFleetResources","9b26204a9fbdd69d1716fb8f158bc697e8e9fdcce1bb041183229d4a90180646",{"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":1000},"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["rollingActivationAllowsRevisionDriftBetweenNodesButNeverWithinAResponse","a2caf2b8dce3a94d17d9ee32036a5e187eea3f0b60a91864a22375033347f7a3",{"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":1000},"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpLocalizationHttpBoundaryTests.java", "7cd9c2fdc1a863cd919d0176790ab8dcae333fe43e062fdd7e2fdca546d5133f", [
    ["cacheableResultsAreClampedToPrivateZeroExactlyWhenLocalized","32ef05135a745decafde402ebb331d54b75a65bf4686c9c702398ef863713a35",{"generation":{"count":5,"mode":"SEQUENTIAL","complete":5,"prior":4,"incomplete":1},"controlJoinMillis":50000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpLocalizationReloadRuntimeTests.java", "06e762b43ee30997aec4e72fc03cb1f6b2188363fb318a5fe4dfafae13a54fe1", [
    ["catalogsChangedDeliversOneCoarseInvalidationPerLocalizedFamily","971b327747c8fae868ad1e2c7a846f8433dfb062078bf19bffc5f5ca5f6c6744",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":20000}],
    ["familiesWithoutALocalizedCatalogAreNeitherAcknowledgedNorDelivered","51fcdfdb8cee2fc1ae58fc6a49b4ec8b63b7337b866bf4990990b5752cae5a37",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":10150}],
    ["discoveryAdvertisesListChangedOnlyForLocalizedCatalogsWithSubscriptions","e4d211e014f2602245e293926882399dec9a8f53661d44c8a4958233ab8d3cd6",{"generation":{"count":3,"mode":"SEQUENTIAL","complete":3,"prior":2,"incomplete":1}}],
    ["aStaleLocalizedTerminalIsReleasedByInvalidation","559d95bfb19ec744718d579a03663fdc0a59e4fdb98420a9a26dd9dea8eec983",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":15000}],
    ["invalidationDuringTerminalPreRenderCannotInstallTheOldSnapshot","391ed7303cd7743b58fed03f10ec281de5ff44ba2e5059e0ac3d465a5d1e0f57",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":20000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["shutdownDuringTerminalPreRenderCannotCommitARejectedSubscription","7bf30820ac4dd223098e6ec6f76c71d3a581682eb5893acddbfe8cb841668a43",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":45000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["twoNodesInvalidateIndependently","e0247cbb18f7ffc966f0f9aae1aad79470cef4de3496385a0fa103bd266ef10a",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpLocalizationRenderingRuntimeTests.java", "523ff8f04ce11f2b0a297095e6f0106e8943c892cceda8645ef831b2c5bfed5f", [
    ["everyNonDiscoveryCatalogRendersItsPlannedSlotsLocalized","286b0a059965366cd6c5c09c8d7d349e2baffb572aa2f8ff0d6889a7b36411a6",{"generation":{"count":4,"mode":"SEQUENTIAL","complete":4,"prior":3,"incomplete":1}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpMetricsEventDeliveryPublicRuntimeTests.java", "3e8390857a9d311861bc20d58e23a226c20b38911b94b3a6136a6e73c0374957", [
    ["adapterStopRequestQueuesStoppedBeforeFreshOwnerStart","31b4c60da8202956d1ae9b27c750b6a3e9cffa6dc27f1edd94b4814d66e93598",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["failedListenerStartEmitsStoppedWithoutStagedStarted","c857b85a97846cba13de142607db4b8f94ceb000d3e94db068459964bc52e312",{"generation":{"count":3,"mode":"SEQUENTIAL","complete":3,"prior":2,"incomplete":1}}],
    ["freshOwnersEmitExactStartedStoppedGenerationsAndShutdownNoOps","74ccbd7085d038d0c5ac76252a8c19d40caff7b5cf2a2b7ad319c29354d2234c",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["unexpectedTerminationOrdersNormalizedStopBeforeFreshOwnerStart","460442bc95883393fb9db323e764cceb7883f2feb2df70cff406eb08c54c275a",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpMirroredHeaderPublicRuntimeTests.java", "acbecd43bf4d1079900ad7960491a9667e08df2be54579b10a10b43e3e82ea17", [
    ["diagnosticQuotaIsSharedAcrossEndpointsAndIsolatedAcrossOwners","d2d7f3dd310037960d9b05c00304b1d217ef80b4d2d21582ebfbcfcbca2ef164",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpProtectionTraceDiagnosticsPublicRuntimeTests.java", "b4a8080b89e3acd5e58837bb273de6bd38add6b5060c24e73bbdb0e5d8d096ba", [
    ["liveRotationsChangeOnlyFreshSnapshotsAcrossStopAndRestart","92e12f335b15e8679bab9887596a82b9beb327cb4d326a6884a102a9d688b885",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpRateLimitIdentityPublicRuntimeTests.java", "aa8872a5bc993d4f210ddfcd950e783627b578f3b098e2d67aa85b8bed03dc53", [
    ["allowlistedSocketPeerCanSelectForwardedIpPartitions","a204fc9c5ca2beb351f9ab37986c3d89aed0c82e8c05add8d0903a35622b7856",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpRateLimitPipelinePublicRuntimeTests.java", "337ebcbf3dd9a28bdc607ac54887e182309ee607b2ed257722171d656ea4600b", [
    ["successfulChargesAreRetainedAfterEveryDownstreamFailure","6021dbe8040107292a2b90efe2a60899cf07dc1c61690ee44de7c463fb998cf0",{"generation":{"count":6,"mode":"SEQUENTIAL","complete":6,"prior":5,"incomplete":1},"controlJoinMillis":30000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpRequestObservationPublicRuntimeTests.java", "513b71c1b4ea63fc5ac3bdc25d334e80bc075ac6cd98ece554c70b0d97d81630", [
    ["defaultOffAndIndependentRawIdOptInHaveExactLogContracts","14737cd3f0a81915d4995369ec1dcbc1f9cc133e220863b65810741ac5ddcb33",{"generation":{"count":3,"mode":"SEQUENTIAL","complete":3,"prior":2,"incomplete":1}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpRequestStatePublicRuntimeTests.java", "fdf0506a70420b77f58c3e994b10af3381fbc468b53cb2b1bdae29fead192986", [
    ["frameworkProtectedStateContinuesAcrossInstancesOnlyWithinItsKeyAndAuthorizationPartition","71cfaa270b25bb74e9da5bd3f15cb71ca3677032a55294d9903155801eab33e7",{"generation":{"count":4,"mode":"SEQUENTIAL","complete":4,"prior":3,"incomplete":1}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpServerPublicRuntimeTests.java", "8dddd90c232632cfb16ec6d8b556adcd39b889e6e23c844417ddfa30880294a3", [
    ["executionConfigurationValidatesAndOwnsOneExecutorPerGeneration","b975b804841b2bc935b2dda32c2e60777cbb607176eb36fa951a90407027d794",{"generation":{"count":3,"mode":"SEQUENTIAL","complete":3,"prior":2,"incomplete":1}}],
    ["explicitRejectAllCorsSuppressesTheOmittedConfigurationDiagnostic","d12aa4815ef29d64a7f3e33ea73207dece772245ed90ea858a1e09976da38281",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["failedFixedPortBindLeavesResourceAvailableToFreshOwnerAfterRelease","28f43e35f69d502ac0dd63d839344e4fb742a5634f0e5acc7242b2b9f3db4f66",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["freshGenerationPublishesNeverBoundAddressBeforeStartupCallbacks","819d42facf4cf68d458fde6eea570dbbbda4809af81b84028feeba66a1450d51",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["freshOwnerStartsAfterUnexpectedMcpListenerTermination","1fab5ec4e6766450253b87d95564ced201d6fd5ba5104d0d07458c304ac89b91",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["omittedCorsDiagnosticIsExactAndOncePerSuccessfulSokletGeneration","9e5320a6926c863a2c504795bd37d7b7194c16a65ebec478eca5b031e732ec68",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["sokletOwnedPortZeroGenerationsPublishImmutableDiagnosticSnapshots","1b326aad87bfce59118ba53f4899ddda2c7f5679726f108ec7a5c4cd1781a4e7",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpShutdownObservabilityTests.java", "722a89ebc0977a63f10a16192565a8d473e163dc8e8438148b4f077a812a3b94", [
    ["rejectedUnexpectedRestartDoesNotDuplicateBeforeFreshOwner","5b9ff535950effaf2c76122ba0bde4d8e343cd33e472b345f1bb5297760146cd",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["unexpectedListenerTerminationAndFreshOwnerHaveExactParity","eed46a197399f7bea5aecf968bd85d4dc51f2d1154c394d0887d8f75585fac67",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpSimulatorEveryOperationTests.java", "b8e12468aa21494d322e12317da298f09ea35762892591dd1486b5da571e2213", [
    ["recognizedRequestMethodsReplayExactJsonOrSseShapes","fa39e5dbbd988a02a9cd86504adceb4ad7ecc3c75ea52c074bc81a1ad9374da6",{"dynamicNodeCount":9}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpSimulatorPublicRuntimeTests.java", "3ba70fc29d36d31926fb7dab61ac930dbdf715b8f037a3ab54f8058e737d1c27", [
    ["concurrentSimulationsRemainRequestIsolatedAndDrainExactlyOnce","f8829bb859c7d3e0f1b8fa632fd2048587f7965a3f23c1d6180bb1218796ec67",{"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":0,"forcedShutdownMillis":250}}],
    ["defaultLoopbackHostPolicyRequiresLiteralConfiguredPortZero","703a79cfb4546cbcf6b6a5cee3b1de7e798208f5d0127f578ecf0eec12b10749",{"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":0,"forcedShutdownMillis":250}}],
    ["malformedAndRejectedSimulationsPreserveProtocolPrecedenceWithoutAdmission","d44356cee1f27b5bf78ef513c8e470688f964eb598b2c0af32219227a0f6d303",{"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":0,"forcedShutdownMillis":250}}],
    ["mcpSimulationBuffersStreamItemsAndClosesExplicitly","390e88c638a02d245cc9c3009d5e054a6d545a0ca5a3f0e8616ba4f3304eb20d",{"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":0,"forcedShutdownMillis":250}}],
    ["mcpSimulationCompletionRetainsStreamCaptureFailures","fbeaec682c155b7dae572c57bacadc4901dff99a54382dea47081d3d50624d66",{"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":0,"forcedShutdownMillis":250}}],
    ["multiRoundTripSimulationContinuesInputRequiredStateToDistinctCompletedRequest","29cbfe2e8c52e764d8a1590eb68d8f9419e67752443d91f619babad75c101662",{"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":0,"forcedShutdownMillis":250}}],
    ["noncooperativeSimulationCleanupIsBoundedAndPreservesSuppression","3d724ed986b2c966d6b3a2a08521fd180618a0ddd34571c030d18b84e018e6a2",{"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":0,"forcedShutdownMillis":250}}],
    ["nonDrainingCaptureLimitDoesNotBlockUnrelatedSimulationOrCreateTransportFailure","991cde188dc9df44f30ded80265ecbc620604b10de5f0a259ae58bbe29333403",{"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":0,"forcedShutdownMillis":250}}],
    ["simulatorRepresentsEventStreamAsOpenMcpSimulation","9578eb5a96f977d0d689fc6708b37c9f328fd6dc4bcfd49741a988d2ced02f1e",{"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":0,"forcedShutdownMillis":250}}],
    ["simulatorScopeExitCancelsOutstandingRequestsAndRestoresOffNetworkState","08bd9bd3d020f902eb7d400e5546b6a26eb19ee270c27e98f4099bdfba63a533",{"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":0,"forcedShutdownMillis":250}}],
    ["simulatorStartsRequestAgainstConfiguredMcpServer","c0569ab7319899baaa8a87fd9eef1329c5f34301c1bcc479e21406333c46d471",{"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":0,"forcedShutdownMillis":250}}],
    ["subscriptionReplayPreservesAcknowledgmentEventAndCancelationOrder","439a88e5cc10cd8454f09225d03786d374231430ec0f2d74687a09213a78e546",{"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":0,"forcedShutdownMillis":250}}],
    ["synchronousJsonSimulationUsesRealProtocolLifecycleMetricsAndBodyMode","19de5a8e0922a627d461adff3aa2b5b2a36e168c9b2d41ac7ba354a6456fbee4",{"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":0,"forcedShutdownMillis":250}}],
    ["waitOperationsHandleZeroTimeoutInterruptionAndCompletionIdempotently","dc9c9fb51e4dbb6e8e09543946ef5f8312f85e5095225f80ad657950484dd666",{"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":0,"forcedShutdownMillis":250}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/ResourceLeakTests.java", "073b617c7e9d396b7833e66f1770f7158f7d5ef94097d959592f95f018fbf4f8", [
    ["httpConnectionChurnReturnsResourcesNearBaselineAfterShutdown","e8fd5790bb67b858380a9d9f9bd04d52f86cf7f2b48a9b4427b4b79fed74d04d",{"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":2000}}],
    ["mcpListenerAndRequestReturnResourcesAfterCompleteShutdown","4a7a0a7c449f74c3418173ea9ddb7017296cb777a92c89ac8e2567f6f53c4c58",{"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":2000}}],
    ["sseConnectionReturnsResourcesNearBaselineAfterShutdown","d419a07618bf5cb500274d57e20fa7cad3351f61e0321aa6c5a5e7e902c68954",{"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":2000}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SokletApplicationDiagnosticsIntegrationTests.java", "17f86b07c07bbd800e0941712a9dd68bcdb1c539172543f219639d4dcfc0bc29", [
    ["blockedFrameworkSetupSynthesizesFrameworkDiagnosticsAndSkipsCleanup","9a5918dda5924a1f541dbfa06c552f88d53cf9921ee7c6b2397098d732f6a61e",{"phasePolicy":{"forcedShutdownMillis":0,"gracefulShutdownMillis":0,"startupCancellationMillis":0,"startupMillis":null},"controlledStartupMillis":5000,"controlledLifecycleCoreMillis":5000,"applicationCleanupMillis":1000}],
    ["blockedNestedCustomHttpAttachProjectsBoundedTransportDiagnostics","c0f41fc78417731c19d1bfeef09b1248417fd06b3cf1dfebb46e6484ccea625f",{"phasePolicy":{"forcedShutdownMillis":0,"gracefulShutdownMillis":0,"startupCancellationMillis":0,"startupMillis":null},"controlledStartupMillis":5000,"controlledLifecycleCoreMillis":5000,"applicationCleanupMillis":1000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SokletApplicationObservationTests.java", "aa2a40fc7c06659eb619c43426cd5520510bbf0415330f9993d24d06636144aa", [
    ["mixedIncompleteAndNotStartedTerminalTraceIsOrderedAndComplete","332da836de6773884c24a79f6329ec7b057a1ac17499055ac9b3e28d52f1d0ca",{"phasePolicy":{"forcedShutdownMillis":0,"gracefulShutdownMillis":0,"startupCancellationMillis":0,"startupMillis":null},"controlledStartupMillis":5000,"controlledLifecycleCoreMillis":5000,"controlJoinMillis":20000,"controlComposition":"REVIEWED_OVERLAP_OR_DUPLICATE"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SokletDirectLateStartupIntegrationTests.java", "c282c05e7fda849dab4e37fbb816989be5e387f8130894cab2816ec0db83e730", [
    ["attachmentLosingShutdownFreezeReturnsBeforeTerminalAsExactNotStarted","b2eeaa1df696e30ac832bbd2a5785eb12b71cf57a73f0cc8a6c62e03e28738da",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":0,"gracefulShutdownMillis":150,"forcedShutdownMillis":2000},"controlJoinMillis":35000}],
    ["pendingAttachProofCannotCompleteCallStillLiveAtTerminalFreeze","41dde2c355c4cf3c08fb088996f4e0576174f62b8f0f1caed0c7e334f7f9c4b0",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":0,"gracefulShutdownMillis":150,"forcedShutdownMillis":2000},"controlJoinMillis":45000}],
    ["installedAttachmentGracefullyReleasedBeforeStartIsNotStarted","5277e7f03fed96d0992752d261106bdb5785b6c71cf0d03625840bef0f57925d",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":0,"gracefulShutdownMillis":150,"forcedShutdownMillis":2000},"controlJoinMillis":35000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
    ["installedAttachmentProvenOnlyAfterForceIsForced","efed2edd62f00fe636a3c1cb9021aa175a7418b9c4bbc0dab40b916449535c12",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":0,"gracefulShutdownMillis":150,"forcedShutdownMillis":2000},"controlJoinMillis":40000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
    ["installedAttachmentMissingProofIsExactUnknown","58ad99b67b94e86fa97b87ef4bfab71721f0642d47151cca942e89926c13ed90",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":0,"gracefulShutdownMillis":150,"forcedShutdownMillis":2000},"controlJoinMillis":40000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
    ["pendingAttachEventsCannotOverrideThrowOrNullPrecedence","504eb58d505d5d0de6bc6a8ff6a3e59c9c695d1df77833dd61643a12375ae15a",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":0,"gracefulShutdownMillis":150,"forcedShutdownMillis":2000},"generation":{"count":4,"mode":"SEQUENTIAL","complete":4,"prior":3,"incomplete":1},"controlJoinMillis":60000}],
    ["pendingAttachProofAndFailureBecomePreReadyEventsOnlyAfterCommit","39f1cb69b4ab1ff19aec679261f27bf208e770a14bbf153acd1439d9336c3898",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":0,"gracefulShutdownMillis":150,"forcedShutdownMillis":2000},"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":60000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
    ["lateStartReturnDuringGraceCatchesUpAfterIndependentIngressQuiesce","c80e671d08c29469af51bd46e6a39ce9af5eab694c157744a407b6df797a28df",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":0,"gracefulShutdownMillis":20000,"forcedShutdownMillis":2000},"controlJoinMillis":55000}],
    ["lateStartReturnAfterGraceReceivesForceAsItsFirstUnderlyingPhase","5573c1caed665065ce5af010759bf60714977ab7d997e4cd41c65ef81e2e4238",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":0,"gracefulShutdownMillis":150,"forcedShutdownMillis":2000},"controlJoinMillis":55000}],
    ["startReturnAfterTerminalFreezeIsInertAndCannotRewriteUnknown","6684b734bd393abb015bbe408003bf20a428c2867a195e5ed40a58d31b06d907",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":0,"gracefulShutdownMillis":150,"forcedShutdownMillis":2000},"controlJoinMillis":55000}],
    ["shutdownBeforeClaimedStartWorkerEntryDeliversOneDeferredPhase","f9a0f2e4f5b3fc1e778cb1e31dee117cf968270c13523003bcfb294710714c8d",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":0,"gracefulShutdownMillis":20000,"forcedShutdownMillis":2000},"controlJoinMillis":40000}],
    ["rejectedStartWorkerLaunchClearsClaimAndRollsBackNotStarted","1d647c883f0c76d5ec5f4706155135c2dd0b5d6aa965a139cc2c8a7d7a98c080",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":0,"gracefulShutdownMillis":150,"forcedShutdownMillis":2000},"controlJoinMillis":15000}],
    ["catchUpFailureIsSecondaryEvidenceToExactLateStartFailure","0c5decad4caa29884e70b6d2071f01af15783c42d00fc8e8cf8338ca117a666a",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":0,"gracefulShutdownMillis":20000,"forcedShutdownMillis":2000},"controlJoinMillis":45000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SokletDirectSseCompositionTests.java", "c0f6272c8bc5a81c557125a7af7de103f3adb60868821ada57b3c28389d8944e", [
    ["lifecycleOwningDecoratorProofCannotBeBypassedByItsDelegate","08aa0e7d39eb5d42234d028d681b890208fcc314bf876e25df1934e8140f1492",{"phasePolicy":{"startupMillis":2000,"startupCancellationMillis":100,"gracefulShutdownMillis":100,"forcedShutdownMillis":100}}],
    ["transparentDecoratorSharesTheRootMemberAndRoutesTheSseSurface","056d958ecde57f862345f377bceb24c72f171358a4caea5b094b7c8bca61dccf",{"phasePolicy":{"startupMillis":2000,"startupCancellationMillis":100,"gracefulShutdownMillis":100,"forcedShutdownMillis":100}}],
    ["twoLevelOwningStackRequiresEveryNestedMemberButRemainsOneParticipant","2a43506b1af7f72ae403383390632d0cd453510154bd76c3e43f80fc8026ef91",{"phasePolicy":{"startupMillis":2000,"startupCancellationMillis":100,"gracefulShutdownMillis":100,"forcedShutdownMillis":100}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SokletDirectHttpCompositionTests.java", "dde15ee1ef0bca779604118ea55ed1395ee71ad0afbec3d56d61655f66f4f028", [
    ["transparentDecoratorSharesRootSignalRuntimeAndRequestPath","31650cc34a826d48d01a5ecaff17ea9772ae782d9a5e52c722afbfd65c0a8076",{"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":15000,"forcedShutdownMillis":3000}}],
    ["lifecycleOwningDecoratorRequiresDelegateAndOuterProof","ccede64a5c8aff78211793f6feef733cdc2821ff84f9b05ddc739d6c5d03f209",{"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":15000,"forcedShutdownMillis":3000},"controlJoinMillis":10000}],
    ["twoLevelOwningDecoratorsRemainOneConfiguredParticipant","f352131b4402da5ffb2251f1d14b23732799297d2c5fecce0458b0cf8c973a13",{"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":15000,"forcedShutdownMillis":3000},"controlJoinMillis":18000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SokletDirectTerminalPublicationTests.java", "4e5de0de02e740918a650efb94d8e4fadf9a503f48cf805f4fcb364cbc8774ee", [
    ["blockedPreRegisteredContinuationCannotStrandPrivateOrPeerOwners","07868a752d00075bf0cf1e994a19e2540402b3ffc87dc6050ee8a5bb1d48de19",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SokletDirectTerminationPrecedenceTests.java", "aa6035bb768d1645be55a8f627bad1bfcde5a47c73e8f3bb8e28842b01fb9630", [
    ["ownerShutdownIntentWinsFormerGroupFanoutGap","d6949788a822a3a5e1b0fd22bf59e2e74cc3ae1e43842ea9b174512e0a4d9b33",{"phasePolicy":{"forcedShutdownMillis":30000,"gracefulShutdownMillis":30000,"startupCancellationMillis":30000,"startupMillis":null},"controlledStartupMillis":5000,"controlledLifecycleCoreMillis":5000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SokletSimulatorIsolationTests.java", "c06b49797a68adabd3cf359e9757763e123a2897a9c1f7c3d588bd94e6fbda8b", [
    ["blockedFrameworkSetupUsesOneExactStartupAndRollbackSchedule","eac5c53752d9133307d28085084de635085184b942b11a115da30e90c4430a5e",{"phasePolicy":{"startupMillis":2000,"startupCancellationMillis":1000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":2000}}],
    ["concurrentFreshScopesDoNotCrossDeliverCallbacks","3ed0428c59239dff98ab82c223aa9e17fe9f6552b79d08e56810b98f98f5a1b9",{"generation":{"count":2,"mode":"CONCURRENT_OR_ALTERNATIVE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":15000,"controlComposition":"REVIEWED_OVERLAP_OR_DUPLICATE"}],
    ["customParameterAndInstanceProvidersAreFreshPerFactoryScope","202ed6d46319a886a5c7ab120d1b684d823238f0d75b569c6204819d63e4beac",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["defaultParameterProviderBindsEachFreshFactoryConfig","4c4f70f41b85b16c61100a7932f8b7517e1c8970fbda99fa64366ebbde94ebf7",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["factoryRunsExactlyOnceAndSequentialScopesUseFreshGraphs","b69b00802331c89585a05dead76d54d364918bbb9191db11db84452174a3e836",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["incompleteTeardownPreservesBodyFailurePrecedenceAndFailsSuccess","db3572dac7fef7527d7fc7f4b02ea5f26cf565ca5d2074b3ca69f022b7d48dac",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["liveMcpStartQuiescesBeforeCancellationAndCatchesUpToForce","b379a0ed0127b8ff4cb1bfe4a80fa70c3f77ed042f11989cf99f09556fead710",{"phasePolicy":{"startupMillis":2000,"startupCancellationMillis":1000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":2000}}],
    ["mcpParticipantStartsBeforeReadinessAndUsesLifecycleClockBudget","1b48409b8c4a9291f065724b30d26bc848aaff2db9db87202d7e91bd60dddb92",{"phasePolicy":{"startupMillis":2000,"startupCancellationMillis":1000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":2000}}],
    ["rejectsMultipleMcpBuildsAndEscapedBuilder","f7d9ebcfbf4e89dbefcb2a8049729f29b6f25a072452f43fc1ac4fdb5d362b29",{"generation":{"count":2,"mode":"ONE_FULL_PLUS_PREINIT_REJECTIONS","complete":1,"prior":0,"incomplete":1}}],
    ["rejectsProductionAndForeignTransportsBeforeInitialization","e69c96f11138eab3c3bd75f321491b75f448644489fa3735eac3d61afe9e2186",{"generation":{"count":3,"mode":"ONE_FULL_PLUS_PREINIT_REJECTIONS","complete":1,"prior":0,"incomplete":1}}],
    ["sealedScopeRetainsRejectedMcpSessionUntilRollbackTerminates","f090a4ae6064025fd0b9ef98b96c8d70647d01d949e4f1779a610c5b23926d4f",{"controlledLifecycleCoreMillis":5000,"controlJoinMillis":30000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["teardownLaunchFailureNeverReplacesPrimaryAndRetainsProofGraph","491908fd4449000956720e590603e7bb1b4a3eb374c1500b4fec680fdf68f3aa",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SseTests.java", "ea826fcf2a8a7c9858dd07054b58bf7cfdb1f915264a9be3518ae3bee6bd11ab", [
    ["staleSseAcceptLoopFailureDoesNotClobberRestartedServer","8fa72e23163470ae54388eda1298ea902441e49999401846f23fec8472dbbf65",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlledLifecycleCoreMillis":18000}],
    ["sse_startStop_doesNotHang","5cbc10d780680e3632c03d3c06e704572efa3f5b047b96b7b7f77079d67492cd",{"phasePolicy":{"startupMillis":3000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":0}}],
    ["sse_stop_allowsIsStartedDuringShutdownWait","176eb363b3c17feade38f0fc28d1bf5a68f45afe747e88e4566660c923bf5920",{"phasePolicy":{"startupMillis":3000,"startupCancellationMillis":1000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":0}}],
    ["sse_stopGracefullyShutsDownRequestHandlerExecutorBeforeInterrupting","d2ac819b64043436ab3667f9b04695649be071cc6e74b7fa264f4aceb12562ec",{"phasePolicy":{"startupMillis":3000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":0}}],
    ["sseServerCanRestartOnSamePort","f9bfbe60f17f243ea82e7d0f68abca6378dd6bcc3e10f4671bea0aa9eceeff9e",{"phasePolicy":{"startupMillis":3000,"startupCancellationMillis":1000,"gracefulShutdownMillis":5000,"forcedShutdownMillis":0},"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["sseStopDrainsQueuedEventsBeforeClosingConnection","237fa22f485ecdf5887ce86002b8d7a9c162b7a753f6a5edab1e4bea77946f8e",{"phasePolicy":{"startupMillis":3000,"startupCancellationMillis":1000,"gracefulShutdownMillis":5000,"forcedShutdownMillis":0}}],
    ["startRejectsRunningSseGenerationWhileItsStopIsInProgress","9303908c0e9f97fd45adc11e233d34ed035fef47e18574fd752838d11cafbbd7",{"phasePolicy":{"startupMillis":3000,"startupCancellationMillis":1000,"gracefulShutdownMillis":5000,"forcedShutdownMillis":0}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpHttpServerApplicationExecutionTests.java", "fa0df41b3787facb9cd275e117b9ec8e6beb38db27a4e4d256f6a756f35bc76b", [
    ["application_executor_factory_failure_restores_a_restartable_runtime","1ac27f10c6e27dffdfbd4478b123692c4ebc8d1eed3190bf49e0b5eb81f303ff",{"generation":{"count":2,"mode":"MIXED_MAX_PLUS_SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":5000}],
    ["lifecycle_grace_preserves_active_handler_then_force_interrupts_without_promoting_queued_work","b2298faf5919d0ddfb071e8a493c18b3b8d8fafa721f0a1188fba621d341ff90",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["shutdown_reports_residual_application_work_and_blocks_restart_until_exit","0168dce25a9f381c15c6b919637388cf236960743a70f23ea37fe954c058c8af",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpHttpServerCustomHeaderTests.java", "ff745b796d527d88bdcda91190d69d1a88e8786c91653f253da49522777f9480", [
    ["custom_mirror_registration_is_scoped_to_the_selected_tool","073c02dfce00b18f5b22cec884dd29001ca1c8448351bbf02c9fe489eaebf677",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpHttpServerNotificationTests.java", "11c8f9ecf09e56feba57277f9f29a61fd3653e206557b645aea5fa8185b7b3ed", [
    ["classified_notification_cors_matrix_preserves_headers_and_rejects_origins_early","b7e6775539d89ca7446603040c8aa4d20425cc264363f0f500d5bee301feb6ad",{"generation":{"count":6,"mode":"SEQUENTIAL","complete":6,"prior":5,"incomplete":1}}],
    ["notification_admission_outputs_fail_closed_on_reserved_codes_and_unsafe_headers","52e4c3411669eb14f543faf5f70e15c50724170cd3cd47f9639002f5c5b30cbe",{"generation":{"count":4,"mode":"SEQUENTIAL","complete":4,"prior":3,"incomplete":1}}],
    ["notification_policy_failures_fail_closed_without_a_json_rpc_body","4e66a46c96247f426624b012efbd84e2efbcc98f4209cdf864beefb7304036f6",{"generation":{"count":4,"mode":"SEQUENTIAL","complete":4,"prior":3,"incomplete":1}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpHttpServerPolicyPipelineTests.java", "fa8685487c25d1a953b0e538085279f35431a2dfd80619449b57edbb0622eedb", [
    ["policy_null_exception_reserved_code_and_unsafe_header_fail_closed","998e3a54b55f4ff58e1b75ac8d31c00e649a2d93dc73abaddc6e31ae4cad7893",{"dynamicNodeCount":12}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpHttpServerRequestScopedSseTests.java", "40f4f954a11b77b754d8d40a412cef13bebc0020fb740a4507a4260b5fcab454", [
    ["shutdown_closes_committed_stream_and_runtime_restarts_cleanly","1b56c480e7618c105d448e1c5f45e17d4967bac50ef9f70443d8ecdf442784b4",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpHttpServerRuntimeTests.java", "a89a6e0e051e99b8f241b7d9680303857ead0b7d3bcd455fc8553bce8422af2e", [
    ["absent_origin_policy_and_cors_hook_failures_fail_closed","4fbe4b9238dad19fbb1816f4f535c22bade382e7cfd53105c72a06c9e8da31a1",{"generation":{"count":3,"mode":"SEQUENTIAL","complete":3,"prior":2,"incomplete":1}}],
    ["alternating_mcp_instances_keep_discovery_state_independent","389ee827fb7bf2d128b64b2a0175b4ed25353576870784c57a449630cc7149d4",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["construction_does_not_bind_and_failed_start_is_restartable","9de0b2c9289c7f6e5e94e7ad1e86a6edd44fffedfd17919dc7ec76959a436fc5",{"generation":{"count":2,"mode":"MIXED_MAX_PLUS_SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":5000}],
    ["cors_preflight_fails_closed_for_authorizer_values_outside_mcp_surface","6fc8f7bc8500e1735c182d9f7e016aaa1916a15307ef9b81bf2affc5cebc00bb",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["cors_rejects_present_origins_by_default_and_reuses_shared_authorizer","634dc2554c4a27451112435f43502df7a022122e86c2598c68481b469b6cfec4",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["diagnostic_sink_failure_does_not_fail_listener_start","f23f881b2cdd4e7bd7974db7e9d24e459c89b10acbaf1c575a32ffa81ec86b77",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["disabledLifecycleUnexpectedEventLoopRetainsFailureUntilLegacyStopCleanup","93d51a00640b73a4d3c854181bdd4b74b7c2198b3c29bb8b26249c8a2a3469a5",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["explicit_cors_authorizer_suppresses_omitted_authorizer_diagnostic","080c214278dfb9c43dea9c1d92aef97f0065ca3153f5b3a9208f23340701a050",{"generation":{"count":4,"mode":"SEQUENTIAL","complete":4,"prior":3,"incomplete":1}}],
    ["lifecycle_is_idempotent_and_restartable_with_a_fresh_listener","25a32bef444ad324baab818353fdfe04747d86935f5cd351d57e5ed6bacda838",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["omitted_cors_authorizer_emits_fixed_diagnostic_once_per_successful_generation","01e503d71abc321735d18007f15f847bde458a271d4e53c737cf39b554cde7d3",{"generation":{"count":3,"mode":"MIXED_MAX_PLUS_SEQUENTIAL","complete":3,"prior":2,"incomplete":1},"controlJoinMillis":5000}],
    ["residual_admission_work_blocks_restart_until_it_really_exits","9ac3fa3e910140166d79b3f2f124407ba0a37eed81558bae8c0c90feb8834cbc",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":5000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
    ["residual_transport_is_a_stop_failure_and_blocks_restart_until_exit","5d36d301f6af6a6dcf2913d1068360d7e084c59a8d12cff1e765ef9fb09808c1",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["submit_after_stop_boundary_returns_unavailable_and_releases_lifecycle_admission","cdfd7bae3d83676f7ff223942b067874458290b828d1ed8abd8cf9908d90d277",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/StreamingResponseTests.java", "91ac4db01c29b6ac9f33e3fa212635fb3956328f5b26fcf6f869133b1ad35f6c", [
    ["simulator_admitted_stream_error_callbacks_survive_scope_seal","68d3926e709b362f58cfdbd18eb253ce55c1d33a83bccda9976b3ad30480bbfa",{"controlJoinMillis":10000,"controlComposition":"REVIEWED_OVERLAP_OR_DUPLICATE"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpFinalTagGoldenWireProductionTests.java", "785f4cb0b0339d65986007d2944c90709a2591843c4e4a86a67b96934ee8416d", [
    ["checked_in_phase_5_subscription_messages_match_the_production_listener","30e28657661007a8a5c81bdd659454a29443f15d0ef7eb72f786f2aa0e3cc8ed",{"controlJoinMillis":0,"controlComposition":"REVIEWED_OVERLAP_OR_DUPLICATE"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpMultiRoundTripTerminationRaceTests.java", "ef6947d21a1b6bf57471bdd6c9ace0f5d36bdcf7d7acb3b7f076b4c76bfb5537", [
    ["blockedCustomProtectorOpenMakesShutdownResidualUntilProtocolWorkExits","4e2940aadf6b81531aaf2c9fb67f63df03035d28ebd927256a287f35d629bdf2",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"requiredAction":"RAISE_OUTER_BOUND"}],
    ["blockedCustomProtectorOpenDiscardsLateResultAfterDeadlineOrDisconnect","9fe0428fbf8a051f8856858b80b43e838880af781f3cf347ae86a50a84792910",{"dynamicNodeCount":2}],
    ["blockedSealCannotPublishLateInputRequiredAndReleasesExactlyOnce","9fa9b58c00aca22633217bf700bf7e481d25fd151076c03d2621399daecb4529",{"dynamicNodeCount":3}],
    ["conditionalCapabilityHoldTerminatesWithoutProgressOrLateResult","e2a6c3af0f11bbd8c32e537b0c7209aebfeb71932f14c3f78dcf445f1d2d9bd1",{"dynamicNodeCount":2}],
    ["sameAuthenticatedStateCanBranchWhileOneFreshIdTerminates","e2658db8bc19db08af218c4aa7408c71c66d309caa4d4edfe61b30847f57274c",{"dynamicNodeCount":2}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpProtocolProfileRegistryTests.java", "a3992b64a6d35eb6fb0156c097698b7c9e627c61824f93bad4d6c7389495ffe9", [
    ["fakeProfileEntersOnlyThroughTheExplicitRuntimeTestSeam","6f7e90f54ed0a74a1f86ef1cfd4723d2b55367c9552e86498838855cf2773a1f",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpResultEnvelopeGoldenProductionTests.java", "52981d60f5ccceed4132a32e71578106ca3a8e6371c08b59214604c13b34e566", [
    ["everyFrameworkAndApplicationCompleteAuthorityMatchesGoldens","1885422c70571967495efd6bfd4ed3d14e4025c671c861b04cfc49adeb75b0d3",{"generation":{"count":3,"mode":"SEQUENTIAL","complete":3,"prior":2,"incomplete":1}}],
    ["requestScopedAndSubscriptionSseTerminalsMatchGoldens","65116277d2225f3c5cec47919879ceef40e09074c286be7dc88eae2575260a20",{"generation":{"count":3,"mode":"SEQUENTIAL","complete":3,"prior":2,"incomplete":1},"controlJoinMillis":0,"controlComposition":"REVIEWED_OVERLAP_OR_DUPLICATE"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpSelectedProfileBindingTests.java", "c8f22db6a80c69bcf28cd800b17626cbbae6d0b967580d9f034c78d63b49422d", [
    ["subscriptionAndSimulationRetainTheSelectedProfileForTheirWholeLifetime","d1973555f04b5023222f6251df7ae83dc4cfd92d164cd14dfd03487778172914",{"generation":{"count":2,"mode":"MIXED_MAX_PLUS_SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlledLifecycleCoreMillis":50000,"controlJoinMillis":15000,"controlComposition":"REVIEWED_OVERLAP_OR_DUPLICATE"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpSubscriptionPublicRuntimeTests.java", "452e7b37e1cefaf2c3eaa4856fc3533ffef919e2febb73a3b08f08cbf4d27567", [
    ["gracefulHttpShutdownEndsWithOnlyTheTerminalCompleteResult","4ba385f13faff066efff356641c7489ffbb73a7f9279299f9598a30076d8488e",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["nullAndThrowingAdmissionNeverActivateOrConsumeSubscriptionQuota","055f629808451d57a1e33dccc412a45aceff06f46e9dfa04234c094932a8901a",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["publisherIdentityIsGroupedPerServerAndSharedAcrossServers","88d5a74bd6fe207fa20b977750c0d235fbc520284ace2560a8b39730e523e42c",{"generation":{"count":3,"mode":"SEQUENTIAL","complete":3,"prior":2,"incomplete":1}}],
    ["validListenUsesAdmissionAndRequestLimiterOnly","67c47e3a7e886bdb3510213238212bd95c1332361587f9dda29bdafe49c47317",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpSubscriptionRuntimeBoundaryTests.java", "5d586d7c481da85cea944db74118c9b367579b2e7ed9ce9a442b6b3556d54696", [
    ["gracefulShutdownReservationBeatsConcurrentPublisherExactlyOnce","975a4aa52ecee3afb3c905969b29d88ff12e1da27d04002ecdbe3634776a2662",{"controlJoinMillis":5000,"controlComposition":"REVIEWED_OVERLAP_OR_DUPLICATE"}],
    ["blockingRegistrationCloseIsBoundedAndNeverRetriedConcurrently","e3329ccdae4d8c4e5cb6743b0fa76199f1afa3662a9e44e35279144ce9d83f40",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["deactivatedGenerationCannotPublishIntoRestartedServer","7d47ff10bbfd4624e0802078ca71b36eceec76b30496b1e967735c43fbadbdcb",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["failedRegistrationCloseIsObservableAndBlocksRestartUntilRetry","4e194cff09de95633ca26f7459406799379f5a2a4a298d777591ae264b0d71ce",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["startupFailureRollsBackRegistrationsAndCanRestart","34882cdd2bc656ee979db7386c04c51fca7542615eda6b565c16a09cc2982f9f",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["startupRollbackCannotBeHeldPastItsShutdownDeadline","6d37c78d40847738ed6540c919eae1931d923b782397bbcb48664149d109c11a",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["startupRollbackRetainsFailedCloseUntilSuccessfulRetry","e2df580afb7217d33dbdabf217dd679789b2c282e7d17f65f57f7ea1aa33cc8a",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpQueuedExecutionWinnerElectionTests.java", "1addd4728b2740565bd56042455c41e0d8a3b3f9c4ce0a047d3dca1dc3390bf1", [
    ["all_queue_promotion_deadline_disconnect_linearizations_elect_exactly_one_outcome","8be5079ecfbe8cc5c5ca23e3a7f74a8fd6c54dbb81638b38ffacee243a80b996",{"generation":{"count":6,"mode":"SEQUENTIAL","complete":6,"prior":5,"incomplete":1},"controlledLifecycleCoreMillis":5000,"controlJoinMillis":240000,"requiredAction":"RAISE_OUTER_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/transport/McpTransportContainmentSpikeTests.java", "a08cc494cef036237d46afe944816fb442dad8c245d0f76cd7632e248e6b5899", [
    ["containmentMatrix","382671af5e8be26bdca44fc59df1c702342a41c0a94cf241d29964ed59c81f4e",{"dynamicNodeCount":15,"controlJoinMillis":8000,"controlComposition":"REVIEWED_DYNAMIC_NODE_MAX"}],
  ]),
  ...reviewedScopeFile("src/test/java/examples/mcp/McpLocalizedCursorFleetApplicationPatternsTests.java", "6f7468fb21915174c0449ddc87e8ce7e4a983ca70949142c2c70e64cfe75b129", [
    ["cursorFailuresPreserveOpaqueBytesAndCollapseToOneNeutralError","6872f017136a7cd55632e4c78cbbe64d31a4c684eca420b7f549bed046135d5a",{"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":1000},"generation":{"count":10,"mode":"SEQUENTIAL","complete":10,"prior":9,"incomplete":1},"controlJoinMillis":100000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","requiredAction":"RAISE_OUTER_BOUND"}],
    ["localizedCursorCrossesNodesWithStableSnapshotLocaleRevisionAndPageBounds","f0fc6e0582bb2e774698f38cabfbf4f8af4ae3a99d4984c8d4f52d5e5b65b15f",{"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":1000},"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":50000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SokletApplicationProcessTests.java", "9beddfae3cc337ffd3eed91fad8a54865d3715ec65d528bd5573c0eba8061463", [
    ["startupFailureAndTimeoutRemainPrimaryWithIncompleteRollback","214817a6c49a1817c06c4854ab3f70dbb5d37a78d0338534b85282710acab8d5",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":0,"incomplete":2},"applicationCleanupCount":2,"incompleteBranchCleanupCount":0,"terminalReportCount":2,"requiredAction":"RAISE_OUTER_BOUND"}],
  ]),
], 'lifecycle scope topology');

const REVIEWED_PHASE_POLICY_OVERRIDES = checkedReviewMap([
  ...reviewedPhasePolicyFile("src/test/java/com/soklet/McpHandlerMetricsObservabilityTests.java", "32fc44abfca07b8f45f04f5288ba9f80e5ea68bf96d7ba77b14ec561e0cef113", [
    ["defaultCollectorAggregatesConfiguredZerosRendersFiltersAndResets","1519ae9fd7f36f20cbb74dfcc21cb194cd490754293ccacd40e866037f45d0c9",{"forcedShutdownMillis":3000,"gracefulShutdownMillis":15000,"startupCancellationMillis":2000,"startupMillis":30000}],
    ["sokletOwnedSaturatedListenerEmitsExactServerWideTransitions","4aea13bb236c46f47cd6292abcb1b30ed6170f9a95ac5a9ecc0989ae252ed635",{"forcedShutdownMillis":3000,"gracefulShutdownMillis":15000,"startupCancellationMillis":2000,"startupMillis":30000}],
    ["queuedDeadlineDequeuesWithoutExecutionAndRetainsActiveGauge","072447bdc3fac24aeb356e1707048174fa8010606d15ca4ef6dd5ac6e4986ce4",{"forcedShutdownMillis":3000,"gracefulShutdownMillis":15000,"startupCancellationMillis":2000,"startupMillis":30000}],
    ["queuedDisconnectDequeuesWithoutStartingHandler","df914124553ef923f5a9f5fdb5856b9b2695dc0ec87ccc96af6ec85f595ce1d0",{"forcedShutdownMillis":3000,"gracefulShutdownMillis":15000,"startupCancellationMillis":2000,"startupMillis":30000}],
    ["managedResidualShutdownDequeuesAndFreezesGaugeAcrossLateExit","c2dfc60d3bfdb910ade54147e914aaf63e1082fa1c7887a681beebf9a6b8a30d",{"forcedShutdownMillis":100,"gracefulShutdownMillis":100,"startupCancellationMillis":100,"startupMillis":5000}],
    ["managedStopDefersQueueAndExecutionCallbacksBeyondLifecycleLocks","d2df586718415b34b86815ab8657d36d8b7d5d1a910a9c8147ee40a6137fb0ab",{"forcedShutdownMillis":3000,"gracefulShutdownMillis":100,"startupCancellationMillis":100,"startupMillis":5000}],
    ["unexpectedTerminationDefersQueueCallbackAndFreezesTerminalGauge","793f4e9e54a7af687d9f77f9d8b5916b83acfbff859e650e797458eec3eaf57e",{"forcedShutdownMillis":100,"gracefulShutdownMillis":100,"startupCancellationMillis":100,"startupMillis":5000}],
    ["handlerMetricsCollectorFailuresAreContainedAndLogged","6064ee632f9baf05b0ebcbd977078adf42c02bcfbe724dd2994092afc324bd54",{"forcedShutdownMillis":3000,"gracefulShutdownMillis":15000,"startupCancellationMillis":2000,"startupMillis":30000}],
  ]),
  ...reviewedPhasePolicyFile("src/test/java/com/soklet/McpHandlerQueueDiagnosticsPublicRuntimeTests.java", "74294585a9c72e23bca246e064cddaf24816e9accb514e1290d684a927f3ec59", [
    ["configuredValuesAndZeroLoadRemainStableAcrossFreshCleanOwners","694802f3c6e168d727ac1e01ac3718cda27a15d4b82324da139ac3d9f34853d6",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["crossEndpointSaturationPublishesRetainedAndBoundedConcurrentTuples","fb3590678ec02fbdf94226c1306eda1c6aac83a8b0d47044541524fff64d6b7b",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["residualStopRetainsOneActiveAndDrainsQueueUntilLateExit","347e37f2f42a9cd5e9cfc93f0de8b2c4ec9e49cb4864fc5637219cd98ff129b1",{"forcedShutdownMillis":100,"gracefulShutdownMillis":100,"startupCancellationMillis":100,"startupMillis":5000}],
  ]),
  ...reviewedPhasePolicyFile("src/test/java/com/soklet/McpLifecycleB3Tests.java", "865ac2adca9b655732d92525be4b47531682754a4277f9f37e58236190843c5f", [
    ["unaryAdmissionIsGenerationScopedAndReleasedExactlyOnce","5b23ff19049963ce60f188e0483f140e37810e5dd31b614ff529b7744f049f5d",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["cooperativeHandlerOutlivesPromptStreamClosureAndDrainsGracefully","88963287bc0844ab0e7c9db0d52dbe6184bcf392e9efc404baf1131ecc150859",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["unexpectedEventLoopFailureFencesBeforeProofAndRetainsAddress","f8bd3d7a2e245e1985a584b5881cb40b051e5fa94c366aebe179914ee592b4fe",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["eventLoopFailureAfterRequestedStopRemainsOrthogonalEvidence","b859e255b25970fbd111586feca1931f4bdf15094a274c8cdb9e5acec1e1e3b3",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["oneShotOwnerCannotConsumeUnexpectedGenerationBeforeExactResultPublication","5a50cff66d4c6138d313719cdc4d85110815f2f56595d5725d163c0468bd3a8d",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["unexpectedEventLoopSignalsFailureBeforeAdmittedHandlerTeardown","b04397b0db5a6ad17082e56b657ffec1f84bf89ae320ed4b023aa1745a113552",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["startupErrorPreservesIdentityAndFreshOwnerStartsAfterNeverBoundFailure","da8427c7ddfc8d6fd563fd0f4fa0f672cb37451b92137b7a1744c0a795becc8b",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["fixedPortBindIOExceptionPreservesExactCauseForFreshOwnerAfterRelease","1f2e5d20223b3f85dba86d8e7316e8855e25751e8c15294670b1003b07ce9042",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["postBindPreReadySubscriptionFailurePreservesExactIdentityAndAddress","8f130a8cf179c6494b6d0ec5770972b5f0b5e57c7582abc1b1311a4da3be031a",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["idleSubscriptionClosesPromptlyWithServerStoppedAndNoForce","4233874ac07bd50cab9a52a5c215c8b943ade0a0dbfcd28a1dc5378ed34400ea",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":10000,"startupCancellationMillis":2000,"startupMillis":5000}],
  ]),
  ...reviewedPhasePolicyFile("src/test/java/com/soklet/McpRequestStatePublicRuntimeTests.java", "fdf0506a70420b77f58c3e994b10af3381fbc468b53cb2b1bdae29fead192986", [
    ["applicationProtectedStateRoundTripsExactlyWithOneSharedContext","9c5f5457999a4363581ac04f5851780a43b528f6db6df226557884b8f2e5e190",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["frameworkProtectedStateCompletesOnlyWithAFreshRetryId","8171f90680a48cbfb2b3443a72a98ad09fb69b2054f740f0deb6bedcff50048e",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["frameworkProtectedStateContinuesAcrossInstancesOnlyWithinItsKeyAndAuthorizationPartition","71cfaa270b25bb74e9da5bd3f15cb71ca3677032a55294d9903155801eab33e7",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["malformedTamperedAndUnavailableStateHaveFixedPrecedence","97a25a6a5ed03ec05f770f7310eb9c572a86f3b3c039fb3a422f2920d1a97c42",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["resourceRetryStateForcesPrivateZeroTtlAndNoStore","51c83f96cdb85704c397cf9512af9191f467914f6dfc4fbbd5150c0da7aec0dd",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
  ]),
  ...reviewedPhasePolicyFile("src/test/java/com/soklet/McpShutdownObservabilityTests.java", "722a89ebc0977a63f10a16192565a8d473e163dc8e8438148b4f077a812a3b94", [
    ["managedCleanStopEmitsOneMatchingLifecycleAndMetricsOutcome","57952075332be33ff2eabe7fa533875dd7704f42c08f016ac3e0af44739ae173",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["lateShutdownFanoutCannotReopenTerminalMetricsDeferral","62808181d1448fe7cc661e846694b9eaeb13ab1be8d3aa60681733ba13dc057e",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["freshOwnerCleanStopRecordsOneLifecycleAndMetricsOutcome","cafb9e7ddd819d57015112ecc2aefa69d79464fd9b2e2a3bc5e6561591182539",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["didStartObserverFailureDoesNotVetoOwnerOrCleanStop","06dabe9d10277976f9ad233f3c586c75fdeda3d161f6f55db9c31a0c45c1448c",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["failedSubscriptionRegistrationCloseRetriesAtForceBeforeOneForcedOutcome","d9eac7e8bae371037f008b94ad99b07a4cdd462f7df1820dc2cabec60ee77183",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["blockingSubscriptionRegistrationCloseFreezesOneResidualOutcome","7b2b97b1d356fb6e0b933472e5ce26561a14b67597c4af2ee325029af703e9df",{"forcedShutdownMillis":100,"gracefulShutdownMillis":100,"startupCancellationMillis":100,"startupMillis":5000}],
    ["unexpectedListenerTerminationAndFreshOwnerHaveExactParity","eed46a197399f7bea5aecf968bd85d4dc51f2d1154c394d0887d8f75585fac67",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["ownerNormalizesUnexpectedGenerationExactlyOnceAfterAdapterWait","7978de5a4927df4328c63e3aefc30da8c94ad897d83b760f347c10567df032f6",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["rejectedUnexpectedRestartDoesNotDuplicateBeforeFreshOwner","5b9ff535950effaf2c76122ba0bde4d8e343cd33e472b345f1bb5297760146cd",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["failedStartCleanupEmitsOneExactForcedServerStoppedEvent","1555a79ab0b00b149740ec8e122898d7ee5a90e299bb4a0846c0db9151db4865",{"forcedShutdownMillis":100,"gracefulShutdownMillis":100,"startupCancellationMillis":100,"startupMillis":5000}],
    ["shutdownMetricsCallbackRunsOutsideServerLifecycleLock","3b73c31b701bc2851739dee19c3fd498e58295307c9ea5c265730c05e798ab1f",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["shutdownMetricsCollectorFailureIsContainedAndLoggedOnce","6b8e05f67f30f818b6102eed77c771de3387cab4d5c582467520c6618a2f2698",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["residualStopAndLaterExitDoNotDuplicateLifecycleOrMetricsOutcome","5cda4bda74e4d20403596e1b76297363c65418098a529092fbd611a7f24224ad",{"forcedShutdownMillis":100,"gracefulShutdownMillis":100,"startupCancellationMillis":100,"startupMillis":5000}],
  ]),
  ...reviewedPhasePolicyFile("src/test/java/com/soklet/SokletDirectHttpCompositionTests.java", "dde15ee1ef0bca779604118ea55ed1395ee71ad0afbec3d56d61655f66f4f028", [
    ["forceBeforeChildProofCancelsUnsubmittedCleanupWithoutRejection","531e637e382fcd931afcd981ddb7ac107e757e86bbce673f2e61ef46c973157b",{"forcedShutdownMillis":2000,"gracefulShutdownMillis":75,"startupCancellationMillis":100,"startupMillis":2000}],
  ]),
  ...reviewedPhasePolicyFile("src/test/java/com/soklet/SokletDirectLifecycleRaceTests.java", "53d6b9c908a173496be5098900fb000e40d85d54fe170919fd4b23b5c4ed386c", [
    ["closeAfterStartClaimCannotPublishNotAttempted","4f3f9e9b90c939abcc3b7c54e1a430530b57f313b49d89e1b1ef3cd934eb0f3b",{"forcedShutdownMillis":80,"gracefulShutdownMillis":80,"startupCancellationMillis":80,"startupMillis":5000}],
    ["lateBlockedAttachReturnIsInertAndCannotEscapeTerminalEvidence","8b53b50a6a76b4862be3c151aa2778e90a62ce02f8762da37fc909270dce65fc",{"forcedShutdownMillis":80,"gracefulShutdownMillis":80,"startupCancellationMillis":80,"startupMillis":5000}],
    ["installedAttachmentWithActiveWrapperRetainsTransportResidualEvidence","524700bdf9511b6e67b86795791c15a811303a9548adddcd9d35ad89b05a3d4d",{"forcedShutdownMillis":80,"gracefulShutdownMillis":80,"startupCancellationMillis":80,"startupMillis":5000}],
    ["resolverCancellationSentinelDoesNotBecomeStartupOrResultFailure","eaa8a0b45f82f499b126558624008ab5826e0b426e4481294a416dac65e4c7d0",{"forcedShutdownMillis":80,"gracefulShutdownMillis":80,"startupCancellationMillis":80,"startupMillis":5000}],
    ["transitionWorkerLaunchFailureCannotStrandReadyOrTerminalPublication","369e62ca9da67c727b46b00a59a4098b351165da69fa983c38857a1b8d1361eb",{"forcedShutdownMillis":80,"gracefulShutdownMillis":80,"startupCancellationMillis":80,"startupMillis":5000}],
    ["shutdownAfterReadyLinearizationCannotRetroactivelyCancelStartup","d13d4d3aa30f2beacf90124c91a79bdbf949a966637839d5e4c12b11301d1968",{"forcedShutdownMillis":80,"gracefulShutdownMillis":80,"startupCancellationMillis":80,"startupMillis":5000}],
    ["interruptResponsiveActiveStartTimeoutRemainsTimedOutNotUnexpected","391d25921ac8d7548d149a0dd9cf216164e0d8e2f29bfd29b27699f2be100a4f",{"forcedShutdownMillis":80,"gracefulShutdownMillis":80,"startupCancellationMillis":80,"startupMillis":150}],
	["externalCloseOfInterruptResponsiveActiveStartRemainsCancelled","6012462e94a9b929ee1419a4a6c7d716facdd32b01ffa181a1b4388d1148f89f",{"forcedShutdownMillis":80,"gracefulShutdownMillis":80,"startupCancellationMillis":80,"startupMillis":5000}],
	["sharedLazyResolverDeadlineRemainsTimedOutNotCallFailure","7896306ca311b2db0c592c58ec4917fa3e9b0fd1acd5c1bac88bd08fa751c7c3",{"forcedShutdownMillis":80,"gracefulShutdownMillis":80,"startupCancellationMillis":80,"startupMillis":5000}],
	["externalShutdownWinsBeforeInducedStartupCallFailure","9c81f6021f17005b2f7fffa2edf6d3a6cd15a8da4cd866c8bd9eab63b15b23d1",{"forcedShutdownMillis":80,"gracefulShutdownMillis":80,"startupCancellationMillis":80,"startupMillis":5000}],
	["startupCallFailureWinsBeforeLaterExternalShutdown","44165ccbbeaa61006596d831498b61affe73c81267d7fdd3f49de36bfd283dd7",{"forcedShutdownMillis":80,"gracefulShutdownMillis":80,"startupCancellationMillis":80,"startupMillis":5000}],
	["startupCallFailureWinsBeforeLaterPeerTermination","14e9c1f63acf1b50ca7d95c8892a1cf4b35b314712494ec7f343d72672f56c05",{"forcedShutdownMillis":80,"gracefulShutdownMillis":80,"startupCancellationMillis":80,"startupMillis":5000}],
  ]),
  ...reviewedPhasePolicyFile("src/test/java/com/soklet/SokletDirectLifecycleTests.java", "76124c2ca77eb557ba3f4ef58f49cd2ce9af42620664e34d3c80159ee04edad8", [
    ["blockingFrameworkSetupIsBoundedByStartupAndShutdownBudgets","e15a71164f4d44935fdef10beb8d9a350688b4c17e6507931d50dbf9c149d9f8",{"forcedShutdownMillis":75,"gracefulShutdownMillis":75,"startupCancellationMillis":75,"startupMillis":75}],
    ["blockingTransportStartIsBoundedAndCannotPublishLateReadiness","97736864a900e952a6688b8cd001d76a3d096ba5450b0400bd240e5a5d5ee8dc",{"forcedShutdownMillis":75,"gracefulShutdownMillis":75,"startupCancellationMillis":75,"startupMillis":75}],
  ]),
  ...reviewedPhasePolicyFile("src/test/java/com/soklet/SokletDirectMcpLifecycleTests.java", "661f59caf4a7be9eb140274d6ce0de9417b0f6c8ec3ce3f0c66c351cd9701e60", [
    ["blockingSubscriptionPublisherTimeoutRetainsListenerUntilLateReturn","f7b1b636f70ac57dfb8283bccf38f08167a46ae4d72b025f0b874bc79746c4ed",{"forcedShutdownMillis":250,"gracefulShutdownMillis":150,"startupCancellationMillis":150,"startupMillis":1000}],
    ["externalShutdownCancelsBlockingPublisherWithSameTerminalIdentity","69323a67bb8c7e6760d6198e375152bbfcc7309a5e19bf3250ad76e553c5073d",{"forcedShutdownMillis":250,"gracefulShutdownMillis":150,"startupCancellationMillis":150,"startupMillis":10000}],
    ["synchronousMcpStartupCleanupFailureRemainsBoundedSecondaryEvidence","22572d372695789eabf01ce3626dbdc8df16fecba6c5ff0ea247c3e11b4aeb46",{"forcedShutdownMillis":250,"gracefulShutdownMillis":150,"startupCancellationMillis":150,"startupMillis":10000}],
    ["lateMcpStartupFailuresCannotMutateFrozenEventLoopPrimary","9696a165e645d879eaa630a93e01773a9f253e0107c940f379b43cf6967d07a5",{"forcedShutdownMillis":250,"gracefulShutdownMillis":150,"startupCancellationMillis":150,"startupMillis":10000}],
    ["admittedMcpHandlerSelfStopPublishesIntentAndFailsFastWithoutSelfJoin","dafdde7275f634deed4f5eb0c63234791a9ce5b91e2573dff07262468a79c604",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":5000,"startupCancellationMillis":250,"startupMillis":5000}],
  ]),
  ...reviewedPhasePolicyFile("src/test/java/com/soklet/internal/mcp/protocol/McpStreamSubscriptionDiagnosticsPublicRuntimeTests.java", "785c0de2b7f4a38ba96c64d060d93987261fe6731f81a12595892b6f3ba77954", [
    ["ordinaryAndSubscriptionStreamsAggregateAcrossEndpointsAndCleanOnDisconnect","3cf18bb52b65bc2da74008ea443a87ab83cf3fb4d98935bc3a55b3ca2988984e",{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000}],
    ["residualHandlerStopPublishesZeroStreamsBeforeLateHandlerExit","c04886102b3910e2831085d41df0ad0fed4fe96bfde94a7765bd5c98b1fc641f",{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":150,"forcedShutdownMillis":150}],
    ["unexpectedFailureRetainsOneSubscriptionUntilCleanupWithConcurrentInvariantReads","2e8bf6d3ea6b095b112c6f6183a27f72f17837ecdf431c1c344115ba977085cc",{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000}],
  ]),
  ...reviewedPhasePolicyFile("src/test/java/com/soklet/internal/mcp/protocol/McpSubscriptionPublicRuntimeTests.java", "452e7b37e1cefaf2c3eaa4856fc3533ffef919e2febb73a3b08f08cbf4d27567", [
    ["acknowledgmentIsFirstAndPreservesExactStringAndIntegerIds","b5962a6919e4491d768afa87648431e23774aec0d4254a744ba28eaf18dba14f",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["publisherEmitsOnlyRequestedResourceEventsForMatchingUris","256689db4ae9f6356f63d2bbf9da2b74c5e8c1a7de24d71fa77ac293fbc18f93",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["supportedIntersectionOmitsToolsPromptsAndUnconfiguredResources","0e7cf6d2b89746f8cac35d6b4de3c91f4ad89cdd6199fb59cb5a056d589ed789",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["malformedRecognizedFilterFieldsFailBeforeAdmission","17454b140a1d2b7494cdfbbe0b34871162bc34ab0678d3c79f81c70d6ce1cd31",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["validListenUsesAdmissionAndRequestLimiterOnly","67c47e3a7e886bdb3510213238212bd95c1332361587f9dda29bdafe49c47317",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["rejectedAdmissionNeverActivatesARegisteredSubscription","d3c8f25689d2cdd3b8b91070fe3f52a4056efb8358dc896dff3c1411605fa55b",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["nullAndThrowingAdmissionNeverActivateOrConsumeSubscriptionQuota","055f629808451d57a1e33dccc412a45aceff06f46e9dfa04234c094932a8901a",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["liveSubscriptionDoesNotConsumeTheConfiguredHandlerSlot","b5d6e75ec33c6d7094c65761365b3b5c5ed5d35a9c0effd3ecc3f50d57366e48",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["configuredPerPrincipalCapRejectsWithoutDisturbingAndRecovers","da8c8ea455bda0c1fb4aa0f1675d91b100d3596dd0a1b6a354b54d98fbd29d83",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["sameIdSubscriptionsAreIsolatedAcrossAdmissionPartitionsAndCapRelease","b26f93d12ea354bfd94306ecc2dfa78d45e135a6628ba0552853743cbd74cdf6",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["publisherIdentityIsGroupedPerServerAndSharedAcrossServers","88d5a74bd6fe207fa20b977750c0d235fbc520284ace2560a8b39730e523e42c",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["gracefulHttpShutdownEndsWithOnlyTheTerminalCompleteResult","4ba385f13faff066efff356641c7489ffbb73a7f9279299f9598a30076d8488e",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["configuredMaximumDurationPublishesExactLifecycleAndMetrics","88a7e4e7bb0b226e1e9d43e9330ee0cb4407699aefe9c8047b0b3bd42de197cd",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["clientDisconnectReleasesStateAndPublishesExactlyOnce","a877299c48aea2c58bb5dd9f4bbf7bf5615284af2c13d9b6b79d4b89bbbf1b8e",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["keepAliveAcceptanceSharesStreamTransitionWithCloseObservation","5189b6b605fba20a67beae786206baadd8e33f34a3c00965047c2285ddb9867b",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["publisherVisibilityBeginsAfterAcknowledgmentActivation","e5d88c4b9f0464346291492c49877c3d3ca5741249414b5627250fa6abd05688",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["configuredQueueContainsBackpressureAndReleasesTheFullCap","2ea2df13b833083db513daabee661d25b2948278def7820a7240c236ecfdee06",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
  ]),
], 'lifecycle phase policy');

const REVIEWED_CONTROL_OVERRIDES = checkedReviewMap([
  ...reviewedScopeFile("src/test/java/com/soklet/BuiltInTransportLifecycleAdapterTests.java", "f81b390c74c61c169704935138c4c221615a4f5b22cd6bd0135eed81d2dd17b7", [
    ["failureRacingNormalShutdownIsRetainedWithoutReclassifyingRequestedProof","33329c14dce3cdd7404732193fb8fb8ce778777606737a8e4c8564b45903f65f",{"controlJoinMillis":3000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
    ["requestedProofThenLateFailureBeforeFreezePreservesBothInSequence","de82e80efa8d95c3cc59f3db04b5fe859eee32b7d8fefd50a832e4c74c3bbddd",{"controlJoinMillis":3000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["exactGenerationOperationsRejectForeignTokensWithoutMutation","ff1b277adb13768eb39a9e28b2a43a292e48b75af80cf0ca8439c542f6a8535c",{"controlJoinMillis":0,"controlComposition":"REVIEWED_NONBLOCKING_PRECONDITION"}],
    ["launchedThenThrowingCoordinatorCannotRepublishOrReleaseEvidence","6119ada3b7b6a7875f664478b8156d0425b5adbe85aef06759395d5427b95135",{"controlJoinMillis":2000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/DefaultHttpServerTests.java", "48e7a9c3b120e1d240902151054b85552ecf2f9c4fc27a74da16ceaba4c5df73", [
    ["httpServerCleansUpAfterUnexpectedEventLoopTermination","2b2c4db9ffe7412bac5d2bb617a9e80d997ef4d4ab3819b4133cad67311c7022",{"controlJoinMillis":4000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["stopCannotPublishAnEmptyGenerationWhileStartInstallsHttpResources","daa38ff9c7f3f1effcc52664813c709b9eb9b4ad38458546dff9dd23de556bd0",{"controlJoinMillis":7100,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
    ["startRejectsRunningHttpGenerationWhileItsStopIsInProgress","e0d2d6151eccfc4364133acb25d801a988063e1fc86f3230065a13aae5c6e309",{"controlJoinMillis":6000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/ExternallyCoordinatedTransportLifecycleAdapterTests.java", "db8038d680de6df9b72e366b4f6da188d8e0264fd68918c578650e95b61f387c", [
    ["externalSelfStopPublishesIntentBeforeOwnerScopedWaitFailsFast","fad281bccc07111fcf7920cd61d49ee36f0140b70dbaf4b360199674951bbdd7",{"controlJoinMillis":0,"controlComposition":"REVIEWED_NONBLOCKING_PRECONDITION"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/HttpServerLifecycleTests.java", "e3ddc79bcf8b7bc493fb53d753dc9b975eca1e025e6212ebde11b69dd892465a", [
    ["ownerLifecycleAttachesServesAndPublishesOneGracefulResult","bdc79f45c02504b7a9e480a89bc117cf22955dd98679b94b89b676619bc3c86b",{"controlJoinMillis":0,"controlComposition":"REVIEWED_NONBLOCKING_PRECONDITION"}],
    ["ownerShutdownDrainsInFlightResponseBeforeClosingConnection","c0dfba7292562aa6e42757c9286e5bb19a56abfdd51dfe893c5b36f7438fb47b",{"controlJoinMillis":9000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpAuthorizationIntegrationTests.java", "26ab192b1854453acfcfe74baea0b55de7f522a9c697900bf10a2216f0b4d603", [
    ["passesSafeBearerChallenge","4f92e4ce7a1cf1c5494a22b5dc5cea5417ab556d224ff609bf3a4b55cd4eee59",{"controlJoinMillis":5000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["corsResponseHeadsMatchIndependentGoldens","770eb979be12f832a69bfd64304e40458414c22a6d95fe929d6d924a05611377",{"controlJoinMillis":5000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpHandlerInterceptionPublicRuntimeTests.java", "dc9c01dd4b24d275d0b2f146214438b49d1411d7edf204533fa41fd6b7d8755d", [
    ["deadlinePreventsLatePublicHandlerEntry","1b4b24704cdb19fefe9e8b4931297609b31ebfea42a9f11e887223e4c71821ef",{"controlJoinMillis":5000,"controlComposition":"REVIEWED_OVERLAP_OR_DUPLICATE"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpHttpContractGoldenProductionTests.java", "fe5a87187c2a53ff7b8d37245edf81cd6983c8ef1ad8bfa1723e39129de02211", [
    ["requestPipelineFirstFailureWinnersMatchCompleteWireGoldens","13579007ec294fc3c5bc1b62413e5e41a2a23500182e52f1521d1a68e721e614",{"controlJoinMillis":10000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["notificationPipelineAndPreflightMatchCompleteWireGoldens","5a18db5c72569f0e33c1db6cb382d6493f38d0c226699173846fc3348c98ff34",{"controlJoinMillis":10000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["overloadAndSseAuthoritiesMatchCompleteWireGoldens","d3180f2f72a8418651565cb75abfefddabdbb3813cda333a9d9c9813dd68ee6e",{"controlJoinMillis":35000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpLifecycleB3Tests.java", "865ac2adca9b655732d92525be4b47531682754a4277f9f37e58236190843c5f", [
    ["exactMcpGenerationOperationsRejectForeignTokensWithoutMutation","67aedfa580c9dd8c6514285b0dd58076b13327801868c12f4c88cd974052d148",{"controlJoinMillis":0,"controlComposition":"REVIEWED_NONBLOCKING_PRECONDITION"}],
    ["forceResponsiveHandlerIsInterruptedOnlyAfterTheGraceDeadline","a4091a81d84f00ab3f37743a354fccfd4e81f4250cd5eebae3b15adb91e7e7da",{"controlJoinMillis":10000,"controlComposition":"REVIEWED_LIFECYCLE_CORE_DEDUPLICATION"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpProtocolProfileMetricsTests.java", "c34b631ac49ee97d28bf4fb4d4055f0ab4b9f164d39065c9a4f6c6d97b56878f", [
    ["unsupportedMissingMetadataRecordsUnsupportedVersionNotInvalidParams","283ea296a30e9d69555b09f38e15d602d1c176a0972733d3a79981c93d76daf0",{"controlJoinMillis":5000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/MetricsCollectorTests.java", "51fbebad6fc7c9dc8e32d3a22e16908e7bbafa499f7981df46b25a82b6f7d0da", [
    ["httpMetricsSnapshot_overNetwork","4cc77aea3d044fd9130367bcd75065f860204a5306f7736a88febd183dfdbd51",{"controlJoinMillis":2000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["sseMetricsSnapshot_overNetwork","87d1120ea6c5e33318796af562a528bc04b562ccbdd822786af0c0b5f83f39a7",{"controlJoinMillis":5000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpRequestObservationPublicRuntimeTests.java", "513b71c1b4ea63fc5ac3bdc25d334e80bc075ac6cd98ece554c70b0d97d81630", [
    ["throwingObservationCallbacksKeepRawCarriersApplicationOwnedAndLogsRedacted","3d238400d6d9098f10867f1e98bdd3b4cb22a527afa40cc2a1017cdf6a73ed3f",{"controlJoinMillis":15000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpErrorMappingGoldenProductionTests.java", "f8453104a931b0451fe3997db2a07e8fa302bd8842a16cddd53326e56ebfa7d0", [
    ["ordinaryMappingFamiliesMatchProductionListenerGoldens","791203cea6affc534885196b9c249a4b774257e6663d98c7ba8aca8f2028bdfb",{"controlJoinMillis":10000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["overloadMappingMatchesProductionListenerGolden","64765106c45e1fd9ded5915bcfa994c5b79533b18787469e79d53ea832bde1b9",{"controlJoinMillis":35000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpHttpServerRequestScopedSseTests.java", "40f4f954a11b77b754d8d40a412cef13bebc0020fb740a4507a4260b5fcab454", [
    ["shutdown_closes_committed_stream_and_runtime_restarts_cleanly","1b56c480e7618c105d448e1c5f45e17d4967bac50ef9f70443d8ecdf442784b4",{"controlComposition":"REVIEWED_LIFECYCLE_CORE_DEDUPLICATION","controlJoinMillis":15000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpSubscriptionRuntimeBoundaryTests.java", "5d586d7c481da85cea944db74118c9b367579b2e7ed9ce9a442b6b3556d54696", [
    ["commonLifecycleAwaitRescansRegistrationAfterCloseAttemptCompletes","1b6c55192d0294ab2be799dc20bc6bada8002817bd219aa572ed0865dfde9c9a",{"controlledLifecycleCoreMillis":5000,"controlJoinMillis":15000,"controlComposition":"REVIEWED_LIFECYCLE_CORE_DEDUPLICATION"}],
    ["commonLifecycleForceRetriesAQuiesceRegistrationCloseFailure","ed74ab5cbf8185b3f1e3a30c80ead352af39ebb40dab22715b5489a8e9fffd61",{"controlJoinMillis":9000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["commonLifecycleForceInterruptsAnOwnedBlockingRegistrationClose","c6d71cd9a87bfd649cbcdfc18d9b0f2a1fc9c210a8822784af0435f77fdb3dff",{"controlJoinMillis":7000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["commonLifecycleForceCancelsTheExactRetryAttemptItCreates","a961e251865f9c945028240e564fb3aa899b4835b58ce5886c25410866553032",{"controlJoinMillis":9000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["commonLifecycleRejectsPostForceGracefulRegistrationRetry","534b3aba001d9f537a633a18d20a79eafccad32163e4268c16971a473b6c7048",{"controlJoinMillis":4000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["preReadinessFailurePublicationWaitsForLifecycleElectionLock","0e60adc809631bf6ff8b977d43e6057bea21e1763de4287218165dfab7c9ea4b",{"controlJoinMillis":20000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpSubscriptionPublicRuntimeTests.java", "452e7b37e1cefaf2c3eaa4856fc3533ffef919e2febb73a3b08f08cbf4d27567", [
    ["configuredPerPrincipalCapRejectsWithoutDisturbingAndRecovers","da8c8ea455bda0c1fb4aa0f1675d91b100d3596dd0a1b6a354b54d98fbd29d83",{"controlJoinMillis":5000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["sameIdSubscriptionsAreIsolatedAcrossAdmissionPartitionsAndCapRelease","b26f93d12ea354bfd94306ecc2dfa78d45e135a6628ba0552853743cbd74cdf6",{"controlJoinMillis":5000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["clientDisconnectReleasesStateAndPublishesExactlyOnce","a877299c48aea2c58bb5dd9f4bbf7bf5615284af2c13d9b6b79d4b89bbbf1b8e",{"controlJoinMillis":5000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["keepAliveAcceptanceSharesStreamTransitionWithCloseObservation","5189b6b605fba20a67beae786206baadd8e33f34a3c00965047c2285ddb9867b",{"controlJoinMillis":15000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["configuredQueueContainsBackpressureAndReleasesTheFullCap","2ea2df13b833083db513daabee661d25b2948278def7820a7240c236ecfdee06",{"controlJoinMillis":5000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/transport/McpTransportRuntimeSmokeTests.java", "0ca1a5e95ab839a148fb9d7454e644e9f30cb930e9469ff4c77b6a4f4e332021", [
    ["platform_live_post_uses_independent_listener_and_event_driven_sse_body","144828fecde665ed569b53b21026b6a70f370549ca7692b4b5c7eba00a3ab0d8",{"controlJoinMillis":6000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["virtual_live_post_uses_independent_listener_and_event_driven_sse_body","bb0e39cd0ec9c64ba846939340305a0c99e102f71bb1c35b85cc3fd732f76afb",{"controlJoinMillis":6000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/InternalLifecycleCoordinatorForceAttributionTests.java", "5198342251acd6f63d446135aae32cd34da413e7692cd6cebc34995e4403c3b1", [
    ["rejectedForceLaunchDoesNotMakeLateGracefulProofForced","c6dbe38079a98e852ef89d3f057320f2b0ec7858ba3bfda3d6137d009cd29b13",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":8000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/LifecycleFoundationTests.java", "5e1ae4bd3500f8a8c5e4b749fadbfd1d65a5db42f37c3d163987f634107d2db7", [
    ["blockedLifecycleCallDoesNotPreventAnotherParticipantPhaseSubmission","997f474afc6dcbf889e885a465b7434834bb25fca51238bd9fd15070c522f154",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":2000}],
    ["graceExpiryCancelsBlockedQuiesceBeforeSubmittingForce","600ac5c14550d27da74baa83f9b5500ee370b0fb06cc21a9a7381b1338d158d0",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":0}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpHandlerMetricsObservabilityTests.java", "32fc44abfca07b8f45f04f5288ba9f80e5ea68bf96d7ba77b14ec561e0cef113", [
    ["queuedDeadlineDequeuesWithoutExecutionAndRetainsActiveGauge","072447bdc3fac24aeb356e1707048174fa8010606d15ca4ef6dd5ac6e4986ce4",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":45000}],
    ["managedResidualShutdownDequeuesAndFreezesGaugeAcrossLateExit","c2dfc60d3bfdb910ade54147e914aaf63e1082fa1c7887a681beebf9a6b8a30d",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":35000}],
    ["unexpectedTerminationDefersQueueCallbackAndFreezesTerminalGauge","793f4e9e54a7af687d9f77f9d8b5916b83acfbff859e650e797458eec3eaf57e",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":55000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpHandlerQueueDiagnosticsPublicRuntimeTests.java", "74294585a9c72e23bca246e064cddaf24816e9accb514e1290d684a927f3ec59", [
    ["residualStopRetainsOneActiveAndDrainsQueueUntilLateExit","347e37f2f42a9cd5e9cfc93f0de8b2c4ec9e49cb4864fc5637219cd98ff129b1",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":25000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpLifecycleB3Tests.java", "865ac2adca9b655732d92525be4b47531682754a4277f9f37e58236190843c5f", [
    ["noncooperativeHandlerClassifiesResidualAndRetainsItsGraphAndAddress","30babb3ab8bba243f67b4508d4820ef3b35e9e2edf937b7be5404dbfdc9a91a5",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":20000}],
    ["unexpectedEventLoopFailureFencesBeforeProofAndRetainsAddress","f8bd3d7a2e245e1985a584b5881cb40b051e5fa94c366aebe179914ee592b4fe",{"controlComposition":"REVIEWED_LIFECYCLE_CORE_DEDUPLICATION","controlJoinMillis":10000}],
    ["oneShotOwnerCannotConsumeUnexpectedGenerationBeforeExactResultPublication","5a50cff66d4c6138d313719cdc4d85110815f2f56595d5725d163c0468bd3a8d",{"controlComposition":"REVIEWED_LIFECYCLE_CORE_DEDUPLICATION","controlJoinMillis":20000}],
    ["simultaneousStartupFailuresShareTheElectedEventLoopPrimary","e34e47e32a51433d84960f8b3b8b20cae50da390d689116dce635a5981232b81",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":20000}],
    ["synchronousStartupFailureWaitsForExactCauseElectionBeforeTermination","8c42ea9650267c24ac11b2dccf0bbc306566f6579734cd7db63bb8f4ca323371",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":25000}],
    ["eventLoopFailureBetweenRuntimeAndCommonReadinessPreservesExactCause","7c2e4e18ddfeab1520ccd374b3790b8fb034eacd3202a1f50ab910e32b6a0111",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":25000}],
    ["deterministicNoProofMapsToMcpUnknownAndRetainsEvidence","1c9651575802191c63038061c690b2fca6663a1d963b0ed43c176e8c8b50accc",{"controlComposition":"REVIEWED_LIFECYCLE_CORE_DEDUPLICATION","controlJoinMillis":0}],
    ["deterministicNoProofRetainsTheExactBoundEphemeralAddress","a3cb5ed692c272e51e90f15a3236c8827ba8eb96d0533a63bca01d30c3509dbc",{"controlComposition":"REVIEWED_LIFECYCLE_CORE_DEDUPLICATION","controlJoinMillis":0}],
    ["blockedMcpQuiesceIsCancelledBeforeForceAndProof","cbc8e3a2c144daa0afd5e25cc87250acef4828c95c93c9258d9834300b5a0352",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":0}],
    ["shutdownIntentFencesAdmissionBeforeDeferredMcpQuiesce","b74807b29d648ddefed28b14f77d8358383418606154654d106095aea03cddb0",{"controlComposition":"REVIEWED_LIFECYCLE_CORE_DEDUPLICATION","controlJoinMillis":0}],
    ["idleSubscriptionClosesPromptlyWithServerStoppedAndNoForce","4233874ac07bd50cab9a52a5c215c8b943ade0a0dbfcd28a1dc5378ed34400ea",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":30000}],
    ["ownerNormalizesRetainedUnexpectedGenerationOnceBeforeRejectingRestart","16782e87cd06c52a258951d2cc9fd71d3442fa6e079884911077a2232db711d4",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":30000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpLocalizationAdversarialTests.java", "23348216ab27eae3693080e3810b421a02eb1566ba93d909f4a80062ccda1d70", [
    ["rejectedAndIrrelevantWorkNeverInvokesTheProvider","314ac4b16ec574dc021d47c15bf20ba61dc3e5d9a7285af060451a08a6d20977",{"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","controlJoinMillis":50000}],
    ["aUniqueTagFloodRetainsNoStateOrMetricSeries","dc7f436f2cd65d7759d5e80bd1c7ca7d4bcdbb18285431169a2e5d34c1ce4625",{"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","controlJoinMillis":30000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpMetricsEventDeliveryPublicRuntimeTests.java", "3e8390857a9d311861bc20d58e23a226c20b38911b94b3a6136a6e73c0374957", [
    ["unexpectedTerminationOrdersNormalizedStopBeforeFreshOwnerStart","460442bc95883393fb9db323e764cceb7883f2feb2df70cff406eb08c54c275a",{"controlComposition":"REVIEWED_LIFECYCLE_CORE_DEDUPLICATION","controlJoinMillis":2000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpShutdownObservabilityTests.java", "722a89ebc0977a63f10a16192565a8d473e163dc8e8438148b4f077a812a3b94", [
    ["unexpectedListenerTerminationAndFreshOwnerHaveExactParity","eed46a197399f7bea5aecf968bd85d4dc51f2d1154c394d0887d8f75585fac67",{"controlComposition":"REVIEWED_LIFECYCLE_CORE_DEDUPLICATION","controlJoinMillis":17000}],
    ["ownerNormalizesUnexpectedGenerationExactlyOnceAfterAdapterWait","7978de5a4927df4328c63e3aefc30da8c94ad897d83b760f347c10567df032f6",{"controlComposition":"REVIEWED_LIFECYCLE_CORE_DEDUPLICATION","controlJoinMillis":12000}],
    ["rejectedUnexpectedRestartDoesNotDuplicateBeforeFreshOwner","5b9ff535950effaf2c76122ba0bde4d8e343cd33e472b345f1bb5297760146cd",{"controlComposition":"REVIEWED_LIFECYCLE_CORE_DEDUPLICATION","controlJoinMillis":17000}],
    ["residualStopAndLaterExitDoNotDuplicateLifecycleOrMetricsOutcome","5cda4bda74e4d20403596e1b76297363c65418098a529092fbd611a7f24224ad",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":30000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpSimulatorPublicRuntimeTests.java", "3ba70fc29d36d31926fb7dab61ac930dbdf715b8f037a3ab54f8058e737d1c27", [
    ["noncooperativeSimulationCleanupIsBoundedAndPreservesSuppression","3d724ed986b2c966d6b3a2a08521fd180618a0ddd34571c030d18b84e018e6a2",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":10000}],
    ["nonDrainingCaptureLimitDoesNotBlockUnrelatedSimulationOrCreateTransportFailure","991cde188dc9df44f30ded80265ecbc620604b10de5f0a259ae58bbe29333403",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":35000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SokletApplicationObservationTests.java", "aa2a40fc7c06659eb619c43426cd5520510bbf0415330f9993d24d06636144aa", [
    ["transportLogDuringAttachIsInlineNonqueuedAndTracked","ffcad197704a26871607ea10bbc70ca4545809e637918ce6e427bd4b955065a6",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":30000}],
    ["blockedTransitionCannotDelayRunnerCleanupOrTerminalReport","ac7012c4f68e15f23d269bd459e832246c048abe122d59c7efe17b972214d93c",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":40000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SokletApplicationProcessTests.java", "9beddfae3cc337ffd3eed91fad8a54865d3715ec65d528bd5573c0eba8061463", [
    ["concurrentHookEnterInterruptionAndExplicitShutdownShareOneAttempt","ae9f9992e49f31d58c4476ca3f3eb8287a4124e5674c8fa0b06f324202a77827",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":0}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SokletDirectLifecycleRaceTests.java", "53d6b9c908a173496be5098900fb000e40d85d54fe170919fd4b23b5c4ed386c", [
    ["lateBlockedAttachReturnIsInertAndCannotEscapeTerminalEvidence","8b53b50a6a76b4862be3c151aa2778e90a62ce02f8762da37fc909270dce65fc",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":4000}],
    ["installedAttachmentWithActiveWrapperRetainsTransportResidualEvidence","524700bdf9511b6e67b86795791c15a811303a9548adddcd9d35ad89b05a3d4d",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":5000}],
    ["resolverCancellationSentinelDoesNotBecomeStartupOrResultFailure","eaa8a0b45f82f499b126558624008ab5826e0b426e4481294a416dac65e4c7d0",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":8000}],
	["shutdownAfterReadyLinearizationCannotRetroactivelyCancelStartup","d13d4d3aa30f2beacf90124c91a79bdbf949a966637839d5e4c12b11301d1968",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":2000}],
	["sharedLazyResolverDeadlineRemainsTimedOutNotCallFailure","7896306ca311b2db0c592c58ec4917fa3e9b0fd1acd5c1bac88bd08fa751c7c3",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":15000}],
	["externalShutdownWinsBeforeInducedStartupCallFailure","9c81f6021f17005b2f7fffa2edf6d3a6cd15a8da4cd866c8bd9eab63b15b23d1",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":20000}],
	["startupCallFailureWinsBeforeLaterExternalShutdown","44165ccbbeaa61006596d831498b61affe73c81267d7fdd3f49de36bfd283dd7",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":15000}],
	["startupCallFailureWinsBeforeLaterPeerTermination","14e9c1f63acf1b50ca7d95c8892a1cf4b35b314712494ec7f343d72672f56c05",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":15000}],
    ["earlierParticipantFailureBoundsBlockedLaterStartAndKeepsExactCause","8f5688eb9273a113e1234ec00647eea3ec5477a5cf202f287b24e022f9c9d094",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":4000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SokletDirectLifecycleTests.java", "76124c2ca77eb557ba3f4ef58f49cd2ce9af42620664e34d3c80159ee04edad8", [
    ["blockingFrameworkSetupIsBoundedByStartupAndShutdownBudgets","e15a71164f4d44935fdef10beb8d9a350688b4c17e6507931d50dbf9c149d9f8",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":5000}],
    ["blockingTransportStartIsBoundedAndCannotPublishLateReadiness","97736864a900e952a6688b8cd001d76a3d096ba5450b0400bd240e5a5d5ee8dc",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":5000}],
    ["admissionRemainsClosedUntilEveryConfiguredTransportHasStarted","f4c2ca449a31d3c74a936dd081db94708c0fcc942dcf0afdd770eda65903105c",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":4000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SokletDirectMcpLifecycleTests.java", "661f59caf4a7be9eb140274d6ce0de9417b0f6c8ec3ce3f0c66c351cd9701e60", [
    ["synchronousMcpStartupCleanupFailureRemainsBoundedSecondaryEvidence","22572d372695789eabf01ce3626dbdc8df16fecba6c5ff0ea247c3e11b4aeb46",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":0}],
    ["admittedMcpHandlerSelfStopPublishesIntentAndFailsFastWithoutSelfJoin","dafdde7275f634deed4f5eb0c63234791a9ce5b91e2573dff07262468a79c604",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":27000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SokletDirectStartClaimTruthTableTests.java", "2e86cbdb0bf350335d39dfebc589cff25b2684a1ce74532d22c6a1ab03758bae", [
    ["startRacingNewOriginShutdownWaitsForExactNotAttemptedResult","789870435f667be6794e3fdb2fe1cc6499c5e15f6c3c75212c29dfca7dab371c",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":6000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SokletDirectTerminalPublicationTests.java", "4e5de0de02e740918a650efb94d8e4fadf9a503f48cf805f4fcb364cbc8774ee", [
    ["blockedPreRegisteredContinuationCannotStrandPrivateOrPeerOwners","07868a752d00075bf0cf1e994a19e2540402b3ffc87dc6050ee8a5bb1d48de19",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":2000}],
    ["concurrentAndPostClosedShutdownCallsShareOneStageAndResult","e8276afc6b3e60b38ea2056d4a07066d94af2eadb4baace5c97711812deab8f7",{"controlComposition":"REVIEWED_CONCURRENT_MAX","controlJoinMillis":10000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SokletDirectWaitSemanticsTests.java", "577fda39aefa03a5cb2dd44519ec75a94ce1a1ed209c70bddd9b3d55934ede5e", [
    ["concurrentCloseCallsJoinOnceAndRestoreEntryInterrupt","9ff6d7a16fa1b0d95b8f3552f038fa68a4f83d6ea485edb49f0e7efc0895e36a",{"controlComposition":"REVIEWED_OVERLAP_OR_DUPLICATE","controlJoinMillis":2000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SokletProtectedLifecycleCompatibilityTests.java", "193c635104cc417f0ece565d20e560bd4a3d802180f8d219c0a46dad69924bff", [
    ["holdingProtectedLockProjectionCannotBlockShutdown","6e970b21afe596b0b7934c2d552f805929e5ab7286163baae728116b2e279b67",{"controlComposition":"REVIEWED_OVERLAP_OR_DUPLICATE","controlJoinMillis":8000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SokletDirectTerminationPrecedenceTests.java", "aa6035bb768d1645be55a8f627bad1bfcde5a47c73e8f3bb8e28842b01fb9630", [
    ["ownerShutdownIntentWinsFormerGroupFanoutGap","d6949788a822a3a5e1b0fd22bf59e2e74cc3ae1e43842ea9b174512e0a4d9b33",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":8000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SokletMcpLifecycleTests.java", "35c5081dc648372c2afc08b60f845a3f116b67ea864461d8fd25737481ae2fd0", [
    ["noncooperativeMcpHandlerFreezesOneResidualOutcomeAcrossLaterCalls","d80b8977656b48b7cff5f998557507d7a3c70fd76017d426acf218f11bec5be9",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":20000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SokletSimulatorIsolationTests.java", "c06b49797a68adabd3cf359e9757763e123a2897a9c1f7c3d588bd94e6fbda8b", [
    ["blockedFrameworkSetupUsesOneExactStartupAndRollbackSchedule","eac5c53752d9133307d28085084de635085184b942b11a115da30e90c4430a5e",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":15000}],
    ["liveMcpStartQuiescesBeforeCancellationAndCatchesUpToForce","b379a0ed0127b8ff4cb1bfe4a80fa70c3f77ed042f11989cf99f09556fead710",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":15000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SseTests.java", "ea826fcf2a8a7c9858dd07054b58bf7cfdb1f915264a9be3518ae3bee6bd11ab", [
    ["sse_handshakeHeaders_and_basicDelivery","489c50045dd147240b4a7347f613b2ad94bdb86a308de1a77b500eb8907902ac",{"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","controlJoinMillis":4000}],
    ["sse_largeEvent_isFullyWritten","a0a649122b75cdc51b945949d32a5b4e31cd31173ef9081eb706e9ecd0db016c",{"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","controlJoinMillis":4000}],
    ["sseRequestReadTimeoutWithoutRequestProgressClosesQuietly","8ac64becf79acc14b5bf7b0eccd8eddb9ef885a35036cb00266b3d568ca1412f",{"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","controlJoinMillis":7000}],
    ["ssePartialRequestReadTimeoutRecordsTransportFailure","6c78df4a8240d786b7b57bdaeb093bea03401a69cedc6843d749a9c04bcb11cd",{"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","controlJoinMillis":7000}],
    ["sse_stop_allowsIsStartedDuringShutdownWait","176eb363b3c17feade38f0fc28d1bf5a68f45afe747e88e4566660c923bf5920",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":6200}],
    ["sse_broadcastMany_underBackpressure_eitherDeliversOrCloses","e9cd6583a330d0de1b52e9041abd92dada8c4800ee26beb9a24741d8a9d527c0",{"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","controlJoinMillis":22000}],
    ["sse_backpressure_setsTerminationReason","8ee38236534cca283a5e27441a514eec1944c6e9f3da7ca0837881f1f53a3cb9",{"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","controlJoinMillis":4000}],
    ["sse_stopClosesConnection","14abf3bef233d701bf502976abcc787a16df7cb2b65d20a02f1f138a1d87de79",{"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","controlJoinMillis":16000}],
    ["sseStopDrainsQueuedEventsBeforeClosingConnection","237fa22f485ecdf5887ce86002b8d7a9c162b7a753f6a5edab1e4bea77946f8e",{"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","controlJoinMillis":24000}],
    ["sse_stop_setsTerminationReason_serverStop","ca9fd6ce8336878273cfab3f37effe222c749bda4a78bcd50e451fb8917cfbf9",{"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","controlJoinMillis":4000}],
    ["handshake_unknown_path_returns_404_and_closes","635e21a236dda068a21f457921880d1c52d0f2ec5b36c23b3f81a3bc986c0dd9",{"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","controlJoinMillis":9000}],
    ["handshake_rejects_transfer_encoding","8112464bae0ce7284db00176ec3df4761201b8b899ca636fdce4390c187cb6c1",{"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","controlJoinMillis":9000}],
    ["handshake_rejects_nonzero_content_length","8d9af9ef8fae874c723965ef0b8e86f6ebdbccc045145513d44fa0ef864f09f4",{"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","controlJoinMillis":9000}],
    ["handshake_rejects_missing_host","5b3a03f01e4332f987bbe29fdf990f77f2cae206530993e40fdfdf31a3f8ead9",{"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","controlJoinMillis":9000}],
    ["handshake_rejects_invalid_host","ce2e69f71064261f8b4ec632fba54a7a6a5ce10fa459b4fea66425fbec526c2e",{"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","controlJoinMillis":9000}],
    ["handshake_rejects_expect_header","38b33772ce234aa7508c5fcb7b69051e4f633053795ea3c6a4714203d7b4d719",{"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","controlJoinMillis":9000}],
    ["handshake_rejects_control_char_header_value","2144d3f1c18e96497ef081315f58fdc9c7f66647584f0c8745bd8e56af80a5b8",{"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","controlJoinMillis":9000}],
    ["handshake_times_out_returns_503_and_closes","65ea6624110291a50adea8b4838f87125df7c97e72f9317a57191c238ea77201",{"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","controlJoinMillis":9000}],
    ["handshake_read_times_out_returns_408_and_closes","c74dbc314b3679d21fcae9e170c2265ac55c70d4cfdb8c79efe29d9bd57cfe69",{"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","controlJoinMillis":9000}],
    ["stopCannotPublishAnEmptyGenerationWhileStartInstallsSseResources","bc651018d3b7b036b1776e671b0189238822f82445718efeb3fb919c63c7b2aa",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":7100}],
    ["startRejectsRunningSseGenerationWhileItsStopIsInProgress","9303908c0e9f97fd45adc11e233d34ed035fef47e18574fd752838d11cafbbd7",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":4000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpConditionalCapabilityProxyRuntimeTests.java", "cc2af61f8a11669bb3967b2428216d67cbc6438ab0a7429a345888ae705c5695", [
    ["proxyIdleExpiryCancelsSilentHoldAndSupportedControlForwardsSse","08f3c9fb26fd9f77a5097211504e8e3bb082a29e26d6dd61390ea9a0fc247361",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":55000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpHttpServerApplicationExecutionTests.java", "fa0df41b3787facb9cd275e117b9ec8e6beb38db27a4e4d256f6a756f35bc76b", [
    ["queued_absolute_deadline_gets_the_exact_capacity_response_without_dispatch","a60be88bcf82aa2c70bfe9b13b4b7994d24bd97550e53091062bcf73ecd22841",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":25010}],
    ["request_deadline_is_captured_before_protocol_admission_work","c94e1d25bc4f3b473595502f21b84f2b44a405d47b38956cd7cd09cdeeba4912",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":25010}],
    ["protocol_deadline_comparison_survives_monotonic_clock_wraparound","bc596f7a96973b138128f56b5b2b9e029ba44f694b0f3121ba6170353e864bc6",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":15000}],
    ["deadline_during_cors_authorization_prevents_later_admission","9afe7ba0cbee588ca9de44c6d3c338948dfb140f33d1381effbc78940a532aef",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":25005}],
    ["protocol_processor_backlog_expires_and_releases_canceled_queue_capacity","6eef5bdd2ebb3c5fc23b754c8032eb0a5845f8d6ded365e0fef6d8fd40ef4d92",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":30010}],
    ["framework_discovery_deadline_releases_identified_exchange_accounting","217f520a17753eafc432c788c20d42a13d644bc35173499c0aaf6ea04c9f0399",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":25005}],
    ["active_client_disconnect_interrupts_but_retains_the_slot_until_handler_exit","8fc72c1dfce652bbb8b5a1d43d2bbd3fedfe477a9b0ece1e3d55afc9346407ae",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":25015}],
    ["lifecycle_grace_preserves_active_handler_then_force_interrupts_without_promoting_queued_work","b2298faf5919d0ddfb071e8a493c18b3b8d8fafa721f0a1188fba621d341ff90",{"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","controlJoinMillis":20005}],
    ["shutdown_reports_residual_application_work_and_blocks_restart_until_exit","0168dce25a9f381c15c6b919637388cf236960743a70f23ea37fe954c058c8af",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":15255}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpHttpServerObservationTerminalRaceTests.java", "b256e51f70629956adc454cc4b13455b6593f6bbb1bb1014d42fc8dafbb51d0c", [
    ["lifecycleLeaseOutlivesApplicationExchangeUntilBodyCompletion","837ed46fa8bf1ba634cc7da6160515d53e13bc8526be6f169963daea3f956191",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":15000}],
    ["protocol_completion_cannot_preempt_inline_stream_terminal_owner","218622f3798f2e1f0fcdb80decaa1095ef3565ccf25d33b0a9f3dc5208483116",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":15000}],
    ["written_sse_terminal_beats_concurrent_client_cancel_exactly_once","e7a78a9f1c35713913c7a0cf40e8a7426774d38ecf2eb3df644b7cb8016f49ae",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":55000}],
    ["precommit_mapped_error_beats_late_client_cancel_exactly_once","5ae4210562fc73a20cd22802f9540655a47aff7a4f1112f3c86ee209efe423d3",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":10000}],
    ["written_streamed_error_terminal_beats_concurrent_client_cancel_exactly_once","fa2d0a6e55bb5028f2c8bf15d121e6e1a503252d302939c72c3a1f1d7b3deaff",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":55000}],
    ["client_cancel_beats_unreserved_streamed_error_and_discards_its_metric","3ae3542e1c26de67e77f97293b73e1490fad92aeaa3adae1e979bb440603e838",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":20000}],
    ["application_encoding_fallback_reports_actual_internal_error","53649fc4bfba7a31b8ea69696084093ac3a01eea3c00316bbae3bfc1dcc82cf0",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":10000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpHttpServerPolicyPipelineTests.java", "fa8685487c25d1a953b0e538085279f35431a2dfd80619449b57edbb0622eedb", [
    ["policy_null_exception_reserved_code_and_unsafe_header_fail_closed","998e3a54b55f4ff58e1b75ac8d31c00e649a2d93dc73abaddc6e31ae4cad7893",{"controlComposition":"REVIEWED_DYNAMIC_NODE_MAX","controlJoinMillis":10000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpMultiRoundTripTerminationRaceTests.java", "ef6947d21a1b6bf57471bdd6c9ace0f5d36bdcf7d7acb3b7f076b4c76bfb5537", [
    ["blockedCustomProtectorOpenMakesShutdownResidualUntilProtocolWorkExits","4e2940aadf6b81531aaf2c9fb67f63df03035d28ebd927256a287f35d629bdf2",{"controlComposition":"REVIEWED_OVERLAP_OR_DUPLICATE","controlJoinMillis":10015}],
    ["blockedCustomProtectorOpenDiscardsLateResultAfterDeadlineOrDisconnect","9fe0428fbf8a051f8856858b80b43e838880af781f3cf347ae86a50a84792910",{"controlComposition":"REVIEWED_DYNAMIC_NODE_MAX","controlJoinMillis":20020}],
    ["blockedSealCannotPublishLateInputRequiredAndReleasesExactlyOnce","9fa9b58c00aca22633217bf700bf7e481d25fd151076c03d2621399daecb4529",{"controlComposition":"REVIEWED_DYNAMIC_NODE_MAX","controlJoinMillis":15020}],
    ["sameAuthenticatedStateCanBranchWhileOneFreshIdTerminates","e2658db8bc19db08af218c4aa7408c71c66d309caa4d4edfe61b30847f57274c",{"controlComposition":"REVIEWED_DYNAMIC_NODE_MAX","controlJoinMillis":30025}],
    ["conditionalCapabilityHoldTerminatesWithoutProgressOrLateResult","e2a6c3af0f11bbd8c32e537b0c7209aebfeb71932f14c3f78dcf445f1d2d9bd1",{"controlComposition":"REVIEWED_DYNAMIC_NODE_MAX","controlJoinMillis":15015}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpProgressPublicRuntimeTests.java", "b15a54287cefccd6394e53b2f459a07c98f440c0d4fe87efc8dd65deef384085", [
    ["progressEnqueueWinsBeforeMappedErrorTerminal","bc346e059df77c8ec558fb5ff75cc18dfeb0cf6f8a81fdbb59fba6cda0552913",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":35000}],
    ["mappedErrorTerminalWinsAfterProgressEligibility","ac973dea8b26a499defa252028530323c1dd22286b3bf3a2463ffb99bcf47207",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":35000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpSimulationLifecyclePhaseTests.java", "7c1a2f3018cc6acb3e3ce63ba174727da3df0cf86445598003bd73f3e58cd761", [
    ["bridge_quiesce_is_idempotent_fences_starts_and_releases_proof","ca57c0c2992be90c6dfe2991bd42530868776deea6b9c0f6646a4b5bc8c3b6a2",{"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","controlJoinMillis":5000}],
    ["graceful_simulation_drain_does_not_interrupt_admitted_handler","eb407f468ff6840a5a2ef38f804cc762d3382e5932fb52fdadea5f3c5102912a",{"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","controlJoinMillis":20000}],
    ["force_interrupts_admitted_handler_and_reaches_complete_barrier","1d6490f610fcb07fee99234e2a90ed50775f8d3e711f8a36236c6916ac2212df",{"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","controlJoinMillis":15000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpStreamSubscriptionDiagnosticsPublicRuntimeTests.java", "785c0de2b7f4a38ba96c64d060d93987261fe6731f81a12595892b6f3ba77954", [
    ["residualHandlerStopPublishesZeroStreamsBeforeLateHandlerExit","c04886102b3910e2831085d41df0ad0fed4fe96bfde94a7765bd5c98b1fc641f",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":20000}],
  ]),
], 'lifecycle control topology');

const REVIEWED_SCOPE_OVERRIDES = mergeReviewedScopeOverrideMaps(
  REVIEWED_SCOPE_TOPOLOGY_OVERRIDES,
  REVIEWED_PHASE_POLICY_OVERRIDES,
  REVIEWED_CONTROL_OVERRIDES);

const REVIEWED_ORPHAN_HELPERS = checkedReviewMap([
  ...reviewedOrphanFile("src/test/java/com/soklet/InternalTransportEndpointTestCompatibility.java", "b887023cbcacc24a911274c8fc362a3dae3658692ab128bcf48e9f4f54b46a7b", [
    ["attach",31,"60018070942f07cd836e14af1a20c5f916061338a750928537fa9fff40bda05c",{"path":"src/main/java/com/soklet/SokletDirectLifecycle.java","line":2418,"lineSha256":"788aa269a8bc65fb1f7f7460a22f1a124dbdda93b52e0fabc3c3a91472897be3","rationale":"The production direct lifecycle invokes the public HTTP endpoint attach contract."}],
    ["publicRuntime",45,"48f5a8717c72ff887006d78956e67062cc0e80cd18c16e3f43038a69582c1a81",{"path":"src/test/java/com/soklet/InternalTransportEndpointTestCompatibility.java","line":36,"lineSha256":"03c392d1a09359e546d74395faec5a2129994402a1e60e337059b35b43b63281","rationale":"The reviewed HTTP compatibility attach default invokes this adapter helper."}],
    ["attach",77,"704afa5337f5c8466e62ee61db9821072b9b4c158e00e17721f1bbfda80eeb0a",{"path":"src/main/java/com/soklet/SokletDirectLifecycle.java","line":2427,"lineSha256":"788aa269a8bc65fb1f7f7460a22f1a124dbdda93b52e0fabc3c3a91472897be3","rationale":"The production direct lifecycle invokes the public SSE endpoint attach contract."}],
    ["publicRuntime",91,"48f5a8717c72ff887006d78956e67062cc0e80cd18c16e3f43038a69582c1a81",{"path":"src/test/java/com/soklet/InternalTransportEndpointTestCompatibility.java","line":82,"lineSha256":"03c392d1a09359e546d74395faec5a2129994402a1e60e337059b35b43b63281","rationale":"The reviewed SSE compatibility attach default invokes this adapter helper."}],
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/McpLifecycleB3Tests.java", "865ac2adca9b655732d92525be4b47531682754a4277f9f37e58236190843c5f", [
    ["close",2715,"eb076ba581178078050f92c63303430e60bec9ec1a9ed8ec7c746d75f8453968",{"path":"src/test/java/com/soklet/McpLifecycleB3Tests.java","line":449,"lineSha256":"b153608a966dca6571b544635e46ccab9aca8126e95a5e6384e21db8425ca463","rationale":"A lifecycle test directly invokes the reviewed fixture close contract."}],
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/McpLocalizationFleetPublicRuntimeTests.java", "943a2862028aed129dd9244c0887498f531f9d65accf609f53fe8cf853b042bd", [
    ["start",405,"a54b1363a3dab8198bf40d09ee1934132622a39ba308ae3f7c7d7eb76c3b88f5",{"path":"src/test/java/com/soklet/McpLocalizationFleetPublicRuntimeTests.java","line":92,"lineSha256":"07b7acceb6a5e59384e6422898a20512037609331671db066348aae36a3e9479","rationale":"The lifecycle test directly invokes the reviewed two-node fleet start helper."}],
    ["close",444,"d936c7abbdd7481d460bf7045588d487c8b8351a19d9473faf10cafabb0c0e89",{"path":"src/test/java/com/soklet/McpLocalizationFleetPublicRuntimeTests.java","line":133,"lineSha256":"186351cad850a49204fd8ae41bd21e660277a6d5af1db6d4744e5f4004467b14","rationale":"The lifecycle test directly invokes the reviewed two-node fleet close helper."}],
    ["start",511,"4e9e880fcdcddc24379810ae107534c7360ef80b73c1011643098ae240c17db6",{"path":"src/test/java/com/soklet/McpLocalizationFleetPublicRuntimeTests.java","line":407,"lineSha256":"dff4a9b14f394a29901a412dce108efa98e67ab21e6150d23ad39072653b0802","rationale":"The reviewed two-node fleet start helper invokes each node start helper."}],
    ["stop",515,"1194e749f943c21d3ce81c218884aaaae258a5157a85ba9874b96d35a1f7d720",{"path":"src/test/java/com/soklet/McpLocalizationFleetPublicRuntimeTests.java","line":230,"lineSha256":"31223babeaccf4900f5d16c97ed0d3db4262a1be2bb763ecdf2f634968993f69","rationale":"The fleet lifecycle test invokes the reviewed node stop helper."}],
    ["close",652,"a9e477c06503aa8ca99ce466692d8fa8a71079cbb8b40d227a459f166e963e26",{"path":"src/test/java/com/soklet/McpLocalizationFleetPublicRuntimeTests.java","line":445,"lineSha256":"d998b1ab99968332971844e39c07c3958a461982d28d6849cf6d04a4df1c563c","rationale":"The reviewed two-node fleet close helper invokes each node close helper."}],
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/SokletApplicationObservationTests.java", "aa2a40fc7c06659eb619c43426cd5520510bbf0415330f9993d24d06636144aa", [
    ["start",1083,"8743bc5899534d2657576bdd66d59311499e6ba40769bb325879c3acfee3de6e",{"path":"src/main/java/com/soklet/SokletApplication.java","line":200,"lineSha256":"b2319df033edd17959e515597bc6bf4b346ffcdeb9c7c82ed90ffdd190add5bd","rationale":"The production application runner invokes the wrapped runtime start contract."}],
    ["shutdown",1088,"eef45a37c20721b055e31362bc503628c1bd6eea2a765b56fe50cfbae15a5c7f",{"path":"src/main/java/com/soklet/SokletApplication.java","line":355,"lineSha256":"0ca794b2fa40ccfdbd54f9835b183890f2e8122b30d4504f27987a6fbbffeb3a","rationale":"The production application runner invokes the wrapped runtime shutdown contract."}],
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/SokletDirectCompositionIsolationTests.java", "ae5d83b952c882ad2bbb328b3385571f17ef0cbee42b0d46e2ada6923abd3d05", [
    ["attach",450,"afec5e4c571f9428b7d669d37b13936456db71663b73399b18a8a54f48387173",{"path":"src/test/java/com/soklet/SokletDirectCompositionIsolationTests.java","line":51,"lineSha256":"59a5a5e95e2ba52cdbec6fb317cbd2020259569b690db93f89a7496fe515cfdb","rationale":"The direct composition test installs this lifecycle-owning decorator."}],
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/SokletDirectLateStartupIntegrationTests.java", "c282c05e7fda849dab4e37fbb816989be5e387f8130894cab2816ec0db83e730", [
    ["close",876,"a2c47642265a189cc7764e547ae97971625558d5bc9359803865d61787c65067",{"path":"src/test/java/com/soklet/SokletDirectLateStartupIntegrationTests.java","line":101,"lineSha256":"50edadd1377f4ad9436d7c5312200d6544b0137965aa8c5b049fc702b3170b05","rationale":"The lifecycle test's try-with-resources scope invokes the reviewed owner-harness close helper."}],
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/SokletDirectSseCompositionTests.java", "c0f6272c8bc5a81c557125a7af7de103f3adb60868821ada57b3c28389d8944e", [
    ["attach",676,"91fab5a94cf8d422d07cba41e148f8b132cbfdd5c97da5ab75148141fc907f89",{"path":"src/test/java/com/soklet/SokletDirectSseCompositionTests.java","line":88,"lineSha256":"3489e198d1cf9063ad182a92b4e5ac78e33f635e650cc7ab1484245db4190b2c","rationale":"The SSE composition test installs this lifecycle-owning decorator."}],
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/SokletDirectStartClaimTruthTableTests.java", "2e86cbdb0bf350335d39dfebc589cff25b2684a1ce74532d22c6a1ab03758bae", [
    ["close",189,"72d21aa1b74efb7d81f57e986ddc30f6fab8e5f8a955335e5f95d5e655500b3f",{"path":"src/test/java/com/soklet/SokletDirectStartClaimTruthTableTests.java","line":144,"lineSha256":"a652045ac401b11d233cba856e6cd5c586f26926053ff1ec249d0bb80c947f87","rationale":"The lifecycle test's try-with-resources scope invokes the reviewed truth-race cleanup helper."}],
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/SokletDirectTerminalPublicationTests.java", "4e5de0de02e740918a650efb94d8e4fadf9a503f48cf805f4fcb364cbc8774ee", [
    ["create",430,"7e0266a1ff39c91caeff040d7a3e5a7712aad3a6c58af387450cac8f5e69492d",{"path":"src/test/java/com/soklet/SokletDirectTerminalPublicationTests.java","line":94,"lineSha256":"8b0299d6130be80b0de8400d73d5ce7177bd1181ee4ff028f0322f5f214f53ab","rationale":"The lifecycle test directly constructs the reviewed owner harness."}],
    ["close",453,"df08d934601614ca68816224f6d17016463202755d3f8dbf6f741e0acc85f3a9",{"path":"src/test/java/com/soklet/SokletDirectTerminalPublicationTests.java","line":94,"lineSha256":"8b0299d6130be80b0de8400d73d5ce7177bd1181ee4ff028f0322f5f214f53ab","rationale":"The lifecycle test's try-with-resources scope invokes the reviewed owner-harness close helper."}],
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/SokletDirectTerminationPrecedenceTests.java", "aa6035bb768d1645be55a8f627bad1bfcde5a47c73e8f3bb8e28842b01fb9630", [
    ["close",504,"e406547b24c34bad6ed8587e1768d7d8a1dbb25d260fef67b4e4bf3027653d3d",{"path":"src/test/java/com/soklet/SokletDirectTerminationPrecedenceTests.java","line":247,"lineSha256":"05e81d8b560c9a1c5498861a13c717af06a07bba454d7305b66bcace0f6b18af","rationale":"The lifecycle test's try-with-resources scope invokes the reviewed precedence-harness close helper."}],
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/SokletDirectWaitSemanticsTests.java", "577fda39aefa03a5cb2dd44519ec75a94ce1a1ed209c70bddd9b3d55934ede5e", [
    ["close",335,"4657b5a39ea6a1e00289aec01e92fbb4937e924fd3e088df0f935fb7c47d96e4",{"path":"src/test/java/com/soklet/SokletDirectWaitSemanticsTests.java","line":85,"lineSha256":"366775be6d81f07243ae7d4809f3249ceed9b8ca6705fca8f77d9a0e1306c9ef","rationale":"The direct wait-semantics test constructs the reviewed AutoCloseable wait harness."}],
  ]),
], 'orphan lifecycle helper');

const LIFECYCLE_SIGNAL_PATTERN = /(?:\bSoklet(?:Application(?:Options)?|Config|Simulator)?\b|\b(?:Http|Sse|Mcp)Server\b|\bMcpHttpServerRuntime\b|\bTransportRuntime\b|\bInternalLifecycleCoordinator\b|\bSimulationSession\b|\bLifecyclePolicy\b|\b(?:startupCancellationTimeout|noStartupTimeout|gracefulShutdownDuration|forcedShutdownDuration)\s*\()/u;
const LIFECYCLE_EXECUTION_PATTERN = /(?:\bSokletSimulator\s*\.\s*run\s*\(|\bSoklet\s*\.\s*fromConfig\s*\(|\bSokletApplication\s*\.\s*run\s*\(|\.\s*(?:start|shutdown|awaitShutdown|awaitTermination)\s*\(|try\s*\(\s*Soklet\b|\bopenSimulationSession\s*\()/u;
const UNSCOPED_LIFECYCLE_EXECUTION_PATTERN = /(?:\bSoklet(?:Application|Simulator)\s*\.\s*run\s*\(|\bSoklet\s*\.\s*fromConfig\s*\(|\bnew\s+SokletDirectLifecycle\s*\(|\b(?:SokletConfig|var)\s+[A-Za-z_$][\w$]*\s*=\s*SokletConfig\b|\bopenSimulationSession\s*\(|\.\s*(?:start|beginStart|markReady|runExternallyCoordinatedStart|commitExternallyCoordinatedGeneration|shutdown|stop|requestStop|sealScope|awaitMcpScopeTermination|awaitShutdown|awaitStop|awaitTermination|whenTerminated)\s*\(|::\s*(?:start|openMcpScope|shutdown|stop)\b|try\s*\([^)]*\b(?:Soklet|[A-Za-z_$][\w$]*Harness)\b)/gu;
const JAVA_FIXED_WAIT_PATTERN = /(?:\.\s*(?:await|join|waitFor)\s*\(|\.\s*get\s*\([^\r\n]*(?:TimeUnit|SECONDS|MILLISECONDS|MINUTES)\b|\bdeadline\b)/iu;
const NAMED_TIMEOUT_PATTERN = /\b[A-Za-z_$][\w$]*(?:Timeout|TimeoutMillis|TimeoutMilliseconds)\b/u;
const JS_PROCESS_GUARD_PATTERN = /(?:\bconst\s+[A-Za-z_$][\w$]*TimeoutMilliseconds\s*=|\btimeout\s*:\s*[A-Za-z_$][\w$]*|\bwaitForClose\s*\()/u;

export class LifecycleBoundHarnessInventoryError extends Error {}

function fail(message) {
  throw new LifecycleBoundHarnessInventoryError(message);
}

function asciiCompare(left, right) {
  return Buffer.compare(Buffer.from(left, 'utf8'), Buffer.from(right, 'utf8'));
}

function sha256(value) {
  return createHash('sha256').update(value).digest('hex');
}

export function lineSha256(line) {
  return sha256(Buffer.from(line, 'utf8'));
}

function stableId(prefix, value) {
  return `${prefix}-${sha256(Buffer.from(value, 'utf8')).slice(0, 16)}`;
}

function splitLines(text) {
  return text.split(/\r\n|\n|\r/u);
}

function exactFields(value, fields, label) {
  if (value === null || typeof value !== 'object' || Array.isArray(value))
    fail(`${label} must be an object.`);
  const actual = Object.keys(value).sort(asciiCompare);
  const expected = [...fields].sort(asciiCompare);
  if (JSON.stringify(actual) !== JSON.stringify(expected))
    fail(`${label} fields must be exactly ${expected.join(', ')}; found ${actual.join(', ')}.`);
}

function compareJson(actual, expected, label) {
  if (JSON.stringify(actual) !== JSON.stringify(expected))
    fail(`${label} does not match the checked-in closure contract.`);
}

function runGit(root, args, { allowFailure = false } = {}) {
  const result = spawnSync('git', args, {
    cwd: root,
    encoding: null,
    maxBuffer: 128 * 1024 * 1024,
  });
  if (result.status !== 0 && !allowFailure) {
    fail(`git ${args.join(' ')} failed: ${(result.stderr ?? Buffer.alloc(0)).toString('utf8').trim()}`);
  }
  return result;
}

function decodeText(buffer) {
  if (buffer.includes(0)) return null;
  try {
    return UTF8_DECODER.decode(buffer);
  } catch {
    return null;
  }
}

function candidatePaths(root) {
  const output = runGit(root, [
    'ls-files', '-z', '--cached', '--others', '--exclude-standard',
  ]).stdout.toString('utf8');
  return output.split('\0').filter(Boolean).sort(asciiCompare);
}

function currentTexts(root) {
  const texts = new Map();
  for (const path of candidatePaths(root)) {
    const absolute = join(root, path);
    if (!existsSync(absolute) || !lstatSync(absolute).isFile()) continue;
    const text = decodeText(readFileSync(absolute));
    if (text !== null) texts.set(path, text);
  }
  return texts;
}

function parseGitGrep(output, commit = null) {
  if (output.length === 0) return [];
  const records = [];
  let offset = 0;
  while (offset < output.length) {
    const pathEnd = output.indexOf('\0', offset);
    const lineEnd = output.indexOf('\0', pathEnd + 1);
    const textEnd = output.indexOf('\n', lineEnd + 1);
    if (pathEnd < 0 || lineEnd < 0) fail('Malformed NUL-delimited git grep output.');
    const rawPath = output.slice(offset, pathEnd);
    const path = commit !== null && rawPath.startsWith(`${commit}:`)
      ? rawPath.slice(commit.length + 1) : rawPath;
    const line = Number.parseInt(output.slice(pathEnd + 1, lineEnd), 10);
    const sourceLine = output.slice(lineEnd + 1,
      textEnd < 0 ? output.length : textEnd);
    let occurrenceIndex = 0;
    for (const ignored of sourceLine.matchAll(
      new RegExp(LEGACY_PATTERN_SOURCE, 'gu'))) {
      records.push({ line, occurrenceIndex, path, sourceLine });
      occurrenceIndex += 1;
    }
    offset = textEnd < 0 ? output.length : textEnd + 1;
  }
  return records.sort((left, right) => asciiCompare(left.path, right.path)
    || left.line - right.line || left.occurrenceIndex - right.occurrenceIndex);
}

function acceptedBaselineOccurrences(root, commit) {
  const result = runGit(root, [
    'grep', '-n', '-I', '-z', '-E', LEGACY_GIT_PATTERN, commit,
  ], { allowFailure: true });
  if (![0, 1].includes(result.status))
    fail(`Unable to scan accepted-D1 shutdownTimeout occurrences: ${result.stderr.toString('utf8').trim()}`);
  return parseGitGrep(result.stdout.toString('utf8'), commit);
}

function currentLegacyOccurrences(texts) {
  const records = [];
  for (const [path, text] of texts) {
    if (EXCLUDED_DISCOVERY_PATHS.has(path)
        || GENERATED_D1P_EVIDENCE_PATHS.has(path))
      continue;
    splitLines(text).forEach((sourceLine, index) => {
      let occurrenceIndex = 0;
      for (const ignored of sourceLine.matchAll(
        new RegExp(LEGACY_PATTERN_SOURCE, 'gu'))) {
        records.push({ line: index + 1, occurrenceIndex, path, sourceLine });
        occurrenceIndex += 1;
      }
    });
  }
  return records.sort((left, right) => asciiCompare(left.path, right.path)
    || left.line - right.line || left.occurrenceIndex - right.occurrenceIndex);
}

export function verifyNoSurvivingLegacySites(texts) {
  const current = currentLegacyOccurrences(texts);
  for (const row of current) {
    if (!currentLegacyExclusionAllowed(row))
      fail(`Surviving non-excluded shutdownTimeout occurrence: ${row.path}:${row.line}.`);
  }
  return current.map(currentLegacyIdentity);
}

function baselineExclusionAllowed(record) {
  return record.path === 'api/mcp/phase-0-incompatibilities.jsonl'
    || record.path.startsWith('src/main/java/com/soklet/internal/mcp/protocol/')
    || (record.path.startsWith('src/test/java/com/soklet/internal/mcp/protocol/')
      && /(?:defaults|configuration)\.shutdownTimeout\s*\(/u.test(record.sourceLine))
    || (record.path === 'src/main/java/com/soklet/DefaultMcpServer.java'
      && /Duration shutdownTimeout\s*\(/u.test(record.sourceLine));
}

function currentLegacyExclusionAllowed(record) {
  return /api\/mcp\/(?:current|phase-0)-incompatibilities\.jsonl$/u
      .test(record.path)
    || record.path.startsWith('src/main/java/com/soklet/internal/mcp/protocol/')
    || (record.path.startsWith('src/test/java/com/soklet/internal/mcp/protocol/')
      && /(?:defaults|configuration)\.shutdownTimeout\s*\(/u.test(record.sourceLine));
}

function baselineIdentity(record) {
  return {
    id: stableId('BASELINE', `${record.path}:${record.line}:${record.occurrenceIndex}`),
    line: record.line,
    lineSha256: lineSha256(record.sourceLine),
    occurrenceIndex: record.occurrenceIndex,
    path: record.path,
  };
}

function currentLegacyIdentity(record) {
  return {
    id: stableId('CURRENT-LEGACY',
      `${record.path}:${record.line}:${record.occurrenceIndex}`),
    line: record.line,
    lineSha256: lineSha256(record.sourceLine),
    occurrenceIndex: record.occurrenceIndex,
    path: record.path,
  };
}

function discoveryKinds(path, line) {
  const kinds = [];
  const java = path.endsWith('.java');
  const javascript = /\.(?:cjs|js|mjs)$/u.test(path);
  if (LIFECYCLE_SIGNAL_PATTERN.test(line)) kinds.push('LIFECYCLE_SIGNAL');
  if (/@(?:org\.junit\.jupiter\.api\.)?Timeout\s*\(/u.test(line))
    kinds.push('JUNIT_OUTER_GUARD');
  if (java && JAVA_FIXED_WAIT_PATTERN.test(line))
    kinds.push('FIXED_WAIT_CANDIDATE');
  if (NAMED_TIMEOUT_PATTERN.test(line)) kinds.push('NAMED_TIMEOUT_CANDIDATE');
  if (javascript && JS_PROCESS_GUARD_PATTERN.test(line))
    kinds.push('PROCESS_OUTER_GUARD');
  if (/timeout-minutes\s*:/u.test(line)) kinds.push('WORKFLOW_OUTER_GUARD');
  return [...new Set(kinds)].sort(asciiCompare);
}

export function buildDiscoveryCensus(texts) {
  const candidates = [];
  for (const [path, text] of texts) {
    if (EXCLUDED_DISCOVERY_PATHS.has(path)
        || !CANDIDATE_PATH_PREFIXES.some((prefix) => path.startsWith(prefix)))
      continue;
    splitLines(text).forEach((lineText, index) => {
      const kinds = discoveryKinds(path, lineText);
      if (kinds.length === 0) return;
      candidates.push({
        kinds,
        line: index + 1,
        lineSha256: lineSha256(lineText),
        path,
      });
    });
  }
  candidates.sort((left, right) => asciiCompare(left.path, right.path)
    || left.line - right.line || asciiCompare(left.kinds.join(','), right.kinds.join(',')));
  const byKind = Object.fromEntries(DISCOVERY_KINDS.map((kind) => [kind, 0]));
  for (const candidate of candidates)
    for (const kind of candidate.kinds) byKind[kind] += 1;
  const paths = [...new Set(candidates.map((candidate) => candidate.path))]
    .sort(asciiCompare);
  const canonical = candidates.map((candidate) =>
    `${candidate.path}\0${candidate.line}\0${candidate.kinds.join(',')}\0${candidate.lineSha256}`)
    .join('\n');
  return {
    candidateCount: candidates.length,
    candidateSha256: sha256(Buffer.from(canonical, 'utf8')),
    candidates,
    countsByKind: byKind,
    pathCount: paths.length,
    paths,
  };
}

function parseDurationMillis(value, label) {
  const match = value.trim().match(/^(\d+)\s*(ns|us|ms|s|m|h|d)$/iu);
  if (!match) fail(`${label} must be an integer duration with an explicit unit.`);
  const amount = BigInt(match[1]);
  const nanos = {
    ns: 1n,
    us: 1_000n,
    ms: 1_000_000n,
    s: 1_000_000_000n,
    m: 60_000_000_000n,
    h: 3_600_000_000_000n,
    d: 86_400_000_000_000n,
  }[match[2].toLowerCase()];
  const totalNanos = amount * nanos;
  if (totalNanos % 1_000_000n !== 0n)
    fail(`${label} must resolve to whole milliseconds.`);
  const millis = totalNanos / 1_000_000n;
  if (millis > BigInt(Number.MAX_SAFE_INTEGER)) fail(`${label} is too large.`);
  return Number(millis);
}

function parseProperties(text, path) {
  const values = new Map();
  for (const [index, rawLine] of splitLines(text).entries()) {
    const line = rawLine.trim();
    if (line.length === 0 || line.startsWith('#') || line.startsWith('!')) continue;
    const match = rawLine.match(/^\s*([^:=\s]+)\s*[:=]\s*(.*?)\s*$/u);
    if (!match) fail(`Malformed property ${path}:${index + 1}.`);
    if (values.has(match[1])) fail(`Duplicate property ${match[1]} in ${path}.`);
    values.set(match[1], match[2]);
  }
  return values;
}

export function standardJunitGuard(texts) {
  const text = texts.get(STANDARD_JUNIT_GUARD_PATH);
  if (text === undefined) fail(`Missing standard JUnit guard ${STANDARD_JUNIT_GUARD_PATH}.`);
  if (text !== STANDARD_JUNIT_GUARD_TEXT)
    fail(`${STANDARD_JUNIT_GUARD_PATH} must contain exactly the approved 60-second JUnit default.`);
  const properties = parseProperties(text, STANDARD_JUNIT_GUARD_PATH);
  const key = 'junit.jupiter.execution.timeout.default';
  if (properties.size !== 1 || !properties.has(key))
    fail(`${STANDARD_JUNIT_GUARD_PATH} must contain only ${key}.`);
  const millis = parseDurationMillis(properties.get(key), key);
  if (millis !== STANDARD_JUNIT_GUARD_MILLIS)
    fail(`${key} must be exactly 60 seconds; found ${millis} ms.`);
  const excludedLiteralPaths = new Set([
    'scripts/verify-lifecycle-bound-harness-inventory-self-test.mjs',
    'scripts/verify-lifecycle-bound-harness-inventory.mjs',
  ]);
  const configurationHost = (path) => path === 'pom.xml'
    || path.startsWith('.mvn/')
    || path.startsWith('.github/workflows/')
    || path.startsWith('scripts/')
    || path === 'junit-platform.properties'
    || path.endsWith('/junit-platform.properties');
  const timeoutConfiguration =
    /junit\.jupiter\.execution\.timeout\.[A-Za-z0-9_.-]+/gu;
  for (const [path, candidate] of texts) {
    if (path === STANDARD_JUNIT_GUARD_PATH
        || excludedLiteralPaths.has(path) || !configurationHost(path))
      continue;
    const matches = [...candidate.matchAll(timeoutConfiguration)];
    if (matches.length > 0)
      fail(`Higher-precedence JUnit timeout configuration is forbidden: ${path} (${matches[0][0]}).`);
  }
  const line = splitLines(text).find((candidate) =>
    /^\s*junit\.jupiter\.execution\.timeout\.default\s*[:=]/u.test(candidate));
  return {
    lineSha256: lineSha256(line),
    millis,
    path: STANDARD_JUNIT_GUARD_PATH,
    property: key,
  };
}

function timeoutUnitMillis(unit, label) {
  const normalized = unit.replace(
    /^(?:[A-Za-z_$][\w$]*\.)*TimeUnit\./u, '');
  const factors = {
    NANOSECONDS: 1 / 1_000_000,
    MICROSECONDS: 1 / 1_000,
    MILLISECONDS: 1,
    SECONDS: 1_000,
    MINUTES: 60_000,
    HOURS: 3_600_000,
    DAYS: 86_400_000,
  };
  if (!(normalized in factors)) fail(`${label} has unsupported TimeUnit ${unit}.`);
  return factors[normalized];
}

function parseTimeoutArguments(argumentsText, label) {
  const valueMatch = argumentsText.match(/(?:^|\bvalue\s*=\s*)(\d+)/u);
  if (!valueMatch) fail(`${label} has a non-literal @Timeout value.`);
  const unitMatch = argumentsText.match(
    /\bunit\s*=\s*((?:(?:[A-Za-z_$][\w$]*\.)*TimeUnit\.)?[A-Z]+)\b/u);
  const unit = unitMatch?.[1] ?? 'SECONDS';
  const value = Number.parseInt(valueMatch[1], 10);
  const millis = value * timeoutUnitMillis(unit, label);
  if (!Number.isSafeInteger(millis))
    fail(`${label} is not whole safe milliseconds.`);
  return millis;
}

function parseJunitTimeouts(path, text) {
  const masked = maskJavaSource(text);
  const rows = [];
  for (const match of masked.matchAll(JUNIT_TIMEOUT_PATTERN)) {
    const argumentsText = match[1];
    const millis = parseTimeoutArguments(argumentsText, `${path} @Timeout`);
    const line = text.slice(0, match.index).split(/\r\n|\n|\r/u).length;
    const physicalLine = splitLines(text)[line - 1];
    const remainder = masked.slice(match.index + match[0].length);
    const declaration = remainder.match(/^[\s\S]{0,600}?\b(class|interface|enum|record|[A-Za-z_$][\w$]*\s*\()/u);
    if (declaration === null)
      fail(`${path}:${line} @Timeout is not attached to a recognizable type or method declaration.`);
    const scopeKind = ['class', 'interface', 'enum', 'record']
      .includes(declaration[1]) ? 'TYPE' : 'METHOD';
    rows.push({ line, lineSha256: lineSha256(physicalLine), millis, path, scopeKind });
  }
  return rows;
}

function maskJavaSource(text) {
  // split('') preserves UTF-16 code-unit indexes used by RegExp match.index.
  const masked = text.split('');
  let state = 'CODE';
  for (let index = 0; index < text.length; index += 1) {
    const character = text[index];
    const next = text[index + 1];
    if (state === 'CODE') {
      if (character === '/' && next === '/') {
        masked[index] = masked[index + 1] = ' ';
        index += 1;
        state = 'LINE_COMMENT';
      } else if (character === '/' && next === '*') {
        masked[index] = masked[index + 1] = ' ';
        index += 1;
        state = 'BLOCK_COMMENT';
      } else if (character === '"') {
        masked[index] = ' ';
        if (text.slice(index, index + 3) === '"""') {
          masked[index + 1] = masked[index + 2] = ' ';
          index += 2;
          state = 'TEXT_BLOCK';
        } else {
          state = 'STRING';
        }
      } else if (character === "'") {
        masked[index] = ' ';
        state = 'CHARACTER';
      }
    } else if (state === 'LINE_COMMENT') {
      if (character === '\n' || character === '\r') state = 'CODE';
      else masked[index] = ' ';
    } else if (state === 'BLOCK_COMMENT') {
      if (character === '*' && next === '/') {
        masked[index] = masked[index + 1] = ' ';
        index += 1;
        state = 'CODE';
      } else if (character !== '\n' && character !== '\r') {
        masked[index] = ' ';
      }
    } else if (state === 'TEXT_BLOCK') {
      if (text.slice(index, index + 3) === '"""') {
        masked[index] = masked[index + 1] = masked[index + 2] = ' ';
        index += 2;
        state = 'CODE';
      } else if (character !== '\n' && character !== '\r') {
        masked[index] = ' ';
      }
    } else if (character === '\\') {
      masked[index] = ' ';
      if (index + 1 < text.length && next !== '\n' && next !== '\r') {
        masked[index + 1] = ' ';
        index += 1;
      }
    } else if ((state === 'STRING' && character === '"')
        || (state === 'CHARACTER' && character === "'")) {
      masked[index] = ' ';
      state = 'CODE';
    } else if (character !== '\n' && character !== '\r') {
      masked[index] = ' ';
    }
  }
  return masked.join('');
}

function maskJavascriptSource(text) {
  // Preserve indexes and newlines so executable matches still bind raw bytes.
  const masked = text.split('');
  let state = 'CODE';
  for (let index = 0; index < text.length; index += 1) {
    const character = text[index];
    const next = text[index + 1];
    if (state === 'CODE') {
      if (character === '/' && next === '/') {
        masked[index] = masked[index + 1] = ' ';
        index += 1;
        state = 'LINE_COMMENT';
      } else if (character === '/' && next === '*') {
        masked[index] = masked[index + 1] = ' ';
        index += 1;
        state = 'BLOCK_COMMENT';
      } else if (character === '"' || character === "'"
          || character === '`') {
        masked[index] = ' ';
        state = character === '`' ? 'TEMPLATE' : character === '"'
          ? 'DOUBLE_STRING' : 'SINGLE_STRING';
      }
    } else if (state === 'LINE_COMMENT') {
      if (character === '\n' || character === '\r') state = 'CODE';
      else masked[index] = ' ';
    } else if (state === 'BLOCK_COMMENT') {
      if (character === '*' && next === '/') {
        masked[index] = masked[index + 1] = ' ';
        index += 1;
        state = 'CODE';
      } else if (character !== '\n' && character !== '\r') {
        masked[index] = ' ';
      }
    } else if (character === '\\') {
      masked[index] = ' ';
      if (index + 1 < text.length && next !== '\n' && next !== '\r') {
        masked[index + 1] = ' ';
        index += 1;
      }
    } else if ((state === 'DOUBLE_STRING' && character === '"')
        || (state === 'SINGLE_STRING' && character === "'")
        || (state === 'TEMPLATE' && character === '`')) {
      masked[index] = ' ';
      state = 'CODE';
    } else if (character !== '\n' && character !== '\r') {
      masked[index] = ' ';
    }
  }
  return masked.join('');
}

function maskSecondCallArguments(text, callPattern, masked) {
  for (const match of text.matchAll(callPattern)) {
    const openParenthesis = match.index + match[0].lastIndexOf('(');
    const end = matchingParenthesisEnd(text, openParenthesis);
    if (end === null) continue;
    let parenthesisDepth = 0;
    let bracketDepth = 0;
    let braceDepth = 0;
    let comma = -1;
    for (let index = openParenthesis + 1; index < end - 1; index += 1) {
      const character = text[index];
      if (character === '(') parenthesisDepth += 1;
      else if (character === ')') parenthesisDepth -= 1;
      else if (character === '[') bracketDepth += 1;
      else if (character === ']') bracketDepth -= 1;
      else if (character === '{') braceDepth += 1;
      else if (character === '}') braceDepth -= 1;
      else if (character === ',' && parenthesisDepth === 0
          && bracketDepth === 0 && braceDepth === 0) {
        comma = index;
        break;
      }
    }
    if (comma < 0) continue;
    for (let index = comma + 1; index < end - 1; index += 1) {
      if (masked[index] !== '\n' && masked[index] !== '\r')
        masked[index] = ' ';
    }
  }
}

function maskDynamicNodeExecutables(text,
  { reviewedNamedScenarioBodies = false } = {}) {
  const masked = text.split('');
  maskSecondCallArguments(text,
    /\bDynamicTest\s*\.\s*dynamicTest\s*\(/gu, masked);
  if (reviewedNamedScenarioBodies)
    maskSecondCallArguments(text, /\bnew\s+NamedScenario\s*\(/gu, masked);
  return masked.join('');
}

function matchingBraceEnd(masked, openBrace, label) {
  let cursor = openBrace + 1;
  let depth = 1;
  while (cursor < masked.length && depth > 0) {
    if (masked[cursor] === '{') depth += 1;
    else if (masked[cursor] === '}') depth -= 1;
    cursor += 1;
  }
  if (depth !== 0) fail(`Unbalanced Java brace scope near ${label}.`);
  return cursor;
}

function javaTypeScopes(path, text, masked) {
  const scopes = [];
  const pattern = /\b(?:class|interface|enum|record)\s+[A-Za-z_$][\w$]*[^;{}]*\{/gu;
  for (const match of masked.matchAll(pattern)) {
    const name = match[0].match(
      /\b(?:class|interface|enum|record)\s+([A-Za-z_$][\w$]*)/u)[1];
    const openBrace = match.index + match[0].lastIndexOf('{');
    const end = matchingBraceEnd(masked, openBrace, path);
    const boundaries = [masked.lastIndexOf('}', match.index - 1),
      masked.lastIndexOf(';', match.index - 1),
      masked.lastIndexOf('{', match.index - 1)];
    const headerStart = Math.max(...boundaries) + 1;
    const header = masked.slice(headerStart, openBrace);
    const annotations = [...header.matchAll(JUNIT_TIMEOUT_PATTERN)];
    if (annotations.length > 1)
      fail(`${path} type declaration has multiple @Timeout annotations.`);
    scopes.push({
      end,
      name,
      openBrace,
      timeoutMillis: annotations.length === 0 ? null
        : parseTimeoutArguments(annotations[0][1], `${path} type @Timeout`),
    });
  }
  return scopes;
}

function javaMethods(path, text) {
  const masked = maskJavaSource(text);
  const typeScopes = javaTypeScopes(path, text, masked);
  const lifecycleReceiverNames = [...new Set([
    ...[...masked.matchAll(
      /\b(?:Soklet(?:\s*\.\s*DefaultSimulator)?|SokletDirectLifecycle|HttpServer|SseServer|McpServer|TransportRuntime|InternalLifecycleCoordinator|SimulationSession|Fixture|Fleet|Graph|LifecycleHarness|Node|Owner|Runtime|[A-Za-z_$][\w$]*(?:Fixture|Fleet|Graph|Harness|HttpServer|SseServer|McpServer|LifecycleAdapter|LifecycleHarness|Node|Owner|PhaseGate|Runtime|RuntimeBridge|Simulator))\s+([A-Za-z_$][\w$]*)\b/gu)]
      .map((match) => match[1]),
    ...[...masked.matchAll(
      /\bvar\s+([A-Za-z_$][\w$]*)\s*=\s*(?:Soklet\s*\.\s*fromConfig\s*\(|new\s+SokletDirectLifecycle\s*\(|(?:new\s+)?[A-Za-z_$][\w$]*Harness(?:\s*\.|\s*\())/gu)]
      .map((match) => match[1]),
  ])];
  const methods = [];
  const seenOpenBraces = new Set();
  const callableRanges = [];
  const addScope = (scopeName, matchIndex, matchText,
    { isConstructor = false } = {}) => {
    const openBrace = matchIndex + matchText.lastIndexOf('{');
    if (seenOpenBraces.has(openBrace)) return;
    if (callableRanges.some((range) => range.openBrace < openBrace
        && openBrace < range.end)) return;
    const cursor = matchingBraceEnd(masked, openBrace,
      `${path} callable ${scopeName}`);
    const containingTypes = typeScopes.filter((scope) =>
      scope.openBrace < openBrace && scope.end >= cursor)
      .sort((left, right) => (left.end - left.openBrace)
        - (right.end - right.openBrace));
    const innermostType = containingTypes[0];
    if (innermostType === undefined) return;
    let memberDepth = 0;
    for (let index = innermostType.openBrace + 1;
      index < openBrace; index += 1) {
      if (masked[index] === '{') memberDepth += 1;
      else if (masked[index] === '}') memberDepth -= 1;
    }
    if (memberDepth !== 0) return;
    seenOpenBraces.add(openBrace);
    callableRanges.push({ end: cursor, openBrace });
    const openParenthesis = matchText.lastIndexOf('(');
    const closeParenthesis = matchingParenthesisEnd(matchText,
      openParenthesis);
    const parameterText = closeParenthesis === null ? ''
      : matchText.slice(openParenthesis + 1, closeParenthesis - 1).trim();
    const relativeName = matchText.lastIndexOf(scopeName, openParenthesis);
    const declarationIndex = matchIndex + relativeName;
    const line = text.slice(0, declarationIndex).split(/\r\n|\n|\r/u).length;
    const header = masked.slice(matchIndex, openBrace);
    const timeouts = [...header.matchAll(JUNIT_TIMEOUT_PATTERN)];
    if (timeouts.length > 1)
      fail(`${path}:${line} has multiple callable-scoped @Timeout annotations.`);
    const typeTimeoutMillis = containingTypes.find((scope) =>
      scope.timeoutMillis !== null)?.timeoutMillis ?? null;
    const methodTimeoutMillis = timeouts.length === 0 ? null
      : parseTimeoutArguments(timeouts[0][1], `${path}:${line} @Timeout`);
    const constructorScope = isConstructor
      || containingTypes[0]?.name === scopeName;
    const scopeKind = constructorScope ? 'CONSTRUCTOR'
      : /@(?:org\.junit\.jupiter\.api\.)?(?:Test|RepeatedTest|TestFactory|TestTemplate)\b/u
          .test(header)
        || /@(?:org\.junit\.jupiter\.params\.)?ParameterizedTest\b/u.test(header)
        ? 'TEST'
        : /@(?:org\.junit\.jupiter\.api\.)?(?:BeforeEach|AfterEach|BeforeAll|AfterAll)\b/u
            .test(header)
          ? 'SETUP_TEARDOWN' : 'HELPER';
    methods.push({
      body: masked.slice(openBrace + 1, cursor - 1),
      disabled: /@(?:org\.junit\.jupiter\.api\.)?Disabled\b/u.test(header),
      end: cursor,
      enclosingTypes: containingTypes.map((scope) => ({
        end: scope.end,
        name: scope.name,
        openBrace: scope.openBrace,
      })),
      effectiveOuterTimeoutMillis: methodTimeoutMillis
        ?? typeTimeoutMillis ?? STANDARD_JUNIT_GUARD_MILLIS,
      line,
      lineSha256: lineSha256(splitLines(text)[line - 1]),
      outerTimeoutScope: methodTimeoutMillis !== null ? 'METHOD'
        : typeTimeoutMillis !== null ? 'TYPE' : 'DEFAULT',
      openBrace,
      parameterCount: parameterText.length === 0 ? 0
        : splitTopLevelArguments(parameterText).length,
      path,
      receiverNames: lifecycleReceiverNames.filter((name) =>
        new RegExp(`\\b${name}\\b`, 'u').test(masked.slice(openBrace + 1,
          cursor - 1))),
      scopeName,
      scopeKind,
      testFactory: /@(?:org\.junit\.jupiter\.api\.)?TestFactory\b/u
        .test(header),
      scopeSha256: sha256(Buffer.from(
        text.slice(declarationIndex, cursor), 'utf8')),
    });
  };

  const pattern = /(?:^|[;{}]\s*|\n\s*)(?:(?:@[\w$.]+(?:\s*\([^;{}]*?\))?\s*)|(?:(?:public|protected|private|static|final|synchronized|abstract|native|strictfp|default)\s+))*?(?:<[^;{}]+>\s+)?[\w$@.<>\[\],?]+(?:\s+[\w$@.<>\[\],?]+)*\s+([A-Za-z_$][\w$]*)\s*\([^;{}]*\)\s*(?:throws\s+[^{}]+)?\s*\{/gmu;
  for (const match of masked.matchAll(pattern)) {
    const scopeName = match[1];
    if (['if', 'for', 'while', 'switch', 'catch', 'try', 'synchronized',
      'new'].includes(scopeName)) continue;
    addScope(scopeName, match.index, match[0]);
  }

  for (const type of typeScopes) {
    const escapedName = type.name.replace(/[.*+?^${}()|[\]\\]/gu, '\\$&');
    const constructorPattern = new RegExp(
      String.raw`(?:^|[;{}]\s*|\n\s*)(?:(?:@[\w$.]+(?:\s*\([^;{}]*?\))?\s*)|(?:(?:public|protected|private)\s+))*${escapedName}\s*\([^;{}]*\)\s*(?:throws\s+[^{}]+)?\s*\{`,
      'gmu');
    const segmentStart = type.openBrace + 1;
    const segment = masked.slice(segmentStart, type.end - 1);
    for (const match of segment.matchAll(constructorPattern)) {
      const absoluteIndex = segmentStart + match.index;
      const openBrace = absoluteIndex + match[0].lastIndexOf('{');
      const innermost = typeScopes.filter((scope) =>
        scope.openBrace < openBrace && scope.end > openBrace)
        .sort((left, right) => (left.end - left.openBrace)
          - (right.end - right.openBrace))[0];
      if (innermost !== type) continue;
      addScope(type.name, absoluteIndex, match[0], { isConstructor: true });
    }
  }
  return methods.sort((left, right) => left.line - right.line
    || asciiCompare(left.scopeName, right.scopeName));
}

function verifyNoUnscopedLifecycleExecution(path, text, methods) {
  const masked = maskJavaSource(text);
  const ranges = methods.map((method) => ({
    end: method.end,
    start: method.openBrace,
  })).sort((left, right) => left.start - right.start);
  let cursor = 0;
  for (const range of [...ranges, { end: masked.length, start: masked.length }]) {
    const segment = masked.slice(cursor, range.start);
    const match = UNSCOPED_LIFECYCLE_EXECUTION_PATTERN.exec(segment);
    UNSCOPED_LIFECYCLE_EXECUTION_PATTERN.lastIndex = 0;
    if (match !== null) {
      const index = cursor + match.index;
      const line = text.slice(0, index).split(/\r\n|\n|\r/u).length;
      fail(`Lifecycle execution appears outside a parsed callable scope: ${path}:${line}.`);
    }
    cursor = Math.max(cursor, range.end);
  }
}

function literalPolicyFromBuilderChain(chain, durationConstants = new Map()) {
  const policy = {
    forcedShutdownMillis: DEFAULT_PHASE_POLICY.forcedShutdownMillis,
    gracefulShutdownMillis: DEFAULT_PHASE_POLICY.gracefulShutdownMillis,
    startupCancellationMillis: DEFAULT_PHASE_POLICY.startupCancellationMillis,
    startupMillis: DEFAULT_PHASE_POLICY.startupMillis,
  };
  for (const [setter, field] of [
    ['startupTimeout', 'startupMillis'],
    ['startupCancellationTimeout', 'startupCancellationMillis'],
    ['gracefulShutdownDuration', 'gracefulShutdownMillis'],
    ['forcedShutdownDuration', 'forcedShutdownMillis'],
  ]) {
    const setterMatches = [...chain.matchAll(new RegExp(
      `\\.\\s*${setter}\\s*\\(`, 'gu'))];
    for (const setterMatch of setterMatches) {
      const openParenthesis = setterMatch.index
        + setterMatch[0].lastIndexOf('(');
      const end = matchingParenthesisEnd(chain, openParenthesis);
      if (end === null) return null;
      const duration = resolveDurationExpression(chain.slice(
        openParenthesis + 1, end - 1),
        durationConstants);
      if (duration === undefined) return null;
      policy[field] = duration;
    }
  }
  const lastStartupTimeout = [...chain.matchAll(
    /\.\s*startupTimeout\s*\(/gu)].at(-1)?.index ?? -1;
  const lastNoStartupTimeout = [...chain.matchAll(
    /\.\s*noStartupTimeout\s*\(\s*\)/gu)].at(-1)?.index ?? -1;
  if (lastNoStartupTimeout > lastStartupTimeout)
    policy.startupMillis = null;
  return policy;
}

function javaDurationConstants(masked) {
  const constants = new Map();
  const pattern = /\bDuration\s+([A-Za-z_$][\w$]*)\s*=\s*Duration\s*\.\s*(ZERO|of(?:Nanos|Micros|Millis|Seconds|Minutes|Hours|Days))\s*(?:\(\s*(\d+)\s*\))?\s*;/gu;
  for (const match of masked.matchAll(pattern)) {
    let millis;
    if (match[2] === 'ZERO') millis = 0;
    else {
      millis = Number.parseInt(match[3], 10) * ({
      ofNanos: 1 / 1_000_000,
      ofMicros: 1 / 1_000,
      ofMillis: 1,
      ofSeconds: 1_000,
      ofMinutes: 60_000,
      ofHours: 3_600_000,
      ofDays: 86_400_000,
      })[match[2]];
    }
    if (!Number.isSafeInteger(millis)) continue;
    if (constants.has(match[1]) && constants.get(match[1]) !== millis)
      constants.set(match[1], undefined);
    else if (!constants.has(match[1])) constants.set(match[1], millis);
  }
  return constants;
}

function javaNumericConstants(masked) {
  const constants = new Map();
  for (const match of masked.matchAll(
    /\b(?:byte|short|int|long)\s+([A-Za-z_$][\w$]*)\s*=\s*([0-9][0-9_]*)[lL]?\s*;/gu)) {
    const value = Number.parseInt(match[2].replaceAll('_', ''), 10);
    if (!Number.isSafeInteger(value)) continue;
    if (constants.has(match[1]) && constants.get(match[1]) !== value)
      constants.set(match[1], undefined);
    else if (!constants.has(match[1])) constants.set(match[1], value);
  }
  return constants;
}

function resolveDurationExpression(expression, durationConstants) {
  const trimmed = expression.trim();
  if (durationConstants.has(trimmed)) return durationConstants.get(trimmed);
  const match = trimmed.match(
    /^(?:java\s*\.\s*time\s*\.\s*)?Duration\s*\.\s*(ZERO|of(?:Nanos|Micros|Millis|Seconds|Minutes|Hours|Days))\s*(?:\(\s*(\d+)\s*\))?$/u);
  if (match === null) return undefined;
  if (match[1] === 'ZERO') return 0;
  const millis = Number.parseInt(match[2], 10) * ({
    ofNanos: 1 / 1_000_000,
    ofMicros: 1 / 1_000,
    ofMillis: 1,
    ofSeconds: 1_000,
    ofMinutes: 60_000,
    ofHours: 3_600_000,
    ofDays: 86_400_000,
  })[match[1]];
  return Number.isSafeInteger(millis) ? millis : undefined;
}

function resolveMillisNumber(expression, numericConstants,
  durationConstants) {
  const trimmed = expression.trim();
  const literal = trimmed.match(/^([0-9][0-9_]*)[lL]?$/u);
  if (literal !== null)
    return Number.parseInt(literal[1].replaceAll('_', ''), 10);
  if (numericConstants.has(trimmed)) return numericConstants.get(trimmed);
  const durationMillis = trimmed.match(/^([A-Za-z_$][\w$]*)\s*\.\s*toMillis\s*\(\s*\)$/u);
  if (durationMillis !== null)
    return durationConstants.get(durationMillis[1]);
  const durationNanos = trimmed.match(/^([A-Za-z_$][\w$]*)\s*\.\s*toNanos\s*\(\s*\)$/u);
  if (durationNanos !== null) {
    const millis = durationConstants.get(durationNanos[1]);
    const nanos = millis === undefined ? undefined : millis * 1_000_000;
    return Number.isSafeInteger(nanos) ? nanos : undefined;
  }
  const inlineDurationMillis = trimmed.match(
    /^((?:java\s*\.\s*time\s*\.\s*)?Duration\s*\.\s*(?:ZERO|of(?:Nanos|Micros|Millis|Seconds|Minutes|Hours|Days))\s*(?:\(\s*\d+\s*\))?)\s*\.\s*toMillis\s*\(\s*\)$/u);
  if (inlineDurationMillis !== null)
    return resolveDurationExpression(inlineDurationMillis[1],
      durationConstants);
  const inlineDurationNanos = trimmed.match(
    /^((?:java\s*\.\s*time\s*\.\s*)?Duration\s*\.\s*(?:ZERO|of(?:Nanos|Micros|Millis|Seconds|Minutes|Hours|Days))\s*(?:\(\s*\d+\s*\))?)\s*\.\s*toNanos\s*\(\s*\)$/u);
  if (inlineDurationNanos !== null) {
    const millis = resolveDurationExpression(inlineDurationNanos[1],
      durationConstants);
    const nanos = millis === undefined ? undefined : millis * 1_000_000;
    return Number.isSafeInteger(nanos) ? nanos : undefined;
  }
  const timeUnitMillis = trimmed.match(
    /^TimeUnit\s*\.\s*(NANOSECONDS|MICROSECONDS|MILLISECONDS|SECONDS|MINUTES|HOURS|DAYS)\s*\.\s*toMillis\s*\(\s*([0-9][0-9_]*)[lL]?\s*\)$/u);
  if (timeUnitMillis !== null) {
    const value = Number.parseInt(timeUnitMillis[2].replaceAll('_', ''), 10);
    return Math.ceil(value * ({
      DAYS: 86_400_000,
      HOURS: 3_600_000,
      MICROSECONDS: 1 / 1_000,
      MILLISECONDS: 1,
      MINUTES: 60_000,
      NANOSECONDS: 1 / 1_000_000,
      SECONDS: 1_000,
    })[timeUnitMillis[1]]);
  }
  return undefined;
}

function matchingParenthesisEnd(masked, openParenthesis) {
  let depth = 1;
  for (let index = openParenthesis + 1; index < masked.length; index += 1) {
    if (masked[index] === '(') depth += 1;
    else if (masked[index] === ')' && --depth === 0) return index + 1;
  }
  return null;
}

function splitTopLevelArguments(text) {
  const argumentsList = [];
  let parenthesisDepth = 0;
  let angleDepth = 0;
  let bracketDepth = 0;
  let braceDepth = 0;
  let start = 0;
  for (let index = 0; index < text.length; index += 1) {
    if (text[index] === '(') parenthesisDepth += 1;
    else if (text[index] === ')') parenthesisDepth -= 1;
    else if (text[index] === '[') bracketDepth += 1;
    else if (text[index] === ']') bracketDepth -= 1;
    else if (text[index] === '{') braceDepth += 1;
    else if (text[index] === '}') braceDepth -= 1;
    else if (text[index] === '<') angleDepth += 1;
    else if (text[index] === '>' && angleDepth > 0) angleDepth -= 1;
    else if (text[index] === ',' && parenthesisDepth === 0
        && angleDepth === 0 && bracketDepth === 0 && braceDepth === 0) {
      argumentsList.push(text.slice(start, index).trim());
      start = index + 1;
    }
  }
  argumentsList.push(text.slice(start).trim());
  return argumentsList;
}

function repetitionContext(body, siteIndex) {
  let multiplier = 1;
  let unresolved = 0;
  for (const match of body.matchAll(/\b(for|while)\s*\(/gu)) {
    const openParenthesis = match.index + match[0].lastIndexOf('(');
    const closeParenthesis = matchingParenthesisEnd(body, openParenthesis);
    if (closeParenthesis === null) continue;
    let bodyStart = closeParenthesis;
    while (/\s/u.test(body[bodyStart] ?? '')) bodyStart += 1;
    let bodyEnd;
    if (body[bodyStart] === '{') {
      bodyEnd = matchingBraceEnd(body, bodyStart, 'lifecycle repetition');
    } else {
      const semicolon = body.indexOf(';', bodyStart);
      bodyEnd = semicolon < 0 ? body.length : semicolon + 1;
    }
    if (!(bodyStart <= siteIndex && siteIndex < bodyEnd)) continue;
    if (match[1] === 'while') {
      unresolved += 1;
      continue;
    }
    const header = body.slice(openParenthesis + 1, closeParenthesis - 1);
    const classic = header.split(';');
    const initial = classic[0]?.match(/=\s*(\d+)\s*$/u);
    const bound = classic[1]?.match(/(?:<|<=)\s*(\d+)\s*$/u);
    if (classic.length === 3 && initial !== null && bound !== null) {
      const start = Number.parseInt(initial[1], 10);
      const limit = Number.parseInt(bound[1], 10)
        + (classic[1].includes('<=') ? 1 : 0);
      const iterations = limit - start;
      if (Number.isSafeInteger(iterations) && iterations > 0) {
        multiplier *= iterations;
        continue;
      }
    }
    // Enhanced-for, symbolic classic-for, and malformed loop bounds are all
    // manual topology until an independently source-bound review supplies the
    // exact sequential/max composition.
    unresolved += 1;
  }
  for (const match of body.matchAll(
    /\.\s*(?:forEach|map|flatMap)\s*\(/gu)) {
    const openParenthesis = match.index + match[0].lastIndexOf('(');
    const end = matchingParenthesisEnd(body, openParenthesis);
    if (end !== null && openParenthesis < siteIndex && siteIndex < end)
      unresolved += 1;
  }
  return { multiplier, unresolved };
}

function dynamicTestFacts(body, durationConstants) {
  const guards = [];
  let siteCount = 0;
  let unwrappedCount = 0;
  for (const match of body.matchAll(
    /\bDynamicTest\s*\.\s*dynamicTest\s*\(/gu)) {
    siteCount += 1;
    const openParenthesis = match.index + match[0].lastIndexOf('(');
    const end = matchingParenthesisEnd(body, openParenthesis);
    if (end === null) {
      unwrappedCount += 1;
      continue;
    }
    const args = splitTopLevelArguments(body.slice(openParenthesis + 1,
      end - 1));
    const executable = args[1] ?? '';
    const wrapper = /^\s*\(\s*\)\s*->\s*(?:Assertions\s*\.\s*)?assertTimeoutPreemptively\s*\(/u
      .exec(executable);
    if (wrapper === null) {
      unwrappedCount += 1;
      continue;
    }
    const wrapperOpen = wrapper.index + wrapper[0].lastIndexOf('(');
    const wrapperEnd = matchingParenthesisEnd(executable, wrapperOpen);
    if (wrapperEnd === null) {
      unwrappedCount += 1;
      continue;
    }
    const wrapperArgs = splitTopLevelArguments(executable.slice(
      wrapperOpen + 1, wrapperEnd - 1));
    if (wrapperArgs.length < 2) {
      unwrappedCount += 1;
      continue;
    }
    const guard = resolveDurationExpression(wrapperArgs[0],
      durationConstants);
    if (guard === undefined) {
      unwrappedCount += 1;
      continue;
    }
    guards.push(guard);
  }
  return {
    dynamicNodeGuardMillis: siteCount > 0 && unwrappedCount === 0
      ? Math.min(...guards) : null,
    dynamicNodeSiteCount: siteCount,
    unwrappedDynamicNodeCount: unwrappedCount,
  };
}

function dynamicProducerFacts(body, durationConstants) {
  let siteCount = 0;
  let unwrappedCount = 0;
  const guards = [];
  for (const match of body.matchAll(
    /\bDynamicTest\s*\.\s*dynamicTest\s*\(/gu)) {
    siteCount += 1;
    const before = body.slice(0, match.index);
    const lastReturn = before.lastIndexOf('return');
    const returnedDirectly = lastReturn >= 0
      && before.slice(lastReturn + 'return'.length).indexOf(';') < 0;
    const added = /\b([A-Za-z_$][\w$]*)\s*\.\s*add\s*\(\s*$/u
      .exec(before);
    const returnedCollection = added !== null && new RegExp(
      `\\breturn\\s+${added[1]}\\s*;`, 'u').test(body.slice(match.index));
    if (!returnedDirectly && !returnedCollection) {
      unwrappedCount += 1;
      continue;
    }
    const openParenthesis = match.index + match[0].lastIndexOf('(');
    const end = matchingParenthesisEnd(body, openParenthesis);
    if (end === null) {
      unwrappedCount += 1;
      continue;
    }
    const facts = dynamicTestFacts(body.slice(match.index, end),
      durationConstants);
    unwrappedCount += facts.unwrappedDynamicNodeCount;
    if (facts.dynamicNodeGuardMillis !== null)
      guards.push(facts.dynamicNodeGuardMillis);
  }
  return {
    dynamicNodeGuardMillis: siteCount > 0 && unwrappedCount === 0
      && guards.length === siteCount ? Math.min(...guards) : null,
    dynamicNodeSiteCount: siteCount,
    unwrappedDynamicNodeCount: unwrappedCount,
  };
}

function dynamicFactoryNodeCount(method) {
  if (!method.testFactory) return 0;
  const body = method.body;
  const explicitListCounts = [];
  for (const match of body.matchAll(
    /\b(?:List\s*<[^<>;=\r\n]+>|var)\s+([A-Za-z_$][\w$]*)\s*=\s*List\s*\.\s*of\s*\(/gu)) {
    const openParenthesis = match.index + match[0].lastIndexOf('(');
    const end = matchingParenthesisEnd(body, openParenthesis);
    if (end === null) continue;
    const name = match[1];
    const tail = body.slice(end);
    const escaped = name.replace(/[.*+?^${}()|[\]\\]/gu, '\\$&');
    const feedsDirectProducer = new RegExp(
      `\\breturn\\s+${escaped}\\s*\\.\\s*stream\\s*\\(\\s*\\)[\\s\\S]*?\\bDynamicTest\\s*\\.\\s*dynamicTest\\s*\\(`,
      'u').test(tail);
    const feedsProducerHelper = new RegExp(
      `\\breturn\\s+dynamicTests\\s*\\(\\s*${escaped}\\s*\\)\\s*;`,
      'u').test(tail);
    let feedsEnhancedForProducer = false;
    const enhancedFor = new RegExp(
      `\\bfor\\s*\\([^:;]+:\\s*${escaped}\\s*\\)\\s*`, 'gu');
    for (const loop of tail.matchAll(enhancedFor)) {
      const statementStart = loop.index + loop[0].length;
      if (tail[statementStart] === '{') {
        const loopEnd = matchingBraceEnd(tail, statementStart,
          `dynamic factory ${method.scopeName} enhanced-for`);
        if (/\bDynamicTest\s*\.\s*dynamicTest\s*\(/u.test(
          tail.slice(statementStart + 1, loopEnd - 1))) {
          feedsEnhancedForProducer = true;
          break;
        }
      } else {
        const statementEnd = tail.indexOf(';', statementStart);
        if (statementEnd >= 0
            && /\bDynamicTest\s*\.\s*dynamicTest\s*\(/u.test(
              tail.slice(statementStart, statementEnd + 1))) {
          feedsEnhancedForProducer = true;
          break;
        }
      }
    }
    if (!feedsDirectProducer && !feedsProducerHelper
        && !feedsEnhancedForProducer) continue;
    const elements = body.slice(openParenthesis + 1, end - 1).trim();
    explicitListCounts.push(elements.length === 0 ? 0
      : splitTopLevelArguments(elements).length);
  }
  let repeatedDynamicSiteCount = 0;
  let unresolvedDynamicRepetition = false;
  for (const match of body.matchAll(
    /\bDynamicTest\s*\.\s*dynamicTest\s*\(/gu)) {
    const repetition = repetitionContext(body, match.index);
    if (repetition.unresolved > 0) {
      unresolvedDynamicRepetition = true;
      break;
    }
    repeatedDynamicSiteCount += repetition.multiplier;
  }
  const explicitCandidates = explicitListCounts.filter((count) => count > 0);
  if (explicitCandidates.length > 1) return 0;
  const candidates = [...explicitCandidates,
    ...(unresolvedDynamicRepetition ? [] : [repeatedDynamicSiteCount])]
    .filter((count) => count > 0);
  return candidates.length === 0 ? 0 : Math.max(...candidates);
}

function deadlineBudgetFacts(body, durationConstants, numericConstants) {
  const facts = [];
  const unitMillis = {
    DAYS: 86_400_000,
    HOURS: 3_600_000,
    MICROSECONDS: 1 / 1_000,
    MILLISECONDS: 1,
    MINUTES: 60_000,
    NANOSECONDS: 1 / 1_000_000,
    SECONDS: 1_000,
  };
  const deltaMillis = (expression, nanoClock) => {
    const timeUnit = expression.trim().match(
      /^(?:(?:[A-Za-z_$][\w$]*\s*\.\s*)*TimeUnit\s*\.\s*)?(NANOSECONDS|MICROSECONDS|MILLISECONDS|SECONDS|MINUTES|HOURS|DAYS)\s*\.\s*toNanos\s*\(\s*([0-9][0-9_]*)[lL]?\s*\)$/u);
    if (timeUnit !== null) {
      const value = Number.parseInt(timeUnit[2].replaceAll('_', ''), 10);
      const millis = Math.ceil(value * unitMillis[timeUnit[1]]);
      return Number.isSafeInteger(millis) ? millis : undefined;
    }
    const resolved = resolveMillisNumber(expression, numericConstants,
      durationConstants);
    if (!Number.isSafeInteger(resolved)) return undefined;
    const millis = nanoClock ? Math.ceil(resolved / 1_000_000) : resolved;
    return Number.isSafeInteger(millis) ? millis : undefined;
  };
  const pattern = /\b(?:final\s+)?(?:long|var)\s+([A-Za-z_$][\w$]*)\s*=\s*(?:System\s*\.\s*)?(nanoTime|currentTimeMillis)\s*\(\s*\)\s*\+\s*([^;]+);/gu;
  for (const match of body.matchAll(pattern)) {
    const references = [...body.matchAll(new RegExp(
      `\\b${match[1]}\\b`, 'gu'))].length;
    if (references < 2) continue;
    const millis = deltaMillis(match[3], match[2] === 'nanoTime');
    if (!Number.isSafeInteger(millis) || millis <= 0) continue;
    facts.push({ index: match.index, millis, name: match[1] });
  }
  return facts;
}

function deadlineControlsPollingSite(body, deadline, siteIndex) {
  const escaped = deadline.name.replace(/[.*+?^${}()|[\]\\]/gu, '\\$&');
  const comparison = new RegExp(
    `(?:\\b${escaped}\\b[\\s\\S]{0,96}(?:<|>|compare)|(?:<|>|compare)[\\s\\S]{0,96}\\b${escaped}\\b)`,
    'u');
  const hasGuardedExit = (prefix) => {
    for (const conditional of prefix.matchAll(/\bif\s*\(/gu)) {
      const conditionalOpen = conditional.index
        + conditional[0].lastIndexOf('(');
      const conditionalEnd = matchingParenthesisEnd(prefix,
        conditionalOpen);
      if (conditionalEnd === null
          || !comparison.test(prefix.slice(conditionalOpen + 1,
            conditionalEnd - 1)))
        continue;
      const exit = prefix.slice(conditionalEnd).trimStart();
      if (/^(?:\{\s*)?(?:return\b|break\s*;|throw\b|(?:Assertions\s*\.\s*)?fail\s*\()/u
        .test(exit))
        return true;
    }
    return false;
  };
  for (const loop of body.matchAll(/\bwhile\s*\(/gu)) {
    const openParenthesis = loop.index + loop[0].lastIndexOf('(');
    const closeParenthesis = matchingParenthesisEnd(body, openParenthesis);
    if (closeParenthesis === null) continue;
    let bodyStart = closeParenthesis;
    while (/\s/u.test(body[bodyStart] ?? '')) bodyStart += 1;
    let bodyEnd;
    if (body[bodyStart] === '{')
      bodyEnd = matchingBraceEnd(body, bodyStart, 'deadline polling loop');
    else {
      const semicolon = body.indexOf(';', bodyStart);
      if (semicolon < 0) continue;
      bodyEnd = semicolon + 1;
    }
    if (siteIndex < bodyStart || siteIndex >= bodyEnd) continue;
    const condition = body.slice(openParenthesis + 1,
      closeParenthesis - 1);
    if (comparison.test(condition)) return true;
    if (hasGuardedExit(body.slice(bodyStart, siteIndex))) return true;
  }
  for (const loop of body.matchAll(/\bdo\b/gu)) {
    let bodyStart = loop.index + loop[0].length;
    while (/\s/u.test(body[bodyStart] ?? '')) bodyStart += 1;
    let bodyEnd;
    if (body[bodyStart] === '{')
      bodyEnd = matchingBraceEnd(body, bodyStart, 'deadline do-while loop');
    else {
      const semicolon = body.indexOf(';', bodyStart);
      if (semicolon < 0) continue;
      bodyEnd = semicolon + 1;
    }
    if (siteIndex < bodyStart || siteIndex >= bodyEnd) continue;
    const trailer = /^\s*while\s*\(/u.exec(body.slice(bodyEnd));
    if (trailer === null) continue;
    const openParenthesis = bodyEnd + trailer.index
      + trailer[0].lastIndexOf('(');
    const closeParenthesis = matchingParenthesisEnd(body, openParenthesis);
    if (closeParenthesis === null) continue;
    const condition = body.slice(openParenthesis + 1,
      closeParenthesis - 1);
    if (comparison.test(condition)
        || hasGuardedExit(body.slice(bodyStart, siteIndex)))
      return true;
  }
  return false;
}

function fixedControlWaitFacts(body, durationConstants, numericConstants,
  localCallableNames = new Set(), siteContext = null) {
  let count = 0;
  let millis = 0;
  const rawSites = [];
  const sites = [];
  let unresolvedCount = 0;
  const unitMultipliers = {
    DAYS: 86_400_000,
    HOURS: 3_600_000,
    MICROSECONDS: 1 / 1_000,
    MILLISECONDS: 1,
    MINUTES: 60_000,
    NANOSECONDS: 1 / 1_000_000,
    SECONDS: 1_000,
  };
  for (const match of body.matchAll(
    /(?:\.\s*|(?<![\w$]))(await(?:[A-Z][A-Za-z0-9_$]*)?|waitFor(?:[A-Z][A-Za-z0-9_$]*)?|waitUntil|get|join|sleep|park|parkNanos|parkUntil|orTimeout|completeOnTimeout|connectWithRetry)\s*\(/gu)) {
    const method = match[1];
    if (method === 'join'
        && /\b(?:String|Collectors)\s*$/u.test(body.slice(
          Math.max(0, match.index - 32), match.index)))
      continue;
    if (localCallableNames.has(method)) continue;
    const openParenthesis = match.index + match[0].lastIndexOf('(');
    const end = matchingParenthesisEnd(body, openParenthesis);
    if (end === null) continue;
    const args = splitTopLevelArguments(body.slice(openParenthesis + 1,
      end - 1));
    const zeroArgumentBlockingWait = args.length === 1 && args[0] === ''
      && (method === 'join' || method === 'await');
    if (zeroArgumentBlockingWait && method === 'join') {
      const prefix = body.slice(Math.max(0, match.index - 160), match.index);
      const lifecycleCoreJoin = /\.\s*(?:shutdown|close|whenTerminated)\s*\([^;]*\)\s*\.\s*toCompletableFuture\s*\(\s*\)\s*$/u
        .test(prefix);
      let boundedSubmittedJoin = false;
      const preceding = body.slice(0, match.index);
      const assignments = [...preceding.matchAll(
        /\b(?:Future|CompletableFuture)(?:\s*<[^;=]+>)?\s+([A-Za-z_$][\w$]*)\s*=\s*[^;]{0,240}?\.\s*submit\s*\(/gu)];
      const assignment = assignments.at(-1);
      if (assignment !== undefined) {
        const submitOpen = assignment.index
          + assignment[0].lastIndexOf('(');
        const submitEnd = matchingParenthesisEnd(body, submitOpen);
        const escaped = assignment[1].replace(
          /[.*+?^${}()|[\]\\]/gu, '\\$&');
        boundedSubmittedJoin = submitEnd !== null
          && match.index < submitEnd
          && new RegExp(`\\b${escaped}\\s*\\.\\s*get\\s*\\(\\s*[^,()]+,\\s*(?:(?:[A-Za-z_$][\\w$]*\\s*\\.\\s*)*TimeUnit\\s*\\.\\s*)?[A-Z]+\\s*\\)`, 'u')
            .test(body.slice(submitEnd));
      }
      if (lifecycleCoreJoin || boundedSubmittedJoin) continue;
    }
    if (args.length === 1 && args[0] === '' && !zeroArgumentBlockingWait)
      continue;
    if (method === 'get' && args.length < 2) continue;
    const resolvedDurations = args.map((argument) =>
      resolveDurationExpression(argument, durationConstants))
      .filter((value) => Number.isSafeInteger(value) && value >= 0);
    for (let index = 0; index + 1 < args.length; index += 1) {
      const numeric = resolveMillisNumber(args[index], numericConstants,
        durationConstants);
      const unit = args[index + 1].match(
        /^(?:(?:[A-Za-z_$][\w$]*\s*\.\s*)*TimeUnit\s*\.\s*)?(NANOSECONDS|MICROSECONDS|MILLISECONDS|SECONDS|MINUTES|HOURS|DAYS)$/u);
      if (Number.isSafeInteger(numeric) && unit !== null) {
        resolvedDurations.push(Math.ceil(numeric
          * unitMultipliers[unit[1]]));
      }
    }
    if (resolvedDurations.length === 0 && ['join', 'sleep'].includes(method)) {
      const numeric = resolveMillisNumber(args[0] ?? '', numericConstants,
        durationConstants);
      if (Number.isSafeInteger(numeric)) resolvedDurations.push(numeric);
    }
    if (resolvedDurations.length === 0 && method === 'parkNanos') {
      const numeric = resolveMillisNumber(args[0] ?? '', numericConstants,
        durationConstants);
      if (Number.isSafeInteger(numeric))
        resolvedDurations.push(Math.ceil(numeric / 1_000_000));
    }
    if (resolvedDurations.length === 0
        && /^(?:waitForEof|connectWithRetry)$/u.test(method)) {
      const numeric = [...args].reverse().map((argument) =>
        resolveMillisNumber(argument, numericConstants, durationConstants))
        .find(Number.isSafeInteger);
      if (numeric !== undefined) resolvedDurations.push(numeric);
    }
    const timeoutLooking = resolvedDurations.length > 0
      || zeroArgumentBlockingWait
      || ['join', 'sleep', 'park', 'parkNanos', 'parkUntil', 'orTimeout',
        'completeOnTimeout'].includes(method)
      || /^(?:await[A-Z]|waitFor[A-Z]|waitUntil|connectWithRetry)/u
        .test(method)
      || (method === 'get' && args.length >= 2)
      || args.some((argument) => /(?:\bDuration\b|\bTimeUnit\b|\b(?:NANOSECONDS|MICROSECONDS|MILLISECONDS|SECONDS|MINUTES|HOURS|DAYS)\b|timeout|deadline|budget|nanos|micros|millis|seconds|minutes|hours)/iu
        .test(argument));
    if (!timeoutLooking) continue;
    const repetition = repetitionContext(body, match.index);
    count += repetition.multiplier;
    const unresolved = resolvedDurations.length === 0
      || repetition.unresolved > 0;
    const perOccurrenceMillis = resolvedDurations.length === 0 ? null
      : Math.max(...resolvedDurations);
    if (unresolved)
      unresolvedCount += 1;
    else millis += perOccurrenceMillis * repetition.multiplier;
    let sourceLine = null;
    if (siteContext !== null) {
      const absoluteIndex = siteContext.bodyStart + match.index;
      sourceLine = siteContext.text.slice(0, absoluteIndex)
        .split(/\r\n|\n|\r/u).length;
    }
    rawSites.push({
      composedMillis: unresolved ? null
        : perOccurrenceMillis * repetition.multiplier,
      index: match.index,
      line: sourceLine,
      method,
      unresolved,
    });
    if (siteContext !== null) {
      const absoluteIndex = siteContext.bodyStart + match.index;
      const line = siteContext.text.slice(0, absoluteIndex)
        .split(/\r\n|\n|\r/u).length;
      sites.push({
        composedMillis: unresolved ? null
          : perOccurrenceMillis * repetition.multiplier,
        line,
        lineSha256: lineSha256(splitLines(siteContext.text)[line - 1]),
        method,
        occurrenceCount: repetition.multiplier,
        path: siteContext.path,
        perOccurrenceMillis,
        unresolved,
      });
    }
  }
  const deadlineFacts = deadlineBudgetFacts(body, durationConstants,
    numericConstants);
  const deadlinePollingSites = deadlineFacts.length === 1
    ? rawSites.filter((site) => ['park', 'parkNanos', 'sleep']
      .includes(site.method)
      && deadlineControlsPollingSite(body, deadlineFacts[0], site.index))
    : [];
  if (deadlinePollingSites.length > 0
      && rawSites.filter((site) => site.unresolved).every((site) =>
        deadlinePollingSites.includes(site))) {
    const removalCounts = new Map();
    for (const site of deadlinePollingSites) {
      const key = `${site.line}:${site.method}`;
      removalCounts.set(key, (removalCounts.get(key) ?? 0) + 1);
      if (site.unresolved) unresolvedCount -= 1;
      else millis -= site.composedMillis;
    }
    for (let index = sites.length - 1; index >= 0; index -= 1) {
      const key = `${sites[index].line}:${sites[index].method}`;
      const remaining = removalCounts.get(key) ?? 0;
      if (remaining > 0) {
        sites.splice(index, 1);
        removalCounts.set(key, remaining - 1);
      }
    }
    const deadline = deadlineFacts[0];
    millis += deadline.millis;
    if (siteContext !== null) {
      const absoluteIndex = siteContext.bodyStart + deadline.index;
      const line = siteContext.text.slice(0, absoluteIndex)
        .split(/\r\n|\n|\r/u).length;
      sites.push({
        composedMillis: deadline.millis,
        line,
        lineSha256: lineSha256(splitLines(siteContext.text)[line - 1]),
        method: 'deadlinePoll',
        occurrenceCount: 1,
        path: siteContext.path,
        perOccurrenceMillis: deadline.millis,
        unresolved: false,
      });
    }
  }
  return { count, millis, sites, unresolvedCount };
}

function scanInternalPolicies(masked, durationConstants) {
  const policies = [];
  let unresolvedCount = 0;
  for (const match of masked.matchAll(
    /\bnew\s+InternalLifecyclePolicy\s*\(/gu)) {
    const openParenthesis = match.index + match[0].lastIndexOf('(');
    const end = matchingParenthesisEnd(masked, openParenthesis);
    if (end === null) {
      unresolvedCount += 1;
      continue;
    }
    const args = splitTopLevelArguments(masked.slice(openParenthesis + 1,
      end - 1));
    if (args.length !== 4) {
      unresolvedCount += 1;
      continue;
    }
    let startupMillis;
    if (/^Optional\s*\.\s*empty\s*\(\s*\)$/u.test(args[0])) {
      startupMillis = null;
    } else {
      const optional = args[0].match(
        /^Optional\s*\.\s*of\s*\(([\s\S]*)\)$/u);
      startupMillis = optional === null ? undefined
        : resolveDurationExpression(optional[1], durationConstants);
    }
    const phases = args.slice(1).map((argument) =>
      resolveDurationExpression(argument, durationConstants));
    if (startupMillis === undefined
        || phases.some((phase) => phase === undefined)) {
      unresolvedCount += 1;
      continue;
    }
    policies.push({
      forcedShutdownMillis: phases[2],
      gracefulShutdownMillis: phases[1],
      startupCancellationMillis: phases[0],
      startupMillis,
    });
  }
  return { policies, unresolvedCount };
}

function javaFieldPolicies(path, text) {
  const masked = maskJavaSource(text);
  const durationConstants = javaDurationConstants(masked);
  const policies = new Map();
  const typeScopes = javaTypeScopes(path, text, masked);
  const append = (name, index, policy) => {
    const type = typeScopes.filter((candidate) =>
      candidate.openBrace < index && index < candidate.end)
      .sort((left, right) => (left.end - left.openBrace)
        - (right.end - right.openBrace))[0];
    if (type === undefined) return;
    if (!policies.has(name)) policies.set(name, []);
    policies.get(name).push({
      ...policy,
      typeEnd: type.end,
      typeName: type.name,
      typeOpenBrace: type.openBrace,
    });
  };
  const pattern = /\bLifecyclePolicy\s+([A-Za-z_$][\w$]*)\s*=\s*LifecyclePolicy\s*\.\s*builder\s*\(\s*\)([\s\S]{0,1800}?)\.\s*build\s*\(\s*\)\s*;/gu;
  for (const match of masked.matchAll(pattern)) {
    const policy = literalPolicyFromBuilderChain(match[2], durationConstants);
    const line = text.slice(0, match.index).split(/\r\n|\n|\r/u).length;
    append(match[1], match.index, {
      line,
      name: match[1],
      path,
      phasePolicy: policy,
      spanSha256: sha256(Buffer.from(
        text.slice(match.index, match.index + match[0].length), 'utf8')),
      unresolved: policy === null,
    });
  }
  const internalFieldPattern = /\bInternalLifecyclePolicy\s+([A-Za-z_$][\w$]*)\s*=\s*new\s+InternalLifecyclePolicy\s*\(/gu;
  for (const match of masked.matchAll(internalFieldPattern)) {
    const openParenthesis = match.index + match[0].lastIndexOf('(');
    const end = matchingParenthesisEnd(masked, openParenthesis);
    if (end === null) {
      append(match[1], match.index, {
        line: text.slice(0, match.index).split(/\r\n|\n|\r/u).length,
        name: match[1],
        path,
        phasePolicy: null,
        spanSha256: sha256(Buffer.from(match[0], 'utf8')),
        unresolved: true,
      });
      continue;
    }
    const scanned = scanInternalPolicies(masked.slice(match.index, end),
      durationConstants);
    const line = text.slice(0, match.index).split(/\r\n|\n|\r/u).length;
    append(match[1], match.index, {
      line,
      name: match[1],
      path,
      phasePolicy: scanned.policies.length === 1
          && scanned.unresolvedCount === 0 ? scanned.policies[0] : null,
      spanSha256: sha256(Buffer.from(text.slice(match.index, end), 'utf8')),
      unresolved: scanned.policies.length !== 1
        || scanned.unresolvedCount !== 0,
    });
  }
  return policies;
}

function resolveFieldPolicy(fieldPolicies, name, method, qualifier = null) {
  const candidates = (fieldPolicies.get(name) ?? []).filter((policy) =>
    policy.typeOpenBrace < method.openBrace
      && method.openBrace < policy.typeEnd
      && (qualifier === null || qualifier === policy.typeName))
    .sort((left, right) => (left.typeEnd - left.typeOpenBrace)
      - (right.typeEnd - right.typeOpenBrace));
  if (candidates.length === 0) return undefined;
  const nearestSpan = candidates[0].typeEnd - candidates[0].typeOpenBrace;
  const nearest = candidates.filter((candidate) =>
    candidate.typeEnd - candidate.typeOpenBrace === nearestSpan);
  if (nearest.length !== 1) return {
    ambiguous: true,
    line: method.line,
    name,
    path: method.path,
    phasePolicy: null,
    spanSha256: method.scopeSha256,
    unresolved: true,
  };
  return nearest[0];
}

function directLifecycleFacts(method, fieldPolicies, durationConstants,
  numericConstants, localCallableNames = new Set(), sourceText = null) {
  const body = method.body;
  const operations = [];
  const matches = (pattern) => pattern.test(body);
  const dynamicFacts = dynamicProducerFacts(body, durationConstants);
  const controlWaitFacts = fixedControlWaitFacts(body, durationConstants,
    numericConstants, localCallableNames, sourceText === null ? null : {
      bodyStart: method.openBrace + 1,
      path: method.path,
      text: sourceText,
    });
  if (method.scopeKind === 'HELPER'
      && /^(?:await|waitFor|waitUntil|connectWithRetry)/u
        .test(method.scopeName)
      && /(?:\.\s*(?:await|join|get|waitFor)\s*\(|\b(?:nanoTime|currentTimeMillis)\s*\(|\bdeadline\b|\btimeout\b)/iu
        .test(body)
      && controlWaitFacts.count === 0) {
    const deadlineFacts = deadlineBudgetFacts(body, durationConstants,
      numericConstants);
    const spinWait = /\b(?:Thread\s*\.\s*)?onSpinWait\s*\(\s*\)/u
      .exec(body);
    const resolvedPoll = deadlineFacts.length === 1
      && spinWait !== null
      && deadlineControlsPollingSite(body, deadlineFacts[0], spinWait.index);
    const deadlineMillis = resolvedPoll ? deadlineFacts[0].millis : null;
    controlWaitFacts.count += 1;
    if (!resolvedPoll) controlWaitFacts.unresolvedCount += 1;
    else controlWaitFacts.millis += deadlineMillis;
    const deadlineIndex = resolvedPoll ? deadlineFacts[0].index : 0;
    const absoluteIndex = method.openBrace + 1 + deadlineIndex;
    const line = resolvedPoll ? sourceText.slice(0, absoluteIndex)
      .split(/\r\n|\n|\r/u).length : method.line;
    controlWaitFacts.sites.push({
      composedMillis: deadlineMillis,
      line,
      lineSha256: resolvedPoll
        ? lineSha256(splitLines(sourceText)[line - 1]) : method.lineSha256,
      method: resolvedPoll ? 'deadlinePoll' : method.scopeName,
      occurrenceCount: 1,
      path: method.path,
      perOccurrenceMillis: deadlineMillis,
      unresolved: !resolvedPoll,
    });
  }
  const policyReferenceNames = [...new Set([...body.matchAll(
    /\b([A-Za-z_$][\w$]*Policy)\s*\(/gu)].map((match) => match[1]))]
    .sort(asciiCompare);
  const inlineCleanupDurations = [...body.matchAll(
    /\.\s*afterCompleteShutdown\s*\(\s*Duration\s*\.\s*of(Seconds|Millis)\s*\(\s*(\d+)\s*\)/gu)]
    .map((match) => Number.parseInt(match[2], 10)
      * (match[1] === 'Seconds' ? 1_000 : 1));
  const literalPhasePolicies = [];
  if (/\bLifecyclePolicy\s*\.\s*fromDefaults\s*\(\s*\)/u.test(body)) {
    literalPhasePolicies.push({
      forcedShutdownMillis: DEFAULT_PHASE_POLICY.forcedShutdownMillis,
      gracefulShutdownMillis: DEFAULT_PHASE_POLICY.gracefulShutdownMillis,
      startupCancellationMillis:
        DEFAULT_PHASE_POLICY.startupCancellationMillis,
      startupMillis: DEFAULT_PHASE_POLICY.startupMillis,
    });
  }
  let unresolvedPolicyBuilderCount = 0;
  let unresolvedPolicyInstallationCount = 0;
  for (const builder of body.matchAll(
    /\bLifecyclePolicy\s*\.\s*builder\s*\(\s*\)([\s\S]{0,1600}?)\.\s*build\s*\(\s*\)/gu)) {
    const policy = literalPolicyFromBuilderChain(builder[1],
      durationConstants);
    if (policy === null) unresolvedPolicyBuilderCount += 1;
    else literalPhasePolicies.push(policy);
  }
  const internalPolicies = scanInternalPolicies(body, durationConstants);
  literalPhasePolicies.push(...internalPolicies.policies);
  unresolvedPolicyBuilderCount += internalPolicies.unresolvedCount;
  const referencedFieldNames = new Set();
  const referencedFieldPolicyMap = new Map();
  for (const name of fieldPolicies.keys()) {
    const escaped = name.replace(/[.*+?^${}()|[\]\\]/gu, '\\$&');
    const referencePattern = new RegExp(
      `(?:(?<![\\w$])([A-Za-z_$][\\w$]*)\\s*\\.\\s*)?\\b${escaped}\\b`,
      'gu');
    for (const reference of body.matchAll(referencePattern)) {
      const policy = resolveFieldPolicy(fieldPolicies, name, method,
        reference[1] ?? null);
      if (policy === undefined) continue;
      referencedFieldNames.add(name);
      referencedFieldPolicyMap.set(
        `${policy.path}:${policy.name}:${policy.spanSha256}`, policy);
    }
  }
  const referencedFieldPolicies = [...referencedFieldPolicyMap.values()];
  literalPhasePolicies.push(...referencedFieldPolicies
    .map((row) => row.phasePolicy).filter((policy) => policy !== null));
  unresolvedPolicyBuilderCount += referencedFieldPolicies.filter((row) =>
    row.phasePolicy === null || row.unresolved === true).length;
  let policyInstallationCount = 0;
  for (const match of body.matchAll(
    /\.\s*(?:internalLifecyclePolicy|lifecyclePolicy)\s*\(/gu)) {
    const openParenthesis = match.index + match[0].lastIndexOf('(');
    const end = matchingParenthesisEnd(body, openParenthesis);
    if (end === null) {
      unresolvedPolicyInstallationCount += 1;
      continue;
    }
    const argument = body.slice(openParenthesis + 1, end - 1).trim();
    // A zero-argument lifecyclePolicy() call is a diagnostics/accessor read,
    // not a builder policy installation.
    if (argument.length === 0) continue;
    policyInstallationCount += 1;
    const fieldReference = argument.match(
      /^(?:([A-Za-z_$][\w$]*)\s*\.\s*)?([A-Za-z_$][\w$]*)$/u);
    const field = fieldReference === null ? undefined
      : resolveFieldPolicy(fieldPolicies, fieldReference[2], method,
        fieldReference[1] ?? null);
    const inlinePublic = /^LifecyclePolicy\s*\.\s*(?:builder|fromDefaults)\s*\(/u
      .test(argument);
    const inlineInternal = /^new\s+InternalLifecyclePolicy\s*\(/u
      .test(argument);
    if (field?.phasePolicy === null
        || (!inlinePublic && !inlineInternal
          && field?.phasePolicy === undefined))
      unresolvedPolicyInstallationCount += 1;
  }
  if (policyInstallationCount > 0
      || matches(/(?:\bLifecyclePolicy\s*\.\s*(?:builder|fromDefaults)\s*\(|\b(?:shutdownPolicy|cancellationPolicy|handlerPolicy|shortShutdownPolicy|managedLockProbeShutdownPolicy)\s*\()/u))
    operations.push('CONFIGURE_POLICY');
  if (matches(/(?:\bSokletApplicationOptions\b|\.\s*afterCompleteShutdown\s*\()/u))
    operations.push('CONFIGURE_RUNNER');
  if (matches(/\bHttpServer\s*\.\s*(?:withPort|builder)\s*\(/u))
    operations.push('CONSTRUCT_HTTP_SERVER');
  if (matches(/\bMcpServer\s*\.\s*(?:withPort|builder)\s*\(/u))
    operations.push('CONSTRUCT_MCP_SERVER');
  if (matches(/\bSoklet\s*\.\s*fromConfig\s*\(/u))
    operations.push('CONSTRUCT_SOKLET');
  if (matches(/\bSseServer\s*\.\s*(?:withPort|builder)\s*\(/u))
    operations.push('CONSTRUCT_SSE_SERVER');
  if (matches(/(?:\bnew\s+[A-Za-z_$][\w$]*LifecycleAdapter\s*\(|\bnew\s+SokletDirectLifecycle\s*\(|\bInternalLifecycleCoordinator\b|\bTransportRuntime\b|\bMcpHttpServerRuntime\b)/u))
    operations.push('CONSTRUCT_TEMPORARY_RUNTIME');
  if (matches(/\bopenSimulationSession\s*\(/u))
    operations.push('OPEN_SIMULATION_SESSION');
  if (matches(/(?:\bSokletApplication\s*\.\s*run\s*\(|\bstartRunner\s*\()/u))
    operations.push('RUN_APPLICATION');
  if (matches(/(?:\bSokletSimulator\s*\.\s*run\s*\(|\brunConcurrentScope\s*\()/u))
    operations.push('RUN_SIMULATOR');
  const namedReceivers = method.receiverNames
    .map((name) => name.replace(/[.*+?^${}()|[\]\\]/gu, '\\$&'));
  const lifecycleReceiver = namedReceivers.length === 0 ? '(?!)'
    : String.raw`(?:${namedReceivers.join('|')})(?:\s*\.\s*[A-Za-z_$][\w$]*\s*\(\s*\))?`;
  if (matches(new RegExp(`\\b${lifecycleReceiver}\\s*\\.\\s*(?:start|beginStart|markReady|runExternallyCoordinatedStart|commitExternallyCoordinatedGeneration)\\s*\\(`, 'iu')))
    operations.push('START');
  else if (matches(new RegExp(`\\b${lifecycleReceiver}\\s*::\\s*start\\b`, 'iu')))
    operations.push('START');
  if (matches(new RegExp(`\\b${lifecycleReceiver}\\s*::\\s*openMcpScope\\b`, 'iu')))
    operations.push('START');
  if (matches(new RegExp(`\\b${lifecycleReceiver}\\s*\\.\\s*(?:shutdown|stop|requestStop|recordExternallyCoordinatedShutdownIntent|sealScope)\\s*\\(`, 'iu'))
      || (matches(/\bnew\s+InternalLifecycleCoordinator\s*\(/u)
        && matches(/\.\s*shutdown\s*\(/u)))
    operations.push('SHUTDOWN_OR_STOP');
  else if (matches(new RegExp(`\\b${lifecycleReceiver}\\s*::\\s*(?:shutdown|stop)\\b`, 'iu')))
    operations.push('SHUTDOWN_OR_STOP');
  if (matches(new RegExp(`\\b${lifecycleReceiver}\\s*\\.\\s*(?:awaitMcpScopeTermination|awaitShutdown|awaitStop|awaitTermination|whenTerminated)\\s*\\(`, 'iu')))
    operations.push('AWAIT_TERMINATION');
  if (matches(/try\s*\(\s*Soklet\b/u)
      || matches(/try\s*\([^)]*\b[A-Za-z_$][\w$]*Harness\s+[A-Za-z_$][\w$]*/u)
      || matches(new RegExp(`try\\s*\\(\\s*${lifecycleReceiver}\\s*(?:;|\\))`, 'iu'))
      || matches(new RegExp(`\\b${lifecycleReceiver}\\s*\\.\\s*close\\s*\\(`, 'iu')))
    operations.push('CLOSE');
  const ambiguousReceiverSites = new Map();
  for (const match of body.matchAll(
    /\(\(\s*(?:Soklet|HttpServer|SseServer|McpServer|TransportRuntime|InternalLifecycleCoordinator|SokletDirectLifecycle)\s*\)\s*[^()]+\)\s*\.\s*(start|beginStart|shutdown|stop|close)\s*\(/gu))
    ambiguousReceiverSites.set(`${match.index}:${match[1]}`, match[1]);
  for (const match of body.matchAll(
    /\bidentity\s*\(\s*([A-Za-z_$][\w$]*)\s*\)\s*\.\s*(start|beginStart|shutdown|stop|close)\s*\(/gu)) {
    if (method.receiverNames.includes(match[1]))
      ambiguousReceiverSites.set(`${match.index}:${match[2]}`, match[2]);
  }
  for (const match of body.matchAll(
    /\b([A-Za-z_$][\w$]*)\s*\[[^\]]+\]\s*\.\s*(start|beginStart|shutdown|stop|close)\s*\(/gu)) {
    const escaped = match[1].replace(/[.*+?^${}()|[\]\\]/gu, '\\$&');
    if (new RegExp(`\\b(?:Soklet|HttpServer|SseServer|McpServer|TransportRuntime|InternalLifecycleCoordinator|SokletDirectLifecycle)\\s*\\[\\s*\\]\\s*${escaped}\\b`, 'u').test(body))
      ambiguousReceiverSites.set(`${match.index}:${match[2]}`, match[2]);
  }
  const lifecycleConstructionOrSignal = operations.some((operation) =>
    operation.startsWith('CONSTRUCT_') || operation === 'CONFIGURE_POLICY'
      || operation === 'CONFIGURE_RUNNER' || operation === 'RUN_APPLICATION'
      || operation === 'RUN_SIMULATOR' || operation === 'OPEN_SIMULATION_SESSION');
  const unresolvedLifecycleReceiverCount = lifecycleConstructionOrSignal
    ? ambiguousReceiverSites.size : 0;
  if ([...ambiguousReceiverSites.values()].some((name) =>
    /^(?:start|beginStart)$/u.test(name))) operations.push('START');
  if ([...ambiguousReceiverSites.values()].some((name) =>
    /^(?:shutdown|stop)$/u.test(name))) operations.push('SHUTDOWN_OR_STOP');
  if ([...ambiguousReceiverSites.values()].includes('close'))
    operations.push('CLOSE');
  const orderedOperations = LIFECYCLE_OPERATIONS.filter((operation) =>
    operations.includes(operation));
  const constructionSiteCount = [...body.matchAll(
    /\bSoklet\s*\.\s*fromConfig\s*\(/gu)].length;
  const generationPattern = /(?:\bSokletApplication\s*\.\s*run\s*\(|\bSokletSimulator\s*\.\s*run\s*\(|\bopenSimulationSession\s*\()/gu;
  const hasExecution = orderedOperations.some((operation) => [
    'OPEN_SIMULATION_SESSION', 'RUN_APPLICATION', 'RUN_SIMULATOR', 'START',
    'SHUTDOWN_OR_STOP', 'AWAIT_TERMINATION', 'CLOSE',
  ].includes(operation));
  let unresolvedLifecycleRepetitionCount = 0;
  let syntacticGenerations = 0;
  for (const match of body.matchAll(generationPattern)) {
    const repetition = repetitionContext(body, match.index);
    syntacticGenerations += repetition.multiplier;
    unresolvedLifecycleRepetitionCount += repetition.unresolved;
  }
  let applicationRunSiteCount = 0;
  for (const match of body.matchAll(
    /\bSokletApplication\s*\.\s*run\s*\(/gu)) {
    const repetition = repetitionContext(body, match.index);
    applicationRunSiteCount += repetition.multiplier;
    unresolvedLifecycleRepetitionCount += repetition.unresolved;
  }
  let lifecycleStartSiteCount = 0;
  for (const match of body.matchAll(new RegExp(
    `\\b${lifecycleReceiver}\\s*(?:\\.\\s*(?:start|beginStart|runExternallyCoordinatedStart)\\s*\\(|::\\s*(?:start|openMcpScope)\\b)`,
    'giu'))) {
    const repetition = repetitionContext(body, match.index);
    lifecycleStartSiteCount += repetition.multiplier;
    unresolvedLifecycleRepetitionCount += repetition.unresolved;
  }
  let componentGenerationSiteCount = 0;
  for (const match of body.matchAll(
    /\.\s*(?:beginStart|runExternallyCoordinatedStart|openMcpScope)\s*\(|::\s*openMcpScope\b/gu)) {
    const repetition = repetitionContext(body, match.index);
    componentGenerationSiteCount += repetition.multiplier;
    unresolvedLifecycleRepetitionCount += repetition.unresolved;
  }
  return {
    cleanupConfigured: /\.\s*afterCompleteShutdown\s*\(/u.test(body),
    applicationRunSiteCount,
    componentGenerationSiteCount,
    constructionSiteCount,
    dynamicNodeCount: dynamicFactoryNodeCount(method),
    dynamicNodeGuardMillis: dynamicFacts.dynamicNodeGuardMillis,
    dynamicNodeSiteCount: dynamicFacts.dynamicNodeSiteCount,
    fixedControlWaitCount: controlWaitFacts.count,
    fixedControlWaitMillis: controlWaitFacts.millis,
    fixedControlWaitSites: controlWaitFacts.sites,
    freshGenerationSites: syntacticGenerations,
    hasExecution,
    hasInlinePolicy: /\.\s*(?:startupTimeout|noStartupTimeout|startupCancellationTimeout|gracefulShutdownDuration|forcedShutdownDuration)\s*\(/u
      .test(body),
    hasInlineNoStartupTimeout: /\bnoStartupTimeout\s*\(/u.test(body),
    lifecycleStartSiteCount,
    inlineCleanupMillis: inlineCleanupDurations.length === 0 ? null
      : Math.max(...inlineCleanupDurations),
    fieldPolicyProofs: referencedFieldPolicies,
    literalPhasePolicies,
    observedOperations: orderedOperations,
    policyReferenceNames,
    terminalReportExpected: orderedOperations.includes('RUN_APPLICATION'),
    unresolvedLifecycleRepetitionCount,
    unresolvedLifecycleReceiverCount,
    unresolvedFixedControlWaitCount: controlWaitFacts.unresolvedCount,
    unresolvedPolicyBuilderCount,
    unresolvedPolicyInstallationCount,
    unwrappedDynamicNodeCount: dynamicFacts.unwrappedDynamicNodeCount,
  };
}

function localHelperCalls(method, callableNames) {
  const calls = new Map();
  const arities = new Map();
  const unresolvedNames = new Set();
  for (const name of callableNames) {
    const escaped = name.replace(/[.*+?^${}()|[\]\\]/gu, '\\$&');
    const patterns = [
      new RegExp(`(?<![\\w$.])${escaped}\\s*\\(`, 'gu'),
      new RegExp(`\\bthis\\s*\\.\\s*${escaped}\\s*\\(`, 'gu'),
      new RegExp(`\\bthis\\s*::\\s*${escaped}\\b`, 'gu'),
    ];
    let count = 0;
    for (const pattern of patterns) {
      for (const match of method.body.matchAll(pattern)) {
        const repetition = repetitionContext(method.body, match.index);
        count += repetition.multiplier;
        if (repetition.unresolved > 0) unresolvedNames.add(name);
        const relativeOpen = match[0].lastIndexOf('(');
        if (relativeOpen >= 0) {
          const openParenthesis = match.index + relativeOpen;
          const end = matchingParenthesisEnd(method.body, openParenthesis);
          if (end !== null) {
            const argumentsText = method.body.slice(openParenthesis + 1,
              end - 1).trim();
            if (!arities.has(name)) arities.set(name, new Set());
            arities.get(name).add(argumentsText.length === 0 ? 0
              : splitTopLevelArguments(argumentsText).length);
          }
        }
      }
    }
    if (count > 0) calls.set(name, count);
  }
  return { arities, calls, unresolvedNames };
}

function mergeFacts(target, source, multiplier = 1) {
  target.applicationRunSiteCount +=
    source.applicationRunSiteCount * multiplier;
  target.cleanupConfigured ||= source.cleanupConfigured;
  target.componentGenerationSiteCount +=
    source.componentGenerationSiteCount * multiplier;
  target.constructionSiteCount += source.constructionSiteCount * multiplier;
  target.freshGenerationSites += source.freshGenerationSites * multiplier;
  target.fixedControlWaitCount += source.fixedControlWaitCount * multiplier;
  target.fixedControlWaitMillis += source.fixedControlWaitMillis * multiplier;
  target.fixedControlWaitSites = [...target.fixedControlWaitSites,
    ...source.fixedControlWaitSites.map((site) => ({
      ...site,
      composedMillis: site.composedMillis === null ? null
        : site.composedMillis * multiplier,
      occurrenceCount: site.occurrenceCount * multiplier,
    }))].sort((left, right) => asciiCompare(left.path, right.path)
      || left.line - right.line || asciiCompare(left.method, right.method));
  target.hasExecution ||= source.hasExecution;
  target.hasInlineNoStartupTimeout ||= source.hasInlineNoStartupTimeout;
  target.lifecycleStartSiteCount += source.lifecycleStartSiteCount * multiplier;
  target.inlineCleanupMillis = Math.max(target.inlineCleanupMillis ?? 0,
    source.inlineCleanupMillis ?? 0) || null;
  target.fieldPolicyProofs = [...new Map([
    ...target.fieldPolicyProofs, ...source.fieldPolicyProofs,
  ].map((proof) => [`${proof.path}:${proof.name}:${proof.spanSha256}`,
    proof])).values()].sort((left, right) => asciiCompare(left.path,
    right.path) || asciiCompare(left.name, right.name));
  target.observedOperations = [...new Set([
    ...target.observedOperations, ...source.observedOperations,
  ])].sort((left, right) => LIFECYCLE_OPERATIONS.indexOf(left)
    - LIFECYCLE_OPERATIONS.indexOf(right));
  target.policyReferenceNames = [...new Set([
    ...target.policyReferenceNames, ...source.policyReferenceNames,
  ])].sort(asciiCompare);
  target.literalPhasePolicies = [...new Map([
    ...target.literalPhasePolicies, ...source.literalPhasePolicies,
  ].map((policy) => [JSON.stringify(policy), policy])).values()]
    .sort((left, right) => asciiCompare(JSON.stringify(left),
      JSON.stringify(right)));
  target.terminalReportExpected ||= source.terminalReportExpected;
  target.unresolvedLifecycleRepetitionCount +=
    source.unresolvedLifecycleRepetitionCount * multiplier;
  target.unresolvedLifecycleReceiverCount +=
    source.unresolvedLifecycleReceiverCount * multiplier;
  target.unresolvedFixedControlWaitCount +=
    source.unresolvedFixedControlWaitCount * multiplier;
  target.unresolvedPolicyBuilderCount +=
    source.unresolvedPolicyBuilderCount * multiplier;
  target.unresolvedPolicyInstallationCount +=
    source.unresolvedPolicyInstallationCount * multiplier;
  return target;
}

function buildLifecycleScopeEvidence(texts) {
  const observations = [];
  const orphanHelpers = [];
  for (const [path, text] of texts) {
    if (!path.startsWith(JUNIT_ROOT) || !path.endsWith('.java')) continue;
    const methods = javaMethods(path, text);
    verifyNoUnscopedLifecycleExecution(path, text, methods);
    const fieldPolicies = javaFieldPolicies(path, text);
    const fileMasked = maskJavaSource(text);
    const durationConstants = javaDurationConstants(fileMasked);
    const numericConstants = javaNumericConstants(fileMasked);
    const operationsField = /\bOPERATIONS\s*=\s*List\s*\.\s*of\s*\(/u
      .exec(fileMasked);
    let fileOperationCaseCount = 0;
    if (operationsField !== null) {
      const openParenthesis = operationsField.index
        + operationsField[0].lastIndexOf('(');
      const end = matchingParenthesisEnd(fileMasked, openParenthesis);
      if (end !== null) {
        fileOperationCaseCount = [...fileMasked.slice(openParenthesis, end)
          .matchAll(/\bnew\s+OperationCase\s*\(/gu)].length;
      }
    }
    const byName = new Map();
    for (const [index, method] of methods.entries()) {
      if (!byName.has(method.scopeName)) byName.set(method.scopeName, []);
      byName.get(method.scopeName).push(index);
    }
    const direct = methods.map((method) =>
      directLifecycleFacts(method, fieldPolicies, durationConstants,
        numericConstants, new Set(byName.keys()), text));
    const helperAnalyses = methods.map((method) =>
      localHelperCalls(method, byName.keys()));
    const helperCalls = helperAnalyses.map((analysis) => analysis.calls);
    const dynamicFactsForFactory = (index) => {
      const root = direct[index];
      let siteCount = root.dynamicNodeSiteCount;
      let unwrappedCount = root.unwrappedDynamicNodeCount;
      const guards = root.dynamicNodeGuardMillis === null
        ? [] : [root.dynamicNodeGuardMillis];
      if (methods[index].testFactory) {
        for (const match of methods[index].body.matchAll(
          /\breturn\s+([A-Za-z_$][\w$]*)\s*\(/gu)) {
          const name = match[1];
          const openParenthesis = match.index + match[0].lastIndexOf('(');
          const end = matchingParenthesisEnd(methods[index].body,
            openParenthesis);
          if (end === null) continue;
          const argumentsText = methods[index].body.slice(
            openParenthesis + 1, end - 1).trim();
          const arity = argumentsText.length === 0 ? 0
            : splitTopLevelArguments(argumentsText).length;
          const candidates = (byName.get(name) ?? []).filter((candidate) =>
            methods[candidate].parameterCount === arity);
          for (const candidate of candidates) {
            const facts = direct[candidate];
            if (facts.dynamicNodeSiteCount === 0) continue;
            siteCount += facts.dynamicNodeSiteCount;
            unwrappedCount += facts.unwrappedDynamicNodeCount;
            if (facts.dynamicNodeGuardMillis !== null)
              guards.push(facts.dynamicNodeGuardMillis);
          }
        }
      }
      return {
        dynamicNodeGuardMillis: siteCount > 0 && unwrappedCount === 0
          && guards.length > 0 ? Math.min(...guards) : null,
        dynamicNodeSiteCount: siteCount,
        unwrappedDynamicNodeCount: unwrappedCount,
      };
    };
    const recursiveHelpers = new Set();
    const reachesSelf = (start, current, visited) => {
      for (const name of helperCalls[current].keys()) {
        const arities = helperAnalyses[current].arities.get(name);
        for (const candidate of byName.get(name).filter((index) =>
          arities === undefined || arities.has(methods[index].parameterCount))) {
          if (candidate === start) return true;
          if (visited.has(candidate)) continue;
          if (reachesSelf(start, candidate,
            new Set(visited).add(candidate))) return true;
        }
      }
      return false;
    };
    for (const index of methods.keys()) {
      if (reachesSelf(index, index, new Set([index])))
        recursiveHelpers.add(index);
    }
    const cache = new Map();
    const summarize = (index, stack = new Set()) => {
      if (cache.has(index)) return cache.get(index);
      const summary = structuredClone(direct[index]);
      if (stack.has(index)) return summary;
      const nextStack = new Set(stack).add(index);
      for (const [name, callCount] of helperCalls[index]) {
        const arities = helperAnalyses[index].arities.get(name);
        const candidates = byName.get(name)
          .filter((candidate) => !nextStack.has(candidate)
            && (arities === undefined
              || arities.has(methods[candidate].parameterCount)))
          .map((candidate) => summarize(candidate, nextStack));
        if (candidates.length === 0) continue;
        const mergedCandidate = structuredClone(candidates[0]);
        for (const candidate of candidates.slice(1)) {
          mergedCandidate.constructionSiteCount = Math.max(
            mergedCandidate.constructionSiteCount,
            candidate.constructionSiteCount);
          mergedCandidate.componentGenerationSiteCount = Math.max(
            mergedCandidate.componentGenerationSiteCount,
            candidate.componentGenerationSiteCount);
          mergedCandidate.freshGenerationSites = Math.max(
            mergedCandidate.freshGenerationSites,
            candidate.freshGenerationSites);
          mergedCandidate.lifecycleStartSiteCount = Math.max(
            mergedCandidate.lifecycleStartSiteCount,
            candidate.lifecycleStartSiteCount);
          mergeFacts(mergedCandidate, {
            ...candidate,
            constructionSiteCount: 0,
            componentGenerationSiteCount: 0,
            fieldPolicyProofs: [],
            freshGenerationSites: 0,
            fixedControlWaitCount: 0,
            fixedControlWaitMillis: 0,
            fixedControlWaitSites: [],
            lifecycleStartSiteCount: 0,
            unresolvedPolicyBuilderCount: 0,
          });
        }
        mergeFacts(summary, mergedCandidate, callCount);
        if (helperAnalyses[index].unresolvedNames.has(name)) {
          if (mergedCandidate.observedOperations.length > 0)
            summary.unresolvedLifecycleRepetitionCount += 1;
          if (mergedCandidate.fixedControlWaitCount > 0) {
            summary.unresolvedFixedControlWaitCount += 1;
            summary.fixedControlWaitSites.push({
              composedMillis: null,
              line: methods[index].line,
              lineSha256: methods[index].lineSha256,
              method: `repeatedHelper:${name}`,
              occurrenceCount: 1,
              path: methods[index].path,
              perOccurrenceMillis: null,
              unresolved: true,
            });
          }
        }
      }
      if (recursiveHelpers.has(index)
          && summary.observedOperations.length > 0)
        summary.unresolvedLifecycleRepetitionCount = Math.max(1,
          summary.unresolvedLifecycleRepetitionCount);
      cache.set(index, summary);
      return summary;
    };
    const factoryGenerationCache = new Map();
    const summarizeFactoryGeneration = (index, stack = new Set()) => {
      if (factoryGenerationCache.has(index))
        return factoryGenerationCache.get(index);
      const method = methods[index];
      const scenarioRunPattern =
        /\.\s*[A-Za-z_$][\w$]*\s*\(\s*\)\s*\.\s*run\s*\(/u;
      const hasOnlyDeferredScenarioRuns = (candidate) =>
        /\bDynamicTest\s*\.\s*dynamicTest\s*\(/u.test(candidate.body)
          && scenarioRunPattern.test(candidate.body)
          && !scenarioRunPattern.test(maskDynamicNodeExecutables(
            candidate.body));
      const reviewedNamedScenarioBodies = method.testFactory
        && (hasOnlyDeferredScenarioRuns(method)
          || [...method.body.matchAll(
            /\breturn\s+([A-Za-z_$][\w$]*)\s*\(/gu)]
            .some((returned) => (byName.get(returned[1]) ?? [])
              .some((candidate) =>
                hasOnlyDeferredScenarioRuns(methods[candidate]))));
      const generationMethod = {
        ...method,
        body: maskDynamicNodeExecutables(method.body,
          { reviewedNamedScenarioBodies }),
      };
      const summary = directLifecycleFacts(generationMethod, fieldPolicies,
        durationConstants, numericConstants, new Set(byName.keys()), text);
      if (stack.has(index)) return summary;
      const nextStack = new Set(stack).add(index);
      const generationCalls = localHelperCalls(generationMethod,
        byName.keys());
      for (const [name, callCount] of generationCalls.calls) {
        const arities = generationCalls.arities.get(name);
        const candidates = byName.get(name).filter((candidate) =>
          !nextStack.has(candidate) && (arities === undefined
            || arities.has(methods[candidate].parameterCount)));
        for (const candidate of candidates)
          mergeFacts(summary, summarizeFactoryGeneration(candidate,
            nextStack), callCount);
      }
      factoryGenerationCache.set(index, summary);
      return summary;
    };
    const rootIndices = methods.map((method, index) => ({ index, method }))
      .filter(({ method }) => ['TEST', 'SETUP_TEARDOWN']
        .includes(method.scopeKind))
      .map(({ index }) => index);
    const reachableHelpers = new Set();
    const helperEvidenceFor = (rootIndex) => {
      const evidence = new Map();
      const visit = (index, multiplier, stack) => {
        if (stack.has(index)) return;
        const nextStack = new Set(stack).add(index);
        for (const [name, callCount] of helperCalls[index]) {
          const arities = helperAnalyses[index].arities.get(name);
          for (const candidate of byName.get(name)
            .filter((candidate) => !nextStack.has(candidate)
              && (arities === undefined
                || arities.has(methods[candidate].parameterCount))
              && summarize(candidate).observedOperations.length > 0)) {
            if (methods[candidate].scopeKind === 'TEST') continue;
            reachableHelpers.add(candidate);
            const method = methods[candidate];
            const key = `${method.path}:${method.line}:${method.scopeName}`;
            const effectiveCount = multiplier * callCount;
            const prior = evidence.get(key);
            evidence.set(key, {
              callCount: (prior?.callCount ?? 0) + effectiveCount,
              line: method.line,
              lineSha256: method.lineSha256,
              path: method.path,
              scopeKind: method.scopeKind,
              scopeName: method.scopeName,
              scopeSha256: method.scopeSha256,
            });
            visit(candidate, effectiveCount, nextStack);
          }
        }
      };
      visit(rootIndex, 1, new Set());
      return [...evidence.values()].sort((left, right) =>
        asciiCompare(left.path, right.path) || left.line - right.line
        || asciiCompare(left.scopeName, right.scopeName));
    };
    for (const index of rootIndices) {
      const method = methods[index];
      const facts = summarize(index);
      if (facts.observedOperations.length === 0) continue;
      const factoryDynamicFacts = dynamicFactsForFactory(index);
      const factoryGenerationFacts = method.testFactory
        ? summarizeFactoryGeneration(index) : null;
      const propagatedHelperCalls = [...helperCalls[index].entries()]
        .filter(([name]) => byName.get(name).some((candidate) =>
          summarize(candidate).observedOperations.length > 0))
        .map(([name, callCount]) => ({ callCount, name }))
        .sort((left, right) => asciiCompare(left.name, right.name));
      observations.push({
        applicationRunSiteCount: facts.applicationRunSiteCount,
        cleanupConfigured: facts.cleanupConfigured,
        componentGenerationSiteCount: facts.componentGenerationSiteCount,
        constructionSiteCount: facts.constructionSiteCount,
        dynamicNodeCount: method.testFactory
          && /\bOPERATIONS\b/u.test(method.body)
          && fileOperationCaseCount > 0
          ? fileOperationCaseCount : direct[index].dynamicNodeCount,
        dynamicNodeGuardMillis: factoryDynamicFacts.dynamicNodeGuardMillis,
        dynamicNodeSiteCount: factoryDynamicFacts.dynamicNodeSiteCount,
        disabled: method.disabled,
        effectiveOuterTimeoutMillis: factoryDynamicFacts.dynamicNodeGuardMillis
          ?? method.effectiveOuterTimeoutMillis,
        factoryGenerationHasExecution:
          factoryGenerationFacts?.hasExecution ?? false,
        factoryGenerationFixedControlWaitMillis:
          factoryGenerationFacts?.fixedControlWaitMillis ?? 0,
        factoryGenerationUnresolvedFixedControlWaitCount:
          factoryGenerationFacts?.unresolvedFixedControlWaitCount ?? 0,
        factoryGenerationOuterTimeoutMillis: method.testFactory
          ? method.effectiveOuterTimeoutMillis : null,
        factoryGenerationOuterTimeoutScope: method.testFactory
          ? method.outerTimeoutScope : null,
        fileSha256: sha256(Buffer.from(text, 'utf8')),
        fixedControlWaitCount: facts.fixedControlWaitCount,
        fixedControlWaitMillis: facts.fixedControlWaitMillis,
        fixedControlWaitSites: facts.fixedControlWaitSites,
        generationSiteCount: method.testFactory ? (facts.hasExecution ? 1 : 0)
          : Math.max(facts.freshGenerationSites,
            facts.componentGenerationSiteCount,
            Math.min(facts.constructionSiteCount,
              facts.lifecycleStartSiteCount),
            facts.hasExecution ? 1 : 0),
        hasExecution: facts.hasExecution && !method.disabled,
        hasInlineCleanup: direct[index].cleanupConfigured,
        hasInlineNoStartupTimeout: direct[index].hasInlineNoStartupTimeout,
        hasInlinePolicy: direct[index].hasInlinePolicy,
        hasLocalPolicy: facts.observedOperations.includes('CONFIGURE_POLICY'),
        hasNoStartupTimeout: facts.hasInlineNoStartupTimeout
          || facts.literalPhasePolicies.some((policy) =>
            policy.startupMillis === null),
        id: stableId('SCOPE', `${method.path}:${method.line}:${method.scopeName}`),
        inlineCleanupMillis: facts.inlineCleanupMillis,
        fieldPolicyProofs: facts.fieldPolicyProofs,
        lifecycleStartSiteCount: facts.lifecycleStartSiteCount,
        literalPhasePolicies: facts.literalPhasePolicies,
        line: method.line,
        lineSha256: method.lineSha256,
        observedOperations: facts.observedOperations,
        outerTimeoutScope: factoryDynamicFacts.dynamicNodeGuardMillis === null
          ? method.outerTimeoutScope : 'DYNAMIC_NODE',
        path: method.path,
        policyReferenceNames: facts.policyReferenceNames,
        propagatedHelperEvidence: helperEvidenceFor(index),
        propagatedHelperCalls,
        scopeKind: method.scopeKind,
        scopeName: method.scopeName,
        scopeSha256: method.scopeSha256,
        terminalReportExpected: facts.terminalReportExpected,
        testFactory: method.testFactory,
        unresolvedPolicyBuilderCount: facts.unresolvedPolicyBuilderCount,
        unresolvedPolicyInstallationCount:
          facts.unresolvedPolicyInstallationCount,
        unresolvedLifecycleRepetitionCount:
          facts.unresolvedLifecycleRepetitionCount,
        unresolvedLifecycleReceiverCount:
          facts.unresolvedLifecycleReceiverCount,
        unresolvedFixedControlWaitCount:
          facts.unresolvedFixedControlWaitCount,
        unwrappedDynamicNodeCount:
          factoryDynamicFacts.unwrappedDynamicNodeCount,
      });
    }
    const orphanExecutionHelpers = methods.map((method, index) => ({
      facts: summarize(index), index, method,
    })).filter(({ facts, index, method }) => method.scopeKind === 'HELPER'
      && facts.hasExecution && !reachableHelpers.has(index));
    for (const orphan of orphanExecutionHelpers) {
      orphanHelpers.push({
        fileSha256: sha256(Buffer.from(text, 'utf8')),
        id: stableId('HELPER', `${orphan.method.path}:${orphan.method.line}:${orphan.method.scopeName}`),
        line: orphan.method.line,
        lineSha256: orphan.method.lineSha256,
        observedOperations: orphan.facts.observedOperations,
        path: orphan.method.path,
        scopeKind: orphan.method.scopeKind,
        scopeName: orphan.method.scopeName,
        scopeSha256: orphan.method.scopeSha256,
      });
    }
  }
  return {
    observations: observations.sort((left, right) =>
      asciiCompare(left.path, right.path) || left.line - right.line
      || asciiCompare(left.scopeName, right.scopeName)),
    orphanHelpers: orphanHelpers.sort((left, right) =>
      asciiCompare(left.path, right.path) || left.line - right.line
      || asciiCompare(left.scopeName, right.scopeName)),
  };
}

export function buildLifecycleScopeObservations(texts) {
  return buildLifecycleScopeEvidence(texts).observations;
}

function scopePathBounds(policy) {
  exactFields(policy, [
    'controlledStartupMillis', 'forcedShutdownMillis',
    'gracefulShutdownMillis', 'mode', 'startupCancellationMillis',
    'startupMillis',
  ], 'lifecycle scope phasePolicy');
  if (!['INHERITED_DEFAULT', 'LOCAL_FINITE_REVIEWED',
    'SOURCE_CONFIGURED_FINITE_WITH_PROOF',
    'SOURCE_CONFIGURED_STANDARD_GUARD',
    'UNBOUNDED_STARTUP_CONSTRUCTION_ONLY',
    'UNBOUNDED_STARTUP_WITH_CONTROLLED_PROOF'].includes(policy.mode))
    fail(`Unknown lifecycle scope phasePolicy mode ${policy.mode}.`);
  for (const field of ['forcedShutdownMillis', 'gracefulShutdownMillis',
    'startupCancellationMillis']) {
    if (!Number.isSafeInteger(policy[field]) || policy[field] < 0)
      fail(`lifecycle scope phasePolicy ${field} must be a nonnegative integer.`);
  }
  for (const field of ['controlledStartupMillis', 'startupMillis']) {
    if (policy[field] !== null
        && (!Number.isSafeInteger(policy[field]) || policy[field] < 0))
      fail(`lifecycle scope phasePolicy ${field} must be null or a nonnegative integer.`);
  }
  const startup = policy.startupMillis ?? policy.controlledStartupMillis;
  if (startup === null) return null;
  const stop = policy.gracefulShutdownMillis + policy.forcedShutdownMillis;
  const rollback = startup + policy.startupCancellationMillis + stop;
  return {
    NORMAL_STARTUP: startup,
    RUNNING_STOP: stop,
    NORMAL_START_THEN_RUNNING_STOP: startup + stop,
    SHUTDOWN_DURING_STARTUP_FROM_OUTER_START: rollback,
    STARTUP_TIMEOUT_PLUS_ROLLBACK: rollback,
  };
}

function lifecycleScopeKey(source) {
  return `${source.path}#${source.scopeName}#${source.scopeKind}`;
}

function conservativeSourcePolicy(source, override) {
  if (override?.phasePolicy !== undefined)
    return structuredClone(override.phasePolicy);
  if (source.literalPhasePolicies.length === 0) {
    if (source.hasLocalPolicy)
      fail(`Lifecycle scope has unresolved source policy: ${source.id}.`);
    return {
      forcedShutdownMillis: DEFAULT_PHASE_POLICY.forcedShutdownMillis,
      gracefulShutdownMillis: DEFAULT_PHASE_POLICY.gracefulShutdownMillis,
      startupCancellationMillis:
        DEFAULT_PHASE_POLICY.startupCancellationMillis,
      startupMillis: DEFAULT_PHASE_POLICY.startupMillis,
    };
  }
  return {
    forcedShutdownMillis: Math.max(...source.literalPhasePolicies.map(
      (policy) => policy.forcedShutdownMillis)),
    gracefulShutdownMillis: Math.max(...source.literalPhasePolicies.map(
      (policy) => policy.gracefulShutdownMillis)),
    startupCancellationMillis: Math.max(...source.literalPhasePolicies.map(
      (policy) => policy.startupCancellationMillis)),
    startupMillis: source.literalPhasePolicies.some((policy) =>
      policy.startupMillis === null) ? null
      : Math.max(...source.literalPhasePolicies.map((policy) =>
        policy.startupMillis)),
  };
}

function declarationProof(source, rationale) {
  return {
    line: source.line,
    lineSha256: source.lineSha256,
    path: source.path,
    rationale,
  };
}

function emptyLifecycleReview(source, phasePolicy) {
  const constructionOnly = source.observedOperations.some((operation) =>
    operation.startsWith('CONSTRUCT_') || operation === 'CONFIGURE_POLICY');
  return {
    applicablePathBoundsMillis: {},
    applicationCleanupCount: 0,
    applicationCleanupMillis: 0,
    branchBoundsMillis: {},
    classification: source.disabled ? 'NON_EXECUTING_LIFECYCLE_EVIDENCE'
      : constructionOnly ? 'CONSTRUCTION_ONLY'
      : 'NON_EXECUTING_LIFECYCLE_EVIDENCE',
    cleanupProof: null,
    closureStatus: 'NOT_APPLICABLE',
    completeGenerationMultiplier: 0,
    controlJoinMillis: 0,
    controlProof: null,
    controlTopology: 'NONE',
    controlledCompletionProof: null,
    controlledLifecycleCoreMillis: null,
    generationCount: 0,
    generationMode: 'CONSTRUCTION_ONLY',
    generationProof: null,
    incompleteBranchCleanupCount: 0,
    incompleteGenerationMultiplier: 0,
    lifecycleCoreBranchBoundsMillis: {},
    outerGuard: null,
    phasePolicy: {
      controlledStartupMillis: null,
      ...phasePolicy,
      mode: phasePolicy.startupMillis === null
        ? 'UNBOUNDED_STARTUP_CONSTRUCTION_ONLY'
        : source.hasLocalPolicy || source.literalPhasePolicies.length > 0
          ? 'SOURCE_CONFIGURED_STANDARD_GUARD' : 'INHERITED_DEFAULT',
    },
    policyProof: null,
    priorCompleteGenerationMultiplier: 0,
    rationale: source.disabled
      ? 'The source-hashed JUnit scope is explicitly disabled and therefore executes no lifecycle generation.'
      : 'The source contains lifecycle construction, configuration, or isolated unit evidence but executes no lifecycle generation.',
    requiredAction: 'NONE',
    requiredReserveMillis: 0,
    reserveMillis: null,
    terminalReportCount: 0,
    terminalReportMillis: 0,
    totalComposedBoundMillis: 0,
  };
}

const REVIEWED_SCOPE_OVERRIDE_KEYS = new Set([
  'applicationCleanupCount', 'applicationCleanupMillis',
  'controlledLifecycleCoreMillis', 'controlledStartupMillis',
  'controlComposition', 'controlJoinMillis', 'dynamicNodeCount', 'fileSha256', 'generation',
  'incompleteBranchCleanupCount', 'phasePolicy', 'requiredAction',
  'receiverAliasing', 'scopeSha256', 'terminalReportCount',
]);
const REVIEWED_CONTROL_COMPOSITIONS = new Set([
  'REVIEWED_CONCURRENT_MAX',
  'REVIEWED_DYNAMIC_NODE_MAX',
  'REVIEWED_FOREGROUND_RELEASE',
  'REVIEWED_LIFECYCLE_CORE_DEDUPLICATION',
  'REVIEWED_NONBLOCKING_PRECONDITION',
  'REVIEWED_OVERLAP_OR_DUPLICATE',
  'REVIEWED_SEQUENTIAL_SOURCE_BOUND',
]);

function verifyReviewedScopeOverride(override, source) {
  if (override === undefined) return;
  for (const key of Object.keys(override)) {
    if (!REVIEWED_SCOPE_OVERRIDE_KEYS.has(key))
      fail(`Reviewed lifecycle override has unknown field ${key}: ${source.id}.`);
  }
  for (const field of ['fileSha256', 'scopeSha256']) {
    if (!/^[0-9a-f]{64}$/u.test(override[field] ?? ''))
      fail(`Reviewed lifecycle override ${field} is invalid: ${source.id}.`);
  }
  if (override.requiredAction !== undefined
      && !SCOPE_ACTIONS.has(override.requiredAction))
    fail(`Reviewed lifecycle override has unknown required action: ${source.id}.`);
  if (override.requiredAction === 'DELETE_OBSOLETE_ASSERTION'
      || (override.requiredAction === 'RAISE_OUTER_BOUND'
        && source.effectiveOuterTimeoutMillis
          <= STANDARD_JUNIT_GUARD_MILLIS)
      || (override.requiredAction === 'MIGRATE_POLICY'
        && !source.hasLocalPolicy
        && source.literalPhasePolicies.length === 0))
    fail(`Reviewed lifecycle override requiredAction is not source-applicable: ${source.id}.`);
  if (override.controlComposition !== undefined
      && !REVIEWED_CONTROL_COMPOSITIONS.has(override.controlComposition))
    fail(`Reviewed lifecycle override controlComposition is invalid: ${source.id}.`);
  if (override.controlComposition === 'REVIEWED_DYNAMIC_NODE_MAX'
      && (!source.testFactory || source.dynamicNodeCount < 1))
    fail(`Reviewed dynamic-node control composition is not source-applicable: ${source.id}.`);
  if (source.testFactory && override.controlJoinMillis !== undefined
      && override.controlComposition !== 'REVIEWED_DYNAMIC_NODE_MAX')
    fail(`Lifecycle dynamic-test control override must be a per-node maximum: ${source.id}.`);
  if (override.controlComposition !== undefined
      && (override.controlJoinMillis === undefined
        || source.fixedControlWaitSites.length === 0))
    fail(`Reviewed lifecycle override controlComposition is not source-applicable: ${source.id}.`);
  if (override.receiverAliasing !== undefined
      && override.receiverAliasing !== 'REVIEWED_LIFECYCLE_RECEIVER')
    fail(`Reviewed lifecycle receiver aliasing is invalid: ${source.id}.`);
  if (override.receiverAliasing !== undefined
      && source.unresolvedLifecycleReceiverCount === 0)
    fail(`Reviewed lifecycle receiver aliasing is not source-applicable: ${source.id}.`);
  if (override.controlComposition === 'REVIEWED_FOREGROUND_RELEASE'
      && source.unresolvedFixedControlWaitCount === 0)
    fail(`Reviewed lifecycle foreground-release composition has no unresolved blocker: ${source.id}.`);
  if (override.controlComposition === 'REVIEWED_NONBLOCKING_PRECONDITION'
      && (source.unresolvedFixedControlWaitCount === 0
        || override.controlJoinMillis !== 0))
    fail(`Reviewed lifecycle nonblocking-precondition composition is invalid: ${source.id}.`);
  for (const field of ['applicationCleanupCount', 'applicationCleanupMillis',
    'controlledLifecycleCoreMillis', 'controlledStartupMillis',
    'controlJoinMillis', 'dynamicNodeCount', 'incompleteBranchCleanupCount',
    'terminalReportCount']) {
    if (override[field] !== undefined
        && (!Number.isSafeInteger(override[field]) || override[field] < 0))
      fail(`Reviewed lifecycle override ${field} must be a nonnegative safe integer: ${source.id}.`);
  }
  if (override.phasePolicy !== undefined) {
    exactFields(override.phasePolicy, [
      'forcedShutdownMillis', 'gracefulShutdownMillis',
      'startupCancellationMillis', 'startupMillis',
    ], `reviewed lifecycle phase policy ${source.id}`);
    for (const field of ['forcedShutdownMillis', 'gracefulShutdownMillis',
      'startupCancellationMillis']) {
      if (!Number.isSafeInteger(override.phasePolicy[field])
          || override.phasePolicy[field] < 0)
        fail(`Reviewed lifecycle phase policy ${field} is invalid: ${source.id}.`);
    }
    if (override.phasePolicy.startupMillis !== null
        && (!Number.isSafeInteger(override.phasePolicy.startupMillis)
          || override.phasePolicy.startupMillis < 0))
      fail(`Reviewed lifecycle phase policy startupMillis is invalid: ${source.id}.`);
  }
  if (override.generation !== undefined)
    exactFields(override.generation,
      ['complete', 'count', 'incomplete', 'mode', 'prior'],
      `reviewed lifecycle generation ${source.id}`);
  if (!source.cleanupConfigured && [
    'applicationCleanupCount', 'applicationCleanupMillis',
    'incompleteBranchCleanupCount',
  ].some((field) => override[field] !== undefined))
    fail(`Lifecycle cleanup override is not source-applicable: ${source.id}.`);
  if (!source.terminalReportExpected
      && override.terminalReportCount !== undefined)
    fail(`Lifecycle terminal-report override is not source-applicable: ${source.id}.`);
  if (!source.testFactory && override.dynamicNodeCount !== undefined)
    fail(`Lifecycle dynamic-node override is not source-applicable: ${source.id}.`);
  if (override.controlledStartupMillis !== undefined
      && !source.hasNoStartupTimeout
      && override.phasePolicy?.startupMillis !== null)
    fail(`Lifecycle controlled-startup override is not source-applicable: ${source.id}.`);
  if (!source.hasExecution && !source.disabled
      && ['generation', 'controlJoinMillis', 'controlledLifecycleCoreMillis']
        .some((field) => override[field] !== undefined))
    fail(`Lifecycle execution override is not source-applicable: ${source.id}.`);
}

function inventoryLifecycleScopeSource(source) {
  return {
    applicationRunSiteCount: source.applicationRunSiteCount,
    cleanupConfigured: source.cleanupConfigured,
    disabled: source.disabled,
    dynamicNodeCount: source.dynamicNodeCount,
    dynamicNodeGuardMillis: source.dynamicNodeGuardMillis,
    effectiveOuterTimeoutMillis: source.effectiveOuterTimeoutMillis,
    fileSha256: source.fileSha256,
    fixedControlWaitCount: source.fixedControlWaitCount,
    fixedControlWaitMillis: source.fixedControlWaitMillis,
    factoryGenerationHasExecution: source.factoryGenerationHasExecution,
    factoryGenerationFixedControlWaitMillis:
      source.factoryGenerationFixedControlWaitMillis,
    factoryGenerationOuterTimeoutMillis:
      source.factoryGenerationOuterTimeoutMillis,
    factoryGenerationOuterTimeoutScope:
      source.factoryGenerationOuterTimeoutScope,
    factoryGenerationUnresolvedFixedControlWaitCount:
      source.factoryGenerationUnresolvedFixedControlWaitCount,
    generationSiteCount: source.generationSiteCount,
    hasExecution: source.hasExecution,
    hasInlineCleanup: source.hasInlineCleanup,
    hasInlineNoStartupTimeout: source.hasInlineNoStartupTimeout,
    hasInlinePolicy: source.hasInlinePolicy,
    hasLocalPolicy: source.hasLocalPolicy,
    hasNoStartupTimeout: source.hasNoStartupTimeout,
    id: source.id,
    inlineCleanupMillis: source.inlineCleanupMillis,
    line: source.line,
    lineSha256: source.lineSha256,
    observedOperations: source.observedOperations,
    outerTimeoutScope: source.outerTimeoutScope,
    path: source.path,
    scopeKind: source.scopeKind,
    scopeName: source.scopeName,
    scopeSha256: source.scopeSha256,
    terminalReportExpected: source.terminalReportExpected,
    testFactory: source.testFactory,
    unresolvedFixedControlWaitCount:
      source.unresolvedFixedControlWaitCount,
    unresolvedLifecycleRepetitionCount:
      source.unresolvedLifecycleRepetitionCount,
    unresolvedLifecycleReceiverCount:
      source.unresolvedLifecycleReceiverCount,
    unresolvedPolicyBuilderCount: source.unresolvedPolicyBuilderCount,
    unresolvedPolicyInstallationCount:
      source.unresolvedPolicyInstallationCount,
    unwrappedDynamicNodeCount: source.unwrappedDynamicNodeCount,
  };
}

export function buildReviewedLifecycleScopeRows(observations,
  { requireRegistryCompleteness = true,
    reviewedOverrides = REVIEWED_SCOPE_OVERRIDES } = {}) {
  const observationKeys = new Set(observations.map(lifecycleScopeKey));
  if (requireRegistryCompleteness) {
    for (const key of reviewedOverrides.keys()) {
      if (!observationKeys.has(key))
        fail(`Reviewed lifecycle override is unused: ${key}.`);
    }
  }
  const rows = observations.map((source) => {
    const override = reviewedOverrides.get(lifecycleScopeKey(source));
    verifyReviewedScopeOverride(override, source);
    if (override !== undefined
        && (override.scopeSha256 !== source.scopeSha256
          || override.fileSha256 !== source.fileSha256))
      fail(`Reviewed lifecycle override is stale: ${lifecycleScopeKey(source)}.`);
    if (source.disabled) {
      if (source.hasExecution)
        fail(`Disabled lifecycle scope is incorrectly marked executing: ${source.id}.`);
      return {
        review: emptyLifecycleReview(source, {
          forcedShutdownMillis: DEFAULT_PHASE_POLICY.forcedShutdownMillis,
          gracefulShutdownMillis: DEFAULT_PHASE_POLICY.gracefulShutdownMillis,
          startupCancellationMillis:
            DEFAULT_PHASE_POLICY.startupCancellationMillis,
          startupMillis: DEFAULT_PHASE_POLICY.startupMillis,
        }),
        source,
      };
    }
    if (source.unresolvedPolicyBuilderCount > 0
        && override?.phasePolicy === undefined)
      fail(`Lifecycle execution has unresolved policy builders without a source-bound override: ${source.id}.`);
    if (source.unresolvedPolicyInstallationCount > 0
        && override?.phasePolicy === undefined)
      fail(`Lifecycle execution has unresolved policy installation without a source-bound override: ${source.id}.`);
    if (source.unresolvedLifecycleReceiverCount > 0
        && override?.receiverAliasing !== 'REVIEWED_LIFECYCLE_RECEIVER')
      fail(`Lifecycle receiver aliasing is unresolved without a source-bound review: ${source.id}.`);
    if (source.testFactory) {
      if (!Number.isSafeInteger(source.factoryGenerationOuterTimeoutMillis)
          || source.factoryGenerationOuterTimeoutMillis < 0
          || !['DEFAULT', 'METHOD', 'TYPE'].includes(
            source.factoryGenerationOuterTimeoutScope))
        fail(`Lifecycle dynamic-test factory generation guard is unresolved: ${source.id}.`);
      if (source.factoryGenerationHasExecution)
        fail(`Lifecycle dynamic-test factory has pre-node generation work that cannot borrow a dynamic-node guard: ${source.id}.`);
      if (source.factoryGenerationUnresolvedFixedControlWaitCount > 0)
        fail(`Lifecycle dynamic-test factory has unresolved pre-node control waits: ${source.id}.`);
      if (source.factoryGenerationFixedControlWaitMillis
          >= source.factoryGenerationOuterTimeoutMillis)
        fail(`Lifecycle dynamic-test factory pre-node control waits do not fit the factory-generation guard: ${source.id}.`);
      if (source.dynamicNodeSiteCount < 1
          || source.unwrappedDynamicNodeCount !== 0
          || source.dynamicNodeGuardMillis === null
          || source.dynamicNodeGuardMillis < STANDARD_JUNIT_GUARD_MILLIS
          || source.outerTimeoutScope !== 'DYNAMIC_NODE')
        fail(`Lifecycle dynamic-test nodes lack an explicit 60-second outer guard: ${source.id}.`);
      if (!Number.isSafeInteger(source.dynamicNodeCount)
          || source.dynamicNodeCount < 1)
        fail(`Lifecycle dynamic-test factory lacks a source-bound node census: ${source.id}.`);
      if (override?.dynamicNodeCount === undefined)
        fail(`Lifecycle dynamic-test factory lacks a source-bound reviewed node census: ${source.id}.`);
      if (override.dynamicNodeCount !== source.dynamicNodeCount)
        fail(`Lifecycle dynamic-test node census drifted: ${source.id}.`);
    } else if (source.factoryGenerationHasExecution
        || source.factoryGenerationFixedControlWaitMillis !== 0
        || source.factoryGenerationOuterTimeoutMillis !== null
        || source.factoryGenerationOuterTimeoutScope !== null
        || source.factoryGenerationUnresolvedFixedControlWaitCount !== 0
        || source.dynamicNodeGuardMillis !== null
        || source.dynamicNodeCount !== 0
        || source.dynamicNodeSiteCount !== 0
        || source.unwrappedDynamicNodeCount !== 0
        || source.outerTimeoutScope === 'DYNAMIC_NODE') {
      fail(`Non-factory lifecycle scope has dynamic-node guard metadata: ${source.id}.`);
    }

    const sourcePolicy = conservativeSourcePolicy(source, override);
    if (source.hasNoStartupTimeout && sourcePolicy.startupMillis !== null)
      fail(`Unbounded source startup policy cannot be replaced by a finite review: ${source.id}.`);
    if (!source.hasExecution)
      return { review: emptyLifecycleReview(source, sourcePolicy), source };

    if (source.scopeKind !== 'TEST' && source.scopeKind !== 'SETUP_TEARDOWN')
      fail(`Non-JUnit lifecycle helper reached the closure row set: ${source.id}.`);
    if (source.unresolvedLifecycleRepetitionCount > 0
        && override?.generation === undefined && !source.testFactory)
      fail(`Lifecycle repetition topology is unresolved without a source-bound generation review: ${source.id}.`);
    const repeatedApplicationTopology = source.applicationRunSiteCount > 1
      || (source.applicationRunSiteCount > 0
        && source.unresolvedLifecycleRepetitionCount > 0);
    if (repeatedApplicationTopology
        && ((source.cleanupConfigured
          && (override?.applicationCleanupCount === undefined
            || override?.incompleteBranchCleanupCount === undefined))
          || (source.terminalReportExpected
            && override?.terminalReportCount === undefined)))
      fail(`Repeated application runs lack source-bound cleanup/report multiplicities: ${source.id}.`);
    if (source.unresolvedFixedControlWaitCount > 0
        && override?.controlJoinMillis === undefined)
      fail(`Lifecycle fixed-control waits are unresolved without a source-bound allowance: ${source.id}.`);
    if (source.unresolvedFixedControlWaitCount > 0
        && override?.controlComposition === undefined)
      fail(`Lifecycle unresolved fixed-control waits lack an explicit reviewed composition: ${source.id}.`);
    if (source.effectiveOuterTimeoutMillis < STANDARD_JUNIT_GUARD_MILLIS)
      fail(`Lifecycle execution outer guard is shorter than 60 seconds: ${source.id}.`);
    const configuredPolicy = source.hasLocalPolicy
      || source.literalPhasePolicies.length > 0
      || override?.phasePolicy !== undefined;
    const unbounded = sourcePolicy.startupMillis === null;
    if (unbounded && (override?.controlledStartupMillis === undefined
        || override?.controlledLifecycleCoreMillis === undefined))
      fail(`Unbounded lifecycle execution lacks a source-bound controlled-completion override: ${source.id}.`);

    const generation = override?.generation ?? (() => {
      const count = Math.max(1, source.generationSiteCount);
      return count === 1 ? {
        complete: 1, count: 1, incomplete: 1, mode: 'SINGLE', prior: 0,
      } : {
        complete: count, count, incomplete: 1, mode: 'SEQUENTIAL',
        prior: count - 1,
      };
    })();
    if (source.testFactory && (generation.mode !== 'SINGLE'
        || generation.count !== 1 || generation.complete !== 1
        || generation.prior !== 0 || generation.incomplete !== 1))
      fail(`Lifecycle dynamic-test factory must model one independently guarded node: ${source.id}.`);
    if (![generation.count, generation.complete, generation.incomplete,
      generation.prior].every(Number.isSafeInteger)
        || generation.count < 1 || generation.complete < 1
        || generation.incomplete < 1 || generation.prior < 0)
      fail(`Reviewed generation override is invalid: ${source.id}.`);
    if (!['SINGLE', 'SEQUENTIAL', 'CONCURRENT_OR_ALTERNATIVE',
      'MIXED_MAX_PLUS_SEQUENTIAL', 'ONE_FULL_PLUS_PREINIT_REJECTIONS']
      .includes(generation.mode)
        || (generation.mode === 'SINGLE'
          && (generation.count !== 1 || generation.complete !== 1
            || generation.prior !== 0 || generation.incomplete !== 1))
        || (generation.mode === 'SEQUENTIAL'
          && (generation.count < 2
            || generation.complete !== generation.count
            || generation.prior + generation.incomplete
              !== generation.count))
        || (generation.mode === 'CONCURRENT_OR_ALTERNATIVE'
          && (generation.count < 2 || generation.complete !== 1
            || generation.prior !== 0 || generation.incomplete !== 1))
        || (generation.mode === 'MIXED_MAX_PLUS_SEQUENTIAL'
          && (generation.count < 2 || generation.complete < 2
            || generation.complete > generation.count
            || generation.prior + generation.incomplete
              > generation.count))
        || (generation.mode === 'ONE_FULL_PLUS_PREINIT_REJECTIONS'
          && (generation.count < 2 || generation.complete !== 1
            || generation.prior !== 0 || generation.incomplete !== 1)))
      fail(`Reviewed generation topology is invalid: ${source.id}.`);
    if (override?.generation === undefined
        && generation.count < source.generationSiteCount)
      fail(`Lifecycle generation review understates source evidence: ${source.id}.`);
    if (override?.generation !== undefined
        && override.generation.count !== generation.count)
      fail(`Lifecycle generation override is internally inconsistent: ${source.id}.`);

    const controlledStartupMillis = unbounded
      ? override.controlledStartupMillis : null;
    const phasePolicy = {
      controlledStartupMillis,
      ...sourcePolicy,
      mode: unbounded ? 'UNBOUNDED_STARTUP_WITH_CONTROLLED_PROOF'
        : configuredPolicy
          ? source.hasInlinePolicy ? 'LOCAL_FINITE_REVIEWED'
            : 'SOURCE_CONFIGURED_FINITE_WITH_PROOF'
          : 'INHERITED_DEFAULT',
    };
    const bounds = scopePathBounds(phasePolicy);
    if (bounds === null)
      fail(`Lifecycle execution scope remains unbounded: ${source.id}.`);
    const completePath = override?.controlledLifecycleCoreMillis
      ?? Math.max(bounds.RUNNING_STOP,
        bounds.NORMAL_START_THEN_RUNNING_STOP);
    const incompletePath = override?.controlledLifecycleCoreMillis
      ?? Math.max(bounds.SHUTDOWN_DURING_STARTUP_FROM_OUTER_START,
        bounds.STARTUP_TIMEOUT_PLUS_ROLLBACK);
    const coreBranches = {
      COMPLETE_CORE: completePath * generation.complete,
      INCOMPLETE_CORE: completePath * generation.prior
        + incompletePath * generation.incomplete,
    };
    const applicationCleanupMillis = override?.applicationCleanupMillis
      ?? source.inlineCleanupMillis ?? 0;
    if (source.cleanupConfigured && applicationCleanupMillis <= 0)
      fail(`Configured lifecycle cleanup lacks a source-bound allowance: ${source.id}.`);
    if (!source.cleanupConfigured && applicationCleanupMillis !== 0)
      fail(`Lifecycle cleanup override is not source-applicable: ${source.id}.`);
    const applicationCleanupCount = source.cleanupConfigured
      ? override?.applicationCleanupCount ?? 1 : 0;
    if (!Number.isSafeInteger(applicationCleanupCount)
        || applicationCleanupCount < 0
        || (source.cleanupConfigured && applicationCleanupCount < 1)
        || (!source.cleanupConfigured && applicationCleanupCount !== 0))
      fail(`Lifecycle cleanup repetition count is invalid: ${source.id}.`);
    if (source.applicationRunSiteCount > 1 && source.cleanupConfigured
        && applicationCleanupCount < source.applicationRunSiteCount)
      fail(`Lifecycle cleanup repetition count understates application runs: ${source.id}.`);
    const incompleteBranchCleanupCount = source.cleanupConfigured
      ? override?.incompleteBranchCleanupCount ?? 0 : 0;
    if (!Number.isSafeInteger(incompleteBranchCleanupCount)
        || incompleteBranchCleanupCount < 0
        || incompleteBranchCleanupCount > applicationCleanupCount)
      fail(`Lifecycle incomplete-branch cleanup repetition count is invalid: ${source.id}.`);
    const controlJoinMillis = override?.controlJoinMillis
      ?? source.fixedControlWaitMillis;
    if (!Number.isSafeInteger(controlJoinMillis) || controlJoinMillis < 0)
      fail(`Lifecycle control/join allowance is invalid: ${source.id}.`);
    if (override?.controlJoinMillis !== undefined
        && controlJoinMillis < source.fixedControlWaitMillis
        && override.controlComposition === undefined)
      fail(`Lifecycle control/join allowance understates fixed source waits: ${source.id}.`);
    if (override?.controlComposition === 'REVIEWED_SEQUENTIAL_SOURCE_BOUND'
        && controlJoinMillis < source.fixedControlWaitMillis)
      fail(`Lifecycle sequential control/join allowance understates fixed source waits: ${source.id}.`);
    if (override?.controlComposition === 'REVIEWED_OVERLAP_OR_DUPLICATE'
        && controlJoinMillis >= source.fixedControlWaitMillis)
      fail(`Lifecycle reviewed control composition does not reduce overlapping source waits: ${source.id}.`);
    if (['CONCURRENT_OR_ALTERNATIVE', 'MIXED_MAX_PLUS_SEQUENTIAL']
      .includes(generation.mode) && controlJoinMillis <= 0)
      fail(`Concurrent lifecycle composition lacks a source-bound control/join allowance: ${source.id}.`);
    const terminalReportCount = source.terminalReportExpected
      ? override?.terminalReportCount ?? 1 : 0;
    if (!Number.isSafeInteger(terminalReportCount)
        || terminalReportCount < 0
        || (source.terminalReportExpected && terminalReportCount < 1)
        || (!source.terminalReportExpected && terminalReportCount !== 0))
      fail(`Lifecycle terminal-report repetition count is invalid: ${source.id}.`);
    if (source.applicationRunSiteCount > 0
        && terminalReportCount < source.applicationRunSiteCount)
      fail(`Lifecycle terminal-report count understates application runs: ${source.id}.`);
    const terminalReportMillis = terminalReportCount * 250;
    const branches = {
      COMPLETE_CORE: coreBranches.COMPLETE_CORE + controlJoinMillis
        + applicationCleanupMillis * applicationCleanupCount
        + terminalReportMillis,
      INCOMPLETE_CORE: coreBranches.INCOMPLETE_CORE + controlJoinMillis
        + applicationCleanupMillis * incompleteBranchCleanupCount
        + terminalReportMillis,
    };
    const total = Math.max(branches.COMPLETE_CORE,
      branches.INCOMPLETE_CORE);
    const reserve = source.effectiveOuterTimeoutMillis - total;
    const classification = configuredPolicy && !unbounded
      ? 'LOCAL_POLICY_STRICT_FIT'
      : 'STANDARD_60_SECOND_DEADLOCK_GUARD';
    const requiredReserveMillis = classification === 'LOCAL_POLICY_STRICT_FIT'
      ? 1 : 0;
    if (reserve < requiredReserveMillis)
      fail(`Lifecycle composition does not fit its outer guard: ${source.id} (total=${total}, guard=${source.effectiveOuterTimeoutMillis}, requiredReserve=${requiredReserveMillis}).`);
    const helperPolicyProof = configuredPolicy && !source.hasInlinePolicy;
    const sourceProof = (rationale) => declarationProof(source, rationale);
    const requiredAction = override?.requiredAction
      ?? (source.outerTimeoutScope === 'METHOD'
          && source.effectiveOuterTimeoutMillis > STANDARD_JUNIT_GUARD_MILLIS
        ? 'RAISE_OUTER_BOUND' : configuredPolicy ? 'MIGRATE_POLICY' : 'NONE');
    return {
      review: {
        applicablePathBoundsMillis: bounds,
        applicationCleanupCount,
        applicationCleanupMillis,
        branchBoundsMillis: branches,
        classification,
        cleanupProof: source.cleanupConfigured && !source.hasInlineCleanup
          ? sourceProof('The full callable hash binds the helper-configured complete-shutdown cleanup allowance.')
          : null,
        closureStatus: 'CLOSED',
        completeGenerationMultiplier: generation.complete,
        controlJoinMillis,
        controlProof: controlJoinMillis > 0
            || override?.controlComposition !== undefined
          ? sourceProof({
            REVIEWED_CONCURRENT_MAX: 'The full callable and owning-file hashes bind pre-submission, concurrent maximum, and fail-fast control-wait topology.',
            REVIEWED_DYNAMIC_NODE_MAX: 'The full callable and owning-file hashes bind the per-dynamic-node control maximum without summing independent nodes.',
            REVIEWED_FOREGROUND_RELEASE: 'The full callable and owning-file hashes bind the background blocker to a guaranteed foreground/finally release and bounded join.',
            REVIEWED_LIFECYCLE_CORE_DEDUPLICATION: 'The full callable and owning-file hashes bind apparent waits which are lifecycle-core phases or nonblocking rejection checks rather than additional sequential control.',
            REVIEWED_NONBLOCKING_PRECONDITION: 'The full callable and owning-file hashes bind the reviewed precondition which throws before the apparent wait can block.',
            REVIEWED_OVERLAP_OR_DUPLICATE: 'The full callable and owning-file hashes bind the reviewed overlapping or duplicate control-wait topology.',
            REVIEWED_SEQUENTIAL_SOURCE_BOUND: 'The full callable and owning-file hashes bind every sequential fixed-control wait and helper deadline.',
          }[override?.controlComposition]
            ?? 'The full callable hash binds the reviewed latch, future, executor, or process-control join allowance.')
          : null,
        controlTopology: override?.controlComposition
          ?? (controlJoinMillis > 0 || source.fixedControlWaitSites.length > 0
            ? 'CONSERVATIVE_SEQUENTIAL_SUM' : 'NONE'),
        controlledCompletionProof: unbounded
          ? sourceProof('The full callable hash binds the deterministic entry, trigger, and completion controls for the unbounded startup path.')
          : null,
        controlledLifecycleCoreMillis:
          override?.controlledLifecycleCoreMillis ?? null,
        generationCount: generation.count,
        generationMode: generation.mode,
        generationProof: generation.count === 1 ? null
          : sourceProof('The full callable hash and independent verifier override bind the reviewed generation topology.'),
        incompleteBranchCleanupCount,
        incompleteGenerationMultiplier: generation.incomplete,
        lifecycleCoreBranchBoundsMillis: coreBranches,
        outerGuard: {
          kind: source.outerTimeoutScope === 'DYNAMIC_NODE'
            ? 'EXPLICIT_DYNAMIC_NODE_TIMEOUT'
            : source.outerTimeoutScope === 'DEFAULT'
              ? 'STANDARD_JUNIT_DEFAULT'
              : source.outerTimeoutScope === 'TYPE'
                ? 'EXPLICIT_TYPE_TIMEOUT' : 'EXPLICIT_METHOD_TIMEOUT',
          millis: source.effectiveOuterTimeoutMillis,
          path: source.outerTimeoutScope === 'DEFAULT'
            ? STANDARD_JUNIT_GUARD_PATH : source.path,
        },
        phasePolicy,
        policyProof: helperPolicyProof
          ? sourceProof('The full callable hash plus propagated helper/field evidence binds every configured policy phase.')
          : null,
        priorCompleteGenerationMultiplier: generation.prior,
        rationale: 'Every applicable lifecycle branch, repeated generation, cleanup/report phase, and control join is composed under the effective outer guard.',
        requiredAction,
        requiredReserveMillis,
        reserveMillis: reserve,
        terminalReportCount,
        terminalReportMillis,
        totalComposedBoundMillis: total,
      },
      source,
    };
  });
  return rows.map((row) => ({
    review: row.review,
    source: inventoryLifecycleScopeSource(row.source),
  }));
}

function verifyProof(proof, texts, label) {
  exactFields(proof, ['line', 'lineSha256', 'path', 'rationale'], label);
  if (!proof.rationale) fail(`${label} lacks rationale.`);
  const text = texts.get(proof.path);
  const sourceLine = text === undefined ? undefined : splitLines(text)[proof.line - 1];
  if (sourceLine === undefined || lineSha256(sourceLine) !== proof.lineSha256)
    fail(`${label} source evidence is stale: ${proof.path}:${proof.line}.`);
}

export function buildReviewedOrphanLifecycleHelperRows(observations,
  { texts = null } = {}) {
  const observedKeys = new Set(observations.map((source) =>
    `${source.path}#${source.scopeName}#${source.line}`));
  for (const [key, override] of REVIEWED_ORPHAN_HELPERS) {
    const source = observations.find((candidate) =>
      `${candidate.path}#${candidate.scopeName}#${candidate.line}` === key);
    if (source === undefined)
      fail(`Reviewed orphan lifecycle helper is unused: ${key}.`);
    if (source.scopeSha256 !== override.scopeSha256
        || source.fileSha256 !== override.fileSha256)
      fail(`Reviewed orphan lifecycle helper is stale: ${key}.`);
    if (texts !== null)
      verifyProof(override.invocationProof, texts,
        `reviewed orphan lifecycle helper invocation proof ${key}`);
  }
  for (const key of observedKeys) {
    if (!REVIEWED_ORPHAN_HELPERS.has(key))
      fail(`Unreviewed orphan lifecycle helper: ${key}.`);
  }
  return observations.map((source) => {
    const key = `${source.path}#${source.scopeName}#${source.line}`;
    return {
      review: {
        classification: 'REVIEWED_EXTERNAL_HELPER_EVIDENCE',
        closureStatus: 'CLOSED',
        invocationProof: structuredClone(
          REVIEWED_ORPHAN_HELPERS.get(key).invocationProof),
        rationale: 'Source-hashed helper lifecycle evidence is reviewed independently; it receives no synthetic JUnit outer guard and is not an executable closure row.',
        requiredAction: 'NONE',
      },
      source,
    };
  });
}

function verifyOrphanLifecycleHelpers(rows, observations, texts) {
  if (!Array.isArray(rows))
    fail('orphanLifecycleHelpers must be an array.');
  const expectedRows = buildReviewedOrphanLifecycleHelperRows(observations,
    { texts });
  const sources = rows.map((row, index) => {
    exactFields(row, ['review', 'source'],
      `orphan lifecycle helper row ${index}`);
    exactFields(row.review, [
      'classification', 'closureStatus', 'invocationProof', 'rationale',
      'requiredAction',
    ], `orphan lifecycle helper review ${index}`);
    if (row.review.classification !== 'REVIEWED_EXTERNAL_HELPER_EVIDENCE'
        || row.review.closureStatus !== 'CLOSED'
        || row.review.requiredAction !== 'NONE'
        || !row.review.rationale)
      fail(`Orphan lifecycle helper review is unresolved: ${row.source.id}.`);
    const key = `${row.source.path}#${row.source.scopeName}#${row.source.line}`;
    const expected = REVIEWED_ORPHAN_HELPERS.get(key);
    if (expected === undefined)
      fail(`Unreviewed orphan lifecycle helper: ${key}.`);
    compareJson(row.review.invocationProof, expected.invocationProof,
      `Orphan lifecycle helper invocation proof ${key}`);
    return row.source;
  });
  compareJson(sources, observations,
    'Source-hashed orphan lifecycle helper evidence');
  compareJson(rows, expectedRows,
    'Source-bound orphan lifecycle helper reviews');
}

function requiredExecutingScopeKeys() {
  return Object.entries(REQUIRED_EXECUTING_SCOPES).flatMap(([path, names]) =>
    names.map((scopeName) => `${path}#${scopeName}#TEST`));
}

function verifyRequiredExecutingObservations(observations) {
  const byKey = new Map(observations.map((source) =>
    [lifecycleScopeKey(source), source]));
  for (const key of requiredExecutingScopeKeys()) {
    const source = byKey.get(key);
    if (source === undefined || !source.hasExecution
        || source.generationSiteCount < 1)
      fail(`Required lifecycle execution scope was downgraded or lost: ${key}.`);
  }
  for (const [key, expected] of REQUIRED_GENERATION_COUNTS) {
    const source = byKey.get(key);
    if (source === undefined || source.generationSiteCount < expected)
      fail(`Required lifecycle generation topology was understated: ${key}.`);
  }
  for (const key of REQUIRED_DISABLED_SCOPES) {
    const source = byKey.get(key);
    if (source === undefined || !source.disabled || source.hasExecution)
      fail(`Required disabled lifecycle scope was re-enabled: ${key}.`);
  }
}

export function verifyRequiredExecutingRows(rows) {
  const byKey = new Map(rows.map((row) =>
    [lifecycleScopeKey(row.source), row]));
  for (const key of requiredExecutingScopeKeys()) {
    const row = byKey.get(key);
    if (row === undefined || row.review.closureStatus !== 'CLOSED'
        || row.review.outerGuard === null
        || row.review.generationCount < 1
        || row.review.totalComposedBoundMillis < 0
        || row.review.reserveMillis < 0
        || ['CONSTRUCTION_ONLY', 'NON_EXECUTING_LIFECYCLE_EVIDENCE']
          .includes(row.review.classification))
      fail(`Required lifecycle execution scope is not bounded and closed: ${key}.`);
  }
  for (const [key, expected] of REQUIRED_GENERATION_COUNTS) {
    const row = byKey.get(key);
    if (row === undefined || row.review.generationCount !== expected)
      fail(`Required lifecycle generation review drifted: ${key}.`);
  }
  for (const [key, expected] of REQUIRED_REVIEWED_GENERATION_COUNTS) {
    const row = byKey.get(key);
    if (row === undefined || row.review.generationCount !== expected)
      fail(`Required reviewed lifecycle generation topology drifted: ${key}.`);
  }
}

function verifyLifecycleScopes(rows, observations, texts) {
  if (!Array.isArray(rows)) fail('lifecycleScopes must be an array.');
  const sources = rows.map((row, index) => {
    exactFields(row, ['review', 'source'], `lifecycle scope row ${index}`);
    return row.source;
  });
  compareJson(sources, observations.map(inventoryLifecycleScopeSource),
    'Line-addressed lifecycle method scope set');
  const expectedRows = buildReviewedLifecycleScopeRows(observations);
  compareJson(rows, expectedRows,
    'Source-bound lifecycle semantic closure rows');
  verifyRequiredExecutingRows(rows);
  for (const { review, source } of rows) {
    if (!CLOSED_STATUSES.has(review.closureStatus)
        || review.requiredAction === undefined)
      fail(`Lifecycle semantic row is unresolved: ${source.id}.`);
    if (!SCOPE_CLASSIFICATIONS.has(review.classification))
      fail(`Lifecycle semantic row has unknown classification: ${source.id}.`);
    if (!SCOPE_ACTIONS.has(review.requiredAction))
      fail(`Lifecycle semantic row has unknown required action: ${source.id}.`);
    for (const field of ['cleanupProof', 'controlProof',
      'controlledCompletionProof', 'generationProof', 'policyProof']) {
      if (review[field] !== null)
        verifyProof(review[field], texts,
          `Lifecycle scope ${source.id} ${field}`);
    }
  }
  return {
    count: rows.length,
    pathCount: new Set(rows.map((row) => row.source.path)).size,
  };
}

function verifyJunitLifecyclePaths(paths, texts) {
  const overrides = [];
  for (const path of paths) {
    if (!path.startsWith(JUNIT_ROOT) || !path.endsWith('.java'))
      fail(`JUNIT_LIFECYCLE path is outside ${JUNIT_ROOT}: ${path}`);
    const text = texts.get(path);
    if (text === undefined) fail(`Missing JUNIT_LIFECYCLE path: ${path}`);
    for (const row of parseJunitTimeouts(path, text)) {
      if (row.millis < STANDARD_JUNIT_GUARD_MILLIS)
        fail(`Lifecycle @Timeout is shorter than the standard 60-second guard: ${path}:${row.line} (${row.millis} ms).`);
      overrides.push(row);
    }
  }
  overrides.sort((left, right) => asciiCompare(left.path, right.path)
    || left.line - right.line);
  return {
    count: overrides.length,
    sha256: sha256(Buffer.from(overrides.map((row) =>
      `${row.path}:${row.line}:${row.scopeKind}:${row.millis}:${row.lineSha256}`)
      .join('\n'), 'utf8')),
  };
}

function parseIntegerProperties(text, path) {
  const properties = parseProperties(text, path);
  const values = {};
  for (const [key, value] of properties) {
    if (!/^\d+$/u.test(value)) fail(`${path} property ${key} must be an integer.`);
    const number = Number.parseInt(value, 10);
    if (!Number.isSafeInteger(number) || number <= 0)
      fail(`${path} property ${key} must be a positive safe integer.`);
    values[key] = number;
  }
  return values;
}

export function soakProfiles(texts) {
  const profiles = SOAK_PROFILE_NAMES.map((name) => {
    const path = `${SOAK_PROFILE_ROOT}/${name}.properties`;
    const text = texts.get(path);
    if (text === undefined) fail(`Missing soak profile ${path}.`);
    const values = parseIntegerProperties(text, path);
    const required = [
      'http.runTimeoutMillis', 'http.settleTimeoutMillis',
      'mcp.forcedShutdownMillis', 'mcp.gracefulShutdownMillis',
      'mcp.runTimeoutMillis', 'mcp.settleTimeoutMillis',
      'mcp.shutdownCycles',
      'realtime.runTimeoutMillis', 'realtime.settleTimeoutMillis',
    ];
    for (const key of required)
      if (!(key in values)) fail(`${path} is missing ${key}.`);
    return {
      name,
      path,
      sha256: sha256(Buffer.from(text, 'utf8')),
      values: Object.fromEntries(required.map((key) => [key, values[key]])),
    };
  });
  compareJson(profiles.map((profile) =>
    profile.values['mcp.gracefulShutdownMillis']), [3_000, 5_000, 10_000],
  'V4 MCP soak graceful bounds');
  compareJson(profiles.map((profile) =>
    profile.values['mcp.forcedShutdownMillis']), [1_000, 1_000, 1_000],
  'V4 MCP soak forced bounds');
  compareJson(profiles.map((profile) =>
    profile.values['mcp.shutdownCycles']), [2, 4, 8],
  'V4 MCP cross-feature shutdown cycles');
  return profiles;
}

function requirePattern(text, pattern, label, expectedCount = 1) {
  const matches = [...text.matchAll(new RegExp(pattern.source,
    pattern.flags.includes('g') ? pattern.flags : `${pattern.flags}g`))];
  if (matches.length !== expectedCount)
    fail(`${label} must occur exactly ${expectedCount} time(s); found ${matches.length}.`);
  return matches;
}

function sourceLineEvidence(path, text, pattern, label, searchText = text) {
  const matches = requirePattern(searchText, pattern, label);
  const match = matches[0];
  const line = text.slice(0, match.index).split(/\r\n|\n|\r/u).length;
  return { line, lineSha256: lineSha256(splitLines(text)[line - 1]), path };
}

function requirePolicySetterCount(text, setter, label, expectedCount) {
  requirePattern(text, new RegExp(
    `\\.\\s*${setter}\\s*\\(\\s*(?!\\))`, 'u'), label, expectedCount);
}

function normalizeHostText(text) {
  return text.replace(/\r\n?/gu, '\n');
}

function literalCount(text, literal) {
  let count = 0;
  let offset = 0;
  while (true) {
    const index = text.indexOf(literal, offset);
    if (index < 0) return count;
    count += 1;
    offset = index + literal.length;
  }
}

function requireUniqueLiteral(text, literal, label) {
  const count = literalCount(text, literal);
  if (count !== 1)
    fail(`${label} must occur exactly once; found ${count}.`);
}

function requireExactShellBlock(text, expectedLines, label) {
  const normalized = normalizeHostText(text);
  const expected = expectedLines.join('\n');
  requireUniqueLiteral(normalized, expected, `${label} executable block`);
  for (const command of expectedLines.filter((line) =>
    /lifecycle_bound_harness_(?:self_test|verifier)/u.test(line)))
    requireUniqueLiteral(normalized, command.trim(), `${label} command`);
}

export function verifyLifecycleHostWiring(texts) {
  const ciPath = '.github/workflows/ci.yml';
  const ci = texts.get(ciPath);
  if (ci === undefined) fail(`Missing routine CI workflow ${ciPath}.`);
  const normalizedCi = normalizeHostText(ci);
  for (const command of [
    'node scripts/verify-lifecycle-bound-harness-inventory-self-test.mjs',
    'node scripts/verify-lifecycle-bound-harness-inventory.mjs',
  ]) {
    if (literalCount(normalizedCi, command) !== 0)
      fail('Routine CI must not invoke release-only lifecycle closure checks.');
  }

  const releasePath = 'scripts/validate-release-candidate.sh';
  const release = texts.get(releasePath);
  if (release === undefined)
    fail(`Missing lifecycle release host ${releasePath}.`);
  requireUniqueLiteral(normalizeHostText(release), 'set -euo pipefail',
    'release host fail-closed shell mode');
  requireExactShellBlock(release, [
    '{',
    '\tnode "$version_transition_self_test"',
    '\tnode "$version_transition_verifier" --stage final',
    '\tnode "$lifecycle_bound_harness_self_test"',
    '\tnode "$lifecycle_bound_harness_verifier"',
    '\tnode "$d1p_evidence_self_test"',
    '\tmvn -B -ntp -Dgpg.skip=true clean verify',
    '} 2>&1 | tee "$build_log"',
  ], 'release-candidate lifecycle closure host');
  return { ciPath, releasePath };
}

export function javascriptGuards(texts) {
  const officialRaw = texts.get('conformance/official/run.mjs');
  const localRaw = texts.get(LOCAL_SIMULATOR_PATH);
  if (officialRaw === undefined || localRaw === undefined)
    fail('Official JavaScript lifecycle harnesses are missing.');
  const official = maskJavascriptSource(officialRaw);
  const local = maskJavascriptSource(localRaw);
  const officialEvidence = sourceLineEvidence('conformance/official/run.mjs',
    officialRaw, /const\s+shutdownTimeoutMilliseconds\s*=\s*10_000\s*;/u,
    'official shared shutdown guard', official);
  const officialUses = [...official.matchAll(/\bshutdownTimeoutMilliseconds\b/gu)];
  if (officialUses.length !== 3)
    fail(`Official shutdown guard must have one definition and two consumers; found ${officialUses.length} references.`);
  requirePattern(official,
    /const\s+shutdownDeadlineNanoseconds\s*=\s*process\.hrtime\.bigint\(\)\s*\+\s*BigInt\(shutdownTimeoutMilliseconds\)\s*\*\s*1_000_000n\s*;/u,
    'official shared absolute shutdown deadline');
  requirePattern(official,
    /fixture\.lines\.next\(\s*remainingFixtureShutdownMilliseconds\(shutdownDeadlineNanoseconds\)\s*,?\s*\)/u,
    'official stopped-line remaining-deadline consumer');
  requirePattern(official,
    /fixture\.supervisor\.waitForClose\(\s*fixture\.child\s*,\s*remainingFixtureShutdownMilliseconds\(shutdownDeadlineNanoseconds\)\s*,?\s*\)/u,
    'official process-close remaining-deadline consumer');
  if ([...official.matchAll(
    /remainingFixtureShutdownMilliseconds\(shutdownDeadlineNanoseconds\)/gu)]
    .length !== 2)
    fail('Official graceful shutdown must have exactly two shared-deadline consumers.');
  requirePattern(official,
    /catch\s*\(error\)\s*\{[\s\S]{0,500}?terminate\(fixture\.child\)[\s\S]{0,300}?waitForClose\(fixture\.child,\s*shutdownTimeoutMilliseconds\)/u,
    'official best-effort cleanup fallback');
  const remainingHelper = requirePattern(official,
    /function\s+remainingFixtureShutdownMilliseconds\s*\(deadlineNanoseconds\)\s*\{\s*const\s+remainingNanoseconds\s*=\s*deadlineNanoseconds\s*-\s*process\.hrtime\.bigint\(\)\s*;\s*if\s*\(remainingNanoseconds\s*<=\s*0n\)\s*throw\s+new\s+Error\([^;]+\)\s*;\s*return\s+Number\s*\(\s*\(remainingNanoseconds\s*\+\s*999_999n\)\s*\/\s*1_000_000n\s*\)\s*;\s*\}/u,
    'official remaining shutdown deadline helper semantics')[0];
  const remainingHelperSha256 = sha256(Buffer.from(officialRaw.slice(
    remainingHelper.index, remainingHelper.index + remainingHelper[0].length),
  'utf8'));
  const localEvidence = sourceLineEvidence(LOCAL_SIMULATOR_PATH, localRaw,
    /const\s+timeoutMilliseconds\s*=\s*120_000\s*;/u,
    'local simulator process guard', local);
  requirePattern(local, /timeout\s*:\s*timeoutMilliseconds\s*,/u,
    'local simulator spawn timeout');
  if ([...local.matchAll(/\btimeoutMilliseconds\b/gu)].length !== 2)
    fail('Local simulator process guard must have one definition and one consumer.');
  return [
    {
      closureStatus: 'CLOSED',
      cleanupFallbackMillis: 10_000,
      consumerCount: 2,
      deadlineConsumerCount: 2,
      evidence: officialEvidence,
      id: 'OFFICIAL-SHARED-SHUTDOWN-GUARD',
      helperSha256: remainingHelperSha256,
      millis: 10_000,
      requiredAction: 'NONE',
      variable: 'shutdownTimeoutMilliseconds',
    },
    {
      closureStatus: 'CLOSED',
      cleanupFallbackMillis: 0,
      consumerCount: 1,
      deadlineConsumerCount: 0,
      evidence: localEvidence,
      id: 'LOCAL-SIMULATOR-PROCESS-GUARD',
      millis: 120_000,
      requiredAction: 'NONE',
      variable: 'timeoutMilliseconds',
    },
  ];
}

export function verifySpecialSourceWiring(texts) {
  const officialFixtureRaw = texts.get(OFFICIAL_FIXTURE_PATH);
  if (officialFixtureRaw === undefined) fail(`Missing ${OFFICIAL_FIXTURE_PATH}.`);
  const officialFixture = maskJavaSource(officialFixtureRaw);
  requirePattern(officialFixture,
    /\.startupCancellationTimeout\s*\(\s*Duration\.ofSeconds\s*\(\s*1\s*\)\s*\)/u,
    'official 1-second startup cancellation');
  requirePattern(officialFixture,
    /\.gracefulShutdownDuration\s*\(\s*Duration\.ofSeconds\s*\(\s*5\s*\)\s*\)/u,
    'official 5-second graceful shutdown');
  requirePattern(officialFixture,
    /\.forcedShutdownDuration\s*\(\s*Duration\.ofSeconds\s*\(\s*1\s*\)\s*\)/u,
    'official 1-second forced shutdown');
  for (const setter of ['startupCancellationTimeout',
    'gracefulShutdownDuration', 'forcedShutdownDuration']) {
    requirePolicySetterCount(officialFixture, setter,
      `official unique ${setter}`, 1);
  }
  requirePolicySetterCount(officialFixture, 'startupTimeout',
    'official no startup-timeout override', 0);
  requirePattern(officialFixture, /\.\s*noStartupTimeout\s*\(/u,
    'official no unbounded-startup override', 0);

  for (const path of [
    'soak/src/test/java/com/soklet/HttpSoakTests.java',
    'soak/src/test/java/com/soklet/RealtimeTransportSoakTests.java',
  ]) {
    const rawText = texts.get(path);
    if (rawText === undefined) fail(`Missing ${path}.`);
    const text = maskJavaSource(rawText);
    requirePattern(text,
      /\.gracefulShutdownDuration\s*\(\s*Duration\.ofSeconds\s*\(\s*3\s*\)\s*\)/u,
      `${path} 3-second graceful shutdown`);
    requirePattern(text,
      /\.forcedShutdownDuration\s*\(\s*Duration\.ZERO\s*\)/u,
      `${path} immediate force boundary`);
    requirePolicySetterCount(text, 'gracefulShutdownDuration',
      `${path} unique graceful policy setter`, 1);
    requirePolicySetterCount(text, 'forcedShutdownDuration',
      `${path} unique forced policy setter`, 1);
    for (const setter of ['startupTimeout', 'startupCancellationTimeout'])
      requirePolicySetterCount(text, setter, `${path} no ${setter}`, 0);
    requirePattern(text, /\.\s*noStartupTimeout\s*\(/u,
      `${path} no unbounded-startup override`, 0);
  }

  for (const path of [
    'soak/src/test/java/com/soklet/McpCrossFeatureSoakTests.java',
    'soak/src/test/java/com/soklet/McpLocalizationSoakTests.java',
  ]) {
    const rawText = texts.get(path);
    if (rawText === undefined) fail(`Missing ${path}.`);
    const text = maskJavaSource(rawText);
    requirePattern(text,
      /\.startupTimeout\s*\(\s*Duration\.ofSeconds\s*\(\s*30\s*\)\s*\)/u,
      `${path} 30-second startup timeout`);
    requirePattern(text,
      /\.startupCancellationTimeout\s*\(\s*Duration\.ofSeconds\s*\(\s*2\s*\)\s*\)/u,
      `${path} 2-second startup cancellation`);
    requirePattern(text,
      /\.gracefulShutdownDuration\s*\(\s*PROFILE\.gracefulShutdownDuration\s*\(\s*\)\s*\)/u,
      `${path} profile graceful wiring`);
    requirePattern(text,
      /\.forcedShutdownDuration\s*\(\s*PROFILE\.forcedShutdownDuration\s*\(\s*\)\s*\)/u,
      `${path} profile forced wiring`);
    for (const setter of ['startupTimeout', 'startupCancellationTimeout',
      'gracefulShutdownDuration', 'forcedShutdownDuration']) {
      requirePolicySetterCount(text, setter, `${path} unique ${setter}`, 1);
    }
    requirePattern(text, /\.\s*noStartupTimeout\s*\(/u,
      `${path} no unbounded-startup override`, 0);
  }
  const cross = maskJavaSource(texts.get(
    'soak/src/test/java/com/soklet/McpCrossFeatureSoakTests.java'));
  requirePattern(cross,
    /int\s+expectedGenerations\s*=\s*1\s*\+\s*PROFILE\.shutdownCycles\(\)\s*;/u,
    'MCP cross-feature warmup-plus-cycle generation count');
  requirePattern(cross,
    /try\s*\(\s*Soklet\s+warmupSoklet\s*=\s*crossFeatureSoklet\(/u,
    'MCP cross-feature single warmup owner');
  requirePattern(cross,
    /for\s*\(\s*int\s+shutdownCycle\s*=\s*0\s*;\s*shutdownCycle\s*<\s*PROFILE\.shutdownCycles\(\)\s*;\s*shutdownCycle\+\+\s*\)\s*\{/u,
    'MCP cross-feature profile-bounded shutdown cycle loop');
  requirePattern(cross,
    /try\s*\(\s*Soklet\s+cycleSoklet\s*=\s*crossFeatureSoklet\(/u,
    'MCP cross-feature fresh owner per shutdown cycle');
  requirePattern(cross, /\bcrossFeatureSoklet\s*\(/u,
    'MCP cross-feature exact two entries plus helper declaration', 3);
  requirePattern(cross, /\bSoklet\s*\.\s*fromConfig\s*\(/u,
    'MCP cross-feature exact owner construction site');
  requirePattern(cross, /\bSokletSimulator\s*\.\s*run\s*\(/u,
    'MCP cross-feature exact simulator lifecycle entries', 4);
  requirePattern(cross,
    /stopThread\.join\s*\(\s*PROFILE\.gracefulShutdownDuration\s*\(\s*\)\s*\.plus\s*\(\s*PROFILE\.forcedShutdownDuration\s*\(\s*\)\s*\)\s*\.plus\s*\(\s*PROFILE\.settleTimeout\s*\(\s*\)\s*\)\s*\.toMillis\s*\(\s*\)\s*\)/u,
    'MCP cross-feature stop-thread composed bound');
  const localization = maskJavaSource(texts.get(
    'soak/src/test/java/com/soklet/McpLocalizationSoakTests.java'));
  requirePattern(localization,
    /runSimulatorWorkload\(configFactory,\s*server,\s*state,\s*1,\s*1,\s*[^,()]*\)\s*;/u,
    'MCP localization warmup generation');
  requirePattern(localization,
    /runSimulatorWorkload\(configFactory,\s*server,\s*state,\s*PROFILE\.concurrentClients\(\),\s*PROFILE\.cyclesPerClient\(\),\s*[^,()]*\)\s*;/u,
    'MCP localization measured generation');
  if ([...localization.matchAll(/\brunSimulatorWorkload\s*\(/gu)].length !== 3)
    fail('MCP localization soak must have exactly two workload calls plus one helper declaration.');
  requirePattern(localization,
    /\bSokletSimulator\s*\.\s*run\s*\(/u,
    'MCP localization exact simulator lifecycle entry');
  requirePattern(localization, /\bSoklet\s*\.\s*fromConfig\s*\(/u,
    'MCP localization no direct owner lifecycle entry', 0);
}

function specialProfileVariants(profiles, family) {
  return profiles.map((profile) => {
    let lifecycleCoreMillis;
    let runControlTimeoutMillis;
    let settleTimeoutMillis;
    if (family === 'http') {
      lifecycleCoreMillis = 3_000;
      runControlTimeoutMillis = profile.values['http.runTimeoutMillis'];
      settleTimeoutMillis = profile.values['http.settleTimeoutMillis'];
    } else if (family === 'realtime') {
      lifecycleCoreMillis = 3_000;
      runControlTimeoutMillis = profile.values['realtime.runTimeoutMillis'];
      settleTimeoutMillis = profile.values['realtime.settleTimeoutMillis'];
    } else {
      lifecycleCoreMillis = profile.values['mcp.gracefulShutdownMillis']
        + profile.values['mcp.forcedShutdownMillis'];
      runControlTimeoutMillis = profile.values['mcp.runTimeoutMillis'];
      settleTimeoutMillis = profile.values['mcp.settleTimeoutMillis'];
    }
    const variant = {
      lifecycleCoreMillis,
      name: profile.name,
      runControlTimeoutMillis,
      settleTimeoutMillis,
    };
    if (family === 'mcp-cross') {
      const generationCount = 1 + profile.values['mcp.shutdownCycles'];
      return {
        ...variant,
        generationCount,
        joinStopThreadBoundMillis: lifecycleCoreMillis + settleTimeoutMillis,
        sequentialLifecycleCoreMillis: generationCount * lifecycleCoreMillis,
      };
    }
    if (family === 'mcp-localization') {
      return {
        ...variant,
        generationCount: 2,
        sequentialLifecycleCoreMillis: 2 * lifecycleCoreMillis,
      };
    }
    return variant;
  });
}

function specialHarnesses(profiles) {
  const common = {
    classification: 'SETTLED_HARNESS',
    closureStatus: 'CLOSED',
    requiredAction: 'NONE',
  };
  return [
    {
      ...common,
      id: 'OFFICIAL-CONFORMANCE',
      outerGuardId: 'OFFICIAL-SHARED-SHUTDOWN-GUARD',
      path: 'conformance/official/run.mjs',
      policyVariants: [{
        forcedShutdownMillis: 1_000,
        gracefulShutdownMillis: 5_000,
        name: 'official',
        runningStopMillis: 6_000,
        shutdownDuringStartupMillis: 7_000,
        startupCancellationMillis: 1_000,
      }],
      requiredReserveMillis: 3_000,
      settledScope: 'FULL_OFFICIAL_SHUTDOWN_CONTROL',
    },
    {
      ...common,
      id: 'HTTP-SOAK',
      fullMethodOuterGuard: null,
      path: 'soak/src/test/java/com/soklet/HttpSoakTests.java',
      policyVariants: specialProfileVariants(profiles, 'http'),
      requiredReserveMillis: 0,
      settledScope: 'LIFECYCLE_CORE_ONLY',
    },
    {
      ...common,
      id: 'MCP-CROSS-FEATURE-SOAK',
      fullMethodOuterGuard: null,
      path: 'soak/src/test/java/com/soklet/McpCrossFeatureSoakTests.java',
      policyVariants: specialProfileVariants(profiles, 'mcp-cross'),
      requiredReserveMillis: 0,
      settledScope: 'LIFECYCLE_CORE_AND_JOIN_ONLY',
    },
    {
      ...common,
      id: 'MCP-LOCALIZATION-SOAK',
      fullMethodOuterGuard: null,
      path: 'soak/src/test/java/com/soklet/McpLocalizationSoakTests.java',
      policyVariants: specialProfileVariants(profiles, 'mcp-localization'),
      requiredReserveMillis: 0,
      settledScope: 'LIFECYCLE_CORE_ONLY',
    },
    {
      ...common,
      id: 'REALTIME-TRANSPORT-SOAK',
      fullMethodOuterGuard: null,
      path: 'soak/src/test/java/com/soklet/RealtimeTransportSoakTests.java',
      policyVariants: specialProfileVariants(profiles, 'realtime'),
      requiredReserveMillis: 0,
      settledScope: 'LIFECYCLE_CORE_ONLY',
    },
  ];
}

function classificationPathMap(classifications) {
  if (!Array.isArray(classifications)) fail('classifications must be an array.');
  const byPath = new Map();
  const ids = new Set();
  for (const [index, row] of classifications.entries()) {
    exactFields(row, ['classification', 'closureStatus', 'id', 'paths',
      'rationale', 'requiredAction'], `classification row ${index}`);
    if (ids.has(row.id)) fail(`Duplicate classification ID ${row.id}.`);
    ids.add(row.id);
    if (!CLASSIFICATIONS.has(row.classification))
      fail(`Unknown classification ${row.classification}.`);
    if (!CLOSED_STATUSES.has(row.closureStatus))
      fail(`Classification ${row.id} is unresolved: ${row.closureStatus}.`);
    if (row.requiredAction !== 'NONE')
      fail(`Classification ${row.id} retains required action ${row.requiredAction}.`);
    if (!Array.isArray(row.paths) || row.paths.length === 0 || !row.rationale)
      fail(`Classification ${row.id} is incomplete.`);
    const sorted = [...row.paths].sort(asciiCompare);
    compareJson(row.paths, sorted, `classification ${row.id} path order`);
    for (const path of row.paths) {
      if (byPath.has(path)) fail(`Discovery path has duplicate classifications: ${path}.`);
      byPath.set(path, row.classification);
    }
  }
  return byPath;
}

function verifyClassifications(document, discovery, texts, scopePaths) {
  const byPath = classificationPathMap(document.classifications);
  compareJson([...byPath.keys()].sort(asciiCompare), discovery.paths,
    'Explicitly classified discovery path union');
  const junitPaths = [];
  for (const [path, classification] of byPath) {
    const text = texts.get(path);
    if (text === undefined) fail(`Classified discovery path is missing: ${path}.`);
    if (classification === 'JUNIT_LIFECYCLE') {
      junitPaths.push(path);
    } else if (classification === 'REVIEWED_DISCOVERY_ONLY'
        && scopePaths.includes(path)) {
      fail(`Java test lifecycle path cannot be REVIEWED_DISCOVERY_ONLY: ${path}.`);
    } else if (classification === 'CONSTRUCTION_ONLY') {
      if (!LIFECYCLE_SIGNAL_PATTERN.test(text)
          || LIFECYCLE_EXECUTION_PATTERN.test(text))
        fail(`CONSTRUCTION_ONLY path has no lifecycle construction or has execution: ${path}.`);
    } else if (classification === 'PROCESS_HARNESS' && path !== LOCAL_SIMULATOR_PATH) {
      fail(`Unexpected PROCESS_HARNESS path: ${path}.`);
    } else if (classification === 'SETTLED_HARNESS'
        && !SPECIAL_HARNESS_PATHS.includes(path)) {
      fail(`Unexpected SETTLED_HARNESS path: ${path}.`);
    } else if (classification === 'SETTLED_HARNESS_SUPPORT'
        && !path.startsWith('conformance/official/public-fixture-')) {
      fail(`Unexpected SETTLED_HARNESS_SUPPORT path: ${path}.`);
    }
    if (path.startsWith(SOAK_ROOT) && LIFECYCLE_EXECUTION_PATTERN.test(text)
        && !SPECIAL_HARNESS_PATHS.includes(path)) {
      fail(`Unsettled lifecycle-capable soak path: ${path}.`);
    }
  }
  junitPaths.sort(asciiCompare);
  compareJson(junitPaths, scopePaths,
    'JUnit lifecycle classification and method-scope path union');
  return verifyJunitLifecyclePaths(junitPaths, texts);
}

function verifyBaseline(documentRows, observed, texts) {
  if (!Array.isArray(documentRows)) fail('acceptedD1Occurrences must be an array.');
  const observedIdentities = observed.map(baselineIdentity);
  const documentIdentities = documentRows.map((row) => ({
    id: row.id,
    line: row.line,
    lineSha256: row.lineSha256,
    occurrenceIndex: row.occurrenceIndex,
    path: row.path,
  }));
  compareJson(documentIdentities, observedIdentities,
    'Accepted-D1 shutdownTimeout identity set');
  for (const [index, row] of documentRows.entries()) {
    exactFields(row, ['classification', 'closureStatus', 'id', 'line',
      'lineSha256', 'occurrenceIndex', 'path', 'rationale', 'requiredAction'],
    `accepted-D1 row ${index}`);
    if (!CLOSED_STATUSES.has(row.closureStatus))
      fail(`Accepted-D1 row ${row.id} is unresolved.`);
    if (!BASELINE_ACTIONS.has(row.requiredAction))
      fail(`Accepted-D1 row ${row.id} has unknown required action.`);
    const source = observed[index];
    const allowedExclusion = baselineExclusionAllowed(source);
    if (row.classification === 'REVIEWED_EXCLUSION') {
      if (!allowedExclusion || row.requiredAction !== 'NONE'
          || row.closureStatus !== 'NOT_APPLICABLE')
        fail(`Accepted-D1 exclusion is not independently allowed: ${row.id}.`);
    } else {
      if (allowedExclusion || row.classification !== 'LIFECYCLE_MIGRATION'
          || row.requiredAction === 'NONE' || row.closureStatus !== 'CLOSED')
        fail(`Accepted-D1 migration row is malformed: ${row.id}.`);
    }
    if (!row.rationale) fail(`Accepted-D1 row ${row.id} lacks rationale.`);
  }

  const current = currentLegacyOccurrences(texts);
  for (const row of current) {
    if (!currentLegacyExclusionAllowed(row))
      fail(`Surviving non-excluded shutdownTimeout occurrence: ${row.path}:${row.line}.`);
  }
  return current.map(currentLegacyIdentity);
}

function verifyClosedRows(rows, label) {
  for (const row of rows) {
    if (row.closureStatus !== 'CLOSED' || row.requiredAction !== 'NONE')
      fail(`${label} ${row.id} is unresolved.`);
  }
}

// Read-only evidence collection is exported for the self-test and for human
// review tooling. It intentionally does not choose classifications or actions.
export function collectLifecycleBoundHarnessEvidence({
  root,
  junitLifecyclePaths = [],
  acceptedBaselineCommit = ACCEPTED_D1_COMMIT,
} = {}) {
  const resolvedRoot = resolve(root
    ?? join(dirname(fileURLToPath(import.meta.url)), '..'));
  const texts = currentTexts(resolvedRoot);
  const baseline = acceptedBaselineOccurrences(resolvedRoot,
    acceptedBaselineCommit);
  const currentLegacy = currentLegacyOccurrences(texts);
  for (const row of currentLegacy) {
    if (!currentLegacyExclusionAllowed(row))
      fail(`Surviving non-excluded shutdownTimeout occurrence: ${row.path}:${row.line}.`);
  }
  const discovery = buildDiscoveryCensus(texts);
  const lifecycleEvidence = buildLifecycleScopeEvidence(texts);
  const lifecycleScopeObservations = lifecycleEvidence.observations;
  verifyRequiredExecutingObservations(lifecycleScopeObservations);
  const observedScopePaths = [...new Set(lifecycleScopeObservations
    .map((row) => row.path))].sort(asciiCompare);
  if (junitLifecyclePaths.length > 0)
    compareJson([...junitLifecyclePaths].sort(asciiCompare), observedScopePaths,
      'Requested and observed lifecycle JUnit paths');
  const profiles = soakProfiles(texts);
  verifyLifecycleHostWiring(texts);
  verifySpecialSourceWiring(texts);
  return {
    acceptedD1: baseline.map((row) => ({
      ...baselineIdentity(row),
      exclusionAllowed: baselineExclusionAllowed(row),
      sourceLine: row.sourceLine,
    })),
    currentLegacyExclusions: currentLegacy.map(currentLegacyIdentity),
    discoveryCensus: {
      candidateCount: discovery.candidateCount,
      candidateSha256: discovery.candidateSha256,
      candidates: discovery.candidates,
      countsByKind: discovery.countsByKind,
      pathCount: discovery.pathCount,
    },
    discoveryPaths: discovery.paths,
    javascriptGuards: javascriptGuards(texts),
    lifecycleScopeObservations,
    orphanLifecycleHelperObservations: lifecycleEvidence.orphanHelpers,
    junitGuardSummary: verifyJunitLifecyclePaths(
      observedScopePaths, texts),
    soakProfiles: profiles,
    specialHarnesses: specialHarnesses(profiles),
    standardJunitGuard: standardJunitGuard(texts),
  };
}

export function collectLifecycleClosureGapCensus({ root } = {}) {
  const evidence = collectLifecycleBoundHarnessEvidence({ root });
  const groups = {
    definiteArithmeticOverflow: [],
    policyProvenance: [],
    scannerAmbiguity: [],
    symbolicCustomWait: [],
  };
  for (const source of evidence.lifecycleScopeObservations) {
    let error;
    try {
      buildReviewedLifecycleScopeRows([source], {
        requireRegistryCompleteness: false,
      });
      continue;
    } catch (failure) {
      if (!(failure instanceof LifecycleBoundHarnessInventoryError))
        throw failure;
      error = failure.message;
    }
    const overflow = error.match(
      /\(total=(\d+), guard=(\d+), requiredReserve=(\d+)\)\.$/u);
    const common = {
      applicationRunSiteCount: source.applicationRunSiteCount,
      computedTotalMillis: overflow === null ? null
        : Number.parseInt(overflow[1], 10),
      error,
      factoryGenerationHasExecution:
        source.factoryGenerationHasExecution,
      factoryGenerationFixedControlWaitMillis:
        source.factoryGenerationFixedControlWaitMillis,
      factoryGenerationOuterTimeoutMillis:
        source.factoryGenerationOuterTimeoutMillis,
      factoryGenerationOuterTimeoutScope:
        source.factoryGenerationOuterTimeoutScope,
      factoryGenerationUnresolvedFixedControlWaitCount:
        source.factoryGenerationUnresolvedFixedControlWaitCount,
      fieldPolicyProofs: source.fieldPolicyProofs,
      fileSha256: source.fileSha256,
      fixedControlWaitMillis: source.fixedControlWaitMillis,
      fixedControlWaitSites: source.fixedControlWaitSites,
      generationSiteCount: source.generationSiteCount,
      guardMillis: source.effectiveOuterTimeoutMillis,
      id: source.id,
      line: source.line,
      literalPhasePolicies: source.literalPhasePolicies,
      outerTimeoutScope: source.outerTimeoutScope,
      path: source.path,
      policyReferenceNames: source.policyReferenceNames,
      requiredReserveMillis: overflow === null ? null
        : Number.parseInt(overflow[3], 10),
      scopeName: source.scopeName,
      scopeSha256: source.scopeSha256,
      unresolvedFixedControlWaitCount:
        source.unresolvedFixedControlWaitCount,
      unresolvedLifecycleRepetitionCount:
        source.unresolvedLifecycleRepetitionCount,
      unresolvedLifecycleReceiverCount:
        source.unresolvedLifecycleReceiverCount,
      unresolvedPolicyBuilderCount: source.unresolvedPolicyBuilderCount,
      unresolvedPolicyInstallationCount:
        source.unresolvedPolicyInstallationCount,
    };
    if (/unresolved policy|policy builders/u.test(error)) {
      groups.policyProvenance.push({
        ...common,
        suggestedMinimalRepair: 'BIND_EFFECTIVE_PHASE_POLICY',
      });
    } else if (/fixed-control waits are unresolved/u.test(error)) {
      groups.symbolicCustomWait.push({
        ...common,
        suggestedMinimalRepair: 'REVIEW_AND_BIND_CONTROL_ALLOWANCE',
      });
    } else if (overflow !== null) {
      groups.definiteArithmeticOverflow.push({
        ...common,
        suggestedMinimalRepair:
          'RAISE_GUARD_OR_BIND_NONSEQUENTIAL_BRANCH_TOPOLOGY',
      });
    } else {
      groups.scannerAmbiguity.push({
        ...common,
        suggestedMinimalRepair: /understates fixed source waits/u.test(error)
          ? 'REFRESH_SOURCE_BOUND_CONTROL_TOPOLOGY'
          : 'ADD_SOURCE_BOUND_TOPOLOGY_REVIEW',
      });
    }
  }
  for (const rows of Object.values(groups)) {
    rows.sort((left, right) => asciiCompare(left.path, right.path)
      || left.line - right.line || asciiCompare(left.scopeName,
        right.scopeName));
  }
  return {
    generatedFromCurrentTree: true,
    groups,
    summary: {
      definiteArithmeticOverflow:
        groups.definiteArithmeticOverflow.length,
      lifecycleScopeCount: evidence.lifecycleScopeObservations.length,
      policyProvenance: groups.policyProvenance.length,
      scannerAmbiguity: groups.scannerAmbiguity.length,
      symbolicCustomWait: groups.symbolicCustomWait.length,
      totalGaps: Object.values(groups).reduce((total, rows) =>
        total + rows.length, 0),
    },
  };
}

export function verifyLifecycleBoundHarnessInventory({
  root,
  inventoryPath = INVENTORY_PATH,
  expectedAcceptedBaselineCommit = ACCEPTED_D1_COMMIT,
} = {}) {
  const resolvedRoot = resolve(root
    ?? join(dirname(fileURLToPath(import.meta.url)), '..'));
  const absoluteInventory = isAbsolute(inventoryPath)
    ? inventoryPath : join(resolvedRoot, inventoryPath);
  let document;
  try {
    document = JSON.parse(readFileSync(absoluteInventory, 'utf8'));
  } catch (error) {
    fail(`Unable to read lifecycle-bound harness inventory: ${error.message}`);
  }
  exactFields(document, [
    'acceptedBaselineCommit', 'acceptedD1Occurrences', 'authority',
    'classifications', 'currentLegacyExclusions', 'discoveryCensus',
    'formatVersion', 'javascriptGuards', 'junitGuardSummary',
    'lifecycleScopes', 'orphanLifecycleHelpers', 'soakProfiles',
    'specialHarnesses',
    'standardJunitGuard',
  ], 'inventory');
  if (document.formatVersion !== 2) fail('inventory formatVersion must be 2.');
  if (document.acceptedBaselineCommit !== expectedAcceptedBaselineCommit)
    fail(`acceptedBaselineCommit must be ${expectedAcceptedBaselineCommit}.`);
  compareJson(document.authority, { path: PLAN_PATH, section: PLAN_SECTION },
    'Inventory authority');

  const texts = currentTexts(resolvedRoot);
  const baseline = acceptedBaselineOccurrences(resolvedRoot,
    document.acceptedBaselineCommit);
  const currentLegacy = verifyBaseline(document.acceptedD1Occurrences,
    baseline, texts);
  compareJson(document.currentLegacyExclusions, currentLegacy,
    'Current intentional shutdownTimeout exclusions');

  const discovery = buildDiscoveryCensus(texts);
  const lifecycleEvidence = buildLifecycleScopeEvidence(texts);
  const scopeObservations = lifecycleEvidence.observations;
  verifyRequiredExecutingObservations(scopeObservations);
  const scopePaths = [...new Set(scopeObservations.map((row) => row.path))]
    .sort(asciiCompare);
  const guard = standardJunitGuard(texts);
  compareJson(document.standardJunitGuard, guard, 'Standard JUnit guard');
  const junitSummary = verifyClassifications(document, discovery, texts,
    scopePaths);
  compareJson(document.junitGuardSummary, junitSummary,
    'Lifecycle JUnit explicit guard summary');
  const scopeSummary = verifyLifecycleScopes(document.lifecycleScopes,
    scopeObservations, texts);
  verifyOrphanLifecycleHelpers(document.orphanLifecycleHelpers,
    lifecycleEvidence.orphanHelpers, texts);

  const profiles = soakProfiles(texts);
  compareJson(document.soakProfiles, profiles, 'Checked-in soak profiles');
  verifyLifecycleHostWiring(texts);
  verifySpecialSourceWiring(texts);
  const guards = javascriptGuards(texts);
  compareJson(document.javascriptGuards, guards,
    'JavaScript lifecycle process guards');
  verifyClosedRows(guards, 'JavaScript guard');
  const special = specialHarnesses(profiles);
  compareJson(document.specialHarnesses, special,
    'V4 settled harness families');
  verifyClosedRows(special, 'Settled harness');

  // Compare the broad, line-addressed census after semantic checks so a
  // policy regression reports its actionable cause instead of only a digest
  // mismatch. The digest still closes every newly introduced candidate.
  compareJson(document.discoveryCensus, {
    candidateCount: discovery.candidateCount,
    candidateSha256: discovery.candidateSha256,
    candidates: discovery.candidates,
    countsByKind: discovery.countsByKind,
    pathCount: discovery.pathCount,
  }, 'Broad discovery census');

  return {
    acceptedD1Occurrences: baseline.length,
    classifiedPaths: discovery.pathCount,
    currentLegacyExclusions: currentLegacy.length,
    discoveryCandidates: discovery.candidateCount,
    junitLifecyclePaths: document.classifications
      .filter((row) => row.classification === 'JUNIT_LIFECYCLE')
      .flatMap((row) => row.paths).length,
    lifecycleScopes: scopeSummary.count,
    specialHarnesses: special.length,
  };
}

function parseArguments(argv) {
  const options = { reportGaps: false, root: null };
  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index];
    if (argument === '--root') options.root = argv[++index];
    else if (argument === '--report-gaps') options.reportGaps = true;
    else if (argument === '--write')
      fail('--write is intentionally unsupported; closure classifications require explicit review.');
    else fail(`Unknown argument: ${argument}`);
  }
  return options;
}

const invokedPath = process.argv[1] ? resolve(process.argv[1]) : null;
if (invokedPath === fileURLToPath(import.meta.url)) {
  try {
    const options = parseArguments(process.argv.slice(2));
    if (options.reportGaps) {
      process.stdout.write(`${JSON.stringify(
        collectLifecycleClosureGapCensus({ root: options.root }), null, 2)}\n`);
      process.exit(0);
    }
    const result = verifyLifecycleBoundHarnessInventory({ root: options.root });
    process.stdout.write('lifecycle-bound harness inventory PASS '
      + `(${result.acceptedD1Occurrences} accepted-D1 occurrences; `
      + `${result.discoveryCandidates} discovery candidates across `
      + `${result.classifiedPaths} explicitly classified paths; `
      + `${result.junitLifecyclePaths} JUnit lifecycle paths; `
      + `${result.specialHarnesses} settled harnesses)\n`);
  } catch (error) {
    process.stderr.write(`lifecycle-bound harness inventory FAIL: ${error.message}\n`);
    process.exitCode = 1;
  }
}
