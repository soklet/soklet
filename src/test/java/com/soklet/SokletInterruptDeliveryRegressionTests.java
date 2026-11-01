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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.spi.AbstractInterruptibleChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Interrupting a worker must not run blocking NIO close work on lifecycle owners. */
@Timeout(value = 90, unit = TimeUnit.SECONDS)
final class SokletInterruptDeliveryRegressionTests {
	@TempDir
	Path directory;

	@Test
	void startupCancellationSettlesWhileInterruptCloseRemainsBlocked() throws Exception {
		BlockingCloseChannel channel = new BlockingCloseChannel(null);
		CountDownLatch callerReturned = new CountDownLatch(1);
		CountDownLatch stopped = new CountDownLatch(1);
		AtomicReference<Throwable> startupFailure = new AtomicReference<>();
		AtomicReference<Soklet> unexpectedHandle = new AtomicReference<>();
		AtomicBoolean interruptRestored = new AtomicBoolean();
		AtomicBoolean cleaned = new AtomicBoolean();
		LifecycleObserver observer = new LifecycleObserver() {
			@Override
			public void didStopSoklet(ShutdownResult result) {
				stopped.countDown();
			}
		};
		SokletConfig configuration = config(transport(channel), Set.of(observer));
		Thread caller = new Thread(() -> {
			try {
				unexpectedHandle.set(Soklet.run(configuration,
						ShutdownCleanup.fromTimeoutAndAction(Duration.ofSeconds(1),
								result -> cleaned.set(true))));
			} catch (Throwable failure) {
				startupFailure.set(failure);
			} finally {
				interruptRestored.set(Thread.currentThread().isInterrupted());
				callerReturned.countDown();
			}
		}, "interrupt-close-startup-caller");
		caller.setDaemon(true);
		caller.start();
		try {
			Assertions.assertTrue(channel.readEntered.await(5, TimeUnit.SECONDS));
			caller.interrupt();
			Assertions.assertTrue(channel.closeEntered.await(5, TimeUnit.SECONDS));
			Assertions.assertTrue(callerReturned.await(5, TimeUnit.SECONDS),
					"Cancellation must not join the channel-close callback run by interrupt");
			Assertions.assertNull(unexpectedHandle.get());
			SokletStartupException canceled = Assertions.assertInstanceOf(
					SokletStartupException.class, startupFailure.get());
			Assertions.assertEquals(StartupDisposition.CANCELED, canceled.getStartupDisposition());
			Assertions.assertFalse(canceled.getShutdownResult().isComplete(),
					"The held interrupt callback leaves startup work without termination proof");
			Assertions.assertFalse(cleaned.get(), "Incomplete shutdown must skip application cleanup");
			Assertions.assertTrue(interruptRestored.get());
			Assertions.assertEquals(1L, channel.releaseClose.getCount());
			Assertions.assertTrue(channel.closingThread.get().isDaemon());
			Assertions.assertNotSame(caller, channel.closingThread.get());
		} finally {
			channel.release();
			caller.interrupt();
			Assertions.assertTrue(callerReturned.await(5, TimeUnit.SECONDS));
			Soklet handle = unexpectedHandle.get();
			if (handle != null) {
				handle.requestShutdown();
				try {
					handle.awaitShutdown();
				} catch (SokletLifecycleException ignored) {
					// Emergency retirement must not obscure the original assertion.
				}
			}
			Assertions.assertTrue(stopped.await(5, TimeUnit.SECONDS));
			caller.join(5_000);
			Assertions.assertFalse(caller.isAlive());
			channel.joinWorkers();
		}
	}

