package com.soklet;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;

class Round2MetricsAndUnparsedResponseTests {
	@Test
	void observedSseMetricsSurviveLaterHttpOnlyInitialization() {
		DefaultMetricsCollector collector = DefaultMetricsCollector.defaultInstance();
		collector.didAcceptConnection(ServerType.SSE, null);
		Assertions.assertTrue(collector.snapshotText(MetricsCollector.SnapshotTextOptions.fromMetricsFormat(MetricsCollector.MetricsFormat.PROMETHEUS)).orElseThrow().contains("soklet_sse_connections_accepted_total 1"));
		collector.initialize(SokletConfig.withHttpServer(HttpServer.fromPort(8080)).build());
		Assertions.assertTrue(collector.snapshotText(MetricsCollector.SnapshotTextOptions.fromMetricsFormat(MetricsCollector.MetricsFormat.PROMETHEUS)).orElseThrow().contains("soklet_sse_connections_accepted_total 1"));
	}

	@Test
	void unparsedResponsePreservesSameNameValueOrderAcrossCaseVariants() {
		var headers = new java.util.LinkedHashMap<String, List<String>>();
		headers.put("X-Order", List.of("z", "a"));
		headers.put("x-order", List.of("y", "b"));
		var prepared = UnparsedRequestResponseSupport.prepare(MarshaledResponse.withStatusCode(400).headers(headers).build());
		Assertions.assertEquals(List.of("z", "a", "y", "b"), prepared.response().headers().stream()
				.filter(header -> header.name().equalsIgnoreCase("X-Order")).map(header -> header.value()).toList());
	}
}
