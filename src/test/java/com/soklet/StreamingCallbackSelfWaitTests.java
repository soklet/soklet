package com.soklet;

import com.soklet.annotation.GET;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(60)
class StreamingCallbackSelfWaitTests {
	@Test void writerAwaitFailsFast() throws Exception { exercise("writer", false); }
	@Test void writerCloseFailsFast() throws Exception { exercise("writer", true); }
	@Test void ownedSourceCleanupAwaitFailsFast() throws Exception { exercise("cleanup", false); }
	@Test void ownedSourceCleanupCloseFailsFast() throws Exception { exercise("cleanup", true); }
	@Test void cancelationCallbackAwaitFailsFast() throws Exception { exercise("cancelation", false); }
	@Test void terminationObserverAwaitFailsFast() throws Exception { exercise("termination", false); }
	@Test void asynchronousPublisherRequestAwaitFailsFast() throws Exception { exercise("publisher-request", false); }
	@Test void asynchronousPublisherCancelAwaitFailsFast() throws Exception { exercise("publisher-cancel", false); }
	@Test void sseInitializerAwaitFailsFast() throws Exception { sseInitializer(false); }
	@Test void sseInitializerCloseFailsFast() throws Exception { sseInitializer(true); }

	private static void sseInitializer(boolean close) throws Exception {
		AtomicReference<Soklet> owner = new AtomicReference<>();
		AtomicReference<Thread> callbackThread = new AtomicReference<>();
		CompletableFuture<Throwable> outcome = new CompletableFuture<>();
		SseRoutes resource = new SseRoutes(() -> {
			callbackThread.set(Thread.currentThread());
			try {
				if (close) owner.get().close(); else owner.get().awaitCoreShutdown();
				outcome.complete(null);
			} catch (Throwable failure) { outcome.complete(failure); }
		});
		DefaultSseServer server = (DefaultSseServer) SseServer.withPort(0).host("127.0.0.1").build();
		Soklet soklet = Soklet.fromConfig(SokletConfig.withSseServer(server)
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(SseRoutes.class)))
				.instanceProvider(new InstanceProvider() {
					@Override public <T> T provide(Class<T> type) {
						return type == SseRoutes.class ? type.cast(resource) : InstanceProvider.defaultInstance().provide(type);
					}
				}).lifecyclePolicy(LifecyclePolicy.builder()
						.startupTimeout(Duration.ofSeconds(2)).startupCancelationTimeout(Duration.ofMillis(200))
						.gracefulShutdownTimeout(Duration.ofMillis(200)).forcedShutdownTimeout(Duration.ofMillis(200)).build()).build());
		owner.set(soklet);
		try {
			soklet.start();
			var field = DefaultSseServer.class.getDeclaredField("serverSocketChannel");
			field.setAccessible(true);
			int port = ((java.net.InetSocketAddress) ((java.nio.channels.ServerSocketChannel) field.get(server))
					.getLocalAddress()).getPort();
			try (Socket socket = new Socket()) {
				socket.connect(new java.net.InetSocketAddress("127.0.0.1", port), 1_000);
				socket.getOutputStream().write("GET /events HTTP/1.1\r\nHost: localhost\r\nAccept: text/event-stream\r\n\r\n"
						.getBytes(StandardCharsets.US_ASCII));
				Throwable failure = outcome.get(2, TimeUnit.SECONDS);
				assertInstanceOf(IllegalStateException.class, failure);
				assertEquals("Lifecycle wait cannot run from tracked lifecycle execution", failure.getMessage());
			}
		} finally {
			if (!outcome.isDone()) {
				Thread blocked = callbackThread.get();
				if (blocked != null) blocked.interrupt();
			}
			soklet.shutdown().toCompletableFuture().get(4, TimeUnit.SECONDS);
		}
	}

	private static void exercise(String phase, boolean close) throws Exception {
		AtomicReference<Soklet> owner = new AtomicReference<>();
		AtomicReference<Thread> callbackThread = new AtomicReference<>();
		AtomicReference<Thread> publisherThread = new AtomicReference<>();
		CompletableFuture<Throwable> outcome = new CompletableFuture<>();
		CompletableFuture<Void> ready = new CompletableFuture<>();
		CompletableFuture<Void> release = new CompletableFuture<>();
		Runnable callback = () -> {
			callbackThread.set(Thread.currentThread());
			try {
				if (close) owner.get().close(); else owner.get().awaitCoreShutdown();
				outcome.complete(null);
			} catch (Throwable failure) { outcome.complete(failure); }
		};
		StreamingResponseBody body;
		if (phase.startsWith("publisher")) {
			body = StreamingResponseBody.fromPublisher(subscriber -> {
				Thread worker = Thread.ofVirtual().unstarted(() -> subscriber.onSubscribe(new Flow.Subscription() {
					@Override public void request(long count) {
						if (phase.equals("publisher-request")) {
							callback.run();
							subscriber.onComplete();
						} else ready.complete(null);
					}
					@Override public void cancel() { if (phase.equals("publisher-cancel")) callback.run(); }
				}));
				publisherThread.set(worker);
				worker.start();
			});
		} else body = StreamingResponseBody.fromWriter(stream -> {
			if (phase.equals("writer")) callback.run();
			if (phase.equals("cleanup")) stream.own((AutoCloseable) callback::run);
			if (phase.equals("cancelation")) {
				stream.getCancelationToken().onCancel(callback);
				ready.complete(null);
				release.get(4, TimeUnit.SECONDS);
			} else stream.write(ByteBuffer.wrap("ok".getBytes(StandardCharsets.UTF_8)));
		});
		Routes resource = new Routes(body);
		DefaultHttpServer server = (DefaultHttpServer) HttpServer.withPort(0).host("127.0.0.1").build();
		Soklet soklet = Soklet.fromConfig(SokletConfig.withHttpServer(server)
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(Routes.class)))
				.instanceProvider(new InstanceProvider() {
					@Override public <T> T provide(Class<T> type) {
						return type == Routes.class ? type.cast(resource) : InstanceProvider.defaultInstance().provide(type);
					}
				}).lifecycleObserver(new LifecycleObserver() {
					@Override public void didReceiveLogEvent(LogEvent event) { }
					@Override public void willTerminateResponseStream(StreamingResponseHandle handle, StreamTermination termination) {
						if (phase.equals("termination")) callback.run();
					}
				}).lifecyclePolicy(LifecyclePolicy.builder()
						.startupTimeout(Duration.ofSeconds(2)).startupCancelationTimeout(Duration.ofMillis(200))
						.gracefulShutdownTimeout(Duration.ofMillis(200)).forcedShutdownTimeout(Duration.ofMillis(200)).build()).build());
		owner.set(soklet);
		try {
			soklet.start();
			try (Socket socket = new Socket()) {
				socket.connect(new java.net.InetSocketAddress("127.0.0.1", server.getEventLoop().orElseThrow().getPort()), 1_000);
				socket.getOutputStream().write("GET /stream HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
						.getBytes(StandardCharsets.US_ASCII));
				if (phase.equals("cancelation") || phase.equals("publisher-cancel")) {
					ready.get(2, TimeUnit.SECONDS);
					soklet.shutdown();
				}
				Throwable failure = outcome.get(2, TimeUnit.SECONDS);
				assertInstanceOf(IllegalStateException.class, failure);
				assertEquals("Lifecycle wait cannot run from tracked lifecycle execution", failure.getMessage());
			}
		} finally {
			release.complete(null);
			if (!outcome.isDone()) {
				Thread blocked = callbackThread.get();
				if (blocked != null) blocked.interrupt();
			}
			soklet.shutdown().toCompletableFuture().get(4, TimeUnit.SECONDS);
			Thread publisher = publisherThread.get();
			if (publisher != null) {
				publisher.join(2_000);
				assertFalse(publisher.isAlive(), "Provider callback must physically return");
			}
		}
	}

	public static final class Routes {
		private final StreamingResponseBody body;
		Routes(StreamingResponseBody body) { this.body = body; }
		@GET("/stream") public MarshaledResponse stream() {
			return MarshaledResponse.withStatusCode(200).streamingResponseBody(this.body).build();
		}
	}

	public static final class SseRoutes {
		private final Runnable initializer;
		SseRoutes(Runnable initializer) { this.initializer = initializer; }
		@com.soklet.annotation.SseEventSource("/events") public SseHandshakeResult events() {
			return SseHandshakeResult.Accepted.builder().clientInitializer(connection -> this.initializer.run()).build();
		}
	}
}