	@Test
	void cleanupTimeoutSettlesBothAwaitMethodsWhileInterruptCloseRemainsBlocked() throws Exception {
		BlockingCloseChannel channel = new BlockingCloseChannel(null);
		Soklet soklet = Soklet.run(config(transport(null), Set.of()),
				ShutdownCleanup.fromTimeoutAndAction(Duration.ofSeconds(1),
						result -> channel.read()));
		CompletableFuture<ShutdownResult> async = soklet.awaitShutdownAsync().toCompletableFuture();
		CountDownLatch waiterReturned = new CountDownLatch(1);
		AtomicReference<Throwable> waiterFailure = new AtomicReference<>();
		Thread waiter = new Thread(() -> {
			try {
				soklet.awaitShutdown();
			} catch (Throwable failure) {
				waiterFailure.set(failure);
			} finally {
				waiterReturned.countDown();
			}
		}, "interrupt-close-cleanup-waiter");
		waiter.setDaemon(true);
		waiter.start();
		try {
			soklet.requestShutdown();
			Assertions.assertTrue(channel.readEntered.await(5, TimeUnit.SECONDS));
			Assertions.assertTrue(channel.closeEntered.await(5, TimeUnit.SECONDS));
			ExecutionException failure = Assertions.assertThrows(ExecutionException.class,
					() -> async.get(5, TimeUnit.SECONDS));
			SokletShutdownCleanupException timedOut = Assertions.assertInstanceOf(
					SokletShutdownCleanupException.class, failure.getCause());
			Assertions.assertEquals(ShutdownCleanupFailureReason.TIMED_OUT,
					timedOut.getShutdownCleanupFailureReason());
			Assertions.assertTrue(timedOut.getShutdownResult().isComplete());
			Assertions.assertTrue(waiterReturned.await(5, TimeUnit.SECONDS),
					"The synchronous await must settle independently of close cleanup");
			Assertions.assertSame(timedOut, waiterFailure.get());
			Assertions.assertEquals(1L, channel.releaseClose.getCount());
			Assertions.assertTrue(channel.closingThread.get().isDaemon());
			Assertions.assertNotEquals("soklet-process-lifecycle", channel.closingThread.get().getName());
		} finally {
			channel.release();
			soklet.requestShutdown();
			try {
				soklet.awaitShutdown();
			} catch (SokletLifecycleException ignored) {
				// The cleanup timeout is the expected terminal failure.
			}
			Assertions.assertTrue(waiterReturned.await(5, TimeUnit.SECONDS));
			waiter.join(5_000);
			Assertions.assertFalse(waiter.isAlive());
			channel.joinWorkers();
		}
	}

	@Test
	void jvmShutdownDoesNotJoinAStartupInterruptCloseCallback() throws Exception {
		assertChild("startup");
	}

	@Test
	void jvmShutdownDoesNotJoinACleanupInterruptCloseCallback() throws Exception {
		assertChild("cleanup");
	}

	private void assertChild(String mode) throws Exception {
		Path marker = this.directory.resolve(mode + ".marker");
		Path output = this.directory.resolve(mode + ".log");
		Process child = new ProcessBuilder(
				Path.of(System.getProperty("java.home"), "bin", "java").toString(),
				"-cp",
				System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
				Child.class.getName(), mode, marker.toString())
				.redirectErrorStream(true).redirectOutput(output.toFile()).start();
		try {
			Assertions.assertTrue(child.waitFor(15, TimeUnit.SECONDS),
					() -> "JVM shutdown joined blocked interrupt-close cleanup: " + childOutput(output));
			Assertions.assertEquals(0, child.exitValue(), () -> childOutput(output));
			Assertions.assertTrue(Files.exists(marker),
					() -> "Interrupt delivery did not reach the close callback: " + childOutput(output));
			Assertions.assertEquals("daemon=true", Files.readString(marker),
					"The channel close callback must not execute on an owner or JVM hook");
		} finally {
			if (child.isAlive()) {
				child.destroyForcibly();
				Assertions.assertTrue(child.waitFor(5, TimeUnit.SECONDS));
			}
		}
	}

