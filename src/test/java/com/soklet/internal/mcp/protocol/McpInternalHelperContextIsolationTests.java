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

package com.soklet.internal.mcp.protocol;

import com.soklet.CorsAuthorizer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** Internal MCP control workers must not invoke an application's inheritance hooks. */
@Timeout(60)
class McpInternalHelperContextIsolationTests {
	@TempDir Path resources;

	@Test
	void deadlineAndCancellationWorkersIsolateValuesAndKeepTheContextLoader() throws Exception {
		withHostileCaller(false, this::exerciseApplicationWorkers);
	}

	@Test
	void observationDeliveryIsolatesValuesAndKeepsTheContextLoader() throws Exception {
		withHostileCaller(false, this::exerciseObservation);
	}

	@Test
	void metricDrainIsolatesValuesAndKeepsTheContextLoader() throws Exception {
		withHostileCaller(false, this::exerciseMetricDrain);
	}

	@Test
	void subscriptionCloseAndInterruptIsolateValuesAndKeepTheContextLoader() throws Exception {
		withHostileCaller(false, this::exerciseRegistrationClose);
	}

	@Test
	void handlerInterruptDeliveryKeepsTheContextLoaderAndLifecycleScope() throws Exception {
		withHostileCaller(false, this::exerciseHandlerInterrupt);
	}

	@Test
	void blockingInheritanceCannotEnterTheInternalControlFactories() throws Exception {
		withHostileCaller(true, context -> {
			exerciseApplicationWorkers(context);
			exerciseObservation(context);
			exerciseMetricDrain(context);
			exerciseRegistrationClose(context);
			exerciseHandlerInterrupt(context);
		});
	}

	private void exerciseApplicationWorkers(Context context) throws Exception {
		AtomicReference<Throwable> failure = new AtomicReference<>();
		CountDownLatch timerRan = new CountDownLatch(1), cancellationRan = new CountDownLatch(1);
		McpApplicationClock clock = () -> {
			if (Thread.currentThread().getName().equals("soklet-mcp-deadline"))
				context.capture(failure, timerRan);
			return System.nanoTime();
		};
		McpApplicationExecution execution = new McpApplicationExecution(
				new McpApplicationExecutionConfiguration(1, 1,
						Duration.ofSeconds(20), Duration.ofDays(1)), clock);
		try {
			execution.start();
			ThreadPoolExecutor cancellation = field(execution,
					"cancellationCallbackExecutor", ThreadPoolExecutor.class);
			cancellation.execute(() -> context.capture(failure, cancellationRan));
			assertTrue(timerRan.await(5, TimeUnit.SECONDS));
			assertTrue(cancellationRan.await(5, TimeUnit.SECONDS));
			assertNull(failure.get());
		} finally {
			execution.stop();
			assertTrue(execution.awaitTermination(Duration.ofSeconds(5)));
		}
	}

	private void exerciseObservation(Context context) throws Exception {
		McpLegacyHttpObservationDispatcher delivery = new McpLegacyHttpObservationDispatcher(
				1, 1, 0L, () -> {}, "isolated-mcp-observer-");
		AtomicReference<Throwable> failure = new AtomicReference<>();
		AtomicReference<Thread> worker = new AtomicReference<>();
		CountDownLatch callbackRan = new CountDownLatch(1), exited = new CountDownLatch(1);
		try {
			var reservation = delivery.reserve();
			assertNotNull(reservation);
			reservation.finish(() -> {
				worker.set(Thread.currentThread());
				context.capture(failure, callbackRan);
			}, exited::countDown);
			assertTrue(callbackRan.await(5, TimeUnit.SECONDS));
			assertTrue(exited.await(5, TimeUnit.SECONDS));
			assertNull(failure.get());
			assertEquals("isolated-mcp-observer-1", worker.get().getName());
			assertEquals(Thread.NORM_PRIORITY, worker.get().getPriority());
		} finally {
			delivery.stop(false);
			delivery.awaitTermination(Duration.ofSeconds(5));
			assertTrue(delivery.isTerminated());
		}
	}

	private void exerciseMetricDrain(Context context) throws Exception {
		AtomicReference<Throwable> failure = new AtomicReference<>();
		CountDownLatch ran = new CountDownLatch(1);
		McpApplicationExecutionObserver observer = new EmptyObserver() {
			@Override public void drainAsynchronously() { context.capture(failure, ran); }
		};
		var scheduler = new McpHttpServerRuntime.TransportMetricDrainScheduler(observer);
		ThreadPoolExecutor executor = field(scheduler, "executor", ThreadPoolExecutor.class);
		try {
			scheduler.schedule();
			assertTrue(ran.await(5, TimeUnit.SECONDS));
			assertNull(failure.get());
		} finally {
			executor.shutdown();
			assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
		}
	}

