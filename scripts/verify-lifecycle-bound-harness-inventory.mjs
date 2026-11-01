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
  "src/test/java/com/soklet/ReleaseInputCloseoutTests.java": ["malformedMultipartCannotInvokeAnOptionalBindingHandler","unsupportedMcpEncodingsRejectBeforeAdmissionAndDispatch","identityMcpEncodingsStillReachTheHandler","mcpHostOriginAndEndpointGuardsPrecedeContentEncoding","approvedOriginCanReadRateLimitRetryAfter"],
  "src/test/java/com/soklet/StreamingCallbackSelfWaitTests.java": ["writerAwaitFailsFast","writerCloseFailsFast","ownedSourceCleanupAwaitFailsFast","ownedSourceCleanupCloseFailsFast","cancelationCallbackAwaitFailsFast","terminationObserverAwaitFailsFast","asynchronousPublisherRequestAwaitFailsFast","asynchronousPublisherCancelAwaitFailsFast","sseInitializerAwaitFailsFast","sseInitializerCloseFailsFast"],
  "src/test/java/com/soklet/TransportCallbackSelfWaitTests.java": ["httpObserverAwaitDoesNotJoinItsOwnAcceptLoop","httpCollectorAwaitDoesNotJoinItsOwnAcceptLoop","sseObserverAwaitDoesNotJoinItsOwnAcceptLoop","sseCollectorAwaitDoesNotJoinItsOwnAcceptLoop","httpObserverCloseDoesNotJoinItsOwnAcceptLoop","httpCollectorCloseDoesNotJoinItsOwnAcceptLoop","sseObserverCloseDoesNotJoinItsOwnAcceptLoop","sseCollectorCloseDoesNotJoinItsOwnAcceptLoop","mcpAdmissionAwaitDoesNotJoinItsOwnWork","mcpAdmissionCloseDoesNotJoinItsOwnWork","mcpLimiterAwaitDoesNotJoinItsOwnWork","mcpLimiterCloseDoesNotJoinItsOwnWork","mcpCorsAwaitDoesNotJoinItsOwnWork","mcpCorsCloseDoesNotJoinItsOwnWork","mcpCollectorAwaitDoesNotJoinItsOwnWork","mcpCollectorCloseDoesNotJoinItsOwnWork","callbackGuardIsOwnerSpecificAndRestoresCallerContextAfterFailure","mcpCatalogPolicyAwaitDoesNotJoinItsOwnWork","mcpCatalogPolicyCloseDoesNotJoinItsOwnWork","mcpPolicyCancelationAwaitDoesNotJoinItsOwnWork","mcpHandlerCancelationAwaitDoesNotJoinItsOwnWork"],
  'src/test/java/com/soklet/HttpDurationBoundaryTests.java': ["submillisecondSelectorTimeoutStillExpiresAStalledRequest","acceptedHugeReadAndWriteTimeoutsDoNotBreakOrdinaryRequests"],
  'src/test/java/com/soklet/HttpTransportResponseOutcomeTests.java': ["notWrittenFailsDirectAndOpaqueConsumersWithoutClaimingSuccessOrReplacement"],
  'src/test/java/com/soklet/SseLateResponseOutcomeTests.java': ["lateHandlerDoesNotWaitForBlockedTimeoutMarshalerOrReportItsResponseAsWritten","lateHandlerAfterCustomTimeoutResponseIsWrittenStillReportsWriteFailureOnly","lateSseTimeoutResponseClosesOwnedFileChannel","lateSseTimeoutResponsePreservesBorrowedFileChannel"],
  'src/test/java/com/soklet/MultipartEdgeCaseTests.java': ["mixedCaseContentDispositionParametersBindToResourceArguments"],
  'src/test/java/com/soklet/McpRequestMetricOrderingTests.java': ["realWorkerCannotOvertakePausedSubmission","realDeadlineCannotOvertakePausedSubmission","realCancellationCannotOvertakePausedSubmission"],
  'src/test/java/com/soklet/internal/mcp/protocol/McpLegacySubscriptionAuthorizationDeadlineTests.java': ["ignoredTimedOutJuneAuthorizationCannotExtendItsLeaseOrOverlapAnotherCallback","ignoredTimedOutNovemberAuthorizationCannotExtendItsLeaseOrOverlapAnotherCallback","expiredQueuedJuneAuthorizationNeverInvokesTheCallback","expiredQueuedNovemberAuthorizationNeverInvokesTheCallback"],
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
  'src/test/java/com/soklet/SokletScopedRunTests.java': [
    'callbackRunsOnceOnCallerAfterReadinessAndReturnsTheFullCleanupResult',
    'callbackWithoutCheckedFailureRequiresNoCheckedThrowsDeclaration',
    'checkedCallbackFailureRetainsItsExactTypeAndIdentityAfterCleanup',
    'callbackAssertionErrorRemainsPrimaryWhenApplicationCleanupAlsoFails',
    'successfulBodyExposesTheExistingCleanupFailure',
    'incompleteShutdownThrowsAndSkipsApplicationCleanup',
    'callbackFailureRemainsPrimaryWhenShutdownIsIncomplete',
    'completeForcedShutdownCanReturnNormally',
    'startupFailureSkipsTheCallbackAndFinishesEligibleCleanup',
    'invalidScopedArgumentsDoNotClaimTheTransport',
    'callbackInterruptFlagIsRestoredAfterEligibleCleanup',
    'interruptionWhileJoiningCleanupCannotEndTheScopeEarly',
    'preInterruptedStartupSkipsTheCallbackAndRestoresInterruption',
    'externalShutdownDoesNotInterruptOrEndTheCallback',
  ],
  'src/test/java/com/soklet/SokletFailureEvidenceMonitorTests.java': [
    'canceledStartupAndCatchUpFailureFinishWhilePrimaryExceptionMonitorIsHeld',
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
  ["src/test/java/com/soklet/ResponseProcessingCleanupTests.java#interceptorFailureReleasesGeneratedAndSubmittedOwnedBodies#TEST", 2],
  ["src/test/java/com/soklet/ResponseProcessingCleanupTests.java#interceptorCanSelectAnEarlierGeneratedBodyOrBorrowItsChannel#TEST", 2],
  ["src/test/java/com/soklet/McpLegacyCatalogPaginationPublicRuntimeTests.java#oneOversizedLocalizedDescriptorHonorsTheFailurePolicyAndKeepsContinuationUsable#TEST", 2],
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
  // Final deep-dive matrices retain their independently reviewed owner counts.
  ["src/test/java/com/soklet/DeepDiveResponsePipelineTests.java#decoratorAndConfiguredHeadProjectionUseTheSameCompressionInputAsGet#TEST",2],
  ["src/test/java/com/soklet/DeepDiveResponsePipelineTests.java#finalizationFailureUsesCustomThrowableRecoveryWithoutRepeatingThePostprocessor#TEST",2],
  ["src/test/java/com/soklet/DeepDiveResponsePipelineTests.java#finiteSimulationConsumesOwnedChannelsAndPreservesBorrowedChannels#TEST",4],
  ["src/test/java/com/soklet/DeepDiveResponsePipelineTests.java#lateAcceptedSseFramingFailureProducesAResponseInSimulationAndOnTheWire#TEST",2],
  ["src/test/java/com/soklet/DeepDiveResponsePipelineTests.java#lateAndAsynchronousFirstWrapConsumerCannotDispatchAfterFailure#TEST",2],
  ["src/test/java/com/soklet/DeepDiveResponsePipelineTests.java#simulatedSseRejectionCleansAnUnsupportedReplacementAndObservesLogicalHeaders#TEST",2],
  ["src/test/java/com/soklet/HeadReplacementCompressionTests.java#freshBodylessFileAndStreamingReplacementsClearPriorHeadPlanning#TEST",3],
  ["src/test/java/com/soklet/HeadReplacementCompressionTests.java#freshHeadReplacementRetainsEncodingRejection#TEST",2],
  ["src/test/java/com/soklet/HeadReplacementCompressionTests.java#freshReplacementPlansTheFinalPostprocessedBodyForGetAndHead#TEST",4],
  ["src/test/java/com/soklet/InterceptorDefaultFinalizationTests.java#freshResponsesReceiveCorsDateAndHeadHandlingWithoutAPostprocessor#TEST",2],
  ["src/test/java/com/soklet/InternalResponsePostProcessingTests.java#liveSseRejectionsFinalizeInvalidBodyAndBothCapacityPathsOnce#TEST",3],
  ["src/test/java/com/soklet/McpLegacySessionTransportCapacityRuntimeTests.java#routineRenewalCapacityDoesNotRevokeTheStillValidGetGrant#TEST",2],
  ["src/test/java/com/soklet/McpLegacySessionTransportPublicRuntimeTests.java#reconciliationDuringGetAdmissionRetriesBothCooperativeAndStaleDecisions#TEST",4],
  ["src/test/java/com/soklet/McpObservationIsolationTests.java#blockedFinishAndTraceLoggingDoNotBlockAnotherConnection#TEST",2],
  ["src/test/java/com/soklet/ResponseFinalizationReleaseTests.java#copyingAnOwnedFileToAStreamDefersClosureThroughSuccessAndFailure#TEST",2],
  ["src/test/java/com/soklet/ResponseFinalizationReleaseTests.java#directInterceptorReplacementAndGeneratedCopiesAreProcessedOnce#TEST",2],
  ["src/test/java/com/soklet/ResponseFinalizationReleaseTests.java#nonIdempotentPostprocessorRunsOnceForGetHeadCorsAndErrors#TEST",6],
  ["src/test/java/com/soklet/ResponseFinalizationReleaseTests.java#wireFiniteAndStreamingWrappersDeliverTheirBytesAndCloseChannels#TEST",2],
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
  // Release closeout: preserve exact owner policies and include each new local
  // socket connection/read matrix in the source-bound finite control allowance.
  ...reviewedScopeFile("src/test/java/com/soklet/ReleaseInputCloseoutTests.java", "fe1aebcf4dc9a205869fb8cca69edc7b2c576a4b7eb31c2aebc4278c13b92eae", [
    ["unsupportedMcpEncodingsRejectBeforeAdmissionAndDispatch","0606350ad18799a0d2fd8fc4d4a47e358dfd454a3d868fc070ff8ea83d397112",{"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":1000},"controlJoinMillis":39000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/ReleaseInputCloseoutTests.java", "fe1aebcf4dc9a205869fb8cca69edc7b2c576a4b7eb31c2aebc4278c13b92eae", [
    ["identityMcpEncodingsStillReachTheHandler","bcec6aaf6f18068656cef16f7e913796d6b0538103eacd3e4540d3820bea23fe",{"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":1000},"controlJoinMillis":15000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/ReleaseInputCloseoutTests.java", "fe1aebcf4dc9a205869fb8cca69edc7b2c576a4b7eb31c2aebc4278c13b92eae", [
    ["mcpHostOriginAndEndpointGuardsPrecedeContentEncoding","4c2fdd2ca5351e76d678a239b05b63532ee201001c704ed4610e13a6859ccb6d",{"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":1000},"controlJoinMillis":9000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/ReleaseInputCloseoutTests.java", "fe1aebcf4dc9a205869fb8cca69edc7b2c576a4b7eb31c2aebc4278c13b92eae", [
    ["approvedOriginCanReadRateLimitRetryAfter","2a39dcc9936c2a745325e84212b5046ee76f1950a2fac4b80d729577009bbcd5",{"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":1000},"controlJoinMillis":6000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/StreamingCallbackSelfWaitTests.java", "c229265f87c141ce16668284b4592fef7e1b5fc5d01e79d8697e75bc67b85e6e", [
    ["writerAwaitFailsFast","17fc3e25d73f59cd321379bdadac1cb6ab265cffad8d23e4fb4a0fd022ae032f",{"phasePolicy":{"startupMillis":2000,"startupCancellationMillis":200,"gracefulShutdownMillis":200,"forcedShutdownMillis":200},"controlJoinMillis":15000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/StreamingCallbackSelfWaitTests.java", "c229265f87c141ce16668284b4592fef7e1b5fc5d01e79d8697e75bc67b85e6e", [
    ["writerCloseFailsFast","601218056db3bee5639f9098ef2491006caa1af78bed4a7affa4816dffa9b8a4",{"phasePolicy":{"startupMillis":2000,"startupCancellationMillis":200,"gracefulShutdownMillis":200,"forcedShutdownMillis":200},"controlJoinMillis":15000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/StreamingCallbackSelfWaitTests.java", "c229265f87c141ce16668284b4592fef7e1b5fc5d01e79d8697e75bc67b85e6e", [
    ["ownedSourceCleanupAwaitFailsFast","1c0ccedad72fb725f126dcb3f4970156450a991849fbd1739c32a9f0025c6813",{"phasePolicy":{"startupMillis":2000,"startupCancellationMillis":200,"gracefulShutdownMillis":200,"forcedShutdownMillis":200},"controlJoinMillis":15000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/StreamingCallbackSelfWaitTests.java", "c229265f87c141ce16668284b4592fef7e1b5fc5d01e79d8697e75bc67b85e6e", [
    ["ownedSourceCleanupCloseFailsFast","bb645057e0b9832b5fb1eebc73844b336fb22cfb27c78450714191740c6d9b16",{"phasePolicy":{"startupMillis":2000,"startupCancellationMillis":200,"gracefulShutdownMillis":200,"forcedShutdownMillis":200},"controlJoinMillis":15000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/StreamingCallbackSelfWaitTests.java", "c229265f87c141ce16668284b4592fef7e1b5fc5d01e79d8697e75bc67b85e6e", [
    ["cancelationCallbackAwaitFailsFast","78bbe03201dee4ff26511771c4db448388585e71627c9e72e91172a4f1052d9e",{"phasePolicy":{"startupMillis":2000,"startupCancellationMillis":200,"gracefulShutdownMillis":200,"forcedShutdownMillis":200},"controlJoinMillis":15000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/StreamingCallbackSelfWaitTests.java", "c229265f87c141ce16668284b4592fef7e1b5fc5d01e79d8697e75bc67b85e6e", [
    ["terminationObserverAwaitFailsFast","583e76490e634c717b3f061883409277d285786da843647d8aef8661c4377da0",{"phasePolicy":{"startupMillis":2000,"startupCancellationMillis":200,"gracefulShutdownMillis":200,"forcedShutdownMillis":200},"controlJoinMillis":15000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/StreamingCallbackSelfWaitTests.java", "c229265f87c141ce16668284b4592fef7e1b5fc5d01e79d8697e75bc67b85e6e", [
    ["asynchronousPublisherRequestAwaitFailsFast","cb356ecbf58a5de19104b88d3dd0017ad882368b01816ea866a9fb6009fcb55c",{"phasePolicy":{"startupMillis":2000,"startupCancellationMillis":200,"gracefulShutdownMillis":200,"forcedShutdownMillis":200},"controlJoinMillis":15000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/StreamingCallbackSelfWaitTests.java", "c229265f87c141ce16668284b4592fef7e1b5fc5d01e79d8697e75bc67b85e6e", [
    ["asynchronousPublisherCancelAwaitFailsFast","6dab5e7b2e6431f67570e5ccc024257eb40a21bfda384ba79d3e9be9f166b4d9",{"phasePolicy":{"startupMillis":2000,"startupCancellationMillis":200,"gracefulShutdownMillis":200,"forcedShutdownMillis":200},"controlJoinMillis":15000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/StreamingCallbackSelfWaitTests.java", "c229265f87c141ce16668284b4592fef7e1b5fc5d01e79d8697e75bc67b85e6e", [
    ["sseInitializerAwaitFailsFast","96dea94cddddef64c46bff877c4f7d0caa8aaa84d1eebf2096e45fd8f3e2f57e",{"phasePolicy":{"startupMillis":2000,"startupCancellationMillis":200,"gracefulShutdownMillis":200,"forcedShutdownMillis":200},"controlJoinMillis":7000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/StreamingCallbackSelfWaitTests.java", "c229265f87c141ce16668284b4592fef7e1b5fc5d01e79d8697e75bc67b85e6e", [
    ["sseInitializerCloseFailsFast","f3ce57b87550749f402d2f0ddcb1972bb4c234e3261940f315cdc092dc179906",{"phasePolicy":{"startupMillis":2000,"startupCancellationMillis":200,"gracefulShutdownMillis":200,"forcedShutdownMillis":200},"controlJoinMillis":7000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/TransportCallbackSelfWaitTests.java", "62961d8fcc1da09c2cdbb5cbe5fed1c1ff9db0b7a748e7a09aba25fa27bd0c3c", [
    ["httpObserverAwaitDoesNotJoinItsOwnAcceptLoop","6bbd48b5072e72327dd5b953e44cf6506b1bdd7b60cb1ee3f558a71b5859bcee",{"phasePolicy":{"startupMillis":2000,"startupCancellationMillis":200,"gracefulShutdownMillis":200,"forcedShutdownMillis":200},"controlJoinMillis":7000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/TransportCallbackSelfWaitTests.java", "62961d8fcc1da09c2cdbb5cbe5fed1c1ff9db0b7a748e7a09aba25fa27bd0c3c", [
    ["httpCollectorAwaitDoesNotJoinItsOwnAcceptLoop","4363fe4466197a094337de8a44e4737defd293a592ba2e48cfe3f765d20bb38a",{"phasePolicy":{"startupMillis":2000,"startupCancellationMillis":200,"gracefulShutdownMillis":200,"forcedShutdownMillis":200},"controlJoinMillis":7000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/TransportCallbackSelfWaitTests.java", "62961d8fcc1da09c2cdbb5cbe5fed1c1ff9db0b7a748e7a09aba25fa27bd0c3c", [
    ["sseObserverAwaitDoesNotJoinItsOwnAcceptLoop","ddfdc01f1606e3fa0643c44302974ab9880ea50cd8034c53baaec49eef56b640",{"phasePolicy":{"startupMillis":2000,"startupCancellationMillis":200,"gracefulShutdownMillis":200,"forcedShutdownMillis":200},"controlJoinMillis":7000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/TransportCallbackSelfWaitTests.java", "62961d8fcc1da09c2cdbb5cbe5fed1c1ff9db0b7a748e7a09aba25fa27bd0c3c", [
    ["sseCollectorAwaitDoesNotJoinItsOwnAcceptLoop","b595c80fae788447e7932cf3d8e1e2c1945f227d91c9f8b0fb366e814da3f459",{"phasePolicy":{"startupMillis":2000,"startupCancellationMillis":200,"gracefulShutdownMillis":200,"forcedShutdownMillis":200},"controlJoinMillis":7000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/TransportCallbackSelfWaitTests.java", "62961d8fcc1da09c2cdbb5cbe5fed1c1ff9db0b7a748e7a09aba25fa27bd0c3c", [
    ["httpObserverCloseDoesNotJoinItsOwnAcceptLoop","90f3c99d7b36ad7ac2d8ef5c82fd93979ed3b7d5c38c9d68b7bf7c3d519bcabc",{"phasePolicy":{"startupMillis":2000,"startupCancellationMillis":200,"gracefulShutdownMillis":200,"forcedShutdownMillis":200},"controlJoinMillis":7000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/TransportCallbackSelfWaitTests.java", "62961d8fcc1da09c2cdbb5cbe5fed1c1ff9db0b7a748e7a09aba25fa27bd0c3c", [
    ["httpCollectorCloseDoesNotJoinItsOwnAcceptLoop","f64b6c2d9df046179a7f52f2c868228529cb79c5d95faefbcbd9af239ab24b3c",{"phasePolicy":{"startupMillis":2000,"startupCancellationMillis":200,"gracefulShutdownMillis":200,"forcedShutdownMillis":200},"controlJoinMillis":7000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/TransportCallbackSelfWaitTests.java", "62961d8fcc1da09c2cdbb5cbe5fed1c1ff9db0b7a748e7a09aba25fa27bd0c3c", [
    ["sseObserverCloseDoesNotJoinItsOwnAcceptLoop","80b43b00eb6547207c2d41884c42912f35571e1f567ce5387c8df7f28e3add17",{"phasePolicy":{"startupMillis":2000,"startupCancellationMillis":200,"gracefulShutdownMillis":200,"forcedShutdownMillis":200},"controlJoinMillis":7000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/TransportCallbackSelfWaitTests.java", "62961d8fcc1da09c2cdbb5cbe5fed1c1ff9db0b7a748e7a09aba25fa27bd0c3c", [
    ["sseCollectorCloseDoesNotJoinItsOwnAcceptLoop","871c8dadee4ca690918dc3f6a0ac35e73d5e0996399250fdb5cf2d80bef0c644",{"phasePolicy":{"startupMillis":2000,"startupCancellationMillis":200,"gracefulShutdownMillis":200,"forcedShutdownMillis":200},"controlJoinMillis":7000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/TransportCallbackSelfWaitTests.java", "62961d8fcc1da09c2cdbb5cbe5fed1c1ff9db0b7a748e7a09aba25fa27bd0c3c", [
    ["mcpAdmissionAwaitDoesNotJoinItsOwnWork","284bfcafb0452e534604520edd8e3acb4bbf79914fadf974514750c87ad5a559",{"phasePolicy":{"startupMillis":2000,"startupCancellationMillis":200,"gracefulShutdownMillis":200,"forcedShutdownMillis":200},"controlJoinMillis":17000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/TransportCallbackSelfWaitTests.java", "62961d8fcc1da09c2cdbb5cbe5fed1c1ff9db0b7a748e7a09aba25fa27bd0c3c", [
    ["mcpAdmissionCloseDoesNotJoinItsOwnWork","f75c33fa99c5464feba4d4e372a4d2345adc44c63b40e5f4e6e41fcb4c471241",{"phasePolicy":{"startupMillis":2000,"startupCancellationMillis":200,"gracefulShutdownMillis":200,"forcedShutdownMillis":200},"controlJoinMillis":17000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/TransportCallbackSelfWaitTests.java", "62961d8fcc1da09c2cdbb5cbe5fed1c1ff9db0b7a748e7a09aba25fa27bd0c3c", [
    ["mcpLimiterAwaitDoesNotJoinItsOwnWork","90b4ace5b293671c70206c5364db0ecd3718f63212c032e5583fad94d9d0d0d9",{"phasePolicy":{"startupMillis":2000,"startupCancellationMillis":200,"gracefulShutdownMillis":200,"forcedShutdownMillis":200},"controlJoinMillis":17000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/TransportCallbackSelfWaitTests.java", "62961d8fcc1da09c2cdbb5cbe5fed1c1ff9db0b7a748e7a09aba25fa27bd0c3c", [
    ["mcpLimiterCloseDoesNotJoinItsOwnWork","42d5d393e56ea2c92e591709eaeeb503d29cd27cb66cf930c1909a0a3ee44a9a",{"phasePolicy":{"startupMillis":2000,"startupCancellationMillis":200,"gracefulShutdownMillis":200,"forcedShutdownMillis":200},"controlJoinMillis":17000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/TransportCallbackSelfWaitTests.java", "62961d8fcc1da09c2cdbb5cbe5fed1c1ff9db0b7a748e7a09aba25fa27bd0c3c", [
    ["mcpCorsAwaitDoesNotJoinItsOwnWork","941fb6831295d875abc08a248dd248edeb417c739ee8991017abfa6df1182993",{"phasePolicy":{"startupMillis":2000,"startupCancellationMillis":200,"gracefulShutdownMillis":200,"forcedShutdownMillis":200},"controlJoinMillis":17000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/TransportCallbackSelfWaitTests.java", "62961d8fcc1da09c2cdbb5cbe5fed1c1ff9db0b7a748e7a09aba25fa27bd0c3c", [
    ["mcpCorsCloseDoesNotJoinItsOwnWork","81dc7c35777ba7696aa3852b6b2cc09cfe4d501d0b1351db5067641cc0ea8899",{"phasePolicy":{"startupMillis":2000,"startupCancellationMillis":200,"gracefulShutdownMillis":200,"forcedShutdownMillis":200},"controlJoinMillis":17000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/TransportCallbackSelfWaitTests.java", "62961d8fcc1da09c2cdbb5cbe5fed1c1ff9db0b7a748e7a09aba25fa27bd0c3c", [
    ["mcpCollectorAwaitDoesNotJoinItsOwnWork","bc335b5412a7249875e2b63ddb0920368c1ae111c4f9a7a6c1670db66c23916a",{"phasePolicy":{"startupMillis":2000,"startupCancellationMillis":200,"gracefulShutdownMillis":200,"forcedShutdownMillis":200},"controlJoinMillis":17000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/TransportCallbackSelfWaitTests.java", "62961d8fcc1da09c2cdbb5cbe5fed1c1ff9db0b7a748e7a09aba25fa27bd0c3c", [
    ["mcpCollectorCloseDoesNotJoinItsOwnWork","9006a7cb33f027c5308a742926893e3fc77f690452fcae5e62c944f2cec3df07",{"phasePolicy":{"startupMillis":2000,"startupCancellationMillis":200,"gracefulShutdownMillis":200,"forcedShutdownMillis":200},"controlJoinMillis":17000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/TransportCallbackSelfWaitTests.java", "62961d8fcc1da09c2cdbb5cbe5fed1c1ff9db0b7a748e7a09aba25fa27bd0c3c", [
    ["mcpCatalogPolicyAwaitDoesNotJoinItsOwnWork","0c0806b7e0de1d91c36c8b43e3f7d7a1513437d4b82b7ee523e05ce927a6cc7d",{"phasePolicy":{"startupMillis":2000,"startupCancellationMillis":200,"gracefulShutdownMillis":200,"forcedShutdownMillis":200},"controlJoinMillis":17000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/TransportCallbackSelfWaitTests.java", "62961d8fcc1da09c2cdbb5cbe5fed1c1ff9db0b7a748e7a09aba25fa27bd0c3c", [
    ["mcpCatalogPolicyCloseDoesNotJoinItsOwnWork","f1300cfc4efac9ebe7505ceff212130c212b9c734f0f81aed5a5cc045059b610",{"phasePolicy":{"startupMillis":2000,"startupCancellationMillis":200,"gracefulShutdownMillis":200,"forcedShutdownMillis":200},"controlJoinMillis":17000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/TransportCallbackSelfWaitTests.java", "62961d8fcc1da09c2cdbb5cbe5fed1c1ff9db0b7a748e7a09aba25fa27bd0c3c", [
    ["mcpPolicyCancelationAwaitDoesNotJoinItsOwnWork","19ecc2b786a349d2a889f7111094d6cb71560a258307326d4d08ea485a49ae47",{"phasePolicy":{"startupMillis":2000,"startupCancellationMillis":200,"gracefulShutdownMillis":200,"forcedShutdownMillis":200},"controlJoinMillis":17000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/TransportCallbackSelfWaitTests.java", "62961d8fcc1da09c2cdbb5cbe5fed1c1ff9db0b7a748e7a09aba25fa27bd0c3c", [
    ["mcpHandlerCancelationAwaitDoesNotJoinItsOwnWork","9667482109d6650aad256dadb1ffc8df27c721f36032a059703cc42abaa1a54a",{"phasePolicy":{"startupMillis":2000,"startupCancellationMillis":200,"gracefulShutdownMillis":200,"forcedShutdownMillis":200},"controlJoinMillis":17000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/TransportCallbackSelfWaitTests.java", "62961d8fcc1da09c2cdbb5cbe5fed1c1ff9db0b7a748e7a09aba25fa27bd0c3c", [
    ["callbackGuardIsOwnerSpecificAndRestoresCallerContextAfterFailure","384de37e47d81e223ea25e0e492118f044d971bdfcfced58b0ee8e6aa9de7bc8",{"phasePolicy":{"startupMillis":2000,"startupCancellationMillis":200,"gracefulShutdownMillis":200,"forcedShutdownMillis":200},"controlJoinMillis":0}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/HttpResponseStreamEnrollmentTests.java", "fd7da08d70d7894ded81c95e2d24c4d9efbb9863b7a4fc9d0e4a3f1cf5765363", [
    ["rebuildingDecoratorUsesHandoffFallbackWhileDefaultTransportStillCompletesStream","d7d540f857575bf3067f0d4684bc3ef4435fb5a100c40f706a16ec72e367c31f",{"phasePolicy":{"startupMillis":2000,"startupCancellationMillis":200,"gracefulShutdownMillis":200,"forcedShutdownMillis":200},"controlJoinMillis":26000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  // Round 11: two finite simulator loops own four/eight sequential generations;
  // wire fixtures install the exact local policy helper and include socket reads.
  ...reviewedScopeFile("src/test/java/com/soklet/Round11LifecycleMetricsRegressionTests.java", "5380316e889f5dc8a4212aea3d92dd3faf3cd6faa2886df598339082bc2ee784", [
    ["preDispatchWrapperFailuresReachBothFinishObservers","cd3e7acc18c54293c464cfb67c282d227eabc8ba21631d5a857f13e46a1380ff",{"phasePolicy":{"startupMillis":1000,"startupCancellationMillis":100,"gracefulShutdownMillis":100,"forcedShutdownMillis":100},"generation":{"count":4,"mode":"SEQUENTIAL","complete":4,"prior":3,"incomplete":1}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/Round11LifecycleMetricsRegressionTests.java", "5380316e889f5dc8a4212aea3d92dd3faf3cd6faa2886df598339082bc2ee784", [
    ["sseSimulatorProjectsRejectedResponsesLikeTheFiniteWireSerializer","0219e370b84c635bd592d98ca82451dc74759b636ed99b9f208e306db669087e",{"phasePolicy":{"startupMillis":1000,"startupCancellationMillis":100,"gracefulShutdownMillis":100,"forcedShutdownMillis":100},"generation":{"count":8,"mode":"SEQUENTIAL","complete":8,"prior":7,"incomplete":1}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/TransportRound11WireTests.java", "c7f4d5bc267e40cb5fe10f1e27c5b1a2ee996116f2cc7a2b1dce2b32133a3b3b", [
    ["knownOversizedContentLengthIsRejectedBefore100Continue","6845d2e12bec116b7898ca65e8db1556830c1bef8e00cddf759a8733f55ad3f1",{"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":1000},"controlJoinMillis":2000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/TransportRound11WireTests.java", "c7f4d5bc267e40cb5fe10f1e27c5b1a2ee996116f2cc7a2b1dce2b32133a3b3b", [
    ["rejectedRewrittenHandshakeReportsEffectiveRequestAndRoute","ad497f77e9fe1fc23207365022e8edfe03f65a8e8a26b6b76b5c31b94419a9f6",{"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":1000},"controlJoinMillis":4000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/TransportRound11WireTests.java", "c7f4d5bc267e40cb5fe10f1e27c5b1a2ee996116f2cc7a2b1dce2b32133a3b3b", [
    ["sseBindFailureRetainsItsDiagnosableCauseInTheParticipantResult","c87e1e1aee34a596f65175e1cbaaba407a0ee4fe90e557c673877215b5eb40ce",{"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":1000},"controlJoinMillis":0}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SseHandshakeOutcomeRuntimeTests.java", "6d7781eafe8226c62f3e1ebb303c565c53c085add9660b49aa1d0ec8a8d90459", [
    ["capacityMarshalerFailureWritesFailsafeAndPreservesItsCause","1e398b439ca1ea00b45a209e8da046df943438222467d5492f50524f7cf69527",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":300,"forcedShutdownMillis":1000},"controlJoinMillis":16000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/HttpDurationBoundaryTests.java", "142e80c3c09a7e3afc45b5614b19d07ccdd2c4b47b2e8628aa613a195123c72d", [
    ["submillisecondSelectorTimeoutStillExpiresAStalledRequest","4bd8ed68e035be3ebd418973b82647fd89a474b8cdc547c476b0ef942c56a737",{"controlJoinMillis":6000}],
    ["acceptedHugeReadAndWriteTimeoutsDoNotBreakOrdinaryRequests","6eca1150d09730dee8a079f0eb9d0785decc00e197f657eb5ca06ed6d905bc8a",{"controlJoinMillis":6000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/HttpTransportResponseOutcomeTests.java", "60fb95984e6a8e4d2ff38601a0711d19c0ad261b3e42bffabb1d4355beba0696", [
    ["notWrittenFailsDirectAndOpaqueConsumersWithoutClaimingSuccessOrReplacement","4540c7c3c5c8934cf1e561aeabb1e1e8453915a4f09e19bb694662dbfdc25be8",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SokletSimulatorIsolationTests.java", "eb7752f519e2b19d0b0d5127606dd63468295e05738b3b7086773d30c68823b2", [
    ["successfulStartupBeforeWrappedDeadlineRunsTheBody","4c6f30e21494af5df3f7298d90a7df72e79fb7f7022f2d25596fad7f6e072986",{"phasePolicy":{"startupMillis":1,"startupCancellationMillis":1,"gracefulShutdownMillis":1,"forcedShutdownMillis":1}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SseLateResponseOutcomeTests.java", "e420e7be432944ee33a1fff45e7e4da6d70e33a046bb783b00a21cf7cfa226c1", [
    ["lateHandlerDoesNotWaitForBlockedTimeoutMarshalerOrReportItsResponseAsWritten","4ceaf87ae44bbe06afcfa2a326cac89ff294ba8545515562b8f5e1e866420064",{"controlJoinMillis":35000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
    ["lateHandlerAfterCustomTimeoutResponseIsWrittenStillReportsWriteFailureOnly","218c13aa51ef6a6c1b7851dcf7a478dd00969f6108dd093b782d5f8b5e8286a4",{"controlJoinMillis":35000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
    ["lateSseTimeoutResponseClosesOwnedFileChannel","56448094919f4e520dd93a8bc888872d6defe2e6e219feb0f7e6243bccf12da7",{"controlJoinMillis":35000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
    ["lateSseTimeoutResponsePreservesBorrowedFileChannel","56ddb2af9a50576f8ad01aa46773e31d589b36140c9fb84cc46dc3c6d2bdc4f7",{"controlJoinMillis":35000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpSimulationLifecyclePhaseTests.java", "745d4366596625c484e3b82db19653bf6a0998f6a9b037ad723d47c2c390940c", [
    ["simulationDrainDeadlinePreservesBudgetAcrossMonotonicClockWrap","0121691b7de2f8e4e002605232631d2dc85611e5f0512cf21bcb9702dc0f4acb",{"controlledLifecycleCoreMillis":5000,"controlJoinMillis":15000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpRequestMetricOrderingTests.java", "5f213ae8c36d8e3ce99bdbecfdb1b4169c06bf0512c0e55725eb2b4779ca2313", [
    ["realWorkerCannotOvertakePausedSubmission","34562f58e792729015c5afdbe91842842c45788a610aa80d8b17072f4c0a4045",{"controlJoinMillis":45000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
    ["realDeadlineCannotOvertakePausedSubmission","f1e5b305b73ce1d6a19cf31f30866b27f8d86dfc66bf7fece9c11a45bfbad882",{"controlJoinMillis":45000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
    ["realCancellationCannotOvertakePausedSubmission","e527cf38d8194c12bc0231175933f028e4dd46d72a774601104c283a19691149",{"controlJoinMillis":45000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpLegacySubscriptionAuthorizationDeadlineTests.java", "8db348c60b0d0d3726491252d982396eaa76ad41730c599a1016fa8003b2025c", [
    ["ignoredTimedOutJuneAuthorizationCannotExtendItsLeaseOrOverlapAnotherCallback","94dddbe84973b401ea1c8c64830382d650f44fc3c48a353ae0e0304b58334c1f",{"controlledLifecycleCoreMillis":5000,"controlJoinMillis":130000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","requiredAction":"RAISE_OUTER_BOUND"}],
    ["ignoredTimedOutNovemberAuthorizationCannotExtendItsLeaseOrOverlapAnotherCallback","271a661e04d63085983c092b6cad7eaf0a6223bfa46c85540a296c1d396df839",{"controlledLifecycleCoreMillis":5000,"controlJoinMillis":130000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","requiredAction":"RAISE_OUTER_BOUND"}],
    ["expiredQueuedJuneAuthorizationNeverInvokesTheCallback","40a4f98b54ff86028bb17f4437c6597e9a21c2e619882b12e057771658b54799",{"controlledLifecycleCoreMillis":5000,"controlJoinMillis":100000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","requiredAction":"RAISE_OUTER_BOUND"}],
    ["expiredQueuedNovemberAuthorizationNeverInvokesTheCallback","154b96858296db7a753527091134c3e2d149a3e34757918311e70fe010182f57",{"controlledLifecycleCoreMillis":5000,"controlJoinMillis":100000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","requiredAction":"RAISE_OUTER_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/TransportStartupFailureTests.java", "d5568c75de818bf13739c5b38d62105f0c3bd5ab341200386e546ced6d2a84c9", [
    ["startupFailureTakesPrecedenceWhenRollbackCannotProveTermination","9a8a3df0ea628c855c5282a50a9631fba33dc5eacf8160e94a2163bf2a41a7ad",{"phasePolicy":{"startupMillis":2000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/ResponseErrorMappingTests.java", "26af323cfc5197e8166b5cb7662a2cb85b8b56f20e873ee643ae8a85253a09d2", [
    ["bodylessMarshalerHooksReleaseOwnedChannelsAndPreserveBorrowedChannels","e5616ee8d6c640be5a0fc3f0ddec9b580da357837ede420e0f1393566b4c8dd1",{"generation":{"count":12,"mode":"SEQUENTIAL","complete":12,"prior":11,"incomplete":1}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/AdvancedTests.java", "3cf7703029251482e9f0368ec9848d1af527965a3a41aed123f22abfea53e9a3", [
    ["testSSERaceConditionOnConcurrentConnectionsAndDisconnections","651d38808f15a0c369b33ae31503855b799f58cac15beeb22680404e364812d4",{"controlJoinMillis":10500,"controlComposition":"REVIEWED_CONCURRENT_MAX"}],
    ["testConcurrentRequestProcessing","2d1b94698f88c901172d01ed3c9084ccc8c71f53d9c97bdc9c321af3011f589f",{"generation":{"count":3,"mode":"MIXED_MAX_PLUS_SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":45000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","requiredAction":"RAISE_OUTER_BOUND"}],
    ["testSseServerClearsCachesOnStop","c7ecce261e4007301031eec89bfd234d918bdda98e3d60bd37a42fbdd3d18d11",{"controlJoinMillis":4000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["testLargeRequestBodyMemoryHandling","6ba2c31887fc2184db976f14c9ae071c3c015734a4dca969773c651ddf60ff65",{"generation":{"count":11,"mode":"SEQUENTIAL","complete":11,"prior":10,"incomplete":1},"controlJoinMillis":100,"requiredAction":"RAISE_OUTER_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/BuiltInTransportLifecycleAdapterTests.java", "15f29083d2dfdf367f98d9ef7fe36d0e68ca3c87a25be3754707b37c13371258", [
    ["admissionIsClosedUntilReadinessAndShutdownBeforeReadinessSealsIt","b0fbc6599143c7cf95856f40ca7a5bb2593d8ce8d79b371f3e13700f9f9b863f",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlledLifecycleCoreMillis":0}],
    ["positiveResidualAndUnknownBothRetainEvidenceWithoutRelease","eed0e5efd87ca55d8b6edd92b1e48f9eaa4bc07b044818317ba84539e7bdde0e",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlledLifecycleCoreMillis":0}],
    ["completedResultIsPublishedOnlyAfterCoordinatorRoleRelease","4a728c54f345a68a3ac1c2ead8e2a6def0076690bbb748cfe60e1d36446ec933",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlledLifecycleCoreMillis":0}],
    ["exactGenerationOperationsRejectForeignTokensWithoutMutation","ff1b277adb13768eb39a9e28b2a43a292e48b75af80cf0ca8439c542f6a8535c",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlledLifecycleCoreMillis":0}],
  ]),
  // The added 205 wire regression changes the owning file only; both existing
  // restart callables retain their independently checked exact source hashes.
  ...reviewedScopeFile("src/test/java/com/soklet/DefaultHttpServerTests.java", "7d530d5ade2424bdf30b40dffea22645193217cfe9746df3a5b2c3f694a1cada", [
    ["earlySetupErrorRetainsIdentityCleansGenerationAndAllowsHttpRestart","8686b0fce8b5bb5579eee51892ce418c6ee7c03b1a3a013b4e7bb885e368b843",{"generation":{"count":2,"mode":"MIXED_MAX_PLUS_SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":5000}],
    ["staleHttpEventLoopFailureDoesNotClobberRestartedServer","cec3c46b78a87dd61c5fc5fe49b5ea59e9c13f4e2250a7948428bffa8c12ba4e",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/ExternallyCoordinatedTransportLifecycleAdapterTests.java", "3e174ec987706f22557041c10810d8d3003ead5dee2edc23b0ef184681a84f4e", [
    ["externalGenerationDefersCommitAndAdmissionAndPublishesExactOwnerResult","6b5864c53929481b7a492d60067de53b40b8f0810b73882ec8c7aba74a4cc100",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlledLifecycleCoreMillis":0}],
    ["completedExternalGenerationPermanentlyRejectsStandaloneAndSecondOwner","4184d50695629979c4c1f07e709bc23467266d540875960529ebbf3a5fc114c4",{"generation":{"count":2,"mode":"ONE_FULL_PLUS_PREINIT_REJECTIONS","complete":1,"prior":0,"incomplete":1},"controlledLifecycleCoreMillis":0}],
    ["externalUnexpectedFailureRecordsBeforeOneOwnerCallbackWithoutCoordinating","466a3a5f01f811e4d2dee9604fb61ab2b1804215d2a0d078879b697fc77ea4b8",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlledLifecycleCoreMillis":0}],
    ["externalStartFailureRecordsExactCauseWithoutLaunchingCoordinator","e197dd306e2a0d512d6c064c5673b54a961c1566963d28a849418afc4bbadf8a",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlledLifecycleCoreMillis":0}],
    ["externalSelfStopPublishesIntentBeforeOwnerScopedWaitFailsFast","80b2f130651f5b7f7a03444787899e2b42e6e82c05f7b3ec251201b0444ae4e0",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlledLifecycleCoreMillis":0}],
    ["releaseFailureMustBeFoldedIntoDowngradedOwnerResultBeforePublication","21c32eddc52b6be4003e6b6d696c28cdd417f434c4b8d3ecd9c991d5c9bf84fb",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlledLifecycleCoreMillis":0}],
    ["ownerFallbackPublicationReleasesWaitersAfterStrictValidationFailure","4a03dabae4fd4a8b644cbfed7793159f3211b3ae203c4c64428510bd29167061",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlledLifecycleCoreMillis":0}],
    ["mcpForwardsTheExactExternalGenerationAndParticipantEvidence","19f51a2ea945084f4366567253ff6a5811055a40ecbfa3f6d491625b1eb4cfa4",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlledLifecycleCoreMillis":0}],
  ]),
  // The owned and caller-owned channel cases each start and stop a fresh HTTP
  // server sequentially. Each exchange has a 2-second connection retry and a
  // 5-second socket-read bound, for 14 seconds of control work across both cases.
  ...reviewedScopeFile("src/test/java/com/soklet/IntegrationTests.java", "beedd9c12a7d7dccbec6bdca9bc22f6a723ed6e2bf3238835b32e35917654499", [
    ["responseCompressionRawHeadPreservesFileChannelOwnershipAndHypotheticalLength","7f6dc0f5331f204edeb1bc9c2d0880bb5957bae38175e5b3ce12122f6b052377",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":14000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpCatalogAccessLocalizationPublicRuntimeTests.java", "05555b9d28ee66fa2e699c53dc4e4f65833429469941f82aed967a15ca06e8fc", [
    ["filteredTenantCatalogsLocalizeOnlySurvivingStableOwners","510b49dbf487de244ee0edd18699dfe85452b14d9d7a2f6597db10ac547263f6",{"controlJoinMillis":60000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpDeferredTaskOutputSafetyTests.java", "0216f5397bbe7dbe479f0290943d408442769b83821139a6773886a5014f5b14", [
    ["nonCompletedTaskReadsDoNotInitializePolicyOrLocalization","e3efa72d3b6b9ff4fc04d18fd3794d0b317f7a6e0fb00b0210d0cc149477195f",{"generation":{"count":4,"mode":"SEQUENTIAL","complete":4,"prior":3,"incomplete":1}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpLifecycleB3Tests.java", "0e9b9712ae5d9c3e899ca01213911ff37dcbdc4b7b46406572f721174e9ae78d", [
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
  ...reviewedScopeFile("src/test/java/com/soklet/McpLocalizationAdversarialTests.java", "cb874b4b2169ba8720b396aa8271715f6e5a4dfccefbb8835a4f10441d944fbc", [
    ["rejectedAndIrrelevantWorkNeverInvokesTheProvider","314ac4b16ec574dc021d47c15bf20ba61dc3e5d9a7285af060451a08a6d20977",{"generation":{"count":5,"mode":"SEQUENTIAL","complete":5,"prior":4,"incomplete":1}}],
    ["simultaneousLocaleSelectionAndInvalidationStayIsolated","1f78dc197636be61b9fecb1249d3d9eeb42328c4c05332000e3a1cb789675a63",{"controlJoinMillis":10000,"controlComposition":"REVIEWED_OVERLAP_OR_DUPLICATE"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpLocalizationFleetPublicRuntimeTests.java", "226b9445317201e235c721ddc100e17be8730c41707cc5c43d930d458fea96d8", [
    ["failedFleetReloadPreservesBothOldSnapshotsAndPublishesNoInvalidation","70d8041d7b6fe9345f0d4367b322acfe549108279e0b38b87339056b3db1a530",{"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":1000},"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["nodeLossAndSubscriptionReconnectNeedNoSessionRecoveryAndReleaseFleetResources","5129e6ed08369dcd1c49d39f65dc0b10bcac31bab8f738c60d6c0abca22327df",{"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":1000},"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["rollingActivationAllowsRevisionDriftBetweenNodesButNeverWithinAResponse","f6928f916b52293b816244d20de51ebe28a29cc215665ab90f900106f30c0889",{"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":1000},"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpLocalizationHttpBoundaryTests.java", "b9089787c21a0e02c0632b0ddc7ffc59851c177b88ec05f8cb5538a7a2977806", [
    ["cacheableResultsAreClampedToPrivateZeroExactlyWhenLocalized","e4444aeafd9c2ce6a0c72772d03f355cb47b56de72616ba69f38f485c87f777f",{"generation":{"count":5,"mode":"SEQUENTIAL","complete":5,"prior":4,"incomplete":1},"controlJoinMillis":50000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpLocalizationReloadRuntimeTests.java", "1af3b3798167641eb14d2fcf407a2a895abf59422c16534a6a33840932708906", [
    ["invalidateCatalogsDeliversOneCoarseInvalidationPerLocalizedFamily","e2728e01df6c447112bddaca168c47c7792262f2a2092bc6a70422169e7e783a",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":20000}],
    ["localizedPromptPublisherNeedsNoUnrelatedApplicationPublisher","c1a8cf444a977b9c8d1b7822a5efbc5ad2dcf9e409459af9537087672812a835",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":10150,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["discoveryAdvertisesListChangedOnlyForLocalizedCatalogsWithSubscriptions","101808b6fd4a80d2741cb461aae5393d9bbb3cbdad11f6fa7e392344aca90762",{"generation":{"count":3,"mode":"SEQUENTIAL","complete":3,"prior":2,"incomplete":1}}],
    ["aStaleLocalizedTerminalIsReleasedByInvalidation","deea4d219b51e0e01de4ca3feb2e00fe016cbe25905a7aa198239c88a1106a3b",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":15000}],
    ["invalidationDuringTerminalPreRenderCannotInstallTheOldSnapshot","9645d8df6db1ad92bf1b5d8659f73408497f0dd03e3ea9afd6bd6fa9feb75d20",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":20000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["shutdownDuringTerminalPreRenderCannotCommitARejectedSubscription","e84f16d5d275ad30d9310da44147e6d986fb2a3c0ae2bc9e4124bcbf823f65f6",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":45000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["twoNodesInvalidateIndependently","da50c24ee984e3c8a13c75fc12666f92474c20f508e7aafe433fb4f818b78479",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":20150,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["mixedLocalizedEndpointsShareOnePublisherAndFanOutToEveryEndpoint","1ce475a6cb13a7640def901c6aee243947ca37fc9e2d3dc9fb9bf778f38be705",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":30000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpLocalizationRenderingRuntimeTests.java", "b2392481eac2874687c2714fbf54ea0f942c870605066f899000587fde6ea454", [
    ["everyNonDiscoveryCatalogRendersItsPlannedSlotsLocalized","e7b65f569523f0e0de6491224189a6b2f336ee772bfc4499890cf8fdab34e337",{"generation":{"count":4,"mode":"SEQUENTIAL","complete":4,"prior":3,"incomplete":1}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpMetricsEventDeliveryPublicRuntimeTests.java", "093d7ba98a614a9514abc064729b7bcea1338c042a4f6eff9a6ef41808848234", [
    ["adapterStopRequestQueuesStoppedBeforeFreshOwnerStart","2d031bb359b7fe83b22b9fdc0bdb27b9e28b0897ed912aff7ffbac9139f254cf",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":20000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":1000}}],
    ["failedListenerStartEmitsStoppedWithoutStagedStarted","e4b6b6d4c0a17869b1740f8899a9ed2917db65df1df4ab283c125a18d0d04ba9",{"generation":{"count":3,"mode":"SEQUENTIAL","complete":3,"prior":2,"incomplete":1},"controlJoinMillis":15000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":1000}}],
    ["freshOwnersEmitExactStartedStoppedGenerationsAndShutdownNoOps","7bd08eb5464b749cd09f3c1dbb790b2a4b3cb038fab943aa0a6d84c9937f2d8b",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":25000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":1000}}],
    ["unexpectedTerminationOrdersNormalizedStopBeforeFreshOwnerStart","d8e231e0f7b581efb02c062b5cae49f507d0bb8839975b8cf43f966d1215e39c",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpMirroredHeaderPublicRuntimeTests.java", "9f474592f006a5ef48663df29295e548250c2d08a6af17c2e10b3398096ac9af", [
    ["diagnosticQuotaIsSharedAcrossEndpointsAndIsolatedAcrossOwners","d2d7f3dd310037960d9b05c00304b1d217ef80b4d2d21582ebfbcfcbca2ef164",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpNotificationPublicRuntimeTests.java", "c11f8ebd54955be45ccc1360d0d64943769234465587b47286a0789c94dbb4f5", [
    ["inboundNotificationsNeverEmitJsonRpcBodiesOrReachApplicationHandlers","ac73360a54c74740e0b6a5a5d091b69aa40f05dd3cceba38f73cada5f34aca5f",{"controlJoinMillis":50000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["outboundFrameworkNotificationsOmitIdsWhileTerminalResponsePreservesRequestId","004911fbfa7770b0ae4c25a3a5e13bbee380835e49f15e130794dacec3ac1f73",{"controlJoinMillis":35000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpProtectionTraceDiagnosticsPublicRuntimeTests.java", "5a17e41effbe20d6df59e5d3632222b9a971e4e5eb7f46fe67c205d3bc2362d3", [
    ["liveRotationsChangeOnlyFreshSnapshotsAcrossStopAndRestart","aff4a2ec05c343fd842b869a28e5604fe84a68ca15a30943291e5e446c22392e",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpRateLimitIdentityPublicRuntimeTests.java", "f584e13e3c6bb1e66ef57e7860a06a8754c1029009676ea516d489c9f68c8865", [
    ["allowlistedSocketPeerCanSelectForwardedIpPartitions","ae0e5edad8bf1c56b636704e6566826fbb162e27c5fbf8635dee42723b3023d9",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpRateLimitPipelinePublicRuntimeTests.java", "7129954dea2d03abeca6d2ae073871d3245c938e614678554efacaf63c01d47c", [
    ["endpointAndToolOverridesEnforceQuotasWithoutAServerFallback","c4d8258226b2546c0c6b966b4b375c63576c614fcd622be449c2c38d1d5ad051",{"generation":{"count":4,"mode":"SEQUENTIAL","complete":4,"prior":3,"incomplete":1}}],
    ["successfulChargesAreRetainedAfterEveryDownstreamFailure","6021dbe8040107292a2b90efe2a60899cf07dc1c61690ee44de7c463fb998cf0",{"generation":{"count":6,"mode":"SEQUENTIAL","complete":6,"prior":5,"incomplete":1},"controlJoinMillis":30000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpRequestObservationPublicRuntimeTests.java", "39e3196bf05e38858b029d5954a2e7842fdf1f456045378634889ba38f9df324", [
    ["defaultOffAndIndependentRawIdOptInHaveExactLogContracts","c7179437628b87f78ffda891cad81dcfd3d1c0ff3b93aca2e4ba919c186213ee",{"generation":{"count":3,"mode":"SEQUENTIAL","complete":3,"prior":2,"incomplete":1}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpRequestStatePublicRuntimeTests.java", "4bc8659e930752b589d99c6a8c29b3f9a8241021560e5c8d265a3d7fd2cffde4", [
    ["frameworkProtectedStateContinuesAcrossInstancesOnlyWithinItsKeyAndAuthorizationPartition","d694cf1bfc2df30fe991073139bcf3495952c30229556db21876448ced9a3322",{"generation":{"count":4,"mode":"SEQUENTIAL","complete":4,"prior":3,"incomplete":1}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpServerPublicRuntimeTests.java", "60beec0cc4924652dcd8f777237f901ad43c2dd04d64c826c4e7ca2e27d8f600", [
    ["executionConfigurationValidatesAndOwnsOneExecutorPerGeneration","a1272dab1f8f342cc5f7aa048fad81745a0a0ae941c215e735259874bb5cea7d",{"generation":{"count":3,"mode":"SEQUENTIAL","complete":3,"prior":2,"incomplete":1}}],
    ["explicitRejectAllCorsSuppressesTheOmittedConfigurationDiagnostic","e1fb4923fecfe92e98e4f5b236402aac503c53118d6ad11f7b09cc42d442e860",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["failedFixedPortBindLeavesResourceAvailableToFreshOwnerAfterRelease","3994325e8e4642ea22e22c380838a2cbe2436d6b4afa5e0123fc8c8ec2b7da9c",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["freshGenerationPublishesNeverBoundAddressBeforeStartupCallbacks","3b72f7823f23ae43d02e6068b23458f85336f56b336027b5b1bbc221d4ea1c44",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["freshOwnerStartsAfterUnexpectedMcpListenerTermination","cae716b3b14cba1599399b85f25405963d055e6f633dc0b01bdd241cef4ad393",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["omittedCorsDiagnosticIsExactAndOncePerSuccessfulSokletGeneration","f63e592b4660e8748a99a45c78582182f7771c7ba2ad21b2c3d3cd9ce98df37e",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["sokletOwnedPortZeroGenerationsPublishImmutableDiagnosticSnapshots","2188c1ef7acb0c871c9dc12149bb1314d528a21092c3d1e4fb0996dbed2ea4d9",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpShutdownObservabilityTests.java", "82b0d44a88a7d653d5f01a46b7ebe4deb7a3a2f85daa089c045952724d789408", [
    ["rejectedUnexpectedRestartDoesNotDuplicateBeforeFreshOwner","f4e7d5392de0df5da2dd468969ae18d43b2188c81d87fa21afabdebd1d4ef9ef",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["unexpectedListenerTerminationAndFreshOwnerHaveExactParity","658fb897e3bcf0c822984d9d201f5c79c95dee91bc0957419d653a984b2c9b14",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpSimulatorEveryOperationTests.java", "c32d8bbb6e3c9a973e697f06c64a462c1cca6d8dfb07711d077c4da2ff81c03c", [
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
  ...reviewedScopeFile("src/test/java/com/soklet/ResourceLeakTests.java", "c351e0399e887deacb727d76b3fb29b108bf335fe368678c6d4a7cfe10168b25", [
    ["httpConnectionChurnReturnsResourcesNearBaselineAfterShutdown","c2a87a6779ada21503e8872c2945747c4caa495eb137b4705ff2b486874ed278",{"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":2000}}],
    ["mcpListenerAndRequestReturnResourcesAfterCompleteShutdown","6bfad81dbddf23d3815097a28df27bcca055d78015b7e03803378dc438bc6a7d",{"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":2000}}],
    ["sseConnectionReturnsResourcesNearBaselineAfterShutdown","8c30d40aeaf07d2a2f70ec9e0661c809e5fc6cd439d44553b848c5d34264d0a7",{"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":2000}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SokletApplicationTests.java", "272dbaee05d9a91c8aa6d1ca9d702b7e219ce00d55d85ffef44e4c4c6e0e8dc1", [
    ["malformedRunArgumentsDoNotConsumeTheApplicationRunClaim","4a766e82a39b54473a10f0aaca9b8903a6892623bdf83a809cf2b72c3e84dd90",{"generation":{"count":2,"mode":"PRECOMMIT_REJECTIONS_ONLY","complete":0,"prior":0,"incomplete":0},"terminalReportCount":0}],
    ["failedAttemptStillConsumesTheApplicationRunClaim","ee95dde17da3ee7dae8f55543e7de083357b9a4481af5d001db1d16c1203feee",{"generation":{"count":2,"mode":"PRECOMMIT_REJECTIONS_ONLY","complete":0,"prior":0,"incomplete":0},"terminalReportCount":0}],
    ["invalidInjectedEnvironmentStillConsumesTheApplicationRunClaim","f2624053763e0dcc25e7843a1952e60686e5e465ee1a10cad9566584ac9f6ea0",{"generation":{"count":2,"mode":"PRECOMMIT_REJECTIONS_ONLY","complete":0,"prior":0,"incomplete":0},"terminalReportCount":0}],
    ["concurrentRunIsRejectedWhileTheFirstClaimIsActive","7411542ec9730a2c348c394090f0202fb9fe88ecd5d2afd1ba19f0297bf24fd4",{"generation":{"count":2,"mode":"PRECOMMIT_REJECTIONS_ONLY","complete":0,"prior":0,"incomplete":0},"terminalReportCount":0}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SokletApplicationDiagnosticsIntegrationTests.java", "8ce90deb853215abcf0ab22935e28d2866551b42325ae1a2bfae42e71efcb3bb", [
    ["blockedFrameworkSetupSynthesizesFrameworkDiagnosticsAndSkipsCleanup","f218d02df1bdeffd05dd423fe22e5a648a1069a8e35d9ce8162003b7abc1054f",{"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":0,"gracefulShutdownMillis":0,"forcedShutdownMillis":0},"controlledLifecycleCoreMillis":5000,"applicationCleanupMillis":1000,"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":40000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
    ["blockedNestedCustomHttpAttachProjectsBoundedTransportDiagnostics","255657d63ada4a5e906662f12c5891532345751d16710546d322e8e33769f928",{"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":0,"gracefulShutdownMillis":0,"forcedShutdownMillis":0},"controlledLifecycleCoreMillis":5000,"applicationCleanupMillis":1000,"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":40000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SokletApplicationObservationTests.java", "0c866bfa06729660ba06719c12bc78232104f528de275e9f4504fa69e65c0ab3", [
    ["mixedIncompleteAndNotStartedTerminalTraceIsOrderedAndComplete","5debbf0f31e1b92f1e61dce7945f86b38742af0487fcfb184536b2bb0872acb9",{"phasePolicy":{"forcedShutdownMillis":0,"gracefulShutdownMillis":0,"startupCancellationMillis":0,"startupMillis":5000},"controlledLifecycleCoreMillis":5000,"controlJoinMillis":20000,"controlComposition":"REVIEWED_OVERLAP_OR_DUPLICATE"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SokletDirectLateStartupIntegrationTests.java", "a2f576081a6b3a123979fd753276bd1999a5613351185dab1dd77dc5a855a819", [
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
    ["catchUpFailureIsSecondaryEvidenceToExactLateStartFailure","f90d60d59c508581f202a8ad8b03d9580355672accd943ae6395dfa8c5ad0ace",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":0,"gracefulShutdownMillis":20000,"forcedShutdownMillis":2000},"controlJoinMillis":45000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SokletDirectSseCompositionTests.java", "ce0085de3c04cfc58debef721f8adfbb9ca1d253f42163c812f91349cb63a405", [
    ["lifecycleOwningDecoratorProofCannotBeBypassedByItsDelegate","edf67e53017ae8a3635666f228532c08218610b77e5b412cc96929916fdf0463",{"phasePolicy":{"startupMillis":2000,"startupCancellationMillis":100,"gracefulShutdownMillis":100,"forcedShutdownMillis":100}}],
    ["transparentDecoratorSharesTheRootMemberAndRoutesTheSseSurface","da6d9481451f2c926a3be78243d75aa753764cea8d6e17191240296d58033e2e",{"phasePolicy":{"startupMillis":2000,"startupCancellationMillis":100,"gracefulShutdownMillis":100,"forcedShutdownMillis":100}}],
    ["twoLevelOwningStackRequiresEveryNestedMemberButRemainsOneParticipant","12bf89e23f0ed63426f0d279f825aca12d6a72b072aa3595aac781bedad8223f",{"phasePolicy":{"startupMillis":2000,"startupCancellationMillis":100,"gracefulShutdownMillis":100,"forcedShutdownMillis":100}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SokletDirectHttpCompositionTests.java", "6252f136b9cb056c2eff589860f091badcd56363947205c95a5d551ca3f1c075", [
    ["transparentDecoratorSharesRootSignalRuntimeAndRequestPath","96363f3e1c54e3ed99307da12c3c27a1eef1630885fd308d3ae6d7c353d51737",{"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":15000,"forcedShutdownMillis":3000}}],
    ["lifecycleOwningDecoratorRequiresDelegateAndOuterProof","ccede64a5c8aff78211793f6feef733cdc2821ff84f9b05ddc739d6c5d03f209",{"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":15000,"forcedShutdownMillis":3000},"controlJoinMillis":10000}],
    ["twoLevelOwningDecoratorsRemainOneConfiguredParticipant","baa997e98c6e21a907ceee6a4f5d915b99ad45d39f1cf865169a7086cb1db246",{"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":15000,"forcedShutdownMillis":3000},"controlJoinMillis":18000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SokletDirectTerminalPublicationTests.java", "dab2b99392310cb48d54d26298fb03c2bfa41ed7feb9d4205ec25a8c38f07e3f", [
    ["blockedPreRegisteredContinuationCannotStrandPrivateOrPeerOwners","1d864c33e7c10d2827a931036b1421d194fa1d66acf06ff064d48ec14888b874",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SokletDirectTerminationPrecedenceTests.java", "fc2e9a0459ddb113411e9e937d45743bbfc7461095829ba8c69f82a815ad6f48", [
    ["ownerShutdownIntentWinsFormerGroupFanoutGap","da9453ddd92e3d10cfe8cb15476bf271efd399c14e41292a2edf1ac54b142ef9",{"phasePolicy":{"forcedShutdownMillis":30000,"gracefulShutdownMillis":30000,"startupCancellationMillis":30000,"startupMillis":5000},"controlledLifecycleCoreMillis":5000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SokletSimulatorIsolationTests.java", "eb7752f519e2b19d0b0d5127606dd63468295e05738b3b7086773d30c68823b2", [
    ["blockedFrameworkSetupUsesOneExactStartupAndRollbackSchedule","442ee614d657896f5cc74b540cd579692b71200861966840267bfc61894e3f7f",{"phasePolicy":{"startupMillis":2000,"startupCancellationMillis":1000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":2000}}],
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
  ...reviewedScopeFile("src/test/java/com/soklet/SseTests.java", "9ce193b4f0e2ec07a6b602600a3171dd27895fbb9db776a3b9c64d9dcab61920", [
    ["staleSseAcceptLoopFailureDoesNotClobberRestartedServer","8fa72e23163470ae54388eda1298ea902441e49999401846f23fec8472dbbf65",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlledLifecycleCoreMillis":18000}],
    ["sse_startStop_doesNotHang","f0073925d21aee7e8e0692f9456b944284ab9c3d027eeef3f10598f12767a799",{"phasePolicy":{"startupMillis":3000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":0}}],
    ["sse_stop_allowsIsStartedDuringShutdownWait","f8620ccecfa2908fbe4b7f91d8d3c19e27520e01a0751f4bc4388cb4ca771849",{"phasePolicy":{"startupMillis":3000,"startupCancellationMillis":1000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":0}}],
    ["sse_stopGracefullyShutsDownRequestHandlerExecutorBeforeInterrupting","d2ac819b64043436ab3667f9b04695649be071cc6e74b7fa264f4aceb12562ec",{"phasePolicy":{"startupMillis":3000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":0}}],
    ["sseServerCanRestartOnSamePort","f9bfbe60f17f243ea82e7d0f68abca6378dd6bcc3e10f4671bea0aa9eceeff9e",{"phasePolicy":{"startupMillis":3000,"startupCancellationMillis":1000,"gracefulShutdownMillis":5000,"forcedShutdownMillis":0},"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["startRejectsRunningSseGenerationWhileItsStopIsInProgress","9303908c0e9f97fd45adc11e233d34ed035fef47e18574fd752838d11cafbbd7",{"phasePolicy":{"startupMillis":3000,"startupCancellationMillis":1000,"gracefulShutdownMillis":5000,"forcedShutdownMillis":0}}],
    ["socketOptionFailureWithProvenPeerEofClosesWithoutInternalDiagnostics","3885055de5dd5b26c8ca9ae98fbafeaacb3b264bf9c08a194c62aa770c1eaeff",{"controlledLifecycleCoreMillis":18000}],
    ["socketOptionFailureWithProvenPeerReadFailureClosesWithoutInternalDiagnostics","2ac41d32f057d491cd066b691c7c42546c84a94ee39cb0d54343f41641dff032",{"controlledLifecycleCoreMillis":18000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpHttpServerApplicationExecutionTests.java", "0d97eca40e871a08535dbe57b44098666eb1f8b9a683369f5ffcaf5a5b5ab377", [
    ["application_executor_factory_failure_restores_a_restartable_runtime","12c0b6987c528bf3716755625c2904f548e2a7d6a0ac7ab133134e0a4d19ba2b",{"generation":{"count":2,"mode":"MIXED_MAX_PLUS_SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":5000}],
    ["lifecycle_grace_preserves_active_handler_then_force_interrupts_without_promoting_queued_work","fb1e2567d1e404d58ca8cd2d8ef36f003fae2e32f0c4558172d8982627927bcc",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["shutdown_reports_residual_application_work_and_blocks_restart_until_exit","bbbee36baa887838cb7b30b073ae71813e2ac320b8a75f85333d4248068c2639",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpHttpServerCustomHeaderTests.java", "b7090c26121d61b10c7df9d3c6cb2f0e15112518dcc1d7b07b106a48f699ebf5", [
    ["custom_mirror_registration_is_scoped_to_the_selected_tool","4c0e49b4f43570fbadcc0fc86cdada8810c1ca2d41d8aef06a66a58783754e55",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpHttpServerNotificationTests.java", "1adb67a46e495ac93455da49b5313f28ebb40a84fc4ce0389dcfe5b3112f4591", [
    ["classified_notification_cors_matrix_preserves_headers_and_rejects_origins_early","8e47fee1adcc7ee5ca11db7c56becf66026cbde345ef373fc47eb9e9e9d9c939",{"generation":{"count":6,"mode":"SEQUENTIAL","complete":6,"prior":5,"incomplete":1}}],
    ["notification_admission_allows_invalid_params_and_fails_closed_on_unsafe_headers","e22b5be4e146a3fee9c2bef67e06c54f3b8f4406d03e8fa84f88f357441fa6dd",{"generation":{"count":4,"mode":"SEQUENTIAL","complete":4,"prior":3,"incomplete":1}}],
    ["notification_policy_failures_fail_closed_without_a_json_rpc_body","4e66a46c96247f426624b012efbd84e2efbcc98f4209cdf864beefb7304036f6",{"generation":{"count":4,"mode":"SEQUENTIAL","complete":4,"prior":3,"incomplete":1}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpHttpServerPolicyPipelineTests.java", "f2932bd98a694d5cf51d8befbb172b5a169b66123d7309e61040cdf401e314f8", [
    ["policy_null_exception_reserved_code_and_unsafe_header_fail_closed","998e3a54b55f4ff58e1b75ac8d31c00e649a2d93dc73abaddc6e31ae4cad7893",{"dynamicNodeCount":12}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpHttpServerRequestScopedSseTests.java", "27493b08e8de3ab5db28f52c2f7a8ece54ca336f2b4702a49a832b5a4385d398", [
    ["shutdown_closes_committed_stream_and_runtime_restarts_cleanly","56025a56f7defe06f6eda65fa1b8882607880ca71c1daa149ca069518aae340a",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpCatalogPolicyDeadlineRuntimeTests.java", "e6f215073cf7d7e7ca87cab101fdc9f821e00ab948600a960ed991430615611d", [
    ["applicationStopCancelsActivePolicyBeforeAnotherEvaluatorCanEnter","691edc69658c41476fc1e809c6ec5eb9eecd80607b80c4b491be8bb6573a7b12",{"controlJoinMillis":30000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpHttpServerRuntimeTests.java", "8bec486b523455260f2425ce002e1a491b833771b97ab8cbd0efb728fafc1be1", [
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
  ...reviewedScopeFile("src/test/java/com/soklet/StreamingResponseTests.java", "f87ada067a63688f98f903cb021966734d37a11981ef76ec0ff47ac5dac39fb9", [
    ["simulator_admitted_stream_error_callbacks_survive_scope_seal","b90cf962e5913cc291faa943fd6f8e4e1c06cadc33d628b641cb4b9c5bfddc4a",{"controlJoinMillis":10000,"controlComposition":"REVIEWED_OVERLAP_OR_DUPLICATE"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpFinalTagGoldenWireProductionTests.java", "6dcb345bfd84ce5c5c3dd691918c1fbca87a7b366e8bd2562c89aa639f0c5eeb", [
    ["checked_in_phase_5_subscription_messages_match_the_production_listener","f05dab8acacfe830f21c07ecef5f3c63da26a2c461b78fd7d68f0d34b04df48d",{"controlJoinMillis":0,"controlComposition":"REVIEWED_OVERLAP_OR_DUPLICATE"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpMultiRoundTripTerminationRaceTests.java", "99ffcc9aad351eb991e609189cba924ab7b977b750bbc9fbe8bd2648eb2e456f", [
    ["blockedCustomProtectorOpenMakesShutdownResidualUntilProtocolWorkExits","e064a6917cf6535e0a43fc52d299c8e51c4c8c855fa0d0420ac26930399fb8e0",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"requiredAction":"RAISE_OUTER_BOUND"}],
    ["blockedCustomProtectorOpenDiscardsLateResultAfterDeadlineOrDisconnect","9fe0428fbf8a051f8856858b80b43e838880af781f3cf347ae86a50a84792910",{"dynamicNodeCount":2}],
    ["blockedSealCannotPublishLateInputRequiredAndReleasesExactlyOnce","9fa9b58c00aca22633217bf700bf7e481d25fd151076c03d2621399daecb4529",{"dynamicNodeCount":3}],
    ["conditionalCapabilityHoldTerminatesWithoutProgressOrLateResult","e2a6c3af0f11bbd8c32e537b0c7209aebfeb71932f14c3f78dcf445f1d2d9bd1",{"dynamicNodeCount":2}],
    ["sameAuthenticatedStateCanBranchWhileOneFreshIdTerminates","e2658db8bc19db08af218c4aa7408c71c66d309caa4d4edfe61b30847f57274c",{"dynamicNodeCount":2}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpProtocolProfileRegistryTests.java", "3dba1d2df07ba340cc0d08918e2672cf7ce0cc49613e59f4601397af442e3f2a", [
    ["fakeProfileEntersOnlyThroughTheExplicitRuntimeTestSeam","6f7e90f54ed0a74a1f86ef1cfd4723d2b55367c9552e86498838855cf2773a1f",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpResultEnvelopeGoldenProductionTests.java", "259751ad7148dd0b907fba76d766338b39684cf8e4aa4197a4a3718ac0b30ae9", [
    ["everyFrameworkAndApplicationCompleteAuthorityMatchesGoldens","1e0bc148501a1dcd4a1a959c79abb0bc6761f612635942c41f84cbb3a0266517",{"generation":{"count":4,"mode":"SEQUENTIAL","complete":4,"prior":3,"incomplete":1}}],
    ["requestScopedAndSubscriptionSseTerminalsMatchGoldens","d8741458332a9ce96f01ac6c30993618baf55a0841030d45a3e945ce47577ab9",{"generation":{"count":3,"mode":"SEQUENTIAL","complete":3,"prior":2,"incomplete":1},"controlJoinMillis":0,"controlComposition":"REVIEWED_OVERLAP_OR_DUPLICATE"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpSelectedProfileBindingTests.java", "db785c297fdedcbb83f9522d1f18b646d738d3ae807ca1e7f4f403b079602d3d", [
    ["subscriptionAndSimulationRetainTheSelectedProfileForTheirWholeLifetime","d1973555f04b5023222f6251df7ae83dc4cfd92d164cd14dfd03487778172914",{"generation":{"count":2,"mode":"MIXED_MAX_PLUS_SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlledLifecycleCoreMillis":50000,"controlJoinMillis":15000,"controlComposition":"REVIEWED_OVERLAP_OR_DUPLICATE"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpSubscriptionPublicRuntimeTests.java", "f34e2d1755405f60630f884641b2d4761916bb354f3076c8fcf678bbf49044c0", [
    ["gracefulHttpShutdownEndsWithOnlyTheTerminalCompleteResult","4b054c2eaeca9da3a17bcc51fe0fa7c5d76b106d10c7aace497052461f07b9df",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["nullAndThrowingAdmissionNeverActivateOrConsumeSubscriptionQuota","ae474f2c1b72114791c81d1f865b1560d7d9327e993b6e2e1eabea075c0906de",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["publisherIdentityIsGroupedPerServerAndSharedAcrossServers","e472a11ec8fb6f5a657074c2b55870ae7ad80d49d9b8924fc6bdd94aa8f24889",{"generation":{"count":3,"mode":"SEQUENTIAL","complete":3,"prior":2,"incomplete":1}}],
    ["validListenUsesAdmissionAndRequestLimiterOnly","f107f5c9e9d1456283a6b3b75e4431d026c9269980fb55b0f5f5a58cabd83345",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
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
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpQueuedExecutionWinnerElectionTests.java", "3911595af613b9ef03c68f845eafbd73aefe15ca95f0afadd714817b104f8e7b", [
    ["all_queue_promotion_deadline_disconnect_linearizations_elect_exactly_one_outcome","8be5079ecfbe8cc5c5ca23e3a7f74a8fd6c54dbb81638b38ffacee243a80b996",{"generation":{"count":6,"mode":"SEQUENTIAL","complete":6,"prior":5,"incomplete":1},"controlledLifecycleCoreMillis":5000,"controlJoinMillis":240000,"requiredAction":"RAISE_OUTER_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/transport/McpTransportContainmentSpikeTests.java", "6112087ead7a3841b612187b3caf687039f4a5cf80e85d99c61c168828435194", [
    ["containmentMatrix","3fd46222c3565effcfa33614f441751a8acc14b55b279a133e34b4206dec05ce",{"dynamicNodeCount":15,"controlJoinMillis":8000,"controlComposition":"REVIEWED_DYNAMIC_NODE_MAX"}],
    ["initial_deadlines_remain_ordered_across_signed_nano_time_wrap","8491b77e9bd240c850793af942a0c3d0743fe2478af08f8078c6860bce4cbad6",{"controlledLifecycleCoreMillis":0,"controlJoinMillis":24000,"controlComposition":"REVIEWED_LIFECYCLE_CORE_DEDUPLICATION"}],
    ["unarmed_keep_alive_sentinel_cannot_fire_across_nano_time_wrap","9154b0cf3ba212f2cb3e451def2ee07d4deb894055b68310452a52afc16d253a",{"controlledLifecycleCoreMillis":0,"controlJoinMillis":24000,"controlComposition":"REVIEWED_LIFECYCLE_CORE_DEDUPLICATION"}],
    ["rescheduled_keep_alive_remains_ordered_across_signed_nano_time_wrap","1885606e043f4d5bd9731db2a75211efe5ec500fdea3f088792f4f76cc791e2c",{"controlledLifecycleCoreMillis":0,"controlJoinMillis":24000,"controlComposition":"REVIEWED_LIFECYCLE_CORE_DEDUPLICATION"}],
  ]),
  ...reviewedScopeFile("src/test/java/examples/mcp/McpLocalizedCursorFleetApplicationPatternsTests.java", "41b49389aec24b8a2a6eef7dda1fa81b7057a2651be875c4ee70c1ffaca61ad8", [
    ["cursorFailuresPreserveOpaqueBytesAndCollapseToOneNeutralError","4d4627fc74692b252dd66ca031946dd91b241bd00028288411200722da0080d0",{"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":1000},"generation":{"count":10,"mode":"SEQUENTIAL","complete":10,"prior":9,"incomplete":1},"controlJoinMillis":100000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","requiredAction":"RAISE_OUTER_BOUND"}],
    ["localizedCursorCrossesNodesWithStableSnapshotLocaleRevisionAndPageBounds","c23e53ce8a2ae33e696b0652e8026318d6a5196c9f410ee66af7f989b4cc97c9",{"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":1000},"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":50000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SokletApplicationProcessTests.java", "22a0c2f6111efa0281aeb2b5a439a513109df06886ca0659cb5a7a8719d573d4", [
    ["startupFailureAndTimeoutRemainPrimaryWithIncompleteRollback","99df72ac8cc8858599b08bbbb0cbd9e12df44020b1ca8bb534f91a68a5f8783f",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":0,"incomplete":2},"applicationCleanupCount":2,"incompleteBranchCleanupCount":0,"terminalReportCount":2,"requiredAction":"RAISE_OUTER_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpHttpServerRuntimeTests.java", "8bec486b523455260f2425ce002e1a491b833771b97ab8cbd0efb728fafc1be1", [
    ["literal_loopback_bind_spellings_do_not_require_allowed_hosts","3d1edfa1f0627558c8bd577bcb4936857cfe630d55d6aaf55299a5c200d9082b",{"generation":{"count":6,"mode":"SEQUENTIAL","complete":6,"prior":5,"incomplete":1},"controlledLifecycleCoreMillis":0}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpCompletionWireParityTests.java", "727db178173dc5ef21945db24210f79f0d82fed69de767424c1fe92898417dca", [
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
  // Review regression matrices retain two binding owners, five bodyless-status
  // owners (streaming is rejected for 204/205/304), and two legacy revisions.
  // Legacy wire controls: six RawClients each allow 5 s connect + 5 s head +
  // 5 s body, plus two 3 s retirement polls. SSE disconnect controls: eight
  // exchanges each allow 2 s connect, two 3 s reads, and a 3 s retirement poll.
  // Writer-executor controls add connect2 + reads9 + latches6 + executor2;
  // rewrite controls add connect2 + reads9 + latch3. Whole-file hashes bind
  // the shortLifecyclePolicy helper and the raised method/type outer guards.
  ...reviewedScopeFile("src/test/java/com/soklet/DeepDiveBindingRegressionTests.java", "63bb735ffcff5b9ebc85fb164d22879905f61d2b26e00449e5f75d83957b274e", [
    ["wrapFailuresHavePairedLifecycleCallbacksAndCountAsUnmatchedServerErrors","8445adc79db6d5c86b9a4ec40a39922aac309b42440ef061aae80e3556f35b0b",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/DeepDiveRoutingSimulatorRegressionTests.java", "fc0cddf20fbe88dc0834281949a6e67e057cfa094ad556d9f9836840e8abd24f", [
    ["simulatedInterceptorResponsesHonorHeadAndBodylessStatusRules","a642db210e673eae8ed3e65de10bdde0628f6091a6e4ed737eb276c9e2ab9a12",{"generation":{"count":5,"mode":"SEQUENTIAL","complete":5,"prior":4,"incomplete":1}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpLegacyGetWireTerminationTests.java", "506e8e5da4e88fa42bcbdda3a99068f2a9a66d3272738289ce924428aed5b98a", [
    ["shedLegacyGetClosesItsHttpConnectionImmediately","f32c9df282f4e271a8deae7d899332a7c41ef251d25f0e98681d536fd821995f",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":96000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["expiredGracePeriodClosesItsHttpConnectionImmediately","d3e225938e26f78e1882eeee989b90ea162a6071f50bcf1c9ef82115d5acd06a",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":96000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SseReviewRegressionTests.java", "15fb13fae72a10709e965b3b74a73f15aed17990749366e9ff88b7b8d17a2779", [
    ["cleanAndResetDisconnectsReleaseCapacityBeforeTheHeartbeat","2d436dd7f53327ceb9a7f657c9401356be0aa3fa1389876c61fc306ad964e550",{"phasePolicy":{"startupMillis":1000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":88000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["singleWorkerWriterExecutorDoesNotDelayPeerCloseMonitoring","a55dee4c89f15cb64c8f272cba0f428571a12fdcec4ec7768e715d7a546b95b2",{"phasePolicy":{"startupMillis":1000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":19000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["saturatedCallerRunsWriterExecutorDoesNotRunDisconnectMonitoringInline","3383432500966a024ead93b985b3dd7a323ccb0612fc68ede328064dfac3bd17",{"phasePolicy":{"startupMillis":1000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":19000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["rewrittenLiveHandshakeJoinsTheEffectivePathBroadcaster","0be08186554ce3ce790b6d7c2c791825d6d61ccd5da47b2a14d0cb371ee95117",{"phasePolicy":{"startupMillis":1000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":14000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["largeAdmissionAndQueueLimitsDoNotEagerlyAllocateTheirMaximum","b2a066ee831d34e8366ca9e9200526e6d21923354917ab454e9bc3fa83738c00",{"controlJoinMillis":8000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
], 'lifecycle scope topology');

const REVIEWED_PHASE_POLICY_OVERRIDES = checkedReviewMap([
  // Immutable configuration/arithmetic only: no owner executes; maximum nanos rounded up to ms.
  ...reviewedPhasePolicyFile("src/test/java/com/soklet/LifecyclePolicyTests.java", "204114c2a9de2fd50c06c9a9f5e56ec8453ddde1aeeb067b9710ca37482e74ce", [
    ["combinedShutdownHorizonMustFitSignedNanoseconds","9598ffa006367bedcfd662be11c0f6e8e8ad2d0edcf6b6709ae76160125f9edb",{"startupMillis":30000,"startupCancellationMillis":9223372036855,"gracefulShutdownMillis":9223372036855,"forcedShutdownMillis":1}],
    ["exactMaximumShutdownHorizonAndIndependentStartupBudgetAreAccepted","6c2227dcf46b31bdff82c47e18ec05345b8ade705da35634023cc2030fba10b7",{"startupMillis":9223372036855,"startupCancellationMillis":9223372036855,"gracefulShutdownMillis":1,"forcedShutdownMillis":1}],
  ]),
  // Private transport-feedback regression helpers install this exact finite policy.
  ...reviewedPhasePolicyFile("src/test/java/com/soklet/HttpTransportResponseFeedbackIsolationTests.java", "604858b4c12ddf8cf614c76cc07c75d387a1b4e83e9afccee70d7c55e41fa582", [
    ["nestedAdmissionRejectedHttpWriteCannotReplaceAnIndependentResponse","3cf2023e1fc1eb32c118ba8b34f9d9c004245e4d60f9b293990c5e65c5973bf9",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":3000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["sseCallbackThreadHopRetainsInvalidFramingReplacement","7fdbbd9b4caaae4f973ed371f63039dc1ad640febdb1a529332472fe9f41d371",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":3000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["sseHandlerAndCallbackThreadHopsRetainInvalidFramingReplacement","2ca974c491c795c4ac3112c2dcbace7a38d28228b3a99159c2ff1872b0015321",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":3000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["sseFreshRequestRetainsInvalidFramingReplacement","5286a75ce3b2501c5fce49c45836d4fe4196230df49898584b7925ada576ea9f",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":3000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["sseFreshRequestAcrossHandlerAndCallbackThreadHopsRetainsInvalidFramingReplacement","f68aa2973c4ca664a3c019a4e17ee3ad8751ba3a1511108fd83c55f0d7d482ba",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":3000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["sseNestedFreshRedispatchKeepsReplacementWithInnerRequest","be845b0824b744ada2e517646a4ba4243596cb55d809b61bc4b8aca4dd8db166",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":3000,"startupCancellationMillis":2000,"startupMillis":5000}],
  ]),
  ...reviewedPhasePolicyFile("src/test/java/com/soklet/LifecyclePolicyTests.java", "204114c2a9de2fd50c06c9a9f5e56ec8453ddde1aeeb067b9710ca37482e74ce", [
    ["negativeAndNanosecondOverflowingTimeoutsAreRejected","5e09609efbdc80c4bca9d987e57fd31bb400ae72fe94586f3265640c211609fd",{"forcedShutdownMillis":3000,"gracefulShutdownMillis":15000,"startupCancellationMillis":2000,"startupMillis":30000}],
    ["nullRestoresEachBuiltInDefault","70e57c423a1c12469ae46b5767b017a23f583e9bcb790522f57d5a7b305383c3",{"forcedShutdownMillis":3000,"gracefulShutdownMillis":15000,"startupCancellationMillis":2000,"startupMillis":30000}],
    ["policiesUseStructuralEqualityAcrossAllFourTimeouts","854da36425d693c070eb7b6ab139b2f628070d79abacd765f787c75503d4a479",{"forcedShutdownMillis":5000,"gracefulShutdownMillis":5000,"startupCancellationMillis":5000,"startupMillis":5000}],
    ["zeroRemainsAnImmediateBoundary","e137b16cbc06b0fe4d5700ea7053284884ded79e7d3ef904b71c956e58859323",{"forcedShutdownMillis":0,"gracefulShutdownMillis":0,"startupCancellationMillis":0,"startupMillis":0}],
  ]),
  ...reviewedPhasePolicyFile("src/test/java/com/soklet/McpHandlerMetricsObservabilityTests.java", "adac81c1b3e144a7648c5f6cfbc69f2123f6fcdd47e4c8117082c1251f95431c", [
    ["defaultCollectorAggregatesConfiguredZerosRendersFiltersAndResets","a656a20b5aae03d01dbad70988bf517f0221782053c1ca2e7f9ce5d3de09eff5",{"forcedShutdownMillis":3000,"gracefulShutdownMillis":15000,"startupCancellationMillis":2000,"startupMillis":30000}],
    ["sokletOwnedSaturatedListenerEmitsExactServerWideTransitions","e900ffeede7893654eaef5c84152e03ae46d8f73f020ee4c7e941925e48499a4",{"forcedShutdownMillis":3000,"gracefulShutdownMillis":15000,"startupCancellationMillis":2000,"startupMillis":30000}],
    ["queuedDeadlineDequeuesWithoutExecutionAndRetainsActiveGauge","506c4820d068a3df576091eee4910e7fb0c63723f32f25688ba6d8fb4682c19a",{"forcedShutdownMillis":3000,"gracefulShutdownMillis":15000,"startupCancellationMillis":2000,"startupMillis":30000}],
    ["queuedDisconnectDequeuesWithoutStartingHandler","9f0643cc0c4daad8c45e9af1e0be4f83021042d638d5a5886e423b12bd17c9c8",{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":15000,"forcedShutdownMillis":3000}],
    ["managedResidualShutdownDequeuesAndFreezesGaugeAcrossLateExit","eef1d5eaf81478cc8f668b84a0b0154ac17660ffceda81eb504a63328805539c",{"forcedShutdownMillis":100,"gracefulShutdownMillis":100,"startupCancellationMillis":100,"startupMillis":5000}],
    ["managedStopDefersQueueAndExecutionCallbacksBeyondLifecycleLocks","d72292b2dc82b49ed2907122beb97415d59cb36c63a109e331af05c19ded6e71",{"startupMillis":5000,"startupCancellationMillis":100,"gracefulShutdownMillis":100,"forcedShutdownMillis":3000}],
    ["unexpectedTerminationDefersQueueCallbackAndFreezesTerminalGauge","c22022c402ac95bb944ce68148cfe308da3e3f208f4e0738bd05f36363fce9ee",{"forcedShutdownMillis":100,"gracefulShutdownMillis":100,"startupCancellationMillis":100,"startupMillis":5000}],
    ["handlerMetricsCollectorFailuresAreContainedAndLogged","8bdc3a81e67670d35c2946204352fd0e93d6d2d095fe9c44539c0796b83c1f18",{"forcedShutdownMillis":3000,"gracefulShutdownMillis":15000,"startupCancellationMillis":2000,"startupMillis":30000}],
  ]),
  ...reviewedPhasePolicyFile("src/test/java/com/soklet/McpHandlerQueueDiagnosticsPublicRuntimeTests.java", "2677ed230eb935cd74cbf5d84d736e89b0edc04cf68c0479df342712c8ca4014", [
    ["configuredValuesAndZeroLoadRemainStableAcrossFreshCleanOwners","cde7c57e1b9a160fad74d90cc8267620d16056299942d0ac3753b2551d48416b",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["crossEndpointSaturationPublishesRetainedAndBoundedConcurrentTuples","9edb621c414a122735a2a1a9e3a19bcaba7b5cfd0a2dda9163fd100215627c15",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["residualStopRetainsOneActiveAndDrainsQueueUntilLateExit","f09a88590dd6bf66e104c80fdba88e0d85e101e439d763ff63eab8b53db94f81",{"forcedShutdownMillis":100,"gracefulShutdownMillis":100,"startupCancellationMillis":100,"startupMillis":5000}],
  ]),
  ...reviewedPhasePolicyFile("src/test/java/com/soklet/McpLifecycleB3Tests.java", "0e9b9712ae5d9c3e899ca01213911ff37dcbdc4b7b46406572f721174e9ae78d", [
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
  ...reviewedPhasePolicyFile("src/test/java/com/soklet/McpRequestStatePublicRuntimeTests.java", "4bc8659e930752b589d99c6a8c29b3f9a8241021560e5c8d265a3d7fd2cffde4", [
    ["applicationProtectedStateRoundTripsExactlyWithOneSharedContext","492654b0cd80a9920ac6d58f4b1b3a2696724d264e12d77ff8654fda17620438",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["frameworkProtectedStateCompletesOnlyWithAFreshRetryId","4f80d7e715358e1f2ef51828d9613e74eed5b6e798eacb0cc91f64e8c0b04e75",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["frameworkProtectedStateContinuesAcrossInstancesOnlyWithinItsKeyAndAuthorizationPartition","d694cf1bfc2df30fe991073139bcf3495952c30229556db21876448ced9a3322",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["malformedTamperedAndUnavailableStateHaveFixedPrecedence","d95d4fa7c1e75732e55393953d73b25bf1f4500b7b2449657d0a1c14c385629b",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["resourceRetryStateForcesPrivateZeroTtlAndNoStore","4940629802ea6e06ce422ce3e95b69d836e4a10fb437893167795fa689c692c2",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["requestLimiterChargesTamperedFrameworkStateBeforeOpeningIt","f0e79a91b8e78a83c6f936d451e51e0656046c1f86d051f3ab60597d56125788",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["toolLimiterChargesTamperedFrameworkStateBeforeOpeningIt","6b3cf774715e0c047a71ad66c4fcc703f8f17c0296a54b964c5fd7e6dcc84b2e",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["nullToolLimiterFailsClosedBeforeOpeningFrameworkState","05ea97059ab267bc841eb808effb0cc0efed2785682305949dbadb5952f920ac",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["throwingToolLimiterFailsClosedBeforeOpeningFrameworkState","eab634b7f093c5e82950b3d9c57ebf842b49fd142ae60172ae88c45057497a72",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
  ]),
  ...reviewedPhasePolicyFile("src/test/java/com/soklet/McpShutdownObservabilityTests.java", "82b0d44a88a7d653d5f01a46b7ebe4deb7a3a2f85daa089c045952724d789408", [
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
  ...reviewedPhasePolicyFile("src/test/java/com/soklet/SimulatorConfigDerivationTests.java", "b32761cbbe3d392e01400df9f90b962bdefc1d103212611a2a179bf13558ea48", [
    ["derivationCreatesFreshTransportsAndLeavesSourceReusable","95e2ceae2a0c8d43bb88a6159c4bb101a73ab4a86b8db2138bc2fc23fd037391",{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":1000}],
    ["importedMcpConstructionIsClonedAndCanBeCustomized","bc53753592d591e6a55ef5d17d6f544888f5bfdbdaa59fd4fbdd149ffc71251b",{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":1000}],
    ["directRunCreatesAConfigurationPerInvocationAndBuilderAcceptsOptions","b6dbc0f825a131dbb475c79cf3b174275089bcb27b801374f09763543f4de8e9",{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":1000}],
  ]),
  ...reviewedPhasePolicyFile("src/test/java/com/soklet/SokletDirectHttpCompositionTests.java", "6252f136b9cb056c2eff589860f091badcd56363947205c95a5d551ca3f1c075", [
    ["forceBeforeChildProofCancelsUnsubmittedCleanupWithoutRejection","71a3e31e2b30174d68365dd52a2b5ce84da32498dd49651477e80b5b6367f554",{"forcedShutdownMillis":2000,"gracefulShutdownMillis":75,"startupCancellationMillis":100,"startupMillis":2000}],
  ]),
  ...reviewedPhasePolicyFile("src/test/java/com/soklet/SokletDirectLifecycleRaceTests.java", "dadaae61fd2ceb287b9a6f0b8c804833d57226abd5642fe9893bbcad89030f4a", [
    ["closeAfterStartClaimCannotPublishNotAttempted","9c333fd872f526f2fab40932f9a79e5609034548060ab89879e7ae4cfd45eab3",{"forcedShutdownMillis":80,"gracefulShutdownMillis":80,"startupCancellationMillis":80,"startupMillis":5000}],
    ["lateBlockedAttachReturnIsInertAndCannotEscapeTerminalEvidence","9d92aee5dd6d35ba42fa72d36d9513cda7c8f0d5f76b668adfbe69f711bbfb52",{"forcedShutdownMillis":80,"gracefulShutdownMillis":80,"startupCancellationMillis":80,"startupMillis":5000}],
    ["installedAttachmentWithActiveWrapperRetainsTransportResidualEvidence","2c0a257151691a658d11c36565ef8ae5e2102fb720a13f5fb3689d466977c00a",{"forcedShutdownMillis":80,"gracefulShutdownMillis":80,"startupCancellationMillis":80,"startupMillis":5000}],
    ["resolverCancellationSentinelDoesNotBecomeStartupOrResultFailure","a84d176a9f5d4be343823fc7ac7808aff1698a0177e42da8ea2a0ce58b2b1a3c",{"forcedShutdownMillis":80,"gracefulShutdownMillis":80,"startupCancellationMillis":80,"startupMillis":5000}],
    ["transitionWorkerLaunchFailureCannotStrandReadyOrTerminalPublication","fff2162561a54734c2041572b8c50e4f3e83bfc4ed780bba6729c9b4d4e2472a",{"forcedShutdownMillis":80,"gracefulShutdownMillis":80,"startupCancellationMillis":80,"startupMillis":5000}],
    ["shutdownAfterReadyLinearizationCannotRetroactivelyCancelStartup","d13d4d3aa30f2beacf90124c91a79bdbf949a966637839d5e4c12b11301d1968",{"forcedShutdownMillis":80,"gracefulShutdownMillis":80,"startupCancellationMillis":80,"startupMillis":5000}],
    ["interruptResponsiveActiveStartTimeoutRemainsTimedOutNotUnexpected","167ce35cd1887150c3ff4d9979f0e6fb148b7c19deac28f4494b568925243b7d",{"forcedShutdownMillis":80,"gracefulShutdownMillis":80,"startupCancellationMillis":80,"startupMillis":150}],
    ["externalCloseOfInterruptResponsiveActiveStartRemainsCancelled","233edea494fa9c25d494597eb80cbbc64cc080ccf87a4ffa01c3ac32de0ebe5e",{"forcedShutdownMillis":80,"gracefulShutdownMillis":80,"startupCancellationMillis":80,"startupMillis":5000}],
    ["sharedLazyResolverDeadlineRemainsTimedOutNotCallFailure","0e5f9066b692a85cb68564726f17b77fd190be466a6170545c1acff9dddc1513",{"forcedShutdownMillis":80,"gracefulShutdownMillis":80,"startupCancellationMillis":80,"startupMillis":5000}],
    ["externalShutdownWinsBeforeInducedStartupCallFailure","49929c1af09ea36d95cc046cb2cc095bf474cf62ea68e4bd8dc80a7c937aff26",{"forcedShutdownMillis":80,"gracefulShutdownMillis":80,"startupCancellationMillis":80,"startupMillis":5000}],
    ["startupCallFailureWinsBeforeLaterExternalShutdown","33388c7cf8170be6d52186a570045147b49cf79afcd9f9c99ae004a89441c931",{"forcedShutdownMillis":80,"gracefulShutdownMillis":80,"startupCancellationMillis":80,"startupMillis":5000}],
    ["startupCallFailureWinsBeforeLaterPeerTermination","bbc19ad542e94288d0a422779729765d811d6e00b4977de554c03dacd662c18c",{"forcedShutdownMillis":80,"gracefulShutdownMillis":80,"startupCancellationMillis":80,"startupMillis":5000}],
  ]),
  ...reviewedPhasePolicyFile("src/test/java/com/soklet/SokletDirectLifecycleTests.java", "b0a306c5ce951b0186b59ede9aad71c15bb325ad11554319c3f486233b0a8f76", [
    ["blockingFrameworkSetupIsBoundedByStartupAndShutdownBudgets","e15a71164f4d44935fdef10beb8d9a350688b4c17e6507931d50dbf9c149d9f8",{"forcedShutdownMillis":75,"gracefulShutdownMillis":75,"startupCancellationMillis":75,"startupMillis":75}],
    ["blockingTransportStartIsBoundedAndCannotPublishLateReadiness","97736864a900e952a6688b8cd001d76a3d096ba5450b0400bd240e5a5d5ee8dc",{"forcedShutdownMillis":75,"gracefulShutdownMillis":75,"startupCancellationMillis":75,"startupMillis":75}],
  ]),
  ...reviewedPhasePolicyFile("src/test/java/com/soklet/SokletDirectMcpLifecycleTests.java", "3deda8bf1b3ab3c8310862276a9a207814fac29f9872fcf70afac157db651198", [
    ["blockingSubscriptionPublisherTimeoutClosesListenerWithoutOverlappingApplicationPhases","0cb35dda144f3b395e73d64a25ffdf1e87c3a557f7c6ca2f1ff86d4c4e3d536a",{"forcedShutdownMillis":250,"gracefulShutdownMillis":150,"startupCancellationMillis":150,"startupMillis":1000}],
    ["externalShutdownCancelsBlockingPublisherWithSameTerminalIdentity","5393c4227fd80704ee07ea3c0a6488e7ccef510f205d3531b65cf16d22cbcc32",{"forcedShutdownMillis":250,"gracefulShutdownMillis":150,"startupCancellationMillis":150,"startupMillis":10000}],
    ["synchronousMcpStartupCleanupFailureRemainsBoundedSecondaryEvidence","c1db3a355348d05a0794371ec9a6c0c5889a555c67797320475279f7e0b7507f",{"forcedShutdownMillis":250,"gracefulShutdownMillis":150,"startupCancellationMillis":150,"startupMillis":10000}],
    ["lateMcpStartupFailuresCannotMutateFrozenEventLoopPrimary","53a54da7546ecf23445a5c3ac2735b3ab643f20d1b233c9504e99b0e3ea5c581",{"forcedShutdownMillis":250,"gracefulShutdownMillis":150,"startupCancellationMillis":150,"startupMillis":10000}],
    ["admittedMcpHandlerSelfStopPublishesIntentAndDrainsResponseWithoutSelfJoin","b70e6a0bddf1bf32f405a735822457f9d22a9d8a116f30da5e1cccc4149ea6bb",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":5000,"startupCancellationMillis":250,"startupMillis":5000}],
  ]),
  ...reviewedPhasePolicyFile("src/test/java/com/soklet/internal/mcp/protocol/McpStreamSubscriptionDiagnosticsPublicRuntimeTests.java", "7496c8396809dcbf3d7eb29ddb0ae248e07b89326b5e1508872bb8ad4845d0e2", [
    ["ordinaryAndSubscriptionStreamsAggregateAcrossEndpointsAndCleanOnDisconnect","433d0e1e11f4ac9ec77b961a1b8308d3c2aaef74e0785be821fc55607cca04b1",{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000}],
    ["residualHandlerStopPublishesZeroStreamsBeforeLateHandlerExit","abcf0c19f2906a7a13f3207280d5eb05b2470d6ceef047f3eb754293ed956cc0",{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":150,"forcedShutdownMillis":150}],
    ["unexpectedFailureRetainsOneSubscriptionUntilCleanupWithConcurrentInvariantReads","320306b0830860efc27ed86ad5c60931d492dc5378416f782f264e6f58ff977e",{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000}],
  ]),
  ...reviewedPhasePolicyFile("src/test/java/com/soklet/internal/mcp/protocol/McpSubscriptionPublicRuntimeTests.java", "f34e2d1755405f60630f884641b2d4761916bb354f3076c8fcf678bbf49044c0", [
    ["acknowledgmentIsFirstAndPreservesExactStringAndIntegerIds","65e6a11d83b200be33fb1ece4f39f9e64ad4dd32135b9f2da5c53c621e1497e3",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["publisherEmitsOnlyRequestedResourceEventsForMatchingUris","e4222b612f419c2e87a2377988d580cf571041916f4c4c4c771ec4464bf0b223",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["supportedIntersectionOmitsToolsPromptsAndUnconfiguredResources","65f5838c6c94ca8637a6c03dd8211f970145650b97dd7c517d60247213020383",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["declaredNonlocalizedCatalogFamiliesAreAcceptedWithoutResourceSupport","530079a909673a0609df7808168e745ce9f48837c3a684b7a578422b89ca0de5",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["malformedRecognizedFilterFieldsFailBeforeAdmission","fe2a9762e612420b02805ff197683384c6b2fbbecadf85a8ced461cb85945d20",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["validListenUsesAdmissionAndRequestLimiterOnly","f107f5c9e9d1456283a6b3b75e4431d026c9269980fb55b0f5f5a58cabd83345",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["rejectedAdmissionNeverActivatesARegisteredSubscription","e0d014853474dbfe76f94312f266cfe5cefb2477ffe98416e0673f44d79f7f38",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["nullAndThrowingAdmissionNeverActivateOrConsumeSubscriptionQuota","ae474f2c1b72114791c81d1f865b1560d7d9327e993b6e2e1eabea075c0906de",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["liveSubscriptionDoesNotConsumeTheConfiguredHandlerSlot","606d2956fca6e3c1dfffd98bbd9df1e5a16403b5279a0c3703d580c8a7f8d1f8",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["configuredPerPrincipalCapRejectsWithoutDisturbingAndRecovers","70b0a620acd78f11509cd344f7e293e06fd6afb37bf50b6e772e1cc63fe87871",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["sameIdSubscriptionsAreIsolatedAcrossAdmissionPartitionsAndCapRelease","4f4cff603e19d261446f943836102c2c0c0e8cacf58f45c47859faca37ee1375",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["publisherIdentityIsGroupedPerServerAndSharedAcrossServers","e472a11ec8fb6f5a657074c2b55870ae7ad80d49d9b8924fc6bdd94aa8f24889",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["gracefulHttpShutdownEndsWithOnlyTheTerminalCompleteResult","4b054c2eaeca9da3a17bcc51fe0fa7c5d76b106d10c7aace497052461f07b9df",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["configuredMaximumDurationPublishesExactLifecycleAndMetrics","d7b3ead71c411fffd7178fd22943276e2e1811bcb7de2bd74eaef6cc26cacd4b",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["clientDisconnectReleasesStateAndPublishesExactlyOnce","09a4a94f7ece3449b6fafbdbd6b77a0e0216a3d880f71345d30023865bce0f03",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["keepAliveAcceptanceSharesStreamTransitionWithCloseObservation","79fd453ea3aa2d710517188d691cea00da5fe6e8137eb7b5b81c0e941aba47a1",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["publisherVisibilityBeginsAfterAcknowledgmentActivation","2c06684c9183e845b01e3a6d3e8e63584dc76dee2fbaee769e4547f93dc2ae0d",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
    ["configuredQueueContainsBackpressureAndReleasesTheFullCap","9f4a766cf8e9187cb0c0c68dbe5c66023fa9c6dc892b45333e194b0fa551fa59",{"forcedShutdownMillis":1000,"gracefulShutdownMillis":2000,"startupCancellationMillis":2000,"startupMillis":5000}],
  ]),
], 'lifecycle phase policy');

const REVIEWED_CONTROL_OVERRIDES = checkedReviewMap([
  // One simulator generation, three modern calls and six legacy calls.
  // All nine response waits are sequential and bounded at five seconds each;
  // the nested exact-revision/URI loops need an explicit multiplicity review.
  ...reviewedScopeFile("src/test/java/com/soklet/McpAnnotatedResourceProcessorRuntimeTests.java", "10a848a77841cfb944a182c4dbd6483b11e374ef6c35fa8c314f72595b6a65ef", [
    ["generatedProviderPreservesResourceContractsAndInvocationBindings","101f7463f3b801366c30f6f2d773682a4eaae34c8ee9367dfb76254cc2643845",{"controlJoinMillis":45000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/BuiltInTransportLifecycleAdapterTests.java", "15f29083d2dfdf367f98d9ef7fe36d0e68ca3c87a25be3754707b37c13371258", [
    ["failureRacingNormalShutdownIsRetainedWithoutReclassifyingRequestedProof","d5b8e568c287856debb44e45137ebaa592ca10ece32eb55ee6be5c748bcd9e15",{"controlJoinMillis":3000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
    ["requestedProofThenLateFailureBeforeFreezePreservesBothInSequence","0486a99df1c9b6163e90faa067fb5cc24f94f2aaa2a1d586bc815614211943bb",{"controlJoinMillis":3000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["exactGenerationOperationsRejectForeignTokensWithoutMutation","ff1b277adb13768eb39a9e28b2a43a292e48b75af80cf0ca8439c542f6a8535c",{"controlJoinMillis":0,"controlComposition":"REVIEWED_NONBLOCKING_PRECONDITION"}],
    ["launchedThenThrowingCoordinatorCannotRepublishOrReleaseEvidence","b03f92588538cd5cf7ce738deb0df255146cbe88d855fa64e643e0bc50965ef4",{"controlJoinMillis":2000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
  ]),
  // The three existing concurrency callables are unchanged. The new 205
  // regression has one owner with the source-bound 5/2/2/1-second policy,
  // a five-second connection retry and an independent absolute five-second
  // response read deadline with an 8-KiB cap. Its 20-second composed maximum
  // leaves 40 seconds under its explicit 60-second method guard.
  ...reviewedScopeFile("src/test/java/com/soklet/DefaultHttpServerTests.java", "7d530d5ade2424bdf30b40dffea22645193217cfe9746df3a5b2c3f694a1cada", [
    ["httpServerCleansUpAfterUnexpectedEventLoopTermination","c0b3c2429b828d7d6b97cc5d324abf4e1bb75c12ec4b6473a7ff080dab147086",{"controlJoinMillis":4000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["stopCannotPublishAnEmptyGenerationWhileStartInstallsHttpResources","daa38ff9c7f3f1effcc52664813c709b9eb9b4ad38458546dff9dd23de556bd0",{"controlJoinMillis":7100,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
    ["startRejectsRunningHttpGenerationWhileItsStopIsInProgress","468e5c2b4365d5a53148b8525487911d065bcd75075ffac281f4cd6b149d25be",{"controlJoinMillis":6000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
    ["resetContentCannotLeakBypassedBodyIntoNextPipelinedResponse","1c83f3f0c2bf657f5f495ff69a7dc3a48d3d1848f1aa24d20b73017728efe3bb",{"controlJoinMillis":10000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  // One fixture owner with the propagated 10/1/1/1-second policy performs two
  // sequential boundedPipeline calls (GET and HEAD). Each explicitly bounds
  // connection acquisition to five seconds, the entire wire read to five
  // seconds and 8 KiB, and completion observation to five seconds. The local
  // scanner does not propagate this non-lifecycle helper; this exact callable
  // and whole-file review supplies all thirty seconds of controls. Together
  // with the thirteen-second lifecycle maximum, the 60-second guard retains
  // seventeen seconds of reserve. The new bodyless-hook regression performs
  // three boundedPipeline calls with the same controls: 45 seconds plus the
  // 13-second lifecycle bound leave 17 seconds under its explicit 75-second
  // method guard. Existing pipeline users and the 205 callable are unchanged;
  // the whole-file hash includes the additional regression.
  ...reviewedScopeFile("src/test/java/com/soklet/FiniteHttpResponseSafetyTests.java", "03d6347361337f683410ce687066f3125c6817079136f6435b2053057af3d465", [
    ["resetContentHasZeroLengthEvenWithCustomHeadRepresentationMetadata","c0bf1604728c1c478fd6f42c7f140e1ff88ea1df73b863afcf656003788c9f76",{"controlJoinMillis":30000}],
    ["bodylessMarshalerHooksPreserveStatusAndPipelineFraming","32192f4e97670ed8c8478c8025ae8bde099d3e19eb3ada8af8b30fa1b1028517",{"controlJoinMillis":45000}],
  ]),
  // Each live static-file probe owns one Soklet in try-with-resources. The
  // shared non-lifecycle wire helper bounds connect to two seconds and the
  // entire response to an absolute four-second deadline plus an 8-KiB cap.
  // Two sequential GET/range exchanges therefore require twelve seconds;
  // with the inherited fifty-second lifecycle maximum, the explicit
  // 75-second method guard retains thirteen seconds. The symlink probe has
  // one six-second exchange and retains four seconds under the default guard.
  // Callable and whole-file hashes bind both owners and the helper's controls.
  ...reviewedScopeFile("src/test/java/com/soklet/StaticFilesTests.java", "09c5c211c1e056606e623af09f682c9a4744e55c9b14fd6506356b982fdc6232", [
    ["staticFilesSecureBodyReachesBuiltInHttpTransport","de1a0c9276b18a670f7a63f64057d1f8d635ff67e608b4e04d5f0facbb24efd6",{"controlJoinMillis":12000}],
    ["staticFilesLiveTransportNeverServesPostValidationSymlink","335087c63cf5cbfb33dcb0cd3f0265f296428a3252b38bae947da0c6fb6f163b",{"controlJoinMillis":6000}],
  ]),
  // The three invalid-length exchanges are sequential: each has a 2-second
  // connection retry and a 4-second socket read timeout. Retain the conservative
  // 18-second control allowance in addition to the explicit lifecycle policy.
  // Existing reviewed scopes and their control topology are unchanged.
  ...reviewedScopeFile("src/test/java/com/soklet/UnparsedRequestTransportTests.java", "bf4c7a9ae5883e5bef6ac33c179a23c0db13252c6c39d4445d85ed5fa7bddc99", [
    ["invalidContentLengthsUseTheMalformedRequestObserverAndResponsePath","682e366167541b94c4e82f2f31b0c964191bd73a26d9ad00731a311434eb0cee",{"controlJoinMillis":18000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["allParserRejectionsAreObservedAndMarshaledOffTheEventLoop","8aaed790abe953d13f70f1e5a6740d477d709444b0f83dac89b2f3eb531ddabc",{"controlJoinMillis":30000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["timeoutsAndUnsafeExecutorDispatchUseTheBuiltInFallback","32855da54b8584e59500cd90c9cb34c6e6fac69563c60f0bdba821d33d1bbc81",{"controlJoinMillis":17000,"controlComposition":"REVIEWED_OVERLAP_OR_DUPLICATE"}],
    ["unparsedWorkOwnsOneLifecycleAdmissionUntilItCompletes","7f465aa9eefd15d7a0e31b630995a403edd000811448399867fa630a13affaeb",{"controlJoinMillis":18000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/ExternallyCoordinatedTransportLifecycleAdapterTests.java", "3e174ec987706f22557041c10810d8d3003ead5dee2edc23b0ef184681a84f4e", [
    ["externalSelfStopPublishesIntentBeforeOwnerScopedWaitFailsFast","80b2f130651f5b7f7a03444787899e2b42e6e82c05f7b3ec251201b0444ae4e0",{"controlJoinMillis":0,"controlComposition":"REVIEWED_NONBLOCKING_PRECONDITION"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/HttpServerLifecycleTests.java", "79057f14d84738c4371d405d58a30a3b0db61184ff96cf54bbf228b430ae930d", [
    ["ownerLifecycleAttachesServesAndPublishesOneGracefulResult","877ea9ef9d79e0293cd1c31fd69d2a088f617c03ec21119fe6fd9edb5acf59d0",{"controlJoinMillis":0,"controlComposition":"REVIEWED_NONBLOCKING_PRECONDITION"}],
    ["ownerShutdownDrainsInFlightResponseBeforeClosingConnection","c0dfba7292562aa6e42757c9286e5bb19a56abfdd51dfe893c5b36f7438fb47b",{"controlJoinMillis":9000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpAuthorizationIntegrationTests.java", "b569426645249159faa048fbb21e8d8225b3316b14f7ecc90e58ac82d02f9328", [
    ["passesSafeBearerChallenge","18d8a354aea3b98ec318f978e6eff9ac9cfd04fa483f836e784ad311690f3b0f",{"controlJoinMillis":5000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["corsResponseHeadsMatchIndependentGoldens","b829a10a347d5a29dd44934e9e290bd4dbe9713222ce030bc01c78685a8b7d78",{"controlJoinMillis":5000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpHandlerInterceptionPublicRuntimeTests.java", "963799edb19b125f7c92c50808e697c0ba4571ccf4cdbcb16733342bc172612d", [
    ["deadlinePreventsLatePublicHandlerEntry","5ee66b45d3613dc2dc4204353ef70cef3ef0391c834dd92f93121d780685f1b3",{"controlJoinMillis":5000,"controlComposition":"REVIEWED_OVERLAP_OR_DUPLICATE"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpHttpContractGoldenProductionTests.java", "893298f26cd54979df0812f92d373960d5ec82303dbd89418ebce6114de25d36", [
    ["requestPipelineFirstFailureWinnersMatchCompleteWireGoldens","bf7cc626f52e90832370476048a7a77ef415db49c2cd7d8e86945b6e12658456",{"controlJoinMillis":10000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["notificationPipelineAndPreflightMatchCompleteWireGoldens","7900dc1f65c7680ae2cf51e8c567acd36b5a66cd1dcaf612bf1e8af869225df7",{"controlJoinMillis":10000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["overloadAndSseAuthoritiesMatchCompleteWireGoldens","5b4f703c234b79db32aa6f71466d5b51994c6151ff73b24258585b30da8c3f1b",{"controlJoinMillis":35000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpLifecycleB3Tests.java", "0e9b9712ae5d9c3e899ca01213911ff37dcbdc4b7b46406572f721174e9ae78d", [
    ["exactMcpGenerationOperationsRejectForeignTokensWithoutMutation","67aedfa580c9dd8c6514285b0dd58076b13327801868c12f4c88cd974052d148",{"controlJoinMillis":0,"controlComposition":"REVIEWED_NONBLOCKING_PRECONDITION"}],
    ["forceResponsiveHandlerIsInterruptedOnlyAfterTheGraceDeadline","52f03eb3d94266f6598738c86e9d91c33506d47dc71342533950eaf36cd495b7",{"controlJoinMillis":10000,"controlComposition":"REVIEWED_LIFECYCLE_CORE_DEDUPLICATION"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpProtocolProfileMetricsTests.java", "7f250c54584533dc5488b00be17658efe72bfd2ce782c3f737fe6f7d666d1f38", [
    ["unsupportedMissingMetadataRecordsUnsupportedVersionNotInvalidParams","1c5987b5317885b726036f3464e4ca8552b7df24e9b1d554e30c07c11aae141c",{"controlJoinMillis":5000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/MetricsCollectorTests.java", "58293841140d6a00196be268056855a6c22dbf179e3af5a10bd58fe7c00a0b7c", [
    ["httpMetricsSnapshot_overNetwork","90cfbd0be23fe0ccaac8a461751ef3f81bd18450e7b0d1ac53cd7ac62a1341ef",{"controlJoinMillis":2000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["sseMetricsSnapshot_overNetwork","115d2a0a9c4c7d31596d646cab6a4b938bed611166b8179c183625b7ff648a03",{"controlJoinMillis":5000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpRequestObservationPublicRuntimeTests.java", "39e3196bf05e38858b029d5954a2e7842fdf1f456045378634889ba38f9df324", [
    ["throwingObservationCallbacksKeepRawCarriersApplicationOwnedAndLogsRedacted","1584d08c0bc77e9af17ac483d4e73d9fb10b1c5bfb6c86fa36a3289c2c0305c0",{"controlJoinMillis":15000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpErrorMappingGoldenProductionTests.java", "fe5e92dca9ca5e8300b11126fc8dd52f41d2ece628d8469635aa91fe2fee58a3", [
    ["ordinaryMappingFamiliesMatchProductionListenerGoldens","7602ac119a458a0dca1389628874fc1eddb1800853c8177f9a68c54cbf3ca816",{"controlJoinMillis":10000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["overloadMappingMatchesProductionListenerGolden","f72bb3db13dea59587bd575bb4ee67baf543cb70f5ff69defb6fe84beda43825",{"controlJoinMillis":35000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpHttpServerRequestScopedSseTests.java", "27493b08e8de3ab5db28f52c2f7a8ece54ca336f2b4702a49a832b5a4385d398", [
    ["shutdown_closes_committed_stream_and_runtime_restarts_cleanly","56025a56f7defe06f6eda65fa1b8882607880ca71c1daa149ca069518aae340a",{"controlComposition":"REVIEWED_LIFECYCLE_CORE_DEDUPLICATION","controlJoinMillis":15000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpSubscriptionRuntimeBoundaryTests.java", "adb0dd2e2fecfe61e9acadeb7b76a88d1a2f5dd5c5e4ce1ab7ed86c457ca7eb8", [
    ["commonLifecycleAwaitRescansRegistrationAfterCloseAttemptCompletes","1b6c55192d0294ab2be799dc20bc6bada8002817bd219aa572ed0865dfde9c9a",{"controlledLifecycleCoreMillis":5000,"controlJoinMillis":15000,"controlComposition":"REVIEWED_LIFECYCLE_CORE_DEDUPLICATION"}],
    ["commonLifecycleForceRetriesAQuiesceRegistrationCloseFailure","ed74ab5cbf8185b3f1e3a30c80ead352af39ebb40dab22715b5489a8e9fffd61",{"controlJoinMillis":9000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["commonLifecycleForceInterruptsAnOwnedBlockingRegistrationClose","c6d71cd9a87bfd649cbcdfc18d9b0f2a1fc9c210a8822784af0435f77fdb3dff",{"controlJoinMillis":7000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["commonLifecycleForceCancelsTheExactRetryAttemptItCreates","a961e251865f9c945028240e564fb3aa899b4835b58ce5886c25410866553032",{"controlJoinMillis":9000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["commonLifecycleRejectsPostForceGracefulRegistrationRetry","534b3aba001d9f537a633a18d20a79eafccad32163e4268c16971a473b6c7048",{"controlJoinMillis":4000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["preReadinessFailurePublicationWaitsForLifecycleElectionLock","0e60adc809631bf6ff8b977d43e6057bea21e1763de4287218165dfab7c9ea4b",{"controlJoinMillis":20000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpSubscriptionPublicRuntimeTests.java", "f34e2d1755405f60630f884641b2d4761916bb354f3076c8fcf678bbf49044c0", [
    ["configuredPerPrincipalCapRejectsWithoutDisturbingAndRecovers","70b0a620acd78f11509cd344f7e293e06fd6afb37bf50b6e772e1cc63fe87871",{"controlJoinMillis":5000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["sameIdSubscriptionsAreIsolatedAcrossAdmissionPartitionsAndCapRelease","4f4cff603e19d261446f943836102c2c0c0e8cacf58f45c47859faca37ee1375",{"controlJoinMillis":5000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["clientDisconnectReleasesStateAndPublishesExactlyOnce","09a4a94f7ece3449b6fafbdbd6b77a0e0216a3d880f71345d30023865bce0f03",{"controlJoinMillis":5000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["keepAliveAcceptanceSharesStreamTransitionWithCloseObservation","79fd453ea3aa2d710517188d691cea00da5fe6e8137eb7b5b81c0e941aba47a1",{"controlJoinMillis":15000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["configuredQueueContainsBackpressureAndReleasesTheFullCap","9f4a766cf8e9187cb0c0c68dbe5c66023fa9c6dc892b45333e194b0fa551fa59",{"controlJoinMillis":5000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpSubscriptionCatalogProjectionPublicRuntimeTests.java", "367cc246acddd7b4db7ee2f026c70477109b4656be2eb777a00fbb671f7b836c", [
    ["initialBaselinePrecedesAcknowledgmentAndProjectionFailureRetainsStream","464b817c0682880985baf06e62568c778f4c884ef74e7b10cf49c41c7822de3d",{"controlJoinMillis":20200,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["reconciliationDuringInitialProjectionRetriesAfterInterruptedCallback","7504cdfef4f81d616cdf96e04afed4902f29ef8c85f92cdd8ef6aab58a4f348c",{"controlJoinMillis":5000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["successfulReconciliationReprojectsWithReplacementApplicationContext","4af209036ed49e7c6cc6010bdd668a84dddeb73ea2a1bbc832b4c9da1b024d9e",{"controlJoinMillis":10000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["reconciliationCancelsAndFencesAnInFlightCatalogProjection","84ea4a1e46a4a3b0fc412258648f58434203e07d739d424e26d0a64d56f9f20f",{"controlJoinMillis":5100,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["timedOutCallbackRetainsProjectionOwnershipUntilPhysicalExit","555788cb0a13c2cb38c9a3be51f6e8e5385189bfe937c70c7e94e1d23a752f9c",{"controlJoinMillis":10200,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["streamCloseCancelsInFlightProjectionWithoutLateDelivery","3a2d3e1a56ce6c70e4649899ac7c3928f44e0bb1d84273a1509715990452484e",{"controlJoinMillis":5000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpSubscriptionAuthorizationPublicRuntimeTests.java", "0926904d5dc0fcf8bb2b1f374008c12886aabd28f3e975d80642d5ed34a5a243", [
    ["establishingReconciliationDiscardsStaleGrantBeforeAcknowledgment","5ce7db149b5adfa93c243212a5a999db97c52445a14249be71febe2247462199",{"controlJoinMillis":45100,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
    ["activeReconciliationFencesDeliveryCoalescesAndUsesFreshContext","6a5b6406e434c0d88c340c831e0424af3677de5648ab91201ea7f593fd434910",{"controlJoinMillis":35100,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
    ["cancellationResistantRenewalCannotExtendLeaseOrOverlapReplacement","a977056ea1345924624a6e28bbfdd106b32e5ee5568ded3cb52c3acab3911d1f",{"controlJoinMillis":12000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpSubscriptionAuthorizationSchedulingPublicRuntimeTests.java", "c7fe2bb517a26ae5d1ad5cd4d7399ce0372d435cb3616ded082703af64cb524f", [
    ["renewalReservedAfterReconciliationFenceUsesReconciliationSemantics","f1a2af84775f551a32ffa12ad87badaaba9e2ca446d13b8aa8e1a5665626fdfa",{"controlJoinMillis":50000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpSubscriptionAuthorizationTimeoutPublicRuntimeTests.java", "6d7a26bebce1de1ad4664a3a83f33bcee112eecc075736b7363d3e1b42bf4344", [
    ["queuedInitialAuthorizationTimesOutWithoutEntryRetryOrQuotaLeak","b5c15fd6cfd5d520990b75f56e6eb1cb4f940970501a3f762518f53239f5c35a",{"controlJoinMillis":15100,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
    ["activeRenewalTimeoutClosesExactlyOnceWithoutRetryOverlapOrQuotaLeak","04ed66263bd566f9faeca608ab1e787315bffa668e1843e0d4e1bd0625fafede",{"controlJoinMillis":10450,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpSubscriptionCatalogOfferBoundaryTests.java", "e644e7871623ffabfc90922898ecfc1a9ed87e4cf69caff44994f50bb072b4b8", [
    ["synchronousCaptureLimitClosureDuringCatalogOfferTerminatesCleanly","3aa4c381a71bca83164a7104130fac4ba6214b5361e7c5069748191c3d156d93",{"controlJoinMillis":20100,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["offerCrossingProjectionDeadlineIsSuppressedUntilFreshProjection","9e49391d449e9ec8f09c4535a4b7573fa9081ec35f9b501ccb375f97c3b6afb4",{"controlJoinMillis":11300,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["reconciliationCannotFenceBetweenOfferReservationAndHandoff","09e007d935873428cdaeda0b27bc2a72081a0dfe5f1c21798dacda01adc70aa3",{"controlJoinMillis":35200,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/transport/McpTransportRuntimeSmokeTests.java", "e3cf904e790fe9785de76d954936ee6d41ab9957ffd6ba6154bfbbba9a9c9c82", [
    ["platform_live_post_uses_independent_listener_and_event_driven_sse_body","144828fecde665ed569b53b21026b6a70f370549ca7692b4b5c7eba00a3ab0d8",{"controlJoinMillis":6000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["virtual_live_post_uses_independent_listener_and_event_driven_sse_body","e63555726c403bec7a507283ddc186a4c3c9f49013e0040443cf2cdfb034fe7f",{"controlJoinMillis":6000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/InternalLifecycleCoordinatorForceAttributionTests.java", "5ce844a25c119300e1e38672120d4cbe6b90757dffc96541669e3734688b3daa", [
    ["rejectedForceLaunchDoesNotMakeLateGracefulProofForced","29b47f3ab51cf5d1a673dccc2df1ec38579eb4271181c771087827d5f5eb391c",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":8000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/LifecycleFoundationTests.java", "caf577c2b01b8168787bfb049e4804898658b239417cd4805f32de6744dde5fa", [
    ["blockedLifecycleCallDoesNotPreventAnotherParticipantPhaseSubmission","4206c710a70e1c2a9198e219d085020449c7f653f70586b93c0de2ca47d67666",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":2000}],
    ["graceExpiryCancelsBlockedQuiesceBeforeSubmittingForce","600ac5c14550d27da74baa83f9b5500ee370b0fb06cc21a9a7381b1338d158d0",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":0}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpHandlerMetricsObservabilityTests.java", "adac81c1b3e144a7648c5f6cfbc69f2123f6fcdd47e4c8117082c1251f95431c", [
    ["queuedDeadlineDequeuesWithoutExecutionAndRetainsActiveGauge","506c4820d068a3df576091eee4910e7fb0c63723f32f25688ba6d8fb4682c19a",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":45000}],
    ["managedResidualShutdownDequeuesAndFreezesGaugeAcrossLateExit","eef1d5eaf81478cc8f668b84a0b0154ac17660ffceda81eb504a63328805539c",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":35000}],
    ["unexpectedTerminationDefersQueueCallbackAndFreezesTerminalGauge","c22022c402ac95bb944ce68148cfe308da3e3f208f4e0738bd05f36363fce9ee",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":55000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpHandlerQueueDiagnosticsPublicRuntimeTests.java", "2677ed230eb935cd74cbf5d84d736e89b0edc04cf68c0479df342712c8ca4014", [
    ["residualStopRetainsOneActiveAndDrainsQueueUntilLateExit","f09a88590dd6bf66e104c80fdba88e0d85e101e439d763ff63eab8b53db94f81",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":25000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpLifecycleB3Tests.java", "0e9b9712ae5d9c3e899ca01213911ff37dcbdc4b7b46406572f721174e9ae78d", [
    ["noncooperativeHandlerClassifiesResidualAndRetainsItsGraphAndAddress","c090937c5cb6899b7251a3fce920523de2895ca2692b8f6b6893522c5656f9d4",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":20000}],
    ["unexpectedEventLoopFailureFencesBeforeProofAndRetainsAddress","e5a4fc15428bd0b3ab87b7430a0f2ebf6281a21282fe4905918703b64f5b6f52",{"controlComposition":"REVIEWED_LIFECYCLE_CORE_DEDUPLICATION","controlJoinMillis":10000}],
    ["oneShotOwnerCannotConsumeUnexpectedGenerationBeforeExactResultPublication","8ec7c5714e6b014e07678de524cef1b8a49784ca98bdd8f04dcb66d811c62c5b",{"controlComposition":"REVIEWED_LIFECYCLE_CORE_DEDUPLICATION","controlJoinMillis":20000}],
    ["simultaneousStartupFailuresShareTheElectedEventLoopPrimary","2ce5ffc354a471ad24d361f23e20a1e81dfdbd3c702b857c905e5e1c904d0035",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":20000}],
    ["synchronousStartupFailureWaitsForExactCauseElectionBeforeTermination","4ad1e0bb6e115c7e2f170dbd66213fa055498ada418e291025f5b3b73309ab56",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":25000}],
    ["eventLoopFailureBetweenRuntimeAndCommonReadinessPreservesExactCause","357e7603e01bd31e58af2e4e6c30ce99a3fbff91ce1e11efd29a541e593b19fd",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":25000}],
    ["deterministicNoProofMapsToMcpUnknownAndRetainsEvidence","1494be5b83b11e6915906fd580725bdaf02481f55eed026eeda110476477522a",{"controlComposition":"REVIEWED_LIFECYCLE_CORE_DEDUPLICATION","controlJoinMillis":0}],
    ["deterministicNoProofRetainsTheExactBoundEphemeralAddress","fc2eee11e106de18ad84f3cb6e40c4939f330d448e52be7c4a1181a649ff52fc",{"controlComposition":"REVIEWED_LIFECYCLE_CORE_DEDUPLICATION","controlJoinMillis":0}],
    ["blockedMcpQuiesceIsCancelledBeforeForceAndProof","e4c72353ed7574cf8b45b61502de771cbb513b77579bf3d6c79717287c5014b7",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":0}],
    ["shutdownIntentFencesAdmissionBeforeDeferredMcpQuiesce","689238678986fe2dd56cff28da44744f9c7bc748b73b8e05a4d8abd9cca21243",{"controlComposition":"REVIEWED_LIFECYCLE_CORE_DEDUPLICATION","controlJoinMillis":0}],
    ["idleSubscriptionClosesPromptlyWithServerStoppedAndNoForce","d16b3d7074e5c58a7d42cc6b290ed0dbc5147ad17b5288fc720440cd25355470",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":30000}],
    ["ownerNormalizesRetainedUnexpectedGenerationOnceBeforeRejectingRestart","2afc232071d7c006ad16f324b8abae2c0b5f5c4875b7dd64a0c0e71921c93b07",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":30000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpLocalizationAdversarialTests.java", "cb874b4b2169ba8720b396aa8271715f6e5a4dfccefbb8835a4f10441d944fbc", [
    ["rejectedAndIrrelevantWorkNeverInvokesTheProvider","314ac4b16ec574dc021d47c15bf20ba61dc3e5d9a7285af060451a08a6d20977",{"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","controlJoinMillis":50000}],
    ["aUniqueTagFloodRetainsNoStateOrMetricSeries","1269ae110af6ca3bc5fb5c0e36d27779072a1687f22a7c802ce46c3b3f076f2f",{"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","controlJoinMillis":30000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpMetricsEventDeliveryPublicRuntimeTests.java", "093d7ba98a614a9514abc064729b7bcea1338c042a4f6eff9a6ef41808848234", [
    ["unexpectedTerminationOrdersNormalizedStopBeforeFreshOwnerStart","d8e231e0f7b581efb02c062b5cae49f507d0bb8839975b8cf43f966d1215e39c",{"controlComposition":"REVIEWED_LIFECYCLE_CORE_DEDUPLICATION","controlJoinMillis":2000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpShutdownObservabilityTests.java", "82b0d44a88a7d653d5f01a46b7ebe4deb7a3a2f85daa089c045952724d789408", [
    ["unexpectedListenerTerminationAndFreshOwnerHaveExactParity","658fb897e3bcf0c822984d9d201f5c79c95dee91bc0957419d653a984b2c9b14",{"controlComposition":"REVIEWED_LIFECYCLE_CORE_DEDUPLICATION","controlJoinMillis":17000}],
    ["ownerNormalizesUnexpectedGenerationExactlyOnceAfterAdapterWait","39e48c1d89a57026241e6d00641b34ff51e116108eb37e6fd35945c2a91973f7",{"controlComposition":"REVIEWED_LIFECYCLE_CORE_DEDUPLICATION","controlJoinMillis":12000}],
    ["rejectedUnexpectedRestartDoesNotDuplicateBeforeFreshOwner","f4e7d5392de0df5da2dd468969ae18d43b2188c81d87fa21afabdebd1d4ef9ef",{"controlComposition":"REVIEWED_LIFECYCLE_CORE_DEDUPLICATION","controlJoinMillis":17000}],
    ["residualStopAndLaterExitDoNotDuplicateLifecycleOrMetricsOutcome","7279d8846c1263409c86b2c53e4a2db929463300d7dacc6d523b35769f8f1718",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":30000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpSimulatorPublicRuntimeTests.java", "0e2be1c351c0bb667d0a419fe9c3f3d921215361e9c01b696499355d58c6ac6a", [
    ["noncooperativeSimulationCleanupIsBoundedAndPreservesSuppression","527e1495a7b27842684e2d0ee1ab4a5100de3dadc4ba9f807e78fd52d4fedac9",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":10000}],
    ["nonDrainingCaptureLimitDoesNotBlockUnrelatedSimulationOrCreateTransportFailure","cb7489745af384abfe74181d68ec47d64a44aa48ebb2e5dfa7a7435db86fa702",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":35000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SokletApplicationObservationTests.java", "0c866bfa06729660ba06719c12bc78232104f528de275e9f4504fa69e65c0ab3", [
    ["transportLogDuringAttachIsInlineNonqueuedAndTracked","5a1f980df91ca14fe4703a8f6f5363d4b21a5831ef7c94f7b10894de4b59b4d4",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":30000}],
    ["blockedTransitionCannotDelayRunnerCleanupOrTerminalReport","02bf75613b8edcb5e964716bcb7e20572e58d3240561cbfb881fe6e0ddfc0e45",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":40000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SokletApplicationProcessTests.java", "22a0c2f6111efa0281aeb2b5a439a513109df06886ca0659cb5a7a8719d573d4", [
    ["concurrentHookInterruptionAndExplicitShutdownShareOneAttempt","220bb931c97ebef43d8655776594332241491731e61ec9bc796c0d926c66c3f4",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":0}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SokletDirectLifecycleRaceTests.java", "dadaae61fd2ceb287b9a6f0b8c804833d57226abd5642fe9893bbcad89030f4a", [
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
  ...reviewedScopeFile("src/test/java/com/soklet/SokletDirectLifecycleTests.java", "b0a306c5ce951b0186b59ede9aad71c15bb325ad11554319c3f486233b0a8f76", [
    ["blockingFrameworkSetupIsBoundedByStartupAndShutdownBudgets","e15a71164f4d44935fdef10beb8d9a350688b4c17e6507931d50dbf9c149d9f8",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":5000}],
    ["blockingTransportStartIsBoundedAndCannotPublishLateReadiness","97736864a900e952a6688b8cd001d76a3d096ba5450b0400bd240e5a5d5ee8dc",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":5000}],
    ["admissionRemainsClosedUntilEveryConfiguredTransportHasStarted","50c0f28c0e07b4cdd17e26fa3c7d33e406ead223d08339c7e4b476b7590059c8",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":4000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SokletDirectMcpLifecycleTests.java", "3deda8bf1b3ab3c8310862276a9a207814fac29f9872fcf70afac157db651198", [
    ["synchronousMcpStartupCleanupFailureRemainsBoundedSecondaryEvidence","c1db3a355348d05a0794371ec9a6c0c5889a555c67797320475279f7e0b7507f",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":0}],
    ["admittedMcpHandlerSelfStopPublishesIntentAndDrainsResponseWithoutSelfJoin","b70e6a0bddf1bf32f405a735822457f9d22a9d8a116f30da5e1cccc4149ea6bb",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":27000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SokletDirectStartClaimTruthTableTests.java", "c94d8019767282a1c342fad4a157dd68ed7d8f1ab49fdca54ec3c7125bb26c81", [
    ["startRacingNewOriginShutdownWaitsForExactNotAttemptedResult","789870435f667be6794e3fdb2fe1cc6499c5e15f6c3c75212c29dfca7dab371c",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":6000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SokletDirectTerminalPublicationTests.java", "dab2b99392310cb48d54d26298fb03c2bfa41ed7feb9d4205ec25a8c38f07e3f", [
    ["blockedPreRegisteredContinuationCannotStrandPrivateOrPeerOwners","1d864c33e7c10d2827a931036b1421d194fa1d66acf06ff064d48ec14888b874",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":2000}],
    ["concurrentAndPostClosedShutdownCallsShareOneStageAndResult","e8276afc6b3e60b38ea2056d4a07066d94af2eadb4baace5c97711812deab8f7",{"controlComposition":"REVIEWED_CONCURRENT_MAX","controlJoinMillis":10000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SokletDirectWaitSemanticsTests.java", "d200f0ed18f1cd7532ea1f0141fa718c05e1d6ca0390896191ead5c0f7d8b242", [
    ["concurrentCloseCallsJoinOnceAndRestoreEntryInterrupt","9ff6d7a16fa1b0d95b8f3552f038fa68a4f83d6ea485edb49f0e7efc0895e36a",{"controlComposition":"REVIEWED_OVERLAP_OR_DUPLICATE","controlJoinMillis":2000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SokletProtectedLifecycleCompatibilityTests.java", "986548d59fd53fbc313cab83858948dddc7ff1f3a3a3ba1556af576d435f4efd", [
    ["holdingProtectedLockProjectionCannotBlockShutdown","fd9b541b57d7dc0124a6ccd1dbfcf784c2c1208b27eedb40a48d68309dc7f908",{"controlComposition":"REVIEWED_OVERLAP_OR_DUPLICATE","controlJoinMillis":8000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SokletDirectTerminationPrecedenceTests.java", "fc2e9a0459ddb113411e9e937d45743bbfc7461095829ba8c69f82a815ad6f48", [
    ["ownerShutdownIntentWinsFormerGroupFanoutGap","da9453ddd92e3d10cfe8cb15476bf271efd399c14e41292a2edf1ac54b142ef9",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":8000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SokletMcpLifecycleTests.java", "acad4e2c22f13dfd5d42b7bb00e444cf36973ec693cca8f2f8abe19e69bc6e89", [
    ["noncooperativeMcpHandlerFreezesOneResidualOutcomeAcrossLaterCalls","18f86a94442c30148ba2d419b695d6d7b4eb8201c321f5356179a013c1f4328a",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":20000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SokletSimulatorIsolationTests.java", "eb7752f519e2b19d0b0d5127606dd63468295e05738b3b7086773d30c68823b2", [
    ["blockedFrameworkSetupUsesOneExactStartupAndRollbackSchedule","442ee614d657896f5cc74b540cd579692b71200861966840267bfc61894e3f7f",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":30000,"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["concurrentConfigurationReuseLetsExactlyOneRunClaimIt","5579f354b0e00bb9359ba6b607e9e803ea670e7c723448103ae2ab25fd364b9e",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":25000}],
    ["liveMcpStartQuiescesBeforeCancellationAndCatchesUpToForce","37b14f522e8065b3983c1b55e79f9930e5b4756ed89c67534386cb02e9ee3896",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":15000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SseTests.java", "9ce193b4f0e2ec07a6b602600a3171dd27895fbb9db776a3b9c64d9dcab61920", [
    ["sse_handshakeHeaders_and_basicDelivery","208521f69b1bfc61bb8a8c1e0b36896118a657cdd89c94ad719f2926e3e2527f",{"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","controlJoinMillis":4000}],
    ["sse_largeEvent_isFullyWritten","46d8b4ad0c494da245e01d234c8c0bcda5285aaabf2f1d00fbc1a3ffdb8dc425",{"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","controlJoinMillis":4000}],
    ["clientInitializerOverflowOnRealSocketIsLoggedAndMetered","c337859787d683b69294f92eccf46b460b6937420ded75ec9b621b3c51945204",{"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","controlJoinMillis":9000}],
    ["sseRequestReadTimeoutWithoutRequestProgressClosesQuietly","c30dd368040511ddb30cee40018bb2c34a1856e44950374489cc20f68ac4b8de",{"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","controlJoinMillis":7000}],
    ["ssePartialRequestReadTimeoutRecordsTransportFailure","21c9a86970ed545e3408ff69bc926b827470296ef01754bac8d92f01f35e49c5",{"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","controlJoinMillis":7000}],
    ["sse_stop_allowsIsStartedDuringShutdownWait","f8620ccecfa2908fbe4b7f91d8d3c19e27520e01a0751f4bc4388cb4ca771849",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":6200}],
    ["sse_broadcastMany_underBackpressure_eitherDeliversOrCloses","90f2ce824963923396279bc51283ef32758e92e8a7b5a4224060eb9cf050b06a",{"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","controlJoinMillis":22000}],
    ["sse_backpressure_setsTerminationReason","57c88622e2e903c9c5cdc2c5a28ed5c03873a0309f595cd988d460fc70a467c6",{"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","controlJoinMillis":43000}],
    ["sse_stopClosesConnection","67286f7497cead9917981ef5f4a6ce01ed1615bde5089cb2900b990ec4eb352e",{"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","controlJoinMillis":16000}],
    ["sse_stop_setsTerminationReason_serverStop","a5e73812dcb5ab92b84df7491359ee0155408284d06e810af03830787d6e4fe6",{"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","controlJoinMillis":17000}],
    ["handshake_unknown_path_returns_404_and_closes","bdb759c5ce43b365e392969abac46e01366d82caaf61fab8d046d0097b3cbc8d",{"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","controlJoinMillis":9000}],
    ["handshake_rejects_transfer_encoding","6754782c6ecbb8e6f848cfbbe2beecae29bb1eeb9d25700749fe85a8d547ec4b",{"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","controlJoinMillis":9000}],
    ["handshake_rejects_nonzero_content_length","59a3658a40e3481ed63aab3b4c5d6eb8ca421137ce26679207b675171a98ca4e",{"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","controlJoinMillis":9000}],
    ["handshake_rejects_missing_host","7c6e7e9bb67a1f80c4935b448dbadb4d71d08acfacd948ba261797d4b9b9efe7",{"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","controlJoinMillis":9000}],
    ["handshake_rejects_invalid_host","adbb1bdc87a05c2a6379326211a8f99ef21018e7fe1627e3f63b7811b272da7a",{"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","controlJoinMillis":9000}],
    ["handshake_rejects_expect_header","f7bbe2f1a4be360ac172ff9ece2a0b701b2c4ac6bd1156e175d21c6e7616e6df",{"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","controlJoinMillis":9000}],
    ["handshake_rejects_control_char_header_value","c9e7ffc5acc16ddd80fa2179f4786f7994fccf0d6ad7832d7bca38b918aaabf8",{"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","controlJoinMillis":9000}],
    ["handshake_times_out_returns_503_and_closes","ad3913cc400dd243a2db3180abb751b953e4add5741bec6a3937dfc8cb08c9fb",{"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","controlJoinMillis":9000}],
    ["handshake_read_times_out_returns_408_and_closes","69b1ffc986dd75d8ad4e6291b3ca112e0378b785de1dc30ec251bf1f25c1fd06",{"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","controlJoinMillis":9000}],
    ["timeoutInterruptOwnsFailureClassificationAndDoesNotMeterTaskError","7c99e78139af90f3949c7b61afb383019cad8fe930b972823b4f5b7638e66989",{"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","controlJoinMillis":9000}],
    ["stopCannotPublishAnEmptyGenerationWhileStartInstallsSseResources","bc651018d3b7b036b1776e671b0189238822f82445718efeb3fb919c63c7b2aa",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":7100}],
    ["startRejectsRunningSseGenerationWhileItsStopIsInProgress","9303908c0e9f97fd45adc11e233d34ed035fef47e18574fd752838d11cafbbd7",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":4000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpConditionalCapabilityProxyRuntimeTests.java", "16ae036e363e2725e68adbd96381ff8672c92339c9f63925a5f4165a0430c51c", [
    ["proxyIdleExpiryCancelsSilentHoldAndSupportedControlForwardsSse","600b5521724bf945b555dcf4ee2c2ef57b9029f06216312363a3488f187e6150",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":55000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpHttpServerApplicationExecutionTests.java", "0d97eca40e871a08535dbe57b44098666eb1f8b9a683369f5ffcaf5a5b5ab377", [
    ["queued_absolute_deadline_gets_the_exact_capacity_response_without_dispatch","0bf162376ecb7068d1c2ba9a5f7956437edb716f815eee314abd0ad860ed144a",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":25010}],
    ["request_deadline_is_captured_before_protocol_admission_work","2879c5fe6cebc46d14e3f8d58554f84b1f067ed7725279c8eaf2b3c436499ce7",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":25010}],
    ["protocol_deadline_comparison_survives_monotonic_clock_wraparound","0cc1f79ed3418fb6110a2bc82f286f88c081549af1ac531433b96a68d4a6c0c7",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":15000}],
    ["deadline_during_cors_authorization_prevents_later_admission","5f0392c74d0d9cc4542e368660ae66094f99dd8f271a50ad7b77df418750fbac",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":25005}],
    ["protocol_processor_backlog_expires_and_releases_canceled_queue_capacity","ac1f121d8d9dc584a3303ee3ea354f34c45057af7f8503e34a4f19982f4ea92d",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":30010}],
    ["framework_discovery_deadline_releases_identified_exchange_accounting","2a6e8c28000629fc49630cfa8a75fa25b822a4165061fdf2aae15116611f65ea",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":25005}],
    ["active_client_disconnect_interrupts_but_retains_the_slot_until_handler_exit","c85c24cb0c9975d573709f096a78282a69e67d9a907ed9ae1645f88acc5d5bcd",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":25015}],
    ["lifecycle_grace_preserves_active_handler_then_force_interrupts_without_promoting_queued_work","fb1e2567d1e404d58ca8cd2d8ef36f003fae2e32f0c4558172d8982627927bcc",{"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","controlJoinMillis":20005}],
    ["shutdown_reports_residual_application_work_and_blocks_restart_until_exit","bbbee36baa887838cb7b30b073ae71813e2ac320b8a75f85333d4248068c2639",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":15255}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpHttpServerObservationTerminalRaceTests.java", "970def3ef8f8be9f7b3c1b6dafbec12035b62ce00a2739543e9bf8847979b5fe", [
    ["lifecycleLeaseOutlivesApplicationExchangeUntilBodyCompletion","3c14e63effb9b74c2e2ad2ffa68fd72e546b336d2a92ea2984f0c7138d239066",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":15000}],
    ["protocol_completion_cannot_preempt_early_stream_terminal_owner","3c3cab9761afbc72e9e12a2032dc194f92d7a41eb2c825ba29e60ae9bd6d668c",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":15000}],
    ["written_sse_terminal_beats_concurrent_client_cancel_exactly_once","e551d1a7afe3132f71cb928e8ba7ae8ebffbaf3307fe1330000ebf314e59433d",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":55000}],
    ["precommit_mapped_error_beats_late_client_cancel_exactly_once","8bb01c5e4e33e72bd7351475f713210b142d245f035dc28dab57bbaab668f519",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":10000}],
    ["written_streamed_error_terminal_beats_concurrent_client_cancel_exactly_once","fa2d0a6e55bb5028f2c8bf15d121e6e1a503252d302939c72c3a1f1d7b3deaff",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":55000}],
    ["client_cancel_beats_unreserved_streamed_error_and_discards_its_metric","3ae3542e1c26de67e77f97293b73e1490fad92aeaa3adae1e979bb440603e838",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":20000}],
    ["application_encoding_fallback_reports_actual_internal_error","bdaad89f68845d7b1daa59c5a124dcabfe5dda4ea41e6a2599fc3423826690e9",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":10000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpHttpServerPolicyPipelineTests.java", "f2932bd98a694d5cf51d8befbb172b5a169b66123d7309e61040cdf401e314f8", [
    ["policy_null_exception_reserved_code_and_unsafe_header_fail_closed","998e3a54b55f4ff58e1b75ac8d31c00e649a2d93dc73abaddc6e31ae4cad7893",{"controlComposition":"REVIEWED_DYNAMIC_NODE_MAX","controlJoinMillis":10000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpMultiRoundTripTerminationRaceTests.java", "99ffcc9aad351eb991e609189cba924ab7b977b750bbc9fbe8bd2648eb2e456f", [
    ["blockedCustomProtectorOpenMakesShutdownResidualUntilProtocolWorkExits","e064a6917cf6535e0a43fc52d299c8e51c4c8c855fa0d0420ac26930399fb8e0",{"controlComposition":"REVIEWED_OVERLAP_OR_DUPLICATE","controlJoinMillis":10015}],
    ["blockedCustomProtectorOpenDiscardsLateResultAfterDeadlineOrDisconnect","9fe0428fbf8a051f8856858b80b43e838880af781f3cf347ae86a50a84792910",{"controlComposition":"REVIEWED_DYNAMIC_NODE_MAX","controlJoinMillis":20020}],
    ["blockedSealCannotPublishLateInputRequiredAndReleasesExactlyOnce","9fa9b58c00aca22633217bf700bf7e481d25fd151076c03d2621399daecb4529",{"controlComposition":"REVIEWED_DYNAMIC_NODE_MAX","controlJoinMillis":15020}],
    ["sameAuthenticatedStateCanBranchWhileOneFreshIdTerminates","e2658db8bc19db08af218c4aa7408c71c66d309caa4d4edfe61b30847f57274c",{"controlComposition":"REVIEWED_DYNAMIC_NODE_MAX","controlJoinMillis":30025}],
    ["conditionalCapabilityHoldTerminatesWithoutProgressOrLateResult","e2a6c3af0f11bbd8c32e537b0c7209aebfeb71932f14c3f78dcf445f1d2d9bd1",{"controlComposition":"REVIEWED_DYNAMIC_NODE_MAX","controlJoinMillis":15015}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpProgressPublicRuntimeTests.java", "fecbd1d809e2251c451082dde8de21a39a5659529d893ef670b326837a2f8ce5", [
    ["progressEnqueueWinsBeforeMappedErrorTerminal","bc346e059df77c8ec558fb5ff75cc18dfeb0cf6f8a81fdbb59fba6cda0552913",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":35000}],
    ["mappedErrorTerminalWinsAfterProgressEligibility","ac973dea8b26a499defa252028530323c1dd22286b3bf3a2463ffb99bcf47207",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":35000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpSimulationLifecyclePhaseTests.java", "745d4366596625c484e3b82db19653bf6a0998f6a9b037ad723d47c2c390940c", [
    ["bridge_quiesce_is_idempotent_fences_starts_and_releases_proof","fdf054d18525497fbd18207c4d56949875b199a895d53240cfb524faa791fcde",{"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","controlJoinMillis":5000}],
    ["graceful_simulation_drain_does_not_interrupt_admitted_handler","ecf88d4c91c2237e9c58bff0fc70bcf473bb6968054cda7e4b2cb31959d8431c",{"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","controlJoinMillis":20000}],
    ["graceful_simulation_drain_preserves_committed_progress_stream","79c0f19d7df0f14a0baae08257efd2d3ffe5c076c6fe36b53f1863cf3dfc9ead",{"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","controlJoinMillis":30000}],
    ["force_interrupts_admitted_handler_and_reaches_complete_barrier","72bbf104454439b0cf7e99a4622cfdd0f20d44190564899e3af1175e8dc4e537",{"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","controlJoinMillis":15000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpStreamSubscriptionDiagnosticsPublicRuntimeTests.java", "7496c8396809dcbf3d7eb29ddb0ae248e07b89326b5e1508872bb8ad4845d0e2", [
    ["residualHandlerStopPublishesZeroStreamsBeforeLateHandlerExit","abcf0c19f2906a7a13f3207280d5eb05b2470d6ceef047f3eb754293ed956cc0",{"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlJoinMillis":20000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/BuiltInTransportLifecycleAdapterTests.java", "15f29083d2dfdf367f98d9ef7fe36d0e68ca3c87a25be3754707b37c13371258", [
    ["delegatedRuntimeDefersOverlappingShutdownAndCatchesUpAtStrongestPhase","ecddaee114a6401a5946400f867ddf2a76bc67d7f061390ec5c147b75c9a3235",{"controlJoinMillis":3000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
    ["delegatedRuntimeSerializesForcedUpgradeDuringStartupCatchUp","83ce2088246f1dcb4f1f5a01c268683832d59f3ebe7ed36ef17385b5a0e24da0",{"controlJoinMillis":4000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
    ["delegatedRuntimePreservesStartupFailureWhenCatchUpShutdownAlsoFails","8a63bce72eb61c1b38c94ce3449a8838e6e9760417979b79dbf55a70500d12af",{"controlJoinMillis":3000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpTaskCancelationLifecycleTests.java", "e30e05eea80fd156fa2b93f589aacfb47517b5893e3abbf8e74f85cfa273722d", [
    ["requestCancelationNotificationIsAcceptedAndIgnored","d381003bcab94e064688bb0dcda395e4977043e37183206258a3e6b07b8b4a36",{"controlJoinMillis":25000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpTasksPublicRuntimeTests.java", "17a7cc117eba1b044726a58ce5176809e71aa277af85f1b401f9344ea3c7dc15", [
    ["admittedTaskRequestsPublishExactLifecycleAndMetrics","899271d56419f9f7b52156a729fd04e29a30667dcd73fb7d066c0acc6d9a9090",{"controlJoinMillis":10000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpTasksSimulatorPublicRuntimeTests.java", "80256674b6bf6e86b18d8d40076b4bb770ff1743b3f8a18c32d463a56dc561ee", [
    ["admittedTaskRequestsPublishExactLifecycleAndMetricsOffNetwork","2ba5f54f8b9f241754c3b6c608d86ab569ab5a556dac6e1dc7526cc00ac040a6",{"controlJoinMillis":80000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  // Each prepared HEAD wire test owns one fixture. A pipeline exchange is bounded
  // by a 2 s connect retry plus a 5 s whole-read deadline; tests use one or two.
  ...reviewedScopeFile("src/test/java/com/soklet/PreparedHeadHttpResponseTests.java", "63ed03f67c93f291ddd8f4d468224378f309de895917911d030cc2cfcf78e20a", [
    ["explicitHeadPreservesPreparedGetLengthWithoutWritingBodyBytes","78e62edafd3915cadf7c6d7f591590036d344b8da27539b9c1ea1d4d76e5aea1",{"controlJoinMillis":14000}],
    ["explicitHeadWithoutRepresentationLengthOmitsContentLength","41733cade24aea7e6de59ba9d5c34d1a7446c4dd683b032ff5fb4a775a3e3a7b",{"controlJoinMillis":7000}],
    ["conditionalGetAndHeadDoNotInventContentLengthForNotModified","27ed3340e509b1b1474d7bb54dcfe1678c5f5f10cf70be7fa00cddbf3a2de4f1",{"controlJoinMillis":14000}],
    ["implicitHeadStillCalculatesLengthFromGetRepresentation","96df0fd86cc90cc91b49a10687a7cfaf71c6e28c185a75f5f37ca3532418d4fe",{"controlJoinMillis":14000}],
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
  ...reviewedScopeFile("src/test/java/com/soklet/SseLifecycleAdmissionTests.java", "3c2be7b755102190c67d220efa565286ea6ddb1d666a171ae9dfc2479a46dd44", [
    ["defaultConnectionAdmissionSaturatesAt256AndRecoversAfterDisconnect","5173fc6883a04a1e6ba25761a0089944b1f0f5ffc4481cc64a3abc2833f4f6e6",{"controlJoinMillis":20000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":2000}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SseInitializerRuntimeTests.java", "3cca5bfa89b76b3050355b8318187603e1017ad5aa4181977a0ef9edbda231f0", [
    ["synchronousInitializerQueuesCatchupBeforeBroadcast","794b00f4ed0fc82f0b3d67fd754e8a3b396c22cf05904bf4a9b3e08d7ccb6c74",{"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":100,"forcedShutdownMillis":100},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":5000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["checkedInitializerFailureTerminatesWithoutActivation","49aa97ea941f0872f2a3783feb5dfbce530f360ed311c6119736271d0a1dae69",{"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":100,"forcedShutdownMillis":100}}],
    ["caughtInitializerOverflowStillTerminatesWithoutActivation","e49f15441883106668a1979af343d411c215665c4bae88d6c81dab71c7059d99",{"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":100,"forcedShutdownMillis":100}}],
    ["lifecycleCapacityRejectsBeforeAcceptedHeadersAndInitializer","69e7f6e0a0cda68218590541b486dc68738f397cff9c1f16cf58d612582fe721",{"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":100,"forcedShutdownMillis":100},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":7000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["shutdownAccountsForBlockedInitializerUntilPhysicalExit","3849c718c12afc276bdf6b4052a7bf2d4d53d143b3f1997055ee5fbb398a5bf1",{"controlJoinMillis":21000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":100,"forcedShutdownMillis":100}}],
    ["finDuringInitializerInterruptsItAndRetainsCapacityUntilExit","1fb9052a1a28cfc88299a3d0b64ea907566e0bb6f341e5020c95af272ad01d05",{"controlJoinMillis":21000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":100,"forcedShutdownMillis":100}}],
    ["resetDuringInitializerInterruptsItAndRetainsCapacityUntilExit","91bb073f31fb759d990abd8222908b733e9ee2d55bdaa88cbd2c9e1cc7716148",{"controlJoinMillis":21000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":100,"forcedShutdownMillis":100}}],
    ["finDuringInitializerDoesNotDependOnQueuedWriterExecutor","adb51c60386c9044b3166e6e9afbf255cbf75e9a7a13f4051adc31e2f705d4cf",{"controlJoinMillis":21000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":100,"forcedShutdownMillis":100}}],
    ["resetDuringInitializerDoesNotDependOnQueuedWriterExecutor","50554b7a0dd21ea3955ca7fa1cdc463f2113ef1277c95801ac9cca8bc3700336",{"controlJoinMillis":21000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":100,"forcedShutdownMillis":100}}],
    ["disconnectBeforeInitializerEntrySuppressesApplicationInitialization","277cef37c3364d6868c1436a09430fa1ecd4740eca5246fee96e6281998773af",{"controlJoinMillis":18000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":100,"forcedShutdownMillis":100}}],
    ["shutdownInterruptsCooperativeInitializerWithEarlyDisconnectMonitor","a3e0b71a0fdc0b94f1806d2d5835b268883ad2321dab54e3e71dbf97cf699715",{"controlJoinMillis":18000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":100,"forcedShutdownMillis":100}}],
    ["rejectedConnectionExecutorReleasesAdmission","fcc1b980dc9863959d36223ca8f2a54ea582bb120821a05e2c2ee5175892e4a7",{"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":100,"forcedShutdownMillis":100}}],
    ["queuedConnectionEnvelopeIsRetiredOnlyWhenShutdownRemovesIt","08947e8c53bdc4591b2944a2a16d0579b3ee07954287ed591c632ea6c88f06cf",{"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":100,"forcedShutdownMillis":100}}],
    ["passiveClientDisconnectReleasesAdmissionOnHeartbeat","f21ee6c3a8b46dcc96f2a38f00e98f0bc28dc4ff35463e1710de708e10da57bf",{"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":100,"forcedShutdownMillis":100},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":8000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SseTests.java", "9ce193b4f0e2ec07a6b602600a3171dd27895fbb9db776a3b9c64d9dcab61920", [
    ["sseStopTerminatesConnectionWithoutRequiringQueuedEventDelivery","07cfc723394063f3a46dc6ca3d243f27efbd4e594faa7bae24f2f9798e5c4e32",{"controlJoinMillis":24000,"phasePolicy":{"startupMillis":3000,"startupCancellationMillis":1000,"gracefulShutdownMillis":5000,"forcedShutdownMillis":0},"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/StreamingLifecycleTests.java", "8fea091632d561ad48af6a299672d3a722b18b41443e3ff70cbb93cfcb890daf", [
    ["blockedCancelationCallbackCannotStallAnotherStreamsTimeout","71b38d69cb88ecfcc8441e2fcaef60369d7e2fb993e7d195b29b356b52f32799",{"controlJoinMillis":15000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":200,"forcedShutdownMillis":200}}],
    ["blockedSourceFinalizerDoesNotHoldTransportOrShutdownPastDeadline","172fca13a3a92e261f9a76cd87a5686a4534e83f3fc5b0034cfcd0f2eab596ab",{"controlJoinMillis":27000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":200,"forcedShutdownMillis":200}}],
    ["exhaustedLifecycleCapacityRejectsBeforeHeadersWithoutInvokingBodies","227aa97d19ca4565dcf82076e006fbcd9334cd5edd5623dfa52650b72db44504",{"controlJoinMillis":9000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":200,"forcedShutdownMillis":200}}],
    ["inlineCustomExecutorRejectsWithoutEnteringApplicationOrStallingHttp","5d18d8b10597bc8960565d3e29254ea01f139e64d708122f9bb521431aa8e96a",{"controlJoinMillis":6000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":200,"forcedShutdownMillis":200}}],
    ["saturatedCustomProducerExecutorRejectsBeforeStreamingHeaders","2fd1bd632536c33103ac1c5d549402c0f7e1d2f63e5c8d26ec0d3281bc7c6a26",{"controlJoinMillis":9000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":200,"forcedShutdownMillis":200}}],
    ["retainedCleanupPreventsRestartAndClearsOnlyAfterPhysicalExit","400214c12575fafaab4cfbe76d97ffc5395853212c656f29ff9b3e358389eda7",{"controlJoinMillis":17000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":200,"forcedShutdownMillis":200}}],
    ["gracefulShutdownAllowsAdmittedWriterToRenewIdleTimeoutAndComplete","357e7afb0e8d2362303c2151fdd0170ab8131c40962d9c7f0046eebb8154d62d",{"controlJoinMillis":9000,"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":3000,"forcedShutdownMillis":1000},"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/StreamingOutputViewRuntimeTests.java", "e67342f712d5ebf16f66d3504d36949e9a898509f9bdb0048af471214456a564", [
    ["closingOneViewFlushesItAndLeavesOtherOutputUsable","924a042fe4b376e2523e659e5b123eb9beb2862e895e891201d1478a9f425218",{"controlJoinMillis":8000,"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["failureAfterWritingFinalizationBytesCannotReportSuccess","b982fa5ba33c571904769ddd0ca67e1436353b05ae2b52172bb6c8c52a74513a",{"controlJoinMillis":8000,"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["invalidSlicesDoNotPoisonOutputAndEmptySlicesAreAccepted","089728c6b1aa8a70dad1ddaea8eb56c2d855109117233ff16d83facfb54dbfa5",{"controlJoinMillis":8000,"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["mixedOutputKeepsOrderAndCopiesCallerBuffersBeforeReturning","77592c2347e5579a43630321981a0b7c437b96e5bafc95137fdd35151c25aebb",{"controlJoinMillis":8000,"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["ownedUtf8WriterFlushesAndEmitsBufferedTextDuringFinalization","bab937720039e78b3f1bb4f622a2d0fe4ba447485b65a8af076e105347d5cd13",{"controlJoinMillis":8000,"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["ownedZipFinalizationEmitsAReadableCentralDirectory","da5fbe788ea151a7cd87b913834d3081a121f0ab253fe6d3d1fa874860a94d5a",{"controlJoinMillis":8000,"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["viewAndNativeLifetimeChecksPreserveTheResponse","acf3df8de238e85a3f8cea10d9651148d40bbc345dedb767f57f92e15312a175",{"controlJoinMillis":15000,"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/StreamingResourceOwnershipRuntimeTests.java", "ca40e320e414eab27019d94c788f11c5616ed5f93aff32cabc230e217ea586d9", [
    ["cleanupOnlyFailureCannotProduceSuccessfulCompletion","75ba9b1186aec914662968f237db777ffbf0372fb4b9887b544ea82000d2bc5c",{"controlJoinMillis":8000,"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["duplicateActiveOwnershipIsRejectedWithoutDoubleClose","58a30e89069b819bb7aa7bb11a531b9557efc766da26dcf1eeea8310e3ccba3c",{"controlJoinMillis":8000,"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["headSuppressesWriterFactoriesConsumersAndCallbacks","c6e98b0876593a122ee3f722e827898d7ce96b30d7147bc0998ebe347ec60e42",{"controlJoinMillis":5000,"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["nestedUsingClosesItsChildrenBeforeOuterAndRootLifetimesEnd","06f234e19d07e20af721092fa6b2753efa19def3871a6d31dae67ff4cb0a2668",{"controlJoinMillis":8000,"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["ownerChecksAndClosedScopeChecksRunBeforeAcquisition","88f796ea844036ea606713e2efd5a1332c7a233eb2efbe11ba4131c05bd0d6e6",{"controlJoinMillis":12000,"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["producerFailureRemainsPrimaryWhenCleanupAlsoFails","785cc96aa16643b111c8159281bfa40d1fafaf294a2adbff0fac563feac13469",{"controlJoinMillis":8000,"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["rootCleanupRunsInReverseOrderOnOwnerAndCanWriteAfterWriterReturns","afd4e0628db8fdab9855d53e02a85925f02c3a4c9eceeafa068d5411901e57e4",{"controlJoinMillis":8000,"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/StreamingServerSettingsTests.java", "9b74ca7d3fcdd810ca35ab8087c620d59de14177f23cc88aa99f657fa8f39bac", [
    ["publicCleanupTimeoutEndsDeliveryWithoutReleasingABlockedFinalizer","b0725fbb2f3066b77fc6e9f9738b2633bab2fca01b1a4174285ec675f95be610",{"controlJoinMillis":9000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
    ["lifecycleCapacityIsolatesAdmittedObserversAndRetainsExpiredWork","f61ad282c3dcb2ea3b639ec297d36400531b409ce7b013fedb4f775c027145ac",{"controlJoinMillis":12000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/StreamingSourceFactoryRuntimeTests.java", "9306bd9684ba8a22cb5b806a2764ef08e3828497149c521f4578f622aceaaee7", [
    ["checkedAcquisitionFailuresPreserveTheirCause","f0067cedd1fb014777e15d68fcc373d43ea33a6883ad7c3354831e369d0536c5",{"controlJoinMillis":18060,"generation":{"count":4,"mode":"SEQUENTIAL","complete":4,"prior":3,"incomplete":1},"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["checkedSourcesAreLazyAndReopenedForEachExecution","704f48192a274117ac97554f624510a2d711ca10322f63b2f378eba7eee1de0d",{"controlJoinMillis":18060,"generation":{"count":4,"mode":"SEQUENTIAL","complete":4,"prior":3,"incomplete":1},"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["headDoesNotInvokeCheckedFactory","031539ead8407a0e16579d1f04b79a311ad4eb84857fe1f5d0decead9a68060f",{"controlJoinMillis":18060,"generation":{"count":4,"mode":"SEQUENTIAL","complete":4,"prior":3,"incomplete":1},"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["nullSourceIsAProducerFailureInBothRuntimes","0af5c9c436091f9afb12f4c06fdb351c2042efd8466426b21bc2db4179c16395",{"controlJoinMillis":18060,"generation":{"count":4,"mode":"SEQUENTIAL","complete":4,"prior":3,"incomplete":1},"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpHttpServerObservationTerminalRaceTests.java", "970def3ef8f8be9f7b3c1b6dafbec12035b62ce00a2739543e9bf8847979b5fe", [
    ["cleanup_timeout_finishes_request_as_internal_error_without_a_framework_cause","99ca71e3707b6dfe1763fa898464077096fcdfa621c011027911292aeb72fe03",{"controlJoinMillis":15000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
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
  ...reviewedScopeFile("src/test/java/com/soklet/HttpStreamingPassiveDisconnectTests.java", "d9353d4ad401958df5d67feafd6ad1e9c05e52b95ee4f7a62a2a704d62ef11d4", [
    ["ordinaryHttpStreamRetainsPipelinedRequestForDispatchAfterCompletion","650ddc2c9b5cb3b5d9ef3c4ea064a78eb57065498f8dc7080d2539f0ac000608",{"controlJoinMillis":3000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
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
  ...reviewedScopeFile("src/test/java/com/soklet/McpLegacySessionTransportCapacityRuntimeTests.java", "fd8dbefbf833dd8b55ae6b655bf2a67e3afa7bbd538322fbe6d45c19df411ff6", [
    ["temporary_handler_capacity_keeps_get_fenced_and_retries_without_replacing_its_stream","21ac6451e1115e3a9402c6fd6e6339b7c209b13471edd27b1b56341171df0b46",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":140000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","requiredAction":"RAISE_OUTER_BOUND"}],
    ["four_ignoring_renewals_hold_global_maintenance_capacity_after_their_gets_expire","e21582c794c1803de20da23eac54195bfe1d8d81dd6fc0c76580b464b84a4aef",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":140000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","requiredAction":"RAISE_OUTER_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpLegacyCatalogPaginationPublicRuntimeTests.java", "fbbc9f8f8586208ed08c591ebb39fa6c6dc5ce1d7d4ab8e45d801fb200165c8d", [
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
  ...reviewedScopeFile("src/test/java/com/soklet/McpLegacyCompletionPublicRuntimeTests.java", "66eb6303bd2ba052e14d070bbf8987b80f68c6004767c22120434c2ef474b863", [
    ["mixedEndpointRunsOnlyTheSelectedRevisionsCompleter","97942dc69e2fc3bbc67f2300cf6ac5b2278f5d1e70b3bd8622885649c0bdb1d4",{"controlJoinMillis":60000,"requiredAction":"RAISE_OUTER_BOUND"}],
    ["initializationAdvertisesOnlyConfiguredRevisionCompletion","0003920d8e206dfb00e0160d65fefabe41fdccd90a93e92298e9d12e4b4fc315",{"controlJoinMillis":60000,"requiredAction":"RAISE_OUTER_BOUND"}],
    ["visibleTargetsWithoutCompletersAreEmptyAndHiddenTargetsStayNeutral","0e794740fcc169ad33b289be75743063dbcfbf74ff3d5166f1a467ee622ed464",{"controlJoinMillis":65000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","requiredAction":"RAISE_OUTER_BOUND"}],
    ["legacyCompletionEnvelopesAgreeOnTheListenerAndSimulator","ff39c55f7b68cef134ea3c1a857304a11bf03caba20b7f249250f11fde93d57c",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":60000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","requiredAction":"RAISE_OUTER_BOUND"}],
    ["legacyCompletionUsesExistingDeadlineAndCancelationLifecycle","0b193af02caa5761e745c366137b0d804d00ebfb2edee175875bf3aff38661d3",{"controlJoinMillis":75000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","requiredAction":"RAISE_OUTER_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpLegacySessionPublicRuntimeTests.java", "ec7a2db71a8a788ea0e03f48f9a3fea5851acfe1fd4e4deb42e0b9f105a77e9d", [
    ["defaultStatelessServersNeitherIssueNorRequireSessionIds","88151501ff7a7ab8bac6c2f6b6e77fae80d9b67a4076ebec611a833ca385b324",{"controlJoinMillis":60000,"requiredAction":"RAISE_OUTER_BOUND"}],
    ["ownerResolverFailureAndInvalidKeysFailClosedWithoutPublishingIdentifiers","3a09358f22e49acabd07f1ded51d880573f8c8edf529804358e8492b67a1b550",{"generation":{"count":5,"mode":"SEQUENTIAL","complete":5,"prior":4,"incomplete":1},"controlJoinMillis":60000,"requiredAction":"RAISE_OUTER_BOUND"}],
    ["ownerResolutionUsesTheRequestDeadlineAndCannotPublishAfterTimeout","6e08e193d8fdf1babe243855cf35eefb4afa8ee40467e322c8659c04e1506818",{"controlJoinMillis":75000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","requiredAction":"RAISE_OUTER_BOUND"}],
    ["hardSessionExpiryCancelsPhysicalWorkAndCompletesItsUsablePostWithACorrelatedError","17e882acf99ca0acfa5c73ff551de4019938b36fbb07c070625e797556ecc5a8",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":100000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","requiredAction":"RAISE_OUTER_BOUND"}],
    ["corsAllowsAndExposesSessionHeadersOnlyOnExplicitSessionEndpoints","180783acb32d5f8de786e1489d100441e12051329021f69c248d9342c49f3892",{"controlJoinMillis":60000,"requiredAction":"RAISE_OUTER_BOUND"}],
    ["anonymousAllocationRequiresExplicitOptInAndUsesFreshAdmission","aba689d4e804b136b8cff407c7148d468258e5bed1eddf5bb0027daa0f66a684",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":60000,"requiredAction":"RAISE_OUTER_BOUND"}],
    ["ownerAndGlobalCapacityFailuresPreserveSessionsWithoutInventingRetryTimes","c8a2cee25a3551f8e9e84901a49012dbb43e201b2cb646b4102aac1fab1df08e",{"controlJoinMillis":60000,"requiredAction":"RAISE_OUTER_BOUND"}],
    ["oversizedClientSnapshotDoesNotPublishASessionId","b7af15adb394a82ccccc7b0c149ddfa054c38ac4b3f0c366f2288b909438b9b9",{"controlJoinMillis":60000,"requiredAction":"RAISE_OUTER_BOUND"}],
    ["quiescentExpiryReturnsTheSameNeutralUnknownResponseAndAllowsReinitialization","b7be3dcecf62f514e8272385643b51c7e97cd8e693902cf46d1805f71efe734a",{"controlJoinMillis":62000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","requiredAction":"RAISE_OUTER_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpLegacySessionTransportPublicRuntimeTests.java", "3f20c67104f7933e75cfe3371921d9a89614f4daba67e63d28d2ca070e3f40ca", [
    ["methodMatrixRespectsExactRevisionConfigurationAndDoesNotFabricateRpcAdmission","21434706bed90365df21e68be3bf4278d89e02a051560e7a7db40b060750beae",{"controlJoinMillis":60000,"requiredAction":"RAISE_OUTER_BOUND"}],
    ["absenceOfControllerPreservesPostOnlySessions","21116fd674b32ff30819c68c80a9256fcf3431bad7620aaee8d0c68fdd17d35c",{"controlJoinMillis":60000,"requiredAction":"RAISE_OUTER_BOUND"}],
    ["oauthChallengesAreApplicationSelectedAndDoNotRenderJsonRpcErrors","d3c01853ae38296b1acf5f2be5a923aab176d30a533ce53bc4135e11e3ff8687",{"controlJoinMillis":60000,"requiredAction":"RAISE_OUTER_BOUND"}],
    ["expiredAndInvalidSelectionsFailWithoutCreatingGetOrDeletingSession","590c928d82ed77a31200abf7def200178500eb9425dbf7b5df975d3d038e89e8",{"controlJoinMillis":60000,"requiredAction":"RAISE_OUTER_BOUND"}],
    ["getUsesBoundedFreshRenewalAndPhysicalHttpObservationWithoutRpcStreamMetrics","29eabe0b21604968c92d9803a5d5fac39ebf0fd175d2397af6d5f82eccc4d5c0",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":90000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","requiredAction":"RAISE_OUTER_BOUND"}],
    ["reconciliationDenialAndOwnerChangeCloseOnlyTheGetAndCannotResurrectIt","f19ba58f408a59a78783a8b092f4683eadd999f77ab94199c609eca6ca4f29b4",{"generation":{"count":4,"mode":"SEQUENTIAL","complete":4,"prior":3,"incomplete":1},"controlJoinMillis":100000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","requiredAction":"RAISE_OUTER_BOUND"}],
    ["deleteEndsAnExistingGetAndShutdownBalancesMetricsAndDiagnostics","7b5d164f741b2da532cb1dc3f8ca0860510b702d72f5320c0b98cb60187fe1a1",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":90000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","requiredAction":"RAISE_OUTER_BOUND"}],
    ["slowReauthorizationCannotDeliverPastLeaseAndRetainsPhysicalReservationUntilExit","1bbc1a45618a92755276821c77841ddb107098a667805129a57b9a1fad8d5d76",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":90000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","requiredAction":"RAISE_OUTER_BOUND"}],
    ["sameSessionReplacementTransfersQuotaAndCapacityDenialPreservesTheExistingGet","74bc8882866b8b8e5f78d0ad9e03b664f4e5dec6e16536e3e26dd911a3cec441",{"generation":{"count":4,"mode":"SEQUENTIAL","complete":4,"prior":3,"incomplete":1},"controlJoinMillis":80000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","requiredAction":"RAISE_OUTER_BOUND"}],
    ["corsPreflightOffersOnlyConfiguredHttpFacilitiesAndDoesNotAdmitSessions","72a51ad8f1ec31d078ad402b52a70c5c68c39459ff367f4a16a629f8f8a5f322",{"controlJoinMillis":30000,"requiredAction":"RAISE_OUTER_BOUND"}],
    ["authorizationExpiryIsRecheckedAfterOwnerResolutionBeforeGetOrDeleteMutation","3b67aec5401cc809397233226097703c735e244fbd88263f1c8e4fa900737253",{"controlJoinMillis":61000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","requiredAction":"RAISE_OUTER_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpLegacySessionTransportSimulatorTests.java", "e174ffad2a107f18f34da76b9950b6690fe3b5e24d3c693ed2049403534a53b6", [
    ["publishedBeforeAckSessionGetThenDeleteCompletesZeroMessageSseWithExactReason","73846a42deaf51247455f6fd8bd584e8327701575e957d52bc1c8e40f975ad03",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":80000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","requiredAction":"RAISE_OUTER_BOUND"}],
    ["simulatorDisconnectEndsOnlyTheGetAndModernSelectionCannotMutateTheSession","c565bb47800b6d40704ac5d8760f05f06047f6286f9102f15422371a4c777a87",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":80000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","requiredAction":"RAISE_OUTER_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpLegacySubscriptionPublicRuntimeTests.java", "b5e7d6ae431779f9a9a94b3ef522697364631a12801693c8c21f8b1b3de06660", [
    ["simulatorAdmissionExposesValidatedSubscribeSelectionAndUnsubscribeOnlyItsOperationName","181565f33b8fc0ff02fefc32cb963e56120fc3b1cbb8d0bf6baeebc8ef7f9577",{"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":90000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","requiredAction":"RAISE_OUTER_BOUND"}],
    ["invalidUriRequiresAdmissionAndSessionValidationAndMissingOrDeniedReadableRoutesAreNeutral","bd516d0ee9b01a0eda870d84e0d102d550519e70ce47234418d89878329b57a1",{"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":90000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","requiredAction":"RAISE_OUTER_BOUND"}],
    ["liveAdmissionCanChallengeBeforeResourceAuthorizationAndInitializationAdvertisesExactFamilies","c202917ce4d0d5f942a85bc0ced2328c54eb61d8245f7317abe90a47063cb1bb",{"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":90000,"requiredAction":"RAISE_OUTER_BOUND"}],
    ["liveDuplicateTemplateUnsubscribeAndDisconnectedGapPreserveOnlyCurrentUriGrant","26b97596635b416b62d84cbc417ea83a797a6b4df6b8391c040631a0ef39ada3",{"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":90000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","requiredAction":"RAISE_OUTER_BOUND"}],
    ["simulatorGetCallerSubsetCannotDeliverOtherFamiliesOrUnsubscribedUrisAndModernIsIndependent","db630d5c085c508c6a3f23f34c967168d118f5e967bd596c6e252b0426352965",{"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":90000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","requiredAction":"RAISE_OUTER_BOUND"}],
    ["callerPolicyAndCustomResourceCatalogHintsAreCoarseAndRearmOnlyAfterAnAdmittedList","0a6e81dfe31cc5b5535ef7c4128c969fbc972250011071f521a4abf193a11a02",{"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":90000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","requiredAction":"RAISE_OUTER_BOUND"}],
    ["immutableCallerIndependentCatalogHintsAreSuppressedButUriUpdatesRemainAvailable","9f40868ee41addfebc534e4dbf3e83f0d2d2adbd671249fc80cb5d380b57f102",{"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":90000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","requiredAction":"RAISE_OUTER_BOUND"}],
    ["localizationInvalidationWorksWithoutCallerPolicyAndIgnoresModernOnlyLocalizedOwners","713cad313f609460fac9ba86217fb28439c6f39f847515a9b3df4596056b774e",{"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"generation":{"count":3,"mode":"SEQUENTIAL","complete":3,"prior":2,"incomplete":1},"controlJoinMillis":90000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","requiredAction":"RAISE_OUTER_BOUND"}],
    ["modernOnlyLocalizationCannotMakeAnImmutableLegacyCatalogEmitHints","863f638c0a087a7ea9d6237dca527a9bb5cd2d054c8cc6c4167254fd62f9e6a6",{"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":90000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","requiredAction":"RAISE_OUTER_BOUND"}],
    ["reconciliationDuringEstablishmentDiscardsStaleDenialAndUsesFreshCancellationLease","343cac94ef7baef0d3a882fe49dad2a595076e40c253062dbd9e52c9b78924a1",{"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":90000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","requiredAction":"RAISE_OUTER_BOUND"}],
    ["subscribeUsesOrdinaryQuotaWhileVerifiedUnsubscribeCanReleaseTheEstablishedGrant","8fff86ef17e2133fc502a058332c710f507e250070366e9eb311d6ddc264eb4c",{"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":90000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","requiredAction":"RAISE_OUTER_BOUND"}],
    ["equivalentUriSpellingsSharePublisherMatchingAndUnsubscribeIdentity","d0507f28cf236e68a3cf80f864dd66bde65f20f776e512a835829b55f93bf47a",{"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":90000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","requiredAction":"RAISE_OUTER_BOUND"}],
    ["delayedGrantFenceCannotCancelRenewalStartedUnderThatFencedGeneration","f6b709794d1d36c2a49cd763a20c0abb73c1d862f5be48467d379c8b44c2c845",{"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":140000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","requiredAction":"RAISE_OUTER_BOUND"}],
    ["delayedGetFenceCannotCancelRenewalStartedUnderThatFencedGeneration","5d54e3408ed0017b911e16a6743172a545bf03e7ae1362056c1de2e0fccbbf80",{"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":140000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","requiredAction":"RAISE_OUTER_BOUND"}],
    ["refreshedBearerGetDoesNotRefreshUriEvidenceAndRevocationRetiresBothPermissions","3e019beba11740d84b6cacce63084e98eadcf48852a1bd8d7df1453958f99a14",{"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":90000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","requiredAction":"RAISE_OUTER_BOUND"}],
    ["reconciliationDispatchesShortGetLeaseBeforeSlowLongLivedUriRenewals","7188373163685b35c23368347384e566b0971da9f7a14513df32a381661d53f1",{"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":90000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","requiredAction":"RAISE_OUTER_BOUND"}],
    ["reconciliationDispatchesShortUriLeaseBeforeSlowLongLivedUriRenewals","ea04d842ddac0ba2799b83724de4394c459699ba4c47cfdf86280f65c787ec3e",{"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":90000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","requiredAction":"RAISE_OUTER_BOUND"}],
    ["detachedGrantDenialRetiresItsSessionAndRequiresReinitialization","5490406a03884cac6758d51f243b1b845278ac719168f880f7e9eb431c725233",{"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":90000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","requiredAction":"RAISE_OUTER_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpHttpServerObservationTerminalRaceTests.java", "970def3ef8f8be9f7b3c1b6dafbec12035b62ce00a2739543e9bf8847979b5fe", [
    ["legacy_sse_handoff_failure_detaches_delivery_without_refunding_running_work","2c3c885addc64be236f93d5f5dea7339c34383e0e880e96ae0113bcd9c059a8d",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":40000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","requiredAction":"RAISE_OUTER_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpHttpServerRequestScopedSseTests.java", "27493b08e8de3ab5db28f52c2f7a8ece54ca336f2b4702a49a832b5a4385d398", [
    ["reset_before_http_offer_cancels_an_allocated_stream_in_every_revision","848b4365f2ac29f9b4247478659656e0e201f7c59e179d41fcd80d93dcf49aae",{"generation":{"count":3,"mode":"SEQUENTIAL","complete":3,"prior":2,"incomplete":1},"controlJoinMillis":90000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","requiredAction":"RAISE_OUTER_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpLegacyCatalogPaginationBudgetTests.java", "01a0aca68e1406991834356e0eefbf8c37e0436fa8c711412c3e89b93d4920be", [
    ["jsonNodeBudgetIndependentlyPagesEveryLegacyStaticCatalog","4a0d427ce71bb6ad0c62eb9b7ffe53c74b8255a53dd73d13bce999f2550e4d5f",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":60000,"requiredAction":"RAISE_OUTER_BOUND"}],
    ["byteBudgetIndependentlyPagesEveryLegacyStaticCatalog","a57d1e7e3ebd1eb10d2d7338c00321cf0f35b813b2c80ebbf47f5b7e8d8672d2",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":60000,"requiredAction":"RAISE_OUTER_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpLegacyProgressPublicRuntimeTests.java", "6a8345bfa9965dbfa5c93dd63fb2cb02ae994a20a4016223de1c773f86162b38", [
    ["firstProgressPrecedesHandlerCompletionAndPreservesExactMonotonicUpdates","0bfbdf6bf83723301e180489e3fa562c77e29982bee8f3e0646d505b2c1a57c1",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":100000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","requiredAction":"RAISE_OUTER_BOUND"}],
    ["simulatorPublishesProgressBeforeTerminalAndDuplicatesTheExactTerminalMessage","7e9106bf9a2205e308efacea61ccbe2ecfe354d502940d0650ac948ac7b7f5f6",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":100000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","requiredAction":"RAISE_OUTER_BOUND"}],
    ["noReportsStayJsonAndMissingOrMalformedTokensDoNotStartProgress","90b0662a786cb34da2187a1af125566ad98d30a4c9f837ae3826aff0a9bc1fce",{"controlJoinMillis":100000,"requiredAction":"RAISE_OUTER_BOUND"}],
    ["legacyJsonAndSseTerminalProjectionsAgreeForPromptsResourcesAndCompletion","46341a437c066d850478e307db1bcfa527ebaa1bb09bf6cb9510d096644858e0",{"controlJoinMillis":100000,"requiredAction":"RAISE_OUTER_BOUND"}],
    ["intentionalAndUnexpectedErrorsUseJsonBeforeProgressAndSseAfterIt","fe818b101532439fa6a8807326f607d8b6fe3dabe541acc22ca9a82e6e5d70f8",{"controlJoinMillis":100000,"requiredAction":"RAISE_OUTER_BOUND"}],
    ["committedDisconnectDetachesTheWriterAndRetainsUncanceledPhysicalWork","c381dc78f53ff5a3cf1d2c1bc84751ee69e92f8d60c723d437d21eaedb30999b",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":100000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","requiredAction":"RAISE_OUTER_BOUND"}],
    ["finiteAndUncommittedDisconnectsStillCancelTheHandler","04c6cb7cacd0110edac2c78526b87080f742941e4de169aed0ccfc77046ac8f9",{"generation":{"count":4,"mode":"SEQUENTIAL","complete":4,"prior":3,"incomplete":1},"controlJoinMillis":100000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","requiredAction":"RAISE_OUTER_BOUND"}],
    ["detachedProgressWorkStillObservesItsDeadlineAndRetainsCapacityUntilPhysicalExit","54e416999afa50f3a881d11352ed9f061926d0e6619218415358c50b3ff04dc5",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":100000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","requiredAction":"RAISE_OUTER_BOUND"}],
    ["simulatorDisconnectUsesTheSameCommittedVersusFiniteRule","4c016b557e409054a8f15e77e6b300e95d65a2a79fb76a0162bba50e3e87ee99",{"generation":{"count":4,"mode":"SEQUENTIAL","complete":4,"prior":3,"incomplete":1},"controlJoinMillis":100000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","requiredAction":"RAISE_OUTER_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpLegacySessionCancellationReservationTests.java", "33918a09b299c6b73088afd47d0b6a42245c149a6ae4a59f1c7673c9fd9e3e1f", [
    ["canceled_body_that_never_starts_has_a_physical_deadline_and_preserves_its_token_reason","df81749981c2af71384dc3c128c7bba5195c2c379868e54176f327ab2445504b",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":50000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","requiredAction":"RAISE_OUTER_BOUND"}],
  ]),
], 'legacy expansion scope');

// Follow-up review: exact local budgets, foreground releases and per-node
// cardinalities. Every allowance remains bound to the full callable and file.
const REVIEWED_VERIFICATION_FOLLOWUP_SCOPE_OVERRIDES = checkedReviewMap([
  // Two sequential boolean cases, each with one explicit 1/1/1/1-second simulator.
  ...reviewedScopeFile("src/test/java/com/soklet/ResponseProcessingCleanupTests.java", "6a0e44da381931ca81cd8c47bd8bad35a6632adbac95047c42e4c1d1efb7888e", [
    ["interceptorFailureReleasesGeneratedAndSubmittedOwnedBodies","3bb050ef7f38468680e45f0a417833cdbf162f1a252004121914c6c318120106",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["interceptorCanSelectAnEarlierGeneratedBodyOrBorrowItsChannel","d398fe4c33682a0b9ad1cc488503a1b38b24cb3216c940eb180df80dad19bd91",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
  ]),
  // One shared 60-second request deadline; two sequential failure-policy owners or one pinning owner, each with 5/2/2/1-second phases.
  ...reviewedScopeFile("src/test/java/com/soklet/McpLegacyCatalogPaginationPublicRuntimeTests.java", "fbbc9f8f8586208ed08c591ebb39fa6c6dc5ce1d7d4ab8e45d801fb200165c8d", [
    ["oneOversizedLocalizedDescriptorHonorsTheFailurePolicyAndKeepsContinuationUsable","4b7e2085044052dce5d1ee4ddce69a145edaf1e78190f1950c7850dfb40d20ef",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":60000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","requiredAction":"RAISE_OUTER_BOUND"}],
    ["blockingLocalizationCallbacksDoNotPinVirtualThreadCarriers","a45bb89a0ab59476d13eed589eaf0830fcfda1463b25cc7046d7ae1304c38358",{"controlJoinMillis":60000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","requiredAction":"RAISE_OUTER_BOUND"}],
  ]),
  // One 5/2/2/1-second fixture; three valid probes at 6 seconds each, or six malformed probes at 4 seconds each.
  ...reviewedScopeFile("src/test/java/com/soklet/SseLastEventIdTests.java", "f0b4675758cbcdcf421eb485a7e41e71b58b973ec5d3423ea6359fb92b29cc42", [
    ["utf8LastEventIdsReachTheResourceIncludingSupplementaryCharacters","be7414d86359aebd871db747be78bd1d58da2cf7760b2698fab7c52eafe96bbd",{"controlJoinMillis":18000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["malformedUtf8LastEventIdsGet400WithoutReachingTheResource","4d86449f7bbd71a392e9c22e0af9b030a34c176cb7932ee45a25510425234c1f",{"controlJoinMillis":24000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/CombinedTransportShutdownTests.java", "22d296ad150b766b3ca3f2359dbb0744eed450d14792ef2010d209aea5f6636f", [
    ["gracefulShutdownClosesSseAndDrainsFiniteHttpAndMcpWithOneDeadline","c9912b52d5b4402e0c5034947929bb2684e6578097cb71d0ff840fd4826c2859",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":3000,"forcedShutdownMillis":300},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":57000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","requiredAction":"RAISE_OUTER_BOUND"}],
    ["forcedShutdownRetainsAllBlockedWorkersAndLateExitCannotRewriteFrozenResult","773de145aa05cf3518fe0875a96ee065f58855b50a708fe338fdc8b84e35412e",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":300,"forcedShutdownMillis":300},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":59000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","requiredAction":"RAISE_OUTER_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/EmptyVarargsBindingTests.java", "1b7e338eee2cc5500cde2369faf50d1c653492e25e380be656c48ed22ec925e2", [
    ["explicitConverterMayRejectEmptySuffix","0ff3eb51c3c34e7d60dfe4d98610fec90e6190f33ce63f530617b8adf9e7870d",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/external/HttpStreamGracefulShutdownTests.java", "e711733a5084021c0bf71782ee276d151669b01b4291dfd3ff158836dcb249a7", [
    ["liveFeedFinishesItsOutputAndFinalizerWhileAFiniteDownloadKeepsDraining","dc950e8442b185a6f5c96d257620270d890607bb5794703314d0b94426563aaf",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":3000,"forcedShutdownMillis":1000},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":10000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["simulatorRequestsGracefulCompletionAndCapturesFinalOutputNormally","58626325013c6d5b0a7a4a89e866fe9facd0f40009f450fdd304a4d55451e6df",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":3000,"forcedShutdownMillis":1000},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":7000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["completedAndReusedFiniteBodyExecutionsStayUnrequested","87272c73b30222f6f8f6af23e54e7f4c7ec642716b2d0844153390db84a054d6",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":3000,"forcedShutdownMillis":1000},"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/FramingTransportTests.java", "833574c35cf99f738e9b33e573e428af3016de964ca30a10690c51adcb9847d5", [
    ["faultyHttpFramingClosesBeforeAnyHandlerOrFollowingPipelineRequestRuns","8dea71d41ec69995362ce379bd70809f2d3fa616a3c4d425003235b7f9d5347f",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":14000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","phasePolicy":{"forcedShutdownMillis":1000,"gracefulShutdownMillis":1000,"startupCancellationMillis":2000,"startupMillis":5000}}],
    ["validChunkExtensionsAndHttp10ContentLengthReachTheHandler","30bf2f76b243c382d60f79302e296c38d4b885f35466af78d9c37ce56e5add6a",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":4000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","phasePolicy":{"forcedShutdownMillis":1000,"gracefulShutdownMillis":1000,"startupCancellationMillis":2000,"startupMillis":5000}}],
    ["absoluteAuthorityAndEmptyNamesReachLiveHttpHandlers","4ba2f58752fd3e12ab45a39c15c3091d687d66f19e99b74a5a95529c9f6a631c",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":8000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","phasePolicy":{"forcedShutdownMillis":1000,"gracefulShutdownMillis":1000,"startupCancellationMillis":2000,"startupMillis":5000}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/HeadFallbackObservationTests.java", "428348c6f7d852291734d5619af00b210d3e1a80940ba27b9abf5259233939e6", [
    ["missingHeadAndNonHeadVerbRemainUnmatched","4134e32781ec4ef0388cd4f11f584042ad7d47daf6a57e29c71cb4e20d073029",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/HttpHandlerDispatchAndDrainTests.java", "7111bbf8d8e905a82ab8d698597cd3d00fc6067a5e88b5232b51f6576e170748", [
    ["directExecutorIsRejectedBeforeApplicationEntryAndCanRecover","bdaa582fe72cd3450475f2ea43c859137a67e6ca1c40aadace75e2843d44cf93",{"phasePolicy":{"startupMillis":2000,"startupCancellationMillis":1000,"gracefulShutdownMillis":4000,"forcedShutdownMillis":200},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":6000}],
    ["saturatedCallerRunsDoesNotEnterAnotherHandlerOnTheSelector","367da67035ad10c3094938e392db58fe1f5f78bb8b27318ffabac2f44bc3be23",{"phasePolicy":{"startupMillis":2000,"startupCancellationMillis":1000,"gracefulShutdownMillis":4000,"forcedShutdownMillis":200},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":12000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["ordinaryExecutorRejectionStillReturnsUnavailableAndRecovers","e978a2d16c8d8b5b2c95d42471e0423d6eb7200f5312cb5b79380bdf5984875b",{"phasePolicy":{"startupMillis":2000,"startupCancellationMillis":1000,"gracefulShutdownMillis":4000,"forcedShutdownMillis":200},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":12000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["selectorInterruptDoesNotLeakIntoFollowingRequests","95d8e552e6636402412b6ba12a2b2f058ae647dc79d9001f69a28388d59f2ce8",{"phasePolicy":{"startupMillis":2000,"startupCancellationMillis":1000,"gracefulShutdownMillis":4000,"forcedShutdownMillis":200},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":6000}],
    ["activeHandlerDeadlineRemainsEffectiveDuringGracefulDrain","c26a054a065c0ca0fc20264498227a00e78c19b968a9852c2e7b6182ec635f28",{"phasePolicy":{"startupMillis":2000,"startupCancellationMillis":1000,"gracefulShutdownMillis":4000,"forcedShutdownMillis":200},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":12000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["queuedDeadlineExpiresWithoutApplicationEntryWhileResidualHandlerDrains","ebdcaaa0c3aaa51ac96b04f0b6ac9bbabc6fd018af78a5a9b4dad5b5f4d7d095",{"phasePolicy":{"startupMillis":2000,"startupCancellationMillis":1000,"gracefulShutdownMillis":4000,"forcedShutdownMillis":200},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":16000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["successfulDrainCancelsItsDeadlineAndRetiresTheScheduler","5f6b480d755bbdad5dd5baba7a217fb855cad7bc3f23ccedd8e2355c0e485f62",{"phasePolicy":{"startupMillis":2000,"startupCancellationMillis":1000,"gracefulShutdownMillis":4000,"forcedShutdownMillis":200},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":10000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["forcedShutdownStopsTheSchedulerButRetainsRealHandlerResidualEvidence","9fdd1f170e99e0c883805397f3be2e33369414f952a35c9344b513b8f68881eb",{"phasePolicy":{"startupMillis":2000,"startupCancellationMillis":1000,"gracefulShutdownMillis":100,"forcedShutdownMillis":200},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":14000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/HttpResponseStreamTerminalRuntimeTests.java", "f74d8bdb27240b8caeaba029ba4a3e5e87a1d7726fec21ad9088a1567ba749f4", [
    ["earlyCompletionWaitsForMetricsHandoffAndExcludesObserverDelay","12292bbd578ddfa2d999dbef4f559468f6409acf6b75de10394f87081cf382b9",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":17000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
    ["metricsCompleteBeforeBlockedLifecycleObserver","2b062a0fdf69d16b02e71999541db5c55c40350b3d432d440f4c1dd0e1958295",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":17000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
    ["producerFailureCountsOnlyPayloadAlreadyWrittenAndKeepsCommittedStatus","9291efea6dae0c252b3ab64721c602c1edbb19e6a6d334bff0e6fee7669bf5e4",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":12000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
    ["timeoutTerminatesAnAdmittedStreamAndRecordsZeroUnwrittenBytes","89b727d2d7b242b6e02e5712e7d38b7c6ab1dbd69bf6be7d0032fb9e7aa1d2fa",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":17000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
    ["disconnectTerminatesTheAdmittedStream","2a2532c905d276b45f3fa7b7ffe3ff36bc6b5ae19927d57c449eab575c4a7634",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":17000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
    ["http10AndHeadRemainFiniteAndDoNotCountOriginalStreams","d046c3fd65d7fa2e39a7f0d34353601a174dcfff91fa652d8da534c6c0b0a715",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":14000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["throwingMetricsCallbackIsLoggedAndLifecycleStillRunsOnce","7e2dab6b82ee6ac8281dcada6b7f118b42dcd1de2a40bc7b2dc30bb5751fd2d0",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":7000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["simulatorCountsSuccessAndAcceptedPrefixBeforeFailure","afb3f75d6802c49cf76a5caba13655543aca5cc8f6ebf68660bfe62ee08d9a3c",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":0,"controlComposition":"REVIEWED_NONBLOCKING_PRECONDITION"}],
    ["simulatorLimitKeepsTheAcceptedPrefixInMetrics","e38327ba6c7fc98a1fa9ff8a3c3df1cadcc388f35563946bf0e71139e7ecab2f",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":0,"controlComposition":"REVIEWED_NONBLOCKING_PRECONDITION"}],
    ["capacityRejectionRecordsFinite503WithoutAStreamTerminalMetric","cd7b2c080b92abfeb6dc92e3214e09522af06215090994cf263e12d13f4c504e",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":24000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
    ["forcedServerStopTerminatesAdmittedStreamMetrics","49f97c7488ac01aa2c4602d157f3335c6815df623cee026f910e370851a403c2",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":0,"forcedShutdownMillis":2000},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":25000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/HttpStreamingProtocolRejectionTests.java", "71a37065b172ca04cee6286f710feee51081706c4181bf1f06e407082567137c", [
    ["replacementIsReportedEvenWhenTheLogicalStreamAlreadyHasStatus505","8e58733dd3c1ddf6ecd7f844a79fcf6a6e34086c0c3de0c8b40a6f914473ed4f",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":17000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","phasePolicy":{"forcedShutdownMillis":1000,"gracefulShutdownMillis":1000,"startupCancellationMillis":2000,"startupMillis":30000}}],
    ["rejectedFiniteResponseAndRequestFinishDoNotWaitForTerminationObserver","f783fe99830fd39c237fc6da0e0d68eceb40abc261fecc275766ab576cb6fe7f",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":20000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","phasePolicy":{"forcedShutdownMillis":1000,"gracefulShutdownMillis":1000,"startupCancellationMillis":2000,"startupMillis":30000}}],
    ["bufferedHttpOneDotZeroResponseStillWorks","7af9edbf8533a14f80e0d9526e7d54e7a099e96e432e448e039a8646fe0a4ba9",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":11000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","phasePolicy":{"forcedShutdownMillis":1000,"gracefulShutdownMillis":1000,"startupCancellationMillis":2000,"startupMillis":30000}}],
    ["httpOneDotZeroHeadUsesNormalBodyOmissionWithoutStartingProducer","4341e2d6174d1af18aa7443001a15cdd9a3a42616770bff6943c913a7d7c1e42",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":11000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","phasePolicy":{"forcedShutdownMillis":1000,"gracefulShutdownMillis":1000,"startupCancellationMillis":2000,"startupMillis":30000}}],
    ["httpOneDotOneStillStartsAndCompletesTheStreamingProducer","d874be5dd208a72c7b0206478630ca2becf282df52fe0afc4e873c571adfc311",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":14000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","phasePolicy":{"forcedShutdownMillis":1000,"gracefulShutdownMillis":1000,"startupCancellationMillis":2000,"startupMillis":30000}}],
    ["httpOneDotZeroRejectionReportsActualResponseAndRetainsOriginalStreamHandle","6138e199cf69d5e4a19be6e24b6e694fbf5c94e69a7598322517ae099c4b15bd",{"generation":{"count":4,"mode":"SEQUENTIAL","complete":4,"prior":3,"incomplete":1},"controlJoinMillis":68000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","phasePolicy":{"forcedShutdownMillis":1000,"gracefulShutdownMillis":1000,"startupCancellationMillis":2000,"startupMillis":30000},"requiredAction":"RAISE_OUTER_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpLegacyRpcStatusTests.java", "8fab1597b3347205b9c1ad1d23a486995158f186589a40b9a54060f98d9f9070", [
    ["legacyOperationErrorsUseHttp200WithAndWithoutSessions","c11e459b661f5089a9a7b13e40b2d20f33d4a19f7aa1e047b70dfcafb4c68c91",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000}}],
    ["legacyIconProjectionPreservesCompletedResultsAndRunsTheHandlerOnce","a127663de11635be3a92416d2132493a9aaae030bbf435aa062eb1d4e9d74117",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000}}],
  ]),
  // Nine sequential caller waits/joins, including the off-loop finish latch, total
  // at most 45 s per node. The handler's open wait and observer's release wait
  // overlap those same caller checkpoints; the existing 55 s control allowance
  // covers them without adding independent callback waits or changing the 60 s guard.
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpSimulationResponseOrderingTests.java", "f4b0ed4d18f816b09eabbfc7eda0180c0578ec4ea01fe54b39941495d347fd18", [
    ["terminalResultWaitsForTheProgressResponseHead","3df28110665d1deb14c9d32aaae08f6b3a0fa486ca1bc2597c08585856fca81b",{"dynamicNodeCount":3,"controlledLifecycleCoreMillis":5000,"controlJoinMillis":55000,"controlComposition":"REVIEWED_DYNAMIC_NODE_MAX"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpSubscriptionAuthorizationPublicRuntimeTests.java", "0926904d5dc0fcf8bb2b1f374008c12886aabd28f3e975d80642d5ed34a5a243", [
    ["renewalDenialCompletesTheListenAndPreservesItsReason","44b7d7254e0f3d0d46c9d09e37581395c4e2fd904d6cde13003271e14c4166f9",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":1000},"controlJoinMillis":30000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["reconciliationDenialCompletesTheListenAndPreservesItsReason","4f2cbaf5059133a3ce56da40485c2b100b0aa3499eb7ef858852324436018268",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":1000},"controlJoinMillis":30000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["renewalFailureCompletesTheListenWithoutDisclosingTheException","37705ac22a66283c39427084121543ea4dda4af51d12f18be2ccab6d2ee3e7b5",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":1000},"controlJoinMillis":30000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["reconciliationFailureCompletesTheListenWithoutDisclosingTheException","579d4053275f7ff159182184013eeaaa4167ecbd00b3cd90cf760a9ce7e3daeb",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":1000},"controlJoinMillis":30000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpSubscriptionDeadlineWraparoundTests.java", "e0b11cc7a4c65a9324eede5c482e404e8c1ec765ed24cd212cf661fd203c7648", [
    ["queuedReconciliationReceivesFreshCallbackBudgetAtDispatch","abc9ddde9605a66bae53e5ae4ab4893cf224427c36ab941d817b8f736a6aeb55",{"controlledLifecycleCoreMillis":5000}],
    ["queuedReconciliationCannotExtendAnExpiredLease","4cb659ad3e67bf7ac6b57239b1d079d9d50f7d06001063801b7d61bb0f4e492d",{"controlledLifecycleCoreMillis":5000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpTaskSubscriptionPublicRuntimeTests.java", "a09755839331e1ca5206406719f7e0e9b9be48f615de0526e8dbe8dadcd2b3a8", [
    ["acknowledgedTasksSurviveTransientMaintenanceLookupAndUnsupportedInputGenerations","8d58216ed69f499dd809b421bdef500713fa72446df2eee75b1d9595b4ba0b91",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":40000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["lateTaskOfferSuppressesExpiredProjectionWithoutAbsorbingTerminal","fe2121c80435872f6cb130f20cacee89d3d7c2a1d4b949dce81455edf1f0919a",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":15350,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
    ["timedOutTaskPolicyRetainsOwnerUntilPhysicalExitAndAllowsPeerProgress","6100fd844f7ac07d148517aacf5020828cac67800e116e8e72df1817fe4e6c29",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":20000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
    ["taskResultFencedBeforeOfferIsReprojectedWithReplacementPolicyContext","a0825760294f130de56b73718f4cc1e62c279f5d44a66d1617d7f5715a7c2898",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":20000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpTransportMetricsEventRuntimeTests.java", "bf7438b46b521d74e9046c26b849dcc891316dac8708602711c4d586a5e2369e", [
    ["subscriptionMaintenanceNeverDrainsOnTheProjectionThread","7ce5495557bf485dcde6b7aeb200203d91f53ab44bd4724cf90bc1e1823c26ca",{"controlledLifecycleCoreMillis":0}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpWriteIdlePublicObservationTests.java", "34ca609c1b597158a90bb3c31cc400644bd0c17da5d0add1fc22ef4b9b1d0ed6", [
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
  ...reviewedScopeFile("src/test/java/com/soklet/McpAdmissionOrderingPublicRuntimeTests.java", "d516a1be6181f27a79ed0351a205e775a8fbc5eb155b15f8960198f86a47852f", [
    ["unsupportedVersionsCannotProbeRegisteredCustomHeaders","4cca94994eb6ca1ded9f80a54915517b3d861b2a4504b1c5e32b15b479b2e28b",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpClientMetadataAndErrorTests.java", "3e93aac1146d683f2f5dc1d52cfcc974bfbdfccfb4796d248aa105123403b286", [
    ["blankClientInformationSurvivesAdmissionAndRememberedSessionMetadata","754aee9ad0d118d74f18bb5b1d14d67e881413ddb372d470b2794d55497594ac",{"generation":{"count":5,"mode":"SEQUENTIAL","complete":5,"prior":4,"incomplete":1},"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":90000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["sessionCallsAcceptBodiesLargerThanThePersistentEvidenceBudget","06c22bf20d6a9423cb097a8041be9199cd095f92fcc63cdf055889845080abaa",{"generation":{"count":4,"mode":"SEQUENTIAL","complete":4,"prior":3,"incomplete":1},"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":80000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["missingOrNonstringClientInformationRemainsAProtocolError","3ac52eff1b94d20b865fc9b7a0f25241328e3849dbe4c33de539aa57c1220836",{"generation":{"count":3,"mode":"SEQUENTIAL","complete":3,"prior":2,"incomplete":1},"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":120000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpCompletionPublicRuntimeTests.java", "b0ad07d430d5afc8af6cd9414ea046c6c4485fbc02c863802193b05348d34993", [
    ["onlyCompletionHandlerErrorsAreClientVisible","51570aa5f0730ec2353ac8462b3a6bfca47f87d282f2e114fb192b3852b9fc5c",{"generation":{"count":6,"mode":"SEQUENTIAL","complete":6,"prior":5,"incomplete":1},"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":30000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpContentValueBoundaryPublicRuntimeTests.java", "cb62610a4806a0fcc7a37f38c8b042a3ecdeb7657eca1ff7b3b324b33a9570bc", [
    ["contentBoundariesFailPrivatelyAndRecoverForEveryRevision","636e576d012ae7ded4d14123e52bf229ccadf5068f324536bce1bc59b5fc8271",{"dynamicNodeCount":3}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpHandlerExecutorPublicRuntimeTests.java", "4c151cf27d0b1d3274443f7dba1f878ed5e7d311813ffff7e71a8dfdb835d5a5", [
    ["executorBoundariesForEachRevision","6a4fa0a44cb843679eacf13904b13f49f7bd5146d99bb9263675780cdaedae3d",{"dynamicNodeCount":18,"controlJoinMillis":25000,"controlComposition":"REVIEWED_DYNAMIC_NODE_MAX"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpHandlerInterceptionPublicRuntimeTests.java", "963799edb19b125f7c92c50808e697c0ba4571ccf4cdbcb16733342bc172612d", [
    ["interceptorsObserveAndRethrowExactHandlerErrorsOnEveryRevision","2fe9cb5c954424e0915585b8762d547df8aaa430291702aee0e6871c33a60206",{"generation":{"count":3,"mode":"SEQUENTIAL","complete":3,"prior":2,"incomplete":1},"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":110000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["interceptorCanRecoverHandlerErrorsThroughNormalResultValidation","0de6db19ded316a10adf98ce6431d7329ec6558db270afd571b1f007ebfb54a3",{"generation":{"count":3,"mode":"SEQUENTIAL","complete":3,"prior":2,"incomplete":1},"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":110000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["copiedWrappedStaleAndInterceptorAuthoredErrorsStayPrivate","9ca7efa1f3e7c16882f5f206caafc5364a4979c724f6416e1fe49554ea26fc96",{"generation":{"count":3,"mode":"SEQUENTIAL","complete":3,"prior":2,"incomplete":1},"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":490000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpIconPublicRuntimeTests.java", "8eccfdd046b428bc1980ef9f09b95df5a052af4f43bc68cca5c23292833c437f", [
    ["iconsKeepExactValuesAndRespectEachRevisionProjection","45d7d930b9c7c52a025ac70309d7333a76f8d839e1c981d582cfd1b387839520",{"dynamicNodeCount":3}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpJsonAndSchemaLimitPublicRuntimeTests.java", "2eef903d670c010d70fa36d1b63107db4a8287136a7d0ef1b104dce03ab71d1d", [
    ["limitsFailSafelyAndRecoverForEveryRevision","e3ebcfa30fcc56701ede80ee92d61a873e4248f6f5c9056c15c1ceba288a309b",{"dynamicNodeCount":3}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpLegacyNegotiationAndNotificationTests.java", "bbd3426e4d48b9513d65b1344758bf0befa1a79e68e0f31ac3ff82151d7a7248", [
    ["missingVersionCanUseOnlyAnUnambiguousLegacyEndpointWithoutDowngradingModernFraming","339e8d88cfb05d99f635586450d1e7eb228f88381c51c58d45dd47383b41b924",{"generation":{"count":6,"mode":"SEQUENTIAL","complete":6,"prior":5,"incomplete":1},"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":5000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":220000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["initializationCanCounterofferAStaticHeaderWithoutOverridingASupportedBodyVersion","0578305912ee8f8ec53102ad70a0095c18711ecc9833ac333a1573ec0127e3df",{"generation":{"count":5,"mode":"SEQUENTIAL","complete":5,"prior":4,"incomplete":1},"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":5000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":120000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["unsupportedLegacyNotificationsAreAcceptedOnlyAfterAdmissionAndSessionValidation","82268819a8c58ec517cfd65b06032726df306e8c0ddcacd00b20b83967c9f0c0",{"generation":{"count":4,"mode":"SEQUENTIAL","complete":4,"prior":3,"incomplete":1},"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":5000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":400000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpLocalizationInitializationPublicRuntimeTests.java", "150f5d8e438c88f64a284c8aa2952c00348854be8a357e3eca7135595a67766c", [
    ["initializationLocalizesOnlyRevisionSupportedText","b155f96d860d020d2d254418249f27fc1ce20a9cd792dde24a09b90c2a35461b",{"dynamicNodeCount":2}],
    ["failedInitializationLocalizationsReleaseSessionsAndCanRecover","7776b245c2d71ab58abc8d5f403bfbcd7ad038bdc48ea4723f73f1ab08e73840",{"dynamicNodeCount":2}],
    ["wholeResponseFallbackAndNoLocalizerPreserveCanonicalBytes","9eb2464cbcf9da78c76aa97d05519b6e9d7a311e72acf10beb68539ced3efaf9",{"dynamicNodeCount":2,"generation":{"count":3,"mode":"SEQUENTIAL","complete":3,"prior":2,"incomplete":1}}],
    ["simulatorUsesTheSameInitializationLocalization","2ff1fa96536b9a894a0cb268c5a072a27e51d20c1a6ffdb6b11682719712e8c5",{"dynamicNodeCount":2,"controlJoinMillis":10000,"controlComposition":"REVIEWED_DYNAMIC_NODE_MAX"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpLocalizationRenderingRuntimeTests.java", "b2392481eac2874687c2714fbf54ea0f942c870605066f899000587fde6ea454", [
    ["everyFrameworkListLocalizesServerMetadataIncludingEmptyCatalogs","3946de2e78fa35868a30faea558eb7e7dcb32331c5b7bb8b12c902809173ef10",{"generation":{"count":8,"mode":"SEQUENTIAL","complete":8,"prior":7,"incomplete":1},"controlJoinMillis":80000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["serverMetadataFailureUsesTheWholeCatalogFailurePolicy","ba7dbcc5fcec3cf2b7ae6fcd4ff1103402cd9aa228d026e14d381aaacaf9c31e",{"generation":{"count":8,"mode":"SEQUENTIAL","complete":8,"prior":7,"incomplete":1},"controlJoinMillis":80000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  // The existing file-template callable hash is unchanged. The IPv6 test owns
  // one simulator scope, never starts another lifecycle owner in its three-
  // element literal URI loop, and performs three sequential five-second
  // response waits. Its literal 5/2/2/1-second lifecycle policy plus fifteen
  // seconds of control waits totals 25 seconds, leaving 35 seconds under the
  // unchanged explicit 60-second type guard.
  ...reviewedScopeFile("src/test/java/com/soklet/McpResourcePublicRuntimeTests.java", "bf34e3764100e30b15d56245ce06e21b5bb3d48161fdd6c61629391438fff4a5", [
    ["fileTemplateVariablesEncodeSlashesAndDecodeExactlyOnceOnEveryRevision","fec6217cc0553ab160c138d449f6e927ee2e9e373fbf98a5fc14d53822ee5d31",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1}}],
    ["scopedIpv6ReadsAreRejectedWhileOrdinaryIpv6CapturesKeepCase","be15ada00ec09aa5b3151c9c9597d95a69e62df38fe7607aa2fa13c84c0d9fa6",{"controlJoinMillis":15000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpSimulatorPublicRuntimeTests.java", "0e2be1c351c0bb667d0a419fe9c3f3d921215361e9c01b696499355d58c6ac6a", [
    ["simulatorStartsRequestAgainstConfiguredMcpServer","2c0d86afd7ceb1c42c9efae81fe0e636bea552de9e90d8bb39ba40894f411efa",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":45000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["synchronousJsonSimulationUsesRealProtocolLifecycleMetricsAndBodyType","08626d3584884fca91e40c574543343f3c6023a14a6f1b224ae64fd335b158f9",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":35000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["simulatorRepresentsEventStreamAsOpenMcpSimulation","db071f4429cc5b5847d612962df6c53923a32b0e860d661eed834f2bb0aef933",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":30000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["mcpSimulationBuffersStreamItemsAndClosesExplicitly","e3bfe064a1680f076a0654326636740024f7e2165f5891c55ab18e1963b2f7bb",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1}}],
    ["subscriptionReplayPreservesAcknowledgmentEventAndCancelationOrder","1ac72a539ae05ae65674d5f91b780f23aa648470a2da39d984b4d3cb9dfda7e2",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1}}],
    ["concurrentSimulationsRemainRequestIsolatedAndDrainExactlyOnce","35c0b468159f9ad950f4ddbaf882ba4dc80168e6f04b6696052ec9fbb07e3264",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":65000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpSubscriptionCatalogProjectionPublicRuntimeTests.java", "367cc246acddd7b4db7ee2f026c70477109b4656be2eb777a00fbb671f7b836c", [
    ["providerFailureUsesCanonicalCatalogBaselineAndRecovers","f8cfd98530ed0011e26da65f06bf85d134e694f831aa23fde94e899b1c755d64",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":150200,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["lookupFailureUsesCanonicalCatalogBaselineAndRecovers","8ec66bf121e51988e76148123c1062d1065d4e3e4453f321b71a7fcb91eb5c6c",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":150200,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["failRequestCatalogBaselineIsSanitizedAndReleasesCapacity","2fbff3fdda74f3f79abc3b53c21157c3628f9deb292e31c5dd1e3ee308ba1980",{"generation":{"count":4,"mode":"SEQUENTIAL","complete":4,"prior":3,"incomplete":1},"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":160000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["explicitCatalogPolicyContextFailureStillFailsBeforeEvaluation","5520c20d50a93ef2579c9e35ef99e139018c7e195583b10e684df320b36b658e",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":60000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["localizedInvalidationDuringBaselineCatchesUpAndDropsStaleTerminal","c3d81ae76c6979c63e1cc21448938188211e98bdc67076e3e0fcadf2aaef91dd",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":50000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["catalogChurnDuringOpeningDoesNotRepeatAuthorization","ee20a9cf8d072312c84a6b3295e2dddbfbab778da43163705722854b8bdae160",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":100200,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["catalogChangeDuringBaselineCatchesUpAfterAcknowledgment","eec2479d9a223791f4fd2fb22a6189a860254bba6068958bbd95a4e9d28c4d08",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":80200,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpTaskCapabilityBoundaryPublicRuntimeTests.java", "8548b8831c5bbd3865ef94a9e88e473ee1045a2c10eec3ac171b5d7e905e21c0", [
    ["advancedTaskReturnsWithoutCreationContextAreInternalErrors","f01f23752f80185b828711a70c5d0755e8fc13c53c4b7c52443ee61067283878",{"dynamicNodeCount":6}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpTaskInputCapabilityPublicRuntimeTests.java", "da9afd6f3d42d2116b98ff87092d6afa92172a397f411f1f4da4b9c3624090d4", [
    ["everyOutstandingModeRequiresCurrentRequestSupport","4cd38945a4a4c4c4583ca8fa9a4d6c6a7bc88ae9930e1b4a0e065c057181aaf8",{"dynamicNodeCount":13}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpToolInputErrorPublicRuntimeTests.java", "051c52f10b4306302e72933d4b2f7acaecf78646607f30493cdbdc5c60f4834d", [
    ["toolInputFailuresFollowTheSelectedRevision","e472b0198e55dcb82eba57b145d5a4cc0a96b3e3956eaabaf9ef0bc6ff6f9a87",{"dynamicNodeCount":3}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpTypedIntegerLimitPublicRuntimeTests.java", "7f9c5e31d2f924056d3b8cc3141a72891d79d372f04c5c97b64841e83a42d62d", [
    ["boundedIntegerBindingForEachRevision","c8592af11a532247ebd1c691342d495e672fbd85c1b9fb954e4579acc5ed38e1",{"dynamicNodeCount":3}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/ResourceMethodBindingValidationTests.java", "c6d4dae27206b4a10a47020118718b159e5e73683592bc2f7283a5f26f412566", [
    ["defaultBindingFailuresAreRejectedBeforeStartupOrInstanceAcquisition","d8187bcc1d4f0e2db369cf3657eac9a4288fed6e184ee4229a3e3a45616c8ae0",{"generation":{"count":4,"mode":"SEQUENTIAL","complete":4,"prior":3,"incomplete":1}}],
    ["incompatibleDefaultConstructorsFailDuringSetupWithoutConstructingAnything","1c7c0aa57449b1c38c02d7087e5baa3acd695cfe66d6a19b9e19032bbf343368",{"generation":{"count":3,"mode":"SEQUENTIAL","complete":3,"prior":2,"incomplete":1}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/Round2SseRuntimeTests.java", "dcd33b32fd31747bf4f2aded44d88503bdb7f25a58ed1d42dd9fa1254809d2ef", [
    ["partialHeaderReadersLeaveTheApplicationHandshakeWorkerAvailable","20703e0f1d49713df905bf9831b1a48f9ffa503fee87a4ef82ba4236aaf985f1",{"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":1000,"gracefulShutdownMillis":3000,"forcedShutdownMillis":1000},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":9000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["pendingHeaderAdmissionRetainsTheConfiguredBound","33383ce80b4ca9e6c8367a8277537a3b9efcb156b509a47fd7a101b55793f68e",{"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":1000,"gracefulShutdownMillis":3000,"forcedShutdownMillis":1000},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":3000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["gracefulShutdownQuietlyClosesIdleSocketWithDefaultTimeoutsAndJoinsReaders","79f4eddf0d5dbc83f532d7eaea526015dd6043705206d0d2a13e201e801c6ffa",{"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":15000,"forcedShutdownMillis":3000},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":6000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["gracefulShutdownQuietlyClosesPartialHeaderWithDefaultTimeoutsAndJoinsReaders","dafc334a63d5be5c78739e0cc9f2d6560a98a6497a948aa48520af1ba699bba2",{"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":15000,"forcedShutdownMillis":3000},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":6000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["gracefulShutdownPreservesParsedQueuedHandshakesAndTheirResponse","8dafea0cf5957b32a37cb6283ba88bca226bdef4300d9a035e2692d8d35486a3",{"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":15000,"forcedShutdownMillis":3000},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":8000,"controlComposition":"REVIEWED_LIFECYCLE_CORE_DEDUPLICATION"}],
    ["requestFinishingParsingAfterQuiesceCannotEnterTheApplication","42994c4530e3567c1e01efe3ae64fe94f2ee2931d6e9aa4ea64422f8b97b8e04",{"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":15000,"forcedShutdownMillis":3000},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":8000,"controlComposition":"REVIEWED_LIFECYCLE_CORE_DEDUPLICATION"}],
    ["malformedOversizedTargetsUseUnparsed413WithoutInternalFailures","60187a0f852fffa77a4efb3828d39666c0c1f6563f5fda16d1dafd626d28a8d7",{"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":1000,"gracefulShutdownMillis":3000,"forcedShutdownMillis":1000},"generation":{"count":3,"mode":"SEQUENTIAL","complete":3,"prior":2,"incomplete":1}}],
    ["forceShutdownClosesAdmittedHeaderSocketsAndJoinsTheirReaders","93bfee81772025f31b227184f88394b3c1e3fb79b5f0ed9070c5033907aca00c",{"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":1000,"gracefulShutdownMillis":0,"forcedShutdownMillis":1000},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":6000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["interceptorFiniteReplacementCannotJoinTheBroadcasterInLiveOrSimulatedSse","dd2f6cfa45bc33669f4833bcc5b981e7f00ca54c902c1273fd92d27ace2028ab",{"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":1000,"gracefulShutdownMillis":3000,"forcedShutdownMillis":1000},"generation":{"count":4,"mode":"SEQUENTIAL","complete":4,"prior":3,"incomplete":1}}],
    ["initializerFailureAndOverflowEmitOneContextualDiagnosticWithTheElectedReason","ac7de6a7ee76cda1454e58f2bb433d259770520fb83006516a5a9b0220108a88",{"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":1000,"gracefulShutdownMillis":3000,"forcedShutdownMillis":1000},"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":6000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["immediateResetHealthChecksDoNotReportServerAcceptanceOrTransportErrors","ed7ae960058cbe6657658fb166cf28de0e6d0989c27d7c96ed17ae26aed06fd1",{"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":1000,"gracefulShutdownMillis":3000,"forcedShutdownMillis":1000},"controlJoinMillis":37000}],
    ["noncooperativeHandshakeReturningAfterForceDoesNotRecordAWriteTransportFailure","3b93701fa157856a0c5c762b4ea62bbd92499d1fb0a77a76b0f381524ba47684",{"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":1000,"gracefulShutdownMillis":0,"forcedShutdownMillis":1000},"controlJoinMillis":12000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
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
  ...reviewedScopeFile("src/test/java/com/soklet/SokletDirectLateStartupIntegrationTests.java", "a2f576081a6b3a123979fd753276bd1999a5613351185dab1dd77dc5a855a819", [
    ["lateStartCompensationDoesNotWaitForOrReplayQueuedForceWorker","c6e9b2bd340baf6fff466604cb6100ce09a6f080297e60125f6fafed327180ea",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":0,"gracefulShutdownMillis":150,"forcedShutdownMillis":2000},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SseDurationBoundaryTests.java", "72ed04ac961f3a230da15e9a2bebc883cf80dfd2ca679c18a89ea775f0d8ae89", [
    ["invalidHeartbeatsFailAtBuildRatherThanDuringAConnection","58b387113e2c317c038103d915f54288f1f80762318bf1b961bcbfb8aa3551be",{"dynamicNodeCount":7}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SseHandshakeOutcomeRuntimeTests.java", "6d7781eafe8226c62f3e1ebb303c565c53c085add9660b49aa1d0ec8a8d90459", [
    ["simulatedInterceptorFramingHeadersCannotActivateAnAcceptedHandshake","668466bb36869bafc87bedf32de2f61b0dfe61d0efef20375c09c338aaec87c5",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":300,"forcedShutdownMillis":1000},"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
    ["liveInterceptorFramingHeadersCannotActivateAnAcceptedHandshake","54f574a55a070059631bd25fdd54f06d74d650bec2fd3b7401647726a0801204",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":300,"forcedShutdownMillis":1000},"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":4000}],
    ["headAndOptionsDoNotConsumeOrRequireStreamCapacity","561e48be855f0868880b565653df98dade15a6f0755d9cc4a5d318eac3ad314e",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":300,"forcedShutdownMillis":1000},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":9000,"controlComposition":"REVIEWED_DYNAMIC_NODE_MAX","dynamicNodeCount":2}],
    ["headTimeoutResponsesCannotSendContent","cccf67142ae0512a52cab846be60e96b212c7c13efe52813b77c61109870b3bd",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":300,"forcedShutdownMillis":1000},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":5000,"controlComposition":"REVIEWED_DYNAMIC_NODE_MAX","dynamicNodeCount":2}],
    ["initializerFailuresReportEstablishedThenElectedTerminationWithoutInternalRejection","e30728682062a7ce87c442adb45b23ed60b5e32fdecce4e69273e733b3b9bae1",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":300,"forcedShutdownMillis":1000},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":11000,"controlComposition":"REVIEWED_DYNAMIC_NODE_MAX","dynamicNodeCount":3}],
    ["bothCapacityBoundariesReportCapacityRatherThanApplicationRejection","e19bb2164b31af09d24412e04dc8f4667eb25a2921244e6378a676c18de6af09",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":300,"forcedShutdownMillis":1000},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":10000,"controlComposition":"REVIEWED_DYNAMIC_NODE_MAX","dynamicNodeCount":2}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SseMemoizedBroadcastTests.java", "0673f39f429c08717e80b1acbc5f626115672de176a275ef57763b62af1a0205", [
    ["simulationCachesProviderFailuresAndNullResultsOnlyForOneBroadcast","d004c100cab7a682c4d591c0eaf2d6f476bdedaccc364ff45125d96a02a537e1",{"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":15000,"forcedShutdownMillis":3000},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"dynamicNodeCount":4}],
    ["liveBroadcastCachesProviderAndSerializationFailuresAndRecoversOnTheNextCall","9bb11e1661c0dda41b19d74ac3e4526fbfdc67d8bc39120e03225e01e924fe96",{"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":300,"forcedShutdownMillis":1000},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":15000,"controlComposition":"REVIEWED_DYNAMIC_NODE_MAX","dynamicNodeCount":4}],
    ["simulationLogsGenerationFailuresOncePerKeyWhenTheHandlerIsAbsentOrFails","e0a1e89045f95f953311228e80aef2bbc55f8dadb2561cad29614473a78b6d97",{"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":15000,"forcedShutdownMillis":3000},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"dynamicNodeCount":4}],
    ["simulationConsumerFailuresDoNotInvalidateTheSharedPayload","58039a87fca7632e8e9b4769c9d48bae0757e31140957b1168d164dbde634d36",{"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":15000,"forcedShutdownMillis":3000},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"dynamicNodeCount":2}],
    ["simulationKeySelectionFailureRemainsPerClient","b98d982a7abdf89503cf4e9d7bc22e927da596b6f40ad0e94c5061bf2878441f",{"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":15000,"forcedShutdownMillis":3000},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"dynamicNodeCount":2}],
    ["liveKeySelectionFailureDoesNotAffectOtherClients","fb705f665985013a4181593c58782b36bdf8a8965ac11c7771631c4882dc54b5",{"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":300,"forcedShutdownMillis":1000},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":15000,"controlComposition":"REVIEWED_DYNAMIC_NODE_MAX","dynamicNodeCount":2}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SseReadDeadlineRuntimeTests.java", "bf306e0358a37cef3f9734ef104a98f58f8b1943d9252fb20dd92617eb5bb61d", [
    ["headerDeadlineWinsRegardlessOfHandlerTimeoutOrdering","15b9726bf06a7d89c9811b3076dcc6ffd0fe3c939e49c56ccaf3347ee411d829",{"phasePolicy":{"startupMillis":2000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":4000,"controlComposition":"REVIEWED_DYNAMIC_NODE_MAX","dynamicNodeCount":3}],
    ["idleReadDeadlineClosesWithoutFailureEvents","41963a7eb890bbfcf43724d2cbeff3cfecb42557484dfec0e60634d2a309ee51",{"phasePolicy":{"startupMillis":2000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":4000,"controlComposition":"REVIEWED_DYNAMIC_NODE_MAX","dynamicNodeCount":2}],
    ["eofBeforeCompleteHeadersIsAQuietClientDisconnect","c5b6d571e7b86820a17c03711bac8b184897cffa7287d50af23b44e473ca07bb",{"phasePolicy":{"startupMillis":2000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":4000,"controlComposition":"REVIEWED_DYNAMIC_NODE_MAX","dynamicNodeCount":2}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SseTests.java", "9ce193b4f0e2ec07a6b602600a3171dd27895fbb9db776a3b9c64d9dcab61920", [
    ["sse_clientClose_setsTerminationReason_remoteClose","f24d33685d5c7fa4f560c554b669c5b7fe612ab07373e2adf76fadb1d8149920",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":15000,"forcedShutdownMillis":3000},"controlJoinMillis":23000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["sseHandshakeClientCloseDoesNotRecordTransportFailure","6c791a431166340770692b0576437069c6be71bd4906ed45b63834d4103bf459",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":1000,"gracefulShutdownMillis":3000,"forcedShutdownMillis":1000},"controlJoinMillis":12000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["handshakeTimeoutApplicationCallbackDoesNotBlockSharedScheduler","80faa4c825f394041a2c14796dffdc0d32adcb6e3d190e0b036ae7baa55bcac2",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":15000,"forcedShutdownMillis":3000},"controlJoinMillis":17000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["sse_backpressure_setsTerminationReason","57c88622e2e903c9c5cdc2c5a28ed5c03873a0309f595cd988d460fc70a467c6",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":15000,"forcedShutdownMillis":3000}}],
    ["sse_stop_setsTerminationReason_serverStop","a5e73812dcb5ab92b84df7491359ee0155408284d06e810af03830787d6e4fe6",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":15000,"forcedShutdownMillis":3000}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/StreamingCleanupDiagnosticsTests.java", "022124943ef7b965298b1e27d393df869a7da985eeb79aa356dd8426b4a649d5", [
    ["abortFailureKeepsThePreviouslyElectedTimeoutAndReportsCleanupContext","e02db8b69a84ba967b3506dd0fb37769e326ea6fd9ed49e9b63f2d41d9380653",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":17000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","phasePolicy":{"forcedShutdownMillis":100,"gracefulShutdownMillis":100,"startupCancellationMillis":2000,"startupMillis":30000}}],
    ["cleanupDeadlineStillReportsFrameworkSupervisionAndRetainsPhysicalWork","8e6d4c7c7faa0a07ca789221d0cecfd104693401e9d350e0bb036d15e46f02c1",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":20000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","phasePolicy":{"forcedShutdownMillis":100,"gracefulShutdownMillis":100,"startupCancellationMillis":2000,"startupMillis":30000}}],
    ["untypedUpstreamExitAfterCancelationIsProducerEvidenceAndNotACleanupFailure","05ecda567f4fe3bd8520064a4848902ef7a203e53bc24acc7a07685ed95b5454",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":14000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","phasePolicy":{"forcedShutdownMillis":100,"gracefulShutdownMillis":100,"startupCancellationMillis":2000,"startupMillis":30000}}],
    ["ordinaryWriterFailureIsStillAProducerFailureWithoutACleanupDiagnostic","79da3f6570ea83d81f0ecbcc5cc82ab694b22fb7d4bc76ef7d09eb8415c98305",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":12000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":15000,"forcedShutdownMillis":3000},"requiredAction":"RAISE_OUTER_BOUND"}],
    ["applicationSocketFailureAfterCancelationRemainsDiagnosticEvidence","57ea62c859f7d5cea01381c9177aad8fc41dfb5f20888dbb1e72818ccea85a78",{"controlJoinMillis":14000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/StreamingLifecycleTests.java", "8fea091632d561ad48af6a299672d3a722b18b41443e3ff70cbb93cfcb890daf", [
    ["lifecycleCapacityRejectionReportsActualFiniteResponseAndOneStreamTermination","eada83eaf4c7d9521284cb06d38c7379d350102845917f70bba8c4c64207883f",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":200,"forcedShutdownMillis":200},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":9000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
    ["defaultProducerCapacityNeverCommitsAStreamQueuedBehindHeldProducers","9b97778887645ed122fdc72c09fec1f975e408181cb4af5ea4ea92e3ffd775b3",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":200,"forcedShutdownMillis":200},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":12000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
    ["suppressedHeadAndUnsupportedProtocolReleaseAdmissionWithoutAcquisition","1a9dbacf44cd015253f48b94a1d3e444361b95b74962204cefea42c34bb67d09",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":200,"forcedShutdownMillis":200},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":10000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/StreamingObserverIsolationRuntimeTests.java", "741c079cea7e65eeae013f8e25e285d11f1ae9a8fda6910218e421fd1656837b", [
    ["blockedHttpCancelBatchLeavesHealthyStreamingAdmissionAvailable","ce29233199d68b3b5e0cd3baa9de7885dc4023f07770d9be9e1bcc3ed6a3d925",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":65000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","phasePolicy":{"forcedShutdownMillis":100,"gracefulShutdownMillis":100,"startupCancellationMillis":2000,"startupMillis":30000},"requiredAction":"RAISE_OUTER_BOUND"}],
    ["blockedHttpTerminationObserverLeavesHealthyStreamingAdmissionAvailable","6eb9d5266f54d15fcd37446a8501c5188230d088b4d71f3ea7e0f6159b453a60",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":65000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","phasePolicy":{"forcedShutdownMillis":100,"gracefulShutdownMillis":100,"startupCancellationMillis":2000,"startupMillis":30000},"requiredAction":"RAISE_OUTER_BOUND"}],
    ["blockedHttpCleanupLoggerLeavesOtherCleanupDiagnosticsDeliverable","35dc23ab38a0006b73f498a5a8dc9ca162ae892eff0111a73696cabe1f86bdf4",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":65000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","phasePolicy":{"forcedShutdownMillis":100,"gracefulShutdownMillis":100,"startupCancellationMillis":2000,"startupMillis":30000},"requiredAction":"RAISE_OUTER_BOUND"}],
    ["simulatorWaitsForItsOwnObserverWithoutBlockingOtherRequests","577ec13b15ef4a198f608b963cb91807d490aff8fc9f11b36b9ea7f831f1709e",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":30000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","phasePolicy":{"forcedShutdownMillis":100,"gracefulShutdownMillis":100,"startupCancellationMillis":2000,"startupMillis":30000},"requiredAction":"RAISE_OUTER_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/TransportStartupFailureTests.java", "d5568c75de818bf13739c5b38d62105f0c3bd5ab341200386e546ced6d2a84c9", [
    ["httpDirectBindFailure","e6d6f31185575540dd922c5b9a4f6c25f49004ab9ff1a1149b78fabb937ef22b",{"phasePolicy":{"startupMillis":2000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1}}],
    ["httpTransparentBindFailure","90381426992711494e73c94a359572da2004bb5668b7f023cda056e41be0ee89",{"phasePolicy":{"startupMillis":2000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1}}],
    ["httpOwningBindFailure","56dca1623bb70091216f325fc012f8931ea5366e4d2faede575ad67c657195d9",{"phasePolicy":{"startupMillis":2000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1}}],
    ["httpNestedBindFailure","223a63a52dbf71b4a0deeec99f71fdcb2056cf09568a72f52fbe0cd7a0cf604f",{"phasePolicy":{"startupMillis":2000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1}}],
    ["sseDirectBindFailure","da8044ebd0d084eb3c3e9f34ece1f669a189c6dddc9e695b78eabc81582eccd7",{"phasePolicy":{"startupMillis":2000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1}}],
    ["sseTransparentBindFailure","461ad5202c16fee7e9bc41c6e5eeb66f706e5f193b2ef1c09199ae49c26af8a8",{"phasePolicy":{"startupMillis":2000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1}}],
    ["sseOwningBindFailure","e50bf96441e78481cfa875cee9d7503a70493d9cf7cfde4c3c633172a1813f67",{"phasePolicy":{"startupMillis":2000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1}}],
    ["sseNestedBindFailure","9b014ed670c2efe5138bf48a2fd9e69a3e107dae2a3711e454ef20694a7d647e",{"phasePolicy":{"startupMillis":2000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1}}],
    ["mcpBindFailure","7fb77915793f25c56ba8c3749ed684e0a58324d72871f53a4f81d8844558afd8",{"phasePolicy":{"startupMillis":2000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1}}],
    ["applicationUncheckedIOExceptionIsNotUnwrappedAsAFrameworkAdapter","83d2ad7bd0378d399677e60357ac2f30e61499589e88fb0a449ff2dc97688254",{"phasePolicy":{"startupMillis":2000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1}}],
    ["customStartThrowWaitsForRollbackToReportProof","fda539caf80f0f29c155556e406c8e02dc49e2d5b9a7ca8457b63210c976eadf",{"phasePolicy":{"startupMillis":2000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1}}],
    ["independentFailureBeforeStartThrowRetainsItsControllingEvent","5f738a708a70b1572cf6b8b8af3e1d4a98161e776e7de939b83d7295829343df",{"phasePolicy":{"startupMillis":2000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/UnparsedRequestAdditionalTransportTests.java", "4f1cf2dca4aa26a4f63d8b844e37ce855a76137c7a26eb5efd335f412236f35e", [
    ["uncooperativeSseUnparsedHandlerPreventsCompleteShutdownUntilItActuallyReturns","5b3ebc7fa130da50df2b2150954376a5fdf59166d3b5645b041adcb939be554b",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":6000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","phasePolicy":{"forcedShutdownMillis":1000,"gracefulShutdownMillis":300,"startupCancellationMillis":2000,"startupMillis":30000}}],
    ["stalledSseMarshalerReceivesInterruptionAndCannotWriteALateResponse","98e50a02cdf66cfcd2b80a8cf155c5fa19b1e8124a32530c32dd929a4f1d33d4",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":6000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","phasePolicy":{"forcedShutdownMillis":1000,"gracefulShutdownMillis":300,"startupCancellationMillis":2000,"startupMillis":30000}}],
    ["laterHttpConstructionFailureUsesItsOriginalRejectionWhenMarshalingTimesOut","9b27dd78ae658458177c3f082fee6c2a64ac97be3beb2796eba4e8b29dbca1f5",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":6000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","phasePolicy":{"forcedShutdownMillis":1000,"gracefulShutdownMillis":300,"startupCancellationMillis":2000,"startupMillis":30000}}],
    ["httpRejectionsBeforeRequestConstructionAreCustomizable","d7a103e98f0ba63915854aea21568e76029a3407fb07596e7dd1d50b32cbc779",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":2000,"controlComposition":"REVIEWED_DYNAMIC_NODE_MAX","phasePolicy":{"forcedShutdownMillis":1000,"gracefulShutdownMillis":300,"startupCancellationMillis":2000,"startupMillis":30000},"dynamicNodeCount":8}],
    ["ssePreRequestRejectionsUseTheSameHook","15b985c8b5d2bd1307582248ecfccdeea8dcf9035486326ef93cf108e661b10c",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":2000,"controlComposition":"REVIEWED_DYNAMIC_NODE_MAX","phasePolicy":{"forcedShutdownMillis":1000,"gracefulShutdownMillis":300,"startupCancellationMillis":2000,"startupMillis":30000},"dynamicNodeCount":7}],
    ["sseInvalidOrFailedMarshalingUsesTheOriginalFallback","9b764a3eddfa20f31f18d9f91a1938e3ca10445eaa13769561c71d4b83691561",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":2000,"controlComposition":"REVIEWED_DYNAMIC_NODE_MAX","phasePolicy":{"forcedShutdownMillis":1000,"gracefulShutdownMillis":300,"startupCancellationMillis":2000,"startupMillis":30000},"dynamicNodeCount":3}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/IntegrationTests.java", "beedd9c12a7d7dccbec6bdca9bc22f6a723ed6e2bf3238835b32e35917654499", [
    ["requestHandlerQueueCapacity_rejectsWhenFull","c97c31d8d176b9913b3583ec961bb52cd4f5bf9396d89e09607c64e6e7831ba3",{"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":15000,"forcedShutdownMillis":3000},"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":11200,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","requiredAction":"RAISE_OUTER_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpHandlerMetricsObservabilityTests.java", "adac81c1b3e144a7648c5f6cfbc69f2123f6fcdd47e4c8117082c1251f95431c", [
    ["queuedDisconnectDequeuesWithoutStartingHandler","9f0643cc0c4daad8c45e9af1e0be4f83021042d638d5a5886e423b12bd17c9c8",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":43000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
    ["managedStopDefersQueueAndExecutionCallbacksBeyondLifecycleLocks","d72292b2dc82b49ed2907122beb97415d59cb36c63a109e331af05c19ded6e71",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":40000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpLocalizationFleetPublicRuntimeTests.java", "226b9445317201e235c721ddc100e17be8730c41707cc5c43d930d458fea96d8", [
    ["applicationBrokerRecoveryCoversDelayedDuplicateMissedAndRevokedFleetState","533b23644b51a425749c022f1f6d071acf85ee2a144ff890e627e316f87cd8e3",{"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":1000},"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":179000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","requiredAction":"RAISE_OUTER_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpMetricsEventDeliveryPublicRuntimeTests.java", "093d7ba98a614a9514abc064729b7bcea1338c042a4f6eff9a6ef41808848234", [
    ["lifecycleObserverFailureDoesNotControlStartedStoppedDelivery","c7787436e1b87bd9cdc2a876de226328c02eb87984f126a178ae30da5b254fa0",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":30000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":1000}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/Round2HttpStreamingRuntimeTests.java", "119e97003f90cec0cc29e968d0af0ce710db4559a8190757739ca1d90153be9c", [
    ["abortedFileDownloadDoesNotBecomeAServerTransportError","7346b104946ee9657bc77cbf1c0c18f0e44eff53c34876f8963047c2c45237f6",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":10000}],
    ["zipCleanupAndInterruptibleUpstreamRemainQuietAfterLiveDisconnect","8e0a5480e4cf7e5ee0bbbc9d8a95d53ee10b9ac2273f6303826705c309c76b92",{"generation":{"count":4,"mode":"SEQUENTIAL","complete":4,"prior":3,"incomplete":1},"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":49000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["lateFiniteAndStreamingHandlersObserveTheTimeoutResponseWithoutStreamAdmission","c69fadee8234733cc29a09417e85f485b9531a948b423c573a211da933d0f8e8",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":20000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["throwingWillWriteObserverStillWritesBeforeFinishForNormalAndProtocolReplacedStreams","5dbc8c5ce2d6bc43eeb4f9277a505688d401d509cb196df75fba8dedeb492fe9",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":16000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["classicSocketUpstreamInterruptRemainsQuietAfterLiveDisconnect","55ea64432810808b7ae0529417d3e1044ee45b51c0627c13baa4dce35e77343d",{"controlJoinMillis":10000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SimulatorStreamingContractTests.java", "b1f6e4656443a02f69095ff4af2fa7060e58afc04352d731c68b25731195f1fe", [
    ["interceptorOnlyStreamSupportsAbsentResourceMethodInBothRuntimes","6c0e66399c0e7e3e92283be256173ab7c30d1319e100e2c38199690a997e2469",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":15000,"forcedShutdownMillis":3000},"controlJoinMillis":5000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SokletApplicationProcessTests.java", "22a0c2f6111efa0281aeb2b5a439a513109df06886ca0659cb5a7a8719d573d4", [
    ["hookRegistrationPrecedesStartAndRemovalFollowsReport","610f7f5e0b1ceeca2b41bfbce8564d273cc59ca01183df9ddf5fd0bc61db7499",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":20000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlledLifecycleCoreMillis":0}],
    ["hookBeforeStartClaimNormalizesNotAttempted","77f56f36f04e856fa3905ce8daca06bfb0a249e55727edafb6a48caae0455657",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":25000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlledLifecycleCoreMillis":0}],
    ["hookDuringStartClaimNormalizesCancelled","ec9056dedd99daa5fcfdec04a5251cce84887b8bff054279d69b52be41fede52",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":20000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlledLifecycleCoreMillis":0}],
    ["explicitShutdownDuringStartReturnsCompleteCancellation","8ae4cb1a951bd746d3c88e4d7947c9dbac621003c63cdf5ad66addf7e6155c80",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":15000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlledLifecycleCoreMillis":0}],
    ["explicitCancellationWithIncompleteRollbackThrowsIncomplete","456e8be3f04e8d206f3654927363e8f2a57a7d7df11e8202f4c081d33005871d",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":20000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlledLifecycleCoreMillis":0}],
    ["runnerAndHookShareOneCleanupAndReporter","eba57d7a768935e0e9284274b0e286101cde498c4772543f8247dce51e0771fd",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":22000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlledLifecycleCoreMillis":0}],
    ["hookRemovalWaitsForReporterAndConcurrentShutdownRemovalIsBenign","4002545c2744aca82a3b244dbe357895b2c60cc350839901f54ab9bd50cad9f3",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":25000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlledLifecycleCoreMillis":0}],
    ["runnerInterruptionRequestsShutdownAndIsRestoredOnReturn","800983204c984b288c7c76edb2a005f8b77843b2c77c0ccfb133b5105b942b57",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":15000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlledLifecycleCoreMillis":0}],
    ["incompleteResultSkipsConfiguredCleanupAndThrowsExactResult","3538328cc54e2c4b4324a5a9549f9f5a0ee5828f3490ec2ece75259afafc4e16",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":15000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlledLifecycleCoreMillis":0}],
    ["unexpectedTerminationRemainsPrimaryWhenShutdownIsIncomplete","df639b533811ff25969179d380d341e90770f2f621f4d84b81263311840cb9f9",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":20000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlledLifecycleCoreMillis":0}],
    ["expectedCompleteCleanupFailureIsTheCallerPrimary","0d7396ecb77d3c6b684e2935acb4069d5ff4b2e71f4eec8a3f99298dcdd2bfa4",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":21000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlledLifecycleCoreMillis":0}],
    ["unexpectedCompleteRemainsPrimaryWithOneCleanupSuppression","2cb88bdae400fda6a4f1da552ce2f279e39a8382b3fe857addf55995059b6aee",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":21000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlledLifecycleCoreMillis":0}],
    ["interruptedRunnerRestoresFlagWhenCleanupFails","068ce53dbde867ce3b1429569f13268eaf93f81207a0dbfae5ac91743bccedc7",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":16000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlledLifecycleCoreMillis":0}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SokletDirectTerminalPublicationTests.java", "dab2b99392310cb48d54d26298fb03c2bfa41ed7feb9d4205ec25a8c38f07e3f", [
    ["minimalViewAndDetachedMirrorsCannotControlOwner","84ba72162352e5ba9ea4dad8e5f8f0127771115d4c247dc8f1e047ba283d98a1",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":20000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":1000}}],
    ["publicContinuationsPreserveExecutionAndFailureIsolation","7c531b1ae531cc4ee5fe5514ac574e1ac550e5d9a1f71e97be0e3d9b44900fc4",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":24000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":1000}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpSubscriptionAuthorizationPublicRuntimeTests.java", "0926904d5dc0fcf8bb2b1f374008c12886aabd28f3e975d80642d5ed34a5a243", [
    ["nullInitialAuthorizationFailsClosedWithoutQuotaLeak","03f336138f700296f4bd30b11639e62949abb35cf4bb2b3693cfad6cb6bc687e",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":1000},"controlJoinMillis":5000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["thrownInitialAuthorizationFailsClosedWithoutQuotaLeak","280b3a69f8007d5ee99229e1269511197cf9b0345728a202e67e3a260eb3b87e",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":1000},"controlJoinMillis":5000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["alreadyExpiredInitialAuthorizationFailsClosedWithoutQuotaLeak","19fa99676358294e3b2ac0c0ec9dec662683a7739600d96f0d663703722b038a",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":1000},"controlJoinMillis":5000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["deniedInitialAuthorizationFailsClosedWithoutAcknowledgmentOrQuotaLeak","3b0f922c0826f0ff94240e87a7b0e99308e2fefe2790362c092f0c7772cd1c23",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":1000},"controlJoinMillis":5000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["maximumAuthorizationDurationClipsTheApplicationLease","d1138b14b254b5d8d5f7875a67c46d59f540136df9ffbea7fd5bd90fbb96e94e",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":1000},"controlJoinMillis":10000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["automaticRenewalRunsNearHalfLeaseClearsContextAndRetainsFilter","60a1ca5a937e5ba29675b024e183ada4ab33aa9874a640e6336369017b0fe353",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":1000},"controlJoinMillis":20000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["ordinaryBlockedRenewalKeepsDeliveryUnderThePriorLease","4be3040274c7b1fb5027194e14065cd94aa3c3e9eb77ff8ff356e809eb829d7a",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":1000},"controlJoinMillis":21000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["sameExpirationRenewalDoesNotScheduleAnotherRenewal","cfdef936701c99e286d42ca74a3d2b6f605d10837a7c42c885220dc09e236b41",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":1000},"controlJoinMillis":15000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["simulatedReconciliationDenialPreservesTheCompletionAndCloseReason","0d985e95d9d84a061e63ca7d1daa8d6b244077601012858e3759ba9ec5a59fff",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":25020,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["disconnectWhileTheClosingFrameIsReservedKeepsTheTransportReason","aed486936b499a1b49070be3b911200e7b5a3a54842eaf21ce70d3946bb265ec",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":1000},"controlJoinMillis":25000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpSubscriptionResourceCatchUpPublicRuntimeTests.java", "b8daedf4bfc705fe97bdb7476db258847e70e61a9cc15aaa0c1b5f923407eac3", [
    ["renewalReplacingRegistrationDoesNotLoseResourceOffer","6cdb6cc0e40f46240392bc7d141d672bf8fb2a80ceae2a53f968a5b2ff4d9bd4",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":1000},"controlJoinMillis":20000,"controlComposition":"REVIEWED_OVERLAP_OR_DUPLICATE"}],
    ["renewalChangingApplicationContextReprojectsPendingResourceOffer","1ef9f99f4dd9c8aeb45b21faf7221a50aa86ab11e43d21ae7238ffbca70e9823",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":1000},"controlJoinMillis":20000,"controlComposition":"REVIEWED_OVERLAP_OR_DUPLICATE"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpTaskSubscriptionPublicRuntimeTests.java", "a09755839331e1ca5206406719f7e0e9b9be48f615de0526e8dbe8dadcd2b3a8", [
    ["taskNotificationUsesCurrentCatalogPolicyLikePolling","5b770ad0838f1025d8f5adafaa36e89d761567cf2d9db801464ee8efaf85c9d8",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":15000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["missingOriginSuppressesCompletedNotificationWithoutMutatingStoredTask","edcb1438534e66bec84f5e9027e0151f73f031cf59cc7fa13ff234ff691e2b76",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":15000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["taskAuthorizationUsesCredentialFreeReplacementContextAndCannotExpandOnReconciliation","7f18c1b102b35d21bc04f6761a782d284864821f08842d41013a9c51152cd4e5",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":20000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["reconciliationFencesOldTaskContextAndDropsOnlyRevokedQueuedTask","9d45afeb70ed3010c3163b5aa86bf1a55934a217f8146380abfb011a118721f3",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":35000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
    ["renewalRechecksPendingTaskResultWhenCatalogAccessChangesUnderSameContext","27f039527ba5dad63951ab5e5f0dbe24309953a13262e140fa252fc87d86b322",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":45000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
    ["transientProjectionLookupFailureKeepsSubscriptionOpen","45b27668ab3754f1bde494e00d610b16287efbef92e769ab1a939c09aa55bd63",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":15000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["duplicateEventsReadCurrentTerminalAndSuppressLaterStates","7d48c1f2fa3c35077355750faae4beb6b46217af61276417719f0f833c9b9c1c",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":30000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
    ["disconnectDuringActiveProjectionReleasesSubscriptionAndRecovers","540785d708f3837b396da3dd6eb46cd24747679d7e62522aa5da06ab0e6a5661",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":40000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpLegacySessionPublicRuntimeTests.java", "ec7a2db71a8a788ea0e03f48f9a3fea5851acfe1fd4e4deb42e0b9f105a77e9d", [
    ["explicitCancellationCompletesFiniteAndCommittedSseBodiesWithoutAResultAndRetainsPhysicalWork","b59a308129982bd78f27de8d282c072a6ee4fd81517698a2b8974a678d71041b",{"generation":{"count":4,"mode":"SEQUENTIAL","complete":4,"prior":3,"incomplete":1},"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":73000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["activeIdCollisionsAreTypedAndCancellationIsBoundToOneOwnerAndSession","e7adaaa237ed4d66f23819a0f7a4005a5005e1fa445608e4917f3cc8ac8c2bc6",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":65000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["resultReservationPreservesQueuedTerminalBytesAgainstALateCancellation","3fa0ca8bb0b95db74db18ae55522624390c8930be35aac8f950bbf661cb28bab",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":70000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["verifiedControlsRemainAvailableWithFullHandlerQueuesAndDeniedOrdinaryQuota","514065255dce9b2cfa1927577ca61029b0f5096062ec893447077647cbabd6be",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":73000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["verifiedDeleteTerminatesActiveAndQueuedCallsWhileOrdinaryHandlersAreFull","c06b3c05cc99ddeb1f4d6ea0e3797fc8e1ba5a3fce08def6d1cef8b24641222a",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":73000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["correlatedLegacyDeadlineErrorsUseHttp200WithAndWithoutSessions","48721b591205b4fca4b6f0f5e3924f520f3301c75eb6eff4675b5afcb6a33085",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":60000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpLegacySessionTransportPublicRuntimeTests.java", "3f20c67104f7933e75cfe3371921d9a89614f4daba67e63d28d2ca070e3f40ca", [
    ["blockingHttpFinishObserverDoesNotStallUnrelatedPostAndRetainsCallbackOwnership","4d0a6435664b48bd40a1ed43bc894ead46a8f965c84b840fba84a08d480865e6",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":15000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
    ["transportEndDuringBlockedHttpStartDeliversFinishOnlyAfterStartReturns","1f109e61e89dd476eb593cd05ab460be2935dd555a30411cdcfe5cb0cb2ceac0",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":10000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpLegacySessionTransportSimulatorTests.java", "e174ffad2a107f18f34da76b9950b6690fe3b5e24d3c693ed2049403534a53b6", [
    ["simulatedLegacyGetCapturesKeepAliveOnlyAfterTheConfiguredIdleInterval","41f175222888afb45cc526d3fe8a0bc435a87dddc90b62b1c91c5b33b552ef87",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":34800,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["getAndDeleteSnapshotsUseTheConfiguredEndpointInSimulation","be4564581a7e95cacebed268fcac0a0d075f99397ae267c582f90e3bb03eb773",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":70000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["singleLegacyViewSupportsHeaderlessGetAndDeleteWithVerifiedSession","7e02fbee5efaa38616fdafc3f19ed302a80946fce09be017a2951e1b4def66e5",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":60000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["rejectedGetPublishesAFiniteEmptyResponseAndDiscardsTheUnopenedChannel","9a5a1879012fb97eb6285df8111ad5264dba9ddd3c7c9700204947c140cc493d",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":80000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpLegacySubscriptionPublicRuntimeTests.java", "b5e7d6ae431779f9a9a94b3ef522697364631a12801693c8c21f8b1b3de06660", [
    ["failedFirstPagesAndSuccessfulContinuationPagesPreservePendingCatalogHints","093f3da6544ecc65cffca6a1a471b5ad400c77d516cc3670926c07c58939dc7b",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":120000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["aChangeDuringSuccessfulFirstPageProjectionRemainsPending","0c5dff16425ee74c5cdd5ed87ce6c4fbce61bb2e88e427d2edf68e14a48d9abb",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":60000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["failedDuplicateSubscribePreservesTheEstablishedSessionAndRenewsItsFencedGrant","bb1ec6568822d435260d16143c65b38e8669120d37963c4aef2e77427c2c311a",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":100000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["liveNotificationsUseOneStreamAndDoNotReplayOnNewGetOrReconnect","7e985989d83e7cbba087efbafba921c96def78bd8a80fb8dc25750e9bcc11b79",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":61200,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["duplicateResourceFlushDoesNotShedTheWinningWriter","492c121e4cfd6b4a88aa9c17d8fc920444dfc7212103c190458d0b7c6628e8e5",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":60200}],
    ["duplicateCatalogFlushDoesNotShedTheWinningWriter","0df467edbcc56dbf9eedd81d0eb576c7accbd26323fe1653cdec2bf764008520",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":60200}],
    ["transientUriAuthorizationFailureRetriesWithDeliveryFencedAndPreservesDirtyUpdates","0c09b6a8c5844bfccceb1d4550f394d3049d716ad3bb416e84eb91e247cdb89f",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":92300,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["threeConsecutiveUriAuthorizationFailuresRetireTheSessionAndAllowFreshInitialization","a1305ed04d05a9d4c6f4973067a1eb31cf0c4913be44583fa82b83ebc8ff12f6",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":130000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpSubscriptionShutdownAdmissionTests.java", "2cf1ac1dfcc8a0792e0d76cf633df775dada50a3bea8431cce010492e31ee2be", [
    ["legacyGetPausedInCorsCannotOpenAfterQuiesce","9c91add3dd03c97ac0a5a8ac20bd8c06c134d6c76bfb2029e6c95d9c95ee86e7",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":1000,"gracefulShutdownMillis":3000,"forcedShutdownMillis":1000},"controlJoinMillis":110000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
    ["legacyGetPausedInAdmissionCannotOpenAfterQuiesce","ff84e1b1b4ba6137b0ddadec1281abf5264077cd1cb19bb13b1258ddf88d7751",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":1000,"gracefulShutdownMillis":3000,"forcedShutdownMillis":1000},"controlJoinMillis":110000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
    ["legacyGetPausedInOwnerResolutionCannotOpenAfterQuiesce","7cf2b2874a29ab0cf6791117458c1e7d4e31f255da6e4e4734a91f02f8b38067",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":1000,"gracefulShutdownMillis":3000,"forcedShutdownMillis":1000},"controlJoinMillis":110000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
    ["queuedLegacyGetIsRejectedWhileAdmittedFiniteRequestStillCompletes","7464114f8ee5411136c055feecd3371fcc464b0f941e56e5c328b32c091498cf",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":1000,"gracefulShutdownMillis":3000,"forcedShutdownMillis":1000},"controlJoinMillis":75000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
    ["establishedModernAndLegacyStreamsCompleteGracefully","df9e840152e871a11d31832325f6f279f49f598522ed46009c9df2a1b80ced14",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":1000,"gracefulShutdownMillis":3000,"forcedShutdownMillis":1000},"controlJoinMillis":70000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpHttpServerRequestScopedSseTests.java", "27493b08e8de3ab5db28f52c2f7a8ece54ca336f2b4702a49a832b5a4385d398", [
    ["finBeforeHttpOfferCancelsAllocatedStreamInEveryRevision","eb7a3f9811e7bed92787ab9999015e0d6274778cdae467e2d020ff590035c960",{"generation":{"count":3,"mode":"SEQUENTIAL","complete":3,"prior":2,"incomplete":1},"controlJoinMillis":105000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlledLifecycleCoreMillis":5000}],
    ["response_write_idle_timeout_closes_stream_and_interrupts_handler","ce05b707f53aecfa545cefd9d16ed24a5429b194832a95a9220aa04410e86bda",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":30000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlledLifecycleCoreMillis":5000}],
    ["generic_stream_termination_discards_losing_write_timeout","be746358b1c25ad176dd3d0c19d2c3406e412e18e01b668e6053932ad0c682c5",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":30000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","controlledLifecycleCoreMillis":5000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpHttpServerRuntimeTests.java", "8bec486b523455260f2425ce002e1a491b833771b97ab8cbd0efb728fafc1be1", [
    ["mcp_and_ordinary_http_listeners_are_independent","5bc7a723964f12433646cda6ab8eb82a101607863430ed2d41beabf91d3f8244",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":15000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","controlledLifecycleCoreMillis":5000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpLegacyProgressPublicRuntimeTests.java", "6a8345bfa9965dbfa5c93dd63fb2cb02ae994a20a4016223de1c773f86162b38", [
    ["inputEndDetachesLegacyWriterAndRetainsUncanceledPhysicalWork","3671d0c30b6a2960cbd734c6948ba71c7a6388b3711b8ba600a7601cc853996d",{"generation":{"count":4,"mode":"SEQUENTIAL","complete":4,"prior":3,"incomplete":1},"controlJoinMillis":68000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE","phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":1000}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpSubscriptionAuthorizationSchedulingPublicRuntimeTests.java", "c7fe2bb517a26ae5d1ad5cd4d7399ce0372d435cb3616ded082703af64cb524f", [
    ["renewalWorkersReserveHalfOfTheSharedBudgetsForOrdinaryRequests","b239b43e15a08562c72036b5474b1d38742445c270dd7168773c9baf1e518526",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":1000},"controlJoinMillis":3000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpSubscriptionAuthorizationTerminationPublicRuntimeTests.java", "e0db37fb40adcc31329d729c261ef38f5aae8e03b91976f733b8a63094558553", [
    ["clientDisconnectCancelsResistantRenewalWithoutAuthRevival","1fbdeb5f709ca822568dac954f1dc40a1e07caf6d36889f49728e61e2a32ab69",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":5000,"forcedShutdownMillis":2000},"controlJoinMillis":35000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
    ["serverShutdownCancelsResistantRenewalWithoutAuthRevival","5980221922b2cad417f3d29772ed7d395413bbcab776fc81746bdd28f99a658d",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":5000,"forcedShutdownMillis":2000},"controlJoinMillis":40000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpSubscriptionAuthorizationTimeoutPublicRuntimeTests.java", "6d7a26bebce1de1ad4664a3a83f33bcee112eecc075736b7363d3e1b42bf4344", [
    ["boundedAuthorizationCapacityRejectionDoesNotInvokeCallbackOrLeakQuota","01ac9e50c38326785e9301d736c3df526e433970290cd95c6d9b6c47f53d341b",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":2000,"forcedShutdownMillis":1000},"controlJoinMillis":20000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpTaskSubscriptionPublicRuntimeTests.java", "a09755839331e1ca5206406719f7e0e9b9be48f615de0526e8dbe8dadcd2b3a8", [
    ["taskNotificationBackpressureClosesStreamAndReleasesCapacity","efa70a79e2fbd7bb90d1cfcc07e301aa1c1ff084d2ef79b62790a4552c179e6e",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":30000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpTransportMetricsEventRuntimeTests.java", "bf7438b46b521d74e9046c26b849dcc891316dac8708602711c4d586a5e2369e", [
    ["sameConnectionRecordsAcceptedBeforeRequestAccepted","6ff1379c52770b26d397515448ab7d8af9da0f7e07009effb61d41161f6459ab",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":10000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","controlledLifecycleCoreMillis":5000}],
    ["maximumConnectionCapacityRejectsOnlyAndRecoversAfterRelease","b875795ef960b15c302d3bec4a9222919561d59b804389e7f6aa387c29ad6259",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":40000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","controlledLifecycleCoreMillis":5000}],
    ["partialRequestReadTimeoutIsRecordedWhileIdleConnectionClosesQuietly","ca9cb58cac9161ee2ec82f450dbea165ba26e259b9b2a2af6602006c39b2c2cb",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":20000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","controlledLifecycleCoreMillis":5000}],
    ["partialRequestBodyTimeoutIsRecordedAtTheMcpBoundary","73e0fedf0e6d38cc228d51caafd14fa841632d1642be53cdb4e9c68899ef6ec3",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":5000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","controlledLifecycleCoreMillis":5000}],
    ["acceptAndSetupFailuresRemainPartitionedFromCapacityRejection","670b3d4535c653430baf9ae48ea5e73e7f3998514d27e94dfeacd4aa535c0555",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":8000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","controlledLifecycleCoreMillis":5000}],
    ["boundedTransportReasonsPassThroughWithoutUnboundedContext","51b023be447bf30d831caf9b03cd9b7ded4513d3656ae4cb2143f77db69dd6ed",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":5000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","controlledLifecycleCoreMillis":0}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpCompletionPublicRuntimeTests.java", "b0ad07d430d5afc8af6cd9414ea046c6c4485fbc02c863802193b05348d34993", [
    ["completionUsesExactRoutesAndNeutralErrors","d1bcb27f3e6155aed66eadb25a02eb802506c76a16c42d2365b8c9310795dae8",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":15000,"forcedShutdownMillis":3000},"controlJoinMillis":70000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpHandlerInterceptionPublicRuntimeTests.java", "963799edb19b125f7c92c50808e697c0ba4571ccf4cdbcb16733342bc172612d", [
    ["everyApplicationHandlerUsesOneInterceptorWhileCatalogsBypassIt","861c9db630ee9ba7ef1387202730f21f455e1d2899e4675ec716d897fd1c302e",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":40000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["interceptorMayShortCircuitBeforeBindingAndFailuresFailClosed","cf11d0ca08d8853c26ae2598a893b0cfbf976a7810d8ac81d04b65b7e83fbca5",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":20000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["continuationIsOneShotThreadBoundAndCallScoped","f9f17c981fbfce60ea3f5b18921f8fa3f644193d45bd5b67746b9bd1d6ea6433",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":20000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpLegacySessionHttpRouteMetricsTests.java", "e0cdbb9a47d3d0655e51b89d305a45cab6bc6d184b0ada5f829787afbf5f4e1b", [
    ["acceptedGetAndDeleteUseTheSelectedEndpointAndReleaseTheActiveGauge","e4316fcd8127ed55748592eef684b7d2c99cc9e99858c875be2bc79dd633ec59",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":66000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["idleGetsDoNotConsumeTheTransientObservationBudget","70275daf45fc65385bcd6ba0d29c0e6ae9c396730c9b332d78ea4a6f829cffb0",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":63000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["rejectedControlsRetainMatchedRoutesAndSeparateEndpoints","bfb3ba14f823e04aa70837acdac2c4eb4580c144fd69d0b32d9e1a3eafc11eb1",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":66000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["ordinaryUnmatchedHttpRequestsKeepTheirOwnRouteEvenAtTheSamePath","aaf7a51761d82784f3a905a9c83eebd517a8ec696229f168ed18fada4a065e37",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":18000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["customCollectorsReceiveTheOriginalRequestWithNoInventedResourceMethod","b818094499dfc5b52f199e23c7bc4459671b944f29b8e5318080a4dd4744ccb8",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":18000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpSubscriptionCatalogProjectionPublicRuntimeTests.java", "367cc246acddd7b4db7ee2f026c70477109b4656be2eb777a00fbb671f7b836c", [
    ["coalescedToolOfferAdvancesBaselineWithoutLosingOscillatingChanges","9611981861741f1a4a6821faf6ef67fe239531870041d081466d3c895a964a8e",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":120100,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","requiredAction":"RAISE_OUTER_BOUND"}],
    ["coalescedPromptOfferAdvancesBaselineWithoutLosingOscillatingChanges","ed30f37dce99dc90e9e97574700e729b3c5c4989ec07337df383e668f65246c8",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":120100,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","requiredAction":"RAISE_OUTER_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SimulatorStreamingAdmissionRejectionTests.java", "71bf7711ef28039e3a9ac21eb6e3182b61ddb70d892977dc557c1deec34488a1", [
    ["exhaustedAdmissionReportsFiniteResponseWithoutAcquiringAnyProducerKind","c0c93862332d21aabb9adf33c259d6c4467d2a9a2c11dc794f1399a43ac1ebc3",{"generation":{"count":4,"mode":"SEQUENTIAL","complete":4,"prior":3,"incomplete":1},"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":36000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
    ["saturatedRejectionObservationStillReturnsFinite503AndReportsTheSkippedNotification","c7a64514c60f13a7bbeebf15a00ed13fbf6809bcda334ded147b4a9ad4b00b2c",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":9000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["rejectedRequestFinishesWhileRejectionObserverIsBlockedAndAdmissionRecovers","0f73520750092f81edfbc563b6aea79083ef0f0519a54dff66edd2f88d89415b",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":15000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  // One scoped ReaderFixture owner; retain 16 reset-client connects plus one successful connect (2 s each), and a 3 s read. Callback finalization owns the already-charged stop. No separately parsed latch/future remains, so the unchanged 37 s source-bound allowance needs no control-composition exception.
  ...reviewedScopeFile("src/test/java/com/soklet/Round2SseRuntimeTests.java", "dcd33b32fd31747bf4f2aded44d88503bdb7f25a58ed1d42dd9fa1254809d2ef", [
    ["bytesBeforeResetDoNotReportServerAcceptanceOrTransportErrors","a09451750e13aeeda0b3a4135c6b9d464b4d0604762160e076701dc0ce1220c5",{"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":1000,"gracefulShutdownMillis":3000,"forcedShutdownMillis":1000},"controlJoinMillis":37000}],
  ]),
  // One Fixture owner; a 2 s connect and 3 s deadline poll. No client body read is attempted; fixture close owns the existing lifecycle stop.
  ...reviewedScopeFile("src/test/java/com/soklet/SseHandshakeOutcomeRuntimeTests.java", "6d7781eafe8226c62f3e1ebb303c565c53c085add9660b49aa1d0ec8a8d90459", [
    ["handshakeWriteDeadlineRemainsATimeoutWithOriginalSocketCause","69dbc546d4c61ee0516631b5a5694ddd7f495d9c01a75a73f9b36b9f68b90d27",{"controlJoinMillis":5000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  // The helper marks a manually-created SSE generation ready and never calls server startup. Its only lifecycle wait is default graceful + forced shutdown (15 s + 3 s).
  ...reviewedScopeFile("src/test/java/com/soklet/SseTests.java", "9ce193b4f0e2ec07a6b602600a3171dd27895fbb9db776a3b9c64d9dcab61920", [
    ["socketOptionFailureAfterBufferedRequestAndResetClosesWithoutInternalDiagnostics","c525a8573435041d588d6dd80c82a3ed672fb73f62dc02242784b1d24c08496f",{"controlledLifecycleCoreMillis":18000}],
  ]),
  // The shared helper owns one HttpFixture: 3 s connect + 3 s socket read, 3 s terminal latch, and 5 s physical-exit deadline. Its producer latch is released by the elected timeout; the producer connect is inside the physical-exit deadline.
  ...reviewedScopeFile("src/test/java/com/soklet/StreamingCleanupDiagnosticsTests.java", "022124943ef7b965298b1e27d393df869a7da985eeb79aa356dd8426b4a649d5", [
    ["jdkTranslatedConnectInterruptionAfterCancelationRemainsQuiet","3f52ae74c718a74607cc8e3dcc8550f64687a59353e4ea51d9c45818eb045430",{"controlJoinMillis":14000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  // Same source-bound HttpFixture and control composition, with independent socket failure retained. Fixture close repeats the counted owner shutdown, not another generation.
  ...reviewedScopeFile("src/test/java/com/soklet/StreamingCleanupDiagnosticsTests.java", "022124943ef7b965298b1e27d393df869a7da985eeb79aa356dd8426b4a649d5", [
    ["independentlyClosedSocketConnectAfterCancelationRemainsDiagnosticEvidence","14626bc6205cb3bce0551177566b1844c6c5c2499eef3fafecf41205ad8fb023",{"controlJoinMillis":14000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  // One HTTP Soklet owner with the source-hashed helper policy (10/1/1/1 s), one 3 s client connect, and one 10 s aggregate deadline for all warmed samples. Per-read remaining-deadline checks do not multiply with requests.
  ...reviewedScopeFile("src/test/java/com/soklet/KeepAliveResponseLatencyRuntimeTests.java", "216416edd7da2a44296e61e601e78becb280a7997198465c2c55082ab9c57e0e", [
    ["smallHttpResponsesAvoidDelayedAckStallsOnAReusedConnection","c3dce3b07fcf87f6e9a185f98fe0036c73af2c97738795dbdd439cb6b163fa9b",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":13000}],
  ]),
  // One real MCP Soklet owner uses the same 10/1/1/1 s helper policy, 3 s connect, and single 10 s sample deadline. PersistentClient closes the socket; the containing try-with-resources closes the counted owner.
  ...reviewedScopeFile("src/test/java/com/soklet/KeepAliveResponseLatencyRuntimeTests.java", "216416edd7da2a44296e61e601e78becb280a7997198465c2c55082ab9c57e0e", [
    ["smallMcpCatalogResponsesAvoidDelayedAckStallsOnAReusedConnection","1404371d11fcefbbc07d2feea7c3dc1000a44a4e37a2a10ba6a3b1b8752e2563",{"phasePolicy":{"startupMillis":10000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":13000}],
  ]),
  // One HttpFixture owner with inherited startup/cancelation and 100 ms graceful/forced limits; 3 s client connect + 3 s response read, 3 s termination latch, 5 s physical-exit deadline. Producer connect enters with the elected timeout interrupt already set; its exit is supervised by that existing deadline. The loopback ServerSocket is construction-only and introduces no second Soklet generation.
  ...reviewedScopeFile("src/test/java/com/soklet/StreamingCleanupDiagnosticsTests.java", "022124943ef7b965298b1e27d393df869a7da985eeb79aa356dd8426b4a649d5", [
    ["realNioSocketConnectInterruptionAfterCancelationRemainsQuiet","fa59e836fc6de6dbb36eea3ab167f348a318a20dd5a1483ba250b3a6cb52e318",{"controlJoinMillis":14000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
], 'verification follow-up scope');







// Final deep-dive review: exact sequential-owner matrices and independently
// guarded dynamic nodes. Control allowances bind finite socket reads, callback
// latches and the aggregate observation-pressure loop; null producer gates are
// proven nonblocking. These reviews do not change runtime policy or gate rules.
const REVIEWED_FINAL_DEEP_DIVE_SCOPE_OVERRIDES = checkedReviewMap([
  ...reviewedScopeFile("src/test/java/com/soklet/DeepDiveResponsePipelineTests.java", "de952480441c97c810cc590ec8b943cf1bbf1f95f1cef5dd20593d7ba4262c38", [
    ["decoratorAndConfiguredHeadProjectionUseTheSameCompressionInputAsGet","4de023d4f9957cd053017d7e0eabcddbe1c615d5b21e565be7c9aaa18c708404",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":20000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/DeepDiveResponsePipelineTests.java", "de952480441c97c810cc590ec8b943cf1bbf1f95f1cef5dd20593d7ba4262c38", [
    ["emergencyHandlerDeadlineBypassesApplicationFinalization","30a4621abf2f817fe217926406dd79e65925582c108ba6258001b41418f35695",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":5000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/DeepDiveResponsePipelineTests.java", "de952480441c97c810cc590ec8b943cf1bbf1f95f1cef5dd20593d7ba4262c38", [
    ["emergencyQueueRejectionBypassesApplicationFinalization","b36be4e59424d9bb196124dab01c50703775b966c18f51e6550976e4715c5494",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":11000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/DeepDiveResponsePipelineTests.java", "de952480441c97c810cc590ec8b943cf1bbf1f95f1cef5dd20593d7ba4262c38", [
    ["finalizationFailureUsesCustomThrowableRecoveryWithoutRepeatingThePostprocessor","b56cc381d52d4eb72fd14a92bb193b8215e1c4d557640b0a53db206543a6cdac",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/DeepDiveResponsePipelineTests.java", "de952480441c97c810cc590ec8b943cf1bbf1f95f1cef5dd20593d7ba4262c38", [
    ["finiteSimulationConsumesOwnedChannelsAndPreservesBorrowedChannels","2bce1aae4eca88b9b7bb2d199fa3c6875c926a2734e281648fc56c893fe85585",{"generation":{"count":4,"mode":"SEQUENTIAL","complete":4,"prior":3,"incomplete":1}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/DeepDiveResponsePipelineTests.java", "de952480441c97c810cc590ec8b943cf1bbf1f95f1cef5dd20593d7ba4262c38", [
    ["forwardingMarshalerProjectsThePostprocessedGetRepresentationForHead","0901dea349fd0f53a5b74b54356f6494375015e49a57c60e53b68537c60f237e",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/DeepDiveResponsePipelineTests.java", "de952480441c97c810cc590ec8b943cf1bbf1f95f1cef5dd20593d7ba4262c38", [
    ["lateAcceptedSseFramingFailureProducesAResponseInSimulationAndOnTheWire","2b4991477f4367b99b7a2e03c0f50bb2254b395f240373821a11ee09e702f67f",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":5000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/DeepDiveResponsePipelineTests.java", "de952480441c97c810cc590ec8b943cf1bbf1f95f1cef5dd20593d7ba4262c38", [
    ["lateAndAsynchronousFirstWrapConsumerCannotDispatchAfterFailure","6b356e77241467de226ba9db1b5c0a658903d7bc72eecb39dbe1687b4ba463ef",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":3000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/DeepDiveResponsePipelineTests.java", "de952480441c97c810cc590ec8b943cf1bbf1f95f1cef5dd20593d7ba4262c38", [
    ["rebuildingGeneratedPartsPostProcessesOnlyTheSelectedResponse","7db91cf07600f1921adf43ecb61f5b4a210073036e1dd9e3012ebf06687f2899",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/DeepDiveResponsePipelineTests.java", "de952480441c97c810cc590ec8b943cf1bbf1f95f1cef5dd20593d7ba4262c38", [
    ["simulatedSseCapacityReplacementRetainsAnOwnedPredecessorThroughCapture","6b8f362726a1bf9f717e15f6489e05c3dec31b96c2311febec9841a4a9030c2f",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/DeepDiveResponsePipelineTests.java", "de952480441c97c810cc590ec8b943cf1bbf1f95f1cef5dd20593d7ba4262c38", [
    ["simulatedSseRejectionCleansAnUnsupportedReplacementAndObservesLogicalHeaders","e57fc212e3f10fc9a3e17007e73e3b4a4f361a272b50b5416f9721571c4fdc7d",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/DeepDiveResponsePipelineTests.java", "de952480441c97c810cc590ec8b943cf1bbf1f95f1cef5dd20593d7ba4262c38", [
    ["unusedBodyReplacingCopiesLeaveTheOriginalChannelOpen","c27bfe585362c8b305fec7aa7880c878e9ff06a2e28de01d852ed6ce51f34393",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/HeadReplacementCompressionTests.java", "b490668e56d7b7ab5282539d71496d288bde8207168f7a2cb75c227eb57fe5ad", [
    ["freshBodylessFileAndStreamingReplacementsClearPriorHeadPlanning","8dc20f31a7966d7bb8ea876026b0b5fdee4db504cb67a4188fd3b89c259c68c8",{"generation":{"count":3,"mode":"SEQUENTIAL","complete":3,"prior":2,"incomplete":1},"controlJoinMillis":15000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/HeadReplacementCompressionTests.java", "b490668e56d7b7ab5282539d71496d288bde8207168f7a2cb75c227eb57fe5ad", [
    ["freshHeadReplacementRetainsEncodingRejection","24bd847c3e98de861ae3074c21892ee3de15e2fa33a11b64f242a9b46a8c7647",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":20000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/HeadReplacementCompressionTests.java", "b490668e56d7b7ab5282539d71496d288bde8207168f7a2cb75c227eb57fe5ad", [
    ["freshReplacementPlansTheFinalPostprocessedBodyForGetAndHead","1d16c67b5050af3535d5074d3feb3ac4a25faa4269a8424f4b3fa4caa55ac56c",{"generation":{"count":4,"mode":"SEQUENTIAL","complete":4,"prior":3,"incomplete":1},"controlJoinMillis":40000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/InterceptorDefaultFinalizationTests.java", "71f101610845bc9d9337e9d77a560682edc32e7c57dde9f3e50320372572a0ed", [
    ["freshAcceptedSseResponseReceivesCorsWithoutFiniteFraming","6c111abcad606a3bae41723978aca480f588a4cb43f2cf72631811c577198055",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":2000}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/InterceptorDefaultFinalizationTests.java", "71f101610845bc9d9337e9d77a560682edc32e7c57dde9f3e50320372572a0ed", [
    ["freshResponsesReceiveCorsDateAndHeadHandlingWithoutAPostprocessor","f567c4b56f01abc379a4e3ea41bebda4b0be1618425647cebb2a13e022df406c",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":2000}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/InternalResponsePostProcessingTests.java", "1a0c36b823bee999b3ba3da207ab816679ac0f3d562bc0ab525d7a088137ebc3", [
    ["liveSseRejectionsFinalizeInvalidBodyAndBothCapacityPathsOnce","e150167c16b4bf7547d2daa58f706b3ef72e798efbb79641af1fdf3b8b99e43a",{"generation":{"count":3,"mode":"SEQUENTIAL","complete":3,"prior":2,"incomplete":1},"controlJoinMillis":31000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpLegacySessionTransportCapacityRuntimeTests.java", "fd8dbefbf833dd8b55ae6b655bf2a67e3afa7bbd538322fbe6d45c19df411ff6", [
    ["routineRenewalCapacityDoesNotRevokeTheStillValidGetGrant","621948ba49038b21331d9a58f26c26168f426d946d6ce2410433dfa98d05fd11",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":120000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpLegacySessionTransportPublicRuntimeTests.java", "3f20c67104f7933e75cfe3371921d9a89614f4daba67e63d28d2ca070e3f40ca", [
    ["reconciliationDuringGetAdmissionRetriesBothCooperativeAndStaleDecisions","63a690a5b7b1ba712e91b0a20fb02ae59cb8ea4b01b54121ddb6e8e44c2ca56d",{"generation":{"count":4,"mode":"SEQUENTIAL","complete":4,"prior":3,"incomplete":1},"controlJoinMillis":60000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpLocalizationLifecycleExecutionTests.java", "46bdd9d644b92c831f433d0431a4828fd1c6250aaf347f0acbc81a6f1001d09d", [
    ["localizationAndCompletedTaskPolicyCallbacksCannotJoinTheirOwnShutdown","1a0fad615f5c7d3909c3caac04b91cc355585f0801633e84b88cac7873a0843a",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"dynamicNodeCount":4,"controlJoinMillis":15000,"controlComposition":"REVIEWED_DYNAMIC_NODE_MAX"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpObservationIsolationTests.java", "bf4ecdc5efbfd53fd21c144ec42824902da53766cebe9af999c521b40cb0835f", [
    ["blockedFinishAndTraceLoggingDoNotBlockAnotherConnection","bbab6786d15d27b9192edf3a6dc6bc0a9b356f717950df5b8e69bfad1c303cdd",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":40000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpObservationIsolationTests.java", "bf4ecdc5efbfd53fd21c144ec42824902da53766cebe9af999c521b40cb0835f", [
    ["omittedObservationPreservesCallerAwareCatalogAndHandlerContext","ee39a9c722e125d41160882f7f47ee8127a8dc1a3da90d12ee4997be42ce500e",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":35000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpObservationIsolationTests.java", "bf4ecdc5efbfd53fd21c144ec42824902da53766cebe9af999c521b40cb0835f", [
    ["protocolConcurrencyAndQueueOptionsBoundAdmissionIndependentlyOfHandlerPool","725b8fedb8c4c6ee294eadaa5775409ce237c90e955b9fef490324d88f8b4bdb",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":25000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpObservationIsolationTests.java", "bf4ecdc5efbfd53fd21c144ec42824902da53766cebe9af999c521b40cb0835f", [
    ["saturatedObservationBudgetOmitsPairsWithoutRejectingRequestsAndRetainsAdmittedFinishes","361d5086e0e23b3d9d814c5e1bcfb47cf3cba38cf87b85c4081843bd89ae676f",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":35000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpObservationStartRaceTests.java", "57a43c77ef4d02c280354f9522ad7216ededc597e7d79217a620a633177d7bbe", [
    ["terminalDuringStartReleasesPairedObservationAndPreservesStreamOrder","348ea418626772eaf6c0dad67c4f0b0bb706b3160f05efbf1bb27ce21ba97d4f",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"dynamicNodeCount":8,"controlJoinMillis":45000,"controlComposition":"REVIEWED_DYNAMIC_NODE_MAX"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpOmittedObservationAuthorizationTests.java", "4dd18b828f871024d2cfbf3552b1b2e808d1003d7741b068ca0ff573398ae3c5", [
    ["omittedJune2025UriGrantRetainsItsAdmittedAuthorizationContext","3562e81153fc785821f8eda8fb8bf084340a0dbd2e650afea874ae682f1d340c",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":100000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","phasePolicy":{"startupMillis":5000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":2000}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpOmittedObservationAuthorizationTests.java", "4dd18b828f871024d2cfbf3552b1b2e808d1003d7741b068ca0ff573398ae3c5", [
    ["omittedModernResourceListenRetainsItsAdmittedAuthorizationContext","bfe896fbc0ec41e2cfa5d78ff53faac71557ab83c8632ced963f2700c8e94a6f",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":100000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","phasePolicy":{"startupMillis":5000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":2000}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpOmittedObservationAuthorizationTests.java", "4dd18b828f871024d2cfbf3552b1b2e808d1003d7741b068ca0ff573398ae3c5", [
    ["omittedModernTaskListenRetainsItsTaskAndSubscriptionAuthorizationContexts","ef9235e52bba14fe9c923450d3a846e7b90191884fcc350466439d49174bd916",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":100000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","phasePolicy":{"startupMillis":5000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":2000}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpOmittedObservationAuthorizationTests.java", "4dd18b828f871024d2cfbf3552b1b2e808d1003d7741b068ca0ff573398ae3c5", [
    ["omittedNovember2025UriGrantRetainsItsAdmittedAuthorizationContext","38608ae9df9a9794379124f808319b20c26cbacf4ba3f90ed0e6bc9d73d6c0dc",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":100000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","phasePolicy":{"startupMillis":5000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":2000}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpRemainingSpiLifecycleExecutionTests.java", "9a400c8608daba0d1be19261095c8e5c94004142eb6e9e21af19473e8179945b", [
    ["stateSkillsAndRegistrationCallbacksCannotJoinTheirOwnShutdown","05d0a585ffde0db34e2fc40b009d1500d9062c3280bdc78c970e9555e91d6c44",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"dynamicNodeCount":7,"controlJoinMillis":15000,"controlComposition":"REVIEWED_DYNAMIC_NODE_MAX"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpTaskLifecycleExecutionTests.java", "bc5f30a4e3295d7b7365b91deedc79d50cfcce89bc40e2ce1fd5caec02f79992", [
    ["taskManagerAndSanitizerCallbacksCannotJoinTheirOwnShutdown","db375276e80d161a33ad433b9d98b37911a4de0e4d2f804cb624a17abdc9b149",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"dynamicNodeCount":5,"controlJoinMillis":15000,"controlComposition":"REVIEWED_DYNAMIC_NODE_MAX"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/ResponseFinalizationReleaseTests.java", "ee63842ffa5b9b240b09d6a9cdaa5357b0e66605504fd9be6b0e76b2fe53b58a", [
    ["copyingAnOwnedFileToAStreamDefersClosureThroughSuccessAndFailure","231441642acafb14617283df824c6303ae11deca108137ccba04a776b86f1c72",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":0,"controlComposition":"REVIEWED_NONBLOCKING_PRECONDITION"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/ResponseFinalizationReleaseTests.java", "ee63842ffa5b9b240b09d6a9cdaa5357b0e66605504fd9be6b0e76b2fe53b58a", [
    ["directInterceptorReplacementAndGeneratedCopiesAreProcessedOnce","d5caf463c71b2c1ab4eb9349471a785bcbacc21528adf9cf611e7de8e6a8ba49",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/ResponseFinalizationReleaseTests.java", "ee63842ffa5b9b240b09d6a9cdaa5357b0e66605504fd9be6b0e76b2fe53b58a", [
    ["disconnectClosesAnOwnedPredecessorOfAStreamingReplacement","5ed40c0e206ac3a1f373e92692626738d0bb0a7ea052454d38fd5961e15d67e9",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":10000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/ResponseFinalizationReleaseTests.java", "ee63842ffa5b9b240b09d6a9cdaa5357b0e66605504fd9be6b0e76b2fe53b58a", [
    ["nonIdempotentPostprocessorRunsOnceForGetHeadCorsAndErrors","fc34edc67f081c80556fb9b2fd3f50ecb8295c12e873e6d4eee47bfde6fc3b3d",{"generation":{"count":6,"mode":"SEQUENTIAL","complete":6,"prior":5,"incomplete":1}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/ResponseFinalizationReleaseTests.java", "ee63842ffa5b9b240b09d6a9cdaa5357b0e66605504fd9be6b0e76b2fe53b58a", [
    ["simulatorRetainedCleanupDoesNotHideTheProducerFailure","7a2f68359c05c181bb72ab4a718bc33690a371442c03504654a4baf491d85592",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":0,"controlComposition":"REVIEWED_NONBLOCKING_PRECONDITION"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/ResponseFinalizationReleaseTests.java", "ee63842ffa5b9b240b09d6a9cdaa5357b0e66605504fd9be6b0e76b2fe53b58a", [
    ["wireFiniteAndStreamingWrappersDeliverTheirBytesAndCloseChannels","9efeb107588a6a2eaeba3b9bb7f37a00bef7fab828d63a1dba44796b6929366a",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"controlJoinMillis":20000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SseMarshalerLifecycleExecutionTests.java", "80054992c52ef2c7007cc12afbef18a83b20e816afbc06ff705ff6052ebda3fc", [
    ["noncentralHandshakeCallbacksCannotWaitOnTheirOwnShutdown","ef981c63e3b72c9d82f5c5bedb11af0017d3cce714ac47c5a4af460f30dd1ea2",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"dynamicNodeCount":8,"controlJoinMillis":35000,"controlComposition":"REVIEWED_DYNAMIC_NODE_MAX"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpContextCreationTerminalFenceTests.java", "6971cf74dd48e76852c11b6f986dc08594e2bcc8a6a5a65185a4c1ba4b2c3c1b", [
    ["clientDisconnectDuringOmittedContextCreationPreventsNotificationLimiterEntry","d64d8711f8a0c599926ed5bd9d9f8970bfa39a9e05de0a1d6f1b4ed2e5844268",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":50000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","controlledLifecycleCoreMillis":5000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpContextCreationTerminalFenceTests.java", "6971cf74dd48e76852c11b6f986dc08594e2bcc8a6a5a65185a4c1ba4b2c3c1b", [
    ["protocolDeadlineDuringOmittedContextCreationPreventsNotificationLimiterEntry","3d8504fbfea7af5b3934c3334a199fa5c4227f01d61350eb461f167b98bab651",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":50000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","controlledLifecycleCoreMillis":5000}],
  ]),
], 'final deep-dive scope');

// Round 18 review: one independently guarded owner per dynamic node. HTTP
// cleanup allows two 5-second socket operations plus callback/report waits;
// live SSE allows two connects, two reads and three 5-second control waits.
// Timeout-isolation callers also charge the fixture's 3-second timer join.
const REVIEWED_ROUND18_SCOPE_OVERRIDES = checkedReviewMap([
  ...reviewedScopeFile("src/test/java/com/soklet/HandlerTimeoutWorkerReuseTests.java", "b983a075ef61901ba23b7960d714483c134f07255c78c6d1da83039ac946ec1e", [
    ["httpWorkerCannotRetireBeforeItsTimeoutInterruptIsDelivered","a9167918135ee1051a28ff8c2c7a1f31f8ed4b7425e1a370c888cac24ec4eba8",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":29000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/HandlerTimeoutWorkerReuseTests.java", "b983a075ef61901ba23b7960d714483c134f07255c78c6d1da83039ac946ec1e", [
    ["sseWorkerCannotRetireBeforeItsTimeoutInterruptIsDelivered","80fac63428f586a2398d873989ea9d8a6b841fb6404a383ca92bb947761a2507",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":29000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/HttpChannelCleanupLifecycleTests.java", "ca2fb66718355f4dbfecff81e05385a919e1ff3eb98dec89c54c2d0f4783f453", [
    ["finiteResponseCleanupPreservesLifecycleAndOwnership","9df6293570dceca05cbd02f40daf657438efe092024397533cafa28240eefe96",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"dynamicNodeCount":6,"controlJoinMillis":20000,"controlComposition":"REVIEWED_DYNAMIC_NODE_MAX"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SseHandshakeOwnershipRegressionTests.java", "6404bce21b12408059b5c50d22f1559367a78022c520734f092751e9c46e8510", [
    ["lateCapacityReplacementRetiresOwnedPredecessorsOnly","61c290f2995b446f1dcef612ee91148740de3b159052cf93cb09e0839826cff2",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"dynamicNodeCount":4,"controlJoinMillis":35000,"controlComposition":"REVIEWED_DYNAMIC_NODE_MAX"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SseHandshakeOwnershipRegressionTests.java", "6404bce21b12408059b5c50d22f1559367a78022c520734f092751e9c46e8510", [
    ["acceptedSimulationReleasesPredecessorsBeforeInitializer","3e1728d9878f4868bc4a28e257cfe18590cc1fb662588883d5ceee06ab1c710a",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"dynamicNodeCount":4}],
  ]),
], 'round 18 ownership and timeout scope');

// Unified ready-returning public API: the shared test policy is 5/1/1/1
// seconds. Control allowances include finite HTTP requests and async waits;
// invalid-argument calls reject before the single real generation is claimed.
const REVIEWED_UNIFIED_API_SCOPE_OVERRIDES = checkedReviewMap([
  ...reviewedScopeFile("src/test/java/com/soklet/SokletPublicLifecycleTests.java", "817bc8f8c25e6c74f35b0ab2dc1d0682b9d731a34ff7b385e046986c4dc41d42", [
    ["runReturnsAfterNetworkReadinessAndAsyncObservationCannotCompleteTheLifecycle","f07ec7dbeff2baa4c44a70a2a923f5d6a3303d53d97fefe4075651ea0624705c",{"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":20000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["completionWaitsForApplicationCleanupAndPreservesThePublishedResult","fdb093d712a4f49a1e3968314254bbbe61ee8b09234e868459c1dbaa61d7f539",{"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":20000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["interruptingAWaitDoesNotRequestShutdown","6278b8ab555d552cb945486fabd26b03527078d552690ecbdfaaa07059fecadf",{"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":20000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["synchronousAndAsynchronousWaitsExposeTheSameCleanupFailure","881f7bd258815be7a179308b1635ee10ab3f86494112b15ef8058349c601ea3f",{"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":20000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["cleanupCannotBlockWaitingForItsOwnFinalization","fa48404c7b76362edbee502ca5505f7328806970115d8c532972fb94cb1e4ded",{"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000}}],
    ["startupFailureIsSynchronousAndFinishesEligibleCleanupBeforeThrowing","bc8116353b3214481205f1bc2cf728ad808526f47d7226914049ef62fea207f4",{"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000}}],
    ["nullArgumentsFailBeforeClaimingTransports","1fb3c0745de1d0e594a0faad51ec21478d33a932da294815c9c684c817f875b9",{"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"generation":{"count":5,"mode":"ONE_FULL_PLUS_PREINIT_REJECTIONS","complete":1,"prior":0,"incomplete":1},"terminalReportCount":1}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SokletRunConcurrencyTests.java", "78879b2794a2a8146cbd5c6d695703150f541246f0b64c36c42651ccf9cb198a", [
    ["blockingAsyncConsumerCannotDelayBlockingWaitOrKeepLifecycleOwnerAlive","50c9519b63bd3caad495d7f43c98581a48437ea2b8e639920a3029e1cfa157f4",{"controlJoinMillis":5000,"controlComposition":"REVIEWED_FOREGROUND_RELEASE"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpLegacyProgressPublicRuntimeTests.java", "6a8345bfa9965dbfa5c93dd63fb2cb02ae994a20a4016223de1c773f86162b38", [
    ["firstProgressPrecedesHandlerCompletionAndPreservesExactMonotonicUpdates","0bfbdf6bf83723301e180489e3fa562c77e29982bee8f3e0646d505b2c1a57c1",{"terminalReportCount":1}],
    ["committedDisconnectDetachesTheWriterAndRetainsUncanceledPhysicalWork","c381dc78f53ff5a3cf1d2c1bc84751ee69e92f8d60c723d437d21eaedb30999b",{"terminalReportCount":1}],
    ["inputEndDetachesLegacyWriterAndRetainsUncanceledPhysicalWork","3671d0c30b6a2960cbd734c6948ba71c7a6388b3711b8ba600a7601cc853996d",{"terminalReportCount":1}],
    ["finiteAndUncommittedDisconnectsStillCancelTheHandler","04c6cb7cacd0110edac2c78526b87080f742941e4de169aed0ccfc77046ac8f9",{"terminalReportCount":1}],
    ["detachedProgressWorkStillObservesItsDeadlineAndRetainsCapacityUntilPhysicalExit","54e416999afa50f3a881d11352ed9f061926d0e6619218415358c50b3ff04dc5",{"terminalReportCount":1}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpLegacyRpcStatusTests.java", "8fab1597b3347205b9c1ad1d23a486995158f186589a40b9a54060f98d9f9070", [
    ["legacyOperationErrorsUseHttp200WithAndWithoutSessions","c11e459b661f5089a9a7b13e40b2d20f33d4a19f7aa1e047b70dfcafb4c68c91",{"terminalReportCount":1}],
    ["legacyIconProjectionPreservesCompletedResultsAndRunsTheHandlerOnce","a127663de11635be3a92416d2132493a9aaae030bbf435aa062eb1d4e9d74117",{"terminalReportCount":1}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpResultEnvelopeGoldenProductionTests.java", "259751ad7148dd0b907fba76d766338b39684cf8e4aa4197a4a3718ac0b30ae9", [
    ["everyFrameworkAndApplicationCompleteAuthorityMatchesGoldens","1e0bc148501a1dcd4a1a959c79abb0bc6761f612635942c41f84cbb3a0266517",{"terminalReportCount":4}],
    ["requestScopedAndSubscriptionSseTerminalsMatchGoldens","d8741458332a9ce96f01ac6c30993618baf55a0841030d45a3e945ce47577ab9",{"terminalReportCount":3}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpSubscriptionAuthorizationPublicRuntimeTests.java", "0926904d5dc0fcf8bb2b1f374008c12886aabd28f3e975d80642d5ed34a5a243", [
    ["renewalDenialCompletesTheListenAndPreservesItsReason","44b7d7254e0f3d0d46c9d09e37581395c4e2fd904d6cde13003271e14c4166f9",{"terminalReportCount":1}],
    ["reconciliationDenialCompletesTheListenAndPreservesItsReason","4f2cbaf5059133a3ce56da40485c2b100b0aa3499eb7ef858852324436018268",{"terminalReportCount":1}],
    ["renewalFailureCompletesTheListenWithoutDisclosingTheException","37705ac22a66283c39427084121543ea4dda4af51d12f18be2ccab6d2ee3e7b5",{"terminalReportCount":1}],
    ["reconciliationFailureCompletesTheListenWithoutDisclosingTheException","579d4053275f7ff159182184013eeaaa4167ecbd00b3cd90cf760a9ce7e3daeb",{"terminalReportCount":1}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpSubscriptionPublicRuntimeTests.java", "f34e2d1755405f60630f884641b2d4761916bb354f3076c8fcf678bbf49044c0", [
    ["validListenUsesAdmissionAndRequestLimiterOnly","f107f5c9e9d1456283a6b3b75e4431d026c9269980fb55b0f5f5a58cabd83345",{"terminalReportCount":2}],
    ["nullAndThrowingAdmissionNeverActivateOrConsumeSubscriptionQuota","ae474f2c1b72114791c81d1f865b1560d7d9327e993b6e2e1eabea075c0906de",{"terminalReportCount":1}],
    ["publisherIdentityIsGroupedPerServerAndSharedAcrossServers","e472a11ec8fb6f5a657074c2b55870ae7ad80d49d9b8924fc6bdd94aa8f24889",{"terminalReportCount":3}],
    ["gracefulHttpShutdownEndsWithOnlyTheTerminalCompleteResult","4b054c2eaeca9da3a17bcc51fe0fa7c5d76b106d10c7aace497052461f07b9df",{"terminalReportCount":2}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpTaskSubscriptionPublicRuntimeTests.java", "a09755839331e1ca5206406719f7e0e9b9be48f615de0526e8dbe8dadcd2b3a8", [
    ["acknowledgedTasksSurviveTransientMaintenanceLookupAndUnsupportedInputGenerations","8d58216ed69f499dd809b421bdef500713fa72446df2eee75b1d9595b4ba0b91",{"terminalReportCount":1}],
    ["missingOriginSuppressesCompletedNotificationWithoutMutatingStoredTask","edcb1438534e66bec84f5e9027e0151f73f031cf59cc7fa13ff234ff691e2b76",{"terminalReportCount":2}],
    ["sharedTaskPublisherRegistersOncePerServerGeneration","9bc364fc446884558eec5cf978e3dd3798c53a3238eca9d86c2d1bbce0dda77c",{"terminalReportCount":2}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/internal/mcp/protocol/McpWriteIdlePublicObservationTests.java", "34ca609c1b597158a90bb3c31cc400644bd0c17da5d0add1fc22ef4b9b1d0ed6", [
    ["stalledWriterIsWriteFailedBeforeTheRequestDeadline","aa0c79e13ae956d2bd58454fa8af6c858d63afd6aa784748b3928a4073726ae4",{"terminalReportCount":1}],
    ["expiredRequestDeadlineRemainsDistinctFromWriteFailure","c9f2ce386663f7cb60a432bc0f7fc714cd4da4fd1f32a5068e857acbcb64062c",{"terminalReportCount":1}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SokletPublicLifecycleTests.java", "817bc8f8c25e6c74f35b0ab2dc1d0682b9d731a34ff7b385e046986c4dc41d42", [
    ["startupCancellationSurvivesAnIdentityGetterConsumingInterruption","ab31420db27843b2d17ea0dab6e9a9134b681f2b86030f1c2fc82298d2dde2a1",{"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":1000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"controlJoinMillis":10000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  // Dispatcher/owner launch failures reject before transport ownership. The
  // subsequent successful run is the only full lifecycle and terminal report.
  ...reviewedScopeFile("src/test/java/com/soklet/SokletManagedShutdownRegressionTests.java", "51b7255c5b819fb07d1f998b5c9fafa13ae0435b2223d122f71b5ad637e5dc62", [
    ["dispatcherReservationFailureDoesNotClaimTransportsOrLaunchOwner","787f906b5a76b995907ea2e8eedf28f5b2d8ae156b61c0986e95ebc72e7b888c",{"generation":{"count":2,"mode":"ONE_FULL_PLUS_PREINIT_REJECTIONS","complete":1,"prior":0,"incomplete":1},"terminalReportCount":1}],
    ["ownerFactoryFailureReleasesReservedDispatcherWithoutClaimingTransports","4c7a385e3da8203767c0e0673f4d28eb0c95fb05a91f1245e105a30b20859637",{"generation":{"count":2,"mode":"ONE_FULL_PLUS_PREINIT_REJECTIONS","complete":1,"prior":0,"incomplete":1},"terminalReportCount":1}],
    ["ownerThreadStartFailureReleasesReservedDispatcherWithoutClaimingTransports","849e28aff3f557cbbc914d480d520b8915e7f1b175a8c46c5d98cf866784dd42",{"generation":{"count":2,"mode":"ONE_FULL_PLUS_PREINIT_REJECTIONS","complete":1,"prior":0,"incomplete":1},"terminalReportCount":1}],
  ]),
  // Console-warning startup work was removed. The two remaining run sites are
  // mutually exclusive; portable denied-owner mode uses six five-second controls,
  // while worker-only child branches account for the broad45s source census.
  // Preserve the prior40s reviewed control allowance and5s cancellation horizon,
  // full30/1/1/1s phase policy, and both possible cleanup/report sites.
  ...reviewedScopeFile("src/test/java/com/soklet/SokletStartupCancellationRegressionTests.java", "2d2766b8e690fe92ac63c913bcb206e266c2ae932698b51db082a01767a610b4", [
    ["startupCancellationDoesNotDependOnInterruptingTheProcessOwner","5ffda907501ff1b6555131bab25cef3de661249e26fc57f199df208a5006c8de",{"generation":{"count":2,"mode":"CONCURRENT_OR_ALTERNATIVE","complete":1,"prior":0,"incomplete":1},"controlJoinMillis":40000,"controlComposition":"REVIEWED_OVERLAP_OR_DUPLICATE","controlledLifecycleCoreMillis":5000,"applicationCleanupCount":2,"incompleteBranchCleanupCount":0,"terminalReportCount":2}],
  ]),
  // Each public interrupt-close regression retains the entire 30/.1/.1/.1-second
  // lifecycle plus cleanup/reporting. Six five-second controls and two bounded
  // channel-worker joins compose sequentially in a forty-second allowance.
  ...reviewedScopeFile("src/test/java/com/soklet/SokletInterruptDeliveryRegressionTests.java", "184ee305ddad0236515f25853819c03b4e470fa00cfc0feab9fa4aff4c4910d1", [
    ["startupCancellationSettlesWhileInterruptCloseRemainsBlocked","29d8dde41e56c7f57272fd5f428a24fac4a7e67b0921d836db11c5daebea64ae",{"controlJoinMillis":40000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["cleanupTimeoutSettlesBothAwaitMethodsWhileInterruptCloseRemainsBlocked","ff41534160741297c3cd658a0a1fc00fbca7641f9d472605fc0518e041e316a6",{"controlJoinMillis":40000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  // The four accounting races retain all 30/.1/.1/.1-second lifecycle phases,
  // five-second cleanup, seven five-second foreground controls, one shared
  // five-second worker reap, and 250ms manual-finalization reporting. The two
  // pre-action reservation failures retain the same phases/cleanup plus only
  // that shared five-second reap and 250ms report; no phase allowance is shaved.
  ...reviewedScopeFile("src/test/java/com/soklet/SokletSyntheticInterruptAccountingTests.java", "c0f2578ba90c524014173d83919e1ea079ea657568ae45e12b9eaadd2ff1f674", [
    ["completedSetupRetainsBlockedInterruptDeliveryAndSkipsCleanup","937c6fdae0f3719991d447364364a26b370cf8ab0fffad166f68819494725af7",{"controlJoinMillis":40250,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["completedAttachmentRetainsBlockedInterruptDeliveryAndSkipsCleanup","feb58c95d9305de7f8668145206c653f791b3ecb0b44b9473d7e33a43ab2a202",{"controlJoinMillis":40250,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["retiredSetupInterruptDeliveryAllowsCompleteShutdownAndCleanup","c4db79d04c12ea8496c90ad307fb1921d3a2e8aef0d6ae6aa59fb0dfa950e79a",{"controlJoinMillis":40250,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["retiredAttachmentInterruptDeliveryAllowsCompleteShutdownAndCleanup","41ebf537248dc658486cbf8e28898de2892699d62f97533ba45ea208ca315067",{"controlJoinMillis":40250,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["setupReservationFailureDoesNotInventResidualStartupWork","8ad25c888396a904a04bc60bdc1bebd74d670578d07c748ac8182a8fa1c5c707",{"controlJoinMillis":5250,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
    ["attachmentReservationFailureDoesNotInventResidualStartupWork","f90c092cd4478d5fe5cace5693c55dfe48295f44d4f8c8fe8e8deefb7bed7377",{"controlJoinMillis":5250,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  // The held-exception regression retains the entire 5/.1/.3/.3-second
  // lifecycle. Four five-second foreground controls, the caller join, one
  // absolute five-second startup gate, and one shared five-second worker reap
  // compose sequentially to 35 seconds under the ninety-second JUnit guard.
  ...reviewedScopeFile("src/test/java/com/soklet/SokletFailureEvidenceMonitorTests.java", "e77335fae927d5aa7571464cbea553bbd44c2a65b75bd5965e160c172844d13b", [
    ["canceledStartupAndCatchUpFailureFinishWhilePrimaryExceptionMonitorIsHeld","bcd36928977252984482f34f75aee058d69d0e264e4df15566e09ed26ba966ac",{"controlJoinMillis":35000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
  // Scoped callback return owns shutdown implicitly: keep all5/.1/1/1s core
  // phases, eligible five-second cleanup and one terminal report. The invalid
  // argument test has four pre-initialization rejections and one full run with
  // no cleanup. The interrupted-cleanup test adds a five-second gate to its
  // three explicit five-second controls; no body borrows a lifecycle deadline.
  ...reviewedScopeFile("src/test/java/com/soklet/SokletScopedRunTests.java", "889d76447298b00ebbe47e700c079039a1954a66646a4f5e4637cc7d52713499", [
    ["invalidScopedArgumentsDoNotClaimTheTransport","302c1f0d7e9a30e366a55e2a3bae953bd8925fab734755a1bfe746ff0c709475",{"generation":{"count":5,"mode":"ONE_FULL_PLUS_PREINIT_REJECTIONS","complete":1,"prior":0,"incomplete":1},"applicationCleanupCount":0,"incompleteBranchCleanupCount":0,"terminalReportCount":1}],
    ["interruptionWhileJoiningCleanupCannotEndTheScopeEarly","1f9f37571e2cac4f0bf0e2d55624223b1368c7764c4fc748ca5e5a86bc33ec04",{"controlJoinMillis":20000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND"}],
  ]),
], 'unified public lifecycle API');

// Ordinary integration scopes now own public process finalization. These
// source-bound rows add one250ms terminal report per public network run, without
// changing any lifecycle phase or control allowance. Mixed HTTP/simulator loops
// retain all existing generation bounds but report only their network branches:
// output/resource ownership tests have one public run, checked-source tests two,
// and the mixed interceptor contract has one. Dynamic factories retain one report
// for each independently guarded node. No simulator borrows a process report.
const REVIEWED_SCOPED_INTEGRATION_SCOPE_OVERRIDES = checkedReviewMap([
  ...reviewedScopeFile("src/test/java/com/soklet/DeepDiveResponsePipelineTests.java", "de952480441c97c810cc590ec8b943cf1bbf1f95f1cef5dd20593d7ba4262c38", [
    ["decoratorAndConfiguredHeadProjectionUseTheSameCompressionInputAsGet","4de023d4f9957cd053017d7e0eabcddbe1c615d5b21e565be7c9aaa18c708404",{"terminalReportCount":2}],
    ["emergencyHandlerDeadlineBypassesApplicationFinalization","30a4621abf2f817fe217926406dd79e65925582c108ba6258001b41418f35695",{"terminalReportCount":1}],
    ["emergencyQueueRejectionBypassesApplicationFinalization","b36be4e59424d9bb196124dab01c50703775b966c18f51e6550976e4715c5494",{"terminalReportCount":1}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/HeadReplacementCompressionTests.java", "b490668e56d7b7ab5282539d71496d288bde8207168f7a2cb75c227eb57fe5ad", [
    ["freshReplacementPlansTheFinalPostprocessedBodyForGetAndHead","1d16c67b5050af3535d5074d3feb3ac4a25faa4269a8424f4b3fa4caa55ac56c",{"terminalReportCount":4}],
    ["freshHeadReplacementRetainsEncodingRejection","24bd847c3e98de861ae3074c21892ee3de15e2fa33a11b64f242a9b46a8c7647",{"terminalReportCount":2}],
    ["freshBodylessFileAndStreamingReplacementsClearPriorHeadPlanning","8dc20f31a7966d7bb8ea876026b0b5fdee4db504cb67a4188fd3b89c259c68c8",{"terminalReportCount":3}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/HttpResponseStreamTerminalRuntimeTests.java", "f74d8bdb27240b8caeaba029ba4a3e5e87a1d7726fec21ad9088a1567ba749f4", [
    ["http10AndHeadRemainFiniteAndDoNotCountOriginalStreams","d046c3fd65d7fa2e39a7f0d34353601a174dcfff91fa652d8da534c6c0b0a715",{"terminalReportCount":2}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/HttpTransportResponseOutcomeTests.java", "60fb95984e6a8e4d2ff38601a0711d19c0ad261b3e42bffabb1d4355beba0696", [
    ["notWrittenFailsDirectAndOpaqueConsumersWithoutClaimingSuccessOrReplacement","4540c7c3c5c8934cf1e561aeabb1e1e8453915a4f09e19bb694662dbfdc25be8",{"terminalReportCount":2}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpContentValueBoundaryPublicRuntimeTests.java", "cb62610a4806a0fcc7a37f38c8b042a3ecdeb7657eca1ff7b3b324b33a9570bc", [
    ["contentBoundariesFailPrivatelyAndRecoverForEveryRevision","636e576d012ae7ded4d14123e52bf229ccadf5068f324536bce1bc59b5fc8271",{"terminalReportCount":1}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpHandlerInterceptionPublicRuntimeTests.java", "963799edb19b125f7c92c50808e697c0ba4571ccf4cdbcb16733342bc172612d", [
    ["interceptorsObserveAndRethrowExactHandlerErrorsOnEveryRevision","2fe9cb5c954424e0915585b8762d547df8aaa430291702aee0e6871c33a60206",{"terminalReportCount":3}],
    ["interceptorCanRecoverHandlerErrorsThroughNormalResultValidation","0de6db19ded316a10adf98ce6431d7329ec6558db270afd571b1f007ebfb54a3",{"terminalReportCount":3}],
    ["copiedWrappedStaleAndInterceptorAuthoredErrorsStayPrivate","9ca7efa1f3e7c16882f5f206caafc5364a4979c724f6416e1fe49554ea26fc96",{"terminalReportCount":3}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpIconPublicRuntimeTests.java", "8eccfdd046b428bc1980ef9f09b95df5a052af4f43bc68cca5c23292833c437f", [
    ["iconsKeepExactValuesAndRespectEachRevisionProjection","45d7d930b9c7c52a025ac70309d7333a76f8d839e1c981d582cfd1b387839520",{"terminalReportCount":1}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpJsonAndSchemaLimitPublicRuntimeTests.java", "2eef903d670c010d70fa36d1b63107db4a8287136a7d0ef1b104dce03ab71d1d", [
    ["limitsFailSafelyAndRecoverForEveryRevision","e3ebcfa30fcc56701ede80ee92d61a873e4248f6f5c9056c15c1ceba288a309b",{"terminalReportCount":1}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpResourcePublicRuntimeTests.java", "bf34e3764100e30b15d56245ce06e21b5bb3d48161fdd6c61629391438fff4a5", [
    ["fileTemplateVariablesEncodeSlashesAndDecodeExactlyOnceOnEveryRevision","fec6217cc0553ab160c138d449f6e927ee2e9e373fbf98a5fc14d53822ee5d31",{"terminalReportCount":1}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpTaskCapabilityBoundaryPublicRuntimeTests.java", "8548b8831c5bbd3865ef94a9e88e473ee1045a2c10eec3ac171b5d7e905e21c0", [
    ["advancedTaskReturnsWithoutCreationContextAreInternalErrors","f01f23752f80185b828711a70c5d0755e8fc13c53c4b7c52443ee61067283878",{"terminalReportCount":1}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpTaskInputCapabilityPublicRuntimeTests.java", "da9afd6f3d42d2116b98ff87092d6afa92172a397f411f1f4da4b9c3624090d4", [
    ["everyOutstandingModeRequiresCurrentRequestSupport","4cd38945a4a4c4c4583ca8fa9a4d6c6a7bc88ae9930e1b4a0e065c057181aaf8",{"terminalReportCount":1}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpToolInputErrorPublicRuntimeTests.java", "051c52f10b4306302e72933d4b2f7acaecf78646607f30493cdbdc5c60f4834d", [
    ["toolInputFailuresFollowTheSelectedRevision","e472b0198e55dcb82eba57b145d5a4cc0a96b3e3956eaabaf9ef0bc6ff6f9a87",{"terminalReportCount":1}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpTypedIntegerLimitPublicRuntimeTests.java", "7f9c5e31d2f924056d3b8cc3141a72891d79d372f04c5c97b64841e83a42d62d", [
    ["boundedIntegerBindingForEachRevision","c8592af11a532247ebd1c691342d495e672fbd85c1b9fb954e4579acc5ed38e1",{"terminalReportCount":1}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/ResourceMethodBindingValidationTests.java", "c6d4dae27206b4a10a47020118718b159e5e73683592bc2f7283a5f26f412566", [
    ["defaultBindingFailuresAreRejectedBeforeStartupOrInstanceAcquisition","d8187bcc1d4f0e2db369cf3657eac9a4288fed6e184ee4229a3e3a45616c8ae0",{"terminalReportCount":4}],
    ["incompatibleDefaultConstructorsFailDuringSetupWithoutConstructingAnything","1c7c0aa57449b1c38c02d7087e5baa3acd695cfe66d6a19b9e19032bbf343368",{"terminalReportCount":3}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/ResponseFinalizationReleaseTests.java", "ee63842ffa5b9b240b09d6a9cdaa5357b0e66605504fd9be6b0e76b2fe53b58a", [
    ["wireFiniteAndStreamingWrappersDeliverTheirBytesAndCloseChannels","9efeb107588a6a2eaeba3b9bb7f37a00bef7fab828d63a1dba44796b6929366a",{"terminalReportCount":2}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SimulatorStreamingContractTests.java", "b1f6e4656443a02f69095ff4af2fa7060e58afc04352d731c68b25731195f1fe", [
    ["interceptorOnlyStreamSupportsAbsentResourceMethodInBothRuntimes","6c0e66399c0e7e3e92283be256173ab7c30d1319e100e2c38199690a997e2469",{"terminalReportCount":1}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SseReviewRegressionTests.java", "15fb13fae72a10709e965b3b74a73f15aed17990749366e9ff88b7b8d17a2779", [
    ["cleanAndResetDisconnectsReleaseCapacityBeforeTheHeartbeat","2d436dd7f53327ceb9a7f657c9401356be0aa3fa1389876c61fc306ad964e550",{"terminalReportCount":2}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/StreamingOutputViewRuntimeTests.java", "e67342f712d5ebf16f66d3504d36949e9a898509f9bdb0048af471214456a564", [
    ["mixedOutputKeepsOrderAndCopiesCallerBuffersBeforeReturning","77592c2347e5579a43630321981a0b7c437b96e5bafc95137fdd35151c25aebb",{"terminalReportCount":1}],
    ["invalidSlicesDoNotPoisonOutputAndEmptySlicesAreAccepted","089728c6b1aa8a70dad1ddaea8eb56c2d855109117233ff16d83facfb54dbfa5",{"terminalReportCount":1}],
    ["closingOneViewFlushesItAndLeavesOtherOutputUsable","924a042fe4b376e2523e659e5b123eb9beb2862e895e891201d1478a9f425218",{"terminalReportCount":1}],
    ["ownedZipFinalizationEmitsAReadableCentralDirectory","da5fbe788ea151a7cd87b913834d3081a121f0ab253fe6d3d1fa874860a94d5a",{"terminalReportCount":1}],
    ["ownedUtf8WriterFlushesAndEmitsBufferedTextDuringFinalization","bab937720039e78b3f1bb4f622a2d0fe4ba447485b65a8af076e105347d5cd13",{"terminalReportCount":1}],
    ["viewAndNativeLifetimeChecksPreserveTheResponse","acf3df8de238e85a3f8cea10d9651148d40bbc345dedb767f57f92e15312a175",{"terminalReportCount":1}],
    ["failureAfterWritingFinalizationBytesCannotReportSuccess","b982fa5ba33c571904769ddd0ca67e1436353b05ae2b52172bb6c8c52a74513a",{"terminalReportCount":1}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/StreamingResourceOwnershipRuntimeTests.java", "ca40e320e414eab27019d94c788f11c5616ed5f93aff32cabc230e217ea586d9", [
    ["rootCleanupRunsInReverseOrderOnOwnerAndCanWriteAfterWriterReturns","afd4e0628db8fdab9855d53e02a85925f02c3a4c9eceeafa068d5411901e57e4",{"terminalReportCount":1}],
    ["nestedUsingClosesItsChildrenBeforeOuterAndRootLifetimesEnd","06f234e19d07e20af721092fa6b2753efa19def3871a6d31dae67ff4cb0a2668",{"terminalReportCount":1}],
    ["duplicateActiveOwnershipIsRejectedWithoutDoubleClose","58a30e89069b819bb7aa7bb11a531b9557efc766da26dcf1eeea8310e3ccba3c",{"terminalReportCount":1}],
    ["ownerChecksAndClosedScopeChecksRunBeforeAcquisition","88f796ea844036ea606713e2efd5a1332c7a233eb2efbe11ba4131c05bd0d6e6",{"terminalReportCount":1}],
    ["headSuppressesWriterFactoriesConsumersAndCallbacks","c6e98b0876593a122ee3f722e827898d7ce96b30d7147bc0998ebe347ec60e42",{"terminalReportCount":1}],
    ["producerFailureRemainsPrimaryWhenCleanupAlsoFails","785cc96aa16643b111c8159281bfa40d1fafaf294a2adbff0fac563feac13469",{"terminalReportCount":1}],
    ["cleanupOnlyFailureCannotProduceSuccessfulCompletion","75ba9b1186aec914662968f237db777ffbf0372fb4b9887b544ea82000d2bc5c",{"terminalReportCount":1}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/StreamingSourceFactoryRuntimeTests.java", "9306bd9684ba8a22cb5b806a2764ef08e3828497149c521f4578f622aceaaee7", [
    ["checkedSourcesAreLazyAndReopenedForEachExecution","704f48192a274117ac97554f624510a2d711ca10322f63b2f378eba7eee1de0d",{"terminalReportCount":2}],
    ["checkedAcquisitionFailuresPreserveTheirCause","f0067cedd1fb014777e15d68fcc373d43ea33a6883ad7c3354831e369d0536c5",{"terminalReportCount":2}],
    ["nullSourceIsAProducerFailureInBothRuntimes","0af5c9c436091f9afb12f4c06fdb351c2042efd8466426b21bc2db4179c16395",{"terminalReportCount":2}],
    ["headDoesNotInvokeCheckedFactory","031539ead8407a0e16579d1f04b79a311ad4eb84857fe1f5d0decead9a68060f",{"terminalReportCount":2}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/UnparsedRequestTransportTests.java", "bf4c7a9ae5883e5bef6ac33c179a23c0db13252c6c39d4445d85ed5fa7bddc99", [
    ["timeoutsAndUnsafeExecutorDispatchUseTheBuiltInFallback","32855da54b8584e59500cd90c9cb34c6e6fac69563c60f0bdba821d33d1bbc81",{"terminalReportCount":5}],
  ]),
  // Former manual fixtures likewise retain one report per exact network owner.
  ...reviewedScopeFile("src/test/java/com/soklet/FiniteHttpResponseSafetyTests.java", "03d6347361337f683410ce687066f3125c6817079136f6435b2053057af3d465", [
    ["interceptorAndCustomMarshalerCannotBypassFinalStatusValidation","420d8f037437d51ce56478ccd1338fb13790eba47fb36b5b0b0aa37f788cda5f",{"terminalReportCount":2}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/HttpStreamingProtocolRejectionTests.java", "71a37065b172ca04cee6286f710feee51081706c4181bf1f06e407082567137c", [
    ["httpOneDotZeroRejectionReportsActualResponseAndRetainsOriginalStreamHandle","6138e199cf69d5e4a19be6e24b6e694fbf5c94e69a7598322517ae099c4b15bd",{"terminalReportCount":4}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/InternalResponsePostProcessingTests.java", "1a0c36b823bee999b3ba3da207ab816679ac0f3d562bc0ab525d7a088137ebc3", [
    ["liveSseRejectionsFinalizeInvalidBodyAndBothCapacityPathsOnce","e150167c16b4bf7547d2daa58f706b3ef72e798efbb79641af1fdf3b8b99e43a",{"terminalReportCount":3}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpAdmissionOrderingPublicRuntimeTests.java", "d516a1be6181f27a79ed0351a205e775a8fbc5eb155b15f8960198f86a47852f", [
    ["unsupportedVersionsCannotProbeRegisteredCustomHeaders","4cca94994eb6ca1ded9f80a54915517b3d861b2a4504b1c5e32b15b479b2e28b",{"terminalReportCount":2}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpPreAdmissionMetricsEventPublicRuntimeTests.java", "f004f6b39b3feb7edbbeea503a85fe638bb0c0e4d9918a70ad3c169e56b01a99", [
    ["unknownHeaderOccurrencesAreExactRedactedAndMethodBoundedAcrossPolicies","8822804c199515031a714f867f41d8a3adbfec87f757b0d8db1a5f747d65167b",{"terminalReportCount":2}],
  ]),
  // Expanded MCP and streaming fixtures preserve all core/control bounds.
  // Completion parity runs two network owners and two simulators, so only its
  // two network reports are charged. The executor dynamic factory conservatively
  // retains all six public callback paths as reporting allowance within each
  // independently guarded node; no lifecycle or reporting budget is omitted.
  ...reviewedScopeFile("src/test/java/com/soklet/McpAnnotatedToolProcessorRuntimeTests.java", "518a920a45ca6cbbdfab35c9229f6e98fe99260731eff94c1d7c0e7f8252ffb2", [
    ["generatedProviderUsesOnlyPublicApisAndDefersInstancesUntilInvocation","580bca8d7e72c7b74ca6c35f093bd87b9b5441977347c908e2ff43c0215e724a",{"terminalReportCount":2}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpCompletionPublicRuntimeTests.java", "b0ad07d430d5afc8af6cd9414ea046c6c4485fbc02c863802193b05348d34993", [
    ["onlyCompletionHandlerErrorsAreClientVisible","51570aa5f0730ec2353ac8462b3a6bfca47f87d282f2e114fb192b3852b9fc5c",{"terminalReportCount":6}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpCompletionWireParityTests.java", "727db178173dc5ef21945db24210f79f0d82fed69de767424c1fe92898417dca", [
    ["empty","c8040defb4386c409d195e45a0a1b82e3f4f11c9fc8ff46a02a3513bd43b86d0",{"terminalReportCount":2}],
    ["hundred","fa65d224bdfc69aaa4f2ec9fa8e2f6600be6a4c5d79c3471a116c73bc38082cc",{"terminalReportCount":2}],
    ["totalOnly","b122bf07ed6130f6e34313bd0e488b3bb90eef8e25f2e03d377d49163971203a",{"terminalReportCount":2}],
    ["moreOnly","bbad1f7ef5022120952d971c7f440149cbdeea036e35f5cafa1ed98d2eafcd92",{"terminalReportCount":2}],
    ["both","e6be21a180a9b116de2243e9e1cdc32d46c0d7530fcf722eb3a4d5f1fa2c90df",{"terminalReportCount":2}],
    ["maxTotal","8c9aa28deed0a14c199c34faa99b473029e0e6e591e84e2a439e4a590fbd088e",{"terminalReportCount":2}],
    ["tooMany","e2e076085aa774a292c0be2af0322e70aa7a3c402a67a56a3df2ba2f11624773",{"terminalReportCount":2}],
    ["negativeTotal","99f62c20f8e5e9acd4f373d5117d5f5f3ddc54cdfc834e554998f73520a9c578",{"terminalReportCount":2}],
    ["unsafeTotal","c75735ed188ab3a369ad41d8afca4bace5b9c51549aee607b0433505edbaf9eb",{"terminalReportCount":2}],
    ["belowSize","4dcf4fbe3de680d98019801f71aaa9c9d0c5362a3f347c93bb411084fc56020c",{"terminalReportCount":2}],
    ["inconsistentMore","f322d9acdfef8b1cc0d85e02893bc3b20ebd00566f367b03b9b56297986435db",{"terminalReportCount":2}],
    ["inconsistentComplete","840a5dfd12d9a8e92b2bf672a073307de9db2a6be777c06bac05c49087fb8709",{"terminalReportCount":2}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpDeferredTaskOutputSafetyTests.java", "0216f5397bbe7dbe479f0290943d408442769b83821139a6773886a5014f5b14", [
    ["completedTaskReadFailsWithoutDisclosureWhenItsOriginToolIsNoLongerRegistered","ee8715459b5ad569c3fd8cce536313f93048831be06854eb46320ae2a040f3bf",{"terminalReportCount":2}],
    ["nonCompletedTaskReadsDoNotInitializePolicyOrLocalization","e3efa72d3b6b9ff4fc04d18fd3794d0b317f7a6e0fb00b0210d0cc149477195f",{"terminalReportCount":4}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpExtensionCompatibilityPublicRuntimeTests.java", "a7da85112f16e9edc67568acfc45375090f647cf4452d45e026b59d721a781b1", [
    ["taskCreationContextRequiresConfiguredManagerAndNegotiatedToolCall","7c3c927d342ec1ee99a1709d5c56cb71d89a5a19287957059d45514b003a1c74",{"terminalReportCount":2}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpHandlerExecutorPublicRuntimeTests.java", "4c151cf27d0b1d3274443f7dba1f878ed5e7d311813ffff7e71a8dfdb835d5a5", [
    ["executorBoundariesForEachRevision","6a4fa0a44cb843679eacf13904b13f49f7bd5146d99bb9263675780cdaedae3d",{"terminalReportCount":6}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpLocalizationInitializationPublicRuntimeTests.java", "150f5d8e438c88f64a284c8aa2952c00348854be8a357e3eca7135595a67766c", [
    ["initializationLocalizesOnlyRevisionSupportedText","b155f96d860d020d2d254418249f27fc1ce20a9cd792dde24a09b90c2a35461b",{"terminalReportCount":1}],
    ["failedInitializationLocalizationsReleaseSessionsAndCanRecover","7776b245c2d71ab58abc8d5f403bfbcd7ad038bdc48ea4723f73f1ab08e73840",{"terminalReportCount":1}],
    ["wholeResponseFallbackAndNoLocalizerPreserveCanonicalBytes","9eb2464cbcf9da78c76aa97d05519b6e9d7a311e72acf10beb68539ced3efaf9",{"terminalReportCount":3}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpNumericRequestStatePublicRuntimeTests.java", "7106780363dcc0c9730aaff0cf282eeba4451be633012b910e0ed290e2745b31", [
    ["canonicalNumericStateHasStableEqualityAndHashLookupsAcrossTwoRetries","dc9501b782db44c418dd6d27095a5ca22b900b2ee389513673bc360a4ce6c279",{"terminalReportCount":2}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpObservationIsolationTests.java", "bf4ecdc5efbfd53fd21c144ec42824902da53766cebe9af999c521b40cb0835f", [
    ["blockedFinishAndTraceLoggingDoNotBlockAnotherConnection","bbab6786d15d27b9192edf3a6dc6bc0a9b356f717950df5b8e69bfad1c303cdd",{"terminalReportCount":2}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpRateLimitIdentityPublicRuntimeTests.java", "f584e13e3c6bb1e66ef57e7860a06a8754c1029009676ea516d489c9f68c8865", [
    ["allowlistedSocketPeerCanSelectForwardedIpPartitions","ae0e5edad8bf1c56b636704e6566826fbb162e27c5fbf8635dee42723b3023d9",{"terminalReportCount":2}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpRateLimitPipelinePublicRuntimeTests.java", "7129954dea2d03abeca6d2ae073871d3245c938e614678554efacaf63c01d47c", [
    ["endpointAndToolOverridesEnforceQuotasWithoutAServerFallback","c4d8258226b2546c0c6b966b4b375c63576c614fcd622be449c2c38d1d5ad051",{"terminalReportCount":4}],
    ["successfulChargesAreRetainedAfterEveryDownstreamFailure","6021dbe8040107292a2b90efe2a60899cf07dc1c61690ee44de7c463fb998cf0",{"terminalReportCount":6}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpRequestObservationPublicRuntimeTests.java", "39e3196bf05e38858b029d5954a2e7842fdf1f456045378634889ba38f9df324", [
    ["defaultOffAndIndependentRawIdOptInHaveExactLogContracts","c7179437628b87f78ffda891cad81dcfd3d1c0ff3b93aca2e4ba919c186213ee",{"terminalReportCount":3}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpRequestStateBoundaryPublicRuntimeTests.java", "58b1e37b065aeea4bd67e326ab85bb6207c0a3b44a3293175db20fa431349cb5", [
    ["defaultRequestLimitPermitsLargeArgumentsWithSmallPortableState","a65daa64fb914dbc773a5d3cd4506a399aff5ecc740f5438a1fab6b4853641e4",{"terminalReportCount":2}],
    ["configuredRequestLimitPermitsArgumentsLargerThanTheDefault","dc53aa0c457b81151e25728f42fe591f444273ade28708f0e77a68a0cd30367c",{"terminalReportCount":2}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpRequestStatePublicRuntimeTests.java", "4bc8659e930752b589d99c6a8c29b3f9a8241021560e5c8d265a3d7fd2cffde4", [
    ["frameworkProtectedStateContinuesAcrossInstancesOnlyWithinItsKeyAndAuthorizationPartition","d694cf1bfc2df30fe991073139bcf3495952c30229556db21876448ced9a3322",{"terminalReportCount":4}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpServerPublicRuntimeTests.java", "60beec0cc4924652dcd8f777237f901ad43c2dd04d64c826c4e7ca2e27d8f600", [
    ["omittedAdmissionWarnsButExplicitAnonymousAccessDoesNot","320f0051c37c95aa48e5625cbfa2afa3bb9dec6ccb5adddcb2a02fa542e43466",{"terminalReportCount":2}],
    ["explicitRejectAllCorsSuppressesTheOmittedConfigurationDiagnostic","e1fb4923fecfe92e98e4f5b236402aac503c53118d6ad11f7b09cc42d442e860",{"terminalReportCount":2}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpTasksFleetPublicRuntimeTests.java", "e3d75096d5fa69c9c5b43128fd048d724527c6b76dca0a09bebb23ef490c6374", [
    ["taskOperationsPreserveEndpointAndTenantIsolationAcrossNodes","69ac228e2bba070524d71ae39f867668973af5c29660bef3280a67ce082256cc",{"terminalReportCount":2}],
    ["durableSnapshotsControlTtlAndOriginVersionCompatibility","ec564db62a8bca4ff494729e44de113563dc6d75f962db9145941d1187178b4e",{"terminalReportCount":2}],
    ["persistedInputContractSurvivesCurrentRegistrationChanges","e0a3feb4f66579ffa6b24dd9f1e8a1edfa889ff35e6b569d29bf6668a92e03ef",{"terminalReportCount":3}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpToolOutputSanitizerPublicRuntimeTests.java", "29be8a5795c0d8cf5f5d0457ef37b3a7c037946ea09d2a7093dce4339da30d96", [
    ["sanitizerNullAndExceptionsFailClosedWithoutOutputLeaks","34af0764760898b38dca1a3e4bfc00a8cc27e1608781d2ed6e02d27b509c87ad",{"terminalReportCount":2}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/Round2HttpStreamingRuntimeTests.java", "119e97003f90cec0cc29e968d0af0ce710db4559a8190757739ca1d90153be9c", [
    ["abortedFileDownloadDoesNotBecomeAServerTransportError","7346b104946ee9657bc77cbf1c0c18f0e44eff53c34876f8963047c2c45237f6",{"terminalReportCount":2}],
    ["zipCleanupAndInterruptibleUpstreamRemainQuietAfterLiveDisconnect","8e0a5480e4cf7e5ee0bbbc9d8a95d53ee10b9ac2273f6303826705c309c76b92",{"terminalReportCount":4}],
    ["lateFiniteAndStreamingHandlersObserveTheTimeoutResponseWithoutStreamAdmission","c69fadee8234733cc29a09417e85f485b9531a948b423c573a211da933d0f8e8",{"terminalReportCount":2}],
    ["throwingWillWriteObserverStillWritesBeforeFinishForNormalAndProtocolReplacedStreams","5dbc8c5ce2d6bc43eeb4f9277a505688d401d509cb196df75fba8dedeb492fe9",{"terminalReportCount":2}],
  ]),
  // Final ordinary fixture migration retains the reviewed generation and phase
  // budgets. Mixed simulator/network matrices charge reports only for network
  // owners: subscription localization has one, Round2 finite replacement has
  // two, and the writer-failure matrix has one. The framing-header helper's
  // constant live/simulated alternatives retain the conservative two-network
  // report allowance in both wrappers; only one branch executes per iteration.
  // Each finalizer-failure wrapper passes a fixed boolean to a helper with
  // mutually exclusive simulator/network branches: precisely one owner executes.
  // Charge one network report conservatively to either branch, without charging
  // both alternative owners as sequential generations.
  // The newly exposed blocked-cancellation method has two legacy generations:
  // a shared absolute 60 s wire/condition budget, eight 5 s foreground latches,
  // and two 8 s background gates per revision total 132 s of controls. The
  // unchanged 5/2/1/1 phase policy and two reports compose to 148.5 s under 180 s.
  ...reviewedScopeFile("src/test/java/com/soklet/McpLegacyGetWireTerminationTests.java", "506e8e5da4e88fa42bcbdda3a99068f2a9a66d3272738289ce924428aed5b98a", [
    ["shedLegacyGetClosesItsHttpConnectionImmediately","f32c9df282f4e271a8deae7d899332a7c41ef251d25f0e98681d536fd821995f",{"terminalReportCount":2,"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000}}],
    ["expiredGracePeriodClosesItsHttpConnectionImmediately","d3e225938e26f78e1882eeee989b90ea162a6071f50bcf1c9ef82115d5acd06a",{"terminalReportCount":2,"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpLegacySessionHttpRouteMetricsTests.java", "e0cdbb9a47d3d0655e51b89d305a45cab6bc6d184b0ada5f829787afbf5f4e1b", [
    ["acceptedGetAndDeleteUseTheSelectedEndpointAndReleaseTheActiveGauge","e4316fcd8127ed55748592eef684b7d2c99cc9e99858c875be2bc79dd633ec59",{"terminalReportCount":2}],
    ["rejectedControlsRetainMatchedRoutesAndSeparateEndpoints","bfb3ba14f823e04aa70837acdac2c4eb4580c144fd69d0b32d9e1a3eafc11eb1",{"terminalReportCount":2}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpLegacySessionPublicRuntimeTests.java", "ec7a2db71a8a788ea0e03f48f9a3fea5851acfe1fd4e4deb42e0b9f105a77e9d", [
    ["ownerResolverFailureAndInvalidKeysFailClosedWithoutPublishingIdentifiers","3a09358f22e49acabd07f1ded51d880573f8c8edf529804358e8492b67a1b550",{"terminalReportCount":5}],
    ["explicitCancellationCompletesFiniteAndCommittedSseBodiesWithoutAResultAndRetainsPhysicalWork","b59a308129982bd78f27de8d282c072a6ee4fd81517698a2b8974a678d71041b",{"terminalReportCount":4}],
    ["activeIdCollisionsAreTypedAndCancellationIsBoundToOneOwnerAndSession","e7adaaa237ed4d66f23819a0f7a4005a5005e1fa445608e4917f3cc8ac8c2bc6",{"terminalReportCount":2}],
    ["resultReservationPreservesQueuedTerminalBytesAgainstALateCancellation","3fa0ca8bb0b95db74db18ae55522624390c8930be35aac8f950bbf661cb28bab",{"terminalReportCount":2}],
    ["hardSessionExpiryCancelsPhysicalWorkAndCompletesItsUsablePostWithACorrelatedError","17e882acf99ca0acfa5c73ff551de4019938b36fbb07c070625e797556ecc5a8",{"terminalReportCount":2}],
    ["anonymousAllocationRequiresExplicitOptInAndUsesFreshAdmission","aba689d4e804b136b8cff407c7148d468258e5bed1eddf5bb0027daa0f66a684",{"terminalReportCount":2}],
    ["verifiedControlsRemainAvailableWithFullHandlerQueuesAndDeniedOrdinaryQuota","514065255dce9b2cfa1927577ca61029b0f5096062ec893447077647cbabd6be",{"terminalReportCount":2}],
    ["verifiedDeleteTerminatesActiveAndQueuedCallsWhileOrdinaryHandlersAreFull","c06b3c05cc99ddeb1f4d6ea0e3797fc8e1ba5a3fce08def6d1cef8b24641222a",{"terminalReportCount":2}],
    ["blockedCancellationCallbacksDoNotHoldHttpCompletionButRetainTheirPhysicalExecutionSlot","1cd01ea95a3cba7e757e1f5a2364684902e3bc9ea34051f86387156531100ffb",{"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000},"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"terminalReportCount":2,"controlJoinMillis":132000,"controlComposition":"REVIEWED_SEQUENTIAL_SOURCE_BOUND","requiredAction":"RAISE_OUTER_BOUND"}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpLegacySessionTransportCapacityRuntimeTests.java", "fd8dbefbf833dd8b55ae6b655bf2a67e3afa7bbd538322fbe6d45c19df411ff6", [
    ["temporary_handler_capacity_keeps_get_fenced_and_retries_without_replacing_its_stream","21ac6451e1115e3a9402c6fd6e6339b7c209b13471edd27b1b56341171df0b46",{"terminalReportCount":2,"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000}}],
    ["routineRenewalCapacityDoesNotRevokeTheStillValidGetGrant","621948ba49038b21331d9a58f26c26168f426d946d6ce2410433dfa98d05fd11",{"terminalReportCount":2,"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000}}],
    ["four_ignoring_renewals_hold_global_maintenance_capacity_after_their_gets_expire","e21582c794c1803de20da23eac54195bfe1d8d81dd6fc0c76580b464b84a4aef",{"terminalReportCount":2,"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpLegacySessionTransportPublicRuntimeTests.java", "3f20c67104f7933e75cfe3371921d9a89614f4daba67e63d28d2ca070e3f40ca", [
    ["getUsesBoundedFreshRenewalAndPhysicalHttpObservationWithoutRpcStreamMetrics","29eabe0b21604968c92d9803a5d5fac39ebf0fd175d2397af6d5f82eccc4d5c0",{"terminalReportCount":2}],
    ["reconciliationDuringGetAdmissionRetriesBothCooperativeAndStaleDecisions","63a690a5b7b1ba712e91b0a20fb02ae59cb8ea4b01b54121ddb6e8e44c2ca56d",{"terminalReportCount":4}],
    ["reconciliationDenialAndOwnerChangeCloseOnlyTheGetAndCannotResurrectIt","f19ba58f408a59a78783a8b092f4683eadd999f77ab94199c609eca6ca4f29b4",{"terminalReportCount":4}],
    ["deleteEndsAnExistingGetAndShutdownBalancesMetricsAndDiagnostics","7b5d164f741b2da532cb1dc3f8ca0860510b702d72f5320c0b98cb60187fe1a1",{"terminalReportCount":2,"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000}}],
    ["slowReauthorizationCannotDeliverPastLeaseAndRetainsPhysicalReservationUntilExit","1bbc1a45618a92755276821c77841ddb107098a667805129a57b9a1fad8d5d76",{"terminalReportCount":2}],
    ["sameSessionReplacementTransfersQuotaAndCapacityDenialPreservesTheExistingGet","74bc8882866b8b8e5f78d0ad9e03b664f4e5dec6e16536e3e26dd911a3cec441",{"terminalReportCount":4}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpLegacySubscriptionPublicRuntimeTests.java", "b5e7d6ae431779f9a9a94b3ef522697364631a12801693c8c21f8b1b3de06660", [
    ["liveNotificationsUseOneStreamAndDoNotReplayOnNewGetOrReconnect","7e985989d83e7cbba087efbafba921c96def78bd8a80fb8dc25750e9bcc11b79",{"terminalReportCount":2}],
    ["duplicateResourceFlushDoesNotShedTheWinningWriter","492c121e4cfd6b4a88aa9c17d8fc920444dfc7212103c190458d0b7c6628e8e5",{"terminalReportCount":2}],
    ["duplicateCatalogFlushDoesNotShedTheWinningWriter","0df467edbcc56dbf9eedd81d0eb576c7accbd26323fe1653cdec2bf764008520",{"terminalReportCount":2}],
    ["localizationInvalidationWorksWithoutCallerPolicyAndIgnoresModernOnlyLocalizedOwners","713cad313f609460fac9ba86217fb28439c6f39f847515a9b3df4596056b774e",{"terminalReportCount":1}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpLocalizationFleetPublicRuntimeTests.java", "226b9445317201e235c721ddc100e17be8730c41707cc5c43d930d458fea96d8", [
    ["failedFleetReloadPreservesBothOldSnapshotsAndPublishesNoInvalidation","70d8041d7b6fe9345f0d4367b322acfe549108279e0b38b87339056b3db1a530",{"terminalReportCount":2}],
    ["rollingActivationAllowsRevisionDriftBetweenNodesButNeverWithinAResponse","f6928f916b52293b816244d20de51ebe28a29cc215665ab90f900106f30c0889",{"terminalReportCount":2}],
    ["applicationBrokerRecoveryCoversDelayedDuplicateMissedAndRevokedFleetState","533b23644b51a425749c022f1f6d071acf85ee2a144ff890e627e316f87cd8e3",{"terminalReportCount":2}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/McpObservationStartRaceTests.java", "57a43c77ef4d02c280354f9522ad7216ededc597e7d79217a620a633177d7bbe", [
    ["terminalDuringStartReleasesPairedObservationAndPreservesStreamOrder","348ea418626772eaf6c0dad67c4f0b0bb706b3160f05efbf1bb27ce21ba97d4f",{"terminalReportCount":1,"phasePolicy":{"startupMillis":5000,"startupCancellationMillis":2000,"gracefulShutdownMillis":1000,"forcedShutdownMillis":1000}}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/Round2SseRuntimeTests.java", "dcd33b32fd31747bf4f2aded44d88503bdb7f25a58ed1d42dd9fa1254809d2ef", [
    ["malformedOversizedTargetsUseUnparsed413WithoutInternalFailures","60187a0f852fffa77a4efb3828d39666c0c1f6563f5fda16d1dafd626d28a8d7",{"terminalReportCount":3}],
    ["interceptorFiniteReplacementCannotJoinTheBroadcasterInLiveOrSimulatedSse","dd2f6cfa45bc33669f4833bcc5b981e7f00ca54c902c1273fd92d27ace2028ab",{"terminalReportCount":2}],
    ["initializerFailureAndOverflowEmitOneContextualDiagnosticWithTheElectedReason","ac7de6a7ee76cda1454e58f2bb433d259770520fb83006516a5a9b0220108a88",{"terminalReportCount":2}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SseHandshakeOutcomeRuntimeTests.java", "6d7781eafe8226c62f3e1ebb303c565c53c085add9660b49aa1d0ec8a8d90459", [
    ["simulatedInterceptorFramingHeadersCannotActivateAnAcceptedHandshake","668466bb36869bafc87bedf32de2f61b0dfe61d0efef20375c09c338aaec87c5",{"terminalReportCount":2}],
    ["liveInterceptorFramingHeadersCannotActivateAnAcceptedHandshake","54f574a55a070059631bd25fdd54f06d74d650bec2fd3b7401647726a0801204",{"terminalReportCount":2}],
    ["headAndOptionsDoNotConsumeOrRequireStreamCapacity","561e48be855f0868880b565653df98dade15a6f0755d9cc4a5d318eac3ad314e",{"terminalReportCount":1}],
    ["headTimeoutResponsesCannotSendContent","cccf67142ae0512a52cab846be60e96b212c7c13efe52813b77c61109870b3bd",{"terminalReportCount":1}],
    ["initializerFailuresReportEstablishedThenElectedTerminationWithoutInternalRejection","e30728682062a7ce87c442adb45b23ed60b5e32fdecce4e69273e733b3b9bae1",{"terminalReportCount":1}],
    ["bothCapacityBoundariesReportCapacityRatherThanApplicationRejection","e19bb2164b31af09d24412e04dc8f4667eb25a2921244e6378a676c18de6af09",{"terminalReportCount":1}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SseMemoizedBroadcastTests.java", "0673f39f429c08717e80b1acbc5f626115672de176a275ef57763b62af1a0205", [
    ["liveBroadcastCachesProviderAndSerializationFailuresAndRecoversOnTheNextCall","9bb11e1661c0dda41b19d74ac3e4526fbfdc67d8bc39120e03225e01e924fe96",{"terminalReportCount":1}],
    ["liveKeySelectionFailureDoesNotAffectOtherClients","fb705f665985013a4181593c58782b36bdf8a8965ac11c7771631c4882dc54b5",{"terminalReportCount":1}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SseReadDeadlineRuntimeTests.java", "bf306e0358a37cef3f9734ef104a98f58f8b1943d9252fb20dd92617eb5bb61d", [
    ["headerDeadlineWinsRegardlessOfHandlerTimeoutOrdering","15b9726bf06a7d89c9811b3076dcc6ffd0fe3c939e49c56ccaf3347ee411d829",{"terminalReportCount":1}],
    ["idleReadDeadlineClosesWithoutFailureEvents","41963a7eb890bbfcf43724d2cbeff3cfecb42557484dfec0e60634d2a309ee51",{"terminalReportCount":1}],
    ["eofBeforeCompleteHeadersIsAQuietClientDisconnect","c5b6d571e7b86820a17c03711bac8b184897cffa7287d50af23b44e473ca07bb",{"terminalReportCount":1}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/StreamingCleanupDiagnosticsTests.java", "022124943ef7b965298b1e27d393df869a7da985eeb79aa356dd8426b4a649d5", [
    ["ordinaryWriterFailureIsStillAProducerFailureWithoutACleanupDiagnostic","79da3f6570ea83d81f0ecbcc5cc82ab694b22fb7d4bc76ef7d09eb8415c98305",{"terminalReportCount":1}],
    ["httpFinalizerFailureHasCleanupClassificationAndOriginalStreamContext","12e6b3743c58b0cde342a4662bfb45c3b73d3fffae0dc0166924d589657073c9",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"terminalReportCount":1}],
    ["simulatorFinalizerFailureHasCleanupClassificationAndOriginalStreamContext","f7dca85d2adc71f99cd6f1494ce19b03ceed5271e676f91b83c9760fc98916eb",{"generation":{"count":1,"mode":"SINGLE","complete":1,"prior":0,"incomplete":1},"terminalReportCount":1}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/UnparsedRequestAdditionalTransportTests.java", "4f1cf2dca4aa26a4f63d8b844e37ce855a76137c7a26eb5efd335f412236f35e", [
    ["httpRejectionsBeforeRequestConstructionAreCustomizable","d7a103e98f0ba63915854aea21568e76029a3407fb07596e7dd1d50b32cbc779",{"terminalReportCount":1}],
    ["ssePreRequestRejectionsUseTheSameHook","15b985c8b5d2bd1307582248ecfccdeea8dcf9035486326ef93cf108e661b10c",{"terminalReportCount":1}],
    ["sseInvalidOrFailedMarshalingUsesTheOriginalFallback","9b764a3eddfa20f31f18d9f91a1938e3ca10445eaa13769561c71d4b83691561",{"terminalReportCount":1}],
  ]),
  // Config-only base constructors still install these exact original phase
  // policies. Keep their numeric phase budgets source-bound after imported or
  // inherited manual fixture ownership stops propagating the builder chain.
  ...reviewedScopeFile("src/test/java/com/soklet/StreamingCleanupDiagnosticsTests.java", "022124943ef7b965298b1e27d393df869a7da985eeb79aa356dd8426b4a649d5", [
    ["blockedCleanupLogObserverRetainsItsSlotAndShutdownEvidenceWithoutHoldingTheTransport","e11046f56a8e99494a0c04f037d772476d606319aa418e2535f23fc560f0df09",{"phasePolicy":{"startupMillis":30000,"startupCancellationMillis":2000,"gracefulShutdownMillis":100,"forcedShutdownMillis":100}}],
  ]),
  // Retain every existing numeric control allowance, including source waits
  // removed when automatic callback finalization replaced imperative joins.
  // These conservative allowances are bound to the new complete callables;
  // migration does not reclaim budget for other work or shorten phase bounds.
  ...reviewedScopeFile("src/test/java/com/soklet/DecoratedHttpQueuedAdmissionTests.java", "7e2e785f05e818df97eed37d3d966fa74e8fba92cb4b0c9e93d0a045ced09b10", [
    ["transparentDecoratorCanRedispatchACopyWhileAdmissionIsOpen","08d2938f3fcc40dae99cdcbcd5cae489c7822ff876f53e77a8d6f0baaaacbba7",{"controlJoinMillis":3000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/HttpTransportResponseFeedbackIsolationTests.java", "604858b4c12ddf8cf614c76cc07c75d387a1b4e83e9afccee70d7c55e41fa582", [
    ["sseCallbackThreadHopRetainsInvalidFramingReplacement","7fdbbd9b4caaae4f973ed371f63039dc1ad640febdb1a529332472fe9f41d371",{"controlJoinMillis":32000}],
    ["sseNestedFreshRedispatchKeepsReplacementWithInnerRequest","be845b0824b744ada2e517646a4ba4243596cb55d809b61bc4b8aca4dd8db166",{"controlJoinMillis":32000}],
    ["sseFreshRequestAcrossHandlerAndCallbackThreadHopsRetainsInvalidFramingReplacement","f68aa2973c4ca664a3c019a4e17ee3ad8751ba3a1511108fd83c55f0d7d482ba",{"controlJoinMillis":32000}],
    ["sseHandlerAndCallbackThreadHopsRetainInvalidFramingReplacement","2ca974c491c795c4ac3112c2dcbace7a38d28228b3a99159c2ff1872b0015321",{"controlJoinMillis":32000}],
    ["sseFreshRequestRetainsInvalidFramingReplacement","5286a75ce3b2501c5fce49c45836d4fe4196230df49898584b7925ada576ea9f",{"controlJoinMillis":32000}],
  ]),
  ...reviewedScopeFile("src/test/java/com/soklet/SseDurationBoundaryTests.java", "72ed04ac961f3a230da15e9a2bebc883cf80dfd2ca679c18a89ea775f0d8ae89", [
    ["retryWireValuesUseNonnegativeWholeMillisecondsAtBothBounds","3a7eb9d59e708ccfc3f1a053d5b03301119992a0706c6eac9468fdf2fe29c0d7",{"controlJoinMillis":5000}],
    ["absentDataAndEmptyDataRemainDistinctOnTheWire","c7524d97c62bbf85824a8d98f5a7e90e15f6ad3760963c4e6deccfa8588715ea",{"controlJoinMillis":5000}],
    ["maximumHeartbeatStillAcceptsApplicationWritesAndStopsCompletely","d68b7c1d79b59c0725dc21eae5cda32d64f2fb0681af2ab0438c69274071842e",{"controlJoinMillis":5000}],
    ["idleHeartbeatRemainsACommentAndDoesNotPreventLaterEvents","96b227719b6f888528f9e0aea06a6d3c76bfa10c85a22a5059a0b549427a7642",{"controlJoinMillis":5000}],
  ]),
  // The scoped outer callback contains a distinct manually stopped inner
  // owner. Keep both full generations while only the outer owner reports
  // through public run; the callback is not a second view of the same owner.
  ...reviewedScopeFile("src/test/java/com/soklet/HttpTransportResponseFeedbackIsolationTests.java", "604858b4c12ddf8cf614c76cc07c75d387a1b4e83e9afccee70d7c55e41fa582", [
    ["nestedAdmissionRejectedHttpWriteCannotReplaceAnIndependentResponse","3cf2023e1fc1eb32c118ba8b34f9d9c004245e4d60f9b293990c5e65c5973bf9",{"generation":{"count":2,"mode":"SEQUENTIAL","complete":2,"prior":1,"incomplete":1},"terminalReportCount":1}],
  ]),
], 'scoped integration finalization');

// The deleted SokletApplicationTriggerTests input-manager fixture and
// triggerRegistrationFailureStillFinalizesPostcommitOwner have no remaining
// console implementation to exercise. Their discovery/closure evidence retires
// with that source. Three owner tests above retain their policies under the
// hook/explicit-shutdown names; JVM-hook and async coverage remain executing.
const REVIEWED_SCOPE_OVERRIDES = mergeReviewedScopeOverrideMaps(
  REVIEWED_SCOPED_INTEGRATION_SCOPE_OVERRIDES,
  REVIEWED_UNIFIED_API_SCOPE_OVERRIDES,
  REVIEWED_ROUND18_SCOPE_OVERRIDES,
  REVIEWED_FINAL_DEEP_DIVE_SCOPE_OVERRIDES,
  REVIEWED_VERIFICATION_FOLLOWUP_SCOPE_OVERRIDES,
  REVIEWED_LEGACY_EXPANSION_SCOPE_OVERRIDES,
  REVIEWED_STREAMING_SCOPE_OVERRIDES,
  REVIEWED_SCOPE_TOPOLOGY_OVERRIDES,
  REVIEWED_PHASE_POLICY_OVERRIDES,
  REVIEWED_CONTROL_OVERRIDES);

const REVIEWED_ORPHAN_HELPERS = checkedReviewMap([
  ...reviewedOrphanFile("src/test/java/com/soklet/HandlerTimeoutWorkerReuseTests.java", "b983a075ef61901ba23b7960d714483c134f07255c78c6d1da83039ac946ec1e", [
    // Retired close: the scoped-run migration removed its imperative helper.
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/SseLastEventIdTests.java", "f0b4675758cbcdcf421eb485a7e41e71b58b973ec5d3423ea6359fb92b29cc42", [
    // Retired close: the scoped-run migration removed its imperative helper.
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/SseLateResponseOutcomeTests.java", "e420e7be432944ee33a1fff45e7e4da6d70e33a046bb783b00a21cf7cfa226c1", [
    // Retired close: the scoped-run migration removed its imperative helper.
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/CombinedTransportShutdownTests.java", "22d296ad150b766b3ca3f2359dbb0744eed450d14792ef2010d209aea5f6636f", [
    ["openWork",185,"4f0698cd38a5f0ec00ed3fac96aab4a7955a93b4fcf45b5bcac9c6526556f21b",{"path":"src/test/java/com/soklet/CombinedTransportShutdownTests.java","line":49,"lineSha256":"af9b1cc5d3b5a82753738827d031f68e688ef50bbb730401e780eeeed1a8b128","rationale":"The source-bound calling JUnit fixture owns this exact helper through its explicit start/shutdown invocation or try-with-resources cleanup. The helper operates on the same transport/Soklet owner already represented by the caller closure; it introduces no independent generation or synthetic JUnit outer guard. The callable and whole-file hashes bind the ownership and bounded control evidence."}],
    ["close",261,"6b6fb3524306d7e4d11e0e01d0f91238be77086faad56261497c27fae7b2e0d5",{"path":"src/test/java/com/soklet/CombinedTransportShutdownTests.java","line":48,"lineSha256":"d373a7c7b99ff503802098ba0393cad4235712af01a843fd95b1ea1e37a76366","rationale":"The source-bound calling JUnit fixture owns this exact helper through its explicit start/shutdown invocation or try-with-resources cleanup. The helper operates on the same transport/Soklet owner already represented by the caller closure; it introduces no independent generation or synthetic JUnit outer guard. The callable and whole-file hashes bind the ownership and bounded control evidence."}],
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/DecoratedHttpQueuedAdmissionTests.java", "7e2e785f05e818df97eed37d3d966fa74e8fba92cb4b0c9e93d0a045ced09b10", [
    ["attach",118,"b2f9569cb8984170b753860ceccba4a4c6cafa004f241e7a5c3c96e583ce7166",{"path":"src/test/java/com/soklet/DecoratedHttpQueuedAdmissionTests.java","line":80,"lineSha256":"4c452535317c6e72f8f265ce54b145125d48a17d829cbe06b12cbc3de0cce2cb","rationale":"The calling test starts one Soklet owner whose HTTP decorator attach contract creates the exact termination-owning delegate runtime. Production startup invokes this public attach/start contract; the returned wrapper delegates physical lifecycle and signals only after child teardown. It is part of the caller owner closure, not a second independently started owner or a synthetic JUnit helper guard."}],
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/external/HttpStreamGracefulShutdownTests.java", "e711733a5084021c0bf71782ee276d151669b01b4291dfd3ff158836dcb249a7", [
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/external/SseTransportHandshakeAccessTests.java", "0a9483e6c667de2a7bc3ddd526dd3ef8523d03ddea0c209c49ae257fdcd12ffb", [
  ]),
  // The new 205 test and bounded helper shift close without changing its
  // callable body or the existing source-bound try-with-resources invocation.
  ...reviewedOrphanFile("src/test/java/com/soklet/FiniteHttpResponseSafetyTests.java", "03d6347361337f683410ce687066f3125c6817079136f6435b2053057af3d465", [
    // Retired close: the scoped-run migration removed its imperative helper.
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/HeaderLocaleTests.java", "45e78bafb485296206911b38cf651e6385f57a36a507c0b7ce734046763d79ab", [
    ["main",92,"dbc90458f94bc354bb7232da397d6306b66276d015e0e0456f3304a618c03d57",{"path":"src/test/java/com/soklet/HeaderLocaleTests.java","line":74,"lineSha256":"4fb90ede31f41e09dafeb6b6ac48ed005e8f618ae0cc68a82e34ed3102312eb0","rationale":"The isolated-locale JUnit driver starts this exact main class for tr, az, ar_EG, fa_IR and en with matching FORMAT locale pins. Each child has a 25-second wait and finally forced destruction with a two-second termination wait. The method-level 150-second guard covers five sequential 27-second process/cleanup budgets plus 15 seconds of reserve. The child main invokes bounded HTTP and SSE wire probes; it remains externally bounded helper evidence, not a synthetic JUnit lifecycle scope."}],
    ["assertHttpWire",195,"53e2bf23faae20739b62ef9ead573c1ad2809b612a496a0b1a69687a92607166",{"path":"src/test/java/com/soklet/HeaderLocaleTests.java","line":115,"lineSha256":"a4363014c1e8fc28d22483cd9e4319fbcda958c8bc389ab00584c7307f1a23b9","rationale":"The source-hashed locale child main invokes this exact HTTP wire helper through assertAll. Its loopback reads/connects are timed, its Soklet owner has explicit three-second startup, two-second graceful and one-second forced shutdown settings, and the entire child is owned by the parent's 25-second process deadline and two-second forced cleanup. No synthetic JUnit guard or independently reusable execution claim is assigned to the helper."}],
    ["assertSseWire",218,"9b4083349a4c7dd98470e53a0024c64dd9adc74394238876815350f80d5f946d",{"path":"src/test/java/com/soklet/HeaderLocaleTests.java","line":116,"lineSha256":"a3f26f059e04928a8533cc5ab99dc1072b82c93b5704431afe1cc759aa20963a","rationale":"The source-hashed locale child main invokes this exact SSE wire helper on every supported runtime. It exercises accepted, rejected, unknown-status and overload responses with timed loopback operations and a three-second establishment latch. Its Soklet owner uses three-second startup, two-second graceful and one-second forced shutdown settings; the entire child remains under the parent's 25-second process deadline and two-second forced cleanup. No synthetic JUnit guard is assigned to this helper."}],
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/HttpHandlerDispatchAndDrainTests.java", "7111bbf8d8e905a82ab8d698597cd3d00fc6067a5e88b5232b51f6576e170748", [
    ["beginDrain",342,"de88704052c4eac01d9ada0a6fc10b7a9b579029acd080f7e362f87893ce9f74",{"path":"src/test/java/com/soklet/HttpHandlerDispatchAndDrainTests.java","line":164,"lineSha256":"4fd89634b34136e7576156620ec8901bf96e92c318da9ff4d6d3a0119d55abfb","rationale":"The source-bound calling JUnit fixture owns this exact helper through its explicit start/shutdown invocation or try-with-resources cleanup. The helper operates on the same transport/Soklet owner already represented by the caller closure; it introduces no independent generation or synthetic JUnit outer guard. The callable and whole-file hashes bind the ownership and bounded control evidence."}],
    ["close",349,"e49ebe21b299452fe87e708f7150da3925891bef11d146c0f3848f99e487b554",{"path":"src/test/java/com/soklet/HttpHandlerDispatchAndDrainTests.java","line":160,"lineSha256":"c73afff4d0ae1b7c3e4219f597ab67c564dc1557970aafaeb59a016f756330b3","rationale":"The retained active-handler drain test owns this exact ManualFixture with try-with-resources and explicitly begins shutdown while the handler remains live. Its close helper joins the already-counted owner and releases its executor; ordinary request fixtures now use scoped Soklet.run. No new lifecycle generation or synthetic guard is assigned to the helper."}],
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/HttpStreamingPassiveDisconnectTests.java", "d9353d4ad401958df5d67feafd6ad1e9c05e52b95ee4f7a62a2a704d62ef11d4", [
    // Retired close: the scoped-run migration removed its imperative helper.
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/HttpStreamingProtocolRejectionTests.java", "71a37065b172ca04cee6286f710feee51081706c4181bf1f06e407082567137c", [
    // Retired start: the scoped-run migration removed its imperative helper.
    // Retired close: the scoped-run migration removed its imperative helper.
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/internal/mcp/protocol/McpLegacyRpcStatusTests.java", "8fab1597b3347205b9c1ad1d23a486995158f186589a40b9a54060f98d9f9070", [
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/InternalTransportEndpointTestCompatibility.java", "89141d7627fc3329742dfe3bb66364fdda2c509291b87cf4094efea180aeb992", [
    ["attach",31,"1883bfa6fe73fdd47cd502d6daeee8cb86c6860a345a5b7d5e40fe04fa5da300",{"path":"src/main/java/com/soklet/SokletDirectLifecycle.java","line":2544,"lineSha256":"788aa269a8bc65fb1f7f7460a22f1a124dbdda93b52e0fabc3c3a91472897be3","rationale":"The production direct lifecycle invokes the public HTTP endpoint attach contract."}],
    ["publicRuntime",45,"f360dda2e0fc8a7cb76981a7c6c47b6c79bcbe05f8c841d280de98f75b7164cd",{"path":"src/test/java/com/soklet/InternalTransportEndpointTestCompatibility.java","line":36,"lineSha256":"03c392d1a09359e546d74395faec5a2129994402a1e60e337059b35b43b63281","rationale":"The reviewed HTTP compatibility attach default invokes this adapter helper."}],
    ["attach",77,"c29b8ee82137d5a63ea9ee192c2f9f63e541ebee1e152ebee2f6b0cb612ef1b8",{"path":"src/main/java/com/soklet/SokletDirectLifecycle.java","line":2553,"lineSha256":"788aa269a8bc65fb1f7f7460a22f1a124dbdda93b52e0fabc3c3a91472897be3","rationale":"The production direct lifecycle invokes the public SSE endpoint attach contract."}],
    ["publicRuntime",91,"f360dda2e0fc8a7cb76981a7c6c47b6c79bcbe05f8c841d280de98f75b7164cd",{"path":"src/test/java/com/soklet/InternalTransportEndpointTestCompatibility.java","line":36,"lineSha256":"03c392d1a09359e546d74395faec5a2129994402a1e60e337059b35b43b63281","rationale":"The reviewed HTTP compatibility attach default invokes this adapter helper."}],
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/McpAdmissionOrderingPublicRuntimeTests.java", "d516a1be6181f27a79ed0351a205e775a8fbc5eb155b15f8960198f86a47852f", [
    // Retired close: the scoped-run migration removed its imperative helper.
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/McpDecimalElicitationPublicRuntimeTests.java", "036154bb5b9909da39476c86608bd51e8491e44f37c28f7dc46e07be9ddf6e4c", [
    // Retired close: the scoped-run migration removed its imperative helper.
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/McpHandlerExecutorPublicRuntimeTests.java", "4c151cf27d0b1d3274443f7dba1f878ed5e7d311813ffff7e71a8dfdb835d5a5", [
    // Retired close: the scoped-run migration removed its imperative helper.
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/McpLegacySessionPublicRuntimeTests.java", "ec7a2db71a8a788ea0e03f48f9a3fea5851acfe1fd4e4deb42e0b9f105a77e9d", [
    // Retired close: the scoped-run migration removed its imperative helper.
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/McpLegacySessionTransportPublicRuntimeTests.java", "3f20c67104f7933e75cfe3371921d9a89614f4daba67e63d28d2ca070e3f40ca", [
    ["close",775,"496f77a84a3a14de422c047cda06988c45dcb9d376331caa02ca74a02cf9a001",{"path":"src/test/java/com/soklet/McpLegacySessionTransportPublicRuntimeTests.java","line":450,"lineSha256":"0c52b6c0e20020307939f10eb93cd45fc4479f729913bd73c915c7575a9ea74a","rationale":"The dedicated legacy GET shutdown test owns ManualFixture with try-with-resources and explicitly closes its owner while GET is active. This close helper joins the caller-counted lifecycle, with the existing finite policy and outer guard; ordinary socket fixtures use scoped run."}],
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/McpLegacySubscriptionPublicRuntimeTests.java", "b5e7d6ae431779f9a9a94b3ef522697364631a12801693c8c21f8b1b3de06660", [
    // Retired close: the scoped-run migration removed its imperative helper.
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/McpLifecycleB3Tests.java", "0e9b9712ae5d9c3e899ca01213911ff37dcbdc4b7b46406572f721174e9ae78d", [
    ["close",2729,"eb076ba581178078050f92c63303430e60bec9ec1a9ed8ec7c746d75f8453968",{"path":"src/test/java/com/soklet/McpLifecycleB3Tests.java","line":456,"lineSha256":"b153608a966dca6571b544635e46ccab9aca8126e95a5e6384e21db8425ca463","rationale":"A lifecycle test directly invokes the reviewed fixture close contract."}],
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/McpLocalizationFleetPublicRuntimeTests.java", "226b9445317201e235c721ddc100e17be8730c41707cc5c43d930d458fea96d8", [
    // Retired per-node start/stop/close helpers: scoped callbacks own ordinary
    // nodes; the dedicated manual node-loss fixture retains four reviewed controls.
    ["start",653,"d6dab946a9ee87b1a9312d7e1916109698e169484db3c6fd1f23e352ea0172f9",{"path":"src/test/java/com/soklet/McpLocalizationFleetPublicRuntimeTests.java","line":247,"lineSha256":"07b7acceb6a5e59384e6422898a20512037609331671db066348aae36a3e9479","rationale":"The dedicated node-loss test starts its two explicit owners through ManualTwoNodeFleet; ordinary fleet callbacks use scoped run and do not invoke this helper."}],
    ["stopFirst",663,"24f04c27903c182fb7b9115573749d245c785daca3166406a49d31fc7c81b2d1",{"path":"src/test/java/com/soklet/McpLocalizationFleetPublicRuntimeTests.java","line":257,"lineSha256":"96d35f5e79113f1565346cfc9fd14ee86889565a158cc46f9cf4de0a2bfb6764","rationale":"The dedicated node-loss test closes the first explicit owner while its subscription remains open, exercising the shutdown boundary on the caller-counted node."}],
    ["stopSecond",664,"290fe8b3c5c1b43977a179a487d9d96c23c08dd8ce000056dda34d445842d2dc",{"path":"src/test/java/com/soklet/McpLocalizationFleetPublicRuntimeTests.java","line":292,"lineSha256":"6f005add45edbf333a0679b129e03d7d4689f30c878fbcc3432ad6eaec030580","rationale":"The dedicated node-loss test closes the second explicit owner after reconnecting its subscription, exercising the second caller-counted node."}],
    ["close",667,"a49bc373ef7e1cfa151def2812e1bb9f87ef9c2ee17029b436160eafdd08eb54",{"path":"src/test/java/com/soklet/McpLocalizationFleetPublicRuntimeTests.java","line":297,"lineSha256":"186351cad850a49204fd8ae41bd21e660277a6d5af1db6d4744e5f4004467b14","rationale":"The dedicated node-loss test joins cleanup of both already-counted explicit owners from its finally block; ordinary scoped fleet callbacks do not use this helper."}],
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/McpLocalizationInitializationPublicRuntimeTests.java", "150f5d8e438c88f64a284c8aa2952c00348854be8a357e3eca7135595a67766c", [
    // Retired close: the scoped-run migration removed its imperative helper.
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/McpNumericRequestStatePublicRuntimeTests.java", "7106780363dcc0c9730aaff0cf282eeba4451be633012b910e0ed290e2745b31", [
    // Retired close: the scoped-run migration removed its imperative helper.
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/McpRequestStateBoundaryPublicRuntimeTests.java", "58b1e37b065aeea4bd67e326ab85bb6207c0a3b44a3293175db20fa431349cb5", [
    // Retired close: the scoped-run migration removed its imperative helper.
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/McpSubscriptionShutdownAdmissionTests.java", "2cf1ac1dfcc8a0792e0d76cf633df775dada50a3bea8431cce010492e31ee2be", [
    ["quiesceAndShutdown",263,"a45fa57b2690f4e82fa9298e226e4f65a02ffdd0781d0e4d4a8219e89ec1b5e7",{"path":"src/test/java/com/soklet/McpSubscriptionShutdownAdmissionTests.java","line":67,"lineSha256":"0b337713bc797f86d92e4042d56b555aaa0787394759535806d46cb8ee48eacf","rationale":"The source-bound calling JUnit fixture owns this exact helper through its explicit start/shutdown invocation or try-with-resources cleanup. The helper operates on the same transport/Soklet owner already represented by the caller closure; it introduces no independent generation or synthetic JUnit outer guard. The callable and whole-file hashes bind the ownership and bounded control evidence."}],
    ["close",304,"182798c5ac2cadf7d711c839423da6516a9735517bdfea28a6dd24d1f0cf0c71",{"path":"src/test/java/com/soklet/McpSubscriptionShutdownAdmissionTests.java","line":59,"lineSha256":"699121fb4f14aa5dc0061d3ba953e91250db88282ca30a87d838302e6608afdc","rationale":"The source-bound calling JUnit fixture owns this exact helper through its explicit start/shutdown invocation or try-with-resources cleanup. The helper operates on the same transport/Soklet owner already represented by the caller closure; it introduces no independent generation or synthetic JUnit outer guard. The callable and whole-file hashes bind the ownership and bounded control evidence."}],
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/ResidualWorkerProcessTests.java", "72b65564b8ef24cc7bcda86249f915f28f36adefc63ac83772c55e472d3421fd", [
    ["main",97,"1f7f61b0aec762750eec6e00b78f57ab309075ec9b00347cfa4dbe97208052b0",{"path":"src/test/java/com/soklet/ResidualWorkerProcessTests.java","line":60,"lineSha256":"38065fbd28bd677f7b073b99fb68919028b502adbf07bd13c182377fa234455e","rationale":"The parent assertResidualExit starts this exact Fixture.main in an owned child for five literal residual modes. Each parent has a5s absolute marker poll,3s natural-exit wait and finally5s forced reap under the60s JUnit guard. The actual child owns one public SokletApplication with2s startup,1s cancellation,100ms grace and100ms forced policy; resistant callbacks deliberately remain daemon and the INCOMPLETE result skips cleanup. Public chained fromConfig(config).run(cleanup) execution is externally supervised helper evidence, not an ordinary synthetic JUnit lifecycle scope or a claim that raw fixture I/O has an independent total deadline."}],
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/Round2HttpStreamingRuntimeTests.java", "119e97003f90cec0cc29e968d0af0ce710db4559a8190757739ca1d90153be9c", [
    // Retired close: the scoped-run migration removed its imperative helper.
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/Round2SseRuntimeTests.java", "dcd33b32fd31747bf4f2aded44d88503bdb7f25a58ed1d42dd9fa1254809d2ef", [
    // Retired close: the scoped-run migration removed its imperative helper.
    // Retired close: the scoped-run migration removed its imperative helper.
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/SokletApplicationObservationTests.java", "0c866bfa06729660ba06719c12bc78232104f528de275e9f4504fa69e65c0ab3", [
    ["start",1102,"8743bc5899534d2657576bdd66d59311499e6ba40769bb325879c3acfee3de6e",{"path":"src/main/java/com/soklet/SokletProcessLifecycle.java","line":269,"lineSha256":"b2319df033edd17959e515597bc6bf4b346ffcdeb9c7c82ed90ffdd190add5bd","rationale":"The production application runner invokes the wrapped runtime start contract."}],
    ["shutdown",1107,"eef45a37c20721b055e31362bc503628c1bd6eea2a765b56fe50cfbae15a5c7f",{"path":"src/main/java/com/soklet/SokletProcessLifecycle.java","line":420,"lineSha256":"0ca794b2fa40ccfdbd54f9835b183890f2e8122b30d4504f27987a6fbbffeb3a","rationale":"The production application runner invokes the wrapped runtime shutdown contract."}],
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/SokletDirectCompositionIsolationTests.java", "4c0fa5ec0854c8ebb54996d9a2d4167e27d6c386e5451cd3be0b6ae6ff7e1174", [
    ["attach",450,"7c57b13fdf6fc3452e7a932d056e49fcd3f5a4bf63b3005791d37526b8c19828",{"path":"src/test/java/com/soklet/SokletDirectCompositionIsolationTests.java","line":51,"lineSha256":"59a5a5e95e2ba52cdbec6fb317cbd2020259569b690db93f89a7496fe515cfdb","rationale":"The direct composition test installs this lifecycle-owning decorator."}],
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/SokletDirectLateStartupIntegrationTests.java", "a2f576081a6b3a123979fd753276bd1999a5613351185dab1dd77dc5a855a819", [
    ["close",976,"a2c47642265a189cc7764e547ae97971625558d5bc9359803865d61787c65067",{"path":"src/test/java/com/soklet/SokletDirectLateStartupIntegrationTests.java","line":101,"lineSha256":"50edadd1377f4ad9436d7c5312200d6544b0137965aa8c5b049fc702b3170b05","rationale":"The lifecycle test's try-with-resources scope invokes the reviewed owner-harness close helper."}],
    ["attach",1357,"0a42f2275a9b81821b8b904c86631850738e49019c39ec025838ac2b46668266",{"path":"src/test/java/com/soklet/SokletDirectLateStartupIntegrationTests.java","line":216,"lineSha256":"128fbafeefb443ff53cafb5089fe4c18e97f7be4caebb68052a8bac3bd7af4b3","rationale":"The owner-level lifecycle test installs this reviewed transparent endpoint, whose lifecycle owner invokes its attach contract."}],
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/SokletDirectSseCompositionTests.java", "ce0085de3c04cfc58debef721f8adfbb9ca1d253f42163c812f91349cb63a405", [
    ["attach",678,"e125b5b643e4e0b7f8910a97362f795c80686387d0ccc1400b1702ea5b8bcb33",{"path":"src/test/java/com/soklet/SokletDirectSseCompositionTests.java","line":89,"lineSha256":"3489e198d1cf9063ad182a92b4e5ac78e33f635e650cc7ab1484245db4190b2c","rationale":"The SSE composition test installs this lifecycle-owning decorator."}],
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/SokletDirectStartClaimTruthTableTests.java", "c94d8019767282a1c342fad4a157dd68ed7d8f1ab49fdca54ec3c7125bb26c81", [
    ["close",192,"72d21aa1b74efb7d81f57e986ddc30f6fab8e5f8a955335e5f95d5e655500b3f",{"path":"src/test/java/com/soklet/SokletDirectStartClaimTruthTableTests.java","line":147,"lineSha256":"a652045ac401b11d233cba856e6cd5c586f26926053ff1ec249d0bb80c947f87","rationale":"The lifecycle test's try-with-resources scope invokes the reviewed truth-race cleanup helper."}],
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/SokletDirectTerminalPublicationTests.java", "dab2b99392310cb48d54d26298fb03c2bfa41ed7feb9d4205ec25a8c38f07e3f", [
    ["create",431,"7e0266a1ff39c91caeff040d7a3e5a7712aad3a6c58af387450cac8f5e69492d",{"path":"src/test/java/com/soklet/SokletDirectTerminalPublicationTests.java","line":94,"lineSha256":"8b0299d6130be80b0de8400d73d5ce7177bd1181ee4ff028f0322f5f214f53ab","rationale":"The lifecycle test directly constructs the reviewed owner harness."}],
    ["close",454,"df08d934601614ca68816224f6d17016463202755d3f8dbf6f741e0acc85f3a9",{"path":"src/test/java/com/soklet/SokletDirectTerminalPublicationTests.java","line":94,"lineSha256":"8b0299d6130be80b0de8400d73d5ce7177bd1181ee4ff028f0322f5f214f53ab","rationale":"The lifecycle test's try-with-resources scope invokes the reviewed owner-harness close helper."}],
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/SokletDirectTerminationPrecedenceTests.java", "fc2e9a0459ddb113411e9e937d45743bbfc7461095829ba8c69f82a815ad6f48", [
    ["close",521,"e406547b24c34bad6ed8587e1768d7d8a1dbb25d260fef67b4e4bf3027653d3d",{"path":"src/test/java/com/soklet/SokletDirectTerminationPrecedenceTests.java","line":247,"lineSha256":"05e81d8b560c9a1c5498861a13c717af06a07bba454d7305b66bcace0f6b18af","rationale":"The lifecycle test's try-with-resources scope invokes the reviewed precedence-harness close helper."}],
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/SokletDirectWaitSemanticsTests.java", "d200f0ed18f1cd7532ea1f0141fa718c05e1d6ca0390896191ead5c0f7d8b242", [
    ["close",334,"4657b5a39ea6a1e00289aec01e92fbb4937e924fd3e088df0f935fb7c47d96e4",{"path":"src/test/java/com/soklet/SokletDirectWaitSemanticsTests.java","line":84,"lineSha256":"366775be6d81f07243ae7d4809f3249ceed9b8ca6705fca8f77d9a0e1306c9ef","rationale":"The direct wait-semantics test constructs the reviewed AutoCloseable wait harness."}],
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/SseDurationBoundaryTests.java", "72ed04ac961f3a230da15e9a2bebc883cf80dfd2ca679c18a89ea775f0d8ae89", [
    // Retired close: the scoped-run migration removed its imperative helper.
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/SseHandshakeOutcomeRuntimeTests.java", "6d7781eafe8226c62f3e1ebb303c565c53c085add9660b49aa1d0ec8a8d90459", [
    // Retired close: the scoped-run migration removed its imperative helper.
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/SseInitializerRuntimeTests.java", "3cca5bfa89b76b3050355b8318187603e1017ad5aa4181977a0ef9edbda231f0", [
    // Retired start: the scoped-run migration removed its imperative helper.
    // Retired close: the scoped-run migration removed its imperative helper.
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/SseLifecycleAdmissionTests.java", "3c2be7b755102190c67d220efa565286ea6ddb1d666a171ae9dfc2479a46dd44", [
    // Retired start: the scoped-run migration removed its imperative helper.
    // Retired close: the scoped-run migration removed its imperative helper.
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/SseMemoizedBroadcastTests.java", "0673f39f429c08717e80b1acbc5f626115672de176a275ef57763b62af1a0205", [
    // Retired close: the scoped-run migration removed its imperative helper.
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/SseReadDeadlineRuntimeTests.java", "bf306e0358a37cef3f9734ef104a98f58f8b1943d9252fb20dd92617eb5bb61d", [
    // Retired close: the scoped-run migration removed its imperative helper.
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/StreamingCleanupDiagnosticsTests.java", "022124943ef7b965298b1e27d393df869a7da985eeb79aa356dd8426b4a649d5", [
    ["start",452,"0c3ee687393877456d2e2fd47677d64daab07587e4bb91e5c2431b55f4bd7b3b",{"path":"src/test/java/com/soklet/StreamingCleanupDiagnosticsTests.java","line":96,"lineSha256":"8d7e8eb67bdc2bd8120bf0ef36821961a66d992f7a1142afbef0f2d4f8e3313e","rationale":"The retained abort-failure test owns ManualHttpFixture while its producer remains physically blocked, then releases and joins that work in finally. This helper starts or closes that already-counted owner; ordinary diagnostic callbacks use scoped run and add no imperative helper ownership."}],
    ["close",453,"8f601964d35c38bdd58c3c01535f83095b789d7d5c5a87ccf9601cd60ec4bf8d",{"path":"src/test/java/com/soklet/StreamingCleanupDiagnosticsTests.java","line":95,"lineSha256":"2a4ef6757bcc04efa6f6bf705e05753c74520f751ec02e25cfa4df143b50d891","rationale":"The retained abort-failure test owns ManualHttpFixture while its producer remains physically blocked, then releases and joins that work in finally. This helper starts or closes that already-counted owner; ordinary diagnostic callbacks use scoped run and add no imperative helper ownership."}],
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/StreamingLifecycleTests.java", "8fea091632d561ad48af6a299672d3a722b18b41443e3ff70cbb93cfcb890daf", [
    ["start",655,"0c3ee687393877456d2e2fd47677d64daab07587e4bb91e5c2431b55f4bd7b3b",{"path":"src/test/java/com/soklet/StreamingLifecycleTests.java","line":121,"lineSha256":"8d7e8eb67bdc2bd8120bf0ef36821961a66d992f7a1142afbef0f2d4f8e3313e","rationale":"The lifecycle-capacity test starts this fixture; its helper policy and test control waits are reviewed together in the method closure row."}],
    ["shutdown",656,"dd76ab62776b8764f97798ec7f5d4929e492cd31627da1e7bb4e434a5e65458b",{"path":"src/test/java/com/soklet/StreamingLifecycleTests.java","line":150,"lineSha256":"e87deb21768689ed3d66e3f3066cc85935fe05c6ceeb37ecba57f6586510dd21","rationale":"The lifecycle-capacity test invokes fixture shutdown in its finally block; this is cleanup of its already-counted generation."}],
    ["shutdownNow",728,"62bc31ebee56edd579fd247b10d54b6c705a05752dcecc8416b33b6d6fa673c6",{"path":"src/test/java/com/soklet/StreamingLifecycleTests.java","line":240,"lineSha256":"7685ddf8264996409d10b8b00dfa1e111dcb2d159f72f0631340ca250ab0a297","rationale":"The inline-executor test installs this executor supplier. Its shutdownNow delegates to a nonblocking shutdown flag and returns an empty task list; DefaultHttpServer owns the executor and invokes its shutdown contract."}],
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/StreamingServerSettingsTests.java", "9b74ca7d3fcdd810ca35ab8087c620d59de14177f23cc88aa99f657fa8f39bac", [
    // Retired start: the scoped-run migration removed its imperative helper.
    // Retired close: the scoped-run migration removed its imperative helper.
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/TransportProcessOwnershipTests.java", "8e7cc8ffbb5e04ca962a9441b20abc983dfcadcd6edcdfd4a7f36689a5ad9bd4", [
    ["main",141,"cb83a7bd1b98ccc42a151e1185cefcdc9a99b7164baf1d6d28b686df3460c638",{"path":"src/test/java/com/soklet/TransportProcessOwnershipTests.java","line":115,"lineSha256":"332aff3f9792c966a040644d982d21955fb7ae4b77a1af69addce646f1e4e0bc","rationale":"The parent start helper launches this exact Fixture.main class into an owned child. Tests use finite readiness/exit waits under the25s class guard, finally destroy/forcibly destroy and wait2s for owned child cleanup. The blocked-startup child installs2/1/1/1s policy; parent now waits8s for natural exit with3s reserve over that5s phase envelope. Other literal modes retain their explicit2s startup,1s cancellation/grace/force settings and bounded parent liveness/signal controls. Obsolete automatic console-input cases retire with the removed trigger machinery. Child public-owner execution is externally supervised evidence; the main is not assigned a synthetic ordinary JUnit lifecycle scope."}],
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/TransportStartupFailureTests.java", "d5568c75de818bf13739c5b38d62105f0c3bd5ab341200386e546ced6d2a84c9", [
    ["owning",209,"bce84e3d6f50a9ff7a14e970f41a50b00b5d6c80f76f15f6aee2b6b08b49723e",{"path":"src/test/java/com/soklet/TransportStartupFailureTests.java","line":231,"lineSha256":"5210a2bd0012daba2d03d46b9290fe43144c564b7e53183605a272e1912fdbbd","rationale":"The exact owning-decorator attach contract invokes this helper with the returned child attachment. The helper returns a lifecycle wrapper whose start forwards once and whose grace/force methods delegate then signal the wrapper; the calling startup-failure tests own the enclosing Soklet lifetime under explicit2/1/1/1s policy. It does not create a second public owner or borrow a synthetic JUnit helper guard."}],
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/UnparsedRequestAdditionalTransportTests.java", "4f1cf2dca4aa26a4f63d8b844e37ce855a76137c7a26eb5efd335f412236f35e", [
    ["close",344,"8483658be11af4a9e197a94648b49f9dcc0b8009aa929db1f39ecaaeb2794d5e",{"path":"src/test/java/com/soklet/UnparsedRequestAdditionalTransportTests.java","line":133,"lineSha256":"af776c3f09f0c2ab90b966c2bd9dd1d666b42fca7f6ce95fc43bda2209abf3c3","rationale":"The dedicated noncooperative pre-request rejection test retains ManualFixture to request shutdown before the blocked hook exits and inspect its immutable incomplete result. Try-with-resources closes that caller-counted owner under the existing finite policy; ordinary rejection callbacks use scoped run."}],
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/HttpTransportResponseOutcomeTests.java", "60fb95984e6a8e4d2ff38601a0711d19c0ad261b3e42bffabb1d4355beba0696", [
    // Retired close: the scoped-run migration removed its imperative helper.
  ]),
  // The isolated carrier probe owns a real SSE generation in its child main.
  ...reviewedOrphanFile("src/test/java/com/soklet/SseReviewRegressionTests.java", "15fb13fae72a10709e965b3b74a73f15aed17990749366e9ff88b7b8d17a2779", [
    ["main",236,"0a66235064fa94c9ec110a82826974b45c3a93125c72e8406244670625604654",{"path":"src/test/java/com/soklet/SseReviewRegressionTests.java","line":214,"lineSha256":"c6b491c0fb3f365ee24cd570a20892801687940912c86fa3df7ca0aada2d8c3b","rationale":"The JUnit parent starts this exact test class in an isolated child JVM, waits15s, and forcibly reaps it within2s in finally. The child starts a real Soklet/SSE owner; this external process cap is not a claim that default lifecycle phase maxima fit15s and supplies no synthetic lifecycle closure row."}],
  ]),
  // Prepared HEAD wire fixtures close the one server owned by each calling test.
  ...reviewedOrphanFile("src/test/java/com/soklet/PreparedHeadHttpResponseTests.java", "63ed03f67c93f291ddd8f4d468224378f309de895917911d030cc2cfcf78e20a", [
    // Retired close: the scoped-run migration removed its imperative helper.
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/InternalResponsePostProcessingTests.java", "1a0c36b823bee999b3ba3da207ab816679ac0f3d562bc0ab525d7a088137ebc3", [
    // Retired close: the scoped-run migration removed its imperative helper.
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/McpObservationIsolationTests.java", "bf4ecdc5efbfd53fd21c144ec42824902da53766cebe9af999c521b40cb0835f", [
    // Retired close: the scoped-run migration removed its imperative helper.
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/SokletRunConcurrencyTests.java", "78879b2794a2a8146cbd5c6d695703150f541246f0b64c36c42651ccf9cb198a", [
    ["main",176,"92f212e7817b80a7792f25737bf847df021fd08df55000c4ab5188d4d0deb92f",{"path":"src/test/java/com/soklet/SokletRunConcurrencyTests.java","line":122,"lineSha256":"08b6eb9eb95002498da87c9492ec0a03590011ead0c66b930ae8ed658df7aae8","rationale":"The three source-bound parent tests invoke this exact child through ProcessBuilder. Each parent uses a ten-second readiness deadline, ten-second exit wait, and five-second forced-reap finally under its sixty-second JUnit guard. The child owns one public lifecycle with explicit 5/1/1/1-second policy and three-second cleanup, and proves application-owned keypress shutdown, automatic JVM-hook, and returned-main finalization without borrowing a synthetic child guard."}],
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/SokletTestScope.java", "adc09d2ee5842fa42b56a3afaf2918c2e96c3703d5bd9f09ab4ee33703d2f609", [
    ["shutdown",44,"979f71ba6740a4ba5651ac031adf03ad77b8ee27c6dfd1617a202d1799b0f752",{"path":"src/test/java/com/soklet/external/HttpStreamGracefulShutdownTests.java","line":67,"lineSha256":"839d9e2b25942a2a5a6b6250ae462f05deaeb4690d935d49df55ad0038c3eeb5","rationale":"This exact external test invokes the test-only shutdown adapter on its already-counted fixture owner. The helper requests shutdown and returns the same async observation stage; it creates no lifecycle generation, blocking wait, or independent guard."}],
    ["close",50,"aada8a426f1e2a865f59805ef0b1a376cfe184c81745ac4a2b38461ea911a653",{"path":"src/test/java/com/soklet/SokletTestScope.java","line":70,"lineSha256":"a627d98b7329c1e0bb61ab8b21f9f4231f4cb3491f40d8083102b470f8557de9","rationale":"The instance close method delegates to this exact test-only shared shutdown-and-join helper for its existing owner. Callers own the scope via try-with-resources or explicit finally and retain the original lifecycle policy and JUnit guard; the helper introduces no generation or additional timeout budget."}],
    ["close",69,"b2fcc72a6dd2fefb37f8edc9a5388ac2d942e9641e2206daf71fb63ef550efdf",{"path":"src/test/java/com/soklet/SokletDirectHttpCompositionTests.java","line":53,"lineSha256":"1535379087a7bad03e1f5b9faa488d9e81881bea28a996247d93618e9b99651e","rationale":"The direct HTTP composition contract owns this exact test-only resource wrapper through try-with-resources. It requires the explicit owner handle to inspect shared transport identity, contexts, and termination evidence. The wrapper closes that same already-counted owner without adding a generation or a synthetic guard; ordinary request tests now use scoped Soklet.run."}],
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/SokletManagedShutdownRegressionTests.java", "51b7255c5b819fb07d1f998b5c9fafa13ae0435b2223d122f71b5ad637e5dc62", [
    ["main",312,"b295c9cd1fa0da8132ddb6882b8cc2843cfb054dfd62886da4483ad033beb8c9",{"path":"src/test/java/com/soklet/SokletManagedShutdownRegressionTests.java","line":216,"lineSha256":"5c491a06383f491c63f3154b31c1c3bce0991e55960e3a2aa8341bbedc152f7a","rationale":"One source-bound parent test invokes this exact child through ProcessBuilder. It retains a ten-second readiness poll, ten-second exit wait, and five-second forced-reap finally under its sixty-second JUnit guard. The handoff child owns one public lifecycle with explicit1/1/1/1-second policy and a deliberately blocked daemon completion callback; it has no application cleanup. The obsolete console-warning child retires with configuration-warning delivery. The remaining child receives no synthetic JUnit guard; the real parent process deadline proves completion callback isolation."}],
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/SokletStartupCancellationRegressionTests.java", "2d2766b8e690fe92ac63c913bcb206e266c2ae932698b51db082a01767a610b4", [
    ["main",325,"aa806dcc2f437ce14905a433393a6af22e5a4d78dfe2c375fcf68d27f0c7283b",{"path":"src/test/java/com/soklet/SokletStartupCancellationRegressionTests.java","line":67,"lineSha256":"974b9cca29a11d103dce97fc63f68c860aa86f06a94b6077a5d9c1538552b0e2","rationale":"Two source-bound parent tests invoke this exact child through ProcessBuilder with a 25-second exit wait and five-second forced-reap finally under their sixty-second JUnit guard. The security-manager and denied-worker children select mutually exclusive helper modes, each owning one public lifecycle with explicit30/1/1/1-second policy and one-second cleanup. The remaining transport-start gate is released or aborted in finally after cancellation publication, and caller/worker joins remain bounded. Obsolete warning-worker gates retire with console-warning delivery. The child receives no synthetic JUnit guard; the actual parent process deadline contains failed or uncooperative cases."}],
  ]),
  ...reviewedOrphanFile("src/test/java/com/soklet/SokletInterruptDeliveryRegressionTests.java", "184ee305ddad0236515f25853819c03b4e470fa00cfc0feab9fa4aff4c4910d1", [
    ["main",337,"8446866f24caf4f6bdd0d787cfe65d87d90b5838050bc129827a687c79713ac7",{"path":"src/test/java/com/soklet/SokletInterruptDeliveryRegressionTests.java","line":181,"lineSha256":"99d30e85a0249ecbcd5eb011839f0d9582f202d0b49fc3b32d6ec3a7bd785168","rationale":"The two source-bound parent tests invoke this exact child through ProcessBuilder, wait at most fifteen seconds for exit, and forcibly reap a failed child within five more seconds under their ninety-second JUnit guards. The startup and cleanup modes each own one public lifecycle with explicit30/.1/.1/.1-second policy; cleanup mode adds one-second application cleanup. A five-second latch-only observation hook waits for the deliberately held channel-close callback to begin before allowing JVM exit. That callback must remain daemon-owned, and the parent's actual process deadline contains the old blocking implementation without lending the child a synthetic JUnit guard."}],
  ]),
], 'orphan lifecycle helper');

const LIFECYCLE_SIGNAL_PATTERN = /(?:\bSoklet(?:Application(?:Options)?|ProcessLifecycle|Config|Simulator)?\b|\b(?:Http|Sse|Mcp)Server\b|\bMcpHttpServerRuntime\b|\bTransportRuntime\b|\bInternalLifecycleCoordinator\b|\bSimulationSession\b|\bLifecyclePolicy\b|\b(?:startupTimeout|startupCancelationTimeout|gracefulShutdownTimeout|forcedShutdownTimeout)\s*\()/u;
const LIFECYCLE_EXECUTION_PATTERN = /(?:\bSokletSimulator\s*\.\s*run\s*\(|\bSoklet\s*\.\s*(?:fromConfig|run)\s*\(|\bSoklet(?:Application|ProcessLifecycle)\s*\.\s*run\s*\(|\.\s*(?:start|shutdown|requestShutdown|awaitShutdown|awaitShutdownAsync|awaitTermination)\s*\(|try\s*\(\s*Soklet\b|\bopenSimulationSession\s*\()/u;
const UNSCOPED_LIFECYCLE_EXECUTION_PATTERN = /(?:\bSoklet(?:Application|ProcessLifecycle|Simulator)?\s*\.\s*run\s*\(|\bSoklet\s*\.\s*(?:fromConfig|run)\s*\(|\bnew\s+SokletDirectLifecycle\s*\(|\b(?:SokletConfig|var)\s+[A-Za-z_$][\w$]*\s*=\s*SokletConfig\b|\bopenSimulationSession\s*\(|\.\s*(?:start|beginStart|markReady|runExternallyCoordinatedStart|commitExternallyCoordinatedGeneration|shutdown|requestShutdown|stop|requestStop|sealScope|awaitMcpScopeTermination|awaitShutdown|awaitShutdownAsync|awaitStop|awaitTermination|whenTerminated)\s*\(|::\s*(?:start|openMcpScope|shutdown|requestShutdown|stop)\b|try\s*\([^)]*\b(?:Soklet|[A-Za-z_$][\w$]*Harness)\b)/gu;
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
  if (NAMED_TIMEOUT_PATTERN.test(line)
      || (java && /\bassertTimeoutPreemptively\s*\(/u.test(line)))
    kinds.push('NAMED_TIMEOUT_CANDIDATE');
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
      /\bSoklet(?:Application|ProcessLifecycle)\s+([A-Za-z_$][\w$]*)\b/gu)]
      .map((match) => match[1]),
    ...[...masked.matchAll(
      /\bvar\s+([A-Za-z_$][\w$]*)\s*=\s*Soklet(?:Application|ProcessLifecycle)\s*\.\s*fromConfig\s*\(/gu)]
      .map((match) => match[1]),
  ])];
  const lifecycleReceiverNames = [...new Set([
    ...[...masked.matchAll(
      /\b(?:Soklet(?:\s*\.\s*DefaultSimulator)?|SokletApplication|SokletProcessLifecycle|SokletDirectLifecycle|HttpServer|SseServer|McpServer|TransportRuntime|InternalLifecycleCoordinator|SimulationSession|Fixture|Fleet|Graph|LifecycleHarness|Node|Owner|Runtime|[A-Za-z_$][\w$]*(?:Fixture|Fleet|Graph|Harness|HttpServer|SseServer|McpServer|LifecycleAdapter|LifecycleHarness|Node|Owner|PhaseGate|Runtime|RuntimeBridge|Simulator))\s+([A-Za-z_$][\w$]*)\b/gu)]
      .map((match) => match[1]),
    ...[...masked.matchAll(
      /\bvar\s+([A-Za-z_$][\w$]*)\s*=\s*(?:Soklet\s*\.\s*(?:fromConfig|run)\s*\(|Soklet(?:Application|ProcessLifecycle)\s*\.\s*fromConfig\s*\(|new\s+SokletDirectLifecycle\s*\(|(?:new\s+)?[A-Za-z_$][\w$]*Harness(?:\s*\.|\s*\())/gu)]
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
    /\bSoklet(?:Application|ProcessLifecycle)\s*\.\s*fromConfig\s*\(/gu)) {
    const open = factory.index + factory[0].lastIndexOf('(');
    const end = matchingParenthesisEnd(body, open);
    if (end === null) continue;
    const immediateRun = /^\s*\.\s*run\s*\(/u.exec(body.slice(end));
    if (immediateRun === null) continue;
    chainedApplicationRuns.push((body.slice(factory.index, end)
      + immediateRun[0]).replace(/[.*+?^${}()|[\]\\]/gu, '\\$&'));
  }
  const applicationRunPattern = () => new RegExp(
    `(?:(?:\\bSoklet(?:Application|ProcessLifecycle)?\\s*\\.\\s*run|\\b${applicationReceiver}\\s*\\.\\s*run)\\s*\\(|\\b(?:Soklet(?:Application|ProcessLifecycle)?|${applicationReceiver})\\s*::\\s*run\\b${chainedApplicationRuns.length === 0 ? '' : `|${[...new Set(chainedApplicationRuns)].join('|')}`}|\\bSokletProcessLifecycle\\s*\\.\\s*launch\\s*\\()`,
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
    || /\bShutdownCleanup\b/u.test(body.replace(
      /\(\s*ShutdownCleanup\s*\)\s*null\b/gu, ''));
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
  if (matches(/\bSoklet\s*\.\s*(?:fromConfig|run)\s*\(/u))
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
  if (matches(new RegExp(`\\b${lifecycleReceiver}\\s*\\.\\s*(?:shutdown|requestShutdown|stop|requestStop|recordExternallyCoordinatedShutdownIntent|sealScope)\\s*\\(`, 'iu'))
      || (matches(/\bnew\s+InternalLifecycleCoordinator\s*\(/u)
        && matches(/\.\s*shutdown\s*\(/u)))
    operations.push('SHUTDOWN_OR_STOP');
  else if (matches(new RegExp(`\\b${lifecycleReceiver}\\s*::\\s*(?:shutdown|requestShutdown|stop)\\b`, 'iu')))
    operations.push('SHUTDOWN_OR_STOP');
  if (matches(new RegExp(`\\b${lifecycleReceiver}\\s*\\.\\s*(?:awaitMcpScopeTermination|awaitShutdown|awaitShutdownAsync|awaitStop|awaitTermination|whenTerminated)\\s*\\(`, 'iu')))
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
    /^(?:shutdown|requestShutdown|stop)$/u.test(name))) operations.push('SHUTDOWN_OR_STOP');
  if ([...ambiguousReceiverSites.values()].includes('close'))
    operations.push('CLOSE');
  const orderedOperations = LIFECYCLE_OPERATIONS.filter((operation) =>
    operations.includes(operation));
  const constructionSiteCount = [...body.matchAll(
    /\bSoklet\s*\.\s*(?:fromConfig|run)\s*\(/gu)].length;
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
        // Scoped callbacks can own a lifecycle whose imported fixture only
        // builds its configuration. Keep that constructor's policy and source
        // provenance even when it no longer starts or closes the owner itself.
        const constructorFacts = constructors.map((method) =>
          directLifecycleFacts(method, context.fieldPolicies,
            context.durationConstants, context.numericConstants, new Set(),
            importedText));
        const ownsLifecycle = constructorFacts.some((facts) =>
          facts.hasExecution);
        if (!ownsLifecycle && !constructorFacts.some((facts) =>
          facts.observedOperations.includes('CONFIGURE_POLICY'))) break;
        contexts.set(importedPath, context);
        methods.push(...constructors.map((method) => ({
          ...method,
          importedConfigurationOnly: !ownsLifecycle,
        })));
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
      const facts = directLifecycleFacts(method, context.fieldPolicies,
        context.durationConstants, context.numericConstants,
        new Set(byName.keys()), context.text);
      // Configuration provenance does not prove which config a later owner
      // receives. Retain inherited defaults as a conservative candidate until
      // an exact source-bound policy review establishes that connection.
      if (method.importedConfigurationOnly)
        facts.literalPhasePolicies.push({ ...DEFAULT_PHASE_POLICY });
      return facts;
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
    /\bSoklet\s+warmupSoklet\s*=\s*crossFeatureSoklet\(/u,
    'MCP cross-feature single warmup owner');
  requirePattern(cross,
    /for\s*\(\s*int\s+shutdownCycle\s*=\s*0\s*;\s*shutdownCycle\s*<\s*PROFILE\.shutdownCycles\(\)\s*;\s*shutdownCycle\+\+\s*\)\s*\{/u,
    'MCP cross-feature profile-bounded shutdown cycle loop');
  requirePattern(cross,
    /\bSoklet\s+cycleSoklet\s*=\s*crossFeatureSoklet\(/u,
    'MCP cross-feature fresh owner per shutdown cycle');
  for (const owner of ['warmupSoklet', 'cycleSoklet']) {
    requirePattern(cross, new RegExp(
      `try\\s*\\(\\s*(?:@[\\w.]+\\s*\\([^)]*\\)\\s*)*AutoCloseable\\s+${owner}Shutdown\\s*=`, 'u'),
      `MCP cross-feature ${owner} explicit shutdown scope`);
  }
  requirePattern(cross, /\bcrossFeatureSoklet\s*\(/u,
    'MCP cross-feature exact two entries plus helper declaration', 3);
  requirePattern(cross, /\bSoklet\s*\.\s*(?:fromConfig|run)\s*\(/u,
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
  requirePattern(localization, /\bSoklet\s*\.\s*(?:fromConfig|run)\s*\(/u,
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
