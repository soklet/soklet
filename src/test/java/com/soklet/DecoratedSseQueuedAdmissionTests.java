package com.soklet;

import com.soklet.annotation.SseEventSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Timeout(60)
class DecoratedSseQueuedAdmissionTests {
	@Test
	void decoratedParsedHandshakeRetainsAdmissionAcrossQuiesceAndCustomCallsRemainSealed() throws Exception {
		assumeTrue(Runtime.version().feature() >= 21, "SSE requires virtual threads");
		int port;
		try (ServerSocket reserved = new ServerSocket(0)) { port = reserved.getLocalPort(); }
		CountDownLatch occupied = new CountDownLatch(1), release = new CountDownLatch(1);
		CountDownLatch queued = new CountDownLatch(1), quiesced = new CountDownLatch(1);
		ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
				new ArrayBlockingQueue<>(2), runnable -> {
			Thread worker = new Thread(runnable, "queued-sse-decorator-test-handler"); worker.setDaemon(true); return worker;
		}) {
			@Override public void execute(Runnable runnable) { super.execute(runnable); if (!getQueue().isEmpty()) queued.countDown(); }
			@Override public void shutdown() { super.shutdown(); quiesced.countDown(); }
		};
		executor.execute(() -> {
			occupied.countDown();
			try { assertTrue(release.await(8, TimeUnit.SECONDS)); }
			catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
		});
		assertTrue(occupied.await(2, TimeUnit.SECONDS));
		Resource resource = new Resource();
		SseServer delegate = SseServer.withPort(port).host("127.0.0.1").requestHandlerConcurrency(1)
				.requestHandlerQueueCapacity(2).requestHeaderTimeout(Duration.ofSeconds(5))
				.requestHandlerTimeout(Duration.ofSeconds(8)).requestHandlerExecutorServiceSupplier(() -> executor).build();
		AtomicReference<SseServer.RequestHandler> upstream = new AtomicReference<>();
		SseServer decorator = new SseServer() {
			@Override public TransportIdentity getTransportIdentity() { return delegate.getTransportIdentity(); }
			@Override public Optional<? extends SseBroadcaster> acquireBroadcaster(ResourcePath path) { return delegate.acquireBroadcaster(path); }
			@Override public TransportRuntime attach(SseTransportAttachmentContext context, StartupContext startup) {
				upstream.set(context.getAdmissionFencedRequestHandler());
				return context.attachTransparentDelegate(delegate, (request, consumer) -> upstream.get().handleRequest(request.copy().finish(), consumer));
			}
		};
		SokletConfig config = SokletConfig.withSseServer(decorator)
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(Resource.class)))
				.instanceProvider(new InstanceProvider() { @Override public <T> T provide(Class<T> type) { return type.cast(resource); } })
				.lifecycleObserver(new LifecycleObserver() { @Override public void didReceiveLogEvent(LogEvent event) {} })
				.lifecyclePolicy(LifecyclePolicy.builder().startupTimeout(Duration.ofSeconds(2)).startupCancelationTimeout(Duration.ofSeconds(1))
						.gracefulShutdownTimeout(Duration.ofSeconds(4)).forcedShutdownTimeout(Duration.ofMillis(200)).build()).build();
		try (Soklet soklet = Soklet.fromConfig(config)) {
			soklet.start();
			try (Socket socket = new Socket("127.0.0.1", port)) {
				socket.setSoTimeout(5000);
				socket.getOutputStream().write("GET /events HTTP/1.1\r\nHost: 127.0.0.1\r\nAccept: text/event-stream\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
				socket.getOutputStream().flush();
				assertTrue(queued.await(2, TimeUnit.SECONDS), "A fully parsed handshake must reach the application queue");
				var shutdown = soklet.shutdown().toCompletableFuture();
				assertTrue(quiesced.await(2, TimeUnit.SECONDS), "Quiesce must seal application executor admission");
				assertFalse(shutdown.isDone());
				AtomicReference<HttpRequestResult> rejected = new AtomicReference<>();
				upstream.get().handleRequest(Request.fromPath(HttpMethod.GET, "/events"), rejected::set);
				assertEquals(503, rejected.get().getMarshaledResponse().getStatusCode()); assertEquals(0, resource.calls.get());
				release.countDown();
				String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
				assertTrue(response.startsWith("HTTP/1.1 200"), response); assertTrue(response.endsWith("ok"), response);
				assertEquals(1, resource.calls.get());
				ShutdownResult result = shutdown.get(3, TimeUnit.SECONDS);
				assertTrue(result.isComplete()); assertEquals(ShutdownDisposition.GRACEFUL, result.getShutdownDisposition());
				assertTrue(result.getShutdownComponentResult(ShutdownComponentType.SSE).orElseThrow().getResidualActivityEvidence().isEmpty());
			} finally { release.countDown(); }
		} finally { release.countDown(); executor.shutdownNow(); assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS)); }
	}
	public static final class Resource {
		private final AtomicInteger calls = new AtomicInteger();
		@SseEventSource("/events") public SseHandshakeResult source() {
			this.calls.incrementAndGet(); return SseHandshakeResult.rejectWithResponse(Response.withStatusCode(200).body("ok").build());
		}
	}
}
