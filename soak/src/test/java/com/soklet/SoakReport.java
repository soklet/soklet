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
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalLong;

import static java.util.Objects.requireNonNull;

/**
 * Small Markdown report writer for soak-test artifacts.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class SoakReport {
	private static final Object LOCK = new Object();
	private static boolean initialized;

	private SoakReport() {
		// Non-instantiable
	}

	static void recordPassedScenario(@NonNull String scenario,
																	 @NonNull String workload,
																	 @NonNull Duration elapsed,
																	 @NonNull SoakResourceSnapshot baseline,
																	 @NonNull SoakResourceSnapshot finalSnapshot,
																	 SoakResourceSnapshot.ResourceTolerance tolerance,
																	 @NonNull Map<@NonNull String, @NonNull String> observations) {
		requireNonNull(scenario);
		requireNonNull(workload);
		requireNonNull(elapsed);
		requireNonNull(baseline);
		requireNonNull(finalSnapshot);
		requireNonNull(tolerance);
		requireNonNull(observations);

		synchronized (LOCK) {
			try {
				Path reportPath = reportPath();
				initialize(reportPath);
				Files.writeString(reportPath, scenarioSection(scenario, workload, elapsed, baseline, finalSnapshot, tolerance, observations),
						StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
			} catch (IOException e) {
				throw new AssertionError("Unable to write soak report", e);
			}
		}
	}

	@NonNull
	static Map<@NonNull String, @NonNull String> observations(@NonNull String... keyValues) {
		requireNonNull(keyValues);

		if (keyValues.length % 2 != 0)
			throw new IllegalArgumentException("Observations must be provided as key/value pairs.");

		Map<String, String> observations = new LinkedHashMap<>();

		for (int i = 0; i < keyValues.length; i += 2)
			observations.put(requireNonNull(keyValues[i]), requireNonNull(keyValues[i + 1]));

		return Collections.unmodifiableMap(observations);
	}

	private static void initialize(@NonNull Path reportPath) throws IOException {
		requireNonNull(reportPath);

		if (initialized)
			return;

		Path parent = reportPath.getParent();

		if (parent != null)
			Files.createDirectories(parent);

		Files.writeString(reportPath, header(), StandardCharsets.UTF_8);
		initialized = true;
	}

	@NonNull
	private static Path reportPath() {
		String override = System.getenv("SOKLET_SOAK_REPORT");

		if (override == null || override.isBlank())
			override = System.getProperty("soklet.soak.reportPath");

		if (override == null || override.isBlank())
			override = "target/soak-report.md";

		return Path.of(override);
	}

	@NonNull
	private static String header() {
		Runtime.Version version = Runtime.version();

		return """
				# Soklet Soak Report

				- Generated: %s
				- Mode: %s
				- Java: %s
				- JVM: %s %s
				- OS: %s %s (%s)
				- Available processors: %d
				- Process: %s

				""".formatted(
				Instant.now(),
				"1".equals(System.getenv("SOKLET_SOAK")) ? "soak (SOKLET_SOAK=1)" : "smoke",
				version,
				System.getProperty("java.vm.name"),
				System.getProperty("java.vm.version"),
				System.getProperty("os.name"),
				System.getProperty("os.version"),
				System.getProperty("os.arch"),
				Runtime.getRuntime().availableProcessors(),
				ManagementFactory.getRuntimeMXBean().getName());
	}

	@NonNull
	private static String scenarioSection(@NonNull String scenario,
																					@NonNull String workload,
																					@NonNull Duration elapsed,
																					@NonNull SoakResourceSnapshot baseline,
																					@NonNull SoakResourceSnapshot finalSnapshot,
																					SoakResourceSnapshot.ResourceTolerance tolerance,
																					@NonNull Map<@NonNull String, @NonNull String> observations) {
		StringBuilder report = new StringBuilder(1024);
		report.append("## ").append(scenario).append("\n\n");
		report.append("- Result: PASS\n");
		report.append("- Elapsed: ").append(formatDuration(elapsed)).append("\n");
		report.append("- Workload: ").append(workload).append("\n");

		for (Map.Entry<String, String> observation : observations.entrySet())
			report.append("- ").append(observation.getKey()).append(": ").append(observation.getValue()).append("\n");

		report.append("- Baseline resources: ").append(formatSnapshot(baseline)).append("\n");
		report.append("- Final resources: ").append(formatSnapshot(finalSnapshot)).append("\n");
		report.append("- Resource deltas: ").append(formatDelta(baseline, finalSnapshot)).append("\n");
		report.append("- Tolerance: ").append(formatTolerance(tolerance)).append("\n\n");
		return report.toString();
	}

	@NonNull
	private static String formatSnapshot(@NonNull SoakResourceSnapshot snapshot) {
		requireNonNull(snapshot);
		return "fd=%s, heap=%s, threads=%d".formatted(
				formatOptionalLong(snapshot.openFileDescriptorCount()),
				formatBytes(snapshot.usedHeapBytes()),
				snapshot.liveThreadCount());
	}

	@NonNull
	private static String formatDelta(@NonNull SoakResourceSnapshot baseline,
																		@NonNull SoakResourceSnapshot finalSnapshot) {
		requireNonNull(baseline);
		requireNonNull(finalSnapshot);
		String fdDelta = "unavailable";

		if (baseline.openFileDescriptorCount().isPresent() && finalSnapshot.openFileDescriptorCount().isPresent())
			fdDelta = signed(finalSnapshot.openFileDescriptorCount().getAsLong() - baseline.openFileDescriptorCount().getAsLong());

		return "fd=%s, heap=%s, threads=%s".formatted(
				fdDelta,
				formatSignedBytes(finalSnapshot.usedHeapBytes() - baseline.usedHeapBytes()),
				signed(finalSnapshot.liveThreadCount() - baseline.liveThreadCount()));
	}

	@NonNull
	private static String formatTolerance(SoakResourceSnapshot.ResourceTolerance tolerance) {
		requireNonNull(tolerance);
		return "fd<=+%d, heap<=+%s, threads<=+%d".formatted(
				tolerance.maxOpenFileDescriptorGrowth(),
				formatBytes(tolerance.maxHeapGrowthBytes()),
				tolerance.maxLiveThreadGrowth());
	}

	@NonNull
	private static String formatOptionalLong(@NonNull OptionalLong value) {
		requireNonNull(value);
		return value.isPresent() ? Long.toString(value.getAsLong()) : "unavailable";
	}

	@NonNull
	private static String signed(long value) {
		return value >= 0 ? "+" + value : Long.toString(value);
	}

	@NonNull
	private static String formatSignedBytes(long bytes) {
		return (bytes >= 0 ? "+" : "") + formatBytes(bytes);
	}

	@NonNull
	private static String formatBytes(long bytes) {
		double mib = bytes / (1024.0 * 1024.0);
		return "%d bytes (%.2f MiB)".formatted(bytes, mib);
	}

	@NonNull
	private static String formatDuration(@NonNull Duration duration) {
		requireNonNull(duration);
		return "%.3fs".formatted(duration.toNanos() / 1_000_000_000.0);
	}
}
