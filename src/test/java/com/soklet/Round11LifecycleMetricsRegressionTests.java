package com.soklet;

import com.soklet.annotation.GET;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(60)
class Round11LifecycleMetricsRegressionTests {
	public static class Routes {
		@GET("/test") public String get() { return "ok"; }
	}

	@Test
	void preDispatchWrapperFailuresReachBothFinishObservers() {
		for (boolean omitDispatch : List.of(false, true)) {
			for (ServerType type : List.of(ServerType.HTTP, ServerType.SSE)) {
				var observation = new ArrayList<Throwable>();
				var metrics = new ArrayList<Throwable>();
				var failure = new IllegalStateException("wrapper failed");
				SimulatorConfig config = config().requestInterceptor(new RequestInterceptor() {
					@Override public void wrapRequest(ServerType serverType, Request request, Consumer<Request> processor) {
						if (!omitDispatch) throw failure;
					}
				}).lifecycleObserver(new LifecycleObserver() {
					@Override public void didReceiveLogEvent(LogEvent event) { }
					@Override public void didFinishRequestHandling(ServerType serverType, Request request, ResourceMethod method,
							MarshaledResponse response, Duration duration, List<Throwable> throwables) {
						observation.addAll(throwables);
					}
				}).metricsCollector(new MetricsCollector() {
					@Override public void didFinishRequestHandling(ServerType serverType, Request request, ResourceMethod method,
							MarshaledResponse response, Duration duration, List<Throwable> throwables) {
						metrics.addAll(throwables);
					}
				}).build();
				SokletSimulator.run(config, simulator -> {
					Request request = Request.fromPath(HttpMethod.GET, "/test");
					MarshaledResponse response = type == ServerType.HTTP
							? simulator.performHttpRequest(request).getMarshaledResponse()
							: assertInstanceOf(SseRequestResult.RequestFailed.class, simulator.performSseRequest(request))
									.getHttpRequestResult().getMarshaledResponse();
					assertEquals(500, response.getStatusCode());
					assertEquals(1, observation.size());
					assertEquals(observation, metrics);
					if (!omitDispatch) assertSame(failure, observation.getFirst());
				});
			}
		}
	}

	@Test
	void sseSimulatorProjectsRejectedResponsesLikeTheFiniteWireSerializer() {
		for (HttpMethod method : List.of(HttpMethod.GET, HttpMethod.HEAD)) {
			for (int status : List.of(204, 205, 304, 401)) {
				SokletSimulator.run(config().requestInterceptor(new RequestInterceptor() {
					@Override public void interceptRequest(ServerType serverType, Request request, ResourceMethod resourceMethod,
							Function<Request, MarshaledResponse> generator, Consumer<MarshaledResponse> writer) {
						writer.accept(MarshaledResponse.withStatusCode(status).body(new byte[] {1, 2, 3})
								.headers(Map.of("Content-Length", List.of("999"), "Connection", List.of("X-Hop, Date"),
										"Date", List.of("discard this nominated hop header"),
										"X-Hop", List.of("private"), "Transfer-Encoding", List.of("chunked"))).build());
					}
				}).build(), simulator -> {
					MarshaledResponse response = assertInstanceOf(SseRequestResult.RequestFailed.class,
							simulator.performSseRequest(Request.fromPath(method, "/test"))).getHttpRequestResult().getMarshaledResponse();
					assertEquals(status, response.getStatusCode());
					assertEquals(method == HttpMethod.GET && status == 401 ? 3 : 0, response.getBodyLength());
					assertEquals(List.of("close"), response.getHeaders().get("Connection"));
					assertFalse(response.getHeaders().containsKey("X-Hop"));
					assertFalse(response.getHeaders().containsKey("Transfer-Encoding"));
					assertDoesNotThrow(() -> java.time.ZonedDateTime.parse(response.getHeaders().get("Date").getFirst(),
							java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME));
					if (method == HttpMethod.HEAD || status == 204 || status == 304)
						assertFalse(response.getHeaders().containsKey("Content-Length"));
					else assertEquals(List.of(status == 205 ? "0" : "3"), response.getHeaders().get("Content-Length"));
				});
			}
		}
	}

	@Test
	void metricsTextPreservesCarriageReturnsWithoutInvalidEscapeSequences() throws Exception {
		var collector = DefaultMetricsCollector.defaultInstance();
		var request = Request.fromRawUrl(HttpMethod.GET, "/cr/a%0Db");
		var method = ResourceMethod.fromComponents(HttpMethod.GET, ResourcePathDeclaration.fromPath("/cr/a\rb"),
				Routes.class.getMethod("get"), false);
		collector.didStartRequestHandling(ServerType.HTTP, request, method);
		collector.didFinishRequestHandling(ServerType.HTTP, request, method, MarshaledResponse.fromStatusCode(200), Duration.ofMillis(1), List.of());
		for (var format : MetricsCollector.MetricsFormat.values()) {
			String text = collector.snapshotText(MetricsCollector.SnapshotTextOptions.fromMetricsFormat(format)).orElseThrow();
			assertTrue(text.contains("route=\"/cr/a\rb\""));
			assertFalse(text.contains("route=\"/cr/a\\rb\""));
		}
	}

	@Test
	void histogramDiagnosticsUseAsciiDigitsInEveryLocale() {
		Locale original = Locale.getDefault();
		try {
			Locale.setDefault(Locale.forLanguageTag("ar-EG"));
			String text = new MetricsCollector.HistogramSnapshot(List.of(Long.MAX_VALUE), List.of(2L),
					2L, 1234572D, 5L, 1234567L).toString();
			assertTrue(text.contains("count=2, min=5, max=1234567, sum=1234572.0"));
		} finally {
			Locale.setDefault(original);
		}
	}

	private static SimulatorConfig.Builder config() {
		return SimulatorConfig.builder().httpServer().sseServer()
				.resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(Routes.class)))
				.lifecyclePolicy(LifecyclePolicy.builder().startupTimeout(Duration.ofSeconds(1))
						.startupCancelationTimeout(Duration.ofMillis(100)).gracefulShutdownTimeout(Duration.ofMillis(100))
						.forcedShutdownTimeout(Duration.ofMillis(100)).build())
				.lifecycleObserver(new LifecycleObserver() { @Override public void didReceiveLogEvent(LogEvent event) { } });
	}
}
