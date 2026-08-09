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
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * Loads the selected, immutable soak workload profile.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@ThreadSafe
final class SoakProfiles {
	private static final String PROFILE_ENVIRONMENT_VARIABLE = "SOKLET_SOAK_PROFILE";
	private static final Set<String> PROFILE_NAMES = Set.of("smoke", "nightly");
	private static final Set<String> REQUIRED_KEYS = Set.of(
			"http.abortConnectTimeoutMillis",
			"http.abortIterationsPerClient",
			"http.cleanRequestsPerClient",
			"http.concurrentClients",
			"http.resourceTolerance.maxHeapGrowthBytes",
			"http.resourceTolerance.maxLiveThreadGrowth",
			"http.resourceTolerance.maxOpenFileDescriptorGrowth",
			"http.runTimeoutMillis",
			"http.serverConcurrency",
			"http.settleTimeoutMillis",
			"http.socketPendingConnectionLimit",
			"mcp.clientSocketTimeoutMillis",
			"mcp.concurrentClients",
			"mcp.cyclesPerClient",
			"mcp.keepAliveIntervalMillis",
			"mcp.maximumSubscriptionDurationMillis",
			"mcp.maximumSubscriptionsPerPrincipal",
			"mcp.requestHandlerConcurrency",
			"mcp.requestHandlerQueueCapacity",
			"mcp.requestTimeoutMillis",
			"mcp.resourceTolerance.maxHeapGrowthBytes",
			"mcp.resourceTolerance.maxLiveThreadGrowth",
			"mcp.resourceTolerance.maxOpenFileDescriptorGrowth",
			"mcp.runTimeoutMillis",
			"mcp.settleTimeoutMillis",
			"mcp.shutdownCycles",
			"mcp.shutdownTimeoutMillis",
			"mcp.streamQueueCapacity",
			"mcp.writeTimeoutMillis",
			"realtime.clientSocketTimeoutMillis",
			"realtime.concurrentClients",
			"realtime.resourceTolerance.maxHeapGrowthBytes",
			"realtime.resourceTolerance.maxLiveThreadGrowth",
			"realtime.resourceTolerance.maxOpenFileDescriptorGrowth",
			"realtime.runTimeoutMillis",
			"realtime.settleTimeoutMillis",
			"realtime.sseConcurrentConnectionLimit",
			"realtime.sseInterStreamPauseMillis",
			"realtime.sseStreamsPerClient"
	);
	private static final SelectedProfile SELECTED = loadSelected();

	private SoakProfiles() {
		// Non-instantiable.
	}

	@NonNull
	static SelectedProfile selected() {
		return SELECTED;
	}

	@NonNull
	private static SelectedProfile loadSelected() {
		String profileName = System.getenv(PROFILE_ENVIRONMENT_VARIABLE);

		if (profileName == null || profileName.isBlank())
			profileName = "smoke";

		if (!PROFILE_NAMES.contains(profileName))
			throw new IllegalStateException("%s must be exactly one of %s, but was '%s'"
					.formatted(PROFILE_ENVIRONMENT_VARIABLE, PROFILE_NAMES.stream().sorted().toList(), profileName));

		String resourceName = "/com/soklet/soak-profiles/" + profileName + ".properties";
		byte[] bytes;

		try (InputStream inputStream = SoakProfiles.class.getResourceAsStream(resourceName)) {
			if (inputStream == null)
				throw new IllegalStateException("Missing checked-in soak profile " + resourceName);

			bytes = inputStream.readAllBytes();
		} catch (IOException e) {
			throw new IllegalStateException("Unable to read checked-in soak profile " + resourceName, e);
		}

		String configuration = new String(bytes, StandardCharsets.UTF_8);
		Map<String, String> values = parseStrictConfiguration(resourceName, configuration);

		if (!values.keySet().equals(REQUIRED_KEYS)) {
			Set<String> missing = new java.util.TreeSet<>(REQUIRED_KEYS);
			missing.removeAll(values.keySet());
			Set<String> unexpected = new java.util.TreeSet<>(values.keySet());
			unexpected.removeAll(REQUIRED_KEYS);
			throw new IllegalStateException("Invalid soak profile keys for %s; missing=%s unexpected=%s"
					.formatted(resourceName, missing, unexpected));
		}

		for (Map.Entry<String, String> entry : values.entrySet()) {
			long value;

			try {
				value = Long.parseLong(entry.getValue());
			} catch (NumberFormatException e) {
				throw new IllegalStateException("Soak profile value must be an integer: " + entry, e);
			}

			if (value <= 0)
				throw new IllegalStateException("Soak profile value must be positive: " + entry);
		}

		return new SelectedProfile(profileName, resourceName, values, configuration, sha256(bytes));
	}

	@NonNull
	private static Map<String, String> parseStrictConfiguration(@NonNull String resourceName,
																						 @NonNull String configuration) {
		requireNonNull(resourceName);
		requireNonNull(configuration);

		if (!configuration.endsWith("\n"))
			throw new IllegalStateException("Soak profile must end with LF: " + resourceName);

		List<String> lines = configuration.lines().toList();
		List<String> sortedLines = lines.stream().sorted().toList();

		if (!lines.equals(sortedLines))
			throw new IllegalStateException("Soak profile keys must be sorted: " + resourceName);

		Map<String, String> values = new LinkedHashMap<>();

		for (String line : lines) {
			int equals = line.indexOf('=');

			if (equals <= 0 || equals == line.length() - 1 || line.indexOf('=', equals + 1) >= 0)
				throw new IllegalStateException("Malformed soak profile line in %s: %s".formatted(resourceName, line));

			String key = line.substring(0, equals);
			String value = line.substring(equals + 1);

			if (values.putIfAbsent(key, value) != null)
				throw new IllegalStateException("Duplicate soak profile key in %s: %s".formatted(resourceName, key));
		}

		return Collections.unmodifiableMap(values);
	}

	@NonNull
	private static String sha256(byte[] bytes) {
		try {
			return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is unavailable", e);
		}
	}

	record SelectedProfile(@NonNull String name,
								 @NonNull String resourceName,
								 @NonNull Map<String, String> values,
								 @NonNull String canonicalConfiguration,
								 @NonNull String sha256) {
		SelectedProfile {
			requireNonNull(name);
			requireNonNull(resourceName);
			requireNonNull(values);
			requireNonNull(canonicalConfiguration);
			requireNonNull(sha256);
		}

		int integer(@NonNull String key) {
			long value = number(key);

			if (value > Integer.MAX_VALUE)
				throw new IllegalStateException("Soak profile value exceeds Integer.MAX_VALUE: " + key);

			return (int) value;
		}

		long number(@NonNull String key) {
			requireNonNull(key);
			String value = values.get(key);

			if (value == null)
				throw new IllegalStateException("Missing soak profile value: " + key);

			return Long.parseLong(value);
		}

		@NonNull
		Duration durationMillis(@NonNull String key) {
			return Duration.ofMillis(number(key));
		}
	}
}
