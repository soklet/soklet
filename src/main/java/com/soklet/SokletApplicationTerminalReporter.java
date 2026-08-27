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

import javax.annotation.concurrent.ThreadSafe;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import static java.util.Objects.requireNonNull;

/** Compact, bounded, non-recursive stderr terminal diagnostics. */
@ThreadSafe
final class DefaultLifecycleTerminalReporter implements LifecycleTerminalReporter {
	static final int MAXIMUM_UTF8_BYTES = 16 * 1_024;
	static final int MAXIMUM_THROWABLE_MESSAGE_CODE_POINTS = 512;

	@NonNull
	private final OutputStream errorStream;
	@NonNull
	private final Object writeLock;

	DefaultLifecycleTerminalReporter(@NonNull OutputStream errorStream) {
		this.errorStream = requireNonNull(errorStream);
		this.writeLock = new Object();
	}

	@NonNull
	static DefaultLifecycleTerminalReporter system() {
		return new DefaultLifecycleTerminalReporter(System.err);
	}

	@Override
	public void report(@NonNull SokletApplicationTerminalSnapshot snapshot) {
		SokletApplicationTerminalSnapshot exactSnapshot = requireNonNull(snapshot);
		if (!shouldEmit(exactSnapshot))
			return;
		byte[] rendered = utf8Prefix(render(exactSnapshot), MAXIMUM_UTF8_BYTES);
		synchronized (this.writeLock) {
			try {
				this.errorStream.write(rendered);
				this.errorStream.flush();
			} catch (IOException exception) {
				throw new IllegalStateException(
						"Unable to write the Soklet terminal report", exception);
			}
		}
	}

	private static boolean shouldEmit(
			@NonNull SokletApplicationTerminalSnapshot snapshot) {
		InternalShutdownResult result = snapshot.coreSnapshot().result();
		return snapshot.primaryOutcome()
				!= SokletApplicationPrimaryOutcome.EXPECTED
				|| result.startupDisposition() == InternalStartupDisposition.FAILED
				|| result.startupDisposition() == InternalStartupDisposition.TIMED_OUT
				|| result.disposition() == InternalShutdownDisposition.FORCED
				|| !result.isComplete()
				|| result.participantResults().stream().anyMatch(participant ->
						participant.disposition()
								== InternalParticipantShutdownDisposition
										.UNEXPECTED_TERMINATION)
				|| snapshot.cleanupOutcome().failed();
	}

	@NonNull
	private static String render(
			@NonNull SokletApplicationTerminalSnapshot snapshot) {
		StringBuilder report = new StringBuilder(2_048);
		InternalLifecycleCoreSnapshot core = snapshot.coreSnapshot();
		InternalShutdownResult result = core.result();
		report.append("soklet-terminal-report\n")
				.append("startup=").append(result.startupDisposition()).append('\n')
				.append("shutdown=").append(result.disposition()).append('\n')
				.append("primary=").append(snapshot.primaryOutcome()).append('\n');
		snapshot.primaryFailure().ifPresent(failure -> report
				.append("primaryFailure=").append(safeThrowable(failure))
				.append('\n'));

		Map<InternalParticipantKind, SokletApplicationParticipantDiagnostics>
				diagnostics = snapshot.coreDiagnostics().participantDiagnostics();
		for (InternalParticipantShutdownResult participant
				: result.participantResults()) {
			SokletApplicationParticipantDiagnostics participantDiagnostics =
					diagnostics.get(participant.kind());
			report.append("participant.").append(participant.kind())
					.append(".disposition=").append(participant.disposition())
					.append('\n');
			report.append("participant.").append(participant.kind())
					.append(".residual=").append(participant.residualActivity())
					.append('\n');
			if (participantDiagnostics != null) {
				report.append("participant.").append(participant.kind())
						.append(".authority=")
						.append(participantDiagnostics.authority()).append('\n')
						.append("participant.").append(participant.kind())
						.append(".members=")
						.append(participantDiagnostics.memberCount())
						.append(",failed=")
						.append(participantDiagnostics.failedMembers())
						.append(",proven=")
						.append(participantDiagnostics.provenMembers())
						.append(",truncated=")
						.append(participantDiagnostics.truncated()).append('\n');
			}
			if (!participant.failures().isEmpty())
				report.append("participant.").append(participant.kind())
						.append(".failure=")
						.append(safeThrowable(participant.failures().get(0)))
						.append('\n');
		}

		InternalShutdownCleanupOutcome cleanup = snapshot.cleanupOutcome();
		report.append("cleanup=").append(cleanup.disposition()).append('\n')
				.append("cleanupWorkerMayRemain=")
				.append(cleanup.workerMayRemain()).append('\n');
		cleanup.configuredTimeout().ifPresent(timeout -> report
				.append("cleanupTimeoutNanos=").append(timeout.toNanos())
				.append('\n'));
		cleanup.failure().ifPresent(failure -> report
				.append("cleanupFailure=").append(safeThrowable(failure))
				.append('\n'));
		result.retentionSummary().ifPresent(retention -> report
				.append("retainedCounts=").append(retention.counts()).append('\n')
				.append("retainedSummary=").append(retention.summary()).append('\n'));

		LifecycleTransitionSnapshot transitions = snapshot.coreDiagnostics()
				.transitionSnapshot();
		report.append("observerAccepted=")
				.append(transitions.acceptedRecords())
				.append(",pending=").append(transitions.pendingRecords())
				.append(",active=").append(transitions.callbackActive())
				.append(",sealed=").append(transitions.sealed())
				.append(",disabled=").append(transitions.disabled())
				.append(",failed=").append(transitions.failedCallbacks())
				.append('\n');
		transitions.firstFailureSummary().ifPresent(summary -> report
				.append("observerFirstFailure=").append(summary).append('\n'));

		InternalLifecyclePolicy policy = snapshot.coreDiagnostics()
				.lifecyclePolicy();
		report.append("startupBudgetNanos=")
				.append(policy.startupTimeout().map(Duration::toNanos)
						.map(String::valueOf).orElse("unbounded"))
				.append('\n')
				.append("startupCancellationBudgetNanos=")
				.append(policy.startupCancellationTimeout().toNanos()).append('\n')
				.append("gracefulBudgetNanos=")
				.append(policy.gracefulShutdownTimeout().toNanos()).append('\n')
				.append("forcedBudgetNanos=")
				.append(policy.forcedShutdownTimeout().toNanos()).append('\n')
				.append("lifecycleElapsedNanos=")
				.append(elapsed(snapshot.coreDiagnostics().lifecycleBeganNanos(),
						core.publicationNanos())).append('\n')
				.append("runnerToCoreElapsedNanos=")
				.append(elapsed(snapshot.runnerBeganNanos(),
						core.publicationNanos())).append('\n')
				.append("terminalReportBudgetNanos=")
				.append(Duration.ofMillis(250).toNanos()).append('\n');
		return report.toString();
	}

