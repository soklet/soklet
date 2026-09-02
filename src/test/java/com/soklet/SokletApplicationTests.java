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

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@Timeout(value = 60, unit = TimeUnit.SECONDS)
class SokletApplicationTests {
	@Test
	void factoryRejectsNullConfigImmediately() {
		Assertions.assertThrows(NullPointerException.class,
				() -> SokletApplication.fromConfig(null));
	}

	@Test
	void cleanupAcceptsEveryPositiveNanosecondBoundary() {
		for (Duration timeout : List.of(Duration.ofNanos(1),
				Duration.ofSeconds(1), Duration.ofNanos(Long.MAX_VALUE))) {
			ShutdownCleanup cleanup = ShutdownCleanup.fromTimeoutAndAction(
					timeout, shutdownResult -> { });

			Assertions.assertEquals(timeout, cleanup.getTimeout());
		}
	}

	@Test
	void cleanupRejectsNullZeroNegativeAndOverflowValues() {
		ShutdownCleanup.Action action = shutdownResult -> { };

		Assertions.assertThrows(NullPointerException.class, () ->
				ShutdownCleanup.fromTimeoutAndAction(null, action));
		Assertions.assertThrows(NullPointerException.class, () ->
				ShutdownCleanup.fromTimeoutAndAction(Duration.ofSeconds(1), null));
		Assertions.assertThrows(NullPointerException.class, () ->
				ShutdownCleanup.fromTimeoutAndAction(null, null));
		Assertions.assertThrows(IllegalArgumentException.class, () ->
				ShutdownCleanup.fromTimeoutAndAction(Duration.ZERO, action));
		Assertions.assertThrows(IllegalArgumentException.class, () ->
				ShutdownCleanup.fromTimeoutAndAction(Duration.ofNanos(-1), action));
		IllegalArgumentException overflow = Assertions.assertThrows(
				IllegalArgumentException.class, () ->
						ShutdownCleanup.fromTimeoutAndAction(
								Duration.ofSeconds(Long.MAX_VALUE), action));
		Assertions.assertInstanceOf(ArithmeticException.class,
				overflow.getCause());
	}

	@Test
	void malformedRunArgumentsDoNotConsumeTheApplicationRunClaim() {
		SokletApplication application = SokletApplication.fromConfig(config());
		ShutdownCleanup cleanup = ShutdownCleanup.fromTimeoutAndAction(
				Duration.ofSeconds(1), shutdownResult -> { });

		Assertions.assertThrows(NullPointerException.class,
				() -> application.run((ShutdownTrigger[]) null));
		Assertions.assertThrows(NullPointerException.class,
				() -> application.run(ShutdownTrigger.ENTER_KEY, null));
		Assertions.assertThrows(NullPointerException.class,
				() -> application.run((ShutdownCleanup) null,
						ShutdownTrigger.ENTER_KEY));
		Assertions.assertThrows(NullPointerException.class,
				() -> application.run(cleanup, (ShutdownTrigger[]) null));
		Assertions.assertThrows(NullPointerException.class,
				() -> application.run(cleanup, ShutdownTrigger.ENTER_KEY, null));

		RuntimeException exactFailure = new RuntimeException("factory failed");
		RuntimeException firstAttempt = Assertions.assertThrows(
				RuntimeException.class, () -> application.run(environment(
						(config, services, publisher) -> { throw exactFailure; }),
						ShutdownTrigger.ENTER_KEY,
						ShutdownTrigger.ENTER_KEY));
		Assertions.assertSame(exactFailure, firstAttempt);
	}

	@Test
	void failedAttemptStillConsumesTheApplicationRunClaim() {
		RuntimeException exactFailure = new RuntimeException("factory failed");
		SokletApplication application = SokletApplication.fromConfig(config());
		SokletApplicationEnvironment environment = environment(
				(config, services, publisher) -> { throw exactFailure; });

		RuntimeException first = Assertions.assertThrows(RuntimeException.class,
				() -> application.run(environment));
		IllegalStateException second = Assertions.assertThrows(
				IllegalStateException.class, application::run);

		Assertions.assertSame(exactFailure, first);
		Assertions.assertTrue(second.getMessage().contains("already claimed"));
	}

	@Test
	void invalidInjectedEnvironmentStillConsumesTheApplicationRunClaim() {
		SokletApplication application = SokletApplication.fromConfig(config());

		Assertions.assertThrows(NullPointerException.class,
				() -> application.run((SokletApplicationEnvironment) null));
		IllegalStateException second = Assertions.assertThrows(
				IllegalStateException.class,
				() -> application.run((SokletApplicationEnvironment) null));

		Assertions.assertTrue(second.getMessage().contains("already claimed"));
	}

	@Test
	void concurrentRunIsRejectedWhileTheFirstClaimIsActive() throws Exception {
		CountDownLatch factoryEntered = new CountDownLatch(1);
		CountDownLatch releaseFactory = new CountDownLatch(1);
		RuntimeException exactFailure = new RuntimeException("factory released");
		SokletApplication application = SokletApplication.fromConfig(config());
		SokletApplicationEnvironment environment = environment(
				(config, services, publisher) -> {
					factoryEntered.countDown();
					try {
						if (!releaseFactory.await(5, TimeUnit.SECONDS))
							throw new AssertionError(
									"Timed out waiting to release the first run");
					} catch (InterruptedException exception) {
						Thread.currentThread().interrupt();
						throw new AssertionError(
								"Interrupted while waiting to release the first run",
								exception);
					}
					throw exactFailure;
				});
		AtomicReference<Throwable> firstFailure = new AtomicReference<>();
		Thread firstRun = new Thread(() -> {
			try {
				application.run(environment);
			} catch (Throwable failure) {
				firstFailure.set(failure);
			}
		}, "soklet-application-first-run-test");
		firstRun.setDaemon(true);
		firstRun.start();

		Assertions.assertTrue(factoryEntered.await(5, TimeUnit.SECONDS));
		IllegalStateException concurrent;
		try {
			concurrent = Assertions.assertThrows(IllegalStateException.class,
					() -> application.run(environment));
		} finally {
			releaseFactory.countDown();
		}
		firstRun.join(5_000L);

		Assertions.assertFalse(firstRun.isAlive());
		Assertions.assertSame(exactFailure, firstFailure.get());
		Assertions.assertTrue(concurrent.getMessage().contains("already claimed"));
	}

	@NonNull
	private static SokletApplicationEnvironment environment(
			@NonNull SokletApplicationRuntimeFactory runtimeFactory) {
		return new SokletApplicationEnvironment(
				LifecycleRuntimeServices.system(), SokletApplication.SYSTEM_PROCESS,
				SokletApplication.SYSTEM_INPUT, snapshot -> { }, runtimeFactory,
				(name, task) -> new Thread(task, name));
	}

	@NonNull
	private static SokletConfig config() {
		return SokletConfig.withHttpServer(HttpServer.withPort(0).build()).build();
	}
}
