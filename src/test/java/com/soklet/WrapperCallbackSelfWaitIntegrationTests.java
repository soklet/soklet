package com.soklet;

import com.soklet.annotation.GET;
import com.soklet.annotation.SseEventSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the public delegate attachment path with real built-in callbacks. */
@Timeout(60)
class WrapperCallbackSelfWaitIntegrationTests {

	private static final LifecyclePolicy POLICY = LifecyclePolicy.builder()
			.startupTimeout(Duration.ofSeconds(2))
			.startupCancelationTimeout(Duration.ofMillis(200))
			.gracefulShutdownTimeout(Duration.ofMillis(200))
			.forcedShutdownTimeout(Duration.ofMillis(200)).build();

	@Test void transparentHttpObserverCannotAwaitItsOwner() throws Exception {
		guard(ServerType.HTTP, Shape.TRANSPARENT, false, false);
	}

	@Test void owningHttpMetricsCannotCloseItsOwner() throws Exception {
		guard(ServerType.HTTP, Shape.OWNING, true, true);
	}

	@Test void nestedHttpObserverCannotCloseItsOwner() throws Exception {
		guard(ServerType.HTTP, Shape.NESTED, false, true);
	}

	@Test void transparentSseMetricsCannotAwaitItsOwner() throws Exception {
		guard(ServerType.SSE, Shape.TRANSPARENT, true, false);
	}

	@Test void owningSseObserverCannotCloseItsOwner() throws Exception {
		guard(ServerType.SSE, Shape.OWNING, false, true);
	}

	@Test void nestedSseMetricsCannotCloseItsOwner() throws Exception {
		guard(ServerType.SSE, Shape.NESTED, true, true);
	}

	@Test void wrappedCallbacksMayRequestShutdownAndReturn() throws Exception {
		for (ServerType type : new ServerType[]{ServerType.HTTP, ServerType.SSE}) {
			Attempt attempt = new Attempt();
			try (Fixture fixture = new Fixture(type, Shape.NESTED, false,
					attempt, Soklet::requestShutdown, null, null)) {
				fixture.start();
				try (Socket ignored = fixture.connect()) {
					assertNull(attempt.outcome.get(2, TimeUnit.SECONDS));
				}
				assertTrue(fixture.owner.shutdown().toCompletableFuture()
						.get(4, TimeUnit.SECONDS).isComplete());
			}
		}
	}

	@Test void wrappedCallbackMayAwaitAnUnfinishedUnrelatedOwner() throws Exception {
		Attempt attempt = new Attempt();
		CountDownLatch entered = new CountDownLatch(1);
		try (Fixture other = new Fixture(ServerType.HTTP, Shape.TRANSPARENT,
				false, null, null, null, null)) {
			other.start();
			try (Fixture fixture = new Fixture(ServerType.HTTP, Shape.OWNING,
					false, attempt, owner -> {
						entered.countDown();
						other.owner.awaitCoreShutdown();
					}, null, null)) {
				fixture.start();
				try (Socket ignored = fixture.connect()) {
					assertTrue(entered.await(2, TimeUnit.SECONDS));
					assertFalse(other.owner.awaitShutdownAsync().toCompletableFuture().isDone(),
							"The unrelated owner must still be unfinished at the await");
					assertThrows(java.util.concurrent.TimeoutException.class,
							() -> attempt.outcome.get(50, TimeUnit.MILLISECONDS),
							"An unrelated owner's await must block rather than fail the self-wait guard");
					other.owner.requestShutdown();
					assertNull(attempt.outcome.get(4, TimeUnit.SECONDS));
				}
				fixture.owner.requestShutdown();
				assertTrue(fixture.owner.shutdown().toCompletableFuture()
						.get(4, TimeUnit.SECONDS).isComplete());
			}
		}
	}

	@Test void nestedHttpWriterCannotAwaitItsOwner() throws Exception {
		streamingGuard(false);
	}

	@Test void nestedHttpAsynchronousPublisherCannotCloseItsOwner() throws Exception {
		streamingGuard(true);
	}

	@Test void nestedSseInitializerCannotAwaitItsOwner() throws Exception {
		Attempt attempt = new Attempt();
		AtomicReference<Soklet> owner = new AtomicReference<>();
		SseRoutes resource = new SseRoutes(() -> attempt.invoke(owner.get(), Soklet::awaitCoreShutdown));
		try (Fixture fixture = new Fixture(ServerType.SSE, Shape.NESTED,
				false, null, null, null, resource)) {
			owner.set(fixture.owner);
			fixture.start();
			try (Socket socket = fixture.connect()) {
				request(socket, "/events");
				assertGuard(attempt);
			} finally {
				attempt.releaseBlockedAwait();
			}
		}
	}