	private static SokletConfig config(HttpServer transport, Set<LifecycleObserver> observers) {
		return SokletConfig.withHttpServer(transport)
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(Resource.class)))
				.lifecycleObservers(observers)
				.lifecyclePolicy(LifecyclePolicy.builder()
						.startupTimeout(Duration.ofSeconds(30))
						.startupCancelationTimeout(Duration.ofMillis(100))
						.gracefulShutdownTimeout(Duration.ofMillis(100))
						.forcedShutdownTimeout(Duration.ofMillis(100)).build())
				.build();
	}

	private static HttpServer transport(BlockingCloseChannel startupChannel) {
		return new HttpServer() {
			private final TransportIdentity identity = TransportIdentity.create();

			@Override
			public TransportIdentity getTransportIdentity() {
				return this.identity;
			}

			@Override
			public TransportRuntime attach(HttpTransportAttachmentContext attachment,
					StartupContext startup) {
				TransportTerminationSignal terminated = attachment.getTransportTerminationSignal();
				return new TransportRuntime() {
					@Override
					public void start(StartupContext context) {
						if (startupChannel != null) {
							try {
								startupChannel.read();
							} catch (IOException failure) {
								throw new UncheckedIOException(failure);
							}
						}
					}

					@Override
					public void shutdownGracefully(ShutdownContext context) {
						terminated.signalTerminated();
					}

					@Override
					public void shutdownForcibly(ShutdownContext context) {
						terminated.signalTerminated();
					}
				};
			}
		};
	}

	private static String childOutput(Path output) {
		try {
			return Files.readString(output);
		} catch (IOException failure) {
			return failure.toString();
		}
	}

	private static void awaitUninterruptibly(CountDownLatch latch) {
		boolean interrupted = false;
		for (;;) {
			try {
				latch.await();
				break;
			} catch (InterruptedException interruption) {
				interrupted = true;
			}
		}
		if (interrupted)
			Thread.currentThread().interrupt();
	}

	private static final class BlockingCloseChannel extends AbstractInterruptibleChannel {
		private final CountDownLatch readEntered = new CountDownLatch(1);
		private final CountDownLatch releaseRead = new CountDownLatch(1);
		private final CountDownLatch closeEntered = new CountDownLatch(1);
		private final CountDownLatch releaseClose = new CountDownLatch(1);
		private final AtomicReference<Thread> readingThread = new AtomicReference<>();
		private final AtomicReference<Thread> closingThread = new AtomicReference<>();
		private final Path closeMarker;

		private BlockingCloseChannel(Path closeMarker) {
			this.closeMarker = closeMarker;
		}

		private void read() throws IOException {
			begin();
			this.readingThread.set(Thread.currentThread());
			try {
				this.readEntered.countDown();
				awaitUninterruptibly(this.releaseRead);
			} finally {
				end(false);
			}
		}

		@Override
		protected void implCloseChannel() throws IOException {
			// Honor the channel contract before beginning slow application cleanup.
			this.releaseRead.countDown();
			this.closingThread.set(Thread.currentThread());
			try {
				if (this.closeMarker != null)
					Files.writeString(this.closeMarker, "daemon=" + Thread.currentThread().isDaemon());
			} finally {
				this.closeEntered.countDown();
			}
			awaitUninterruptibly(this.releaseClose);
		}

		private void release() {
			this.releaseRead.countDown();
			this.releaseClose.countDown();
		}

		private void joinWorkers() throws InterruptedException {
			Thread closing = this.closingThread.get();
			if (closing != null) {
				closing.join(5_000);
				Assertions.assertFalse(closing.isAlive(), "Interrupt delivery worker was not retired");
			}
			Thread reading = this.readingThread.get();
			if (reading != null) {
				reading.join(5_000);
				Assertions.assertFalse(reading.isAlive(), "Channel reader was not retired");
			}
		}
	}

	public static final class Resource {
		@GET("/ready")
		public String ready() {
			return "ready";
		}
	}

	public static final class Child {
		public static void main(String[] args) throws Exception {
			BlockingCloseChannel channel = new BlockingCloseChannel(Path.of(args[1]));
			// Keep the VM alive until the actual close callback starts. This hook
			// observes a latch only; the held callback itself must remain daemon-owned.
			Runtime.getRuntime().addShutdownHook(new Thread(() -> {
				try {
					if (!channel.closeEntered.await(5, TimeUnit.SECONDS))
						throw new AssertionError("The interrupt close callback was not entered");
				} catch (InterruptedException interruption) {
					Thread.currentThread().interrupt();
					throw new AssertionError(interruption);
				}
			}, "interrupt-close-observation-hook"));
			if (args[0].equals("startup")) {
				Thread caller = new Thread(() -> {
					try {
						Soklet.run(config(transport(channel), Set.of()));
					} catch (SokletLifecycleException ignored) {
						// Expected when the JVM hook cancels the held startup operation.
					}
				}, "interrupt-close-child-startup-caller");
				caller.setDaemon(true);
				caller.start();
				if (!channel.readEntered.await(5, TimeUnit.SECONDS))
					throw new AssertionError("Startup did not enter channel read");
			} else {
				Soklet.run(config(transport(null), Set.of()),
						ShutdownCleanup.fromTimeoutAndAction(Duration.ofSeconds(1),
								result -> channel.read()));
			}
			System.exit(0);
		}
	}
}
