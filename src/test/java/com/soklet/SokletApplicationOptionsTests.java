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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;

class SokletApplicationOptionsTests {
	@Test
	void defaultsContainNeitherAdditionalTriggersNorCleanup() {
		SokletApplicationOptions options = SokletApplicationOptions.fromDefaults();

		Assertions.assertEquals(Set.of(), options.getAdditionalTriggers());
		Assertions.assertTrue(options.getShutdownCleanup().isEmpty());
		Assertions.assertTrue(options.getShutdownCleanupTimeout().isEmpty());
		Assertions.assertThrows(UnsupportedOperationException.class,
				() -> options.getAdditionalTriggers().add(ShutdownTrigger.ENTER_KEY));
	}

	@Test
	void builtOptionsAreImmutableSnapshotsWithPairedCleanupValues() {
		SokletApplicationOptions.Builder builder = SokletApplicationOptions.builder();
		SokletApplicationOptions beforeMutation = builder.build();
		ShutdownCleanup cleanup = result -> { };
		Duration timeout = Duration.ofSeconds(7);

		builder.additionalTrigger(ShutdownTrigger.ENTER_KEY)
				.additionalTrigger(ShutdownTrigger.ENTER_KEY)
				.afterCompleteShutdown(timeout, cleanup);
		SokletApplicationOptions afterMutation = builder.build();

		Assertions.assertEquals(Set.of(), beforeMutation.getAdditionalTriggers());
		Assertions.assertTrue(beforeMutation.getShutdownCleanup().isEmpty());
		Assertions.assertEquals(Set.of(ShutdownTrigger.ENTER_KEY),
				afterMutation.getAdditionalTriggers());
		Assertions.assertSame(cleanup,
				afterMutation.getShutdownCleanup().orElseThrow());
		Assertions.assertEquals(timeout,
				afterMutation.getShutdownCleanupTimeout().orElseThrow());
		Assertions.assertThrows(UnsupportedOperationException.class,
				afterMutation.getAdditionalTriggers()::clear);
	}

	@Test
	void nullAdditionalTriggerIsRejectedImmediately() {
		SokletApplicationOptions.Builder builder = SokletApplicationOptions.builder();

		Assertions.assertThrows(NullPointerException.class,
				() -> builder.additionalTrigger(null));
	}

	@Test
	void everyPositiveSignedNanosecondBoundaryIsAccepted() {
		ShutdownCleanup cleanup = result -> { };
		for (Duration timeout : List.of(Duration.ofNanos(1), Duration.ofSeconds(1),
				Duration.ofNanos(Long.MAX_VALUE))) {
			SokletApplicationOptions options = SokletApplicationOptions.builder()
					.afterCompleteShutdown(timeout, cleanup)
					.build();

			Assertions.assertSame(cleanup,
					options.getShutdownCleanup().orElseThrow());
			Assertions.assertEquals(timeout,
					options.getShutdownCleanupTimeout().orElseThrow());
		}
	}

	@Test
	void cleanupConfigurationRejectsUnpairedNullZeroNegativeAndOverflowValues() {
		ShutdownCleanup cleanup = result -> { };

		Assertions.assertThrows(NullPointerException.class, () ->
				SokletApplicationOptions.builder()
						.afterCompleteShutdown(null, cleanup).build());
		Assertions.assertThrows(NullPointerException.class, () ->
				SokletApplicationOptions.builder()
						.afterCompleteShutdown(Duration.ofSeconds(1), null).build());
		Assertions.assertThrows(NullPointerException.class, () ->
				SokletApplicationOptions.builder()
						.afterCompleteShutdown(null, null).build());
		Assertions.assertThrows(IllegalArgumentException.class, () ->
				SokletApplicationOptions.builder()
						.afterCompleteShutdown(Duration.ZERO, cleanup).build());
		Assertions.assertThrows(IllegalArgumentException.class, () ->
				SokletApplicationOptions.builder()
						.afterCompleteShutdown(Duration.ofNanos(-1), cleanup).build());
		IllegalArgumentException overflow = Assertions.assertThrows(
				IllegalArgumentException.class, () -> SokletApplicationOptions.builder()
						.afterCompleteShutdown(Duration.ofSeconds(Long.MAX_VALUE), cleanup)
						.build());
		Assertions.assertInstanceOf(ArithmeticException.class, overflow.getCause());
	}
}
