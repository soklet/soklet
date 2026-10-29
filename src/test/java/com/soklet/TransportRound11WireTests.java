package com.soklet;

import com.soklet.annotation.POST;
import com.soklet.annotation.SseEventSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(60)
class TransportRound11WireTests {
	@Test void knownOversizedContentLengthIsRejectedBefore100Continue() throws Exception {
		int port = TestSupport.findFreePort();
		HttpServer server = HttpServer.withPort(port).host("127.0.0.1")
				.maximumRequestSizeInBytes(1024).maximumRequestBodySizeInBytes(1024).build();
		try (Soklet app = Soklet.fromConfig(SokletConfig.withHttpServer(server)
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(Upload.class)))
				.lifecyclePolicy(policy()).lifecycleObserver(quiet()).build())) {
			app.start();
			try (Socket client = new Socket("127.0.0.1", port)) {
				client.setSoTimeout(2000);
				client.getOutputStream().write(("POST /upload HTTP/1.1\r\nHost: localhost\r\nContent-Length: 1024\r\n"
						+ "Expect: 100-continue\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
				String status = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII)).readLine();
				assertTrue(status.startsWith("HTTP/1.1 413"), status);
			}
		}
	}

	@Test void rejectedRewrittenHandshakeReportsEffectiveRequestAndRoute() throws Exception {
		int port = TestSupport.findFreePort();
		AtomicReference<Request> observedRequest = new AtomicReference<>();
		AtomicReference<ResourceMethod> observedMethod = new AtomicReference<>();
		CountDownLatch observed = new CountDownLatch(1);
		SseServer server = SseServer.withPort(port).host("127.0.0.1").build();
		RequestInterceptor rewrite = new RequestInterceptor() {
			@Override public void wrapRequest(ServerType type, Request request, Consumer<Request> process) {
				process.accept(request.copy().path("/reject").finish());
			}
		};
		LifecycleObserver observer = new LifecycleObserver() {
			@Override public void didReceiveLogEvent(LogEvent event) { }
			@Override public void didFailToEstablishSseConnection(Request request, ResourceMethod method,
					SseConnection.HandshakeFailureReason reason, Throwable cause) {
				observedRequest.set(request); observedMethod.set(method); observed.countDown();
			}
		};
		try (Soklet app = Soklet.fromConfig(SokletConfig.withSseServer(server)
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(Rejection.class)))
				.requestInterceptor(rewrite).lifecycleObserver(observer).lifecyclePolicy(policy()).build())) {
			app.start();
			try (Socket client = new Socket("127.0.0.1", port)) {
				client.setSoTimeout(2000);
				client.getOutputStream().write("GET /v1/reject HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
				assertTrue(new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII)).readLine().startsWith("HTTP/1.1 403"));
				assertTrue(observed.await(2, TimeUnit.SECONDS));
				assertEquals("/reject", observedRequest.get().getPath());
				assertNotNull(observedMethod.get());
				assertEquals("/reject", observedMethod.get().getResourcePathDeclaration().getPath());
			}
		}
	}

	@Test void sseBindFailureRetainsItsDiagnosableCauseInTheParticipantResult() throws Exception {
		try (ServerSocket occupied = new ServerSocket()) {
			occupied.bind(new java.net.InetSocketAddress("127.0.0.1", 0));
			SseServer server = SseServer.withPort(occupied.getLocalPort()).host("127.0.0.1").build();
			try (Soklet app = Soklet.fromConfig(SokletConfig.withSseServer(server)
					.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(Rejection.class)))
					.lifecyclePolicy(policy()).lifecycleObserver(quiet()).build())) {
				SokletStartupException failure = assertThrows(SokletStartupException.class, app::start);
				assertTrue(failure.getShutdownResult().getShutdownComponentResult(ShutdownComponentType.SSE)
						.orElseThrow().getThrowables().stream().anyMatch(java.net.BindException.class::isInstance));
			}
		}
	}

	public static class Upload { @POST("/upload") public Response upload() { return Response.fromStatusCode(204); } }
	public static class Rejection { @SseEventSource("/reject") public SseHandshakeResult reject() { return SseHandshakeResult.rejectWithResponse(Response.fromStatusCode(403)); } }
	private static LifecycleObserver quiet() { return new LifecycleObserver() { @Override public void didReceiveLogEvent(LogEvent event) { } }; }
	private static LifecyclePolicy policy() {
		return LifecyclePolicy.builder().startupTimeout(Duration.ofSeconds(5)).startupCancelationTimeout(Duration.ofSeconds(2))
				.gracefulShutdownTimeout(Duration.ofSeconds(2)).forcedShutdownTimeout(Duration.ofSeconds(1)).build();
	}
}