	private static void guard(ServerType type, Shape shape, boolean metrics,
			boolean close) throws Exception {
		Attempt attempt = new Attempt();
		try (Fixture fixture = new Fixture(type, shape, metrics, attempt,
				close ? Soklet::close : Soklet::awaitCoreShutdown, null, null)) {
			fixture.start();
			try (Socket ignored = fixture.connect()) {
				assertGuard(attempt);
			}
			fixture.owner.requestShutdown();
			assertTrue(fixture.owner.shutdown().toCompletableFuture()
					.get(4, TimeUnit.SECONDS).isComplete());
		}
	}

	private static void streamingGuard(boolean publisher) throws Exception {
		Attempt attempt = new Attempt();
		AtomicReference<Soklet> owner = new AtomicReference<>();
		AtomicReference<Thread> publisherThread = new AtomicReference<>();
		Runnable callback = () -> attempt.invoke(owner.get(),
				publisher ? Soklet::close : Soklet::awaitCoreShutdown);
		StreamingResponseBody body;
		if (publisher) {
			body = StreamingResponseBody.fromPublisher(subscriber -> {
				Thread worker = Thread.ofVirtual().unstarted(() ->
						subscriber.onSubscribe(new Flow.Subscription() {
							@Override public void request(long count) {
								callback.run();
								subscriber.onComplete();
							}
							@Override public void cancel() { }
						}));
				publisherThread.set(worker);
				worker.start();
			});
		} else {
			body = StreamingResponseBody.fromWriter(stream -> {
				callback.run();
				stream.write(ByteBuffer.wrap("ok".getBytes(StandardCharsets.UTF_8)));
			});
		}
		try (Fixture fixture = new Fixture(ServerType.HTTP, Shape.NESTED,
				false, null, null, new HttpRoutes(body), null)) {
			owner.set(fixture.owner);
			fixture.start();
			try (Socket socket = fixture.connect()) {
				request(socket, "/stream");
				assertGuard(attempt);
			} finally {
				attempt.releaseBlockedAwait();
			}
		} finally {
			Thread worker = publisherThread.get();
			if (worker != null) {
				worker.join(2_000);
				assertFalse(worker.isAlive(), "Asynchronous provider must physically return");
			}
		}
	}

	private static void assertGuard(Attempt attempt) throws Exception {
		Throwable failure = attempt.outcome.get(2, TimeUnit.SECONDS);
		assertInstanceOf(IllegalStateException.class, failure);
		assertEquals("Lifecycle wait cannot run from tracked lifecycle execution", failure.getMessage());
	}

	private static void request(Socket socket, String path) throws Exception {
		socket.getOutputStream().write(("GET " + path + " HTTP/1.1\r\nHost: localhost\r\n"
				+ "Accept: text/event-stream\r\nConnection: close\r\n\r\n")
				.getBytes(StandardCharsets.US_ASCII));
	}

	private enum Shape { TRANSPARENT, OWNING, NESTED }
	@FunctionalInterface private interface Action { void run(Soklet owner) throws Exception; }

	private static final class Attempt {
		final AtomicBoolean entered = new AtomicBoolean();
		final AtomicReference<Thread> thread = new AtomicReference<>();
		final CompletableFuture<Throwable> outcome = new CompletableFuture<>();

		void invoke(Soklet owner, Action action) {
			if (!this.entered.compareAndSet(false, true)) return;
			this.thread.set(Thread.currentThread());
			try {
				action.run(owner);
				this.outcome.complete(null);
			} catch (Throwable failure) {
				this.outcome.complete(failure);
			}
		}

		void releaseBlockedAwait() {
			// A missing guard on the baseline must be interruptible, not hang the suite.
			if (!this.outcome.isDone()) {
				Thread callback = this.thread.get();
				if (callback != null) callback.interrupt();
			}
		}
	}

	private static final class Fixture implements AutoCloseable {
		final DefaultHttpServer http;
		final DefaultSseServer sse;
		final Soklet owner;
		final Attempt attempt;

