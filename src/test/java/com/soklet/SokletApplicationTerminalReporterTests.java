/*
 * Copyright 2022-2026 Revetware LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.soklet;

import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Timeout(value = 60, unit = TimeUnit.SECONDS)
class SokletApplicationTerminalReporterTests {
	private static final long CORE_PUBLICATION_NANOS = 200L;

	@Test
	void emissionPolicyIsLimitedToTheReviewedTerminalConditions() {
		assertSilent(result(InternalShutdownDisposition.NOT_STARTED,
				InternalStartupDisposition.NOT_ATTEMPTED, List.of()), notConfigured());
		assertSilent(result(InternalShutdownDisposition.NOT_STARTED,
				InternalStartupDisposition.CANCELLED, List.of()), notConfigured());
		assertSilent(result(InternalShutdownDisposition.GRACEFUL,
				InternalStartupDisposition.READY,
				List.of(participant(InternalParticipantKind.HTTP,
						InternalParticipantShutdownDisposition.GRACEFUL_TERMINATION))),
				succeededCleanup());

		assertEmits(result(InternalShutdownDisposition.NOT_STARTED,
				InternalStartupDisposition.FAILED, List.of()), notConfigured());
		assertEmits(result(InternalShutdownDisposition.NOT_STARTED,
				InternalStartupDisposition.TIMED_OUT, List.of()), notConfigured());
		assertEmits(result(InternalShutdownDisposition.FORCED,
				InternalStartupDisposition.READY,
				List.of(participant(InternalParticipantKind.HTTP,
						InternalParticipantShutdownDisposition.FORCED_TERMINATION))),
				notConfigured());
		assertEmits(result(InternalShutdownDisposition.INCOMPLETE,
				InternalStartupDisposition.READY,
				List.of(participant(InternalParticipantKind.HTTP,
						InternalParticipantShutdownDisposition.TERMINATION_UNKNOWN))),
				notConfigured());
		assertEmits(result(InternalShutdownDisposition.GRACEFUL,
				InternalStartupDisposition.READY,
				List.of(participant(InternalParticipantKind.HTTP,
						InternalParticipantShutdownDisposition.UNEXPECTED_TERMINATION))),
				notConfigured());
		assertEmits(result(InternalShutdownDisposition.GRACEFUL,
				InternalStartupDisposition.READY, List.of()), failedCleanup());
		assertEmits(result(InternalShutdownDisposition.GRACEFUL,
				InternalStartupDisposition.READY, List.of()), timedOutCleanup());

		InternalShutdownResult processOwnershipResult = result(
				InternalShutdownDisposition.NOT_STARTED,
				InternalStartupDisposition.NOT_ATTEMPTED, List.of());
		ByteArrayOutputStream processOwnershipOutput = new ByteArrayOutputStream();
		new DefaultLifecycleTerminalReporter(processOwnershipOutput).report(snapshot(
				processOwnershipResult,
				SokletApplicationPrimaryOutcome.PROCESS_OWNERSHIP_FAILURE,
				Optional.of(new SecurityException("hooks unavailable")),
				notConfigured(), diagnostics(processOwnershipResult), 50L));
		Assertions.assertTrue(processOwnershipOutput.size() > 0);
	}

	@Test
	void reportContainsEveryStructuredTerminalField() {
		IllegalStateException participantFailure =
				new IllegalStateException("participant-failure");
		InternalParticipantShutdownResult participant = new InternalParticipantShutdownResult(
				InternalParticipantKind.HTTP,
				InternalParticipantShutdownDisposition.RESIDUAL_ACTIVITY,
				List.of(participantFailure),
				EnumSet.of(InternalResidualActivityKind.CALLBACK,
						InternalResidualActivityKind.LIFECYCLE_CALL));
		Object retainedGraph = new Object();
		InternalShutdownResult result = result(InternalShutdownDisposition.INCOMPLETE,
				InternalStartupDisposition.FAILED, List.of(participant))
				.withRetentionAnchor(new LifecycleRetentionAnchor(retainedGraph,
						Map.of(InternalResidualActivityKind.CALLBACK, 2,
								InternalResidualActivityKind.LIFECYCLE_CALL, 1),
						"retained\nsummary"));
		TimeoutExceptionForTest cleanupFailure =
				new TimeoutExceptionForTest("cleanup-timeout");
		InternalShutdownCleanupOutcome cleanup = new InternalShutdownCleanupOutcome(
				InternalShutdownCleanupDisposition.TIMED_OUT,
				Optional.of(Duration.ofSeconds(3)), Optional.of(cleanupFailure), true,
				CORE_PUBLICATION_NANOS);
		EnumMap<InternalParticipantKind, SokletApplicationParticipantDiagnostics>
				diagnostics = new EnumMap<>(InternalParticipantKind.class);
		diagnostics.put(InternalParticipantKind.HTTP,
				new SokletApplicationParticipantDiagnostics(
						InternalTerminationAuthority.TRANSPORT_ATTESTED,
						4, 1, 2, true));
		SokletApplicationCoreDiagnostics coreDiagnostics =
				new SokletApplicationCoreDiagnostics(
						new LifecycleTransitionSnapshot(4, 2, true, true, false,
								1, Optional.of(IllegalStateException.class.getName())),
						diagnostics, InternalLifecyclePolicy.defaults(), 100L);
		IllegalArgumentException primaryFailure =
				new IllegalArgumentException("primary-failure");
		SokletApplicationTerminalSnapshot snapshot = snapshot(result,
				SokletApplicationPrimaryOutcome.STARTUP_FAILURE,
				Optional.of(primaryFailure), cleanup, coreDiagnostics, 50L);

		String report = render(snapshot);

		Assertions.assertTrue(report.startsWith("soklet-terminal-report\n"));
		Assertions.assertTrue(report.contains("startup=FAILED\n"));
		Assertions.assertTrue(report.contains("shutdown=INCOMPLETE\n"));
		Assertions.assertTrue(report.contains("primary=STARTUP_FAILURE\n"));
		Assertions.assertTrue(report.contains("primaryFailure="
				+ IllegalArgumentException.class.getName() + "\n"));
		Assertions.assertTrue(report.contains(
				"participant.HTTP.disposition=RESIDUAL_ACTIVITY\n"));
		Assertions.assertTrue(report.contains(
				"participant.HTTP.residual=[CALLBACK, LIFECYCLE_CALL]\n"));
		Assertions.assertTrue(report.contains(
				"participant.HTTP.authority=TRANSPORT_ATTESTED\n"));
		Assertions.assertTrue(report.contains(
				"participant.HTTP.members=4,failed=1,proven=2,truncated=true\n"));
		Assertions.assertTrue(report.contains("participant.HTTP.failure="
				+ IllegalStateException.class.getName() + "\n"));
		Assertions.assertTrue(report.contains("cleanup=TIMED_OUT\n"));
		Assertions.assertTrue(report.contains("cleanupWorkerMayRemain=true\n"));
		Assertions.assertTrue(report.contains("cleanupTimeoutNanos=3000000000\n"));
		Assertions.assertTrue(report.contains("cleanupFailure="
				+ TimeoutExceptionForTest.class.getName() + "\n"));
		Assertions.assertTrue(report.contains(
				"retainedCounts={CALLBACK=2, LIFECYCLE_CALL=1}\n"));
		Assertions.assertTrue(report.contains("retainedSummary=retained\\nsummary\n"));
		Assertions.assertTrue(report.contains(
				"observerAccepted=4,pending=2,active=true,sealed=true,disabled=false,failed=1\n"));
		Assertions.assertTrue(report.contains(
				"observerFirstFailure=" + IllegalStateException.class.getName()
						+ "\n"));
		for (String messageCanary : List.of("primary-failure",
				"participant-failure", "cleanup-timeout"))
			Assertions.assertFalse(report.contains(messageCanary), report);
		Assertions.assertTrue(report.contains("startupBudgetNanos=30000000000\n"));
		Assertions.assertTrue(report.contains(
				"startupCancellationBudgetNanos=2000000000\n"));
		Assertions.assertTrue(report.contains("gracefulBudgetNanos=15000000000\n"));
		Assertions.assertTrue(report.contains("forcedBudgetNanos=3000000000\n"));
		Assertions.assertTrue(report.contains("lifecycleElapsedNanos=100\n"));
		Assertions.assertTrue(report.contains("runnerToCoreElapsedNanos=150\n"));
		Assertions.assertTrue(report.contains("terminalReportBudgetNanos=250000000\n"));
	}

	@Test
	void safeThrowableUsesOnlyTheClassAndNeverReadsTheMessage() {
		String messageCanary = "terminal-throwable-message-secret";
		AtomicInteger messageReads = new AtomicInteger();
		RuntimeException failure = new RuntimeException(messageCanary) {
			@Override
			public String getMessage() {
				messageReads.incrementAndGet();
				throw new AssertionError("Throwable messages must not be rendered");
			}
		};

		String rendered = Assertions.assertDoesNotThrow(
				() -> DefaultLifecycleTerminalReporter.safeThrowable(failure));

		Assertions.assertEquals(failure.getClass().getName(), rendered);
		Assertions.assertFalse(rendered.contains(messageCanary), rendered);
		Assertions.assertEquals(0, messageReads.get());
	}

	@Test
	void reporterNeverTraversesCauseSuppressedOrStackGraphs() {
		PoisonThrowable cause = new PoisonThrowable("cause");
		PoisonThrowable suppressed = new PoisonThrowable("suppressed");
		TraversalGuardThrowable primary =
				new TraversalGuardThrowable("safe-primary", cause);
		primary.addSuppressed(suppressed);
		InternalShutdownResult result = result(InternalShutdownDisposition.NOT_STARTED,
				InternalStartupDisposition.FAILED, List.of());
		SokletApplicationTerminalSnapshot snapshot = snapshot(result,
				SokletApplicationPrimaryOutcome.STARTUP_FAILURE,
				Optional.of(primary), notConfigured(), diagnostics(result), 50L);

		String report = Assertions.assertDoesNotThrow(() -> render(snapshot));

		Assertions.assertTrue(report.contains(
				TraversalGuardThrowable.class.getName()));
		Assertions.assertFalse(report.contains("safe-primary"));
		Assertions.assertFalse(report.contains("cause"));
		Assertions.assertFalse(report.contains("suppressed"));
		Assertions.assertEquals(0, cause.messageReads());
		Assertions.assertEquals(0, suppressed.messageReads());
	}

	@Test
	void incompleteReportUsesOnlyThePrecomputedRetentionSummary() {
		Object retainedGraph = new Object() {
			@Override
			public String toString() {
				throw new AssertionError("retained graph must not be rendered");
			}
		};
		InternalParticipantShutdownResult unknown = participant(
				InternalParticipantKind.MCP,
				InternalParticipantShutdownDisposition.TERMINATION_UNKNOWN);
		InternalShutdownResult result = result(InternalShutdownDisposition.INCOMPLETE,
				InternalStartupDisposition.READY, List.of(unknown))
				.withRetentionAnchor(new LifecycleRetentionAnchor(retainedGraph,
						Map.of(InternalResidualActivityKind.LIFECYCLE_CALL, 3),
						"only-this-bounded-summary"));

		String report = Assertions.assertDoesNotThrow(() -> render(snapshot(result,
				SokletApplicationPrimaryOutcome.INCOMPLETE_SHUTDOWN,
				Optional.empty(), notConfigured(), diagnostics(result), 50L)));

		Assertions.assertTrue(report.contains("retainedCounts={LIFECYCLE_CALL=3}"));
		Assertions.assertTrue(report.contains(
				"retainedSummary=only-this-bounded-summary"));
		Assertions.assertTrue(result.retentionSummary().isPresent());
	}

	@Test
	void oversizedReportIsBoundedAndRemainsValidUtf8() throws Exception {
		String emoji = "\uD83D\uDE00";
		RuntimeException largeFailure = new RuntimeException(emoji.repeat(600));
		List<InternalParticipantShutdownResult> participants = new ArrayList<>();
		for (InternalParticipantKind kind : InternalParticipantKind.values())
			participants.add(new InternalParticipantShutdownResult(kind,
					InternalParticipantShutdownDisposition.RESIDUAL_ACTIVITY,
					List.of(largeFailure), Set.of(InternalResidualActivityKind.CALLBACK)));
		InternalShutdownResult result = result(InternalShutdownDisposition.INCOMPLETE,
				InternalStartupDisposition.FAILED, participants)
				.withRetentionAnchor(new LifecycleRetentionAnchor(new Object(),
						Map.of(InternalResidualActivityKind.CALLBACK, 4),
						emoji.repeat(2_000)));
		InternalShutdownCleanupOutcome cleanup = new InternalShutdownCleanupOutcome(
				InternalShutdownCleanupDisposition.FAILED,
				Optional.of(Duration.ofSeconds(1)), Optional.of(largeFailure), false,
				CORE_PUBLICATION_NANOS);
		SokletApplicationCoreDiagnostics ordinaryDiagnostics = diagnostics(result);
		SokletApplicationCoreDiagnostics oversizedDiagnostics =
				new SokletApplicationCoreDiagnostics(
						new LifecycleTransitionSnapshot(1, 0, false, true, false,
								1, Optional.of(emoji.repeat(5_000))),
						ordinaryDiagnostics.participantDiagnostics(),
						ordinaryDiagnostics.lifecyclePolicy(),
						ordinaryDiagnostics.lifecycleBeganNanos());
		SokletApplicationTerminalSnapshot snapshot = snapshot(result,
				SokletApplicationPrimaryOutcome.STARTUP_FAILURE,
				Optional.of(largeFailure), cleanup, oversizedDiagnostics, 50L);
		ByteArrayOutputStream output = new ByteArrayOutputStream();

		new DefaultLifecycleTerminalReporter(output).report(snapshot);

		byte[] bytes = output.toByteArray();
		Assertions.assertTrue(bytes.length > 8_192,
				"fixture must exercise a genuinely large report");
		Assertions.assertTrue(bytes.length
				<= DefaultLifecycleTerminalReporter.MAXIMUM_UTF8_BYTES);
		String decoded = StandardCharsets.UTF_8.newDecoder()
				.onMalformedInput(CodingErrorAction.REPORT)
				.onUnmappableCharacter(CodingErrorAction.REPORT)
				.decode(ByteBuffer.wrap(bytes)).toString();
		Assertions.assertArrayEquals(bytes, decoded.getBytes(StandardCharsets.UTF_8));
	}

	@Test
	void outputFailureIsControlledAndContainedByTheFinalizationReporterSeam() {
		IOException writeFailure = new IOException("stderr unavailable");
		DefaultLifecycleTerminalReporter reporter =
				new DefaultLifecycleTerminalReporter(failingOutput(writeFailure));
		InternalShutdownResult forced = result(InternalShutdownDisposition.FORCED,
				InternalStartupDisposition.READY,
				List.of(participant(InternalParticipantKind.HTTP,
						InternalParticipantShutdownDisposition.FORCED_TERMINATION)));
		SokletApplicationTerminalSnapshot directSnapshot = snapshot(forced,
				SokletApplicationPrimaryOutcome.EXPECTED, Optional.empty(),
				notConfigured(), diagnostics(forced), 50L);

		IllegalStateException reporterFailure = Assertions.assertThrows(
				IllegalStateException.class, () -> reporter.report(directSnapshot));
		Assertions.assertSame(writeFailure, reporterFailure.getCause());

		AtomicLong now = new AtomicLong(CORE_PUBLICATION_NANOS);
		LifecycleWorkers workers = new LifecycleWorkers((name, task) -> task.run());
		SokletApplicationFinalization finalization = new SokletApplicationFinalization(
				SokletApplicationOptions.fromDefaults(),
				new LifecycleRuntimeServices(now::get, workers),
				new DefaultLifecycleTerminalReporter(failingOutput(writeFailure)));
		finalization.diagnosticsSupplier(() -> diagnostics(forced));
		finalization.publishCoreSnapshot(new InternalLifecycleCoreSnapshot(
				forced, CORE_PUBLICATION_NANOS));

		SokletApplicationFinalization.AwaitResult awaitResult =
				Assertions.assertDoesNotThrow(finalization::awaitCompletion);

		Assertions.assertEquals(InternalShutdownCleanupDisposition.NOT_CONFIGURED,
				awaitResult.cleanupOutcome().disposition());
		Assertions.assertTrue(finalization.isComplete());
		Assertions.assertEquals(1,
				workers.created(LifecycleWorkers.Role.TERMINAL_REPORTER));
	}

	private static void assertSilent(@NonNull InternalShutdownResult result,
			@NonNull InternalShutdownCleanupOutcome cleanup) {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		new DefaultLifecycleTerminalReporter(output).report(snapshot(result,
				SokletApplicationPrimaryOutcome.EXPECTED, Optional.empty(), cleanup,
				diagnostics(result), 50L));
		Assertions.assertEquals(0, output.size());
	}

	private static void assertEmits(@NonNull InternalShutdownResult result,
			@NonNull InternalShutdownCleanupOutcome cleanup) {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		new DefaultLifecycleTerminalReporter(output).report(snapshot(result,
				SokletApplicationPrimaryOutcome.EXPECTED, Optional.empty(), cleanup,
				diagnostics(result), 50L));
		Assertions.assertTrue(output.size() > 0);
	}

	@NonNull
	private static String render(@NonNull SokletApplicationTerminalSnapshot snapshot) {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		new DefaultLifecycleTerminalReporter(output).report(snapshot);
		return output.toString(StandardCharsets.UTF_8);
	}

	@NonNull
	private static SokletApplicationTerminalSnapshot snapshot(
			@NonNull InternalShutdownResult result,
			@NonNull SokletApplicationPrimaryOutcome primaryOutcome,
			@NonNull Optional<? extends Throwable> primaryFailure,
			@NonNull InternalShutdownCleanupOutcome cleanup,
			@NonNull SokletApplicationCoreDiagnostics diagnostics,
			long runnerBeganNanos) {
		return new SokletApplicationTerminalSnapshot(
				new InternalLifecycleCoreSnapshot(result, CORE_PUBLICATION_NANOS),
				primaryOutcome, primaryFailure, cleanup, diagnostics, runnerBeganNanos,
				CORE_PUBLICATION_NANOS + Duration.ofMillis(250).toNanos());
	}

	@NonNull
	private static SokletApplicationCoreDiagnostics diagnostics(
			@NonNull InternalShutdownResult result) {
		EnumMap<InternalParticipantKind, SokletApplicationParticipantDiagnostics>
				participantDiagnostics = new EnumMap<>(InternalParticipantKind.class);
		for (InternalParticipantShutdownResult participant : result.participantResults())
			participantDiagnostics.put(participant.kind(),
					new SokletApplicationParticipantDiagnostics(
							InternalTerminationAuthority.FRAMEWORK_PROVEN,
							1, 0, 1, false));
		return new SokletApplicationCoreDiagnostics(
				new LifecycleTransitionSnapshot(0, 0, false, true, false,
						0, Optional.empty()),
				participantDiagnostics, InternalLifecyclePolicy.defaults(), 100L);
	}

	@NonNull
	private static InternalShutdownResult result(
			@NonNull InternalShutdownDisposition disposition,
			@NonNull InternalStartupDisposition startupDisposition,
			@NonNull List<InternalParticipantShutdownResult> participants) {
		return new InternalShutdownResult(disposition, startupDisposition, participants);
	}

	@NonNull
	private static InternalParticipantShutdownResult participant(
			@NonNull InternalParticipantKind kind,
			@NonNull InternalParticipantShutdownDisposition disposition) {
		return new InternalParticipantShutdownResult(kind, disposition,
				List.of(), Set.of());
	}

	@NonNull
	private static InternalShutdownCleanupOutcome notConfigured() {
		return new InternalShutdownCleanupOutcome(
				InternalShutdownCleanupDisposition.NOT_CONFIGURED,
				Optional.empty(), Optional.empty(), false, CORE_PUBLICATION_NANOS);
	}

	@NonNull
	private static InternalShutdownCleanupOutcome succeededCleanup() {
		return new InternalShutdownCleanupOutcome(
				InternalShutdownCleanupDisposition.SUCCEEDED,
				Optional.of(Duration.ofSeconds(1)), Optional.empty(), false,
				CORE_PUBLICATION_NANOS);
	}

	@NonNull
	private static InternalShutdownCleanupOutcome failedCleanup() {
		return new InternalShutdownCleanupOutcome(
				InternalShutdownCleanupDisposition.FAILED,
				Optional.of(Duration.ofSeconds(1)),
				Optional.of(new IllegalStateException("cleanup failed")), false,
				CORE_PUBLICATION_NANOS);
	}

	@NonNull
	private static InternalShutdownCleanupOutcome timedOutCleanup() {
		return new InternalShutdownCleanupOutcome(
				InternalShutdownCleanupDisposition.TIMED_OUT,
				Optional.of(Duration.ofSeconds(1)),
				Optional.of(new TimeoutExceptionForTest("cleanup timed out")), true,
				CORE_PUBLICATION_NANOS);
	}

	@NonNull
	private static OutputStream failingOutput(@NonNull IOException failure) {
		return new OutputStream() {
			@Override
			public void write(int value) throws IOException {
				throw failure;
			}
		};
	}

	private static final class TimeoutExceptionForTest extends Exception {
		private TimeoutExceptionForTest(@NonNull String message) {
			super(message);
		}
	}

	private static final class PoisonThrowable extends RuntimeException {
		private final AtomicInteger messageReads = new AtomicInteger();

		private PoisonThrowable(@NonNull String message) {
			super(message);
		}

		@Override
		public String getMessage() {
			this.messageReads.incrementAndGet();
			throw new AssertionError("nested throwable must not be rendered");
		}

		private int messageReads() {
			return this.messageReads.get();
		}
	}

	private static final class TraversalGuardThrowable extends RuntimeException {
		private TraversalGuardThrowable(@NonNull String message,
				@NonNull Throwable cause) {
			super(message, cause);
		}

		@Override
		public String getMessage() {
			throw new AssertionError("throwable message must not be rendered");
		}

		@Override
		public synchronized Throwable getCause() {
			throw new AssertionError("cause graph must not be traversed");
		}

		@Override
		public StackTraceElement[] getStackTrace() {
			throw new AssertionError("stack graph must not be traversed");
		}

		@Override
		public String toString() {
			throw new AssertionError("Throwable.toString() must not be used");
		}
	}
}
