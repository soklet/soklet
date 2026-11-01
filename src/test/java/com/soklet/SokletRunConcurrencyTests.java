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

import com.soklet.annotation.GET;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Timeout(value = 60, unit = TimeUnit.SECONDS)
final class SokletRunConcurrencyTests {
	@TempDir
	Path directory;

	@Test
	void preInterruptedStartupCancelsAndFinishesCleanupBeforeThrowing() throws Exception {
		AtomicReference<Throwable> failure = new AtomicReference<>();
		AtomicBoolean cleaned = new AtomicBoolean();
		AtomicBoolean interrupted = new AtomicBoolean();
		Thread caller = new Thread(() -> {
			Thread.currentThread().interrupt();
			try {
				Soklet unexpected = Soklet.run(config(),
						ShutdownCleanup.fromTimeoutAndAction(Duration.ofSeconds(3),
								result -> cleaned.set(true)));
				unexpected.requestShutdown();
				failure.set(new AssertionError("Interrupted startup reached readiness"));
			} catch (Throwable thrown) {
				failure.set(thrown);
			} finally {
				interrupted.set(Thread.currentThread().isInterrupted());
			}
		});
		caller.start();
		caller.join(10_000);
		Assertions.assertFalse(caller.isAlive());
		SokletStartupException startup = Assertions.assertInstanceOf(
				SokletStartupException.class, failure.get());
		Assertions.assertEquals(StartupDisposition.CANCELED, startup.getStartupDisposition());
		Assertions.assertTrue(startup.getShutdownResult().isComplete());
		Assertions.assertTrue(cleaned.get());
		Assertions.assertTrue(interrupted.get());
	}

	@Test
	void blockingAsyncConsumerCannotDelayBlockingWaitOrKeepLifecycleOwnerAlive()
			throws Exception {
		Soklet soklet = Soklet.run(config());
		CountDownLatch callbackEntered = new CountDownLatch(1);
		CountDownLatch releaseCallback = new CountDownLatch(1);
		AtomicReference<Thread> callbackThread = new AtomicReference<>();
		soklet.awaitShutdownAsync().thenRun(() -> {
			callbackThread.set(Thread.currentThread());
			callbackEntered.countDown();
			try {
				releaseCallback.await();
			} catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
			}
		});
		try {
			soklet.requestShutdown();
			Assertions.assertTrue(callbackEntered.await(5, TimeUnit.SECONDS));
			Assertions.assertTrue(soklet.awaitShutdown().isComplete());
			Assertions.assertTrue(callbackThread.get().isDaemon(),
					"Arbitrary async consumers must not retain JVM liveness");
		} finally {
			releaseCallback.countDown();
			soklet.requestShutdown();
			soklet.awaitShutdown();
		}
	}

	@Test
	void enterKeyRequestsShutdownThroughThePublicRunApi() throws Exception {
		assertChildFinalizes("enter");
	}

	@Test
	void automaticJvmHookJoinsApplicationCleanup() throws Exception {
		assertChildFinalizes("signal");
	}

	@Test
	void finalizationSurvivesReturningFromMainWithoutAPublicWaiter() throws Exception {
		assertChildFinalizes("return");
	}

	private void assertChildFinalizes(String mode) throws Exception {
		Path ready = this.directory.resolve(mode + ".ready");
		Path cleaned = this.directory.resolve(mode + ".cleaned");
		Path output = this.directory.resolve(mode + ".log");
		Process child = new ProcessBuilder(
				Path.of(System.getProperty("java.home"), "bin", "java").toString(),
				"-cp", System.getProperty("surefire.test.class.path",
						System.getProperty("java.class.path")),
				Child.class.getName(), mode, ready.toString(), cleaned.toString())
				.redirectErrorStream(true).redirectOutput(output.toFile()).start();
		try {
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
			while (!Files.exists(ready) && child.isAlive() && System.nanoTime() < deadline)
				Thread.sleep(10);
			Assertions.assertTrue(Files.exists(ready), () -> childOutput(output));
			if (mode.equals("enter")) {
				child.getOutputStream().write('\n');
				child.getOutputStream().flush();
			} else if (mode.equals("signal")) {
				child.destroy();
			}
			Assertions.assertTrue(child.waitFor(10, TimeUnit.SECONDS),
					() -> childOutput(output));
			Assertions.assertTrue(Files.exists(cleaned), () -> childOutput(output));
			Assertions.assertEquals("complete", Files.readString(cleaned));
			if (!mode.equals("signal"))
				Assertions.assertEquals(0, child.exitValue(), () -> childOutput(output));
		} finally {
			if (child.isAlive()) {
				child.destroyForcibly();
				child.waitFor(5, TimeUnit.SECONDS);
			}
		}
	}

	private static String childOutput(Path output) {
		try {
			return Files.readString(output);
		} catch (Exception failure) {
			return failure.toString();
		}
	}

	private static SokletConfig config() {
		return SokletConfig.withHttpServer(HttpServer.withPort(0).host("127.0.0.1").build())
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(Resource.class)))
				.lifecyclePolicy(LifecyclePolicy.builder()
						.startupTimeout(Duration.ofSeconds(5))
						.startupCancelationTimeout(Duration.ofSeconds(1))
						.gracefulShutdownTimeout(Duration.ofSeconds(1))
						.forcedShutdownTimeout(Duration.ofSeconds(1)).build())
				.build();
	}

	public static final class Resource {
		@GET("/ready")
		public String ready() {
			return "ready";
		}
	}

	public static final class Child {
		public static void main(String[] args) throws Exception {
			ShutdownCleanup cleanup = ShutdownCleanup.fromTimeoutAndAction(
					Duration.ofSeconds(3), result -> {
						// Let transport listeners exit before proving process-liveness retention.
						Thread.sleep(100);
						Files.writeString(Path.of(args[2]),
								result.isComplete() ? "complete" : "incomplete", StandardCharsets.UTF_8);
					});
			Soklet soklet = Soklet.run(config(), cleanup);
			if (args[0].equals("enter")) {
				Thread input = new Thread(() -> {
					try {
						new java.io.BufferedReader(new java.io.InputStreamReader(System.in,
								StandardCharsets.UTF_8)).readLine();
						soklet.requestShutdown();
					} catch (java.io.IOException failure) {
						throw new IllegalStateException(failure);
					}
				}, "application-console-input");
				input.setDaemon(true);
				input.start();
			}
			Files.writeString(Path.of(args[1]), "ready");
			if (args[0].equals("return"))
				soklet.requestShutdown();
			// Deliberately do not await: the lifecycle owner/hook must finish cleanup.
		}
	}
}