		Fixture(ServerType type, Shape shape, boolean metrics, Attempt attempt,
				Action action, HttpRoutes httpResource, SseRoutes sseResource) {
			this.attempt = attempt;
			this.http = (DefaultHttpServer) HttpServer.withPort(0).host("127.0.0.1").build();
			this.sse = (DefaultSseServer) SseServer.withPort(0).host("127.0.0.1").build();
			AtomicReference<Soklet> attachedOwner = new AtomicReference<>();
			Runnable callback = () -> {
				if (attempt != null) attempt.invoke(attachedOwner.get(), action);
			};
			SokletConfig.Builder builder = type == ServerType.HTTP
					? SokletConfig.withHttpServer(http(this.http, shape))
					: SokletConfig.withSseServer(sse(this.sse, shape));
			this.owner = Soklet.fromConfig(builder
					.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(
							type == ServerType.HTTP ? HttpRoutes.class : SseRoutes.class)))
					.instanceProvider(new InstanceProvider() {
						@Override public <T> T provide(Class<T> type) {
							if (type == HttpRoutes.class && httpResource != null) return type.cast(httpResource);
							if (type == SseRoutes.class && sseResource != null) return type.cast(sseResource);
							return InstanceProvider.defaultInstance().provide(type);
						}
					}).lifecycleObserver(new LifecycleObserver() {
						@Override public void didReceiveLogEvent(LogEvent event) { }
						@Override public void willAcceptConnection(ServerType type, InetSocketAddress address) {
							if (!metrics) callback.run();
						}
					}).metricsCollector(new MetricsCollector() {
						@Override public void willAcceptConnection(ServerType type, InetSocketAddress address) {
							if (metrics) callback.run();
						}
					}).lifecyclePolicy(POLICY).build());
			attachedOwner.set(this.owner);
		}

		void start() { this.owner.start(); }

		Socket connect() throws Exception {
			int port;
			if (this.http.getEventLoop().isPresent()) {
				port = this.http.getEventLoop().orElseThrow().getPort();
			} else {
				// Only discover the ephemeral listener; all attachment and callbacks are real.
				var field = DefaultSseServer.class.getDeclaredField("serverSocketChannel");
				field.setAccessible(true);
				port = ((InetSocketAddress) ((ServerSocketChannel) field.get(this.sse)).getLocalAddress()).getPort();
			}
			Socket socket = new Socket();
			try {
				socket.connect(new InetSocketAddress("127.0.0.1", port), 1_000);
				return socket;
			} catch (Throwable failure) {
				socket.close();
				throw failure;
			}
		}

		@Override public void close() throws Exception {
			if (this.attempt != null) this.attempt.releaseBlockedAwait();
			assertTrue(this.owner.shutdown().toCompletableFuture()
					.get(4, TimeUnit.SECONDS).isComplete(), "Wrapper cleanup must prove complete termination");
		}
	}

	private static HttpServer http(HttpServer delegate, Shape shape) {
		if (shape == Shape.NESTED) return http(http(delegate, Shape.TRANSPARENT), Shape.OWNING);
		return new HttpServer() {
			@Override public TransportIdentity getTransportIdentity() { return delegate.getTransportIdentity(); }
			@Override public TransportRuntime attach(HttpTransportAttachmentContext context, StartupContext startup) {
				HttpServer.RequestHandler upstream = context.getAdmissionFencedRequestHandler();
				HttpServer.RequestHandler forwarding = (request, consumer) -> upstream.handleRequest(request, consumer);
				if (shape == Shape.TRANSPARENT) return context.attachTransparentDelegate(delegate, forwarding);
				TransportDelegateAttachment child = context.attachTerminationOwningDelegate(delegate, forwarding);
				return owning(child, context.getTransportTerminationSignal());
			}
		};
	}

	private static SseServer sse(SseServer delegate, Shape shape) {
		if (shape == Shape.NESTED) return sse(sse(delegate, Shape.OWNING), Shape.TRANSPARENT);
		return new SseServer() {
			@Override public TransportIdentity getTransportIdentity() { return delegate.getTransportIdentity(); }
			@Override public Optional<? extends SseBroadcaster> acquireBroadcaster(ResourcePath path) {
				return delegate.acquireBroadcaster(path);
			}
			@Override public TransportRuntime attach(SseTransportAttachmentContext context, StartupContext startup) {
				SseServer.RequestHandler upstream = context.getAdmissionFencedRequestHandler();
				SseServer.RequestHandler forwarding = (request, consumer) -> upstream.handleRequest(request, consumer);
				if (shape == Shape.TRANSPARENT) return context.attachTransparentDelegate(delegate, forwarding);
				TransportDelegateAttachment child = context.attachTerminationOwningDelegate(delegate, forwarding);
				return owning(child, context.getTransportTerminationSignal());
			}
		};
	}

	private static TransportRuntime owning(TransportDelegateAttachment attachment,
			TransportTerminationSignal signal) {
		attachment.whenTerminated().whenComplete((ignored, failure) -> {
			if (failure == null) signal.signalTerminated();
			else signal.signalTerminationFailure(failure);
		});
		TransportRuntime child = attachment.getTransportRuntime();
		return new TransportRuntime() {
			@Override public void start(StartupContext context) { child.start(context); }
			@Override public void shutdownGracefully(ShutdownContext context) { child.shutdownGracefully(context); }
			@Override public void shutdownForcibly(ShutdownContext context) { child.shutdownForcibly(context); }
		};
	}

	public static final class HttpRoutes {
		private final StreamingResponseBody body;
		public HttpRoutes() { this(null); }
		HttpRoutes(StreamingResponseBody body) { this.body = body; }
		@GET("/ok") public String ok() { return "ok"; }
		@GET("/stream") public MarshaledResponse stream() {
			return MarshaledResponse.withStatusCode(200).streamingResponseBody(this.body).build();
		}
	}

	public static final class SseRoutes {
		private final Runnable initializer;
		public SseRoutes() { this(() -> { }); }
		SseRoutes(Runnable initializer) { this.initializer = initializer; }
		@SseEventSource("/events") public SseHandshakeResult events() {
			return SseHandshakeResult.Accepted.builder().clientInitializer(connection -> this.initializer.run()).build();
		}
	}
}
