/*
 * Copyright 2022-2026 Revetware LLC.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.soklet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(60)
class DefaultResourceMethodResolverCaptureFailureTests {
	@TempDir Path temporaryDirectory;

	@Test
	void contextCaptureRuntimeAndErrorFailuresWakeAllPublicReaders() throws Exception {
		runPublicFixture("runtime-and-error");
	}

	@Test
	void unexpectedCheckedCaptureFailureIsNormalizedAndCachedOnce() throws Exception {
		runPublicFixture("checked");
	}

	@Test
	void healthyCaptureAndCaughtRecursionPublishOneSnapshot() throws Exception {
		runPublicFixture("healthy");
	}

	@Test
	void uncaughtRecursiveContextCapturePublishesOneFailure() throws Exception {
		runPublicFixture("recursive-failure");
	}

	@Test
	void lifecycleWaitersCanCancelOrExpireWhileContextCaptureIsBlocked() throws Exception {
		for (boolean canceled : List.of(false, true)) {
			CountDownLatch captureEntered = new CountDownLatch(1), releaseCapture = new CountDownLatch(1);
			AtomicInteger captures = new AtomicInteger(), loads = new AtomicInteger();
			AtomicReference<Throwable> ownerFailure = new AtomicReference<>(), waiterFailure = new AtomicReference<>();
			CountDownLatch waiterDone = new CountDownLatch(1);
			DefaultResourceMethodResolver complete = DefaultResourceMethodResolver.fromClasses(Set.of());
			DefaultResourceMethodResolver resolver = DefaultResourceMethodResolver.lazyClasspathResolverForTesting(loader -> {
				loads.incrementAndGet(); return complete;
			});
			Thread owner = new Thread(() -> {
				try { resolver.getResourceMethods(); } catch (Throwable failure) { ownerFailure.set(failure); }
			}, "context-capture-lifecycle-owner") {
				@Override public ClassLoader getContextClassLoader() {
					captures.incrementAndGet(); captureEntered.countDown(); awaitUninterruptibly(releaseCapture);
					return super.getContextClassLoader();
				}
			};
			AtomicLong now = new AtomicLong(5L), observedRemaining = new AtomicLong();
			DeadlineWaiter waiter = new DeadlineWaiter(now::get, (monitor, remaining) -> {
				observedRemaining.set(remaining); now.addAndGet(remaining);
			});
			StartupContext context = new StartupContext(now::get, 37L, 90L, () -> canceled);
			Thread lifecycle = new Thread(() -> {
				try { resolver.getResourceMethodsForLifecycle(context, waiter); }
				catch (Throwable failure) { waiterFailure.set(failure); }
				finally { waiterDone.countDown(); }
			}, "context-capture-lifecycle-waiter");
			owner.setDaemon(true); lifecycle.setDaemon(true);
			try {
				owner.start(); assertTrue(captureEntered.await(5, TimeUnit.SECONDS));
				lifecycle.start();
				assertTrue(waiterDone.await(2, TimeUnit.SECONDS), "A blocked getter must not hold the resolver monitor");
				assertInstanceOf(DefaultResourceMethodResolver.StartupWaitCanceledException.class, waiterFailure.get());
				assertEquals(0, loads.get()); assertEquals(1L, releaseCapture.getCount());
				if (!canceled) { assertEquals(32L, observedRemaining.get()); assertEquals(37L, now.get()); }
			} finally {
				releaseCapture.countDown(); owner.join(5_000); lifecycle.join(5_000);
				assertFalse(owner.isAlive()); assertFalse(lifecycle.isAlive());
			}
			assertNull(ownerFailure.get()); assertEquals(1, captures.get()); assertEquals(1, loads.get());
			assertSame(complete.getResourceMethods(), resolver.getResourceMethods());
		}
	}

	private void runPublicFixture(String mode) throws Exception {
		Path log = temporaryDirectory.resolve(mode + ".log");
		Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
				"-cp", System.getProperty("java.class.path"), PublicFixture.class.getName(), mode,
				DefaultResourceMethodResolver.class.getProtectionDomain().getCodeSource().getLocation().toString(),
				ResourceMethodResolver.class.getProtectionDomain().getCodeSource().getLocation().toString())
				.redirectErrorStream(true).redirectOutput(log.toFile()).start();
		try {
			assertTrue(child.waitFor(20, TimeUnit.SECONDS), "Public resolver fixture exceeded its process guard");
			assertEquals(0, child.exitValue(), Files.readString(log));
			assertTrue(Files.readString(log).contains("PASS public resolver " + mode), Files.readString(log));
		} finally {
			if (child.isAlive()) {
				child.destroyForcibly();
				assertTrue(child.waitFor(5, TimeUnit.SECONDS), "Owned resolver fixture did not exit");
			}
		}
	}

	private static void awaitUninterruptibly(CountDownLatch latch) {
		boolean interrupted = false;
		for (;;) {
			try { latch.await(); break; } catch (InterruptedException ignored) { interrupted = true; }
		}
		if (interrupted) Thread.currentThread().interrupt();
	}

	/** Fresh class-loader singleton per case; failed old-code waiters stay only in this disposable JVM. */
	public static final class PublicFixture {
		public static void main(String[] args) throws Exception {
			switch (args[0]) {
				case "runtime-and-error" -> {
					failureCase(args, new SecurityException("capture denied"), false);
					failureCase(args, new AssertionError("capture error"), false);
				}
				case "checked" -> failureCase(args, new Exception("unexpected checked capture"), false);
				case "healthy" -> { healthyCase(args, false); healthyCase(args, true); }
				case "recursive-failure" -> failureCase(args, null, true);
				default -> throw new AssertionError("Unknown fixture mode");
			}
			System.out.println("PASS public resolver " + args[0]);
		}

		private static void failureCase(String[] args, Throwable supplied, boolean recursive) throws Exception {
			try (IsolatedResolver api = new IsolatedResolver(args)) {
				CountDownLatch captureEntered = new CountDownLatch(1), releaseCapture = new CountDownLatch(1);
				CountDownLatch readersEntered = new CountDownLatch(4), readersDone = new CountDownLatch(4);
				AtomicInteger captures = new AtomicInteger();
				AtomicReference<Throwable> ownerFailure = new AtomicReference<>();
				List<AtomicReference<Throwable>> failures = new ArrayList<>();
				AtomicBoolean interruptRestored = new AtomicBoolean();
				Thread owner = new Thread(() -> {
					try { api.lookup(); } catch (Throwable failure) { ownerFailure.set(failure); }
				}, "public-context-capture-owner") {
					@Override public ClassLoader getContextClassLoader() {
						captures.incrementAndGet(); captureEntered.countDown(); awaitUninterruptibly(releaseCapture);
						try { if (recursive) api.lookup(); } catch (Throwable failure) { return throwAny(failure); }
						return throwAny(supplied);
					}
				};
				owner.setDaemon(true);
				List<Thread> readers = new ArrayList<>();
				for (int i = 0; i < 4; ++i) {
					AtomicReference<Throwable> failure = new AtomicReference<>(); failures.add(failure);
					boolean interruptedReader = i == 0;
					Thread reader = new Thread(() -> {
						readersEntered.countDown();
						try { api.lookup(); } catch (Throwable caught) { failure.set(caught); }
						finally { if (interruptedReader) interruptRestored.set(Thread.currentThread().isInterrupted()); readersDone.countDown(); }
					}, "public-context-capture-reader-" + i) {
						@Override public ClassLoader getContextClassLoader() { throw new AssertionError("A reader must not recapture TCCL"); }
					};
					reader.setDaemon(true); readers.add(reader);
				}
				try {
					owner.start(); require(captureEntered.await(5, TimeUnit.SECONDS), "Capture did not enter");
					readers.forEach(Thread::start); require(readersEntered.await(5, TimeUnit.SECONDS), "Readers did not enter");
					awaitResolverReaders(readers); readers.get(0).interrupt(); releaseCapture.countDown();
					owner.join(5_000); require(!owner.isAlive(), "Owner did not finish");
					require(readersDone.await(5, TimeUnit.SECONDS), "Capture failure did not release public readers");
					Throwable expected = ownerFailure.get(); require(expected != null, "Capture failure was lost");
					if (recursive) require(expected instanceof IllegalStateException && expected.getMessage().contains("recursively resolve"), "Recursive owner fence missing");
					else if (supplied instanceof RuntimeException || supplied instanceof Error) require(expected == supplied, "Runtime/Error identity changed");
					else require(expected instanceof AssertionError && expected.getCause() == supplied, "Checked failure was not normalized once");
					for (var failure : failures) require(failure.get() == expected, "Readers did not observe one cached failure");
					require(interruptRestored.get(), "Interrupted public reader did not restore its flag");
					try { api.lookup(); throw new AssertionError("Cached lookup unexpectedly succeeded"); } catch (Throwable failure) { require(failure == expected, "Later lookup retried or changed failure"); }
					try { api.route(); throw new AssertionError("Cached route unexpectedly succeeded"); } catch (Throwable failure) { require(failure == expected, "Public routing did not share cached failure"); }
					require(captures.get() == 1, "Failed capture retried");
				} finally {
					releaseCapture.countDown(); owner.join(1_000);
					for (Thread reader : readers) reader.join(100);
				}
			}
		}

		private static void healthyCase(String[] args, boolean catchRecursion) throws Exception {
			try (IsolatedResolver api = new IsolatedResolver(args)) {
				AtomicInteger captures = new AtomicInteger(); AtomicReference<Object> snapshot = new AtomicReference<>();
				AtomicReference<Throwable> failure = new AtomicReference<>(), recursiveFailure = new AtomicReference<>();
				Thread owner = new Thread(() -> { try { snapshot.set(api.lookup()); } catch (Throwable caught) { failure.set(caught); } }, "healthy-context-capture") {
					@Override public ClassLoader getContextClassLoader() {
						captures.incrementAndGet();
						if (catchRecursion) { try { api.lookup(); } catch (Throwable caught) { recursiveFailure.set(caught); } }
						return api.loader;
					}
				};
				owner.setDaemon(true); owner.start(); owner.join(5_000); require(!owner.isAlive(), "Healthy owner did not finish");
				require(failure.get() == null, "Healthy capture failed: " + failure.get()); require(captures.get() == 1, "Healthy capture repeated");
				if (catchRecursion) require(recursiveFailure.get() instanceof IllegalStateException, "Caught recursion did not reject owner reentry");
				try {
					require(api.lookup() == snapshot.get(), "Healthy snapshot was not cached"); require(api.route() != null, "Healthy public routing failed");
					((Set<?>) snapshot.get()).clear(); throw new AssertionError("Published snapshot was mutable");
				} catch (UnsupportedOperationException expected) { }
				catch (Throwable caught) { throw new AssertionError(caught); }
			}
		}

		private static void awaitResolverReaders(List<Thread> readers) throws Exception {
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
			while (System.nanoTime() - deadline < 0) {
				boolean all = readers.stream().allMatch(reader -> (reader.getState() == Thread.State.WAITING || reader.getState() == Thread.State.BLOCKED)
						&& java.util.Arrays.stream(reader.getStackTrace()).anyMatch(frame -> frame.getClassName().contains("LazyClasspathResolution")));
				if (all) return;
				Thread.sleep(1);
			}
			throw new AssertionError("Concurrent readers did not reach the resolver");
		}

		private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
		@SuppressWarnings("unchecked") private static <T extends Throwable, R> R throwAny(Throwable failure) throws T { throw (T) failure; }

		private static final class IsolatedResolver implements AutoCloseable {
			final URLClassLoader loader;
			final Object resolver;
			final Method lookup, route;
			final Object request, serverType;
			IsolatedResolver(String[] args) throws Exception {
				loader = new URLClassLoader(new URL[]{URI.create(args[1]).toURL(), URI.create(args[2]).toURL()}, ClassLoader.getPlatformClassLoader());
				Class<?> api = loader.loadClass("com.soklet.ResourceMethodResolver");
				resolver = api.getMethod("fromClasspathIntrospection").invoke(null);
				require(resolver == api.getMethod("fromClasspathIntrospection").invoke(null), "Resolver is not the public singleton");
				lookup = api.getMethod("getResourceMethods");
				Class<?> http = loader.loadClass("com.soklet.HttpMethod"), requestType = loader.loadClass("com.soklet.Request"), server = loader.loadClass("com.soklet.ServerType");
				request = requestType.getMethod("fromPath", http, String.class).invoke(null, http.getField("GET").get(null), "/unregistered");
				serverType = server.getField("HTTP").get(null); route = api.getMethod("resourceMethodForRequest", requestType, server);
			}
			Object lookup() throws Throwable { return invoke(lookup); }
			Object route() throws Throwable { return invoke(route, request, serverType); }
			private Object invoke(Method method, Object... arguments) throws Throwable {
				try { return method.invoke(resolver, arguments); } catch (InvocationTargetException failure) { throw failure.getCause(); }
			}
			@Override public void close() throws Exception { loader.close(); }
		}
	}
}