	private static long elapsed(long beganNanos, long endedNanos) {
		return endedNanos >= beganNanos ? endedNanos - beganNanos : Long.MAX_VALUE;
	}

	@NonNull
	static String safeThrowable(@NonNull Throwable throwable) {
		Throwable exactThrowable = requireNonNull(throwable);
		String message;
		try {
			message = exactThrowable.getMessage();
		} catch (Throwable ignored) {
			message = "<message unavailable>";
		}
		return exactThrowable.getClass().getName() + ": "
				+ escapeAndCap(message == null ? "" : message,
						MAXIMUM_THROWABLE_MESSAGE_CODE_POINTS);
	}

	@NonNull
	static String escapeAndCap(@NonNull String value, int maximumCodePoints) {
		if (maximumCodePoints < 0)
			throw new IllegalArgumentException("maximumCodePoints must be >= 0");
		String exactValue = requireNonNull(value);
		StringBuilder escaped = new StringBuilder();
		int emitted = 0;
		for (int offset = 0; offset < exactValue.length()
				&& emitted < maximumCodePoints;) {
			int codePoint = exactValue.codePointAt(offset);
			offset += Character.charCount(codePoint);
			String replacement = switch (codePoint) {
				case '\n' -> "\\n";
				case '\r' -> "\\r";
				case '\t' -> "\\t";
				default -> Character.isISOControl(codePoint)
						? String.format("\\u%04X", codePoint)
						: new String(Character.toChars(codePoint));
			};
			int replacementPoints = replacement.codePointCount(0,
					replacement.length());
			if (replacementPoints > maximumCodePoints - emitted)
				break;
			escaped.append(replacement);
			emitted += replacementPoints;
		}
		return escaped.toString();
	}

	@NonNull
	static byte[] utf8Prefix(@NonNull String value, int maximumBytes) {
		if (maximumBytes < 0)
			throw new IllegalArgumentException("maximumBytes must be >= 0");
		ByteArrayOutputStream output = new ByteArrayOutputStream(
				Math.min(maximumBytes, 4_096));
		String exactValue = requireNonNull(value);
		for (int offset = 0; offset < exactValue.length();) {
			int codePoint = exactValue.codePointAt(offset);
			offset += Character.charCount(codePoint);
			byte[] encoded = new String(Character.toChars(codePoint))
					.getBytes(StandardCharsets.UTF_8);
			if (output.size() + encoded.length > maximumBytes)
				break;
			output.writeBytes(encoded);
		}
		return output.toByteArray();
	}
}
