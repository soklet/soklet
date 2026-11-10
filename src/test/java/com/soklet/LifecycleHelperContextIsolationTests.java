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

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

@Timeout(60)
class LifecycleHelperContextIsolationTests {
	private static final InheritableThreadLocal<String> APPLICATION_CONTEXT = new InheritableThreadLocal<>();
	private static final String REQUEST_CONTEXT_MARKER = "soklet-request-context-marker.txt";

	@Test
	void startupContextIsIsolatedWhileExplicitRequestContextReachesLiveHandlers(@TempDir Path applicationDirectory)
			throws Exception {
		Files.writeString(applicationDirectory.resolve(REQUEST_CONTEXT_MARKER), "application marker", StandardCharsets.UTF_8);
		ClassLoader previousLoader = Thread.currentThread().getContextClassLoader();
		Assertions.assertNull(previousLoader.getResource(REQUEST_CONTEXT_MARKER));
		try (URLClassLoader applicationLoader = new URLClassLoader(
				new URL[]{applicationDirectory.toUri().toURL()}, previousLoader)) {
			HttpServer server = HttpServer.withPort(0).host("127.0.0.1").build();
			ConcurrentLinkedQueue<RequestScopeObservation> scopes = new ConcurrentLinkedQueue<>();
			CountDownLatch scopesFinished = new CountDownLatch(4);
			SokletConfig config = SokletConfig.withHttpServer(server)
					.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(ContextResource.class)))
					.lifecycleObservers(List.of()).jvmShutdownHookEnabled(false)
					.lifecyclePolicy(LifecyclePolicy.builder().startupTimeout(Duration.ofSeconds(5))
							.startupCancelationTimeout(Duration.ofSeconds(1)).gracefulShutdownTimeout(Duration.ofSeconds(1))
							.forcedShutdownTimeout(Duration.ofSeconds(1)).build())
					.requestInterceptor(new RequestInterceptor() {
						@Override
						public void wrapRequest(ServerType type, Request request, Consumer<Request> processor) {
							String previous = APPLICATION_CONTEXT.get();
							request.getHeader("X-Request-Context").ifPresent(APPLICATION_CONTEXT::set);
							try {
								processor.accept(request);
							} finally {
								if (previous == null)
									APPLICATION_CONTEXT.remove();
								else
									APPLICATION_CONTEXT.set(previous);
								// Observe cleanup on this same handler thread, rather than relying on
								// a subsequent request being dispatched to a fresh virtual thread.
								scopes.add(new RequestScopeObservation(previous, APPLICATION_CONTEXT.get(),
										Thread.currentThread().getContextClassLoader()));
								scopesFinished.countDown();
							}
						}
					}).build();
			AtomicReference<ShutdownResult> result = new AtomicReference<>();
			AtomicReference<Throwable> failure = new AtomicReference<>();
			CountDownLatch finished = new CountDownLatch(1);
			Thread applicationCaller = Thread.ofPlatform().daemon(true).inheritInheritableThreadLocals(false)
					.name("application-request-context-caller").unstarted(() -> {
				APPLICATION_CONTEXT.set("set-on-startup");
				try {
					result.set(Soklet.run(config, () -> {
						int port = ((DefaultHttpServer) server).getEventLoop().orElseThrow().getPort();
						Assertions.assertEquals("null|application marker", requestContext(port, null));
						Assertions.assertEquals("request-one|application marker", requestContext(port, "request-one"));
						Assertions.assertEquals("request-two|application marker", requestContext(port, "request-two"));
						Assertions.assertEquals("null|application marker", requestContext(port, null));
						Assertions.assertTrue(scopesFinished.await(5, TimeUnit.SECONDS), "Request scopes did not retire");
						Assertions.assertEquals(4, scopes.size());
						for (RequestScopeObservation scope : scopes) {
							Assertions.assertNull(scope.before(), "Startup context reached a default request handler");
							Assertions.assertNull(scope.after(), "Explicit request context survived its finally block");
							Assertions.assertSame(applicationLoader, scope.contextClassLoader());
						}
						Assertions.assertEquals("set-on-startup", APPLICATION_CONTEXT.get(),
								"Request context binding changed the application caller's context");
						Assertions.assertSame(applicationLoader, Thread.currentThread().getContextClassLoader());
					}));
				} catch (Throwable throwable) {
					failure.set(throwable);
				} finally {
					APPLICATION_CONTEXT.remove();
					finished.countDown();
				}
			});
			applicationCaller.setContextClassLoader(applicationLoader);
			applicationCaller.start();
			try {
				Assertions.assertTrue(finished.await(20, TimeUnit.SECONDS), "Live context requests or shutdown did not finish");
				Assertions.assertNull(failure.get(), () -> "Public lifecycle/request failed: " + failure.get());
				Assertions.assertNotNull(result.get());
				Assertions.assertEquals(StartupDisposition.READY, result.get().getStartupDisposition());
				Assertions.assertEquals(ShutdownDisposition.GRACEFUL, result.get().getShutdownDisposition());
			} finally {
				applicationCaller.join(10_000);
				Assertions.assertFalse(applicationCaller.isAlive(), "Application context caller did not retire");
			}
		}
	}

	private static String requestContext(int port, String context) throws Exception {
		try (Socket socket = new Socket()) {
			socket.connect(new InetSocketAddress("127.0.0.1", port), 2_000);
			socket.setSoTimeout(2_000);
			String request = "GET /context HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n"
					+ (context == null ? "" : "X-Request-Context: " + context + "\r\n") + "\r\n";
			socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
			String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			Assertions.assertTrue(response.startsWith("HTTP/1.1 200 "), response);
			int headerEnd = response.indexOf("\r\n\r\n");
			Assertions.assertTrue(headerEnd >= 0, response);
			return response.substring(headerEnd + 4);
		}
	}

	private record RequestScopeObservation(String before, String after, ClassLoader contextClassLoader) {}

	public static final class ContextResource {
		@GET("/context")
		public String context() throws Exception {
			try (InputStream marker = Thread.currentThread().getContextClassLoader()
					.getResourceAsStream(REQUEST_CONTEXT_MARKER)) {
				return String.valueOf(APPLICATION_CONTEXT.get()) + "|"
						+ (marker == null ? "missing marker" : new String(marker.readAllBytes(), StandardCharsets.UTF_8));
			}
		}
	}

	@Test
	void throwingApplicationInheritanceCannotPreventPublicStartup() throws Exception {
		AtomicInteger inheritanceCalls = new AtomicInteger();
		InheritableThreadLocal<String> context = new InheritableThreadLocal<>() {
			@Override
			protected String childValue(String value) {
				inheritanceCalls.incrementAndGet();
				throw new IllegalStateException("Application inheritance must not run for lifecycle helpers");
			}
		};
		Assertions.assertEquals(ShutdownDisposition.GRACEFUL,
				runWithApplicationContext(context, new CountDownLatch(0)).getShutdownDisposition());
		Assertions.assertEquals(0, inheritanceCalls.get());
	}

	@Test
	void blockingApplicationInheritanceCannotStrandPublicStartup() throws Exception {
		AtomicInteger inheritanceCalls = new AtomicInteger();
		CountDownLatch release = new CountDownLatch(1);
		InheritableThreadLocal<String> context = new InheritableThreadLocal<>() {
			@Override
			protected String childValue(String value) {
				inheritanceCalls.incrementAndGet();
				try {
					if (!release.await(20, TimeUnit.SECONDS))
						throw new AssertionError("Application inheritance safety gate expired");
				} catch (InterruptedException failure) {
					Thread.currentThread().interrupt();
					throw new AssertionError(failure);
				}
				return value;
			}
		};
		Assertions.assertEquals(ShutdownDisposition.GRACEFUL,
				runWithApplicationContext(context, release).getShutdownDisposition());
		Assertions.assertEquals(0, inheritanceCalls.get());
	}

	@Test
	void startupDiscoveryRetainsApplicationContextClassLoader(@TempDir Path applicationDirectory) throws Exception {
		String markerName = "soklet-application-only-context-marker.txt";
		Files.writeString(applicationDirectory.resolve(markerName), "application marker", StandardCharsets.UTF_8);
		ClassLoader previous = Thread.currentThread().getContextClassLoader();
		Assertions.assertNull(previous.getResource(markerName));
		AtomicBoolean discovered = new AtomicBoolean();
		try (URLClassLoader applicationLoader = new URLClassLoader(
				new URL[]{applicationDirectory.toUri().toURL()}, previous)) {
			DefaultResourceMethodResolver resolver = DefaultResourceMethodResolver.lazyClasspathResolverForTesting(loader -> {
				Assertions.assertSame(applicationLoader, loader);
				Assertions.assertSame(applicationLoader, Thread.currentThread().getContextClassLoader());
				try (InputStream marker = loader.getResourceAsStream(markerName)) {
					Assertions.assertNotNull(marker, "Startup discovery lost application-only resources");
					Assertions.assertEquals("application marker", new String(marker.readAllBytes(), StandardCharsets.UTF_8));
				} catch (Exception failure) {
					throw new AssertionError(failure);
				}
				discovered.set(true);
				return DefaultResourceMethodResolver.fromClasses(Set.of(Resource.class));
			});
			SokletConfig config = configuration(resolver);
			Thread.currentThread().setContextClassLoader(applicationLoader);
			try {
				ShutdownResult result = Soklet.run(config, () -> {
					Assertions.assertTrue(discovered.get());
					Assertions.assertSame(applicationLoader, Thread.currentThread().getContextClassLoader());
				});
				Assertions.assertEquals(StartupDisposition.READY, result.getStartupDisposition());
				Assertions.assertEquals(ShutdownDisposition.GRACEFUL, result.getShutdownDisposition());
			} finally {
				Thread.currentThread().setContextClassLoader(previous);
			}
		}
	}

	private static ShutdownResult runWithApplicationContext(InheritableThreadLocal<String> context,
			CountDownLatch release) throws Exception {
		SokletConfig config = configuration(ResourceMethodResolver.fromClasses(Set.of(Resource.class)));
		ClassLoader applicationLoader = Thread.currentThread().getContextClassLoader();
		AtomicReference<ShutdownResult> result = new AtomicReference<>();
		AtomicReference<Throwable> failure = new AtomicReference<>();
		AtomicInteger actions = new AtomicInteger();
		CountDownLatch finished = new CountDownLatch(1);
		Thread applicationCaller = Thread.ofPlatform().daemon(true)
				.inheritInheritableThreadLocals(false).name("application-start-caller").unstarted(() -> {
			context.set("application context");
			try {
				result.set(Soklet.run(config, () -> {
					Assertions.assertEquals("application context", context.get());
					Assertions.assertSame(applicationLoader, Thread.currentThread().getContextClassLoader());
					actions.incrementAndGet();
				}));
			} catch (Throwable throwable) {
				failure.set(throwable);
			} finally {
				context.remove();
				finished.countDown();
			}
		});
		applicationCaller.setContextClassLoader(applicationLoader);
		applicationCaller.start();
		try {
			Assertions.assertTrue(finished.await(10, TimeUnit.SECONDS),
					"Public startup/finalization was stranded by application inheritance");
			Assertions.assertNull(failure.get(), () -> "Public lifecycle failed: " + failure.get());
			Assertions.assertEquals(1, actions.get());
			Assertions.assertNotNull(result.get());
			Assertions.assertEquals(StartupDisposition.READY, result.get().getStartupDisposition());
			return result.get();
		} finally {
			release.countDown();
			applicationCaller.join(10_000);
			Assertions.assertFalse(applicationCaller.isAlive(), "Application caller did not retire after safety release");
		}
	}

	private static SokletConfig configuration(ResourceMethodResolver resolver) {
		return SokletConfig.withHttpServer(HttpServer.fromPort(0))
				.resourceMethodResolver(resolver)
				.lifecycleObservers(List.of())
				.jvmShutdownHookEnabled(false)
				.lifecyclePolicy(LifecyclePolicy.builder()
						.startupTimeout(Duration.ofSeconds(5))
						.startupCancelationTimeout(Duration.ofSeconds(1))
						.gracefulShutdownTimeout(Duration.ofSeconds(1))
						.forcedShutdownTimeout(Duration.ofSeconds(1)).build())
				.build();
	}

	public static final class Resource {
		@GET("/")
		public String hello() { return "hello"; }
	}
}
