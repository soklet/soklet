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
    'installedBuiltInDelegateGracefullyTerminatesBeforeStart',
    'installedAttachmentProvenOnlyAfterForceIsForced',
    'installedAttachmentMissingProofIsExactUnknown',
    'pendingAttachProofAndFailureBecomePreReadyEventsOnlyAfterCommit',
    'pendingAttachEventsCannotOverrideThrowOrNullPrecedence',
    'lateStartReturnDuringGraceCatchesUpAfterIndependentIngressQuiesce',
    'lateStartReturnAfterGraceReceivesForceAsItsFirstUnderlyingPhase',
    'startReturnAfterTerminalFreezeIsForcedWithoutRewritingUnknown',
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
  ['src/test/java/com/soklet/internal/mcp/protocol/McpResultEnvelopeGoldenProductionTests.java#everyFrameworkAndApplicationCompleteAuthorityMatchesGoldens#TEST', 4],
]);
// Exact reviewed cardinalities whose source topology is intentionally broader
// than the conservative syntactic generation-site floor.  Keeping this
// authority separate from REVIEWED_SCOPE_OVERRIDES means neither a regenerated
// inventory nor an accidentally reduced manual override can silently turn a
// reviewed loop/helper topology back into a single generation.
const REQUIRED_REVIEWED_GENERATION_COUNTS = new Map([
  // Canonical rendering and both fallback modes start three owners inside each independently guarded revision node.
  ["src/test/java/com/soklet/McpLocalizationInitializationPublicRuntimeTests.java#wholeResponseFallbackAndNoLocalizerPreserveCanonicalBytes#TEST", 3],
  ["src/test/java/com/soklet/McpLegacySessionTransportCapacityRuntimeTests.java#temporary_handler_capacity_keeps_get_fenced_and_retries_without_replacing_its_stream#TEST",2],
  ["src/test/java/com/soklet/McpLegacySessionTransportCapacityRuntimeTests.java#four_ignoring_renewals_hold_global_maintenance_capacity_after_their_gets_expire#TEST",2],
  ["src/test/java/com/soklet/McpLegacyCatalogPaginationPublicRuntimeTests.java#changedCatalogRejectsAnOldCursorButEquivalentInstancesCanResumeIt#TEST", 6],
  ["src/test/java/com/soklet/McpLegacyCompletionPublicRuntimeTests.java#legacyCompletionEnvelopesAgreeOnTheListenerAndSimulator#TEST", 2],
  ["src/test/java/com/soklet/McpLegacySessionPublicRuntimeTests.java#ownerResolverFailureAndInvalidKeysFailClosedWithoutPublishingIdentifiers#TEST", 5],
  ["src/test/java/com/soklet/McpLegacySessionPublicRuntimeTests.java#hardSessionExpiryCancelsPhysicalWorkAndCompletesItsUsablePostWithACorrelatedError#TEST", 2],
  ["src/test/java/com/soklet/McpLegacySessionPublicRuntimeTests.java#anonymousAllocationRequiresExplicitOptInAndUsesFreshAdmission#TEST", 2],
  ["src/test/java/com/soklet/McpLegacySessionTransportPublicRuntimeTests.java#getUsesBoundedFreshRenewalAndPhysicalHttpObservationWithoutRpcStreamMetrics#TEST", 2],
  ["src/test/java/com/soklet/McpLegacySessionTransportPublicRuntimeTests.java#reconciliationDenialAndOwnerChangeCloseOnlyTheGetAndCannotResurrectIt#TEST", 4],
  ["src/test/java/com/soklet/McpLegacySessionTransportPublicRuntimeTests.java#deleteEndsAnExistingGetAndShutdownBalancesMetricsAndDiagnostics#TEST", 2],
  ["src/test/java/com/soklet/McpLegacySessionTransportPublicRuntimeTests.java#slowReauthorizationCannotDeliverPastLeaseAndRetainsPhysicalReservationUntilExit#TEST", 2],
  ["src/test/java/com/soklet/McpLegacySessionTransportPublicRuntimeTests.java#sameSessionReplacementTransfersQuotaAndCapacityDenialPreservesTheExistingGet#TEST", 4],
  ["src/test/java/com/soklet/McpLegacySessionTransportSimulatorTests.java#publishedBeforeAckSessionGetThenDeleteCompletesZeroMessageSseWithExactReason#TEST", 2],
  ["src/test/java/com/soklet/McpLegacySessionTransportSimulatorTests.java#simulatorDisconnectEndsOnlyTheGetAndModernSelectionCannotMutateTheSession#TEST", 2],
  ["src/test/java/com/soklet/McpLegacySubscriptionPublicRuntimeTests.java#simulatorAdmissionExposesValidatedSubscribeSelectionAndUnsubscribeOnlyItsOperationName#TEST", 2],
  ["src/test/java/com/soklet/McpLegacySubscriptionPublicRuntimeTests.java#invalidUriRequiresAdmissionAndSessionValidationAndMissingOrDeniedReadableRoutesAreNeutral#TEST", 2],
  ["src/test/java/com/soklet/McpLegacySubscriptionPublicRuntimeTests.java#simulatorGetCallerSubsetCannotDeliverOtherFamiliesOrUnsubscribedUrisAndModernIsIndependent#TEST", 2],
  ["src/test/java/com/soklet/McpLegacySubscriptionPublicRuntimeTests.java#callerPolicyAndCustomResourceCatalogHintsAreCoarseAndRearmOnlyAfterAnAdmittedList#TEST", 2],
  ["src/test/java/com/soklet/McpLegacySubscriptionPublicRuntimeTests.java#immutableCallerIndependentCatalogHintsAreSuppressedButUriUpdatesRemainAvailable#TEST", 2],
  ["src/test/java/com/soklet/McpLegacySubscriptionPublicRuntimeTests.java#localizationInvalidationWorksWithoutCallerPolicyAndIgnoresModernOnlyLocalizedOwners#TEST", 3],
  ["src/test/java/com/soklet/McpLegacySubscriptionPublicRuntimeTests.java#modernOnlyLocalizationCannotMakeAnImmutableLegacyCatalogEmitHints#TEST", 2],
  ["src/test/java/com/soklet/McpLegacySubscriptionPublicRuntimeTests.java#reconciliationDuringEstablishmentDiscardsStaleDenialAndUsesFreshCancellationLease#TEST", 2],
  ["src/test/java/com/soklet/McpLegacySubscriptionPublicRuntimeTests.java#subscribeUsesOrdinaryQuotaWhileVerifiedUnsubscribeCanReleaseTheEstablishedGrant#TEST", 2],
  ["src/test/java/com/soklet/McpLegacySubscriptionPublicRuntimeTests.java#equivalentUriSpellingsSharePublisherMatchingAndUnsubscribeIdentity#TEST", 2],
  ["src/test/java/com/soklet/McpLegacySubscriptionPublicRuntimeTests.java#delayedGrantFenceCannotCancelRenewalStartedUnderThatFencedGeneration#TEST", 2],
  ["src/test/java/com/soklet/McpLegacySubscriptionPublicRuntimeTests.java#delayedGetFenceCannotCancelRenewalStartedUnderThatFencedGeneration#TEST", 2],
  ["src/test/java/com/soklet/McpLegacySubscriptionPublicRuntimeTests.java#refreshedBearerGetDoesNotRefreshUriEvidenceAndRevocationRetiresBothPermissions#TEST", 2],
  ["src/test/java/com/soklet/McpLegacySubscriptionPublicRuntimeTests.java#reconciliationDispatchesShortGetLeaseBeforeSlowLongLivedUriRenewals#TEST", 2],
  ["src/test/java/com/soklet/McpLegacySubscriptionPublicRuntimeTests.java#reconciliationDispatchesShortUriLeaseBeforeSlowLongLivedUriRenewals#TEST", 2],
  ["src/test/java/com/soklet/McpLegacySubscriptionPublicRuntimeTests.java#detachedGrantDenialRetiresItsSessionAndRequiresReinitialization#TEST", 2],
  ["src/test/java/com/soklet/internal/mcp/protocol/McpHttpServerObservationTerminalRaceTests.java#legacy_sse_handoff_failure_detaches_delivery_without_refunding_running_work#TEST", 2],
  ["src/test/java/com/soklet/internal/mcp/protocol/McpHttpServerRequestScopedSseTests.java#reset_before_http_offer_cancels_an_allocated_stream_in_every_revision#TEST", 3],
  ["src/test/java/com/soklet/internal/mcp/protocol/McpLegacyCatalogPaginationBudgetTests.java#jsonNodeBudgetIndependentlyPagesEveryLegacyStaticCatalog#TEST", 2],
  ["src/test/java/com/soklet/internal/mcp/protocol/McpLegacyCatalogPaginationBudgetTests.java#byteBudgetIndependentlyPagesEveryLegacyStaticCatalog#TEST", 2],
  ["src/test/java/com/soklet/internal/mcp/protocol/McpLegacyProgressPublicRuntimeTests.java#firstProgressPrecedesHandlerCompletionAndPreservesExactMonotonicUpdates#TEST", 2],
  ["src/test/java/com/soklet/internal/mcp/protocol/McpLegacyProgressPublicRuntimeTests.java#simulatorPublishesProgressBeforeTerminalAndDuplicatesTheExactTerminalMessage#TEST", 2],
  ["src/test/java/com/soklet/internal/mcp/protocol/McpLegacyProgressPublicRuntimeTests.java#committedDisconnectDetachesTheWriterAndRetainsUncanceledPhysicalWork#TEST", 2],
  ["src/test/java/com/soklet/internal/mcp/protocol/McpLegacyProgressPublicRuntimeTests.java#finiteAndUncommittedDisconnectsStillCancelTheHandler#TEST", 4],
  ["src/test/java/com/soklet/internal/mcp/protocol/McpLegacyProgressPublicRuntimeTests.java#detachedProgressWorkStillObservesItsDeadlineAndRetainsCapacityUntilPhysicalExit#TEST", 2],
  ["src/test/java/com/soklet/internal/mcp/protocol/McpLegacyProgressPublicRuntimeTests.java#simulatorDisconnectUsesTheSameCommittedVersusFiniteRule#TEST", 4],
  ["src/test/java/com/soklet/internal/mcp/protocol/McpLegacySessionCancellationReservationTests.java#canceled_body_that_never_starts_has_a_physical_deadline_and_preserves_its_token_reason#TEST", 2],
  ["src/test/java/com/soklet/StreamingOutputViewRuntimeTests.java#closingOneViewFlushesItAndLeavesOtherOutputUsable#TEST", 2],
  ["src/test/java/com/soklet/StreamingOutputViewRuntimeTests.java#failureAfterWritingFinalizationBytesCannotReportSuccess#TEST", 2],
  ["src/test/java/com/soklet/StreamingOutputViewRuntimeTests.java#invalidSlicesDoNotPoisonOutputAndEmptySlicesAreAccepted#TEST", 2],
  ["src/test/java/com/soklet/StreamingOutputViewRuntimeTests.java#mixedOutputKeepsOrderAndCopiesCallerBuffersBeforeReturning#TEST", 2],
  ["src/test/java/com/soklet/StreamingOutputViewRuntimeTests.java#ownedUtf8WriterFlushesAndEmitsBufferedTextDuringFinalization#TEST", 2],
  ["src/test/java/com/soklet/StreamingOutputViewRuntimeTests.java#ownedZipFinalizationEmitsAReadableCentralDirectory#TEST", 2],
  ["src/test/java/com/soklet/StreamingOutputViewRuntimeTests.java#viewAndNativeLifetimeChecksPreserveTheResponse#TEST", 2],
  ["src/test/java/com/soklet/StreamingResourceOwnershipRuntimeTests.java#cleanupOnlyFailureCannotProduceSuccessfulCompletion#TEST", 2],
  ["src/test/java/com/soklet/StreamingResourceOwnershipRuntimeTests.java#duplicateActiveOwnershipIsRejectedWithoutDoubleClose#TEST", 2],
  ["src/test/java/com/soklet/StreamingResourceOwnershipRuntimeTests.java#headSuppressesWriterFactoriesConsumersAndCallbacks#TEST", 2],
  ["src/test/java/com/soklet/StreamingResourceOwnershipRuntimeTests.java#nestedUsingClosesItsChildrenBeforeOuterAndRootLifetimesEnd#TEST", 2],
  ["src/test/java/com/soklet/StreamingResourceOwnershipRuntimeTests.java#ownerChecksAndClosedScopeChecksRunBeforeAcquisition#TEST", 2],
  ["src/test/java/com/soklet/StreamingResourceOwnershipRuntimeTests.java#producerFailureRemainsPrimaryWhenCleanupAlsoFails#TEST", 2],
  ["src/test/java/com/soklet/StreamingResourceOwnershipRuntimeTests.java#rootCleanupRunsInReverseOrderOnOwnerAndCanWriteAfterWriterReturns#TEST", 2],
  ["src/test/java/com/soklet/StreamingSourceFactoryRuntimeTests.java#checkedAcquisitionFailuresPreserveTheirCause#TEST", 4],
  ["src/test/java/com/soklet/StreamingSourceFactoryRuntimeTests.java#checkedSourcesAreLazyAndReopenedForEachExecution#TEST", 4],
  ["src/test/java/com/soklet/StreamingSourceFactoryRuntimeTests.java#headDoesNotInvokeCheckedFactory#TEST", 4],
  ["src/test/java/com/soklet/StreamingSourceFactoryRuntimeTests.java#nullSourceIsAProducerFailureInBothRuntimes#TEST", 4],
  ["src/test/java/com/soklet/internal/microhttp/StreamingOutputInterruptionTests.java#nativeByteBufferPositionLimitAndMarkSurvivePartialInterruption#TEST", 3],
  ["src/test/java/com/soklet/internal/microhttp/StreamingOutputInterruptionTests.java#anElectedTimeoutOrDisconnectWinsOverInterruptionTranslation#TEST", 2],
  ["src/test/java/com/soklet/internal/microhttp/StreamingOutputInterruptionTests.java#interruptedFlushAndCloseReportZeroAndFailedCloseIsStillIdempotent#TEST", 2],
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
  ...reviewedScopeFile("src/test/java/com/soklet/AdvancedTests.java", "812397213c6ec929f4b355e86cd0236a5646450b103417e1180bb3820d161b85", [
    ["testSSERaceConditionOnConcurrentConnectionsAndDisconnections","9e6701d8411c27975b0a07f25cfbf18deb89025fcd8627214789a9f2cdaaf560",{"controlJoinMillis":10500,"controlComposition":"REVIEWED_CONCURRENT_MAX"}],
    ["testConcurrentRequestProcessing","81e7d2134b606c5eb8b9c7743e908734d4e7fa06b5e996c53b37f1800a7c6750",{"generation":{"count":3,"mode":"MIXED_MAX_PLUS_SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":45000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","requiredAction":"RAISE_OUTER_BOUND"}],
    ["testSseServerClearsCachesOnStop","0900dfd859757aae61d2d17a81bd0fd7209cd03a0841bb911c2cdb4a5d7aa7fa",{"controlJoinMillis":4000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["testLargeRequestBodyMemoryHandling","a29c5cfe2b3af88e8f4dfaa05b9d78558e0908a9d58ed102c1d25b2fdc0a2fc0",{"generation":{"count":11,"mode":"SEQUENTIAL","complete":11,"prior":10,"incomplete":1},"controlJoinMillis":100,"requiredAction":"RAISE_OUTER_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/BuiltInTransportLifecycleAdapterTests.java", "15f29083d2dfdf367f98d9ef7fe36d0e68ca3c87a25be3754707b37c13371258", [
    ["admissionIsClosedUntilReadinessAndShutdownBeforeReadinessSealsIt","b0fbc6599143c7cf95856f40ca7a5bb2593d8ce8d79b371f3e13700f9f9b863f",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlledLifecycleCoreMillis":0}],
    ["positiveResidualAndUnknownBothRetainEvidenceWithoutRelease","eed0e5efd87ca55d8b6edd92b1e48f9eaa4bc07b044818317ba84539e7bdde0e",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlledLifecycleCoreMillis":0}],
    ["completedResultIsPublishedOnlyAfterCoordinatorRoleRelease","4a728c54f345a68a3ac1c2ead8e2a6def0076690bbb748cfe60e1d36446ec933",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlledLifecycleCoreMillis":0}],
    ["exactGenerationOperationsRejectForeignTokensWithoutMutation","ff1b277adb13768eb39a9e28b2a43a292e48b75af80cf0ca8439c542f6a8535c",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlledLifecycleCoreMillis":0}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/DefaultHttpServerTests.java", "f73850c424e890383f5e9912ee48e6aa83b1eb2fd816fabd202331f492578f3e", [
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
  // The owned and caller-owned channel cases each start and stop a fresh HTTP
  // server sequentially. Each exchange has a 2-second connection retry and a
  // 5-second socket-read bound, for 14 seconds of control work across both cases.
  ...reviewedScopeFile("src/test/java/com/soklet/IntegrationTests.java", "738a71ec0daa2e1412aedbebbe40efbeea23f3f180602776668ae72eb6d9d61f", [
    ["responseCompressionRawHeadPreservesFileChannelOwnershipAndHypotheticalLength","5953fa1cdcf3efa0779fb724a1493fb762e96a3f9fffec2caa1798cbfb86a7c6",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":14000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpCatalogAccessLocalizationPublicRuntimeTests.java", "05555b9d28ee66fa2e699c53dc4e4f65833429469941f82aed967a15ca06e8fc", [
    ["filteredTenantCatalogsLocalizeOnlySurvivingStableOwners","510b49dbf487de244ee0edd18699dfe85452b14d9d7a2f6597db10ac547263f6",{"controlJoinMillis":60000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpDeferredTaskOutputSafetyTests.java", "0d6073052efc16e8163b0231dabc1b195dad5d5f4450e290504dacabdbd33911", [
    ["nonCompletedTaskReadsDoNotInitializePolicyOrLocalization","261dbf824fae9ddd196afb00d00f4a2b10ecabe703e3fad8f882db27475e10e3",{"generation":{"count":4,"mode":"SEQUENTIAL","complete":4,"prior":3,"incomplete":1}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpLifecycleB3Tests.java", "853c9c9d7b7b6b2bfa50b364a8c009b2612064c7ae8f759d791866df505bac8b", [
    ["cancelledFreshOwnerCannotMutateAnotherPreparedOwner","2b13bb8eb2777752876b34ac17a10577bdee5db3c9c98a7571afec41b341d3f3",{"generation":{"count":2,"mode":"MIXED_MAX_PLUS_SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":5000,"controlComposition":"REVIEWED_CONCURRENT_MAX"}],
    ["executableEndpointPlansAreImmutableAndFreshPerServerFactory","99724b969af4a029c883848c69861ce8de38902c8f7129f9521afd7903115a43",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["exactMcpGenerationOperationsRejectForeignTokensWithoutMutation","67aedfa580c9dd8c6514285b0dd58076b13327801868c12f4c88cd974052d148",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlledLifecycleCoreMillis":0}],
    ["fixedPortBindIOExceptionPreservesExactCauseForFreshOwnerAfterRelease","6772bb93ffa9dfb1a0c4c130af2741cb611f389786039488b27b478619a1c873",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["forceResponsiveHandlerIsInterruptedOnlyAfterTheGraceDeadline","52f03eb3d94266f6598738c86e9d91c33506d47dc71342533950eaf36cd495b7",{"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":1000}}],
    ["mcpFailureAndProofOrderingPreservesTheExactGenerationAndBarrier","2270ca5ea943da3ac63de613459e0b91ac70b96a3da098adb8c30e624da40b5a",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlledLifecycleCoreMillis":2000,"controlJoinMillis":5000,"controlComposition":"REVIEWED_LIFECYCLE_CORE_DEDUPLICATION"}],
    ["noncooperativeHandlerClassifiesResidualAndRetainsItsGraphAndAddress","c090937c5cb6899b7251a3fce920523de2895ca2692b8f6b6893522c5656f9d4",{"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":1000}}],
    ["oneServerStartupDoesNotMakeAnotherServerStopFailFast","6ba34566aaa3c7ce0aa398e3b675ea5c1d5491b34b9cd33e9f5356cc89f0f815",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["oneShotOwnerCannotConsumeUnexpectedGenerationBeforeExactResultPublication","8ec7c5714e6b014e07678de524cef1b8a49784ca98bdd8f04dcb66d811c62c5b",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["ownerNormalizesRetainedUnexpectedGenerationOnceBeforeRejectingRestart","2afc232071d7c006ad16f324b8abae2c0b5f5c4875b7dd64a0c0e71921c93b07",{"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":1000}}],
    ["postBindPreReadySubscriptionFailurePreservesExactIdentityAndAddress","22377635b1ac4eae868a88512686e919614e30e3622b8d6400ff678ed6156170",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["readyEventLoopObserverReentrantShutdownReturnsPromptlyWithoutFalseResidual","4dcf3decbf5db2363f26ff2b40ee5a77c21d08d577fc3308e50fffd67cbad07d",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":10000,"controlComposition":"REVIEWED_LIFECYCLE_CORE_DEDUPLICATION"}],
    ["startupErrorPreservesIdentityAndFreshOwnerStartsAfterNeverBoundFailure","e0b981d64b24aa620c0f977c324e8c96b151e9a7728f52856b5b583d4f125559",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["stopBeforeRuntimeInstallAndBeforeMarkReadyWinsDeterministically","dfbc3019541b94c163cf454aa3de26c895da5e635e3895327691a3b9b5ec62e9",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlledLifecycleCoreMillis":18000,"controlJoinMillis":5000,"controlComposition":"REVIEWED_LIFECYCLE_CORE_DEDUPLICATION"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpLocalizationAdversarialTests.java", "cca048bbd0dcefb7eec9324fbcd96e41309cb34c96550b6fba99c42d18299a63", [
    ["rejectedAndIrrelevantWorkNeverInvokesTheProvider","314ac4b16ec574dc021d47c15bf20ba61dc3e5d9a7285af060451a08a6d20977",{"generation":{"count":5,"mode":"SEQUENTIAL","complete":5,"prior":4,"incomplete":1}}],
    ["simultaneousLocaleSelectionAndInvalidationStayIsolated","1f78dc197636be61b9fecb1249d3d9eeb42328c4c05332000e3a1cb789675a63",{"controlJoinMillis":10000,"controlComposition":"REVIEWED_OVERLAP_OR_DUPLICATE"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpLocalizationFleetPublicRuntimeTests.java", "913ab645cc78806ea13ec1abd3797d9244f1301adccc8fcfbacfd64c4ef37f1d", [
    ["failedFleetReloadPreservesBothOldSnapshotsAndPublishesNoInvalidation","e7c2ac03e01a3e70a99c29e5ca27b2e8fd38bac9ed21b6ae0ad9bc53b2e04159",{"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":1000},"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["nodeLossAndSubscriptionReconnectNeedNoSessionRecoveryAndReleaseFleetResources","e8fba4c540591695dbb98959c5c77d164f59b4bef0bb4eac02065223f182eced",{"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":1000},"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["rollingActivationAllowsRevisionDriftBetweenNodesButNeverWithinAResponse","89a417a4529d01d5cfd39ea92177a282f28191e25864cbdaa1580082bb327c35",{"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":1000},"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpLocalizationHttpBoundaryTests.java", "b9089787c21a0e02c0632b0ddc7ffc59851c177b88ec05f8cb5538a7a2977806", [
    ["cacheableResultsAreClampedToPrivateZeroExactlyWhenLocalized","e4444aeafd9c2ce6a0c72772d03f355cb47b56de72616ba69f38f485c87f777f",{"generation":{"count":5,"mode":"SEQUENTIAL","complete":5,"prior":4,"incomplete":1},"controlJoinMillis":50000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpLocalizationReloadRuntimeTests.java", "cc3cc81cab31f80a647ba38dde4877ec39434771acdd0e9d9e1af7d3808dad9a", [
    ["invalidateCatalogsDeliversOneCoarseInvalidationPerLocalizedFamily","e2728e01df6c447112bddaca168c47c7792262f2a2092bc6a70422169e7e783a",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":20000}],
    ["localizedPromptPublisherNeedsNoUnrelatedApplicationPublisher","c1a8cf444a977b9c8d1b7822a5efbc5ad2dcf9e409459af9537087672812a835",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":10150,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["discoveryAdvertisesListChangedOnlyForLocalizedCatalogsWithSubscriptions","101808b6fd4a80d2741cb461aae5393d9bbb3cbdad11f6fa7e392344aca90762",{"generation":{"count":3,"mode":"SEQUENTIAL","complete":3,"prior":2,"incomplete":1}}],
    ["aStaleLocalizedTerminalIsReleasedByInvalidation","28e22b5e70ad71c7ab1550d7cc2e19f5cdb42049fee73ee61b41203a7c951e71",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":15000}],
    ["invalidationDuringTerminalPreRenderCannotInstallTheOldSnapshot","996c9b411ddf26cdd40f7c30fb289834e68d3949378325645c18a2175d35964e",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":20000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["shutdownDuringTerminalPreRenderCannotCommitARejectedSubscription","e84f16d5d275ad30d9310da44147e6d986fb2a3c0ae2bc9e4124bcbf823f65f6",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":45000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["twoNodesInvalidateIndependently","da50c24ee984e3c8a13c75fc12666f92474c20f508e7aafe433fb4f818b78479",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":20150,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["mixedLocalizedEndpointsShareOnePublisherAndFanOutToEveryEndpoint","1ce475a6cb13a7640def901c6aee243947ca37fc9e2d3dc9fb9bf778f38be705",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":30000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpLocalizationRenderingRuntimeTests.java", "b2392481eac2874687c2714fbf54ea0f942c870605066f899000587fde6ea454", [
    ["everyNonDiscoveryCatalogRendersItsPlannedSlotsLocalized","e7b65f569523f0e0de6491224189a6b2f336ee772bfc4499890cf8fdab34e337",{"generation":{"count":4,"mode":"SEQUENTIAL","complete":4,"prior":3,"incomplete":1}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpMetricsEventDeliveryPublicRuntimeTests.java", "58574faf8e196d18a93425c340ce748972e1c3a7d908fa5872d5f88998098bd6", [
    ["adapterStopRequestQueuesStoppedBeforeFreshOwnerStart","46e1dd7468b75bd78ed002513bcdb3b19070cce5ecd7785be71d18f3de07b383",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":20000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":1000}}],
    ["failedListenerStartEmitsStoppedWithoutStagedStarted","9f7e3b316a77c3c94758a5cd9c8a3eadfaacf700b7c5e22fccb2ecf43dea7f1f",{"generation":{"count":3,"mode":"SEQUENTIAL","complete":3,"prior":2,"incomplete":1},"controlJoinMillis":15000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":1000}}],
    ["freshOwnersEmitExactStartedStoppedGenerationsAndShutdownNoOps","8a1c6d8cd86167e923a01d2db875cb1fda367cedc08dd7821889bc89ba3f3319",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":25000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":1000}}],
    ["unexpectedTerminationOrdersNormalizedStopBeforeFreshOwnerStart","90cb70ed200f06d85e999d10083dc95a7bc34236ae7d0a23f9e76db79c1a6938",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpMirroredHeaderPublicRuntimeTests.java", "8d4ec225467792fee1edd0f83567404a86cfb2682b6f915910b48657847329c0", [
    ["diagnosticQuotaIsSharedAcrossEndpointsAndIsolatedAcrossOwners","d2d7f3dd310037960d9b05c00304b1d217ef80b4d2d21582ebfbcfcbca2ef164",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpNotificationPublicRuntimeTests.java", "c11f8ebd54955be45ccc1360d0d64943769234465587b47286a0789c94dbb4f5", [
    ["inboundNotificationsNeverEmitJsonRpcBodiesOrReachApplicationHandlers","ac73360a54c74740e0b6a5a5d091b69aa40f05dd3cceba38f73cada5f34aca5f",{"controlJoinMillis":50000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["outboundFrameworkNotificationsOmitIdsWhileTerminalResponsePreservesRequestId","004911fbfa7770b0ae4c25a3a5e13bbee380835e49f15e130794dacec3ac1f73",{"controlJoinMillis":35000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpProtectionTraceDiagnosticsPublicRuntimeTests.java", "5a17e41effbe20d6df59e5d3632222b9a971e4e5eb7f46fe67c205d3bc2362d3", [
    ["liveRotationsChangeOnlyFreshSnapshotsAcrossStopAndRestart","aff4a2ec05c343fd842b869a28e5604fe84a68ca15a30943291e5e446c22392e",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpRateLimitIdentityPublicRuntimeTests.java", "a03b26462a04da772ec81fc0f332c8b16067e8b38288ac486caa077d512a6ab4", [
    ["allowlistedSocketPeerCanSelectForwardedIpPartitions","a204fc9c5ca2beb351f9ab37986c3d89aed0c82e8c05add8d0903a35622b7856",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpRateLimitPipelinePublicRuntimeTests.java", "0914c859c5f3e4f82e3f1f016d6631e0639903d5f8c87ff9c686079e79207716", [
    ["successfulChargesAreRetainedAfterEveryDownstreamFailure","6021dbe8040107292a2b90efe2a60899cf07dc1c61690ee44de7c463fb998cf0",{"generation":{"count":6,"mode":"SEQUENTIAL","complete":6,"prior":5,"incomplete":1},"controlJoinMillis":30000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpRequestObservationPublicRuntimeTests.java", "922ef79f048086d9f0dd11db275e67e29571338a92d6b037002fd5934772c142", [
    ["defaultOffAndIndependentRawIdOptInHaveExactLogContracts","e336c7c0730cdeaa0c0e2cc841be7759b32f7eb0756b0fd38f398def4c0c1342",{"generation":{"count":3,"mode":"SEQUENTIAL","complete":3,"prior":2,"incomplete":1}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpRequestStatePublicRuntimeTests.java", "8cab0ed5e543acab82d90f61292d18f2f1c138858f70456e3aa2a46f08ebc40c", [
    ["frameworkProtectedStateContinuesAcrossInstancesOnlyWithinItsKeyAndAuthorizationPartition","1b0720ed899f8d36f3a5b7722a9331943f60519f80991b80e3c4a922af67a4b9",{"generation":{"count":4,"mode":"SEQUENTIAL","complete":4,"prior":3,"incomplete":1}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpServerPublicRuntimeTests.java", "28d4bb706ccfb05587e38cb6a58685466a0a058255b7b591ded20b46348d9286", [
    ["executionConfigurationValidatesAndOwnsOneExecutorPerGeneration","a4fd9b3313bb5ff0e662202ade77a6e536fb487b9430fbc772144c06d46c6fda",{"generation":{"count":3,"mode":"SEQUENTIAL","complete":3,"prior":2,"incomplete":1}}],
    ["explicitRejectAllCorsSuppressesTheOmittedConfigurationDiagnostic","d12aa4815ef29d64a7f3e33ea73207dece772245ed90ea858a1e09976da38281",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["failedFixedPortBindLeavesResourceAvailableToFreshOwnerAfterRelease","28f43e35f69d502ac0dd63d839344e4fb742a5634f0e5acc7242b2b9f3db4f66",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["freshGenerationPublishesNeverBoundAddressBeforeStartupCallbacks","819d42facf4cf68d458fde6eea570dbbbda4809af81b84028feeba66a1450d51",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["freshOwnerStartsAfterUnexpectedMcpListenerTermination","ef1582dbf323cadef1164078316f2e44960e7818bdb70c4c81f00c7082108847",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["omittedCorsDiagnosticIsExactAndOncePerSuccessfulSokletGeneration","9e5320a6926c863a2c504795bd37d7b7194c16a65ebec478eca5b031e732ec68",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["sokletOwnedPortZeroGenerationsPublishImmutableDiagnosticSnapshots","1b326aad87bfce59118ba53f4899ddda2c7f5679726f108ec7a5c4cd1781a4e7",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpShutdownObservabilityTests.java", "aeb56738880c2edd7a659e63d7e2cdd2d33b34c97d29014c1f0bfb1d5a6ee977", [
    ["rejectedUnexpectedRestartDoesNotDuplicateBeforeFreshOwner","f4e7d5392de0df5da2dd468969ae18d43b2188c81d87fa21afabdebd1d4ef9ef",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["unexpectedListenerTerminationAndFreshOwnerHaveExactParity","658fb897e3bcf0c822984d9d201f5c79c95dee91bc0957419d653a984b2c9b14",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpSimulatorEveryOperationTests.java", "0258bf715563cff9e34848950ebf35875b5fd4413f60e3ee5567e7a1f66239e5", [
    ["recognizedRequestMethodsReplayExactJsonOrSseShapes","175949c5dfa1e8cea7c9552a111e065b76b062f423a15ccf3ab4e1a4e891c3b3",{"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":1000},"dynamicNodeCount":9,"controlJoinMillis":20000,"controlComposition":"REVIEWED_DYNAMIC_NODE_MAX"}],
    ["cancellationNotificationIsAcceptedAndIgnoredWithoutTerminatingItsTargetSimulation","1425099cbbaa301988284aa3c867763a17ffdf8b0bebeb09f2b63b56df98d94c",{"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":1000},"controlJoinMillis":40000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["concurrentRecognizedOperationReplayIsIsolatedAndExactlyDrained","6919769d8b299d97fc8f58cf32f284e614fad839139e6bf74d5e3f77dce2649d",{"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":1000},"controlJoinMillis":25000,"controlComposition":"REVIEWED_OVERLAP_OR_DUPLICATE"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpSimulatorPublicRuntimeTests.java", "0e2be1c351c0bb667d0a419fe9c3f3d921215361e9c01b696499355d58c6ac6a", [
    ["concurrentSimulationsRemainRequestIsolatedAndDrainExactlyOnce","35c0b468159f9ad950f4ddbaf882ba4dc80168e6f04b6696052ec9fbb07e3264",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":0,"forcedShutdownMillis":250}}],
    ["defaultLoopbackHostPolicyRequiresLiteralConfiguredPortZero","69dd68a3cac05f010344ba16a9ece088d2fc3018340863599332ede97dd88b91",{"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":0,"forcedShutdownMillis":250}}],
    ["malformedAndRejectedSimulationsPreserveProtocolPrecedenceWithoutAdmission","37d7e0dab25903b9a6a2c45b3f40f18f600c771efc4fc10a38b4da523590a3ce",{"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":0,"forcedShutdownMillis":250}}],
    ["mcpSimulationBuffersStreamItemsAndClosesExplicitly","e3bfe064a1680f076a0654326636740024f7e2165f5891c55ab18e1963b2f7bb",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":0,"forcedShutdownMillis":250},"controlJoinMillis":25000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["mcpSimulationCompletionRetainsStreamCaptureFailures","854ce2e7788159ff1c63b8b30363bb100ef73626347bb4b871cab191ff6c405a",{"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":0,"forcedShutdownMillis":250},"controlJoinMillis":25000,"controlComposition":"REVIEWED_OVERLAP_OR_DUPLICATE"}],
    ["multiRoundTripSimulationContinuesInputRequiredStateToDistinctCompletedRequest","7f145424f68e36095649ca7e4d6431b5db1512a6f59d9c2355a2f11f0b8aa0e1",{"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":0,"forcedShutdownMillis":250},"controlJoinMillis":25000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["noncooperativeSimulationCleanupIsBoundedAndPreservesSuppression","527e1495a7b27842684e2d0ee1ab4a5100de3dadc4ba9f807e78fd52d4fedac9",{"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":0,"forcedShutdownMillis":250}}],
    ["nonDrainingCaptureLimitDoesNotBlockUnrelatedSimulationOrCreateTransportFailure","cb7489745af384abfe74181d68ec47d64a44aa48ebb2e5dfa7a7435db86fa702",{"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":0,"forcedShutdownMillis":250}}],
    ["simulatorRepresentsEventStreamAsOpenMcpSimulation","db071f4429cc5b5847d612962df6c53923a32b0e860d661eed834f2bb0aef933",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":0,"forcedShutdownMillis":250}}],
    ["simulatorScopeExitCancelsOutstandingRequestsAndRestoresOffNetworkState","df308e9e23caa18ee4d3009c1aebcdf99def44de7c53ad62287287dab4a1edb3",{"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":0,"forcedShutdownMillis":250}}],
    ["simulatorStartsRequestAgainstConfiguredMcpServer","2c0d86afd7ceb1c42c9efae81fe0e636bea552de9e90d8bb39ba40894f411efa",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":0,"forcedShutdownMillis":250}}],
    ["subscriptionReplayPreservesAcknowledgmentEventAndCancelationOrder","1ac72a539ae05ae65674d5f91b780f23aa648470a2da39d984b4d3cb9dfda7e2",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":0,"forcedShutdownMillis":250},"controlJoinMillis":45000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["synchronousJsonSimulationUsesRealProtocolLifecycleMetricsAndBodyType","08626d3584884fca91e40c574543343f3c6023a14a6f1b224ae64fd335b158f9",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":0,"forcedShutdownMillis":250}}],
    ["waitOperationsHandleZeroTimeoutInterruptionAndCompletionIdempotently","0905a22496828c1974cd6cec90f5e658c4bddf3ee7aa4f34858e115155f7551e",{"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":0,"forcedShutdownMillis":250},"controlJoinMillis":20000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/ResourceLeakTests.java", "8212cc4ca351e6ffa18d9a3a422aa1092ded050984af28d2c66a34f530b934b2", [
    ["httpConnectionChurnReturnsResourcesNearBaselineAfterShutdown","e8fd5790bb67b858380a9d9f9bd04d52f86cf7f2b48a9b4427b4b79fed74d04d",{"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":2000}}],
    ["mcpListenerAndRequestReturnResourcesAfterCompleteShutdown","ed77612eb1a906e30688944d64700be6c3a9d733f04778adca471c646c0307e9",{"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":2000}}],
    ["sseConnectionReturnsResourcesNearBaselineAfterShutdown","d419a07618bf5cb500274d57e20fa7cad3351f61e0321aa6c5a5e7e902c68954",{"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":2000}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SokletApplicationTests.java", "da191b72115528c27111477cd5f5cd5e3ca1688e7c6eda3e5f98e81addeb83eb", [
    ["malformedRunArgumentsDoNotConsumeTheApplicationRunClaim","1b91dc08e9965a3269b5bbcd2ce58bbbb320debf6b6f035e6fd64ea675765900",{"generation":{"count":6,"mode":"PRECOMMIT_REJECTIONS_ONLY","complete":0,"prior":0,"incomplete":0},"applicationCleanupCount":0,"incompleteBranchCleanupCount":0,"terminalReportCount":0}],
    ["failedAttemptStillConsumesTheApplicationRunClaim","5a506239dff5707e38c3b4d6a265b5d2dc4a028e8739029a7e4c21256fdafb35",{"generation":{"count":2,"mode":"PRECOMMIT_REJECTIONS_ONLY","complete":0,"prior":0,"incomplete":0},"terminalReportCount":0}],
    ["invalidInjectedEnvironmentStillConsumesTheApplicationRunClaim","f4979d57d8ab6a121e53c6bb823c0fd5b9adf843818df97436a0c330d60936db",{"generation":{"count":2,"mode":"PRECOMMIT_REJECTIONS_ONLY","complete":0,"prior":0,"incomplete":0},"terminalReportCount":0}],
    ["concurrentRunIsRejectedWhileTheFirstClaimIsActive","31f6dc9407b3c059ad5ff3078cab98c14beea0d8df7ba983261f9b6c3b4acee6",{"generation":{"count":2,"mode":"PRECOMMIT_REJECTIONS_ONLY","complete":0,"prior":0,"incomplete":0},"terminalReportCount":0}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SokletApplicationDiagnosticsIntegrationTests.java", "21dabbd28149a6606bcf3f9ba96004b867bcbdaa7d5618664ec18eef0091fb98", [
    ["blockedFrameworkSetupSynthesizesFrameworkDiagnosticsAndSkipsCleanup","af6dfe671f5cdbee55a96e15ef1c30a7d0abaccfa01a35e626b17eb5ce3b96f1",{"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":0,"gracefulShutdownMillis":0,"forcedShutdownMillis":0},"controlledLifecycleCoreMillis":5000,"applicationCleanupMillis":1000,"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":40000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
    ["blockedNestedCustomHttpAttachProjectsBoundedTransportDiagnostics","249f2f85e0eb41339908681e4fae0cc21df3c119ea4fa18a9db1d8e1b8cd8f17",{"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":0,"gracefulShutdownMillis":0,"forcedShutdownMillis":0},"controlledLifecycleCoreMillis":5000,"applicationCleanupMillis":1000,"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":40000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SokletApplicationObservationTests.java", "d4b53de085208074bf3f60d65cbfa624ec11d012c7e8dd2bfc3b2855a42bcba4", [
    ["mixedIncompleteAndNotStartedTerminalTraceIsOrderedAndComplete","ecc217638a2fae905003d9ba3daf55f678c08058eb8936f0b3fe29ee2aee6eeb",{"phasePolicy":{"forcedShutdownMillis":0,"gracefulShutdownMillis":0,"startupCancellationMillis":0,"startupMillis":5000},"controlledLifecycleCoreMillis":5000,"controlJoinMillis":20000,"controlComposition":"REVIEWED_OVERLAP_OR_DUPLICATE"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SokletDirectLateStartupIntegrationTests.java", "9e0c8efa5bc053cf760be123c35f280f0cf4b679ce29942604f8a24419d5cce1", [
    ["attachmentLosingShutdownFreezeReturnsBeforeTerminalAsExactNotStarted","5577476a9233d1140854c6fe549abab098f7df0b2de11a4c7eda148957bf7731",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":0,"gracefulShutdownMillis":150,"forcedShutdownMillis":2000},"controlJoinMillis":35000}],
    ["pendingAttachProofCannotCompleteCallStillLiveAtTerminalFreeze","908d202d1782585e1ff626ada31a679e10806b73b7ad74344d214b709a212b6b",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":0,"gracefulShutdownMillis":150,"forcedShutdownMillis":2000},"controlJoinMillis":45000}],
    ["installedAttachmentGracefullyReleasedBeforeStartIsNotStarted","e7197792c9caa89933b06a58e4498cc1c69c566d23ed289dc16fd9e2ddc7ceb0",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":0,"gracefulShutdownMillis":150,"forcedShutdownMillis":2000},"controlJoinMillis":35000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
    ["installedBuiltInDelegateGracefullyTerminatesBeforeStart","df1e389a7feb7f574c98da43d0f3ad79afe715b297b4c8298f842544cc575dce",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":0,"gracefulShutdownMillis":150,"forcedShutdownMillis":2000},"controlJoinMillis":35000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
    ["installedAttachmentProvenOnlyAfterForceIsForced","538e5bee1a8e4601c4a9a5102afad130fb4d13d04140ad67e220b68d0c1ac07c",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":0,"gracefulShutdownMillis":150,"forcedShutdownMillis":2000},"controlJoinMillis":40000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
    ["installedAttachmentMissingProofIsExactUnknown","9ea885da62d43c06c1feabe496a983f6b484209ed0f73aa976f7af4621dda809",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":0,"gracefulShutdownMillis":150,"forcedShutdownMillis":2000},"controlJoinMillis":40000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
    ["pendingAttachEventsCannotOverrideThrowOrNullPrecedence","504eb58d505d5d0de6bc6a8ff6a3e59c9c695d1df77833dd61643a12375ae15a",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":0,"gracefulShutdownMillis":150,"forcedShutdownMillis":2000},"generation":{"count":4,"mode":"SEQUENTIAL","complete":4,"prior":3,"incomplete":1},"controlJoinMillis":60000}],
    ["pendingAttachProofAndFailureBecomePreReadyEventsOnlyAfterCommit","39f1cb69b4ab1ff19aec679261f27bf208e770a14bbf153acd1439d9336c3898",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":0,"gracefulShutdownMillis":150,"forcedShutdownMillis":2000},"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":60000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
    ["lateStartReturnDuringGraceCatchesUpAfterIndependentIngressQuiesce","c80e671d08c29469af51bd46e6a39ce9af5eab694c157744a407b6df797a28df",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":0,"gracefulShutdownMillis":20000,"forcedShutdownMillis":2000},"controlJoinMillis":55000}],
    ["lateStartReturnAfterGraceReceivesForceAsItsFirstUnderlyingPhase","5573c1caed665065ce5af010759bf60714977ab7d997e4cd41c65ef81e2e4238",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":0,"gracefulShutdownMillis":150,"forcedShutdownMillis":2000},"controlJoinMillis":55000}],
    ["startReturnAfterTerminalFreezeIsForcedWithoutRewritingUnknown","844d6fa9e65fb2d67b81dae22e532b1fb0adc7ec6ae7bc7a753a4b28cebae2b0",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":0,"gracefulShutdownMillis":150,"forcedShutdownMillis":2000},"controlJoinMillis":55000}],
    ["shutdownBeforeClaimedStartWorkerEntryDeliversOneDeferredPhase","0c158c6a9d7117edacbcb7a4f13596de4f0b92fe5153bf5330effd5549062627",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":0,"gracefulShutdownMillis":20000,"forcedShutdownMillis":2000},"controlJoinMillis":40000}],
    ["rejectedStartWorkerLaunchClearsClaimAndRollsBackNotStarted","4e23453d94108875dde00a5df999d36b5966052ea7ab246ea36fd0de0aa0d6c8",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":0,"gracefulShutdownMillis":150,"forcedShutdownMillis":2000},"controlJoinMillis":15000}],
    ["catchUpFailureIsSecondaryEvidenceToExactLateStartFailure","09006ab667d5cde9cb97b11d2a9afe63946696292ac96dbfbf33af4c1ce9ee69",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":0,"gracefulShutdownMillis":20000,"forcedShutdownMillis":2000},"controlJoinMillis":45000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SokletDirectSseCompositionTests.java", "0e2a3d2ecf05cdf08a397adc9b99fee2448263ebf864bce9280dab4e4223afe1", [
    ["lifecycleOwningDecoratorProofCannotBeBypassedByItsDelegate","44b61c5dedaf5003d31e3210e13a0a3d0e8c6b2c11a4b4a8e5831ed8db6a05ab",{"phasePolicy":{"startupMillis":2000,"startupCancellationMillis":100,"gracefulShutdownMillis":100,"forcedShutdownMillis":100}}],
    ["transparentDecoratorSharesTheRootMemberAndRoutesTheSseSurface","7ee5fc63b29bf360446d9cca3f01ee051e340713be3eb9a86102f7c94ab4ee8d",{"phasePolicy":{"startupMillis":2000,"startupCancellationMillis":100,"gracefulShutdownMillis":100,"forcedShutdownMillis":100}}],
    ["twoLevelOwningStackRequiresEveryNestedMemberButRemainsOneParticipant","12bf89e23f0ed63426f0d279f825aca12d6a72b072aa3595aac781bedad8223f",{"phasePolicy":{"startupMillis":2000,"startupCancellationMillis":100,"gracefulShutdownMillis":100,"forcedShutdownMillis":100}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SokletDirectHttpCompositionTests.java", "b1e226c98b5868f066a9f851145a931864e1abb3d85509cedbae93164c719100", [
    ["transparentDecoratorSharesRootSignalRuntimeAndRequestPath","31650cc34a826d48d01a5ecaff17ea9772ae782d9a5e52c722afbfd65c0a8076",{"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":15000,"forcedShutdownMillis":3000}}],
    ["lifecycleOwningDecoratorRequiresDelegateAndOuterProof","ccede64a5c8aff78211793f6feef733cdc2821ff84f9b05ddc739d6c5d03f209",{"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":15000,"forcedShutdownMillis":3000},"controlJoinMillis":10000}],
    ["twoLevelOwningDecoratorsRemainOneConfiguredParticipant","baa997e98c6e21a907ceee6a4f5d915b99ad45d39f1cf865169a7086cb1db246",{"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":15000,"forcedShutdownMillis":3000},"controlJoinMillis":18000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SokletDirectTerminalPublicationTests.java", "b24ad641b84b61b0ba009ab12e45a7e5fdec3df39299b2ff0b913a66e745351f", [
    ["blockedPreRegisteredContinuationCannotStrandPrivateOrPeerOwners","07868a752d00075bf0cf1e994a19e2540402b3ffc87dc6050ee8a5bb1d48de19",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SokletDirectTerminationPrecedenceTests.java", "fa53b1ea0174a65ed9900203fe2f0f17f05f1dbc8518ca513201c1992c9e58e9", [
    ["ownerShutdownIntentWinsFormerGroupFanoutGap","da9453ddd92e3d10cfe8cb15476bf271efd399c14e41292a2edf1ac54b142ef9",{"phasePolicy":{"forcedShutdownMillis":30000,"gracefulShutdownMillis":30000,"startupCancellationMillis":30000,"startupMillis":5000},"controlledLifecycleCoreMillis":5000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SokletSimulatorIsolationTests.java", "e642e15cfe950e209fab840aeead6520b4dae72fa7be5e3017aa352dd2332851", [
    ["blockedFrameworkSetupUsesOneExactStartupAndRollbackSchedule","3e3b5ad4ea82946356b53bacd01456586c1061d238075018d12ce690f1e41dc1",{"phasePolicy":{"startupMillis":2000,"startupCancellationMillis":1000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":2000}}],
    ["concurrentFreshScopesDoNotCrossDeliverCallbacks","3ed0428c59239dff98ab82c223aa9e17fe9f6552b79d08e56810b98f98f5a1b9",{"generation":{"count":2,"mode":"CONCURRENT_OR_ALTERNATIVE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":15000,"controlComposition":"REVIEWED_OVERLAP_OR_DUPLICATE"}],
    ["customParameterAndInstanceProvidersAreFreshPerConfiguration","f10665e6611be5b47db4c751ecb9cdebeb9a2001355f48a6088f708f61c9070c",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["defaultParameterProviderBindsEachFreshConfiguration","ca8d2ec83a0012a1c1474f94d15b80bad7df3cec670935e5f0de423d6e8faabe",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["prebuiltConfigurationsUseFreshGraphsAndExposeTheirTransports","1c51cbb7527e53f1f654d7e420971d2fc9d7b83ecdce307222ba7f26ca1dfbdf",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["incompleteTeardownPreservesBodyFailurePrecedenceAndFailsSuccess","ce78d713e2aef1a0af4157ae824c7b8c70d43e966f02956f1bc72e4c7be545d2",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["liveMcpStartQuiescesBeforeCancellationAndCatchesUpToForce","37b14f522e8065b3983c1b55e79f9930e5b4756ed89c67534386cb02e9ee3896",{"phasePolicy":{"startupMillis":2000,"startupCancellationMillis":1000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":2000}}],
    ["mcpParticipantStartsBeforeReadinessAndUsesLifecycleClockBudget","37ed5ebf6117fe3bdbfb7129d089e4f00e720949fa248eeb3c93b8b7e2be6ee5",{"phasePolicy":{"startupMillis":2000,"startupCancellationMillis":1000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":2000}}],
    ["rejectsMultipleMcpBuildsAndEscapedBuilder","fd89f107b8947d85a101b610d6cafedd09f434676b4d2f6bdda68c033e61a49f",{"generation":{"count":2,"mode":"ONE_FULL_PLUS_PREINIT_REJECTIONS","complete":1,"prior":0,"incomplete":1}}],
    ["sealedScopeRetainsRejectedMcpSessionUntilRollbackTerminates","3dc3a7f0902d57cbf66ce8dc194c01188e46651a996214f7b34ba3c925b01b44",{"controlledLifecycleCoreMillis":5000,"controlJoinMillis":30000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["teardownLaunchFailureNeverReplacesPrimaryAndRetainsProofGraph","8629ccb85b1e9811aae2e75b1b11f0edd0136688c45988ab38d498e14d564ed0",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SseTests.java", "3ffc6f689adc1cd9a6ec3de32ae5736ea6566a5c43ba0ae471201e1247a4554c", [
    ["staleSseAcceptLoopFailureDoesNotClobberRestartedServer","8fa72e23163470ae54388eda1298ea902441e49999401846f23fec8472dbbf65",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlledLifecycleCoreMillis":18000}],
    ["sse_startStop_doesNotHang","5cbc10d780680e3632c03d3c06e704572efa3f5b047b96b7b7f77079d67492cd",{"phasePolicy":{"startupMillis":3000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":0}}],
    ["sse_stop_allowsIsStartedDuringShutdownWait","176eb363b3c17feade38f0fc28d1bf5a68f45afe747e88e4566660c923bf5920",{"phasePolicy":{"startupMillis":3000,"startupCancellationMillis":1000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":0}}],
    ["sse_stopGracefullyShutsDownRequestHandlerExecutorBeforeInterrupting","d2ac819b64043436ab3667f9b04695649be071cc6e74b7fa264f4aceb12562ec",{"phasePolicy":{"startupMillis":3000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":0}}],
    ["sseServerCanRestartOnSamePort","f9bfbe60f17f243ea82e7d0f68abca6378dd6bcc3e10f4671bea0aa9eceeff9e",{"phasePolicy":{"startupMillis":3000,"startupCancellationMillis":1000,"gracefulShutdownMillis":5000,"forcedShutdownMillis":0},"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["startRejectsRunningSseGenerationWhileItsStopIsInProgress","9303908c0e9f97fd45adc11e233d34ed035fef47e18574fd752838d11cafbbd7",{"phasePolicy":{"startupMillis":3000,"startupCancellationMillis":1000,"gracefulShutdownMillis":5000,"forcedShutdownMillis":0}}],
    ["socketOptionFailureWithProvenPeerEofClosesWithoutInternalDiagnostics","3885055de5dd5b26c8ca9ae98fbafeaacb3b264bf9c08a194c62aa770c1eaeff",{"controlledLifecycleCoreMillis":18000}],
    ["socketOptionFailureWithProvenPeerReadFailureClosesWithoutInternalDiagnostics","2ac41d32f057d491cd066b691c7c42546c84a94ee39cb0d54343f41641dff032",{"controlledLifecycleCoreMillis":18000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpHttpServerApplicationExecutionTests.java", "83b57f7cfc4cbddd08fe335145c0275cdbba92f804ab989311b786408cfc2c63", [
    ["application_executor_factory_failure_restores_a_restartable_runtime","12c0b6987c528bf3716755625c2904f548e2a7d6a0ac7ab133134e0a4d19ba2b",{"generation":{"count":2,"mode":"MIXED_MAX_PLUS_SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":5000}],
    ["lifecycle_grace_preserves_active_handler_then_force_interrupts_without_promoting_queued_work","b2298faf5919d0ddfb071e8a493c18b3b8d8fafa721f0a1188fba621d341ff90",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["shutdown_reports_residual_application_work_and_blocks_restart_until_exit","0168dce25a9f381c15c6b919637388cf236960743a70f23ea37fe954c058c8af",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpHttpServerCustomHeaderTests.java", "9cdb44edbe472b20ddeb5f80f9a8beffc85bddf541b3f8df322eb51e99b8b863", [
    ["custom_mirror_registration_is_scoped_to_the_selected_tool","4c0e49b4f43570fbadcc0fc86cdada8810c1ca2d41d8aef06a66a58783754e55",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpHttpServerNotificationTests.java", "977cd7830f87e800527441e2f3285df66a238c9733bbbdd283054ce7c2fc7b74", [
    ["classified_notification_cors_matrix_preserves_headers_and_rejects_origins_early","b7e6775539d89ca7446603040c8aa4d20425cc264363f0f500d5bee301feb6ad",{"generation":{"count":6,"mode":"SEQUENTIAL","complete":6,"prior":5,"incomplete":1}}],
    ["notification_admission_allows_invalid_params_and_fails_closed_on_unsafe_headers","e22b5be4e146a3fee9c2bef67e06c54f3b8f4406d03e8fa84f88f357441fa6dd",{"generation":{"count":4,"mode":"SEQUENTIAL","complete":4,"prior":3,"incomplete":1}}],
    ["notification_policy_failures_fail_closed_without_a_json_rpc_body","4e66a46c96247f426624b012efbd84e2efbcc98f4209cdf864beefb7304036f6",{"generation":{"count":4,"mode":"SEQUENTIAL","complete":4,"prior":3,"incomplete":1}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpHttpServerPolicyPipelineTests.java", "de9d1b91795719a4a49d5f77cdfc5ee6a3e54f5317e67075399909b6f6df22de", [
    ["policy_null_exception_reserved_code_and_unsafe_header_fail_closed","998e3a54b55f4ff58e1b75ac8d31c00e649a2d93dc73abaddc6e31ae4cad7893",{"dynamicNodeCount":12}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpHttpServerRequestScopedSseTests.java", "ee9b77c5b93c44597eca3fc49059bdbc549c44f0ea9ee93de51af0ff52645c69", [
    ["shutdown_closes_committed_stream_and_runtime_restarts_cleanly","1b56c480e7618c105d448e1c5f45e17d4967bac50ef9f70443d8ecdf442784b4",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpCatalogPolicyDeadlineRuntimeTests.java", "e6f215073cf7d7e7ca87cab101fdc9f821e00ab948600a960ed991430615611d", [
    ["applicationStopCancelsActivePolicyBeforeAnotherEvaluatorCanEnter","691edc69658c41476fc1e809c6ec5eb9eecd80607b80c4b491be8bb6573a7b12",{"controlJoinMillis":30000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpHttpServerRuntimeTests.java", "d3a8851fe9cefb26914565a1454e2e6745929ad36ca9e8857aedde74c831ca64", [
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
  ...reviewedScopeFile("src/test/java/com/soklet/StreamingResponseTests.java", "3823af7671584d8fe259a71807e8a3de244320cc63710effbaa671c00af87d72", [
    ["simulator_admitted_stream_error_callbacks_survive_scope_seal","b90cf962e5913cc291faa943fd6f8e4e1c06cadc33d628b641cb4b9c5bfddc4a",{"controlJoinMillis":10000,"controlComposition":"REVIEWED_OVERLAP_OR_DUPLICATE"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpFinalTagGoldenWireProductionTests.java", "0850f0d00bc25efd97697b4c22d25b0ec097d19517dfc361e2cdf6eb40918869", [
    ["checked_in_phase_5_subscription_messages_match_the_production_listener","752fbe577f0c4b94dfc9de377bf0427272c936d83dc2d8dae2acbd8c14c42ab6",{"controlJoinMillis":0,"controlComposition":"REVIEWED_OVERLAP_OR_DUPLICATE"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpMultiRoundTripTerminationRaceTests.java", "b2ffd548f7d0056535f64de26d09e33a5e25cfdaed7494ab65b9b2d2f4483bb3", [
    ["blockedCustomProtectorOpenMakesShutdownResidualUntilProtocolWorkExits","4e2940aadf6b81531aaf2c9fb67f63df03035d28ebd927256a287f35d629bdf2",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"requiredAction":"RAISE_OUTER_BOUND"}],
    ["blockedCustomProtectorOpenDiscardsLateResultAfterDeadlineOrDisconnect","9fe0428fbf8a051f8856858b80b43e838880af781f3cf347ae86a50a84792910",{"dynamicNodeCount":2}],
    ["blockedSealCannotPublishLateInputRequiredAndReleasesExactlyOnce","9fa9b58c00aca22633217bf700bf7e481d25fd151076c03d2621399daecb4529",{"dynamicNodeCount":3}],
    ["conditionalCapabilityHoldTerminatesWithoutProgressOrLateResult","e2a6c3af0f11bbd8c32e537b0c7209aebfeb71932f14c3f78dcf445f1d2d9bd1",{"dynamicNodeCount":2}],
    ["sameAuthenticatedStateCanBranchWhileOneFreshIdTerminates","e2658db8bc19db08af218c4aa7408c71c66d309caa4d4edfe61b30847f57274c",{"dynamicNodeCount":2}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpProtocolProfileRegistryTests.java", "3dba1d2df07ba340cc0d08918e2672cf7ce0cc49613e59f4601397af442e3f2a", [
    ["fakeProfileEntersOnlyThroughTheExplicitRuntimeTestSeam","6f7e90f54ed0a74a1f86ef1cfd4723d2b55367c9552e86498838855cf2773a1f",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpResultEnvelopeGoldenProductionTests.java", "7c91d8489de726e5d2d671422a898b2b0fbea1bcb1b831fb0d5420d9f9e67ead", [
    ["everyFrameworkAndApplicationCompleteAuthorityMatchesGoldens","5e5d728543e4b8c9ec64dee98b5c42bce7625b7bf205839f918b3de193e58a42",{"generation":{"count":4,"mode":"SEQUENTIAL","complete":4,"prior":3,"incomplete":1}}],
    ["requestScopedAndSubscriptionSseTerminalsMatchGoldens","e741d914496c6f9f1c361cf459271ef55bbcf2f1a5e611eb03b4057fe7f64061",{"generation":{"count":3,"mode":"SEQUENTIAL","complete":3,"prior":2,"incomplete":1},"controlJoinMillis":0,"controlComposition":"REVIEWED_OVERLAP_OR_DUPLICATE"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpSelectedProfileBindingTests.java", "5656ec3fb5f7fc9a4c258997f84003a968c5bd0628779b65e88f18b9439d609f", [
    ["subscriptionAndSimulationRetainTheSelectedProfileForTheirWholeLifetime","d1973555f04b5023222f6251df7ae83dc4cfd92d164cd14dfd03487778172914",{"generation":{"count":2,"mode":"MIXED_MAX_PLUS_SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlledLifecycleCoreMillis":50000,"controlJoinMillis":15000,"controlComposition":"REVIEWED_OVERLAP_OR_DUPLICATE"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpSubscriptionPublicRuntimeTests.java", "5631c392ffe1a026e55205f316af31a68a8a96cd529280742d745160d4eec715", [
    ["gracefulHttpShutdownEndsWithOnlyTheTerminalCompleteResult","fb45b5659d7297085ce685282006124e995394f55128e73d3eb5b85308bcfdb6",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["nullAndThrowingAdmissionNeverActivateOrConsumeSubscriptionQuota","8d487fe681050558d3ef47ad3736091583b3ad1da1d75509b22b140f8cba1d0a",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["publisherIdentityIsGroupedPerServerAndSharedAcrossServers","88d5a74bd6fe207fa20b977750c0d235fbc520284ace2560a8b39730e523e42c",{"generation":{"count":3,"mode":"SEQUENTIAL","complete":3,"prior":2,"incomplete":1}}],
    ["validListenUsesAdmissionAndRequestLimiterOnly","67c47e3a7e886bdb3510213238212bd95c1332361587f9dda29bdafe49c47317",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpSubscriptionRuntimeBoundaryTests.java", "adb0dd2e2fecfe61e9acadeb7b76a88d1a2f5dd5c5e4ce1ab7ed86c457ca7eb8", [
    ["gracefulShutdownReservationBeatsConcurrentPublisherExactlyOnce","975a4aa52ecee3afb3c905969b29d88ff12e1da27d04002ecdbe3634776a2662",{"controlJoinMillis":5000,"controlComposition":"REVIEWED_OVERLAP_OR_DUPLICATE"}],
    ["blockingRegistrationCloseIsBoundedAndNeverRetriedConcurrently","e3329ccdae4d8c4e5cb6743b0fa76199f1afa3662a9e44e35279144ce9d83f40",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["deactivatedGenerationCannotPublishIntoRestartedServer","7d47ff10bbfd4624e0802078ca71b36eceec76b30496b1e967735c43fbadbdcb",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["failedRegistrationCloseIsObservableAndBlocksRestartUntilRetry","4e194cff09de95633ca26f7459406799379f5a2a4a298d777591ae264b0d71ce",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["startupFailureRollsBackRegistrationsAndCanRestart","34882cdd2bc656ee979db7386c04c51fca7542615eda6b565c16a09cc2982f9f",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["startupRollbackCannotBeHeldPastItsShutdownDeadline","6d37c78d40847738ed6540c919eae1931d923b782397bbcb48664149d109c11a",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["startupRollbackRetainsFailedCloseUntilSuccessfulRetry","e2df580afb7217d33dbdabf217dd679789b2c282e7d17f65f57f7ea1aa33cc8a",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpQueuedExecutionWinnerElectionTests.java", "15c43fb45177069406ad834ac7e5ff9caaed3755bc1a716f83aad2088eb6594b", [
    ["all_queue_promotion_deadline_disconnect_linearizations_elect_exactly_one_outcome","8be5079ecfbe8cc5c5ca23e3a7f74a8fd6c54dbb81638b38ffacee243a80b996",{"generation":{"count":6,"mode":"SEQUENTIAL","complete":6,"prior":5,"incomplete":1},"controlledLifecycleCoreMillis":5000,"controlJoinMillis":240000,"requiredAction":"RAISE_OUTER_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/transport/McpTransportContainmentSpikeTests.java", "e0e8198e51caf9a246e5dd8a5937e406f2589ab9b11333ae93a1857ae24e6bf1", [
    ["containmentMatrix","382671af5e8be26bdca44fc59df1c702342a41c0a94cf241d29964ed59c81f4e",{"dynamicNodeCount":15,"controlJoinMillis":8000,"controlComposition":"REVIEWED_DYNAMIC_NODE_MAX"}],
    ["initial_deadlines_remain_ordered_across_signed_nano_time_wrap","8491b77e9bd240c850793af942a0c3d0743fe2478af08f8078c6860bce4cbad6",{"controlledLifecycleCoreMillis":0,"controlJoinMillis":24000,"controlComposition":"REVIEWED_LIFECYCLE_CORE_DEDUPLICATION"}],
    ["unarmed_keep_alive_sentinel_cannot_fire_across_nano_time_wrap","9154b0cf3ba212f2cb3e451def2ee07d4deb894055b68310452a52afc16d253a",{"controlledLifecycleCoreMillis":0,"controlJoinMillis":24000,"controlComposition":"REVIEWED_LIFECYCLE_CORE_DEDUPLICATION"}],
    ["rescheduled_keep_alive_remains_ordered_across_signed_nano_time_wrap","1885606e043f4d5bd9731db2a75211efe5ec500fdea3f088792f4f76cc791e2c",{"controlledLifecycleCoreMillis":0,"controlJoinMillis":24000,"controlComposition":"REVIEWED_LIFECYCLE_CORE_DEDUPLICATION"}],
  ]),
  ...reviewedScopeFile("src/test/java/examples/mcp/McpLocalizedCursorFleetApplicationPatternsTests.java", "41b49389aec24b8a2a6eef7dda1fa81b7057a2651be875c4ee70c1ffaca61ad8", [
    ["cursorFailuresPreserveOpaqueBytesAndCollapseToOneNeutralError","4d4627fc74692b252dd66ca031946dd91b241bd00028288411200722da0080d0",{"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":1000},"generation":{"count":10,"mode":"SEQUENTIAL","complete":10,"prior":9,"incomplete":1},"controlJoinMillis":100000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","requiredAction":"RAISE_OUTER_BOUND"}],
    ["localizedCursorCrossesNodesWithStableSnapshotLocaleRevisionAndPageBounds","c23e53ce8a2ae33e696b0652e8026318d6a5196c9f410ee66af7f989b4cc97c9",{"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":1000},"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":50000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SokletApplicationProcessTests.java", "85b493f9d67f53858a76316793df4c88ffdde37b9e0db8d487880f2cab0c8cc0", [
    ["startupFailureAndTimeoutRemainPrimaryWithIncompleteRollback","077fce9b32af9ecaabc5e1c7ae28d5aef7c9e9cf1ba9f1f63d059a0474a0778a",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":0,"incomplete":2},"applicationCleanupCount":2,"incompleteBranchCleanupCount":0,"terminalReportCount":2,"requiredAction":"RAISE_OUTER_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpHttpServerRuntimeTests.java", "d3a8851fe9cefb26914565a1454e2e6745929ad36ca9e8857aedde74c831ca64", [
    ["literal_loopback_bind_spellings_do_not_require_allowed_hosts","3d1edfa1f0627558c8bd577bcb4936857cfe630d55d6aaf55299a5c200d9082b",{"generation":{"count":6,"mode":"SEQUENTIAL","complete":6,"prior":5,"incomplete":1},"controlledLifecycleCoreMillis":0}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpCompletionWireParityTests.java", "2b8e6a9607a34990c8eb36f612cf242b6c14b2321c94681a3341df6fd122c676", [
    ["empty","c8040defb4386c409d195e45a0a1b82e3f4f11c9fc8ff46a02a3513bd43b86d0",{"generation":{"count":4,"mode":"SEQUENTIAL","complete":4,"prior":3,"incomplete":1},"controlJoinMillis":30000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["hundred","fa65d224bdfc69aaa4f2ec9fa8e2f6600be6a4c5d79c3471a116c73bc38082cc",{"generation":{"count":4,"mode":"SEQUENTIAL","complete":4,"prior":3,"incomplete":1},"controlJoinMillis":30000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["totalOnly","b122bf07ed6130f6e34313bd0e488b3bb90eef8e25f2e03d377d49163971203a",{"generation":{"count":4,"mode":"SEQUENTIAL","complete":4,"prior":3,"incomplete":1},"controlJoinMillis":30000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["moreOnly","bbad1f7ef5022120952d971c7f440149cbdeea036e35f5cafa1ed98d2eafcd92",{"generation":{"count":4,"mode":"SEQUENTIAL","complete":4,"prior":3,"incomplete":1},"controlJoinMillis":30000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["both","e6be21a180a9b116de2243e9e1cdc32d46c0d7530fcf722eb3a4d5f1fa2c90df",{"generation":{"count":4,"mode":"SEQUENTIAL","complete":4,"prior":3,"incomplete":1},"controlJoinMillis":30000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["maxTotal","8c9aa28deed0a14c199c34faa99b473029e0e6e591e84e2a439e4a590fbd088e",{"generation":{"count":4,"mode":"SEQUENTIAL","complete":4,"prior":3,"incomplete":1},"controlJoinMillis":30000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["tooMany","e2e076085aa774a292c0be2af0322e70aa7a3c402a67a56a3df2ba2f11624773",{"generation":{"count":4,"mode":"SEQUENTIAL","complete":4,"prior":3,"incomplete":1},"controlJoinMillis":30000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["negativeTotal","99f62c20f8e5e9acd4f373d5117d5f5f3ddc54cdfc834e554998f73520a9c578",{"generation":{"count":4,"mode":"SEQUENTIAL","complete":4,"prior":3,"incomplete":1},"controlJoinMillis":30000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["unsafeTotal","c75735ed188ab3a369ad41d8afca4bace5b9c51549aee607b0433505edbaf9eb",{"generation":{"count":4,"mode":"SEQUENTIAL","complete":4,"prior":3,"incomplete":1},"controlJoinMillis":30000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["belowSize","4dcf4fbe3de680d98019801f71aaa9c9d0c5362a3f347c93bb411084fc56020c",{"generation":{"count":4,"mode":"SEQUENTIAL","complete":4,"prior":3,"incomplete":1},"controlJoinMillis":30000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["inconsistentMore","f322d9acdfef8b1cc0d85e02893bc3b20ebd00566f367b03b9b56297986435db",{"generation":{"count":4,"mode":"SEQUENTIAL","complete":4,"prior":3,"incomplete":1},"controlJoinMillis":30000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["inconsistentComplete","840a5dfd12d9a8e92b2bf672a073307de9db2a6be777c06bac05c49087fb8709",{"generation":{"count":4,"mode":"SEQUENTIAL","complete":4,"prior":3,"incomplete":1},"controlJoinMillis":30000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
], 'lifecycle scope topology');

const REVIEWED_PHASE_POLICY_OVERRIDES = checkedReviewMap([
  ...reviewedPhasePolicyFile("src/test/java/com/soklet/LifecyclePolicyTests.java", "1e913492fed46f7dc26e621a325f89e0afe58cbb5f66d63332c2879fa4993143", [
    ["negativeAndNanosecondOverflowingTimeoutsAreRejected","5e09609efbdc80c4bca9d987e57fd31bb400ae72fe94586f3265640c211609fd",{"forcedShutdownMillis":3000,"gracefulShutdownMillis":15000,"startupCancellationMillis":2000,"startupMillis":30000}],
    ["nullRestoresEachBuiltInDefault","70e57c423a1c12469ae46b5767b017a23f583e9bcb790522f57d5a7b305383c3",{"forcedShutdownMillis":3000,"gracefulShutdownMillis":15000,"startupCancellationMillis":2000,"startupMillis":30000}],
    ["policiesUseStructuralEqualityAcrossAllFourTimeouts","854da36425d693c070eb7b6ab139b2f628070d79abacd765f787c75503d4a479",{"forcedShutdownMillis":5000,"gracefulShutdownMillis":5000,"startupCancellationMillis":5000,"startupMillis":5000}],
    ["zeroRemainsAnImmediateBoundary","e137b16cbc06b0fe4d5700ea7053284884ded79e7d3ef904b71c956e58859323",{"forcedShutdownMillis":0,"gracefulShutdownMillis":0,"startupCancellationMillis":0,"startupMillis":0}],
  ]),
  ...reviewedPhasePolicyFile("src/test/java/com/soklet/McpHandlerMetricsObservabilityTests.java", "8d3f0f334bacf0ec22eadbcaee75c3762e860036a3b6eb87a894e53f469caace", [
    ["defaultCollectorAggregatesConfiguredZerosRendersFiltersAndResets","1519ae9fd7f36f20cbb74dfcc21cb194cd490754293ccacd40e866037f45d0c9",{"forcedShutdownMillis":3000,"gracefulShutdownMillis":15000,"startupCancellationMillis":2000,"startupMillis":30000}],
    ["sokletOwnedSaturatedListenerEmitsExactServerWideTransitions","4aea13bb236c46f47cd6292abcb1b30ed6170f9a95ac5a9ecc0989ae252ed635",{"forcedShutdownMillis":3000,"gracefulShutdownMillis":15000,"startupCancellationMillis":2000,"startupMillis":30000}],
    ["queuedDeadlineDequeuesWithoutExecutionAndRetainsActiveGauge","072447bdc3fac24aeb356e1707048174fa8010606d15ca4ef6dd5ac6e4986ce4",{"forcedShutdownMillis":3000,"gracefulShutdownMillis":15000,"startupCancellationMillis":2000,"startupMillis":30000}],
    ["queuedDisconnectDequeuesWithoutStartingHandler","df914124553ef923f5a9f5fdb5856b9b2695dc0ec87ccc96af6ec85f595ce1d0",{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":15000,"forcedShutdownMillis":3000}],
    ["managedResidualShutdownDequeuesAndFreezesGaugeAcrossLateExit","4681101ac6e14f0dde692d5ba2d481450422cf0d7bd7e381c309949dce6ffea1",{"forcedShutdownMillis":100,"gracefulShutdownMillis":100,"startupCancellationMillis":100,"startupMillis":5000}],
    ["managedStopDefersQueueAndExecutionCallbacksBeyondLifecycleLocks","7c141d8300239299c62293fa3562bfecd96042ddf000e1c8b3f69add97bd77b2",{"startupMillis":5000,"startupCancellationMillis":100,"gracefulShutdownMillis":100,"forcedShutdownMillis":3000}],
    ["unexpectedTerminationDefersQueueCallbackAndFreezesTerminalGauge","4c0c33330fbd8c8c8d749894f8267d1a569d6fd5e75537c1cfd597c9f1bd6ba4",{"forcedShutdownMillis":100,"gracefulShutdownMillis":100,"startupCancellationMillis":100,"startupMillis":5000}],
    ["handlerMetricsCollectorFailuresAreContainedAndLogged","6064ee632f9baf05b0ebcbd977078adf42c02bcfbe724dd2994092afc324bd54",{"forcedShutdownMillis":3000,"gracefulShutdownMillis":15000,"startupCancellationMillis":2000,"startupMillis":30000}],
  ]),
  ...reviewedPhasePolicyFile("src/test/java/com/soklet/McpHandlerQueueDiagnosticsPublicRuntimeTests.java", "a070a11617affcb2eefda890748f30440f3afc7f50d5006f298865a34de9f271", [
    ["configuredValuesAndZeroLoadRemainStableAcrossFreshCleanOwners","694802f3c6e168d727ac1e01ac3718cda27a15d4b82324da139ac3d9f34853d6",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["crossEndpointSaturationPublishesRetainedAndBoundedConcurrentTuples","bf67fbfbb7bc74dd14f9e811756a8a425fe2242c91e026a347f5df08f6144250",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["residualStopRetainsOneActiveAndDrainsQueueUntilLateExit","b87fc0c5be57b5735e3ffef9568f235a5ffad7df06ce578207a33b52ab894453",{"forcedShutdownMillis":100,"gracefulShutdownMillis":100,"startupCancellationMillis":100,"startupMillis":5000}],
  ]),
  ...reviewedPhasePolicyFile("src/test/java/com/soklet/McpLifecycleB3Tests.java", "853c9c9d7b7b6b2bfa50b364a8c009b2612064c7ae8f759d791866df505bac8b", [
    ["unaryAdmissionIsGenerationScopedAndReleasedExactlyOnce","f7844c6fcc8713e748969679b7e07e43da22b1cb8fe047b426bc973a6874287a",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["cooperativeHandlerAndRequestStreamDrainGracefully","21b9f771e59507793904a964f96d913eea9822f31200136e124af6dfdb90cf1e",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["unexpectedEventLoopFailureFencesBeforeProofAndRetainsAddress","e5a4fc15428bd0b3ab87b7430a0f2ebf6281a21282fe4905918703b64f5b6f52",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["eventLoopFailureAfterRequestedStopRemainsOrthogonalEvidence","5e81f42f5a8864e8ccbe6f691973b8ee5c5a8f1cf7eb2ff973b136ab91ae1a3e",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["oneShotOwnerCannotConsumeUnexpectedGenerationBeforeExactResultPublication","8ec7c5714e6b014e07678de524cef1b8a49784ca98bdd8f04dcb66d811c62c5b",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["unexpectedEventLoopSignalsFailureBeforeAdmittedHandlerTeardown","b63d987ed1183f72ede7c66afcdc39919f2a11927fed7751d1805450ba2ad8f6",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":10000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["startupErrorPreservesIdentityAndFreshOwnerStartsAfterNeverBoundFailure","e0b981d64b24aa620c0f977c324e8c96b151e9a7728f52856b5b583d4f125559",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["fixedPortBindIOExceptionPreservesExactCauseForFreshOwnerAfterRelease","6772bb93ffa9dfb1a0c4c130af2741cb611f389786039488b27b478619a1c873",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["postBindPreReadySubscriptionFailurePreservesExactIdentityAndAddress","22377635b1ac4eae868a88512686e919614e30e3622b8d6400ff678ed6156170",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["idleSubscriptionClosesPromptlyWithServerStoppedAndNoForce","d16b3d7074e5c58a7d42cc6b290ed0dbc5147ad17b5288fc720440cd25355470",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":10000,"startupCancellationMillis":2000,"startupMillis":5000}],
  ]),
  ...reviewedPhasePolicyFile("src/test/java/com/soklet/McpRequestStatePublicRuntimeTests.java", "8cab0ed5e543acab82d90f61292d18f2f1c138858f70456e3aa2a46f08ebc40c", [
    ["applicationProtectedStateRoundTripsExactlyWithOneSharedContext","5c873557cf1727b3e0730bdbdd38d5da17c93b3bca20af023950f6afd110170a",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["frameworkProtectedStateCompletesOnlyWithAFreshRetryId","07bf83ad4235bcdc7c791542d2b1b0bf76bfaebdb11cc3ef66e78c52a470be93",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["frameworkProtectedStateContinuesAcrossInstancesOnlyWithinItsKeyAndAuthorizationPartition","1b0720ed899f8d36f3a5b7722a9331943f60519f80991b80e3c4a922af67a4b9",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["malformedTamperedAndUnavailableStateHaveFixedPrecedence","7af1b7b794c69072f012d5ca408a829f2a1743e3b652ace9fd3ca712a1a3632b",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["resourceRetryStateForcesPrivateZeroTtlAndNoStore","43d8097bdf5dcf848c84c85dd98ec1ac1b70ff0e491591a36f43e5dd12631f61",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["requestLimiterChargesTamperedFrameworkStateBeforeOpeningIt","96e0bdb7ef658d91c4caf1f99eb69d42603458eff10a70570b0c50fdbf7264fe",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["toolLimiterChargesTamperedFrameworkStateBeforeOpeningIt","235b4369efe5406838b043c7877626f14fb8438f0fd7a0b515707b2173ec16b9",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["nullToolLimiterFailsClosedBeforeOpeningFrameworkState","05ea97059ab267bc841eb808effb0cc0efed2785682305949dbadb5952f920ac",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["throwingToolLimiterFailsClosedBeforeOpeningFrameworkState","eab634b7f093c5e82950b3d9c57ebf842b49fd142ae60172ae88c45057497a72",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
  ]),
  ...reviewedPhasePolicyFile("src/test/java/com/soklet/McpShutdownObservabilityTests.java", "aeb56738880c2edd7a659e63d7e2cdd2d33b34c97d29014c1f0bfb1d5a6ee977", [
    ["managedCleanStopEmitsOneMatchingLifecycleAndMetricsOutcome","88e1e4c1ab7669edbe8a126aba648d0af1dd4e18d5cbe2cc52710f9140eadf2b",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["lateShutdownFanoutCannotReopenTerminalMetricsDeferral","355ede79ae26f91af953d240fc2a4c12b081977126b1c99cc5d7f970bcc6828f",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["freshOwnerCleanStopRecordsOneLifecycleAndMetricsOutcome","6c6255b86940e0d23af7ff500e6251d42f603248f0a4c0284e1de89b76f3b452",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["didStartObserverFailureDoesNotVetoOwnerOrCleanStop","623a6d64a0ff7ef898d0e789b95ce3e59f6d1d2c4d39b25c70a602c529063fc1",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["failedSubscriptionRegistrationCloseRetriesAtForceBeforeOneForcedOutcome","cf0407f01f8de082a99118b9c6c7a8dfa1d3ce7dc3b5a9119b7260b3054dc069",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["blockingSubscriptionRegistrationCloseFreezesOneResidualOutcome","0e9cb82f93de492eb50cb8724ac6e0f76c477a6f4ad6165619b31f821d870075",{"forcedShutdownMillis":100,"gracefulShutdownMillis":100,"startupCancellationMillis":100,"startupMillis":5000}],
    ["unexpectedListenerTerminationAndFreshOwnerHaveExactParity","658fb897e3bcf0c822984d9d201f5c79c95dee91bc0957419d653a984b2c9b14",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["ownerNormalizesUnexpectedGenerationExactlyOnceAfterAdapterWait","39e48c1d89a57026241e6d00641b34ff51e116108eb37e6fd35945c2a91973f7",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["rejectedUnexpectedRestartDoesNotDuplicateBeforeFreshOwner","f4e7d5392de0df5da2dd468969ae18d43b2188c81d87fa21afabdebd1d4ef9ef",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["failedStartCleanupEmitsOneExactForcedServerStoppedEvent","1e3d87833c721808e0d582c2312c20f4fa87b33cfd351d7fd026f88f8fbfd1dc",{"forcedShutdownMillis":100,"gracefulShutdownMillis":100,"startupCancellationMillis":100,"startupMillis":5000}],
    ["shutdownMetricsCallbackRunsOutsideServerLifecycleLock","722e5cec860135f81335e86f6d5fa8dc095a6678079030e53a2abf0fa6d0f518",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["shutdownMetricsCollectorFailureIsContainedAndLoggedOnce","68922c414ce532a2e73d80a216e4d697e5822369c88e9a80da8e8bd3c5b9dda8",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["residualStopAndLaterExitDoNotDuplicateLifecycleOrMetricsOutcome","7279d8846c1263409c86b2c53e4a2db929463300d7dacc6d523b35769f8f1718",{"forcedShutdownMillis":100,"gracefulShutdownMillis":100,"startupCancellationMillis":100,"startupMillis":5000}],
  ]),
  ...reviewedPhasePolicyFile("src/test/java/com/soklet/SimulatorConfigDerivationTests.java", "5252bf391e494dad0420628ccd5a5407d55204c1ffb2c961d76e6e2a6891e94a", [
    ["derivationCreatesFreshTransportsAndLeavesSourceReusable","e8c100e7fcd7c1d73c30f731fec4de8d067815b4974714b9b5dbf96244128b13",{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":1000}],
    ["importedMcpConstructionIsClonedAndCanBeCustomized","bc53753592d591e6a55ef5d17d6f544888f5bfdbdaa59fd4fbdd149ffc71251b",{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":1000}],
    ["directRunCreatesAConfigurationPerInvocationAndBuilderAcceptsOptions","b6dbc0f825a131dbb475c79cf3b174275089bcb27b801374f09763543f4de8e9",{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":1000}],
  ]),
  ...reviewedPhasePolicyFile("src/test/java/com/soklet/SokletDirectHttpCompositionTests.java", "b1e226c98b5868f066a9f851145a931864e1abb3d85509cedbae93164c719100", [
    ["forceBeforeChildProofCancelsUnsubmittedCleanupWithoutRejection","71a3e31e2b30174d68365dd52a2b5ce84da32498dd49651477e80b5b6367f554",{"forcedShutdownMillis":2000,"gracefulShutdownMillis":75,"startupCancellationMillis":100,"startupMillis":2000}],
  ]),
  ...reviewedPhasePolicyFile("src/test/java/com/soklet/SokletDirectLifecycleRaceTests.java", "ee51affe849155a95f83f3799e63e53d01930eeb9f998a29e35866c555d35500", [
    ["closeAfterStartClaimCannotPublishNotAttempted","ecde80f60e9b90e011eacea59d7e082b401cb9b933cf8a19b1bf3ceb0202aa9f",{"forcedShutdownMillis":80,"gracefulShutdownMillis":80,"startupCancellationMillis":80,"startupMillis":5000}],
    ["lateBlockedAttachReturnIsInertAndCannotEscapeTerminalEvidence","9d92aee5dd6d35ba42fa72d36d9513cda7c8f0d5f76b668adfbe69f711bbfb52",{"forcedShutdownMillis":80,"gracefulShutdownMillis":80,"startupCancellationMillis":80,"startupMillis":5000}],
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
  ...reviewedPhasePolicyFile("src/test/java/com/soklet/SokletDirectLifecycleTests.java", "ad05772b0125b5bcf446e84bbd00badad74cdf2cbaef4f7c18467f91c57f6a85", [
    ["blockingFrameworkSetupIsBoundedByStartupAndShutdownBudgets","e15a71164f4d44935fdef10beb8d9a350688b4c17e6507931d50dbf9c149d9f8",{"forcedShutdownMillis":75,"gracefulShutdownMillis":75,"startupCancellationMillis":75,"startupMillis":75}],
    ["blockingTransportStartIsBoundedAndCannotPublishLateReadiness","97736864a900e952a6688b8cd001d76a3d096ba5450b0400bd240e5a5d5ee8dc",{"forcedShutdownMillis":75,"gracefulShutdownMillis":75,"startupCancellationMillis":75,"startupMillis":75}],
  ]),
  ...reviewedPhasePolicyFile("src/test/java/com/soklet/SokletDirectMcpLifecycleTests.java", "d6834206996c37bccd7cac47015bc27234eb6cfe7e522f2661d0772ec315751b", [
    ["blockingSubscriptionPublisherTimeoutClosesListenerWithoutOverlappingApplicationPhases","0cb35dda144f3b395e73d64a25ffdf1e87c3a557f7c6ca2f1ff86d4c4e3d536a",{"forcedShutdownMillis":250,"gracefulShutdownMillis":150,"startupCancellationMillis":150,"startupMillis":1000}],
    ["externalShutdownCancelsBlockingPublisherWithSameTerminalIdentity","5393c4227fd80704ee07ea3c0a6488e7ccef510f205d3531b65cf16d22cbcc32",{"forcedShutdownMillis":250,"gracefulShutdownMillis":150,"startupCancellationMillis":150,"startupMillis":10000}],
    ["synchronousMcpStartupCleanupFailureRemainsBoundedSecondaryEvidence","a074616d21487ca515bbd6fe581b3122d2c280749cbbc0dfc66005bd59f7b1d9",{"forcedShutdownMillis":250,"gracefulShutdownMillis":150,"startupCancellationMillis":150,"startupMillis":10000}],
    ["lateMcpStartupFailuresCannotMutateFrozenEventLoopPrimary","53a54da7546ecf23445a5c3ac2735b3ab643f20d1b233c9504e99b0e3ea5c581",{"forcedShutdownMillis":250,"gracefulShutdownMillis":150,"startupCancellationMillis":150,"startupMillis":10000}],
    ["admittedMcpHandlerSelfStopPublishesIntentAndDrainsResponseWithoutSelfJoin","b70e6a0bddf1bf32f405a735822457f9d22a9d8a116f30da5e1cccc4149ea6bb",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":5000,"startupCancellationMillis":250,"startupMillis":5000}],
  ]),
  ...reviewedPhasePolicyFile("src/test/java/com/soklet/internal/mcp/protocol/McpStreamSubscriptionDiagnosticsPublicRuntimeTests.java", "d0478c183dd3553f64862149d6d38dd4b55f0fb877d60e84f5cbed8adf423e7e", [
    ["ordinaryAndSubscriptionStreamsAggregateAcrossEndpointsAndCleanOnDisconnect","3cf18bb52b65bc2da74008ea443a87ab83cf3fb4d98935bc3a55b3ca2988984e",{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000}],
    ["residualHandlerStopPublishesZeroStreamsBeforeLateHandlerExit","6b9520765cc0596b7e5a71cdccbf10b0ea43762fa8df54345e6da39919efe650",{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":150,"forcedShutdownMillis":150}],
    ["unexpectedFailureRetainsOneSubscriptionUntilCleanupWithConcurrentInvariantReads","e1e2e84dc08e53e62be658cb149d1317e362bb4112588d3ed0d06966ef675243",{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000}],
  ]),
  ...reviewedPhasePolicyFile("src/test/java/com/soklet/internal/mcp/protocol/McpSubscriptionPublicRuntimeTests.java", "5631c392ffe1a026e55205f316af31a68a8a96cd529280742d745160d4eec715", [
    ["acknowledgmentIsFirstAndPreservesExactStringAndIntegerIds","b5962a6919e4491d768afa87648431e23774aec0d4254a744ba28eaf18dba14f",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["publisherEmitsOnlyRequestedResourceEventsForMatchingUris","256689db4ae9f6356f63d2bbf9da2b74c5e8c1a7de24d71fa77ac293fbc18f93",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["supportedIntersectionOmitsToolsPromptsAndUnconfiguredResources","0e7cf6d2b89746f8cac35d6b4de3c91f4ad89cdd6199fb59cb5a056d589ed789",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["declaredNonlocalizedCatalogFamiliesAreAcceptedWithoutResourceSupport","b3c14ac01c4c20a9ad449cdb05703b0e1220148ebf4f22d69bc5147ced80d6d0",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["malformedRecognizedFilterFieldsFailBeforeAdmission","f03f17b21a74ec608292bed1a5800295a9930e0bd2a85d064f37b65c15a0bfe3",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["validListenUsesAdmissionAndRequestLimiterOnly","67c47e3a7e886bdb3510213238212bd95c1332361587f9dda29bdafe49c47317",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["rejectedAdmissionNeverActivatesARegisteredSubscription","5f2170fa8d30d6a03de32a6b1d2961bf3c26a0b49fcbc654433743fc8c4ce142",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["nullAndThrowingAdmissionNeverActivateOrConsumeSubscriptionQuota","8d487fe681050558d3ef47ad3736091583b3ad1da1d75509b22b140f8cba1d0a",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["liveSubscriptionDoesNotConsumeTheConfiguredHandlerSlot","b5d6e75ec33c6d7094c65761365b3b5c5ed5d35a9c0effd3ecc3f50d57366e48",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["configuredPerPrincipalCapRejectsWithoutDisturbingAndRecovers","d6263f95adf47bff9a2cdf9c133423469276c30e7c563ad7131261d758e23fdc",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["sameIdSubscriptionsAreIsolatedAcrossAdmissionPartitionsAndCapRelease","14acf3fc1afc9550175bd0e711110179abc4ce9d1d3b31cd3f82e257cbfe530f",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["publisherIdentityIsGroupedPerServerAndSharedAcrossServers","88d5a74bd6fe207fa20b977750c0d235fbc520284ace2560a8b39730e523e42c",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["gracefulHttpShutdownEndsWithOnlyTheTerminalCompleteResult","fb45b5659d7297085ce685282006124e995394f55128e73d3eb5b85308bcfdb6",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["configuredMaximumDurationPublishesExactLifecycleAndMetrics","aee7f374d52ae46f37f396c0b20f9082e9ac2c3cbc6949eb7ed2005aab5789f2",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["clientDisconnectReleasesStateAndPublishesExactlyOnce","06776a37a9b16c9ffc411d2fcb2f9c16d72004d10a901f713247ed2f452c1b79",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["keepAliveAcceptanceSharesStreamTransitionWithCloseObservation","216bdb4112831b0376162aa951693f5a855a96209fbcaad09cd542c04a10c64c",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["publisherVisibilityBeginsAfterAcknowledgmentActivation","e5d88c4b9f0464346291492c49877c3d3ca5741249414b5627250fa6abd05688",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["configuredQueueContainsBackpressureAndReleasesTheFullCap","949a18ede9f3cf034ee4453e5aea45d67e77febee7266d2427f60d7d183695f1",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
  ]),
], 'lifecycle phase policy');

const REVIEWED_CONTROL_OVERRIDES = checkedReviewMap([
  // One simulator generation, three modern calls and six legacy calls.
  // All nine response waits are sequential and bounded at five seconds each;
  // the nested exact-revision/URI loops need an explicit multiplicity review.
  ...reviewedScopeFile("src/test/java/com/soklet/McpAnnotatedResourceProcessorRuntimeTests.java", "7b648ca121feeeb110d35a994e1e0e5794d0024d4d61500278ade0b9fbe79825", [
    ["generatedProviderPreservesResourceContractsAndInvocationBindings", "101f7463f3b801366c30f6f2d773682a4eaae34c8ee9367dfb76254cc2643845", {"controlJoinMillis":45000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/BuiltInTransportLifecycleAdapterTests.java", "15f29083d2dfdf367f98d9ef7fe36d0e68ca3c87a25be3754707b37c13371258", [
    ["failureRacingNormalShutdownIsRetainedWithoutReclassifyingRequestedProof","d5b8e568c287856debb44e45137ebaa592ca10ece32eb55ee6be5c748bcd9e15",{"controlJoinMillis":3000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
    ["requestedProofThenLateFailureBeforeFreezePreservesBothInSequence","0486a99df1c9b6163e90faa067fb5cc24f94f2aaa2a1d586bc815614211943bb",{"controlJoinMillis":3000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["exactGenerationOperationsRejectForeignTokensWithoutMutation","ff1b277adb13768eb39a9e28b2a43a292e48b75af80cf0ca8439c542f6a8535c",{"controlJoinMillis":0,"controlComposition":"REVIEWED_NONBLOCKING_PRECONDITION"}],
    ["launchedThenThrowingCoordinatorCannotRepublishOrReleaseEvidence","b03f92588538cd5cf7ce738deb0df255146cbe88d855fa64e643e0bc50965ef4",{"controlJoinMillis":2000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/DefaultHttpServerTests.java", "f73850c424e890383f5e9912ee48e6aa83b1eb2fd816fabd202331f492578f3e", [
    ["httpServerCleansUpAfterUnexpectedEventLoopTermination","c0b3c2429b828d7d6b97cc5d324abf4e1bb75c12ec4b6473a7ff080dab147086",{"controlJoinMillis":4000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["stopCannotPublishAnEmptyGenerationWhileStartInstallsHttpResources","daa38ff9c7f3f1effcc52664813c709b9eb9b4ad38458546dff9dd23de556bd0",{"controlJoinMillis":7100,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
    ["startRejectsRunningHttpGenerationWhileItsStopIsInProgress","468e5c2b4365d5a53148b8525487911d065bcd75075ffac281f4cd6b149d25be",{"controlJoinMillis":6000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
  ]),
  // The three invalid-length exchanges are sequential: each has a 2-second
  // connection retry and a 4-second socket read timeout. Retain the conservative
  // 18-second control allowance in addition to the explicit lifecycle policy.
  // Existing reviewed scopes and their control topology are unchanged.
  ...reviewedScopeFile("src/test/java/com/soklet/UnparsedRequestTransportTests.java", "25f9e337f5ff184b0f8fd922e02ed0cebed5c4b25f3eb0efa07bfada9d024069", [
    ["invalidContentLengthsUseTheMalformedRequestObserverAndResponsePath","cc0ccac843956492caa3234150614be8ef7fc9ede8575597bf55e2fba5145bd1",{"controlJoinMillis":18000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["allParserRejectionsAreObservedAndMarshaledOffTheEventLoop","0580175f6fb5a617400ddb1996e940bf254b40571010f2fe6b2975c069db6da1",{"controlJoinMillis":30000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["timeoutsAndUnsafeExecutorDispatchUseTheBuiltInFallback","20c6dc35cb575f66b6cce0ca69f99002dab5460762a9e2844f2c311baadd458c",{"controlJoinMillis":17000,"controlComposition":"REVIEWED_OVERLAP_OR_DUPLICATE"}],
    ["unparsedWorkOwnsOneLifecycleAdmissionUntilItCompletes","7f465aa9eefd15d7a0e31b630995a403edd000811448399867fa630a13affaeb",{"controlJoinMillis":18000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/ExternallyCoordinatedTransportLifecycleAdapterTests.java", "7be4c5d1b5fe67a92e4457584bf75f6c854953f14b21daaa36521e216b1575b7", [
    ["externalSelfStopPublishesIntentBeforeOwnerScopedWaitFailsFast","80b2f130651f5b7f7a03444787899e2b42e6e82c05f7b3ec251201b0444ae4e0",{"controlJoinMillis":0,"controlComposition":"REVIEWED_NONBLOCKING_PRECONDITION"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/HttpServerLifecycleTests.java", "666de09c5200fc245c3761595022782f2343b484cb875a803c7ce5c56ae3a5c5", [
    ["ownerLifecycleAttachesServesAndPublishesOneGracefulResult","bdc79f45c02504b7a9e480a89bc117cf22955dd98679b94b89b676619bc3c86b",{"controlJoinMillis":0,"controlComposition":"REVIEWED_NONBLOCKING_PRECONDITION"}],
    ["ownerShutdownDrainsInFlightResponseBeforeClosingConnection","c0dfba7292562aa6e42757c9286e5bb19a56abfdd51dfe893c5b36f7438fb47b",{"controlJoinMillis":9000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpAuthorizationIntegrationTests.java", "758d3d7dafbbdf74f05e295f06db75e749a4da6103c5519eeba831ecb45fa40b", [
    ["passesSafeBearerChallenge","4f92e4ce7a1cf1c5494a22b5dc5cea5417ab556d224ff609bf3a4b55cd4eee59",{"controlJoinMillis":5000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["corsResponseHeadsMatchIndependentGoldens","770eb979be12f832a69bfd64304e40458414c22a6d95fe929d6d924a05611377",{"controlJoinMillis":5000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpHandlerInterceptionPublicRuntimeTests.java", "462850f5ca7f76d7c71139e42fd6cc35862c1d07fd22679415ca0cc901daabda", [
    ["deadlinePreventsLatePublicHandlerEntry","4c10164d0541acc858576d2a834f82346a64d580b8d898107535754c097f2cae",{"controlJoinMillis":5000,"controlComposition":"REVIEWED_OVERLAP_OR_DUPLICATE"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpHttpContractGoldenProductionTests.java", "cb96d4ae300df956728633fc7726b82a34cc279b8b9052aaedd499157287c9c5", [
    ["requestPipelineFirstFailureWinnersMatchCompleteWireGoldens","4fc32d45264602deb08b4e09b5926cebdf6aebae34f8ad4e1f13dc01705701f5",{"controlJoinMillis":10000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["notificationPipelineAndPreflightMatchCompleteWireGoldens","5a18db5c72569f0e33c1db6cb382d6493f38d0c226699173846fc3348c98ff34",{"controlJoinMillis":10000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["overloadAndSseAuthoritiesMatchCompleteWireGoldens","8bb450280668ad74e00bad100768304c24310ad064a3256e150b0c4589405638",{"controlJoinMillis":35000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpLifecycleB3Tests.java", "853c9c9d7b7b6b2bfa50b364a8c009b2612064c7ae8f759d791866df505bac8b", [
    ["exactMcpGenerationOperationsRejectForeignTokensWithoutMutation","67aedfa580c9dd8c6514285b0dd58076b13327801868c12f4c88cd974052d148",{"controlJoinMillis":0,"controlComposition":"REVIEWED_NONBLOCKING_PRECONDITION"}],
    ["forceResponsiveHandlerIsInterruptedOnlyAfterTheGraceDeadline","52f03eb3d94266f6598738c86e9d91c33506d47dc71342533950eaf36cd495b7",{"controlJoinMillis":10000,"controlComposition":"REVIEWED_LIFECYCLE_CORE_DEDUPLICATION"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpProtocolProfileMetricsTests.java", "8462c2f0143d036fea585e8f000348ddbf6366a352ce0b57e4cef3ef0d816216", [
    ["unsupportedMissingMetadataRecordsUnsupportedVersionNotInvalidParams","081cf8b366b075700cc495e8d856bce3b2ae04e49e28c6f2f42ab8e778ccf906",{"controlJoinMillis":5000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/MetricsCollectorTests.java", "47ec98a8cd1cd5b4e5ed27e14f1e9c20065644588fb2d22d4653d613dc9808b0", [
    ["httpMetricsSnapshot_overNetwork","4cc77aea3d044fd9130367bcd75065f860204a5306f7736a88febd183dfdbd51",{"controlJoinMillis":2000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["sseMetricsSnapshot_overNetwork","87d1120ea6c5e33318796af562a528bc04b562ccbdd822786af0c0b5f83f39a7",{"controlJoinMillis":5000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpRequestObservationPublicRuntimeTests.java", "922ef79f048086d9f0dd11db275e67e29571338a92d6b037002fd5934772c142", [
    ["throwingObservationCallbacksKeepRawCarriersApplicationOwnedAndLogsRedacted","67ec3ec149dab30aa4b54f0a64f1f56ad4c40c69e9290addbd5c5a74c44ebc44",{"controlJoinMillis":15000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpErrorMappingGoldenProductionTests.java", "ae6d0bfb8126fd4c4f703c501b930ab04ce71f4827bf25229b9b52f4a71a06f5", [
    ["ordinaryMappingFamiliesMatchProductionListenerGoldens","791203cea6affc534885196b9c249a4b774257e6663d98c7ba8aca8f2028bdfb",{"controlJoinMillis":10000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["overloadMappingMatchesProductionListenerGolden","45624e4374586622754a17d77df7686142bbdf55ef21e838d51ba4c1e2f0df91",{"controlJoinMillis":35000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpHttpServerRequestScopedSseTests.java", "ee9b77c5b93c44597eca3fc49059bdbc549c44f0ea9ee93de51af0ff52645c69", [
    ["shutdown_closes_committed_stream_and_runtime_restarts_cleanly","1b56c480e7618c105d448e1c5f45e17d4967bac50ef9f70443d8ecdf442784b4",{"controlComposition":"REVIEWED_LIFECYCLE_CORE_DEDUPLICATION","controlJoinMillis":15000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpSubscriptionRuntimeBoundaryTests.java", "adb0dd2e2fecfe61e9acadeb7b76a88d1a2f5dd5c5e4ce1ab7ed86c457ca7eb8", [
    ["commonLifecycleAwaitRescansRegistrationAfterCloseAttemptCompletes","1b6c55192d0294ab2be799dc20bc6bada8002817bd219aa572ed0865dfde9c9a",{"controlledLifecycleCoreMillis":5000,"controlJoinMillis":15000,"controlComposition":"REVIEWED_LIFECYCLE_CORE_DEDUPLICATION"}],
    ["commonLifecycleForceRetriesAQuiesceRegistrationCloseFailure","ed74ab5cbf8185b3f1e3a30c80ead352af39ebb40dab22715b5489a8e9fffd61",{"controlJoinMillis":9000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["commonLifecycleForceInterruptsAnOwnedBlockingRegistrationClose","c6d71cd9a87bfd649cbcdfc18d9b0f2a1fc9c210a8822784af0435f77fdb3dff",{"controlJoinMillis":7000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["commonLifecycleForceCancelsTheExactRetryAttemptItCreates","a961e251865f9c945028240e564fb3aa899b4835b58ce5886c25410866553032",{"controlJoinMillis":9000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["commonLifecycleRejectsPostForceGracefulRegistrationRetry","534b3aba001d9f537a633a18d20a79eafccad32163e4268c16971a473b6c7048",{"controlJoinMillis":4000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["preReadinessFailurePublicationWaitsForLifecycleElectionLock","0e60adc809631bf6ff8b977d43e6057bea21e1763de4287218165dfab7c9ea4b",{"controlJoinMillis":20000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpSubscriptionPublicRuntimeTests.java", "5631c392ffe1a026e55205f316af31a68a8a96cd529280742d745160d4eec715", [
    ["configuredPerPrincipalCapRejectsWithoutDisturbingAndRecovers","d6263f95adf47bff9a2cdf9c133423469276c30e7c563ad7131261d758e23fdc",{"controlJoinMillis":5000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["sameIdSubscriptionsAreIsolatedAcrossAdmissionPartitionsAndCapRelease","14acf3fc1afc9550175bd0e711110179abc4ce9d1d3b31cd3f82e257cbfe530f",{"controlJoinMillis":5000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["clientDisconnectReleasesStateAndPublishesExactlyOnce","06776a37a9b16c9ffc411d2fcb2f9c16d72004d10a901f713247ed2f452c1b79",{"controlJoinMillis":5000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["keepAliveAcceptanceSharesStreamTransitionWithCloseObservation","216bdb4112831b0376162aa951693f5a855a96209fbcaad09cd542c04a10c64c",{"controlJoinMillis":15000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["configuredQueueContainsBackpressureAndReleasesTheFullCap","949a18ede9f3cf034ee4453e5aea45d67e77febee7266d2427f60d7d183695f1",{"controlJoinMillis":5000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpSubscriptionCatalogProjectionPublicRuntimeTests.java", "a2e78349550ea76ea79a55f47f56dfad2d415b3e1b03c5b0dada83854f79633d", [
    ["initialBaselinePrecedesAcknowledgmentAndProjectionFailureRetainsStream","464b817c0682880985baf06e62568c778f4c884ef74e7b10cf49c41c7822de3d",{"controlJoinMillis":20200,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["reconciliationDuringInitialProjectionRetriesAfterInterruptedCallback","7504cdfef4f81d616cdf96e04afed4902f29ef8c85f92cdd8ef6aab58a4f348c",{"controlJoinMillis":5000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["successfulReconciliationReprojectsWithReplacementApplicationContext","4af209036ed49e7c6cc6010bdd668a84dddeb73ea2a1bbc832b4c9da1b024d9e",{"controlJoinMillis":10000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["reconciliationCancelsAndFencesAnInFlightCatalogProjection","84ea4a1e46a4a3b0fc412258648f58434203e07d739d424e26d0a64d56f9f20f",{"controlJoinMillis":5100,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["timedOutCallbackRetainsProjectionOwnershipUntilPhysicalExit","555788cb0a13c2cb38c9a3be51f6e8e5385189bfe937c70c7e94e1d23a752f9c",{"controlJoinMillis":10200,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["streamCloseCancelsInFlightProjectionWithoutLateDelivery","3a2d3e1a56ce6c70e4649899ac7c3928f44e0bb1d84273a1509715990452484e",{"controlJoinMillis":5000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],

  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpSubscriptionAuthorizationPublicRuntimeTests.java", "1cf21fc9f03f4915e74b1bfadb8c5434ecba96ea41b318dceb3b1e4efb52a7db", [
    ["establishingReconciliationDiscardsStaleGrantBeforeAcknowledgment","069679462a362e7969b30b8ef7253eb3c134f93dd8893e307f12b93610dbdc8c",{"controlJoinMillis":45100,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
    ["activeReconciliationFencesDeliveryCoalescesAndUsesFreshContext","a98fce606ba4b7161f50677398d15ae9986d54e97ba022fd80b6c733188f2837",{"controlJoinMillis":35100,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
    ["cancellationResistantRenewalCannotExtendLeaseOrOverlapReplacement","24b81c1088ba4c595425e75a33904ed50fd055cbe9d01dada88f6323e66c0426",{"controlJoinMillis":12000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpSubscriptionAuthorizationSchedulingPublicRuntimeTests.java", "f63867626226c5a1510191292314156b4ae76615edb9fcc06a5ded2401edc813", [
    ["renewalReservedAfterReconciliationFenceUsesReconciliationSemantics","23279c34d9bb38c6cdac2100e9f4f0d30c741d7384d14a8ca9fcb0c3f7771a32",{"controlJoinMillis":50000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpSubscriptionAuthorizationTimeoutPublicRuntimeTests.java", "d004ff8496cde85027e8c7ccfb34edca2054b869aa27374e908d057068b33843", [
    ["queuedInitialAuthorizationTimesOutWithoutEntryRetryOrQuotaLeak","656c344aaea43b75e1d80454b7c87b0cf8d2be5862eac00fca203a007b86c46d",{"controlJoinMillis":15100,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
    ["activeRenewalTimeoutClosesExactlyOnceWithoutRetryOverlapOrQuotaLeak","301275c2ea32f35c2b7d82607660d9c9a083293fdbf0410112a637520fe2f5fe",{"controlJoinMillis":10450,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpSubscriptionCatalogOfferBoundaryTests.java", "e644e7871623ffabfc90922898ecfc1a9ed87e4cf69caff44994f50bb072b4b8", [
    ["synchronousCaptureLimitClosureDuringCatalogOfferTerminatesCleanly","3aa4c381a71bca83164a7104130fac4ba6214b5361e7c5069748191c3d156d93",{"controlJoinMillis":20100,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["offerCrossingProjectionDeadlineIsSuppressedUntilFreshProjection","9e49391d449e9ec8f09c4535a4b7573fa9081ec35f9b501ccb375f97c3b6afb4",{"controlJoinMillis":11300,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["reconciliationCannotFenceBetweenOfferReservationAndHandoff","09e007d935873428cdaeda0b27bc2a72081a0dfe5f1c21798dacda01adc70aa3",{"controlJoinMillis":35200,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
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
  ...reviewedScopeFile("src/test/java/com/soklet/McpHandlerMetricsObservabilityTests.java", "8d3f0f334bacf0ec22eadbcaee75c3762e860036a3b6eb87a894e53f469caace", [
    ["queuedDeadlineDequeuesWithoutExecutionAndRetainsActiveGauge","072447bdc3fac24aeb356e1707048174fa8010606d15ca4ef6dd5ac6e4986ce4",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":45000}],
    ["managedResidualShutdownDequeuesAndFreezesGaugeAcrossLateExit","4681101ac6e14f0dde692d5ba2d481450422cf0d7bd7e381c309949dce6ffea1",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":35000}],
    ["unexpectedTerminationDefersQueueCallbackAndFreezesTerminalGauge","4c0c33330fbd8c8c8d749894f8267d1a569d6fd5e75537c1cfd597c9f1bd6ba4",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":55000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpHandlerQueueDiagnosticsPublicRuntimeTests.java", "a070a11617affcb2eefda890748f30440f3afc7f50d5006f298865a34de9f271", [
    ["residualStopRetainsOneActiveAndDrainsQueueUntilLateExit","b87fc0c5be57b5735e3ffef9568f235a5ffad7df06ce578207a33b52ab894453",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":25000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpLifecycleB3Tests.java", "853c9c9d7b7b6b2bfa50b364a8c009b2612064c7ae8f759d791866df505bac8b", [
    ["noncooperativeHandlerClassifiesResidualAndRetainsItsGraphAndAddress","c090937c5cb6899b7251a3fce920523de2895ca2692b8f6b6893522c5656f9d4",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":20000}],
    ["unexpectedEventLoopFailureFencesBeforeProofAndRetainsAddress","e5a4fc15428bd0b3ab87b7430a0f2ebf6281a21282fe4905918703b64f5b6f52",{"controlComposition":"REVIEWED_LIFECYCLE_CORE_DEDUPLICATION","controlJoinMillis":10000}],
    ["oneShotOwnerCannotConsumeUnexpectedGenerationBeforeExactResultPublication","8ec7c5714e6b014e07678de524cef1b8a49784ca98bdd8f04dcb66d811c62c5b",{"controlComposition":"REVIEWED_LIFECYCLE_CORE_DEDUPLICATION","controlJoinMillis":20000}],
    ["simultaneousStartupFailuresShareTheElectedEventLoopPrimary","75e977d00a919430b55732b7fb0b60704c58172c1d8877860ebcc8a84bef44f5",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":20000}],
    ["synchronousStartupFailureWaitsForExactCauseElectionBeforeTermination","4ad1e0bb6e115c7e2f170dbd66213fa055498ada418e291025f5b3b73309ab56",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":25000}],
    ["eventLoopFailureBetweenRuntimeAndCommonReadinessPreservesExactCause","357e7603e01bd31e58af2e4e6c30ce99a3fbff91ce1e11efd29a541e593b19fd",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":25000}],
    ["deterministicNoProofMapsToMcpUnknownAndRetainsEvidence","1494be5b83b11e6915906fd580725bdaf02481f55eed026eeda110476477522a",{"controlComposition":"REVIEWED_LIFECYCLE_CORE_DEDUPLICATION","controlJoinMillis":0}],
    ["deterministicNoProofRetainsTheExactBoundEphemeralAddress","fc2eee11e106de18ad84f3cb6e40c4939f330d448e52be7c4a1181a649ff52fc",{"controlComposition":"REVIEWED_LIFECYCLE_CORE_DEDUPLICATION","controlJoinMillis":0}],
    ["blockedMcpQuiesceIsCancelledBeforeForceAndProof","e4c72353ed7574cf8b45b61502de771cbb513b77579bf3d6c79717287c5014b7",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":0}],
    ["shutdownIntentFencesAdmissionBeforeDeferredMcpQuiesce","689238678986fe2dd56cff28da44744f9c7bc748b73b8e05a4d8abd9cca21243",{"controlComposition":"REVIEWED_LIFECYCLE_CORE_DEDUPLICATION","controlJoinMillis":0}],
    ["idleSubscriptionClosesPromptlyWithServerStoppedAndNoForce","d16b3d7074e5c58a7d42cc6b290ed0dbc5147ad17b5288fc720440cd25355470",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":30000}],
    ["ownerNormalizesRetainedUnexpectedGenerationOnceBeforeRejectingRestart","2afc232071d7c006ad16f324b8abae2c0b5f5c4875b7dd64a0c0e71921c93b07",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":30000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpLocalizationAdversarialTests.java", "cca048bbd0dcefb7eec9324fbcd96e41309cb34c96550b6fba99c42d18299a63", [
    ["rejectedAndIrrelevantWorkNeverInvokesTheProvider","314ac4b16ec574dc021d47c15bf20ba61dc3e5d9a7285af060451a08a6d20977",{"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","controlJoinMillis":50000}],
    ["aUniqueTagFloodRetainsNoStateOrMetricSeries","4d4055d47a16c0edbc778145d05d23b4a1a7485bb31859c2f59d39f7ada9086d",{"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","controlJoinMillis":30000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpMetricsEventDeliveryPublicRuntimeTests.java", "58574faf8e196d18a93425c340ce748972e1c3a7d908fa5872d5f88998098bd6", [
    ["unexpectedTerminationOrdersNormalizedStopBeforeFreshOwnerStart","90cb70ed200f06d85e999d10083dc95a7bc34236ae7d0a23f9e76db79c1a6938",{"controlComposition":"REVIEWED_LIFECYCLE_CORE_DEDUPLICATION","controlJoinMillis":2000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpShutdownObservabilityTests.java", "aeb56738880c2edd7a659e63d7e2cdd2d33b34c97d29014c1f0bfb1d5a6ee977", [
    ["unexpectedListenerTerminationAndFreshOwnerHaveExactParity","658fb897e3bcf0c822984d9d201f5c79c95dee91bc0957419d653a984b2c9b14",{"controlComposition":"REVIEWED_LIFECYCLE_CORE_DEDUPLICATION","controlJoinMillis":17000}],
    ["ownerNormalizesUnexpectedGenerationExactlyOnceAfterAdapterWait","39e48c1d89a57026241e6d00641b34ff51e116108eb37e6fd35945c2a91973f7",{"controlComposition":"REVIEWED_LIFECYCLE_CORE_DEDUPLICATION","controlJoinMillis":12000}],
    ["rejectedUnexpectedRestartDoesNotDuplicateBeforeFreshOwner","f4e7d5392de0df5da2dd468969ae18d43b2188c81d87fa21afabdebd1d4ef9ef",{"controlComposition":"REVIEWED_LIFECYCLE_CORE_DEDUPLICATION","controlJoinMillis":17000}],
    ["residualStopAndLaterExitDoNotDuplicateLifecycleOrMetricsOutcome","7279d8846c1263409c86b2c53e4a2db929463300d7dacc6d523b35769f8f1718",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":30000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpSimulatorPublicRuntimeTests.java", "0e2be1c351c0bb667d0a419fe9c3f3d921215361e9c01b696499355d58c6ac6a", [
    ["noncooperativeSimulationCleanupIsBoundedAndPreservesSuppression","527e1495a7b27842684e2d0ee1ab4a5100de3dadc4ba9f807e78fd52d4fedac9",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":10000}],
    ["nonDrainingCaptureLimitDoesNotBlockUnrelatedSimulationOrCreateTransportFailure","cb7489745af384abfe74181d68ec47d64a44aa48ebb2e5dfa7a7435db86fa702",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":35000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SokletApplicationObservationTests.java", "d4b53de085208074bf3f60d65cbfa624ec11d012c7e8dd2bfc3b2855a42bcba4", [
    ["transportLogDuringAttachIsInlineNonqueuedAndTracked","ffcad197704a26871607ea10bbc70ca4545809e637918ce6e427bd4b955065a6",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":30000}],
    ["blockedTransitionCannotDelayRunnerCleanupOrTerminalReport","951c7c9c2ed19df7ca42583c10472f0424c9fb9d905b4ab4530956cccfc7e9f2",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":40000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SokletApplicationProcessTests.java", "85b493f9d67f53858a76316793df4c88ffdde37b9e0db8d487880f2cab0c8cc0", [
    ["concurrentHookEnterInterruptionAndExplicitShutdownShareOneAttempt","2b081d56a37034ad07a1b7560ba6bcbcd4486d9505cc0f9f7449d85e27aee126",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":0}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SokletDirectLifecycleRaceTests.java", "ee51affe849155a95f83f3799e63e53d01930eeb9f998a29e35866c555d35500", [
    ["lateBlockedAttachReturnIsInertAndCannotEscapeTerminalEvidence","9d92aee5dd6d35ba42fa72d36d9513cda7c8f0d5f76b668adfbe69f711bbfb52",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":4000}],
    ["installedAttachmentWithActiveWrapperRetainsTransportResidualEvidence","2c0a257151691a658d11c36565ef8ae5e2102fb720a13f5fb3689d466977c00a",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":5000}],
    ["resolverCancellationSentinelDoesNotBecomeStartupOrResultFailure","a84d176a9f5d4be343823fc7ac7808aff1698a0177e42da8ea2a0ce58b2b1a3c",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":8000}],
    ["shutdownAfterReadyLinearizationCannotRetroactivelyCancelStartup","d13d4d3aa30f2beacf90124c91a79bdbf949a966637839d5e4c12b11301d1968",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":2000}],
    ["sharedLazyResolverDeadlineRemainsTimedOutNotCallFailure","0e5f9066b692a85cb68564726f17b77fd190be466a6170545c1acff9dddc1513",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":15000}],
    ["externalShutdownWinsBeforeInducedStartupCallFailure","49929c1af09ea36d95cc046cb2cc095bf474cf62ea68e4bd8dc80a7c937aff26",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":20000}],
    ["startupCallFailureWinsBeforeLaterExternalShutdown","33388c7cf8170be6d52186a570045147b49cf79afcd9f9c99ae004a89441c931",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":15000}],
    ["startupCallFailureWinsBeforeLaterPeerTermination","bbc19ad542e94288d0a422779729765d811d6e00b4977de554c03dacd662c18c",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":15000}],
    ["earlierParticipantFailureBoundsBlockedLaterStartAndKeepsExactCause","6cfa5cb3699cb2181f389c932c41e4855b6799c47ec33b11831cea122f3a03ab",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":4000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SokletDirectLifecycleTests.java", "ad05772b0125b5bcf446e84bbd00badad74cdf2cbaef4f7c18467f91c57f6a85", [
    ["blockingFrameworkSetupIsBoundedByStartupAndShutdownBudgets","e15a71164f4d44935fdef10beb8d9a350688b4c17e6507931d50dbf9c149d9f8",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":5000}],
    ["blockingTransportStartIsBoundedAndCannotPublishLateReadiness","97736864a900e952a6688b8cd001d76a3d096ba5450b0400bd240e5a5d5ee8dc",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":5000}],
    ["admissionRemainsClosedUntilEveryConfiguredTransportHasStarted","50c0f28c0e07b4cdd17e26fa3c7d33e406ead223d08339c7e4b476b7590059c8",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":4000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SokletDirectMcpLifecycleTests.java", "d6834206996c37bccd7cac47015bc27234eb6cfe7e522f2661d0772ec315751b", [
    ["synchronousMcpStartupCleanupFailureRemainsBoundedSecondaryEvidence","a074616d21487ca515bbd6fe581b3122d2c280749cbbc0dfc66005bd59f7b1d9",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":0}],
    ["admittedMcpHandlerSelfStopPublishesIntentAndDrainsResponseWithoutSelfJoin","b70e6a0bddf1bf32f405a735822457f9d22a9d8a116f30da5e1cccc4149ea6bb",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":27000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SokletDirectStartClaimTruthTableTests.java", "55ac3deeb7cc5dba31d4d577274d0bda7ac804de86745204b5a29d961b80d487", [
    ["startRacingNewOriginShutdownWaitsForExactNotAttemptedResult","789870435f667be6794e3fdb2fe1cc6499c5e15f6c3c75212c29dfca7dab371c",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":6000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SokletDirectTerminalPublicationTests.java", "b24ad641b84b61b0ba009ab12e45a7e5fdec3df39299b2ff0b913a66e745351f", [
    ["blockedPreRegisteredContinuationCannotStrandPrivateOrPeerOwners","07868a752d00075bf0cf1e994a19e2540402b3ffc87dc6050ee8a5bb1d48de19",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":2000}],
    ["concurrentAndPostClosedShutdownCallsShareOneStageAndResult","e8276afc6b3e60b38ea2056d4a07066d94af2eadb4baace5c97711812deab8f7",{"controlComposition":"REVIEWED_CONCURRENT_MAX","controlJoinMillis":10000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SokletDirectWaitSemanticsTests.java", "d200f0ed18f1cd7532ea1f0141fa718c05e1d6ca0390896191ead5c0f7d8b242", [
    ["concurrentCloseCallsJoinOnceAndRestoreEntryInterrupt","9ff6d7a16fa1b0d95b8f3552f038fa68a4f83d6ea485edb49f0e7efc0895e36a",{"controlComposition":"REVIEWED_OVERLAP_OR_DUPLICATE","controlJoinMillis":2000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SokletProtectedLifecycleCompatibilityTests.java", "193c635104cc417f0ece565d20e560bd4a3d802180f8d219c0a46dad69924bff", [
    ["holdingProtectedLockProjectionCannotBlockShutdown","6e970b21afe596b0b7934c2d552f805929e5ab7286163baae728116b2e279b67",{"controlComposition":"REVIEWED_OVERLAP_OR_DUPLICATE","controlJoinMillis":8000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SokletDirectTerminationPrecedenceTests.java", "fa53b1ea0174a65ed9900203fe2f0f17f05f1dbc8518ca513201c1992c9e58e9", [
    ["ownerShutdownIntentWinsFormerGroupFanoutGap","da9453ddd92e3d10cfe8cb15476bf271efd399c14e41292a2edf1ac54b142ef9",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":8000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SokletMcpLifecycleTests.java", "e523be77c435a8f5a6cd508ced1632007941c07811df3f87c997b21c7dbb7593", [
    ["noncooperativeMcpHandlerFreezesOneResidualOutcomeAcrossLaterCalls","e9d031300b12b7359ee7b01aa72bc4196ef027fa33d95fac863cd248b00f6361",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":20000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SokletSimulatorIsolationTests.java", "e642e15cfe950e209fab840aeead6520b4dae72fa7be5e3017aa352dd2332851", [
    ["blockedFrameworkSetupUsesOneExactStartupAndRollbackSchedule","3e3b5ad4ea82946356b53bacd01456586c1061d238075018d12ce690f1e41dc1",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":15000}],
    ["concurrentConfigurationReuseLetsExactlyOneRunClaimIt","5579f354b0e00bb9359ba6b607e9e803ea670e7c723448103ae2ab25fd364b9e",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":25000}],
    ["liveMcpStartQuiescesBeforeCancellationAndCatchesUpToForce","37b14f522e8065b3983c1b55e79f9930e5b4756ed89c67534386cb02e9ee3896",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":15000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SseTests.java", "3ffc6f689adc1cd9a6ec3de32ae5736ea6566a5c43ba0ae471201e1247a4554c", [
    ["sse_handshakeHeaders_and_basicDelivery","0a55cf795fffd87a23434e25eea9e135674290a9ef35cf2d11a8877ac9c62762",{"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","controlJoinMillis":4000}],
    ["sse_largeEvent_isFullyWritten","a0a649122b75cdc51b945949d32a5b4e31cd31173ef9081eb706e9ecd0db016c",{"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","controlJoinMillis":4000}],
    ["clientInitializerOverflowOnRealSocketIsLoggedAndMetered","f5570828da7591fed84ed1c7d6b864e24bad960e6080e4b8a8d4ffb909d680b9",{"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","controlJoinMillis":9000}],
    ["sseRequestReadTimeoutWithoutRequestProgressClosesQuietly","8ac64becf79acc14b5bf7b0eccd8eddb9ef885a35036cb00266b3d568ca1412f",{"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","controlJoinMillis":7000}],
    ["ssePartialRequestReadTimeoutRecordsTransportFailure","6c78df4a8240d786b7b57bdaeb093bea03401a69cedc6843d749a9c04bcb11cd",{"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","controlJoinMillis":7000}],
    ["sse_stop_allowsIsStartedDuringShutdownWait","176eb363b3c17feade38f0fc28d1bf5a68f45afe747e88e4566660c923bf5920",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":6200}],
    ["sse_broadcastMany_underBackpressure_eitherDeliversOrCloses","e9cd6583a330d0de1b52e9041abd92dada8c4800ee26beb9a24741d8a9d527c0",{"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","controlJoinMillis":22000}],
    ["sse_backpressure_setsTerminationReason","8ee38236534cca283a5e27441a514eec1944c6e9f3da7ca0837881f1f53a3cb9",{"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","controlJoinMillis":43000}],
    ["sse_stopClosesConnection","14abf3bef233d701bf502976abcc787a16df7cb2b65d20a02f1f138a1d87de79",{"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","controlJoinMillis":16000}],
    ["sse_stop_setsTerminationReason_serverStop","ca9fd6ce8336878273cfab3f37effe222c749bda4a78bcd50e451fb8917cfbf9",{"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","controlJoinMillis":17000}],
    ["handshake_unknown_path_returns_404_and_closes","635e21a236dda068a21f457921880d1c52d0f2ec5b36c23b3f81a3bc986c0dd9",{"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","controlJoinMillis":9000}],
    ["handshake_rejects_transfer_encoding","8112464bae0ce7284db00176ec3df4761201b8b899ca636fdce4390c187cb6c1",{"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","controlJoinMillis":9000}],
    ["handshake_rejects_nonzero_content_length","8d9af9ef8fae874c723965ef0b8e86f6ebdbccc045145513d44fa0ef864f09f4",{"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","controlJoinMillis":9000}],
    ["handshake_rejects_missing_host","3db4da4142aa7e93b103c6a654bdc3713aa5034db64362faa22ea1c0ee705deb",{"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","controlJoinMillis":9000}],
    ["handshake_rejects_invalid_host","ce2e69f71064261f8b4ec632fba54a7a6a5ce10fa459b4fea66425fbec526c2e",{"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","controlJoinMillis":9000}],
    ["handshake_rejects_expect_header","38b33772ce234aa7508c5fcb7b69051e4f633053795ea3c6a4714203d7b4d719",{"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","controlJoinMillis":9000}],
    ["handshake_rejects_control_char_header_value","2144d3f1c18e96497ef081315f58fdc9c7f66647584f0c8745bd8e56af80a5b8",{"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","controlJoinMillis":9000}],
    ["handshake_times_out_returns_503_and_closes","65ea6624110291a50adea8b4838f87125df7c97e72f9317a57191c238ea77201",{"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","controlJoinMillis":9000}],
    ["handshake_read_times_out_returns_408_and_closes","c74dbc314b3679d21fcae9e170c2265ac55c70d4cfdb8c79efe29d9bd57cfe69",{"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","controlJoinMillis":9000}],
    ["timeoutInterruptOwnsFailureClassificationAndDoesNotMeterTaskError","7c99e78139af90f3949c7b61afb383019cad8fe930b972823b4f5b7638e66989",{"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","controlJoinMillis":9000}],
    ["stopCannotPublishAnEmptyGenerationWhileStartInstallsSseResources","bc651018d3b7b036b1776e671b0189238822f82445718efeb3fb919c63c7b2aa",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":7100}],
    ["startRejectsRunningSseGenerationWhileItsStopIsInProgress","9303908c0e9f97fd45adc11e233d34ed035fef47e18574fd752838d11cafbbd7",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":4000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpConditionalCapabilityProxyRuntimeTests.java", "e10c064771905ffeb0e5b618d32b555625baaa04cf9d473b4b32795694a7c05f", [
    ["proxyIdleExpiryCancelsSilentHoldAndSupportedControlForwardsSse","3db618aa70c5ce74c6f94322b8312733b10b697d87b9dfd1564d032a0d9fa4ed",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":55000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpHttpServerApplicationExecutionTests.java", "83b57f7cfc4cbddd08fe335145c0275cdbba92f804ab989311b786408cfc2c63", [
    ["queued_absolute_deadline_gets_the_exact_capacity_response_without_dispatch","1f178011f53e88ce3ac12d46751daf0ef8a3033170a287bbdeada0109ba67af8",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":25010}],
    ["request_deadline_is_captured_before_protocol_admission_work","5d32c73b556dd345643c753b43f2531d4109866e1a96021c4d5b81f253d30dac",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":25010}],
    ["protocol_deadline_comparison_survives_monotonic_clock_wraparound","0cc1f79ed3418fb6110a2bc82f286f88c081549af1ac531433b96a68d4a6c0c7",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":15000}],
    ["deadline_during_cors_authorization_prevents_later_admission","954266bce7a0cdf67bd9286d4d184af3973d11533e12be691f13e6aafb9b4abc",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":25005}],
    ["protocol_processor_backlog_expires_and_releases_canceled_queue_capacity","379f4684bbb0fc89f4375d594b5c851b396b01750c24a8ad2f843ac3b2e6a75e",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":30010}],
    ["framework_discovery_deadline_releases_identified_exchange_accounting","2a6e8c28000629fc49630cfa8a75fa25b822a4165061fdf2aae15116611f65ea",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":25005}],
    ["active_client_disconnect_interrupts_but_retains_the_slot_until_handler_exit","8fc72c1dfce652bbb8b5a1d43d2bbd3fedfe477a9b0ece1e3d55afc9346407ae",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":25015}],
    ["lifecycle_grace_preserves_active_handler_then_force_interrupts_without_promoting_queued_work","b2298faf5919d0ddfb071e8a493c18b3b8d8fafa721f0a1188fba621d341ff90",{"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","controlJoinMillis":20005}],
    ["shutdown_reports_residual_application_work_and_blocks_restart_until_exit","0168dce25a9f381c15c6b919637388cf236960743a70f23ea37fe954c058c8af",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":15255}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpHttpServerObservationTerminalRaceTests.java", "5cd0b3916b44da61da4de6adee53d13eedcf0b0315cce74c4ce44c50c279954b", [
    ["lifecycleLeaseOutlivesApplicationExchangeUntilBodyCompletion","837ed46fa8bf1ba634cc7da6160515d53e13bc8526be6f169963daea3f956191",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":15000}],
    ["protocol_completion_cannot_preempt_early_stream_terminal_owner","a289f1a175900c19e0ec4df5c6ed7c1aa198cdfaa8d59449830129d6f819358e",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":15000}],
    ["written_sse_terminal_beats_concurrent_client_cancel_exactly_once","e7a78a9f1c35713913c7a0cf40e8a7426774d38ecf2eb3df644b7cb8016f49ae",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":55000}],
    ["precommit_mapped_error_beats_late_client_cancel_exactly_once","5ae4210562fc73a20cd22802f9540655a47aff7a4f1112f3c86ee209efe423d3",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":10000}],
    ["written_streamed_error_terminal_beats_concurrent_client_cancel_exactly_once","fa2d0a6e55bb5028f2c8bf15d121e6e1a503252d302939c72c3a1f1d7b3deaff",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":55000}],
    ["client_cancel_beats_unreserved_streamed_error_and_discards_its_metric","3ae3542e1c26de67e77f97293b73e1490fad92aeaa3adae1e979bb440603e838",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":20000}],
    ["application_encoding_fallback_reports_actual_internal_error","53649fc4bfba7a31b8ea69696084093ac3a01eea3c00316bbae3bfc1dcc82cf0",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":10000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpHttpServerPolicyPipelineTests.java", "de9d1b91795719a4a49d5f77cdfc5ee6a3e54f5317e67075399909b6f6df22de", [
    ["policy_null_exception_reserved_code_and_unsafe_header_fail_closed","998e3a54b55f4ff58e1b75ac8d31c00e649a2d93dc73abaddc6e31ae4cad7893",{"controlComposition":"REVIEWED_DYNAMIC_NODE_MAX","controlJoinMillis":10000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpMultiRoundTripTerminationRaceTests.java", "b2ffd548f7d0056535f64de26d09e33a5e25cfdaed7494ab65b9b2d2f4483bb3", [
    ["blockedCustomProtectorOpenMakesShutdownResidualUntilProtocolWorkExits","4e2940aadf6b81531aaf2c9fb67f63df03035d28ebd927256a287f35d629bdf2",{"controlComposition":"REVIEWED_OVERLAP_OR_DUPLICATE","controlJoinMillis":10015}],
    ["blockedCustomProtectorOpenDiscardsLateResultAfterDeadlineOrDisconnect","9fe0428fbf8a051f8856858b80b43e838880af781f3cf347ae86a50a84792910",{"controlComposition":"REVIEWED_DYNAMIC_NODE_MAX","controlJoinMillis":20020}],
    ["blockedSealCannotPublishLateInputRequiredAndReleasesExactlyOnce","9fa9b58c00aca22633217bf700bf7e481d25fd151076c03d2621399daecb4529",{"controlComposition":"REVIEWED_DYNAMIC_NODE_MAX","controlJoinMillis":15020}],
    ["sameAuthenticatedStateCanBranchWhileOneFreshIdTerminates","e2658db8bc19db08af218c4aa7408c71c66d309caa4d4edfe61b30847f57274c",{"controlComposition":"REVIEWED_DYNAMIC_NODE_MAX","controlJoinMillis":30025}],
    ["conditionalCapabilityHoldTerminatesWithoutProgressOrLateResult","e2a6c3af0f11bbd8c32e537b0c7209aebfeb71932f14c3f78dcf445f1d2d9bd1",{"controlComposition":"REVIEWED_DYNAMIC_NODE_MAX","controlJoinMillis":15015}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpProgressPublicRuntimeTests.java", "5cc0cdb29fe7d7c99f47343cbb8174a6edecaf4927d923481b5ca6a8267cc097", [
    ["progressEnqueueWinsBeforeMappedErrorTerminal","bc346e059df77c8ec558fb5ff75cc18dfeb0cf6f8a81fdbb59fba6cda0552913",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":35000}],
    ["mappedErrorTerminalWinsAfterProgressEligibility","ac973dea8b26a499defa252028530323c1dd22286b3bf3a2463ffb99bcf47207",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":35000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpSimulationLifecyclePhaseTests.java", "bd9b5e090499791334383913ca7c6f2c1dc9b5f26b907e0ad7f29976b23adff0", [
    ["bridge_quiesce_is_idempotent_fences_starts_and_releases_proof","fdf054d18525497fbd18207c4d56949875b199a895d53240cfb524faa791fcde",{"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","controlJoinMillis":5000}],
    ["graceful_simulation_drain_does_not_interrupt_admitted_handler","9db5cf0b123e6cd36972457a295c50089c42afbb008bd50721dc3402bb03361c",{"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","controlJoinMillis":20000}],
    ["graceful_simulation_drain_preserves_committed_progress_stream","321babdc212373b7cf6d89bebfc773a8981d7959d9018b6610bb7371525aadcd",{"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","controlJoinMillis":30000}],
    ["force_interrupts_admitted_handler_and_reaches_complete_barrier","1d6490f610fcb07fee99234e2a90ed50775f8d3e711f8a36236c6916ac2212df",{"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","controlJoinMillis":15000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpStreamSubscriptionDiagnosticsPublicRuntimeTests.java", "d0478c183dd3553f64862149d6d38dd4b55f0fb877d60e84f5cbed8adf423e7e", [
    ["residualHandlerStopPublishesZeroStreamsBeforeLateHandlerExit","6b9520765cc0596b7e5a71cdccbf10b0ea43762fa8df54345e6da39919efe650",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":20000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/BuiltInTransportLifecycleAdapterTests.java", "15f29083d2dfdf367f98d9ef7fe36d0e68ca3c87a25be3754707b37c13371258", [
    ["delegatedRuntimeDefersOverlappingShutdownAndCatchesUpAtStrongestPhase","ecddaee114a6401a5946400f867ddf2a76bc67d7f061390ec5c147b75c9a3235",{"controlJoinMillis":3000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
    ["delegatedRuntimeSerializesForcedUpgradeDuringStartupCatchUp","83ce2088246f1dcb4f1f5a01c268683832d59f3ebe7ed36ef17385b5a0e24da0",{"controlJoinMillis":4000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
    ["delegatedRuntimePreservesStartupFailureWhenCatchUpShutdownAlsoFails","8a63bce72eb61c1b38c94ce3449a8838e6e9760417979b79dbf55a70500d12af",{"controlJoinMillis":3000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpTaskCancelationLifecycleTests.java", "15c32d399c0fe3257494e3f744d1f6dc309f9291e8f9108dc929945d54f39f0e", [
    ["requestCancelationNotificationIsAcceptedAndIgnored","70e7bad65a3911d791f508adf066a9a7c86ab57b2903d90536ef840c4ee86091",{"controlJoinMillis":25000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpTasksPublicRuntimeTests.java", "8691eb5e7636332365463dd3df292b74954f783a524ab850278333c8e7000944", [
    ["admittedTaskRequestsPublishExactLifecycleAndMetrics","7982973fa697f99b3476ce262e35fc687576823ced9450b06f85681086485df1",{"controlJoinMillis":10000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpTasksSimulatorPublicRuntimeTests.java", "dbcdc976a1ab608100bffa0550586ac1763a106deb32b36f55125f87f68800f4", [
    ["admittedTaskRequestsPublishExactLifecycleAndMetricsOffNetwork","2ba5f54f8b9f241754c3b6c608d86ab569ab5a556dac6e1dc7526cc00ac040a6",{"controlJoinMillis":80000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
], 'lifecycle control topology');

// Streaming 4.0 source-specific review.
// Small HTTP helper allowances include concrete connection and socket-idle
// controls. Socket idle limits are per-read, not whole-response deadlines;
// JUnit remains the independent guard for arbitrary network workload stalls. Exact formulas and pre-rename source
// identities are retained in docs/streaming-api-evidence/milestone-6b-2026-09-22/.
const REVIEWED_STREAMING_SCOPE_OVERRIDES = checkedReviewMap([
  ...reviewedScopeFile("src/test/java/com/soklet/SimulatorPublisherCancelationTests.java", "0358b4ba930867f0be3c047ea63a021cdaab74dc9417994c8c10df47e20d54a4", [
    ["lateSubscriptionAfterScopeCancelationReceivesNoDemand","80b6975499914bd1e697de3666a5591600bdea54115415969b80632f01dbf18c",{"controlJoinMillis":12000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["limitCancelationAndProducerFinalizationShareOnePhysicalCancelAttempt","169ee4b83ff628fae72285f59823878f38c7afb51864e9b4007dbe897a91c913",{"controlJoinMillis":9000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SimulatorPublisherLifecycleTests.java", "4ad499eaef4176ea900d507a6568e9ac98f161d228cdd6fd95fd59765a7e83d9", [
    ["late_blocked_cancel_retains_capacity_after_the_request_producer_exits","94585ee7155f83c2eaf15d8abd8fa572f8c88f2a2d717f3f5ebb2fa764f82567",{"controlJoinMillis":33000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":100,"forcedShutdownMillis":100},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1}}],
    ["a_throwing_late_cancel_is_diagnosed_without_replacing_the_cancelation_reason","5da97c6e7797a9dfb0b9b67b7f8761a6015e1930a5dddbae45f02f4b6807158b",{"controlJoinMillis":30000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":100,"forcedShutdownMillis":100},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1}}],
    ["a_missing_subscription_is_reported_as_publisher_work_at_bounded_shutdown","fa90e1b75f16330d9e7652dde7e759f3e79b79cc45b44fb74466d489bc31de0a",{"controlJoinMillis":24030,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SimulatorSseInitializerTests.java", "33f8ccd9aa6d9f70b1ee09e23a60e857275d43773fc80e43d495ab3e961b126f", [
    ["helper_thread_may_queue_during_initializer_but_handle_closes_at_return","02040c0f08ec136bf3eb9bd2cbfed2aa989ab53da2b727e2811a8d4e72117329",{"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":2000},"controlJoinMillis":4000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["close_without_consumers_releases_connection_and_preserves_handshake_metadata","f9fb1512697bd5acbad2b3ebe587a6d6b9bf27744580414652a88cf3d283b423",{"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":2000}}],
    ["initializer_and_broadcast_payloads_are_ordered_and_released_after_close","03361cc1641cfb498f350e4aaac2c7fb0487df2d330c5c9bef4b555020845f1f",{"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":2000}}],
    ["buffered_delivery_preserves_unicast_and_broadcast_error_handlers","86ad7c409fb58236a79d4ed24c0a4abe5ee3223f10a42e775367ef7626dcf4df",{"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":2000}}],
    ["checked_initializer_failure_exposes_no_connection","c5ae846cad9f07fc83b974069e32ed0cb58964a90b731299dcb407354f64ea70",{"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":2000}}],
    ["blocked_broadcast_consumer_does_not_block_close_but_remains_accounted","a0a5df70658a7453778fbb720d2eba0c1b49bc499be98fb1c98a1071f9a0bb99",{"controlJoinMillis":20000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":2000}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SimulatorStreamingOwnershipSupervisionTests.java", "84d95dd659162010cf3ece31db4b9faf26f42a89b3d1b57f0b41b0ca9f3af0ad", [
    ["expired_normal_close_keeps_slot_and_producer_thread_until_physical_exit","7acbc9b0720adc854d82f43359ebb76a2038b9aa4ddb4d8598ad41e17dc7043f",{"controlJoinMillis":20000,"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":2000},"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["scoped_shutdown_reports_unfinished_http_cleanup_and_later_retires_it","34880da4ee7de12daf45810529e67e035f8db049a682aafa961f3130fa932a45",{"controlJoinMillis":25000,"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":100,"forcedShutdownMillis":100},"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SimulatorStreamingServerSettingsTests.java", "19bb37a3233f12bc892be9fbc72dfe566c0a015ad3d810183d4bde97e0b59efe", [
    ["inherited_capacity_and_cleanup_timeout_keep_the_slot_until_owned_close_exits","97fb0afb9abfb09bd02a50d07d11b4f2743d395722f5f63b1f6108376e20c26e",{"controlJoinMillis":21000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["inherited_capacity_isolates_all_observers_from_callback_concurrency","69aa310c2b3f3006e8f71f8eddf457e9e34d1462dfc8f8608de31fc93ff1dc11",{"controlJoinMillis":27000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SseLifecycleAdmissionTests.java", "763768af0d54d1f20ab164bda6d35afe3eb3962d75bea4de03a8b1391bc32695", [
    ["defaultConnectionAdmissionSaturatesAt256AndRecoversAfterDisconnect","9bf2fcb1e4a99f02261b182a7e82ef1e8869a23c4282356d36d1b3ead4fb3902",{"controlJoinMillis":20000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":2000}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SseInitializerRuntimeTests.java", "1c5ce8703c1e630012e4105dce00f5af6fff0200b7470eed9067592600a798be", [
    ["synchronousInitializerQueuesCatchupBeforeBroadcast","30d00a974c211c9998a6372a8baf9d63abd9061d485d0742145eeb776c3db3ec",{"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":100,"forcedShutdownMillis":100},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":5000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["checkedInitializerFailureTerminatesWithoutActivation","3fb79a5d9a948f6822707c9ffd807f3087f0333dfbf16bdbaf09f21bf6a0b6b1",{"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":100,"forcedShutdownMillis":100}}],
    ["caughtInitializerOverflowStillTerminatesWithoutActivation","271d16ba6f98d1a905657a9f932603127e3726a56c090a831fc52b672bcb555f",{"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":100,"forcedShutdownMillis":100}}],
    ["lifecycleCapacityRejectsBeforeAcceptedHeadersAndInitializer","17f2919033a25fbcf78c3fa108d54079d2a47c7e8d7579c2d853c0c91d37814d",{"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":100,"forcedShutdownMillis":100},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":7000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["shutdownAccountsForBlockedInitializerUntilPhysicalExit","eb39df45a17ab5ef96ec7a1ecb08f588ffac60e5690118f605621e6b987a2c7f",{"controlJoinMillis":21000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":100,"forcedShutdownMillis":100}}],
    ["rejectedConnectionExecutorReleasesAdmission","da7c2e0928a2dfc6528bfbcacf7043edc07b92401e581aba14c77e8b998428e9",{"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":100,"forcedShutdownMillis":100}}],
    ["queuedConnectionEnvelopeIsRetiredOnlyWhenShutdownRemovesIt","2ca6ad81f4ee910efd256a95be92a1f5c5bae9eef78aeda04ad691f5c0040c5a",{"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":100,"forcedShutdownMillis":100}}],
    ["passiveClientDisconnectReleasesAdmissionOnHeartbeat","fdea1325022b6843e4668b6c5b94f0e30cd94a38851e41e7d5fa6fd5b791adbc",{"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":100,"forcedShutdownMillis":100},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":8000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SseTests.java", "3ffc6f689adc1cd9a6ec3de32ae5736ea6566a5c43ba0ae471201e1247a4554c", [
    ["sseStopTerminatesConnectionWithoutRequiringQueuedEventDelivery","2874b315ae6a1a4b7c1c89299380427fbbd289b4646ef227b9902cd9a2b952dc",{"controlJoinMillis":24000,"phasePolicy":{"startupMillis":3000,"startupCancellationMillis":1000,"gracefulShutdownMillis":5000,"forcedShutdownMillis":0},"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/StreamingLifecycleTests.java", "108c4bd74abe853b35ee2c12c3334aa51e93cb01d49de71a6cca688cccb72727", [
    ["blockedCancelationCallbackCannotStallAnotherStreamsTimeout","707fa2009b6f4bb6cb1854be329ad2564becba8903a8ef2b5dae0e46a481928f",{"controlJoinMillis":15000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":200,"forcedShutdownMillis":200}}],
    ["blockedSourceFinalizerDoesNotHoldTransportOrShutdownPastDeadline","519af467226cd421d0a8882b38b9d8da2c5110dc7427a67d78d777ead177c66a",{"controlJoinMillis":27000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":200,"forcedShutdownMillis":200}}],
    ["exhaustedLifecycleCapacityRejectsBeforeHeadersWithoutInvokingBodies","2fc8acac5834fd13c2205c697393e4d78041a0a8ca68e832323f29df6412af84",{"controlJoinMillis":9000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":200,"forcedShutdownMillis":200}}],
    ["inlineCustomExecutorRejectsWithoutEnteringApplicationOrStallingHttp","07c27ff74cc05a109934af23b6e9e631c6373e0be20d7cae2fa11e99f75dcef9",{"controlJoinMillis":6000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":200,"forcedShutdownMillis":200}}],
    ["saturatedCustomProducerExecutorRejectsBeforeStreamingHeaders","dfd5ac9d4b1a3755587b1eae17e1d5bfd35e1ac93eb613677450f26fb7843215",{"controlJoinMillis":9000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":200,"forcedShutdownMillis":200}}],
    ["retainedCleanupPreventsRestartAndClearsOnlyAfterPhysicalExit","400214c12575fafaab4cfbe76d97ffc5395853212c656f29ff9b3e358389eda7",{"controlJoinMillis":17000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":200,"forcedShutdownMillis":200}}],
    ["gracefulShutdownAllowsAdmittedWriterToRenewIdleTimeoutAndComplete","dbc2d7c1ed921d438663d13c5973ba8fc3782abb4100b8d28967f1d79154eabc",{"controlJoinMillis":9000,"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":3000,"forcedShutdownMillis":1000},"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/StreamingOutputViewRuntimeTests.java", "190b5fede322e2f6cf4b14d7bbb39cc03bbbbd48b028f406a8b5dc7bd138445b", [
    ["closingOneViewFlushesItAndLeavesOtherOutputUsable","924a042fe4b376e2523e659e5b123eb9beb2862e895e891201d1478a9f425218",{"controlJoinMillis":8000,"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["failureAfterWritingFinalizationBytesCannotReportSuccess","b982fa5ba33c571904769ddd0ca67e1436353b05ae2b52172bb6c8c52a74513a",{"controlJoinMillis":8000,"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["invalidSlicesDoNotPoisonOutputAndEmptySlicesAreAccepted","089728c6b1aa8a70dad1ddaea8eb56c2d855109117233ff16d83facfb54dbfa5",{"controlJoinMillis":8000,"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["mixedOutputKeepsOrderAndCopiesCallerBuffersBeforeReturning","77592c2347e5579a43630321981a0b7c437b96e5bafc95137fdd35151c25aebb",{"controlJoinMillis":8000,"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["ownedUtf8WriterFlushesAndEmitsBufferedTextDuringFinalization","bab937720039e78b3f1bb4f622a2d0fe4ba447485b65a8af076e105347d5cd13",{"controlJoinMillis":8000,"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["ownedZipFinalizationEmitsAReadableCentralDirectory","da5fbe788ea151a7cd87b913834d3081a121f0ab253fe6d3d1fa874860a94d5a",{"controlJoinMillis":8000,"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["viewAndNativeLifetimeChecksPreserveTheResponse","acf3df8de238e85a3f8cea10d9651148d40bbc345dedb767f57f92e15312a175",{"controlJoinMillis":15000,"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/StreamingResourceOwnershipRuntimeTests.java", "436cf5f2b7adc774a81752926faa7321c5d32825031429e312c2c5cce8df7d4d", [
    ["cleanupOnlyFailureCannotProduceSuccessfulCompletion","75ba9b1186aec914662968f237db777ffbf0372fb4b9887b544ea82000d2bc5c",{"controlJoinMillis":8000,"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["duplicateActiveOwnershipIsRejectedWithoutDoubleClose","58a30e89069b819bb7aa7bb11a531b9557efc766da26dcf1eeea8310e3ccba3c",{"controlJoinMillis":8000,"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["headSuppressesWriterFactoriesConsumersAndCallbacks","c6e98b0876593a122ee3f722e827898d7ce96b30d7147bc0998ebe347ec60e42",{"controlJoinMillis":5000,"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["nestedUsingClosesItsChildrenBeforeOuterAndRootLifetimesEnd","06f234e19d07e20af721092fa6b2753efa19def3871a6d31dae67ff4cb0a2668",{"controlJoinMillis":8000,"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["ownerChecksAndClosedScopeChecksRunBeforeAcquisition","88f796ea844036ea606713e2efd5a1332c7a233eb2efbe11ba4131c05bd0d6e6",{"controlJoinMillis":12000,"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["producerFailureRemainsPrimaryWhenCleanupAlsoFails","785cc96aa16643b111c8159281bfa40d1fafaf294a2adbff0fac563feac13469",{"controlJoinMillis":8000,"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["rootCleanupRunsInReverseOrderOnOwnerAndCanWriteAfterWriterReturns","afd4e0628db8fdab9855d53e02a85925f02c3a4c9eceeafa068d5411901e57e4",{"controlJoinMillis":8000,"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/StreamingServerSettingsTests.java", "041b17ac3202d901a6b0fd4cae34e0df1a2b17007dd4844c0d0cd1806e6004aa", [
    ["publicCleanupTimeoutEndsDeliveryWithoutReleasingABlockedFinalizer","51c0fb7e1c8ef5b5066e39898031a0b9734edd917ab64c8931393bbadda586e3",{"controlJoinMillis":9000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
    ["lifecycleCapacityIsolatesAdmittedObserversAndRetainsExpiredWork","cbc47ea9000a64ccc418a372c32add25f773a74166f8bd8011d9a0aa2dd94c43",{"controlJoinMillis":12000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/StreamingSourceFactoryRuntimeTests.java", "72783302049118af5f731dbaa6b7d6aedbdeb0241bfcf2824d710326e7851f4f", [
    ["checkedAcquisitionFailuresPreserveTheirCause","f0067cedd1fb014777e15d68fcc373d43ea33a6883ad7c3354831e369d0536c5",{"controlJoinMillis":18060,"generation":{"count":4,"mode":"SEQUENTIAL","complete":4,"prior":3,"incomplete":1},"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["checkedSourcesAreLazyAndReopenedForEachExecution","704f48192a274117ac97554f624510a2d711ca10322f63b2f378eba7eee1de0d",{"controlJoinMillis":18060,"generation":{"count":4,"mode":"SEQUENTIAL","complete":4,"prior":3,"incomplete":1},"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["headDoesNotInvokeCheckedFactory","031539ead8407a0e16579d1f04b79a311ad4eb84857fe1f5d0decead9a68060f",{"controlJoinMillis":18060,"generation":{"count":4,"mode":"SEQUENTIAL","complete":4,"prior":3,"incomplete":1},"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["nullSourceIsAProducerFailureInBothRuntimes","0af5c9c436091f9afb12f4c06fdb351c2042efd8466426b21bc2db4179c16395",{"controlJoinMillis":18060,"generation":{"count":4,"mode":"SEQUENTIAL","complete":4,"prior":3,"incomplete":1},"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpHttpServerObservationTerminalRaceTests.java", "5cd0b3916b44da61da4de6adee53d13eedcf0b0315cce74c4ce44c50c279954b", [
    ["cleanup_timeout_finishes_request_as_internal_error_without_a_framework_cause","48dc561c3cda04daa4d664b6c1e21f6587acee3e312049269b68992b0655ca4d",{"controlJoinMillis":15000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/microhttp/StreamingOutputInterruptionTests.java", "b9586eb14f4d7942804e8d80fb14ab6bfe1d35942525b281cb9af24de7fd1d37", [
    ["viewBulkInterruptionReportsOnlyItsAcceptedPrefixAndRemainsTerminalWhenCaught","71aa33180665adcd5ab719f0efbe5a5eccebd31fab3ff56493433f79dd72690e",{"controlJoinMillis":15000,"controlledLifecycleCoreMillis":0}],
    ["nativeByteBufferPositionLimitAndMarkSurvivePartialInterruption","a25ceabd0da664f376460c0c3f68f0978b350b726a16aefef674ef3862e3457b",{"controlJoinMillis":45000,"generation":{"count":3,"mode":"SEQUENTIAL","complete":3,"prior":2,"incomplete":1},"controlledLifecycleCoreMillis":0}],
    ["anElectedTimeoutOrDisconnectWinsOverInterruptionTranslation","410339dde1617d36439fdbb1237d2c2277224117e33eac62e90c6e79bb47e20a",{"controlJoinMillis":30000,"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlledLifecycleCoreMillis":0,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["interruptionDrainingOlderStagingReportsZeroForTheNewBulkCall","3d80d939e4e1255e861188e6efb7a7897369d6cdb3ba037f186fe87327af9d0c",{"controlJoinMillis":18000,"controlledLifecycleCoreMillis":0,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["interruptedFlushAndCloseReportZeroAndFailedCloseIsStillIdempotent","3ff35b89c3fb6535b9a77755b045153c6b0bf3a0bc938caf77f653a483750db7",{"controlJoinMillis":36000,"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlledLifecycleCoreMillis":0,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/microhttp/StreamingOwnershipSupervisionTests.java", "7a459664530463101c7a47594102e48da64884df5c17e83e0b9d63f9b60f2e44", [
    ["blockedOwnCloseExpiresWithoutMovingDuplicatingOrRetiringIt","db71273efe1b8a383ab9d8f421ad2f3b44066b7b588e6d62574a061eaf1821f5",{"controlJoinMillis":15000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlledLifecycleCoreMillis":0}],
    ["canceledAcquisitionDisposesLateResultBeforeReturningItToWriter","8bc76d73acc3fc6b162ce1d6c9473c3c457517a91bbe3a29b589df006acbce22",{"controlJoinMillis":15000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlledLifecycleCoreMillis":0}],
    ["caughtInterruptedQueueWriteRemainsAnApplicationCanceledResponse","b46eca55f82595a68ac1eef456c0c04fbf2ddb72fddd94e0ed5160c22c2caebc",{"controlJoinMillis":15000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","controlledLifecycleCoreMillis":0}],
    ["coordinatedCloseAsAbortRunsOnceWhileProducerCleanupWaits","46156e830dbe60f27fb6ce97e4bcc2d994717f8c02e5cb7c5a01ffdf8c4cc52f",{"controlJoinMillis":15000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlledLifecycleCoreMillis":0}],
    ["lexicalFailureAfterCancelationRemainsAvailableAsDiagnosticEvidence","3d9dba8ca11f55d147517020d9f91473211e16c09cdaf969422abc5fafcae9b8",{"controlJoinMillis":15000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlledLifecycleCoreMillis":0}],
    ["separateAbortCompletesBeforeFinalCloseOnProducer","86e82f3fe55d7ef0f6ea656f88928202811d2f541dfccf57f9e7eecd5ecedd53",{"controlJoinMillis":15000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlledLifecycleCoreMillis":0}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/microhttp/StreamingPublisherLifecycleTests.java", "5f6af5fcf18186faff05689c62e3234eadd43d7313f07ffd2a049849cb37f27a", [
    ["duplicateOriginalSubscriptionFailsWithoutDoubleCancelOrSuccessfulEof","d8c7e510a4032543340eebae806ac6c6f2d41f8024d8cea6ba6e7960efce7939",{"controlJoinMillis":15000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","controlledLifecycleCoreMillis":0}],
    ["synchronousTerminalStartsCleanupBeforeSubscribePhysicallyReturns","114e65f391f6140aba18205daf30aa3158f18247c33b7bab75268f11d8f264c2",{"controlJoinMillis":18000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlledLifecycleCoreMillis":0}],
    ["terminalBeforeSubscriptionFailsInsteadOfCompletingSuccessfully","2bfb4eaaf52a853c417f2f982398e23f61a409c9954c702aeda95de7a125a6de",{"controlJoinMillis":15000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","controlledLifecycleCoreMillis":0}],
    ["throwingSubscribeBeforeAcquisitionRetiresAndRejectsLaterProtocolViolation","7ce433380164140f1e46c69cf8273053bb19017ef8c4c06910144ceaeb01ff9b",{"controlJoinMillis":15000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","controlledLifecycleCoreMillis":0}],
  ]),
  // The producer's release.await() runs concurrently with its test driver. The
  // driver releases it before checking completion (and in finally); it is not
  // another sequential control wait. The only driver latch has a three-second
  // limit. Fixture shutdown belongs to the already counted Soklet generation.
  ...reviewedScopeFile("src/test/java/com/soklet/HttpStreamingPassiveDisconnectTests.java", "568a341d6bc6d646255fa36c1a4464a2b9b3db7c4316cc4b57d460a1793587b7", [
    ["ordinaryHttpStreamRetainsPipelinedRequestForDispatchAfterCompletion","5d795c34296849edebce6b8c7b1209b89bd20e8911946d7ba3690c609a4fb8d7",{"controlJoinMillis":3000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
  ]),
], 'streaming scope');

// Legacy expansion closure review. These are test-driver allowances, not
// production protocol limits. Catalog and Completion requests share one
// 60-second deadline per test. Raw session reads/connects share the same budget;
// each framing operation also has its own five-second deadline. Simulator
// notification polling uses one five-second deadline across all keepalives.
// The subscription allowance includes that shared request budget, short quiet
// probes, driver latches and bounded joins. Delayed fences include both finally
// joins; their held callbacks run concurrently and are released in finally.
// Revision loops create sequential owners: two legacy views, four owners for
// crossed alternatives, five invalid owner resolvers, six pagination restart
// owners and three owners for the mixed simulator/listener localization test.
// Internal runtime probes retain the conservative default lifecycle allowance.
const REVIEWED_LEGACY_EXPANSION_SCOPE_OVERRIDES = checkedReviewMap([
  // Imported fixture: one owner per revision. The shared foreground socket
  // budget, driver polling and both finally thread joins are included; held
  // policy callbacks are released in finally and run concurrently.
  ...reviewedScopeFile("src/test/java/com/soklet/McpLegacySessionTransportCapacityRuntimeTests.java", "fa016943e41ac31bb429b0f93ce569521035860833076c61a95697e7966ee96d", [
    ["temporary_handler_capacity_keeps_get_fenced_and_retries_without_replacing_its_stream","428772cc08aebd9a9d65ff159d0a1e1bbbf618dc91109a09d442c59bf25c863a",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":140000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","requiredAction":"RAISE_OUTER_BOUND"}],
    ["four_ignoring_renewals_hold_global_maintenance_capacity_after_their_gets_expire","4b9831e4b1bb593b92fe7f096f269e8d0f130aa46573bb132fdaa5ecb847e73a",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":140000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","requiredAction":"RAISE_OUTER_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpLegacyCatalogPaginationPublicRuntimeTests.java", "b99e4d8fb999ecc5a1edbabe70e589b56f2df031513ba96ec07eb8722f5b9882", [
    ["catalogsThatFitRemainOneCompletePageInCanonicalOrder","8ccd08350f057f9e689ad92098ef49c0262bfa2b767932c54a4b2c9974528678",{"controlJoinMillis":60000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","requiredAction":"RAISE_OUTER_BOUND"}],
    ["localizationSlotBudgetPagesAllFourCatalogKinds","b093bf04c996d8817c17ee495d9ebbb5241ff5f1935cf8b93c98ae5cfe2212a5",{"controlJoinMillis":60000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","requiredAction":"RAISE_OUTER_BOUND"}],
    ["largeCatalogEnumerationEvaluatesOnlyTheResumeAnchorAndNeededPrefix","eec2d6791a81e9eb3d863de09ac90b5bc3f40529c705196a7ddd9be93166e7e8",{"controlJoinMillis":60000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","requiredAction":"RAISE_OUTER_BOUND"}],
    ["oneDescriptorThatExceedsTheSlotBudgetFailsWithoutAnEmptyContinuation","add9a2b559bbd83a247ab3cc0fdf1eb13f31cd8f27ff54ad3331ea32f2ce328d",{"controlJoinMillis":60000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","requiredAction":"RAISE_OUTER_BOUND"}],
    ["productionByteBudgetSplitsAnAggregateWhileEachDescriptorStillFits","fae7cf8aa9dbf450f02375f411b61526ae930143b25e1f95740ba0142a3f0017",{"controlJoinMillis":60000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","requiredAction":"RAISE_OUTER_BOUND"}],
    ["shrinkingLocalizedPagesReuseLookupResultsAndOneContextAcrossRetries","8a288a7e9c6a43b33256321858a96096122f36bb12f0e73d17d9f259fcc5b128",{"controlJoinMillis":60000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","requiredAction":"RAISE_OUTER_BOUND"}],
    ["cursorScopeFailuresStayNeutralAndEveryContinuationIsFreshlyAdmitted","c3c83776a5a16c6b9a6f52b5a7711e9d4d70b4c8b7920d5224f551563c901910",{"controlJoinMillis":60000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","requiredAction":"RAISE_OUTER_BOUND"}],
    ["changedCatalogRejectsAnOldCursorButEquivalentInstancesCanResumeIt","d92ab9d78f4ff3ca3e4089da260525490538d440d91947794a5a515595cd5f2c",{"generation":{"count":6,"mode":"SEQUENTIAL","complete":6,"prior":5,"incomplete":1},"controlJoinMillis":60000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","requiredAction":"RAISE_OUTER_BOUND"}],
    ["legacyViewWithoutLocalizableOwnersDoesNotInvokeTheModernOwnersProvider","b057e3d5ffb9c004b8abcc948be1f41e6575b6789e202b95784d3ed34b02a828",{"controlJoinMillis":60000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","requiredAction":"RAISE_OUTER_BOUND"}],
    ["frameworkCursorLimitIsIndependentOfOneByteApplicationCursors","ec7c6ab1cdb055915b73be094128474ced76bc0fd833a0b3d946dcea2f5cec91",{"controlJoinMillis":60000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","requiredAction":"RAISE_OUTER_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpLegacyCompletionPublicRuntimeTests.java", "06c1d024cf5ae83cc1a09a77cd486142f59b26ef0fed3865521775d10e2c9d15", [
    ["mixedEndpointRunsOnlyTheSelectedRevisionsCompleter","8f4c94e4d5a50d867f2caea66aab0145f76c0c48560a6f1d5d98814a8f853a45",{"controlJoinMillis":60000,"requiredAction":"RAISE_OUTER_BOUND"}],
    ["initializationAdvertisesOnlyConfiguredRevisionCompletion","b80e99423e103b4a071da7f534d3590c5e19073af111e50000c46bf9a9bbefc4",{"controlJoinMillis":60000,"requiredAction":"RAISE_OUTER_BOUND"}],
    ["visibleTargetsWithoutCompletersAreEmptyAndHiddenTargetsStayNeutral","83175fc47a5f0240bea6f7495536922566a9a6d88909b7829a25a1538fb7e2af",{"controlJoinMillis":65000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","requiredAction":"RAISE_OUTER_BOUND"}],
    ["legacyCompletionEnvelopesAgreeOnTheListenerAndSimulator","5d3f533a95a265d846a00c3386f2ef41f8ad7763ef42170cdb01e3e40aa17e50",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":60000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","requiredAction":"RAISE_OUTER_BOUND"}],
    ["legacyCompletionUsesExistingDeadlineAndCancelationLifecycle","b64f2f39488f12aee11aaa5fcde5991af143ebb88c8d6608bc383e577e0f28e8",{"controlJoinMillis":75000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","requiredAction":"RAISE_OUTER_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpLegacySessionPublicRuntimeTests.java", "960ff8eda225d5cf9230969a64b7bdef9bcb764b2eceaee03e2b6f1726313def", [
    ["defaultStatelessServersNeitherIssueNorRequireSessionIds","92fe746e892af8e6efdf001ac2b62327a92593935d36504e03c1c8f056d06fae",{"controlJoinMillis":60000,"requiredAction":"RAISE_OUTER_BOUND"}],
    ["ownerResolverFailureAndInvalidKeysFailClosedWithoutPublishingIdentifiers","0b0747c821c0a9a6173f44179e0bce09929d89b9821842742f5f994ec1570006",{"generation":{"count":5,"mode":"SEQUENTIAL","complete":5,"prior":4,"incomplete":1},"controlJoinMillis":60000,"requiredAction":"RAISE_OUTER_BOUND"}],
    ["ownerResolutionUsesTheRequestDeadlineAndCannotPublishAfterTimeout","5fadb019882a0bd203ec5ff6bda5fbab5f5dbb5070f32c21e6b96b09f8c57f14",{"controlJoinMillis":75000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","requiredAction":"RAISE_OUTER_BOUND"}],
    ["hardSessionExpiryCancelsPhysicalWorkAndCompletesItsUsablePostWithACorrelatedError","3b82870a64222748c7ff6858abf17ace7464be47823b07ec14a8407877fc1663",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":100000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","requiredAction":"RAISE_OUTER_BOUND"}],
    ["corsAllowsAndExposesSessionHeadersOnlyOnExplicitSessionEndpoints","fc0f49345c0f94dd1bf7e6579ab944c39b560ca86d953ea1a7d112a077d75509",{"controlJoinMillis":60000,"requiredAction":"RAISE_OUTER_BOUND"}],
    ["anonymousAllocationRequiresExplicitOptInAndUsesFreshAdmission","d47948c596554d39372c2a4ae8ee249fdf5a8ba473ea3b49504babf4fa7b16e0",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":60000,"requiredAction":"RAISE_OUTER_BOUND"}],
    ["ownerAndGlobalCapacityFailuresPreserveSessionsWithoutInventingRetryTimes","b764b2696bd61672b155e6be3599db7b9e27ffb974634a87f4a50285e47f72a8",{"controlJoinMillis":60000,"requiredAction":"RAISE_OUTER_BOUND"}],
    ["oversizedClientSnapshotDoesNotPublishASessionId","c78be6768666f710a0bb4836b636fc1c4a02493d9677de1c6319bbb60f617c5a",{"controlJoinMillis":60000,"requiredAction":"RAISE_OUTER_BOUND"}],
    ["quiescentExpiryReturnsTheSameNeutralUnknownResponseAndAllowsReinitialization","796e8b261697e0139e229ee7b69a40e36d7c800f4cda4123c5b5294fbf530f93",{"controlJoinMillis":62000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","requiredAction":"RAISE_OUTER_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpLegacySessionTransportPublicRuntimeTests.java", "e42a66fb27a96a21b2091795c5e3f862250726d4e707cc36765b31a37b4d2b8a", [
    ["methodMatrixRespectsExactRevisionConfigurationAndDoesNotFabricateRpcAdmission","bbaa692983f49dc8e48c63845d23cf2966cf3b2056fea5945b2e95d9d5c3a003",{"controlJoinMillis":60000,"requiredAction":"RAISE_OUTER_BOUND"}],
    ["absenceOfControllerPreservesPostOnlySessions","ad43a445690210c7336807f98d197cfbb3cdd1374fdf01fbffb455373c495999",{"controlJoinMillis":60000,"requiredAction":"RAISE_OUTER_BOUND"}],
    ["oauthChallengesAreApplicationSelectedAndDoNotRenderJsonRpcErrors","4d352fbfebbc85d01fefc34219702ffd7d86b98b399b3bbd06b4c81ccbe27f5d",{"controlJoinMillis":60000,"requiredAction":"RAISE_OUTER_BOUND"}],
    ["expiredAndInvalidSelectionsFailWithoutCreatingGetOrDeletingSession","de6a5e2116bb8685dce942f423b50c6a7815c8479d30271f4ed0d7f560fce6ad",{"controlJoinMillis":60000,"requiredAction":"RAISE_OUTER_BOUND"}],
    ["getUsesBoundedFreshRenewalAndPhysicalHttpObservationWithoutRpcStreamMetrics","e7df14b1645f20295a47f54389a1a82ec41ef5e60bbebb617009c2383b7d0576",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":90000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","requiredAction":"RAISE_OUTER_BOUND"}],
    ["reconciliationDenialAndOwnerChangeCloseOnlyTheGetAndCannotResurrectIt","109273e656783ee34e74917ff22779fcf04c6892ef930a85cbff4dbd28a31879",{"generation":{"count":4,"mode":"SEQUENTIAL","complete":4,"prior":3,"incomplete":1},"controlJoinMillis":100000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","requiredAction":"RAISE_OUTER_BOUND"}],
    ["deleteEndsAnExistingGetAndShutdownBalancesMetricsAndDiagnostics","25a1e426e32c413dedb861bc4cbafd48235a910b29cf74f48f2c753ed6e3686e",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":90000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","requiredAction":"RAISE_OUTER_BOUND"}],
    ["slowReauthorizationCannotDeliverPastLeaseAndRetainsPhysicalReservationUntilExit","4aea7f35e92f1e4aad1034cd47d38b6c62865b61863786f703720a353add84eb",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":90000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","requiredAction":"RAISE_OUTER_BOUND"}],
    ["sameSessionReplacementTransfersQuotaAndCapacityDenialPreservesTheExistingGet","0ad59aef6c928ac3ab3ccc25b1eb0e9fab50416a84f8452db1ccd24b7d419d79",{"generation":{"count":4,"mode":"SEQUENTIAL","complete":4,"prior":3,"incomplete":1},"controlJoinMillis":80000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","requiredAction":"RAISE_OUTER_BOUND"}],
    ["corsPreflightOffersOnlyConfiguredHttpFacilitiesAndDoesNotAdmitSessions","73f95ea53f43b1758304a2e4e0ac89ab5e34f11825e54fcbda94207d471a7cfe",{"controlJoinMillis":30000,"requiredAction":"RAISE_OUTER_BOUND"}],
    ["authorizationExpiryIsRecheckedAfterOwnerResolutionBeforeGetOrDeleteMutation","97d424cae983155f30cf3c22b2f62294fb3f0293e405e309f4ee9bf838786175",{"controlJoinMillis":61000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","requiredAction":"RAISE_OUTER_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpLegacySessionTransportSimulatorTests.java", "e174ffad2a107f18f34da76b9950b6690fe3b5e24d3c693ed2049403534a53b6", [
    ["publishedBeforeAckSessionGetThenDeleteCompletesZeroMessageSseWithExactReason","73846a42deaf51247455f6fd8bd584e8327701575e957d52bc1c8e40f975ad03",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":80000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","requiredAction":"RAISE_OUTER_BOUND"}],
    ["simulatorDisconnectEndsOnlyTheGetAndModernSelectionCannotMutateTheSession","c565bb47800b6d40704ac5d8760f05f06047f6286f9102f15422371a4c777a87",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":80000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","requiredAction":"RAISE_OUTER_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpLegacySubscriptionPublicRuntimeTests.java", "6bf7797a873acb9f979c1aed6bfbbf2e596c3edf2924965e754f60f0ec1f612e", [
    ["simulatorAdmissionExposesValidatedSubscribeSelectionAndUnsubscribeOnlyItsOperationName","181565f33b8fc0ff02fefc32cb963e56120fc3b1cbb8d0bf6baeebc8ef7f9577",{"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":90000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","requiredAction":"RAISE_OUTER_BOUND"}],
    ["invalidUriRequiresAdmissionAndSessionValidationAndMissingOrDeniedReadableRoutesAreNeutral","bd516d0ee9b01a0eda870d84e0d102d550519e70ce47234418d89878329b57a1",{"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":90000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","requiredAction":"RAISE_OUTER_BOUND"}],
    ["liveAdmissionCanChallengeBeforeResourceAuthorizationAndInitializationAdvertisesExactFamilies","e711a9e2000b5af7a97455c8ea65ebb252a7c28b50431d836cc8b80a4f1263c4",{"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":90000,"requiredAction":"RAISE_OUTER_BOUND"}],
    ["liveDuplicateTemplateUnsubscribeAndDisconnectedGapPreserveOnlyCurrentUriGrant","b6308fb761af3d0bc462ffe3c377073ccf6fc5d3ed5f12feea32b4970afe8b5f",{"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":90000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","requiredAction":"RAISE_OUTER_BOUND"}],
    ["simulatorGetCallerSubsetCannotDeliverOtherFamiliesOrUnsubscribedUrisAndModernIsIndependent","db630d5c085c508c6a3f23f34c967168d118f5e967bd596c6e252b0426352965",{"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":90000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","requiredAction":"RAISE_OUTER_BOUND"}],
    ["callerPolicyAndCustomResourceCatalogHintsAreCoarseAndRearmOnlyAfterAnAdmittedList","0a6e81dfe31cc5b5535ef7c4128c969fbc972250011071f521a4abf193a11a02",{"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":90000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","requiredAction":"RAISE_OUTER_BOUND"}],
    ["immutableCallerIndependentCatalogHintsAreSuppressedButUriUpdatesRemainAvailable","9f40868ee41addfebc534e4dbf3e83f0d2d2adbd671249fc80cb5d380b57f102",{"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":90000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","requiredAction":"RAISE_OUTER_BOUND"}],
    ["localizationInvalidationWorksWithoutCallerPolicyAndIgnoresModernOnlyLocalizedOwners","8017ca2bb3aa5e9b7971bf52922b225bd0de1fd8bb91bbfec3a72fe59ccfd874",{"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"generation":{"count":3,"mode":"SEQUENTIAL","complete":3,"prior":2,"incomplete":1},"controlJoinMillis":90000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","requiredAction":"RAISE_OUTER_BOUND"}],
    ["modernOnlyLocalizationCannotMakeAnImmutableLegacyCatalogEmitHints","863f638c0a087a7ea9d6237dca527a9bb5cd2d054c8cc6c4167254fd62f9e6a6",{"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":90000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","requiredAction":"RAISE_OUTER_BOUND"}],
    ["reconciliationDuringEstablishmentDiscardsStaleDenialAndUsesFreshCancellationLease","343cac94ef7baef0d3a882fe49dad2a595076e40c253062dbd9e52c9b78924a1",{"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":90000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","requiredAction":"RAISE_OUTER_BOUND"}],
    ["subscribeUsesOrdinaryQuotaWhileVerifiedUnsubscribeCanReleaseTheEstablishedGrant","8fff86ef17e2133fc502a058332c710f507e250070366e9eb311d6ddc264eb4c",{"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":90000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","requiredAction":"RAISE_OUTER_BOUND"}],
    ["equivalentUriSpellingsSharePublisherMatchingAndUnsubscribeIdentity","d0507f28cf236e68a3cf80f864dd66bde65f20f776e512a835829b55f93bf47a",{"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":90000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","requiredAction":"RAISE_OUTER_BOUND"}],
    ["delayedGrantFenceCannotCancelRenewalStartedUnderThatFencedGeneration","f6b709794d1d36c2a49cd763a20c0abb73c1d862f5be48467d379c8b44c2c845",{"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":140000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","requiredAction":"RAISE_OUTER_BOUND"}],
    ["delayedGetFenceCannotCancelRenewalStartedUnderThatFencedGeneration","5d54e3408ed0017b911e16a6743172a545bf03e7ae1362056c1de2e0fccbbf80",{"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":140000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","requiredAction":"RAISE_OUTER_BOUND"}],
    ["refreshedBearerGetDoesNotRefreshUriEvidenceAndRevocationRetiresBothPermissions","bb9ee7113ffc7cb6eac346100d8695c05d0706dfd69c86f6a81c9941f4b71a17",{"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":90000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","requiredAction":"RAISE_OUTER_BOUND"}],
    ["reconciliationDispatchesShortGetLeaseBeforeSlowLongLivedUriRenewals","5c90bc6f949dc9227c517821522c3d9eb55081f4c9921b803b113c6c31420da6",{"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":90000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","requiredAction":"RAISE_OUTER_BOUND"}],
    ["reconciliationDispatchesShortUriLeaseBeforeSlowLongLivedUriRenewals","ea04d842ddac0ba2799b83724de4394c459699ba4c47cfdf86280f65c787ec3e",{"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":90000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","requiredAction":"RAISE_OUTER_BOUND"}],
    ["detachedGrantDenialRetiresItsSessionAndRequiresReinitialization","5490406a03884cac6758d51f243b1b845278ac719168f880f7e9eb431c725233",{"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":90000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","requiredAction":"RAISE_OUTER_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpHttpServerObservationTerminalRaceTests.java", "5cd0b3916b44da61da4de6adee53d13eedcf0b0315cce74c4ce44c50c279954b", [
    ["legacy_sse_handoff_failure_detaches_delivery_without_refunding_running_work","20fcc7923ad0f209e9a4cb128b0ebf25c13ab5f9309ff428c237a74954d378eb",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":40000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","requiredAction":"RAISE_OUTER_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpHttpServerRequestScopedSseTests.java", "ee9b77c5b93c44597eca3fc49059bdbc549c44f0ea9ee93de51af0ff52645c69", [
    ["reset_before_http_offer_cancels_an_allocated_stream_in_every_revision","848b4365f2ac29f9b4247478659656e0e201f7c59e179d41fcd80d93dcf49aae",{"generation":{"count":3,"mode":"SEQUENTIAL","complete":3,"prior":2,"incomplete":1},"controlJoinMillis":90000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","requiredAction":"RAISE_OUTER_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpLegacyCatalogPaginationBudgetTests.java", "01a0aca68e1406991834356e0eefbf8c37e0436fa8c711412c3e89b93d4920be", [
    ["jsonNodeBudgetIndependentlyPagesEveryLegacyStaticCatalog","4a0d427ce71bb6ad0c62eb9b7ffe53c74b8255a53dd73d13bce999f2550e4d5f",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":60000,"requiredAction":"RAISE_OUTER_BOUND"}],
    ["byteBudgetIndependentlyPagesEveryLegacyStaticCatalog","a57d1e7e3ebd1eb10d2d7338c00321cf0f35b813b2c80ebbf47f5b7e8d8672d2",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":60000,"requiredAction":"RAISE_OUTER_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpLegacyProgressPublicRuntimeTests.java", "5460c7c138801772481195c0ceaa0e99d904ef9834a0a7084f41a7d1119dfe93", [
    ["firstProgressPrecedesHandlerCompletionAndPreservesExactMonotonicUpdates","beed36326f591c1ef37a3b5d37ba5b6fff6f9591cea586a83995ec43d6bef9a3",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":100000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","requiredAction":"RAISE_OUTER_BOUND"}],
    ["simulatorPublishesProgressBeforeTerminalAndDuplicatesTheExactTerminalMessage","7e9106bf9a2205e308efacea61ccbe2ecfe354d502940d0650ac948ac7b7f5f6",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":100000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","requiredAction":"RAISE_OUTER_BOUND"}],
    ["noReportsStayJsonAndMissingOrMalformedTokensDoNotStartProgress","1b5ebf00cdbb8ac3ac4aa09e92f79d0875b963cef6a2bcc3be6e354e5db81160",{"controlJoinMillis":100000,"requiredAction":"RAISE_OUTER_BOUND"}],
    ["legacyJsonAndSseTerminalProjectionsAgreeForPromptsResourcesAndCompletion","d6012c0751e04b77237f7a950da9cc511959cbcd6130ecddf79575de1719b871",{"controlJoinMillis":100000,"requiredAction":"RAISE_OUTER_BOUND"}],
    ["intentionalAndUnexpectedErrorsUseJsonBeforeProgressAndSseAfterIt","b9ec6e2be7ea54790f0ae89925e6a40f26b73ac82c9c8d3b23c9251f54102124",{"controlJoinMillis":100000,"requiredAction":"RAISE_OUTER_BOUND"}],
    ["committedDisconnectDetachesTheWriterAndRetainsUncanceledPhysicalWork","c381dc78f53ff5a3cf1d2c1bc84751ee69e92f8d60c723d437d21eaedb30999b",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":100000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","requiredAction":"RAISE_OUTER_BOUND"}],
    ["finiteAndUncommittedDisconnectsStillCancelTheHandler","0dc4dac53ed5b2c6c8a6feb7e4737455d233e4fc8631635659a872c405c02979",{"generation":{"count":4,"mode":"SEQUENTIAL","complete":4,"prior":3,"incomplete":1},"controlJoinMillis":100000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","requiredAction":"RAISE_OUTER_BOUND"}],
    ["detachedProgressWorkStillObservesItsDeadlineAndRetainsCapacityUntilPhysicalExit","2d882bf63715467c9eac5d45c37c97bef47ac88795956a07d6f7e3cbd3073cda",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":100000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","requiredAction":"RAISE_OUTER_BOUND"}],
    ["simulatorDisconnectUsesTheSameCommittedVersusFiniteRule","4c016b557e409054a8f15e77e6b300e95d65a2a79fb76a0162bba50e3e87ee99",{"generation":{"count":4,"mode":"SEQUENTIAL","complete":4,"prior":3,"incomplete":1},"controlJoinMillis":100000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","requiredAction":"RAISE_OUTER_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpLegacySessionCancellationReservationTests.java", "8acc37758d545c14470fd0c31241a5512fb174a6e84623a593cd72636d2dbca7", [
    ["canceled_body_that_never_starts_has_a_physical_deadline_and_preserves_its_token_reason","78b81c05d430efe7c53e56bef4b14066de2b9e5662f2d9a559b4597093ea56e9",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":50000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","requiredAction":"RAISE_OUTER_BOUND"}],
  ]),
], 'legacy expansion scope');

// Follow-up review: exact local budgets, foreground releases and per-node
// cardinalities. Every allowance remains bound to the full callable and file.
const REVIEWED_VERIFICATION_FOLLOWUP_SCOPE_OVERRIDES = checkedReviewMap([
  ...reviewedScopeFile("src/test/java/com/soklet/CombinedTransportShutdownTests.java", "519925fa00c0cbe929a41dfede416bcf81eca7c0daf7318cbfd3473d299aad81", [
    ["gracefulShutdownClosesSseAndDrainsFiniteHttpAndMcpWithOneDeadline","81f5f33dc8041fb99d46f79973dcfa5e6fd4a7be56128b503d52dc527badb9d4",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":3000,"forcedShutdownMillis":300},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":57000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","requiredAction":"RAISE_OUTER_BOUND"}],
    ["forcedShutdownRetainsAllBlockedWorkersAndLateExitCannotRewriteFrozenResult","773de145aa05cf3518fe0875a96ee065f58855b50a708fe338fdc8b84e35412e",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":300,"forcedShutdownMillis":300},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":59000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","requiredAction":"RAISE_OUTER_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/EmptyVarargsBindingTests.java", "b8b45d5f187e71feb38155e949baed9c73ae6332dc41d1c940a5a35f49ad66dd", [
    ["explicitConverterMayRejectEmptySuffix","0ff3eb51c3c34e7d60dfe4d98610fec90e6190f33ce63f530617b8adf9e7870d",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/external/HttpStreamGracefulShutdownTests.java", "8b0f39217951c91fc9ab07a5e293b0ceae9021adf90c4ca3b6cec4257a4d0cf8", [
    ["liveFeedFinishesItsOutputAndFinalizerWhileAFiniteDownloadKeepsDraining","d0e2a58d49b1d8b1ba258a3e3be6880abe1a854cf4277021d5f19f08dd4a42f7",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":3000,"forcedShutdownMillis":1000},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":10000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["simulatorRequestsGracefulCompletionAndCapturesFinalOutputNormally","58626325013c6d5b0a7a4a89e866fe9facd0f40009f450fdd304a4d55451e6df",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":3000,"forcedShutdownMillis":1000},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":7000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["completedAndReusedFiniteBodyExecutionsStayUnrequested","87272c73b30222f6f8f6af23e54e7f4c7ec642716b2d0844153390db84a054d6",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":3000,"forcedShutdownMillis":1000},"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/FramingTransportTests.java", "244830d1fd738ba55448fe10011902df615d591c33797577efd7f31ae66e1d03", [
    ["faultyHttpFramingClosesBeforeAnyHandlerOrFollowingPipelineRequestRuns","3f86669be51e35a64cd25446bb06b45cba30c83769f751c6024b2c4745cb0b3c",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":14000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","phasePolicy":{"forcedShutdownMillis":1000,"gracefulShutdownMillis":1000,"startupCancellationMillis":2000,"startupMillis":5000}}],
    ["validChunkExtensionsAndHttp10ContentLengthReachTheHandler","56494b056d671ab12382149b37a29d1cf8482198a3f3d52b8e0b0634c0d4a52a",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":4000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","phasePolicy":{"forcedShutdownMillis":1000,"gracefulShutdownMillis":1000,"startupCancellationMillis":2000,"startupMillis":5000}}],
    ["absoluteAuthorityAndEmptyNamesReachLiveHttpHandlers","88d65c68e136bea4361cf6927d8b6c1665d79af44ed60967c86ff3ae1c5d9c53",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":8000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","phasePolicy":{"forcedShutdownMillis":1000,"gracefulShutdownMillis":1000,"startupCancellationMillis":2000,"startupMillis":5000}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/HeadFallbackObservationTests.java", "40014157a80edabf6e935e8758f1d19537b3fa6b5ce6d90f56f39abeb98ec4d6", [
    ["missingHeadAndNonHeadVerbRemainUnmatched","4134e32781ec4ef0388cd4f11f584042ad7d47daf6a57e29c71cb4e20d073029",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/HttpHandlerDispatchAndDrainTests.java", "8be29cebf68665f91f2f66f765e99686c5ec9ec511246dca8271a1a56e208ae9", [
    ["directExecutorIsRejectedBeforeApplicationEntryAndCanRecover","6ae4fc905b0778cace32db5b4a9770fcd7c3e8f35b07815784f7ab1e8491fa51",{"phasePolicy":{"startupMillis":2000,"startupCancellationMillis":1000,"gracefulShutdownMillis":4000,"forcedShutdownMillis":200},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":6000}],
    ["saturatedCallerRunsDoesNotEnterAnotherHandlerOnTheSelector","eab189fa1f390507962eb780cb68e29964795f1a264a68bdaaab84590c5e35e8",{"phasePolicy":{"startupMillis":2000,"startupCancellationMillis":1000,"gracefulShutdownMillis":4000,"forcedShutdownMillis":200},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":12000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["ordinaryExecutorRejectionStillReturnsUnavailableAndRecovers","998c2e53f3c7a32949f5df696434fac20d37ac3c2a84d54837bcb3eb784adda1",{"phasePolicy":{"startupMillis":2000,"startupCancellationMillis":1000,"gracefulShutdownMillis":4000,"forcedShutdownMillis":200},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":12000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["selectorInterruptDoesNotLeakIntoFollowingRequests","4213015959a8aa469575438e904e32404df3cc5e6d9dde01abefcc07f2939a8d",{"phasePolicy":{"startupMillis":2000,"startupCancellationMillis":1000,"gracefulShutdownMillis":4000,"forcedShutdownMillis":200},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":6000}],
    ["activeHandlerDeadlineRemainsEffectiveDuringGracefulDrain","af6f458efad6c788a8d2068df91091fc0ca775692c6f9395c2793fcc2b0379bb",{"phasePolicy":{"startupMillis":2000,"startupCancellationMillis":1000,"gracefulShutdownMillis":4000,"forcedShutdownMillis":200},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":12000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["queuedDeadlineExpiresWithoutApplicationEntryWhileResidualHandlerDrains","db390fdfa292102d8e7f7e1309228a8d6fcc5807d848ee8854a225cc68629005",{"phasePolicy":{"startupMillis":2000,"startupCancellationMillis":1000,"gracefulShutdownMillis":4000,"forcedShutdownMillis":200},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":16000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["successfulDrainCancelsItsDeadlineAndRetiresTheScheduler","7558ab819e7da441c07d56b46a674759750c031d9a242c5521e7123a4828913c",{"phasePolicy":{"startupMillis":2000,"startupCancellationMillis":1000,"gracefulShutdownMillis":4000,"forcedShutdownMillis":200},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":10000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["forcedShutdownStopsTheSchedulerButRetainsRealHandlerResidualEvidence","8523d264b6144fc215c994ec338ff7e0b9430239244e616c5a247ca0f77e7e9f",{"phasePolicy":{"startupMillis":2000,"startupCancellationMillis":1000,"gracefulShutdownMillis":100,"forcedShutdownMillis":200},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":14000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/HttpResponseStreamTerminalRuntimeTests.java", "e921b48c6ac407becea3d6e8988557c5c8c7998f4631af4654577a2e53b00129", [
    ["earlyCompletionWaitsForMetricsHandoffAndExcludesObserverDelay","cb19abdc77185a5e030721b0b570e18533563e5e1601049a0e19807412a62531",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":17000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
    ["metricsCompleteBeforeBlockedLifecycleObserver","6caa5bbe1c6ecb5ec3bf411046745171308106b8b7c4000dcd102154c86b5438",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":17000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
    ["producerFailureCountsOnlyPayloadAlreadyWrittenAndKeepsCommittedStatus","435f4a9f8cfcf53c57b8cbbf80682305db0613841650b2506f2ab0effb776bfd",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":12000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
    ["timeoutTerminatesAnAdmittedStreamAndRecordsZeroUnwrittenBytes","c02ca807313dc59311e38ae7233e77cb1abf747fa4556ad7ef59bfa7f5090ab4",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":17000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
    ["disconnectTerminatesTheAdmittedStream","221ae476993af3ee8418a8a6e795c22f85e89d19673e6b69013c6ead31000245",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":17000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
    ["http10AndHeadRemainFiniteAndDoNotCountOriginalStreams","3829157ed9f18dde4b5a694a1b669c677396a67137b31097bf1459694f3bfae7",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":14000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["throwingMetricsCallbackIsLoggedAndLifecycleStillRunsOnce","a287137f97e652022299300c46b9fa15d0e25761116a0ca9ba2c3c7f8af5e20b",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":7000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["simulatorCountsSuccessAndAcceptedPrefixBeforeFailure","afb3f75d6802c49cf76a5caba13655543aca5cc8f6ebf68660bfe62ee08d9a3c",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":0,"controlComposition":"REVIEWED_NONBLOCKING_PRECONDITION"}],
    ["simulatorLimitKeepsTheAcceptedPrefixInMetrics","e38327ba6c7fc98a1fa9ff8a3c3df1cadcc388f35563946bf0e71139e7ecab2f",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":0,"controlComposition":"REVIEWED_NONBLOCKING_PRECONDITION"}],
    ["capacityRejectionRecordsFinite503WithoutAStreamTerminalMetric","61d46c9f9c4218b901362b4c8fdc9cfb78bf4c5747b75bf9cb7a52a0e223c2bb",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":24000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
    ["forcedServerStopTerminatesAdmittedStreamMetrics","a9209b00622ae8c0c49463564410b422dff94b666f86362f46bdb0c71f50bbeb",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":0,"forcedShutdownMillis":2000},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":25000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/HttpStreamingProtocolRejectionTests.java", "8204e9d36e574e08f0e1f0053706af79b5d226df6c9bf61351002343c92109f6", [
    ["replacementIsReportedEvenWhenTheLogicalStreamAlreadyHasStatus505","edd9de03747cf9409b8357468728c381a2750465985d442706d738c3052e4daa",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":17000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","phasePolicy":{"forcedShutdownMillis":1000,"gracefulShutdownMillis":1000,"startupCancellationMillis":2000,"startupMillis":30000}}],
    ["rejectedFiniteResponseAndRequestFinishDoNotWaitForTerminationObserver","e8174a7201e3a75f164e8061bfd65aaf58367e2e1b76009a43e3a532dbe0ff8d",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":20000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","phasePolicy":{"forcedShutdownMillis":1000,"gracefulShutdownMillis":1000,"startupCancellationMillis":2000,"startupMillis":30000}}],
    ["bufferedHttpOneDotZeroResponseStillWorks","7c1191ed1427e5b33c5a2ffc0d95c37199dd97d8bd089176489439b46ebf6b91",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":11000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","phasePolicy":{"forcedShutdownMillis":1000,"gracefulShutdownMillis":1000,"startupCancellationMillis":2000,"startupMillis":30000}}],
    ["httpOneDotZeroHeadUsesNormalBodyOmissionWithoutStartingProducer","5a640e7dc12ef9fbfa8584ad432d22d116cd2d0ab368092b82cb33827b33fdac",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":11000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","phasePolicy":{"forcedShutdownMillis":1000,"gracefulShutdownMillis":1000,"startupCancellationMillis":2000,"startupMillis":30000}}],
    ["httpOneDotOneStillStartsAndCompletesTheStreamingProducer","f0970e97e31dc43a5ea0ed5116d433baedbebf215ba8e66e3667ea5e851d246c",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":14000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","phasePolicy":{"forcedShutdownMillis":1000,"gracefulShutdownMillis":1000,"startupCancellationMillis":2000,"startupMillis":30000}}],
    ["httpOneDotZeroRejectionReportsActualResponseAndRetainsOriginalStreamHandle","9e56ea5b9ad0a7e4810a4c3e095dc5f9f311c7b9aa61301007287cff81caed5a",{"generation":{"count":4,"mode":"SEQUENTIAL","complete":4,"prior":3,"incomplete":1},"controlJoinMillis":68000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","phasePolicy":{"forcedShutdownMillis":1000,"gracefulShutdownMillis":1000,"startupCancellationMillis":2000,"startupMillis":30000},"requiredAction":"RAISE_OUTER_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpLegacyRpcStatusTests.java", "ac8172f4768f73247378ac9155ed3ab5ecb5ad09d3b5d72b321d34b791c92029", [
    ["legacyOperationErrorsUseHttp200WithAndWithoutSessions","0e808b9be8ab42c855f493e1186d106ca660b9709b01faa968d7074323a4bd75",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000}}],
    ["legacyIconProjectionPreservesCompletedResultsAndRunsTheHandlerOnce","179a0225a1b4d56e207c648a28e9861508b1095e505fb2751c26cdd94ca41888",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpSimulationResponseOrderingTests.java", "a003f7b99f8d20b574efe426ff4e319f30004a7068f29f440f590c498e8344eb", [
    ["terminalResultWaitsForTheProgressResponseHead","3df28110665d1deb14c9d32aaae08f6b3a0fa486ca1bc2597c08585856fca81b",{"dynamicNodeCount":3,"controlledLifecycleCoreMillis":5000,"controlJoinMillis":55000,"controlComposition":"REVIEWED_DYNAMIC_NODE_MAX"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpSubscriptionAuthorizationPublicRuntimeTests.java", "1cf21fc9f03f4915e74b1bfadb8c5434ecba96ea41b318dceb3b1e4efb52a7db", [
    ["renewalDenialCompletesTheListenAndPreservesItsReason","44b7d7254e0f3d0d46c9d09e37581395c4e2fd904d6cde13003271e14c4166f9",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":1000},"controlJoinMillis":30000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["reconciliationDenialCompletesTheListenAndPreservesItsReason","4f2cbaf5059133a3ce56da40485c2b100b0aa3499eb7ef858852324436018268",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":1000},"controlJoinMillis":30000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["renewalFailureCompletesTheListenWithoutDisclosingTheException","37705ac22a66283c39427084121543ea4dda4af51d12f18be2ccab6d2ee3e7b5",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":1000},"controlJoinMillis":30000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["reconciliationFailureCompletesTheListenWithoutDisclosingTheException","579d4053275f7ff159182184013eeaaa4167ecbd00b3cd90cf760a9ce7e3daeb",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":1000},"controlJoinMillis":30000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpSubscriptionDeadlineWraparoundTests.java", "e0b11cc7a4c65a9324eede5c482e404e8c1ec765ed24cd212cf661fd203c7648", [
    ["queuedReconciliationReceivesFreshCallbackBudgetAtDispatch","abc9ddde9605a66bae53e5ae4ab4893cf224427c36ab941d817b8f736a6aeb55",{"controlledLifecycleCoreMillis":5000}],
    ["queuedReconciliationCannotExtendAnExpiredLease","4cb659ad3e67bf7ac6b57239b1d079d9d50f7d06001063801b7d61bb0f4e492d",{"controlledLifecycleCoreMillis":5000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpTaskSubscriptionPublicRuntimeTests.java", "b2652db11f4040068949c539e11790988dde38bb11ba25cb8c8ff041eaffec9c", [
    ["acknowledgedTasksSurviveTransientMaintenanceLookupAndUnsupportedInputGenerations","21339fb061378b6481f3c374be2f1f71ba50d82e45487f57ff845eb249e8fef4",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":40000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["lateTaskOfferSuppressesExpiredProjectionWithoutAbsorbingTerminal","25fe7e5763987a4dbacc7ae02c1d437c1a93b9afc3d44136c17b0dfa14a023ab",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":15350,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
    ["timedOutTaskPolicyRetainsOwnerUntilPhysicalExitAndAllowsPeerProgress","a609c25ba28abdc1973db5b311863a1941f7bbe90e83231cc369eeb7404f358c",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":20000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
    ["taskResultFencedBeforeOfferIsReprojectedWithReplacementPolicyContext","3e1e91268b8ed754a16fdf93c33b27d190d7c13b87774ecc748c1b081bfca5de",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":20000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpTransportMetricsEventRuntimeTests.java", "bf7438b46b521d74e9046c26b849dcc891316dac8708602711c4d586a5e2369e", [
    ["subscriptionMaintenanceNeverDrainsOnTheProjectionThread","7ce5495557bf485dcde6b7aeb200203d91f53ab44bd4724cf90bc1e1823c26ca",{"controlledLifecycleCoreMillis":0}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpWriteIdlePublicObservationTests.java", "788f7df288a0b58e08899f0de80ba1afe93a02de89bfc63cc92d2b1925233f86", [
    ["stalledWriterIsWriteFailedBeforeTheRequestDeadline","aa0c79e13ae956d2bd58454fa8af6c858d63afd6aa784748b3928a4073726ae4",{"dynamicNodeCount":3,"controlJoinMillis":40000,"controlComposition":"REVIEWED_DYNAMIC_NODE_MAX"}],
    ["expiredRequestDeadlineRemainsDistinctFromWriteFailure","c9f2ce386663f7cb60a432bc0f7fc714cd4da4fd1f32a5068e857acbcb64062c",{"dynamicNodeCount":3,"controlJoinMillis":40000,"controlComposition":"REVIEWED_DYNAMIC_NODE_MAX"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/microhttp/StreamingDisconnectMonitorTests.java", "414476efa40df3f0ed99703f399fdb92b2cb1ed96491adcabcc89efd0083dba0", [
    ["coalescedInputAboveRequestLimitClosesBeforeCommittingStream","b2b33fd09bf43cff80394b02d5cb3632df3e0d1f9bea3072a4002373de8489c1",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":9000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","controlledLifecycleCoreMillis":0}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/microhttp/StreamingOwnershipSupervisionTests.java", "7a459664530463101c7a47594102e48da64884df5c17e83e0b9d63f9b60f2e44", [
    ["blockedOwnCloseExpiresWithoutMovingDuplicatingOrRetiringIt","db71273efe1b8a383ab9d8f421ad2f3b44066b7b588e6d62574a061eaf1821f5",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1}}],
    ["canceledAcquisitionDisposesLateResultBeforeReturningItToWriter","8bc76d73acc3fc6b162ce1d6c9473c3c457517a91bbe3a29b589df006acbce22",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1}}],
    ["coordinatedCloseAsAbortRunsOnceWhileProducerCleanupWaits","46156e830dbe60f27fb6ce97e4bcc2d994717f8c02e5cb7c5a01ffdf8c4cc52f",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1}}],
    ["separateAbortCompletesBeforeFinalCloseOnProducer","86e82f3fe55d7ef0f6ea656f88928202811d2f541dfccf57f9e7eecd5ecedd53",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1}}],
    ["lexicalFailureAfterCancelationRemainsAvailableAsDiagnosticEvidence","3d9dba8ca11f55d147517020d9f91473211e16c09cdaf969422abc5fafcae9b8",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1}}],
    ["typedCancelationAndWrappedInterruptionDoNotClaimProducerDiagnostics","9b98b2cc8dc4844ee1c49f166c8d6befc8f4606c67735c36151819c37863e3dd",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":30000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlledLifecycleCoreMillis":0}],
    ["boundedCyclicAndDeepCancelationEvidenceKeepsTheElectedOutcome","0176bfbbcd692c112c9a68a683d1f8e57204ca654ea14a693b8818bcfad91ff0",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":30000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlledLifecycleCoreMillis":0}],
    ["caughtInterruptedQueueWriteRemainsAnApplicationCanceledResponse","b46eca55f82595a68ac1eef456c0c04fbf2ddb72fddd94e0ed5160c22c2caebc",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpAdmissionOrderingPublicRuntimeTests.java", "81e820fb71665a1b83ea6176f4b663500fb8e4196fcbd2057d597f471db9e909", [
    ["unsupportedVersionsCannotProbeRegisteredCustomHeaders","bf73588c7d00e60af2485e4ba89ca60251fff3f5757bf9d7c25a614c506fabd6",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpClientMetadataAndErrorTests.java", "3e93aac1146d683f2f5dc1d52cfcc974bfbdfccfb4796d248aa105123403b286", [
    ["blankClientInformationSurvivesAdmissionAndRememberedSessionMetadata","754aee9ad0d118d74f18bb5b1d14d67e881413ddb372d470b2794d55497594ac",{"generation":{"count":5,"mode":"SEQUENTIAL","complete":5,"prior":4,"incomplete":1},"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":90000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["sessionCallsAcceptBodiesLargerThanThePersistentEvidenceBudget","06c22bf20d6a9423cb097a8041be9199cd095f92fcc63cdf055889845080abaa",{"generation":{"count":4,"mode":"SEQUENTIAL","complete":4,"prior":3,"incomplete":1},"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":80000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["missingOrNonstringClientInformationRemainsAProtocolError","3ac52eff1b94d20b865fc9b7a0f25241328e3849dbe4c33de539aa57c1220836",{"generation":{"count":3,"mode":"SEQUENTIAL","complete":3,"prior":2,"incomplete":1},"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":120000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpCompletionPublicRuntimeTests.java", "91232c12ca31b729c4207bd88bf38a4e4b5c611b39a051c1be0c540497c7fae8", [
    ["onlyCompletionHandlerErrorsAreClientVisible","0c8936cb541fecc053695e6074a9a536d2fbd888da43ed6e14108b46214a0ff0",{"generation":{"count":6,"mode":"SEQUENTIAL","complete":6,"prior":5,"incomplete":1},"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":30000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpContentValueBoundaryPublicRuntimeTests.java", "603d8c57d38108080a829ef8d855795381270311b3c4e6eff9a79c76371bbbee", [
    ["contentBoundariesFailPrivatelyAndRecoverForEveryRevision","636e576d012ae7ded4d14123e52bf229ccadf5068f324536bce1bc59b5fc8271",{"dynamicNodeCount":3}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpHandlerExecutorPublicRuntimeTests.java", "252b65f4871965d324c251389872c8814e0d3c205904ba30b55622fcbebf4e9b", [
    ["executorBoundariesForEachRevision","db76964a8a6b5875405518546452bd9364d3f6967d22773aec67e4ad5f4b91d3",{"dynamicNodeCount":15,"controlJoinMillis":25000,"controlComposition":"REVIEWED_DYNAMIC_NODE_MAX"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpHandlerInterceptionPublicRuntimeTests.java", "462850f5ca7f76d7c71139e42fd6cc35862c1d07fd22679415ca0cc901daabda", [
    ["interceptorsObserveAndRethrowExactHandlerErrorsOnEveryRevision","98e184a4fb3a22376a17739c02f7effe906eeaa878e538dff91709c585bc01d0",{"generation":{"count":3,"mode":"SEQUENTIAL","complete":3,"prior":2,"incomplete":1},"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":110000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["interceptorCanRecoverHandlerErrorsThroughNormalResultValidation","12304710516a8e2cf8d2af1c81bc8c4d984d6d0207362f291808faf8774a66f0",{"generation":{"count":3,"mode":"SEQUENTIAL","complete":3,"prior":2,"incomplete":1},"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":110000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["copiedWrappedStaleAndInterceptorAuthoredErrorsStayPrivate","2d124ec727ff58281752b8c197dd7db759e73a593ae9d60a55705979430aeeb0",{"generation":{"count":3,"mode":"SEQUENTIAL","complete":3,"prior":2,"incomplete":1},"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":490000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpIconPublicRuntimeTests.java", "1206fb6ff271c38396cff9e0510daf81fc6bbec81d731234d69710e513a3a6fd", [
    ["iconsKeepExactValuesAndRespectEachRevisionProjection","45d7d930b9c7c52a025ac70309d7333a76f8d839e1c981d582cfd1b387839520",{"dynamicNodeCount":3}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpJsonAndSchemaLimitPublicRuntimeTests.java", "5901f7828ba37d4bdf0a34cf85b81d074a4f8d2d48715524a458e007c4aeb8ef", [
    ["limitsFailSafelyAndRecoverForEveryRevision","e3ebcfa30fcc56701ede80ee92d61a873e4248f6f5c9056c15c1ceba288a309b",{"dynamicNodeCount":3}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpLegacyNegotiationAndNotificationTests.java", "bbd3426e4d48b9513d65b1344758bf0befa1a79e68e0f31ac3ff82151d7a7248", [
    ["missingVersionCanUseOnlyAnUnambiguousLegacyEndpointWithoutDowngradingModernFraming","339e8d88cfb05d99f635586450d1e7eb228f88381c51c58d45dd47383b41b924",{"generation":{"count":6,"mode":"SEQUENTIAL","complete":6,"prior":5,"incomplete":1},"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":5000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":220000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["initializationCanCounterofferAStaticHeaderWithoutOverridingASupportedBodyVersion","0578305912ee8f8ec53102ad70a0095c18711ecc9833ac333a1573ec0127e3df",{"generation":{"count":5,"mode":"SEQUENTIAL","complete":5,"prior":4,"incomplete":1},"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":5000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":120000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["unsupportedLegacyNotificationsAreAcceptedOnlyAfterAdmissionAndSessionValidation","82268819a8c58ec517cfd65b06032726df306e8c0ddcacd00b20b83967c9f0c0",{"generation":{"count":4,"mode":"SEQUENTIAL","complete":4,"prior":3,"incomplete":1},"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":5000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":400000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpLocalizationInitializationPublicRuntimeTests.java", "fba56f4fb4dd98da85044b418d8d6e294f9345cd7ee25cfd0ec6b6966c2d1349", [
    ["initializationLocalizesOnlyRevisionSupportedText","d3be4d1f2093f50f80f0cd7406df7e20f1157faacd3a419cbc2c0075d4dd6b17",{"dynamicNodeCount":2}],
    ["failedInitializationLocalizationsReleaseSessionsAndCanRecover","2c4e84acf1150f777d9c3c00037930eb354fe078eac7ac50d3f629c3244c3993",{"dynamicNodeCount":2}],
    ["wholeResponseFallbackAndNoLocalizerPreserveCanonicalBytes","9d2492f3e1eb33c782b307600d53b18ccb98db6efc4244da573c496d368d014a",{"dynamicNodeCount":2,"generation":{"count":3,"mode":"SEQUENTIAL","complete":3,"prior":2,"incomplete":1}}],
    ["simulatorUsesTheSameInitializationLocalization","2ff1fa96536b9a894a0cb268c5a072a27e51d20c1a6ffdb6b11682719712e8c5",{"dynamicNodeCount":2,"controlJoinMillis":10000,"controlComposition":"REVIEWED_DYNAMIC_NODE_MAX"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpLocalizationRenderingRuntimeTests.java", "b2392481eac2874687c2714fbf54ea0f942c870605066f899000587fde6ea454", [
    ["everyFrameworkListLocalizesServerMetadataIncludingEmptyCatalogs","3946de2e78fa35868a30faea558eb7e7dcb32331c5b7bb8b12c902809173ef10",{"generation":{"count":8,"mode":"SEQUENTIAL","complete":8,"prior":7,"incomplete":1},"controlJoinMillis":80000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["serverMetadataFailureUsesTheWholeCatalogFailurePolicy","ba7dbcc5fcec3cf2b7ae6fcd4ff1103402cd9aa228d026e14d381aaacaf9c31e",{"generation":{"count":8,"mode":"SEQUENTIAL","complete":8,"prior":7,"incomplete":1},"controlJoinMillis":80000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpResourcePublicRuntimeTests.java", "394cb802f742f6a5110093230816f5e288930afe959a1e4b7ec765cc3ed9514e", [
    ["fileTemplateVariablesEncodeSlashesAndDecodeExactlyOnceOnEveryRevision","58799552f4eda07d290352d91e7ae01d17605fa8e8654d467b4862b59e4c0b8a",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpSimulatorPublicRuntimeTests.java", "0e2be1c351c0bb667d0a419fe9c3f3d921215361e9c01b696499355d58c6ac6a", [
    ["simulatorStartsRequestAgainstConfiguredMcpServer","2c0d86afd7ceb1c42c9efae81fe0e636bea552de9e90d8bb39ba40894f411efa",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":45000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["synchronousJsonSimulationUsesRealProtocolLifecycleMetricsAndBodyType","08626d3584884fca91e40c574543343f3c6023a14a6f1b224ae64fd335b158f9",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":35000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["simulatorRepresentsEventStreamAsOpenMcpSimulation","db071f4429cc5b5847d612962df6c53923a32b0e860d661eed834f2bb0aef933",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":30000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["mcpSimulationBuffersStreamItemsAndClosesExplicitly","e3bfe064a1680f076a0654326636740024f7e2165f5891c55ab18e1963b2f7bb",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1}}],
    ["subscriptionReplayPreservesAcknowledgmentEventAndCancelationOrder","1ac72a539ae05ae65674d5f91b780f23aa648470a2da39d984b4d3cb9dfda7e2",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1}}],
    ["concurrentSimulationsRemainRequestIsolatedAndDrainExactlyOnce","35c0b468159f9ad950f4ddbaf882ba4dc80168e6f04b6696052ec9fbb07e3264",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":65000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpSubscriptionCatalogProjectionPublicRuntimeTests.java", "a2e78349550ea76ea79a55f47f56dfad2d415b3e1b03c5b0dada83854f79633d", [
    ["providerFailureUsesCanonicalCatalogBaselineAndRecovers","f8cfd98530ed0011e26da65f06bf85d134e694f831aa23fde94e899b1c755d64",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":150200,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["lookupFailureUsesCanonicalCatalogBaselineAndRecovers","8ec66bf121e51988e76148123c1062d1065d4e3e4453f321b71a7fcb91eb5c6c",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":150200,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["failRequestCatalogBaselineIsSanitizedAndReleasesCapacity","2fbff3fdda74f3f79abc3b53c21157c3628f9deb292e31c5dd1e3ee308ba1980",{"generation":{"count":4,"mode":"SEQUENTIAL","complete":4,"prior":3,"incomplete":1},"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":160000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["explicitCatalogPolicyContextFailureStillFailsBeforeEvaluation","5520c20d50a93ef2579c9e35ef99e139018c7e195583b10e684df320b36b658e",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":60000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["localizedInvalidationDuringBaselineCatchesUpAndDropsStaleTerminal","2eeb7e761772af9c787ee7666017f7bb58db6ca58da755e4f0952b80ae06edcd",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":50000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["catalogChurnDuringOpeningDoesNotRepeatAuthorization","ee20a9cf8d072312c84a6b3295e2dddbfbab778da43163705722854b8bdae160",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":100200,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["catalogChangeDuringBaselineCatchesUpAfterAcknowledgment","eec2479d9a223791f4fd2fb22a6189a860254bba6068958bbd95a4e9d28c4d08",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":80200,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpTaskCapabilityBoundaryPublicRuntimeTests.java", "e0dcc3ef88dc597068e68bbb92eada3467941d18d4a9dd8979afd5a44edb1040", [
    ["advancedTaskReturnsWithoutCreationContextAreInternalErrors","f01f23752f80185b828711a70c5d0755e8fc13c53c4b7c52443ee61067283878",{"dynamicNodeCount":6}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpTaskInputCapabilityPublicRuntimeTests.java", "659378283508637f04b603ad0ba48b719d1f4b0ced21f7e7a58b1abf83740dc0", [
    ["everyOutstandingModeRequiresCurrentRequestSupport","4cd38945a4a4c4c4583ca8fa9a4d6c6a7bc88ae9930e1b4a0e065c057181aaf8",{"dynamicNodeCount":13}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpToolInputErrorPublicRuntimeTests.java", "7988112745c3e24f7ca954fcae24146534c97cc673851fe452e68a7053365045", [
    ["toolInputFailuresFollowTheSelectedRevision","e472b0198e55dcb82eba57b145d5a4cc0a96b3e3956eaabaf9ef0bc6ff6f9a87",{"dynamicNodeCount":3}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpTypedIntegerLimitPublicRuntimeTests.java", "34835add9274c8e2cd5b21af42aa2773480f9869f6d82f202fba43640547e138", [
    ["boundedIntegerBindingForEachRevision","c8592af11a532247ebd1c691342d495e672fbd85c1b9fb954e4579acc5ed38e1",{"dynamicNodeCount":3}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/ResourceMethodBindingValidationTests.java", "a2072fb0fa90a2b5cc01961e7ffa3881a74ca9ba515f30a03c5d044e56112d1e", [
    ["defaultBindingFailuresAreRejectedBeforeStartupOrInstanceAcquisition","0388d3d8d1040109d5f11238f2e645efaa06eeae80a6b268eb862ec16755510b",{"generation":{"count":4,"mode":"SEQUENTIAL","complete":4,"prior":3,"incomplete":1}}],
    ["incompatibleDefaultConstructorsFailDuringSetupWithoutConstructingAnything","bb5e0d50885b95fdc1992995af82cebcfd559cf199a3163279661a23bc03a883",{"generation":{"count":3,"mode":"SEQUENTIAL","complete":3,"prior":2,"incomplete":1}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/Round2SseRuntimeTests.java", "3686c08a4a35b52dc4ab53522a4575976b542dda7324a8a8f19511e5bec76f4f", [
    ["partialHeaderReadersLeaveTheApplicationHandshakeWorkerAvailable","aeeb9ccb53d632a5b3a7c501bb044c36467f3065ad8d99458983c53917d2624c",{"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":1000,"gracefulShutdownMillis":3000,"forcedShutdownMillis":1000},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":3000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["pendingHeaderAdmissionRetainsTheConfiguredBound","e77c765743a1da611786b6ab6d6c9b7e28f2ed3e5b3439a95ab20ba98978b3f3",{"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":1000,"gracefulShutdownMillis":3000,"forcedShutdownMillis":1000},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":3000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["gracefulShutdownQuietlyClosesIdleSocketWithDefaultTimeoutsAndJoinsReaders","79f4eddf0d5dbc83f532d7eaea526015dd6043705206d0d2a13e201e801c6ffa",{"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":15000,"forcedShutdownMillis":3000},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":6000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["gracefulShutdownQuietlyClosesPartialHeaderWithDefaultTimeoutsAndJoinsReaders","dafc334a63d5be5c78739e0cc9f2d6560a98a6497a948aa48520af1ba699bba2",{"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":15000,"forcedShutdownMillis":3000},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":6000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["gracefulShutdownPreservesParsedQueuedHandshakesAndTheirResponse","4716812762fcf5c0196651acc78b2a97bd029d36da2ea56b972afd6c0258d634",{"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":15000,"forcedShutdownMillis":3000},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":8000,"controlComposition":"REVIEWED_LIFECYCLE_CORE_DEDUPLICATION"}],
    ["requestFinishingParsingAfterQuiesceCannotEnterTheApplication","e5f1b2247addc25d36c79eeacbb67a68ea2d428489c8e1f4c3ac4d94c00e8000",{"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":15000,"forcedShutdownMillis":3000},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":8000,"controlComposition":"REVIEWED_LIFECYCLE_CORE_DEDUPLICATION"}],
    ["malformedOversizedTargetsUseUnparsed413WithoutInternalFailures","bd94c55beaa2d93325b855aaf4fe419e3a3bbe13b3d9b42cb459755c1e6aaf6d",{"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":1000,"gracefulShutdownMillis":3000,"forcedShutdownMillis":1000},"generation":{"count":3,"mode":"SEQUENTIAL","complete":3,"prior":2,"incomplete":1}}],
    ["forceShutdownClosesAdmittedHeaderSocketsAndJoinsTheirReaders","42adbd7af5f0a091112796d30c1b91372ccda9265fe5aae3db653893404c6695",{"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":1000,"gracefulShutdownMillis":0,"forcedShutdownMillis":1000},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":6000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["interceptorFiniteReplacementCannotJoinTheBroadcasterInLiveOrSimulatedSse","4723ee5a1d46494228c34223a69795657ca64e0cdb10c6d591d5b2834573f8c7",{"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":1000,"gracefulShutdownMillis":3000,"forcedShutdownMillis":1000},"generation":{"count":4,"mode":"SEQUENTIAL","complete":4,"prior":3,"incomplete":1}}],
    ["initializerFailureAndOverflowEmitOneContextualDiagnosticWithTheElectedReason","55fa9d65a4da7fd3f559c25ba120283ae2ca523a187b639b255a55f116bb76e8",{"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":1000,"gracefulShutdownMillis":3000,"forcedShutdownMillis":1000},"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":6000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["immediateResetHealthChecksDoNotReportServerAcceptanceOrTransportErrors","a06551d12eb6fe4cb59a331e96c24d10cb8cf6d2bc50409c9dd17a5468cdc0d3",{"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":1000,"gracefulShutdownMillis":3000,"forcedShutdownMillis":1000},"controlJoinMillis":37000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["noncooperativeHandshakeReturningAfterForceDoesNotRecordAWriteTransportFailure","eb459f98300b5c48f9f4fb13fea5e4bfa1f4f3241423ede720913714fd5d5c01",{"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":1000,"gracefulShutdownMillis":0,"forcedShutdownMillis":1000},"controlJoinMillis":12000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/ShutdownDiagnosticsTests.java", "52a6964248610ba557144c2c1d2ff88bb6182fbb5d92ce8aef068914c311dea5", [
    ["actualSimulatorResidualWorkIsExplainedWithoutChangingFailurePrecedence","8612d11bb585b0214cc5ee8a1b6aed01a314351d7bfe31135fe9109de8240a3e",{"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":20,"forcedShutdownMillis":20},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":15000,"controlComposition":"REVIEWED_DYNAMIC_NODE_MAX","dynamicNodeCount":4}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SimulatorSseDeliveryContractTests.java", "1c83eed282eb6708c9aca42ae08df464befbcc0e3edce8bd21b5e81e2b8b24e4", [
    ["oneListenerCanReadMixedBroadcastsBeyondTheQueueLimit","d761d8906f4f8352bb3648198f13a10b16a7acc3d9bfea6923604afc87fced38",{"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":300,"forcedShutdownMillis":1000},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":3000,"controlComposition":"REVIEWED_DYNAMIC_NODE_MAX","dynamicNodeCount":2}],
    ["initialMixedCaptureSurvivesReadingAndLaterListenerRegistration","4f4bda3f6338427cc8c10321414703a65733d5acfdc6764c390ca5c9cfdb4dd3",{"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":300,"forcedShutdownMillis":1000},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"dynamicNodeCount":2}],
    ["readingStartsBeforeReentrantConsumerDelivery","775f944f116834deaeb053ae4fde1575d739b02e9ede551240ffe994ab49b05f",{"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":300,"forcedShutdownMillis":1000},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"dynamicNodeCount":2}],
    ["capturedPayloadsDoNotConsumeTheLiveDeliveryBudget","441900c92ca837fd4e134bd4c941983a31eb1628ba41828eb2777528b958b921",{"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":300,"forcedShutdownMillis":1000},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":9000,"controlComposition":"REVIEWED_DYNAMIC_NODE_MAX","dynamicNodeCount":2}],
    ["aBlockedListenerStillHasFiniteLiveBackpressure","06b6d691df6ee3e3350e00246c8c3b93d6481f2178d2dd6d79d65fb0c825736b",{"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":300,"forcedShutdownMillis":1000},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":15000,"controlComposition":"REVIEWED_DYNAMIC_NODE_MAX","dynamicNodeCount":2}],
    ["twoRegisteredTypesShareOneLiveDeliveryLimit","15f7f89f0bb9b7284eaf1d51b6793d24c3ea863ad4a309ca6290de4a68a419b2",{"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":300,"forcedShutdownMillis":1000},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":18000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SimulatorSseLifecycleTests.java", "0fb6320a8b01011a4f516a802e97eff85b6066edf07fc856ba40616a80f8e2c8", [
    ["closeReportsOneOrderedLifetimeWithExactMetadataToBothSinks","983ee6ef8d4352aaf071500be2d9f04cdc4006b7596297bd43ccd6fdc20e1d93",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":2000},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":5000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["checkedInitializerFailureStillPairsAcceptanceAndPreservesTheElectedCause","685938bde38d30e00ec1408a378acc130a2559b48477b9f6396674f723d69bd3",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":2000},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":5000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["initializerOverflowReportsBackpressureWithTheOriginalFailure","f35c56db42039ec27e6eb25e389b0406970c9059c4f73ff56e1556aa71b695b4",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":2000},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":5000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["unreadBroadcastOverflowReportsBackpressureOnlyOnce","4dd2014c7c1a56bbb0055c23d3db81b2551a874ad0bcb48af964ee75a4d8d666",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":2000},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":5000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["teardownReportsServerStoppingBeforeTheScopeReturns","7c6aa01fac6de8fe0de69c383507e0f95fc055bf59cd2e0800fe74554d3f8986",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":2000},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1}}],
    ["callbackFailuresAreLoggedIndependentlyAndDoNotReplaceTheOutcome","10b14753ea2143ac37666b161cfb50a5e852ba299619878b10eb297bc2f76f52",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":2000},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":5000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["blockedTerminalObserverDoesNotDelayReleaseOrAnotherLifetimeAndRetainsCapacity","ee56e9da28e782bae961f54623a372904ca66dcbcb1739310e2dbd9c43cc91e8",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":2000},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":30000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["shutdownDuringEstablishmentPreservesOrderAndCountsTheBlockedCallback","dccac91c00342d5fc9e252185004d6e867a9c196bddf68cc9e4db40086185d99",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":2000},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":25000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["initializerFailureWinsBeforeABlockedEstablishmentObserverAndConcurrentShutdown","85c61c78b74590c247e19f18b6a831f33b77b8fb2bb5b46b553d6349205f7623",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":2000},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":20000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["rejectedAndFailedRequestsReportHandshakeFailureWithoutInventingAnAcceptedLifetime","5a409618fafe74ee24eeb149fba1e6927b504c0fcbb8181f6ba88dc408051f79",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":2000},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":10000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["cancellationBeforeConstructionStillPairsAcceptanceAndSuppressesTheInitializer","13a491a334642c8e0b0e2996f26fdf788ff4adbe57d42a7a7d776f6017442e49",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":2000},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":10000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["blockedHandshakeFailureObservationHasABoundedAllowanceAndDoesNotDelayTheResponse","d1c32ae45443a013fd416c5433f2650642964da976ccd1ccb9a9bf3de0b61bc5",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":2000},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":10000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["anObserverThatOutlivesTeardownRemainsAccountedUntilItsActualReturn","6ee964c888d1d8b30e36879938da30d88359d438e7f9913cc240479ea1871397",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":20,"forcedShutdownMillis":20},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":10000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SokletDirectLateStartupIntegrationTests.java", "9e0c8efa5bc053cf760be123c35f280f0cf4b679ce29942604f8a24419d5cce1", [
    ["lateStartCompensationDoesNotWaitForOrReplayQueuedForceWorker","c6e9b2bd340baf6fff466604cb6100ce09a6f080297e60125f6fafed327180ea",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":0,"gracefulShutdownMillis":150,"forcedShutdownMillis":2000},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SseDurationBoundaryTests.java", "9c3ad846a50e5fb09cefe23957bcdf0eb9cc60a48455294cbce2506f4c8f0ad7", [
    ["invalidHeartbeatsFailAtBuildRatherThanDuringAConnection","58b387113e2c317c038103d915f54288f1f80762318bf1b961bcbfb8aa3551be",{"dynamicNodeCount":7}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SseHandshakeOutcomeRuntimeTests.java", "4d79a0f1defe79662dc46562e6cb5b5e1ee710bd2138c8e6ca81594e3ad31e85", [
    ["simulatedInterceptorFramingHeadersCannotActivateAnAcceptedHandshake","668466bb36869bafc87bedf32de2f61b0dfe61d0efef20375c09c338aaec87c5",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":300,"forcedShutdownMillis":1000},"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["liveInterceptorFramingHeadersCannotActivateAnAcceptedHandshake","54f574a55a070059631bd25fdd54f06d74d650bec2fd3b7401647726a0801204",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":300,"forcedShutdownMillis":1000},"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":4000}],
    ["headAndOptionsDoNotConsumeOrRequireStreamCapacity","b12989304ca1e067566a3ec4401fcb1ea447d10ff67dcd75ea71165a590ec408",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":300,"forcedShutdownMillis":1000},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":9000,"controlComposition":"REVIEWED_DYNAMIC_NODE_MAX","dynamicNodeCount":2}],
    ["headTimeoutResponsesCannotSendContent","e02012f92e23948e947adce37413abf6f03b857bd848b98f294b961fd63b75fc",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":300,"forcedShutdownMillis":1000},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":5000,"controlComposition":"REVIEWED_DYNAMIC_NODE_MAX","dynamicNodeCount":2}],
    ["initializerFailuresReportEstablishedThenElectedTerminationWithoutInternalRejection","522810573cc15ebc9681bfb45ab042ab4f4f133b56e3dd06525ed84c2b1120f2",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":300,"forcedShutdownMillis":1000},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":11000,"controlComposition":"REVIEWED_DYNAMIC_NODE_MAX","dynamicNodeCount":3}],
    ["bothCapacityBoundariesReportCapacityRatherThanApplicationRejection","5e034cca673ee981c680fc5c4cf6ca97f39b161cd8cebb98c79bff8ff1ca7765",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":300,"forcedShutdownMillis":1000},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":10000,"controlComposition":"REVIEWED_DYNAMIC_NODE_MAX","dynamicNodeCount":2}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SseMemoizedBroadcastTests.java", "b5d0874db907e36dcef95d07f8ff8614a4267b6a3279bb12e193142d500dd6ad", [
    ["simulationCachesProviderFailuresAndNullResultsOnlyForOneBroadcast","d004c100cab7a682c4d591c0eaf2d6f476bdedaccc364ff45125d96a02a537e1",{"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":15000,"forcedShutdownMillis":3000},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"dynamicNodeCount":4}],
    ["liveBroadcastCachesProviderAndSerializationFailuresAndRecoversOnTheNextCall","da0030554f6e9f44524e10ac470427e204b1db3bda6b56332482e4e5ffdc6233",{"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":300,"forcedShutdownMillis":1000},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":15000,"controlComposition":"REVIEWED_DYNAMIC_NODE_MAX","dynamicNodeCount":4}],
    ["simulationLogsGenerationFailuresOncePerKeyWhenTheHandlerIsAbsentOrFails","e0a1e89045f95f953311228e80aef2bbc55f8dadb2561cad29614473a78b6d97",{"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":15000,"forcedShutdownMillis":3000},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"dynamicNodeCount":4}],
    ["simulationConsumerFailuresDoNotInvalidateTheSharedPayload","58039a87fca7632e8e9b4769c9d48bae0757e31140957b1168d164dbde634d36",{"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":15000,"forcedShutdownMillis":3000},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"dynamicNodeCount":2}],
    ["simulationKeySelectionFailureRemainsPerClient","b98d982a7abdf89503cf4e9d7bc22e927da596b6f40ad0e94c5061bf2878441f",{"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":15000,"forcedShutdownMillis":3000},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"dynamicNodeCount":2}],
    ["liveKeySelectionFailureDoesNotAffectOtherClients","1e45eb1e57a856745e67b472970305d59c0cf568211992b1eb5365ea0fe351c3",{"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":300,"forcedShutdownMillis":1000},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":15000,"controlComposition":"REVIEWED_DYNAMIC_NODE_MAX","dynamicNodeCount":2}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SseReadDeadlineRuntimeTests.java", "d953b77ad1f3c3856a7507710b634e92f5100f44d1b0c5bf0f4f4a29f263bb9c", [
    ["headerDeadlineWinsRegardlessOfHandlerTimeoutOrdering","b7344152b0556816ed727543aa9adba645fdd7077d63c4a55207c859d1927360",{"phasePolicy":{"startupMillis":2000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":4000,"controlComposition":"REVIEWED_DYNAMIC_NODE_MAX","dynamicNodeCount":3}],
    ["idleReadDeadlineClosesWithoutFailureEvents","c165e9158ee7949941b920cbdee64510b26d10369c5d6ee7005b6cb48c1a6d82",{"phasePolicy":{"startupMillis":2000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":4000,"controlComposition":"REVIEWED_DYNAMIC_NODE_MAX","dynamicNodeCount":2}],
    ["eofBeforeCompleteHeadersIsAQuietClientDisconnect","9d68fd7ded2bfb5af7237e324e1be96197f35c65a8af50d456664fdbdd7081b3",{"phasePolicy":{"startupMillis":2000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":4000,"controlComposition":"REVIEWED_DYNAMIC_NODE_MAX","dynamicNodeCount":2}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SseTests.java", "3ffc6f689adc1cd9a6ec3de32ae5736ea6566a5c43ba0ae471201e1247a4554c", [
    ["sse_clientClose_setsTerminationReason_remoteClose","4bd45164be475cc1677da869704de8c9ba12937a6b17196e594fdf826a681a64",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":15000,"forcedShutdownMillis":3000},"controlJoinMillis":23000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["sseHandshakeClientCloseDoesNotRecordTransportFailure","af57cd43412bbd148c34640e72d9961279fff2a1feaa9b3be871e996fafaa2a4",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":1000,"gracefulShutdownMillis":3000,"forcedShutdownMillis":1000},"controlJoinMillis":12000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["handshakeTimeoutApplicationCallbackDoesNotBlockSharedScheduler","9636991901986ec28f12c03e0f399d109b2dfd0628cf29e71c3e01600ed9171e",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":15000,"forcedShutdownMillis":3000},"controlJoinMillis":17000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["sse_backpressure_setsTerminationReason","8ee38236534cca283a5e27441a514eec1944c6e9f3da7ca0837881f1f53a3cb9",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":15000,"forcedShutdownMillis":3000}}],
    ["sse_stop_setsTerminationReason_serverStop","ca9fd6ce8336878273cfab3f37effe222c749bda4a78bcd50e451fb8917cfbf9",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":15000,"forcedShutdownMillis":3000}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/StreamingCleanupDiagnosticsTests.java", "4819471ec5dc9766f39d5dbaeace72d842cf448a1691155e5990e56c633aebfb", [
    ["abortFailureKeepsThePreviouslyElectedTimeoutAndReportsCleanupContext","04d7cc26bbb2a245163ac995a99ed76cf7a7455e9f4a8bd3245af2cfa27a3048",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":17000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","phasePolicy":{"forcedShutdownMillis":100,"gracefulShutdownMillis":100,"startupCancellationMillis":2000,"startupMillis":30000}}],
    ["cleanupDeadlineStillReportsFrameworkSupervisionAndRetainsPhysicalWork","e78851cbbf9c8a8e9d86f9ded8679fd2eef3dd79f0e3e1ceb5ebb79e45bb7584",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":20000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","phasePolicy":{"forcedShutdownMillis":100,"gracefulShutdownMillis":100,"startupCancellationMillis":2000,"startupMillis":30000}}],
    ["untypedUpstreamExitAfterCancelationIsProducerEvidenceAndNotACleanupFailure","7e9ebf1acc05546b3156cc325a3f65953347f6d40ca59af313ebe8a3c271ad6a",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":14000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","phasePolicy":{"forcedShutdownMillis":100,"gracefulShutdownMillis":100,"startupCancellationMillis":2000,"startupMillis":30000}}],
    ["ordinaryWriterFailureIsStillAProducerFailureWithoutACleanupDiagnostic","773c3104f25386a6e9a023bc7e885000d43366b6201393e578044a75535bb309",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":12000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":15000,"forcedShutdownMillis":3000},"requiredAction":"RAISE_OUTER_BOUND"}],
    ["applicationSocketFailureAfterCancelationRemainsDiagnosticEvidence","5c2e717307fa2d635dc289cf2a6c4b987fce775554b041b97e6f402eb194f8d5",{"controlJoinMillis":14000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/StreamingLifecycleTests.java", "108c4bd74abe853b35ee2c12c3334aa51e93cb01d49de71a6cca688cccb72727", [
    ["lifecycleCapacityRejectionReportsActualFiniteResponseAndOneStreamTermination","ef948a54768ad9c783361434b28bcb6c9d408b083c602146a1960a91053dfdf8",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":200,"forcedShutdownMillis":200},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":9000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
    ["defaultProducerCapacityNeverCommitsAStreamQueuedBehindHeldProducers","ee714ae2b4c3c3525ea897ff1d0769ebe62385a11c4a98368b3003fdddb9fada",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":200,"forcedShutdownMillis":200},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":12000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
    ["suppressedHeadAndUnsupportedProtocolReleaseAdmissionWithoutAcquisition","7acb73790da9e7b43f2a7ad30a3f270e1adac97eef57c49f0ea4aef4d9920e34",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":200,"forcedShutdownMillis":200},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":10000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/StreamingObserverIsolationRuntimeTests.java", "741c079cea7e65eeae013f8e25e285d11f1ae9a8fda6910218e421fd1656837b", [
    ["blockedHttpCancelBatchLeavesHealthyStreamingAdmissionAvailable","ce29233199d68b3b5e0cd3baa9de7885dc4023f07770d9be9e1bcc3ed6a3d925",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":65000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","phasePolicy":{"forcedShutdownMillis":100,"gracefulShutdownMillis":100,"startupCancellationMillis":2000,"startupMillis":30000},"requiredAction":"RAISE_OUTER_BOUND"}],
    ["blockedHttpTerminationObserverLeavesHealthyStreamingAdmissionAvailable","6eb9d5266f54d15fcd37446a8501c5188230d088b4d71f3ea7e0f6159b453a60",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":65000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","phasePolicy":{"forcedShutdownMillis":100,"gracefulShutdownMillis":100,"startupCancellationMillis":2000,"startupMillis":30000},"requiredAction":"RAISE_OUTER_BOUND"}],
    ["blockedHttpCleanupLoggerLeavesOtherCleanupDiagnosticsDeliverable","35dc23ab38a0006b73f498a5a8dc9ca162ae892eff0111a73696cabe1f86bdf4",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":65000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","phasePolicy":{"forcedShutdownMillis":100,"gracefulShutdownMillis":100,"startupCancellationMillis":2000,"startupMillis":30000},"requiredAction":"RAISE_OUTER_BOUND"}],
    ["simulatorWaitsForItsOwnObserverWithoutBlockingOtherRequests","577ec13b15ef4a198f608b963cb91807d490aff8fc9f11b36b9ea7f831f1709e",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":30000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","phasePolicy":{"forcedShutdownMillis":100,"gracefulShutdownMillis":100,"startupCancellationMillis":2000,"startupMillis":30000},"requiredAction":"RAISE_OUTER_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/TransportStartupFailureTests.java", "2be9b16a8a00acc97153e6802d94db505f2dea77ab0a305b084b02b2a97346cf", [
    ["httpDirectBindFailure","e6d6f31185575540dd922c5b9a4f6c25f49004ab9ff1a1149b78fabb937ef22b",{"phasePolicy":{"startupMillis":2000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1}}],
    ["httpTransparentBindFailure","90381426992711494e73c94a359572da2004bb5668b7f023cda056e41be0ee89",{"phasePolicy":{"startupMillis":2000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1}}],
    ["httpOwningBindFailure","56dca1623bb70091216f325fc012f8931ea5366e4d2faede575ad67c657195d9",{"phasePolicy":{"startupMillis":2000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1}}],
    ["httpNestedBindFailure","223a63a52dbf71b4a0deeec99f71fdcb2056cf09568a72f52fbe0cd7a0cf604f",{"phasePolicy":{"startupMillis":2000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1}}],
    ["sseDirectBindFailure","da8044ebd0d084eb3c3e9f34ece1f669a189c6dddc9e695b78eabc81582eccd7",{"phasePolicy":{"startupMillis":2000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1}}],
    ["sseTransparentBindFailure","461ad5202c16fee7e9bc41c6e5eeb66f706e5f193b2ef1c09199ae49c26af8a8",{"phasePolicy":{"startupMillis":2000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1}}],
    ["sseOwningBindFailure","e50bf96441e78481cfa875cee9d7503a70493d9cf7cfde4c3c633172a1813f67",{"phasePolicy":{"startupMillis":2000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1}}],
    ["sseNestedBindFailure","9b014ed670c2efe5138bf48a2fd9e69a3e107dae2a3711e454ef20694a7d647e",{"phasePolicy":{"startupMillis":2000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1}}],
    ["mcpBindFailure","7fb77915793f25c56ba8c3749ed684e0a58324d72871f53a4f81d8844558afd8",{"phasePolicy":{"startupMillis":2000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1}}],
    ["applicationUncheckedIOExceptionIsNotUnwrappedAsAFrameworkAdapter","b59d9cc229535c88c9c239a1d8cc8129844d8921b60bd62870b593bcecffbdef",{"phasePolicy":{"startupMillis":2000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1}}],
    ["customStartThrowWaitsForRollbackToReportProof","fda539caf80f0f29c155556e406c8e02dc49e2d5b9a7ca8457b63210c976eadf",{"phasePolicy":{"startupMillis":2000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1}}],
    ["independentFailureBeforeStartThrowRetainsItsControllingEvent","df6e1a6acaaf753aeb2d46dba6a6a75a3b3ded4a9b3e8f61b8c93af3c5631dd6",{"phasePolicy":{"startupMillis":2000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/UnparsedRequestAdditionalTransportTests.java", "35e8e276f824d85d8b1aa58635ed2927f31e8f35f676a6353b20a51df86f270c", [
    ["uncooperativeSseUnparsedHandlerPreventsCompleteShutdownUntilItActuallyReturns","d9b4e6ed7aa251c880e91b2a8fd487197d8afcfddbed4d6924389f4d74a676f4",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":6000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","phasePolicy":{"forcedShutdownMillis":1000,"gracefulShutdownMillis":300,"startupCancellationMillis":2000,"startupMillis":30000}}],
    ["stalledSseMarshalerReceivesInterruptionAndCannotWriteALateResponse","87ed24fccb4019d4774cb0c9793d2894fd78739c313b5e43b59c3b8340e4965f",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":6000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","phasePolicy":{"forcedShutdownMillis":1000,"gracefulShutdownMillis":300,"startupCancellationMillis":2000,"startupMillis":30000}}],
    ["laterHttpConstructionFailureUsesItsOriginalRejectionWhenMarshalingTimesOut","2b59668b15c8503ebaf8a6dd57b09d551f76ccdd42b89022b09ded82c2afccba",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":6000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","phasePolicy":{"forcedShutdownMillis":1000,"gracefulShutdownMillis":300,"startupCancellationMillis":2000,"startupMillis":30000}}],
    ["httpRejectionsBeforeRequestConstructionAreCustomizable","d7a103e98f0ba63915854aea21568e76029a3407fb07596e7dd1d50b32cbc779",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":2000,"controlComposition":"REVIEWED_DYNAMIC_NODE_MAX","phasePolicy":{"forcedShutdownMillis":1000,"gracefulShutdownMillis":300,"startupCancellationMillis":2000,"startupMillis":30000},"dynamicNodeCount":8}],
    ["ssePreRequestRejectionsUseTheSameHook","15b985c8b5d2bd1307582248ecfccdeea8dcf9035486326ef93cf108e661b10c",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":2000,"controlComposition":"REVIEWED_DYNAMIC_NODE_MAX","phasePolicy":{"forcedShutdownMillis":1000,"gracefulShutdownMillis":300,"startupCancellationMillis":2000,"startupMillis":30000},"dynamicNodeCount":7}],
    ["sseInvalidOrFailedMarshalingUsesTheOriginalFallback","f8092fd48c3dbb879870c5f4b9844784b3cfb406f5ab670fa79b84a5f6356f4a",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":2000,"controlComposition":"REVIEWED_DYNAMIC_NODE_MAX","phasePolicy":{"forcedShutdownMillis":1000,"gracefulShutdownMillis":300,"startupCancellationMillis":2000,"startupMillis":30000},"dynamicNodeCount":3}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/IntegrationTests.java", "738a71ec0daa2e1412aedbebbe40efbeea23f3f180602776668ae72eb6d9d61f", [
    ["requestHandlerQueueCapacity_rejectsWhenFull","90ae620db6e48ed8508dc765295f665e7bf7ce34e431a70db866385a67901565",{"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":15000,"forcedShutdownMillis":3000},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":11200,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","requiredAction":"RAISE_OUTER_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpHandlerMetricsObservabilityTests.java", "8d3f0f334bacf0ec22eadbcaee75c3762e860036a3b6eb87a894e53f469caace", [
    ["queuedDisconnectDequeuesWithoutStartingHandler","df914124553ef923f5a9f5fdb5856b9b2695dc0ec87ccc96af6ec85f595ce1d0",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":43000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
    ["managedStopDefersQueueAndExecutionCallbacksBeyondLifecycleLocks","7c141d8300239299c62293fa3562bfecd96042ddf000e1c8b3f69add97bd77b2",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":40000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpLocalizationFleetPublicRuntimeTests.java", "913ab645cc78806ea13ec1abd3797d9244f1301adccc8fcfbacfd64c4ef37f1d", [
    ["applicationBrokerRecoveryCoversDelayedDuplicateMissedAndRevokedFleetState","59d163ef25e7cc1c015f64cdaf735f7154292665c136c13480c2eed4fdb712cb",{"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":1000},"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":179000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","requiredAction":"RAISE_OUTER_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpMetricsEventDeliveryPublicRuntimeTests.java", "58574faf8e196d18a93425c340ce748972e1c3a7d908fa5872d5f88998098bd6", [
    ["lifecycleObserverFailureDoesNotControlStartedStoppedDelivery","3b3946be473d7c5aeb982fd384c9ad0bd744955148b5079857c7807c40909125",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":30000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":1000}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/Round2HttpStreamingRuntimeTests.java", "6ca223831871d7007f859ebfb224455b6ddef5b243353c91303e0d612d645fd1", [
    ["abortedFileDownloadDoesNotBecomeAServerTransportError","ad411c907278c6122463d8ec4f080b23469a704c5d7cf7329c752046a1c8677e",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":10000}],
    ["zipCleanupAndInterruptibleUpstreamRemainQuietAfterLiveDisconnect","02f97e2ebf2e2edf2548ef8f6c97b809c36123818c9025c61b87787bcaca2dd4",{"generation":{"count":4,"mode":"SEQUENTIAL","complete":4,"prior":3,"incomplete":1},"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":49000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["lateFiniteAndStreamingHandlersObserveTheTimeoutResponseWithoutStreamAdmission","1cb6cd8de5f98d6982004f53edf48bf319e4a6c694e2975b1a7fcf228e9dbbdc",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":20000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["throwingWillWriteObserverStillWritesBeforeFinishForNormalAndProtocolReplacedStreams","d9ce1223806780d5d2c590aa6ecac747b6ac2f949e89b886182ad7410b9346bd",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":16000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["classicSocketUpstreamInterruptRemainsQuietAfterLiveDisconnect","f716e5e57b0c72cef7d829c7f3a0af42bb2664a1a5ee083d53d6060198ffe3ff",{"controlJoinMillis":10000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SimulatorStreamingContractTests.java", "6b1dff4700bbc408b3b43432860a664a9a19c9909c3a8cf43556734855d4dc93", [
    ["interceptorOnlyStreamSupportsAbsentResourceMethodInBothRuntimes","665e1cf6eed9c4df125fca6ebc46eb8bc2979712da995ab6ff516068f08669a1",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":15000,"forcedShutdownMillis":3000},"controlJoinMillis":5000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SokletApplicationProcessTests.java", "85b493f9d67f53858a76316793df4c88ffdde37b9e0db8d487880f2cab0c8cc0", [
    ["hookAndEnterRegistrationPrecedeStartAndRemovalFollowsReport","3286a59892743de5665d014a749c793046b2ada70b6c37e469aff5e2831ff3df",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":20000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlledLifecycleCoreMillis":0}],
    ["hookBeforeStartClaimNormalizesNotAttempted","382b252d9a753dffd9fcb54f531afe153f9727463056f712076df669c31111a9",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":25000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlledLifecycleCoreMillis":0}],
    ["hookDuringStartClaimNormalizesCancelled","ffd8e96a06e3c47fa0e25129eb5d53696b9120b488e025d6e9edee9c6687c3bf",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":20000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlledLifecycleCoreMillis":0}],
    ["explicitShutdownDuringStartReturnsCompleteCancellation","85b1c1e13aab1acb901e25b1ea3065ca2babacd4d670633e47ed35e6815fefd8",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":15000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlledLifecycleCoreMillis":0}],
    ["triggerCancellationWithIncompleteRollbackThrowsIncomplete","e47e66eb1b2d3a6228626b9f96fb8df9096603011100242d5cde326df0e5d30d",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":20000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlledLifecycleCoreMillis":0}],
    ["runnerAndHookShareOneCleanupAndReporter","5ff144f1cc32d58a28de7214388594f8e9e9983c7f4e7a08caa5c714a87c832c",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":22000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlledLifecycleCoreMillis":0}],
    ["hookRemovalWaitsForReporterAndConcurrentShutdownRemovalIsBenign","df597b43dfd94d51b00f67704f6ba6faac9208bdbb5205f93b8f244cc9b1c2e6",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":25000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlledLifecycleCoreMillis":0}],
    ["runnerInterruptionRequestsShutdownAndIsRestoredOnReturn","e757b40fac4bca38c9628bc4f7cd862d289e0cc2e666950e7e8e5c8b67b9b4dd",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":15000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlledLifecycleCoreMillis":0}],
    ["incompleteResultSkipsConfiguredCleanupAndThrowsExactResult","e4b7a2f3c0307c02ba9c4ab60db76d1de35f9f546e3bf7eb421dc53d992ede33",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":15000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlledLifecycleCoreMillis":0}],
    ["unexpectedTerminationRemainsPrimaryWhenShutdownIsIncomplete","5d82493f1350d5988b055e9d82151cd9e1a1fffbfff479a473bb6e01668cc8cc",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":20000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlledLifecycleCoreMillis":0}],
    ["expectedCompleteCleanupFailureIsTheCallerPrimary","4ad4805e82f20dab7389ac2767a931bc9cf843832eebe9823d731ca189b28114",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":21000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlledLifecycleCoreMillis":0}],
    ["unexpectedCompleteRemainsPrimaryWithOneCleanupSuppression","aa453e658a7055358993ce1c0040d967a962c64252d0a8cf6da7f0326fc278cf",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":21000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlledLifecycleCoreMillis":0}],
    ["interruptedRunnerRestoresFlagWhenCleanupFails","c4d7b066e9c894803bfe07396e77de0a15a42e62f19f9fb91d32e76d0d67ef43",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":16000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlledLifecycleCoreMillis":0}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SokletDirectTerminalPublicationTests.java", "b24ad641b84b61b0ba009ab12e45a7e5fdec3df39299b2ff0b913a66e745351f", [
    ["minimalViewAndDetachedMirrorsCannotControlOwner","84ba72162352e5ba9ea4dad8e5f8f0127771115d4c247dc8f1e047ba283d98a1",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":20000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":1000}}],
    ["publicContinuationsPreserveExecutionAndFailureIsolation","7c531b1ae531cc4ee5fe5514ac574e1ac550e5d9a1f71e97be0e3d9b44900fc4",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":24000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":1000}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpSubscriptionAuthorizationPublicRuntimeTests.java", "1cf21fc9f03f4915e74b1bfadb8c5434ecba96ea41b318dceb3b1e4efb52a7db", [
    ["nullInitialAuthorizationFailsClosedWithoutQuotaLeak","03f336138f700296f4bd30b11639e62949abb35cf4bb2b3693cfad6cb6bc687e",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":1000},"controlJoinMillis":5000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["thrownInitialAuthorizationFailsClosedWithoutQuotaLeak","280b3a69f8007d5ee99229e1269511197cf9b0345728a202e67e3a260eb3b87e",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":1000},"controlJoinMillis":5000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["alreadyExpiredInitialAuthorizationFailsClosedWithoutQuotaLeak","19fa99676358294e3b2ac0c0ec9dec662683a7739600d96f0d663703722b038a",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":1000},"controlJoinMillis":5000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["deniedInitialAuthorizationFailsClosedWithoutAcknowledgmentOrQuotaLeak","4c97e47f2834622a3352c689fe4497d1b4e0ab69764a834062859803e2029c8d",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":1000},"controlJoinMillis":5000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["maximumAuthorizationDurationClipsTheApplicationLease","fbe4d1067c7ca5b8d9a1cdf99b6e5a07e4ee7d0bc45e7437563f240d7fe5ac19",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":1000},"controlJoinMillis":10000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["automaticRenewalRunsNearHalfLeaseClearsContextAndRetainsFilter","6bb33d9a20a8b65d33ffb4d9ab722d8cc9fbc7db5d4c82f383695cf170dad2c1",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":1000},"controlJoinMillis":20000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["ordinaryBlockedRenewalKeepsDeliveryUnderThePriorLease","089b578b7ef44178b0e9b6fa715c1784bdcd09063b7efa484a975922b7620456",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":1000},"controlJoinMillis":21000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["sameExpirationRenewalDoesNotScheduleAnotherRenewal","79f6d8331a68f70e95af75e2fd3eefeb893fd471068947154de51cbe569d51b8",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":1000},"controlJoinMillis":15000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["simulatedReconciliationDenialPreservesTheCompletionAndCloseReason","0d985e95d9d84a061e63ca7d1daa8d6b244077601012858e3759ba9ec5a59fff",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":25020,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["disconnectWhileTheClosingFrameIsReservedKeepsTheTransportReason","fd02b8026deda77af8a68bcedefe1aca63235b10392d39c08a3f20bf239ef25a",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":1000},"controlJoinMillis":25000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpSubscriptionResourceCatchUpPublicRuntimeTests.java", "08cde547e4da593fd047a32dd118139b958b158a9836d22d5b31b99c9d5d4ea9", [
    ["renewalReplacingRegistrationDoesNotLoseResourceOffer","40e55befe6e3ba5641ed7071ac89d337f1e6faf5cc2bcb66c51857d7684092ba",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":1000},"controlJoinMillis":20000,"controlComposition":"REVIEWED_OVERLAP_OR_DUPLICATE"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpTaskSubscriptionPublicRuntimeTests.java", "b2652db11f4040068949c539e11790988dde38bb11ba25cb8c8ff041eaffec9c", [
    ["taskNotificationUsesCurrentCatalogPolicyLikePolling","5483fbe56282524819a56834bc8fcb015046dd5830517f2877982e29b6381714",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":15000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["missingOriginSuppressesCompletedNotificationWithoutMutatingStoredTask","06c835a0a39f2c7ecbc478a5af214beb26e3466f0740416f8e671f9e50067d6b",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":15000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["taskAuthorizationUsesCredentialFreeReplacementContextAndCannotExpandOnReconciliation","4462c04fcc7e3de127751bf6b462bd09f726fe886d869b1322f319f31b7a5a28",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":20000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["reconciliationFencesOldTaskContextAndDropsOnlyRevokedQueuedTask","2e753164eae9d5dc174291d4a618a4a2cc6ae54c7b32e4358e7fdc1657a78176",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":35000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
    ["transientProjectionLookupFailureKeepsSubscriptionOpen","df6dc54d6f196bf347d41989b80b6809e25ab85ca5fa1b12784bb39d58fecedd",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":15000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["duplicateEventsReadCurrentTerminalAndSuppressLaterStates","0b0669ac93c458c2fbcbdd0f36a1214e5fe54a0bccfd2bcab969494be1e3068f",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":30000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
    ["disconnectDuringActiveProjectionReleasesSubscriptionAndRecovers","d129145249ab7d6ac49510cf4a8f98e1c733a3798bd6aba306c6a815b2e91269",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":40000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpLegacySessionPublicRuntimeTests.java", "960ff8eda225d5cf9230969a64b7bdef9bcb764b2eceaee03e2b6f1726313def", [
    ["explicitCancellationCompletesFiniteAndCommittedSseBodiesWithoutAResultAndRetainsPhysicalWork","4f9b841582ad6d2d41d5a00efd68a950adfb1bbbc9c002770e4a6a649ceb1354",{"generation":{"count":4,"mode":"SEQUENTIAL","complete":4,"prior":3,"incomplete":1},"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":73000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["activeIdCollisionsAreTypedAndCancellationIsBoundToOneOwnerAndSession","423060295b571f713ab9ff00aa6b13d50404eca26a091c45826f89edc5a4ecd3",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":65000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["resultReservationPreservesQueuedTerminalBytesAgainstALateCancellation","fca641c099e2b3143c9662ca04a68a514024fe79f0920f8ad16b3a6662ef1c67",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":70000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["verifiedControlsRemainAvailableWithFullHandlerQueuesAndDeniedOrdinaryQuota","29757ac1fab01876daeb1bda2fd81fff81648c14f2e8aa17b4d241055481da99",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":73000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["verifiedDeleteTerminatesActiveAndQueuedCallsWhileOrdinaryHandlersAreFull","88c4ab68e9b3ce0f2651fdc9a73f9b05f65397d5cb90836fe506fcf71eb046a5",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":73000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["correlatedLegacyDeadlineErrorsUseHttp200WithAndWithoutSessions","4fb1635ca29e31f70f3541340dee1c5739381bae23829c639889f40a4e75113e",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":60000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpLegacySessionTransportPublicRuntimeTests.java", "e42a66fb27a96a21b2091795c5e3f862250726d4e707cc36765b31a37b4d2b8a", [
    ["blockingHttpFinishObserverDoesNotStallUnrelatedPostAndRetainsCallbackOwnership","2d3656eb3fd5eb1fcf8edab8721af5cd3d5101e59e504184ab837db82787975f",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":15000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
    ["transportEndDuringBlockedHttpStartDeliversFinishOnlyAfterStartReturns","50b2e9d90be6efd2c20e54ae21c171db4ccd6e6c8ac50fbc80d6af4319f91e50",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":10000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpLegacySessionTransportSimulatorTests.java", "e174ffad2a107f18f34da76b9950b6690fe3b5e24d3c693ed2049403534a53b6", [
    ["simulatedLegacyGetCapturesKeepAliveOnlyAfterTheConfiguredIdleInterval","41f175222888afb45cc526d3fe8a0bc435a87dddc90b62b1c91c5b33b552ef87",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":34800,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["getAndDeleteSnapshotsUseTheConfiguredEndpointInSimulation","be4564581a7e95cacebed268fcac0a0d075f99397ae267c582f90e3bb03eb773",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":70000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["singleLegacyViewSupportsHeaderlessGetAndDeleteWithVerifiedSession","7e02fbee5efaa38616fdafc3f19ed302a80946fce09be017a2951e1b4def66e5",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":60000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["rejectedGetPublishesAFiniteEmptyResponseAndDiscardsTheUnopenedChannel","9a5a1879012fb97eb6285df8111ad5264dba9ddd3c7c9700204947c140cc493d",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":80000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpLegacySubscriptionPublicRuntimeTests.java", "6bf7797a873acb9f979c1aed6bfbbf2e596c3edf2924965e754f60f0ec1f612e", [
    ["failedFirstPagesAndSuccessfulContinuationPagesPreservePendingCatalogHints","093f3da6544ecc65cffca6a1a471b5ad400c77d516cc3670926c07c58939dc7b",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":120000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["aChangeDuringSuccessfulFirstPageProjectionRemainsPending","0c5dff16425ee74c5cdd5ed87ce6c4fbce61bb2e88e427d2edf68e14a48d9abb",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":60000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["failedDuplicateSubscribePreservesTheEstablishedSessionAndRenewsItsFencedGrant","bb1ec6568822d435260d16143c65b38e8669120d37963c4aef2e77427c2c311a",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":100000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["liveNotificationsUseOneStreamAndDoNotReplayOnNewGetOrReconnect","26d952eec85afe3ff659858054c7e3c640970f1073790531fd5aa46086c38a37",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":61200,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["duplicateResourceFlushDoesNotShedTheWinningWriter","492c121e4cfd6b4a88aa9c17d8fc920444dfc7212103c190458d0b7c6628e8e5",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":60200}],
    ["duplicateCatalogFlushDoesNotShedTheWinningWriter","0df467edbcc56dbf9eedd81d0eb576c7accbd26323fe1653cdec2bf764008520",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":60200}],
    ["transientUriAuthorizationFailureRetriesWithDeliveryFencedAndPreservesDirtyUpdates","0c09b6a8c5844bfccceb1d4550f394d3049d716ad3bb416e84eb91e247cdb89f",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":92300,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["threeConsecutiveUriAuthorizationFailuresRetireTheSessionAndAllowFreshInitialization","a1305ed04d05a9d4c6f4973067a1eb31cf0c4913be44583fa82b83ebc8ff12f6",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":130000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["ignoredTimedOutUriAuthorizationCannotExtendItsLeaseOrOverlapAnotherCallback","7aa90f088b8b0abf0488847d5876db90df580ef758c200e6fad20a737393c29b",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":104000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpSubscriptionShutdownAdmissionTests.java", "2cf1ac1dfcc8a0792e0d76cf633df775dada50a3bea8431cce010492e31ee2be", [
    ["legacyGetPausedInCorsCannotOpenAfterQuiesce","9c91add3dd03c97ac0a5a8ac20bd8c06c134d6c76bfb2029e6c95d9c95ee86e7",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":1000,"gracefulShutdownMillis":3000,"forcedShutdownMillis":1000},"controlJoinMillis":110000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
    ["legacyGetPausedInAdmissionCannotOpenAfterQuiesce","ff84e1b1b4ba6137b0ddadec1281abf5264077cd1cb19bb13b1258ddf88d7751",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":1000,"gracefulShutdownMillis":3000,"forcedShutdownMillis":1000},"controlJoinMillis":110000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
    ["legacyGetPausedInOwnerResolutionCannotOpenAfterQuiesce","7cf2b2874a29ab0cf6791117458c1e7d4e31f255da6e4e4734a91f02f8b38067",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":1000,"gracefulShutdownMillis":3000,"forcedShutdownMillis":1000},"controlJoinMillis":110000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
    ["queuedLegacyGetIsRejectedWhileAdmittedFiniteRequestStillCompletes","7464114f8ee5411136c055feecd3371fcc464b0f941e56e5c328b32c091498cf",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":1000,"gracefulShutdownMillis":3000,"forcedShutdownMillis":1000},"controlJoinMillis":75000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
    ["establishedModernAndLegacyStreamsCompleteGracefully","df9e840152e871a11d31832325f6f279f49f598522ed46009c9df2a1b80ced14",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":1000,"gracefulShutdownMillis":3000,"forcedShutdownMillis":1000},"controlJoinMillis":70000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpHttpServerRequestScopedSseTests.java", "ee9b77c5b93c44597eca3fc49059bdbc549c44f0ea9ee93de51af0ff52645c69", [
    ["finBeforeHttpOfferCancelsAllocatedStreamInEveryRevision","eb7a3f9811e7bed92787ab9999015e0d6274778cdae467e2d020ff590035c960",{"generation":{"count":3,"mode":"SEQUENTIAL","complete":3,"prior":2,"incomplete":1},"controlJoinMillis":105000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlledLifecycleCoreMillis":5000}],
    ["response_write_idle_timeout_closes_stream_and_interrupts_handler","9ba343a32dea57416c7923e6f2e468caff91c904b0d3d93728d26a02bc6e6ba3",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":30000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlledLifecycleCoreMillis":5000}],
    ["generic_stream_termination_discards_losing_write_timeout","75df34b480b69d2c3b023f70a681e335478a015645cee65415d0088e8a5ab051",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":30000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlledLifecycleCoreMillis":5000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpHttpServerRuntimeTests.java", "d3a8851fe9cefb26914565a1454e2e6745929ad36ca9e8857aedde74c831ca64", [
    ["mcp_and_ordinary_http_listeners_are_independent","5bc7a723964f12433646cda6ab8eb82a101607863430ed2d41beabf91d3f8244",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":15000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","controlledLifecycleCoreMillis":5000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpLegacyProgressPublicRuntimeTests.java", "5460c7c138801772481195c0ceaa0e99d904ef9834a0a7084f41a7d1119dfe93", [
    ["inputEndDetachesLegacyWriterAndRetainsUncanceledPhysicalWork","3671d0c30b6a2960cbd734c6948ba71c7a6388b3711b8ba600a7601cc853996d",{"generation":{"count":4,"mode":"SEQUENTIAL","complete":4,"prior":3,"incomplete":1},"controlJoinMillis":68000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":1000}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpSubscriptionAuthorizationSchedulingPublicRuntimeTests.java", "f63867626226c5a1510191292314156b4ae76615edb9fcc06a5ded2401edc813", [
    ["renewalWorkersReserveHalfOfTheSharedBudgetsForOrdinaryRequests","728f8252df57dcf6e0b93357af93179eefd2755a6c9e7adaeb1b5e1fdd7ba3a7",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":1000},"controlJoinMillis":3000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpSubscriptionAuthorizationTerminationPublicRuntimeTests.java", "1468e0ce4006cf134d2e8be901ba19342557c3353ad9459d1c0697fc06f591ca", [
    ["clientDisconnectCancelsResistantRenewalWithoutAuthRevival","beb00152b349f2876b87e45a63bf9863aebf13611c5f18dbbfa81209503c83a4",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":5000,"forcedShutdownMillis":2000},"controlJoinMillis":35000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
    ["serverShutdownCancelsResistantRenewalWithoutAuthRevival","80eb4e5925f4561a36c744a3019ed435fcfdd29a64f165183078500c739a4647",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":5000,"forcedShutdownMillis":2000},"controlJoinMillis":40000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpSubscriptionAuthorizationTimeoutPublicRuntimeTests.java", "d004ff8496cde85027e8c7ccfb34edca2054b869aa27374e908d057068b33843", [
    ["boundedAuthorizationCapacityRejectionDoesNotInvokeCallbackOrLeakQuota","2764247a4e8a1f5262de2f9f70bbc9cebb3e5334bb9e9c78c8da0b5145383008",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":1000},"controlJoinMillis":20000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpTaskSubscriptionPublicRuntimeTests.java", "b2652db11f4040068949c539e11790988dde38bb11ba25cb8c8ff041eaffec9c", [
    ["taskNotificationBackpressureClosesStreamAndReleasesCapacity","6c2e522e3fb86bb4e7a44c820231f4181a0b2890ecc762f7b1b15e63537321f4",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":30000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpTransportMetricsEventRuntimeTests.java", "bf7438b46b521d74e9046c26b849dcc891316dac8708602711c4d586a5e2369e", [
    ["sameConnectionRecordsAcceptedBeforeRequestAccepted","6ff1379c52770b26d397515448ab7d8af9da0f7e07009effb61d41161f6459ab",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":10000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","controlledLifecycleCoreMillis":5000}],
    ["maximumConnectionCapacityRejectsOnlyAndRecoversAfterRelease","b875795ef960b15c302d3bec4a9222919561d59b804389e7f6aa387c29ad6259",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":40000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","controlledLifecycleCoreMillis":5000}],
    ["partialRequestReadTimeoutIsRecordedWhileIdleConnectionClosesQuietly","ca9cb58cac9161ee2ec82f450dbea165ba26e259b9b2a2af6602006c39b2c2cb",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":20000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","controlledLifecycleCoreMillis":5000}],
    ["partialRequestBodyTimeoutIsRecordedAtTheMcpBoundary","73e0fedf0e6d38cc228d51caafd14fa841632d1642be53cdb4e9c68899ef6ec3",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":5000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","controlledLifecycleCoreMillis":5000}],
    ["acceptAndSetupFailuresRemainPartitionedFromCapacityRejection","670b3d4535c653430baf9ae48ea5e73e7f3998514d27e94dfeacd4aa535c0555",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":8000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","controlledLifecycleCoreMillis":5000}],
    ["boundedTransportReasonsPassThroughWithoutUnboundedContext","51b023be447bf30d831caf9b03cd9b7ded4513d3656ae4cb2143f77db69dd6ed",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":5000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","controlledLifecycleCoreMillis":0}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpCompletionPublicRuntimeTests.java", "91232c12ca31b729c4207bd88bf38a4e4b5c611b39a051c1be0c540497c7fae8", [
    ["completionUsesExactRoutesAndNeutralErrors","0d92e9fc26889f9d2d932ab4916e337e821b608e96e96a1c93a4ef8cc6519011",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":15000,"forcedShutdownMillis":3000},"controlJoinMillis":70000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpHandlerInterceptionPublicRuntimeTests.java", "462850f5ca7f76d7c71139e42fd6cc35862c1d07fd22679415ca0cc901daabda", [
    ["everyApplicationHandlerUsesOneInterceptorWhileCatalogsBypassIt","9f3924fb7af548acc738fa7d3056956f835f6ad79df6c66370e9d34a40428e3f",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":40000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["interceptorMayShortCircuitBeforeBindingAndFailuresFailClosed","5a63e144fe8e343d01638669538142c414e44496ce15a4ab1287369ad12a854d",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":20000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["continuationIsOneShotThreadBoundAndCallScoped","2ab832dc87fa86144379931febc03f7d86a10bba88161fdaf383cae7ad48af7e",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":20000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpLegacySessionHttpRouteMetricsTests.java", "517e483b16c75095ba0a38c5eab92bb7c09213ae76f226b455ba5f87e2609c01", [
    ["acceptedGetAndDeleteUseTheSelectedEndpointAndReleaseTheActiveGauge","219d33a9ce1bdabfd51e53a633e87656052a428d04f53493e2858b938659bf09",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":66000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["idleGetsDoNotConsumeTheTransientObservationBudget","d30ca8318e69b0b37b34bdee633de47e9832080f1d2fc801ef389d43720d685d",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":63000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["rejectedControlsRetainMatchedRoutesAndSeparateEndpoints","c975b846008c833978e433d40f77d6413d6f830402f20a1b8e70fcae1a8f8000",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":66000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["ordinaryUnmatchedHttpRequestsKeepTheirOwnRouteEvenAtTheSamePath","ba2c34dc2cac5b43452a59f31a8968e8e292be913fad12eb2aeb63b834424431",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":18000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["customCollectorsReceiveTheOriginalRequestWithNoInventedResourceMethod","10f6a05d4f7be1427b79ef2bb2ed49bbdc17644f0719c3543b06e69585b1a352",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":18000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpSubscriptionCatalogProjectionPublicRuntimeTests.java", "a2e78349550ea76ea79a55f47f56dfad2d415b3e1b03c5b0dada83854f79633d", [
    ["coalescedToolOfferAdvancesBaselineWithoutLosingOscillatingChanges","9611981861741f1a4a6821faf6ef67fe239531870041d081466d3c895a964a8e",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":120100,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","requiredAction":"RAISE_OUTER_BOUND"}],
    ["coalescedPromptOfferAdvancesBaselineWithoutLosingOscillatingChanges","ed30f37dce99dc90e9e97574700e729b3c5c4989ec07337df383e668f65246c8",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":120100,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","requiredAction":"RAISE_OUTER_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SimulatorStreamingAdmissionRejectionTests.java", "71bf7711ef28039e3a9ac21eb6e3182b61ddb70d892977dc557c1deec34488a1", [
    ["exhaustedAdmissionReportsFiniteResponseWithoutAcquiringAnyProducerKind","c0c93862332d21aabb9adf33c259d6c4467d2a9a2c11dc794f1399a43ac1ebc3",{"generation":{"count":4,"mode":"SEQUENTIAL","complete":4,"prior":3,"incomplete":1},"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":36000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
    ["saturatedRejectionObservationStillReturnsFinite503AndReportsTheSkippedNotification","c7a64514c60f13a7bbeebf15a00ed13fbf6809bcda334ded147b4a9ad4b00b2c",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":9000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["rejectedRequestFinishesWhileRejectionObserverIsBlockedAndAdmissionRecovers","0f73520750092f81edfbc563b6aea79083ef0f0519a54dff66edd2f88d89415b",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":15000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  // One ReaderFixture owner; 16 reset-client connects plus one successful connect (2 s each), 3 s read. The shutdown future repeats the already-charged owner stop.
  ...reviewedScopeFile("src/test/java/com/soklet/Round2SseRuntimeTests.java", "3686c08a4a35b52dc4ab53522a4575976b542dda7324a8a8f19511e5bec76f4f", [
    ["bytesBeforeResetDoNotReportServerAcceptanceOrTransportErrors","ce7ebaa825bcc51b15d8b91a9cb5a121c6c88a35094b9e3f492f6cec4a6954d3",{"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":1000,"gracefulShutdownMillis":3000,"forcedShutdownMillis":1000},"controlJoinMillis":37000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  // One Fixture owner; a 2 s connect and 3 s deadline poll. No client body read is attempted; fixture close owns the existing lifecycle stop.
  ...reviewedScopeFile("src/test/java/com/soklet/SseHandshakeOutcomeRuntimeTests.java", "4d79a0f1defe79662dc46562e6cb5b5e1ee710bd2138c8e6ca81594e3ad31e85", [
    ["handshakeWriteDeadlineRemainsATimeoutWithOriginalSocketCause","4828d6afecdd739fef0de81fa5cadcaf7f838ad293bb0d49b4c54c62ebe14745",{"controlJoinMillis":5000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  // The helper marks a manually-created SSE generation ready and never calls server startup. Its only lifecycle wait is default graceful + forced shutdown (15 s + 3 s).
  ...reviewedScopeFile("src/test/java/com/soklet/SseTests.java", "3ffc6f689adc1cd9a6ec3de32ae5736ea6566a5c43ba0ae471201e1247a4554c", [
    ["socketOptionFailureAfterBufferedRequestAndResetClosesWithoutInternalDiagnostics","c525a8573435041d588d6dd80c82a3ed672fb73f62dc02242784b1d24c08496f",{"controlledLifecycleCoreMillis":18000}],
  ]),
  // The shared helper owns one HttpFixture: 3 s connect + 3 s socket read, 3 s terminal latch, and 5 s physical-exit deadline. Its producer latch is released by the elected timeout; the producer connect is inside the physical-exit deadline.
  ...reviewedScopeFile("src/test/java/com/soklet/StreamingCleanupDiagnosticsTests.java", "4819471ec5dc9766f39d5dbaeace72d842cf448a1691155e5990e56c633aebfb", [
    ["jdkTranslatedConnectInterruptionAfterCancelationRemainsQuiet","3f52ae74c718a74607cc8e3dcc8550f64687a59353e4ea51d9c45818eb045430",{"controlJoinMillis":14000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  // Same source-bound HttpFixture and control composition, with independent socket failure retained. Fixture close repeats the counted owner shutdown, not another generation.
  ...reviewedScopeFile("src/test/java/com/soklet/StreamingCleanupDiagnosticsTests.java", "4819471ec5dc9766f39d5dbaeace72d842cf448a1691155e5990e56c633aebfb", [
    ["independentlyClosedSocketConnectAfterCancelationRemainsDiagnosticEvidence","14626bc6205cb3bce0551177566b1844c6c5c2499eef3fafecf41205ad8fb023",{"controlJoinMillis":14000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  // One HTTP Soklet owner with the source-hashed helper policy (10/1/1/1 s), one 3 s client connect, and one 10 s aggregate deadline for all warmed samples. Per-read remaining-deadline checks do not multiply with requests.
  ...reviewedScopeFile("src/test/java/com/soklet/KeepAliveResponseLatencyRuntimeTests.java", "33d35d67b7bdabdd551fc80c83dd8245ef4ec0636d5709eaa523bbe35170ea60", [
    ["smallHttpResponsesAvoidDelayedAckStallsOnAReusedConnection","b914d84af56947a4c15f4fad0152522cb6c65c107d0cfa17feb0b821f70436f8",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":13000}],
  ]),
  // One real MCP Soklet owner uses the same 10/1/1/1 s helper policy, 3 s connect, and single 10 s sample deadline. PersistentClient closes the socket; the containing try-with-resources closes the counted owner.
  ...reviewedScopeFile("src/test/java/com/soklet/KeepAliveResponseLatencyRuntimeTests.java", "33d35d67b7bdabdd551fc80c83dd8245ef4ec0636d5709eaa523bbe35170ea60", [
    ["smallMcpCatalogResponsesAvoidDelayedAckStallsOnAReusedConnection","144de2577c1b4459d0ea1e74fbb2b3674661fbe8e35169184d200a84b8d28523",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":13000}],
  ]),
  // One HttpFixture owner with inherited startup/cancelation and 100 ms graceful/forced limits; 3 s client connect + 3 s response read, 3 s termination latch, 5 s physical-exit deadline. Producer connect enters with the elected timeout interrupt already set; its exit is supervised by that existing deadline. The loopback ServerSocket is construction-only and introduces no second Soklet generation.
  ...reviewedScopeFile("src/test/java/com/soklet/StreamingCleanupDiagnosticsTests.java", "4819471ec5dc9766f39d5dbaeace72d842cf448a1691155e5990e56c633aebfb", [
    ["realNioSocketConnectInterruptionAfterCancelationRemainsQuiet","efb0da00a3565c131f2d6a64a15e0f7c8c69fc49b9335a7d01f3d3558213e777",{"controlJoinMillis":14000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
], 'verification follow-up scope');

const REVIEWED_SCOPE_OVERRIDES = mergeReviewedScopeOverrideMaps(
  REVIEWED_VERIFICATION_FOLLOWUP_SCOPE_OVERRIDES,
  REVIEWED_LEGACY_EXPANSION_SCOPE_OVERRIDES,
  REVIEWED_STREAMING_SCOPE_OVERRIDES,
  REVIEWED_SCOPE_TOPOLOGY_OVERRIDES,
  REVIEWED_PHASE_POLICY_OVERRIDES,
  REVIEWED_CONTROL_OVERRIDES);

const REVIEWED_ORPHAN_HELPERS = checkedReviewMap([
  ...reviewedOrphanFile("src/test/java/com/soklet/CombinedTransportShutdownTests.java", "519925fa00c0cbe929a41dfede416bcf81eca7c0daf7318cbfd3473d299aad81", [
    ["openWork",188,"4f0698cd38a5f0ec00ed3fac96aab4a7955a93b4fcf45b5bcac9c6526556f21b",{"path":"src/test/java/com/soklet/CombinedTransportShutdownTests.java","line":52,"lineSha256":"af9b1cc5d3b5a82753738827d031f68e688ef50bbb730401e780eeeed1a8b128","rationale":"The source-bound calling JUnit fixture owns this exact helper through its explicit start/shutdown invocation or try-with-resources cleanup. The helper operates on the same transport/Soklet owner already represented by the caller closure; it introduces no independent generation or synthetic JUnit outer guard. The callable and whole-file hashes bind the ownership and bounded control evidence."}],
    ["close",264,"6b6fb3524306d7e4d11e0e01d0f91238be77086faad56261497c27fae7b2e0d5",{"path":"src/test/java/com/soklet/CombinedTransportShutdownTests.java","line":51,"lineSha256":"d373a7c7b99ff503802098ba0393cad4235712af01a843fd95b1ea1e37a76366","rationale":"The source-bound calling JUnit fixture owns this exact helper through its explicit start/shutdown invocation or try-with-resources cleanup. The helper operates on the same transport/Soklet owner already represented by the caller closure; it introduces no independent generation or synthetic JUnit outer guard. The callable and whole-file hashes bind the ownership and bounded control evidence."}],
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/DecoratedHttpQueuedAdmissionTests.java", "4b6b77b205c2c9cf0b374e30e7c7532bfba9192a82531013b28ffe4efe6fee67", [
    ["attach",117,"b2f9569cb8984170b753860ceccba4a4c6cafa004f241e7a5c3c96e583ce7166",{"path":"src/test/java/com/soklet/DecoratedHttpQueuedAdmissionTests.java","line":35,"lineSha256":"4c452535317c6e72f8f265ce54b145125d48a17d829cbe06b12cbc3de0cce2cb","rationale":"The calling test starts one Soklet owner whose HTTP decorator attach contract creates the exact termination-owning delegate runtime. Production startup invokes this public attach/start contract; the returned wrapper delegates physical lifecycle and signals only after child teardown. It is part of the caller owner closure, not a second independently started owner or a synthetic JUnit helper guard."}],
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/external/HttpStreamGracefulShutdownTests.java", "8b0f39217951c91fc9ab07a5e293b0ceae9021adf90c4ca3b6cec4257a4d0cf8", [
    ["close",199,"64aaa47898f7332368f44f98e6a352b9b60de3e9528717668d0a03e588c3be33",{"path":"src/test/java/com/soklet/external/HttpStreamGracefulShutdownTests.java","line":61,"lineSha256":"827595d867243a6c3d17517745c8b0ee605ec1d2dcb4b17b807256fabed2fa71","rationale":"The source-bound calling JUnit fixture owns this exact helper through its explicit start/shutdown invocation or try-with-resources cleanup. The helper operates on the same transport/Soklet owner already represented by the caller closure; it introduces no independent generation or synthetic JUnit outer guard. The callable and whole-file hashes bind the ownership and bounded control evidence."}],
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/external/SseTransportHandshakeAccessTests.java", "c674f3e5dbb03f26e28a763b72d2c13b7938810f402334079931eed4a512dd46", [
    ["close",195,"496f77a84a3a14de422c047cda06988c45dcb9d376331caa02ca74a02cf9a001",{"path":"src/test/java/com/soklet/external/SseTransportHandshakeAccessTests.java","line":64,"lineSha256":"9d167d0ef74dc5cb7191410f92e76465eef59d154451a17a38d70657bd67b0cf","rationale":"The source-bound calling JUnit fixture owns this exact helper through its explicit start/shutdown invocation or try-with-resources cleanup. The helper operates on the same transport/Soklet owner already represented by the caller closure; it introduces no independent generation or synthetic JUnit outer guard. The callable and whole-file hashes bind the ownership and bounded control evidence."}],
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/FiniteHttpResponseSafetyTests.java", "db7ed149701d41f501b98f0b19dd6a4f4f51a61cd709c451eb5ae1bec8cdab18", [
    ["close",232,"496f77a84a3a14de422c047cda06988c45dcb9d376331caa02ca74a02cf9a001",{"path":"src/test/java/com/soklet/FiniteHttpResponseSafetyTests.java","line":34,"lineSha256":"d1e064401db8d8c05bb1b5e28dd5c4d8527428c8be134d9678218ca99294b170","rationale":"The source-bound calling JUnit fixture owns this exact helper through its explicit start/shutdown invocation or try-with-resources cleanup. The helper operates on the same transport/Soklet owner already represented by the caller closure; it introduces no independent generation or synthetic JUnit outer guard. The callable and whole-file hashes bind the ownership and bounded control evidence."}],
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/HeaderLocaleTests.java", "82129d6038b9552d3e954bfe9de5702fb1dba51360363ae1ebdf0a42cc7dc7db", [
    ["main",92,"aad2a39b20e15b5ca5ee07ce3b777f965fd195860007f7f586b2cc157b645cb5",{"path":"src/test/java/com/soklet/HeaderLocaleTests.java","line":74,"lineSha256":"4fb90ede31f41e09dafeb6b6ac48ed005e8f618ae0cc68a82e34ed3102312eb0","rationale":"The isolated-locale JUnit driver starts this exact main class for tr, az, ar_EG, fa_IR and en with matching FORMAT locale pins. Each child has a 25-second wait and finally forced destruction with a two-second termination wait. The method-level 150-second guard covers five sequential 27-second process/cleanup budgets plus 15 seconds of reserve. The child main invokes bounded HTTP and Java-21-plus SSE wire probes; it remains externally bounded helper evidence, not a synthetic JUnit lifecycle scope."}],
    ["assertHttpWire",198,"2249dba7d39d27c04c51ed3be818aa7e1780295c4f9d7a0dfe2c7fbfa44437b5",{"path":"src/test/java/com/soklet/HeaderLocaleTests.java","line":115,"lineSha256":"a4363014c1e8fc28d22483cd9e4319fbcda958c8bc389ab00584c7307f1a23b9","rationale":"The source-hashed locale child main invokes this exact HTTP wire helper through assertAll. Its loopback reads/connects are timed, its Soklet owner has explicit three-second startup, two-second graceful and one-second forced shutdown settings, and the entire child is owned by the parent's 25-second process deadline and two-second forced cleanup. No synthetic JUnit guard or independently reusable execution claim is assigned to the helper."}],
    ["assertSseWire",222,"0134b218936fefea8804478a4e216c5443f3157e9c1a2444eb05a885ec5fb887",{"path":"src/test/java/com/soklet/HeaderLocaleTests.java","line":118,"lineSha256":"7089b89ea77d850deed28f3c84b09d71122e029fb085c58c29ee1e429b6f6c1d","rationale":"The source-hashed locale child main invokes this exact SSE wire helper only on Java 21+. It exercises accepted, rejected, unknown-status and overload responses with timed loopback operations and a three-second establishment latch. Its Soklet owner uses three-second startup, two-second graceful and one-second forced shutdown settings; the entire child remains under the parent's 25-second process deadline and two-second forced cleanup. No synthetic JUnit guard is assigned to this helper."}],
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/HttpHandlerDispatchAndDrainTests.java", "8be29cebf68665f91f2f66f765e99686c5ec9ec511246dca8271a1a56e208ae9", [
    ["beginDrain",287,"de88704052c4eac01d9ada0a6fc10b7a9b579029acd080f7e362f87893ce9f74",{"path":"src/test/java/com/soklet/HttpHandlerDispatchAndDrainTests.java","line":131,"lineSha256":"4fd89634b34136e7576156620ec8901bf96e92c318da9ff4d6d3a0119d55abfb","rationale":"The source-bound calling JUnit fixture owns this exact helper through its explicit start/shutdown invocation or try-with-resources cleanup. The helper operates on the same transport/Soklet owner already represented by the caller closure; it introduces no independent generation or synthetic JUnit outer guard. The callable and whole-file hashes bind the ownership and bounded control evidence."}],
    ["close",294,"e49ebe21b299452fe87e708f7150da3925891bef11d146c0f3848f99e487b554",{"path":"src/test/java/com/soklet/HttpHandlerDispatchAndDrainTests.java","line":53,"lineSha256":"f4248a25f6896de909a1cffb8936ccd6dc0562139369d0579554fd10ccf2d7d8","rationale":"The source-bound calling JUnit fixture owns this exact helper through its explicit start/shutdown invocation or try-with-resources cleanup. The helper operates on the same transport/Soklet owner already represented by the caller closure; it introduces no independent generation or synthetic JUnit outer guard. The callable and whole-file hashes bind the ownership and bounded control evidence."}],
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/HttpStreamingPassiveDisconnectTests.java", "568a341d6bc6d646255fa36c1a4464a2b9b3db7c4316cc4b57d460a1793587b7", [
    ["close",151,"bc009b65971dbf4b367e1a87680bf9e987a42a2c6b0df8ae18f47b013dfb17f3",{"path":"src/test/java/com/soklet/HttpStreamingPassiveDisconnectTests.java","line":57,"lineSha256":"a7ac02db1ad1154d09fdf6ce06d830f89ea85d0ddb2b15da8669fb2d76a242a6","rationale":"Both passive-disconnect tests own this fixture with try-with-resources. Its close method shuts down the single Soklet generation already counted by each calling test; it introduces no additional generation or separate guard."}],
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/HttpStreamingProtocolRejectionTests.java", "8204e9d36e574e08f0e1f0053706af79b5d226df6c9bf61351002343c92109f6", [
    ["start",262,"0c3ee687393877456d2e2fd47677d64daab07587e4bb91e5c2431b55f4bd7b3b",{"path":"src/test/java/com/soklet/HttpStreamingProtocolRejectionTests.java","line":63,"lineSha256":"fa6676b45ddf796ece9e01f8062315528827a0f45c10402dd5544ffe900e9f7c","rationale":"The source-bound calling JUnit fixture owns this exact helper through its explicit start/shutdown invocation or try-with-resources cleanup. The helper operates on the same transport/Soklet owner already represented by the caller closure; it introduces no independent generation or synthetic JUnit outer guard. The callable and whole-file hashes bind the ownership and bounded control evidence."}],
    ["close",274,"688b4c4b5104c903362d8307a41cfb60c74464aff814f5efa2e7f41dcff1111e",{"path":"src/test/java/com/soklet/HttpStreamingProtocolRejectionTests.java","line":62,"lineSha256":"5bd95356757a8c260b00eb41fc7f82b28f1d2c6ec37f1704deea45e0e4c8aef9","rationale":"The source-bound calling JUnit fixture owns this exact helper through its explicit start/shutdown invocation or try-with-resources cleanup. The helper operates on the same transport/Soklet owner already represented by the caller closure; it introduces no independent generation or synthetic JUnit outer guard. The callable and whole-file hashes bind the ownership and bounded control evidence."}],
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/internal/mcp/protocol/McpLegacyRpcStatusTests.java", "ac8172f4768f73247378ac9155ed3ab5ecb5ad09d3b5d72b321d34b791c92029", [
    ["close",299,"55e2c72ca7b684e2dac0fbfd5caa5a8c1952649c66ebc90b98eabe30a0f87cc7",{"path":"src/test/java/com/soklet/internal/mcp/protocol/McpLegacyRpcStatusTests.java","line":68,"lineSha256":"10077cbb7af8d5dcc1724267ebf7274781fad41c7caafea2ce28149bb1249045","rationale":"The source-bound calling JUnit fixture owns this exact helper through its explicit start/shutdown invocation or try-with-resources cleanup. The helper operates on the same transport/Soklet owner already represented by the caller closure; it introduces no independent generation or synthetic JUnit outer guard. The callable and whole-file hashes bind the ownership and bounded control evidence."}],
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/InternalTransportEndpointTestCompatibility.java", "89141d7627fc3329742dfe3bb66364fdda2c509291b87cf4094efea180aeb992", [
    ["attach",31,"1883bfa6fe73fdd47cd502d6daeee8cb86c6860a345a5b7d5e40fe04fa5da300",{"path":"src/main/java/com/soklet/SokletDirectLifecycle.java","line":2509,"lineSha256":"788aa269a8bc65fb1f7f7460a22f1a124dbdda93b52e0fabc3c3a91472897be3","rationale":"The production direct lifecycle invokes the public HTTP endpoint attach contract."}],
    ["publicRuntime",45,"f360dda2e0fc8a7cb76981a7c6c47b6c79bcbe05f8c841d280de98f75b7164cd",{"path":"src/test/java/com/soklet/InternalTransportEndpointTestCompatibility.java","line":36,"lineSha256":"03c392d1a09359e546d74395faec5a2129994402a1e60e337059b35b43b63281","rationale":"The reviewed HTTP compatibility attach default invokes this adapter helper."}],
    ["attach",77,"c29b8ee82137d5a63ea9ee192c2f9f63e541ebee1e152ebee2f6b0cb612ef1b8",{"path":"src/main/java/com/soklet/SokletDirectLifecycle.java","line":2509,"lineSha256":"788aa269a8bc65fb1f7f7460a22f1a124dbdda93b52e0fabc3c3a91472897be3","rationale":"The production direct lifecycle invokes the public SSE endpoint attach contract."}],
    ["publicRuntime",91,"f360dda2e0fc8a7cb76981a7c6c47b6c79bcbe05f8c841d280de98f75b7164cd",{"path":"src/test/java/com/soklet/InternalTransportEndpointTestCompatibility.java","line":36,"lineSha256":"03c392d1a09359e546d74395faec5a2129994402a1e60e337059b35b43b63281","rationale":"The reviewed HTTP compatibility attach default invokes this adapter helper."}],
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/McpAdmissionOrderingPublicRuntimeTests.java", "81e820fb71665a1b83ea6176f4b663500fb8e4196fcbd2057d597f471db9e909", [
    ["close",223,"678f0e1d5ca773009083cc7a97f5cd5484ee2107a2efdbe8c5d7d5d202f8fc06",{"path":"src/test/java/com/soklet/McpAdmissionOrderingPublicRuntimeTests.java","line":35,"lineSha256":"827595d867243a6c3d17517745c8b0ee605ec1d2dcb4b17b807256fabed2fa71","rationale":"The source-bound calling JUnit fixture owns this exact helper through its explicit start/shutdown invocation or try-with-resources cleanup. The helper operates on the same transport/Soklet owner already represented by the caller closure; it introduces no independent generation or synthetic JUnit outer guard. The callable and whole-file hashes bind the ownership and bounded control evidence."}],
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/McpDecimalElicitationPublicRuntimeTests.java", "6cb4adc83498577573c3e744ec159f7ed77992a4d946b83e6b9223acd472604f", [
    ["close",172,"55e2c72ca7b684e2dac0fbfd5caa5a8c1952649c66ebc90b98eabe30a0f87cc7",{"path":"src/test/java/com/soklet/McpDecimalElicitationPublicRuntimeTests.java","line":43,"lineSha256":"827595d867243a6c3d17517745c8b0ee605ec1d2dcb4b17b807256fabed2fa71","rationale":"The source-bound calling JUnit fixture owns this exact helper through its explicit start/shutdown invocation or try-with-resources cleanup. The helper operates on the same transport/Soklet owner already represented by the caller closure; it introduces no independent generation or synthetic JUnit outer guard. The callable and whole-file hashes bind the ownership and bounded control evidence."}],
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/McpHandlerExecutorPublicRuntimeTests.java", "252b65f4871965d324c251389872c8814e0d3c205904ba30b55622fcbebf4e9b", [
    ["close",266,"004f31559febc214e9a6069868fb221877f5be81af10cc29488212a8046035bf",{"path":"src/test/java/com/soklet/McpHandlerExecutorPublicRuntimeTests.java","line":57,"lineSha256":"1b83a89e44f8c9136fe206ad9d44d87496d37f7e3d96a2010c48d383f2047d0d","rationale":"The source-bound calling JUnit fixture owns this exact helper through its explicit start/shutdown invocation or try-with-resources cleanup. The helper operates on the same transport/Soklet owner already represented by the caller closure; it introduces no independent generation or synthetic JUnit outer guard. The callable and whole-file hashes bind the ownership and bounded control evidence."}],
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/McpLegacySessionPublicRuntimeTests.java", "960ff8eda225d5cf9230969a64b7bdef9bcb764b2eceaee03e2b6f1726313def", [
    ["close",966,"496f77a84a3a14de422c047cda06988c45dcb9d376331caa02ca74a02cf9a001",{"path":"src/test/java/com/soklet/McpLegacySessionPublicRuntimeTests.java","line":188,"lineSha256":"8354739e8b5110f3f889715c4bdeb352ad17dc688591a71028a188dec20cbec9","rationale":"The calling JUnit tests own this fixture with try-with-resources. Its close method closes the already counted Soklet owner; it creates no additional lifecycle generation. The constructor and calling tests retain explicit finite lifecycle policies and source-bound outer guards."}],
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/McpLegacySessionTransportPublicRuntimeTests.java", "e42a66fb27a96a21b2091795c5e3f862250726d4e707cc36765b31a37b4d2b8a", [
    ["close",679,"55e2c72ca7b684e2dac0fbfd5caa5a8c1952649c66ebc90b98eabe30a0f87cc7",{"path":"src/test/java/com/soklet/McpLegacySessionTransportPublicRuntimeTests.java","line":116,"lineSha256":"70dab03963cccbf5b9d34bbccde3f24dae74ffc40630a200b3f356b719b07955","rationale":"The calling JUnit tests own this fixture with try-with-resources. Its close method closes the already counted Soklet owner; it creates no additional lifecycle generation. The constructor and calling tests retain explicit finite lifecycle policies and source-bound outer guards."}],
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/McpLegacySubscriptionPublicRuntimeTests.java", "6bf7797a873acb9f979c1aed6bfbbf2e596c3edf2924965e754f60f0ec1f612e", [
    ["close",1235,"55e2c72ca7b684e2dac0fbfd5caa5a8c1952649c66ebc90b98eabe30a0f87cc7",{"path":"src/test/java/com/soklet/McpLegacySubscriptionPublicRuntimeTests.java","line":249,"lineSha256":"1283865e93a8e3eaa0d7ba0696f4f3307f6422cce7560c54fe9e76af4ddda2ae","rationale":"The calling JUnit tests own this fixture with try-with-resources. Its close method closes the already counted Soklet owner; it creates no additional lifecycle generation. The constructor and calling tests retain explicit finite lifecycle policies and source-bound outer guards."}],
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/McpLifecycleB3Tests.java", "853c9c9d7b7b6b2bfa50b364a8c009b2612064c7ae8f759d791866df505bac8b", [
    ["close",2729,"eb076ba581178078050f92c63303430e60bec9ec1a9ed8ec7c746d75f8453968",{"path":"src/test/java/com/soklet/McpLifecycleB3Tests.java","line":456,"lineSha256":"b153608a966dca6571b544635e46ccab9aca8126e95a5e6384e21db8425ca463","rationale":"A lifecycle test directly invokes the reviewed fixture close contract."}],
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/McpLocalizationFleetPublicRuntimeTests.java", "913ab645cc78806ea13ec1abd3797d9244f1301adccc8fcfbacfd64c4ef37f1d", [
    ["start",578,"a54b1363a3dab8198bf40d09ee1934132622a39ba308ae3f7c7d7eb76c3b88f5",{"path":"src/test/java/com/soklet/McpLocalizationFleetPublicRuntimeTests.java","line":102,"lineSha256":"07b7acceb6a5e59384e6422898a20512037609331671db066348aae36a3e9479","rationale":"The lifecycle test directly invokes the reviewed two-node fleet start helper."}],
    ["close",617,"d936c7abbdd7481d460bf7045588d487c8b8351a19d9473faf10cafabb0c0e89",{"path":"src/test/java/com/soklet/McpLocalizationFleetPublicRuntimeTests.java","line":143,"lineSha256":"186351cad850a49204fd8ae41bd21e660277a6d5af1db6d4744e5f4004467b14","rationale":"The lifecycle test directly invokes the reviewed two-node fleet close helper."}],
    ["start",742,"4e9e880fcdcddc24379810ae107534c7360ef80b73c1011643098ae240c17db6",{"path":"src/test/java/com/soklet/McpLocalizationFleetPublicRuntimeTests.java","line":580,"lineSha256":"dff4a9b14f394a29901a412dce108efa98e67ab21e6150d23ad39072653b0802","rationale":"The reviewed two-node fleet start helper invokes each node start helper."}],
    ["stop",746,"1194e749f943c21d3ce81c218884aaaae258a5157a85ba9874b96d35a1f7d720",{"path":"src/test/java/com/soklet/McpLocalizationFleetPublicRuntimeTests.java","line":242,"lineSha256":"31223babeaccf4900f5d16c97ed0d3db4262a1be2bb763ecdf2f634968993f69","rationale":"The fleet lifecycle test invokes the reviewed node stop helper."}],
    ["close",946,"a9e477c06503aa8ca99ce466692d8fa8a71079cbb8b40d227a459f166e963e26",{"path":"src/test/java/com/soklet/McpLocalizationFleetPublicRuntimeTests.java","line":618,"lineSha256":"d998b1ab99968332971844e39c07c3958a461982d28d6849cf6d04a4df1c563c","rationale":"The reviewed two-node fleet close helper invokes each node close helper."}],
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/McpLocalizationInitializationPublicRuntimeTests.java", "fba56f4fb4dd98da85044b418d8d6e294f9345cd7ee25cfd0ec6b6966c2d1349", [
    ["close",270,"55e2c72ca7b684e2dac0fbfd5caa5a8c1952649c66ebc90b98eabe30a0f87cc7",{"path":"src/test/java/com/soklet/McpLocalizationInitializationPublicRuntimeTests.java","line":52,"lineSha256":"d95525a5d9244e0c2ccd191fa06a39cd0d93c5e49e676f3b7efa24bfc2107daa","rationale":"The source-bound calling JUnit fixture owns this exact helper through its explicit start/shutdown invocation or try-with-resources cleanup. The helper operates on the same transport/Soklet owner already represented by the caller closure; it introduces no independent generation or synthetic JUnit outer guard. The callable and whole-file hashes bind the ownership and bounded control evidence."}],
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/McpNumericRequestStatePublicRuntimeTests.java", "5990cb26eb7e842bd86a17b8c8e6a9b1f48c9b50a04e3bc81ff05ba0217afb3e", [
    ["close",164,"55e2c72ca7b684e2dac0fbfd5caa5a8c1952649c66ebc90b98eabe30a0f87cc7",{"path":"src/test/java/com/soklet/McpNumericRequestStatePublicRuntimeTests.java","line":50,"lineSha256":"bd31724c97ae12edc2e1bb77f9f1095d69526a6bf9225f513c2d66f293ef2514","rationale":"The source-bound calling JUnit fixture owns this exact helper through its explicit start/shutdown invocation or try-with-resources cleanup. The helper operates on the same transport/Soklet owner already represented by the caller closure; it introduces no independent generation or synthetic JUnit outer guard. The callable and whole-file hashes bind the ownership and bounded control evidence."}],
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/McpRequestStateBoundaryPublicRuntimeTests.java", "6f47207a0589ece85e539c00e73968b9b655366a3002ea53963c3fb53906a195", [
    ["close",161,"55e2c72ca7b684e2dac0fbfd5caa5a8c1952649c66ebc90b98eabe30a0f87cc7",{"path":"src/test/java/com/soklet/McpRequestStateBoundaryPublicRuntimeTests.java","line":55,"lineSha256":"cced6982c80e4e0bc82a718f8487a6f9600a4df1a04bbe003a3c3630cbeb387f","rationale":"The source-bound calling JUnit fixture owns this exact helper through its explicit start/shutdown invocation or try-with-resources cleanup. The helper operates on the same transport/Soklet owner already represented by the caller closure; it introduces no independent generation or synthetic JUnit outer guard. The callable and whole-file hashes bind the ownership and bounded control evidence."}],
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/McpSubscriptionShutdownAdmissionTests.java", "2cf1ac1dfcc8a0792e0d76cf633df775dada50a3bea8431cce010492e31ee2be", [
    ["quiesceAndShutdown",263,"a45fa57b2690f4e82fa9298e226e4f65a02ffdd0781d0e4d4a8219e89ec1b5e7",{"path":"src/test/java/com/soklet/McpSubscriptionShutdownAdmissionTests.java","line":67,"lineSha256":"0b337713bc797f86d92e4042d56b555aaa0787394759535806d46cb8ee48eacf","rationale":"The source-bound calling JUnit fixture owns this exact helper through its explicit start/shutdown invocation or try-with-resources cleanup. The helper operates on the same transport/Soklet owner already represented by the caller closure; it introduces no independent generation or synthetic JUnit outer guard. The callable and whole-file hashes bind the ownership and bounded control evidence."}],
    ["close",304,"182798c5ac2cadf7d711c839423da6516a9735517bdfea28a6dd24d1f0cf0c71",{"path":"src/test/java/com/soklet/McpSubscriptionShutdownAdmissionTests.java","line":59,"lineSha256":"699121fb4f14aa5dc0061d3ba953e91250db88282ca30a87d838302e6608afdc","rationale":"The source-bound calling JUnit fixture owns this exact helper through its explicit start/shutdown invocation or try-with-resources cleanup. The helper operates on the same transport/Soklet owner already represented by the caller closure; it introduces no independent generation or synthetic JUnit outer guard. The callable and whole-file hashes bind the ownership and bounded control evidence."}],
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/ResidualWorkerProcessTests.java", "68217b39d0136b15912128b9e697cc4d17e8c60ae489c7f11b8740dfbf5eec07", [
    ["main",96,"b427888b3cbba3fd564fd5a414f1b13cef1510e44722d1ae0aeb14b077beac21",{"path":"src/test/java/com/soklet/ResidualWorkerProcessTests.java","line":60,"lineSha256":"38065fbd28bd677f7b073b99fb68919028b502adbf07bd13c182377fa234455e","rationale":"The parent assertResidualExit starts this exact Fixture.main in an owned child for five literal residual modes. Each parent has a5s absolute marker poll,3s natural-exit wait and finally5s forced reap under the60s JUnit guard. The actual child owns one public SokletApplication with2s startup,1s cancellation,100ms grace and100ms forced policy; resistant callbacks deliberately remain daemon and the INCOMPLETE result skips cleanup. Public chained fromConfig(config).run(cleanup) execution is externally supervised helper evidence, not an ordinary synthetic JUnit lifecycle scope or a claim that raw fixture I/O has an independent total deadline."}],
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/Round2HttpStreamingRuntimeTests.java", "6ca223831871d7007f859ebfb224455b6ddef5b243353c91303e0d612d645fd1", [
    ["close",294,"7d8c51a387372a127ef542e43c90de2cd5f18c393ed63bca53c5ed1b2bfb6e50",{"path":"src/test/java/com/soklet/Round2HttpStreamingRuntimeTests.java","line":26,"lineSha256":"5bd95356757a8c260b00eb41fc7f82b28f1d2c6ec37f1704deea45e0e4c8aef9","rationale":"The source-bound calling JUnit fixture owns this exact helper through its explicit start/shutdown invocation or try-with-resources cleanup. The helper operates on the same transport/Soklet owner already represented by the caller closure; it introduces no independent generation or synthetic JUnit outer guard. The callable and whole-file hashes bind the ownership and bounded control evidence."}],
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/Round2SseRuntimeTests.java", "3686c08a4a35b52dc4ab53522a4575976b542dda7324a8a8f19511e5bec76f4f", [
    ["close",450,"5551c46e59d27c4db4c583d9368f36234326d52e6ad07988202bd2704428019c",{"path":"src/test/java/com/soklet/Round2SseRuntimeTests.java","line":89,"lineSha256":"2da2504d2221ec4e4deb5172d6a231b0899cbffc2b1507494e7543f9197c58f1","rationale":"The source-bound calling JUnit fixture owns this exact helper through its explicit start/shutdown invocation or try-with-resources cleanup. The helper operates on the same transport/Soklet owner already represented by the caller closure; it introduces no independent generation or synthetic JUnit outer guard. The callable and whole-file hashes bind the ownership and bounded control evidence."}],
    ["close",541,"5551c46e59d27c4db4c583d9368f36234326d52e6ad07988202bd2704428019c",{"path":"src/test/java/com/soklet/Round2SseRuntimeTests.java","line":248,"lineSha256":"91741254e4f4a77aabb7f7c5287e7c05e16f644bd153a5e6c89fbcf28a1b924e","rationale":"The source-bound calling JUnit fixture owns this exact helper through its explicit start/shutdown invocation or try-with-resources cleanup. The helper operates on the same transport/Soklet owner already represented by the caller closure; it introduces no independent generation or synthetic JUnit outer guard. The callable and whole-file hashes bind the ownership and bounded control evidence."}],
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/SokletApplicationObservationTests.java", "d4b53de085208074bf3f60d65cbfa624ec11d012c7e8dd2bfc3b2855a42bcba4", [
    ["start",1110,"8743bc5899534d2657576bdd66d59311499e6ba40769bb325879c3acfee3de6e",{"path":"src/main/java/com/soklet/SokletApplication.java","line":392,"lineSha256":"b2319df033edd17959e515597bc6bf4b346ffcdeb9c7c82ed90ffdd190add5bd","rationale":"The production application runner invokes the wrapped runtime start contract."}],
    ["shutdown",1115,"eef45a37c20721b055e31362bc503628c1bd6eea2a765b56fe50cfbae15a5c7f",{"path":"src/main/java/com/soklet/SokletApplication.java","line":577,"lineSha256":"0ca794b2fa40ccfdbd54f9835b183890f2e8122b30d4504f27987a6fbbffeb3a","rationale":"The production application runner invokes the wrapped runtime shutdown contract."}],
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/SokletDirectCompositionIsolationTests.java", "4c0fa5ec0854c8ebb54996d9a2d4167e27d6c386e5451cd3be0b6ae6ff7e1174", [
    ["attach",450,"7c57b13fdf6fc3452e7a932d056e49fcd3f5a4bf63b3005791d37526b8c19828",{"path":"src/test/java/com/soklet/SokletDirectCompositionIsolationTests.java","line":51,"lineSha256":"59a5a5e95e2ba52cdbec6fb317cbd2020259569b690db93f89a7496fe515cfdb","rationale":"The direct composition test installs this lifecycle-owning decorator."}],
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/SokletDirectLateStartupIntegrationTests.java", "9e0c8efa5bc053cf760be123c35f280f0cf4b679ce29942604f8a24419d5cce1", [
    ["close",976,"a2c47642265a189cc7764e547ae97971625558d5bc9359803865d61787c65067",{"path":"src/test/java/com/soklet/SokletDirectLateStartupIntegrationTests.java","line":101,"lineSha256":"50edadd1377f4ad9436d7c5312200d6544b0137965aa8c5b049fc702b3170b05","rationale":"The lifecycle test's try-with-resources scope invokes the reviewed owner-harness close helper."}],
    ["attach",1357,"0a42f2275a9b81821b8b904c86631850738e49019c39ec025838ac2b46668266",{"path":"src/test/java/com/soklet/SokletDirectLateStartupIntegrationTests.java","line":216,"lineSha256":"128fbafeefb443ff53cafb5089fe4c18e97f7be4caebb68052a8bac3bd7af4b3","rationale":"The owner-level lifecycle test installs this reviewed transparent endpoint, whose lifecycle owner invokes its attach contract."}],
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/SokletDirectSseCompositionTests.java", "0e2a3d2ecf05cdf08a397adc9b99fee2448263ebf864bce9280dab4e4223afe1", [
    ["attach",676,"e125b5b643e4e0b7f8910a97362f795c80686387d0ccc1400b1702ea5b8bcb33",{"path":"src/test/java/com/soklet/SokletDirectSseCompositionTests.java","line":88,"lineSha256":"3489e198d1cf9063ad182a92b4e5ac78e33f635e650cc7ab1484245db4190b2c","rationale":"The SSE composition test installs this lifecycle-owning decorator."}],
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/SokletDirectStartClaimTruthTableTests.java", "55ac3deeb7cc5dba31d4d577274d0bda7ac804de86745204b5a29d961b80d487", [
    ["close",189,"72d21aa1b74efb7d81f57e986ddc30f6fab8e5f8a955335e5f95d5e655500b3f",{"path":"src/test/java/com/soklet/SokletDirectStartClaimTruthTableTests.java","line":144,"lineSha256":"a652045ac401b11d233cba856e6cd5c586f26926053ff1ec249d0bb80c947f87","rationale":"The lifecycle test's try-with-resources scope invokes the reviewed truth-race cleanup helper."}],
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/SokletDirectTerminalPublicationTests.java", "b24ad641b84b61b0ba009ab12e45a7e5fdec3df39299b2ff0b913a66e745351f", [
    ["create",430,"7e0266a1ff39c91caeff040d7a3e5a7712aad3a6c58af387450cac8f5e69492d",{"path":"src/test/java/com/soklet/SokletDirectTerminalPublicationTests.java","line":94,"lineSha256":"8b0299d6130be80b0de8400d73d5ce7177bd1181ee4ff028f0322f5f214f53ab","rationale":"The lifecycle test directly constructs the reviewed owner harness."}],
    ["close",453,"df08d934601614ca68816224f6d17016463202755d3f8dbf6f741e0acc85f3a9",{"path":"src/test/java/com/soklet/SokletDirectTerminalPublicationTests.java","line":94,"lineSha256":"8b0299d6130be80b0de8400d73d5ce7177bd1181ee4ff028f0322f5f214f53ab","rationale":"The lifecycle test's try-with-resources scope invokes the reviewed owner-harness close helper."}],
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/SokletDirectTerminationPrecedenceTests.java", "fa53b1ea0174a65ed9900203fe2f0f17f05f1dbc8518ca513201c1992c9e58e9", [
    ["close",518,"e406547b24c34bad6ed8587e1768d7d8a1dbb25d260fef67b4e4bf3027653d3d",{"path":"src/test/java/com/soklet/SokletDirectTerminationPrecedenceTests.java","line":247,"lineSha256":"05e81d8b560c9a1c5498861a13c717af06a07bba454d7305b66bcace0f6b18af","rationale":"The lifecycle test's try-with-resources scope invokes the reviewed precedence-harness close helper."}],
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/SokletDirectWaitSemanticsTests.java", "d200f0ed18f1cd7532ea1f0141fa718c05e1d6ca0390896191ead5c0f7d8b242", [
    ["close",334,"4657b5a39ea6a1e00289aec01e92fbb4937e924fd3e088df0f935fb7c47d96e4",{"path":"src/test/java/com/soklet/SokletDirectWaitSemanticsTests.java","line":84,"lineSha256":"366775be6d81f07243ae7d4809f3249ceed9b8ca6705fca8f77d9a0e1306c9ef","rationale":"The direct wait-semantics test constructs the reviewed AutoCloseable wait harness."}],
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/SseDurationBoundaryTests.java", "9c3ad846a50e5fb09cefe23957bcdf0eb9cc60a48455294cbce2506f4c8f0ad7", [
    ["close",207,"761d2e3b8dee5e9864302cabbfc074a01d9d15807ec6d947265da6cab874f273",{"path":"src/test/java/com/soklet/SseDurationBoundaryTests.java","line":99,"lineSha256":"1d26e88ab4a917538020b8ad0d57fe21b8ee95650291a19b1f35a18442cba45d","rationale":"The source-bound calling JUnit fixture owns this exact helper through its explicit start/shutdown invocation or try-with-resources cleanup. The helper operates on the same transport/Soklet owner already represented by the caller closure; it introduces no independent generation or synthetic JUnit outer guard. The callable and whole-file hashes bind the ownership and bounded control evidence."}],
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/SseHandshakeOutcomeRuntimeTests.java", "4d79a0f1defe79662dc46562e6cb5b5e1ee710bd2138c8e6ca81594e3ad31e85", [
    ["close",462,"587f393710c8b32159a9fc141befc56d316713e9f0aaae29e9099ca9a111bfe4",{"path":"src/test/java/com/soklet/SseHandshakeOutcomeRuntimeTests.java","line":56,"lineSha256":"d806cd67e53f515d51ac61b18454946ba7685b6b0d99d04372b989d295eef0e2","rationale":"The source-bound calling JUnit fixture owns this exact helper through its explicit start/shutdown invocation or try-with-resources cleanup. The helper operates on the same transport/Soklet owner already represented by the caller closure; it introduces no independent generation or synthetic JUnit outer guard. The callable and whole-file hashes bind the ownership and bounded control evidence."}],
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/SseInitializerRuntimeTests.java", "1c5ce8703c1e630012e4105dce00f5af6fff0200b7470eed9067592600a798be", [
    ["start",245,"0c3ee687393877456d2e2fd47677d64daab07587e4bb91e5c2431b55f4bd7b3b",{"path":"src/test/java/com/soklet/SseInitializerRuntimeTests.java","line":62,"lineSha256":"8d7e8eb67bdc2bd8120bf0ef36821961a66d992f7a1142afbef0f2d4f8e3313e","rationale":"Each initializer test directly starts this fixture; its constructor supplies the reviewed 100-millisecond graceful and forced policy."}],
    ["close",253,"9b013bb1b47807fcd626406f533d5a1ed7d93b4e9fb7664c4d7111700b7ca232",{"path":"src/test/java/com/soklet/SseInitializerRuntimeTests.java","line":57,"lineSha256":"d4a48d539834456f20aabbe41059b2c633b2efd9c633b6105335ca17e77091e2","rationale":"The initializer test owns this AutoCloseable fixture in a try-with-resources scope; fixture shutdown belongs to its already-counted test generation."}],
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/SseLifecycleAdmissionTests.java", "763768af0d54d1f20ab164bda6d35afe3eb3962d75bea4de03a8b1391bc32695", [
    ["start",123,"0c3ee687393877456d2e2fd47677d64daab07587e4bb91e5c2431b55f4bd7b3b",{"path":"src/test/java/com/soklet/SseLifecycleAdmissionTests.java","line":63,"lineSha256":"8d7e8eb67bdc2bd8120bf0ef36821961a66d992f7a1142afbef0f2d4f8e3313e","rationale":"The default-capacity admission test directly starts this fixture; its two-second graceful and forced policy and socket-poll controls are composed under its 90-second method guard."}],
    ["close",138,"03fad5df7fe6ec56dc55b8cddfe36f4f976a8f087b46b2e143d86bf0172cd992",{"path":"src/test/java/com/soklet/SseLifecycleAdmissionTests.java","line":94,"lineSha256":"b153608a966dca6571b544635e46ccab9aca8126e95a5e6384e21db8425ca463","rationale":"The admission test invokes fixture shutdown in its finally block; this closes the same owned generation counted in the test row."}],
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/SseMemoizedBroadcastTests.java", "b5d0874db907e36dcef95d07f8ff8614a4267b6a3279bb12e193142d500dd6ad", [
    ["close",343,"1e52871625411bf73db1c5dd440b6f9a94c45d96b65faf36d91dfbd213d05200",{"path":"src/test/java/com/soklet/SseMemoizedBroadcastTests.java","line":88,"lineSha256":"1928e085150186485537e213cb184b8afce11ab3838d0dd9619f8c9d8816067d","rationale":"The source-bound calling JUnit fixture owns this exact helper through its explicit start/shutdown invocation or try-with-resources cleanup. The helper operates on the same transport/Soklet owner already represented by the caller closure; it introduces no independent generation or synthetic JUnit outer guard. The callable and whole-file hashes bind the ownership and bounded control evidence."}],
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/SseReadDeadlineRuntimeTests.java", "d953b77ad1f3c3856a7507710b634e92f5100f44d1b0c5bf0f4f4a29f263bb9c", [
    ["close",360,"4effc381cb838f52e85475c5ebad8652b43cc8fccc87700b1eae42687fda9608",{"path":"src/test/java/com/soklet/SseReadDeadlineRuntimeTests.java","line":61,"lineSha256":"09be20708df91c381bccdb03542c670fc97943c1222b24a9e3c8fbb01841a7a6","rationale":"The source-bound calling JUnit fixture owns this exact helper through its explicit start/shutdown invocation or try-with-resources cleanup. The helper operates on the same transport/Soklet owner already represented by the caller closure; it introduces no independent generation or synthetic JUnit outer guard. The callable and whole-file hashes bind the ownership and bounded control evidence."}],
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/StreamingCleanupDiagnosticsTests.java", "4819471ec5dc9766f39d5dbaeace72d842cf448a1691155e5990e56c633aebfb", [
    ["start",432,"529364ad6b346836b2da09315be017a547eb92a661a5a6d290486679119e0a63",{"path":"src/test/java/com/soklet/StreamingCleanupDiagnosticsTests.java","line":67,"lineSha256":"fa6676b45ddf796ece9e01f8062315528827a0f45c10402dd5544ffe900e9f7c","rationale":"The source-bound calling JUnit fixture owns this exact helper through its explicit start/shutdown invocation or try-with-resources cleanup. The helper operates on the same transport/Soklet owner already represented by the caller closure; it introduces no independent generation or synthetic JUnit outer guard. The callable and whole-file hashes bind the ownership and bounded control evidence."}],
    ["close",448,"8f601964d35c38bdd58c3c01535f83095b789d7d5c5a87ccf9601cd60ec4bf8d",{"path":"src/test/java/com/soklet/StreamingCleanupDiagnosticsTests.java","line":66,"lineSha256":"b1071a5fd913e6527b2b171f0a07c8cb9f1036a48842fb125063196f235714bf","rationale":"The source-bound calling JUnit fixture owns this exact helper through its explicit start/shutdown invocation or try-with-resources cleanup. The helper operates on the same transport/Soklet owner already represented by the caller closure; it introduces no independent generation or synthetic JUnit outer guard. The callable and whole-file hashes bind the ownership and bounded control evidence."}],
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/StreamingLifecycleTests.java", "108c4bd74abe853b35ee2c12c3334aa51e93cb01d49de71a6cca688cccb72727", [
    ["start",634,"4e9e880fcdcddc24379810ae107534c7360ef80b73c1011643098ae240c17db6",{"path":"src/test/java/com/soklet/StreamingLifecycleTests.java","line":74,"lineSha256":"8d7e8eb67bdc2bd8120bf0ef36821961a66d992f7a1142afbef0f2d4f8e3313e","rationale":"The lifecycle-capacity test starts this fixture; its helper policy and test control waits are reviewed together in the method closure row."}],
    ["shutdown",646,"dd76ab62776b8764f97798ec7f5d4929e492cd31627da1e7bb4e434a5e65458b",{"path":"src/test/java/com/soklet/StreamingLifecycleTests.java","line":147,"lineSha256":"e87deb21768689ed3d66e3f3066cc85935fe05c6ceeb37ecba57f6586510dd21","rationale":"The lifecycle-capacity test invokes fixture shutdown in its finally block; this is cleanup of its already-counted generation."}],
    ["shutdownNow",718,"62bc31ebee56edd579fd247b10d54b6c705a05752dcecc8416b33b6d6fa673c6",{"path":"src/test/java/com/soklet/StreamingLifecycleTests.java","line":240,"lineSha256":"7685ddf8264996409d10b8b00dfa1e111dcb2d159f72f0631340ca250ab0a297","rationale":"The inline-executor test installs this executor supplier. Its shutdownNow delegates to a nonblocking shutdown flag and returns an empty task list; DefaultHttpServer owns the executor and invokes its shutdown contract."}],
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/StreamingServerSettingsTests.java", "041b17ac3202d901a6b0fd4cae34e0df1a2b17007dd4844c0d0cd1806e6004aa", [
    ["start",335,"0c3ee687393877456d2e2fd47677d64daab07587e4bb91e5c2431b55f4bd7b3b",{"path":"src/test/java/com/soklet/StreamingServerSettingsTests.java","line":169,"lineSha256":"8d7e8eb67bdc2bd8120bf0ef36821961a66d992f7a1142afbef0f2d4f8e3313e","rationale":"The public-settings test starts this fixture; its literal 500-millisecond graceful and forced policy is covered by the calling test row."}],
    ["close",345,"49a79e791f8047831313a4d0a5486b21cdef60f75acbf550f1b3b013cbc6af74",{"path":"src/test/java/com/soklet/StreamingServerSettingsTests.java","line":237,"lineSha256":"b153608a966dca6571b544635e46ccab9aca8126e95a5e6384e21db8425ca463","rationale":"The public-settings test closes its fixture in finally; this is shutdown of the generation already counted by that test."}],
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/TransportProcessOwnershipTests.java", "8e672f8da1ce3b9a00c711e62e89935fe43b7e345b30ea9e0207795bb4a7184f", [
    ["main",183,"06338e50a39af95189824a3d1c3b6fe4a549ec02d253e599da857b5e5a5e6a80",{"path":"src/test/java/com/soklet/TransportProcessOwnershipTests.java","line":157,"lineSha256":"332aff3f9792c966a040644d982d21955fb7ae4b77a1af69addce646f1e4e0bc","rationale":"The parent start helper launches this exact Fixture.main class into an owned child. Tests use finite readiness/exit waits under the25s class guard, finally destroy/forcibly destroy and wait2s for owned child cleanup. The blocked-startup child installs2/1/1/1s policy; parent now waits8s for natural exit with3s reserve over that5s phase envelope. Other literal modes retain their explicit2s startup,1s cancellation/grace/force settings and bounded parent liveness/signal/input controls. Child public-owner execution is externally supervised evidence; the main is not assigned a synthetic ordinary JUnit lifecycle scope."}],
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/TransportStartupFailureTests.java", "2be9b16a8a00acc97153e6802d94db505f2dea77ab0a305b084b02b2a97346cf", [
    ["owning",193,"bce84e3d6f50a9ff7a14e970f41a50b00b5d6c80f76f15f6aee2b6b08b49723e",{"path":"src/test/java/com/soklet/TransportStartupFailureTests.java","line":215,"lineSha256":"5210a2bd0012daba2d03d46b9290fe43144c564b7e53183605a272e1912fdbbd","rationale":"The exact owning-decorator attach contract invokes this helper with the returned child attachment. The helper returns a lifecycle wrapper whose start forwards once and whose grace/force methods delegate then signal the wrapper; the calling startup-failure tests own the enclosing Soklet lifetime under explicit2/1/1/1s policy. It does not create a second public owner or borrow a synthetic JUnit helper guard."}],
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/UnparsedRequestAdditionalTransportTests.java", "35e8e276f824d85d8b1aa58635ed2927f31e8f35f676a6353b20a51df86f270c", [
    ["close",312,"8483658be11af4a9e197a94648b49f9dcc0b8009aa929db1f39ecaaeb2794d5e",{"path":"src/test/java/com/soklet/UnparsedRequestAdditionalTransportTests.java","line":80,"lineSha256":"16a5028a469c057bb6257a235f692e4bb83ce173f2abc1051fb486d777c4a2fa","rationale":"The source-bound calling JUnit fixture owns this exact helper through its explicit start/shutdown invocation or try-with-resources cleanup. The helper operates on the same transport/Soklet owner already represented by the caller closure; it introduces no independent generation or synthetic JUnit outer guard. The callable and whole-file hashes bind the ownership and bounded control evidence."}],
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

// Resolve only immutable literal producer sources and map/flatMap chains. The
// independently reviewed node census remains mandatory. Unknown filtering,
// mutable collections, branching helpers and recursion are left unresolved.
function literalDynamicFactoryNodeCount(method, sourceText) {
  if (sourceText === null) return null;
  const masked = maskJavaSource(sourceText);
  const methods = javaMethods(method.path, sourceText);
  const typeScopes = javaTypeScopes(method.path, sourceText, masked);
  const collections = new Map();
  for (const match of masked.matchAll(
    /\bstatic\s+final\s+(?:Set|List)\s*<[^;=]+>\s+([A-Za-z_$][\w$]*)\s*=\s*(?:Set|List)\s*\.\s*of\s*\(/gu)) {
    const owner = typeScopes.filter(type => type.openBrace < match.index
      && match.index < type.end).sort((a, b) =>
        (a.end - a.openBrace) - (b.end - b.openBrace))[0];
    if (owner === undefined || !method.enclosingTypes.some(type =>
      type.openBrace === owner.openBrace)) continue;
    const open = match.index + match[0].lastIndexOf('(');
    const end = matchingParenthesisEnd(masked, open);
    if (end === null) continue;
    const elements = sourceText.slice(open + 1, end - 1).trim();
    const count = elements.length === 0 ? 0
      : splitTopLevelArguments(maskJavaSource(elements)).length;
    if (collections.has(match[1])) collections.set(match[1], null);
    else collections.set(match[1], count);
  }
  // Never bind an outer field's cardinality to a shadowing local or lambda
  // parameter. Unresolved cardinality must fail the node census instead of
  // falling back to a potentially smaller unrelated literal producer.
  for (const name of collections.keys()) {
    const escaped = name.replace(/[.*+?^${}()|[\]\\]/gu, '\\$&');
    if (new RegExp(`\\b${escaped}\\s*=|\\b${escaped}\\s*->|\\([^(){};]*\\b${escaped}\\b[^(){};]*\\)\\s*->`, 'u')
      .test(method.body)) return 0;
  }
  const returnedExpression = (body, allowLocalLiteralCollection = false) => {
    const returned = /^\s*return\s+([\s\S]+);\s*$/u.exec(body);
    if (returned !== null) return returned[1];
    if (!allowLocalLiteralCollection) return null;
    const declaration = /^\s*(?:final\s+)?List\s*<[^;]+>\s+[A-Za-z_$][\w$]*\s*=\s*List\s*\.\s*of\s*\(/u.exec(body);
    if (declaration === null) return null;
    const end = matchingParenthesisEnd(body,
      declaration[0].lastIndexOf('('));
    if (end === null) return null;
    return /^\s*;\s*return\s+([\s\S]+);\s*$/u.exec(body.slice(end))?.[1]
      ?? null;
  };
  const countProducer = (expression, visited = new Set()) => {
    const text = expression.trim();
    let match = /^(?:(?:java\s*\.\s*util\s*\.\s*stream\s*\.\s*)?Stream|(?:java\s*\.\s*util\s*\.\s*)?List)\s*\.\s*of\s*\(/u.exec(text);
    let count;
    let tail;
    if (match !== null) {
      const open = match[0].lastIndexOf('(');
      const end = matchingParenthesisEnd(text, open);
      if (end === null) return null;
      const argumentsText = text.slice(open + 1, end - 1).trim();
      const args = argumentsText.length === 0 ? []
        : splitTopLevelArguments(argumentsText);
      count = args.length;
      tail = text.slice(end).trim();
      if (tail.length === 0 && args.every(argument =>
        /^DynamicTest\s*\.\s*dynamicTest\s*\(/u.test(argument.trim())))
        return count;
      if (/List\s*\.\s*of/u.test(match[0])) {
        const stream = /^\.\s*stream\s*\(\s*\)/u.exec(tail);
        if (stream === null) return null;
        tail = tail.slice(stream[0].length).trim();
      }
    } else if ((match = /^([A-Za-z_$][\w$]*)\s*\.\s*stream\s*\(\s*\)/u.exec(text)) !== null) {
      count = collections.get(match[1]);
      tail = text.slice(match[0].length).trim();
    } else if ((match = /^([A-Za-z_$][\w$]*)\s*\(\s*\)/u.exec(text)) !== null) {
      if (visited.has(match[1])) return null;
      const helpers = methods.filter(candidate =>
        candidate.scopeName === match[1] && candidate.parameterCount === 0
        && candidate.enclosingTypes[0]?.openBrace
          === method.enclosingTypes[0]?.openBrace);
      if (helpers.length !== 1) return null;
      const returned = returnedExpression(helpers[0].body);
      if (returned === null) return null;
      count = countProducer(returned, new Set(visited).add(match[1]));
      tail = text.slice(match[0].length).trim();
    } else if ((match = /^Stream\s*\.\s*iterate\s*\(/u.exec(text)) !== null) {
      const open = match[0].lastIndexOf('(');
      const end = matchingParenthesisEnd(text, open);
      if (end === null) return null;
      tail = text.slice(end).trim();
      const limit = /^\.\s*limit\s*\(\s*([A-Za-z_$][\w$]*)\s*\.\s*size\s*\(\s*\)\s*\)/u.exec(tail);
      if (limit === null) return null;
      const escaped = limit[1].replace(/[.*+?^${}()|[\]\\]/gu, '\\$&');
      const declarations = [...method.body.matchAll(new RegExp(
        `\\b${escaped}\\s*=\\s*List\\s*\\.\\s*of\\s*\\(`, 'gu'))];
      if (declarations.length !== 1) return null;
      const declaration = declarations[0];
      const listOpen = declaration.index + declaration[0].lastIndexOf('(');
      const listEnd = matchingParenthesisEnd(method.body, listOpen);
      if (listEnd === null) return null;
      const args = method.body.slice(listOpen + 1, listEnd - 1).trim();
      count = args.length === 0 ? 0 : splitTopLevelArguments(args).length;
      tail = tail.slice(limit[0].length).trim();
    } else return null;
    if (!Number.isSafeInteger(count) || count < 1) return null;
    const operation = /^\.\s*(map|flatMap)\s*\(/u.exec(tail);
    if (operation === null) return tail.length === 0 ? count : null;
    const open = operation[0].lastIndexOf('(');
    const end = matchingParenthesisEnd(tail, open);
    if (end === null || tail.slice(end).trim().length !== 0) return null;
    const callback = tail.slice(open + 1, end - 1);
    const arrow = callback.indexOf('->');
    if (arrow < 0) return null;
    const mapped = callback.slice(arrow + 2).trim();
    if (operation[1] === 'map') {
      if (!/^DynamicTest\s*\.\s*dynamicTest\s*\(/u.test(mapped)) return null;
      return count;
    }
    const childCount = countProducer(mapped, visited);
    return childCount === null ? null : count * childCount;
  };
  const returned = returnedExpression(method.body, true);
  return returned === null ? null : countProducer(returned);
}

function dynamicFactoryNodeCount(method, sourceText = null) {
  if (!method.testFactory) return 0;
  const literalCount = literalDynamicFactoryNodeCount(method, sourceText);
  if (literalCount !== null) return literalCount;
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
    const localHelperReceiver = !match[0].startsWith('.')
      || /\bthis\s*$/u.test(body.slice(0, match.index));
    if (localCallableNames.has(method) && localHelperReceiver) continue;
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
  const callableScopes = javaMethods(path, text);
  const append = (name, index, policy) => {
    const type = typeScopes.filter((candidate) =>
      candidate.openBrace < index && index < candidate.end)
      .sort((left, right) => (left.end - left.openBrace)
        - (right.end - right.openBrace))[0];
    if (type === undefined) return;
    const callable = callableScopes.find((candidate) =>
      candidate.openBrace < index && index < candidate.end);
    if (!policies.has(name)) policies.set(name, []);
    policies.get(name).push({
      ...policy,
      callableOpenBrace: callable?.openBrace ?? null,
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
      && (policy.callableOpenBrace === null
        || policy.callableOpenBrace === method.openBrace)
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
  const chainedApplicationRuns = [];
  for (const factory of body.matchAll(
    /\bSokletApplication\s*\.\s*fromConfig\s*\(/gu)) {
    const open = factory.index + factory[0].lastIndexOf('(');
    const end = matchingParenthesisEnd(body, open);
    if (end === null) continue;
    const immediateRun = /^\s*\.\s*run\s*\(/u.exec(body.slice(end));
    if (immediateRun === null) continue;
    chainedApplicationRuns.push((body.slice(factory.index, end)
      + immediateRun[0]).replace(/[.*+?^${}()|[\]\\]/gu, '\\$&'));
  }
  const applicationRunPattern = () => new RegExp(
    `(?:(?:\\bSokletApplication\\s*\\.\\s*run|\\b${applicationReceiver}\\s*\\.\\s*run)\\s*\\(|\\b(?:SokletApplication|${applicationReceiver})\\s*::\\s*run\\b${chainedApplicationRuns.length === 0 ? '' : `|${[...new Set(chainedApplicationRuns)].join('|')}`})`,
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
  if (/\bLifecyclePolicy\s*\.\s*(?:defaultInstance|fromDefaults)\s*\(\s*\)/u.test(body)) {
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
    const inlinePublic = /^(?:com\s*\.\s*soklet\s*\.\s*)?LifecyclePolicy\s*\.\s*(?:builder|defaultInstance|fromDefaults)\s*\(/u
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
      || matches(/(?:\bLifecyclePolicy\s*\.\s*(?:builder|defaultInstance|fromDefaults)\s*\(|\b(?:shutdownPolicy|cancellationPolicy|handlerPolicy|shortShutdownPolicy|managedLockProbeShutdownPolicy)\s*\()/u))
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
    dynamicNodeCount: dynamicFactoryNodeCount(method, sourceText),
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

function localHelperCalls(method, callableNames, fixtureMethods = []) {
  const calls = new Map();
  const arities = new Map();
  const constructorDelegations = new Set();
  const unresolvedNames = new Set();
  const qualifiedCandidates = new Map();
  const localReceivers = new Map();
  for (const declaration of method.body.matchAll(
    /(?:^|[;{}(\n])\s*(?:final\s+)?(?:var|[\w$.<>]+)\s+([A-Za-z_$][\w$]*)\s*=\s*new\s+([\w$.]+)\s*\(/gu)) {
    const [, receiver, type] = declaration;
    const declarationSite = declaration.index + declaration[0].indexOf(receiver);
    // Resource and for-initializer bindings have statement-local visibility,
    // which cannot borrow the surrounding method's range. Leave these to
    // explicit helper review rather than guessing their lexical endpoint.
    let parenthesisDepth = 0;
    for (const character of method.body.slice(0, declarationSite)) {
      if (character === '(') parenthesisDepth += 1;
      else if (character === ')') parenthesisDepth -= 1;
    }
    if (parenthesisDepth !== 0) continue;
    let visibleEnd = method.body.length;
    for (let offset = 0; offset < declarationSite; offset += 1) {
      if (method.body[offset] !== '{') continue;
      const end = matchingBraceEnd(method.body, offset, 'fixture receiver');
      if (end > declarationSite) visibleEnd = Math.min(visibleEnd, end);
    }
    if (!localReceivers.has(receiver)) localReceivers.set(receiver, []);
    localReceivers.get(receiver).push({ type, visibleStart: declarationSite, visibleEnd });
  }
  for (const name of callableNames) {
    const escaped = name.replace(/[.*+?^${}()|[\]\\]/gu, '\\$&');
    const patterns = [
      new RegExp(`(?<![\\w$.])${escaped}\\s*\\(`, 'gu'),
      new RegExp(`\\bthis\\s*\\.\\s*${escaped}\\s*\\(`, 'gu'),
      new RegExp(`\\bthis\\s*::\\s*${escaped}\\b`, 'gu'),
    ];
    const unqualified = patterns.some((pattern) =>
      new RegExp(pattern.source, pattern.flags).test(method.body));
    const qualifiedConstructedTypes = new Set();
    for (const constructed of method.body.matchAll(
      /\bnew\s+([\w$]+(?:\s*\.\s*[\w$]+)+)\s*\(/gu)) {
      const type = constructed[1].replace(/\s/gu, '');
      if (type.split('.').at(-1) !== name) continue;
      if (qualifiedConstructedTypes.has(type)) continue;
      qualifiedConstructedTypes.add(type);
      const candidates = fixtureMethods.flatMap((candidate, index) => {
        if (candidate.scopeKind !== 'CONSTRUCTOR'
            || candidate.scopeName !== name) return [];
        const chain = [...candidate.enclosingTypes].reverse()
          .map((owner) => owner.name).join('.');
        const packageName = candidate.path.slice(JUNIT_ROOT.length)
          .split('/').slice(0, -1).join('.');
        return type === chain || type === `${packageName}.${chain}`
          ? [index] : [];
      });
      if (candidates.length === 0) continue;
      const escapedType = type.replace(/[.*+?^${}()|[\]\\]/gu, '\\$&');
      patterns.push(new RegExp(`\\bnew\\s+${escapedType}\\s*\\(`, 'gu'));
      if (!unqualified) {
        if (!qualifiedCandidates.has(name)) qualifiedCandidates.set(name, new Set());
        for (const candidate of candidates) qualifiedCandidates.get(name).add(candidate);
      }
    }
    // Qualified simulator fixture runners are discoverable only from a
    // unique local constructor binding and the exact declaring helper body.
    // Arbitrary same-named external calls cannot borrow that lifecycle.
    for (const [receiver, types] of localReceivers) {
      if (name !== 'run') continue;
      if (types.length !== 1) continue;
      const escapedReceiver = receiver.replace(/[.*+?^${}()|[\]\\]/gu, '\\$&');
      const reassigned = new RegExp(`\\b${escapedReceiver}\\s*=(?!=)`, 'gu');
      if ([...method.body.matchAll(reassigned)].length !== 1) continue;
      const qualifiedPattern = new RegExp(`\\b${escapedReceiver}\\s*\\.\\s*${escaped}\\s*\\(`, 'gu');
      if (!qualifiedPattern.test(method.body)) continue;
      qualifiedPattern.lastIndex = 0;
      const { type, visibleStart, visibleEnd } = types[0];
      const candidates = fixtureMethods.flatMap((candidate, index) => {
        if (candidate.scopeKind !== 'HELPER' || candidate.scopeName !== name
            || !/\bSokletSimulator\s*\.\s*run\s*\(/u.test(candidate.body)
            || candidate.path !== method.path) return [];
        const chain = [...candidate.enclosingTypes].reverse()
          .map((owner) => owner.name).join('.');
        const packageName = candidate.path.slice(JUNIT_ROOT.length)
          .split('/').slice(0, -1).join('.');
        return type === candidate.enclosingTypes[0]?.name || type === chain
          || type === `${packageName}.${chain}` ? [index] : [];
      });
      if (candidates.length === 0) continue;
      if (new Set(candidates.map((index) => fixtureMethods[index]
        .enclosingTypes.map((owner) => owner.openBrace).join(':'))).size !== 1) continue;
      qualifiedPattern.lifecycleReceiverRange = { visibleStart, visibleEnd };
      patterns.push(qualifiedPattern);
      if (!unqualified) {
        if (!qualifiedCandidates.has(name)) qualifiedCandidates.set(name, new Set());
        for (const candidate of candidates) qualifiedCandidates.get(name).add(candidate);
      }
    }
    if (method.scopeKind === 'CONSTRUCTOR' && name === method.scopeName
        && /\bthis\s*\(/u.test(method.body)) {
      patterns.push(/\bthis\s*\(/gu);
      constructorDelegations.add(name);
    }
    let count = 0;
    for (const pattern of patterns) {
      for (const match of method.body.matchAll(pattern)) {
        if (pattern.lifecycleReceiverRange !== undefined
            && (match.index < pattern.lifecycleReceiverRange.visibleStart
              || match.index >= pattern.lifecycleReceiverRange.visibleEnd)) continue;
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
  return { arities, calls, constructorDelegations, qualifiedCandidates, unresolvedNames };
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
    // Explicitly imported test fixtures can own a real lifecycle in their
    // constructor. Retain the declaring source and policy context instead of
    // treating new Fixture(...) in the calling test as component-only work.
    const contexts = new Map([[path, {
      text, fieldPolicies, durationConstants, numericConstants,
    }]]);
    const fixtureTypes = [...fileMasked.matchAll(
      /\bimport\s+(?!static\b)([\w$.]+)\s*;/gu)]
      .map((match) => match[1]);
    for (const constructed of fileMasked.matchAll(/\bnew\s+([\w$]+(?:\s*\.\s*[\w$]+)+)\s*\(/gu)) {
      const type = constructed[1].replace(/\s/gu, '');
      fixtureTypes.push(type.startsWith('com.') ? type
        : `${fileMasked.match(/\bpackage\s+([\w.]+)\s*;/u)?.[1] ?? ''}.${type}`);
    }
    for (const importedType of new Set(fixtureTypes)) {
      const parts = importedType.split('.');
      const name = parts.at(-1);
      const escapedName = name.replace(/[.*+?^${}()|[\]\\]/gu, '\\$&');
      if (!new RegExp(`\\bnew\\s+(?:[\\w$]+\\s*\\.\\s*)*${escapedName}\\s*\\(`, 'u').test(fileMasked)) continue;
      for (let length = parts.length; length > 0; length -= 1) {
        const importedPath = `${JUNIT_ROOT}${parts.slice(0, length).join('/')}.java`;
        const importedText = texts.get(importedPath);
        if (importedText === undefined || importedPath === path) continue;
        const enclosingNames = parts.slice(length - 1).reverse();
        const constructors = javaMethods(importedPath, importedText)
          .filter((method) => method.scopeKind === 'CONSTRUCTOR'
            && method.scopeName === name
            && enclosingNames.every((type, index) =>
              method.enclosingTypes[index]?.name === type));
        if (constructors.length === 0) break;
        const masked = maskJavaSource(importedText);
        const context = {
          text: importedText,
          fieldPolicies: javaFieldPolicies(importedPath, importedText),
          durationConstants: javaDurationConstants(masked),
          numericConstants: javaNumericConstants(masked),
        };
        if (!constructors.some((method) => directLifecycleFacts(method,
          context.fieldPolicies, context.durationConstants,
          context.numericConstants, new Set(), importedText).hasExecution)) break;
        contexts.set(importedPath, context);
        methods.push(...constructors);
        break;
      }
    }
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
    const direct = methods.map((method) => {
      const context = contexts.get(method.path);
      return directLifecycleFacts(method, context.fieldPolicies,
        context.durationConstants, context.numericConstants,
        new Set(byName.keys()), context.text);
    });
    const helperAnalyses = methods.map((method) =>
      localHelperCalls(method, byName.keys(), methods));
    const helperCalls = helperAnalyses.map((analysis) => analysis.calls);
    const helperCandidates = (index, name) => {
      const method = methods[index];
      const analysis = helperAnalyses[index];
      const arities = analysis.arities.get(name);
      return (byName.get(name) ?? []).filter((candidate) => {
        const target = methods[candidate];
        const qualified = analysis.qualifiedCandidates?.get(name);
        return (qualified === undefined || qualified.has(candidate))
          && (arities === undefined || arities.has(target.parameterCount))
          && (!analysis.constructorDelegations.has(name)
            || (target.scopeKind === 'CONSTRUCTOR'
              && target.path === method.path
              && target.enclosingTypes[0]?.openBrace
                === method.enclosingTypes[0]?.openBrace));
      });
    };
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
        for (const candidate of helperCandidates(current, name)) {
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
        const candidates = helperCandidates(index, name)
          .filter((candidate) => !nextStack.has(candidate))
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
      const context = contexts.get(method.path);
      const summary = directLifecycleFacts(generationMethod,
        context.fieldPolicies, context.durationConstants,
        context.numericConstants, new Set(byName.keys()), context.text);
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
      .filter(({ method }) => method.path === path && ['TEST', 'SETUP_TEARDOWN']
        .includes(method.scopeKind))
      .map(({ index }) => index);
    const reachableHelpers = new Set();
    const helperEvidenceFor = (rootIndex) => {
      const evidence = new Map();
      const visit = (index, multiplier, stack) => {
        if (stack.has(index)) return;
        const nextStack = new Set(stack).add(index);
        for (const [name, callCount] of helperCalls[index]) {
          for (const candidate of helperCandidates(index, name)
            .filter((candidate) => !nextStack.has(candidate)
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
        importedLifecycleHelperEvidence: helperEvidenceFor(index)
          .filter((helper) => helper.path !== path)
          .map((helper) => ({
            ...helper,
            fileSha256: sha256(Buffer.from(texts.get(helper.path), 'utf8')),
          })),
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
    })).filter(({ facts, index, method }) => method.path === path && method.scopeKind === 'HELPER'
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
  // An exact, independently reviewed dynamic-node census can include bounded
  // connection helpers which the local callable scanner cannot propagate.
  // The positive per-node allowance is still bound to both source hashes.
  const reviewedDynamicHelperAllowance = source.testFactory
    && override.controlComposition === 'REVIEWED_DYNAMIC_NODE_MAX'
    && override.dynamicNodeCount === source.dynamicNodeCount
    && override.controlJoinMillis > 0;
  if (override.controlComposition !== undefined
      && (override.controlJoinMillis === undefined
        || (source.fixedControlWaitSites.length === 0
          && source.unresolvedFixedControlWaitCount === 0
          && !reviewedDynamicHelperAllowance)))
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
    ...(source.importedLifecycleHelperEvidence?.length > 0
      ? { importedLifecycleHelperEvidence:
        source.importedLifecycleHelperEvidence } : {}),
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
    // A reviewed node can itself contain a coherent sequential owner matrix.
    // The separate cardinality authority must bind it; the factory's node
    // count never supplies a generation multiplier for its sibling nodes.
    const reviewedNodeGenerationCount = source.testFactory
      ? REQUIRED_REVIEWED_GENERATION_COUNTS.get(lifecycleScopeKey(source)) : undefined;
    if (reviewedNodeGenerationCount !== undefined
        && generation.count !== reviewedNodeGenerationCount)
      fail(`Lifecycle dynamic-node owner count drifted: ${source.id}.`);
    if (source.testFactory && reviewedNodeGenerationCount === undefined
        && (generation.mode !== 'SINGLE'
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
        && generation.mode === 'SEQUENTIAL'
        && generation.count < source.generationSiteCount)
      fail(`Reviewed sequential lifecycle generation topology understates source evidence: ${source.id}.`);
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

export function taskNotificationSupplementGuard(texts) {
  const path = 'conformance/official/run.mjs';
  const raw = texts.get(path);
  if (raw === undefined) fail('Tasks notification process guard source is missing.');
  const masked = maskJavascriptSource(raw);
  const declaration = requirePattern(masked,
    /export\s+async\s+function\s+runTaskNotificationSupplement\s*\(options,\s*supervisor\)\s*\{/u,
    'Tasks notification process guard entrypoint')[0];
  const openBrace = declaration.index + declaration[0].lastIndexOf('{');
  const end = matchingBraceEnd(masked, openBrace,
    'Tasks notification process guard');
  const body = masked.slice(openBrace, end);
  requirePattern(body,
    /const\s+result\s*=\s*await\s+runBoundedCommand\(options\.javaExecutable,\s*\[[\s\S]*?taskNotificationSupplementMain,\s*\],\s*\{\s*timeoutMilliseconds:\s*120_000,\s*workingDirectory:\s*options\.projectRoot,\s*supervisor\s*\}\s*\)/u,
    'Tasks notification supervised 120-second command');
  if ([...body.matchAll(/\brunBoundedCommand\s*\(/gu)].length !== 1
      || [...body.matchAll(/\btimeoutMilliseconds\s*:/gu)].length !== 1
      || [...masked.matchAll(/\bawait\s+runTaskNotificationSupplement\s*\(/gu)].length !== 1)
    fail('Tasks notification process guard must have one supervised command and one awaited invocation.');
  requirePattern(masked,
    /supervisor\.waitForClose\(child,\s*timeoutMilliseconds\s*\+\s*5_000\)/u,
    'Tasks notification command close allowance');
  requirePattern(masked,
    /constructor\(\{\s*terminationGraceMilliseconds\s*=\s*2_000\s*\}\s*=\s*\{\}\)/u,
    'Tasks notification supervisor cleanup grace');
  requirePattern(masked,
    /async\s+function\s+forceStop\(record,\s*graceMilliseconds\s*=\s*2_000\)\s*\{[\s\S]*?await\s+waitForTreeExit\(record,\s*graceMilliseconds\);[\s\S]*?catch\s*\{[\s\S]*?await\s+waitForTreeExit\(record,\s*graceMilliseconds\);\s*\}\s*\}/u,
    'Tasks notification two-phase tree cleanup');
  const evidence = sourceLineEvidence(path, raw,
    /\{\s*timeoutMilliseconds:\s*120_000,\s*workingDirectory:\s*options\.projectRoot,\s*supervisor\s*\}/u,
    'Tasks notification process guard', masked);
  return {
    closureStatus: 'CLOSED',
    // Five seconds for process close plus two conservative four-second
    // termination attempts (timed-out branch and finally cleanup).
    cleanupFallbackMillis: 13_000,
    consumerCount: 1,
    deadlineConsumerCount: 0,
    evidence,
    helperSha256: sha256(Buffer.from(raw.slice(declaration.index, end), 'utf8')),
    id: 'TASK-NOTIFICATION-SOCKET-PROCESS-GUARD',
    millis: 120_000,
    requiredAction: 'NONE',
    variable: 'timeoutMilliseconds',
  };
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
    taskNotificationSupplementGuard(texts),
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
