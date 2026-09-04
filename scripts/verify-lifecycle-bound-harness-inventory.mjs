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
  'conformance/mcp-finite-bound-inventory.json',
  'conformance/mcp-limits-and-accounting.json',
  'conformance/mcp-privacy-boundary-inventory.json',
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
  'src/test/java/com/soklet/McpInputRequiredPublicRuntimeTests.java': [
    'aggregateInputRequestTreesFailClosedWithoutCanaryAndServerRecovers',
  ],
  'src/test/java/com/soklet/McpResourcePublicRuntimeTests.java': [
    'aggregateDynamicResourcePageFailsClosedWithoutCanaryAndRecovers',
  ],
  'src/test/java/com/soklet/McpToolOutputSanitizerPublicRuntimeTests.java': [
    'applicationResultBoundsFailClosedWithoutLeaksAndServerRecovers',
  ],
  'src/test/java/com/soklet/internal/mcp/protocol/McpHttpServerRuntimeTests.java': [
    'endpointPathBoundMatchesAndReachesTheProductionListener',
    'headerCountAndEncodedByteLimitsHaveExactListenerBoundaries',
  ],
  'src/test/java/com/soklet/internal/mcp/protocol/McpSubscriptionRuntimeBoundaryTests.java': [
    'actualSubscriptionIdMustFitTerminalBeforeAcknowledgementCommit',
  ],
  'src/test/java/com/soklet/internal/mcp/protocol/McpTransportMetricsEventRuntimeTests.java': [
    'partialRequestBodyTimeoutIsRecordedAtTheMcpBoundary',
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
  ['src/test/java/com/soklet/internal/mcp/protocol/McpHttpServerRuntimeTests.java#headerCountAndEncodedByteLimitsHaveExactListenerBoundaries#TEST', 2],
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
  ...reviewedScopeFile("src/test/java/com/soklet/AdvancedTests.java", "33067701c201e866293fbbf96e1161d14277b4df8958c1998aa797134536443d", [
    ["testSSERaceConditionOnConcurrentConnectionsAndDisconnections","4ba7f73845d5d616360c427a0d59ffcd46cae30af9e60437410f2074d0deb716",{"controlJoinMillis":10500,"controlComposition":"REVIEWED_CONCURRENT_MAX"}],
    ["testConcurrentRequestProcessing","81e7d2134b606c5eb8b9c7743e908734d4e7fa06b5e996c53b37f1800a7c6750",{"generation":{"count":3,"mode":"MIXED_MAX_PLUS_SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":45000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","requiredAction":"RAISE_OUTER_BOUND"}],
    ["testSseServerClearsCachesOnStop","0900dfd859757aae61d2d17a81bd0fd7209cd03a0841bb911c2cdb4a5d7aa7fa",{"controlJoinMillis":4000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["testLargeRequestBodyMemoryHandling","a29c5cfe2b3af88e8f4dfaa05b9d78558e0908a9d58ed102c1d25b2fdc0a2fc0",{"generation":{"count":11,"mode":"SEQUENTIAL","complete":11,"prior":10,"incomplete":1},"controlJoinMillis":100,"requiredAction":"RAISE_OUTER_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/BuiltInTransportLifecycleAdapterTests.java", "1da2d2468e84d284bde7a5b7eb102bb31d7c907045006404385f901b646c02c1", [
    ["admissionIsClosedUntilReadinessAndShutdownBeforeReadinessSealsIt","b0fbc6599143c7cf95856f40ca7a5bb2593d8ce8d79b371f3e13700f9f9b863f",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlledLifecycleCoreMillis":0}],
    ["positiveResidualAndUnknownBothRetainEvidenceWithoutRelease","eed0e5efd87ca55d8b6edd92b1e48f9eaa4bc07b044818317ba84539e7bdde0e",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlledLifecycleCoreMillis":0}],
    ["completedResultIsPublishedOnlyAfterCoordinatorRoleRelease","4a728c54f345a68a3ac1c2ead8e2a6def0076690bbb748cfe60e1d36446ec933",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlledLifecycleCoreMillis":0}],
    ["exactGenerationOperationsRejectForeignTokensWithoutMutation","ff1b277adb13768eb39a9e28b2a43a292e48b75af80cf0ca8439c542f6a8535c",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlledLifecycleCoreMillis":0}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/DefaultHttpServerTests.java", "662b6a547722286e003fcb0cb32b5ea66b03a9d7165a36878ece3853102f80ca", [
    ["earlySetupErrorRetainsIdentityCleansGenerationAndAllowsHttpRestart","8686b0fce8b5bb5579eee51892ce418c6ee7c03b1a3a013b4e7bb885e368b843",{"generation":{"count":2,"mode":"MIXED_MAX_PLUS_SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":5000}],
    ["staleHttpEventLoopFailureDoesNotClobberRestartedServer","cec3c46b78a87dd61c5fc5fe49b5ea59e9c13f4e2250a7948428bffa8c12ba4e",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/ExternallyCoordinatedTransportLifecycleAdapterTests.java", "7be4c5d1b5fe67a92e4457584bf75f6c854953f14b21daaa36521e216b1575b7", [
    ["externalGenerationDefersCommitAndAdmissionAndPublishesExactOwnerResult","6b5864c53929481b7a492d60067de53b40b8f0810b73882ec8c7aba74a4cc100",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlledLifecycleCoreMillis":0}],
    ["completedExternalGenerationPermanentlyRejectsStandaloneAndSecondOwner","4184d50695629979c4c1f07e709bc23467266d540875960529ebbf3a5fc114c4",{"generation":{"count":2,"mode":"ONE_FULL_PLUS_PREINIT_REJECTIONS","complete":1,"prior":0,"incomplete":1},"controlledLifecycleCoreMillis":0}],
    ["externalUnexpectedFailureRecordsBeforeOneOwnerCallbackWithoutCoordinating","c82d5043019d0fe7b9ab3031014025e5201fa7d3cd646e16d8c3a429d4880c4a",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlledLifecycleCoreMillis":0}],
    ["externalStartFailureRecordsExactCauseWithoutLaunchingCoordinator","e197dd306e2a0d512d6c064c5673b54a961c1566963d28a849418afc4bbadf8a",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlledLifecycleCoreMillis":0}],
    ["externalSelfStopPublishesIntentBeforeOwnerScopedWaitFailsFast","80b2f130651f5b7f7a03444787899e2b42e6e82c05f7b3ec251201b0444ae4e0",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlledLifecycleCoreMillis":0}],
    ["releaseFailureMustBeFoldedIntoDowngradedOwnerResultBeforePublication","21c32eddc52b6be4003e6b6d696c28cdd417f434c4b8d3ecd9c991d5c9bf84fb",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlledLifecycleCoreMillis":0}],
    ["ownerFallbackPublicationReleasesWaitersAfterStrictValidationFailure","4a03dabae4fd4a8b644cbfed7793159f3211b3ae203c4c64428510bd29167061",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlledLifecycleCoreMillis":0}],
    ["mcpForwardsTheExactExternalGenerationAndParticipantEvidence","19f51a2ea945084f4366567253ff6a5811055a40ecbfa3f6d491625b1eb4cfa4",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlledLifecycleCoreMillis":0}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpLifecycleB3Tests.java", "5e251cc76ab78a8cc48bd86724fed02f984619e253ea17168d2dd1fe7dc2356b", [
    ["cancelledFreshOwnerCannotMutateAnotherPreparedOwner","2b13bb8eb2777752876b34ac17a10577bdee5db3c9c98a7571afec41b341d3f3",{"generation":{"count":2,"mode":"MIXED_MAX_PLUS_SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":5000,"controlComposition":"REVIEWED_CONCURRENT_MAX"}],
    ["executableEndpointPlansAreImmutableAndFreshPerServerFactory","99724b969af4a029c883848c69861ce8de38902c8f7129f9521afd7903115a43",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["exactMcpGenerationOperationsRejectForeignTokensWithoutMutation","67aedfa580c9dd8c6514285b0dd58076b13327801868c12f4c88cd974052d148",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlledLifecycleCoreMillis":0}],
    ["fixedPortBindIOExceptionPreservesExactCauseForFreshOwnerAfterRelease","1f2e5d20223b3f85dba86d8e7316e8855e25751e8c15294670b1003b07ce9042",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["forceResponsiveHandlerIsInterruptedOnlyAfterTheGraceDeadline","52f03eb3d94266f6598738c86e9d91c33506d47dc71342533950eaf36cd495b7",{"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":1000}}],
    ["mcpFailureAndProofOrderingPreservesTheExactGenerationAndBarrier","2270ca5ea943da3ac63de613459e0b91ac70b96a3da098adb8c30e624da40b5a",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlledLifecycleCoreMillis":2000,"controlJoinMillis":5000,"controlComposition":"REVIEWED_LIFECYCLE_CORE_DEDUPLICATION"}],
    ["noncooperativeHandlerClassifiesResidualAndRetainsItsGraphAndAddress","c090937c5cb6899b7251a3fce920523de2895ca2692b8f6b6893522c5656f9d4",{"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":1000}}],
    ["oneServerStartupDoesNotMakeAnotherServerStopFailFast","6ba34566aaa3c7ce0aa398e3b675ea5c1d5491b34b9cd33e9f5356cc89f0f815",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["oneShotOwnerCannotConsumeUnexpectedGenerationBeforeExactResultPublication","8ec7c5714e6b014e07678de524cef1b8a49784ca98bdd8f04dcb66d811c62c5b",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["ownerNormalizesRetainedUnexpectedGenerationOnceBeforeRejectingRestart","2afc232071d7c006ad16f324b8abae2c0b5f5c4875b7dd64a0c0e71921c93b07",{"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":1000}}],
    ["postBindPreReadySubscriptionFailurePreservesExactIdentityAndAddress","897426ad1ff244287a6580e0e5056436155d6ad0fc52d870add5cbe2ad3b2c66",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["readyEventLoopObserverReentrantShutdownReturnsPromptlyWithoutFalseResidual","4dcf3decbf5db2363f26ff2b40ee5a77c21d08d577fc3308e50fffd67cbad07d",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":10000,"controlComposition":"REVIEWED_LIFECYCLE_CORE_DEDUPLICATION"}],
    ["startupErrorPreservesIdentityAndFreshOwnerStartsAfterNeverBoundFailure","e0b981d64b24aa620c0f977c324e8c96b151e9a7728f52856b5b583d4f125559",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["stopBeforeRuntimeInstallAndBeforeMarkReadyWinsDeterministically","dfbc3019541b94c163cf454aa3de26c895da5e635e3895327691a3b9b5ec62e9",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlledLifecycleCoreMillis":18000,"controlJoinMillis":5000,"controlComposition":"REVIEWED_LIFECYCLE_CORE_DEDUPLICATION"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpLocalizationAdversarialTests.java", "008f34ff783d7297b187244897f5619f4727dd22accbb37f1afa6c84d7acbfaf", [
    ["rejectedAndIrrelevantWorkNeverInvokesTheProvider","314ac4b16ec574dc021d47c15bf20ba61dc3e5d9a7285af060451a08a6d20977",{"generation":{"count":5,"mode":"SEQUENTIAL","complete":5,"prior":4,"incomplete":1}}],
    ["simultaneousLocaleSelectionAndInvalidationStayIsolated","3c4e2398abe4df889a36f3d3c776abbe9e6dc7553d816053322fea86ce15f524",{"controlJoinMillis":10000,"controlComposition":"REVIEWED_OVERLAP_OR_DUPLICATE"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpLocalizationFleetPublicRuntimeTests.java", "cba2a283c88859d95fab6b07815fd743604c445614e91ac334c021c507633b41", [
    ["failedFleetReloadPreservesBothOldSnapshotsAndPublishesNoInvalidation","e7c2ac03e01a3e70a99c29e5ca27b2e8fd38bac9ed21b6ae0ad9bc53b2e04159",{"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":1000},"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["nodeLossAndSubscriptionReconnectNeedNoSessionRecoveryAndReleaseFleetResources","c035d59ba51bb1f0287d2e6e652fb4f6553014358808d69fdbebcf482d8045c3",{"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":1000},"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["rollingActivationAllowsRevisionDriftBetweenNodesButNeverWithinAResponse","a2caf2b8dce3a94d17d9ee32036a5e187eea3f0b60a91864a22375033347f7a3",{"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":1000},"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpLocalizationHttpBoundaryTests.java", "487254f047fcae5de6fe5e5abbba1edb8a7ed2a12059ddcbdf9b0ef361d023a9", [
    ["cacheableResultsAreClampedToPrivateZeroExactlyWhenLocalized","32ef05135a745decafde402ebb331d54b75a65bf4686c9c702398ef863713a35",{"generation":{"count":5,"mode":"SEQUENTIAL","complete":5,"prior":4,"incomplete":1},"controlJoinMillis":50000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpLocalizationReloadRuntimeTests.java", "06758ac825c42ebab66200590d6e60d2913bece4d0e2b64f87772810401ffb6e", [
    ["invalidateCatalogsDeliversOneCoarseInvalidationPerLocalizedFamily","9a9b94f185715a3af3a9cd3d1ac50eca08aad5aba4309333eceec4d0d84b5e70",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":20000}],
    ["familiesWithoutALocalizedCatalogAreNeitherAcknowledgedNorDelivered","ece1c380529b4b333c9da075b0caf4562a94f7e6957aedacc29a44bbfff4cb1a",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":10150,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["discoveryAdvertisesListChangedOnlyForLocalizedCatalogsWithSubscriptions","e4d211e014f2602245e293926882399dec9a8f53661d44c8a4958233ab8d3cd6",{"generation":{"count":3,"mode":"SEQUENTIAL","complete":3,"prior":2,"incomplete":1}}],
    ["aStaleLocalizedTerminalIsReleasedByInvalidation","ba82f6d9fcf719f7ed69298ae369c9a77b3ab72f388970bd368958c17e8156c4",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":15000}],
    ["invalidationDuringTerminalPreRenderCannotInstallTheOldSnapshot","901d64011ac9e299a70b4ebf480eae4f73b17229536291e340ab4191070c0a95",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":20000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["shutdownDuringTerminalPreRenderCannotCommitARejectedSubscription","e84f16d5d275ad30d9310da44147e6d986fb2a3c0ae2bc9e4124bcbf823f65f6",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":45000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["twoNodesInvalidateIndependently","333da40c6de0409d026001daf4659929cfbee600c5ad47593fef0faf52774c37",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":20150,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["mixedLocalizedEndpointsShareOnePublisherAndFanOutToEveryEndpoint","0049044b08e4b5dc64d4e611b882f3f68f92bfb26e8cc31550a497ae1017050a",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":30000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpLocalizationRenderingRuntimeTests.java", "ab5eeb825feb28a12b640f303f42c544b1eee9a50c36fdd7b9e9b505bd9886bf", [
    ["everyNonDiscoveryCatalogRendersItsPlannedSlotsLocalized","6591f86b5c6231b98c999ddc29478ce7ab48d90ed90815db36579804d1e15338",{"generation":{"count":4,"mode":"SEQUENTIAL","complete":4,"prior":3,"incomplete":1}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpMetricsEventDeliveryPublicRuntimeTests.java", "de540f18839832f8748a0f44ac3da506845e18dc6d5f446c64d6a7d63c0b79cb", [
    ["adapterStopRequestQueuesStoppedBeforeFreshOwnerStart","46e1dd7468b75bd78ed002513bcdb3b19070cce5ecd7785be71d18f3de07b383",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["failedListenerStartEmitsStoppedWithoutStagedStarted","9f7e3b316a77c3c94758a5cd9c8a3eadfaacf700b7c5e22fccb2ecf43dea7f1f",{"generation":{"count":3,"mode":"SEQUENTIAL","complete":3,"prior":2,"incomplete":1}}],
    ["freshOwnersEmitExactStartedStoppedGenerationsAndShutdownNoOps","8a1c6d8cd86167e923a01d2db875cb1fda367cedc08dd7821889bc89ba3f3319",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["unexpectedTerminationOrdersNormalizedStopBeforeFreshOwnerStart","90cb70ed200f06d85e999d10083dc95a7bc34236ae7d0a23f9e76db79c1a6938",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpMirroredHeaderPublicRuntimeTests.java", "c0d33fe59ec687e378412c4b307ebe0f8865e15b5d7c9f797d82afc6a3e9e4ef", [
    ["diagnosticQuotaIsSharedAcrossEndpointsAndIsolatedAcrossOwners","d2d7f3dd310037960d9b05c00304b1d217ef80b4d2d21582ebfbcfcbca2ef164",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpNotificationPublicRuntimeTests.java", "f449de818009439df52cdcd0176ab03f2054ab58c5f9836e4902bf0c0267d6c2", [
    ["inboundNotificationsNeverEmitJsonRpcBodiesOrReachApplicationHandlers","d71fe39d6491773f4b75bf622077fb8dfc63b29ad8ce242208c639b16dbb4b82",{"controlJoinMillis":50000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["outboundFrameworkNotificationsOmitIdsWhileTerminalResponsePreservesRequestId","b1fcd2a62f2947faf55f09cf862ee9a5eabcb8df2bf792a2bd4a4d1732f518f7",{"controlJoinMillis":35000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpProtectionTraceDiagnosticsPublicRuntimeTests.java", "4eddfcfd2b6e4136f243ed1ef75b6170a941f28d049318aac710ed3be7969a7c", [
    ["liveRotationsChangeOnlyFreshSnapshotsAcrossStopAndRestart","e07d2d1b4e3652fc8d560cf3b2af293b22344266713f052d99b30dce3721b398",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpRateLimitIdentityPublicRuntimeTests.java", "99bc8704da120c2bb96692ccbcdacf9963231c657afb9afa58b3a530473dc26b", [
    ["allowlistedSocketPeerCanSelectForwardedIpPartitions","a204fc9c5ca2beb351f9ab37986c3d89aed0c82e8c05add8d0903a35622b7856",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpRateLimitPipelinePublicRuntimeTests.java", "4bf92051aded89fb06c4a2f35d5961d05837c0af4b78bc4a519814dbbb9c839d", [
    ["successfulChargesAreRetainedAfterEveryDownstreamFailure","6021dbe8040107292a2b90efe2a60899cf07dc1c61690ee44de7c463fb998cf0",{"generation":{"count":6,"mode":"SEQUENTIAL","complete":6,"prior":5,"incomplete":1},"controlJoinMillis":30000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpRequestObservationPublicRuntimeTests.java", "83db5397129dfa25ee0f9edf2232f6c8499494c2272cfcd7ab0804fba17cdf61", [
    ["defaultOffAndIndependentRawIdOptInHaveExactLogContracts","504d5497daccb916d6317875e0a39beecbc2389fdde77e7e547a24f67e370ea0",{"generation":{"count":3,"mode":"SEQUENTIAL","complete":3,"prior":2,"incomplete":1}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpRequestStatePublicRuntimeTests.java", "bb71a4923610a92a710aae90d2165b6d9c27aac250bc1e48e4eb5e7b2001adcd", [
    ["frameworkProtectedStateContinuesAcrossInstancesOnlyWithinItsKeyAndAuthorizationPartition","f2c0e7241e6f4e200429c4f516d3a0b9c0fc8237ff726deea02af8fc8a7632c7",{"generation":{"count":4,"mode":"SEQUENTIAL","complete":4,"prior":3,"incomplete":1}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpServerPublicRuntimeTests.java", "58dbb52cb35efbd8b020b1a24c2b61d4ddf756585717648e1158613aa45567d7", [
    ["executionConfigurationValidatesAndOwnsOneExecutorPerGeneration","a4fd9b3313bb5ff0e662202ade77a6e536fb487b9430fbc772144c06d46c6fda",{"generation":{"count":3,"mode":"SEQUENTIAL","complete":3,"prior":2,"incomplete":1}}],
    ["explicitRejectAllCorsSuppressesTheOmittedConfigurationDiagnostic","d12aa4815ef29d64a7f3e33ea73207dece772245ed90ea858a1e09976da38281",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["failedFixedPortBindLeavesResourceAvailableToFreshOwnerAfterRelease","28f43e35f69d502ac0dd63d839344e4fb742a5634f0e5acc7242b2b9f3db4f66",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["freshGenerationPublishesNeverBoundAddressBeforeStartupCallbacks","819d42facf4cf68d458fde6eea570dbbbda4809af81b84028feeba66a1450d51",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["freshOwnerStartsAfterUnexpectedMcpListenerTermination","ef1582dbf323cadef1164078316f2e44960e7818bdb70c4c81f00c7082108847",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["omittedCorsDiagnosticIsExactAndOncePerSuccessfulSokletGeneration","9e5320a6926c863a2c504795bd37d7b7194c16a65ebec478eca5b031e732ec68",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["sokletOwnedPortZeroGenerationsPublishImmutableDiagnosticSnapshots","1b326aad87bfce59118ba53f4899ddda2c7f5679726f108ec7a5c4cd1781a4e7",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpShutdownObservabilityTests.java", "59d647da35f6082edd42d01686be83d197e1f19a93715d0dad6e2cdcaf94db10", [
    ["rejectedUnexpectedRestartDoesNotDuplicateBeforeFreshOwner","f4e7d5392de0df5da2dd468969ae18d43b2188c81d87fa21afabdebd1d4ef9ef",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["unexpectedListenerTerminationAndFreshOwnerHaveExactParity","658fb897e3bcf0c822984d9d201f5c79c95dee91bc0957419d653a984b2c9b14",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpSimulatorEveryOperationTests.java", "fd4bc481f484d5690bfd25bd9df9ccdfb2f3b1a39558f655117534c94e54b4b3", [
    ["recognizedRequestMethodsReplayExactJsonOrSseShapes","fd8525398e79de30f3df4374a71b0e22ecd20453ea56f9eee53d1d643b5f97f8",{"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":1000},"dynamicNodeCount":9,"controlJoinMillis":20000,"controlComposition":"REVIEWED_DYNAMIC_NODE_MAX"}],
    ["cancellationNotificationIsAcceptedAndIgnoredWithoutTerminatingItsTargetSimulation","a747cd037c176ec1cbfaee262bf569cfdc903743121a1a6223367901ecb1c07d",{"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":1000},"controlJoinMillis":40000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["concurrentRecognizedOperationReplayIsIsolatedAndExactlyDrained","2cc66528a385ee5a761a888998a6773b9b41f7c3cc9303b51b787b370ef3bebd",{"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":1000},"controlJoinMillis":25000,"controlComposition":"REVIEWED_OVERLAP_OR_DUPLICATE"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpSimulatorPublicRuntimeTests.java", "16f791eb79e09f7b79c5b4e51a1ee7b90b3f952f2830d3be0ae70aec31c242b9", [
    ["concurrentSimulationsRemainRequestIsolatedAndDrainExactlyOnce","35c0b468159f9ad950f4ddbaf882ba4dc80168e6f04b6696052ec9fbb07e3264",{"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":0,"forcedShutdownMillis":250}}],
    ["defaultLoopbackHostPolicyRequiresLiteralConfiguredPortZero","aa2203dc5dc3ec6926e23bf277d876e69081e2c059d4665e5eb72db9d6ac0443",{"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":0,"forcedShutdownMillis":250}}],
    ["malformedAndRejectedSimulationsPreserveProtocolPrecedenceWithoutAdmission","9bf757a2c2002d84eeb3050603ed862368bc39e3a50a0a743fea3ea53b9abede",{"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":0,"forcedShutdownMillis":250}}],
    ["mcpSimulationBuffersStreamItemsAndClosesExplicitly","e3bfe064a1680f076a0654326636740024f7e2165f5891c55ab18e1963b2f7bb",{"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":0,"forcedShutdownMillis":250},"controlJoinMillis":10000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["mcpSimulationCompletionRetainsStreamCaptureFailures","854ce2e7788159ff1c63b8b30363bb100ef73626347bb4b871cab191ff6c405a",{"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":0,"forcedShutdownMillis":250},"controlJoinMillis":25000,"controlComposition":"REVIEWED_OVERLAP_OR_DUPLICATE"}],
    ["multiRoundTripSimulationContinuesInputRequiredStateToDistinctCompletedRequest","9c27c0bcf37c98d4588f1c9fd48dad1084633874af1624557bfae4b74b863776",{"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":0,"forcedShutdownMillis":250},"controlJoinMillis":25000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["noncooperativeSimulationCleanupIsBoundedAndPreservesSuppression","527e1495a7b27842684e2d0ee1ab4a5100de3dadc4ba9f807e78fd52d4fedac9",{"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":0,"forcedShutdownMillis":250}}],
    ["nonDrainingCaptureLimitDoesNotBlockUnrelatedSimulationOrCreateTransportFailure","abeb07b6c813ea671430fd7d785fb78f2309ee92a7f63207d2acfbb1f26fa9a7",{"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":0,"forcedShutdownMillis":250}}],
    ["simulatorRepresentsEventStreamAsOpenMcpSimulation","db071f4429cc5b5847d612962df6c53923a32b0e860d661eed834f2bb0aef933",{"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":0,"forcedShutdownMillis":250}}],
    ["simulatorScopeExitCancelsOutstandingRequestsAndRestoresOffNetworkState","df308e9e23caa18ee4d3009c1aebcdf99def44de7c53ad62287287dab4a1edb3",{"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":0,"forcedShutdownMillis":250}}],
    ["simulatorStartsRequestAgainstConfiguredMcpServer","d5de424f835ef3af845acb3440dfdb42a4330927a2f167fc29fa53e7e77b5420",{"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":0,"forcedShutdownMillis":250}}],
    ["subscriptionReplayPreservesAcknowledgmentEventAndCancelationOrder","87d8d0144c15ee3e1506c67b7ec8134c55ed2e7b9073b1f48d062ca06cd53f0c",{"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":0,"forcedShutdownMillis":250},"controlJoinMillis":10000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["synchronousJsonSimulationUsesRealProtocolLifecycleMetricsAndBodyType","08626d3584884fca91e40c574543343f3c6023a14a6f1b224ae64fd335b158f9",{"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":0,"forcedShutdownMillis":250}}],
    ["waitOperationsHandleZeroTimeoutInterruptionAndCompletionIdempotently","0905a22496828c1974cd6cec90f5e658c4bddf3ee7aa4f34858e115155f7551e",{"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":0,"forcedShutdownMillis":250},"controlJoinMillis":20000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/ResourceLeakTests.java", "855a8af2729f3840f3ee9363408e6d9b9c952a0e2673732c8c3020de12b72723", [
    ["httpConnectionChurnReturnsResourcesNearBaselineAfterShutdown","e8fd5790bb67b858380a9d9f9bd04d52f86cf7f2b48a9b4427b4b79fed74d04d",{"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":2000}}],
    ["mcpListenerAndRequestReturnResourcesAfterCompleteShutdown","d3c8c8a38243071c8c79cc34e848dbfea5a9a67a577ec5dc907e3a5f3568f72b",{"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":2000}}],
    ["sseConnectionReturnsResourcesNearBaselineAfterShutdown","d419a07618bf5cb500274d57e20fa7cad3351f61e0321aa6c5a5e7e902c68954",{"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":2000}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SokletApplicationTests.java", "da191b72115528c27111477cd5f5cd5e3ca1688e7c6eda3e5f98e81addeb83eb", [
    ["malformedRunArgumentsDoNotConsumeTheApplicationRunClaim","1b91dc08e9965a3269b5bbcd2ce58bbbb320debf6b6f035e6fd64ea675765900",{"generation":{"count":6,"mode":"PRECOMMIT_REJECTIONS_ONLY","complete":0,"prior":0,"incomplete":0},"applicationCleanupCount":0,"incompleteBranchCleanupCount":0,"terminalReportCount":0}],
    ["failedAttemptStillConsumesTheApplicationRunClaim","5a506239dff5707e38c3b4d6a265b5d2dc4a028e8739029a7e4c21256fdafb35",{"generation":{"count":2,"mode":"PRECOMMIT_REJECTIONS_ONLY","complete":0,"prior":0,"incomplete":0},"terminalReportCount":0}],
    ["invalidInjectedEnvironmentStillConsumesTheApplicationRunClaim","f4979d57d8ab6a121e53c6bb823c0fd5b9adf843818df97436a0c330d60936db",{"generation":{"count":2,"mode":"PRECOMMIT_REJECTIONS_ONLY","complete":0,"prior":0,"incomplete":0},"terminalReportCount":0}],
    ["concurrentRunIsRejectedWhileTheFirstClaimIsActive","31f6dc9407b3c059ad5ff3078cab98c14beea0d8df7ba983261f9b6c3b4acee6",{"generation":{"count":2,"mode":"PRECOMMIT_REJECTIONS_ONLY","complete":0,"prior":0,"incomplete":0},"terminalReportCount":0}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SokletApplicationDiagnosticsIntegrationTests.java", "1b77fd213620bd4088ce183e6afc4367829cd65f457fbaf177d6190d4b3c263f", [
    ["blockedFrameworkSetupSynthesizesFrameworkDiagnosticsAndSkipsCleanup","af6dfe671f5cdbee55a96e15ef1c30a7d0abaccfa01a35e626b17eb5ce3b96f1",{"phasePolicy":{"forcedShutdownMillis":0,"gracefulShutdownMillis":0,"startupCancellationMillis":0,"startupMillis":5000},"controlledLifecycleCoreMillis":5000,"applicationCleanupMillis":1000}],
    ["blockedNestedCustomHttpAttachProjectsBoundedTransportDiagnostics","249f2f85e0eb41339908681e4fae0cc21df3c119ea4fa18a9db1d8e1b8cd8f17",{"phasePolicy":{"forcedShutdownMillis":0,"gracefulShutdownMillis":0,"startupCancellationMillis":0,"startupMillis":5000},"controlledLifecycleCoreMillis":5000,"applicationCleanupMillis":1000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SokletApplicationObservationTests.java", "6880ff3614db6190e37ec90fb72d9340c590a3da62881abca8cad7f4a7375951", [
    ["mixedIncompleteAndNotStartedTerminalTraceIsOrderedAndComplete","3e21f5223d4f60a4ce8bf780b4597b23ebafcc80ddd4af617e815bb04895a78c",{"phasePolicy":{"forcedShutdownMillis":0,"gracefulShutdownMillis":0,"startupCancellationMillis":0,"startupMillis":5000},"controlledLifecycleCoreMillis":5000,"controlJoinMillis":20000,"controlComposition":"REVIEWED_OVERLAP_OR_DUPLICATE"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SokletDirectLateStartupIntegrationTests.java", "ede72e644c5737faba255abc533785db1d5a23bfc886664fa401c62bbdd47164", [
    ["attachmentLosingShutdownFreezeReturnsBeforeTerminalAsExactNotStarted","e8ec6aef2ccfa96fa789f4530f250689758a31b939968419c5501b9e06088664",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":0,"gracefulShutdownMillis":150,"forcedShutdownMillis":2000},"controlJoinMillis":35000}],
    ["pendingAttachProofCannotCompleteCallStillLiveAtTerminalFreeze","908d202d1782585e1ff626ada31a679e10806b73b7ad74344d214b709a212b6b",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":0,"gracefulShutdownMillis":150,"forcedShutdownMillis":2000},"controlJoinMillis":45000}],
    ["installedAttachmentGracefullyReleasedBeforeStartIsNotStarted","e7197792c9caa89933b06a58e4498cc1c69c566d23ed289dc16fd9e2ddc7ceb0",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":0,"gracefulShutdownMillis":150,"forcedShutdownMillis":2000},"controlJoinMillis":35000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
    ["installedAttachmentProvenOnlyAfterForceIsForced","538e5bee1a8e4601c4a9a5102afad130fb4d13d04140ad67e220b68d0c1ac07c",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":0,"gracefulShutdownMillis":150,"forcedShutdownMillis":2000},"controlJoinMillis":40000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
    ["installedAttachmentMissingProofIsExactUnknown","9ea885da62d43c06c1feabe496a983f6b484209ed0f73aa976f7af4621dda809",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":0,"gracefulShutdownMillis":150,"forcedShutdownMillis":2000},"controlJoinMillis":40000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
    ["pendingAttachEventsCannotOverrideThrowOrNullPrecedence","504eb58d505d5d0de6bc6a8ff6a3e59c9c695d1df77833dd61643a12375ae15a",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":0,"gracefulShutdownMillis":150,"forcedShutdownMillis":2000},"generation":{"count":4,"mode":"SEQUENTIAL","complete":4,"prior":3,"incomplete":1},"controlJoinMillis":60000}],
    ["pendingAttachProofAndFailureBecomePreReadyEventsOnlyAfterCommit","39f1cb69b4ab1ff19aec679261f27bf208e770a14bbf153acd1439d9336c3898",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":0,"gracefulShutdownMillis":150,"forcedShutdownMillis":2000},"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":60000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
    ["lateStartReturnDuringGraceCatchesUpAfterIndependentIngressQuiesce","c80e671d08c29469af51bd46e6a39ce9af5eab694c157744a407b6df797a28df",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":0,"gracefulShutdownMillis":20000,"forcedShutdownMillis":2000},"controlJoinMillis":55000}],
    ["lateStartReturnAfterGraceReceivesForceAsItsFirstUnderlyingPhase","5573c1caed665065ce5af010759bf60714977ab7d997e4cd41c65ef81e2e4238",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":0,"gracefulShutdownMillis":150,"forcedShutdownMillis":2000},"controlJoinMillis":55000}],
    ["startReturnAfterTerminalFreezeIsInertAndCannotRewriteUnknown","6684b734bd393abb015bbe408003bf20a428c2867a195e5ed40a58d31b06d907",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":0,"gracefulShutdownMillis":150,"forcedShutdownMillis":2000},"controlJoinMillis":55000}],
    ["shutdownBeforeClaimedStartWorkerEntryDeliversOneDeferredPhase","0c158c6a9d7117edacbcb7a4f13596de4f0b92fe5153bf5330effd5549062627",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":0,"gracefulShutdownMillis":20000,"forcedShutdownMillis":2000},"controlJoinMillis":40000}],
    ["rejectedStartWorkerLaunchClearsClaimAndRollsBackNotStarted","4e23453d94108875dde00a5df999d36b5966052ea7ab246ea36fd0de0aa0d6c8",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":0,"gracefulShutdownMillis":150,"forcedShutdownMillis":2000},"controlJoinMillis":15000}],
    ["catchUpFailureIsSecondaryEvidenceToExactLateStartFailure","09006ab667d5cde9cb97b11d2a9afe63946696292ac96dbfbf33af4c1ce9ee69",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":0,"gracefulShutdownMillis":20000,"forcedShutdownMillis":2000},"controlJoinMillis":45000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SokletDirectSseCompositionTests.java", "5481128e8f1b04fa9a9a508c451e181fab4cf4fde7b5903814c1340b6a0533af", [
    ["lifecycleOwningDecoratorProofCannotBeBypassedByItsDelegate","44b61c5dedaf5003d31e3210e13a0a3d0e8c6b2c11a4b4a8e5831ed8db6a05ab",{"phasePolicy":{"startupMillis":2000,"startupCancellationMillis":100,"gracefulShutdownMillis":100,"forcedShutdownMillis":100}}],
    ["transparentDecoratorSharesTheRootMemberAndRoutesTheSseSurface","7ee5fc63b29bf360446d9cca3f01ee051e340713be3eb9a86102f7c94ab4ee8d",{"phasePolicy":{"startupMillis":2000,"startupCancellationMillis":100,"gracefulShutdownMillis":100,"forcedShutdownMillis":100}}],
    ["twoLevelOwningStackRequiresEveryNestedMemberButRemainsOneParticipant","12bf89e23f0ed63426f0d279f825aca12d6a72b072aa3595aac781bedad8223f",{"phasePolicy":{"startupMillis":2000,"startupCancellationMillis":100,"gracefulShutdownMillis":100,"forcedShutdownMillis":100}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SokletDirectHttpCompositionTests.java", "76d7a658e5c39f0a3685014aa50dffefa2b3898d1ae5e45e2558af56e8fa9114", [
    ["transparentDecoratorSharesRootSignalRuntimeAndRequestPath","31650cc34a826d48d01a5ecaff17ea9772ae782d9a5e52c722afbfd65c0a8076",{"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":15000,"forcedShutdownMillis":3000}}],
    ["lifecycleOwningDecoratorRequiresDelegateAndOuterProof","ccede64a5c8aff78211793f6feef733cdc2821ff84f9b05ddc739d6c5d03f209",{"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":15000,"forcedShutdownMillis":3000},"controlJoinMillis":10000}],
    ["twoLevelOwningDecoratorsRemainOneConfiguredParticipant","baa997e98c6e21a907ceee6a4f5d915b99ad45d39f1cf865169a7086cb1db246",{"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":15000,"forcedShutdownMillis":3000},"controlJoinMillis":18000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SokletDirectTerminalPublicationTests.java", "06996f724b9b6b881b81868186e5742d8eaf59773a195944a99c74e3b0c826f5", [
    ["blockedPreRegisteredContinuationCannotStrandPrivateOrPeerOwners","07868a752d00075bf0cf1e994a19e2540402b3ffc87dc6050ee8a5bb1d48de19",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SokletDirectTerminationPrecedenceTests.java", "4803d9fb89add3afeb02b2f4d0642e1075e3aae959e34f677f02dd38381fac03", [
    ["ownerShutdownIntentWinsFormerGroupFanoutGap","da9453ddd92e3d10cfe8cb15476bf271efd399c14e41292a2edf1ac54b142ef9",{"phasePolicy":{"forcedShutdownMillis":30000,"gracefulShutdownMillis":30000,"startupCancellationMillis":30000,"startupMillis":5000},"controlledLifecycleCoreMillis":5000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SokletSimulatorIsolationTests.java", "db44d74627a39c405b9d498b7b7aca08edaab209fee1a41904b55ffd776bf891", [
    ["blockedFrameworkSetupUsesOneExactStartupAndRollbackSchedule","3e3b5ad4ea82946356b53bacd01456586c1061d238075018d12ce690f1e41dc1",{"phasePolicy":{"startupMillis":2000,"startupCancellationMillis":1000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":2000}}],
    ["concurrentFreshScopesDoNotCrossDeliverCallbacks","3ed0428c59239dff98ab82c223aa9e17fe9f6552b79d08e56810b98f98f5a1b9",{"generation":{"count":2,"mode":"CONCURRENT_OR_ALTERNATIVE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":15000,"controlComposition":"REVIEWED_OVERLAP_OR_DUPLICATE"}],
    ["customParameterAndInstanceProvidersAreFreshPerConfiguration","f10665e6611be5b47db4c751ecb9cdebeb9a2001355f48a6088f708f61c9070c",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["defaultParameterProviderBindsEachFreshConfiguration","ca8d2ec83a0012a1c1474f94d15b80bad7df3cec670935e5f0de423d6e8faabe",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["prebuiltConfigurationsUseFreshGraphsAndExposeTheirTransports","fc0c2203b90ef5d3819d3fa76038b4328ff2d75fd4bb012309f49536564077c2",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["incompleteTeardownPreservesBodyFailurePrecedenceAndFailsSuccess","ce78d713e2aef1a0af4157ae824c7b8c70d43e966f02956f1bc72e4c7be545d2",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["liveMcpStartQuiescesBeforeCancellationAndCatchesUpToForce","5d7cb7ec1f927c9944225cbef023a642d39fb5889875b8f9b6c41df19402a238",{"phasePolicy":{"startupMillis":2000,"startupCancellationMillis":1000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":2000}}],
    ["mcpParticipantStartsBeforeReadinessAndUsesLifecycleClockBudget","fe2fa891dd7246b0ad48cdf331a3e97d751f8a3c3d0a9e8ae4fdfed3169a8f59",{"phasePolicy":{"startupMillis":2000,"startupCancellationMillis":1000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":2000}}],
    ["rejectsMultipleMcpBuildsAndEscapedBuilder","1c34c9d3092443f7cd95a244fd58f78190b7958ea2eea6244c08634533a60b88",{"generation":{"count":2,"mode":"ONE_FULL_PLUS_PREINIT_REJECTIONS","complete":1,"prior":0,"incomplete":1}}],
    ["sealedScopeRetainsRejectedMcpSessionUntilRollbackTerminates","8370f6125ae8e09dee87ef3ec233df53ec965ac947704764b42cced71a952d3f",{"controlledLifecycleCoreMillis":5000,"controlJoinMillis":30000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["teardownLaunchFailureNeverReplacesPrimaryAndRetainsProofGraph","8629ccb85b1e9811aae2e75b1b11f0edd0136688c45988ab38d498e14d564ed0",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SseTests.java", "d1db8c459e2d0a29b94f7dda4ca198a2ad816c8bcd4b9b652b0d4fd3e70d1409", [
    ["staleSseAcceptLoopFailureDoesNotClobberRestartedServer","8fa72e23163470ae54388eda1298ea902441e49999401846f23fec8472dbbf65",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlledLifecycleCoreMillis":18000}],
    ["sse_startStop_doesNotHang","5cbc10d780680e3632c03d3c06e704572efa3f5b047b96b7b7f77079d67492cd",{"phasePolicy":{"startupMillis":3000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":0}}],
    ["sse_stop_allowsIsStartedDuringShutdownWait","176eb363b3c17feade38f0fc28d1bf5a68f45afe747e88e4566660c923bf5920",{"phasePolicy":{"startupMillis":3000,"startupCancellationMillis":1000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":0}}],
    ["sse_stopGracefullyShutsDownRequestHandlerExecutorBeforeInterrupting","d2ac819b64043436ab3667f9b04695649be071cc6e74b7fa264f4aceb12562ec",{"phasePolicy":{"startupMillis":3000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":0}}],
    ["sseServerCanRestartOnSamePort","f9bfbe60f17f243ea82e7d0f68abca6378dd6bcc3e10f4671bea0aa9eceeff9e",{"phasePolicy":{"startupMillis":3000,"startupCancellationMillis":1000,"gracefulShutdownMillis":5000,"forcedShutdownMillis":0},"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["sseStopDrainsQueuedEventsBeforeClosingConnection","237fa22f485ecdf5887ce86002b8d7a9c162b7a753f6a5edab1e4bea77946f8e",{"phasePolicy":{"startupMillis":3000,"startupCancellationMillis":1000,"gracefulShutdownMillis":5000,"forcedShutdownMillis":0}}],
    ["startRejectsRunningSseGenerationWhileItsStopIsInProgress","9303908c0e9f97fd45adc11e233d34ed035fef47e18574fd752838d11cafbbd7",{"phasePolicy":{"startupMillis":3000,"startupCancellationMillis":1000,"gracefulShutdownMillis":5000,"forcedShutdownMillis":0}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpHttpServerApplicationExecutionTests.java", "6458c88ff9b34bf2a88ed2ed7acefde7d538dee832d82d62b49ab7498c478d4e", [
    ["application_executor_factory_failure_restores_a_restartable_runtime","12c0b6987c528bf3716755625c2904f548e2a7d6a0ac7ab133134e0a4d19ba2b",{"generation":{"count":2,"mode":"MIXED_MAX_PLUS_SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":5000}],
    ["lifecycle_grace_preserves_active_handler_then_force_interrupts_without_promoting_queued_work","b2298faf5919d0ddfb071e8a493c18b3b8d8fafa721f0a1188fba621d341ff90",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["shutdown_reports_residual_application_work_and_blocks_restart_until_exit","0168dce25a9f381c15c6b919637388cf236960743a70f23ea37fe954c058c8af",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpHttpServerCustomHeaderTests.java", "0c68f53881e104f0291f491d391a705a749413a6722f75856f2f675497ab4349", [
    ["custom_mirror_registration_is_scoped_to_the_selected_tool","9b7a71b2e518662e1c7d6c1e50d32257fb06bf2ec3ce0f7551df037bbdc01fa5",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpHttpServerNotificationTests.java", "5533deadd8ab355b0f9daf8d58b75e90c1c0183248e92a13637f20796e26c6a9", [
    ["classified_notification_cors_matrix_preserves_headers_and_rejects_origins_early","b7e6775539d89ca7446603040c8aa4d20425cc264363f0f500d5bee301feb6ad",{"generation":{"count":6,"mode":"SEQUENTIAL","complete":6,"prior":5,"incomplete":1}}],
    ["notification_admission_outputs_fail_closed_on_reserved_codes_and_unsafe_headers","52e4c3411669eb14f543faf5f70e15c50724170cd3cd47f9639002f5c5b30cbe",{"generation":{"count":4,"mode":"SEQUENTIAL","complete":4,"prior":3,"incomplete":1}}],
    ["notification_policy_failures_fail_closed_without_a_json_rpc_body","4e66a46c96247f426624b012efbd84e2efbcc98f4209cdf864beefb7304036f6",{"generation":{"count":4,"mode":"SEQUENTIAL","complete":4,"prior":3,"incomplete":1}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpHttpServerPolicyPipelineTests.java", "0661a41233278fbb8050607916686f43985badf6aad062e63842a9f01ef282c5", [
    ["policy_null_exception_reserved_code_and_unsafe_header_fail_closed","998e3a54b55f4ff58e1b75ac8d31c00e649a2d93dc73abaddc6e31ae4cad7893",{"dynamicNodeCount":12}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpHttpServerRequestScopedSseTests.java", "7723b0d5f4f5384ba1e25ce4023b076d937a8aafd7ccc64b8071fd0a86a6c7e5", [
    ["shutdown_closes_committed_stream_and_runtime_restarts_cleanly","1b56c480e7618c105d448e1c5f45e17d4967bac50ef9f70443d8ecdf442784b4",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpHttpServerRuntimeTests.java", "6e5dac3cc8b2db8fe5d969742a53c4be930eed9248f8b3873868621544b22594", [
    ["absent_origin_policy_and_cors_hook_failures_fail_closed","4fbe4b9238dad19fbb1816f4f535c22bade382e7cfd53105c72a06c9e8da31a1",{"generation":{"count":3,"mode":"SEQUENTIAL","complete":3,"prior":2,"incomplete":1}}],
    ["alternating_mcp_instances_keep_discovery_state_independent","389ee827fb7bf2d128b64b2a0175b4ed25353576870784c57a449630cc7149d4",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["construction_does_not_bind_and_failed_start_is_restartable","9de0b2c9289c7f6e5e94e7ad1e86a6edd44fffedfd17919dc7ec76959a436fc5",{"generation":{"count":2,"mode":"MIXED_MAX_PLUS_SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":5000}],
    ["cors_preflight_fails_closed_for_authorizer_values_outside_mcp_surface","6fc8f7bc8500e1735c182d9f7e016aaa1916a15307ef9b81bf2affc5cebc00bb",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["cors_rejects_present_origins_by_default_and_reuses_shared_authorizer","634dc2554c4a27451112435f43502df7a022122e86c2598c68481b469b6cfec4",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["diagnostic_sink_failure_does_not_fail_listener_start","f23f881b2cdd4e7bd7974db7e9d24e459c89b10acbaf1c575a32ffa81ec86b77",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["disabledLifecycleUnexpectedEventLoopRetainsFailureUntilLegacyStopCleanup","93d51a00640b73a4d3c854181bdd4b74b7c2198b3c29bb8b26249c8a2a3469a5",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["explicit_cors_authorizer_suppresses_omitted_authorizer_diagnostic","080c214278dfb9c43dea9c1d92aef97f0065ca3153f5b3a9208f23340701a050",{"generation":{"count":4,"mode":"SEQUENTIAL","complete":4,"prior":3,"incomplete":1}}],
    ["headerCountAndEncodedByteLimitsHaveExactListenerBoundaries","3f4de77b9b054285149358d00300ee6610cefc0c9b5e914adeefabefeef8d378",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["lifecycle_is_idempotent_and_restartable_with_a_fresh_listener","25a32bef444ad324baab818353fdfe04747d86935f5cd351d57e5ed6bacda838",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["omitted_cors_authorizer_emits_fixed_diagnostic_once_per_successful_generation","01e503d71abc321735d18007f15f847bde458a271d4e53c737cf39b554cde7d3",{"generation":{"count":3,"mode":"MIXED_MAX_PLUS_SEQUENTIAL","complete":3,"prior":2,"incomplete":1},"controlJoinMillis":5000}],
    ["residual_admission_work_blocks_restart_until_it_really_exits","9ac3fa3e910140166d79b3f2f124407ba0a37eed81558bae8c0c90feb8834cbc",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":5000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
    ["residual_transport_is_a_stop_failure_and_blocks_restart_until_exit","5d36d301f6af6a6dcf2913d1068360d7e084c59a8d12cff1e765ef9fb09808c1",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["submit_after_stop_boundary_returns_unavailable_and_releases_lifecycle_admission","cdfd7bae3d83676f7ff223942b067874458290b828d1ed8abd8cf9908d90d277",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/StreamingResponseTests.java", "f79df4d7f52f5d456a5bd29f0aa3c774f5bc5cc74d4503912673ab4f6b6402cf", [
    ["simulator_admitted_stream_error_callbacks_survive_scope_seal","498e908723435e5d776d6b8a0a42eaafe019aaa99b1c5d899772e87ba1691044",{"controlJoinMillis":10000,"controlComposition":"REVIEWED_OVERLAP_OR_DUPLICATE"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpFinalTagGoldenWireProductionTests.java", "d00ff59610ea87f22334cc98342ea2271436ce64e6d991189b622dfc719fbe13", [
    ["checked_in_phase_5_subscription_messages_match_the_production_listener","75b9a52b0434dd7e99e77ceec4b329ec2f81cc4a0fe62167b39313f7ce6eab2a",{"controlJoinMillis":0,"controlComposition":"REVIEWED_OVERLAP_OR_DUPLICATE"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpMultiRoundTripTerminationRaceTests.java", "019aa23a6fa25dc356d0f1378551da43b2d493f681dc4d400aa633ceb879ddd2", [
    ["blockedCustomProtectorOpenMakesShutdownResidualUntilProtocolWorkExits","4e2940aadf6b81531aaf2c9fb67f63df03035d28ebd927256a287f35d629bdf2",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"requiredAction":"RAISE_OUTER_BOUND"}],
    ["blockedCustomProtectorOpenDiscardsLateResultAfterDeadlineOrDisconnect","9fe0428fbf8a051f8856858b80b43e838880af781f3cf347ae86a50a84792910",{"dynamicNodeCount":2}],
    ["blockedSealCannotPublishLateInputRequiredAndReleasesExactlyOnce","9fa9b58c00aca22633217bf700bf7e481d25fd151076c03d2621399daecb4529",{"dynamicNodeCount":3}],
    ["conditionalCapabilityHoldTerminatesWithoutProgressOrLateResult","e2a6c3af0f11bbd8c32e537b0c7209aebfeb71932f14c3f78dcf445f1d2d9bd1",{"dynamicNodeCount":2}],
    ["sameAuthenticatedStateCanBranchWhileOneFreshIdTerminates","e2658db8bc19db08af218c4aa7408c71c66d309caa4d4edfe61b30847f57274c",{"dynamicNodeCount":2}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpProtocolProfileRegistryTests.java", "f10803a58f6847de2ddb61ba61c1b70aee837f3fa0bb0e674f4fa73e26fe6bb4", [
    ["fakeProfileEntersOnlyThroughTheExplicitRuntimeTestSeam","6f7e90f54ed0a74a1f86ef1cfd4723d2b55367c9552e86498838855cf2773a1f",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpResultEnvelopeGoldenProductionTests.java", "387c9e318dbf1231c348f1bdafbe112f030f18f9844d05a561d4e2f054e82aed", [
    ["everyFrameworkAndApplicationCompleteAuthorityMatchesGoldens","762ad56432c844ade57ddcf6ea18c4e6a4987ab2bc7e7e77812b899250355473",{"generation":{"count":3,"mode":"SEQUENTIAL","complete":3,"prior":2,"incomplete":1}}],
    ["requestScopedAndSubscriptionSseTerminalsMatchGoldens","b8a14a0d39fde572ba50a01cc4545349be73db2cb727e5f919a954122c963671",{"generation":{"count":3,"mode":"SEQUENTIAL","complete":3,"prior":2,"incomplete":1},"controlJoinMillis":0,"controlComposition":"REVIEWED_OVERLAP_OR_DUPLICATE"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpSelectedProfileBindingTests.java", "1aeb5bad2a94a509063b657beddc79b05c7765da0f624189fa994eb5835b65b4", [
    ["subscriptionAndSimulationRetainTheSelectedProfileForTheirWholeLifetime","d1973555f04b5023222f6251df7ae83dc4cfd92d164cd14dfd03487778172914",{"generation":{"count":2,"mode":"MIXED_MAX_PLUS_SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlledLifecycleCoreMillis":50000,"controlJoinMillis":15000,"controlComposition":"REVIEWED_OVERLAP_OR_DUPLICATE"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpSubscriptionPublicRuntimeTests.java", "df26302bb7c18a462a4a3122287725b18c227e87a1fe1bb784a53c674a2197b4", [
    ["gracefulHttpShutdownEndsWithOnlyTheTerminalCompleteResult","fb45b5659d7297085ce685282006124e995394f55128e73d3eb5b85308bcfdb6",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["nullAndThrowingAdmissionNeverActivateOrConsumeSubscriptionQuota","8d487fe681050558d3ef47ad3736091583b3ad1da1d75509b22b140f8cba1d0a",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["publisherIdentityIsGroupedPerServerAndSharedAcrossServers","88d5a74bd6fe207fa20b977750c0d235fbc520284ace2560a8b39730e523e42c",{"generation":{"count":3,"mode":"SEQUENTIAL","complete":3,"prior":2,"incomplete":1}}],
    ["validListenUsesAdmissionAndRequestLimiterOnly","67c47e3a7e886bdb3510213238212bd95c1332361587f9dda29bdafe49c47317",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpSubscriptionRuntimeBoundaryTests.java", "8c6eb6696d70910e912bc0f4279420fd2b0c81b8bf41b946f0ac1b6b3ae5d029", [
    ["gracefulShutdownReservationBeatsConcurrentPublisherExactlyOnce","975a4aa52ecee3afb3c905969b29d88ff12e1da27d04002ecdbe3634776a2662",{"controlJoinMillis":5000,"controlComposition":"REVIEWED_OVERLAP_OR_DUPLICATE"}],
    ["blockingRegistrationCloseIsBoundedAndNeverRetriedConcurrently","e3329ccdae4d8c4e5cb6743b0fa76199f1afa3662a9e44e35279144ce9d83f40",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["deactivatedGenerationCannotPublishIntoRestartedServer","7d47ff10bbfd4624e0802078ca71b36eceec76b30496b1e967735c43fbadbdcb",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["failedRegistrationCloseIsObservableAndBlocksRestartUntilRetry","4e194cff09de95633ca26f7459406799379f5a2a4a298d777591ae264b0d71ce",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["startupFailureRollsBackRegistrationsAndCanRestart","34882cdd2bc656ee979db7386c04c51fca7542615eda6b565c16a09cc2982f9f",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["startupRollbackCannotBeHeldPastItsShutdownDeadline","6d37c78d40847738ed6540c919eae1931d923b782397bbcb48664149d109c11a",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["startupRollbackRetainsFailedCloseUntilSuccessfulRetry","e2df580afb7217d33dbdabf217dd679789b2c282e7d17f65f57f7ea1aa33cc8a",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpQueuedExecutionWinnerElectionTests.java", "4173cd9c73a2c88eb867f52cbb59a92b65a0a95c37947d56978c5fcb720c46cc", [
    ["all_queue_promotion_deadline_disconnect_linearizations_elect_exactly_one_outcome","8be5079ecfbe8cc5c5ca23e3a7f74a8fd6c54dbb81638b38ffacee243a80b996",{"generation":{"count":6,"mode":"SEQUENTIAL","complete":6,"prior":5,"incomplete":1},"controlledLifecycleCoreMillis":5000,"controlJoinMillis":240000,"requiredAction":"RAISE_OUTER_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/transport/McpTransportContainmentSpikeTests.java", "a08cc494cef036237d46afe944816fb442dad8c245d0f76cd7632e248e6b5899", [
    ["containmentMatrix","382671af5e8be26bdca44fc59df1c702342a41c0a94cf241d29964ed59c81f4e",{"dynamicNodeCount":15,"controlJoinMillis":8000,"controlComposition":"REVIEWED_DYNAMIC_NODE_MAX"}],
  ]),
  ...reviewedScopeFile("src/test/java/examples/mcp/McpLocalizedCursorFleetApplicationPatternsTests.java", "8075bcdc2fab8033e8be9f040969185a347850fb17842883a102c969f7922e71", [
    ["cursorFailuresPreserveOpaqueBytesAndCollapseToOneNeutralError","4d4627fc74692b252dd66ca031946dd91b241bd00028288411200722da0080d0",{"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":1000},"generation":{"count":10,"mode":"SEQUENTIAL","complete":10,"prior":9,"incomplete":1},"controlJoinMillis":100000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","requiredAction":"RAISE_OUTER_BOUND"}],
    ["localizedCursorCrossesNodesWithStableSnapshotLocaleRevisionAndPageBounds","c23e53ce8a2ae33e696b0652e8026318d6a5196c9f410ee66af7f989b4cc97c9",{"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":1000},"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":50000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SokletApplicationProcessTests.java", "85b493f9d67f53858a76316793df4c88ffdde37b9e0db8d487880f2cab0c8cc0", [
    ["startupFailureAndTimeoutRemainPrimaryWithIncompleteRollback","077fce9b32af9ecaabc5e1c7ae28d5aef7c9e9cf1ba9f1f63d059a0474a0778a",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":0,"incomplete":2},"applicationCleanupCount":2,"incompleteBranchCleanupCount":0,"terminalReportCount":2,"requiredAction":"RAISE_OUTER_BOUND"}],
  ]),
], 'lifecycle scope topology');

const REVIEWED_PHASE_POLICY_OVERRIDES = checkedReviewMap([
  ...reviewedPhasePolicyFile("src/test/java/com/soklet/LifecyclePolicyTests.java", "4c9f78e1940596fb1666dfc588fa351dd52618ab783623d7458563ae659328c7", [
    ["defaultPolicyHasFourFiniteTimeouts","2ec478d330452750459eeae6c9137894c3616d515f468d39033f789276b36cd4",{"forcedShutdownMillis":3000,"gracefulShutdownMillis":15000,"startupCancellationMillis":2000,"startupMillis":30000}],
    ["negativeAndNanosecondOverflowingTimeoutsAreRejected","5e09609efbdc80c4bca9d987e57fd31bb400ae72fe94586f3265640c211609fd",{"forcedShutdownMillis":3000,"gracefulShutdownMillis":15000,"startupCancellationMillis":2000,"startupMillis":30000}],
    ["nullRestoresEachBuiltInDefault","70e57c423a1c12469ae46b5767b017a23f583e9bcb790522f57d5a7b305383c3",{"forcedShutdownMillis":3000,"gracefulShutdownMillis":15000,"startupCancellationMillis":2000,"startupMillis":30000}],
    ["zeroRemainsAnImmediateBoundary","e137b16cbc06b0fe4d5700ea7053284884ded79e7d3ef904b71c956e58859323",{"forcedShutdownMillis":0,"gracefulShutdownMillis":0,"startupCancellationMillis":0,"startupMillis":0}],
  ]),
  ...reviewedPhasePolicyFile("src/test/java/com/soklet/McpHandlerMetricsObservabilityTests.java", "6d60b09d4718e7d58c08066caa841068f084af101db568ea11781d21c74d2b14", [
    ["defaultCollectorAggregatesConfiguredZerosRendersFiltersAndResets","1519ae9fd7f36f20cbb74dfcc21cb194cd490754293ccacd40e866037f45d0c9",{"forcedShutdownMillis":3000,"gracefulShutdownMillis":15000,"startupCancellationMillis":2000,"startupMillis":30000}],
    ["sokletOwnedSaturatedListenerEmitsExactServerWideTransitions","4aea13bb236c46f47cd6292abcb1b30ed6170f9a95ac5a9ecc0989ae252ed635",{"forcedShutdownMillis":3000,"gracefulShutdownMillis":15000,"startupCancellationMillis":2000,"startupMillis":30000}],
    ["queuedDeadlineDequeuesWithoutExecutionAndRetainsActiveGauge","072447bdc3fac24aeb356e1707048174fa8010606d15ca4ef6dd5ac6e4986ce4",{"forcedShutdownMillis":3000,"gracefulShutdownMillis":15000,"startupCancellationMillis":2000,"startupMillis":30000}],
    ["queuedDisconnectDequeuesWithoutStartingHandler","df914124553ef923f5a9f5fdb5856b9b2695dc0ec87ccc96af6ec85f595ce1d0",{"forcedShutdownMillis":3000,"gracefulShutdownMillis":15000,"startupCancellationMillis":2000,"startupMillis":30000}],
    ["managedResidualShutdownDequeuesAndFreezesGaugeAcrossLateExit","4681101ac6e14f0dde692d5ba2d481450422cf0d7bd7e381c309949dce6ffea1",{"forcedShutdownMillis":100,"gracefulShutdownMillis":100,"startupCancellationMillis":100,"startupMillis":5000}],
    ["managedStopDefersQueueAndExecutionCallbacksBeyondLifecycleLocks","7c141d8300239299c62293fa3562bfecd96042ddf000e1c8b3f69add97bd77b2",{"forcedShutdownMillis":3000,"gracefulShutdownMillis":100,"startupCancellationMillis":100,"startupMillis":5000}],
    ["unexpectedTerminationDefersQueueCallbackAndFreezesTerminalGauge","4c0c33330fbd8c8c8d749894f8267d1a569d6fd5e75537c1cfd597c9f1bd6ba4",{"forcedShutdownMillis":100,"gracefulShutdownMillis":100,"startupCancellationMillis":100,"startupMillis":5000}],
    ["handlerMetricsCollectorFailuresAreContainedAndLogged","6064ee632f9baf05b0ebcbd977078adf42c02bcfbe724dd2994092afc324bd54",{"forcedShutdownMillis":3000,"gracefulShutdownMillis":15000,"startupCancellationMillis":2000,"startupMillis":30000}],
  ]),
  ...reviewedPhasePolicyFile("src/test/java/com/soklet/McpHandlerQueueDiagnosticsPublicRuntimeTests.java", "428bbfb015bcef7dc1d208bfbd417226ef408b7530c6a75e99e1718b45cfab36", [
    ["configuredValuesAndZeroLoadRemainStableAcrossFreshCleanOwners","694802f3c6e168d727ac1e01ac3718cda27a15d4b82324da139ac3d9f34853d6",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["crossEndpointSaturationPublishesRetainedAndBoundedConcurrentTuples","bf67fbfbb7bc74dd14f9e811756a8a425fe2242c91e026a347f5df08f6144250",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["residualStopRetainsOneActiveAndDrainsQueueUntilLateExit","b87fc0c5be57b5735e3ffef9568f235a5ffad7df06ce578207a33b52ab894453",{"forcedShutdownMillis":100,"gracefulShutdownMillis":100,"startupCancellationMillis":100,"startupMillis":5000}],
  ]),
  ...reviewedPhasePolicyFile("src/test/java/com/soklet/McpLifecycleB3Tests.java", "5e251cc76ab78a8cc48bd86724fed02f984619e253ea17168d2dd1fe7dc2356b", [
    ["unaryAdmissionIsGenerationScopedAndReleasedExactlyOnce","f7844c6fcc8713e748969679b7e07e43da22b1cb8fe047b426bc973a6874287a",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["cooperativeHandlerOutlivesPromptStreamClosureAndDrainsGracefully","0464bd371f029731ebf7e0c29755213399bd9468385cc7370108fa3e4db6d536",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["unexpectedEventLoopFailureFencesBeforeProofAndRetainsAddress","e5a4fc15428bd0b3ab87b7430a0f2ebf6281a21282fe4905918703b64f5b6f52",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["eventLoopFailureAfterRequestedStopRemainsOrthogonalEvidence","5e81f42f5a8864e8ccbe6f691973b8ee5c5a8f1cf7eb2ff973b136ab91ae1a3e",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["oneShotOwnerCannotConsumeUnexpectedGenerationBeforeExactResultPublication","8ec7c5714e6b014e07678de524cef1b8a49784ca98bdd8f04dcb66d811c62c5b",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["unexpectedEventLoopSignalsFailureBeforeAdmittedHandlerTeardown","9c870c1286f42d8eb8fb21bed2610550cc1a8bfbf5826f13bc33d69fcebe8be8",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["startupErrorPreservesIdentityAndFreshOwnerStartsAfterNeverBoundFailure","e0b981d64b24aa620c0f977c324e8c96b151e9a7728f52856b5b583d4f125559",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["fixedPortBindIOExceptionPreservesExactCauseForFreshOwnerAfterRelease","1f2e5d20223b3f85dba86d8e7316e8855e25751e8c15294670b1003b07ce9042",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["postBindPreReadySubscriptionFailurePreservesExactIdentityAndAddress","897426ad1ff244287a6580e0e5056436155d6ad0fc52d870add5cbe2ad3b2c66",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["idleSubscriptionClosesPromptlyWithServerStoppedAndNoForce","3e3983de9333cd2f8fbb360307d5e9126ddbce9d9c3c2f99a187d9b9936b18e8",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":10000,"startupCancellationMillis":2000,"startupMillis":5000}],
  ]),
  ...reviewedPhasePolicyFile("src/test/java/com/soklet/McpRequestStatePublicRuntimeTests.java", "bb71a4923610a92a710aae90d2165b6d9c27aac250bc1e48e4eb5e7b2001adcd", [
    ["applicationProtectedStateRoundTripsExactlyWithOneSharedContext","727dc0ae8738fa056d135c8d45a4e9b78503333f0537912b272a9f5e1c8a0cfd",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["frameworkProtectedStateCompletesOnlyWithAFreshRetryId","fecc4d062156b28f848c2fe1395942587e23289fcebfd4896999ced2e42493fb",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["frameworkProtectedStateContinuesAcrossInstancesOnlyWithinItsKeyAndAuthorizationPartition","f2c0e7241e6f4e200429c4f516d3a0b9c0fc8237ff726deea02af8fc8a7632c7",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["malformedTamperedAndUnavailableStateHaveFixedPrecedence","ea8199d5f761073627d74b6e983c499db9b2e4e3d9578208b8f5e5f3fc0463ca",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["resourceRetryStateForcesPrivateZeroTtlAndNoStore","5fdb90c9560d6dadb3ee0f3896246a593d8b0376eecfafb77d948b035f0e3984",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
  ]),
  ...reviewedPhasePolicyFile("src/test/java/com/soklet/McpShutdownObservabilityTests.java", "59d647da35f6082edd42d01686be83d197e1f19a93715d0dad6e2cdcaf94db10", [
    ["managedCleanStopEmitsOneMatchingLifecycleAndMetricsOutcome","88e1e4c1ab7669edbe8a126aba648d0af1dd4e18d5cbe2cc52710f9140eadf2b",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["lateShutdownFanoutCannotReopenTerminalMetricsDeferral","355ede79ae26f91af953d240fc2a4c12b081977126b1c99cc5d7f970bcc6828f",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["freshOwnerCleanStopRecordsOneLifecycleAndMetricsOutcome","6c6255b86940e0d23af7ff500e6251d42f603248f0a4c0284e1de89b76f3b452",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["didStartObserverFailureDoesNotVetoOwnerOrCleanStop","623a6d64a0ff7ef898d0e789b95ce3e59f6d1d2c4d39b25c70a602c529063fc1",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["failedSubscriptionRegistrationCloseRetriesAtForceBeforeOneForcedOutcome","cf0407f01f8de082a99118b9c6c7a8dfa1d3ce7dc3b5a9119b7260b3054dc069",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["blockingSubscriptionRegistrationCloseFreezesOneResidualOutcome","599b8139d89a00aeeaa3f2e3580ed94e7c8690a226027f9953c0e59703ad3d0f",{"forcedShutdownMillis":100,"gracefulShutdownMillis":100,"startupCancellationMillis":100,"startupMillis":5000}],
    ["unexpectedListenerTerminationAndFreshOwnerHaveExactParity","658fb897e3bcf0c822984d9d201f5c79c95dee91bc0957419d653a984b2c9b14",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["ownerNormalizesUnexpectedGenerationExactlyOnceAfterAdapterWait","39e48c1d89a57026241e6d00641b34ff51e116108eb37e6fd35945c2a91973f7",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["rejectedUnexpectedRestartDoesNotDuplicateBeforeFreshOwner","f4e7d5392de0df5da2dd468969ae18d43b2188c81d87fa21afabdebd1d4ef9ef",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["failedStartCleanupEmitsOneExactForcedServerStoppedEvent","1e3d87833c721808e0d582c2312c20f4fa87b33cfd351d7fd026f88f8fbfd1dc",{"forcedShutdownMillis":100,"gracefulShutdownMillis":100,"startupCancellationMillis":100,"startupMillis":5000}],
    ["shutdownMetricsCallbackRunsOutsideServerLifecycleLock","722e5cec860135f81335e86f6d5fa8dc095a6678079030e53a2abf0fa6d0f518",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["shutdownMetricsCollectorFailureIsContainedAndLoggedOnce","68922c414ce532a2e73d80a216e4d697e5822369c88e9a80da8e8bd3c5b9dda8",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["residualStopAndLaterExitDoNotDuplicateLifecycleOrMetricsOutcome","1aa0f0aecaff326799116333959b34d03fa5857a02cf195827d44abaa481e676",{"forcedShutdownMillis":100,"gracefulShutdownMillis":100,"startupCancellationMillis":100,"startupMillis":5000}],
  ]),
  ...reviewedPhasePolicyFile("src/test/java/com/soklet/SokletDirectHttpCompositionTests.java", "76d7a658e5c39f0a3685014aa50dffefa2b3898d1ae5e45e2558af56e8fa9114", [
    ["forceBeforeChildProofCancelsUnsubmittedCleanupWithoutRejection","71a3e31e2b30174d68365dd52a2b5ce84da32498dd49651477e80b5b6367f554",{"forcedShutdownMillis":2000,"gracefulShutdownMillis":75,"startupCancellationMillis":100,"startupMillis":2000}],
  ]),
  ...reviewedPhasePolicyFile("src/test/java/com/soklet/SokletDirectLifecycleRaceTests.java", "9d3fa3a4e72b2777cb2270736ec9e1918794077ea294cf9fc06abc24b4075a24", [
    ["closeAfterStartClaimCannotPublishNotAttempted","ecde80f60e9b90e011eacea59d7e082b401cb9b933cf8a19b1bf3ceb0202aa9f",{"forcedShutdownMillis":80,"gracefulShutdownMillis":80,"startupCancellationMillis":80,"startupMillis":5000}],
    ["lateBlockedAttachReturnIsInertAndCannotEscapeTerminalEvidence","1f066caf0ee88334e4766f54ee80e1ce4c3c15b0836079aec467e0309382f50d",{"forcedShutdownMillis":80,"gracefulShutdownMillis":80,"startupCancellationMillis":80,"startupMillis":5000}],
    ["installedAttachmentWithActiveWrapperRetainsTransportResidualEvidence","2c0a257151691a658d11c36565ef8ae5e2102fb720a13f5fb3689d466977c00a",{"forcedShutdownMillis":80,"gracefulShutdownMillis":80,"startupCancellationMillis":80,"startupMillis":5000}],
    ["resolverCancellationSentinelDoesNotBecomeStartupOrResultFailure","a84d176a9f5d4be343823fc7ac7808aff1698a0177e42da8ea2a0ce58b2b1a3c",{"forcedShutdownMillis":80,"gracefulShutdownMillis":80,"startupCancellationMillis":80,"startupMillis":5000}],
    ["transitionWorkerLaunchFailureCannotStrandReadyOrTerminalPublication","369e62ca9da67c727b46b00a59a4098b351165da69fa983c38857a1b8d1361eb",{"forcedShutdownMillis":80,"gracefulShutdownMillis":80,"startupCancellationMillis":80,"startupMillis":5000}],
    ["shutdownAfterReadyLinearizationCannotRetroactivelyCancelStartup","d13d4d3aa30f2beacf90124c91a79bdbf949a966637839d5e4c12b11301d1968",{"forcedShutdownMillis":80,"gracefulShutdownMillis":80,"startupCancellationMillis":80,"startupMillis":5000}],
    ["interruptResponsiveActiveStartTimeoutRemainsTimedOutNotUnexpected","eeddb44f7d943e1dadf27caa244b5f1e8dc97473c6329c47a9910268dd12aa75",{"forcedShutdownMillis":80,"gracefulShutdownMillis":80,"startupCancellationMillis":80,"startupMillis":150}],
	["externalCloseOfInterruptResponsiveActiveStartRemainsCancelled","233edea494fa9c25d494597eb80cbbc64cc080ccf87a4ffa01c3ac32de0ebe5e",{"forcedShutdownMillis":80,"gracefulShutdownMillis":80,"startupCancellationMillis":80,"startupMillis":5000}],
	["sharedLazyResolverDeadlineRemainsTimedOutNotCallFailure","0e5f9066b692a85cb68564726f17b77fd190be466a6170545c1acff9dddc1513",{"forcedShutdownMillis":80,"gracefulShutdownMillis":80,"startupCancellationMillis":80,"startupMillis":5000}],
	["externalShutdownWinsBeforeInducedStartupCallFailure","49929c1af09ea36d95cc046cb2cc095bf474cf62ea68e4bd8dc80a7c937aff26",{"forcedShutdownMillis":80,"gracefulShutdownMillis":80,"startupCancellationMillis":80,"startupMillis":5000}],
	["startupCallFailureWinsBeforeLaterExternalShutdown","33388c7cf8170be6d52186a570045147b49cf79afcd9f9c99ae004a89441c931",{"forcedShutdownMillis":80,"gracefulShutdownMillis":80,"startupCancellationMillis":80,"startupMillis":5000}],
	["startupCallFailureWinsBeforeLaterPeerTermination","bbc19ad542e94288d0a422779729765d811d6e00b4977de554c03dacd662c18c",{"forcedShutdownMillis":80,"gracefulShutdownMillis":80,"startupCancellationMillis":80,"startupMillis":5000}],
  ]),
  ...reviewedPhasePolicyFile("src/test/java/com/soklet/SokletDirectLifecycleTests.java", "6ff6a15b02204c82c239109a66b8099dbfbe2ff8680731c95b8ec5c5461004fc", [
    ["blockingFrameworkSetupIsBoundedByStartupAndShutdownBudgets","e15a71164f4d44935fdef10beb8d9a350688b4c17e6507931d50dbf9c149d9f8",{"forcedShutdownMillis":75,"gracefulShutdownMillis":75,"startupCancellationMillis":75,"startupMillis":75}],
    ["blockingTransportStartIsBoundedAndCannotPublishLateReadiness","97736864a900e952a6688b8cd001d76a3d096ba5450b0400bd240e5a5d5ee8dc",{"forcedShutdownMillis":75,"gracefulShutdownMillis":75,"startupCancellationMillis":75,"startupMillis":75}],
  ]),
  ...reviewedPhasePolicyFile("src/test/java/com/soklet/SokletDirectMcpLifecycleTests.java", "843a6887b0a2106a8cde9e2a651bccc3dc87db1aed50cb9602fa48ec82909ee2", [
    ["blockingSubscriptionPublisherTimeoutRetainsListenerUntilLateReturn","f7b1b636f70ac57dfb8283bccf38f08167a46ae4d72b025f0b874bc79746c4ed",{"forcedShutdownMillis":250,"gracefulShutdownMillis":150,"startupCancellationMillis":150,"startupMillis":1000}],
    ["externalShutdownCancelsBlockingPublisherWithSameTerminalIdentity","0f5cf7ffb49ff8addfd587d774b116364a9bbd9dce0678b11228748a497c6c44",{"forcedShutdownMillis":250,"gracefulShutdownMillis":150,"startupCancellationMillis":150,"startupMillis":10000}],
    ["synchronousMcpStartupCleanupFailureRemainsBoundedSecondaryEvidence","03f60a42d6ed7d27a83cd24b6ea7222492eb78bbdf76199c2384943dc75d9c2a",{"forcedShutdownMillis":250,"gracefulShutdownMillis":150,"startupCancellationMillis":150,"startupMillis":10000}],
    ["lateMcpStartupFailuresCannotMutateFrozenEventLoopPrimary","8038391c8e75bccae7bf5ff3764c6684fb44773996292ddfc647457ef4b64f39",{"forcedShutdownMillis":250,"gracefulShutdownMillis":150,"startupCancellationMillis":150,"startupMillis":10000}],
    ["admittedMcpHandlerSelfStopPublishesIntentAndFailsFastWithoutSelfJoin","eedddaa1c1f39112fb17890ad217c8a4d6c6955e3da493df2e1800b9e6ef1c21",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":5000,"startupCancellationMillis":250,"startupMillis":5000}],
  ]),
  ...reviewedPhasePolicyFile("src/test/java/com/soklet/internal/mcp/protocol/McpStreamSubscriptionDiagnosticsPublicRuntimeTests.java", "ced985535c94ce800f0a1f7e849999b204ad6ca12f221846ffb3cb86871ea432", [
    ["ordinaryAndSubscriptionStreamsAggregateAcrossEndpointsAndCleanOnDisconnect","3cf18bb52b65bc2da74008ea443a87ab83cf3fb4d98935bc3a55b3ca2988984e",{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000}],
    ["residualHandlerStopPublishesZeroStreamsBeforeLateHandlerExit","9b8d0f401f61b81f2a4eff908e7ba1855a0f6d9161b49907ecf72f79df1c4f5c",{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":150,"forcedShutdownMillis":150}],
    ["unexpectedFailureRetainsOneSubscriptionUntilCleanupWithConcurrentInvariantReads","e1e2e84dc08e53e62be658cb149d1317e362bb4112588d3ed0d06966ef675243",{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000}],
  ]),
  ...reviewedPhasePolicyFile("src/test/java/com/soklet/internal/mcp/protocol/McpSubscriptionPublicRuntimeTests.java", "df26302bb7c18a462a4a3122287725b18c227e87a1fe1bb784a53c674a2197b4", [
    ["acknowledgmentIsFirstAndPreservesExactStringAndIntegerIds","b5962a6919e4491d768afa87648431e23774aec0d4254a744ba28eaf18dba14f",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["publisherEmitsOnlyRequestedResourceEventsForMatchingUris","256689db4ae9f6356f63d2bbf9da2b74c5e8c1a7de24d71fa77ac293fbc18f93",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["supportedIntersectionOmitsToolsPromptsAndUnconfiguredResources","0e7cf6d2b89746f8cac35d6b4de3c91f4ad89cdd6199fb59cb5a056d589ed789",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["malformedRecognizedFilterFieldsFailBeforeAdmission","17454b140a1d2b7494cdfbbe0b34871162bc34ab0678d3c79f81c70d6ce1cd31",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["validListenUsesAdmissionAndRequestLimiterOnly","67c47e3a7e886bdb3510213238212bd95c1332361587f9dda29bdafe49c47317",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["rejectedAdmissionNeverActivatesARegisteredSubscription","5f2170fa8d30d6a03de32a6b1d2961bf3c26a0b49fcbc654433743fc8c4ce142",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["nullAndThrowingAdmissionNeverActivateOrConsumeSubscriptionQuota","8d487fe681050558d3ef47ad3736091583b3ad1da1d75509b22b140f8cba1d0a",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["liveSubscriptionDoesNotConsumeTheConfiguredHandlerSlot","b5d6e75ec33c6d7094c65761365b3b5c5ed5d35a9c0effd3ecc3f50d57366e48",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["configuredPerPrincipalCapRejectsWithoutDisturbingAndRecovers","d6263f95adf47bff9a2cdf9c133423469276c30e7c563ad7131261d758e23fdc",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["sameIdSubscriptionsAreIsolatedAcrossAdmissionPartitionsAndCapRelease","14acf3fc1afc9550175bd0e711110179abc4ce9d1d3b31cd3f82e257cbfe530f",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["publisherIdentityIsGroupedPerServerAndSharedAcrossServers","88d5a74bd6fe207fa20b977750c0d235fbc520284ace2560a8b39730e523e42c",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["gracefulHttpShutdownEndsWithOnlyTheTerminalCompleteResult","fb45b5659d7297085ce685282006124e995394f55128e73d3eb5b85308bcfdb6",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["configuredMaximumDurationPublishesExactLifecycleAndMetrics","88a7e4e7bb0b226e1e9d43e9330ee0cb4407699aefe9c8047b0b3bd42de197cd",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["clientDisconnectReleasesStateAndPublishesExactlyOnce","06776a37a9b16c9ffc411d2fcb2f9c16d72004d10a901f713247ed2f452c1b79",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["keepAliveAcceptanceSharesStreamTransitionWithCloseObservation","5189b6b605fba20a67beae786206baadd8e33f34a3c00965047c2285ddb9867b",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["publisherVisibilityBeginsAfterAcknowledgmentActivation","e5d88c4b9f0464346291492c49877c3d3ca5741249414b5627250fa6abd05688",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["configuredQueueContainsBackpressureAndReleasesTheFullCap","949a18ede9f3cf034ee4453e5aea45d67e77febee7266d2427f60d7d183695f1",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
  ]),
], 'lifecycle phase policy');

const REVIEWED_CONTROL_OVERRIDES = checkedReviewMap([
  ...reviewedScopeFile("src/test/java/com/soklet/BuiltInTransportLifecycleAdapterTests.java", "1da2d2468e84d284bde7a5b7eb102bb31d7c907045006404385f901b646c02c1", [
    ["failureRacingNormalShutdownIsRetainedWithoutReclassifyingRequestedProof","d5b8e568c287856debb44e45137ebaa592ca10ece32eb55ee6be5c748bcd9e15",{"controlJoinMillis":3000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
    ["requestedProofThenLateFailureBeforeFreezePreservesBothInSequence","0486a99df1c9b6163e90faa067fb5cc24f94f2aaa2a1d586bc815614211943bb",{"controlJoinMillis":3000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["exactGenerationOperationsRejectForeignTokensWithoutMutation","ff1b277adb13768eb39a9e28b2a43a292e48b75af80cf0ca8439c542f6a8535c",{"controlJoinMillis":0,"controlComposition":"REVIEWED_NONBLOCKING_PRECONDITION"}],
    ["launchedThenThrowingCoordinatorCannotRepublishOrReleaseEvidence","b03f92588538cd5cf7ce738deb0df255146cbe88d855fa64e643e0bc50965ef4",{"controlJoinMillis":2000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/DefaultHttpServerTests.java", "662b6a547722286e003fcb0cb32b5ea66b03a9d7165a36878ece3853102f80ca", [
    ["httpServerCleansUpAfterUnexpectedEventLoopTermination","2b2c4db9ffe7412bac5d2bb617a9e80d997ef4d4ab3819b4133cad67311c7022",{"controlJoinMillis":4000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["stopCannotPublishAnEmptyGenerationWhileStartInstallsHttpResources","daa38ff9c7f3f1effcc52664813c709b9eb9b4ad38458546dff9dd23de556bd0",{"controlJoinMillis":7100,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
    ["startRejectsRunningHttpGenerationWhileItsStopIsInProgress","468e5c2b4365d5a53148b8525487911d065bcd75075ffac281f4cd6b149d25be",{"controlJoinMillis":6000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/ExternallyCoordinatedTransportLifecycleAdapterTests.java", "7be4c5d1b5fe67a92e4457584bf75f6c854953f14b21daaa36521e216b1575b7", [
    ["externalSelfStopPublishesIntentBeforeOwnerScopedWaitFailsFast","80b2f130651f5b7f7a03444787899e2b42e6e82c05f7b3ec251201b0444ae4e0",{"controlJoinMillis":0,"controlComposition":"REVIEWED_NONBLOCKING_PRECONDITION"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/HttpServerLifecycleTests.java", "0c8ac8cb3898479d83c3e64993bd567ad6495b5f01490b97c95e25e101e82da5", [
    ["ownerLifecycleAttachesServesAndPublishesOneGracefulResult","bdc79f45c02504b7a9e480a89bc117cf22955dd98679b94b89b676619bc3c86b",{"controlJoinMillis":0,"controlComposition":"REVIEWED_NONBLOCKING_PRECONDITION"}],
    ["ownerShutdownDrainsInFlightResponseBeforeClosingConnection","c0dfba7292562aa6e42757c9286e5bb19a56abfdd51dfe893c5b36f7438fb47b",{"controlJoinMillis":9000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpAuthorizationIntegrationTests.java", "cc550caf1cd514c3fc5fa0cfbc3a62376d8ab6845977b7bde4df33eea8bf5112", [
    ["passesSafeBearerChallenge","4f92e4ce7a1cf1c5494a22b5dc5cea5417ab556d224ff609bf3a4b55cd4eee59",{"controlJoinMillis":5000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["corsResponseHeadsMatchIndependentGoldens","770eb979be12f832a69bfd64304e40458414c22a6d95fe929d6d924a05611377",{"controlJoinMillis":5000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpHandlerInterceptionPublicRuntimeTests.java", "21d4e29c6cba7280aa143baa93e6c192cee5473c02016a335bc651a4e1abcc76", [
    ["deadlinePreventsLatePublicHandlerEntry","84b09475995c300839ab8d96f34ac846eae744c5aa5f8f31c0652b05d70de037",{"controlJoinMillis":5000,"controlComposition":"REVIEWED_OVERLAP_OR_DUPLICATE"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpHttpContractGoldenProductionTests.java", "58b3980841b86c79b04c1aa06695a9c7ed8a2f4d625fd944574e8a7cf63ee065", [
    ["requestPipelineFirstFailureWinnersMatchCompleteWireGoldens","13579007ec294fc3c5bc1b62413e5e41a2a23500182e52f1521d1a68e721e614",{"controlJoinMillis":10000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["notificationPipelineAndPreflightMatchCompleteWireGoldens","5a18db5c72569f0e33c1db6cb382d6493f38d0c226699173846fc3348c98ff34",{"controlJoinMillis":10000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["overloadAndSseAuthoritiesMatchCompleteWireGoldens","8bb450280668ad74e00bad100768304c24310ad064a3256e150b0c4589405638",{"controlJoinMillis":35000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpLifecycleB3Tests.java", "5e251cc76ab78a8cc48bd86724fed02f984619e253ea17168d2dd1fe7dc2356b", [
    ["exactMcpGenerationOperationsRejectForeignTokensWithoutMutation","67aedfa580c9dd8c6514285b0dd58076b13327801868c12f4c88cd974052d148",{"controlJoinMillis":0,"controlComposition":"REVIEWED_NONBLOCKING_PRECONDITION"}],
    ["forceResponsiveHandlerIsInterruptedOnlyAfterTheGraceDeadline","52f03eb3d94266f6598738c86e9d91c33506d47dc71342533950eaf36cd495b7",{"controlJoinMillis":10000,"controlComposition":"REVIEWED_LIFECYCLE_CORE_DEDUPLICATION"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpProtocolProfileMetricsTests.java", "7ed8caa3559ac3cfd0cae677e52677d4f031fb49adf00c1b173b3f1e51d7c32b", [
    ["unsupportedMissingMetadataRecordsUnsupportedVersionNotInvalidParams","2f00b2977d3c7ed715487cc9fc1575c8e2cf7a94b61237b3d2187c96d1709af3",{"controlJoinMillis":5000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/MetricsCollectorTests.java", "51fbebad6fc7c9dc8e32d3a22e16908e7bbafa499f7981df46b25a82b6f7d0da", [
    ["httpMetricsSnapshot_overNetwork","4cc77aea3d044fd9130367bcd75065f860204a5306f7736a88febd183dfdbd51",{"controlJoinMillis":2000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["sseMetricsSnapshot_overNetwork","87d1120ea6c5e33318796af562a528bc04b562ccbdd822786af0c0b5f83f39a7",{"controlJoinMillis":5000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpRequestObservationPublicRuntimeTests.java", "83db5397129dfa25ee0f9edf2232f6c8499494c2272cfcd7ab0804fba17cdf61", [
    ["throwingObservationCallbacksKeepRawCarriersApplicationOwnedAndLogsRedacted","3d238400d6d9098f10867f1e98bdd3b4cb22a527afa40cc2a1017cdf6a73ed3f",{"controlJoinMillis":15000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpErrorMappingGoldenProductionTests.java", "097f6bdae88a3e2ef4621d1bff33b2d4055f33b9d74b3b04569473baf238180d", [
    ["ordinaryMappingFamiliesMatchProductionListenerGoldens","791203cea6affc534885196b9c249a4b774257e6663d98c7ba8aca8f2028bdfb",{"controlJoinMillis":10000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["overloadMappingMatchesProductionListenerGolden","45624e4374586622754a17d77df7686142bbdf55ef21e838d51ba4c1e2f0df91",{"controlJoinMillis":35000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpHttpServerRequestScopedSseTests.java", "7723b0d5f4f5384ba1e25ce4023b076d937a8aafd7ccc64b8071fd0a86a6c7e5", [
    ["shutdown_closes_committed_stream_and_runtime_restarts_cleanly","1b56c480e7618c105d448e1c5f45e17d4967bac50ef9f70443d8ecdf442784b4",{"controlComposition":"REVIEWED_LIFECYCLE_CORE_DEDUPLICATION","controlJoinMillis":15000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpSubscriptionRuntimeBoundaryTests.java", "8c6eb6696d70910e912bc0f4279420fd2b0c81b8bf41b946f0ac1b6b3ae5d029", [
    ["commonLifecycleAwaitRescansRegistrationAfterCloseAttemptCompletes","1b6c55192d0294ab2be799dc20bc6bada8002817bd219aa572ed0865dfde9c9a",{"controlledLifecycleCoreMillis":5000,"controlJoinMillis":15000,"controlComposition":"REVIEWED_LIFECYCLE_CORE_DEDUPLICATION"}],
    ["commonLifecycleForceRetriesAQuiesceRegistrationCloseFailure","ed74ab5cbf8185b3f1e3a30c80ead352af39ebb40dab22715b5489a8e9fffd61",{"controlJoinMillis":9000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["commonLifecycleForceInterruptsAnOwnedBlockingRegistrationClose","c6d71cd9a87bfd649cbcdfc18d9b0f2a1fc9c210a8822784af0435f77fdb3dff",{"controlJoinMillis":7000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["commonLifecycleForceCancelsTheExactRetryAttemptItCreates","a961e251865f9c945028240e564fb3aa899b4835b58ce5886c25410866553032",{"controlJoinMillis":9000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["commonLifecycleRejectsPostForceGracefulRegistrationRetry","534b3aba001d9f537a633a18d20a79eafccad32163e4268c16971a473b6c7048",{"controlJoinMillis":4000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["preReadinessFailurePublicationWaitsForLifecycleElectionLock","0e60adc809631bf6ff8b977d43e6057bea21e1763de4287218165dfab7c9ea4b",{"controlJoinMillis":20000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpSubscriptionPublicRuntimeTests.java", "df26302bb7c18a462a4a3122287725b18c227e87a1fe1bb784a53c674a2197b4", [
    ["configuredPerPrincipalCapRejectsWithoutDisturbingAndRecovers","d6263f95adf47bff9a2cdf9c133423469276c30e7c563ad7131261d758e23fdc",{"controlJoinMillis":5000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["sameIdSubscriptionsAreIsolatedAcrossAdmissionPartitionsAndCapRelease","14acf3fc1afc9550175bd0e711110179abc4ce9d1d3b31cd3f82e257cbfe530f",{"controlJoinMillis":5000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["clientDisconnectReleasesStateAndPublishesExactlyOnce","06776a37a9b16c9ffc411d2fcb2f9c16d72004d10a901f713247ed2f452c1b79",{"controlJoinMillis":5000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["keepAliveAcceptanceSharesStreamTransitionWithCloseObservation","5189b6b605fba20a67beae786206baadd8e33f34a3c00965047c2285ddb9867b",{"controlJoinMillis":15000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["configuredQueueContainsBackpressureAndReleasesTheFullCap","949a18ede9f3cf034ee4453e5aea45d67e77febee7266d2427f60d7d183695f1",{"controlJoinMillis":5000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/transport/McpTransportRuntimeSmokeTests.java", "0ca1a5e95ab839a148fb9d7454e644e9f30cb930e9469ff4c77b6a4f4e332021", [
    ["platform_live_post_uses_independent_listener_and_event_driven_sse_body","144828fecde665ed569b53b21026b6a70f370549ca7692b4b5c7eba00a3ab0d8",{"controlJoinMillis":6000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["virtual_live_post_uses_independent_listener_and_event_driven_sse_body","bb0e39cd0ec9c64ba846939340305a0c99e102f71bb1c35b85cc3fd732f76afb",{"controlJoinMillis":6000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/InternalLifecycleCoordinatorForceAttributionTests.java", "5ce844a25c119300e1e38672120d4cbe6b90757dffc96541669e3734688b3daa", [
    ["rejectedForceLaunchDoesNotMakeLateGracefulProofForced","29b47f3ab51cf5d1a673dccc2df1ec38579eb4271181c771087827d5f5eb391c",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":8000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/LifecycleFoundationTests.java", "8832c7f43c2ab7472204cd34d669058c8fcc4004ae6248f8d4f34ea2bd83313d", [
    ["blockedLifecycleCallDoesNotPreventAnotherParticipantPhaseSubmission","4206c710a70e1c2a9198e219d085020449c7f653f70586b93c0de2ca47d67666",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":2000}],
    ["graceExpiryCancelsBlockedQuiesceBeforeSubmittingForce","600ac5c14550d27da74baa83f9b5500ee370b0fb06cc21a9a7381b1338d158d0",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":0}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpHandlerMetricsObservabilityTests.java", "6d60b09d4718e7d58c08066caa841068f084af101db568ea11781d21c74d2b14", [
    ["queuedDeadlineDequeuesWithoutExecutionAndRetainsActiveGauge","072447bdc3fac24aeb356e1707048174fa8010606d15ca4ef6dd5ac6e4986ce4",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":45000}],
    ["managedResidualShutdownDequeuesAndFreezesGaugeAcrossLateExit","4681101ac6e14f0dde692d5ba2d481450422cf0d7bd7e381c309949dce6ffea1",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":35000}],
    ["unexpectedTerminationDefersQueueCallbackAndFreezesTerminalGauge","4c0c33330fbd8c8c8d749894f8267d1a569d6fd5e75537c1cfd597c9f1bd6ba4",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":55000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpHandlerQueueDiagnosticsPublicRuntimeTests.java", "428bbfb015bcef7dc1d208bfbd417226ef408b7530c6a75e99e1718b45cfab36", [
    ["residualStopRetainsOneActiveAndDrainsQueueUntilLateExit","b87fc0c5be57b5735e3ffef9568f235a5ffad7df06ce578207a33b52ab894453",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":25000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpLifecycleB3Tests.java", "5e251cc76ab78a8cc48bd86724fed02f984619e253ea17168d2dd1fe7dc2356b", [
    ["noncooperativeHandlerClassifiesResidualAndRetainsItsGraphAndAddress","c090937c5cb6899b7251a3fce920523de2895ca2692b8f6b6893522c5656f9d4",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":20000}],
    ["unexpectedEventLoopFailureFencesBeforeProofAndRetainsAddress","e5a4fc15428bd0b3ab87b7430a0f2ebf6281a21282fe4905918703b64f5b6f52",{"controlComposition":"REVIEWED_LIFECYCLE_CORE_DEDUPLICATION","controlJoinMillis":10000}],
    ["oneShotOwnerCannotConsumeUnexpectedGenerationBeforeExactResultPublication","8ec7c5714e6b014e07678de524cef1b8a49784ca98bdd8f04dcb66d811c62c5b",{"controlComposition":"REVIEWED_LIFECYCLE_CORE_DEDUPLICATION","controlJoinMillis":20000}],
    ["simultaneousStartupFailuresShareTheElectedEventLoopPrimary","e8a6afe30254960b207bbdcf619b320d380ef70c0669756239415f2129146496",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":20000}],
    ["synchronousStartupFailureWaitsForExactCauseElectionBeforeTermination","77aa65b9cb73609098000eea0f400c9dbe6a0c803a1f2484ffbd7730bea7179a",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":25000}],
    ["eventLoopFailureBetweenRuntimeAndCommonReadinessPreservesExactCause","357e7603e01bd31e58af2e4e6c30ce99a3fbff91ce1e11efd29a541e593b19fd",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":25000}],
    ["deterministicNoProofMapsToMcpUnknownAndRetainsEvidence","1494be5b83b11e6915906fd580725bdaf02481f55eed026eeda110476477522a",{"controlComposition":"REVIEWED_LIFECYCLE_CORE_DEDUPLICATION","controlJoinMillis":0}],
    ["deterministicNoProofRetainsTheExactBoundEphemeralAddress","fc2eee11e106de18ad84f3cb6e40c4939f330d448e52be7c4a1181a649ff52fc",{"controlComposition":"REVIEWED_LIFECYCLE_CORE_DEDUPLICATION","controlJoinMillis":0}],
    ["blockedMcpQuiesceIsCancelledBeforeForceAndProof","e4c72353ed7574cf8b45b61502de771cbb513b77579bf3d6c79717287c5014b7",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":0}],
    ["shutdownIntentFencesAdmissionBeforeDeferredMcpQuiesce","689238678986fe2dd56cff28da44744f9c7bc748b73b8e05a4d8abd9cca21243",{"controlComposition":"REVIEWED_LIFECYCLE_CORE_DEDUPLICATION","controlJoinMillis":0}],
    ["idleSubscriptionClosesPromptlyWithServerStoppedAndNoForce","3e3983de9333cd2f8fbb360307d5e9126ddbce9d9c3c2f99a187d9b9936b18e8",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":30000}],
    ["ownerNormalizesRetainedUnexpectedGenerationOnceBeforeRejectingRestart","2afc232071d7c006ad16f324b8abae2c0b5f5c4875b7dd64a0c0e71921c93b07",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":30000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpLocalizationAdversarialTests.java", "008f34ff783d7297b187244897f5619f4727dd22accbb37f1afa6c84d7acbfaf", [
    ["rejectedAndIrrelevantWorkNeverInvokesTheProvider","314ac4b16ec574dc021d47c15bf20ba61dc3e5d9a7285af060451a08a6d20977",{"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","controlJoinMillis":50000}],
    ["aUniqueTagFloodRetainsNoStateOrMetricSeries","ae0687bd43d6f0f28b6e6b429824e400d0bc9f5a95416d8d49b702f6cc0f7806",{"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","controlJoinMillis":30000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpMetricsEventDeliveryPublicRuntimeTests.java", "de540f18839832f8748a0f44ac3da506845e18dc6d5f446c64d6a7d63c0b79cb", [
    ["unexpectedTerminationOrdersNormalizedStopBeforeFreshOwnerStart","90cb70ed200f06d85e999d10083dc95a7bc34236ae7d0a23f9e76db79c1a6938",{"controlComposition":"REVIEWED_LIFECYCLE_CORE_DEDUPLICATION","controlJoinMillis":2000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpShutdownObservabilityTests.java", "59d647da35f6082edd42d01686be83d197e1f19a93715d0dad6e2cdcaf94db10", [
    ["unexpectedListenerTerminationAndFreshOwnerHaveExactParity","658fb897e3bcf0c822984d9d201f5c79c95dee91bc0957419d653a984b2c9b14",{"controlComposition":"REVIEWED_LIFECYCLE_CORE_DEDUPLICATION","controlJoinMillis":17000}],
    ["ownerNormalizesUnexpectedGenerationExactlyOnceAfterAdapterWait","39e48c1d89a57026241e6d00641b34ff51e116108eb37e6fd35945c2a91973f7",{"controlComposition":"REVIEWED_LIFECYCLE_CORE_DEDUPLICATION","controlJoinMillis":12000}],
    ["rejectedUnexpectedRestartDoesNotDuplicateBeforeFreshOwner","f4e7d5392de0df5da2dd468969ae18d43b2188c81d87fa21afabdebd1d4ef9ef",{"controlComposition":"REVIEWED_LIFECYCLE_CORE_DEDUPLICATION","controlJoinMillis":17000}],
    ["residualStopAndLaterExitDoNotDuplicateLifecycleOrMetricsOutcome","1aa0f0aecaff326799116333959b34d03fa5857a02cf195827d44abaa481e676",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":30000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpSimulatorPublicRuntimeTests.java", "16f791eb79e09f7b79c5b4e51a1ee7b90b3f952f2830d3be0ae70aec31c242b9", [
    ["noncooperativeSimulationCleanupIsBoundedAndPreservesSuppression","527e1495a7b27842684e2d0ee1ab4a5100de3dadc4ba9f807e78fd52d4fedac9",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":10000}],
    ["nonDrainingCaptureLimitDoesNotBlockUnrelatedSimulationOrCreateTransportFailure","abeb07b6c813ea671430fd7d785fb78f2309ee92a7f63207d2acfbb1f26fa9a7",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":35000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SokletApplicationObservationTests.java", "6880ff3614db6190e37ec90fb72d9340c590a3da62881abca8cad7f4a7375951", [
    ["transportLogDuringAttachIsInlineNonqueuedAndTracked","ffcad197704a26871607ea10bbc70ca4545809e637918ce6e427bd4b955065a6",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":30000}],
    ["blockedTransitionCannotDelayRunnerCleanupOrTerminalReport","80a4b0fb8dca4e5e29ce04dce07e955d48f2afe47a62f62089f875f95f2fe388",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":40000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SokletApplicationProcessTests.java", "85b493f9d67f53858a76316793df4c88ffdde37b9e0db8d487880f2cab0c8cc0", [
    ["concurrentHookEnterInterruptionAndExplicitShutdownShareOneAttempt","2b081d56a37034ad07a1b7560ba6bcbcd4486d9505cc0f9f7449d85e27aee126",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":0}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SokletDirectLifecycleRaceTests.java", "9d3fa3a4e72b2777cb2270736ec9e1918794077ea294cf9fc06abc24b4075a24", [
    ["lateBlockedAttachReturnIsInertAndCannotEscapeTerminalEvidence","1f066caf0ee88334e4766f54ee80e1ce4c3c15b0836079aec467e0309382f50d",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":4000}],
    ["installedAttachmentWithActiveWrapperRetainsTransportResidualEvidence","2c0a257151691a658d11c36565ef8ae5e2102fb720a13f5fb3689d466977c00a",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":5000}],
    ["resolverCancellationSentinelDoesNotBecomeStartupOrResultFailure","a84d176a9f5d4be343823fc7ac7808aff1698a0177e42da8ea2a0ce58b2b1a3c",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":8000}],
	["shutdownAfterReadyLinearizationCannotRetroactivelyCancelStartup","d13d4d3aa30f2beacf90124c91a79bdbf949a966637839d5e4c12b11301d1968",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":2000}],
	["sharedLazyResolverDeadlineRemainsTimedOutNotCallFailure","0e5f9066b692a85cb68564726f17b77fd190be466a6170545c1acff9dddc1513",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":15000}],
	["externalShutdownWinsBeforeInducedStartupCallFailure","49929c1af09ea36d95cc046cb2cc095bf474cf62ea68e4bd8dc80a7c937aff26",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":20000}],
	["startupCallFailureWinsBeforeLaterExternalShutdown","33388c7cf8170be6d52186a570045147b49cf79afcd9f9c99ae004a89441c931",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":15000}],
	["startupCallFailureWinsBeforeLaterPeerTermination","bbc19ad542e94288d0a422779729765d811d6e00b4977de554c03dacd662c18c",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":15000}],
    ["earlierParticipantFailureBoundsBlockedLaterStartAndKeepsExactCause","6cfa5cb3699cb2181f389c932c41e4855b6799c47ec33b11831cea122f3a03ab",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":4000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SokletDirectLifecycleTests.java", "6ff6a15b02204c82c239109a66b8099dbfbe2ff8680731c95b8ec5c5461004fc", [
    ["blockingFrameworkSetupIsBoundedByStartupAndShutdownBudgets","e15a71164f4d44935fdef10beb8d9a350688b4c17e6507931d50dbf9c149d9f8",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":5000}],
    ["blockingTransportStartIsBoundedAndCannotPublishLateReadiness","97736864a900e952a6688b8cd001d76a3d096ba5450b0400bd240e5a5d5ee8dc",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":5000}],
    ["admissionRemainsClosedUntilEveryConfiguredTransportHasStarted","f4c2ca449a31d3c74a936dd081db94708c0fcc942dcf0afdd770eda65903105c",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":4000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SokletDirectMcpLifecycleTests.java", "843a6887b0a2106a8cde9e2a651bccc3dc87db1aed50cb9602fa48ec82909ee2", [
    ["synchronousMcpStartupCleanupFailureRemainsBoundedSecondaryEvidence","03f60a42d6ed7d27a83cd24b6ea7222492eb78bbdf76199c2384943dc75d9c2a",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":0}],
    ["admittedMcpHandlerSelfStopPublishesIntentAndFailsFastWithoutSelfJoin","eedddaa1c1f39112fb17890ad217c8a4d6c6955e3da493df2e1800b9e6ef1c21",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":27000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SokletDirectStartClaimTruthTableTests.java", "bf9dc53cbe56c4c4f81a32c5c06f836e93ce815c8c2b818abb10c0c6cdc70a08", [
    ["startRacingNewOriginShutdownWaitsForExactNotAttemptedResult","789870435f667be6794e3fdb2fe1cc6499c5e15f6c3c75212c29dfca7dab371c",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":6000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SokletDirectTerminalPublicationTests.java", "06996f724b9b6b881b81868186e5742d8eaf59773a195944a99c74e3b0c826f5", [
    ["blockedPreRegisteredContinuationCannotStrandPrivateOrPeerOwners","07868a752d00075bf0cf1e994a19e2540402b3ffc87dc6050ee8a5bb1d48de19",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":2000}],
    ["concurrentAndPostClosedShutdownCallsShareOneStageAndResult","e8276afc6b3e60b38ea2056d4a07066d94af2eadb4baace5c97711812deab8f7",{"controlComposition":"REVIEWED_CONCURRENT_MAX","controlJoinMillis":10000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SokletDirectWaitSemanticsTests.java", "07c280513edcf9cb753950b18dc211fa83c605797a9bcc91e37be91bc2c9fe1e", [
    ["concurrentCloseCallsJoinOnceAndRestoreEntryInterrupt","9ff6d7a16fa1b0d95b8f3552f038fa68a4f83d6ea485edb49f0e7efc0895e36a",{"controlComposition":"REVIEWED_OVERLAP_OR_DUPLICATE","controlJoinMillis":2000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SokletProtectedLifecycleCompatibilityTests.java", "193c635104cc417f0ece565d20e560bd4a3d802180f8d219c0a46dad69924bff", [
    ["holdingProtectedLockProjectionCannotBlockShutdown","6e970b21afe596b0b7934c2d552f805929e5ab7286163baae728116b2e279b67",{"controlComposition":"REVIEWED_OVERLAP_OR_DUPLICATE","controlJoinMillis":8000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SokletDirectTerminationPrecedenceTests.java", "4803d9fb89add3afeb02b2f4d0642e1075e3aae959e34f677f02dd38381fac03", [
    ["ownerShutdownIntentWinsFormerGroupFanoutGap","da9453ddd92e3d10cfe8cb15476bf271efd399c14e41292a2edf1ac54b142ef9",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":8000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SokletMcpLifecycleTests.java", "00762704fb2517a719685cdb28f5228d4f809f36a53a54a8fb3920871e453c59", [
    ["noncooperativeMcpHandlerFreezesOneResidualOutcomeAcrossLaterCalls","1be226f9f0014a57ebd87378e7e9bd7aca38dc6ef097e13b4c54e921e2fb024d",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":20000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SokletSimulatorIsolationTests.java", "db44d74627a39c405b9d498b7b7aca08edaab209fee1a41904b55ffd776bf891", [
    ["blockedFrameworkSetupUsesOneExactStartupAndRollbackSchedule","3e3b5ad4ea82946356b53bacd01456586c1061d238075018d12ce690f1e41dc1",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":15000}],
    ["concurrentConfigurationReuseLetsExactlyOneRunClaimIt","5579f354b0e00bb9359ba6b607e9e803ea670e7c723448103ae2ab25fd364b9e",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":25000}],
    ["liveMcpStartQuiescesBeforeCancellationAndCatchesUpToForce","5d7cb7ec1f927c9944225cbef023a642d39fb5889875b8f9b6c41df19402a238",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":15000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SseTests.java", "d1db8c459e2d0a29b94f7dda4ca198a2ad816c8bcd4b9b652b0d4fd3e70d1409", [
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
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpConditionalCapabilityProxyRuntimeTests.java", "fd2409b14f5e6b8ead3a535f10097292c31c521c5b5534597e5b7209d9ab4312", [
    ["proxyIdleExpiryCancelsSilentHoldAndSupportedControlForwardsSse","08f3c9fb26fd9f77a5097211504e8e3bb082a29e26d6dd61390ea9a0fc247361",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":55000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpHttpServerApplicationExecutionTests.java", "6458c88ff9b34bf2a88ed2ed7acefde7d538dee832d82d62b49ab7498c478d4e", [
    ["queued_absolute_deadline_gets_the_exact_capacity_response_without_dispatch","a60be88bcf82aa2c70bfe9b13b4b7994d24bd97550e53091062bcf73ecd22841",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":25010}],
    ["request_deadline_is_captured_before_protocol_admission_work","5d32c73b556dd345643c753b43f2531d4109866e1a96021c4d5b81f253d30dac",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":25010}],
    ["protocol_deadline_comparison_survives_monotonic_clock_wraparound","0cc1f79ed3418fb6110a2bc82f286f88c081549af1ac531433b96a68d4a6c0c7",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":15000}],
    ["deadline_during_cors_authorization_prevents_later_admission","954266bce7a0cdf67bd9286d4d184af3973d11533e12be691f13e6aafb9b4abc",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":25005}],
    ["protocol_processor_backlog_expires_and_releases_canceled_queue_capacity","379f4684bbb0fc89f4375d594b5c851b396b01750c24a8ad2f843ac3b2e6a75e",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":30010}],
    ["framework_discovery_deadline_releases_identified_exchange_accounting","2a6e8c28000629fc49630cfa8a75fa25b822a4165061fdf2aae15116611f65ea",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":25005}],
    ["active_client_disconnect_interrupts_but_retains_the_slot_until_handler_exit","8fc72c1dfce652bbb8b5a1d43d2bbd3fedfe477a9b0ece1e3d55afc9346407ae",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":25015}],
    ["lifecycle_grace_preserves_active_handler_then_force_interrupts_without_promoting_queued_work","b2298faf5919d0ddfb071e8a493c18b3b8d8fafa721f0a1188fba621d341ff90",{"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","controlJoinMillis":20005}],
    ["shutdown_reports_residual_application_work_and_blocks_restart_until_exit","0168dce25a9f381c15c6b919637388cf236960743a70f23ea37fe954c058c8af",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":15255}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpHttpServerObservationTerminalRaceTests.java", "ba3e4b3ab88f879d5022309d2b89dea9965fba4949f91cc28d7cb28e5316ac7f", [
    ["lifecycleLeaseOutlivesApplicationExchangeUntilBodyCompletion","837ed46fa8bf1ba634cc7da6160515d53e13bc8526be6f169963daea3f956191",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":15000}],
    ["protocol_completion_cannot_preempt_inline_stream_terminal_owner","218622f3798f2e1f0fcdb80decaa1095ef3565ccf25d33b0a9f3dc5208483116",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":15000}],
    ["written_sse_terminal_beats_concurrent_client_cancel_exactly_once","e7a78a9f1c35713913c7a0cf40e8a7426774d38ecf2eb3df644b7cb8016f49ae",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":55000}],
    ["precommit_mapped_error_beats_late_client_cancel_exactly_once","5ae4210562fc73a20cd22802f9540655a47aff7a4f1112f3c86ee209efe423d3",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":10000}],
    ["written_streamed_error_terminal_beats_concurrent_client_cancel_exactly_once","fa2d0a6e55bb5028f2c8bf15d121e6e1a503252d302939c72c3a1f1d7b3deaff",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":55000}],
    ["client_cancel_beats_unreserved_streamed_error_and_discards_its_metric","3ae3542e1c26de67e77f97293b73e1490fad92aeaa3adae1e979bb440603e838",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":20000}],
    ["application_encoding_fallback_reports_actual_internal_error","53649fc4bfba7a31b8ea69696084093ac3a01eea3c00316bbae3bfc1dcc82cf0",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":10000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpHttpServerPolicyPipelineTests.java", "0661a41233278fbb8050607916686f43985badf6aad062e63842a9f01ef282c5", [
    ["policy_null_exception_reserved_code_and_unsafe_header_fail_closed","998e3a54b55f4ff58e1b75ac8d31c00e649a2d93dc73abaddc6e31ae4cad7893",{"controlComposition":"REVIEWED_DYNAMIC_NODE_MAX","controlJoinMillis":10000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpMultiRoundTripTerminationRaceTests.java", "019aa23a6fa25dc356d0f1378551da43b2d493f681dc4d400aa633ceb879ddd2", [
    ["blockedCustomProtectorOpenMakesShutdownResidualUntilProtocolWorkExits","4e2940aadf6b81531aaf2c9fb67f63df03035d28ebd927256a287f35d629bdf2",{"controlComposition":"REVIEWED_OVERLAP_OR_DUPLICATE","controlJoinMillis":10015}],
    ["blockedCustomProtectorOpenDiscardsLateResultAfterDeadlineOrDisconnect","9fe0428fbf8a051f8856858b80b43e838880af781f3cf347ae86a50a84792910",{"controlComposition":"REVIEWED_DYNAMIC_NODE_MAX","controlJoinMillis":20020}],
    ["blockedSealCannotPublishLateInputRequiredAndReleasesExactlyOnce","9fa9b58c00aca22633217bf700bf7e481d25fd151076c03d2621399daecb4529",{"controlComposition":"REVIEWED_DYNAMIC_NODE_MAX","controlJoinMillis":15020}],
    ["sameAuthenticatedStateCanBranchWhileOneFreshIdTerminates","e2658db8bc19db08af218c4aa7408c71c66d309caa4d4edfe61b30847f57274c",{"controlComposition":"REVIEWED_DYNAMIC_NODE_MAX","controlJoinMillis":30025}],
    ["conditionalCapabilityHoldTerminatesWithoutProgressOrLateResult","e2a6c3af0f11bbd8c32e537b0c7209aebfeb71932f14c3f78dcf445f1d2d9bd1",{"controlComposition":"REVIEWED_DYNAMIC_NODE_MAX","controlJoinMillis":15015}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpProgressPublicRuntimeTests.java", "d965536d1830c1166faded97a72b426cf749fe9f9928e585d41fd7aa8cdcc9f6", [
    ["progressEnqueueWinsBeforeMappedErrorTerminal","bc346e059df77c8ec558fb5ff75cc18dfeb0cf6f8a81fdbb59fba6cda0552913",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":35000}],
    ["mappedErrorTerminalWinsAfterProgressEligibility","ac973dea8b26a499defa252028530323c1dd22286b3bf3a2463ffb99bcf47207",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":35000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpSimulationLifecyclePhaseTests.java", "87119b3a96e0faeec6cb5369b79cfced7ce3bfc10b912e070cd6dcc6c6129c69", [
    ["bridge_quiesce_is_idempotent_fences_starts_and_releases_proof","651edfd3936cd52eaded296076fc65cfbd0d0cf56cf58ac13cd06eb0d75fdc19",{"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","controlJoinMillis":5000}],
    ["graceful_simulation_drain_does_not_interrupt_admitted_handler","eb407f468ff6840a5a2ef38f804cc762d3382e5932fb52fdadea5f3c5102912a",{"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","controlJoinMillis":20000}],
    ["force_interrupts_admitted_handler_and_reaches_complete_barrier","1d6490f610fcb07fee99234e2a90ed50775f8d3e711f8a36236c6916ac2212df",{"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","controlJoinMillis":15000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpStreamSubscriptionDiagnosticsPublicRuntimeTests.java", "ced985535c94ce800f0a1f7e849999b204ad6ca12f221846ffb3cb86871ea432", [
    ["residualHandlerStopPublishesZeroStreamsBeforeLateHandlerExit","9b8d0f401f61b81f2a4eff908e7ba1855a0f6d9161b49907ecf72f79df1c4f5c",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":20000}],
  ]),
], 'lifecycle control topology');

const REVIEWED_SCOPE_OVERRIDES = mergeReviewedScopeOverrideMaps(
  REVIEWED_SCOPE_TOPOLOGY_OVERRIDES,
  REVIEWED_PHASE_POLICY_OVERRIDES,
  REVIEWED_CONTROL_OVERRIDES);

const REVIEWED_ORPHAN_HELPERS = checkedReviewMap([
  ...reviewedOrphanFile("src/test/java/com/soklet/InternalTransportEndpointTestCompatibility.java", "89141d7627fc3329742dfe3bb66364fdda2c509291b87cf4094efea180aeb992", [
    ["attach",31,"1883bfa6fe73fdd47cd502d6daeee8cb86c6860a345a5b7d5e40fe04fa5da300",{"path":"src/main/java/com/soklet/SokletDirectLifecycle.java","line":2418,"lineSha256":"03a5fa2917b2b6fbdb4493fc2f3cbcc9de997706e068a0218483f8b6b6ac9bd3","rationale":"The production direct lifecycle invokes the public HTTP endpoint attach contract."}],
    ["publicRuntime",45,"f360dda2e0fc8a7cb76981a7c6c47b6c79bcbe05f8c841d280de98f75b7164cd",{"path":"src/test/java/com/soklet/InternalTransportEndpointTestCompatibility.java","line":36,"lineSha256":"03c392d1a09359e546d74395faec5a2129994402a1e60e337059b35b43b63281","rationale":"The reviewed HTTP compatibility attach default invokes this adapter helper."}],
    ["attach",77,"c29b8ee82137d5a63ea9ee192c2f9f63e541ebee1e152ebee2f6b0cb612ef1b8",{"path":"src/main/java/com/soklet/SokletDirectLifecycle.java","line":2427,"lineSha256":"948de2c898997b5149018b2ccdbf693ee7971062c8b6b288f2db389fcd22dc73","rationale":"The production direct lifecycle invokes the public SSE endpoint attach contract."}],
    ["publicRuntime",91,"f360dda2e0fc8a7cb76981a7c6c47b6c79bcbe05f8c841d280de98f75b7164cd",{"path":"src/test/java/com/soklet/InternalTransportEndpointTestCompatibility.java","line":82,"lineSha256":"03c392d1a09359e546d74395faec5a2129994402a1e60e337059b35b43b63281","rationale":"The reviewed SSE compatibility attach default invokes this adapter helper."}],
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/McpLifecycleB3Tests.java", "5e251cc76ab78a8cc48bd86724fed02f984619e253ea17168d2dd1fe7dc2356b", [
    ["close",2707,"eb076ba581178078050f92c63303430e60bec9ec1a9ed8ec7c746d75f8453968",{"path":"src/test/java/com/soklet/McpLifecycleB3Tests.java","line":449,"lineSha256":"b153608a966dca6571b544635e46ccab9aca8126e95a5e6384e21db8425ca463","rationale":"A lifecycle test directly invokes the reviewed fixture close contract."}],
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/McpLocalizationFleetPublicRuntimeTests.java", "cba2a283c88859d95fab6b07815fd743604c445614e91ac334c021c507633b41", [
    ["start",405,"a54b1363a3dab8198bf40d09ee1934132622a39ba308ae3f7c7d7eb76c3b88f5",{"path":"src/test/java/com/soklet/McpLocalizationFleetPublicRuntimeTests.java","line":92,"lineSha256":"07b7acceb6a5e59384e6422898a20512037609331671db066348aae36a3e9479","rationale":"The lifecycle test directly invokes the reviewed two-node fleet start helper."}],
    ["close",444,"d936c7abbdd7481d460bf7045588d487c8b8351a19d9473faf10cafabb0c0e89",{"path":"src/test/java/com/soklet/McpLocalizationFleetPublicRuntimeTests.java","line":133,"lineSha256":"186351cad850a49204fd8ae41bd21e660277a6d5af1db6d4744e5f4004467b14","rationale":"The lifecycle test directly invokes the reviewed two-node fleet close helper."}],
    ["start",511,"4e9e880fcdcddc24379810ae107534c7360ef80b73c1011643098ae240c17db6",{"path":"src/test/java/com/soklet/McpLocalizationFleetPublicRuntimeTests.java","line":407,"lineSha256":"dff4a9b14f394a29901a412dce108efa98e67ab21e6150d23ad39072653b0802","rationale":"The reviewed two-node fleet start helper invokes each node start helper."}],
    ["stop",515,"1194e749f943c21d3ce81c218884aaaae258a5157a85ba9874b96d35a1f7d720",{"path":"src/test/java/com/soklet/McpLocalizationFleetPublicRuntimeTests.java","line":230,"lineSha256":"31223babeaccf4900f5d16c97ed0d3db4262a1be2bb763ecdf2f634968993f69","rationale":"The fleet lifecycle test invokes the reviewed node stop helper."}],
    ["close",646,"a9e477c06503aa8ca99ce466692d8fa8a71079cbb8b40d227a459f166e963e26",{"path":"src/test/java/com/soklet/McpLocalizationFleetPublicRuntimeTests.java","line":445,"lineSha256":"d998b1ab99968332971844e39c07c3958a461982d28d6849cf6d04a4df1c563c","rationale":"The reviewed two-node fleet close helper invokes each node close helper."}],
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/SokletApplicationObservationTests.java", "6880ff3614db6190e37ec90fb72d9340c590a3da62881abca8cad7f4a7375951", [
    ["start",1099,"8743bc5899534d2657576bdd66d59311499e6ba40769bb325879c3acfee3de6e",{"path":"src/main/java/com/soklet/SokletApplication.java","line":200,"lineSha256":"bdd0e99098bccd7fe3b3b3a9adbf1ebe8bac4505838be6613dd52b21a8ffc1ab","rationale":"The production application runner invokes the wrapped runtime start contract."}],
    ["shutdown",1104,"eef45a37c20721b055e31362bc503628c1bd6eea2a765b56fe50cfbae15a5c7f",{"path":"src/main/java/com/soklet/SokletApplication.java","line":355,"lineSha256":"51ee4edce47698ffa55e0d525992ee6e4f019166b070bd329ebfbad938b783dc","rationale":"The production application runner invokes the wrapped runtime shutdown contract."}],
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/SokletDirectCompositionIsolationTests.java", "651154c34c11cc5bbd959a9437977d2f0b59246f14fdecd0d831684dfda973f3", [
    ["attach",450,"c1957007e8911a16e93c35de1b206b6bc98d57a091f01c43f3f72c80a5b85ee1",{"path":"src/test/java/com/soklet/SokletDirectCompositionIsolationTests.java","line":51,"lineSha256":"59a5a5e95e2ba52cdbec6fb317cbd2020259569b690db93f89a7496fe515cfdb","rationale":"The direct composition test installs this lifecycle-owning decorator."}],
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/SokletDirectLateStartupIntegrationTests.java", "ede72e644c5737faba255abc533785db1d5a23bfc886664fa401c62bbdd47164", [
    ["close",876,"a2c47642265a189cc7764e547ae97971625558d5bc9359803865d61787c65067",{"path":"src/test/java/com/soklet/SokletDirectLateStartupIntegrationTests.java","line":101,"lineSha256":"50edadd1377f4ad9436d7c5312200d6544b0137965aa8c5b049fc702b3170b05","rationale":"The lifecycle test's try-with-resources scope invokes the reviewed owner-harness close helper."}],
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/SokletDirectSseCompositionTests.java", "5481128e8f1b04fa9a9a508c451e181fab4cf4fde7b5903814c1340b6a0533af", [
    ["attach",676,"17fd3dfd5a458ef9f75e3e027f6265f8591e250ece129660aebfdc4b33f7076a",{"path":"src/test/java/com/soklet/SokletDirectSseCompositionTests.java","line":88,"lineSha256":"3489e198d1cf9063ad182a92b4e5ac78e33f635e650cc7ab1484245db4190b2c","rationale":"The SSE composition test installs this lifecycle-owning decorator."}],
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/SokletDirectStartClaimTruthTableTests.java", "bf9dc53cbe56c4c4f81a32c5c06f836e93ce815c8c2b818abb10c0c6cdc70a08", [
    ["close",189,"72d21aa1b74efb7d81f57e986ddc30f6fab8e5f8a955335e5f95d5e655500b3f",{"path":"src/test/java/com/soklet/SokletDirectStartClaimTruthTableTests.java","line":144,"lineSha256":"a652045ac401b11d233cba856e6cd5c586f26926053ff1ec249d0bb80c947f87","rationale":"The lifecycle test's try-with-resources scope invokes the reviewed truth-race cleanup helper."}],
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/SokletDirectTerminalPublicationTests.java", "06996f724b9b6b881b81868186e5742d8eaf59773a195944a99c74e3b0c826f5", [
    ["create",430,"7e0266a1ff39c91caeff040d7a3e5a7712aad3a6c58af387450cac8f5e69492d",{"path":"src/test/java/com/soklet/SokletDirectTerminalPublicationTests.java","line":94,"lineSha256":"8b0299d6130be80b0de8400d73d5ce7177bd1181ee4ff028f0322f5f214f53ab","rationale":"The lifecycle test directly constructs the reviewed owner harness."}],
    ["close",453,"df08d934601614ca68816224f6d17016463202755d3f8dbf6f741e0acc85f3a9",{"path":"src/test/java/com/soklet/SokletDirectTerminalPublicationTests.java","line":94,"lineSha256":"8b0299d6130be80b0de8400d73d5ce7177bd1181ee4ff028f0322f5f214f53ab","rationale":"The lifecycle test's try-with-resources scope invokes the reviewed owner-harness close helper."}],
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/SokletDirectTerminationPrecedenceTests.java", "4803d9fb89add3afeb02b2f4d0642e1075e3aae959e34f677f02dd38381fac03", [
    ["close",504,"e406547b24c34bad6ed8587e1768d7d8a1dbb25d260fef67b4e4bf3027653d3d",{"path":"src/test/java/com/soklet/SokletDirectTerminationPrecedenceTests.java","line":247,"lineSha256":"05e81d8b560c9a1c5498861a13c717af06a07bba454d7305b66bcace0f6b18af","rationale":"The lifecycle test's try-with-resources scope invokes the reviewed precedence-harness close helper."}],
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/SokletDirectWaitSemanticsTests.java", "07c280513edcf9cb753950b18dc211fa83c605797a9bcc91e37be91bc2c9fe1e", [
    ["close",334,"4657b5a39ea6a1e00289aec01e92fbb4937e924fd3e088df0f935fb7c47d96e4",{"path":"src/test/java/com/soklet/SokletDirectWaitSemanticsTests.java","line":85,"lineSha256":"2d7292bc4288524114095a2a36625c5d885e0291fc3b49b4cab843e556a01253","rationale":"The direct wait-semantics test constructs the reviewed AutoCloseable wait harness."}],
  ]),
], 'orphan lifecycle helper');

const LIFECYCLE_SIGNAL_PATTERN = /(?:\bSoklet(?:Application(?:Options)?|Config|Simulator)?\b|\b(?:Http|Sse|Mcp)Server\b|\bMcpHttpServerRuntime\b|\bTransportRuntime\b|\bInternalLifecycleCoordinator\b|\bSimulationSession\b|\bLifecyclePolicy\b|\b(?:startupTimeout|startupCancelationTimeout|gracefulShutdownTimeout|forcedShutdownTimeout)\s*\()/u;
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

function zeroArgumentLegacyCall(record) {
  const calls = [...record.sourceLine.matchAll(
    /\bshutdownTimeout\s*\(([^)]*)\)/gu,
  )];
  return calls[record.occurrenceIndex]?.[1].trim() === '';
}

function currentLegacyExclusionAllowed(record) {
  return /api\/mcp\/(?:current|phase-0)-incompatibilities\.jsonl$/u
      .test(record.path)
    || ((record.path.startsWith('src/main/java/com/soklet/internal/mcp/protocol/')
      || record.path.startsWith('src/test/java/com/soklet/internal/mcp/protocol/'))
      && zeroArgumentLegacyCall(record));
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
  const applicationReceiverNames = [...new Set([
    ...[...masked.matchAll(
      /\bSokletApplication\s+([A-Za-z_$][\w$]*)\b/gu)]
      .map((match) => match[1]),
    ...[...masked.matchAll(
      /\bvar\s+([A-Za-z_$][\w$]*)\s*=\s*SokletApplication\s*\.\s*fromConfig\s*\(/gu)]
      .map((match) => match[1]),
  ])];
  const lifecycleReceiverNames = [...new Set([
    ...[...masked.matchAll(
      /\b(?:Soklet(?:\s*\.\s*DefaultSimulator)?|SokletApplication|SokletDirectLifecycle|HttpServer|SseServer|McpServer|TransportRuntime|InternalLifecycleCoordinator|SimulationSession|Fixture|Fleet|Graph|LifecycleHarness|Node|Owner|Runtime|[A-Za-z_$][\w$]*(?:Fixture|Fleet|Graph|Harness|HttpServer|SseServer|McpServer|LifecycleAdapter|LifecycleHarness|Node|Owner|PhaseGate|Runtime|RuntimeBridge|Simulator))\s+([A-Za-z_$][\w$]*)\b/gu)]
      .map((match) => match[1]),
    ...[...masked.matchAll(
      /\bvar\s+([A-Za-z_$][\w$]*)\s*=\s*(?:Soklet\s*\.\s*fromConfig\s*\(|SokletApplication\s*\.\s*fromConfig\s*\(|new\s+SokletDirectLifecycle\s*\(|(?:new\s+)?[A-Za-z_$][\w$]*Harness(?:\s*\.|\s*\())/gu)]
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
      applicationReceiverNames: applicationReceiverNames.filter((name) =>
        new RegExp(`\\b${name}\\b`, 'u').test(masked.slice(openBrace + 1,
          cursor - 1))),
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
    ['startupCancelationTimeout', 'startupCancellationMillis'],
    ['gracefulShutdownTimeout', 'gracefulShutdownMillis'],
    ['forcedShutdownTimeout', 'forcedShutdownMillis'],
  ]) {
    const setterMatches = [...chain.matchAll(new RegExp(
      `\\.\\s*${setter}\\s*\\(`, 'gu'))];
    for (const setterMatch of setterMatches) {
      const openParenthesis = setterMatch.index
        + setterMatch[0].lastIndexOf('(');
      const end = matchingParenthesisEnd(chain, openParenthesis);
      if (end === null) return null;
      const expression = chain.slice(openParenthesis + 1, end - 1).trim();
      const duration = expression === 'null' ? DEFAULT_PHASE_POLICY[field]
        : resolveDurationExpression(expression, durationConstants);
      if (duration === undefined) return null;
      policy[field] = duration;
    }
  }
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

function javaConditionalExpressionBranches(body) {
  const depths = [];
  const pendingQuestions = [];
  const pairs = [];
  let braceDepth = 0;
  let bracketDepth = 0;
  let parenthesisDepth = 0;
  const depth = () => ({ braceDepth, bracketDepth, parenthesisDepth });
  const sameDepth = (left, right) =>
    left.braceDepth === right.braceDepth
      && left.bracketDepth === right.bracketDepth
      && left.parenthesisDepth === right.parenthesisDepth;
  const isWildcard = (index) => /^(?:extends\b|super\b|[,&>])/u.test(
    body.slice(index + 1).trimStart());

  for (let index = 0; index < body.length; index += 1) {
    depths.push(depth());
    const character = body[index];
    if (character === '?') {
      if (!isWildcard(index))
        pendingQuestions.push({ index, ...depth() });
    } else if (character === ':' && body[index - 1] !== ':'
        && body[index + 1] !== ':') {
      const colonDepth = depth();
      const questionIndex = pendingQuestions.findLastIndex((question) =>
        sameDepth(question, colonDepth));
      if (questionIndex >= 0) {
        const question = pendingQuestions.splice(questionIndex, 1)[0];
        pairs.push({
          colonIndex: index,
          questionIndex: question.index,
          ...colonDepth,
        });
      }
    } else if (character === ';') {
      const boundaryDepth = depth();
      for (let questionIndex = pendingQuestions.length - 1;
        questionIndex >= 0; questionIndex -= 1) {
        if (sameDepth(pendingQuestions[questionIndex], boundaryDepth))
          pendingQuestions.splice(questionIndex, 1);
      }
    }
    if (character === '(') parenthesisDepth += 1;
    else if (character === ')') parenthesisDepth -= 1;
    else if (character === '[') bracketDepth += 1;
    else if (character === ']') bracketDepth -= 1;
    else if (character === '{') braceDepth += 1;
    else if (character === '}') braceDepth -= 1;
  }
  depths.push(depth());

  const colonQuestions = new Map(pairs.map((pair) =>
    [pair.colonIndex, pair.questionIndex]));
  const expressionEnd = (pair) => {
    for (let index = pair.colonIndex + 1; index < body.length; index += 1) {
      const character = body[index];
      const siteDepth = depths[index];
      if ((character === ';' || character === ',')
          && sameDepth(pair, siteDepth)) return index;
      if (character === ')' && pair.parenthesisDepth === siteDepth.parenthesisDepth
          && pair.bracketDepth === siteDepth.bracketDepth
          && pair.braceDepth === siteDepth.braceDepth) return index;
      if (character === ']' && pair.bracketDepth === siteDepth.bracketDepth
          && pair.parenthesisDepth === siteDepth.parenthesisDepth
          && pair.braceDepth === siteDepth.braceDepth) return index;
      if (character === '}' && pair.braceDepth === siteDepth.braceDepth
          && pair.parenthesisDepth === siteDepth.parenthesisDepth
          && pair.bracketDepth === siteDepth.bracketDepth) return index;
      if (character === ':' && sameDepth(pair, siteDepth)
          && (colonQuestions.get(index) ?? pair.questionIndex)
            < pair.questionIndex) return index;
    }
    return body.length;
  };
  return pairs.map((pair, index) => ({
    ...pair,
    endIndex: expressionEnd(pair),
    id: index,
  }));
}

function conditionalInvocationFacts(body, pattern) {
  const branches = javaConditionalExpressionBranches(body);
  const branchesById = new Map(branches.map((branch) =>
    [branch.id, branch]));
  const sites = [...body.matchAll(pattern)].map((match) => {
    const repetition = repetitionContext(body, match.index);
    return {
      branches: branches.flatMap((branch) => {
        if (branch.questionIndex < match.index
            && match.index < branch.colonIndex)
          return [[branch.id, true]];
        if (branch.colonIndex < match.index
            && match.index < branch.endIndex)
          return [[branch.id, false]];
        return [];
      }),
      multiplier: repetition.multiplier,
      unresolved: repetition.unresolved,
    };
  });
  const maximumCompatibleTotal = (candidates, field) => {
    if (candidates.length === 0) return 0;
    const branchId = [...new Set(candidates.flatMap((site) =>
      site.branches.map(([id]) => id)))].sort((left, right) => {
        const leftBranch = branchesById.get(left);
        const rightBranch = branchesById.get(right);
        // Resolve the outer conditional first so its opposite branch is not
        // added to a nested branch as though both could execute sequentially.
        return (rightBranch.endIndex - rightBranch.questionIndex)
          - (leftBranch.endIndex - leftBranch.questionIndex) || left - right;
      })[0];
    if (branchId === undefined)
      return candidates.reduce((total, site) => total + site[field], 0);
    const unconditional = candidates.filter((site) =>
      !site.branches.some(([id]) => id === branchId));
    const conditional = (value) => candidates.filter((site) =>
      site.branches.some(([id, branchValue]) => id === branchId
        && branchValue === value)).map((site) => ({
          ...site,
          branches: site.branches.filter(([id]) => id !== branchId),
        }));
    return maximumCompatibleTotal(unconditional, field)
      + Math.max(maximumCompatibleTotal(conditional(true), field),
        maximumCompatibleTotal(conditional(false), field));
  };
  return {
    count: maximumCompatibleTotal(sites, 'multiplier'),
    unresolved: maximumCompatibleTotal(sites, 'unresolved'),
  };
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
    const startupMillis = resolveDurationExpression(args[0],
      durationConstants);
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
  const applicationReceiver = method.applicationReceiverNames.length === 0
    ? '(?!)' : `(?:${method.applicationReceiverNames
      .map((name) => name.replace(/[.*+?^${}()|[\]\\]/gu, '\\$&'))
      .join('|')})`;
  const applicationRunPattern = () => new RegExp(
    `(?:(?:\\bSokletApplication\\s*\\.\\s*run|\\b${applicationReceiver}\\s*\\.\\s*run)\\s*\\(|\\b(?:SokletApplication|${applicationReceiver})\\s*::\\s*run\\b)`,
    'gu');
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
  const cleanupConfigured = /\.\s*afterCompleteShutdown\s*\(/u.test(body)
    || /\bShutdownCleanup\b/u.test(body);
  const cleanupDurationMultipliers = {
    Days: 86_400_000,
    Hours: 3_600_000,
    Micros: 1 / 1_000,
    Millis: 1,
    Minutes: 60_000,
    Nanos: 1 / 1_000_000,
    Seconds: 1_000,
  };
  const inlineCleanupDurations = [...body.matchAll(
    /(?:\.\s*afterCompleteShutdown|\bShutdownCleanup\s*\.\s*fromTimeoutAndAction|\bcleanup)\s*\(\s*(?:java\s*\.\s*time\s*\.\s*)?Duration\s*\.\s*of(Days|Hours|Micros|Millis|Minutes|Nanos|Seconds)\s*\(\s*([0-9][0-9_]*)[lL]?\s*\)/gu)]
    .map((match) => Math.ceil(Number.parseInt(
      match[2].replaceAll('_', ''), 10)
        * cleanupDurationMultipliers[match[1]]));
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
    /\.\s*(internalLifecyclePolicy|lifecyclePolicy)\s*\(/gu)) {
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
    const publicDefaultReset = match[1] === 'lifecyclePolicy'
      && argument === 'null';
    if (publicDefaultReset) {
      literalPhasePolicies.push({
        forcedShutdownMillis: DEFAULT_PHASE_POLICY.forcedShutdownMillis,
        gracefulShutdownMillis: DEFAULT_PHASE_POLICY.gracefulShutdownMillis,
        startupCancellationMillis:
          DEFAULT_PHASE_POLICY.startupCancellationMillis,
        startupMillis: DEFAULT_PHASE_POLICY.startupMillis,
      });
    }
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
          && !publicDefaultReset
          && field?.phasePolicy === undefined))
      unresolvedPolicyInstallationCount += 1;
  }
  if (policyInstallationCount > 0
      || matches(/(?:\bLifecyclePolicy\s*\.\s*(?:builder|fromDefaults)\s*\(|\b(?:shutdownPolicy|cancellationPolicy|handlerPolicy|shortShutdownPolicy|managedLockProbeShutdownPolicy)\s*\()/u))
    operations.push('CONFIGURE_POLICY');
  if (cleanupConfigured)
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
  if (matches(applicationRunPattern()) || matches(/\bstartRunner\s*\(/u))
    operations.push('RUN_APPLICATION');
  if (matches(/(?:\bSokletSimulator\s*\.\s*run\s*\(|\brunConcurrentScope\s*\()/u))
    operations.push('RUN_SIMULATOR');
  const namedReceivers = method.receiverNames
    .map((name) => name.replace(/[.*+?^${}()|[\]\\]/gu, '\\$&'));
  const lifecycleReceiver = namedReceivers.length === 0 ? '(?!)'
    : String.raw`(?:${namedReceivers.join('|')})(?:\s*\.\s*[A-Za-z_$][\w$]*\s*\(\s*\))?`;
  // McpSimulationRuntime is one request-capture handle, and close() asks its
  // bound request controller to cancel that request.  It does not own or close
  // a Soklet/transport lifecycle generation despite the generic *Runtime name.
  // Resolve this at the declaration in the current callable so another method
  // may still use the same local name for a real lifecycle runtime.
  const nonLifecycleCloseReceivers = new Set([...body.matchAll(
    /\bMcpSimulationRuntime\s+([A-Za-z_$][\w$]*)\b/gu)]
    .map((match) => match[1]));
  const namedCloseReceivers = method.receiverNames
    .filter((name) => !nonLifecycleCloseReceivers.has(name))
    .map((name) => name.replace(/[.*+?^${}()|[\]\\]/gu, '\\$&'));
  const lifecycleCloseReceiver = namedCloseReceivers.length === 0 ? '(?!)'
    : String.raw`(?:${namedCloseReceivers.join('|')})(?:\s*\.\s*[A-Za-z_$][\w$]*\s*\(\s*\))?`;
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
      || matches(new RegExp(`try\\s*\\(\\s*${lifecycleCloseReceiver}\\s*(?:;|\\))`, 'iu'))
      || matches(new RegExp(`\\b${lifecycleCloseReceiver}\\s*\\.\\s*close\\s*\\(`, 'iu')))
    operations.push('CLOSE');
  const ambiguousReceiverSites = new Map();
  for (const match of body.matchAll(
    /\(\(\s*(?:Soklet|HttpServer|SseServer|McpServer|TransportRuntime|InternalLifecycleCoordinator|SokletDirectLifecycle)\s*\)\s*[^()]+\)\s*\.\s*(start|beginStart|shutdown|stop|close)\s*\(/gu))
    ambiguousReceiverSites.set(`${match.index}:${match[1]}`, match[1]);
  for (const match of body.matchAll(
    /\bidentity\s*\(\s*([A-Za-z_$][\w$]*)\s*\)\s*\.\s*(start|beginStart|shutdown|stop|close)\s*\(/gu)) {
    if (method.receiverNames.includes(match[1])
        && !(match[2] === 'close'
          && nonLifecycleCloseReceivers.has(match[1])))
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
  const applicationRunFacts = conditionalInvocationFacts(body,
    applicationRunPattern());
  const otherGenerationPattern =
    /(?:\bSokletSimulator\s*\.\s*run\s*\(|\bopenSimulationSession\s*\()/gu;
  const hasExecution = orderedOperations.some((operation) => [
    'OPEN_SIMULATION_SESSION', 'RUN_APPLICATION', 'RUN_SIMULATOR', 'START',
    'SHUTDOWN_OR_STOP', 'AWAIT_TERMINATION', 'CLOSE',
  ].includes(operation));
  let unresolvedLifecycleRepetitionCount = applicationRunFacts.unresolved;
  let syntacticGenerations = applicationRunFacts.count;
  for (const match of body.matchAll(otherGenerationPattern)) {
    const repetition = repetitionContext(body, match.index);
    syntacticGenerations += repetition.multiplier;
    unresolvedLifecycleRepetitionCount += repetition.unresolved;
  }
  const applicationRunSiteCount = applicationRunFacts.count;
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
    cleanupConfigured,
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
    hasInlinePolicy: /\.\s*(?:startupTimeout|startupCancelationTimeout|gracefulShutdownTimeout|forcedShutdownTimeout)\s*\(/u
      .test(body),
    hasInlineNoStartupTimeout: false,
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
        hasNoStartupTimeout: false,
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
    'SOURCE_CONFIGURED_STANDARD_GUARD'].includes(policy.mode))
    fail(`Unknown lifecycle scope phasePolicy mode ${policy.mode}.`);
  for (const field of ['forcedShutdownMillis', 'gracefulShutdownMillis',
    'startupCancellationMillis']) {
    if (!Number.isSafeInteger(policy[field]) || policy[field] < 0)
      fail(`lifecycle scope phasePolicy ${field} must be a nonnegative integer.`);
  }
  if (policy.controlledStartupMillis !== null)
    fail('lifecycle scope phasePolicy controlledStartupMillis must be null.');
  if (!Number.isSafeInteger(policy.startupMillis)
      || policy.startupMillis < 0)
    fail('lifecycle scope phasePolicy startupMillis must be a nonnegative integer.');
  const startup = policy.startupMillis;
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
    startupMillis: Math.max(...source.literalPhasePolicies.map((policy) =>
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
      mode: source.hasLocalPolicy || source.literalPhasePolicies.length > 0
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
  'controlledLifecycleCoreMillis',
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
    'controlledLifecycleCoreMillis',
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
    if (!Number.isSafeInteger(override.phasePolicy.startupMillis)
        || override.phasePolicy.startupMillis < 0)
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
        || generation.count < 1 || generation.complete < 0
        || generation.incomplete < 0 || generation.prior < 0)
      fail(`Reviewed generation override is invalid: ${source.id}.`);
    if (!['SINGLE', 'SEQUENTIAL', 'CONCURRENT_OR_ALTERNATIVE',
      'MIXED_MAX_PLUS_SEQUENTIAL', 'ONE_FULL_PLUS_PREINIT_REJECTIONS',
      'PRECOMMIT_REJECTIONS_ONLY']
      .includes(generation.mode)
        || (generation.mode === 'SINGLE'
          && (generation.count !== 1 || generation.complete !== 1
            || generation.prior !== 0 || generation.incomplete !== 1))
        || (generation.mode === 'SEQUENTIAL'
          && (generation.count < 2
            || generation.complete !== generation.count
            || generation.incomplete < 1
            || generation.prior + generation.incomplete
              !== generation.count))
        || (generation.mode === 'CONCURRENT_OR_ALTERNATIVE'
          && (generation.count < 2 || generation.complete !== 1
            || generation.prior !== 0 || generation.incomplete !== 1))
        || (generation.mode === 'MIXED_MAX_PLUS_SEQUENTIAL'
          && (generation.count < 2 || generation.complete < 2
            || generation.incomplete < 1
            || generation.complete > generation.count
            || generation.prior + generation.incomplete
              > generation.count))
        || (generation.mode === 'ONE_FULL_PLUS_PREINIT_REJECTIONS'
          && (generation.count < 2 || generation.complete !== 1
            || generation.prior !== 0 || generation.incomplete !== 1))
        || (generation.mode === 'PRECOMMIT_REJECTIONS_ONLY'
          && (source.applicationRunSiteCount < 1
            || generation.count !== source.applicationRunSiteCount
            || generation.complete !== 0 || generation.prior !== 0
            || generation.incomplete !== 0)))
      fail(`Reviewed generation topology is invalid: ${source.id}.`);
    if (override?.generation === undefined
        && generation.count < source.generationSiteCount)
      fail(`Lifecycle generation review understates source evidence: ${source.id}.`);
    if (override?.generation !== undefined
        && override.generation.count !== generation.count)
      fail(`Lifecycle generation override is internally inconsistent: ${source.id}.`);

    const phasePolicy = {
      controlledStartupMillis: null,
      ...sourcePolicy,
      mode: configuredPolicy
        ? source.hasInlinePolicy ? 'LOCAL_FINITE_REVIEWED'
          : 'SOURCE_CONFIGURED_FINITE_WITH_PROOF'
          : 'INHERITED_DEFAULT',
    };
    const bounds = scopePathBounds(phasePolicy);
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
    const oneFullPlusPreinitializationRejections =
      generation.mode === 'ONE_FULL_PLUS_PREINIT_REJECTIONS';
    const precommitRejectionsOnly =
      generation.mode === 'PRECOMMIT_REJECTIONS_ONLY';
    if (!Number.isSafeInteger(applicationCleanupCount)
        || applicationCleanupCount < 0
        || (source.cleanupConfigured && applicationCleanupCount < 1
          && !oneFullPlusPreinitializationRejections
          && !precommitRejectionsOnly)
        || (!source.cleanupConfigured && applicationCleanupCount !== 0))
      fail(`Lifecycle cleanup repetition count is invalid: ${source.id}.`);
    if (precommitRejectionsOnly && applicationCleanupCount !== 0)
      fail(`Precommit application rejections cannot run lifecycle cleanup: ${source.id}.`);
    if (oneFullPlusPreinitializationRejections
        && applicationCleanupCount > generation.complete)
      fail(`Lifecycle cleanup repetition count exceeds fully executing application runs: ${source.id}.`);
    if (!oneFullPlusPreinitializationRejections && !precommitRejectionsOnly
        && source.applicationRunSiteCount > 1 && source.cleanupConfigured
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
        || (source.terminalReportExpected && terminalReportCount < 1
          && !precommitRejectionsOnly)
        || (!source.terminalReportExpected && terminalReportCount !== 0))
      fail(`Lifecycle terminal-report repetition count is invalid: ${source.id}.`);
    if (precommitRejectionsOnly && terminalReportCount !== 0)
      fail(`Precommit application rejections cannot publish terminal reports: ${source.id}.`);
    if (oneFullPlusPreinitializationRejections
        && source.terminalReportExpected
        && terminalReportCount !== generation.complete)
      fail(`Lifecycle terminal-report count must match fully executing application runs: ${source.id}.`);
    if (!oneFullPlusPreinitializationRejections && !precommitRejectionsOnly
        && source.applicationRunSiteCount > 0
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
    const classification = configuredPolicy
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
        controlledCompletionProof: null,
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
    /\.startupCancelationTimeout\s*\(\s*Duration\.ofSeconds\s*\(\s*1\s*\)\s*\)/u,
    'official 1-second startup cancellation');
  requirePattern(officialFixture,
    /\.gracefulShutdownTimeout\s*\(\s*Duration\.ofSeconds\s*\(\s*5\s*\)\s*\)/u,
    'official 5-second graceful shutdown');
  requirePattern(officialFixture,
    /\.forcedShutdownTimeout\s*\(\s*Duration\.ofSeconds\s*\(\s*1\s*\)\s*\)/u,
    'official 1-second forced shutdown');
  for (const setter of ['startupCancelationTimeout',
    'gracefulShutdownTimeout', 'forcedShutdownTimeout']) {
    requirePolicySetterCount(officialFixture, setter,
      `official unique ${setter}`, 1);
  }
	requirePolicySetterCount(officialFixture, 'startupTimeout',
		'official no startup-timeout override', 0);

  for (const path of [
    'soak/src/test/java/com/soklet/HttpSoakTests.java',
    'soak/src/test/java/com/soklet/RealtimeTransportSoakTests.java',
  ]) {
    const rawText = texts.get(path);
    if (rawText === undefined) fail(`Missing ${path}.`);
    const text = maskJavaSource(rawText);
    requirePattern(text,
      /\.gracefulShutdownTimeout\s*\(\s*Duration\.ofSeconds\s*\(\s*3\s*\)\s*\)/u,
      `${path} 3-second graceful shutdown`);
    requirePattern(text,
      /\.forcedShutdownTimeout\s*\(\s*Duration\.ZERO\s*\)/u,
      `${path} immediate force boundary`);
    requirePolicySetterCount(text, 'gracefulShutdownTimeout',
      `${path} unique graceful policy setter`, 1);
    requirePolicySetterCount(text, 'forcedShutdownTimeout',
      `${path} unique forced policy setter`, 1);
		for (const setter of ['startupTimeout', 'startupCancelationTimeout'])
			requirePolicySetterCount(text, setter, `${path} no ${setter}`, 0);
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
      /\.startupCancelationTimeout\s*\(\s*Duration\.ofSeconds\s*\(\s*2\s*\)\s*\)/u,
      `${path} 2-second startup cancellation`);
    requirePattern(text,
      /\.gracefulShutdownTimeout\s*\(\s*PROFILE\.gracefulShutdownTimeout\s*\(\s*\)\s*\)/u,
      `${path} profile graceful wiring`);
    requirePattern(text,
      /\.forcedShutdownTimeout\s*\(\s*PROFILE\.forcedShutdownTimeout\s*\(\s*\)\s*\)/u,
      `${path} profile forced wiring`);
		for (const setter of ['startupTimeout', 'startupCancelationTimeout',
			'gracefulShutdownTimeout', 'forcedShutdownTimeout']) {
			requirePolicySetterCount(text, setter, `${path} unique ${setter}`, 1);
		}
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
    /stopThread\.join\s*\(\s*PROFILE\.gracefulShutdownTimeout\s*\(\s*\)\s*\.plus\s*\(\s*PROFILE\.forcedShutdownTimeout\s*\(\s*\)\s*\)\s*\.plus\s*\(\s*PROFILE\.settleTimeout\s*\(\s*\)\s*\)\s*\.toMillis\s*\(\s*\)\s*\)/u,
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
