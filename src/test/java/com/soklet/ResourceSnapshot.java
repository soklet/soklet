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

import javax.annotation.concurrent.Immutable;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.time.Duration;
import java.util.OptionalLong;

import static java.util.Objects.requireNonNull;

/**
 * Lightweight resource snapshot for CI-safe leak tripwires.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
@Immutable
record ResourceSnapshot(
		@NonNull OptionalLong openFileDescriptorCount,
		long usedHeapBytes,
		int liveThreadCount
) {
	ResourceSnapshot {
		requireNonNull(openFileDescriptorCount);
	}

	@NonNull
	static ResourceSnapshot captureAfterGc() throws InterruptedException {
		forceGc();
		return capture();
	}

	static void assertReturnsNear(@NonNull String scenario,
																@NonNull ResourceSnapshot baseline,
																@NonNull Duration timeout,
																@NonNull ResourceTolerance tolerance) throws InterruptedException {
		requireNonNull(scenario);
		requireNonNull(baseline);
		requireNonNull(timeout);
		requireNonNull(tolerance);

		long deadline = System.nanoTime() + timeout.toNanos();
		ResourceSnapshot last = captureAfterGc();

		while (System.nanoTime() < deadline) {
			if (last.isNear(baseline, tolerance))
				return;

			Thread.sleep(100L);
			last = captureAfterGc();
		}

		Assertions.fail("%s resources did not return near baseline within %s. Baseline=%s, last=%s, tolerance=%s"
				.formatted(scenario, timeout, baseline, last, tolerance));
	}

	@NonNull
	private static ResourceSnapshot capture() {
		MemoryUsage heapUsage = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();

		return new ResourceSnapshot(
				currentOpenFileDescriptorCount(),
				heapUsage.getUsed(),
				ManagementFactory.getThreadMXBean().getThreadCount());
	}

	private static void forceGc() throws InterruptedException {
		for (int i = 0; i < 3; i++) {
			System.gc();
			Thread.sleep(50L);
		}
	}

	@NonNull
	private static OptionalLong currentOpenFileDescriptorCount() {
		java.lang.management.OperatingSystemMXBean operatingSystem = ManagementFactory.getOperatingSystemMXBean();

		if (operatingSystem instanceof com.sun.management.UnixOperatingSystemMXBean unixOperatingSystem)
			return OptionalLong.of(unixOperatingSystem.getOpenFileDescriptorCount());

		return OptionalLong.empty();
	}

	private boolean isNear(@NonNull ResourceSnapshot baseline,
												 @NonNull ResourceTolerance tolerance) {
		requireNonNull(baseline);
		requireNonNull(tolerance);

		if (this.openFileDescriptorCount.isPresent() && baseline.openFileDescriptorCount().isPresent()
				&& this.openFileDescriptorCount.getAsLong() > baseline.openFileDescriptorCount().getAsLong() + tolerance.maxOpenFileDescriptorGrowth())
			return false;

		if (this.usedHeapBytes > baseline.usedHeapBytes() + tolerance.maxHeapGrowthBytes())
			return false;

		return this.liveThreadCount <= baseline.liveThreadCount() + tolerance.maxLiveThreadGrowth();
	}

	@Immutable
	record ResourceTolerance(
			long maxOpenFileDescriptorGrowth,
			long maxHeapGrowthBytes,
			int maxLiveThreadGrowth
	) {}
}
