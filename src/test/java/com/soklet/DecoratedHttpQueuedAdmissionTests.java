package com.soklet;

import com.soklet.annotation.GET;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(60)
class DecoratedHttpQueuedAdmissionTests {
	@Test
	void transparentDecoratorCanRedispatchACopyWhileAdmissionIsOpen() throws Exception {
		int port;
		try (ServerSocket reserved = new ServerSocket(0)) { port = reserved.getLocalPort(); }
		Resource resource = new Resource();
		Decorator decorator = new Decorator(HttpServer.withPort(port).host("127.0.0.1").build(), false);
		SokletConfig config = SokletConfig.withHttpServer(decorator)
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(Resource.class)))
				.instanceProvider(new InstanceProvider() { @Override public <T> T provide(Class<T> type) { return type.cast(resource); } })
				.lifecycleObserver(new LifecycleObserver() { @Override public void didReceiveLogEvent(LogEvent event) {} }).build();
		try (Soklet soklet = Soklet.fromConfig(config)) {
			soklet.start();
			try (Socket socket = open(port, "/fallback")) {
				String response = read(socket);
				assertTrue(response.startsWith("HTTP/1.1 200"), response);
				assertTrue(response.endsWith("queued"), response);
				assertEquals(1, resource.queuedCalls.get());
			}
			assertEquals(ShutdownDisposition.GRACEFUL, soklet.shutdown().toCompletableFuture().get(3, TimeUnit.SECONDS).getShutdownDisposition());
		}
	}
	@Test
	void transparentDecoratorPreservesQueuedAdmissionAcrossQuiesce() throws Exception { assertQueuedAdmission(false); }

	@Test
	void terminationOwningDecoratorPreservesQueuedAdmissionAcrossQuiesce() throws Exception { assertQueuedAdmission(true); }

	private static void assertQueuedAdmission(boolean owning) throws Exception {
		int port;
		try (ServerSocket reserved = new ServerSocket(0)) { port = reserved.getLocalPort(); }
		CountDownLatch queued = new CountDownLatch(1);
		CountDownLatch quiesced = new CountDownLatch(1);
		ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
				new ArrayBlockingQueue<>(2), runnable -> {
			Thread worker = new Thread(runnable, "queued-decorator-test-handler"); worker.setDaemon(true); return worker;
		}) {
			@Override public void execute(Runnable runnable) {
				super.execute(runnable); if (!getQueue().isEmpty()) queued.countDown();
			}
			@Override public void shutdown() { super.shutdown(); quiesced.countDown(); }
		};
		Resource resource = new Resource();
		HttpServer delegate = HttpServer.withPort(port).host("127.0.0.1").concurrency(1)
				.requestHandlerTimeout(Duration.ofSeconds(8)).requestHandlerExecutorServiceSupplier(() -> executor).build();
		Decorator decorator = new Decorator(delegate, owning);
		SokletConfig config = SokletConfig.withHttpServer(decorator)
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(Resource.class)))
				.instanceProvider(new InstanceProvider() {
					@Override public <T> T provide(Class<T> type) { return type.cast(resource); }
				})
				.lifecycleObserver(new LifecycleObserver() { @Override public void didReceiveLogEvent(LogEvent event) {} })
				.lifecyclePolicy(LifecyclePolicy.builder().startupTimeout(Duration.ofSeconds(2))
						.startupCancelationTimeout(Duration.ofSeconds(1)).gracefulShutdownTimeout(Duration.ofSeconds(5))
						.forcedShutdownTimeout(Duration.ofMillis(200)).build()).build();
		try (Soklet soklet = Soklet.fromConfig(config)) {
			soklet.start();
			try (Socket first = open(port, "/block")) {
				assertTrue(resource.entered.await(2, TimeUnit.SECONDS));
				try (Socket second = open(port, "/queued")) {
					assertTrue(queued.await(2, TimeUnit.SECONDS), "Second request must be admitted to the real worker queue");
					CompletableFuture<ShutdownResult> shutdown = soklet.shutdown().toCompletableFuture();
					assertTrue(quiesced.await(2, TimeUnit.SECONDS), "Built-in executor must quiesce before the queue is released");
					assertFalse(shutdown.isDone(), "Queued and executing admissions still require completion");
					AtomicReference<HttpRequestResult> rejected = new AtomicReference<>();
					decorator.upstream.handleRequest(Request.fromPath(HttpMethod.GET, "/queued"), rejected::set);
					assertEquals(503, rejected.get().getMarshaledResponse().getStatusCode(), "Generic custom dispatch remains sealed");
					assertEquals(0, resource.queuedCalls.get());
					resource.release.countDown();
					assertTrue(read(first).startsWith("HTTP/1.1 200"));
					String response = read(second); assertTrue(response.startsWith("HTTP/1.1 200"), response);
					assertTrue(response.endsWith("queued"), response);
					assertEquals(1, resource.queuedCalls.get());
					ShutdownResult result = shutdown.get(3, TimeUnit.SECONDS);
					assertTrue(result.isComplete()); assertEquals(ShutdownDisposition.GRACEFUL, result.getShutdownDisposition());
					assertTrue(result.getShutdownComponentResult(ShutdownComponentType.HTTP).orElseThrow().getResidualActivityEvidence().isEmpty());
					// A retained/copy alias cannot replay a consumed dispatch permit.
					decorator.upstream.handleRequest(decorator.lastQueued.get().copy().finish(), rejected::set);
					assertEquals(503, rejected.get().getMarshaledResponse().getStatusCode());
					assertEquals(1, resource.queuedCalls.get());
				}
			}
		} finally {
			resource.release.countDown(); executor.shutdownNow(); assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
		}
	}

	private static final class Decorator implements HttpServer {
		private final HttpServer delegate;
		private final boolean owning;
		private RequestHandler upstream;
		private final AtomicReference<Request> lastQueued = new AtomicReference<>();
		Decorator(HttpServer delegate, boolean owning) { this.delegate = delegate; this.owning = owning; }
		@Override public TransportIdentity getTransportIdentity() { return this.delegate.getTransportIdentity(); }
		@Override public TransportRuntime attach(HttpTransportAttachmentContext context, StartupContext startup) {
			this.upstream = context.getAdmissionFencedRequestHandler();
			RequestHandler wrapped = (request, consumer) -> {
				if (request.getPath().equals("/queued")) this.lastQueued.set(request);
				this.upstream.handleRequest(request.copy().finish(), result -> {
					if (request.getPath().equals("/fallback") && result.getMarshaledResponse().getStatusCode() == 404)
						this.upstream.handleRequest(request.copy().path("/queued").finish(), consumer);
					else consumer.accept(result);
				});
			};
			if (!this.owning) return context.attachTransparentDelegate(this.delegate, wrapped);
			TransportRuntime child = context.attachTerminationOwningDelegate(this.delegate, wrapped).getTransportRuntime();
			TransportTerminationSignal signal = context.getTransportTerminationSignal();
			return new TransportRuntime() {
				@Override public void start(StartupContext startup) { child.start(startup); }
				@Override public void shutdownGracefully(ShutdownContext shutdown) { child.shutdownGracefully(shutdown); signal.signalTerminated(); }
				@Override public void shutdownForcibly(ShutdownContext shutdown) { child.shutdownForcibly(shutdown); signal.signalTerminated(); }
			};
		}
	}

	public static final class Resource {
		private final CountDownLatch entered = new CountDownLatch(1);
		private final CountDownLatch release = new CountDownLatch(1);
		private final AtomicInteger queuedCalls = new AtomicInteger();
		@GET("/block") public String block() throws InterruptedException { this.entered.countDown(); assertTrue(this.release.await(8, TimeUnit.SECONDS)); return "finished"; }
		@GET("/queued") public String queued() { this.queuedCalls.incrementAndGet(); return "queued"; }
	}
	private static Socket open(int port, String path) throws Exception {
		Socket socket = new Socket("127.0.0.1", port); socket.setSoTimeout(5000);
		socket.getOutputStream().write(("GET " + path + " HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
		socket.getOutputStream().flush(); return socket;
	}
	private static String read(Socket socket) throws Exception { return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8); }
}