	private void exerciseHandlerInterrupt(Context context) throws Exception {
		AtomicReference<Throwable> failure = new AtomicReference<>();
		CountDownLatch entered = new CountDownLatch(1), interrupted = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1), scopeEntered = new CountDownLatch(1);
		CountDownLatch scopeExited = new CountDownLatch(1);
		AtomicInteger scopeExits = new AtomicInteger();
		ExecutorService executor = Executors.newSingleThreadExecutor(action -> {
			Thread worker = new Thread(null, action, "application-handler-fixture", 0L, false);
			worker.setDaemon(true);
			return worker;
		});
		McpApplicationExecutionObserver observer = new EmptyObserver() {
			@Override public LifecycleScope enterLifecycleExecution() {
				context.capture(failure, scopeEntered);
				if (Thread.currentThread().getPriority() != Thread.NORM_PRIORITY)
					failure.compareAndSet(null, new AssertionError("Interrupt helper priority changed"));
				return () -> { scopeExits.incrementAndGet(); scopeExited.countDown(); };
			}
		};
		var dispatcher = new McpApplicationHandlerDispatcher(1, 1, executor, observer);
		var ticket = dispatcher.newTicket(() -> {
			entered.countDown();
			try {
				if (!release.await(5, TimeUnit.SECONDS))
					failure.compareAndSet(null, new AssertionError("Handler release timed out"));
			} catch (InterruptedException expected) {
				interrupted.countDown();
				Thread.currentThread().interrupt();
			}
		}, failure::set);
		try {
			dispatcher.admit(ticket);
			assertTrue(entered.await(5, TimeUnit.SECONDS));
			ticket.requestInterrupt();
			assertTrue(scopeEntered.await(5, TimeUnit.SECONDS));
			assertTrue(interrupted.await(5, TimeUnit.SECONDS));
			assertTrue(scopeExited.await(5, TimeUnit.SECONDS));
		} finally {
			release.countDown();
			dispatcher.stopAcceptingAndReserveCancellation(new IllegalStateException("Test finished")).run();
			executor.shutdown();
			assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
		}
		assertEquals(1, scopeExits.get());
		assertNull(failure.get());
	}

	private void exerciseRegistrationClose(Context context) throws Exception {
		McpNormalizedEndpoint endpoint = McpNormalizedEndpoint.withServerInformation(
				McpImplementationMetadata.withNameAndVersion("helper-context", "1.0.0")).build();
		McpHttpServerRuntime runtime = new McpHttpServerRuntime(
				McpHttpTransportConfiguration.productionDefaults(0),
				McpHttpEndpointPolicy.forDiscovery(CorsAuthorizer.rejectAllInstance(),
						request -> McpRequestAdmissionDecision.ACCEPT), endpoint);
		AtomicReference<Throwable> failure = new AtomicReference<>();
		CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
		AtomicInteger interrupted = new AtomicInteger();
		McpSubscriptionEventSource.Registration registration = () -> {
			context.capture(failure, entered);
			try {
				if (!release.await(5, TimeUnit.SECONDS))
					failure.compareAndSet(null, new AssertionError("Registration close release timed out"));
			} catch (InterruptedException expected) {
				interrupted.incrementAndGet();
				Thread.currentThread().interrupt();
			}
		};
		Thread closeWorker = null;
		try {
			Class<?> generationType = nested("SubscriptionEventSourceGeneration");
			Object generation = construct(generationType, new Class<?>[0]);
			Class<?> fenceType = nested("SubscriptionEventListenerFence");
			Object fence = construct(fenceType, new Class<?>[]{generationType, Consumer.class},
					generation, (Consumer<McpSubscriptionEventSource.Event>) event -> {});
			Object control = construct(nested("SubscriptionSourceRegistrationControl"),
					new Class<?>[]{McpHttpServerRuntime.class, McpSubscriptionEventSource.Registration.class, fenceType},
					runtime, registration, fence);
			Object attempt = call(control, "beginClose", new Class<?>[]{long.class}, 42L);
			assertTrue(entered.await(5, TimeUnit.SECONDS));
			closeWorker = field(attempt, "worker", Thread.class);
			call(attempt, "cancel", new Class<?>[0]);
			assertEquals(Boolean.TRUE, call(attempt, "await", new Class<?>[]{long.class}, TimeUnit.SECONDS.toNanos(5)));
			assertEquals(1, interrupted.get(), "The real interrupt helper must reach the close worker");
			assertNull(failure.get());
		} finally {
			release.countDown();
			if (closeWorker != null) { closeWorker.join(5_000); assertFalse(closeWorker.isAlive()); }
			runtime.close();
		}
	}

	private void withHostileCaller(boolean block, Operation operation) throws Exception {
		Files.writeString(resources.resolve("mcp-helper-context.txt"), "isolated-resource", StandardCharsets.UTF_8);
		CountDownLatch releaseInheritance = new CountDownLatch(1);
		AtomicInteger inherited = new AtomicInteger();
		try (URLClassLoader loader = new URLClassLoader(new java.net.URL[]{resources.toUri().toURL()},
				ClassLoader.getPlatformClassLoader())) {
			FutureTask<Void> result = new FutureTask<>(() -> {
				InheritableThreadLocal<String> local = new InheritableThreadLocal<>() {
					@Override protected String childValue(String value) {
						inherited.incrementAndGet();
						if (!block) throw new IllegalStateException("Application inheritance must not run");
						try { assertTrue(releaseInheritance.await(10, TimeUnit.SECONDS)); }
						catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
						return value;
					}
				};
				Thread caller = Thread.currentThread();
				ClassLoader prior = caller.getContextClassLoader();
				caller.setPriority(Thread.NORM_PRIORITY + 1);
				caller.setContextClassLoader(loader);
				local.set("application-value");
				try { operation.run(new Context(local, loader)); return null; }
				finally { local.remove(); caller.setContextClassLoader(prior); }
			});
			Thread caller = Thread.ofPlatform().daemon(true).inheritInheritableThreadLocals(false)
					.name("mcp-helper-context-caller").start(result);
			try {
				assertDoesNotThrow(() -> result.get(5, TimeUnit.SECONDS),
						"Internal helper construction must avoid application inheritance and blocking");
				assertEquals(0, inherited.get());
			} finally {
				releaseInheritance.countDown();
				caller.join(5_000);
				assertFalse(caller.isAlive());
			}
		}
	}

	private record Context(InheritableThreadLocal<String> local, ClassLoader loader) {
		void capture(AtomicReference<Throwable> failure, CountDownLatch completed) {
			try {
				assertNull(local.get());
				assertSame(loader, Thread.currentThread().getContextClassLoader());
				assertTrue(Thread.currentThread().isDaemon());
				assertFalse(Thread.currentThread().isVirtual());
				try (InputStream resource = Thread.currentThread().getContextClassLoader()
						.getResourceAsStream("mcp-helper-context.txt")) {
					assertNotNull(resource);
					assertEquals("isolated-resource", new String(resource.readAllBytes(), StandardCharsets.UTF_8));
				}
			} catch (Throwable unexpected) { failure.compareAndSet(null, unexpected); }
			finally { completed.countDown(); }
		}
	}

	private static class EmptyObserver implements McpApplicationExecutionObserver {
		@Override public void beginDeferral() {}
		@Override public void recordHandlerExecutionStarted() {}
		@Override public void recordHandlerExecutionFinished() {}
		@Override public void recordHandlerQueued() {}
		@Override public void recordHandlerDequeued() {}
		@Override public void recordHandlerCapacityRejected() {}
		@Override public void drain() {}
		@Override public void endDeferral() {}
	}

	@FunctionalInterface private interface Operation { void run(Context context) throws Exception; }
	private static Class<?> nested(String name) throws ClassNotFoundException {
		return Class.forName(McpHttpServerRuntime.class.getName() + "$" + name);
	}
	private static Object construct(Class<?> type, Class<?>[] types, Object... args) throws Exception {
		Constructor<?> constructor = type.getDeclaredConstructor(types); constructor.setAccessible(true);
		return constructor.newInstance(args);
	}
	private static Object call(Object target, String name, Class<?>[] types, Object... args) throws Exception {
		Method method = target.getClass().getDeclaredMethod(name, types); method.setAccessible(true);
		return method.invoke(target, args);
	}
	private static <T> T field(Object target, String name, Class<T> type) throws Exception {
		Field field = target.getClass().getDeclaredField(name); field.setAccessible(true);
		return type.cast(field.get(target));
	}
}
